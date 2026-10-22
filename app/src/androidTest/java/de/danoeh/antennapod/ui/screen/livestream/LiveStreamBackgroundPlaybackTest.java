package de.danoeh.antennapod.ui.screen.livestream;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.Rect;
import android.media.AudioManager;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.service.notification.StatusBarNotification;
import android.util.Log;
import android.view.WindowManager;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.webkit.WebSettings;
import android.webkit.JavascriptInterface;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.rule.ActivityTestRule;
import androidx.test.runner.lifecycle.ActivityLifecycleCallback;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;
import androidx.media.AudioFocusRequestCompat;
import androidx.media.AudioManagerCompat;
import androidx.webkit.WebViewCompat;
import de.danoeh.antennapod.R;
import de.danoeh.antennapod.activity.MainActivity;
import de.danoeh.antennapod.event.PlayerStatusEvent;
import de.danoeh.antennapod.storage.preferences.PlaybackPreferences;
import de.danoeh.antennapod.ui.appstartintent.MainActivityStarter;
import de.danoeh.antennapod.ui.screen.home.HomeFragment;
import fi.iki.elonen.NanoHTTPD;
import org.apache.commons.io.IOUtils;
import org.awaitility.Awaitility;
import org.greenrobot.eventbus.EventBus;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.Collections;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public class LiveStreamBackgroundPlaybackTest {
    private static final String TITLE = "Background playback test";
    @Rule
    public ActivityTestRule<MainActivity> activityRule = new ActivityTestRule<>(MainActivity.class, false, false);
    private Context context;
    private NanoHTTPD server;
    private MediaController controller;
    private int oldPlayerStatus;
    private final AtomicInteger progressReports = new AtomicInteger();
    private final AtomicInteger embedLoads = new AtomicInteger();
    private volatile String lastPosition = "";
    private volatile boolean lastMediaPaused = true;
    private volatile boolean providerAdvertising = true;
    private volatile boolean providerAudible;
    private final AtomicInteger providerBroadcastSamples = new AtomicInteger();
    private final ActivityLifecycleCallback lifecycleCallback = (activity, stage) -> {
        if (activity instanceof MainActivity && stage == Stage.CREATED) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                activity.setShowWhenLocked(true);
                activity.setTurnScreenOn(true);
            } else {
                activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                        | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
            }
        }
    };

    @Before
    public void setUp() throws IOException {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        context.getSharedPreferences(MainActivity.PREF_NAME, Context.MODE_PRIVATE).edit()
                .putBoolean(MainActivity.PREF_IS_FIRST_LAUNCH, false).commit();
        context.getSharedPreferences("XmrChatPrefs", Context.MODE_PRIVATE).edit()
                .putBoolean("disclosure_shown", true).commit();
        oldPlayerStatus = PlaybackPreferences.getCurrentPlayerStatus();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
                ActivityLifecycleMonitorRegistry.getInstance().addLifecycleCallback(lifecycleCallback));
        shell("input keyevent KEYCODE_WAKEUP");
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
                WebView.setWebContentsDebuggingEnabled(true));
    }

    @After
    public void tearDown() throws IOException {
        if (controller != null) {
            controller.getTransportControls().stop();
        }
        shell("input keyevent KEYCODE_WAKEUP");
        shell("wm dismiss-keyguard");
        activityRule.finishActivity();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
                ActivityLifecycleMonitorRegistry.getInstance().removeLifecycleCallback(lifecycleCallback));
        context.stopService(new Intent(context, LiveStreamPlaybackService.class));
        PlaybackPreferences.setCurrentPlayerStatus(oldPlayerStatus);
        if (server != null) {
            server.stop();
        }
    }

    @Test
    public void embeddedVideoKeepsAudioAcrossBackgroundScreenOffAndNavigation() throws Throwable {
        startLocalServer();
        open("http://127.0.0.1:" + server.getListeningPort() + "/embed");
        awaitPlaying();
        assertProgress();
        Rect playerBounds = new Rect();
        activityRule.runOnUiThread(() -> activityRule.getActivity().findViewById(R.id.videoHolder)
                .getGlobalVisibleRect(playerBounds));
        shell("input tap " + playerBounds.centerX() + " " + playerBounds.centerY());
        Awaitility.await().atMost(20, TimeUnit.SECONDS).until(() -> activityRule.getActivity()
                .getRequestedOrientation() == ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        shell("input keyevent KEYCODE_HOME");
        assertProgress();
        int loadsBeforeFullscreenReturn = embedLoads.get();
        controller.getSessionActivity().send();
        assertProgress();
        assertEquals(loadsBeforeFullscreenReturn, embedLoads.get());
        shell("input keyevent KEYCODE_HOME");
        assertProgress();
        controller.getTransportControls().pause();
        awaitPaused();
        controller.getTransportControls().play();
        awaitPlaying();
        assertProgress();
        shell("input keyevent KEYCODE_MEDIA_PAUSE");
        awaitPaused();
        shell("input keyevent KEYCODE_MEDIA_PLAY");
        awaitPlaying();
        int loadsBeforeReturn = embedLoads.get();
        controller.getSessionActivity().send();
        assertProgress();
        assertEquals(loadsBeforeReturn, embedLoads.get());
        activityRule.runOnUiThread(() -> activityRule.getActivity().loadFragment(HomeFragment.TAG, null));
        assertProgress();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            PlaybackPreferences.setCurrentPlayerStatus(PlaybackPreferences.PLAYER_STATUS_PLAYING);
            EventBus.getDefault().post(new PlayerStatusEvent());
        });
        awaitPaused();
        PlaybackPreferences.setCurrentPlayerStatus(PlaybackPreferences.PLAYER_STATUS_PAUSED);
        controller.getTransportControls().play();
        awaitPlaying();
        shell("input keyevent KEYCODE_SLEEP");
        assertProgress();
        controller.getTransportControls().stop();
        Awaitility.await().atMost(10, TimeUnit.SECONDS).until(() -> findController() == null);
    }

    @Test
    public void audioFocusLossPausesAndTransientLossResumes() throws Throwable {
        startLocalServer();
        open("http://127.0.0.1:" + server.getListeningPort() + "/embed");
        awaitPlaying();
        assertProgress();
        AudioManager audioManager = context.getSystemService(AudioManager.class);
        AudioFocusRequestCompat transientFocus = new AudioFocusRequestCompat.Builder(
                AudioManagerCompat.AUDIOFOCUS_GAIN_TRANSIENT).setOnAudioFocusChangeListener(focus -> { }).build();
        assertEquals(AudioManager.AUDIOFOCUS_REQUEST_GRANTED,
                AudioManagerCompat.requestAudioFocus(audioManager, transientFocus));
        awaitPaused();
        AudioManagerCompat.abandonAudioFocusRequest(audioManager, transientFocus);
        awaitPlaying();
        assertProgress();
        AudioFocusRequestCompat permanentFocus = new AudioFocusRequestCompat.Builder(
                AudioManagerCompat.AUDIOFOCUS_GAIN).setOnAudioFocusChangeListener(focus -> { }).build();
        assertEquals(AudioManager.AUDIOFOCUS_REQUEST_GRANTED,
                AudioManagerCompat.requestAudioFocus(audioManager, permanentFocus));
        awaitPaused();
        AudioManagerCompat.abandonAudioFocusRequest(audioManager, permanentFocus);
        SystemClock.sleep(3000);
        assertEquals(PlaybackState.STATE_PAUSED, controller.getPlaybackState().getState());
        assertEquals(true, lastMediaPaused);
    }

    private void startLocalServer() throws IOException {
        server = new NanoHTTPD("127.0.0.1", 0) {
            @Override
            public Response serve(IHTTPSession session) {
                if ("/clip.mp4".equals(session.getUri())) {
                    try {
                        InputStream clip = InstrumentationRegistry.getInstrumentation().getContext()
                                .getAssets().open("livestream-test.mp4");
                        return new Response(Response.Status.OK, "video/mp4", clip);
                    } catch (IOException e) {
                        return new Response(Response.Status.INTERNAL_ERROR, "text/plain", e.toString());
                    }
                } else if ("/progress".equals(session.getUri())) {
                    recordProgress(session.getParms().get("position"),
                            "true".equals(session.getParms().get("paused")));
                    return new Response(Response.Status.OK, "text/plain", "");
                }
                embedLoads.incrementAndGet();
                return new Response(Response.Status.OK, "text/html",
                        "<video id='media' autoplay loop playsinline controls src='/clip.mp4'></video>"
                                + "<button style='position:fixed;inset:0;width:100%;height:100%' "
                                + "onclick='media.requestFullscreen()'>Fullscreen</button>"
                                + "<script>addEventListener('message',e=>console.log('XMRPOD_TEST_MESSAGE '+"
                                + "JSON.stringify({data:e.data,parent:e.source===parent,paused:media.paused})));"
                                + "setInterval(()=>fetch('/progress?position='+media.currentTime"
                                + "+'&paused='+media.paused),500)</script>");
            }
        };
        server.start();
    }

    @Test
    public void providerKeepsAudioAcrossBackgroundAndScreenOff() throws Exception {
        String embedUrl = InstrumentationRegistry.getArguments().getString("liveEmbedUrl");
        Assume.assumeNotNull(embedUrl);
        open(embedUrl);
        if ("true".equals(InstrumentationRegistry.getArguments().getString("liveWithoutProbe"))) {
            awaitPlaying();
            SystemClock.sleep(30000);
            assertEquals(true, context.getSystemService(AudioManager.class).isMusicActive());
            return;
        }
        installProviderProbe(embedUrl);
        awaitPlaying();
        Awaitility.await().atMost(180, TimeUnit.SECONDS).until(() -> providerBroadcastSamples.get() >= 20);
        assertProgress();
        shell("input keyevent KEYCODE_HOME");
        assertAudioContinues();
        shell("input keyevent KEYCODE_SLEEP");
        assertAudioContinues();
        controller.getTransportControls().pause();
        awaitPaused();
        controller.getTransportControls().play();
        awaitPlaying();
        assertAudioContinues();
        shell("input keyevent KEYCODE_WAKEUP");
        shell("wm dismiss-keyguard");
        controller.getSessionActivity().send();
        assertProgress();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
                activityRule.getActivity().loadFragment(HomeFragment.TAG, null));
        assertProgress();
        shell("am start -a android.settings.SETTINGS");
        assertProgress();
        shell("input keyevent KEYCODE_SLEEP");
        assertProgress();
        shell("input keyevent KEYCODE_WAKEUP");
        shell("wm dismiss-keyguard");
        controller.getSessionActivity().send();
        assertProgress();
    }

    private void open(String embedUrl) {
        Bundle args = new Bundle();
        args.putString(LiveStreamFragment.ARG_FEED_TITLE, "XMRPod playback verification");
        args.putString(LiveStreamFragment.ARG_TITLE, TITLE);
        args.putString(LiveStreamFragment.ARG_EMBED_URL, embedUrl);
        args.putInt("platform_name", R.string.live_platform_twitch);
        args.putString("path", "ard");
        activityRule.launchActivity(new Intent(context, MainActivity.class)
                .putExtra(MainActivityStarter.EXTRA_FRAGMENT_TAG, LiveStreamFragment.TAG)
                .putExtra(MainActivityStarter.EXTRA_FRAGMENT_ARGS, args));
        if (embedUrl.startsWith("http://127.0.0.1:")) {
            AtomicBoolean configured = new AtomicBoolean();
            Awaitility.await().atMost(10, TimeUnit.SECONDS).until(() -> {
                InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                    ViewGroup holder = activityRule.getActivity().findViewById(R.id.videoHolder);
                    if (holder != null && holder.getChildCount() > 0 && !configured.get()) {
                        WebView view = (WebView) holder.getChildAt(0);
                        view.getSettings().setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
                        view.evaluateJavascript("(() => {const frame=document.querySelector('iframe');"
                                + "if(!frame)return false;frame.src=frame.src;return true;})()",
                                result -> configured.set("true".equals(result)));
                    }
                });
                return configured.get();
            });
        }
    }

    private MediaController findController() {
        for (StatusBarNotification notification : context.getSystemService(NotificationManager.class)
                .getActiveNotifications()) {
            CharSequence title = notification.getNotification().extras.getCharSequence(Notification.EXTRA_TITLE, "");
            if (TITLE.contentEquals(title)) {
                MediaSession.Token token = notification.getNotification().extras
                        .getParcelable(Notification.EXTRA_MEDIA_SESSION);
                return token == null ? null : new MediaController(context, token);
            }
        }
        return null;
    }

    private void awaitPlaying() {
        Awaitility.await().atMost(90, TimeUnit.SECONDS).until(() -> {
            controller = findController();
            return controller != null && controller.getPlaybackState() != null
                    && controller.getPlaybackState().getState() == PlaybackState.STATE_PLAYING
                    && context.getSystemService(AudioManager.class).isMusicActive();
        });
    }

    private void awaitPaused() {
        Awaitility.await().atMost(10, TimeUnit.SECONDS).until(() ->
                controller.getPlaybackState().getState() == PlaybackState.STATE_PAUSED
                        && lastMediaPaused);
        int pausedProgress = progressReports.get();
        SystemClock.sleep(1000);
        assertEquals(pausedProgress, progressReports.get());
        assertEquals(true, lastMediaPaused);
    }

    private void assertProgress() {
        int before = progressReports.get();
        Awaitility.await().atMost(15, TimeUnit.SECONDS).until(() -> progressReports.get() >= before + 6);
        assertNotNull(controller);
        assertEquals(PlaybackState.STATE_PLAYING, controller.getPlaybackState().getState());
        assertEquals(true, context.getSystemService(AudioManager.class).isMusicActive());
    }

    private void assertAudioContinues() {
        assertProgress();
        long duration = Long.parseLong(InstrumentationRegistry.getArguments()
                .getString("liveContinuitySeconds", "60")) * 1000;
        long until = SystemClock.elapsedRealtime() + duration;
        long lastAdvance = SystemClock.elapsedRealtime();
        int previous = progressReports.get();
        while (SystemClock.elapsedRealtime() < until) {
            SystemClock.sleep(100);
            int current = progressReports.get();
            if (current != previous) {
                previous = current;
                lastAdvance = SystemClock.elapsedRealtime();
            }
            assertTrue("Playback progress stalled for over 1.5 seconds",
                    SystemClock.elapsedRealtime() - lastAdvance < 1500);
            assertEquals(true, context.getSystemService(AudioManager.class).isMusicActive());
        }
    }

    private void recordProgress(String position, boolean paused) {
        if (!paused && !lastPosition.equals(position)) {
            progressReports.incrementAndGet();
        }
        lastPosition = position;
        lastMediaPaused = paused;
    }

    private void installProviderProbe(String embedUrl) {
        AtomicReference<WebView> webView = new AtomicReference<>();
        Awaitility.await().atMost(10, TimeUnit.SECONDS).until(() -> {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                ViewGroup holder = activityRule.getActivity().findViewById(R.id.videoHolder);
                if (holder != null && holder.getChildCount() > 0) {
                    webView.set((WebView) holder.getChildAt(0));
                }
            });
            return webView.get() != null;
        });
        Uri uri = Uri.parse(embedUrl);
        Set<String> origins = Collections.singleton(uri.getScheme() + "://" + uri.getAuthority());
        AtomicBoolean embedReady = new AtomicBoolean();
        Awaitility.await().atMost(20, TimeUnit.SECONDS).until(() -> {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> webView.get().evaluateJavascript(
                    "document.querySelector('iframe') !== null", result -> embedReady.set("true".equals(result))));
            return embedReady.get();
        });
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            webView.get().addJavascriptInterface(new Object() {
                @JavascriptInterface
                public void postMessage(String message) {
                        Log.i("LiveStreamTest", message);
                        try {
                            JSONObject sample = new JSONObject(message);
                            recordProgress(sample.getString("position"), sample.getBoolean("paused"));
                            providerAdvertising = sample.getBoolean("advertisement");
                            providerAudible = sample.getBoolean("audible");
                            if (!providerAdvertising && providerAudible) {
                                providerBroadcastSamples.incrementAndGet();
                            } else {
                                providerBroadcastSamples.set(0);
                            }
                        } catch (JSONException e) {
                            throw new AssertionError(e);
                        }
                }
            }, "xmrpodTestPlayback");
            WebViewCompat.addDocumentStartJavaScript(webView.get(),
                    "setInterval(()=>{const items=[...document.querySelectorAll('video,audio')];"
                            + "const media=items.find(item=>!item.paused)||items[0];if(media){"
                            + "xmrpodTestPlayback.postMessage(JSON.stringify({position:String(media.currentTime),"
                            + "paused:media.paused,visibility:document.visibilityState,focused:document.hasFocus(),"
                            + "audible:!media.paused&&!media.muted&&media.volume>0&&media.readyState>=3,"
                            + "advertisement:!!document.querySelector('[data-a-target=video-ad-label],"
                            + "[data-a-target=video-ad-countdown]')||/^\\s*Ad\\b/.test(document.body.innerText),"
                            + "media:items.map(item=>({time:item.currentTime,paused:item.paused,muted:item.muted}))"
                            + "}));}},500)", origins);
            webView.get().evaluateJavascript(
                    "document.querySelector('iframe').src = document.querySelector('iframe').src", null);
        });
    }

    private void shell(String command) throws IOException {
        ParcelFileDescriptor result = InstrumentationRegistry.getInstrumentation().getUiAutomation()
                .executeShellCommand(command);
        try (InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(result)) {
            IOUtils.toByteArray(input);
        }
    }
}
