package com.kaizen.logextractor;

import java.util.Locale;

/** Small, side-effect-free rules for selecting one Telegram archive attachment safely. */
final class TelegramArchiveRules {
    private static final int MAX_FILE_NAME_LENGTH = 120;

    private TelegramArchiveRules() { }

    static boolean isSupportedArchiveName(String name) {
        if (name == null) return false;
        String normalized = name.trim().replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        if (slash >= 0) normalized = normalized.substring(slash + 1);
        if (normalized.isEmpty() || normalized.equals(".") || normalized.equals("..")) return false;
        String lower = normalized.toLowerCase(Locale.ROOT);
        return lower.endsWith(".zip") || lower.endsWith(".jar")
                || lower.endsWith(".rar") || lower.endsWith(".7z")
                || lower.matches(".*\\.part\\d+\\.rar")
                || lower.matches(".*\\.7z\\.\\d{3,}")
                || lower.matches(".*\\.zip\\.\\d{3,}")
                || lower.matches(".*\\.z\\d{2,3}")
                || lower.matches(".*\\.r\\d{2,3}");
    }

    /** Telegram mode imports one attachment at a time; numbered volume pieces need their siblings. */
    static boolean isSingleArchiveName(String name) {
        if (!isSupportedArchiveName(name)) return false;
        String normalized = name.trim().replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        if (slash >= 0) normalized = normalized.substring(slash + 1);
        String lower = normalized.toLowerCase(Locale.ROOT);
        return !lower.matches(".*\\.part\\d+\\.rar")
                && !lower.matches(".*\\.7z\\.\\d{3,}")
                && !lower.matches(".*\\.zip\\.\\d{3,}")
                && !lower.matches(".*\\.z\\d{2,3}")
                && !lower.matches(".*\\.r\\d{2,3}");
    }

    /** Returns a plain basename safe to append to the app-private import directory. */
    static String sanitizeFileName(String name) {
        if (name == null) throw new IllegalArgumentException("Dosya adı boş.");
        String normalized = name.trim().replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        String base = slash >= 0 ? normalized.substring(slash + 1) : normalized;
        base = base.trim().replaceAll("[^A-Za-z0-9._-]", "_");
        while (base.startsWith(".")) base = base.substring(1);
        if (base.isEmpty() || base.equals(".") || base.equals("..")) {
            throw new IllegalArgumentException("Geçersiz dosya adı.");
        }
        if (base.length() > MAX_FILE_NAME_LENGTH) {
            int dot = base.lastIndexOf('.');
            String extension = dot > 0 && base.length() - dot <= 12 ? base.substring(dot) : "";
            int keep = MAX_FILE_NAME_LENGTH - extension.length();
            base = base.substring(0, keep) + extension;
        }
        return base;
    }
}
