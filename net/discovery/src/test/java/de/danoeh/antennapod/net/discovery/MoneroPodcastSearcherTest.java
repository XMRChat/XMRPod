package de.danoeh.antennapod.net.discovery;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

@RunWith(RobolectricTestRunner.class)
public class MoneroPodcastSearcherTest {

    @Test
    public void testPageWithFullMetadata() throws Exception {
        JSONObject page = page("somecreator", "Some Creator",
                links(rssLink("https://example.com/feed.xml"), websiteLink("https://example.com")),
                logo("https://files.xmrchat.com/logo.png", "https://files.xmrchat.com/logo-thumb.png"));

        PodcastSearchResult result = MoneroPodcastSearcher.parsePage(page);

        assertEquals("Some Creator", result.title);
        assertEquals("https://files.xmrchat.com/logo-thumb.png", result.imageUrl);
        assertEquals("https://example.com/feed.xml", result.feedUrl);
        assertEquals("", result.author);
    }

    @Test
    public void testRelativeLogoPathsResolveToImageServer() throws Exception {
        JSONObject page = page("somecreator", "Some Creator",
                links(rssLink("https://example.com/feed.xml")),
                logo("/images/logo.jpg", "/thumbnails/thumbnail-logo.jpg"));

        assertEquals("https://s3.xmrchat.com/thumbnails/thumbnail-logo.jpg",
                MoneroPodcastSearcher.parsePage(page).imageUrl);
    }

    @Test
    public void testLogoFallsBackToFullUrlWhenThumbnailMissing() throws Exception {
        JSONObject page = page("somecreator", "Some Creator",
                links(rssLink("https://example.com/feed.xml")),
                logo("https://files.xmrchat.com/logo.png", null));

        assertEquals("https://files.xmrchat.com/logo.png", MoneroPodcastSearcher.parsePage(page).imageUrl);
    }

    @Test
    public void testPageWithoutLogoHasNullImageUrl() throws Exception {
        JSONObject page = page("somecreator", "Some Creator", links(rssLink("https://example.com/feed.xml")), null);

        assertNull(MoneroPodcastSearcher.parsePage(page).imageUrl);
    }

    @Test
    public void testMissingNameFallsBackToPath() throws Exception {
        JSONObject page = page("somecreator", null, links(rssLink("https://example.com/feed.xml")), null);

        assertEquals("somecreator", MoneroPodcastSearcher.parsePage(page).title);
    }

    @Test
    public void testEmptyRssLinkValueIsSkipped() throws Exception {
        JSONObject page = page("somecreator", "Some Creator",
                links(rssLink("  "), websiteLink("https://example.com")), null);

        assertNull(MoneroPodcastSearcher.parsePage(page));
    }

    @Test
    public void testPageWithoutRssLinkIsSkipped() throws Exception {
        JSONObject page = page("somecreator", "Some Creator",
                links(websiteLink("https://example.com")), logo("https://files.xmrchat.com/logo.png", null));

        assertNull(MoneroPodcastSearcher.parsePage(page));
    }

    @Test
    public void testNullPageIsSkipped() {
        assertNull(MoneroPodcastSearcher.parsePage(null));
    }

    private static JSONObject page(String path, String name, JSONArray links, JSONObject logo) throws Exception {
        JSONObject page = new JSONObject();
        page.put("path", path);
        if (name != null) {
            page.put("name", name);
        }
        page.put("links", links);
        if (logo != null) {
            page.put("logo", logo);
        }
        return page;
    }

    private static JSONArray links(JSONObject... entries) throws Exception {
        JSONArray array = new JSONArray();
        for (JSONObject entry : entries) {
            array.put(entry);
        }
        return array;
    }

    private static JSONObject rssLink(String value) throws Exception {
        return link("podcast-rss", value);
    }

    private static JSONObject websiteLink(String value) throws Exception {
        return link("website", value);
    }

    private static JSONObject link(String platform, String value) throws Exception {
        JSONObject link = new JSONObject();
        link.put("platform", platform);
        link.put("value", value);
        return link;
    }

    private static JSONObject logo(String url, String thumbnail) throws Exception {
        JSONObject logo = new JSONObject();
        logo.put("url", url);
        if (thumbnail != null) {
            logo.put("thumbnail", thumbnail);
        }
        return logo;
    }
}
