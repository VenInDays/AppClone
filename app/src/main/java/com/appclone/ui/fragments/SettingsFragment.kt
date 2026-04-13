package com.appclone.ui.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatDelegate
import androidx.fragment.app.Fragment
import com.appclone.R
import com.appclone.databinding.FragmentSettingsBinding

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupViews()
    }

    private fun setupViews() {
        binding.topAppBar.setNavigationOnClickListener {
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }

        binding.switchNotifications.setOnCheckedChangeListener { _, isChecked ->
            // Save notification preference
        }

        binding.switchDarkMode.setOnCheckedChangeListener { _, isChecked ->
            // Toggle dark mode using AppCompatDelegate
            if (isChecked) {
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            } else {
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            }
        }

        binding.btnClearCache.setOnClickListener {
            com.google.android.material.snackbar.Snackbar.make(
                binding.root,
                "Đã xóa cache thành công",
                com.google.android.material.snackbar.Snackbar.LENGTH_SHORT
            ).show()
        }

        binding.btnClearAll.setOnClickListener {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle("Xóa tất cả clone?")
                .setMessage("Hành động này không thể hoàn tác.")
                .setPositiveButton("Xóa tất cả") { _, _ ->
                    com.google.android.material.snackbar.Snackbar.make(
                        binding.root,
                        "Đã xóa tất cả clone",
                        com.google.android.material.snackbar.Snackbar.LENGTH_SHORT
                    ).show()
                }
                .setNegativeButton("Hủy") { dialog, _ ->
                    dialog.dismiss()
                }
                .show()
        }

        binding.btnShare.setOnClickListener {
            val shareIntent = android.content.Intent().apply {
                action = android.content.Intent.ACTION_SEND
                putExtra(android.content.Intent.EXTRA_TEXT, "Hãy thử AppClone - Ứng dụng clone đa tài khoản mạnh mẽ! 🚀")
                type = "text/plain"
            }
            startActivity(android.content.Intent.createChooser(shareIntent, "Chia sẻ AppClone"))
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
