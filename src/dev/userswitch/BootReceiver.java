package dev.userswitch;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Without root, Shizuku has to be started by hand after a reboot. After boot
 * we arm ShizukuWatch, which starts the daemon by itself within a minute of
 * Shizuku coming up, and leave a reminder to start Shizuku (tapping it opens
 * the app, which also starts the daemon right away).
 */
public final class BootReceiver extends BroadcastReceiver {
    /** High importance so the reminder shows as a heads-up right after boot. */
    private static final String CHANNEL = "reminder";
    private static final String OLD_CHANNEL = "start";
    private static final int ID = 1;

    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        ShizukuWatch.arm(ctx);
        showReminder(ctx);
    }

    static void showReminder(Context ctx) {
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        nm.deleteNotificationChannel(OLD_CHANNEL);
        nm.createNotificationChannel(new NotificationChannel(
                CHANNEL, "Rappel après redémarrage", NotificationManager.IMPORTANCE_HIGH));

        PendingIntent open = PendingIntent.getActivity(ctx, 0,
                new Intent(ctx, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = new Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_rotate)
                .setContentTitle("User Switch inactif")
                .setContentText("Démarre Shizuku : le démon suivra tout seul en 1 à 2 minutes (ou touche ici).")
                .setContentIntent(open)
                .setAutoCancel(true);

        Intent shizuku = ctx.getPackageManager().getLaunchIntentForPackage(ShizukuLauncher.SHIZUKU_PACKAGE);
        if (shizuku != null) {
            b.addAction(new Notification.Action.Builder(null, "Ouvrir Shizuku",
                    PendingIntent.getActivity(ctx, 1, shizuku, PendingIntent.FLAG_IMMUTABLE)).build());
        }
        nm.notify(ID, b.build());
    }

    static void cancelReminder(Context ctx) {
        ctx.getSystemService(NotificationManager.class).cancel(ID);
    }
}
