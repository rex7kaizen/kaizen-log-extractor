package com.kaizen.logextractor;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.provider.DocumentsContract;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.util.Log;

import androidx.documentfile.provider.DocumentFile;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final String TAG = "KaizenLogExtractor";
    private static final int REQUEST_ARCHIVE = 101;
    private static final int REQUEST_OUTPUT_FOLDER = 102;
    private static final long MAX_INPUT_BYTES = 2L * 1024L * 1024L * 1024L;
    private static final int COLOR_TEXT = Color.rgb(247, 242, 255);
    private static final int COLOR_MUTED = Color.rgb(185, 171, 211);
    private static final int COLOR_LAVENDER = Color.rgb(205, 184, 255);
    private static final int COLOR_CARD = Color.rgb(34, 26, 51);
    private static final int COLOR_BORDER = Color.rgb(77, 59, 104);

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private Uri selectedArchiveUri;
    private String selectedArchiveName = "No archive selected";
    private Uri outputTreeUri;
    private int selectedMode = ArchiveProcessor.MODE_DEFAULT;
    private boolean busy;

    private TextView archiveNameView;
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
        window.setStatusBarColor(Color.rgb(21, 16, 34));
        window.setNavigationBarColor(Color.rgb(21, 16, 34));
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
        scroll.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{Color.rgb(20, 15, 32), Color.rgb(39, 25, 61), Color.rgb(24, 18, 42)}));

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(22), dp(24), dp(22), dp(30));
        scroll.addView(page, new ScrollView.LayoutParams(-1, -2));

        TextView brand = text("✦  KAIZEN STUDIO", 12, COLOR_LAVENDER, true);
        brand.setLetterSpacing(0.13f);
        page.addView(brand);
        addSpace(page, 10);

        TextView title = text("LOG EXTRACTOR", 30, COLOR_TEXT, true);
        title.setLetterSpacing(-0.02f);
        page.addView(title);
        TextView subtitle = text("Clean your archives. Keep what matters.", 14, COLOR_MUTED, false);
        subtitle.setPadding(0, dp(7), 0, dp(20));
        page.addView(subtitle);

        LinearLayout archiveCard = panel(page);
        addSectionLabel(archiveCard, "01  /  SOURCE ARCHIVE");
        archiveNameView = text(selectedArchiveName, 14, COLOR_TEXT, true);
        archiveNameView.setPadding(0, dp(8), 0, dp(14));
        archiveCard.addView(archiveNameView);
        addButton(archiveCard, "＋   Choose ZIP or RAR", true, this::pickArchive);
        addSpace(page, 16);

        TextView modesLabel = text("02  /  SELECT MODE", 12, COLOR_MUTED, true);
        modesLabel.setLetterSpacing(0.1f);
        page.addView(modesLabel);
        addSpace(page, 10);

        LinearLayout modes = new LinearLayout(this);
        modes.setOrientation(LinearLayout.HORIZONTAL);
        page.addView(modes, new LinearLayout.LayoutParams(-1, -2));
        defaultCard = modeCard("✧  DEFAULT", "Keep every file; drop empty folders.", true);
        cardsCard = modeCard("▤  CARDS", "Keep files whose names contain ‘card’. ", false);
        LinearLayout.LayoutParams leftParams = new LinearLayout.LayoutParams(0, -2, 1f);
        leftParams.setMargins(0, 0, dp(6), 0);
        LinearLayout.LayoutParams rightParams = new LinearLayout.LayoutParams(0, -2, 1f);
        rightParams.setMargins(dp(6), 0, 0, 0);
        modes.addView(defaultCard, leftParams);
        modes.addView(cardsCard, rightParams);
        defaultCard.setOnClickListener(v -> chooseMode(ArchiveProcessor.MODE_DEFAULT));
        cardsCard.setOnClickListener(v -> chooseMode(ArchiveProcessor.MODE_CARDS));
        addSpace(page, 16);

        LinearLayout outputCard = panel(page);
        addSectionLabel(outputCard, "03  /  OUTPUT LOCATION");
        outputFolderView = text("Choose the Kaizen_LOGS folder", 13, COLOR_MUTED, false);
        outputFolderView.setPadding(0, dp(8), 0, dp(12));
        outputCard.addView(outputFolderView);
        addButton(outputCard, "⌁   Choose Kaizen_LOGS folder", false, this::pickOutputFolder);
        addSpace(page, 16);

        processButton = addButton(page, "✦   Extract & Filter", true, this::startProcessing);
        processButton.setGravity(Gravity.CENTER);
        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setIndeterminate(true);
        progressBar.setVisibility(View.GONE);
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(-1, dp(4));
        progressParams.setMargins(0, dp(12), 0, dp(4));
        page.addView(progressBar, progressParams);

        LinearLayout resultCard = panel(page);
        LinearLayout.LayoutParams resultParams = (LinearLayout.LayoutParams) resultCard.getLayoutParams();
        resultParams.topMargin = dp(16);
        resultCard.setLayoutParams(resultParams);
        addSectionLabel(resultCard, "PROCESS REPORT");
        statusView = text("Ready when you are. Your original archive will stay untouched.", 13, COLOR_MUTED, false);
        statusView.setPadding(0, dp(8), 0, dp(6));
        resultCard.addView(statusView);
        statsView = text("Files kept: —     Entries skipped: —", 12, COLOR_LAVENDER, true);
        statsView.setPadding(0, dp(4), 0, 0);
        resultCard.addView(statsView);

        addSpace(page, 14);
        TextView footer = text("PRIVATE BY DESIGN  ·  NO FILES UPLOADED", 10, Color.rgb(135, 119, 164), true);
        footer.setGravity(Gravity.CENTER);
        footer.setLetterSpacing(0.09f);
        page.addView(footer);

        setContentView(scroll);
        refreshModeCards();
    }

    private LinearLayout panel(LinearLayout parent) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(17), dp(16), dp(17), dp(17));
        card.setBackground(rounded(COLOR_CARD, COLOR_BORDER, 22));
        card.setElevation(dp(2));
        parent.addView(card, new LinearLayout.LayoutParams(-1, -2));
        return card;
    }

    private LinearLayout modeCard(String title, String description, boolean selected) {
        LinearLayout wrapper = new LinearLayout(this);
        wrapper.setOrientation(LinearLayout.VERTICAL);
        wrapper.setPadding(dp(13), dp(15), dp(13), dp(15));
        wrapper.setBackground(rounded(selected ? Color.rgb(65, 48, 94) : COLOR_CARD,
                selected ? Color.rgb(183, 150, 246) : COLOR_BORDER, 20));
        TextView heading = text(title, 14, selected ? COLOR_LAVENDER : COLOR_TEXT, true);
        wrapper.addView(heading);
        TextView body = text(description, 12, COLOR_MUTED, false);
        LinearLayout.LayoutParams bodyParams = new LinearLayout.LayoutParams(-1, -2);
        bodyParams.topMargin = dp(10);
        wrapper.addView(body, bodyParams);
        wrapper.setMinimumHeight(dp(112));
        wrapper.setClickable(true);
        wrapper.setFocusable(true);
        return wrapper;
    }

    private void chooseMode(int mode) {
        if (busy) return;
        selectedMode = mode;
        refreshModeCards();
        statusView.setText(mode == ArchiveProcessor.MODE_DEFAULT
                ? "Default selected: all regular files will be kept."
                : "Cards selected: only file names containing 'card' will be kept.");
    }

    private void refreshModeCards() {
        if (defaultCard == null || cardsCard == null) return;
        defaultCard.setBackground(rounded(
                selectedMode == ArchiveProcessor.MODE_DEFAULT ? Color.rgb(65, 48, 94) : COLOR_CARD,
                selectedMode == ArchiveProcessor.MODE_DEFAULT ? Color.rgb(183, 150, 246) : COLOR_BORDER, 20));
        cardsCard.setBackground(rounded(
                selectedMode == ArchiveProcessor.MODE_CARDS ? Color.rgb(65, 48, 94) : COLOR_CARD,
                selectedMode == ArchiveProcessor.MODE_CARDS ? Color.rgb(183, 150, 246) : COLOR_BORDER, 20));
        ((TextView) defaultCard.getChildAt(0)).setTextColor(
                selectedMode == ArchiveProcessor.MODE_DEFAULT ? COLOR_LAVENDER : COLOR_TEXT);
        ((TextView) cardsCard.getChildAt(0)).setTextColor(
                selectedMode == ArchiveProcessor.MODE_CARDS ? COLOR_LAVENDER : COLOR_TEXT);
    }

    private void addSectionLabel(LinearLayout parent, String value) {
        TextView label = text(value, 11, COLOR_LAVENDER, true);
        label.setLetterSpacing(0.09f);
        parent.addView(label);
    }

    private TextView addButton(LinearLayout parent, String label, boolean primary, Runnable action) {
        TextView button = text(label, 14, primary ? Color.rgb(31, 21, 48) : COLOR_TEXT, true);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(15), dp(15), dp(15), dp(15));
        if (primary) {
            GradientDrawable primaryBackground = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                    new int[]{Color.rgb(202, 173, 255), Color.rgb(169, 139, 239)});
            primaryBackground.setCornerRadius(dp(16));
            button.setBackground(primaryBackground);
        } else {
            button.setBackground(rounded(Color.rgb(48, 37, 69), Color.rgb(104, 80, 140), 16));
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

    private GradientDrawable rounded(int fill, int stroke, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(dp(radiusDp));
        drawable.setStroke(dp(1), stroke);
        return drawable;
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void pickArchive() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "application/zip", "application/x-zip-compressed", "application/vnd.rar",
                "application/x-rar-compressed", "application/x-rar", "application/octet-stream"
        });
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, REQUEST_ARCHIVE);
    }

    private void pickOutputFolder() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        startActivityForResult(intent, REQUEST_OUTPUT_FOLDER);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (requestCode == REQUEST_ARCHIVE) {
            selectedArchiveUri = uri;
            selectedArchiveName = queryDisplayName(uri);
            archiveNameView.setText("Selected: " + selectedArchiveName);
            statusView.setText("Archive selected. Choose a mode, then filter it.");
            try {
                int flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                getContentResolver().takePersistableUriPermission(uri, flags);
            } catch (Exception e) {
                Log.w(TAG, "Input URI permission could not be persisted; current selection may still work", e);
            }
        } else if (requestCode == REQUEST_OUTPUT_FOLDER) {
            DocumentFile folder = DocumentFile.fromTreeUri(this, uri);
            String name = folder == null ? null : folder.getName();
            if (name == null || !"Kaizen_LOGS".equalsIgnoreCase(name) || !isExpectedOutputFolder(uri)) {
                Toast.makeText(this, "Lütfen Internal storage içindeki Kaizen_LOGS klasörünü seç.", Toast.LENGTH_LONG).show();
                statusView.setText("Output folder not changed. Select the exact Kaizen_LOGS folder.");
                return;
            }
            try {
                int flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                getContentResolver().takePersistableUriPermission(uri, flags);
            } catch (Exception e) {
                Log.e(TAG, "Could not persist output-folder permission", e);
                Toast.makeText(this, "Klasör izni kalıcı yapılamadı. Tekrar seçmeyi dene.", Toast.LENGTH_LONG).show();
                return;
            }
            outputTreeUri = uri;
            getSharedPreferences("kaizen_log_extractor", MODE_PRIVATE).edit()
                    .putString("output_tree_uri", uri.toString()).apply();
            refreshOutputFolderLabel();
            statusView.setText("Output folder ready. Your original archive will not be modified.");
        }
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
            outputFolderView.setText("Choose Internal storage / Kaizen_LOGS");
            return;
        }
        outputFolderView.setText("Ready · /storage/emulated/0/Kaizen_LOGS/");
    }


    private boolean isExpectedOutputFolder(Uri uri) {
        if (uri == null) return false;
        try {
            String documentId = DocumentsContract.getTreeDocumentId(uri);
            return "primary:Kaizen_LOGS".equalsIgnoreCase(documentId);
        } catch (Exception e) {
            Log.w(TAG, "Could not validate output folder document ID", e);
            return false;
        }
    }

    private void startProcessing() {
        if (busy) return;
        if (selectedArchiveUri == null) {
            Toast.makeText(this, "Önce bir ZIP veya RAR dosyası seç.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (outputTreeUri == null) {
            Toast.makeText(this, "Önce Kaizen_LOGS klasörünü seç.", Toast.LENGTH_SHORT).show();
            pickOutputFolder();
            return;
        }
        final Uri inputUri = selectedArchiveUri;
        final Uri folderUri = outputTreeUri;
        final String inputName = selectedArchiveName;
        final int mode = selectedMode;
        setBusy(true, "Scanning archive safely…");

        worker.execute(() -> {
            File tempInput = null;
            try {
                tempInput = copyUriToCache(inputUri, inputName);
                // Validate the real archive signature before creating any output file.
                ArchiveProcessor.detectType(tempInput, inputName);
                DocumentFile folder = DocumentFile.fromTreeUri(this, folderUri);
                if (folder == null || !folder.canWrite() || !"Kaizen_LOGS".equalsIgnoreCase(folder.getName())
                        || !isExpectedOutputFolder(folderUri)) {
                    throw new IOException("Kaizen_LOGS klasörüne yazma izni yok. Klasörü yeniden seç.");
                }
                String modeName = mode == ArchiveProcessor.MODE_CARDS ? "Cards" : "Default";
                String timestamp = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)
                        .format(new java.util.Date());
                String outputName = "Kaizen_LOG_EXTRACTOR_" + modeName + "_" + timestamp + ".zip";
                DocumentFile outputFile = folder.createFile("application/zip", outputName);
                if (outputFile == null) throw new IOException("Çıktı ZIP dosyası oluşturulamadı.");
                ArchiveProcessor.Result result;
                try {
                    OutputStream output = getContentResolver().openOutputStream(outputFile.getUri(), "w");
                    if (output == null) throw new IOException("Çıktı dosyası yazma için açılamadı.");
                    result = ArchiveProcessor.filter(tempInput, inputName, mode, output);
                } catch (Exception e) {
                    outputFile.delete();
                    throw e;
                }
                String savedPath = "/storage/emulated/0/Kaizen_LOGS/" + outputName;
                runOnUiThread(() -> {
                    setBusy(false, "✦ Done. Output saved to:\n" + savedPath);
                    statsView.setText("Entries scanned: " + result.scannedEntries
                            + "\nFiles kept: " + result.keptFiles
                            + "   ·   Entries skipped: " + result.skippedEntries
                            + "\nSource format: " + result.archiveType + " → ZIP");
                    Toast.makeText(this, "İşlem tamamlandı: " + outputName, Toast.LENGTH_LONG).show();
                });
            } catch (Exception e) {
                Log.e(TAG, "Archive filtering failed", e);
                String detail = e.getMessage();
                if (detail == null || detail.trim().isEmpty()) detail = e.getClass().getSimpleName();
                final String message = detail;
                runOnUiThread(() -> {
                    setBusy(false, "Could not finish: " + message);
                    statsView.setText("No completed output was kept. Check the archive and folder permissions.");
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show();
                });
            } finally {
                if (tempInput != null && tempInput.exists() && !tempInput.delete()) {
                    Log.w(TAG, "Could not delete temporary input file: " + tempInput.getAbsolutePath());
                }
            }
        });
    }

    private File copyUriToCache(Uri uri, String name) throws IOException {
        String extension = name != null && name.toLowerCase(Locale.ROOT).endsWith(".rar") ? ".rar" : ".zip";
        File temp = File.createTempFile("kaizen-input-", extension, getCacheDir());
        boolean complete = false;
        try (InputStream in = getContentResolver().openInputStream(uri);
             OutputStream out = new FileOutputStream(temp)) {
            if (in == null) throw new IOException("Seçilen dosya okunamadı. Dosyayı tekrar seç.");
            byte[] buffer = new byte[32 * 1024];
            long total = 0;
            int read;
            while ((read = in.read(buffer)) != -1) {
                if (read == 0) continue;
                if (total > MAX_INPUT_BYTES - read) {
                    throw new IOException("Girdi dosyası 2 GiB sınırını aşıyor.");
                }
                out.write(buffer, 0, read);
                total += read;
            }
            if (total == 0) throw new IOException("Seçilen dosya boş.");
            complete = true;
            return temp;
        } finally {
            if (!complete && temp.exists() && !temp.delete()) {
                Log.w(TAG, "Could not clean partial input copy: " + temp.getAbsolutePath());
            }
        }
    }

    private void setBusy(boolean isBusy, String status) {
        busy = isBusy;
        statusView.setText(status);
        progressBar.setVisibility(isBusy ? View.VISIBLE : View.GONE);
        processButton.setEnabled(!isBusy);
        processButton.setAlpha(isBusy ? 0.55f : 1.0f);
        processButton.setText(isBusy ? "Processing…" : "✦   Extract & Filter");
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        worker.shutdownNow();
    }
}
