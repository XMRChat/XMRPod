package de.danoeh.antennapod.net.push;

import org.unifiedpush.android.connector.PushService;
import org.unifiedpush.android.connector.FailedReason;
import org.unifiedpush.android.connector.data.PushEndpoint;
import org.unifiedpush.android.connector.data.PushMessage;
import java.nio.charset.StandardCharsets;

public class LivePushService extends PushService {
    @Override
    public void onMessage(PushMessage message, String instance) {
        LiveNotifications notifications = LiveNotifications.get(this);
        if (notifications.accepts(instance) && message.getDecrypted()) {
            notifications.enqueue("message", new String(message.getContent(), StandardCharsets.UTF_8));
        }
    }

    @Override
    public void onNewEndpoint(PushEndpoint endpoint, String instance) {
        LiveNotifications notifications = LiveNotifications.get(this);
        if (notifications.accepts(instance)) {
            notifications.setEndpoint(endpoint);
        }
    }

    @Override
    public void onRegistrationFailed(FailedReason reason, String instance) {
        LiveNotifications notifications = LiveNotifications.get(this);
        if (notifications.accepts(instance)) {
            if (reason == FailedReason.ACTION_REQUIRED) {
                notifications.actionRequired();
            } else {
                notifications.unavailable();
            }
        }
    }

    @Override
    public void onUnregistered(String instance) {
        LiveNotifications notifications = LiveNotifications.get(this);
        if (notifications.accepts(instance)) {
            notifications.lostRegistration();
        }
    }

    @Override
    public void onTempUnavailable(String instance) {
        LiveNotifications notifications = LiveNotifications.get(this);
        if (notifications.accepts(instance)) {
            notifications.unavailable();
        }
    }
}
