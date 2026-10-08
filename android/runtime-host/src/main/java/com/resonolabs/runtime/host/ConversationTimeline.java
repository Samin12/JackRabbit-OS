package com.resonolabs.runtime.host;

import org.json.JSONObject;

import java.security.SecureRandom;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * One user-visible Voice conversation as a stream of sync events (pure; main thread). The
 * {@code conversationId} ({@code c_} + 20 hex) is created when the user starts a session, is
 * kept across always-on reconnects (each of which gets a new runtime {@code sessionId}), and is
 * cleared when the user stops or the session ends for good. Every event gets
 * {@code id = "<conversationId>:<seq>"}, a per-conversation {@code seq} starting at 1, the wall
 * clock {@code at} and an {@code origin} (CONTRACTS-WAVE3 "Shared identifiers").
 *
 * <p>Event helpers return the event they emitted, or null when no conversation is active.
 */
public final class ConversationTimeline {
    /** Where events go ({@link ConversationSyncClient#emit}). */
    public interface Sink {
        void accept(JSONObject event, boolean urgent);
    }

    public static final String ORIGIN_USER = "user";
    public static final String ORIGIN_MODEL = "model";
    public static final String ORIGIN_HOST = "host";
    /**
     * {@code reason} of a session/conversation the user ended (stop button, side button). The
     * desktop shows ends with a quiet reason (user, closed, …) without a "Session ended" warning.
     */
    public static final String REASON_USER = "user";
    /** The Voice page was torn down (HOME closed). */
    public static final String REASON_CLOSED = "closed";
    /** Text fields are cut here (the runtime keeps 16,384-char transcript entries). */
    static final int MAX_TEXT = 16_000;
    /** Host completions and T3 envelopes carry data blobs; keep each event small. */
    static final int MAX_HOST_TEXT = 8_000;
    /** Assistant drafts are mirrored at most this often (the whole draft each time). */
    public static final long DELTA_INTERVAL_MS = 300L;

    private final Sink sink;
    private final LongSupplier clock;
    private final Supplier<String> ids;
    private String conversationId;
    private String sessionId = "";
    private long seq;
    private long messageCounter;
    private long lastDeltaAt = Long.MIN_VALUE / 2;

    public ConversationTimeline(Sink sink) {
        this(sink, System::currentTimeMillis, ConversationTimeline::randomId);
    }

    public ConversationTimeline(Sink sink, LongSupplier clock, Supplier<String> ids) {
        this.sink = sink;
        this.clock = clock;
        this.ids = ids;
    }

    /** {@code c_} + 20 random hex digits. */
    public static String randomId() {
        byte[] bytes = new byte[10];
        new SecureRandom().nextBytes(bytes);
        StringBuilder out = new StringBuilder(22).append("c_");
        for (byte value : bytes) {
            out.append(Character.forDigit((value >> 4) & 0xF, 16)).append(Character.forDigit(value & 0xF, 16));
        }
        return out.toString();
    }

    // ------------------------------------------------------------------ lifecycle

    /**
     * A session the user asked for: a new conversation (an active one is ended first). Emits
     * {@code conversation.started}. Returns the new id.
     */
    public String start(String trigger) {
        if (conversationId != null) end("restarted");
        conversationId = ids.get();
        seq = 0L;
        messageCounter = 0L;
        sessionId = "";
        lastDeltaAt = Long.MIN_VALUE / 2;
        emit("conversation.started", ORIGIN_USER, false, "trigger", trigger);
        return conversationId;
    }

    /** The conversation is over (user stop, final failure, teardown). Emits {@code conversation.ended}. */
    public void end(String reason) {
        if (conversationId == null) return;
        emit("conversation.ended", ORIGIN_HOST, true, "reason", reason);
        conversationId = null;
        sessionId = "";
    }

    public boolean active() {
        return conversationId != null;
    }

    /** The current id, or null between conversations. */
    public String conversationId() {
        return conversationId;
    }

    /** The runtime session events are attributed to ("" while connecting). */
    public void setSessionId(String id) {
        sessionId = id == null ? "" : id;
    }

    public String sessionId() {
        return sessionId;
    }

    long seq() {
        return seq;
    }

    // ------------------------------------------------------------------ events

    public JSONObject sessionConnected(String runtimeSessionId, boolean reconnect) {
        setSessionId(runtimeSessionId);
        return emit("session.connected", ORIGIN_HOST, false, "reconnect", reconnect);
    }

    /** Before finalize; the session id is then cleared (a reconnect gets a new one). */
    public JSONObject sessionEnded(String reason) {
        JSONObject event = emit("session.ended", ORIGIN_HOST, true, "reason", reason);
        sessionId = "";
        return event;
    }

    /** What the user said or sent ({@code eventType} = the Realtime/transcript event type). */
    public JSONObject userMessage(String text, String eventType) {
        if (blank(text)) return null;
        return emit("message.user", ORIGIN_USER, false, "text", cut(text, MAX_TEXT), "eventType", eventType);
    }

    /** A new assistant message id ({@code a_<n>}), unique within the conversation. */
    public String nextMessageId() {
        messageCounter++;
        lastDeltaAt = Long.MIN_VALUE / 2;
        return "a_" + messageCounter;
    }

    /** The whole draft so far, at most every {@link #DELTA_INTERVAL_MS}; null when throttled. */
    public JSONObject assistantDelta(String messageId, String draft) {
        if (conversationId == null || blank(draft) || messageId == null) return null;
        long now = clock.getAsLong();
        if (now - lastDeltaAt < DELTA_INTERVAL_MS) return null;
        lastDeltaAt = now;
        return emit("message.assistant.delta", ORIGIN_MODEL, false, "messageId", messageId,
                "text", cut(draft, MAX_TEXT));
    }

    public JSONObject assistantDone(String messageId, String text) {
        if (blank(text) || messageId == null) return null;
        return emit("message.assistant.done", ORIGIN_MODEL, false, "messageId", messageId,
                "text", cut(text, MAX_TEXT), "interrupted", false);
    }

    /** The user stopped the reply mid-sentence; {@code text} is what was spoken so far. */
    public JSONObject assistantInterrupted(String messageId, String text) {
        if (messageId == null) return null;
        return emit("message.assistant.interrupted", ORIGIN_USER, false, "messageId", messageId,
                "text", cut(text == null ? "" : text, MAX_TEXT), "interrupted", true);
    }

    /** {@code card.shown|updated|dismissed}; {@code card} = GenCardCodec JSON. */
    public JSONObject card(String type, JSONObject card, String origin) {
        if (card == null) return null;
        return emit(type, origin, false, "cardId", card.optString("id", ""), "card", card);
    }

    /** A T3 envelope injected as host data (never the user's words). */
    public JSONObject hostT3Update(String text, long announcementId, String kind) {
        if (blank(text)) return null;
        return emit("host.t3_update", ORIGIN_HOST, false, "text", cut(text, MAX_HOST_TEXT),
                "announcementId", announcementId > 0L ? announcementId : null, "kind", kind);
    }

    /** A host note for the model (T3 tab Talk, Cards tab say, …). */
    public JSONObject hostNote(String text, String source) {
        if (blank(text)) return null;
        return emit("host.note", ORIGIN_HOST, false, "text", cut(text, MAX_HOST_TEXT), "source", source);
    }

    /** A background-goal result handed to the model. */
    public JSONObject hostCompletion(String runId, String text) {
        if (blank(text)) return null;
        return emit("host.completion", ORIGIN_HOST, false, "runId", runId, "text", cut(text, MAX_HOST_TEXT));
    }

    /** A silent {@code [UI event]} note (checklist toggles, card focus, Approve/Deny). */
    public JSONObject uiEvent(String text) {
        if (blank(text)) return null;
        return emit("ui.event", ORIGIN_USER, false, "text", cut(text, MAX_HOST_TEXT));
    }

    /** An image in the conversation; the bytes travel separately as blob {@code blobId}. */
    public JSONObject image(String blobId, String mime, int width, int height, int bytes, String source,
                            String caption) {
        if (blank(blobId)) return null;
        String origin = "camera".equals(source) ? ORIGIN_USER : ORIGIN_HOST;
        return emit("image", origin, true, "blobId", blobId, "mime", mime,
                "width", width > 0 ? width : null, "height", height > 0 ? height : null,
                "bytes", bytes > 0 ? bytes : null, "source", source,
                "caption", blank(caption) ? null : cut(caption, 240));
    }

    /**
     * Emits {@code type} with the common fields plus {@code fields} (key, value pairs; null
     * values are skipped). Null when no conversation is active.
     */
    public JSONObject emit(String type, String origin, boolean urgent, Object... fields) {
        if (conversationId == null) return null;
        seq++;
        JSONObject event = new JSONObject();
        try {
            event.put("id", conversationId + ":" + seq);
            event.put("conversationId", conversationId);
            if (!sessionId.isEmpty()) event.put("sessionId", sessionId);
            event.put("seq", seq);
            event.put("type", type);
            event.put("at", clock.getAsLong());
            event.put("origin", origin);
            for (int index = 0; index + 1 < fields.length; index += 2) {
                Object value = fields[index + 1];
                if (value != null) event.put(String.valueOf(fields[index]), value);
            }
        } catch (Exception invalid) {
            return null; // org.json rejects only NaN/Infinity; never produced here
        }
        if (sink != null) sink.accept(event, urgent);
        return event;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    static String cut(String value, int max) {
        String text = value == null ? "" : value.trim();
        return text.length() <= max ? text : text.substring(0, max - 1) + "…";
    }
}
