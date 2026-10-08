package com.resonolabs.feature.settings;

import com.resonolabs.ui.input.UiInputIntent;

/** Input rules that keep the R1 wheel separate from device-setting mutations. */
final class SettingsInputPolicy {
    private SettingsInputPolicy() { }

    static boolean consumeWheelWithoutAdjustment(String page, UiInputIntent intent) {
        if (intent != UiInputIntent.PREVIOUS && intent != UiInputIntent.NEXT) return false;
        return "Sound".equals(page) || "Display".equals(page);
    }

    /**
     * Settings > Theme: the wheel only moves focus between the preview tiles (down = the next
     * tile, clamped at both ends); applying takes a tap or ACTIVATE. Other intents keep focus.
     */
    static int themeFocusForWheel(int focus, int tiles, UiInputIntent intent) {
        int current = Math.max(0, Math.min(tiles - 1, focus));
        if (intent == UiInputIntent.NEXT) return Math.min(tiles - 1, current + 1);
        if (intent == UiInputIntent.PREVIOUS) return Math.max(0, current - 1);
        return current;
    }
}
