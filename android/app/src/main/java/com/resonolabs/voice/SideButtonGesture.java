package com.resonolabs.voice;

import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.provider.Settings;
import android.util.Log;

/**
 * Side button (KEYCODE_POWER) double press → Voice toggle.
 *
 * <p>The framework owns the power key: PhoneWindowManager never passes it to apps. The
 * {@code com.resonolabs.overlay.powerbutton} RRO (android/system/power-overlay) sets
 * {@code config_doublePressOnPowerBehavior=3}, so on the second key-down of a double press
 * (down-to-down &lt; 300 ms, fixed by the framework) PWM starts the non-exported
 * {@code .SideButtonToggle} activity-alias of {@link MainActivity} as uid 1000. The alias component
 * in the delivered intent is what tells a double press apart from any other HOME launch.
 */
final class SideButtonGesture {
    static final String LOG_TAG = "SamSideButton";
    /** Class name of the activity-alias in AndroidManifest.xml (no build-type suffix). */
    static final String TOGGLE_ALIAS = "com.resonolabs.voice.SideButtonToggle";
    /** PhoneWindowManager.MULTI_PRESS_POWER_LAUNCH_TARGET_ACTIVITY. */
    static final String LAUNCH_TARGET_ACTIVITY = "3";
    static final String DOUBLE_PRESS_SETTING = "power_button_double_press";
    /**
     * Two genuine double presses are always more than 300 ms apart (a third press inside the window
     * only raises PWM's press counter), so this only drops duplicate deliveries of one gesture.
     */
    static final long MIN_TOGGLE_INTERVAL_MS = 300L;

    private long lastToggleAt = Long.MIN_VALUE;

    static boolean isToggle(Intent intent) {
        if (intent == null) return false;
        ComponentName component = intent.getComponent();
        return component != null && isToggleClassName(component.getClassName());
    }

    static boolean isToggleClassName(String className) {
        return TOGGLE_ALIAS.equals(className);
    }

    /** {@code <package>/<alias>} exactly as the overlay must spell config_doublePressOnPowerTargetActivity. */
    static String overlayTargetFor(String packageName) {
        return packageName + "/" + TOGGLE_ALIAS;
    }

    /**
     * Whether to rewrite {@code Settings.Global power_button_double_press}. PWM reads that key live
     * and it beats the overlay's default, so a stale 0 (e.g. a settings toggle) would silently
     * disable the gesture. Only touch it when the overlay is really pointing at this app, an
     * explicit value exists and it is not already the launch-target behaviour.
     */
    static boolean shouldRestoreDoublePress(String overlayTarget, String expectedTarget, String current) {
        if (overlayTarget == null || !overlayTarget.equals(expectedTarget)) return false;
        return current != null && !LAUNCH_TARGET_ACTIVITY.equals(current.trim());
    }

    /** Debounce for one physical gesture. Returns false when {@code nowMs} is a duplicate. */
    boolean accept(long nowMs) {
        if (lastToggleAt != Long.MIN_VALUE && nowMs - lastToggleAt < MIN_TOGGLE_INTERVAL_MS) return false;
        lastToggleAt = nowMs;
        return true;
    }

    /**
     * Logs the live framework policy and repairs an overriding Global setting. Never throws: on a
     * device without the overlay this only logs that the gesture is unavailable.
     */
    static void checkFrameworkPolicy(Context context) {
        try {
            String expected = overlayTargetFor(context.getPackageName());
            String target = frameworkString(context.getResources(), "config_doublePressOnPowerTargetActivity");
            ContentResolver resolver = context.getContentResolver();
            String current = Settings.Global.getString(resolver, DOUBLE_PRESS_SETTING);
            if (shouldRestoreDoublePress(target, expected, current)) {
                Settings.Global.putString(resolver, DOUBLE_PRESS_SETTING, LAUNCH_TARGET_ACTIVITY);
                Log.i(LOG_TAG, DOUBLE_PRESS_SETTING + " was " + current + "; restored "
                        + LAUNCH_TARGET_ACTIVITY);
            }
            Log.i(LOG_TAG, "double press " + (expected.equals(target) ? "ready" : "unavailable")
                    + ": target=" + (target == null || target.isEmpty() ? "<none>" : target)
                    + " setting=" + current);
        } catch (RuntimeException error) {
            Log.w(LOG_TAG, "double press policy check failed: " + error);
        }
    }

    @SuppressWarnings("DiscouragedApi") // The config is internal to the framework; read by name.
    private static String frameworkString(Resources resources, String name) {
        int id = resources.getIdentifier(name, "string", "android");
        return id == 0 ? null : resources.getString(id);
    }
}
