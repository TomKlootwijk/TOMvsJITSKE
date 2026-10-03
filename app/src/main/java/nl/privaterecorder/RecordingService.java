package nl.privaterecorder;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.media.MediaCodec;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;

/** Visible, user-started microphone service. It never attempts an unattended boot restart. */
public final class RecordingService extends Service {
    public static final String ACTION_START = "nl.privaterecorder.START";
    public static final String ACTION_STOP = "nl.privaterecorder.STOP";
    public static final String PREFS = "recorder";
    public static final String KEY_WAS_RUNNING = "was_running";
    public static volatile boolean running;
    public static volatile String status = "Stopped";
    public static volatile long startedAtMs;
    private static final String CHANNEL = "recording";
    private static final String ALERTS = "recording_alerts";
    private static final int NOTIFICATION_ID = 10;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile AudioCapture capture;
    private volatile boolean stopRequested;
    private volatile long lastProgress;
    private volatile String captureState = "Starting…";
    private PowerManager.WakeLock wakeLock;
    private Thread worker;
    private boolean destroyed;

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel channel = new NotificationChannel(CHANNEL, "Recording status",
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Shows when the microphone recorder is running");
        nm.createNotificationChannel(channel);
        nm.createNotificationChannel(new NotificationChannel(ALERTS, "Recording interruptions",
                NotificationManager.IMPORTANCE_DEFAULT));
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopRequested = true;
            status = "Stopping and saving…";
            AudioCapture current = capture;
            if (current != null) current.requestStop();
            if (worker == null) stopSelf();
            else getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, notification());
            return START_NOT_STICKY;
        }
        if (!ACTION_START.equals(action)) {
            if (!running) stopSelf();
            return START_NOT_STICKY;
        }
        if (running || worker != null) return START_NOT_STICKY;
        NotificationManager manager = getSystemService(NotificationManager.class);
        NotificationChannel recordingChannel = manager.getNotificationChannel(CHANNEL);
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
                || (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) || !manager.areNotificationsEnabled()
                || (recordingChannel != null && recordingChannel.getImportance() == NotificationManager.IMPORTANCE_NONE)) {
            status = "Microphone and notification permissions are required";
            stopSelf();
            return START_NOT_STICKY;
        }
        stopRequested = false;
        status = "Starting…";
        startedAtMs = System.currentTimeMillis();
        try {
            startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            manager.cancel(11);
            PowerManager pm = getSystemService(PowerManager.class);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PrivateRecorder:AudioCapture");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire(); // Released in finish/onDestroy; a timed lock would break long captures.
            running = true;
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_WAS_RUNNING, true).commit();
            lastProgress = SystemClock.elapsedRealtime();
            worker = new Thread(this::record, "audio-capture");
            worker.start();
            main.postDelayed(watchdog, 15000);
        } catch (RuntimeException e) {
            finish("Could not start: " + e.getMessage());
        }
        return START_NOT_STICKY;
    }

    private void record() {
        String error = null;
        try {
            EvidenceStore store = new EvidenceStore(this);
            int attempts = 0;
            while (!stopRequested) {
                long attemptStarted = SystemClock.elapsedRealtime();
                AudioCapture current = new AudioCapture(store, new AudioCapture.Listener() {
                @Override public void state(String text) {
                    captureState = text;
                    main.post(() -> {
                        if (destroyed || stopRequested) return;
                        status = text;
                        getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, notification());
                        if (text.contains("silenced")) alert(text + ". Some audio is missing.");
                        else getSystemService(NotificationManager.class).cancel(11);
                    });
                }
                @Override public void progress() {
                    lastProgress = SystemClock.elapsedRealtime();
                    if (status.startsWith("Warning:")) main.post(() -> {
                        if (destroyed || stopRequested || !status.startsWith("Warning:")) return;
                        status = captureState;
                        getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, notification());
                        if (!captureState.contains("silenced")) getSystemService(NotificationManager.class).cancel(11);
                    });
                }
                @Override public void segmentSaved() {
                    main.post(() -> SyncScheduler.enqueue(getApplicationContext()));
                }
            });
                capture = current;
                if (stopRequested) current.requestStop();
                try {
                    current.run();
                    break;
                } catch (Exception failure) {
                    if (stopRequested) throw failure;
                    boolean retryable = failure instanceof AudioCapture.RetryableCaptureException
                            || (failure instanceof MediaCodec.CodecException
                            && (((MediaCodec.CodecException) failure).isRecoverable()
                            || ((MediaCodec.CodecException) failure).isTransient()));
                    if (SystemClock.elapsedRealtime() - attemptStarted >= 300000) attempts = 0;
                    if (!retryable || attempts >= 3) throw failure;
                    long delay = new long[] { 1000, 3000, 10000 }[attempts++];
                    store.event("capture_retry", "Attempt " + attempts + " in " + delay
                            + " ms; audio has a gap; " + failure.getMessage());
                    main.post(() -> {
                        if (destroyed || stopRequested) return;
                        status = "Interrupted — retrying microphone";
                        getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, notification());
                        alert("Recording interrupted. Retrying while the recording service is active.");
                    });
                    long until = SystemClock.elapsedRealtime() + delay;
                    while (!stopRequested && SystemClock.elapsedRealtime() < until) Thread.sleep(100);
                    lastProgress = SystemClock.elapsedRealtime();
                }
            }
        } catch (Exception e) {
            error = e.getClass().getSimpleName() + ": " + e.getMessage();
        }
        String result = error;
        main.post(() -> finish(result));
    }

    private final Runnable watchdog = new Runnable() {
        @Override public void run() {
            if (!running || destroyed) return;
            if (SystemClock.elapsedRealtime() - lastProgress > 15000) {
                status = "Warning: recording has not reported progress";
                getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, notification());
                alert(status);
            }
            main.postDelayed(this, 15000);
        }
    };

    private void finish(String error) {
        SyncScheduler.ensureScheduled(this);
        SyncScheduler.enqueue(this);
        running = false;
        worker = null;
        capture = null;
        main.removeCallbacks(watchdog);
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_WAS_RUNNING, false)
                .putString("last_status", error == null ? "Stopped — recordings saved" : "Stopped: " + error).commit();
        status = error == null ? "Stopped — recordings saved" : "Stopped: " + error;
        stopForeground(STOP_FOREGROUND_REMOVE);
        if (error != null) alert(status);
        stopSelf();
    }

    private Notification notification() {
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, RecordingService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_mic).setContentTitle("Private Recorder")
                .setContentText(status).setContentIntent(open).setOngoing(true)
                .setOnlyAlertOnce(true).setCategory(Notification.CATEGORY_SERVICE)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .addAction(new Notification.Action.Builder(null, "Stop and save", stop).build())
                .build();
    }

    private void alert(String text) {
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        getSystemService(NotificationManager.class).notify(11,
                new Notification.Builder(this, ALERTS).setSmallIcon(R.drawable.ic_mic)
                        .setContentTitle("Check Private Recorder").setContentText(text)
                        .setOnlyAlertOnce(true).setVisibility(Notification.VISIBILITY_PRIVATE)
                        .setStyle(new Notification.BigTextStyle().bigText(text))
                        .setContentIntent(open).setAutoCancel(true).build());
    }

    @Override public void onDestroy() {
        destroyed = true;
        stopRequested = true;
        main.removeCallbacks(watchdog);
        AudioCapture current = capture;
        if (current != null) current.requestStop();
        // Keep the stale-session preference until the worker actually finalizes.
        // Process death cannot run this code; next explicit start recovers partials.
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
