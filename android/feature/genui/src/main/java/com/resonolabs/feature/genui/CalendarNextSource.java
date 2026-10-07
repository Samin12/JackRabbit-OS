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
 */
final class CalendarNextSource extends PollingLiveSource {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("h:mm a", Locale.US);
    private static final DateTimeFormatter DAY_TIME = DateTimeFormatter.ofPattern("EEE h:mm a", Locale.US);

    static final class Event {
        final String title;
        final long start;
        final long end;
        final String location;
        final boolean allDay;

        Event(String title, long start, long end, String location, boolean allDay) {
            this.title = title;
            this.start = start;
            this.end = end;
            this.location = location;
            this.allDay = allDay;
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
                events = parse(value.optJSONArray("events"));
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
        ArrayList<Event> out = new ArrayList<>();
        for (int index = 0; items != null && index < items.length(); index++) {
            JSONObject item = items.optJSONObject(index);
            if (item == null) continue;
            long start = instant(item.opt("startsAt"));
            if (start == Long.MIN_VALUE) continue;
            long end = instant(item.opt("endsAt"));
            String title = GenCardParser.clean(GenCardParser.value(item, "title"), GenSchema.ROW_TITLE, null, null);
            String location = GenCardParser.clean(GenCardParser.value(item, "location"), 40, null, null);
            out.add(new Event(title == null ? "Untitled event" : title, start,
                    end == Long.MIN_VALUE ? start + 30L * 60_000L : end, location, item.optBoolean("allDay", false)));
        }
        out.sort((a, b) -> Long.compare(a.start, b.start));
        return out;
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

    /** Pure: writes the next event (and up to 3 upcoming rows) into the card. */
    static void apply(GenCard card, ArrayList<Event> events, long nowWall, ZoneId zone) {
        card.body.clear();
        card.liveStatus = GenSchema.STATUS_ACTIVE;
        card.liveNote = null;
        ArrayList<Event> upcoming = new ArrayList<>();
        for (Event event : events) if (event.end > nowWall) upcoming.add(event);
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
        card.liveTitle = next.title;
        String when = when(next, nowWall, zone);
        card.liveSubtitle = next.location != null ? next.location + " • " + when : when;
        card.liveTrailing = relative(next, nowWall);
        GenBlock list = new GenBlock(GenBlock.Type.LIST);
        list.id = "events";
        int count = Math.min(3, upcoming.size());
        list.items = new GenRow[count];
        for (int index = 0; index < count; index++) {
            Event event = upcoming.get(index);
            GenRow row = new GenRow();
            row.title = event.title;
            row.trailing = event.allDay ? "All day" : clock(event.start, zone, nowWall);
            row.detail = event.location;
            row.status = event.start <= nowWall ? GenSchema.STATUS_ACTIVE
                    : index == 0 ? GenSchema.STATUS_IDLE : GenSchema.STATUS_NONE;
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

    private static String when(Event event, long nowWall, ZoneId zone) {
        if (event.start <= nowWall) {
            return "Now • until " + TIME.format(Instant.ofEpochMilli(event.end).atZone(zone));
        }
        if (event.allDay) return "All day";
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
