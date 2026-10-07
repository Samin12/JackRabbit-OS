package com.resonolabs.feature.voice;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.nio.charset.StandardCharsets;

/** Tool results sent back over the Realtime data channel stay under its message limit. */
public final class RealtimeToolOutputTest {
    @Test public void normalOutputIsSentAsIs() throws Exception {
        String output = "{\"content\":[{\"type\":\"text\",\"text\":\"[]\"}],\"isError\":false}";
        JSONObject event = RealtimeToolOutput.event("call_1", output);
        assertEquals("conversation.item.create", event.getString("type"));
        JSONObject item = event.getJSONObject("item");
        assertEquals("function_call_output", item.getString("type"));
        assertEquals("call_1", item.getString("call_id"));
        assertEquals(output, item.getString("output"));
    }

    @Test public void oversizedOutputBecomesAShortErrorForTheModel() throws Exception {
        // ~300 KB once escaped: libwebrtc would close the data channel (256 KiB limit).
        String big = "{\"text\":\"" + "\\\"quoted\\\" ".repeat(25_000) + "\"}";
        JSONObject event = RealtimeToolOutput.event("call_2", big);
        assertEquals("call_2", event.getJSONObject("item").getString("call_id"));
        assertEquals(RealtimeToolOutput.TOO_LARGE, event.getJSONObject("item").getString("output"));
        assertTrue(new JSONObject(RealtimeToolOutput.TOO_LARGE).getBoolean("isError"));
    }

    @Test public void theLimitCountsTheEscapedEventInUtf8Bytes() throws Exception {
        // Just under the limit as raw text, over it once quotes are escaped inside the event.
        String quotes = "\"".repeat(RealtimeToolOutput.MAX_EVENT_BYTES - 1_000);
        JSONObject event = RealtimeToolOutput.event("c", quotes);
        assertEquals(RealtimeToolOutput.TOO_LARGE, event.getJSONObject("item").getString("output"));
        String fits = "é".repeat((RealtimeToolOutput.MAX_EVENT_BYTES - 1_000) / 2);
        JSONObject kept = RealtimeToolOutput.event("c", fits);
        assertEquals(fits, kept.getJSONObject("item").getString("output"));
        assertTrue(kept.toString().getBytes(StandardCharsets.UTF_8).length <= RealtimeToolOutput.MAX_EVENT_BYTES);
    }
}
