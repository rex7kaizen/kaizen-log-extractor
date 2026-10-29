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

public final class MainActivity extends Activity {
    private static final String TAG = "KaizenLogExtractor";
    private static final int REQUEST_ARCHIVE = 101;
    private static final int REQUEST_OUTPUT_FOLDER = 102;
    private static final int REQUEST_PARTS_FOLDER = 103;
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

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final List<PickedArchive> selectedArchives = new ArrayList<>();
    private Uri outputTreeUri;
    private Uri partsTreeUri;
    private int selectedMode = ArchiveProcessor.MODE_DEFAULT;
    private boolean busy;
    private boolean resumeAfterPartsFolder;

    private TextView archiveNameView;
    private TextView partsFolderView;
    private TextView outputFolderView;
    private TextView statusView;
    private TextView statsView;
    private LinearLayout defaultCard;
    private LinearLayout cardsCard;
    private TextView processButton;
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
        progressBar.setIndeterminate(true);
        progressBar.setVisibility(View.GONE);
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(-1, dp(3));
        progressParams.setMargins(0, dp(12), 0, dp(4));
        page.addView(progressBar, progressParams);
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

    private void chooseMode(int mode) {
        if (busy) return;
        selectedMode = mode;
        refreshModeCards();
        statusView.setText(mode == ArchiveProcessor.MODE_DEFAULT
                ? "Tüm güvenli dosyalar korunacak; boş klasör girdileri atlanacak."
                : "Yalnızca dosya adında card geçen dosyalar korunacak.");
    }

    private void refreshModeCards() {
        if (defaultCard == null || cardsCard == null) return;
        boolean isDefault = selectedMode == ArchiveProcessor.MODE_DEFAULT;
        defaultCard.setBackground(rounded(isDefault ? COLOR_CARD_SELECTED : COLOR_CARD,
                isDefault ? COLOR_BORDER_SELECTED : COLOR_BORDER, 19, true));
        cardsCard.setBackground(rounded(!isDefault ? COLOR_CARD_SELECTED : COLOR_CARD,
                !isDefault ? COLOR_BORDER_SELECTED : COLOR_BORDER, 19, true));
        ((TextView) defaultCard.getChildAt(0)).setTextColor(isDefault ? COLOR_LAVENDER : COLOR_TEXT);
        ((TextView) cardsCard.getChildAt(0)).setTextColor(!isDefault ? COLOR_LAVENDER : COLOR_TEXT);
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
            if (busy) return;
            v.animate().scaleX(0.985f).scaleY(0.985f).setDuration(70)
                    .withEndAction(() -> v.animate().scaleX(1f).scaleY(1f).setDuration(90).start()).start();
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
        setBusy(true, "Arşiv parçaları kontrol ediliyor…");
        worker.execute(() -> {
            File workDir = null;
            DocumentFile outputFile = null;
            try {
                workDir = new File(getCacheDir(), "kaizen-job-" + System.nanoTime());
                if (!workDir.mkdirs() && !workDir.isDirectory()) throw new IOException("Geçici çalışma klasörü oluşturulamadı.");
                List<SourceFile> sources = resolveSources(picked, sourceFolderUri);
                if (sources.isEmpty()) throw new IOException("Arşiv dosyası veya gerekli parçalar bulunamadı.");
                List<File> staged = new ArrayList<>();
                long totalBytes = 0;
                for (SourceFile source : sources) {
                    File stagedFile = new File(workDir, safeLocalName(source.name));
                    long copied = copyUriToFile(source.uri, stagedFile, MAX_INPUT_BYTES - totalBytes);
                    totalBytes += copied;
                    staged.add(stagedFile);
                }
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
                String modeName = mode == ArchiveProcessor.MODE_CARDS ? "Cards" : "Default";
                String timestamp = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(new java.util.Date());
                String outputName = "Kaizen_LOG_EXTRACTOR_" + modeName + "_" + timestamp + ".zip";
                outputFile = folder.createFile("application/zip", outputName);
                if (outputFile == null) throw new IOException("Çıktı ZIP dosyası oluşturulamadı.");
                ArchiveProcessor.Result result;
                try (OutputStream output = getContentResolver().openOutputStream(outputFile.getUri(), "w")) {
                    if (output == null) throw new IOException("Çıktı dosyası yazma için açılamadı.");
                    result = ArchiveProcessor.filter(requiredFiles.get(0), picked.get(0).name, mode, output, requiredFiles);
                } catch (Exception e) {
                    outputFile.delete();
                    outputFile = null;
                    throw e;
                }
                String savedPath = "/storage/emulated/0/Kaizen_LOGS/" + outputName;
                runOnUiThread(() -> {
                    setBusy(false, "✦  İşlem tamamlandı\nÇıktı: " + savedPath);
                    statsView.setText("Taranan: " + result.scannedEntries + "\nKorunan dosya: " + result.keptFiles
                            + "  ·  Atlanan: " + result.skippedEntries + "\nKaynak: " + result.archiveType
                            + "  ·  Parça: " + result.sourceParts + "  ·  Çıktı: ZIP64 uyumlu ZIP");
                    Toast.makeText(this, "İşlem tamamlandı: " + outputName, Toast.LENGTH_LONG).show();
                });
            } catch (Exception e) {
                Log.e(TAG, "Archive filtering failed", e);
                String detail = e.getMessage();
                if (detail == null || detail.trim().isEmpty()) detail = e.getClass().getSimpleName();
                final String message = detail;
                runOnUiThread(() -> {
                    setBusy(false, "İşlem tamamlanamadı\n" + message);
                    statsView.setText("Tamamlanmamış çıktı silindi. Arşiv parçalarını ve depolama alanını kontrol et.");
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show();
                });
            } finally {
                if (workDir != null) deleteRecursively(workDir);
            }
        });
    }

    private List<SourceFile> resolveSources(List<PickedArchive> picked, Uri folderUri) throws IOException {
        Map<String, SourceFile> byName = new HashMap<>();
        List<String> names = new ArrayList<>();
        if (folderUri != null) {
            DocumentFile folder = DocumentFile.fromTreeUri(this, folderUri);
            if (folder == null || !folder.isDirectory() || !folder.canRead()) throw new IOException("Parça klasörü okunamıyor.");
            for (DocumentFile child : folder.listFiles()) {
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

    private long copyUriToFile(Uri uri, File destination, long remainingLimit) throws IOException {
        boolean completed = false;
        long total = 0;
        try (InputStream input = getContentResolver().openInputStream(uri);
             OutputStream output = new FileOutputStream(destination)) {
            if (input == null) throw new IOException("Seçilen dosya okunamadı: " + destination.getName());
            byte[] buffer = new byte[128 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (read == 0) continue;
                if (total > remainingLimit - read) throw new IOException("Girdi arşivinin toplamı 64 GiB sınırını aşıyor.");
                if (destination.getParentFile().getUsableSpace() < read + DISK_RESERVE_BYTES) {
                    throw new IOException("Geçici depolama alanı yetersiz. Telefonunda yer açıp tekrar dene.");
                }
                output.write(buffer, 0, read);
                total += read;
            }
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
        processButton.setEnabled(!isBusy);
        processButton.setAlpha(isBusy ? 0.6f : 1f);
        processButton.setText(isBusy ? "İŞLENİYOR…" : "✦   EXTRACT & FILTER");
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
