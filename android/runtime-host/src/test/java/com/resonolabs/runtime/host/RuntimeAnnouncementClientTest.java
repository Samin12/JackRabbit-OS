package com.resonolabs.runtime.host;

import static org.junit.Assert.assertEquals;
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

    @Test public void readTimeoutOutlastsTheServerWait() {
        assertTrue(RuntimeAnnouncementClient.READ_TIMEOUT_MS > RuntimeAnnouncementClient.WAIT_SECONDS * 1000);
    }
}
