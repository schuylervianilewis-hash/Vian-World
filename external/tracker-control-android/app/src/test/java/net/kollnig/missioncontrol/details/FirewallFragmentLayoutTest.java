package net.kollnig.missioncontrol.details;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import androidx.fragment.app.FragmentActivity;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.android.material.button.MaterialButton;

import net.kollnig.missioncontrol.R;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class FirewallFragmentLayoutTest {
    @Test
    public void firewallListFillsTheScreenBelowThreeFullWidthBulkActions() {
        org.robolectric.android.controller.ActivityController<FragmentActivity> controller =
                Robolectric.buildActivity(FragmentActivity.class);
        FragmentActivity activity = controller.get();
        activity.setTheme(R.style.AppTheme_NoActionBar);
        controller.setup();

        FrameLayout host = new FrameLayout(activity);
        host.setId(View.generateViewId());
        activity.setContentView(host);

        FirewallFragment fragment = new FirewallFragment();
        activity.getSupportFragmentManager().beginTransaction()
                .add(host.getId(), fragment)
                .commitNow();

        View rootView = fragment.getView();
        assertNotNull(rootView);
        assertTrue(rootView instanceof LinearLayout);
        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, rootView.getLayoutParams().width);
        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, rootView.getLayoutParams().height);

        LinearLayout root = (LinearLayout) rootView;
        assertEquals(LinearLayout.VERTICAL, root.getOrientation());
        assertEquals(2, root.getChildCount());
        assertTrue(root.getChildAt(0) instanceof LinearLayout);
        assertTrue(root.getChildAt(1) instanceof SwipeRefreshLayout);

        LinearLayout actions = (LinearLayout) root.getChildAt(0);
        assertEquals(3, actions.getChildCount());
        assertActionButton(activity, actions, 0, R.string.firewall_direct_all);
        assertActionButton(activity, actions, 1, R.string.firewall_vpn_all);
        assertActionButton(activity, actions, 2, R.string.firewall_block_all);

        LinearLayout.LayoutParams listParams =
                (LinearLayout.LayoutParams) root.getChildAt(1).getLayoutParams();
        assertEquals(1f, listParams.weight, 0f);
        assertEquals(0, listParams.height);

        SwipeRefreshLayout swipeRefresh = (SwipeRefreshLayout) root.getChildAt(1);
        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, swipeRefresh.getLayoutParams().width);
        RecyclerView list = null;
        for (int i = 0; i < swipeRefresh.getChildCount(); i++) {
            View child = swipeRefresh.getChildAt(i);
            if (child instanceof RecyclerView) { list = (RecyclerView) child; break; }
        }
        assertNotNull(list);
        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, list.getLayoutParams().width);
        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, list.getLayoutParams().height);
    }

    private static void assertActionButton(FragmentActivity activity, LinearLayout actions,
            int index, int expectedTitle) {
        assertTrue(actions.getChildAt(index) instanceof MaterialButton);
        MaterialButton button = (MaterialButton) actions.getChildAt(index);
        assertEquals(activity.getString(expectedTitle), button.getText().toString());
        LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) button.getLayoutParams();
        assertEquals(0, params.width);
        assertEquals(1f, params.weight, 0f);
    }
}
