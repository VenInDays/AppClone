package com.appclone.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.fragment.app.Fragment
import com.appclone.R
import com.appclone.databinding.ActivityMainBinding
import com.appclone.ui.fragments.AppListFragment
import com.appclone.ui.fragments.ClonedAppsFragment
import com.appclone.ui.fragments.SettingsFragment
import com.google.android.material.badge.BadgeDrawable
import com.google.android.material.bottomnavigation.BottomNavigationView
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

    fun updateCloneBadge(count: Int) {
        val badge = binding.bottomNavigation.getOrCreateBadge(R.id.nav_clones)
        badge.number = count
        badge.isVisible = count > 0
        badge.backgroundColor = getColor(R.color.md_theme_primary)
        badge.badgeTextColor = getColor(R.color.md_theme_on_primary)
    }

    fun clearCloneBadge() {
        binding.bottomNavigation.removeBadge(R.id.nav_clones)
    }

    fun showSnackbar(message: String) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_SHORT).show()
    }

    override fun onResume() {
        super.onResume()
        // Refresh cloned apps list when returning from install
        val fragment = supportFragmentManager.findFragmentById(R.id.fragmentContainer)
        if (fragment is ClonedAppsFragment) {
            fragment.refreshList()
        }
    }
}
