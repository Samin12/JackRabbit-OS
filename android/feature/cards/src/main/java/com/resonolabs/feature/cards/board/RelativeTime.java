package com.resonolabs.feature.cards.board;

/** Short, glanceable relative durations ("in 25 min", "1 hr 5 min left"). Pure Java. */
public final class RelativeTime {
    private RelativeTime() {}

    /** Time until a start: "starting now", "in 1 min", "in 25 min", "in 1 hr 20 min", "in 6 hr". */
    public static String until(long millis) {
        if (millis < 60_000L) return "starting now";
        return "in " + span(minutesCeil(millis));
    }

    /** Time remaining in a running event: "1 min left", "25 min left", "1 hr 5 min left". */
    public static String left(long millis) {
        if (millis <= 0L) return "ending now";
        return span(minutesCeil(millis)) + " left";
    }

    /** "25 min", "1 hr", "1 hr 20 min"; five hours and more round to whole hours. */
    public static String span(long minutes) {
        if (minutes < 60L) return Math.max(1L, minutes) + " min";
        long hours = minutes / 60L;
        long rest = minutes % 60L;
        if (hours >= 5L) return (rest >= 30L ? hours + 1L : hours) + " hr";
        return rest == 0L ? hours + " hr" : hours + " hr " + rest + " min";
    }

    static long minutesCeil(long millis) {
        return (millis + 59_999L) / 60_000L;
    }
}
