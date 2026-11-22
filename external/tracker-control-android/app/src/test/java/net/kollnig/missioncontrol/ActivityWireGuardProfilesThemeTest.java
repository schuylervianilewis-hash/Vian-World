package net.kollnig.missioncontrol;

import static org.junit.Assert.assertEquals;

import android.content.ComponentName;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

@RunWith(RobolectricTestRunner.class)
public class ActivityWireGuardProfilesThemeTest {
    @Test
    public void profileManagerActivityUsesActionBarAppCompatTheme() throws Exception {
        PackageManager packageManager = RuntimeEnvironment.getApplication().getPackageManager();
        ActivityInfo activityInfo = packageManager.getActivityInfo(
                new ComponentName(RuntimeEnvironment.getApplication(),
                        ActivityWireGuardProfiles.class),
                PackageManager.GET_META_DATA);

        assertEquals(R.style.AppTheme, activityInfo.theme);
    }
}
