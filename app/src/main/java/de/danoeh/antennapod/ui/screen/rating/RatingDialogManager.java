package de.danoeh.antennapod.ui.screen.rating;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.fragment.app.FragmentActivity;

public class RatingDialogManager {
    private static final String PREFS_NAME = "RatingPrefs";
    private static final String KEY_RATED = "KEY_WAS_RATED";
    private static final String KEY_FIRST_START_DATE = "KEY_FIRST_HIT_DATE";

    private final SharedPreferences preferences;

    public RatingDialogManager(FragmentActivity activity) {
        preferences = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public void saveRated() {
        preferences.edit().putBoolean(KEY_RATED, true).apply();
    }

    public void resetStartDate() {
        preferences.edit().putLong(KEY_FIRST_START_DATE, System.currentTimeMillis()).apply();
    }

}
