package com.resonolabs.feature.genui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.resonolabs.runtime.host.BackgroundRunSnapshot;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.time.ZoneId;
import java.util.List;

public class LiveSnapshotTest {
    private static GenCard liveCard() {
        return GenCardParser.parseShow("{\"id\":\"t3\",\"title\":\"Fix login\",\"eyebrow\":\"T3 Code\","
                + "\"live\":{\"type\":\"t3-thread\",\"threadId\":\"thr_1\"}}", 0L).card;
    }

    @Test public void snapshotBecomesProgressStepsAndLastMessage() throws Exception {
        JSONObject json = new JSONObject()
                .put("status", "active").put("terminal", false)
                .put("subtitle", "Codex • running 6m").put("phase", "Running tests").put("progress", JSONObject.NULL)
                .put("items", new JSONArray()
                        .put(new JSONObject().put("title", "Old step").put("status", "ok"))
                        .put(new JSONObject().put("title", "Read auth middleware").put("status", "ok"))
                        .put(new JSONObject().put("title", "Patch redirect").put("detail", "+18 -4").put("status", "ok"))
                        .put(new JSONObject().put("title", "Write test").put("status", "ok"))
                        .put(new JSONObject().put("title", "Run test suite").put("status", "active").put("trailing", "41/58")))
                .put("text", "All good so far.").put("nextPollMs", 2500);
        LiveSnapshot snapshot = LiveSnapshot.fromJson(json);
        assertEquals(4, snapshot.items.length);
        assertEquals("Read auth middleware", snapshot.items[0].title);
        assertEquals(2500L, snapshot.nextPollMs);

        GenCard card = liveCard();
        snapshot.applyTo(card, 77L);
        assertEquals("Codex • running 6m", card.displaySubtitle());
        assertEquals(3, card.body.size());
        assertEquals("phase", card.body.get(0).id);
        assertTrue(card.body.get(0).indeterminate());
        assertEquals("Running tests", card.body.get(0).label);
        assertEquals("steps", card.body.get(1).id);
        assertEquals(GenSchema.STATUS_ACTIVE, card.body.get(1).items[3].status);
        assertEquals("last", card.body.get(2).id);
        assertEquals(GenSchema.STYLE_MUTED, card.body.get(2).style);
        assertTrue(card.isRunningLive());
    }

    @Test public void terminalSnapshotFinishesTheCard() throws Exception {
        GenCard card = liveCard();
        LiveSnapshot.fromJson(new JSONObject().put("status", "ok").put("terminal", true).put("phase", "Merged"))
                .applyTo(card, 500L);
        assertTrue(card.terminal);
        assertEquals(500L, card.terminalAt);
        assertEquals(GenCard.State.DONE, card.state);
        assertEquals(1f, card.body.get(0).progress, 0f);
        assertTrue(!card.isRunningLive());
    }

    @Test public void backgroundRunMapsToTheSnapshotShape() {
        BackgroundRunSnapshot run = new BackgroundRunSnapshot("r1", "Research", "running", "Working", "Reading sources",
                0.4f, 3, 5, "", "", "", List.of(
                        new BackgroundRunSnapshot.TimelineEntry("Planned", ""),
                        new BackgroundRunSnapshot.TimelineEntry("Searched the web", "")));
        LiveSnapshot snapshot = BackgroundRunSource.toSnapshot(run);
        assertEquals(GenSchema.STATUS_ACTIVE, snapshot.status);
        assertEquals("Working • 5 tools", snapshot.subtitle);
        assertEquals("Reading sources", snapshot.phase);
        assertEquals(0.4f, snapshot.progress, 0.0001f);
        assertEquals(GenSchema.STATUS_ACTIVE, snapshot.items[1].status);
        BackgroundRunSnapshot failed = new BackgroundRunSnapshot("r1", "Research", "failed", "failed", "",
                0f, 3, 1, "", "", "Network down", List.of());
        LiveSnapshot end = BackgroundRunSource.toSnapshot(failed);
        assertTrue(end.terminal);
        assertEquals(GenSchema.STATUS_ERROR, end.status);
        assertEquals("Network down", end.text);
    }

    @Test public void tasksBecomeAChecklistThatAsksTheModel() throws Exception {
        GenCard card = GenCardParser.parseShow("{\"id\":\"tasks\",\"title\":\"Tasks\",\"live\":{\"type\":\"tasks\"}}", 0L).card;
        JSONArray tasks = new JSONArray()
                .put(new JSONObject().put("taskId", "t1").put("text", "Call mom").put("status", "open"))
                .put(new JSONObject().put("taskId", "t2").put("text", "Old").put("status", "completed"))
                .put(new JSONObject().put("taskId", "t3").put("text", "Pay rent").put("status", "open"));
        TasksSource.apply(card, tasks);
        GenBlock list = card.body.get(0);
        assertEquals(GenBlock.Type.CHECKLIST, list.type);
        assertEquals(2, list.items.length);
        assertEquals("t3", list.items[1].ref);
        assertEquals(TasksSource.ROW_SAY, list.rowSay);
        assertEquals("2 open", card.liveSubtitle);
        TasksSource.apply(card, new JSONArray());
        assertEquals(GenBlock.Type.TEXT, card.body.get(0).type);
    }

    @Test public void calendarNextShowsTheUpcomingEvent() throws Exception {
        long now = java.time.Instant.parse("2026-10-07T20:05:00Z").toEpochMilli();
        JSONArray events = new JSONArray()
                .put(new JSONObject().put("title", "Standup").put("startsAt", "2026-10-07T19:00:00+00:00")
                        .put("endsAt", "2026-10-07T19:15:00+00:00"))
                .put(new JSONObject().put("title", "Design review").put("startsAt", "2026-10-07T20:30:00+00:00")
                        .put("endsAt", "2026-10-07T21:00:00+00:00").put("location", "Zoom"))
                .put(new JSONObject().put("title", "Dinner").put("startsAt", "2026-10-08T00:00:00Z"));
        GenCard card = GenCardParser.parseShow("{\"id\":\"next\",\"title\":\"Next\",\"live\":{\"type\":\"calendar-next\"}}", 0L).card;
        CalendarNextSource.apply(card, CalendarNextSource.parse(events), now, ZoneId.of("America/New_York"));
        assertEquals("Design review", card.displayTitle());
        assertEquals("25m", card.liveTrailing);
        assertEquals("Zoom • 4:30 PM", card.liveSubtitle);
        assertEquals(2, card.body.get(0).items.length);
        assertEquals("8:00 PM", card.body.get(0).items[1].trailing);

        CalendarNextSource.apply(card, CalendarNextSource.parse(events), now + 30 * 60_000L, ZoneId.of("America/New_York"));
        assertEquals("Now", card.liveTrailing);
        assertTrue(card.liveSubtitle.startsWith("Zoom • Now • until 5:00 PM"));

        CalendarNextSource.apply(card, CalendarNextSource.parse(new JSONArray()), now, ZoneId.of("America/New_York"));
        assertEquals("Nothing coming up", card.displayTitle());
        assertNull(card.liveTrailing);
    }

    @Test public void relativeTimes() {
        CalendarNextSource.Event event = new CalendarNextSource.Event("x", 10_000_000L, 11_000_000L, null, false);
        assertEquals("Now", CalendarNextSource.relative(event, 10_000_000L));
        assertEquals("1m", CalendarNextSource.relative(event, 10_000_000L - 30_000L));
        assertEquals("2h 5m", CalendarNextSource.relative(event, 10_000_000L - 125 * 60_000L));
        assertEquals("12h", CalendarNextSource.relative(event, 10_000_000L - 12 * 3_600_000L));
    }
}
