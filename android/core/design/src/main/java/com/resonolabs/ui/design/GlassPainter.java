package com.resonolabs.ui.design;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;

/**
 * The {@link SamTheme#glass} look without per-frame shader allocation. Gradients are built
 * at the origin, cached per (height, selected) or (height, colors), and drawn at a translated
 * origin so slide animations reuse them. One instance per view; main thread only.
 */
public final class GlassPainter {
    private static final int SLOTS = 16;
    private final float[] heights = new float[SLOTS];
    private final int[] tops = new int[SLOTS];
    private final int[] bottoms = new int[SLOTS];
    private final LinearGradient[] shaders = new LinearGradient[SLOTS];
    private int cursor;

    public void draw(Canvas canvas, Paint paint, RectF rect, float radius, boolean selected) {
        draw(canvas, paint, rect.left, rect.top, rect.right, rect.bottom, radius, selected);
    }

    /** Frosted glass panel: faint top-lit fill with a hairline edge (pale and 2 px when selected). */
    public void draw(Canvas canvas, Paint paint, float left, float top, float right, float bottom,
                     float radius, boolean selected) {
        float height = bottom - top;
        LinearGradient shader = gradient(height,
                Color.argb(selected ? 46 : 30, 200, 220, 255),
                Color.argb(selected ? 22 : 12, 200, 220, 255));
        canvas.save();
        canvas.translate(left, top);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.BLACK);
        paint.setShader(shader);
        canvas.drawRoundRect(0f, 0f, right - left, height, radius, radius, paint);
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(selected ? 2f : 1.2f);
        paint.setColor(selected ? SamTheme.withAlpha(SamTheme.ORB_PALE, 150) : SamTheme.LINE);
        float inset = selected ? 1f : 0.6f;
        canvas.drawRoundRect(inset, inset, right - left - inset, height - inset, radius, radius, paint);
        paint.setStyle(Paint.Style.FILL);
        canvas.restore();
    }

    /** Draws a cached vertical gradient (top→bottom colors) into a rounded rect. */
    public void fillVertical(Canvas canvas, Paint paint, float left, float top, float right, float bottom,
                             float radius, int topColor, int bottomColor, float gradientHeight) {
        LinearGradient shader = gradient(gradientHeight, topColor, bottomColor);
        canvas.save();
        canvas.translate(left, top);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.BLACK);
        paint.setShader(shader);
        canvas.drawRoundRect(0f, 0f, right - left, bottom - top, radius, radius, paint);
        paint.setShader(null);
        canvas.restore();
    }

    /** Cached gradient from y=0 to y=height (CLAMP beyond). */
    public LinearGradient gradient(float height, int topColor, int bottomColor) {
        float key = Math.max(1f, Math.round(height));
        for (int index = 0; index < SLOTS; index++) {
            if (shaders[index] != null && heights[index] == key && tops[index] == topColor
                    && bottoms[index] == bottomColor) {
                return shaders[index];
            }
        }
        int slot = cursor;
        cursor = (cursor + 1) % SLOTS;
        heights[slot] = key;
        tops[slot] = topColor;
        bottoms[slot] = bottomColor;
        shaders[slot] = new LinearGradient(0f, 0f, 0f, key, topColor, bottomColor, Shader.TileMode.CLAMP);
        return shaders[slot];
    }
}
