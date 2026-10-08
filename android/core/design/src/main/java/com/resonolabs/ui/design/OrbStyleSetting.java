package com.resonolabs.ui.design;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The process-wide orb style (Settings > Display > Orb style). Hero orbs read {@link #current()}
 * on every draw, so a change shows up on the next frame everywhere; views that do not redraw on
 * their own can {@link #addListener listen}. Persisted in device-protected SharedPreferences.
 */
public final class OrbStyleSetting {
    static final String PREFS = "sam_orb_style";
    static final String KEY = "style";

    /** Called on the thread that changed the style (the main thread for the Settings UI). */
    public interface Listener {
        void onOrbStyleChanged(OrbStyle style);
    }

    /** Where the style is persisted (SharedPreferences on the device, a field in unit tests). */
    interface Store {
        String read();

        void write(String value);
    }

    private static final CopyOnWriteArrayList<Listener> LISTENERS = new CopyOnWriteArrayList<>();
    private static volatile OrbStyle current = OrbStyle.FLUID;
    private static Store store;

    private OrbStyleSetting() {}

    /** Loads the persisted style once per process; cheap to call again. */
    public static void init(Context context) {
        synchronized (OrbStyleSetting.class) {
            if (store != null) return;
        }
        Context app = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        // The app is direct-boot aware (it is HOME) and builds its hero orbs before the user is
        // unlocked: credential-encrypted SharedPreferences throw then, device-protected ones do not.
        SharedPreferences prefs;
        try {
            prefs = app.createDeviceProtectedStorageContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        } catch (RuntimeException unavailable) {
            Log.w("OrbStyle", "orb style storage unavailable; using the orb for now", unavailable);
            return; // stays FLUID; the next init retries
        }
        attach(new Store() {
            @Override public String read() {
                return prefs.getString(KEY, null);
            }

            @Override public void write(String value) {
                prefs.edit().putString(KEY, value).apply();
            }
        });
    }

    /** Uses {@code backing} for persistence (first caller wins) and loads the stored style. */
    static void attach(Store backing) {
        synchronized (OrbStyleSetting.class) {
            if (store != null) return;
            store = backing;
            current = OrbStyle.parse(backing.read());
        }
    }

    public static OrbStyle current() {
        return current;
    }

    /** Persists and applies {@code style}; listeners hear about real changes only. */
    public static void set(Context context, OrbStyle style) {
        init(context);
        apply(style);
    }

    static void apply(OrbStyle style) {
        if (style == null) style = OrbStyle.FLUID;
        Store backing;
        synchronized (OrbStyleSetting.class) {
            if (current == style) return;
            current = style;
            backing = store;
        }
        if (backing != null) backing.write(style.key());
        for (Listener listener : LISTENERS) listener.onOrbStyleChanged(style);
    }

    public static void addListener(Listener listener) {
        if (listener != null) LISTENERS.addIfAbsent(listener);
    }

    public static void removeListener(Listener listener) {
        LISTENERS.remove(listener);
    }

    /** Test hook: forget the store, listeners and style. */
    static void resetForTest() {
        synchronized (OrbStyleSetting.class) {
            store = null;
            current = OrbStyle.FLUID;
        }
        LISTENERS.clear();
    }
}
