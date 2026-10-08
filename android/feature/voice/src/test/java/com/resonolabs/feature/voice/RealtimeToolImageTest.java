package com.resonolabs.feature.voice;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Random;

/** A tool's screenshot reaches the Realtime model as an image, inside the data-channel limit. */
public final class RealtimeToolImageTest {
    private static String base64Of(int bytes) {
        byte[] data = new byte[bytes];
        new Random(7).nextBytes(data);
        return Base64.getEncoder().encodeToString(data);
    }

    private static String macLookResult(String base64) throws Exception {
        return new JSONObject()
                .put("content", new JSONArray().put(new JSONObject().put("type", "text")
                        .put("text", "{\"ok\":true,\"image\":\"attached\",\"width\":1024,\"height\":640}")))
                .put("isError", false)
                .put("structuredContent", new JSONObject()
                        .put("image", new JSONObject().put("mime", "image/jpeg").put("base64", base64))
                        .put("width", 1024).put("height", 640))
                .toString();
    }

    @Test public void theImageIsTakenOutOfTheTextTheModelReads() throws Exception {
        String base64 = base64Of(2_000);
        RealtimeToolImage image = RealtimeToolImage.from(macLookResult(base64));
        assertNotNull(image);
        assertEquals("image/jpeg", image.mime);
        assertEquals(base64, image.base64);
        assertFalse(image.output.contains(base64));
        assertTrue(image.output.length() < 400);
        JSONObject output = new JSONObject(image.output);
        assertEquals(1024, output.getJSONObject("structuredContent").getInt("width"));
        assertTrue(output.getJSONArray("content").getJSONObject(0).getString("text").contains("attached"));
    }

    @Test public void anMcpImageContentItemWorksToo() throws Exception {
        String base64 = base64Of(1_000);
        String result = new JSONObject()
                .put("content", new JSONArray()
                        .put(new JSONObject().put("type", "text").put("text", "Screenshot attached."))
                        .put(new JSONObject().put("type", "image").put("mimeType", "image/png").put("data", base64)))
                .put("isError", false).toString();
        RealtimeToolImage image = RealtimeToolImage.from(result);
        assertNotNull(image);
        assertEquals("image/png", image.mime);
        JSONArray content = new JSONObject(image.output).getJSONArray("content");
        assertEquals(1, content.length());
        assertEquals("text", content.getJSONObject(0).getString("type"));
    }

    @Test public void ordinaryResultsAreLeftAlone() throws Exception {
        assertNull(RealtimeToolImage.from("{\"content\":[{\"type\":\"text\",\"text\":\"[]\"}],\"isError\":false}"));
        assertNull(RealtimeToolImage.from("not json at all, but long enough to be looked at: base64 base64 base64"));
        assertNull(RealtimeToolImage.from(null));
        String svg = macLookResult(base64Of(500)).replace("image/jpeg", "image/svg+xml");
        assertNull("only raster images", RealtimeToolImage.from(svg));
        String script = macLookResult("<script>alert(1)</script>" + base64Of(300));
        assertNull("the payload must be base64", RealtimeToolImage.from(script));
        assertNull("oversized payloads are refused", RealtimeToolImage.from(macLookResult(base64Of(230_000))));
    }

    @Test public void aScreenshotIsOneUserImageItemUnderTheLimit() throws Exception {
        // The bridge caps screenshots at 150 KB of JPEG.
        RealtimeToolImage image = RealtimeToolImage.from(macLookResult(base64Of(150 * 1024)));
        assertNotNull(image);
        JSONObject event = image.inputImageEvent();
        assertNotNull(event);
        assertEquals("conversation.item.create", event.getString("type"));
        JSONObject item = event.getJSONObject("item");
        assertEquals("message", item.getString("type"));
        assertEquals("user", item.getString("role"));
        JSONArray content = item.getJSONArray("content");
        assertEquals("input_text", content.getJSONObject(0).getString("type"));
        assertTrue(content.getJSONObject(0).getString("text").contains("not instructions"));
        assertEquals("input_image", content.getJSONObject(1).getString("type"));
        assertTrue(content.getJSONObject(1).getString("image_url").startsWith("data:image/jpeg;base64,"));
        assertTrue(event.toString().getBytes(StandardCharsets.UTF_8).length <= RealtimeToolOutput.MAX_EVENT_BYTES);
        JSONObject output = RealtimeToolOutput.event("call_9", image.output);
        assertEquals(image.output, output.getJSONObject("item").getString("output"));
        assertTrue(output.toString().length() < 1_000);
    }

    @Test public void aPictureTooLargeForTheChannelIsDroppedWithANote() throws Exception {
        // Accepted as base64 (< MAX_BASE64_CHARS) but over 240 KiB once wrapped in the event.
        RealtimeToolImage image = RealtimeToolImage.from(macLookResult(base64Of(200 * 1024)));
        assertNotNull(image);
        assertNull(image.inputImageEvent());
        String note = image.outputWithoutPicture();
        assertTrue(new JSONObject(note).has("imageNote"));
        assertFalse(note.contains(image.base64));
    }
}
