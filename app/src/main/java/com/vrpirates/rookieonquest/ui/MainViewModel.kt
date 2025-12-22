package com.vrpirates.rookieonquest.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vrpirates.rookieonquest.data.GameData
import com.vrpirates.rookieonquest.data.MainRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

sealed class MainEvent {
    data class Uninstall(val packageName: String) : MainEvent()
}

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = MainRepository(application)
    
    private val _rawGames = MutableStateFlow<List<GameData>>(emptyList())
    
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery

    private val _events = MutableSharedFlow<MainEvent>()
    val events = _events.asSharedFlow()

    // Cache current package versions to avoid repeated PM calls during list mapping
    private val _installedPackages = MutableStateFlow<Map<String, Long>>(emptyMap())

    val games: StateFlow<List<GameItemState>> = combine(_rawGames, _searchQuery, _installedPackages) { list, query, installed ->
        val filtered = if (query.isBlank()) {
            list
        } else {
            list.filter { 
                it.gameName.contains(query, ignoreCase = true) || 
                it.packageName.contains(query, ignoreCase = true) 
            }
        }
        
        filtered.map { game ->
            val iconFile = File(repository.iconsDir, "${game.packageName}.png")
            val fallbackIcon = File(repository.iconsDir, "${game.packageName}.jpg")
            
            val installedVersion = installed[game.packageName]
            val catalogVersion = game.versionCode.toLongOrNull() ?: 0L
            
            val status = when {
                installedVersion == null -> InstallStatus.NOT_INSTALLED
                catalogVersion > installedVersion -> InstallStatus.UPDATE_AVAILABLE
                else -> InstallStatus.INSTALLED
            }
            
            GameItemState(
                name = game.gameName,
                version = game.versionCode,
                installedVersion = installedVersion?.toString(),
                packageName = game.packageName,
                releaseName = game.releaseName,
                iconFile = if (iconFile.exists()) iconFile else if (fallbackIcon.exists()) fallbackIcon else null,
                installStatus = status
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing

    private val _isInstalling = MutableStateFlow(false)
    val isInstalling: StateFlow<Boolean> = _isInstalling

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error
    
    private val _progressMessage = MutableStateFlow<String?>(null)
    val progressMessage: StateFlow<String?> = _progressMessage
    
    private var installJob: Job? = null

    init {
        refreshData()
    }

    fun refreshData() {
        viewModelScope.launch {
            _isRefreshing.value = true
            _error.value = null
            try {
                // Refresh installed packages list once on a background thread
                _installedPackages.value = repository.getInstalledPackagesMap()
                
                val config = repository.fetchConfig()
                val gameList = repository.downloadCatalog(config.baseUri)
                _rawGames.value = gameList
            } catch (e: Exception) {
                _error.value = "Error: ${e.message}"
            } finally {
                _isRefreshing.value = false
            }
        }
    }
    
    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun installGame(packageName: String) {
        val game = _rawGames.value.find { it.packageName == packageName } ?: return
        installJob?.cancel()
        installJob = viewModelScope.launch {
            try {
                _isInstalling.value = true
                repository.installGame(game) { message, progress ->
                    _progressMessage.value = "$message (${(progress * 100).toInt()}%)"
                }
                // Update UI state after installation
                refreshData()
            } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException) {
                    _error.value = "Installation error: ${e.message}"
                }
            } finally {
                _isInstalling.value = false
                _progressMessage.value = null
            }
        }
    }

    fun uninstallGame(packageName: String) {
        viewModelScope.launch {
            _events.emit(MainEvent.Uninstall(packageName))
        }
    }
    
    fun cancelInstall() {
        installJob?.cancel()
        _isInstalling.value = false
        _progressMessage.value = null
    }
}
