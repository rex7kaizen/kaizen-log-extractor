package com.kaizen.logextractor;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Enumeration;
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
        File input = zipOf(
                "kaizen/lmao/hahaha/xd/", null,
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
        } finally {
            input.delete(); output.delete();
        }
    }

    @Test
    public void cardsMatchesBasenameCaseInsensitivelyNotParentFolder() throws Exception {
        File input = zipOf(
                "kaizen/card/fix.txt", "folder has card only",
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
        } finally {
            input.delete(); output.delete();
        }
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
        } finally {
            input.delete(); output.delete();
        }
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
        } finally {
            input.delete(); output.delete();
        }
    }

    @Test(expected = IOException.class)
    public void nonArchiveTxtIsRejected() throws Exception {
        File input = File.createTempFile("kaizen-not-archive", ".txt");
        try {
            Files.write(input.toPath(), "not a zip".getBytes(StandardCharsets.UTF_8));
            ArchiveProcessor.filter(input, "notes.txt", ArchiveProcessor.MODE_DEFAULT, new ByteArrayOutputStream());
        } finally {
            input.delete();
        }
    }

    @Test(expected = ArchiveProcessor.NoMatchesException.class)
    public void directoryOnlyArchiveDoesNotProduceEmptyResult() throws Exception {
        File input = zipOf("empty/", null, "empty/deep/", null);
        try {
            ArchiveProcessor.filter(input, "folders.zip", ArchiveProcessor.MODE_DEFAULT, new ByteArrayOutputStream());
        } finally {
            input.delete();
        }
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

    private static Set<String> entryNames(File archive) throws Exception {
        Set<String> names = new TreeSet<>();
        try (ZipFile zip = new ZipFile(archive)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) names.add(entries.nextElement().getName());
        }
        return names;
    }
}
