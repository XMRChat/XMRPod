package de.danoeh.antennapod.storage.preferences;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import static org.junit.Assert.assertEquals;

@RunWith(RobolectricTestRunner.class)
public class UserPreferencesTest {
    private SharedPreferences prefs;

    @Before
    public void setUp() {
        Context context = RuntimeEnvironment.getApplication();
        prefs = PreferenceManager.getDefaultSharedPreferences(context);
        prefs.edit().clear().commit();
        UserPreferences.init(context);
    }

    @Test
    public void getUpdateIntervalRepairsRepeatedMigrationValue() {
        prefs.edit().putString(UserPreferences.PREF_UPDATE_INTERVAL_MINUTES, "9331200000").commit();

        assertEquals(720, UserPreferences.getUpdateInterval());
        assertEquals("720", prefs.getString(UserPreferences.PREF_UPDATE_INTERVAL_MINUTES, null));
    }

    @Test
    public void getUpdateIntervalKeepsValidValue() {
        prefs.edit().putString(UserPreferences.PREF_UPDATE_INTERVAL_MINUTES, "4320").commit();

        assertEquals(4320, UserPreferences.getUpdateInterval());
        assertEquals("4320", prefs.getString(UserPreferences.PREF_UPDATE_INTERVAL_MINUTES, null));
    }

    @Test
    public void getUpdateIntervalFallsBackForInvalidValue() {
        prefs.edit().putString(UserPreferences.PREF_UPDATE_INTERVAL_MINUTES, "invalid").commit();

        assertEquals(720, UserPreferences.getUpdateInterval());
        assertEquals("720", prefs.getString(UserPreferences.PREF_UPDATE_INTERVAL_MINUTES, null));
    }
}
