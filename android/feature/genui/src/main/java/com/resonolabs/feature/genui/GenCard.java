package com.resonolabs.feature.genui;

import java.util.ArrayList;

/**
 * Main-thread card model produced by {@link GenCardParser}. All times use the
 * {@code SystemClock.elapsedRealtime()} base; {@link GenCardCodec} converts to wall time for
 * persistence. Every visible change must bump {@link #revision} (via {@link GenCardStore})
 * so the cached {@link GenCardLayout} is rebuilt.
 */
public final class GenCard {
    public enum Size { CARD, COMPACT }
    public enum State { ACTIVE, DONE, STALE }

    public enum Accent {
        BLUE(GenColors.rgb(26, 115, 242)), VIOLET(GenColors.rgb(124, 108, 255)),
        CYAN(GenColors.rgb(92, 162, 255)), MINT(GenColors.rgb(104, 222, 204)),
        PINK(GenColors.rgb(255, 92, 168)), AMBER(GenColors.rgb(255, 196, 92)),
        RED(GenColors.rgb(255, 99, 99)), GREEN(GenColors.SUCCESS);

        public final int color;
        /** Lifted variant for 12-15 px text on dark glass (contrast). */
        public final int text;

        Accent(int color) {
            this.color = color;
            this.text = GenColors.lift(color, 0.28f);
        }

        public String wire() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }

        public static Accent of(Object value) {
            for (Accent accent : values()) if (accent.wire().equals(value)) return accent;
            return null;
        }
    }

    public String id;
    public String title;
    public String subtitle;
    public String eyebrow;
    public Size size = Size.CARD;
    public int icon = -1;
    public Accent accent = Accent.BLUE;
    public boolean pinned;
    public long ttlMs = GenSchema.TTL_DEFAULT_SEC * 1000L;
    public long createdAt;
    public long updatedAt;
    public State state = State.ACTIVE;
    public final ArrayList<GenBlock> body = new ArrayList<>();
    public final ArrayList<GenAction> actions = new ArrayList<>();
    public LiveBinding live;
    public int revision;
    public String originSessionId;
    /** When the card moved to Recent (elapsed clock); 0 while it is on screen or in the deck. */
    public long retiredAt;

    // ---- live source output (never model-authored) ----
    /** Overrides title/subtitle while a live source owns the card (e.g. next calendar event). */
    public String liveTitle;
    public String liveSubtitle;
    /** Short big value shown at the right of a compact pill (e.g. "25m"); timers use their clock. */
    public String liveTrailing;
    public int liveStatus = GenSchema.STATUS_NONE;
    public boolean terminal;
    public long terminalAt;
    /** Live source waiting for one of the 3 polling slots. */
    public boolean livePaused;
    public long liveUpdatedAt;
    /** Shown when the source cannot be reached (e.g. T3 not connected). */
    public String liveNote;

    // ---- UI-only (not persisted) ----
    /** +1 = user expanded a compact pill to a full card, -1 = user minimized a card to a pill. */
    public int presentation;
    public long pulseAt;
    public long arrivedAt;

    public String displayTitle() {
        return liveTitle != null && !liveTitle.isEmpty() ? liveTitle : title;
    }

    public String displaySubtitle() {
        return liveSubtitle != null && !liveSubtitle.isEmpty() ? liveSubtitle : subtitle;
    }

    public boolean isLive() {
        return live != null;
    }

    public boolean isTimer() {
        return live != null && live.type == LiveBinding.Type.TIMER;
    }

    /** A live card that has not reached a terminal state (timers: not done). */
    public boolean isRunningLive() {
        if (live == null) return false;
        if (isTimer()) {
            GenBlock timer = timerBlock();
            return timer != null && !timer.done;
        }
        return !terminal;
    }

    public boolean isEphemeral() {
        return live == null && !pinned;
    }

    public GenBlock timerBlock() {
        for (int index = 0; index < body.size(); index++) {
            GenBlock block = body.get(index);
            if (block.type == GenBlock.Type.TIMER) return block;
        }
        return null;
    }

    public GenBlock findBlock(String blockId) {
        if (blockId == null) return null;
        for (int index = 0; index < body.size(); index++) {
            GenBlock block = body.get(index);
            if (blockId.equals(block.id)) return block;
        }
        return null;
    }

    /** True when this card should currently render as a one-line pill. */
    public boolean showsAsPill(boolean sessionLive) {
        if (presentation > 0) return false;
        if (presentation < 0) return true;
        if (size == Size.COMPACT) return true;
        // On the idle Voice page persistent cards collapse to pills (genui.md 4.4).
        return !sessionLive;
    }
}
