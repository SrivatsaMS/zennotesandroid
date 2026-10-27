package md.zennotes.widgets;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

/**
 * The Calendar widget's checkbox taps (src/bridge/widget-calendar.ts
 * `WidgetAction`), logged until the app has applied them. The log is also
 * what draws a tapped row ticked: a tap must show at once, and keep showing
 * until a snapshot built after the app saw it arrives, or a publish that
 * raced the tap would put the box back. A snapshot's `actionsAck` (the
 * newest tap the app applied) is what prunes it.
 *
 * The widget's receiver and WidgetBridgePlugin run in the app's one
 * process, so a lock around SharedPreferences is all the coordination the
 * log needs.
 */
public final class WidgetActions {
    private WidgetActions() {}

    private static final String PREFS = "zn_widget_actions";
    private static final String KEY = "log";
    /** The newest `at` ever handed out. Kept beside the log because the log
     *  can be pruned empty: a clock set back after that would otherwise give
     *  a new tap an `at` the app has already acked, and it would be pruned
     *  unapplied. */
    private static final String LAST_AT = "lastAt";
    /** A tap the app never got to (uninstalled vault, a long absence) must
     *  not grow the log forever. */
    private static final int MAX_ACTIONS = 200;

    /** The latest tap for one task, as the widget draws it. */
    static final class Mark {
        final boolean checked;
        final boolean written;

        Mark(boolean checked, boolean written) {
            this.checked = checked;
            this.written = written;
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static JSONArray read(Context context) {
        try {
            return new JSONArray(prefs(context).getString(KEY, "[]"));
        } catch (JSONException e) {
            return new JSONArray();
        }
    }

    private static void save(Context context, JSONArray log) {
        prefs(context).edit().putString(KEY, log.toString()).commit();
    }

    /** Append a tap; returns its `at`, above every earlier one. */
    static synchronized long append(Context context, JSONObject action) throws JSONException {
        JSONArray log = read(context);
        long at = System.currentTimeMillis();
        long floor = prefs(context).getLong(LAST_AT, 0);
        if (log.length() > 0) {
            floor = Math.max(floor, (long) log.getJSONObject(log.length() - 1).optDouble("at", 0));
        }
        if (at <= floor) at = floor + 1;
        action.put("at", at);
        log.put(action);
        while (log.length() > MAX_ACTIONS) log.remove(0);
        prefs(context).edit().putString(KEY, log.toString()).putLong(LAST_AT, at).commit();
        return at;
    }

    static synchronized void markWritten(Context context, long at) {
        JSONArray log = read(context);
        for (int i = 0; i < log.length(); i++) {
            JSONObject a = log.optJSONObject(i);
            if (a != null && (long) a.optDouble("at", 0) == at) {
                try {
                    a.put("written", true);
                } catch (JSONException ignored) {
                    return;
                }
                save(context, log);
                return;
            }
        }
    }

    /** The log as JSON, for WidgetBridgePlugin.readActions. */
    public static synchronized String json(Context context) {
        return read(context).toString();
    }

    /** Drop every tap the app has applied (`at <= ack`). */
    public static synchronized void prune(Context context, long ack) {
        if (ack <= 0) return;
        JSONArray log = read(context);
        JSONArray kept = new JSONArray();
        for (int i = 0; i < log.length(); i++) {
            JSONObject a = log.optJSONObject(i);
            if (a != null && (long) a.optDouble("at", 0) > ack) kept.put(a);
        }
        if (kept.length() != log.length()) save(context, kept);
    }

    /** Task id -> its latest tap in `vaultKey`, oldest to newest so the last wins. */
    static synchronized Map<String, Mark> marks(Context context, String vaultKey) {
        Map<String, Mark> out = new HashMap<>();
        JSONArray log = read(context);
        for (int i = 0; i < log.length(); i++) {
            JSONObject a = log.optJSONObject(i);
            if (a == null || !vaultKey.equals(a.optString("vaultKey", ""))) continue;
            out.put(a.optString("id", ""), new Mark(a.optBoolean("checked", false), a.optBoolean("written", false)));
        }
        return out;
    }
}
