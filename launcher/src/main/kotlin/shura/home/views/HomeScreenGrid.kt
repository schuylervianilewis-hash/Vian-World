package shura.home.views

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Point
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.util.AttributeSet
import android.util.Size
import android.util.SizeF
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.RelativeLayout
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.toBitmap
import androidx.core.graphics.drawable.toDrawable
import androidx.core.graphics.toRect
import androidx.core.graphics.withScale
import androidx.core.graphics.withTranslation
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat.Type
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.customview.widget.ExploreByTouchHelper
import com.google.android.material.math.MathUtils
import kotlinx.collections.immutable.toImmutableList
import org.fossify.commons.extensions.adjustAlpha
import org.fossify.commons.extensions.beGone
import org.fossify.commons.extensions.beVisible
import org.fossify.commons.extensions.getContrastColor
import org.fossify.commons.extensions.getProperBackgroundColor
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.commons.extensions.performHapticFeedback
import org.fossify.commons.helpers.FontHelper
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.helpers.isSPlus
import shura.home.R
import shura.home.activities.MainActivity
import shura.home.databinding.HomeScreenGridBinding
import shura.home.extensions.config
import shura.home.extensions.getDrawableForPackageName
import shura.home.helpers.ItemGestureManager
import shura.home.extensions.homeScreenGridItemsDB
import shura.home.helpers.BUILT_IN_CLOCK_CLASS_NAME
import shura.home.helpers.isBuiltInClockWidget
import shura.home.helpers.ITEM_TYPE_FOLDER
import shura.home.helpers.ITEM_TYPE_ICON
import shura.home.helpers.ITEM_TYPE_SHORTCUT
import shura.home.helpers.ITEM_TYPE_WIDGET
import shura.home.helpers.SIDEBAR_ELEMENT_CLASS_NAME
import shura.home.helpers.WIDGET_HOST_ID
import shura.home.models.HomeScreenGridItem
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sqrt

class HomeScreenGrid(context: Context, attrs: AttributeSet, defStyle: Int) :
    RelativeLayout(context, attrs, defStyle) {
    constructor(context: Context, attrs: AttributeSet) : this(context, attrs, 0)

    private lateinit var binding: HomeScreenGridBinding
    private var columnCount = context.config.homeColumnCount
    private var dockColumnCount = context.config.dockColumnCount
    private var rowCount = context.config.homeRowCount
    private var pageIndicatorsYPos = 0
    private val cells = mutableMapOf<Point, Rect>()
    private var dockCellY = 0
    var cellWidth = 0
    var dockCellWidth = 0
    var cellHeight = 0

    private var iconMargin =
        (context.resources.getDimension(R.dimen.icon_side_margin) * 0.22f).toInt().coerceAtLeast(2)
    private var labelSideMargin =
        context.resources.getDimension(org.fossify.commons.R.dimen.small_margin).toInt()
    private var roundedCornerRadius =
        context.resources.getDimension(org.fossify.commons.R.dimen.activity_margin)
    private var folderPadding =
        context.resources.getDimension(org.fossify.commons.R.dimen.medium_margin)
    private var pageIndicatorRadius =
        context.resources.getDimension(R.dimen.page_indicator_dot_radius)
    private var pageIndicatorMargin = context.resources.getDimension(R.dimen.page_indicator_margin)
    private var textPaint: TextPaint
    private var contrastTextPaint: TextPaint
    private var folderTitleTextPaint: TextPaint
    private var dragShadowCirclePaint: Paint
    private var dragShadowBlockedFillPaint: Paint
    private var dragShadowBlockedStrokePaint: Paint
    private var emptyPageIndicatorPaint: Paint
    private var currentPageIndicatorPaint: Paint
    private var folderBackgroundPaint: Paint
    private var folderIconBackgroundPaint: Paint
    private var badgeBackgroundPaint: Paint
    private var badgeTextPaint: Paint
    private var newAppDotPaint: Paint
    private var newAppDotStrokePaint: Paint

    private val folderShadowLayerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val folderPopupBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#E2E8F0")
    }
    private val folderPlusBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F3F4F6")
        style = Paint.Style.FILL
    }
    private val folderPlusStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1C1B1F")
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val folderShadowLayerRect = RectF()

    var notificationBadges: Map<String, Int> = emptyMap()
        private set

    fun updateNotificationBadges(badges: Map<String, Int>) {
        if (notificationBadges != badges) {
            notificationBadges = badges
            invalidate()
        }
    }

    private var draggedItem: HomeScreenGridItem? = null
    private var resizedWidget: HomeScreenGridItem? = null
    private var isFirstDraw = true
    private var iconSize = 0

    private val pager = AnimatedGridPager(
        getMaxPage = ::getMaxPage,
        redrawGrid = ::redrawGrid,
        getWidth = { width },
        getHandler = { handler },
        getNextPageBound = { right - sideMargins.right - cellWidth / 2 },
        getPrevPageBound = { left + sideMargins.left + cellWidth / 2 },
        pageChangeStarted = {
            widgetViews.forEach { it.resetTouches() }
            closeFolder()
            accessibilityHelper.invalidateRoot()
        }
    )

    private var currentlyOpenFolder: HomeScreenFolder? = null
    private var draggingLeftFolderAt: Long? = null
    private var draggingEnteredNewFolderAt: Long? = null

    // apply fake margins at the home screen. Real ones would cause the icons be cut at dragging at screen sides
    var sideMargins = Rect()

    private var gridItems = ArrayList<HomeScreenGridItem>()
    private var gridCenters = ArrayList<Point>()
    private var draggedItemCurrentCoords = Pair(-1, -1)
    private var widgetViews = ArrayList<MyAppWidgetHostView>()

    val appWidgetHost = MyAppWidgetHost(context, WIDGET_HOST_ID)
    private val appWidgetManager = AppWidgetManager.getInstance(context)

    private val accessibilityHelper = HomeScreenGridTouchHelper(this)
    var itemClickListener: ((HomeScreenGridItem) -> Unit)? = null
    var itemLongClickListener: ((HomeScreenGridItem) -> Unit)? = null


    init {
        ViewCompat.setAccessibilityDelegate(this, accessibilityHelper)

        val customTypeface = FontHelper.getTypeface(context)
        textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = context.resources.getDimension(org.fossify.commons.R.dimen.smaller_text_size)
            setShadowLayer(2f, 0f, 0f, Color.BLACK)
            typeface = customTypeface
        }

        contrastTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = context.getProperTextColor()
            textSize = context.resources.getDimension(org.fossify.commons.R.dimen.smaller_text_size)
            setShadowLayer(2f, 0f, 0f, context.getProperTextColor().getContrastColor())
            typeface = customTypeface
        }

        folderTitleTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#1C1B1F")
            textSize = context.resources.getDimension(org.fossify.commons.R.dimen.medium_text_size)
            typeface = customTypeface
        }

        dragShadowCirclePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = context.resources.getColor(org.fossify.commons.R.color.hint_white)
            strokeWidth = context.resources.getDimension(org.fossify.commons.R.dimen.small_margin)
            style = Paint.Style.STROKE
        }

        dragShadowBlockedFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#33EF4444")
            style = Paint.Style.FILL
        }

        dragShadowBlockedStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#80EF4444")
            strokeWidth = context.resources.getDimension(org.fossify.commons.R.dimen.small_margin)
            style = Paint.Style.STROKE
        }

        emptyPageIndicatorPaint = Paint(dragShadowCirclePaint).apply {
            strokeWidth = context.resources.getDimension(R.dimen.page_indicator_stroke_width)
        }
        currentPageIndicatorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = context.resources.getColor(android.R.color.white)
            style = Paint.Style.FILL
        }

        folderBackgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(230, 255, 255, 255) // ~90% opacity translucent white
            style = Paint.Style.FILL
        }

        folderIconBackgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = context.getProperBackgroundColor().adjustAlpha(0.9f)
            style = Paint.Style.FILL
        }

        badgeBackgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#E53935")
            style = Paint.Style.FILL
        }

        badgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 9.5f * context.resources.displayMetrics.density
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }

        newAppDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }

        newAppDotStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#66000000")
            style = Paint.Style.STROKE
            strokeWidth = 1f * context.resources.displayMetrics.density
        }

        ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
            val bars = insets.getInsets(Type.systemBars() or Type.displayCutout())
            val gestures = insets.getInsets(Type.systemGestures())
            val navIgnoring = insets.getInsetsIgnoringVisibility(Type.navigationBars())

            val sideMargin = (18f * context.resources.displayMetrics.density).toInt()

            val newLeft   = sideMargin + bars.left
            val newRight  = sideMargin + bars.right
            val newTop    = bars.top
            val newBottom = max(bars.bottom, max(gestures.bottom, navIgnoring.bottom))
            val marginsChanged = sideMargins.left != newLeft
                    || sideMargins.top != newTop
                    || sideMargins.right != newRight
                    || sideMargins.bottom != newBottom
            if (marginsChanged) {
                sideMargins.set(newLeft, newTop, newRight, newBottom)
                cells.clear()
                gridCenters.clear()
                isFirstDraw = true
                redrawGrid()
            }

            insets
        }

        fetchGridItems()
    }

    override fun dispatchHoverEvent(event: MotionEvent): Boolean {
        if (accessibilityHelper.dispatchHoverEvent(event)) return true
        return super.dispatchHoverEvent(event)
    }

    override fun onFinishInflate() {
        super.onFinishInflate()
        binding = HomeScreenGridBinding.bind(this)
    }

    fun fetchGridItems() {
        ensureBackgroundThread {
            val providers = appWidgetManager.installedProviders
            gridItems = context.homeScreenGridItemsDB.getAllItems() as ArrayList<HomeScreenGridItem>
            val sidebarElementInfos = shura.home.helpers.SidebarHomeElementClient.load(
                context,
                gridItems.filter { it.className == SIDEBAR_ELEMENT_CLASS_NAME }.map { it.shortcutId }
            )
            gridItems.toImmutableList().forEach { item ->
                if (item.type == ITEM_TYPE_ICON) {
                    if (item.className == SIDEBAR_ELEMENT_CLASS_NAME) {
                        val info = sidebarElementInfos[item.shortcutId]
                        if (info != null) {
                            item.icon = info.icon
                            item.drawable = shura.home.helpers.SidebarHomeElementIconDecorator.createDrawable(
                                context,
                                info.icon,
                                info.isActive,
                                info.glowColor
                            ) ?: context.getDrawable(android.R.drawable.ic_menu_view)
                        } else {
                            item.drawable = context.getDrawable(android.R.drawable.ic_menu_view)
                        }
                        return@forEach
                    }
                    val isArchived = shura.home.helpers.ArchivedAppsManager.isArchived(context, item.packageName)
                    item.isArchived = isArchived
                    val isLocked = shura.home.helpers.AppLockManager.isAppLocked(item.packageName)
                    var rawDrawable = if (item.icon != null) {
                        item.icon?.toDrawable(context.resources)
                    } else {
                        context.getDrawableForPackageName(item.packageName, item.activityName)
                    }
                    if (rawDrawable == null && android.os.Build.VERSION.SDK_INT >= 35) {
                        rawDrawable = try {
                            context.packageManager.getArchivedPackage(item.packageName)
                                ?.launcherActivities?.firstOrNull()?.icon
                        } catch (e: Exception) {
                            null
                        }
                    }
                    item.drawable = when {
                        rawDrawable == null -> null
                        isArchived && isLocked -> {
                            val arch = shura.home.helpers.IconDecorator.decorateArchivedIcon(context, rawDrawable)
                            shura.home.helpers.IconDecorator.decorateLockedIcon(context, arch)
                        }
                        isArchived -> shura.home.helpers.IconDecorator.decorateArchivedIcon(context, rawDrawable)
                        isLocked -> shura.home.helpers.IconDecorator.decorateLockedIcon(context, rawDrawable)
                        else -> rawDrawable
                    }
                } else if (item.type == ITEM_TYPE_FOLDER) {
                    item.drawable = if (item.icon != null) {
                        item.icon?.toDrawable(context.resources)
                    } else {
                        item.toFolder().generateDrawable()
                    }
                } else if (item.type == ITEM_TYPE_SHORTCUT) {
                    if (item.icon != null) {
                        item.drawable = item.icon?.toDrawable(context.resources)
                    } else {
                        ensureBackgroundThread {
                            context.homeScreenGridItemsDB.deleteById(item.id!!)
                        }
                    }
                }

                item.providerInfo =
                    providers.firstOrNull { it.provider.className == item.className }
            }

            redrawGrid()
        }
    }

    fun resizeGrid(newRowCount: Int, newColumnCount: Int, newDockColumnCount: Int = context.config.dockColumnCount) {
        if (columnCount != newColumnCount || rowCount != newRowCount || dockColumnCount != newDockColumnCount) {
            rowCount = newRowCount
            columnCount = newColumnCount
            dockColumnCount = newDockColumnCount
            cells.clear()
            gridCenters.clear()
            val maxCols = max(columnCount, dockColumnCount)
            iconMargin =
                (context.resources.getDimension(R.dimen.icon_side_margin) * 0.22f).toInt().coerceAtLeast(2)
            isFirstDraw = true
            reflowGridItemsForNewSize(newRowCount, newColumnCount, newDockColumnCount)
            gridItems.filter { it.type == ITEM_TYPE_WIDGET && !isBuiltInClockWidget(it.className) }.forEach {
                appWidgetHost.deleteAppWidgetId(it.widgetId)
            }
            widgetViews.forEach { removeView(it) }
            widgetViews.clear()
            redrawGrid()
        }
    }

    private fun reflowGridItemsForNewSize(newRowCount: Int, newColumnCount: Int, newDockColumnCount: Int) {
        val modifiedItems = ArrayList<HomeScreenGridItem>()
        val maxCols = newColumnCount
        val maxRows = newRowCount - 1 // desktop usable rows (last row is reserved for dock)

        // 1. Adjust widgets: shrink width/height or clamp if exceeding new grid bounds
        gridItems.filter { it.type == ITEM_TYPE_WIDGET && !it.docked }.forEach { widget ->
            var changed = false

            // Clamp width
            if (widget.right >= maxCols) {
                if (widget.left < maxCols) {
                    widget.right = maxCols - 1
                } else {
                    val w = min(widget.widthCells, maxCols)
                    widget.left = 0
                    widget.right = w - 1
                }
                widget.widthCells = widget.right - widget.left + 1
                changed = true
            }

            // Clamp height
            if (widget.bottom >= maxRows) {
                if (widget.top < maxRows) {
                    widget.bottom = maxRows - 1
                } else {
                    val h = min(widget.heightCells, maxRows)
                    widget.top = 0
                    widget.bottom = h - 1
                }
                widget.heightCells = widget.bottom - widget.top + 1
                changed = true
            }

            if (changed) {
                modifiedItems.add(widget)
            }
        }

        // 2. Adjust docked items if dock column count changed
        gridItems.filter { it.docked && it.parentId == null }.forEach { item ->
            if (item.left >= newDockColumnCount || item.right >= newDockColumnCount) {
                val occupiedDockCols = gridItems.filter { it.docked && it.id != item.id && it.left < newDockColumnCount }.map { it.left }.toSet()
                val freeSlot = (0 until newDockColumnCount).firstOrNull { !occupiedDockCols.contains(it) }
                if (freeSlot != null) {
                    item.left = freeSlot
                    item.right = freeSlot
                    modifiedItems.add(item)
                } else {
                    item.docked = false
                }
            }
        }

        // 3. For single-cell items (icons, shortcuts, folders) on desktop:
        // Keep those that already fit in place.
        // For overflowing items, find first open cell on their page or spill over to next page.
        val occupiedCells = HashMap<Int, MutableSet<Point>>()
        gridItems.filter { !it.docked && it.parentId == null }.forEach { item ->
            val pageOccupied = occupiedCells.getOrPut(item.page) { HashSet() }
            if (item.left < maxCols && item.right < maxCols && item.top < maxRows && item.bottom < maxRows) {
                for (c in item.left..item.right) {
                    for (r in item.top..item.bottom) {
                        pageOccupied.add(Point(c, r))
                    }
                }
            }
        }

        gridItems.filter { it.isSingleCellType() && !it.docked && it.parentId == null }.forEach { item ->
            if (item.left >= maxCols || item.top >= maxRows) {
                var placed = false
                var targetPage = item.page
                while (!placed) {
                    val pageOccupied = occupiedCells.getOrPut(targetPage) { HashSet() }
                    var foundPoint: Point? = null
                    for (r in 0 until maxRows) {
                        for (c in 0 until maxCols) {
                            val pt = Point(c, r)
                            if (!pageOccupied.contains(pt)) {
                                foundPoint = pt
                                break
                            }
                        }
                        if (foundPoint != null) break
                    }

                    if (foundPoint != null) {
                        item.page = targetPage
                        item.left = foundPoint.x
                        item.right = foundPoint.x
                        item.top = foundPoint.y
                        item.bottom = foundPoint.y
                        pageOccupied.add(foundPoint)
                        modifiedItems.add(item)
                        placed = true
                    } else {
                        targetPage++
                    }
                }
            }
        }

        // 4. Save modified items in database
        if (modifiedItems.isNotEmpty()) {
            ensureBackgroundThread {
                modifiedItems.forEach {
                    if (it.id != null) {
                        context.homeScreenGridItemsDB.updateItemPosition(
                            it.left, it.top, it.right, it.bottom, it.page, it.docked, it.parentId, it.id!!
                        )
                    }
                }
            }
        }
    }

    fun updateColors() {
        folderTitleTextPaint.color = Color.parseColor("#1C1B1F")
        contrastTextPaint.color = context.getProperTextColor()
        contrastTextPaint.setShadowLayer(
            2f, 0f, 0f, context.getProperTextColor().getContrastColor()
        )
        folderBackgroundPaint.color = Color.WHITE
    }

    fun removeAppIcon(item: HomeScreenGridItem) {
        ensureBackgroundThread {
            removeItemFromHomeScreen(item)
            post {
                removeView(widgetViews.firstOrNull { it.tag == item.widgetId })
            }

            gridItems.removeIf { it.id == item.id }
            if (pager.isOutsideOfPageRange()) {
                post {
                    prevPage()
                }
            }
            redrawGrid()
        }
    }

    fun removeWidget(item: HomeScreenGridItem) {
        ensureBackgroundThread {
            removeItemFromHomeScreen(item)
            post {
                removeView(widgetViews.firstOrNull { it.tag == item.widgetId })
                widgetViews.removeIf { it.tag == item.widgetId }
            }
            gridItems.removeIf { it.id == item.id }
        }
    }

    private fun removeItemFromHomeScreen(item: HomeScreenGridItem) {
        ensureBackgroundThread {
            if (item.id != null) {
                shura.home.helpers.LogCatcher.log("HomeScreenGrid", "Removed item ${item.id} (${item.title}) from home screen")
                context.homeScreenGridItemsDB.deleteById(item.id!!)
                val linkedWidgetId = ItemGestureManager.unlinkWidget(context, item.id!!)
                if (linkedWidgetId != -1) {
                    appWidgetHost.deleteAppWidgetId(linkedWidgetId)
                }
                ItemGestureManager.removeConfig(context, item.id!!)
                if (item.parentId != null) {
                    invalidateFolderDrawables(item.parentId)
                    gridItems
                        .filter {
                            it.parentId == item.parentId && it.left > item.left && it.id != item.id
                        }
                        .forEach {
                            it.left -= 1
                        }

                    context.homeScreenGridItemsDB.shiftFolderItems(
                        folderId = item.parentId!!,
                        shiftFrom = item.left,
                        shiftBy = -1,
                        excludingId = item.id
                    )
                }
            }

            if (item.type == ITEM_TYPE_WIDGET && !isBuiltInClockWidget(item.className)) {
                appWidgetHost.deleteAppWidgetId(item.widgetId)
            }

            if (
                item.page != 0 && gridItems
                    .none { it.page == item.page && it.id != item.id && it.parentId == null }
            ) {
                deletePage(item.page)
            }
        }
    }

    fun itemDraggingStarted(draggedGridItem: HomeScreenGridItem) {
        draggedItem = draggedGridItem

        if (draggedGridItem.type == ITEM_TYPE_WIDGET) {
            closeFolder()
            val draggedWidgetView = widgetViews.firstOrNull { it.tag == draggedGridItem.widgetId }
            if (draggedWidgetView != null) {
                val bitmap = try {
                    if (draggedWidgetView.width > 0 && draggedWidgetView.height > 0) {
                        val bmp = Bitmap.createBitmap(
                            draggedWidgetView.width,
                            draggedWidgetView.height,
                            Bitmap.Config.ARGB_8888
                        )
                        val canvas = Canvas(bmp)
                        draggedWidgetView.draw(canvas)
                        bmp
                    } else null
                } catch (e: Exception) {
                    null
                }
                if (bitmap != null) {
                    draggedItem!!.drawable = bitmap.toDrawable(context.resources)
                }
                draggedWidgetView.beGone()
            }
        } else if (draggedItem!!.drawable == null) {
            if (draggedItem?.type == ITEM_TYPE_FOLDER) {
                draggedItem!!.drawable = draggedGridItem.toFolder().generateDrawable()
            } else {
                draggedItem!!.drawable =
                    context.getDrawableForPackageName(draggedGridItem.packageName, draggedGridItem.activityName)
            }
        }

        redrawGrid()
    }

    fun draggedItemMoved(x: Int, y: Int) {
        if (draggedItem == null) {
            return
        }

        currentlyOpenFolder?.also { folder ->
            if (folder.getDrawingRect().contains(x.toFloat(), y.toFloat())) {
                draggingLeftFolderAt = null
            } else {
                draggingLeftFolderAt.also {
                    if (it == null) {
                        draggingLeftFolderAt = System.currentTimeMillis()
                    } else if (System.currentTimeMillis() - it > FOLDER_CLOSE_HOLD_THRESHOLD) {
                        closeFolder()
                    }
                }
            }
        }

        if (
            draggedItemCurrentCoords.first == -1
            && draggedItemCurrentCoords.second == -1 && draggedItem != null
        ) {
            if (draggedItem!!.type == ITEM_TYPE_WIDGET) {
                val draggedWidgetView = widgetViews.firstOrNull { it.tag == draggedItem?.widgetId }
                if (draggedWidgetView != null) {
                    val bitmap = try {
                        if (draggedWidgetView.width > 0 && draggedWidgetView.height > 0) {
                            val bmp = Bitmap.createBitmap(
                                draggedWidgetView.width,
                                draggedWidgetView.height,
                                Bitmap.Config.ARGB_8888
                            )
                            val canvas = Canvas(bmp)
                            draggedWidgetView.draw(canvas)
                            bmp
                        } else null
                    } catch (e: Exception) {
                        null
                    }
                    if (bitmap != null) {
                        draggedItem!!.drawable = bitmap.toDrawable(context.resources)
                    }
                    draggedWidgetView.beGone()
                }
            }
        }

        draggedItemCurrentCoords = Pair(x, y)

        if (draggedItem?.type != ITEM_TYPE_FOLDER && draggedItem?.type != ITEM_TYPE_WIDGET) {
            val center = gridCenters.minBy {
                abs(it.x - draggedItemCurrentCoords.first + sideMargins.left) + abs(it.y - draggedItemCurrentCoords.second + sideMargins.top)
            }
            val coveredCell = getClosestGridCells(center)
            if (coveredCell != null) {
                val coveredFolder = gridItems.firstOrNull {
                    it.type == ITEM_TYPE_FOLDER
                            && it.left == coveredCell.x && it.getDockAdjustedTop(rowCount) == coveredCell.y
                }

                if (
                    coveredFolder != null
                    && coveredFolder.id != draggedItem?.id
                    && currentlyOpenFolder == null
                ) {
                    draggingEnteredNewFolderAt.also {
                        if (it == null) {
                            draggingEnteredNewFolderAt = System.currentTimeMillis()
                        } else if (System.currentTimeMillis() - it > FOLDER_OPEN_HOLD_THRESHOLD) {
                            if (
                                coveredFolder.toFolder().getItems()
                                    .count() >= HomeScreenGridItem.FOLDER_MAX_CAPACITY
                                && draggedItem?.parentId != coveredFolder.id
                            ) {
                                performHapticFeedback()
                                draggingEnteredNewFolderAt = null
                            } else {
                                openFolder(coveredFolder)
                            }
                        }
                    }
                } else {
                    draggingEnteredNewFolderAt = null
                }
            } else {
                draggingEnteredNewFolderAt = null
            }
        }

        pager.handleItemMovement(x, y)
        redrawGrid()
    }

    // figure out at which cell was the item dropped, if it is empty
    fun itemDraggingStopped() {
        widgetViews.forEach {
            it.hasLongPressed = false
        }

        if (draggedItem == null) {
            return
        }

        pager.itemMovementStopped()
        when (draggedItem!!.type) {
            ITEM_TYPE_FOLDER -> moveItem()
            ITEM_TYPE_ICON, ITEM_TYPE_SHORTCUT -> addAppIconOrShortcut()
            ITEM_TYPE_WIDGET -> addWidget()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    fun widgetLongPressed(item: HomeScreenGridItem) {
        resizedWidget = item
        redrawGrid()

        val widgetView = widgetViews.firstOrNull { it.tag == resizedWidget!!.widgetId }
        binding.resizeFrame.beGone()
        if (widgetView != null) {
            val viewX = widgetView.x.toInt()
            val viewY = widgetView.y.toInt()
            val frameRect = Rect(viewX, viewY, viewX + widgetView.width, viewY + widgetView.height)
            val otherGridItems = gridItems.filterVisibleOnCurrentPageOnly()
                .filter { !it.outOfBounds() }
                .filter { it.widgetId != item.widgetId }
                .toMutableList() as ArrayList<HomeScreenGridItem>
            binding.resizeFrame.updateFrameCoords(
                coords = frameRect,
                cellWidth = cellWidth,
                cellHeight = cellHeight,
                sideMargins = sideMargins,
                gridItem = item,
                allGridItems = otherGridItems
            )
            binding.resizeFrame.beVisible()
            binding.resizeFrame.z = 1f     // make sure the frame isnt behind the widget itself
            binding.resizeFrame.onClickListener = {
                hideResizeLines()
            }

            binding.resizeFrame.onResizeListener = { cellsRect ->
                item.left = cellsRect.left
                item.top = cellsRect.top
                item.right = cellsRect.right
                item.bottom = if (cellsRect.bottom > rowCount - 2) {
                    rowCount - 2
                } else {
                    cellsRect.bottom
                }
                updateWidgetPositionAndSize(widgetView, item)

                val widgetTargetCells = ArrayList<Pair<Int, Int>>()
                for (xCell in item.left..item.right) {
                    for (yCell in item.top..item.bottom) {
                        widgetTargetCells.add(Pair(xCell, yCell))
                    }
                }

                gridItems.filterVisibleOnCurrentPageOnly().filter { it.id != item.id }
                    .forEach { gridItem ->
                        for (xCell in gridItem.left..gridItem.right) {
                            for (
                            yCell in gridItem.getDockAdjustedTop(rowCount)
                                .rangeTo(gridItem.getDockAdjustedBottom(rowCount))
                            ) {
                                val cell = Pair(xCell, yCell)
                                val isAnyCellOccupied = widgetTargetCells.contains(cell)
                                if (isAnyCellOccupied) {
                                    if (
                                        gridItem.type == ITEM_TYPE_WIDGET && gridItem.outOfBounds()
                                    ) {
                                        removeWidget(gridItem)
                                    }
                                }
                            }
                        }
                    }

                ensureBackgroundThread {
                    context.homeScreenGridItemsDB.updateItemPosition(
                        left = item.left,
                        top = item.top,
                        right = item.right,
                        bottom = item.bottom,
                        page = item.page,
                        docked = false,
                        parentId = null,
                        id = item.id!!
                    )
                }
            }

            widgetView.ignoreTouches = false
            widgetView.setOnTouchListener(null)
        }
    }

    fun hideResizeLines() {
        if (resizedWidget == null) {
            return
        }

        binding.resizeFrame.beGone()
        widgetViews.firstOrNull { it.tag == resizedWidget!!.widgetId }?.apply {
            ignoreTouches = false
            setOnTouchListener(null)
        }
        resizedWidget = null
    }

    private fun moveItem() {
        val draggedHomeGridItem = gridItems.firstOrNull { it.id == draggedItem?.id }
        val center = gridCenters.minBy {
            abs(it.x - draggedItemCurrentCoords.first + sideMargins.left) + abs(it.y - draggedItemCurrentCoords.second + sideMargins.top)
        }

        var redrawIcons = false
        val gridCells = getClosestGridCells(center)
        if (gridCells != null) {
            val xIndex = gridCells.x
            val yIndex = gridCells.y

            // check if the destination cell is empty
            var isDroppingPositionValid = true
            val wantedCell = Pair(xIndex, yIndex)
            // Check dock boundaries if dropping into dock row
            if (yIndex == rowCount - 1 && xIndex >= dockColumnCount) {
                isDroppingPositionValid = false
            } else {
                gridItems.filterVisibleOnCurrentPageOnly().forEach { item ->
                    for (xCell in item.left..item.right) {
                        for (
                        yCell in item.getDockAdjustedTop(rowCount)
                            .rangeTo(item.getDockAdjustedBottom(rowCount))
                        ) {
                            val cell = Pair(xCell, yCell)
                            val isAnyCellOccupied = wantedCell == cell
                            if (isAnyCellOccupied) {
                                if (item.type == ITEM_TYPE_WIDGET && item.outOfBounds()) {
                                    removeWidget(item)
                                } else {
                                    isDroppingPositionValid = false
                                }
                                return@forEach
                            }
                        }
                    }
                }
            }

            if (isDroppingPositionValid) {
                draggedHomeGridItem?.apply {
                    val oldPage = page
                    left = xIndex
                    top = yIndex
                    right = xIndex
                    bottom = yIndex
                    page = pager.getCurrentPage()
                    docked = yIndex == rowCount - 1

                    ensureBackgroundThread {
                        context.homeScreenGridItemsDB.updateItemPosition(
                            left = left,
                            top = top,
                            right = right,
                            bottom = bottom,
                            page = page,
                            docked = docked,
                            parentId = parentId,
                            id = id!!
                        )

                        if (page != oldPage && oldPage != 0) {
                            if (gridItems.none { it.page == oldPage && it.parentId == null }) {
                                deletePage(oldPage)
                            }
                        }
                    }
                }
                redrawIcons = true
            }
        }

        draggedItem = null
        draggedItemCurrentCoords = Pair(-1, -1)
        if (redrawIcons) {
            redrawGrid()
        }
    }

    private fun addAppIconOrShortcut() {
        var isDroppingPositionValid = false
        var potentialParent: HomeScreenGridItem? = null
        var xIndex: Int? = null
        var yIndex: Int? = null
        var redrawIcons = false

        val folder = currentlyOpenFolder
        if (folder != null && folder.getItemsDrawingRect().contains(
                (draggedItemCurrentCoords.first).toFloat(),
                (draggedItemCurrentCoords.second).toFloat()
            )
        ) {
            val centers = folder.getItemsGridCenters()
            if (centers.isNotEmpty()) {
                val center = centers.minByOrNull {
                    abs(it.second - draggedItemCurrentCoords.first + sideMargins.left)
                        .plus(abs(it.third - draggedItemCurrentCoords.second + sideMargins.top))
                }
                xIndex = center?.first ?: 0
            } else {
                xIndex = 0
            }

            isDroppingPositionValid = true
            potentialParent = folder.item
            yIndex = 0
            redrawIcons = true
        } else {
            val center = gridCenters.minBy {
                abs(it.x - draggedItemCurrentCoords.first + sideMargins.left)
                    .plus(abs(it.y - draggedItemCurrentCoords.second + sideMargins.top))
            }

            val gridCells = getClosestGridCells(center)
            if (gridCells != null) {
                xIndex = gridCells.x
                yIndex = gridCells.y

                // check if the destination cell is empty or a folder
                isDroppingPositionValid = true
                val wantedCell = Pair(xIndex, yIndex)
                if (yIndex == rowCount - 1 && xIndex >= dockColumnCount) {
                    isDroppingPositionValid = false
                } else {
                    gridItems.filterVisibleOnCurrentPageOnly().filter { it.id != draggedItem?.id }
                        .forEach { item ->
                            for (xCell in item.left..item.right) {
                                for (
                                yCell in item.getDockAdjustedTop(rowCount)
                                    .rangeTo(item.getDockAdjustedBottom(rowCount))
                                ) {
                                    val cell = Pair(xCell, yCell)
                                    val isAnyCellOccupied = wantedCell == cell
                                    if (isAnyCellOccupied) {
                                        if (item.type != ITEM_TYPE_WIDGET) {
                                            potentialParent = item
                                        } else {
                                            if (item.type == ITEM_TYPE_WIDGET && item.outOfBounds()) {
                                                removeWidget(item)
                                            } else {
                                                isDroppingPositionValid = false
                                            }
                                        }
                                        return@forEach
                                    }
                                }
                            }
                        }
                }
            }
        }

        if (isDroppingPositionValid) {
            val draggedHomeGridItem = gridItems.firstOrNull { it.id == draggedItem?.id }

            if (potentialParent != null) {
                if (potentialParent.type == ITEM_TYPE_FOLDER) {
                    addAppIconOrShortcut(
                        draggedHomeGridItem,
                        xIndex!!,
                        yIndex!!,
                        potentialParent.id,
                        toFolderEnd = potentialParent != currentlyOpenFolder?.item
                    )
                } else {
                    val parentItem = potentialParent.copy(
                        type = ITEM_TYPE_FOLDER,
                        id = null,
                        title = resources.getString(org.fossify.commons.R.string.folder)
                    )
                    ensureBackgroundThread {
                        val newId = context.homeScreenGridItemsDB.insert(parentItem)
                        parentItem.id = newId
                        potentialParent.apply {
                            parentId = newId
                            left = 0
                            docked = false
                            context.homeScreenGridItemsDB.updateItemPosition(
                                left = left,
                                top = top,
                                right = right,
                                bottom = bottom,
                                page = page,
                                docked = false,
                                parentId = newId,
                                id = id!!
                            )
                        }
                        (context as? MainActivity)?.runOnUiThread {
                            gridItems.add(parentItem)
                            invalidateFolderDrawables()
                            addAppIconOrShortcut(draggedHomeGridItem, xIndex!!, yIndex!!, newId)
                        }
                    }
                }
                return
            } else {
                addAppIconOrShortcut(draggedHomeGridItem, xIndex!!, yIndex!!)
                return
            }
        } else {
            performHapticFeedback()
            redrawIcons = true
        }

        draggedItem = null
        draggedItemCurrentCoords = Pair(-1, -1)
        if (redrawIcons) {
            redrawGrid()
        }
    }

    private fun addAppIconOrShortcut(
        draggedHomeGridItem: HomeScreenGridItem?,
        xIndex: Int,
        yIndex: Int,
        newParentId: Long? = null,
        toFolderEnd: Boolean = true,
    ) {
        if (newParentId != null) {
            invalidateFolderDrawables(newParentId)
        }
        if (newParentId != null && newParentId != draggedHomeGridItem?.parentId) {
            gridItems.firstOrNull { it.id == newParentId }?.also {
                if (it.toFolder().getItems().count() >= HomeScreenGridItem.FOLDER_MAX_CAPACITY) {
                    performHapticFeedback()
                    draggedItem = null
                    draggedItemCurrentCoords = Pair(-1, -1)
                    redrawGrid()
                    return
                }
            }
        }

        val finalXIndex = if (newParentId != null) {
            if (toFolderEnd) {
                gridItems
                    .firstOrNull { it.id == newParentId }
                    ?.toFolder()
                    ?.getItems()
                    ?.maxOfOrNull { it.left + 1 } ?: 0
            } else {
                min(
                    a = xIndex,
                    b = gridItems
                        .firstOrNull { it.id == newParentId }
                        ?.toFolder()
                        ?.getItems()
                        ?.maxOfOrNull {
                            if (draggedHomeGridItem?.parentId == newParentId) {
                                it.left
                            } else {
                                it.left + 1
                            }
                        } ?: 0)
            }
        } else {
            xIndex
        }
        // we are moving an existing home screen item from one place to another
        if (draggedHomeGridItem != null) {
            draggedHomeGridItem.apply {
                val oldParentId = parentId
                val oldLeft = left
                val oldPage = page
                left = finalXIndex
                top = yIndex
                right = finalXIndex
                bottom = yIndex
                page = pager.getCurrentPage()
                docked = if (newParentId != null) false else (yIndex == rowCount - 1)
                parentId = newParentId

                val oldParent = gridItems.firstOrNull { it.id == oldParentId }
                val deleteOldParent = if (oldParent?.toFolder()?.getItems()?.isEmpty() == true) {
                    gridItems.remove(oldParent)
                    true
                } else {
                    false
                }

                ensureBackgroundThread {
                    context.homeScreenGridItemsDB.updateItemPosition(
                        left = left,
                        top = top,
                        right = right,
                        bottom = bottom,
                        page = page,
                        docked = docked,
                        parentId = newParentId,
                        id = id!!
                    )
                    if (deleteOldParent && oldParentId != null) {
                        context.homeScreenGridItemsDB.deleteById(oldParentId)
                    } else if (
                        oldParentId != null
                        && gridItems.none { it.parentId == oldParentId && it.left == oldLeft }
                    ) {
                        gridItems
                            .filter {
                                it.parentId == oldParentId && it.left > oldLeft && it.id != id
                            }
                            .forEach {
                                it.left -= 1
                            }
                        context.homeScreenGridItemsDB.shiftFolderItems(oldParentId, oldLeft, -1, id)
                    }

                    if (
                        newParentId != null
                        && gridItems.any { it.parentId == newParentId && it.left == left }
                        && (newParentId != oldParentId || left != oldLeft)
                    ) {
                        gridItems
                            .filter { it.parentId == newParentId && it.left >= left && it.id != id }
                            .forEach {
                                it.left += 1
                            }

                        context.homeScreenGridItemsDB.shiftFolderItems(
                            folderId = newParentId,
                            shiftFrom = left - 1,
                            shiftBy = +1,
                            excludingId = id
                        )
                    }

                    if (page != oldPage && oldPage != 0) {
                        if (gridItems.none { it.page == oldPage && it.parentId == null }) {
                            deletePage(oldPage)
                        }
                    }
                }
            }
        } else if (draggedItem != null) {
            // we are dragging a new item at the home screen from the All Apps fragment
            val newHomeScreenGridItem = HomeScreenGridItem(
                id = null,
                left = finalXIndex,
                top = yIndex,
                right = finalXIndex,
                bottom = yIndex,
                page = pager.getCurrentPage(),
                packageName = draggedItem!!.packageName,
                activityName = draggedItem!!.activityName,
                title = draggedItem!!.title,
                type = draggedItem!!.type,
                className = "",
                widgetId = -1,
                shortcutId = "",
                icon = draggedItem!!.icon,
                docked = yIndex == rowCount - 1,
                parentId = newParentId,
                drawable = draggedItem!!.drawable,
                providerInfo = draggedItem!!.providerInfo,
                activityInfo = draggedItem!!.activityInfo
            )

            fun finalizeFolderOrder(newItem: HomeScreenGridItem) {
                if (
                    newParentId != null
                    && gridItems.any { it.parentId == newParentId && it.left == newItem.left }
                ) {
                    gridItems
                        .filter {
                            it.parentId == newParentId
                                    && it.left >= newItem.left && it.id != newItem.id
                        }
                        .forEach {
                            it.left += 1
                        }

                    context.homeScreenGridItemsDB.shiftFolderItems(
                        folderId = newParentId,
                        shiftFrom = newItem.left - 1,
                        shiftBy = +1,
                        excludingId = newItem.id
                    )
                }
            }

            if (newHomeScreenGridItem.type == ITEM_TYPE_ICON) {
                ensureBackgroundThread {
                    storeAndShowGridItem(newHomeScreenGridItem)
                    finalizeFolderOrder(newHomeScreenGridItem)
                }
            } else if (newHomeScreenGridItem.type == ITEM_TYPE_SHORTCUT) {
                (context as? MainActivity)?.handleShorcutCreation(newHomeScreenGridItem.activityInfo!!) { shortcutId, label, icon ->
                    ensureBackgroundThread {
                        newHomeScreenGridItem.shortcutId = shortcutId
                        newHomeScreenGridItem.title = label
                        newHomeScreenGridItem.icon = icon.toBitmap()
                        newHomeScreenGridItem.drawable = icon
                        storeAndShowGridItem(newHomeScreenGridItem)
                        finalizeFolderOrder(newHomeScreenGridItem)
                    }
                }
            }
        }

        draggedItem = null
        draggedItemCurrentCoords = Pair(-1, -1)
        redrawGrid()
    }

    /**
     * Persist a new item off the UI thread, then update the in-memory grid on the main thread.
     * Used by Sidebar Add Element as well as launcher pin/add flows.
     */
    fun storeAndShowGridItem(item: HomeScreenGridItem, onStored: (() -> Unit)? = null) {
        ensureBackgroundThread { persistAndShowGridItem(item, onStored) }
    }

    /**
     * Variant for callers already running a background transaction, allowing cell allocation
     * and insertion to remain serialized as one operation.
     */
    fun storeAndShowGridItemFromBackground(item: HomeScreenGridItem, onStored: (() -> Unit)? = null) {
        persistAndShowGridItem(item, onStored)
    }

    private fun persistAndShowGridItem(item: HomeScreenGridItem, onStored: (() -> Unit)?) {
        try {
            val newId = context.homeScreenGridItemsDB.insert(item)
            item.id = newId
            post {
                if (gridItems.none { it.id == newId }) gridItems.add(item)
                redrawGrid()
                onStored?.invoke()
            }
        } catch (e: Exception) {
            android.util.Log.e("HomeScreenGrid", "Failed to persist home screen item '${item.title}'", e)
            post {
                android.widget.Toast.makeText(
                    context,
                    "Could not add item to the home screen",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun addWidget() {
        val center = gridCenters.minBy {
            abs(it.x - draggedItemCurrentCoords.first + sideMargins.left) + abs(it.y - draggedItemCurrentCoords.second + sideMargins.top)
        }

        val gridCells = getClosestGridCells(center)
        if (gridCells != null) {
            val widgetRect = getWidgetOccupiedRect(gridCells)
            val widgetTargetCells = ArrayList<Pair<Int, Int>>()
            for (xCell in widgetRect.left..widgetRect.right) {
                for (yCell in widgetRect.top..widgetRect.bottom) {
                    widgetTargetCells.add(Pair(xCell, yCell))
                }
            }

            var areAllCellsEmpty = true
            gridItems.filterVisibleOnCurrentPageOnly().filter { it.id != draggedItem?.id }
                .forEach { item ->
                    for (xCell in item.left..item.right) {
                        for (yCell in item.getDockAdjustedTop(rowCount)
                            .rangeTo(item.getDockAdjustedBottom(rowCount))
                        ) {
                            val cell = Pair(xCell, yCell)
                            val isAnyCellOccupied = widgetTargetCells.contains(cell)
                            if (isAnyCellOccupied) {
                                if (item.type == ITEM_TYPE_WIDGET && item.outOfBounds()) {
                                    removeWidget(item)
                                } else {
                                    areAllCellsEmpty = false
                                    return@forEach
                                }
                            }
                        }
                    }
                }

            if (areAllCellsEmpty) {
                val widgetItem = draggedItem!!.copy()
                val oldPage = widgetItem.page
                widgetItem.apply {
                    left = widgetRect.left
                    top = widgetRect.top
                    right = widgetRect.right
                    bottom = widgetRect.bottom
                    page = pager.getCurrentPage()
                }

                ensureBackgroundThread {
                    // store the new widget at creating it, else just move the existing one
                    if (widgetItem.id == null) {
                        val itemId = context.homeScreenGridItemsDB.insert(widgetItem)
                        widgetItem.id = itemId
                        post {
                            bindWidget(widgetItem)
                        }
                    } else {
                        context.homeScreenGridItemsDB.updateItemPosition(
                            widgetItem.left,
                            widgetItem.top,
                            widgetItem.right,
                            widgetItem.bottom,
                            pager.getCurrentPage(),
                            false,
                            null,
                            widgetItem.id!!
                        )
                        val widgetView = widgetViews.firstOrNull { it.tag == widgetItem.widgetId }
                        if (widgetView != null && !widgetItem.outOfBounds()) {
                            post {
                                val widgetPos = calculateWidgetPos(widgetItem.getTopLeft(rowCount))
                                widgetView.x = widgetPos.x.toFloat()
                                widgetView.y = widgetPos.y.toFloat()
                                widgetView.beVisible()
                            }
                        }

                        gridItems.firstOrNull { it.id == widgetItem.id }?.apply {
                            left = widgetItem.left
                            right = widgetItem.right
                            top = widgetItem.top
                            bottom = widgetItem.bottom
                            page = pager.getCurrentPage()
                        }


                        if (widgetItem.page != oldPage && oldPage != 0) {
                            if (gridItems.none { it.page == oldPage && it.parentId == null }) {
                                deletePage(oldPage)
                            }
                        }
                    }
                }
            } else {
                performHapticFeedback()
                widgetViews.firstOrNull { it.tag == draggedItem?.widgetId }?.apply {
                    post {
                        beVisible()
                    }
                }
            }
        }

        draggedItem = null
        draggedItemCurrentCoords = Pair(-1, -1)
        redrawGrid()
    }

    private fun bindWidget(item: HomeScreenGridItem) {
        if (item.outOfBounds()) {
            return
        }

        if (isBuiltInClockWidget(item.className)) {
            if (item.widgetId >= 0) {
                item.widgetId = -((System.currentTimeMillis() % 100000).toInt() + 100)
                ensureBackgroundThread {
                    context.homeScreenGridItemsDB.updateWidgetId(item.widgetId, item.id!!)
                }
            }
            placeBuiltInClockWidget(item)
            return
        }

        val activity = context as MainActivity
        val appWidgetProviderInfo = item.providerInfo
            ?: appWidgetManager!!.installedProviders
                .firstOrNull { it.provider.className == item.className }
        if (appWidgetProviderInfo != null) {
            item.widgetId = appWidgetHost.allocateAppWidgetId()
            ensureBackgroundThread {
                context.homeScreenGridItemsDB.updateWidgetId(item.widgetId, item.id!!)
            }

            activity.handleWidgetBinding(
                appWidgetManager = appWidgetManager,
                appWidgetId = item.widgetId,
                appWidgetInfo = appWidgetProviderInfo
            ) { canBind ->
                if (canBind) {
                    if (appWidgetProviderInfo.configure != null) {
                        activity.handleWidgetConfigureScreen(
                            appWidgetHost = appWidgetHost,
                            appWidgetId = item.widgetId
                        ) { success ->
                            if (success) {
                                placeAppWidget(appWidgetProviderInfo, item)
                            } else {
                                removeItemFromHomeScreen(item)
                            }
                        }
                    } else {
                        placeAppWidget(appWidgetProviderInfo, item)
                    }
                } else {
                    removeItemFromHomeScreen(item)
                }

                if (pager.isOutsideOfPageRange()) {
                    prevPage(redraw = true)
                }
            }
        }
    }

    private fun placeAppWidget(
        appWidgetProviderInfo: AppWidgetProviderInfo,
        item: HomeScreenGridItem,
    ) {
        // we have to pass the base context here, else there will be errors with the themes
        val widgetView = appWidgetHost.createView(
            (context as MainActivity).baseContext,
            item.widgetId,
            appWidgetProviderInfo
        ) as MyAppWidgetHostView
        widgetView.tag = item.widgetId
        widgetView.setAppWidget(item.widgetId, appWidgetProviderInfo)
        widgetView.longPressListener = { x, y ->
            val activity = context as? MainActivity
            if (activity?.isAllAppsFragmentExpanded() == false) {
                activity.showHomeIconMenu(x, widgetView.y, item, false)
                performHapticFeedback()
            }
        }
        widgetView.dragListener = { event, isUp ->
            val activity = context as? MainActivity
            activity?.handleWidgetDrag(item, event, isUp)
        }

        widgetView.onIgnoreInterceptedListener = {
            hideResizeLines()
        }

        val widgetSize = updateWidgetPositionAndSize(widgetView, item)
        addView(widgetView, widgetSize.width, widgetSize.height)
        widgetViews.add(widgetView)

        // remove the drawable so that it gets refreshed on long press
        item.drawable = null
        // Delete existing widget if it has already been loaded to the list
        gridItems.removeIf { it.id == item.id }
        gridItems.add(item)
        (context as MainActivity).clearWidgetsSearch()
    }

    private fun placeBuiltInClockWidget(item: HomeScreenGridItem) {
        widgetViews.firstOrNull { it.tag == item.widgetId }?.let {
            removeView(it)
            widgetViews.remove(it)
        }

        val clockView = BuiltInClockWidgetView(context)
        clockView.tag = item.widgetId
        clockView.longPressListener = { x, y ->
            val activity = context as? MainActivity
            if (activity?.isAllAppsFragmentExpanded() == false) {
                activity.showHomeIconMenu(x, clockView.y, item, false)
                performHapticFeedback()
            }
        }
        clockView.dragListener = { event, isUp ->
            val activity = context as? MainActivity
            activity?.handleWidgetDrag(item, event, isUp)
        }

        clockView.onIgnoreInterceptedListener = {
            hideResizeLines()
        }

        val widgetSize = updateWidgetPositionAndSize(clockView, item)
        addView(clockView, widgetSize.width, widgetSize.height)
        widgetViews.add(clockView)

        item.drawable = null
        gridItems.removeIf { it.id == item.id }
        gridItems.add(item)
        (context as? MainActivity)?.clearWidgetsSearch()
    }

    private fun updateWidgetPositionAndSize(
        widgetView: AppWidgetHostView,
        item: HomeScreenGridItem,
    ): Size {
        val currentViewPosition = pager.getCurrentViewPositionInFullPageSpace() * width.toFloat()
        val widgetPos = calculateWidgetPos(item.getTopLeft(rowCount))
        widgetView.x = widgetPos.x + width * item.page - currentViewPosition
        widgetView.y = widgetPos.y.toFloat()
        val widgetWidth = item.getWidthInCells() * cellWidth
        val widgetHeight = item.getHeightInCells() * cellHeight

        val density = context.resources.displayMetrics.density
        val widgetDpWidth = (widgetWidth / density).toInt()
        val widgetDpHeight = (widgetHeight / density).toInt()

        if (widgetView !is BuiltInClockWidgetView) {
            val widgetPadding = (4 * density).toInt()
            widgetView.setPadding(widgetPadding, widgetPadding, widgetPadding, widgetPadding)
            widgetView.clipToPadding = false
            if (isSPlus()) {
                val sizes = listOf(SizeF(widgetDpWidth.toFloat(), widgetDpHeight.toFloat()))
                widgetView.updateAppWidgetSize(Bundle(), sizes)
            } else {
                widgetView.updateAppWidgetSize(
                    Bundle(),
                    widgetDpWidth,
                    widgetDpHeight,
                    widgetDpWidth,
                    widgetDpHeight
                )
            }
        }

        widgetView.layoutParams?.width = widgetWidth
        widgetView.layoutParams?.height = widgetHeight
        widgetView.requestLayout()
        widgetView.invalidate()
        return Size(widgetWidth, widgetHeight)
    }

    fun getGridItem(id: Long?): HomeScreenGridItem? = gridItems.firstOrNull { it.id == id }

    private fun calculateWidgetPos(topLeft: Point): Point {
        val cell = cells[topLeft]!!
        return Point(
            cell.left + sideMargins.left,
            cell.top + sideMargins.top
        )
    }

    // convert stuff like 102x192 to grid cells like 0x1
    private fun getClosestGridCells(center: Point): Point? {
        return cells.entries.firstOrNull { (_, cell) -> center.x == cell.centerX() && center.y == cell.centerY() }?.key
    }

    fun redrawGrid() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            setWillNotDraw(false)
            invalidate()
            binding.drawingArea.invalidate()
        } else {
            post {
                setWillNotDraw(false)
                invalidate()
                binding.drawingArea.invalidate()
            }
        }
    }

    private fun getFakeWidth() = width - sideMargins.left - sideMargins.right

    private fun getFakeHeight() = height - sideMargins.top - sideMargins.bottom

    fun drawInto(canvas: Canvas) {
        if (cells.isEmpty()) {
            fillCellSizes()
        }

        val currentXFactor = pager.getXFactorForCurrentPage()
        val lastXFactor = pager.getXFactorForLastPage()

        fun handleMainGridItemDrawing(item: HomeScreenGridItem, xFactor: Float) {
            val offsetX = sideMargins.left + (this@HomeScreenGrid.width * xFactor).toInt()
            val offsetY = sideMargins.top
            cells[item.getTopLeft(rowCount)]!!.withOffset(offsetX, offsetY) {
                canvas.drawItemInCell(item, this)
            }
        }

        gridItems
            .filter {
                it.isSingleCellType() && pager.isItemOnCurrentPage(it)
                        && !it.docked && it.parentId == null
            }
            .forEach { item ->
                if (item.outOfBounds()) {
                    return@forEach
                }

                handleMainGridItemDrawing(item, currentXFactor)
            }

        gridItems
            .filter { it.isSingleCellType() && it.docked && it.parentId == null }
            .forEach { item ->
                if (item.outOfBounds()) {
                    return@forEach
                }

                handleMainGridItemDrawing(item, 0f)
            }

        if (pager.isAnimatingPageChange()) {
            gridItems
                .filter {
                    it.isSingleCellType() && pager.isItemOnLastPage(it)
                            && !it.docked && it.parentId == null
                }
                .forEach { item ->
                    if (item.outOfBounds()) {
                        return@forEach
                    }

                    handleMainGridItemDrawing(item, lastXFactor)
                }
        }

        if (pager.isSwiped()) {
            gridItems
                .filter {
                    it.isSingleCellType()
                            && pager.isItemInSwipeRange(it)
                            && !it.docked
                            && it.parentId == null
                }.forEach { item ->
                    if (item.outOfBounds()) {
                        return@forEach
                    }

                    handleMainGridItemDrawing(item, lastXFactor)
                }
        }

        if (isFirstDraw) {
            gridItems
                .filter { it.type == ITEM_TYPE_WIDGET && !it.outOfBounds() }
                .forEach { item ->
                    if (isBuiltInClockWidget(item.className)) {
                        placeBuiltInClockWidget(item)
                    } else {
                        val providerInfo = item.providerInfo
                            ?: appWidgetManager!!.installedProviders
                                .firstOrNull { it.provider.className == item.className }

                        if (providerInfo != null) {
                            placeAppWidget(providerInfo, item)
                        } else {
                            removeWidget(item)
                        }
                    }
                }
        } else {
            gridItems
                .filter { it.type == ITEM_TYPE_WIDGET && !it.outOfBounds() }
                .forEach { item ->
                    val existingView = widgetViews.firstOrNull { it.tag == item.widgetId }
                    if (existingView != null) {
                        updateWidgetPositionAndSize(existingView, item)
                    } else {
                        if (isBuiltInClockWidget(item.className)) {
                            placeBuiltInClockWidget(item)
                        } else {
                            val providerInfo = item.providerInfo
                                ?: appWidgetManager!!.installedProviders
                                    .firstOrNull { it.provider.className == item.className }

                            if (providerInfo != null) {
                                placeAppWidget(providerInfo, item)
                            } else {
                                removeWidget(item)
                            }
                        }
                    }
                }
        }

        // Only draw page indicators when there is a need for it
        if (pager.shouldDisplayPageChangeIndicator()) {
            val pageCount = pager.getPageCount()
            val pageIndicatorsRequiredWidth =
                pageCount * pageIndicatorRadius * 2 + pageCount * (pageIndicatorMargin - 1)
            val usableWidth = getFakeWidth()
            val pageIndicatorsStart =
                (usableWidth - pageIndicatorsRequiredWidth) / 2 + sideMargins.left
            var currentPageIndicatorLeft = pageIndicatorsStart
            val pageIndicatorY = pageIndicatorsYPos.toFloat() + sideMargins.top + iconMargin
            val pageIndicatorStep = pageIndicatorRadius * 2 + pageIndicatorMargin
            emptyPageIndicatorPaint.alpha = pager.getPageChangeIndicatorsAlpha()
            // Draw empty page indicators
            for (page in 0 until pageCount) {
                canvas.drawCircle(
                    currentPageIndicatorLeft + pageIndicatorRadius,
                    pageIndicatorY,
                    pageIndicatorRadius,
                    emptyPageIndicatorPaint
                )
                currentPageIndicatorLeft += pageIndicatorStep
            }

            // Draw current page indicator on exact position
            val currentIndicatorPosition =
                pageIndicatorsStart + pager.getCurrentViewPositionInFullPageSpace() * pageIndicatorStep
            currentPageIndicatorPaint.alpha = pager.getPageChangeIndicatorsAlpha()
            canvas.drawCircle(
                currentIndicatorPosition + pageIndicatorRadius,
                pageIndicatorY,
                pageIndicatorRadius,
                currentPageIndicatorPaint
            )
        }

        val folder = currentlyOpenFolder
        if (folder != null) {
            val items = folder.getItems()
            val folderRect = folder.getDrawingRect()

            val currentViewPosition =
                pager.getCurrentViewPositionInFullPageSpace() * width.toFloat()
            val rectOffset = width * folder.item.page - currentViewPosition
            folderRect.offset(rectOffset, 0f)

            canvas.withScale(
                x = folder.scale,
                y = folder.scale,
                pivotX = folderRect.centerX(),
                pivotY = folderRect.centerY()
            ) {
                val folderCornerRadius = 28f * resources.displayMetrics.density

                // Ambient elevation shadow beneath folder container
                val shadowLayers = 4
                for (i in shadowLayers downTo 1) {
                    val spread = i * 2f * resources.displayMetrics.density / folder.scale
                    folderShadowLayerPaint.color = Color.argb(10, 0, 0, 0)
                    folderShadowLayerRect.set(
                        folderRect.left - spread * 0.5f,
                        folderRect.top + spread * 0.5f,
                        folderRect.right + spread * 0.5f,
                        folderRect.bottom + spread * 1.5f
                    )
                    val layerRadius = (folderCornerRadius + spread * 0.5f) / folder.scale
                    canvas.drawRoundRect(folderShadowLayerRect, layerRadius, layerRadius, folderShadowLayerPaint)
                }

                // Folder card surface
                canvas.drawRoundRect(
                    folderRect,
                    folderCornerRadius / folder.scale,
                    folderCornerRadius / folder.scale,
                    folderBackgroundPaint
                )

                // Subtle outline around folder popup
                folderPopupBorderPaint.strokeWidth = 1f * resources.displayMetrics.density / folder.scale
                canvas.drawRoundRect(
                    folderRect,
                    folderCornerRadius / folder.scale,
                    folderCornerRadius / folder.scale,
                    folderPopupBorderPaint
                )

                val headerRect = folder.getHeaderRect()
                val addBtnRect = folder.getFolderAddButtonRect()

                // Folder Title on the left
                val titleWidth = (addBtnRect.left - headerRect.left - folderPadding).toInt().coerceAtLeast(10)
                val staticLayout = StaticLayout.Builder
                    .obtain(
                        folder.item.title,
                        0,
                        folder.item.title.length,
                        folderTitleTextPaint,
                        titleWidth
                    )
                    .setMaxLines(1)
                    .setEllipsize(TextUtils.TruncateAt.END)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .build()

                val textY = headerRect.top + (folder.headerHeight - folderTitleTextPaint.textSize) / 2f
                withTranslation(headerRect.left, textY) {
                    staticLayout.draw(canvas)
                }

                // (+) button on the right
                canvas.drawCircle(addBtnRect.centerX(), addBtnRect.centerY(), addBtnRect.width() / 2f, folderPlusBgPaint)

                folderPlusStrokePaint.strokeWidth = 2f * resources.displayMetrics.density
                val plusArm = addBtnRect.width() * 0.26f
                canvas.drawLine(
                    addBtnRect.centerX() - plusArm,
                    addBtnRect.centerY(),
                    addBtnRect.centerX() + plusArm,
                    addBtnRect.centerY(),
                    folderPlusStrokePaint
                )
                canvas.drawLine(
                    addBtnRect.centerX(),
                    addBtnRect.centerY() - plusArm,
                    addBtnRect.centerX(),
                    addBtnRect.centerY() + plusArm,
                    folderPlusStrokePaint
                )

                items.forEach { item ->
                    val itemRect = folder.getItemRect(item)
                    canvas.drawItemInCell(item, itemRect)
                }
            }
        }

        if (draggedItem != null && draggedItemCurrentCoords.first != -1 && draggedItemCurrentCoords.second != -1) {
            if (draggedItem!!.isSingleCellType()) {
                if (folder != null && folder.getItemsDrawingRect().contains(
                        (draggedItemCurrentCoords.first).toFloat(),
                        (draggedItemCurrentCoords.second).toFloat()
                    )
                ) {

                    val center = folder.getItemsGridCenters().minBy {
                        abs(it.second - draggedItemCurrentCoords.first + sideMargins.left) + abs(it.third - draggedItemCurrentCoords.second + sideMargins.top)
                    }
                    val cellSize = folder.getCellSize()

                    val shadowX = center.second - cellSize / 2 + iconMargin + iconSize / 2f
                    val shadowY = center.third - cellSize / 2 + iconMargin + iconSize / 2

                    val halfSize = iconSize / 2f
                    val dropSquirclePath = createSquirclePath(
                        shadowX - halfSize,
                        shadowY.toFloat() - halfSize,
                        shadowX + halfSize,
                        shadowY.toFloat() + halfSize
                    )
                    val isFolderFull = folder.getItems().count() >= HomeScreenGridItem.FOLDER_MAX_CAPACITY &&
                        draggedItem?.parentId != folder.item.id
                    if (isFolderFull) {
                        canvas.drawPath(dropSquirclePath, dragShadowBlockedFillPaint)
                        canvas.drawPath(dropSquirclePath, dragShadowBlockedStrokePaint)
                    } else {
                        canvas.drawPath(dropSquirclePath, dragShadowCirclePaint)
                    }
                } else {
                    // draw drop indicator or full occupied block
                    val center = gridCenters.minBy {
                        abs(it.x - draggedItemCurrentCoords.first + sideMargins.left) + abs(it.y - draggedItemCurrentCoords.second + sideMargins.top)
                    }

                    val gridCells = getClosestGridCells(center)
                    if (gridCells != null) {
                        cells[gridCells]?.let { cell ->
                            val shadowX = cell.centerX().toFloat() + sideMargins.left
                            val shadowY = if (gridCells.y == rowCount - 1) {
                                cellHeight - iconMargin - iconSize / 2f
                            } else {
                                iconMargin + iconSize / 2f
                            } + sideMargins.top + cell.top

                            val halfSize = iconSize / 2f
                            val dropSquirclePath = createSquirclePath(
                                shadowX - halfSize,
                                shadowY - halfSize,
                                shadowX + halfSize,
                                shadowY + halfSize
                            )

                            val hoveredItem = gridItems.filterVisibleOnCurrentPageOnly()
                                .filter { it.id != draggedItem?.id }
                                .firstOrNull { item ->
                                    gridCells.x in item.left..item.right &&
                                    gridCells.y in item.getDockAdjustedTop(rowCount)..item.getDockAdjustedBottom(rowCount)
                                }

                            val isDockOutOfRange = gridCells.y == rowCount - 1 && gridCells.x >= dockColumnCount

                            if (hoveredItem?.type == ITEM_TYPE_WIDGET) {
                                val topLeftCell = cells[Point(hoveredItem.left, hoveredItem.top)]
                                val bottomRightCell = cells[Point(hoveredItem.right, hoveredItem.bottom)]
                                if (topLeftCell != null && bottomRightCell != null) {
                                    val widgetLeft = topLeftCell.left.toFloat() + sideMargins.left
                                    val widgetTop = topLeftCell.top.toFloat() + sideMargins.top
                                    val widgetRight = bottomRightCell.right.toFloat() + sideMargins.left
                                    val widgetBottom = bottomRightCell.bottom.toFloat() + sideMargins.top
                                    val widgetRect = RectF(widgetLeft, widgetTop, widgetRight, widgetBottom)
                                    val cornerRadius = 16f * resources.displayMetrics.density
                                    canvas.drawRoundRect(widgetRect, cornerRadius, cornerRadius, dragShadowBlockedFillPaint)
                                    canvas.drawRoundRect(widgetRect, cornerRadius, cornerRadius, dragShadowBlockedStrokePaint)
                                } else {
                                    canvas.drawPath(dropSquirclePath, dragShadowBlockedFillPaint)
                                    canvas.drawPath(dropSquirclePath, dragShadowBlockedStrokePaint)
                                }
                            } else if (isDockOutOfRange) {
                                canvas.drawPath(dropSquirclePath, dragShadowBlockedFillPaint)
                                canvas.drawPath(dropSquirclePath, dragShadowBlockedStrokePaint)
                            } else if (hoveredItem != null) {
                                val isFolderFull = hoveredItem.type == ITEM_TYPE_FOLDER &&
                                    hoveredItem.toFolder().getItems().count() >= HomeScreenGridItem.FOLDER_MAX_CAPACITY
                                val isFolderDropOnFolder = draggedItem?.type == ITEM_TYPE_FOLDER && hoveredItem.type == ITEM_TYPE_FOLDER
                                val isWidgetDropOnItem = draggedItem?.type == ITEM_TYPE_WIDGET

                                if (isFolderFull || isFolderDropOnFolder || isWidgetDropOnItem) {
                                    canvas.drawPath(dropSquirclePath, dragShadowBlockedFillPaint)
                                    canvas.drawPath(dropSquirclePath, dragShadowBlockedStrokePaint)
                                } else {
                                    canvas.drawPath(dropSquirclePath, dragShadowCirclePaint)
                                }
                            } else {
                                canvas.drawPath(dropSquirclePath, dragShadowCirclePaint)
                            }
                        }
                    }
                }

                // show the app icon itself at dragging, move it above the finger a bit to make it visible
                val drawableX = (draggedItemCurrentCoords.first - iconSize / 1.5f).toInt()
                val drawableY = (draggedItemCurrentCoords.second - iconSize / 1.2f).toInt()
                val newDrawable = if (draggedItem?.type == ITEM_TYPE_FOLDER) {
                    if (draggedItem!!.icon != null) {
                        draggedItem!!.icon?.toDrawable(context.resources)
                    } else {
                        draggedItem!!.toFolder().generateDrawable()
                    }
                } else {
                    if (draggedItem!!.icon != null) {
                        draggedItem!!.icon?.toDrawable(context.resources)
                    } else {
                        draggedItem!!.drawable?.constantState?.newDrawable()?.mutate()
                    }
                }
                newDrawable?.setBounds(
                    drawableX,
                    drawableY,
                    drawableX + iconSize,
                    drawableY + iconSize
                )
                newDrawable?.draw(canvas)
            } else if (draggedItem!!.type == ITEM_TYPE_WIDGET) {
                // at first draw we are loading the widget from the database at some exact spot, not dragging it
                if (!isFirstDraw) {
                    val center = gridCenters.minBy {
                        abs(it.x - draggedItemCurrentCoords.first + sideMargins.left) + abs(it.y - draggedItemCurrentCoords.second + sideMargins.top)
                    }

                    val gridCells = getClosestGridCells(center)
                    if (gridCells != null) {
                        val widgetRect = getWidgetOccupiedRect(gridCells)
                        val widgetPos = calculateWidgetPos(Point(widgetRect.left, widgetRect.top))
                        val leftSide = widgetPos.x.toFloat()
                        val topSide = widgetPos.y.toFloat()
                        val rightSide = leftSide + draggedItem!!.getWidthInCells() * cellWidth
                        val bottomSide = topSide + draggedItem!!.getHeightInCells() * cellHeight
                        canvas.drawRoundRect(
                            leftSide,
                            topSide,
                            rightSide,
                            bottomSide,
                            roundedCornerRadius,
                            roundedCornerRadius,
                            dragShadowCirclePaint
                        )
                    }

                    // show the widget preview itself at dragging
                    draggedItem!!.drawable?.also { drawable ->
                        val aspectRatio = drawable.minimumHeight / drawable.minimumWidth.toFloat()
                        val drawableX =
                            (draggedItemCurrentCoords.first - drawable.minimumWidth / 2f).toInt()
                        val drawableY =
                            (draggedItemCurrentCoords.second - drawable.minimumHeight / 3f).toInt()
                        val drawableWidth =
                            draggedItem!!.getWidthInCells() * cellWidth - iconMargin * (draggedItem!!.getWidthInCells() - 1)
                        drawable.setBounds(
                            drawableX,
                            drawableY,
                            drawableX + drawableWidth,
                            (drawableY + drawableWidth * aspectRatio).toInt()
                        )
                        drawable.draw(canvas)
                    }
                }
            }
        }

        isFirstDraw = false
    }

    private fun fillCellSizes() {
        cellWidth = getFakeWidth() / columnCount
        dockCellWidth = getFakeWidth() / dockColumnCount
        cellHeight = getFakeHeight() / rowCount
        val extraXMargin = if (cellWidth > cellHeight) {
            (cellWidth - cellHeight) / 2
        } else {
            0
        }
        val extraYMargin = if (cellHeight > cellWidth) {
            (cellHeight - cellWidth) / 2
        } else {
            0
        }
        val effectiveCellWidth = min(cellWidth, dockCellWidth)
        val maxCols = max(columnCount, dockColumnCount)
        iconMargin =
            (context.resources.getDimension(R.dimen.icon_side_margin) * 0.12f).toInt().coerceAtLeast(1)
        iconSize = min(effectiveCellWidth, cellHeight) - 2 * iconMargin
        dockCellY = (rowCount - 1) * cellHeight
        pageIndicatorsYPos = dockCellY + extraYMargin

        cells.clear()
        gridCenters.clear()

        for (i in 0 until columnCount) {
            for (j in 0 until rowCount - 1) {
                val rect = Rect(
                    i * cellWidth + extraXMargin,
                    j * cellHeight + extraYMargin,
                    (i + 1) * cellWidth - extraXMargin,
                    (j + 1) * cellHeight - extraYMargin,
                )
                cells[Point(i, j)] = rect
                gridCenters.add(Point(rect.centerX(), rect.centerY()))
            }
        }

        val dockExtraXMargin = if (dockCellWidth > cellHeight) {
            (dockCellWidth - cellHeight) / 2
        } else {
            0
        }
        for (i in 0 until dockColumnCount) {
            val rect = Rect(
                i * dockCellWidth + dockExtraXMargin,
                dockCellY,
                (i + 1) * dockCellWidth - dockExtraXMargin,
                dockCellY + cellHeight,
            )
            cells[Point(i, rowCount - 1)] = rect
            gridCenters.add(Point(rect.centerX(), rect.centerY()))
        }
    }

    fun fragmentExpanded() {
        widgetViews.forEach {
            it.ignoreTouches = true
        }
        closeFolder(true)
    }

    fun fragmentCollapsed() {
        widgetViews.forEach {
            it.ignoreTouches = false
        }
    }

    // get the clickable area around the icon, it includes text too
    fun getClickableRect(item: HomeScreenGridItem): Rect {
        if (cells.isEmpty()) {
            fillCellSizes()
        }

        val folder = currentlyOpenFolder
        val clickableLeft: Int
        val clickableTop: Int
        if (folder != null && item.parentId == folder.item.id) {
            val itemRect = folder.getItemRect(item)
            clickableLeft = itemRect.left
            clickableTop = itemRect.top - iconMargin
        } else {
            val cell = cells[item.getTopLeft(rowCount)] ?: return Rect(0, 0, 0, 0)
            clickableLeft = cell.left + (cell.width() - (iconSize + 2 * iconMargin)) / 2 + sideMargins.left
            clickableTop = if (item.docked) {
                dockCellY + cellHeight - iconSize - iconMargin
            } else {
                cell.top - iconMargin
            } + sideMargins.top
        }
        val additionalHeight = if (!item.docked && context.config.showHomeAppLabels && item.type != ITEM_TYPE_FOLDER) {
            // multiply line count by line height to get label height
            // we multiply all line heights by 2 so all widgets get the same clickable area and 2 is the max line count
            (2 * (textPaint.fontMetrics.bottom - textPaint.fontMetrics.top)).toInt()
        } else 0
        return Rect(
            clickableLeft,
            clickableTop,
            clickableLeft + iconSize + 2 * iconMargin,
            clickableTop + iconSize + 2 * iconMargin + additionalHeight
        )
    }

    // drag the center of the widget, not the top left corner
    private fun getWidgetOccupiedRect(item: Point): Rect {
        val left = item.x - floor((draggedItem!!.getWidthInCells() - 1) / 2.0).toInt()
        val rect = Rect(
            left,
            item.y,
            left + draggedItem!!.getWidthInCells() - 1,
            item.y + draggedItem!!.getHeightInCells() - 1
        )
        if (rect.left < 0) {
            rect.right -= rect.left
            rect.left = 0
        } else if (rect.right > columnCount - 1) {
            val diff = rect.right - columnCount + 1
            rect.right -= diff
            rect.left -= diff
        }

        if (rect.top < 0) {
            rect.bottom -= rect.top
            rect.top = 0
        } else if (rect.bottom > rowCount - 2) {
            val diff = rect.bottom - rowCount + 2
            rect.bottom -= diff
            rect.top -= diff
        }

        return rect
    }

    fun isClickingGridItem(x: Int, y: Int): HomeScreenGridItem? {
        if (pager.isAnimatingPageChange() || pager.isSwiped()) {
            return null
        }

        currentlyOpenFolder?.also { folder ->
            folder.getItems().forEach { gridItem ->
                val rect = getClickableRect(gridItem)
                if (x >= rect.left && x <= rect.right && y >= rect.top && y <= rect.bottom) {
                    return gridItem
                }
            }
        }

        // if a folder is open, we only want to allow clicks on items in the folder
        if (currentlyOpenFolder != null) {
            return null
        }

        for (gridItem in gridItems.filterVisibleOnCurrentPageOnly()) {
            if (gridItem.outOfBounds()) {
                continue
            }

            if (gridItem.isSingleCellType()) {
                val rect = getClickableRect(gridItem)
                if (x >= rect.left && x <= rect.right && y >= rect.top && y <= rect.bottom) {
                    return gridItem
                }
            } else if (gridItem.type == ITEM_TYPE_WIDGET) {
                val widgetPos = calculateWidgetPos(gridItem.getTopLeft(rowCount))
                val left = widgetPos.x.toFloat()
                val top = widgetPos.y.toFloat()
                val right = left + gridItem.getWidthInCells() * cellWidth
                val bottom = top + gridItem.getHeightInCells() * cellHeight

                if (x >= left && x <= right && y >= top && y <= bottom) {
                    return gridItem
                }
            }
        }

        return null
    }

    fun intoViewSpaceCoords(screenSpaceX: Float, screenSpaceY: Float): Pair<Float, Float> {
        val viewLocation = IntArray(2)
        getLocationOnScreen(viewLocation)
        val x = screenSpaceX - viewLocation[0]
        val y = screenSpaceY - viewLocation[1]
        return Pair(x, y)
    }

    private fun HomeScreenGridItem.outOfBounds(): Boolean {
        val maxCols = if (docked) dockColumnCount else columnCount
        return (left >= maxCols
                || right >= maxCols
                || (!docked && (top >= rowCount - 1 || bottom >= rowCount - 1))
                || (type == ITEM_TYPE_WIDGET && (bottom - top > rowCount - 1 || right - left > columnCount - 1))
                )
    }

    private inner class HomeScreenGridTouchHelper(host: View) : ExploreByTouchHelper(host) {
        override fun getVirtualViewAt(x: Float, y: Float): Int {
            val item = isClickingGridItem(x.toInt(), y.toInt())

            return if (item != null) {
                item.id?.toInt() ?: INVALID_ID
            } else {
                INVALID_ID
            }
        }

        override fun getVisibleVirtualViews(virtualViewIds: MutableList<Int>?) {
            val sorted =
                gridItems
                    .filter {
                        it.visibleOnCurrentPage()
                                || (
                                currentlyOpenFolder != null
                                        && it.parentId == currentlyOpenFolder?.item?.id
                                )
                    }
                    .sortedBy {
                        (if (it.parentId == null) {
                            it.getDockAdjustedTop(rowCount)
                        } else {
                            1
                        }) * 100 + it.left
                    }
            sorted.forEachIndexed { index, homeScreenGridItem ->
                virtualViewIds?.add(index, homeScreenGridItem.id?.toInt() ?: index)
            }
        }

        override fun onPopulateNodeForVirtualView(
            virtualViewId: Int,
            node: AccessibilityNodeInfoCompat,
        ) {
            val viewLocation = IntArray(2)
            getLocationOnScreen(viewLocation)

            // home screen
            if (virtualViewId == -1) {
                node.text = context.getString(R.string.app_name)
                val viewBounds = Rect(left, top, right, bottom)
                val onScreenBounds = Rect(viewBounds)
                onScreenBounds.offset(viewLocation[0], viewLocation[1])
                node.setBoundsInScreen(onScreenBounds)
                node.setBoundsInParent(viewBounds)

                node.addAction(AccessibilityNodeInfoCompat.ACTION_CLICK)
                node.addAction(AccessibilityNodeInfoCompat.ACTION_LONG_CLICK)
                node.setParent(this@HomeScreenGrid)
            }

            val item = gridItems.firstOrNull { it.id?.toInt() == virtualViewId }
                ?: throw IllegalArgumentException("Unknown id")

            node.text = if (item.type == ITEM_TYPE_WIDGET) {
                item.providerInfo?.loadLabel(context.packageManager) ?: item.title
            } else {
                item.title
            }

            val viewBounds = if (item == currentlyOpenFolder?.item) {
                currentlyOpenFolder?.getDrawingRect()?.toRect()
            } else if (item.type == ITEM_TYPE_WIDGET) {
                val widgetPos = calculateWidgetPos(item.getTopLeft(rowCount))
                val left = widgetPos.x
                val top = widgetPos.y
                val right = left + item.getWidthInCells() * cellWidth
                val bottom = top + item.getHeightInCells() * cellHeight

                Rect(left, top, right, bottom)
            } else {
                getClickableRect(item)
            }
            val onScreenBounds = Rect(viewBounds)
            onScreenBounds.offset(viewLocation[0], viewLocation[1])
            node.setBoundsInScreen(onScreenBounds)
            node.setBoundsInParent(viewBounds)

            node.addAction(AccessibilityNodeInfoCompat.ACTION_CLICK)
            node.addAction(AccessibilityNodeInfoCompat.ACTION_LONG_CLICK)
            node.setParent(this@HomeScreenGrid)
        }

        override fun onPerformActionForVirtualView(
            virtualViewId: Int,
            action: Int,
            arguments: Bundle?,
        ): Boolean {
            val item = gridItems.firstOrNull { it.id?.toInt() == virtualViewId }
                ?: throw IllegalArgumentException("Unknown id")
            when (action) {
                AccessibilityNodeInfoCompat.ACTION_CLICK -> itemClickListener?.apply {
                    if (item == currentlyOpenFolder?.item) {
                        closeFolder(true)
                    } else {
                        invoke(item)
                    }
                    return true
                }

                AccessibilityNodeInfoCompat.ACTION_LONG_CLICK -> itemLongClickListener?.apply {
                    invoke(item)
                    return true
                }
            }

            return false
        }
    }

    private fun deletePage(page: Int) {
        gridItems.filter { it.page > page }.forEach {
            it.page -= 1
        }
        context.homeScreenGridItemsDB.shiftPage(page, -1)

        if (pager.isOutsideOfPageRange()) {
            post {
                prevPage()
            }
        }
    }

    private var targetMaxPage = 0

    private fun getMaxPage() =
        max(targetMaxPage, gridItems.filter { !it.docked && !it.outOfBounds() }.maxOfOrNull { it.page } ?: 0)

    fun getCurrentPage(): Int = pager.getCurrentPage()

    fun getTargetCell(x: Float, y: Float): Point? {
        val adjustedX = x - sideMargins.left
        val adjustedY = y - sideMargins.top
        val tappedCell = cells.entries.firstOrNull { (_, rect) ->
            rect.contains(adjustedX.toInt(), adjustedY.toInt())
        }?.key

        if (tappedCell != null) {
            val isDock = tappedCell.y == rowCount - 1
            val isOccupied = if (isDock) {
                gridItems.any { it.docked && it.left == tappedCell.x }
            } else {
                gridItems.filterVisibleOnCurrentPageOnly().any { item ->
                    tappedCell.x in item.left..item.right && tappedCell.y in item.top..item.bottom
                }
            }
            if (!isOccupied) {
                return tappedCell
            }
        }
        return null
    }

    /**
     * Returns the closest available desktop cell to the supplied long-press position.
     * Unlike getTargetCell, this also works when the original cell is occupied.
     */
    fun findNearestEmptyCell(x: Float, y: Float): Point? {
        val adjustedX = x - sideMargins.left
        val adjustedY = y - sideMargins.top
        val target = cells.entries.firstOrNull { (_, rect) ->
            rect.contains(adjustedX.toInt(), adjustedY.toInt())
        }?.key ?: return findFirstEmptyCellOnCurrentPage()

        val occupied = HashSet<Point>()
        gridItems.filterVisibleOnCurrentPageOnly()
            .filter { it.parentId == null && !it.docked }
            .forEach { item ->
                for (col in item.left..item.right) {
                    for (row in item.top..item.bottom) {
                        occupied.add(Point(col, row))
                    }
                }
            }

        return (0 until (rowCount - 1))
            .flatMap { row -> (0 until columnCount).map { col -> Point(col, row) } }
            .filterNot { occupied.contains(it) }
            .minWithOrNull(
                compareBy<Point>(
                    { abs(it.x - target.x) + abs(it.y - target.y) },
                    { it.y },
                    { it.x }
                )
            )
    }

    /** Refreshes only Sidebar item icons/state once; it never registers a watcher. */
    fun refreshSidebarElementIcons() {
        ensureBackgroundThread {
            val sidebarItems = gridItems.filter {
                it.className == SIDEBAR_ELEMENT_CLASS_NAME && it.shortcutId.isNotBlank()
            }
            if (sidebarItems.isEmpty()) return@ensureBackgroundThread

            val infos = shura.home.helpers.SidebarHomeElementClient.load(
                context,
                sidebarItems.map { it.shortcutId }
            )
            sidebarItems.forEach { item ->
                val info = infos[item.shortcutId]
                if (info != null) {
                    item.icon = info.icon
                    item.drawable = shura.home.helpers.SidebarHomeElementIconDecorator.createDrawable(
                        context,
                        info.icon,
                        info.isActive,
                        info.glowColor
                    ) ?: context.getDrawable(android.R.drawable.ic_menu_view)
                } else {
                    item.drawable = context.getDrawable(android.R.drawable.ic_menu_view)
                }
            }
            post { redrawGrid() }
        }
    }

    fun findFirstEmptyCellOnCurrentPage(): Point? {
        val occupied = HashSet<Point>()
        gridItems.filterVisibleOnCurrentPageOnly().filter { it.parentId == null }.forEach { item ->
            for (col in item.left..item.right) {
                for (row in item.top..item.bottom) {
                    occupied.add(Point(col, row))
                }
            }
        }

        for (row in 0 until (rowCount - 1)) {
            for (col in 0 until columnCount) {
                val pt = Point(col, row)
                if (!occupied.contains(pt)) {
                    return pt
                }
            }
        }
        return null
    }

    fun addNewPage(): Int {
        val newPage = getMaxPage() + 1
        targetMaxPage = newPage
        pager.skipToPage(newPage)
        redrawGrid()
        return newPage
    }

    fun nextPage(redraw: Boolean = false): Boolean {
        return pager.nextPage(redraw)
    }

    fun prevPage(redraw: Boolean = false): Boolean {
        return pager.prevPage(redraw)
    }

    fun skipToPage(targetPage: Int): Boolean {
        return pager.skipToPage(targetPage)
    }

    fun getCurrentIconSize(): Int = iconSize

    fun isDraggingItem(): Boolean = draggedItem != null


    fun setSwipeMovement(diffX: Float) {
        if (draggedItem == null) {
            pager.setSwipeMovement(diffX)
        }
    }

    fun finalizeSwipe() {
        pager.finalizeSwipe()
    }

    fun openFolder(folder: HomeScreenGridItem) {
        if (currentlyOpenFolder == null) {
            currentlyOpenFolder = folder.toFolder(animateOpening = true)
            redrawGrid()
        } else if (currentlyOpenFolder?.item?.id != folder.id) {
            closeFolder()
        }
        accessibilityHelper.invalidateRoot()
    }

    fun closeFolder(redraw: Boolean = false) {
        invalidateFolderDrawables()
        currentlyOpenFolder?.animateClosing {
            currentlyOpenFolder = null
            if (redraw) {
                redrawGrid()
            }
            accessibilityHelper.invalidateRoot()
        }
    }

    fun getCurrentlyOpenFolderItem(): HomeScreenGridItem? = currentlyOpenFolder?.item

    fun isClickingFolderAddButton(x: Float, y: Float): Boolean {
        return currentlyOpenFolder?.isAddButtonClicked(x, y) == true
    }

    fun isClickingFolderHeader(x: Float, y: Float): Boolean {
        return currentlyOpenFolder?.getHeaderRect()?.contains(x, y) == true
    }

    fun addAppToFolder(folderItem: HomeScreenGridItem, app: shura.home.models.AppLauncher) {
        val folderItems = folderItem.toFolder().getItems()
        if (folderItems.count() >= HomeScreenGridItem.FOLDER_MAX_CAPACITY) {
            performHapticFeedback()
            return
        }

        val nextPosition = folderItems.count()
        val gridItem = HomeScreenGridItem(
            id = null,
            left = nextPosition,
            top = 0,
            right = nextPosition,
            bottom = 0,
            page = folderItem.page,
            packageName = app.packageName,
            activityName = app.activityName,
            title = app.title,
            type = ITEM_TYPE_ICON,
            className = "",
            widgetId = -1,
            shortcutId = "",
            icon = app.drawable?.toBitmap(),
            docked = false,
            parentId = folderItem.id,
            drawable = app.drawable
        )

        ensureBackgroundThread {
            val newId = context.homeScreenGridItemsDB.insert(gridItem)
            gridItem.id = newId
            post {
                gridItems.add(gridItem)
                redrawGrid()
            }
        }
    }

    override fun onInterceptTouchEvent(ev: MotionEvent?): Boolean {
        return if (currentlyOpenFolder != null) {
            true
        } else {
            super.onInterceptTouchEvent(ev)
        }
    }

    fun invalidateFolderDrawables(folderId: Long? = null) {
        if (folderId != null) {
            gridItems.firstOrNull { it.id == folderId && it.type == ITEM_TYPE_FOLDER }?.drawable = null
        } else {
            gridItems.filter { it.type == ITEM_TYPE_FOLDER }.forEach { it.drawable = null }
        }
    }

    private fun Canvas.drawItemInCell(item: HomeScreenGridItem, cell: Rect) {
        if (item.id != draggedItem?.id) {
            val drawableX = cell.left + (cell.width() - iconSize) / 2

            val drawable = if (item.type == ITEM_TYPE_FOLDER) {
                if (item.icon != null) {
                    item.icon?.toDrawable(context.resources)
                } else {
                    item.drawable ?: item.toFolder().generateDrawable().also { item.drawable = it }
                }
            } else {
                if (item.icon != null) {
                    item.icon?.toDrawable(context.resources)
                } else {
                    item.drawable?.constantState?.newDrawable()?.mutate()
                }
            }

            val drawableY = if (item.docked) {
                dockCellY + cellHeight - iconMargin - iconSize + sideMargins.top
            } else if (item.parentId != null) {
                cell.top + (cell.height() - iconSize) / 2
            } else {
                cell.top + iconMargin
            }

            drawable?.setBounds(
                drawableX,
                drawableY,
                drawableX + iconSize,
                drawableY + iconSize
            )

            if (!item.docked && item.parentId == null && item.id != draggedItem?.id && item.title.isNotEmpty() && context.config.showHomeAppLabels && item.type != ITEM_TYPE_FOLDER) {
                val textX = cell.left.toFloat() + labelSideMargin
                val textY = cell.top.toFloat() + iconSize + iconMargin + labelSideMargin
                val textPaintToUse = textPaint
                val staticLayout = StaticLayout.Builder
                    .obtain(
                        item.title,
                        0,
                        item.title.length,
                        textPaintToUse,
                        cell.width() - 2 * labelSideMargin
                    )
                    .setMaxLines(2)
                    .setEllipsize(TextUtils.TruncateAt.END)
                    .setAlignment(Layout.Alignment.ALIGN_CENTER)
                    .build()

                withTranslation(textX, textY) {
                    staticLayout.draw(this)
                }
            }

            drawable?.draw(this)

            // Draw Notification Badge or New App Dot (Home Screen icons only)
            if (item.type == ITEM_TYPE_ICON && item.packageName.isNotEmpty()) {
                val density = context.resources.displayMetrics.density
                val count = if (context.config.showNotificationBadges) {
                    notificationBadges[item.packageName] ?: 0
                } else 0

                val iconRight = (drawableX + iconSize).toFloat()
                val iconTop = drawableY.toFloat()

                if (count > 0) {
                    val countText = if (count > 99) "99+" else count.toString()
                    val badgeHeight = 16f * density
                    val badgeRadius = badgeHeight / 2f
                    val textWidth = badgeTextPaint.measureText(countText)
                    val badgeWidth = maxOf(badgeHeight, textWidth + 8f * density)

                    // Position badge anchored at top-right edge of icon
                    val badgeCenterX = iconRight - (badgeRadius * 0.6f)
                    val badgeCenterY = iconTop + (badgeRadius * 0.6f)

                    val badgeRect = RectF(
                        badgeCenterX - badgeWidth / 2f,
                        badgeCenterY - badgeHeight / 2f,
                        badgeCenterX + badgeWidth / 2f,
                        badgeCenterY + badgeHeight / 2f
                    )

                    drawRoundRect(badgeRect, badgeRadius, badgeRadius, badgeBackgroundPaint)

                    val fontMetrics = badgeTextPaint.fontMetrics
                    val textBaselineY = badgeCenterY - (fontMetrics.ascent + fontMetrics.descent) / 2f
                    drawText(countText, badgeCenterX, textBaselineY, badgeTextPaint)
                } else if (context.config.newInstalledPackages.contains(item.packageName)) {
                    val dotRadius = 4f * density
                    val dotCenterX = iconRight - (dotRadius * 0.9f)
                    val dotCenterY = iconTop + (dotRadius * 0.9f)

                    drawCircle(dotCenterX, dotCenterY, dotRadius, newAppDotPaint)
                    drawCircle(dotCenterX, dotCenterY, dotRadius, newAppDotStrokePaint)
                }
            }
        }
    }

    private fun Rect.withOffset(offsetX: Int, offsetY: Int, block: Rect.() -> Unit) {
        offset(offsetX, offsetY)
        try {
            block()
        } finally {
            offset(-offsetX, -offsetY)
        }
    }

    private fun ArrayList<HomeScreenGridItem>.filterVisibleOnCurrentPageOnly() =
        filter { it.visibleOnCurrentPage() }

    private fun HomeScreenGridItem.visibleOnCurrentPage() =
        (pager.isItemOnCurrentPage(this) || docked) && parentId == null

    private fun HomeScreenGridItem.isSingleCellType() =
        (type == ITEM_TYPE_ICON || type == ITEM_TYPE_SHORTCUT || type == ITEM_TYPE_FOLDER)

    private fun createSquirclePath(left: Float, top: Float, right: Float, bottom: Float): Path {
        val path = Path()
        val width = right - left
        val height = bottom - top
        val c = 0.10f

        path.moveTo(left + width * 0.5f, top)
        path.cubicTo(
            left + width * c, top,
            left, top + height * c,
            left, top + height * 0.5f
        )
        path.cubicTo(
            left, bottom - height * c,
            left + width * c, bottom,
            left + width * 0.5f, bottom
        )
        path.cubicTo(
            right - width * c, bottom,
            right, bottom - height * c,
            right, top + height * 0.5f
        )
        path.cubicTo(
            right, top + height * c,
            right - width * c, top,
            left + width * 0.5f, top
        )
        path.close()
        return path
    }

    private fun HomeScreenGridItem.toFolder(animateOpening: Boolean = false) =
        HomeScreenFolder(this, animateOpening)

    private inner class HomeScreenFolder(
        val item: HomeScreenGridItem,
        animateOpening: Boolean,
    ) {
        var scale: Float = 1f
        private var closing = false

        init {
            if (animateOpening) {
                scale = 0f
                post {
                    ValueAnimator.ofFloat(0f, 1f)
                        .apply {
                            interpolator = DecelerateInterpolator()
                            addUpdateListener {
                                scale = it.animatedValue as Float
                                redrawGrid()
                            }
                            addListener(object : AnimatorListenerAdapter() {
                                override fun onAnimationEnd(animation: Animator) {
                                    super.onAnimationEnd(animation)
                                    scale = 1f
                                    redrawGrid()
                                }
                            })
                            duration = FOLDER_ANIMATION_DURATION
                            start()
                        }
                }
            }
        }

        fun getItems(): List<HomeScreenGridItem> {
            return gridItems
                .toList()
                .filter { it.isSingleCellType() && it.parentId == item.id }
        }

        val headerHeight = max(folderTitleTextPaint.textSize, 36f * resources.displayMetrics.density)

        fun generateDrawable(): Drawable? {
            if (iconSize == 0) {
                return null
            }

            val items = getItems()
            val itemsCount = items.count()

            val bitmap = createBitmap(iconSize, iconSize)
            val canvas = Canvas(bitmap)

            val inset = 3.5f * resources.displayMetrics.density
            val squirclePath = createSquirclePath(inset, inset, iconSize.toFloat() - inset, iconSize.toFloat() - inset)

            // Outer soft drop shadow around squircle border
            val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#33000000")
                style = Paint.Style.STROKE
                strokeWidth = 1.2f * resources.displayMetrics.density
                maskFilter = android.graphics.BlurMaskFilter(1.5f * resources.displayMetrics.density, android.graphics.BlurMaskFilter.Blur.NORMAL)
            }
            canvas.drawPath(squirclePath, shadowPaint)

            // Translucent white squircle bubble background
            val translucentBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = ColorUtils.setAlphaComponent(Color.WHITE, 125)
                style = Paint.Style.FILL
            }
            canvas.drawPath(squirclePath, translucentBgPaint)

            // Subtle white bubble border outline
            val bubbleBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = ColorUtils.setAlphaComponent(Color.WHITE, 80)
                style = Paint.Style.STROKE
                strokeWidth = 0.8f * resources.displayMetrics.density
            }
            canvas.drawPath(squirclePath, bubbleBorderPaint)

            canvas.clipPath(squirclePath)

            if (itemsCount == 0) {
                return bitmap.toDrawable(resources)
            }

            val innerPadding = iconSize * 0.14f
            val contentSize = iconSize - 2 * innerPadding
            val folderColumnCount = ceil(sqrt(itemsCount.toDouble())).roundToInt()
            val folderRowCount = ceil(itemsCount.toFloat() / folderColumnCount).roundToInt()
            val scaledCellSize = contentSize / folderColumnCount
            val scaledGap = scaledCellSize / 5f
            val scaledIconSize = (contentSize - (folderColumnCount - 1) * scaledGap) / folderColumnCount
            val extraYMargin = if (folderRowCount < folderColumnCount) (scaledIconSize + scaledGap) / 2 else 0f

            items.forEach {
                val (row, column) = getItemPosition(it)
                val drawableX = (innerPadding + column * scaledIconSize + column * scaledGap).toInt()
                val drawableY = (innerPadding + extraYMargin + row * scaledIconSize + row * scaledGap).toInt()
                val newDrawable = it.drawable?.constantState?.newDrawable()?.mutate()
                newDrawable?.setBounds(
                    drawableX,
                    drawableY,
                    drawableX + scaledIconSize.toInt(),
                    drawableY + scaledIconSize.toInt()
                )
                newDrawable?.draw(canvas)
            }
            return bitmap.toDrawable(resources)
        }

        fun getDrawingRect(): RectF {
            val count = max(1, getItems().count())
            val columnsCount = ceil(sqrt(count.toDouble())).toInt()
            val rowsCount = ceil(count.toFloat() / columnsCount).toInt()
            val cellSize = getCellSize()
            val gap = getGapSize()
            val cell = cells[item.getTopLeft(rowCount)] ?: return RectF(0f, 0f, 0f, 0f)
            val folderDialogWidth =
                columnsCount * cellSize + 2 * folderPadding + (columnsCount - 1) * gap
            val folderDialogHeight =
                rowsCount * cellSize + 3 * folderPadding + headerHeight + (rowsCount - 1) * gap

            val cellLeftWithMargin = sideMargins.left + cell.left.toFloat()
            val cellRightWithMargin = sideMargins.left + cell.right.toFloat()
            val cellTopWithMargin = sideMargins.top + cell.top.toFloat()
            val cellBottomWithMargin = sideMargins.top + cell.bottom.toFloat()

            val gridMiddleY = (top + sideMargins.top + bottom - sideMargins.bottom) / 2f
            val gridMiddleX = (left + sideMargins.left + right - sideMargins.right) / 2f

            // Positioning: The corner of the popup rests directly on top of the tapped folder
            // (folder is hidden underneath that corner of the popup).
            var folderDialogTop = if (cellTopWithMargin >= gridMiddleY) {
                // Bottom half: bottom of popup covers folder cell bottom (popup corner sits over folder)
                cellBottomWithMargin - folderDialogHeight
            } else {
                // Top half: top of popup covers folder cell top (popup corner sits over folder)
                cellTopWithMargin
            }

            var folderDialogLeft = if (cellLeftWithMargin <= gridMiddleX) {
                // Left half: left of popup covers folder cell left
                cellLeftWithMargin
            } else {
                // Right half: right of popup covers folder cell right
                cellRightWithMargin - folderDialogWidth
            }

            // Safety boundary clamp to keep popup within screen margins
            if (folderDialogLeft < left + sideMargins.left) {
                folderDialogLeft = (left + sideMargins.left).toFloat()
            }
            if (folderDialogLeft + folderDialogWidth > right - sideMargins.right) {
                folderDialogLeft = (right - sideMargins.right - folderDialogWidth).toFloat()
            }
            if (folderDialogTop < top + sideMargins.top) {
                folderDialogTop = (top + sideMargins.top).toFloat()
            }
            if (folderDialogTop + folderDialogHeight > bottom - sideMargins.bottom) {
                folderDialogTop = (bottom - sideMargins.bottom - folderDialogHeight).toFloat()
            }

            return RectF(
                folderDialogLeft,
                folderDialogTop,
                folderDialogLeft + folderDialogWidth,
                folderDialogTop + folderDialogHeight
            )
        }

        fun getHeaderRect(): RectF {
            val folderRect = getDrawingRect()
            return RectF(
                folderRect.left + folderPadding,
                folderRect.top + folderPadding,
                folderRect.right - folderPadding,
                folderRect.top + folderPadding + headerHeight
            )
        }

        fun getFolderAddButtonRect(): RectF {
            val headerRect = getHeaderRect()
            val btnSize = 36f * resources.displayMetrics.density
            return RectF(
                headerRect.right - btnSize,
                headerRect.centerY() - btnSize / 2f,
                headerRect.right,
                headerRect.centerY() + btnSize / 2f
            )
        }

        fun isAddButtonClicked(x: Float, y: Float): Boolean {
            val btnRect = getFolderAddButtonRect()
            val touchSlop = 8f * resources.displayMetrics.density
            return RectF(
                btnRect.left - touchSlop,
                btnRect.top - touchSlop,
                btnRect.right + touchSlop,
                btnRect.bottom + touchSlop
            ).contains(x, y)
        }

        fun getItemsDrawingRect(): RectF {
            val folderRect = getDrawingRect()
            return RectF(
                folderRect.left + folderPadding,
                folderRect.top + folderPadding * 2 + headerHeight,
                folderRect.right - folderPadding,
                folderRect.bottom - folderPadding
            )
        }

        fun getItemsGridCenters(): List<Triple<Int, Int, Int>> {
            val count = max(1, getItems().count())
            val columnsCount = ceil(sqrt(count.toDouble())).roundToInt()
            val rowsCount = ceil(count.toFloat() / columnsCount).roundToInt()
            val folderItemsRect = getItemsDrawingRect()
            val cellSize = getCellSize()
            val gap = getGapSize()
            return (0 until columnsCount * rowsCount)
                .toList()
                .map { Pair(it % columnsCount, it / columnsCount) }
                .mapIndexed { index, (x, y) ->
                    Triple(
                        index,
                        (folderItemsRect.left + x * cellSize + x * gap + cellSize / 2).toInt(),
                        (folderItemsRect.top + y * cellSize + y * gap + cellSize / 2).toInt()
                    )
                }
        }

        private fun getItemPosition(item: HomeScreenGridItem): Pair<Int, Int> {
            val count = getItems().count()
            val columnsCount = ceil(sqrt(count.toDouble())).roundToInt()
            val column = item.left % columnsCount
            val row = item.left / columnsCount
            return Pair(row, column)
        }

        fun getItemRect(item: HomeScreenGridItem): Rect {
            val (row, column) = getItemPosition(item)
            val itemsRect = getItemsDrawingRect()
            val cellSize = getCellSize()
            val gapSize = getGapSize()
            val left = (itemsRect.left + column * cellSize + column * gapSize).roundToInt()
            val top = (itemsRect.top + row * cellSize + row * gapSize).roundToInt()
            return Rect(
                left,
                top,
                left + cellSize,
                top + cellSize
            )
        }

        fun animateClosing(callback: () -> Unit) {
            post {
                if (closing) {
                    return@post
                }
                closing = true
                ValueAnimator.ofFloat(scale, 0.2f)
                    .apply {
                        interpolator = DecelerateInterpolator()
                        addUpdateListener {
                            scale = it.animatedValue as Float
                            redrawGrid()
                        }
                        addListener(object : AnimatorListenerAdapter() {
                            override fun onAnimationEnd(animation: Animator) {
                                super.onAnimationEnd(animation)
                                scale = 0.2f
                                callback()
                            }
                        })
                        duration = (FOLDER_ANIMATION_DURATION * (max(0f, scale - 0.2f))).toLong()
                        start()
                    }
            }
        }

        fun getCellSize(): Int = min(cellWidth, cellHeight)

        private fun getGapSize(): Float {
            val cellSize = getCellSize()
            return if (cellSize == cellWidth) {
                0f
            } else {
                cellSize / 5f
            }
        }
    }

    companion object {
        private const val FOLDER_OPEN_HOLD_THRESHOLD = 500L
        private const val FOLDER_CLOSE_HOLD_THRESHOLD = 300L
        private const val FOLDER_ANIMATION_DURATION = 200L
    }
}

/**
 * Helper class responsible for managing current page and providing utilities for animating page changes,
 * as well as partial dragigng between pages
 */
private class AnimatedGridPager(
    private val getMaxPage: () -> Int,
    private val redrawGrid: () -> Unit,
    private val getWidth: () -> Int,
    private val getHandler: () -> Handler,
    private val getNextPageBound: () -> Int,
    private val getPrevPageBound: () -> Int,
    private val pageChangeStarted: () -> Unit,
) {

    companion object {
        private const val PAGE_CHANGE_HOLD_THRESHOLD = 500L
        private const val PAGE_INDICATORS_FADE_DELAY = PAGE_CHANGE_HOLD_THRESHOLD + 300L

        private enum class PageChangeArea {
            LEFT,
            MIDDLE,
            RIGHT
        }
    }

    private var lastPage = 0
    private var currentPage = 0
    private var pageChangeLastArea = PageChangeArea.MIDDLE
    private var pageChangeLastAreaEntryTime = 0L
    private var pageChangeAnimLeftPercentage = 0f
    private var pageChangeEnabled = true
    private var pageChangeIndicatorsAlpha = 0f
    private var pageChangeSwipedPercentage = 0f

    fun getCurrentPage() = currentPage

    fun isItemOnCurrentPage(item: HomeScreenGridItem) = item.page == currentPage

    fun isItemOnLastPage(item: HomeScreenGridItem) = item.page == lastPage

    fun getPageCount() = max(getMaxPage(), currentPage) + 1


    fun isOutsideOfPageRange() = currentPage > getMaxPage()

    fun isItemInSwipeRange(item: HomeScreenGridItem) =
        ((pageChangeSwipedPercentage > 0f && item.page == currentPage - 1) || (pageChangeSwipedPercentage < 0f && item.page == currentPage + 1))

    fun isSwiped() = abs(pageChangeSwipedPercentage) > 0f

    fun isAnimatingPageChange() = pageChangeAnimLeftPercentage != 0f

    fun shouldDisplayPageChangeIndicator() =
        isSwiped() || isAnimatingPageChange() || pageChangeIndicatorsAlpha != 0f

    fun getPageChangeIndicatorsAlpha() = if (pageChangeIndicatorsAlpha != 0f) {
        (pageChangeIndicatorsAlpha * 255.0f).toInt()
    } else {
        255
    }

    fun getXFactorForCurrentPage(): Float {
        return if (abs(pageChangeSwipedPercentage) > 0f) {
            pageChangeSwipedPercentage
        } else {
            if (currentPage > lastPage) {
                pageChangeAnimLeftPercentage
            } else {
                -pageChangeAnimLeftPercentage
            }
        }
    }

    fun getXFactorForLastPage(): Float {
        return if (abs(pageChangeSwipedPercentage) > 0f) {
            (1 - abs(pageChangeSwipedPercentage)) * -sign(pageChangeSwipedPercentage)
        } else {
            if (currentPage > lastPage) {
                pageChangeAnimLeftPercentage - 1
            } else {
                1 - pageChangeAnimLeftPercentage
            }
        }
    }

    fun getCurrentViewPositionInFullPageSpace(): Float {
        val rangeStart = lastPage.toFloat()
        val rangeEndPage = if (abs(pageChangeSwipedPercentage) > 0f) {
            if (pageChangeSwipedPercentage > 0f) {
                currentPage - 1
            } else {
                currentPage + 1
            }
        } else {
            currentPage
        }
        val rangeEnd = rangeEndPage.toFloat()
        val lerpAmount = if (pageChangeAnimLeftPercentage != 0f) {
            1 - pageChangeAnimLeftPercentage
        } else {
            abs(pageChangeSwipedPercentage)
        }
        return MathUtils.lerp(rangeStart, rangeEnd, lerpAmount)
    }

    fun setSwipeMovement(diffX: Float) {
        if (!pageChangeEnabled) {
            return
        }

        if (currentPage < getMaxPage() && diffX > 0f || currentPage > 0 && diffX < 0f) {
            pageChangeSwipedPercentage = (-diffX / getWidth().toFloat()).coerceIn(-1f, 1f)
            pageChangeStarted()
            redrawGrid()
        }
    }

    fun finalizeSwipe() {
        if (abs(pageChangeSwipedPercentage) == 0f) {
            return
        }

        if (abs(pageChangeSwipedPercentage) > 0.5f) {
            lastPage = currentPage
            currentPage = if (pageChangeSwipedPercentage > 0f) {
                currentPage - 1
            } else {
                currentPage + 1
            }
            handlePageChange(true)
        } else {
            lastPage = if (pageChangeSwipedPercentage > 0f) {
                currentPage - 1
            } else {
                currentPage + 1
            }
            pageChangeSwipedPercentage =
                sign(pageChangeSwipedPercentage) * (1 - abs(pageChangeSwipedPercentage))
            handlePageChange(true)
        }
    }

    fun handleItemMovement(x: Int, y: Int) {
        showIndicators()
        if (x > getNextPageBound()) {
            doWithPageChangeDelay(PageChangeArea.RIGHT) {
                nextOrAdditionalPage()
            }
        } else if (x < getPrevPageBound()) {
            doWithPageChangeDelay(PageChangeArea.LEFT) {
                prevPage()
            }
        } else {
            clearPageChangeFlags()
        }
    }

    fun itemMovementStopped() {
        scheduleIndicatorsFade()
    }

    fun nextPage(redraw: Boolean = false): Boolean {
        if (currentPage < getMaxPage() && pageChangeEnabled) {
            lastPage = currentPage
            currentPage++
            handlePageChange(redraw)
            return true
        }

        return false
    }

    fun prevPage(redraw: Boolean = false): Boolean {
        if (currentPage > 0 && pageChangeEnabled) {
            lastPage = currentPage
            currentPage--
            handlePageChange(redraw)
            return true
        }

        return false
    }

    fun skipToPage(targetPage: Int): Boolean {
        if (currentPage != targetPage && targetPage <= getMaxPage() + 1) {
            lastPage = currentPage
            currentPage = targetPage
            handlePageChange()
            return true
        }

        return false
    }

    private val checkAndExecuteDelayedPageChange: Runnable = Runnable {
        if (System.currentTimeMillis() - pageChangeLastAreaEntryTime > PAGE_CHANGE_HOLD_THRESHOLD) {
            when (pageChangeLastArea) {
                PageChangeArea.RIGHT -> nextOrAdditionalPage(true)
                PageChangeArea.LEFT -> prevPage(true)
                else -> clearPageChangeFlags()
            }
        }
    }

    private val startFadingIndicators: Runnable = Runnable {
        ValueAnimator.ofFloat(1f, 0f)
            .apply {
                addUpdateListener {
                    pageChangeIndicatorsAlpha = it.animatedValue as Float
                    redrawGrid()
                }
                start()
            }
    }

    private fun showIndicators() {
        pageChangeIndicatorsAlpha = 1f
        getHandler().removeCallbacks(startFadingIndicators)
    }

    private fun clearPageChangeFlags() {
        pageChangeLastArea = PageChangeArea.MIDDLE
        pageChangeLastAreaEntryTime = 0
        getHandler().removeCallbacks(checkAndExecuteDelayedPageChange)
    }

    private fun schedulePageChange() {
        pageChangeLastAreaEntryTime = System.currentTimeMillis()
        getHandler().postDelayed(checkAndExecuteDelayedPageChange, PAGE_CHANGE_HOLD_THRESHOLD)
    }

    private fun scheduleIndicatorsFade() {
        pageChangeIndicatorsAlpha = 1f
        getHandler().postDelayed(startFadingIndicators, PAGE_INDICATORS_FADE_DELAY)
    }

    private fun doWithPageChangeDelay(needed: PageChangeArea, pageChangeFunction: () -> Boolean) {
        if (pageChangeLastArea != needed) {
            pageChangeLastArea = needed
            schedulePageChange()
        } else if (System.currentTimeMillis() - pageChangeLastAreaEntryTime > PAGE_CHANGE_HOLD_THRESHOLD) {
            if (pageChangeFunction()) {
                clearPageChangeFlags()
            }
        }
    }

    private fun nextOrAdditionalPage(redraw: Boolean = false): Boolean {
        if (currentPage < getMaxPage() + 1 && pageChangeEnabled) {
            lastPage = currentPage
            currentPage++
            handlePageChange(redraw)
            return true
        }

        return false
    }

    private fun handlePageChange(redraw: Boolean = false) {
        pageChangeEnabled = false
        pageChangeIndicatorsAlpha = 0f
        pageChangeStarted()
        val startingAt = 1 - abs(pageChangeSwipedPercentage)
        pageChangeSwipedPercentage = 0f
        getHandler().removeCallbacks(startFadingIndicators)
        pageChangeStarted()
        if (redraw) {
            redrawGrid()
        }
        ValueAnimator.ofFloat(startingAt, 0f)
            .apply {
                interpolator = OvershootInterpolator(1f)
                addUpdateListener {
                    if (it.animatedValue != 0f) {
                        pageChangeAnimLeftPercentage = it.animatedValue as Float
                        redrawGrid()
                    }
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        super.onAnimationEnd(animation)
                        pageChangeAnimLeftPercentage = 0f
                        pageChangeEnabled = true
                        lastPage = currentPage
                        clearPageChangeFlags()
                        scheduleIndicatorsFade()
                        redrawGrid()
                    }
                })
                start()
            }
    }
}
