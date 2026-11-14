package shura.home.dialogs

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.fossify.commons.extensions.beGone
import org.fossify.commons.extensions.beVisible
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.normalizeString
import org.fossify.commons.helpers.ensureBackgroundThread
import shura.home.R
import shura.home.databinding.DialogSelectPopupWidgetBinding
import shura.home.databinding.ItemSelectPopupWidgetBinding
import shura.home.extensions.getInitialCellSize
import shura.home.models.HomeScreenGridItem

class SelectPopupWidgetDialog(
    private val activity: Activity,
    private val targetItem: HomeScreenGridItem,
    private val onWidgetSelected: (info: AppWidgetProviderInfo) -> Unit,
) : Dialog(activity) {

    data class WidgetPickerItem(
        val providerInfo: AppWidgetProviderInfo,
        val appTitle: String,
        val widgetLabel: String,
        val previewDrawable: Drawable?,
        val widthCells: Int,
        val heightCells: Int,
    )

    private val binding = DialogSelectPopupWidgetBinding.inflate(LayoutInflater.from(activity))
    private val allWidgetItems = ArrayList<WidgetPickerItem>()
    private val displayedItems = ArrayList<WidgetPickerItem>()
    private var showingAppOnly = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        setupUI()
        loadWidgets()
    }

    private fun setupUI() {
        val appTitle = targetItem.title.ifEmpty { activity.getString(R.string.app_launcher_name) }
        binding.selectWidgetSubtitle.text = activity.getString(R.string.select_widget_to_link, appTitle)

        binding.selectWidgetBtnClose.setOnClickListener {
            dismiss()
        }

        val adapter = WidgetPickerAdapter(displayedItems) { selected ->
            dismiss()
            onWidgetSelected(selected.providerInfo)
        }

        binding.selectWidgetRecycler.layoutManager = LinearLayoutManager(activity)
        binding.selectWidgetRecycler.adapter = adapter

        binding.selectWidgetSearchInput.doAfterTextChanged { text ->
            filterList(text?.toString().orEmpty(), adapter)
        }

        binding.selectWidgetToggleFilter.setOnClickListener {
            showingAppOnly = !showingAppOnly
            updateFilterToggleText()
            filterList(binding.selectWidgetSearchInput.text?.toString().orEmpty(), adapter)
        }
    }

    private fun updateFilterToggleText() {
        val appTitle = targetItem.title.ifEmpty { activity.getString(R.string.app_launcher_name) }
        if (showingAppOnly) {
            binding.selectWidgetToggleFilter.text = activity.getString(R.string.show_all_widgets)
        } else {
            binding.selectWidgetToggleFilter.text = activity.getString(R.string.widgets_from_app, appTitle)
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun loadWidgets() {
        ensureBackgroundThread {
            val manager = AppWidgetManager.getInstance(activity)
            val packageManager = activity.packageManager
            val installed = manager.installedProviders

            val loadedList = ArrayList<WidgetPickerItem>()
            val targetPkg = targetItem.packageName

            for (info in installed) {
                val pkg = info.provider.packageName
                val appLabel = try {
                    packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
                } catch (e: Exception) {
                    pkg
                }

                val widgetLabel = info.loadLabel(packageManager).ifEmpty { appLabel }
                val preview = try {
                    info.loadPreviewImage(activity, activity.resources.displayMetrics.densityDpi)
                        ?: packageManager.getApplicationIcon(pkg)
                } catch (e: Exception) {
                    null
                }

                val cellSize = activity.getInitialCellSize(info, info.minWidth, info.minHeight)
                loadedList.add(
                    WidgetPickerItem(
                        providerInfo = info,
                        appTitle = appLabel,
                        widgetLabel = widgetLabel,
                        previewDrawable = preview,
                        widthCells = cellSize.width,
                        heightCells = cellSize.height,
                    )
                )
            }

            loadedList.sortWith(compareBy({ it.appTitle.normalizeString().lowercase() }, { it.widgetLabel.lowercase() }))

            activity.runOnUiThread {
                allWidgetItems.clear()
                allWidgetItems.addAll(loadedList)

                val hasAppWidgets = targetPkg.isNotEmpty() && allWidgetItems.any { it.providerInfo.provider.packageName == targetPkg }
                showingAppOnly = hasAppWidgets
                binding.selectWidgetToggleFilter.beVisibleIf(hasAppWidgets)
                if (hasAppWidgets) {
                    updateFilterToggleText()
                }

                val adapter = binding.selectWidgetRecycler.adapter as? WidgetPickerAdapter
                if (adapter != null) {
                    filterList(binding.selectWidgetSearchInput.text?.toString().orEmpty(), adapter)
                }
            }
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun filterList(query: String, adapter: WidgetPickerAdapter) {
        val trimmed = query.trim().lowercase()
        val targetPkg = targetItem.packageName

        val filtered = allWidgetItems.filter { item ->
            val matchesApp = !showingAppOnly || item.providerInfo.provider.packageName == targetPkg
            val matchesQuery = trimmed.isEmpty() ||
                item.widgetLabel.lowercase().contains(trimmed) ||
                item.appTitle.lowercase().contains(trimmed)
            matchesApp && matchesQuery
        }

        displayedItems.clear()
        displayedItems.addAll(filtered)
        adapter.notifyDataSetChanged()

        val isEmpty = displayedItems.isEmpty()
        binding.selectWidgetEmptyText.beVisibleIf(isEmpty)
        binding.selectWidgetRecycler.beVisibleIf(!isEmpty)
        if (isEmpty) {
            if (showingAppOnly) {
                binding.selectWidgetEmptyText.text = activity.getString(R.string.no_widgets_for_app)
            } else {
                binding.selectWidgetEmptyText.text = activity.getString(R.string.no_widgets_available)
            }
        }
    }

    private class WidgetPickerAdapter(
        private val items: List<WidgetPickerItem>,
        private val onItemClick: (WidgetPickerItem) -> Unit,
    ) : RecyclerView.Adapter<WidgetPickerAdapter.ViewHolder>() {

        class ViewHolder(val binding: ItemSelectPopupWidgetBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val binding = ItemSelectPopupWidgetBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return ViewHolder(binding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.binding.itemWidgetLabel.text = item.widgetLabel
            holder.binding.itemWidgetAppName.text = item.appTitle
            holder.binding.itemWidgetDimensions.text = "${item.widthCells} × ${item.heightCells}"

            if (item.previewDrawable != null) {
                holder.binding.itemWidgetPreview.setImageDrawable(item.previewDrawable)
                holder.binding.itemWidgetPreview.beVisible()
            } else {
                holder.binding.itemWidgetPreview.setImageResource(R.drawable.ic_widget_vector)
            }

            holder.binding.root.setOnClickListener {
                onItemClick(item)
            }
        }

        override fun getItemCount(): Int = items.size
    }
}
