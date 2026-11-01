package com.kaizen.logextractor;

import com.github.junrar.Archive;
import com.github.junrar.rarfile.FileHeader;
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZFile;
import org.apache.commons.compress.archivers.zip.Zip64Mode;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.apache.commons.compress.archivers.zip.ZipSplitReadOnlySeekableByteChannel;
import org.apache.commons.compress.utils.MultiReadOnlySeekableByteChannel;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.SeekableByteChannel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Streaming, path-safe archive filtering. Archive data is never loaded fully into memory. */
final class ArchiveProcessor {
    static final int MODE_DEFAULT = 0;
    static final long MAX_INPUT_BYTES = 64L * 1024L * 1024L * 1024L;
    static final long MAX_EXPANDED_BYTES = 64L * 1024L * 1024L * 1024L;
    private static final int MAX_ENTRIES = 100_000;
    private static final int BUFFER_SIZE = 128 * 1024;
    private static final int SEVEN_Z_MEMORY_LIMIT_KIB = 128 * 1024;

    private static final Pattern RAR_PART = Pattern.compile("^(.*)\\.part(\\d+)\\.rar$", Pattern.CASE_INSENSITIVE);
    private static final Pattern SEVEN_Z_PART = Pattern.compile("^(.*\\.7z)\\.(\\d{3,})$", Pattern.CASE_INSENSITIVE);
    private static final Pattern RAW_ZIP_PART = Pattern.compile("^(.*\\.zip)\\.(\\d{3,})$", Pattern.CASE_INSENSITIVE);
    private static final Pattern SPLIT_ZIP_DISK = Pattern.compile("^(.*)\\.z(\\d{2,3})$", Pattern.CASE_INSENSITIVE);
    private static final Pattern OLD_RAR_PART = Pattern.compile("^(.*)\\.r(\\d{2,3})$", Pattern.CASE_INSENSITIVE);


    private ArchiveProcessor() { }

    enum ArchiveType { ZIP, JAR, RAR, SEVEN_Z }

    static final class Result {
        final String archiveType;
        final int scannedEntries;
        final int keptFiles;
        final int skippedEntries;
        final int sourceParts;

        Result(String archiveType, int scannedEntries, int keptFiles, int skippedEntries, int sourceParts) {
            this.archiveType = archiveType;
            this.scannedEntries = scannedEntries;
            this.keptFiles = keptFiles;
            this.skippedEntries = skippedEntries;
            this.sourceParts = sourceParts;
        }
    }

    static final class NoMatchesException extends IOException {
        NoMatchesException(String message) { super(message); }
    }

    static ArchiveType detectType(File file, String originalName) throws IOException {
        if (file == null || !file.isFile() || file.length() < 4) {
            throw new IOException("Seçilen dosya boş veya okunamıyor.");
        }
        String name = baseName(originalName).toLowerCase(Locale.ROOT);
        boolean zipName = name.endsWith(".zip") || name.endsWith(".jar")
                || RAW_ZIP_PART.matcher(name).matches() || SPLIT_ZIP_DISK.matcher(name).matches();
        boolean rarName = name.endsWith(".rar") || OLD_RAR_PART.matcher(name).matches();
        boolean sevenZName = name.endsWith(".7z") || SEVEN_Z_PART.matcher(name).matches();
        byte[] header = new byte[8];
        int length = 0;
        try (InputStream input = new BufferedInputStream(new FileInputStream(file))) {
            while (length < header.length) {
                int read = input.read(header, length, header.length - length);
                if (read < 0) break;
                if (read > 0) length += read;
            }
        }
        if (zipName && length >= 4 && isZipSignature(header)) {
            return name.endsWith(".jar") ? ArchiveType.JAR : ArchiveType.ZIP;
        }
        if (rarName && length >= 7 && isRarSignature(header, length)) return ArchiveType.RAR;
        if (sevenZName && length >= 6 && isSevenZSignature(header)) return ArchiveType.SEVEN_Z;

        // A later split volume does not necessarily begin with the container signature.
        // Infer its format from the canonical volume name and let the archive reader
        // determine whether the selected range contains enough data to be readable.
        if (isLaterNumberedVolume(name, RAR_PART, 1)) return ArchiveType.RAR;
        if (isLaterNumberedVolume(name, SEVEN_Z_PART, 1)) return ArchiveType.SEVEN_Z;
        if (isLaterNumberedVolume(name, RAW_ZIP_PART, 1)) return ArchiveType.ZIP;
        throw new IOException("Dosya uzantısı ile arşiv imzası uyuşmuyor veya arşiv eksik. ZIP, JAR, RAR ve 7Z kabul edilir.");
    }

    interface ProgressListener {
        boolean isCancelled();
        void onProgress(Progress progress);
    }

    static final class Progress {
        final int percent;
        final String phase;
        final String currentEntry;
        final int scannedEntries;
        final int keptFiles;
        final int skippedEntries;

        Progress(int percent, String phase, String currentEntry,
                 int scannedEntries, int keptFiles, int skippedEntries) {
            this.percent = percent;
            this.phase = phase;
            this.currentEntry = currentEntry;
            this.scannedEntries = scannedEntries;
            this.keptFiles = keptFiles;
            this.skippedEntries = skippedEntries;
        }
    }

    static final class OperationCancelledException extends IOException {
        OperationCancelledException() { super("İşlem kullanıcı tarafından iptal edildi."); }
    }

    private static final ProgressListener NO_PROGRESS = new ProgressListener() {
        @Override public boolean isCancelled() { return false; }
        @Override public void onProgress(Progress progress) { }
    };

    /**
     * Where kept entries are written. The ZIP sink recreates parent directories; the card sink
     * parses payload bytes into pipe-separated rows, so it needs no directory entries at all.
     */
    private interface EntrySink {
        void parentDirectories(String path, Set<String> directories, Set<String> files) throws IOException;
        OutputStream beginEntry(String path, long size, long time) throws IOException;
        /** Closes the entry. Returns true when the entry actually produced output. */
        boolean endEntry() throws IOException;
    }

    static Result filter(File input, String originalName, int mode, OutputStream destination) throws Exception {
        return filter(input, originalName, mode, destination, Collections.singletonList(input), null);
    }

    /** candidates must contain the selected file and, when present, all sibling volumes, with original names. */
    static Result filter(File input, String originalName, int mode, OutputStream destination,
                         List<File> candidates) throws Exception {
        return filter(input, originalName, mode, destination, candidates, null);
    }

    /** Streaming filter with cooperative cancellation and byte-weighted entry progress. */
    static Result filter(File input, String originalName, int mode, OutputStream destination,
                         List<File> candidates, ProgressListener listener) throws Exception {
        if (mode != MODE_DEFAULT) {
            throw new IllegalArgumentException("Bilinmeyen filtre modu.");
        }
        if (destination == null) throw new IOException("Çıktı akışı açılamadı.");
        if (input == null || candidates == null || candidates.isEmpty()) throw new IOException("Arşiv parçaları bulunamadı.");
        ProgressTracker progress = new ProgressTracker(listener == null ? NO_PROGRESS : listener);
        progress.checkCancelled();
        List<String> candidateNames = new ArrayList<>();
        Map<String, File> filesByName = new HashMap<>();
        for (File candidate : candidates) {
            if (candidate == null || !candidate.isFile()) continue;
            String name = candidate.getName();
            candidateNames.add(name);
            filesByName.put(name.toLowerCase(Locale.ROOT), candidate);
        }
        if (!filesByName.containsKey(baseName(originalName).toLowerCase(Locale.ROOT))) {
            filesByName.put(baseName(originalName).toLowerCase(Locale.ROOT), input);
            candidateNames.add(baseName(originalName));
        }
        List<String> resolvedNames = selectVolumeNames(candidateNames, baseName(originalName));
        List<File> volumes = new ArrayList<>();
        long totalInputBytes = 0;
        for (String name : resolvedNames) {
            File volume = filesByName.get(name.toLowerCase(Locale.ROOT));
            if (volume == null || !volume.isFile()) throw new IOException("Arşiv parçası eksik: " + name);
            long length = volume.length();
            if (length < 0 || totalInputBytes > MAX_INPUT_BYTES - length) {
                throw new IOException("Arşiv parçalarının toplamı 64 GiB sınırını aşıyor.");
            }
            totalInputBytes += length;
            volumes.add(volume);
        }
        if (volumes.isEmpty()) throw new IOException("İşlenecek arşiv bulunamadı.");
        ArchiveType type = detectType(primaryVolume(volumes, baseName(originalName)), primaryName(volumes, baseName(originalName)));
        Stats stats = new Stats();
        ZipArchiveOutputStream zipOutput = new ZipArchiveOutputStream(destination);
        zipOutput.setUseZip64(Zip64Mode.AsNeeded);
        EntrySink sink = new ZipSink(zipOutput);
        try (ZipArchiveOutputStream closeableOutput = zipOutput) {
            try {
                switch (type) {
                    case ZIP:
                    case JAR:
                        filterZip(volumes, baseName(originalName), mode, sink, stats, progress);
                        break;
                    case RAR:
                        filterRar(volumes.get(0), mode, sink, stats, hasMissingLeadingVolume(volumes), progress);
                        break;
                    case SEVEN_Z:
                        filterSevenZ(volumes, mode, sink, stats, progress);
                        break;
                    default:
                        throw new IOException("Desteklenmeyen arşiv türü.");
                }
            } catch (Exception e) {
                if (e instanceof OperationCancelledException) {
                    throw e;
                }
                if (hasMissingLeadingVolume(volumes)) {
                    throw new IOException("Seçilen parça(lar) tanındı ve taranmaya çalışıldı, ancak arşiv bu parçalardan tek başına okunamıyor. İçerik başlangıç parçasına veya önceki parçalara bağlı olabilir; varsa eksik önceki parçaları da seç. Ayrıntı: "
                            + safeMessage(e), e);
                }
                throw e;
            }
        }
        if (stats.kept == 0) {
            throw new NoMatchesException("Arşivde korunabilecek bir dosya bulunamadı. Boş çıktı kaydedilmedi.");
        }
        progress.finish(stats);
        return new Result(type.name(), stats.scanned, stats.kept, stats.skipped, volumes.size());
    }

    /** Determines the required volume names and rejects gaps before large files are staged. */
    static List<String> selectVolumeNames(List<String> candidateNames, String selectedName) throws IOException {
        if (selectedName == null || selectedName.trim().isEmpty()) throw new IOException("Arşiv dosya adı okunamadı.");
        String selected = baseName(selectedName);
        Map<String, String> available = new LinkedHashMap<>();
        if (candidateNames != null) {
            for (String name : candidateNames) {
                if (name != null && !name.trim().isEmpty()) available.put(name.toLowerCase(Locale.ROOT), baseName(name));
            }
        }
        available.putIfAbsent(selected.toLowerCase(Locale.ROOT), selected);
        String lower = selected.toLowerCase(Locale.ROOT);
        Matcher match = RAR_PART.matcher(selected);
        if (match.matches()) {
            String prefix = match.group(1);
            return numberedSet(available, Pattern.compile("^" + Pattern.quote(prefix) + "\\.part(\\d+)\\.rar$", Pattern.CASE_INSENSITIVE), 1, selected);
        }
        match = SEVEN_Z_PART.matcher(selected);
        if (match.matches()) {
            String prefix = match.group(1);
            return numberedSet(available, Pattern.compile("^" + Pattern.quote(prefix) + "\\.(\\d{3,})$", Pattern.CASE_INSENSITIVE), 1, selected);
        }
        match = RAW_ZIP_PART.matcher(selected);
        if (match.matches()) {
            String prefix = match.group(1);
            return numberedSet(available, Pattern.compile("^" + Pattern.quote(prefix) + "\\.(\\d{3,})$", Pattern.CASE_INSENSITIVE), 1, selected);
        }
        match = SPLIT_ZIP_DISK.matcher(selected);
        if (match.matches() || lower.endsWith(".zip")) {
            String prefix = match.matches() ? match.group(1) : selected.substring(0, selected.length() - 4);
            List<NumberedName> disks = findNumbered(available, Pattern.compile("^" + Pattern.quote(prefix) + "\\.z(\\d{2,3})$", Pattern.CASE_INSENSITIVE), 1);
            String finalName = findName(available, prefix + ".zip");
            if (!disks.isEmpty() || match.matches()) {
                if (finalName == null) throw new IOException("Bölünmüş ZIP'in son .zip parçası bulunamadı: " + prefix + ".zip");
                ensureContiguous(disks, 1, "ZIP parçası");
                List<String> result = namesFrom(disks);
                result.add(finalName);
                if (result.size() == 1) return Collections.singletonList(finalName);
                return result;
                }
        }
        match = OLD_RAR_PART.matcher(selected);
        if (match.matches() || lower.endsWith(".rar")) {
            String prefix = match.matches() ? match.group(1) : selected.substring(0, selected.length() - 4);
            String first = findName(available, prefix + ".rar");
            if (first == null) throw new IOException("Bölünmüş RAR için ilk parça bulunamadı: " + prefix + ".rar");
            List<NumberedName> disks = findNumbered(available, Pattern.compile("^" + Pattern.quote(prefix) + "\\.r(\\d{2,3})$", Pattern.CASE_INSENSITIVE), 0);
            ensureContiguous(disks, 0, "RAR parçası");
            List<String> result = new ArrayList<>();
            result.add(first);
            result.addAll(namesFrom(disks));
            return result;
        }
        return Collections.singletonList(selected);
    }

    private static File primaryVolume(List<File> volumes, String selectedName) {
        String lower = selectedName.toLowerCase(Locale.ROOT);
        if (SPLIT_ZIP_DISK.matcher(selectedName).matches() || lower.endsWith(".zip")) {
            for (File file : volumes) if (file.getName().toLowerCase(Locale.ROOT).endsWith(".z01")) return file;
            for (File file : volumes) if (file.getName().toLowerCase(Locale.ROOT).endsWith(".zip")) return file;
        }
        return volumes.get(0);
    }

    private static String primaryName(List<File> volumes, String selectedName) {
        return primaryVolume(volumes, selectedName).getName();
    }

    private static List<String> numberedSet(Map<String, String> available, Pattern pattern,
                                            int firstNumber, String selected) throws IOException {
        List<NumberedName> numbered = findNumbered(available, pattern, firstNumber);
        if (numbered.isEmpty()) throw new IOException("Seçilen arşiv parçası bulunamadı.");
        // Do not fail merely because an earlier volume is absent. Begin with the
        // earliest available volume and scan the selected range in numeric order.
        // Gaps inside the available range are still rejected because those pieces
        // would make the byte stream discontinuous.
        int firstAvailable = numbered.get(0).number;
        ensureContiguous(numbered, firstAvailable, "Arşiv parçası");
        List<String> result = namesFrom(numbered);
        if (!containsIgnoreCase(result, selected)) throw new IOException("Seçilen parça bu arşiv grubuna ait değil: " + selected);
        return result;
    }

    private static boolean isLaterNumberedVolume(String name, Pattern pattern, int firstNumber) {
        Matcher matcher = pattern.matcher(name);
        if (!matcher.matches()) return false;
        try {
            return Integer.parseInt(matcher.group(2)) > firstNumber;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static boolean hasMissingLeadingVolume(List<File> volumes) {
        if (volumes == null || volumes.isEmpty()) return false;
        String name = volumes.get(0).getName().toLowerCase(Locale.ROOT);
        return isLaterNumberedVolume(name, RAR_PART, 1)
                || isLaterNumberedVolume(name, SEVEN_Z_PART, 1)
                || isLaterNumberedVolume(name, RAW_ZIP_PART, 1);
    }

    /**
     * A RAR entry marked split-before starts in an unavailable earlier volume when processing
     * begins mid-set. Such an entry must be omitted before creating an output ZIP entry.
     */
    static boolean shouldSkipUnavailableRarContinuation(boolean missingLeadingVolume,
                                                         boolean splitBefore, boolean rar5Entry) {
        return missingLeadingVolume && splitBefore && rar5Entry;
    }

    /** Once a missing RAR5 continuation is skipped, solid entries need its unavailable decoder state. */
    static boolean shouldSkipRarEntryWithUnavailableSolidContext(boolean missingSolidContext, boolean solid) {
        return missingSolidContext && solid;
    }

    private static String safeMessage(Exception error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty()
                ? error.getClass().getSimpleName() : message;
    }

    private static List<NumberedName> findNumbered(Map<String, String> available, Pattern pattern,
                                                    int firstNumber) throws IOException {
        List<NumberedName> result = new ArrayList<>();
        for (String name : available.values()) {
            Matcher matcher = pattern.matcher(name);
            if (!matcher.matches()) continue;
            try {
                int number = Integer.parseInt(matcher.group(1));
                if (number >= firstNumber) result.add(new NumberedName(number, name));
            } catch (NumberFormatException e) {
                throw new IOException("Arşiv parça numarası çok büyük: " + name, e);
            }
        }
        result.sort(Comparator.comparingInt(value -> value.number));
        return result;
    }
    private static List<String> namesFrom(List<NumberedName> values) {
        List<String> result = new ArrayList<>();
        for (NumberedName value : values) result.add(value.name);
        return result;
    }

    private static void ensureContiguous(List<NumberedName> values, int start, String label) throws IOException {
        int expected = start;
        for (NumberedName value : values) {
            if (value.number != expected) {
                throw new IOException(label + " eksik: beklenen " + String.format(Locale.ROOT, "%03d", expected)
                        + " numaralı parça, bulunan " + value.name + ". Tüm parçaları aynı klasörden seç.");
            }
            expected++;
        }
    }

    private static String findName(Map<String, String> available, String wanted) {
        return available.get(wanted.toLowerCase(Locale.ROOT));
    }

    private static boolean containsIgnoreCase(List<String> values, String selected) {
        for (String value : values) if (value.equalsIgnoreCase(selected)) return true;
        return false;
    }

    private static void filterZip(List<File> volumes, String originalName, int mode,
                                  EntrySink sink, Stats stats, ProgressTracker progress) throws IOException {
        SeekableByteChannel channel = null;
        ZipFile zip = null;
        try {
            String lower = originalName.toLowerCase(Locale.ROOT);
            boolean classicSplit = volumes.size() > 1
                    && volumes.get(volumes.size() - 1).getName().toLowerCase(Locale.ROOT).endsWith(".zip")
                    && volumes.get(0).getName().toLowerCase(Locale.ROOT).matches(".*\\.z\\d{2,3}");
            boolean rawParts = RAW_ZIP_PART.matcher(lower).matches() && volumes.size() > 1;
            if (classicSplit) {
                channel = ZipSplitReadOnlySeekableByteChannel.forFiles(volumes.toArray(new File[0]));
                zip = ZipFile.builder().setSeekableByteChannel(channel).setMaxNumberOfDisks(volumes.size()).get();
            } else if (rawParts) {
                channel = MultiReadOnlySeekableByteChannel.forFiles(volumes.toArray(new File[0]));
                zip = ZipFile.builder().setSeekableByteChannel(channel).get();
            } else {
                zip = ZipFile.builder().setFile(volumes.get(0)).get();
            }
            Set<String> directories = new HashSet<>();
            Set<String> files = new HashSet<>();
            long[] expanded = {0L};
            long totalWork = 0L;
            Enumeration<ZipArchiveEntry> countEntries = zip.getEntries();
            while (countEntries.hasMoreElements()) {
                progress.checkCancelled();
                ZipArchiveEntry entry = countEntries.nextElement();
                progress.inspectEntry(entry.getName(), stats);
                totalWork = addWork(totalWork, progressWorkSize(entry.getName(), entry.getSize(),
                        entry.isDirectory() || entry.isUnixSymlink(), mode));
            }
            progress.setTotalWork(totalWork);
            Enumeration<ZipArchiveEntry> entries = zip.getEntries();
            while (entries.hasMoreElements()) {
                progress.checkCancelled();
                ZipArchiveEntry entry = entries.nextElement();
                stats.scanned++;
                checkEntryLimit(stats.scanned);
                long size = entry.isDirectory() || entry.isUnixSymlink() ? 0L : entry.getSize();
                progress.startEntry(entry.getName(), progressWorkSize(entry.getName(), size,
                        entry.isDirectory() || entry.isUnixSymlink(), mode), stats);
                if (entry.isDirectory() || entry.isUnixSymlink()) {
                    stats.skipped++;
                    progress.skipCurrent(stats);
                    continue;
                }
                String path = safeEntryPath(entry.getName());
                if (path == null || !matchesMode(path, mode)) {
                    stats.skipped++;
                    progress.skipCurrent(stats);
                    continue;
                }
                if (files.contains(path) || directories.contains(path + "/")) {
                    stats.skipped++;
                    progress.skipCurrent(stats);
                    continue;
                }
                if (size < 0) throw new IOException("Arşiv girdisinin boyutu okunamadı: " + path);
                sink.parentDirectories(path, directories, files);
                files.add(path);
                OutputStream entryOutput = sink.beginEntry(path, size, entry.getTime());
                try (InputStream in = zip.getInputStream(entry)) {
                    copyLimited(in, entryOutput, expanded, progress, stats);
                }
                completeEntry(sink, stats, progress);
            }
        } finally {
            if (zip != null) zip.close();
            if (channel != null) channel.close();
        }
    }

    private static void filterRar(File primaryVolume, int mode, EntrySink sink,
                                  Stats stats, boolean missingLeadingVolume, ProgressTracker progress) throws Exception {
                                      Set<String> directories = new HashSet<>();
        Set<String> files = new HashSet<>();
        long[] expanded = {0L};
        Archive archive = new Archive(primaryVolume);
        try {
            if (archive.isPasswordProtected()) throw new IOException("Parola korumalı RAR arşivleri bu sürümde desteklenmiyor.");
            List<FileHeader> headers = archive.getFileHeaders();
            if (headers.size() > MAX_ENTRIES) throw new IOException("Arşiv 100.000 girdiden fazla içeriyor.");
            long totalWork = 0L;
            boolean sizingSolidContextUnavailable = false;
            for (FileHeader header : headers) {
                progress.checkCancelled();
                progress.inspectEntry(header.getFileName(), stats);
                long size = header.isDirectory() ? 0L : header.getFullUnpackSize();
                boolean rar5Entry = header.isRar5Container() || header.isRar5Family();
                boolean missingContinuation = shouldSkipUnavailableRarContinuation(
                        missingLeadingVolume, header.isSplitBefore(), rar5Entry);
                boolean unavailableSolidEntry = shouldSkipRarEntryWithUnavailableSolidContext(
                        sizingSolidContextUnavailable, header.isSolid());
                boolean excluded = header.isDirectory() || header.getRedirection() != null
                        || missingContinuation || unavailableSolidEntry;
                long weight = excluded ? 0L : progressWorkSize(header.getFileName(), size, false, mode);
                totalWork = addWork(totalWork, weight);
                if (header.isDirectory() || header.getRedirection() != null) {
                    // These entries are skipped before the solid-context state is changed below.
                } else if (missingContinuation) {
                    sizingSolidContextUnavailable = true;
                } else if (!header.isSolid()) {
                    sizingSolidContextUnavailable = false;
                }
            }
            progress.setTotalWork(totalWork);
            boolean unavailableRar5SolidContext = false;
            for (FileHeader header : headers) {
                progress.checkCancelled();
                stats.scanned++;
                checkEntryLimit(stats.scanned);
                long size = header.isDirectory() ? 0L : header.getFullUnpackSize();
                boolean rar5Entry = header.isRar5Container() || header.isRar5Family();
                boolean missingContinuation = shouldSkipUnavailableRarContinuation(
                        missingLeadingVolume, header.isSplitBefore(), rar5Entry);
                boolean unavailableSolidEntry = shouldSkipRarEntryWithUnavailableSolidContext(
                        unavailableRar5SolidContext, header.isSolid());
                String path = header.isDirectory() || header.getRedirection() != null
                        ? null : safeEntryPath(header.getFileName());
                boolean matches = path != null && matchesMode(path, mode);
                progress.startEntry(header.getFileName(),
                        missingContinuation || unavailableSolidEntry || !matches ? 0L : size, stats);
                if (header.isDirectory() || header.getRedirection() != null) {
                    stats.skipped++;
                    progress.skipCurrent(stats);
                    continue;
                }
                if (missingContinuation) {
                    // Junrar rejects RAR5 split-before entries when an earlier volume is absent.
                    // Skip before opening a ZIP entry, so no empty or partial file is written.
                    stats.skipped++;
                    progress.skipCurrent(stats);
                    unavailableRar5SolidContext = true;
                    continue;
                }
                if (unavailableSolidEntry) {
                    // A later solid entry depends on the skipped entry's decoder state. Skip until
                    // a non-solid entry resets that state; otherwise extraction can fail CRC checks.
                    stats.skipped++;
                    progress.skipCurrent(stats);
                    continue;
                }
                if (!header.isSolid()) unavailableRar5SolidContext = false;
                if (path == null || !matches) {
                    stats.skipped++;
                    progress.skipCurrent(stats);
                    continue;
                }
                if (files.contains(path) || directories.contains(path + "/")) {
                    stats.skipped++;
                    progress.skipCurrent(stats);
                    continue;
                }
                if (size < 0) throw new IOException("RAR girdisinin boyutu okunamadı: " + path);
                sink.parentDirectories(path, directories, files);
                files.add(path);
                OutputStream entryOutput = sink.beginEntry(path, size, 0L);
                archive.extractFile(header, new CountingOutputStream(entryOutput, expanded, progress, stats));
                completeEntry(sink, stats, progress);
            }
        } finally {
            archive.close();
        }
    }

    private static void filterSevenZ(List<File> volumes, int mode, EntrySink sink,
                                     Stats stats, ProgressTracker progress) throws IOException {
        SeekableByteChannel channel = null;
        SevenZFile sevenZ = null;
        try {
            SevenZFile.Builder builder;
            if (volumes.size() > 1) {
                channel = MultiReadOnlySeekableByteChannel.forFiles(volumes.toArray(new File[0]));
                builder = SevenZFile.builder().setSeekableByteChannel(channel);
                } else {
                builder = SevenZFile.builder().setFile(volumes.get(0));
            }
            // If an earlier 7z volume is absent, attempt Commons Compress' bounded
            // end-of-archive recovery. It may recover the file index from later pieces,
            // but cannot reconstruct payload bytes that are actually missing.
            sevenZ = builder.setMaxMemoryLimitKiB(SEVEN_Z_MEMORY_LIMIT_KIB)
                    .setTryToRecoverBrokenArchives(hasMissingLeadingVolume(volumes)).get();
            Set<String> directories = new HashSet<>();
            Set<String> files = new HashSet<>();
            long[] expanded = {0L};
            long totalWork = 0L;
            for (SevenZArchiveEntry metadata : sevenZ.getEntries()) {
                progress.checkCancelled();
                progress.inspectEntry(metadata.getName(), stats);
                totalWork = addWork(totalWork, progressWorkSize(metadata.getName(), metadata.getSize(),
                        metadata.isDirectory(), mode));
            }
            progress.setTotalWork(totalWork);
            SevenZArchiveEntry entry;
            while ((entry = sevenZ.getNextEntry()) != null) {
                progress.checkCancelled();
                stats.scanned++;
                checkEntryLimit(stats.scanned);
                long size = entry.isDirectory() ? 0L : entry.getSize();
                String path = entry.isDirectory() ? null : safeEntryPath(entry.getName());
                boolean matches = path != null && matchesMode(path, mode);
                progress.startEntry(entry.getName(), matches ? size : 0L, stats);
                if (entry.isDirectory()) {
                    stats.skipped++;
                    progress.skipCurrent(stats);
                    continue;
                }
                if (path == null || !matches) {
                    stats.skipped++;
                    progress.skipCurrent(stats);
                    continue;
                }
                if (files.contains(path) || directories.contains(path + "/")) {
                    stats.skipped++;
                    progress.skipCurrent(stats);
                    continue;
                }
                if (size < 0) throw new IOException("7Z girdisinin boyutu okunamadı: " + path);
                sink.parentDirectories(path, directories, files);
                files.add(path);
                long time = entry.getHasLastModifiedDate() ? entry.getLastModifiedDate().getTime() : 0L;
                OutputStream entryOutput = sink.beginEntry(path, size, time);
                copySevenZEntry(sevenZ, entryOutput, expanded, progress, stats);
                completeEntry(sink, stats, progress);
            }
        } finally {
            if (sevenZ != null) sevenZ.close();
            if (channel != null) channel.close();
        }
    }

    /** Shared tail of every kept entry: a sink may report that the entry produced no output. */
    private static void completeEntry(EntrySink sink, Stats stats, ProgressTracker progress) throws IOException {
        if (sink.endEntry()) {
            stats.kept++;
            progress.finishCurrent(stats);
        } else {
            stats.skipped++;
            progress.skipCurrent(stats);
        }
    }

    private static void copySevenZEntry(SevenZFile sevenZ, OutputStream out, long[] expanded,
                                        ProgressTracker progress, Stats stats) throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        int read;
        while (true) {
            progress.checkCancelled();
            read = sevenZ.read(buffer);
            if (read == -1) break;
            if (read == 0) continue;
            ensureExpandedCapacity(expanded[0], read);
            out.write(buffer, 0, read);
            expanded[0] += read;
            progress.advanceCurrent(read, stats);
        }
    }

    private static boolean isZipSignature(byte[] h) {
        return h[0] == 'P' && h[1] == 'K'
                && ((h[2] == 3 && h[3] == 4) || (h[2] == 5 && h[3] == 6) || (h[2] == 7 && h[3] == 8));
    }

    private static boolean isRarSignature(byte[] h, int length) {
        if (length >= 7 && h[0] == 0x52 && h[1] == 0x61 && h[2] == 0x72 && h[3] == 0x21
                && h[4] == 0x1A && h[5] == 0x07 && h[6] == 0x00) return true;
        return length >= 8 && h[0] == 0x52 && h[1] == 0x61 && h[2] == 0x72 && h[3] == 0x21
                && h[4] == 0x1A && h[5] == 0x07 && h[6] == 0x01 && h[7] == 0x00;
    }

    private static boolean isSevenZSignature(byte[] h) {
        return (h[0] & 0xFF) == 0x37 && (h[1] & 0xFF) == 0x7A && (h[2] & 0xFF) == 0xBC
                && (h[3] & 0xFF) == 0xAF && (h[4] & 0xFF) == 0x27 && (h[5] & 0xFF) == 0x1C;
    }
    private static String safeEntryPath(String raw) {
        if (raw == null || raw.isEmpty() || raw.indexOf('\0') >= 0) return null;
        String normal = raw.replace('\\', '/');
        if (normal.startsWith("/") || normal.matches("^[A-Za-z]:.*") || normal.endsWith("/")) return null;
        String[] parts = normal.split("/+");
        List<String> clean = new ArrayList<>();
        for (String part : parts) {
            if (part.isEmpty()) continue;
            if (part.equals(".") || part.equals("..")) return null;
            clean.add(part);
        }
        if (clean.isEmpty()) return null;
        StringBuilder result = new StringBuilder();
        for (String part : clean) {
            if (result.length() > 0) result.append('/');
            result.append(part);
        }
        return result.toString();
    }

    private static boolean matchesMode(String path, int mode) {
        return mode == MODE_DEFAULT && path != null;
    }

    private static void addParentDirectories(String filePath, EntrySink sink, Set<String> directories,
                                             Set<String> files) throws IOException {
        String[] parts = filePath.split("/");
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < parts.length - 1; i++) {
            if (current.length() > 0) current.append('/');
            current.append(parts[i]);
            String path = current.toString();
            if (files.contains(path)) throw new IOException("Arşivde çakışan dosya/klasör yolu bulundu: " + path);
            String directory = path + "/";
            if (directories.add(directory)) {
                sink.beginEntry(directory, 0L, 0L);
                sink.endEntry();
            }
        }
    }

    private static void copyLimited(InputStream in, OutputStream out, long[] expanded,
                                    ProgressTracker progress, Stats stats) throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        int read;
        while (true) {
            progress.checkCancelled();
            read = in.read(buffer);
            if (read == -1) break;
            if (read == 0) continue;
            ensureExpandedCapacity(expanded[0], read);
            out.write(buffer, 0, read);
            expanded[0] += read;
            progress.advanceCurrent(read, stats);
        }
    }

    private static void ensureExpandedCapacity(long current, int incoming) throws IOException {
        if (incoming < 0 || current > MAX_EXPANDED_BYTES - incoming) {
            throw new IOException("Açılan toplam dosya içeriği 64 GiB sınırını aşıyor; işlem güvenlik için durduruldu.");
        }
    }

    private static void checkEntryLimit(int scanned) throws IOException {
        if (scanned > MAX_ENTRIES) throw new IOException("Arşiv 100.000 girdiden fazla içeriyor; işlem güvenlik için durduruldu.");
    }

    private static String baseName(String value) {
        if (value == null) return "";
        String normalized = value.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        return slash >= 0 ? normalized.substring(slash + 1) : normalized;
    }

    private static long progressWorkSize(String name, long size, boolean excluded, int mode) {
        if (excluded) return 0L;
        String path = safeEntryPath(name);
        return path != null && matchesMode(path, mode) ? size : 0L;
    }

    private static long addWork(long current, long size) {
        long weight = size > 0 ? size : 1L;
        if (current > Long.MAX_VALUE - weight) return Long.MAX_VALUE;
        return current + weight;
    }

    private static final class ZipSink implements EntrySink {
        private final ZipArchiveOutputStream output;

        ZipSink(ZipArchiveOutputStream output) { this.output = output; }

        @Override public void parentDirectories(String path, Set<String> directories, Set<String> files) throws IOException {
            addParentDirectories(path, this, directories, files);
        }
        @Override public OutputStream beginEntry(String path, long size, long time) throws IOException {
            ZipArchiveEntry entry = new ZipArchiveEntry(path);
            entry.setSize(size);
            if (time > 0L) entry.setTime(time);
            output.putArchiveEntry(entry);
            return output;
        }

        @Override public boolean endEntry() throws IOException {
            output.closeArchiveEntry();
            return true;
        }
    }

    private static final class ProgressTracker {
        private final ProgressListener listener;
        private long totalWork = 1L;
        private long completedWork;
        private long currentWeight;
        private long currentBytes;
        private String currentEntry = "";

        ProgressTracker(ProgressListener listener) { this.listener = listener; }

        void checkCancelled() throws OperationCancelledException {
            if (Thread.currentThread().isInterrupted() || listener.isCancelled()) {
                throw new OperationCancelledException();
            }
        }

        void setTotalWork(long value) {
            totalWork = Math.max(1L, value);
        }

        void inspectEntry(String name, Stats stats) throws IOException {
            checkCancelled();
            currentEntry = name == null || name.trim().isEmpty() ? "(isimsiz girdi)" : name;
            report("Arşiv başlıkları inceleniyor", stats);
            currentEntry = "";
        }

        void startEntry(String name, long size, Stats stats) throws IOException {
            checkCancelled();
            currentEntry = name == null || name.trim().isEmpty() ? "(isimsiz girdi)" : name;
            currentWeight = size > 0 ? size : 1L;
            currentBytes = 0L;
            report("Girdi taranıyor", stats);
        }

        void advanceCurrent(long amount, Stats stats) throws IOException {
            checkCancelled();
            if (amount <= 0) return;
            long remaining = Math.max(0L, currentWeight - currentBytes);
            currentBytes += Math.min(remaining, amount);
            report("Dosya çıkarılıyor", stats);
        }

        void skipCurrent(Stats stats) throws IOException {
            completeCurrent("Girdi atlandı", stats);
        }

        void finishCurrent(Stats stats) throws IOException {
            completeCurrent("Dosya tamamlandı", stats);
        }

        private void completeCurrent(String phase, Stats stats) throws IOException {
            checkCancelled();
            if (completedWork > Long.MAX_VALUE - currentWeight) completedWork = Long.MAX_VALUE;
            else completedWork += currentWeight;
            currentWeight = 0L;
            currentBytes = 0L;
            report(phase, stats);
            currentEntry = "";
        }

        void finish(Stats stats) throws IOException {
            checkCancelled();
            completedWork = totalWork;
            currentWeight = 0L;
            currentBytes = 0L;
            report("Tamamlandı", stats);
        }

        private void report(String phase, Stats stats) {
            long current = completedWork;
            if (currentWeight > 0) {
                long partial = Math.min(currentWeight, currentBytes);
                current = current > Long.MAX_VALUE - partial ? Long.MAX_VALUE : current + partial;
            }
            int percent = totalWork <= 0 ? 0
                    : current >= totalWork ? 100
                    : (int) Math.max(0d, Math.min(100d, ((double) current / (double) totalWork) * 100d));
            listener.onProgress(new Progress(percent, phase, currentEntry,
                    stats.scanned, stats.kept, stats.skipped));
        }
    }

    private static final class NumberedName {
        final int number;
        final String name;
        NumberedName(int number, String name) { this.number = number; this.name = name; }
    }

    private static final class Stats { int scanned; int kept; int skipped; }

    private static final class CountingOutputStream extends OutputStream {
        private final OutputStream target;
        private final long[] total;
        private final ProgressTracker progress;
        private final Stats stats;
        CountingOutputStream(OutputStream target, long[] total, ProgressTracker progress, Stats stats) {
            this.target = target;
            this.total = total;
            this.progress = progress;
            this.stats = stats;
        }
        @Override public void write(int value) throws IOException {
            progress.checkCancelled();
            ensureExpandedCapacity(total[0], 1);
            target.write(value);
            total[0]++;
            progress.advanceCurrent(1, stats);
        }
        @Override public void write(byte[] bytes, int offset, int length) throws IOException {
            if (bytes == null) throw new NullPointerException("bytes");
            if (offset < 0 || length < 0 || length > bytes.length - offset) throw new IndexOutOfBoundsException("Invalid buffer range");
            progress.checkCancelled();
            ensureExpandedCapacity(total[0], length);
            target.write(bytes, offset, length);
            total[0] += length;
            progress.advanceCurrent(length, stats);
        }
        @Override public void flush() throws IOException { target.flush(); }
        @Override public void close() throws IOException { flush(); }
    }
}