package com.resonolabs.feature.genui;

import android.media.AudioManager;
import android.media.ToneGenerator;

/**
 * Local countdown: no polling (the Voice page already redraws at 30 fps). Schedules one
 * callback at {@code endsAt}; when due it marks the card done and rings an alarm-stream tone
 * for up to 30 s or until the user stops/dismisses it.
 */
final class TimerSource extends LiveSource {
    static final long RING_MS = 30_000L;
    private static final long BEEP_EVERY_MS = 1_600L;

    private final Runnable check = this::check;
    private final Runnable beep = this::beep;
    private ToneGenerator tone;
    private long ringUntil;

    TimerSource(LiveSourceRegistry registry, GenCard card) {
        super(registry, card);
    }

    @Override protected void onStart() {
        reschedule();
    }

    @Override protected void onStop() {
        registry.handler().removeCallbacks(check);
        silence();
    }

    /** +1m / pause / resume changed the clock. */
    void reschedule() {
        registry.handler().removeCallbacks(check);
        GenBlock timer = card.timerBlock();
        if (timer == null || timer.done || timer.paused) {
            if (timer != null && !timer.done) silence();
            return;
        }
        silence();
        long remaining = GenTimers.remaining(timer, registry.store().now());
        registry.handler().postDelayed(check, Math.max(0L, remaining) + 5L);
    }

    @Override protected void tick(long now) {
        GenBlock timer = card.timerBlock();
        if (timer != null && GenTimers.isDue(timer, now)) check();
    }

    private void check() {
        if (!running()) return;
        GenBlock timer = card.timerBlock();
        if (timer == null || timer.done || timer.paused) return;
        long now = registry.store().now();
        if (!GenTimers.isDue(timer, now)) {
            reschedule();
            return;
        }
        registry.store().timerDone(card);
        ring();
    }

    private void ring() {
        ringUntil = registry.store().now() + RING_MS;
        try {
            if (tone == null) tone = new ToneGenerator(AudioManager.STREAM_ALARM, 85);
        } catch (RuntimeException unavailable) {
            tone = null;
            android.util.Log.w("GenUi", "timer tone unavailable");
        }
        beep();
    }

    private void beep() {
        if (!running() || tone == null || registry.store().now() >= ringUntil) {
            silence();
            return;
        }
        tone.startTone(ToneGenerator.TONE_PROP_BEEP2, 400);
        registry.handler().postDelayed(beep, BEEP_EVERY_MS);
    }

    boolean ringing() {
        return tone != null;
    }

    void silence() {
        registry.handler().removeCallbacks(beep);
        ringUntil = 0L;
        if (tone != null) {
            try {
                tone.stopTone();
                tone.release();
            } catch (RuntimeException ignored) { }
            tone = null;
        }
    }
}
