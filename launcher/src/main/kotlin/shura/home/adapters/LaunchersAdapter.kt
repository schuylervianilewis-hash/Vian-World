package shura.home.adapters

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.qtalk.recyclerviewfastscroller.RecyclerViewFastScroller
import org.fossify.commons.extensions.beGone
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.getColoredDrawableWithColor
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.commons.extensions.realScreenSize
import org.fossify.commons.helpers.ensureBackgroundThread
import shura.home.R
import shura.home.activities.SimpleActivity
import shura.home.databinding.ItemLauncherLabelBinding
import shura.home.extensions.animateScale
import shura.home.extensions.config
import shura.home.extensions.getDrawableForPackageName
import shura.home.helpers.IconCache
import shura.home.interfaces.AllAppsListener
import shura.home.models.AppLauncher

class LaunchersAdapter(
    val activity: SimpleActivity,
    val allAppsListener: AllAppsListener,
    val itemClick: (Any) -> Unit
) : ListAdapter<AppLauncher, LaunchersAdapter.ViewHolder>(AppLauncherDiffCallback()),
    RecyclerViewFastScroller.OnPopupTextUpdate {

    private var textColor = Color.WHITE
    private var iconPadding = 0

    init {
        setHasStableIds(true)
        calculateIconWidth()
    }

    override fun getItemId(position: Int): Long {
        return getItem(position).getLauncherIdentifier().hashCode().toLong()
    }

    fun launchFirstApp(): Boolean {
        val launcher = currentList.firstOrNull() ?: return false
        itemClick(launcher)
        return true
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemLauncherLabelBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding.root)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bindView(getItem(position))
    }

    override fun submitList(list: MutableList<AppLauncher>?) {
        calculateIconWidth()
        super.submitList(list)
    }

    private fun calculateIconWidth() {
        val currentColumnCount = activity.config.drawerColumnCount
        val iconWidth = activity.realScreenSize.x / currentColumnCount
        iconPadding = (iconWidth * 0.12f).toInt()
    }

    @SuppressLint("NotifyDataSetChanged")
    fun updateTextColor(newTextColor: Int) {
        textColor = Color.WHITE
        notifyDataSetChanged()
    }

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        @SuppressLint("ClickableViewAccessibility")
        fun bindView(launcher: AppLauncher): View {
            val binding = ItemLauncherLabelBinding.bind(itemView)
            itemView.apply {
                binding.launcherLabel.text = launcher.title
                binding.launcherLabel.setTextColor(textColor)
                binding.launcherLabel.beVisibleIf(activity.config.showDrawerAppLabels)
                binding.launcherIcon.setPadding(iconPadding, iconPadding, iconPadding, iconPadding)
                binding.launcherNewDot.beVisibleIf(activity.config.newInstalledPackages.contains(launcher.packageName))

                val isLocked = shura.home.helpers.AppLockManager.isAppLocked(launcher.packageName)
                val key = when {
                    launcher.isArchived && isLocked -> "${launcher.getLauncherIdentifier()}_archived_locked"
                    launcher.isArchived -> "${launcher.getLauncherIdentifier()}_archived"
                    isLocked -> "${launcher.getLauncherIdentifier()}_locked"
                    else -> launcher.getLauncherIdentifier()
                }
                val cachedDrawable = launcher.drawable ?: IconCache.getDrawable(key)

                if (cachedDrawable != null) {
                    launcher.drawable = cachedDrawable
                    binding.launcherIcon.setImageDrawable(cachedDrawable)
                    binding.launcherIcon.tag = key
                } else {
                    val placeholderDrawable = activity.resources.getColoredDrawableWithColor(
                        drawableId = R.drawable.placeholder_drawable,
                        color = launcher.thumbnailColor
                    )
                    binding.launcherIcon.setImageDrawable(placeholderDrawable)
                    binding.launcherIcon.tag = key

                    ensureBackgroundThread {
                        var rawDrawable = activity.getDrawableForPackageName(launcher.packageName, launcher.activityName)
                            ?: try {
                                if (launcher.activityName.isNotEmpty()) {
                                    activity.packageManager.getActivityIcon(android.content.ComponentName(launcher.packageName, launcher.activityName))
                                } else {
                                    activity.packageManager.getApplicationIcon(launcher.packageName)
                                }
                            } catch (e: Exception) {
                                null
                            }
                        if (rawDrawable == null && android.os.Build.VERSION.SDK_INT >= 35) {
                            rawDrawable = try {
                                activity.packageManager.getArchivedPackage(launcher.packageName)
                                    ?.launcherActivities?.firstOrNull()?.icon
                            } catch (e: Exception) {
                                null
                            }
                        }

                        val loadedDrawable = when {
                            rawDrawable == null -> null
                            launcher.isArchived && isLocked -> {
                                val arch = shura.home.helpers.IconDecorator.decorateArchivedIcon(activity, rawDrawable)
                                shura.home.helpers.IconDecorator.decorateLockedIcon(activity, arch)
                            }
                            launcher.isArchived -> shura.home.helpers.IconDecorator.decorateArchivedIcon(activity, rawDrawable)
                            isLocked -> shura.home.helpers.IconDecorator.decorateLockedIcon(activity, rawDrawable)
                            else -> rawDrawable
                        }
                        if (loadedDrawable != null) {
                            IconCache.putDrawable(key, loadedDrawable)
                            launcher.drawable = loadedDrawable
                            activity.runOnUiThread {
                                if (binding.launcherIcon.tag == key) {
                                    binding.launcherIcon.setImageDrawable(loadedDrawable)
                                }
                            }
                        }
                    }
                }

                setOnClickListener {
                    if (activity.config.newInstalledPackages.contains(launcher.packageName)) {
                        activity.config.removeNewInstalledPackage(launcher.packageName)
                        binding.launcherNewDot.beGone()
                    }
                    itemClick(launcher)
                }
                setOnLongClickListener {
                    val iconLocation = IntArray(2)
                    binding.launcherIcon.getLocationOnScreen(iconLocation)
                    val iconLeft = iconLocation[0] + binding.launcherIcon.paddingLeft
                    val iconTop = iconLocation[1] + binding.launcherIcon.paddingTop
                    val iconRight = iconLocation[0] + binding.launcherIcon.width - binding.launcherIcon.paddingRight
                    val iconBottom = iconLocation[1] + binding.launcherIcon.height - binding.launcherIcon.paddingBottom
                    val iconRect = Rect(iconLeft, iconTop, iconRight, iconBottom)

                    allAppsListener.onAppLauncherLongPressed(
                        x = iconRect.exactCenterX(),
                        y = iconRect.exactCenterY(),
                        appLauncher = launcher,
                        iconRect = iconRect
                    )
                    true
                }

                setOnTouchListener { _, event ->
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            binding.launcherIcon.drawable.alpha = LAUNCHER_ALPHA_PRESSED
                            animateScale(
                                from = LAUNCHER_SCALE_NORMAL,
                                to = LAUNCHER_SCALE_PRESSED,
                                duration = LAUNCHER_SCALE_UP_DURATION
                            )
                        }

                        MotionEvent.ACTION_UP,
                        MotionEvent.ACTION_CANCEL -> {
                            binding.launcherIcon.drawable.alpha = LAUNCHER_ALPHA_NORMAL
                            animateScale(
                                from = LAUNCHER_SCALE_PRESSED,
                                to = LAUNCHER_SCALE_NORMAL,
                                duration = LAUNCHER_SCALE_DOWN_DURATION
                            )
                        }
                    }
                    false
                }
            }

            return itemView
        }
    }

    override fun onChange(position: Int) = currentList.getOrNull(position)?.getBubbleText() ?: ""

    companion object {
        private const val LAUNCHER_SCALE_NORMAL = 1f
        private const val LAUNCHER_SCALE_PRESSED = 1.15f
        private const val LAUNCHER_SCALE_UP_DURATION = 100L
        private const val LAUNCHER_SCALE_DOWN_DURATION = 50L
        private const val LAUNCHER_ALPHA_NORMAL = 255
        private const val LAUNCHER_ALPHA_PRESSED = 220
    }
}

private class AppLauncherDiffCallback : DiffUtil.ItemCallback<AppLauncher>() {
    override fun areItemsTheSame(oldItem: AppLauncher, newItem: AppLauncher): Boolean {
        return oldItem.getLauncherIdentifier().hashCode().toLong() ==
                newItem.getLauncherIdentifier().hashCode().toLong()
    }

    override fun areContentsTheSame(oldItem: AppLauncher, newItem: AppLauncher): Boolean {
        return oldItem.title == newItem.title &&
                oldItem.order == newItem.order &&
                oldItem.thumbnailColor == newItem.thumbnailColor &&
                oldItem.drawable != null &&
                newItem.drawable != null
    }
}
