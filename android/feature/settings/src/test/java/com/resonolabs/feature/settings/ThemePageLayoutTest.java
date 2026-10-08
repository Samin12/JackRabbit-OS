package com.resonolabs.feature.settings;

import com.resonolabs.ui.design.OrbStyle;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class ThemePageLayoutTest {
    private static final float EPS = 0.001f;

    @Test public void oneTilePerStyleInStyleOrder() {
        assertEquals(OrbStyle.values().length, ThemePageLayout.TILES);
        assertEquals(0, OrbStyle.FLUID.ordinal());
        assertEquals(1, OrbStyle.PIXEL_HEAD.ordinal());
        assertEquals(0, ThemePageLayout.tileAt(127f, 300f));
        assertEquals(1, ThemePageLayout.tileAt(353f, 300f));
    }

    @Test public void tilesAreBigTouchTargetsInsideTheScreen() {
        for (int i = 0; i < ThemePageLayout.TILES; i++) {
            assertTrue(ThemePageLayout.TILE_RIGHT[i] - ThemePageLayout.TILE_LEFT[i] >= 48f);
            assertTrue(ThemePageLayout.TILE_LEFT[i] >= 0f && ThemePageLayout.TILE_RIGHT[i] <= 480f);
        }
        assertTrue(ThemePageLayout.TILE_BOTTOM - ThemePageLayout.TILE_TOP >= 48f);
        // Below the back/close discs (y 20..68), above the toast line.
        assertTrue(ThemePageLayout.TILE_TOP > 68f);
        assertTrue(ThemePageLayout.TILE_BOTTOM < 560f);
    }

    @Test public void headerGapAndFooterAreNotTiles() {
        assertEquals(-1, ThemePageLayout.tileAt(127f, 40f));
        assertEquals(-1, ThemePageLayout.tileAt(127f, ThemePageLayout.TILE_TOP - 1f));
        assertEquals(-1, ThemePageLayout.tileAt(240f, 300f));   // the gap between the tiles
        assertEquals(-1, ThemePageLayout.tileAt(127f, 590f));
        assertEquals(-1, ThemePageLayout.tileAt(Float.NaN, 300f));
        assertEquals(-1, ThemePageLayout.tileAt(127f, Float.NaN));
    }

    @Test public void badgePopsFromSmallToFullWithAnOvershoot() {
        assertEquals(1f, ThemePageLayout.badgeScale(-1L), EPS);   // nothing applied: plain badge
        assertEquals(0.6f, ThemePageLayout.badgeScale(0L), EPS);
        assertEquals(1f, ThemePageLayout.badgeScale(ThemePageLayout.BADGE_POP_MS), EPS);
        float peak = 0f;
        for (long t = 0; t < ThemePageLayout.BADGE_POP_MS; t += 5) peak = Math.max(peak, ThemePageLayout.badgeScale(t));
        assertTrue(peak > 1f && peak < 1.08f);
        assertEquals(0f, ThemePageLayout.badgeAlpha(0L), EPS);
        assertEquals(1f, ThemePageLayout.badgeAlpha(ThemePageLayout.BADGE_POP_MS / 2), EPS);
        assertEquals(1f, ThemePageLayout.badgeAlpha(-1L), EPS);
    }

    @Test public void toastFadesInHoldsAndFadesOut() {
        assertEquals(0f, ThemePageLayout.toastAlpha(-1L), EPS);
        assertEquals(0f, ThemePageLayout.toastAlpha(0L), EPS);
        assertEquals(1f, ThemePageLayout.toastAlpha(ThemePageLayout.TOAST_IN_MS + 10L), EPS);
        long end = ThemePageLayout.TOAST_IN_MS + ThemePageLayout.TOAST_HOLD_MS + ThemePageLayout.TOAST_OUT_MS;
        float fading = ThemePageLayout.toastAlpha(end - ThemePageLayout.TOAST_OUT_MS / 2);
        assertTrue(fading > 0f && fading < 1f);
        assertEquals(0f, ThemePageLayout.toastAlpha(end), EPS);
        assertTrue(ThemePageLayout.toastShowing(end - 1L));
        assertFalse(ThemePageLayout.toastShowing(end));
        assertFalse(ThemePageLayout.toastShowing(-1L));
    }
}
