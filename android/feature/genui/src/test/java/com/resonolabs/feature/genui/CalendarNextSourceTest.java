package com.resonolabs.feature.genui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;

public class CalendarNextSourceTest {
    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");

    private static long ny(int day, int hour, int minute) {
        return ZonedDateTime.of(2026, 10, day, hour, minute, 0, 0, NEW_YORK).toInstant().toEpochMilli();
    }

    /** Runtime shape: all-day events are UTC midnight of the date, end exclusive; absent fields are null. */
    private static JSONObject event(String title, String startsAt, Object endsAt, boolean allDay, Object location)
            throws Exception {
        return new JSONObject().put("eventId", title).put("title", title).put("startsAt", startsAt)
                .put("endsAt", endsAt).put("allDay", allDay).put("location", location)
                .put("organizer", JSONObject.NULL).put("description", JSONObject.NULL).put("calendar", "Family");
    }

    private static GenCard card() {
        return GenCardParser.parseShow("{\"id\":\"next\",\"title\":\"Up next\","
                + "\"live\":{\"type\":\"calendar-next\"}}", 0L).card;
    }

    private static GenBlock list(GenCard card) {
        return card.body.get(0);
    }

    @Test public void todaysAllDayEventSurvivesEightPmInNewYork() throws Exception {
        // 2026-10-07T00:00Z..2026-10-08T00:00Z ends at 8 PM on the 7th in New York; it is still the 7th.
        ArrayList<CalendarNextSource.Event> events = CalendarNextSource.parse(new JSONArray()
                .put(event("Mom's birthday", "2026-10-07T00:00:00+00:00", "2026-10-08T00:00:00+00:00", true,
                        JSONObject.NULL)), NEW_YORK);
        GenCard card = card();
        CalendarNextSource.apply(card, events, ny(7, 21, 30), NEW_YORK);
        assertEquals("Mom's birthday", card.liveTitle);
        assertEquals("Today", card.liveTrailing);
        assertEquals("All day", card.liveSubtitle);
        assertEquals("All day", list(card).items[0].trailing);
        assertEquals(GenSchema.STATUS_NONE, list(card).items[0].status);
    }

    @Test public void yesterdaysAllDayEventKeptByTheRuntimeGraceIsDropped() throws Exception {
        // The runtime keeps all-day events 14 h past their stored end: at 9 AM the 6th is still listed.
        ArrayList<CalendarNextSource.Event> events = CalendarNextSource.parse(new JSONArray()
                .put(event("Yesterday", "2026-10-06T00:00:00+00:00", "2026-10-07T00:00:00+00:00", true, JSONObject.NULL))
                .put(event("Standup", "2026-10-07T13:30:00+00:00", "2026-10-07T13:45:00+00:00", false, "Zoom")),
                NEW_YORK);
        GenCard card = card();
        CalendarNextSource.apply(card, events, ny(7, 9, 0), NEW_YORK);
        assertEquals("Standup", card.liveTitle);
        assertEquals("30m", card.liveTrailing);
        assertEquals(1, list(card).items.length);
    }

    @Test public void nextTimedEventHeadsTheCardAndAllDayLeadsTheRows() throws Exception {
        ArrayList<CalendarNextSource.Event> events = CalendarNextSource.parse(new JSONArray()
                .put(event("Coffee", "2026-10-07T18:00:00Z", "2026-10-07T19:00:00Z", false, "Tartine"))
                .put(event("Mom's birthday", "2026-10-07T00:00:00+00:00", "2026-10-08T00:00:00+00:00", true,
                        JSONObject.NULL))
                .put(event("Holiday", "2026-10-08", "2026-10-09", true, JSONObject.NULL)), NEW_YORK);
        GenCard card = card();
        CalendarNextSource.apply(card, events, ny(7, 13, 35), NEW_YORK);
        assertEquals("Coffee", card.liveTitle);
        assertEquals("25m", card.liveTrailing);
        assertEquals("Tartine • 2:00 PM", card.liveSubtitle);
        GenBlock rows = list(card);
        assertEquals("Mom's birthday", rows.items[0].title);
        assertEquals("All day", rows.items[0].trailing);
        assertEquals("Coffee", rows.items[1].title);
        assertEquals(GenSchema.STATUS_IDLE, rows.items[1].status);
        assertEquals("Holiday", rows.items[2].title);
        assertEquals("Tmrw", rows.items[2].trailing);
    }

    @Test public void onlyTomorrowsAllDayEventLeft() throws Exception {
        ArrayList<CalendarNextSource.Event> events = CalendarNextSource.parse(new JSONArray()
                .put(event("Holiday", "2026-10-08T00:00:00+00:00", "2026-10-09T00:00:00+00:00", true,
                        JSONObject.NULL)), NEW_YORK);
        GenCard card = card();
        CalendarNextSource.apply(card, events, ny(7, 22, 0), NEW_YORK);
        assertEquals("Holiday", card.liveTitle);
        assertEquals("Tmrw", card.liveTrailing);
        assertEquals("All day • Tmrw", card.liveSubtitle);
    }

    @Test public void nullOptionalFieldsNeverShowAsText() throws Exception {
        JSONObject untitled = new JSONObject().put("title", JSONObject.NULL).put("startsAt", "2026-10-07T18:00:00Z")
                .put("endsAt", JSONObject.NULL).put("allDay", JSONObject.NULL).put("location", JSONObject.NULL);
        JSONObject noStart = new JSONObject().put("title", "Broken").put("startsAt", JSONObject.NULL);
        ArrayList<CalendarNextSource.Event> events = CalendarNextSource.parse(
                new JSONArray().put(untitled).put(noStart).put(JSONObject.NULL), NEW_YORK);
        assertEquals(1, events.size());
        CalendarNextSource.Event event = events.get(0);
        assertEquals("Untitled event", event.title);
        assertNull(event.location);
        assertFalse(event.allDay);
        assertEquals(30L * 60_000L, event.end - event.start);
        GenCard card = card();
        CalendarNextSource.apply(card, events, ny(7, 13, 0), NEW_YORK);
        assertEquals("2:00 PM", card.liveSubtitle);
        assertNull(list(card).items[0].detail);
        assertTrue(!card.liveSubtitle.contains("null"));
    }

    @Test public void multiDayAllDayEventCoversEveryLocalDay() throws Exception {
        ArrayList<CalendarNextSource.Event> events = CalendarNextSource.parse(new JSONArray()
                .put(event("Conference", "2026-10-06T00:00:00+00:00", "2026-10-09T00:00:00+00:00", true, "Lisbon")),
                NEW_YORK);
        CalendarNextSource.Event event = events.get(0);
        assertTrue(event.upcoming(ny(8, 23, 59), java.time.LocalDate.of(2026, 10, 8)));
        assertFalse(event.upcoming(ny(9, 0, 1), java.time.LocalDate.of(2026, 10, 9)));
        GenCard card = card();
        CalendarNextSource.apply(card, events, ny(8, 21, 0), NEW_YORK);
        assertEquals("Today", card.liveTrailing);
        assertEquals("Lisbon • All day", card.liveSubtitle);
    }
}
