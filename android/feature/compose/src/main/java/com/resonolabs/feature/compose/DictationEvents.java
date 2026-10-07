package com.resonolabs.feature.compose;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Routes Realtime data-channel events of a dictation call into the {@link DictationTranscript}
 * and the {@link DictationMachine}. Works for both session shapes the runtime may open (a
 * transcription session or a Realtime session that never answers): both emit the same input
 * audio buffer and input transcription events.
 */
public final class DictationEvents {
    public enum Outcome {
        IGNORED,
        /** VAD speech start/stop: the listening visual may change. */
        SPEECH,
        /** The dictated text changed. */
        TEXT,
        /** Something heard became an item; no text yet. */
        COMMITTED,
        /** The model started a reply it should never give: cancel it. */
        RESPONSE_STARTED,
        /** A harmless error (e.g. committing an empty buffer while winding down). */
        BENIGN_ERROR,
        /** A real error; see {@link #lastError()}. */
        ERROR
    }

    private String lastError = "";

    public String lastError() {
        return lastError;
    }

    public Outcome apply(String json, DictationTranscript transcript, DictationMachine machine, long now) {
        JSONObject event;
        try {
            event = new JSONObject(json);
        } catch (JSONException | RuntimeException invalid) {
            return Outcome.IGNORED;
        }
        String type = event.optString("type", "");
        String itemId = event.optString("item_id", "");
        switch (type) {
            case "input_audio_buffer.speech_started" -> {
                machine.onSpeechStarted(now);
                transcript.onSpeechStarted(itemId);
                return Outcome.SPEECH;
            }
            case "input_audio_buffer.speech_stopped" -> {
                machine.onSpeechStopped(now);
                return Outcome.SPEECH;
            }
            case "input_audio_buffer.committed" -> {
                machine.onCommitted(now);
                transcript.onCommitted(itemId, optional(event, "previous_item_id"));
                return Outcome.COMMITTED;
            }
            case "conversation.item.input_audio_transcription.delta" -> {
                return transcript.onDelta(itemId, event.optString("delta", "")) ? Outcome.TEXT : Outcome.IGNORED;
            }
            case "conversation.item.input_audio_transcription.completed" -> {
                transcript.onCompleted(itemId, event.optString("transcript", ""));
                return Outcome.TEXT;
            }
            case "conversation.item.input_audio_transcription.failed" -> {
                transcript.onFailed(itemId);
                JSONObject error = event.optJSONObject("error");
                lastError = error == null ? "transcription-failed" : error.optString("message", "transcription-failed");
                return Outcome.TEXT;
            }
            case "response.created" -> {
                return Outcome.RESPONSE_STARTED;
            }
            case "error" -> {
                JSONObject error = event.optJSONObject("error");
                String code = error == null ? "" : error.optString("code", "");
                lastError = error == null ? "error" : error.optString("message", code.isEmpty() ? "error" : code);
                return isBenign(code) ? Outcome.BENIGN_ERROR : Outcome.ERROR;
            }
            default -> {
                return Outcome.IGNORED;
            }
        }
    }

    static boolean isBenign(String code) {
        return switch (code) {
            case "input_audio_buffer_commit_empty", "response_cancel_not_active",
                    "conversation_already_has_active_response" -> true;
            default -> false;
        };
    }

    private static String optional(JSONObject event, String key) {
        return event.isNull(key) ? null : event.optString(key, null);
    }
}
