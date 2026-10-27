package md.zennotes.widgets;

import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Paint;
import android.os.Build;
import android.view.View;
import android.widget.RemoteViews;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import md.zennotes.R;

/**
 * The rows of the Calendar widget's week list, the panel's agenda: each day
 * of the selected day's week as a header ("27 TUE", Today, a count), then
 * that day's open tasks. Built in one place for both ways the list is fed
 * (CalendarWidgetProvider's collection items on Android 12+, the
 * RemoteViewsService before), so they agree on rows and positions.
 */
final class CalendarAgenda {
    private CalendarAgenda() {}

    static final int HEADER = 0;
    static final int TASK = 1;
    static final int MESSAGE = 2;
    /** "+N more" under a busy day (the message layout). */
    static final int MORE = 3;
    /** Distinct layouts: MORE shares MESSAGE's. */
    static final int VIEW_TYPES = 3;
    /** Rows per day. The whole week travels in one widget update, which has
     *  a size limit; past this a day ends in "+N more", opening the app. */
    static final int MAX_TASKS_PER_DAY = 12;

    static final class Row {
        final int type;
        final String iso;
        final CalendarWidgetData.Task task;
        /** Header: open tasks that day (including any past the snapshot's cap). */
        final int count;
        /** Task: the latest widget tap on it, or null. */
        final WidgetActions.Mark mark;

        Row(int type, String iso, CalendarWidgetData.Task task, int count, WidgetActions.Mark mark) {
            this.type = type;
            this.iso = iso;
            this.task = task;
            this.count = count;
            this.mark = mark;
        }
    }

    static List<Row> rows(CalendarWidgetData data, String selected, Map<String, WidgetActions.Mark> marks) {
        if (data == null || !data.dailyEnabled) return Collections.emptyList();
        List<Row> rows = new ArrayList<>();
        List<String> week = CalendarMath.weekOf(selected, data.weekStart);
        if (!data.covers(week.get(0)) || !data.covers(week.get(6))) {
            rows.add(new Row(MESSAGE, selected, null, 0, null));
            return rows;
        }
        for (String iso : week) {
            CalendarWidgetData.Day day = data.agenda.get(iso);
            rows.add(new Row(HEADER, iso, null, day == null ? 0 : day.total, null));
            if (day == null) continue;
            int shown = Math.min(day.tasks.size(), MAX_TASKS_PER_DAY);
            for (int i = 0; i < shown; i++) {
                CalendarWidgetData.Task task = day.tasks.get(i);
                rows.add(new Row(TASK, iso, task, 0, marks.get(task.id)));
            }
            if (day.total > shown) rows.add(new Row(MORE, iso, null, day.total - shown, null));
        }
        return rows;
    }

    /** Position of the selected day's header, to scroll the list to. */
    static int positionOf(List<Row> rows, String iso) {
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).type == HEADER && rows.get(i).iso.equals(iso)) return i;
        }
        return 0;
    }

    static RemoteViews view(Context context, WidgetSnapshot.Palette p, Row row, String selected, String today) {
        switch (row.type) {
            case HEADER:
                return header(context, p, row, selected, today);
            case TASK:
                return task(context, p, row);
            case MORE:
                return more(context, p, row);
            default:
                return message(context, p, row);
        }
    }

    private static RemoteViews header(Context context, WidgetSnapshot.Palette p, Row row, String selected, String today) {
        RemoteViews v = new RemoteViews(context.getPackageName(), R.layout.widget_cal_agenda_day);
        v.setTextViewText(R.id.zn_agenda_day_label, CalendarMath.shortDay(row.iso).toUpperCase());
        v.setTextColor(R.id.zn_agenda_day_label, row.iso.equals(selected) ? p.fg : p.muted);
        v.setViewVisibility(R.id.zn_agenda_today, row.iso.equals(today) ? View.VISIBLE : View.GONE);
        v.setInt(R.id.zn_agenda_today_bg, "setColorFilter", WidgetSnapshot.Palette.over(p.accent, 0.15f, p.bg));
        v.setTextColor(R.id.zn_agenda_today_text, p.accent);
        v.setTextViewText(R.id.zn_agenda_day_count, row.count > 0 ? String.valueOf(row.count) : "");
        v.setTextColor(R.id.zn_agenda_day_count, p.muted);
        v.setOnClickFillInIntent(R.id.zn_agenda_day, CalendarWidgetActionReceiver.selectFillIn(row.iso));
        return v;
    }

    private static RemoteViews task(Context context, WidgetSnapshot.Palette p, Row row) {
        CalendarWidgetData.Task task = row.task;
        RemoteViews v = new RemoteViews(context.getPackageName(), R.layout.widget_cal_agenda_task);
        boolean checked = row.mark != null && row.mark.checked;
        // A tick the app has yet to apply (it could not be written here)
        // shows in the muted color until it lands.
        int boxColor = checked && row.mark.written ? p.accent : p.muted;
        String content = task.content.trim();
        v.setTextViewText(R.id.zn_task_title, content.isEmpty() ? "Untitled task" : content);
        v.setTextColor(R.id.zn_task_title, checked ? p.muted : p.fg);
        v.setInt(R.id.zn_task_title, "setPaintFlags",
                Paint.ANTI_ALIAS_FLAG | (checked ? Paint.STRIKE_THRU_TEXT_FLAG : 0));
        v.setViewVisibility(R.id.zn_task_note, task.inDailyNote ? View.GONE : View.VISIBLE);
        v.setTextViewText(R.id.zn_task_note, task.noteTitle);
        v.setTextColor(R.id.zn_task_note, p.muted);
        v.setContentDescription(R.id.zn_task_check, context.getString(
                checked ? R.string.widget_calendar_mark_not_done : R.string.widget_calendar_mark_done));
        Intent tick = CalendarWidgetActionReceiver.toggleFillIn(task, !checked);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            v.setCompoundButtonChecked(R.id.zn_task_check, checked);
            v.setColorStateList(R.id.zn_task_check, "setButtonTintList", ColorStateList.valueOf(boxColor));
            v.setOnCheckedChangeResponse(R.id.zn_task_check, RemoteViews.RemoteResponse.fromFillInIntent(tick));
        } else {
            v.setImageViewResource(R.id.zn_task_check, checked ? R.drawable.ic_zn_check_square : R.drawable.ic_zn_square);
            v.setInt(R.id.zn_task_check, "setColorFilter", boxColor);
            v.setOnClickFillInIntent(R.id.zn_task_check, tick);
        }
        v.setOnClickFillInIntent(R.id.zn_task_open,
                CalendarWidgetActionReceiver.openFillIn(WidgetLinks.task(task.id, task.path)));
        return v;
    }

    private static RemoteViews more(Context context, WidgetSnapshot.Palette p, Row row) {
        RemoteViews v = new RemoteViews(context.getPackageName(), R.layout.widget_cal_agenda_message);
        v.setTextViewText(R.id.zn_agenda_message, context.getString(R.string.widget_calendar_more, row.count));
        v.setTextColor(R.id.zn_agenda_message, p.muted);
        v.setOnClickFillInIntent(R.id.zn_agenda_message,
                CalendarWidgetActionReceiver.openFillIn(WidgetLinks.calendar(row.iso, false)));
        return v;
    }

    private static RemoteViews message(Context context, WidgetSnapshot.Palette p, Row row) {
        RemoteViews v = new RemoteViews(context.getPackageName(), R.layout.widget_cal_agenda_message);
        v.setTextViewText(R.id.zn_agenda_message, context.getString(R.string.widget_calendar_out_of_range));
        v.setTextColor(R.id.zn_agenda_message, p.muted);
        v.setOnClickFillInIntent(R.id.zn_agenda_message,
                CalendarWidgetActionReceiver.openFillIn(WidgetLinks.calendar(row.iso, false)));
        return v;
    }
}
