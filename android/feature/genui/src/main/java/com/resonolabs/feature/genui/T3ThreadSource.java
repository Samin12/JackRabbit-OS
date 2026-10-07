package com.resonolabs.feature.genui;

import com.resonolabs.runtime.host.RuntimeLiveClient;

import org.json.JSONObject;

/**
 * Coding-thread progress from the T3 bridge: {@code GET /v1/live/t3-thread/{threadId}} on the
 * on-device runtime (CONTRACTS section 2). 404 (unknown thread) and 409 (T3 not paired) are
 * shown as a calm "unavailable" state, never as an error loop.
 */
final class T3ThreadSource extends PollingLiveSource {
    private RuntimeLiveClient client;

    T3ThreadSource(LiveSourceRegistry registry, GenCard card) {
        super(registry, card, 3_000L, 20_000L);
    }

    @Override protected void fetch(int token) {
        if (client == null) client = new RuntimeLiveClient();
        client.load(registry.context(), "t3-thread", card.live.threadId, new RuntimeLiveClient.Callback() {
            @Override public void onSnapshot(JSONObject value) {
                LiveSnapshot snapshot = LiveSnapshot.fromJson(value);
                snapshot.applyTo(card, registry.store().now());
                succeeded(token, snapshot.nextPollMs);
            }

            @Override public void onUnavailable(int status, String code) {
                String note = switch (status) {
                    case 404 -> "Thread not found";
                    case 409 -> "T3 not connected";
                    case 401, 403 -> "T3 needs sign-in";
                    default -> "Unavailable";
                };
                if ("t3_not_connected".equals(code)) note = "T3 not connected";
                showUnavailable(note);
                unavailable(token, note);
            }

            @Override public void onFailure() {
                failed(token);
            }
        });
    }

    private void showUnavailable(String note) {
        if (card.liveUpdatedAt > 0L && !card.body.isEmpty() && card.liveNote == null) return;
        card.body.clear();
        GenBlock text = new GenBlock(GenBlock.Type.TEXT);
        text.id = "last";
        text.style = GenSchema.STYLE_MUTED;
        text.text = "Thread not found".equals(note)
                ? "This coding thread isn't on the connected T3 server."
                : "Pair T3 Code in R1 management to follow this thread live.";
        card.body.add(text);
        card.liveSubtitle = note;
    }

    @Override protected void closeClient() {
        if (client != null) client.close();
        client = null;
    }
}
