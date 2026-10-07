package com.resonolabs.feature.cards.board;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Device-zone, 12/24-hour aware labels for agenda rows. Pure Java. */
public final class AgendaText {
    private final ZoneId zone;
    private final boolean use24Hour;
    private final DateTimeFormatter hourMinute;
    private final DateTimeFormatter meridiem;
    private final DateTimeFormatter shortDay;

    public AgendaText(ZoneId zone, boolean use24Hour, Locale locale) {
        this.zone = zone;
        this.use24Hour = use24Hour;
        this.hourMinute = DateTimeFormatter.ofPattern(use24Hour ? "H:mm" : "h:mm", locale);
        this.meridiem = DateTimeFormatter.ofPattern("a", locale);
        this.shortDay = DateTimeFormatter.ofPattern("EEE, MMM d", locale);
    }

    /** Clock face for a time column: {"11:30", "AM"} (second part empty on 24-hour devices). */
    public String[] clock(Instant instant) {
        ZonedDateTime time = instant.atZone(zone);
        return new String[]{hourMinute.format(time), use24Hour ? "" : meridiem.format(time)};
    }

    /** "10:00 – 11:00 AM", "11:30 AM – 12:30 PM", "10:00 AM" for a point event, "All day". */
    public String range(AgendaEvent event) {
        if (event.allDay) return "All day";
        ZonedDateTime start = event.start.atZone(zone);
        ZonedDateTime end = event.end.atZone(zone);
        if (use24Hour) {
            return event.end.equals(event.start) ? hourMinute.format(start)
                    : hourMinute.format(start) + " – " + hourMinute.format(end);
        }
        String startMeridiem = meridiem.format(start);
        if (event.end.equals(event.start)) return hourMinute.format(start) + " " + startMeridiem;
        String endMeridiem = meridiem.format(end);
        String first = startMeridiem.equals(endMeridiem) && start.toLocalDate().equals(end.toLocalDate())
                ? hourMinute.format(start) : hourMinute.format(start) + " " + startMeridiem;
        return first + " – " + hourMinute.format(end) + " " + endMeridiem;
    }

    /** "Fri, Oct 9". */
    public String day(LocalDate day) {
        return shortDay.format(day);
    }

    public ZoneId zone() { return zone; }
}
