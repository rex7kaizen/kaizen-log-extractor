package com.kaizen.logextractor;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.documentfile.provider.DocumentFile;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public final class MainActivity extends Activity {
    private static final String TAG = "KaizenLogExtractor";
    private static final int REQUEST_ARCHIVE = 101;
    private static final int REQUEST_OUTPUT_FOLDER = 102;
    private static final int REQUEST_PARTS_FOLDER = 103;
    private static final int REQUEST_TELEGRAM_ARCHIVE = 104;
    private static final long MAX_INPUT_BYTES = ArchiveProcessor.MAX_INPUT_BYTES;
    private static final long DISK_RESERVE_BYTES = 32L * 1024L * 1024L;

    private static final int COLOR_TEXT = Color.rgb(247, 247, 250);
    private static final int COLOR_MUTED = Color.rgb(166, 166, 179);
    private static final int COLOR_LAVENDER = Color.rgb(211, 198, 255);
    private static final int COLOR_CARD = Color.argb(190, 27, 27, 32);
    private static final int COLOR_CARD_SELECTED = Color.argb(225, 50, 43, 66);
    private static final int COLOR_BORDER = Color.argb(82, 239, 239, 250);
    private static final int COLOR_BORDER_SELECTED = Color.argb(210, 202, 183, 255);
    private static final int COLOR_SUCCESS = Color.rgb(167, 224, 194);
    private static final int COLOR_SUB = Color.argb(150, 15, 15, 19);
    private static final int COLOR_SUB_SELECTED = Color.argb(235, 66, 56, 92);

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final List<PickedArchive> selectedArchives = new ArrayList<>();
    private final AtomicBoolean cancelRequested = new AtomicBoolean(false);
    private long lastProgressUpdateMs;
    private int lastProgressPercent = -1;
    private Uri outputTreeUri;
    private Uri partsTreeUri;
    private int selectedMode = ArchiveProcessor.MODE_DEFAULT;
    private boolean busy;
    private boolean resumeAfterPartsFolder;
    private File pendingTelegramImport;

    private TextView archiveNameView;
    private TextView partsFolderView;
    private TextView outputFolderView;
    private TextView statusView;
    private TextView statsView;
    private LinearLayout defaultCard;
    private LinearLayout cardsCard;
    private LinearLayout cardsSubPanel;
    private TextView cardsDefaultOption;
    private TextView cardsFormatOption;
    private TextView processButton;
    private TextView cancelButton;
    private TextView progressPercentView;
    private TextView progressPhaseView;
    private TextView progressFileView;
    private LinearLayout progressInfo;
    private ProgressBar progressBar;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Window window = getWindow();
        window.setStatusBarColor(Color.rgb(10, 10, 12));
        window.setNavigationBarColor(Color.rgb(10, 10, 12));
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        buildUi();
        SharedPreferences prefs = getSharedPreferences("kaizen_log_extractor", MODE_PRIVATE);
        String savedFolder = prefs.getString("output_tree_uri", null);
        if (savedFolder != null) {
            outputTreeUri = Uri.parse(savedFolder);
            refreshOutputFolderLabel();
        }
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setPadding(0, dp(8), 0, dp(12));
        scroll.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{Color.rgb(9, 9, 11), Color.rgb(31, 29, 36), Color.rgb(13, 12, 17), Color.rgb(31, 26, 43)}));

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(18), dp(18), dp(18), dp(30));
        scroll.addView(page, new ScrollView.LayoutParams(-1, -2));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        page.addView(top, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout brandStack = new LinearLayout(this);
        brandStack.setOrientation(LinearLayout.VERTICAL);
        TextView brand = text("KAIZEN  /  UTILITIES", 10, COLOR_LAVENDER, true);
        brand.setLetterSpacing(0.17f);
        brandStack.addView(brand);
        TextView label = text("LOCAL ARCHIVE TOOL", 10, COLOR_MUTED, false);
        label.setLetterSpacing(0.11f);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(-2, -2);
        labelParams.topMargin = dp(5);
        brandStack.addView(label, labelParams);
        top.addView(brandStack, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView privacy = text("●  ON DEVICE", 9, COLOR_SUCCESS, true);
        privacy.setPadding(dp(11), dp(8), dp(11), dp(8));
        privacy.setBackground(rounded(Color.argb(35, 122, 214, 165), Color.argb(80, 122, 214, 165), 30, true));
        top.addView(privacy);

        addSpace(page, 18);
        LinearLayout hero = panel(page, true);
        addSectionLabel(hero, "ARCHIVE INTELLIGENCE  /  01");
        TextView title = text("LOG\nEXTRACTOR", 36, COLOR_TEXT, true);
        title.setLetterSpacing(-0.035f);
        title.setLineSpacing(0, 0.9f);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(-1, -2);
        titleParams.topMargin = dp(13);
        hero.addView(title, titleParams);
        TextView subtitle = text("Arşivlerini filtrele. Gereken dosyaları koru.", 14, COLOR_MUTED, false);
        subtitle.setPadding(0, dp(9), 0, dp(16));
        hero.addView(subtitle);

        LinearLayout chips = new LinearLayout(this);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        chips.setGravity(Gravity.CENTER_VERTICAL);
        hero.addView(chips);
        addChip(chips, ".ZIP"); addChip(chips, ".JAR"); addChip(chips, ".RAR"); addChip(chips, ".7Z");
        TextView hint = text("64 GiB işlem sınırı  ·  ZIP64 çıktı", 11, COLOR_MUTED, false);
        hint.setPadding(0, dp(15), 0, 0);
        hero.addView(hint);
        addSpace(page, 14);

        LinearLayout sourceCard = panel(page, false);
        addSectionLabel(sourceCard, "01  /  SOURCE ARCHIVE");
        archiveNameView = text("Henüz arşiv seçilmedi", 14, COLOR_TEXT, true);
        archiveNameView.setPadding(0, dp(11), 0, dp(12));
        sourceCard.addView(archiveNameView);
        addButton(sourceCard, "＋   Arşiv veya parçaları seç", true, this::pickArchive);
        addButton(sourceCard, "✈   Telegram'dan arşiv seç", false, this::pickTelegramArchive);
        TextView partHelp = text("Bölünmüş arşivlerde tüm parçaları seçebilir veya parçaların bulunduğu klasörü gösterebilirsin.", 11, COLOR_MUTED, false);
        partHelp.setPadding(0, dp(11), 0, dp(11));
        sourceCard.addView(partHelp);
        addButton(sourceCard, "▦   Parça klasörünü seç (isteğe bağlı)", false, () -> pickPartsFolder(false));
        partsFolderView = text("Parça klasörü: otomatik seçim / gerekirse iste", 11, COLOR_MUTED, false);
        partsFolderView.setPadding(0, dp(9), 0, 0);
        sourceCard.addView(partsFolderView);
        addSpace(page, 14);

        addSectionLabel(page, "02  /  FILTER MODE");
        addSpace(page, 9);
        LinearLayout modes = new LinearLayout(this);
        modes.setOrientation(LinearLayout.HORIZONTAL);
        page.addView(modes, new LinearLayout.LayoutParams(-1, -2));
        defaultCard = modeCard("ALL FILES", "Dosyaları koru; boş klasörleri atla.");
        cardsCard = modeCard("CARD MATCH", "Adında card geçen dosyaları tut.");
        // Sub-mode row: only shown while CARD MATCH is the active top-level mode.
        cardsSubPanel = new LinearLayout(this);
        cardsSubPanel.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams subParams = new LinearLayout.LayoutParams(-1, -2);
        subParams.topMargin = dp(12);
        cardsCard.addView(cardsSubPanel, subParams);
        cardsDefaultOption = subOption("Default Cards");
        cardsFormatOption = subOption("Format Cards");
        cardsSubPanel.addView(cardsDefaultOption, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams formatOptionParams = new LinearLayout.LayoutParams(-1, -2);
        formatOptionParams.topMargin = dp(6);
        cardsSubPanel.addView(cardsFormatOption, formatOptionParams);
        cardsDefaultOption.setOnClickListener(v -> chooseMode(ArchiveProcessor.MODE_CARDS));
        cardsFormatOption.setOnClickListener(v -> chooseMode(ArchiveProcessor.MODE_FORMAT_CARDS));
        LinearLayout.LayoutParams leftParams = new LinearLayout.LayoutParams(0, -2, 1f);
        leftParams.setMargins(0, 0, dp(6), 0);
        LinearLayout.LayoutParams rightParams = new LinearLayout.LayoutParams(0, -2, 1f);
        rightParams.setMargins(dp(6), 0, 0, 0);
        modes.addView(defaultCard, leftParams);
        modes.addView(cardsCard, rightParams);
        defaultCard.setOnClickListener(v -> chooseMode(ArchiveProcessor.MODE_DEFAULT));
        cardsCard.setOnClickListener(v -> chooseMode(ArchiveProcessor.MODE_CARDS));
        addSpace(page, 14);

        LinearLayout outputCard = panel(page, false);
        addSectionLabel(outputCard, "03  /  OUTPUT LOCATION");
        outputFolderView = text("Internal storage / Kaizen_LOGS klasörünü seç", 12, COLOR_MUTED, false);
        outputFolderView.setPadding(0, dp(10), 0, dp(12));
        outputCard.addView(outputFolderView);
        addButton(outputCard, "⌁   Çıktı klasörünü seç", false, this::pickOutputFolder);
        addSpace(page, 14);

        processButton = addButton(page, "✦   EXTRACT & FILTER", true, this::startProcessing);
        processButton.setGravity(Gravity.CENTER);
        processButton.setTextSize(15);
        processButton.setPadding(dp(16), dp(17), dp(16), dp(17));
        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setIndeterminate(false);
        progressBar.setMax(100);
        progressBar.setProgress(0);
        progressBar.setVisibility(View.GONE);
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(-1, dp(5));
        progressParams.setMargins(0, dp(12), 0, dp(7));
        page.addView(progressBar, progressParams);

        progressInfo = new LinearLayout(this);
        progressInfo.setOrientation(LinearLayout.VERTICAL);
        progressInfo.setVisibility(View.GONE);
        LinearLayout progressHeading = new LinearLayout(this);
        progressHeading.setOrientation(LinearLayout.HORIZONTAL);
        progressHeading.setGravity(Gravity.CENTER_VERTICAL);
        progressPercentView = text("0%", 13, COLOR_LAVENDER, true);
        progressPercentView.setMinWidth(dp(42));
        progressHeading.addView(progressPercentView, new LinearLayout.LayoutParams(-2, -2));
        progressPhaseView = text("Hazırlanıyor…", 11, COLOR_MUTED, false);
        progressPhaseView.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        progressHeading.addView(progressPhaseView, new LinearLayout.LayoutParams(0, -2, 1f));
        progressInfo.addView(progressHeading);
        progressFileView = text("Dosya: —", 11, COLOR_TEXT, false);
        progressFileView.setMaxLines(2);
        progressFileView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams fileParams = new LinearLayout.LayoutParams(-1, -2);
        fileParams.topMargin = dp(5);
        progressInfo.addView(progressFileView, fileParams);
        page.addView(progressInfo, new LinearLayout.LayoutParams(-1, -2));

        cancelButton = addButton(page, "✕   İŞLEMİ İPTAL ET", false, this::cancelProcessing);
        cancelButton.setVisibility(View.GONE);
        cancelButton.setTextColor(Color.rgb(255, 193, 205));
        cancelButton.setBackground(rounded(Color.argb(65, 100, 35, 52), Color.argb(115, 231, 120, 150), 16, true));
        LinearLayout.LayoutParams cancelParams = (LinearLayout.LayoutParams) cancelButton.getLayoutParams();
        cancelParams.topMargin = dp(9);
        cancelButton.setLayoutParams(cancelParams);
        addSpace(page, 10);

        LinearLayout resultCard = panel(page, false);
        addSectionLabel(resultCard, "LIVE ACTIVITY  /  REPORT");
        statusView = text("Hazır. Orijinal arşiv değiştirilmez; yeni bir ZIP oluşturulur.", 13, COLOR_MUTED, false);
        statusView.setPadding(0, dp(10), 0, dp(7));
        resultCard.addView(statusView);
        statsView = text("Taranan: —     Korunan: —     Atlanan: —", 12, COLOR_LAVENDER, true);
        statsView.setPadding(0, dp(5), 0, 0);
        resultCard.addView(statsView);

        addSpace(page, 18);
        TextView footer = text("PRIVATE BY DESIGN   ·   NO UPLOADS", 9, Color.rgb(125, 125, 139), true);
        footer.setGravity(Gravity.CENTER);
        footer.setLetterSpacing(0.13f);
        page.addView(footer);
        setContentView(scroll);
        refreshModeCards();
    }

    private LinearLayout panel(LinearLayout parent, boolean hero) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(hero ? 19 : 16), dp(hero ? 20 : 16), dp(hero ? 19 : 16), dp(hero ? 20 : 16));
        card.setBackground(rounded(hero ? Color.argb(190, 32, 31, 36) : COLOR_CARD, COLOR_BORDER, hero ? 28 : 22, true));
        card.setElevation(dp(hero ? 5 : 3));
        parent.addView(card, new LinearLayout.LayoutParams(-1, -2));
        return card;
    }

    private LinearLayout modeCard(String title, String description) {
        LinearLayout wrapper = new LinearLayout(this);
        wrapper.setOrientation(LinearLayout.VERTICAL);
        wrapper.setPadding(dp(13), dp(15), dp(13), dp(15));
        wrapper.setBackground(rounded(COLOR_CARD, COLOR_BORDER, 19, true));
        TextView heading = text(title, 12, COLOR_TEXT, true);
        heading.setLetterSpacing(0.04f);
        wrapper.addView(heading);
        TextView body = text(description, 11, COLOR_MUTED, false);
        LinearLayout.LayoutParams bodyParams = new LinearLayout.LayoutParams(-1, -2);
        bodyParams.topMargin = dp(10);
        wrapper.addView(body, bodyParams);
        wrapper.setMinimumHeight(dp(106));
        wrapper.setClickable(true);
        wrapper.setFocusable(true);
        return wrapper;
    }

    private TextView subOption(String label) {
        TextView option = text(label, 10, COLOR_MUTED, true);
        option.setGravity(Gravity.CENTER);
        option.setPadding(dp(6), dp(9), dp(6), dp(9));
        option.setBackground(rounded(COLOR_SUB, COLOR_BORDER, 12, true));
        option.setClickable(true);
        option.setFocusable(true);
        return option;
    }

    private void chooseMode(int mode) {
        if (busy) return;
        selectedMode = mode;
        refreshModeCards();
        if (mode == ArchiveProcessor.MODE_DEFAULT) {
            statusView.setText("Tüm güvenli dosyalar korunacak; boş klasör girdileri atlanacak.");
        } else if (mode == ArchiveProcessor.MODE_FORMAT_CARDS) {
            statusView.setText("Adında card geçen dosyalar taranacak; yalnızca son 4 karakteri görünen kayıtlar masked_cards.txt içine yazılacak.");
        } else {
            statusView.setText("Yalnızca dosya adında card geçen dosyalar korunacak.");
        }
    }

    private void refreshModeCards() {
        if (defaultCard == null || cardsCard == null) return;
        boolean isDefault = selectedMode == ArchiveProcessor.MODE_DEFAULT;
        boolean isFormat = selectedMode == ArchiveProcessor.MODE_FORMAT_CARDS;
        defaultCard.setBackground(rounded(isDefault ? COLOR_CARD_SELECTED : COLOR_CARD,
                isDefault ? COLOR_BORDER_SELECTED : COLOR_BORDER, 19, true));
        cardsCard.setBackground(rounded(!isDefault ? COLOR_CARD_SELECTED : COLOR_CARD,
                !isDefault ? COLOR_BORDER_SELECTED : COLOR_BORDER, 19, true));
        ((TextView) defaultCard.getChildAt(0)).setTextColor(isDefault ? COLOR_LAVENDER : COLOR_TEXT);
        ((TextView) cardsCard.getChildAt(0)).setTextColor(!isDefault ? COLOR_LAVENDER : COLOR_TEXT);
        if (cardsSubPanel == null) return;
        cardsSubPanel.setVisibility(isDefault ? View.GONE : View.VISIBLE);
        boolean defaultCardsActive = !isDefault && !isFormat;
        cardsDefaultOption.setBackground(rounded(defaultCardsActive ? COLOR_SUB_SELECTED : COLOR_SUB,
                defaultCardsActive ? COLOR_BORDER_SELECTED : COLOR_BORDER, 12, true));
        cardsDefaultOption.setTextColor(defaultCardsActive ? COLOR_LAVENDER : COLOR_MUTED);
        cardsFormatOption.setBackground(rounded(isFormat ? COLOR_SUB_SELECTED : COLOR_SUB,
                isFormat ? COLOR_BORDER_SELECTED : COLOR_BORDER, 12, true));
        cardsFormatOption.setTextColor(isFormat ? COLOR_LAVENDER : COLOR_MUTED);
    }

    private void addSectionLabel(LinearLayout parent, String value) {
        TextView label = text(value, 10, COLOR_LAVENDER, true);
        label.setLetterSpacing(0.11f);
        parent.addView(label);
    }

    private void addChip(LinearLayout parent, String value) {
        TextView chip = text(value, 9, COLOR_TEXT, true);
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(dp(9), dp(7), dp(9), dp(7));
        chip.setBackground(rounded(Color.argb(35, 240, 240, 255), Color.argb(80, 240, 240, 255), 30, true));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -2);
        params.setMargins(0, 0, dp(6), 0);
        parent.addView(chip, params);
    }

    private TextView addButton(LinearLayout parent, String label, boolean primary, Runnable action) {
        TextView button = text(label, 13, primary ? Color.rgb(27, 24, 35) : COLOR_TEXT, true);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(14), dp(14), dp(14), dp(14));
        if (primary) {
            GradientDrawable gradient = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                    new int[]{Color.rgb(221, 208, 255), Color.rgb(180, 160, 231)});
            gradient.setCornerRadius(dp(16));
            gradient.setStroke(dp(1), Color.argb(150, 255, 255, 255));
            button.setBackground(gradient);
        } else {
            button.setBackground(rounded(Color.argb(100, 54, 54, 64), Color.argb(100, 232, 232, 246), 16, true));
        }
        button.setClickable(true);
        button.setFocusable(true);
        button.setOnClickListener(v -> {
            if (busy && button != cancelButton) return;
            if (button != cancelButton) {
                v.animate().scaleX(0.985f).scaleY(0.985f).setDuration(70)
                        .withEndAction(() -> v.animate().scaleX(1f).scaleY(1f).setDuration(90).start()).start();
            }
            action.run();
        });
        parent.addView(button, new LinearLayout.LayoutParams(-1, -2));
        return button;
    }

    private TextView text(String value, int sp, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setTypeface(Typeface.create("sans-serif", bold ? Typeface.BOLD : Typeface.NORMAL));
        view.setIncludeFontPadding(true);
        return view;
    }

    private void addSpace(LinearLayout parent, int heightDp) {
        View space = new View(this);
        parent.addView(space, new LinearLayout.LayoutParams(1, dp(heightDp)));
    }

    private GradientDrawable rounded(int fill, int stroke, int radiusDp, boolean glass) {
        GradientDrawable drawable = new GradientDrawable();
        if (glass) {
            drawable.setColors(new int[]{withAlpha(fill, Math.min(255, Color.alpha(fill) + 22)), fill,
                    withAlpha(fill, Math.max(0, Color.alpha(fill) - 18))});
            drawable.setOrientation(GradientDrawable.Orientation.TL_BR);
        } else drawable.setColor(fill);
        drawable.setCornerRadius(dp(radiusDp));
        drawable.setStroke(dp(1), stroke);
        return drawable;
    }

    private int withAlpha(int color, int alpha) { return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color)); }
    private int dp(float value) { return (int) (value * getResources().getDisplayMetrics().density + 0.5f); }

    private void pickTelegramArchive() {
        if (busy) return;
        startActivityForResult(new Intent(this, TelegramImportActivity.class), REQUEST_TELEGRAM_ARCHIVE);
    }

    private void deletePendingTelegramImport() {
        File file = pendingTelegramImport;
        pendingTelegramImport = null;
        if (file != null && file.exists() && !file.delete()) {
            Log.w(TAG, "Could not delete unused Telegram import: " + file.getAbsolutePath());
        }
    }

    private void pickArchive() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        // Do not apply EXTRA_MIME_TYPES here. Android document providers do not
        // consistently map .rar files to a MIME type, so the MIME filter can hide
        // valid RAR archives even when the extension is supported. Validate the
        // selected filename instead, before accepting it for processing.
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, REQUEST_ARCHIVE);
    }

    private void pickOutputFolder() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        startActivityForResult(intent, REQUEST_OUTPUT_FOLDER);
    }

    private void pickPartsFolder(boolean resume) {
        resumeAfterPartsFolder = resume;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        startActivityForResult(intent, REQUEST_PARTS_FOLDER);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_TELEGRAM_ARCHIVE) {
            if (resultCode != RESULT_OK || data == null) return;
            String path = data.getStringExtra(TelegramImportActivity.EXTRA_FILE_PATH);
            String name = data.getStringExtra(TelegramImportActivity.EXTRA_FILE_NAME);
            if (path == null || name == null || !TelegramArchiveRules.isSupportedArchiveName(name)) {
                statusView.setText("Telegram içe aktarma sonucu geçersiz. Yeniden dene.");
                return;
            }
            try {
                File imported = new File(path).getCanonicalFile();
                File importRoot = new File(getFilesDir(), "telegram-import").getCanonicalFile();
                String rootPrefix = importRoot.getPath() + File.separator;
                if (!imported.getPath().startsWith(rootPrefix) || !imported.isFile()
                        || imported.length() <= 0L || imported.length() > MAX_INPUT_BYTES) {
                    throw new IOException("Telegram dosyası uygulamanın özel içe aktarma klasöründe değil veya boyutu geçersiz.");
                }
                deletePendingTelegramImport();
                pendingTelegramImport = imported;
                selectedArchives.clear();
                selectedArchives.add(new PickedArchive(Uri.fromFile(imported), safeLocalName(name)));
                partsTreeUri = null;
                archiveNameView.setText(name + "  ·  Telegram");
                partsFolderView.setText("Parça klasörü: seçilmedi");
                statusView.setText("Telegram arşivi seçildi. Mevcut yerel filtre modlarından birini seçip devam edebilirsin.");
            } catch (Exception e) {
                Log.e(TAG, "Telegram archive result rejected", e);
                String detail = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                statusView.setText("Telegram arşivi eklenemedi: " + detail);
                Toast.makeText(this, "Telegram arşivi eklenemedi: " + detail, Toast.LENGTH_LONG).show();
            }
            return;
        }
        if (requestCode == REQUEST_ARCHIVE) {
            if (resultCode != RESULT_OK || data == null) return;
            List<Uri> uris = new ArrayList<>();
            ClipData clip = data.getClipData();
            if (clip != null && clip.getItemCount() > 0) {
                for (int i = 0; i < clip.getItemCount(); i++) {
                    Uri item = clip.getItemAt(i).getUri();
                    if (item != null) uris.add(item);
                }
            } else if (data.getData() != null) uris.add(data.getData());
            if (uris.isEmpty()) {
                statusView.setText("Dosya seçimi alınamadı. Tekrar dene.");
                return;
            }
            deletePendingTelegramImport();
            selectedArchives.clear();
            partsTreeUri = null;
            for (Uri uri : uris) {
                String name = queryDisplayName(uri);
                if (!isSupportedArchiveName(name)) {
                    selectedArchives.clear();
                    archiveNameView.setText("Henüz arşiv seçilmedi");
                    statusView.setText("Desteklenmeyen dosya: " + name
                            + ". ZIP, JAR, RAR, 7Z veya desteklenen arşiv parçalarını seç.");
                    Toast.makeText(this, "Yalnızca arşiv dosyaları seçilebilir.", Toast.LENGTH_LONG).show();
                    return;
                }
                if (!containsSelected(uri)) selectedArchives.add(new PickedArchive(uri, name));
                try {
                    int flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                    if ((flags & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0) getContentResolver().takePersistableUriPermission(uri, flags);
                } catch (Exception e) {
                    Log.w(TAG, "Input URI permission not persistent; current selection may still work", e);
                }
            }
            PickedArchive first = selectedArchives.get(0);
            archiveNameView.setText(selectedArchives.size() == 1 ? first.name : first.name + "  +  " + (selectedArchives.size() - 1) + " parça/dosya");
            statusView.setText(selectedArchives.size() > 1
                    ? "Birden çok parça seçildi. İşlemden önce parça sırası ve eksikler kontrol edilecek."
                    : isSplitVolumeName(first.name)
                    ? "Bölünmüş arşiv algılandı. Tüm parçaların bulunduğu klasör de gerekli olabilir."
                    : "Arşiv seçildi. Filtre modunu belirleyip devam et.");
            partsFolderView.setText("Parça klasörü: " + (partsTreeUri == null ? "seçilmedi" : "seçili"));
            return;
        }
        if (requestCode == REQUEST_PARTS_FOLDER) {
            boolean resume = resumeAfterPartsFolder;
            resumeAfterPartsFolder = false;
            if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
            Uri uri = data.getData();
            DocumentFile folder = DocumentFile.fromTreeUri(this, uri);
            if (folder == null || !folder.isDirectory()) {
                statusView.setText("Parça klasörü okunamadı; yeniden seç.");
                return;
            }
            partsTreeUri = uri;
            try {
                int flags = data.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION;
                if (flags != 0) getContentResolver().takePersistableUriPermission(uri, flags);
            } catch (Exception e) {
                Log.w(TAG, "Parts-folder permission could not be persisted", e);
            }
            partsFolderView.setText("Parça klasörü: " + (folder.getName() == null ? "seçildi" : folder.getName()));
            statusView.setText("Parça klasörü seçildi. İşlemden önce sıralama ve eksikler kontrol edilecek.");
            if (resume) startProcessing();
            return;
        }
        if (requestCode == REQUEST_OUTPUT_FOLDER) {
            if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
            Uri uri = data.getData();
            DocumentFile folder = DocumentFile.fromTreeUri(this, uri);
            String name = folder == null ? null : folder.getName();
            if (name == null || !"Kaizen_LOGS".equalsIgnoreCase(name) || !isExpectedOutputFolder(uri)) {
                Toast.makeText(this, "Internal storage içindeki Kaizen_LOGS klasörünü seç.", Toast.LENGTH_LONG).show();
                statusView.setText("Çıktı klasörü değişmedi. Kaizen_LOGS klasörünü seçmelisin.");
                return;
            }
            try {
                int flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                getContentResolver().takePersistableUriPermission(uri, flags);
            } catch (Exception e) {
                Log.e(TAG, "Could not persist output-folder permission", e);
                Toast.makeText(this, "Klasör izni kaydedilemedi. Klasörü tekrar seç.", Toast.LENGTH_LONG).show();
                return;
            }
            outputTreeUri = uri;
            getSharedPreferences("kaizen_log_extractor", MODE_PRIVATE).edit().putString("output_tree_uri", uri.toString()).apply();
            refreshOutputFolderLabel();
            statusView.setText("Çıktı klasörü hazır. Orijinal arşiv değiştirilmeyecek.");
        }
    }

    private boolean containsSelected(Uri uri) {
        for (PickedArchive archive : selectedArchives) if (archive.uri.equals(uri)) return true;
        return false;
    }

    private String queryDisplayName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    String name = cursor.getString(index);
                    if (name != null && !name.trim().isEmpty()) return name;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not read selected display name", e);
        }
        return "selected_archive";
    }

    private void refreshOutputFolderLabel() {
        if (outputFolderView == null) return;
        DocumentFile folder = outputTreeUri == null ? null : DocumentFile.fromTreeUri(this, outputTreeUri);
        if (folder == null || !isExpectedOutputFolder(outputTreeUri)) {
            outputFolderView.setText("Internal storage / Kaizen_LOGS klasörünü seç");
            return;
        }
        outputFolderView.setText("Hazır  ·  /storage/emulated/0/Kaizen_LOGS/");
    }

    private boolean isExpectedOutputFolder(Uri uri) {
        if (uri == null) return false;
        try {
            return "primary:Kaizen_LOGS".equalsIgnoreCase(DocumentsContract.getTreeDocumentId(uri));
        } catch (Exception e) {
            Log.w(TAG, "Could not validate output folder document ID", e);
            return false;
        }
    }

    /** Strips the archive extension so the formatted TXT is named after the payload, not the container. */
    private static String safeOutputBase(String name) {
        String base = name == null ? "" : name.replace('\\', '/');
        int slash = base.lastIndexOf('/');
        if (slash >= 0) base = base.substring(slash + 1);
        base = base.replaceAll("(?i)\\.(part\\d+\\.rar|r\\d{2,3}|z\\d{2,3}|7z\\.\\d{3,}|zip\\.\\d{3,}|zip|jar|rar|7z)$", "");
        base = base.replaceAll("[^A-Za-z0-9._-]", "_");
        if (base.isEmpty() || base.equals(".") || base.equals("..")) base = "kaizen";
        return base;
    }

    private void startProcessing() {
        if (busy) return;
        if (selectedArchives.isEmpty()) {
            Toast.makeText(this, "Önce bir arşiv seç.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (selectedArchives.size() == 1 && partsTreeUri == null && isSplitVolumeName(selectedArchives.get(0).name)) {
            statusView.setText("Bölünmüş arşiv için tüm parçaların bulunduğu klasörü seç.");
            pickPartsFolder(true);
            return;
        }
        if (outputTreeUri == null) {
            Toast.makeText(this, "Önce Kaizen_LOGS klasörünü seç.", Toast.LENGTH_SHORT).show();
            pickOutputFolder();
            return;
        }
        final List<PickedArchive> picked = new ArrayList<>(selectedArchives);
        final Uri folderUri = outputTreeUri;
        final Uri sourceFolderUri = partsTreeUri;
        final int mode = selectedMode;
        final File telegramImportToDelete = pendingTelegramImport;
        pendingTelegramImport = null;
        cancelRequested.set(false);
        lastProgressUpdateMs = 0L;
        lastProgressPercent = -1;
        setBusy(true, "Arşiv parçaları kontrol ediliyor…");
        postProgress(0, "İş hazırlanıyor", "Arşiv seçimi", 0, 0, 0);
        worker.execute(() -> {
            File workDir = null;
            DocumentFile outputFile = null;
            boolean outputCompleted = false;
            try {
                checkJobCancelled();
                workDir = new File(getCacheDir(), "kaizen-job-" + System.nanoTime());
                if (!workDir.mkdirs() && !workDir.isDirectory()) throw new IOException("Geçici çalışma klasörü oluşturulamadı.");
                List<SourceFile> sources = resolveSources(picked, sourceFolderUri);
                if (sources.isEmpty()) throw new IOException("Arşiv dosyası veya gerekli parçalar bulunamadı.");

                List<Long> sourceSizes = new ArrayList<>();
                long expectedTotalBytes = 0L;
                boolean hasReliableTotal = true;
                for (SourceFile source : sources) {
                    checkJobCancelled();
                    long size = querySize(source.uri);
                    sourceSizes.add(size);
                    if (size < 0) {
                        hasReliableTotal = false;
                    } else {
                        if (expectedTotalBytes > MAX_INPUT_BYTES - size) {
                            throw new IOException("Girdi arşivinin toplamı 64 GiB sınırını aşıyor.");
                        }
                        expectedTotalBytes += size;
                    }
                }
                if (expectedTotalBytes <= 0L) hasReliableTotal = false;

                List<File> staged = new ArrayList<>();
                long totalBytes = 0L;
                long stageBytesCompleted = 0L;
                for (int i = 0; i < sources.size(); i++) {
                    checkJobCancelled();
                    SourceFile source = sources.get(i);
                    long expectedCurrentBytes = sourceSizes.get(i);
                    long baseBytes = stageBytesCompleted;
                    final long expectedAll = expectedTotalBytes;
                    final boolean reliableTotal = hasReliableTotal;
                    final long expectedThis = expectedCurrentBytes;
                    final int sourceIndex = i;
                    File stagedFile = new File(workDir, safeLocalName(source.name));
                    long copied = copyUriToFile(source.uri, stagedFile, MAX_INPUT_BYTES - totalBytes,
                            copiedBytes -> {
                                int percent;
                                if (reliableTotal && expectedAll > 0L) {
                                    long done = Math.min(expectedAll, baseBytes + copiedBytes);
                                    percent = (int) Math.min(19L, (done * 20L) / expectedAll);
                                } else {
                                    double fraction = expectedThis > 0L
                                            ? Math.min(1d, (double) copiedBytes / (double) expectedThis)
                                            : (copiedBytes <= 0L ? 0d : Math.min(0.99d,
                                                    (double) copiedBytes / ((double) copiedBytes + 32d * 1024d * 1024d)));
                                    percent = (int) Math.min(19d,
                                            ((sourceIndex + fraction) * 20d) / sources.size());
                                }
                                String detail = source.name + " · " + humanBytes(copiedBytes)
                                        + (expectedThis > 0L ? " / " + humanBytes(expectedThis) : "");
                                postProgress(percent, "Arşiv parçaları kopyalanıyor", detail, 0, 0, 0);
                            });
                    totalBytes += copied;
                    stageBytesCompleted += copied;
                    staged.add(stagedFile);
                }
                checkJobCancelled();
                postProgress(20, "Arşiv parçaları hazır", "Arşiv başlıkları kontrol ediliyor", 0, 0, 0);

                // Resolve and validate the complete volume set before creating an output document.
                List<String> stagedNames = new ArrayList<>();
                for (File file : staged) stagedNames.add(file.getName());
                String selectedName = picked.get(0).name;
                List<String> resolved = ArchiveProcessor.selectVolumeNames(stagedNames, selectedName);
                Set<String> needed = new HashSet<>();
                for (String name : resolved) needed.add(name.toLowerCase(Locale.ROOT));
                List<File> requiredFiles = new ArrayList<>();
                for (File file : staged) if (needed.contains(file.getName().toLowerCase(Locale.ROOT))) requiredFiles.add(file);
                if (requiredFiles.size() != resolved.size()) throw new IOException("Arşiv parçalarından biri seçilmedi veya okunamadı.");
                DocumentFile folder = DocumentFile.fromTreeUri(this, folderUri);
                if (folder == null || !folder.canWrite() || !"Kaizen_LOGS".equalsIgnoreCase(folder.getName())
                        || !isExpectedOutputFolder(folderUri)) {
                    throw new IOException("Kaizen_LOGS klasörüne yazma izni yok. Klasörü yeniden seç.");
                }
                checkJobCancelled();
                final boolean formatCards = mode == ArchiveProcessor.MODE_FORMAT_CARDS;
                String timestamp = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(new java.util.Date());
                String modeName = mode == ArchiveProcessor.MODE_FORMAT_CARDS ? "FormatCards"
                        : mode == ArchiveProcessor.MODE_CARDS ? "Cards" : "Default";
                final String outputMime = "application/zip";
                final String outputName = "Kaizen_LOG_EXTRACTOR_" + modeName + "_" + timestamp + ".zip";
                outputFile = folder.createFile(outputMime, outputName);
                if (outputFile == null) throw new IOException("Çıktı dosyası oluşturulamadı.");
                ArchiveProcessor.ProgressListener listener = new ArchiveProcessor.ProgressListener() {
                    @Override public boolean isCancelled() {
                        return cancelRequested.get() || Thread.currentThread().isInterrupted();
                    }
                    @Override public void onProgress(ArchiveProcessor.Progress progress) {
                        int overall = 20 + (int) ((progress.percent * 79L) / 100L);
                        postProgress(overall, progress.phase, progress.currentEntry,
                                progress.scannedEntries, progress.keptFiles, progress.skippedEntries);
                    }
                };
                ArchiveProcessor.Result result;
                try (OutputStream output = getContentResolver().openOutputStream(outputFile.getUri(), "w")) {
                    if (output == null) throw new IOException("Çıktı dosyası yazma için açılamadı.");
                    checkJobCancelled();
                    result = ArchiveProcessor.filter(requiredFiles.get(0), picked.get(0).name,
                            mode, output, requiredFiles, listener);
                    checkJobCancelled();
                }
                // Also honor a cancellation requested while the document provider was closing the output.
                checkJobCancelled();
                outputCompleted = true;
                String savedPath = "/storage/emulated/0/Kaizen_LOGS/" + outputName;
                runOnUiThread(() -> {
                    setBusy(false, "✦  İşlem tamamlandı\nÇıktı: " + savedPath);
                    String keptLabel = formatCards ? "Roleplay kart satırı" : "Korunan dosya";
                    int keptValue = formatCards ? result.cardRows : result.keptFiles;
                    String outputLabel = formatCards ? "ZIP64 uyumlu ZIP (masked_cards.txt)" : "ZIP64 uyumlu ZIP";
                    statsView.setText("Taranan: " + result.scannedEntries + "\n" + keptLabel + ": " + keptValue
                            + "  ·  Atlanan: " + result.skippedEntries + "\nKaynak: " + result.archiveType
                            + "  ·  Parça: " + result.sourceParts + "  ·  Çıktı: " + outputLabel);
                    Toast.makeText(this, "İşlem tamamlandı: " + outputName, Toast.LENGTH_LONG).show();
                });
            } catch (Exception e) {
                boolean cancelled = cancelRequested.get() || Thread.currentThread().isInterrupted()
                        || e instanceof ArchiveProcessor.OperationCancelledException;
                String incompleteOutputName = null;
                boolean incompleteOutputDeleted = true;
                if (!outputCompleted && outputFile != null) {
                    incompleteOutputName = outputFile.getName();
                    if (incompleteOutputName == null || incompleteOutputName.trim().isEmpty()) {
                        incompleteOutputName = "oluşturulan çıktı dosyası";
                    }
                    incompleteOutputDeleted = outputFile.delete();
                    if (!incompleteOutputDeleted) {
                        Log.w(TAG, "Could not delete incomplete output file: " + incompleteOutputName);
                    }
                    outputFile = null;
                }
                String detail = e.getMessage();
                if (detail == null || detail.trim().isEmpty()) detail = e.getClass().getSimpleName();
                String cleanupNote = incompleteOutputName == null ? ""
                        : incompleteOutputDeleted ? "Yarım çıktı silindi."
                        : "UYARI: Yarım çıktı silinemedi: " + incompleteOutputName;
                final String message = detail;
                final String finalCleanupNote = cleanupNote;
                if (cancelled) {
                    Log.i(TAG, "Archive processing cancelled by user or activity lifecycle");
                    runOnUiThread(() -> {
                        setBusy(false, finalCleanupNote.isEmpty() ? "İşlem iptal edildi."
                                : "İşlem iptal edildi.\n" + finalCleanupNote);
                        statsView.setText(finalCleanupNote.startsWith("UYARI:")
                                ? finalCleanupNote + " Lütfen dosyayı elle kontrol et."
                                : "İptal edildi. Orijinal arşiv korunuyor.");
                        Toast.makeText(this, finalCleanupNote.startsWith("UYARI:")
                                ? finalCleanupNote : "İşlem iptal edildi.", Toast.LENGTH_LONG).show();
                    });
                } else {
                    Log.e(TAG, "Archive filtering failed", e);
                    String fullMessage = finalCleanupNote.isEmpty() ? message : message + "\n" + finalCleanupNote;
                    runOnUiThread(() -> {
                        setBusy(false, "İşlem tamamlanamadı\n" + fullMessage);
                        statsView.setText(finalCleanupNote.isEmpty()
                                ? "Arşiv parçalarını, dosya erişimini ve depolama alanını kontrol et."
                                : finalCleanupNote);
                        Toast.makeText(this, fullMessage, Toast.LENGTH_LONG).show();
                    });
                }
            } finally {
                if (workDir != null) deleteRecursively(workDir);
                if (telegramImportToDelete != null && telegramImportToDelete.exists()
                        && !telegramImportToDelete.delete()) {
                    Log.w(TAG, "Could not delete processed Telegram import: " + telegramImportToDelete.getAbsolutePath());
                }
            }
            });
    }

    private void cancelProcessing() {
        if (!busy) return;
        if (cancelRequested.compareAndSet(false, true)) {
            cancelButton.setEnabled(false);
            cancelButton.setText("İPTAL İSTEĞİ GÖNDERİLDİ…");
            statusView.setText("İptal isteği gönderildi. Güvenli durma noktasında işlem durduruluyor…");
            progressPhaseView.setText("İptal ediliyor…");
        }
    }

    private void checkJobCancelled() throws ArchiveProcessor.OperationCancelledException {
        if (cancelRequested.get() || Thread.currentThread().isInterrupted()) {
            throw new ArchiveProcessor.OperationCancelledException();
        }
    }

    private void postProgress(int percent, String phase, String currentFile,
                              int scanned, int kept, int skipped) {
        if (cancelRequested.get() && !"İptal ediliyor…".equals(phase)) return;
        int requestedPercent = Math.max(0, Math.min(99, percent));
        long now = SystemClock.elapsedRealtime();
        if (lastProgressUpdateMs != 0L && now - lastProgressUpdateMs < 120L) return;
        int boundedPercent = Math.max(lastProgressPercent, requestedPercent);
        lastProgressUpdateMs = now;
        lastProgressPercent = boundedPercent;
        final int finalPercent = boundedPercent;
        final String finalPhase = phase == null ? "İşleniyor" : phase;
        final String finalFile = currentFile == null || currentFile.trim().isEmpty() ? "—" : currentFile;
        runOnUiThread(() -> {
            if (!busy || isFinishing()) return;
            // Ignore progress updates already queued before the user pressed Cancel.
            if (cancelRequested.get() && !"İptal ediliyor…".equals(finalPhase)) return;
            progressBar.setIndeterminate(false);
            progressBar.setProgress(finalPercent);
            progressPercentView.setText(finalPercent + "%");
            progressPhaseView.setText(finalPhase);
            progressFileView.setText("Şu an: " + finalFile);
            if (scanned > 0 || kept > 0 || skipped > 0) {
                statsView.setText("Taranan: " + scanned + "  ·  Korunan: " + kept + "  ·  Atlanan: " + skipped);
            } else if (finalPhase.contains("kopyalanıyor")) {
                statsView.setText("Kaynak dosyaları geçici alana güvenli biçimde kopyalanıyor.");
            }
        });
    }

    private long querySize(Uri uri) {
        if (uri != null && "file".equalsIgnoreCase(uri.getScheme())) {
            String path = uri.getPath();
            if (path == null) return -1L;
            File file = new File(path);
            return file.isFile() ? file.length() : -1L;
        }
        try (Cursor cursor = getContentResolver().query(uri, new String[]{OpenableColumns.SIZE}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (index >= 0 && !cursor.isNull(index)) {
                    long value = cursor.getLong(index);
                    return value >= 0L ? value : -1L;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not read source size for progress estimation", e);
        }
        return -1L;
    }

    private static String humanBytes(long bytes) {
        if (bytes < 0L) return "boyut bilinmiyor";
        if (bytes < 1024L) return bytes + " B";
        double value = bytes;
        String[] units = {"B", "KiB", "MiB", "GiB", "TiB"};
        int unit = 0;
        while (value >= 1024d && unit < units.length - 1) {
            value /= 1024d;
            unit++;
        }
        return String.format(Locale.ROOT, "%.1f %s", value, units[unit]);
    }

    private List<SourceFile> resolveSources(List<PickedArchive> picked, Uri folderUri) throws IOException {
        Map<String, SourceFile> byName = new HashMap<>();
        List<String> names = new ArrayList<>();
        if (folderUri != null) {
            DocumentFile folder = DocumentFile.fromTreeUri(this, folderUri);
            if (folder == null || !folder.isDirectory() || !folder.canRead()) throw new IOException("Parça klasörü okunamıyor.");
            for (DocumentFile child : folder.listFiles()) {
                checkJobCancelled();
                if (!child.isFile() || child.getName() == null) continue;
                String name = safeLocalName(child.getName());
                byName.put(name.toLowerCase(Locale.ROOT), new SourceFile(child.getUri(), name));
                names.add(name);
            }
            for (PickedArchive archive : picked) {
                String name = safeLocalName(archive.name);
                byName.put(name.toLowerCase(Locale.ROOT), new SourceFile(archive.uri, name));
                if (!containsIgnoreCase(names, name)) names.add(name);
            }
        } else {
            for (PickedArchive archive : picked) {
                String name = safeLocalName(archive.name);
                byName.put(name.toLowerCase(Locale.ROOT), new SourceFile(archive.uri, name));
                names.add(name);
            }
            }
        List<String> volumeNames = ArchiveProcessor.selectVolumeNames(names, picked.get(0).name);
        List<SourceFile> result = new ArrayList<>();
        Set<String> added = new HashSet<>();
        for (String name : volumeNames) {
            String key = name.toLowerCase(Locale.ROOT);
            SourceFile source = byName.get(key);
            if (source == null) throw new IOException("Gerekli arşiv parçası bulunamadı: " + name);
            if (added.add(key)) result.add(source);
        }
        return result;
    }

    private static boolean containsIgnoreCase(List<String> list, String name) {
        for (String value : list) if (value.equalsIgnoreCase(name)) return true;
        return false;
    }

    private interface CopyProgress { void onBytesCopied(long bytesCopied); }

    private long copyUriToFile(Uri uri, File destination, long remainingLimit,
                               CopyProgress copyProgress) throws IOException {
        boolean completed = false;
        long total = 0L;
        InputStream openedInput;
        if (uri != null && "file".equalsIgnoreCase(uri.getScheme())) {
            String path = uri.getPath();
            if (path == null) throw new IOException("Telegram dosya yolu okunamadı.");
            openedInput = new FileInputStream(new File(path));
        } else {
            openedInput = getContentResolver().openInputStream(uri);
        }
        try (InputStream input = openedInput;
             OutputStream output = new FileOutputStream(destination)) {
            if (input == null) throw new IOException("Seçilen dosya okunamadı: " + destination.getName());
            byte[] buffer = new byte[128 * 1024];
            int read;
            while (true) {
                checkJobCancelled();
                read = input.read(buffer);
                if (read == -1) break;
                if (read == 0) continue;
                if (total > remainingLimit - read) throw new IOException("Girdi arşivinin toplamı 64 GiB sınırını aşıyor.");
                if (destination.getParentFile().getUsableSpace() < read + DISK_RESERVE_BYTES) {
                    throw new IOException("Geçici depolama alanı yetersiz. Telefonunda yer açıp tekrar dene.");
                }
                output.write(buffer, 0, read);
                total += read;
                if (copyProgress != null) copyProgress.onBytesCopied(total);
            }
            checkJobCancelled();
            if (total == 0) throw new IOException("Seçilen dosya boş: " + destination.getName());
            completed = true;
            return total;
        } finally {
            if (!completed && destination.exists() && !destination.delete()) Log.w(TAG, "Could not delete partial staged input: " + destination);
        }
    }

    private String safeLocalName(String name) throws IOException {
        if (name == null || name.trim().isEmpty()) throw new IOException("Arşiv parçasının adı okunamadı.");
        String base = name.replace('\\', '/');
        int slash = base.lastIndexOf('/');
        if (slash >= 0) base = base.substring(slash + 1);
        if (base.isEmpty() || base.equals(".") || base.equals("..") || base.indexOf('\0') >= 0) {
            throw new IOException("Geçersiz arşiv parçası adı.");
        }
        return base;
    }

    private boolean isSupportedArchiveName(String name) {
        String lower = name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
        return lower.endsWith(".zip") || lower.endsWith(".jar")
                || lower.endsWith(".rar") || lower.endsWith(".7z")
                || isSplitVolumeName(lower);
    }

    private boolean isSplitVolumeName(String name) {
        String lower = name == null ? "" : name.toLowerCase(Locale.ROOT);
        return lower.matches(".*\\.part\\d+\\.rar") || lower.matches(".*\\.7z\\.\\d{3,}")
                || lower.matches(".*\\.zip\\.\\d{3,}") || lower.matches(".*\\.z\\d{2,3}")
                || lower.matches(".*\\.r\\d{2,3}");
    }

    private void setBusy(boolean isBusy, String status) {
        busy = isBusy;
        statusView.setText(status);
        progressBar.setVisibility(isBusy ? View.VISIBLE : View.GONE);
        progressInfo.setVisibility(isBusy ? View.VISIBLE : View.GONE);
        cancelButton.setVisibility(isBusy ? View.VISIBLE : View.GONE);
        cancelButton.setEnabled(isBusy && !cancelRequested.get());
        if (!isBusy) cancelButton.setText("✕   İŞLEMİ İPTAL ET");
        processButton.setEnabled(!isBusy);
        processButton.setAlpha(isBusy ? 0.6f : 1f);
        processButton.setText(isBusy ? "İŞLENİYOR…" : "✦   EXTRACT & FILTER");
        if (isBusy) {
            progressBar.setIndeterminate(false);
            progressBar.setMax(100);
            progressBar.setProgress(0);
            progressPercentView.setText("0%");
            progressPhaseView.setText("Başlatılıyor…");
            progressFileView.setText("Şu an: —");
        }
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) deleteRecursively(child);
        }
        if (!file.delete()) Log.w(TAG, "Temporary file could not be deleted: " + file.getAbsolutePath());
    }

    @Override
    protected void onDestroy() {
        cancelRequested.set(true);
        if (!busy) deletePendingTelegramImport();
        super.onDestroy();
        worker.shutdownNow();
    }

    private static final class PickedArchive {
        final Uri uri;
        final String name;
        PickedArchive(Uri uri, String name) { this.uri = uri; this.name = name; }
    }

    private static final class SourceFile {
        final Uri uri;
        final String name;
        SourceFile(Uri uri, String name) { this.uri = uri; this.name = name; }
    }
}