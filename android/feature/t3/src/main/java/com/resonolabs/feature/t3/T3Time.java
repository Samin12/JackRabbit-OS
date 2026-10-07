package com.resonolabs.feature.t3;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** ISO timestamps from the runtime into glanceable relative times ("now", "4m", "3h", "Oct 3"). */
final class T3Time {
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("MMM d", Locale.US);

    private T3Time() {}

    /** Epoch millis, or 0 when missing or unparseable. Accepts Z, offsets, and naive UTC. */
    static long parse(String iso) {
        if (iso == null) return 0L;
        String value = iso.trim();
        if (value.isEmpty() || "null".equals(value)) return 0L;
        try {
            return Instant.parse(value).toEpochMilli();
        } catch (RuntimeException ignored) {
            // Fall through to offset / naive forms.
        }
        try {
            return OffsetDateTime.parse(value).toInstant().toEpochMilli();
        } catch (RuntimeException ignored) {
            // Fall through to naive form.
        }
        try {
            return LocalDateTime.parse(value).toInstant(ZoneOffset.UTC).toEpochMilli();
        } catch (RuntimeException ignored) {
            return 0L;
        }
    }

    static String relative(long nowMillis, long thenMillis) {
        return relative(nowMillis, thenMillis, ZoneId.systemDefault());
    }

    static String relative(long nowMillis, long thenMillis, ZoneId zone) {
        if (thenMillis <= 0L) return "";
        long seconds = Math.max(0L, (nowMillis - thenMillis) / 1000L);
        if (seconds < 45L) return "now";
        long minutes = Math.max(1L, Math.round(seconds / 60.0));
        if (minutes < 60L) return minutes + "m";
        long hours = minutes / 60L;
        if (hours < 24L) return hours + "h";
        long days = hours / 24L;
        if (days < 7L) return days + "d";
        return DAY.format(Instant.ofEpochMilli(thenMillis).atZone(zone));
    }

    /** "Synced just now" / "Synced 4m ago" for the list header. */
    static String synced(long nowMillis, long thenMillis) {
        if (thenMillis <= 0L) return "";
        String value = relative(nowMillis, thenMillis);
        if ("now".equals(value)) return "Synced just now";
        return value.indexOf(' ') >= 0 ? "Synced " + value : "Synced " + value + " ago";
    }
}
