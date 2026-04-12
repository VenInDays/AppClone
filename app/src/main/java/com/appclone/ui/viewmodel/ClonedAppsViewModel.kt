package com.appclone.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.appclone.core.PackageManagerHelper
import com.appclone.core.ProfileManager
import com.appclone.data.ClonedApp
import com.appclone.data.CloneRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class ClonedAppsViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = (application as com.appclone.ui.CloneApplication).repository
    private val profileManager = ProfileManager.getInstance(application)

    private val _clonedApps = MutableStateFlow<List<ClonedApp>>(emptyList())
    val clonedApps: StateFlow<List<ClonedApp>> = _clonedApps.asStateFlow()

    private val _clonedCount = MutableStateFlow(0)
    val clonedCount: StateFlow<Int> = _clonedCount.asStateFlow()

    private val _events = MutableStateFlow<Event>(Event.None)
    val events: StateFlow<Event> = _events.asStateFlow()

    sealed class Event {
        data object None : Event()
        data class ShowMessage(val message: String) : Event()
    }

    init {
        loadClonedApps()
    }

    private fun loadClonedApps() {
        viewModelScope.launch {
            repository.allClones.collect { apps ->
                _clonedApps.value = apps
                _clonedCount.value = apps.size
            }
        }
    }

    fun launchClone(clonedApp: ClonedApp) {
        viewModelScope.launch {
            val context = getApplication<Application>()
            if (PackageManagerHelper.launchApp(context, clonedApp.packageName)) {
                repository.updateLastUsed(clonedApp.id)
                repository.updateRunningStatus(clonedApp.id, true)
                _events.value = Event.ShowMessage("Đang mở ${clonedApp.cloneLabel}...")
            } else {
                _events.value = Event.ShowMessage("Không thể mở ${clonedApp.cloneLabel}")
            }
        }
    }

    fun deleteClone(clonedApp: ClonedApp) {
        viewModelScope.launch {
            try {
                profileManager.removeClone(clonedApp.packageName)
                repository.deleteClone(clonedApp)
                _events.value = Event.ShowMessage("Đã xóa ${clonedApp.cloneLabel}")
            } catch (e: Exception) {
                _events.value = Event.ShowMessage("Lỗi xóa: ${e.message}")
            }
        }
    }

    fun deleteAllClones() {
        viewModelScope.launch {
            try {
                repository.deleteAllClones()
                profileManager.removeWorkProfile()
                _events.value = Event.ShowMessage("Đã xóa tất cả clone")
            } catch (e: Exception) {
                _events.value = Event.ShowMessage("Lỗi: ${e.message}")
            }
        }
    }
}
