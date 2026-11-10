package shura.home.dialogs

import android.app.Activity
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.getAlertDialogBuilder
import org.fossify.commons.extensions.normalizeString
import org.fossify.commons.extensions.setupDialogStuff
import shura.home.R
import shura.home.adapters.SelectAppAdapter
import shura.home.databinding.DialogSelectAppBinding
import shura.home.models.AppLauncher

class AddAppDialog(
    val activity: Activity,
    apps: List<AppLauncher>,
    val callback: (app: AppLauncher) -> Unit,
) {

    init {
        val sortedApps = apps.sortedWith(
            compareBy(
                { it.title.normalizeString().lowercase() },
                { it.packageName }
            )
        )
        val binding = DialogSelectAppBinding.inflate(activity.layoutInflater)
        val adapter = SelectAppAdapter(activity, sortedApps) { selectedApp ->
            callback(selectedApp)
        }

        binding.selectAppList.layoutManager = LinearLayoutManager(activity)
        binding.selectAppList.adapter = adapter

        binding.selectAppSearch.doAfterTextChanged { text ->
            adapter.filter(text?.toString().orEmpty()) { count ->
                binding.selectAppEmptyPlaceholder.beVisibleIf(count == 0)
                binding.selectAppList.beVisibleIf(count > 0)
            }
        }

        activity.getAlertDialogBuilder()
            .setNegativeButton(org.fossify.commons.R.string.cancel, null)
            .apply {
                activity.setupDialogStuff(binding.root, this, R.string.select_app) { alertDialog ->
                    // Auto-dismiss on click
                    val originalCallback = adapter.onAppClicked
                    val customAdapter = SelectAppAdapter(activity, sortedApps) { selectedApp ->
                        alertDialog.dismiss()
                        callback(selectedApp)
                    }
                    binding.selectAppList.adapter = customAdapter
                    binding.selectAppSearch.doAfterTextChanged { text ->
                        customAdapter.filter(text?.toString().orEmpty()) { count ->
                            binding.selectAppEmptyPlaceholder.beVisibleIf(count == 0)
                            binding.selectAppList.beVisibleIf(count > 0)
                        }
                    }
                }
            }
    }
}
