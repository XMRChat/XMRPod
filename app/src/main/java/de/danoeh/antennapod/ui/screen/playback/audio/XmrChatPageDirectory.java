package de.danoeh.antennapod.ui.screen.playback.audio;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import de.danoeh.antennapod.event.XmrChatDirectoryUpdateEvent;
import de.danoeh.antennapod.model.feed.Feed;
import de.danoeh.antennapod.net.common.AntennapodHttpClient;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.schedulers.Schedulers;
import okhttp3.HttpUrl;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.greenrobot.eventbus.EventBus;

/**
 * Privacy-preserving local matching for XMRChat creator pages.
 *
 * <p>Instead of sending podcast metadata to the server, this downloads a generic list of public
 * XMRChat pages that have a registered {@code podcast-rss} content link, then matches the user's
 * subscribed feed URLs against that list entirely on the device. The only outbound call is a
 * single generic fetch (cached and refreshed at most once per day); no information about which
 * podcasts the user listens to or is subscribed to ever leaves the device.
 */
public final class XmrChatPageDirectory {
    private static final String TAG = "XmrChatPageDirectory";
    private static final String SEARCH_URL = "https://nest.xmrchat.com/pages/search";
    private static final String LINK_PLATFORM_PODCAST_RSS = "podcast-rss";
    private static final String LINK_PLATFORM_WEBSITE = "website";
    private static final String CACHE_FILE_NAME = "xmrchat_page_directory.json";
    private static final String CACHE_KEY_PAGES = "pages";
    private static final String CACHE_KEY_WEBSITES = "websites";
    private static final String CACHE_KEY_WEBSITES_AMBIGUOUS = "websitesAmbiguous";
    private static final String CACHE_KEY_UPDATED_AT = "updatedAt";
    private static final long REFRESH_INTERVAL_MS = 24L * 60L * 60L * 1000L;
    private static final int PAGE_SIZE = 100;

    @android.annotation.SuppressLint("StaticFieldLeak")
    private static volatile XmrChatPageDirectory instance;

    private final Map<String, String> rssDirectory = new HashMap<>();
    private final Map<String, String> websiteDirectory = new HashMap<>();
    private final Set<String> websiteAmbiguous = new HashSet<>();
    private Context context;
    private long lastUpdatedAt = 0L;
    private volatile boolean refreshInFlight = false;

    private XmrChatPageDirectory() {
    }

    public static XmrChatPageDirectory getInstance() {
        if (instance == null) {
            synchronized (XmrChatPageDirectory.class) {
                if (instance == null) {
                    instance = new XmrChatPageDirectory();
                }
            }
        }
        return instance;
    }

    public synchronized void init(Context context) {
        this.context = context.getApplicationContext();
        loadFromDisk();
    }

    private void loadFromDisk() {
        if (context == null) {
            return;
        }
        File file = new File(context.getFilesDir(), CACHE_FILE_NAME);
        if (!file.exists()) {
            return;
        }
        try {
            String json = FileUtils.readFileToString(file, StandardCharsets.UTF_8);
            JSONObject root = new JSONObject(json);
            lastUpdatedAt = root.optLong(CACHE_KEY_UPDATED_AT, 0L);
            rssDirectory.clear();
            websiteDirectory.clear();
            websiteAmbiguous.clear();
            JSONObject pages = root.optJSONObject(CACHE_KEY_PAGES);
            if (pages != null) {
                for (Iterator<String> it = pages.keys(); it.hasNext(); ) {
                    String rssUrl = it.next();
                    String path = pages.optString(rssUrl, null);
                    if (!TextUtils.isEmpty(rssUrl) && !TextUtils.isEmpty(path)) {
                        rssDirectory.put(rssUrl, path);
                    }
                }
            }
            JSONObject websites = root.optJSONObject(CACHE_KEY_WEBSITES);
            if (websites != null) {
                for (Iterator<String> it = websites.keys(); it.hasNext(); ) {
                    String websiteUrl = it.next();
                    String path = websites.optString(websiteUrl, null);
                    if (!TextUtils.isEmpty(websiteUrl) && !TextUtils.isEmpty(path)) {
                        websiteDirectory.put(websiteUrl, path);
                    }
                }
            }
            JSONArray ambiguous = root.optJSONArray(CACHE_KEY_WEBSITES_AMBIGUOUS);
            if (ambiguous != null) {
                for (int i = 0; i < ambiguous.length(); i++) {
                    String websiteUrl = ambiguous.optString(i);
                    if (!TextUtils.isEmpty(websiteUrl)) {
                        websiteAmbiguous.add(websiteUrl);
                    }
                }
            }
            Log.d(TAG, "Loaded " + rssDirectory.size() + " RSS and " + websiteDirectory.size()
                    + " website link mappings from disk");
        } catch (Exception e) {
            Log.w(TAG, "Could not load XMRChat directory cache, starting empty", e);
            rssDirectory.clear();
            websiteDirectory.clear();
            websiteAmbiguous.clear();
            lastUpdatedAt = 0L;
        }
    }

    private void saveToDisk() {
        if (context == null) {
            return;
        }
        try {
            JSONObject pages = new JSONObject();
            for (Map.Entry<String, String> entry : rssDirectory.entrySet()) {
                pages.put(entry.getKey(), entry.getValue());
            }
            JSONObject websites = new JSONObject();
            for (Map.Entry<String, String> entry : websiteDirectory.entrySet()) {
                websites.put(entry.getKey(), entry.getValue());
            }
            JSONArray ambiguous = new JSONArray();
            for (String websiteUrl : websiteAmbiguous) {
                ambiguous.put(websiteUrl);
            }
            JSONObject root = new JSONObject();
            root.put(CACHE_KEY_PAGES, pages);
            root.put(CACHE_KEY_WEBSITES, websites);
            root.put(CACHE_KEY_WEBSITES_AMBIGUOUS, ambiguous);
            root.put(CACHE_KEY_UPDATED_AT, lastUpdatedAt);
            File file = new File(context.getFilesDir(), CACHE_FILE_NAME);
            FileUtils.writeStringToFile(file, root.toString(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            Log.w(TAG, "Could not save XMRChat directory cache", e);
        }
    }

    /**
     * Triggers a background refresh of the master list if one has not run recently. Non-blocking;
     * safe to call from the UI thread on startup. On failure the existing cached directory is kept.
     */
    public void refreshIfNeeded() {
        if (context == null) {
            return;
        }
        synchronized (this) {
            if (refreshInFlight) {
                return;
            }
            if (lastUpdatedAt > 0L && System.currentTimeMillis() - lastUpdatedAt < REFRESH_INTERVAL_MS) {
                return;
            }
            refreshInFlight = true;
        }
        Completable.fromAction(() -> {
            try {
                FetchedDirectory fetched = fetchAllPages();
                if (fetched != null) {
                    synchronized (this) {
                        rssDirectory.clear();
                        rssDirectory.putAll(fetched.rss);
                        websiteDirectory.clear();
                        websiteDirectory.putAll(fetched.websites);
                        websiteAmbiguous.clear();
                        websiteAmbiguous.addAll(fetched.websitesAmbiguous);
                        lastUpdatedAt = System.currentTimeMillis();
                        saveToDisk();
                    }
                    Log.d(TAG, "Refreshed XMRChat directory: " + fetched.rss.size()
                            + " RSS and " + fetched.websites.size() + " website link mappings");
                    EventBus.getDefault().post(new XmrChatDirectoryUpdateEvent());
                }
            } catch (Exception e) {
                Log.e(TAG, "XMRChat directory refresh failed", e);
            } finally {
                synchronized (this) {
                    refreshInFlight = false;
                }
            }
        }).subscribeOn(Schedulers.io()).subscribe();
    }

    private static final class FetchedDirectory {
        final Map<String, String> rss = new HashMap<>();
        final Map<String, String> websites = new HashMap<>();
        final Set<String> websitesAmbiguous = new HashSet<>();
    }

    private FetchedDirectory fetchAllPages() throws IOException {
        FetchedDirectory result = new FetchedDirectory();
        int offset = 0;
        int total = Integer.MAX_VALUE;
        while (offset < total) {
            HttpUrl url = HttpUrl.parse(SEARCH_URL).newBuilder()
                    .addQueryParameter("hasLinks", LINK_PLATFORM_PODCAST_RSS)
                    .addQueryParameter("hasLinks", LINK_PLATFORM_WEBSITE)
                    .addQueryParameter("offset", String.valueOf(offset))
                    .addQueryParameter("limit", String.valueOf(PAGE_SIZE))
                    .build();
            Request request = new Request.Builder().url(url).get().build();
            try (Response response = AntennapodHttpClient.getHttpClient().newCall(request).execute()) {
                ResponseBody body = response.body();
                String responseBody = body == null ? "" : body.string();
                if (!response.isSuccessful()) {
                    throw new IOException("XMRChat page search failed: " + response.code());
                }
                JSONObject parsed = new JSONObject(responseBody);
                total = parsed.optInt("total", 0);
                JSONArray pages = parsed.optJSONArray("pages");
                if (pages == null || pages.length() == 0) {
                    break;
                }
                int added = 0;
                for (int i = 0; i < pages.length(); i++) {
                    JSONObject page = pages.optJSONObject(i);
                    if (page == null) {
                        continue;
                    }
                    String path = page.optString("path");
                    if (TextUtils.isEmpty(path)) {
                        continue;
                    }
                    JSONArray links = page.optJSONArray("links");
                    if (links == null) {
                        continue;
                    }
                    for (int j = 0; j < links.length(); j++) {
                        JSONObject link = links.optJSONObject(j);
                        if (link == null) {
                            continue;
                        }
                        String platform = link.optString("platform");
                        String value = StringUtils.stripToEmpty(link.optString("value"));
                        if (TextUtils.isEmpty(value)) {
                            continue;
                        }
                        if (LINK_PLATFORM_PODCAST_RSS.equals(platform)) {
                            String normalized = normalizeRssUrl(value);
                            if (!TextUtils.isEmpty(normalized)) {
                                result.rss.put(normalized, path);
                                added++;
                            }
                        } else if (LINK_PLATFORM_WEBSITE.equals(platform)) {
                            String normalized = normalizeWebsiteUrl(value);
                            if (TextUtils.isEmpty(normalized)) {
                                continue;
                            }
                            String existing = result.websites.get(normalized);
                            if (existing != null && !existing.equals(path)) {
                                result.websitesAmbiguous.add(normalized);
                            } else if (existing == null) {
                                result.websites.put(normalized, path);
                                added++;
                            }
                        }
                    }
                }
                if (added == 0 && pages.length() < PAGE_SIZE) {
                    break;
                }
                offset += pages.length();
                if (pages.length() < PAGE_SIZE) {
                    break;
                }
            } catch (org.json.JSONException e) {
                throw new IOException("XMRChat page search response was not valid JSON", e);
            }
        }
        return result;
    }

    /**
     * Returns the cached XMRChat page slug for the given feed's RSS URL, or {@code null} if no
     * local match exists. This performs no network I/O and sends no data off the device.
     */
    @androidx.annotation.Nullable
    public synchronized String pathForFeed(@androidx.annotation.Nullable Feed feed) {
        if (feed == null) {
            return null;
        }
        String downloadUrl = feed.getDownloadUrl();
        if (TextUtils.isEmpty(downloadUrl)) {
            return null;
        }
        return rssDirectory.get(normalizeRssUrl(downloadUrl));
    }

    /**
     * Returns the cached XMRChat page slug for the given feed's declared website URL, or {@code null}
     * if no unambiguous local match exists. Performs no network I/O and sends no data off the device.
     * Website URLs claimed by more than one creator are treated as ambiguous and return {@code null}.
     */
    @androidx.annotation.Nullable
    public synchronized String pathForFeedWebsite(@androidx.annotation.Nullable Feed feed) {
        if (feed == null) {
            return null;
        }
        String link = feed.getLink();
        if (TextUtils.isEmpty(link)) {
            return null;
        }
        String key = normalizeWebsiteUrl(link);
        if (TextUtils.isEmpty(key) || websiteAmbiguous.contains(key)) {
            return null;
        }
        return websiteDirectory.get(key);
    }

    static String normalizeRssUrl(@androidx.annotation.Nullable String value) {
        if (value == null) {
            return "";
        }
        String url = StringUtils.stripToEmpty(value).toLowerCase(Locale.US);
        int query = url.indexOf('?');
        if (query >= 0) {
            url = url.substring(0, query);
        }
        int fragment = url.indexOf('#');
        if (fragment >= 0) {
            url = url.substring(0, fragment);
        }
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        url = StringUtils.removeStart(url, "https://www.");
        url = StringUtils.removeStart(url, "http://www.");
        return url;
    }

    static String normalizeWebsiteUrl(@androidx.annotation.Nullable String value) {
        if (value == null) {
            return "";
        }
        String url = StringUtils.stripToEmpty(value);
        if (url.isEmpty()) {
            return "";
        }
        String withScheme = url.contains("://") ? url : "https://" + url;
        android.net.Uri parsed = android.net.Uri.parse(withScheme);
        String host = parsed.getHost();
        if (TextUtils.isEmpty(host)) {
            return "";
        }
        host = host.toLowerCase(Locale.US);
        if (host.startsWith("www.")) {
            host = host.substring(4);
        }
        String path = parsed.getPath();
        if (path != null && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return path == null || path.isEmpty() ? host : host + path;
    }
}
