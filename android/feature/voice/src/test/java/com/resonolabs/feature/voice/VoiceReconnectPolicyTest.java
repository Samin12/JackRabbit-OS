package com.resonolabs.feature.voice;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Always-on reconnect backoff: 2/5/15/30/60 s, give up after 5 consecutive failures. */
public final class VoiceReconnectPolicyTest {
    private static final long GIVE_UP = VoiceReconnectPolicy.GIVE_UP;

    private static VoiceReconnectPolicy liveSession(long now) {
        VoiceReconnectPolicy policy = new VoiceReconnectPolicy();
        policy.onUserStart();
        policy.onLive(now);
        return policy;
    }

    @Test public void backoffSequenceThenGiveUpAfterFiveFailures() {
        VoiceReconnectPolicy policy = liveSession(0L);
        long now = 120_000L;
        assertEquals(2_000L, policy.onUnexpectedEnd(now, "data-channel-closed", true));
        assertEquals(5_000L, policy.onUnexpectedEnd(now += 3_000L, "provider_unavailable", true));
        assertEquals(15_000L, policy.onUnexpectedEnd(now += 6_000L, "ice-failed", true));
        assertEquals(30_000L, policy.onUnexpectedEnd(now += 16_000L, "runtime-unavailable", true));
        assertEquals(60_000L, policy.onUnexpectedEnd(now += 31_000L, "runtime-unavailable", true));
        assertEquals(5, policy.attempts());
        assertEquals(GIVE_UP, policy.onUnexpectedEnd(now + 61_000L, "runtime-unavailable", true));
        assertFalse(policy.armed());
        // Disarmed: further ends never reconnect.
        assertEquals(GIVE_UP, policy.onUnexpectedEnd(now + 70_000L, "data-channel-closed", true));
    }

    @Test public void stableReconnectResetsTheBackoff() {
        VoiceReconnectPolicy policy = liveSession(0L);
        assertEquals(2_000L, policy.onUnexpectedEnd(3_600_000L, "provider-error", true)); // max duration
        assertEquals(5_000L, policy.onUnexpectedEnd(3_602_500L, "provider_unavailable", true));
        policy.onLive(3_608_000L);
        // Stayed live for minutes: the next drop starts again at 2 s.
        assertEquals(2_000L, policy.onUnexpectedEnd(3_900_000L, "data-channel-closed", true));
    }

    @Test public void flappingSessionKeepsEscalating() {
        VoiceReconnectPolicy policy = liveSession(0L);
        long now = 60_000L;
        assertEquals(2_000L, policy.onUnexpectedEnd(now, "data-channel-closed", true));
        policy.onLive(now += 2_500L);
        // Live for only 4 s before dropping again: not a recovery.
        assertEquals(5_000L, policy.onUnexpectedEnd(now += 4_000L, "data-channel-closed", true));
    }

    @Test public void userStopOrFreshStartNeverReconnects() {
        VoiceReconnectPolicy policy = liveSession(0L);
        policy.onUserStop();
        assertEquals(GIVE_UP, policy.onUnexpectedEnd(1_000L, "data-channel-closed", true));
        // A start that never reached live is a plain error, not a reconnect.
        policy.onUserStart();
        assertEquals(GIVE_UP, policy.onUnexpectedEnd(2_000L, "provider_unavailable", true));
    }

    @Test public void disabledSettingOrPermanentFailureGivesUp() {
        assertEquals(GIVE_UP, liveSession(0L).onUnexpectedEnd(60_000L, "data-channel-closed", false));
        assertEquals(GIVE_UP, liveSession(0L).onUnexpectedEnd(60_000L, "microphone-required", true));
        assertEquals(GIVE_UP, liveSession(0L).onUnexpectedEnd(60_000L,
                "credential_rejected: OpenAI rejected this credential", true));
        assertTrue(VoiceReconnectPolicy.permanent("unsupported_model"));
        assertFalse(VoiceReconnectPolicy.permanent("provider_unavailable: timeout"));
        assertFalse(VoiceReconnectPolicy.permanent(null));
    }
}
