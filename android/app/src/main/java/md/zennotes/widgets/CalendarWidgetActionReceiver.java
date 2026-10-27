package md.zennotes.widgets;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.widget.RemoteViews;

import org.json.JSONObject;

import java.util.List;

import md.zennotes.MainActivity;
import md.zennotes.R;

/**
 * The Calendar widget's taps: month paging, picking a day, and the week
 * list's rows (tick a task, open it, pick its day). Not exported: a tick
 * writes into a note, so only this app's own pending intents may ask.
 *
 * A tick is logged first (WidgetActions, which also draws the box ticked),
 * then written into the note when its line on disk is still the one the
 * snapshot named (TaskLineWriter). Either way the app applies the log the
 * next time it runs, through app-core's own toggle.
 */
public class CalendarWidgetActionReceiver extends BroadcastReceiver {
    static final String ACTION_MONTH = "md.zennotes.widgets.calendar.MONTH";
    static final String ACTION_SELECT = "md.zennotes.widgets.calendar.SELECT";
    static final String ACTION_LIST = "md.zennotes.widgets.calendar.LIST";
    static final String ACTION_MIDNIGHT = "md.zennotes.widgets.calendar.MIDNIGHT";

    static final String EXTRA_VALUE = "md.zennotes.widgets.extra.VALUE";
    static final String EXTRA_KIND = "md.zennotes.widgets.extra.KIND";
    static final String EXTRA_LINK = "md.zennotes.widgets.extra.LINK";
    static final String EXTRA_TASK_ID = "md.zennotes.widgets.extra.TASK_ID";
    static final String EXTRA_PATH = "md.zennotes.widgets.extra.PATH";
    static final String EXTRA_LINE = "md.zennotes.widgets.extra.LINE";
    static final String EXTRA_RAW = "md.zennotes.widgets.extra.RAW";
    static final String EXTRA_TOGGLED = "md.zennotes.widgets.extra.TOGGLED";
    static final String EXTRA_WANT = "md.zennotes.widgets.extra.WANT";
    /** RemoteViews.EXTRA_CHECKED (API 31): a CheckBox's new state, added to
     *  its fill-in intent by the system. */
    private static final String EXTRA_CHECKED = "android.widget.extra.CHECKED";

    static final String KIND_TOGGLE = "toggle";
    static final String KIND_OPEN = "open";
    static final String KIND_SELECT = "select";

    /** Long enough for the launcher to load the new list before it scrolls. */
    private static final long SCROLL_DELAY_MS = 350;

    /** A fixed tap: its own data URI, so each cell's intent stays distinct. */
    static PendingIntent broadcast(Context context, String action, int widgetId, String value) {
        Intent intent = new Intent(context, CalendarWidgetActionReceiver.class).setAction(action);
        intent.setData(Uri.parse("zennotes-widget://calendar/" + widgetId + "/" + action + "/"
                + Uri.encode(value == null ? "" : value)));
        intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId);
        intent.putExtra(EXTRA_VALUE, value);
        return PendingIntent.getBroadcast(context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** The week list's template; rows fill in their own extras, which needs
     *  a mutable pending intent on Android 12+. */
    static PendingIntent listTemplate(Context context, int widgetId) {
        Intent intent = new Intent(context, CalendarWidgetActionReceiver.class).setAction(ACTION_LIST);
        intent.setData(Uri.parse("zennotes-widget://calendar/" + widgetId + "/list"));
        intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) flags |= PendingIntent.FLAG_MUTABLE;
        return PendingIntent.getBroadcast(context, 0, intent, flags);
    }

    static Intent selectFillIn(String iso) {
        return new Intent().putExtra(EXTRA_KIND, KIND_SELECT).putExtra(EXTRA_VALUE, iso);
    }

    static Intent openFillIn(Uri link) {
        return new Intent().putExtra(EXTRA_KIND, KIND_OPEN).putExtra(EXTRA_LINK, link.toString());
    }

    static Intent toggleFillIn(CalendarWidgetData.Task task, boolean want) {
        return new Intent()
                .putExtra(EXTRA_KIND, KIND_TOGGLE)
                .putExtra(EXTRA_TASK_ID, task.id)
                .putExtra(EXTRA_PATH, task.path)
                .putExtra(EXTRA_LINE, task.line)
                .putExtra(EXTRA_RAW, task.raw)
                .putExtra(EXTRA_TOGGLED, task.toggled == null ? "" : task.toggled)
                .putExtra(EXTRA_WANT, want);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        Context app = context.getApplicationContext();
        String action = intent.getAction();
        int widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID);
        if (ACTION_MIDNIGHT.equals(action)) {
            CalendarWidgetProvider.refreshAll(app);
            return;
        }
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return;
        if (ACTION_MONTH.equals(action)) {
            int delta;
            try {
                delta = Integer.parseInt(intent.getStringExtra(EXTRA_VALUE));
            } catch (RuntimeException e) {
                return;
            }
            CalendarWidgetState state = CalendarWidgetState.load(app, widgetId);
            String month = delta == 0 ? CalendarMath.monthOf(CalendarMath.today()) : CalendarMath.addMonths(state.month, delta);
            CalendarWidgetState.save(app, widgetId, month, state.selected);
            redraw(app, widgetId, false);
        } else if (ACTION_SELECT.equals(action)) {
            select(app, widgetId, intent.getStringExtra(EXTRA_VALUE));
        } else if (ACTION_LIST.equals(action)) {
            String kind = intent.getStringExtra(EXTRA_KIND);
            if (KIND_SELECT.equals(kind)) {
                select(app, widgetId, intent.getStringExtra(EXTRA_VALUE));
            } else if (KIND_OPEN.equals(kind)) {
                open(app, intent.getStringExtra(EXTRA_LINK));
            } else if (KIND_TOGGLE.equals(kind)) {
                toggle(app, intent);
            }
        }
    }

    private void select(Context app, int widgetId, String iso) {
        if (iso == null || iso.length() != 10) return;
        CalendarWidgetState state = CalendarWidgetState.load(app, widgetId);
        CalendarWidgetState.save(app, widgetId, state.month, iso);
        redraw(app, widgetId, true);
    }

    private void redraw(Context app, int widgetId, boolean scrollToSelected) {
        AppWidgetManager manager = AppWidgetManager.getInstance(app);
        if (manager == null) return;
        WidgetSnapshot snapshot = WidgetSnapshot.load(app);
        CalendarWidgetProvider.update(app, manager, widgetId, snapshot, scrollToSelected);
        // From Android 12 the scroll rode along with the update; before, the
        // list loads through its service and can only scroll once it has.
        if (CalendarWidgetProvider.collectionItems()) return;
        if (!scrollToSelected || snapshot == null || snapshot.calendar == null) return;
        CalendarWidgetState state = CalendarWidgetState.load(app, widgetId);
        List<CalendarAgenda.Row> rows = CalendarAgenda.rows(snapshot.calendar, state.selected,
                WidgetActions.marks(app, snapshot.calendar.vaultKey));
        int position = CalendarAgenda.positionOf(rows, state.selected);
        PendingResult pending = goAsync();
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try {
                RemoteViews scroll = new RemoteViews(app.getPackageName(), R.layout.widget_calendar);
                scroll.setScrollPosition(R.id.zn_cal_agenda, position);
                manager.partiallyUpdateAppWidget(widgetId, scroll);
            } finally {
                pending.finish();
            }
        }, SCROLL_DELAY_MS);
    }

    private static void open(Context app, String link) {
        if (link == null || !link.startsWith("zennotes://")) return;
        Intent view = new Intent(Intent.ACTION_VIEW, Uri.parse(link), app, MainActivity.class);
        view.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        app.startActivity(view);
    }

    private void toggle(Context app, Intent intent) {
        String id = intent.getStringExtra(EXTRA_TASK_ID);
        String path = intent.getStringExtra(EXTRA_PATH);
        String raw = intent.getStringExtra(EXTRA_RAW);
        String toggled = intent.getStringExtra(EXTRA_TOGGLED);
        int line = intent.getIntExtra(EXTRA_LINE, -1);
        boolean want = intent.hasExtra(EXTRA_CHECKED)
                ? intent.getBooleanExtra(EXTRA_CHECKED, true)
                : intent.getBooleanExtra(EXTRA_WANT, true);
        if (id == null || path == null || raw == null || toggled == null) return;
        PendingResult pending = goAsync();
        new Thread(() -> {
            try {
                WidgetSnapshot snapshot = WidgetSnapshot.load(app);
                CalendarWidgetData cal = snapshot != null ? snapshot.calendar : null;
                if (cal == null) return;
                JSONObject action = new JSONObject()
                        .put("vaultKey", cal.vaultKey)
                        .put("id", id)
                        .put("path", path)
                        .put("line", line)
                        .put("raw", raw)
                        .put("toggled", toggled)
                        .put("checked", want)
                        .put("written", false);
                long at = WidgetActions.append(app, action);
                boolean written = !toggled.isEmpty() && cal.vaultRoot != null && TaskLineWriter.swap(
                        app, cal.vaultRoot, path, line, want ? raw : toggled, want ? toggled : raw);
                if (written) WidgetActions.markWritten(app, at);
                CalendarWidgetProvider.refreshAll(app);
            } catch (Exception e) {
                // The box redraws from the log; a failed log leaves it as it was.
            } finally {
                pending.finish();
            }
        }, "zn-calendar-tick").start();
    }
}
