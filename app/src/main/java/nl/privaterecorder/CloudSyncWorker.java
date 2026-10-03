package nl.privaterecorder;

import android.content.Context;
import android.os.SystemClock;
import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import org.json.JSONObject;
import java.io.File;
import java.io.IOException;
import java.util.concurrent.locks.ReentrantLock;

/** Each provider has an independent durable queue, scoped to its actual cloud account and folder. */
public final class CloudSyncWorker extends Worker {
    private static final ReentrantLock GOOGLE_LOCK = new ReentrantLock();
    private static final ReentrantLock MICROSOFT_LOCK = new ReentrantLock();
    public CloudSyncWorker(@NonNull Context context, @NonNull WorkerParameters parameters) { super(context, parameters); }

    @NonNull @Override public Result doWork() {
        Context context = getApplicationContext();
        String provider = getInputData().getString("provider");
        if (!"google".equals(provider) && !"onedrive".equals(provider)) return Result.failure();
        if (!SyncSettings.enabled(context, provider)) return Result.success();
        ReentrantLock lock = "google".equals(provider) ? GOOGLE_LOCK : MICROSOFT_LOCK;
        if (!lock.tryLock()) return Result.retry();
        final long revision = SyncSettings.revision(context, provider);
        long deadline = SystemClock.elapsedRealtime() + 7 * 60_000;
        int uploaded = 0;
        try {
            checkCurrent(context, provider, revision);
            SyncSettings.status(context, provider, "Checking account and backup folder…");
            String authAccountKey = CloudAuth.accountKey(context, provider);
            RemoteCloud remote = new RemoteCloud(provider, new RemoteCloud.TokenSource() {
                @Override public String get() throws Exception {
                    checkCurrent(context, provider, revision);
                    return CloudAuth.token(context, provider);
                }
                @Override public void invalidate(String token) throws Exception { CloudAuth.invalidateToken(context, provider, token); }
            });
            RemoteCloud.Folder folder = remote.ensureFolder();
            checkCurrent(context, provider, revision);
            if ("google".equals(provider) && folder.accountEmail != null && !folder.accountEmail.isEmpty()) {
                CloudAuth.setGoogleAccount(context, folder.accountEmail, folder.accountId, authAccountKey);
            }
            SyncSettings.prefs(context).edit().putString(provider + "_folder", folder.label)
                    .putString(provider + "_folder_url", folder.webUrl == null ? "" : folder.webUrl)
                    .putString(provider + "_scope", folder.scope()).apply();
            EvidenceStore store = new EvidenceStore(context);
            store.createJournalCheckpoint();
            EvidenceStore.SyncSnapshot snapshot = store.syncSnapshot();
            int localErrors = snapshot.errors.size();
            CloudLedger ledger = new CloudLedger(new File(context.getNoBackupFilesDir(), "cloud-receipts"));
            for (EvidenceStore.SyncBundle bundle : snapshot.bundles) {
                checkCurrent(context, provider, revision);
                // Committed bundles are immutable. Do not reread years of audio on every scan.
                if (ledger.isUploaded(folder.scope(), bundle.key, bundle.digest)) continue;
                if (SystemClock.elapsedRealtime() >= deadline) {
                    SyncSettings.status(context, provider, "More files queued; will continue automatically");
                    return Result.retry();
                }
                final String digest;
                try {
                    bundle.verifyIntegrity();
                    digest = BundleIdentity.digest(bundle.files);
                } catch (IOException damagedLocalFile) {
                    // Preserve the damaged original, but continue backing up other recordings.
                    localErrors++;
                    continue;
                }
                SyncSettings.status(context, provider, "Uploading and checking saved files… (" + uploaded + " verified this run)");
                JSONObject receipt = remote.uploadVerified(folder, bundle.key, bundle.files);
                checkCurrent(context, provider, revision);
                try {
                    bundle.verifyIntegrity();
                    if (!digest.equals(BundleIdentity.digest(bundle.files))) throw new IOException("Local bundle changed during upload");
                } catch (IOException changedLocalFile) {
                    localErrors++;
                    continue;
                }
                receipt.put("bundle_sha256", digest);
                ledger.markUploaded(folder.scope(), bundle.key, bundle.digest, receipt);
                uploaded++;
                SyncSettings.prefs(context).edit().putLong(provider + "_last_verified", System.currentTimeMillis()).apply();
            }
            if (localErrors > 0) {
                SyncSettings.status(context, provider, "Backup needs attention: " + localErrors
                        + " local bundles failed integrity checks; other saved bundles verified");
                return Result.retry();
            }
            SyncSettings.status(context, provider, "All " + snapshot.bundles.size() + " saved bundles in this snapshot verified");
            SyncSettings.prefs(context).edit().putLong(provider + "_last_checked", System.currentTimeMillis()).apply();
            return Result.success();
        } catch (Exception failure) {
            if (isStopped() || !SyncSettings.enabled(context, provider) || revision != SyncSettings.revision(context, provider)) return Result.success();
            String message = failure.getMessage();
            if (message == null) message = failure.getClass().getSimpleName();
            if (message.length() > 220) message = message.substring(0, 220);
            SyncSettings.status(context, provider, "Backup pending: " + message);
            // A single provider's failure never blocks the other provider or deletes local data.
            return Result.retry();
        } finally {
            lock.unlock();
        }
    }

    private void checkCurrent(Context context, String provider, long revision) throws IOException {
        if (isStopped() || !SyncSettings.enabled(context, provider) || revision != SyncSettings.revision(context, provider)) {
            throw new IOException("Backup cancelled or account connection changed");
        }
    }
}
