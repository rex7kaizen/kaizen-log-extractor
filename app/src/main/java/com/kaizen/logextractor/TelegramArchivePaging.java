package com.kaizen.logextractor;

/** Paging guard for TDLib history responses, which may contain fewer items than requested. */
final class TelegramArchivePaging {
    private TelegramArchivePaging() { }

    static boolean shouldContinue(int returnedCount, int newlySeenCount, int scannedCount,
                                  int scanLimit, long previousCursor, long nextCursor) {
        if (returnedCount <= 0 || newlySeenCount <= 0 || newlySeenCount > returnedCount) return false;
        if (scannedCount < 0 || scanLimit <= 0 || scannedCount >= scanLimit) return false;
        if (nextCursor <= 0L) return false;
        return previousCursor <= 0L || nextCursor < previousCursor;
    }
}
