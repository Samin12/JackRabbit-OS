package com.resonolabs.feature.settings;

import com.resonolabs.ui.design.OrbStyle;
import com.resonolabs.ui.input.UiInputIntent;

/** Input rules that keep the R1 wheel separate from device-setting mutations. */
final class SettingsInputPolicy {
    private SettingsInputPolicy() { }

    static boolean consumeWheelWithoutAdjustment(String page, UiInputIntent intent) {
        if (intent != UiInputIntent.PREVIOUS && intent != UiInputIntent.NEXT) return false;
        return "Sound".equals(page) || "Display".equals(page);
    }

    /**
     * On the Display page the wheel picks the orb style (down = Pixel head, up = Orb): a purely
     * visual, instantly reversible choice, unlike brightness, which stays on its buttons.
     */
    static OrbStyle orbStyleForWheel(String page, UiInputIntent intent) {
        if (!"Display".equals(page)) return null;
        if (intent == UiInputIntent.NEXT) return OrbStyle.PIXEL_HEAD;
        if (intent == UiInputIntent.PREVIOUS) return OrbStyle.FLUID;
        return null;
    }
}
