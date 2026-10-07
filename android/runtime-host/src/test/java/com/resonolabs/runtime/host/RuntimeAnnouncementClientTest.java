package com.resonolabs.runtime.host;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class RuntimeAnnouncementClientTest {
    @Test public void cursorFollowsDeliveredItems() {
        assertEquals(12L, RuntimeAnnouncementClient.nextCursor(10L, 12L, 2));
        // Never moves backwards because of a batch.
        assertEquals(10L, RuntimeAnnouncementClient.nextCursor(10L, 9L, 1));
    }

    @Test public void emptyPollKeepsOrResyncsTheCursor() {
        assertEquals(10L, RuntimeAnnouncementClient.nextCursor(10L, 10L, 0));
        // Runtime database reset (our cursor is ahead of its newest id): take its newest id.
        assertEquals(3L, RuntimeAnnouncementClient.nextCursor(40L, 3L, 0));
        assertEquals(40L, RuntimeAnnouncementClient.nextCursor(40L, -1L, 0));
    }

    @Test public void backoffIsCappedAtOneMinute() {
        assertEquals(2_000L, RuntimeAnnouncementClient.backoffMs(0));
        assertEquals(5_000L, RuntimeAnnouncementClient.backoffMs(1));
        assertEquals(60_000L, RuntimeAnnouncementClient.backoffMs(4));
        assertEquals(60_000L, RuntimeAnnouncementClient.backoffMs(99));
    }

    @Test public void onlyRecentUnacknowledgedItemsAreReplayedOnStart() {
        long now = java.time.Instant.parse("2026-10-07T21:30:00Z").toEpochMilli();
        assertTrue(RuntimeAnnouncementClient.replayable("2026-10-07T21:25:00.123Z", now));
        assertTrue(RuntimeAnnouncementClient.replayable("2026-10-07T21:20:00Z", now));
        // Older than the 10 min window: history, not news.
        assertFalse(RuntimeAnnouncementClient.replayable("2026-10-07T21:19:59Z", now));
        assertFalse(RuntimeAnnouncementClient.replayable("2026-10-07T09:00:00Z", now));
        // Clock far in the future, missing or malformed: dropped.
        assertFalse(RuntimeAnnouncementClient.replayable("2026-10-07T23:00:00Z", now));
        assertFalse(RuntimeAnnouncementClient.replayable("", now));
        assertFalse(RuntimeAnnouncementClient.replayable(null, now));
        assertFalse(RuntimeAnnouncementClient.replayable("yesterday", now));
    }

    @Test public void readTimeoutOutlastsTheServerWait() {
        assertTrue(RuntimeAnnouncementClient.READ_TIMEOUT_MS > RuntimeAnnouncementClient.WAIT_SECONDS * 1000);
    }
}
