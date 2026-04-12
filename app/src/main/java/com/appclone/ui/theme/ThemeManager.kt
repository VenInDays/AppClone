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
    private var useDynamicColors = false

    fun setThemeMode(mode: ThemeMode) {
        currentThemeMode = mode
    }

    fun getThemeMode(): ThemeMode = currentThemeMode

    @RequiresApi(Build.VERSION_CODES.S)
    fun applyDynamicColors(context: Context) {
        if (DynamicColors.isDynamicColorAvailable()) {
            DynamicColors.applyToActivitiesIfAvailable(context.applicationContext)
            useDynamicColors = true
        }
    }

    fun isDynamicColorsAvailable(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    }
}
