package de.danoeh.antennapod.ui.preferences.screen;

import android.os.Bundle;
import android.Manifest;
import android.content.Intent;
import android.content.ActivityNotFoundException;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.TextView;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.app.NotificationChannelCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;
import androidx.preference.SwitchPreferenceCompat;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import de.danoeh.antennapod.net.push.LiveNotifications;
import de.danoeh.antennapod.ui.notifications.NotificationUtils;
import androidx.appcompat.app.AppCompatActivity;
import de.danoeh.antennapod.storage.preferences.SynchronizationSettings;
import de.danoeh.antennapod.ui.preferences.R;
import java.text.DateFormat;
import java.util.Date;
import java.util.List;

public class NotificationPreferencesFragment extends AnimatedPreferenceFragment
        implements SharedPreferences.OnSharedPreferenceChangeListener {

    public static final String EXTRA_START_PUSH_SETUP = "StartLivePushSetup";
    private static final String PREF_GPODNET_NOTIFICATIONS = "pref_gpodnet_notifications";
    private LiveNotifications notifications;
    private final ActivityResultLauncher<String> notificationPermission = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) {
                    enablePush();
                } else {
                    refreshPushStatus();
                }
            });

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        addPreferencesFromResource(R.xml.preferences_notifications);
        notifications = LiveNotifications.get(requireContext());
        setUpScreen();
    }

    @Override
    public void onStart() {
        super.onStart();
        ((AppCompatActivity) getActivity()).getSupportActionBar().setTitle(R.string.notification_pref_fragment);
        notifications.getPreferences().registerOnSharedPreferenceChangeListener(this);
        notifications.enqueue("sync", null);
        refreshPushStatus();
        Intent intent = requireActivity().getIntent();
        if (intent.getBooleanExtra(EXTRA_START_PUSH_SETUP, false)) {
            intent.removeExtra(EXTRA_START_PUSH_SETUP);
            enablePush();
        }
    }

    @Override
    public void onStop() {
        notifications.getPreferences().unregisterOnSharedPreferenceChangeListener(this);
        super.onStop();
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshPushStatus();
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences preferences, String key) {
        if (isAdded()) {
            requireActivity().runOnUiThread(this::refreshPushStatus);
        }
    }

    private void setUpScreen() {
        findPreference(PREF_GPODNET_NOTIFICATIONS).setEnabled(SynchronizationSettings.isProviderConnected());
        findPreference("live_push_enabled").setOnPreferenceChangeListener((preference, value) -> {
            if ((Boolean) value) {
                enablePush();
            } else {
                notifications.setEnabled(false);
                refreshPushStatus();
            }
            return false;
        });
        findPreference("live_push_provider").setOnPreferenceClickListener(preference -> {
            chooseProvider();
            return true;
        });
        findPreference("live_push_retry").setOnPreferenceClickListener(preference -> {
            enablePush();
            return true;
        });
        findPreference("live_push_test").setOnPreferenceClickListener(preference -> {
            notifications.enqueue("test", null);
            return true;
        });
        findPreference("live_push_android_settings").setOnPreferenceClickListener(preference -> {
            Intent intent;
            if (Build.VERSION.SDK_INT >= 26) {
                intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, requireContext().getPackageName());
            } else {
                intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + requireContext().getPackageName()));
            }
            try {
                startActivity(intent);
            } catch (ActivityNotFoundException e) {
                return false;
            }
            return true;
        });
        findPreference("live_push_help").setOnPreferenceClickListener(preference -> {
            showInstallHelp();
            return true;
        });
    }

    private void enablePush() {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(requireContext(),
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS);
            return;
        }
        notifications.setEnabled(true);
        notifications.useCurrentOrDefaultProvider(requireActivity(), () -> {
            if (isAdded()) {
                chooseProvider();
            }
        });
        refreshPushStatus();
    }

    private void chooseProvider() {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(requireContext(),
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS);
            return;
        }
        List<String> providers = notifications.getProviders();
        if (providers.isEmpty()) {
            showInstallHelp();
            return;
        }
        if (providers.size() == 1) {
            notifications.selectProvider(providers.get(0));
            refreshPushStatus();
            return;
        }
        String[] names = new String[providers.size()];
        for (int i = 0; i < providers.size(); i++) {
            names[i] = notifications.providerName(providers.get(i));
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(requireContext(),
                android.R.layout.select_dialog_item, names) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                TextView view = (TextView) super.getView(position, convertView, parent);
                try {
                    Drawable icon = requireContext().getPackageManager().getApplicationIcon(providers.get(position));
                    int size = (int) (32 * getResources().getDisplayMetrics().density);
                    icon.setBounds(0, 0, size, size);
                    view.setCompoundDrawables(icon, null, null, null);
                    view.setCompoundDrawablePadding(size / 2);
                } catch (PackageManager.NameNotFoundException e) {
                    view.setCompoundDrawables(null, null, null, null);
                }
                return view;
            }
        };
        new MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.live_push_choose_provider)
                .setAdapter(adapter, (dialog, which) -> notifications.selectProvider(providers.get(which)))
                .setNegativeButton(android.R.string.cancel, null).show();
    }

    private void showInstallHelp() {
        new MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.live_push_choose_provider)
                .setMessage(R.string.live_push_install_help)
                .setPositiveButton(R.string.live_push_install, (dialog, which) -> startActivity(new Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("https://f-droid.org/packages/org.unifiedpush.distributor.sunup/"))))
                .setNeutralButton(R.string.live_push_setup_guide, (dialog, which) -> startActivity(new Intent(
                        Intent.ACTION_VIEW, Uri.parse("https://unifiedpush.org/users/distributors/sunup/"))))
                .setNegativeButton(android.R.string.cancel, null).show();
    }

    private void refreshPushStatus() {
        if (!isAdded() || notifications == null) {
            return;
        }
        ((SwitchPreferenceCompat) findPreference("live_push_enabled")).setChecked(notifications.isEnabled());
        String provider = notifications.getProvider();
        List<String> providers = notifications.getProviders();
        String displayProvider = providers.contains(provider) ? provider
                : providers.size() == 1 ? providers.get(0) : "";
        findPreference("live_push_provider").setSummary(!displayProvider.isEmpty()
                ? notifications.providerName(displayProvider)
                : getString(providers.isEmpty() ? R.string.live_push_no_provider : R.string.live_push_choose_provider));
        try {
            findPreference("live_push_provider").setIcon(
                    requireContext().getPackageManager().getApplicationIcon(displayProvider));
        } catch (PackageManager.NameNotFoundException e) {
            findPreference("live_push_provider").setIcon(null);
        }
        SharedPreferences preferences = notifications.getPreferences();
        String status = preferences.getString("status", "off");
        long tested = preferences.getLong("tested_at", 0);
        int message = switch (status) {
            case "registering" -> R.string.live_push_status_registering;
            case "registered" -> R.string.live_push_status_registered;
            case "testing" -> R.string.live_push_status_testing;
            case "action_required" -> R.string.live_push_status_action_required;
            case "no_provider", "unavailable" -> R.string.live_push_status_fallback;
            default -> R.string.live_push_status_off;
        };
        String summary = tested > 0 ? getString(R.string.live_push_status_tested,
                DateFormat.getDateTimeInstance().format(new Date(tested))) : getString(message);
        NotificationManagerCompat manager = NotificationManagerCompat.from(requireContext());
        NotificationChannelCompat channel = manager.getNotificationChannelCompat(
                NotificationUtils.CHANNEL_ID_LIVE_NOTIFICATIONS);
        if (!manager.areNotificationsEnabled()
                || (channel != null && channel.getImportance() == NotificationManagerCompat.IMPORTANCE_NONE)) {
            summary = getString(R.string.live_push_status_blocked);
        }
        findPreference("live_push_status").setSummary(summary);
        findPreference("live_push_test").setEnabled(notifications.isEnabled() && preferences.contains("endpoint"));
    }
}
