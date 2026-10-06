package dev.userswitch;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Process;

/**
 * Channel between the shell daemon and the app. The daemon calls this
 * provider in user 0 (through the binder, or `content call` as a fallback):
 * "hello" when it starts, "ping" every 30 s, "burst" with the key sequence
 * it saw. A burst is
 * either recorded (when the UI is recording) or matched, and the answer's
 * "target" tells the daemon which user to switch to.
 */
public final class ConfigProvider extends ContentProvider {
    private static final int ROOT_UID = 0;
    private static final int SHELL_UID = 2000;

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        int uid = Binder.getCallingUid();
        if (uid != SHELL_UID && uid != ROOT_UID && uid != Process.myUid()) {
            throw new SecurityException("only the userswitch daemon may call this provider");
        }
        Store store = Store.get(getContext());
        if (extras != null) {
            store.daemonSeen(extras.getInt("pid", -1), decode(extras.getString("users")));
            if ("hello".equals(method)) { // a daemon is up, however it was started (e.g. from the PC)
                ShizukuWatch.disarm(getContext());
                BootReceiver.cancelReminder(getContext());
            }
        }
        Bundle result = new Bundle();
        if ("remind".equals(method)) {
            // lets `content call ... --method remind` show the after-boot reminder without rebooting
            BootReceiver.showReminder(getContext());
        } else if ("dump".equals(method)) {
            StringBuilder sb = new StringBuilder();
            for (Store.Sequence s : store.sequences()) sb.append(s.keys).append(" -> ").append(s.target).append("; ");
            result.putString("sequences", sb.toString());
            result.putBoolean("recording", store.isRecording());
        } else if ("burst".equals(method) && arg != null) {
            if (store.offerRecording(arg)) {
                result.putString("recorded", "1");
            } else {
                String target = store.match(arg);
                if (target != null) result.putString("target", target);
            }
        }
        return result;
    }

    private static String decode(String s) {
        return s == null ? null : Uri.decode(s);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
