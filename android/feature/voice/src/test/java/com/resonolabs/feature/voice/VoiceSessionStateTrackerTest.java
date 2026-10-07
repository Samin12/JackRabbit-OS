package com.resonolabs.feature.voice;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class VoiceSessionStateTrackerTest {
    @Test public void responseCompletesBackToLive() {
        VoiceSessionStateTracker tracker = new VoiceSessionStateTracker();
        tracker.connecting();
        tracker.live();
        tracker.onRealtimeEvent("input_audio_buffer.speech_stopped");
        assertEquals(VoiceSessionStateTracker.State.RESPONDING, tracker.state());
        tracker.onRealtimeEvent("response.created");
        tracker.onRealtimeEvent("response.output_audio_transcript.done");
        assertEquals(VoiceSessionStateTracker.State.RESPONDING, tracker.state());
        tracker.onRealtimeEvent("response.done");
        assertEquals(VoiceSessionStateTracker.State.LIVE, tracker.state());
    }

    @Test public void toolAndFollowUpRemainRespondingUntilFollowUpDone() {
        VoiceSessionStateTracker tracker = new VoiceSessionStateTracker();
        tracker.live();
        tracker.onRealtimeEvent("response.function_call_arguments.done");
        tracker.onRealtimeEvent("response.done");
        assertEquals(VoiceSessionStateTracker.State.RESPONDING, tracker.state());
        tracker.toolOutputSent();
        tracker.onRealtimeEvent("response.done");
        assertEquals(VoiceSessionStateTracker.State.RESPONDING, tracker.state());
        tracker.onRealtimeEvent("response.created");
        tracker.onRealtimeEvent("response.done");
        assertEquals(VoiceSessionStateTracker.State.LIVE, tracker.state());
    }

    @Test public void resetClearsToolState() {
        VoiceSessionStateTracker tracker = new VoiceSessionStateTracker();
        tracker.onRealtimeEvent("response.function_call_arguments.done");
        tracker.error();
        tracker.connecting();
        tracker.live();
        tracker.onRealtimeEvent("response.done");
        assertEquals(VoiceSessionStateTracker.State.LIVE, tracker.state());
    }

    @Test public void localToolWithoutFollowUpReturnsToLiveAfterTheResponse() {
        VoiceSessionStateTracker tracker = new VoiceSessionStateTracker();
        tracker.live();
        tracker.onRealtimeEvent("response.created");
        tracker.onRealtimeEvent("response.function_call_arguments.done");
        // show_card answered on-device after the reply already spoke: no follow-up response.
        tracker.toolOutputSentWithoutFollowUp();
        assertEquals(VoiceSessionStateTracker.State.RESPONDING, tracker.state());
        tracker.onRealtimeEvent("response.done");
        assertEquals(VoiceSessionStateTracker.State.LIVE, tracker.state());
    }

    @Test public void lateLocalToolOutputAfterResponseDoneGoesLive() {
        VoiceSessionStateTracker tracker = new VoiceSessionStateTracker();
        tracker.live();
        tracker.onRealtimeEvent("response.created");
        tracker.onRealtimeEvent("response.function_call_arguments.done");
        tracker.onRealtimeEvent("response.done");
        assertEquals(VoiceSessionStateTracker.State.RESPONDING, tracker.state());
        tracker.toolOutputSentWithoutFollowUp();
        assertEquals(VoiceSessionStateTracker.State.LIVE, tracker.state());
    }
}
