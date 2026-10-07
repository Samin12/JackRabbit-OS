package com.resonolabs.feature.cards.board;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class RelativeTimeTest {
    private static final long MINUTE = 60_000L;

    @Test public void untilRoundsUpToWholeMinutes() {
        assertEquals("starting now", RelativeTime.until(0L));
        assertEquals("starting now", RelativeTime.until(59_000L));
        assertEquals("in 1 min", RelativeTime.until(MINUTE));
        assertEquals("in 25 min", RelativeTime.until(24 * MINUTE + 30_000L));
        assertEquals("in 59 min", RelativeTime.until(59 * MINUTE));
        assertEquals("in 1 hr", RelativeTime.until(60 * MINUTE));
        assertEquals("in 1 hr 20 min", RelativeTime.until(80 * MINUTE));
        assertEquals("in 4 hr 59 min", RelativeTime.until(299 * MINUTE));
        assertEquals("in 5 hr", RelativeTime.until(329 * MINUTE));
        assertEquals("in 6 hr", RelativeTime.until(330 * MINUTE));
    }

    @Test public void leftDescribesTheRemainderOfARunningEvent() {
        assertEquals("ending now", RelativeTime.left(0L));
        assertEquals("1 min left", RelativeTime.left(5_000L));
        assertEquals("25 min left", RelativeTime.left(25 * MINUTE));
        assertEquals("1 hr 5 min left", RelativeTime.left(65 * MINUTE));
    }
}
