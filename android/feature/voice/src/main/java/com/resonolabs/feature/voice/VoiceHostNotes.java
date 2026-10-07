package com.resonolabs.feature.voice;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Host context notes sent to the model when a voice session connects (pure, unit-tested).
 * The Realtime instructions carry no clock, and tool results (calendar) report UTC times, so
 * without the device's local time and zone the model cannot tell what "tomorrow" means or say
 * "8 AM" instead of "12:00 UTC".
 */
final class VoiceHostNotes {
    private static final DateTimeFormatter CLOCK =
            DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy, h:mm a z", Locale.US);

    private VoiceHostNotes() {}

    /** {@code [Clock] It is Wednesday, October 7, 2026, 5:40 PM EDT on the R1 (America/New_York, UTC-04:00). ...} */
    static String clock(ZonedDateTime now) {
        String offset = now.getOffset().getTotalSeconds() == 0 ? "UTC" : "UTC" + now.getOffset().getId();
        return "[Clock] It is " + CLOCK.format(now) + " on the R1 ("
                + now.getZone().getId() + ", " + offset + "). Tool results may give times in UTC: "
                + "convert them to this local time before you say or show them, and read today, "
                + "tomorrow and weekdays in this time zone.";
    }

    /** The note sent on connect: the clock, then the screen summary ("[Screen] ...") if any. */
    static String onConnect(ZonedDateTime now, String screen) {
        String clock = clock(now);
        return screen == null || screen.isBlank() ? clock : clock + "\n" + screen.trim();
    }
}
