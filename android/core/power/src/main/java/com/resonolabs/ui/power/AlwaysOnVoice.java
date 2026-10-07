package com.resonolabs.ui.power;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * "Always-on voice" (Settings &gt; Sound, default on). On: a single side-button press only
 * sleeps the screen while a voice session keeps running (held by a microphone foreground
 * service), and a session that drops unexpectedly reconnects by itself. Off: the screen going
 * off ends the session, as before.
 *
 * <p>Stored in device-protected storage because HOME is direct-boot aware.
 */
public final class AlwaysOnVoice {
    private static final String PREFS = "sam_voice";
    private static final String KEY = "always_on";
    private static SharedPreferences prefs;

    private AlwaysOnVoice() {}

    public static boolean isEnabled(Context context) {
        return prefs(context).getBoolean(KEY, true);
    }

    public static void setEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY, enabled).apply();
    }

    private static synchronized SharedPreferences prefs(Context context) {
        if (prefs == null) {
            Context app = context.getApplicationContext() != null ? context.getApplicationContext() : context;
            prefs = app.createDeviceProtectedStorageContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        }
        return prefs;
    }
}
