package com.vrpirates.rookieonquest.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vrpirates.rookieonquest.data.GameData
import com.vrpirates.rookieonquest.data.MainRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = MainRepository(application)
    
    private val _rawGames = MutableStateFlow<List<GameData>>(emptyList())
    
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery

    // Use a simpler mapping to avoid issues with icon detection during state combination
    val games: StateFlow<List<GameItemState>> = combine(_rawGames, _searchQuery) { list, query ->
        val filtered = if (query.isBlank()) {
            list
        } else {
            list.filter { 
                it.gameName.contains(query, ignoreCase = true) || 
                it.packageName.contains(query, ignoreCase = true) 
            }
        }
        
        filtered.distinctBy { it.packageName }.map { game ->
            val iconFile = File(repository.iconsDir, "${game.packageName}.png")
            val fallbackIcon = File(repository.iconsDir, "${game.packageName}.jpg")
            
            GameItemState(
                name = game.gameName,
                version = game.versionCode,
                packageName = game.packageName,
                iconFile = if (iconFile.exists()) iconFile else if (fallbackIcon.exists()) fallbackIcon else null
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
                val config = repository.fetchConfig()
                val gameList = repository.downloadCatalog(config.baseUri)
                _rawGames.value = gameList
            } catch (e: Exception) {
                _error.value = "Erreur: ${e.message}"
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
    
    fun cancelInstall() {
        installJob?.cancel()
        _isInstalling.value = false
        _progressMessage.value = null
    }
}
