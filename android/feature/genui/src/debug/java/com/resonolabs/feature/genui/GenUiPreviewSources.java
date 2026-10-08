package com.resonolabs.feature.genui;

import android.os.Handler;
import android.os.Looper;

import java.time.ZoneId;
import java.util.ArrayList;

/** Debug-only scripted live sources so live cards can be judged without T3 or a running goal. */
final class GenUiPreviewSources implements LiveSourceRegistry.Factory {
    /** Live types that should use the real on-device sources instead of fakes. */
    private final boolean real;

    GenUiPreviewSources(boolean real) {
        this.real = real;
    }

    @Override public LiveSource create(LiveSourceRegistry registry, GenCard card) {
        if (real) return null;
        return switch (card.live.type) {
            case T3_THREAD -> "thr_done".equals(card.live.threadId)
                    ? new ScriptedSource(registry, card, t3DoneScript(), 1_000L, 0)
                    : new ScriptedSource(registry, card, t3Script(), 2_400L, 2);
            case BACKGROUND_RUN -> new ScriptedSource(registry, card, runScript(), 2_000L, 2);
            case CALENDAR_NEXT -> new CalendarFake(registry, card);
            default -> null; // timers and tasks are real
        };
    }

    /** Plays snapshots in order, then holds at {@code holdAt} (the visually interesting state). */
    static final class ScriptedSource extends LiveSource {
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final LiveSnapshot[] script;
        private final long stepMs;
        private final int holdAt;
        private final long startedWall = System.currentTimeMillis() - 372_000L;
        private int step;
        private final Runnable next = this::advance;

        ScriptedSource(LiveSourceRegistry registry, GenCard card, LiveSnapshot[] script, long stepMs, int holdAt) {
            super(registry, card);
            this.script = script;
            this.stepMs = stepMs;
            this.holdAt = holdAt;
        }

        @Override protected void onStart() {
            step = 0;
            apply();
            handler.postDelayed(next, stepMs);
        }

        @Override protected void onStop() {
            handler.removeCallbacks(next);
        }

        private void advance() {
            if (step < holdAt) step++;
            apply();
            handler.postDelayed(next, step >= holdAt ? 1_000L : stepMs);
        }

        private void apply() {
            LiveSnapshot snapshot = script[step];
            if (card.live.type == LiveBinding.Type.T3_THREAD && !snapshot.terminal) {
                long seconds = (System.currentTimeMillis() - startedWall) / 1000L;
                snapshot.subtitle = "Codex • running " + (seconds / 60) + "m " + (seconds % 60) + "s";
            }
            snapshot.applyTo(card, registry.store().now());
            publish();
        }
    }

    static LiveSnapshot snapshot(int status, boolean terminal, String subtitle, String phase, float progress,
                                 String text, GenRow... items) {
        LiveSnapshot snapshot = new LiveSnapshot();
        snapshot.status = status;
        snapshot.terminal = terminal;
        snapshot.subtitle = subtitle;
        snapshot.phase = phase;
        snapshot.progress = progress;
        snapshot.text = text;
        snapshot.items = items;
        return snapshot;
    }

    static GenRow row(String title, String detail, int status, String trailing) {
        GenRow row = new GenRow();
        row.title = title;
        row.detail = detail;
        row.status = status;
        row.trailing = trailing;
        return row;
    }

    static LiveSnapshot[] t3Script() {
        return new LiveSnapshot[]{
                snapshot(GenSchema.STATUS_ACTIVE, false, null, "Reading code", -1f, null,
                        row("Read auth middleware", null, GenSchema.STATUS_ACTIVE, null)),
                snapshot(GenSchema.STATUS_ACTIVE, false, null, "Patching", -1f, null,
                        row("Read auth middleware", null, GenSchema.STATUS_OK, null),
                        row("Patch redirect handling", "src/auth/redirect.ts +18 −4", GenSchema.STATUS_ACTIVE, null)),
                snapshot(GenSchema.STATUS_ACTIVE, false, null, "Running tests", -1f, null,
                        row("Read auth middleware", null, GenSchema.STATUS_OK, null),
                        row("Patch redirect handling", "src/auth/redirect.ts +18 −4", GenSchema.STATUS_OK, null),
                        row("Run test suite", null, GenSchema.STATUS_ACTIVE, "41/58")),
        };
    }

    static LiveSnapshot[] t3DoneScript() {
        return new LiveSnapshot[]{
                snapshot(GenSchema.STATUS_OK, true, "Codex • finished in 9m 40s", "Branch pushed", 1f,
                        "All 58 tests pass. Opened PR #212 for review.",
                        row("Patch redirect handling", null, GenSchema.STATUS_OK, null),
                        row("Run test suite", null, GenSchema.STATUS_OK, "58/58"),
                        row("Push branch", null, GenSchema.STATUS_OK, null)),
        };
    }

    static LiveSnapshot[] runScript() {
        return new LiveSnapshot[]{
                snapshot(GenSchema.STATUS_ACTIVE, false, "Working • 2 tools", "Searching the web", 0.2f, null,
                        row("Planned the research", null, GenSchema.STATUS_OK, null),
                        row("Searching recycling programs", null, GenSchema.STATUS_ACTIVE, null)),
                snapshot(GenSchema.STATUS_ACTIVE, false, "Working • 5 tools", "Reading sources", 0.45f, null,
                        row("Planned the research", null, GenSchema.STATUS_OK, null),
                        row("Searched recycling programs", null, GenSchema.STATUS_OK, null),
                        row("Reading 4 county pages", null, GenSchema.STATUS_ACTIVE, null)),
                snapshot(GenSchema.STATUS_ACTIVE, false, "Working • 7 tools", "Writing the summary", 0.7f, null,
                        row("Planned the research", null, GenSchema.STATUS_OK, null),
                        row("Searched recycling programs", null, GenSchema.STATUS_OK, null),
                        row("Read 4 county pages", null, GenSchema.STATUS_OK, null),
                        row("Writing the summary", null, GenSchema.STATUS_ACTIVE, null)),
        };
    }

    /** Synthetic upcoming events relative to now (so "in 25 min" is always true). */
    static final class CalendarFake extends LiveSource {
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final long base = System.currentTimeMillis();
        private final Runnable tick = this::apply;

        CalendarFake(LiveSourceRegistry registry, GenCard card) {
            super(registry, card);
        }

        @Override protected void onStart() {
            apply();
        }

        @Override protected void onStop() {
            handler.removeCallbacks(tick);
        }

        private void apply() {
            long minute = 60_000L;
            ArrayList<CalendarNextSource.Event> events = new ArrayList<>();
            long start = base + 25 * minute;
            events.add(new CalendarNextSource.Event("Design review", start, start + 30 * minute, "Zoom", false));
            events.add(new CalendarNextSource.Event("1:1 with Alex", start + 95 * minute, start + 125 * minute,
                    "Room 4B", false));
            events.add(new CalendarNextSource.Event("Dinner with Sam", start + 240 * minute, start + 330 * minute,
                    "Nopa", false));
            CalendarNextSource.apply(card, events, System.currentTimeMillis(), ZoneId.systemDefault());
            publish();
            handler.postDelayed(tick, 15_000L);
        }
    }
}
