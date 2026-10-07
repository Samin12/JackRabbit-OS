package com.resonolabs.feature.t3;

import static org.junit.Assert.assertEquals;

import java.time.Instant;
import java.time.ZoneId;

import org.junit.Test;

public final class T3TimeTest {
    private static final long NOW = Instant.parse("2026-10-07T18:00:00Z").toEpochMilli();
    private static final ZoneId NY = ZoneId.of("America/New_York");

    @Test
    public void parsesRuntimeTimestampShapes() {
        assertEquals(Instant.parse("2026-10-07T17:18:33.806Z").toEpochMilli(), T3Time.parse("2026-10-07T17:18:33.806Z"));
        assertEquals(Instant.parse("2026-10-07T17:18:33.806Z").toEpochMilli(),
                T3Time.parse("2026-10-07T17:18:33.806000+00:00"));
        assertEquals(Instant.parse("2026-10-07T21:18:33Z").toEpochMilli(), T3Time.parse("2026-10-07T17:18:33-04:00"));
        assertEquals(Instant.parse("2026-10-07T17:18:33Z").toEpochMilli(), T3Time.parse("2026-10-07T17:18:33"));
        assertEquals(0L, T3Time.parse(null));
        assertEquals(0L, T3Time.parse(""));
        assertEquals(0L, T3Time.parse("null"));
        assertEquals(0L, T3Time.parse("yesterday"));
    }

    @Test
    public void relativeBuckets() {
        assertEquals("now", T3Time.relative(NOW, NOW - 10_000L, NY));
        assertEquals("now", T3Time.relative(NOW, NOW + 60_000L, NY)); // clock skew never goes negative
        assertEquals("1m", T3Time.relative(NOW, NOW - 50_000L, NY));
        assertEquals("4m", T3Time.relative(NOW, NOW - 4 * 60_000L, NY));
        assertEquals("59m", T3Time.relative(NOW, NOW - 59 * 60_000L, NY));
        assertEquals("1h", T3Time.relative(NOW, NOW - 61 * 60_000L, NY));
        assertEquals("23h", T3Time.relative(NOW, NOW - 23 * 3_600_000L, NY));
        assertEquals("1d", T3Time.relative(NOW, NOW - 25 * 3_600_000L, NY));
        assertEquals("6d", T3Time.relative(NOW, NOW - 6 * 86_400_000L, NY));
        assertEquals("Sep 29", T3Time.relative(NOW, NOW - 8 * 86_400_000L, NY));
        assertEquals("", T3Time.relative(NOW, 0L, NY));
    }

    @Test
    public void updatedPhrase() {
        assertEquals("Updated just now", T3Time.updated(NOW, NOW - 2_000L));
        assertEquals("Updated 3m ago", T3Time.updated(NOW, NOW - 3 * 60_000L));
        assertEquals("", T3Time.updated(NOW, 0L));
    }
}
