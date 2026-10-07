package com.resonolabs.feature.genui;

import com.resonolabs.runtime.host.BackgroundRunSnapshot;
import com.resonolabs.runtime.host.RuntimeBackgroundRunClient;

import java.util.List;

/** A delegated background goal ({@code goal_start} runId), from the background-agent notifications feed. */
final class BackgroundRunSource extends PollingLiveSource {
    private RuntimeBackgroundRunClient client;

    BackgroundRunSource(LiveSourceRegistry registry, GenCard card) {
        super(registry, card, 2_000L, 15_000L);
    }

    @Override protected void fetch(int token) {
        if (client == null) client = new RuntimeBackgroundRunClient();
        client.load(registry.context(), runs -> {
            BackgroundRunSnapshot run = find(runs, card.live.runId);
            if (run == null) {
                // The feed returns nothing on transport errors too, so treat it as a failure.
                failed(token);
                if (card.state == GenCard.State.STALE) card.liveNote = "Run not found";
                return;
            }
            toSnapshot(run).applyTo(card, registry.store().now());
            succeeded(token, 0L);
        });
    }

    private static BackgroundRunSnapshot find(List<BackgroundRunSnapshot> runs, String runId) {
        for (BackgroundRunSnapshot run : runs) if (run.runId().equals(runId)) return run;
        return null;
    }

    static LiveSnapshot toSnapshot(BackgroundRunSnapshot run) {
        LiveSnapshot snapshot = new LiveSnapshot();
        String state = run.state() == null ? "" : run.state();
        snapshot.terminal = !run.active();
        snapshot.status = run.active() ? GenSchema.STATUS_ACTIVE
                : "completed".equals(state) ? GenSchema.STATUS_OK
                : "failed".equals(state) ? GenSchema.STATUS_ERROR : GenSchema.STATUS_WARN;
        String label = blankToNull(run.label());
        StringBuilder subtitle = new StringBuilder(label != null ? capitalize(label) : capitalize(state));
        if (run.toolCalls() > 0) subtitle.append(" • ").append(run.toolCalls()).append(run.toolCalls() == 1 ? " tool" : " tools");
        snapshot.subtitle = GenCardParser.clip(subtitle.toString(), GenSchema.SUBTITLE);
        String activity = blankToNull(run.activity());
        snapshot.phase = activity != null ? GenCardParser.clip(GenCardParser.collapse(activity), GenSchema.LABEL) : null;
        snapshot.progress = run.fraction() > 0f ? Math.min(1f, run.fraction()) : -1f;
        List<BackgroundRunSnapshot.TimelineEntry> timeline = run.timeline();
        int start = Math.max(0, timeline.size() - LiveSnapshot.MAX_ITEMS);
        GenRow[] rows = new GenRow[timeline.size() - start];
        for (int index = start; index < timeline.size(); index++) {
            GenRow row = new GenRow();
            row.title = GenCardParser.clip(GenCardParser.collapse(timeline.get(index).label()), GenSchema.ROW_TITLE);
            boolean last = index == timeline.size() - 1;
            row.status = last && run.active() ? GenSchema.STATUS_ACTIVE : GenSchema.STATUS_OK;
            rows[index - start] = row;
        }
        snapshot.items = rows;
        String outcome = blankToNull(run.outcome());
        snapshot.text = outcome == null ? null : GenCardParser.clip(GenCardParser.collapse(outcome), GenSchema.TEXT);
        return snapshot;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String capitalize(String value) {
        if (value == null || value.isEmpty()) return "Running";
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    @Override protected void closeClient() {
        if (client != null) client.close();
        client = null;
    }
}
