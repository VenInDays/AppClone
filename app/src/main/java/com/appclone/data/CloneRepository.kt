package com.appclone.data

import kotlinx.coroutines.flow.Flow

class CloneRepository(private val dao: ClonedAppDao) {

    val allClones: Flow<List<ClonedApp>> = dao.getAllClonedApps()

    suspend fun getCloneCount(packageName: String): Int = dao.getCloneCount(packageName)

    suspend fun addClone(clonedApp: ClonedApp): Long = dao.insert(clonedApp)

    suspend fun updateClone(clonedApp: ClonedApp) = dao.update(clonedApp)

    suspend fun deleteClone(clonedApp: ClonedApp) = dao.delete(clonedApp)

    suspend fun deleteCloneById(id: Int) = dao.deleteById(id)

    suspend fun updateLastUsed(id: Int) = dao.updateLastUsed(id)

    suspend fun updateRunningStatus(id: Int, running: Boolean) = dao.updateRunningStatus(id, running)

    suspend fun getTotalClones(): Int = dao.getTotalCount()

    suspend fun deleteAllClones() = dao.deleteAll()
}
