package md.zennotes.widgets;

import static org.junit.Assert.assertEquals;

import org.json.JSONObject;
import org.junit.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class CalendarAgendaTest {
    /** A snapshot for the week of Mon 2026-10-26: four tasks open on Tuesday,
     *  two of them listed (the rest past the snapshot's cap), one on Wednesday. */
    private static CalendarWidgetData week() throws Exception {
        String json = "{"
                + "\"todayIso\":\"2026-10-27\",\"weekStart\":1,\"dailyEnabled\":true,"
                + "\"from\":\"2026-09-28\",\"to\":\"2026-11-08\",\"vaultKey\":\"zn://My Vault\","
                + "\"agenda\":{"
                + "\"2026-10-27\":{\"total\":4,\"tasks\":["
                + task("inbox/Errands.md#0", "Pick up the dry cleaning") + ","
                + task("inbox/Errands.md#1", "Return the library books") + "]},"
                + "\"2026-10-28\":{\"total\":1,\"tasks\":["
                + task("inbox/Errands.md#2", "Buy stamps") + "]}"
                + "}}";
        return CalendarWidgetData.parse(new JSONObject(json));
    }

    private static String task(String id, String content) {
        return "{\"id\":\"" + id + "\",\"path\":\"inbox/Errands.md\",\"noteTitle\":\"Errands\","
                + "\"content\":\"" + content + "\",\"line\":2,\"raw\":\"- [ ] " + content + "\","
                + "\"toggled\":\"- [x] " + content + "\"}";
    }

    private static int headerCount(List<CalendarAgenda.Row> rows, String iso) {
        for (CalendarAgenda.Row row : rows) {
            if (row.type == CalendarAgenda.HEADER && row.iso.equals(iso)) return row.count;
        }
        throw new AssertionError("no header for " + iso);
    }

    @Test
    public void countsTheSnapshotsOpenTasksWhenNothingIsTicked() throws Exception {
        List<CalendarAgenda.Row> rows = CalendarAgenda.rows(week(), "2026-10-27", new HashMap<>());
        assertEquals(4, headerCount(rows, "2026-10-27"));
        assertEquals(1, headerCount(rows, "2026-10-28"));
        assertEquals(0, headerCount(rows, "2026-10-29"));
    }

    @Test
    public void aTickComesOffItsDaysCountAtOnce() throws Exception {
        Map<String, WidgetActions.Mark> marks = new HashMap<>();
        marks.put("inbox/Errands.md#1", new WidgetActions.Mark(true, true));
        List<CalendarAgenda.Row> rows = CalendarAgenda.rows(week(), "2026-10-27", marks);
        assertEquals(3, headerCount(rows, "2026-10-27"));
        assertEquals(1, headerCount(rows, "2026-10-28"));
    }

    @Test
    public void aTickTheAppHasYetToWriteCountsToo() throws Exception {
        Map<String, WidgetActions.Mark> marks = new HashMap<>();
        marks.put("inbox/Errands.md#0", new WidgetActions.Mark(true, false));
        marks.put("inbox/Errands.md#2", new WidgetActions.Mark(true, false));
        List<CalendarAgenda.Row> rows = CalendarAgenda.rows(week(), "2026-10-27", marks);
        assertEquals(3, headerCount(rows, "2026-10-27"));
        assertEquals(0, headerCount(rows, "2026-10-28"));
    }

    @Test
    public void anUntickPutsItBack() throws Exception {
        Map<String, WidgetActions.Mark> marks = new HashMap<>();
        marks.put("inbox/Errands.md#1", new WidgetActions.Mark(false, true));
        assertEquals(4, headerCount(CalendarAgenda.rows(week(), "2026-10-27", marks), "2026-10-27"));
    }

    @Test
    public void aTickOnATaskTheSnapshotNoLongerListsIsNotTakenOffTwice() throws Exception {
        // The app applied the tick and published before the log was pruned:
        // the task has left the list and the total already counts it out.
        Map<String, WidgetActions.Mark> marks = new HashMap<>();
        marks.put("inbox/Errands.md#9", new WidgetActions.Mark(true, true));
        assertEquals(4, headerCount(CalendarAgenda.rows(week(), "2026-10-27", marks), "2026-10-27"));
    }

    @Test
    public void neverDropsBelowZero() throws Exception {
        // A snapshot whose total undercounts its own rows still reads 0, not -1.
        CalendarWidgetData data = CalendarWidgetData.parse(new JSONObject("{"
                + "\"weekStart\":1,\"dailyEnabled\":true,\"from\":\"2026-09-28\",\"to\":\"2026-11-08\","
                + "\"agenda\":{\"2026-10-27\":{\"total\":1,\"tasks\":["
                + task("a.md#0", "One") + "," + task("a.md#1", "Two") + "]}}}"));
        Map<String, WidgetActions.Mark> marks = new HashMap<>();
        marks.put("a.md#0", new WidgetActions.Mark(true, true));
        marks.put("a.md#1", new WidgetActions.Mark(true, true));
        assertEquals(0, CalendarAgenda.openCount(data.agenda.get("2026-10-27"), marks));
        assertEquals(0, CalendarAgenda.openCount(null, marks));
    }
}
