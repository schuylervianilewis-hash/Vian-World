package net.kollnig.missioncontrol.data;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

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
    public void onlyEligibleUserPackageRulesAreIncluded() {
        assertTrue(InternetPauseManager.isEligibleUserRule(true, false, false));
        assertFalse(InternetPauseManager.isEligibleUserRule(false, false, false));
        assertFalse(InternetPauseManager.isEligibleUserRule(true, true, false));
        assertFalse(InternetPauseManager.isEligibleUserRule(true, false, true));
    }

    @Test
    public void protectedUidExcludesEveryPackageSharingThatUid() {
        Set<Integer> protectedUids = new HashSet<>(Collections.singleton(1001));
        assertFalse(InternetPauseManager.isEligibleUid(1001, protectedUids));
        assertTrue(InternetPauseManager.isEligibleUid(1002, protectedUids));
        assertFalse(InternetPauseManager.isEligibleUid(-1, protectedUids));
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
