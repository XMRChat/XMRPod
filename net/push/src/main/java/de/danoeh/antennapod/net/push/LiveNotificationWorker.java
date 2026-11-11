package de.danoeh.antennapod.net.push;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import java.io.IOException;
import org.json.JSONException;

public class LiveNotificationWorker extends Worker {
    public LiveNotificationWorker(@NonNull Context context, @NonNull WorkerParameters parameters) {
        super(context, parameters);
    }

    @NonNull
    @Override
    public Result doWork() {
        LiveNotifications notifications = LiveNotifications.get(getApplicationContext());
        try {
            String mode = getInputData().getString("mode");
            if ("message".equals(mode)) {
                if (notifications.accepts(getInputData().getString("instance"))) {
                    notifications.receive(getInputData().getString("payload"), getInputData().getString("instance"));
                }
            } else if ("poll".equals(mode)) {
                notifications.poll();
            } else if ("test".equals(mode)) {
                notifications.sendTest();
            } else if ("schedule".equals(mode)) {
                notifications.updateSchedule();
            } else if ("test_timeout".equals(mode)) {
                notifications.checkTestTimeout();
            } else {
                notifications.synchronize();
            }
            notifications.updateSchedule();
            return Result.success();
        } catch (IOException | JSONException e) {
            notifications.unavailable();
            return getRunAttemptCount() < 4 ? Result.retry() : Result.failure();
        }
    }
}
