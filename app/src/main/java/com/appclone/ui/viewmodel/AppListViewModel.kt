package com.appclone.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.appclone.core.ApkCloner
import com.appclone.core.CloneCallback
import com.appclone.data.CloneRepository
import com.appclone.data.ClonedApp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class AppListViewModel(application: Application) : AndroidViewModel(application) {

    private val cloneEngine = com.appclone.core.CloneEngine.getInstance(application)
    private val apkCloner = ApkCloner.getInstance(application)
    private val repository = (application as CloneApplication).repository

    private val _appList = MutableStateFlow<List<com.appclone.core.AppInfo>>(emptyList())
    val appList: StateFlow<List<com.appclone.core.AppInfo>> = _appList.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _cloneProgress = MutableStateFlow<CloneProgress?>(null)
    val cloneProgress: StateFlow<CloneProgress?> = _cloneProgress.asStateFlow()

    private val _isCloning = MutableStateFlow(false)
    val isCloning: StateFlow<Boolean> = _isCloning.asStateFlow()

    private val _events = MutableStateFlow<Event>(Event.None)
    val events: StateFlow<Event> = _events.asStateFlow()

    data class CloneProgress(
        val packageName: String,
        val progress: Int,
        val message: String
    )

    sealed class Event {
        data object None : Event()
        data class ShowMessage(val message: String) : Event()
        data class NavigateToClone(val appInfo: com.appclone.core.AppInfo) : Event()
        data class CloneCompleted(val clonedApp: ClonedApp) : Event()
        data class NeedInstallPermission(val packageName: String) : Event()
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
        // Check install permission first
        if (!apkCloner.canInstallPackages()) {
            _events.value = Event.NeedInstallPermission(appInfo.packageName)
            return
        }

        _isCloning.value = true

        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val cloneCount = repository.getCloneCount(appInfo.packageName)

                apkCloner.cloneApp(appInfo.packageName, appInfo.appName ?: "App", cloneCount,
                    object : CloneCallback {
                        override fun onProgress(pkg: String, progress: Int, message: String) {
                            _cloneProgress.value = CloneProgress(pkg, progress, message)
                        }

                        override fun onSuccess(
                            originalPackage: String,
                            clonedPackage: String,
                            cloneLabel: String,
                            cloneNumber: Int,
                            apkPath: String
                        ) {
                            // Save to database
                            viewModelScope.launch {
                                val clonedApp = ClonedApp(
                                    packageName = originalPackage,
                                    appName = appInfo.appName ?: "App",
                                    cloneLabel = cloneLabel,
                                    clonedPackageName = clonedPackage,
                                    versionName = appInfo.versionName ?: "Unknown",
                                    apkPath = apkPath,
                                    cloneNumber = cloneNumber
                                )
                                repository.addClone(clonedApp)
                                _events.value = Event.CloneCompleted(clonedApp)
                                _cloneProgress.value = null
                                _isCloning.value = false
                            }
                        }

                        override fun onFailure(pkg: String, error: String) {
                            _events.value = Event.ShowMessage("Clone thất bại: $error")
                            _cloneProgress.value = null
                            _isCloning.value = false
                        }
                    })
            } catch (e: Exception) {
                _events.value = Event.ShowMessage("Lỗi: ${e.message}")
                _cloneProgress.value = null
                _isCloning.value = false
            }
        }
    }

    fun requestInstallPermission() {
        apkCloner.requestInstallPermission()
    }

    fun clearCloneProgress() {
        _cloneProgress.value = null
    }

    fun onAppSelected(appInfo: com.appclone.core.AppInfo) {
        _events.value = Event.NavigateToClone(appInfo)
    }
}
