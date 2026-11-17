package net.kollnig.missioncontrol.data;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.preference.PreferenceManager;

import eu.faircode.netguard.ShieldControl;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

@RunWith(RobolectricTestRunner.class)
public class InternetPauseManagerTest {
    private Context context;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences(InternetPauseManager.PREFS_NAME, Context.MODE_PRIVATE)
                .edit().clear().commit();
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit();
        InternetBlocklist.getInstance(context).clear();
    }

    @Test
    public void requestedPauseIsNotReportedAsAppliedUntilServiceAcknowledges() {
        context.getSharedPreferences(InternetPauseManager.PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(InternetPauseManager.KEY_ACTIVE, true)
                .putBoolean(InternetPauseManager.KEY_APPLIED, false).commit();

        assertTrue(InternetPauseManager.isActive(context));
        assertFalse(InternetPauseManager.isApplied(context));
        assertTrue(InternetPauseManager.isRecovering(context));
    }

    @Test
    public void vpnReadinessComesFromDefaultProcessProvider() {
        assertFalse(InternetPauseManager.isVpnReady(context));
    }

    @Test
    public void pauseOffRestoresFirewallToDisabledWhenPauseStartedIt() {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
                .putBoolean("enabled", true).commit();
        context.getSharedPreferences(InternetPauseManager.PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(InternetPauseManager.KEY_ACTIVE, true)
                .putBoolean(InternetPauseManager.KEY_APPLIED, true)
                .putBoolean(InternetPauseManager.KEY_PREPARED, true)
                .putBoolean(InternetPauseManager.KEY_PREVIOUS_FIREWALL_ENABLED, false)
                .commit();

        assertTrue(InternetPauseManager.setPaused(context, false));

        assertFalse(PreferenceManager.getDefaultSharedPreferences(context).getBoolean("enabled", true));
        assertFalse(context.getSharedPreferences(InternetPauseManager.PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(InternetPauseManager.KEY_PREPARED, false));
    }

    @Test
    public void pauseOffKeepsFirewallEnabledWhenItWasAlreadyEnabled() {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
                .putBoolean("enabled", true).commit();
        context.getSharedPreferences(InternetPauseManager.PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(InternetPauseManager.KEY_ACTIVE, true)
                .putBoolean(InternetPauseManager.KEY_APPLIED, true)
                .putBoolean(InternetPauseManager.KEY_PREPARED, true)
                .putBoolean(InternetPauseManager.KEY_PREVIOUS_FIREWALL_ENABLED, true)
                .commit();

        assertTrue(InternetPauseManager.setPaused(context, false));

        assertTrue(PreferenceManager.getDefaultSharedPreferences(context).getBoolean("enabled", false));
        assertFalse(context.getSharedPreferences(InternetPauseManager.PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(InternetPauseManager.KEY_PREPARED, false));
    }

    @Test
    public void firewallCannotBeDisabledWhilePauseIsActive() {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
                .putBoolean("enabled", true).commit();
        context.getSharedPreferences(InternetPauseManager.PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(InternetPauseManager.KEY_ACTIVE, true).commit();

        ShieldControl.setEnabled(context, false, "test");

        assertTrue(PreferenceManager.getDefaultSharedPreferences(context).getBoolean("enabled", false));
    }

    @Test
    public void pausePreparationRestoreDecisionOnlyStopsFirewallItStarted() {
        assertTrue(InternetPauseManager.shouldRestoreFirewallOff(true, false));
        assertFalse(InternetPauseManager.shouldRestoreFirewallOff(true, true));
        assertFalse(InternetPauseManager.shouldRestoreFirewallOff(false, false));
    }

    @Test
    public void packageBackedSystemAppsAreEligibleButProtectedIdentitiesAreNot() {
        assertTrue(InternetPauseManager.isEligiblePackageRule(true, false, false));
        // Preinstalled/OEM system packages are package-backed and should be
        // included in the pause overlay even when normal system routing is off.
        assertTrue(InternetPauseManager.isEligibleUserRule(true, true, false));
        assertFalse(InternetPauseManager.isEligibleUserRule(false, false, false));
        assertFalse(InternetPauseManager.isEligibleUserRule(false, true, false));
        assertFalse(InternetPauseManager.isEligibleUserRule(true, false, true));
        assertFalse(InternetPauseManager.isEligibleUserRule(true, true, true));
    }

    @Test
    public void systemPackagesAreRoutedOnlyForPauseOrWhenNormallyEnabled() {
        assertFalse(InternetPauseManager.shouldIncludeSystemApps(false, false));
        assertTrue(InternetPauseManager.shouldIncludeSystemApps(true, false));
        assertTrue(InternetPauseManager.shouldIncludeSystemApps(false, true));
        assertTrue(InternetPauseManager.shouldIncludeSystemApps(true, true));
    }

    @Test
    public void protectedUidExcludesEveryPackageSharingThatUid() {
        Set<Integer> protectedUids = new HashSet<>(Collections.singleton(1001));
        assertFalse(InternetPauseManager.isEligibleUid(1001, protectedUids));
        assertTrue(InternetPauseManager.isEligibleUid(1002, protectedUids));
        assertFalse(InternetPauseManager.isEligibleUid(-1, protectedUids));
    }

    @Test
    public void externalFirewallStopsAreBlockedWhilePauseIsActive() {
        assertTrue(InternetPauseManager.shouldBlockFirewallStop(true, "switch off"));
        assertTrue(InternetPauseManager.shouldBlockFirewallStop(true, "background"));
        assertTrue(InternetPauseManager.shouldBlockFirewallStop(true, "call state"));
        assertTrue(InternetPauseManager.shouldBlockFirewallStop(true, null));
        assertFalse(InternetPauseManager.shouldBlockFirewallStop(true, "internet_pause_restore"));
        assertFalse(InternetPauseManager.shouldBlockFirewallStop(true, "native recovery exhausted"));
        assertFalse(InternetPauseManager.shouldBlockFirewallStop(false, "switch off"));
    }

    @Test
    public void turningPauseOffClearsTemporaryBlocksEvenWhenVpnIsStopped() {
        context.getSharedPreferences(InternetPauseManager.PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(InternetPauseManager.KEY_ACTIVE, true)
                .putBoolean(InternetPauseManager.KEY_APPLIED, true).commit();
        InternetBlocklist blocklist = InternetBlocklist.getInstance(context);
        blocklist.setTemporaryBlockedUids(Collections.singleton(1001));

        assertTrue(InternetPauseManager.setPaused(context, false));

        assertFalse(InternetPauseManager.isActive(context));
        assertFalse(InternetPauseManager.isApplied(context));
        assertFalse(blocklist.blockedInternet(1001));
    }
}
