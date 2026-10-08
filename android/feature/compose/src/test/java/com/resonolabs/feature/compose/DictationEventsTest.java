package com.resonolabs.feature.compose;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.resonolabs.feature.compose.DictationEvents.Outcome;

import org.junit.Test;

public class DictationEventsTest {
    private final DictationEvents events = new DictationEvents();
    private final DictationTranscript transcript = new DictationTranscript();
    private final DictationMachine machine = new DictationMachine();

    private Outcome apply(String json) {
        return events.apply(json, transcript, machine, 1_000L);
    }

    @Test public void fullUtteranceFromVadToFinalTranscript() {
        machine.start(0L);
        machine.onLive(100L);
        assertEquals(Outcome.SPEECH, apply("{\"type\":\"input_audio_buffer.speech_started\",\"item_id\":\"item_1\",\"audio_start_ms\":120}"));
        assertTrue(machine.speechActive());
        assertEquals(Outcome.TEXT, apply("{\"type\":\"conversation.item.input_audio_transcription.delta\",\"item_id\":\"item_1\",\"content_index\":0,\"delta\":\"Remember to\"}"));
        assertEquals("Remember to", transcript.text());
        assertEquals(Outcome.SPEECH, apply("{\"type\":\"input_audio_buffer.speech_stopped\",\"item_id\":\"item_1\"}"));
        assertFalse(machine.speechActive());
        assertEquals(Outcome.COMMITTED, apply("{\"type\":\"input_audio_buffer.committed\",\"item_id\":\"item_1\",\"previous_item_id\":null}"));
        assertEquals(1, transcript.pending());
        assertEquals(Outcome.TEXT, apply("{\"type\":\"conversation.item.input_audio_transcription.completed\",\"item_id\":\"item_1\",\"content_index\":0,\"transcript\":\"Remember to call Devin tomorrow.\"}"));
        assertEquals("Remember to call Devin tomorrow.", transcript.text());
        assertEquals(0, transcript.pending());
    }

    @Test public void failedTranscriptionReportsAndStopsWaiting() {
        apply("{\"type\":\"conversation.item.input_audio_transcription.delta\",\"item_id\":\"a\",\"delta\":\"hi\"}");
        assertEquals(Outcome.TEXT, apply("{\"type\":\"conversation.item.input_audio_transcription.failed\",\"item_id\":\"a\",\"error\":{\"message\":\"bad audio\"}}"));
        assertEquals(0, transcript.pending());
        assertEquals("bad audio", events.lastError());
    }

    @Test public void errorsAreSortedIntoBenignAndReal() {
        assertEquals(Outcome.BENIGN_ERROR, apply("{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\",\"code\":\"input_audio_buffer_commit_empty\",\"message\":\"buffer too small\"}}"));
        assertEquals(Outcome.ERROR, apply("{\"type\":\"error\",\"error\":{\"type\":\"server_error\",\"code\":\"session_expired\",\"message\":\"Session expired\"}}"));
        assertEquals("Session expired", events.lastError());
    }

    @Test public void aResponseIsFlaggedSoItCanBeCancelled() {
        assertEquals(Outcome.RESPONSE_STARTED, apply("{\"type\":\"response.created\",\"response\":{\"id\":\"r\"}}"));
    }

    @Test public void unknownAndInvalidEventsAreIgnored() {
        assertEquals(Outcome.IGNORED, apply("{\"type\":\"session.created\",\"session\":{}}"));
        assertEquals(Outcome.IGNORED, apply("not json"));
        assertEquals(Outcome.IGNORED, apply("{\"type\":\"conversation.item.input_audio_transcription.delta\",\"item_id\":\"a\",\"delta\":\"\"}"));
        assertTrue(transcript.isEmpty());
    }

    @Test public void failureCodesBecomeShortMessages() {
        assertEquals("Connect OpenAI in Settings to dictate.", DictationSession.describe("credential_unavailable:Connect ChatGPT first."));
        assertEquals("The assistant runtime isn't ready yet.", DictationSession.describe("runtime-unavailable"));
        assertEquals("Couldn't start dictation. Try again.", DictationSession.describe("provider_rejected:OpenAI could not start this session."));
        assertEquals("Couldn't start dictation. Try again.", DictationSession.describe(null));
    }
}
