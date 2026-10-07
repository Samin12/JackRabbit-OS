package com.resonolabs.feature.genui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import java.util.List;

public class GenCardStoreTest {
    private GenTestSupport.FakeClock clock;
    private GenCardStore store;

    @Before public void setUp() {
        clock = new GenTestSupport.FakeClock();
        store = GenCardStore.inMemory(clock, null);
    }

    private GenCard put(String json) {
        GenCard card = GenCardParser.parseShow(json, clock.elapsed()).card;
        store.put(card);
        clock.advance(1_000L);
        return card;
    }

    private GenCard ephemeral(String id) {
        return put("{\"id\":\"" + id + "\",\"title\":\"" + id + "\"}");
    }

    private GenCard timer(String id) {
        return put("{\"id\":\"" + id + "\",\"title\":\"" + id + "\",\"live\":{\"type\":\"timer\",\"durationSec\":600}}");
    }

    private GenCard pinned(String id) {
        return put("{\"id\":\"" + id + "\",\"title\":\"" + id + "\",\"pinned\":true}");
    }

    private static List<String> ids(List<GenCard> cards) {
        return cards.stream().map(card -> card.id).toList();
    }

    @Test public void newCardsGoToTheFront() {
        ephemeral("a");
        ephemeral("b");
        assertEquals("b", store.front().id);
        assertEquals("a", store.stackCard(1).id);
        assertEquals(2, store.stackSize());
    }

    @Test public void reusingAnIdReplacesTheCard() {
        ephemeral("a");
        ephemeral("b");
        GenCard replacement = put("{\"id\":\"a\",\"title\":\"New A\"}");
        assertEquals(2, store.stackSize());
        assertEquals(replacement, store.front());
        assertEquals("New A", store.find("a").title);
    }

    @Test public void stackEvictsOldestEphemeralFirst() {
        timer("t1");
        ephemeral("e1");
        ephemeral("e2");
        pinned("p1");
        ephemeral("e3");
        GenCardStore.PutResult result = store.put(GenCardParser.parseShow("{\"id\":\"e4\",\"title\":\"x\"}",
                clock.elapsed()).card);
        assertEquals(GenCardStore.MAX_STACK, store.stackSize());
        assertEquals(List.of("e1"), result.evicted);
        assertEquals("e1", store.recent().get(0).id);
        assertNotNull(store.find("t1"));
    }

    @Test public void unevictableCardsMoveToTheDeckInstead() {
        timer("t1");
        timer("t2");
        pinned("p1");
        pinned("p2");
        timer("t3");
        GenCardStore.PutResult result = store.put(GenCardParser.parseShow(
                "{\"id\":\"t4\",\"title\":\"x\",\"live\":{\"type\":\"timer\",\"durationSec\":60}}", clock.elapsed()).card);
        assertEquals(List.of("p1"), result.evicted);
        assertEquals(GenCardStore.MAX_STACK, store.stackSize());
        assertNotNull(store.find("p1"));
        assertFalse(store.inStack(store.find("p1")));
        assertTrue(ids(store.liveAndPinned()).contains("p1"));
    }

    @Test public void terminalLiveCardsAreEvictedBeforePinned() {
        GenCard done = timer("done");
        store.timerDone(done);
        pinned("p1");
        timer("t1");
        timer("t2");
        timer("t3");
        GenCardStore.PutResult result = store.put(GenCardParser.parseShow(
                "{\"id\":\"t4\",\"title\":\"x\",\"live\":{\"type\":\"timer\",\"durationSec\":60}}", clock.elapsed()).card);
        assertEquals(List.of("done"), result.evicted);
        assertNull(store.find("done"));
    }

    @Test public void pinnedLimitIsSix() {
        for (int index = 0; index < GenCardStore.MAX_PINNED; index++) pinned("p" + index);
        GenCard extra = GenCardParser.parseShow("{\"id\":\"p9\",\"title\":\"x\",\"pinned\":true}", clock.elapsed()).card;
        GenCardStore.PutResult result = store.put(extra);
        assertFalse(extra.pinned);
        assertEquals(List.of("pinned: limit 6 reached, not pinned"), result.notes);
        assertEquals(GenCardStore.MAX_PINNED, store.pinnedCount());
    }

    @Test public void ephemeralTtlExpiresIntoRecent() {
        GenCard card = put("{\"id\":\"a\",\"title\":\"A\",\"ttlSec\":30}");
        clock.advance(28_000L);
        assertFalse(store.tick(clock.elapsed()));
        assertNotNull(store.find("a"));
        clock.advance(2_000L);
        assertTrue(store.tick(clock.elapsed()));
        assertNull(store.find("a"));
        assertEquals(card, store.recent().get(0));
    }

    @Test public void updatesResetTheTtl() {
        GenCard card = put("{\"id\":\"a\",\"title\":\"A\",\"ttlSec\":30}");
        clock.advance(25_000L);
        store.changed(card, true);
        clock.advance(25_000L);
        store.tick(clock.elapsed());
        assertNotNull(store.find("a"));
    }

    @Test public void pinnedCardsNeverExpire() {
        pinned("p");
        clock.advance(GenCardStore.RECENT_KEEP_MS * 3);
        store.tick(clock.elapsed());
        assertNotNull(store.find("p"));
    }

    @Test public void terminalLiveCardsLingerTwoMinutes() {
        GenCard run = put("{\"id\":\"run\",\"title\":\"Research\",\"live\":{\"type\":\"background-run\",\"runId\":\"r1\"}}");
        LiveSnapshot snapshot = new LiveSnapshot();
        snapshot.status = GenSchema.STATUS_OK;
        snapshot.terminal = true;
        snapshot.applyTo(run, clock.elapsed());
        store.liveChanged(run);
        clock.advance(GenCardStore.LIVE_LINGER_MS - 1_000L);
        store.tick(clock.elapsed());
        assertNotNull(store.find("run"));
        clock.advance(2_000L);
        store.tick(clock.elapsed());
        assertNull(store.find("run"));
        assertEquals("run", store.recent().get(0).id);
    }

    @Test public void runningLiveCardsHaveAnEightHourCap() {
        put("{\"id\":\"run\",\"title\":\"Research\",\"live\":{\"type\":\"background-run\",\"runId\":\"r1\"}}");
        clock.advance(GenCardStore.LIVE_MAX_MS);
        store.tick(clock.elapsed());
        assertNull(store.find("run"));
    }

    @Test public void finishedTimersStayUntilStoppedThenAutoClear() {
        GenCard card = timer("pasta");
        store.timerDone(card);
        assertTrue(card.terminal);
        assertEquals(GenCard.State.DONE, card.state);
        clock.advance(GenCardStore.TIMER_DONE_KEEP_MS - 1_000L);
        store.tick(clock.elapsed());
        assertNotNull(store.find("pasta"));
        clock.advance(2_000L);
        store.tick(clock.elapsed());
        assertNull(store.find("pasta"));
        assertTrue(store.recent().isEmpty());
    }

    @Test public void recentIsARingOfTen() {
        for (int index = 0; index < 12; index++) {
            ephemeral("e" + index);
        }
        store.onSessionEnded();
        assertEquals(0, store.stackSize());
        assertEquals(GenCardStore.MAX_RECENT, store.recent().size());
        assertEquals("e11", store.recent().get(0).id);
        assertEquals("e2", store.recent().get(GenCardStore.MAX_RECENT - 1).id);
    }

    @Test public void recentIsKeptOneDay() {
        ephemeral("a");
        store.onSessionEnded();
        clock.advance(GenCardStore.RECENT_KEEP_MS + 1L);
        store.tick(clock.elapsed());
        assertTrue(store.recent().isEmpty());
    }

    @Test public void userDismissalsSkipRecent() {
        ephemeral("a");
        assertTrue(store.dismiss("a", true));
        assertTrue(store.recent().isEmpty());
        assertTrue(store.wasDismissedByUser("a"));
        assertFalse(store.dismiss("a", true));
        ephemeral("a");
        assertFalse(store.wasDismissedByUser("a"));
    }

    @Test public void cycleMovesTheFrontAndWraps() {
        ephemeral("a");
        ephemeral("b");
        ephemeral("c");
        store.cycle(1);
        assertEquals("b", store.front().id);
        assertEquals("a", store.stackCard(1).id);
        assertEquals("c", store.stackCard(2).id);
        store.cycle(-1);
        assertEquals("c", store.front().id);
        store.dismiss("c", true);
        assertEquals("b", store.front().id);
    }

    @Test public void deckCountsLivePinnedAndRecent() {
        timer("t");
        pinned("p");
        ephemeral("e");
        assertEquals(2, store.deckCount());
        store.onSessionEnded();
        assertEquals(3, store.deckCount());
    }

    @Test public void persistenceRoundTripSurvivesAReboot() {
        GenTestSupport.MemoryPersistence disk = new GenTestSupport.MemoryPersistence();
        GenCardStore persisted = new GenCardStore(clock, disk, null);
        GenCard timer = GenCardParser.parseShow(
                "{\"id\":\"pasta\",\"title\":\"Pasta\",\"live\":{\"type\":\"timer\",\"durationSec\":600}}", clock.elapsed()).card;
        persisted.put(timer);
        persisted.put(GenCardParser.parseShow("{\"id\":\"list\",\"title\":\"List\",\"body\":[{\"type\":\"checklist\","
                + "\"items\":[{\"text\":\"Milk\",\"checked\":true}]}]}", clock.elapsed()).card);
        persisted.put(GenCardParser.parseShow("{\"id\":\"keep\",\"title\":\"Keep\",\"pinned\":true}", clock.elapsed()).card);
        assertNotNull(disk.saved);

        // Reboot: elapsedRealtime restarts near zero while wall time moves on by 100 s.
        GenTestSupport.FakeClock rebooted = new GenTestSupport.FakeClock();
        rebooted.elapsed = 5_000L;
        rebooted.wall = clock.wall + 100_000L;
        GenCardStore restored = new GenCardStore(rebooted, disk, null);

        GenCard pasta = restored.find("pasta");
        assertNotNull(pasta);
        assertEquals(500_000L, GenTimers.remaining(pasta.timerBlock(), rebooted.elapsed));
        assertNotNull(restored.find("keep"));
        assertTrue(restored.find("keep").pinned);
        // The ephemeral list belonged to the old session: it is in Recent now.
        assertNull(restored.find("list"));
        GenCard list = restored.recent().get(0);
        assertEquals("list", list.id);
        assertTrue(list.body.get(0).items[0].checked);
    }

    @Test public void corruptStoreFilesStartEmpty() {
        GenTestSupport.MemoryPersistence disk = new GenTestSupport.MemoryPersistence();
        disk.saved = "{\"version\":1,\"cards\":[{\"id\":5}]} trailing";
        GenCardStore restored = new GenCardStore(clock, disk, null);
        assertEquals(0, restored.stackSize());
        disk.saved = "not json";
        assertEquals(0, new GenCardStore(clock, disk, null).stackSize());
    }
}
