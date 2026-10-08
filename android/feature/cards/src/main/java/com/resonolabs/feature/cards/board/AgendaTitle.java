package com.resonolabs.feature.cards.board;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Glanceable event titles. Booking tools name events "&lt;event type&gt; between &lt;host&gt;
 * and &lt;guest&gt;" (Cal.com: "Monday 1-1 with Samin between Samin and Sarah Mohepath"); in a
 * one-line row the guest, the part that differs, would be cut off. Such titles become
 * "Sarah Mohepath · Monday 1-1" (the host's own name dropped from the event type). Any other
 * title is returned unchanged. Pure Java.
 */
public final class AgendaTitle {
    /** Names are 1-4 words, so ordinary titles that merely contain "between" stay as they are. */
    private static final Pattern BETWEEN = Pattern.compile(
            "^(.+?)\\s+between\\s+(\\S+(?:\\s+\\S+){0,3})\\s+and\\s+(\\S+(?:\\s+\\S+){0,3})$");

    private AgendaTitle() {}

    public static String compact(String title) {
        String text = title == null ? "" : title.trim();
        Matcher match = BETWEEN.matcher(text);
        if (!match.matches()) return text;
        String type = match.group(1).trim();
        String host = match.group(2).trim();
        String guest = match.group(3).trim();
        // "Monday 1-1 with Samin" → "Monday 1-1" when Samin is the host.
        String suffix = " with " + host;
        if (type.toLowerCase(Locale.ROOT).endsWith(suffix.toLowerCase(Locale.ROOT)) && type.length() > suffix.length()) {
            type = type.substring(0, type.length() - suffix.length()).trim();
        }
        return guest + " · " + type;
    }
}
