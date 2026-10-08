package com.resonolabs.feature.voice;

/**
 * One entry of the Voice transcript (main thread). {@link Kind#TEXT} is a spoken or sent
 * message; {@link Kind#IMAGE} a picture in the conversation (a camera photo the user sent, a
 * Mac screenshot a tool returned); {@link Kind#UI} a UI generated on the Mac (thumbnail, title
 * and a "Generated" label). Pictures are {@code GenImages} refs; their thumbnails come from that
 * store's LRU (a placeholder is drawn while one decodes or after it was evicted).
 *
 * <p>The layout fields are owned by {@link TranscriptLayout} and recomputed only when the item
 * is {@link #dirty} (new, or its text changed), never per frame.
 */
final class TranscriptItem {
    enum Kind { TEXT, IMAGE, UI }

    static final String USER = "user";
    static final String ASSISTANT = "assistant";

    final String role;
    final Kind kind;
    /** TEXT: the words; IMAGE: caption; UI: the generated UI's title. */
    private String text;
    /** IMAGE/UI: the picture ({@code sha256:<hex>}), or null when unavailable. */
    final String imageRef;
    /** height / width of the picture. */
    final float aspect;
    /** UI: summary; IMAGE: source label. */
    final String detail;
    /** camera | mac_screenshot | generated_ui (IMAGE/UI). */
    final String source;
    /** UI: the artifact id (dedupe, notification taps). */
    final String artifactId;

    // ---- layout (TranscriptLayout) ----
    boolean dirty = true;
    float top;
    float height;
    String[] lines = new String[0];
    float bubbleWidth;
    float frameWidth;
    float frameHeight;
    String titleLine = "";
    String detailLine = "";

    private TranscriptItem(String role, Kind kind, String text, String imageRef, float aspect, String detail,
                           String source, String artifactId) {
        this.role = role;
        this.kind = kind;
        this.text = text == null ? "" : text.trim();
        this.imageRef = imageRef;
        this.aspect = aspect > 0f && !Float.isNaN(aspect) ? aspect : 0.75f;
        this.detail = detail == null ? "" : detail.trim();
        this.source = source;
        this.artifactId = artifactId;
    }

    static TranscriptItem text(String role, String text) {
        return new TranscriptItem(role, Kind.TEXT, text, null, 0.75f, null, null, null);
    }

    static TranscriptItem image(String role, String imageRef, float aspect, String caption, String source) {
        return new TranscriptItem(role, Kind.IMAGE, caption, imageRef, aspect, null, source, null);
    }

    static TranscriptItem generatedUi(String imageRef, float aspect, String title, String summary, String artifactId) {
        return new TranscriptItem(ASSISTANT, Kind.UI, title, imageRef, aspect, summary, "generated_ui", artifactId);
    }

    String text() {
        return text;
    }

    /** Streaming drafts and interrupt marks update the words in place. */
    void setText(String value) {
        String next = value == null ? "" : value.trim();
        if (next.equals(text)) return;
        text = next;
        dirty = true;
    }

    boolean user() {
        return USER.equals(role);
    }

    boolean hasPicture() {
        return kind != Kind.TEXT && imageRef != null;
    }
}
