package com.urltoapk.shell;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.media.AudioAttributes;
import android.net.Uri;

/**
 * The optional "Alarms" notification channel: notifications whose tag contains the configured
 * marker (R.string.alarmTag) play the sound bundled as res/raw/alarm_sound.* instead of the
 * phone's default. Used by both the Chrome (TWA) and the native build.
 */
final class AlarmChannel {
    // A channel's sound and vibration are fixed when it is created and cannot be changed
    // afterwards, so change this id whenever the sound or the vibration pattern changes, and
    // add the old one to RETIRED so it doesn't linger in the app's notification settings.
    static final String ID = "alarm_v3";
    private static final String[] RETIRED = {"alarm_v1", "alarm_v2"};

    private static final long[] VIBRATION =
            {0, 600, 200, 600, 200, 600, 200, 600, 200, 600, 200, 900};

    private AlarmChannel() {}

    /** True when an alarm sound is bundled, a marker is set, and this tag contains it. */
    static boolean matches(Context context, String tag) {
        String marker = context.getString(R.string.alarmTag);
        // Chrome's platform tag looks like p#<origin>#0<web tag>, so match anywhere in it.
        return !marker.isEmpty() && soundId(context) != 0 && tag != null && tag.contains(marker);
    }

    /** Creates the channel if needed. Returns false if the user has turned it off. */
    static boolean ensure(Context context, NotificationManager manager) {
        NotificationChannel channel = new NotificationChannel(ID,
                context.getString(R.string.alarmChannelName), NotificationManager.IMPORTANCE_HIGH);
        Uri sound = Uri.parse(
                "android.resource://" + context.getPackageName() + "/" + soundId(context));
        // USAGE_ALARM: played at the alarm volume, so it still sounds with the ringer on
        // vibrate or silent, the way a clock alarm does.
        channel.setSound(sound, new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build());
        channel.enableVibration(true);
        channel.setVibrationPattern(VIBRATION);
        // No-op if the channel already exists; the user's own changes to it are kept.
        manager.createNotificationChannel(channel);
        for (String old : RETIRED) manager.deleteNotificationChannel(old);

        NotificationChannel live = manager.getNotificationChannel(ID);
        return live == null || live.getImportance() != NotificationManager.IMPORTANCE_NONE;
    }

    // Looked up by name so the app still builds when no sound is bundled.
    private static int soundId(Context context) {
        return context.getResources().getIdentifier("alarm_sound", "raw", context.getPackageName());
    }
}
