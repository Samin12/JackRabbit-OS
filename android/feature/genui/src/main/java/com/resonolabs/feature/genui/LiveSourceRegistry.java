package com.resonolabs.feature.genui;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;

/**
 * Starts one {@link LiveSource} per live card in the store and stops it when the card goes
 * away (or is replaced by a show_card with the same id). At most {@value #MAX_POLLING} sources
 * poll at once; the rest wait (their cards show "Paused"). Visibility is automatic: any
 * {@link GenCardOverlay} or deck that draws marks the registry visible for 1.5 s, so no host
 * has to forward show/hide events.
 */
public final class LiveSourceRegistry implements GenCardStore.Listener, AutoCloseable {
    public interface Factory {
        /** Returns null to fall back to the built-in source for the card's live type. */
        LiveSource create(LiveSourceRegistry registry, GenCard card);
    }

    public static final int MAX_POLLING = 3;
    private static final long VISIBLE_GRACE_MS = 1_500L;
    private static LiveSourceRegistry instance;

    private final Context context;
    private final GenCardStore store;
    private final Factory factory;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final IdentityHashMap<GenCard, LiveSource> byCard = new IdentityHashMap<>();
    private final ArrayList<LiveSource> sources = new ArrayList<>();
    private final ArrayList<PollingLiveSource> waiting = new ArrayList<>();
    private int polling;
    private long visibleMark = Long.MIN_VALUE / 2;
    private boolean reconciling;

    public static synchronized LiveSourceRegistry get(Context context) {
        if (instance == null) {
            instance = new LiveSourceRegistry(context.getApplicationContext(), GenCardStore.get(context), null);
        }
        return instance;
    }

    public LiveSourceRegistry(Context context, GenCardStore store, Factory factory) {
        this.context = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        this.store = store;
        this.factory = factory;
        store.addListener(this);
        reconcile();
    }

    public GenCardStore store() {
        return store;
    }

    Context context() {
        return context;
    }

    Handler handler() {
        return handler;
    }

    // ------------------------------------------------------------------ visibility

    /** Called from draw paths; cheap and allocation-free. */
    public void markVisible() {
        long now = SystemClock.uptimeMillis();
        boolean wasVisible = now - visibleMark < VISIBLE_GRACE_MS;
        visibleMark = now;
        if (!wasVisible) {
            for (int index = 0; index < sources.size(); index++) sources.get(index).onVisible();
        }
    }

    public boolean isVisible() {
        return SystemClock.uptimeMillis() - visibleMark < VISIBLE_GRACE_MS;
    }

    /** Per-frame: lets timers fire exactly on the frame they reach zero. */
    public void tick(long now) {
        for (int index = 0; index < sources.size(); index++) sources.get(index).tick(now);
    }

    // ------------------------------------------------------------------ timers

    public void onTimerChanged(GenCard card) {
        LiveSource source = byCard.get(card);
        if (source instanceof TimerSource timer) timer.reschedule();
    }

    /** Stops a ringing timer tone (Stop / dismiss). */
    public void silence(GenCard card) {
        LiveSource source = byCard.get(card);
        if (source instanceof TimerSource timer) timer.silence();
    }

    public boolean isRinging(GenCard card) {
        LiveSource source = byCard.get(card);
        return source instanceof TimerSource timer && timer.ringing();
    }

    // ------------------------------------------------------------------ polling slots

    boolean requestSlot(PollingLiveSource source) {
        if (polling < MAX_POLLING) {
            polling++;
            return true;
        }
        if (!waiting.contains(source)) waiting.add(source);
        return false;
    }

    void releaseSlot(PollingLiveSource source, boolean held) {
        waiting.remove(source);
        if (!held) return;
        polling = Math.max(0, polling - 1);
        while (polling < MAX_POLLING && !waiting.isEmpty()) {
            PollingLiveSource next = waiting.remove(0);
            if (!next.running()) continue;
            polling++;
            next.granted();
        }
    }

    // ------------------------------------------------------------------ lifecycle

    @Override public void onCardsChanged() {
        reconcile();
    }

    private void reconcile() {
        if (reconciling) return;
        reconciling = true;
        try {
            List<GenCard> active = store.activeCards();
            for (int index = sources.size() - 1; index >= 0; index--) {
                LiveSource source = sources.get(index);
                if (!containsIdentity(active, source.card()) || source.card().live == null) {
                    sources.remove(index);
                    byCard.remove(source.card());
                    source.stop();
                }
            }
            for (GenCard card : active) {
                if (card.live == null || byCard.containsKey(card)) continue;
                LiveSource source = create(card);
                if (source == null) continue;
                byCard.put(card, source);
                sources.add(source);
                source.start();
            }
        } finally {
            reconciling = false;
        }
    }

    private LiveSource create(GenCard card) {
        if (factory != null) {
            LiveSource custom = factory.create(this, card);
            if (custom != null) return custom;
        }
        return switch (card.live.type) {
            case TIMER -> new TimerSource(this, card);
            case CALENDAR_NEXT -> new CalendarNextSource(this, card);
            case TASKS -> new TasksSource(this, card);
            case BACKGROUND_RUN -> new BackgroundRunSource(this, card);
            case T3_THREAD -> new T3ThreadSource(this, card);
        };
    }

    private static boolean containsIdentity(List<GenCard> cards, GenCard card) {
        for (int index = 0; index < cards.size(); index++) if (cards.get(index) == card) return true;
        return false;
    }

    /** Stops every source (debug preview teardown; the process singleton is never closed). */
    @Override public void close() {
        store.removeListener(this);
        for (LiveSource source : new ArrayList<>(sources)) source.stop();
        sources.clear();
        byCard.clear();
        waiting.clear();
        polling = 0;
    }
}
