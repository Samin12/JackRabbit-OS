package com.resonolabs.feature.genui;

import android.graphics.Canvas;
import android.graphics.Paint;

import com.resonolabs.ui.input.UiInputIntent;

/**
 * The Voice-page card layer (genui.md section 5). All coordinates are in the 480x640
 * logical space. Per frame the host calls {@link #layout} (cheap; rebuilds a layout only when
 * a card revision changes), positions its orb from {@link #orbCenter()} / {@link #orbRadiusCap()},
 * then calls {@link #draw}. Input: {@link #onInput} (wheel/back), {@link #onTap}, {@link #onDrag}.
 *
 * <pre>
 * NONE      no cards                     orb (240,290)
 * COMPACT   pill (24,462)-(456,530)      orb (240,262), "n of m" at y 544
 * CARDS     bottom-anchored at 546       orb y = 106 + (stackTop-106)/2, r = clamp((stackTop-112)/2-14, 30, 72)
 * EXPANDED  card (16,84)-(464,546)       mini orb (240,44) r 22, chrome hidden
 * </pre>
 */
public final class GenCardOverlay {
    public enum Dock { NONE, COMPACT, CARDS, EXPANDED }

    public static final float WIDTH = 480f;
    public static final float STACK_TOP_LIMIT = 192f;
    public static final float STACK_BOTTOM = 546f;
    public static final float CARD_LEFT = 24f;
    public static final float CARD_RIGHT = 456f;
    public static final float PEEK = 8f;
    public static final float MIN_CARD_HEIGHT = 120f;
    public static final float PILL_TOP = 462f;
    public static final float PILL_BOTTOM = PILL_TOP + GenCardLayout.PILL_HEIGHT;
    public static final float EXPANDED_LEFT = 16f;
    public static final float EXPANDED_TOP = 84f;
    public static final float EXPANDED_RIGHT = 464f;
    public static final float EXPANDED_BOTTOM = 546f;
    public static final float COMPACT_ORB_Y = 262f;
    public static final float NONE_ORB_Y = 290f;
    public static final float EXPANDED_ORB_Y = 44f;
    public static final float EXPANDED_ORB_R = 22f;
    static final float SWIPE = 55f;
    static final float WHEEL_SCROLL = 60f;
    static final long ARRIVE_MS = 260L;
    static final float ARRIVE_OFFSET = 28f;
    private static final float CHIP_LEFT = 28f;
    private static final float CHIP_TOP = 128f;
    private static final float CHIP_RIGHT = 150f;
    private static final float CHIP_BOTTOM = 164f;

    private final GenUiController controller;
    private final GenCardStore store;
    private final GenCardRenderer renderer;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final char[] text = new char[24];
    private GenCardLayout cardLayout = new GenCardLayout();
    private GenCardLayout pillLayout = new GenCardLayout();
    private GenCardLayout expandedLayout = new GenCardLayout();

    private Dock dock = Dock.NONE;
    private boolean transcriptOpen;
    private int peeks;
    private float targetTop = STACK_BOTTOM;
    private float shownTop = Float.NaN;
    private long lastFrame;
    private GenCard expanded;
    private float scroll;
    private boolean immersive;
    private GenCard lastFront;
    private long frontChangedAt;
    private float arriveFrom;
    private float drag;
    private boolean dragCycled;
    private int pendingDirection;

    public GenCardOverlay(GenUiController controller) {
        this(controller, new GenCardRenderer());
    }

    public GenCardOverlay(GenUiController controller, GenCardRenderer renderer) {
        this.controller = controller;
        this.store = controller.store();
        this.renderer = renderer;
    }

    public GenCardRenderer renderer() {
        return renderer;
    }

    public Dock dock() {
        return dock;
    }

    // ------------------------------------------------------------------ per-frame layout

    /** Call once per frame before positioning the orb. */
    public void layout(boolean sessionLive, boolean transcriptOpen) {
        long now = store.now();
        store.tick(now);
        LiveSourceRegistry registry = controller.registry();
        if (registry != null) registry.tick(now);
        this.transcriptOpen = transcriptOpen;
        GenCard front = store.front();
        if (expanded != null && !store.inStack(expanded)) expanded = null;

        Dock next;
        if (front == null || transcriptOpen) next = Dock.NONE;
        else if (expanded != null) next = Dock.EXPANDED;
        else if (front.showsAsPill(sessionLive)) next = Dock.COMPACT;
        else next = Dock.CARDS;
        setImmersive(next == Dock.EXPANDED);

        if (front != lastFront) {
            // New arrival rises from below; wheel/swipe cycling comes from the direction moved.
            arriveFrom = pendingDirection < 0 ? -ARRIVE_OFFSET : ARRIVE_OFFSET;
            frontChangedAt = lastFront == null && dock == Dock.NONE && next == Dock.NONE ? 0L : now;
            lastFront = front;
            pendingDirection = 0;
        }
        if (front != null && front.arrivedAt > 0L && now - front.arrivedAt < ARRIVE_MS && frontChangedAt < front.arrivedAt) {
            frontChangedAt = front.arrivedAt;
            arriveFrom = ARRIVE_OFFSET;
        }
        Dock previous = dock;
        dock = next;

        switch (dock) {
            case CARDS -> {
                peeks = Math.min(2, store.stackSize() - 1);
                float budget = STACK_BOTTOM - STACK_TOP_LIMIT - PEEK * peeks;
                cardLayout = renderer.layout(cardLayout, front, GenCardLayout.MODE_CARD, CARD_RIGHT - CARD_LEFT, budget);
                float height = Math.max(MIN_CARD_HEIGHT, Math.min(budget, cardLayout.measuredHeight));
                cardLayout.height = height;
                targetTop = STACK_BOTTOM - height;
            }
            case COMPACT -> {
                peeks = 0;
                pillLayout = renderer.layout(pillLayout, front, GenCardLayout.MODE_PILL, CARD_RIGHT - CARD_LEFT,
                        GenCardLayout.PILL_HEIGHT);
                targetTop = PILL_TOP;
            }
            case EXPANDED -> {
                peeks = 0;
                expandedLayout = renderer.layout(expandedLayout, expanded, GenCardLayout.MODE_EXPANDED,
                        EXPANDED_RIGHT - EXPANDED_LEFT, EXPANDED_BOTTOM - EXPANDED_TOP);
                clampScroll();
                targetTop = EXPANDED_TOP;
            }
            case NONE -> {
                peeks = 0;
                targetTop = STACK_BOTTOM;
            }
        }
        // Ease the stack top (card height changes, dock switches) with a frame-rate independent filter.
        long dt = lastFrame == 0L ? 0L : Math.min(100L, now - lastFrame);
        lastFrame = now;
        if (Float.isNaN(shownTop) || previous != dock && (previous == Dock.NONE || dock == Dock.EXPANDED
                || previous == Dock.EXPANDED)) {
            shownTop = targetTop;
        } else {
            float k = 1f - (float) Math.exp(-dt / 70.0);
            shownTop += (targetTop - shownTop) * k;
            if (Math.abs(targetTop - shownTop) < 0.3f) shownTop = targetTop;
        }
    }

    private void setImmersive(boolean value) {
        if (immersive == value) return;
        immersive = value;
        controller.host().setImmersive(value);
    }

    // ------------------------------------------------------------------ geometry for the host

    /** Top of the topmost peek strip (CARDS), pill top (COMPACT), card top (EXPANDED), else 640. */
    public float stackTop() {
        return switch (dock) {
            case CARDS -> shownTop - PEEK * peeks;
            case COMPACT -> PILL_TOP;
            case EXPANDED -> EXPANDED_TOP;
            case NONE -> 640f;
        };
    }

    public float orbCenter() {
        return switch (dock) {
            case CARDS -> 106f + (stackTop() - 106f) / 2f;
            case COMPACT -> COMPACT_ORB_Y;
            case EXPANDED -> EXPANDED_ORB_Y;
            case NONE -> NONE_ORB_Y;
        };
    }

    /** Max orb radius for this dock; {@code Float.MAX_VALUE} means "use the normal radius". */
    public float orbRadiusCap() {
        return switch (dock) {
            case CARDS -> Math.max(30f, Math.min(72f, (stackTop() - 112f) / 2f - 14f));
            case EXPANDED -> EXPANDED_ORB_R;
            default -> Float.MAX_VALUE;
        };
    }

    /** CARDS/EXPANDED: the status line goes right of the orb at this x (14 px muted, baseline orbY + 5). */
    public float statusX(float orbRadius) {
        return 240f + orbRadius + 16f;
    }

    public boolean isAnimating() {
        long now = store.now();
        return Math.abs(targetTop - shownTop) > 0.3f || now - frontChangedAt < ARRIVE_MS;
    }

    // ------------------------------------------------------------------ draw

    public void draw(Canvas canvas, long now) {
        LiveSourceRegistry registry = controller.registry();
        if (registry != null && dock != Dock.NONE) registry.markVisible();
        GenCard front = store.front();
        if (front == null) return;
        float offset = arriveOffset(now);
        switch (dock) {
            case NONE -> {
                if (transcriptOpen) drawChip(canvas);
            }
            case COMPACT -> {
                renderer.drawPill(canvas, pillLayout, CARD_LEFT, PILL_TOP + offset, now);
                int count = store.stackSize();
                if (count > 1) {
                    int n = writeOf(store.frontIndex() + 1, count);
                    Paint label = renderer.fonts.counter;
                    label.setTextSize(12f);
                    label.setColor(GenColors.MUTED);
                    label.setTextAlign(Paint.Align.CENTER);
                    canvas.drawText(text, 0, n, 240f, PILL_BOTTOM + 15f, label);
                    label.setTextAlign(Paint.Align.LEFT);
                }
            }
            case CARDS -> {
                float top = shownTop;
                for (int depth = peeks; depth >= 1; depth--) {
                    float inset = 12f * depth;
                    float peekTop = top - PEEK * depth;
                    renderer.drawPeek(canvas, store.stackCard(depth), CARD_LEFT + inset, peekTop,
                            CARD_RIGHT - inset, peekTop + 48f, depth, now);
                }
                cardLayout.height = STACK_BOTTOM - top;
                renderer.drawCard(canvas, cardLayout, CARD_LEFT, top + offset, now, store.frontIndex(),
                        store.stackSize(), 0f);
            }
            case EXPANDED -> renderer.drawCard(canvas, expandedLayout, EXPANDED_LEFT, EXPANDED_TOP, now, 0, 1, scroll);
        }
    }

    private float arriveOffset(long now) {
        long age = now - frontChangedAt;
        if (frontChangedAt == 0L || age >= ARRIVE_MS || age < 0L) return 0f;
        float t = age / (float) ARRIVE_MS;
        float eased = 1f - (1f - t) * (1f - t) * (1f - t);
        return arriveFrom * (1f - eased);
    }

    private void drawChip(Canvas canvas) {
        int count = store.stackSize();
        renderer.glass.draw(canvas, paint, CHIP_LEFT, CHIP_TOP, CHIP_RIGHT, CHIP_BOTTOM, 18f, true);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.8f);
        paint.setColor(GenColors.ORB_PALE);
        canvas.drawRoundRect(CHIP_LEFT + 14f, CHIP_TOP + 11f, CHIP_LEFT + 30f, CHIP_TOP + 25f, 3f, 3f, paint);
        paint.setStyle(Paint.Style.FILL);
        int n = writeCards(count);
        Paint label = renderer.fonts.counter;
        label.setTextSize(14f);
        label.setColor(GenColors.INK);
        canvas.drawText(text, 0, n, CHIP_LEFT + 40f, CHIP_TOP + 23f, label);
        label.setTextSize(12f);
    }

    // ------------------------------------------------------------------ input

    /** Wheel and BACK. ACTIVATE is never consumed (it keeps starting/stopping voice). */
    public boolean onInput(UiInputIntent intent) {
        if (intent == UiInputIntent.ACTIVATE) return false;
        switch (dock) {
            case EXPANDED -> {
                if (intent == UiInputIntent.NEXT) scrollBy(WHEEL_SCROLL);
                else if (intent == UiInputIntent.PREVIOUS) scrollBy(-WHEEL_SCROLL);
                else collapse();
                return true;
            }
            case CARDS -> {
                if (intent == UiInputIntent.BACK) {
                    backOnFront();
                    return true;
                }
                return cycle(intent == UiInputIntent.NEXT ? 1 : -1);
            }
            case COMPACT -> {
                GenCard front = store.front();
                if (intent == UiInputIntent.BACK) {
                    if (front != null && front.isTimer() && front.timerBlock() != null && front.timerBlock().done) {
                        controller.stopTimer(front);
                        return true;
                    }
                    return false;
                }
                return cycle(intent == UiInputIntent.NEXT ? 1 : -1);
            }
            default -> {
                return false;
            }
        }
    }

    private boolean cycle(int direction) {
        if (store.stackSize() < 2) return false;
        pendingDirection = direction;
        store.cycle(direction);
        controller.host().invalidateUi();
        return true;
    }

    /** BACK on a full card: un-promote a pill, minimize running live work, else dismiss. */
    private void backOnFront() {
        GenCard front = store.front();
        if (front == null) return;
        if (front.presentation > 0) front.presentation = 0;
        else if (front.isRunningLive()) front.presentation = -1;
        else controller.dismissByUser(front);
        controller.host().invalidateUi();
    }

    public void expand(GenCard card) {
        expanded = card;
        scroll = 0f;
        drag = 0f;
        controller.host().invalidateUi();
    }

    public void collapse() {
        expanded = null;
        scroll = 0f;
        controller.host().invalidateUi();
    }

    /** True if a touch starting here belongs to the card layer (so the host routes drags to us). */
    public boolean contains(float x, float y) {
        return switch (dock) {
            case CARDS -> x >= CARD_LEFT && x <= CARD_RIGHT && y >= stackTop() && y <= STACK_BOTTOM;
            case COMPACT -> x >= CARD_LEFT && x <= CARD_RIGHT && y >= PILL_TOP && y <= PILL_BOTTOM + 24f;
            case EXPANDED -> x >= EXPANDED_LEFT && x <= EXPANDED_RIGHT && y >= EXPANDED_TOP && y <= EXPANDED_BOTTOM;
            case NONE -> transcriptOpen && chipAt(x, y);
        };
    }

    /** TRANSCRIPT mode: the "▣ 2 cards" chip; the host closes the transcript when it's tapped. */
    public boolean chipAt(float x, float y) {
        return store.stackSize() > 0 && x >= CHIP_LEFT - 6f && x <= CHIP_RIGHT + 6f
                && y >= CHIP_TOP - 8f && y <= CHIP_BOTTOM + 8f;
    }

    /** Vertical drag; {@code dy} = previous y - current y (positive when the finger moves up). */
    public boolean onDrag(float dy) {
        if (dock == Dock.EXPANDED) {
            scrollBy(dy);
            return true;
        }
        if (dock != Dock.CARDS && dock != Dock.COMPACT) return false;
        if (dragCycled) return true; // one card per swipe, however long the swipe
        drag += dy;
        if (drag >= SWIPE) {
            dragCycled = true;
            cycle(1);
        } else if (drag <= -SWIPE) {
            dragCycled = true;
            cycle(-1);
        }
        return true;
    }

    public void onDragEnd() {
        drag = 0f;
        dragCycled = false;
    }

    public boolean onTap(float x, float y) {
        return switch (dock) {
            case CARDS -> tapCards(x, y);
            case COMPACT -> tapPill(x, y);
            case EXPANDED -> tapExpanded(x, y);
            case NONE -> false;
        };
    }

    private boolean tapCards(float x, float y) {
        GenCard card = store.front();
        if (card == null || x < CARD_LEFT || x > CARD_RIGHT || y > STACK_BOTTOM) return false;
        float top = shownTop;
        if (y < top) {
            if (y >= stackTop() - 6f) return cycle(1); // tap a peek strip = bring the next card forward
            return false;
        }
        float lx = x - CARD_LEFT;
        float ly = y - top;
        GenCardLayout layout = cardLayout;
        if (layout.closeAt(lx, ly)) {
            controller.dismissByUser(card);
            return true;
        }
        int action = layout.actionAt(lx, ly);
        if (action >= 0) {
            runAction(card, card.actions.get(action));
            return true;
        }
        GenBlock image = layout.imageAt(lx, ly, 0f);
        if (image != null) {
            controller.onImageTapped(card, image);
            return true;
        }
        if (layout.moreAt(lx, ly)) {
            expand(card);
            return true;
        }
        int row = layout.rowAt(lx, ly, 0f);
        if (row >= 0 && rowTappable(layout, row)) {
            controller.onRowTapped(card, layout.boxBlock(row >> 8), row & 0xFF);
            return true;
        }
        if (layout.overflow) expand(card);
        else {
            card.pulseAt = store.now();
            controller.onCardFocused(card);
            controller.host().invalidateUi();
        }
        return true;
    }

    private boolean tapPill(float x, float y) {
        GenCard card = store.front();
        if (card == null || x < CARD_LEFT || x > CARD_RIGHT) return false;
        if (y > PILL_BOTTOM && y <= PILL_BOTTOM + 26f && store.stackSize() > 1) return cycle(1);
        if (y < PILL_TOP || y > PILL_BOTTOM) return false;
        float lx = x - CARD_LEFT;
        float ly = y - PILL_TOP;
        GenCardLayout layout = pillLayout;
        GenBlock timer = card.isTimer() ? card.timerBlock() : null;
        if (layout.closeAt(lx, ly)) {
            controller.dismissByUser(card);
            return true;
        }
        if (layout.verbAt(lx, ly)) {
            if (timer != null && timer.done) controller.stopTimer(card);
            else if (!card.actions.isEmpty()) runAction(card, card.actions.get(0));
            return true;
        }
        // Body: open the full card (works on the idle page too).
        card.presentation = 1;
        card.arrivedAt = store.now();
        controller.host().invalidateUi();
        return true;
    }

    private boolean tapExpanded(float x, float y) {
        GenCard card = expanded;
        if (card == null || x < EXPANDED_LEFT || x > EXPANDED_RIGHT || y < EXPANDED_TOP || y > EXPANDED_BOTTOM) {
            return false;
        }
        float lx = x - EXPANDED_LEFT;
        float ly = y - EXPANDED_TOP;
        GenCardLayout layout = expandedLayout;
        if (layout.closeAt(lx, ly)) {
            controller.dismissByUser(card);
            collapse();
            return true;
        }
        int action = layout.actionAt(lx, ly);
        if (action >= 0) {
            runAction(card, card.actions.get(action));
            return true;
        }
        if (ly < layout.bodyTop) {
            collapse();
            return true;
        }
        GenBlock image = layout.imageAt(lx, ly, scroll);
        if (image != null) {
            controller.onImageTapped(card, image);
            return true;
        }
        int row = layout.rowAt(lx, ly, scroll);
        if (row >= 0 && rowTappable(layout, row)) {
            controller.onRowTapped(card, layout.boxBlock(row >> 8), row & 0xFF);
        }
        return true;
    }

    private static boolean rowTappable(GenCardLayout layout, int row) {
        GenBlock block = layout.boxBlock(row >> 8);
        return block != null && (block.type == GenBlock.Type.CHECKLIST || block.rowSay != null);
    }

    private void runAction(GenCard card, GenAction action) {
        if (action.kind == GenAction.Kind.DISMISS && card == expanded) collapse();
        if (action.kind == GenAction.Kind.DISMISS && card.isTimer()) {
            controller.stopTimer(card);
            return;
        }
        controller.onAction(card, action);
    }

    private void scrollBy(float delta) {
        scroll += delta;
        clampScroll();
        controller.host().invalidateUi();
    }

    private void clampScroll() {
        GenCardLayout layout = expandedLayout;
        float viewport = layout.bodyBottom - layout.bodyTop;
        float max = Math.max(0f, layout.contentHeight - viewport);
        scroll = Math.max(0f, Math.min(max, scroll));
    }

    private int writeOf(int index, int total) {
        int n = writeInt(index, 0);
        text[n++] = ' ';
        text[n++] = 'o';
        text[n++] = 'f';
        text[n++] = ' ';
        return writeInt(total, n);
    }

    private int writeCards(int count) {
        int n = writeInt(count, 0);
        String word = count == 1 ? " card" : " cards";
        for (int index = 0; index < word.length(); index++) text[n++] = word.charAt(index);
        return n;
    }

    private int writeInt(int value, int at) {
        int n = at;
        if (value >= 10) text[n++] = (char) ('0' + (value / 10) % 10);
        text[n++] = (char) ('0' + value % 10);
        return n;
    }
}
