package com.resonolabs.feature.cards.board;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;

import com.resonolabs.ui.design.SamTheme;

/**
 * Static orb mark (white crown, pale band, saturated base, soft halo) with cached shaders. The
 * live FluidOrb animates every frame; a glanceable board stays still and only redraws on change.
 */
public final class OrbGlyph {
    private float cx = Float.NaN;
    private float cy;
    private float radius;
    private int color;
    private float haloScale;
    private RadialGradient halo;
    private LinearGradient body;
    private RadialGradient glint;

    /** Measure-time: re-creates the shaders only when geometry or color change. */
    public OrbGlyph set(float centerX, float centerY, float r, int orbColor, float haloRadiusScale) {
        if (centerX == cx && centerY == cy && r == radius && orbColor == color && haloScale == haloRadiusScale) return this;
        cx = centerX;
        cy = centerY;
        radius = r;
        color = orbColor;
        haloScale = haloRadiusScale;
        halo = haloRadiusScale <= 0f ? null : new RadialGradient(cx, cy + r * 0.25f, r * haloRadiusScale,
                new int[]{SamTheme.withAlpha(orbColor, 96), SamTheme.withAlpha(orbColor, 26), SamTheme.withAlpha(orbColor, 0)},
                new float[]{0.3f, 0.65f, 1f}, Shader.TileMode.CLAMP);
        body = new LinearGradient(cx, cy - r, cx, cy + r,
                new int[]{Color.WHITE, pale(orbColor), orbColor}, new float[]{0.12f, 0.48f, 0.92f}, Shader.TileMode.CLAMP);
        glint = new RadialGradient(cx - r * 0.3f, cy - r * 0.42f, r * 0.7f,
                Color.argb(120, 255, 255, 255), Color.argb(0, 255, 255, 255), Shader.TileMode.CLAMP);
        return this;
    }

    public void draw(Canvas canvas, Paint paint) {
        if (body == null) return;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.BLACK);
        if (halo != null) {
            paint.setShader(halo);
            canvas.drawCircle(cx, cy + radius * 0.25f, radius * haloScale, paint);
        }
        paint.setShader(body);
        canvas.drawCircle(cx, cy, radius, paint);
        paint.setShader(glint);
        canvas.drawCircle(cx, cy, radius, paint);
        paint.setShader(null);
    }

    static int pale(int color) {
        return Color.rgb(Math.round(Color.red(color) + (255 - Color.red(color)) * 0.6f),
                Math.round(Color.green(color) + (255 - Color.green(color)) * 0.6f),
                Math.round(Color.blue(color) + (255 - Color.blue(color)) * 0.6f));
    }
}
