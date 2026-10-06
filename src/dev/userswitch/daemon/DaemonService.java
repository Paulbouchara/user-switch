package dev.userswitch.daemon;

import android.content.Context;
import android.os.Binder;
import android.os.Parcel;
import android.os.RemoteException;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;

/**
 * Shizuku UserService entry point: Shizuku instantiates this class in a new
 * process running with its own rights (shell when started through ADB) and
 * hands the binder back to the app. The process then runs the same daemon as
 * the adb launch path.
 */
public final class DaemonService extends Binder {
    /** Shizuku's UserService "destroy" transaction, sent by unbindUserService(remove = true). */
    private static final int TRANSACTION_DESTROY = 16777115;
    private static final String LOG = "/data/local/tmp/userswitch.log";
    private static final long MAX_LOG = 1 << 20;

    public DaemonService() {
        redirectOutput();
        Thread t = new Thread(() -> Main.main(new String[0]), "userswitchd");
        t.start();
    }

    public DaemonService(Context context) {
        this();
    }

    /** There is no terminal here, so the log goes to the same file as the adb launch. */
    private static void redirectOutput() {
        try {
            File f = new File(LOG);
            boolean append = f.length() < MAX_LOG;
            PrintStream out = new PrintStream(new FileOutputStream(f, append), true);
            System.setOut(out);
            System.setErr(out);
            Main.log("launched by Shizuku");
        } catch (IOException ignored) {
            // keep the default streams
        }
    }

    @Override
    protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
        if (code == TRANSACTION_DESTROY) {
            Main.log("stopped by Shizuku");
            System.exit(0);
        }
        return super.onTransact(code, data, reply, flags);
    }
}
