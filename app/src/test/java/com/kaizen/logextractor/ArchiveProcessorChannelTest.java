package com.kaizen.logextractor;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.NonWritableChannelException;
import java.nio.channels.SeekableByteChannel;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public final class ArchiveProcessorChannelTest {
    @Test public void filtersZipDirectlyFromSeekableChannel() throws Exception {
        byte[] source = createZip("logs/example.txt", "chunked-telegram-read");
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        ArchiveProcessor.Result stats = ArchiveProcessor.filter(
                new MemoryChannel(source), source.length, "sample.zip", ArchiveProcessor.MODE_DEFAULT,
                result, null);

        assertEquals("ZIP", stats.archiveType);
        assertEquals(1, stats.keptFiles);
        try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(result.toByteArray()))) {
            ZipEntry entry = input.getNextEntry();
            while (entry != null && entry.isDirectory()) entry = input.getNextEntry();
            assertNotNull(entry);
            assertEquals("logs/example.txt", entry.getName());
            ByteArrayOutputStream payload = new ByteArrayOutputStream();
            byte[] buffer = new byte[128];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                if (count > 0) payload.write(buffer, 0, count);
            }
            assertEquals("chunked-telegram-read", payload.toString("UTF-8"));
        }
    }

    private static byte[] createZip(String name, String content) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            zip.putNextEntry(new ZipEntry(name));
            zip.write(content.getBytes("UTF-8"));
            zip.closeEntry();
        }
        return output.toByteArray();
    }

    private static final class MemoryChannel implements SeekableByteChannel {
        private final byte[] data;
        private long position;
        private boolean open = true;

        MemoryChannel(byte[] data) { this.data = data.clone(); }
        @Override public int read(ByteBuffer destination) throws IOException {
            ensureOpen();
            if (!destination.hasRemaining()) return 0;
            if (position >= data.length) return -1;
            int count = (int) Math.min(destination.remaining(), data.length - position);
            destination.put(data, (int) position, count);
            position += count;
            return count;
        }
        @Override public int write(ByteBuffer source) { throw new NonWritableChannelException(); }
        @Override public long position() throws IOException { ensureOpen(); return position; }
        @Override public SeekableByteChannel position(long next) throws IOException {
            ensureOpen();
            if (next < 0) throw new IllegalArgumentException("negative position");
            position = next;
            return this;
        }
        @Override public long size() throws IOException { ensureOpen(); return data.length; }
        @Override public SeekableByteChannel truncate(long size) { throw new NonWritableChannelException(); }
        @Override public boolean isOpen() { return open; }
        @Override public void close() { open = false; }
        private void ensureOpen() throws ClosedChannelException { if (!open) throw new ClosedChannelException(); }
    }
}
