package com.resonolabs.feature.voice;

/**
 * Whether the assistant's voice is coming out right now (the Pixel head moves its mouth then).
 *
 * <p>Over WebRTC the provider brackets playback with {@code output_audio_buffer.started} and
 * {@code .stopped} ({@code .cleared} after an interrupt), which also covers audio that is still
 * draining after {@code response.done}. Until such an event arrives in a session, a reply counts
 * as speaking from its first audio (or audio transcript) delta while the page is RESPONDING.
 * The "thinking" part of RESPONDING (after the user stops, before audio) is never speaking.
 */
final class AssistantSpeechTracker {
    /** The provider sends output_audio_buffer events in this session: trust them alone. */
    private boolean bufferEvents;
    private boolean playing;
    /** The current response has produced audio (fallback without buffer events). */
    private boolean responseAudio;

    void onRealtimeEvent(String type) {
        switch (type) {
            case "output_audio_buffer.started" -> {
                bufferEvents = true;
                playing = true;
            }
            case "output_audio_buffer.stopped", "output_audio_buffer.cleared" -> {
                bufferEvents = true;
                playing = false;
            }
            case "response.created" -> responseAudio = false;
            case "response.audio.delta", "response.output_audio.delta",
                    "response.audio_transcript.delta", "response.output_audio_transcript.delta" ->
                    responseAudio = true;
            default -> { }
        }
    }

    /** The user cut the reply off (the page also clears the provider's audio buffer). */
    void interrupted() {
        playing = false;
        responseAudio = false;
    }

    /** A session starts (or ends): forget everything. */
    void reset() {
        bufferEvents = false;
        playing = false;
        responseAudio = false;
    }

    boolean speaking(VoiceSessionStateTracker.State state) {
        if (state != VoiceSessionStateTracker.State.LIVE && state != VoiceSessionStateTracker.State.RESPONDING) {
            return false;
        }
        if (bufferEvents) return playing;
        return state == VoiceSessionStateTracker.State.RESPONDING && responseAudio;
    }
}
