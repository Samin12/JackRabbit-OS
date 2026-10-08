package com.resonolabs.feature.settings;

/**
 * Geometry and timing of Settings > Theme (pure, unit-tested): two side-by-side preview tiles,
 * one per orb style in {@code OrbStyle.values()} order (Orb, Pixel head).
 */
final class ThemePageLayout {
    static final int TILES = 2;
    static final float TILE_TOP = 92f;
    static final float TILE_BOTTOM = 532f;
    static final float[] TILE_LEFT = {20f, 246f};
    static final float[] TILE_RIGHT = {234f, 460f};
    /** The live preview "screen" inside a tile. */
    static final float STAGE_INSET = 10f;
    static final float STAGE_BOTTOM = 368f;
    static final float PREVIEW_RADIUS = 50f;
    /**
     * The head is mostly wings and air, so it gets a bigger radius to weigh about the same as the
     * orb (and draws from the crisper 176 px art, scaled down).
     */
    static final float HEAD_PREVIEW_RADIUS = 57f;
    /** Badge pop after applying: scale 0.6 to 1 with a small overshoot. */
    static final long BADGE_POP_MS = 320L;
    /** Confirmation toast: fade in, hold, fade out. */
    static final long TOAST_IN_MS = 160L;
    static final long TOAST_HOLD_MS = 1500L;
    static final long TOAST_OUT_MS = 360L;

    private ThemePageLayout() { }

    /** The tile under ({@code x}, {@code y}), or -1 outside both (header, gap, footer). */
    static int tileAt(float x, float y) {
        if (!(y >= TILE_TOP) || y > TILE_BOTTOM) return -1;
        for (int i = 0; i < TILES; i++) {
            if (x >= TILE_LEFT[i] && x <= TILE_RIGHT[i]) return i;
        }
        return -1;
    }

    static float tileCenterX(int tile) {
        return (TILE_LEFT[tile] + TILE_RIGHT[tile]) / 2f;
    }

    static float previewCenterY() {
        return (TILE_TOP + STAGE_INSET + STAGE_BOTTOM) / 2f;
    }

    /**
     * Badge scale {@code elapsedMs} after a style was applied: 0.6 growing to 1 with a small
     * overshoot (ease-out-back), exactly 1 once the pop is over or when nothing was applied.
     */
    static float badgeScale(long elapsedMs) {
        if (elapsedMs < 0L || elapsedMs >= BADGE_POP_MS) return 1f;
        float t = elapsedMs / (float) BADGE_POP_MS - 1f;
        float back = 1.7f;
        float eased = 1f + t * t * ((back + 1f) * t + back);
        return 0.6f + 0.4f * eased;
    }

    /** Badge opacity during the pop: fades in over the first ~40% of it. */
    static float badgeAlpha(long elapsedMs) {
        if (elapsedMs < 0L || elapsedMs >= BADGE_POP_MS) return 1f;
        return Math.min(1f, elapsedMs / (BADGE_POP_MS * 0.4f));
    }

    /** Toast opacity {@code elapsedMs} after applying; 0 before and after it shows. */
    static float toastAlpha(long elapsedMs) {
        if (elapsedMs < 0L) return 0f;
        if (elapsedMs < TOAST_IN_MS) return elapsedMs / (float) TOAST_IN_MS;
        long out = elapsedMs - TOAST_IN_MS - TOAST_HOLD_MS;
        if (out < 0L) return 1f;
        if (out < TOAST_OUT_MS) return 1f - out / (float) TOAST_OUT_MS;
        return 0f;
    }

    /** True while the toast is still on screen (or yet to fade out). */
    static boolean toastShowing(long elapsedMs) {
        return elapsedMs >= 0L && elapsedMs < TOAST_IN_MS + TOAST_HOLD_MS + TOAST_OUT_MS;
    }
}
