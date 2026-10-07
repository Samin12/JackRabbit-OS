package com.resonolabs.feature.cards.board;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The short "where" of an agenda row. Real calendar feeds put raw meeting links in the location
 * ({@code https://us02web.zoom.us/j/868…}), Calendly writes "Google Meet (instructions in
 * description)", and Google Calendar keeps the Meet link only in the description; a row has
 * room for a word or two, so these become "Zoom", "Google Meet", "Teams". Street addresses keep
 * their first part ("Tartine Bakery, 600 Guerrero St, …" → "Tartine Bakery"). Pure Java.
 */
public final class AgendaPlace {
    private static final Pattern URL = Pattern.compile("(?i)\\b(?:https?://|www\\.)[^\\s<>\"]+");
    private static final Pattern HOST = Pattern.compile("(?i)^(?:https?://)?(?:www\\.)?([^/:?#\\s]+)");
    /** Recognised services: [label, needle in a link or the text]. Order matters (Meet before Google). */
    private static final String[][] SERVICES = {
            {"Zoom", "zoom.us"}, {"Zoom", "zoom.com"}, {"Zoom", "zoom meeting"}, {"Zoom", "zoom call"},
            {"Google Meet", "meet.google.com"}, {"Google Meet", "google meet"}, {"Google Meet", "hangouts"},
            {"Teams", "teams.microsoft.com"}, {"Teams", "teams.live.com"}, {"Teams", "microsoft teams"},
            {"Webex", "webex.com"}, {"Webex", "webex"}, {"Whereby", "whereby.com"}, {"FaceTime", "facetime"},
            {"Discord", "discord.gg"}, {"Discord", "discord.com"}, {"Slack huddle", "slack.com"},
            {"Riverside", "riverside.fm"}, {"StreamYard", "streamyard.com"}, {"Skool", "skool.com"},
    };

    private AgendaPlace() {}

    /** Short label for a row: from the location, else a meeting link found in the description. */
    public static String label(String location, String description) {
        String place = clean(location);
        if (!place.isEmpty()) {
            String service = service(place);
            String text = clean(URL.matcher(place).replaceAll(" ")).replaceAll("^[\\s,;:|·•-]+|[\\s,;:|·•-]+$", "");
            if (service != null) {
                // "Zoom Meeting", "Google Meet (instructions in description)", a bare link → the service.
                // A real room next to a link keeps the room: "Studio B · Zoom".
                if (text.isEmpty() || service(text) != null || mentionsInstructions(text)) return service;
                return shortAddress(text) + " · " + service;
            }
            if (text.isEmpty()) {
                Matcher host = HOST.matcher(firstUrl(place));
                return host.find() ? host.group(1).toLowerCase(Locale.ROOT) : place;
            }
            return shortAddress(text);
        }
        String notes = clean(description);
        if (notes.isEmpty()) return "";
        // Only links count in a description: prose may mention Zoom without being a Zoom call.
        Matcher links = URL.matcher(notes);
        while (links.find()) {
            String service = service(links.group());
            if (service != null) return service;
        }
        return "";
    }

    /** "Tartine Bakery, 600 Guerrero St, San Francisco, CA" → "Tartine Bakery"; short places stay whole. */
    static String shortAddress(String text) {
        String[] parts = text.split(",");
        if (parts.length >= 3 && !parts[0].isBlank()) return parts[0].trim();
        return text;
    }

    private static String service(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        for (String[] service : SERVICES) if (lower.contains(service[1])) return service[0];
        return null;
    }

    private static boolean mentionsInstructions(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        return lower.contains("instructions") || lower.contains("see description") || lower.contains("link in");
    }

    private static String firstUrl(String text) {
        Matcher matcher = URL.matcher(text);
        return matcher.find() ? matcher.group() : text;
    }

    private static String clean(String value) {
        return value == null ? "" : value.replace('\n', ' ').replace('\r', ' ').replaceAll("\\s+", " ").trim();
    }
}
