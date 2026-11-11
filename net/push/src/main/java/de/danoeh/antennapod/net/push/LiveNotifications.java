package de.danoeh.antennapod.net.push;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.Manifest;
import android.os.Build;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;
import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import de.danoeh.antennapod.model.feed.Feed;
import de.danoeh.antennapod.net.common.AntennapodHttpClient;
import de.danoeh.antennapod.net.common.NetworkUtils;
import de.danoeh.antennapod.storage.database.DBReader;
import de.danoeh.antennapod.ui.appstartintent.MainActivityStarter;
import de.danoeh.antennapod.ui.notifications.NotificationUtils;
import kotlin.Unit;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.unifiedpush.android.connector.UnifiedPush;
import org.unifiedpush.android.connector.data.PushEndpoint;
import java.io.IOException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public final class LiveNotifications {
    public interface FeedMatcher {
        String match(Feed feed);
    }

    public static final String PREFERENCES = "xmrpod-live-push";
    private static final String API = "https://nest.xmrchat.com/live-streams/push";
    private static final String POLL_WORK = "xmrpod-live-poll";
    private static LiveNotifications instance;
    private final Context context;
    private final SharedPreferences preferences;
    private FeedMatcher matcher = feed -> null;
    private boolean resumed;

    private LiveNotifications(Context context) {
        this.context = context.getApplicationContext();
        preferences = this.context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    public static synchronized LiveNotifications get(Context context) {
        if (instance == null) {
            instance = new LiveNotifications(context);
        }
        return instance;
    }

    public void initialize(FeedMatcher feedMatcher) {
        matcher = feedMatcher;
    }

    public void resume() {
        if (resumed || !isEnabled() && !preferences.getBoolean("has_bells", false)
                && !preferences.contains("subscription_id")) {
            return;
        }
        resumed = true;
        String vapid = preferences.getString("vapid", "");
        String registration = preferences.getString("instance", "");
        if (isEnabled() && !vapid.isEmpty() && !registration.isEmpty() && getProviders().contains(getProvider())) {
            UnifiedPush.register(context, registration, context.getString(R.string.app_name), vapid);
        }
        enqueue("sync", null);
    }

    public SharedPreferences getPreferences() {
        return preferences;
    }

    public boolean isEnabled() {
        return preferences.getBoolean("enabled", false);
    }

    public void setEnabled(boolean enabled) {
        preferences.edit().putBoolean("enabled", enabled).putLong("tested_at", 0)
                .putString("status", enabled ? "registering" : "off").apply();
        if (!enabled) {
            String registration = preferences.getString("instance", "");
            preferences.edit().remove("instance").putString("status", "off").apply();
            if (!registration.isEmpty()) {
                UnifiedPush.unregister(context, registration);
            }
        }
        enqueue("sync", null);
    }

    public List<String> getProviders() {
        return UnifiedPush.getDistributors(context);
    }

    public String getProvider() {
        return preferences.getString("provider", "");
    }

    public void selectProvider(String provider) {
        String oldInstance = preferences.getString("instance", "");
        preferences.edit().putString("instance", UUID.randomUUID().toString())
                .putString("provider", provider).putBoolean("enabled", true)
                .putString("status", "registering").remove("endpoint")
                .putBoolean("automatic_test_queued", false)
                .putLong("tested_at", 0).putLong("test_requested_at", 0).apply();
        if (!oldInstance.isEmpty()) {
            UnifiedPush.unregister(context, oldInstance);
        }
        UnifiedPush.saveDistributor(context, provider);
        enqueue("sync", null);
    }

    public void useCurrentOrDefaultProvider(Activity activity, Runnable chooser) {
        UnifiedPush.tryUseCurrentOrDefaultDistributor(activity, success -> {
            String provider = UnifiedPush.getSavedDistributor(context);
            if (!success || provider == null) {
                chooser.run();
            } else if (provider.equals(getProvider()) && !preferences.getString("instance", "").isEmpty()) {
                enqueue("sync", null);
            } else {
                selectProvider(provider);
            }
            return Unit.INSTANCE;
        });
    }

    public String providerName(String provider) {
        try {
            PackageManager manager = context.getPackageManager();
            return manager.getApplicationLabel(manager.getApplicationInfo(provider, 0)).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return context.getString(R.string.live_push_choose_provider);
        }
    }

    public boolean accepts(String registration) {
        return isEnabled() && registration != null && registration.equals(preferences.getString("instance", ""));
    }

    public void setEndpoint(PushEndpoint endpoint) {
        if (endpoint.getPubKeySet() == null) {
            unavailable();
            return;
        }
        if (!endpoint.getUrl().equals(preferences.getString("endpoint", ""))
                || !endpoint.getPubKeySet().getPubKey().equals(preferences.getString("p256dh", ""))
                || !endpoint.getPubKeySet().getAuth().equals(preferences.getString("auth", ""))) {
            preferences.edit().putLong("tested_at", 0).putBoolean("automatic_test_queued", false).apply();
        }
        preferences.edit().putString("endpoint", endpoint.getUrl())
                .putString("p256dh", endpoint.getPubKeySet().getPubKey())
                .putString("auth", endpoint.getPubKeySet().getAuth()).apply();
        enqueue("sync", null);
    }

    public void lostRegistration() {
        preferences.edit().remove("endpoint").putLong("tested_at", 0).putString("status", "unavailable").apply();
        enqueue("sync", null);
    }

    public void unavailable() {
        preferences.edit().putLong("tested_at", 0).putString("status", "unavailable").apply();
        enqueue("schedule", null);
    }

    public void enqueue(String mode, String payload) {
        Data.Builder data = new Data.Builder().putString("mode", mode);
        if ("message".equals(mode)) {
            data.putString("instance", preferences.getString("instance", ""));
        }
        if (payload != null) {
            if (payload.length() > 4000) {
                return;
            }
            data.putString("payload", payload);
        }
        OneTimeWorkRequest work = new OneTimeWorkRequest.Builder(LiveNotificationWorker.class)
                .setInputData(data.build()).setConstraints(networkConstraints()).build();
        if ("sync".equals(mode)) {
            WorkManager.getInstance(context).enqueueUniqueWork("xmrpod-live-sync",
                    ExistingWorkPolicy.APPEND_OR_REPLACE, work);
        } else {
            WorkManager.getInstance(context).enqueue(work);
        }
    }

    private Constraints networkConstraints() {
        return new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build();
    }

    public boolean hasEnabledFeeds() {
        for (Feed feed : DBReader.getFeedList()) {
            if (feed.getState() == Feed.STATE_SUBSCRIBED && feed.getPreferences().getLiveNotificationSince() > 0) {
                return true;
            }
        }
        return false;
    }

    public void updateSchedule() {
        WorkManager work = WorkManager.getInstance(context);
        boolean hasBells = hasEnabledFeeds();
        preferences.edit().putBoolean("has_bells", hasBells).apply();
        if (isEnabled()) {
            work.enqueueUniquePeriodicWork("xmrpod-live-renewal", ExistingPeriodicWorkPolicy.KEEP,
                    new PeriodicWorkRequest.Builder(LiveNotificationWorker.class, 1, TimeUnit.DAYS)
                            .setInputData(new Data.Builder().putString("mode", "sync").build())
                            .setConstraints(networkConstraints()).build());
        } else {
            work.cancelUniqueWork("xmrpod-live-renewal");
        }
        if (hasBells && (!isEnabled() || preferences.getLong("tested_at", 0) == 0)) {
            work.enqueueUniquePeriodicWork(POLL_WORK, ExistingPeriodicWorkPolicy.KEEP,
                    new PeriodicWorkRequest.Builder(LiveNotificationWorker.class, 1, TimeUnit.HOURS)
                            .setInputData(new Data.Builder().putString("mode", "poll").build())
                            .setConstraints(networkConstraints()).build());
        } else {
            work.cancelUniqueWork(POLL_WORK);
        }
    }

    public void synchronize() throws IOException, JSONException {
        if (!isEnabled()) {
            if (preferences.contains("subscription_id")) {
                api("DELETE", subscriptionPath(), null);
            }
            preferences.edit().remove("endpoint").putString("status", "off").apply();
            return;
        }
        String provider = getProvider();
        if (!getProviders().contains(provider)) {
            preferences.edit().putString("status", "no_provider").putLong("tested_at", 0).apply();
            return;
        }
        String registration = preferences.getString("instance", "");
        if (registration.isEmpty()) {
            registration = UUID.randomUUID().toString();
            preferences.edit().putString("instance", registration).apply();
        }
        String endpoint = preferences.getString("endpoint", "");
        if (!endpoint.isEmpty()) {
            JSONObject body = new JSONObject().put("endpoint", endpoint)
                    .put("p256dh", preferences.getString("p256dh", ""))
                    .put("auth", preferences.getString("auth", ""));
            JSONObject registered = api("PUT", subscriptionPath(), body);
            if (!registered.optBoolean("verified")) {
                preferences.edit().putLong("tested_at", 0).apply();
            }
            if (accepts(registration)) {
                preferences.edit().putString("status", "registered").apply();
            }
        } else {
            String vapid = api("GET", "/key", null).getString("publicKey");
            if (accepts(registration)) {
                preferences.edit().putString("vapid", vapid).apply();
                UnifiedPush.saveDistributor(context, provider);
                UnifiedPush.register(context, registration, context.getString(R.string.app_name), vapid);
            }
        }
    }

    public void sendTest() throws IOException, JSONException {
        if (isEnabled() && preferences.contains("endpoint")) {
            preferences.edit().putString("status", "testing").putLong("tested_at", 0)
                    .putLong("test_requested_at", System.currentTimeMillis()).apply();
            api("POST", subscriptionPath() + "/test", new JSONObject());
            WorkManager.getInstance(context).enqueueUniqueWork("xmrpod-live-test-timeout", ExistingWorkPolicy.REPLACE,
                    new OneTimeWorkRequest.Builder(LiveNotificationWorker.class).setInitialDelay(2, TimeUnit.MINUTES)
                            .setInputData(new Data.Builder().putString("mode", "test_timeout").build()).build());
        }
    }

    public void checkTestTimeout() {
        if (isEnabled() && "testing".equals(preferences.getString("status", ""))
                && System.currentTimeMillis() - preferences.getLong("test_requested_at", 0) >= 2 * 60_000
                && preferences.getLong("tested_at", 0) == 0) {
            unavailable();
        }
    }

    public void actionRequired() {
        preferences.edit().putLong("tested_at", 0).putString("status", "action_required").apply();
        enqueue("schedule", null);
    }

    public void receive(String payload, String registration) throws IOException, JSONException {
        if (!accepts(registration) || payload == null) {
            return;
        }
        JSONObject message = new JSONObject(payload);
        String type = message.optString("type");
        if ("verify".equals(type) || "test".equals(type)) {
            JSONObject acknowledgement = api("POST", subscriptionPath() + "/ack",
                    new JSONObject().put("nonce", message.getString("nonce")));
            if (!accepts(registration) || acknowledgement.optBoolean("ignored")) {
                return;
            }
            if ("test".equals(type)
                    && System.currentTimeMillis() - preferences.getLong("test_requested_at", 0) < 10 * 60_000
                    && postNotification("test", context.getString(R.string.live_push_test_title),
                    context.getString(R.string.live_push_test_body), new MainActivityStarter(context).getIntent())) {
                preferences.edit().putLong("tested_at", System.currentTimeMillis())
                        .putString("status", "registered").apply();
            } else if ("verify".equals(type)) {
                preferences.edit().putString("status", "registered").apply();
                if (preferences.getLong("tested_at", 0) == 0
                        && !preferences.getBoolean("automatic_test_queued", false)) {
                    preferences.edit().putBoolean("automatic_test_queued", true).apply();
                    enqueue("test", null);
                }
            }
        } else if ("live".equals(type)) {
            long detected = timestamp(message.optString("detectedAt"));
            long age = System.currentTimeMillis() - detected;
            if (detected > 0 && age >= -60_000 && age < 15 * 60_000) {
                checkSnapshot(fetchSnapshot(), message.optString("path"), message.optString("sessionId"));
            }
        }
    }

    public void poll() throws IOException, JSONException {
        if (hasEnabledFeeds() && NetworkUtils.isFeedRefreshAllowed()) {
            checkSnapshot(fetchSnapshot(), null, null);
        }
    }

    private JSONArray fetchSnapshot() throws IOException, JSONException {
        Request request = new Request.Builder().url("https://nest.xmrchat.com/live-streams").build();
        try (Response response = AntennapodHttpClient.getHttpClient().newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IOException("Live list unavailable");
            }
            return new JSONObject(response.body().string()).optJSONArray("liveStreams");
        }
    }

    @SuppressLint("ApplySharedPref")
    synchronized void checkSnapshot(JSONArray streams, String expectedPath, String expectedSession) {
        for (Feed feed : DBReader.getFeedList()) {
            long since = feed.getPreferences().getLiveNotificationSince();
            if (feed.getState() != Feed.STATE_SUBSCRIBED || since <= 0) {
                continue;
            }
            String path = matcher.match(feed);
            if (path == null || (expectedPath != null && !path.equalsIgnoreCase(expectedPath))) {
                continue;
            }
            JSONObject current = null;
            for (int i = 0; streams != null && i < streams.length(); i++) {
                JSONObject stream = streams.optJSONObject(i);
                JSONObject page = stream == null ? null : stream.optJSONObject("page");
                if (page != null && path.equalsIgnoreCase(page.optString("path"))) {
                    current = stream;
                    break;
                }
            }
            String observedKey = "observed_" + feed.getId() + "_" + since;
            if (current == null) {
                preferences.edit().putString(observedKey, "offline").apply();
                continue;
            }
            String session = current.optString("notificationSessionId");
            boolean serverIdentity = !session.isEmpty();
            if (!serverIdentity) {
                session = current.optString("platform") + ":" + current.optString("videoId")
                        + ":" + current.optString("startedAt");
            }
            String previousObservation = preferences.getString(observedKey, null);
            preferences.edit().putString(observedKey, session).apply();
            String notifiedKey = "notified_" + path.toLowerCase(Locale.US);
            if (expectedSession != null && !expectedSession.equals(session)) {
                continue;
            }
            if (expectedSession == null && previousObservation == null) {
                preferences.edit().putString(notifiedKey, session).apply();
                continue;
            }
            long started = timestamp(current.optString("notificationStartedAt", current.optString("startedAt")));
            if (started == 0) {
                if (session.equals(previousObservation)) {
                    continue;
                }
                started = System.currentTimeMillis();
            }
            if (LiveAlertPolicy.shouldNotify(true, since, started, System.currentTimeMillis(), session,
                    preferences.getString(notifiedKey, ""))) {
                if (postNotification(path + ":" + session, feed.getTitle(),
                        context.getString(R.string.live_push_live_body),
                        new MainActivityStarter(context).withOpenFeed(feed.getId()).getIntent())) {
                    preferences.edit().putString(notifiedKey, session).commit();
                }
            }
        }
    }

    private boolean postNotification(String tag, String title, String body, Intent intent) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context,
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        NotificationManagerCompat manager = NotificationManagerCompat.from(context);
        if (!manager.areNotificationsEnabled()) {
            return false;
        }
        if (manager.getNotificationChannelCompat(NotificationUtils.CHANNEL_ID_LIVE_NOTIFICATIONS) != null
                && manager.getNotificationChannelCompat(NotificationUtils.CHANNEL_ID_LIVE_NOTIFICATIONS).getImportance()
                    == NotificationManagerCompat.IMPORTANCE_NONE) {
            return false;
        }
        try {
            PendingIntent pending = PendingIntent.getActivity(context, tag.hashCode(), intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            manager.notify("xmrpod-live-" + tag, 1, new NotificationCompat.Builder(context,
                    NotificationUtils.CHANNEL_ID_LIVE_NOTIFICATIONS)
                    .setSmallIcon(R.drawable.ic_notification).setContentTitle(title).setContentText(body)
                    .setContentIntent(pending).setAutoCancel(true).setOnlyAlertOnce(true).build());
            return true;
        } catch (SecurityException e) {
            return false;
        }
    }

    @SuppressLint("ApplySharedPref")
    private synchronized String subscriptionPath() {
        if (!preferences.contains("subscription_id")) {
            preferences.edit().putString("subscription_id", UUID.randomUUID().toString())
                    .putString("token", UUID.randomUUID().toString().replace("-", "")
                            + UUID.randomUUID().toString().replace("-", "")).commit();
        }
        return "/subscriptions/" + preferences.getString("subscription_id", "");
    }

    private JSONObject api(String method, String path, JSONObject body) throws IOException, JSONException {
        Request.Builder builder = new Request.Builder().url(API + path)
                .header("Authorization", "Bearer " + preferences.getString("token", ""));
        builder.method(method, body == null ? null
                : RequestBody.create(body.toString(), MediaType.get("application/json")));
        try (Response response = AntennapodHttpClient.getHttpClient().newCall(builder.build()).execute()) {
            if (path.endsWith("/ack") && response.code() == 409) {
                return new JSONObject().put("ignored", true);
            }
            if ("DELETE".equals(method) && (response.code() == 401 || response.code() == 404)) {
                return new JSONObject();
            }
            if (!response.isSuccessful()) {
                throw new IOException("Live push server unavailable: " + response.code());
            }
            return response.body() == null ? new JSONObject() : new JSONObject(response.body().string());
        }
    }

    private static long timestamp(String value) {
        String normalized = value.replaceAll("Z$", "+0000").replaceAll("([+-]\\d\\d):(\\d\\d)$", "$1$2");
        for (String format : new String[] {"yyyy-MM-dd'T'HH:mm:ss.SSSZ", "yyyy-MM-dd'T'HH:mm:ssZ"}) {
            try {
                Date parsed = new SimpleDateFormat(format, Locale.US).parse(normalized);
                if (parsed != null) {
                    return parsed.getTime();
                }
            } catch (ParseException e) {
                continue;
            }
        }
        return 0;
    }
}
