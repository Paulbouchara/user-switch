package dev.userswitch;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Without root, Shizuku has to be started by hand after a reboot, and it only
 * hands its binder to apps whose process starts (or comes to the foreground)
 * after it. So after boot we leave a reminder: start Shizuku, then tap here,
 * which opens the app, gets the binder and starts the daemon.
 */
public final class BootReceiver extends BroadcastReceiver {
    private static final String CHANNEL = "start";
    private static final int ID = 1;

    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) showReminder(ctx);
    }

    static void showReminder(Context ctx) {
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(
                CHANNEL, "Démarrage du démon", NotificationManager.IMPORTANCE_DEFAULT));

        PendingIntent open = PendingIntent.getActivity(ctx, 0,
                new Intent(ctx, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = new Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_rotate)
                .setContentTitle("User Switch inactif")
                .setContentText("Démarre Shizuku, puis touche ici pour activer les séquences.")
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
