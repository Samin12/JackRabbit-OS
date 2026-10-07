package com.resonolabs.feature.cards.board;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.SparseArray;

import com.resonolabs.ui.design.SamTheme;

/**
 * The T3 tab's status orb, for the board: a soft glow, a solid core with a white crown, and a
 * spinning arc while working. Glows are cached per color in unit space and placed with a local
 * matrix, so drawing never allocates.
 */
final class StatusOrb {
    /** One revolution of the "working" arc every ~1.15 s, like the T3 tab. */
    private static final long SPIN_PERIOD_MS = 1152L;

    private final SparseArray<RadialGradient> glows = new SparseArray<>();
    private final Matrix matrix = new Matrix();
    private final RectF arc = new RectF();

    void draw(Canvas canvas, Paint paint, float cx, float cy, float r, int color, boolean spinning, long now) {
        RadialGradient glow = glows.get(color);
        if (glow == null) {
            glow = new RadialGradient(0f, 0f, 1f, new int[]{SamTheme.withAlpha(color, 120),
                    SamTheme.withAlpha(color, 40), SamTheme.withAlpha(color, 0)},
                    new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP);
            glows.put(color, glow);
        }
        float pulse = spinning ? 1f + 0.12f * (float) Math.sin(now / 260.0) : 1f;
        float glowRadius = r * 2.3f * pulse;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.BLACK);
        matrix.setScale(glowRadius, glowRadius);
        matrix.postTranslate(cx, cy);
        glow.setLocalMatrix(matrix);
        paint.setShader(glow);
        canvas.drawCircle(cx, cy, glowRadius, paint);
        paint.setShader(null);
        paint.setColor(color);
        canvas.drawCircle(cx, cy, r, paint);
        paint.setColor(Color.argb(150, 255, 255, 255));
        canvas.drawCircle(cx - r * 0.25f, cy - r * 0.3f, r * 0.42f, paint);
        if (spinning) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2.2f);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setColor(SamTheme.withAlpha(color, 220));
            float ring = r + 5f;
            arc.set(cx - ring, cy - ring, cx + ring, cy + ring);
            // Modulo on the long first: wall-clock millis do not fit a float's precision.
            canvas.drawArc(arc, (now % SPIN_PERIOD_MS) * 360f / SPIN_PERIOD_MS, 110f, false, paint);
            paint.setStrokeCap(Paint.Cap.BUTT);
            paint.setStyle(Paint.Style.FILL);
        }
    }
}
