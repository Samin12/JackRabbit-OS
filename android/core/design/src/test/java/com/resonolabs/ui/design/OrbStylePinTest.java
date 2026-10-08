package com.resonolabs.ui.design;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Pinned orbs (the Settings > Theme previews) draw their own style whatever the user picked. */
public final class OrbStylePinTest {
    @Test public void aPinWinsOverTheUsersStyle() {
        assertEquals(OrbStyle.PIXEL_HEAD, OrbStyle.drawn(OrbStyle.PIXEL_HEAD, false, OrbStyle.FLUID));
        assertEquals(OrbStyle.PIXEL_HEAD, OrbStyle.drawn(OrbStyle.PIXEL_HEAD, true, OrbStyle.FLUID));
        assertEquals(OrbStyle.FLUID, OrbStyle.drawn(OrbStyle.FLUID, true, OrbStyle.PIXEL_HEAD));
        assertEquals(OrbStyle.FLUID, OrbStyle.drawn(OrbStyle.FLUID, false, OrbStyle.PIXEL_HEAD));
    }

    @Test public void unpinnedHeroesFollowTheUserAndPlainOrbsStayFluid() {
        assertEquals(OrbStyle.PIXEL_HEAD, OrbStyle.drawn(null, true, OrbStyle.PIXEL_HEAD));
        assertEquals(OrbStyle.FLUID, OrbStyle.drawn(null, true, OrbStyle.FLUID));
        assertEquals(OrbStyle.FLUID, OrbStyle.drawn(null, true, null));
        assertEquals(OrbStyle.FLUID, OrbStyle.drawn(null, false, OrbStyle.PIXEL_HEAD));
    }

    @Test public void headArtIsKeptWhileItIsTheStyleOrAPreviewHoldsIt() {
        assertTrue(PixelHeadSprites.keepsArt(OrbStyle.PIXEL_HEAD, 0));
        assertTrue(PixelHeadSprites.keepsArt(OrbStyle.FLUID, 1));
        assertTrue(PixelHeadSprites.keepsArt(OrbStyle.PIXEL_HEAD, 2));
        // Neither: a decode serves one frame only, so pinned previews must hold.
        assertFalse(PixelHeadSprites.keepsArt(OrbStyle.FLUID, 0));
    }
}
