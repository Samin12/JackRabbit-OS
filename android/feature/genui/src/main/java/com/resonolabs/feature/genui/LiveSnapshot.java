package com.resonolabs.feature.genui;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Normalized live snapshot (genui.md section 4.1), the shape served by
 * {@code GET /v1/live/t3-thread/{id}} and produced by the app-side sources. The source owns
 * the card body; the model's title, eyebrow, accent and actions are kept.
 */
public final class LiveSnapshot {
    public static final int MAX_ITEMS = 4;

    public int status = GenSchema.STATUS_ACTIVE;
    public boolean terminal;
    public String subtitle;
    public String phase;
    /** 0..1, or negative for indeterminate. */
    public float progress = -1f;
    public GenRow[] items = new GenRow[0];
    public String text;
    public long nextPollMs;

    public static LiveSnapshot fromJson(JSONObject json) {
        LiveSnapshot snapshot = new LiveSnapshot();
        int status = GenSchema.indexOf(GenSchema.STATUSES, GenCardParser.value(json, "status"));
        snapshot.status = status < 0 ? GenSchema.STATUS_ACTIVE : status;
        snapshot.terminal = GenCardParser.bool(GenCardParser.value(json, "terminal"));
        snapshot.subtitle = GenCardParser.clean(GenCardParser.value(json, "subtitle"), GenSchema.SUBTITLE, null, null);
        snapshot.phase = GenCardParser.clean(GenCardParser.value(json, "phase"), GenSchema.LABEL, null, null);
        double progress = GenCardParser.number(GenCardParser.value(json, "progress"), Double.NaN);
        snapshot.progress = Double.isNaN(progress) ? -1f : (float) Math.max(0.0, Math.min(1.0, progress));
        snapshot.text = GenCardParser.clean(GenCardParser.value(json, "text"), GenSchema.TEXT, null, null);
        snapshot.nextPollMs = (long) GenCardParser.number(GenCardParser.value(json, "nextPollMs"), 0);
        JSONArray items = GenCardParser.array(GenCardParser.value(json, "items"));
        if (items != null) {
            int start = Math.max(0, items.length() - MAX_ITEMS); // most recent rows win
            java.util.ArrayList<GenRow> rows = new java.util.ArrayList<>();
            for (int index = start; index < items.length(); index++) {
                JSONObject item = GenCardParser.object(items.opt(index));
                if (item == null) continue;
                GenRow row = new GenRow();
                String title = GenCardParser.clean(GenCardParser.value(item, "title"), GenSchema.ROW_TITLE, null, null);
                if (title == null) continue;
                row.title = title;
                row.detail = GenCardParser.clean(GenCardParser.value(item, "detail"), GenSchema.ROW_DETAIL, null, null);
                row.trailing = GenCardParser.clean(GenCardParser.value(item, "trailing"), GenSchema.ROW_TRAILING, null, null);
                row.status = GenSchema.indexOf(GenSchema.STATUSES, GenCardParser.value(item, "status"));
                rows.add(row);
            }
            snapshot.items = rows.toArray(new GenRow[0]);
        }
        return snapshot;
    }

    /** Writes this snapshot into a live card: progress{phase}, list{steps}, text{last}. */
    public void applyTo(GenCard card, long now) {
        card.liveStatus = status;
        card.liveSubtitle = subtitle;
        card.liveNote = null;
        if (terminal && !card.terminal) card.terminalAt = now;
        card.terminal = terminal;
        card.state = terminal ? GenCard.State.DONE : GenCard.State.ACTIVE;
        card.body.clear();
        boolean ok = status == GenSchema.STATUS_OK;
        if (phase != null || progress >= 0f || (!terminal && status == GenSchema.STATUS_ACTIVE)) {
            GenBlock bar = new GenBlock(GenBlock.Type.PROGRESS);
            bar.id = "phase";
            bar.label = phase != null ? phase : terminal ? (ok ? "Done" : "Stopped") : "Working";
            bar.progress = terminal && ok ? 1f : progress;
            if (terminal && !ok && progress < 0f) bar.progress = 0f;
            card.body.add(bar);
        }
        if (items.length > 0) {
            GenBlock list = new GenBlock(GenBlock.Type.LIST);
            list.id = "steps";
            list.items = items;
            card.body.add(list);
        }
        if (text != null) {
            GenBlock last = new GenBlock(GenBlock.Type.TEXT);
            last.id = "last";
            last.style = GenSchema.STYLE_MUTED;
            last.text = text;
            card.body.add(last);
        }
    }
}
