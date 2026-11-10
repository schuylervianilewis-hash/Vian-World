package shura.home.fragments

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import java.io.File
import android.net.Uri
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.view.inputmethod.EditorInfo
import androidx.appcompat.view.ContextThemeWrapper
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.RecyclerView.OnScrollListener
import org.fossify.commons.extensions.applyColorFilter
import org.fossify.commons.extensions.beGone
import org.fossify.commons.extensions.beVisible
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.getPopupMenuTheme
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.commons.extensions.hideKeyboard
import org.fossify.commons.extensions.normalizeString
import org.fossify.commons.extensions.showKeyboard
import org.fossify.commons.views.MyGridLayoutManager
import shura.home.R
import shura.home.activities.HiddenIconsActivity
import shura.home.activities.MainActivity
import shura.home.activities.SettingsActivity
import shura.home.adapters.LaunchersAdapter
import shura.home.databinding.AllAppsFragmentBinding
import shura.home.extensions.config
import shura.home.extensions.launchApp
import shura.home.extensions.setupDrawerBackground
import shura.home.helpers.DRAWER_SORT_ASCENDING
import shura.home.helpers.DRAWER_SORT_BY_NAME
import shura.home.helpers.DRAWER_SORT_BY_TIME
import shura.home.helpers.DRAWER_SORT_DESCENDING
import shura.home.helpers.ITEM_TYPE_ICON
import shura.home.interfaces.AllAppsListener
import shura.home.models.AppLauncher
import shura.home.models.HomeScreenGridItem

class AllAppsFragment(
    context: Context,
    attributeSet: AttributeSet
) : MyFragment<AllAppsFragmentBinding>(context, attributeSet), AllAppsListener {

    private var lastTouchCoords = Pair(0f, 0f)
    var touchDownY = -1
    var ignoreTouches = false

    private var launchers = emptyList<AppLauncher>()

    @SuppressLint("ClickableViewAccessibility")
    override fun setupFragment(activity: MainActivity) {
        this.activity = activity
        this.binding = AllAppsFragmentBinding.bind(this)

        binding.allAppsGrid.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                touchDownY = -1
            }

            return@setOnTouchListener false
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        setupDrawerBackground()
    }

    @SuppressLint("NotifyDataSetChanged")
    fun onResume() {
        if (binding.allAppsGrid.layoutManager == null || binding.allAppsGrid.adapter == null) {
            return
        }

        val layoutManager = binding.allAppsGrid.layoutManager as MyGridLayoutManager
        if (layoutManager.spanCount != context.config.drawerColumnCount) {
            onConfigurationChanged()
            // Force redraw due to changed item size
            (binding.allAppsGrid.adapter as LaunchersAdapter).notifyDataSetChanged()
        }
    }

    fun onConfigurationChanged() {
        binding.allAppsGrid.scrollToPosition(0)
        binding.allAppsFastscroller.resetManualScrolling()
        setupViews()

        val layoutManager = binding.allAppsGrid.layoutManager as MyGridLayoutManager
        layoutManager.spanCount = context.config.drawerColumnCount
        setupAdapter(launchers)
    }

    override fun onInterceptTouchEvent(event: MotionEvent?): Boolean {
        if (event == null) {
            return super.onInterceptTouchEvent(event)
        }

        var shouldIntercept = false

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                touchDownY = event.y.toInt()
            }

            MotionEvent.ACTION_MOVE -> {
                if (ignoreTouches) {
                    // some devices ACTION_MOVE keeps triggering for the whole long press duration, but we are interested in real moves only, when coords change
                    if (lastTouchCoords.first != event.x || lastTouchCoords.second != event.y) {
                        touchDownY = -1
                        return true
                    }
                }

                // pull the whole fragment down if it is scrolled way to the top and the user pulls it even further
                if (touchDownY != -1) {
                    val distance = event.y.toInt() - touchDownY
                    shouldIntercept =
                        distance > 0 && binding.allAppsGrid.computeVerticalScrollOffset() == 0
                    if (shouldIntercept) {
                        // Hiding is expensive, only do it if focused
                        if (binding.searchBar.hasFocus()) {
                            activity?.hideKeyboard()
                        }
                        activity?.startHandlingTouches(touchDownY)
                        touchDownY = -1
                    }
                }
            }
        }

        lastTouchCoords = Pair(event.x, event.y)
        return shouldIntercept
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (ignoreTouches) {
            val mainActivity = activity as? MainActivity
            if (mainActivity != null) {
                val handled = mainActivity.onTouchEvent(event)
                if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) {
                    ignoreTouches = false
                    touchDownY = -1
                }
                return handled
            }
        }
        return super.onTouchEvent(event)
    }

    fun gotLaunchers(appLaunchers: List<AppLauncher>) {
        launchers = sortAppLaunchers(appLaunchers)
        setupAdapter(launchers)
    }

    private fun getInstallTime(launcher: AppLauncher): Long {
        if (launcher.installTime != 0L) return launcher.installTime
        val time = try {
            val pkgInfo = context.packageManager.getPackageInfo(launcher.packageName, 0)
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
        launcher.installTime = time
        return time
    }

    private fun sortAppLaunchers(list: List<AppLauncher>): List<AppLauncher> {
        val sortBy = context.config.drawerSortBy
        val sortOrder = context.config.drawerSortOrder
        val isAscending = sortOrder == DRAWER_SORT_ASCENDING

        return if (sortBy == DRAWER_SORT_BY_TIME) {
            if (isAscending) {
                list.sortedWith(
                    compareBy<AppLauncher> { getInstallTime(it) }
                        .thenBy { it.title.normalizeString().lowercase() }
                        .thenBy { it.packageName }
                )
            } else {
                list.sortedWith(
                    compareByDescending<AppLauncher> { getInstallTime(it) }
                        .thenBy { it.title.normalizeString().lowercase() }
                        .thenBy { it.packageName }
                )
            }
        } else {
            if (isAscending) {
                list.sortedWith(
                    compareBy<AppLauncher> { it.title.normalizeString().lowercase() }
                        .thenBy { it.packageName }
                )
            } else {
                list.sortedWith(
                    compareByDescending<AppLauncher> { it.title.normalizeString().lowercase() }
                        .thenBy { it.packageName }
                )
            }
        }
    }

    private fun getAdapter() = binding.allAppsGrid.adapter as? LaunchersAdapter

    private fun setupAdapter(launchers: List<AppLauncher>) {
        activity?.runOnUiThread {
            val layoutManager = binding.allAppsGrid.layoutManager as MyGridLayoutManager
            layoutManager.spanCount = context.config.drawerColumnCount

            if (getAdapter() == null) {
                LaunchersAdapter(activity!!, this) {
                    val launcher = it as AppLauncher
                    shura.home.helpers.LogCatcher.log("AllApps", "Launching app: ${launcher.packageName}")
                    activity?.launchApp(launcher.packageName, launcher.activityName, launcher.title)
                    if (activity?.config?.closeAppDrawer == true) {
                        activity?.closeAppDrawer(delayed = true)
                    }
                    ignoreTouches = false
                    touchDownY = -1
                }.apply {
                    binding.allAppsGrid.itemAnimator = null
                    binding.allAppsGrid.adapter = this
                }
            }

            submitList(launchers.toMutableList())
        }
    }

    fun onIconHidden(item: HomeScreenGridItem) {
        val itemToRemove = launchers.firstOrNull {
            it.getLauncherIdentifier() == item.getItemIdentifier()
        }

        if (itemToRemove != null) {
            val position = launchers.indexOfFirst {
                it.getLauncherIdentifier() == item.getItemIdentifier()
            }

            launchers = launchers.toMutableList().apply {
                removeAt(position)
            }

            submitList(launchers.toMutableList())
        }
    }

    fun setupViews() {
        if (activity == null) {
            return
        }

        binding.allAppsFastscroller.updateColors(context.getProperPrimaryColor())
        binding.allAppsGrid.addOnScrollListener(object : OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                // Hiding is expensive, only do it if focused
                if (binding.searchBar.hasFocus() && dy > 0 && binding.allAppsGrid.computeVerticalScrollOffset() > 0) {
                    activity?.hideKeyboard()
                }
            }
        })

        setupDrawerBackground()
        getAdapter()?.updateTextColor(Color.WHITE)

        val textColor = Color.WHITE
        binding.drawerTitle.setTextColor(textColor)
        binding.drawerBtnSearch.applyColorFilter(textColor)
        binding.drawerBtnPlayStore.applyColorFilter(textColor)
        binding.drawerBtnMenu.applyColorFilter(textColor)

        binding.drawerBtnSearch.beVisible()
        binding.drawerBtnSearch.setOnClickListener {
            openSearchMode()
        }
        binding.drawerTitle.setOnClickListener {
            openSearchMode()
        }

        binding.drawerBtnPlayStore.setOnClickListener {
            launchPlayStore()
        }

        binding.drawerBtnMenu.setOnClickListener {
            showDrawerMenu(binding.drawerBtnMenu)
        }

        binding.searchBar.requireToolbar().beGone()
        binding.searchBar.setupMenu()
        binding.searchBar.toggleForceArrowBackIcon(true)
        setupSearchBarDarkTheme()

        binding.searchBar.onNavigateBackClickListener = {
            closeSearchMode()
        }

        binding.searchBar.onSearchClosedListener = {
            closeSearchMode()
        }

        binding.searchBar.onSearchTextChangedListener = {
            submitList(launchers)
        }

        binding.searchBar.binding.topToolbarSearch.setOnEditorActionListener { _, actionId, _ ->
            if (binding.searchBar.getCurrentQuery().isEmpty()) return@setOnEditorActionListener false
            when (actionId) {
                EditorInfo.IME_ACTION_DONE,
                EditorInfo.IME_ACTION_SEARCH,
                EditorInfo.IME_ACTION_GO -> getAdapter()?.launchFirstApp() == true
                else -> false
            }
        }
    }

    private fun setupSearchBarDarkTheme() {
        val darkBg = Color.BLACK
        val skyBlue = Color.parseColor("#38BDF8")
        val hintColor = Color.parseColor("#94A3B8")

        // 1. Ensure all outer containers are seamless pure Black matching the app drawer topbar
        binding.drawerTopbarContainer.setBackgroundColor(darkBg)
        binding.searchBar.setBackgroundColor(darkBg)
        binding.searchBar.binding.root.setBackgroundColor(darkBg)
        binding.searchBar.binding.searchBarContainer.setBackgroundColor(darkBg)

        // 2. Style the type input capsule (toolbarContainer) with slightly grey surface (#1E2229) and rounded pill shape
        val pillRadius = 20f * resources.displayMetrics.density
        val strokeWidth = (0.8f * resources.displayMetrics.density).toInt().coerceAtLeast(1)
        val inputPillDrawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = pillRadius
            setColor(Color.parseColor("#1E2229"))
            setStroke(strokeWidth, Color.parseColor("#334155"))
        }
        binding.searchBar.binding.toolbarContainer.background = inputPillDrawable

        // 3. Style text and internal toolbar
        binding.searchBar.binding.topToolbar.setBackgroundColor(Color.TRANSPARENT)
        binding.searchBar.binding.topToolbarSearch.setTextColor(Color.WHITE)
        binding.searchBar.binding.topToolbarSearch.setHintTextColor(hintColor)

        // 4. Tint all icons inside the search bar (search icon, back arrow, clear button) with sky light blue
        fun tintChildImageViews(viewGroup: ViewGroup) {
            for (i in 0 until viewGroup.childCount) {
                val child = viewGroup.getChildAt(i)
                if (child is ImageView) {
                    child.applyColorFilter(skyBlue)
                } else if (child is ViewGroup) {
                    tintChildImageViews(child)
                }
            }
        }
        tintChildImageViews(binding.searchBar)
    }

    fun openSearchMode() {
        binding.drawerIdleTopbar.beGone()
        setupSearchBarDarkTheme()
        binding.searchBar.beVisible()
        binding.searchBar.focusView()
        activity?.showKeyboard(binding.searchBar.binding.topToolbarSearch)
    }

    fun closeSearchMode() {
        if (binding.searchBar.isSearchOpen) {
            binding.searchBar.closeSearch()
        }
        binding.searchBar.beGone()
        binding.drawerIdleTopbar.beVisible()
        activity?.hideKeyboard()
        submitList(launchers)
    }

    fun isSearchModeActive(): Boolean {
        return binding.searchBar.isVisible && (binding.searchBar.isSearchOpen || binding.searchBar.getCurrentQuery().isNotEmpty())
    }

    private fun showDrawerMenu(anchorView: View) {
        val currentActivity = activity ?: return
        val contextTheme = ContextThemeWrapper(currentActivity, R.style.Theme_App_PopupMenu_WhiteSky)
        PopupMenu(contextTheme, anchorView, Gravity.TOP or Gravity.END).apply {
            inflate(R.menu.menu_drawer)

            val sortBy = currentActivity.config.drawerSortBy
            val sortOrder = currentActivity.config.drawerSortOrder

            val sortNameItem = menu.findItem(R.id.drawer_menu_sort_name)
            val sortTimeItem = menu.findItem(R.id.drawer_menu_sort_time)

            val nameArrow = if (sortBy == DRAWER_SORT_BY_NAME) {
                if (sortOrder == DRAWER_SORT_ASCENDING) " (A-Z) ↑" else " (Z-A) ↓"
            } else ""
            sortNameItem?.title = "${context.getString(R.string.sort_by_name)}$nameArrow"

            val timeArrow = if (sortBy == DRAWER_SORT_BY_TIME) {
                if (sortOrder == DRAWER_SORT_ASCENDING) " (Oldest) ↑" else " (Newest) ↓"
            } else ""
            sortTimeItem?.title = "${context.getString(R.string.sort_by_time)}$timeArrow"

            setOnMenuItemClickListener { menuItem ->
                when (menuItem.itemId) {
                    R.id.drawer_menu_sort_name -> {
                        toggleSortByName()
                        true
                    }
                    R.id.drawer_menu_sort_time -> {
                        toggleSortByTime()
                        true
                    }
                    R.id.drawer_menu_hidden_apps -> {
                        currentActivity.startActivity(Intent(currentActivity, HiddenIconsActivity::class.java))
                        true
                    }
                    R.id.drawer_menu_settings -> {
                        currentActivity.startActivity(Intent(currentActivity, SettingsActivity::class.java))
                        true
                    }
                    else -> false
                }
            }
            show()
        }
    }

    private fun toggleSortByName() {
        val config = context.config
        if (config.drawerSortBy == DRAWER_SORT_BY_NAME) {
            config.drawerSortOrder = if (config.drawerSortOrder == DRAWER_SORT_ASCENDING) {
                DRAWER_SORT_DESCENDING
            } else {
                DRAWER_SORT_ASCENDING
            }
        } else {
            config.drawerSortBy = DRAWER_SORT_BY_NAME
            config.drawerSortOrder = DRAWER_SORT_ASCENDING
        }
        applyCurrentSort()
    }

    private fun toggleSortByTime() {
        val config = context.config
        if (config.drawerSortBy == DRAWER_SORT_BY_TIME) {
            config.drawerSortOrder = if (config.drawerSortOrder == DRAWER_SORT_DESCENDING) {
                DRAWER_SORT_ASCENDING
            } else {
                DRAWER_SORT_DESCENDING
            }
        } else {
            config.drawerSortBy = DRAWER_SORT_BY_TIME
            config.drawerSortOrder = DRAWER_SORT_DESCENDING
        }
        applyCurrentSort()
    }

    private fun applyCurrentSort() {
        launchers = sortAppLaunchers(launchers)
        submitList(launchers)
        binding.allAppsGrid.scrollToPosition(0)
    }

    private fun launchPlayStore() {
        val pm = context.packageManager
        val storePackages = listOf("com.android.vending", "org.fdroid.fdroid", "com.aurora.store")
        for (pkg in storePackages) {
            val intent = pm.getLaunchIntentForPackage(pkg)
            if (intent != null) {
                try {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                    return
                } catch (e: Exception) {
                    activity?.logKeeper?.log("AllAppsFragment", "Failed to launch $pkg", e)
                }
            }
        }

        try {
            val marketIntent = Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q="))
            marketIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (marketIntent.resolveActivity(pm) != null) {
                context.startActivity(marketIntent)
                return
            }
        } catch (ignored: Exception) {}

        try {
            val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps"))
            webIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(webIntent)
        } catch (e: Exception) {
            activity?.logKeeper?.log("AllAppsFragment", "Failed to launch web store", e)
        }
    }

    private fun showNoResultsPlaceholderIfNeeded() {
        val itemCount = getAdapter()?.itemCount
        binding.noResultsPlaceholder.beVisibleIf(itemCount != null && itemCount == 0)
    }

    override fun onAppLauncherLongPressed(
        x: Float,
        y: Float,
        appLauncher: AppLauncher,
        iconRect: Rect?,
    ) {
        val gridItem = HomeScreenGridItem(
            id = null,
            left = -1,
            top = -1,
            right = -1,
            bottom = -1,
            page = 0,
            packageName = appLauncher.packageName,
            activityName = appLauncher.activityName,
            title = appLauncher.title,
            type = ITEM_TYPE_ICON,
            className = "",
            widgetId = -1,
            shortcutId = "",
            icon = null,
            docked = false,
            parentId = null,
            drawable = appLauncher.drawable
        ).apply {
            isArchived = appLauncher.isArchived
        }

        activity?.showHomeIconMenu(x, y, gridItem, true, iconRect)
        ignoreTouches = true

        closeSearchMode()
    }

    fun onBackPressed(): Boolean {
        if (isSearchModeActive()) {
            closeSearchMode()
            return true
        }

        return false
    }

    private fun submitList(items: List<AppLauncher>) {
        val searchQuery = binding.searchBar.getCurrentQuery()
        val filtered = if (searchQuery.isNotEmpty()) {
            items.filter {
                it.title.normalizeString()
                    .contains(searchQuery.normalizeString(), ignoreCase = true)
            }
        } else {
            items
        }

        getAdapter()?.submitList(filtered) {
            showNoResultsPlaceholderIfNeeded()
        }
    }
}
