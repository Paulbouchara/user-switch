package dev.userswitch;

import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.view.View;

/**
 * Runtime hardening. What an app can switch on itself is done everywhere (it is
 * harmless on any Android): no overlay windows over ours, no taps through an
 * obscuring window (the app hands out Shizuku permission), and sync memory
 * tagging, declared in the manifest. GrapheneOS adds per-app hardening that only
 * the user can set; on GrapheneOS the UI shows what to set and links there.
 */
final class Hardening {
    private Hardening() {
    }

    /**
     * GrapheneOS ships its own system apps. The build properties do not tell: its
     * builds are reproducible, with a Google fingerprint and generic user and host
     * ("android-user", "r-0123456789abcdef-0123" on a Pixel 10 Pro XL, Android 17).
     */
    static boolean isGrapheneOs(Context ctx) {
        PackageManager pm = ctx.getPackageManager();
        for (String p : new String[] {"app.grapheneos.apps", "app.grapheneos.info"}) {
            try {
                pm.getPackageInfo(p, 0);
                return true;
            } catch (PackageManager.NameNotFoundException ignored) {
                // not this one
            }
        }
        return false;
    }

    /** Applies the window protections; call once the content view is set. */
    static void apply(Activity a, View root) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            a.getWindow().setHideOverlayWindows(true);
        }
        // Children inherit it only for their own touches: set it on the root and let
        // ViewGroup dispatch drop obscured events for the whole tree.
        root.setFilterTouchesWhenObscured(true);
    }
}
