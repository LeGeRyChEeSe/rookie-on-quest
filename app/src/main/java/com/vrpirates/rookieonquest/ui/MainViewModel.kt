package com.vrpirates.rookieonquest.ui

import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vrpirates.rookieonquest.BuildConfig
import com.vrpirates.rookieonquest.data.GameData
import com.vrpirates.rookieonquest.data.MainRepository
import com.vrpirates.rookieonquest.network.GitHubRelease
import com.vrpirates.rookieonquest.network.GitHubService
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import okhttp3.OkHttpClient
import okhttp3.Request
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext
import kotlin.time.Duration.Companion.milliseconds

sealed class MainEvent {
    data class Uninstall(val packageName: String) : MainEvent()
    data class InstallApk(val apkFile: File) : MainEvent()
    object RequestInstallPermission : MainEvent()
    object RequestStoragePermission : MainEvent()
    object RequestIgnoreBatteryOptimizations : MainEvent()
    data class ShowUpdatePopup(val release: GitHubRelease) : MainEvent()
    data class ShowMessage(val message: String) : MainEvent()
    data class CopyLogs(val logs: String) : MainEvent()
}

enum class RequiredPermission {
    INSTALL_UNKNOWN_APPS,
    MANAGE_EXTERNAL_STORAGE,
    IGNORE_BATTERY_OPTIMIZATIONS
}

enum class FilterStatus {
    ALL,
    INSTALLED,
    DOWNLOADED,
    UPDATE_AVAILABLE
}

enum class InstallStatus {
    NOT_INSTALLED,
    INSTALLED,
    UPDATE_AVAILABLE
}

enum class InstallTaskStatus {
    QUEUED, DOWNLOADING, EXTRACTING, INSTALLING, PAUSED, COMPLETED, FAILED
}

fun InstallTaskStatus.isProcessing(): Boolean = 
    this == InstallTaskStatus.QUEUED ||
    this == InstallTaskStatus.DOWNLOADING || 
    this == InstallTaskStatus.EXTRACTING || 
    this == InstallTaskStatus.INSTALLING

@Immutable
data class InstallTaskState(
    val releaseName: String,
    val gameName: String,
    val packageName: String,
    val status: InstallTaskStatus = InstallTaskStatus.QUEUED,
    val progress: Float = 0f,
    val message: String? = null,
    val currentSize: String? = null,
    val totalSize: String? = null,
    val isDownloadOnly: Boolean = false,
    val totalBytes: Long = 0L,
    val error: String? = null
)

data class InstallState(
    val isInstalling: Boolean = false,
    val packageName: String? = null,
    val gameName: String? = null,
    val message: String? = null,
    val progress: Float = 0f,
    val currentSize: String? = null,
    val totalSize: String? = null
)

@Immutable
data class GameItemState(
    val name: String,
    val version: String,
    val installedVersion: String? = null,
    val packageName: String,
    val releaseName: String,
    val iconFile: File?,
    val installStatus: InstallStatus = InstallStatus.NOT_INSTALLED,
    val queueStatus: InstallTaskStatus? = null,
    val isFirstInQueue: Boolean = false,
    val isDownloaded: Boolean = false,
    val size: String? = null,
    val description: String? = null,
    val screenshotUrls: List<String>? = null
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val TAG = "MainViewModel"
    private val repository = MainRepository(application)
    private val prefs = application.getSharedPreferences("rookie_prefs", Context.MODE_PRIVATE)
    
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery

    private val _selectedFilter = MutableStateFlow(FilterStatus.ALL)
    val selectedFilter: StateFlow<FilterStatus> = _selectedFilter

    private val _events = MutableSharedFlow<MainEvent>()
    val events = _events.asSharedFlow()

    private val _installedPackages = MutableStateFlow<Map<String, Long>>(emptyMap())
    private val _downloadedReleases = MutableStateFlow<Set<String>>(emptySet())

    private val _missingPermissions = MutableStateFlow<List<RequiredPermission>?>(null)
    val missingPermissions: StateFlow<List<RequiredPermission>?> = _missingPermissions

    private val _visibleIndices = MutableStateFlow<List<Int>>(emptyList())
    private val priorityUpdateChannel = Channel<Unit>(Channel.CONFLATED)

    private val _isUpdateCheckInProgress = MutableStateFlow(true)
    val isUpdateCheckInProgress: StateFlow<Boolean> = _isUpdateCheckInProgress

    private val _isUpdateDialogShowing = MutableStateFlow(false)
    val isUpdateDialogShowing: StateFlow<Boolean> = _isUpdateDialogShowing

    private val _isUpdateDownloading = MutableStateFlow(false)
    val isUpdateDownloading: StateFlow<Boolean> = _isUpdateDownloading

    private val _updateProgress = MutableStateFlow("")
    val updateProgress: StateFlow<String> = _updateProgress

    private val _keepApks = MutableStateFlow(prefs.getBoolean("keep_apks", false))
    val keepApks: StateFlow<Boolean> = _keepApks

    private val _isAppVisible = MutableStateFlow(false)

    // Queue Management
    private val _installQueue = MutableStateFlow<List<InstallTaskState>>(emptyList())
    val installQueue: StateFlow<List<InstallTaskState>> = _installQueue

    private val _showInstallOverlay = MutableStateFlow(false)
    val showInstallOverlay: StateFlow<Boolean> = _showInstallOverlay

    private val _viewedReleaseName = MutableStateFlow<String?>(null)
    val viewedReleaseName: StateFlow<String?> = _viewedReleaseName

    private var queueProcessorJob: Job? = null
    private var currentTaskJob: Job? = null
    private var activeReleaseName: String? = null

    private var isPermissionFlowActive = false
    private var previousMissingCount = 0
    private var refreshJob: Job? = null
    private var sizeFetchJob: Job? = null

    private val githubService = Retrofit.Builder()
        .baseUrl("https://api.github.com/")
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(GitHubService::class.java)

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val _allGames = repository.getAllGamesFlow()
        .map { list ->
            list.sortedWith(compareBy<GameData> {
                val c = it.gameName.firstOrNull() ?: ' '
                when {
                    c == '_' -> 0
                    c.isDigit() -> 1
                    else -> 2
                }
            }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.gameName })
        }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val filterCounts: StateFlow<Map<FilterStatus, Int>> = combine(
        _allGames,
        _installedPackages,
        _downloadedReleases
    ) { list, installed, downloaded ->
        val counts = mutableMapOf<FilterStatus, Int>()
        counts[FilterStatus.ALL] = list.size
        
        var installedCount = 0
        var updateCount = 0
        var downloadedCount = 0
        
        list.forEach { game ->
            val installedVersion = installed[game.packageName]
            val catalogVersion = game.versionCode.toLongOrNull() ?: 0L
            
            if (installedVersion != null) {
                installedCount++
                if (catalogVersion > installedVersion) {
                    updateCount++
                }
            }
            
            if (downloaded.contains(game.releaseName)) {
                downloadedCount++
            }
        }
        
        counts[FilterStatus.INSTALLED] = installedCount
        counts[FilterStatus.DOWNLOADED] = downloadedCount
        counts[FilterStatus.UPDATE_AVAILABLE] = updateCount
        counts
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    @Suppress("UNCHECKED_CAST")
    val games: StateFlow<List<GameItemState>> = combine(
        _allGames, 
        _searchQuery, 
        _installedPackages,
        _downloadedReleases,
        _selectedFilter,
        _installQueue
    ) { args ->
        val list = args[0] as List<GameData>
        val query = args[1] as String
        val installed = args[2] as Map<String, Long>
        val downloaded = args[3] as Set<String>
        val filter = args[4] as FilterStatus
        val queue = args[5] as List<InstallTaskState>

        val firstInQueue = queue.firstOrNull()?.releaseName

        val filteredByQuery = if (query.isBlank()) {
            list
        } else {
            list.filter { 
                it.gameName.contains(query, ignoreCase = true) || 
                it.packageName.contains(query, ignoreCase = true) 
            }
        }
        
        filteredByQuery.mapNotNull { game ->
            val installedVersion = installed[game.packageName]
            val catalogVersion = game.versionCode.toLongOrNull() ?: 0L
            
            val status = when {
                installedVersion == null -> InstallStatus.NOT_INSTALLED
                catalogVersion > installedVersion -> InstallStatus.UPDATE_AVAILABLE
                else -> InstallStatus.INSTALLED
            }
            
            val isDownloaded = downloaded.contains(game.releaseName)
            val queueTask = queue.find { it.releaseName == game.releaseName }

            when (filter) {
                FilterStatus.INSTALLED -> if (status == InstallStatus.NOT_INSTALLED) return@mapNotNull null
                FilterStatus.DOWNLOADED -> if (!isDownloaded) return@mapNotNull null
                FilterStatus.UPDATE_AVAILABLE -> if (status != InstallStatus.UPDATE_AVAILABLE) return@mapNotNull null
                FilterStatus.ALL -> {}
            }

            val iconLocations = listOf(
                File(repository.iconsDir, "${game.packageName}.png"),
                File(repository.iconsDir, "${game.packageName}.jpg"),
                File(repository.thumbnailsDir, "${game.packageName}.png"),
                File(repository.thumbnailsDir, "${game.packageName}.jpg"),
                File(repository.thumbnailsDir, "${game.packageName}.jpeg")
            )
            
            val iconFile = iconLocations.find { it.exists() }
            
            GameItemState(
                name = game.gameName,
                version = game.versionCode,
                installedVersion = installedVersion?.toString(),
                packageName = game.packageName,
                releaseName = game.releaseName,
                iconFile = iconFile,
                installStatus = status,
                queueStatus = queueTask?.status,
                isFirstInQueue = game.releaseName == firstInQueue,
                isDownloaded = isDownloaded,
                size = if (game.sizeBytes != null && game.sizeBytes > 0) formatSize(game.sizeBytes) else if (game.sizeBytes == -1L) "Error" else null,
                description = game.description,
                screenshotUrls = game.screenshotUrls
            )
        }
    }
    .flowOn(Dispatchers.Default)
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val alphabetInfo: StateFlow<Pair<List<Char>, Map<Char, Int>>> = games
        .map { list ->
            val chars = mutableListOf<Char>()
            val charToIndex = mutableMapOf<Char, Int>()
            list.forEachIndexed { index, game ->
                val firstChar = game.name.trim().firstOrNull()?.uppercaseChar() ?: '_'
                val mappedChar = when {
                    firstChar == '_' -> '_'
                    firstChar.isDigit() -> '#' 
                    else -> firstChar
                }
                if (!charToIndex.containsKey(mappedChar)) {
                    chars.add(mappedChar)
                    charToIndex[mappedChar] = index
                }
            }
            chars to charToIndex
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList<Char>() to emptyMap<Char, Int>())

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    // Composite Install State for legacy UI support
    val installState: StateFlow<InstallState> = _installQueue.map { queue ->
        val active = queue.find { it.status.isProcessing() }
        if (active != null) {
            InstallState(
                isInstalling = true,
                packageName = active.packageName,
                gameName = active.gameName,
                message = active.message,
                progress = active.progress,
                currentSize = active.currentSize,
                totalSize = active.totalSize
            )
        } else {
            InstallState(isInstalling = false)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), InstallState())
    
    init {
        viewModelScope.launch {
            _isUpdateCheckInProgress.value = true
            try {
                val updateAvailable = checkForAppUpdates()
                if (!updateAvailable) {
                    checkPermissions()
                    refreshInstalledPackages()
                    refreshDownloadedReleases()
                    startMetadataFetchLoop()
                    repository.verifyAndCleanupInstalls()
                }
            } finally {
                _isUpdateCheckInProgress.value = false
            }
        }

        // Auto-refresh downloaded and installed status when catalog is loaded or changes
        viewModelScope.launch {
            _allGames.collect { games ->
                if (games.isNotEmpty()) {
                    refreshDownloadedReleases(games)
                    refreshInstalledPackages()
                }
            }
        }
    }

    fun refreshInstalledPackages() {
        viewModelScope.launch {
            try {
                val installed = withContext(Dispatchers.IO) {
                    repository.getInstalledPackagesMap()
                }
                _installedPackages.value = installed
            } catch (e: Exception) {
                Log.e(TAG, "Failed to refresh installed packages", e)
            }
        }
    }

    fun refreshDownloadedReleases(gamesOverride: List<GameData>? = null) {
        viewModelScope.launch {
            try {
                val downloaded = withContext(Dispatchers.IO) {
                    val dir = repository.downloadsDir
                    if (!dir.exists()) return@withContext emptySet<String>()
                    
                    val games = gamesOverride ?: _allGames.value
                    if (games.isEmpty()) return@withContext emptySet<String>()

                    val releaseNamesWithFolders = dir.listFiles()?.filter { it.isDirectory }?.map { it.name }?.toSet() ?: emptySet()
                    
                    games.filter { game ->
                        val safeDirName = game.releaseName.replace(Regex("[^a-zA-Z0-9.-]"), "_")
                        releaseNamesWithFolders.contains(safeDirName)
                    }.map { it.releaseName }.toSet()
                }
                _downloadedReleases.value = downloaded
            } catch (e: Exception) {
                Log.e(TAG, "Failed to refresh downloaded releases", e)
            }
        }
    }

    private suspend fun checkForAppUpdates(): Boolean {
        return try {
            val latest = githubService.getLatestRelease()
            val currentVersion = BuildConfig.VERSION_NAME
            
            val latestClean = latest.tagName.lowercase().removePrefix("v")
            val currentClean = currentVersion.lowercase().removePrefix("v")
            
            if (isVersionNewer(latestClean, currentClean)) {
                _isUpdateDialogShowing.value = true
                _events.emit(MainEvent.ShowUpdatePopup(latest))
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to check for updates: ${e.message}")
            false
        }
    }

    private fun isVersionNewer(latest: String, current: String): Boolean {
        val latestParts = latest.split('.').mapNotNull { it.filter { c -> c.isDigit() }.toIntOrNull() }
        val currentParts = current.split('.').mapNotNull { it.filter { c -> c.isDigit() }.toIntOrNull() }
        
        val maxLength = maxOf(latestParts.size, currentParts.size)
        for (i in 0 until maxLength) {
            val latestPart = latestParts.getOrElse(i) { 0 }
            val currentPart = currentParts.getOrElse(i) { 0 }
            if (latestPart > currentPart) return true
            if (latestPart < currentPart) return false
        }
        return false
    }

    fun downloadAndInstallUpdate(release: GitHubRelease) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _isUpdateDownloading.value = true
                _isUpdateDialogShowing.value = false
                
                val apkAsset = release.assets.find { it.name.endsWith(".apk", true) }
                    ?: throw Exception("No APK found in release assets")
                
                val context = getApplication<Application>()
                val targetFile = File(context.getExternalFilesDir(null), "rookie_update.apk")
                
                val request = Request.Builder().url(apkAsset.downloadUrl).build()
                okHttpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw Exception("HTTP ${response.code}")
                    val totalSize = response.body?.contentLength() ?: -1L
                    var downloaded = 0L
                    
                    response.body?.byteStream()?.use { input ->
                        targetFile.outputStream().use { output ->
                            val buffer = ByteArray(8192 * 8)
                            var bytesRead: Int
                            while (input.read(buffer).also { bytesRead = it } != -1) {
                                output.write(buffer, 0, bytesRead)
                                downloaded += bytesRead
                                if (totalSize > 0) {
                                    _updateProgress.value = "Downloading update: ${(downloaded * 100 / totalSize)}%"
                                } else {
                                    _updateProgress.value = "Downloading update..."
                                }
                            }
                        }
                    }
                }
                
                _updateProgress.value = "Launching installer..."
                withContext(Dispatchers.Main) {
                    if (!_isAppVisible.value) {
                        _updateProgress.value = "Update ready. Please return to the app."
                        _isAppVisible.first { it }
                    }
                    _events.emit(MainEvent.InstallApk(targetFile))
                    _isUpdateDownloading.value = false
                }
            } catch (e: Exception) {
                Log.e(TAG, "Update error", e)
                _error.value = "Update failed: ${e.message}"
                _isUpdateDownloading.value = false
                onUpdateDialogDismissed()
            }
        }
    }

    fun onUpdateDialogDismissed() {
        if (_isUpdateDownloading.value) return
        _isUpdateDialogShowing.value = false
        checkPermissions()
        refreshInstalledPackages()
        refreshDownloadedReleases()
        startMetadataFetchLoop()
    }

    fun setVisibleIndices(indices: List<Int>) {
        if (_visibleIndices.value != indices) {
            _visibleIndices.value = indices
            priorityUpdateChannel.trySend(Unit)
        }
    }

    fun setAppVisibility(visible: Boolean) {
        if (_isAppVisible.value != visible) {
            _isAppVisible.value = visible
            if (visible) {
                priorityUpdateChannel.trySend(Unit)
                refreshInstalledPackages() 
                refreshDownloadedReleases()
                viewModelScope.launch {
                    repository.verifyAndCleanupInstalls()
                }
            }
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun startMetadataFetchLoop() {
        sizeFetchJob = viewModelScope.launch(Dispatchers.Default) {
            while (true) {
                if (!_isAppVisible.value) {
                    _isAppVisible.first { it }
                }

                val currentGames = _allGames.value
                val currentSearch = _searchQuery.value
                if (currentGames.isEmpty()) {
                    priorityUpdateChannel.receive()
                    continue
                }

                val needsData = currentGames.filter { it.sizeBytes == null || (it.description == null && it.screenshotUrls == null) }
                if (needsData.isEmpty()) {
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
                    needsData.filter { searchResultPackages.contains(it.packageName) }
                } else {
                    needsData
                }

                val target = candidates.find { prioritizedPackages.contains(it.packageName) }

                if (target != null) {
                    try {
                        repository.getGameRemoteInfo(target)
                    } catch (e: Exception) {
                        delay(2000)
                    }
                    
                    select<Unit> {
                        priorityUpdateChannel.onReceive { }
                        onTimeout(100.milliseconds) { }
                    }
                } else {
                    priorityUpdateChannel.receive()
                }
            }
        }
    }

    fun refreshData() {
        if (refreshJob?.isActive == true) return
        if (_isUpdateDialogShowing.value || _isUpdateDownloading.value) return

        refreshJob = viewModelScope.launch {
            val context = getApplication<Application>()
            val missing = withContext(Dispatchers.Default) { getMissingPermissionsList(context) }
            _missingPermissions.value = missing
            
            if (missing.isNotEmpty()) return@launch

            _isRefreshing.value = true
            _error.value = null
            try {
                withContext(Dispatchers.IO) {
                    // 1. Sync Catalog first to have the latest games list
                    val config = repository.fetchConfig()
                    repository.syncCatalog(config.baseUri)
                    
                    // 2. Refresh statuses now that we have the latest catalog
                    // We get the fresh games list directly to be immediate
                    val freshGames = repository.getAllGamesFlow().first()
                    
                    val installed = repository.getInstalledPackagesMap()
                    _installedPackages.value = installed
                    refreshDownloadedReleases(freshGames)
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
        if (_isUpdateDialogShowing.value || _isUpdateDownloading.value) return

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
            
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!powerManager.isIgnoringBatteryOptimizations(context.packageName)) {
                missing.add(RequiredPermission.IGNORE_BATTERY_OPTIMIZATIONS)
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

    fun setFilter(filter: FilterStatus) {
        _selectedFilter.value = filter
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
                RequiredPermission.IGNORE_BATTERY_OPTIMIZATIONS -> _events.emit(MainEvent.RequestIgnoreBatteryOptimizations)
            }
        }
    }

    // Queue Management Methods
    fun installGame(releaseName: String, downloadOnly: Boolean = false) {
        if (_missingPermissions.value?.isNotEmpty() == true) {
            startPermissionFlow()
            return
        }

        if (releaseName.isEmpty()) {
            showOverlay()
            return
        }

        val game = _allGames.value.find { it.releaseName == releaseName } ?: return
        
        val existingTask = _installQueue.value.find { it.releaseName == releaseName }
        if (existingTask != null) {
            val isFirst = _installQueue.value.firstOrNull()?.releaseName == releaseName
            if (existingTask.status == InstallTaskStatus.PAUSED && isFirst) {
                resumeInstall(releaseName)
            } else {
                viewModelScope.launch {
                    _events.emit(MainEvent.ShowMessage("${game.gameName} is already in the queue"))
                }
            }
            return
        }

        val newTask = InstallTaskState(
            releaseName = game.releaseName,
            gameName = game.gameName,
            packageName = game.packageName,
            status = InstallTaskStatus.QUEUED,
            isDownloadOnly = downloadOnly
        )

        val isShowing = _showInstallOverlay.value
        val alreadyProcessing = _installQueue.value.any { it.status.isProcessing() }
        
        _installQueue.update { it + newTask }
        
        if (!isShowing && !alreadyProcessing) {
            _viewedReleaseName.value = releaseName
            _showInstallOverlay.value = true
        } else {
            viewModelScope.launch {
                _events.emit(MainEvent.ShowMessage("${game.gameName} added to queue"))
            }
        }
        
        startQueueProcessor()
    }

    fun showOverlay() {
        val currentQueue = _installQueue.value
        if (currentQueue.isEmpty()) return

        val taskToView = _viewedReleaseName.value?.let { v -> currentQueue.find { it.releaseName == v } }?.releaseName
            ?: currentQueue.find { it.status.isProcessing() }?.releaseName 
            ?: currentQueue.firstOrNull()?.releaseName
            
        if (taskToView != null) {
            _viewedReleaseName.value = taskToView
            _showInstallOverlay.value = true
        }
    }

    private fun startQueueProcessor() {
        if (queueProcessorJob?.isActive == true) return
        
        queueProcessorJob = viewModelScope.launch {
            try {
                while (isActive) {
                    val nextTask = _installQueue.value.find { it.status == InstallTaskStatus.QUEUED }
                    if (nextTask == null) {
                        if (_installQueue.value.none { it.status == InstallTaskStatus.QUEUED }) {
                            break 
                        }
                        delay(1000)
                        continue
                    }

                    runTask(nextTask)
                }
            } finally {
                queueProcessorJob = null
            }
        }
    }

    private suspend fun runTask(task: InstallTaskState) {
        val game = _allGames.value.find { it.releaseName == task.releaseName } ?: return
        
        // Double check status hasn't changed to PAUSED before we start
        val latestTask = _installQueue.value.find { it.releaseName == task.releaseName }
        if (latestTask == null || latestTask.status != InstallTaskStatus.QUEUED) return

        // Auto-switch view if nothing is being viewed or the viewed task is not active
        val currentViewed = _installQueue.value.find { it.releaseName == _viewedReleaseName.value }
        if (currentViewed == null || !currentViewed.status.isProcessing()) {
            _viewedReleaseName.value = task.releaseName
        }

        // Initialize active task state before updating status to ensure UI pause/cancel works immediately
        activeReleaseName = task.releaseName
        val taskJob = SupervisorJob(coroutineContext[Job])
        currentTaskJob = taskJob

        updateTaskStatus(task.releaseName, InstallTaskStatus.DOWNLOADING)
        
        try {
            withContext(taskJob + Dispatchers.IO) {
                val apkFile = repository.installGame(
                    game = game,
                    keepApk = _keepApks.value,
                    downloadOnly = task.isDownloadOnly
                ) { message, progress, current, total ->
                    updateTaskProgress(task.releaseName, message, progress, current, total)
                }
                
                updateTaskStatus(task.releaseName, InstallTaskStatus.COMPLETED)
                refreshDownloadedReleases()

                if (apkFile != null && !task.isDownloadOnly) {
                    withContext(Dispatchers.Main) {
                        if (!_isAppVisible.value) {
                            _isAppVisible.first { it }
                        }
                        _events.emit(MainEvent.InstallApk(apkFile))
                    }
                }
            }
            
            delay(2000)
            _installQueue.update { list -> list.filter { it.releaseName != task.releaseName } }
            
            if (_installQueue.value.isEmpty()) {
                _showInstallOverlay.value = false
                _viewedReleaseName.value = null
            } else if (_viewedReleaseName.value == task.releaseName) {
                val remaining = _installQueue.value
                val nextActive = remaining.find { it.status.isProcessing() }
                _viewedReleaseName.value = nextActive?.releaseName ?: remaining.firstOrNull()?.releaseName
            }

        } catch (e: Exception) {
            if (e is CancellationException) {
                Log.d(TAG, "Task cancelled/paused: ${task.gameName}")
                // Do not remove from queue, status is already set to PAUSED by pauseInstall()
            } else {
                val errorMessage = e.message ?: "Unknown error"
                Log.e(TAG, "Installation failed for ${task.gameName}", e)
                
                // Ensure the status is updated to FAILED so the UI shows it
                updateTaskStatus(task.releaseName, InstallTaskStatus.FAILED, errorMessage)
                
                // Specific handling for storage space to pause other tasks if needed or show prominent popup
                if (errorMessage.contains("Insufficient storage space", ignoreCase = true)) {
                    _events.emit(MainEvent.ShowMessage("STORAGE ERROR: $errorMessage"))
                } else {
                    _events.emit(MainEvent.ShowMessage("Failed to install ${task.gameName}: $errorMessage"))
                }
            }
        } finally {
            // Only clear shared state if it still belongs to this task (prevents race conditions with promoted tasks)
            if (activeReleaseName == task.releaseName) {
                activeReleaseName = null
                currentTaskJob = null
            }
            taskJob.cancel() 
        }
    }

    private fun updateTaskStatus(releaseName: String, status: InstallTaskStatus, error: String? = null) {
        _installQueue.update { list ->
            list.map { 
                if (it.releaseName == releaseName) it.copy(status = status, error = error) else it 
            }
        }
    }

    private fun updateTaskProgress(releaseName: String, message: String, progress: Float, current: Long, total: Long) {
        _installQueue.update { list ->
            list.map { 
                if (it.releaseName == releaseName) {
                    it.copy(
                        message = message, 
                        progress = progress, 
                        currentSize = if (total > 0) formatSize(current) else null,
                        totalSize = if (total > 0) formatSize(total) else null,
                        totalBytes = total,
                        error = null // Clear error if we are progressing
                    )
                } else it 
            }
        }
    }

    fun pauseInstall(releaseName: String) {
        val task = _installQueue.value.find { it.releaseName == releaseName } ?: return
        if (task.status.isProcessing() || task.status == InstallTaskStatus.QUEUED) {
            updateTaskStatus(releaseName, InstallTaskStatus.PAUSED)
            if (releaseName == activeReleaseName) {
                currentTaskJob?.cancel()
            }
        }
    }

    fun resumeInstall(releaseName: String) {
        // Only allow resume if it's the first in queue
        val isFirst = _installQueue.value.firstOrNull()?.releaseName == releaseName
        if (isFirst) {
            _viewedReleaseName.value = releaseName
            updateTaskStatus(releaseName, InstallTaskStatus.QUEUED)
            startQueueProcessor()
        }
    }

    fun cancelInstall(releaseName: String) {
        val task = _installQueue.value.find { it.releaseName == releaseName } ?: return
        
        if (releaseName == activeReleaseName) {
            currentTaskJob?.cancel()
        }

        viewModelScope.launch(Dispatchers.IO) {
            repository.cleanupInstall(releaseName, task.totalBytes)
        }

        _installQueue.update { list -> list.filter { it.releaseName != releaseName } }
        
        val remaining = _installQueue.value
        if (_viewedReleaseName.value == releaseName) {
            _viewedReleaseName.value = remaining.find { it.status.isProcessing() }?.releaseName
                ?: remaining.firstOrNull()?.releaseName
        }

        if (remaining.isEmpty()) {
            _showInstallOverlay.value = false
            _viewedReleaseName.value = null
        } else {
            // Restart processor just in case it stopped
            startQueueProcessor()
        }
    }

    fun promoteTask(releaseName: String) {
        val currentQueue = _installQueue.value
        val task = currentQueue.find { it.releaseName == releaseName } ?: return
        
        if (task.status == InstallTaskStatus.QUEUED || task.status == InstallTaskStatus.PAUSED || task.status == InstallTaskStatus.FAILED) {
            // Pause current processing task if any
            val previousActive = activeReleaseName
            if (previousActive != null && previousActive != releaseName) {
                updateTaskStatus(previousActive, InstallTaskStatus.PAUSED)
                currentTaskJob?.cancel()
            }
            
            // Reorder queue: Put promoted task at the very top
            _installQueue.update { list ->
                val filtered = list.filter { it.releaseName != releaseName }
                listOf(task.copy(status = InstallTaskStatus.QUEUED)) + filtered
            }
            
            _viewedReleaseName.value = releaseName
            
            // Restart processor to pick up the new top task
            queueProcessorJob?.cancel()
            queueProcessorJob = null
            startQueueProcessor()
        }
    }

    fun setFocusedTask(releaseName: String) {
        _viewedReleaseName.value = releaseName
        _showInstallOverlay.value = true
    }

    fun hideInstallOverlay() {
        _showInstallOverlay.value = false
    }

    fun toggleKeepApks() {
        val newValue = !_keepApks.value
        _keepApks.value = newValue
        prefs.edit().putBoolean("keep_apks", newValue).apply()
    }

    fun uninstallGame(packageName: String) {
        viewModelScope.launch {
            _events.emit(MainEvent.Uninstall(packageName))
        }
    }

    fun deleteDownloadedGame(releaseName: String) {
        viewModelScope.launch {
            try {
                repository.deleteDownloadedGame(releaseName)
                refreshDownloadedReleases()
                _events.emit(MainEvent.ShowMessage("Download deleted"))
            } catch (e: Exception) {
                _events.emit(MainEvent.ShowMessage("Failed to delete download: ${e.message}"))
            }
        }
    }

    fun clearCache() {
        viewModelScope.launch {
            try {
                val freed = repository.clearCache()
                _events.emit(MainEvent.ShowMessage("Cache cleared, freed ${formatSize(freed)}"))
            } catch (e: Exception) {
                _events.emit(MainEvent.ShowMessage("Failed to clear cache: ${e.message}"))
            }
        }
    }

    fun exportDiagnostics(toFile: Boolean = false) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _events.emit(MainEvent.ShowMessage("Collecting logs..."))
                val process = Runtime.getRuntime().exec("logcat -d")
                val logs = process.inputStream.bufferedReader().use { it.readText() }
                
                if (toFile) {
                    val fileName = repository.saveLogs(logs)
                    _events.emit(MainEvent.ShowMessage("Logs saved to Download/RookieOnQuest/$fileName"))
                } else {
                    withContext(Dispatchers.Main) {
                        _events.emit(MainEvent.CopyLogs(logs))
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to export logs", e)
                _events.emit(MainEvent.ShowMessage("Failed to collect logs: ${e.message}"))
            }
        }
    }

    private fun formatSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
        return String.format(Locale.US, "%.1f %s", bytes / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
    }
}
