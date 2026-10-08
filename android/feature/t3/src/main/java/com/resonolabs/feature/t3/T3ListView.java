package com.resonolabs.feature.t3;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.widget.OverScroller;

import com.resonolabs.ui.design.FluidOrb;
import com.resonolabs.ui.design.SamTheme;
import com.resonolabs.ui.input.UiInputIntent;

import java.util.ArrayList;
import java.util.List;

/**
 * T3 tab root: "2 need you · 1 working" header, a New orb button, and the thread list in
 * Needs you / Working / Recent sections. Wheel moves focus (above the first row is New), tap
 * opens. Drawn in the 480x640 logical space below the 100px product chrome.
 */
final class T3ListView extends View {
    interface Actions {
        void openThread(T3Model.Summary summary);
        void newThread();
        void openSettings();
        void retry();
    }

    enum Mode { LOADING, READY, UNCONFIGURED, REAUTH, FAILED, RUNTIME_DOWN }

    private static final float W = 480f;
    private static final float H = 640f;
    private static final float LIST_TOP = 172f;
    private static final float ROW_H = 76f;
    private static final float ROW_GAP = 8f;
    private static final float SECTION_H = 32f;
    private static final float BOTTOM_PAD = 28f;
    private static final RectF NEW_BUTTON = new RectF(350f, 110f, 462f, 158f);
    private static final RectF STATE_BUTTON = new RectF(96f, 548f, 384f, 600f);

    private static final class Row {
        final boolean section;
        final float top;
        final float height;
        final T3Model.Summary thread;
        final String text;
        final String meta;
        final String metaStatus;
        final int color;
        final String count;

        Row(boolean section, float top, float height, T3Model.Summary thread, String text, String metaStatus,
            String meta, int color, String count) {
            this.section = section;
            this.top = top;
            this.height = height;
            this.thread = thread;
            this.text = text;
            this.metaStatus = metaStatus;
            this.meta = meta;
            this.color = color;
            this.count = count;
        }
    }

    private final T3Surface surface = new T3Surface();
    private final FluidOrb newOrb = new FluidOrb().setColor(SamTheme.ORB_BLUE).setEnergy(0.35f).setSpeed(0.7f);
    private final FluidOrb heroOrb = new FluidOrb().setEnergy(0.3f).setSpeed(0.6f);
    private final OverScroller scroller;
    private final T3Toast toast;
    private final Actions actions;
    private final RectF rect = new RectF();
    private final List<Row> rows = new ArrayList<>();
    private final List<Row> threadRows = new ArrayList<>();
    private final List<T3Sections.Part> headline = new ArrayList<>();
    private final List<String> stateLines = new ArrayList<>();
    private T3Model.Snapshot snapshot;
    private Mode mode = Mode.LOADING;
    private String modeDetail = "";
    private String connectionLabel = "";
    private boolean demo;
    private String subline = "";
    private float headlineSize = 21f;
    private float contentHeight;
    private int focus;
    private String focusedId = "";
    private float scroll;
    private float scrollTarget;
    private long layoutAt;
    private VelocityTracker velocity;
    private float downX;
    private float downY;
    private float lastY;
    private boolean dragging;
    private boolean caughtMotion;
    /** Set when returning from a thread: the next layout focuses the top row. */
    private boolean focusTop;
    private boolean anyWorking;

    T3ListView(Context context, T3Toast toast, Actions actions) {
        super(context);
        this.toast = toast;
        this.actions = actions;
        this.scroller = new OverScroller(context);
        setFocusable(true);
        setFocusableInTouchMode(true);
        setContentDescription("T3 Code threads");
        relayoutState();
    }

    // ---- data ------------------------------------------------------------------------------

    void showSnapshot(T3Model.Snapshot next, String label, boolean demoData) {
        snapshot = next;
        connectionLabel = label == null ? "" : label;
        demo = demoData;
        mode = Mode.READY;
        relayout();
        if (threadRows.isEmpty()) relayoutState();
        invalidate();
    }

    void showMode(Mode next, String detail) {
        if (next == Mode.READY && snapshot == null) next = Mode.LOADING;
        if (mode == next && modeDetail.equals(detail == null ? "" : detail)) return;
        mode = next;
        modeDetail = detail == null ? "" : detail;
        if (mode == Mode.READY) relayout();
        else relayoutState();
        invalidate();
    }

    Mode mode() {
        return mode;
    }

    boolean hasThreads() {
        return snapshot != null && !snapshot.threads.isEmpty();
    }

    /** Back from a thread: focus the most urgent row and show the top of the list. */
    void resetFocus() {
        focus = threadRows.isEmpty() ? -1 : 0;
        rememberFocus();
        focusTop = true; // The refresh that follows may reorder rows; keep the top row focused.
        scroller.forceFinished(true);
        scrollTarget = 0f;
    }

    /** Keep a "can't reach T3" note in the header while still showing the last good list. */
    void showStale(String detail) {
        subline = detail;
        invalidate();
    }

    private void relayout() {
        rows.clear();
        threadRows.clear();
        headline.clear();
        layoutAt = System.currentTimeMillis();
        if (snapshot == null) return;
        headline.addAll(T3Sections.headline(snapshot.counts));
        headlineSize = 21f;
        while (headlineSize > 17f && headlineWidth() > NEW_BUTTON.left - 46f) headlineSize -= 0.5f;
        while (headline.size() > 1 && headlineWidth() > NEW_BUTTON.left - 46f) headline.remove(headline.size() - 1);
        String updated = T3Time.updated(layoutAt, snapshot.updatedAt > 0 ? snapshot.updatedAt : layoutAt);
        String source = demo ? "Demo data" : connectionLabel.isEmpty() ? "T3 Code" : connectionLabel;
        subline = surface.ellipsize(source + " · " + updated, NEW_BUTTON.left - 40f, 14f, T3Surface.REGULAR);

        float y = 0f;
        anyWorking = false;
        for (T3Sections.Section section : T3Sections.build(snapshot.threads)) {
            int color = switch (section.kind) {
                case NEEDS_YOU -> T3Status.AMBER;
                case WORKING -> T3Status.BLUE;
                case RECENT -> SamTheme.MUTED;
            };
            rows.add(new Row(true, y, SECTION_H, null, section.title.toUpperCase(java.util.Locale.ROOT), null,
                    null, color, String.valueOf(section.threads.size())));
            y += SECTION_H;
            for (T3Model.Summary thread : section.threads) {
                if (T3Status.working(thread.status)) anyWorking = true;
                String status = thread.label();
                if (T3Status.working(thread.status) && !thread.phase.isEmpty()) status = thread.phase;
                String when = T3Time.relative(layoutAt, thread.updatedAt);
                StringBuilder rest = new StringBuilder();
                if (!thread.projectTitle.isEmpty()) rest.append("·  ").append(thread.projectTitle);
                if (!when.isEmpty()) rest.append(rest.length() > 0 ? "  ·  " : "·  ").append(when);
                float statusWidth = surface.measure(status, 14f, T3Surface.MEDIUM);
                String shownStatus = statusWidth > 250f ? surface.ellipsize(status, 250f, 14f, T3Surface.MEDIUM) : status;
                float restWidth = 448f - 78f - surface.measure(shownStatus, 14f, T3Surface.MEDIUM);
                Row row = new Row(false, y, ROW_H, thread,
                        surface.ellipsize(thread.title, 448f - 72f, 18.5f, T3Surface.MEDIUM),
                        shownStatus, surface.ellipsize(rest.toString(), restWidth, 14f, T3Surface.REGULAR),
                        thread.color(), null);
                rows.add(row);
                threadRows.add(row);
                y += ROW_H + ROW_GAP;
            }
            y += 6f;
        }
        contentHeight = y + BOTTOM_PAD;
        if (focusTop) {
            focusTop = false;
            focus = threadRows.isEmpty() ? -1 : 0;
            rememberFocus();
            scroll = 0f;
            scrollTarget = 0f;
            return;
        }
        // Keep focus on the same thread across refreshes.
        int restored = -1;
        for (int i = 0; i < threadRows.size(); i++) {
            if (threadRows.get(i).thread.id.equals(focusedId)) restored = i;
        }
        if (focus >= 0) focus = restored >= 0 ? restored : Math.min(focus, threadRows.size() - 1);
        if (threadRows.isEmpty()) focus = -1;
        rememberFocus();
        scroll = Math.min(scroll, maxScroll());
        scrollTarget = Math.min(scrollTarget, maxScroll());
    }

    private float headlineWidth() {
        float width = 0f;
        for (int i = 0; i < headline.size(); i++) {
            if (i > 0) width += surface.measure("  ·  ", headlineSize, T3Surface.MEDIUM);
            width += surface.measure(headline.get(i).text, headlineSize, T3Surface.MEDIUM);
        }
        return width;
    }

    private void relayoutState() {
        stateLines.clear();
        String body = switch (mode) {
            case LOADING -> "Fetching your threads from T3 Code.";
            case RUNTIME_DOWN -> "Waiting for the SamRabbit runtime to start.";
            case FAILED -> (modeDetail.isEmpty() ? "" : modeDetail + "\n")
                    + "Check that T3 Code is open on your Mac and on this Wi-Fi. Retrying…";
            case READY -> "Start one here, or just ask Voice.";
            default -> "";
        };
        if (!body.isEmpty()) stateLines.addAll(surface.wrap(body, 400f, 16f, T3Surface.REGULAR, false));
    }

    private void rememberFocus() {
        focusedId = focus >= 0 && focus < threadRows.size() ? threadRows.get(focus).thread.id : "";
    }

    private float viewport() {
        return H - LIST_TOP;
    }

    private float maxScroll() {
        return Math.max(0f, contentHeight - viewport());
    }

    // ---- input -----------------------------------------------------------------------------

    boolean onInput(UiInputIntent intent) {
        if (intent == UiInputIntent.BACK) return false;
        if (mode != Mode.READY || threadRows.isEmpty()) {
            if (intent == UiInputIntent.ACTIVATE) activateState();
            return true;
        }
        if (intent == UiInputIntent.NEXT) moveFocus(1);
        else if (intent == UiInputIntent.PREVIOUS) moveFocus(-1);
        else if (intent == UiInputIntent.ACTIVATE) {
            if (focus < 0) actions.newThread();
            else actions.openThread(threadRows.get(focus).thread);
        }
        return true;
    }

    private void moveFocus(int delta) {
        focusTop = false;
        int next = Math.max(-1, Math.min(threadRows.size() - 1, focus + delta));
        if (next == focus) return;
        focus = next;
        rememberFocus();
        revealFocus();
        invalidate();
    }

    private void revealFocus() {
        scroller.forceFinished(true);
        if (focus <= 0) {
            scrollTarget = 0f;
            return;
        }
        Row row = threadRows.get(focus);
        float top = row.top;
        int index = rows.indexOf(row);
        if (index > 0 && rows.get(index - 1).section) top -= SECTION_H;
        float bottom = row.top + row.height + 10f;
        if (top - 6f < scrollTarget) scrollTarget = Math.max(0f, top - 6f);
        else if (bottom > scrollTarget + viewport()) scrollTarget = Math.min(maxScroll(), bottom - viewport());
    }

    private void activateState() {
        switch (mode) {
            case UNCONFIGURED, REAUTH -> actions.openSettings();
            case FAILED -> actions.retry();
            case READY -> actions.newThread();
            default -> { }
        }
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        float x = event.getX() * W / Math.max(1f, getWidth());
        float y = event.getY() * H / Math.max(1f, getHeight());
        if (velocity == null) velocity = VelocityTracker.obtain();
        velocity.addMovement(event);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN -> {
                // A touch that stops a fling (or the wheel's glide) is not also a tap.
                caughtMotion = !scroller.isFinished() || Math.abs(scrollTarget - scroll) > 2f;
                scroller.forceFinished(true);
                scrollTarget = scroll;
                downX = x;
                downY = y;
                lastY = y;
                dragging = false;
            }
            case MotionEvent.ACTION_MOVE -> {
                if (!dragging && Math.abs(y - downY) > 12f && mode == Mode.READY) dragging = true;
                if (dragging) {
                    scroll = clamp(scroll - (y - lastY), 0f, maxScroll());
                    scrollTarget = scroll;
                    invalidate();
                }
                lastY = y;
            }
            case MotionEvent.ACTION_UP -> {
                if (dragging) {
                    velocity.computeCurrentVelocity(1000);
                    float vy = velocity.getYVelocity() * H / Math.max(1f, getHeight());
                    scroller.fling(0, Math.round(scroll), 0, Math.round(-vy), 0, 0, 0, Math.round(maxScroll()));
                    invalidate();
                } else if (!caughtMotion || y < LIST_TOP) {
                    tap(x, y);
                }
                recycleVelocity();
            }
            case MotionEvent.ACTION_CANCEL -> recycleVelocity();
            default -> { }
        }
        return true;
    }

    private void recycleVelocity() {
        if (velocity != null) {
            velocity.recycle();
            velocity = null;
        }
        dragging = false;
    }

    private void tap(float x, float y) {
        focusTop = false;
        if (mode != Mode.READY || threadRows.isEmpty()) {
            if (mode != Mode.LOADING && mode != Mode.RUNTIME_DOWN && hit(STATE_BUTTON, x, y, 10f)) activateState();
            return;
        }
        if (hit(NEW_BUTTON, x, y, 8f)) {
            focus = -1;
            rememberFocus();
            invalidate();
            actions.newThread();
            return;
        }
        if (y < LIST_TOP) return;
        float contentY = y - LIST_TOP + scroll;
        for (int i = 0; i < threadRows.size(); i++) {
            Row row = threadRows.get(i);
            if (contentY >= row.top - ROW_GAP / 2f && contentY < row.top + row.height + ROW_GAP / 2f) {
                focus = i;
                rememberFocus();
                invalidate();
                actions.openThread(row.thread);
                return;
            }
        }
    }

    private static boolean hit(RectF rect, float x, float y, float slop) {
        return x >= rect.left - slop && x <= rect.right + slop && y >= rect.top - slop && y <= rect.bottom + slop;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    // ---- drawing ---------------------------------------------------------------------------

    @Override protected void onDraw(Canvas canvas) {
        long now = System.currentTimeMillis();
        if (mode == Mode.READY && snapshot != null && now - layoutAt > 30_000L) relayout();
        boolean moving = false;
        boolean animating = false;
        if (scroller.computeScrollOffset()) {
            scroll = clamp(scroller.getCurrY(), 0f, maxScroll());
            scrollTarget = scroll;
            moving = true;
        } else if (Math.abs(scrollTarget - scroll) > 0.5f) {
            scroll += (scrollTarget - scroll) * 0.28f;
            moving = true;
        } else {
            scroll = scrollTarget;
        }
        canvas.save();
        canvas.scale(getWidth() / W, getHeight() / H);
        int glow = mode == Mode.READY && snapshot != null && snapshot.counts.needsYou > 0
                ? T3Status.AMBER : SamTheme.ORB_BLUE;
        surface.background(canvas, W, H, 420f, 150f, 260f, glow);
        if (mode == Mode.READY && !threadRows.isEmpty()) {
            drawHeader(canvas);
            drawRows(canvas, now);
            animating |= anyWorking || focus < 0;
        } else {
            drawState(canvas);
            animating = true;
        }
        animating |= toast.draw(canvas, surface, 600f);
        canvas.restore();
        // Scrolling runs at vsync; ambient motion (orbs, spinners, toasts) at ~30 fps.
        if (moving && isShown()) postInvalidateOnAnimation();
        else if (animating && isShown()) postInvalidateDelayed(33L);
        else if (isShown()) postInvalidateDelayed(1_000L);
    }

    private void drawHeader(Canvas canvas) {
        float x = 24f;
        for (int i = 0; i < headline.size(); i++) {
            if (i > 0) {
                surface.text(canvas, "  ·  ", x, 137f, headlineSize, SamTheme.MUTED, Paint.Align.LEFT, T3Surface.MEDIUM);
                x += surface.measure("  ·  ", headlineSize, T3Surface.MEDIUM);
            }
            T3Sections.Part part = headline.get(i);
            surface.text(canvas, part.text, x, 137f, headlineSize, part.color, Paint.Align.LEFT, T3Surface.MEDIUM);
            x += surface.measure(part.text, headlineSize, T3Surface.MEDIUM);
        }
        surface.text(canvas, subline, 24f, 160f, 14f, SamTheme.MUTED, Paint.Align.LEFT, T3Surface.REGULAR);

        boolean focused = focus < 0;
        surface.glass(canvas, NEW_BUTTON, 24f, focused);
        // Frozen when nothing on screen moves, so an idle list costs ~nothing to keep up.
        newOrb.setEnergy(focused ? 0.7f : 0.35f).setSpeed(focused ? 1.3f : anyWorking ? 0.7f : 0f);
        newOrb.draw(canvas, 377f, 134f, 14f);
        surface.plus(canvas, 377f, 134f, 5.5f, SamTheme.BACKGROUND);
        surface.text(canvas, "New", 401f, 141f, 19f, SamTheme.INK, Paint.Align.LEFT, T3Surface.MEDIUM);
    }

    private void drawRows(Canvas canvas, long now) {
        canvas.save();
        canvas.clipRect(0f, LIST_TOP, W, H);
        float offset = LIST_TOP - scroll;
        for (Row row : rows) {
            float top = row.top + offset;
            if (top > H || top + row.height < LIST_TOP) continue;
            if (row.section) {
                surface.label(canvas, row.text, 26f, top + 21f, SamTheme.withAlpha(row.color, 230), Paint.Align.LEFT);
                surface.text(canvas, row.count, 454f, top + 21f, 13f, SamTheme.withAlpha(SamTheme.MUTED, 200),
                        Paint.Align.RIGHT, T3Surface.MEDIUM);
                continue;
            }
            drawThread(canvas, row, top, threadRows.indexOf(row) == focus, now);
        }
        canvas.restore();
        surface.fadeEdges(canvas, 0f, W, LIST_TOP, H, 16f, scroll > 1f, scroll < maxScroll() - 1f);
    }

    private void drawThread(Canvas canvas, Row row, float top, boolean focused, long now) {
        T3Model.Summary thread = row.thread;
        rect.set(16f, top, 464f, top + row.height);
        boolean attention = T3Status.needsYou(thread.status);
        if (attention && !focused) surface.tinted(canvas, rect, 22f, T3Status.AMBER, 16, 64);
        else surface.glass(canvas, rect, 22f, focused);
        boolean working = T3Status.working(thread.status);
        surface.statusOrb(canvas, 45f, top + row.height / 2f, 8.5f, row.color, working, now);
        boolean quiet = T3Status.DONE.equals(thread.status) && !thread.unread;
        surface.text(canvas, row.text, 72f, top + 33f, 18.5f,
                quiet ? SamTheme.withAlpha(SamTheme.INK, 196) : SamTheme.INK, Paint.Align.LEFT, T3Surface.MEDIUM);
        int statusColor = quiet ? SamTheme.MUTED : row.color;
        surface.text(canvas, row.metaStatus, 72f, top + 57f, 14f, statusColor, Paint.Align.LEFT, T3Surface.MEDIUM);
        float metaX = 78f + surface.measure(row.metaStatus, 14f, T3Surface.MEDIUM);
        surface.text(canvas, row.meta, metaX, top + 57f, 14f, SamTheme.MUTED, Paint.Align.LEFT, T3Surface.REGULAR);
        if (working && thread.progress >= 0d) {
            rect.set(72f, top + 65f, 448f, top + 67.5f);
            surface.solid(canvas, rect, 1.25f, SamTheme.withAlpha(SamTheme.INK, 26));
            rect.set(72f, top + 65f, 72f + (float) (376f * thread.progress), top + 67.5f);
            surface.solid(canvas, rect, 1.25f, SamTheme.withAlpha(T3Status.BLUE, 220));
        }
        if (thread.unread && T3Status.DONE.equals(thread.status)) {
            surface.paint.setColor(T3Status.GREEN);
            canvas.drawCircle(450f, top + 28f, 4f, surface.paint);
        }
    }

    private void drawState(Canvas canvas) {
        boolean setup = mode == Mode.UNCONFIGURED || mode == Mode.REAUTH;
        int color = switch (mode) {
            case UNCONFIGURED -> SamTheme.VIOLET;
            case REAUTH -> T3Status.AMBER;
            case FAILED -> T3Status.RED;
            case RUNTIME_DOWN -> SamTheme.MUTED;
            default -> SamTheme.ORB_BLUE;
        };
        float orbY = setup ? 186f : 262f;
        heroOrb.setColor(color).setEnergy(mode == Mode.LOADING ? 0.6f : 0.3f).setSpeed(mode == Mode.LOADING ? 1.4f : 0.6f);
        heroOrb.draw(canvas, 240f, orbY + heroOrb.bob(3f), setup ? 36f : 46f);
        String title = switch (mode) {
            case LOADING -> "Loading threads…";
            case UNCONFIGURED -> "Connect T3 Code";
            case REAUTH -> "Pair T3 Code again";
            case FAILED -> "Can't reach T3 Code";
            case RUNTIME_DOWN -> "Starting up…";
            case READY -> "No active threads";
        };
        float titleY = setup ? 266f : 356f;
        surface.text(canvas, title, 240f, titleY, 24f, SamTheme.INK, Paint.Align.CENTER, T3Surface.MEDIUM);
        if (setup) {
            String lead = mode == Mode.REAUTH ? "Your 30-day pairing ran out. Re-pair in 3 steps:"
                    : "See and answer your T3 threads here. 3 steps:";
            surface.text(canvas, lead, 240f, 292f, 15f, SamTheme.MUTED, Paint.Align.CENTER, T3Surface.REGULAR);
            drawSteps(canvas, 310f);
            rect.set(STATE_BUTTON);
            surface.primary(canvas, rect, 26f);
            surface.text(canvas, "Open Settings", 240f, rect.centerY() + 7f, 19f, SamTheme.INK,
                    Paint.Align.CENTER, T3Surface.MEDIUM);
            return;
        }
        float y = titleY + 34f;
        for (String line : stateLines) {
            surface.text(canvas, line, 240f, y, 16f, SamTheme.MUTED, Paint.Align.CENTER, T3Surface.REGULAR);
            y += 23f;
        }
        if (mode == Mode.FAILED || mode == Mode.READY) {
            rect.set(STATE_BUTTON);
            if (mode == Mode.READY) {
                surface.primary(canvas, rect, 26f);
                surface.plus(canvas, rect.left + 34f, rect.centerY(), 7f, SamTheme.INK);
            } else {
                surface.glass(canvas, rect, 26f, true);
            }
            surface.text(canvas, mode == Mode.READY ? "New thread" : "Retry now", 240f, rect.centerY() + 7f, 19f,
                    SamTheme.INK, Paint.Align.CENTER, T3Surface.MEDIUM);
        }
    }

    private static final String[] STEPS = {
            "On this R1: Settings → Management. Open the address on your computer.",
            "In T3 Code on your Mac: Settings → Connections → Create link.",
            "On that R1 page, under T3 Code, paste the server URL and pairing code.",
    };
    private static final String[] STEP_NUMBERS = {"1", "2", "3"};
    private List<List<String>> stepLines;

    private void drawSteps(Canvas canvas, float top) {
        if (stepLines == null) {
            stepLines = new ArrayList<>();
            for (String step : STEPS) stepLines.add(surface.wrap(step, 356f, 15f, T3Surface.REGULAR, false));
        }
        float height = 16f;
        for (List<String> lines : stepLines) height += lines.size() * 20f + 14f;
        rect.set(20f, top, 460f, top + height);
        surface.glass(canvas, rect, 22f, false);
        float y = top + 16f;
        for (int step = 0; step < stepLines.size(); step++) {
            List<String> lines = stepLines.get(step);
            surface.paint.setColor(SamTheme.withAlpha(SamTheme.ORB_PALE, 40));
            canvas.drawCircle(52f, y + 12f, 13f, surface.paint);
            surface.text(canvas, STEP_NUMBERS[step], 52f, y + 17.5f, 15f, SamTheme.ORB_PALE,
                    Paint.Align.CENTER, T3Surface.MEDIUM);
            for (int line = 0; line < lines.size(); line++) {
                surface.text(canvas, lines.get(line), 78f, y + 17f + line * 20f, 15f, SamTheme.INK,
                        Paint.Align.LEFT, T3Surface.REGULAR);
            }
            y += lines.size() * 20f + 14f;
        }
    }
}
