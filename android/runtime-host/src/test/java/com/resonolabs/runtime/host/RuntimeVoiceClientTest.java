package com.resonolabs.runtime.host;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

public final class RuntimeVoiceClientTest {
    @Test public void dropsTheStructuredCopyWhenTextIsPresent() throws Exception {
        JSONObject result = new JSONObject("{\"content\":[{\"type\":\"text\",\"text\":\"[{\\\"title\\\":\\\"Dinner\\\"}]\"}],"
                + "\"isError\":false,\"structuredContent\":{\"result\":[{\"title\":\"Dinner\"}]}}");
        RuntimeVoiceClient.compactForModel(result);
        assertFalse(result.has("structuredContent"));
        assertEquals("[{\"title\":\"Dinner\"}]", result.getJSONArray("content").getJSONObject(0).getString("text"));
        assertFalse(result.getBoolean("isError"));
    }

    @Test public void keepsStructuredContentWithoutAText() throws Exception {
        JSONObject result = new JSONObject("{\"content\":[],\"structuredContent\":{\"result\":[]}}");
        RuntimeVoiceClient.compactForModel(result);
        assertTrue(result.has("structuredContent"));
        JSONObject image = new JSONObject("{\"content\":[{\"type\":\"image\",\"data\":\"x\"}],\"structuredContent\":{}}");
        RuntimeVoiceClient.compactForModel(image);
        assertTrue(image.has("structuredContent"));
    }

    @Test public void dropsAnUnwrappedCopyWithKeysInAnotherOrder() throws Exception {
        // web_search / journal / device status: text = json.dumps(value), structured = value.
        JSONObject result = new JSONObject("{\"content\":[{\"type\":\"text\",\"text\":"
                + "\"{\\\"b\\\":[1,2.0,{\\\"c\\\":null}],\\\"a\\\":\\\"x\\\"}\"}],"
                + "\"structuredContent\":{\"a\":\"x\",\"b\":[1,2,{\"c\":null}]}}");
        RuntimeVoiceClient.compactForModel(result);
        assertFalse(result.has("structuredContent"));
    }

    @Test public void keepsStructuredContentWhenTheTextIsOnlyASummary() throws Exception {
        // goal_inspect: the run's output exists only in structuredContent.
        JSONObject goal = new JSONObject("{\"content\":[{\"type\":\"text\",\"text\":\"Goal is completed.\"}],"
                + "\"structuredContent\":{\"runId\":\"r1\",\"state\":\"completed\",\"output\":\"The answer\"}}");
        RuntimeVoiceClient.compactForModel(goal);
        assertEquals("The answer", goal.getJSONObject("structuredContent").getString("output"));
        // JSON text that is not the same data (e.g. an MCP server's own summary object).
        JSONObject different = new JSONObject("{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"count\\\":2}\"}],"
                + "\"structuredContent\":{\"result\":[{\"title\":\"A\"},{\"title\":\"B\"}]}}");
        RuntimeVoiceClient.compactForModel(different);
        assertTrue(different.has("structuredContent"));
    }

    @Test public void responseCapFitsLargeCalendarListings() {
        // A real 38-event calendar listing was ~75 KB (text + structured copy).
        assertTrue(RuntimeVoiceClient.MAX_MCP_RESPONSE_BYTES >= 512 * 1024);
    }
}
