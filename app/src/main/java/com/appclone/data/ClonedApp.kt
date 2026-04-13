package com.appclone.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.ColumnInfo

/**
 * Represents a cloned application stored in the database.
 */
@Entity(tableName = "cloned_apps")
data class ClonedApp(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,

    /** Original app package name (e.g., com.facebook.katana) */
    val packageName: String,

    /** Original app display name (e.g., Facebook) */
    val appName: String,

    /** Display label for the clone (e.g., "Facebook (Clone 1)") */
    val cloneLabel: String,

    /** The modified package name used for the cloned APK */
    @ColumnInfo(defaultValue = "")
    val clonedPackageName: String = "",

    /** Version name of the original app at clone time */
    val versionName: String,

    /** Timestamp when the clone was created */
    val cloneDate: Long = System.currentTimeMillis(),

    /** Timestamp when the clone was last used/launched */
    val lastUsedDate: Long = System.currentTimeMillis(),

    /** Whether the cloned app is currently running */
    val isRunning: Boolean = false,

    /** Storage used by the cloned app in bytes */
    val storageUsed: Long = 0,

    /** Whether notifications are enabled for this clone */
    val notificationEnabled: Boolean = true,

    /** Absolute path to the cloned APK file */
    @ColumnInfo(defaultValue = "")
    val apkPath: String = "",

    /** Clone number (1-based) */
    @ColumnInfo(defaultValue = "1")
    val cloneNumber: Int = 1
)
