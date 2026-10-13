package de.danoeh.antennapod.ui.screen.livestream;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.BlendModeColorFilterCompat;
import androidx.core.graphics.BlendModeCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.media3.session.MediaController;
import de.danoeh.antennapod.BuildConfig;
import de.danoeh.antennapod.R;
import de.danoeh.antennapod.databinding.LiveStreamFragmentBinding;
import de.danoeh.antennapod.event.PlayerStatusEvent;
import de.danoeh.antennapod.playback.service.PlaybackController;
import de.danoeh.antennapod.playback.service.PlaybackService;
import de.danoeh.antennapod.storage.preferences.PlaybackPreferences;
import de.danoeh.antennapod.ui.appstartintent.MediaButtonStarter;
import de.danoeh.antennapod.ui.common.IntentUtils;
import de.danoeh.antennapod.ui.screen.playback.audio.XmrChatTipFragment;
import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

public class LiveStreamFragment extends XmrChatTipFragment {
    public static final String TAG = "LiveStreamFragment";
    private static final String ARG_FEED_TITLE = "feed_title";
    private static final String ARG_TITLE = "title";
    private static final String ARG_PLATFORM_NAME = "platform_name";
    private static final String ARG_EMBED_URL = "embed_url";
    private static final String ARG_PATH = "path";
    private static final String ARG_NOT_SUBSCRIBED = "not_subscribed";
    private static final int MAX_EMBED_RELOADS = 3;
    private static final long EMBED_RELOAD_DELAY_MS = 2000;

    private LiveStreamFragmentBinding viewBinding;
    private View fullscreenView;
    private WebChromeClient.CustomViewCallback fullscreenCallback;
    private int embedReloads = 0;
    private boolean streamPausedForPodcast = false;
    private final OnBackPressedCallback exitFullscreenCallback = new OnBackPressedCallback(false) {
        @Override
        public void handleOnBackPressed() {
            exitFullscreen();
        }
    };

    public static LiveStreamFragment newInstance(@Nullable String feedTitle,
                                                 @NonNull XmrChatLiveStreams.LiveStream stream,
                                                 boolean notSubscribed) {
        Bundle args = new Bundle();
        args.putString(ARG_FEED_TITLE, feedTitle);
        args.putString(ARG_TITLE, stream.title);
        args.putInt(ARG_PLATFORM_NAME, stream.getPlatformName());
        args.putString(ARG_EMBED_URL, stream.getEmbedUrl());
        args.putString(ARG_PATH, stream.path);
        args.putBoolean(ARG_NOT_SUBSCRIBED, notSubscribed);
        LiveStreamFragment fragment = new LiveStreamFragment();
        fragment.setArguments(args);
        return fragment;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        viewBinding = LiveStreamFragmentBinding.inflate(inflater);
        Bundle args = requireArguments();
        viewBinding.toolbar.setNavigationOnClickListener(v -> getParentFragmentManager().popBackStack());

        String liveLabel = getString(R.string.live_stream_on_platform, getString(args.getInt(ARG_PLATFORM_NAME)));
        String feedTitle = args.getString(ARG_FEED_TITLE);
        viewBinding.txtvPodcastTitle.setText(TextUtils.isEmpty(feedTitle) ? liveLabel : feedTitle + " · " + liveLabel);
        viewBinding.txtvStreamTitle.setText(args.getString(ARG_TITLE));
        viewBinding.nonSubscribedWarningLabel.setVisibility(
                args.getBoolean(ARG_NOT_SUBSCRIBED) ? View.VISIBLE : View.GONE);

        viewBinding.tipButton.setColorFilter(BlendModeColorFilterCompat.createBlendModeColorFilterCompat(
                viewBinding.txtvPodcastTitle.getCurrentTextColor(), BlendModeCompat.SRC_IN));
        String xmrChatUrl = "https://" + XmrChatLiveStreams.EMBED_HOST + "/" + args.getString(ARG_PATH);
        viewBinding.tipButton.setOnClickListener(v -> openXmrChatTip(xmrChatUrl));

        setupWebView(args.getString(ARG_EMBED_URL));
        pausePodcast();
        requireActivity().getOnBackPressedDispatcher().addCallback(getViewLifecycleOwner(), exitFullscreenCallback);
        return viewBinding.getRoot();
    }

    @SuppressLint({"SetJavaScriptEnabled", "ClickableViewAccessibility"})
    private void setupWebView(@Nullable String embedUrl) {
        WebSettings settings = viewBinding.webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        CookieManager.getInstance().setAcceptThirdPartyCookies(viewBinding.webView, true);
        viewBinding.webView.setBackgroundColor(Color.BLACK);
        viewBinding.webView.setOnTouchListener((v, event) -> {
            if (streamPausedForPodcast && event.getAction() == MotionEvent.ACTION_DOWN) {
                streamPausedForPodcast = false;
                viewBinding.webView.onResume();
                pausePodcast();
            }
            return false;
        });
        viewBinding.webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (!request.isForMainFrame()) {
                    return false;
                }
                IntentUtils.openInBrowser(getContext(), request.getUrl().toString());
                return true;
            }

            @Override
            public void onReceivedHttpError(WebView view, WebResourceRequest request,
                                            WebResourceResponse errorResponse) {
                if (embedReloads >= MAX_EMBED_RELOADS || embedUrl == null
                        || !request.getUrl().toString().startsWith(embedUrl)) {
                    return;
                }
                embedReloads++;
                view.postDelayed(() -> loadEmbed(embedUrl), EMBED_RELOAD_DELAY_MS);
            }
        });
        viewBinding.webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                enterFullscreen(view, callback);
            }

            @Override
            public void onHideCustomView() {
                exitFullscreen();
            }

            @Override
            public Bitmap getDefaultVideoPoster() {
                return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
            }
        });
        if (!TextUtils.isEmpty(embedUrl)) {
            loadEmbed(embedUrl);
        }
    }

    private void loadEmbed(@NonNull String embedUrl) {
        if (viewBinding == null) {
            return;
        }
        String html = "<!DOCTYPE html><html><head>"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
                + "<style>html,body{margin:0;height:100%;background:#000;overflow:hidden}"
                + "iframe{border:0;width:100%;height:100%}</style></head><body>"
                + "<iframe src=\"" + TextUtils.htmlEncode(embedUrl) + "\""
                + " allow=\"autoplay; fullscreen; encrypted-media\"></iframe>"
                + "</body></html>";
        viewBinding.webView.loadDataWithBaseURL("https://" + XmrChatLiveStreams.EMBED_HOST + "/",
                html, "text/html", "utf-8", null);
    }

    private void pausePodcast() {
        if (BuildConfig.USE_MEDIA3_PLAYBACK_SERVICE) {
            if (PlaybackService.isRunning) {
                PlaybackController.bindToMedia3Service(getActivity(), MediaController::pause);
            }
            return;
        }
        if (PlaybackService.isRunning
                && PlaybackPreferences.getCurrentPlayerStatus() == PlaybackPreferences.PLAYER_STATUS_PLAYING) {
            requireContext().sendBroadcast(MediaButtonStarter.createIntent(requireContext(),
                    KeyEvent.KEYCODE_MEDIA_PAUSE));
        }
    }

    private void enterFullscreen(@NonNull View view, @NonNull WebChromeClient.CustomViewCallback callback) {
        Activity activity = getActivity();
        if (activity == null || fullscreenView != null) {
            callback.onCustomViewHidden();
            return;
        }
        fullscreenView = view;
        fullscreenCallback = callback;
        ViewGroup decorView = (ViewGroup) activity.getWindow().getDecorView();
        decorView.addView(view, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        WindowInsetsControllerCompat insetsController = WindowCompat.getInsetsController(activity.getWindow(),
                decorView);
        insetsController.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        insetsController.hide(WindowInsetsCompat.Type.systemBars());
        activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        exitFullscreenCallback.setEnabled(true);
    }

    private void exitFullscreen() {
        Activity activity = getActivity();
        if (activity == null || fullscreenView == null) {
            return;
        }
        ViewGroup decorView = (ViewGroup) activity.getWindow().getDecorView();
        decorView.removeView(fullscreenView);
        fullscreenView = null;
        WindowCompat.getInsetsController(activity.getWindow(), decorView).show(WindowInsetsCompat.Type.systemBars());
        activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        exitFullscreenCallback.setEnabled(false);
        WebChromeClient.CustomViewCallback callback = fullscreenCallback;
        fullscreenCallback = null;
        if (callback != null) {
            callback.onCustomViewHidden();
        }
    }

    @Override
    public void onStart() {
        super.onStart();
        EventBus.getDefault().register(this);
    }

    @Override
    public void onStop() {
        super.onStop();
        EventBus.getDefault().unregister(this);
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onPlayerStatusChanged(PlayerStatusEvent event) {
        if (viewBinding != null
                && PlaybackPreferences.getCurrentPlayerStatus() == PlaybackPreferences.PLAYER_STATUS_PLAYING) {
            streamPausedForPodcast = true;
            viewBinding.webView.onPause();
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if (!streamPausedForPodcast) {
            viewBinding.webView.onResume();
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        viewBinding.webView.onPause();
    }

    @Override
    public void onDestroyView() {
        exitFullscreen();
        viewBinding.webView.destroy();
        viewBinding = null;
        super.onDestroyView();
    }
}
