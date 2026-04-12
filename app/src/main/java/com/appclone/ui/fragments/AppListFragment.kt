package com.appclone.ui.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.appclone.R
import com.appclone.databinding.FragmentAppListBinding
import com.appclone.ui.adapters.AppListAdapter
import com.appclone.ui.viewmodel.AppListViewModel
import kotlinx.coroutines.launch

class AppListFragment : Fragment() {

    private var _binding: FragmentAppListBinding? = null
    private val binding get() = _binding!!
    private val viewModel: AppListViewModel by viewModels()
    private lateinit var adapter: AppListAdapter

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
                viewModel.cloneApp(appInfo)
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

        binding.searchInput.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus && binding.searchInput.text.isNullOrEmpty()) {
                viewModel.loadPopularApps()
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
                        binding.snackbarHost.showSnackbar(event.message)
                    }
                    is AppListViewModel.Event.NavigateToClone -> {
                        // Navigate to clone detail
                    }
                }
            }
        }
    }

    private fun setupFab() {
        binding.fabRefresh.setOnClickListener {
            viewModel.loadPopularApps()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
