package md.zennotes.widgets;

import android.text.format.DateFormat;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.Locale;

/**
 * Calendar arithmetic for the Calendar widget: the same 6-week grid, ISO
 * week numbers and labels app-core's calendar panel draws
 * (lib/calendar-data.ts, CalendarPanel.tsx). Dates travel as ISO
 * `YYYY-MM-DD` strings, the form the snapshot keys days by; no task or
 * note rules live here, only the calendar's.
 */
final class CalendarMath {
    private CalendarMath() {}

    /** The panel's weekday labels (it uppercases them in CSS). */
    private static final String[] DAY_LABELS = {"SUN", "MON", "TUE", "WED", "THU", "FRI", "SAT"};

    static Calendar parse(String iso) {
        Calendar c = new GregorianCalendar();
        c.clear();
        try {
            c.set(Integer.parseInt(iso.substring(0, 4)), Integer.parseInt(iso.substring(5, 7)) - 1,
                    Integer.parseInt(iso.substring(8, 10)));
        } catch (RuntimeException e) {
            c.setTimeInMillis(System.currentTimeMillis());
        }
        return c;
    }

    static String iso(Calendar c) {
        return String.format(Locale.US, "%04d-%02d-%02d",
                c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH));
    }

    static String today() {
        return iso(Calendar.getInstance());
    }

    static String addDays(String iso, int days) {
        Calendar c = parse(iso);
        c.add(Calendar.DAY_OF_MONTH, days);
        return iso(c);
    }

    /** `YYYY-MM` of a day. */
    static String monthOf(String iso) {
        return iso.substring(0, 7);
    }

    static String addMonths(String month, int months) {
        Calendar c = parse(month + "-01");
        c.add(Calendar.MONTH, months);
        return iso(c).substring(0, 7);
    }

    /** 0 = Sunday … 6 = Saturday, as the snapshot's `weekStart`. */
    static int weekday(String iso) {
        return parse(iso).get(Calendar.DAY_OF_WEEK) - 1;
    }

    /** The 42 days of `month`'s grid starting on `weekStart` (buildGrid). */
    static List<String> grid(String month, int weekStart) {
        String first = month + "-01";
        int offset = (weekday(first) - weekStart + 7) % 7;
        String start = addDays(first, -offset);
        List<String> days = new ArrayList<>(42);
        Calendar c = parse(start);
        for (int i = 0; i < 42; i++) {
            days.add(iso(c));
            c.add(Calendar.DAY_OF_MONTH, 1);
        }
        return days;
    }

    /** The 7 days of the week holding `iso`, from `weekStart` (the agenda). */
    static List<String> weekOf(String iso, int weekStart) {
        int offset = (weekday(iso) - weekStart + 7) % 7;
        List<String> days = new ArrayList<>(7);
        for (int i = 0; i < 7; i++) days.add(addDays(iso, i - offset));
        return days;
    }

    /** The day a grid row's week number comes from: its Monday, else its
     *  first day (CalendarPanel's `monday`). */
    static String rowMonday(List<String> row) {
        for (String day : row) if (weekday(day) == 1) return day;
        return row.get(0);
    }

    private static GregorianCalendar isoCalendar(String iso) {
        GregorianCalendar c = new GregorianCalendar();
        c.setFirstDayOfWeek(Calendar.MONDAY);
        c.setMinimalDaysInFirstWeek(4);
        c.setTimeInMillis(parse(iso).getTimeInMillis());
        return c;
    }

    static int isoWeek(String iso) {
        return isoCalendar(iso).get(Calendar.WEEK_OF_YEAR);
    }

    /** `2026-W44`: how the snapshot keys weekly notes. */
    static String isoWeekKey(String iso) {
        GregorianCalendar c = isoCalendar(iso);
        return String.format(Locale.US, "%d-W%02d", c.getWeekYear(), c.get(Calendar.WEEK_OF_YEAR));
    }

    static String dayLabel(int weekday) {
        return DAY_LABELS[((weekday % 7) + 7) % 7];
    }

    private static String format(String iso, String skeleton) {
        String pattern = DateFormat.getBestDateTimePattern(Locale.getDefault(), skeleton);
        return new SimpleDateFormat(pattern, Locale.getDefault()).format(parse(iso).getTime());
    }

    /** "October 2026". */
    static String monthLabel(String month) {
        return format(month + "-01", "MMMMy");
    }

    /** "27 Tue" in en-US, the panel's `{ weekday: 'short', day: 'numeric' }`. */
    static String shortDay(String iso) {
        return format(iso, "dEEE");
    }

    /** "Oct 26 – Nov 1", or "Oct 5 – 11" within one month (the panel's label). */
    static String weekRange(List<String> week) {
        String start = week.get(0);
        String end = week.get(6);
        String endLabel = monthOf(start).equals(monthOf(end)) ? format(end, "d") : format(end, "MMMd");
        return format(start, "MMMd") + " – " + endLabel;
    }
}
