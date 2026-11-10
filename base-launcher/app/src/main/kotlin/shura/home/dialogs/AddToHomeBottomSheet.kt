package shura.home.dialogs

import android.os.Bundle
import android.view.LayoutInflater
import com.google.android.material.bottomsheet.BottomSheetDialog
import shura.home.activities.MainActivity
import shura.home.databinding.DialogAddToHomeBottomSheetBinding
import shura.home.helpers.LogCatcher

class AddToHomeBottomSheet(
    private val activity: MainActivity,
    private val targetX: Float? = null,
    private val targetY: Float? = null,
) : BottomSheetDialog(activity) {

    private val binding = DialogAddToHomeBottomSheetBinding.inflate(LayoutInflater.from(activity))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)

        setupListeners()
        LogCatcher.log("AddToHomeBottomSheet", "Displayed Add to Home sheet at coords ($targetX, $targetY)")
    }

    private fun setupListeners() {
        binding.addToHomeBtnClose.setOnClickListener {
            dismiss()
        }

        // 1. Applications
        binding.addCardApps.setOnClickListener {
            dismiss()
            activity.showAddAppDialog(targetX, targetY)
        }

        // 2. Desktop Widgets (Multi-cell)
        binding.addCardDesktopWidgets.setOnClickListener {
            dismiss()
            activity.showWidgetsFragment()
        }

        // 3. Popup Widgets (1-cell shutter)
        binding.addCardPopupWidgets.setOnClickListener {
            dismiss()
            activity.showAddStandalonePopupWidgetDialog(targetX, targetY)
        }

        // 4. Shortcuts
        binding.addCardShortcuts.setOnClickListener {
            dismiss()
            activity.showAddShortcutDialog(targetX, targetY)
        }

        // 5. Folders
        binding.addCardFolders.setOnClickListener {
            dismiss()
            activity.showAddFolderDialog(targetX, targetY)
        }
    }
}
