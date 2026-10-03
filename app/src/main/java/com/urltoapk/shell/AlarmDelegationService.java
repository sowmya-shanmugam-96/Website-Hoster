package com.urltoapk.shell;

import android.app.Notification;
import android.app.NotificationManager;

import com.google.androidbrowserhelper.trusted.DelegationService;

/**
 * Chrome hands every web push notification to this service. Notifications whose web tag
 * contains the configured marker are re-posted on the Alarms channel (see AlarmChannel);
 * everything else goes through the default DelegationService unchanged.
 *
 * A web page cannot pick a notification sound on Android 8+, because the channel decides
 * it, so the app does.
 */
public class AlarmDelegationService extends DelegationService {
    @Override
    public boolean onNotifyNotificationWithChannel(String platformTag, int platformId,
            Notification notification, String channelName) {
        if (!AlarmChannel.matches(this, platformTag)) {
            return super.onNotifyNotificationWithChannel(
                    platformTag, platformId, notification, channelName);
        }

        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null || !manager.areNotificationsEnabled()) return false;
        if (!AlarmChannel.ensure(this, manager)) return false;

        Notification alarm = Notification.Builder.recoverBuilder(this, notification)
                .setChannelId(AlarmChannel.ID)
                .build();
        manager.notify(platformTag, platformId, alarm);
        return true;
    }
}
