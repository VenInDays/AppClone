package com.appclone.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.appclone.core.PackageManagerHelper
import com.appclone.data.CloneRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class AppListViewModel(application: Application) : AndroidViewModel(application) {

    private val cloneEngine = com.appclone.core.CloneEngine.getInstance(application)
    private val profileManager = com.appclone.core.ProfileManager.getInstance(application)
    private val repository = (application as com.appclone.ui.CloneApplication).repository

    private val _appList = MutableStateFlow<List<com.appclone.core.AppInfo>>(emptyList())
    val appList: StateFlow<List<com.appclone.core.AppInfo>> = _appList.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _events = MutableStateFlow<Event>(Event.None)
    val events: StateFlow<Event> = _events.asStateFlow()

    sealed class Event {
        data object None : Event()
        data class ShowMessage(val message: String) : Event()
        data class NavigateToClone(val appInfo: com.appclone.core.AppInfo) : Event()
    }

    init {
        loadPopularApps()
    }

    fun loadPopularApps() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            _isLoading.value = true
            try {
                val apps = cloneEngine.popularCloneTargets
                _appList.value = apps
            } catch (e: Exception) {
                _events.value = Event.ShowMessage("Lỗi tải danh sách ứng dụng")
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun loadAllApps() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            _isLoading.value = true
            try {
                val apps = cloneEngine.allInstalledApps
                _appList.value = apps
            } catch (e: Exception) {
                _events.value = Event.ShowMessage("Lỗi tải danh sách ứng dụng")
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun searchApps(query: String) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            _isLoading.value = true
            try {
                val apps = cloneEngine.searchApps(query)
                _appList.value = apps
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun cloneApp(appInfo: com.appclone.core.AppInfo) {
        viewModelScope.launch {
            try {
                val cloneCount = repository.getCloneCount(appInfo.packageName)
                val clonedApp = com.appclone.data.ClonedApp(
                    packageName = appInfo.packageName,
                    appName = appInfo.appName,
                    cloneLabel = "${appInfo.appName} (Clone ${cloneCount + 1})",
                    versionName = appInfo.versionName ?: "Unknown"
                )
                repository.addClone(clonedApp)

                if (profileManager.isProfileOwner()) {
                    profileManager.cloneApp(appInfo.packageName)
                }

                _events.value = Event.ShowMessage(
                    "Đã clone ${appInfo.appName} thành công!"
                )
            } catch (e: Exception) {
                _events.value = Event.ShowMessage(
                    "Lỗi clone ${appInfo.appName}: ${e.message}"
                )
            }
        }
    }

    fun onAppSelected(appInfo: com.appclone.core.AppInfo) {
        _events.value = Event.NavigateToClone(appInfo)
    }
}
