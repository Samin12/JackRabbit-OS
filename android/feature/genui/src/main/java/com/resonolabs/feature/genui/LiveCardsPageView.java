package com.resonolabs.feature.genui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.MotionEvent;
import android.view.View;

import com.resonolabs.ui.design.SamTheme;
import com.resonolabs.ui.input.UiInputIntent;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;

/**
 * Cards &gt; Live: every live, pinned and recent card as glass pills (480x640 logical).
 * Wheel moves the selection, tap or ACTIVATE opens a card full-height (wheel scrolls it),
 * BACK closes it. In a card: X dismisses, the top-right pin toggles pinning, and recent cards
 * offer "Show on Voice". Top-left 60x80 stays free for the Cards back button.
 */
public final class LiveCardsPageView extends View implements GenCardStore.Listener, AutoCloseable {
    private static final float W = 480f;
    private static final float H = 640f;
    private static final float LIST_TOP = 104f;
    private static final float LIST_BOTTOM = 626f;
    private static final float ROW = GenCardLayout.PILL_HEIGHT;
    private static final float ROW_GAP = 12f;
    private static final float SECTION = 30f;
    private static final float DETAIL_LEFT = 16f;
    private static final float DETAIL_TOP = 88f;
    private static final float DETAIL_RIGHT = 464f;
    private static final float DETAIL_BOTTOM = 624f;

    private static final class Entry {
        GenCard card;
        /** What the detail view draws: the card itself, or for Recent a copy whose only action restores it. */
        GenCard view;
        boolean recent;
        String section;
        float top;
        GenCardLayout layout;
    }

    private final GenUiController controller;
    private final GenCardStore store;
    private final GenCardRenderer renderer = new GenCardRenderer();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final ArrayList<Entry> entries = new ArrayList<>();
    private final IdentityHashMap<GenCard, GenCardLayout> layouts = new IdentityHashMap<>();
    private GenCardLayout detailLayout = new GenCardLayout();
    private final GenAction showAgain = new GenAction("Show on Voice", GenAction.Style.PRIMARY,
            GenAction.Kind.SAY, null);
    private String counts = "";
    private int selected;
    private float scroll;
    private float contentHeight;
    private Entry detail;
    private float detailScroll;
    private float downX;
    private float downY;
    private float lastY;
    private boolean dragging;

    public LiveCardsPageView(Context context, GenUiController controller) {
        super(context);
        this.controller = controller;
        this.store = controller.store();
        setFocusable(true);
        setFocusableInTouchMode(true);
        setContentDescription("Live cards");
    }

    public void start() {
        store.addListener(this);
        rebuild();
        invalidate();
    }

    public void stop() {
        store.removeListener(this);
    }

    @Override public void close() {
        stop();
    }

    @Override public void onCardsChanged() {
        rebuild();
        invalidate();
    }

    private void rebuild() {
        GenCard keep = detail != null ? detail.card : null;
        entries.clear();
        List<GenCard> active = store.liveAndPinned();
        List<GenCard> recent = store.recent();
        int live = 0;
        int pinned = 0;
        for (GenCard card : active) if (card.live != null) live++;
        for (GenCard card : active) if (card.live == null && card.pinned) pinned++;
        counts = live + " live • " + pinned + " pinned • " + recent.size() + " recent";
        float y = 0f;
        y = section(y, "LIVE", active, true, false);
        y = section(y, "PINNED", active, false, false);
        y = section(y, "RECENT", recent, false, true);
        contentHeight = y;
        selected = Math.max(0, Math.min(selected, entries.size() - 1));
        layouts.keySet().retainAll(cardsOf(entries));
        Entry previous = detail;
        detail = null;
        if (keep != null) {
            for (Entry entry : entries) {
                if (entry.card == keep) {
                    detail = entry;
                    entry.view = previous != null && previous.view != null ? previous.view : keep;
                }
            }
        }
    }

    private float section(float y, String name, List<GenCard> cards, boolean live, boolean recent) {
        boolean first = true;
        for (GenCard card : cards) {
            if (!recent && live != (card.live != null)) continue;
            if (first) {
                y += SECTION;
                first = false;
            }
            Entry entry = new Entry();
            entry.card = card;
            entry.recent = recent;
            entry.section = entries.isEmpty() || !entries.get(entries.size() - 1).section.equals(name) ? name : null;
            if (entry.section == null) entry.section = "";
            entry.top = y;
            entries.add(entry);
            y += ROW + ROW_GAP;
        }
        return y;
    }

    private static ArrayList<GenCard> cardsOf(List<Entry> list) {
        ArrayList<GenCard> out = new ArrayList<>();
        for (Entry entry : list) out.add(entry.card);
        return out;
    }

    // ------------------------------------------------------------------ input

    public boolean onInput(UiInputIntent intent) {
        if (detail != null) {
            if (intent == UiInputIntent.BACK) closeDetail();
            else if (intent == UiInputIntent.NEXT) detailScroll += 60f;
            else if (intent == UiInputIntent.PREVIOUS) detailScroll -= 60f;
            else return false;
            invalidate();
            return true;
        }
        if (entries.isEmpty()) return false;
        switch (intent) {
            case NEXT -> select(selected + 1);
            case PREVIOUS -> select(selected - 1);
            case ACTIVATE -> openDetail(entries.get(selected));
            default -> {
                return false;
            }
        }
        invalidate();
        return true;
    }

    private void select(int index) {
        selected = Math.max(0, Math.min(entries.size() - 1, index));
        Entry entry = entries.get(selected);
        float viewport = LIST_BOTTOM - LIST_TOP;
        if (entry.top - SECTION < scroll) scroll = Math.max(0f, entry.top - SECTION);
        if (entry.top + ROW > scroll + viewport) scroll = entry.top + ROW - viewport;
    }

    private void openDetail(Entry entry) {
        detail = entry;
        detailScroll = 0f;
        if (entry.recent) {
            entry.view = entry.card.copy();
            entry.view.actions.clear();
            entry.view.actions.add(showAgain);
            entry.view.pinned = false;
        } else {
            entry.view = entry.card;
        }
    }

    private void closeDetail() {
        detail = null;
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        float x = event.getX() * W / Math.max(1f, getWidth());
        float y = event.getY() * H / Math.max(1f, getHeight());
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN -> {
                downX = x;
                downY = y;
                lastY = y;
                dragging = false;
            }
            case MotionEvent.ACTION_MOVE -> {
                if (dragging || Math.abs(y - downY) > 12f) {
                    dragging = true;
                    float dy = lastY - y;
                    if (detail != null) detailScroll += dy;
                    else scroll = Math.max(0f, Math.min(Math.max(0f, contentHeight - (LIST_BOTTOM - LIST_TOP)),
                            scroll + dy));
                    invalidate();
                }
                lastY = y;
            }
            case MotionEvent.ACTION_UP -> {
                if (!dragging && Math.abs(x - downX) < 24f && Math.abs(y - downY) < 24f) tap(x, y);
                dragging = false;
            }
            default -> { }
        }
        return true;
    }

    private void tap(float x, float y) {
        if (detail != null) {
            tapDetail(x, y);
        } else {
            for (int index = 0; index < entries.size(); index++) {
                Entry entry = entries.get(index);
                float top = LIST_TOP + entry.top - scroll;
                if (y >= top && y <= top + ROW && x >= 24f && x <= 456f) {
                    selected = index;
                    GenCardLayout layout = entry.layout;
                    if (layout != null && layout.closeAt(x - 24f, y - top)) remove(entry);
                    else openDetail(entry);
                    break;
                }
            }
        }
        invalidate();
    }

    private void tapDetail(float x, float y) {
        Entry entry = detail;
        GenCard card = entry.card;
        GenCard view = entry.view;
        if (x >= 404f && y <= 80f && !entry.recent) {
            store.setPinned(card, !card.pinned);
            return;
        }
        if (x < DETAIL_LEFT || x > DETAIL_RIGHT || y < DETAIL_TOP || y > DETAIL_BOTTOM) {
            if (y < DETAIL_TOP && x > 60f) closeDetail();
            return;
        }
        float lx = x - DETAIL_LEFT;
        float ly = y - DETAIL_TOP;
        GenCardLayout layout = detailLayout;
        if (layout.closeAt(lx, ly)) {
            remove(entry);
            closeDetail();
            return;
        }
        int action = layout.actionAt(lx, ly);
        if (action >= 0) {
            if (entry.recent) {
                store.restore(card);
                closeDetail();
            } else {
                controller.onAction(card, view.actions.get(action));
            }
            return;
        }
        int row = layout.rowAt(lx, ly, detailScroll);
        if (row >= 0 && !entry.recent) {
            GenBlock block = layout.boxBlock(row >> 8);
            if (block != null && (block.type == GenBlock.Type.CHECKLIST || block.rowSay != null)) {
                controller.onRowTapped(card, block, row & 0xFF);
            }
        }
    }

    private void remove(Entry entry) {
        if (entry.recent) store.removeRecent(entry.card.id);
        else controller.dismissByUser(entry.card);
    }

    // ------------------------------------------------------------------ draw

    @Override protected void onDraw(Canvas canvas) {
        long now = store.now();
        canvas.save();
        canvas.scale(getWidth() / W, getHeight() / H);
        store.tick(now);
        LiveSourceRegistry registry = controller.registry();
        if (registry != null) {
            registry.markVisible();
            registry.tick(now);
        }
        SamTheme.background(canvas, paint, W, H, 240f, 90f, 230f, SamTheme.ORB_BLUE);
        if (detail != null) drawDetail(canvas, now);
        else drawList(canvas, now);
        canvas.restore();
        if (isShown()) postInvalidateDelayed(33L);
    }

    private void drawList(Canvas canvas, long now) {
        SamTheme.text(canvas, paint, "Live", 240f, 56f, 26f, SamTheme.INK, Paint.Align.CENTER, true);
        SamTheme.text(canvas, paint, counts, 240f, 82f, 14f, SamTheme.MUTED, Paint.Align.CENTER, false);
        if (entries.isEmpty()) {
            SamTheme.text(canvas, paint, "No live cards", 240f, 316f, 22f, SamTheme.INK, Paint.Align.CENTER, true);
            SamTheme.text(canvas, paint, "Timers, live work and pinned cards", 240f, 346f, 16f, SamTheme.MUTED,
                    Paint.Align.CENTER, false);
            SamTheme.text(canvas, paint, "from Voice show up here.", 240f, 368f, 16f, SamTheme.MUTED,
                    Paint.Align.CENTER, false);
            return;
        }
        canvas.save();
        canvas.clipRect(0f, LIST_TOP - 6f, W, LIST_BOTTOM + 8f);
        for (int index = 0; index < entries.size(); index++) {
            Entry entry = entries.get(index);
            float top = LIST_TOP + entry.top - scroll;
            if (!entry.section.isEmpty()) {
                renderer.fonts.eyebrow.setColor(SamTheme.MUTED);
                canvas.drawText(entry.section, 32f, top - 10f, renderer.fonts.eyebrow);
            }
            if (top > LIST_BOTTOM + 8f || top + ROW < LIST_TOP - 6f) continue;
            GenCardLayout layout = layouts.get(entry.card);
            layout = renderer.layout(layout, entry.card, GenCardLayout.MODE_PILL, 432f, ROW);
            layouts.put(entry.card, layout);
            entry.layout = layout;
            if (entry.recent) {
                canvas.saveLayerAlpha(24f, top, 456f, top + ROW, 150);
                renderer.drawPill(canvas, layout, 24f, top, now);
                canvas.restore();
            } else {
                renderer.drawPill(canvas, layout, 24f, top, now);
            }
            if (index == selected) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(2f);
                paint.setColor(SamTheme.withAlpha(SamTheme.INK, 200));
                canvas.drawRoundRect(20f, top - 4f, 460f, top + ROW + 4f, 38f, 38f, paint);
                paint.setStyle(Paint.Style.FILL);
            }
        }
        canvas.restore();
    }

    private void drawDetail(Canvas canvas, long now) {
        Entry entry = detail;
        GenCard card = entry.view != null ? entry.view : entry.card;
        detailLayout = renderer.layout(detailLayout, card, GenCardLayout.MODE_EXPANDED,
                DETAIL_RIGHT - DETAIL_LEFT, DETAIL_BOTTOM - DETAIL_TOP);
        float viewport = detailLayout.bodyBottom - detailLayout.bodyTop;
        detailScroll = Math.max(0f, Math.min(Math.max(0f, detailLayout.contentHeight - viewport), detailScroll));
        String heading = entry.recent ? "Recent" : card.live != null ? "Live" : "Pinned";
        SamTheme.text(canvas, paint, heading, 240f, 56f, 22f, SamTheme.INK, Paint.Align.CENTER, true);
        if (!entry.recent) {
            renderer.glass.draw(canvas, paint, 404f, 28f, 452f, 76f, 24f, card.pinned);
            renderer.icons.draw(canvas, paint, GenSchema.indexOf(GenSchema.ICONS, "pin"), 428f, 52f, 22f,
                    card.pinned ? card.accent.text : SamTheme.INK, 2f);
        }
        renderer.drawCard(canvas, detailLayout, DETAIL_LEFT, DETAIL_TOP, now, 0, 1, detailScroll);
    }
}
