package com.resonolabs.feature.settings;

/**
 * Geometry of the Settings list in the 480x640 design space (pure, unit-tested). The title and
 * close button stay put above {@link #VIEW_TOP}; the rows scroll underneath, clamped to their
 * content (no overscroll), so a list taller than the screen keeps every row reachable.
 */
final class SettingsListLayout {
    /** Top of the scrolling viewport: everything above is the fixed header, and taps there never open a row. */
    static final float VIEW_TOP = 78f;
    static final float VIEW_BOTTOM = 640f;
    /** First row's top at scroll 0. */
    static final float ROW_TOP = 88f;
    static final float ROW_STEP = 66f;
    static final float ROW_HEIGHT = 58f;
    /** Space below the last row when scrolled to the end. */
    static final float BOTTOM_PAD = 16f;
    /** Room kept around a row that the wheel focuses, so its edge never sits on the viewport edge. */
    static final float REVEAL_MARGIN = 10f;

    private SettingsListLayout() { }

    /** Where the content ends (last row plus padding), in unscrolled design coordinates. */
    static float contentBottom(int rows) {
        if (rows <= 0) return ROW_TOP;
        return ROW_TOP + (rows - 1) * ROW_STEP + ROW_HEIGHT + BOTTOM_PAD;
    }

    /** How far the list can scroll; 0 when every row fits. */
    static float maxScroll(int rows) {
        return Math.max(0f, contentBottom(rows) - VIEW_BOTTOM);
    }

    /** {@code scroll} limited to 0..{@link #maxScroll} (NaN counts as the top). */
    static float clamp(float scroll, int rows) {
        if (!(scroll > 0f)) return 0f;
        return Math.min(maxScroll(rows), scroll);
    }

    /** On-screen top of {@code row} at {@code scroll}. */
    static float rowTop(int row, float scroll) {
        return ROW_TOP + row * ROW_STEP - scroll;
    }

    /**
     * The row under a tap at design y {@code y}, or -1 for the header band, the gaps between
     * rows and anything past the last row.
     */
    static int rowAt(float y, float scroll, int rows) {
        if (!(y >= VIEW_TOP) || y > VIEW_BOTTOM) return -1;
        float content = y + scroll - ROW_TOP;
        if (content < 0f) return -1;
        int row = (int) (content / ROW_STEP);
        if (row >= rows) return -1;
        return content - row * ROW_STEP <= ROW_HEIGHT ? row : -1;
    }

    /**
     * The scroll that shows {@code row} fully (with {@link #REVEAL_MARGIN}), moving as little as
     * possible from {@code scroll}: the first row always means the top, the last the end.
     */
    static float reveal(int row, float scroll, int rows) {
        if (row <= 0) return 0f;
        if (row >= rows - 1) return maxScroll(rows);
        float top = ROW_TOP + row * ROW_STEP - REVEAL_MARGIN;
        float bottom = ROW_TOP + row * ROW_STEP + ROW_HEIGHT + REVEAL_MARGIN;
        float next = clamp(scroll, rows);
        if (top < VIEW_TOP + next) next = top - VIEW_TOP;
        else if (bottom > VIEW_BOTTOM + next) next = bottom - VIEW_BOTTOM;
        return clamp(next, rows);
    }
}
