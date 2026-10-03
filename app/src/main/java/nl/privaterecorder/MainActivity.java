package nl.privaterecorder;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.provider.Settings;
import android.provider.DocumentsContract;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.IOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Visible owner controls; recording continues when this screen is closed. */
public final class MainActivity extends Activity {
    private static final int REQUEST_PERMISSIONS = 41;
    private static final int CREATE_EXPORT = 42;
    private static final int EXPORT_FOLDER = 43;
    private static final int BG = Color.rgb(16, 28, 40);
    private static final int CARD = Color.rgb(24, 44, 57);
    private static final int INK = Color.rgb(236, 242, 240);
    private static final int MUTED = Color.rgb(169, 190, 196);
    private static final int TEAL = Color.rgb(128, 213, 201);
    private static final int AMBER = Color.rgb(247, 207, 145);

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService files = Executors.newSingleThreadExecutor();
    private TextView stateText, durationText, storageText, interruptionText, exportText;
    private Button startButton, stopButton, exportButton, folderExportButton;
    private boolean resumed, starting, exporting, statsBusy;
    private int ticks;

    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            if (!resumed) return;
            renderState();
            if (ticks++ % 5 == 0) refreshStorage();
            handler.postDelayed(this, 1000);
        }
    };

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SyncScheduler.ensureScheduled(this);
        SyncScheduler.enqueue(this);
        if (!RecordingService.running && "Stopped".equals(RecordingService.status)) {
            RecordingService.status = getSharedPreferences(RecordingService.PREFS, MODE_PRIVATE)
                    .getString("last_status", "Stopped");
        }
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(BG);
        if (Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);
        else getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        scroll.setFillViewport(true);
        LinearLayout page = column();
        page.setPadding(dp(24), dp(28), dp(24), dp(32));
        scroll.addView(page, new ScrollView.LayoutParams(-1, -2));
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            int left, top, right, bottom;
            if (Build.VERSION.SDK_INT >= 30) {
                Insets edges = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                left = edges.left; top = edges.top; right = edges.right; bottom = edges.bottom;
            } else {
                left = insets.getSystemWindowInsetLeft(); top = insets.getSystemWindowInsetTop();
                right = insets.getSystemWindowInsetRight(); bottom = insets.getSystemWindowInsetBottom();
            }
            page.setPadding(dp(24) + left, dp(28) + top, dp(24) + right, dp(32) + bottom);
            return insets;
        });
        setContentView(scroll);

        TextView eyebrow = label("ON THIS DEVICE", 11, TEAL);
        eyebrow.setLetterSpacing(.16f);
        page.addView(eyebrow);
        TextView title = label("Private Recorder", 31, INK);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        add(page, title, 9);
        add(page, label("Your conversations. Saved locally.", 15, MUTED), 8);

        LinearLayout statusCard = column();
        statusCard.setPadding(dp(20), dp(20), dp(20), dp(20));
        statusCard.setBackground(round(CARD, 20));
        stateText = label("Stopped", 22, INK);
        stateText.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        statusCard.addView(stateText);
        durationText = label("Ready when you are", 15, MUTED);
        add(statusCard, durationText, 8);
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        startButton = button("Start recording", true);
        stopButton = button("Stop", false);
        actions.addView(startButton, new LinearLayout.LayoutParams(0, dp(54), 2));
        LinearLayout.LayoutParams stopParams = new LinearLayout.LayoutParams(0, dp(54), 1);
        stopParams.setMarginStart(dp(12));
        actions.addView(stopButton, stopParams);
        add(statusCard, actions, 22);
        add(page, statusCard, 28);

        interruptionText = label("", 14, AMBER);
        add(page, interruptionText, 14);
        add(page, label("Keeps recording with the screen off. Android shows a microphone indicator and an ongoing recording notification.", 14, MUTED), 14);

        TextView archiveHeading = label("LOCAL ARCHIVE", 11, TEAL);
        archiveHeading.setLetterSpacing(.14f);
        add(page, archiveHeading, 30);
        storageText = label("Reading storage…", 15, INK);
        add(page, storageText, 12);
        folderExportButton = button("Export individual files to folder", false);
        add(page, folderExportButton, 16);
        exportButton = button("Export evidence ZIP", false);
        add(page, exportButton, 10);
        exportText = label("Folder export saves separate five-minute audio files and integrity records. Repeat to add new files; matching copies are skipped. ZIP export remains available. The segment currently recording is excluded.", 13, MUTED);
        add(page, exportText, 10);
        Button cloud = button("Google Drive & OneDrive backups", false);
        cloud.setOnClickListener(view -> startActivity(new Intent(this, CloudSettingsActivity.class)));
        add(page, cloud, 20);

        LinearLayout settings = new LinearLayout(this);
        settings.setOrientation(LinearLayout.HORIZONTAL);
        Button device = button("App settings", false);
        Button setup = button("Setup & limits", false);
        settings.addView(device, new LinearLayout.LayoutParams(0, dp(50), 1));
        LinearLayout.LayoutParams setupParams = new LinearLayout.LayoutParams(0, dp(50), 1);
        setupParams.setMarginStart(dp(12));
        settings.addView(setup, setupParams);
        add(page, settings, 28);
        add(page, label("Room sound only; phone-call audio is not supported. Start again after a reboot. Continuous recording can be interrupted by Android, calls, low storage, or power loss.", 13, MUTED), 18);
        add(page, label("Keep separate exports: clearing app data or uninstalling deletes this device’s archive.", 13, AMBER), 12);

        startButton.setOnClickListener(view -> prepareStart());
        stopButton.setOnClickListener(view -> stopRecording());
        exportButton.setOnClickListener(view -> chooseExport());
        folderExportButton.setOnClickListener(view -> chooseFolderExport());
        device.setOnClickListener(view -> openAppSettings());
        setup.setOnClickListener(view -> showSetup());
        renderState();
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        ticks = 0;
        handler.removeCallbacks(refresh);
        handler.post(refresh);
    }

    @Override protected void onPause() {
        resumed = false;
        handler.removeCallbacks(refresh);
        super.onPause();
    }

    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        files.shutdown();
        super.onDestroy();
    }

    private void renderState() {
        boolean exportBusy = exporting || FolderExporter.isRunning();
        exportButton.setEnabled(!exportBusy);
        folderExportButton.setEnabled(!exportBusy);
        exportButton.setAlpha(exportBusy ? .45f : 1f);
        folderExportButton.setAlpha(exportBusy ? .45f : 1f);
        if (exportBusy) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        boolean running = RecordingService.running;
        if (running) starting = false;
        stateText.setText(starting ? "Starting…" : RecordingService.status);
        stateText.setTextColor(running ? TEAL : INK);
        startButton.setEnabled(!running && !starting);
        startButton.setAlpha(running || starting ? .45f : 1f);
        stopButton.setEnabled(running);
        stopButton.setAlpha(running ? 1f : .45f);
        if (running && RecordingService.startedAtMs > 0) {
            long seconds = Math.max(0, (System.currentTimeMillis() - RecordingService.startedAtMs) / 1000);
            durationText.setText(String.format(Locale.ROOT, "%02d:%02d:%02d elapsed", seconds / 3600, seconds / 60 % 60, seconds % 60));
        } else durationText.setText(starting ? "Opening the microphone" : "Tap Start to begin a recording session");
        boolean interrupted = !running && !starting && getSharedPreferences(RecordingService.PREFS, MODE_PRIVATE)
                .getBoolean(RecordingService.KEY_WAS_RUNNING, false);
        interruptionText.setVisibility(interrupted ? View.VISIBLE : View.GONE);
        interruptionText.setText("The previous recording may have been interrupted. Review your archive and tap Start to resume. No audio is being captured now.");
    }

    private void prepareStart() {
        ArrayList<String> missing = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            missing.add(Manifest.permission.RECORD_AUDIO);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            missing.add(Manifest.permission.POST_NOTIFICATIONS);
        if (!missing.isEmpty()) {
            requestPermissions(missing.toArray(new String[0]), REQUEST_PERMISSIONS);
            return;
        }
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null && !manager.areNotificationsEnabled()) {
            permissionExplanation();
            return;
        }
        NotificationChannel recordingChannel = manager == null ? null : manager.getNotificationChannel("recording");
        if (recordingChannel != null && recordingChannel.getImportance() == NotificationManager.IMPORTANCE_NONE) {
            new AlertDialog.Builder(this).setTitle("Enable recording notifications")
                    .setMessage("Enable the Recording status notification channel so you can see when recording is active and stop it from the notification.")
                    .setPositiveButton("Notification settings", (dialog, which) -> startActivity(
                            new Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                                    .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName())
                                    .putExtra(Settings.EXTRA_CHANNEL_ID, "recording")))
                    .setNegativeButton("Close", null).show();
            return;
        }
        startRecording();
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != REQUEST_PERMISSIONS) return;
        boolean mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        boolean notifications = Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
        if (mic && notifications) prepareStart();
        else permissionExplanation();
    }

    private void permissionExplanation() {
        new AlertDialog.Builder(this).setTitle("Permissions needed")
                .setMessage("Enable Microphone and Notifications in app settings. The recording notification gives you a visible status and a Stop control while the screen is closed.")
                .setPositiveButton("App settings", (dialog, which) -> openAppSettings())
                .setNegativeButton("Close", null).show();
    }

    private void startRecording() {
        try {
            starting = true;
            startForegroundService(new Intent(this, RecordingService.class).setAction(RecordingService.ACTION_START));
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.cancel(2002);
            renderState();
            handler.postDelayed(() -> { starting = false; if (!isDestroyed()) renderState(); }, 4000);
        } catch (RuntimeException e) {
            starting = false;
            renderState();
            error("Could not start recording", e);
        }
    }

    private void stopRecording() {
        if (!RecordingService.running) return;
        try {
            startService(new Intent(this, RecordingService.class).setAction(RecordingService.ACTION_STOP));
            stopButton.setEnabled(false);
            durationText.setText("Finishing the current audio segment…");
        } catch (RuntimeException e) { error("Could not stop recording", e); }
    }

    private void refreshStorage() {
        if (statsBusy || files.isShutdown()) return;
        statsBusy = true;
        files.execute(() -> {
            String value;
            try {
                EvidenceStore store = new EvidenceStore(getApplicationContext());
                long free = new StatFs(getFilesDir().getAbsolutePath()).getAvailableBytes();
                int count = store.completedCount();
                value = count + (count == 1 ? " finished segment" : " finished segments") + " · " + bytes(store.totalBytes())
                        + " saved\n" + bytes(free) + " available on device";
            } catch (Exception e) { value = "Storage details unavailable: " + safeMessage(e); }
            final String result = value;
            handler.post(() -> {
                statsBusy = false;
                if (!isDestroyed()) storageText.setText(result);
            });
        });
    }

    private void chooseExport() {
        if (exporting || FolderExporter.isRunning()) return;
        SimpleDateFormat timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss'Z'", Locale.ROOT);
        timestamp.setTimeZone(TimeZone.getTimeZone("UTC"));
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE).setType("application/zip")
                .putExtra(Intent.EXTRA_TITLE, "PrivateRecorder_" + timestamp.format(new Date()) + ".zip");
        try { startActivityForResult(intent, CREATE_EXPORT); }
        catch (ActivityNotFoundException e) { error("No document picker available", e); }
    }

    private void chooseFolderExport() {
        if (exporting || FolderExporter.isRunning()) return;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        String previous = getSharedPreferences("folder-export", MODE_PRIVATE).getString("last_tree", null);
        if (previous != null) intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, Uri.parse(previous));
        try { startActivityForResult(intent, EXPORT_FOLDER); }
        catch (ActivityNotFoundException e) { error("No folder picker available", e); }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == EXPORT_FOLDER && resultCode == RESULT_OK && data != null && data.getData() != null) {
            Uri tree = data.getData();
            int grants = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            if ((data.getFlags() & Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) != 0
                    && grants == (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION)) {
                try { getContentResolver().takePersistableUriPermission(tree,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION); }
                catch (SecurityException ignored) { /* This picker grant still permits the current export. */ }
            }
            getSharedPreferences("folder-export", MODE_PRIVATE).edit().putString("last_tree", tree.toString()).apply();
            exportFolder(tree);
            return;
        }
        if (requestCode != CREATE_EXPORT || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri destination = data.getData();
        setExporting(true);
        exportText.setText("Exporting finished audio and integrity records. Keep the app open until the export completes.");
        files.execute(() -> {
            Exception failure = null;
            try (OutputStream out = getContentResolver().openOutputStream(destination, "w")) {
                if (out == null) throw new IOException("The selected location could not be opened.");
                new EvidenceStore(getApplicationContext()).exportZip(out);
            } catch (Exception e) { failure = e; }
            final Exception error = failure;
            handler.post(() -> {
                if (isDestroyed()) return;
                setExporting(false);
                if (error == null) exportText.setText("Export saved. Keep this ZIP and a separate backup for your lawyer. Any segment still recording was excluded.");
                else {
                    exportText.setText("Export failed. The destination may contain an incomplete ZIP; your local recordings are unchanged.");
                    error("Export failed", error);
                }
            });
        });
    }

    private void exportFolder(Uri tree) {
        if (exporting || FolderExporter.isRunning()) return;
        setExporting(true);
        exportText.setText("Checking finished files and the selected folder. Keep this screen open; recording can continue.");
        files.execute(() -> {
            FolderExporter.Result result = null;
            Exception failure = null;
            try {
                result = FolderExporter.export(getApplicationContext(), tree, (completed, total, filename) ->
                        handler.post(() -> {
                            if (!isDestroyed()) exportText.setText("Exporting separate files: " + completed + " of " + total
                                    + " saved bundles checked.\n" + filename + "\nKeep this screen open; recording can continue.");
                        }));
            } catch (Exception e) { failure = e; }
            final FolderExporter.Result finished = result;
            final Exception exportFailure = failure;
            handler.post(() -> {
                if (isDestroyed()) return;
                setExporting(false);
                if (exportFailure != null) {
                    exportText.setText("Folder export did not finish. Your recordings and existing destination files remain. Retry the same folder to resume; Pending_ files are incomplete copies.");
                    error("Folder export did not finish", exportFailure);
                    return;
                }
                String summary = finished.audioSegments + " separate audio files verified. " + finished.copiedFiles
                        + " files copied; " + finished.reusedFiles + " matching files already present.";
                if (finished.manifestName != null) summary += "\nIntegrity list: " + finished.manifestName;
                if (finished.errorCount > 0) {
                    summary += "\n" + finished.errorCount + " issue(s) need attention. Existing files were preserved; export again after resolving them.";
                    error("Some files were not exported", new IOException(String.join("\n\n", finished.errors)));
                } else summary += "\nYou can copy this folder to your laptop. Any segment still recording was excluded.";
                exportText.setText(summary);
            });
        });
    }

    private void setExporting(boolean value) {
        exporting = value;
        renderState();
    }

    private void showSetup() {
        new AlertDialog.Builder(this).setTitle("Setup & limits")
                .setMessage("On your Poco, set this app’s battery use to No restrictions if available, enable background autostart, and lock it in Recents. Menu names vary by HyperOS version. These settings improve reliability but do not guarantee continuous operation.\n\nStart recording here while the app is visible, then turn the screen off. Keep the recording notification enabled. After a reboot or interruption, open the app and tap Start again.\n\nTest screen-off recording, charging, calls and storage before relying on it. Make regular exports; audio is never automatically deleted to make space. A full disk can stop recording.\n\nRecord only conversations you participate in. Ask your lawyer about collection and use of the recordings. Integrity records help check exported bytes; they do not prove who spoke or guarantee legal admissibility.")
                .setPositiveButton("Battery settings", (dialog, which) -> {
                    try { startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)); }
                    catch (ActivityNotFoundException e) { openAppSettings(); }
                })
                .setNegativeButton("Close", null).show();
    }

    private void openAppSettings() {
        startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())));
    }

    private void error(String title, Exception e) {
        if (isFinishing() || isDestroyed()) return;
        new AlertDialog.Builder(this).setTitle(title).setMessage(safeMessage(e)).setPositiveButton("Close", null).show();
    }

    private static String safeMessage(Exception e) {
        String text = e.getMessage();
        return text == null || text.trim().isEmpty() ? e.getClass().getSimpleName() : text;
    }

    private static String bytes(long bytes) {
        if (bytes >= 1L << 30) return String.format(Locale.ROOT, "%.1f GiB", bytes / (double) (1L << 30));
        if (bytes >= 1L << 20) return String.format(Locale.ROOT, "%.1f MiB", bytes / (double) (1L << 20));
        return String.format(Locale.ROOT, "%.1f KiB", bytes / 1024.0);
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private LinearLayout column() { LinearLayout view = new LinearLayout(this); view.setOrientation(LinearLayout.VERTICAL); return view; }
    private TextView label(String text, int size, int color) {
        TextView view = new TextView(this);
        view.setText(text); view.setTextSize(size); view.setTextColor(color);
        view.setLineSpacing(dp(3), 1f);
        return view;
    }
    private Button button(String text, boolean primary) {
        Button button = new Button(this);
        button.setText(text); button.setAllCaps(false); button.setTextSize(14);
        button.setTextColor(primary ? BG : INK);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setBackground(round(primary ? TEAL : Color.rgb(39, 61, 74), 12));
        button.setPadding(dp(10), dp(8), dp(10), dp(8));
        button.setMinHeight(dp(52)); button.setGravity(Gravity.CENTER);
        return button;
    }
    private GradientDrawable round(int color, int radius) {
        GradientDrawable shape = new GradientDrawable(); shape.setColor(color); shape.setCornerRadius(dp(radius)); return shape;
    }
    private void add(LinearLayout parent, View child, int topMargin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(topMargin); parent.addView(child, params);
    }
}
