package dev.userswitch;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

import dev.userswitch.M3.Type;

public final class MainActivity extends Activity implements Store.Listener {
    static final String START_CMD =
            "adb shell 'P=$(pm path dev.userswitch | head -1 | cut -d: -f2); "
                    + "setsid nohup app_process -Djava.class.path=$P /system/bin --nice-name=userswitchd "
                    + "dev.userswitch.daemon.Main > /data/local/tmp/userswitch.log 2>&1 < /dev/null &'";

    private static final long HEARTBEAT_S = 30;
    /** Window width (dp) from which the screen gets two panes: an unfolded Fold, a tablet. */
    private static final int TWO_PANE_DP = 600;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Store store;
    private ShizukuLauncher shizuku;
    private M3 m;
    private boolean grapheneOs;
    private boolean foldable;

    // views rebuilt by build() (on create and on every fold / unfold)
    private ScrollView scroll;
    private LinearLayout page;
    private ImageView daemonBadge;
    private TextView daemonValue;
    private ImageView shizukuBadge;
    private TextView shizukuValue;
    private TextView shizukuButton;
    private LinearLayout recordingCard;
    private TextView recordingLeft;
    private LinearLayout guide;
    private LinearLayout list;
    private LinearLayout fab;
    private ImageView fabIcon;
    private TextView fabLabel;

    // what is on screen, so a tick only rebuilds what changed (and a rebuild cannot swallow a tap)
    private String shownList;
    private String shownGuide;
    private boolean guideOpen;
    private boolean pcHelpOpen;
    private boolean hardeningOpen;

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
        grapheneOs = Hardening.isGrapheneOs(this);
        foldable = getPackageManager().hasSystemFeature(PackageManager.FEATURE_SENSOR_HINGE_ANGLE);
        if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            // for the after-reboot reminder
            requestPermissions(new String[] {android.Manifest.permission.POST_NOTIFICATIONS}, 0);
        }
        edgeToEdge();
        build();
    }

    /** Android 15+ already draws apps targeting it edge to edge; earlier ones must ask. */
    @SuppressWarnings("deprecation")
    private void edgeToEdge() {
        if (android.os.Build.VERSION.SDK_INT < 35) getWindow().setDecorFitsSystemWindows(false);
    }

    @Override
    public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        int y = scroll.getScrollY();
        build();
        scroll.post(() -> scroll.scrollTo(0, y));
        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        store.setListener(this);
        shizuku.setListener(this::onChanged);
        // Opening the app brings a dead daemon back (unless it was stopped from here).
        shizuku.startIfGranted();
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

    // ---- layout -------------------------------------------------------------

    private boolean twoPane() {
        return getResources().getConfiguration().screenWidthDp >= TWO_PANE_DP;
    }

    private void build() {
        m = new M3(this);
        shownList = null;
        shownGuide = null;
        boolean wide = twoPane();

        page = m.column();
        page.addView(m.text(getString(R.string.app_name), Type.HEADLINE_L, m.color(R.color.on_surface)));
        TextView tagline = m.text(getString(R.string.tagline), Type.BODY_L, m.color(R.color.on_surface_variant));
        page.addView(tagline, m.top(M3.fill(), 4));

        LinearLayout left;
        LinearLayout right;
        if (wide) {
            // Unfolded: state and setup on the left, what the user works with on the right.
            LinearLayout panes = m.row();
            panes.setGravity(Gravity.TOP);
            left = m.column();
            right = m.column();
            panes.addView(left, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.2f);
            rp.setMarginStart(m.dp(24));
            panes.addView(right, rp);
            page.addView(panes, m.top(M3.fill(), 28));
        } else {
            left = right = m.column();
            page.addView(left, m.top(M3.fill(), 24));
        }

        left.addView(statusCard(), M3.fill());
        right.addView(recordingCard(), wide ? M3.fill() : m.top(M3.fill(), 16));
        guide = m.column();
        left.addView(guide, m.top(M3.fill(), 16));

        TextView header = m.text(getString(R.string.sequences), Type.TITLE_S, m.color(R.color.primary));
        header.setPadding(m.dp(16), 0, 0, m.dp(8));
        right.addView(header, m.top(M3.fill(), wide ? 0 : 24));
        list = m.column();
        right.addView(list, M3.fill());

        if (grapheneOs) left.addView(hardeningCard(), m.top(M3.fill(), 16));
        left.addView(pcHelpCard(), m.top(M3.fill(), 16));

        scroll = new ScrollView(this);
        scroll.setClipToPadding(false);
        // Out of touch mode (keyboard, or launched over ADB) the first button would take
        // focus and the page would open scrolled down to it: start focused at the top.
        scroll.setFocusable(true);
        scroll.setDescendantFocusability(ViewGroup.FOCUS_BEFORE_DESCENDANTS);
        scroll.addView(page);

        fab = fab();
        FrameLayout frame = new FrameLayout(this);
        frame.addView(scroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        // Behind the status bar: the page scrolls under it, the clock must stay readable.
        View statusScrim = new View(this);
        statusScrim.setBackgroundColor(m.color(R.color.surface)); // opaque: text showed through at 94%
        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, Gravity.TOP);
        frame.addView(statusScrim, sp);
        FrameLayout.LayoutParams fp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.END);
        frame.addView(fab, fp);

        // Edge to edge: the content scrolls under the bars, padded by their insets.
        frame.setOnApplyWindowInsetsListener((v, insets) -> {
            Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            int side = m.dp(wide ? 24 : 16);
            page.setPadding(side + bars.left, bars.top + m.dp(wide ? 32 : 24), side + bars.right,
                    bars.bottom + m.dp(56 + 16 + 24));
            fp.setMargins(0, 0, m.dp(16) + bars.right, m.dp(16) + bars.bottom);
            fab.setLayoutParams(fp);
            sp.height = bars.top;
            statusScrim.setLayoutParams(sp);
            return WindowInsets.CONSUMED;
        });

        setContentView(frame);
        Hardening.apply(this, frame);
    }

    private LinearLayout statusCard() {
        LinearLayout card = m.card(m.color(R.color.surface_container), 28);

        LinearLayout daemon = m.row();
        daemonBadge = m.badge(R.drawable.ic_schedule, 0, 0, 40);
        daemon.addView(daemonBadge);
        LinearLayout dText = m.column();
        dText.addView(m.text(getString(R.string.status_daemon), Type.TITLE_M, m.color(R.color.on_surface)));
        daemonValue = m.text("", Type.BODY_M, m.color(R.color.on_surface_variant));
        dText.addView(daemonValue);
        LinearLayout.LayoutParams tp = M3.weight();
        tp.setMarginStart(m.dp(16));
        daemon.addView(dText, tp);
        card.addView(daemon);

        card.addView(m.divider());

        LinearLayout shz = m.row();
        shz.setGravity(Gravity.TOP);
        shizukuBadge = m.badge(R.drawable.ic_terminal, 0, 0, 40);
        shz.addView(shizukuBadge);
        LinearLayout sText = m.column();
        sText.addView(m.text(getString(R.string.status_shizuku), Type.TITLE_M, m.color(R.color.on_surface)));
        shizukuValue = m.text("", Type.BODY_M, m.color(R.color.on_surface_variant));
        sText.addView(shizukuValue);
        shizukuButton = m.tonalButton("", 0);
        shizukuButton.setOnClickListener(v -> onShizukuButton());
        LinearLayout.LayoutParams bp = M3.wrap();
        bp.topMargin = m.dp(12);
        sText.addView(shizukuButton, bp);
        LinearLayout.LayoutParams sp = M3.weight();
        sp.setMarginStart(m.dp(16));
        shz.addView(sText, sp);
        card.addView(shz);
        return card;
    }

    private LinearLayout recordingCard() {
        int fg = m.color(R.color.on_tertiary_container);
        recordingCard = m.card(m.color(R.color.tertiary_container), 28);
        LinearLayout top = m.row();
        top.addView(m.icon(R.drawable.ic_schedule, fg, 24));
        TextView title = m.text(getString(R.string.recording_title), Type.TITLE_M, fg);
        LinearLayout.LayoutParams lp = M3.weight();
        lp.setMarginStart(m.dp(12));
        top.addView(title, lp);
        recordingLeft = m.text("", Type.LABEL_L, fg);
        top.addView(recordingLeft);
        recordingCard.addView(top);
        recordingCard.addView(m.text(getString(R.string.record_hint), Type.BODY_M, fg), m.top(M3.fill(), 8));
        recordingCard.setVisibility(View.GONE);
        return recordingCard;
    }

    private LinearLayout fab() {
        int fg = m.color(R.color.on_primary_container);
        LinearLayout f = m.row();
        f.setMinimumHeight(m.dp(56));
        f.setMinimumWidth(m.dp(80));
        f.setPadding(m.dp(16), 0, m.dp(20), 0);
        f.setBackground(m.ripple(m.shape(m.color(R.color.primary_container), 16), fg));
        f.setElevation(m.dp(6));
        f.setClickable(true);
        f.setFocusable(true);
        fabIcon = m.icon(R.drawable.ic_add, fg, 24);
        f.addView(fabIcon);
        fabLabel = m.text(getString(R.string.record_fab), Type.LABEL_L, fg);
        LinearLayout.LayoutParams lp = M3.wrap();
        lp.setMarginStart(m.dp(12));
        f.addView(fabLabel, lp);
        f.setOnClickListener(v -> {
            if (store.isRecording()) store.cancelRecording();
            else store.startRecording();
        });
        return f;
    }

    /** A card that folds open on its title row (PC help, hardening). */
    private LinearLayout expandable(int iconRes, String title, boolean open, Runnable toggle, View body) {
        int fg = m.color(R.color.on_surface);
        LinearLayout card = m.column();
        card.setBackground(m.shape(m.color(R.color.surface_container_low), 28));
        LinearLayout head = m.row();
        head.setPadding(m.dp(20), m.dp(16), m.dp(12), m.dp(16));
        head.setBackground(m.ripple(null, fg));
        head.setClickable(true);
        head.addView(m.icon(iconRes, m.color(R.color.on_surface_variant), 24));
        TextView t = m.text(title, Type.TITLE_M, fg);
        LinearLayout.LayoutParams lp = M3.weight();
        lp.setMarginStart(m.dp(16));
        head.addView(t, lp);
        ImageView chevron = m.icon(R.drawable.ic_expand, m.color(R.color.on_surface_variant), 24);
        chevron.setRotation(open ? 180 : 0);
        head.addView(chevron);
        card.addView(head);
        body.setPadding(m.dp(20), 0, m.dp(20), m.dp(20));
        body.setVisibility(open ? View.VISIBLE : View.GONE);
        card.addView(body);
        head.setOnClickListener(v -> {
            toggle.run();
            boolean nowOpen = body.getVisibility() != View.VISIBLE;
            body.setVisibility(nowOpen ? View.VISIBLE : View.GONE);
            chevron.animate().rotation(nowOpen ? 180 : 0).setDuration(200).start();
        });
        return card;
    }

    private LinearLayout pcHelpCard() {
        LinearLayout body = m.column();
        body.addView(m.text(getString(R.string.pc_help), Type.BODY_M, m.color(R.color.on_surface_variant)));
        TextView code = m.text(START_CMD, Type.BODY_M, m.color(R.color.on_surface));
        code.setTypeface(Typeface.MONOSPACE);
        code.setTextSize(12);
        code.setTextIsSelectable(true);
        code.setPadding(m.dp(12), m.dp(12), m.dp(12), m.dp(12));
        code.setBackground(m.shape(m.color(R.color.surface_container_highest), 12));
        body.addView(code, m.top(M3.fill(), 12));
        TextView copy = m.textButton(getString(R.string.copy), R.drawable.ic_copy);
        copy.setOnClickListener(v -> {
            getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("adb", START_CMD));
            Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show();
        });
        LinearLayout.LayoutParams cp = m.top(M3.wrap(), 8);
        cp.gravity = Gravity.END;
        body.addView(copy, cp);
        return expandable(R.drawable.ic_terminal, getString(R.string.pc_help_title), pcHelpOpen,
                () -> pcHelpOpen = !pcHelpOpen, body);
    }

    private LinearLayout hardeningCard() {
        LinearLayout body = m.column();
        int fg = m.color(R.color.on_surface_variant);
        body.addView(m.text(getString(R.string.hardening_intro), Type.BODY_M, fg));
        for (int res : new int[] {R.string.hardening_mte, R.string.hardening_compat,
                R.string.hardening_dcl, R.string.hardening_sensors}) {
            LinearLayout item = m.row();
            item.setGravity(Gravity.TOP);
            // a recommendation the app cannot read back: an arrow, not a check mark
            item.addView(m.icon(R.drawable.ic_arrow_forward, m.color(R.color.primary), 20));
            LinearLayout.LayoutParams lp = M3.weight();
            lp.setMarginStart(m.dp(12));
            item.addView(m.text(getString(res), Type.BODY_M, m.color(R.color.on_surface)), lp);
            body.addView(item, m.top(M3.fill(), 12));
        }
        body.addView(m.text(getString(R.string.hardening_daemon), Type.BODY_M, fg), m.top(M3.fill(), 12));
        TextView open = m.tonalButton(getString(R.string.hardening_button), 0);
        open.setOnClickListener(v -> launch(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", getPackageName(), null))));
        body.addView(open, m.top(M3.wrap(), 16));
        return expandable(R.drawable.ic_shield, getString(R.string.hardening_title), hardeningOpen,
                () -> hardeningOpen = !hardeningOpen, body);
    }

    // ---- refresh ------------------------------------------------------------

    private void refresh() {
        refreshDaemon();
        ShizukuLauncher.State st = shizuku.state();
        refreshShizuku(st);
        refreshGuide(st);

        boolean recording = store.isRecording();
        recordingCard.setVisibility(recording ? View.VISIBLE : View.GONE);
        if (recording) {
            recordingLeft.setText(getString(R.string.recording_left, (int) ((store.recordingLeftMs() + 999) / 1000)));
        }
        fabIcon.setImageResource(recording ? R.drawable.ic_close : R.drawable.ic_add);
        fabLabel.setText(recording ? R.string.cancel : R.string.record_fab);
        fab.setContentDescription(getString(recording ? R.string.record_cancel : R.string.record));

        String recorded = store.takeRecorded();
        if (recorded != null) chooseTarget(recorded);

        refreshList();
    }

    /** The daemon sent a heartbeat recently (it beats every HEARTBEAT_S). */
    private boolean daemonRunning() {
        long seen = store.daemonSeenAt();
        return seen != 0 && System.currentTimeMillis() - seen <= 2 * HEARTBEAT_S * 1000;
    }

    private void refreshDaemon() {
        long seen = store.daemonSeenAt();
        long ago = (System.currentTimeMillis() - seen) / 1000;
        int icon, bg, fg;
        if (seen == 0) {
            daemonValue.setText(R.string.daemon_waiting);
            icon = R.drawable.ic_schedule;
            bg = R.color.surface_container_highest;
            fg = R.color.on_surface_variant;
        } else if (daemonRunning()) {
            daemonValue.setText(getString(R.string.daemon_running, store.daemonPid()));
            icon = R.drawable.ic_check;
            bg = R.color.primary_container;
            fg = R.color.on_primary_container;
        } else {
            daemonValue.setText(getString(R.string.daemon_stopped, (int) ago));
            icon = R.drawable.ic_warning;
            bg = R.color.error_container;
            fg = R.color.on_error_container;
        }
        setBadge(daemonBadge, icon, bg, fg);
    }

    private void setBadge(ImageView badge, int icon, int bg, int fg) {
        badge.setImageResource(icon);
        badge.setImageTintList(android.content.res.ColorStateList.valueOf(m.color(fg)));
        badge.setBackground(m.shape(m.color(bg), 20));
    }

    private void refreshShizuku(ShizukuLauncher.State st) {
        int text, button, bg, fg;
        switch (st) {
            case NOT_INSTALLED:
                text = R.string.shizuku_not_installed;
                button = 0;
                bg = R.color.surface_container_highest;
                fg = R.color.on_surface_variant;
                break;
            case NOT_RUNNING:
                text = R.string.shizuku_not_running;
                button = R.string.open_shizuku;
                bg = R.color.tertiary_container;
                fg = R.color.on_tertiary_container;
                break;
            case TOO_OLD:
                text = R.string.shizuku_too_old;
                button = R.string.open_shizuku;
                bg = R.color.error_container;
                fg = R.color.on_error_container;
                break;
            case NEEDS_PERMISSION:
                text = R.string.shizuku_needs_permission;
                button = R.string.grant_and_start;
                bg = R.color.tertiary_container;
                fg = R.color.on_tertiary_container;
                break;
            case DENIED:
                text = R.string.shizuku_denied;
                button = R.string.retry;
                bg = R.color.error_container;
                fg = R.color.on_error_container;
                break;
            case READY:
                text = R.string.shizuku_ready;
                button = R.string.start_daemon;
                bg = R.color.secondary_container;
                fg = R.color.on_secondary_container;
                break;
            default:
                text = R.string.shizuku_running;
                button = R.string.stop_daemon;
                bg = R.color.primary_container;
                fg = R.color.on_primary_container;
        }
        shizukuValue.setText(text);
        setBadge(shizukuBadge, R.drawable.ic_terminal, bg, fg);
        shizukuButton.setVisibility(button == 0 ? View.GONE : View.VISIBLE);
        if (button != 0) shizukuButton.setText(button);
    }

    private void onShizukuButton() {
        switch (shizuku.state()) {
            case NOT_RUNNING:
            case TOO_OLD:
                openShizuku();
                break;
            case RUNNING:
                shizuku.stop();
                break;
            default:
                shizuku.start();
        }
        refresh();
    }

    // ---- Shizuku setup guide ----------------------------------------------

    private static final int STEPS = 6;

    /** How far the setup has come: the index of the first step not done (STEPS when all are). */
    private int guideProgress(ShizukuLauncher.State st) {
        if (st == ShizukuLauncher.State.READY || st == ShizukuLauncher.State.RUNNING) return STEPS;
        boolean started = st == ShizukuLauncher.State.NEEDS_PERMISSION || st == ShizukuLauncher.State.DENIED;
        if (started) return 5;
        if (st == ShizukuLauncher.State.NOT_INSTALLED || st == ShizukuLauncher.State.TOO_OLD) return 0;
        // Shizuku has run for us before, so it is paired: it only needs starting again
        // (after a reboot, or a framework restart that leaves shizuku_server unusable).
        if (ShizukuLauncher.everConnected(this)) return 4;
        // Only a 1 can be trusted: Android 17 (beta, Pixel 11 Pro Fold) silently answers 0
        // to apps for both settings even when they are on. A 0 therefore just leaves the
        // user on that step, whose text says how to check it; -1 (unreadable) counts as done.
        if (globalSetting(Settings.Global.DEVELOPMENT_SETTINGS_ENABLED) == 0) return 1;
        if (globalSetting("adb_wifi_enabled") == 0) return 2;
        return 3; // pairing cannot be seen from here; starting Shizuku shows it worked
    }

    /** A global setting, -1 when this app may not read it. */
    private int globalSetting(String name) {
        try {
            return Settings.Global.getInt(getContentResolver(), name, 0);
        } catch (SecurityException e) {
            return -1;
        }
    }

    private void refreshGuide(ShizukuLauncher.State st) {
        int progress = guideProgress(st);
        boolean wide = twoPane();
        String sig = progress + "/" + guideOpen + "/" + wide + "/" + daemonRunning();
        if (sig.equals(shownGuide)) return;
        shownGuide = sig;
        guide.removeAllViews();

        boolean done = progress >= STEPS;
        // A running daemon (through Shizuku or started from a computer) needs no setup.
        if ((done || daemonRunning()) && !guideOpen) {
            // Set up: keep the guide one tap away for after a reboot or a new phone.
            LinearLayout row = expandable(R.drawable.ic_check, getString(R.string.guide_show), false,
                    () -> {
                        guideOpen = true;
                        shownGuide = null;
                        handler.post(this::refresh);
                    }, new View(this));
            guide.addView(row);
            return;
        }

        LinearLayout card = m.card(m.color(R.color.surface_container_low), 28);
        LinearLayout head = m.row();
        head.addView(m.text(getString(R.string.guide_title), Type.TITLE_L, m.color(R.color.on_surface)), M3.weight());
        if (done || daemonRunning()) {
            ImageView close = m.iconButton(R.drawable.ic_expand, m.color(R.color.on_surface_variant), null);
            close.setRotation(180);
            close.setOnClickListener(v -> {
                guideOpen = false;
                refresh();
            });
            head.addView(close);
        }
        card.addView(head);
        card.addView(m.text(getString(done ? R.string.guide_done : R.string.guide_subtitle), Type.BODY_M,
                m.color(R.color.on_surface_variant)), m.top(M3.fill(), 4));

        int[][] steps = {
                {R.string.step_install, R.string.step_install_body, R.string.step_install_button},
                {R.string.step_dev, R.string.step_dev_body, R.string.step_dev_button},
                {R.string.step_wireless, R.string.step_wireless_body, R.string.step_wireless_button},
                {R.string.step_pair, R.string.step_pair_body, R.string.open_shizuku},
                {R.string.step_start, R.string.step_start_body, R.string.open_shizuku},
                {R.string.step_grant, R.string.step_grant_body, R.string.grant_and_start},
        };
        for (int i = 0; i < STEPS; i++) {
            card.addView(step(i, steps[i], progress), m.top(M3.fill(), 20));
        }
        guide.addView(card);
    }

    private View step(int i, int[] s, int progress) {
        boolean done = i < progress;
        boolean current = i == progress;
        LinearLayout row = m.row();
        row.setGravity(Gravity.TOP);

        TextView num = m.text(done ? "" : Integer.toString(i + 1), Type.LABEL_L,
                m.color(current ? R.color.on_primary : R.color.on_surface_variant));
        num.setGravity(Gravity.CENTER);
        num.setBackground(m.shape(m.color(current ? R.color.primary
                : done ? R.color.primary_container : R.color.surface_container_highest), 14));
        if (done) {
            android.graphics.drawable.Drawable ok = getDrawable(R.drawable.ic_check).mutate();
            ok.setTint(m.color(R.color.on_primary_container));
            ok.setBounds(0, 0, m.dp(16), m.dp(16));
            num.setCompoundDrawables(ok, null, null, null);
            num.setPadding(m.dp(6), 0, 0, 0);
        }
        row.addView(num, new LinearLayout.LayoutParams(m.dp(28), m.dp(28)));

        LinearLayout text = m.column();
        text.addView(m.text(getString(s[0]), Type.TITLE_M,
                m.color(done ? R.color.on_surface_variant : R.color.on_surface)));
        if (!done) {
            text.addView(m.text(getString(s[1]), Type.BODY_M, m.color(R.color.on_surface_variant)),
                    m.top(M3.fill(), 2));
        }
        if (current && i == 3) {
            // Pairing means reading a code in Settings and typing it in Shizuku: on a
            // Fold that is easiest unfolded, side by side.
            int tip = !foldable ? R.string.step_pair_tip_phone
                    : twoPane() ? R.string.step_pair_tip_open : R.string.step_pair_tip_closed;
            TextView t = m.text(getString(tip), Type.BODY_M, m.color(R.color.on_secondary_container));
            t.setPadding(m.dp(12), m.dp(10), m.dp(12), m.dp(10));
            t.setBackground(m.shape(m.color(R.color.secondary_container), 12));
            text.addView(t, m.top(M3.fill(), 10));
        }
        if (current) {
            TextView b = m.filledButton(getString(s[2]), 0);
            b.setOnClickListener(v -> stepAction(i));
            text.addView(b, m.top(M3.wrap(), 12));
        }
        LinearLayout.LayoutParams lp = M3.weight();
        lp.setMarginStart(m.dp(16));
        row.addView(text, lp);
        return row;
    }

    private void stepAction(int i) {
        switch (i) {
            case 0:
                if (!launch(new Intent(Intent.ACTION_VIEW,
                        Uri.parse("market://details?id=" + ShizukuLauncher.SHIZUKU_PACKAGE)))) {
                    launch(new Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/RikkaApps/Shizuku/releases")));
                }
                break;
            case 1:
                launch(new Intent(Settings.ACTION_DEVICE_INFO_SETTINGS));
                break;
            case 2:
                launch(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS));
                break;
            case 3:
            case 4:
                openShizuku();
                break;
            default:
                shizuku.start();
                refresh();
        }
    }

    private void openShizuku() {
        Intent i = getPackageManager().getLaunchIntentForPackage(ShizukuLauncher.SHIZUKU_PACKAGE);
        if (i != null) launch(i);
    }

    private boolean launch(Intent i) {
        try {
            startActivity(i);
            return true;
        } catch (ActivityNotFoundException e) {
            return false;
        }
    }

    // ---- sequences ----------------------------------------------------------

    private void refreshList() {
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
        if (seqs.isEmpty()) {
            list.addView(emptyState());
            return;
        }
        for (int i = 0; i < seqs.size(); i++) {
            // Pixel-style grouped list: round outer corners, small inner ones, 2 dp apart.
            float topR = i == 0 ? 24 : 4;
            float bottomR = i == seqs.size() - 1 ? 24 : 4;
            list.addView(sequenceItem(seqs.get(i), labels.get(i), topR, bottomR), m.top(M3.fill(), i == 0 ? 0 : 2));
        }
    }

    private View sequenceItem(Store.Sequence s, String target, float topR, float bottomR) {
        LinearLayout item = m.row();
        item.setBackground(m.shape(m.color(R.color.surface_container), topR, topR, bottomR, bottomR));
        item.setPadding(m.dp(16), m.dp(14), m.dp(4), m.dp(14));

        LinearLayout text = m.column();
        M3.Flow keys = new M3.Flow(this, m.dp(6));
        for (String tok : s.keys.split(" ")) keys.addView(m.keyChip(Store.pretty(tok)));
        text.addView(keys, M3.fill());
        LinearLayout to = m.row();
        to.addView(m.icon(R.drawable.ic_arrow_forward, m.color(R.color.primary), 18));
        LinearLayout.LayoutParams lp = M3.weight();
        lp.setMarginStart(m.dp(8));
        to.addView(m.text(target, Type.BODY_L, m.color(R.color.on_surface)), lp);
        text.addView(to, m.top(M3.fill(), 10));
        item.addView(text, M3.weight());

        ImageView del = m.iconButton(R.drawable.ic_delete, m.color(R.color.on_surface_variant),
                getString(R.string.delete));
        del.setOnClickListener(v -> store.removeSequence(s.keys));
        item.addView(del);
        return item;
    }

    private View emptyState() {
        LinearLayout card = m.card(m.color(R.color.surface_container_low), 24);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(m.dp(24), m.dp(32), m.dp(24), m.dp(32));
        card.addView(m.badge(R.drawable.ic_people, m.color(R.color.secondary_container),
                m.color(R.color.on_secondary_container), 56));
        TextView title = m.text(getString(R.string.no_sequences), Type.TITLE_M, m.color(R.color.on_surface));
        title.setGravity(Gravity.CENTER);
        card.addView(title, m.top(M3.wrap(), 16));
        TextView hint = m.text(getString(R.string.no_sequences_hint), Type.BODY_M, m.color(R.color.on_surface_variant));
        hint.setGravity(Gravity.CENTER);
        card.addView(hint, m.top(M3.wrap(), 4));
        return card;
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
}
