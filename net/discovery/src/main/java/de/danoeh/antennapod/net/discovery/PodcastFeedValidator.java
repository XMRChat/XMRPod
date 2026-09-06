package de.danoeh.antennapod.net.discovery;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import de.danoeh.antennapod.net.common.AntennapodHttpClient;
import okhttp3.CacheControl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Validates that feed URLs from the XMRChat directory actually point to a playable podcast
 * before they are shown in the "Monero enabled podcasts" list. XMRChat accepts any string as
 * a {@code podcast-rss} content link, so the directory contains dead domains, regular webpages
 * and blog feeds without episodes. This checks each URL on the device:
 *
 * <ul>
 *     <li>the host is reachable and returns a 2xx response,</li>
 *     <li>the body is an RSS or Atom feed, not an HTML page,</li>
 *     <li>the feed has a title and at least one item with an audio enclosure.</li>
 * </ul>
 *
 * <p>Verdicts are cached on disk: valid feeds are re-checked at most every 7 days, broken ones
 * once a day, so repeat searches do not re-probe everything. Probes run in parallel, download
 * at most {@link #MAX_PROBE_BYTES} per feed and never block longer than the per-call timeout.
 * If validation itself fails unexpectedly, feeds are shown anyway (fail open).
 */
public final class PodcastFeedValidator {
    static final String TAG = "PodcastFeedValidator";

    private static final String CACHE_FILE_NAME = "monero_feed_validation_cache.json";
    private static final String CACHE_KEY_FEEDS = "feeds";
    private static final String CACHE_KEY_OK = "ok";
    private static final String CACHE_KEY_REASON = "reason";
    private static final String CACHE_KEY_CHECKED_AT = "checkedAt";

    /** How long a "feed is playable" verdict is trusted without re-checking. */
    static final long OK_TTL_MS = 7L * 24L * 60L * 60L * 1000L;
    /** How long a "feed is broken" verdict is trusted; broken feeds are hidden until re-check. */
    static final long BROKEN_TTL_MS = 24L * 60L * 60L * 1000L;

    /** Maximum number of bytes read from a feed while looking for the first enclosure. */
    static final int MAX_PROBE_BYTES = 256 * 1024;
    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int READ_TIMEOUT_MS = 10000;
    private static final int CALL_TIMEOUT_MS = 15000;
    private static final int PROBE_PARALLELISM = 6;

    static final String REASON_OK = "ok";
    static final String REASON_HTTP_ERROR = "http_error";
    static final String REASON_UNREACHABLE = "unreachable";
    static final String REASON_WEBSITE = "website";
    static final String REASON_NOT_A_FEED = "not_a_feed";
    static final String REASON_NO_TITLE = "no_title";
    static final String REASON_NO_ENCLOSURES = "no_enclosures";

    private static volatile PodcastFeedValidator instance;

    private final Map<String, Verdict> cache = new HashMap<>();
    private Context context;
    private volatile OkHttpClient probeClient;

    private PodcastFeedValidator() {
    }

    public static PodcastFeedValidator getInstance() {
        if (instance == null) {
            synchronized (PodcastFeedValidator.class) {
                if (instance == null) {
                    instance = new PodcastFeedValidator();
                }
            }
        }
        return instance;
    }

    /**
     * Loads the persisted verdict cache. Call once at app start; without a context the
     * validator still works but does not persist verdicts across restarts.
     */
    public synchronized void init(Context context) {
        this.context = context.getApplicationContext();
        loadFromDisk();
    }

    /**
     * Filters the given search results down to entries whose feed URL points to a playable
     * podcast. Performs network I/O for URLs without a fresh verdict; call on a background
     * thread. The input order is preserved. Feeds whose probe could not run at all are kept.
     */
    public List<PodcastSearchResult> filterPlayable(List<PodcastSearchResult> results) {
        if (results == null || results.isEmpty()) {
            return results;
        }
        long now = System.currentTimeMillis();
        List<PodcastSearchResult> toProbe = new ArrayList<>();
        Map<String, Verdict> verdicts = new HashMap<>();
        for (PodcastSearchResult result : results) {
            Verdict cached = freshVerdict(result.feedUrl, now);
            if (cached != null) {
                verdicts.put(result.feedUrl, cached);
            } else {
                toProbe.add(result);
            }
        }
        probeAll(toProbe, verdicts);
        saveToDisk();

        List<PodcastSearchResult> playable = new ArrayList<>(results.size());
        for (PodcastSearchResult result : results) {
            Verdict verdict = verdicts.get(result.feedUrl);
            if (verdict == null || verdict.ok) {
                playable.add(result);
            }
        }
        return playable;
    }

    private void probeAll(List<PodcastSearchResult> toProbe, Map<String, Verdict> verdicts) {
        if (toProbe.isEmpty()) {
            return;
        }
        ExecutorService executor = Executors.newFixedThreadPool(
                Math.min(PROBE_PARALLELISM, toProbe.size()));
        try {
            List<Callable<Void>> jobs = new ArrayList<>(toProbe.size());
            for (PodcastSearchResult result : toProbe) {
                jobs.add(() -> {
                    verdicts.put(result.feedUrl, probe(result.feedUrl));
                    return null;
                });
            }
            List<Future<Void>> futures = executor.invokeAll(jobs, CALL_TIMEOUT_MS * 2, TimeUnit.MILLISECONDS);
            for (int i = 0; i < futures.size(); i++) {
                if (futures.get(i).isCancelled() && !verdicts.containsKey(toProbe.get(i).feedUrl)) {
                    Log.w(TAG, "Probe timed out, keeping " + toProbe.get(i).feedUrl);
                }
            }
            for (Map.Entry<String, Verdict> entry : verdicts.entrySet()) {
                remember(entry.getKey(), entry.getValue());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Log.w(TAG, "Probing interrupted, keeping unjudged feeds");
        } finally {
            executor.shutdownNow();
        }
    }

    Verdict probe(String feedUrl) {
        long start = System.currentTimeMillis();
        try {
            Request request = new Request.Builder().url(feedUrl)
                    .header("Range", "bytes=0-" + (MAX_PROBE_BYTES - 1))
                    .cacheControl(CacheControl.FORCE_NETWORK)
                    .get()
                    .build();
            try (Response response = client().newCall(request).execute()) {
                ResponseBody body = response.body();
                if (!response.isSuccessful()) {
                    return finish(new Verdict(false, REASON_HTTP_ERROR + "_" + response.code(),
                            System.currentTimeMillis()), start);
                }
                if (body == null) {
                    return finish(new Verdict(false, REASON_UNREACHABLE,
                            System.currentTimeMillis()), start);
                }
                byte[] bytes = readCapped(body.byteStream());
                return finish(classify(feedUrl, response.code(), bytes, bytes.length >= MAX_PROBE_BYTES),
                        start);
            }
        } catch (IOException | IllegalArgumentException e) {
            return finish(new Verdict(false, REASON_UNREACHABLE, System.currentTimeMillis()), start);
        }
    }

    private static Verdict finish(Verdict verdict, long start) {
        Log.d(TAG, (verdict.ok ? "OK   " : "FAIL ") + "(" + (System.currentTimeMillis() - start)
                + " ms) " + verdict.reason);
        return verdict;
    }

    private static byte[] readCapped(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int total = 0;
        int read;
        while (total < MAX_PROBE_BYTES && (read = in.read(chunk)) != -1) {
            int allowed = Math.min(read, MAX_PROBE_BYTES - total);
            buffer.write(chunk, 0, allowed);
            total += allowed;
        }
        return buffer.toByteArray();
    }

    /**
     * Classifies a feed response body. Pure function of its inputs, so it is unit-testable
     * without network access. {@code truncated} should be true when the body was larger than
     * {@link #MAX_PROBE_BYTES} and only the first part was read.
     */
    static Verdict classify(String feedUrl, int httpCode, byte[] body, boolean truncated) {
        if (httpCode < 200 || httpCode >= 300) {
            return new Verdict(false, REASON_HTTP_ERROR + "_" + httpCode, System.currentTimeMillis());
        }
        String lower = new String(body, StandardCharsets.UTF_8).toLowerCase(Locale.US).trim();
        if (lower.startsWith("\ufeff")) {
            lower = lower.substring(1);
        }
        String root = rootElementName(lower);
        if ("html".equals(root)) {
            return new Verdict(false, REASON_WEBSITE, System.currentTimeMillis());
        }
        if (!"rss".equals(root) && !"feed".equals(root) && !"atom:feed".equals(root)) {
            return new Verdict(false, REASON_NOT_A_FEED, System.currentTimeMillis());
        }
        // The channel/feed title must appear before the first item, otherwise the feed
        // has no show title (only episode titles) and XMRPod would reject it.
        int firstTitle = lower.indexOf("<title");
        int firstItem = lower.indexOf("<item");
        if (firstItem < 0) {
            firstItem = lower.indexOf("<entry");
        }
        if (firstTitle < 0 || (firstItem >= 0 && firstTitle > firstItem)) {
            return new Verdict(false, REASON_NO_TITLE, System.currentTimeMillis());
        }
        boolean hasEnclosure = lower.contains("<enclosure")
                || lower.contains("rel=\"enclosure\"")
                || lower.contains("rel='enclosure'");
        if (!hasEnclosure) {
            return new Verdict(false, REASON_NO_ENCLOSURES, System.currentTimeMillis());
        }
        return new Verdict(true, REASON_OK, System.currentTimeMillis());
    }

    /**
     * Returns the name of the first XML element, skipping whitespace, the XML prolog,
     * doctype declarations and comments. Returns null if no element start is found.
     */
    static String rootElementName(String xml) {
        String lowerCasedXml = xml.toLowerCase(Locale.US);
        int i = 0;
        while (i < lowerCasedXml.length()) {
            while (i < lowerCasedXml.length() && Character.isWhitespace(lowerCasedXml.charAt(i))) {
                i++;
            }
            if (i >= lowerCasedXml.length() || lowerCasedXml.charAt(i) != '<') {
                return null;
            }
            if (lowerCasedXml.startsWith("<?", i)) {
                int end = lowerCasedXml.indexOf("?>", i);
                if (end < 0) {
                    return null;
                }
                i = end + 2;
            } else if (lowerCasedXml.startsWith("<!--", i)) {
                int end = lowerCasedXml.indexOf("-->", i);
                if (end < 0) {
                    return null;
                }
                i = end + 3;
            } else if (lowerCasedXml.startsWith("<!doctype", i)) {
                int end = lowerCasedXml.indexOf('>', i);
                if (end < 0) {
                    return null;
                }
                i = end + 1;
            } else {
                int end = i + 1;
                while (end < lowerCasedXml.length()) {
                    char c = lowerCasedXml.charAt(end);
                    if (Character.isWhitespace(c) || c == '>' || c == '/') {
                        break;
                    }
                    end++;
                }
                return lowerCasedXml.substring(i + 1, end);
            }
        }
        return null;
    }

    private OkHttpClient client() {
        if (probeClient == null) {
            synchronized (this) {
                if (probeClient == null) {
                    probeClient = AntennapodHttpClient.newBuilder()
                            .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                            .readTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                            .callTimeout(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                            .build();
                }
            }
        }
        return probeClient;
    }

    private synchronized void remember(String feedUrl, Verdict verdict) {
        cache.put(feedUrl, verdict);
    }

    synchronized Verdict freshVerdict(String feedUrl, long now) {
        Verdict cached = cache.get(feedUrl);
        if (cached == null) {
            return null;
        }
        long ttl = cached.ok ? OK_TTL_MS : BROKEN_TTL_MS;
        return now - cached.checkedAt < ttl ? cached : null;
    }

    private synchronized void loadFromDisk() {
        cache.clear();
        if (context == null) {
            return;
        }
        File file = new File(context.getFilesDir(), CACHE_FILE_NAME);
        if (!file.exists()) {
            return;
        }
        try {
            byte[] content;
            try (FileInputStream in = new FileInputStream(file)) {
                content = readCapped(in);
            }
            JSONObject root = new JSONObject(new String(content, StandardCharsets.UTF_8));
            JSONObject feeds = root.optJSONObject(CACHE_KEY_FEEDS);
            if (feeds == null) {
                return;
            }
            for (Iterator<String> it = feeds.keys(); it.hasNext(); ) {
                String url = it.next();
                JSONObject entry = feeds.optJSONObject(url);
                if (entry == null) {
                    continue;
                }
                cache.put(url, new Verdict(entry.optBoolean(CACHE_KEY_OK, false),
                        entry.optString(CACHE_KEY_REASON, REASON_UNREACHABLE),
                        entry.optLong(CACHE_KEY_CHECKED_AT, 0L)));
            }
            Log.d(TAG, "Loaded " + cache.size() + " feed verdicts from disk");
        } catch (Exception e) {
            Log.w(TAG, "Could not load feed verdict cache, starting empty", e);
            cache.clear();
        }
    }

    private synchronized void saveToDisk() {
        if (context == null) {
            return;
        }
        try {
            JSONObject feeds = new JSONObject();
            for (Map.Entry<String, Verdict> entry : cache.entrySet()) {
                JSONObject verdict = new JSONObject();
                verdict.put(CACHE_KEY_OK, entry.getValue().ok);
                verdict.put(CACHE_KEY_REASON, entry.getValue().reason);
                verdict.put(CACHE_KEY_CHECKED_AT, entry.getValue().checkedAt);
                feeds.put(entry.getKey(), verdict);
            }
            JSONObject root = new JSONObject();
            root.put(CACHE_KEY_FEEDS, feeds);
            try (FileOutputStream out = new FileOutputStream(new File(context.getFilesDir(), CACHE_FILE_NAME))) {
                out.write(root.toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not save feed verdict cache", e);
        }
    }

    synchronized void putForTest(String feedUrl, boolean ok, String reason, long checkedAt) {
        cache.put(feedUrl, new Verdict(ok, reason, checkedAt));
    }

    static final class Verdict {
        final boolean ok;
        final String reason;
        final long checkedAt;

        Verdict(boolean ok, String reason, long checkedAt) {
            this.ok = ok;
            this.reason = reason;
            this.checkedAt = checkedAt;
        }
    }
}
