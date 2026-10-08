package com.resonolabs.feature.genui;

import com.resonolabs.runtime.host.CalendarEventClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Locale;

/**
 * The next calendar event: pill title = event, big trailing value = "25m" / "Now", subtitle =
 * place and time. Re-polls every 60 s (5 min hidden) and recomputes relative time every 15 s.
 *
 * <p>All-day events are floating calendar dates: the runtime stores them as UTC midnight of the
 * date (end exclusive) and keeps them 14 h past that stored end. They are matched by local date
 * (like the Cards board's Up next), never by the stored instants, which in New York end at 8 PM
 * the day before. Optional fields arrive as JSON null and are read null-safely.
 */
final class CalendarNextSource extends PollingLiveSource {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("h:mm a", Locale.US);
    private static final DateTimeFormatter DAY_TIME = DateTimeFormatter.ofPattern("EEE h:mm a", Locale.US);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE", Locale.US);

    static final class Event {
        final String title;
        /** Timed: start instant. All-day: local midnight starting {@link #firstDay}. */
        final long start;
        /** Timed: end instant. All-day: local midnight starting {@link #endDay}. */
        final long end;
        final String location;
        final boolean allDay;
        /** All-day only: first calendar day (inclusive) and the day after the last (exclusive). */
        final LocalDate firstDay;
        final LocalDate endDay;

        Event(String title, long start, long end, String location, boolean allDay) {
            this(title, start, end, location, allDay, null, null);
        }

        Event(String title, long start, long end, String location, boolean allDay, LocalDate firstDay,
              LocalDate endDay) {
            this.title = title;
            this.start = start;
            this.end = end;
            this.location = location;
            this.allDay = allDay;
            this.firstDay = firstDay;
            this.endDay = endDay;
        }

        boolean covers(LocalDate day) {
            return allDay && firstDay != null && !day.isBefore(firstDay) && day.isBefore(endDay);
        }

        /** Not over yet: all-day by local date, timed by its end instant. */
        boolean upcoming(long nowWall, LocalDate today) {
            return allDay && firstDay != null ? endDay.isAfter(today) : end > nowWall;
        }
    }

    private final Runnable relative = this::refreshRelative;
    private CalendarEventClient client;
    private ArrayList<Event> events = new ArrayList<>();

    CalendarNextSource(LiveSourceRegistry registry, GenCard card) {
        super(registry, card, 60_000L, 300_000L);
    }

    @Override protected void fetch(int token) {
        if (client == null) client = new CalendarEventClient();
        client.loadUpcoming(registry.context(), new CalendarEventClient.Callback() {
            @Override public void onEvents(JSONObject value) {
                events = parse(value.optJSONArray("events"), ZoneId.systemDefault());
                apply(card, events, System.currentTimeMillis(), ZoneId.systemDefault());
                succeeded(token, 0L);
                scheduleRelative();
            }

            @Override public void onFailure() {
                failed(token);
            }
        });
    }

    private void scheduleRelative() {
        registry.handler().removeCallbacks(relative);
        if (running()) registry.handler().postDelayed(relative, 15_000L);
    }

    private void refreshRelative() {
        if (!running()) return;
        String before = card.liveTrailing + "|" + card.liveSubtitle + "|" + card.liveTitle;
        apply(card, events, System.currentTimeMillis(), ZoneId.systemDefault());
        String after = card.liveTrailing + "|" + card.liveSubtitle + "|" + card.liveTitle;
        if (!before.equals(after)) publish();
        scheduleRelative();
    }

    @Override protected void closeClient() {
        registry.handler().removeCallbacks(relative);
        if (client != null) client.close();
        client = null;
    }

    static ArrayList<Event> parse(JSONArray items) {
        return parse(items, ZoneId.systemDefault());
    }

    static ArrayList<Event> parse(JSONArray items, ZoneId zone) {
        ArrayList<Event> out = new ArrayList<>();
        for (int index = 0; items != null && index < items.length(); index++) {
            JSONObject item = items.optJSONObject(index);
            if (item == null) continue;
            Object startsAt = GenCardParser.value(item, "startsAt");
            Object endsAt = GenCardParser.value(item, "endsAt");
            String title = GenCardParser.clean(GenCardParser.value(item, "title"), GenSchema.ROW_TITLE, null, null);
            String location = GenCardParser.clean(GenCardParser.value(item, "location"), 40, null, null);
            if (title == null) title = "Untitled event";
            if (Boolean.TRUE.equals(GenCardParser.value(item, "allDay"))) {
                LocalDate first = date(startsAt);
                if (first == null) continue;
                LocalDate last = date(endsAt);
                if (last == null || !last.isAfter(first)) last = first.plusDays(1);
                out.add(new Event(title, first.atStartOfDay(zone).toInstant().toEpochMilli(),
                        last.atStartOfDay(zone).toInstant().toEpochMilli(), location, true, first, last));
                continue;
            }
            long start = instant(startsAt);
            if (start == Long.MIN_VALUE) continue;
            long end = instant(endsAt);
            out.add(new Event(title, start, end == Long.MIN_VALUE || end < start ? start + 30L * 60_000L : end,
                    location, false));
        }
        out.sort((a, b) -> Long.compare(a.start, b.start));
        return out;
    }

    /** The stored calendar date of an all-day bound ("2026-10-07T00:00:00+00:00" or "2026-10-07"). */
    static LocalDate date(Object value) {
        if (!(value instanceof String text) || text.isBlank()) return null;
        String trimmed = text.trim();
        try {
            return OffsetDateTime.parse(trimmed.replace(" ", "T").replace("Z", "+00:00")).toLocalDate();
        } catch (RuntimeException ignored) { }
        try {
            return LocalDate.parse(trimmed.length() >= 10 ? trimmed.substring(0, 10) : trimmed);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    static long instant(Object value) {
        if (!(value instanceof String text) || text.isBlank()) return Long.MIN_VALUE;
        try {
            return OffsetDateTime.parse(text.trim().replace(" ", "T")).toInstant().toEpochMilli();
        } catch (RuntimeException ignored) { }
        try {
            return Instant.parse(text.trim()).toEpochMilli();
        } catch (RuntimeException ignored) { }
        try {
            return LocalDate.parse(text.trim()).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
        } catch (RuntimeException ignored) {
            return Long.MIN_VALUE;
        }
    }

    /**
     * Pure: writes the next event (and up to 3 upcoming rows) into the card. The headline is the
     * next timed event; all-day events lead the rows ("All day") and only head the card when no
     * timed event is left.
     */
    static void apply(GenCard card, ArrayList<Event> events, long nowWall, ZoneId zone) {
        card.body.clear();
        card.liveStatus = GenSchema.STATUS_ACTIVE;
        card.liveNote = null;
        LocalDate today = Instant.ofEpochMilli(nowWall).atZone(zone).toLocalDate();
        ArrayList<Event> upcoming = new ArrayList<>();
        for (Event event : events) if (event.upcoming(nowWall, today)) upcoming.add(event);
        if (upcoming.isEmpty()) {
            card.liveTitle = "Nothing coming up";
            card.liveSubtitle = "Your calendar is clear";
            card.liveTrailing = null;
            GenBlock text = new GenBlock(GenBlock.Type.TEXT);
            text.id = "events";
            text.style = GenSchema.STYLE_MUTED;
            text.text = "No upcoming events on your connected calendars.";
            card.body.add(text);
            return;
        }
        Event next = upcoming.get(0);
        for (Event event : upcoming) {
            if (!event.allDay) {
                next = event;
                break;
            }
        }
        card.liveTitle = next.title;
        String when = when(next, nowWall, zone);
        card.liveSubtitle = next.location != null ? next.location + " • " + when : when;
        card.liveTrailing = relative(next, nowWall, zone);
        GenBlock list = new GenBlock(GenBlock.Type.LIST);
        list.id = "events";
        int count = Math.min(3, upcoming.size());
        list.items = new GenRow[count];
        for (int index = 0; index < count; index++) {
            Event event = upcoming.get(index);
            GenRow row = new GenRow();
            row.title = event.title;
            row.trailing = event.allDay ? allDayLabel(event, today) : clock(event.start, zone, nowWall);
            row.detail = event.location;
            row.status = event.allDay ? GenSchema.STATUS_NONE
                    : event.start <= nowWall ? GenSchema.STATUS_ACTIVE
                    : event == next ? GenSchema.STATUS_IDLE : GenSchema.STATUS_NONE;
            list.items[index] = row;
        }
        card.body.add(list);
    }

    static String relative(Event event, long nowWall) {
        if (event.start <= nowWall) return "Now";
        long minutes = (event.start - nowWall + 59_999L) / 60_000L;
        if (minutes < 60) return minutes + "m";
        long hours = minutes / 60;
        if (hours < 10) return hours + "h " + (minutes % 60) + "m";
        if (hours < 48) return hours + "h";
        return (hours / 24) + "d";
    }

    static String relative(Event event, long nowWall, ZoneId zone) {
        if (!event.allDay || event.firstDay == null) return relative(event, nowWall);
        LocalDate today = Instant.ofEpochMilli(nowWall).atZone(zone).toLocalDate();
        if (event.covers(today)) return "Today";
        if (event.firstDay.equals(today.plusDays(1))) return "Tmrw";
        return DAY.format(event.firstDay);
    }

    /** "All day" today, otherwise the day it is on ("Tmrw", "Fri"). */
    private static String allDayLabel(Event event, LocalDate today) {
        if (event.firstDay == null || event.covers(today)) return "All day";
        if (event.firstDay.equals(today.plusDays(1))) return "Tmrw";
        return DAY.format(event.firstDay);
    }

    private static String when(Event event, long nowWall, ZoneId zone) {
        if (event.allDay) {
            LocalDate today = Instant.ofEpochMilli(nowWall).atZone(zone).toLocalDate();
            String day = allDayLabel(event, today);
            return "All day".equals(day) ? "All day" : "All day • " + day;
        }
        if (event.start <= nowWall) {
            return "Now • until " + TIME.format(Instant.ofEpochMilli(event.end).atZone(zone));
        }
        return clock(event.start, zone, nowWall);
    }

    private static String clock(long millis, ZoneId zone, long nowWall) {
        ZonedDateTime time = Instant.ofEpochMilli(millis).atZone(zone);
        LocalDate today = Instant.ofEpochMilli(nowWall).atZone(zone).toLocalDate();
        if (time.toLocalDate().equals(today)) return TIME.format(time);
        if (time.toLocalDate().equals(today.plusDays(1))) return "Tmrw " + TIME.format(time);
        return DAY_TIME.format(time);
    }
}
