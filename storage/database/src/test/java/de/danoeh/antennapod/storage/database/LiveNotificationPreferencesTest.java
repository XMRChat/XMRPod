package de.danoeh.antennapod.storage.database;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import de.danoeh.antennapod.model.feed.Feed;
import de.danoeh.antennapod.storage.preferences.UserPreferences;
import de.danoeh.antennapod.net.sync.serviceinterface.SynchronizationQueue;
import de.danoeh.antennapod.net.sync.serviceinterface.SynchronizationQueueStub;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import java.util.Collections;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
public class LiveNotificationPreferencesTest {
    private Context context;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        UserPreferences.init(context);
        PodDBAdapter.init(context);
        PodDBAdapter.deleteDatabase();
        SynchronizationQueue.setInstance(new SynchronizationQueueStub());
    }

    @Test
    public void newSubscriptionsStartOffAndEpisodeAlertsRemainIndependent() throws Exception {
        Feed feed = new Feed("https://example.com/feed", null, "Podcast");
        feed.setItems(Collections.emptyList());
        feed = FeedDatabaseWriter.updateFeed(context, feed, false);
        assertEquals(0, DBReader.getFeed(feed.getId(), false, 0, 0).getPreferences().getLiveNotificationSince());
        feed.getPreferences().setShowEpisodeNotification(true);
        feed.getPreferences().setLiveNotificationSince(123456789);
        DBWriter.setFeedPreferences(feed.getPreferences()).get();
        Feed stored = DBReader.getFeed(feed.getId(), false, 0, 0);
        assertEquals(123456789, stored.getPreferences().getLiveNotificationSince());
        assertTrue(stored.getPreferences().getShowEpisodeNotification());
        Feed refreshed = new Feed("https://example.com/feed", null, "Updated podcast");
        refreshed.setItems(Collections.emptyList());
        FeedDatabaseWriter.updateFeed(context, refreshed, false);
        stored = DBReader.getFeed(feed.getId(), false, 0, 0);
        assertEquals(123456789, stored.getPreferences().getLiveNotificationSince());
        assertTrue(stored.getPreferences().getShowEpisodeNotification());
        stored.getPreferences().setLiveNotificationSince(0);
        DBWriter.setFeedPreferences(stored.getPreferences()).get();
        assertTrue(DBReader.getFeed(feed.getId(), false, 0, 0).getPreferences().getShowEpisodeNotification());
    }

    @Test
    public void upgradePreservesLiveBellsFromEarlierDebugBuilds() {
        try (SQLiteDatabase database = SQLiteDatabase.create(null)) {
            database.execSQL("CREATE TABLE Feeds (_id INTEGER PRIMARY KEY, live_notification_since INTEGER DEFAULT 0)");
            database.execSQL("INSERT INTO Feeds (_id, live_notification_since) VALUES (1, 123456789)");
            DBUpgrader.upgrade(database, 3110000, 3110001);
            try (Cursor cursor = database.rawQuery("SELECT live_notification_since FROM Feeds", null)) {
                assertTrue(cursor.moveToFirst());
                assertEquals(123456789, cursor.getLong(0));
            }
        }
    }

    @Test
    public void upgradeKeepsEpisodePreferencesAndDefaultsLiveBellsOff() {
        try (SQLiteDatabase database = SQLiteDatabase.create(null)) {
            database.execSQL("CREATE TABLE Feeds (_id INTEGER PRIMARY KEY, episode_notification INTEGER DEFAULT 0)");
            database.execSQL("INSERT INTO Feeds (_id, episode_notification) VALUES (1, 1)");
            DBUpgrader.upgrade(database, 3110000, 3110001);
            try (Cursor cursor = database.rawQuery("SELECT episode_notification, live_notification_since FROM Feeds", null)) {
                assertTrue(cursor.moveToFirst());
                assertEquals(1, cursor.getInt(0));
                assertEquals(0, cursor.getLong(1));
            }
        }
    }
}
