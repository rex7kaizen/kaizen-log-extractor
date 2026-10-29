package com.kaizen.logextractor;

import com.github.junrar.Archive;
import com.github.junrar.rarfile.FileHeader;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/** Archive filtering is isolated from Android UI and streams file data instead of buffering whole files. */
final class ArchiveProcessor {
    static final int MODE_DEFAULT = 0;
    static final int MODE_CARDS = 1;

    private static final int MAX_ENTRIES = 100_000;
    private static final long MAX_INPUT_BYTES = 2L * 1024L * 1024L * 1024L;
    private static final long MAX_EXPANDED_BYTES = 1024L * 1024L * 1024L;
    private static final int BUFFER_SIZE = 32 * 1024;

    private ArchiveProcessor() { }

    enum ArchiveType { ZIP, RAR }

    static final class Result {
        final String archiveType;
        final int scannedEntries;
        final int keptFiles;
        final int skippedEntries;

        Result(String archiveType, int scannedEntries, int keptFiles, int skippedEntries) {
            this.archiveType = archiveType;
            this.scannedEntries = scannedEntries;
            this.keptFiles = keptFiles;
            this.skippedEntries = skippedEntries;
        }
    }

    static final class NoMatchesException extends IOException {
        NoMatchesException(String message) { super(message); }
    }

    static ArchiveType detectType(File file, String originalName) throws IOException {
        if (file == null || !file.isFile() || file.length() < 4) {
            throw new IOException("Seçilen dosya boş veya okunamıyor.");
        }
        String lowerName = originalName == null ? "" : originalName.toLowerCase(Locale.ROOT);
        boolean zipExtension = lowerName.endsWith(".zip");
        boolean rarExtension = lowerName.endsWith(".rar");
        byte[] header = new byte[8];
        int length;
        try (InputStream in = new BufferedInputStream(new FileInputStream(file))) {
            length = 0;
            while (length < header.length) {
                int read = in.read(header, length, header.length - length);
                if (read < 0) break;
                if (read == 0) continue;
                length += read;
            }
        }
        if (length >= 4 && zipExtension && isZipSignature(header)) {
            return ArchiveType.ZIP;
        }
        if (length >= 7 && rarExtension && isRarSignature(header, length)) {
            return ArchiveType.RAR;
        }
        throw new IOException("Yalnızca geçerli ZIP veya RAR arşivi seçebilirsin. Dosya uzantısı ve içeriği uyuşmuyor.");
    }

    static Result filter(File input, String originalName, int mode, OutputStream destination) throws Exception {
        if (mode != MODE_DEFAULT && mode != MODE_CARDS) {
            throw new IllegalArgumentException("Bilinmeyen filtre modu.");
        }
        ArchiveType type = detectType(input, originalName);
        Stats stats = new Stats();
        try (ZipOutputStream output = new ZipOutputStream(new BufferedOutputStream(destination))) {
            if (type == ArchiveType.ZIP) {
                filterZip(input, mode, output, stats);
            } else {
                filterRar(input, mode, output, stats);
            }
        }
        if (stats.kept == 0) {
            throw new NoMatchesException(mode == MODE_CARDS
                    ? "Dosya adında 'card' bulunan bir dosya bulunamadı. Boş çıktı kaydedilmedi."
                    : "Arşivde korunabilecek bir dosya bulunamadı. Boş çıktı kaydedilmedi.");
        }
        return new Result(type.name(), stats.scanned, stats.kept, stats.skipped);
    }

    private static void filterZip(File input, int mode, ZipOutputStream output, Stats stats) throws IOException {
        Set<String> directories = new HashSet<>();
        Set<String> files = new HashSet<>();
        long[] expanded = {0L};

        try (ZipFile zip = new ZipFile(input)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
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
                addParentDirectories(path, output, directories, files);
                files.add(path);

                ZipEntry outEntry = new ZipEntry(path);
                if (entry.getTime() > 0) {
                    outEntry.setTime(entry.getTime());
                }
                output.putNextEntry(outEntry);
                try (InputStream in = zip.getInputStream(entry)) {
                    copyLimited(in, output, expanded);
                }
                output.closeEntry();
                stats.kept++;
            }
        }
    }

    private static void filterRar(File input, int mode, ZipOutputStream output, Stats stats) throws Exception {
        Set<String> directories = new HashSet<>();
        Set<String> files = new HashSet<>();
        long[] expanded = {0L};
        Archive archive = new Archive(input);
        try {
            if (archive.isPasswordProtected()) {
                throw new IOException("Bu RAR arşivi parola korumalı. Bu sürüm parolalı arşivleri işlemiyor.");
            }
            List<FileHeader> headers = archive.getFileHeaders();
            if (headers.size() > MAX_ENTRIES) {
                throw new IOException("Arşiv çok fazla girdi içeriyor; güvenlik sınırı aşıldı.");
            }
            for (FileHeader header : headers) {
                stats.scanned++;
                checkEntryLimit(stats.scanned);

                // Directory and link entries are not payload files and should not be copied.
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
                output.putNextEntry(new ZipEntry(path));
                CountingOutputStream counted = new CountingOutputStream(output, expanded);
                archive.extractFile(header, counted);
                output.closeEntry();
                stats.kept++;
            }
        } finally {
            archive.close();
        }
    }

    private static boolean isZipSignature(byte[] h) {
        return h[0] == 'P' && h[1] == 'K'
                && ((h[2] == 3 && h[3] == 4)
                || (h[2] == 5 && h[3] == 6)
                || (h[2] == 7 && h[3] == 8));
    }

    private static boolean isRarSignature(byte[] h, int length) {
        if (length >= 7 && h[0] == 0x52 && h[1] == 0x61 && h[2] == 0x72
                && h[3] == 0x21 && h[4] == 0x1A && h[5] == 0x07 && h[6] == 0x00) {
            return true; // RAR 1.5–4 signature
        }
        return length >= 8 && h[0] == 0x52 && h[1] == 0x61 && h[2] == 0x72
                && h[3] == 0x21 && h[4] == 0x1A && h[5] == 0x07
                && h[6] == 0x01 && h[7] == 0x00; // RAR 5+ signature
    }

    /** Reject absolute paths, traversal components, NULs, and directory-shaped file names. */
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

    private static void addParentDirectories(String filePath, ZipOutputStream output,
                                             Set<String> directories, Set<String> files) throws IOException {
        String[] parts = filePath.split("/");
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < parts.length - 1; i++) {
            if (current.length() > 0) current.append('/');
            current.append(parts[i]);
            String path = current.toString();
            if (files.contains(path)) {
                throw new IOException("Arşivde çakışan dosya/klasör yolu bulundu: " + path);
            }
            String directory = path + "/";
            if (directories.add(directory)) {
                output.putNextEntry(new ZipEntry(directory));
                output.closeEntry();
            }
        }
    }

    private static void copyLimited(InputStream in, OutputStream out, long[] expanded) throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        int read;
        while ((read = in.read(buffer)) != -1) {
            if (read == 0) continue;
            if (expanded[0] > MAX_EXPANDED_BYTES - read) {
                throw new IOException("Açılmış toplam veri 1 GiB sınırını aştı; işlem güvenlik için durduruldu.");
            }
            out.write(buffer, 0, read);
            expanded[0] += read;
        }
    }

    private static void checkEntryLimit(int scanned) throws IOException {
        if (scanned > MAX_ENTRIES) {
            throw new IOException("Arşiv 100.000 girdiden fazla içeriyor; işlem güvenlik için durduruldu.");
        }
    }

    private static final class Stats {
        int scanned;
        int kept;
        int skipped;
    }

    /** Counts decompressed RAR payload bytes but never closes the ZipOutputStream it wraps. */
    private static final class CountingOutputStream extends OutputStream {
        private final OutputStream target;
        private final long[] total;

        CountingOutputStream(OutputStream target, long[] total) {
            this.target = target;
            this.total = total;
        }

        @Override public void write(int value) throws IOException {
            ensureCapacity(1);
            target.write(value);
            total[0]++;
        }

        @Override public void write(byte[] bytes, int offset, int length) throws IOException {
            if (bytes == null) throw new NullPointerException("bytes");
            if (offset < 0 || length < 0 || length > bytes.length - offset) {
                throw new IndexOutOfBoundsException("Invalid buffer range");
            }
            ensureCapacity(length);
            target.write(bytes, offset, length);
            total[0] += length;
        }

        private void ensureCapacity(int amount) throws IOException {
            if (amount < 0 || total[0] > MAX_EXPANDED_BYTES - amount) {
                throw new IOException("Açılmış toplam veri 1 GiB sınırını aştı; işlem güvenlik için durduruldu.");
            }
        }

        @Override public void flush() throws IOException { target.flush(); }
        @Override public void close() throws IOException { flush(); }
    }
}
