package com.resonolabs.feature.calendar;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/** All-day dates as the runtime stores them: UTC midnight of a floating date, end exclusive. Pure Java. */
final class CalendarDates {
    private CalendarDates() {}

    /**
     * True when an all-day event is over on {@code today} (the device's date). The upcoming projection
     * keeps all-day events up to 14 h past their stored UTC end so zones behind UTC still see today's,
     * so the page has to drop yesterday's itself.
     */
    static boolean allDayOver(String startsAt, String endsAt, LocalDate today) {
        LocalDate first = date(startsAt);
        if (first == null) return false;
        LocalDate end = date(endsAt);
        if (end == null || !end.isAfter(first)) end = first.plusDays(1);
        return !end.isAfter(today);
    }

    /** The stored calendar date (never converted to another zone), or null when unparseable. */
    static LocalDate date(String value) {
        if (value == null || value.isBlank()) return null;
        String text = value.trim();
        try {
            return OffsetDateTime.parse(text.endsWith("Z") ? text.substring(0, text.length() - 1) + "+00:00" : text)
                    .toLocalDate();
        } catch (RuntimeException ignored) {
            try {
                return LocalDate.parse(text);
            } catch (RuntimeException alsoIgnored) {
                return null;
            }
        }
    }
}
