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
    /** What the list currently shows; rebuilding every tick could swallow a tap on "Supprimer". */
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

        TextView title = text("User Switch", 26);
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
        recordButton.setText("Enregistrer une séquence");
        recordButton.setOnClickListener(v -> {
            if (store.isRecording()) store.cancelRecording();
            else store.startRecording();
        });
        root.addView(recordButton);

        recordHint = text("", 15);
        recordHint.setPadding(0, dp(8), 0, dp(16));
        root.addView(recordHint);

        root.addView(text("Séquences", 20));
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        root.addView(list);

        TextView help = text("Sans Shizuku, le démon se lance depuis le PC (à refaire après chaque redémarrage) :\n\n" + START_CMD, 13);
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
            status.setText("Démon : en attente de son signal (toutes les 30 s).");
        } else if (ago <= 2 * HEARTBEAT_S) {
            status.setText("Démon : actif (pid " + store.daemonPid() + ")");
        } else {
            status.setText("Démon : arrêté, ou téléphone en veille (dernier signe il y a " + ago + " s)");
        }
        refreshShizuku();

        boolean recording = store.isRecording();
        recordButton.setText(recording ? "Annuler l'enregistrement" : "Enregistrer une séquence");
        recordHint.setText(recording
                ? "Fais ta séquence sur les boutons maintenant, puis attends une seconde.\n"
                        + "Au moins 3 points : 1 par appui, +1 si appui long, +1 si plusieurs boutons ensemble."
                : "");

        String recorded = store.takeRecorded();
        if (recorded != null) chooseTarget(recorded);

        List<Store.Sequence> seqs = store.sequences();
        List<Store.User> users = store.users();
        List<String> labels = new ArrayList<>();
        StringBuilder sig = new StringBuilder();
        for (Store.Sequence s : seqs) {
            String label = Store.targetLabel(s.target, users);
            labels.add(label);
            sig.append(s.keys).append('>').append(label).append(';');
        }
        if (sig.toString().equals(shownList)) return;
        shownList = sig.toString();

        list.removeAllViews();
        if (seqs.isEmpty()) list.addView(text("Aucune séquence.", 15));
        for (int i = 0; i < seqs.size(); i++) {
            Store.Sequence s = seqs.get(i);
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(6), 0, dp(6));
            TextView label = text(Store.pretty(s.keys) + "\n→ " + labels.get(i), 15);
            row.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            Button del = new Button(this);
            del.setText("Supprimer");
            del.setOnClickListener(v -> store.removeSequence(s.keys));
            row.addView(del);
            list.addView(row);
        }
    }

    private static final long HEARTBEAT_S = 30;

    private void refreshShizuku() {
        ShizukuLauncher.State st = shizuku.state();
        String text;
        String button;
        switch (st) {
            case NOT_INSTALLED:
                text = "Shizuku : non installé. Installe-le pour lancer le démon sans PC.";
                button = null;
                break;
            case NOT_RUNNING:
                text = "Shizuku : installé mais pas démarré. Démarre-le dans l'app Shizuku (débogage sans fil), "
                        + "le démon suivra automatiquement.";
                button = "Ouvrir Shizuku";
                break;
            case TOO_OLD:
                text = "Shizuku : version trop ancienne, mets-le à jour.";
                button = "Ouvrir Shizuku";
                break;
            case NEEDS_PERMISSION:
                text = "Shizuku : démarré, autorisation à accorder.";
                button = "Autoriser et lancer le démon";
                break;
            case DENIED:
                text = "Shizuku : autorisation refusée. Accorde-la dans l'app Shizuku.";
                button = "Réessayer";
                break;
            case READY:
                text = "Shizuku : prêt.";
                button = "Lancer le démon via Shizuku";
                break;
            default:
                text = "Shizuku : le démon tourne via Shizuku. Il redémarrera avec Shizuku.";
                button = "Arrêter le démon";
        }
        shizukuStatus.setText(text);
        shizukuButton.setVisibility(button == null ? android.view.View.GONE : android.view.View.VISIBLE);
        if (button != null) shizukuButton.setText(button);
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
        labels.add("Profil suivant");
        targets.add("next");
        for (Store.User u : store.users()) {
            labels.add(u.name + " (" + u.id + ")");
            targets.add(Integer.toString(u.id));
        }
        new AlertDialog.Builder(this)
                .setTitle(Store.pretty(keys))
                .setItems(labels.toArray(new String[0]), (d, which) -> {
                    store.putSequence(keys, targets.get(which));
                    Toast.makeText(this, "Séquence enregistrée", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Annuler", null)
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
