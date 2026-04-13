package com.appclone.ui.theme

import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import com.google.android.material.color.DynamicColors

object ThemeManager {

    enum class ThemeMode {
        SYSTEM, LIGHT, DARK
    }

    private var currentThemeMode = ThemeMode.SYSTEM

    fun setThemeMode(mode: ThemeMode) {
        currentThemeMode = mode
    }

    fun getThemeMode(): ThemeMode = currentThemeMode

    @RequiresApi(Build.VERSION_CODES.S)
    fun applyDynamicColors(applicationContext: Context) {
        if (DynamicColors.isDynamicColorAvailable()) {
            if (applicationContext is android.app.Application) {
                DynamicColors.applyToActivitiesIfAvailable(applicationContext)
            }
        }
    }

    fun isDynamicColorsAvailable(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    }
}
