package com.resonolabs.feature.genui;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Maps a runtime announcement ({@code GET /v1/host/announcements/next}, kinds
 * {@code t3.thread.*}) to an app-built GenUI live card bound to that T3 thread
 * ({@code live.type = t3-thread}). Pure: JSON in, card JSON out; the caller shows it with
 * {@link GenUiController#showHostCard}.
 *
 * <p>Buttons: every card has <b>Open</b> ({@code open: "t3:<threadId>"}, the T3 tab). An approval
 * with a known requestId gets <b>Deny</b> / <b>Approve</b> as {@code host} buttons that the app
 * answers directly through the T3 approval route (exact requestId, works with or without a
 * live voice session, same as the T3 tab's buttons). Without a requestId they fall back to
 * {@code say} buttons, so the model resolves the request with {@code t3_respond}. A question
 * gets <b>Answer</b>, a say button: answering is a conversation.
 */
public final class T3AnnouncementCards {
    public static final String KIND_PREFIX = "t3.thread.";
    public static final String FINISHED = "t3.thread.finished";
    public static final String NEEDS_APPROVAL = "t3.thread.needs_approval";
    public static final String NEEDS_INPUT = "t3.thread.needs_input";
    public static final String ERROR = "t3.thread.error";
    /** {@code open} target prefix the Voice page hands to the shell: {@code t3:<threadId>}. */
    public static final String OPEN_PREFIX = "t3:";
    private static final String APPROVAL_PREFIX = "t3.approval|";
    private static final String EYEBROW = "T3 Code";

    /** An approval button's decoded host action. */
    public static final class Approval {
        public final String decision;
        public final String threadId;
        public final String requestId;

        Approval(String decision, String threadId, String requestId) {
            this.decision = decision;
            this.threadId = threadId;
            this.requestId = requestId;
        }

        public boolean accept() {
            return "accept".equals(decision);
        }
    }

    private T3AnnouncementCards() {}

    public static boolean isT3(String kind) {
        return kind != null && kind.startsWith(KIND_PREFIX);
    }

    /** {@code payload.threadId}, or null. */
    public static String threadId(JSONObject announcement) {
        return text(payload(announcement), "threadId");
    }

    /** Stable card id for a thread: {@code t3-<id>} in the card id alphabet (max 40 chars). */
    public static String cardId(String threadId) {
        return GenCardParser.normalizeId("t3-" + (threadId == null ? "" : threadId));
    }

    public static String openTarget(String threadId) {
        return OPEN_PREFIX + threadId;
    }

    /** The thread id from an {@code open} target, or null if it is not a T3 target. */
    public static String threadFromOpenTarget(String page) {
        if (page == null || !page.startsWith(OPEN_PREFIX)) return null;
        String id = page.substring(OPEN_PREFIX.length()).trim();
        return id.isEmpty() ? null : id;
    }

    public static String approvalAction(String decision, String threadId, String requestId) {
        return APPROVAL_PREFIX + decision + "|" + threadId + "|" + requestId;
    }

    /** Decodes {@link #approvalAction}; null for anything else. */
    public static Approval parseApproval(String action) {
        if (action == null || !action.startsWith(APPROVAL_PREFIX)) return null;
        String[] parts = action.substring(APPROVAL_PREFIX.length()).split("\\|", -1);
        if (parts.length != 3) return null;
        String decision = parts[0];
        if (!"accept".equals(decision) && !"decline".equals(decision)) return null;
        if (parts[1].isEmpty() || parts[2].isEmpty()) return null;
        return new Approval(decision, parts[1], parts[2]);
    }

    /**
     * The card for one announcement, or null if it is not a T3 thread announcement.
     * {@code existingId} keeps the id of a card already following this thread (for example
     * one the model showed after t3_new_thread) so the thread never gets two cards.
     */
    public static JSONObject cardJson(JSONObject announcement, String existingId) {
        if (announcement == null) return null;
        String kind = text(announcement, "kind");
        JSONObject payload = payload(announcement);
        String threadId = text(payload, "threadId");
        if (!isT3(kind) || threadId == null) return null;
        String title = first(text(payload, "title"), text(announcement, "title"), "T3 thread");
        String project = text(payload, "projectTitle");
        String requestId = text(payload, "requestId");
        String state;
        String accent;
        boolean compact;
        String detail;
        switch (kind) {
            case FINISHED -> {
                state = "Finished";
                accent = "green";
                compact = true;
                detail = text(payload, "lastMessage");
            }
            case NEEDS_APPROVAL -> {
                state = "Needs your approval";
                accent = "amber";
                compact = false;
                detail = first(text(payload, "detail"), text(payload, "lastMessage"), null);
            }
            case NEEDS_INPUT -> {
                state = "Has a question";
                accent = "amber";
                compact = false;
                detail = first(text(payload, "question"), text(payload, "lastMessage"), null);
            }
            case ERROR -> {
                state = "Hit an error";
                accent = "red";
                compact = true;
                detail = first(text(payload, "error"), text(payload, "lastMessage"), null);
            }
            default -> {
                state = first(text(payload, "statusLabel"), "Updated", null);
                accent = "blue";
                compact = true;
                detail = text(payload, "lastMessage");
            }
        }
        try {
            JSONObject card = new JSONObject();
            card.put("id", existingId != null && !existingId.isEmpty() ? existingId : cardId(threadId));
            card.put("title", title);
            card.put("subtitle", project == null ? state : state + " · " + project);
            card.put("eyebrow", EYEBROW);
            card.put("icon", "code");
            card.put("accent", accent);
            card.put("size", compact ? "compact" : "card");
            card.put("live", new JSONObject().put("type", LiveBinding.Type.T3_THREAD.wire).put("threadId", threadId));
            JSONArray body = new JSONArray();
            if (detail != null) {
                body.put(new JSONObject().put("type", "text").put("id", "last").put("style", "muted").put("text", detail));
            }
            card.put("body", body);
            JSONArray actions = new JSONArray();
            switch (kind) {
                case NEEDS_APPROVAL -> {
                    actions.put(open(threadId, "secondary"));
                    if (requestId != null && !requestId.contains("|") && !threadId.contains("|")) {
                        actions.put(host("Deny", "secondary", approvalAction("decline", threadId, requestId)));
                        actions.put(host("Approve", "primary", approvalAction("accept", threadId, requestId)));
                    } else {
                        actions.put(say("Deny", "secondary",
                                "Decline the pending request on the T3 thread “" + title + "”."));
                        actions.put(say("Approve", "primary",
                                "Approve the pending request on the T3 thread “" + title + "”."));
                    }
                }
                case NEEDS_INPUT -> {
                    actions.put(open(threadId, "secondary"));
                    actions.put(say("Answer", "primary",
                            "I want to answer the question from the T3 thread “" + title + "”."));
                }
                default -> actions.put(open(threadId, "primary"));
            }
            card.put("actions", actions);
            return card;
        } catch (Exception impossible) {
            return null;
        }
    }

    /**
     * The card right after the user answered an approval from it: same id and thread, the
     * decision as subtitle, only Open left; its live source then shows the thread resuming.
     */
    public static JSONObject afterDecision(String cardId, String threadId, String title, boolean accepted) {
        if (cardId == null || threadId == null) return null;
        try {
            JSONObject card = new JSONObject();
            card.put("id", cardId);
            card.put("title", title == null || title.isBlank() ? "T3 thread" : title);
            card.put("subtitle", accepted ? "Approved · resuming" : "Declined");
            card.put("eyebrow", EYEBROW);
            card.put("icon", "code");
            card.put("accent", accepted ? "blue" : "amber");
            card.put("size", "compact");
            card.put("live", new JSONObject().put("type", LiveBinding.Type.T3_THREAD.wire).put("threadId", threadId));
            card.put("actions", new JSONArray().put(open(threadId, "primary")));
            return card;
        } catch (Exception impossible) {
            return null;
        }
    }

    private static JSONObject open(String threadId, String style) throws Exception {
        return new JSONObject().put("label", "Open").put("style", style).put("open", openTarget(threadId));
    }

    private static JSONObject host(String label, String style, String action) throws Exception {
        return new JSONObject().put("label", label).put("style", style).put("host", action);
    }

    private static JSONObject say(String label, String style, String text) throws Exception {
        return new JSONObject().put("label", label).put("style", style).put("say", text);
    }

    private static JSONObject payload(JSONObject announcement) {
        if (announcement == null) return null;
        Object value = announcement.opt("payload");
        return value instanceof JSONObject object ? object : null;
    }

    /** Null-safe string read: JSON null, missing, non-strings and blanks are null (never "null"). */
    static String text(JSONObject json, String key) {
        if (json == null) return null;
        Object value = json.opt(key);
        if (!(value instanceof String text)) return null;
        String trimmed = text.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String first(String a, String b, String fallback) {
        if (a != null) return a;
        if (b != null) return b;
        return fallback;
    }
}
