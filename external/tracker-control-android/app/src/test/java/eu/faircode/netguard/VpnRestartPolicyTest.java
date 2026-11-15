package eu.faircode.netguard;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class VpnRestartPolicyTest {
    @Test
    public void aServiceKilledWhileProtectingIsRestarted() {
        assertTrue(VpnRestartPolicy.shouldRestart(true, false, false));
    }

    @Test
    public void aRunningTunnelIsLeftAlone() {
        assertFalse(VpnRestartPolicy.shouldRestart(true, false, true));
    }

    @Test
    public void protectionTheUserTurnedOffStaysOff() {
        assertFalse(VpnRestartPolicy.shouldRestart(false, true, false));
    }

    @Test
    public void aNewEnabledStartAttemptClearsThePreviousCleanStopMarker() {
        assertTrue(VpnRestartPolicy.shouldClearCleanStopForStartAttempt(true, true));
        assertFalse(VpnRestartPolicy.shouldClearCleanStopForStartAttempt(false, true));
        assertFalse(VpnRestartPolicy.shouldClearCleanStopForStartAttempt(true, false));

        // If establish() fails after the new attempt clears the old marker,
        // the periodic recovery policy must see an unexpected missing tunnel.
        assertTrue(VpnRestartPolicy.shouldRestart(true, false, false));
    }

    @Test
    public void aDeliberateTeardownIsNotRestarted() {
        // A revoke and a stop for a phone call both land here: the service saw
        // them coming and said so, unlike a kill.
        assertFalse(VpnRestartPolicy.shouldRestart(true, true, false));
    }
}
