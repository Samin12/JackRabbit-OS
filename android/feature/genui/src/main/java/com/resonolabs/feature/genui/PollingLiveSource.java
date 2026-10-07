package com.resonolabs.feature.genui;

/**
 * Shared polling loop: visible/hidden cadence, at most 3 sources polling at once (others show
 * "Paused"), exponential backoff 2/4/8/30 s, and a stale state after 3 consecutive failures
 * (the Live Activities {@code isStale} idea). Stops polling once the card is terminal.
 */
public abstract class PollingLiveSource extends LiveSource {
    private static final long[] BACKOFF_MS = {2_000L, 4_000L, 8_000L, 30_000L};
    static final int STALE_AFTER_FAILURES = 3;

    private final long visibleMs;
    private final long hiddenMs;
    private final Runnable poll = this::poll;
    private boolean hasSlot;
    private boolean inFlight;
    private int generation;
    private int failures;
    private long overrideMs;

    protected PollingLiveSource(LiveSourceRegistry registry, GenCard card, long visibleMs, long hiddenMs) {
        super(registry, card);
        this.visibleMs = visibleMs;
        this.hiddenMs = hiddenMs;
    }

    /** Fetch once and finish with exactly one of {@link #succeeded}, {@link #failed}, {@link #unavailable}. */
    protected abstract void fetch(int token);

    @Override protected void onStart() {
        if (card.terminal) return; // restored finished card: nothing to poll
        if (registry.requestSlot(this)) granted();
        else {
            card.livePaused = true;
            publish();
        }
    }

    final void granted() {
        hasSlot = true;
        if (card.livePaused) {
            card.livePaused = false;
            publish();
        }
        schedule(0L);
    }

    @Override protected void onStop() {
        generation++;
        inFlight = false;
        registry.handler().removeCallbacks(poll);
        boolean held = hasSlot;
        hasSlot = false;
        registry.releaseSlot(this, held);
        closeClient();
    }

    protected void closeClient() { }

    @Override protected void onVisible() {
        if (running() && hasSlot && !inFlight) schedule(250L);
    }

    private void schedule(long delayMs) {
        registry.handler().removeCallbacks(poll);
        registry.handler().postDelayed(poll, delayMs);
    }

    private void poll() {
        if (!running() || !hasSlot || inFlight) return;
        inFlight = true;
        fetch(generation);
    }

    private boolean current(int token) {
        if (token != generation || !running()) return false;
        inFlight = false;
        return true;
    }

    /** The source applied fresh data to the card. {@code nextPollMs} overrides the cadence when > 0. */
    protected final void succeeded(int token, long nextPollMs) {
        if (!current(token)) return;
        failures = 0;
        overrideMs = nextPollMs;
        if (card.state == GenCard.State.STALE) card.state = card.terminal ? GenCard.State.DONE : GenCard.State.ACTIVE;
        publish();
        if (card.terminal) {
            finishPolling();
            return;
        }
        schedule(interval());
    }

    /** A definitive "not available" answer (e.g. T3 not connected): calm slow retry, no stale. */
    protected final void unavailable(int token, String note) {
        if (!current(token)) return;
        failures = 0;
        card.liveNote = note;
        card.liveStatus = GenSchema.STATUS_IDLE;
        publish();
        schedule(Math.max(hiddenMs, 20_000L));
    }

    protected final void failed(int token) {
        if (!current(token)) return;
        failures++;
        if (failures >= STALE_AFTER_FAILURES && card.state != GenCard.State.STALE) {
            card.state = GenCard.State.STALE;
            publish();
        }
        schedule(BACKOFF_MS[Math.min(BACKOFF_MS.length - 1, failures - 1)]);
    }

    private void finishPolling() {
        registry.handler().removeCallbacks(poll);
        if (hasSlot) {
            hasSlot = false;
            registry.releaseSlot(this, true);
        }
    }

    private long interval() {
        if (overrideMs > 0L) return Math.max(1_000L, Math.min(60_000L, overrideMs));
        return registry.isVisible() ? visibleMs : hiddenMs;
    }
}
