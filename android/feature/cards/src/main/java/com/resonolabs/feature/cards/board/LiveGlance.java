package com.resonolabs.feature.cards.board;

import com.resonolabs.feature.genui.GenCard;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Which GenUI cards the board shows, in what order: running live cards first (a finished timer
 * that is still ringing leads), then finished live cards, pinned cards, and finally recent ones.
 * Pure Java over {@link GenCard}, so the rules are JVM-testable.
 */
final class LiveGlance {
    static final class Entry {
        final GenCard card;
        final boolean recent;

        Entry(GenCard card, boolean recent) {
            this.card = card;
            this.recent = recent;
        }
    }

    final List<Entry> shown;
    final int live;
    final int pinned;
    final int recent;
    /** Cards that did not fit. */
    final int more;

    private LiveGlance(List<Entry> shown, int live, int pinned, int recent, int more) {
        this.shown = Collections.unmodifiableList(shown);
        this.live = live;
        this.pinned = pinned;
        this.recent = recent;
        this.more = more;
    }

    static LiveGlance pick(List<GenCard> liveAndPinned, List<GenCard> recentCards, int max) {
        List<GenCard> ringing = new ArrayList<>();
        List<GenCard> running = new ArrayList<>();
        List<GenCard> finished = new ArrayList<>();
        List<GenCard> pinnedCards = new ArrayList<>();
        for (GenCard card : liveAndPinned) {
            if (card == null) continue;
            if (card.live == null) pinnedCards.add(card);
            else if (card.isTimer() && card.timerBlock() != null && card.timerBlock().done) ringing.add(card);
            else if (card.isRunningLive()) running.add(card);
            else finished.add(card);
        }
        List<Entry> ordered = new ArrayList<>();
        for (GenCard card : ringing) ordered.add(new Entry(card, false));
        for (GenCard card : running) ordered.add(new Entry(card, false));
        for (GenCard card : finished) ordered.add(new Entry(card, false));
        for (GenCard card : pinnedCards) ordered.add(new Entry(card, false));
        int recentCount = 0;
        for (GenCard card : recentCards) {
            if (card == null) continue;
            ordered.add(new Entry(card, true));
            recentCount++;
        }
        int limit = Math.max(0, max);
        List<Entry> shown = new ArrayList<>(ordered.subList(0, Math.min(limit, ordered.size())));
        int live = ringing.size() + running.size() + finished.size();
        return new LiveGlance(shown, live, pinnedCards.size(), recentCount, ordered.size() - shown.size());
    }

    boolean isEmpty() {
        return live + pinned + recent == 0;
    }

    /** "2 live · 1 pinned", "3 recent". */
    String summary() {
        StringBuilder out = new StringBuilder();
        if (live > 0) out.append(live).append(" live");
        if (pinned > 0) out.append(out.length() > 0 ? " · " : "").append(pinned).append(" pinned");
        if (out.length() == 0 && recent > 0) out.append(recent).append(" recent");
        return out.toString();
    }
}
