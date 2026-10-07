package com.resonolabs.feature.voice;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;

/**
 * The {@code function_call_output} event for a tool result, bounded to what the Realtime data
 * channel can carry. libwebrtc refuses a data-channel message over 256 KiB and closes the
 * channel, which would end (or, with always-on, reconnect) the whole session. A larger result
 * is replaced by a short error the model can act on (ask for less) instead.
 */
final class RealtimeToolOutput {
    /** Below libwebrtc's 256 KiB send limit, with room to spare. */
    static final int MAX_EVENT_BYTES = 240 * 1024;
    static final String TOO_LARGE =
            "{\"isError\":true,\"message\":\"The result was too large to read out. Ask for less: "
                    + "fewer items (a smaller limit) or a narrower request.\"}";

    private RealtimeToolOutput() {}

    static JSONObject event(String callId, String output) throws Exception {
        JSONObject event = build(callId, output);
        if (event.toString().getBytes(StandardCharsets.UTF_8).length <= MAX_EVENT_BYTES) return event;
        return build(callId, TOO_LARGE);
    }

    private static JSONObject build(String callId, String output) throws Exception {
        return new JSONObject()
                .put("type", "conversation.item.create")
                .put("item", new JSONObject()
                        .put("type", "function_call_output")
                        .put("call_id", callId)
                        .put("output", output));
    }
}
