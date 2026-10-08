package com.resonolabs.feature.settings;

import com.resonolabs.ui.input.UiInputIntent;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class ThemeWheelTest {
    @Test public void wheelMovesFocusBetweenTheTilesAndClampsAtBothEnds() {
        assertEquals(1, SettingsInputPolicy.themeFocusForWheel(0, 2, UiInputIntent.NEXT));
        assertEquals(1, SettingsInputPolicy.themeFocusForWheel(1, 2, UiInputIntent.NEXT));
        assertEquals(0, SettingsInputPolicy.themeFocusForWheel(1, 2, UiInputIntent.PREVIOUS));
        assertEquals(0, SettingsInputPolicy.themeFocusForWheel(0, 2, UiInputIntent.PREVIOUS));
    }

    @Test public void otherIntentsKeepFocusAndBadFocusIsRepaired() {
        assertEquals(1, SettingsInputPolicy.themeFocusForWheel(1, 2, UiInputIntent.ACTIVATE));
        assertEquals(0, SettingsInputPolicy.themeFocusForWheel(0, 2, UiInputIntent.BACK));
        assertEquals(1, SettingsInputPolicy.themeFocusForWheel(7, 2, UiInputIntent.ACTIVATE));
        assertEquals(1, SettingsInputPolicy.themeFocusForWheel(-3, 2, UiInputIntent.NEXT));
    }

    @Test public void displayNoLongerOwnsTheStyleButStillIgnoresTheWheel() {
        // The orb style moved to its own Theme page; on Display the wheel never touches brightness.
        assertTrue(SettingsInputPolicy.consumeWheelWithoutAdjustment("Display", UiInputIntent.NEXT));
        assertTrue(SettingsInputPolicy.consumeWheelWithoutAdjustment("Display", UiInputIntent.PREVIOUS));
        assertFalse(SettingsInputPolicy.consumeWheelWithoutAdjustment("Theme", UiInputIntent.NEXT));
    }
}
