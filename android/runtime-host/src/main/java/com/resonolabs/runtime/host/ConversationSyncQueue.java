package com.resonolabs.runtime.host;

import java.util.ArrayList;

/**
 * The pure delivery policy behind {@link ConversationSyncClient} (single thread, no Android):
 * a bounded in-memory queue of serialized conversation events, the drop policy, batching, the
 * flush schedule and the retry backoff (CONTRACTS-WAVE3 hop 1, r1-dataflow §4.2).
 *
 * <ul>
 *   <li>At most {@link #MAX_EVENTS} events. When full, the oldest queued
 *       {@code message.assistant.delta} goes first (each delta carries the whole draft, so a
 *       later delta or the {@code done} event supersedes it), then the oldest event.</li>
 *   <li>A new delta replaces any queued, not yet sent delta of the same message.</li>
 *   <li>A batch is the run of head events that share one conversation and runtime session (the
 *       request body names both), at most {@link #MAX_BATCH_EVENTS} events and
 *       {@link #MAX_BATCH_BYTES} bytes.</li>
 *   <li>Flush when {@link #FLUSH_COUNT} events wait, at once for an urgent event (session end,
 *       image), otherwise {@link #FLUSH_DELAY_MS} after the oldest unsent event arrived.</li>
 *   <li>Failures back off {@link #BACKOFF_MS} (1, 2, 5, 10 s, then 10 s).</li>
 * </ul>
 */
final class ConversationSyncQueue {
    static final int MAX_EVENTS = 500;
    static final int FLUSH_COUNT = 20;
    static final long FLUSH_DELAY_MS = 750L;
    static final int MAX_BATCH_EVENTS = 100;
    /** UTF-8 bytes of events per request: below the runtime's 256 KiB body limit, with room for the envelope. */
    static final int MAX_BATCH_BYTES = 200_000;
    static final long[] BACKOFF_MS = {1_000L, 2_000L, 5_000L, 10_000L};
    static final String DELTA = "message.assistant.delta";

    /** One queued event, already serialized. */
    static final class Entry {
        final String conversationId;
        final String sessionId;
        final String type;
        final String messageId;
        final String json;
        final boolean urgent;
        final long queuedAt;
        /** UTF-8 size on the wire, plus its comma. */
        private final int bytes;
        boolean inFlight;

        Entry(String conversationId, String sessionId, String type, String messageId, String json,
              boolean urgent, long queuedAt) {
            this.conversationId = conversationId == null ? "" : conversationId;
            this.sessionId = sessionId == null ? "" : sessionId;
            this.type = type == null ? "" : type;
            this.messageId = messageId;
            this.json = json;
            this.urgent = urgent;
            this.queuedAt = queuedAt;
            this.bytes = utf8Length(json) + 1;
        }

        int bytes() {
            return bytes;
        }
    }

    /**
     * Encoded UTF-8 length without encoding. The runtime limit is in bytes: counting chars would
     * let a batch of non-Latin text (up to 3 bytes per char) exceed it, and the runtime then
     * rejects the whole batch (dropped for good).
     */
    static int utf8Length(String text) {
        if (text == null) return 0;
        int bytes = 0;
        for (int index = 0; index < text.length(); index++) {
            char c = text.charAt(index);
            if (c < 0x80) {
                bytes += 1;
            } else if (c < 0x800) {
                bytes += 2;
            } else if (Character.isHighSurrogate(c) && index + 1 < text.length()
                    && Character.isLowSurrogate(text.charAt(index + 1))) {
                bytes += 4;
                index++;
            } else {
                bytes += 3;
            }
        }
        return bytes;
    }

    /** Events sent together (they share conversation and session). */
    static final class Batch {
        final String conversationId;
        final String sessionId;
        final ArrayList<Entry> entries;

        Batch(String conversationId, String sessionId, ArrayList<Entry> entries) {
            this.conversationId = conversationId;
            this.sessionId = sessionId;
            this.entries = entries;
        }

        int size() {
            return entries.size();
        }
    }

    private final ArrayList<Entry> entries = new ArrayList<>();
    private int dropped;
    private int failures;
    /** {@link #forceFlush}: everything unsent is due now. */
    private boolean forced;

    int size() {
        return entries.size();
    }

    boolean isEmpty() {
        return entries.isEmpty();
    }

    /** Events dropped by the size cap or delta coalescing since the last {@link #takeDropped}. */
    int takeDropped() {
        int value = dropped;
        dropped = 0;
        return value;
    }

    /** Adds an event; applies coalescing and the size cap. */
    void add(Entry entry) {
        if (entry == null || entry.json == null || entry.json.isEmpty()) return;
        if (DELTA.equals(entry.type) && entry.messageId != null) {
            for (int index = entries.size() - 1; index >= 0; index--) {
                Entry queued = entries.get(index);
                if (!queued.inFlight && DELTA.equals(queued.type) && entry.messageId.equals(queued.messageId)
                        && queued.conversationId.equals(entry.conversationId)) {
                    entries.remove(index);
                    dropped++;
                }
            }
        }
        entries.add(entry);
        while (entries.size() > MAX_EVENTS) {
            if (!dropOldest(true) && !dropOldest(false)) break;
        }
    }

    private boolean dropOldest(boolean deltasOnly) {
        for (int index = 0; index < entries.size(); index++) {
            Entry queued = entries.get(index);
            if (queued.inFlight) continue;
            if (deltasOnly && !DELTA.equals(queued.type)) continue;
            entries.remove(index);
            dropped++;
            return true;
        }
        return false;
    }

    /** Makes everything queued so far due at once (session end). */
    void forceFlush() {
        forced = true;
    }

    /** True when something waits that has not been handed out in a batch. */
    boolean hasUnsent() {
        for (Entry entry : entries) if (!entry.inFlight) return true;
        return false;
    }

    /**
     * Milliseconds from {@code now} until the next flush is due (0 = now), or -1 when nothing
     * waits. Ignores the failure backoff (see {@link #backoffMs()}).
     */
    long flushDelay(long now) {
        int unsent = 0;
        long oldest = Long.MAX_VALUE;
        for (Entry entry : entries) {
            if (entry.inFlight) continue;
            if (entry.urgent) return 0L;
            unsent++;
            oldest = Math.min(oldest, entry.queuedAt);
        }
        if (unsent == 0) {
            forced = false;
            return -1L;
        }
        if (forced || unsent >= FLUSH_COUNT) return 0L;
        return Math.max(0L, oldest + FLUSH_DELAY_MS - now);
    }

    /** The next batch from the head (marked in flight), or null when nothing is unsent. */
    Batch nextBatch() {
        ArrayList<Entry> picked = new ArrayList<>();
        String conversation = null;
        String session = null;
        int bytes = 0;
        for (Entry entry : entries) {
            if (entry.inFlight) continue;
            if (conversation == null) {
                conversation = entry.conversationId;
                session = entry.sessionId;
            } else if (!conversation.equals(entry.conversationId) || !session.equals(entry.sessionId)) {
                break;
            }
            if (!picked.isEmpty() && (picked.size() >= MAX_BATCH_EVENTS || bytes + entry.bytes() > MAX_BATCH_BYTES)) {
                break;
            }
            picked.add(entry);
            bytes += entry.bytes();
        }
        if (picked.isEmpty()) return null;
        for (Entry entry : picked) entry.inFlight = true;
        return new Batch(conversation, session, picked);
    }

    /** The batch arrived (or must be dropped for good): remove it. Resets the backoff on success. */
    void complete(Batch batch, boolean delivered) {
        if (batch == null) return;
        entries.removeAll(batch.entries);
        if (delivered) failures = 0;
        else dropped += batch.size();
    }

    /** The batch failed in a way that may succeed later: keep it at the head, back off. */
    void retry(Batch batch) {
        if (batch != null) for (Entry entry : batch.entries) entry.inFlight = false;
        failures++;
    }

    /** Drops everything (the route is missing on this runtime). */
    int clear() {
        int count = entries.size();
        entries.clear();
        dropped += count;
        return count;
    }

    int failures() {
        return failures;
    }

    /** Delay before the next attempt after {@link #failures()} consecutive failures (0 = none). */
    long backoffMs() {
        return backoffMs(failures);
    }

    static long backoffMs(int failures) {
        if (failures <= 0) return 0L;
        return BACKOFF_MS[Math.min(BACKOFF_MS.length - 1, failures - 1)];
    }

    /** {@code {"conversationId":…,"sessionId":…,"events":[…]}} from the serialized events. */
    static String body(Batch batch) {
        StringBuilder out = new StringBuilder(64 + batch.size() * 160);
        out.append("{\"conversationId\":").append(quote(batch.conversationId))
                .append(",\"sessionId\":").append(quote(batch.sessionId))
                .append(",\"events\":[");
        for (int index = 0; index < batch.entries.size(); index++) {
            if (index > 0) out.append(',');
            out.append(batch.entries.get(index).json);
        }
        return out.append("]}").toString();
    }

    /** JSON string literal (no org.json dependency, so it runs anywhere). */
    static String quote(String value) {
        String text = value == null ? "" : value;
        StringBuilder out = new StringBuilder(text.length() + 2).append('"');
        for (int index = 0; index < text.length(); index++) {
            char c = text.charAt(index);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20 || c == ' ' || c == ' ') {
                        out.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }
}
