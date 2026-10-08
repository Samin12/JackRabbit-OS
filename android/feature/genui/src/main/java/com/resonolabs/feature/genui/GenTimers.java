package com.resonolabs.feature.genui;

/**
 * Timer math on {@link GenBlock} timer blocks. Absolute {@code endsAt} on the
 * elapsedRealtime clock, so the countdown survives redraw gaps and deep sleep without ticking.
 */
public final class GenTimers {
    private GenTimers() {}

    public static void start(GenBlock timer, long now, long durationMs) {
        long duration = Math.max(1000L, durationMs);
        timer.endsAt = now + duration;
        timer.totalMs = duration;
        timer.paused = false;
        timer.pausedRemainingMs = 0L;
        timer.done = false;
    }

    public static long remaining(GenBlock timer, long now) {
        if (timer.paused) return Math.max(0L, timer.pausedRemainingMs);
        return Math.max(0L, timer.endsAt - now);
    }

    /** Remaining fraction of the (possibly extended) total, 0..1. */
    public static float fraction(GenBlock timer, long now) {
        long total = Math.max(1L, timer.totalMs);
        return Math.max(0f, Math.min(1f, remaining(timer, now) / (float) total));
    }

    public static boolean isDue(GenBlock timer, long now) {
        return !timer.paused && !timer.done && now >= timer.endsAt;
    }

    /** +1m / +5m: extends a running or paused timer; restarts a finished one. */
    public static void add(GenBlock timer, long now, long ms) {
        if (timer.done || (!timer.paused && now >= timer.endsAt)) {
            start(timer, now, ms);
            return;
        }
        if (timer.paused) timer.pausedRemainingMs += ms;
        else timer.endsAt += ms;
        timer.totalMs = Math.max(timer.totalMs + ms, remaining(timer, now));
    }

    public static boolean pause(GenBlock timer, long now) {
        if (timer.paused || timer.done) return false;
        timer.pausedRemainingMs = remaining(timer, now);
        timer.paused = true;
        return true;
    }

    public static boolean resume(GenBlock timer, long now) {
        if (!timer.paused || timer.done) return false;
        timer.endsAt = now + timer.pausedRemainingMs;
        timer.paused = false;
        return true;
    }

    /**
     * Writes {@code mm:ss} (or {@code h:mm:ss}) into {@code out} without allocating.
     * Seconds round up, so a fresh 9 minute timer reads 09:00 and the last second reads 00:01.
     *
     * @return number of chars written
     */
    public static int format(long remainingMs, char[] out) {
        long seconds = (Math.max(0L, remainingMs) + 999L) / 1000L;
        long hours = seconds / 3600L;
        int minutes = (int) ((seconds / 60L) % 60L);
        int secs = (int) (seconds % 60L);
        int n = 0;
        if (hours > 0) {
            if (hours >= 10) out[n++] = (char) ('0' + (hours / 10) % 10);
            out[n++] = (char) ('0' + hours % 10);
            out[n++] = ':';
        }
        out[n++] = (char) ('0' + minutes / 10);
        out[n++] = (char) ('0' + minutes % 10);
        out[n++] = ':';
        out[n++] = (char) ('0' + secs / 10);
        out[n++] = (char) ('0' + secs % 10);
        return n;
    }

    public static String format(long remainingMs) {
        char[] buffer = new char[12];
        return new String(buffer, 0, format(remainingMs, buffer));
    }

    /** Spoken/summary form: "4:12" style without a leading zero minute. */
    public static String brief(long remainingMs) {
        String value = format(remainingMs);
        return value.length() == 5 && value.charAt(0) == '0' ? value.substring(1) : value;
    }
}
