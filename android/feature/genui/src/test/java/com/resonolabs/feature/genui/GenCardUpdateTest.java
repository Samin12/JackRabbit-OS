package com.resonolabs.feature.genui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class GenCardUpdateTest {
    private static final long NOW = 9_000_000L;

    private static GenCard groceries() throws Exception {
        return GenCardParser.parseShow(
                GenTestSupport.examples().getJSONArray("show_card").getJSONObject(0).toString(), NOW).card;
    }

    @Test public void topLevelFieldsReplace() throws Exception {
        GenCard card = groceries();
        GenCardParser.UpdateResult result = GenCardParser.applyUpdate(card,
                "{\"id\":\"groceries\",\"title\":\"Costco run\",\"subtitle\":\"Updated\",\"accent\":\"green\","
                        + "\"pinned\":true,\"actions\":[{\"label\":\"Done\",\"dismiss\":true}]}", NOW);
        assertTrue(result.ok);
        assertEquals("Costco run", card.title);
        assertEquals("Updated", card.subtitle);
        assertEquals(GenCard.Accent.GREEN, card.accent);
        assertTrue(card.pinned);
        assertEquals(1, card.actions.size());
        assertEquals(java.util.List.of("title", "subtitle", "accent", "pinned", "actions"), result.changed);
    }

    /** examples.json patches 2 of 6 rows: ticking items must not delete the other four. */
    @Test public void checklistPatchWithKnownRowsMergesByText() throws Exception {
        GenCard card = groceries();
        String patch = GenTestSupport.examples().getJSONArray("update_card").getJSONObject(0).toString();
        GenCardParser.UpdateResult result = GenCardParser.applyUpdate(card, patch, NOW);
        assertTrue(result.ok);
        assertEquals(java.util.List.of("patch:items"), result.changed);
        GenBlock list = card.findBlock("items");
        assertEquals(6, list.items.length);
        assertTrue(list.items[0].checked);
        assertTrue(list.items[1].checked);
        assertFalse(list.items[2].checked);
        assertEquals("Spinach", list.items[2].title);
    }

    @Test public void patchWithNewRowsReplacesWholesale() throws Exception {
        GenCard card = groceries();
        GenCardParser.applyUpdate(card, "{\"id\":\"groceries\",\"patch\":[{\"id\":\"items\",\"items\":"
                + "[{\"text\":\"Bananas\"},{\"text\":\"Bread\",\"checked\":true}]}]}", NOW);
        GenBlock list = card.findBlock("items");
        assertEquals(2, list.items.length);
        assertEquals("Bananas", list.items[0].title);
        assertTrue(list.items[1].checked);
    }

    @Test public void patchMergesScalarFieldsIntoTheBlock() {
        GenCard card = GenCardParser.parseShow("{\"id\":\"s\",\"title\":\"Spend\",\"body\":[{\"type\":\"stat\","
                + "\"id\":\"total\",\"value\":\"$10\",\"label\":\"Today\"}]}", NOW).card;
        GenCardParser.UpdateResult result = GenCardParser.applyUpdate(card,
                "{\"id\":\"s\",\"patch\":[{\"id\":\"total\",\"value\":\"$12\"},{\"id\":\"ghost\",\"value\":\"1\"}]}", NOW);
        GenBlock stat = card.findBlock("total");
        assertEquals("$12", stat.value);
        assertEquals("Today", stat.label);
        assertTrue(result.trimmed.contains("patch 'ghost' not found"));
    }

    @Test public void appendIsClampedToSixBlocks() {
        GenCard card = GenCardParser.parseShow("{\"id\":\"n\",\"title\":\"Notes\",\"body\":["
                + "{\"type\":\"text\",\"text\":\"1\"},{\"type\":\"text\",\"text\":\"2\"},{\"type\":\"text\",\"text\":\"3\"},"
                + "{\"type\":\"text\",\"text\":\"4\"},{\"type\":\"text\",\"text\":\"5\"}]}", NOW).card;
        GenCardParser.UpdateResult result = GenCardParser.applyUpdate(card, "{\"id\":\"n\",\"append\":["
                + "{\"type\":\"text\",\"text\":\"6\"},{\"type\":\"text\",\"text\":\"7\"}]}", NOW);
        assertEquals(6, card.body.size());
        assertEquals("6", card.body.get(5).text);
        assertTrue(result.trimmed.contains("body 7->6"));
        assertTrue(result.changed.contains("append:1"));
    }

    @Test public void removeAndBodyReplace() {
        GenCard card = GenCardParser.parseShow("{\"id\":\"n\",\"title\":\"Notes\",\"body\":["
                + "{\"type\":\"text\",\"id\":\"a\",\"text\":\"A\"},{\"type\":\"divider\",\"id\":\"d\"}]}", NOW).card;
        GenCardParser.applyUpdate(card, "{\"id\":\"n\",\"remove\":[\"d\"]}", NOW);
        assertEquals(1, card.body.size());
        assertNull(card.findBlock("d"));
        GenCardParser.UpdateResult replaced = GenCardParser.applyUpdate(card,
                "{\"id\":\"n\",\"body\":[{\"type\":\"stat\",\"value\":\"9\"}]}", NOW);
        assertEquals(java.util.List.of("body"), replaced.changed);
        assertEquals(GenBlock.Type.STAT, card.body.get(0).type);
    }

    @Test public void liveCardsIgnoreBodyEdits() throws Exception {
        GenCard card = GenCardParser.parseShow(
                GenTestSupport.examples().getJSONArray("show_card").getJSONObject(2).toString(), NOW).card;
        GenCardParser.UpdateResult result = GenCardParser.applyUpdate(card,
                "{\"id\":\"t3-login-fix\",\"title\":\"Login fix\",\"body\":[{\"type\":\"text\",\"text\":\"x\"}]}", NOW);
        assertTrue(result.ok);
        assertTrue(result.liveBodyIgnored);
        assertEquals("Login fix", card.title);
        assertTrue(card.body.isEmpty());
        assertTrue(result.trimmed.contains("body edits ignored: live card updates itself"));
    }

    @Test public void timerPatchKeepsTheClockUnlessDurationChanges() throws Exception {
        GenCard card = GenCardParser.parseShow(
                GenTestSupport.examples().getJSONArray("show_card").getJSONObject(1).toString(), NOW).card;
        long endsAt = card.timerBlock().endsAt;
        GenCardParser.applyUpdate(card, "{\"id\":\"timer-pasta\",\"patch\":[{\"id\":\"timer\",\"label\":\"Stir\"}]}",
                NOW + 30_000L);
        assertEquals(endsAt, card.timerBlock().endsAt);
        assertEquals("Stir", card.timerBlock().label);
        GenCardParser.applyUpdate(card, "{\"id\":\"timer-pasta\",\"patch\":[{\"id\":\"timer\",\"durationSec\":60}]}",
                NOW + 30_000L);
        assertEquals(NOW + 90_000L, card.timerBlock().endsAt);
        assertEquals(60_000L, card.timerBlock().totalMs);
    }
}
