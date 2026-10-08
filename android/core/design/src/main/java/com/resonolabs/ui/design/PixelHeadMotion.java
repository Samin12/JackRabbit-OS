package com.resonolabs.ui.design;

/**
 * Pure timing and sizing rules of the Pixel head (no Android types, unit-tested).
 *
 * <p>The art is a 48-frame sway around a 3/4 view, yaw = -32 + 12 sin(2 pi i / 48) degrees
 * (negative = turned toward the viewer's left). Frame i and frame 24 - i show the same yaw, so
 * only 25 poses are stored: pose 0 = -44 (most turned), 12 = -32 (the 3/4 centre), 24 = -20
 * (closest to frontal).
 */
final class PixelHeadMotion {
    static final int LOOP_FRAMES = 48;
    static final int POSES = 25;
    /** The resting 3/4 view (yaw -32), the middle of the sway. */
    static final int CENTER_POSE = 12;
    static final double YAW_CENTER_DEG = -32.0;
    static final double YAW_AMPLITUDE_DEG = 12.0;
    /** Head height (top of the headband to the hair ends) per orb radius, so it fills about the orb's footprint. */
    static final float HEAD_PER_RADIUS = 2.2f;
    /** Bigger art is preferred over upscaling smaller art past this factor. */
    static final float MAX_UPSCALE = 1.2f;
    /** Longest real-time step taken in one draw; longer pauses (page hidden) do not jump ahead. */
    static final long MAX_STEP_MS = 250L;
    static final long BLINK_MS = 170L;
    static final long BLINK_MIN_GAP_MS = 4000L;
    static final long BLINK_GAP_SPREAD_MS = 3000L;
    /** Mouth open / closed phases while speaking: one syllable every 125-200 ms (5-8 Hz). */
    static final long MOUTH_OPEN_MIN_MS = 70L;
    static final long MOUTH_OPEN_SPREAD_MS = 40L;
    static final long MOUTH_CLOSED_MIN_MS = 55L;
    static final long MOUTH_CLOSED_SPREAD_MS = 35L;
    /** A short closed beat between phrases of 5-12 syllables, so the flapping breathes. */
    static final long MOUTH_PAUSE_MIN_MS = 220L;
    static final long MOUTH_PAUSE_SPREAD_MS = 200L;
    static final int PHRASE_MIN_SYLLABLES = 5;
    static final int PHRASE_SYLLABLE_SPREAD = 7;

    private PixelHeadMotion() {}

    /** Stored pose for loop frame {@code frame} (any integer; wraps). */
    static int poseForFrame(int frame) {
        int i = Math.floorMod(frame, LOOP_FRAMES);
        if (i <= CENTER_POSE) return CENTER_POSE + i;        // centre (-32) -> most frontal (-20)
        if (i < 36) return 36 - i;                           // -20 -> centre -> -44 (frame 24 - i)
        return i - 36;                                       // most turned (-44) -> centre
    }

    /** Head yaw in degrees of a stored pose: -44 (pose 0) .. -32 (12) .. -20 (24). */
    static double yawForPose(int pose) {
        return YAW_CENTER_DEG
                + YAW_AMPLITUDE_DEG * Math.sin(2.0 * Math.PI * (pose - CENTER_POSE) / LOOP_FRAMES);
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
        return BLINK_MIN_GAP_MS + jitter(index, (int) BLINK_GAP_SPREAD_MS);
    }

    /** Deterministic pseudo-random 0..{@code spread} for step {@code index} (integer hash). */
    static int jitter(int index, int spread) {
        int h = index * 0x9E3779B1;
        h ^= h >>> 15;
        h *= 0x85EBCA6B;
        h ^= h >>> 13;
        return Math.floorMod(h, spread + 1);
    }

    /** True while a blink that starts at {@code blinkAt} is showing at {@code now}. */
    static boolean blinking(long now, long blinkAt) {
        return now >= blinkAt && now < blinkAt + BLINK_MS;
    }

    /**
     * The talking mouth: while speaking it opens and closes at a syllable cadence (5-8 Hz,
     * varied but deterministic) with a short pause between phrases; it shuts at once when
     * speaking stops. One per head; {@link #update} allocates nothing.
     */
    static final class Mouth {
        private boolean open;
        /** When the current open / closed phase ends; 0 = not speaking. */
        private long phaseEnd;
        private int step;
        private int syllablesLeft;

        /** Advances to {@code nowMs} and returns whether the mouth is open. */
        boolean update(boolean speaking, long nowMs) {
            if (!speaking) {
                open = false;
                phaseEnd = 0L;
                return false;
            }
            if (phaseEnd == 0L || nowMs - phaseEnd > MAX_STEP_MS) {
                // Starts talking (or the page was hidden for a while): open now, new phrase.
                syllablesLeft = phraseSyllables(step);
                open = true;
                phaseEnd = nowMs + openMs(step++);
                return true;
            }
            while (nowMs >= phaseEnd) {
                if (open) {
                    open = false;
                    phaseEnd += --syllablesLeft > 0 ? closedMs(step++) : pauseMs(step++);
                } else {
                    if (syllablesLeft <= 0) syllablesLeft = phraseSyllables(step);
                    open = true;
                    phaseEnd += openMs(step++);
                }
            }
            return open;
        }

        boolean isOpen() {
            return open;
        }

        static long openMs(int step) {
            return MOUTH_OPEN_MIN_MS + jitter(step, (int) MOUTH_OPEN_SPREAD_MS);
        }

        static long closedMs(int step) {
            return MOUTH_CLOSED_MIN_MS + jitter(step ^ 0x5BD1E995, (int) MOUTH_CLOSED_SPREAD_MS);
        }

        static long pauseMs(int step) {
            return MOUTH_PAUSE_MIN_MS + jitter(step ^ 0x27D4EB2F, (int) MOUTH_PAUSE_SPREAD_MS);
        }

        static int phraseSyllables(int step) {
            return PHRASE_MIN_SYLLABLES + jitter(step ^ 0x165667B1, PHRASE_SYLLABLE_SPREAD);
        }
    }
}
