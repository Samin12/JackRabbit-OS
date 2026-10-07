package com.resonolabs.feature.cards.board;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class AgendaTitleTest {
    @Test public void bookingTitlesLeadWithTheGuest() {
        assertEquals("Sarah Mohepath · Monday 1-1",
                AgendaTitle.compact("Monday 1-1 with Samin between Samin and Sarah Mohepath"));
        assertEquals("Anna M Spadaccini · Monday 1-1",
                AgendaTitle.compact("Monday 1-1 with Samin between Samin and Anna M Spadaccini"));
        assertEquals("Bob · 30 Min Meeting", AgendaTitle.compact("30 Min Meeting between Samin Yasar and Bob"));
    }

    @Test public void otherTitlesStayAsTheyAre() {
        assertEquals("🚀 7FCEO Mastermind Session", AgendaTitle.compact(" 🚀 7FCEO Mastermind Session "));
        assertEquals("samin and Joseph Tsar", AgendaTitle.compact("samin and Joseph Tsar"));
        assertEquals("Buffer between calls", AgendaTitle.compact("Buffer between calls"));
        assertEquals("Choose between the blue and green deck for the launch campaign review",
                AgendaTitle.compact("Choose between the blue and green deck for the launch campaign review"));
        assertEquals("", AgendaTitle.compact(null));
    }
}
