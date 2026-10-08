package com.resonolabs.feature.settings;

import com.resonolabs.ui.design.OrbStyle;
import com.resonolabs.ui.input.UiInputIntent;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public final class OrbStyleWheelTest {
    @Test public void wheelPicksTheOrbStyleOnTheDisplayPage() {
        assertEquals(OrbStyle.PIXEL_HEAD, SettingsInputPolicy.orbStyleForWheel("Display", UiInputIntent.NEXT));
        assertEquals(OrbStyle.FLUID, SettingsInputPolicy.orbStyleForWheel("Display", UiInputIntent.PREVIOUS));
    }

    @Test public void otherInputsAndPagesLeaveTheStyleAlone() {
        assertNull(SettingsInputPolicy.orbStyleForWheel("Display", UiInputIntent.ACTIVATE));
        assertNull(SettingsInputPolicy.orbStyleForWheel("Display", UiInputIntent.BACK));
        assertNull(SettingsInputPolicy.orbStyleForWheel("Sound", UiInputIntent.NEXT));
        assertNull(SettingsInputPolicy.orbStyleForWheel(null, UiInputIntent.NEXT));
    }

    @Test public void brightnessStillNeverFollowsTheWheel() {
        assertTrue(SettingsInputPolicy.consumeWheelWithoutAdjustment("Display", UiInputIntent.NEXT));
        assertTrue(SettingsInputPolicy.consumeWheelWithoutAdjustment("Display", UiInputIntent.PREVIOUS));
    }
}
