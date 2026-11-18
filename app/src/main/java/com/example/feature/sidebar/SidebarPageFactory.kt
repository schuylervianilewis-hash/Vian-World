package com.example.feature.sidebar

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import com.example.core.HandleManager
import com.example.core.PageManager
import com.example.core.SidebarPage
import kotlinx.coroutines.CoroutineScope

/**
 * SidebarPageFactory: Factory and lifecycle manager for instantiating Sidebar page views.
 *
 * Extracts page creation out of SidebarView to establish a clean boundary between the
 * high-level Sidebar coordinator (window, IME, keyboard, sizing, gestures) and the
 * dedicated page / element runtime.
 */
interface SidebarPageFactory {
    fun createPageView(
        context: Context,
        config: SidebarPage,
        physicalHandleId: String,
        containerId: String,
        viewScope: CoroutineScope,
        onClose: () -> Unit,
        setDimmed: (Boolean) -> Unit,
        onHeightChanged: (Int) -> Unit
    ): View

    fun destroy()
}

/**
 * Default implementation of SidebarPageFactory creating existing modular page views.
 */
class DefaultSidebarPageFactory(
    private val prefs: SharedPreferences
) : SidebarPageFactory {

    private val appsManagers = mutableMapOf<String, SidebarAppsManager>()

    override fun createPageView(
        context: Context,
        config: SidebarPage,
        physicalHandleId: String,
        containerId: String,
        viewScope: CoroutineScope,
        onClose: () -> Unit,
        setDimmed: (Boolean) -> Unit,
        onHeightChanged: (Int) -> Unit
    ): View {
        return when (config.type) {
            "calculator" -> HeavyPagePlaceholderView(context, config, containerId) { newHeight ->
                onHeightChanged(newHeight)
            }
            "compass" -> HeavyPagePlaceholderView(context, config, containerId) { newHeight ->
                onHeightChanged(newHeight)
            }
            "apps", "apps_grid" -> {
                HeavyPagePlaceholderView(context, config, containerId) { newHeight ->
                    onHeightChanged(newHeight)
                }
            }
            "hybrid_grid", "default_hybrid", "tools" -> {
                if (isResidentDefaultHybrid(context, config, physicalHandleId, containerId)) {
                    HybridGridPageView(
                        context, "default_hybrid", viewScope, containerId,
                        onClose = { onClose() },
                        onDimSidebar = { dimmed -> setDimmed(dimmed) }
                    ) { newHeight ->
                        onHeightChanged(newHeight)
                    }
                } else {
                    HeavyPagePlaceholderView(context, config, containerId) { newHeight ->
                        onHeightChanged(newHeight)
                    }
                }
            }
            "widgets_grid", "widgets" -> {
                HeavyPagePlaceholderView(context, config, containerId) { newHeight ->
                    onHeightChanged(newHeight)
                }
            }
            "app_tracker" -> HeavyPagePlaceholderView(context, config, containerId) { newHeight ->
                onHeightChanged(newHeight)
            }
            "media_player" -> HeavyPagePlaceholderView(context, config, containerId) { newHeight ->
                onHeightChanged(newHeight)
            }
            "widget", "single_widget" -> {
                HeavyPagePlaceholderView(context, config, containerId) { newHeight ->
                    onHeightChanged(newHeight)
                }
            }
            "scheduler" -> HeavyPagePlaceholderView(context, config, containerId) { newHeight ->
                onHeightChanged(newHeight)
            }
            "notifications", "notification" -> HeavyPagePlaceholderView(context, config, containerId) { newHeight ->
                onHeightChanged(newHeight)
            }
            "resources_tracker" -> HeavyPagePlaceholderView(context, config, containerId) { newHeight ->
                onHeightChanged(newHeight)
            }
            else -> {
                TextView(context).apply {
                    text = "Page: ${config.title}\nType: ${config.type}\n(Not Implemented)"
                    setTextColor(Color.WHITE)
                    textSize = 16f
                    gravity = Gravity.CENTER
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                }
            }
        }
    }

    override fun destroy() {
        appsManagers.values.forEach { it.destroy() }
        appsManagers.clear()
    }

    companion object {
        /**
         * Enforces the Main/Heavy Sidebar split residency rule for Home Grid:
         *
         * MAIN owns exactly ONE resident page: default_hybrid on Handle 1's
         * first configured Sidebar-opening gesture. The default configuration is
         * Handle 1 Swipe Left; if the user changes that primary gesture, residency
         * follows the new container rather than leaving the Home Grid stranded.
         *
         * Every secondary Home Grid and every other handle/container belongs to
         * :heavy and is represented by HeavyPagePlaceholderView.
         */
        fun isResidentDefaultHybrid(
            context: Context,
            config: SidebarPage,
            physicalHandleId: String,
            containerId: String
        ): Boolean {
            if (config.id != "default_hybrid") return false
            val cleanHandleId = PageManager.getCleanHandleId(
                if (physicalHandleId.isNotBlank()) physicalHandleId else containerId
            )
            if (cleanHandleId != "handle_1" && cleanHandleId != "sidebar") return false

            val primaryContainerId = HandleManager.getInstance(context).getFirstHandlePrimaryContainerId()
            val isLegacyPrimaryAlias = primaryContainerId == "handle_1_swipe_left" &&
                (containerId == "sidebar" || containerId == "handle_1")
            return containerId == primaryContainerId || isLegacyPrimaryAlias
        }
    }
}
