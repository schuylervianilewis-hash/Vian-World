package shura.home.dialogs

import android.app.Activity
import androidx.appcompat.app.AlertDialog
import org.fossify.commons.extensions.getAlertDialogBuilder
import org.fossify.commons.extensions.setupDialogStuff
import org.fossify.commons.helpers.ensureBackgroundThread
import shura.home.R
import shura.home.databinding.DialogItemGesturesBinding
import shura.home.extensions.getDrawableForPackageName
import shura.home.helpers.ITEM_TYPE_FOLDER
import shura.home.helpers.ItemGestureConfig
import shura.home.helpers.ItemGestureManager
import shura.home.helpers.LauncherActionHandler
import shura.home.models.HomeScreenGridItem

class ItemGesturesDialog(
    private val activity: Activity,
    private val item: HomeScreenGridItem,
    private val callback: () -> Unit
) {
    init {
        val binding = DialogItemGesturesBinding.inflate(activity.layoutInflater)
        val isFolder = item.type == ITEM_TYPE_FOLDER

        // Set header preview
        binding.itemGestureHeaderTitle.text = item.title.ifEmpty {
            activity.getString(R.string.app_launcher_name)
        }

        if (item.icon != null) {
            binding.itemGestureHeaderIcon.setImageBitmap(item.icon)
        } else if (item.drawable != null) {
            binding.itemGestureHeaderIcon.setImageDrawable(item.drawable)
        } else if (item.packageName.isNotEmpty()) {
            binding.itemGestureHeaderIcon.setImageDrawable(activity.getDrawableForPackageName(item.packageName))
        } else {
            binding.itemGestureHeaderIcon.setImageResource(R.mipmap.ic_launcher)
        }

        val gestureConfig = if (item.id != null) {
            ItemGestureManager.getOrCreateConfig(activity, item.id!!)
        } else {
            ItemGestureConfig()
        }

        fun refreshGestureBadges() {
            binding.gestureSwipeUpBadge.text = LauncherActionHandler.getActionLabel(
                activity,
                gestureConfig.swipeUpAction,
                gestureConfig.getTargetPackage(ItemGestureManager.GESTURE_SWIPE_UP)
            )
            binding.gestureSwipeDownBadge.text = LauncherActionHandler.getActionLabel(
                activity,
                gestureConfig.swipeDownAction,
                gestureConfig.getTargetPackage(ItemGestureManager.GESTURE_SWIPE_DOWN)
            )
            binding.gestureSwipeLeftBadge.text = LauncherActionHandler.getActionLabel(
                activity,
                gestureConfig.swipeLeftAction,
                gestureConfig.getTargetPackage(ItemGestureManager.GESTURE_SWIPE_LEFT)
            )
            binding.gestureSwipeRightBadge.text = LauncherActionHandler.getActionLabel(
                activity,
                gestureConfig.swipeRightAction,
                gestureConfig.getTargetPackage(ItemGestureManager.GESTURE_SWIPE_RIGHT)
            )
        }

        refreshGestureBadges()

        binding.gestureSwipeUpHolder.setOnClickListener {
            SelectActionDialog(
                activity = activity,
                gestureLabel = activity.getString(R.string.gesture_swipe_up),
                currentAction = gestureConfig.swipeUpAction,
                isFolderItem = isFolder
            ) { action, pkg, _ ->
                gestureConfig.swipeUpAction = action
                if (pkg.isNotEmpty()) gestureConfig.setTargetPackage(ItemGestureManager.GESTURE_SWIPE_UP, pkg)
                refreshGestureBadges()
            }.show()
        }

        binding.gestureSwipeDownHolder.setOnClickListener {
            SelectActionDialog(
                activity = activity,
                gestureLabel = activity.getString(R.string.gesture_swipe_down),
                currentAction = gestureConfig.swipeDownAction,
                isFolderItem = isFolder
            ) { action, pkg, _ ->
                gestureConfig.swipeDownAction = action
                if (pkg.isNotEmpty()) gestureConfig.setTargetPackage(ItemGestureManager.GESTURE_SWIPE_DOWN, pkg)
                refreshGestureBadges()
            }.show()
        }

        binding.gestureSwipeLeftHolder.setOnClickListener {
            SelectActionDialog(
                activity = activity,
                gestureLabel = activity.getString(R.string.gesture_swipe_left),
                currentAction = gestureConfig.swipeLeftAction,
                isFolderItem = isFolder
            ) { action, pkg, _ ->
                gestureConfig.swipeLeftAction = action
                if (pkg.isNotEmpty()) gestureConfig.setTargetPackage(ItemGestureManager.GESTURE_SWIPE_LEFT, pkg)
                refreshGestureBadges()
            }.show()
        }

        binding.gestureSwipeRightHolder.setOnClickListener {
            SelectActionDialog(
                activity = activity,
                gestureLabel = activity.getString(R.string.gesture_swipe_right),
                currentAction = gestureConfig.swipeRightAction,
                isFolderItem = isFolder
            ) { action, pkg, _ ->
                gestureConfig.swipeRightAction = action
                if (pkg.isNotEmpty()) gestureConfig.setTargetPackage(ItemGestureManager.GESTURE_SWIPE_RIGHT, pkg)
                refreshGestureBadges()
            }.show()
        }

        binding.itemGestureClearAllBtn.setOnClickListener {
            gestureConfig.doubleTapAction = LauncherActionHandler.ACTION_NONE
            gestureConfig.swipeUpAction = LauncherActionHandler.ACTION_NONE
            gestureConfig.swipeDownAction = LauncherActionHandler.ACTION_NONE
            gestureConfig.swipeLeftAction = LauncherActionHandler.ACTION_NONE
            gestureConfig.swipeRightAction = LauncherActionHandler.ACTION_NONE
            refreshGestureBadges()
        }

        activity.getAlertDialogBuilder()
            .setPositiveButton(org.fossify.commons.R.string.ok, null)
            .setNegativeButton(org.fossify.commons.R.string.cancel, null)
            .apply {
                activity.setupDialogStuff(binding.root, this, R.string.item_gestures_title) { alertDialog ->
                    alertDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        if (item.id != null) {
                            ensureBackgroundThread {
                                ItemGestureManager.setGestureAction(
                                    activity,
                                    item.id!!,
                                    ItemGestureManager.GESTURE_SWIPE_UP,
                                    gestureConfig.swipeUpAction,
                                    gestureConfig.getTargetPackage(ItemGestureManager.GESTURE_SWIPE_UP)
                                )
                                ItemGestureManager.setGestureAction(
                                    activity,
                                    item.id!!,
                                    ItemGestureManager.GESTURE_SWIPE_DOWN,
                                    gestureConfig.swipeDownAction,
                                    gestureConfig.getTargetPackage(ItemGestureManager.GESTURE_SWIPE_DOWN)
                                )
                                ItemGestureManager.setGestureAction(
                                    activity,
                                    item.id!!,
                                    ItemGestureManager.GESTURE_SWIPE_LEFT,
                                    gestureConfig.swipeLeftAction,
                                    gestureConfig.getTargetPackage(ItemGestureManager.GESTURE_SWIPE_LEFT)
                                )
                                ItemGestureManager.setGestureAction(
                                    activity,
                                    item.id!!,
                                    ItemGestureManager.GESTURE_SWIPE_RIGHT,
                                    gestureConfig.swipeRightAction,
                                    gestureConfig.getTargetPackage(ItemGestureManager.GESTURE_SWIPE_RIGHT)
                                )

                                activity.runOnUiThread {
                                    callback()
                                    alertDialog.dismiss()
                                }
                            }
                        } else {
                            alertDialog.dismiss()
                        }
                    }
                }
            }
    }
}
