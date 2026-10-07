package com.resonolabs.feature.cards.board;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

public class JournalStatusTest {
    private static JSONObject status(boolean connected, boolean reconnect, int pending, int failed, JSONObject today)
            throws Exception {
        return new JSONObject().put("connected", connected).put("autoSessions", true).put("pending", pending)
                .put("failed", failed).put("lastSentAt", JSONObject.NULL).put("needsReconnect", reconnect)
                .put("today", today == null ? JSONObject.NULL : today);
    }

    private static JSONObject today(int sent, int queued) throws Exception {
        return new JSONObject().put("date", "2026-10-07").put("sent", sent).put("queued", queued).put("failed", 0);
    }

    @Test public void connectedShowsTodaysSentAndQueued() throws Exception {
        JournalStatus status = JournalStatus.from(status(true, false, 1, 0, today(3, 1)));
        assertEquals(JournalStatus.State.CONNECTED, status.state);
        assertTrue(status.canWrite());
        assertEquals("3 sent · 1 queued", status.todayLine());
        assertEquals("Heptabase", status.chip());
        assertEquals("Nothing yet today", JournalStatus.from(status(true, false, 0, 0, today(0, 0))).todayLine());
        assertEquals("2 sent", JournalStatus.from(status(true, false, 0, 0, today(2, 0))).todayLine());
    }

    @Test public void reconnectStillTakesNotesAndCountsEverythingWaiting() throws Exception {
        // Yesterday's entry is still waiting too: while paused, every queued entry is worth showing.
        JournalStatus status = JournalStatus.from(status(true, true, 3, 0, today(1, 2)));
        assertEquals(JournalStatus.State.RECONNECT, status.state);
        assertTrue(status.canWrite());
        assertEquals("1 sent · 3 queued", status.todayLine());
        assertEquals("Reconnect", status.chip());
        assertEquals("3 queued", status.reconnectChip());
        assertEquals("Reconnect", JournalStatus.from(status(true, true, 0, 0, today(0, 0))).reconnectChip());
    }

    @Test public void notConnectedOffersNoNote() throws Exception {
        JournalStatus status = JournalStatus.from(status(false, false, 0, 0, today(0, 0)));
        assertEquals(JournalStatus.State.DISCONNECTED, status.state);
        assertFalse(status.canWrite());
        assertEquals("Not connected", status.chip());
        assertEquals(JournalStatus.State.DISCONNECTED, JournalStatus.from(new JSONObject()).state);
        assertEquals(JournalStatus.State.DISCONNECTED, JournalStatus.from(null).state);
    }

    @Test public void olderRuntimeWithoutTodayFallsBackToPending() throws Exception {
        assertEquals("2 queued · 1 failed", JournalStatus.from(status(true, false, 2, 1, null)).todayLine());
        assertEquals("Ready for a note", JournalStatus.from(status(true, false, 0, 0, null)).todayLine());
    }

    @Test public void noteResultsReadPlainly() throws Exception {
        assertEquals("Added to today's journal",
                JournalStatus.noteSaved(new JSONObject().put("recorded", true).put("state", "sent")));
        assertEquals("Saved · sends when Heptabase is reachable",
                JournalStatus.noteSaved(new JSONObject().put("recorded", true).put("state", "queued")));
        assertEquals("Saved · sends when Heptabase is reachable",
                JournalStatus.noteSaved(new JSONObject().put("state", JSONObject.NULL)));
        assertEquals("Heptabase isn't connected", JournalStatus.noteFailed(409, "heptabase_not_connected"));
        assertEquals("Runtime offline · note not saved", JournalStatus.noteFailed(0, "runtime_unavailable"));
        assertEquals("That note couldn't be saved", JournalStatus.noteFailed(400, "invalid_request"));
    }
}
