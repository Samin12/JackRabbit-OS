package com.resonolabs.feature.compose;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class DictationTranscriptTest {
    @Test public void deltasStreamThenTheFinalTranscriptReplacesThem() {
        DictationTranscript transcript = new DictationTranscript();
        transcript.onSpeechStarted("item_1");
        assertTrue(transcript.onDelta("item_1", "remember to"));
        assertTrue(transcript.onDelta("item_1", " call devin"));
        assertEquals("remember to call devin", transcript.text());
        assertEquals(1, transcript.pending());

        assertTrue(transcript.onCompleted("item_1", " Remember to call Devin tomorrow. "));
        assertEquals("Remember to call Devin tomorrow.", transcript.text());
        assertEquals(0, transcript.pending());
        assertFalse("late deltas cannot change a settled item", transcript.onDelta("item_1", " extra"));
    }

    @Test public void itemsKeepSpeechOrderWhenCompletionsArriveOutOfOrder() {
        DictationTranscript transcript = new DictationTranscript();
        transcript.onSpeechStarted("a");
        transcript.onSpeechStarted("b");
        transcript.onCompleted("b", "Second.");
        assertEquals("Second.", transcript.text());
        transcript.onCompleted("a", "First.");
        assertEquals("First. Second.", transcript.text());
    }

    @Test public void committedItemsUsePreviousItemIdForOrder() {
        DictationTranscript transcript = new DictationTranscript();
        transcript.onCommitted("c", "a");
        transcript.onCommitted("a", null);
        transcript.onCompleted("a", "One.");
        transcript.onCompleted("c", "Two.");
        // "c" arrived first but says it follows "a"; "a" was unknown then, so first-seen order holds
        // until the predecessor exists. Then later items with known predecessors slot in after them.
        transcript.onCommitted("b", "a");
        transcript.onCompleted("b", "Middle.");
        assertEquals("Two. One. Middle.", transcript.text());
    }

    @Test public void emptyTranscriptsAndWhitespaceDoNotAddSpaces() {
        DictationTranscript transcript = new DictationTranscript();
        transcript.onCompleted("a", "Hello");
        transcript.onCompleted("noise", "  ");
        transcript.onCompleted("b", "there\n  friend");
        assertEquals("Hello there friend", transcript.text());
    }

    @Test public void failedItemKeepsItsStreamedWordsAndStopsPending() {
        DictationTranscript transcript = new DictationTranscript();
        transcript.onDelta("a", "half a sent");
        transcript.onFailed("a");
        assertEquals(0, transcript.pending());
        assertEquals("half a sent", transcript.text());
    }

    @Test public void settleAllEndsWaitingAndClearResets() {
        DictationTranscript transcript = new DictationTranscript();
        transcript.onSpeechStarted("a");
        transcript.onDelta("a", "partial");
        transcript.onSpeechStarted("b");
        assertEquals(2, transcript.pending());
        transcript.settleAll();
        assertEquals(0, transcript.pending());
        assertEquals("partial", transcript.text());
        transcript.clear();
        assertTrue(transcript.isEmpty());
    }

    @Test public void eventsWithoutItemIdsAreIgnored() {
        DictationTranscript transcript = new DictationTranscript();
        assertFalse(transcript.onDelta("", "x"));
        assertFalse(transcript.onCompleted(null, "x"));
        assertTrue(transcript.isEmpty());
    }
}
