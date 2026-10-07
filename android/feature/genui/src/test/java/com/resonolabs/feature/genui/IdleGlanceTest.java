package com.resonolabs.feature.genui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;

/** Idle Voice page glance chips: next calendar event and T3 counts. */
public class IdleGlanceTest {
    private static final ZoneId ZONE = ZoneId.of("America/New_York");
    // Wed 2026-10-07 14:00 New York
    private static final long NOW = ZonedDateTime.of(2026, 10, 7, 14, 0, 0, 0, ZONE).toInstant().toEpochMilli();
    private static final long MIN = 60_000L;

    private static CalendarNextSource.Event event(String title, long start, long minutes, boolean allDay) {
        return new CalendarNextSource.Event(title, start, start + minutes * MIN, null, allDay);
    }

    @Test public void nextTimedEventWithRelativeTimeAndClock() {
        ArrayList<CalendarNextSource.Event> events = new ArrayList<>();
        events.add(event("Holiday", NOW - 2 * 60 * MIN, 24 * 60, true));
        events.add(event("Design review", NOW + 25 * MIN, 30, false));
        events.add(event("Dinner", NOW + 5 * 60 * MIN, 60, false));
        IdleGlance.ChipText chip = IdleGlance.calendarChip(events, NOW, ZONE);
        assertEquals("Design review", chip.title);
        assertEquals("In 25m · 2:25 PM", chip.subtitle);
        assertFalse(chip.attention);
    }

    @Test public void ongoingEventSaysNow() {
        ArrayList<CalendarNextSource.Event> events = new ArrayList<>();
        events.add(event("Standup", NOW - 5 * MIN, 20, false));
        IdleGlance.ChipText chip = IdleGlance.calendarChip(events, NOW, ZONE);
        assertEquals("Standup", chip.title);
        assertEquals("Now · until 2:15 PM", chip.subtitle);
    }

    @Test public void tomorrowMorningShowsTmrw() {
        ArrayList<CalendarNextSource.Event> events = new ArrayList<>();
        long tomorrow9 = ZonedDateTime.of(2026, 10, 8, 9, 0, 0, 0, ZONE).toInstant().toEpochMilli();
        events.add(event("Dentist", tomorrow9, 60, false));
        IdleGlance.ChipText chip = IdleGlance.calendarChip(events, NOW, ZONE);
        assertEquals("In 19h · Tmrw 9:00 AM", chip.subtitle);
    }

    @Test public void hiddenWhenNothingTimedSoonOrAllPast() {
        ArrayList<CalendarNextSource.Event> events = new ArrayList<>();
        assertNull(IdleGlance.calendarChip(events, NOW, ZONE));
        events.add(event("All-day offsite", NOW - 60 * MIN, 24 * 60, true));
        events.add(event("Earlier", NOW - 90 * MIN, 30, false));
        events.add(event("Next week", NOW + 6L * 24 * 60 * MIN, 30, false));
        assertNull(IdleGlance.calendarChip(events, NOW, ZONE));
        assertNull(IdleGlance.calendarChip(null, NOW, ZONE));
    }

    @Test public void t3NeedsYouWinsOverWorking() {
        IdleGlance.ChipText one = IdleGlance.t3Chip(1, 0);
        assertEquals("1 needs you", one.title);
        assertEquals("T3 Code", one.subtitle);
        assertTrue(one.attention);
        IdleGlance.ChipText two = IdleGlance.t3Chip(2, 3);
        assertEquals("2 need you", two.title);
        assertEquals("T3 Code · 3 working", two.subtitle);
        IdleGlance.ChipText working = IdleGlance.t3Chip(0, 3);
        assertEquals("3 working", working.title);
        assertFalse(working.attention);
        assertNull(IdleGlance.t3Chip(0, 0));
    }
}
