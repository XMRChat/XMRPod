package de.danoeh.antennapod.ui.screen.livestream;

import android.net.Uri;
import android.text.TextUtils;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import de.danoeh.antennapod.R;
import de.danoeh.antennapod.model.feed.Feed;
import de.danoeh.antennapod.net.common.AntennapodHttpClient;
import de.danoeh.antennapod.ui.screen.playback.audio.XmrChatPageDirectory;
import io.reactivex.rxjava3.core.Maybe;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class XmrChatLiveStreams {
    private static final String TAG = "XmrChatLiveStreams";
    private static final String LIVE_STREAMS_URL = "https://nest.xmrchat.com/live-streams";
    private static final long CACHE_DURATION_MS = 60L * 1000L;
    static final String EMBED_HOST = "xmrchat.com";
    private static final String PLATFORM_TWITCH = "twitch";
    private static final String PLATFORM_KICK = "kick";
    private static final String PLATFORM_PEERTUBE = "peertube";
    private static final String PLATFORM_YOUTUBE = "youtube";
    private static final String PLATFORM_RUMBLE = "rumble";
    private static final List<String> PLATFORM_PRIORITY = Arrays.asList(
            PLATFORM_TWITCH, PLATFORM_KICK, PLATFORM_PEERTUBE, PLATFORM_YOUTUBE, PLATFORM_RUMBLE);

    private static final Map<String, LiveStream> streamsByPath = new HashMap<>();
    private static long lastUpdatedAt = 0L;

    private XmrChatLiveStreams() {
    }

    public static Maybe<LiveStream> getForFeed(@Nullable Feed feed) {
        return Maybe.fromCallable(() -> findForFeed(feed));
    }

    @Nullable
    private static LiveStream findForFeed(@Nullable Feed feed) {
        String path = XmrChatPageDirectory.getInstance().pathForFeed(feed);
        if (TextUtils.isEmpty(path)) {
            path = XmrChatPageDirectory.getInstance().pathForFeedWebsite(feed);
        }
        if (TextUtils.isEmpty(path)) {
            return null;
        }
        synchronized (XmrChatLiveStreams.class) {
            if (System.currentTimeMillis() - lastUpdatedAt >= CACHE_DURATION_MS) {
                try {
                    Map<String, LiveStream> fetched = fetchLiveStreams();
                    streamsByPath.clear();
                    streamsByPath.putAll(fetched);
                    lastUpdatedAt = System.currentTimeMillis();
                } catch (IOException | JSONException e) {
                    Log.e(TAG, "XMRChat live stream refresh failed", e);
                }
            }
            return streamsByPath.get(path.toLowerCase(Locale.US));
        }
    }

    private static Map<String, LiveStream> fetchLiveStreams() throws IOException, JSONException {
        Request request = new Request.Builder().url(LIVE_STREAMS_URL).get().build();
        try (Response response = AntennapodHttpClient.getHttpClient().newCall(request).execute()) {
            ResponseBody body = response.body();
            String responseBody = body == null ? "" : body.string();
            if (!response.isSuccessful()) {
                throw new IOException("XMRChat live stream request failed: " + response.code());
            }
            long fetchedAt = System.currentTimeMillis();
            Map<String, LiveStream> result = new HashMap<>();
            JSONArray streams = new JSONObject(responseBody).optJSONArray("liveStreams");
            for (int i = 0; streams != null && i < streams.length(); i++) {
                JSONObject stream = streams.optJSONObject(i);
                JSONObject page = stream == null ? null : stream.optJSONObject("page");
                String path = page == null ? null : getString(page, "path");
                if (TextUtils.isEmpty(path)) {
                    continue;
                }
                LiveStream liveStream = new LiveStream(path, getString(stream, "platform"),
                        getString(stream, "title"), getString(stream, "imageUrl"), getString(stream, "channelName"),
                        getString(stream, "videoId"), getString(stream, "embedUrl"), fetchedAt);
                if (liveStream.getEmbedUrl() == null) {
                    continue;
                }
                String key = path.toLowerCase(Locale.US);
                LiveStream existing = result.get(key);
                if (existing == null || liveStream.getPriority() < existing.getPriority()) {
                    result.put(key, liveStream);
                }
            }
            return result;
        }
    }

    @Nullable
    private static String getString(@NonNull JSONObject object, @NonNull String key) {
        if (object.isNull(key)) {
            return null;
        }
        String value = object.optString(key).trim();
        return value.isEmpty() ? null : value;
    }

    public static final class LiveStream {
        public final String path;
        public final String platform;
        public final String title;
        public final long fetchedAt;
        private final String imageUrl;
        private final String channelName;
        private final String videoId;
        private final String embedUrl;

        LiveStream(@NonNull String path, @Nullable String platform, @Nullable String title, @Nullable String imageUrl,
                   @Nullable String channelName, @Nullable String videoId, @Nullable String embedUrl,
                   long fetchedAt) {
            this.path = path;
            this.platform = platform == null ? "" : platform.toLowerCase(Locale.US);
            this.title = title;
            this.imageUrl = imageUrl;
            this.channelName = channelName;
            this.videoId = videoId;
            this.embedUrl = embedUrl;
            this.fetchedAt = fetchedAt;
        }

        int getPriority() {
            return PLATFORM_PRIORITY.indexOf(platform);
        }

        @Nullable
        public String getImageUrl() {
            if (imageUrl == null) {
                return null;
            }
            return imageUrl.replace("{width}", "640").replace("{height}", "360");
        }

        @Nullable
        public String getEmbedUrl() {
            switch (platform) {
                case PLATFORM_TWITCH:
                    return channelName == null ? null : "https://player.twitch.tv/?channel=" + Uri.encode(channelName)
                            + "&parent=" + EMBED_HOST + "&autoplay=false";
                case PLATFORM_KICK:
                    String channel = channelName != null ? channelName : videoId;
                    return channel == null ? null : "https://player.kick.com/" + Uri.encode(channel);
                case PLATFORM_PEERTUBE:
                    return embedUrl;
                case PLATFORM_YOUTUBE:
                    return videoId == null ? null : "https://www.youtube.com/embed/" + Uri.encode(videoId);
                case PLATFORM_RUMBLE:
                    return videoId == null ? null : "https://rumble.com/embed/v" + Uri.encode(videoId);
                default:
                    return null;
            }
        }

        @StringRes
        public int getPlatformName() {
            switch (platform) {
                case PLATFORM_TWITCH:
                    return R.string.live_platform_twitch;
                case PLATFORM_KICK:
                    return R.string.live_platform_kick;
                case PLATFORM_PEERTUBE:
                    return R.string.live_platform_peertube;
                case PLATFORM_YOUTUBE:
                    return R.string.live_platform_youtube;
                default:
                    return R.string.live_platform_rumble;
            }
        }
    }
}
