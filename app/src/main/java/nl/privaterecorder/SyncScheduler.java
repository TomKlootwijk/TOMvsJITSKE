package nl.privaterecorder;

import android.content.Context;
import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import java.util.concurrent.TimeUnit;

/** Durable, network-constrained work independent of the microphone service. */
final class SyncScheduler {
    private SyncScheduler() {}
    private static Constraints constraints(Context c) {
        return new Constraints.Builder().setRequiredNetworkType(SyncSettings.wifiOnly(c)
                ? NetworkType.UNMETERED : NetworkType.CONNECTED).build();
    }
    static void ensureScheduled(Context context) {
        try { ensureScheduledImpl(context); }
        catch (RuntimeException error) { recordFailure(context); }
    }
    private static void ensureScheduledImpl(Context context) {
        Context c = context.getApplicationContext();
        WorkManager manager = WorkManager.getInstance(c);
        for (String provider : SyncSettings.PROVIDERS) {
            if (!SyncSettings.enabled(c, provider)) continue;
            Data data = new Data.Builder().putString("provider", provider).build();
            PeriodicWorkRequest periodic = new PeriodicWorkRequest.Builder(CloudSyncWorker.class, 15, TimeUnit.MINUTES)
                    .setInputData(data).setConstraints(constraints(c))
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build();
            manager.enqueueUniquePeriodicWork("cloud-periodic-" + provider, ExistingPeriodicWorkPolicy.UPDATE, periodic);
        }
    }
    static void enqueue(Context context) {
        Context c = context.getApplicationContext();
        for (String provider : SyncSettings.PROVIDERS) if (SyncSettings.enabled(c, provider)) enqueue(c, provider);
    }
    static void enqueue(Context c, String provider) {
        try { enqueueImpl(c, provider); }
        catch (RuntimeException error) { recordFailure(c); }
    }
    private static void enqueueImpl(Context c, String provider) {
        if (!SyncSettings.enabled(c, provider)) return;
        OneTimeWorkRequest work = new OneTimeWorkRequest.Builder(CloudSyncWorker.class)
                .setInputData(new Data.Builder().putString("provider", provider).build())
                .setConstraints(constraints(c)).setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build();
        WorkManager.getInstance(c).enqueueUniqueWork("cloud-now-" + provider, ExistingWorkPolicy.KEEP, work);
    }
    static void cancel(Context c, String provider) {
        try {
            WorkManager manager = WorkManager.getInstance(c);
            manager.cancelUniqueWork("cloud-periodic-" + provider);
            manager.cancelUniqueWork("cloud-now-" + provider);
        } catch (RuntimeException error) { recordFailure(c); }
    }
    static void networkPolicyChanged(Context c) {
        for (String provider : SyncSettings.PROVIDERS) cancel(c, provider);
        ensureScheduled(c);
        enqueue(c);
    }
    private static void recordFailure(Context c) {
        // A broken sync database or full disk must never crash the microphone service.
        for (String provider : SyncSettings.PROVIDERS) if (SyncSettings.enabled(c, provider)) {
            SyncSettings.status(c, provider, "Could not schedule backup. Free storage and reopen the app; recordings remain local.");
        }
    }
}
