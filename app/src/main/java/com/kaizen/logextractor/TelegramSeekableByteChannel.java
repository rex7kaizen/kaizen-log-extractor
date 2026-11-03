package com.kaizen.logextractor;

import org.drinkless.tdlib.Client;
import org.drinkless.tdlib.TdApi;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SeekableByteChannel;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/**
 * Read-only, bounded-memory view of a Telegram document.
 *
 * TDLib downloads only the requested byte range into its own private file cache. The
 * corresponding range is then copied into this channel's single in-memory chunk. The
 * complete archive is never downloaded before the archive reader starts consuming it.
 */
final class TelegramSeekableByteChannel implements SeekableByteChannel {
    static final int CHUNK_SIZE = 1024 * 1024;
    private static final long REQUEST_TIMEOUT_SECONDS = 180L;

    interface RangeReader {
        byte[] read(long offset, int count) throws IOException;
    }

    private final long fileSize;
    private final RangeReader rangeReader;
    private final BooleanSupplier cancelled;
    private long position;
    private long cachedOffset = -1L;
    private byte[] cachedBytes;
    private volatile boolean open = true;

    TelegramSeekableByteChannel(Client client, int fileId, long fileSize,
                                BooleanSupplier cancelled) throws IOException {
        if (client == null) throw new IOException("Telegram bağlantısı hazır değil.");
        if (fileId <= 0) throw new IOException("Telegram dosya kimliği geçersiz.");
        if (fileSize <= 0L || fileSize > ArchiveProcessor.MAX_INPUT_BYTES) {
            throw new IOException("Telegram arşiv boyutu geçersiz veya 64 GiB sınırını aşıyor.");
        }
        this.fileSize = fileSize;
        this.cancelled = cancelled == null ? () -> false : cancelled;
        this.rangeReader = (offset, count) -> readTelegramRange(client, fileId, offset, count, this.cancelled);
    }

    /** Test seam: makes chunk/seek/EOF behavior testable without a Telegram connection. */
    TelegramSeekableByteChannel(long fileSize, RangeReader rangeReader,
                                BooleanSupplier cancelled) throws IOException {
        if (fileSize < 0L || fileSize > ArchiveProcessor.MAX_INPUT_BYTES) {
            throw new IOException("Arşiv boyutu geçersiz.");
        }
        if (rangeReader == null) throw new IOException("Parça okuyucu atanmadı.");
        this.fileSize = fileSize;
        this.rangeReader = rangeReader;
        this.cancelled = cancelled == null ? () -> false : cancelled;
    }

    private static byte[] readTelegramRange(Client client, int fileId, long offset, int count,
                                            BooleanSupplier cancelled) throws IOException {
        TdApi.Object downloadResult = sendAndWait(client,
                new TdApi.DownloadFile(fileId, 32, offset, count, true),
                cancelled, fileId, true);
        if (!(downloadResult instanceof TdApi.File) || ((TdApi.File) downloadResult).id != fileId) {
            throw new IOException("TDLib beklenen dosya aralığı yerine farklı bir dosya döndürdü.");
        }

        TdApi.Object readResult = sendAndWait(client, new TdApi.ReadFilePart(fileId, offset, count),
                cancelled, fileId, false);
        if (!(readResult instanceof TdApi.Data)) {
            throw new IOException("TDLib dosya parçası yerine beklenmeyen bir yanıt döndürdü.");
        }
        TdApi.Data data = (TdApi.Data) readResult;
        if (data.data == null || data.data.length != count) {
            int actual = data.data == null ? -1 : data.data.length;
            throw new IOException("Telegram arşiv parçası eksik geldi (beklenen " + count
                    + " bayt, gelen " + actual + ").");
        }
        return data.data;
    }

    private static TdApi.Object sendAndWait(Client client, TdApi.Function<?> function,
                                            BooleanSupplier cancelled, int fileId,
                                            boolean cancelDownloadOnAbort) throws IOException {
        CountDownLatch completed = new CountDownLatch(1);
        AtomicReference<TdApi.Object> response = new AtomicReference<>();
        try {
            client.send(function, result -> {
                response.set(result);
                completed.countDown();
            });
        } catch (RuntimeException e) {
            throw new IOException("TDLib isteği gönderilemedi: " + readableMessage(e), e);
        }

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(REQUEST_TIMEOUT_SECONDS);
        try {
            while (true) {
                if (Thread.currentThread().isInterrupted()) {
                    if (cancelDownloadOnAbort) cancelDownload(client, fileId);
                    throw new InterruptedIOException("Telegram arşiv okuması kesildi.");
                }
                if (cancelled.getAsBoolean()) {
                    if (cancelDownloadOnAbort) cancelDownload(client, fileId);
                    throw new InterruptedIOException("Telegram arşiv okuması kullanıcı tarafından iptal edildi.");
                }
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0L) {
                    if (cancelDownloadOnAbort) cancelDownload(client, fileId);
                    throw new IOException("Telegram'dan arşiv parçası 180 saniye içinde alınamadı.");
                }
                if (completed.await(Math.min(TimeUnit.NANOSECONDS.toMillis(remaining), 1000L),
                        TimeUnit.MILLISECONDS)) {
                    TdApi.Object result = response.get();
                    if (result == null) throw new IOException("TDLib boş yanıt döndürdü.");
                    if (result instanceof TdApi.Error) {
                        TdApi.Error error = (TdApi.Error) result;
                        throw new IOException("Telegram isteği başarısız (" + error.code + "): "
                                + (error.message == null ? "Bilinmeyen TDLib hatası" : error.message));
                    }
                    return result;
                }
            }
        } catch (InterruptedException e) {
            if (cancelDownloadOnAbort) cancelDownload(client, fileId);
            Thread.currentThread().interrupt();
            InterruptedIOException interrupted = new InterruptedIOException("Telegram arşiv okuması kesildi.");
            interrupted.initCause(e);
            throw interrupted;
        }
    }

    private static void cancelDownload(Client client, int fileId) {
        try {
            client.send(new TdApi.CancelDownloadFile(fileId, false), result -> {
                if (result instanceof TdApi.Error) {
                    TdApi.Error error = (TdApi.Error) result;
                    android.util.Log.w("KaizenTelegram", "Range cancellation returned "
                            + error.code + ": " + error.message);
                }
            });
        } catch (RuntimeException e) {
            android.util.Log.e("KaizenTelegram", "Could not cancel Telegram range request", e);
        }
    }

    private static String readableMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty()
                ? error.getClass().getSimpleName() : message;
    }

    @Override
    public synchronized int read(ByteBuffer destination) throws IOException {
        ensureOpen();
        if (destination == null) throw new NullPointerException("destination");
        if (!destination.hasRemaining()) return 0;
        if (position >= fileSize) return -1;

        int copied = 0;
        while (destination.hasRemaining() && position < fileSize) {
            checkCancelled();
            long chunkOffset = (position / CHUNK_SIZE) * CHUNK_SIZE;
            ensureChunk(chunkOffset);
            int inChunk = (int) (position - cachedOffset);
            if (inChunk < 0 || inChunk >= cachedBytes.length) {
                throw new IOException("Telegram arşiv parça önbelleği tutarsız.");
            }
            int amount = Math.min(destination.remaining(), cachedBytes.length - inChunk);
            destination.put(cachedBytes, inChunk, amount);
            position += amount;
            copied += amount;
        }
        return copied == 0 ? -1 : copied;
    }

    private void ensureChunk(long offset) throws IOException {
        if (cachedBytes != null && cachedOffset == offset) return;
        checkCancelled();
        long remaining = fileSize - offset;
        if (remaining <= 0L) throw new IOException("Arşiv sonunun ötesinde parça istendi.");
        int count = (int) Math.min((long) CHUNK_SIZE, remaining);
        // Release the previous window before requesting the next one. This keeps the
        // channel's retained byte cache at one chunk even during a boundary seek.
        if (cachedBytes != null) Arrays.fill(cachedBytes, (byte) 0);
        cachedBytes = null;
        cachedOffset = -1L;
        byte[] loaded = rangeReader.read(offset, count);
        if (loaded == null || loaded.length != count) {
            if (loaded != null) Arrays.fill(loaded, (byte) 0);
            throw new IOException("Arşiv parçası eksik veya boş geldi.");
        }
        cachedOffset = offset;
        cachedBytes = loaded;
    }

    private void checkCancelled() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted() || cancelled.getAsBoolean()) {
            throw new InterruptedIOException("Telegram arşiv okuması kullanıcı tarafından iptal edildi.");
        }
    }

    private void ensureOpen() throws ClosedChannelException {
        if (!open) throw new ClosedChannelException();
    }

    @Override
    public synchronized int write(ByteBuffer source) throws IOException {
        ensureOpen();
        throw new java.nio.channels.NonWritableChannelException();
    }

    @Override
    public synchronized long position() throws IOException {
        ensureOpen();
        return position;
    }

    @Override
    public synchronized SeekableByteChannel position(long newPosition) throws IOException {
        ensureOpen();
        if (newPosition < 0L) throw new IllegalArgumentException("position must be non-negative");
        position = newPosition;
        return this;
    }

    @Override
    public long size() throws IOException {
        ensureOpen();
        return fileSize;
    }

    @Override
    public synchronized SeekableByteChannel truncate(long size) throws IOException {
        ensureOpen();
        throw new java.nio.channels.NonWritableChannelException();
    }

    @Override
    public synchronized boolean isOpen() {
        return open;
    }

    @Override
    public synchronized void close() {
        if (!open) return;
        open = false;
        if (cachedBytes != null) {
            Arrays.fill(cachedBytes, (byte) 0);
            cachedBytes = null;
        }
        cachedOffset = -1L;
    }
}
