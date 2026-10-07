package com.resonolabs.feature.genui;

/**
 * Keeps one live card's body current. Owned by {@link LiveSourceRegistry}; main thread only.
 * Implementations: {@link TimerSource}, {@link CalendarNextSource}, {@link TasksSource},
 * {@link BackgroundRunSource}, {@link T3ThreadSource}.
 */
public abstract class LiveSource {
    protected final LiveSourceRegistry registry;
    protected final GenCard card;
    private boolean running;

    protected LiveSource(LiveSourceRegistry registry, GenCard card) {
        this.registry = registry;
        this.card = card;
    }

    public final GenCard card() {
        return card;
    }

    public final boolean running() {
        return running;
    }

    final void start() {
        if (running) return;
        running = true;
        onStart();
    }

    final void stop() {
        if (!running) return;
        running = false;
        onStop();
    }

    protected abstract void onStart();

    protected abstract void onStop();

    /** The Voice page or Live deck just became visible again. */
    protected void onVisible() { }

    /** Cheap per-frame check (timers). */
    protected void tick(long now) { }

    /** Writes into the card and notifies the store (bumps the revision). */
    protected final void publish() {
        if (running) registry.store().liveChanged(card);
    }
}
