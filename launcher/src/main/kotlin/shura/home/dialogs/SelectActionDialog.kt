package shura.home.dialogs

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.helpers.ensureBackgroundThread
import shura.home.R
import shura.home.activities.MainActivity
import shura.home.databinding.DialogSelectActionBinding
import shura.home.databinding.ItemSelectActionBinding
import shura.home.extensions.launchersDB
import shura.home.helpers.LauncherActionHandler

class SelectActionDialog(
    private val activity: Activity,
    private val gestureLabel: String,
    private val currentAction: String,
    private val isFolderItem: Boolean = false,
    private val onActionSelected: (action: String, targetPackage: String, targetLabel: String) -> Unit,
) : Dialog(activity) {

    data class ActionOption(
        val actionId: String,
        val iconRes: Int,
        val title: String,
        val description: String,
    )

    private val binding = DialogSelectActionBinding.inflate(LayoutInflater.from(activity))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)

        window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        setupUI()
    }

    private fun setupUI() {
        binding.selectActionSubtitle.text = activity.getString(R.string.choose_action_for_gesture, gestureLabel)
        binding.selectActionBtnClose.setOnClickListener { dismiss() }

        val options = buildActionOptions()
        val adapter = ActionOptionAdapter(options, currentAction) { selectedOption ->
            if (selectedOption.actionId == LauncherActionHandler.ACTION_LAUNCH_APP) {
                dismiss()
                pickAppToLaunch()
            } else {
                dismiss()
                onActionSelected(selectedOption.actionId, "", selectedOption.title)
            }
        }

        binding.selectActionRecycler.layoutManager = LinearLayoutManager(activity)
        binding.selectActionRecycler.adapter = adapter
    }

    private fun buildActionOptions(): List<ActionOption> {
        val list = ArrayList<ActionOption>()

        list.add(
            ActionOption(
                actionId = LauncherActionHandler.ACTION_LOCK_SCREEN,
                iconRes = R.drawable.ic_lock_vector,
                title = activity.getString(R.string.action_lock_screen),
                description = "Turn off screen and lock device"
            )
        )
        list.add(
            ActionOption(
                actionId = LauncherActionHandler.ACTION_NOTIFICATIONS,
                iconRes = R.drawable.ic_swipe_down_vector,
                title = activity.getString(R.string.action_notifications),
                description = "Expand system notification panel"
            )
        )
        list.add(
            ActionOption(
                actionId = LauncherActionHandler.ACTION_QUICK_SETTINGS,
                iconRes = R.drawable.ic_settings_gear_vector,
                title = activity.getString(R.string.action_quick_settings),
                description = "Expand quick settings panel"
            )
        )
        list.add(
            ActionOption(
                actionId = LauncherActionHandler.ACTION_APP_DRAWER,
                iconRes = R.drawable.ic_apps_vector,
                title = activity.getString(R.string.action_app_drawer),
                description = "Open all applications drawer"
            )
        )
        list.add(
            ActionOption(
                actionId = LauncherActionHandler.ACTION_ADD_TO_HOME,
                iconRes = R.drawable.ic_touch_vector,
                title = activity.getString(R.string.action_add_to_home),
                description = "Open Add Element bottom sheet"
            )
        )
        list.add(
            ActionOption(
                actionId = LauncherActionHandler.ACTION_ADD_WIDGET,
                iconRes = R.drawable.ic_widget_vector,
                title = activity.getString(R.string.action_add_widget),
                description = "Open desktop widgets drawer"
            )
        )
        list.add(
            ActionOption(
                actionId = LauncherActionHandler.ACTION_POPUP_WIDGET,
                iconRes = R.drawable.ic_popup_widget_glyph,
                title = activity.getString(R.string.action_popup_widget),
                description = "Open linked or on-demand popup widget"
            )
        )
        list.add(
            ActionOption(
                actionId = LauncherActionHandler.ACTION_MUTE_VOLUME,
                iconRes = R.drawable.ic_vibrate_vector,
                title = activity.getString(R.string.action_mute_volume),
                description = "Toggle media volume mute"
            )
        )
        list.add(
            ActionOption(
                actionId = LauncherActionHandler.ACTION_MUTE_RINGTONE,
                iconRes = R.drawable.ic_vibrate_vector,
                title = activity.getString(R.string.action_mute_ringtone),
                description = "Toggle ringer between normal and vibrate"
            )
        )
        list.add(
            ActionOption(
                actionId = LauncherActionHandler.ACTION_APP_SHORTCUTS,
                iconRes = R.drawable.ic_pin_shortcut_vector,
                title = activity.getString(R.string.action_app_shortcuts),
                description = "Open dynamic app shortcuts popup"
            )
        )

        if (isFolderItem) {
            list.add(
                ActionOption(
                    actionId = LauncherActionHandler.ACTION_FOLDER_POPUP,
                    iconRes = R.drawable.ic_folder_vector,
                    title = activity.getString(R.string.action_folder_popup),
                    description = "Open folder popup overlay"
                )
            )
        }

        list.add(
            ActionOption(
                actionId = LauncherActionHandler.ACTION_LAUNCH_APP,
                iconRes = R.drawable.ic_apps_vector,
                title = activity.getString(R.string.action_launch_app),
                description = "Launch a specific installed application"
            )
        )

        list.add(
            ActionOption(
                actionId = LauncherActionHandler.ACTION_NONE,
                iconRes = R.drawable.ic_cross_vector,
                title = activity.getString(R.string.action_none),
                description = "Disable this gesture"
            )
        )

        return list
    }

    private fun pickAppToLaunch() {
        ensureBackgroundThread {
            val apps = if (activity is MainActivity) {
                activity.getAllAppLaunchers()
            } else {
                activity.launchersDB.getAppLaunchers()
            }

            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                AddAppDialog(activity, apps) { selectedApp ->
                    onActionSelected(
                        LauncherActionHandler.ACTION_LAUNCH_APP,
                        selectedApp.packageName,
                        selectedApp.title
                    )
                }
            }
        }
    }

    private class ActionOptionAdapter(
        private val options: List<ActionOption>,
        private val currentAction: String,
        private val onOptionClicked: (ActionOption) -> Unit,
    ) : RecyclerView.Adapter<ActionOptionAdapter.ViewHolder>() {

        class ViewHolder(val binding: ItemSelectActionBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val binding = ItemSelectActionBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return ViewHolder(binding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val option = options[position]
            holder.binding.itemActionTitle.text = option.title
            holder.binding.itemActionDesc.text = option.description
            holder.binding.itemActionIcon.setImageDrawable(
                ContextCompat.getDrawable(holder.itemView.context, option.iconRes)
            )

            val isSelected = option.actionId == currentAction
            holder.binding.itemActionSelected.beVisibleIf(isSelected)

            holder.binding.root.setOnClickListener {
                onOptionClicked(option)
            }
        }

        override fun getItemCount(): Int = options.size
    }
}
