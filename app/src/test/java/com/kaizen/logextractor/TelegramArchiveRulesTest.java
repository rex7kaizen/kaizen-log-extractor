package com.kaizen.logextractor;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class TelegramArchiveRulesTest {
    @Test
    public void acceptsSupportedArchiveFormatsAndVolumeNames() {
        assertTrue(TelegramArchiveRules.isSupportedArchiveName("bundle.zip"));
        assertTrue(TelegramArchiveRules.isSupportedArchiveName("backup.JAR"));
        assertTrue(TelegramArchiveRules.isSupportedArchiveName("set.part004.rar"));
        assertTrue(TelegramArchiveRules.isSupportedArchiveName("set.7z.001"));
        assertTrue(TelegramArchiveRules.isSupportedArchiveName("set.zip.002"));
        assertTrue(TelegramArchiveRules.isSupportedArchiveName("set.z01"));
        assertTrue(TelegramArchiveRules.isSupportedArchiveName("set.r02"));
    }

    @Test
    public void rejectsNullEmptyAndNonArchiveNames() {
        assertFalse(TelegramArchiveRules.isSupportedArchiveName(null));
        assertFalse(TelegramArchiveRules.isSupportedArchiveName(""));
        assertFalse(TelegramArchiveRules.isSupportedArchiveName("notes.txt"));
        assertFalse(TelegramArchiveRules.isSupportedArchiveName("."));
        assertFalse(TelegramArchiveRules.isSupportedArchiveName(".."));
    }

    @Test
    public void telegramModeRejectsSplitPartsThatNeedSiblingFiles() {
        assertTrue(TelegramArchiveRules.isSingleArchiveName("bundle.zip"));
        assertTrue(TelegramArchiveRules.isSingleArchiveName("bundle.rar"));
        assertFalse(TelegramArchiveRules.isSingleArchiveName("bundle.part01.rar"));
        assertFalse(TelegramArchiveRules.isSingleArchiveName("bundle.7z.001"));
        assertFalse(TelegramArchiveRules.isSingleArchiveName("bundle.zip.001"));
        assertFalse(TelegramArchiveRules.isSingleArchiveName("bundle.z01"));
        assertFalse(TelegramArchiveRules.isSingleArchiveName("bundle.r00"));
        assertFalse(TelegramArchiveRules.isSingleArchiveName(null));
    }

    @Test
    public void sanitizesPathSeparatorsAndUnsafeCharacters() {
        assertEquals("archive_name.zip", TelegramArchiveRules.sanitizeFileName("../archive name.zip"));
        assertEquals("backup.rar", TelegramArchiveRules.sanitizeFileName("folder\\backup.rar"));
        assertEquals("backup.zip", TelegramArchiveRules.sanitizeFileName("  backup.zip  "));
    }

    @Test
    public void limitsExtremelyLongFileNames() {
        String longName = "a".repeat(300) + ".zip";
        String safe = TelegramArchiveRules.sanitizeFileName(longName);
        assertTrue(safe.length() <= 120);
        assertTrue(safe.endsWith(".zip"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsNullSanitizationInput() {
        TelegramArchiveRules.sanitizeFileName(null);
    }
}
