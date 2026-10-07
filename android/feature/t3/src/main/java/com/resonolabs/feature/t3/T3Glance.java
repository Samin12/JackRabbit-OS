package com.resonolabs.feature.t3;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Read-only glance over {@code GET /v1/t3/threads} for surfaces outside the T3 tab (the Cards
 * board widget). It reuses the tab's own status vocabulary, colors, ordering and relative times,
 * so a thread looks the same everywhere. Pure Java (org.json only); parse off the draw path.
 *
 * <p>Rows: threads that need the user first (approval, then question), then working, then
 * unsettled failures, then finished-but-unread ones, each most recent first. When none of those
 * exist the single most recently active thread is offered as a quiet row ({@link #lastOnly}).
 */
public final class T3Glance {
    public static final int AMBER = T3Status.AMBER;
    public static final int BLUE = T3Status.BLUE;
    public static final int GREEN = T3Status.GREEN;
    public static final int QUIET = T3Status.QUIET;
    public static final int RED = T3Status.RED;

    /** One thread as the glance shows it. */
    public static final class Row {
        public final String id;
        public final String title;
        public final String project;
        /** Phase for working threads (when known), otherwise the runtime's status label. */
        public final String label;
        public final int color;
        public final boolean needsYou;
        public final boolean working;
        public final boolean failed;
        public final boolean unread;
        /** Finished and already seen: drawn quieter. */
        public final boolean quiet;
        /** 0..1, or negative when unknown. */
        public final double progress;
        public final long updatedAt;

        Row(T3Model.Summary thread) {
            id = thread.id;
            title = thread.title;
            project = thread.projectTitle;
            working = T3Status.working(thread.status);
            label = working && !thread.phase.isEmpty() ? thread.phase : thread.label();
            color = thread.color();
            needsYou = T3Status.needsYou(thread.status);
            failed = T3Status.ERROR.equals(thread.status);
            unread = thread.unread;
            quiet = T3Status.DONE.equals(thread.status) && !thread.unread;
            progress = working ? thread.progress : -1d;
            updatedAt = thread.updatedAt;
        }

        /** "SamRabbit · 4m" (project and relative time, either may be missing). */
        public String meta(long nowMillis) {
            String when = T3Time.relative(nowMillis, updatedAt);
            if (project.isEmpty()) return when;
            return when.isEmpty() ? project : project + " · " + when;
        }
    }

    /** One colored piece of the headline ("2 need you", "1 working"). */
    public static final class Part {
        public final String text;
        public final int color;

        Part(String text, int color) {
            this.text = text;
            this.color = color;
        }
    }

    public final boolean connected;
    public final long revision;
    public final int needsYou;
    public final int working;
    public final int failed;
    public final int threadCount;
    public final List<Row> rows;
    /** Threads worth a look (needs you, working, failed, unread) that did not fit in {@link #rows}. */
    public final int more;
    /** True when {@link #rows} only holds the most recent thread because nothing is going on. */
    public final boolean lastOnly;

    private T3Glance(boolean connected, long revision, T3Model.Counts counts, int threadCount, List<Row> rows,
                     int more, boolean lastOnly) {
        this.connected = connected;
        this.revision = revision;
        this.needsYou = counts.needsYou;
        this.working = counts.working;
        this.failed = counts.error;
        this.threadCount = threadCount;
        this.rows = Collections.unmodifiableList(rows);
        this.more = more;
        this.lastOnly = lastOnly;
    }

    public static T3Glance from(JSONObject snapshot, int maxRows) {
        T3Model.Snapshot parsed = T3Model.Snapshot.from(snapshot == null ? new JSONObject() : snapshot);
        List<T3Model.Summary> worth = new ArrayList<>();
        T3Model.Summary latest = null;
        for (T3Model.Summary thread : parsed.threads) {
            boolean active = T3Status.needsYou(thread.status) || T3Status.working(thread.status)
                    || T3Status.ERROR.equals(thread.status) || thread.unread;
            if (active) worth.add(thread);
            if (latest == null || thread.updatedAt > latest.updatedAt) latest = thread;
        }
        worth.sort((a, b) -> {
            int rank = Integer.compare(rank(a), rank(b));
            return rank != 0 ? rank : Long.compare(b.updatedAt, a.updatedAt);
        });
        List<Row> rows = new ArrayList<>();
        int limit = Math.max(0, maxRows);
        for (int i = 0; i < worth.size() && rows.size() < limit; i++) rows.add(new Row(worth.get(i)));
        boolean lastOnly = false;
        if (rows.isEmpty() && latest != null && limit > 0) {
            rows.add(new Row(latest));
            lastOnly = true;
        }
        int more = Math.max(0, worth.size() - (lastOnly ? 0 : rows.size()));
        return new T3Glance(parsed.connected, parsed.revision, parsed.counts, parsed.threads.size(), rows, more,
                lastOnly);
    }

    /** needs-approval, needs-input, working, error, then finished-unread. */
    private static int rank(T3Model.Summary thread) {
        return T3Status.rank(thread.status);
    }

    /** "2 need you", "1 working", "1 failed" in their status colors, or "All caught up". */
    public List<Part> headline() {
        List<Part> parts = new ArrayList<>();
        for (T3Sections.Part part : T3Sections.headline(new T3Model.Counts(needsYou, working, 0, failed))) {
            parts.add(new Part(part.text, part.color));
        }
        return parts;
    }

    /** Glanceable relative time, as on the T3 tab ("now", "4m", "3h", "Oct 3"). */
    public static String relative(long nowMillis, long thenMillis) {
        return T3Time.relative(nowMillis, thenMillis);
    }
}
