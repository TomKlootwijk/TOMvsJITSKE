package nl.privaterecorder;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import java.text.DateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Explicit account connections and human-readable status for each independent cloud backup. */
public final class CloudSettingsActivity extends Activity {
    // This UI guard survives Activity recreation without retaining an Activity. OAuth
    // state itself is owned by CloudAuth; a process restart safely rebuilds the controls.
    private static final Object OPERATION_LOCK = new Object();
    private static String operationProvider;
    private static long operationDeadline;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Map<String, TextView> status = new HashMap<>();
    private final Map<String, Button> connect = new HashMap<>();
    private final Map<String, Button> disconnect = new HashMap<>();
    private Button microsoftConfig;
    private boolean resumed;
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            if (!resumed) return;
            render();
            main.postDelayed(this, 1500);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(16, 28, 40));
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(24), dp(24), dp(24), dp(24));
        scroll.addView(page);
        if (Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                Insets edge = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                page.setPadding(dp(24) + edge.left, dp(24) + edge.top, dp(24) + edge.right, dp(24) + edge.bottom);
            } else page.setPadding(dp(24), dp(24) + insets.getSystemWindowInsetTop(), dp(24), dp(24) + insets.getSystemWindowInsetBottom());
            return insets;
        });
        setContentView(scroll);
        TextView title = text("Cloud backups", 30);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        add(page, title);
        add(page, text("Recordings stay on your phone. Finished audio, metadata and event records are copied to a dedicated folder in each connected account.", 15));
        addProvider(page, "google", "Google Drive");
        addProvider(page, "onedrive", "OneDrive");
        Switch network = new Switch(this);
        network.setText("Back up only on unmetered networks (usually Wi-Fi)");
        network.setTextColor(Color.WHITE);
        network.setChecked(SyncSettings.wifiOnly(this));
        network.setOnCheckedChangeListener((button, checked) -> {
            SyncSettings.wifiOnly(this, checked);
            SyncScheduler.networkPolicyChanged(this);
        });
        add(page, network);
        add(page, text("With this switch off, backups can use Wi-Fi or mobile data. Uploads are read back to verify their bytes, which also uses download data. Android may delay background work; pending files remain on the phone.", 13));
        Button now = button("Sync saved files now");
        now.setOnClickListener(view -> {
            SyncScheduler.ensureScheduled(this);
            SyncScheduler.enqueue(this);
            render();
        });
        add(page, now);
        microsoftConfig = button("Microsoft app registration");
        microsoftConfig.setOnClickListener(view -> microsoftConfiguration());
        add(page, microsoftConfig);
        add(page, text("A verified backup means the uploaded file bytes matched. It does not establish who spoke or guarantee admissibility. The current recording segment is uploaded after it closes. Disconnecting leaves existing cloud files and all local recordings intact.", 13));
        Button back = button("Back to recorder");
        back.setOnClickListener(view -> finish());
        add(page, back);
        receiveMicrosoftResult(getIntent());
        render();
    }

    private void addProvider(LinearLayout page, String provider, String name) {
        TextView heading = text(name, 22);
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        add(page, heading);
        TextView detail = text("Not connected", 14);
        status.put(provider, detail);
        add(page, detail);
        Button link = button("Connect " + name);
        connect.put(provider, link);
        link.setOnClickListener(view -> beginConnect(provider));
        add(page, link);
        Button unlink = button("Disconnect " + name);
        disconnect.put(provider, unlink);
        unlink.setOnClickListener(view -> {
            if (!startOperation(provider)) return;
            render();
            try {
                SyncSettings.enable(this, provider, false);
                SyncScheduler.cancel(this, provider);
                executor.execute(() -> {
                    try { CloudAuth.disconnect(this, provider); }
                    catch (Exception error) { main.post(() -> showError(error)); }
                    finally {
                        finishOperation(provider);
                        main.post(this::render);
                    }
                });
            } catch (Exception error) {
                finishOperation(provider);
                showError(error);
                render();
            }
        });
        add(page, unlink);
        Button folder = button("Open backup folder");
        folder.setOnClickListener(view -> {
            String url = SyncSettings.prefs(this).getString(provider + "_folder_url", "");
            if (url == null || url.isEmpty()) {
                new AlertDialog.Builder(this).setMessage("The folder link will appear after the account connects and the first sync reaches the cloud.").setPositiveButton("Close", null).show();
            } else {
                Uri uri = Uri.parse(url);
                if ("https".equalsIgnoreCase(uri.getScheme())) {
                    try { startActivity(new Intent(Intent.ACTION_VIEW, uri)); }
                    catch (RuntimeException error) { showError(error); }
                }
            }
        });
        add(page, folder);
    }

    private void beginConnect(String provider) {
        if (operationInProgress()) return;
        if ("onedrive".equals(provider) && CloudAuth.microsoftClientId(this).isEmpty()) {
            microsoftConfiguration();
            return;
        }
        if (!startOperation(provider)) return;
        render();
        try {
            SyncSettings.enable(this, provider, false);
            SyncScheduler.cancel(this, provider);
            SyncSettings.prefs(this).edit().remove(provider + "_folder").remove(provider + "_folder_url")
                    .remove(provider + "_last_verified").remove(provider + "_last_checked").apply();
            SyncSettings.status(this, provider, "Finish account sign-in…");
            CloudAuth.connect(this, provider, this::connected);
        }
        catch (Exception failure) { connected(provider, failure); }
    }

    private void connected(String provider, Exception failure) {
        // Clear even if the originating Activity was destroyed while the provider UI
        // was open. Another CloudSettingsActivity observes the same guard on resume.
        finishOperation(provider);
        runOnUiThread(() -> {
            if (failure != null) {
                SyncSettings.status(this, provider, "Sign-in needed: " + safe(failure));
                showError(failure);
            } else {
                SyncSettings.enable(this, provider, true);
                SyncScheduler.ensureScheduled(this);
                SyncScheduler.enqueue(this, provider);
            }
            if (!isDestroyed()) render();
        });
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == CloudAuth.REQUEST_GOOGLE) {
            try { CloudAuth.handleGoogleResult(this, result, data, this::connected); }
            catch (Exception failure) { connected("google", failure); }
        }
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        receiveMicrosoftResult(intent);
    }

    private void receiveMicrosoftResult(Intent intent) {
        if (intent == null || !"onedrive".equals(intent.getStringExtra(CloudAuth.EXTRA_AUTH_PROVIDER))) return;
        String error = intent.getStringExtra(CloudAuth.EXTRA_AUTH_ERROR);
        intent.removeExtra(CloudAuth.EXTRA_AUTH_PROVIDER);
        intent.removeExtra(CloudAuth.EXTRA_AUTH_ERROR);
        if (error == null && CloudAuth.connected(this, "onedrive")) connected("onedrive", null);
        else connected("onedrive", new IllegalStateException(error == null ? "Microsoft sign-in was not completed" : error));
    }

    private void microsoftConfiguration() {
        if (operationInProgress()) return;
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setSingleLine(true);
        input.setHint("Application (client) ID");
        input.setText(CloudAuth.microsoftClientId(this));
        new AlertDialog.Builder(this).setTitle("Microsoft app registration")
                .setMessage("Use this app’s public Application (client) ID from Microsoft Entra. Redirect: nl.privaterecorder://oauth/microsoft. No client secret is needed. Setup instructions are included with the APK.")
                .setView(input).setPositiveButton("Save", (dialog, which) -> {
                    if (!startOperation("onedrive")) return;
                    render();
                    try {
                        SyncSettings.enable(this, "onedrive", false);
                        SyncScheduler.cancel(this, "onedrive");
                        String value = input.getText().toString().trim();
                        executor.execute(() -> {
                            try {
                                CloudAuth.configureMicrosoft(this, value);
                                finishOperation("onedrive");
                                main.post(() -> {
                                    if (!isDestroyed() && !isFinishing()) beginConnect("onedrive");
                                });
                            } catch (Exception error) {
                                finishOperation("onedrive");
                                main.post(() -> { showError(error); render(); });
                            }
                        });
                    } catch (Exception error) {
                        finishOperation("onedrive");
                        showError(error);
                        render();
                    }
                }).setNegativeButton("Cancel", null).show();
    }

    private void render() {
        if (isDestroyed()) return;
        boolean busy = operationInProgress();
        microsoftConfig.setEnabled(!busy);
        for (String provider : SyncSettings.PROVIDERS) {
            boolean enabled = SyncSettings.enabled(this, provider);
            String detail = (enabled ? "Automatic backup on\n" : "Automatic backup off\n") + SyncSettings.status(this, provider);
            String folder = SyncSettings.prefs(this).getString(provider + "_folder", "");
            if (folder != null && !folder.isEmpty()) detail += "\nFolder: " + folder;
            long verified = SyncSettings.prefs(this).getLong(provider + "_last_verified", 0);
            if (verified != 0) detail += "\nLast verified upload: " + DateFormat.getDateTimeInstance().format(new Date(verified));
            status.get(provider).setText(detail);
            connect.get(provider).setText((enabled ? "Reconnect " : "Connect ") + ("google".equals(provider) ? "Google Drive" : "OneDrive"));
            connect.get(provider).setEnabled(!busy);
            disconnect.get(provider).setEnabled(!busy && (enabled || CloudAuth.connected(this, provider)));
        }
    }

    private static boolean startOperation(String provider) {
        synchronized (OPERATION_LOCK) {
            if (operationInProgress()) return false;
            operationProvider = provider;
            // CloudAuth rejects authorization responses after ten minutes. Allow its
            // normal completion first, then recover controls if a callback was lost.
            operationDeadline = SystemClock.elapsedRealtime() + 10 * 60_000L + 30_000L;
            return true;
        }
    }

    private static void finishOperation(String provider) {
        synchronized (OPERATION_LOCK) {
            if (provider.equals(operationProvider)) {
                operationProvider = null;
                operationDeadline = 0;
            }
        }
    }

    private static boolean operationInProgress() {
        synchronized (OPERATION_LOCK) {
            if (operationProvider != null && SystemClock.elapsedRealtime() >= operationDeadline) {
                operationProvider = null;
                operationDeadline = 0;
            }
            return operationProvider != null;
        }
    }

    @Override protected void onResume() { super.onResume(); resumed = true; main.post(refresh); }
    @Override protected void onPause() { resumed = false; main.removeCallbacks(refresh); super.onPause(); }
    @Override protected void onDestroy() { main.removeCallbacksAndMessages(null); executor.shutdown(); super.onDestroy(); }
    private void showError(Exception error) {
        if (!isDestroyed() && !isFinishing()) new AlertDialog.Builder(this).setTitle("Cloud connection needs attention")
                .setMessage(safe(error)).setPositiveButton("Close", null).show();
    }
    private static String safe(Exception error) { String message = error.getMessage(); return message == null ? error.getClass().getSimpleName() : message; }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private TextView text(String text, int size) { TextView v = new TextView(this); v.setText(text); v.setTextSize(size); v.setTextColor(Color.rgb(229, 240, 239)); v.setLineSpacing(dp(3), 1); return v; }
    private Button button(String text) { Button b = new Button(this); b.setText(text); b.setAllCaps(false); b.setMinHeight(dp(50)); return b; }
    private void add(LinearLayout page, View view) { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.topMargin = dp(16); page.addView(view, p); }
}
