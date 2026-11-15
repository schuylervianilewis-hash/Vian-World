package shura.home.dialogs

import android.app.Activity
import android.app.AlertDialog
import org.fossify.commons.extensions.*
import shura.home.R
import shura.home.databinding.DialogCreateFolderBinding

class CreateFolderDialog(val activity: Activity, val callback: (name: String) -> Unit) {

    init {
        val binding = DialogCreateFolderBinding.inflate(activity.layoutInflater)
        val defaultName = activity.getString(R.string.folder)
        binding.createFolderEdittext.setText(defaultName)
        binding.createFolderEdittext.selectAll()

        activity.getAlertDialogBuilder()
            .setPositiveButton(org.fossify.commons.R.string.ok, null)
            .setNegativeButton(org.fossify.commons.R.string.cancel, null)
            .apply {
                activity.setupDialogStuff(binding.root, this, R.string.create_folder) { alertDialog ->
                    alertDialog.showKeyboard(binding.createFolderEdittext)
                    alertDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val name = binding.createFolderEdittext.value.trim()
                        val finalName = if (name.isNotEmpty()) name else defaultName
                        callback(finalName)
                        alertDialog.dismiss()
                    }
                }
            }
    }
}
