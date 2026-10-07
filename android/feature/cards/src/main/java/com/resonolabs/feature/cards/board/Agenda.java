package com.resonolabs.feature.cards.board;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * "Up next" grouping rules: today's remaining events, then tomorrow, within a row budget.
 *
 * <ul>
 *   <li>Running events are {@link Kind#NOW} (even when they began yesterday).</li>
 *   <li>All-day events of a day collapse into one compact {@link Kind#ALL_DAY} row shown first.</li>
 *   <li>A multi-day all-day event appears on the first visible day only.</li>
 *   <li>When today and tomorrow are empty, {@link #later} is the next event after that.</li>
 *   <li>The same event on two calendars (same title, start and end) is shown once.</li>
 *   <li>A busy today leaves {@code tomorrowReserve} rows for tomorrow, so tomorrow never vanishes
 *       behind "+N more".</li>
 * </ul>
 * Pure Java; the device time zone is whatever {@code now} carries.
 */
public final class Agenda {
    public enum Kind { NOW, UPCOMING, ALL_DAY }

    public static final class Row {
        public final Kind kind;
        public final boolean today;
        public final LocalDate day;
        /** Exactly one event, except a combined ALL_DAY row. */
        public final List<AgendaEvent> events;

        Row(Kind kind, boolean today, LocalDate day, List<AgendaEvent> events) {
            this.kind = kind;
            this.today = today;
            this.day = day;
            this.events = Collections.unmodifiableList(events);
        }

        public AgendaEvent event() { return events.get(0); }
    }

    public static final class Section {
        public final boolean today;
        public final LocalDate day;
        public final List<Row> rows;

        Section(boolean today, LocalDate day, List<Row> rows) {
            this.today = today;
            this.day = day;
            this.rows = Collections.unmodifiableList(rows);
        }
    }

    /** Visible sections in order (Today, Tomorrow); a section with no visible rows is omitted. */
    public final List<Section> sections;
    /** Remaining events today, including running and all-day ones. */
    public final int todayCount;
    /** Events tomorrow. */
    public final int tomorrowCount;
    /** Events of today or tomorrow that did not fit the row budget. */
    public final int hidden;
    /** First event after tomorrow, only when today and tomorrow have nothing. */
    public final AgendaEvent later;

    private Agenda(List<Section> sections, int todayCount, int tomorrowCount, int hidden, AgendaEvent later) {
        this.sections = Collections.unmodifiableList(sections);
        this.todayCount = todayCount;
        this.tomorrowCount = tomorrowCount;
        this.hidden = hidden;
        this.later = later;
    }

    public boolean todayEmpty() { return todayCount == 0; }

    /** Header summary: "3 today · 2 tomorrow", "3 today", "Free today · 2 tomorrow", "Free". */
    public String summary() {
        String today = todayCount > 0 ? todayCount + " today" : "";
        String next = tomorrowCount > 0 ? tomorrowCount + " tomorrow" : "";
        if (!today.isEmpty() && !next.isEmpty()) return today + " · " + next;
        if (!today.isEmpty()) return today;
        if (!next.isEmpty()) return "Free today · " + next;
        return "Free";
    }

    public static Agenda build(List<AgendaEvent> events, ZonedDateTime now, int maxRows) {
        return build(events, now, maxRows, 0);
    }

    /**
     * Up to {@code maxRows} rows; when tomorrow has events, today gets at most
     * {@code maxRows - min(tomorrowRows, tomorrowReserve)} of them and tomorrow the rest.
     */
    public static Agenda build(List<AgendaEvent> input, ZonedDateTime now, int maxRows, int tomorrowReserve) {
        List<AgendaEvent> events = dedupe(input);
        ZoneId zone = now.getZone();
        Instant instant = now.toInstant();
        LocalDate today = now.toLocalDate();
        LocalDate tomorrow = today.plusDays(1);
        List<AgendaEvent> todayAllDay = new ArrayList<>();
        List<AgendaEvent> running = new ArrayList<>();
        List<AgendaEvent> todayTimed = new ArrayList<>();
        List<AgendaEvent> tomorrowAllDay = new ArrayList<>();
        List<AgendaEvent> tomorrowTimed = new ArrayList<>();
        AgendaEvent later = null;
        for (AgendaEvent event : events) {
            if (event == null) continue;
            if (event.allDay) {
                if (event.covers(today)) todayAllDay.add(event);
                else if (event.covers(tomorrow)) tomorrowAllDay.add(event);
                else if (event.firstDay.isAfter(tomorrow)) later = earlier(later, event);
                continue;
            }
            boolean point = event.end.equals(event.start);
            if (point ? event.start.isBefore(instant) : !event.end.isAfter(instant)) continue; // over
            if (!event.start.isAfter(instant)) { running.add(event); continue; }
            LocalDate day = event.start.atZone(zone).toLocalDate();
            if (day.equals(today)) todayTimed.add(event);
            else if (day.equals(tomorrow)) tomorrowTimed.add(event);
            else if (day.isAfter(tomorrow)) later = earlier(later, event);
        }
        Comparator<AgendaEvent> byStart = Comparator.<AgendaEvent, Instant>comparing(item -> item.start)
                .thenComparing(item -> item.title).thenComparing(item -> item.id);
        Comparator<AgendaEvent> byTitle = Comparator.<AgendaEvent, String>comparing(item -> item.title)
                .thenComparing(item -> item.id);
        todayAllDay.sort(byTitle);
        tomorrowAllDay.sort(byTitle);
        running.sort(byStart);
        todayTimed.sort(byStart);
        tomorrowTimed.sort(byStart);

        List<Row> todayRows = new ArrayList<>();
        if (!todayAllDay.isEmpty()) todayRows.add(new Row(Kind.ALL_DAY, true, today, todayAllDay));
        for (AgendaEvent event : running) todayRows.add(new Row(Kind.NOW, true, today, List.of(event)));
        for (AgendaEvent event : todayTimed) todayRows.add(new Row(Kind.UPCOMING, true, today, List.of(event)));
        List<Row> tomorrowRows = new ArrayList<>();
        if (!tomorrowAllDay.isEmpty()) tomorrowRows.add(new Row(Kind.ALL_DAY, false, tomorrow, tomorrowAllDay));
        for (AgendaEvent event : tomorrowTimed) tomorrowRows.add(new Row(Kind.UPCOMING, false, tomorrow, List.of(event)));

        int total = Math.max(1, maxRows);
        int reserve = Math.min(tomorrowRows.size(), Math.max(0, Math.min(tomorrowReserve, total - 1)));
        int hidden = 0;
        List<Section> sections = new ArrayList<>();
        for (int pass = 0; pass < 2; pass++) {
            List<Row> source = pass == 0 ? todayRows : tomorrowRows;
            int budget = pass == 0 ? total - reserve : total - visibleRows(sections);
            List<Row> visible = new ArrayList<>();
            for (Row row : source) {
                if (budget > 0) { visible.add(row); budget--; }
                else hidden += row.events.size();
            }
            if (!visible.isEmpty()) sections.add(new Section(pass == 0, pass == 0 ? today : tomorrow, visible));
        }
        int todayCount = todayAllDay.size() + running.size() + todayTimed.size();
        int tomorrowCount = tomorrowAllDay.size() + tomorrowTimed.size();
        return new Agenda(sections, todayCount, tomorrowCount, hidden,
                todayCount == 0 && tomorrowCount == 0 ? later : null);
    }

    private static int visibleRows(List<Section> sections) {
        int rows = 0;
        for (Section section : sections) rows += section.rows.size();
        return rows;
    }

    /**
     * Drops repeats of one event shown by several calendars (an invite on the work and the
     * personal calendar): same title (ignoring case and spacing), all-day flag, start and end.
     * The first copy wins, so the input order (the runtime's start order) decides its calendar.
     */
    static List<AgendaEvent> dedupe(List<AgendaEvent> events) {
        List<AgendaEvent> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (AgendaEvent event : events) {
            if (event == null) continue;
            String key = event.title.trim().replaceAll("\\s+", " ").toLowerCase(java.util.Locale.ROOT) + "|" + event.allDay
                    + "|" + event.start.toEpochMilli() + "|" + event.end.toEpochMilli();
            if (seen.add(key)) out.add(event);
        }
        return out;
    }

    /** Fraction of a running event that has elapsed, clamped to [0, 1]. */
    public static float progress(AgendaEvent event, Instant now) {
        long total = event.end.toEpochMilli() - event.start.toEpochMilli();
        if (total <= 0L) return now.isBefore(event.start) ? 0f : 1f;
        float value = (now.toEpochMilli() - event.start.toEpochMilli()) / (float) total;
        return Math.max(0f, Math.min(1f, value));
    }

    private static AgendaEvent earlier(AgendaEvent current, AgendaEvent candidate) {
        if (current == null) return candidate;
        return candidate.start.isBefore(current.start) ? candidate : current;
    }
}
