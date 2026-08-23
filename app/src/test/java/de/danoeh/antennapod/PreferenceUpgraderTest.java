package de.danoeh.antennapod;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;

import de.danoeh.antennapod.storage.preferences.UserPreferences;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class)
public class PreferenceUpgraderTest {
    private static final String PREF_CONFIGURED_VERSION = "version_code";
    private static final String PREF_NAME = "app_version";

    private Context context;
    private SharedPreferences prefs;
    private SharedPreferences upgraderPrefs;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        prefs = PreferenceManager.getDefaultSharedPreferences(context);
        upgraderPrefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        prefs.edit().clear().commit();
        upgraderPrefs.edit().clear().commit();
        UserPreferences.init(context);
    }

    @Test
    public void checkUpgradesDoesNotRunAntennaPodMigrationsForXmrPodVersionCodes() {
        upgraderPrefs.edit().putInt(PREF_CONFIGURED_VERSION, 16).commit();
        prefs.edit().putString(UserPreferences.PREF_UPDATE_INTERVAL_MINUTES, "720").commit();

        PreferenceUpgrader.checkUpgrades(context);

        assertEquals("720", prefs.getString(UserPreferences.PREF_UPDATE_INTERVAL_MINUTES, null));
        assertEquals(BuildConfig.VERSION_CODE, upgraderPrefs.getInt(PREF_CONFIGURED_VERSION, -1));
    }
}
