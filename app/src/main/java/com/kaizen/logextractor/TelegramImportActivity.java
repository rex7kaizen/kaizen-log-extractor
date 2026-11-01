package com.kaizen.logextractor;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** UI for securely connecting Telegram and importing one user-selected archive attachment. */
public final class TelegramImportActivity extends Activity implements TelegramTdLibManager.Listener {
    static final String EXTRA_FILE_PATH = "com.kaizen.logextractor.telegram.FILE_PATH";
    static final String EXTRA_FILE_NAME = "com.kaizen.logextractor.telegram.FILE_NAME";
    private static final String TAG = "KaizenTelegramUI";

    private TelegramTdLibManager manager;
    private TextView statusView;
    private TextView connectButton;
    private TextView refreshButton;
    private TextView logoutButton;
    private TextView settingsButton;
    private boolean inputDialogVisible;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.rgb(10, 10, 12));
        getWindow().setNavigationBarColor(Color.rgb(10, 10, 12));
        buildUi();
        manager = TelegramTdLibManager.getInstance(this);
        manager.attach(this);
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(15, 14, 19));
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(20), dp(24), dp(20), dp(24));
        scroll.addView(page, new ScrollView.LayoutParams(-1, -2));

        TextView title = text("TELEGRAM  /  TDLib", 24, Color.rgb(225, 213, 255), true);
        page.addView(title);
        TextView note = text("Kendi Telegram hesabına bağlan. Ana sohbet listesindeki en fazla 50 sohbetin son 50 mesajındaki tek dosyalık ZIP, JAR, RAR ve 7Z eklerini görüntüle; seçtiğin tek arşivi indir. Numaralı çok parçalı arşivler bu ekrandan alınmaz.",
                14, Color.rgb(180, 177, 190), false);
        LinearLayout.LayoutParams noteParams = new LinearLayout.LayoutParams(-1, -2);
        noteParams.topMargin = dp(12);
        page.addView(note, noteParams);

        connectButton = button("Telegram hesabına bağlan", v -> connectTelegram());
        LinearLayout.LayoutParams connectParams = new LinearLayout.LayoutParams(-1, -2);
        connectParams.topMargin = dp(24);
        page.addView(connectButton, connectParams);

        refreshButton = button("Sohbetleri yenile", v -> manager.loadChats());
        LinearLayout.LayoutParams refreshParams = new LinearLayout.LayoutParams(-1, -2);
        refreshParams.topMargin = dp(10);
        page.addView(refreshButton, refreshParams);
        refreshButton.setVisibility(View.GONE);

        logoutButton = button("Telegram oturumunu kapat", v -> new AlertDialog.Builder(this)
                .setTitle("Telegram oturumunu kapat?")
                .setMessage("TDLib oturumu ve yerel oturum veritabanı silinir. İçe aktardığın arşiv dosyası korunur.")
                .setNegativeButton("Vazgeç", null)
                .setPositiveButton("Çıkış yap", (dialog, which) -> manager.logout())
                .show());
        LinearLayout.LayoutParams logoutParams = new LinearLayout.LayoutParams(-1, -2);
        logoutParams.topMargin = dp(10);
        page.addView(logoutButton, logoutParams);
        logoutButton.setVisibility(View.GONE);

        settingsButton = button("API ID / Hash bilgilerini değiştir", v -> showCredentialsDialog());
        LinearLayout.LayoutParams settingsParams = new LinearLayout.LayoutParams(-1, -2);
        settingsParams.topMargin = dp(10);
        page.addView(settingsButton, settingsParams);

        statusView = text("Hazır. Telegram API kimliğin uygulamaya gömülü değildir.", 13,
                Color.rgb(210, 205, 224), false);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(-1, -2);
        statusParams.topMargin = dp(18);
        page.addView(statusView, statusParams);

        TextView privacy = text("API Hash ve TDLib veritabanı anahtarı Android Keystore ile şifreli saklanır. Telefon numarası, doğrulama kodu ve parola uygulama loglarına yazılmaz.",
                11, Color.rgb(135, 132, 147), false);
        LinearLayout.LayoutParams privacyParams = new LinearLayout.LayoutParams(-1, -2);
        privacyParams.topMargin = dp(24);
        page.addView(privacy, privacyParams);
        setContentView(scroll);
    }

    private void connectTelegram() {
        TelegramCredentialStore store = manager.credentialStore();
        try {
            int apiId = store.getApiId();
            String apiHash = store.getApiHash();
            if (apiId > 0 && apiHash != null) {
                manager.connect(apiId, apiHash);
            } else {
                showCredentialsDialog();
            }
        } catch (Exception e) {
            Log.e(TAG, "Saved Telegram credentials could not be decrypted", e);
            new AlertDialog.Builder(this)
                    .setTitle("Telegram ayarları okunamadı")
                    .setMessage("Android Keystore anahtarı değişmiş veya şifreli ayarlar bozulmuş olabilir. Telegram'ın yerel veritabanı ve şifreli ayarlar sıfırlanır; API ID ve Hash değerlerini yeniden girebilirsin.")
                    .setNegativeButton("Vazgeç", null)
                    .setPositiveButton("Ayarları sıfırla", (dialog, which) -> {
                        try {
                            manager.resetCredentialsAndLocalDatabase();
                            showCredentialsDialog();
                        } catch (Exception clearError) {
                            Log.e(TAG, "Telegram secure settings could not be reset", clearError);
                            onError("Telegram güvenli ayarları sıfırlanamadı: " + readableMessage(clearError));
                        }
                    }).show();
        }
    }

    private void showCredentialsDialog() {
        if (manager != null && !manager.canEditCredentials()) {
            onError("Etkin Telegram yetkilendirmesi sırasında API bilgileri değiştirilemez. Önce giriş akışını tamamla veya oturumu kapat.");
            return;
        }
        LinearLayout fields = new LinearLayout(this);
        fields.setOrientation(LinearLayout.VERTICAL);
        fields.setPadding(dp(20), dp(8), dp(20), 0);
        EditText apiIdField = new EditText(this);
        apiIdField.setHint("Telegram API ID (sayı)");
        apiIdField.setInputType(InputType.TYPE_CLASS_NUMBER);
        EditText apiHashField = new EditText(this);
        apiHashField.setHint("Telegram API Hash (32 hex karakter)");
        apiHashField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        fields.addView(apiIdField);
        fields.addView(apiHashField);
        new AlertDialog.Builder(this)
                .setTitle("Telegram API bilgileri")
                .setMessage("Bu değerleri Telegram'ın resmi geliştirici portalından kendi uygulaman için al. Buraya başkasının API bilgilerini girme.")
                .setView(fields)
                .setNegativeButton("İptal", null)
                .setPositiveButton("Kaydet ve bağlan", (dialog, which) -> {
                    String idValue = apiIdField.getText().toString().trim();
                    String hashValue = apiHashField.getText().toString().trim();
                    try {
                        int apiId = Integer.parseInt(idValue);
                        manager.credentialStore().saveCredentials(apiId, hashValue);
                        manager.connect(apiId, hashValue);
                    } catch (NumberFormatException e) {
                        onError("API ID pozitif bir sayı olmalı.");
                    } catch (Exception e) {
                        Log.e(TAG, "Telegram credentials could not be saved", e);
                        onError("Telegram API bilgileri kaydedilemedi: " + readableMessage(e));
                    }
                }).show();
    }

    @Override public void onStatus(String message) {
        statusView.setText(message);
        connectButton.setEnabled(true);
        settingsButton.setVisibility(manager.canEditCredentials() ? View.VISIBLE : View.GONE);
    }

    @Override public void onInputRequired(TelegramTdLibManager.InputKind kind, String hint) {
        if (isFinishing() || inputDialogVisible) return;
        inputDialogVisible = true;
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint(hint);
        if (kind == TelegramTdLibManager.InputKind.PHONE) {
            input.setInputType(InputType.TYPE_CLASS_PHONE);
        } else if (kind == TelegramTdLibManager.InputKind.CODE) {
            input.setInputType(InputType.TYPE_CLASS_NUMBER);
        } else {
            input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        }
        String title = kind == TelegramTdLibManager.InputKind.PHONE ? "Telefon numarası"
                : kind == TelegramTdLibManager.InputKind.CODE ? "Telegram doğrulama kodu" : "İki aşamalı doğrulama";
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(hint)
                .setView(input)
                .setCancelable(false)
                .setNegativeButton("İptal", (d, which) -> {
                    inputDialogVisible = false;
                    statusView.setText("Giriş beklemede. Devam etmek için Telegram bağlantısına yeniden dokun.");
                })
                .setPositiveButton("Gönder", null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String value = input.getText().toString();
            if (value.trim().isEmpty()) {
                input.setError("Bu alan boş bırakılamaz.");
                return;
            }
            inputDialogVisible = false;
            dialog.dismiss();
            if (kind == TelegramTdLibManager.InputKind.PHONE) manager.submitPhone(value);
            else if (kind == TelegramTdLibManager.InputKind.CODE) manager.submitCode(value);
            else manager.submitPassword(value);
        }));
        dialog.setOnDismissListener(ignored -> inputDialogVisible = false);
        dialog.show();
    }

    @Override public void onReady() {
        statusView.setText("Telegram bağlantısı hazır. Sohbet listesini yüklüyorum…");
        refreshButton.setVisibility(View.VISIBLE);
        logoutButton.setVisibility(View.VISIBLE);
        settingsButton.setVisibility(View.GONE);
        manager.loadChats();
    }

    @Override public void onChatsLoaded(List<TelegramTdLibManager.ChatItem> chats) {
        if (chats == null || chats.isEmpty()) {
            statusView.setText("Sohbet bulunamadı. Hesabında görünür bir sohbet olduğundan emin ol.");
            return;
        }
        List<TelegramTdLibManager.ChatItem> safeChats = new ArrayList<>(chats);
        String[] labels = new String[safeChats.size()];
        for (int i = 0; i < safeChats.size(); i++) labels[i] = safeChats.get(i).title;
        new AlertDialog.Builder(this)
                .setTitle("Sohbet seç · en fazla 50")
                .setItems(labels, (dialog, which) -> manager.loadArchives(safeChats.get(which)))
                .setNegativeButton("İptal", null)
                .show();
        statusView.setText(safeChats.size() + " sohbet yüklendi. Arşiv eki aramak için bir sohbet seç.");
    }

    @Override public void onArchivesLoaded(TelegramTdLibManager.ChatItem chat,
                                           List<TelegramTdLibManager.ArchiveItem> archives) {
        if (archives == null || archives.isEmpty()) {
            statusView.setText("“" + chat.title + "” sohbetinin son 50 mesajında desteklenen arşiv eki bulunamadı.");
            Toast.makeText(this, "Arşiv eki bulunamadı.", Toast.LENGTH_SHORT).show();
            return;
        }
        List<TelegramTdLibManager.ArchiveItem> safeArchives = new ArrayList<>(archives);
        String[] labels = new String[safeArchives.size()];
        for (int i = 0; i < safeArchives.size(); i++) {
            TelegramTdLibManager.ArchiveItem archive = safeArchives.get(i);
            labels[i] = archive.name + "  ·  " + humanBytes(archive.size);
        }
        new AlertDialog.Builder(this)
                .setTitle("Tek bir arşiv indir")
                .setItems(labels, (dialog, which) -> manager.downloadArchive(safeArchives.get(which)))
                .setNegativeButton("İptal", null)
                .show();
        statusView.setText(safeArchives.size() + " arşiv eki bulundu. İndirmek için birini seç.");
    }

    @Override public void onArchiveImported(File file, String originalName) {
        if (file == null || !file.isFile() || file.length() <= 0L || file.length() > ArchiveProcessor.MAX_INPUT_BYTES) {
            onError("İçe aktarılan dosya geçersiz veya boyut sınırını aşıyor.");
            return;
        }
        Intent result = new Intent();
        result.putExtra(EXTRA_FILE_PATH, file.getAbsolutePath());
        result.putExtra(EXTRA_FILE_NAME, originalName);
        setResult(RESULT_OK, result);
        Toast.makeText(this, "Arşiv uygulamaya aktarıldı.", Toast.LENGTH_SHORT).show();
        finish();
    }

    @Override public void onLoggedOut() {
        refreshButton.setVisibility(View.GONE);
        logoutButton.setVisibility(View.GONE);
        settingsButton.setVisibility(View.VISIBLE);
        statusView.setText("Telegram oturumu kapatıldı; yerel TDLib oturum verileri temizlendi.");
        Toast.makeText(this, "Telegram oturumu kapatıldı.", Toast.LENGTH_SHORT).show();
    }

    @Override public void onError(String message) {
        statusView.setText(message);
        settingsButton.setVisibility(manager.canEditCredentials() ? View.VISIBLE : View.GONE);
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    @Override protected void onDestroy() {
        if (manager != null) manager.detach(this);
        super.onDestroy();
    }

    private TextView button(String label, View.OnClickListener action) {
        TextView view = text(label, 14, Color.rgb(245, 242, 255), true);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(14), dp(15), dp(14), dp(15));
        view.setBackgroundColor(Color.rgb(64, 54, 88));
        view.setClickable(true);
        view.setFocusable(true);
        view.setOnClickListener(action);
        return view;
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private static String humanBytes(long bytes) {
        if (bytes <= 0L) return "boyut bilinmiyor";
        if (bytes < 1024L) return bytes + " B";
        double value = bytes;
        String[] units = {"B", "KiB", "MiB", "GiB", "TiB"};
        int index = 0;
        while (value >= 1024d && index < units.length - 1) {
            value /= 1024d;
            index++;
        }
        return String.format(java.util.Locale.ROOT, "%.1f %s", value, units[index]);
    }

    private static String readableMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty() ? error.getClass().getSimpleName() : message;
    }
}
