package com.resonolabs.feature.cards.board;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;

import com.resonolabs.ui.design.SamTheme;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared type, color and text-fitting rules for board widgets. Text fitting allocates and belongs
 * in {@link BoardWidget#measure}; the draw helpers never allocate.
 */
public final class BoardPaint {
    public static final Typeface REGULAR = Typeface.create("sans-serif", Typeface.NORMAL);
    public static final Typeface MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    public static final Typeface LIGHT = Typeface.create("sans-serif-light", Typeface.NORMAL);
    /** Done / ok green; the orb theme has no green token. */
    public static final int SUCCESS = Color.rgb(96, 214, 160);
    public static final int CALENDAR_ACCENT = SamTheme.PINK;
    public static final int TASKS_ACCENT = SamTheme.AMBER;
    private static final int[] CALENDAR_COLORS = {
            SamTheme.PINK, SamTheme.CYAN, SamTheme.AMBER, SamTheme.VIOLET, SUCCESS, SamTheme.RED,
            Color.rgb(255, 150, 92)};

    private BoardPaint() {}

    /**
     * Color per calendar (the runtime projection has no calendar color yet): distinct calendars get
     * distinct palette colors in name order, so the assignment is stable while the set is stable.
     */
    public static java.util.Map<String, Integer> calendarColors(java.util.Collection<String> calendars) {
        java.util.TreeSet<String> names = new java.util.TreeSet<>();
        for (String name : calendars) names.add(key(name));
        java.util.Map<String, Integer> colors = new java.util.HashMap<>();
        int index = 0;
        for (String name : names) colors.put(name, name.isEmpty() ? SamTheme.ORB_PALE : CALENDAR_COLORS[index++ % CALENDAR_COLORS.length]);
        return colors;
    }

    public static int calendarColor(java.util.Map<String, Integer> colors, String calendar) {
        Integer color = colors.get(key(calendar));
        return color == null ? SamTheme.ORB_PALE : color;
    }

    private static String key(String calendar) {
        return calendar == null ? "" : calendar.trim().toLowerCase(java.util.Locale.ROOT);
    }

    public static void text(Canvas canvas, Paint paint, String value, float x, float baseline, float size,
                            int color, Paint.Align align, Typeface face) {
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(color);
        paint.setTextAlign(align);
        paint.setTextSize(size);
        paint.setTypeface(face);
        canvas.drawText(value, x, baseline, paint);
    }

    /** Small caps eyebrow label ("UP NEXT"). */
    public static void eyebrow(Canvas canvas, Paint paint, String value, float x, float baseline, float size,
                               int color, Paint.Align align) {
        paint.setLetterSpacing(0.14f);
        text(canvas, paint, value, x, baseline, size, color, align, MEDIUM);
        paint.setLetterSpacing(0f);
    }

    public static float width(Paint paint, String value, float size, Typeface face) {
        paint.setTextSize(size);
        paint.setTypeface(face);
        paint.setLetterSpacing(0f);
        return paint.measureText(value);
    }

    public static float eyebrowWidth(Paint paint, String value, float size) {
        paint.setTextSize(size);
        paint.setTypeface(MEDIUM);
        paint.setLetterSpacing(0.14f);
        float width = paint.measureText(value);
        paint.setLetterSpacing(0f);
        return width;
    }

    /** Single line, ellipsized to {@code maxWidth} (leading spaces kept). Allocates: measure-time only. */
    public static String fit(Paint paint, String value, float maxWidth, float size, Typeface face) {
        String text = value == null ? "" : value.stripTrailing();
        paint.setTextSize(size);
        paint.setTypeface(face);
        paint.setLetterSpacing(0f);
        if (text.isEmpty() || paint.measureText(text) <= maxWidth) return text;
        float ellipsis = paint.measureText("…");
        int count = paint.breakText(text, true, Math.max(0f, maxWidth - ellipsis), null);
        return text.substring(0, Math.max(0, count)).stripTrailing() + "…";
    }

    /** Word-wrapped into at most {@code maxLines}; the last line is ellipsized. Measure-time only. */
    public static String[] wrap(Paint paint, String value, float maxWidth, int maxLines, float size, Typeface face) {
        List<String> lines = new ArrayList<>();
        String rest = value == null ? "" : value.trim();
        paint.setTextSize(size);
        paint.setTypeface(face);
        paint.setLetterSpacing(0f);
        while (!rest.isEmpty() && lines.size() < maxLines) {
            if (lines.size() == maxLines - 1) { lines.add(fit(paint, rest, maxWidth, size, face)); break; }
            int count = paint.breakText(rest, true, maxWidth, null);
            if (count < rest.length()) {
                int space = rest.lastIndexOf(' ', Math.max(0, count));
                if (space > 0) count = space;
            }
            lines.add(rest.substring(0, Math.max(1, count)).trim());
            rest = rest.substring(Math.min(rest.length(), Math.max(1, count))).trim();
        }
        return lines.toArray(new String[0]);
    }
}
