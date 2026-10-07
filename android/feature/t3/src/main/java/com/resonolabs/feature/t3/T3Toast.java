package com.resonolabs.feature.t3;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;

import com.resonolabs.ui.design.SamTheme;

/** Short-lived confirmation pill ("Approved", "Sent"), shared by the T3 screens. */
final class T3Toast {
    private static final long DURATION_MS = 2_400L;
    private final RectF rect = new RectF();
    private String message = "";
    private String shown;
    private float width;
    private int color = SamTheme.ORB_PALE;
    private long shownAt;

    void show(String message, int color) {
        this.message = message == null ? "" : message;
        this.shown = null;
        this.color = color;
        this.shownAt = SystemClock.uptimeMillis();
    }

    boolean visible() {
        return !message.isEmpty() && SystemClock.uptimeMillis() - shownAt < DURATION_MS;
    }

    /** Draws centered with its baseline area around {@code centerY}; returns true while animating. */
    boolean draw(Canvas canvas, T3Surface surface, float centerY) {
        if (!visible()) return false;
        long age = SystemClock.uptimeMillis() - shownAt;
        float fadeIn = Math.min(1f, age / 160f);
        float fadeOut = Math.min(1f, (DURATION_MS - age) / 300f);
        float alpha = Math.max(0f, Math.min(fadeIn, fadeOut));
        if (shown == null) {
            shown = surface.ellipsize(message, 360f, 16f, T3Surface.MEDIUM);
            width = surface.measure(shown, 16f, T3Surface.MEDIUM) + 56f;
        }
        float lift = (1f - fadeIn) * 10f;
        rect.set(240f - width / 2f, centerY - 22f + lift, 240f + width / 2f, centerY + 22f + lift);
        surface.solid(canvas, rect, 22f, SamTheme.withAlpha(SamTheme.PANEL_RAISED, (int) (245 * alpha)));
        surface.stroke(canvas, rect, 22f, 1.4f, SamTheme.withAlpha(color, (int) (170 * alpha)));
        surface.paint.setColor(SamTheme.withAlpha(color, (int) (255 * alpha)));
        canvas.drawCircle(rect.left + 22f, centerY + lift, 4.5f, surface.paint);
        surface.text(canvas, shown, rect.left + 36f, centerY + 6f + lift, 16f,
                SamTheme.withAlpha(SamTheme.INK, (int) (255 * alpha)), Paint.Align.LEFT, T3Surface.MEDIUM);
        return true;
    }
}
