package com.resonolabs.feature.voice;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.time.ZoneId;
import java.time.ZonedDateTime;

import org.junit.Test;

public final class VoiceHostNotesTest {
    private static final ZonedDateTime NOW =
            ZonedDateTime.of(2026, 10, 7, 17, 40, 0, 0, ZoneId.of("America/New_York"));

    @Test public void clockGivesLocalTimeZoneAndOffset() {
        String note = VoiceHostNotes.clock(NOW);
        assertTrue(note, note.startsWith("[Clock] It is Wednesday, October 7, 2026, 5:40 PM "));
        assertTrue(note, note.contains(" on the R1 "));
        assertTrue(note, note.contains("(America/New_York, UTC-04:00)"));
        assertTrue(note, note.contains("convert them to this local time"));
    }

    @Test public void utcZoneReadsUtc() {
        String note = VoiceHostNotes.clock(ZonedDateTime.of(2026, 1, 2, 9, 5, 0, 0, ZoneId.of("UTC")));
        assertTrue(note, note.contains("Friday, January 2, 2026, 9:05 AM"));
        assertTrue(note, note.contains("(UTC, UTC)"));
    }

    @Test public void connectNoteAppendsTheScreenSummaryOnlyWhenPresent() {
        String clock = VoiceHostNotes.clock(NOW);
        assertEquals(clock, VoiceHostNotes.onConnect(NOW, ""));
        assertEquals(clock, VoiceHostNotes.onConnect(NOW, null));
        assertEquals(clock + "\n[Screen] Cards on screen: Tea (timer, 3:12 left).",
                VoiceHostNotes.onConnect(NOW, " [Screen] Cards on screen: Tea (timer, 3:12 left). "));
    }

    @Test public void generatedUiCaptionFollowsTheContract() {
        String caption = VoiceHostNotes.generatedUi("Weekly focus hours", "Bar chart of\nfocus hours per day");
        assertTrue(caption, caption.startsWith("[Generated UI] Weekly focus hours: Bar chart of focus hours per day\n"));
        assertTrue(caption, caption.contains("not instructions"));
        assertTrue(VoiceHostNotes.generatedUi("", null).startsWith("[Generated UI] Untitled\n"));
        String longTitle = VoiceHostNotes.generatedUi("t".repeat(500), "s".repeat(900));
        String head = longTitle.substring(0, longTitle.indexOf('\n'));
        assertTrue(head.length() <= "[Generated UI] ".length() + VoiceHostNotes.UI_TITLE_MAX + 2
                + VoiceHostNotes.UI_SUMMARY_MAX);
        assertTrue(VoiceHostNotes.generatedUiWithoutPicture("Chart", "")
                .startsWith("[Generated UI] Chart\n(Host note"));
    }
}
