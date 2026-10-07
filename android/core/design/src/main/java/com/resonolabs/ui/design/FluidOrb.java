package com.resonolabs.ui.design;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.os.Build;
import android.os.SystemClock;

/**
 * Floating fluid orb: white crown, pale middle band, saturated base, with drifting wavy
 * boundaries. Visual language after the Rare UI Fluid Orb (rareui.com, MIT + attribution).
 */
public final class FluidOrb {
    private static final String SHADER = """
            uniform float2 center;
            uniform float radius;
            uniform float time;
            uniform float energy;
            uniform half3 base;
            uniform half3 tint;

            float wave(float x, float t, float a, float b, float c) {
                return 0.16 * sin(x * a + t * b + c) + 0.09 * sin(x * (a * 1.9) - t * (b * 1.4) + c * 2.3)
                     + 0.05 * sin(x * (a * 3.7) + t * (b * 0.6) - c);
            }

            half4 main(float2 p) {
                float2 uv = (p - center) / radius;
                float d = length(uv);
                if (d > 1.0) return half4(0.0);
                float t = time;
                float k = 1.0 + energy * 1.6;
                float ang = 0.18 * sin(t * 0.21);
                float2 r = float2(uv.x * cos(ang) - uv.y * sin(ang), uv.x * sin(ang) + uv.y * cos(ang));
                float x = r.x + 0.25 * sin(t * 0.17 + r.y * 1.3);
                float y = r.y + 0.06 * sin(t * 0.33 + r.x * 2.1);
                float b1 = -0.28 + wave(x, t * 0.55, 2.2, 1.0, 0.4) * k;
                float b2 = 0.22 + wave(x, t * 0.48, 2.7, -0.8, 1.9) * k;
                float s1 = 0.10 + 0.05 * sin(t * 0.4 + x * 3.0);
                float s2 = 0.09 + 0.04 * sin(t * 0.5 - x * 2.4);
                half3 white = half3(0.99, 1.0, 1.0);
                half3 c = mix(white, tint, smoothstep(b1 - s1, b1 + s1, y));
                c = mix(c, base, smoothstep(b2 - s2, b2 + s2, y));
                float streak = smoothstep(0.05, 0.0, abs(y - b2 + 0.12 * sin(x * 4.0 + t))) * 0.12;
                c = mix(c, tint, streak);
                c *= half(1.0 - 0.10 * d * d * d);
                float a = clamp((1.0 - d) * radius * 0.9, 0.0, 1.0);
                return half4(c * a, a);
            }
            """;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RuntimeShader shader;
    private final long start = SystemClock.uptimeMillis();
    private int color = ReSonoTheme.ORB_BLUE;
    private float energy;
    private float speed = 1f;
    private float phase;
    private long lastFrame = start;

    public FluidOrb() {
        RuntimeShader created = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                created = new RuntimeShader(SHADER);
            } catch (RuntimeException ignored) {
                created = null;
            }
        }
        shader = created;
        if (shader != null) paint.setShader(shader);
        setColor(color);
    }

    /** Any hex-like ARGB color; the middle band is derived as a pale tint of it. */
    public FluidOrb setColor(int color) {
        this.color = color;
        if (shader != null) {
            shader.setFloatUniform("base", Color.red(color) / 255f, Color.green(color) / 255f,
                    Color.blue(color) / 255f);
            int pale = paleTint(color);
            shader.setFloatUniform("tint", Color.red(pale) / 255f, Color.green(pale) / 255f,
                    Color.blue(pale) / 255f);
        }
        return this;
    }

    /** 0 = calm ambient drift, 1 = lively (listening / responding). */
    public FluidOrb setEnergy(float energy) {
        this.energy = Math.max(0f, Math.min(1f, energy));
        return this;
    }

    public FluidOrb setSpeed(float speed) {
        this.speed = speed;
        return this;
    }

    public int color() {
        return color;
    }

    /** Draws the orb with a soft halo; call every frame while animating. */
    public void draw(Canvas canvas, float cx, float cy, float radius) {
        long now = SystemClock.uptimeMillis();
        phase += (now - lastFrame) / 1000f * speed;
        lastFrame = now;
        float glow = radius * (1.55f + energy * 0.25f);
        glowPaint.setShader(new RadialGradient(cx, cy + radius * 0.25f, glow,
                new int[]{ReSonoTheme.withAlpha(color, 110), ReSonoTheme.withAlpha(color, 34),
                        ReSonoTheme.withAlpha(color, 0)},
                new float[]{0.35f, 0.7f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawCircle(cx, cy + radius * 0.25f, glow, glowPaint);
        if (shader != null) {
            shader.setFloatUniform("center", cx, cy);
            shader.setFloatUniform("radius", radius);
            shader.setFloatUniform("time", phase);
            shader.setFloatUniform("energy", energy);
            canvas.drawCircle(cx, cy, radius, paint);
        } else {
            paint.setShader(new LinearGradient(cx, cy - radius, cx, cy + radius,
                    new int[]{Color.WHITE, paleTint(color), color}, new float[]{0.2f, 0.5f, 0.8f},
                    Shader.TileMode.CLAMP));
            canvas.drawCircle(cx, cy, radius, paint);
        }
    }

    /** Gentle vertical bob so the orb reads as floating. */
    public float bob(float amplitude) {
        return (float) Math.sin(phase * 0.9f) * amplitude;
    }

    private static int paleTint(int color) {
        return Color.rgb(
                Math.round(Color.red(color) + (255 - Color.red(color)) * 0.6f),
                Math.round(Color.green(color) + (255 - Color.green(color)) * 0.6f),
                Math.round(Color.blue(color) + (255 - Color.blue(color)) * 0.6f));
    }
}
