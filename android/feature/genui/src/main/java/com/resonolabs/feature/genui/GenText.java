package com.resonolabs.feature.genui;

import android.graphics.Paint;

import java.util.ArrayList;

/** Measuring-time text fitting (layout only; never called from draw). */
final class GenText {
    static final String ELLIPSIS = "…";

    private GenText() {}

    static String ellipsize(String value, Paint paint, float maxWidth) {
        if (value == null || value.isEmpty()) return "";
        if (maxWidth <= 0f) return "";
        if (paint.measureText(value) <= maxWidth) return value;
        float room = maxWidth - paint.measureText(ELLIPSIS);
        if (room <= 0f) return ELLIPSIS;
        int count = paint.breakText(value, true, room, null);
        if (count > 0 && Character.isHighSurrogate(value.charAt(count - 1))) count--;
        return value.substring(0, Math.max(0, count)).stripTrailing() + ELLIPSIS;
    }

    /** Word-wraps to at most {@code maxLines}; the last line is ellipsized if text remains. */
    static String[] wrap(String value, Paint paint, float maxWidth, int maxLines) {
        ArrayList<String> lines = new ArrayList<>();
        String remaining = value == null ? "" : value.trim();
        while (!remaining.isEmpty() && lines.size() < maxLines) {
            int count = paint.breakText(remaining, true, maxWidth, null);
            if (count <= 0) count = 1;
            if (count < remaining.length()) {
                int space = remaining.lastIndexOf(' ', count);
                if (space > 0) count = space;
            }
            String line = remaining.substring(0, count).trim();
            remaining = remaining.substring(count).trim();
            if (lines.size() == maxLines - 1 && !remaining.isEmpty()) {
                line = ellipsize(line + " " + remaining, paint, maxWidth);
                remaining = "";
            }
            lines.add(line);
        }
        return lines.toArray(new String[0]);
    }

    /** Largest text size (step 2 px, at least {@code min}) at which {@code value} fits. */
    static float fitSize(String value, Paint paint, float maxSize, float minSize, float maxWidth) {
        float size = maxSize;
        float original = paint.getTextSize();
        while (size > minSize) {
            paint.setTextSize(size);
            if (paint.measureText(value) <= maxWidth) break;
            size -= 2f;
        }
        paint.setTextSize(original);
        return Math.max(minSize, size);
    }
}
