package com.resonolabs.feature.genui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

public class GenUiControllerTest {
    private GenTestSupport.FakeClock clock;
    private GenTestSupport.FakeHost host;
    private GenCardStore store;
    private GenUiController controller;

    @Before public void setUp() {
        clock = new GenTestSupport.FakeClock();
        host = new GenTestSupport.FakeHost();
        store = GenCardStore.inMemory(clock, null);
        controller = new GenUiController(store, null, host);
        controller.onSessionStarted("voice-1");
    }

    private String show(String json) {
        return controller.execute(GenUiTools.SHOW_CARD, json, clock.elapsed());
    }

    private static String card(String id) {
        return "{\"id\":\"" + id + "\",\"title\":\"" + id + "\",\"body\":[{\"type\":\"text\",\"text\":\"hi\"}]}";
    }

    @Test public void localToolNames() {
        assertTrue(GenUiTools.isLocal("show_card"));
        assertTrue(GenUiTools.isLocal("update_card"));
        assertTrue(GenUiTools.isLocal("dismiss_card"));
        assertFalse(GenUiTools.isLocal("tasks_list"));
        assertFalse(GenUiTools.isLocal(null));
    }

    @Test public void showOutputIsExact() throws Exception {
        String output = show(GenTestSupport.examples().getJSONArray("show_card").getJSONObject(0).toString());
        assertEquals("{\"ok\":true,\"id\":\"groceries\",\"shown\":\"front\",\"stack\":1,\"trimmed\":[]}", output);
        assertEquals("voice-1", store.find("groceries").originSessionId);
        assertTrue(controller.lastTranscriptLine().startsWith("[Card] Grocery list: Oat milk, Eggs"));
    }

    @Test public void showReportsTrims() {
        String output = show("{\"id\":\"a\",\"title\":\"T\",\"accent\":\"teal\"}");
        assertEquals("{\"ok\":true,\"id\":\"a\",\"shown\":\"front\",\"stack\":1,\"trimmed\":[\"accent unknown, blue used\"]}",
                output);
    }

    @Test public void atMostTwoShowsPerResponse() {
        controller.onResponseCreated();
        assertTrue(show(card("a")).startsWith("{\"ok\":true"));
        assertTrue(show(card("b")).startsWith("{\"ok\":true"));
        assertEquals("{\"ok\":false,\"error\":\"Too many cards in one reply. Update an existing card instead.\"}",
                show(card("c")));
        assertNull(store.find("c"));
        controller.onResponseCreated();
        assertTrue(show(card("c")).startsWith("{\"ok\":true"));
    }

    @Test public void invalidShowDoesNotSpendTheBudget() {
        controller.onResponseCreated();
        assertEquals("{\"ok\":false,\"error\":\"title is required.\"}", show("{\"id\":\"x\"}"));
        assertTrue(show(card("a")).startsWith("{\"ok\":true"));
        assertTrue(show(card("b")).startsWith("{\"ok\":true"));
    }

    @Test public void softBudgetLimitsNewIdsPerTwoMinutes() {
        for (String id : new String[]{"a", "b", "c", "d"}) {
            controller.onResponseCreated();
            assertTrue(show(card(id)).startsWith("{\"ok\":true"));
        }
        controller.onResponseCreated();
        assertEquals("{\"ok\":false,\"error\":\"Too many new cards. Update or reuse an existing card instead.\"}",
                show(card("e")));
        // Re-showing an existing id is always fine.
        assertTrue(show(card("a")).startsWith("{\"ok\":true"));
        clock.advance(GenUiController.NEW_IDS_WINDOW_MS + 1);
        controller.onResponseCreated();
        assertTrue(show(card("e")).startsWith("{\"ok\":true"));
    }

    @Test public void updateOutputsChangedFields() throws Exception {
        show(GenTestSupport.examples().getJSONArray("show_card").getJSONObject(0).toString());
        String patch = GenTestSupport.examples().getJSONArray("update_card").getJSONObject(0).toString();
        assertEquals("{\"ok\":true,\"id\":\"groceries\",\"changed\":[\"patch:items\"]}",
                controller.execute(GenUiTools.UPDATE_CARD, patch, clock.elapsed()));
        assertEquals(1, store.find("groceries").revision);
    }

    @Test public void unknownIdsExplainWhy() {
        assertEquals("{\"ok\":false,\"error\":\"No card with id 'nope'.\"}",
                controller.execute(GenUiTools.UPDATE_CARD, "{\"id\":\"nope\",\"title\":\"x\"}", clock.elapsed()));
        show(card("groceries"));
        controller.dismissByUser(store.find("groceries"));
        assertEquals("{\"ok\":false,\"error\":\"No card with id 'groceries' (the user dismissed it).\"}",
                controller.execute(GenUiTools.UPDATE_CARD, "{\"id\":\"groceries\",\"title\":\"x\"}", clock.elapsed()));
        assertEquals("{\"ok\":false,\"error\":\"No card with id 'groceries' (the user dismissed it).\"}",
                controller.execute(GenUiTools.DISMISS_CARD, "{\"id\":\"groceries\"}", clock.elapsed()));
    }

    @Test public void dismissOutputs() {
        show(card("groceries"));
        assertEquals("{\"ok\":true,\"dismissed\":[\"groceries\"]}",
                controller.execute(GenUiTools.DISMISS_CARD, "{\"id\":\"groceries\"}", clock.elapsed()));
        assertEquals(0, store.stackSize());
    }

    @Test public void dismissAllKeepsRunningTimersUnlessAsked() throws Exception {
        controller.onResponseCreated();
        show(GenTestSupport.examples().getJSONArray("show_card").getJSONObject(1).toString());
        show(card("notes"));
        assertEquals("{\"ok\":true,\"dismissed\":[\"notes\"]}",
                controller.execute(GenUiTools.DISMISS_CARD, "{\"all\":true}", clock.elapsed()));
        assertNotNull(store.find("timer-pasta"));
        assertEquals("{\"ok\":true,\"dismissed\":[\"timer-pasta\"]}",
                controller.execute(GenUiTools.DISMISS_CARD, "{\"all\":true,\"includeTimers\":true}", clock.elapsed()));
        assertEquals(0, store.stackSize());
    }

    @Test public void unknownToolAndGarbageNeverThrow() {
        assertEquals("{\"ok\":false,\"error\":\"Unknown card tool.\"}", controller.execute("draw", "{}", 0L));
        assertEquals("{\"ok\":false,\"error\":\"Arguments are not valid JSON.\"}",
                controller.execute(GenUiTools.UPDATE_CARD, "[", 0L));
        assertTrue(controller.execute(GenUiTools.SHOW_CARD, null, 0L).startsWith("{\"ok\":false"));
    }

    @Test public void sayActionSendsTextOrStartsASession() throws Exception {
        show(GenTestSupport.examples().getJSONArray("show_card").getJSONObject(0).toString());
        GenCard card = store.find("groceries");
        controller.onAction(card, card.actions.get(0));
        assertEquals(java.util.List.of("Add something to the grocery list"), host.userTexts);
        host.live = false;
        controller.onAction(card, card.actions.get(0));
        assertEquals(java.util.List.of("Add something to the grocery list"), host.started);
    }

    @Test public void dismissActionRemovesTheCardAsTheUser() throws Exception {
        show(GenTestSupport.examples().getJSONArray("show_card").getJSONObject(0).toString());
        GenCard card = store.find("groceries");
        controller.onAction(card, card.actions.get(1));
        assertNull(store.find("groceries"));
        assertTrue(store.wasDismissedByUser("groceries"));
    }

    @Test public void checklistTapTogglesAndTellsTheModelSilently() throws Exception {
        show(GenTestSupport.examples().getJSONArray("show_card").getJSONObject(0).toString());
        GenCard card = store.find("groceries");
        controller.onRowTapped(card, card.body.get(0), 2);
        assertTrue(card.body.get(0).items[2].checked);
        assertEquals(java.util.List.of("[UI event] User checked 'Spinach' on card groceries."), host.notes);
        controller.onRowTapped(card, card.body.get(0), 2);
        assertFalse(card.body.get(0).items[2].checked);
        assertTrue(host.notes.get(1).contains("unchecked 'Spinach'"));
    }

    @Test public void rowPromptRowsAskTheModelInstead() {
        show("{\"id\":\"tasks\",\"title\":\"Tasks\",\"body\":[{\"type\":\"checklist\",\"items\":[\"Call mom\"]}]}");
        GenCard card = store.find("tasks");
        card.body.get(0).rowSay = TasksSource.ROW_SAY;
        controller.onRowTapped(card, card.body.get(0), 0);
        assertFalse(card.body.get(0).items[0].checked);
        assertEquals(java.util.List.of("Mark task 'Call mom' as done"), host.userTexts);
    }

    @Test public void timerActionsChangeTheClock() throws Exception {
        show(GenTestSupport.examples().getJSONArray("show_card").getJSONObject(1).toString());
        GenCard card = store.find("timer-pasta");
        long before = card.timerBlock().endsAt;
        controller.onAction(card, card.actions.get(0));
        assertEquals(before + 60_000L, card.timerBlock().endsAt);
        controller.timerOp(card, "pause");
        assertTrue(card.timerBlock().paused);
        controller.timerOp(card, "resume");
        assertFalse(card.timerBlock().paused);
    }

    @Test public void focusNotesAreRateLimited() {
        show(card("a"));
        GenCard card = store.find("a");
        controller.onCardFocused(card);
        controller.onCardFocused(card);
        assertEquals(java.util.List.of("[UI event] The user is looking at card 'a'."), host.notes);
        clock.advance(11_000L);
        controller.onCardFocused(card);
        assertEquals(2, host.notes.size());
    }

    @Test public void screenSummaryDescribesWhatIsShown() throws Exception {
        assertFalse(controller.hasScreenSummary());
        controller.onResponseCreated();
        show(GenTestSupport.examples().getJSONArray("show_card").getJSONObject(1).toString());
        show(GenTestSupport.examples().getJSONArray("show_card").getJSONObject(0).toString());
        clock.advance(288_000L);
        assertEquals("[Screen] Cards on screen: groceries (Grocery list, 1/6 checked), timer-pasta (Pasta, 4:12 left).",
                controller.screenSummary());
    }

    @Test public void timerDoneReachesTheHost() throws Exception {
        show(GenTestSupport.examples().getJSONArray("show_card").getJSONObject(1).toString());
        store.timerDone(store.find("timer-pasta"));
        assertEquals(java.util.List.of("timer-pasta"), host.finished);
        assertTrue(store.find("timer-pasta").timerBlock().done);
    }

    @Test public void sessionEndMovesEphemeralCardsToRecent() throws Exception {
        controller.onResponseCreated();
        show(GenTestSupport.examples().getJSONArray("show_card").getJSONObject(0).toString());
        show(GenTestSupport.examples().getJSONArray("show_card").getJSONObject(1).toString());
        controller.onSessionEnded();
        assertEquals(1, store.stackSize());
        assertEquals("timer-pasta", store.front().id);
        assertEquals("groceries", store.recent().get(0).id);
        assertEquals("{\"ok\":false,\"error\":\"No card with id 'groceries' (it expired; show it again if needed).\"}",
                controller.execute(GenUiTools.UPDATE_CARD, "{\"id\":\"groceries\"}", clock.elapsed()));
    }

    /** A failed call must not leave the previous call's transcript line for the host to record again. */
    @Test public void failedCallsClearTheTranscriptLine() {
        show(card("a"));
        assertTrue(controller.lastTranscriptLine().startsWith("[Card]"));
        controller.execute(GenUiTools.UPDATE_CARD, "{\"title\":\"no id\"}", clock.elapsed());
        assertEquals("", controller.lastTranscriptLine());
        show(card("b"));
        controller.execute(GenUiTools.DISMISS_CARD, "{}", clock.elapsed());
        assertEquals("", controller.lastTranscriptLine());
    }
}
