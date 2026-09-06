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

    @Test
    public void testRssFeedWithEnclosureIsValid() {
        PodcastFeedValidator.Verdict verdict = classify(RSS_WITH_ENCLOSURE, 200);
        assertTrue(verdict.ok);
        assertEquals(PodcastFeedValidator.REASON_OK, verdict.reason);
    }

    @Test
    public void testAtomFeedWithEnclosureIsValid() {
        PodcastFeedValidator.Verdict verdict = classify(ATOM_WITH_ENCLOSURE, 200);
        assertTrue(verdict.ok);
    }

    @Test
    public void testBlogRssWithoutEnclosuresIsRejected() {
        PodcastFeedValidator.Verdict verdict = classify(RSS_WITHOUT_ENCLOSURE, 200);
        assertEquals(PodcastFeedValidator.REASON_NO_ENCLOSURES, verdict.reason);
    }

    @Test
    public void testHtmlPageIsRejectedAsWebsite() {
        PodcastFeedValidator.Verdict verdict = classify(HTML_PAGE, 200);
        assertEquals(PodcastFeedValidator.REASON_WEBSITE, verdict.reason);
    }

    @Test
    public void testNonFeedXmlIsRejected() {
        PodcastFeedValidator.Verdict verdict = classify(NON_FEED_XML, 200);
        assertEquals(PodcastFeedValidator.REASON_NOT_A_FEED, verdict.reason);
    }

    @Test
    public void testHttpErrorIsRejected() {
        PodcastFeedValidator.Verdict verdict = classify(HTML_PAGE, 403);
        assertEquals(PodcastFeedValidator.REASON_HTTP_ERROR + "_403", verdict.reason);
    }

    @Test
    public void testRssWithoutTitleIsRejected() {
        String feed = "<?xml version=\"1.0\"?><rss version=\"2.0\"><channel>"
                + "<item><title>Episode 1</title>"
                + "<enclosure url=\"https://example.com/ep1.mp3\"/></item></channel></rss>";
        assertEquals(PodcastFeedValidator.REASON_NO_TITLE, classify(feed, 200).reason);
    }

    @Test
    public void testBomAndCommentsBeforeRootElementAreSkipped() {
        String feed = "\ufeff<!-- generated --><?xml version=\"1.0\"?>\n<!-- hi -->"
                + "<rss version=\"2.0\"><channel><title>Podcast</title>"
                + "<item><enclosure url=\"https://example.com/ep1.mp3\"/></item></channel></rss>";
        assertTrue(classify(feed, 200).ok);
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

    private static PodcastFeedValidator.Verdict classify(String body, int httpCode) {
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
