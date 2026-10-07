package com.resonolabs.ui.design;

/**
 * Pure timing and sizing rules of the Pixel head (no Android types, unit-tested).
 *
 * <p>The art is a 48-frame head swing, yaw = 28 sin(2 pi i / 48) degrees. Frame i and frame
 * 24 - i show the same yaw, so only 25 poses are stored: pose 0 = -28, 12 = front, 24 = +28.
 */
final class PixelHeadMotion {
    static final int LOOP_FRAMES = 48;
    static final int POSES = 25;
    static final int FRONT_POSE = 12;
    /** Head height (wing tip to neck) per orb radius, so it fills about the orb's footprint. */
    static final float HEAD_PER_RADIUS = 2.2f;
    /** Bigger art is preferred over upscaling smaller art past this factor. */
    static final float MAX_UPSCALE = 1.2f;
    /** Longest real-time step taken in one draw; longer pauses (page hidden) do not jump ahead. */
    static final long MAX_STEP_MS = 250L;
    static final long BLINK_MS = 170L;
    static final long BLINK_MIN_GAP_MS = 4000L;
    static final long BLINK_GAP_SPREAD_MS = 3000L;

    private PixelHeadMotion() {}

    /** Stored pose for loop frame {@code frame} (any integer; wraps). */
    static int poseForFrame(int frame) {
        int i = Math.floorMod(frame, LOOP_FRAMES);
        if (i <= FRONT_POSE) return FRONT_POSE + i;          // front -> +28
        if (i < 36) return 36 - i;                           // +28 -> front -> -28 (frame 24 - i)
        return i - 36;                                       // -28 -> front
    }

    /** Head yaw in degrees of a stored pose. */
    static double yawForPose(int pose) {
        return 28.0 * Math.sin(2.0 * Math.PI * (pose - FRONT_POSE) / LOOP_FRAMES);
    }

    /**
     * Loop frames per second for a FluidOrb speed: about 10 fps when idle (speed 0.6), 12 while
     * listening, 15 while speaking. Speed 0 or less freezes the swing.
     */
    static float framesPerSecond(float speed) {
        if (!(speed > 0f)) return 0f;
        return Math.max(6f, Math.min(16f, 7f + 4.5f * speed));
    }

    /** New loop position in [0, 48) after {@code elapsedMs} at {@code fps}. */
    static float advance(float loop, long elapsedMs, float fps) {
        long step = Math.max(0L, Math.min(MAX_STEP_MS, elapsedMs));
        float next = loop + step / 1000f * fps;
        next %= LOOP_FRAMES;
        if (next < 0f) next += LOOP_FRAMES;
        return next;
    }

    /** Eye light for an orb energy: off when calm (idle 0.15), full while listening (0.6+). */
    static float litFor(float energy) {
        float t = (energy - 0.35f) / 0.25f;
        t = Math.max(0f, Math.min(1f, t));
        return t * t * (3f - 2f * t);
    }

    /** Eases {@code current} toward {@code target} with a ~120 ms time constant. */
    static float ease(float current, float target, long elapsedMs) {
        float k = Math.max(0f, Math.min(1f, Math.max(0L, Math.min(MAX_STEP_MS, elapsedMs)) / 120f));
        float next = current + (target - current) * k;
        return Math.abs(next - target) < 0.004f ? target : next;
    }

    /**
     * Size bucket to draw {@code headPx} from: the smallest whose art needs at most
     * {@link #MAX_UPSCALE}, else the largest. {@code headHeights} is ordered smallest first.
     */
    static int bucketFor(float headPx, float[] headHeights) {
        for (int i = 0; i < headHeights.length; i++) {
            if (headHeights[i] * MAX_UPSCALE >= headPx) return i;
        }
        return headHeights.length - 1;
    }

    /** Gap before blink number {@code index}: 4-7 s, varied but deterministic. */
    static long blinkGapMs(int index) {
        int h = index * 0x9E3779B1;
        h ^= h >>> 15;
        h *= 0x85EBCA6B;
        h ^= h >>> 13;
        return BLINK_MIN_GAP_MS + Math.floorMod(h, (int) BLINK_GAP_SPREAD_MS + 1);
    }

    /** True while a blink that starts at {@code blinkAt} is showing at {@code now}. */
    static boolean blinking(long now, long blinkAt) {
        return now >= blinkAt && now < blinkAt + BLINK_MS;
    }
}
