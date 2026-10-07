package com.resonolabs.feature.calendar;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.time.LocalDate;
import org.junit.Test;

public class CalendarDatesTest {
    private static final LocalDate OCT_7 = LocalDate.of(2026, 10, 7);

    @Test public void yesterdaysAllDayEventIsOverEvenThoughTheRuntimeStillListsIt() {
        // 6 Oct all-day, stored 2026-10-06T00:00Z..2026-10-07T00:00Z; the runtime keeps it until 7 Oct 14:00 UTC.
        assertTrue(CalendarDates.allDayOver("2026-10-06T00:00:00+00:00", "2026-10-07T00:00:00+00:00", OCT_7));
        assertTrue(CalendarDates.allDayOver("2026-10-06T00:00:00Z", "null", OCT_7));
    }

    @Test public void todaysAndMultiDayAllDayEventsAreNotOver() {
        assertFalse(CalendarDates.allDayOver("2026-10-07T00:00:00+00:00", "2026-10-08T00:00:00+00:00", OCT_7));
        assertFalse(CalendarDates.allDayOver("2026-10-07T00:00:00+00:00", "", OCT_7));
        assertFalse(CalendarDates.allDayOver("2026-10-05T00:00:00+00:00", "2026-10-09T00:00:00+00:00", OCT_7));
        assertFalse(CalendarDates.allDayOver("2026-10-08T00:00:00+00:00", "2026-10-09T00:00:00+00:00", OCT_7));
    }

    @Test public void unparseableStartIsKept() {
        assertFalse(CalendarDates.allDayOver("soon", "2026-10-07T00:00:00+00:00", OCT_7));
    }

    @Test public void datesAreTheStoredDateNotAZoneConversion() {
        assertEquals(OCT_7, CalendarDates.date("2026-10-07T00:00:00Z"));
        assertEquals(OCT_7, CalendarDates.date("2026-10-07"));
        assertNull(CalendarDates.date("null"));
    }
}
