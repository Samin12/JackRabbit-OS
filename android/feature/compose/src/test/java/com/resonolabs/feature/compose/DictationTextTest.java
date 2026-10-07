package com.resonolabs.feature.compose;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class DictationTextTest {
    @Test public void emptyFieldTakesTheWordsAsSaid() {
        DictationText.Splice splice = DictationText.splice("", "Remember to call Devin tomorrow.", "", 0);
        assertEquals("Remember to call Devin tomorrow.", splice.text);
        assertEquals(splice.text.length(), splice.cursor);
        assertEquals(0, splice.start);
        assertEquals(splice.text.length(), splice.end);
    }

    @Test public void appendsAfterTypedTextWithOneSpace() {
        DictationText.Splice splice = DictationText.splice("Groceries:", "milk and eggs", "", 0);
        assertEquals("Groceries: milk and eggs", splice.text);
        assertEquals(splice.text.length(), splice.cursor);
    }

    @Test public void noDoubleSpaceWhenTypedTextEndsWithSpaceOrNewline() {
        assertEquals("Note: hi", DictationText.splice("Note: ", "hi", "", 0).text);
        assertEquals("Line one\nHi", DictationText.splice("Line one\n", "Hi", "", 0).text);
    }

    @Test public void insertsAtTheCursorBetweenTypedText() {
        DictationText.Splice splice = DictationText.splice("Call", "Devin", "tomorrow.", 0);
        assertEquals("Call Devin tomorrow.", splice.text);
        assertEquals("Call Devin".length(), splice.cursor);
        assertEquals("Devin", splice.text.substring(splice.start, splice.end));
    }

    @Test public void punctuationAndBracketsDoNotGetStraySpaces() {
        assertEquals("(note)", DictationText.splice("(", "note", ")", 0).text);
        assertEquals("Done.", DictationText.splice("Done", ".", "", 0).text);
        assertEquals("Ask him, please", DictationText.splice("Ask him", ", please", "", 0).text);
        assertEquals("Say hi.", DictationText.splice("Say", "hi", ".", 0).text);
    }

    @Test public void wordsAreTrimmedButNeverRewritten() {
        DictationText.Splice splice = DictationText.splice("", "  iPhone NASA i'm  ", "", 0);
        assertEquals("iPhone NASA i'm", splice.text);
    }

    @Test public void nothingDictatedLeavesTheTextAlone() {
        DictationText.Splice splice = DictationText.splice("Typed ", "   ", "text", 0);
        assertEquals("Typed text", splice.text);
        assertEquals("Typed ".length(), splice.cursor);
        assertFalse(splice.clipped);
    }

    @Test public void limitCutsTheDictationNotTheTypedText() {
        DictationText.Splice splice = DictationText.splice("Hello", "one two three four", "", 15);
        assertTrue(splice.clipped);
        assertTrue(splice.text.length() <= 15);
        assertTrue(splice.text.startsWith("Hello "));
        assertEquals("Hello one two", splice.text);
    }

    @Test public void fullFieldTakesNoWords() {
        DictationText.Splice splice = DictationText.splice("12345", "more", "", 5);
        assertTrue(splice.clipped);
        assertEquals("12345", splice.text);
        assertEquals(5, splice.cursor);
    }

    @Test public void counterShowsNearTheLimit() {
        assertFalse(DictationText.nearLimit(10, 0));
        assertFalse(DictationText.nearLimit(79, 100));
        assertTrue(DictationText.nearLimit(80, 100));
        assertEquals(Integer.MAX_VALUE, DictationText.remaining(10, 0));
        assertEquals(0, DictationText.remaining(12, 10));
    }
}
