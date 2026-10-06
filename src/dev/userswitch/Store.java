package dev.userswitch;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** App state shared by the UI and the ConfigProvider (same process). */
public final class Store {
    public static final long RECORD_WINDOW_MS = 30_000;

    public interface Listener {
        void onChanged();
    }

    public static final class Sequence {
        public final String keys;
        /** "next" or a user id. */
        public final String target;

        Sequence(String keys, String target) {
            this.keys = keys;
            this.target = target;
        }
    }

    public static final class User {
        public final int id;
        public final String name;

        User(int id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    private static Store instance;

    private final SharedPreferences prefs;
    private Listener listener;
    private long recordingUntil;
    private String recorded;
    // daemon heartbeat, every 30 s: kept in memory so it does not write to disk each time
    private volatile long daemonSeenAt;
    private volatile int daemonPid = -1;

    private Store(Context ctx) {
        prefs = ctx.getSharedPreferences("config", Context.MODE_PRIVATE);
    }

    public static synchronized Store get(Context ctx) {
        if (instance == null) instance = new Store(ctx.getApplicationContext());
        return instance;
    }

    public synchronized void setListener(Listener l) {
        listener = l;
    }

    private void changed() {
        Listener l;
        synchronized (this) {
            l = listener;
        }
        if (l != null) l.onChanged();
    }

    // ---- sequences ---------------------------------------------------------

    /** Parsed once, then kept: the UI asks every second and every burst is matched against it. */
    private List<Sequence> sequencesCache;
    private List<User> usersCache;

    /** A fresh copy the caller may modify. */
    public synchronized List<Sequence> sequences() {
        if (sequencesCache == null) {
            List<Sequence> out = new ArrayList<>();
            try {
                JSONArray a = new JSONArray(prefs.getString("sequences", "[]"));
                for (int i = 0; i < a.length(); i++) {
                    JSONObject o = a.getJSONObject(i);
                    out.add(new Sequence(o.getString("keys"), o.getString("target")));
                }
            } catch (JSONException ignored) {
                // corrupt config: start empty
            }
            sequencesCache = out;
        }
        return new ArrayList<>(sequencesCache);
    }

    private void saveSequences(List<Sequence> list) {
        JSONArray a = new JSONArray();
        try {
            for (Sequence s : list) a.put(new JSONObject().put("keys", s.keys).put("target", s.target));
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
        prefs.edit().putString("sequences", a.toString()).apply();
        sequencesCache = new ArrayList<>(list);
    }

    /** Adds or replaces the sequence with the same keys. */
    public void putSequence(String keys, String target) {
        synchronized (this) {
            List<Sequence> list = sequences();
            list.removeIf(s -> s.keys.equals(keys));
            list.add(new Sequence(keys, target));
            saveSequences(list);
        }
        changed();
    }

    public void removeSequence(String keys) {
        synchronized (this) {
            List<Sequence> list = sequences();
            list.removeIf(s -> s.keys.equals(keys));
            saveSequences(list);
        }
        changed();
    }

    public synchronized String match(String keys) {
        for (Sequence s : sequences()) if (s.keys.equals(keys)) return s.target;
        return null;
    }

    // ---- recording ---------------------------------------------------------

    public void startRecording() {
        synchronized (this) {
            recordingUntil = System.currentTimeMillis() + RECORD_WINDOW_MS;
            recorded = null;
        }
        changed();
    }

    public void cancelRecording() {
        synchronized (this) {
            recordingUntil = 0;
        }
        changed();
    }

    public synchronized boolean isRecording() {
        return System.currentTimeMillis() < recordingUntil;
    }

    /** Returns true when the burst was consumed by a recording. */
    public boolean offerRecording(String keys) {
        synchronized (this) {
            if (!isRecording()) return false;
            recordingUntil = 0;
            recorded = keys;
        }
        changed();
        return true;
    }

    /** The last recorded burst, cleared once read. */
    public synchronized String takeRecorded() {
        String r = recorded;
        recorded = null;
        return r;
    }

    // ---- daemon info -------------------------------------------------------

    public void daemonSeen(int pid, String usersJson) {
        daemonSeenAt = System.currentTimeMillis();
        daemonPid = pid;
        synchronized (this) {
            if (usersJson != null && !usersJson.equals(prefs.getString("users", null))) {
                prefs.edit().putString("users", usersJson).apply();
                usersCache = null;
            }
        }
        changed();
    }

    public long daemonSeenAt() {
        return daemonSeenAt;
    }

    public int daemonPid() {
        return daemonPid;
    }

    public synchronized List<User> users() {
        if (usersCache == null) {
            List<User> out = new ArrayList<>();
            try {
                JSONArray a = new JSONArray(prefs.getString("users", "[]"));
                for (int i = 0; i < a.length(); i++) {
                    JSONObject o = a.getJSONObject(i);
                    out.add(new User(o.getInt("id"), o.getString("name")));
                }
            } catch (JSONException ignored) {
                // nothing reported yet
            }
            usersCache = out;
        }
        return new ArrayList<>(usersCache);
    }

    // ---- display -----------------------------------------------------------

    public static String pretty(String keys) {
        StringBuilder sb = new StringBuilder();
        for (String tok : keys.split(" ")) {
            if (sb.length() > 0) sb.append("  ·  ");
            boolean isLong = tok.endsWith(":long");
            String base = isLong ? tok.substring(0, tok.length() - 5) : tok;
            sb.append(base.replace("UP", "Vol+").replace("DOWN", "Vol−")
                    .replace("POWER", "Power").replace("+Vol", " & Vol").replace("+Power", " & Power"));
            if (isLong) sb.append(" (long)");
        }
        return sb.toString();
    }

    public static String targetLabel(String target, List<User> users) {
        if ("next".equals(target)) return "Profil suivant";
        for (User u : users) if (Integer.toString(u.id).equals(target)) return u.name + " (" + u.id + ")";
        return "Profil " + target;
    }
}
