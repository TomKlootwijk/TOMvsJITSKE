package nl.privaterecorder;

import android.content.Context;
import android.content.SharedPreferences;

/** Settings and last observed progress; receipts in CloudLedger determine actual completion. */
final class SyncSettings {
    static final String[] PROVIDERS = { "google", "onedrive" };
    private SyncSettings() {}
    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("cloud_sync", Context.MODE_PRIVATE); }
    static boolean enabled(Context c, String p) { return prefs(c).getBoolean(p + "_enabled", false); }
    static long revision(Context c, String p) { return prefs(c).getLong(p + "_revision", 0); }
    static synchronized void enable(Context c, String p, boolean value) {
        prefs(c).edit().putBoolean(p + "_enabled", value).putLong(p + "_revision", revision(c, p) + 1)
                .putString(p + "_status", value ? "Waiting for a network connection" : "Automatic backup off").commit();
    }
    static boolean wifiOnly(Context c) { return prefs(c).getBoolean("wifi_only", false); }
    static void wifiOnly(Context c, boolean value) { prefs(c).edit().putBoolean("wifi_only", value).apply(); }
    static void status(Context c, String p, String text) { prefs(c).edit().putString(p + "_status", text).apply(); }
    static String status(Context c, String p) { return prefs(c).getString(p + "_status", "Not connected"); }
}
