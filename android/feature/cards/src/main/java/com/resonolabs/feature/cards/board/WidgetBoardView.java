package com.resonolabs.feature.cards.board;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.animation.DecelerateInterpolator;
import android.widget.OverScroller;

import com.resonolabs.ui.design.SamTheme;
import com.resonolabs.ui.input.UiInputIntent;

import java.util.ArrayList;
import java.util.List;

/**
 * Vertical, scrollable board of glass widgets in the 480x640 logical space.
 *
 * <ul>
 *   <li>Touch: drag scrolls, flings decelerate, a tap goes to the widget under the finger.</li>
 *   <li>Wheel: NEXT/PREVIOUS move a focus highlight row by row across widgets and scroll it into
 *       view; PREVIOUS past the first row returns to the top. ACTIVATE acts on the focused row.</li>
 *   <li>Each widget's {@link BoardWidget#refreshIntervalMs()} cadence runs while the board is shown;
 *       time-dependent text is re-measured on each minute boundary.</li>
 *   <li>onDraw does not allocate: shaders are cached, text is prepared in measure.</li>
 * </ul>
 */
public final class WidgetBoardView extends View {
    public static final float W = 480f;
    public static final float H = 640f;
    /** The product chrome (tabs, gear) covers 0..100; content scrolls beneath it. */
    public static final float VIEW_TOP = 100f;
    public static final float SIDE = 16f;
    public static final float WIDGET_WIDTH = W - SIDE * 2f;
    private static final float GAP = 14f;
    private static final float CONTENT_TOP = 100f;
    private static final float BOTTOM_PAD = 36f;
    private static final float FOCUS_MARGIN = 14f;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final OverScroller scroller;
    private final float touchSlopPx;
    private final RectF rect = new RectF();
    private final List<BoardWidget> widgets = new ArrayList<>();
    private final List<Refresher> refreshers = new ArrayList<>();
    private final LinearGradient backdrop;
    private final RadialGradient glow;
    private final LinearGradient topFade;
    private final LinearGradient bottomFade;
    private float[] tops = new float[0];
    private float[] heights = new float[0];
    private boolean[] dirty = new boolean[0];
    private float scrollY;
    private float maxScroll;
    private boolean layoutDirty = true;
    private boolean started;
    private int focusWidget = -1;
    private int focusRow = -1;
    private int pressWidget = -1;
    private int pressRow = -1;
    private VelocityTracker velocity;
    private float downX;
    private float downY;
    private float lastY;
    private boolean dragging;
    private boolean tapCandidate;
    private boolean disallowed;
    private long lastScrollAt;

    private final Runnable minuteTick = new Runnable() {
        @Override public void run() {
            markAllDirty();
            invalidate();
            scheduleMinuteTick();
        }
    };

    public WidgetBoardView(Context context) {
        super(context);
        setFocusable(true);
        setContentDescription("Widgets: up next, tasks and creations");
        scroller = new OverScroller(context, new DecelerateInterpolator(1.6f));
        touchSlopPx = ViewConfiguration.get(context).getScaledTouchSlop();
        backdrop = new LinearGradient(0f, 0f, 0f, H, SamTheme.BACKGROUND_TOP, SamTheme.BACKGROUND, Shader.TileMode.CLAMP);
        glow = new RadialGradient(250f, 560f, 330f, SamTheme.withAlpha(SamTheme.ORB_BLUE, 54),
                SamTheme.withAlpha(SamTheme.ORB_BLUE, 0), Shader.TileMode.CLAMP);
        topFade = new LinearGradient(0f, VIEW_TOP, 0f, VIEW_TOP + 22f, colorAt(VIEW_TOP), SamTheme.withAlpha(colorAt(VIEW_TOP), 0),
                Shader.TileMode.CLAMP);
        bottomFade = new LinearGradient(0f, H - 30f, 0f, H, Color.argb(0, 9, 11, 16), Color.argb(170, 9, 11, 16),
                Shader.TileMode.CLAMP);
    }

    public void setWidgets(List<BoardWidget> list) {
        widgets.clear();
        widgets.addAll(list);
        refreshers.clear();
        for (int i = 0; i < widgets.size(); i++) refreshers.add(new Refresher(widgets.get(i)));
        tops = new float[widgets.size()];
        heights = new float[widgets.size()];
        dirty = new boolean[widgets.size()];
        java.util.Arrays.fill(dirty, true);
        focusWidget = -1;
        focusRow = -1;
        layoutDirty = true;
        invalidate();
    }

    /** A widget's data or layout changed. */
    public void widgetChanged(BoardWidget widget) {
        int index = widgets.indexOf(widget);
        if (index >= 0) dirty[index] = true;
        layoutDirty = true;
        invalidate();
    }

    private void markAllDirty() {
        java.util.Arrays.fill(dirty, true);
        layoutDirty = true;
    }

    /** Tab shown: refresh everything now, then keep each widget's cadence. */
    public void start() {
        started = true;
        handler.removeCallbacksAndMessages(null);
        for (int i = 0; i < widgets.size(); i++) widgets.get(i).onShow();
        for (int i = 0; i < refreshers.size(); i++) handler.post(refreshers.get(i));
        scheduleMinuteTick();
        markAllDirty();
        invalidate();
    }

    public void stop() {
        started = false;
        handler.removeCallbacksAndMessages(null);
        for (int i = 0; i < widgets.size(); i++) widgets.get(i).onHide();
    }

    public void close() {
        stop();
        for (int i = 0; i < widgets.size(); i++) widgets.get(i).close();
    }

    /** Back to the top, focus cleared (e.g. when returning from a full page). */
    public void scrollToTop(boolean animate) {
        clearFocus(false);
        smoothScrollTo(0f, animate);
    }

    private void scheduleMinuteTick() {
        handler.removeCallbacks(minuteTick);
        if (!started) return;
        long now = System.currentTimeMillis();
        long delay = 60_000L - (now % 60_000L) + 40L;
        handler.postDelayed(minuteTick, delay);
    }

    private final class Refresher implements Runnable {
        private final BoardWidget widget;

        Refresher(BoardWidget widget) { this.widget = widget; }

        @Override public void run() {
            if (!started) return;
            widget.refresh();
            long interval = widget.refreshIntervalMs();
            if (interval > 0L) handler.postDelayed(this, interval);
        }
    }

    // ---------------------------------------------------------------- layout

    private void relayout() {
        long now = System.currentTimeMillis();
        int anchor = -1;
        float anchorOffset = 0f;
        if (scrollY > 0f) {
            for (int i = 0; i < widgets.size(); i++) {
                if (tops[i] + heights[i] > scrollY + VIEW_TOP) { anchor = i; anchorOffset = scrollY - tops[i]; break; }
            }
        }
        float y = CONTENT_TOP;
        for (int i = 0; i < widgets.size(); i++) {
            float height = dirty[i] ? Math.max(0f, widgets.get(i).measure(WIDGET_WIDTH, now)) : heights[i];
            dirty[i] = false;
            tops[i] = y;
            heights[i] = height;
            if (height > 0f) y += height + GAP;
        }
        float content = y - GAP + BOTTOM_PAD;
        maxScroll = Math.max(0f, content - H);
        if (anchor >= 0) scrollY = tops[anchor] + anchorOffset;
        scrollY = clamp(scrollY);
        if (focusWidget >= 0 && (focusWidget >= widgets.size() || focusRow >= widgets.get(focusWidget).focusCount())) {
            clearFocus(false);
        }
        layoutDirty = false;
    }

    // ---------------------------------------------------------------- drawing

    @Override protected void onDraw(Canvas canvas) {
        if (layoutDirty) relayout();
        boolean scrolling = scroller.computeScrollOffset();
        if (scrolling) {
            scrollY = clamp(scroller.getCurrY());
            lastScrollAt = System.currentTimeMillis();
        }
        long now = System.currentTimeMillis();
        canvas.save();
        canvas.scale(getWidth() / W, getHeight() / H);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.BLACK);
        paint.setShader(backdrop);
        canvas.drawRect(0f, 0f, W, H, paint);
        paint.setShader(glow);
        canvas.drawCircle(250f, 560f, 330f, paint);
        paint.setShader(null);

        boolean animating = false;
        for (int i = 0; i < widgets.size(); i++) {
            float top = tops[i] - scrollY;
            float height = heights[i];
            if (height <= 0f || top > H || top + height < VIEW_TOP - 4f) continue;
            BoardWidget widget = widgets.get(i);
            canvas.save();
            canvas.translate(SIDE, top);
            widget.draw(canvas, now);
            if (i == pressWidget && pressRow >= 0) highlight(canvas, widget, pressRow, false);
            else if (i == focusWidget && focusRow >= 0) highlight(canvas, widget, focusRow, true);
            canvas.restore();
            animating |= widget.animating(now);
        }

        // Cover the chrome band so scrolled content never shows behind the tabs.
        paint.setColor(Color.BLACK);
        paint.setShader(backdrop);
        canvas.drawRect(0f, 0f, W, VIEW_TOP, paint);
        paint.setShader(topFade);
        canvas.drawRect(0f, VIEW_TOP, W, VIEW_TOP + 22f, paint);
        paint.setShader(bottomFade);
        canvas.drawRect(0f, H - 30f, W, H, paint);
        paint.setShader(null);
        boolean indicator = drawScrollIndicator(canvas, now);
        canvas.restore();
        if (scrolling || animating || indicator) postInvalidateOnAnimation();
    }

    private void highlight(Canvas canvas, BoardWidget widget, int row, boolean focus) {
        widget.focusBounds(row, rect);
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(SamTheme.withAlpha(SamTheme.INK, focus ? 16 : 26));
        canvas.drawRoundRect(rect, 20f, 20f, paint);
        if (focus) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2f);
            paint.setColor(SamTheme.withAlpha(SamTheme.ORB_PALE, 165));
            canvas.drawRoundRect(rect, 20f, 20f, paint);
            paint.setStyle(Paint.Style.FILL);
        }
    }

    private boolean drawScrollIndicator(Canvas canvas, long now) {
        if (maxScroll <= 0f) return false;
        long since = now - lastScrollAt;
        float alpha = dragging ? 1f : since < 700L ? 1f : since < 1100L ? 1f - (since - 700L) / 400f : 0f;
        if (alpha <= 0f) return false;
        float trackTop = VIEW_TOP + 8f;
        float trackBottom = H - 10f;
        float track = trackBottom - trackTop;
        float visible = H - VIEW_TOP;
        float thumb = Math.max(36f, track * visible / (visible + maxScroll));
        float thumbTop = trackTop + (track - thumb) * (scrollY / maxScroll);
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(SamTheme.withAlpha(SamTheme.ORB_PALE, Math.round(150 * alpha)));
        canvas.drawRoundRect(W - 7f, thumbTop, W - 3f, thumbTop + thumb, 2f, 2f, paint);
        return true;
    }

    // ---------------------------------------------------------------- touch

    @Override public boolean onTouchEvent(MotionEvent event) {
        float sx = W / Math.max(1f, getWidth());
        float sy = H / Math.max(1f, getHeight());
        float x = event.getX() * sx;
        float y = event.getY() * sy;
        if (velocity == null) velocity = VelocityTracker.obtain();
        velocity.addMovement(event);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN -> {
                if (layoutDirty) relayout();
                boolean wasFlinging = !scroller.isFinished();
                scroller.forceFinished(true);
                downX = x;
                downY = y;
                lastY = y;
                dragging = false;
                disallowed = false;
                tapCandidate = !wasFlinging;
                if (focusWidget >= 0) clearFocus(false);
                if (tapCandidate) press(x, y);
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_MOVE -> {
                float slop = touchSlopPx * sy;
                if (Math.abs(y - downY) > slop || Math.abs(x - downX) > touchSlopPx * sx) {
                    tapCandidate = false;
                    clearPress();
                }
                if (!dragging && Math.abs(y - downY) > slop && Math.abs(y - downY) > Math.abs(x - downX)) {
                    dragging = true;
                    clearPress();
                    // Let a pull-down from the very top open the Control Center when already at the top.
                    boolean controlCenterPull = downY <= 130f && scrollY <= 0f && y > downY;
                    if (!controlCenterPull && getParent() != null) {
                        getParent().requestDisallowInterceptTouchEvent(true);
                        disallowed = true;
                    }
                    lastY = y;
                }
                if (dragging) {
                    float before = scrollY;
                    scrollY = clamp(scrollY - (y - lastY));
                    lastY = y;
                    if (scrollY != before) { lastScrollAt = System.currentTimeMillis(); invalidate(); }
                }
                return true;
            }
            case MotionEvent.ACTION_UP -> {
                if (dragging) {
                    velocity.computeCurrentVelocity(1000);
                    float vy = velocity.getYVelocity() * sy;
                    if (Math.abs(vy) > 120f && maxScroll > 0f) {
                        scroller.fling(0, Math.round(scrollY), 0, Math.round(-vy), 0, 0, 0, Math.round(maxScroll));
                        postInvalidateOnAnimation();
                    }
                } else if (tapCandidate) {
                    tap(x, y);
                }
                endGesture();
                return true;
            }
            case MotionEvent.ACTION_CANCEL -> {
                endGesture();
                return true;
            }
            default -> { return true; }
        }
    }

    private void endGesture() {
        dragging = false;
        tapCandidate = false;
        clearPress();
        if (disallowed && getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
        disallowed = false;
        if (velocity != null) { velocity.recycle(); velocity = null; }
        invalidate();
    }

    private void press(float x, float y) {
        clearPress();
        int index = widgetAt(y);
        if (index < 0) return;
        BoardWidget widget = widgets.get(index);
        float localX = x - SIDE;
        float localY = y + scrollY - tops[index];
        for (int row = 0; row < widget.focusCount(); row++) {
            widget.focusBounds(row, rect);
            if (rect.contains(localX, localY)) { pressWidget = index; pressRow = row; return; }
        }
    }

    private void clearPress() {
        if (pressWidget >= 0) invalidate();
        pressWidget = -1;
        pressRow = -1;
    }

    private void tap(float x, float y) {
        if (y < VIEW_TOP) return;
        int index = widgetAt(y);
        if (index < 0) return;
        if (widgets.get(index).onTap(x - SIDE, y + scrollY - tops[index])) {
            performClick();
            invalidate();
        }
    }

    @Override public boolean performClick() {
        super.performClick();
        return true;
    }

    private int widgetAt(float y) {
        if (y < VIEW_TOP) return -1;
        float content = y + scrollY;
        for (int i = 0; i < widgets.size(); i++) {
            if (heights[i] > 0f && content >= tops[i] && content < tops[i] + heights[i]) return i;
        }
        return -1;
    }

    // ---------------------------------------------------------------- wheel

    public boolean onInput(UiInputIntent input) {
        if (layoutDirty) relayout();
        switch (input) {
            case NEXT -> { moveFocus(1); return true; }
            case PREVIOUS -> { moveFocus(-1); return true; }
            case ACTIVATE -> {
                if (focusWidget < 0) { moveFocus(1); return true; }
                widgets.get(focusWidget).activate(focusRow);
                invalidate();
                return true;
            }
            default -> { return false; }
        }
    }

    private void moveFocus(int direction) {
        if (focusWidget < 0) {
            if (direction < 0) { smoothScrollTo(0f, true); return; }
            if (!firstVisibleFocus()) smoothScrollTo(scrollY + 160f, true);
        } else if (!step(direction)) {
            if (direction < 0) { clearFocus(true); smoothScrollTo(0f, true); return; }
            smoothScrollTo(maxScroll, true);
        }
        ensureFocusVisible();
        invalidate();
    }

    private boolean firstVisibleFocus() {
        for (int i = 0; i < widgets.size(); i++) {
            BoardWidget widget = widgets.get(i);
            if (heights[i] <= 0f) continue;
            for (int row = 0; row < widget.focusCount(); row++) {
                widget.focusBounds(row, rect);
                if (tops[i] + rect.bottom > scrollY + VIEW_TOP + 8f) {
                    focusWidget = i;
                    focusRow = row;
                    return true;
                }
            }
        }
        return false;
    }

    private boolean step(int direction) {
        int widget = focusWidget;
        int row = focusRow + direction;
        while (widget >= 0 && widget < widgets.size()) {
            int count = heights[widget] > 0f ? widgets.get(widget).focusCount() : 0;
            if (row >= 0 && row < count) { focusWidget = widget; focusRow = row; return true; }
            widget += direction;
            if (widget < 0 || widget >= widgets.size()) break;
            row = direction > 0 ? 0 : (heights[widget] > 0f ? widgets.get(widget).focusCount() - 1 : -1);
        }
        return false;
    }

    private void ensureFocusVisible() {
        if (focusWidget < 0) return;
        widgets.get(focusWidget).focusBounds(focusRow, rect);
        float top = tops[focusWidget] + rect.top;
        float bottom = tops[focusWidget] + rect.bottom;
        float target = scrollY;
        if (bottom + FOCUS_MARGIN > scrollY + H) target = bottom + FOCUS_MARGIN - H;
        if (top - FOCUS_MARGIN < target + VIEW_TOP) target = top - FOCUS_MARGIN - VIEW_TOP;
        if (focusWidget == firstFocusableWidget() && focusRow == 0 && tops[focusWidget] - CONTENT_TOP < H * 0.5f) {
            target = Math.min(target, 0f);
        }
        smoothScrollTo(target, true);
    }

    private int firstFocusableWidget() {
        for (int i = 0; i < widgets.size(); i++) if (heights[i] > 0f && widgets.get(i).focusCount() > 0) return i;
        return -1;
    }

    private void clearFocus(boolean redraw) {
        focusWidget = -1;
        focusRow = -1;
        if (redraw) invalidate();
    }

    private void smoothScrollTo(float target, boolean animate) {
        float clamped = clamp(target);
        if (!animate) {
            scroller.forceFinished(true);
            scrollY = clamped;
            invalidate();
            return;
        }
        int from = scroller.isFinished() ? Math.round(scrollY) : scroller.getCurrY();
        scroller.forceFinished(true);
        int distance = Math.round(clamped) - from;
        if (distance == 0) { scrollY = clamped; invalidate(); return; }
        scroller.startScroll(0, from, 0, distance, 230);
        lastScrollAt = System.currentTimeMillis();
        postInvalidateOnAnimation();
    }

    private float clamp(float value) {
        return Math.max(0f, Math.min(maxScroll, value));
    }

    private static int colorAt(float y) {
        float t = y / H;
        int a = SamTheme.BACKGROUND_TOP;
        int b = SamTheme.BACKGROUND;
        return Color.rgb(Math.round(Color.red(a) + (Color.red(b) - Color.red(a)) * t),
                Math.round(Color.green(a) + (Color.green(b) - Color.green(a)) * t),
                Math.round(Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t));
    }
}
