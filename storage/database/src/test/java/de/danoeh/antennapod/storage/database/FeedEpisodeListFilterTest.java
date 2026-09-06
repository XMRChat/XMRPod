package de.danoeh.antennapod.storage.database;

import android.content.Context;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.util.ArrayList;
import java.util.Date;

import de.danoeh.antennapod.model.feed.Feed;
import de.danoeh.antennapod.model.feed.FeedItem;
import de.danoeh.antennapod.model.feed.FeedMedia;
import de.danoeh.antennapod.storage.preferences.UserPreferences;

import static org.junit.Assert.assertEquals;

@RunWith(RobolectricTestRunner.class)
public class FeedEpisodeListFilterTest {
    private Context context;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        UserPreferences.init(context);
        PodDBAdapter.init(context);
        PodDBAdapter.deleteDatabase();
        PodDBAdapter adapter = PodDBAdapter.getInstance();
        adapter.open();
        adapter.close();
    }

    @Test
    public void testEpisodeListOnlyShowsItemsWithMedia() {
        Feed feed = createFeed();
        feed.getItems().add(itemWithMedia("episode-1", "Episode 1", feed));
        feed.getItems().add(articleItem("article-1", "Blog post", feed));
        feed.getItems().add(itemWithMedia("episode-2", "Episode 2", feed));

        PodDBAdapter adapter = PodDBAdapter.getInstance();
        adapter.open();
        adapter.setCompleteFeed(feed);
        adapter.close();

        Feed filteredFeed = DBReader.getFeed(feed.getId(), true, 0, Integer.MAX_VALUE);
        assertEquals(2, filteredFeed.getItems().size());
        java.util.Set<String> identifiers = new java.util.HashSet<>();
        for (FeedItem item : filteredFeed.getItems()) {
            identifiers.add(item.getItemIdentifier());
        }
        assertEquals(new java.util.HashSet<>(java.util.Arrays.asList("episode-1", "episode-2")), identifiers);

        // Unfiltered access (used by settings, info screens, etc.) still returns everything.
        Feed completeFeed = DBReader.getFeed(feed.getId(), false, 0, Integer.MAX_VALUE);
        assertEquals(3, completeFeed.getItems().size());
    }

    @Test
    public void testFeedWithOnlyArticlesShowsEmptyList() {
        Feed feed = createFeed();
        feed.getItems().add(articleItem("article-1", "Blog post", feed));

        PodDBAdapter adapter = PodDBAdapter.getInstance();
        adapter.open();
        adapter.setCompleteFeed(feed);
        adapter.close();

        Feed filteredFeed = DBReader.getFeed(feed.getId(), true, 0, Integer.MAX_VALUE);
        assertEquals(0, filteredFeed.getItems().size());
    }

    private Feed createFeed() {
        Feed feed = new Feed("url", null, null);
        feed.setItems(new ArrayList<>());
        return feed;
    }

    private FeedItem itemWithMedia(String identifier, String title, Feed feed) {
        FeedItem item = new FeedItem(0, title, identifier, "link", new Date(), FeedItem.UNPLAYED, feed);
        item.setMedia(new FeedMedia(item, "https://example.com/" + identifier + ".mp3", 123, "audio/mpeg"));
        return item;
    }

    private FeedItem articleItem(String identifier, String title, Feed feed) {
        return new FeedItem(0, title, identifier, "link", new Date(), FeedItem.UNPLAYED, feed);
    }
}
