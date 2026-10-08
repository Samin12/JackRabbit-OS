package com.resonolabs.feature.voice;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/** Transcript geometry with a monospace fake measurer (10 px per char, bold 11 px). */
public final class TranscriptLayoutTest {
    private static final String REF = "sha256:" + "ab".repeat(32);

    /** Counts measurements, so tests can prove clean items are not re-measured. */
    private static final class Mono implements TranscriptLayout.Measurer {
        int calls;

        private static float per(boolean bold) {
            return bold ? 11f : 10f;
        }

        @Override public float width(String text, float size, boolean bold) {
            calls++;
            return text.length() * per(bold);
        }

        @Override public int fit(String text, float size, boolean bold, float maxWidth) {
            calls++;
            return Math.min(text.length(), (int) Math.floor(maxWidth / per(bold)));
        }
    }

    @Test public void textHeights() {
        Mono mono = new Mono();
        List<TranscriptItem> items = new ArrayList<>();
        // 30 chars per user line (300 px), 40 per assistant line (404 px)
        items.add(TranscriptItem.text(TranscriptItem.USER, "short"));
        items.add(TranscriptItem.text(TranscriptItem.ASSISTANT, "word ".repeat(20).trim()));
        float total = TranscriptLayout.layout(items, mono);
        TranscriptItem user = items.get(0);
        assertEquals(1, user.lines.length);
        assertEquals(22f + 20f, user.height, 0f);
        assertEquals(50f + 28f, user.bubbleWidth, 0f);
        TranscriptItem model = items.get(1);
        assertEquals(3, model.lines.length);
        assertEquals(3 * 22f + 8f, model.height, 0f);
        assertEquals(0f, user.top, 0f);
        assertEquals(42f + 12f, model.top, 0f);
        assertEquals(42f + 12f + 74f + 12f, total, 0f);
    }

    @Test public void wrapBreaksAtSpaces() {
        String[] lines = TranscriptLayout.wrap("alpha beta gamma delta", 120f, new Mono());
        assertArrayEquals(new String[]{"alpha beta", "gamma delta"}, lines);
        assertArrayEquals(new String[]{"alpha", "beta", "gamma", "delta"},
                TranscriptLayout.wrap("alpha beta gamma delta", 90f, new Mono()));
        // a word longer than the line is cut, never dropped
        assertArrayEquals(new String[]{"abcdefghij", "klm"}, TranscriptLayout.wrap("abcdefghijklm", 100f, new Mono()));
        assertEquals(0, TranscriptLayout.wrap("   ", 100f, new Mono()).length);
    }

    @Test public void pictureFrames() {
        // 4:3 photo on the user side: 264 wide, 198 tall, plus the caption row
        TranscriptItem photo = TranscriptItem.image(TranscriptItem.USER, REF, 0.75f, "Photo", "camera");
        TranscriptLayout.measure(photo, new Mono());
        assertEquals(264f, photo.frameWidth, 0.01f);
        assertEquals(198f, photo.frameHeight, 0.01f);
        assertEquals(198f + TranscriptLayout.CAPTION_H, photo.height, 0.01f);
        // wide screenshot (host side): 360 wide, 225 tall
        TranscriptItem screen = TranscriptItem.image(TranscriptItem.ASSISTANT, REF, 0.625f, "Your Mac", "mac_screenshot");
        TranscriptLayout.measure(screen, new Mono());
        assertEquals(360f, screen.frameWidth, 0.01f);
        assertEquals(225f, screen.frameHeight, 0.01f);
        // very tall: height capped, frame narrower (never under 120)
        float[] tall = TranscriptLayout.frame(3f, 360f, TranscriptLayout.IMAGE_MAX_H);
        assertEquals(TranscriptLayout.IMAGE_MAX_H, tall[1], 0.01f);
        assertEquals(TranscriptLayout.IMAGE_MIN_W, tall[0], 0.01f);
        // very wide: at least the minimum height
        float[] wide = TranscriptLayout.frame(0.1f, 360f, TranscriptLayout.IMAGE_MAX_H);
        assertEquals(TranscriptLayout.IMAGE_MIN_H, wide[1], 0.01f);
        assertEquals(360f, wide[0], 0.01f);
        // unknown aspect falls back to 4:3
        float[] unknown = TranscriptLayout.frame(Float.NaN, 264f, TranscriptLayout.IMAGE_MAX_H);
        assertEquals(198f, unknown[1], 0.01f);
    }

    @Test public void generatedUiPanel() {
        TranscriptItem ui = TranscriptItem.generatedUi(REF, 0.66f, "Weekly focus hours",
                "Bar chart of focus hours per day", "art_1");
        TranscriptLayout.measure(ui, new Mono());
        float inner = TranscriptLayout.UI_RIGHT - TranscriptLayout.UI_LEFT - 2f * TranscriptLayout.UI_PAD;
        assertEquals(TranscriptLayout.UI_THUMB_MAX_H, ui.frameHeight, 0.01f); // 408*0.66 = 269 -> 200
        assertEquals(200f / 0.66f, ui.frameWidth, 0.01f); // hugs the fitted picture
        TranscriptItem wide = TranscriptItem.generatedUi(REF, 0.3f, "Wide", "", "art_w");
        TranscriptLayout.measure(wide, new Mono());
        assertEquals(inner, wide.frameWidth, 0.01f);
        assertEquals(inner * 0.3f, wide.frameHeight, 0.01f);
        assertEquals("Weekly focus hours", ui.titleLine);
        assertEquals("Bar chart of focus hours per day", ui.detailLine);
        float expected = TranscriptLayout.UI_PAD + TranscriptLayout.UI_LABEL_H + 8f + 200f + 10f
                + TranscriptLayout.UI_TITLE_H + TranscriptLayout.UI_SUMMARY_H + TranscriptLayout.UI_PAD;
        assertEquals(expected, ui.height, 0.01f);
        // no summary: no summary row; long titles are ellipsized to the panel
        TranscriptItem bare = TranscriptItem.generatedUi(null, 0.5f, "x".repeat(80), "", "art_2");
        TranscriptLayout.measure(bare, new Mono());
        assertEquals(expected - TranscriptLayout.UI_SUMMARY_H, bare.height, 0.01f);
        assertTrue(bare.titleLine.endsWith(TranscriptLayout.ELLIPSIS));
        assertTrue(bare.titleLine.length() * 11f <= inner + 11f);
        assertFalse(bare.hasPicture());
        assertTrue(ui.hasPicture());
        TranscriptItem untitled = TranscriptItem.generatedUi(REF, 0.5f, "", "", "art_3");
        TranscriptLayout.measure(untitled, new Mono());
        assertEquals("Generated UI", untitled.titleLine);
    }

    @Test public void onlyDirtyItemsAreMeasured() {
        Mono mono = new Mono();
        List<TranscriptItem> items = new ArrayList<>();
        items.add(TranscriptItem.text(TranscriptItem.USER, "hello there"));
        items.add(TranscriptItem.text(TranscriptItem.ASSISTANT, "Hi"));
        TranscriptLayout.layout(items, mono);
        int afterFirst = mono.calls;
        float again = TranscriptLayout.layout(items, mono);
        assertEquals(afterFirst, mono.calls);
        items.get(1).setText("Hi");
        TranscriptLayout.layout(items, mono);
        assertEquals("same text keeps the item clean", afterFirst, mono.calls);
        items.get(1).setText("Hi, how can I help you today? I can look at your screen and more.");
        float grown = TranscriptLayout.layout(items, mono);
        assertTrue(mono.calls > afterFirst);
        assertTrue(grown > again);
    }

    @Test public void hitTestingAndScroll() {
        List<TranscriptItem> items = new ArrayList<>();
        items.add(TranscriptItem.text(TranscriptItem.USER, "show me the chart"));      // 0..42
        items.add(TranscriptItem.generatedUi(REF, 0.5f, "Chart", "", "art_1"));        // 54..
        float total = TranscriptLayout.layout(items, new Mono());
        assertEquals(0, TranscriptLayout.itemAt(items, 10f));
        assertEquals(-1, TranscriptLayout.itemAt(items, 48f)); // the gap
        assertEquals(1, TranscriptLayout.itemAt(items, 60f));
        assertEquals(-1, TranscriptLayout.itemAt(items, total + 100f));
        assertEquals(0f, TranscriptLayout.maxScroll(100f), 0f);
        assertEquals(total - TranscriptLayout.viewport(), TranscriptLayout.maxScroll(total), 0.01f);
        assertEquals(322f, TranscriptLayout.viewport(), 0f);
    }

    @Test public void ellipsize() {
        Mono mono = new Mono();
        assertEquals("short", TranscriptLayout.ellipsize("short", 13f, false, 100f, mono));
        assertEquals("abcdefgh…", TranscriptLayout.ellipsize("abcdefghijklmnop", 13f, false, 90f, mono));
        assertEquals("", TranscriptLayout.ellipsize(null, 13f, false, 90f, mono));
        assertEquals("a b", TranscriptLayout.ellipsize("a\nb", 13f, false, 90f, mono));
    }
}
