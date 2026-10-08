package com.resonolabs.ui.design;

import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class PixelHeadMotionTest {
    @Test public void everyLoopFrameMapsToTheStoredPoseWithTheSameYaw() {
        Set<Integer> used = new HashSet<>();
        for (int frame = 0; frame < PixelHeadMotion.LOOP_FRAMES; frame++) {
            int pose = PixelHeadMotion.poseForFrame(frame);
            assertTrue(pose >= 0 && pose < PixelHeadMotion.POSES);
            // The v2 art: a 3/4 view swaying around -32 degrees (frames_info.json yaw_curve).
            double yaw = -32.0 + 12.0 * Math.sin(2.0 * Math.PI * frame / PixelHeadMotion.LOOP_FRAMES);
            assertEquals("frame " + frame, yaw, PixelHeadMotion.yawForPose(pose), 1e-9);
            used.add(pose);
        }
        assertEquals(PixelHeadMotion.POSES, used.size());
    }

    @Test public void poseYawLandmarks() {
        assertEquals(-44.0, PixelHeadMotion.yawForPose(0), 1e-9);
        assertEquals(-32.0, PixelHeadMotion.yawForPose(PixelHeadMotion.CENTER_POSE), 1e-9);
        assertEquals(-20.0, PixelHeadMotion.yawForPose(24), 1e-9);
        assertEquals(12, PixelHeadMotion.CENTER_POSE);
        assertEquals(PixelHeadMotion.CENTER_POSE, PixelHead.CENTER_POSE);
        for (int pose = 1; pose < PixelHeadMotion.POSES; pose++) {
            assertTrue(PixelHeadMotion.yawForPose(pose) > PixelHeadMotion.yawForPose(pose - 1));
        }
    }

    @Test public void loopLandmarks() {
        assertEquals(12, PixelHeadMotion.poseForFrame(0));
        assertEquals(24, PixelHeadMotion.poseForFrame(12));
        assertEquals(12, PixelHeadMotion.poseForFrame(24));
        assertEquals(0, PixelHeadMotion.poseForFrame(36));
        assertEquals(11, PixelHeadMotion.poseForFrame(47));
        assertEquals(PixelHeadMotion.poseForFrame(5), PixelHeadMotion.poseForFrame(24 - 5));
    }

    @Test public void framesWrapBothWays() {
        assertEquals(PixelHeadMotion.poseForFrame(47), PixelHeadMotion.poseForFrame(-1));
        assertEquals(PixelHeadMotion.poseForFrame(3), PixelHeadMotion.poseForFrame(48 + 3));
        assertEquals(PixelHeadMotion.poseForFrame(3), PixelHeadMotion.poseForFrame(-45));
    }

    @Test public void consecutiveFramesNeverJumpMoreThanOnePose() {
        for (int frame = 0; frame < PixelHeadMotion.LOOP_FRAMES; frame++) {
            int a = PixelHeadMotion.poseForFrame(frame);
            int b = PixelHeadMotion.poseForFrame(frame + 1);
            assertTrue("frame " + frame, Math.abs(a - b) <= 1);
        }
    }

    @Test public void swingRateFollowsOrbSpeed() {
        assertEquals(0f, PixelHeadMotion.framesPerSecond(0f), 0f);
        assertEquals(0f, PixelHeadMotion.framesPerSecond(-1f), 0f);
        assertEquals(0f, PixelHeadMotion.framesPerSecond(Float.NaN), 0f);
        float idle = PixelHeadMotion.framesPerSecond(0.6f);
        float listening = PixelHeadMotion.framesPerSecond(1.1f);
        float speaking = PixelHeadMotion.framesPerSecond(1.9f);
        assertTrue(idle >= 9f && idle <= 11f);
        assertTrue(listening > idle && listening <= 12.5f);
        assertTrue(speaking > listening && speaking <= 16f);
        assertTrue(PixelHeadMotion.framesPerSecond(0.01f) >= 6f);
        assertEquals(16f, PixelHeadMotion.framesPerSecond(10f), 0f);
    }

    @Test public void advanceUsesRealTimeWrapsAndCapsLongPauses() {
        assertEquals(2f, PixelHeadMotion.advance(0f, 200L, 10f), 1e-4);
        assertEquals(1f, PixelHeadMotion.advance(47f, 200L, 10f), 1e-4);
        assertEquals(2.5f, PixelHeadMotion.advance(0f, 60_000L, 10f), 1e-4);
        assertEquals(5f, PixelHeadMotion.advance(5f, -40L, 10f), 0f);
        assertEquals(5f, PixelHeadMotion.advance(5f, 100L, 0f), 0f);
        float loop = 0f;
        for (int i = 0; i < 1000; i++) {
            loop = PixelHeadMotion.advance(loop, 33L, 15f);
            assertTrue(loop >= 0f && loop < PixelHeadMotion.LOOP_FRAMES);
        }
    }

    @Test public void eyesLightOnlyWhileListeningOrSpeaking() {
        assertEquals(0f, PixelHeadMotion.litFor(0.15f), 0f);   // idle
        assertEquals(0f, PixelHeadMotion.litFor(0.2f), 0f);    // control center
        assertEquals(1f, PixelHeadMotion.litFor(0.6f), 0f);    // listening
        assertEquals(1f, PixelHeadMotion.litFor(1f), 0f);      // speaking
        float connecting = PixelHeadMotion.litFor(0.4f);
        assertTrue(connecting > 0f && connecting < 0.2f);
    }

    @Test public void easeSettlesOnTheTarget() {
        float value = 0f;
        for (int i = 0; i < 40; i++) value = PixelHeadMotion.ease(value, 1f, 33L);
        assertEquals(1f, value, 0f);
        assertEquals(0.5f, PixelHeadMotion.ease(0.5f, 1f, 0L), 0f);
        assertEquals(1f, PixelHeadMotion.ease(0f, 1f, 5_000L), 0f);
    }

    @Test public void bucketsPreferArtWithinTheUpscaleLimit() {
        float[] heights = PixelHeadAtlas.HEAD_H;
        int last = heights.length - 1;
        // Voice page idle / listening / speaking (radius 96 / 104 / 112): the big art.
        assertEquals(last, PixelHeadMotion.bucketFor(96f * 2.2f, heights));
        assertEquals(last, PixelHeadMotion.bucketFor(112f * 2.2f, heights));
        // Docked transcript and Control Center (radius 30), Cards board (19), camera (18): docked art.
        assertEquals(0, PixelHeadMotion.bucketFor(30f * 2.2f, heights));
        assertEquals(0, PixelHeadMotion.bucketFor(19f * 2.2f, heights));
        assertEquals(0, PixelHeadMotion.bucketFor(18f * 2.2f, heights));
        // Settings About (50) and the Display preview (44): the 112 px art.
        assertEquals(1, PixelHeadMotion.bucketFor(50f * 2.2f, heights));
        assertEquals(1, PixelHeadMotion.bucketFor(44f * 2.2f, heights));
        // Background run, running (56): the 176 px art.
        assertEquals(2, PixelHeadMotion.bucketFor(56f * 2.2f, heights));
        assertEquals(last, PixelHeadMotion.bucketFor(10_000f, heights));
        for (float px = 10f; px < 400f; px += 1f) {
            int bucket = PixelHeadMotion.bucketFor(px, heights);
            float scale = px / heights[bucket];
            assertTrue("upscale at " + px, scale <= PixelHeadMotion.MAX_UPSCALE + 1e-4 || bucket == last);
            if (bucket > 0) assertTrue("smaller art would do at " + px, heights[bucket - 1] * PixelHeadMotion.MAX_UPSCALE < px);
        }
    }

    @Test public void blinksComeEveryFourToSevenSecondsAndVary() {
        Set<Long> gaps = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            long gap = PixelHeadMotion.blinkGapMs(i);
            assertTrue(gap >= 4000L && gap <= 7000L);
            gaps.add(gap);
        }
        assertTrue(gaps.size() > 100);
        assertEquals(PixelHeadMotion.blinkGapMs(7), PixelHeadMotion.blinkGapMs(7));
    }

    @Test public void blinkWindow() {
        assertFalse(PixelHeadMotion.blinking(999L, 1000L));
        assertTrue(PixelHeadMotion.blinking(1000L, 1000L));
        assertTrue(PixelHeadMotion.blinking(1000L + PixelHeadMotion.BLINK_MS - 1, 1000L));
        assertFalse(PixelHeadMotion.blinking(1000L + PixelHeadMotion.BLINK_MS, 1000L));
    }

    @Test public void bobIsASlowEasedFloatThatQuickensWhenLit() {
        // idle: one full bob in 3.2 s; lit: 2.6 s
        float phase = 0f;
        for (int i = 0; i < 100; i++) phase = PixelHeadMotion.advanceBob(phase, 16L, 0f);
        assertEquals(2.0 * Math.PI * 1.6 / 3.2, phase, 1e-3);
        assertEquals(2.0 * Math.PI * 0.1 / 2.6, PixelHeadMotion.advanceBob(0f, 100L, 1f), 1e-4);
        // long pauses are capped like the sway, negative steps ignored, phase wraps
        assertEquals(PixelHeadMotion.advanceBob(0f, PixelHeadMotion.MAX_STEP_MS, 0f),
                PixelHeadMotion.advanceBob(0f, 60_000L, 0f), 0f);
        assertEquals(1f, PixelHeadMotion.advanceBob(1f, -50L, 0f), 0f);
        for (int i = 0; i < 2000; i++) {
            phase = PixelHeadMotion.advanceBob(phase, 33L, i % 3 == 0 ? 1f : 0f);
            assertTrue(phase >= 0f && phase < 2.0 * Math.PI);
        }
        // a Voice page caller passes 5 px: about +-3.5 px calm, +-4 px lit; 0 at rest
        assertEquals(0f, PixelHeadMotion.bobOffset(0f, 5f, 0f), 0f);
        assertEquals(3.5f, PixelHeadMotion.bobOffset((float) (Math.PI / 2), 5f, 0f), 1e-4);
        assertEquals(-4f, PixelHeadMotion.bobOffset((float) (-Math.PI / 2), 5f, 1f), 1e-4);
        assertEquals(0f, PixelHeadMotion.bobOffset(1f, 0f, 1f), 0f);
        // eased: the step per frame is largest through the middle and tiny at the ends
        float middle = Math.abs(PixelHeadMotion.bobOffset(0.05f, 5f, 0f) - PixelHeadMotion.bobOffset(0f, 5f, 0f));
        float end = Math.abs(PixelHeadMotion.bobOffset((float) (Math.PI / 2), 5f, 0f)
                - PixelHeadMotion.bobOffset((float) (Math.PI / 2) - 0.05f, 5f, 0f));
        assertTrue(end < middle / 10f);
    }

    @Test public void mouthStaysShutUnlessSpeaking() {
        PixelHeadMotion.Mouth mouth = new PixelHeadMotion.Mouth();
        for (long t = 1000L; t < 5000L; t += 16L) assertFalse(mouth.update(false, t));
        assertTrue(mouth.update(true, 5000L));                 // opens as soon as speech starts
        assertFalse(mouth.update(false, 5016L));               // and shuts the moment it stops
        assertFalse(mouth.isOpen());
        assertTrue(mouth.update(true, 9000L));                 // a new reply opens again at once
    }

    @Test public void mouthFlapsAtASyllableCadenceWithPhrasePauses() {
        PixelHeadMotion.Mouth mouth = new PixelHeadMotion.Mouth();
        long start = 10_000L;
        boolean open = mouth.update(true, start);
        long changedAt = start;
        int syllables = 0;
        int pauses = 0;
        int sinceTalk = 0;
        Set<Long> openLengths = new HashSet<>();
        long end = start + 20_000L;
        for (long t = start + 1; t <= end; t++) {
            boolean now = mouth.update(true, t);
            if (now == open) continue;
            long length = t - changedAt;
            if (open) {
                assertTrue("open " + length, length >= 70L && length <= 110L);
                openLengths.add(length);
                syllables++;
                sinceTalk++;
            } else if (length >= PixelHeadMotion.MOUTH_PAUSE_MIN_MS) {
                assertTrue("pause " + length, length <= 420L);
                assertTrue("phrase of " + sinceTalk, sinceTalk >= 5 && sinceTalk <= 12);
                pauses++;
                sinceTalk = 0;
            } else {
                assertTrue("closed " + length, length >= 55L && length <= 90L);
            }
            open = now;
            changedAt = t;
        }
        float rate = syllables / 20f;
        assertTrue("syllables per second " + rate, rate >= 4.5f && rate <= 8f);
        assertTrue(pauses >= 10);
        assertTrue(openLengths.size() > 10);                   // varied, not a metronome
    }

    @Test public void mouthIsDeterministicAndSurvivesFrameDropsAndStalls() {
        PixelHeadMotion.Mouth a = new PixelHeadMotion.Mouth();
        PixelHeadMotion.Mouth b = new PixelHeadMotion.Mouth();
        int opens = 0;
        boolean was = false;
        for (long t = 0L; t < 4000L; t += 16L) {
            boolean open = a.update(true, 100L + t);
            assertEquals(open, b.update(true, 100L + t));
            if (open && !was) opens++;
            was = open;
        }
        assertTrue("opens in 4 s at 60 fps: " + opens, opens >= 16 && opens <= 32);
        // a dropped frame or a hidden page: no burst of catch-up flaps, it simply carries on
        long t = 100_000L;
        a.update(true, t);
        assertTrue(a.update(true, t + 60_000L));               // after a stall it restarts open
        a.update(true, t + 60_200L);                           // 200 ms jump: catches up in bounds
    }
}
