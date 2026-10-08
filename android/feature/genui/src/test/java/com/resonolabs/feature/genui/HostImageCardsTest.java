package com.resonolabs.feature.genui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * Host-only picture cards: the image block exists only on the trusted (app-built) path, the
 * model can neither create nor change it, and the cards persist by ref.
 */
public final class HostImageCardsTest {
    private static final String REF = "sha256:" + "0123456789abcdef".repeat(4);

    private GenTestSupport.FakeClock clock;
    private RecordingHost host;
    private GenCardStore store;
    private GenUiController controller;

    static final class RecordingHost extends GenTestSupportHost {
        final List<String> openedImages = new ArrayList<>();

        @Override public void openImage(String ref, String caption) {
            openedImages.add(ref + "|" + caption);
        }
    }

    /** FakeHost cannot be extended (final); a minimal host for these tests. */
    abstract static class GenTestSupportHost implements GenUiController.Host {
        @Override public boolean sendUserText(String text) { return true; }
        @Override public boolean sendSystemNote(String text, boolean respond) { return true; }
        @Override public void open(String page) { }
        @Override public void startSessionWith(String text) { }
        @Override public void invalidateUi() { }
        @Override public void setImmersive(boolean immersive) { }
    }

    @Before public void setUp() {
        clock = new GenTestSupport.FakeClock();
        host = new RecordingHost();
        store = GenCardStore.inMemory(clock, null);
        controller = new GenUiController(store, null, host);
        controller.onSessionStarted("voice-1");
    }

    private static JSONObject imageBlock() throws Exception {
        return new JSONObject().put("type", "image").put("ref", REF).put("alt", "Weekly chart").put("aspect", 0.66);
    }

    @Test public void refsAndSampleSizes() {
        assertTrue(GenImages.validRef(REF));
        assertFalse(GenImages.validRef("sha256:ABC"));
        assertFalse(GenImages.validRef("sha256:" + "G".repeat(64)));
        assertFalse(GenImages.validRef("md5:" + "0".repeat(64)));
        assertFalse(GenImages.validRef(null));
        assertEquals(1, GenImages.sampleSize(480, 360, 480, 480));
        assertEquals(2, GenImages.sampleSize(960, 720, 480, 480));
        assertEquals(2, GenImages.sampleSize(1024, 640, 480, 480));
        assertEquals(4, GenImages.sampleSize(2048, 1536, 480, 480));
        assertEquals(1, GenImages.sampleSize(0, 0, 480, 480));
        // never decodes smaller than the shown size: 1000 -> fit 480 needs >= 480 px, so 2 (500)
        assertEquals(2, GenImages.sampleSize(1000, 100, 480, 480));
    }

    @Test public void theModelCannotShowAPicture() throws Exception {
        JSONObject card = new JSONObject().put("id", "x").put("title", "X")
                .put("body", new org.json.JSONArray().put(imageBlock()));
        String output = controller.execute(GenUiTools.SHOW_CARD, card.toString(), clock.elapsed());
        assertTrue(output, output.contains("\"ok\":true"));
        assertTrue(output, output.contains("dropped (unknown type 'image')"));
        assertTrue(store.find("x").body.isEmpty());
    }

    @Test public void theHostCanAndItPersistsByRef() throws Exception {
        JSONObject json = HostImageCards.cardJson(HostImageCards.GENERATED_UI, REF, 0.66f, "Weekly focus hours",
                "Bar chart of focus hours per day", "art_42", false);
        GenCard card = controller.showHostCard(json);
        assertNotNull(card);
        assertEquals("ui-art_42", card.id);
        GenBlock image = card.body.get(0);
        assertEquals(GenBlock.Type.IMAGE, image.type);
        assertEquals(REF, image.ref);
        assertEquals("Weekly focus hours", image.alt);
        assertEquals(0.66f, image.aspect, 0.001f);
        JSONObject saved = GenCardCodec.cardToJson(card, 0L);
        JSONObject block = saved.getJSONArray("body").getJSONObject(0);
        assertEquals("image", block.getString("type"));
        assertEquals(REF, block.getString("ref"));
        assertFalse(saved.toString().contains("base64"));
        GenCard restored = GenCardCodec.cardFromJson(saved, 0L, clock.elapsed());
        assertEquals(REF, restored.body.get(0).ref);
    }

    @Test public void theModelCannotUpdateOrPatchAPictureCard() throws Exception {
        controller.showHostCard(HostImageCards.cardJson(HostImageCards.CAMERA, REF, 0.75f, "Photo", "Sent", null, false));
        String id = HostImageCards.cardId(HostImageCards.CAMERA, REF, null);
        String output = controller.execute(GenUiTools.UPDATE_CARD,
                "{\"id\":\"" + id + "\",\"title\":\"Hacked\"}", clock.elapsed());
        assertTrue(output, output.startsWith("{\"ok\":false"));
        assertEquals("Photo", store.find(id).title);

        // A model card's block cannot be patched into a picture either.
        controller.execute(GenUiTools.SHOW_CARD, "{\"id\":\"notes\",\"title\":\"Notes\",\"body\":"
                + "[{\"type\":\"text\",\"id\":\"t\",\"text\":\"hello\"}]}", clock.elapsed());
        String patched = controller.execute(GenUiTools.UPDATE_CARD, "{\"id\":\"notes\",\"patch\":"
                + "[{\"id\":\"t\",\"type\":\"image\",\"ref\":\"" + REF + "\"}]}", clock.elapsed());
        assertTrue(patched, patched.contains("\"ok\":true"));
        assertEquals(GenBlock.Type.TEXT, store.find("notes").body.get(0).type);
        String appended = controller.execute(GenUiTools.UPDATE_CARD, "{\"id\":\"notes\",\"append\":"
                + "[{\"type\":\"image\",\"ref\":\"" + REF + "\"}]}", clock.elapsed());
        assertTrue(appended, appended.contains("\"ok\":true"));
        assertEquals(1, store.find("notes").body.size());
    }

    @Test public void invalidRefsAreDropped() throws Exception {
        JSONObject json = new JSONObject().put("id", "p").put("title", "P")
                .put("body", new org.json.JSONArray().put(new JSONObject().put("type", "image").put("ref", "file:///x")));
        GenCard card = controller.showHostCard(json);
        assertTrue(card.body.isEmpty());
    }

    @Test public void cardShapes() throws Exception {
        JSONObject generated = HostImageCards.cardJson(HostImageCards.GENERATED_UI, REF, 0.5f, "Sales by region",
                "Bars for four regions", "art-1", true);
        assertEquals("New: Sales by region", generated.getString("title"));
        assertEquals("violet", generated.getString("accent"));
        assertEquals("Bars for four regions", generated.getString("subtitle"));
        assertEquals(HostImageCards.GENERATED_TTL_SEC, generated.getInt("ttlSec"));
        JSONObject live = HostImageCards.cardJson(HostImageCards.GENERATED_UI, REF, 0.5f, "Sales by region",
                "", "art-1", false);
        assertEquals("Sales by region", live.getString("title"));
        assertFalse(live.has("subtitle"));
        // Without a picture a generated UI still gets a card that says where it is.
        JSONObject missing = HostImageCards.cardJson(HostImageCards.GENERATED_UI, null, 0.5f, "Sales", "", "art-2", false);
        assertEquals("text", missing.getJSONArray("body").getJSONObject(0).getString("type"));
        assertNull(HostImageCards.cardJson(HostImageCards.CAMERA, null, 0.75f, "Photo", "", null, false));
        JSONObject screen = HostImageCards.cardJson(HostImageCards.MAC_SCREENSHOT, REF, 0.62f, "", "", null, false);
        assertEquals("Your Mac", screen.getString("title"));
        assertEquals("screen-0123456789ab", screen.getString("id"));
        assertEquals(3f, HostImageCards.clampAspect(9f), 0f);
        assertEquals(0.75f, HostImageCards.clampAspect(Float.NaN), 0f);
    }

    @Test public void imageBoxesAreSizedAndTappable() throws Exception {
        GenCard card = controller.showHostCard(HostImageCards.cardJson(HostImageCards.GENERATED_UI, REF, 0.66f,
                "Weekly focus hours", "Bars per day", "art_7", false));
        float inner = GenCardOverlay.CARD_RIGHT - GenCardOverlay.CARD_LEFT - 2f * GenCardLayout.PAD_X;
        assertEquals(212f, GenCardLayout.imageHeight(0.66f, inner, false), 0.01f);
        assertEquals(inner * 0.66f, GenCardLayout.imageHeight(0.66f, inner, true), 0.01f);
        assertEquals(GenCardLayout.IMAGE_MIN_H, GenCardLayout.imageHeight(0.1f, inner, false), 0.01f);
        assertEquals(GenCardLayout.IMAGE_EXPANDED_MAX_H, GenCardLayout.imageHeight(3f, inner, true), 0.01f);
        assertEquals("Weekly focus hours", GenCardLayout.summary(card));
        assertEquals(card.body.get(0), GenCardLayout.firstImage(card));
        controller.onImageTapped(card, card.body.get(0));
        assertEquals(REF + "|Weekly focus hours: Bars per day", host.openedImages.get(0));
        GenCard photo = controller.showHostCard(HostImageCards.cardJson(HostImageCards.CAMERA, REF, 0.75f,
                "Photo", "You sent this photo", null, false));
        assertEquals("You sent this photo", GenUiController.imageCaption(photo, photo.body.get(0)));
        assertTrue(GenUiController.describe(card).contains("picture (Weekly focus hours)"));
    }

    @Test public void dismissAllReportsEachCard() {
        List<String> dismissed = new ArrayList<>();
        store.addListener(new GenCardStore.Listener() {
            @Override public void onCardsChanged() { }

            @Override public void onCardEvent(int event, GenCard card) {
                if (event == GenCardStore.EVENT_DISMISSED) dismissed.add(card.id);
            }
        });
        controller.execute(GenUiTools.SHOW_CARD, "{\"id\":\"a\",\"title\":\"A\"}", clock.elapsed());
        controller.execute(GenUiTools.SHOW_CARD, "{\"id\":\"b\",\"title\":\"B\"}", clock.elapsed());
        controller.execute(GenUiTools.DISMISS_CARD, "{\"all\":true}", clock.elapsed());
        assertEquals(2, dismissed.size());
        assertTrue(dismissed.contains("a") && dismissed.contains("b"));
    }
}
