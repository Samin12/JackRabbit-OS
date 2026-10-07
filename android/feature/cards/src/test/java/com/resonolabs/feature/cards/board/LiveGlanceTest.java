package com.resonolabs.feature.cards.board;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.resonolabs.feature.genui.GenCard;
import com.resonolabs.feature.genui.LiveBinding;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

public class LiveGlanceTest {
    private static GenCard card(String id, LiveBinding.Type live, boolean pinned) {
        GenCard card = new GenCard();
        card.id = id;
        card.title = id;
        card.pinned = pinned;
        card.live = live == null ? null : new LiveBinding(live, 300, null, live == LiveBinding.Type.T3_THREAD ? "t-1" : null);
        return card;
    }

    private static List<String> ids(LiveGlance glance) {
        List<String> out = new ArrayList<>();
        for (LiveGlance.Entry entry : glance.shown) out.add((entry.recent ? "recent:" : "") + entry.card.id);
        return out;
    }

    @Test public void runningLiveWorkLeadsThenFinishedThenPinnedThenRecent() {
        GenCard pinned = card("wifi", null, true);
        GenCard finished = card("deploy", LiveBinding.Type.T3_THREAD, false);
        finished.terminal = true;
        GenCard running = card("login", LiveBinding.Type.T3_THREAD, false);
        List<GenCard> active = List.of(pinned, finished, running);
        List<GenCard> recent = List.of(card("weather", null, false), card("groceries", null, false));
        LiveGlance glance = LiveGlance.pick(active, recent, 3);
        assertEquals(List.of("login", "deploy", "wifi"), ids(glance));
        assertEquals(2, glance.live);
        assertEquals(1, glance.pinned);
        assertEquals(2, glance.recent);
        assertEquals(2, glance.more);
        assertEquals("2 live · 1 pinned", glance.summary());

        assertEquals(List.of("login", "deploy", "wifi", "recent:weather", "recent:groceries"),
                ids(LiveGlance.pick(active, recent, 10)));
    }

    @Test public void onlyRecentCardsStillShowDimmed() {
        LiveGlance glance = LiveGlance.pick(List.of(), List.of(card("weather", null, false)), 3);
        assertFalse(glance.isEmpty());
        assertTrue(glance.shown.get(0).recent);
        assertEquals("1 recent", glance.summary());
        assertEquals(0, glance.more);
    }

    @Test public void emptyStoreHidesTheWidget() {
        LiveGlance glance = LiveGlance.pick(List.of(), List.of(), 3);
        assertTrue(glance.isEmpty());
        assertTrue(glance.shown.isEmpty());
        assertEquals("", glance.summary());
    }
}
