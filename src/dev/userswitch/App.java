package dev.userswitch;

import android.app.Application;

public final class App extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        // Registers the Shizuku binder listener, which starts the daemon as soon as Shizuku is up.
        ShizukuLauncher.get(this);
    }
}
