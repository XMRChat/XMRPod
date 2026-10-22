package de.danoeh.antennapod.ui.screen.livestream;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.IBinder;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.widget.FrameLayout;
import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.Lifecycle;
import androidx.core.graphics.BlendModeColorFilterCompat;
import androidx.core.graphics.BlendModeCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import de.danoeh.antennapod.R;
import de.danoeh.antennapod.databinding.LiveStreamFragmentBinding;
import de.danoeh.antennapod.ui.screen.playback.audio.XmrChatTipFragment;

public class LiveStreamFragment extends XmrChatTipFragment {
    public static final String TAG = "LiveStreamFragment";
    static final String ARG_FEED_TITLE = "feed_title";
    static final String ARG_TITLE = "title";
    private static final String ARG_PLATFORM_NAME = "platform_name";
    static final String ARG_EMBED_URL = "embed_url";
    private static final String ARG_PATH = "path";
    private static final String ARG_NOT_SUBSCRIBED = "not_subscribed";

    private LiveStreamFragmentBinding viewBinding;
    private View fullscreenView;
    private WebChromeClient.CustomViewCallback fullscreenCallback;
    private LiveStreamPlaybackService playbackService;
    private WebView webView;
    private WebChromeClient chromeClient;
    private boolean serviceBound;
    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            if (viewBinding == null) {
                return;
            }
            playbackService = ((LiveStreamPlaybackService.LocalBinder) binder).getService();
            webView = playbackService.attach(requireActivity(), requireArguments());
            if (webView.getParent() instanceof ViewGroup parent) {
                parent.removeView(webView);
            }
            viewBinding.videoHolder.addView(webView, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            chromeClient = new WebChromeClient() {
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
            };
            webView.setWebChromeClient(chromeClient);
            if (!getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.STARTED)) {
                playbackService.detach(webView);
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            playbackService = null;
            webView = null;
        }
    };
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

        serviceBound = requireContext().bindService(new Intent(requireContext(), LiveStreamPlaybackService.class),
                serviceConnection, Context.BIND_AUTO_CREATE);
        requireActivity().getOnBackPressedDispatcher().addCallback(getViewLifecycleOwner(), exitFullscreenCallback);
        return viewBinding.getRoot();
    }

    private void enterFullscreen(@NonNull View view, @NonNull WebChromeClient.CustomViewCallback callback) {
        Activity activity = getActivity();
        if (activity == null || fullscreenView != null) {
            callback.onCustomViewHidden();
            return;
        }
        FrameLayout fullscreenContainer = new FrameLayout(activity) {
            @Override
            public void dispatchWindowVisibilityChanged(int visibility) {
                super.dispatchWindowVisibilityChanged(View.VISIBLE);
            }
        };
        fullscreenContainer.addView(view, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        fullscreenView = fullscreenContainer;
        fullscreenCallback = callback;
        ViewGroup decorView = (ViewGroup) activity.getWindow().getDecorView();
        decorView.addView(fullscreenContainer, new FrameLayout.LayoutParams(
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
        if (playbackService != null && webView != null && viewBinding != null
                && webView.getParent() != viewBinding.videoHolder) {
            webView = playbackService.attach(requireActivity(), requireArguments());
            webView.setWebChromeClient(chromeClient);
            viewBinding.videoHolder.addView(webView, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
    }

    @Override
    public void onStop() {
        exitFullscreen();
        if (playbackService != null && webView != null && viewBinding != null
                && webView.getParent() == viewBinding.videoHolder) {
            playbackService.detach(webView);
        }
        super.onStop();
    }

    @Override
    public void onDestroyView() {
        exitFullscreen();
        if (playbackService != null && webView != null && webView.getParent() == viewBinding.videoHolder) {
            playbackService.detach(webView);
        }
        if (serviceBound) {
            requireContext().unbindService(serviceConnection);
            serviceBound = false;
        }
        playbackService = null;
        webView = null;
        chromeClient = null;
        viewBinding = null;
        super.onDestroyView();
    }
}
