package com.resonolabs.feature.cards.board;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class AgendaPlaceTest {
    @Test public void meetingLinksBecomeTheServiceName() {
        assertEquals("Zoom", AgendaPlace.label("https://us02web.zoom.us/j/8681235900", null));
        assertEquals("Zoom", AgendaPlace.label("Zoom Meeting https://zoom.us/j/123?pwd=abc", ""));
        assertEquals("Google Meet", AgendaPlace.label("Google Meet (instructions in description)", null));
        assertEquals("Google Meet", AgendaPlace.label("https://meet.google.com/exg-tkwr-rmu", null));
        assertEquals("Teams", AgendaPlace.label("Microsoft Teams Meeting", null));
        assertEquals("Webex", AgendaPlace.label("https://acme.webex.com/meet/sam", null));
    }

    @Test public void aRoomNextToALinkKeepsTheRoom() {
        assertEquals("Studio B · Zoom", AgendaPlace.label("Studio B, https://us06web.zoom.us/j/555", null));
    }

    @Test public void otherLinksShowTheirHostAndAddressesTheirFirstPart() {
        assertEquals("calendly.com", AgendaPlace.label("https://calendly.com/events/abc/google_meet", null));
        assertEquals("Tartine Bakery",
                AgendaPlace.label("Tartine Bakery, 600 Guerrero St, San Francisco, CA 94110, USA", null));
        assertEquals("12 Main St, Springfield", AgendaPlace.label("12 Main St, Springfield", null));
        assertEquals("Zoom", AgendaPlace.label("Zoom", null));
        assertEquals("Dr. Lee · 12 Main St", AgendaPlace.label("  Dr. Lee · 12 Main St \n", null));
    }

    @Test public void withoutALocationOnlyAMeetingLinkInTheNotesCounts() {
        assertEquals("Google Meet", AgendaPlace.label("", "Join with Google Meet: https://meet.google.com/abc-defg-hij"));
        assertEquals("Zoom", AgendaPlace.label(null, "Agenda\nJoin Zoom Meeting\nhttps://us02web.zoom.us/j/86?pwd=x"));
        assertEquals("", AgendaPlace.label(null, "We'll pick Zoom or Meet later."));
        assertEquals("", AgendaPlace.label(null, "Docs: https://docs.example.com/plan"));
        assertEquals("", AgendaPlace.label(null, null));
    }
}
