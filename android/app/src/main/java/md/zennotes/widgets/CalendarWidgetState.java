package md.zennotes.widgets;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * What one placed Calendar widget shows: the month in its grid and the
 * selected day (whose week the agenda lists), like the panel's `anchor`
 * and `selectedIso`. A new day starts over on today, the way the panel
 * orients on today when it opens.
 */
final class CalendarWidgetState {
    private static final String PREFS = "zn_calendar_widget";

    /** `YYYY-MM`. */
    final String month;
    /** `YYYY-MM-DD`. */
    final String selected;

    private CalendarWidgetState(String month, String selected) {
        this.month = month;
        this.selected = selected;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static CalendarWidgetState load(Context context, int widgetId) {
        String today = CalendarMath.today();
        SharedPreferences p = prefs(context);
        if (!today.equals(p.getString("on_" + widgetId, ""))) {
            return new CalendarWidgetState(CalendarMath.monthOf(today), today);
        }
        String month = p.getString("month_" + widgetId, CalendarMath.monthOf(today));
        String selected = p.getString("sel_" + widgetId, today);
        return new CalendarWidgetState(month, selected);
    }

    static void save(Context context, int widgetId, String month, String selected) {
        prefs(context).edit()
                .putString("on_" + widgetId, CalendarMath.today())
                .putString("month_" + widgetId, month)
                .putString("sel_" + widgetId, selected)
                .apply();
    }

    static void forget(Context context, int widgetId) {
        prefs(context).edit()
                .remove("on_" + widgetId)
                .remove("month_" + widgetId)
                .remove("sel_" + widgetId)
                .apply();
    }
}
