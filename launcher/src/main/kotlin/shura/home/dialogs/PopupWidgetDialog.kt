package shura.home.dialogs

import android.app.Dialog
import android.appwidget.AppWidgetProviderInfo
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.FrameLayout
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.helpers.ensureBackgroundThread
import shura.home.R
import shura.home.activities.MainActivity
import shura.home.databinding.DialogPopupWidgetBinding
import shura.home.helpers.LogCatcher
import shura.home.models.HomeScreenGridItem
import shura.home.views.MyAppWidgetHost
import shura.home.views.MyAppWidgetHostView
import kotlin.math.max
import kotlin.math.min

class PopupWidgetDialog(
    private val activity: MainActivity,
    private val appWidgetHost: MyAppWidgetHost,
    private val targetItem: HomeScreenGridItem,
    private val widgetId: Int,
    private val providerInfo: AppWidgetProviderInfo,
    private val onReconfigure: (() -> Unit)? = null,
    private val onChangeWidget: (() -> Unit)? = null,
) : Dialog(activity) {

    private val binding = DialogPopupWidgetBinding.inflate(LayoutInflater.from(activity))
    private var widgetHostView: MyAppWidgetHostView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)

        window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.CENTER)
        }

        setupHeader()
        attachWidgetView()

        setOnDismissListener {
            detachWidgetView()
        }
    }

    private fun setupHeader() {
        val packageManager = activity.packageManager
        val widgetTitle = providerInfo.loadLabel(packageManager).ifEmpty {
            targetItem.title.ifEmpty { activity.getString(R.string.popup_widget) }
        }
        binding.popupWidgetTitle.text = widgetTitle

        // Header App Icon
        ensureBackgroundThread {
            val appIcon = try {
                if (targetItem.packageName.isNotEmpty()) {
                    packageManager.getApplicationIcon(targetItem.packageName)
                } else {
                    null
                }
            } catch (e: Exception) {
                null
            }

            activity.runOnUiThread {
                if (appIcon != null) {
                    binding.popupWidgetAppIcon.setImageDrawable(appIcon)
                } else {
                    binding.popupWidgetAppIcon.setImageResource(R.drawable.ic_widget_vector)
                }
            }
        }

        // Configure button (if widget has configuration activity)
        val canConfigure = providerInfo.configure != null
        binding.popupWidgetBtnConfigure.beVisibleIf(canConfigure)
        if (canConfigure) {
            binding.popupWidgetBtnConfigure.setOnClickListener {
                dismiss()
                onReconfigure?.invoke()
            }
        }

        // Unlink / Change Widget button
        binding.popupWidgetBtnUnlink.setOnClickListener {
            dismiss()
            onChangeWidget?.invoke()
        }

        // Close button
        binding.popupWidgetBtnClose.setOnClickListener {
            dismiss()
        }
    }

    private fun attachWidgetView() {
        try {
            // Important: Use baseContext to prevent themed context conflicts with RemoteViews
            val hostView = appWidgetHost.createView(
                activity.baseContext,
                widgetId,
                providerInfo
            ) as MyAppWidgetHostView

            hostView.setAppWidget(widgetId, providerInfo)
            hostView.ignoreTouches = false
            widgetHostView = hostView

            val displayMetrics = activity.resources.displayMetrics
            val density = displayMetrics.density
            val maxAllowedWidth = (displayMetrics.widthPixels * 0.92f).toInt()
            val maxAllowedHeight = (displayMetrics.heightPixels * 0.65f).toInt()

            val estimatedMinHeight = if (providerInfo.minHeight > 0) {
                (providerInfo.minHeight * density).toInt()
            } else {
                (160 * density).toInt()
            }

            val targetHeight = min(maxAllowedHeight, max((120 * density).toInt(), estimatedMinHeight))

            val params = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                targetHeight
            ).apply {
                gravity = Gravity.CENTER
            }

            binding.popupWidgetContentFrame.removeAllViews()
            binding.popupWidgetContentFrame.addView(hostView, params)
            LogCatcher.log("PopupWidgetDialog", "Attached popup widget view (ID: $widgetId) for ${targetItem.title}")
        } catch (e: Exception) {
            LogCatcher.log("PopupWidgetDialog", "Failed to inflate popup widget view: $widgetId", e)
        }
    }

    private fun detachWidgetView() {
        try {
            binding.popupWidgetContentFrame.removeAllViews()
            widgetHostView = null
            LogCatcher.log("PopupWidgetDialog", "Detached popup widget view (ID: $widgetId), memory freed")
        } catch (e: Exception) {
            LogCatcher.log("PopupWidgetDialog", "Error during widget view detachment", e)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        detachWidgetView()
    }
}
