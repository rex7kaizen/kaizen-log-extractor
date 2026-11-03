package com.kaizen.logextractor;

import java.nio.channels.SeekableByteChannel;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.drinkless.tdlib.Client;
import org.drinkless.tdlib.TdApi;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/** TDLib adapter. It exposes bounded chat/history browsing and one-at-a-time archive imports. */
final class TelegramTdLibManager {
    private static final String TAG = "KaizenTelegram";
    private static final int MAX_CHATS = 200;
    private static final int MAX_CHAT_LOAD_ATTEMPTS = 6;
    private static final int MAX_TOTAL_CHATS = 500;
    private static final int MAX_CUSTOM_CHAT_FOLDERS = 10;
    private static final int CHAT_DETAIL_BATCH_SIZE = 20;
    private static final int MAX_MESSAGES = 100;
    private static final int MAX_ARCHIVES = 5;
    private static final int MAX_HISTORY_MESSAGES_TO_SCAN = 20000;
    private static final long MAX_DOWNLOAD_BYTES = ArchiveProcessor.MAX_INPUT_BYTES;
    private static volatile TelegramTdLibManager instance;

    enum InputKind { PHONE, CODE, PASSWORD }

    interface Listener {
        void onStatus(String message);
        void onInputRequired(InputKind kind, String hint);
        void onReady();
        void onChatsLoaded(List<ChatItem> chats);
        void onArchivesLoaded(ChatItem chat, List<ArchiveItem> archives);
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
        final int date;
        final String name;

        ArchiveItem(long chatId, long messageId, int fileId, long size, int date, String name) {
            this.chatId = chatId;
            this.messageId = messageId;
            this.fileId = fileId;
            this.size = size;
            this.date = date;
            this.name = name;
        }
    }

    private final Context context;
    private final TelegramCredentialStore credentialStore;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean requestInProgress = new AtomicBoolean(false);
    private final AtomicBoolean parametersSent = new AtomicBoolean(false);
    private final AtomicBoolean logoutRequested = new AtomicBoolean(false);
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
    private volatile int[] chatFolderIds = new int[0];

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
        if (this.listener == listener) this.listener = null;
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
        emitStatus("Ana, arşivlenmiş ve özel klasörlerdeki sohbetler yükleniyor…");
        List<TdApi.ChatList> lists = new ArrayList<>();
        lists.add(new TdApi.ChatListMain());
        lists.add(new TdApi.ChatListArchive());
        int[] folderIds = chatFolderIds;
        if (folderIds != null) {
            int count = Math.min(folderIds.length, MAX_CUSTOM_CHAT_FOLDERS);
            for (int i = 0; i < count; i++) {
                if (folderIds[i] > 0) lists.add(new TdApi.ChatListFolder(folderIds[i]));
            }
        }
        loadChatList(active, lists, 0, new java.util.LinkedHashSet<>());
    }

    private void loadChatList(Client active, List<TdApi.ChatList> lists, int listIndex,
                              java.util.LinkedHashSet<Long> chatIds) {
        if (chatIds.size() >= MAX_TOTAL_CHATS || listIndex >= lists.size()) {
            fetchChatDetails(active, new ArrayList<>(chatIds), 0,
                    Collections.synchronizedList(new ArrayList<>()));
            return;
        }
        loadChatListPage(active, lists, listIndex, lists.get(listIndex), 0, chatIds);
    }

    private void loadChatListPage(Client active, List<TdApi.ChatList> lists, int listIndex,
                                  TdApi.ChatList chatList, int attempt,
                                  java.util.LinkedHashSet<Long> chatIds) {
        if (attempt >= MAX_CHAT_LOAD_ATTEMPTS || chatIds.size() >= MAX_TOTAL_CHATS) {
            readChatIds(active, lists, listIndex, chatList, attempt, true, chatIds);
            return;
        }
        try {
            active.send(new TdApi.LoadChats(chatList, MAX_CHATS), object -> {
                if (object instanceof TdApi.Error) {
                    TdApi.Error error = (TdApi.Error) object;
                    if (error.code != 404) {
                        Log.e(TAG, "LoadChats failed for one Telegram list (" + error.code + "): " + error.message);
                    }
                    readChatIds(active, lists, listIndex, chatList, attempt, true, chatIds);
                    return;
                }
                if (!(object instanceof TdApi.Ok)) {
                    Log.e(TAG, "LoadChats returned an unexpected response: "
                            + (object == null ? "null" : object.getClass().getSimpleName()));
                    readChatIds(active, lists, listIndex, chatList, attempt, true, chatIds);
                    return;
                }
                // TDLib may load fewer than requested while more chats remain. Read the
                // loaded count before asking for another page to avoid redundant loading.
                readChatIds(active, lists, listIndex, chatList, attempt + 1, false, chatIds);
            });
        } catch (RuntimeException e) {
            Log.e(TAG, "LoadChats request failed", e);
            readChatIds(active, lists, listIndex, chatList, attempt, true, chatIds);
        }
    }

    private void readChatIds(Client active, List<TdApi.ChatList> lists, int listIndex,
                             TdApi.ChatList chatList, int attempt, boolean listComplete,
                             java.util.LinkedHashSet<Long> chatIds) {
        try {
            active.send(new TdApi.GetChats(chatList, MAX_CHATS), object -> {
                int returnedCount = 0;
                boolean completeThisList = listComplete;
                if (object instanceof TdApi.Chats) {
                    long[] ids = ((TdApi.Chats) object).chatIds;
                    returnedCount = ids == null ? 0 : ids.length;
                    if (ids != null) {
                        for (long id : ids) {
                            if (id > 0L && chatIds.size() < MAX_TOTAL_CHATS) chatIds.add(id);
                        }
                    }
                } else if (object instanceof TdApi.Error) {
                    TdApi.Error error = (TdApi.Error) object;
                    Log.e(TAG, "GetChats failed for one Telegram list (" + error.code + "): " + error.message);
                    completeThisList = true;
                } else {
                    Log.e(TAG, "GetChats returned an unexpected response: "
                            + (object == null ? "null" : object.getClass().getSimpleName()));
                    completeThisList = true;
                }
                if (completeThisList || returnedCount >= MAX_CHATS
                        || attempt >= MAX_CHAT_LOAD_ATTEMPTS || chatIds.size() >= MAX_TOTAL_CHATS) {
                    loadChatList(active, lists, listIndex + 1, chatIds);
                } else {
                    loadChatListPage(active, lists, listIndex, chatList, attempt, chatIds);
                }
            });
        } catch (RuntimeException e) {
            Log.e(TAG, "GetChats request failed", e);
            loadChatList(active, lists, listIndex + 1, chatIds);
        }
    }

    private void fetchChatDetails(Client active, List<Long> ids, int start, List<ChatItem> chats) {
        if (start >= ids.size()) {
            deliverChats(chats);
            return;
        }
        int end = Math.min(ids.size(), start + CHAT_DETAIL_BATCH_SIZE);
        java.util.concurrent.atomic.AtomicInteger remaining = new java.util.concurrent.atomic.AtomicInteger(end - start);
        Runnable continueAfterBatch = () -> {
            if (remaining.decrementAndGet() == 0) fetchChatDetails(active, ids, end, chats);
        };
        for (int i = start; i < end; i++) {
            final long chatId = ids.get(i);
            try {
                active.send(new TdApi.GetChat(chatId), chatObject -> {
                    try {
                        if (chatObject instanceof TdApi.Chat) {
                            TdApi.Chat chat = (TdApi.Chat) chatObject;
                            chats.add(new ChatItem(chat.id, chat.title));
                        } else if (chatObject instanceof TdApi.Error) {
                            TdApi.Error error = (TdApi.Error) chatObject;
                            Log.w(TAG, "Could not load Telegram chat " + chatId + " (" + error.code + "): " + error.message);
                        } else {
                            Log.w(TAG, "GetChat returned an unexpected response for " + chatId);
                        }
                    } catch (RuntimeException callbackError) {
                        Log.e(TAG, "GetChat callback failed for " + chatId, callbackError);
                    } finally {
                        continueAfterBatch.run();
                    }
                });
            } catch (RuntimeException e) {
                Log.e(TAG, "GetChat request failed for " + chatId, e);
                continueAfterBatch.run();
            }
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
        emitStatus("Sohbet geçmişinde en yeni arşivler aranıyor…");
        fetchArchivePage(active, chat, 0L, 0, new ArrayList<>(), new HashSet<>(), new HashSet<>());
    }

    private void fetchArchivePage(Client active, ChatItem chat, long fromMessageId, int scannedMessages,
                                  List<ArchiveItem> archives, Set<Integer> seenFileIds,
                                  Set<Long> seenMessageIds) {
        int remaining = MAX_HISTORY_MESSAGES_TO_SCAN - scannedMessages;
        if (archives.size() >= MAX_ARCHIVES || remaining <= 0) {
            finishArchiveScan(chat, archives);
            return;
        }
        int pageLimit = Math.min(MAX_MESSAGES, remaining);
        try {
            active.send(new TdApi.GetChatHistory(chat.id, fromMessageId, 0, pageLimit, false), object -> {
                if (reportIfError(object, "Sohbet geçmişi alınamadı.")) {
                    requestInProgress.set(false);
                    return;
                }
                if (!(object instanceof TdApi.Messages)) {
                    requestInProgress.set(false);
                    emitError("TDLib mesaj listesi yerine beklenmeyen bir yanıt döndürdü.");
                    return;
                }
                TdApi.Message[] messages = ((TdApi.Messages) object).messages;
                if (messages == null || messages.length == 0) {
                    finishArchiveScan(chat, archives);
                    return;
                }
                int newlySeenMessages = 0;
                long oldestMessageId = Long.MAX_VALUE;
                for (TdApi.Message message : messages) {
                    if (message == null || message.id <= 0L || !seenMessageIds.add(message.id)) continue;
                    newlySeenMessages++;
                    oldestMessageId = Math.min(oldestMessageId, message.id);
                    if (!(message.content instanceof TdApi.MessageDocument)) continue;
                    TdApi.Document document = ((TdApi.MessageDocument) message.content).document;
                    if (document == null || document.document == null || document.document.id <= 0) continue;
                    String name = document.fileName;
                    if (!TelegramArchiveRules.isSingleArchiveName(name)) continue;
                    TdApi.File file = document.document;
                    // expectedSize is explicitly approximate in TDLib; it cannot be used as
                    // a seekable stream length. Unknown-size files are skipped safely.
                    long size = file.size;
                    if (size <= 0L || size > MAX_DOWNLOAD_BYTES || !seenFileIds.add(file.id)) continue;
                    try {
                        archives.add(new ArchiveItem(chat.id, message.id, file.id, size,
                                message.date, TelegramArchiveRules.sanitizeFileName(name)));
                    } catch (IllegalArgumentException e) {
                        Log.w(TAG, "Skipping Telegram document with unsafe name", e);
                    }
                }
                int nextScanned = scannedMessages + newlySeenMessages;
                long nextCursor = oldestMessageId == Long.MAX_VALUE ? 0L : oldestMessageId;
                if (archives.size() >= MAX_ARCHIVES
                        || !TelegramArchivePaging.shouldContinue(messages.length, newlySeenMessages,
                                nextScanned, MAX_HISTORY_MESSAGES_TO_SCAN, fromMessageId, nextCursor)) {
                    finishArchiveScan(chat, archives);
                } else {
                    fetchArchivePage(active, chat, nextCursor, nextScanned, archives,
                            seenFileIds, seenMessageIds);
                }
            });
        } catch (RuntimeException e) {
            requestInProgress.set(false);
            Log.e(TAG, "GetChatHistory page request failed", e);
            emitError("Mesaj isteği gönderilemedi: " + readableMessage(e));
        }
    }

    private void finishArchiveScan(ChatItem chat, List<ArchiveItem> archives) {
        requestInProgress.set(false);
        archives.sort((left, right) -> {
            int byDate = Integer.compare(right.date, left.date);
            return byDate != 0 ? byDate : Long.compare(right.messageId, left.messageId);
        });
        List<ArchiveItem> latestArchives = new ArrayList<>(archives.subList(0,
                Math.min(MAX_ARCHIVES, archives.size())));
        post(l -> l.onArchivesLoaded(chat, latestArchives));
    }





    SeekableByteChannel openArchiveChannel(int fileId, long fileSize,
                                                   java.util.function.BooleanSupplier cancelled) throws IOException {
        Client active = client;
        if (active == null || !authorized) throw new IOException("Telegram bağlantısı hazır değil.");
        if (fileId <= 0) throw new IOException("Telegram dosya kimliği geçersiz.");
        if (fileSize <= 0L || fileSize > MAX_DOWNLOAD_BYTES) {
            throw new IOException("Telegram arşiv boyutu geçersiz veya 64 GiB sınırını aşıyor.");
        }
        return new TelegramSeekableByteChannel(active, fileId, fileSize, cancelled);
    }

    /**
     * Delivers TDLib's already-downloaded private file directly to the importer.
     * No second full-size copy is created. TDLib DownloadFile itself still writes
     * the original download to the app-private tdlib/files directory.
     */


    private void onUpdate(TdApi.Object object) {
        if (object instanceof TdApi.UpdateChatFolders) {
            TdApi.ChatFolderInfo[] folders = ((TdApi.UpdateChatFolders) object).chatFolders;
            if (folders == null || folders.length == 0) {
                chatFolderIds = new int[0];
            } else {
                int count = Math.min(folders.length, MAX_CUSTOM_CHAT_FOLDERS);
                int[] ids = new int[count];
                for (int i = 0; i < count; i++) ids[i] = folders[i] == null ? 0 : folders[i].id;
                chatFolderIds = ids;
            }
        }

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
