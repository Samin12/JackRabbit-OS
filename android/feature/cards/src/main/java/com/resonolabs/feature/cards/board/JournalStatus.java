package com.resonolabs.feature.cards.board;

import org.json.JSONObject;

/**
 * {@code GET /v1/journal/status} as the board shows it (CONTRACTS §3 plus the additive
 * {@code today} counts). Pure Java over org.json; absent fields arrive as JSON null and are read
 * null-safely.
 */
final class JournalStatus {
    enum State { CONNECTED, RECONNECT, DISCONNECTED }

    final State state;
    /** Today's entries in the user's journal timezone; -1 when the runtime does not report them. */
    final int sentToday;
    final int queuedToday;
    /** Everything still waiting to go out (all days). */
    final int pending;
    final int failed;

    private JournalStatus(State state, int sentToday, int queuedToday, int pending, int failed) {
        this.state = state;
        this.sentToday = sentToday;
        this.queuedToday = queuedToday;
        this.pending = pending;
        this.failed = failed;
    }

    static JournalStatus from(JSONObject value) {
        JSONObject json = value == null ? new JSONObject() : value;
        boolean connected = json.optBoolean("connected", false);
        boolean reconnect = json.optBoolean("needsReconnect", false);
        State state = !connected ? State.DISCONNECTED : reconnect ? State.RECONNECT : State.CONNECTED;
        JSONObject today = json.isNull("today") ? null : json.optJSONObject("today");
        int sent = today == null ? -1 : Math.max(0, today.optInt("sent", 0));
        int queued = today == null ? -1 : Math.max(0, today.optInt("queued", 0));
        return new JournalStatus(state, sent, queued, Math.max(0, json.optInt("pending", 0)),
                Math.max(0, json.optInt("failed", 0)));
    }

    boolean canWrite() {
        return state != State.DISCONNECTED;
    }

    /** "3 sent · 1 queued", "Nothing yet today"; older runtimes without today counts: "2 queued". */
    String todayLine() {
        StringBuilder out = new StringBuilder();
        if (sentToday < 0) {
            if (pending > 0) out.append(pending).append(" queued");
        } else {
            if (sentToday > 0) out.append(sentToday).append(" sent");
            int queued = Math.max(queuedToday, state == State.RECONNECT ? pending : 0);
            if (queued > 0) out.append(out.length() > 0 ? " · " : "").append(queued).append(" queued");
        }
        if (failed > 0) out.append(out.length() > 0 ? " · " : "").append(failed).append(" failed");
        if (out.length() == 0) return sentToday < 0 ? "Ready for a note" : "Nothing yet today";
        return out.toString();
    }

    /** Header chip text: "Heptabase", "Reconnect", "Not connected". */
    String chip() {
        return switch (state) {
            case CONNECTED -> "Heptabase";
            case RECONNECT -> "Reconnect";
            case DISCONNECTED -> "Not connected";
        };
    }

    /** Header chip while waiting for a reconnect: what is held back ("2 queued"), else "Reconnect". */
    String reconnectChip() {
        int queued = Math.max(Math.max(queuedToday, 0), pending);
        return queued > 0 ? queued + " queued" : "Reconnect";
    }

    /** What to say after {@code POST /v1/journal/notes} answered {@code result}. */
    static String noteSaved(JSONObject result) {
        String state = result == null || result.isNull("state") ? "" : result.optString("state", "");
        return "sent".equals(state) ? "Added to today's journal" : "Saved · sends when Heptabase is reachable";
    }

    /** What to say when the note was not recorded (HTTP status and runtime error code). */
    static String noteFailed(int httpStatus, String code) {
        if (httpStatus == 409 || "heptabase_not_connected".equals(code)) return "Heptabase isn't connected";
        if (httpStatus == 400) return "That note couldn't be saved";
        if (httpStatus == 0 && "runtime_timeout".equals(code)) return "Still saving… check again soon";
        if (httpStatus == 0) return "Runtime offline · note not saved";
        return "Couldn't save the note";
    }
}
