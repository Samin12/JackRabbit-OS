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
            double yaw = 28.0 * Math.sin(2.0 * Math.PI * frame / PixelHeadMotion.LOOP_FRAMES);
            assertEquals("frame " + frame, yaw, PixelHeadMotion.yawForPose(pose), 1e-9);
            used.add(pose);
        }
        assertEquals(PixelHeadMotion.POSES, used.size());
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
        // Settings About (50) and Display preview (40): the 112 px art.
        assertEquals(1, PixelHeadMotion.bucketFor(50f * 2.2f, heights));
        assertEquals(1, PixelHeadMotion.bucketFor(40f * 2.2f, heights));
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
}
