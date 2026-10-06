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
    private static final String PREFS = "launcher";
    private static final String KEY_EVER_CONNECTED = "everConnected";
    private static final String KEY_USER_STOPPED = "userStopped";

    public enum State { NOT_INSTALLED, NOT_RUNNING, TOO_OLD, NEEDS_PERMISSION, DENIED, READY, RUNNING }

    private static final int MAX_RESTARTS = 3;
    private static final long RESTART_WINDOW_MS = 5 * 60_000L;

    private static ShizukuLauncher instance;

    private final Context ctx;
    private final Shizuku.UserServiceArgs args;
    private boolean bound;
    private boolean denied;
    private int restarts;
    private long restartWindowStart;
    private Runnable listener;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            bound = binder != null && binder.pingBinder();
            Log.i(TAG, "daemon service connected: " + bound);
            if (bound) {
                prefs(ctx).edit().putBoolean(KEY_EVER_CONNECTED, true).apply();
                BootReceiver.cancelReminder(ctx);
                ShizukuWatch.disarm(ctx);
            }
            changed();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            bound = false;
            Log.i(TAG, "daemon service disconnected");
            // The daemon died while Shizuku still runs (crash, killed): nothing else would
            // bring it back, since Shizuku only resends its binder once per app process.
            // Restart it, but give up on one that keeps dying.
            long now = android.os.SystemClock.elapsedRealtime();
            if (now - restartWindowStart > RESTART_WINDOW_MS) {
                restartWindowStart = now;
                restarts = 0;
            }
            if (restarts < MAX_RESTARTS) {
                restarts++;
                startIfGranted();
            } else {
                Log.w(TAG, "daemon died " + restarts + " times in a row, not restarting it");
            }
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
            // Shizuku stopped (and the daemon with it): restart the daemon as soon as
            // Shizuku is back, unless the user stopped the daemon on purpose
            if (!userStopped(this.ctx)) ShizukuWatch.arm(this.ctx);
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

    /**
     * Starts (or reuses) the daemon when Shizuku runs and already granted us, unless
     * the user stopped it; never prompts. Returns whether a bind was attempted.
     */
    public boolean startIfGranted() {
        if (bound) return true;
        if (userStopped(ctx) || !isGranted()) return false;
        start();
        return true;
    }

    /** Whether the daemon has ever run through Shizuku on this install. */
    public static boolean everConnected(Context ctx) {
        return prefs(ctx).getBoolean(KEY_EVER_CONNECTED, false);
    }

    /** The user stopped the daemon from the app: nothing restarts it until started there again. */
    public static boolean userStopped(Context ctx) {
        return prefs(ctx).getBoolean(KEY_USER_STOPPED, false);
    }

    private void setUserStopped(boolean stopped) {
        prefs(ctx).edit().putBoolean(KEY_USER_STOPPED, stopped).apply();
    }

    private static android.content.SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Asks for the permission if needed, then starts (or reuses) the daemon. */
    public void start() {
        if (!Shizuku.pingBinder() || Shizuku.isPreV11()) return;
        if (!isGranted()) {
            Shizuku.requestPermission(PERMISSION_REQUEST);
            return;
        }
        setUserStopped(false);
        try {
            Shizuku.bindUserService(args, connection);
        } catch (RuntimeException e) {
            Log.w(TAG, "bindUserService failed", e);
        }
    }

    /** Kills the Shizuku-started daemon; it stays off until started again from the app. */
    public void stop() {
        setUserStopped(true);
        ShizukuWatch.disarm(ctx);
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
