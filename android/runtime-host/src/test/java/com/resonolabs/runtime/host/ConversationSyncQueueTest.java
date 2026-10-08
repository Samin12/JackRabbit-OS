package com.resonolabs.runtime.host;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

/** Drop policy, coalescing, batching, flush timing and backoff of the conversation mirror. */
public final class ConversationSyncQueueTest {
    private static ConversationSyncQueue.Entry entry(String type, String messageId, long at) {
        return entry("c_1", "s1", type, messageId, at, false);
    }

    private static ConversationSyncQueue.Entry entry(String conversation, String session, String type,
                                                     String messageId, long at, boolean urgent) {
        String json = "{\"type\":\"" + type + "\",\"at\":" + at + "}";
        return new ConversationSyncQueue.Entry(conversation, session, type, messageId, json, urgent, at);
    }

    @Test public void fullQueueDropsDeltasFirst() {
        ConversationSyncQueue queue = new ConversationSyncQueue();
        queue.add(entry("message.user", null, 0));
        for (int index = 1; index < ConversationSyncQueue.MAX_EVENTS; index++) {
            // distinct messages so nothing coalesces
            queue.add(entry(index % 2 == 0 ? "message.assistant.delta" : "ui.event", "a_" + index, index));
        }
        assertEquals(ConversationSyncQueue.MAX_EVENTS, queue.size());
        queue.add(entry("message.assistant.done", "a_x", 9_999));
        assertEquals(ConversationSyncQueue.MAX_EVENTS, queue.size());
        assertEquals(1, queue.takeDropped());
        // The oldest delta went (a_2), the very first user message stayed at the head.
        ConversationSyncQueue.Batch batch = queue.nextBatch();
        assertEquals("message.user", batch.entries.get(0).type);
        assertEquals("ui.event", batch.entries.get(1).type);
        assertEquals("ui.event", batch.entries.get(2).type); // a_2 (the oldest delta) is gone
        assertEquals("a_4", batch.entries.get(3).messageId);
    }

    @Test public void fullQueueWithoutDeltasDropsTheOldest() {
        ConversationSyncQueue queue = new ConversationSyncQueue();
        for (int index = 0; index <= ConversationSyncQueue.MAX_EVENTS; index++) {
            queue.add(entry("ui.event", null, index));
        }
        assertEquals(ConversationSyncQueue.MAX_EVENTS, queue.size());
        assertEquals(1, queue.takeDropped());
        assertTrue(queue.nextBatch().entries.get(0).json.contains("\"at\":1}"));
    }

    @Test public void aNewerDeltaReplacesTheQueuedOne() {
        ConversationSyncQueue queue = new ConversationSyncQueue();
        queue.add(entry("message.assistant.delta", "a_1", 0));
        queue.add(entry("message.assistant.delta", "a_2", 1));
        queue.add(entry("message.assistant.delta", "a_1", 2));
        assertEquals(2, queue.size());
        assertEquals(1, queue.takeDropped());
        ConversationSyncQueue.Batch batch = queue.nextBatch();
        assertEquals("a_2", batch.entries.get(0).messageId);
        assertEquals("a_1", batch.entries.get(1).messageId);
        assertTrue(batch.entries.get(1).json.contains("\"at\":2"));
    }

    @Test public void aDeltaInFlightIsNotReplaced() {
        ConversationSyncQueue queue = new ConversationSyncQueue();
        queue.add(entry("message.assistant.delta", "a_1", 0));
        ConversationSyncQueue.Batch sending = queue.nextBatch();
        queue.add(entry("message.assistant.delta", "a_1", 5));
        assertEquals(2, queue.size());
        queue.complete(sending, true);
        assertEquals(1, queue.size());
    }

    @Test public void flushTiming() {
        ConversationSyncQueue queue = new ConversationSyncQueue();
        assertEquals(-1L, queue.flushDelay(0));
        queue.add(entry("message.user", null, 1_000));
        assertEquals(ConversationSyncQueue.FLUSH_DELAY_MS, queue.flushDelay(1_000));
        assertEquals(250L, queue.flushDelay(1_500));
        assertEquals(0L, queue.flushDelay(2_000));
        // 20 waiting: at once
        for (int index = 1; index < ConversationSyncQueue.FLUSH_COUNT; index++) {
            queue.add(entry("ui.event", null, 1_000));
        }
        assertEquals(0L, queue.flushDelay(1_000));
    }

    @Test public void urgentEventsFlushAtOnce() {
        ConversationSyncQueue queue = new ConversationSyncQueue();
        queue.add(entry("message.user", null, 1_000));
        queue.add(entry("c_1", "s1", "session.ended", null, 1_010, true));
        assertEquals(0L, queue.flushDelay(1_010));
    }

    @Test public void forcedFlushLastsUntilTheQueueIsSent() {
        ConversationSyncQueue queue = new ConversationSyncQueue();
        queue.add(entry("message.user", null, 1_000));
        queue.forceFlush();
        assertEquals(0L, queue.flushDelay(1_000));
        queue.complete(queue.nextBatch(), true);
        assertEquals(-1L, queue.flushDelay(1_000));
        queue.add(entry("message.user", null, 1_100));
        assertEquals(ConversationSyncQueue.FLUSH_DELAY_MS, queue.flushDelay(1_100));
    }

    @Test public void batchesNeverMixConversationsOrSessions() {
        ConversationSyncQueue queue = new ConversationSyncQueue();
        queue.add(entry("c_1", "s1", "message.user", null, 0, false));
        queue.add(entry("c_1", "s1", "session.ended", null, 1, true));
        queue.add(entry("c_1", "s2", "session.connected", null, 2, false));
        queue.add(entry("c_2", "", "conversation.started", null, 3, false));
        ConversationSyncQueue.Batch first = queue.nextBatch();
        assertEquals(2, first.size());
        assertEquals("s1", first.sessionId);
        ConversationSyncQueue.Batch second = queue.nextBatch();
        assertEquals(1, second.size());
        assertEquals("s2", second.sessionId);
        ConversationSyncQueue.Batch third = queue.nextBatch();
        assertEquals("c_2", third.conversationId);
        assertNull(queue.nextBatch());
    }

    @Test public void batchesAreCapped() {
        ConversationSyncQueue queue = new ConversationSyncQueue();
        for (int index = 0; index < 150; index++) queue.add(entry("ui.event", null, index));
        assertEquals(ConversationSyncQueue.MAX_BATCH_EVENTS, queue.nextBatch().size());
        assertEquals(50, queue.nextBatch().size());

        ConversationSyncQueue big = new ConversationSyncQueue();
        String text = "x".repeat(60_000);
        for (int index = 0; index < 5; index++) {
            big.add(new ConversationSyncQueue.Entry("c_1", "s1", "host.completion", null,
                    "{\"text\":\"" + text + "\"}", false, index));
        }
        ConversationSyncQueue.Batch batch = big.nextBatch();
        assertEquals(3, batch.size());
        assertTrue(ConversationSyncQueue.body(batch).length() <= ConversationSyncQueue.MAX_BATCH_BYTES + 100);
    }

    @Test public void batchCapCountsUtf8BytesNotChars() throws Exception {
        // 100 events of 1,500 CJK chars: 150,000 chars, but 450,000 UTF-8 bytes. Sent as one
        // request the runtime (256 KiB body limit) would reject it and the whole batch would be lost.
        ConversationSyncQueue queue = new ConversationSyncQueue();
        String words = "你好".repeat(750);
        for (int index = 0; index < 100; index++) {
            queue.add(new ConversationSyncQueue.Entry("c_1", "s1", "message.user", null,
                    new JSONObject().put("id", "c_1:" + index).put("text", words).toString(), false, index));
        }
        int sent = 0;
        ConversationSyncQueue.Batch batch;
        while ((batch = queue.nextBatch()) != null) {
            int bytes = ConversationSyncQueue.body(batch).getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            assertTrue("batch of " + bytes + " bytes", bytes <= 256 * 1024);
            assertTrue(bytes <= ConversationSyncQueue.MAX_BATCH_BYTES + 100);
            sent += batch.size();
            queue.complete(batch, true);
        }
        assertEquals(100, sent);
    }

    @Test public void utf8Length() {
        String[] samples = {"", "abc", "café", "你好", "😀 ok", "  x"};
        for (String sample : samples) {
            assertEquals(sample, sample.getBytes(java.nio.charset.StandardCharsets.UTF_8).length,
                    ConversationSyncQueue.utf8Length(sample));
        }
    }

    @Test public void retryKeepsTheBatchAtTheHeadAndBacksOff() {
        ConversationSyncQueue queue = new ConversationSyncQueue();
        queue.add(entry("message.user", null, 0));
        queue.add(entry("ui.event", null, 1));
        ConversationSyncQueue.Batch batch = queue.nextBatch();
        assertFalse(queue.hasUnsent());
        queue.retry(batch);
        assertEquals(1_000L, queue.backoffMs());
        assertTrue(queue.hasUnsent());
        queue.retry(queue.nextBatch());
        assertEquals(2_000L, queue.backoffMs());
        queue.retry(queue.nextBatch());
        assertEquals(5_000L, queue.backoffMs());
        queue.retry(queue.nextBatch());
        assertEquals(10_000L, queue.backoffMs());
        queue.retry(queue.nextBatch());
        assertEquals(10_000L, queue.backoffMs());
        ConversationSyncQueue.Batch again = queue.nextBatch();
        assertEquals("message.user", again.entries.get(0).type);
        queue.complete(again, true);
        assertEquals(0L, queue.backoffMs());
        assertTrue(queue.isEmpty());
    }

    @Test public void bodyIsTheContractEnvelope() throws Exception {
        ConversationSyncQueue queue = new ConversationSyncQueue();
        queue.add(new ConversationSyncQueue.Entry("c_\"q", "s1", "message.user", null,
                new JSONObject().put("id", "c_1:1").put("text", "hi \"there\"\n").toString(), false, 0));
        JSONObject body = new JSONObject(ConversationSyncQueue.body(queue.nextBatch()));
        assertEquals("c_\"q", body.getString("conversationId"));
        assertEquals("s1", body.getString("sessionId"));
        assertEquals("hi \"there\"\n", body.getJSONArray("events").getJSONObject(0).getString("text"));
    }

    @Test public void quoteEscapesControlCharacters() throws Exception {
        String quoted = ConversationSyncQueue.quote("a\u0001b c\\");
        assertEquals("a\u0001b c\\", new JSONObject("{\"v\":" + quoted + "}").getString("v"));
    }
}
