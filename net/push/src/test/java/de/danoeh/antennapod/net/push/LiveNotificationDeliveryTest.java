package de.danoeh.antennapod.net.push;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.service.notification.StatusBarNotification;
import de.danoeh.antennapod.model.feed.Feed;
import de.danoeh.antennapod.net.sync.serviceinterface.SynchronizationQueue;
import de.danoeh.antennapod.net.sync.serviceinterface.SynchronizationQueueStub;
import de.danoeh.antennapod.storage.database.DBWriter;
import de.danoeh.antennapod.storage.database.FeedDatabaseWriter;
import de.danoeh.antennapod.storage.database.PodDBAdapter;
import de.danoeh.antennapod.storage.preferences.UserPreferences;
import de.danoeh.antennapod.ui.notifications.NotificationUtils;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.time.Instant;
import java.util.Collections;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class LiveNotificationDeliveryTest {
    @Test
    public void liveChannelVibratesAndReplacesOldChannel() {
        Context context = RuntimeEnvironment.getApplication();
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        manager.createNotificationChannel(new NotificationChannel("live_notifications", "Live streams",
                NotificationManager.IMPORTANCE_DEFAULT));
        NotificationUtils.createChannels(context);
        assertNull(manager.getNotificationChannel("live_notifications"));
        NotificationChannel channel = manager.getNotificationChannel(NotificationUtils.CHANNEL_ID_LIVE_NOTIFICATIONS);
        assertTrue(channel.shouldVibrate());
        assertNotNull(channel.getSound());
        NotificationChannel vibrationOnly = manager.getNotificationChannel(
                NotificationUtils.CHANNEL_ID_LIVE_NOTIFICATIONS_VIBRATE_ONLY);
        assertTrue(vibrationOnly.shouldVibrate());
        assertNull(vibrationOnly.getSound());
    }

    @Test
    public void baselineIsSilentAndPushPollingShareDeduplicationAndRespectDisabledBells() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        UserPreferences.init(context);
        PodDBAdapter.init(context);
        PodDBAdapter.deleteDatabase();
        SynchronizationQueue.setInstance(new SynchronizationQueueStub());
        NotificationUtils.createChannels(context);
        Feed feed = new Feed("https://example.com/feed", null, "Test podcast");
        feed.setItems(Collections.emptyList());
        feed.setState(Feed.STATE_SUBSCRIBED);
        feed = FeedDatabaseWriter.updateFeed(context, feed, false);
        long since = System.currentTimeMillis() - 20_000;
        feed.getPreferences().setLiveNotificationSince(since);
        DBWriter.setFeedPreferences(feed.getPreferences()).get();
        LiveNotifications notifications = LiveNotifications.get(context);
        notifications.getPreferences().edit().clear().commit();
        notifications.initialize(ignored -> "creator");
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);

        JSONArray baseline = snapshot("already-live", since + 1000);
        notifications.checkSnapshot(baseline, null, null);
        notifications.checkSnapshot(baseline, null, null);
        assertEquals(0, manager.getActiveNotifications().length);

        JSONArray next = snapshot("new-broadcast", since + 2000);
        notifications.checkSnapshot(next, "creator", "new-broadcast");
        notifications.checkSnapshot(next, null, null);
        assertEquals(1, manager.getActiveNotifications().length);

        JSONArray later = snapshot("later-broadcast", since + 3000);
        notifications.checkSnapshot(later, "creator", "later-broadcast");
        assertEquals(2, manager.getActiveNotifications().length);
        for (StatusBarNotification posted : manager.getActiveNotifications()) {
            assertEquals(NotificationUtils.CHANNEL_ID_LIVE_NOTIFICATIONS, posted.getNotification().getChannelId());
        }

        feed.getPreferences().setLiveNotificationVibrateOnly(true);
        DBWriter.setFeedPreferences(feed.getPreferences()).get();
        notifications.checkSnapshot(snapshot("quiet-broadcast", since + 3500), "creator", "quiet-broadcast");
        assertEquals(3, manager.getActiveNotifications().length);
        int quiet = 0;
        for (StatusBarNotification posted : manager.getActiveNotifications()) {
            if (NotificationUtils.CHANNEL_ID_LIVE_NOTIFICATIONS_VIBRATE_ONLY.equals(
                    posted.getNotification().getChannelId())) {
                assertNull(posted.getNotification().sound);
                quiet++;
            }
        }
        assertEquals(1, quiet);

        feed.getPreferences().setLiveNotificationSince(0);
        DBWriter.setFeedPreferences(feed.getPreferences()).get();
        notifications.checkSnapshot(snapshot("disabled", since + 4000), "creator", "disabled");
        assertEquals(3, manager.getActiveNotifications().length);
    }

    private JSONArray snapshot(String session, long started) throws Exception {
        return new JSONArray().put(new JSONObject()
                .put("notificationSessionId", session)
                .put("notificationStartedAt", Instant.ofEpochMilli(started).toString())
                .put("page", new JSONObject().put("path", "creator")));
    }
}
