package com.resonolabs.voice;

import android.app.Notification;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.util.ArrayList;
import java.util.List;

/** Posted notifications for the Control Center, fed by the system notification listener. */
public final class NotificationFeed extends NotificationListenerService {
    record Item(String key, String app, String title, String text, long when, boolean clearable,
                android.app.PendingIntent intent) {}

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile NotificationFeed connected;
    private static volatile List<Item> items = List.of();
    private static Runnable observer;

    /** Grants this HOME its own listener slot; needs WRITE_SECURE_SETTINGS (privileged). */
    static void ensureEnabled(Context context) {
        ComponentName component = new ComponentName(context, NotificationFeed.class);
        String flat = component.flattenToString();
        try {
            String current = Settings.Secure.getString(context.getContentResolver(),
                    "enabled_notification_listeners");
            if (current != null && current.contains(flat)) return;
            String next = current == null || current.isBlank() ? flat : current + ":" + flat;
            Settings.Secure.putString(context.getContentResolver(), "enabled_notification_listeners", next);
        } catch (SecurityException ignored) {
            // Without the privilege the Control Center simply shows no notifications.
        }
    }

    static List<Item> items() {
        return items;
    }

    static void observe(Runnable onChange) {
        observer = onChange;
    }

    /** Tap on a notification row: fire its content intent and clear it if auto-cancel. */
    static boolean open(Item item) {
        if (item == null || item.intent() == null) return false;
        try {
            item.intent().send();
        } catch (android.app.PendingIntent.CanceledException | RuntimeException ignored) {
            return false;
        }
        NotificationFeed service = connected;
        if (service != null && item.clearable()) {
            try { service.cancelNotification(item.key()); } catch (RuntimeException ignored) { }
        }
        return true;
    }

    static void dismissAll() {
        NotificationFeed service = connected;
        if (service != null) {
            try { service.cancelAllNotifications(); } catch (RuntimeException ignored) { }
        }
    }

    @Override public void onListenerConnected() {
        connected = this;
        refresh();
    }

    @Override public void onListenerDisconnected() {
        connected = null;
    }

    @Override public void onNotificationPosted(StatusBarNotification notification) {
        refresh();
    }

    @Override public void onNotificationRemoved(StatusBarNotification notification) {
        refresh();
    }

    private void refresh() {
        List<Item> next = new ArrayList<>();
        StatusBarNotification[] active;
        try { active = getActiveNotifications(); } catch (RuntimeException ignored) { active = null; }
        if (active != null) {
            for (StatusBarNotification posted : active) {
                Notification notification = posted.getNotification();
                if ((notification.flags & Notification.FLAG_GROUP_SUMMARY) != 0) continue;
                CharSequence title = notification.extras.getCharSequence(Notification.EXTRA_TITLE);
                CharSequence text = notification.extras.getCharSequence(Notification.EXTRA_TEXT);
                if ((title == null || title.length() == 0) && (text == null || text.length() == 0)) continue;
                next.add(new Item(posted.getKey(), appLabel(posted.getPackageName()),
                        title == null ? "" : title.toString(), text == null ? "" : text.toString(),
                        posted.getPostTime(), posted.isClearable(), notification.contentIntent));
            }
        }
        next.sort((left, right) -> Long.compare(right.when(), left.when()));
        items = List.copyOf(next);
        MAIN.post(() -> {
            Runnable current = observer;
            if (current != null) current.run();
        });
    }

    private String appLabel(String packageName) {
        try {
            PackageManager packages = getPackageManager();
            return packages.getApplicationLabel(packages.getApplicationInfo(packageName, 0)).toString();
        } catch (PackageManager.NameNotFoundException ignored) {
            return packageName;
        }
    }
}
