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
    }

    private fun setupRecyclerView() {
        adapter = ClonedAppAdapter(
            onLaunchClick = { clonedApp ->
                viewModel.launchClone(clonedApp)
            },
            onDeleteClick = { clonedApp ->
                viewModel.deleteClone(clonedApp)
            },
            onSettingsClick = { clonedApp ->
                // Navigate to clone settings
            }
        )
        binding.recyclerView.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = this@ClonedAppsFragment.adapter
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
                binding.toolbar.subtitle = "$count clone${if (count != 1) "s" else ""} dang chay"
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

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
