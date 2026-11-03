package com.kaizen.logextractor;

import org.junit.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public final class TelegramSeekableByteChannelTest {
    @Test public void readsOnlyRequestedRangesAndCachesOneChunk() throws Exception {
        byte[] source = new byte[TelegramSeekableByteChannel.CHUNK_SIZE + 17];
        for (int i = 0; i < source.length; i++) source[i] = (byte) (i % 251);
        List<Long> requestedOffsets = new ArrayList<>();
        TelegramSeekableByteChannel channel = new TelegramSeekableByteChannel(source.length,
                (offset, count) -> {
                    requestedOffsets.add(offset);
                    byte[] part = new byte[count];
                    System.arraycopy(source, (int) offset, part, 0, count);
                    return part;
                }, () -> false);

        ByteBuffer first = ByteBuffer.allocate(32);
        assertEquals(32, channel.read(first));
        assertEquals((byte) 0, first.array()[0]);
        assertEquals(1, requestedOffsets.size());
        assertEquals(Long.valueOf(0L), requestedOffsets.get(0));

        channel.position(8L);
        ByteBuffer cached = ByteBuffer.allocate(8);
        assertEquals(8, channel.read(cached));
        assertEquals(1, requestedOffsets.size());

        channel.position(TelegramSeekableByteChannel.CHUNK_SIZE);
        ByteBuffer last = ByteBuffer.allocate(17);
        assertEquals(17, channel.read(last));
        assertEquals(2, requestedOffsets.size());
        assertEquals(Long.valueOf(TelegramSeekableByteChannel.CHUNK_SIZE), requestedOffsets.get(1));
        assertEquals(-1, channel.read(ByteBuffer.allocate(1)));
        channel.close();
        assertFalse(channel.isOpen());
    }

    @Test public void handlesEmptyBuffersAndSeekPastEnd() throws Exception {
        TelegramSeekableByteChannel channel = new TelegramSeekableByteChannel(4,
                (offset, count) -> new byte[count], () -> false);
        assertEquals(0, channel.read(ByteBuffer.allocate(0)));
        channel.position(99L);
        assertEquals(-1, channel.read(ByteBuffer.allocate(1)));
        channel.close();
    }

    @Test public void rejectsShortRangesAndHonorsCancellation() throws Exception {
        TelegramSeekableByteChannel shortRead = new TelegramSeekableByteChannel(10,
                (offset, count) -> new byte[Math.max(0, count - 1)], () -> false);
        try {
            shortRead.read(ByteBuffer.allocate(10));
            fail("Short range must fail instead of silently returning corrupted bytes");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("eksik"));
        }
        shortRead.close();

        AtomicBoolean cancelled = new AtomicBoolean(true);
        TelegramSeekableByteChannel cancelledChannel = new TelegramSeekableByteChannel(10,
                (offset, count) -> new byte[count], cancelled::get);
        try {
            cancelledChannel.read(ByteBuffer.allocate(1));
            fail("Cancellation must stop reads");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("iptal"));
        }
        cancelledChannel.close();
    }

    @Test public void rejectsNegativeSeekAndReadAfterClose() throws Exception {
        TelegramSeekableByteChannel channel = new TelegramSeekableByteChannel(1,
                (offset, count) -> new byte[count], () -> false);
        try {
            channel.position(-1L);
            fail("Negative position must fail");
        } catch (IllegalArgumentException expected) {
            // expected
        }
        channel.close();
        try {
            channel.read(ByteBuffer.allocate(1));
            fail("Closed channel must fail");
        } catch (ClosedChannelException expected) {
            // expected
        }
    }
}
