package com.resonolabs.feature.cards.board;

import android.graphics.Canvas;
import android.graphics.RectF;

/**
 * One glanceable widget on the Cards board (the "WidgetProvider" contract).
 *
 * <p>Geometry is in the board's 480x640 logical space. A widget is laid out for a given width by
 * {@link #measure} and drawn at the local origin: the board translates the canvas to the widget's
 * slot and handles scrolling, culling, wheel focus, pressed/focus highlights and refresh cadence.
 *
 * <p>Rules: {@link #measure} may allocate (strings, shaders) and is called only when the widget
 * reported a change, the minute ticks, or the width changes. {@link #draw} must not allocate.
 * {@link #refresh} must never block the UI thread: use the runtime clients' workers and call
 * {@link BoardHost#widgetChanged} from the main-thread callback.
 *
 * <p>Wave 2 widgets: "t3" (needs-you / working threads), "live" (GenUI live, pinned and recent
 * cards) and "journal" (Heptabase journal status and a Note button).
 */
public interface BoardWidget {
    /** Stable id: "clock", "agenda", "t3", "tasks", "live", "journal", "creations". */
    String id();

    /** Lays out for {@code width} logical px at {@code nowMs}; returns the height (0 hides the widget). */
    float measure(float width, long nowMs);

    /** Draws at the local origin. Allocation-free. */
    void draw(Canvas canvas, long nowMs);

    /** Number of wheel-focusable rows (headers, event rows, task rows, tiles...). */
    int focusCount();

    /** Writes the local bounds of focus row {@code index} into {@code out}. */
    void focusBounds(int index, RectF out);

    /** Corner radius of the focus / pressed highlight for row {@code index}. */
    default float focusRadius(int index) { return 20f; }

    /** Touch tap at local coordinates; true when handled. */
    boolean onTap(float x, float y);

    /** Wheel-focus activation (ACTIVATE) of row {@code index}; true when handled. */
    boolean activate(int index);

    /** Data refresh cadence while the board is shown; 0 means host-driven. */
    long refreshIntervalMs();

    /** Starts an asynchronous data refresh. */
    void refresh();

    /** The board became visible (the tab was shown). */
    default void onShow() {}

    /** The board is going away (tab hidden or app closing); flush anything pending. */
    default void onHide() {}

    /** True while the widget runs a short animation and needs another frame. */
    default boolean animating(long nowMs) { return false; }

    /**
     * While the widget is on screen: how soon it needs to be redrawn without a data change, in
     * ms (a ticking timer, a spinning "working" orb), or -1 for never. Cheaper than
     * {@link #animating}, which redraws every vsync. Allocation-free; called after {@link #draw}.
     */
    default long redrawDelayMs(long nowMs) { return -1L; }

    /**
     * Stable identity of focus row {@code index} (e.g. a thread or card id), or null. When a
     * refresh reorders rows, the board keeps the wheel focus on the row with the same key.
     */
    default String focusKey(int index) { return null; }

    default void close() {}
}
