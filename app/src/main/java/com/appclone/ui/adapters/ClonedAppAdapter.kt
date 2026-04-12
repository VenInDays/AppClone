package com.appclone.ui.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.appclone.data.ClonedApp
import com.appclone.databinding.ItemClonedAppBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ClonedAppAdapter(
    private val onLaunchClick: (ClonedApp) -> Unit,
    private val onDeleteClick: (ClonedApp) -> Unit,
    private val onSettingsClick: (ClonedApp) -> Unit
) : ListAdapter<ClonedApp, ClonedAppAdapter.CloneViewHolder>(CloneDiffCallback) {

    private val dateFormat = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())

    inner class CloneViewHolder(
        private val binding: ItemClonedAppBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(clonedApp: ClonedApp) {
            binding.cloneName.text = clonedApp.cloneLabel
            binding.packageName.text = clonedApp.packageName
            binding.versionInfo.text = "v${clonedApp.versionName}"
            binding.cloneDate.text = dateFormat.format(Date(clonedApp.cloneDate))

            binding.launchButton.setOnClickListener { onLaunchClick(clonedApp) }
            binding.deleteButton.setOnClickListener { onDeleteClick(clonedApp) }
            binding.settingsButton.setOnClickListener { onSettingsClick(clonedApp) }

            binding.statusIndicator.setCardBackgroundColor(
                if (clonedApp.isRunning) {
                    binding.root.context.getColor(android.R.color.holo_green_light)
                } else {
                    binding.root.context.getColor(android.R.color.darker_gray)
                }
            )
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
