package com.resonolabs.feature.cards.board;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.Test;

public class AgendaTest {
    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");
    /** Wednesday 7 Oct 2026, 10:20 in New York (14:20 UTC). */
    private static final ZonedDateTime NOW = ZonedDateTime.of(2026, 10, 7, 10, 20, 0, 0, NEW_YORK);

    private static AgendaEvent timed(String id, String startUtc, String endUtc) {
        return AgendaEvent.of(id, id, startUtc, endUtc, false, "", "Work", NEW_YORK);
    }

    private static AgendaEvent allDay(String id, String startUtc, String endUtc) {
        return AgendaEvent.of(id, id, startUtc, endUtc, true, "", "Family", NEW_YORK);
    }

    private static List<String> ids(Agenda.Section section) {
        List<String> out = new ArrayList<>();
        for (Agenda.Row row : section.rows) for (AgendaEvent event : row.events) out.add(row.kind + ":" + event.id);
        return out;
    }

    @Test public void allDayStoredAsUtcMidnightStaysOnItsCalendarDate() {
        // The runtime stores the all-day 7 Oct as 2026-10-07T00:00Z, which is 6 Oct 8 PM in New York.
        AgendaEvent birthday = allDay("birthday", "2026-10-07T00:00:00+00:00", "2026-10-08T00:00:00+00:00");
        AgendaEvent holiday = allDay("holiday", "2026-10-08T00:00:00+00:00", "2026-10-09T00:00:00+00:00");
        assertEquals(LocalDate.of(2026, 10, 7), birthday.firstDay);
        assertTrue(birthday.covers(LocalDate.of(2026, 10, 7)));
        Agenda agenda = Agenda.build(List.of(holiday, birthday), NOW, 4);
        assertEquals(2, agenda.sections.size());
        assertEquals(List.of("ALL_DAY:birthday"), ids(agenda.sections.get(0)));
        assertTrue(agenda.sections.get(0).today);
        assertEquals(List.of("ALL_DAY:holiday"), ids(agenda.sections.get(1)));
        assertEquals(LocalDate.of(2026, 10, 8), agenda.sections.get(1).day);
        assertEquals(1, agenda.todayCount);
        assertEquals(1, agenda.tomorrowCount);
    }

    @Test public void allDayEventsOfADayCollapseIntoOneRowShownFirst() {
        Agenda agenda = Agenda.build(List.of(
                timed("standup", "2026-10-07T15:00:00Z", "2026-10-07T15:15:00Z"),
                allDay("zeta", "2026-10-07T00:00:00+00:00", "2026-10-08T00:00:00+00:00"),
                allDay("alpha", "2026-10-07T00:00:00+00:00", null)), NOW, 4);
        Agenda.Row first = agenda.sections.get(0).rows.get(0);
        assertEquals(Agenda.Kind.ALL_DAY, first.kind);
        assertEquals("alpha", first.events.get(0).id);
        assertEquals("zeta", first.events.get(1).id);
        assertEquals(List.of("ALL_DAY:alpha", "ALL_DAY:zeta", "UPCOMING:standup"), ids(agenda.sections.get(0)));
        assertEquals(3, agenda.todayCount);
    }

    @Test public void multiDayAllDayAppearsOnceOnTheFirstVisibleDay() {
        AgendaEvent trip = allDay("trip", "2026-10-06T00:00:00+00:00", "2026-10-10T00:00:00+00:00");
        Agenda agenda = Agenda.build(List.of(trip,
                timed("flight", "2026-10-08T13:00:00Z", "2026-10-08T15:00:00Z")), NOW, 4);
        assertEquals(List.of("ALL_DAY:trip"), ids(agenda.sections.get(0)));
        assertEquals(List.of("UPCOMING:flight"), ids(agenda.sections.get(1)));
    }

    @Test public void yesterdaysAllDayEventKeptByTheRuntimeGraceIsDropped() {
        // The upcoming projection keeps all-day events up to 14 h past their stored UTC end.
        AgendaEvent yesterday = allDay("yesterday", "2026-10-06T00:00:00+00:00", "2026-10-07T00:00:00+00:00");
        Agenda agenda = Agenda.build(List.of(yesterday), NOW, 4);
        assertTrue(agenda.sections.isEmpty());
        assertEquals(0, agenda.todayCount);
        assertNull(agenda.later);
    }

    @Test public void groupsAndSortsInTheDeviceZoneAndDropsWhatIsOver() {
        Agenda agenda = Agenda.build(List.of(
                timed("dinner", "2026-10-07T23:00:00Z", "2026-10-08T00:30:00Z"),     // 7 PM today
                timed("late", "2026-10-08T03:30:00Z", "2026-10-08T04:00:00Z"),       // 11:30 PM today
                timed("gym", "2026-10-08T11:00:00Z", "2026-10-08T12:00:00Z"),        // 7 AM tomorrow
                timed("review", "2026-10-07T14:00:00Z", "2026-10-07T15:00:00Z"),     // running now
                timed("breakfast", "2026-10-07T12:00:00Z", "2026-10-07T13:00:00Z"),  // over
                timed("lunch", "2026-10-07T16:00:00Z", "2026-10-07T17:00:00Z"),      // noon today
                timed("friday", "2026-10-09T14:00:00Z", "2026-10-09T15:00:00Z")), NOW, 8);
        assertEquals(List.of("NOW:review", "UPCOMING:lunch", "UPCOMING:dinner", "UPCOMING:late"),
                ids(agenda.sections.get(0)));
        assertEquals(List.of("UPCOMING:gym"), ids(agenda.sections.get(1)));
        assertEquals(4, agenda.todayCount);
        assertEquals(1, agenda.tomorrowCount);
        assertEquals(0, agenda.hidden);
        assertNull("later is only offered when today and tomorrow are empty", agenda.later);
    }

    @Test public void eventThatStartedYesterdayAndIsStillRunningIsNow() {
        Agenda agenda = Agenda.build(List.of(
                timed("overnight", "2026-10-07T02:00:00Z", "2026-10-07T16:00:00Z")), NOW, 4);
        assertEquals(List.of("NOW:overnight"), ids(agenda.sections.get(0)));
    }

    @Test public void rowBudgetHidesOverflowAcrossSections() {
        List<AgendaEvent> events = new ArrayList<>();
        for (int hour = 15; hour <= 20; hour++) {
            events.add(timed("t" + hour, "2026-10-07T" + hour + ":00:00Z", "2026-10-07T" + hour + ":30:00Z"));
        }
        events.add(timed("tomorrow", "2026-10-08T14:00:00Z", "2026-10-08T15:00:00Z"));
        Agenda agenda = Agenda.build(events, NOW, 4);
        assertEquals(1, agenda.sections.size());
        assertEquals(4, agenda.sections.get(0).rows.size());
        assertEquals(3, agenda.hidden);
        assertEquals(6, agenda.todayCount);
        assertEquals(1, agenda.tomorrowCount);
    }

    @Test public void emptyTodayShowsTomorrowAndLaterOnlyWhenBothAreEmpty() {
        AgendaEvent friday = timed("friday", "2026-10-09T14:00:00Z", "2026-10-09T15:00:00Z");
        AgendaEvent saturday = allDay("saturday", "2026-10-10T00:00:00+00:00", "2026-10-11T00:00:00+00:00");
        Agenda nothingSoon = Agenda.build(List.of(saturday, friday), NOW, 4);
        assertTrue(nothingSoon.todayEmpty());
        assertTrue(nothingSoon.sections.isEmpty());
        assertSame(friday, nothingSoon.later);

        Agenda tomorrowOnly = Agenda.build(List.of(friday,
                timed("gym", "2026-10-08T11:00:00Z", "2026-10-08T12:00:00Z")), NOW, 4);
        assertTrue(tomorrowOnly.todayEmpty());
        assertEquals(1, tomorrowOnly.sections.size());
        assertEquals(false, tomorrowOnly.sections.get(0).today);
        assertNull(tomorrowOnly.later);
    }

    @Test public void pointEventWithoutEndIsUpcomingUntilItStarts() {
        AgendaEvent past = AgendaEvent.of("past", "Past", "2026-10-07T14:00:00Z", null, false, "", "", NEW_YORK);
        AgendaEvent soon = AgendaEvent.of("soon", "Soon", "2026-10-07T14:30:00Z", "", false, "", "", NEW_YORK);
        assertEquals(past.start, past.end);
        Agenda agenda = Agenda.build(List.of(past, soon), NOW, 4);
        assertEquals(List.of("UPCOMING:soon"), ids(agenda.sections.get(0)));
    }

    @Test public void unparseableStartIsSkippedNotFatal() {
        assertNull(AgendaEvent.of("x", "Broken", "tomorrow-ish", null, false, "", "", NEW_YORK));
        Agenda agenda = Agenda.build(java.util.Arrays.asList(null,
                timed("ok", "2026-10-07T15:00:00Z", "2026-10-07T16:00:00Z")), NOW, 4);
        assertEquals(1, agenda.todayCount);
        assertEquals("Untitled event", AgendaEvent.of("y", "  ", "2026-10-07T15:00:00Z", null, false, null, null, NEW_YORK).title);
    }

    @Test public void nowProgressIsClampedFraction() {
        AgendaEvent review = timed("review", "2026-10-07T14:00:00Z", "2026-10-07T15:00:00Z");
        assertEquals(0.5f, Agenda.progress(review, Instant.parse("2026-10-07T14:30:00Z")), 0.0001f);
        assertEquals(0.3333f, Agenda.progress(review, NOW.toInstant()), 0.001f);
        assertEquals(0f, Agenda.progress(review, Instant.parse("2026-10-07T13:00:00Z")), 0f);
        assertEquals(1f, Agenda.progress(review, Instant.parse("2026-10-07T16:00:00Z")), 0f);
        AgendaEvent point = timed("point", "2026-10-07T14:00:00Z", null);
        assertEquals(1f, Agenda.progress(point, NOW.toInstant()), 0f);
    }

    @Test public void labelsUseTheDeviceZoneAndClockStyle() {
        AgendaText twelve = new AgendaText(NEW_YORK, false, Locale.US);
        assertEquals("10:00 – 11:00 AM", twelve.range(timed("a", "2026-10-07T14:00:00Z", "2026-10-07T15:00:00Z")));
        assertEquals("11:30 AM – 12:30 PM", twelve.range(timed("b", "2026-10-07T15:30:00Z", "2026-10-07T16:30:00Z")));
        assertEquals("9:00 AM", twelve.range(timed("c", "2026-10-07T13:00:00Z", null)));
        assertEquals("All day", twelve.range(allDay("d", "2026-10-07T00:00:00+00:00", null)));
        String[] clock = twelve.clock(Instant.parse("2026-10-07T23:05:00Z"));
        assertEquals("7:05", clock[0]);
        assertEquals("PM", clock[1]);
        AgendaText twentyFour = new AgendaText(NEW_YORK, true, Locale.US);
        assertEquals("19:05", twentyFour.clock(Instant.parse("2026-10-07T23:05:00Z"))[0]);
        assertEquals("", twentyFour.clock(Instant.parse("2026-10-07T23:05:00Z"))[1]);
        assertEquals("10:00 – 11:00", twentyFour.range(timed("e", "2026-10-07T14:00:00Z", "2026-10-07T15:00:00Z")));
        assertEquals("Fri, Oct 9", twelve.day(LocalDate.of(2026, 10, 9)));
        // Another zone moves the same instant.
        assertEquals("7:00 – 8:00 AM", new AgendaText(ZoneId.of("America/Los_Angeles"), false, Locale.US)
                .range(timed("f", "2026-10-07T14:00:00Z", "2026-10-07T15:00:00Z")));
    }

    @Test public void zuluAndOffsetSpellingsParseToTheSameInstant() {
        AgendaEvent zulu = timed("z", "2026-10-07T14:00:00Z", null);
        AgendaEvent offset = timed("o", "2026-10-07T10:00:00-04:00", null);
        assertEquals(zulu.start, offset.start);
    }

    @Test public void tomorrowKeepsItsReservedRowsWhenTodayIsBusy() {
        List<AgendaEvent> events = new ArrayList<>();
        for (int hour = 15; hour <= 20; hour++) {
            events.add(timed("t" + hour, "2026-10-07T" + hour + ":00:00Z", "2026-10-07T" + hour + ":30:00Z"));
        }
        for (int hour = 12; hour <= 15; hour++) {
            events.add(timed("m" + hour, "2026-10-08T" + hour + ":00:00Z", "2026-10-08T" + hour + ":30:00Z"));
        }
        Agenda agenda = Agenda.build(events, NOW, 7, 3);
        assertEquals(2, agenda.sections.size());
        assertEquals(4, agenda.sections.get(0).rows.size());
        assertEquals(List.of("UPCOMING:m12", "UPCOMING:m13", "UPCOMING:m14"), ids(agenda.sections.get(1)));
        assertEquals(3, agenda.hidden);
        assertEquals("6 today · 4 tomorrow", agenda.summary());

        // A quiet today hands its unused rows to tomorrow.
        Agenda quiet = Agenda.build(events.subList(5, events.size()), NOW, 7, 3);
        assertEquals(List.of("UPCOMING:t20"), ids(quiet.sections.get(0)));
        assertEquals(4, quiet.sections.get(1).rows.size());
        assertEquals(0, quiet.hidden);
    }

    @Test public void theSameEventOnTwoCalendarsShowsOnce() {
        AgendaEvent work = AgendaEvent.of("w", "Mastermind", "2026-10-07T16:00:00Z", "2026-10-07T17:00:00Z", false,
                "Zoom", "Work", NEW_YORK);
        AgendaEvent personal = AgendaEvent.of("p", " mastermind ", "2026-10-07T16:00:00Z", "2026-10-07T17:00:00Z", false,
                "Zoom", "Personal", NEW_YORK);
        AgendaEvent moved = AgendaEvent.of("m", "Mastermind", "2026-10-07T18:00:00Z", "2026-10-07T19:00:00Z", false,
                "Zoom", "Personal", NEW_YORK);
        Agenda agenda = Agenda.build(List.of(work, personal, moved), NOW, 7, 3);
        assertEquals(List.of("UPCOMING:w", "UPCOMING:m"), ids(agenda.sections.get(0)));
        assertEquals(2, agenda.todayCount);
    }

    @Test public void summaryNamesTodayAndTomorrow() {
        assertEquals("Free", Agenda.build(List.of(), NOW, 7, 3).summary());
        assertEquals("Free today · 1 tomorrow", Agenda.build(List.of(
                timed("gym", "2026-10-08T11:00:00Z", "2026-10-08T12:00:00Z")), NOW, 7, 3).summary());
        assertEquals("1 today", Agenda.build(List.of(
                timed("lunch", "2026-10-07T16:00:00Z", "2026-10-07T17:00:00Z")), NOW, 7, 3).summary());
    }
}
