package com.resonolabs.ui.design;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * Decoded Pixel head atlases, shared by every head in the process. Each size bucket is decoded
 * once (inScaled=false, so no density scaling) on first use or by a background preload as soon
 * as the Pixel head style is active, and dropped again when the style goes back to the orb, unless
 * a preview pinned to the head (Settings > Theme) still {@link #hold holds} it.
 */
final class PixelHeadSprites {
    private static final String TAG = "PixelHead";

    /**
     * One size bucket: the 25 idle poses and the patches drawn over them (eyes + earcups lit,
     * eyes closed, mouth open; the field keeps its first name).
     */
    static final class Art {
        final Bitmap idle;
        final Bitmap eyes;

        Art(Bitmap idle, Bitmap eyes) {
            this.idle = idle;
            this.eyes = eyes;
        }
    }

    private static final int[] IDLE_RES = {
            R.drawable.pixel_head_72, R.drawable.pixel_head_112,
            R.drawable.pixel_head_176, R.drawable.pixel_head_256};
    private static final int[] EYES_RES = {
            R.drawable.pixel_head_72_eyes, R.drawable.pixel_head_112_eyes,
            R.drawable.pixel_head_176_eyes, R.drawable.pixel_head_256_eyes};
    /** Preload order: the Voice page's big head first, then the docked size. */
    private static final int[] PRELOAD_ORDER = {3, 0, 1, 2};

    private static final AtomicReferenceArray<Art> ART = new AtomicReferenceArray<>(IDLE_RES.length);
    private static final Object[] LOCKS = {new Object(), new Object(), new Object(), new Object()};
    private static final boolean[] FAILED = new boolean[IDLE_RES.length];
    private static final AtomicBoolean PRELOADING = new AtomicBoolean();
    /** Pinned Pixel head previews on screen; while any holds, decoded art is kept whatever the style. */
    private static final AtomicInteger HOLDS = new AtomicInteger();
    private static volatile Context app;

    private PixelHeadSprites() {}

    static int buckets() {
        return IDLE_RES.length;
    }

    /** Remembers the app context, loads the style setting and follows its changes. */
    static void attach(Context context) {
        if (app != null) return;
        synchronized (PixelHeadSprites.class) {
            if (app != null) return;
            Context application = context.getApplicationContext();
            app = application != null ? application : context;
            OrbStyleSetting.init(app);
            OrbStyleSetting.addListener(style -> {
                if (style == OrbStyle.PIXEL_HEAD) preload();
                else if (HOLDS.get() == 0) release();
            });
        }
        if (OrbStyleSetting.current() == OrbStyle.PIXEL_HEAD) preload();
    }

    /** The decoded bucket, decoding it now if the preload has not got there yet; null on failure. */
    static Art art(int bucket) {
        Art art = ART.get(bucket);
        if (art != null || app == null) return art;
        synchronized (LOCKS[bucket]) {
            art = ART.get(bucket);
            if (art != null || FAILED[bucket]) return art;
            art = decode(bucket);
            if (art == null) FAILED[bucket] = true;
            else if (keepsArt(OrbStyleSetting.current(), HOLDS.get())) ART.set(bucket, art);
        }
        return art;
    }

    private static Art decode(int bucket) {
        long start = android.os.SystemClock.uptimeMillis();
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inScaled = false;
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            Bitmap idle = BitmapFactory.decodeResource(app.getResources(), IDLE_RES[bucket], options);
            Bitmap eyes = BitmapFactory.decodeResource(app.getResources(), EYES_RES[bucket], options);
            if (idle == null || eyes == null) {
                Log.w(TAG, "pixel head art missing for bucket " + bucket);
                return null;
            }
            idle.prepareToDraw();
            eyes.prepareToDraw();
            Log.i(TAG, "decoded bucket " + bucket + " " + idle.getWidth() + "x" + idle.getHeight()
                    + " in " + (android.os.SystemClock.uptimeMillis() - start) + " ms");
            return new Art(idle, eyes);
        } catch (RuntimeException | OutOfMemoryError error) {
            Log.w(TAG, "pixel head art failed for bucket " + bucket, error);
            return null;
        }
    }

    /**
     * Decoded art is cached while the head is the user's style or a pinned preview holds it;
     * otherwise a decode serves one frame and is dropped (a preview drawing the head every frame
     * without a hold would decode every frame).
     */
    static boolean keepsArt(OrbStyle style, int holds) {
        return style == OrbStyle.PIXEL_HEAD || holds > 0;
    }

    /** A preview pinned to the head is showing: keep its art even while the user's style is the orb. */
    static void hold() {
        HOLDS.incrementAndGet();
    }

    /** That preview left the screen; the last one out drops the art if the style is the orb. */
    static void unhold() {
        int left = HOLDS.decrementAndGet();
        if (left < 0) HOLDS.compareAndSet(left, 0);
        if (left <= 0 && OrbStyleSetting.current() != OrbStyle.PIXEL_HEAD) release();
    }

    /** Decodes every bucket on a background thread (once at a time). */
    static void preload() {
        if (app == null || !PRELOADING.compareAndSet(false, true)) return;
        Thread thread = new Thread(() -> {
            try {
                for (int bucket : PRELOAD_ORDER) {
                    if (OrbStyleSetting.current() != OrbStyle.PIXEL_HEAD) break;
                    art(bucket);
                }
            } finally {
                PRELOADING.set(false);
            }
        }, "pixel-head-art");
        // Default priority on purpose: the first frame of a hero orb waits on this thread's
        // per-bucket lock (art()), so a lowest-priority decoder starved at boot would stall the
        // UI thread for as long as the scheduler keeps it off the CPU.
        thread.start();
    }

    /** Lets the bitmaps go (the renderer keeps what is still on screen until the next frame). */
    static void release() {
        for (int bucket = 0; bucket < ART.length(); bucket++) ART.set(bucket, null);
    }
}
