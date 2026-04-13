package com.appclone.ui.adapters

import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.appclone.core.AppInfo
import com.appclone.databinding.ItemAppBinding

class AppListAdapter(
    private val onAppClick: (AppInfo) -> Unit,
    private val onCloneClick: (AppInfo) -> Unit
) : ListAdapter<AppInfo, AppListAdapter.AppViewHolder>(AppDiffCallback) {

    private val iconCache = mutableMapOf<String, Drawable>()

    inner class AppViewHolder(
        private val binding: ItemAppBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(appInfo: AppInfo) {
            binding.appName.text = appInfo.appName
            binding.packageName.text = appInfo.packageName
            binding.appVersion.text = "v${appInfo.versionName}"
            binding.appSize.text = appInfo.formattedSize
            binding.appCategory.text = appInfo.category ?: "Ứng dụng"

            // Load icon from cache or set default
            iconCache[appInfo.packageName]?.let {
                binding.appIcon.setImageDrawable(it)
            }

            binding.cloneButton.setOnClickListener {
                onCloneClick(appInfo)
            }

            binding.root.setOnClickListener {
                onAppClick(appInfo)
            }
        }
    }

    fun updateIcons(packageName: String, icon: Drawable) {
        iconCache[packageName] = icon
        val position = currentList.indexOfFirst { it.packageName == packageName }
        if (position >= 0) {
            notifyItemChanged(position)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppViewHolder {
        val binding = ItemAppBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return AppViewHolder(binding)
    }

    override fun onBindViewHolder(holder: AppViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    companion object AppDiffCallback : DiffUtil.ItemCallback<AppInfo>() {
        override fun areItemsTheSame(oldItem: AppInfo, newItem: AppInfo): Boolean {
            return oldItem.packageName == newItem.packageName
        }
        override fun areContentsTheSame(oldItem: AppInfo, newItem: AppInfo): Boolean {
            return oldItem == newItem
        }
    }
}
