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
import android.graphics.Color;
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
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
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
import de.danoeh.antennapod.event.XmrChatDirectoryUpdateEvent;
import de.danoeh.antennapod.model.feed.Feed;
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
import de.danoeh.antennapod.ui.common.ThemeUtils;
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
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static android.widget.LinearLayout.LayoutParams.MATCH_PARENT;
import static android.widget.LinearLayout.LayoutParams.WRAP_CONTENT;

/**
 * Displays the cover and the title of a FeedItem.
 */
public class CoverFragment extends Fragment {
    private static final String TAG = "CoverFragment";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final String XMR_PRICE_URL = "https://nest.xmrchat.com/prices";
    private static final String MONERO_SCHEME = "monero";
    private static final String CAKE_WALLET_SCHEME = "cakewallet";
    private static final String MONERO_COM_SCHEME = "monerocom";
    private static final int XMR_AMOUNT_SCALE = 12;
    private static final int TIP_MESSAGE_DEFAULT_MAX_LENGTH = 255;
    private static final int TIP_MESSAGE_MAX_SERVER_LENGTH = 1000;
    private static final String TIP_SOURCE = "xmrpod";
    private CoverFragmentBinding viewBinding;
    private Disposable disposable;
    private Disposable tipDisposable;
    private Disposable xmrPriceDisposable;
    private Disposable xmrChatPageDisposable;
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
            showTipButton(null);
            return;
        }
        showTipButton(resolveTipTarget(media));
    }

    @Nullable
    private TipTarget resolveTipTarget(@NonNull Playable media) {
        if (!(media instanceof FeedMedia) || ((FeedMedia) media).getItem() == null) {
            return null;
        }
        Feed feed = ((FeedMedia) media).getItem().getFeed();
        if (feed == null) {
            return null;
        }
        XmrChatPageDirectory.getInstance().refreshIfNeeded();
        String path = XmrChatPageDirectory.getInstance().pathForFeed(feed);
        if (TextUtils.isEmpty(path)) {
            path = XmrChatPageDirectory.getInstance().pathForFeedWebsite(feed);
        }
        if (TextUtils.isEmpty(path)) {
            return null;
        }
        return TipTarget.forXmrChatUrl("https://xmrchat.com/" + path);
    }

    private void showTipButton(@Nullable TipTarget tipTarget) {
        viewBinding.tipButton.setVisibility(tipTarget == null ? View.GONE : View.VISIBLE);
        viewBinding.tipButton.setOnClickListener(tipTarget == null ? null : v -> openTipTarget(tipTarget));
    }

    private void openTipTarget(@Nullable TipTarget tipTarget) {
        if (tipTarget == null) {
            return;
        }
        if (!TextUtils.isEmpty(tipTarget.moneroUri)) {
            openMoneroUri(tipTarget.moneroUri);
            return;
        }
        if (TextUtils.isEmpty(tipTarget.xmrChatPath) || TextUtils.isEmpty(tipTarget.xmrChatApiUrl)
                || TextUtils.isEmpty(tipTarget.xmrChatPageApiUrl)) {
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

        LinearLayout titleRow = new LinearLayout(requireContext());
        titleRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        layout.addView(titleRow, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));

        TextView title = new TextView(requireContext());
        title.setText(R.string.tip_label);
        title.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleLarge);
        titleRow.addView(title, new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1));

        ImageButton viewXmrChatPageButton = new ImageButton(requireContext());
        viewXmrChatPageButton.setImageResource(R.drawable.ic_web);
        viewXmrChatPageButton.setColorFilter(ThemeUtils.getColorFromAttr(requireContext(), R.attr.action_icon_color));
        viewXmrChatPageButton.setBackgroundResource(ThemeUtils.getDrawableFromAttr(requireContext(),
                android.R.attr.selectableItemBackgroundBorderless));
        viewXmrChatPageButton.setContentDescription(getString(R.string.tip_view_xmrchat_page));
        viewXmrChatPageButton.setOnClickListener(v -> IntentUtils.openInBrowser(getContext(), tipTarget.fallbackUrl));
        titleRow.addView(viewXmrChatPageButton, new LinearLayout.LayoutParams((int) (48 * density),
                (int) (48 * density)));

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
        usdButton.setText(getString(R.string.tip_currency_fiat, FiatCurrency.USD.label));
        currencyGroup.addView(usdButton, new RadioGroup.LayoutParams(WRAP_CONTENT, WRAP_CONTENT));
        RadioButton xmrButton = new RadioButton(requireContext());
        xmrButton.setId(View.generateViewId());
        xmrButton.setText(R.string.tip_currency_xmr);
        currencyGroup.addView(xmrButton, new RadioGroup.LayoutParams(WRAP_CONTENT, WRAP_CONTENT));
        currencyGroup.check(usdButton.getId());
        layout.addView(currencyGroup, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));

        EditText amountInput = new EditText(requireContext());
        amountInput.setHint(getString(R.string.tip_amount_fiat_hint, FiatCurrency.USD.label));
        amountInput.setSingleLine(true);
        amountInput.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        layout.addView(amountInput, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));

        TextView minimumLabel = new TextView(requireContext());
        minimumLabel.setTextAppearance(
                com.google.android.material.R.style.TextAppearance_Material3_BodySmall);
        minimumLabel.setVisibility(View.GONE);
        layout.addView(minimumLabel, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));

        TextView amountPreview = new TextView(requireContext());
        amountPreview.setVisibility(View.GONE);
        layout.addView(amountPreview, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));

        final BigDecimal[] xmrUsdPrice = new BigDecimal[1];
        final FiatCurrency[] pageFiat = new FiatCurrency[] {FiatCurrency.USD};
        final XmrChatPageDetails[] pageDetails = new XmrChatPageDetails[1];
        final int[] messageMaxLength = new int[] {TIP_MESSAGE_DEFAULT_MAX_LENGTH};

        HorizontalScrollView tierScroll = new HorizontalScrollView(requireContext());
        tierScroll.setHorizontalScrollBarEnabled(false);
        tierScroll.setVisibility(View.GONE);
        LinearLayout tierRow = new LinearLayout(requireContext());
        tierRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        tierRow.setOrientation(LinearLayout.HORIZONTAL);
        tierScroll.addView(tierRow);
        layout.addView(tierScroll, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));

        TextView messageCounter = new TextView(requireContext());
        messageCounter.setGravity(android.view.Gravity.END);
        TextView messageError = new TextView(requireContext());
        messageError.setText(R.string.tip_message_too_short);
        messageError.setTextColor(Color.RED);
        messageError.setGravity(android.view.Gravity.END);
        messageError.setVisibility(View.GONE);

        EditText messageInput = new EditText(requireContext());
        final TextWatcher amountWatcher = new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                updateTipAmountPreview(amountInput, amountPreview, getSelectedTipCurrency(currencyGroup, usdButton),
                        xmrUsdPrice[0], pageFiat[0]);
                updateTipMessageLimit(amountInput, messageInput, messageCounter, pageDetails[0],
                        getSelectedTipCurrency(currencyGroup, usdButton), xmrUsdPrice[0], messageMaxLength);
                updateMinimumLabel(minimumLabel, pageDetails[0], getSelectedTipCurrency(currencyGroup, usdButton),
                        xmrUsdPrice[0], pageFiat[0]);
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        };
        currencyGroup.setOnCheckedChangeListener((group, checkedId) -> {
            TipCurrency currency = getSelectedTipCurrency(currencyGroup, usdButton);
            amountInput.setHint(currency == TipCurrency.USD
                    ? getString(R.string.tip_amount_fiat_hint, pageFiat[0].label)
                    : getString(R.string.tip_amount_xmr_hint));
            updateTipAmountPreview(amountInput, amountPreview, currency, xmrUsdPrice[0], pageFiat[0]);
            updateTipMessageLimit(amountInput, messageInput, messageCounter, pageDetails[0], currency,
                    xmrUsdPrice[0], messageMaxLength);
            updateMinimumLabel(minimumLabel, pageDetails[0], currency, xmrUsdPrice[0], pageFiat[0]);
            if (pageDetails[0] != null) {
                updateTipTierControls(pageDetails[0], tierScroll, tierRow, amountInput, currencyGroup, usdButton,
                        xmrUsdPrice, pageFiat[0]);
            }
            if (xmrUsdPrice[0] == null) {
                fetchXmrUsdPrice(xmrUsdPrice, pageFiat[0], amountInput, amountPreview, currencyGroup, usdButton,
                        pageDetails, tierScroll, tierRow, messageInput, messageCounter, minimumLabel, messageMaxLength);
            }
        });

        messageInput.setHint(R.string.tip_message_hint);
        messageInput.setFilters(new InputFilter[] {new InputFilter.LengthFilter(messageMaxLength[0])});
        messageInput.setMinLines(2);
        messageInput.setMaxLines(4);
        messageInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        messageInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                if (s.length() > messageMaxLength[0]) {
                    s.delete(messageMaxLength[0], s.length());
                }
                updateTipMessageError(messageInput, messageError);
                updateTipMessageCounter(messageInput, messageCounter, messageMaxLength[0]);
            }
        });
        layout.addView(messageInput, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
        layout.addView(messageError, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
        layout.addView(messageCounter, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
        updateTipMessageCounter(messageInput, messageCounter, messageMaxLength[0]);
        amountInput.addTextChangedListener(amountWatcher);
        fetchXmrUsdPrice(xmrUsdPrice, pageFiat[0], amountInput, amountPreview, currencyGroup, usdButton,
                pageDetails, tierScroll, tierRow, messageInput, messageCounter, minimumLabel, messageMaxLength);
        fetchXmrChatPageDetails(tipTarget, pageDetails, tierScroll, tierRow, amountInput, messageInput,
                messageCounter, amountPreview, minimumLabel, currencyGroup, usdButton, xmrUsdPrice, pageFiat,
                messageMaxLength);

        LinearLayout buttons = new LinearLayout(requireContext());
        buttons.setGravity(android.view.Gravity.END);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams buttonRowParams = new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT);
        buttonRowParams.topMargin = spacing;
        layout.addView(buttons, buttonRowParams);

        Button cancelButton = new Button(requireContext());
        cancelButton.setText(android.R.string.cancel);
        cancelButton.setContentDescription(getString(android.R.string.cancel));
        cancelButton.setOnClickListener(v -> dialog.dismiss());
        buttons.addView(cancelButton, new LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT));

        Button openWalletButton = new Button(requireContext());
        openWalletButton.setText(R.string.tip_open_wallet);
        openWalletButton.setContentDescription(getString(R.string.tip_open_wallet));
        TextView nameError = new TextView(requireContext());
        nameError.setText(R.string.tip_name_too_short);
        nameError.setTextColor(Color.RED);
        nameError.setGravity(android.view.Gravity.END);
        nameError.setVisibility(View.GONE);
        openWalletButton.setOnClickListener(v -> {
            String name = nameInput.getText().toString().trim();
            String amount = amountInput.getText().toString().trim();
            if (name.length() < 2) {
                nameError.setVisibility(View.VISIBLE);
                return;
            }
            nameError.setVisibility(View.GONE);
            if (amount.length() == 0) {
                Toast.makeText(getContext(), R.string.tip_invalid_input, Toast.LENGTH_LONG).show();
                return;
            }
            try {
                parsePositiveDecimal(amount);
            } catch (NumberFormatException e) {
                Toast.makeText(getContext(), R.string.tip_invalid_input, Toast.LENGTH_LONG).show();
                return;
            }
            final TipCurrency currency = getSelectedTipCurrency(currencyGroup, usdButton);
            BigDecimal minimum = pageDetails[0] == null ? null : pageDetails[0].minTipAmount;
            BigDecimal xmrAmount = getEnteredXmrAmount(amountInput, currency, xmrUsdPrice[0]);
            if (minimum != null && isBelowMinimum(xmrAmount, minimum)) {
                showTipErrorDialog(getString(R.string.tip_amount_below_minimum,
                        formatMinimumForDisplay(minimum, currency, xmrUsdPrice[0], pageFiat[0])));
                return;
            }
            final String message = limitTipMessage(messageInput.getText().toString().trim(), messageMaxLength[0]);
            if (!isValidOptionalTipMessage(message)) {
                messageError.setVisibility(View.VISIBLE);
                return;
            }
            messageError.setVisibility(View.GONE);
            openWalletButton.setEnabled(false);
            createXmrChatTip(dialog, openWalletButton, tipTarget, name, amount, message, currency, xmrUsdPrice[0],
                    pageFiat[0]);
        });
        buttons.addView(openWalletButton, new LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT));
        layout.addView(nameError, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));

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
                                  @NonNull TipCurrency currency, @Nullable BigDecimal cachedXmrUsdPrice,
                                  @NonNull FiatCurrency fiat) {
        if (tipDisposable != null) {
            tipDisposable.dispose();
        }
        Toast.makeText(getContext(), R.string.tip_creating, Toast.LENGTH_SHORT).show();
        tipDisposable = Maybe.<String>create(emitter -> {
            final String xmrAmount = getTipAmountInXmr(amount, currency, cachedXmrUsdPrice, fiat);
            final String limitedMessage = limitTipMessage(message, TIP_MESSAGE_MAX_SERVER_LENGTH);
            JSONObject payload = new JSONObject();
            payload.put("path", tipTarget.xmrChatPath);
            payload.put("name", name);
            if (!TextUtils.isEmpty(limitedMessage)) {
                payload.put("message", limitedMessage);
            }
            payload.put("amount", xmrAmount);
            payload.put("private", false);
            payload.put("source", TIP_SOURCE);

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
                String moneroUri = "monero:" + paymentAddress
                        + "?tx_amount=" + Uri.encode(xmrAmount);
                emitter.onSuccess(moneroUri);
            }
        }).subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(moneroUri -> {
                    dialog.dismiss();
                    openMoneroUri(moneroUri);
                }, error -> {
                    openWalletButton.setEnabled(true);
                    Log.e(TAG, Log.getStackTraceString(error));
                    showTipErrorDialog(friendlyTipErrorMessage(error));
                });
    }

    @Nullable
    private String friendlyTipErrorMessage(@NonNull Throwable error) {
        if (error instanceof SocketTimeoutException
                || error instanceof UnknownHostException
                || error instanceof ConnectException) {
            return getString(R.string.tip_create_failed_timeout);
        }
        String message = error.getMessage();
        return TextUtils.isEmpty(message) ? null : message;
    }

    private void showTipErrorDialog(@Nullable String message) {
        ScrollView scrollView = new ScrollView(requireContext());
        LinearLayout layout = new LinearLayout(requireContext());
        layout.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (20 * getResources().getDisplayMetrics().density);
        layout.setPadding(padding, padding, padding, padding);
        scrollView.addView(layout, new ScrollView.LayoutParams(MATCH_PARENT, WRAP_CONTENT));

        TextView messageText = new TextView(requireContext());
        messageText.setTextIsSelectable(true);
        if (TextUtils.isEmpty(message)) {
            messageText.setText(R.string.tip_create_failed_generic);
        } else {
            messageText.setText(message);
        }
        layout.addView(messageText, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));

        new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.tip_create_failed)
                .setView(scrollView)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private void fetchXmrChatPageDetails(@NonNull TipTarget tipTarget,
                                         @NonNull XmrChatPageDetails[] pageDetails,
                                         @NonNull HorizontalScrollView tierScroll,
                                         @NonNull LinearLayout tierRow,
                                         @NonNull EditText amountInput,
                                         @NonNull EditText messageInput,
                                         @NonNull TextView messageCounter,
                                         @NonNull TextView amountPreview,
                                         @NonNull TextView minimumLabel,
                                         @NonNull RadioGroup currencyGroup,
                                         @NonNull RadioButton usdButton,
                                         @NonNull BigDecimal[] xmrUsdPrice,
                                         @NonNull FiatCurrency[] pageFiat,
                                         @NonNull int[] messageMaxLength) {
        if (xmrChatPageDisposable != null) {
            xmrChatPageDisposable.dispose();
        }
        xmrChatPageDisposable = Maybe.<XmrChatPageDetails>create(emitter -> {
            Request request = new Request.Builder().url(tipTarget.xmrChatPageApiUrl).get().build();
            try (Response response = AntennapodHttpClient.getHttpClient().newCall(request).execute()) {
                ResponseBody body = response.body();
                String responseBody = body == null ? "" : body.string();
                if (!response.isSuccessful()) {
                    throw new IOException("XMRChat page request failed: " + response.code());
                }
                emitter.onSuccess(parseXmrChatPageDetails(responseBody));
            }
        }).subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(details -> {
                    pageDetails[0] = details;
                    pageFiat[0] = details.fiat;
                    xmrUsdPrice[0] = null;
                    usdButton.setText(getString(R.string.tip_currency_fiat, details.fiat.label));
                    if (getSelectedTipCurrency(currencyGroup, usdButton) == TipCurrency.USD) {
                        amountInput.setHint(getString(R.string.tip_amount_fiat_hint, details.fiat.label));
                    }
                    updateTipAmountPreview(amountInput, amountPreview, getSelectedTipCurrency(currencyGroup, usdButton),
                            null, details.fiat);
                    fetchXmrUsdPrice(xmrUsdPrice, details.fiat, amountInput, amountPreview, currencyGroup, usdButton,
                            pageDetails, tierScroll, tierRow, messageInput, messageCounter, minimumLabel,
                            messageMaxLength);
                    updateTipTierControls(details, tierScroll, tierRow, amountInput, currencyGroup, usdButton,
                            xmrUsdPrice, details.fiat);
                    updateTipMessageLimit(amountInput, messageInput, messageCounter, details,
                            getSelectedTipCurrency(currencyGroup, usdButton), xmrUsdPrice[0], messageMaxLength);
                    updateMinimumLabel(minimumLabel, details, getSelectedTipCurrency(currencyGroup, usdButton),
                            xmrUsdPrice[0], details.fiat);
                }, error -> Log.e(TAG, Log.getStackTraceString(error)));
    }

    private XmrChatPageDetails parseXmrChatPageDetails(@NonNull String responseBody) throws JSONException {
        JSONObject page = new JSONObject(responseBody);
        JSONArray tierArray = page.optJSONArray("pageTipTiers");
        List<PageTipTier> tiers = new ArrayList<>();
        if (tierArray != null) {
            for (int i = 0; i < tierArray.length(); i++) {
                JSONObject tier = tierArray.optJSONObject(i);
                if (tier == null) {
                    continue;
                }
                tiers.add(new PageTipTier(
                        tier.optString("name"),
                        tier.optString("description"),
                        parseNullableDecimal(tier, "minAmount"),
                        tier.has("messageLength") && !tier.isNull("messageLength")
                                ? tier.optInt("messageLength") : null,
                        tier.optString("color")));
            }
        }
        return new XmrChatPageDetails(tiers, FiatCurrency.fromCode(page.optString("fiat")),
                parseNullableDecimal(page, "minTipAmount"));
    }

    @Nullable
    private BigDecimal parseNullableDecimal(@NonNull JSONObject object, @NonNull String key) {
        if (!object.has(key) || object.isNull(key)) {
            return null;
        }
        try {
            return new BigDecimal(String.valueOf(object.get(key)));
        } catch (JSONException | NumberFormatException e) {
            return null;
        }
    }

    private void updateTipTierControls(@NonNull XmrChatPageDetails details,
                                       @NonNull HorizontalScrollView tierScroll,
                                       @NonNull LinearLayout tierRow,
                                       @NonNull EditText amountInput,
                                       @NonNull RadioGroup currencyGroup,
                                       @NonNull RadioButton usdButton,
                                       @NonNull BigDecimal[] xmrUsdPrice,
                                       @NonNull FiatCurrency fiat) {
        tierRow.removeAllViews();
        if (details.pageTipTiers.isEmpty()) {
            tierScroll.setVisibility(View.GONE);
            return;
        }
        float density = getResources().getDisplayMetrics().density;
        int horizontalPadding = (int) (12 * density);
        int verticalPadding = (int) (4 * density);
        for (PageTipTier tier : details.pageTipTiers) {
            if (tier.minAmount == null) {
                continue;
            }
            Button tierButton = new Button(requireContext());
            tierButton.setAllCaps(false);
            tierButton.setText(getTierButtonText(tier, getSelectedTipCurrency(currencyGroup, usdButton),
                    xmrUsdPrice[0], fiat));
            tierButton.setContentDescription(getTierButtonContentDescription(tier,
                    getSelectedTipCurrency(currencyGroup, usdButton), xmrUsdPrice[0], fiat));
            tierButton.setPadding(horizontalPadding, verticalPadding, horizontalPadding, verticalPadding);
            applyTierColor(tierButton, tier.color);
            tierButton.setOnClickListener(v -> {
                TipCurrency currency = getSelectedTipCurrency(currencyGroup, usdButton);
                BigDecimal price = xmrUsdPrice[0];
                if (currency == TipCurrency.USD && price != null) {
                    amountInput.setText(formatFiatAmount(tier.minAmount.multiply(price)));
                } else {
                    currencyGroup.check(getXmrCurrencyButtonId(currencyGroup, usdButton));
                    amountInput.setText(formatXmrAmount(tier.minAmount));
                }
                amountInput.setSelection(amountInput.getText().length());
            });
            LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT);
            buttonParams.setMarginEnd((int) (8 * density));
            tierRow.addView(tierButton, buttonParams);
        }
        ImageButton infoButton = new ImageButton(requireContext());
        infoButton.setImageResource(R.drawable.ic_info);
        infoButton.setColorFilter(ThemeUtils.getColorFromAttr(requireContext(), R.attr.action_icon_color));
        infoButton.setBackgroundResource(ThemeUtils.getDrawableFromAttr(requireContext(),
                android.R.attr.selectableItemBackgroundBorderless));
        infoButton.setContentDescription(getString(R.string.tip_tiers_info));
        infoButton.setOnClickListener(v -> showTipTiersInfo(details, xmrUsdPrice[0], fiat));
        tierRow.addView(infoButton, new LinearLayout.LayoutParams((int) (48 * density), (int) (48 * density)));
        tierScroll.setVisibility(tierRow.getChildCount() > 1 ? View.VISIBLE : View.GONE);
    }

    private void applyTierColor(@NonNull Button button, @Nullable String color) {
        if (TextUtils.isEmpty(color)) {
            return;
        }
        try {
            int parsedColor = Color.parseColor(color);
            button.setBackgroundColor(parsedColor);
            button.setTextColor(isDarkColor(parsedColor) ? Color.WHITE : Color.BLACK);
        } catch (IllegalArgumentException e) {
            Log.d(TAG, "Ignoring invalid XMRChat tier color: " + color);
        }
    }

    private boolean isDarkColor(int color) {
        double luminance = (0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color)) / 255;
        return luminance < 0.5;
    }

    private String getTierButtonText(@NonNull PageTipTier tier, @NonNull TipCurrency currency,
                                     @Nullable BigDecimal xmrUsdPrice, @NonNull FiatCurrency fiat) {
        if (tier.minAmount == null) {
            return "";
        }
        if (currency == TipCurrency.USD && xmrUsdPrice != null) {
            return fiat.symbol + formatFiatAmount(tier.minAmount.multiply(xmrUsdPrice));
        }
        return formatXmrAmount(tier.minAmount) + " XMR";
    }

    private String getTierButtonContentDescription(@NonNull PageTipTier tier, @NonNull TipCurrency currency,
                                                   @Nullable BigDecimal xmrUsdPrice, @NonNull FiatCurrency fiat) {
        String name = TextUtils.isEmpty(tier.name) ? getString(R.string.tip_tier_unnamed) : tier.name;
        return getString(R.string.tip_tier_select, name, getTierButtonText(tier, currency, xmrUsdPrice, fiat));
    }

    private void showTipTiersInfo(@NonNull XmrChatPageDetails details, @Nullable BigDecimal xmrUsdPrice,
                                  @NonNull FiatCurrency fiat) {
        ScrollView scrollView = new ScrollView(requireContext());
        LinearLayout layout = new LinearLayout(requireContext());
        layout.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (20 * getResources().getDisplayMetrics().density);
        layout.setPadding(padding, padding, padding, padding);
        scrollView.addView(layout, new ScrollView.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
        for (PageTipTier tier : details.pageTipTiers) {
            TextView tierText = new TextView(requireContext());
            tierText.setText(buildTierInfoText(tier, xmrUsdPrice, fiat));
            tierText.setTextIsSelectable(true);
            layout.addView(tierText, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
        }
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.tip_tiers_title)
                .setView(scrollView)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private String buildTierInfoText(@NonNull PageTipTier tier, @Nullable BigDecimal xmrUsdPrice,
                                     @NonNull FiatCurrency fiat) {
        StringBuilder builder = new StringBuilder();
        builder.append(TextUtils.isEmpty(tier.name) ? getString(R.string.tip_tier_unnamed) : tier.name);
        if (tier.minAmount != null) {
            builder.append('\n').append(getString(R.string.tip_tier_min_amount, formatXmrAmount(tier.minAmount)));
            if (xmrUsdPrice != null) {
                builder.append(" (").append(fiat.symbol)
                        .append(formatFiatAmount(tier.minAmount.multiply(xmrUsdPrice))).append(')');
            }
        }
        if (tier.messageLength != null) {
            builder.append('\n').append(getString(R.string.tip_tier_message_length, tier.messageLength));
        }
        if (!TextUtils.isEmpty(tier.description)) {
            builder.append('\n').append(tier.description);
        }
        builder.append("\n\n");
        return builder.toString();
    }

    private void updateTipMessageLimit(@NonNull EditText amountInput,
                                       @NonNull EditText messageInput,
                                       @NonNull TextView messageCounter,
                                       @Nullable XmrChatPageDetails pageDetails,
                                       @NonNull TipCurrency currency,
                                       @Nullable BigDecimal xmrUsdPrice,
                                       @NonNull int[] messageMaxLength) {
        BigDecimal xmrAmount = getEnteredXmrAmount(amountInput, currency, xmrUsdPrice);
        int nextMaxLength = getTipMessageLength(xmrAmount,
                pageDetails == null ? null : pageDetails.pageTipTiers);
        if (messageMaxLength[0] != nextMaxLength) {
            messageMaxLength[0] = nextMaxLength;
            messageInput.setFilters(new InputFilter[] {new InputFilter.LengthFilter(nextMaxLength)});
            Editable message = messageInput.getText();
            if (message.length() > nextMaxLength) {
                message.delete(nextMaxLength, message.length());
            }
        }
        updateTipMessageCounter(messageInput, messageCounter, nextMaxLength);
    }

    private void updateMinimumLabel(@NonNull TextView minimumLabel, @Nullable XmrChatPageDetails pageDetails,
                                    @NonNull TipCurrency currency, @Nullable BigDecimal xmrUsdPrice,
                                    @NonNull FiatCurrency fiat) {
        if (pageDetails == null || pageDetails.minTipAmount == null
                || pageDetails.minTipAmount.compareTo(BigDecimal.ZERO) <= 0) {
            minimumLabel.setVisibility(View.GONE);
            return;
        }
        minimumLabel.setText(getString(R.string.tip_minimum_label,
                formatMinimumForDisplay(pageDetails.minTipAmount, currency, xmrUsdPrice, fiat)));
        minimumLabel.setVisibility(View.VISIBLE);
    }

    private String formatMinimumForDisplay(@NonNull BigDecimal minimum, @NonNull TipCurrency currency,
                                           @Nullable BigDecimal xmrUsdPrice, @NonNull FiatCurrency fiat) {
        if (currency == TipCurrency.USD && xmrUsdPrice != null) {
            return fiat.symbol + formatFiatAmount(minimum.multiply(xmrUsdPrice));
        }
        // XMR mode, or fiat selected but price not loaded yet.
        return formatXmrAmount(minimum) + " XMR";
    }

    @Nullable
    private BigDecimal getEnteredXmrAmount(@NonNull EditText amountInput, @NonNull TipCurrency currency,
                                           @Nullable BigDecimal xmrUsdPrice) {
        try {
            BigDecimal amount = parsePositiveDecimal(amountInput.getText().toString().trim());
            if (currency == TipCurrency.XMR) {
                return amount;
            }
            if (xmrUsdPrice == null) {
                return null;
            }
            return amount.divide(xmrUsdPrice, XMR_AMOUNT_SCALE, RoundingMode.HALF_UP);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void updateTipMessageCounter(@NonNull EditText messageInput, @NonNull TextView messageCounter,
                                         int maxLength) {
        messageCounter.setText(getString(R.string.tip_message_counter, messageInput.length(), maxLength));
    }

    private void updateTipMessageError(@NonNull EditText messageInput, @NonNull TextView messageError) {
        String message = messageInput.getText().toString().trim();
        messageError.setVisibility(isValidOptionalTipMessage(message) ? View.GONE : View.VISIBLE);
    }

    private boolean isValidOptionalTipMessage(@NonNull String message) {
        return message.isEmpty() || message.length() >= 3;
    }

    static int getTipMessageLength(@Nullable BigDecimal xmrAmount, @Nullable List<PageTipTier> tiers) {
        if (tiers == null || tiers.isEmpty() || xmrAmount == null) {
            return getDefaultTipMessageLength(tiers);
        }
        PageTipTier activeTier = null;
        for (PageTipTier tier : tiers) {
            BigDecimal minAmount = tier.minAmount == null ? BigDecimal.ZERO : tier.minAmount;
            BigDecimal activeMinAmount = activeTier == null || activeTier.minAmount == null
                    ? BigDecimal.ZERO : activeTier.minAmount;
            if (xmrAmount.compareTo(minAmount) >= 0
                    && (activeTier == null || minAmount.compareTo(activeMinAmount) > 0)) {
                activeTier = tier;
            }
        }
        if (activeTier != null && activeTier.messageLength != null && activeTier.messageLength > 0) {
            return Math.min(activeTier.messageLength, TIP_MESSAGE_MAX_SERVER_LENGTH);
        }
        return getDefaultTipMessageLength(tiers);
    }

    static boolean isBelowMinimum(@Nullable BigDecimal xmrAmount, @Nullable BigDecimal minTipAmount) {
        if (xmrAmount == null || minTipAmount == null || minTipAmount.compareTo(BigDecimal.ZERO) <= 0) {
            return false;
        }
        return xmrAmount.compareTo(minTipAmount) < 0;
    }

    private static int getDefaultTipMessageLength(@Nullable List<PageTipTier> tiers) {
        int defaultLength = TIP_MESSAGE_DEFAULT_MAX_LENGTH;
        if (tiers != null) {
            for (PageTipTier tier : tiers) {
                if (tier.messageLength != null && tier.messageLength > 0) {
                    defaultLength = Math.min(defaultLength, tier.messageLength);
                }
            }
        }
        return Math.min(defaultLength, TIP_MESSAGE_DEFAULT_MAX_LENGTH);
    }

    private String limitTipMessage(@NonNull String message, int maxLength) {
        if (message.length() <= maxLength) {
            return message;
        }
        return message.substring(0, maxLength);
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

    private void fetchXmrUsdPrice(@NonNull BigDecimal[] xmrUsdPrice, @NonNull FiatCurrency fiat,
                                  @NonNull EditText amountInput, @Nullable TextView amountPreview,
                                  @NonNull RadioGroup currencyGroup,
                                  @NonNull RadioButton usdButton,
                                  @Nullable XmrChatPageDetails[] pageDetails,
                                  @Nullable HorizontalScrollView tierScroll,
                                  @Nullable LinearLayout tierRow,
                                  @Nullable EditText messageInput,
                                  @Nullable TextView messageCounter,
                                  @Nullable TextView minimumLabel,
                                  @Nullable int[] messageMaxLength) {
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
                emitter.onSuccess(parseXmrUsdPrice(responseBody, fiat));
            }
        }).subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(price -> {
                    xmrUsdPrice[0] = price;
                    if (amountPreview != null) {
                        updateTipAmountPreview(amountInput, amountPreview,
                                getSelectedTipCurrency(currencyGroup, usdButton), price, fiat);
                    }
                    if (pageDetails != null && pageDetails[0] != null && tierScroll != null && tierRow != null) {
                        updateTipTierControls(pageDetails[0], tierScroll, tierRow, amountInput, currencyGroup,
                                usdButton, xmrUsdPrice, fiat);
                    }
                    if (pageDetails != null && messageInput != null && messageCounter != null
                            && messageMaxLength != null) {
                        updateTipMessageLimit(amountInput, messageInput, messageCounter, pageDetails[0],
                                getSelectedTipCurrency(currencyGroup, usdButton), price, messageMaxLength);
                    }
                    if (pageDetails != null && minimumLabel != null) {
                        updateMinimumLabel(minimumLabel, pageDetails[0],
                                getSelectedTipCurrency(currencyGroup, usdButton), price, fiat);
                    }
                }, error -> {
                    Log.e(TAG, Log.getStackTraceString(error));
                    if (amountPreview != null) {
                        updateTipAmountPreview(amountInput, amountPreview,
                                getSelectedTipCurrency(currencyGroup, usdButton), null, fiat);
                    }
                });
    }

    private String getTipAmountInXmr(@NonNull String amount, @NonNull TipCurrency currency,
                                     @Nullable BigDecimal cachedXmrUsdPrice, @NonNull FiatCurrency fiat)
            throws IOException, JSONException {
        BigDecimal amountValue = parsePositiveDecimal(amount);
        if (currency == TipCurrency.XMR) {
            return formatXmrAmount(amountValue);
        }
        BigDecimal price = cachedXmrUsdPrice == null ? fetchXmrUsdPrice(fiat) : cachedXmrUsdPrice;
        return formatXmrAmount(amountValue.divide(price, XMR_AMOUNT_SCALE, RoundingMode.HALF_UP));
    }

    private BigDecimal fetchXmrUsdPrice(@NonNull FiatCurrency fiat) throws IOException, JSONException {
        Request request = new Request.Builder().url(XMR_PRICE_URL).get().build();
        try (Response response = AntennapodHttpClient.getHttpClient().newCall(request).execute()) {
            ResponseBody body = response.body();
            String responseBody = body == null ? "" : body.string();
            if (!response.isSuccessful()) {
                throw new IOException("XMR price request failed: " + response.code());
            }
            return parseXmrUsdPrice(responseBody, fiat);
        }
    }

    private BigDecimal parseXmrUsdPrice(@NonNull String responseBody, @NonNull FiatCurrency fiat)
            throws JSONException, IOException {
        String trimmedBody = responseBody.trim();
        BigDecimal price;
        if (trimmedBody.startsWith("{")) {
            JSONObject response = new JSONObject(trimmedBody);
            JSONObject xmr = response.optJSONObject("xmr");
            JSONObject prices = xmr == null ? response : xmr;
            price = new BigDecimal(prices.optString(fiat.code, prices.optString("price")));
        } else {
            price = new BigDecimal(trimmedBody);
        }
        if (price.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IOException("XMR price must be positive");
        }
        return price;
    }

    private void updateTipAmountPreview(@NonNull EditText amountInput, @NonNull TextView amountPreview,
                                        @NonNull TipCurrency currency, @Nullable BigDecimal xmrUsdPrice,
                                        @NonNull FiatCurrency fiat) {
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
                String fiatAmount = formatFiatAmount(amountValue.multiply(xmrUsdPrice));
                amountPreview.setText(getString(R.string.tip_amount_xmr_conversion_preview, amount, fiat.symbol,
                        fiatAmount));
            } else {
                String xmrAmount = formatXmrAmount(amountValue.divide(xmrUsdPrice, XMR_AMOUNT_SCALE,
                        RoundingMode.HALF_UP));
                amountPreview.setText(getString(R.string.tip_amount_conversion_preview, fiat.symbol, amount,
                        xmrAmount));
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

    private String formatFiatAmount(@NonNull BigDecimal amount) {
        return amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private TipCurrency getSelectedTipCurrency(@NonNull RadioGroup currencyGroup, @NonNull RadioButton usdButton) {
        return currencyGroup.getCheckedRadioButtonId() == usdButton.getId() ? TipCurrency.USD : TipCurrency.XMR;
    }

    private int getXmrCurrencyButtonId(@NonNull RadioGroup currencyGroup, @NonNull RadioButton usdButton) {
        for (int i = 0; i < currencyGroup.getChildCount(); i++) {
            View child = currencyGroup.getChildAt(i);
            if (child instanceof RadioButton && child.getId() != usdButton.getId()) {
                return child.getId();
            }
        }
        return usdButton.getId();
    }

    private void openMoneroUri(@NonNull String moneroUri) {
        if (openWalletUri(moneroUri)) {
            return;
        }
        Log.w(TAG, "No wallet accepted Monero URI=" + moneroUri);
        Toast.makeText(getContext(), R.string.tip_no_wallet, Toast.LENGTH_LONG).show();
    }

    private boolean openWalletUri(@Nullable String walletUri) {
        if (TextUtils.isEmpty(walletUri)) {
            return false;
        }
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(walletUri));
            intent.addCategory(Intent.CATEGORY_BROWSABLE);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            return true;
        } catch (ActivityNotFoundException e) {
            return false;
        }
    }

    @Nullable
    private String buildCakeWalletMoneroUri(@NonNull String scheme, @NonNull String moneroUri) {
        Uri uri = Uri.parse(moneroUri);
        if (!MONERO_SCHEME.equalsIgnoreCase(uri.getScheme())) {
            return null;
        }
        String schemeSpecificPart = uri.getEncodedSchemeSpecificPart();
        if (TextUtils.isEmpty(schemeSpecificPart)) {
            return null;
        }
        int addressEnd = schemeSpecificPart.length();
        int queryStart = schemeSpecificPart.indexOf('?');
        int fragmentStart = schemeSpecificPart.indexOf('#');
        if (queryStart >= 0) {
            addressEnd = queryStart;
        }
        if (fragmentStart >= 0 && fragmentStart < addressEnd) {
            addressEnd = fragmentStart;
        }
        String address = schemeSpecificPart.substring(0, addressEnd);
        if (TextUtils.isEmpty(address)) {
            return null;
        }
        String query = null;
        if (queryStart >= 0) {
            int queryEnd = fragmentStart >= 0 && fragmentStart > queryStart
                    ? fragmentStart : schemeSpecificPart.length();
            query = schemeSpecificPart.substring(queryStart + 1, queryEnd);
        }
        String walletUri = scheme + ":" + MONERO_SCHEME + "?address=" + address
                + (TextUtils.isEmpty(query) ? "" : "&" + query);
        return walletUri;
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
        if (tipDisposable != null) {
            tipDisposable.dispose();
        }
        if (xmrPriceDisposable != null) {
            xmrPriceDisposable.dispose();
        }
        if (xmrChatPageDisposable != null) {
            xmrChatPageDisposable.dispose();
        }
        viewBinding = null;
    }

    private enum TipCurrency {
        USD,
        XMR
    }

    private enum FiatCurrency {
        USD("usd", "USD", "$"),
        EUR("eur", "EUR", "€"),
        MXN("mxn", "MXN", "MXN$");

        private final String code;
        private final String label;
        private final String symbol;

        FiatCurrency(@NonNull String code, @NonNull String label, @NonNull String symbol) {
            this.code = code;
            this.label = label;
            this.symbol = symbol;
        }

        private static FiatCurrency fromCode(@Nullable String code) {
            for (FiatCurrency currency : values()) {
                if (currency.code.equalsIgnoreCase(code)) {
                    return currency;
                }
            }
            return USD;
        }
    }

    private static class TipTarget {
        private final String moneroUri;
        private final String fallbackUrl;
        private final String xmrChatApiUrl;
        private final String xmrChatPageApiUrl;
        private final String xmrChatPath;

        private TipTarget(@Nullable String moneroUri, @Nullable String fallbackUrl,
                          @Nullable String xmrChatApiUrl, @Nullable String xmrChatPageApiUrl,
                          @Nullable String xmrChatPath) {
            this.moneroUri = moneroUri;
            this.fallbackUrl = fallbackUrl;
            this.xmrChatApiUrl = xmrChatApiUrl;
            this.xmrChatPageApiUrl = xmrChatPageApiUrl;
            this.xmrChatPath = xmrChatPath;
        }

        private static TipTarget forMoneroUri(@NonNull String moneroUri) {
            return new TipTarget(moneroUri, null, null, null, null);
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
                return new TipTarget(null, normalizedUrl, null, null, null);
            }

            String apiUrl;
            String pageApiUrl;
            String lowerHost = host.toLowerCase(Locale.US);
            if ("xmrchat.com".equals(lowerHost) || "www.xmrchat.com".equals(lowerHost)) {
                apiUrl = "https://nest.xmrchat.com/tips";
                pageApiUrl = "https://nest.xmrchat.com/pages/" + Uri.encode(path);
            } else {
                String scheme = TextUtils.isEmpty(uri.getScheme()) ? "https" : uri.getScheme();
                apiUrl = scheme + "://" + uri.getEncodedAuthority() + "/tips";
                pageApiUrl = scheme + "://" + uri.getEncodedAuthority() + "/pages/" + Uri.encode(path);
            }
            return new TipTarget(null, normalizedUrl, apiUrl, pageApiUrl, path);
        }
    }

    static class XmrChatPageDetails {
        private final List<PageTipTier> pageTipTiers;
        private final FiatCurrency fiat;
        private final BigDecimal minTipAmount;

        XmrChatPageDetails(@NonNull List<PageTipTier> pageTipTiers, @NonNull FiatCurrency fiat) {
            this(pageTipTiers, fiat, null);
        }

        XmrChatPageDetails(@NonNull List<PageTipTier> pageTipTiers, @NonNull FiatCurrency fiat,
                           @Nullable BigDecimal minTipAmount) {
            this.pageTipTiers = pageTipTiers;
            this.fiat = fiat;
            this.minTipAmount = minTipAmount;
        }
    }

    static class PageTipTier {
        private final String name;
        private final String description;
        private final BigDecimal minAmount;
        private final Integer messageLength;
        private final String color;

        PageTipTier(@Nullable String name, @Nullable String description, @Nullable BigDecimal minAmount,
                    @Nullable Integer messageLength, @Nullable String color) {
            this.name = name;
            this.description = description;
            this.minAmount = minAmount;
            this.messageLength = messageLength;
            this.color = color;
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onPlayerStatusEvent(PlayerStatusEvent event) {
        loadMediaInfo(false);
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onXmrChatDirectoryUpdateEvent(XmrChatDirectoryUpdateEvent event) {
        updateTipButton(media);
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
