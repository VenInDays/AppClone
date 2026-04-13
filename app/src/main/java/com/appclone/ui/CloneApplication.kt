package com.appclone.ui

import android.app.Application
import com.appclone.core.GlobalExceptionHandler
import com.appclone.data.AppDatabase
import com.appclone.data.CloneRepository

class CloneApplication : Application() {

    val database: AppDatabase by lazy { AppDatabase.getDatabase(this) }
    val repository: CloneRepository by lazy { CloneRepository(database.clonedAppDao()) }

    override fun onCreate() {
        super.onCreate()
        instance = this

        // Register global exception handler to catch all crashes
        Thread.setDefaultUncaughtExceptionHandler(
            GlobalExceptionHandler(this)
        )
    }

    companion object {
        @Volatile
        private lateinit var instance: CloneApplication

        fun getInstance(): CloneApplication = instance
    }
}
