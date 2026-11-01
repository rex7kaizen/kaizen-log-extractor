package com.kaizen.logextractor;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.drinkless.tdlib.Client;
import org.drinkless.tdlib.TdApi;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** TDLib adapter. It exposes bounded chat/history browsing and one-at-a-time archive imports. */
final class TelegramTdLibManager {
    private static final String TAG = "KaizenTelegram";
    private static final int MAX_CHATS = 50;
    private static final int MAX_MESSAGES = 50;
    private static final long MAX_DOWNLOAD_BYTES = ArchiveProcessor.MAX_INPUT_BYTES;
    private static final long DISK_RESERVE_BYTES = 32L * 1024L * 1024L;
    private static final long DOWNLOAD_TIMEOUT_HOURS = 12L;
    private static volatile TelegramTdLibManager instance;

    enum InputKind { PHONE, CODE, PASSWORD }

    interface Listener {
        void onStatus(String message);
        void onInputRequired(InputKind kind, String hint);
        void onReady();
        void onChatsLoaded(List<ChatItem> chats);
        void onArchivesLoaded(ChatItem chat, List<ArchiveItem> archives);
        void onArchiveImported(File file, String originalName);
        void onLoggedOut();
        void onError(String message);
    }

    static final class ChatItem {
        final long id;
        final String title;

        ChatItem(long id, String title) {
            this.id = id;
            this.title = title == null || title.trim().isEmpty() ? "İsimsiz sohbet" : title;
        }

        @Override public String toString() { return title; }
    }

    static final class ArchiveItem {
        final long chatId;
        final long messageId;
        final int fileId;
        final long size;
        final String name;

        ArchiveItem(long chatId, long messageId, int fileId, long size, String name) {
            this.chatId = chatId;
            this.messageId = messageId;
            this.fileId = fileId;
            this.size = size;
            this.name = name;
        }
    }

    private final Context context;
    private final TelegramCredentialStore credentialStore;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService fileWorker = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "kaizen-telegram-import");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean requestInProgress = new AtomicBoolean(false);
    private final AtomicBoolean parametersSent = new AtomicBoolean(false);
    private final AtomicBoolean downloadAwaitingResult = new AtomicBoolean(false);
    private final AtomicBoolean importingFile = new AtomicBoolean(false);
    private final AtomicBoolean importCancellationRequested = new AtomicBoolean(false);
    private final AtomicLong downloadGeneration = new AtomicLong();
    private final AtomicBoolean logoutRequested = new AtomicBoolean(false);
    private final ScheduledExecutorService timeoutWorker = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "kaizen-telegram-timeout");
        thread.setDaemon(true);
        return thread;
    });
    private volatile Listener listener;
    private volatile Client client;
    private volatile boolean authorized;
    private volatile InputKind pendingInput;
    private volatile String pendingInputHint;
    private volatile byte[] databaseKey;
    private volatile int apiId;
    private volatile String apiHash;
    private volatile TdApi.AuthorizationState pendingAuthorizationState;
    private volatile TdApi.AuthorizationState currentAuthorizationState;
    private volatile ScheduledFuture<?> activeDownloadTimeout;
    private volatile int activeDownloadFileId;

    private TelegramTdLibManager(Context context) {
        this.context = context.getApplicationContext();
        this.credentialStore = new TelegramCredentialStore(this.context);
    }

    static TelegramTdLibManager getInstance(Context context) {
        TelegramTdLibManager current = instance;
        if (current == null) {
            synchronized (TelegramTdLibManager.class) {
                current = instance;
                if (current == null) instance = current = new TelegramTdLibManager(context);
            }
        }
        return current;
    }

    void attach(Listener listener) {
        this.listener = listener;
        if (authorized) {
            post(Listener::onReady);
        } else if (pendingInput != null) {
            InputKind kind = pendingInput;
            String hint = pendingInputHint;
            post(l -> l.onInputRequired(kind, hint));
        }
    }

    void detach(Listener listener) {
        if (this.listener == listener) {
            this.listener = null;
            cancelDownloadForDetachedScreen();
        }
    }

    private void cancelDownloadForDetachedScreen() {
        if (!downloadAwaitingResult.compareAndSet(true, false)) {
            if (importingFile.get()) importCancellationRequested.set(true);
            return;
        }
        downloadGeneration.incrementAndGet();
        int fileId = activeDownloadFileId;
        activeDownloadFileId = 0;
        ScheduledFuture<?> timeout = activeDownloadTimeout;
        if (timeout != null) timeout.cancel(false);
        requestInProgress.set(false);
        Client active = client;
        if (active == null || fileId <= 0) return;
        try {
            active.send(new TdApi.CancelDownloadFile(fileId, false), object -> {
                if (object instanceof TdApi.Error) {
                    TdApi.Error error = (TdApi.Error) object;
                    Log.w(TAG, "Download cancellation after screen exit failed " + error.code + ": " + error.message);
                }
            });
        } catch (RuntimeException e) {
            Log.e(TAG, "Could not cancel download after screen exit", e);
        }
    }

    TelegramCredentialStore credentialStore() {
        return credentialStore;
    }

    boolean canEditCredentials() {
        return client == null || (currentAuthorizationState instanceof TdApi.AuthorizationStateWaitTdlibParameters
                && !parametersSent.get());
    }

    void resetCredentialsAndLocalDatabase() throws Exception {
        if (!canEditCredentials()) {
            throw new IllegalStateException("Etkin Telegram oturumu kapatılmadan yerel veritabanı sıfırlanamaz.");
        }
        File tdlibDirectory = new File(context.getFilesDir(), "tdlib");
        if (!deleteRecursively(tdlibDirectory)) {
            throw new IOException("TDLib yerel veritabanı silinemedi.");
        }
        credentialStore.clear();
        if (databaseKey != null) java.util.Arrays.fill(databaseKey, (byte) 0);
        databaseKey = credentialStore.getOrCreateDatabaseKey();
        apiId = 0;
        apiHash = null;
        authorized = false;
        pendingInput = null;
        pendingInputHint = null;
        pendingAuthorizationState = null;
        parametersSent.set(false);
    }

    void connect(int apiId, String apiHash) {
        if (apiId <= 0 || apiHash == null || !apiHash.matches("(?i)[0-9a-f]{32}")) {
            emitError("Telegram API ID veya API Hash geçersiz.");
            return;
        }
        if (client != null) {
            boolean credentialsChanged = this.apiId != apiId || !java.util.Objects.equals(this.apiHash, apiHash);
            if (currentAuthorizationState instanceof TdApi.AuthorizationStateWaitTdlibParameters
                    && !parametersSent.get()) {
                this.apiId = apiId;
                this.apiHash = apiHash;
                handleAuthorizationState(currentAuthorizationState);
            } else if (credentialsChanged) {
                emitError("Etkin Telegram oturumu varken API bilgileri değiştirilemez. Önce oturumu kapat.");
            } else if (authorized) {
                post(Listener::onReady);
            } else if (pendingInput != null) {
                InputKind kind = pendingInput;
                String hint = pendingInputHint;
                post(l -> l.onInputRequired(kind, hint));
            } else {
                emitStatus("Telegram oturumunun yetkilendirme durumu bekleniyor…");
            }
            return;
        }
        this.apiId = apiId;
        this.apiHash = apiHash;
        emitStatus("TDLib başlatılıyor…");
        try {
            databaseKey = credentialStore.getOrCreateDatabaseKey();
            // The pinned Android AAR supplies libtdjni.so and the generated Java API.
            System.loadLibrary("tdjni");
            Client created = Client.create(this::onUpdate, this::onCallbackException, this::onCallbackException);
            client = created;
            TdApi.AuthorizationState pending = pendingAuthorizationState;
            if (pending != null) {
                pendingAuthorizationState = null;
                handleAuthorizationState(pending);
            }
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "TDLib native library could not be loaded", e);
            emitError("TDLib'in yerel kütüphanesi yüklenemedi. APK'nın ABI paketlemesini kontrol et.");
        } catch (Exception e) {
            Log.e(TAG, "TDLib initialization failed", e);
            emitError("Telegram güvenli ayarları okunamadı: " + readableMessage(e));
        }
    }

    void submitPhone(String phone) {
        String normalized = phone == null ? "" : phone.replaceAll("[\\s()-]", "");
        if (!normalized.matches("\\+?[0-9]{5,20}")) {
            emitError("Telefon numarasını ülke koduyla birlikte gir. Örnek biçim: +90…");
            return;
        }
        send(new TdApi.SetAuthenticationPhoneNumber(normalized, null), "Telefon numarası gönderilemedi.");
    }

    void submitCode(String code) {
        String normalized = code == null ? "" : code.trim();
        if (normalized.isEmpty() || normalized.length() > 32) {
            emitError("Doğrulama kodu boş veya geçersiz.");
            return;
        }
        send(new TdApi.CheckAuthenticationCode(normalized), "Doğrulama kodu kontrol edilemedi.");
    }

    void submitPassword(String password) {
        if (password == null || password.isEmpty() || password.length() > 256) {
            emitError("İki aşamalı doğrulama parolası boş veya çok uzun.");
            return;
        }
        send(new TdApi.CheckAuthenticationPassword(password), "İki aşamalı doğrulama başarısız.");
    }

    void logout() {
        Client active = client;
        if (active == null || !authorized) {
            emitError("Çıkış yapılacak etkin bir Telegram oturumu yok.");
            return;
        }
        if (!requestInProgress.compareAndSet(false, true)) {
            emitError("Telegram isteği devam ederken oturum kapatılamaz. İşlemin tamamlanmasını bekle.");
            return;
        }
        logoutRequested.set(true);
        emitStatus("Telegram oturumu kapatılıyor…");
        try {
            active.send(new TdApi.LogOut(), object -> {
                if (object instanceof TdApi.Error) {
                    logoutRequested.set(false);
                    requestInProgress.set(false);
                    reportIfError(object, "Telegram oturumu kapatılamadı.");
                }
            });
        } catch (RuntimeException e) {
            logoutRequested.set(false);
            requestInProgress.set(false);
            Log.e(TAG, "Telegram logout request failed", e);
            emitError("Telegram oturumu kapatılamadı: " + readableMessage(e));
        }
    }

    void loadChats() {
        Client active = client;
        if (active == null || !authorized) {
            emitError("Önce Telegram hesabını bağlamalısın.");
            return;
        }
        if (!requestInProgress.compareAndSet(false, true)) {
            emitStatus("Başka bir Telegram isteği devam ediyor…");
            return;
        }
        emitStatus("Ana sohbet listesi yükleniyor…");
        try {
            active.send(new TdApi.LoadChats(new TdApi.ChatListMain(), MAX_CHATS), object -> {
                if (object instanceof TdApi.Error) {
                    TdApi.Error error = (TdApi.Error) object;
                    // TDLib uses 404 to signal that the requested list has no more chats.
                    if (error.code != 404) {
                        requestInProgress.set(false);
                        reportIfError(object, "Sohbet listesi yüklenemedi.");
                        return;
                    }
                } else if (!(object instanceof TdApi.Ok)) {
                    requestInProgress.set(false);
                    emitError("TDLib sohbet yükleme isteği için beklenmeyen bir yanıt döndürdü.");
                    return;
                }
                fetchChats(active);
            });
        } catch (RuntimeException e) {
            requestInProgress.set(false);
            Log.e(TAG, "LoadChats request failed", e);
            emitError("Sohbet listesi yüklenemedi: " + readableMessage(e));
        }
    }

    private void fetchChats(Client active) {
        try {
            active.send(new TdApi.GetChats(new TdApi.ChatListMain(), MAX_CHATS), object -> {
                if (reportIfError(object, "Sohbet listesi alınamadı.")) {
                    requestInProgress.set(false);
                    return;
                }
                if (!(object instanceof TdApi.Chats)) {
                    requestInProgress.set(false);
                    emitError("TDLib sohbet listesi yerine beklenmeyen bir yanıt döndürdü.");
                    return;
                }
                long[] ids = ((TdApi.Chats) object).chatIds;
                if (ids == null || ids.length == 0) {
                    requestInProgress.set(false);
                    post(l -> l.onChatsLoaded(Collections.emptyList()));
                    return;
                }
                int count = Math.min(ids.length, MAX_CHATS);
                List<ChatItem> chats = Collections.synchronizedList(new ArrayList<>());
                AtomicBoolean delivered = new AtomicBoolean(false);
                java.util.concurrent.atomic.AtomicInteger remaining = new java.util.concurrent.atomic.AtomicInteger(count);
                for (int i = 0; i < count; i++) {
                    final long chatId = ids[i];
                    try {
                        active.send(new TdApi.GetChat(chatId), chatObject -> {
                            if (chatObject instanceof TdApi.Chat) {
                                TdApi.Chat chat = (TdApi.Chat) chatObject;
                                chats.add(new ChatItem(chat.id, chat.title));
                            } else if (chatObject instanceof TdApi.Error) {
                                TdApi.Error error = (TdApi.Error) chatObject;
                                Log.w(TAG, "Could not load one chat (" + error.code + "): " + error.message);
                            }
                            if (remaining.decrementAndGet() == 0 && delivered.compareAndSet(false, true)) {
                                deliverChats(chats);
                            }
                        });
                    } catch (RuntimeException chatRequestError) {
                        Log.e(TAG, "GetChat request failed for one chat", chatRequestError);
                        if (remaining.decrementAndGet() == 0 && delivered.compareAndSet(false, true)) {
                            deliverChats(chats);
                        }
                    }
                }
            });
        } catch (RuntimeException e) {
            requestInProgress.set(false);
            Log.e(TAG, "GetChats request failed", e);
            emitError("Sohbet isteği gönderilemedi: " + readableMessage(e));
        }
    }

    private void deliverChats(List<ChatItem> chats) {
        requestInProgress.set(false);
        List<ChatItem> sorted = new ArrayList<>(chats);
        sorted.sort(Comparator.comparing(item -> item.title.toLowerCase(Locale.ROOT)));
        post(l -> l.onChatsLoaded(sorted));
    }

    void loadArchives(ChatItem chat) {
        if (chat == null) {
            emitError("Sohbet seçimi geçersiz.");
            return;
        }
        Client active = client;
        if (active == null || !authorized) {
            emitError("Telegram bağlantısı hazır değil.");
            return;
        }
        if (!requestInProgress.compareAndSet(false, true)) {
            emitStatus("Başka bir Telegram isteği devam ediyor…");
            return;
        }
        emitStatus("Seçilen sohbetin son " + MAX_MESSAGES + " mesajı taranıyor…");
        try {
            active.send(new TdApi.GetChatHistory(chat.id, 0L, 0, MAX_MESSAGES, false), object -> {
                requestInProgress.set(false);
                if (reportIfError(object, "Sohbet geçmişi alınamadı.")) return;
                if (!(object instanceof TdApi.Messages)) {
                    emitError("TDLib mesaj listesi yerine beklenmeyen bir yanıt döndürdü.");
                    return;
                }
                TdApi.Message[] messages = ((TdApi.Messages) object).messages;
                List<ArchiveItem> archives = new ArrayList<>();
                Set<Integer> seenFileIds = new HashSet<>();
                if (messages != null) {
                    for (TdApi.Message message : messages) {
                        if (message == null || !(message.content instanceof TdApi.MessageDocument)) continue;
                        TdApi.Document document = ((TdApi.MessageDocument) message.content).document;
                        if (document == null || document.document == null || document.document.id <= 0) continue;
                        String name = document.fileName;
                        if (!TelegramArchiveRules.isSingleArchiveName(name)) continue;
                        TdApi.File file = document.document;
                        if (file.size > MAX_DOWNLOAD_BYTES) continue;
                        if (!seenFileIds.add(file.id)) continue;
                        try {
                            archives.add(new ArchiveItem(chat.id, message.id, file.id,
                                    Math.max(0L, file.size), TelegramArchiveRules.sanitizeFileName(name)));
                        } catch (IllegalArgumentException e) {
                            Log.w(TAG, "Skipping Telegram document with unsafe name", e);
                        }
                    }
                }
                post(l -> l.onArchivesLoaded(chat, archives));
            });
        } catch (RuntimeException e) {
            requestInProgress.set(false);
            Log.e(TAG, "GetChatHistory request failed", e);
            emitError("Mesaj isteği gönderilemedi: " + readableMessage(e));
        }
    }

    void downloadArchive(ArchiveItem archive) {
        if (archive == null || archive.fileId <= 0 || !TelegramArchiveRules.isSupportedArchiveName(archive.name)) {
            emitError("Arşiv seçimi geçersiz.");
            return;
        }
        if (archive.size > MAX_DOWNLOAD_BYTES) {
            emitError("Seçilen arşiv mevcut 64 GiB sınırını aşıyor.");
            return;
        }
        Client active = client;
        if (active == null || !authorized) {
            emitError("Telegram bağlantısı hazır değil.");
            return;
        }
        if (!requestInProgress.compareAndSet(false, true)) {
            emitStatus("Başka bir Telegram isteği devam ediyor…");
            return;
        }
        Listener requestListener = listener;
        if (requestListener == null) {
            requestInProgress.set(false);
            emitError("Telegram ekranı hazır değil. Yeniden açıp dene.");
            return;
        }
        emitStatus("Seçilen arşiv indiriliyor: " + archive.name);
        try {
            // Synchronous mode completes only after success, failure, or cancellation.
            final long generation = downloadGeneration.incrementAndGet();
            importCancellationRequested.set(false);
            importingFile.set(false);
            activeDownloadFileId = archive.fileId;
            downloadAwaitingResult.set(true);
            activeDownloadTimeout = timeoutWorker.schedule(() -> {
                if (downloadGeneration.get() != generation || !downloadAwaitingResult.compareAndSet(true, false)) return;
                activeDownloadFileId = 0;
                requestInProgress.set(false);
                Client current = client;
                if (current != null) {
                    try {
                        current.send(new TdApi.CancelDownloadFile(archive.fileId, false), cancelResult -> {
                            if (cancelResult instanceof TdApi.Error) {
                                TdApi.Error error = (TdApi.Error) cancelResult;
                                Log.w(TAG, "TDLib download timeout cancel returned " + error.code + ": " + error.message);
                            }
                        });
                    } catch (RuntimeException cancelError) {
                        Log.e(TAG, "Could not request TDLib download cancellation", cancelError);
                    }
                }
                emitError("Telegram indirmesi 12 saat içinde tamamlanmadı; iptal isteği gönderildi.");
            }, DOWNLOAD_TIMEOUT_HOURS, TimeUnit.HOURS);
            active.send(new TdApi.DownloadFile(archive.fileId, 32, 0L, 0L, true), object -> {
                if (downloadGeneration.get() != generation || !downloadAwaitingResult.compareAndSet(true, false)) return;
                importingFile.set(true);
                activeDownloadFileId = 0;
                ScheduledFuture<?> timeout = activeDownloadTimeout;
                if (timeout != null) timeout.cancel(false);
                if (reportIfError(object, "Telegram dosyası indirilemedi.")) {
                    importingFile.set(false);
                    requestInProgress.set(false);
                    return;
                }
                if (!(object instanceof TdApi.File)) {
                    importingFile.set(false);
                    requestInProgress.set(false);
                    emitError("TDLib dosya indirme sonucu yerine beklenmeyen bir yanıt döndürdü.");
                    return;
                }
                TdApi.File downloaded = (TdApi.File) object;
                if (downloaded.local == null || !downloaded.local.isDownloadingCompleted
                        || downloaded.local.path == null || downloaded.local.path.trim().isEmpty()) {
                    importingFile.set(false);
                    requestInProgress.set(false);
                    emitError("Telegram indirmesi tamamlandı olarak işaretlenmedi; dosya içe aktarılmadı.");
                    return;
                }
                final String path = downloaded.local.path;
                try {
                    fileWorker.execute(() -> copyDownloadedFile(path, archive.name, requestListener));
                } catch (java.util.concurrent.RejectedExecutionException e) {
                    importingFile.set(false);
                    requestInProgress.set(false);
                    Log.e(TAG, "Telegram file worker rejected import", e);
                    emitError("Telegram dosya işçisi şu anda kullanılamıyor.");
                }
            });
        } catch (RuntimeException e) {
            downloadGeneration.incrementAndGet();
            downloadAwaitingResult.set(false);
            activeDownloadFileId = 0;
            ScheduledFuture<?> timeout = activeDownloadTimeout;
            if (timeout != null) timeout.cancel(false);
            requestInProgress.set(false);
            Log.e(TAG, "DownloadFile request failed", e);
            emitError("İndirme başlatılamadı: " + readableMessage(e));
        }
    }

    private void copyDownloadedFile(String sourcePath, String originalName, Listener expectedListener) {
        File destination = null;
        boolean completed = false;
        boolean callbackPosted = false;
        try {
            if (expectedListener == null || listener != expectedListener || importCancellationRequested.get()) {
                throw new IOException("Telegram ekranı kapatıldığı için içe aktarma iptal edildi.");
            }
            File source = new File(sourcePath).getCanonicalFile();
            File tdlibFilesRoot = new File(context.getFilesDir(), "tdlib/files").getCanonicalFile();
            String tdlibFilesPrefix = tdlibFilesRoot.getPath() + File.separator;
            if (!source.getPath().startsWith(tdlibFilesPrefix)) {
                throw new IOException("TDLib indirme yolu uygulamanın özel dosya klasörünün dışında.");
            }
            if (!source.isFile() || !source.canRead()) throw new IOException("TDLib indirme dosyası okunamıyor.");
            long sourceLength = source.length();
            if (sourceLength <= 0L) throw new IOException("Telegram'dan indirilen dosya boş.");
            if (sourceLength > MAX_DOWNLOAD_BYTES) throw new IOException("İndirilen dosya 64 GiB sınırını aşıyor.");

            File directory = new File(context.getFilesDir(), "telegram-import");
            if (!directory.exists() && !directory.mkdirs() && !directory.isDirectory()) {
                throw new IOException("Telegram içe aktarma klasörü oluşturulamadı.");
            }
            if (directory.getUsableSpace() < sourceLength + DISK_RESERVE_BYTES) {
                throw new IOException("İçe aktarma için yeterli boş depolama alanı yok.");
            }
            String safeName = TelegramArchiveRules.sanitizeFileName(originalName);
            destination = new File(directory, UUID.randomUUID().toString() + "-" + safeName);
            long copied = 0L;
            try (FileInputStream input = new FileInputStream(source);
                 FileOutputStream output = new FileOutputStream(destination)) {
                byte[] buffer = new byte[128 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    if (importCancellationRequested.get() || listener != expectedListener) {
                        throw new IOException("Telegram ekranı kapatıldığı için içe aktarma iptal edildi.");
                    }
                    if (read == 0) continue;
                    if (copied > MAX_DOWNLOAD_BYTES - read) {
                        throw new IOException("İndirilen dosya 64 GiB sınırını aşıyor.");
                    }
                    output.write(buffer, 0, read);
                    copied += read;
                }
                output.flush();
                output.getFD().sync();
            }
            if (copied != sourceLength || copied <= 0L) {
                throw new IOException("İçe aktarılan dosyanın boyutu kaynak dosyayla eşleşmiyor.");
            }
            File imported = destination;
            callbackPosted = mainHandler.post(() -> {
                try {
                    if (listener == expectedListener && expectedListener != null) {
                        expectedListener.onArchiveImported(imported, safeName);
                    } else if (imported.exists() && !imported.delete()) {
                        Log.w(TAG, "Unused Telegram import could not be deleted: " + imported.getAbsolutePath());
                    }
                } catch (RuntimeException callbackError) {
                    Log.e(TAG, "Telegram import result callback failed", callbackError);
                    emitError("İçe aktarılan arşiv ekrana aktarılamadı: " + readableMessage(callbackError));
                } finally {
                    importingFile.set(false);
                    requestInProgress.set(false);
                }
            });
            if (!callbackPosted) throw new IOException("Telegram içe aktarma sonucu uygulama ekranına iletilemedi.");
            completed = true;
        } catch (Exception e) {
            Log.e(TAG, "Telegram archive import failed", e);
            emitError("Telegram arşivi içe aktarılamadı: " + readableMessage(e));
        } finally {
            if (!completed && destination != null && destination.exists() && !destination.delete()) {
                Log.w(TAG, "Partial Telegram import could not be deleted: " + destination.getAbsolutePath());
            }
            if (!callbackPosted) {
                importingFile.set(false);
                requestInProgress.set(false);
            }
        }
    }

    private void onUpdate(TdApi.Object object) {
        if (object instanceof TdApi.UpdateAuthorizationState) {
            TdApi.AuthorizationState state = ((TdApi.UpdateAuthorizationState) object).authorizationState;
            if (client == null) pendingAuthorizationState = state;
            else handleAuthorizationState(state);
        }
    }

    private void handleAuthorizationState(TdApi.AuthorizationState state) {
        currentAuthorizationState = state;
        if (state instanceof TdApi.AuthorizationStateWaitTdlibParameters) {
            if (!parametersSent.compareAndSet(false, true)) return;
            emitStatus("TDLib veritabanı hazırlanıyor…");
            try {
                File databaseDirectory = new File(context.getFilesDir(), "tdlib/database");
                File filesDirectory = new File(context.getFilesDir(), "tdlib/files");
                if (!databaseDirectory.exists() && !databaseDirectory.mkdirs() && !databaseDirectory.isDirectory()) {
                    throw new IOException("TDLib veritabanı klasörü oluşturulamadı.");
                }
                if (!filesDirectory.exists() && !filesDirectory.mkdirs() && !filesDirectory.isDirectory()) {
                    throw new IOException("TDLib dosya klasörü oluşturulamadı.");
                }
                TdApi.SetTdlibParameters parameters = new TdApi.SetTdlibParameters();
                parameters.useTestDc = false;
                parameters.databaseDirectory = databaseDirectory.getAbsolutePath();
                parameters.filesDirectory = filesDirectory.getAbsolutePath();
                parameters.databaseEncryptionKey = databaseKey;
                parameters.useFileDatabase = true;
                parameters.useChatInfoDatabase = true;
                parameters.useMessageDatabase = true;
                parameters.useSecretChats = false;
                parameters.apiId = apiId;
                parameters.apiHash = apiHash;
                parameters.systemLanguageCode = Locale.getDefault().toLanguageTag();
                parameters.deviceModel = Build.MODEL == null ? "Android" : Build.MODEL;
                parameters.systemVersion = Build.VERSION.RELEASE == null ? "Android" : Build.VERSION.RELEASE;
                parameters.applicationVersion = "1.4.0";
                send(parameters, "TDLib parametreleri ayarlanamadı.");
            } catch (Exception e) {
                parametersSent.set(false);
                Log.e(TAG, "Could not configure TDLib", e);
                emitError("TDLib kurulumu tamamlanamadı: " + readableMessage(e));
            }
            return;
        }
        if (state instanceof TdApi.AuthorizationStateWaitPhoneNumber) {
            pendingInput = InputKind.PHONE;
            pendingInputHint = "Ülke koduyla Telegram telefon numaran";
            post(l -> l.onInputRequired(InputKind.PHONE, pendingInputHint));
            return;
        }
        if (state instanceof TdApi.AuthorizationStateWaitCode) {
            pendingInput = InputKind.CODE;
            pendingInputHint = "Telegram tarafından gönderilen doğrulama kodu";
            post(l -> l.onInputRequired(InputKind.CODE, pendingInputHint));
            return;
        }
        if (state instanceof TdApi.AuthorizationStateWaitPassword) {
            String hint = ((TdApi.AuthorizationStateWaitPassword) state).passwordHint;
            pendingInput = InputKind.PASSWORD;
            pendingInputHint = hint == null || hint.isEmpty() ? "İki aşamalı doğrulama parolası" : "Parola ipucu: " + hint;
            post(l -> l.onInputRequired(InputKind.PASSWORD, pendingInputHint));
            return;
        }
        if (state instanceof TdApi.AuthorizationStateReady) {
            authorized = true;
            pendingInput = null;
            pendingInputHint = null;
            post(Listener::onReady);
            return;
        }
        authorized = false;
        if (state instanceof TdApi.AuthorizationStateWaitEmailAddress
                || state instanceof TdApi.AuthorizationStateWaitEmailCode) {
            emitError("Bu sürüm telefon, doğrulama kodu ve iki aşamalı parola akışını destekliyor; e-posta doğrulama akışı henüz eklenmedi.");
        } else if (state instanceof TdApi.AuthorizationStateWaitRegistration) {
            emitError("Bu Telegram hesabı için yeni hesap kaydı gerekiyor; kayıt akışı bu sürümde desteklenmiyor.");
        } else if (state instanceof TdApi.AuthorizationStateWaitOtherDeviceConfirmation) {
            emitError("Başka cihazla QR onayı bu sürümde desteklenmiyor. Telefon numarasıyla oturum açmayı kullan.");
        } else if (state instanceof TdApi.AuthorizationStateClosing || state instanceof TdApi.AuthorizationStateLoggingOut) {
            emitStatus("Telegram oturumu kapatılıyor…");
        } else if (state instanceof TdApi.AuthorizationStateClosed) {
            client = null;
            parametersSent.set(false);
            requestInProgress.set(false);
            if (logoutRequested.compareAndSet(true, false)) {
                try {
                    File tdlibDirectory = new File(context.getFilesDir(), "tdlib");
                    if (!deleteRecursively(tdlibDirectory)) {
                        throw new IOException("TDLib yerel oturum dosyaları silinemedi.");
                    }
                    credentialStore.clear();
                    if (databaseKey != null) java.util.Arrays.fill(databaseKey, (byte) 0);
                    databaseKey = null;
                    apiId = 0;
                    apiHash = null;
                    post(Listener::onLoggedOut);
                } catch (Exception e) {
                    Log.e(TAG, "Telegram local session cleanup failed", e);
                    emitError("Oturum kapandı ancak yerel Telegram verileri temizlenemedi: " + readableMessage(e));
                }
            } else {
                emitStatus("Telegram oturumu kapandı. Yeniden bağlanabilirsin.");
            }
        } else {
            String stateName = state == null ? "null" : state.getClass().getSimpleName();
            emitError("TDLib bu sürümde desteklenmeyen bir yetkilendirme durumu bildirdi: " + stateName);
        }
    }

    private void send(TdApi.Function<?> function, String errorPrefix) {
        Client active = client;
        if (active == null) {
            emitError("TDLib istemcisi başlatılmadı.");
            return;
        }
        try {
            active.send(function, object -> {
                if (object instanceof TdApi.Error) {
                    TdApi.Error error = (TdApi.Error) object;
                    if (function instanceof TdApi.SetTdlibParameters) parametersSent.set(false);
                    Log.e(TAG, errorPrefix + " TDLib " + error.code + ": " + error.message);
                    emitError(errorPrefix + " " + error.message);
                }
            });
        } catch (RuntimeException e) {
            if (function instanceof TdApi.SetTdlibParameters) parametersSent.set(false);
            Log.e(TAG, "TDLib request could not be sent", e);
            emitError(errorPrefix + " " + readableMessage(e));
        }
    }

    private boolean reportIfError(TdApi.Object object, String prefix) {
        if (!(object instanceof TdApi.Error)) return false;
        TdApi.Error error = (TdApi.Error) object;
        Log.e(TAG, prefix + " TDLib " + error.code + ": " + error.message);
        emitError(prefix + " " + error.message);
        return true;
    }

    private void onCallbackException(Throwable error) {
        Log.e(TAG, "Exception from TDLib callback", error);
        emitError("TDLib geri çağrısında hata oluştu: " + readableMessage(error));
    }

    private void emitStatus(String message) {
        post(l -> l.onStatus(message));
    }

    private void emitError(String message) {
        post(l -> l.onError(message));
    }

    private void post(java.util.function.Consumer<Listener> callback) {
        mainHandler.post(() -> {
            Listener current = listener;
            if (current != null) callback.accept(current);
        });
    }

    private static boolean deleteRecursively(File file) {
        if (file == null || !file.exists()) return true;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children == null) return false;
            for (File child : children) {
                if (!deleteRecursively(child)) return false;
            }
        }
        return file.delete();
    }

    private static String readableMessage(Throwable error) {
        String message = error.getMessage();
        if (message == null || message.trim().isEmpty()) return error.getClass().getSimpleName();
        return message;
    }
}
