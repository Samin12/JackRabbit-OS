package com.resonolabs.feature.voice;

import java.util.ArrayList;
import java.util.List;

/**
 * Geometry of the Voice transcript in the 480x640 logical space (pure; unit-tested with a fake
 * {@link Measurer}). Items are measured only when {@link TranscriptItem#dirty}; positioning the
 * whole list is an allocation-free loop, so it can run every frame.
 *
 * <pre>
 * clip area     y 196..518 (322 px), newest at the bottom
 * TEXT user     glass bubble, right edge 452, wrap 300, 22 px lines, height lines*22+20
 * TEXT model    plain text from x 30, wrap 404, height lines*22+8
 * IMAGE         rounded picture frame (user: right-aligned, max 264 wide; host: left, max 360),
 *               height clamp(w*aspect, 96, 236) + a 24 px caption row
 * UI            glass panel 24..456: "Generated" label, thumbnail (max 200 tall), title, summary
 * gap           12 px between items
 * </pre>
 */
final class TranscriptLayout {
    /** Text measurement (a Paint on the device). */
    interface Measurer {
        float width(String text, float size, boolean bold);

        /** How many leading chars of {@code text} fit in {@code maxWidth}. */
        int fit(String text, float size, boolean bold, float maxWidth);
    }

    static final float TOP = 196f;
    static final float BOTTOM = 518f;
    static final float GAP = 12f;
    static final float TEXT_SIZE = 16f;
    static final float LINE = 22f;
    static final float USER_WRAP = 300f;
    static final float USER_RIGHT = 452f;
    static final float ASSISTANT_LEFT = 30f;
    static final float ASSISTANT_WRAP = 404f;
    static final float IMAGE_USER_MAX_W = 264f;
    static final float IMAGE_HOST_MAX_W = 360f;
    static final float IMAGE_MIN_W = 120f;
    static final float IMAGE_MIN_H = 96f;
    static final float IMAGE_MAX_H = 236f;
    static final float CAPTION_H = 24f;
    static final float CAPTION_SIZE = 13f;
    static final float UI_LEFT = 24f;
    static final float UI_RIGHT = 456f;
    static final float UI_PAD = 12f;
    static final float UI_LABEL_H = 18f;
    static final float UI_THUMB_MAX_H = 200f;
    static final float UI_TITLE_SIZE = 16f;
    static final float UI_TITLE_H = 22f;
    static final float UI_SUMMARY_SIZE = 13f;
    static final float UI_SUMMARY_H = 18f;
    static final String ELLIPSIS = "…";

    private TranscriptLayout() {}

    static float viewport() {
        return BOTTOM - TOP;
    }

    /** Measures dirty items, stacks all of them from y 0 and returns the content height. */
    static float layout(List<TranscriptItem> items, Measurer measurer) {
        float y = 0f;
        for (int index = 0; index < items.size(); index++) {
            TranscriptItem item = items.get(index);
            if (item.dirty) {
                measure(item, measurer);
                item.dirty = false;
            }
            item.top = y;
            y += item.height + GAP;
        }
        return y;
    }

    static void measure(TranscriptItem item, Measurer measurer) {
        switch (item.kind) {
            case TEXT -> {
                boolean user = item.user();
                item.lines = wrap(item.text(), user ? USER_WRAP : ASSISTANT_WRAP, measurer);
                float widest = 0f;
                if (user) {
                    for (String line : item.lines) widest = Math.max(widest, measurer.width(line, TEXT_SIZE, false));
                }
                item.bubbleWidth = widest + 28f;
                item.height = item.lines.length * LINE + (user ? 20f : 8f);
            }
            case IMAGE -> {
                float maxWidth = item.user() ? IMAGE_USER_MAX_W : IMAGE_HOST_MAX_W;
                float[] frame = frame(item.aspect, maxWidth, IMAGE_MAX_H);
                item.frameWidth = frame[0];
                item.frameHeight = frame[1];
                item.titleLine = ellipsize(item.text(), CAPTION_SIZE, false, item.frameWidth, measurer);
                item.height = item.frameHeight + CAPTION_H;
            }
            case UI -> {
                float inner = UI_RIGHT - UI_LEFT - 2f * UI_PAD;
                item.frameWidth = inner;
                item.frameHeight = Math.max(IMAGE_MIN_H, Math.min(UI_THUMB_MAX_H, inner * item.aspect));
                item.titleLine = ellipsize(item.text().isEmpty() ? "Generated UI" : item.text(),
                        UI_TITLE_SIZE, true, inner, measurer);
                item.detailLine = ellipsize(item.detail, UI_SUMMARY_SIZE, false, inner, measurer);
                item.height = UI_PAD + UI_LABEL_H + 8f + item.frameHeight + 10f + UI_TITLE_H
                        + (item.detailLine.isEmpty() ? 0f : UI_SUMMARY_H) + UI_PAD;
            }
        }
    }

    /**
     * Picture frame {width, height}: as wide as allowed, height clamped to [96, maxHeight];
     * a tall picture gets a narrower frame (never under 120 wide).
     */
    static float[] frame(float aspect, float maxWidth, float maxHeight) {
        float ratio = aspect > 0f && !Float.isNaN(aspect) ? aspect : 0.75f;
        float height = Math.max(IMAGE_MIN_H, Math.min(maxHeight, maxWidth * ratio));
        float width = Math.max(IMAGE_MIN_W, Math.min(maxWidth, height / ratio));
        return new float[]{width, height};
    }

    /** Index of the item under content y ({@code y - TOP + scroll}), or -1 (gaps included as misses). */
    static int itemAt(List<TranscriptItem> items, float contentY) {
        for (int index = 0; index < items.size(); index++) {
            TranscriptItem item = items.get(index);
            if (contentY >= item.top && contentY < item.top + item.height) return index;
            if (item.top > contentY) return -1;
        }
        return -1;
    }

    /** The scroll offset that shows the newest content (0 when it all fits). */
    static float maxScroll(float contentHeight) {
        return Math.max(0f, contentHeight - viewport());
    }

    static String[] wrap(String value, float width, Measurer measurer) {
        ArrayList<String> lines = new ArrayList<>();
        String remaining = value == null ? "" : value.trim();
        while (!remaining.isEmpty()) {
            int count = measurer.fit(remaining, TEXT_SIZE, false, width);
            if (count < remaining.length()) {
                int space = remaining.lastIndexOf(' ', Math.max(0, count - 1));
                if (space > 0) count = space;
            }
            count = Math.max(1, Math.min(remaining.length(), count));
            lines.add(remaining.substring(0, count).trim());
            remaining = remaining.substring(count).trim();
        }
        return lines.toArray(new String[0]);
    }

    static String ellipsize(String value, float size, boolean bold, float width, Measurer measurer) {
        String text = value == null ? "" : value.replace('\n', ' ').trim();
        if (text.isEmpty() || measurer.width(text, size, bold) <= width) return text;
        float room = Math.max(0f, width - measurer.width(ELLIPSIS, size, bold));
        int count = Math.max(0, Math.min(text.length(), measurer.fit(text, size, bold, room)));
        return text.substring(0, count).trim() + ELLIPSIS;
    }
}
