package de.danoeh.antennapod.net.push;

public final class LiveAlertPolicy {
    private LiveAlertPolicy() {
    }

    public static boolean shouldNotify(boolean subscribed, long enabledSince, long startedAt,
                                       long now, String sessionId, String lastSessionId) {
        return subscribed && enabledSince > 0 && startedAt > enabledSince && startedAt <= now + 60_000
                && now - startedAt <= 24 * 60 * 60_000L
                && sessionId != null && !sessionId.isEmpty() && !sessionId.equals(lastSessionId);
    }
}
