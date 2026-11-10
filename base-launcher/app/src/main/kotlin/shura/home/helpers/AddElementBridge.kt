package shura.home.helpers

import shura.home.activities.MainActivity
import shura.home.dialogs.AddToHomeBottomSheet

/**
 * Coordinator bridge between the launcher home screen and element-adding UI flows.
 * Decouples the home screen placement engine from the UI presentation, ensuring that
 * when merged with the Sidebar App, routing to the Sidebar's external multi-page Add
 * Element Activity requires modifying only the delegate implementation.
 */
object AddElementBridge {

    interface Delegate {
        fun openAddElementUi(
            activity: MainActivity,
            targetX: Float? = null,
            targetY: Float? = null,
        )
    }

    private object DefaultLauncherAddElementDelegate : Delegate {
        override fun openAddElementUi(
            activity: MainActivity,
            targetX: Float?,
            targetY: Float?,
        ) {
            AddToHomeBottomSheet(activity, targetX, targetY).show()
        }
    }

    private var currentDelegate: Delegate = DefaultLauncherAddElementDelegate

    fun setDelegate(delegate: Delegate) {
        currentDelegate = delegate
        LogCatcher.log("AddElementBridge", "Custom AddElementDelegate registered")
    }

    fun resetToDefault() {
        currentDelegate = DefaultLauncherAddElementDelegate
    }

    fun open(
        activity: MainActivity,
        targetX: Float? = null,
        targetY: Float? = null,
    ) {
        LogCatcher.log("AddElementBridge", "Opening Add Element UI at coords ($targetX, $targetY)")
        currentDelegate.openAddElementUi(activity, targetX, targetY)
    }
}
