package com.appclone.ui.adapters

import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.appclone.core.AppInfo
import com.appclone.databinding.ItemAppBinding
import kotlinx.coroutines.*

class AppListAdapter(
    private val onAppClick: (AppInfo) -> Unit,
    private val onCloneClick: (AppInfo) -> Unit
) : ListAdapter<AppInfo, AppListAdapter.AppViewHolder>(AppDiffCallback) {

    private val iconCache = mutableMapOf<String, Drawable>()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    inner class AppViewHolder(
        private val binding: ItemAppBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(appInfo: AppInfo) {
            binding.appName.text = appInfo.appName
            binding.packageName.text = appInfo.packageName
            binding.appVersion.text = "v${appInfo.versionName}"
            binding.appSize.text = appInfo.formattedSize
            binding.appCategory.text = appInfo.category ?: "Ứng dụng"

            // Load icon from cache or try to load asynchronously
            val cachedIcon = iconCache[appInfo.packageName]
            if (cachedIcon != null) {
                binding.appIcon.setImageDrawable(cachedIcon)
            } else {
                binding.appIcon.setImageResource(com.appclone.R.drawable.ic_apps)
                loadIconAsync(appInfo.packageName)
            }

            binding.cloneButton.setOnClickListener {
                onCloneClick(appInfo)
            }

            binding.root.setOnClickListener {
                onAppClick(appInfo)
            }
        }
    }

    private fun loadIconAsync(packageName: String) {
        scope.launch {
            try {
                // Try loading icon from the package manager
                val pm = currentList.firstOrNull()?.let { }?.let { }
                // Fallback: load using reflection on the context from an itemView
                // We'll use a deferred approach - the icon will be set when available
                val context = currentList.firstOrNull()?.let {
                    // Try to load icon via PM if we can get it
                }
            } catch (e: Exception) {
                // Ignore - will show default icon
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

    fun setIconsFromMap(icons: Map<String, Drawable>) {
        iconCache.putAll(icons)
        notifyDataSetChanged()
    }

    fun preloadIcons(context: android.content.Context, packages: List<String>) {
        scope.launch {
            val pm = context.packageManager
            packages.forEach { packageName ->
                try {
                    val appInfo = pm.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
                    val icon = pm.getApplicationIcon(appInfo)
                    iconCache[packageName] = icon
                } catch (e: PackageManager.NameNotFoundException) {
                    // Package not found, skip
                }
            }
            withContext(Dispatchers.Main) {
                notifyDataSetChanged()
            }
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

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        scope.cancel()
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
