package com.resonolabs.feature.genui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public class GenCardParserTest {
    private static final long NOW = 5_000_000L;

    /** The render_cards.py validator self-test: oversized payloads are clamped, never rejected. */
    @Test public void clampSelfTestMatchesReferenceValidator() throws Exception {
        JSONArray rows = new JSONArray();
        for (int index = 0; index < 20; index++) rows.put(new JSONObject().put("title", "Item " + index));
        JSONArray body = new JSONArray();
        for (int index = 0; index < 9; index++) body.put(new JSONObject().put("type", "list").put("items", rows));
        JSONArray actions = new JSONArray();
        for (int index = 0; index < 4; index++) actions.put(new JSONObject().put("label", "A").put("say", "a"));
        JSONObject big = new JSONObject()
                .put("id", "Big List!!")
                .put("title", GenTestSupport.repeat('x', 90))
                .put("body", body)
                .put("actions", actions);

        GenCardParser.ParseResult result = GenCardParser.parseShow(big.toString(), NOW);

        assertTrue(result.ok);
        GenCard card = result.card;
        assertEquals("biglist", card.id);
        assertEquals(48, card.title.length());
        assertTrue(card.title.endsWith("…"));
        assertEquals(6, card.body.size());
        for (GenBlock block : card.body) assertEquals(8, block.items.length);
        assertEquals(2, card.actions.size());
        assertTrue(result.trimmed.contains("title 90->48 chars"));
        assertTrue(result.trimmed.contains("body 9->6"));
        assertTrue(result.trimmed.contains("body[0].items 20->8"));
        assertTrue(result.trimmed.contains("actions 4->2"));
        assertTrue(result.trimmed.size() <= 10);
    }

    @Test public void allSchemaExamplesParse() throws Exception {
        JSONArray shows = GenTestSupport.examples().getJSONArray("show_card");
        for (int index = 0; index < shows.length(); index++) {
            GenCardParser.ParseResult result = GenCardParser.parseShow(shows.getJSONObject(index).toString(), NOW);
            assertTrue("example " + index, result.ok);
            assertTrue("example " + index + " trimmed " + result.trimmed, result.trimmed.isEmpty());
        }
    }

    @Test public void groceriesExampleBuildsChecklistAndActions() throws Exception {
        JSONObject json = GenTestSupport.examples().getJSONArray("show_card").getJSONObject(0);
        GenCard card = GenCardParser.parseShow(json.toString(), NOW).card;
        assertEquals("groceries", card.id);
        assertEquals(GenCard.Accent.VIOLET, card.accent);
        assertEquals(GenSchema.indexOf(GenSchema.ICONS, "cart"), card.icon);
        GenBlock list = card.body.get(0);
        assertEquals(GenBlock.Type.CHECKLIST, list.type);
        assertEquals("items", list.id);
        assertEquals(6, list.items.length);
        assertTrue(list.items[0].checked);
        assertFalse(list.items[1].checked);
        assertEquals(GenAction.Kind.SAY, card.actions.get(0).kind);
        assertEquals(GenAction.Kind.DISMISS, card.actions.get(1).kind);
        assertEquals(GenAction.Style.PRIMARY, card.actions.get(1).style);
    }

    @Test public void liveTimerCreatesItsTimerBlock() throws Exception {
        JSONObject json = GenTestSupport.examples().getJSONArray("show_card").getJSONObject(1);
        GenCard card = GenCardParser.parseShow(json.toString(), NOW).card;
        assertEquals(GenCard.Size.COMPACT, card.size);
        assertTrue(card.isTimer());
        GenBlock timer = card.timerBlock();
        assertNotNull(timer);
        assertEquals(540_000L, timer.totalMs);
        assertEquals(NOW + 540_000L, timer.endsAt);
        assertEquals(GenAction.Kind.TIMER, card.actions.get(0).kind);
        assertEquals("add1m", card.actions.get(0).arg);
        assertEquals(GenAction.Style.DANGER, card.actions.get(1).style);
    }

    @Test public void timerBlockImpliesLiveTimer() {
        GenCard card = GenCardParser.parseShow(
                "{\"id\":\"t\",\"title\":\"Eggs\",\"body\":[{\"type\":\"timer\",\"durationSec\":300}]}", NOW).card;
        assertNotNull(card.live);
        assertEquals(LiveBinding.Type.TIMER, card.live.type);
        assertEquals(GenSchema.ICON_TIMER, card.icon);
    }

    @Test public void timerWithoutDurationIsDropped() {
        GenCardParser.ParseResult result = GenCardParser.parseShow(
                "{\"id\":\"t\",\"title\":\"Eggs\",\"live\":{\"type\":\"timer\"}}", NOW);
        assertTrue(result.ok);
        assertNull(result.card.live);
        assertTrue(result.trimmed.contains("live timer dropped (needs durationSec)"));
    }

    @Test public void rejectsOnlyForTheDocumentedReasons() {
        assertEquals("Arguments are not valid JSON.", GenCardParser.parseShow("{nope", NOW).error);
        assertEquals("id is required (letters, digits, - or _).",
                GenCardParser.parseShow("{\"id\":\"!!!\",\"title\":\"x\"}", NOW).error);
        assertEquals("title is required.", GenCardParser.parseShow("{\"id\":\"a\"}", NOW).error);
        assertEquals("title is required.", GenCardParser.parseShow("{\"id\":\"a\",\"title\":\"   \"}", NOW).error);
        String huge = "{\"id\":\"a\",\"title\":\"" + GenTestSupport.repeat('y', 17_000) + "\"}";
        assertFalse(GenCardParser.parseShow(huge, NOW).ok);
        assertTrue(GenCardParser.parseShow(huge, NOW).error.contains("16 KB"));
    }

    @Test public void unknownEnumsAndBlocksAreDefaultedOrDropped() {
        GenCardParser.ParseResult result = GenCardParser.parseShow("{\"id\":\"a\",\"title\":\"T\",\"accent\":\"teal\","
                + "\"icon\":\"rocket\",\"size\":\"huge\",\"body\":[{\"type\":\"image\",\"url\":\"https://x\"},"
                + "{\"type\":\"checklist\",\"items\":[]},{\"type\":\"text\",\"text\":\"hello   world\",\"style\":\"shout\"}]}",
                NOW);
        assertTrue(result.ok);
        GenCard card = result.card;
        assertEquals(GenCard.Accent.BLUE, card.accent);
        assertEquals(-1, card.icon);
        assertEquals(GenCard.Size.CARD, card.size);
        assertEquals(1, card.body.size());
        assertEquals("hello world", card.body.get(0).text);
        assertEquals(GenSchema.STYLE_BODY, card.body.get(0).style);
        assertTrue(result.trimmed.contains("accent unknown, blue used"));
        assertTrue(result.trimmed.contains("icon unknown, none used"));
        assertTrue(result.trimmed.contains("body[0] dropped (unknown type 'image')"));
        assertTrue(result.trimmed.contains("body[1] dropped (empty checklist)"));
    }

    @Test public void numbersAreClamped() {
        GenCard card = GenCardParser.parseShow("{\"id\":\"a\",\"title\":\"T\",\"ttlSec\":5,\"body\":["
                + "{\"type\":\"progress\",\"progress\":1.7},{\"type\":\"progress\",\"progress\":45},"
                + "{\"type\":\"progress\",\"indeterminate\":true,\"steps\":[\"A\",\"B\",\"C\",\"D\",\"E\"],\"step\":9},"
                + "{\"type\":\"bars\",\"values\":[1,-3,\"x\",4],\"highlight\":7},"
                + "{\"type\":\"timer\",\"durationSec\":999999}]}", NOW).card;
        assertEquals(10_000L, card.ttlMs);
        assertEquals(1f, card.body.get(0).progress, 0.0001f);
        assertEquals(0.45f, card.body.get(1).progress, 0.0001f);
        assertTrue(card.body.get(2).indeterminate());
        assertEquals(4, card.body.get(2).steps.length);
        assertEquals(3, card.body.get(2).step);
        assertEquals(3, card.body.get(3).bars.length);
        assertEquals(0f, card.body.get(3).bars[1], 0f);
        assertEquals(-1, card.body.get(3).highlight);
        assertEquals(86_400_000L, card.body.get(4).totalMs);
    }

    @Test public void forgivingShapesAreAccepted() {
        GenCard card = GenCardParser.parseShow("{\"id\":\"Grocery List\",\"title\":\"Groceries\",\"body\":["
                + "{\"type\":\"checklist\",\"items\":[\"Milk\",{\"title\":\"Eggs\",\"checked\":\"true\"}]},"
                + "{\"type\":\"kv\",\"pairs\":{\"Total\":42.0,\"Store\":\"TJ\"}},"
                + "{\"type\":\"stat\",\"value\":412.8}]}", NOW).card;
        assertEquals("grocerylist", card.id);
        assertEquals("Milk", card.body.get(0).items[0].title);
        assertEquals("Eggs", card.body.get(0).items[1].title);
        assertTrue(card.body.get(0).items[1].checked);
        assertEquals(2, card.body.get(1).keys.length);
        assertEquals("42", card.body.get(1).vals[java.util.Arrays.asList(card.body.get(1).keys).indexOf("Total")]);
        assertEquals("412.8", card.body.get(2).value);
    }

    @Test public void actionsNeedExactlyOneEffect() {
        GenCardParser.ParseResult result = GenCardParser.parseShow("{\"id\":\"a\",\"title\":\"T\",\"actions\":["
                + "{\"label\":\"Both\",\"say\":\"hi\",\"dismiss\":true},{\"label\":\"None\"}]}", NOW);
        assertEquals(1, result.card.actions.size());
        assertEquals(GenAction.Kind.SAY, result.card.actions.get(0).kind);
        assertTrue(result.trimmed.contains("actions[0]: kept say only"));
        assertTrue(result.trimmed.contains("actions[1] dropped (needs say, open, timer or dismiss)"));
    }

    @Test public void liveBindingsNeedTheirIds() {
        GenCardParser.ParseResult t3 = GenCardParser.parseShow(
                "{\"id\":\"a\",\"title\":\"T\",\"live\":{\"type\":\"t3-thread\"}}", NOW);
        assertNull(t3.card.live);
        assertTrue(t3.trimmed.contains("live dropped (t3-thread needs threadId)"));
        GenCardParser.ParseResult run = GenCardParser.parseShow(
                "{\"id\":\"a\",\"title\":\"T\",\"live\":{\"type\":\"background-run\",\"runId\":\"run-1\"}}", NOW);
        assertEquals(LiveBinding.Type.BACKGROUND_RUN, run.card.live.type);
        assertEquals("run-1", run.card.live.runId);
    }

    @Test public void longRowsAreShortenedWithANote() {
        GenCardParser.ParseResult result = GenCardParser.parseShow("{\"id\":\"a\",\"title\":\"T\",\"body\":[{\"type\":"
                + "\"list\",\"items\":[{\"title\":\"" + GenTestSupport.repeat('z', 80) + "\"}]}]}", NOW);
        assertEquals(48, result.card.body.get(0).items[0].title.length());
        assertTrue(result.trimmed.contains("body[0].items: 1 long rows shortened"));
    }

    @Test public void dismissNeedsIdOrAll() {
        assertFalse(GenCardParser.parseDismiss("{}").ok);
        GenCardParser.DismissRequest all = GenCardParser.parseDismiss("{\"all\":true,\"includeTimers\":true}");
        assertTrue(all.ok && all.all && all.includeTimers && all.id == null);
        assertEquals("groceries", GenCardParser.parseDismiss("{\"id\":\"groceries\"}").id);
    }
}
