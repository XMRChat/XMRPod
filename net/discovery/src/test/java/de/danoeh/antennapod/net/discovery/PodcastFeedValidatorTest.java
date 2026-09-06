package de.danoeh.antennapod.net.discovery;

import android.content.Context;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
public class PodcastFeedValidatorTest {

    private static final String RSS_WITH_ENCLOSURE =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<rss version=\"2.0\" xmlns:itunes=\"http://www.itunes.com/dtds/podcast-1.0.dtd\">\n"
            + "<channel><title>Some Podcast</title><item><title>Episode 1</title>"
            + "<enclosure url=\"https://example.com/ep1.mp3\" type=\"audio/mpeg\" length=\"123\"/>"
            + "</item></channel></rss>";

    private static final String RSS_WITHOUT_ENCLOSURE =
            "<?xml version=\"1.0\"?>\n"
            + "<rss version=\"2.0\"><channel><title>Some Blog</title>"
            + "<item><title>Blog post</title><description>Hello</description></item>"
            + "</channel></rss>";

    private static final String HTML_PAGE =
            "<!DOCTYPE html>\n"
            + "<html lang=\"en\"><head><title>Some website</title></head>"
            + "<body><p>Welcome to our podcast page!</p></body></html>";

    private static final String ATOM_WITH_ENCLOSURE =
            "<?xml version=\"1.0\"?>\n"
            + "<feed xmlns=\"http://www.w3.org/2005/Atom\"><title>Atom Podcast</title>"
            + "<entry><title>Episode 1</title>"
            + "<link rel=\"enclosure\" href=\"https://example.com/ep1.ogg\" type=\"audio/ogg\"/>"
            + "</entry></feed>";

    private static final String NON_FEED_XML =
            "<?xml version=\"1.0\"?>\n"
            + "<smil><body><par><audio src=\"x.mp3\"/></par></body></smil>";

    private static String rssItems(String... itemBodies) {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\"?><rss version=\"2.0\"><channel><title>Feed</title>");
        for (String item : itemBodies) {
            sb.append("<item>").append(item).append("</item>");
        }
        sb.append("</channel></rss>");
        return sb.toString();
    }

    private static String audioItem(String url, String type) {
        return "<title>Episode</title><enclosure url=\"" + url + "\"" + type + " length=\"1\"/>";
    }

    private static String articleItem() {
        return "<title>Article</title><description>Text only</description>";
    }

    @Test
    public void testRssFeedWithEnclosureIsValid() {
        PodcastFeedValidator.Classification result = classify(RSS_WITH_ENCLOSURE, 200);
        assertTrue(result.verdict.ok);
        assertEquals(PodcastFeedValidator.REASON_OK, result.verdict.reason);
        assertEquals(java.util.Collections.singletonList("https://example.com/ep1.mp3"), result.mediaUrls);
    }

    @Test
    public void testAtomFeedWithEnclosureIsValid() {
        PodcastFeedValidator.Classification result = classify(ATOM_WITH_ENCLOSURE, 200);
        assertTrue(result.verdict.ok);
        assertEquals(java.util.Collections.singletonList("https://example.com/ep1.ogg"), result.mediaUrls);
    }

    @Test
    public void testBlogRssWithoutEnclosuresIsRejected() {
        assertEquals(PodcastFeedValidator.REASON_NO_ENCLOSURES, classify(RSS_WITHOUT_ENCLOSURE, 200).verdict.reason);
    }

    @Test
    public void testArticleHeavyFeedIsRejectedAsNoRecentAudio() {
        // Enclosure exists, but only after RECENT_ITEM_LIMIT article-only items.
        String[] items = new String[PodcastFeedValidator.RECENT_ITEM_LIMIT + 4];
        for (int i = 0; i < items.length - 1; i++) {
            items[i] = articleItem();
        }
        items[items.length - 1] = audioItem("https://example.com/old.mp3", " type=\"audio/mpeg\"");
        assertEquals(PodcastFeedValidator.REASON_NO_RECENT_AUDIO,
                classify(rssItems(items), 200).verdict.reason);
    }

    @Test
    public void testEnclosureWithinRecentItemsIsValid() {
        String feed = rssItems(articleItem(), articleItem(),
                audioItem("https://example.com/ep1.mp3", " type=\"audio/mpeg\""));
        PodcastFeedValidator.Classification result = classify(feed, 200);
        assertTrue(result.verdict.ok);
        assertEquals(java.util.Collections.singletonList("https://example.com/ep1.mp3"), result.mediaUrls);
    }

    @Test
    public void testNonAudioEnclosureIsSkippedInFavorOfAudio() {
        String feed = rssItems(
                "<title>Show notes</title><enclosure url=\"https://example.com/cover.jpeg\""
                        + " type=\"image/jpeg\" length=\"1\"/>",
                audioItem("https://example.com/ep1.mp3", " type=\"audio/mpeg\""));
        PodcastFeedValidator.Classification result = classify(feed, 200);
        assertTrue(result.verdict.ok);
        assertEquals(java.util.Collections.singletonList("https://example.com/ep1.mp3"), result.mediaUrls);
    }

    @Test
    public void testEnclosureUrlCaseIsPreserved() {
        String feed = rssItems(audioItem("https://example.com/Eps/Episode_1.MP3", " type=\"audio/mpeg\""));
        assertEquals(java.util.Collections.singletonList("https://example.com/Eps/Episode_1.MP3"), classify(feed, 200).mediaUrls);
    }

    @Test
    public void testMultipleEnclosureUrlsAreSampled() {
        // Feeds can carry a dead live-stream entry before the real episodes; the media check
        // needs several candidate URLs so a single dead one does not reject the feed.
        String feed = rssItems(
                audioItem("http://stream.example.com/live", " type=\"audio/mpeg\""),
                audioItem("https://example.com/ep1.mp3", " type=\"audio/mpeg\""),
                audioItem("https://example.com/ep2.mp3", " type=\"audio/mpeg\""),
                audioItem("https://example.com/ep3.mp3", " type=\"audio/mpeg\""));
        assertEquals(java.util.Arrays.asList(
                "http://stream.example.com/live",
                "https://example.com/ep1.mp3",
                "https://example.com/ep2.mp3"),
                classify(feed, 200).mediaUrls);
    }

    @Test
    public void testHtmlPageIsRejectedAsWebsite() {
        assertEquals(PodcastFeedValidator.REASON_WEBSITE, classify(HTML_PAGE, 200).verdict.reason);
    }

    @Test
    public void testNonFeedXmlIsRejected() {
        assertEquals(PodcastFeedValidator.REASON_NOT_A_FEED, classify(NON_FEED_XML, 200).verdict.reason);
    }

    @Test
    public void testHttpErrorIsRejected() {
        assertEquals(PodcastFeedValidator.REASON_HTTP_ERROR + "_403",
                classify(HTML_PAGE, 403).verdict.reason);
    }

    @Test
    public void testRssWithoutTitleIsRejected() {
        String feed = "<?xml version=\"1.0\"?><rss version=\"2.0\"><channel>"
                + "<item><title>Episode 1</title>"
                + "<enclosure url=\"https://example.com/ep1.mp3\"/></item></channel></rss>";
        assertEquals(PodcastFeedValidator.REASON_NO_TITLE, classify(feed, 200).verdict.reason);
    }

    @Test
    public void testBomAndCommentsBeforeRootElementAreSkipped() {
        String feed = "\ufeff<!-- generated --><?xml version=\"1.0\"?>\n<!-- hi -->"
                + "<rss version=\"2.0\"><channel><title>Podcast</title>"
                + "<item><enclosure url=\"https://example.com/ep1.mp3\"/></item></channel></rss>";
        assertTrue(classify(feed, 200).verdict.ok);
    }

    @Test
    public void testRootElementNames() {
        assertEquals("rss", PodcastFeedValidator.rootElementName("<rss version=\"2.0\">"));
        assertEquals("feed", PodcastFeedValidator.rootElementName("<?xml version=\"1.0\"?><feed>"));
        assertEquals("atom:feed", PodcastFeedValidator.rootElementName("<atom:feed xmlns=\"x\">"));
        assertEquals("html", PodcastFeedValidator.rootElementName("  <!DOCTYPE html>\n<html>"));
        assertNull(PodcastFeedValidator.rootElementName("no xml here"));
        assertNull(PodcastFeedValidator.rootElementName("<?xml version=\"1.0\""));
    }

    @Test
    public void testPlayableEnclosureTypes() {
        assertTrue(PodcastFeedValidator.isPlayableEnclosure("audio/mpeg", null));
        assertTrue(PodcastFeedValidator.isPlayableEnclosure("video/mp4", null));
        assertTrue(PodcastFeedValidator.isPlayableEnclosure(null, "https://x.com/a/b/ep.OPUS?token=1"));
        assertTrue(PodcastFeedValidator.isPlayableEnclosure("", "https://x.com/ep.mp3"));
        assertEquals(false, PodcastFeedValidator.isPlayableEnclosure("application/pdf", null));
        assertEquals(false, PodcastFeedValidator.isPlayableEnclosure("image/jpeg", null));
        assertEquals(false, PodcastFeedValidator.isPlayableEnclosure(null, "https://x.com/doc.pdf"));
    }

    @Test
    public void testRecentAudioBoundary() {
        String lower = "<rss><channel><title>t</title>"
                + "<item>1</item><item>2</item><item>3</item><item>4</item><item>5</item><item>6</item>"
                + "</channel></rss>";
        // Boundary is the start of the 6th item: end of the 5th item's closing tag.
        int boundary = PodcastFeedValidator.recentAudioBoundary(lower);
        assertEquals(lower.indexOf("<item>6</item>"), boundary);
        // Fewer items than the limit: boundary is the end of the body.
        String small = "<rss><channel><title>t</title><item>1</item></channel></rss>";
        assertEquals(small.length(), PodcastFeedValidator.recentAudioBoundary(small));
    }

    @Test
    public void testFreshVerdictHonorsTtls() {
        PodcastFeedValidator validator = PodcastFeedValidator.getInstance();
        long now = System.currentTimeMillis();
        validator.putForTest("https://ok-fresh.example/feed", true,
                PodcastFeedValidator.REASON_OK, now - 1000);
        validator.putForTest("https://ok-stale.example/feed", true,
                PodcastFeedValidator.REASON_OK, now - PodcastFeedValidator.OK_TTL_MS - 1000);
        validator.putForTest("https://broken-fresh.example/feed", false,
                PodcastFeedValidator.REASON_WEBSITE, now - 1000);
        validator.putForTest("https://broken-stale.example/feed", false,
                PodcastFeedValidator.REASON_WEBSITE, now - PodcastFeedValidator.BROKEN_TTL_MS - 1000);

        assertNotNull(validator.freshVerdict("https://ok-fresh.example/feed", now));
        assertNull(validator.freshVerdict("https://ok-stale.example/feed", now));
        assertNotNull(validator.freshVerdict("https://broken-fresh.example/feed", now));
        assertNull(validator.freshVerdict("https://broken-stale.example/feed", now));
    }

    @Test
    public void testFilterPlayableUsesCachedVerdictsWithoutNetwork() {
        PodcastFeedValidator validator = PodcastFeedValidator.getInstance();
        validator.init(org.robolectric.RuntimeEnvironment.getApplication());
        long now = System.currentTimeMillis();
        validator.putForTest("https://good.example/feed", true, PodcastFeedValidator.REASON_OK, now);
        validator.putForTest("https://dead.example/feed", false,
                PodcastFeedValidator.REASON_UNREACHABLE, now);
        validator.putForTest("https://webpage.example", false,
                PodcastFeedValidator.REASON_WEBSITE, now);

        List<PodcastSearchResult> filtered = validator.filterPlayable(results(
                "Good", "https://good.example/feed",
                "Dead", "https://dead.example/feed",
                "Webpage", "https://webpage.example"));

        assertEquals(1, filtered.size());
        assertEquals("Good", filtered.get(0).title);

        // Verdicts must survive a cache reload from disk (no network involved:
        // all URLs are still cached, so nothing gets probed).
        validator.init(org.robolectric.RuntimeEnvironment.getApplication());
        List<PodcastSearchResult> reloaded = validator.filterPlayable(results(
                "Good", "https://good.example/feed",
                "Dead", "https://dead.example/feed",
                "Webpage", "https://webpage.example"));
        assertEquals(1, reloaded.size());
        assertEquals("Good", reloaded.get(0).title);

        Context context = org.robolectric.RuntimeEnvironment.getApplication();
        assertTrue(new java.io.File(context.getFilesDir(), "monero_feed_validation_cache.json").exists());
    }

    private static PodcastFeedValidator.Classification classify(String body, int httpCode) {
        return PodcastFeedValidator.classify("https://example.com/feed", httpCode,
                body.getBytes(StandardCharsets.UTF_8), false);
    }

    private static List<PodcastSearchResult> results(String... titleUrlPairs) {
        List<PodcastSearchResult> list = new ArrayList<>();
        for (int i = 0; i < titleUrlPairs.length; i += 2) {
            list.add(new PodcastSearchResult(titleUrlPairs[i], null, titleUrlPairs[i + 1], ""));
        }
        return list;
    }
}
