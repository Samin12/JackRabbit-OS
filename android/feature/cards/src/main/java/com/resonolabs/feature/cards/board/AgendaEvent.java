package com.resonolabs.feature.cards.board;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;

/**
 * One calendar event normalized for the glanceable agenda. Pure Java (java.time only) so the
 * grouping rules are JVM-testable.
 *
 * <p>The runtime stores timed events as UTC instants and all-day events as UTC midnight of the
 * calendar date ({@code 2026-10-07T00:00:00+00:00}, end exclusive). An all-day date is a floating
 * calendar date, so it is read from the stored date fields, never converted to the device zone
 * (in New York that instant is 8 PM the previous day).
 */
public final class AgendaEvent {
    public final String id;
    public final String title;
    public final String location;
    public final String calendar;
    public final boolean allDay;
    /** Timed events: start instant. All-day: start of the first day in {@code zone}. */
    public final Instant start;
    /** Timed events: end instant (equal to start when the source had none). */
    public final Instant end;
    /** All-day only: first calendar day (inclusive). */
    public final LocalDate firstDay;
    /** All-day only: day after the last calendar day (exclusive). */
    public final LocalDate endDay;

    private AgendaEvent(String id, String title, String location, String calendar, boolean allDay,
                        Instant start, Instant end, LocalDate firstDay, LocalDate endDay) {
        this.id = id;
        this.title = title;
        this.location = location;
        this.calendar = calendar;
        this.allDay = allDay;
        this.start = start;
        this.end = end;
        this.firstDay = firstDay;
        this.endDay = endDay;
    }

    /** Returns null when the start cannot be parsed (the row is skipped, never crashes the board). */
    public static AgendaEvent of(String id, String title, String startsAt, String endsAt,
                                 boolean allDay, String location, String calendar, ZoneId zone) {
        OffsetDateTime start = parse(startsAt);
        if (start == null) return null;
        OffsetDateTime end = parse(endsAt);
        String name = clean(title);
        if (name.isEmpty()) name = "Untitled event";
        if (allDay) {
            LocalDate first = start.toLocalDate();
            LocalDate last = end == null ? first.plusDays(1) : end.toLocalDate();
            if (!last.isAfter(first)) last = first.plusDays(1);
            return new AgendaEvent(clean(id), name, clean(location), clean(calendar), true,
                    first.atStartOfDay(zone).toInstant(), last.atStartOfDay(zone).toInstant(), first, last);
        }
        Instant startInstant = start.toInstant();
        Instant endInstant = end == null || end.toInstant().isBefore(startInstant) ? startInstant : end.toInstant();
        return new AgendaEvent(clean(id), name, clean(location), clean(calendar), false,
                startInstant, endInstant, null, null);
    }

    /** True when the all-day event covers {@code day}. */
    public boolean covers(LocalDate day) {
        return allDay && !day.isBefore(firstDay) && day.isBefore(endDay);
    }

    private static OffsetDateTime parse(String value) {
        if (value == null || value.isBlank()) return null;
        String text = value.trim();
        try {
            return OffsetDateTime.parse(text.endsWith("Z") ? text.substring(0, text.length() - 1) + "+00:00" : text);
        } catch (DateTimeParseException ignored) {
            try {
                return LocalDate.parse(text).atStartOfDay().atOffset(java.time.ZoneOffset.UTC);
            } catch (DateTimeParseException alsoIgnored) {
                return null;
            }
        }
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim().replace('\n', ' ');
    }
}
