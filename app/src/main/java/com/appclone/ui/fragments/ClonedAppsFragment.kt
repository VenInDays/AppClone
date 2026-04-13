package com.appclone.ui.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.appclone.databinding.FragmentClonedAppsBinding
import com.appclone.ui.adapters.ClonedAppAdapter
import com.appclone.ui.viewmodel.ClonedAppsViewModel
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
                // Navigate to clone settings (future feature)
            }
        )
        binding.recyclerView.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = this@ClonedAppsFragment.adapter
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
                binding.toolbar.subtitle = "$count clone đang hoạt động"
            }
        }

        lifecycleScope.launch {
            viewModel.events.collect { event ->
                when (event) {
                    is ClonedAppsViewModel.Event.ShowMessage -> {
                        Toast.makeText(requireContext(), event.message, Toast.LENGTH_SHORT).show()
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
