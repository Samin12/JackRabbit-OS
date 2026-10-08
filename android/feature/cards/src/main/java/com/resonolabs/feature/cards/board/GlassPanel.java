package com.resonolabs.feature.cards.board;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;

import com.resonolabs.ui.design.SamTheme;

/**
 * The SamTheme frosted glass look with its gradients cached per size, drawn at the local origin
 * (callers translate), so onDraw never allocates a shader.
 */
public final class GlassPanel {
    private final RectF rect = new RectF();
    private float radius;
    private LinearGradient normal;
    private LinearGradient raised;
    private LinearGradient sheen;

    /** Measure-time: re-creates the gradients only when the size changes. */
    public void size(float width, float height, float cornerRadius) {
        if (normal != null && rect.width() == width && rect.height() == height && radius == cornerRadius) return;
        rect.set(0f, 0f, width, height);
        radius = cornerRadius;
        normal = new LinearGradient(0f, 0f, 0f, height, Color.argb(32, 200, 220, 255),
                Color.argb(12, 200, 220, 255), Shader.TileMode.CLAMP);
        raised = new LinearGradient(0f, 0f, 0f, height, Color.argb(50, 200, 220, 255),
                Color.argb(22, 200, 220, 255), Shader.TileMode.CLAMP);
        sheen = new LinearGradient(0f, 0f, 0f, Math.min(height, 46f), Color.argb(26, 255, 255, 255),
                Color.argb(0, 255, 255, 255), Shader.TileMode.CLAMP);
    }

    public void draw(Canvas canvas, Paint paint, boolean selected) {
        if (normal == null) return;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(SamTheme.withAlpha(SamTheme.PANEL, 150));
        paint.setShader(null);
        canvas.drawRoundRect(rect, radius, radius, paint);
        paint.setColor(Color.BLACK);
        paint.setShader(selected ? raised : normal);
        canvas.drawRoundRect(rect, radius, radius, paint);
        paint.setShader(sheen);
        canvas.drawRoundRect(rect, radius, radius, paint);
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(selected ? 2f : 1.2f);
        paint.setColor(selected ? SamTheme.withAlpha(SamTheme.ORB_PALE, 150) : SamTheme.LINE);
        canvas.drawRoundRect(rect, radius, radius, paint);
        paint.setStyle(Paint.Style.FILL);
    }

    public float width() { return rect.width(); }
    public float height() { return rect.height(); }
}
