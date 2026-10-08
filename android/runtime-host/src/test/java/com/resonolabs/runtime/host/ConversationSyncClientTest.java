package com.resonolabs.runtime.host;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Delivery of the conversation mirror with a fake runtime and a manual clock/thread. */
public final class ConversationSyncClientTest {
    /** A single "thread": tasks run when the virtual clock reaches them. */
    private static final class ManualScheduler implements ConversationSyncClient.Scheduler, ConversationSyncClient.Clock {
        long now = 1_000_000L;
        final List<Object[]> tasks = new ArrayList<>();
        boolean shutdown;

        @Override public void execute(Runnable task) {
            if (shutdown) throw new java.util.concurrent.RejectedExecutionException();
            tasks.add(new Object[]{now, task});
        }

        @Override public Object schedule(Runnable task, long delayMs) {
            Object[] entry = {now + delayMs, task};
            tasks.add(entry);
            return entry;
        }

        @Override public void cancel(Object handle) {
            tasks.remove(handle);
        }

        @Override public void shutdown() {
            shutdown = true;
        }

        @Override public long now() {
            return now;
        }

        /** Runs every task due up to now + ms, in time order. */
        void advance(long ms) {
            long until = now + ms;
            while (true) {
                Object[] next = null;
                for (Object[] task : tasks) {
                    if ((Long) task[0] <= until && (next == null || (Long) task[0] < (Long) next[0])) next = task;
                }
                if (next == null) break;
                tasks.remove(next);
                now = Math.max(now, (Long) next[0]);
                ((Runnable) next[1]).run();
            }
            now = until;
        }
    }

    private static final class FakeRuntime implements ConversationSyncClient.Transport {
        final List<String> paths = new ArrayList<>();
        final List<String> bodies = new ArrayList<>();
        final List<String> conversations = new ArrayList<>();
        int eventsStatus = 202;
        int blobsStatus = 200;

        @Override public int post(String path, String contentType, byte[] body, String conversationId) {
            paths.add(path);
            conversations.add(conversationId);
            boolean events = ConversationSyncClient.EVENTS_PATH.equals(path);
            bodies.add(events ? new String(body, StandardCharsets.UTF_8) : contentType + ":" + body.length);
            return events ? eventsStatus : blobsStatus;
        }

        int eventPosts() {
            int count = 0;
            for (String path : paths) if (ConversationSyncClient.EVENTS_PATH.equals(path)) count++;
            return count;
        }
    }

    private ManualScheduler thread;
    private FakeRuntime runtime;
    private List<String> logs;
    private ConversationSyncClient client;
    private ConversationTimeline timeline;

    @Before public void setUp() {
        thread = new ManualScheduler();
        runtime = new FakeRuntime();
        logs = new ArrayList<>();
        client = new ConversationSyncClient(runtime, thread, thread, logs::add);
        timeline = new ConversationTimeline(client::emit, thread::now, () -> "c_0123456789abcdef0123");
    }

    @Test public void batchesWithinTheFlushWindow() throws Exception {
        timeline.start("user");
        timeline.userMessage("hello", "debug.say");
        thread.advance(100);
        assertEquals(0, runtime.eventPosts());
        thread.advance(ConversationSyncQueue.FLUSH_DELAY_MS);
        assertEquals(1, runtime.eventPosts());
        JSONObject body = new JSONObject(runtime.bodies.get(0));
        assertEquals("c_0123456789abcdef0123", body.getString("conversationId"));
        JSONArray events = body.getJSONArray("events");
        assertEquals(2, events.length());
        assertEquals("conversation.started", events.getJSONObject(0).getString("type"));
        assertEquals("c_0123456789abcdef0123:2", events.getJSONObject(1).getString("id"));
        assertEquals(0, client.queuedEvents());
    }

    @Test public void urgentEventsGoAtOnce() throws Exception {
        timeline.start("user");
        timeline.sessionConnected("s1", false);
        timeline.sessionEnded("user_stop");
        thread.advance(0);
        // conversation.started (no session yet) and the session's events are separate requests.
        assertEquals(2, runtime.eventPosts());
        JSONObject first = new JSONObject(runtime.bodies.get(0));
        assertEquals("", first.getString("sessionId"));
        assertEquals(1, first.getJSONArray("events").length());
        JSONObject second = new JSONObject(runtime.bodies.get(1));
        assertEquals("s1", second.getString("sessionId"));
        assertEquals(2, second.getJSONArray("events").length());
    }

    @Test public void failuresRetryWithBackoff() {
        runtime.eventsStatus = 503;
        timeline.start("user");
        timeline.end("user_stop"); // urgent
        thread.advance(0);
        assertEquals(1, runtime.eventPosts());
        thread.advance(999);
        assertEquals(1, runtime.eventPosts());
        thread.advance(1);
        assertEquals(2, runtime.eventPosts());
        thread.advance(2_000);
        assertEquals(3, runtime.eventPosts());
        runtime.eventsStatus = 202;
        thread.advance(5_000);
        assertEquals(4, runtime.eventPosts());
        assertEquals(0, client.queuedEvents());
        // Logged once for the first failure, not for every retry.
        assertEquals(1, logs.size());
    }

    @Test public void aMissingRouteDropsQuietlyAndLogsOnce() {
        runtime.eventsStatus = 404;
        timeline.start("user");
        timeline.end("closed");
        thread.advance(0);
        assertEquals(1, runtime.eventPosts());
        assertEquals(0, client.queuedEvents());
        timeline.start("user");
        timeline.end("closed");
        thread.advance(0);
        // Within the missing-route window nothing is sent; events are dropped.
        assertEquals(1, runtime.eventPosts());
        assertEquals(0, client.queuedEvents());
        thread.advance(ConversationSyncClient.MISSING_ROUTE_MS);
        timeline.start("user");
        timeline.end("closed");
        thread.advance(0);
        assertEquals(2, runtime.eventPosts());
        assertEquals(1, logs.size());
        assertTrue(logs.get(0).contains("404"));
    }

    @Test public void rejectedBatchesAreDroppedNotRetried() {
        runtime.eventsStatus = 400;
        timeline.start("user");
        timeline.end("closed");
        thread.advance(0);
        thread.advance(60_000);
        assertEquals(1, runtime.eventPosts());
        assertEquals(0, client.queuedEvents());
    }

    @Test public void blobsGoBeforeTheEventThatNamesThem() {
        timeline.start("user");
        byte[] jpeg = {(byte) 0xFF, (byte) 0xD8, 1, 2, 3};
        String blobId = client.uploadBlob(jpeg, "image/jpeg", timeline.conversationId());
        assertEquals(ConversationSyncClient.blobId(jpeg), blobId);
        assertTrue(blobId.startsWith("sha256:"));
        assertEquals(71, blobId.length());
        timeline.image(blobId, "image/jpeg", 960, 720, jpeg.length, "camera", "camera.jpg");
        thread.advance(0);
        assertEquals(ConversationSyncClient.BLOBS_PATH, runtime.paths.get(0));
        assertEquals("image/jpeg:5", runtime.bodies.get(0));
        assertEquals("c_0123456789abcdef0123", runtime.conversations.get(0));
        assertEquals(ConversationSyncClient.EVENTS_PATH, runtime.paths.get(1));
        assertTrue(runtime.bodies.get(1).contains(blobId));
        assertEquals(0, client.queuedBlobs());
    }

    @Test public void aMissingBlobRouteStillSendsTheEvents() {
        runtime.blobsStatus = 404;
        timeline.start("user");
        String blobId = client.uploadBlob(new byte[]{1, 2}, "image/jpeg", timeline.conversationId());
        timeline.image(blobId, "image/jpeg", 0, 0, 2, "camera", null);
        thread.advance(0);
        assertEquals(1, runtime.eventPosts());
        assertEquals(0, client.queuedBlobs());
    }

    @Test public void refusesWhatIsNotAnImage() {
        assertNull(client.uploadBlob(new byte[]{1}, "text/plain", "c_1"));
        assertNull(client.uploadBlob(new byte[0], "image/jpeg", "c_1"));
        assertNull(client.uploadBlob(new byte[ConversationSyncClient.MAX_BLOB_BYTES + 1], "image/jpeg", "c_1"));
        assertNull(client.uploadBlob(new byte[]{1}, "image/jpeg", ""));
        assertNotNull(client.uploadBlob(new byte[]{1}, "image/png", "c_1"));
    }

    @Test public void eventsWithoutAConversationAreIgnored() throws Exception {
        client.emit(new JSONObject().put("type", "message.user"), true);
        thread.advance(1_000);
        assertEquals(0, runtime.eventPosts());
    }

    @Test public void closeSendsWhatIsQueuedOnce() {
        timeline.start("user");
        timeline.userMessage("bye", "debug.say");
        client.close();
        thread.advance(0);
        assertEquals(1, runtime.eventPosts());
        assertTrue(thread.shutdown);
        // after close: nothing more is accepted
        timeline.userMessage("late", "debug.say");
        thread.advance(10_000);
        assertEquals(1, runtime.eventPosts());
    }

    @Test public void flushSendsEverythingNow() {
        timeline.start("user");
        timeline.userMessage("one", "debug.say");
        client.flush();
        thread.advance(0);
        assertEquals(1, runtime.eventPosts());
    }
}
