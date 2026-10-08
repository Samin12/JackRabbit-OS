package com.resonolabs.feature.voice;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class AssistantSpeechTrackerTest {
    private static final VoiceSessionStateTracker.State IDLE = VoiceSessionStateTracker.State.IDLE;
    private static final VoiceSessionStateTracker.State LIVE = VoiceSessionStateTracker.State.LIVE;
    private static final VoiceSessionStateTracker.State RESPONDING = VoiceSessionStateTracker.State.RESPONDING;
    private static final VoiceSessionStateTracker.State ERROR = VoiceSessionStateTracker.State.ERROR;

    @Test public void withoutBufferEventsAReplySpeaksFromItsFirstAudioUntilItIsDone() {
        AssistantSpeechTracker speech = new AssistantSpeechTracker();
        assertFalse(speech.speaking(LIVE));
        speech.onRealtimeEvent("response.created");
        assertFalse("thinking is not speaking", speech.speaking(RESPONDING));
        speech.onRealtimeEvent("response.output_audio_transcript.delta");
        assertTrue(speech.speaking(RESPONDING));
        assertFalse("response.done: the page is LIVE again", speech.speaking(LIVE));
        speech.onRealtimeEvent("response.created");
        assertFalse(speech.speaking(RESPONDING));
        speech.onRealtimeEvent("response.audio.delta");
        assertTrue(speech.speaking(RESPONDING));
    }

    @Test public void bufferEventsDecideOnceSeenIncludingAudioDrainingAfterTheResponse() {
        AssistantSpeechTracker speech = new AssistantSpeechTracker();
        speech.onRealtimeEvent("response.created");
        speech.onRealtimeEvent("output_audio_buffer.started");
        speech.onRealtimeEvent("response.output_audio_transcript.delta");
        assertTrue(speech.speaking(RESPONDING));
        assertTrue("still playing after response.done", speech.speaking(LIVE));
        speech.onRealtimeEvent("output_audio_buffer.stopped");
        assertFalse(speech.speaking(LIVE));
        // next reply: transcript deltas alone no longer count, the buffer does
        speech.onRealtimeEvent("response.created");
        speech.onRealtimeEvent("response.output_audio_transcript.delta");
        assertFalse(speech.speaking(RESPONDING));
        speech.onRealtimeEvent("output_audio_buffer.started");
        assertTrue(speech.speaking(RESPONDING));
        speech.onRealtimeEvent("output_audio_buffer.cleared");
        assertFalse(speech.speaking(RESPONDING));
    }

    @Test public void interruptAndSessionEndsSilenceIt() {
        AssistantSpeechTracker speech = new AssistantSpeechTracker();
        speech.onRealtimeEvent("output_audio_buffer.started");
        speech.interrupted();
        assertFalse(speech.speaking(LIVE));
        speech.onRealtimeEvent("output_audio_buffer.started");
        assertFalse("no session, no mouth", speech.speaking(IDLE));
        assertFalse(speech.speaking(ERROR));
        speech.reset();
        speech.onRealtimeEvent("response.created");
        speech.onRealtimeEvent("response.output_audio_transcript.delta");
        assertTrue("reset forgets the buffer events", speech.speaking(RESPONDING));
    }
}
