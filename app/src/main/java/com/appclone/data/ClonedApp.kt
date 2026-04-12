package com.appclone.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "cloned_apps")
data class ClonedApp(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val packageName: String,
    val appName: String,
    val cloneLabel: String,
    val versionName: String,
    val cloneDate: Long = System.currentTimeMillis(),
    val lastUsedDate: Long = System.currentTimeMillis(),
    val isRunning: Boolean = false,
    val storageUsed: Long = 0,
    val notificationEnabled: Boolean = true
)
