package com.kaizen.logextractor;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class TelegramArchivePagingTest {
    @Test public void continuesAfterShortPageWhenCursorMovesBackwards() {
        assertTrue(TelegramArchivePaging.shouldContinue(12, 12, 12, 20_000, 0L, 900L));
        assertTrue(TelegramArchivePaging.shouldContinue(3, 3, 15, 20_000, 900L, 850L));
    }

    @Test public void stopsWhenNoNewMessagesAreReturned() {
        assertFalse(TelegramArchivePaging.shouldContinue(10, 0, 100, 20_000, 900L, 800L));
    }

    @Test public void stopsWhenCursorDoesNotMoveStrictlyBackwards() {
        assertFalse(TelegramArchivePaging.shouldContinue(10, 10, 100, 20_000, 900L, 900L));
        assertFalse(TelegramArchivePaging.shouldContinue(10, 10, 100, 20_000, 900L, 901L));
    }

    @Test public void stopsAtScanLimitAndForInvalidCursors() {
        assertFalse(TelegramArchivePaging.shouldContinue(1, 1, 20_000, 20_000, 0L, 1L));
        assertFalse(TelegramArchivePaging.shouldContinue(1, 1, 1, 0, 0L, 1L));
        assertFalse(TelegramArchivePaging.shouldContinue(1, 1, 1, 20_000, 0L, 0L));
        assertFalse(TelegramArchivePaging.shouldContinue(0, 0, 0, 20_000, 0L, 1L));
        assertFalse(TelegramArchivePaging.shouldContinue(1, 2, 1, 20_000, 0L, 1L));
    }
}
