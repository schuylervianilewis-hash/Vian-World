package shura.home.dialogs

import android.app.Activity
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.getAlertDialogBuilder
import org.fossify.commons.extensions.normalizeString
import org.fossify.commons.extensions.setupDialogStuff
import shura.home.R
import shura.home.adapters.SelectShortcutAdapter
import shura.home.adapters.ShortcutItem
import shura.home.databinding.DialogSelectAppBinding

class AddShortcutDialog(
    val activity: Activity,
    shortcuts: List<ShortcutItem>,
    val callback: (shortcut: ShortcutItem) -> Unit,
) {

    init {
        val sortedShortcuts = shortcuts.sortedWith(
            compareBy(
                { it.title.normalizeString().lowercase() },
                { it.appName.normalizeString().lowercase() }
            )
        )
        val binding = DialogSelectAppBinding.inflate(activity.layoutInflater)

        binding.selectAppList.layoutManager = LinearLayoutManager(activity)

        activity.getAlertDialogBuilder()
            .setNegativeButton(org.fossify.commons.R.string.cancel, null)
            .apply {
                activity.setupDialogStuff(binding.root, this, R.string.select_shortcut) { alertDialog ->
                    val adapter = SelectShortcutAdapter(activity, sortedShortcuts) { selectedShortcut ->
                        alertDialog.dismiss()
                        callback(selectedShortcut)
                    }
                    binding.selectAppList.adapter = adapter
                    binding.selectAppSearch.doAfterTextChanged { text ->
                        adapter.filter(text?.toString().orEmpty()) { count ->
                            binding.selectAppEmptyPlaceholder.beVisibleIf(count == 0)
                            binding.selectAppList.beVisibleIf(count > 0)
                        }
                    }
                }
            }
    }
}
