package dev.userswitch.daemon;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import android.app.ActivityManager;
import android.content.AttributionSource;
import android.os.Binder;
import android.os.Bundle;
import android.os.DeadObjectException;
import android.os.IBinder;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Daemon started with ADB shell rights (uid 2000) through app_process.
 *
 * It reads the hardware keys straight from /dev/input, cuts the presses into
 * bursts ("UP UP DOWN:long"), hands every burst to the app's ConfigProvider
 * (which matches it or records it) and switches user when the app answers
 * with a target. Being a shell process, it keeps running when the foreground
 * user changes and wireless debugging goes down.
 */
public final class Main {
    static final String AUTHORITY = "dev.userswitch.config";
    static final String PID_FILE = "/data/local/tmp/userswitch.pid";

    static final int EV_SYN = 0;
    static final int SYN_DROPPED = 3;
    static final int EV_KEY = 1;
    static final int KEY_VOLUMEDOWN = 114;
    static final int KEY_VOLUMEUP = 115;
    static final int KEY_POWER = 116;
    static final int EVENT_SIZE = 24; // struct input_event on 64-bit

    /** Silence after the last release that closes a burst. */
    static final long GAP_MS = 800;
    /** A press (or chord) held at least this long is a long press. */
    static final long LONG_MS = 500;
    /** Bursts below this score are ignored, so normal volume use costs nothing. */
    static final int MIN_SCORE = 3;
    /** The app shows the daemon as running when it pinged less than 2 heartbeats ago. */
    static final long HEARTBEAT_S = 30;

    static final int FLAG_FULL = 0x400;
    static final int FLAG_PROFILE = 0x1000;

    private final ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor();
    private final Set<Integer> held = new HashSet<>();
    private final TreeSet<Integer> groupKeys = new TreeSet<>();
    private final List<String> tokens = new ArrayList<>();
    private long groupStart;
    private long groupFirstUp = -1;
    private ScheduledFuture<?> pending;

    public static void main(String[] args) {
        try {
            new Main().run();
        } catch (Throwable t) {
            log("fatal: " + t);
            t.printStackTrace();
            System.exit(1);
        }
    }

    private void run() throws Exception {
        int pid = android.os.Process.myPid();
        log("start pid=" + pid + " uid=" + android.os.Process.myUid());
        killPrevious();
        try (FileWriter w = new FileWriter(PID_FILE)) {
            w.write(Integer.toString(pid));
        }

        List<String> devices = findKeyDevices();
        if (devices.isEmpty()) throw new IllegalStateException("no input device with volume keys");
        log("input devices: " + devices);

        refreshUsers();
        exec.execute(() -> notifyApp("hello", "", false));
        // lets the app show whether a daemon is alive; one binder call, no process spawned
        exec.scheduleWithFixedDelay(() -> notifyApp("ping", "", false), HEARTBEAT_S, HEARTBEAT_S, TimeUnit.SECONDS);

        List<Thread> readers = new ArrayList<>();
        for (String dev : devices) {
            Thread t = new Thread(() -> readLoop(dev), "read-" + dev);
            t.start();
            readers.add(t);
        }
        for (Thread t : readers) t.join();
        // Exit instead of lingering: the heartbeat would keep telling the app we run.
        log("all readers stopped, exiting");
        System.exit(2);
    }

    // ---- input -------------------------------------------------------------

    /**
     * Event devices that report KEY_VOLUMEUP. SELinux hides /proc/bus/input
     * and the sysfs capabilities from the shell, but getevent (which asks the
     * devices themselves) is allowed.
     */
    static List<String> findKeyDevices() throws Exception {
        List<String> out = new ArrayList<>();
        String device = null;
        for (String line : sh("getevent", "-pl").split("\n")) {
            Matcher m = Pattern.compile("add device \\d+: (/dev/input/event\\d+)").matcher(line);
            if (m.find()) {
                device = m.group(1);
            } else if (device != null && line.contains("KEY_VOLUMEUP")) {
                out.add(device);
                device = null;
            }
        }
        return out;
    }

    private void readLoop(String dev) {
        byte[] buf = new byte[EVENT_SIZE];
        ByteBuffer bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN);
        try (InputStream in = new FileInputStream(dev)) {
            while (true) {
                int n = 0;
                while (n < EVENT_SIZE) {
                    int r = in.read(buf, n, EVENT_SIZE - n);
                    if (r < 0) throw new IOException("EOF");
                    n += r;
                }
                long sec = bb.getLong(0);
                long usec = bb.getLong(8);
                int type = bb.getShort(16) & 0xffff;
                int code = bb.getShort(18) & 0xffff;
                int value = bb.getInt(20);
                if (type == EV_SYN && code == SYN_DROPPED) {
                    // the kernel dropped events: key releases may be lost, start over
                    exec.execute(this::resetKeys);
                    continue;
                }
                if (type != EV_KEY) continue;
                if (code != KEY_VOLUMEUP && code != KEY_VOLUMEDOWN && code != KEY_POWER) continue;
                long t = sec * 1000 + usec / 1000;
                exec.execute(() -> onKey(code, value, t));
            }
        } catch (IOException e) {
            log("reader " + dev + " stopped: " + e);
        }
    }

    /** Runs on the executor thread only. */
    private void onKey(int code, int value, long t) {
        if (value == 2) return; // autorepeat
        if (value == 1) {
            if (held.contains(code)) resetKeys(); // its release was lost
            if (pending != null) pending.cancel(false);
            if (held.isEmpty()) {
                groupKeys.clear();
                groupStart = t;
                groupFirstUp = -1;
            }
            held.add(code);
            groupKeys.add(code);
        } else if (value == 0) {
            if (!held.remove(code)) return;
            if (groupFirstUp < 0) groupFirstUp = t;
            if (held.isEmpty()) {
                tokens.add(token(groupKeys, groupFirstUp - groupStart >= LONG_MS));
                pending = exec.schedule(this::finishBurst, GAP_MS, TimeUnit.MILLISECONDS);
            }
        }
    }

    /** Forgets keys held and the burst in progress, so a lost release cannot block bursts forever. */
    private void resetKeys() {
        held.clear();
        groupKeys.clear();
        tokens.clear();
        if (pending != null) pending.cancel(false);
        pending = null;
    }

    /** "UP", "DOWN", "POWER", chords as "UP+DOWN", long presses suffixed ":long". */
    static String token(Set<Integer> keys, boolean isLong) {
        StringBuilder sb = new StringBuilder();
        for (int k : new int[] {KEY_VOLUMEUP, KEY_VOLUMEDOWN, KEY_POWER}) {
            if (!keys.contains(k)) continue;
            if (sb.length() > 0) sb.append('+');
            sb.append(k == KEY_VOLUMEUP ? "UP" : k == KEY_VOLUMEDOWN ? "DOWN" : "POWER");
        }
        if (isLong) sb.append(":long");
        return sb.toString();
    }

    /** One point per press, one more for a chord, one more for a long press. */
    static int score(List<String> tokens) {
        int s = 0;
        for (String tok : tokens) {
            s++;
            if (tok.contains("+")) s++;
            if (tok.endsWith(":long")) s++;
        }
        return s;
    }

    private void finishBurst() {
        List<String> burst = new ArrayList<>(tokens);
        tokens.clear();
        pending = null;
        String seq = String.join(" ", burst);
        if (score(burst) < MIN_SCORE) return;
        log("burst: " + seq);
        notifyApp("burst", seq, true);
    }

    // ---- app ---------------------------------------------------------------

    private final IBinder providerToken = new Binder();
    private Object provider; // IContentProvider for AUTHORITY in user 0
    private boolean directCallBroken;

    private void notifyApp(String method, String arg, boolean mayswitch) {
        try {
            long t0 = System.currentTimeMillis();
            Bundle extras = new Bundle();
            // content's --extra splits on ':' so the fallback needs it encoded; keep one format
            extras.putString("users", android.net.Uri.encode(usersJson()));
            extras.putInt("current", currentUser());
            extras.putInt("pid", android.os.Process.myPid());
            Bundle result = callApp(method, arg.isEmpty() ? null : arg, extras);
            long t1 = System.currentTimeMillis();
            if (result.containsKey("recorded")) log(method + " -> recorded by the app (" + (t1 - t0) + " ms)");
            String target = result.getString("target");
            if (target == null) return;
            log(method + " -> target=" + target + " (" + (t1 - t0) + " ms)");
            if (mayswitch) switchTo(target);
        } catch (Exception e) {
            log(method + " failed: " + e);
        } finally {
            if (!method.equals("ping")) bg.execute(this::refreshUsers);
        }
    }

    private Bundle callApp(String method, String arg, Bundle extras) throws Exception {
        if (!directCallBroken) {
            try {
                return callDirect(method, arg, extras);
            } catch (Throwable t) {
                directCallBroken = true;
                log("direct provider call unavailable, falling back to `content`: " + t);
            }
        }
        return callViaContent(method, arg, extras);
    }

    /**
     * What the `content` tool does internally, minus starting a whole VM per
     * call: get the provider binder through IActivityManager and call it.
     * The binder is kept, and fetched again if the app process died.
     */
    private Bundle callDirect(String method, String arg, Bundle extras) throws Exception {
        for (int attempt = 0; ; attempt++) {
            if (provider == null) {
                Object am = activityManager();
                Method get = findMethod(am.getClass(), "getContentProviderExternal", 4);
                Object holder = get.invoke(am, AUTHORITY, 0, providerToken, "userswitchd");
                if (holder == null) throw new IllegalStateException("provider not found (is the app installed?)");
                provider = holder.getClass().getField("provider").get(holder);
            }
            try {
                Method call = findMethod(provider.getClass(), "call", 5);
                AttributionSource source = new AttributionSource.Builder(android.os.Process.myUid())
                        .setPackageName("com.android.shell").build();
                Bundle r = (Bundle) call.invoke(provider, source, AUTHORITY, method, arg, extras);
                return r != null ? r : new Bundle();
            } catch (InvocationTargetException e) {
                provider = null;
                if (!(e.getCause() instanceof DeadObjectException) || attempt > 0) throw e;
            }
        }
    }

    private static final Pattern RESULT_ENTRY = Pattern.compile("(target|recorded)=([A-Za-z0-9_-]+)");

    private static Bundle callViaContent(String method, String arg, Bundle extras) throws Exception {
        List<String> cmd = new ArrayList<>(List.of(
                "content", "call", "--user", "0", "--uri", "content://" + AUTHORITY, "--method", method));
        if (arg != null) {
            cmd.add("--arg");
            cmd.add(arg);
        }
        cmd.addAll(List.of("--extra", "users:s:" + extras.getString("users"),
                "--extra", "current:i:" + extras.getInt("current"),
                "--extra", "pid:i:" + extras.getInt("pid")));
        String out = sh(cmd.toArray(new String[0]));
        if (!out.contains("Result:")) throw new IOException("content: " + out.trim());
        Bundle b = new Bundle();
        Matcher m = RESULT_ENTRY.matcher(out);
        while (m.find()) b.putString(m.group(1), m.group(2));
        return b;
    }

    // ---- users -------------------------------------------------------------

    private final ExecutorService bg = Executors.newSingleThreadExecutor();
    private volatile List<int[]> userIds = new ArrayList<>();
    private volatile List<String> userNames = new ArrayList<>();

    private void refreshUsers() {
        try {
            List<int[]> ids = new ArrayList<>();
            List<String> names = new ArrayList<>();
            listUsers(ids, names);
            userNames = names;
            userIds = ids;
        } catch (Exception e) {
            log("listing users failed: " + e);
        }
    }

    private String usersJson() throws Exception {
        List<int[]> ids = userIds;
        List<String> names = userNames;
        JSONArray ja = new JSONArray();
        for (int i = 0; i < ids.size() && i < names.size(); i++) {
            ja.put(new JSONObject().put("id", ids.get(i)[0]).put("name", names.get(i)));
        }
        return ja.toString();
    }

    private void switchTo(String target) throws Exception {
        List<int[]> users = userIds;
        int current = currentUser();
        int dest;
        if (target.equals("next")) {
            if (users.isEmpty()) return;
            int idx = 0;
            for (int i = 0; i < users.size(); i++) if (users.get(i)[0] == current) idx = i;
            dest = users.get((idx + 1) % users.size())[0];
        } else {
            dest = Integer.parseInt(target);
            boolean known = false;
            for (int[] u : users) known |= u[0] == dest;
            if (!known) {
                log("unknown user " + dest);
                return;
            }
        }
        if (dest == current) return;
        long t0 = System.currentTimeMillis();
        try {
            Object am = activityManager();
            am.getClass().getMethod("switchUser", int.class).invoke(am, dest);
        } catch (Throwable t) {
            log("direct switchUser failed, using am: " + t);
            sh("am", "switch-user", Integer.toString(dest));
        }
        log("switch " + current + " -> " + dest + " (" + (System.currentTimeMillis() - t0) + " ms)");
    }

    private static final Pattern USER =
            Pattern.compile("UserInfo\\{(\\d+):(.*?):([0-9a-fA-F]+)\\}");

    /** Full, switchable users (no work profiles), in id order. */
    static void listUsers(List<int[]> ids, List<String> names) throws Exception {
        Matcher m = USER.matcher(sh("pm", "list", "users"));
        while (m.find()) {
            int flags = Integer.parseInt(m.group(3), 16);
            if ((flags & FLAG_FULL) == 0 || (flags & FLAG_PROFILE) != 0) continue;
            ids.add(new int[] {Integer.parseInt(m.group(1))});
            names.add(m.group(2));
        }
    }

    static int currentUser() throws Exception {
        try {
            return (Integer) ActivityManager.class.getMethod("getCurrentUser").invoke(null);
        } catch (ReflectiveOperationException e) {
            return Integer.parseInt(sh("am", "get-current-user").trim());
        }
    }

    // ---- framework access (hidden APIs, reachable from app_process) --------

    static Object activityManager() throws Exception {
        return ActivityManager.class.getMethod("getService").invoke(null);
    }

    static Method findMethod(Class<?> cls, String name, int params) throws NoSuchMethodException {
        for (Method m : cls.getMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == params) return m;
        }
        throw new NoSuchMethodException(cls.getName() + "." + name + "/" + params);
    }

    static String sh(String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        byte[] out = p.getInputStream().readAllBytes();
        if (!p.waitFor(15, TimeUnit.SECONDS)) p.destroyForcibly();
        return new String(out, StandardCharsets.UTF_8);
    }

    // ---- housekeeping ------------------------------------------------------

    /** Only one daemon at a time: kill the one recorded in the pid file. */
    static void killPrevious() {
        try (BufferedReader r = new BufferedReader(new FileReader(PID_FILE))) {
            int pid = Integer.parseInt(r.readLine().trim());
            if (pid == android.os.Process.myPid()) return;
            File cmdline = new File("/proc/" + pid + "/cmdline");
            if (!cmdline.exists()) return;
            String c = new String(java.nio.file.Files.readAllBytes(cmdline.toPath()), StandardCharsets.UTF_8);
            if (c.contains("userswitch")) {
                android.os.Process.sendSignal(pid, 9);
                log("killed previous daemon " + pid);
            }
        } catch (Exception ignored) {
            // no previous daemon
        }
    }

    static void log(String msg) {
        String ts = new SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT).format(new Date());
        System.out.println(ts + " " + msg);
        System.out.flush();
    }
}
