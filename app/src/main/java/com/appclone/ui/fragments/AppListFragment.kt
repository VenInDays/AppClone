package com.appclone.ui.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.appclone.R
import com.appclone.databinding.FragmentAppListBinding
import com.appclone.ui.adapters.AppListAdapter
import com.appclone.ui.viewmodel.AppListViewModel
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch

class AppListFragment : Fragment() {

    private var _binding: FragmentAppListBinding? = null
    private val binding get() = _binding!!
    private val viewModel: AppListViewModel by viewModels()
    private lateinit var adapter: AppListAdapter
    private var progressDialog: androidx.appcompat.app.AlertDialog? = null

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
        setupChips()
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

    private fun setupChips() {
        binding.chipAll.setOnClickListener {
            binding.chipAll.isChecked = true
            viewModel.loadAllApps()
        }

        binding.chipPopular.setOnClickListener {
            viewModel.loadPopularApps()
        }

        binding.chipGroup.setOnCheckedChangeListener { _, checkedId ->
            when (checkedId) {
                R.id.chipAll -> viewModel.loadAllApps()
                R.id.chipPopular -> viewModel.loadPopularApps()
                R.id.chipMessaging -> filterByCategory("Nhắn tin")
                R.id.chipSocial -> filterByCategory("MXH")
            }
        }
    }

    private fun handleChipSelection() {
        val checkedId = binding.chipGroup.checkedChipId
        when (checkedId) {
            R.id.chipAll -> viewModel.loadAllApps()
            R.id.chipPopular -> viewModel.loadPopularApps()
            R.id.chipMessaging -> filterByCategory("Nhắn tin")
            R.id.chipSocial -> filterByCategory("MXH")
        }
    }

    private fun filterByCategory(category: String) {
        lifecycleScope.launch {
            viewModel.appList.collect { apps ->
                val filtered = apps.filter { it.category == category }
                adapter.submitList(filtered)
                updateAppCount(filtered.size)
            }
        }
    }

    private fun updateAppCount(count: Int) {
        binding.txtAppCount.text = "$count ứng dụng"
    }

    private fun setupObservers() {
        lifecycleScope.launch {
            viewModel.appList.collect { apps ->
                adapter.submitList(apps)
                binding.emptyState.isVisible = apps.isEmpty()
                binding.recyclerView.isVisible = apps.isNotEmpty()
                updateAppCount(apps.size)

                // Preload icons
                if (apps.isNotEmpty()) {
                    adapter.preloadIcons(requireContext(), apps.map { it.packageName })
                }
            }
        }

        lifecycleScope.launch {
            viewModel.isLoading.collect { loading ->
                binding.progressBar.isVisible = loading
                binding.shimmerLoading.isVisible = loading
                binding.recyclerView.isVisible = !loading
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

        if (progressDialog == null || progressDialog?.isShowing == false) {
            val dialogView = LayoutInflater.from(requireContext())
                .inflate(R.layout.dialog_clone_progress, null)

            val dialog = MaterialAlertDialogBuilder(requireContext())
                .setTitle("Đang clone...")
                .setView(dialogView)
                .setCancelable(false)
                .create()
            dialog.show()
            progressDialog = dialog
        }

        progressDialog?.let { dialog ->
            dialog.findViewById<TextView>(R.id.txtProgressMessage)?.text = message
            dialog.findViewById<TextView>(R.id.txtProgressPercent)?.text = "$progress%"

            val progressBar = dialog.findViewById<com.google.android.material.progressindicator.LinearProgressIndicator>(R.id.progressBar)
            progressBar?.setProgress(progress, true)

            // Update step indicators based on progress
            updateStepIndicators(dialog, progress)
        }
    }

    private fun updateStepIndicators(dialog: androidx.appcompat.app.AlertDialog, progress: Int) {
        val primaryColor = ContextCompat.getColor(requireContext(), R.color.md_theme_primary)
        val outlineColor = ContextCompat.getColor(requireContext(), R.color.md_theme_on_surface_variant)

        // Step 1: Extracting (0-25%)
        dialog.findViewById<ImageView>(R.id.step1Icon)?.imageTintList =
            android.content.res.ColorStateList.valueOf(if (progress >= 0) primaryColor else outlineColor)
        dialog.findViewById<TextView>(R.id.step1Text)?.setTextColor(
            if (progress >= 0) primaryColor else outlineColor
        )

        // Step 2: Modifying (25-60%)
        dialog.findViewById<ImageView>(R.id.step2Icon)?.imageTintList =
            android.content.res.ColorStateList.valueOf(if (progress >= 25) primaryColor else outlineColor)
        dialog.findViewById<TextView>(R.id.step2Text)?.setTextColor(
            if (progress >= 25) primaryColor else outlineColor
        )

        // Step 3: Signing (60-90%)
        dialog.findViewById<ImageView>(R.id.step3Icon)?.imageTintList =
            android.content.res.ColorStateList.valueOf(if (progress >= 60) primaryColor else outlineColor)
        dialog.findViewById<TextView>(R.id.step3Text)?.setTextColor(
            if (progress >= 60) primaryColor else outlineColor
        )

        // Step 4: Done (90-100%)
        dialog.findViewById<ImageView>(R.id.step4Icon)?.imageTintList =
            android.content.res.ColorStateList.valueOf(if (progress >= 90) primaryColor else outlineColor)
        dialog.findViewById<TextView>(R.id.step4Text)?.setTextColor(
            if (progress >= 90) primaryColor else outlineColor
        )
    }

    private fun hideCloneProgressDialog() {
        progressDialog?.let { dialog ->
            if (dialog.isShowing) {
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
