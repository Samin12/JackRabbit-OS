package com.resonolabs.feature.t3;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;

import com.resonolabs.ui.design.SamTheme;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Allocation-free drawing primitives for the T3 tab in the orb/glass language. Gradients are
 * built once in unit space and positioned with a local matrix, so onDraw never creates shaders.
 */
final class T3Surface {
    static final Typeface REGULAR = Typeface.create("sans-serif", Typeface.NORMAL);
    static final Typeface MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    static final Typeface MONO = Typeface.MONOSPACE;

    final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix matrix = new Matrix();
    private final Path path = new Path();
    private final RectF scratch = new RectF();
    private final LinearGradient glassIdle = vertical(Color.argb(30, 200, 220, 255), Color.argb(12, 200, 220, 255));
    private final LinearGradient glassSelected = vertical(Color.argb(50, 200, 220, 255), Color.argb(24, 200, 220, 255));
    private final LinearGradient primary = vertical(SamTheme.withAlpha(SamTheme.ORB_BLUE, 245),
            SamTheme.withAlpha(SamTheme.ORB_BLUE, 200));
    private final LinearGradient fadeTop = vertical(SamTheme.BACKGROUND_TOP, SamTheme.withAlpha(SamTheme.BACKGROUND_TOP, 0));
    private final LinearGradient fadeBottom = vertical(SamTheme.withAlpha(SamTheme.BACKGROUND, 0), SamTheme.BACKGROUND);
    private final Map<Integer, RadialGradient> glows = new HashMap<>();
    private LinearGradient background;
    private RadialGradient backgroundGlow;
    private int backgroundGlowColor;

    T3Surface() {
        text.setStyle(Paint.Style.FILL);
    }

    // ---- surfaces -------------------------------------------------------------------------

    /** Page background: the SamTheme vertical wash plus one soft glow (cached). */
    void background(Canvas canvas, float width, float height, float glowX, float glowY, float glowRadius,
                    int glowColor) {
        if (background == null) background = vertical(SamTheme.BACKGROUND_TOP, SamTheme.BACKGROUND);
        if (backgroundGlow == null || backgroundGlowColor != glowColor) {
            backgroundGlow = new RadialGradient(0f, 0f, 1f, SamTheme.withAlpha(glowColor, 62),
                    SamTheme.withAlpha(glowColor, 0), Shader.TileMode.CLAMP);
            backgroundGlowColor = glowColor;
        }
        fillVertical(canvas, background, 0f, 0f, width, height, 0f);
        paint.setColor(Color.BLACK);
        paint.setStyle(Paint.Style.FILL);
        matrix.setScale(glowRadius, glowRadius);
        matrix.postTranslate(glowX, glowY);
        backgroundGlow.setLocalMatrix(matrix);
        paint.setShader(backgroundGlow);
        canvas.drawCircle(glowX, glowY, glowRadius, paint);
        paint.setShader(null);
    }

    void glass(Canvas canvas, RectF rect, float radius, boolean selected) {
        fillVertical(canvas, selected ? glassSelected : glassIdle, rect.left, rect.top, rect.right, rect.bottom, radius);
        stroke(canvas, rect, radius, selected ? 2f : 1.2f,
                selected ? SamTheme.withAlpha(SamTheme.ORB_PALE, 150) : SamTheme.LINE);
    }

    /** Glass with a colored wash and edge, for cards that need attention. */
    void tinted(Canvas canvas, RectF rect, float radius, int color, int fillAlpha, int edgeAlpha) {
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(SamTheme.PANEL);
        canvas.drawRoundRect(rect, radius, radius, paint);
        paint.setColor(SamTheme.withAlpha(color, fillAlpha));
        canvas.drawRoundRect(rect, radius, radius, paint);
        stroke(canvas, rect, radius, 1.4f, SamTheme.withAlpha(color, edgeAlpha));
    }

    /** Solid orb-blue primary button. */
    void primary(Canvas canvas, RectF rect, float radius) {
        fillVertical(canvas, primary, rect.left, rect.top, rect.right, rect.bottom, radius);
    }

    void solid(Canvas canvas, RectF rect, float radius, int color) {
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(color);
        canvas.drawRoundRect(rect, radius, radius, paint);
    }

    void stroke(Canvas canvas, RectF rect, float radius, float width, int color) {
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(width);
        paint.setColor(color);
        canvas.drawRoundRect(rect, radius, radius, paint);
        paint.setStyle(Paint.Style.FILL);
    }

    /** Focus ring drawn just outside a control (wheel focus). */
    void focus(Canvas canvas, RectF rect, float radius) {
        scratch.set(rect.left - 3f, rect.top - 3f, rect.right + 3f, rect.bottom + 3f);
        stroke(canvas, scratch, radius + 3f, 2.4f, SamTheme.withAlpha(SamTheme.ORB_PALE, 230));
    }

    /** Soft fades where scrolled content is clipped (only on edges that actually hide content). */
    void fadeEdges(Canvas canvas, float left, float right, float top, float bottom, float size,
                   boolean topClipped, boolean bottomClipped) {
        if (topClipped) fillVertical(canvas, fadeTop, left, top, right, top + size, 0f);
        if (bottomClipped) fillVertical(canvas, fadeBottom, left, bottom - size, right, bottom, 0f);
    }

    /** Status orb: soft glow, solid core with a white crown; working adds a spinning arc. */
    void statusOrb(Canvas canvas, float cx, float cy, float r, int color, boolean spinning, long now) {
        RadialGradient glow = glows.get(color);
        if (glow == null) {
            glow = new RadialGradient(0f, 0f, 1f, new int[]{SamTheme.withAlpha(color, 120),
                    SamTheme.withAlpha(color, 40), SamTheme.withAlpha(color, 0)},
                    new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP);
            glows.put(color, glow);
        }
        float pulse = spinning ? 1f + 0.12f * (float) Math.sin(now / 260.0) : 1f;
        float glowRadius = r * 2.3f * pulse;
        paint.setColor(Color.BLACK);
        paint.setStyle(Paint.Style.FILL);
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
            scratch.set(cx - ring, cy - ring, cx + ring, cy + ring);
            canvas.drawArc(scratch, (now / 3.2f) % 360f, 110f, false, paint);
            paint.setStrokeCap(Paint.Cap.BUTT);
            paint.setStyle(Paint.Style.FILL);
        }
    }

    // ---- text -----------------------------------------------------------------------------

    void text(Canvas canvas, String value, float x, float baseline, float size, int color, Paint.Align align,
              Typeface face) {
        text.setShader(null);
        text.setColor(color);
        text.setTextAlign(align);
        text.setTextSize(size);
        text.setTypeface(face);
        text.setLetterSpacing(0f);
        canvas.drawText(value == null ? "" : value, x, baseline, text);
    }

    /** Small caps section label with letter spacing. */
    void label(Canvas canvas, String value, float x, float baseline, int color, Paint.Align align) {
        text.setShader(null);
        text.setColor(color);
        text.setTextAlign(align);
        text.setTextSize(12.5f);
        text.setTypeface(MEDIUM);
        text.setLetterSpacing(0.12f);
        canvas.drawText(value, x, baseline, text);
        text.setLetterSpacing(0f);
    }

    float measure(String value, float size, Typeface face) {
        text.setTextSize(size);
        text.setTypeface(face);
        text.setLetterSpacing(0f);
        return text.measureText(value == null ? "" : value);
    }

    /** Single-line ellipsis to a width (layout time only: allocates). */
    String ellipsize(String value, float width, float size, Typeface face) {
        String source = value == null ? "" : value.replace('\n', ' ').trim();
        text.setTextSize(size);
        text.setTypeface(face);
        text.setLetterSpacing(0f);
        if (text.measureText(source) <= width) return source;
        float budget = width - text.measureText("…");
        int count = text.breakText(source, true, Math.max(0f, budget), null);
        return source.substring(0, Math.max(0, count)).trim() + "…";
    }

    /**
     * Word wrap (layout time only). {@code anywhere} breaks at any character, for code.
     * Leading indentation of each source line is preserved for code.
     */
    List<String> wrap(String value, float width, float size, Typeface face, boolean anywhere) {
        List<String> lines = new ArrayList<>();
        text.setTextSize(size);
        text.setTypeface(face);
        text.setLetterSpacing(0f);
        String source = value == null ? "" : value;
        for (String paragraph : source.split("\n", -1)) {
            String rest = anywhere ? paragraph : paragraph.trim();
            if (rest.isEmpty()) {
                lines.add("");
                continue;
            }
            while (!rest.isEmpty()) {
                int count = text.breakText(rest, true, width, null);
                if (count <= 0) count = 1;
                if (count < rest.length() && !anywhere) {
                    int space = rest.lastIndexOf(' ', count);
                    if (space > 0) count = space;
                }
                lines.add(anywhere ? rest.substring(0, count) : rest.substring(0, count).trim());
                rest = anywhere ? rest.substring(count) : rest.substring(Math.min(rest.length(), count)).trim();
            }
        }
        return lines;
    }

    // ---- glyphs ---------------------------------------------------------------------------

    private void strokeStyle(int color, float width) {
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(width);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setColor(color);
    }

    private void fillStyle() {
        paint.setStrokeCap(Paint.Cap.BUTT);
        paint.setStrokeJoin(Paint.Join.MITER);
        paint.setStyle(Paint.Style.FILL);
    }

    void chevronLeft(Canvas canvas, float cx, float cy, int color) {
        strokeStyle(color, 2.8f);
        canvas.drawLine(cx + 4f, cy - 9f, cx - 5f, cy, paint);
        canvas.drawLine(cx - 5f, cy, cx + 4f, cy + 9f, paint);
        fillStyle();
    }

    void plus(Canvas canvas, float cx, float cy, float half, int color) {
        strokeStyle(color, 2.8f);
        canvas.drawLine(cx - half, cy, cx + half, cy, paint);
        canvas.drawLine(cx, cy - half, cx, cy + half, paint);
        fillStyle();
    }

    void check(Canvas canvas, float cx, float cy, int color) {
        strokeStyle(color, 2.6f);
        canvas.drawLine(cx - 6f, cy, cx - 2f, cy + 4.5f, paint);
        canvas.drawLine(cx - 2f, cy + 4.5f, cx + 6.5f, cy - 5f, paint);
        fillStyle();
    }

    void mic(Canvas canvas, float cx, float cy, int color) {
        strokeStyle(color, 2.4f);
        scratch.set(cx - 5f, cy - 12f, cx + 5f, cy + 3f);
        canvas.drawRoundRect(scratch, 5f, 5f, paint);
        scratch.set(cx - 9.5f, cy - 6f, cx + 9.5f, cy + 8.5f);
        canvas.drawArc(scratch, 0f, 180f, false, paint);
        canvas.drawLine(cx, cy + 8.5f, cx, cy + 12.5f, paint);
        fillStyle();
    }

    void pencil(Canvas canvas, float cx, float cy, int color) {
        strokeStyle(color, 2.4f);
        path.reset();
        path.moveTo(cx - 8f, cy + 8f);
        path.lineTo(cx - 7f, cy + 3.5f);
        path.lineTo(cx + 5f, cy - 8.5f);
        path.lineTo(cx + 8.5f, cy - 5f);
        path.lineTo(cx - 3.5f, cy + 7f);
        path.close();
        canvas.drawPath(path, paint);
        canvas.drawLine(cx + 2f, cy - 5.5f, cx + 5.5f, cy - 2f, paint);
        fillStyle();
    }

    void stopSquare(Canvas canvas, float cx, float cy, int color) {
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(color);
        scratch.set(cx - 7f, cy - 7f, cx + 7f, cy + 7f);
        canvas.drawRoundRect(scratch, 2.5f, 2.5f, paint);
    }

    /** Three bouncing dots ("typing"). */
    void typing(Canvas canvas, float x, float cy, int color, long now) {
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        for (int dot = 0; dot < 3; dot++) {
            double phase = now / 180.0 - dot * 0.9;
            float lift = (float) Math.max(0.0, Math.sin(phase)) * 4f;
            int alpha = 120 + (int) (Math.max(0.0, Math.sin(phase)) * 120);
            paint.setColor(SamTheme.withAlpha(color, alpha));
            canvas.drawCircle(x + dot * 13f, cy - lift, 3.6f, paint);
        }
    }

    // ---- internals ------------------------------------------------------------------------

    private static LinearGradient vertical(int top, int bottom) {
        return new LinearGradient(0f, 0f, 0f, 1f, top, bottom, Shader.TileMode.CLAMP);
    }

    private void fillVertical(Canvas canvas, LinearGradient shader, float left, float top, float right,
                              float bottom, float radius) {
        float height = Math.max(1f, bottom - top);
        matrix.setScale(1f, height);
        matrix.postTranslate(0f, top);
        shader.setLocalMatrix(matrix);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.BLACK);
        paint.setShader(shader);
        if (radius > 0f) {
            scratch.set(left, top, right, bottom);
            canvas.drawRoundRect(scratch, radius, radius, paint);
        } else {
            canvas.drawRect(left, top, right, bottom, paint);
        }
        paint.setShader(null);
    }
}
