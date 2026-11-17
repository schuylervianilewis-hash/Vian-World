package net.kollnig.missioncontrol.details;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

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
    public void firewallListFillsTheScreenBelowTheCompactBulkActions() {
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
        assertTrue(root.getChildAt(1) instanceof FrameLayout);

        // Vian uses compact, column-based bulk actions rather than the
        // standalone app's three full-width MaterialButtons.
        LinearLayout actions = (LinearLayout) root.getChildAt(0);
        assertEquals(5, actions.getChildCount());
        assertActionColumn(activity, actions, 2, R.string.firewall_block);
        assertActionColumn(activity, actions, 3, R.string.firewall_vpn);
        assertActionColumn(activity, actions, 4, R.string.firewall_direct);

        LinearLayout.LayoutParams listParams =
                (LinearLayout.LayoutParams) root.getChildAt(1).getLayoutParams();
        assertEquals(1f, listParams.weight, 0f);
        assertEquals(0, listParams.height);

        FrameLayout listContainer = (FrameLayout) root.getChildAt(1);
        assertEquals(2, listContainer.getChildCount());
        assertTrue(listContainer.getChildAt(0) instanceof SwipeRefreshLayout);
        assertTrue(listContainer.getChildAt(1) instanceof TextView);

        SwipeRefreshLayout swipeRefresh = (SwipeRefreshLayout) listContainer.getChildAt(0);
        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT,
                swipeRefresh.getLayoutParams().width);
        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT,
                swipeRefresh.getLayoutParams().height);

        // SwipeRefreshLayout owns internal progress views too. Locate the
        // actual app list instead of assuming it is child zero.
        RecyclerView list = null;
        for (int i = 0; i < swipeRefresh.getChildCount(); i++) {
            View child = swipeRefresh.getChildAt(i);
            if (child instanceof RecyclerView) {
                list = (RecyclerView) child;
                break;
            }
        }
        assertNotNull(list);
        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, list.getLayoutParams().width);
        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, list.getLayoutParams().height);
    }

    private static void assertActionColumn(FragmentActivity activity, LinearLayout actions,
            int index, int expectedHeading) {
        assertTrue(actions.getChildAt(index) instanceof LinearLayout);
        LinearLayout column = (LinearLayout) actions.getChildAt(index);
        assertEquals(2, column.getChildCount());
        assertTrue(column.getChildAt(0) instanceof TextView);
        assertTrue(column.getChildAt(1) instanceof MaterialButton);

        TextView heading = (TextView) column.getChildAt(0);
        assertEquals(activity.getString(expectedHeading), heading.getText().toString());

        MaterialButton button = (MaterialButton) column.getChildAt(1);
        assertEquals(activity.getString(R.string.firewall_all_short), button.getText().toString());
    }
}
