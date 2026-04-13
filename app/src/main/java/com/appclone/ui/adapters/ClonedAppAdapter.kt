package com.appclone.ui.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.appclone.R
import com.appclone.data.ClonedApp
import com.appclone.databinding.ItemClonedAppBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ClonedAppAdapter(
    private val onLaunchClick: (ClonedApp) -> Unit,
    private val onDeleteClick: (ClonedApp) -> Unit,
    private val onInstallClick: (ClonedApp) -> Unit,
    private val onSettingsClick: (ClonedApp) -> Unit
) : ListAdapter<ClonedApp, ClonedAppAdapter.CloneViewHolder>(CloneDiffCallback) {

    private val dateFormat = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())

    inner class CloneViewHolder(
        private val binding: ItemClonedAppBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(clonedApp: ClonedApp) {
            binding.cloneName.text = clonedApp.cloneLabel
            binding.packageName.text = clonedApp.clonedPackageName.ifEmpty { clonedApp.packageName }
            binding.versionInfo.text = "v${clonedApp.versionName}"
            binding.cloneDate.text = dateFormat.format(Date(clonedApp.cloneDate))

            // Show install button if APK exists
            binding.installButton.isVisible = clonedApp.apkPath.isNotEmpty()

            binding.launchButton.setOnClickListener { onLaunchClick(clonedApp) }
            binding.deleteButton.setOnClickListener { onDeleteClick(clonedApp) }
            binding.installButton.setOnClickListener { onInstallClick(clonedApp) }
            binding.settingsButton.setOnClickListener { onSettingsClick(clonedApp) }

            // Status indicator with theme-aware colors
            val context = binding.root.context
            val bgColor = if (clonedApp.isRunning) {
                ContextCompat.getColor(context, R.color.md_theme_primary_container)
            } else {
                ContextCompat.getColor(context, R.color.md_theme_surface_variant)
            }
            binding.statusIndicator.setCardBackgroundColor(bgColor)

            val iconTint = if (clonedApp.isRunning) {
                ContextCompat.getColor(context, R.color.md_theme_on_primary_container)
            } else {
                ContextCompat.getColor(context, R.color.md_theme_on_surface_variant)
            }
            binding.appIcon.imageTintList = android.content.res.ColorStateList.valueOf(iconTint)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CloneViewHolder {
        val binding = ItemClonedAppBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return CloneViewHolder(binding)
    }

    override fun onBindViewHolder(holder: CloneViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    companion object CloneDiffCallback : DiffUtil.ItemCallback<ClonedApp>() {
        override fun areItemsTheSame(oldItem: ClonedApp, newItem: ClonedApp): Boolean {
            return oldItem.id == newItem.id
        }
        override fun areContentsTheSame(oldItem: ClonedApp, newItem: ClonedApp): Boolean {
            return oldItem == newItem
        }
    }
}
