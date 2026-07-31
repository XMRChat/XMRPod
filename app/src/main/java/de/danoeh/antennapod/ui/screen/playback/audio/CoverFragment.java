package de.danoeh.antennapod.ui.screen.playback.audio;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.ColorFilter;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.BlendModeColorFilterCompat;
import androidx.core.graphics.BlendModeCompat;
import androidx.fragment.app.Fragment;
import androidx.media3.session.MediaController;
import com.bumptech.glide.Glide;
import com.bumptech.glide.RequestBuilder;
import com.bumptech.glide.load.resource.bitmap.FitCenter;
import com.bumptech.glide.load.resource.bitmap.RoundedCorners;
import com.bumptech.glide.request.RequestOptions;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import de.danoeh.antennapod.BuildConfig;
import de.danoeh.antennapod.R;
import de.danoeh.antennapod.event.MessageEvent;
import de.danoeh.antennapod.event.PlayerStatusEvent;
import de.danoeh.antennapod.model.feed.Feed;
import de.danoeh.antennapod.model.feed.FeedFunding;
import de.danoeh.antennapod.net.common.AntennapodHttpClient;
import de.danoeh.antennapod.playback.service.PlaybackService;
import de.danoeh.antennapod.playback.service.PlaybackServiceStarter;
import de.danoeh.antennapod.storage.database.DBReader;
import de.danoeh.antennapod.storage.preferences.PlaybackPreferences;
import de.danoeh.antennapod.ui.appstartintent.MainActivityStarter;
import de.danoeh.antennapod.ui.appstartintent.MediaButtonStarter;
import de.danoeh.antennapod.ui.appstartintent.OnlineFeedviewActivityStarter;
import de.danoeh.antennapod.ui.chapters.ChapterUtils;
import de.danoeh.antennapod.ui.common.IntentUtils;
import de.danoeh.antennapod.ui.screen.chapter.ChaptersFragment;
import de.danoeh.antennapod.playback.service.PlaybackController;
import de.danoeh.antennapod.ui.common.DateFormatter;
import de.danoeh.antennapod.databinding.CoverFragmentBinding;
import de.danoeh.antennapod.event.playback.PlaybackPositionEvent;
import de.danoeh.antennapod.model.feed.Chapter;
import de.danoeh.antennapod.model.feed.EmbeddedChapterImage;
import de.danoeh.antennapod.model.feed.FeedMedia;
import de.danoeh.antennapod.model.playback.Playable;
import de.danoeh.antennapod.ui.episodes.ImageResourceUtils;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.apache.commons.lang3.StringUtils;
import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import java.util.List;

import static android.widget.LinearLayout.LayoutParams.MATCH_PARENT;
import static android.widget.LinearLayout.LayoutParams.WRAP_CONTENT;

/**
 * Displays the cover and the title of a FeedItem.
 */
public class CoverFragment extends Fragment {
    private static final String TAG = "CoverFragment";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final String XMR_PRICE_URL = "https://nest.xmrchat.com/prices/xmr";
    private static final int XMR_AMOUNT_SCALE = 12;
    private static final Pattern MONERO_URI_PATTERN = Pattern.compile("monero:[^\\s<>'\"]+",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern XMRCHAT_URL_PATTERN = Pattern.compile(
            "(?:https?://)?(?:www\\.)?xmrchat\\.com/[A-Za-z0-9_-]+", Pattern.CASE_INSENSITIVE);
    private static final int MAX_XMRCHAT_SEARCH_CANDIDATES = 4;
    private CoverFragmentBinding viewBinding;
    private Disposable disposable;
    private Disposable tipDiscoveryDisposable;
    private Disposable tipDisposable;
    private Disposable xmrPriceDisposable;
    private Object tipDiscoveryMediaIdentifier;
    private Object visibleTipMediaIdentifier;
    private TipTarget visibleTipTarget;
    private int displayedChapterIndex = -1;
    private Playable media;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        viewBinding = CoverFragmentBinding.inflate(inflater);
        viewBinding.imgvCover.setOnClickListener(v -> {
            if (BuildConfig.USE_MEDIA3_PLAYBACK_SERVICE) {
                if (PlaybackService.isRunning) {
                    PlaybackController.bindToMedia3Service(getActivity(), MediaController::pause);
                } else if (media != null) {
                    new PlaybackServiceStarter(getContext(), media)
                            .callEvenIfRunning(true)
                            .start();
                }
                return;
            }
            if (PlaybackService.isRunning
                    && PlaybackPreferences.getCurrentPlayerStatus() == PlaybackPreferences.PLAYER_STATUS_PLAYING) {
                getContext().sendBroadcast(MediaButtonStarter.createIntent(getContext(), KeyEvent.KEYCODE_MEDIA_PAUSE));
            } else if (media != null) {
                new PlaybackServiceStarter(getContext(), media)
                        .callEvenIfRunning(true)
                        .start();
            }
        });
        viewBinding.openDescription.setOnClickListener(view -> ((AudioPlayerFragment) requireParentFragment())
                .scrollToPage(AudioPlayerFragment.POS_DESCRIPTION, true));
        ColorFilter colorFilter = BlendModeColorFilterCompat.createBlendModeColorFilterCompat(
                viewBinding.txtvPodcastTitle.getCurrentTextColor(), BlendModeCompat.SRC_IN);
        viewBinding.butNextChapter.setColorFilter(colorFilter);
        viewBinding.butPrevChapter.setColorFilter(colorFilter);
        viewBinding.descriptionIcon.setColorFilter(colorFilter);
        viewBinding.tipButton.setColorFilter(colorFilter);
        viewBinding.chapterButton.setOnClickListener(v ->
                new ChaptersFragment().show(getChildFragmentManager(), ChaptersFragment.TAG));
        viewBinding.butPrevChapter.setOnClickListener(v -> seekToPrevChapter());
        viewBinding.butNextChapter.setOnClickListener(v -> seekToNextChapter());
        return viewBinding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        configureForOrientation(getResources().getConfiguration());
    }

    private void loadMediaInfo(boolean includingChapters) {
        if (disposable != null) {
            disposable.dispose();
        }
        disposable = Maybe.<Playable>create(emitter -> {
            Playable media = DBReader.getFeedMedia(PlaybackPreferences.getCurrentlyPlayingFeedMediaId());
            if (media != null) {
                if (includingChapters) {
                    ChapterUtils.loadChapters(media, getContext(), false);
                }
                emitter.onSuccess(media);
            } else {
                emitter.onComplete();
            }
        }).subscribeOn(Schedulers.computation())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(media -> {
                    this.media = media;
                    displayMediaInfo(media);
                    if (media.getChapters() == null && !includingChapters) {
                        loadMediaInfo(true);
                    }
                }, error -> Log.e(TAG, Log.getStackTraceString(error)));
    }

    private void displayMediaInfo(@NonNull Playable media) {
        String pubDateStr = DateFormatter.formatAbbrev(getActivity(), media.getPubDate());
        viewBinding.txtvPodcastTitle.setText(StringUtils.stripToEmpty(media.getFeedTitle())
                + "\u00A0"
                + "・"
                + "\u00A0"
                + StringUtils.replace(StringUtils.stripToEmpty(pubDateStr), " ", "\u00A0"));
        if (media instanceof FeedMedia) {
            FeedMedia feedMedia = (FeedMedia) media;
            Feed feed = feedMedia.getItem().getFeed();
            viewBinding.txtvPodcastTitle.setOnClickListener(v -> openFeed(feed));
        } else {
            viewBinding.txtvPodcastTitle.setOnClickListener(null);
        }
        updateTipButton(media);
        viewBinding.txtvPodcastTitle.setOnLongClickListener(v -> copyText(media.getFeedTitle()));
        viewBinding.txtvEpisodeTitle.setText(media.getEpisodeTitle());
        viewBinding.txtvEpisodeTitle.setOnLongClickListener(v -> copyText(media.getEpisodeTitle()));
        viewBinding.txtvEpisodeTitle.setOnClickListener(v -> {
            int lines = viewBinding.txtvEpisodeTitle.getLineCount();
            int animUnit = 1500;
            if (lines > viewBinding.txtvEpisodeTitle.getMaxLines()) {
                int titleHeight = viewBinding.txtvEpisodeTitle.getHeight()
                        - viewBinding.txtvEpisodeTitle.getPaddingTop()
                        - viewBinding.txtvEpisodeTitle.getPaddingBottom();
                ObjectAnimator verticalMarquee = ObjectAnimator.ofInt(
                        viewBinding.txtvEpisodeTitle, "scrollY", 0, (lines - viewBinding.txtvEpisodeTitle.getMaxLines())
                                        * (titleHeight / viewBinding.txtvEpisodeTitle.getMaxLines()))
                        .setDuration(lines * animUnit);
                ObjectAnimator fadeOut = ObjectAnimator.ofFloat(
                        viewBinding.txtvEpisodeTitle, "alpha", 0);
                fadeOut.setStartDelay(animUnit);
                fadeOut.addListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        viewBinding.txtvEpisodeTitle.scrollTo(0, 0);
                    }
                });
                ObjectAnimator fadeBackIn = ObjectAnimator.ofFloat(
                        viewBinding.txtvEpisodeTitle, "alpha", 1);
                AnimatorSet set = new AnimatorSet();
                set.playSequentially(verticalMarquee, fadeOut, fadeBackIn);
                set.start();
            }
        });
        
        displayedChapterIndex = -1;
        refreshChapterData(Chapter.getAfterPosition(media.getChapters(), media.getPosition()));
        updateChapterControlVisibility();
    }

    private void updateTipButton(@Nullable Playable media) {
        if (media == null) {
            clearTipDiscovery();
            visibleTipMediaIdentifier = null;
            visibleTipTarget = null;
            showTipButton(null);
            return;
        }

        Object mediaIdentifier = getTipMediaIdentifier(media);
        if (!Objects.equals(mediaIdentifier, visibleTipMediaIdentifier)) {
            clearTipDiscovery();
            visibleTipMediaIdentifier = mediaIdentifier;
            visibleTipTarget = null;
        }

        TipTarget tipTarget = getTipTarget(media);
        Log.d(TAG, "Tip target direct lookup for media="
                + media.getClass().getSimpleName()
                + ", feedTitle=" + media.getFeedTitle()
                + ", episodeTitle=" + media.getEpisodeTitle()
                + ", found=" + (tipTarget != null));
        if (tipTarget != null) {
            clearTipDiscovery();
            visibleTipTarget = tipTarget;
            showTipButton(tipTarget);
            return;
        }

        if (visibleTipTarget != null) {
            showTipButton(visibleTipTarget);
            return;
        }

        if (tipDiscoveryDisposable != null && Objects.equals(mediaIdentifier, tipDiscoveryMediaIdentifier)) {
            return;
        }
        clearTipDiscovery();
        showTipButton(null);
        discoverXmrChatTipTarget(media, mediaIdentifier);
    }

    private void showTipButton(@Nullable TipTarget tipTarget) {
        Log.d(TAG, "Setting tip button visible=" + (tipTarget != null));
        viewBinding.tipButton.setVisibility(tipTarget == null ? View.GONE : View.VISIBLE);
        viewBinding.tipButton.setOnClickListener(tipTarget == null ? null : v -> openTipTarget(tipTarget));
    }

    @Nullable
    private TipTarget getTipTarget(@Nullable Playable media) {
        if (media == null) {
            return null;
        }

        TipTarget directTarget = getTipTargetFromText(media.getFeedTitle());
        if (directTarget != null) {
            return directTarget;
        }
        directTarget = getTipTargetFromText(media.getEpisodeTitle());
        if (directTarget != null) {
            return directTarget;
        }

        if (!(media instanceof FeedMedia) || ((FeedMedia) media).getItem() == null) {
            return null;
        }

        FeedMedia feedMedia = (FeedMedia) media;

        directTarget = getTipTargetFromText(feedMedia.getItem().getPaymentLink());
        if (directTarget != null) {
            return directTarget;
        }
        directTarget = getTipTargetFromText(feedMedia.getItem().getDescription());
        if (directTarget != null) {
            return directTarget;
        }

        Feed feed = feedMedia.getItem().getFeed();
        if (feed == null) {
            return null;
        }
        directTarget = getTipTargetFromText(feed.getDescription());
        if (directTarget != null) {
            return directTarget;
        }
        if (feed.getPaymentLinks() == null) {
            return null;
        }

        TipTarget xmrChatTarget = null;
        for (FeedFunding funding : feed.getPaymentLinks()) {
            if (!TextUtils.isEmpty(funding.url)) {
                directTarget = getTipTargetFromUrl(funding.url);
                if (directTarget != null && !TextUtils.isEmpty(directTarget.moneroUri)) {
                    return directTarget;
                }
                if (directTarget != null) {
                    xmrChatTarget = directTarget;
                }
            }
            directTarget = getTipTargetFromText(funding.content);
            if (directTarget != null && !TextUtils.isEmpty(directTarget.moneroUri)) {
                return directTarget;
            }
            if (directTarget != null) {
                xmrChatTarget = directTarget;
            }
        }
        return xmrChatTarget;
    }

    @Nullable
    private TipTarget getTipTargetFromText(@Nullable String text) {
        if (TextUtils.isEmpty(text)) {
            return null;
        }

        Matcher moneroMatcher = MONERO_URI_PATTERN.matcher(text);
        if (moneroMatcher.find()) {
            return TipTarget.forMoneroUri(moneroMatcher.group());
        }

        Matcher xmrChatMatcher = XMRCHAT_URL_PATTERN.matcher(text);
        if (xmrChatMatcher.find()) {
            return TipTarget.forXmrChatUrl(xmrChatMatcher.group());
        }
        return null;
    }

    @Nullable
    private TipTarget getTipTargetFromUrl(@Nullable String url) {
        if (TextUtils.isEmpty(url)) {
            return null;
        }
        String trimmedUrl = url.trim();
        String normalizedUrl = trimmedUrl.toLowerCase(Locale.US);
        if (normalizedUrl.startsWith("monero:")) {
            return TipTarget.forMoneroUri(trimmedUrl);
        }
        if (normalizedUrl.contains("xmrchat")) {
            return TipTarget.forXmrChatUrl(trimmedUrl);
        }
        return null;
    }

    private void clearTipDiscovery() {
        if (tipDiscoveryDisposable != null) {
            tipDiscoveryDisposable.dispose();
            tipDiscoveryDisposable = null;
        }
        tipDiscoveryMediaIdentifier = null;
    }

    @NonNull
    private Object getTipMediaIdentifier(@NonNull Playable media) {
        Object identifier = media.getIdentifier();
        if (identifier != null) {
            return identifier;
        }
        return media.getClass().getName() + ":" + StringUtils.stripToEmpty(media.getFeedTitle())
                + ":" + StringUtils.stripToEmpty(media.getEpisodeTitle());
    }

    private void discoverXmrChatTipTarget(@NonNull Playable media, @NonNull Object mediaIdentifier) {
        tipDiscoveryMediaIdentifier = mediaIdentifier;
        tipDiscoveryDisposable = Maybe.<TipTarget>create(emitter -> {
            for (String candidate : getXmrChatSearchCandidates(media)) {
                Log.d(TAG, "Searching XMRChat pages for candidate=" + candidate);
                TipTarget target = searchXmrChatPage(candidate, media);
                if (target != null) {
                    Log.d(TAG, "Found XMRChat tip target for candidate=" + candidate);
                    emitter.onSuccess(target);
                    return;
                }
            }
            Log.d(TAG, "No XMRChat tip target discovered");
            emitter.onComplete();
        }).subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(tipTarget -> {
                    if (!Objects.equals(mediaIdentifier, visibleTipMediaIdentifier)) {
                        return;
                    }
                    tipDiscoveryDisposable = null;
                    tipDiscoveryMediaIdentifier = null;
                    visibleTipTarget = tipTarget;
                    showTipButton(tipTarget);
                }, error -> {
                    tipDiscoveryDisposable = null;
                    tipDiscoveryMediaIdentifier = null;
                    Log.e(TAG, Log.getStackTraceString(error));
                }, () -> {
                    tipDiscoveryDisposable = null;
                    tipDiscoveryMediaIdentifier = null;
                });
    }

    private Set<String> getXmrChatSearchCandidates(@NonNull Playable media) {
        Set<String> candidates = new LinkedHashSet<>();
        addSearchCandidate(candidates, media.getFeedTitle());
        FeedMedia feedMedia = media instanceof FeedMedia ? (FeedMedia) media : null;
        Feed feed = feedMedia == null || feedMedia.getItem() == null ? null : feedMedia.getItem().getFeed();
        if (feed != null) {
            addSearchCandidate(candidates, feed.getAuthor());
            addSearchCandidate(candidates, feed.getTitle());
            addSearchCandidate(candidates, hostLabel(feed.getLink()));
            addSearchCandidate(candidates, hostLabel(feed.getDownloadUrl()));
        }
        addSearchCandidate(candidates, media.getEpisodeTitle());
        return candidates;
    }

    private void addSearchCandidate(@NonNull Set<String> candidates, @Nullable String value) {
        if (candidates.size() >= MAX_XMRCHAT_SEARCH_CANDIDATES) {
            return;
        }
        String candidate = StringUtils.stripToEmpty(value);
        if (candidate.length() >= 3) {
            Log.d(TAG, "Adding XMRChat search candidate=" + candidate);
            candidates.add(candidate);
        }
    }

    @Nullable
    private String hostLabel(@Nullable String value) {
        if (TextUtils.isEmpty(value)) {
            return null;
        }
        String url = value.contains("://") ? value : "https://" + value;
        String host = Uri.parse(url).getHost();
        if (TextUtils.isEmpty(host)) {
            return null;
        }
        host = StringUtils.removeStart(host.toLowerCase(Locale.US), "www.");
        int dot = host.indexOf('.');
        return dot > 0 ? host.substring(0, dot) : host;
    }

    @Nullable
    private TipTarget searchXmrChatPage(@NonNull String candidate, @NonNull Playable media) throws IOException {
        HttpUrl url = HttpUrl.parse("https://nest.xmrchat.com/pages/search").newBuilder()
                .addQueryParameter("search", candidate)
                .addQueryParameter("limit", "3")
                .build();
        Request request = new Request.Builder().url(url).get().build();
        try (Response response = AntennapodHttpClient.getHttpClient().newCall(request).execute()) {
            ResponseBody body = response.body();
            String responseBody = body == null ? "" : body.string();
            if (!response.isSuccessful()) {
                throw new IOException("XMRChat page search failed: " + response.code());
            }
            JSONArray pages;
            try {
                pages = new JSONObject(responseBody).optJSONArray("pages");
            } catch (JSONException e) {
                throw new IOException("XMRChat page search response was not valid JSON", e);
            }
            if (pages == null) {
                Log.d(TAG, "XMRChat search response had no pages for candidate=" + candidate);
                return null;
            }
            for (int i = 0; i < pages.length(); i++) {
                JSONObject page = pages.optJSONObject(i);
                String path = page == null ? null : page.optString("path");
                Log.d(TAG, "XMRChat search result path=" + path
                        + ", name=" + (page == null ? null : page.optString("name"))
                        + ", matches=" + (page != null && isMatchingXmrChatPage(page, candidate, media)));
                if (page != null && isMatchingXmrChatPage(page, candidate, media) && !TextUtils.isEmpty(path)) {
                    return TipTarget.forXmrChatUrl("https://xmrchat.com/" + path);
                }
            }
        }
        return null;
    }

    private boolean isMatchingXmrChatPage(@NonNull JSONObject page, @NonNull String candidate,
                                          @NonNull Playable media) {
        String normalizedCandidate = normalizeSearchValue(candidate);
        String pagePath = normalizeSearchValue(page.optString("path"));
        String pageName = normalizeSearchValue(page.optString("name"));
        if (normalizedCandidate.equals(pagePath) || normalizedCandidate.equals(pageName)) {
            return true;
        }

        FeedMedia feedMedia = media instanceof FeedMedia ? (FeedMedia) media : null;
        Feed feed = feedMedia == null || feedMedia.getItem() == null ? null : feedMedia.getItem().getFeed();
        String feedHost = feed == null ? null : hostLabel(feed.getLink());
        String feedUrl = feed == null ? null : StringUtils.stripToEmpty(feed.getDownloadUrl());
        JSONArray links = page.optJSONArray("links");
        if (links == null) {
            return false;
        }
        for (int i = 0; i < links.length(); i++) {
            JSONObject link = links.optJSONObject(i);
            if (link == null) {
                continue;
            }
            String platform = link.optString("platform");
            String value = StringUtils.stripToEmpty(link.optString("value"));
            if ("podcast-rss".equals(platform) && !TextUtils.isEmpty(feedUrl) && feedUrl.equalsIgnoreCase(value)) {
                return true;
            }
            if ("website".equals(platform) && !TextUtils.isEmpty(feedHost)
                    && feedHost.equalsIgnoreCase(hostLabel(value))) {
                return true;
            }
        }
        return false;
    }

    private String normalizeSearchValue(@Nullable String value) {
        return StringUtils.stripToEmpty(value).toLowerCase(Locale.US)
                .replaceAll("[^a-z0-9]+", "");
    }

    private void openTipTarget(@Nullable TipTarget tipTarget) {
        if (tipTarget == null) {
            return;
        }
        if (!TextUtils.isEmpty(tipTarget.moneroUri)) {
            openMoneroUri(tipTarget.moneroUri);
            return;
        }
        if (TextUtils.isEmpty(tipTarget.xmrChatPath) || TextUtils.isEmpty(tipTarget.xmrChatApiUrl)) {
            IntentUtils.openInBrowser(getContext(), tipTarget.fallbackUrl);
            return;
        }
        showXmrChatTipDialog(tipTarget);
    }

    private void showXmrChatTipDialog(@NonNull TipTarget tipTarget) {
        final BottomSheetDialog dialog = new BottomSheetDialog(requireContext());
        ScrollView scrollView = new ScrollView(requireContext());
        scrollView.setFillViewport(false);

        LinearLayout layout = new LinearLayout(requireContext());
        layout.setOrientation(LinearLayout.VERTICAL);
        float density = getResources().getDisplayMetrics().density;
        int padding = (int) (24 * density);
        final int spacing = (int) (16 * density);
        layout.setPadding(padding, padding, padding, padding);
        scrollView.addView(layout);

        TextView title = new TextView(requireContext());
        title.setText(R.string.tip_label);
        title.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleLarge);
        layout.addView(title, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));

        EditText nameInput = new EditText(requireContext());
        nameInput.setHint(R.string.tip_name_hint);
        nameInput.setSingleLine(true);
        nameInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        LinearLayout.LayoutParams fieldParams = new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT);
        fieldParams.topMargin = spacing;
        layout.addView(nameInput, fieldParams);

        RadioGroup currencyGroup = new RadioGroup(requireContext());
        currencyGroup.setOrientation(RadioGroup.HORIZONTAL);
        RadioButton usdButton = new RadioButton(requireContext());
        usdButton.setId(View.generateViewId());
        usdButton.setText(R.string.tip_currency_usd);
        currencyGroup.addView(usdButton, new RadioGroup.LayoutParams(WRAP_CONTENT, WRAP_CONTENT));
        RadioButton xmrButton = new RadioButton(requireContext());
        xmrButton.setId(View.generateViewId());
        xmrButton.setText(R.string.tip_currency_xmr);
        currencyGroup.addView(xmrButton, new RadioGroup.LayoutParams(WRAP_CONTENT, WRAP_CONTENT));
        currencyGroup.check(usdButton.getId());
        layout.addView(currencyGroup, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));

        EditText amountInput = new EditText(requireContext());
        amountInput.setHint(R.string.tip_amount_usd_hint);
        amountInput.setSingleLine(true);
        amountInput.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        layout.addView(amountInput, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));

        TextView amountPreview = new TextView(requireContext());
        amountPreview.setVisibility(View.GONE);
        layout.addView(amountPreview, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));

        final BigDecimal[] xmrUsdPrice = new BigDecimal[1];
        TextWatcher amountWatcher = new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                updateTipAmountPreview(amountInput, amountPreview, getSelectedTipCurrency(currencyGroup, usdButton),
                        xmrUsdPrice[0]);
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        };
        amountInput.addTextChangedListener(amountWatcher);
        currencyGroup.setOnCheckedChangeListener((group, checkedId) -> {
            TipCurrency currency = getSelectedTipCurrency(currencyGroup, usdButton);
            amountInput.setHint(currency == TipCurrency.USD
                    ? R.string.tip_amount_usd_hint : R.string.tip_amount_xmr_hint);
            updateTipAmountPreview(amountInput, amountPreview, currency, xmrUsdPrice[0]);
            if (xmrUsdPrice[0] == null) {
                fetchXmrUsdPrice(xmrUsdPrice, amountInput, amountPreview, currencyGroup, usdButton);
            }
        });
        fetchXmrUsdPrice(xmrUsdPrice, amountInput, amountPreview, currencyGroup, usdButton);

        EditText messageInput = new EditText(requireContext());
        messageInput.setHint(R.string.tip_message_hint);
        messageInput.setFilters(new InputFilter[] {new InputFilter.LengthFilter(255)});
        messageInput.setMinLines(2);
        messageInput.setMaxLines(4);
        messageInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        layout.addView(messageInput, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));

        LinearLayout buttons = new LinearLayout(requireContext());
        buttons.setGravity(android.view.Gravity.END);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams buttonRowParams = new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT);
        buttonRowParams.topMargin = spacing;
        layout.addView(buttons, buttonRowParams);

        Button cancelButton = new Button(requireContext());
        cancelButton.setText(android.R.string.cancel);
        cancelButton.setOnClickListener(v -> dialog.dismiss());
        buttons.addView(cancelButton, new LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT));

        Button openWalletButton = new Button(requireContext());
        openWalletButton.setText(R.string.tip_open_wallet);
        openWalletButton.setOnClickListener(v -> {
            String name = nameInput.getText().toString().trim();
            String amount = amountInput.getText().toString().trim();
            if (name.length() < 2 || amount.length() == 0) {
                Toast.makeText(getContext(), R.string.tip_invalid_input, Toast.LENGTH_LONG).show();
                return;
            }
            try {
                parsePositiveDecimal(amount);
            } catch (NumberFormatException e) {
                Toast.makeText(getContext(), R.string.tip_invalid_input, Toast.LENGTH_LONG).show();
                return;
            }
            final String message = messageInput.getText().toString().trim();
            final TipCurrency currency = getSelectedTipCurrency(currencyGroup, usdButton);
            openWalletButton.setEnabled(false);
            createXmrChatTip(dialog, openWalletButton, tipTarget, name, amount, message, currency, xmrUsdPrice[0]);
        });
        buttons.addView(openWalletButton, new LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT));

        dialog.setContentView(scrollView);
        dialog.setOnShowListener(dialogInterface -> {
            FrameLayout bottomSheet = dialog.findViewById(com.google.android.material.R.id.design_bottom_sheet);
            if (bottomSheet != null) {
                BottomSheetBehavior<FrameLayout> behavior = BottomSheetBehavior.from(bottomSheet);
                behavior.setSkipCollapsed(true);
                behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
            }
            if (dialog.getWindow() != null) {
                dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            }
        });
        dialog.show();
    }

    private void createXmrChatTip(@NonNull BottomSheetDialog dialog, @NonNull Button openWalletButton,
                                  @NonNull TipTarget tipTarget, @NonNull String name,
                                  @NonNull String amount, @NonNull String message,
                                  @NonNull TipCurrency currency, @Nullable BigDecimal cachedXmrUsdPrice) {
        if (tipDisposable != null) {
            tipDisposable.dispose();
        }
        Toast.makeText(getContext(), R.string.tip_creating, Toast.LENGTH_SHORT).show();
        tipDisposable = Maybe.<String>create(emitter -> {
            final String xmrAmount = getTipAmountInXmr(amount, currency, cachedXmrUsdPrice);
            JSONObject payload = new JSONObject();
            payload.put("path", tipTarget.xmrChatPath);
            payload.put("name", name);
            if (!TextUtils.isEmpty(message)) {
                payload.put("message", message);
            }
            payload.put("amount", xmrAmount);
            payload.put("private", false);

            Request request = new Request.Builder()
                    .url(tipTarget.xmrChatApiUrl)
                    .post(RequestBody.create(payload.toString(), JSON))
                    .build();
            try (Response response = AntennapodHttpClient.getHttpClient().newCall(request).execute()) {
                ResponseBody body = response.body();
                String responseBody = body == null ? "" : body.string();
                if (!response.isSuccessful()) {
                    throw new IOException(getXmrChatErrorMessage(responseBody, response.code()));
                }
                String paymentAddress = new JSONObject(responseBody).optString("paymentAddress");
                if (TextUtils.isEmpty(paymentAddress)) {
                    throw new IOException("XMRChat tip response did not include a payment address");
                }
                emitter.onSuccess("monero:" + paymentAddress
                        + "?tx_amount=" + Uri.encode(xmrAmount)
                        + "&tx_description=" + Uri.encode("XMRPod tip"));
            }
        }).subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(moneroUri -> {
                    dialog.dismiss();
                    openMoneroUri(moneroUri);
                }, error -> {
                    openWalletButton.setEnabled(true);
                    Log.e(TAG, Log.getStackTraceString(error));
                    String errorMessage = error.getMessage();
                    if (TextUtils.isEmpty(errorMessage)) {
                        Toast.makeText(getContext(), R.string.tip_create_failed, Toast.LENGTH_LONG).show();
                    } else {
                        Toast.makeText(getContext(), getString(R.string.tip_create_failed_with_reason, errorMessage),
                                Toast.LENGTH_LONG).show();
                    }
                });
    }

    private String getXmrChatErrorMessage(@NonNull String responseBody, int statusCode) {
        String fallback = "XMRChat tip request failed: " + statusCode;
        if (TextUtils.isEmpty(responseBody)) {
            return fallback;
        }
        try {
            JSONObject errorResponse = new JSONObject(responseBody);
            Object message = errorResponse.opt("message");
            if (message instanceof JSONArray) {
                JSONArray messages = (JSONArray) message;
                if (messages.length() > 0) {
                    return messages.optString(0, fallback);
                }
            } else if (message != null && message != JSONObject.NULL) {
                return String.valueOf(message);
            }
            String error = errorResponse.optString("error");
            return TextUtils.isEmpty(error) ? fallback : error;
        } catch (JSONException e) {
            return responseBody;
        }
    }

    private void fetchXmrUsdPrice(@NonNull BigDecimal[] xmrUsdPrice, @NonNull EditText amountInput,
                                  @NonNull TextView amountPreview, @NonNull RadioGroup currencyGroup,
                                  @NonNull RadioButton usdButton) {
        if (xmrPriceDisposable != null) {
            xmrPriceDisposable.dispose();
        }
        xmrPriceDisposable = Maybe.<BigDecimal>create(emitter -> {
            Request request = new Request.Builder().url(XMR_PRICE_URL).get().build();
            try (Response response = AntennapodHttpClient.getHttpClient().newCall(request).execute()) {
                ResponseBody body = response.body();
                String responseBody = body == null ? "" : body.string();
                if (!response.isSuccessful()) {
                    throw new IOException("XMR price request failed: " + response.code());
                }
                emitter.onSuccess(parseXmrUsdPrice(responseBody));
            }
        }).subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(price -> {
                    xmrUsdPrice[0] = price;
                    updateTipAmountPreview(amountInput, amountPreview,
                            getSelectedTipCurrency(currencyGroup, usdButton), price);
                }, error -> {
                    Log.e(TAG, Log.getStackTraceString(error));
                    updateTipAmountPreview(amountInput, amountPreview,
                            getSelectedTipCurrency(currencyGroup, usdButton), null);
                });
    }

    private String getTipAmountInXmr(@NonNull String amount, @NonNull TipCurrency currency,
                                     @Nullable BigDecimal cachedXmrUsdPrice)
            throws IOException, JSONException {
        BigDecimal amountValue = parsePositiveDecimal(amount);
        if (currency == TipCurrency.XMR) {
            return formatXmrAmount(amountValue);
        }
        BigDecimal price = cachedXmrUsdPrice == null ? fetchXmrUsdPrice() : cachedXmrUsdPrice;
        return formatXmrAmount(amountValue.divide(price, XMR_AMOUNT_SCALE, RoundingMode.HALF_UP));
    }

    private BigDecimal fetchXmrUsdPrice() throws IOException, JSONException {
        Request request = new Request.Builder().url(XMR_PRICE_URL).get().build();
        try (Response response = AntennapodHttpClient.getHttpClient().newCall(request).execute()) {
            ResponseBody body = response.body();
            String responseBody = body == null ? "" : body.string();
            if (!response.isSuccessful()) {
                throw new IOException("XMR price request failed: " + response.code());
            }
            return parseXmrUsdPrice(responseBody);
        }
    }

    private BigDecimal parseXmrUsdPrice(@NonNull String responseBody) throws JSONException, IOException {
        String trimmedBody = responseBody.trim();
        BigDecimal price;
        if (trimmedBody.startsWith("{")) {
            JSONObject response = new JSONObject(trimmedBody);
            price = new BigDecimal(response.optString("usd", response.optString("price")));
        } else {
            price = new BigDecimal(trimmedBody);
        }
        if (price.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IOException("XMR price must be positive");
        }
        return price;
    }

    private void updateTipAmountPreview(@NonNull EditText amountInput, @NonNull TextView amountPreview,
                                        @NonNull TipCurrency currency, @Nullable BigDecimal xmrUsdPrice) {
        String amount = amountInput.getText().toString().trim();
        if (TextUtils.isEmpty(amount)) {
            amountPreview.setVisibility(View.GONE);
            return;
        }
        if (xmrUsdPrice == null) {
            amountPreview.setText(R.string.tip_price_unavailable);
            amountPreview.setVisibility(View.VISIBLE);
            return;
        }
        try {
            BigDecimal amountValue = parsePositiveDecimal(amount);
            if (currency == TipCurrency.XMR) {
                String usdAmount = formatUsdAmount(amountValue.multiply(xmrUsdPrice));
                amountPreview.setText(getString(R.string.tip_amount_xmr_conversion_preview, amount, usdAmount));
            } else {
                String xmrAmount = formatXmrAmount(amountValue.divide(xmrUsdPrice, XMR_AMOUNT_SCALE,
                        RoundingMode.HALF_UP));
                amountPreview.setText(getString(R.string.tip_amount_conversion_preview, amount, xmrAmount));
            }
            amountPreview.setVisibility(View.VISIBLE);
        } catch (NumberFormatException e) {
            amountPreview.setVisibility(View.GONE);
        }
    }

    private BigDecimal parsePositiveDecimal(@NonNull String amount) {
        BigDecimal value = new BigDecimal(amount);
        if (value.compareTo(BigDecimal.ZERO) <= 0) {
            throw new NumberFormatException("Amount must be positive");
        }
        return value;
    }

    private String formatXmrAmount(@NonNull BigDecimal amount) {
        return amount.setScale(XMR_AMOUNT_SCALE, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    private String formatUsdAmount(@NonNull BigDecimal amount) {
        return amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private TipCurrency getSelectedTipCurrency(@NonNull RadioGroup currencyGroup, @NonNull RadioButton usdButton) {
        return currencyGroup.getCheckedRadioButtonId() == usdButton.getId() ? TipCurrency.USD : TipCurrency.XMR;
    }

    private void openMoneroUri(@NonNull String moneroUri) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(moneroUri)));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(getContext(), R.string.tip_no_wallet, Toast.LENGTH_LONG).show();
        }
    }

    private void openFeed(Feed feed) {
        if (feed == null) {
            return;
        }
        if (feed.getState() == Feed.STATE_NOT_SUBSCRIBED) {
            startActivity(new OnlineFeedviewActivityStarter(getContext(), feed.getDownloadUrl()).getIntent());
        } else {
            new MainActivityStarter(getContext()).withOpenFeed(feed.getId()).withClearTop().start();
        }
    }

    private void updateChapterControlVisibility() {
        boolean chapterControlVisible = false;
        if (media.getChapters() != null) {
            chapterControlVisible = media.getChapters().size() > 0;
        } else if (media instanceof FeedMedia) {
            FeedMedia fm = ((FeedMedia) media);
            // If an item has chapters but they are not loaded yet, still display the button.
            chapterControlVisible = fm.getItem() != null && fm.getItem().hasChapters();
        }
        int newVisibility = chapterControlVisible ? View.VISIBLE : View.GONE;
        if (viewBinding.chapterButton.getVisibility() != newVisibility) {
            viewBinding.chapterButton.setVisibility(newVisibility);
            ObjectAnimator.ofFloat(viewBinding.chapterButton,
                    "alpha",
                    chapterControlVisible ? 0 : 1,
                    chapterControlVisible ? 1 : 0)
                    .start();
        }
    }

    private void refreshChapterData(int chapterIndex) {
        List<Chapter> chapters = media.getChapters();
        if (chapterIndex > -1 && chapters != null) {
            if (media.getPosition() > media.getDuration() || chapterIndex >= chapters.size() - 1) {
                displayedChapterIndex = chapters.size() - 1;
                viewBinding.butNextChapter.setVisibility(View.INVISIBLE);
            } else {
                displayedChapterIndex = chapterIndex;
                viewBinding.butNextChapter.setVisibility(View.VISIBLE);
            }
        }

        displayCoverImage();
    }

    private Chapter getCurrentChapter() {
        if (media == null || media.getChapters() == null || displayedChapterIndex == -1) {
            return null;
        }
        return media.getChapters().get(displayedChapterIndex);
    }

    private void seekToPrevChapter() {
        Chapter curr = getCurrentChapter();

        if (curr == null || displayedChapterIndex == -1) {
            return;
        }

        PlaybackController.bindToMedia3Service(getActivity(), controller -> {
            if (displayedChapterIndex < 1) {
                controller.seekTo(0);
            } else if ((controller.getCurrentPosition() - 10000 * controller.getPlaybackParameters().speed)
                    < curr.getStart()) {
                refreshChapterData(displayedChapterIndex - 1);
                controller.seekTo(media.getChapters().get(displayedChapterIndex).getStart());
            } else {
                controller.seekTo(curr.getStart());
            }
        });
    }

    private void seekToNextChapter() {
        if (media == null || media.getChapters() == null
                || displayedChapterIndex == -1 || displayedChapterIndex + 1 >= media.getChapters().size()) {
            return;
        }

        refreshChapterData(displayedChapterIndex + 1);
        PlaybackController.bindToMedia3Service(getActivity(), controller ->
                controller.seekTo(media.getChapters().get(displayedChapterIndex).getStart()));
    }

    @Override
    public void onStart() {
        super.onStart();
        loadMediaInfo(false);
        EventBus.getDefault().register(this);
    }

    @Override
    public void onStop() {
        super.onStop();

        EventBus.getDefault().unregister(this);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (disposable != null) {
            disposable.dispose();
        }
        if (tipDiscoveryDisposable != null) {
            tipDiscoveryDisposable.dispose();
        }
        if (tipDisposable != null) {
            tipDisposable.dispose();
        }
        if (xmrPriceDisposable != null) {
            xmrPriceDisposable.dispose();
        }
        viewBinding = null;
    }

    private enum TipCurrency {
        USD,
        XMR
    }

    private static class TipTarget {
        private final String moneroUri;
        private final String fallbackUrl;
        private final String xmrChatApiUrl;
        private final String xmrChatPath;

        private TipTarget(@Nullable String moneroUri, @Nullable String fallbackUrl,
                          @Nullable String xmrChatApiUrl, @Nullable String xmrChatPath) {
            this.moneroUri = moneroUri;
            this.fallbackUrl = fallbackUrl;
            this.xmrChatApiUrl = xmrChatApiUrl;
            this.xmrChatPath = xmrChatPath;
        }

        private static TipTarget forMoneroUri(@NonNull String moneroUri) {
            return new TipTarget(moneroUri, null, null, null);
        }

        @Nullable
        private static TipTarget forXmrChatUrl(@NonNull String url) {
            String normalizedUrl = url.contains("://") ? url : "https://" + url;
            Uri uri = Uri.parse(normalizedUrl);
            String host = uri.getHost();
            if (TextUtils.isEmpty(host)) {
                return null;
            }

            String path = null;
            for (String segment : uri.getPathSegments()) {
                if (!TextUtils.isEmpty(segment)) {
                    path = segment;
                    break;
                }
            }
            if (TextUtils.isEmpty(path)) {
                return new TipTarget(null, normalizedUrl, null, null);
            }

            String apiUrl;
            String lowerHost = host.toLowerCase(Locale.US);
            if ("xmrchat.com".equals(lowerHost) || "www.xmrchat.com".equals(lowerHost)) {
                apiUrl = "https://nest.xmrchat.com/tips";
            } else {
                String scheme = TextUtils.isEmpty(uri.getScheme()) ? "https" : uri.getScheme();
                apiUrl = scheme + "://" + uri.getEncodedAuthority() + "/tips";
            }
            return new TipTarget(null, normalizedUrl, apiUrl, path);
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onPlayerStatusEvent(PlayerStatusEvent event) {
        loadMediaInfo(false);
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onEventMainThread(PlaybackPositionEvent event) {
        if (media == null) {
            return;
        }
        int newChapterIndex = Chapter.getAfterPosition(media.getChapters(), event.getPosition());
        if (newChapterIndex > -1 && newChapterIndex != displayedChapterIndex) {
            refreshChapterData(newChapterIndex);
        }
    }

    private void displayCoverImage() {
        RequestOptions options = new RequestOptions()
                .dontAnimate()
                .transform(new FitCenter(),
                        new RoundedCorners((int) (16 * getResources().getDisplayMetrics().density)));

        RequestBuilder<Drawable> cover = Glide.with(this)
                .load(media.getImageLocation())
                .error(Glide.with(this)
                        .load(ImageResourceUtils.getFallbackImageLocation(media))
                        .apply(options))
                .apply(options);

        if (displayedChapterIndex == -1 || media == null || media.getChapters() == null
                || TextUtils.isEmpty(media.getChapters().get(displayedChapterIndex).getImageUrl())) {
            cover.into(viewBinding.imgvCover);
        } else {
            Glide.with(this)
                    .load(EmbeddedChapterImage.getModelFor(media, displayedChapterIndex))
                    .apply(options)
                    .thumbnail(cover)
                    .error(cover)
                    .into(viewBinding.imgvCover);
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        configureForOrientation(newConfig);
    }

    private void configureForOrientation(Configuration newConfig) {
        boolean isPortrait = newConfig.orientation == Configuration.ORIENTATION_PORTRAIT;

        viewBinding.coverFragment.setOrientation(isPortrait ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);

        if (isPortrait) {
            viewBinding.coverHolder.setLayoutParams(new LinearLayout.LayoutParams(MATCH_PARENT, 0, 1));
            viewBinding.coverFragmentTextContainer.setLayoutParams(
                    new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
        } else {
            viewBinding.coverHolder.setLayoutParams(new LinearLayout.LayoutParams(0, MATCH_PARENT, 1));
            viewBinding.coverFragmentTextContainer.setLayoutParams(new LinearLayout.LayoutParams(0, MATCH_PARENT, 1));
        }

        ((ViewGroup) viewBinding.episodeDetails.getParent()).removeView(viewBinding.episodeDetails);
        if (isPortrait) {
            viewBinding.coverFragment.addView(viewBinding.episodeDetails);
        } else {
            viewBinding.coverFragmentTextContainer.addView(viewBinding.episodeDetails);
        }
    }

    private boolean copyText(String text) {
        ClipboardManager clipboardManager = ContextCompat.getSystemService(requireContext(), ClipboardManager.class);
        if (clipboardManager != null) {
            clipboardManager.setPrimaryClip(ClipData.newPlainText("XMRPod", text));
        }
        if (Build.VERSION.SDK_INT <= 32) {
            EventBus.getDefault().post(new MessageEvent(getString(R.string.copied_to_clipboard)));
        }
        return true;
    }
}
