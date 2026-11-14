package shura.home.activities

import android.animation.ObjectAnimator
import android.view.animation.PathInterpolator
import android.annotation.SuppressLint
import android.app.WallpaperManager
import android.app.WallpaperManager.OnColorsChangedListener
import android.app.admin.DevicePolicyManager
import android.app.role.RoleManager
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import java.io.File
import shura.home.services.NotificationBadgeService
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.provider.Telephony
import android.telecom.TelecomManager
import android.view.ContextThemeWrapper
import android.view.GestureDetector
import android.view.Gravity
import android.view.Menu
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.DecelerateInterpolator
import android.view.WindowManager
import android.widget.PopupWindow
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.core.graphics.drawable.toDrawable
import androidx.core.net.toUri
import androidx.core.view.GestureDetectorCompat
import androidx.core.view.isVisible
import androidx.core.view.iterator
import androidx.viewbinding.ViewBinding
import kotlinx.collections.immutable.toImmutableList
import org.fossify.commons.dialogs.ConfirmationDialog
import org.fossify.commons.extensions.appLaunched
import org.fossify.commons.extensions.beVisible
import org.fossify.commons.extensions.getContrastColor
import org.fossify.commons.extensions.getPopupMenuTheme
import org.fossify.commons.extensions.getProperBackgroundColor
import org.fossify.commons.extensions.insetsController
import org.fossify.commons.extensions.isPackageInstalled
import org.fossify.commons.extensions.onGlobalLayout
import org.fossify.commons.extensions.performHapticFeedback
import org.fossify.commons.extensions.realScreenSize
import org.fossify.commons.extensions.showErrorToast
import org.fossify.commons.extensions.showKeyboard
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.DARK_GREY
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.helpers.isOreoMr1Plus
import org.fossify.commons.helpers.isQPlus
import shura.home.BuildConfig
import shura.home.R
import shura.home.databinding.ActivityMainBinding
import shura.home.databinding.AllAppsFragmentBinding
import shura.home.databinding.WidgetsFragmentBinding
import android.graphics.drawable.BitmapDrawable
import shura.home.adapters.ShortcutItem
import shura.home.dialogs.AddAppDialog
import shura.home.dialogs.AddShortcutDialog
import shura.home.dialogs.CreateFolderDialog
import shura.home.dialogs.EditItemDialog
import shura.home.dialogs.ItemGesturesDialog
import shura.home.dialogs.PopupWidgetDialog
import shura.home.dialogs.RenameItemDialog
import shura.home.dialogs.SelectPopupWidgetDialog
import shura.home.helpers.AddElementBridge
import shura.home.helpers.CustomIconManager
import shura.home.helpers.ItemGestureManager
import shura.home.helpers.LauncherActionHandler
import shura.home.helpers.LogCatcher
import androidx.activity.result.contract.ActivityResultContracts
import shura.home.extensions.config
import shura.home.extensions.getDrawableForPackageName
import shura.home.extensions.getLabel
import shura.home.extensions.handleGridItemPopupMenu
import shura.home.extensions.hiddenIconsDB
import shura.home.extensions.homeScreenGridItemsDB
import shura.home.extensions.isDefaultLauncher
import shura.home.extensions.launchApp
import shura.home.extensions.launchAppInfo
import shura.home.extensions.launchersDB
import shura.home.extensions.roleManager
import shura.home.extensions.supportsDarkText
import shura.home.extensions.uninstallApp
import shura.home.fragments.MyFragment
import shura.home.helpers.APP_LOCK_TIMEOUT_IMMEDIATELY
import shura.home.helpers.AppLockManager
import shura.home.helpers.BUILT_IN_CLOCK_CLASS_NAME
import shura.home.helpers.ITEM_TYPE_FOLDER
import shura.home.helpers.ITEM_TYPE_ICON
import shura.home.helpers.ITEM_TYPE_SHORTCUT
import shura.home.helpers.ITEM_TYPE_WIDGET
import shura.home.helpers.IconCache
import shura.home.helpers.REQUEST_ALLOW_BINDING_WIDGET
import shura.home.helpers.REQUEST_CONFIGURE_WIDGET
import shura.home.helpers.WIDGET_ID_BUILTIN_CLOCK
import shura.home.helpers.REQUEST_CREATE_SHORTCUT
import shura.home.helpers.REQUEST_SET_DEFAULT
import shura.home.helpers.SIDEBAR_ELEMENT_CLASS_NAME
import shura.home.helpers.UNINSTALL_APP_REQUEST_CODE
import shura.home.interfaces.FlingListener
import shura.home.interfaces.ItemMenuListener
import shura.home.models.AppLauncher
import shura.home.models.HiddenIcon
import shura.home.models.HomeScreenGridItem
import shura.home.receivers.LockDeviceAdminReceiver
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class MainActivity : SimpleActivity(), FlingListener {
    private var sidebarSetupCheckAttempted = false
    private var pendingSidebarElementPlacement: Pair<Int, Rect>? = null
    private var mTouchDownX = -1
    private var mTouchDownY = -1
    private var mAllAppsFragmentY = 0
    private var mWidgetsFragmentY = 0
    private var mScreenHeight = 0
    private var mMoveGestureThreshold = 0
    private var mIgnoreUpEvent = false
    private var mIgnoreMoveEvents = false
    private var mIgnoreXMoveEvents = false
    private var mIgnoreYMoveEvents = false
    private var mLongPressedIcon: HomeScreenGridItem? = null
    var mTouchDownItem: HomeScreenGridItem? = null
    private var mOpenPopupMenu: PopupWindow? = null
    private var mLastTouchCoords = Pair(-1f, -1f)
    private var mActionOnCanBindWidget: ((granted: Boolean) -> Unit)? = null
    private var mActionOnWidgetConfiguredWidget: ((granted: Boolean) -> Unit)? = null
    private var mActionOnAddShortcut:
            ((shortcutId: String, label: String, icon: Drawable) -> Unit)? = null
    private var wasJustPaused: Boolean = false

    private var wallpaperColorChangeListener: OnColorsChangedListener? = null
    private var wallpaperSupportsDarkText: Boolean? = null

    private lateinit var mDetector: GestureDetectorCompat
    private lateinit var mScaleDetector: ScaleGestureDetector
    private var mPinchHandled = false
    private val binding by viewBinding(ActivityMainBinding::inflate)
    val logKeeper by lazy { shura.home.helpers.LogKeeperHelper(applicationContext) }

    private val packageUpdateDebounceHandler = Handler(Looper.getMainLooper())
    private val packageUpdateDebounceRunnable = Runnable {
        needsAppListRefresh = true
        ensureBackgroundThread {
            refreshLaunchers()
        }
    }

    private var isPackageUpdateReceiverRegistered = false

    private val packageUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            val packageName = intent.data?.schemeSpecificPart ?: return
            logKeeper.log("MainActivity", "Package event: $action for $packageName")
            if (action == Intent.ACTION_PACKAGE_ADDED) {
                val replacing = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)
                if (!replacing) {
                    config.addNewInstalledPackage(packageName)
                }
            } else if (action == Intent.ACTION_PACKAGE_REMOVED) {
                val replacing = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)
                if (!replacing) {
                    config.removeNewInstalledPackage(packageName)
                }
            }
            packageUpdateDebounceHandler.removeCallbacks(packageUpdateDebounceRunnable)
            packageUpdateDebounceHandler.postDelayed(packageUpdateDebounceRunnable, 500)
        }
    }

    private var mIconPickerCallback: ((Bitmap?) -> Unit)? = null
    private val mPickImageLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            val bitmap = CustomIconManager.loadAndDownsampleFromUri(this, uri)
            mIconPickerCallback?.invoke(bitmap)
        }
    }

    fun launchIconPicker(callback: (Bitmap?) -> Unit) {
        mIconPickerCallback = callback
        try {
            mPickImageLauncher.launch("image/*")
        } catch (e: Exception) {
            toast(org.fossify.commons.R.string.unknown_error_occurred)
        }
    }

    private val mPickWallpaperLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            shura.home.helpers.WallpaperHelper.showWallpaperTargetDialog(this, uri) {
                binding.allAppsFragment.root.setupViews()
            }
        }
    }

    companion object {
        var needsAppListRefresh = true
        private var mLastUpEvent = 0L
        private const val ANIMATION_DURATION = 150L
        private const val APP_DRAWER_CLOSE_DELAY = 300L
        private const val APP_DRAWER_STATE = "app_drawer_state"
        private var isCrashHandlerInstalled = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        useDynamicTheme = false
        installCrashHandlerIfNeeded()

        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        appLaunched(applicationContext.packageName)
        AppLockManager.init(this)
        setupEdgeToEdge(
            padTopSystem = listOf(binding.allAppsFragment.root, binding.widgetsFragment.root),
            padBottomImeAndSystem = listOf(
                binding.allAppsFragment.allAppsGrid, binding.widgetsFragment.widgetsList
            ),
            padBottomSystem = listOf(binding.homeScreenGrid.root)
        )

        window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS)
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        window.statusBarColor = Color.TRANSPARENT
        if (isQPlus()) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }

        mDetector = GestureDetectorCompat(this, MyGestureListener(this))
        mScaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                if (!mPinchHandled && detector.scaleFactor < 0.82f) {
                    val action = config.gesturePinchInAction
                    if (action.isNotEmpty() && action != LauncherActionHandler.ACTION_NONE) {
                        mPinchHandled = true
                        if (config.gestureHaptics) {
                            binding.mainHolder.performHapticFeedback()
                        }
                        LauncherActionHandler.executeAction(this@MainActivity, action)
                        return true
                    }
                }
                return false
            }
        })

        mScreenHeight = realScreenSize.y
        mAllAppsFragmentY = mScreenHeight
        mWidgetsFragmentY = mScreenHeight
        mMoveGestureThreshold = resources.getDimensionPixelSize(R.dimen.move_gesture_threshold)

        arrayOf(
            binding.allAppsFragment.root as MyFragment<*>,
            binding.widgetsFragment.root as MyFragment<*>
        ).forEach { fragment ->
            fragment.setupFragment(this)
            fragment.y = mScreenHeight.toFloat()
            fragment.beVisible()
        }

        handleIntentAction(intent)

        binding.homeScreenGrid.root.itemClickListener = {
            performItemClick(it)
        }

        binding.homeScreenGrid.root.itemLongClickListener = {
            performItemLongClick(
                x = binding.homeScreenGrid.root.getClickableRect(it).left.toFloat(),
                clickedGridItem = it
            )
        }

        if (!isDefaultLauncher()) {
            requestHomeRole()
        }

    }

    /**
     * Reads setup state from the Heavy-process provider, the single owner of this preference.
     * The Binder read runs in the background so starting :heavy cannot stall the launcher UI.
     * Run once for each fresh launcher visit, not on every onResume callback.
     */
    private fun maybeOpenSidebarSetupForFreshLaunch() {
        if (sidebarSetupCheckAttempted || isFinishing || !isDefaultLauncher()) return
        sidebarSetupCheckAttempted = true

        ensureBackgroundThread {
            val setupCompleted = try {
                val result = contentResolver.call(
                    android.net.Uri.parse("content://$packageName.sidebar.setup"),
                    "is_setup_completed",
                    null,
                    null
                )
                result?.getBoolean("setup_completed", false) == true
            } catch (e: Exception) {
                logKeeper.log("MainActivity", "Could not read Sidebar setup state; opening Welcome safely", e)
                false
            }

            runOnUiThread {
                if (isFinishing || isDestroyed || setupCompleted) return@runOnUiThread
                try {
                    startActivity(Intent().setClassName(
                        packageName,
                        "com.example.feature.welcome.WelcomeActivity"
                    ))
                } catch (e: Exception) {
                    logKeeper.log("MainActivity", "Failed to open Sidebar Welcome", e)
                    toast("Unable to open Sidebar setup")
                }
            }
        }
    }

    private fun setupWallpaperColorListener() {
        if (isOreoMr1Plus() && wallpaperColorChangeListener == null) {
            val wallpaperManager = WallpaperManager.getInstance(this)
            wallpaperColorChangeListener = OnColorsChangedListener { colors, which ->
                if (which and WallpaperManager.FLAG_SYSTEM != 0) {
                    wallpaperSupportsDarkText = colors?.supportsDarkText() ?: run {
                        refreshWallpaperSupportsDarkText()
                        wallpaperSupportsDarkText
                    }
                    if (!isAllAppsFragmentExpanded() && !isWidgetsFragmentExpanded()) {
                        runOnUiThread {
                            updateStatusBarIcons()
                        }
                    }
                }
            }
            wallpaperManager.addOnColorsChangedListener(
                wallpaperColorChangeListener!!,
                Handler(Looper.getMainLooper())
            )

            refreshWallpaperSupportsDarkText()
        }
    }

    private fun refreshWallpaperSupportsDarkText() {
        if (!isOreoMr1Plus()) return
        wallpaperSupportsDarkText = WallpaperManager.getInstance(this)
            .getWallpaperColors(WallpaperManager.FLAG_SYSTEM)
            ?.supportsDarkText()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val isFreshLauncherVisit = wasJustPaused
        if (isFreshLauncherVisit) sidebarSetupCheckAttempted = false
        val wasAnyFragmentOpen = isAllAppsFragmentExpanded() || isWidgetsFragmentExpanded()
        if (wasJustPaused) {
            if (isAllAppsFragmentExpanded()) {
                hideFragment(binding.allAppsFragment)
            }
            if (isWidgetsFragmentExpanded()) {
                hideFragment(binding.widgetsFragment)
            }
        } else {
            closeAppDrawer()
            closeWidgetsFragment()
        }

        binding.allAppsFragment.root.closeSearchMode()

        // scroll to first page when home button is pressed
        val alreadyOnHome = intent.flags and FLAG_ACTIVITY_BROUGHT_TO_FRONT == 0
        if (alreadyOnHome && !wasAnyFragmentOpen) {
            binding.homeScreenGrid.root.skipToPage(0)
        }

        handleIntentAction(intent)
        if (isFreshLauncherVisit) maybeOpenSidebarSetupForFreshLaunch()
    }

    override fun onStart() {
        super.onStart()

        // Home-only observers exist only while the genuine Home Activity is visible.
        if (!isPackageUpdateReceiverRegistered) {
            val packageFilter = IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addAction(Intent.ACTION_PACKAGE_CHANGED)
                addAction(Intent.ACTION_PACKAGE_REPLACED)
                addDataScheme("package")
            }
            ContextCompat.registerReceiver(
                this,
                packageUpdateReceiver,
                packageFilter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            isPackageUpdateReceiverRegistered = true
        }

        setupWallpaperColorListener()
        binding.homeScreenGrid.root.appWidgetHost.startListening()
    }

    override fun onResume() {
        super.onResume()
        maybeOpenSidebarSetupForFreshLaunch()
        wasJustPaused = false
        refreshWallpaperSupportsDarkText()
        Handler(Looper.getMainLooper()).postDelayed({
            if (isAllAppsFragmentExpanded() || isWidgetsFragmentExpanded()) {
                updateStatusBarIcons(getProperBackgroundColor())
            } else {
                updateStatusBarIcons()
            }
        }, ANIMATION_DURATION)

        with(binding.mainHolder) {
            onGlobalLayout {
                binding.allAppsFragment.root.setupViews()
                binding.widgetsFragment.root.setupViews()
            }
        }

        ensureBackgroundThread {
            if (IconCache.launchers.isEmpty()) {
                val hiddenIcons = hiddenIconsDB.getHiddenIcons().map {
                    it.getIconIdentifier()
                }

                IconCache.launchers = launchersDB.getAppLaunchers().filter {
                    val showIcon = !hiddenIcons.contains(it.getLauncherIdentifier())
                    if (!showIcon) {
                        try {
                            launchersDB.deleteById(it.id!!)
                        } catch (e: Exception) {
                            logKeeper.log("MainActivity", "Failed to delete hidden launcher id=${it.id}", e)
                        }
                    }
                    showIcon
                }.toMutableList() as ArrayList<AppLauncher>
            }

            binding.allAppsFragment.root.gotLaunchers(IconCache.launchers)
            if (needsAppListRefresh || IconCache.launchers.isEmpty()) {
                needsAppListRefresh = false
                refreshLaunchers()
            }
        }

        binding.homeScreenGrid.root.resizeGrid(
            newRowCount = config.homeRowCount,
            newColumnCount = config.homeColumnCount,
            newDockColumnCount = config.dockColumnCount
        )
        binding.homeScreenGrid.root.updateColors()
        binding.homeScreenGrid.root.refreshSidebarElementIcons()
        binding.allAppsFragment.root.onResume()
        refreshHomeScreenBadges()
    }

    override fun onStop() {
        super.onStop()
        try {
            binding.homeScreenGrid.root.appWidgetHost.stopListening()
        } catch (e: Exception) {
            logKeeper.log("MainActivity", "appWidgetHost.stopListening() failed in onStop", e)
        }

        packageUpdateDebounceHandler.removeCallbacks(packageUpdateDebounceRunnable)
        if (isPackageUpdateReceiverRegistered) {
            try {
                unregisterReceiver(packageUpdateReceiver)
            } catch (_: Exception) {
                // Receiver may already have been removed by the platform.
            } finally {
                isPackageUpdateReceiverRegistered = false
            }
        }
        wallpaperColorChangeListener?.let { listener ->
            try {
                WallpaperManager.getInstance(this).removeOnColorsChangedListener(listener)
            } catch (_: Exception) {
                // The wallpaper manager may already have detached the listener.
            } finally {
                wallpaperColorChangeListener = null
            }
        }

        wasJustPaused = false
    }

    override fun onDestroy() {
        super.onDestroy()
        packageUpdateDebounceHandler.removeCallbacksAndMessages(null)
        if (isPackageUpdateReceiverRegistered) {
            try {
                unregisterReceiver(packageUpdateReceiver)
            } catch (_: Exception) {
            } finally {
                isPackageUpdateReceiverRegistered = false
            }
        }
        wallpaperColorChangeListener?.let { listener ->
            try {
                WallpaperManager.getInstance(this).removeOnColorsChangedListener(listener)
            } catch (_: Exception) {
            } finally {
                wallpaperColorChangeListener = null
            }
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
            level == android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN
        ) {
            IconCache.clear()
        }
    }

    override fun onPause() {
        super.onPause()
        wasJustPaused = true
        if (config.appLockTimeout == APP_LOCK_TIMEOUT_IMMEDIATELY) {
            AppLockManager.clearSessions()
        }
    }

    override fun onBackPressedCompat(): Boolean {
        return if (isAllAppsFragmentExpanded()) {
            if (!binding.allAppsFragment.root.onBackPressed()) {
                hideFragment(binding.allAppsFragment)
                true
            } else {
                true
            }
        } else if (isWidgetsFragmentExpanded()) {
            if (binding.widgetsFragment.searchBar.isSearchOpen) {
                clearWidgetsSearch()
            } else {
                hideFragment(binding.widgetsFragment)
            }
            true
        } else if (binding.homeScreenGrid.resizeFrame.isVisible) {
            binding.homeScreenGrid.root.hideResizeLines()
            true
        } else {
            // this is a home launcher app, prevent back press from doing anything
            true
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, resultData: Intent?) {
        super.onActivityResult(requestCode, resultCode, resultData)

        if (requestCode == AddElementBridge.REQUEST_SIDEBAR_ELEMENT &&
            (resultCode != RESULT_OK || resultData == null)
        ) {
            pendingSidebarElementPlacement = null
        }

        when (requestCode) {
            AddElementBridge.REQUEST_SIDEBAR_ELEMENT -> {
                if (resultCode == RESULT_OK && resultData != null) {
                    if (resultData.getBooleanExtra("CREATE_LAUNCHER_FOLDER", false)) {
                        val preferredPlacement = pendingSidebarElementPlacement
                        pendingSidebarElementPlacement = null
                        showAddFolderDialogAtPlacement(preferredPlacement)
                    } else if (resultData.getBooleanExtra("OPEN_LAUNCHER_WIDGET_PICKER", false)) {
                        pendingSidebarElementPlacement = null
                        showWidgetsFragment()
                    } else {
                        val elementId = resultData.getStringExtra("ELEMENT_ID")
                        val displayName = resultData.getStringExtra("DISPLAY_NAME")
                        if (!elementId.isNullOrBlank()) {
                            val preferredPlacement = pendingSidebarElementPlacement
                            pendingSidebarElementPlacement = null
                            addSidebarElementToHome(elementId, displayName, preferredPlacement)
                        } else {
                            pendingSidebarElementPlacement = null
                            toast("Sidebar did not return a valid element")
                        }
                    }
                }
            }

            UNINSTALL_APP_REQUEST_CODE -> {
                ensureBackgroundThread {
                    refreshLaunchers()
                }
            }

            REQUEST_ALLOW_BINDING_WIDGET -> mActionOnCanBindWidget?.invoke(resultCode == RESULT_OK)
            REQUEST_CONFIGURE_WIDGET -> mActionOnWidgetConfiguredWidget?.invoke(resultCode == RESULT_OK)
            REQUEST_CREATE_SHORTCUT -> {
                if (resultCode == RESULT_OK && resultData != null) {
                    val launcherApps =
                        applicationContext.getSystemService(LAUNCHER_APPS_SERVICE) as LauncherApps
                    var handled = false
                    if (launcherApps.hasShortcutHostPermission()) {
                        val item = launcherApps.getPinItemRequest(resultData)
                        val shortcutInfo = item?.shortcutInfo
                        if (item != null && shortcutInfo != null && item.accept()) {
                            val shortcutId = shortcutInfo.id
                            val label = shortcutInfo.getLabel()
                            val icon = launcherApps.getShortcutBadgedIconDrawable(
                                shortcutInfo,
                                resources.displayMetrics.densityDpi
                            ) ?: launcherApps.getShortcutIconDrawable(
                                shortcutInfo,
                                resources.displayMetrics.densityDpi
                            )
                            if (icon != null) {
                                mActionOnAddShortcut?.invoke(shortcutId, label, icon)
                                handled = true
                            }
                        }
                    }

                    if (!handled) {
                        val shortcutIntent = IntentCompat.getParcelableExtra(resultData, Intent.EXTRA_SHORTCUT_INTENT, Intent::class.java)
                        val label = resultData.getStringExtra(Intent.EXTRA_SHORTCUT_NAME) ?: ""
                        var iconDrawable: Drawable? = null
                        val bitmap = IntentCompat.getParcelableExtra(resultData, Intent.EXTRA_SHORTCUT_ICON, Bitmap::class.java)
                        if (bitmap != null) {
                            iconDrawable = BitmapDrawable(resources, bitmap)
                        } else {
                            val iconRes = IntentCompat.getParcelableExtra(resultData, Intent.EXTRA_SHORTCUT_ICON_RESOURCE, Intent.ShortcutIconResource::class.java)
                            if (iconRes != null) {
                                try {
                                    val foreignResources = packageManager.getResourcesForApplication(iconRes.packageName)
                                    val id = foreignResources.getIdentifier(iconRes.resourceName, null, null)
                                    iconDrawable = foreignResources.getDrawable(id, null)
                                } catch (_: Exception) {}
                            }
                        }
                        if (iconDrawable == null) {
                            iconDrawable = ContextCompat.getDrawable(this, R.drawable.ic_shortcut_default_vector)
                        }
                        if (iconDrawable != null && (shortcutIntent != null || label.isNotEmpty())) {
                            val shortcutId = shortcutIntent?.toUri(0) ?: java.util.UUID.randomUUID().toString()
                            mActionOnAddShortcut?.invoke(shortcutId, label, iconDrawable)
                        }
                    }
                }
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        binding.allAppsFragment.root.onConfigurationChanged()
        binding.widgetsFragment.root.onConfigurationChanged()
    }

    override fun onTouchEvent(event: MotionEvent?): Boolean {
        if (event == null) {
            return false
        }

        if (mLongPressedIcon != null && event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            mLastUpEvent = System.currentTimeMillis()
        }

        try {
            mDetector.onTouchEvent(event)
            mScaleDetector.onTouchEvent(event)
        } catch (e: Exception) {
            logKeeper.log("MainActivity", "GestureDetector.onTouchEvent failed", e)
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                mPinchHandled = false
                mTouchDownX = event.x.toInt()
                mTouchDownY = event.y.toInt()
                val (viewX, viewY) = binding.homeScreenGrid.root.intoViewSpaceCoords(event.x, event.y)
                mTouchDownItem = binding.homeScreenGrid.root.isClickingGridItem(viewX.toInt(), viewY.toInt())
                mAllAppsFragmentY = binding.allAppsFragment.root.y.toInt()
                mWidgetsFragmentY = binding.widgetsFragment.root.y.toInt()
                mIgnoreUpEvent = false
            }

            MotionEvent.ACTION_MOVE -> {
                // if the initial gesture was handled by some other view, fix the Down values
                val hasFingerMoved = if (mTouchDownX == -1 || mTouchDownY == -1) {
                    val (viewX, viewY) = binding.homeScreenGrid.root.intoViewSpaceCoords(event.rawX, event.rawY)
                    mTouchDownX = viewX.toInt()
                    mTouchDownY = viewY.toInt()
                    mTouchDownItem = binding.homeScreenGrid.root.isClickingGridItem(viewX.toInt(), viewY.toInt())
                    false
                } else {
                    val (viewX, viewY) = binding.homeScreenGrid.root.intoViewSpaceCoords(event.rawX, event.rawY)
                    (abs(mTouchDownX - viewX.toInt()) > mMoveGestureThreshold || abs(mTouchDownY - viewY.toInt()) > mMoveGestureThreshold)
                }

                if (mLongPressedIcon != null && (mOpenPopupMenu != null) && hasFingerMoved) {
                    val iconToDrag = mLongPressedIcon
                    mOpenPopupMenu?.dismiss()
                    mOpenPopupMenu = null
                    if (iconToDrag != null) {
                        mLongPressedIcon = iconToDrag
                        binding.homeScreenGrid.root.itemDraggingStarted(iconToDrag)
                        hideFragment(binding.allAppsFragment)
                    }
                }

                if (mLongPressedIcon != null && hasFingerMoved) {
                    val (viewX, viewY) = binding.homeScreenGrid.root.intoViewSpaceCoords(event.rawX, event.rawY)
                    binding.homeScreenGrid.root.draggedItemMoved(viewX.toInt(), viewY.toInt())
                }

                if (hasFingerMoved && !mIgnoreMoveEvents) {
                    val diffY = mTouchDownY - event.y
                    val diffX = mTouchDownX - event.x

                    if (abs(diffY) > abs(diffX) && !mIgnoreYMoveEvents) {
                        mIgnoreXMoveEvents = true
                        if (isWidgetsFragmentExpanded()) {
                            val newY = mWidgetsFragmentY - diffY
                            binding.widgetsFragment.root.y = min(
                                a = max(0f, newY), b = mScreenHeight.toFloat()
                            )
                        } else if (mLongPressedIcon == null) {
                            val newY = mAllAppsFragmentY - diffY
                            val clampedY = min(
                                a = max(0f, newY), b = mScreenHeight.toFloat()
                            )
                            binding.allAppsFragment.root.y = clampedY
                            val progress = (1f - (clampedY / mScreenHeight.toFloat())).coerceIn(0f, 1f)
                            binding.homeScreenGrid.root.alpha = (1f - (progress * 1.05f)).coerceIn(0f, 1f)
                            val scale = 1f - (progress * 0.06f)
                            binding.homeScreenGrid.root.scaleX = scale
                            binding.homeScreenGrid.root.scaleY = scale
                        }
                    } else if (abs(diffX) > abs(diffY) && !mIgnoreXMoveEvents) {
                        val hasHorizontalGesture = mTouchDownItem != null && ItemGestureManager.hasHorizontalGesture(this, mTouchDownItem!!)
                        if (!hasHorizontalGesture) {
                            mIgnoreYMoveEvents = true
                            binding.homeScreenGrid.root.setSwipeMovement(diffX)
                        }
                    }
                }

                mLastTouchCoords = Pair(event.x, event.y)
            }

            MotionEvent.ACTION_CANCEL,
            MotionEvent.ACTION_UP -> {
                mPinchHandled = false
                mTouchDownX = -1
                mTouchDownY = -1
                mTouchDownItem = null
                mIgnoreMoveEvents = false
                mLongPressedIcon = null
                mLastTouchCoords = Pair(-1f, -1f)
                resetFragmentTouches()
                binding.homeScreenGrid.root.itemDraggingStopped()

                if (!mIgnoreUpEvent) {
                    if (!mIgnoreYMoveEvents) {
                        if (binding.allAppsFragment.root.y < mScreenHeight * 0.5) {
                            showFragment(binding.allAppsFragment)
                        } else if (isAllAppsFragmentExpanded()) {
                            hideFragment(binding.allAppsFragment)
                        }

                        if (binding.widgetsFragment.root.y < mScreenHeight * 0.5) {
                            showFragment(binding.widgetsFragment)
                        } else if (isWidgetsFragmentExpanded()) {
                            hideFragment(binding.widgetsFragment)
                        }
                    }

                    if (!mIgnoreXMoveEvents) {
                        binding.homeScreenGrid.root.finalizeSwipe()
                    }
                }

                mIgnoreXMoveEvents = false
                mIgnoreYMoveEvents = false
            }
        }

        return true
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(APP_DRAWER_STATE, isAllAppsFragmentExpanded())
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        if (savedInstanceState.getBoolean(APP_DRAWER_STATE)) {
            showFragment(binding.allAppsFragment, 0L)
        }
    }

    private fun handleIntentAction(intent: Intent) {
        if (intent.action == LauncherApps.ACTION_CONFIRM_PIN_SHORTCUT) {
            val launcherApps =
                applicationContext.getSystemService(LAUNCHER_APPS_SERVICE) as LauncherApps
            val item = launcherApps.getPinItemRequest(intent)
            val shortcutInfo = item?.shortcutInfo ?: return

            ensureBackgroundThread {
                val shortcutId = shortcutInfo.id
                val label = shortcutInfo.getLabel()
                val icon = launcherApps.getShortcutBadgedIconDrawable(
                    shortcutInfo,
                    resources.displayMetrics.densityDpi
                )
                val (page, rect) = findFirstEmptyCell()
                val gridItem = HomeScreenGridItem(
                    id = null,
                    left = rect.left,
                    top = rect.top,
                    right = rect.right,
                    bottom = rect.bottom,
                    page = page,
                    packageName = shortcutInfo.`package`,
                    activityName = "",
                    title = label,
                    type = ITEM_TYPE_SHORTCUT,
                    className = "",
                    widgetId = -1,
                    shortcutId = shortcutId,
                    icon = icon.toBitmap(),
                    docked = false,
                    parentId = null,
                    drawable = icon
                )

                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    binding.homeScreenGrid.root.skipToPage(page)
                    binding.root.postDelayed({
                        if (isFinishing || isDestroyed) return@postDelayed
                        try {
                            item.accept()
                            binding.homeScreenGrid.root.storeAndShowGridItem(gridItem)
                        } catch (e: IllegalStateException) {
                            logKeeper.log("MainActivity", "Pin shortcut request accept() failed", e)
                        }
                    }, 300)
                }
            }
        }
    }

    fun showAddSidebarElementPicker(x: Float? = null, y: Float? = null) {
        pendingSidebarElementPlacement = getPlacementCell(x, y)
        val pickerIntent = Intent().setClassName(
            packageName,
            "com.example.feature.settings.AddElementActivity"
        ).apply {
            putExtra("SIDEBAR_HOME_PICKER", true)
            putExtra("CONTAINER_ID", "sidebar")
        }

        try {
            startActivityForResult(pickerIntent, AddElementBridge.REQUEST_SIDEBAR_ELEMENT)
        } catch (e: Exception) {
            pendingSidebarElementPlacement = null
            LogCatcher.log("MainActivity", "Unable to open Sidebar element picker: " + e.message)
            toast("Unable to open Sidebar elements")
        }
    }

    private val sidebarElementPlacementLock = Any()

    private fun addSidebarElementToHome(
        elementId: String,
        displayName: String?,
        preferredPlacement: Pair<Int, Rect>?
    ) {
        val packageName = applicationContext.packageName
        val activityName = "com.example.feature.sidebar.SidebarElementLaunchActivity"

        // Resolve the authoritative label/icon/state in Sidebar's main process, and re-check
        // the original target cell against Room before inserting.
        ensureBackgroundThread {
            synchronized(sidebarElementPlacementLock) {
                if (isFinishing || isDestroyed) return@synchronized

                val (page, rect) = preferredPlacement?.let {
                    findNearestEmptyCellOnPage(it.first, it.second.left, it.second.top)
                } ?: findFirstEmptyCell()

                val elementInfo = shura.home.helpers.SidebarHomeElementClient
                    .load(this, listOf(elementId))[elementId]
                val title = displayName?.takeIf { it.isNotBlank() }
                    ?: elementInfo?.label?.takeIf { it.isNotBlank() }
                    ?: elementId.substringAfter(":").replace("_", " ").ifBlank { "Sidebar element" }
                val iconBitmap = elementInfo?.icon
                    ?: ContextCompat.getDrawable(this, android.R.drawable.ic_menu_view)?.toBitmap()
                val item = HomeScreenGridItem(
                    id = null,
                    left = rect.left,
                    top = rect.top,
                    right = rect.right,
                    bottom = rect.bottom,
                    page = page,
                    packageName = packageName,
                    activityName = activityName,
                    title = title,
                    type = ITEM_TYPE_ICON,
                    className = SIDEBAR_ELEMENT_CLASS_NAME,
                    widgetId = -1,
                    shortcutId = elementId,
                    // Keep provider imagery in the Drawable only. A non-null persisted
                    // bitmap is reserved for a user-customized launcher icon.
                    icon = null,
                    docked = false,
                    parentId = null,
                    drawable = shura.home.helpers.SidebarHomeElementIconDecorator.createDrawable(
                        this,
                        iconBitmap,
                        elementInfo?.isActive == true,
                        elementInfo?.glowColor ?: 0
                    )
                )
                binding.homeScreenGrid.root.storeAndShowGridItemFromBackground(item) {
                    if (!isFinishing && !isDestroyed) {
                        binding.homeScreenGrid.root.skipToPage(page)
                        LogCatcher.log("MainActivity", "Added Sidebar Home item: $elementId")
                    }
                }
            }
        }
    }

    private fun findNearestEmptyCellOnPage(
        page: Int,
        targetX: Int,
        targetY: Int
    ): Pair<Int, Rect> {
        val occupied = HashSet<Pair<Int, Int>>()
        homeScreenGridItemsDB.getAllItems()
            .filter { it.parentId == null && !it.docked && it.page == page }
            .forEach { item ->
                for (x in item.left..item.right) {
                    for (y in item.top..item.bottom) {
                        occupied.add(x to y)
                    }
                }
            }

        val nearest = (0 until config.homeRowCount - 1)
            .flatMap { y -> (0 until config.homeColumnCount).map { x -> x to y } }
            .filterNot { occupied.contains(it) }
            .minWithOrNull(
                compareBy<Pair<Int, Int>>(
                    { abs(it.first - targetX) + abs(it.second - targetY) },
                    { it.second },
                    { it.first }
                )
            )

        return if (nearest != null) {
            Pair(page, Rect(nearest.first, nearest.second, nearest.first, nearest.second))
        } else {
            findFirstEmptyCell()
        }
    }

    private fun findFirstEmptyCell(): Pair<Int, Rect> {
        val gridItems = homeScreenGridItemsDB.getAllItems()
        val maxPage = gridItems.maxOfOrNull { it.page } ?: 0
        val occupiedCells = ArrayList<Triple<Int, Int, Int>>()
        gridItems.toImmutableList().filter { it.parentId == null }.forEach { item ->
            for (xCell in item.left..item.right) {
                for (yCell in item.top..item.bottom) {
                    occupiedCells.add(Triple(item.page, xCell, yCell))
                }
            }
        }

        for (page in 0..maxPage) {
            for (checkedYCell in 0 until config.homeRowCount - 1) {
                for (checkedXCell in 0 until config.homeColumnCount) {
                    val wantedCell = Triple(page, checkedXCell, checkedYCell)
                    if (!occupiedCells.contains(wantedCell)) {
                        return Pair(
                            first = page,
                            second = Rect(
                                wantedCell.second,
                                wantedCell.third,
                                wantedCell.second,
                                wantedCell.third
                            )
                        )
                    }
                }
            }
        }

        return Pair(maxPage + 1, Rect(0, 0, 0, 0))
    }

    // some devices ACTION_MOVE keeps triggering for the whole long press duration, but we are interested in real moves only, when coords change
    private fun hasFingerMoved(event: MotionEvent): Boolean {
        return mTouchDownX != -1 && mTouchDownY != -1 &&
                (abs(mTouchDownX - event.x) > mMoveGestureThreshold || abs(mTouchDownY - event.y) > mMoveGestureThreshold)
    }

    private fun refreshLaunchers() {
        val launchers = getAllAppLaunchers()
        binding.allAppsFragment.root.gotLaunchers(launchers)
        binding.widgetsFragment.root.getAppWidgets()

        IconCache.launchers.map { it.packageName }.forEach { packageName ->
            if (!launchers.map { it.packageName }.contains(packageName)) {
                launchersDB.deleteApp(packageName)
                homeScreenGridItemsDB.deleteByPackageName(packageName)
                AppLockManager.unlockApp(this, packageName)
            }
        }

        IconCache.launchers = launchers

        if (!config.wasHomeScreenInit) {
            ensureBackgroundThread {
                getDefaultAppPackages(launchers)
                config.wasHomeScreenInit = true
                config.wasDefaultClockAdded = true
                binding.homeScreenGrid.root.fetchGridItems()
            }
        } else {
            binding.homeScreenGrid.root.fetchGridItems()
        }
    }

    fun isAllAppsFragmentExpanded() = binding.allAppsFragment.root.y != mScreenHeight.toFloat()

    private fun isWidgetsFragmentExpanded() =
        binding.widgetsFragment.root.y != mScreenHeight.toFloat()

    fun startHandlingTouches(touchDownY: Int) {
        mLongPressedIcon = null
        mTouchDownY = touchDownY
        mAllAppsFragmentY = binding.allAppsFragment.root.y.toInt()
        mWidgetsFragmentY = binding.widgetsFragment.root.y.toInt()
        mIgnoreUpEvent = false
    }

    private fun showFragment(fragment: ViewBinding, animationDuration: Long = ANIMATION_DURATION) {
        val fluidInterpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
        ObjectAnimator.ofFloat(fragment.root, "y", 0f).apply {
            duration = animationDuration
            interpolator = fluidInterpolator
            start()
        }

        window.statusBarColor = Color.TRANSPARENT
        if (isQPlus()) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
        window.navigationBarColor = resources.getColor(R.color.semitransparent_navigation)
        binding.homeScreenGrid.root.fragmentExpanded()
        binding.homeScreenGrid.root.hideResizeLines()
        if (fragment is AllAppsFragmentBinding) {
            ObjectAnimator.ofFloat(binding.homeScreenGrid.root, "alpha", 0f).apply {
                duration = animationDuration
                interpolator = fluidInterpolator
                start()
            }
            ObjectAnimator.ofFloat(binding.homeScreenGrid.root, "scaleX", 0.94f).apply {
                duration = animationDuration
                interpolator = fluidInterpolator
                start()
            }
            ObjectAnimator.ofFloat(binding.homeScreenGrid.root, "scaleY", 0.94f).apply {
                duration = animationDuration
                interpolator = fluidInterpolator
                start()
            }
            updateStatusBarIcons(Color.BLACK)
        }

        @SuppressLint("AccessibilityFocus")
        fragment.root.performAccessibilityAction(
            AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS,
            null
        )

        if (
            fragment is AllAppsFragmentBinding
            && config.showSearchBar
            && config.autoShowKeyboardInAppDrawer
        ) {
            fragment.root.post {
                fragment.root.openSearchMode()
            }
        }

        Handler(Looper.getMainLooper()).postDelayed({
            updateStatusBarIcons(getProperBackgroundColor())
        }, animationDuration)
    }

    private fun hideFragment(fragment: ViewBinding, animationDuration: Long = ANIMATION_DURATION) {
        val fluidInterpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
        ObjectAnimator.ofFloat(fragment.root, "y", mScreenHeight.toFloat()).apply {
            duration = animationDuration
            interpolator = fluidInterpolator
            start()
        }

        window.statusBarColor = Color.TRANSPARENT
        if (isQPlus()) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
        window.navigationBarColor = Color.TRANSPARENT
        binding.homeScreenGrid.root.fragmentCollapsed()
        if (fragment is AllAppsFragmentBinding) {
            ObjectAnimator.ofFloat(binding.homeScreenGrid.root, "alpha", 1f).apply {
                duration = animationDuration
                interpolator = fluidInterpolator
                start()
            }
            ObjectAnimator.ofFloat(binding.homeScreenGrid.root, "scaleX", 1f).apply {
                duration = animationDuration
                interpolator = fluidInterpolator
                start()
            }
            ObjectAnimator.ofFloat(binding.homeScreenGrid.root, "scaleY", 1f).apply {
                duration = animationDuration
                interpolator = fluidInterpolator
                start()
            }
            refreshHomeScreenBadges()
        }
        updateStatusBarIcons()
        if (fragment is WidgetsFragmentBinding) {
            clearWidgetsSearch()
        } else if (fragment is AllAppsFragmentBinding) {
            fragment.root.closeSearchMode()
        }
        Handler(Looper.getMainLooper()).postDelayed({
            if (fragment is AllAppsFragmentBinding) {
                fragment.allAppsGrid.scrollToPosition(0)
                fragment.root.touchDownY = -1
            } else if (fragment is WidgetsFragmentBinding) {
                fragment.widgetsList.scrollToPosition(0)
                fragment.root.touchDownY = -1
            }
        }, animationDuration)
    }

    fun refreshHomeScreenBadges() {
        if (config.showNotificationBadges && NotificationBadgeService.isNotificationAccessGranted(this)) {
            val badges = NotificationBadgeService.getActiveBadgeCounts(this)
            binding.homeScreenGrid.root.updateNotificationBadges(badges)
        } else {
            binding.homeScreenGrid.root.updateNotificationBadges(emptyMap())
        }
        binding.homeScreenGrid.root.invalidate()
    }

    fun homeScreenLongPressed(eventX: Float, eventY: Float) {
        if (isAllAppsFragmentExpanded() || isWidgetsFragmentExpanded()) {
            return
        }

        val (x, y) = binding.homeScreenGrid.root.intoViewSpaceCoords(eventX, eventY)
        mIgnoreMoveEvents = true
        val clickedGridItem = binding.homeScreenGrid.root.isClickingGridItem(x.toInt(), y.toInt())
        if (clickedGridItem != null) {
            performItemLongClick(x, clickedGridItem)
            return
        }

        binding.mainHolder.performHapticFeedback()
        showMainLongPressMenu(x, y)
    }

    fun homeScreenClicked(eventX: Float, eventY: Float) {
        val (x, y) = binding.homeScreenGrid.root.intoViewSpaceCoords(eventX, eventY)
        val openFolderItem = binding.homeScreenGrid.root.getCurrentlyOpenFolderItem()
        if (openFolderItem != null) {
            if (binding.homeScreenGrid.root.isClickingFolderAddButton(x, y)) {
                showAddAppToFolderDialog(openFolderItem)
                return
            }
            if (binding.homeScreenGrid.root.isClickingFolderHeader(x, y)) {
                RenameItemDialog(this, openFolderItem) {
                    binding.homeScreenGrid.root.redrawGrid()
                }
                return
            }
        }
        binding.homeScreenGrid.root.hideResizeLines()
        val clickedGridItem = binding.homeScreenGrid.root.isClickingGridItem(x.toInt(), y.toInt())
        if (clickedGridItem != null) {
            performItemClick(clickedGridItem)
        }
        if (clickedGridItem?.type != ITEM_TYPE_FOLDER) {
            binding.homeScreenGrid.root.closeFolder(redraw = true)
        }
    }

    fun homeScreenDoubleTapped(eventX: Float, eventY: Float): Boolean {
        val (x, y) = binding.homeScreenGrid.root.intoViewSpaceCoords(eventX, eventY)
        val clickedGridItem = binding.homeScreenGrid.root.isClickingGridItem(x.toInt(), y.toInt())
        if (clickedGridItem != null) {
            // App icons, folders, and widgets do not intercept double tap, guaranteeing 0ms launch responsiveness
            return false
        }

        val emptyDoubleTapAction = config.gestureDoubleTapAction
        if (emptyDoubleTapAction != LauncherActionHandler.ACTION_NONE) {
            if (config.gestureHaptics) {
                binding.mainHolder.performHapticFeedback()
            }
            return LauncherActionHandler.executeAction(this, emptyDoubleTapAction)
        }
        return false
    }

    fun openAppDrawer() {
        showFragment(binding.allAppsFragment)
    }

    fun openFolderPopup(folder: HomeScreenGridItem) {
        binding.homeScreenGrid.root.openFolder(folder)
    }

    fun showItemShortcuts(item: HomeScreenGridItem) {
        val rect = binding.homeScreenGrid.root.getClickableRect(item)
        val centerX = (rect.left + rect.right) / 2f
        performItemLongClick(centerX, item)
    }

    fun handleItemFlingUp(item: HomeScreenGridItem): Boolean {
        val action = ItemGestureManager.getGestureAction(this, item, ItemGestureManager.GESTURE_SWIPE_UP)
        if (action != LauncherActionHandler.ACTION_NONE) {
            if (config.gestureHaptics) {
                binding.mainHolder.performHapticFeedback()
            }
            val targetPkg = ItemGestureManager.getTargetPackage(this, item, ItemGestureManager.GESTURE_SWIPE_UP)
            return LauncherActionHandler.executeAction(this, action, item, targetPkg)
        }
        if (config.folderCoverMode && item.type == ITEM_TYPE_FOLDER) {
            if (config.gestureHaptics) {
                binding.mainHolder.performHapticFeedback()
            }
            openFolder(item)
            return true
        }
        return false
    }

    fun handleItemFlingDown(item: HomeScreenGridItem): Boolean {
        val action = ItemGestureManager.getGestureAction(this, item, ItemGestureManager.GESTURE_SWIPE_DOWN)
        if (action != LauncherActionHandler.ACTION_NONE) {
            if (config.gestureHaptics) {
                binding.mainHolder.performHapticFeedback()
            }
            val targetPkg = ItemGestureManager.getTargetPackage(this, item, ItemGestureManager.GESTURE_SWIPE_DOWN)
            return LauncherActionHandler.executeAction(this, action, item, targetPkg)
        }
        return false
    }

    fun handleItemFlingLeft(item: HomeScreenGridItem): Boolean {
        val action = ItemGestureManager.getGestureAction(this, item, ItemGestureManager.GESTURE_SWIPE_LEFT)
        if (action != LauncherActionHandler.ACTION_NONE) {
            if (config.gestureHaptics) {
                binding.mainHolder.performHapticFeedback()
            }
            val targetPkg = ItemGestureManager.getTargetPackage(this, item, ItemGestureManager.GESTURE_SWIPE_LEFT)
            return LauncherActionHandler.executeAction(this, action, item, targetPkg)
        }
        return false
    }

    fun handleItemFlingRight(item: HomeScreenGridItem): Boolean {
        val action = ItemGestureManager.getGestureAction(this, item, ItemGestureManager.GESTURE_SWIPE_RIGHT)
        if (action != LauncherActionHandler.ACTION_NONE) {
            if (config.gestureHaptics) {
                binding.mainHolder.performHapticFeedback()
            }
            val targetPkg = ItemGestureManager.getTargetPackage(this, item, ItemGestureManager.GESTURE_SWIPE_RIGHT)
            return LauncherActionHandler.executeAction(this, action, item, targetPkg)
        }
        return false
    }

    fun closeAppDrawer(delayed: Boolean = false) {
        if (isAllAppsFragmentExpanded()) {
            val close = {
                binding.allAppsFragment.root.y = mScreenHeight.toFloat()
                binding.homeScreenGrid.root.alpha = 1f
                binding.homeScreenGrid.root.scaleX = 1f
                binding.homeScreenGrid.root.scaleY = 1f
                binding.allAppsFragment.allAppsGrid.scrollToPosition(0)
                binding.allAppsFragment.root.touchDownY = -1
                binding.allAppsFragment.root.closeSearchMode()
                binding.homeScreenGrid.root.fragmentCollapsed()
                window.statusBarColor = Color.TRANSPARENT
                if (isQPlus()) {
                    window.isStatusBarContrastEnforced = false
                    window.isNavigationBarContrastEnforced = false
                }
                updateStatusBarIcons()
            }
            if (delayed) {
                Handler(Looper.getMainLooper()).postDelayed(close, APP_DRAWER_CLOSE_DELAY)
            } else {
                close()
            }
        }
    }

    fun closeWidgetsFragment(delayed: Boolean = false) {
        if (isWidgetsFragmentExpanded()) {
            val close = {
                binding.widgetsFragment.root.y = mScreenHeight.toFloat()
                binding.widgetsFragment.widgetsList.scrollToPosition(0)
                clearWidgetsSearch()
                binding.widgetsFragment.root.touchDownY = -1
                binding.homeScreenGrid.root.fragmentCollapsed()
                updateStatusBarIcons()
            }
            if (delayed) {
                Handler(Looper.getMainLooper()).postDelayed(close, APP_DRAWER_CLOSE_DELAY)
            } else {
                close()
            }
        }
    }

    fun clearWidgetsSearch() {
        binding.widgetsFragment.searchBar.closeSearch()
    }

    private fun performItemClick(clickedGridItem: HomeScreenGridItem) {
        when (clickedGridItem.type) {
            ITEM_TYPE_ICON -> {
                if (clickedGridItem.className == SIDEBAR_ELEMENT_CLASS_NAME) {
                    val lockKey = AppLockManager.getLockKey(clickedGridItem)
                    val launchElement = {
                        try {
                            val intent = Intent().setClassName(
                                clickedGridItem.packageName,
                                clickedGridItem.activityName
                            ).putExtra("ELEMENT_ID", clickedGridItem.shortcutId)
                            startActivity(intent)
                        } catch (e: Exception) {
                            logKeeper.log("MainActivity", "Failed to open Sidebar element", e)
                            toast("Unable to open this Sidebar element")
                        }
                    }
                    if (AppLockManager.canLaunchWithoutAuth(this, lockKey)) {
                        launchElement()
                    } else {
                        AppLockManager.authenticateAndLaunch(
                            this,
                            lockKey,
                            clickedGridItem.title,
                            onError = { toast("Authentication required to open this element") },
                            onSuccess = launchElement
                        )
                    }
                    return
                }
                val itemId = clickedGridItem.id
                if (itemId != null && ItemGestureManager.getLinkedWidget(this, itemId) != null && clickedGridItem.activityName.isEmpty()) {
                    openPopupWidget(clickedGridItem)
                } else {
                    launchApp(clickedGridItem.packageName, clickedGridItem.activityName, clickedGridItem.title)
                }
            }
            ITEM_TYPE_FOLDER -> {
                if (config.folderCoverMode && clickedGridItem.id != null) {
                    ensureBackgroundThread {
                        val children = homeScreenGridItemsDB.getFolderItems(clickedGridItem.id!!)
                        val firstChild = children.firstOrNull()
                        runOnUiThread {
                            if (firstChild != null) {
                                performItemClick(firstChild)
                            } else {
                                openFolder(clickedGridItem)
                            }
                        }
                    }
                } else {
                    openFolder(clickedGridItem)
                }
            }
            ITEM_TYPE_SHORTCUT -> {
                val id = clickedGridItem.shortcutId
                val packageName = clickedGridItem.packageName
                val userHandle = android.os.Process.myUserHandle()
                val shortcutBounds = binding.homeScreenGrid.root.getClickableRect(clickedGridItem)
                val launcherApps =
                    applicationContext.getSystemService(LAUNCHER_APPS_SERVICE) as LauncherApps
                if (!AppLockManager.canLaunchWithoutAuth(this, packageName)) {
                    AppLockManager.authenticateAndLaunch(this, packageName, clickedGridItem.title) {
                        try {
                            launcherApps.startShortcut(packageName, id, shortcutBounds, null, userHandle)
                        } catch (e: Exception) {
                            showErrorToast(e)
                        }
                    }
                } else {
                    try {
                        launcherApps.startShortcut(packageName, id, shortcutBounds, null, userHandle)
                    } catch (e: Exception) {
                        showErrorToast(e)
                    }
                }
            }
        }
    }

    private fun openFolder(folder: HomeScreenGridItem) {
        binding.homeScreenGrid.root.openFolder(folder)
    }

    fun openPopupWidget(item: HomeScreenGridItem) {
        val itemId = item.id ?: return
        shura.home.helpers.LogCatcher.log("PopupWidget", "Opening popup widget for item $itemId (${item.title})")
        val linked = ItemGestureManager.getLinkedWidget(this, itemId)
        val appWidgetManager = AppWidgetManager.getInstance(this)

        if (linked != null) {
            val (widgetId, _) = linked
            val providerInfo = appWidgetManager.getAppWidgetInfo(widgetId)
            if (providerInfo != null) {
                showPopupWidgetDialog(item, widgetId, providerInfo)
            } else {
                ItemGestureManager.unlinkWidget(this, itemId)
                toast(R.string.widget_unlinked)
                showSelectPopupWidgetDialog(item)
            }
        } else {
            showSelectPopupWidgetDialog(item)
        }
    }

    fun showSelectPopupWidgetDialog(item: HomeScreenGridItem) {
        SelectPopupWidgetDialog(this, item) { selectedProvider ->
            bindAndLinkPopupWidget(item, selectedProvider)
        }.show()
    }

    fun bindAndLinkPopupWidget(item: HomeScreenGridItem, providerInfo: AppWidgetProviderInfo) {
        val itemId = item.id ?: return
        val appWidgetHost = binding.homeScreenGrid.root.appWidgetHost
        val appWidgetManager = AppWidgetManager.getInstance(this)
        val newWidgetId = appWidgetHost.allocateAppWidgetId()

        handleWidgetBinding(appWidgetManager, newWidgetId, providerInfo) { canBind ->
            if (canBind) {
                if (providerInfo.configure != null) {
                    handleWidgetConfigureScreen(appWidgetHost, newWidgetId) { configured ->
                        if (configured) {
                            finalizePopupWidgetLink(item, itemId, newWidgetId, providerInfo)
                        } else {
                            appWidgetHost.deleteAppWidgetId(newWidgetId)
                        }
                    }
                } else {
                    finalizePopupWidgetLink(item, itemId, newWidgetId, providerInfo)
                }
            } else {
                appWidgetHost.deleteAppWidgetId(newWidgetId)
            }
        }
    }

    private fun finalizePopupWidgetLink(
        item: HomeScreenGridItem,
        itemId: Long,
        widgetId: Int,
        providerInfo: AppWidgetProviderInfo
    ) {
        val oldWidgetId = ItemGestureManager.unlinkWidget(this, itemId)
        if (oldWidgetId != -1 && oldWidgetId != widgetId) {
            binding.homeScreenGrid.root.appWidgetHost.deleteAppWidgetId(oldWidgetId)
        }

        ItemGestureManager.setLinkedWidget(
            this,
            itemId,
            widgetId,
            providerInfo.provider.className
        )
        toast(R.string.widget_linked)
        showPopupWidgetDialog(item, widgetId, providerInfo)
    }

    private fun showPopupWidgetDialog(
        item: HomeScreenGridItem,
        widgetId: Int,
        providerInfo: AppWidgetProviderInfo
    ) {
        PopupWidgetDialog(
            activity = this,
            appWidgetHost = binding.homeScreenGrid.root.appWidgetHost,
            targetItem = item,
            widgetId = widgetId,
            providerInfo = providerInfo,
            onReconfigure = {
                handleWidgetConfigureScreen(binding.homeScreenGrid.root.appWidgetHost, widgetId) { success ->
                    if (success) {
                        showPopupWidgetDialog(item, widgetId, providerInfo)
                    }
                }
            },
            onChangeWidget = {
                showSelectPopupWidgetDialog(item)
            }
        ).show()
    }

    fun unlinkPopupWidget(item: HomeScreenGridItem) {
        val itemId = item.id ?: return
        val oldWidgetId = ItemGestureManager.unlinkWidget(this, itemId)
        if (oldWidgetId != -1) {
            binding.homeScreenGrid.root.appWidgetHost.deleteAppWidgetId(oldWidgetId)
            toast(R.string.widget_unlinked)
        }
    }

    private fun performItemLongClick(x: Float, clickedGridItem: HomeScreenGridItem) {
        if (clickedGridItem.type == ITEM_TYPE_ICON || clickedGridItem.type == ITEM_TYPE_SHORTCUT || clickedGridItem.type == ITEM_TYPE_FOLDER) {
            binding.mainHolder.performHapticFeedback()
        }

        val anchorY = binding.homeScreenGrid.root.sideMargins.top +
                (clickedGridItem.top * binding.homeScreenGrid.root.cellHeight.toFloat())
        showHomeIconMenu(x, anchorY, clickedGridItem, false)
    }

    fun showHomeIconMenu(
        x: Float,
        y: Float,
        gridItem: HomeScreenGridItem,
        isOnAllAppsFragment: Boolean,
        customIconRect: Rect? = null,
    ) {
        if (gridItem.type != ITEM_TYPE_WIDGET) {
            binding.homeScreenGrid.root.hideResizeLines()
        }
        mLongPressedIcon = gridItem
        val clickableRect = customIconRect ?: if (isOnAllAppsFragment || gridItem.type == ITEM_TYPE_WIDGET) {
            val iconSize = (realScreenSize.x / config.drawerColumnCount).toInt()
            Rect(
                (x - iconSize / 2f).toInt(),
                (y - iconSize / 2f).toInt(),
                (x + iconSize / 2f).toInt(),
                (y + iconSize / 2f).toInt()
            )
        } else {
            binding.homeScreenGrid.root.getClickableRect(gridItem)
        }

        binding.homeScreenPopupMenuAnchor.x = clickableRect.exactCenterX()
        binding.homeScreenPopupMenuAnchor.y = clickableRect.top.toFloat()

        if (mOpenPopupMenu == null) {
            mOpenPopupMenu = handleGridItemPopupMenu(
                anchorView = binding.homeScreenPopupMenuAnchor,
                gridItem = gridItem,
                isOnAllAppsFragment = isOnAllAppsFragment,
                listener = menuListener,
                iconRect = clickableRect
            )
        }
    }

    fun handleWidgetDrag(item: HomeScreenGridItem, event: MotionEvent, isUp: Boolean) {
        val (viewX, viewY) = binding.homeScreenGrid.root.intoViewSpaceCoords(event.rawX, event.rawY)

        if (!isUp) {
            if (mTouchDownX == -1 || mTouchDownY == -1) {
                mTouchDownX = event.rawX.toInt()
                mTouchDownY = event.rawY.toInt()
            }

            if (mOpenPopupMenu != null) {
                mOpenPopupMenu?.dismiss()
                mOpenPopupMenu = null
            }

            if (!binding.homeScreenGrid.root.isDraggingItem()) {
                mLongPressedIcon = item
                binding.homeScreenGrid.root.hideResizeLines()
                binding.homeScreenGrid.root.itemDraggingStarted(item)
                hideFragment(binding.allAppsFragment)
            }
            binding.homeScreenGrid.root.draggedItemMoved(viewX.toInt(), viewY.toInt())
        } else {
            mTouchDownX = -1
            mTouchDownY = -1
            if (binding.homeScreenGrid.root.isDraggingItem() || mLongPressedIcon != null) {
                binding.homeScreenGrid.root.itemDraggingStopped()
                mLongPressedIcon = null
            }
        }
    }

    fun pinShortcutToHome(shortcutInfo: android.content.pm.ShortcutInfo) {
        val launcherApps =
            applicationContext.getSystemService(LAUNCHER_APPS_SERVICE) as LauncherApps
        ensureBackgroundThread {
            val shortcutId = shortcutInfo.id
            val label = shortcutInfo.getLabel()
            val icon = try {
                launcherApps.getShortcutBadgedIconDrawable(
                    shortcutInfo,
                    resources.displayMetrics.densityDpi
                )
            } catch (e: Exception) {
                null
            } ?: try {
                launcherApps.getShortcutIconDrawable(
                    shortcutInfo,
                    resources.displayMetrics.densityDpi
                )
            } catch (e: Exception) {
                null
            }
            val (page, rect) = findFirstEmptyCell()
            val gridItem = HomeScreenGridItem(
                id = null,
                left = rect.left,
                top = rect.top,
                right = rect.right,
                bottom = rect.bottom,
                page = page,
                packageName = shortcutInfo.`package`,
                activityName = "",
                title = label,
                type = ITEM_TYPE_SHORTCUT,
                className = "",
                widgetId = -1,
                shortcutId = shortcutId,
                icon = icon?.toBitmap(),
                docked = false,
                parentId = null,
                drawable = icon
            )

            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                binding.homeScreenGrid.root.skipToPage(page)
                binding.root.postDelayed({
                    if (isFinishing || isDestroyed) return@postDelayed
                    binding.homeScreenGrid.root.storeAndShowGridItem(gridItem)
                    toast(R.string.shortcut_pinned)
                }, 300)
            }
        }
    }

    fun widgetLongPressedOnList(gridItem: HomeScreenGridItem) {
        mLongPressedIcon = gridItem
        hideFragment(binding.widgetsFragment)
        binding.homeScreenGrid.root.itemDraggingStarted(mLongPressedIcon!!)
    }

    private fun showMainLongPressMenu(x: Float, y: Float) {
        binding.homeScreenGrid.root.hideResizeLines()
        binding.homeScreenPopupMenuAnchor.x = x
        binding.homeScreenPopupMenuAnchor.y =
            y - resources.getDimension(R.dimen.long_press_anchor_button_offset_y) * 2
        val contextTheme = ContextThemeWrapper(this, R.style.Theme_App_PopupMenu_WhiteSky)
        PopupMenu(
            contextTheme,
            binding.homeScreenPopupMenuAnchor,
            Gravity.TOP or Gravity.END
        ).apply {
            inflate(R.menu.menu_home_screen)
            menu.findItem(R.id.set_as_default).isVisible = !isDefaultLauncher()
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.add_to_home -> AddElementBridge.open(this@MainActivity, x, y)
                    R.id.wallpapers -> launchWallpapersIntent()
                    R.id.launcher_settings -> launchSettings()
                    R.id.set_as_default -> launchSetAsDefaultIntent()
                }
                true
            }
            show()
        }
    }

    fun getPlacementCell(x: Float? = null, y: Float? = null): Pair<Int, Rect> {
        val targetCell = if (x != null && y != null) {
            binding.homeScreenGrid.root.findNearestEmptyCell(x, y)
                ?: binding.homeScreenGrid.root.findFirstEmptyCellOnCurrentPage()
        } else {
            binding.homeScreenGrid.root.findFirstEmptyCellOnCurrentPage()
        }
        return if (targetCell != null) {
            Pair(
                binding.homeScreenGrid.root.getCurrentPage(),
                Rect(targetCell.x, targetCell.y, targetCell.x, targetCell.y)
            )
        } else {
            findFirstEmptyCell()
        }
    }

    fun showAddAppDialog(x: Float? = null, y: Float? = null) {
        ensureBackgroundThread {
            val apps = getAllAppLaunchers()
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                AddAppDialog(this, apps) { selectedApp ->
                    val (page, rect) = getPlacementCell(x, y)
                    val isDock = rect.top == config.homeRowCount - 1
                    val gridItem = HomeScreenGridItem(
                        id = null,
                        left = rect.left,
                        top = rect.top,
                        right = rect.right,
                        bottom = rect.bottom,
                        page = if (isDock) 0 else page,
                        packageName = selectedApp.packageName,
                        activityName = selectedApp.activityName,
                        title = selectedApp.title,
                        type = ITEM_TYPE_ICON,
                        className = "",
                        widgetId = -1,
                        shortcutId = "",
                        icon = selectedApp.drawable?.toBitmap(),
                        docked = isDock,
                        parentId = null,
                        drawable = selectedApp.drawable
                    )

                    ensureBackgroundThread {
                        binding.homeScreenGrid.root.storeAndShowGridItem(gridItem)
                        runOnUiThread {
                            if (!isDock && page != binding.homeScreenGrid.root.getCurrentPage()) {
                                binding.homeScreenGrid.root.skipToPage(page)
                            }
                            toast(getString(R.string.app_added_to_home, selectedApp.title))
                        }
                    }
                }
            }
        }
    }

    fun showAddAppToFolderDialog(folderItem: HomeScreenGridItem) {
        ensureBackgroundThread {
            val apps = getAllAppLaunchers()
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                AddAppDialog(this, apps) { selectedApp ->
                    binding.homeScreenGrid.root.addAppToFolder(folderItem, selectedApp)
                    toast(getString(R.string.app_added_to_home, selectedApp.title))
                }
            }
        }
    }

    fun showAddShortcutDialog(x: Float? = null, y: Float? = null) {
        val intent = Intent(Intent.ACTION_CREATE_SHORTCUT, null)
        val resolveInfos = packageManager.queryIntentActivities(intent, PackageManager.PERMISSION_GRANTED)
            .filter { it.activityInfo != null && it.activityInfo.exported }
        if (resolveInfos.isEmpty()) {
            toast(R.string.no_shortcuts_available)
            return
        }

        val shortcutItems = resolveInfos.map { info ->
            val appTitle = info.activityInfo.applicationInfo.loadLabel(packageManager).toString()
            val shortcutTitle = info.loadLabel(packageManager).toString()
            val icon = info.loadIcon(packageManager)
            ShortcutItem(shortcutTitle, appTitle, icon, info.activityInfo)
        }

        AddShortcutDialog(this, shortcutItems) { selectedShortcut ->
            handleShorcutCreation(selectedShortcut.activityInfo) { shortcutId, label, icon ->
                val (page, rect) = getPlacementCell(x, y)
                val isDock = rect.top == config.homeRowCount - 1
                val gridItem = HomeScreenGridItem(
                    id = null,
                    left = rect.left,
                    top = rect.top,
                    right = rect.right,
                    bottom = rect.bottom,
                    page = if (isDock) 0 else page,
                    packageName = selectedShortcut.activityInfo.packageName,
                    activityName = "",
                    title = label,
                    type = ITEM_TYPE_SHORTCUT,
                    className = "",
                    widgetId = -1,
                    shortcutId = shortcutId,
                    icon = icon.toBitmap(),
                    docked = isDock,
                    parentId = null,
                    drawable = icon
                )

                ensureBackgroundThread {
                    binding.homeScreenGrid.root.storeAndShowGridItem(gridItem)
                    runOnUiThread {
                        if (!isDock && page != binding.homeScreenGrid.root.getCurrentPage()) {
                            binding.homeScreenGrid.root.skipToPage(page)
                        }
                        toast(R.string.shortcut_pinned)
                    }
                }
            }
        }
    }

    fun showAddFolderDialog(x: Float? = null, y: Float? = null) {
        CreateFolderDialog(this) { folderName ->
            val (page, rect) = getPlacementCell(x, y)
            val isDock = rect.top == config.homeRowCount - 1
            val gridItem = HomeScreenGridItem(
                id = null,
                left = rect.left,
                top = rect.top,
                right = rect.right,
                bottom = rect.bottom,
                page = if (isDock) 0 else page,
                packageName = "",
                activityName = "",
                title = folderName,
                type = ITEM_TYPE_FOLDER,
                className = "",
                widgetId = -1,
                shortcutId = "",
                icon = null,
                docked = isDock,
                parentId = null,
                drawable = null
            )

            ensureBackgroundThread {
                binding.homeScreenGrid.root.storeAndShowGridItem(gridItem)
                runOnUiThread {
                    if (!isDock && page != binding.homeScreenGrid.root.getCurrentPage()) {
                        binding.homeScreenGrid.root.skipToPage(page)
                    }
                    toast(R.string.folder_created)
                }
            }
        }
    }

    private fun showAddFolderDialogAtPlacement(preferredPlacement: Pair<Int, Rect>?) {
        CreateFolderDialog(this) { folderName ->
            ensureBackgroundThread {
                val (page, rect) = preferredPlacement?.let {
                    findNearestEmptyCellOnPage(it.first, it.second.left, it.second.top)
                } ?: findFirstEmptyCell()
                val isDock = rect.top == config.homeRowCount - 1
                val gridItem = HomeScreenGridItem(
                    id = null,
                    left = rect.left,
                    top = rect.top,
                    right = rect.right,
                    bottom = rect.bottom,
                    page = if (isDock) 0 else page,
                    packageName = "",
                    activityName = "",
                    title = folderName,
                    type = ITEM_TYPE_FOLDER,
                    className = "",
                    widgetId = -1,
                    shortcutId = "",
                    icon = null,
                    docked = isDock,
                    parentId = null,
                    drawable = null
                )
                binding.homeScreenGrid.root.storeAndShowGridItemFromBackground(gridItem) {
                    if (!isFinishing && !isDestroyed) {
                        binding.homeScreenGrid.root.skipToPage(page)
                        toast(R.string.folder_created)
                    }
                }
            }
        }
    }

    fun showAddStandalonePopupWidgetDialog(x: Float? = null, y: Float? = null) {
        SelectPopupWidgetDialog(this, HomeScreenGridItem()) { selectedProvider ->
            placeStandalonePopupWidget(x, y, selectedProvider)
        }.show()
    }

    private fun placeStandalonePopupWidget(
        x: Float? = null,
        y: Float? = null,
        providerInfo: AppWidgetProviderInfo
    ) {
        val appWidgetHost = binding.homeScreenGrid.root.appWidgetHost
        val appWidgetManager = AppWidgetManager.getInstance(this)
        val newWidgetId = appWidgetHost.allocateAppWidgetId()

        handleWidgetBinding(appWidgetManager, newWidgetId, providerInfo) { canBind ->
            if (canBind) {
                if (providerInfo.configure != null) {
                    handleWidgetConfigureScreen(appWidgetHost, newWidgetId) { configured ->
                        if (configured) {
                            finalizeStandalonePopupWidgetPlacement(x, y, newWidgetId, providerInfo)
                        } else {
                            appWidgetHost.deleteAppWidgetId(newWidgetId)
                        }
                    }
                } else {
                    finalizeStandalonePopupWidgetPlacement(x, y, newWidgetId, providerInfo)
                }
            } else {
                appWidgetHost.deleteAppWidgetId(newWidgetId)
            }
        }
    }

    private fun finalizeStandalonePopupWidgetPlacement(
        x: Float?,
        y: Float?,
        widgetId: Int,
        providerInfo: AppWidgetProviderInfo
    ) {
        val (page, rect) = getPlacementCell(x, y)
        val packageManager = packageManager
        val pkg = providerInfo.provider.packageName
        val label = providerInfo.loadLabel(packageManager).ifEmpty {
            try {
                packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
            } catch (e: Exception) {
                pkg
            }
        }

        val preview = try {
            providerInfo.loadPreviewImage(this, resources.displayMetrics.densityDpi)
                ?: packageManager.getApplicationIcon(pkg)
        } catch (e: Exception) {
            ContextCompat.getDrawable(this, R.drawable.ic_widget_vector)
        }

        val gridItem = HomeScreenGridItem(
            id = null,
            left = rect.left,
            top = rect.top,
            right = rect.right,
            bottom = rect.bottom,
            page = page,
            packageName = pkg,
            activityName = "",
            title = label,
            type = ITEM_TYPE_ICON,
            className = providerInfo.provider.className,
            widgetId = widgetId,
            shortcutId = "",
            icon = preview?.toBitmap(),
            docked = false,
            parentId = null,
            drawable = preview
        )

        ensureBackgroundThread {
            binding.homeScreenGrid.root.storeAndShowGridItem(gridItem)
            if (gridItem.id != null) {
                ItemGestureManager.setLinkedWidget(
                    this,
                    gridItem.id!!,
                    widgetId,
                    providerInfo.provider.className
                )
            }
            runOnUiThread {
                if (page != binding.homeScreenGrid.root.getCurrentPage()) {
                    binding.homeScreenGrid.root.skipToPage(page)
                }
                toast(R.string.popup_widget_added_to_home)
            }
        }
    }

    private fun resetFragmentTouches() {
        binding.widgetsFragment.root.apply {
            touchDownY = -1
            ignoreTouches = false
        }

        binding.allAppsFragment.root.apply {
            touchDownY = -1
            ignoreTouches = false
        }
    }

    fun showWidgetsFragment() {
        showFragment(binding.widgetsFragment)
    }

    private fun hideIcon(item: HomeScreenGridItem) {
        ensureBackgroundThread {
            val hiddenIcon = HiddenIcon(null, item.packageName, item.activityName, item.title, null)
            hiddenIconsDB.insert(hiddenIcon)

            runOnUiThread {
                binding.allAppsFragment.root.onIconHidden(item)
            }
        }
    }

    private fun renameItem(homeScreenGridItem: HomeScreenGridItem) {
        EditItemDialog(this, homeScreenGridItem) {
            binding.homeScreenGrid.root.fetchGridItems()
            ensureBackgroundThread {
                refreshLaunchers()
            }
        }
    }

    private fun launchWallpapersIntent() {
        try {
            mPickWallpaperLauncher.launch("image/*")
        } catch (_: Exception) {
            try {
                Intent(Intent.ACTION_SET_WALLPAPER).apply {
                    startActivity(this)
                }
            } catch (_: ActivityNotFoundException) {
                toast(org.fossify.commons.R.string.no_app_found)
            } catch (e: Exception) {
                showErrorToast(e)
            }
        }
    }

    private fun launchSettings() {
        needsAppListRefresh = true
        startActivity(
            Intent(this@MainActivity, SettingsActivity::class.java)
        )
    }

    private fun launchSetAsDefaultIntent() {
        val intents = listOf(
            Intent(Settings.ACTION_HOME_SETTINGS),
            Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),
            Intent(Settings.ACTION_SETTINGS)
        )
        val intent = intents.firstOrNull { it.resolveActivity(packageManager) != null }
        if (intent != null) {
            startActivity(intent)
        }
    }

    private fun requestHomeRole() {
        if (isQPlus()) {
            startActivityForResult(
                roleManager.createRequestRoleIntent(RoleManager.ROLE_HOME),
                REQUEST_SET_DEFAULT
            )
        }
    }

    val menuListener: ItemMenuListener = object : ItemMenuListener {
        override fun onAnyClick() {
            resetFragmentTouches()
        }

        override fun hide(gridItem: HomeScreenGridItem) {
            ConfirmationDialog(
                activity = this@MainActivity,
                message = getString(R.string.hide_icon_confirm, gridItem.title),
                positive = org.fossify.commons.R.string.ok,
                negative = org.fossify.commons.R.string.cancel
            ) {
                hideIcon(gridItem)
            }
        }

        override fun rename(gridItem: HomeScreenGridItem) {
            renameItem(gridItem)
        }

        override fun resize(gridItem: HomeScreenGridItem) {
            binding.homeScreenGrid.root.widgetLongPressed(gridItem)
        }

        override fun appInfo(gridItem: HomeScreenGridItem) {
            launchAppInfo(gridItem.packageName)
        }

        override fun remove(gridItem: HomeScreenGridItem) {
            ConfirmationDialog(
                activity = this@MainActivity,
                message = getString(R.string.remove_from_home_confirm, gridItem.title),
                positive = org.fossify.commons.R.string.ok,
                negative = org.fossify.commons.R.string.cancel
            ) {
                binding.homeScreenGrid.root.removeAppIcon(gridItem)
            }
        }

        override fun uninstall(gridItem: HomeScreenGridItem) {
            ConfirmationDialog(
                activity = this@MainActivity,
                message = getString(R.string.uninstall_app_confirm, gridItem.title),
                positive = R.string.uninstall,
                negative = org.fossify.commons.R.string.cancel
            ) {
                uninstallApp(gridItem.packageName)
            }
        }

        override fun toggleLock(gridItem: HomeScreenGridItem) {
            val pkg = AppLockManager.getLockKey(gridItem)
            val title = gridItem.title
            val isLocked = AppLockManager.isAppLocked(pkg)
            if (isLocked) {
                // Unlocking a locked app strictly requires entering biometric/PIN prompt first
                AppLockManager.authenticateAndLaunch(this@MainActivity, pkg, title) {
                    AppLockManager.unlockApp(this@MainActivity, pkg) {
                        toast(R.string.app_unlocked)
                        binding.homeScreenGrid.root.fetchGridItems()
                        IconCache.clear()
                        ensureBackgroundThread {
                            refreshLaunchers()
                        }
                    }
                }
            } else {
                ConfirmationDialog(
                    activity = this@MainActivity,
                    message = getString(R.string.lock_app_confirm_message, title),
                    positive = org.fossify.commons.R.string.ok,
                    negative = org.fossify.commons.R.string.cancel
                ) {
                    if (!config.isAppLockEnabled) {
                        config.isAppLockEnabled = true
                    }
                    AppLockManager.lockApp(this@MainActivity, pkg, title) {
                        toast(R.string.app_locked)
                        binding.homeScreenGrid.root.fetchGridItems()
                        IconCache.clear()
                        ensureBackgroundThread {
                            refreshLaunchers()
                        }
                    }
                }
            }
        }

        override fun customizeGestures(gridItem: HomeScreenGridItem) {
            ItemGesturesDialog(this@MainActivity, gridItem) {
                binding.homeScreenGrid.root.fetchGridItems()
            }
        }

        override fun openPopupWidget(gridItem: HomeScreenGridItem) {
            this@MainActivity.openPopupWidget(gridItem)
        }

        override fun onDismiss() {
            mOpenPopupMenu = null
            if (!binding.homeScreenGrid.root.isDraggingItem()) {
                mLongPressedIcon = null
            }
            resetFragmentTouches()
        }

        override fun beforeShow(menu: Menu) {
            var visibleMenuItems = 0
            for (item in menu.iterator()) {
                if (item.isVisible) {
                    visibleMenuItems++
                }
            }
            val yOffset =
                resources.getDimension(R.dimen.long_press_anchor_button_offset_y) * (visibleMenuItems - 1)
            binding.homeScreenPopupMenuAnchor.y -= yOffset
        }
    }

    private class MyGestureListener(
        private val flingListener: FlingListener,
    ) : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapUp(event: MotionEvent): Boolean {
            (flingListener as MainActivity).homeScreenClicked(event.x, event.y)
            return super.onSingleTapUp(event)
        }

        override fun onDoubleTap(event: MotionEvent): Boolean {
            val handled = (flingListener as MainActivity).homeScreenDoubleTapped(event.x, event.y)
            return if (handled) true else super.onDoubleTap(event)
        }

        override fun onFling(
            event1: MotionEvent?,
            event2: MotionEvent,
            velocityX: Float,
            velocityY: Float,
        ): Boolean {
            // ignore fling events just after releasing an icon from dragging
            if (System.currentTimeMillis() - mLastUpEvent < 500L) {
                return true
            }

            val mainActivity = flingListener as MainActivity
            val downItem = mainActivity.mTouchDownItem

            if (abs(velocityY) > abs(velocityX)) {
                if (velocityY > 0) {
                    if (downItem != null && mainActivity.handleItemFlingDown(downItem)) {
                        return true
                    }
                    flingListener.onFlingDown()
                } else {
                    if (downItem != null && mainActivity.handleItemFlingUp(downItem)) {
                        return true
                    }
                    flingListener.onFlingUp()
                }
            } else if (abs(velocityX) > abs(velocityY)) {
                if (velocityX > 0) {
                    if (downItem != null && mainActivity.handleItemFlingRight(downItem)) {
                        return true
                    }
                    flingListener.onFlingRight()
                } else {
                    if (downItem != null && mainActivity.handleItemFlingLeft(downItem)) {
                        return true
                    }
                    flingListener.onFlingLeft()
                }
            }

            return true
        }

        override fun onLongPress(event: MotionEvent) {
            (flingListener as MainActivity).homeScreenLongPressed(event.x, event.y)
        }
    }

    override fun onFlingUp() {
        if (mIgnoreYMoveEvents) {
            return
        }

        if (!isWidgetsFragmentExpanded()) {
            mIgnoreUpEvent = true
            val action = config.gestureSwipeUpAction
            if (action.isNotEmpty() && action != LauncherActionHandler.ACTION_NONE) {
                if (config.gestureHaptics) {
                    binding.mainHolder.performHapticFeedback()
                }
                LauncherActionHandler.executeAction(this, action)
            } else {
                showFragment(binding.allAppsFragment)
            }
        }
    }

    @SuppressLint("WrongConstant")
    override fun onFlingDown() {
        if (mIgnoreYMoveEvents) {
            return
        }

        mIgnoreUpEvent = true
        if (isAllAppsFragmentExpanded()) {
            hideFragment(binding.allAppsFragment)
        } else if (isWidgetsFragmentExpanded()) {
            hideFragment(binding.widgetsFragment)
        } else {
            val action = config.gestureSwipeDownAction
            if (action.isNotEmpty() && action != LauncherActionHandler.ACTION_NONE) {
                if (config.gestureHaptics) {
                    binding.mainHolder.performHapticFeedback()
                }
                LauncherActionHandler.executeAction(this, action)
            } else {
                try {
                    Class.forName("android.app.StatusBarManager")
                        .getMethod("expandNotificationsPanel")
                        .invoke(getSystemService("statusbar"))
                } catch (e: Exception) {
                    logKeeper.log("MainActivity", "expandNotificationsPanel reflection call failed", e)
                }
            }
        }
    }

    override fun onFlingRight() {
        if (mIgnoreXMoveEvents) {
            return
        }

        mIgnoreUpEvent = true
        binding.homeScreenGrid.root.prevPage(redraw = true)
    }

    override fun onFlingLeft() {
        if (mIgnoreXMoveEvents) {
            return
        }

        mIgnoreUpEvent = true
        binding.homeScreenGrid.root.nextPage(redraw = true)
    }

    private fun downsampleIconDrawable(raw: Drawable, maxDimension: Int = CustomIconManager.MAX_ICON_SIZE): Drawable {
        if (raw is BitmapDrawable && raw.bitmap != null) {
            val bmp = raw.bitmap
            if (bmp.width <= maxDimension && bmp.height <= maxDimension) {
                return raw
            }
            val scaled = CustomIconManager.downsampleBitmap(bmp, maxDimension)
            return BitmapDrawable(resources, scaled)
        }

        val width = if (raw.intrinsicWidth > 0) raw.intrinsicWidth else maxDimension
        val height = if (raw.intrinsicHeight > 0) raw.intrinsicHeight else maxDimension
        val ratio = minOf(maxDimension.toFloat() / width, maxDimension.toFloat() / height).coerceAtMost(1f)
        val targetWidth = maxOf(1, (width * ratio).toInt())
        val targetHeight = maxOf(1, (height * ratio).toInt())

        return try {
            val bitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            raw.setBounds(0, 0, targetWidth, targetHeight)
            raw.draw(canvas)
            BitmapDrawable(resources, bitmap)
        } catch (e: Exception) {
            raw
        }
    }

    @SuppressLint("WrongConstant")
    fun getAllAppLaunchers(): ArrayList<AppLauncher> {
        val hiddenIcons = hiddenIconsDB.getHiddenIcons().map {
            it.getIconIdentifier()
        }

        val existingColors = try {
            launchersDB.getAppLaunchers().associate { it.getLauncherIdentifier() to it.thumbnailColor }
        } catch (e: Exception) {
            emptyMap()
        }

        val allApps = ArrayList<AppLauncher>()
        val intent = Intent(Intent.ACTION_MAIN, null)
        intent.addCategory(Intent.CATEGORY_LAUNCHER)

        val simpleLauncher = applicationContext.packageName
        val microG = "com.google.android.gms"
        val flags = if (Build.VERSION.SDK_INT >= 35) {
            PackageManager.MATCH_ARCHIVED_PACKAGES
        } else {
            0L
        }
        val list = if (Build.VERSION.SDK_INT >= 33) {
            packageManager.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(flags))
        } else {
            packageManager.queryIntentActivities(intent, PackageManager.PERMISSION_GRANTED)
        }
        for (info in list) {
            val componentInfo = info.activityInfo.applicationInfo
            val packageName = componentInfo.packageName
            if (packageName == simpleLauncher || packageName == microG) {
                continue
            }

            val activityName = info.activityInfo.name
            val identifier = "$packageName/$activityName"
            if (hiddenIcons.contains(identifier)) {
                continue
            }

            val isArchived = if (Build.VERSION.SDK_INT >= 35) {
                try {
                    info.activityInfo.isArchived || shura.home.helpers.ArchivedAppsManager.isArchived(this, packageName)
                } catch (e: Throwable) {
                    shura.home.helpers.ArchivedAppsManager.isArchived(this, packageName)
                }
            } else {
                false
            }

            val label = info.loadLabel(packageManager).toString()
            val isLocked = AppLockManager.isAppLocked(packageName)
            val iconCacheKey = when {
                isArchived && isLocked -> "${identifier}_archived_locked"
                isArchived -> "${identifier}_archived"
                isLocked -> "${identifier}_locked"
                else -> identifier
            }
            var drawable = IconCache.getDrawable(iconCacheKey)
            if (drawable == null) {
                drawable = try {
                    info.loadIcon(packageManager)
                } catch (e: Exception) {
                    null
                } ?: getDrawableForPackageName(packageName)
                if (drawable == null && Build.VERSION.SDK_INT >= 35) {
                    drawable = try {
                        packageManager.getArchivedPackage(packageName)?.launcherActivities?.firstOrNull()?.icon
                    } catch (e: Exception) {
                        null
                    }
                }
                if (drawable != null) {
                    drawable = downsampleIconDrawable(drawable)
                    if (isArchived) {
                        drawable = shura.home.helpers.IconDecorator.decorateArchivedIcon(this@MainActivity, drawable)
                    }
                    if (isLocked) {
                        drawable = shura.home.helpers.IconDecorator.decorateLockedIcon(this@MainActivity, drawable)
                    }
                    IconCache.putDrawable(iconCacheKey, drawable)
                }
            }
            if (drawable == null) {
                continue
            }

            val placeholderColor = existingColors[identifier] ?: try {
                val bitmap = drawable.toBitmap(
                    width = max(drawable.intrinsicWidth, 1),
                    height = max(drawable.intrinsicHeight, 1),
                    config = Bitmap.Config.ARGB_8888
                )
                calculateAverageColor(bitmap)
            } catch (e: Exception) {
                0
            }

            val appInstallTime = try {
                val pkgInfo = packageManager.getPackageInfo(packageName, 0)
                val isSystemApp = (pkgInfo.applicationInfo?.flags ?: 0) and ApplicationInfo.FLAG_SYSTEM != 0
                if (isSystemApp) {
                    val sourceDir = pkgInfo.applicationInfo?.sourceDir
                    if (sourceDir != null) File(sourceDir).lastModified() else pkgInfo.firstInstallTime
                } else {
                    pkgInfo.firstInstallTime
                }
            } catch (e: Exception) {
                0L
            }
            allApps.add(
                AppLauncher(
                    id = null,
                    title = label,
                    packageName = packageName,
                    activityName = activityName,
                    order = 0,
                    thumbnailColor = placeholderColor,
                    drawable = drawable
                ).apply {
                    installTime = appInstallTime
                    this.isArchived = isArchived
                }
            )
        }

        // Dual-Layer Archived Apps Discovery on Android 15+ (API 35+)
        if (Build.VERSION.SDK_INT >= 35) {
            val processedPackages = allApps.map { it.packageName }.toMutableSet()
            processedPackages.add(simpleLauncher)
            processedPackages.add(microG)

            // Method A: LauncherApps.getActivityList for archived packages
            try {
                val launcherApps = applicationContext.getSystemService(LAUNCHER_APPS_SERVICE) as? LauncherApps
                val user = android.os.Process.myUserHandle()
                val activityList = launcherApps?.getActivityList(null, user) ?: emptyList()
                for (activity in activityList) {
                    val pkg = activity.applicationInfo.packageName
                    if (processedPackages.contains(pkg)) continue
                    val isArchived = try {
                        activity.applicationInfo.isArchived || shura.home.helpers.ArchivedAppsManager.isArchived(this, pkg)
                    } catch (e: Throwable) {
                        shura.home.helpers.ArchivedAppsManager.isArchived(this, pkg)
                    }
                    if (!isArchived) continue

                    val actName = activity.name ?: ""
                    val identifier = "$pkg/$actName"
                    if (hiddenIcons.contains(identifier)) continue

                    val label = activity.label?.toString() ?: pkg
                    val isLocked = AppLockManager.isAppLocked(pkg)
                    val iconCacheKey = when {
                        isLocked -> "${identifier}_archived_locked"
                        else -> "${identifier}_archived"
                    }
                    var drawable = IconCache.getDrawable(iconCacheKey)
                    if (drawable == null) {
                        drawable = try {
                            activity.getBadgedIcon(0)
                        } catch (e: Exception) {
                            null
                        } ?: try {
                            activity.getIcon(0)
                        } catch (e: Exception) {
                            null
                        } ?: try {
                            packageManager.getArchivedPackage(pkg)?.launcherActivities?.firstOrNull()?.icon
                        } catch (e: Exception) {
                            null
                        }

                        if (drawable != null) {
                            drawable = shura.home.helpers.IconDecorator.decorateArchivedIcon(this@MainActivity, drawable)
                            if (isLocked) {
                                drawable = shura.home.helpers.IconDecorator.decorateLockedIcon(this@MainActivity, drawable)
                            }
                            IconCache.putDrawable(iconCacheKey, drawable)
                        }
                    }

                    if (drawable == null) continue

                    val placeholderColor = existingColors[identifier] ?: try {
                        val bitmap = drawable.toBitmap(
                            width = max(drawable.intrinsicWidth, 1),
                            height = max(drawable.intrinsicHeight, 1),
                            config = Bitmap.Config.ARGB_8888
                        )
                        calculateAverageColor(bitmap)
                    } catch (e: Exception) {
                        0
                    }

                    val appInstallTime = try {
                        val pkgInfo = packageManager.getPackageInfo(
                            pkg,
                            PackageManager.PackageInfoFlags.of(PackageManager.MATCH_ARCHIVED_PACKAGES.toLong())
                        )
                        val isSystemApp = (pkgInfo.applicationInfo?.flags ?: 0) and ApplicationInfo.FLAG_SYSTEM != 0
                        if (isSystemApp) {
                            val sourceDir = pkgInfo.applicationInfo?.sourceDir
                            if (sourceDir != null) File(sourceDir).lastModified() else pkgInfo.firstInstallTime
                        } else {
                            pkgInfo.firstInstallTime
                        }
                    } catch (e: Exception) {
                        0L
                    }

                    allApps.add(
                        AppLauncher(
                            id = null,
                            title = label,
                            packageName = pkg,
                            activityName = actName,
                            order = 0,
                            thumbnailColor = placeholderColor,
                            drawable = drawable
                        ).apply {
                            installTime = appInstallTime
                            this.isArchived = true
                        }
                    )
                    processedPackages.add(pkg)
                }
            } catch (e: Exception) {
                shura.home.helpers.LogCatcher.log("MainActivity", "LauncherApps archived query failed: ${e.message}")
            }

            // Method B: Direct query via PackageManager.getInstalledPackages / getArchivedPackage
            try {
                val installedPackages = packageManager.getInstalledPackages(
                    PackageManager.PackageInfoFlags.of(PackageManager.MATCH_ARCHIVED_PACKAGES.toLong())
                )
                for (packageInfo in installedPackages) {
                    val pkg = packageInfo.packageName
                    if (processedPackages.contains(pkg)) continue
                    val isArchived = try {
                        packageInfo.applicationInfo?.isArchived == true || shura.home.helpers.ArchivedAppsManager.isArchived(this, pkg)
                    } catch (e: Throwable) {
                        shura.home.helpers.ArchivedAppsManager.isArchived(this, pkg)
                    }
                    if (!isArchived) continue

                    val archivedPackageInfo = try {
                        packageManager.getArchivedPackage(pkg)
                    } catch (e: Exception) {
                        null
                    } ?: continue

                    val launcherActivity = archivedPackageInfo.launcherActivities.firstOrNull() ?: continue
                    val actName = launcherActivity.componentName.className ?: ""
                    val identifier = "$pkg/$actName"
                    if (hiddenIcons.contains(identifier)) continue

                    val label = launcherActivity.label?.toString()
                        ?: packageInfo.applicationInfo?.loadLabel(packageManager)?.toString()
                        ?: pkg
                    val isLocked = AppLockManager.isAppLocked(pkg)
                    val iconCacheKey = when {
                        isLocked -> "${identifier}_archived_locked"
                        else -> "${identifier}_archived"
                    }
                    var drawable = IconCache.getDrawable(iconCacheKey)
                    if (drawable == null) {
                        drawable = launcherActivity.icon ?: try {
                            packageManager.getApplicationIcon(pkg)
                        } catch (e: Exception) {
                            null
                        }

                        if (drawable != null) {
                            drawable = shura.home.helpers.IconDecorator.decorateArchivedIcon(this@MainActivity, drawable)
                            if (isLocked) {
                                drawable = shura.home.helpers.IconDecorator.decorateLockedIcon(this@MainActivity, drawable)
                            }
                            IconCache.putDrawable(iconCacheKey, drawable)
                        }
                    }

                    if (drawable == null) continue

                    val placeholderColor = existingColors[identifier] ?: try {
                        val bitmap = drawable.toBitmap(
                            width = max(drawable.intrinsicWidth, 1),
                            height = max(drawable.intrinsicHeight, 1),
                            config = Bitmap.Config.ARGB_8888
                        )
                        calculateAverageColor(bitmap)
                    } catch (e: Exception) {
                        0
                    }

                    val isSystemApp = (packageInfo.applicationInfo?.flags ?: 0) and ApplicationInfo.FLAG_SYSTEM != 0
                    val appInstallTime = if (isSystemApp) {
                        val sourceDir = packageInfo.applicationInfo?.sourceDir
                        if (sourceDir != null) File(sourceDir).lastModified() else packageInfo.firstInstallTime
                    } else {
                        packageInfo.firstInstallTime
                    }

                    allApps.add(
                        AppLauncher(
                            id = null,
                            title = label,
                            packageName = pkg,
                            activityName = actName,
                            order = 0,
                            thumbnailColor = placeholderColor,
                            drawable = drawable
                        ).apply {
                            installTime = appInstallTime
                            this.isArchived = true
                        }
                    )
                    processedPackages.add(pkg)
                }
            } catch (e: Exception) {
                shura.home.helpers.LogCatcher.log("MainActivity", "PackageManager archived query failed: ${e.message}")
            }
        }

        launchersDB.insertAll(allApps)
        return allApps
    }

    private fun getDefaultAppPackages(appLaunchers: ArrayList<AppLauncher>) {
        val homeScreenGridItems = ArrayList<HomeScreenGridItem>()
        var dockSlot = 0
        val maxDockSlots = config.dockColumnCount

        if (dockSlot < maxDockSlots) {
            try {
                val defaultDialerPackage =
                    (getSystemService(TELECOM_SERVICE) as TelecomManager).defaultDialerPackage
                appLaunchers.firstOrNull { it.packageName == defaultDialerPackage }?.apply {
                    val dialerIcon =
                        HomeScreenGridItem(
                            id = null,
                            left = dockSlot,
                            top = config.homeRowCount - 1,
                            right = dockSlot,
                            bottom = config.homeRowCount - 1,
                            page = 0,
                            packageName = defaultDialerPackage,
                            activityName = "",
                            title = title,
                            type = ITEM_TYPE_ICON,
                            className = "",
                            widgetId = -1,
                            shortcutId = "",
                            icon = null,
                            docked = true,
                            parentId = null
                        )
                    homeScreenGridItems.add(dialerIcon)
                    dockSlot++
                }
            } catch (e: Exception) {
                logKeeper.log("MainActivity", "Default dialer icon detection failed", e)
            }
        }

        if (dockSlot < maxDockSlots) {
            try {
                val defaultSMSMessengerPackage = Telephony.Sms.getDefaultSmsPackage(this)
                appLaunchers.firstOrNull { it.packageName == defaultSMSMessengerPackage }?.apply {
                    val messengerIcon =
                        HomeScreenGridItem(
                            id = null,
                            left = dockSlot,
                            top = config.homeRowCount - 1,
                            right = dockSlot,
                            bottom = config.homeRowCount - 1,
                            page = 0,
                            packageName = defaultSMSMessengerPackage,
                            activityName = "",
                            title = title,
                            type = ITEM_TYPE_ICON,
                            className = "",
                            widgetId = -1,
                            shortcutId = "",
                            icon = null,
                            docked = true,
                            parentId = null
                        )
                    homeScreenGridItems.add(messengerIcon)
                    dockSlot++
                }
            } catch (e: Exception) {
                logKeeper.log("MainActivity", "Default SMS messenger icon detection failed", e)
            }
        }

        if (dockSlot < maxDockSlots) {
            try {
                val browserIntent = Intent(Intent.ACTION_VIEW, "http://".toUri())
                val resolveInfo =
                    packageManager.resolveActivity(browserIntent, PackageManager.MATCH_DEFAULT_ONLY)
                val defaultBrowserPackage = resolveInfo!!.activityInfo.packageName
                appLaunchers.firstOrNull { it.packageName == defaultBrowserPackage }?.apply {
                    val browserIcon =
                        HomeScreenGridItem(
                            id = null,
                            left = dockSlot,
                            top = config.homeRowCount - 1,
                            right = dockSlot,
                            bottom = config.homeRowCount - 1,
                            page = 0,
                            packageName = defaultBrowserPackage,
                            activityName = "",
                            title = title,
                            type = ITEM_TYPE_ICON,
                            className = "",
                            widgetId = -1,
                            shortcutId = "",
                            icon = null,
                            docked = true,
                            parentId = null
                        )
                    homeScreenGridItems.add(browserIcon)
                    dockSlot++
                }
            } catch (e: Exception) {
                logKeeper.log("MainActivity", "Default browser icon detection failed", e)
            }
        }

        if (dockSlot < maxDockSlots) {
            try {
                val potentialStores = arrayListOf(
                    "com.android.vending", "org.fdroid.fdroid", "com.aurora.store"
                )
                val storePackage = potentialStores.firstOrNull {
                    isPackageInstalled(it) && appLaunchers.map { it.packageName }.contains(it)
                }
                if (storePackage != null) {
                    appLaunchers.firstOrNull { it.packageName == storePackage }?.apply {
                        val storeIcon = HomeScreenGridItem(
                            id = null,
                            left = dockSlot,
                            top = config.homeRowCount - 1,
                            right = dockSlot,
                            bottom = config.homeRowCount - 1,
                            page = 0,
                            packageName = storePackage,
                            activityName = "",
                            title = title,
                            type = ITEM_TYPE_ICON,
                            className = "",
                            widgetId = -1,
                            shortcutId = "",
                            icon = null,
                            docked = true,
                            parentId = null
                        )
                        homeScreenGridItems.add(storeIcon)
                        dockSlot++
                    }
                }
            } catch (e: Exception) {
                logKeeper.log("MainActivity", "Default app store icon detection failed", e)
            }
        }

        if (dockSlot < maxDockSlots) {
            try {
                val cameraIntent = Intent("android.media.action.IMAGE_CAPTURE")
                val resolveInfo =
                    packageManager.resolveActivity(cameraIntent, PackageManager.MATCH_DEFAULT_ONLY)
                val defaultCameraPackage = resolveInfo!!.activityInfo.packageName
                appLaunchers.firstOrNull { it.packageName == defaultCameraPackage }?.apply {
                    val cameraIcon =
                        HomeScreenGridItem(
                            id = null,
                            left = dockSlot,
                            top = config.homeRowCount - 1,
                            right = dockSlot,
                            bottom = config.homeRowCount - 1,
                            page = 0,
                            packageName = defaultCameraPackage,
                            activityName = "",
                            title = title,
                            type = ITEM_TYPE_ICON,
                            className = "",
                            widgetId = -1,
                            shortcutId = "",
                            icon = null,
                            docked = true,
                            parentId = null
                        )
                    homeScreenGridItems.add(cameraIcon)
                    dockSlot++
                }
            } catch (e: Exception) {
                logKeeper.log("MainActivity", "Default camera icon detection failed", e)
            }
        }

        // Add default Digital Clock widget (4x2) at the top of the home screen
        try {
            val clockWidget = HomeScreenGridItem(
                id = null,
                left = 0,
                top = 0,
                right = 3,
                bottom = 1,
                page = 0,
                packageName = packageName,
                activityName = "",
                title = getString(R.string.clock_widget_title),
                type = ITEM_TYPE_WIDGET,
                className = BUILT_IN_CLOCK_CLASS_NAME,
                widgetId = WIDGET_ID_BUILTIN_CLOCK,
                shortcutId = "",
                icon = null,
                docked = false,
                parentId = null,
                widthCells = 4,
                heightCells = 2
            )
            homeScreenGridItems.add(clockWidget)
        } catch (e: Exception) {
            logKeeper.log("MainActivity", "Default clock widget placement failed", e)
        }

        homeScreenGridItemsDB.insertAll(homeScreenGridItems)
    }

    fun handleWidgetBinding(
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        appWidgetInfo: AppWidgetProviderInfo,
        callback: (canBind: Boolean) -> Unit,
    ) {
        mActionOnCanBindWidget = null
        val canCreateWidget =
            appWidgetManager.bindAppWidgetIdIfAllowed(appWidgetId, appWidgetInfo.provider)
        if (canCreateWidget) {
            callback(true)
        } else {
            mActionOnCanBindWidget = callback
            Intent(AppWidgetManager.ACTION_APPWIDGET_BIND).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, appWidgetInfo.provider)
                startActivityForResult(this, REQUEST_ALLOW_BINDING_WIDGET)
            }
        }
    }

    fun handleWidgetConfigureScreen(
        appWidgetHost: AppWidgetHost,
        appWidgetId: Int,
        callback: (canBind: Boolean) -> Unit,
    ) {
        mActionOnWidgetConfiguredWidget = callback
        appWidgetHost.startAppWidgetConfigureActivityForResult(
            this,
            appWidgetId,
            0,
            REQUEST_CONFIGURE_WIDGET,
            null
        )
    }

    fun handleShorcutCreation(
        activityInfo: ActivityInfo,
        callback: (shortcutId: String, label: String, icon: Drawable) -> Unit,
    ) {
        mActionOnAddShortcut = callback
        val componentName = ComponentName(activityInfo.packageName, activityInfo.name)
        try {
            Intent(Intent.ACTION_CREATE_SHORTCUT).apply {
                component = componentName
                startActivityForResult(this, REQUEST_CREATE_SHORTCUT)
            }
        } catch (e: Exception) {
            toast(R.string.cannot_create_shortcut)
            LogCatcher.log("MainActivity", "Failed to launch shortcut creation for $componentName: ${e.message}")
        }
    }

    private fun updateStatusBarIcons(backgroundColor: Int? = null) {
        val isDrawerOpen = isAllAppsFragmentExpanded()
        val isLightBackground = when {
            isDrawerOpen -> false
            backgroundColor != null -> backgroundColor.getContrastColor() == DARK_GREY
            wallpaperSupportsDarkText != null -> wallpaperSupportsDarkText!!
            else -> {
                refreshWallpaperSupportsDarkText()
                wallpaperSupportsDarkText ?: false
            }
        }
        window.statusBarColor = Color.TRANSPARENT
        if (isQPlus()) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
        window.insetsController().apply {
            isAppearanceLightStatusBars = isLightBackground
            isAppearanceLightNavigationBars = if (isDrawerOpen) false else isLightBackground
        }
    }

    // taken from https://gist.github.com/maxjvh/a6ab15cbba9c82a5065d
    private fun calculateAverageColor(bitmap: Bitmap): Int {
        var red = 0
        var green = 0
        var blue = 0
        val height = bitmap.height
        val width = bitmap.width
        var n = 0
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        var i = 0
        while (i < pixels.size) {
            val color = pixels[i]
            red += Color.red(color)
            green += Color.green(color)
            blue += Color.blue(color)
            n++
            i += 1
        }

        return Color.rgb(red / n, green / n, blue / n)
    }

    private fun installCrashHandlerIfNeeded() {
        if (isCrashHandlerInstalled) {
            return
        }
        isCrashHandlerInstalled = true
        shura.home.helpers.LogCatcher.init(applicationContext)
    }
}
