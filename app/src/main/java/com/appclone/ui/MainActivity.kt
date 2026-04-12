package com.appclone.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.appclone.R
import com.appclone.databinding.ActivityMainBinding
import com.appclone.ui.fragments.AppListFragment
import com.appclone.ui.fragments.ClonedAppsFragment
import com.appclone.ui.fragments.SettingsFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.topAppBar)
        supportActionBar?.title = getString(R.string.app_name)

        setupBottomNavigation()
        checkProfileSetup()
    }

    private fun setupBottomNavigation() {
        binding.bottomNavigation.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_apps -> {
                    switchFragment(AppListFragment())
                    true
                }
                R.id.nav_clones -> {
                    switchFragment(ClonedAppsFragment())
                    true
                }
                R.id.nav_settings -> {
                    switchFragment(SettingsFragment())
                    true
                }
                else -> false
            }
        }
        binding.bottomNavigation.itemIconTintList = null

        // Default to apps tab
        if (supportFragmentManager.findFragmentById(R.id.fragmentContainer) == null) {
            switchFragment(AppListFragment())
        }
    }

    private fun switchFragment(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, fragment)
            .commitAllowingStateLoss()
    }

    private fun checkProfileSetup() {
        val profileManager = com.appclone.core.ProfileManager.getInstance(this)
        if (!profileManager.isProfileOwner()) {
            showSetupDialog()
        }
    }

    private fun showSetupDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Thiết lập ban đầu")
            .setMessage(
                "Để sử dụng AppClone, bạn cần kích hoạt hồ sơ làm việc. " +
                "Điều này cho phép chạy ứng dụng trong không gian riêng biệt.\n\n" +
                "Nhấn 'Thiết lập' để tiếp tục."
            )
            .setPositiveButton("Thiết lập") { _, _ ->
                val profileManager = com.appclone.core.ProfileManager.getInstance(this)
                profileManager.startProvisioning()
            }
            .setNegativeButton("Sau") { dialog, _ ->
                dialog.dismiss()
            }
            .setCancelable(false)
            .show()
    }

    fun showSnackbar(message: String) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_SHORT).show()
    }
}
