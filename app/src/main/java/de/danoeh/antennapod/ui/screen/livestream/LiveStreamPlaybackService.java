package de.danoeh.antennapod.ui.screen.livestream;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Presentation;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.MutableContextWrapper;
import android.content.pm.ServiceInfo;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.AudioManager;
import android.media.Image;
import android.media.ImageReader;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.text.TextUtils;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;
import androidx.media.app.NotificationCompat.MediaStyle;
import androidx.media3.session.MediaController;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;
import de.danoeh.antennapod.BuildConfig;
import de.danoeh.antennapod.R;
import de.danoeh.antennapod.activity.MainActivity;
import de.danoeh.antennapod.event.PlayerStatusEvent;
import de.danoeh.antennapod.playback.service.PlaybackController;
import de.danoeh.antennapod.playback.service.PlaybackService;
import de.danoeh.antennapod.storage.preferences.PlaybackPreferences;
import de.danoeh.antennapod.ui.appstartintent.MainActivityStarter;
import de.danoeh.antennapod.ui.appstartintent.MediaButtonStarter;
import de.danoeh.antennapod.ui.common.IntentUtils;
import de.danoeh.antennapod.ui.notifications.NotificationUtils;
import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;
import org.apache.commons.io.IOUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class LiveStreamPlaybackService extends Service {
    private static final String TAG = "LiveStreamPlayback";
    private static final String ACTION_PLAY = "play";
    private static final String ACTION_PAUSE = "pause";
    private static final String ACTION_STOP = "stop";
    private static final int NOTIFICATION_ID = 24752;
    private static final int MAX_EMBED_RELOADS = 3;
    private static final long PAUSED_TIMEOUT_MS = 10 * 60 * 1000L;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<String, Boolean> frameStates = new HashMap<>();
    private final Binder binder = new LocalBinder();
    private MutableContextWrapper webContext;
    private LiveStreamWebView webView;
    private Presentation backgroundPresentation;
    private VirtualDisplay backgroundDisplay;
    private ImageReader backgroundFrames;
    private MediaSessionCompat mediaSession;
    private PowerManager.WakeLock wakeLock;
    private Bundle arguments;
    private boolean playing;
    private boolean blocked;
    private boolean attached;
    private boolean stopped;
    private int embedReloads;
    private int moveResumeAttempts;
    private final Runnable resumeAfterMove = new Runnable() {
        @Override
        public void run() {
            if (webView != null && !blocked && !stopped && moveResumeAttempts-- > 0) {
                sendCommand(ACTION_PLAY);
                handler.postDelayed(this, 250);
            }
        }
    };
    private final Runnable pausedTimeout = () -> {
        if (playing) {
            return;
        }
        if (!attached) {
            stop();
        } else {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH);
        }
    };
    private final BroadcastReceiver noisyReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (playing) {
                pause();
            }
        }
    };

    public class LocalBinder extends Binder {
        public LiveStreamPlaybackService getService() {
            return LiveStreamPlaybackService.this;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        mediaSession = new MediaSessionCompat(this, TAG);
        mediaSession.setCallback(new MediaSessionCompat.Callback() {
            @Override
            public void onPlay() {
                play();
            }

            @Override
            public void onPause() {
                pause();
            }

            @Override
            public void onStop() {
                stop();
            }

            @Override
            public void onPlayFromSearch(String query, Bundle extras) {
                play();
            }
        });
        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, getPackageName() + ":livestream");
        ContextCompat.registerReceiver(this, noisyReceiver,
                new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), ContextCompat.RECEIVER_NOT_EXPORTED);
        EventBus.getDefault().register(this);
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_PLAY.equals(action)) {
            play();
        } else if (ACTION_PAUSE.equals(action)) {
            pause();
        } else if (ACTION_STOP.equals(action)) {
            stop();
        }
        return START_NOT_STICKY;
    }

    @SuppressLint({"SetJavaScriptEnabled", "ClickableViewAccessibility"})
    public WebView attach(Activity activity, Bundle args) {
        keepPlayingAcrossMove();
        releaseBackgroundHost();
        String embedUrl = args.getString(LiveStreamFragment.ARG_EMBED_URL);
        if (webView == null || !TextUtils.equals(embedUrl,
                arguments.getString(LiveStreamFragment.ARG_EMBED_URL))) {
            stop();
            destroyWebView();
            arguments = new Bundle(args);
            embedReloads = 0;
            blocked = false;
            stopped = false;
            webContext = new MutableContextWrapper(activity);
            webView = new LiveStreamWebView(webContext);
            webView.getSettings().setJavaScriptEnabled(true);
            webView.getSettings().setDomStorageEnabled(true);
            webView.getSettings().setMediaPlaybackRequiresUserGesture(false);
            CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);
            webView.setBackgroundColor(Color.BLACK);
            webView.setOnTouchListener((view, event) -> {
                if (event.getAction() == MotionEvent.ACTION_DOWN) {
                    handler.removeCallbacks(resumeAfterMove);
                }
                if (event.getAction() == MotionEvent.ACTION_DOWN && blocked) {
                    stopped = false;
                    blocked = false;
                    webView.onResume();
                    sendCommand("unblock");
                }
                return false;
            });
            webView.setWebViewClient(new WebViewClient() {
                @Override
                public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                    if (!request.isForMainFrame()) {
                        return false;
                    }
                    IntentUtils.openInBrowser(LiveStreamPlaybackService.this, request.getUrl().toString());
                    return true;
                }

                @Override
                public void onReceivedHttpError(WebView view, WebResourceRequest request,
                                                WebResourceResponse response) {
                    if (embedReloads < MAX_EMBED_RELOADS && embedUrl != null
                            && request.getUrl().toString().startsWith(embedUrl)) {
                        embedReloads++;
                        view.postDelayed(() -> {
                            if (webView == view) {
                                loadEmbed(embedUrl);
                            }
                        }, 2000);
                    }
                }

                @Override
                public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                    stop();
                    destroyWebView();
                    return true;
                }
            });
            installPlaybackBridge(embedUrl);
            loadEmbed(embedUrl);
        }
        attached = true;
        webContext.setBaseContext(activity);
        return webView;
    }

    public void detach(WebView view) {
        if (webView != view) {
            return;
        }
        keepPlayingAcrossMove();
        attached = false;
        if (view.getParent() instanceof ViewGroup parent) {
            parent.removeView(view);
        }
        view.setWebChromeClient(new WebChromeClient());
        webContext.setBaseContext(getApplicationContext());
        if (playing || mediaSession.isActive()) {
            createBackgroundHost();
            if (!playing) {
                handler.removeCallbacks(pausedTimeout);
                handler.postDelayed(pausedTimeout, PAUSED_TIMEOUT_MS);
            }
        } else {
            stopSelf();
        }
    }

    private void keepPlayingAcrossMove() {
        handler.removeCallbacks(resumeAfterMove);
        if (playing && !blocked && !stopped) {
            moveResumeAttempts = 12;
            handler.postDelayed(resumeAfterMove, 250);
        }
    }

    private void createBackgroundHost() {
        int width = Math.max(1, webView.getWidth());
        int height = Math.max(1, webView.getHeight());
        int density = getResources().getDisplayMetrics().densityDpi;
        backgroundFrames = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);
        backgroundFrames.setOnImageAvailableListener(reader -> {
            if (reader == backgroundFrames) {
                Image frame = reader.acquireLatestImage();
                if (frame != null) {
                    frame.close();
                }
            }
        }, handler);
        DisplayManager displayManager = getSystemService(DisplayManager.class);
        backgroundDisplay = displayManager.createVirtualDisplay(TAG, width, height, density,
                backgroundFrames.getSurface(), DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY);
        if (backgroundDisplay == null) {
            releaseBackgroundHost();
            pause();
            return;
        }
        backgroundPresentation = new Presentation(this, backgroundDisplay.getDisplay());
        FrameLayout holder = new FrameLayout(backgroundPresentation.getContext());
        backgroundPresentation.setContentView(holder);
        backgroundPresentation.show();
        webContext.setBaseContext(backgroundPresentation.getContext());
        holder.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private void releaseBackgroundHost() {
        if (backgroundPresentation != null) {
            if (webView != null && webView.getParent() instanceof ViewGroup parent) {
                parent.removeView(webView);
            }
            backgroundPresentation.dismiss();
            backgroundPresentation = null;
            webContext.setBaseContext(getApplicationContext());
        }
        if (backgroundDisplay != null) {
            backgroundDisplay.release();
            backgroundDisplay = null;
        }
        if (backgroundFrames != null) {
            backgroundFrames.setOnImageAvailableListener(null, null);
            backgroundFrames.close();
            backgroundFrames = null;
        }
    }

    private void installPlaybackBridge(@Nullable String embedUrl) {
        if (TextUtils.isEmpty(embedUrl)) {
            return;
        }
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            return;
        }
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            return;
        }
        Uri uri = Uri.parse(embedUrl);
        if (uri.getHost() == null || (!"https".equals(uri.getScheme()) && !"http".equals(uri.getScheme()))) {
            return;
        }
        Set<String> origins = new HashSet<>();
        origins.add("https://" + XmrChatLiveStreams.EMBED_HOST);
        origins.add(uri.getScheme() + "://" + uri.getHost() + (uri.getPort() < 0 ? "" : ":" + uri.getPort()));
        WebViewCompat.addWebMessageListener(webView, "xmrpodPlayback", origins,
                (view, message, origin, mainFrame, reply) -> {
                    String data = message.getData();
                    if (view != webView || data == null || data.length() > 100 || stopped) {
                        return;
                    }
                    String[] state = data.split(":", 2);
                    if (state.length != 2) {
                        return;
                    }
                    if ("true".equals(state[1]) && frameStates.size() < 100) {
                        frameStates.put(state[0], true);
                    } else if ("false".equals(state[1])) {
                        frameStates.remove(state[0]);
                    }
                    if (!blocked) {
                        setPlaying(frameStates.containsValue(true));
                    }
                });
        try (InputStream script = getAssets().open("live_stream_playback.js")) {
            WebViewCompat.addDocumentStartJavaScript(webView,
                    IOUtils.toString(script, StandardCharsets.UTF_8), origins);
        } catch (IOException e) {
            Log.e(TAG, "Unable to observe live stream playback", e);
        }
    }

    private void loadEmbed(@Nullable String embedUrl) {
        if (webView == null || TextUtils.isEmpty(embedUrl)) {
            return;
        }
        frameStates.clear();
        String html = "<!DOCTYPE html><html><head>"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
                + "<style>html,body{margin:0;height:100%;background:#000;overflow:hidden}"
                + "iframe{border:0;width:100%;height:100%}</style></head><body>"
                + "<iframe src=\"" + TextUtils.htmlEncode(embedUrl) + "\""
                + " allow=\"autoplay; fullscreen; encrypted-media\"></iframe></body></html>";
        webView.loadDataWithBaseURL("https://" + XmrChatLiveStreams.EMBED_HOST + "/",
                html, "text/html", "utf-8", null);
    }

    private void play() {
        if (webView == null) {
            stop();
            return;
        }
        stopped = false;
        blocked = false;
        if (!startForegroundPlayback()) {
            pause();
            return;
        }
        handler.removeCallbacks(pausedTimeout);
        handler.postDelayed(pausedTimeout, PAUSED_TIMEOUT_MS);
        webView.onResume();
        sendCommand(ACTION_PLAY);
    }

    private void pause() {
        handler.removeCallbacks(resumeAfterMove);
        blocked = true;
        sendCommand(ACTION_PAUSE);
        frameStates.clear();
        setPlaying(false);
    }

    private void stop() {
        pause();
        stopped = true;
        handler.removeCallbacks(pausedTimeout);
        mediaSession.setActive(false);
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void sendCommand(String command) {
        if (webView != null) {
            webView.evaluateJavascript("document.querySelectorAll('iframe').forEach(frame => "
                    + "frame.contentWindow.postMessage({xmrpodPlayback:'" + command + "'}, '*'))", null);
        }
    }

    @SuppressLint("WakelockTimeout")
    private void setPlaying(boolean value) {
        if (playing == value) {
            return;
        }
        playing = value;
        handler.removeCallbacks(pausedTimeout);
        if (playing) {
            pausePodcast();
            mediaSession.setActive(true);
            wakeLock.acquire();
        } else if (wakeLock.isHeld()) {
            wakeLock.release();
        }
        mediaSession.setPlaybackState(new PlaybackStateCompat.Builder()
                .setActions(PlaybackStateCompat.ACTION_PLAY | PlaybackStateCompat.ACTION_PAUSE
                        | PlaybackStateCompat.ACTION_PLAY_PAUSE | PlaybackStateCompat.ACTION_STOP)
                .setState(playing ? PlaybackStateCompat.STATE_PLAYING : PlaybackStateCompat.STATE_PAUSED,
                        PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, playing ? 1 : 0)
                .build());
        if (playing) {
            if (!startForegroundPlayback()) {
                pause();
            }
        } else if (mediaSession.isActive()) {
            getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, createNotification());
            handler.postDelayed(pausedTimeout, PAUSED_TIMEOUT_MS);
        } else if (!attached) {
            stopSelf();
        }
    }

    @SuppressLint("InlinedApi")
    private boolean startForegroundPlayback() {
        try {
            startService(new Intent(this, LiveStreamPlaybackService.class));
            ServiceCompat.startForeground(this, NOTIFICATION_ID, createNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            return true;
        } catch (IllegalStateException e) {
            Log.e(TAG, "Unable to start live stream playback in the background", e);
            return false;
        }
    }

    private Notification createNotification() {
        String title = arguments.getString(LiveStreamFragment.ARG_TITLE);
        String feedTitle = arguments.getString(LiveStreamFragment.ARG_FEED_TITLE);
        mediaSession.setMetadata(new MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, feedTitle)
                .build());
        Intent openIntent = new Intent(this, MainActivity.class)
                .putExtra(MainActivityStarter.EXTRA_FRAGMENT_TAG, LiveStreamFragment.TAG)
                .putExtra(MainActivityStarter.EXTRA_FRAGMENT_ARGS, arguments);
        PendingIntent open = PendingIntent.getActivity(this, NOTIFICATION_ID, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        mediaSession.setSessionActivity(open);
        return new NotificationCompat.Builder(this, NotificationUtils.CHANNEL_ID_PLAYING)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(TextUtils.isEmpty(title) ? getString(R.string.live_label) : title)
                .setContentText(feedTitle)
                .setContentIntent(open)
                .setOnlyAlertOnce(true)
                .setOngoing(playing)
                .setDeleteIntent(actionIntent(ACTION_STOP))
                .addAction(playing ? R.drawable.ic_notification_pause : R.drawable.ic_notification_play,
                        getString(playing ? R.string.pause_label : R.string.play_label),
                        actionIntent(playing ? ACTION_PAUSE : ACTION_PLAY))
                .addAction(R.drawable.ic_notification_cancel, getString(R.string.live_stream_stop),
                        actionIntent(ACTION_STOP))
                .setStyle(new MediaStyle().setMediaSession(mediaSession.getSessionToken())
                        .setShowActionsInCompactView(0, 1))
                .build();
    }

    private PendingIntent actionIntent(String action) {
        return PendingIntent.getService(this, NOTIFICATION_ID,
                new Intent(this, LiveStreamPlaybackService.class).setAction(action),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private void pausePodcast() {
        if (BuildConfig.USE_MEDIA3_PLAYBACK_SERVICE) {
            if (PlaybackService.isRunning) {
                PlaybackController.bindToMedia3Service(this, MediaController::pause);
            }
        } else if (PlaybackService.isRunning) {
            sendBroadcast(MediaButtonStarter.createIntent(this, KeyEvent.KEYCODE_MEDIA_PAUSE));
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onPlayerStatusChanged(PlayerStatusEvent event) {
        if (PlaybackPreferences.getCurrentPlayerStatus() == PlaybackPreferences.PLAYER_STATUS_PLAYING) {
            pause();
        }
    }

    private void destroyWebView() {
        releaseBackgroundHost();
        if (webView != null) {
            pause();
            if (webView.getParent() instanceof ViewGroup parent) {
                parent.removeView(webView);
            }
            webView.destroy();
            webView = null;
        }
        frameStates.clear();
    }

    @Override
    public void onDestroy() {
        EventBus.getDefault().unregister(this);
        unregisterReceiver(noisyReceiver);
        destroyWebView();
        handler.removeCallbacksAndMessages(null);
        mediaSession.release();
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE);
        getSystemService(NotificationManager.class).cancel(NOTIFICATION_ID);
        super.onDestroy();
    }
}
