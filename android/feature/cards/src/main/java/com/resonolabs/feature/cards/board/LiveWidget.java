package com.resonolabs.feature.cards.board;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;

import com.resonolabs.feature.genui.GenBlock;
import com.resonolabs.feature.genui.GenCard;
import com.resonolabs.feature.genui.GenCardLayout;
import com.resonolabs.feature.genui.GenCardRenderer;
import com.resonolabs.feature.genui.GenCardStore;
import com.resonolabs.feature.genui.GenTimers;
import com.resonolabs.feature.genui.GenUiController;
import com.resonolabs.feature.genui.LiveBinding;
import com.resonolabs.feature.genui.LiveSourceRegistry;
import com.resonolabs.ui.design.SamTheme;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;

/**
 * GenUI cards from Voice at a glance: live work (timers ticking, T3 threads, background runs),
 * pinned cards and recent ones, as the same glass pills Cards &gt; Live uses. Hidden while the
 * card store is empty. A pill opens that card in Cards &gt; Live; its X dismisses it and a
 * finished timer's Stop silences it, exactly as on the Live page.
 */
public final class LiveWidget implements BoardWidget, GenCardStore.Listener {
    private static final int MAX_ROWS = 3;
    private static final float LABEL = 46f;
    private static final float PILL = GenCardLayout.PILL_HEIGHT;
    private static final float GAP = 10f;
    private static final float FOOTER = 44f;
    /** Shimmer of running T3 / background-run pills. */
    private static final long SHIMMER_FRAME_MS = 40L;
    /** Ringing (finished) timer pulse. */
    private static final long RING_FRAME_MS = 50L;
    /** Keeps the live-source registry marked visible (its grace is 1.5 s). */
    private static final long VISIBLE_FRAME_MS = 1_000L;

    private final BoardHost host;
    private final GenUiController controller;
    private final GenCardStore store;
    private final LiveSourceRegistry registry;
    private final GenCardRenderer renderer = new GenCardRenderer();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final IdentityHashMap<GenCard, GenCardLayout> layouts = new IdentityHashMap<>();
    private final List<Row> rows = new ArrayList<>();
    private LiveGlance glance;
    private float width;
    private String summary = "";
    private String moreText = "";
    private float moreTop;
    private boolean listening;

    private static final class Row {
        GenCard card;
        boolean recent;
        float top;
        GenCardLayout layout;
    }

    public LiveWidget(BoardHost host, GenUiController controller) {
        this.host = host;
        this.controller = controller;
        this.store = controller.store();
        this.registry = controller.registry();
    }

    @Override public String id() { return "live"; }

    // ------------------------------------------------------------- data

    @Override public void onShow() {
        if (!listening) {
            store.addListener(this);
            listening = true;
        }
        host.widgetChanged(this);
    }

    @Override public void onHide() {
        if (listening) {
            store.removeListener(this);
            listening = false;
        }
    }

    @Override public void onCardsChanged() {
        host.widgetChanged(this);
    }

    /** Pushed by the store listener; nothing to poll. */
    @Override public long refreshIntervalMs() { return 0L; }

    @Override public void refresh() { }

    // ------------------------------------------------------------- layout

    @Override public float measure(float width, long nowMs) {
        this.width = width;
        rows.clear();
        glance = LiveGlance.pick(store.liveAndPinned(), store.recent(), MAX_ROWS);
        if (glance.isEmpty()) {
            layouts.clear();
            return 0f;
        }
        summary = glance.summary();
        float y = LABEL;
        IdentityHashMap<GenCard, GenCardLayout> keep = new IdentityHashMap<>();
        for (int i = 0; i < glance.shown.size(); i++) {
            LiveGlance.Entry entry = glance.shown.get(i);
            Row row = new Row();
            row.card = entry.card;
            row.recent = entry.recent;
            row.top = y;
            row.layout = renderer.layout(layouts.get(entry.card), entry.card, GenCardLayout.MODE_ROW, width, PILL);
            keep.put(entry.card, row.layout);
            rows.add(row);
            y += PILL + GAP;
        }
        layouts.clear();
        layouts.putAll(keep);
        y -= GAP;
        if (glance.more > 0) {
            moreText = "+" + glance.more + " more";
            moreTop = y;
            y += FOOTER;
        } else {
            moreText = "";
            y += 4f;
        }
        return y;
    }

    // ------------------------------------------------------------- drawing

    @Override public void draw(Canvas canvas, long nowMs) {
        long now = store.now();
        store.tick(now);
        if (registry != null) {
            registry.markVisible();
            registry.tick(now);
        }
        BoardPaint.eyebrow(canvas, paint, "LIVE", 8f, 30f, 14f, SamTheme.withAlpha(SamTheme.INK, 200), Paint.Align.LEFT);
        BoardPaint.text(canvas, paint, summary, width - 28f, 30f, 15f, SamTheme.MUTED, Paint.Align.RIGHT,
                BoardPaint.REGULAR);
        chevron(canvas, width - 12f, 25f);
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            if (row.recent) {
                canvas.saveLayerAlpha(0f, row.top, width, row.top + PILL, 150);
                renderer.drawPill(canvas, row.layout, 0f, row.top, now);
                canvas.restore();
            } else {
                renderer.drawPill(canvas, row.layout, 0f, row.top, now);
            }
        }
        if (!moreText.isEmpty()) {
            BoardPaint.text(canvas, paint, moreText, 8f, moreTop + 30f, 16f, SamTheme.ORB_PALE, Paint.Align.LEFT,
                    BoardPaint.MEDIUM);
            BoardPaint.text(canvas, paint, "All live cards", width - 28f, moreTop + 30f, 15f, SamTheme.MUTED,
                    Paint.Align.RIGHT, BoardPaint.REGULAR);
            chevron(canvas, width - 12f, moreTop + 25f);
        }
    }

    private void chevron(Canvas canvas, float cx, float cy) {
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2.2f);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setColor(SamTheme.MUTED);
        canvas.drawLine(cx - 3f, cy - 6f, cx + 3f, cy, paint);
        canvas.drawLine(cx + 3f, cy, cx - 3f, cy + 6f, paint);
        paint.setStrokeCap(Paint.Cap.BUTT);
        paint.setStyle(Paint.Style.FILL);
    }

    /** Timers redraw on each second boundary, shimmers and ringing timers at ~20-25 fps. */
    @Override public long redrawDelayMs(long nowMs) {
        if (rows.isEmpty()) return -1L;
        long now = store.now();
        long delay = -1L;
        for (int i = 0; i < rows.size(); i++) {
            GenCard card = rows.get(i).card;
            if (card.live == null) continue;
            long wanted = VISIBLE_FRAME_MS;
            GenBlock timer = card.isTimer() ? card.timerBlock() : null;
            if (timer != null) {
                if (timer.done) wanted = RING_FRAME_MS;
                else if (!timer.paused) {
                    long remaining = GenTimers.remaining(timer, now);
                    // The clock rounds seconds up: it changes when remaining crosses a whole second.
                    wanted = (remaining - 1L) % 1000L + 12L;
                }
            } else if ((card.live.type == LiveBinding.Type.T3_THREAD || card.live.type == LiveBinding.Type.BACKGROUND_RUN)
                    && card.isRunningLive() && !card.livePaused && card.liveNote == null
                    && card.state != GenCard.State.STALE) {
                wanted = SHIMMER_FRAME_MS;
            }
            if (delay < 0L || wanted < delay) delay = wanted;
        }
        return delay;
    }

    // ------------------------------------------------------------- input

    @Override public int focusCount() {
        return rows.isEmpty() ? 0 : rows.size() + 1 + (moreText.isEmpty() ? 0 : 1);
    }

    @Override public void focusBounds(int index, RectF out) {
        if (index == 0) out.set(0f, 2f, width, LABEL - 4f);
        else if (index <= rows.size()) {
            Row row = rows.get(index - 1);
            out.set(-2f, row.top - 2f, width + 2f, row.top + PILL + 2f);
        } else out.set(0f, moreTop, width, moreTop + FOOTER);
    }

    @Override public float focusRadius(int index) {
        return index >= 1 && index <= rows.size() ? GenCardRenderer.PILL_RADIUS + 2f : 20f;
    }

    @Override public String focusKey(int index) {
        if (index == 0) return "h";
        if (index <= rows.size()) return "c:" + rows.get(index - 1).card.id;
        return "more";
    }

    @Override public boolean onTap(float x, float y) {
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            if (y < row.top - GAP / 2f || y > row.top + PILL + GAP / 2f) continue;
            float lx = x;
            float ly = Math.max(0f, Math.min(PILL, y - row.top));
            if (row.layout.closeAt(lx, ly)) {
                if (row.recent) store.removeRecent(row.card.id);
                else controller.dismissByUser(row.card);
                return true;
            }
            if (row.layout.verbAt(lx, ly) && row.card.isTimer()) {
                controller.stopTimer(row.card);
                return true;
            }
            host.openLiveCard(row.card.id);
            return true;
        }
        host.openLiveCard(null);
        return true;
    }

    @Override public boolean activate(int index) {
        if (index >= 1 && index <= rows.size()) host.openLiveCard(rows.get(index - 1).card.id);
        else host.openLiveCard(null);
        return true;
    }

    @Override public void close() {
        onHide();
        layouts.clear();
    }
}
