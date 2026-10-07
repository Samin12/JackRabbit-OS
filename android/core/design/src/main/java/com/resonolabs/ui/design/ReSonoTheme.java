package com.resonolabs.ui.design;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;

/** Orb design tokens: near-black space, white ink, and the Fluid Orb blue family. */
public final class ReSonoTheme {
    public static final int BACKGROUND = Color.rgb(9, 11, 16);
    public static final int BACKGROUND_TOP = Color.rgb(13, 17, 26);
    public static final int INK = Color.rgb(245, 248, 255);
    public static final int MUTED = Color.rgb(140, 152, 172);
    public static final int ORB_BLUE = Color.rgb(26, 115, 242);
    public static final int ORB_PALE = Color.rgb(160, 199, 255);
    public static final int VIOLET = Color.rgb(124, 108, 255);
    public static final int CYAN = Color.rgb(92, 162, 255);
    public static final int MINT = Color.rgb(120, 178, 255);
    public static final int PINK = Color.rgb(255, 92, 168);
    public static final int RED = Color.rgb(255, 99, 99);
    public static final int AMBER = Color.rgb(255, 196, 92);
    public static final int LINE = Color.argb(34, 190, 210, 255);
    public static final int PANEL = Color.argb(255, 18, 22, 32);
    public static final int PANEL_RAISED = Color.argb(255, 24, 29, 42);
    public static final int ORB_MID = Color.argb(105, 92, 162, 255);
    public static final int TRANSPARENT = Color.TRANSPARENT;

    private static final Typeface REGULAR = Typeface.create("sans-serif", Typeface.NORMAL);
    private static final Typeface MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL);

    private ReSonoTheme() {}

    public static void text(Canvas canvas, Paint paint, String value, float x, float baseline,
                            float size, int color, Paint.Align align, boolean bold) {
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(color);
        paint.setTextAlign(align);
        paint.setTextSize(size);
        paint.setTypeface(bold ? MEDIUM : REGULAR);
        canvas.drawText(value == null ? "" : value, x, baseline, paint);
    }

    /** Full-bleed background with a soft blue glow behind where an orb floats. */
    public static void background(Canvas canvas, Paint paint, float width, float height,
                                  float glowX, float glowY, float glowRadius, int glowColor) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.BLACK);
        paint.setShader(new LinearGradient(0f, 0f, 0f, height, BACKGROUND_TOP, BACKGROUND,
                Shader.TileMode.CLAMP));
        canvas.drawRect(0f, 0f, width, height, paint);
        if (glowRadius > 0f) {
            paint.setShader(new RadialGradient(glowX, glowY, glowRadius,
                    withAlpha(glowColor, 70), withAlpha(glowColor, 0), Shader.TileMode.CLAMP));
            canvas.drawCircle(glowX, glowY, glowRadius, paint);
        }
        paint.setShader(null);
    }

    /** Frosted glass panel: faint top-lit fill with a hairline edge. */
    public static void glass(Canvas canvas, Paint paint, RectF rect, float radius, boolean selected) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.BLACK);
        paint.setShader(new LinearGradient(0f, rect.top, 0f, rect.bottom,
                Color.argb(selected ? 46 : 30, 200, 220, 255),
                Color.argb(selected ? 22 : 12, 200, 220, 255), Shader.TileMode.CLAMP));
        canvas.drawRoundRect(rect, radius, radius, paint);
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(selected ? 2f : 1.2f);
        paint.setColor(selected ? withAlpha(ORB_PALE, 150) : LINE);
        canvas.drawRoundRect(rect, radius, radius, paint);
        paint.setStyle(Paint.Style.FILL);
    }

    public static int withAlpha(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }
}
