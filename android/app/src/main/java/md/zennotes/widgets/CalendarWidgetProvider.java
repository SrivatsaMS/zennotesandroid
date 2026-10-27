package md.zennotes.widgets;

import android.app.AlarmManager;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.RemoteViews;

import java.util.Calendar;
import java.util.Collections;
import java.util.List;

import md.zennotes.R;

/**
 * The Calendar widget: app-core's Calendar sheet on the Home Screen (Dave,
 * Discord 2026-10-27). The month grid with its writing dots, open-task marks
 * and week numbers on top; the selected day's week below, every task with a
 * box to tick. Taps go to CalendarWidgetActionReceiver (not exported, since a
 * tick writes into a note); this only draws.
 *
 * Like the panel on a touch screen: a tap selects a day and shows its week,
 * a tap on the selected day opens its daily note. A widget cannot hold a
 * text field, so "Add a task for …" opens the Calendar in the app on that
 * day with the cursor in its field.
 */
public class CalendarWidgetProvider extends AppWidgetProvider {
    /** Below this height the grid gives way to the week list alone. */
    private static final int COMPACT_HEIGHT_DP = 300;
    /** Below this width the week-number column goes. */
    private static final int NARROW_WIDTH_DP = 250;

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] appWidgetIds) {
        WidgetSnapshot snapshot = WidgetSnapshot.load(context);
        for (int id : appWidgetIds) update(context, manager, id, snapshot, false);
        scheduleMidnight(context);
    }

    @Override
    public void onAppWidgetOptionsChanged(Context context, AppWidgetManager manager, int appWidgetId, Bundle options) {
        update(context, manager, appWidgetId, WidgetSnapshot.load(context), false);
    }

    /**
     * Send one widget its whole state. From Android 12 the week list travels
     * inside the same update as collection items: notifyAppWidgetViewDataChanged
     * re-sends whatever views the widget service holds, and right after an
     * update those can still be the previous ones, which put an old grid (and,
     * after a force stop, dead tap targets) back on the Home Screen.
     */
    static void update(Context context, AppWidgetManager manager, int widgetId, WidgetSnapshot snapshot,
                       boolean scrollToSelected) {
        manager.updateAppWidget(widgetId,
                build(context, snapshot, widgetId, manager.getAppWidgetOptions(widgetId), scrollToSelected));
        if (!collectionItems()) manager.notifyAppWidgetViewDataChanged(new int[] {widgetId}, R.id.zn_cal_agenda);
    }

    static boolean collectionItems() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S;
    }

    @Override
    public void onDeleted(Context context, int[] appWidgetIds) {
        for (int id : appWidgetIds) CalendarWidgetState.forget(context, id);
    }

    static int[] ids(Context context) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        if (manager == null) return new int[0];
        int[] ids = manager.getAppWidgetIds(new ComponentName(context, CalendarWidgetProvider.class));
        return ids == null ? new int[0] : ids;
    }

    /** Redraw every placed Calendar widget from the current snapshot. */
    static void refreshAll(Context context) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        if (manager == null) return;
        int[] ids = ids(context);
        if (ids.length == 0) return;
        WidgetSnapshot snapshot = WidgetSnapshot.load(context);
        for (int id : ids) update(context, manager, id, snapshot, false);
        scheduleMidnight(context);
    }

    /** Today's ring moves at midnight whether or not the app publishes. */
    static void scheduleMidnight(Context context) {
        AlarmManager alarms = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarms == null) return;
        Calendar next = Calendar.getInstance();
        next.add(Calendar.DAY_OF_MONTH, 1);
        next.set(Calendar.HOUR_OF_DAY, 0);
        next.set(Calendar.MINUTE, 0);
        next.set(Calendar.SECOND, 5);
        alarms.set(AlarmManager.RTC, next.getTimeInMillis(),
                CalendarWidgetActionReceiver.broadcast(context, CalendarWidgetActionReceiver.ACTION_MIDNIGHT, 0, null));
    }

    static RemoteViews build(Context context, WidgetSnapshot snapshot, int widgetId, Bundle options,
                             boolean scrollToSelected) {
        WidgetSnapshot.Palette p = snapshot != null ? snapshot.palette : WidgetSnapshot.Palette.fallback();
        CalendarWidgetData cal = snapshot != null ? snapshot.calendar : null;
        CalendarWidgetState state = CalendarWidgetState.load(context, widgetId);
        String today = CalendarMath.today();
        int weekStart = cal != null ? cal.weekStart : 1;
        boolean compact = options != null
                && options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0) > 0
                && options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0) < COMPACT_HEIGHT_DP;
        boolean narrow = options != null
                && options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0) > 0
                && options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0) < NARROW_WIDTH_DP;
        boolean daily = cal != null && cal.dailyEnabled;

        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_calendar);
        views.setInt(R.id.zn_widget_bg, "setColorFilter", p.bg);

        // Header: ‹ month › (the label returns to the current month, as in the panel).
        views.setTextViewText(R.id.zn_cal_month, CalendarMath.monthLabel(state.month));
        views.setTextColor(R.id.zn_cal_month, p.fg);
        views.setInt(R.id.zn_cal_prev, "setColorFilter", p.muted);
        views.setInt(R.id.zn_cal_next, "setColorFilter", p.muted);
        views.setOnClickPendingIntent(R.id.zn_cal_prev,
                CalendarWidgetActionReceiver.broadcast(context, CalendarWidgetActionReceiver.ACTION_MONTH, widgetId, "-1"));
        views.setOnClickPendingIntent(R.id.zn_cal_next,
                CalendarWidgetActionReceiver.broadcast(context, CalendarWidgetActionReceiver.ACTION_MONTH, widgetId, "1"));
        views.setOnClickPendingIntent(R.id.zn_cal_month,
                CalendarWidgetActionReceiver.broadcast(context, CalendarWidgetActionReceiver.ACTION_MONTH, widgetId, "0"));
        views.setViewVisibility(R.id.zn_cal_header, compact ? View.GONE : View.VISIBLE);
        views.setViewVisibility(R.id.zn_cal_divider, compact ? View.GONE : View.VISIBLE);
        views.setInt(R.id.zn_cal_divider, "setColorFilter", p.bg2);

        views.removeAllViews(R.id.zn_cal_grid);
        views.setViewVisibility(R.id.zn_cal_grid, compact ? View.GONE : View.VISIBLE);
        if (!compact) addGrid(context, views, p, cal, state, today, weekStart, widgetId,
                cal != null && cal.showWeekNumbers && !narrow);

        // The week bar: "Week of …", Day → / Day +, Week → when weekly notes are on.
        List<String> week = CalendarMath.weekOf(state.selected, weekStart);
        views.setViewVisibility(R.id.zn_cal_week_bar, daily ? View.VISIBLE : View.GONE);
        views.setViewVisibility(R.id.zn_cal_add, daily ? View.VISIBLE : View.GONE);
        if (daily) {
            views.setTextViewText(R.id.zn_cal_week_label,
                    context.getString(R.string.widget_calendar_week_of, CalendarMath.weekRange(week)));
            views.setTextColor(R.id.zn_cal_week_label, p.fg);
            boolean hasDay = cal.days.containsKey(state.selected);
            views.setTextViewText(R.id.zn_cal_day_link, context.getString(
                    hasDay ? R.string.widget_calendar_day_open : R.string.widget_calendar_day_create));
            views.setTextColor(R.id.zn_cal_day_link, p.muted);
            views.setOnClickPendingIntent(R.id.zn_cal_day_link,
                    WidgetLinks.activity(context, WidgetLinks.day(state.selected), 0));
            views.setViewVisibility(R.id.zn_cal_week_link, cal.weeklyEnabled ? View.VISIBLE : View.GONE);
            if (cal.weeklyEnabled) {
                boolean hasWeek = cal.weeks.containsKey(CalendarMath.isoWeekKey(state.selected));
                views.setTextViewText(R.id.zn_cal_week_link, context.getString(
                        hasWeek ? R.string.widget_calendar_week_open : R.string.widget_calendar_week_create));
                views.setTextColor(R.id.zn_cal_week_link, p.muted);
                views.setOnClickPendingIntent(R.id.zn_cal_week_link,
                        WidgetLinks.activity(context, WidgetLinks.week(state.selected), 0));
            }
            views.setInt(R.id.zn_cal_add_bg, "setColorFilter", WidgetSnapshot.Palette.over(p.bg2, 0.6f, p.bg));
            views.setTextViewText(R.id.zn_cal_add_text,
                    context.getString(R.string.widget_calendar_add_task, CalendarMath.shortDay(state.selected)));
            views.setTextColor(R.id.zn_cal_add_text, p.muted);
            views.setOnClickPendingIntent(R.id.zn_cal_add,
                    WidgetLinks.activity(context, WidgetLinks.calendar(state.selected, true), 0));
        }

        // The week list, every row's tap through one broadcast template (a
        // collection allows nothing else).
        if (collectionItems()) {
            List<CalendarAgenda.Row> rows = cal == null ? Collections.<CalendarAgenda.Row>emptyList()
                    : CalendarAgenda.rows(cal, state.selected, WidgetActions.marks(context, cal.vaultKey));
            RemoteViews.RemoteCollectionItems.Builder items = new RemoteViews.RemoteCollectionItems.Builder()
                    .setViewTypeCount(CalendarAgenda.VIEW_TYPES)
                    .setHasStableIds(false);
            for (int i = 0; i < rows.size(); i++) {
                items.addItem(i, CalendarAgenda.view(context, p, rows.get(i), state.selected, today));
            }
            views.setRemoteAdapter(R.id.zn_cal_agenda, items.build());
            if (scrollToSelected) {
                views.setScrollPosition(R.id.zn_cal_agenda, CalendarAgenda.positionOf(rows, state.selected));
            }
        } else {
            Intent adapter = new Intent(context, CalendarAgendaService.class);
            adapter.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId);
            adapter.setData(Uri.parse(adapter.toUri(Intent.URI_INTENT_SCHEME)));
            views.setRemoteAdapter(R.id.zn_cal_agenda, adapter);
        }
        views.setEmptyView(R.id.zn_cal_agenda, R.id.zn_cal_empty);
        views.setPendingIntentTemplate(R.id.zn_cal_agenda, CalendarWidgetActionReceiver.listTemplate(context, widgetId));
        if (cal == null) {
            views.setTextViewText(R.id.zn_cal_empty_title, context.getString(R.string.widget_empty_open_app));
            views.setTextViewText(R.id.zn_cal_empty_detail, context.getString(R.string.widget_calendar_empty_detail));
        } else {
            views.setTextViewText(R.id.zn_cal_empty_title, context.getString(R.string.widget_calendar_daily_off));
            views.setTextViewText(R.id.zn_cal_empty_detail, context.getString(R.string.widget_calendar_daily_off_detail));
        }
        views.setTextColor(R.id.zn_cal_empty_title, p.fg);
        views.setTextColor(R.id.zn_cal_empty_detail, p.muted);
        views.setOnClickPendingIntent(R.id.zn_cal_empty,
                WidgetLinks.activity(context, WidgetLinks.calendar(null, false), 0));
        return views;
    }

    private static void addGrid(Context context, RemoteViews views, WidgetSnapshot.Palette p, CalendarWidgetData cal,
                                CalendarWidgetState state, String today, int weekStart, int widgetId,
                                boolean weekNumbers) {
        String pkg = context.getPackageName();
        RemoteViews labels = new RemoteViews(pkg, R.layout.widget_cal_row);
        if (weekNumbers) {
            RemoteViews w = new RemoteViews(pkg, R.layout.widget_cal_week_label);
            w.setTextColor(R.id.zn_cal_label, p.muted);
            labels.addView(R.id.zn_cal_row, w);
        }
        for (int i = 0; i < 7; i++) {
            RemoteViews label = new RemoteViews(pkg, R.layout.widget_cal_label);
            label.setTextViewText(R.id.zn_cal_label, CalendarMath.dayLabel(weekStart + i));
            label.setTextColor(R.id.zn_cal_label, p.muted);
            labels.addView(R.id.zn_cal_row, label);
        }
        views.addView(R.id.zn_cal_grid, labels);

        List<String> days = CalendarMath.grid(state.month, weekStart);
        // The panel's faint ink for days outside the month and week numbers:
        // the theme's muted color is nearly as bright as its text.
        int faint = WidgetSnapshot.Palette.over(p.muted, 0.6f, p.bg);
        boolean daily = cal != null && cal.dailyEnabled;
        for (int r = 0; r < 6; r++) {
            List<String> row = days.subList(r * 7, r * 7 + 7);
            RemoteViews rowViews = new RemoteViews(pkg, R.layout.widget_cal_row);
            if (weekNumbers) {
                String monday = CalendarMath.rowMonday(row);
                RemoteViews cell = new RemoteViews(pkg, R.layout.widget_cal_week);
                CalendarWidgetData.Mark mark = cal != null && cal.weeklyEnabled
                        ? cal.weeks.get(CalendarMath.isoWeekKey(monday)) : null;
                paintCell(cell, p, String.valueOf(CalendarMath.isoWeek(monday)), faint, false, false, mark);
                if (cal != null && cal.weeklyEnabled) {
                    cell.setOnClickPendingIntent(R.id.zn_cal_cell, WidgetLinks.activity(context, WidgetLinks.week(monday), 0));
                }
                rowViews.addView(R.id.zn_cal_row, cell);
            }
            for (String iso : row) {
                boolean inMonth = CalendarMath.monthOf(iso).equals(state.month);
                boolean selected = daily && iso.equals(state.selected);
                boolean isToday = iso.equals(today);
                int color = selected ? p.fg : isToday ? p.accent : inMonth ? p.fg : faint;
                RemoteViews cell = new RemoteViews(pkg, R.layout.widget_cal_day);
                paintCell(cell, p, String.valueOf(Integer.parseInt(iso.substring(8))), color, selected, isToday,
                        cal != null ? cal.days.get(iso) : null);
                if (cal == null) {
                    cell.setOnClickPendingIntent(R.id.zn_cal_cell,
                            WidgetLinks.activity(context, WidgetLinks.calendar(null, false), 0));
                } else if (daily) {
                    cell.setOnClickPendingIntent(R.id.zn_cal_cell, selected
                            ? WidgetLinks.activity(context, WidgetLinks.day(iso), 0)
                            : CalendarWidgetActionReceiver.broadcast(context,
                                    CalendarWidgetActionReceiver.ACTION_SELECT, widgetId, iso));
                }
                rowViews.addView(R.id.zn_cal_row, cell);
            }
            views.addView(R.id.zn_cal_grid, rowViews);
        }
    }

    /** One cell, as CalendarPanel draws it: selected = fill + ring, today =
     *  ring, dots for the note's words, the corner mark for its open tasks. */
    private static void paintCell(RemoteViews cell, WidgetSnapshot.Palette p, String text, int color,
                                  boolean selected, boolean today, CalendarWidgetData.Mark mark) {
        cell.setTextViewText(R.id.zn_cal_cell_num, text);
        cell.setTextColor(R.id.zn_cal_cell_num, color);
        cell.setViewVisibility(R.id.zn_cal_cell_fill, selected ? View.VISIBLE : View.GONE);
        cell.setInt(R.id.zn_cal_cell_fill, "setColorFilter", p.bg2);
        cell.setViewVisibility(R.id.zn_cal_cell_ring, selected || today ? View.VISIBLE : View.GONE);
        cell.setInt(R.id.zn_cal_cell_ring, "setColorFilter",
                WidgetSnapshot.Palette.over(p.accent, selected ? 0.6f : 0.5f, selected ? p.bg2 : p.bg));
        if (mark != null) {
            StringBuilder dots = new StringBuilder();
            for (int i = 0; i < Math.max(1, Math.min(3, mark.dots)); i++) dots.append('•');
            cell.setTextViewText(R.id.zn_cal_cell_dots, dots.toString());
            cell.setTextColor(R.id.zn_cal_cell_dots, WidgetSnapshot.Palette.withAlpha(p.muted, mark.faint ? 0.4f : 1f));
        } else {
            cell.setTextViewText(R.id.zn_cal_cell_dots, "");
        }
        boolean marked = mark != null && mark.openTasks > 0;
        cell.setViewVisibility(R.id.zn_cal_cell_mark, marked ? View.VISIBLE : View.GONE);
        cell.setInt(R.id.zn_cal_cell_mark, "setColorFilter", p.accent);
    }
}
