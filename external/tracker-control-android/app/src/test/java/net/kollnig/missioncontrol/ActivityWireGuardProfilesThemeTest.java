package net.kollnig.missioncontrol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import android.content.ComponentName;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;

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

    @Test
    public void profileManagerActivityCreatesUsableSupportActionBar() {
        ActivityController<ActivityWireGuardProfiles> controller =
                Robolectric.buildActivity(ActivityWireGuardProfiles.class).create();
        ActivityWireGuardProfiles activity = controller.get();

        assertNotNull(activity.getSupportActionBar());
        assertEquals(
                activity.getString(R.string.setting_wg_profile_manage),
                activity.getSupportActionBar().getTitle());

        controller.pause().stop().destroy();
    }
}
