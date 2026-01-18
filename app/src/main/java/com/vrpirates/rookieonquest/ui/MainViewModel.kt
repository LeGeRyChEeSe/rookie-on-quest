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
import kotlinx.coroutines.NonCancellable
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
    FAVORITES,
    INSTALLED,
    DOWNLOADED,
    UPDATE_AVAILABLE,
    NEW
}

enum class SortMode {
    NAME_ASC,
    NAME_DESC,
    LAST_UPDATED,
    SIZE,
    POPULARITY
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

/**
 * Maps a game name's first character to its alphabet group character.
 * Used for both sorting order and alphabet navigation to ensure consistency.
 *
 * Rules:
 * - '_' prefix → '_' group (comes first in sort)
 * - Digits (0-9) → '#' group (comes second in sort)
 * - Letters → uppercase letter group
 * - Empty/null → '_' group
 *
 * @param gameName The full game name to extract the group character from
 * @return The mapped character for alphabet grouping
 */
fun getAlphabetGroupChar(gameName: String): Char {
    val firstChar = gameName.trim().firstOrNull()?.uppercaseChar() ?: '_'
    return when {
        firstChar == '_' -> '_'
        firstChar.isDigit() -> '#'
        else -> firstChar
    }
}

/**
 * Returns the sort priority for alphabet groups.
 * Lower values sort first.
 *
 * @param groupChar The alphabet group character from getAlphabetGroupChar()
 * @return Sort priority (0 = '_', 1 = '#', 2 = letters)
 */
fun getAlphabetSortPriority(groupChar: Char): Int = when (groupChar) {
    '_' -> 0
    '#' -> 1
    else -> 2
}

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
    val downloadedBytes: Long? = null,
    val error: String? = null
)

/**
 * Maps Room InstallStatus (from data layer) to UI InstallTaskStatus
 */
fun com.vrpirates.rookieonquest.data.InstallStatus.toTaskStatus(): InstallTaskStatus {
    return when (this) {
        com.vrpirates.rookieonquest.data.InstallStatus.QUEUED -> InstallTaskStatus.QUEUED
        com.vrpirates.rookieonquest.data.InstallStatus.DOWNLOADING -> InstallTaskStatus.DOWNLOADING
        com.vrpirates.rookieonquest.data.InstallStatus.EXTRACTING -> InstallTaskStatus.EXTRACTING
        com.vrpirates.rookieonquest.data.InstallStatus.COPYING_OBB -> InstallTaskStatus.INSTALLING // COPYING_OBB is part of install phase, map to INSTALLING
        com.vrpirates.rookieonquest.data.InstallStatus.INSTALLING -> InstallTaskStatus.INSTALLING
        com.vrpirates.rookieonquest.data.InstallStatus.PAUSED -> InstallTaskStatus.PAUSED
        com.vrpirates.rookieonquest.data.InstallStatus.COMPLETED -> InstallTaskStatus.COMPLETED
        com.vrpirates.rookieonquest.data.InstallStatus.FAILED -> InstallTaskStatus.FAILED
    }
}

/**
 * Maps UI InstallTaskStatus to Room InstallStatus (for saving to database)
 */
fun InstallTaskStatus.toDataStatus(): com.vrpirates.rookieonquest.data.InstallStatus {
    return when (this) {
        InstallTaskStatus.QUEUED -> com.vrpirates.rookieonquest.data.InstallStatus.QUEUED
        InstallTaskStatus.DOWNLOADING -> com.vrpirates.rookieonquest.data.InstallStatus.DOWNLOADING
        InstallTaskStatus.EXTRACTING -> com.vrpirates.rookieonquest.data.InstallStatus.EXTRACTING
        InstallTaskStatus.INSTALLING -> com.vrpirates.rookieonquest.data.InstallStatus.INSTALLING
        InstallTaskStatus.PAUSED -> com.vrpirates.rookieonquest.data.InstallStatus.PAUSED
        InstallTaskStatus.COMPLETED -> com.vrpirates.rookieonquest.data.InstallStatus.COMPLETED
        InstallTaskStatus.FAILED -> com.vrpirates.rookieonquest.data.InstallStatus.FAILED
    }
}

/**
 * Converts QueuedInstallEntity from Room to UI InstallTaskState
 * Uses pre-fetched game metadata from cache to avoid N+1 queries
 *
 * @param gameDataCache Pre-loaded map of releaseName -> GameData (from batch query)
 */
fun com.vrpirates.rookieonquest.data.QueuedInstallEntity.toInstallTaskState(
    gameDataCache: Map<String, com.vrpirates.rookieonquest.data.GameData>
): InstallTaskState {
    val gameData = gameDataCache[releaseName]
    val statusEnum = statusEnum.toTaskStatus()

    // Generate status message based on current state
    val message = when (statusEnum) {
        InstallTaskStatus.QUEUED -> "Queued"
        InstallTaskStatus.DOWNLOADING -> "Downloading..."
        InstallTaskStatus.EXTRACTING -> "Extracting..."
        InstallTaskStatus.INSTALLING -> "Installing..."
        InstallTaskStatus.PAUSED -> "Paused"
        InstallTaskStatus.COMPLETED -> "Completed"
        InstallTaskStatus.FAILED -> "Failed"
    }

    // Format size strings
    val currentSize = downloadedBytes?.let { formatBytes(it) }
    val totalSizeStr = totalBytes?.let { formatBytes(it) }

    return InstallTaskState(
        releaseName = releaseName,
        gameName = gameData?.gameName ?: releaseName,
        packageName = gameData?.packageName ?: "",
        status = statusEnum,
        progress = progress,
        message = message,
        currentSize = currentSize,
        totalSize = totalSizeStr,
        isDownloadOnly = isDownloadOnly,
        totalBytes = totalBytes ?: 0L,
        downloadedBytes = downloadedBytes,
        error = null // Error state is transient, not persisted
    )
}

/**
 * Format bytes to human-readable string
 */
private fun formatBytes(bytes: Long): String {
    val kb = 1024L
    val mb = kb * 1024
    val gb = mb * 1024

    return when {
        bytes >= gb -> "%.2f GB".format(bytes / gb.toDouble())
        bytes >= mb -> "%.2f MB".format(bytes / mb.toDouble())
        bytes >= kb -> "%.2f KB".format(bytes / kb.toDouble())
        else -> "$bytes B"
    }
}

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
    val isFavorite: Boolean = false,
    val size: String? = null,
    val sizeBytes: Long = 0L,
    val description: String? = null,
    val screenshotUrls: List<String>? = null,
    val lastUpdated: Long = 0L,
    val popularity: Int = 0
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val TAG = "MainViewModel"
    private val repository = MainRepository(application)
    private val prefs = application.getSharedPreferences(com.vrpirates.rookieonquest.data.Constants.PREFS_NAME, Context.MODE_PRIVATE)
    
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery

    private val _selectedFilter = MutableStateFlow(FilterStatus.ALL)
    val selectedFilter: StateFlow<FilterStatus> = _selectedFilter

    private val _sortMode = MutableStateFlow(SortMode.NAME_ASC)
    val sortMode: StateFlow<SortMode> = _sortMode

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

    // Queue Management (v2.5.0 - Room DB as source of truth)
    // Single StateFlow derived from Room DB - installQueue IS the source of truth for UI
    // Internal code uses installQueue.value for reads; Room DB updates propagate automatically
    // OPTIMIZATION: Uses batch query to fetch all game metadata in 1 DB call instead of N+1
    @OptIn(ExperimentalCoroutinesApi::class)
    val installQueue: StateFlow<List<InstallTaskState>> = repository.getAllQueuedInstalls()
        .flatMapLatest { entities ->
            flow {
                if (entities.isEmpty()) {
                    emit(emptyList())
                    return@flow
                }

                // BATCH QUERY: Fetch all game metadata in a single DB call (avoids N+1 problem)
                val releaseNames = entities.map { it.releaseName }
                val gameDataCache = repository.getGamesByReleaseNames(releaseNames)

                // Convert entities using cached metadata (O(1) lookup per entity)
                val tasks = entities.mapNotNull { entity ->
                    runCatching {
                        entity.toInstallTaskState(gameDataCache)
                    }.getOrElse {
                        Log.e(TAG, "Failed to convert entity to task state: ${entity.releaseName}", it)
                        null
                    }
                }
                emit(tasks)
            }
        }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

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
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val filterCounts: StateFlow<Map<FilterStatus, Int>> = combine(
        _allGames,
        _installedPackages,
        _downloadedReleases
    ) { list, installed, downloaded ->
        val counts = mutableMapOf<FilterStatus, Int>()
        counts[FilterStatus.ALL] = list.size
        
        var favoriteCount = 0
        var installedCount = 0
        var updateCount = 0
        var downloadedCount = 0
        var newCount = 0
        
        val lastSync = prefs.getLong("last_catalog_sync_time", 0L)

        list.forEach { game ->
            val installedVersion = installed[game.packageName]
            val catalogVersion = game.versionCode.toLongOrNull() ?: 0L
            
            if (game.isFavorite) {
                favoriteCount++
            }
            
            if (installedVersion != null) {
                installedCount++
                if (catalogVersion > installedVersion) {
                    updateCount++
                }
            }
            
            if (downloaded.contains(game.releaseName)) {
                downloadedCount++
            }

            if (game.lastUpdated > lastSync && lastSync != 0L) {
                newCount++
            }
        }
        
        counts[FilterStatus.FAVORITES] = favoriteCount
        counts[FilterStatus.INSTALLED] = installedCount
        counts[FilterStatus.DOWNLOADED] = downloadedCount
        counts[FilterStatus.UPDATE_AVAILABLE] = updateCount
        counts[FilterStatus.NEW] = newCount
        counts
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    @Suppress("UNCHECKED_CAST")
    val games: StateFlow<List<GameItemState>> = combine(
        _allGames, 
        _searchQuery, 
        _installedPackages,
        _downloadedReleases,
        _selectedFilter,
        _sortMode,
        installQueue
    ) { args ->
        val list = args[0] as List<GameData>
        val query = args[1] as String
        val installed = args[2] as Map<String, Long>
        val downloaded = args[3] as Set<String>
        val filter = args[4] as FilterStatus
        val sort = args[5] as SortMode
        val queue = args[6] as List<InstallTaskState>

        val firstInQueue = queue.firstOrNull()?.releaseName
        val lastSync = prefs.getLong("last_catalog_sync_time", 0L)

        val filteredList = list.filter { game ->
            val installedVersion = installed[game.packageName]
            val catalogVersion = game.versionCode.toLongOrNull() ?: 0L
            val status = when {
                installedVersion == null -> InstallStatus.NOT_INSTALLED
                catalogVersion > installedVersion -> InstallStatus.UPDATE_AVAILABLE
                else -> InstallStatus.INSTALLED
            }
            val isDownloaded = downloaded.contains(game.releaseName)

            val matchesQuery = query.isBlank() || 
                game.gameName.contains(query, ignoreCase = true) || 
                game.packageName.contains(query, ignoreCase = true)

            val matchesFilter = when (filter) {
                FilterStatus.FAVORITES -> game.isFavorite
                FilterStatus.INSTALLED -> status != InstallStatus.NOT_INSTALLED
                FilterStatus.DOWNLOADED -> isDownloaded
                FilterStatus.UPDATE_AVAILABLE -> status == InstallStatus.UPDATE_AVAILABLE
                FilterStatus.NEW -> game.lastUpdated > lastSync && lastSync != 0L
                FilterStatus.ALL -> true
            }

            matchesQuery && matchesFilter
        }

        val sortedList = when (sort) {
            SortMode.NAME_ASC -> filteredList.sortedWith(compareBy<GameData> {
                getAlphabetSortPriority(getAlphabetGroupChar(it.gameName))
            }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.gameName })
            SortMode.NAME_DESC -> filteredList.sortedByDescending { it.gameName }
            SortMode.LAST_UPDATED -> filteredList.sortedByDescending { it.lastUpdated }
            SortMode.SIZE -> filteredList.sortedByDescending { it.sizeBytes ?: 0L }
            SortMode.POPULARITY -> filteredList.sortedByDescending { it.popularity }
        }
        
        sortedList.map { game ->
            val installedVersion = installed[game.packageName]
            val catalogVersion = game.versionCode.toLongOrNull() ?: 0L
            
            val status = when {
                installedVersion == null -> InstallStatus.NOT_INSTALLED
                catalogVersion > installedVersion -> InstallStatus.UPDATE_AVAILABLE
                else -> InstallStatus.INSTALLED
            }
            
            val isDownloaded = downloaded.contains(game.releaseName)
            val queueTask = queue.find { it.releaseName == game.releaseName }

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
                isFavorite = game.isFavorite,
                size = if (game.sizeBytes != null && game.sizeBytes > 0) formatSize(game.sizeBytes) else if (game.sizeBytes == -1L) "Error" else null,
                sizeBytes = game.sizeBytes ?: 0L,
                description = game.description,
                screenshotUrls = game.screenshotUrls,
                lastUpdated = game.lastUpdated,
                popularity = game.popularity
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
                val mappedChar = getAlphabetGroupChar(game.name)
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
    val installState: StateFlow<InstallState> = installQueue.map { queue ->
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
        // Run v2.4.0 queue migration first (if needed)
        viewModelScope.launch {
            try {
                val migratedCount = repository.migrateLegacyQueueIfNeeded()
                if (migratedCount > 0) {
                    Log.i(TAG, "Migrated $migratedCount items from v2.4.0 queue")
                    _events.emit(MainEvent.ShowMessage("Restored $migratedCount queued items from previous version"))
                } else if (migratedCount < 0) {
                    // Migration returned error code (-1)
                    Log.w(TAG, "Migration returned error code: $migratedCount")
                    _events.emit(MainEvent.ShowMessage("Note: Could not restore previous download queue"))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Migration failed, continuing with empty queue", e)
                _events.emit(MainEvent.ShowMessage("Note: Could not restore previous download queue"))
            }
        }

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

    fun toggleFavorite(releaseName: String, isFavorite: Boolean) {
        viewModelScope.launch {
            repository.toggleFavorite(releaseName, isFavorite)
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

            val now = System.currentTimeMillis()
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

                    // Store current sync time after successful sync
                    prefs.edit().putLong("last_catalog_sync_time", now).apply()
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

    fun setSortMode(mode: SortMode) {
        _sortMode.value = mode
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

        val game = _allGames.value.find { it.releaseName == releaseName }
        if (game == null) {
            // Catalog not yet loaded or game not found
            viewModelScope.launch {
                if (_allGames.value.isEmpty()) {
                    _events.emit(MainEvent.ShowMessage("Please wait for the catalog to load"))
                } else {
                    _events.emit(MainEvent.ShowMessage("Game not found in catalog"))
                }
            }
            return
        }
        
        val existingTask = installQueue.value.find { it.releaseName == releaseName }
        if (existingTask != null) {
            val isFirst = installQueue.value.firstOrNull()?.releaseName == releaseName
            when {
                // FAILED tasks: Promote to front and relaunch (retry UX)
                existingTask.status == InstallTaskStatus.FAILED -> {
                    promoteTask(releaseName)
                    viewModelScope.launch {
                        _events.emit(MainEvent.ShowMessage("Retrying ${game.gameName}..."))
                    }
                }
                // PAUSED tasks at front: Resume
                existingTask.status == InstallTaskStatus.PAUSED && isFirst -> {
                    resumeInstall(releaseName)
                }
                // All other cases: Already in queue
                else -> {
                    viewModelScope.launch {
                        _events.emit(MainEvent.ShowMessage("${game.gameName} is already in the queue"))
                    }
                }
            }
            return
        }

        // Add to Room DB (which will update StateFlow via Flow observer)
        viewModelScope.launch {
            try {
                repository.addToQueue(
                    releaseName = game.releaseName,
                    status = com.vrpirates.rookieonquest.data.InstallStatus.QUEUED,
                    isDownloadOnly = downloadOnly
                )

                val isShowing = _showInstallOverlay.value
                val alreadyProcessing = installQueue.value.any { it.status.isProcessing() }

                if (!isShowing && !alreadyProcessing) {
                    _viewedReleaseName.value = releaseName
                    _showInstallOverlay.value = true
                } else {
                    _events.emit(MainEvent.ShowMessage("${game.gameName} added to queue"))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to add game to queue", e)
                _events.emit(MainEvent.ShowMessage("Failed to add ${game.gameName} to queue"))
            }
        }
        
        startQueueProcessor()
    }

    fun showOverlay() {
        val currentQueue = installQueue.value
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
                    val nextTask = installQueue.value.find { it.status == InstallTaskStatus.QUEUED }
                    if (nextTask == null) {
                        if (installQueue.value.none { it.status == InstallTaskStatus.QUEUED }) {
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
        val latestTask = installQueue.value.find { it.releaseName == task.releaseName }
        if (latestTask == null || latestTask.status != InstallTaskStatus.QUEUED) return

        // Auto-switch view if nothing is being viewed or the viewed task is not active
        val currentViewed = installQueue.value.find { it.releaseName == _viewedReleaseName.value }
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

                // Proactively clean up temp files immediately after success to free disk space
                repository.cleanupInstall(task.releaseName, task.totalBytes)

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
            // Remove completed task from Room DB
            viewModelScope.launch {
                try {
                    repository.removeFromQueue(task.releaseName)
                    progressThrottleMap.remove(task.releaseName) // Clean up throttling state
                    totalBytesWrittenSet.remove(task.releaseName) // Clean up totalBytes tracking
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to remove completed task from DB", e)
                }
            }
            
            if (installQueue.value.isEmpty()) {
                _showInstallOverlay.value = false
                _viewedReleaseName.value = null
            } else if (_viewedReleaseName.value == task.releaseName) {
                val remaining = installQueue.value
                val nextActive = remaining.find { it.status.isProcessing() }
                _viewedReleaseName.value = nextActive?.releaseName ?: remaining.firstOrNull()?.releaseName
            }

        } catch (e: Exception) {
            if (e is CancellationException) {
                Log.d(TAG, "Task cancelled/paused: ${task.gameName}")
                // Do not remove from queue, status is already set to PAUSED by pauseInstall()
                // Throttle map cleanup is handled by pauseInstall() or cancelInstall()
            } else {
                val errorMessage = e.message ?: "Unknown error"
                Log.e(TAG, "Installation failed for ${task.gameName}", e)

                // Ensure the status is updated to FAILED so the UI shows it
                updateTaskStatus(task.releaseName, InstallTaskStatus.FAILED, errorMessage)

                // Clean up throttle map on failure to prevent memory leak
                progressThrottleMap.remove(task.releaseName)

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

    /**
     * Updates task status in Room DB. Status updates are NOT throttled because:
     * 1. State transitions (QUEUED→DOWNLOADING→EXTRACTING→INSTALLING→COMPLETED) must be immediate
     *    for correct UI display and queue processor logic
     * 2. Status changes are infrequent (5-7 per task) vs progress updates (hundreds per second)
     * 3. Delayed status updates could cause race conditions in pause/resume/cancel operations
     * 4. UI relies on status to show correct action buttons (Pause vs Resume, etc.)
     *
     * Terminal states (COMPLETED, FAILED) use NonCancellable context to ensure they persist
     * even if the ViewModel is cleared during the write operation.
     *
     * Contrast with updateTaskProgress() which IS throttled to max 1 update per 500ms.
     */
    private fun updateTaskStatus(releaseName: String, status: InstallTaskStatus, error: String? = null) {
        // Terminal states must persist even if ViewModel/coroutine is cancelled
        val isTerminalState = status == InstallTaskStatus.COMPLETED || status == InstallTaskStatus.FAILED
        val context = if (isTerminalState) NonCancellable else viewModelScope.coroutineContext

        // Persist to Room DB immediately - NO THROTTLING (see doc above)
        viewModelScope.launch(context) {
            try {
                repository.updateQueueStatus(releaseName, status.toDataStatus())
            } catch (e: Exception) {
                Log.e(TAG, "Failed to update task status in DB", e)
                // Notify user of the failure so they're aware the state may be inconsistent
                _events.emit(MainEvent.ShowMessage("Failed to save queue state. Please restart the app if issues persist."))
            }
        }
    }

    // Throttling state for DB progress updates (NOT for status - see updateTaskStatus doc)
    private val progressThrottleMap = mutableMapOf<String, Long>()
    private val PROGRESS_THROTTLE_MS = 500L // Update DB max once every 500ms

    // Tracks tasks where totalBytes has already been written to DB (optimization to avoid redundant writes)
    private val totalBytesWrittenSet = mutableSetOf<String>()

    /**
     * Updates task progress in Room DB with throttling to prevent excessive I/O.
     * Progress updates happen every 64KB of download (~hundreds/second), so we limit
     * DB writes to max once every 500ms per task. Completion (progress >= 1.0) always updates.
     *
     * Optimization: After the first write that includes totalBytes, subsequent writes
     * skip totalBytes since it's constant throughout the download.
     */
    private fun updateTaskProgress(releaseName: String, message: String, progress: Float, current: Long, total: Long) {
        // Check throttle BEFORE launching coroutine to avoid memory pressure
        val now = System.currentTimeMillis()
        val lastUpdate = progressThrottleMap[releaseName] ?: 0L
        val shouldUpdate = (now - lastUpdate) >= PROGRESS_THROTTLE_MS || progress >= 1.0f

        if (!shouldUpdate) return // Early exit - no coroutine launched

        progressThrottleMap[releaseName] = now

        // Check if totalBytes has already been written for this task
        val hasTotalBeenWritten = totalBytesWrittenSet.contains(releaseName)
        val shouldWriteTotal = !hasTotalBeenWritten && total > 0

        // Mark as written if we're about to write totalBytes for the first time
        if (shouldWriteTotal) {
            totalBytesWrittenSet.add(releaseName)
        }

        // Only now launch coroutine for DB persistence
        viewModelScope.launch {
            try {
                repository.updateQueueProgress(
                    releaseName = releaseName,
                    progress = progress,
                    downloadedBytes = if (current > 0) current else null,
                    totalBytes = if (total > 0) total else null,
                    skipTotalBytes = hasTotalBeenWritten // Skip if already written
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to update task progress in DB", e)
            }
        }
    }

    fun pauseInstall(releaseName: String) {
        val task = installQueue.value.find { it.releaseName == releaseName } ?: return
        if (task.status.isProcessing() || task.status == InstallTaskStatus.QUEUED) {
            updateTaskStatus(releaseName, InstallTaskStatus.PAUSED)
            progressThrottleMap.remove(releaseName) // Clean up throttling state on pause
            totalBytesWrittenSet.remove(releaseName) // Reset so totalBytes is written on resume
            if (releaseName == activeReleaseName) {
                currentTaskJob?.cancel()
            }
        }
    }

    fun resumeInstall(releaseName: String) {
        // Only allow resume if it's the first in queue
        val isFirst = installQueue.value.firstOrNull()?.releaseName == releaseName
        if (isFirst) {
            _viewedReleaseName.value = releaseName
            updateTaskStatus(releaseName, InstallTaskStatus.QUEUED)
            startQueueProcessor()
        }
    }

    fun cancelInstall(releaseName: String) {
        val task = installQueue.value.find { it.releaseName == releaseName } ?: return
        
        if (releaseName == activeReleaseName) {
            currentTaskJob?.cancel()
        }

        viewModelScope.launch(Dispatchers.IO) {
            repository.cleanupInstall(releaseName, task.totalBytes)
            // Remove from Room DB
            repository.removeFromQueue(releaseName)
            progressThrottleMap.remove(releaseName) // Clean up throttling state
            totalBytesWrittenSet.remove(releaseName) // Clean up totalBytes tracking
        }
        
        val remaining = installQueue.value
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
        val currentQueue = installQueue.value
        val task = currentQueue.find { it.releaseName == releaseName } ?: return

        if (task.status == InstallTaskStatus.QUEUED || task.status == InstallTaskStatus.PAUSED || task.status == InstallTaskStatus.FAILED) {
            // Pause current processing task if any
            val previousActive = activeReleaseName
            if (previousActive != null && previousActive != releaseName) {
                updateTaskStatus(previousActive, InstallTaskStatus.PAUSED)
                currentTaskJob?.cancel()
            }

            // Reorder queue in Room DB atomically: promote to front AND set status to QUEUED
            // Using single atomic transaction to prevent partial state updates
            viewModelScope.launch {
                try {
                    if (task.status != InstallTaskStatus.QUEUED) {
                        // Use atomic operation that promotes AND updates status in one transaction
                        repository.promoteInQueueAndSetStatus(releaseName, com.vrpirates.rookieonquest.data.InstallStatus.QUEUED)
                    } else {
                        // Already QUEUED, just promote position
                        repository.promoteInQueue(releaseName)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to promote task in DB", e)
                    _events.emit(MainEvent.ShowMessage("Failed to promote task. Please try again."))
                }
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
