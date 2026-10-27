package md.zennotes.widgets;

import android.appwidget.AppWidgetManager;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;
import android.widget.RemoteViewsService;

import java.util.Collections;
import java.util.List;

import md.zennotes.R;

/**
 * The Calendar widget's week list before Android 12, which has no
 * collection items to hand the list over with the rest of the widget
 * (CalendarWidgetProvider uses those from 12 on). Same rows (CalendarAgenda).
 */
public class CalendarAgendaService extends RemoteViewsService {
    @Override
    public RemoteViewsFactory onGetViewFactory(Intent intent) {
        return new Factory(getApplicationContext(),
                intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID));
    }

    static final class Factory implements RemoteViewsFactory {
        private final Context context;
        private final int widgetId;
        private List<CalendarAgenda.Row> rows = Collections.emptyList();
        private WidgetSnapshot.Palette palette = WidgetSnapshot.Palette.fallback();
        private String today = CalendarMath.today();
        private String selected = today;

        Factory(Context context, int widgetId) {
            this.context = context;
            this.widgetId = widgetId;
        }

        @Override
        public void onCreate() {
            load();
        }

        @Override
        public void onDataSetChanged() {
            load();
        }

        private void load() {
            WidgetSnapshot snapshot = WidgetSnapshot.load(context);
            CalendarWidgetData cal = snapshot != null ? snapshot.calendar : null;
            palette = snapshot != null ? snapshot.palette : WidgetSnapshot.Palette.fallback();
            today = CalendarMath.today();
            selected = CalendarWidgetState.load(context, widgetId).selected;
            rows = cal == null
                    ? Collections.<CalendarAgenda.Row>emptyList()
                    : CalendarAgenda.rows(cal, selected, WidgetActions.marks(context, cal.vaultKey));
        }

        @Override
        public void onDestroy() {}

        @Override
        public int getCount() {
            return rows.size();
        }

        @Override
        public RemoteViews getViewAt(int position) {
            if (position < 0 || position >= rows.size()) {
                return new RemoteViews(context.getPackageName(), R.layout.widget_cal_agenda_message);
            }
            return CalendarAgenda.view(context, palette, rows.get(position), selected, today);
        }

        @Override
        public RemoteViews getLoadingView() {
            return null;
        }

        @Override
        public int getViewTypeCount() {
            return CalendarAgenda.VIEW_TYPES;
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public boolean hasStableIds() {
            return false;
        }
    }
}
