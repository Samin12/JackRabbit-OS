package com.resonolabs.feature.t3;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public final class T3GlanceTest {
    private static final long NOW = java.time.Instant.parse("2026-10-07T12:10:00Z").toEpochMilli();

    private static JSONObject thread(String id, String status, String updatedAt, boolean unread) throws Exception {
        return new JSONObject().put("id", id).put("projectId", "p1").put("projectTitle", "SamRabbit")
                .put("title", "Thread " + id).put("status", status).put("statusLabel", JSONObject.NULL)
                .put("updatedAt", updatedAt).put("completedAt", JSONObject.NULL).put("unread", unread)
                .put("model", JSONObject.NULL).put("phase", JSONObject.NULL).put("progress", JSONObject.NULL);
    }

    private static JSONObject snapshot(JSONArray threads, int needsYou, int working, int error) throws Exception {
        return new JSONObject().put("connected", true).put("revision", 1790000123L)
                .put("updatedAt", "2026-10-07T12:09:00Z")
                .put("counts", new JSONObject().put("needsYou", needsYou).put("working", working)
                        .put("done", 0).put("error", error))
                .put("projects", new JSONArray()).put("threads", threads);
    }

    @Test public void needsYouLeadThenWorkingThenFreshResults() throws Exception {
        JSONArray threads = new JSONArray()
                .put(thread("done-read", "done", "2026-10-07T12:09:00Z", false))
                .put(thread("work-old", "working", "2026-10-07T11:00:00Z", false))
                .put(thread("input", "needs-input", "2026-10-07T12:08:00Z", false))
                .put(thread("fresh", "done", "2026-10-07T12:05:00Z", true))
                .put(thread("work-new", "working", "2026-10-07T12:06:00Z", false)
                        .put("phase", "Running tests").put("progress", 0.4))
                .put(thread("approval", "needs-approval", "2026-10-07T10:00:00Z", false));
        T3Glance glance = T3Glance.from(snapshot(threads, 2, 2, 0), 3);
        assertTrue(glance.connected);
        assertEquals(1790000123L, glance.revision);
        assertEquals(3, glance.rows.size());
        assertEquals("approval", glance.rows.get(0).id); // approvals before questions, even if older
        assertEquals("input", glance.rows.get(1).id);
        assertEquals("work-new", glance.rows.get(2).id);
        assertEquals(2, glance.more); // work-old + fresh did not fit; a read result never counts
        assertFalse(glance.lastOnly);

        T3Glance.Row approval = glance.rows.get(0);
        assertTrue(approval.needsYou);
        assertEquals("Needs approval", approval.label);
        assertEquals(T3Glance.AMBER, approval.color);
        assertEquals("SamRabbit · 2h", approval.meta(NOW));

        T3Glance.Row working = T3Glance.from(snapshot(threads, 2, 2, 0), 6).rows.get(2);
        assertEquals("Running tests", working.label);
        assertEquals(0.4, working.progress, 1e-9);
        assertEquals(T3Glance.BLUE, working.color);
    }

    @Test public void freshFailuresAndUnreadResultsFillRemainingRows() throws Exception {
        JSONArray threads = new JSONArray()
                .put(thread("fresh", "done", "2026-10-07T12:05:00Z", true))
                .put(thread("failed", "error", "2026-10-07T12:01:00Z", false))
                .put(thread("old", "done", "2026-10-07T09:00:00Z", false));
        T3Glance glance = T3Glance.from(snapshot(threads, 0, 0, 1), 3);
        assertEquals(2, glance.rows.size());
        assertEquals("failed", glance.rows.get(0).id);
        assertTrue(glance.rows.get(0).failed);
        assertEquals("fresh", glance.rows.get(1).id);
        assertEquals(T3Glance.GREEN, glance.rows.get(1).color);
        assertEquals("Done", glance.rows.get(1).label);
        assertEquals(0, glance.more);
    }

    @Test public void caughtUpOffersTheLatestThreadQuietly() throws Exception {
        JSONArray threads = new JSONArray()
                .put(thread("older", "done", "2026-10-07T09:00:00Z", false))
                .put(thread("latest", "done", "2026-10-07T11:10:00Z", false));
        T3Glance glance = T3Glance.from(snapshot(threads, 0, 0, 0), 3);
        assertTrue(glance.lastOnly);
        assertEquals(1, glance.rows.size());
        assertEquals("latest", glance.rows.get(0).id);
        assertTrue(glance.rows.get(0).quiet);
        assertEquals(T3Glance.QUIET, glance.rows.get(0).color);
        assertEquals(0, glance.more);
        List<T3Glance.Part> headline = glance.headline();
        assertEquals(1, headline.size());
        assertEquals("All caught up", headline.get(0).text);
    }

    @Test public void headlineUsesTheRuntimeCounts() throws Exception {
        T3Glance glance = T3Glance.from(snapshot(new JSONArray(), 2, 1, 0), 3);
        List<T3Glance.Part> parts = glance.headline();
        assertEquals("2 need you", parts.get(0).text);
        assertEquals(T3Glance.AMBER, parts.get(0).color);
        assertEquals("1 working", parts.get(1).text);
        assertEquals(T3Glance.BLUE, parts.get(1).color);
        assertTrue(glance.rows.isEmpty());
        assertEquals(0, glance.threadCount);
    }

    @Test public void disconnectedAndMalformedSnapshotsAreSafe() throws Exception {
        T3Glance off = T3Glance.from(new JSONObject().put("connected", false).put("threads", new JSONArray()), 3);
        assertFalse(off.connected);
        assertTrue(off.rows.isEmpty());
        T3Glance junk = T3Glance.from(new JSONObject().put("threads", new JSONArray().put("x").put(JSONObject.NULL)
                .put(new JSONObject().put("title", "no id"))), 3);
        assertTrue(junk.rows.isEmpty());
        assertEquals(-1L, T3Glance.from(null, 3).revision);
    }
}
