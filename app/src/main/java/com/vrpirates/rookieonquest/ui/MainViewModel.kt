package com.vrpirates.rookieonquest.ui

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Environment
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vrpirates.rookieonquest.data.GameData
import com.vrpirates.rookieonquest.data.MainRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.time.Duration.Companion.milliseconds

sealed class MainEvent {
    data class Uninstall(val packageName: String) : MainEvent()
    data class InstallApk(val apkFile: File) : MainEvent()
    object RequestInstallPermission : MainEvent()
    object RequestStoragePermission : MainEvent()
}

enum class RequiredPermission {
    INSTALL_UNKNOWN_APPS,
    MANAGE_EXTERNAL_STORAGE
}

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val TAG = "MainViewModel"
    private val repository = MainRepository(application)
    
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery

    private val _events = MutableSharedFlow<MainEvent>()
    val events = _events.asSharedFlow()

    private val _installedPackages = MutableStateFlow<Map<String, Long>>(emptyMap())

    private val _missingPermissions = MutableStateFlow<List<RequiredPermission>?>(null)
    val missingPermissions: StateFlow<List<RequiredPermission>?> = _missingPermissions

    private val _visibleIndices = MutableStateFlow<List<Int>>(emptyList())
    private val priorityUpdateChannel = Channel<Unit>(Channel.CONFLATED)

    private var isPermissionFlowActive = false
    private var previousMissingCount = 0
    private var refreshJob: Job? = null
    private var sizeFetchJob: Job? = null
    private var installJob: Job? = null

    // Get games directly from Room
    private val _allGames = repository.getAllGamesFlow()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val games: StateFlow<List<GameItemState>> = combine(
        _allGames, 
        _searchQuery, 
        _installedPackages
    ) { list, query, installed ->
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
                installStatus = status,
                size = game.sizeBytes?.let { formatSize(it) }
            )
        }
    }
    .flowOn(Dispatchers.Default)
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val alphabetInfo: StateFlow<Pair<List<Char>, Map<Char, Int>>> = games
        .map { list ->
            val chars = mutableSetOf<Char>()
            val charToIndex = mutableMapOf<Char, Int>()
            list.forEachIndexed { index, game ->
                val firstChar = game.name.firstOrNull()?.uppercaseChar() ?: '_'
                if (!charToIndex.containsKey(firstChar)) {
                    chars.add(firstChar)
                    charToIndex[firstChar] = index
                }
            }
            val sortedList = chars.sorted().toMutableList()
            if (sortedList.contains('_')) {
                sortedList.remove('_')
                sortedList.add(0, '_')
            }
            sortedList to charToIndex
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList<Char>() to emptyMap<Char, Int>())

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing

    private val _isInstalling = MutableStateFlow(false)
    val isInstalling: StateFlow<Boolean> = _isInstalling

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error
    
    private val _progressMessage = MutableStateFlow<String?>(null)
    val progressMessage: StateFlow<String?> = _progressMessage

    init {
        checkPermissions()
        startSizeFetchLoop()
    }

    fun setVisibleIndices(indices: List<Int>) {
        if (_visibleIndices.value != indices) {
            _visibleIndices.value = indices
            priorityUpdateChannel.trySend(Unit)
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun startSizeFetchLoop() {
        sizeFetchJob = viewModelScope.launch(Dispatchers.Default) {
            while (true) {
                val currentGames = _allGames.value
                val currentSearch = _searchQuery.value
                if (currentGames.isEmpty()) {
                    priorityUpdateChannel.receive()
                    continue
                }

                val needsSize = currentGames.filter { it.sizeBytes == null }
                if (needsSize.isEmpty()) {
                    priorityUpdateChannel.receive()
                    continue
                }

                val visible = _visibleIndices.value
                val filteredGames = games.value
                
                val prioritizedPackages = visible.mapNotNull { index ->
                    filteredGames.getOrNull(index)?.packageName
                }.toSet()

                val searchResultPackages = if (currentSearch.isNotEmpty()) {
                    filteredGames.map { it.packageName }.toSet()
                } else null

                val candidates = if (searchResultPackages != null) {
                    needsSize.filter { searchResultPackages.contains(it.packageName) }
                } else {
                    needsSize
                }

                val target = candidates.find { prioritizedPackages.contains(it.packageName) }
                    ?: candidates.firstOrNull()
                    ?: if (currentSearch.isEmpty()) needsSize.firstOrNull() else null

                if (target != null) {
                    try {
                        Log.d(TAG, "Fetching size for ${target.gameName}")
                        repository.getGameRemoteInfo(target)
                        // DB update will trigger _allGames flow update
                    } catch (e: Exception) {
                        Log.e(TAG, "Error fetching size for ${target.gameName}", e)
                        delay(2000)
                    }
                }

                select<Unit> {
                    priorityUpdateChannel.onReceive { }
                    onTimeout(100.milliseconds) { }
                }
            }
        }
    }

    fun refreshData() {
        if (refreshJob?.isActive == true) return

        refreshJob = viewModelScope.launch {
            val context = getApplication<Application>()
            val missing = withContext(Dispatchers.Default) { getMissingPermissionsList(context) }
            _missingPermissions.value = missing
            
            if (missing.isNotEmpty()) return@launch

            _isRefreshing.value = true
            _error.value = null
            try {
                withContext(Dispatchers.IO) {
                    val installed = repository.getInstalledPackagesMap()
                    _installedPackages.value = installed
                    val config = repository.fetchConfig()
                    repository.syncCatalog(config.baseUri)
                }
                priorityUpdateChannel.trySend(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "Refresh error", e)
                _error.value = "Error: ${e.message}"
            } finally {
                _isRefreshing.value = false
            }
        }
    }
    
    fun checkPermissions() {
        viewModelScope.launch {
            val context = getApplication<Application>()
            val missing = withContext(Dispatchers.Default) { getMissingPermissionsList(context) }
            
            val newCount = missing.size
            if (isPermissionFlowActive) {
                if (newCount < previousMissingCount && newCount > 0) {
                    _missingPermissions.value = missing
                    previousMissingCount = newCount
                    requestNextPermission()
                    return@launch
                } else if (newCount == 0) {
                    isPermissionFlowActive = false
                    _missingPermissions.value = emptyList()
                    refreshData() 
                    return@launch
                } else if (newCount == previousMissingCount) {
                    isPermissionFlowActive = false
                }
            }

            _missingPermissions.value = missing
            previousMissingCount = newCount
            
            if (newCount == 0 && _allGames.value.isEmpty()) {
                refreshData()
            }
        }
    }

    private fun getMissingPermissionsList(context: Context): List<RequiredPermission> {
        val missing = mutableListOf<RequiredPermission>()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (!context.packageManager.canRequestPackageInstalls()) {
                    missing.add(RequiredPermission.INSTALL_UNKNOWN_APPS)
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (!Environment.isExternalStorageManager()) {
                    missing.add(RequiredPermission.MANAGE_EXTERNAL_STORAGE)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking permissions", e)
        }
        return missing
    }
    
    fun setSearchQuery(query: String) {
        _searchQuery.value = query
        priorityUpdateChannel.trySend(Unit)
    }

    fun startPermissionFlow() {
        isPermissionFlowActive = true
        requestNextPermission()
    }

    fun requestNextPermission() {
        val next = _missingPermissions.value?.firstOrNull() ?: return
        viewModelScope.launch {
            when (next) {
                RequiredPermission.INSTALL_UNKNOWN_APPS -> _events.emit(MainEvent.RequestInstallPermission)
                RequiredPermission.MANAGE_EXTERNAL_STORAGE -> _events.emit(MainEvent.RequestStoragePermission)
            }
        }
    }

    fun installGame(packageName: String) {
        if (_missingPermissions.value?.isNotEmpty() == true) {
            startPermissionFlow()
            return
        }

        val game = _allGames.value.find { it.packageName == packageName } ?: return
        installJob?.cancel()
        installJob = viewModelScope.launch {
            try {
                _isInstalling.value = true
                val apkFile = repository.installGame(game) { message, progress, _, _ ->
                    _progressMessage.value = "$message (${(progress * 100).toInt()}%)"
                }
                if (apkFile != null) {
                    _events.emit(MainEvent.InstallApk(apkFile))
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
