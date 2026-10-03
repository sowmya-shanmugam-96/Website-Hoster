package com.urltoapk.shell;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

import java.util.Map;

/**
 * Receives the server's Firebase pushes and shows them.
 *
 * The server sends data-only messages (title, body, tag, url) so that this runs for every
 * push, whether the app is open, in the background or closed, and the notification is
 * always built here, where the channel (and so the sound) is chosen.
 */
public class PushMessagingService extends FirebaseMessagingService {
    @Override
    public void onMessageReceived(RemoteMessage message) {
        Map<String, String> data = message.getData();
        String title = data.get("title");
        String body = data.get("body");
        if (message.getNotification() != null) {
            if (title == null) title = message.getNotification().getTitle();
            if (body == null) body = message.getNotification().getBody();
        }
        AppNotifications.show(this, title, body, data.get("tag"), data.get("url"));
    }

    @Override
    public void onNewToken(String token) {
        // Nothing to do here: the page asks for the current token through the NativePush
        // bridge each time it loads and re-registers it with the server if it changed.
    }
}
