package com.resonolabs.runtime.host;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/** conversationId / seq lifecycle across always-on reconnects, and the event shapes. */
public final class ConversationTimelineTest {
    private final List<JSONObject> events = new ArrayList<>();
    private final List<Boolean> urgent = new ArrayList<>();
    private final ArrayDeque<String> ids = new ArrayDeque<>();
    private long now = 1_759_876_543_210L;
    private ConversationTimeline timeline;

    @Before public void setUp() {
        ids.add("c_aaaaaaaaaaaaaaaaaaaa");
        ids.add("c_bbbbbbbbbbbbbbbbbbbb");
        timeline = new ConversationTimeline((event, flag) -> {
            events.add(event);
            urgent.add(flag);
        }, () -> now, ids::poll);
    }

    private JSONObject last() {
        return events.get(events.size() - 1);
    }

    @Test public void idsAreConversationScopedAndKeptAcrossReconnects() throws Exception {
        String first = timeline.start("user");
        assertEquals("c_aaaaaaaaaaaaaaaaaaaa", first);
        JSONObject started = last();
        assertEquals("conversation.started", started.getString("type"));
        assertEquals(first + ":1", started.getString("id"));
        assertEquals(1, started.getLong("seq"));
        assertFalse(started.has("sessionId"));

        timeline.setSessionId("aaaaaaaaaaaaaaaaaaaaaaa1");
        timeline.sessionConnected("aaaaaaaaaaaaaaaaaaaaaaa1", false);
        assertEquals("aaaaaaaaaaaaaaaaaaaaaaa1", last().getString("sessionId"));
        assertFalse(last().getBoolean("reconnect"));
        timeline.userMessage("what's on my screen", "conversation.item.input_audio_transcription.completed");

        // always-on: the provider session drops and comes back with a new runtime session id
        timeline.sessionEnded("peer-closed");
        assertTrue(urgent.get(urgent.size() - 1));
        assertEquals("aaaaaaaaaaaaaaaaaaaaaaa1", last().getString("sessionId"));
        assertEquals("", timeline.sessionId());
        assertEquals(first, timeline.conversationId());
        timeline.sessionConnected("bbbbbbbbbbbbbbbbbbbbbbb2", true);
        assertTrue(last().getBoolean("reconnect"));
        assertEquals(first, last().getString("conversationId"));
        assertEquals(5, last().getLong("seq"));
        assertEquals(first + ":5", last().getString("id"));

        timeline.end("user_stop");
        assertEquals("conversation.ended", last().getString("type"));
        assertEquals("user_stop", last().getString("reason"));
        assertTrue(urgent.get(urgent.size() - 1));
        assertFalse(timeline.active());
        assertNull(timeline.conversationId());

        // between conversations nothing is emitted
        int count = events.size();
        assertNull(timeline.userMessage("hello?", "debug.say"));
        assertEquals(count, events.size());

        // the next user start is a new conversation, seq starts over
        String second = timeline.start("user");
        assertNotEquals(first, second);
        assertEquals(1, last().getLong("seq"));
        assertEquals(second + ":1", last().getString("id"));
    }

    @Test public void aUserStartDuringAConversationEndsIt() throws Exception {
        String first = timeline.start("user");
        timeline.start("user");
        assertEquals("conversation.ended", events.get(1).getString("type"));
        assertEquals("restarted", events.get(1).getString("reason"));
        assertEquals(first, events.get(1).getString("conversationId"));
        assertEquals("conversation.started", events.get(2).getString("type"));
    }

    @Test public void commonFields() throws Exception {
        timeline.start("user");
        JSONObject event = timeline.userMessage("  hi  ", "debug.say");
        assertEquals("message.user", event.getString("type"));
        assertEquals("hi", event.getString("text"));
        assertEquals("debug.say", event.getString("eventType"));
        assertEquals("user", event.getString("origin"));
        assertEquals(now, event.getLong("at"));
        assertNull(timeline.userMessage("   ", "debug.say"));
    }

    @Test public void assistantDeltasAreThrottledAndCarryTheWholeDraft() throws Exception {
        timeline.start("user");
        String message = timeline.nextMessageId();
        assertEquals("a_1", message);
        assertEquals("You", timeline.assistantDelta(message, "You").getString("text"));
        now += 100;
        assertNull(timeline.assistantDelta(message, "You have"));
        now += 200;
        JSONObject delta = timeline.assistantDelta(message, "You have Chrome");
        assertEquals("You have Chrome", delta.getString("text"));
        assertEquals("a_1", delta.getString("messageId"));
        assertEquals("model", delta.getString("origin"));
        JSONObject done = timeline.assistantDone(message, "You have Chrome open.");
        assertFalse(done.getBoolean("interrupted"));
        assertEquals("message.assistant.done", done.getString("type"));
        // a new message resets the throttle
        String next = timeline.nextMessageId();
        assertEquals("a_2", next);
        now += 10;
        assertTrue(timeline.assistantDelta(next, "Sure") != null);
    }

    @Test public void interruptions() throws Exception {
        timeline.start("user");
        String message = timeline.nextMessageId();
        JSONObject event = timeline.assistantInterrupted(message, "Let me tell you about");
        assertEquals("message.assistant.interrupted", event.getString("type"));
        assertTrue(event.getBoolean("interrupted"));
        assertEquals("user", event.getString("origin"));
        assertNull(timeline.assistantInterrupted(null, "x"));
    }

    @Test public void hostAndUiEvents() throws Exception {
        timeline.start("user");
        JSONObject t3 = timeline.hostT3Update("[T3 update] done", 812, "t3.thread.finished");
        assertEquals("host.t3_update", t3.getString("type"));
        assertEquals(812, t3.getLong("announcementId"));
        assertEquals("host", t3.getString("origin"));
        assertFalse(timeline.hostT3Update("[T3 update] x", 0, "t3.thread.error").has("announcementId"));
        assertEquals("host.note", timeline.hostNote("Host note: Talk", "host").getString("type"));
        assertEquals("ui.event", timeline.uiEvent("[UI event] tapped").getString("type"));
        JSONObject completion = timeline.hostCompletion("run-1", "x".repeat(20_000));
        assertEquals(ConversationTimeline.MAX_HOST_TEXT, completion.getString("text").length());
        JSONObject card = timeline.card("card.shown", new JSONObject().put("id", "groceries"), "model");
        assertEquals("groceries", card.getString("cardId"));
        assertEquals("groceries", card.getJSONObject("card").getString("id"));
    }

    @Test public void imagesAreUrgentAndNameTheirBlob() throws Exception {
        timeline.start("user");
        JSONObject image = timeline.image("sha256:" + "a".repeat(64), "image/jpeg", 960, 720, 148_211,
                "camera", "camera.jpg");
        assertTrue(urgent.get(urgent.size() - 1));
        assertEquals("image", image.getString("type"));
        assertEquals("camera", image.getString("source"));
        assertEquals("user", image.getString("origin"));
        assertEquals(960, image.getInt("width"));
        assertEquals(148_211, image.getInt("bytes"));
        assertEquals("camera.jpg", image.getString("caption"));
        JSONObject unsized = timeline.image("sha256:" + "b".repeat(64), "image/png", 0, 0, 0, "mac_screenshot", null);
        assertFalse(unsized.has("width"));
        assertEquals("host", unsized.getString("origin"));
        assertNull(timeline.image(null, "image/jpeg", 1, 1, 1, "camera", null));
    }

    @Test public void randomIdsHaveTheContractShape() {
        String id = ConversationTimeline.randomId();
        assertTrue(id.matches("c_[0-9a-f]{20}"));
        assertNotEquals(id, ConversationTimeline.randomId());
    }
}
