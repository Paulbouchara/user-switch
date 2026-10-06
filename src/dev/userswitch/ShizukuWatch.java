package dev.userswitch;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.util.Log;

/**
 * Restarts the daemon on its own once Shizuku runs, without opening the app.
 *
 * The Shizuku server does not hand its binder to every client when it starts
 * (only to its own manager); it sends it to a granted app when that app's
 * process changes state for the first time after the server came up. After a
 * reboot our process (started for BOOT_COMPLETED) sits cached, so nothing
 * happens until the app is opened. This alarm wakes the process every minute:
 * each delivery is a process state change, Shizuku answers with its binder,
 * and ShizukuLauncher then binds the daemon and disarms the alarm.
 */
public final class ShizukuWatch extends BroadcastReceiver {
    private static final String TAG = "userswitch";
    private static final long INTERVAL_MS = 60_000; // AlarmManager floor for repeating alarms
    /** Give up after this long, so a phone where Shizuku is never started stops waking us. */
    private static final long GIVE_UP_MS = 6 * 60 * 60_000L;
    private static final String PREFS = "watch";
    /** Binds attempted while Shizuku runs without the daemon ever connecting: it is crashing, stop. */
    private static final int MAX_ATTEMPTS = 3;

    @Override
    public void onReceive(Context ctx, Intent intent) {
        long armedAt = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong("armedAt", 0);
        if (SystemClock.elapsedRealtime() - armedAt > GIVE_UP_MS) {
            Log.i(TAG, "Shizuku watch: giving up");
            disarm(ctx);
            return;
        }
        Log.i(TAG, "Shizuku watch: tick");
        // The delivery itself is what makes Shizuku send its binder; if it already
        // did, this (re)binds the daemon, which disarms the watch on connection.
        if (!ShizukuLauncher.get(ctx).startIfGranted()) return;
        android.content.SharedPreferences prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        int attempts = prefs.getInt("attempts", 0) + 1;
        prefs.edit().putInt("attempts", attempts).apply();
        if (attempts >= MAX_ATTEMPTS) {
            Log.w(TAG, "Shizuku watch: daemon never connected after " + attempts + " binds, giving up");
            disarm(ctx);
        }
    }

    /**
     * Arms the watch if the daemon already ran through Shizuku once (so neither a
     * phone without Shizuku nor one that only uses the PC launch gets woken up).
     * Safe to call repeatedly.
     */
    public static void arm(Context ctx) {
        if (!ShizukuLauncher.isInstalled(ctx) || !ShizukuLauncher.everConnected(ctx)) return;
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putLong("armedAt", SystemClock.elapsedRealtime()).putInt("attempts", 0).apply();
        // Not a wakeup alarm: it only fires while the phone is awake, which is
        // when someone is starting Shizuku anyway. Repeating alarms are inexact and
        // App Standby may space them further; the system also changes the cached
        // process state on its own after ~90 s, which delivers the binder too.
        ctx.getSystemService(AlarmManager.class).setRepeating(AlarmManager.ELAPSED_REALTIME,
                SystemClock.elapsedRealtime() + INTERVAL_MS, INTERVAL_MS, intent(ctx));
        Log.i(TAG, "Shizuku watch armed");
    }

    public static void disarm(Context ctx) {
        ctx.getSystemService(AlarmManager.class).cancel(intent(ctx));
    }

    private static PendingIntent intent(Context ctx) {
        return PendingIntent.getBroadcast(ctx, 0, new Intent(ctx, ShizukuWatch.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }
}
