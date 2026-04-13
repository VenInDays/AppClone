package com.appclone.ui.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.appclone.R
import com.appclone.databinding.FragmentAppListBinding
import com.appclone.ui.adapters.AppListAdapter
import com.appclone.ui.viewmodel.AppListViewModel
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch

class AppListFragment : Fragment() {

    private var _binding: FragmentAppListBinding? = null
    private val binding get() = _binding!!
    private val viewModel: AppListViewModel by viewModels()
    private lateinit var adapter: AppListAdapter
    private var progressDialog: MaterialAlertDialogBuilder? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAppListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupRecyclerView()
        setupSearch()
        setupObservers()
        setupFab()
    }

    private fun setupRecyclerView() {
        adapter = AppListAdapter(
            onAppClick = { appInfo ->
                viewModel.onAppSelected(appInfo)
            },
            onCloneClick = { appInfo ->
                showCloneConfirmDialog(appInfo)
            }
        )
        binding.recyclerView.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = this@AppListFragment.adapter
        }
    }

    private fun setupSearch() {
        binding.searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                viewModel.searchApps(binding.searchInput.text.toString())
                true
            } else {
                false
            }
        }
    }

    private fun setupObservers() {
        lifecycleScope.launch {
            viewModel.appList.collect { apps ->
                adapter.submitList(apps)
                binding.emptyState.isVisible = apps.isEmpty()
                binding.recyclerView.isVisible = apps.isNotEmpty()
            }
        }

        lifecycleScope.launch {
            viewModel.isLoading.collect { loading ->
                binding.progressBar.isVisible = loading
            }
        }

        lifecycleScope.launch {
            viewModel.events.collect { event ->
                when (event) {
                    is AppListViewModel.Event.ShowMessage -> {
                        Toast.makeText(requireContext(), event.message, Toast.LENGTH_SHORT).show()
                    }
                    is AppListViewModel.Event.NavigateToClone -> {
                        // Could show clone detail or directly clone
                    }
                    is AppListViewModel.Event.CloneCompleted -> {
                        Toast.makeText(
                            requireContext(),
                            "Clone ${event.clonedApp.cloneLabel} thành công! Đang mở cài đặt...",
                            Toast.LENGTH_LONG
                        ).show()
                        hideCloneProgressDialog()
                    }
                    is AppListViewModel.Event.NeedInstallPermission -> {
                        showInstallPermissionDialog(event.packageName)
                    }
                    is AppListViewModel.Event.None -> { /* no-op */ }
                }
            }
        }

        // Clone progress observer
        lifecycleScope.launch {
            viewModel.cloneProgress.collect { progress ->
                if (progress != null) {
                    showCloneProgressDialog(progress.packageName, progress.progress, progress.message)
                } else {
                    hideCloneProgressDialog()
                }
            }
        }
    }

    private fun setupFab() {
        binding.fabRefresh.setOnClickListener {
            viewModel.loadAllApps()
        }
    }

    private fun showCloneConfirmDialog(appInfo: com.appclone.core.AppInfo) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Clone ${appInfo.appName}?")
            .setMessage(
                "AppClone sẽ tạo một bản sao của ${appInfo.appName} với tên gói khác. " +
                "Bản sao này sẽ chạy song song với ứng dụng gốc.\n\n" +
                "Package gốc: ${appInfo.packageName}\n" +
                "Kích thước: ${appInfo.formattedSize}"
            )
            .setPositiveButton("Clone") { _, _ ->
                viewModel.cloneApp(appInfo)
            }
            .setNegativeButton("Hủy") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    private fun showCloneProgressDialog(packageName: String, progress: Int, message: String) {
        if (!isAdded) return

        if (progressDialog == null) {
            val dialogView = LayoutInflater.from(requireContext())
                .inflate(R.layout.dialog_clone_progress, null)

            progressDialog = MaterialAlertDialogBuilder(requireContext())
                .setTitle("Đang clone...")
                .setView(dialogView)
                .setCancelable(false)
                .create()
                .also { it.show() }
        }

        progressDialog?.let { dialog ->
            if (dialog is androidx.appcompat.app.AlertDialog) {
                dialog.findViewById<TextView>(R.id.txtProgressMessage)?.text = message
                dialog.findViewById<android.widget.ProgressBar>(R.id.progressBar)?.progress = progress
                dialog.findViewById<TextView>(R.id.txtProgressPercent)?.text = "$progress%"
            }
        }
    }

    private fun hideCloneProgressDialog() {
        progressDialog?.let { dialog ->
            if (dialog is androidx.appcompat.app.AlertDialog && dialog.isShowing) {
                dialog.dismiss()
            }
        }
        progressDialog = null
    }

    private fun showInstallPermissionDialog(packageName: String) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Cần quyền cài đặt")
            .setMessage(
                "Để cài đặt ứng dụng đã clone, bạn cần cấp quyền \"Cài đặt từ nguồn không xác định\" cho AppClone.\n\n" +
                "Nhấn 'Mở cài đặt' để cấp quyền."
            )
            .setPositiveButton("Mở cài đặt") { _, _ ->
                viewModel.requestInstallPermission()
            }
            .setNegativeButton("Hủy") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        hideCloneProgressDialog()
        _binding = null
    }
}
