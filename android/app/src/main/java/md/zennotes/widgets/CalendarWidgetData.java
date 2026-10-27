package md.zennotes.widgets;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * The snapshot's `calendar` block (src/bridge/widget-calendar.ts, built by
 * app-core's getCalendarWidgetData): the Calendar sheet's grid marks and
 * week agenda for the months around the day it was published. Read with a
 * default for every field, like WidgetSnapshot.
 */
public final class CalendarWidgetData {
    public final String todayIso;
    /** 0 = Sunday … 6 = Saturday. */
    public final int weekStart;
    public final boolean showWeekNumbers;
    public final boolean dailyEnabled;
    public final boolean weeklyEnabled;
    /** Inclusive range the marks and agenda cover. */
    public final String from;
    public final String to;
    public final Map<String, Mark> days;
    public final Map<String, Mark> weeks;
    public final Map<String, Day> agenda;
    public final String vaultKey;
    /** Nullable: a remote workspace has no notes on this device. */
    public final String vaultRoot;
    public final long actionsAck;

    private CalendarWidgetData(String todayIso, int weekStart, boolean showWeekNumbers, boolean dailyEnabled,
                               boolean weeklyEnabled, String from, String to, Map<String, Mark> days,
                               Map<String, Mark> weeks, Map<String, Day> agenda, String vaultKey,
                               String vaultRoot, long actionsAck) {
        this.todayIso = todayIso;
        this.weekStart = weekStart;
        this.showWeekNumbers = showWeekNumbers;
        this.dailyEnabled = dailyEnabled;
        this.weeklyEnabled = weeklyEnabled;
        this.from = from;
        this.to = to;
        this.days = Collections.unmodifiableMap(days);
        this.weeks = Collections.unmodifiableMap(weeks);
        this.agenda = Collections.unmodifiableMap(agenda);
        this.vaultKey = vaultKey;
        this.vaultRoot = vaultRoot;
        this.actionsAck = actionsAck;
    }

    /** A day's (or week's) note: word-count dots and its open tasks. */
    public static final class Mark {
        public final int dots;
        public final boolean faint;
        public final int openTasks;

        Mark(int dots, boolean faint, int openTasks) {
            this.dots = dots;
            this.faint = faint;
            this.openTasks = openTasks;
        }
    }

    public static final class Day {
        public final int total;
        public final List<Task> tasks;

        Day(int total, List<Task> tasks) {
            this.total = total;
            this.tasks = Collections.unmodifiableList(tasks);
        }
    }

    public static final class Task {
        public final String id;
        public final String path;
        public final String noteTitle;
        public final String content;
        public final boolean inProgress;
        public final boolean inDailyNote;
        public final int line;
        public final String raw;
        /** Nullable: only the app can tick it (a file task). */
        public final String toggled;

        Task(String id, String path, String noteTitle, String content, boolean inProgress,
             boolean inDailyNote, int line, String raw, String toggled) {
            this.id = id;
            this.path = path;
            this.noteTitle = noteTitle;
            this.content = content;
            this.inProgress = inProgress;
            this.inDailyNote = inDailyNote;
            this.line = line;
            this.raw = raw;
            this.toggled = toggled;
        }
    }

    public boolean covers(String iso) {
        return iso.compareTo(from) >= 0 && iso.compareTo(to) <= 0;
    }

    static CalendarWidgetData parse(JSONObject c) {
        if (c == null) return null;
        String from = c.optString("from", "");
        String to = c.optString("to", "");
        if (from.length() != 10 || to.length() != 10) return null;
        Map<String, Day> agenda = new HashMap<>();
        JSONObject agendaJson = c.optJSONObject("agenda");
        if (agendaJson != null) {
            for (Iterator<String> it = agendaJson.keys(); it.hasNext(); ) {
                String iso = it.next();
                JSONObject d = agendaJson.optJSONObject(iso);
                if (d == null) continue;
                List<Task> tasks = new ArrayList<>();
                JSONArray list = d.optJSONArray("tasks");
                if (list != null) {
                    for (int i = 0; i < list.length(); i++) {
                        JSONObject t = list.optJSONObject(i);
                        if (t == null) continue;
                        String id = t.optString("id", "");
                        String path = t.optString("path", "");
                        if (id.isEmpty() || path.isEmpty()) continue;
                        tasks.add(new Task(id, path, t.optString("noteTitle", ""), t.optString("content", ""),
                                t.optBoolean("inProgress", false), t.optBoolean("inDailyNote", false),
                                t.optInt("line", -1), t.optString("raw", ""),
                                t.isNull("toggled") ? null : t.optString("toggled", null)));
                    }
                }
                agenda.put(iso, new Day(d.optInt("total", tasks.size()), tasks));
            }
        }
        return new CalendarWidgetData(
                c.optString("todayIso", ""),
                c.optInt("weekStart", 1),
                c.optBoolean("showWeekNumbers", false),
                c.optBoolean("dailyEnabled", false),
                c.optBoolean("weeklyEnabled", false),
                from, to,
                marks(c.optJSONObject("days")),
                marks(c.optJSONObject("weeks")),
                agenda,
                c.optString("vaultKey", ""),
                c.isNull("vaultRoot") ? null : c.optString("vaultRoot", null),
                (long) c.optDouble("actionsAck", 0));
    }

    private static Map<String, Mark> marks(JSONObject json) {
        Map<String, Mark> out = new HashMap<>();
        if (json == null) return out;
        for (Iterator<String> it = json.keys(); it.hasNext(); ) {
            String key = it.next();
            JSONObject m = json.optJSONObject(key);
            if (m == null) continue;
            out.put(key, new Mark(m.optInt("dots", 1), m.optBoolean("faint", true), m.optInt("openTasks", 0)));
        }
        return out;
    }
}
