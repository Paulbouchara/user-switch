package dev.userswitch;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

public final class MainActivity extends Activity implements Store.Listener {
    static final String START_CMD =
            "adb shell 'P=$(pm path dev.userswitch | head -1 | cut -d: -f2); "
                    + "setsid nohup app_process -Djava.class.path=$P /system/bin --nice-name=userswitchd "
                    + "dev.userswitch.daemon.Main > /data/local/tmp/userswitch.log 2>&1 < /dev/null &'";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Store store;
    private ShizukuLauncher shizuku;
    private TextView status;
    private TextView shizukuStatus;
    private Button shizukuButton;
    private TextView recordHint;
    private Button recordButton;
    private LinearLayout list;
    /** What the list currently shows; rebuilding every tick could swallow a tap on "Delete". */
    private String shownList;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            refresh();
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        store = Store.get(this);
        shizuku = ShizukuLauncher.get(this);
        if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            // for the after-reboot reminder
            requestPermissions(new String[] {android.Manifest.permission.POST_NOTIFICATIONS}, 0);
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        root.setPadding(pad, pad, pad, pad);

        TextView title = text(getString(R.string.app_name), 26);
        root.addView(title);

        status = text("", 15);
        status.setPadding(0, dp(8), 0, dp(16));
        root.addView(status);

        shizukuStatus = text("", 15);
        root.addView(shizukuStatus);
        shizukuButton = new Button(this);
        shizukuButton.setOnClickListener(v -> onShizukuButton());
        root.addView(shizukuButton);
        LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        gap.bottomMargin = dp(16);
        shizukuButton.setLayoutParams(gap);

        recordButton = new Button(this);
        recordButton.setText(R.string.record);
        recordButton.setOnClickListener(v -> {
            if (store.isRecording()) store.cancelRecording();
            else store.startRecording();
        });
        root.addView(recordButton);

        recordHint = text("", 15);
        recordHint.setPadding(0, dp(8), 0, dp(16));
        root.addView(recordHint);

        root.addView(text(getString(R.string.sequences), 20));
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        root.addView(list);

        TextView help = text(getString(R.string.pc_help, START_CMD), 13);
        help.setPadding(0, dp(24), 0, 0);
        help.setTextIsSelectable(true);
        root.addView(help);

        ScrollView scroll = new ScrollView(this);
        scroll.setFitsSystemWindows(true);
        scroll.addView(root);
        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        store.setListener(this);
        shizuku.setListener(this::onChanged);
        handler.post(tick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        store.setListener(null);
        shizuku.setListener(null);
        handler.removeCallbacks(tick);
    }

    @Override
    public void onChanged() {
        handler.post(this::refresh);
    }

    /** While recording, swallow the volume keys so the sequence does not change the volume. */
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (store.isRecording() && isVolume(keyCode)) return true;
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (store.isRecording() && isVolume(keyCode)) return true;
        return super.onKeyUp(keyCode, event);
    }

    private static boolean isVolume(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN;
    }

    private void refresh() {
        long seen = store.daemonSeenAt();
        long ago = (System.currentTimeMillis() - seen) / 1000;
        if (seen == 0) {
            status.setText(R.string.daemon_waiting);
        } else if (ago <= 2 * HEARTBEAT_S) {
            status.setText(getString(R.string.daemon_running, store.daemonPid()));
        } else {
            status.setText(getString(R.string.daemon_stopped, (int) ago));
        }
        refreshShizuku();

        boolean recording = store.isRecording();
        recordButton.setText(recording ? R.string.record_cancel : R.string.record);
        recordHint.setText(recording ? getString(R.string.record_hint) : "");

        String recorded = store.takeRecorded();
        if (recorded != null) chooseTarget(recorded);

        List<Store.Sequence> seqs = store.sequences();
        List<Store.User> users = store.users();
        List<String> labels = new ArrayList<>();
        StringBuilder sig = new StringBuilder();
        for (Store.Sequence s : seqs) {
            String label = Store.targetLabel(this, s.target, users);
            labels.add(label);
            sig.append(s.keys).append('>').append(label).append(';');
        }
        if (sig.toString().equals(shownList)) return;
        shownList = sig.toString();

        list.removeAllViews();
        if (seqs.isEmpty()) list.addView(text(getString(R.string.no_sequences), 15));
        for (int i = 0; i < seqs.size(); i++) {
            Store.Sequence s = seqs.get(i);
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(6), 0, dp(6));
            TextView label = text(Store.pretty(s.keys) + "\n→ " + labels.get(i), 15);
            row.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            Button del = new Button(this);
            del.setText(R.string.delete);
            del.setOnClickListener(v -> store.removeSequence(s.keys));
            row.addView(del);
            list.addView(row);
        }
    }

    private static final long HEARTBEAT_S = 30;

    private void refreshShizuku() {
        ShizukuLauncher.State st = shizuku.state();
        int text;
        int button;
        switch (st) {
            case NOT_INSTALLED:
                text = R.string.shizuku_not_installed;
                button = 0;
                break;
            case NOT_RUNNING:
                text = R.string.shizuku_not_running;
                button = R.string.open_shizuku;
                break;
            case TOO_OLD:
                text = R.string.shizuku_too_old;
                button = R.string.open_shizuku;
                break;
            case NEEDS_PERMISSION:
                text = R.string.shizuku_needs_permission;
                button = R.string.grant_and_start;
                break;
            case DENIED:
                text = R.string.shizuku_denied;
                button = R.string.retry;
                break;
            case READY:
                text = R.string.shizuku_ready;
                button = R.string.start_daemon;
                break;
            default:
                text = R.string.shizuku_running;
                button = R.string.stop_daemon;
        }
        shizukuStatus.setText(text);
        shizukuButton.setVisibility(button == 0 ? android.view.View.GONE : android.view.View.VISIBLE);
        if (button != 0) shizukuButton.setText(button);
    }

    private void onShizukuButton() {
        switch (shizuku.state()) {
            case NOT_RUNNING:
            case TOO_OLD:
                android.content.Intent i = getPackageManager().getLaunchIntentForPackage(ShizukuLauncher.SHIZUKU_PACKAGE);
                if (i != null) startActivity(i);
                break;
            case RUNNING:
                shizuku.stop();
                break;
            default:
                shizuku.start();
        }
        refresh();
    }

    private void chooseTarget(String keys) {
        List<String> labels = new ArrayList<>();
        List<String> targets = new ArrayList<>();
        labels.add(getString(R.string.target_next));
        targets.add("next");
        for (Store.User u : store.users()) {
            labels.add(getString(R.string.target_user, u.name, u.id));
            targets.add(Integer.toString(u.id));
        }
        new AlertDialog.Builder(this)
                .setTitle(Store.pretty(keys))
                .setItems(labels.toArray(new String[0]), (d, which) -> {
                    store.putSequence(keys, targets.get(which));
                    Toast.makeText(this, R.string.sequence_saved, Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private TextView text(String s, int sp) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        return t;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
