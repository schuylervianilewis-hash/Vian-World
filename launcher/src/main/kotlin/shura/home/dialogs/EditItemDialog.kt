package shura.home.dialogs

import android.app.Activity
import android.graphics.Bitmap
import androidx.appcompat.app.AlertDialog
import org.fossify.commons.extensions.beGone
import org.fossify.commons.extensions.beVisible
import org.fossify.commons.extensions.getAlertDialogBuilder
import org.fossify.commons.extensions.setupDialogStuff
import org.fossify.commons.extensions.showKeyboard
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.value
import org.fossify.commons.helpers.ensureBackgroundThread
import shura.home.R
import shura.home.activities.MainActivity
import shura.home.databinding.DialogEditItemBinding
import shura.home.extensions.getDrawableForPackageName
import shura.home.extensions.homeScreenGridItemsDB
import shura.home.extensions.launchersDB
import shura.home.helpers.CustomIconManager
import shura.home.helpers.ITEM_TYPE_FOLDER
import shura.home.helpers.ITEM_TYPE_ICON
import shura.home.helpers.ItemGestureConfig
import shura.home.helpers.ItemGestureManager
import shura.home.helpers.LauncherActionHandler
import shura.home.helpers.LogCatcher
import shura.home.models.HomeScreenGridItem

class EditItemDialog(
    val activity: Activity,
    val item: HomeScreenGridItem,
    val callback: () -> Unit
) {
    private var pendingCustomIcon: Bitmap? = null
    private var isIconReset = false

    init {
        val binding = DialogEditItemBinding.inflate(activity.layoutInflater)
        binding.editItemEdittext.setText(item.title)

        // Set initial icon preview
        if (item.icon != null) {
            binding.editItemIcon.setImageBitmap(item.icon)
        } else if (item.drawable != null) {
            binding.editItemIcon.setImageDrawable(item.drawable)
        } else if (item.packageName.isNotEmpty()) {
            binding.editItemIcon.setImageDrawable(activity.getDrawableForPackageName(item.packageName))
        }

        binding.editItemIconContainer.setOnClickListener {
            showIconPickerOptions(binding)
        }

        // Setup Gestures configuration
        val gestureConfig = if (item.id != null) {
            ItemGestureManager.getOrCreateConfig(activity, item.id!!)
        } else {
            ItemGestureConfig()
        }

        setupGesturesSection(binding, gestureConfig)

        activity.getAlertDialogBuilder()
            .setPositiveButton(org.fossify.commons.R.string.ok, null)
            .setNegativeButton(org.fossify.commons.R.string.cancel, null)
            .apply {
                activity.setupDialogStuff(binding.root, this, R.string.edit_item) { alertDialog ->
                    alertDialog.showKeyboard(binding.editItemEdittext)
                    alertDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val newTitle = binding.editItemEdittext.value
                        if (newTitle.isEmpty()) {
                            activity.toast(org.fossify.commons.R.string.value_cannot_be_empty)
                            return@setOnClickListener
                        }

                        ensureBackgroundThread {
                            val titleChanged = newTitle != item.title
                            if (titleChanged) {
                                item.title = newTitle
                                if (item.id != null) {
                                    activity.homeScreenGridItemsDB.updateItemTitle(newTitle, item.id!!)
                                }
                                if (item.type == ITEM_TYPE_ICON && item.packageName.isNotEmpty()) {
                                    activity.homeScreenGridItemsDB.updateAppTitle(newTitle, item.packageName)
                                    activity.launchersDB.updateAppTitle(newTitle, item.packageName)
                                }
                            }

                            if (isIconReset) {
                                item.icon = null
                                if (item.id != null) {
                                    activity.homeScreenGridItemsDB.updateItemIcon(null, item.id!!)
                                }
                                if (item.type == ITEM_TYPE_ICON && item.packageName.isNotEmpty()) {
                                    CustomIconManager.removeCustomIcon(activity, item.packageName)
                                    activity.homeScreenGridItemsDB.updateAppIcon(null, item.packageName)
                                }
                            } else if (pendingCustomIcon != null) {
                                val downsampled = CustomIconManager.downsampleBitmap(pendingCustomIcon!!)
                                item.icon = downsampled
                                if (item.id != null) {
                                    activity.homeScreenGridItemsDB.updateItemIcon(downsampled, item.id!!)
                                }
                                if (item.type == ITEM_TYPE_ICON && item.packageName.isNotEmpty()) {
                                    CustomIconManager.saveCustomIcon(activity, item.packageName, downsampled)
                                    activity.homeScreenGridItemsDB.updateAppIcon(downsampled, item.packageName)
                                }
                            }

                            // Save gesture actions
                            if (item.id != null) {
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
                            }

                            activity.runOnUiThread {
                                callback()
                                alertDialog.dismiss()
                            }
                        }
                    }
                }
            }
    }

    private fun setupGesturesSection(binding: DialogEditItemBinding, gestureConfig: ItemGestureConfig) {
        val isFolder = item.type == ITEM_TYPE_FOLDER

        fun refreshGestureBadges() {
            binding.itemGestureSwipeUpBadge.text = LauncherActionHandler.getActionLabel(activity, gestureConfig.swipeUpAction, gestureConfig.getTargetPackage(ItemGestureManager.GESTURE_SWIPE_UP))
            binding.itemGestureSwipeDownBadge.text = LauncherActionHandler.getActionLabel(activity, gestureConfig.swipeDownAction, gestureConfig.getTargetPackage(ItemGestureManager.GESTURE_SWIPE_DOWN))
            binding.itemGestureSwipeLeftBadge.text = LauncherActionHandler.getActionLabel(activity, gestureConfig.swipeLeftAction, gestureConfig.getTargetPackage(ItemGestureManager.GESTURE_SWIPE_LEFT))
            binding.itemGestureSwipeRightBadge.text = LauncherActionHandler.getActionLabel(activity, gestureConfig.swipeRightAction, gestureConfig.getTargetPackage(ItemGestureManager.GESTURE_SWIPE_RIGHT))
        }

        refreshGestureBadges()

        binding.itemGestureSwipeUpHolder.setOnClickListener {
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

        binding.itemGestureSwipeDownHolder.setOnClickListener {
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

        binding.itemGestureSwipeLeftHolder.setOnClickListener {
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

        binding.itemGestureSwipeRightHolder.setOnClickListener {
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
    }

    private fun showIconPickerOptions(binding: DialogEditItemBinding) {
        val options = arrayOf(
            activity.getString(R.string.choose_from_gallery),
            activity.getString(R.string.choose_from_apps),
            activity.getString(R.string.choose_from_symbols),
            activity.getString(R.string.reset_to_default_icon)
        )

        activity.getAlertDialogBuilder()
            .setTitle(R.string.change_icon)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        if (activity is MainActivity) {
                            activity.launchIconPicker { bitmap ->
                                if (bitmap != null) {
                                    pendingCustomIcon = bitmap
                                    isIconReset = false
                                    binding.editItemIcon.setImageBitmap(bitmap)
                                }
                            }
                        }
                    }
                    1 -> {
                        PickAppIconDialog(activity) { bitmap ->
                            pendingCustomIcon = bitmap
                            isIconReset = false
                            binding.editItemIcon.setImageBitmap(bitmap)
                        }
                    }
                    2 -> {
                        PickSymbolDialog(activity) { bitmap ->
                            pendingCustomIcon = bitmap
                            isIconReset = false
                            binding.editItemIcon.setImageBitmap(bitmap)
                        }
                    }
                    3 -> {
                        isIconReset = true
                        pendingCustomIcon = null
                        if (item.drawable != null) {
                            binding.editItemIcon.setImageDrawable(item.drawable)
                        } else if (item.packageName.isNotEmpty()) {
                            binding.editItemIcon.setImageDrawable(activity.getDrawableForPackageName(item.packageName))
                        }
                    }
                }
            }
            .setNegativeButton(org.fossify.commons.R.string.cancel, null)
            .show()
    }
}
