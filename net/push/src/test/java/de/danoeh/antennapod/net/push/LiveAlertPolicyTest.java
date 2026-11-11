package de.danoeh.antennapod.net.push;

import org.junit.Test;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LiveAlertPolicyTest {
    @Test
    public void bellsDefaultOffAndExistingStreamsStaySilent() {
        assertFalse(LiveAlertPolicy.shouldNotify(true, 0, 2000, 3000, "new", "old"));
        assertFalse(LiveAlertPolicy.shouldNotify(true, 2000, 1000, 3000, "new", "old"));
        assertFalse(LiveAlertPolicy.shouldNotify(false, 1000, 2000, 3000, "new", "old"));
    }

    @Test
    public void repeatedSessionIsSilentButNextBroadcastAlerts() {
        assertFalse(LiveAlertPolicy.shouldNotify(true, 1000, 2000, 3000, "same", "same"));
        assertTrue(LiveAlertPolicy.shouldNotify(true, 1000, 2000, 3000, "next", "same"));
    }

    @Test
    public void invalidAndStaleEventsStaySilent() {
        assertFalse(LiveAlertPolicy.shouldNotify(true, 1000, 2000, 3000, "", "old"));
        assertFalse(LiveAlertPolicy.shouldNotify(true, 1000, 2000, 100000000, "new", "old"));
        assertFalse(LiveAlertPolicy.shouldNotify(true, 1000, 100000, 3000, "new", "old"));
    }
}
