package com.resonolabs.ui.design;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;

/**
 * The voxel "Pixel head" orb style: a monochrome voxel bust with headphones, held in a 3/4 view
 * that sways a little (yaw -44 to -20 degrees, see {@link PixelHeadMotion}) and floats. Drawn
 * from pre-rendered poses (see {@link PixelHeadAtlas}); while listening or speaking its eyes and
 * earcups light up and a faint white ring glows behind it, while speaking ({@link #setSpeaking})
 * its mouth moves, and when idle it blinks every 4-7 s. One instance per orb (it keeps the
 * animation clock); main thread only. Drawing allocates nothing.
 */
public final class PixelHead {
    /** The resting 3/4 view (yaw -32 degrees), the pose for still heads. */
    public static final int CENTER_POSE = PixelHeadMotion.CENTER_POSE;

    private static RadialGradient ringShader;

    private final Rect src = new Rect();
    private final RectF dst = new RectF();
    private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final PixelHeadMotion.Mouth mouth = new PixelHeadMotion.Mouth();
    private boolean speaking;
    private float bobPhase;
    private float loop;
    private float lit;
    private long last;
    private long blinkAt;
    private int blinks;

    public PixelHead(Context context) {
        PixelHeadSprites.attach(context);
        ringPaint.setStyle(Paint.Style.FILL);
        if (ringShader == null) {
            // Unit-radius ring at the origin; positioned and sized with the canvas matrix.
            ringShader = new RadialGradient(0f, 0f, 1f,
                    new int[]{Color.argb(14, 255, 255, 255), Color.argb(6, 255, 255, 255),
                            Color.argb(44, 255, 255, 255), Color.argb(10, 255, 255, 255),
                            Color.argb(0, 255, 255, 255)},
                    new float[]{0f, 0.58f, 0.8f, 0.92f, 1f}, Shader.TileMode.CLAMP);
        }
        ringPaint.setShader(ringShader);
    }

    /**
     * True while the assistant's voice is playing: the mouth then opens and closes at a
     * syllable cadence (it stays shut otherwise).
     */
    public PixelHead setSpeaking(boolean speaking) {
        this.speaking = speaking;
        return this;
    }

    /**
     * The head's float offset for a caller's bob {@code amplitude} (what FluidOrb.bob would
     * add): about 0.7 x amplitude, one slow eased bob every 3.2 s, a little larger and quicker
     * while lit. Advanced by {@link #draw}, so it rests at 0 until the head animates.
     */
    public float bob(float amplitude) {
        return PixelHeadMotion.bobOffset(bobPhase, amplitude, lit);
    }

    /**
     * Draws the animated head centred on ({@code cx}, {@code cy}), about 2.2 x {@code radius}
     * tall. {@code energy} 0..1 lights the eyes and earcups (from about 0.4) and {@code speed}
     * sets the sway rate, as for FluidOrb. The caller adds the float ({@link #bob}). Returns
     * false (nothing drawn) when the art is unavailable.
     */
    public boolean draw(Canvas canvas, float cx, float cy, float radius, float energy, float speed, long nowMs) {
        long elapsed = last == 0L ? 0L : nowMs - last;
        last = nowMs;
        loop = PixelHeadMotion.advance(loop, elapsed, PixelHeadMotion.framesPerSecond(speed));
        lit = PixelHeadMotion.ease(lit, PixelHeadMotion.litFor(energy), elapsed);
        bobPhase = PixelHeadMotion.advanceBob(bobPhase, elapsed, lit);
        boolean mouthOpen = mouth.update(speaking, nowMs);
        boolean blink = false;
        if (blinkAt == 0L) blinkAt = nowMs + PixelHeadMotion.blinkGapMs(blinks);
        if (nowMs >= blinkAt + PixelHeadMotion.BLINK_MS) {
            blinkAt = nowMs + PixelHeadMotion.blinkGapMs(++blinks);
        } else if (lit < 0.05f) {
            blink = PixelHeadMotion.blinking(nowMs, blinkAt);
        }
        float headPx = radius * PixelHeadMotion.HEAD_PER_RADIUS;
        int pose = PixelHeadMotion.poseForFrame((int) loop);
        int bucket = PixelHeadMotion.bucketFor(headPx, PixelHeadAtlas.HEAD_H);
        PixelHeadSprites.Art art = PixelHeadSprites.art(bucket);
        if (art == null) return false;
        if (lit > 0.01f) drawRing(canvas, cx, cy, headPx, energy, nowMs);
        drawPose(canvas, art, bucket, pose, cx, cy, headPx, lit, blink, mouthOpen);
        return true;
    }

    /**
     * A still head, eyes open, for glanceable surfaces that do not animate (Cards board).
     * {@code pose} 0..24 runs from the most turned view (yaw -44 degrees, facing screen-left)
     * through the 3/4 centre {@link #CENTER_POSE} (-32) to the most frontal (-20).
     */
    public boolean drawStill(Canvas canvas, float cx, float cy, float radius, int pose) {
        float headPx = radius * PixelHeadMotion.HEAD_PER_RADIUS;
        int bucket = PixelHeadMotion.bucketFor(headPx, PixelHeadAtlas.HEAD_H);
        PixelHeadSprites.Art art = PixelHeadSprites.art(bucket);
        if (art == null) return false;
        drawPose(canvas, art, bucket, Math.max(0, Math.min(PixelHeadMotion.POSES - 1, pose)),
                cx, cy, headPx, 0f, false, false);
        return true;
    }

    private void drawPose(Canvas canvas, PixelHeadSprites.Art art, int b, int pose, float cx, float cy,
                          float headPx, float eyesLit, boolean blink, boolean mouthOpen) {
        float scale = headPx / PixelHeadAtlas.HEAD_H[b];
        float left = cx - PixelHeadAtlas.ANCHOR_X[b] * scale;
        float top = cy - PixelHeadAtlas.ANCHOR_Y[b] * scale;
        int column = pose % PixelHeadAtlas.COLUMNS;
        int row = pose / PixelHeadAtlas.COLUMNS;
        int cellW = PixelHeadAtlas.CELL_W[b];
        int cellH = PixelHeadAtlas.CELL_H[b];
        src.set(column * cellW, row * cellH, (column + 1) * cellW, (row + 1) * cellH);
        dst.set(left, top, left + cellW * scale, top + cellH * scale);
        bitmapPaint.setAlpha(255);
        canvas.drawBitmap(art.idle, src, dst, bitmapPaint);
        if (eyesLit > 0.01f) {
            drawPatch(canvas, art.eyes, column, row, 0, PixelHeadAtlas.ACTIVE_SLOT_W[b],
                    PixelHeadAtlas.ACTIVE_SLOT_H[b], PixelHeadAtlas.ACTIVE_W[b], PixelHeadAtlas.ACTIVE_H[b],
                    PixelHeadAtlas.ACTIVE_X[b][pose], PixelHeadAtlas.ACTIVE_Y[b][pose], left, top, scale, eyesLit);
        } else if (blink) {
            drawPatch(canvas, art.eyes, column, row, PixelHeadAtlas.BLINK_TOP[b], PixelHeadAtlas.BLINK_SLOT_W[b],
                    PixelHeadAtlas.BLINK_SLOT_H[b], PixelHeadAtlas.BLINK_W[b], PixelHeadAtlas.BLINK_H[b],
                    PixelHeadAtlas.BLINK_X[b][pose], PixelHeadAtlas.BLINK_Y[b][pose], left, top, scale, 1f);
        }
        if (mouthOpen) {
            // The mouth patch never overlaps the eye / earcup patches, so it goes over either.
            drawPatch(canvas, art.eyes, column, row, PixelHeadAtlas.TALK_TOP[b], PixelHeadAtlas.TALK_SLOT_W[b],
                    PixelHeadAtlas.TALK_SLOT_H[b], PixelHeadAtlas.TALK_W[b], PixelHeadAtlas.TALK_H[b],
                    PixelHeadAtlas.TALK_X[b][pose], PixelHeadAtlas.TALK_Y[b][pose], left, top, scale, 1f);
        }
    }

    private void drawPatch(Canvas canvas, Bitmap patches, int column, int row, int gridTop, int slotW, int slotH,
                           int w, int h, int x, int y, float left, float top, float scale, float alpha) {
        int sx = column * slotW + PixelHeadAtlas.GUTTER;
        int sy = gridTop + row * slotH + PixelHeadAtlas.GUTTER;
        src.set(sx, sy, sx + w, sy + h);
        float px = left + x * scale;
        float py = top + y * scale;
        dst.set(px, py, px + w * scale, py + h * scale);
        bitmapPaint.setAlpha(Math.round(255f * Math.min(1f, alpha)));
        canvas.drawBitmap(patches, src, dst, bitmapPaint);
        bitmapPaint.setAlpha(255);
    }

    /** Faint white ring behind the head while it listens or speaks; breathes a little faster when speaking. */
    private void drawRing(Canvas canvas, float cx, float cy, float headPx, float energy, long nowMs) {
        double rate = 1.6 + 2.4 * Math.max(0f, Math.min(1f, energy));
        float pulse = 0.82f + 0.18f * (float) Math.sin(nowMs / 1000.0 * rate);
        float strength = lit * (0.6f + 0.4f * Math.min(1f, energy)) * pulse;
        ringPaint.setAlpha(Math.round(255f * Math.max(0f, Math.min(1f, strength))));
        float ring = headPx * 0.66f;
        canvas.save();
        canvas.translate(cx, cy + headPx * 0.05f);
        canvas.scale(ring, ring);
        canvas.drawCircle(0f, 0f, 1f, ringPaint);
        canvas.restore();
    }
}
