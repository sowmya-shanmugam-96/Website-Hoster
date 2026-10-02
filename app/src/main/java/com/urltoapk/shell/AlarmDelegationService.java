package com.urltoapk.shell;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.media.AudioAttributes;
import android.net.Uri;

import com.google.androidbrowserhelper.trusted.DelegationService;

/**
 * Chrome hands every web push notification to this service. Notifications whose web tag
 * contains the configured marker (R.string.alarmTag) are re-posted on a separate "alarm"
 * channel that plays the sound bundled as res/raw/alarm_sound.*; everything else goes
 * through the default DelegationService unchanged.
 *
 * A web page cannot pick a notification sound on Android 8+, because the channel decides
 * it, so the app does.
 */
public class AlarmDelegationService extends DelegationService {
    // A channel's sound and vibration are fixed when it is created and cannot be changed
    // afterwards, so change this id whenever the sound or the vibration pattern changes.
    private static final String ALARM_CHANNEL_ID = "alarm_v1";

    private static final long[] ALARM_VIBRATION =
            {0, 600, 200, 600, 200, 600, 200, 600, 200, 600, 200, 900};

    @Override
    public boolean onNotifyNotificationWithChannel(String platformTag, int platformId,
            Notification notification, String channelName) {
        String marker = getString(R.string.alarmTag);
        // Looked up by name so the app still builds when no sound is bundled.
        int soundId = getResources().getIdentifier("alarm_sound", "raw", getPackageName());

        // Chrome's platform tag looks like p#<origin>#0<web tag>, so match anywhere in it.
        if (marker.isEmpty() || soundId == 0 || platformTag == null
                || !platformTag.contains(marker)) {
            return super.onNotifyNotificationWithChannel(
                    platformTag, platformId, notification, channelName);
        }

        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null || !manager.areNotificationsEnabled()) return false;

        NotificationChannel channel = new NotificationChannel(ALARM_CHANNEL_ID,
                getString(R.string.alarmChannelName), NotificationManager.IMPORTANCE_HIGH);
        Uri sound = Uri.parse("android.resource://" + getPackageName() + "/" + soundId);
        channel.setSound(sound, new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build());
        channel.enableVibration(true);
        channel.setVibrationPattern(ALARM_VIBRATION);
        // No-op if the channel already exists; the user's own changes to it are kept.
        manager.createNotificationChannel(channel);

        NotificationChannel live = manager.getNotificationChannel(ALARM_CHANNEL_ID);
        if (live != null && live.getImportance() == NotificationManager.IMPORTANCE_NONE) {
            return false;
        }

        Notification alarm = Notification.Builder.recoverBuilder(this, notification)
                .setChannelId(ALARM_CHANNEL_ID)
                .build();
        manager.notify(platformTag, platformId, alarm);
        return true;
    }
}
