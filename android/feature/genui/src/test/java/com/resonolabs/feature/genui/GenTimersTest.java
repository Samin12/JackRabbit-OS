package com.resonolabs.feature.genui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class GenTimersTest {
    @Test public void formatsRoundingSecondsUp() {
        assertEquals("09:00", GenTimers.format(540_000L));
        assertEquals("00:01", GenTimers.format(1L));
        assertEquals("00:00", GenTimers.format(0L));
        assertEquals("00:00", GenTimers.format(-5L));
        assertEquals("01:00", GenTimers.format(59_001L));
        assertEquals("1:00:00", GenTimers.format(3_600_000L));
        assertEquals("12:00:00", GenTimers.format(43_200_000L));
        assertEquals("4:12", GenTimers.brief(252_000L));
    }

    @Test public void formatWritesIntoTheBufferWithoutAllocating() {
        char[] buffer = new char[12];
        int n = GenTimers.format(299_000L, buffer);
        assertEquals("04:59", new String(buffer, 0, n));
    }

    @Test public void countdownMath() {
        GenBlock timer = new GenBlock(GenBlock.Type.TIMER);
        GenTimers.start(timer, 1_000L, 60_000L);
        assertEquals(60_000L, GenTimers.remaining(timer, 1_000L));
        assertEquals(30_000L, GenTimers.remaining(timer, 31_000L));
        assertEquals(0.5f, GenTimers.fraction(timer, 31_000L), 0.0001f);
        assertFalse(GenTimers.isDue(timer, 60_999L));
        assertTrue(GenTimers.isDue(timer, 61_000L));
        assertEquals(0L, GenTimers.remaining(timer, 90_000L));
    }

    @Test public void addExtendsTotalAndRemaining() {
        GenBlock timer = new GenBlock(GenBlock.Type.TIMER);
        GenTimers.start(timer, 0L, 540_000L);
        GenTimers.add(timer, 300_000L, 60_000L);
        assertEquals(300_000L, GenTimers.remaining(timer, 300_000L));
        assertEquals(600_000L, timer.totalMs);
        assertEquals(0.5f, GenTimers.fraction(timer, 300_000L), 0.0001f);
    }

    @Test public void pauseFreezesAndResumeContinues() {
        GenBlock timer = new GenBlock(GenBlock.Type.TIMER);
        GenTimers.start(timer, 0L, 100_000L);
        assertTrue(GenTimers.pause(timer, 40_000L));
        assertFalse(GenTimers.pause(timer, 41_000L));
        assertEquals(60_000L, GenTimers.remaining(timer, 500_000L));
        assertFalse(GenTimers.isDue(timer, 500_000L));
        GenTimers.add(timer, 500_000L, 60_000L);
        assertEquals(120_000L, GenTimers.remaining(timer, 500_000L));
        assertTrue(GenTimers.resume(timer, 500_000L));
        assertEquals(620_000L, timer.endsAt);
        assertEquals(20_000L, GenTimers.remaining(timer, 600_000L));
    }

    @Test public void addAfterDoneRestarts() {
        GenBlock timer = new GenBlock(GenBlock.Type.TIMER);
        GenTimers.start(timer, 0L, 10_000L);
        timer.done = true;
        GenTimers.add(timer, 50_000L, 60_000L);
        assertFalse(timer.done);
        assertEquals(110_000L, timer.endsAt);
        assertEquals(60_000L, timer.totalMs);
    }
}
