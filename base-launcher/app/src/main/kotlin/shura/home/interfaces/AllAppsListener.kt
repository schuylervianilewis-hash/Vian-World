package shura.home.interfaces

import android.graphics.Rect
import shura.home.models.AppLauncher

interface AllAppsListener {
    fun onAppLauncherLongPressed(
        x: Float,
        y: Float,
        appLauncher: AppLauncher,
        iconRect: Rect? = null,
    )
}
