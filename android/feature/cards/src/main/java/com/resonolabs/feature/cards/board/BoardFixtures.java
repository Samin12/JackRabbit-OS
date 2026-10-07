package com.resonolabs.feature.cards.board;

import android.content.Context;
import android.content.pm.ApplicationInfo;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Realistic fake board data for screenshots on a device without a connected calendar. Debug
 * (debuggable) builds only, switched by a system property read each time the tab is shown:
 * {@code adb shell setprop debug.sam.widgets.fake 1} (also {@code empty} or {@code unconfigured}).
 * Shapes match the runtime projections exactly, so the same parsing and drawing code runs.
 */
public final class BoardFixtures {
    public enum Mode { OFF, FULL, EMPTY, UNCONFIGURED }

    public static final String PROPERTY = "debug.sam.widgets.fake";
    private static final List<String[]> TASKS = new ArrayList<>();

    private BoardFixtures() {}

    public static Mode mode(Context context) {
        if ((context.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) == 0) return Mode.OFF;
        String value = property(PROPERTY).trim().toLowerCase(java.util.Locale.ROOT);
        Mode mode = switch (value) {
            case "1", "true", "full" -> Mode.FULL;
            case "empty" -> Mode.EMPTY;
            case "unconfigured" -> Mode.UNCONFIGURED;
            default -> Mode.OFF;
        };
        if (mode == Mode.OFF) synchronized (TASKS) { TASKS.clear(); }
        return mode;
    }

    /** {@code GET /v1/calendar/upcoming} shape. */
    public static JSONObject calendar(Mode mode, long nowMs, ZoneId zone) {
        JSONArray events = new JSONArray();
        if (mode == Mode.FULL) {
            ZonedDateTime now = Instant.ofEpochMilli(nowMs).atZone(zone);
            ZonedDateTime five = now.truncatedTo(ChronoUnit.MINUTES).withMinute(now.getMinute() / 5 * 5);
            LocalDate today = now.toLocalDate();
            allDay(events, "fx-birthday", "Mom's birthday", today, "Family");
            ZonedDateTime review = five.minusMinutes(25);
            timed(events, "fx-review", "Design review", review, review.plusMinutes(60), "Studio B", "Work",
                    "Walk through the widget board with the team.", "alex@example.com");
            ZonedDateTime lunch = five.plusMinutes(70);
            timed(events, "fx-coffee", "Coffee with Priya", lunch, lunch.plusMinutes(60), "Tartine Bakery", "Personal",
                    null, null);
            ZonedDateTime oneOnOne = lunch.plusMinutes(150);
            timed(events, "fx-alex", "1:1 with Alex", oneOnOne, oneOnOne.plusMinutes(30), "Zoom", "Work",
                    "Roadmap and hiring.", "alex@example.com");
            ZonedDateTime errand = oneOnOne.plusMinutes(120);
            timed(events, "fx-cleaning", "Pick up dry cleaning", errand, errand.plusMinutes(20), null, "Personal",
                    null, null);
            ZonedDateTime tomorrow = today.plusDays(1).atStartOfDay(zone);
            timed(events, "fx-gym", "Gym", tomorrow.plusHours(7), tomorrow.plusHours(8), "Equinox", "Health", null, null);
            timed(events, "fx-standup", "Team standup", tomorrow.plusHours(9).plusMinutes(30),
                    tomorrow.plusHours(9).plusMinutes(45), "Zoom", "Work", null, null);
            timed(events, "fx-dentist", "Dentist", tomorrow.plusHours(15), tomorrow.plusHours(16),
                    "Dr. Lee · 12 Main St", "Personal", "Bring the insurance card.", null);
        }
        JSONObject value = new JSONObject();
        put(value, "events", events);
        put(value, "configured", mode != Mode.UNCONFIGURED);
        return value;
    }

    /** {@code GET /v1/tasks/active} shape; completions persist for the process. */
    public static JSONObject tasks(Mode mode) {
        JSONArray tasks = new JSONArray();
        if (mode == Mode.FULL) {
            synchronized (TASKS) {
                if (TASKS.isEmpty()) {
                    String[] texts = {"Book flights to Lisbon", "Reply to Sam about the deck", "Renew passport photos",
                            "Order more coffee beans", "Call the plumber about the sink",
                            "Pick up a birthday gift for Mom", "Back up the R1 photos"};
                    for (int i = 0; i < texts.length; i++) TASKS.add(new String[]{"fx-task-" + i, texts[i]});
                }
                for (String[] task : TASKS) {
                    JSONObject item = new JSONObject();
                    put(item, "taskId", task[0]);
                    put(item, "text", task[1]);
                    put(item, "status", "open");
                    tasks.put(item);
                }
            }
        }
        JSONObject value = new JSONObject();
        put(value, "tasks", tasks);
        return value;
    }

    /** Fake {@code POST /v1/tasks/{id}/complete}. */
    public static JSONObject complete(String taskId) {
        synchronized (TASKS) { TASKS.removeIf(task -> task[0].equals(taskId)); }
        JSONObject task = new JSONObject();
        put(task, "taskId", taskId);
        put(task, "status", "completed");
        JSONObject value = new JSONObject();
        put(value, "task", task);
        put(value, "changed", true);
        return value;
    }

    /** Extra link Creations shown only when the real catalog is empty, so the tiles can be seen. */
    public static JSONArray creations(Mode mode) {
        JSONArray cards = new JSONArray();
        if (mode != Mode.FULL) return cards;
        link(cards, "fx-weather", "Weather", "Forecast for your area", "https://wttr.in/?0", "#5ca2ff");
        link(cards, "fx-wiki", "Rabbit R1 on Wikipedia", "Reference", "https://en.m.wikipedia.org/wiki/Rabbit_r1", "#c792ff");
        link(cards, "fx-news", "Hacker News", "Top stories", "https://news.ycombinator.com/", "#ffd166");
        return cards;
    }

    private static void timed(JSONArray events, String id, String title, ZonedDateTime start, ZonedDateTime end,
                              String location, String calendar, String notes, String organizer) {
        JSONObject event = base(id, title, location, calendar, notes, organizer);
        put(event, "startsAt", start.withZoneSameInstant(ZoneOffset.UTC).toOffsetDateTime().toString());
        put(event, "endsAt", end.withZoneSameInstant(ZoneOffset.UTC).toOffsetDateTime().toString());
        put(event, "allDay", false);
        events.put(event);
    }

    private static void allDay(JSONArray events, String id, String title, LocalDate day, String calendar) {
        JSONObject event = base(id, title, null, calendar, null, null);
        // Same storage convention as the runtime: UTC midnight of the floating date, end exclusive.
        put(event, "startsAt", day.atStartOfDay().atOffset(ZoneOffset.UTC).toString());
        put(event, "endsAt", day.plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC).toString());
        put(event, "allDay", true);
        events.put(event);
    }

    private static JSONObject base(String id, String title, String location, String calendar, String notes,
                                   String organizer) {
        JSONObject event = new JSONObject();
        put(event, "eventId", id);
        put(event, "calendarAccountId", "fixture");
        put(event, "title", title);
        put(event, "timezone", "UTC");
        // Like the runtime, absent values are JSON null (not omitted).
        put(event, "location", location == null ? JSONObject.NULL : location);
        put(event, "calendar", calendar);
        put(event, "organizer", organizer == null ? JSONObject.NULL : organizer);
        put(event, "description", notes == null ? JSONObject.NULL : notes);
        put(event, "editable", false);
        return event;
    }

    private static void link(JSONArray cards, String id, String title, String description, String url, String accent) {
        JSONObject card = new JSONObject();
        put(card, "creationId", id);
        put(card, "title", title);
        put(card, "description", description);
        put(card, "contentHash", id);
        put(card, "state", "enabled");
        put(card, "generation", 1);
        put(card, "sourceType", "rabbit_qr_link");
        put(card, "accent", accent);
        put(card, "entryUrl", url);
        cards.put(card);
    }

    private static void put(JSONObject target, String key, Object value) {
        try { target.put(key, value); } catch (JSONException ignored) { }
    }

    private static String property(String key) {
        try {
            Class<?> properties = Class.forName("android.os.SystemProperties");
            Object value = properties.getMethod("get", String.class, String.class).invoke(null, key, "");
            return value == null ? "" : value.toString();
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return "";
        }
    }
}
