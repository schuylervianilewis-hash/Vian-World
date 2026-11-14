package shura.home.models

import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.drawable.Drawable
import androidx.core.content.ContextCompat
import shura.home.R
import shura.home.helpers.BUILT_IN_CLOCK_CLASS_NAME

data class AppWidget(
    var appPackageName: String,
    var appTitle: String,
    val appIcon: Drawable?,
    val widgetTitle: String,
    var widgetPreviewImage: Drawable? = null,
    var widthCells: Int,
    val heightCells: Int,
    val isShortcut: Boolean,
    val className: String,      // identifier to know which app widget are we using
    val providerInfo: AppWidgetProviderInfo?,       // used at widgets
    val activityInfo: ActivityInfo?,                // used at shortcuts
    val iconResource: Int = 0
) : WidgetsListItem() {
    override fun getHashToCompare() = getStringToCompare().hashCode()

    private fun getStringToCompare(): String {
        return copy(appIcon = null, widgetPreviewImage = null).toString()
    }

    fun loadPreview(context: Context): Drawable? {
        if (widgetPreviewImage != null) return widgetPreviewImage
        val loaded = when {
            className == BUILT_IN_CLOCK_CLASS_NAME -> {
                ContextCompat.getDrawable(context, R.drawable.ic_clock_widget_preview)
            }
            providerInfo != null -> {
                providerInfo.loadPreviewImage(context, context.resources.displayMetrics.densityDpi) ?: appIcon
            }
            activityInfo != null && iconResource != 0 -> {
                try {
                    context.packageManager.getDrawable(activityInfo.packageName, iconResource, activityInfo.applicationInfo)
                } catch (_: Exception) {
                    appIcon
                }
            }
            else -> appIcon
        }
        widgetPreviewImage = loaded
        return loaded
    }
}
