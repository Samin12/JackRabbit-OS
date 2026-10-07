package com.resonolabs.feature.genui;

import android.content.Context;

import org.json.JSONObject;

import java.util.List;

/**
 * Executes the Realtime card tools locally (no runtime round trip) and handles card
 * actions. Main thread only. Outputs are the tiny {@code function_call_output} strings from
 * genui.md section 6.4, e.g. {@code {"ok":true,"id":"groceries","shown":"front","stack":2,"trimmed":[]}}.
 */
public final class GenUiController implements GenCardStore.Listener, AutoCloseable {
    /** Implemented by the Voice page (or the debug preview). */
    public interface Host {
        /** Sends text as the user's next message and requests a response. False if no live session. */
        boolean sendUserText(String text);

        /** Adds a system message item; requests a response only if {@code respond}. */
        boolean sendSystemNote(String text, boolean respond);

        /** Navigates: calendar, tasks, cards, runs or transcript. */
        void open(String page);

        /** Starts a voice session and sends {@code text} once live (instead of the greeting). */
        void startSessionWith(String text);

        void invalidateUi();

        /** True hides the chrome bar (EXPANDED dock). */
        void setImmersive(boolean immersive);

        /** A live timer finished; the host may ask the model to mention it. */
        default void onTimerFinished(GenCard card) { }

        /** A {@link GenAction.Kind#HOST} button on an app-built card was tapped. */
        default void onHostAction(GenCard card, String action) { }
    }

    public static final int SHOWS_PER_RESPONSE = 2;
    public static final int NEW_IDS_MAX = 4;
    public static final long NEW_IDS_WINDOW_MS = 120_000L;
    private static final long FOCUS_NOTE_GAP_MS = 10_000L;

    private final GenCardStore store;
    private final LiveSourceRegistry registry;
    private final Host host;
    private final long[] newIdTimes = new long[NEW_IDS_MAX];
    private int newIdCursor;
    private int showsThisResponse;
    private String sessionId;
    private String lastTranscriptLine = "";
    private String lastFocusId;
    private long lastFocusAt;

    public GenUiController(Context context, Host host) {
        this(GenCardStore.get(context), LiveSourceRegistry.get(context), host);
    }

    public GenUiController(GenCardStore store, LiveSourceRegistry registry, Host host) {
        this.store = store;
        this.registry = registry;
        this.host = host;
        java.util.Arrays.fill(newIdTimes, Long.MIN_VALUE / 2);
        store.addListener(this);
    }

    public GenCardStore store() {
        return store;
    }

    public LiveSourceRegistry registry() {
        return registry;
    }

    Host host() {
        return host;
    }

    @Override public void close() {
        store.removeListener(this);
    }

    // ------------------------------------------------------------------ session hooks

    public void onResponseCreated() {
        showsThisResponse = 0;
    }

    /** Clears both show budgets (fixtures and tests; never called for model traffic). */
    void resetBudgets() {
        showsThisResponse = 0;
        java.util.Arrays.fill(newIdTimes, Long.MIN_VALUE / 2);
    }

    public void onSessionStarted(String id) {
        sessionId = id;
        showsThisResponse = 0;
    }

    public void onSessionEnded() {
        sessionId = null;
        showsThisResponse = 0;
        store.onSessionEnded();
    }

    public String screenSummary() {
        return store.screenSummary();
    }

    public boolean hasScreenSummary() {
        return !store.screenSummary().isEmpty();
    }

    /** One line for {@code recordTranscript} describing the last executed tool call. */
    public String lastTranscriptLine() {
        return lastTranscriptLine;
    }

    // ------------------------------------------------------------------ tools

    /** Runs show_card / update_card / dismiss_card and returns the function_call_output string. */
    public String execute(String tool, String raw, long now) {
        // Every call sets its own line; a failed call must not leave the previous one behind.
        lastTranscriptLine = "";
        try {
            if (GenUiTools.SHOW_CARD.equals(tool)) return show(raw, now);
            if (GenUiTools.UPDATE_CARD.equals(tool)) return update(raw, now);
            if (GenUiTools.DISMISS_CARD.equals(tool)) return dismiss(raw);
            return error("Unknown card tool.");
        } catch (RuntimeException failure) {
            // A model payload must never crash the Voice page.
            android.util.Log.w("GenUi", "card tool failed: " + failure.getClass().getSimpleName());
            lastTranscriptLine = "";
            return error("The card could not be shown.");
        }
    }

    private String show(String raw, long now) {
        if (showsThisResponse >= SHOWS_PER_RESPONSE) {
            lastTranscriptLine = "";
            return error("Too many cards in one reply. Update an existing card instead.");
        }
        GenCardParser.ParseResult parsed = GenCardParser.parseShow(raw, now);
        if (!parsed.ok) {
            lastTranscriptLine = "";
            return error(parsed.error);
        }
        GenCard card = parsed.card;
        boolean isNew = store.find(card.id) == null;
        if (isNew && !allowNewId(now)) {
            lastTranscriptLine = "";
            return error("Too many new cards. Update or reuse an existing card instead.");
        }
        card.originSessionId = sessionId;
        GenCardStore.PutResult put = store.put(card);
        if (isNew) noteNewId(now);
        showsThisResponse++;
        lastTranscriptLine = "[Card] " + describe(card);
        StringBuilder out = new StringBuilder(96);
        out.append("{\"ok\":true,\"id\":").append(JSONObject.quote(card.id))
                .append(",\"shown\":\"front\",\"stack\":").append(put.stackSize)
                .append(",\"trimmed\":");
        appendStrings(out, parsed.trimmed, put.notes);
        return out.append('}').toString();
    }

    private String update(String raw, long now) {
        JSONObject json;
        try {
            json = GenCardParser.parseArguments(raw);
        } catch (IllegalArgumentException invalid) {
            lastTranscriptLine = "";
            return error(invalid.getMessage());
        }
        String id = GenCardParser.normalizeId(GenCardParser.value(json, "id"));
        if (id.isEmpty()) return error("id is required.");
        GenCard card = store.find(id);
        if (card == null) {
            lastTranscriptLine = "";
            return error(missing(id));
        }
        if (hasHostAction(card)) {
            // An app-built card with trusted buttons (T3 Approve/Deny): the model must not be
            // able to change what the user reads next to a button that acts on the real request.
            lastTranscriptLine = "";
            return error("Card " + card.id + " is managed by the R1 and cannot be updated; "
                    + "dismiss it or show a new card.");
        }
        GenCardParser.UpdateResult result = GenCardParser.applyUpdate(card, json, now);
        if (!result.ok) return error(result.error);
        if (registry != null && card.isTimer()) registry.onTimerChanged(card);
        store.changed(card, true);
        lastTranscriptLine = "[Card update] " + describe(card);
        StringBuilder out = new StringBuilder(80);
        out.append("{\"ok\":true,\"id\":").append(JSONObject.quote(card.id)).append(",\"changed\":");
        appendStrings(out, result.changed, List.of());
        if (!result.trimmed.isEmpty()) {
            out.append(",\"trimmed\":");
            appendStrings(out, result.trimmed, List.of());
        }
        return out.append('}').toString();
    }

    private String dismiss(String raw) {
        GenCardParser.DismissRequest request = GenCardParser.parseDismiss(raw);
        if (!request.ok) return error(request.error);
        java.util.ArrayList<String> dismissed = new java.util.ArrayList<>();
        if (request.all) {
            if (request.id != null && store.dismiss(request.id, false)) dismissed.add(request.id);
            dismissed.addAll(store.dismissAll(request.includeTimers));
        } else {
            if (!store.dismiss(request.id, false)) {
                lastTranscriptLine = "";
                return error(missing(request.id));
            }
            dismissed.add(request.id);
        }
        lastTranscriptLine = "[Cards dismissed] " + String.join(", ", dismissed);
        StringBuilder out = new StringBuilder(48).append("{\"ok\":true,\"dismissed\":");
        appendStrings(out, dismissed, List.of());
        return out.append('}').toString();
    }

    private String missing(String id) {
        if (store.wasDismissedByUser(id)) return "No card with id '" + id + "' (the user dismissed it).";
        for (GenCard card : store.recent()) {
            if (card.id.equals(id)) return "No card with id '" + id + "' (it expired; show it again if needed).";
        }
        return "No card with id '" + id + "'.";
    }

    private boolean allowNewId(long now) {
        int recentCount = 0;
        for (long time : newIdTimes) if (now - time < NEW_IDS_WINDOW_MS) recentCount++;
        return recentCount < NEW_IDS_MAX;
    }

    private void noteNewId(long now) {
        newIdTimes[newIdCursor] = now;
        newIdCursor = (newIdCursor + 1) % newIdTimes.length;
    }

    // ------------------------------------------------------------------ app-built cards

    /**
     * Shows (or replaces, by id) a card built by the app itself rather than the model, e.g. a
     * T3 announcement card. Parsed as trusted, so it may carry {@code host} buttons and
     * {@code open} targets the model cannot use; the model's show budgets are not touched.
     * Returns the card now in the store, or null if the JSON did not validate.
     */
    public GenCard showHostCard(JSONObject json) {
        GenCardParser.ParseResult parsed = GenCardParser.parseCard(json, store.now(), true);
        if (!parsed.ok) return null;
        GenCard card = parsed.card;
        card.originSessionId = sessionId;
        store.put(card);
        return card;
    }

    /** The active card (stack or deck) following {@code type}/{@code threadId}, or null. */
    public GenCard findLiveCard(LiveBinding.Type type, String threadId) {
        if (type == null || threadId == null) return null;
        for (GenCard card : store.activeCards()) {
            if (card.live != null && card.live.type == type && threadId.equals(card.live.threadId)) return card;
        }
        return null;
    }

    // ------------------------------------------------------------------ user actions

    /** A pill/button tap. */
    public void onAction(GenCard card, GenAction action) {
        switch (action.kind) {
            case SAY -> say(action.arg);
            case OPEN -> host.open(action.arg);
            case TIMER -> timerOp(card, action.arg);
            case DISMISS -> dismissByUser(card);
            case HOST -> host.onHostAction(card, action.arg);
        }
        host.invalidateUi();
    }

    public void say(String text) {
        if (text == null || text.isBlank()) return;
        if (!host.sendUserText(text)) host.startSessionWith(text);
    }

    public void timerOp(GenCard card, String op) {
        GenBlock timer = card.timerBlock();
        if (timer == null) return;
        long now = store.now();
        switch (op) {
            case "add1m" -> GenTimers.add(timer, now, 60_000L);
            case "add5m" -> GenTimers.add(timer, now, 300_000L);
            case "pause" -> GenTimers.pause(timer, now);
            case "resume" -> GenTimers.resume(timer, now);
            default -> { return; }
        }
        if (!timer.done) {
            card.terminal = false;
            card.state = GenCard.State.ACTIVE;
        }
        if (registry != null) registry.onTimerChanged(card);
        store.changed(card, true);
    }

    /** Stop a ringing (or running) timer and remove its card. */
    public void stopTimer(GenCard card) {
        if (registry != null) registry.silence(card);
        dismissByUser(card);
    }

    public void dismissByUser(GenCard card) {
        if (registry != null) registry.silence(card);
        store.dismiss(card.id, true);
        host.invalidateUi();
    }

    /** Checklist row tap: toggles locally and tells the model silently, or sends the row prompt. */
    public void onRowTapped(GenCard card, GenBlock block, int row) {
        if (block.items == null || row < 0 || row >= block.items.length) return;
        GenRow item = block.items[row];
        if (block.rowSay != null) {
            say(block.rowSay.replace("%s", item.title));
            return;
        }
        if (block.type != GenBlock.Type.CHECKLIST) return;
        item.checked = !item.checked;
        store.changed(card, true);
        host.sendSystemNote("[UI event] User " + (item.checked ? "checked" : "unchecked") + " '"
                + item.title + "' on card " + card.id + ".", false);
        host.invalidateUi();
    }

    /** Tap on a card body with nothing hidden: make it the conversation topic, silently. */
    public void onCardFocused(GenCard card) {
        long now = store.now();
        if (card.id.equals(lastFocusId) && now - lastFocusAt < FOCUS_NOTE_GAP_MS) return;
        lastFocusId = card.id;
        lastFocusAt = now;
        host.sendSystemNote("[UI event] The user is looking at card '" + card.id + "'.", false);
    }

    @Override public void onCardsChanged() {
        host.invalidateUi();
    }

    @Override public void onCardEvent(int event, GenCard card) {
        if (event == GenCardStore.EVENT_TIMER_DONE) host.onTimerFinished(card);
    }

    // ------------------------------------------------------------------ text

    /** Short plain description of a card for transcripts and memory review. */
    static String describe(GenCard card) {
        StringBuilder out = new StringBuilder(card.displayTitle());
        int added = 0;
        for (GenBlock block : card.body) {
            if (out.length() > 180) break;
            switch (block.type) {
                case LIST, CHECKLIST -> {
                    for (GenRow row : block.items) {
                        out.append(added++ == 0 ? ": " : ", ").append(row.title);
                        if (out.length() > 180) break;
                    }
                }
                case STAT -> out.append(added++ == 0 ? ": " : ", ").append(block.value)
                        .append(block.label != null ? " " + block.label : "");
                case TEXT -> out.append(added++ == 0 ? ": " : ", ").append(block.text);
                case WEATHER -> out.append(added++ == 0 ? ": " : ", ")
                        .append(block.temp != null ? block.temp + " " : "")
                        .append(GenSchema.CONDITIONS[block.condition]);
                case TIMER -> out.append(added++ == 0 ? ": " : ", ").append("timer ")
                        .append(GenTimers.brief(block.totalMs));
                default -> { }
            }
        }
        String text = out.toString();
        return text.length() > 200 ? text.substring(0, 199) + "…" : text;
    }

    private static boolean hasHostAction(GenCard card) {
        for (GenAction action : card.actions) {
            if (action.kind == GenAction.Kind.HOST) return true;
        }
        return false;
    }

    private static String error(String message) {
        return "{\"ok\":false,\"error\":" + JSONObject.quote(message) + "}";
    }

    private static void appendStrings(StringBuilder out, List<String> first, List<String> second) {
        out.append('[');
        int count = 0;
        for (String value : first) {
            if (count++ > 0) out.append(',');
            out.append(JSONObject.quote(value));
        }
        for (String value : second) {
            if (count++ > 0) out.append(',');
            out.append(JSONObject.quote(value));
        }
        out.append(']');
    }
}
