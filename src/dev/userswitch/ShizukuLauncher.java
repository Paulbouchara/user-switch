package dev.userswitch;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.IBinder;
import android.util.Log;

import dev.userswitch.daemon.DaemonService;
import rikka.shizuku.Shizuku;

/**
 * Starts the daemon as a Shizuku UserService, so no computer is needed once
 * Shizuku runs. Shizuku hands its binder to granted apps when it starts, so
 * registering from App.onCreate also starts the daemon right after Shizuku.
 */
public final class ShizukuLauncher {
    public static final String SHIZUKU_PACKAGE = "moe.shizuku.privileged.api";
    private static final int PERMISSION_REQUEST = 1;
    private static final String TAG = "userswitch";

    public enum State { NOT_INSTALLED, NOT_RUNNING, TOO_OLD, NEEDS_PERMISSION, DENIED, READY, RUNNING }

    private static ShizukuLauncher instance;

    private final Context ctx;
    private final Shizuku.UserServiceArgs args;
    private boolean bound;
    private boolean denied;
    private Runnable listener;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            bound = binder != null && binder.pingBinder();
            Log.i(TAG, "daemon service connected: " + bound);
            if (bound) {
                BootReceiver.cancelReminder(ctx);
                ShizukuWatch.disarm(ctx);
            }
            changed();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            bound = false;
            Log.i(TAG, "daemon service disconnected");
            changed();
        }
    };

    private ShizukuLauncher(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        args = new Shizuku.UserServiceArgs(new ComponentName(ctx.getPackageName(), DaemonService.class.getName()))
                .daemon(true)
                .processNameSuffix("daemon")
                .tag("userswitchd")
                .debuggable(false)
                .version(versionCode(ctx));

        Shizuku.addBinderReceivedListenerSticky(() -> {
            startIfGranted();
            changed();
        });
        Shizuku.addBinderDeadListener(() -> {
            bound = false;
            // Shizuku stopped (and the daemon with it): restart both as soon as Shizuku is back
            ShizukuWatch.arm(this.ctx);
            changed();
        });
        Shizuku.addRequestPermissionResultListener((requestCode, result) -> {
            if (requestCode != PERMISSION_REQUEST) return;
            denied = result != PackageManager.PERMISSION_GRANTED;
            if (!denied) start();
            changed();
        });
    }

    public static synchronized ShizukuLauncher get(Context ctx) {
        if (instance == null) instance = new ShizukuLauncher(ctx);
        return instance;
    }

    public void setListener(Runnable l) {
        listener = l;
    }

    private void changed() {
        Runnable l = listener;
        if (l != null) l.run();
    }

    public State state() {
        if (!isInstalled()) return State.NOT_INSTALLED;
        if (!Shizuku.pingBinder()) return State.NOT_RUNNING;
        if (Shizuku.isPreV11()) return State.TOO_OLD;
        if (bound) return State.RUNNING;
        if (!isGranted()) return denied ? State.DENIED : State.NEEDS_PERMISSION;
        return State.READY;
    }

    /** Starts (or reuses) the daemon when Shizuku runs and already granted us; never prompts. */
    public void startIfGranted() {
        if (isGranted() && !Shizuku.isPreV11()) start();
    }

    /** Asks for the permission if needed, then starts (or reuses) the daemon. */
    public void start() {
        if (!Shizuku.pingBinder() || Shizuku.isPreV11()) return;
        if (!isGranted()) {
            Shizuku.requestPermission(PERMISSION_REQUEST);
            return;
        }
        try {
            Shizuku.bindUserService(args, connection);
        } catch (RuntimeException e) {
            Log.w(TAG, "bindUserService failed", e);
        }
    }

    /** Kills the Shizuku-started daemon. */
    public void stop() {
        if (!Shizuku.pingBinder()) return;
        try {
            Shizuku.unbindUserService(args, connection, true);
        } catch (RuntimeException e) {
            Log.w(TAG, "unbindUserService failed", e);
        }
        bound = false;
        changed();
    }

    private boolean isGranted() {
        try {
            return Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private boolean isInstalled() {
        return isInstalled(ctx);
    }

    public static boolean isInstalled(Context ctx) {
        try {
            ctx.getPackageManager().getPackageInfo(SHIZUKU_PACKAGE, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private static int versionCode(Context ctx) {
        try {
            return (int) ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).getLongVersionCode();
        } catch (PackageManager.NameNotFoundException e) {
            return 1;
        }
    }
}
