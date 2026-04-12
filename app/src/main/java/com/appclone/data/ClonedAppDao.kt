package com.appclone.data

import androidx.lifecycle.LiveData
import androidx.room.*

@Dao
interface ClonedAppDao {
    @Query("SELECT * FROM cloned_apps ORDER BY lastUsedDate DESC")
    fun getAllClonedApps(): LiveData<List<ClonedApp>>

    @Query("SELECT * FROM cloned_apps ORDER BY cloneDate DESC")
    suspend fun getAllClonedAppsSync(): List<ClonedApp>

    @Query("SELECT * FROM cloned_apps WHERE packageName = :packageName")
    fun getByPackage(packageName: String): LiveData<List<ClonedApp>>

    @Query("SELECT * FROM cloned_apps WHERE id = :id")
    suspend fun getById(id: Int): ClonedApp?

    @Query("SELECT COUNT(*) FROM cloned_apps WHERE packageName = :packageName")
    suspend fun getCloneCount(packageName: String): Int

    @Query("UPDATE cloned_apps SET lastUsedDate = :timestamp WHERE id = :id")
    suspend fun updateLastUsed(id: Int, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE cloned_apps SET isRunning = :running WHERE id = :id")
    suspend fun updateRunningStatus(id: Int, running: Boolean)

    @Query("DELETE FROM cloned_apps WHERE id = :id")
    suspend fun deleteById(id: Int)

    @Query("SELECT COUNT(*) FROM cloned_apps")
    suspend fun getTotalCount(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(clonedApp: ClonedApp): Long

    @Update
    suspend fun update(clonedApp: ClonedApp)

    @Delete
    suspend fun delete(clonedApp: ClonedApp)

    @Query("DELETE FROM cloned_apps")
    suspend fun deleteAll()
}
