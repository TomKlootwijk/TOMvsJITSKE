package nl.privaterecorder;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

/** Reboot cannot silently resume a microphone foreground service on current Android. */
public final class BootReceiver extends BroadcastReceiver {
    private static final String CHANNEL_ID = "recorder_reminders";

    @Override public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        SyncScheduler.ensureScheduled(context);
        SyncScheduler.enqueue(context);
        if (!context.getSharedPreferences(RecordingService.PREFS, Context.MODE_PRIVATE)
                .getBoolean(RecordingService.KEY_WAS_RUNNING, false)) return;

        RecordingService.status = "Interrupted by device restart — open the app to resume";
        Context app = context.getApplicationContext();
        PendingResult result = goAsync();
        new Thread(() -> {
            try {
                new EvidenceStore(app).event("device_reboot", "Recording was active before reboot; manual start required.");
            } catch (Exception ignored) {
                // A failed audit write must not prevent the restart reminder.
            } finally {
                result.finish();
            }
        }, "recorder-boot-audit").start();

        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) return;
        manager.createNotificationChannel(new NotificationChannel(CHANNEL_ID,
                "Recording interruptions", NotificationManager.IMPORTANCE_DEFAULT));
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) return;
        PendingIntent open = PendingIntent.getActivity(context, 2,
                new Intent(context, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification reminder = new Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_mic)
                .setContentTitle("Recording stopped after restart")
                .setContentText("Open Private Recorder and tap Start to resume.")
                .setStyle(new Notification.BigTextStyle().bigText(
                        "Your phone restarted. Audio is not being recorded. Open Private Recorder and tap Start to resume."))
                .setContentIntent(open)
                .setAutoCancel(true)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .build();
        manager.notify(2002, reminder);
    }
}
