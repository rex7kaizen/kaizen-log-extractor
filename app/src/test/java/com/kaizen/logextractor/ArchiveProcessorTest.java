package com.kaizen.logextractor;

import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ArchiveProcessorTest {
    @Test
    public void defaultKeepsRegularFilesAndOnlyRequiredParents() throws Exception {
        File input = zipOf("kaizen/lmao/hahaha/xd/", null,
                "kaizen/hahah/xdxd/xd.txt", "ordinary",
                "kaizen/hahah/xdxd/cardxdxd.txt", "card",
                "kaizen/hahah/xfjsifh/cards.txt", "cards",
                "kaizen/hahah/xdxd/lmfao/", null);
        File output = File.createTempFile("kaizen-default", ".zip");
        try {
            ArchiveProcessor.Result result = filterTo(input, "sample.zip", ArchiveProcessor.MODE_DEFAULT, output);
            assertEquals(3, result.keptFiles);
            assertEquals(new TreeSet<>(Arrays.asList(
                    "kaizen/", "kaizen/hahah/", "kaizen/hahah/xdxd/",
                    "kaizen/hahah/xdxd/xd.txt", "kaizen/hahah/xdxd/cardxdxd.txt",
                    "kaizen/hahah/xfjsifh/", "kaizen/hahah/xfjsifh/cards.txt")), entryNames(output));
        } finally { input.delete(); output.delete(); }
    }

    @Test
    public void cardsMatchesBasenameCaseInsensitivelyNotParentFolder() throws Exception {
        File input = zipOf("kaizen/card/fix.txt", "folder has card only",
                "kaizen/hahah/xdxd/xd.txt", "ordinary",
                "kaizen/hahah/xdxd/cardxdxd.txt", "card",
                "kaizen/hahah/xfjsifh/cards.txt", "cards",
                "CARD.txt", "upper case");
        File output = File.createTempFile("kaizen-cards", ".zip");
        try {
            ArchiveProcessor.Result result = filterTo(input, "sample.zip", ArchiveProcessor.MODE_CARDS, output);
            assertEquals(3, result.keptFiles);
            assertEquals(new TreeSet<>(Arrays.asList(
                    "kaizen/", "kaizen/hahah/", "kaizen/hahah/xdxd/",
                    "kaizen/hahah/xdxd/cardxdxd.txt", "kaizen/hahah/xfjsifh/",
                    "kaizen/hahah/xfjsifh/cards.txt", "CARD.txt")), entryNames(output));
        } finally { input.delete(); output.delete(); }
    }

    @Test
    public void unsafeTraversalPathIsSkippedAndSafePathSurvives() throws Exception {
        File input = zipOf("../outside.txt", "unsafe", "safe/inside.txt", "safe");
        File output = File.createTempFile("kaizen-safe", ".zip");
        try {
            ArchiveProcessor.Result result = filterTo(input, "sample.zip", ArchiveProcessor.MODE_DEFAULT, output);
            assertEquals(1, result.keptFiles);
            assertFalse(entryNames(output).contains("../outside.txt"));
            assertTrue(entryNames(output).contains("safe/inside.txt"));
            assertTrue(entryNames(output).contains("safe/"));
        } finally { input.delete(); output.delete(); }
    }

    @Test
    public void zeroByteFileStillCountsAsRealFile() throws Exception {
        File input = zipOf("nested/empty.log", "");
        File output = File.createTempFile("kaizen-empty-file", ".zip");
        try {
            ArchiveProcessor.Result result = filterTo(input, "sample.zip", ArchiveProcessor.MODE_DEFAULT, output);
            assertEquals(1, result.keptFiles);
            assertTrue(entryNames(output).contains("nested/empty.log"));
            assertTrue(entryNames(output).contains("nested/"));
        } finally { input.delete(); output.delete(); }
    }

    @Test(expected = IOException.class)
    public void nonArchiveTxtIsRejected() throws Exception {
        File input = File.createTempFile("kaizen-not-archive", ".txt");
        try {
            Files.write(input.toPath(), "not a zip".getBytes(StandardCharsets.UTF_8));
            ArchiveProcessor.filter(input, "notes.txt", ArchiveProcessor.MODE_DEFAULT, new ByteArrayOutputStream());
        } finally { input.delete(); }
    }

    @Test(expected = ArchiveProcessor.NoMatchesException.class)
    public void directoryOnlyArchiveDoesNotProduceEmptyResult() throws Exception {
        File input = zipOf("empty/", null, "empty/deep/", null);
        try { ArchiveProcessor.filter(input, "folders.zip", ArchiveProcessor.MODE_DEFAULT, new ByteArrayOutputStream()); }
        finally { input.delete(); }
    }

    @Test
    public void jarSignatureIsAcceptedAsZipContainer() throws Exception {
        File input = zipOf("data.txt", "jar payload");
        try { assertEquals(ArchiveProcessor.ArchiveType.JAR, ArchiveProcessor.detectType(input, "sample.jar")); }
        finally { input.delete(); }
    }

    @Test
    public void sevenZFilesAreFilteredIntoZipOutput() throws Exception {
        File input = sevenZOf("nested/card-data.txt", "seven zip payload");
        File output = File.createTempFile("kaizen-7z-output", ".zip");
        try {
            ArchiveProcessor.Result result = filterTo(input, "sample.7z", ArchiveProcessor.MODE_CARDS, output);
            assertEquals("SEVEN_Z", result.archiveType);
            assertEquals(1, result.keptFiles);
            assertTrue(entryNames(output).contains("nested/card-data.txt"));
            assertTrue(entryNames(output).contains("nested/"));
        } finally { input.delete(); output.delete(); }
    }

    @Test
    public void laterRarPartSelectionResolvesAllEarlierPartsInOrder() throws Exception {
        List<String> resolved = ArchiveProcessor.selectVolumeNames(Arrays.asList(
                "backup.part4.rar", "backup.part2.rar", "backup.part1.rar", "backup.part3.rar"), "backup.part4.rar");
        assertEquals(Arrays.asList("backup.part1.rar", "backup.part2.rar", "backup.part3.rar", "backup.part4.rar"), resolved);
    }

    @Test
    public void splitSevenZipPartsAreOrderedNumerically() throws Exception {
        List<String> resolved = ArchiveProcessor.selectVolumeNames(Arrays.asList(
                "backup.7z.004", "backup.7z.001", "backup.7z.003", "backup.7z.002"), "backup.7z.004");
        assertEquals(Arrays.asList("backup.7z.001", "backup.7z.002", "backup.7z.003", "backup.7z.004"), resolved);
    }

    @Test
    public void rawZipPartsAreOrderedNumerically() throws Exception {
        List<String> resolved = ArchiveProcessor.selectVolumeNames(Arrays.asList(
                "backup.zip.003", "backup.zip.001", "backup.zip.002"), "backup.zip.003");
        assertEquals(Arrays.asList("backup.zip.001", "backup.zip.002", "backup.zip.003"), resolved);
    }

    @Test
    public void classicSplitZipUsesDiskPartsThenFinalZip() throws Exception {
        List<String> resolved = ArchiveProcessor.selectVolumeNames(Arrays.asList(
                "backup.zip", "backup.z02", "backup.z01"), "backup.z01");
        assertEquals(Arrays.asList("backup.z01", "backup.z02", "backup.zip"), resolved);
    }

    @Test
    public void classicRarVolumesIncludeFirstRarAndSortedRParts() throws Exception {
        List<String> resolved = ArchiveProcessor.selectVolumeNames(Arrays.asList(
                "backup.r01", "backup.rar", "backup.r00"), "backup.r01");
        assertEquals(Arrays.asList("backup.rar", "backup.r00", "backup.r01"), resolved);
    }

    @Test
    public void laterRarPartsCanBeSelectedWithoutRequiringPartOne() throws Exception {
        List<String> resolved = ArchiveProcessor.selectVolumeNames(
                Arrays.asList("backup.part4.rar", "backup.part3.rar"), "backup.part4.rar");
        assertEquals(Arrays.asList("backup.part3.rar", "backup.part4.rar"), resolved);
    }

    @Test
    public void laterSevenZPartsCanBeSelectedWithoutRequiring001() throws Exception {
        List<String> resolved = ArchiveProcessor.selectVolumeNames(
                Arrays.asList("backup.7z.004", "backup.7z.003"), "backup.7z.004");
        assertEquals(Arrays.asList("backup.7z.003", "backup.7z.004"), resolved);
    }

    @Test
    public void laterRawZipPartsCanBeSelectedWithoutRequiring001() throws Exception {
        List<String> resolved = ArchiveProcessor.selectVolumeNames(
                Arrays.asList("backup.zip.004", "backup.zip.003"), "backup.zip.004");
        assertEquals(Arrays.asList("backup.zip.003", "backup.zip.004"), resolved);
    }

    @Test(expected = IOException.class)
    public void missingIntermediatePartIsStillRejectedWhenStartingLater() throws Exception {
        ArchiveProcessor.selectVolumeNames(
                Arrays.asList("backup.7z.003", "backup.7z.005"), "backup.7z.005");
    }

    @Test
    public void laterSevenZVolumeCanBeIdentifiedWithoutContainerSignature() throws Exception {
        File input = File.createTempFile("kaizen-later-volume", ".bin");
        try {
            Files.write(input.toPath(), "continuation-data".getBytes(StandardCharsets.UTF_8));
            assertEquals(ArchiveProcessor.ArchiveType.SEVEN_Z,
                    ArchiveProcessor.detectType(input, "backup.7z.003"));
        } finally { input.delete(); }
    }

    @Test(expected = IOException.class)
    public void missingIntermediateSevenZPartFailsClearlyBeforeExtraction() throws Exception {
        ArchiveProcessor.selectVolumeNames(Arrays.asList("backup.7z.001", "backup.7z.003"), "backup.7z.003");
    }

    private static ArchiveProcessor.Result filterTo(File input, String name, int mode, File output) throws Exception {
        try (FileOutputStream stream = new FileOutputStream(output)) {
            return ArchiveProcessor.filter(input, name, mode, stream);
        }
    }

    private static File zipOf(String... pairs) throws Exception {
        File file = File.createTempFile("kaizen-fixture", ".zip");
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(file))) {
            for (int i = 0; i < pairs.length; i += 2) {
                zip.putNextEntry(new ZipEntry(pairs[i]));
                if (pairs[i + 1] != null) zip.write(pairs[i + 1].getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return file;
    }

    private static File sevenZOf(String entryName, String payload) throws Exception {
        File input = File.createTempFile("kaizen-fixture", ".7z");
        File data = File.createTempFile("kaizen-payload", ".txt");
        try {
            Files.write(data.toPath(), payload.getBytes(StandardCharsets.UTF_8));
            try (SevenZOutputFile sevenZ = new SevenZOutputFile(input)) {
                SevenZArchiveEntry entry = sevenZ.createArchiveEntry(data, entryName);
                sevenZ.putArchiveEntry(entry);
                sevenZ.write(payload.getBytes(StandardCharsets.UTF_8));
                sevenZ.closeArchiveEntry();
            }
            return input;
        } catch (Exception e) {
            input.delete();
            throw e;
        } finally { data.delete(); }
    }

    private static Set<String> entryNames(File archive) throws Exception {
        Set<String> names = new TreeSet<>();
        try (ZipFile zip = new ZipFile(archive)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) names.add(entries.nextElement().getName());
        }
        return names;
    }
}
