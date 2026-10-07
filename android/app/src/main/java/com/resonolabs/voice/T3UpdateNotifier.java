package com.resonolabs.voice;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * "T3 updates" notifications for announcements that arrive while no voice session is live.
 * Tapping one opens that thread in the T3 tab ({@link #EXTRA_THREAD} on MainActivity). One
 * notification per thread: a newer update replaces the older one.
 */
final class T3UpdateNotifier {
    static final String CHANNEL = "t3_updates";
    static final String EXTRA_THREAD = "com.resonolabs.voice.extra.T3_THREAD";
    private static final String LOG_TAG = "SamAnnounce";

    private T3UpdateNotifier() {}

    static boolean post(Context context, String threadId, String title, String text) {
        try {
            NotificationManager notifications = context.getSystemService(NotificationManager.class);
            if (notifications == null) return false;
            NotificationChannel channel = new NotificationChannel(CHANNEL, "T3 updates",
                    NotificationManager.IMPORTANCE_DEFAULT);
            channel.setDescription("T3 Code threads that finished, failed or need you.");
            notifications.createNotificationChannel(channel);
            Intent open = new Intent(context, MainActivity.class)
                    .setAction("com.resonolabs.voice.OPEN_T3_THREAD")
                    .putExtra(EXTRA_THREAD, threadId)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            int id = AnnouncementRouting.notificationId(threadId);
            PendingIntent tap = PendingIntent.getActivity(context, id, open,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            Notification notification = new Notification.Builder(context, CHANNEL)
                    .setSmallIcon(android.R.drawable.stat_notify_chat)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setStyle(new Notification.BigTextStyle().bigText(text))
                    .setCategory(Notification.CATEGORY_STATUS)
                    .setContentIntent(tap)
                    .setAutoCancel(true)
                    .setWhen(System.currentTimeMillis())
                    .setShowWhen(true)
                    .build();
            notifications.notify("t3", id, notification);
            return true;
        } catch (RuntimeException error) {
            Log.w(LOG_TAG, "T3 notification failed: " + error.getClass().getSimpleName());
            return false;
        }
    }

    /** The thread was opened on the R1: its notification is no longer news. */
    static void cancel(Context context, String threadId) {
        NotificationManager notifications = context.getSystemService(NotificationManager.class);
        if (notifications != null) notifications.cancel("t3", AnnouncementRouting.notificationId(threadId));
    }
}
