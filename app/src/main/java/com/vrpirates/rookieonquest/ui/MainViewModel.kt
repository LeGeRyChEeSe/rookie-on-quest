package com.vrpirates.rookieonquest.ui

import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vrpirates.rookieonquest.BuildConfig
import com.vrpirates.rookieonquest.data.GameData
import com.vrpirates.rookieonquest.data.MainRepository
import com.vrpirates.rookieonquest.network.GitHubRelease
import com.vrpirates.rookieonquest.network.GitHubService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds

sealed class MainEvent {
    data class Uninstall(val packageName: String) : MainEvent()
    data class InstallApk(val apkFile: File) : MainEvent()
    object RequestInstallPermission : MainEvent()
    object RequestStoragePermission : MainEvent()
    data class ShowUpdatePopup(val release: GitHubRelease) : MainEvent()
}

enum class RequiredPermission {
    INSTALL_UNKNOWN_APPS,
    MANAGE_EXTERNAL_STORAGE
}

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val TAG = "MainViewModel"
    private val repository = MainRepository(application)
    private val prefs = application.getSharedPreferences("rookie_prefs", Context.MODE_PRIVATE)
    
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery

    private val _events = MutableSharedFlow<MainEvent>()
    val events = _events.asSharedFlow()

    private val _installedPackages = MutableStateFlow<Map<String, Long>>(emptyMap())

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

    private var isPermissionFlowActive = false
    private var previousMissingCount = 0
    private var refreshJob: Job? = null
    private var sizeFetchJob: Job? = null
    private var installJob: Job? = null

    private val githubService = Retrofit.Builder()
        .baseUrl("https://api.github.com/")
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(GitHubService::class.java)

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    // Get games directly from Room with intelligent sorting
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
                size = if (game.sizeBytes != null && game.sizeBytes > 0) formatSize(game.sizeBytes) else null
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
                    firstChar.isDigit() -> '#' // Use # to represent all digits
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

    private val _isInstalling = MutableStateFlow(false)
    val isInstalling: StateFlow<Boolean> = _isInstalling

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error
    
    private val _progressMessage = MutableStateFlow<String?>(null)
    val progressMessage: StateFlow<String?> = _progressMessage

    init {
        // Only start heavy work AFTER update check
        viewModelScope.launch {
            _isUpdateCheckInProgress.value = true
            try {
                val updateAvailable = checkForAppUpdates()
                if (!updateAvailable) {
                    checkPermissions()
                    startSizeFetchLoop()
                }
            } finally {
                _isUpdateCheckInProgress.value = false
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

    /**
     * Compares two version strings (e.g., "2.1.0" and "2.0.0").
     * Returns true if [latest] is strictly greater than [current].
     */
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
        startSizeFetchLoop()
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
            }
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun startSizeFetchLoop() {
        sizeFetchJob = viewModelScope.launch(Dispatchers.Default) {
            while (true) {
                // Pause fetching if the app is in background to save resources
                if (!_isAppVisible.value) {
                    Log.d(TAG, "Size fetch loop suspended (app in background)")
                    _isAppVisible.first { it }
                    Log.d(TAG, "Size fetch loop resumed")
                }

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

                // ONLY fetch sizes for games currently visible on screen
                val target = candidates.find { prioritizedPackages.contains(it.packageName) }

                if (target != null) {
                    try {
                        Log.d(TAG, "Fetching size for visible game: ${target.gameName}")
                        repository.getGameRemoteInfo(target)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error fetching size for ${target.gameName}", e)
                        delay(2000)
                    }
                    
                    // Small delay or wait for new priority signal before next fetch
                    select<Unit> {
                        priorityUpdateChannel.onReceive { }
                        onTimeout(100.milliseconds) { }
                    }
                } else {
                    // No visible games need size fetching, wait for a signal (scroll, search, etc.)
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

    fun installGame(packageName: String, downloadOnly: Boolean = false) {
        if (_missingPermissions.value?.isNotEmpty() == true) {
            startPermissionFlow()
            return
        }

        val game = _allGames.value.find { it.packageName == packageName } ?: return
        val keepApk = _keepApks.value
        
        installJob?.cancel()
        installJob = viewModelScope.launch {
            try {
                _isInstalling.value = true
                val apkFile = repository.installGame(
                    game = game,
                    keepApk = keepApk,
                    downloadOnly = downloadOnly
                ) { message, progress, _, _ ->
                    _progressMessage.value = "$message (${(progress * 100).toInt()}%)"
                }
                if (apkFile != null && !downloadOnly) {
                    _events.emit(MainEvent.InstallApk(apkFile))
                }
            } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException) {
                    _error.value = "Error: ${e.message}"
                }
            } finally {
                _isInstalling.value = false
                _progressMessage.value = null
            }
        }
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
    
    fun cancelInstall() {
        installJob?.cancel()
        _isInstalling.value = false
        _progressMessage.value = null
    }

    private fun formatSize(bytes: Long): String {
        if (bytes <= 0) return ""
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
        return String.format(Locale.US, "%.1f %s", bytes / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
    }
}
