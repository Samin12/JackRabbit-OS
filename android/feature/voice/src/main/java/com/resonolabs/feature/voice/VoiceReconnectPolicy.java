package com.resonolabs.feature.voice;

/**
 * Always-on voice: when a session the user started drops on its own (failure, data channel
 * closed, provider max duration), reconnect after 2 s, 5 s, 15 s, 30 s, then 60 s, and give up
 * after 5 consecutive failed attempts. A reconnected session counts as recovered only once it
 * has stayed live for {@link #STABLE_MS}; one that drops sooner keeps escalating, so a flapping
 * connection cannot loop at 2 s forever. Nothing reconnects after the user ends the session,
 * before a session first reached live (a start that never connected is a plain error), or for
 * failures a retry cannot fix (no microphone permission, missing or rejected credential, model).
 * Pure; times are any monotonic millisecond clock.
 */
final class VoiceReconnectPolicy {
    static final long[] DELAYS_MS = {2_000L, 5_000L, 15_000L, 30_000L, 60_000L};
    static final int MAX_FAILURES = 5;
    static final long STABLE_MS = 30_000L;
    static final long GIVE_UP = -1L;

    private boolean armed;
    private int failures;
    private long liveSince = -1L;

    /** The user started a fresh session: forget everything. */
    void onUserStart() {
        armed = false;
        failures = 0;
        liveSince = -1L;
    }

    /** The user ended the session (stop button, BACK, side button, screen-off policy). */
    void onUserStop() {
        onUserStart();
    }

    /** The data channel opened (user start or reconnect). */
    void onLive(long now) {
        armed = true;
        liveSince = now;
    }

    /**
     * The session ended without the user asking. Returns the delay before the next attempt, or
     * {@link #GIVE_UP}.
     */
    long onUnexpectedEnd(long now, String reason, boolean enabled) {
        if (!enabled || !armed || permanent(reason)) {
            onUserStart();
            return GIVE_UP;
        }
        if (liveSince >= 0L && now - liveSince >= STABLE_MS) failures = 0;
        liveSince = -1L;
        if (failures >= MAX_FAILURES) {
            onUserStart();
            return GIVE_UP;
        }
        long delay = DELAYS_MS[Math.min(failures, DELAYS_MS.length - 1)];
        failures++;
        return delay;
    }

    /** Reconnect attempts made since the session was last stable. */
    int attempts() {
        return failures;
    }

    boolean armed() {
        return armed;
    }

    static boolean permanent(String reason) {
        if (reason == null) return false;
        int separator = reason.indexOf(':');
        String code = separator > 0 ? reason.substring(0, separator) : reason;
        return switch (code) {
            case "microphone-required", "credential_unavailable", "credential_rejected",
                 "model_required", "unsupported_model", "provider_rejected" -> true;
            default -> false;
        };
    }
}
