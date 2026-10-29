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
    static final int MODE_CARDS = 1;
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

    static Result filter(File input, String originalName, int mode, OutputStream destination) throws Exception {
        return filter(input, originalName, mode, destination, Collections.singletonList(input));
    }

    /** candidates must contain the selected file and, when present, all sibling volumes, with original names. */
    static Result filter(File input, String originalName, int mode, OutputStream destination,
                         List<File> candidates) throws Exception {
        if (mode != MODE_DEFAULT && mode != MODE_CARDS) throw new IllegalArgumentException("Bilinmeyen filtre modu.");
        if (input == null || candidates == null || candidates.isEmpty()) throw new IOException("Arşiv parçaları bulunamadı.");
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
        try (ZipArchiveOutputStream output = new ZipArchiveOutputStream(destination)) {
            output.setUseZip64(Zip64Mode.AsNeeded);
            try {
                switch (type) {
                    case ZIP:
                    case JAR:
                        filterZip(volumes, baseName(originalName), mode, output, stats);
                        break;
                    case RAR:
                        filterRar(volumes.get(0), mode, output, stats);
                        break;
                    case SEVEN_Z:
                        filterSevenZ(volumes, mode, output, stats);
                        break;
                    default:
                        throw new IOException("Desteklenmeyen arşiv türü.");
                }
            } catch (Exception e) {
                if (hasMissingLeadingVolume(volumes)) {
                    throw new IOException("Seçilen parça(lar) tanındı ve taranmaya çalışıldı, ancak arşiv bu parçalardan tek başına okunamıyor. İçerik başlangıç parçasına veya önceki parçalara bağlı olabilir; varsa eksik önceki parçaları da seç. Ayrıntı: "
                            + safeMessage(e), e);
                }
                throw e;
            }
        }
        if (stats.kept == 0) {
            throw new NoMatchesException(mode == MODE_CARDS
                    ? "Dosya adında 'card' bulunan bir dosya bulunamadı. Boş çıktı kaydedilmedi."
                    : "Arşivde korunabilecek bir dosya bulunamadı. Boş çıktı kaydedilmedi.");
        }
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
                                  ZipArchiveOutputStream output, Stats stats) throws IOException {
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
            Enumeration<ZipArchiveEntry> entries = zip.getEntries();
            while (entries.hasMoreElements()) {
                ZipArchiveEntry entry = entries.nextElement();
                stats.scanned++;
                checkEntryLimit(stats.scanned);
                if (entry.isDirectory() || entry.isUnixSymlink()) {
                    stats.skipped++;
                    continue;
                }
                String path = safeEntryPath(entry.getName());
                if (path == null || !matchesMode(path, mode)) {
                    stats.skipped++;
                    continue;
                }
                if (files.contains(path) || directories.contains(path + "/")) {
                    stats.skipped++;
                    continue;
                }
                long size = entry.getSize();
                if (size < 0) throw new IOException("Arşiv girdisinin boyutu okunamadı: " + path);
                addParentDirectories(path, output, directories, files);
                files.add(path);
                ZipArchiveEntry outEntry = new ZipArchiveEntry(path);
                outEntry.setSize(size);
                if (entry.getTime() > 0) outEntry.setTime(entry.getTime());
                output.putArchiveEntry(outEntry);
                try (InputStream in = zip.getInputStream(entry)) {
                    copyLimited(in, output, expanded);
                }
                output.closeArchiveEntry();
                stats.kept++;
            }
        } finally {
            if (zip != null) zip.close();
            if (channel != null) channel.close();
        }
    }

    private static void filterRar(File primaryVolume, int mode, ZipArchiveOutputStream output,
                                  Stats stats) throws Exception {
        Set<String> directories = new HashSet<>();
        Set<String> files = new HashSet<>();
        long[] expanded = {0L};
        Archive archive = new Archive(primaryVolume);
        try {
            if (archive.isPasswordProtected()) throw new IOException("Parola korumalı RAR arşivleri bu sürümde desteklenmiyor.");
            List<FileHeader> headers = archive.getFileHeaders();
            if (headers.size() > MAX_ENTRIES) throw new IOException("Arşiv 100.000 girdiden fazla içeriyor.");
            for (FileHeader header : headers) {
                stats.scanned++;
                checkEntryLimit(stats.scanned);
                if (header.isDirectory() || header.getRedirection() != null) {
                    stats.skipped++;
                    continue;
                }
                String path = safeEntryPath(header.getFileName());
                if (path == null || !matchesMode(path, mode)) {
                    stats.skipped++;
                    continue;
                }
                if (files.contains(path) || directories.contains(path + "/")) {
                    stats.skipped++;
                    continue;
                }
                addParentDirectories(path, output, directories, files);
                files.add(path);
                long size = header.getFullUnpackSize();
                if (size < 0) throw new IOException("RAR girdisinin boyutu okunamadı: " + path);
                ZipArchiveEntry entry = new ZipArchiveEntry(path);
                entry.setSize(size);
                output.putArchiveEntry(entry);
                archive.extractFile(header, new CountingOutputStream(output, expanded));
                output.closeArchiveEntry();
                stats.kept++;
            }
        } finally {
            archive.close();
        }
    }

    private static void filterSevenZ(List<File> volumes, int mode, ZipArchiveOutputStream output,
                                     Stats stats) throws IOException {
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
            SevenZArchiveEntry entry;
            while ((entry = sevenZ.getNextEntry()) != null) {
                stats.scanned++;
                checkEntryLimit(stats.scanned);
                if (entry.isDirectory()) {
                    stats.skipped++;
                    continue;
                }
                String path = safeEntryPath(entry.getName());
                if (path == null || !matchesMode(path, mode)) {
                    stats.skipped++;
                    continue;
                }
                if (files.contains(path) || directories.contains(path + "/")) {
                    stats.skipped++;
                    continue;
                }
                long size = entry.getSize();
                if (size < 0) throw new IOException("7Z girdisinin boyutu okunamadı: " + path);
                addParentDirectories(path, output, directories, files);
                files.add(path);
                ZipArchiveEntry outEntry = new ZipArchiveEntry(path);
                outEntry.setSize(size);
                if (entry.getHasLastModifiedDate()) outEntry.setTime(entry.getLastModifiedDate().getTime());
                output.putArchiveEntry(outEntry);
                copySevenZEntry(sevenZ, output, expanded);
                output.closeArchiveEntry();
                stats.kept++;
            }
        } finally {
            if (sevenZ != null) sevenZ.close();
            if (channel != null) channel.close();
        }
    }

    private static void copySevenZEntry(SevenZFile sevenZ, OutputStream out, long[] expanded) throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        int read;
        while ((read = sevenZ.read(buffer)) != -1) {
            if (read == 0) continue;
            ensureExpandedCapacity(expanded[0], read);
            out.write(buffer, 0, read);
            expanded[0] += read;
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
        if (mode == MODE_DEFAULT) return true;
        int slash = path.lastIndexOf('/');
        String baseName = slash >= 0 ? path.substring(slash + 1) : path;
        return baseName.toLowerCase(Locale.ROOT).contains("card");
    }

    private static void addParentDirectories(String filePath, ZipArchiveOutputStream output,
                                             Set<String> directories, Set<String> files) throws IOException {
        String[] parts = filePath.split("/");
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < parts.length - 1; i++) {
            if (current.length() > 0) current.append('/');
            current.append(parts[i]);
            String path = current.toString();
            if (files.contains(path)) throw new IOException("Arşivde çakışan dosya/klasör yolu bulundu: " + path);
            String directory = path + "/";
            if (directories.add(directory)) {
                ZipArchiveEntry entry = new ZipArchiveEntry(directory);
                entry.setSize(0);
                output.putArchiveEntry(entry);
                output.closeArchiveEntry();
            }
        }
    }

    private static void copyLimited(InputStream in, OutputStream out, long[] expanded) throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        int read;
        while ((read = in.read(buffer)) != -1) {
            if (read == 0) continue;
            ensureExpandedCapacity(expanded[0], read);
            out.write(buffer, 0, read);
            expanded[0] += read;
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

    private static final class NumberedName {
        final int number;
        final String name;
        NumberedName(int number, String name) { this.number = number; this.name = name; }
    }

    private static final class Stats { int scanned; int kept; int skipped; }

    private static final class CountingOutputStream extends OutputStream {
        private final OutputStream target;
        private final long[] total;
        CountingOutputStream(OutputStream target, long[] total) { this.target = target; this.total = total; }
        @Override public void write(int value) throws IOException {
            ensureExpandedCapacity(total[0], 1);
            target.write(value);
            total[0]++;
        }
        @Override public void write(byte[] bytes, int offset, int length) throws IOException {
            if (bytes == null) throw new NullPointerException("bytes");
            if (offset < 0 || length < 0 || length > bytes.length - offset) throw new IndexOutOfBoundsException("Invalid buffer range");
            ensureExpandedCapacity(total[0], length);
            target.write(bytes, offset, length);
            total[0] += length;
        }
        @Override public void flush() throws IOException { target.flush(); }
        @Override public void close() throws IOException { flush(); }
    }
}
