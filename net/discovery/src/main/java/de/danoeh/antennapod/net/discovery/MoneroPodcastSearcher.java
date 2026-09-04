package de.danoeh.antennapod.net.discovery;

import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import de.danoeh.antennapod.net.common.AntennapodHttpClient;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.core.SingleOnSubscribe;
import io.reactivex.rxjava3.schedulers.Schedulers;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Searches the public XMRChat creator directory for podcasts that accept Monero tips.
 *
 * <p>An empty query returns the entire directory of creator pages that registered a
 * {@code podcast-rss} content link. A non-empty query is filtered server-side against the
 * page name, path and search terms.
 */
public class MoneroPodcastSearcher implements PodcastSearcher {
    private static final String SEARCH_URL = "https://nest.xmrchat.com/pages/search";
    private static final String IMAGE_BASE_URL = "https://s3.xmrchat.com";
    private static final String LINK_PLATFORM_PODCAST_RSS = "podcast-rss";
    private static final int PAGE_SIZE = 100;

    public Single<List<PodcastSearchResult>> search(String query) {
        return Single.create((SingleOnSubscribe<List<PodcastSearchResult>>) subscriber -> {
            try {
                subscriber.onSuccess(fetchDirectory(query));
            } catch (IOException | JSONException e) {
                subscriber.onError(e);
            }
        })
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread());
    }

    private List<PodcastSearchResult> fetchDirectory(String query) throws IOException, JSONException {
        OkHttpClient client = AntennapodHttpClient.getHttpClient();
        List<PodcastSearchResult> searchResults = new ArrayList<>();
        int offset = 0;
        long total = Long.MAX_VALUE;
        while (offset < total) {
            HttpUrl.Builder urlBuilder = HttpUrl.parse(SEARCH_URL).newBuilder()
                    .addQueryParameter("hasLinks", LINK_PLATFORM_PODCAST_RSS)
                    .addQueryParameter("offset", String.valueOf(offset))
                    .addQueryParameter("limit", String.valueOf(PAGE_SIZE));
            if (!TextUtils.isEmpty(query)) {
                urlBuilder.addQueryParameter("search", query);
            }
            Request request = new Request.Builder().url(urlBuilder.build()).get().build();
            try (Response response = client.newCall(request).execute()) {
                ResponseBody body = response.body();
                String responseBody = body == null ? "" : body.string();
                if (!response.isSuccessful()) {
                    throw new IOException("XMRChat page search failed: " + response.code());
                }
                JSONObject parsed = new JSONObject(responseBody);
                total = parsed.optLong("total", 0);
                JSONArray pages = parsed.optJSONArray("pages");
                if (pages == null || pages.length() == 0) {
                    break;
                }
                for (int i = 0; i < pages.length(); i++) {
                    PodcastSearchResult result = parsePage(pages.optJSONObject(i));
                    if (result != null) {
                        searchResults.add(result);
                    }
                }
                offset += pages.length();
                if (pages.length() < PAGE_SIZE) {
                    break;
                }
            }
        }
        return searchResults;
    }

    static PodcastSearchResult parsePage(JSONObject page) {
        if (page == null) {
            return null;
        }
        String feedUrl = firstPodcastRssLink(page.optJSONArray("links"));
        if (feedUrl == null) {
            return null;
        }
        String title = page.optString("name", "").trim();
        if (title.isEmpty()) {
            title = page.optString("path", "").trim();
        }
        if (title.isEmpty()) {
            title = feedUrl;
        }
        return new PodcastSearchResult(title, logoUrl(page.optJSONObject("logo")), feedUrl, "");
    }

    private static String firstPodcastRssLink(JSONArray links) {
        if (links == null) {
            return null;
        }
        for (int i = 0; i < links.length(); i++) {
            JSONObject link = links.optJSONObject(i);
            if (link == null) {
                continue;
            }
            String value = link.optString("value", "").trim();
            if (LINK_PLATFORM_PODCAST_RSS.equals(link.optString("platform")) && !value.isEmpty()) {
                return value;
            }
        }
        return null;
    }

    private static String logoUrl(JSONObject logo) {
        if (logo == null) {
            return null;
        }
        String url = logo.optString("thumbnail", "").trim();
        if (url.isEmpty()) {
            url = logo.optString("url", "").trim();
        }
        if (url.isEmpty()) {
            return null;
        }
        // The API returns relative paths for images hosted on the XMRChat image server
        return url.startsWith("/") ? IMAGE_BASE_URL + url : url;
    }

    @Override
    public Single<String> lookupUrl(String url) {
        return Single.just(url);
    }

    @Override
    public boolean urlNeedsLookup(String url) {
        return false;
    }

    @Override
    public String getName() {
        return "XMRChat";
    }
}
