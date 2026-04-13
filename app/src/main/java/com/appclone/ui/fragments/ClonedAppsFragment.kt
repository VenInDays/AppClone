package com.appclone.ui.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.appclone.R
import com.appclone.databinding.FragmentClonedAppsBinding
import com.appclone.ui.adapters.ClonedAppAdapter
import com.appclone.ui.viewmodel.ClonedAppsViewModel
import com.google.android.material.badge.BadgeDrawable
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch

class ClonedAppsFragment : Fragment() {

    private var _binding: FragmentClonedAppsBinding? = null
    private val binding get() = _binding!!
    private val viewModel: ClonedAppsViewModel by viewModels()
    private lateinit var adapter: ClonedAppAdapter

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentClonedAppsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupRecyclerView()
        setupObservers()
        setupToolbar()
    }

    private fun setupRecyclerView() {
        adapter = ClonedAppAdapter(
            onLaunchClick = { clonedApp ->
                viewModel.launchClone(clonedApp)
            },
            onDeleteClick = { clonedApp ->
                showDeleteConfirmDialog(clonedApp)
            },
            onInstallClick = { clonedApp ->
                viewModel.installClone(clonedApp)
            },
            onSettingsClick = { clonedApp ->
                // Share the cloned app
                shareClonedApp(clonedApp)
            }
        )
        binding.recyclerView.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = this@ClonedAppsFragment.adapter
        }
    }

    private fun shareClonedApp(clonedApp: com.appclone.data.ClonedApp) {
        if (clonedApp.apkPath.isNotEmpty()) {
            val intent = android.content.Intent().apply {
                action = android.content.Intent.ACTION_SEND
                type = "application/vnd.android.package-archive"
                val apkUri = androidx.core.content.FileProvider.getUriForFile(
                    requireContext(),
                    "${requireContext().packageName}.fileprovider",
                    java.io.File(clonedApp.apkPath)
                )
                putExtra(android.content.Intent.EXTRA_STREAM, apkUri)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(android.content.Intent.createChooser(intent, "Chia sẻ APK"))
        }
    }

    private fun setupToolbar() {
        binding.toolbar.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.action_delete_all -> {
                    showDeleteAllConfirmDialog()
                    true
                }
                else -> false
            }
        }

        // Navigate to app list when clicking "Start cloning" button
        binding.btnStartCloning.setOnClickListener {
            // Switch to apps tab
            (activity as? com.appclone.ui.MainActivity)?.let { mainActivity ->
                mainActivity.findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(
                    com.appclone.R.id.bottomNavigation
                ).selectedItemId = com.appclone.R.id.nav_apps
            }
        }
    }

    private fun setupObservers() {
        lifecycleScope.launch {
            viewModel.clonedApps.collect { apps ->
                adapter.submitList(apps)
                binding.emptyState.isVisible = apps.isNullOrEmpty()
                binding.recyclerView.isVisible = !apps.isNullOrEmpty()
            }
        }

        lifecycleScope.launch {
            viewModel.clonedCount.collect { count ->
                binding.toolbar.subtitle = if (count > 0) "$count clone" else null

                // Update badge on bottom navigation
                (activity as? com.appclone.ui.MainActivity)?.updateCloneBadge(count)
            }
        }

        lifecycleScope.launch {
            viewModel.events.collect { event ->
                when (event) {
                    is ClonedAppsViewModel.Event.ShowMessage -> {
                        com.google.android.material.snackbar.Snackbar.make(
                            binding.root, event.message,
                            com.google.android.material.snackbar.Snackbar.LENGTH_SHORT
                        ).show()
                    }
                    is ClonedAppsViewModel.Event.ShowError -> {
                        com.appclone.ui.ErrorReportActivity.launch(requireContext(), event.error)
                    }
                    is ClonedAppsViewModel.Event.None -> { /* no-op */ }
                }
            }
        }
    }

    private fun showDeleteConfirmDialog(clonedApp: com.appclone.data.ClonedApp) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Xóa ${clonedApp.cloneLabel}?")
            .setMessage(
                "Điều này sẽ gỡ cài đặt và xóa dữ liệu của clone này. " +
                "Hành động này không thể hoàn tác."
            )
            .setPositiveButton("Xóa") { _, _ ->
                viewModel.deleteClone(clonedApp)
            }
            .setNegativeButton("Hủy") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    private fun showDeleteAllConfirmDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Xóa tất cả clone?")
            .setMessage(
                "Điều này sẽ gỡ cài đặt và xóa dữ liệu của TẤT CẢ clone. " +
                "Hành động này không thể hoàn tác."
            )
            .setPositiveButton("Xóa tất cả") { _, _ ->
                viewModel.deleteAllClones()
            }
            .setNegativeButton("Hủy") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    fun refreshList() {
        viewModel.loadClonedApps()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
