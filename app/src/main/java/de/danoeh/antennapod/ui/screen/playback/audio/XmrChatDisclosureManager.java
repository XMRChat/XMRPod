package de.danoeh.antennapod.ui.screen.playback.audio;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.fragment.app.FragmentActivity;

public class XmrChatDisclosureManager {
    private static final String PREFS_NAME = "XmrChatPrefs";
    private static final String KEY_DISCLOSED = "disclosure_shown";

    private final SharedPreferences preferences;
    private final FragmentActivity fragmentActivity;

    public XmrChatDisclosureManager(FragmentActivity activity) {
        this.fragmentActivity = activity;
        preferences = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public void showIfNeeded() {
        if (preferences.getBoolean(KEY_DISCLOSED, false)) {
            return;
        }
        preferences.edit().putBoolean(KEY_DISCLOSED, true).apply();
        new XmrChatDisclosureDialogFragment()
                .show(fragmentActivity.getSupportFragmentManager(), XmrChatDisclosureDialogFragment.TAG);
    }
}
