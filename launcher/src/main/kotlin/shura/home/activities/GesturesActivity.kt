package shura.home.activities

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.NavigationIcon
import shura.home.R
import shura.home.databinding.ActivityGesturesBinding
import shura.home.dialogs.SelectActionDialog
import shura.home.extensions.config
import shura.home.helpers.LauncherActionHandler
import shura.home.helpers.LogCatcher
import shura.home.receivers.LockDeviceAdminReceiver

class GesturesActivity : SimpleActivity() {

    private val binding by viewBinding(ActivityGesturesBinding::inflate)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)

        setupEdgeToEdge(padBottomSystem = listOf(binding.gesturesNestedScrollview))
        setupMaterialScrollListener(binding.gesturesNestedScrollview, binding.gesturesAppbar)
        setupTopAppBar(binding.gesturesAppbar, NavigationIcon.Arrow)

        setupListeners()
    }

    override fun onResume() {
        super.onResume()
        refreshUI()
    }

    private fun setupListeners() {
        binding.gestureDoubleTapHolder.setOnClickListener {
            SelectActionDialog(
                activity = this,
                gestureLabel = getString(R.string.gesture_double_tap),
                currentAction = config.gestureDoubleTapAction
            ) { action, _, _ ->
                config.gestureDoubleTapAction = action
                refreshUI()
                LogCatcher.log("GesturesActivity", "Updated Double Tap gesture to: $action")
            }.show()
        }

        binding.gestureSwipeDownHolder.setOnClickListener {
            SelectActionDialog(
                activity = this,
                gestureLabel = getString(R.string.gesture_swipe_down),
                currentAction = config.gestureSwipeDownAction
            ) { action, _, _ ->
                config.gestureSwipeDownAction = action
                refreshUI()
                LogCatcher.log("GesturesActivity", "Updated Swipe Down gesture to: $action")
            }.show()
        }

        binding.gestureSwipeUpHolder.setOnClickListener {
            SelectActionDialog(
                activity = this,
                gestureLabel = getString(R.string.gesture_swipe_up),
                currentAction = config.gestureSwipeUpAction
            ) { action, _, _ ->
                config.gestureSwipeUpAction = action
                refreshUI()
                LogCatcher.log("GesturesActivity", "Updated Swipe Up gesture to: $action")
            }.show()
        }

        binding.gesturePinchInHolder.setOnClickListener {
            SelectActionDialog(
                activity = this,
                gestureLabel = getString(R.string.gesture_pinch_in),
                currentAction = config.gesturePinchInAction
            ) { action, _, _ ->
                config.gesturePinchInAction = action
                refreshUI()
                LogCatcher.log("GesturesActivity", "Updated Pinch In gesture to: $action")
            }.show()
        }

        binding.folderCoverModeHolder.setOnClickListener {
            binding.folderCoverModeSwitch.toggle()
            config.folderCoverMode = binding.folderCoverModeSwitch.isChecked
            LogCatcher.log("GesturesActivity", "Toggled Folder Cover Mode: ${config.folderCoverMode}")
        }

        binding.gestureHapticsHolder.setOnClickListener {
            binding.gestureHapticsSwitch.toggle()
            config.gestureHaptics = binding.gestureHapticsSwitch.isChecked
            LogCatcher.log("GesturesActivity", "Toggled Gesture Haptics: ${config.gestureHaptics}")
        }

        binding.gestureAdminStatusHolder.setOnClickListener {
            val dpm = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val admin = ComponentName(this, LockDeviceAdminReceiver::class.java)
            if (!dpm.isAdminActive(admin)) {
                val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                    putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin)
                    putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, getString(R.string.lock_device_admin_hint))
                }
                startActivity(intent)
            }
        }
    }

    private fun refreshUI() {
        val primaryColor = getProperPrimaryColor()
        arrayOf(
            binding.gesturesSectionHomeLabel,
            binding.gesturesSectionFolderLabel,
            binding.gesturesSectionFeedbackLabel
        ).forEach {
            it.setTextColor(primaryColor)
        }

        binding.gestureDoubleTapActionLabel.text = LauncherActionHandler.getActionLabel(this, config.gestureDoubleTapAction)
        binding.gestureSwipeDownActionLabel.text = LauncherActionHandler.getActionLabel(this, config.gestureSwipeDownAction)
        binding.gestureSwipeUpActionLabel.text = LauncherActionHandler.getActionLabel(this, config.gestureSwipeUpAction)
        binding.gesturePinchInActionLabel.text = LauncherActionHandler.getActionLabel(this, config.gesturePinchInAction)

        binding.folderCoverModeSwitch.isChecked = config.folderCoverMode
        binding.gestureHapticsSwitch.isChecked = config.gestureHaptics

        // Admin status
        val dpm = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = ComponentName(this, LockDeviceAdminReceiver::class.java)
        val isAdminActive = dpm.isAdminActive(admin)

        binding.gestureAdminStatusDesc.text = if (isAdminActive) {
            getString(R.string.device_admin_active)
        } else {
            getString(R.string.device_admin_inactive)
        }
        binding.gestureAdminStatusIndicator.beVisibleIf(isAdminActive)
    }
}
