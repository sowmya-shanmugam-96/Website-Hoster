package com.urltoapk.shell;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.text.TextUtils;

/** Builds and posts the notifications the native build receives through Firebase. */
final class AppNotifications {
    static final String DEFAULT_CHANNEL_ID = "notifications";

    private AppNotifications() {}

    /**
     * @param repeat for an alarm, keep playing its sound until the notification is tapped,
     *               dismissed or the shade is opened, instead of playing it once
     */
    static void show(Context context, String title, String body, String tag, String url,
            boolean repeat) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null || !manager.areNotificationsEnabled()) return;

        if (TextUtils.isEmpty(title)) title = context.getString(R.string.appName);
        if (body == null) body = "";
        // Same tag replaces the previous notification, as it does for web notifications.
        if (TextUtils.isEmpty(tag)) tag = "default";

        boolean alarm = AlarmChannel.matches(context, tag);
        String channelId;
        if (alarm) {
            // The user has turned the Alarms channel off: respect that, as the TWA build does.
            if (!AlarmChannel.ensure(context, manager)) return;
            channelId = AlarmChannel.ID;
        } else {
            manager.createNotificationChannel(new NotificationChannel(DEFAULT_CHANNEL_ID,
                    context.getString(R.string.notificationChannelName),
                    NotificationManager.IMPORTANCE_HIGH));
            channelId = DEFAULT_CHANNEL_ID;
        }

        Intent open = new Intent(context, WebAppActivity.class)
                .setAction(Intent.ACTION_VIEW)
                .putExtra(WebAppActivity.EXTRA_URL, resolve(context, url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent tap = PendingIntent.getActivity(context, tag.hashCode(), open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification notification = new Notification.Builder(context, channelId)
                .setSmallIcon(R.drawable.ic_notification)
                .setColor(context.getColor(R.color.themeColor))
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(new Notification.BigTextStyle().bigText(body))
                .setContentIntent(tap)
                .setAutoCancel(true)
                .setCategory(alarm ? Notification.CATEGORY_ALARM : Notification.CATEGORY_REMINDER)
                .build();
        if (alarm && repeat) notification.flags |= Notification.FLAG_INSISTENT;
        manager.notify(tag, 0, notification);
    }

    /** The page to open on tap: a path or same-site URL from the push, else the start page. */
    static String resolve(Context context, String url) {
        String start = context.getString(R.string.launchUrl);
        if (TextUtils.isEmpty(url)) return start;
        Uri base = Uri.parse(start);
        Uri target = Uri.parse(url);
        if (target.getScheme() == null && url.startsWith("/")) {
            return base.buildUpon().encodedPath(target.getEncodedPath())
                    .encodedQuery(target.getEncodedQuery())
                    .encodedFragment(target.getEncodedFragment())
                    .build().toString();
        }
        return WebAppActivity.sameSite(base, target) ? url : start;
    }
}
