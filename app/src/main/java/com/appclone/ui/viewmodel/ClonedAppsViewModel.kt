package com.appclone.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.appclone.core.ApkCloner
import com.appclone.core.PackageManagerHelper
import com.appclone.data.ClonedApp
import com.appclone.data.CloneRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ClonedAppsViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = (application as com.appclone.ui.CloneApplication).repository
    private val apkCloner = ApkCloner.getInstance(application)

    private val _clonedApps = MutableStateFlow<List<ClonedApp>>(emptyList())
    val clonedApps: StateFlow<List<ClonedApp>> = _clonedApps.asStateFlow()

    private val _clonedCount = MutableStateFlow(0)
    val clonedCount: StateFlow<Int> = _clonedCount.asStateFlow()

    private val _events = MutableStateFlow<Event>(Event.None)
    val events: StateFlow<Event> = _events.asStateFlow()

    sealed class Event {
        data object None : Event()
        data class ShowMessage(val message: String) : Event()
        data class ShowError(val error: String) : Event()
    }

    init {
        loadClonedApps()
    }

    fun loadClonedApps() {
        viewModelScope.launch {
            repository.allClones.collect { apps ->
                _clonedApps.value = apps
                _clonedCount.value = apps.size
            }
        }
    }

    /**
     * Launch the cloned app by its CLONED package name.
     */
    fun launchClone(clonedApp: ClonedApp) {
        viewModelScope.launch {
            val context = getApplication<Application>()
            // Try to launch the cloned package first
            val pkgToLaunch = if (clonedApp.clonedPackageName.isNotEmpty()) {
                clonedApp.clonedPackageName
            } else {
                clonedApp.packageName
            }

            if (PackageManagerHelper.launchApp(context, pkgToLaunch)) {
                repository.updateLastUsed(clonedApp.id)
                repository.updateRunningStatus(clonedApp.id, true)
                _events.value = Event.ShowMessage("Đang mở ${clonedApp.cloneLabel}...")
            } else {
                // Clone not installed yet, try installing
                if (clonedApp.apkPath.isNotEmpty()) {
                    val apkFile = java.io.File(clonedApp.apkPath)
                    if (apkFile.exists()) {
                        apkCloner.installApk(apkFile)
                        _events.value = Event.ShowMessage("Đang cài đặt ${clonedApp.cloneLabel}...")
                    } else {
                        _events.value = Event.ShowMessage("File APK không tồn tại. Vui lòng clone lại.")
                    }
                } else {
                    _events.value = Event.ShowMessage("Clone chưa được cài đặt. Vui lòng cài đặt thủ công.")
                }
            }
        }
    }

    /**
     * Install the cloned APK (re-install from saved file).
     */
    fun installClone(clonedApp: ClonedApp) {
        viewModelScope.launch {
            if (clonedApp.apkPath.isNotEmpty()) {
                val apkFile = java.io.File(clonedApp.apkPath)
                if (apkFile.exists()) {
                    val started = apkCloner.installApk(apkFile)
                    if (started) {
                        _events.value = Event.ShowMessage("Đang cài đặt ${clonedApp.cloneLabel}...")
                    } else {
                        _events.value = Event.ShowMessage("Không thể cài đặt. Kiểm tra quyền cài đặt.")
                    }
                } else {
                    _events.value = Event.ShowMessage("File APK không tồn tại. Vui lòng clone lại.")
                }
            }
        }
    }

    fun deleteClone(clonedApp: ClonedApp) {
        viewModelScope.launch {
            try {
                // Uninstall the cloned app if it's installed
                if (clonedApp.clonedPackageName.isNotEmpty()) {
                    apkCloner.uninstallClone(clonedApp.clonedPackageName)
                }

                // Delete from database
                repository.deleteClone(clonedApp)

                // Delete APK file
                if (clonedApp.apkPath.isNotEmpty()) {
                    java.io.File(clonedApp.apkPath).delete()
                }

                _events.value = Event.ShowMessage("Đã xóa ${clonedApp.cloneLabel}")
            } catch (e: Exception) {
                _events.value = Event.ShowMessage("Lỗi xóa: ${e.message}")
            }
        }
    }

    fun deleteAllClones() {
        viewModelScope.launch {
            try {
                val clones = repository.allClones
                // Uninstall all cloned apps
                clones.collect { list ->
                    for (app in list) {
                        if (app.clonedPackageName.isNotEmpty()) {
                            apkCloner.uninstallClone(app.clonedPackageName)
                        }
                        if (app.apkPath.isNotEmpty()) {
                            java.io.File(app.apkPath).delete()
                        }
                    }
                }
                repository.deleteAllClones()
                _events.value = Event.ShowMessage("Đã xóa tất cả clone")
            } catch (e: Exception) {
                _events.value = Event.ShowMessage("Lỗi: ${e.message}")
            }
        }
    }
}
