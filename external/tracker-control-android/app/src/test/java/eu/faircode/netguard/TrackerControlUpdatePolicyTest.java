package eu.faircode.netguard;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class TrackerControlUpdatePolicyTest {
    @Test
    public void sameOrOlderUpstreamReleaseDoesNotNotify() {
        assertFalse(TrackerControlUpdatePolicy.isUpdateAvailable(2026110101L, 2026110101L));
        assertFalse(TrackerControlUpdatePolicy.isUpdateAvailable(2026110101L, 2026102901L));
    }

    @Test
    public void newerUpstreamReleaseNotifies() {
        assertTrue(TrackerControlUpdatePolicy.isUpdateAvailable(2026110101L, 2026111501L));
    }

    @Test
    public void hostAppVersionDoesNotParticipateInComparison() {
        // The Vian World host version is intentionally independent of the
        // TrackerControl release version stored in its library BuildConfig.
        assertFalse(TrackerControlUpdatePolicy.isUpdateAvailable(
                BuildConfig.TRACKERCONTROL_VERSION_CODE, 2026110101L));
        assertTrue(TrackerControlUpdatePolicy.isUpdateAvailable(
                BuildConfig.TRACKERCONTROL_VERSION_CODE, 2026111501L));
    }
}
