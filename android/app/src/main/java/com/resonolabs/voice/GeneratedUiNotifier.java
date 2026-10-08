package com.resonolabs.voice;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * "Generated UIs" notifications for UIs the Mac finished (or failed) while no voice session was
 * live. Tapping one opens Voice with the picture full screen ({@link #EXTRA_ARTIFACT} on
 * MainActivity). One notification per artifact.
 */
final class GeneratedUiNotifier {
    static final String CHANNEL = "generated_ui";
    /** Same notifications without sound (synthetic/debug items; the user sits next to the R1). */
    static final String CHANNEL_QUIET = "generated_ui_quiet";
    static final String ACTION = "com.resonolabs.voice.OPEN_GENERATED_UI";
    static final String EXTRA_ARTIFACT = "com.resonolabs.voice.extra.UI_ARTIFACT";
    private static final String LOG_TAG = "SamAnnounce";

    private GeneratedUiNotifier() {}

    /** {@code silent}: no sound or vibration (debug hooks: the user sits next to the R1). */
    static boolean post(Context context, String artifactId, String title, String text, boolean silent) {
        try {
            NotificationManager notifications = context.getSystemService(NotificationManager.class);
            if (notifications == null) return false;
            String channelId = silent ? CHANNEL_QUIET : CHANNEL;
            NotificationChannel channel = new NotificationChannel(channelId,
                    silent ? "Generated UIs (silent)" : "Generated UIs",
                    silent ? NotificationManager.IMPORTANCE_LOW : NotificationManager.IMPORTANCE_DEFAULT);
            channel.setDescription("Charts and other UIs your Mac generated for a voice conversation.");
            notifications.createNotificationChannel(channel);
            Intent open = new Intent(context, MainActivity.class)
                    .setAction(ACTION)
                    .putExtra(EXTRA_ARTIFACT, artifactId == null ? "" : artifactId)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            int id = AnnouncementRouting.uiNotificationId(artifactId);
            PendingIntent tap = PendingIntent.getActivity(context, id, open,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            Notification notification = new Notification.Builder(context, channelId)
                    .setSmallIcon(android.R.drawable.ic_menu_gallery)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setStyle(new Notification.BigTextStyle().bigText(text))
                    .setCategory(Notification.CATEGORY_STATUS)
                    .setContentIntent(tap)
                    .setAutoCancel(true)
                    .setWhen(System.currentTimeMillis())
                    .setShowWhen(true)
                    .build();
            notifications.notify("ui", id, notification);
            return true;
        } catch (RuntimeException error) {
            Log.w(LOG_TAG, "generated UI notification failed: " + error.getClass().getSimpleName());
            return false;
        }
    }

    static void cancel(Context context, String artifactId) {
        NotificationManager notifications = context.getSystemService(NotificationManager.class);
        if (notifications != null) notifications.cancel("ui", AnnouncementRouting.uiNotificationId(artifactId));
    }
}
