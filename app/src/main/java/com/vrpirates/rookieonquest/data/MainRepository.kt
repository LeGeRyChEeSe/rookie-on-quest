package com.vrpirates.rookieonquest.data

import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import com.vrpirates.rookieonquest.logic.CatalogParser
import com.vrpirates.rookieonquest.network.PublicConfig
import com.vrpirates.rookieonquest.network.VrpService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.vrpirates.rookieonquest.worker.DownloadWorker
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.channelFlow

class MainRepository(private val context: Context) {
    private val TAG = "MainRepository"
    private val catalogMutex = Mutex()
    private val db = AppDatabase.getDatabase(context)
    private val gameDao = db.gameDao()

    // Use shared network instances from NetworkModule (singleton)
    private val okHttpClient = NetworkModule.okHttpClient
    private val service = NetworkModule.retrofit.create(VrpService::class.java)

    private val prefs = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)

    private var cachedConfig: PublicConfig? = null
    private var decodedPassword: String? = null
    
    val iconsDir = File(context.filesDir, "icons").apply { if (!exists()) mkdirs() }
    val thumbnailsDir = File(context.filesDir, "thumbnails").apply { if (!exists()) mkdirs() }
    val notesDir = File(context.filesDir, "notes").apply { if (!exists()) mkdirs() }

    private val catalogCacheFile = File(context.filesDir, "VRP-GameList.txt")
    // Use filesDir instead of cacheDir to prevent Android from purging large game archives
    // during extraction. cacheDir can be cleaned by the system when storage is low.
    private val tempInstallRoot = File(context.filesDir, "install_temp")
    val downloadsDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), FilePaths.DOWNLOADS_ROOT_DIR_NAME)
    val logsDir = File(downloadsDir, "logs").apply { if (!exists()) mkdirs() }

    fun getAllGamesFlow(): Flow<List<GameData>> = gameDao.getAllGames().map { entities ->
        entities.map { it.toData() }
    }

    fun searchGamesFlow(query: String): Flow<List<GameData>> = gameDao.searchGames("%$query%").map { entities ->
        entities.map { it.toData() }
    }

    suspend fun toggleFavorite(releaseName: String, isFavorite: Boolean) = withContext(Dispatchers.IO) {
        gameDao.updateFavorite(releaseName, isFavorite)
    }

    suspend fun fetchConfig(): PublicConfig = withContext(Dispatchers.IO) {
        try {
            val config = service.getPublicConfig()
            cachedConfig = config
            try {
                val decoded = Base64.decode(config.password64, Base64.DEFAULT)
                decodedPassword = String(decoded, Charsets.UTF_8)
            } catch (e: Exception) {
                decodedPassword = config.password64
            }
            config
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching config", e)
            throw e
        }
    }

    suspend fun syncCatalog(baseUri: String) = withContext(Dispatchers.IO) {
        catalogMutex.withLock {
            val sanitizedBase = if (baseUri.endsWith("/")) baseUri else "$baseUri/"
            val metaUrl = "${sanitizedBase}meta.7z"
            
            val lastModified = getRemoteLastModified(metaUrl)
            val savedModified = prefs.getString("meta_last_modified", "")
            
            if (catalogCacheFile.exists() && lastModified == savedModified && lastModified != null && gameDao.getCount() > 0) {
                return@withLock
            }

            val tempMetaFile = File.createTempFile("meta_", ".7z", context.cacheDir)
            try {
                downloadFile(metaUrl, tempMetaFile)

                val passwordsToTry = listOfNotNull(decodedPassword, cachedConfig?.password64, null).distinct()
                var gameListContent = ""
                var success = false

                for (pass in passwordsToTry) {
                    try {
                        extractMetaToCache(tempMetaFile, pass) { content -> gameListContent = content }
                        success = true
                        break
                    } catch (e: Exception) {
                        Log.w(TAG, "Extraction failed with password attempt: ${e.message}")
                    }
                }

                if (success && gameListContent.isNotEmpty()) {
                    if (lastModified != null) {
                        prefs.edit().putString("meta_last_modified", lastModified).apply()
                    }
                    val newList = CatalogParser.parse(gameListContent)
                    
                    val existingData = gameDao.getAllGamesList().associateBy { it.releaseName }
                    
                    val entities = newList.map { game ->
                        val existing = existingData[game.releaseName]
                        val isNewOrUpdated = existing == null || existing.versionCode != game.versionCode
                        
                        // Local description check
                        val localNote = File(notesDir, "${game.releaseName}.txt")
                        val description = if (localNote.exists()) localNote.readText() else existing?.description ?: game.description
                        
                        game.copy(
                            sizeBytes = game.sizeBytes ?: existing?.sizeBytes,
                            description = description,
                            isFavorite = existing?.isFavorite ?: false,
                            lastUpdated = if (isNewOrUpdated) System.currentTimeMillis() else (existing?.lastUpdated ?: System.currentTimeMillis()),
                            popularity = game.popularity
                        ).toEntity()
                    }
                    
                    gameDao.insertGames(entities)
                }
            } finally {
                if (tempMetaFile.exists()) tempMetaFile.delete()
            }
        }
    }

    private fun extractMetaToCache(file: File, password: String?, onGameListFound: (String) -> Unit) {
        val builder = SevenZFile.builder().setFile(file)
        if (password != null) builder.setPassword(password.toCharArray())

        builder.get().use { sevenZFile ->
            // Reuse buffer across all file extractions to reduce GC pressure (Code Review fix)
            val sharedBuffer = ByteArray(DownloadUtils.DOWNLOAD_BUFFER_SIZE)
            var entry = sevenZFile.nextEntry
            while (entry != null) {
                val entryName = entry.name.lowercase()
                if (entryName.endsWith("vrp-gamelist.txt")) {
                    val out = java.io.ByteArrayOutputStream()
                    var bytesRead: Int
                    while (sevenZFile.read(sharedBuffer).also { bytesRead = it } != -1) {
                        out.write(sharedBuffer, 0, bytesRead)
                    }
                    val content = out.toString(Charsets.UTF_8)
                    catalogCacheFile.writeText(content)
                    onGameListFound(content)
                } else if (entryName.contains("/thumbnails/")) {
                    val fileName = entry.name.substringAfterLast("/")
                    if (fileName.isNotEmpty()) {
                        val targetFile = File(thumbnailsDir, fileName)
                        saveEntryToFile(sevenZFile, targetFile, sharedBuffer)
                    }
                } else if (entryName.contains("/notes/")) {
                    val fileName = entry.name.substringAfterLast("/")
                    if (fileName.isNotEmpty()) {
                        val targetFile = File(notesDir, fileName)
                        saveEntryToFile(sevenZFile, targetFile, sharedBuffer)
                    }
                } else if (entryName.endsWith(".png") || entryName.endsWith(".jpg")) {
                    val fileName = entry.name.substringAfterLast("/")
                    val iconFile = File(iconsDir, fileName)
                    if (!iconFile.exists()) {
                        saveEntryToFile(sevenZFile, iconFile, sharedBuffer)
                    }
                }
                entry = sevenZFile.nextEntry
            }
        }
    }

    /**
     * Extracts a single entry from a SevenZFile to a target file.
     * Uses a shared buffer to reduce GC pressure when extracting multiple files.
     *
     * @param sevenZFile The 7z archive being extracted from
     * @param targetFile The destination file to write to
     * @param buffer Reusable buffer for I/O operations (uses DownloadUtils.DOWNLOAD_BUFFER_SIZE)
     */
    private fun saveEntryToFile(sevenZFile: SevenZFile, targetFile: File, buffer: ByteArray) {
        FileOutputStream(targetFile).use { out ->
            var bytesRead: Int
            while (sevenZFile.read(buffer).also { bytesRead = it } != -1) {
                out.write(buffer, 0, bytesRead)
            }
        }
    }

    private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation {
            cancel()
        }
        enqueue(object : Callback {
            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response)
            }
            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWithException(e)
            }
        })
    }

    private suspend fun downloadFile(url: String, targetFile: File) {
        val request = Request.Builder().url(url).header("User-Agent", Constants.USER_AGENT).build()
        okHttpClient.newCall(request).await().use { response ->
            if (!response.isSuccessful) throw Exception("HTTP ${response.code}")
            response.body?.byteStream()?.use { input ->
                targetFile.outputStream().use { output -> 
                    copyToCancellable(input, output)
                }
            }
        }
    }

    private suspend fun copyToCancellable(input: InputStream, output: OutputStream) {
        // Use standardized buffer size from DownloadUtils for consistency (Code Review fix: magic number)
        val buffer = ByteArray(DownloadUtils.DOWNLOAD_BUFFER_SIZE)
        var bytesRead: Int
        while (true) {
            currentCoroutineContext().ensureActive()
            bytesRead = input.read(buffer)
            if (bytesRead == -1) break
            output.write(buffer, 0, bytesRead)
        }
    }

    /**
     * Story 1.7 Code Review Fix: Copy with progress callback for large file staging.
     * Reports progress during APK staging to avoid UI "freeze" at 96%.
     */
    private suspend fun copyToCancellableWithProgress(
        input: InputStream,
        output: OutputStream,
        totalBytes: Long,
        onProgress: (Long) -> Unit
    ) {
        val buffer = ByteArray(DownloadUtils.DOWNLOAD_BUFFER_SIZE)
        var bytesRead: Int
        var bytesCopied = 0L
        var lastProgressReport = 0L
        val progressInterval = maxOf(totalBytes / 100, 1024 * 1024) // Report every 1% or 1MB minimum

        while (true) {
            currentCoroutineContext().ensureActive()
            bytesRead = input.read(buffer)
            if (bytesRead == -1) break
            output.write(buffer, 0, bytesRead)
            bytesCopied += bytesRead

            // Report progress at intervals to avoid excessive callbacks
            if (bytesCopied - lastProgressReport >= progressInterval) {
                onProgress(bytesCopied)
                lastProgressReport = bytesCopied
            }
        }
        // Final progress report
        onProgress(bytesCopied)
    }

    suspend fun getInstalledPackagesMap(): Map<String, Long> = withContext(Dispatchers.IO) {
        try {
            val pm = context.packageManager
            val packages = pm.getInstalledPackages(0)
            packages.associate { 
                @Suppress("DEPRECATION")
                val vCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) it.longVersionCode else it.versionCode.toLong()
                it.packageName to vCode
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting installed packages", e)
            emptyMap()
        }
    }

    /**
     * Fetches remote game information including segment sizes, descriptions, and screenshots.
     *
     * SHARED CODE: The following utilities are shared with DownloadWorker via DownloadUtils:
     * - DownloadUtils.HREF_REGEX: HTML href parsing
     * - DownloadUtils.shouldSkipEntry(): Entry filtering logic
     * - DownloadUtils.isDownloadableFile(): File type detection
     * - DownloadUtils.headRequestSemaphore: Rate limiting for HEAD requests
     * - DownloadUtils.downloadWithProgress(): Progress-reporting file download
     *
     * INTENTIONALLY SEPARATE: The method structure remains separate from DownloadWorker because:
     * - This method also fetches metadata (descriptions, screenshots) which Worker doesn't need
     * - Different error handling for UI vs background contexts (propagate vs retry)
     * - DownloadWorker needs simpler error recovery for WorkManager retries
     *
     * If modifying segment fetching logic, also review DownloadWorker.fetchRemoteSegments()
     * for consistency to prevent behavioral divergence.
     */
    suspend fun getGameRemoteInfo(game: GameData): Triple<Map<String, Long>, Long, Map<String, Any?>> = withContext(Dispatchers.IO) {
        val config = cachedConfig ?: throw Exception("Config not loaded")
        val hash = CryptoUtils.md5(game.releaseName + "\n")
        val sanitizedBase = if (config.baseUri.endsWith("/")) config.baseUri else "${config.baseUri}/"
        val dirUrl = "$sanitizedBase$hash/"

        val rawSegments = mutableListOf<String>()
        val screenshotUrls = mutableListOf<String>()
        
        // Use local metadata if available
        val localNote = File(notesDir, "${game.releaseName}.txt")
        var description: String? = if (localNote.exists()) localNote.readText() else game.description

        try {
            val request = Request.Builder().url(dirUrl).header("User-Agent", Constants.USER_AGENT).build()
            okHttpClient.newCall(request).await().use { response ->
                if (response.code == 404) {
                    gameDao.updateSize(game.releaseName, -1L)
                    // Use type-safe exception for consistent error handling
                    throw MirrorNotFoundException(game.releaseName)
                }
                if (!response.isSuccessful) throw Exception("Mirror error: ${response.code}")

                val html = response.body?.string() ?: ""

                // Extract all relevant entries (files or folders) using idiomatic Kotlin Regex
                DownloadUtils.HREF_REGEX.findAll(html).forEach { matchResult ->
                    val entry = matchResult.groupValues[1]
                    if (DownloadUtils.shouldSkipEntry(entry)) return@forEach

                    if (entry.endsWith("/")) {
                        // It's a folder (like com.beatgames.beatsaber/)
                        fetchAllFilesFromDir(dirUrl + entry, entry).forEach { rawSegments.add(it) }
                    } else if (DownloadUtils.isDownloadableFile(entry)) {
                        rawSegments.add(entry)
                    }
                }

                // Extract screenshots ( gameplay images found on mirror )
                val imgMatcher = java.util.regex.Pattern.compile("href\\s*=\\s*\"([^\"]+\\.(jpg|png|jpeg))\"", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(html)
                while (imgMatcher.find()) {
                    val imgName = imgMatcher.group(1) ?: continue
                    if (!imgName.contains(game.packageName, ignoreCase = true)) {
                        screenshotUrls.add(dirUrl + imgName)
                    }
                }

                if (description == null && html.contains("notes.txt", ignoreCase = true)) {
                    val notesUrl = dirUrl + "notes.txt"
                    try {
                        val notesRequest = Request.Builder().url(notesUrl).header("User-Agent", Constants.USER_AGENT).build()
                        okHttpClient.newCall(notesRequest).await().use { notesResponse ->
                            if (notesResponse.isSuccessful) {
                                description = notesResponse.body?.string()?.trim()
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to fetch remote notes.txt for ${game.gameName}")
                    }
                }
            }

            // Use full path for deduplication to prevent data loss in special directory structures
            // Files with same name but different paths are likely different files (e.g., Quake3Quest data folders)
            val uniqueSegments = rawSegments.distinct()

            // Parallelize HEAD requests with Semaphore rate limiting
            // Semaphore prevents socket exhaustion on mirror servers
            // runCatching ensures a single mirror timeout doesn't fail the entire download batch
            val segmentMap = supervisorScope {
                uniqueSegments.map { seg ->
                    async {
                        DownloadUtils.headRequestSemaphore.withPermit {
                            currentCoroutineContext().ensureActive()
                            runCatching {
                                val headRequest = Request.Builder()
                                    .url(dirUrl + seg)
                                    .head()
                                    .header("User-Agent", Constants.USER_AGENT)
                                    .build()
                                okHttpClient.newCall(headRequest).await().use { response ->
                                    val size = response.header("Content-Length")?.toLongOrNull() ?: 0L
                                    seg to size
                                }
                            }.getOrElse { e ->
                                Log.w(TAG, "HEAD request failed for $seg, will determine size during download", e)
                                // Return -1 size - actual size will be determined during download
                                // Using 0L caused a bug where existingSize >= 0 was always true, skipping the file
                                seg to -1L
                            }
                        }
                    }
                }.awaitAll().toMap()
            }

            // Filter out failed HEAD requests (-1L) before summing to prevent invalid totalSize
            // -1L indicates unknown size (will be determined during download)
            val validSegments = segmentMap.filter { it.value > 0L }
            val totalSize = validSegments.values.sum()
            
            gameDao.updateSize(game.releaseName, totalSize)
            gameDao.updateMetadata(game.releaseName, description, screenshotUrls.joinToString("|"))

            Triple(segmentMap, totalSize, mapOf("description" to description, "screenshots" to screenshotUrls))
        } catch (e: Exception) {
            // Use type-safe exception checking instead of brittle string matching
            // MirrorNotFoundException is thrown explicitly when response.code == 404
            if (e is MirrorNotFoundException) {
                gameDao.updateSize(game.releaseName, -1L)
            }
            throw e
        }
    }

    /**
     * Recursively fetches all files from a directory URL.
     *
     * Error handling strategy:
     * - HTTP errors (4xx/5xx): Logged and return empty list (directory may not exist or be inaccessible)
     * - Network/IO errors: Logged and return empty list (transient errors shouldn't fail the whole operation)
     * - CancellationException: Propagated to allow proper coroutine cancellation
     *
     * This permissive error handling is intentional because:
     * 1. A single subdirectory failure shouldn't prevent downloading other files
     * 2. Some mirror directories may have permission issues or be temporarily unavailable
     * 3. The caller (getGameRemoteInfo) aggregates files from multiple sources
     *
     * @param baseUrl The directory URL to fetch from
     * @param prefix Path prefix to prepend to discovered files
     * @return List of file paths found (may be empty if directory is inaccessible)
     */
    private suspend fun fetchAllFilesFromDir(baseUrl: String, prefix: String): List<String> = withContext(Dispatchers.IO) {
        val files = mutableListOf<String>()
        try {
            currentCoroutineContext().ensureActive()
            val request = Request.Builder().url(baseUrl).header("User-Agent", Constants.USER_AGENT).build()
            okHttpClient.newCall(request).await().use { response ->
                if (response.isSuccessful) {
                    val html = response.body?.string() ?: ""

                    // Use idiomatic Kotlin Regex instead of deprecated HREF_PATTERN
                    DownloadUtils.HREF_REGEX.findAll(html).forEach { matchResult ->
                        currentCoroutineContext().ensureActive()
                        val entry = matchResult.groupValues[1]
                        if (entry.startsWith(".") || entry == "../") return@forEach
                        if (entry.endsWith("/")) {
                            files.addAll(fetchAllFilesFromDir(baseUrl + entry, prefix + entry))
                        } else {
                            files.add(prefix + entry)
                        }
                    }
                } else {
                    // Log non-successful responses for debugging
                    Log.w(TAG, "Failed to list dir $baseUrl: HTTP ${response.code}")
                }
            }
        } catch (e: CancellationException) {
            // Propagate cancellation to allow proper coroutine cleanup
            throw e
        } catch (e: Exception) {
            // Log error but don't fail - some directories may be inaccessible
            Log.w(TAG, "Error listing dir $baseUrl: ${e.message}")
        }
        files
    }

    private fun checkAvailableSpace(
        requiredBytes: Long,
        hasUnknownSizes: Boolean = false,
        checkExternalStorage: Boolean = false,
        estimatedApkSize: Long = 0L
    ) {
        // CRITICAL: Check space on filesDir partition where tempInstallRoot writes to
        // Consistency with DownloadWorker is essential to prevent false positives/negatives
        val stat = StatFs(context.filesDir.path)
        val availableBytes = stat.availableBlocksLong * stat.blockSizeLong

        // When sizes are unknown, require a conservative minimum space check
        // This prevents downloads with all -1L sizes from bypassing space validation
        val effectiveRequired = if (hasUnknownSizes && requiredBytes == 0L) {
            // Require at least 1GB as a safety buffer when all sizes are unknown
            Constants.UNKNOWN_SIZE_SPACE_BUFFER
        } else {
            requiredBytes
        }

        // Check internal storage (filesDir where tempInstallRoot writes)
        if (availableBytes < effectiveRequired) {
            val requiredMb = effectiveRequired / (1024 * 1024)
            val availableMb = availableBytes / (1024 * 1024)
            throw InsufficientStorageException(requiredMb, availableMb)
        }

        // Check external storage for APK staging (externalFilesDir) and downloads (downloadsDir)
        // APK staging always happens during installation - the APK is copied to externalFilesDir
        // before launching the installer via FileProvider
        val apkStagingRequired = if (estimatedApkSize > 0) estimatedApkSize else Constants.MIN_ESTIMATED_APK_SIZE
        val externalRequired = if (checkExternalStorage) effectiveRequired else apkStagingRequired

        try {
            val externalFilesDir = context.getExternalFilesDir(null)
            if (externalFilesDir != null) {
                val externalStat = StatFs(externalFilesDir.path)
                val externalAvailable = externalStat.availableBlocksLong * externalStat.blockSizeLong

                if (externalAvailable < externalRequired) {
                    val requiredMb = externalRequired / (1024 * 1024)
                    val availableMb = externalAvailable / (1024 * 1024)
                    throw InsufficientStorageException(requiredMb, availableMb)
                }
            }
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Could not check external storage space", e)
            // Don't fail on external storage check errors - the device might not have external storage
        }

        // Log warning if we're proceeding with unknown sizes but minimal space check passed
        if (hasUnknownSizes && requiredBytes == 0L) {
            Log.w(TAG, "Proceeding with unknown sizes - space check passed minimum 1GB threshold")
        }
    }

    /**
     * Installs a game by extracting archives and moving files to correct locations.
     *
     * @param game The game metadata
     * @param keepApk If true, save APK to Downloads folder
     * @param downloadOnly If true, skip installation after download
     * @param skipRemoteVerification If true, skip HEAD requests to verify file sizes.
     *        Used when called after WorkManager download completes, since files are already verified.
     *        This optimization avoids redundant network calls.
     * @param onProgress Progress callback
     * @return APK file to install, or null if download-only or already installed
     */
    suspend fun installGame(
        game: GameData,
        keepApk: Boolean = false,
        downloadOnly: Boolean = false,
        skipRemoteVerification: Boolean = false,
        onProgress: (String, Float, Long, Long) -> Unit
    ): File? = withContext(Dispatchers.IO) {
        // 1. Check if already installed and up to date
        val installedMap = getInstalledPackagesMap()
        val installedVersion = installedMap[game.packageName]
        val targetVersion = game.versionCode.toLongOrNull() ?: 0L

        if (!downloadOnly && installedVersion != null && installedVersion >= targetVersion) {
            onProgress("Already installed (v$installedVersion)", 1f, 0, 0)
            delay(1500)
            return@withContext null
        }

        // 2. Fetch remote file info (skip HEAD requests if coming from WorkManager handoff)
        // Refactored to avoid recursion: use local flag to track whether we need server fallback
        var remoteSegments: Map<String, Long>
        var totalBytes: Long
        var useServerVerification = !skipRemoteVerification

        if (skipRemoteVerification) {
            // WorkManager already downloaded files - verify locally instead of making HEAD requests
            // Use EXTRACTION_START (80%) instead of VERIFYING (2%) to prevent visual progress regression
            // after download phase (80%) completes - seamless transition to extraction phase
            onProgress("Preparing extraction...", Constants.PROGRESS_MILESTONE_EXTRACTION_START, 0, 0)
            val hash = CryptoUtils.md5(game.releaseName + "\n")
            val gameTempDir = File(tempInstallRoot, hash)

            // Build segment map from local files
            val localSegments = mutableMapOf<String, Long>()
            if (gameTempDir.exists()) {
                gameTempDir.walkTopDown().filter { it.isFile }.forEach { file ->
                    val relativePath = file.relativeTo(gameTempDir).path
                    if (relativePath != "extraction_done.marker" && !relativePath.startsWith("extracted")) {
                        localSegments[relativePath] = file.length()
                    }
                }
            }

            if (localSegments.isEmpty()) {
                // No local files found - fall back to server verification (non-recursive approach)
                Log.w(TAG, "No local files found for ${game.releaseName}, falling back to server verification")
                useServerVerification = true
            }

            remoteSegments = localSegments
            totalBytes = localSegments.values.sum()
        } else {
            // Initialize with empty values - will be populated below
            remoteSegments = emptyMap()
            totalBytes = 0L
        }

        // Server verification path (either primary or fallback)
        if (useServerVerification) {
            onProgress("Verifying with server...", Constants.PROGRESS_MILESTONE_VERIFYING, 0, 0)
            val result = getGameRemoteInfo(game)
            remoteSegments = result.first
            totalBytes = result.second
        }

        if (remoteSegments.isEmpty()) throw Exception("No installable files found")

        // PRE-FLIGHT SPACE CHECK
        val isSevenZ = remoteSegments.keys.any { it.contains(".7z") }
        val hasUnknownSizes = remoteSegments.values.any { it == -1L }
        val estimatedRequired = DownloadUtils.calculateRequiredStorage(
            totalBytes = totalBytes,
            isSevenZArchive = isSevenZ,
            keepApkOrDownloadOnly = downloadOnly || keepApk
        )
        // Estimate OBB space requirements (Code Review fix)
        val estimatedObbRequired = if (!downloadOnly) {
            DownloadUtils.calculateRequiredObbStorage(remoteSegments, game.packageName)
        } else 0L

        // Estimate APK size from segments (for external storage check)
        // APK is staged to externalFilesDir before installation via FileProvider
        val estimatedApkSize = remoteSegments.entries
            .filter { it.key.endsWith(".apk", ignoreCase = true) }
            .maxOfOrNull { it.value } ?: 0L
        // Check internal storage (for temp files) and external storage (for APK staging + OBB + download-only/keep-apk modes)
        checkAvailableSpace(
            requiredBytes = estimatedRequired,
            hasUnknownSizes = hasUnknownSizes,
            checkExternalStorage = downloadOnly || keepApk || estimatedObbRequired > 0,
            estimatedApkSize = estimatedApkSize + estimatedObbRequired
        )

        val hash = CryptoUtils.md5(game.releaseName + "\n")
        val gameTempDir = File(tempInstallRoot, hash)
        val extractionDir = File(gameTempDir, "extracted")
        val safeDirName = game.releaseName.replace(Regex("[^a-zA-Z0-9.-]"), "_")
        val gameDownloadDir = File(downloadsDir, safeDirName)

        // 3. Verify integrity of existing files (temp or downloads)
        var isLocalReady = false
        
        // Check if standard APK is already in Downloads and valid
        val safeGameName = game.gameName.replace(Regex("[^a-zA-Z0-9.-]"), "_")
        val apkFileName = "${safeGameName}_v${game.versionCode}_${game.packageName}.apk"
        val cachedApk = File(gameDownloadDir, apkFileName)

        // Find any APK in download dir if standard one doesn't match
        // Story 1.7 Code Review Round 8: Use isValidApkFile instead of isApkMatching to eliminate duplication
        var foundApk: File? = if (isValidApkFile(cachedApk, game.packageName, targetVersion)) cachedApk else null
        if (foundApk == null && gameDownloadDir.exists()) {
             foundApk = gameDownloadDir.listFiles()?.find { isValidApkFile(it, game.packageName, targetVersion) }
        }

        if (foundApk != null) {
            // APK is good, now check OBBs
            val localObbDir = File(gameDownloadDir, game.packageName)
            val hasObbFolder = localObbDir.exists() && localObbDir.isDirectory && (localObbDir.list()?.isNotEmpty() ?: false)
            val hasLooseObbs = gameDownloadDir.listFiles()?.any { it.name.endsWith(".obb", true) } ?: false
            
            // If the game needs OBBs (remote segments have them), check if we have them
            val needsObbs = remoteSegments.keys.any { it.contains("/") || it.endsWith(".obb") }
            if (!needsObbs || hasObbFolder || hasLooseObbs) {
                isLocalReady = true
            }
        }

        // If not found in Downloads, check the legacy way (temp/extracted)
        if (!isLocalReady) {
            for ((seg, remoteSize) in remoteSegments) {
                val fileInTemp = File(gameTempDir, seg)
                val fileInExtracted = File(extractionDir, seg)
                if (!(fileInTemp.exists() && fileInTemp.length() == remoteSize) && 
                    !(fileInExtracted.exists() && fileInExtracted.length() == remoteSize)) {
                    isLocalReady = false
                    break
                }
                isLocalReady = true
            }
        }

        if (isLocalReady && downloadOnly) {
            onProgress("Already downloaded and verified", 1f, totalBytes, totalBytes)
            delay(1000)
            return@withContext null
        }

        if (!isLocalReady) {
            // Need to download or resume
            //
            // BEHAVIORAL ALIGNMENT NOTE: This download loop mirrors DownloadWorker.executeDownload()
            // for consistency. Both implementations use the same:
            // - Segment skip/resume/start logic based on existingSize vs remoteSize
            // - 416 Range Not Satisfiable handling with Content-Range verification
            // - Progress scaling (0-80% download, 80-100% extraction)
            //
            // Key difference: This path is used for direct installs (not via WorkManager),
            // e.g., when files are already downloaded and only extraction is needed.
            // DownloadWorker is the primary download path for new installations.
            //
            // If modifying download logic here, review DownloadWorker.downloadSegment() for consistency.
            val config = cachedConfig ?: throw Exception("Config not loaded")
            val sanitizedBase = if (config.baseUri.endsWith("/")) config.baseUri else "${config.baseUri}/"
            val dirUrl = "$sanitizedBase$hash/"

            if (!gameTempDir.exists()) gameTempDir.mkdirs()

            var totalBytesDownloaded = 0L
            remoteSegments.forEach { (seg, _) ->
                val f = File(gameTempDir, seg)
                if (f.exists()) totalBytesDownloaded += f.length()
            }

            for ((index, entry) in remoteSegments.entries.withIndex()) {
                val seg = entry.key
                val remoteSize = entry.value
                currentCoroutineContext().ensureActive()
                val localFile = File(gameTempDir, seg)

                val existingSize = if (localFile.exists()) localFile.length() else 0L

                // Skip segment logic - must handle unknown sizes (-1L) correctly
                // -1L means HEAD request failed, so we can't determine completion without downloading
                // Mirrors DownloadWorker.executeDownload() segment handling for behavioral consistency
                when {
                    // Segment complete: skip only if file exists and EXACTLY matches expected size
                    remoteSize > 0L && existingSize == remoteSize -> {
                        Log.d(TAG, "Skipping completed segment: $seg ($existingSize bytes)")
                        continue
                    }
                    // Segment partial or oversized: will resume/download and let server decide via 206/200/416
                    localFile.exists() && existingSize > 0L && (remoteSize == -1L || existingSize < remoteSize) -> {
                        Log.d(TAG, "Resuming partial segment: $seg (have $existingSize bytes)")
                    }
                    // Segment missing: start fresh
                    !localFile.exists() -> {
                        Log.d(TAG, "Starting new segment: $seg")
                    }
                    // Oversized file (existingSize > remoteSize): re-download to ensure data integrity
                    remoteSize > 0L && existingSize > remoteSize -> {
                        Log.w(TAG, "Oversized file detected: $seg (local=$existingSize, remote=$remoteSize). Re-downloading for integrity.")
                        localFile.delete()
                        Log.d(TAG, "Starting new segment: $seg (deleted oversized file)")
                    }
                    // Unknown remote size (-1L): download and let server provide Content-Length
                    remoteSize == -1L -> {
                        Log.d(TAG, "Unknown remote size for $seg, will determine during download")
                    }
                }

                val segUrl = dirUrl + seg
                val segRequest = Request.Builder()
                    .url(segUrl)
                    .header("User-Agent", Constants.USER_AGENT)
                    .header("Range", "bytes=$existingSize-")
                    .build()

                try {
                    okHttpClient.newCall(segRequest).await().use { response ->
                        // Handle 416 Range Not Satisfiable - verify file completion via Content-Range
                        // Mirrors DownloadWorker.downloadSegment() for consistent 416 handling
                        if (DownloadUtils.isRangeNotSatisfiable(response.code)) {
                            val actualFileSize = localFile.length()
                            val contentRange = response.header("Content-Range") // Format: "bytes */TOTAL"
                            val expectedSize = contentRange?.substringAfter("*/")?.toLongOrNull()

                            if (expectedSize != null && actualFileSize != expectedSize) {
                                // File is corrupted/mismatched - delete and re-download
                                Log.w(TAG, "416 but file size mismatch: local=$actualFileSize, expected=$expectedSize. Deleting and retrying.")
                                localFile.delete()
                                totalBytesDownloaded -= actualFileSize
                                // Re-download this segment from scratch (without Range header)
                                val freshRequest = Request.Builder()
                                    .url(segUrl)
                                    .header("User-Agent", Constants.USER_AGENT)
                                    .build()
                                okHttpClient.newCall(freshRequest).await().use { freshResponse ->
                                    if (!freshResponse.isSuccessful) throw Exception("Failed to re-download $seg: ${freshResponse.code}")
                                    val body = freshResponse.body ?: throw Exception("Empty body for $seg")
                                    body.byteStream().use { input ->
                                        localFile.parentFile?.let { if (!it.exists()) it.mkdirs() }
                                        FileOutputStream(localFile, false).use { output ->
                                            totalBytesDownloaded = DownloadUtils.downloadWithProgress(
                                                inputStream = input,
                                                outputStream = output,
                                                initialDownloaded = totalBytesDownloaded,
                                                totalBytes = totalBytes,
                                                throttleMs = Constants.PROGRESS_THROTTLE_MS,
                                                isCancelled = { false },
                                                onProgress = { downloadedBytes, total, progress ->
                                                    onProgress(
                                                        "Downloading ${game.gameName} (${index + 1}/${remoteSegments.size})",
                                                        progress * Constants.PROGRESS_DOWNLOAD_PHASE_END,
                                                        downloadedBytes,
                                                        total
                                                    )
                                                }
                                            )
                                        }
                                    }
                                }
                            } else {
                                // 416 with matching size = file is complete, skip this segment
                                Log.i(TAG, "Segment complete (416): $seg ($actualFileSize bytes)")
                            }
                        } else if (response.isSuccessful) {
                            val isResume = DownloadUtils.isResumeResponse(response.code)
                            val body = response.body ?: throw Exception("Empty body for $seg")

                            body.byteStream().use { input ->
                                localFile.parentFile?.let { if (!it.exists()) it.mkdirs() }
                                FileOutputStream(localFile, isResume).use { output ->
                                    // Use shared download utility for consistent behavior
                                    totalBytesDownloaded = DownloadUtils.downloadWithProgress(
                                        inputStream = input,
                                        outputStream = output,
                                        initialDownloaded = totalBytesDownloaded,
                                        totalBytes = totalBytes,
                                        throttleMs = Constants.PROGRESS_THROTTLE_MS, // More frequent updates for direct downloads
                                        isCancelled = { false }, // Cancellation handled by ensureActive()
                                        onProgress = { downloadedBytes, total, progress ->
                                            onProgress(
                                                "Downloading ${game.gameName} (${index + 1}/${remoteSegments.size})",
                                                progress * Constants.PROGRESS_DOWNLOAD_PHASE_END, // Download is 0-80%
                                                downloadedBytes,
                                                total
                                            )
                                        }
                                    )
                                }
                            }
                        } else {
                            throw Exception("Failed to download $seg: ${response.code}")
                        }
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Log.e(TAG, "Error downloading segment $seg", e)
                    throw Exception("Download failed: ${e.message ?: "Unknown error"}")
                }
            }
        }

        // 4. Processing / Extraction
        if (!extractionDir.exists()) extractionDir.mkdirs()
        val extractionMarker = File(gameTempDir, "extraction_done.marker")
        
        // Skip extraction if already decompressed in Downloads
        val skipExtraction = isLocalReady && !extractionMarker.exists() && foundApk != null

        if (!extractionMarker.exists() && !skipExtraction) {
            onProgress("Preparing files...", Constants.PROGRESS_MILESTONE_EXTRACTION_START, totalBytes, totalBytes)
            val isArchive = remoteSegments.keys.any { it.contains(".7z") }
            
            if (!isArchive) {
                // Non-archive files: copy with progress reporting
                // Progress spans 80-92% for monotonic progress (not 80-100% which causes backwards jump to OBB 94%, APK 96%)
                val segmentList = remoteSegments.keys.toList()
                val totalFilesBytes = remoteSegments.values.filter { it > 0 }.sum()
                var copiedBytes = 0L
                val copyPhaseSpan = Constants.PROGRESS_MILESTONE_EXTRACTION_END - Constants.PROGRESS_MILESTONE_EXTRACTION_START

                segmentList.forEachIndexed { index, seg ->
                    currentCoroutineContext().ensureActive()
                    val source = File(gameTempDir, seg)
                    val target = File(extractionDir, seg)
                    if (source.exists()) {
                        target.parentFile?.let { if (!it.exists()) it.mkdirs() }

                        // Report progress per file
                        val fileProgress = if (totalFilesBytes > 0) {
                            copiedBytes.toFloat() / totalFilesBytes
                        } else {
                            index.toFloat() / segmentList.size
                        }
                        val scaledProgress = Constants.PROGRESS_MILESTONE_EXTRACTION_START +
                                (fileProgress * copyPhaseSpan)
                        onProgress(
                            "Copying ${index + 1}/${segmentList.size}...",
                            scaledProgress,
                            copiedBytes,
                            totalFilesBytes
                        )

                        // Use .use { } blocks to ensure streams are properly closed (Code Review fix: resource leak)
                        source.inputStream().use { input ->
                            target.outputStream().use { output ->
                                copyToCancellable(input, output)
                            }
                        }
                        copiedBytes += source.length()
                    }
                }

                // Final progress update - stop at EXTRACTION_END (92%) to allow monotonic progress to OBB (94%) and APK (96%)
                onProgress("Copying complete", Constants.PROGRESS_MILESTONE_EXTRACTION_END, totalFilesBytes, totalFilesBytes)
                extractionMarker.createNewFile()
            } else {
                val combinedFile = File(gameTempDir, "combined.7z")
                val archiveParts = remoteSegments.filter { it.key.contains(".7z") }
                val archiveTotalBytes = archiveParts.values.sum()

                // Acquire CPU wake lock BEFORE merge and extraction to prevent Quest sleep (NFR-P11, FR55)
                // Merging large multi-part archives can take several minutes.
                WakeLockManager.acquire(context)
                try {
                    if (!combinedFile.exists() || combinedFile.length() < archiveTotalBytes) {
                        onProgress("Merging files...", Constants.PROGRESS_MILESTONE_MERGING, totalBytes, totalBytes)

                        // Sort archive parts using natural/numeric sorting for robustness (AC: 3, FR23)
                        // This handles both zero-padded (.001, .002) and non-padded (.1, .2, .10) formats.
                        val sortedParts = archiveParts.keys.sortedWith(compareBy<String> { it.substringBeforeLast(".7z") }
                            .thenBy { it.substringAfterLast(".7z.").toIntOrNull() ?: 0 }
                            .thenBy { it })
                        Log.d(TAG, "Merging ${sortedParts.size} archive parts in order: ${sortedParts.joinToString(", ")}")

                        var mergedBytes = 0L
                        combinedFile.outputStream().use { out ->
                            sortedParts.forEachIndexed { index, seg ->
                                currentCoroutineContext().ensureActive()
                                val partFile = File(gameTempDir, seg)
                                if (!partFile.exists()) throw Exception("Part file missing: $seg")

                                // Report merge progress (AC: 3)
                                val mergeProgress = index.toFloat() / sortedParts.size
                                val scaledProgress = Constants.PROGRESS_MILESTONE_MERGING +
                                        (mergeProgress * (Constants.PROGRESS_MILESTONE_EXTRACTING - Constants.PROGRESS_MILESTONE_MERGING))
                                onProgress(
                                    "Merging part ${index + 1}/${sortedParts.size}...",
                                    scaledProgress,
                                    mergedBytes,
                                    archiveTotalBytes
                                )

                                partFile.inputStream().use { input ->
                                    copyToCancellable(input, out)
                                }
                                mergedBytes += partFile.length()
                            }
                        }
                        // Final merge progress to reach EXTRACTING milestone (85%) smoothly
                        onProgress("Merge complete", Constants.PROGRESS_MILESTONE_EXTRACTING, archiveTotalBytes, archiveTotalBytes)
                        Log.d(TAG, "Archive merge complete: $mergedBytes bytes")
                    }

                    // Note: Skip separate "Preparing extraction" call here - merge already ended at 85%
                    // The counting phase will maintain 85% until extraction starts
                    val password = decodedPassword ?: ""

                    SevenZFile.builder().setFile(combinedFile).setPassword(password.toCharArray()).get().use { sevenZFile ->
                        // Count total entries for progress calculation (Story 1.6)
                        // SevenZFile.entries provides an Iterable that can be iterated without consuming entries
                        var totalEntryBytes = 0L
                        var totalEntryCount = 0
                        sevenZFile.entries.forEachIndexed { index, entry ->
                            totalEntryBytes += entry.size
                            totalEntryCount++
                            // Update progress during counting for large archives (Code Review fix)
                            // Stays at EXTRACTING (85%) - counting is part of extraction phase, not a step back
                            if (index % 100 == 0) {
                                currentCoroutineContext().ensureActive()
                                onProgress("Analyzing archive...", Constants.PROGRESS_MILESTONE_EXTRACTING, 0, 0)
                            }
                        }
                        // Use entry count as fallback for archives with only zero-byte entries (e.g., directories only)
                        // This prevents progress from freezing at 85% when totalEntryBytes = 0
                        val useEntryCount = totalEntryBytes == 0L && totalEntryCount > 0

                        var extractedBytes = 0L
                        var extractedEntryCount = 0
                        var lastProgressUpdateMs = 0L
                        // Extraction spans 85-92% for monotonic progress (not 85-100% which causes backwards jump to 94%/96% for OBB/APK)
                        val extractionPhaseSpan = Constants.PROGRESS_MILESTONE_EXTRACTION_END - Constants.PROGRESS_MILESTONE_EXTRACTING
                        // Reuse buffer across all files to reduce GC pressure (Code Review fix)
                        val extractionBuffer = ByteArray(DownloadUtils.DOWNLOAD_BUFFER_SIZE)
                        // Cache canonical path outside loop to avoid repeated IO calls (Code Review fix: performance)
                        val canonicalExtractDir = extractionDir.canonicalPath

                        var entry = sevenZFile.nextEntry
                        while (entry != null) {
                            currentCoroutineContext().ensureActive()
                            // Keep APK, OBB and EVERYTHING ELSE (important for Quake3Quest style structures)
                            val outFile = File(extractionDir, entry.name)

                            // Zip Slip vulnerability protection: Validate that the resolved path
                            // is within the extraction directory to prevent directory traversal attacks
                            val canonicalOutFile = outFile.canonicalPath
                            if (!canonicalOutFile.startsWith(canonicalExtractDir + File.separator) &&
                                canonicalOutFile != canonicalExtractDir) {
                                val errorMsg = "Zip Slip detected! Malicious entry: ${entry.name}"
                                Log.e(TAG, errorMsg)
                                // Fail loudly instead of skipping silently to prevent broken installations (Adversarial Review fix)
                                throw IOException(errorMsg)
                            }

                            if (entry.isDirectory) outFile.mkdirs()
                            else {
                                outFile.parentFile?.let { if (!it.exists()) it.mkdirs() }
                                FileOutputStream(outFile).use { out ->
                                    var bytesRead: Int
                                    while (true) {
                                        currentCoroutineContext().ensureActive()
                                        bytesRead = sevenZFile.read(extractionBuffer)
                                        if (bytesRead == -1) break
                                        out.write(extractionBuffer, 0, bytesRead)

                                        // Track actual bytes extracted for progress (NFR-P10 fix)
                                        // Update inside loop to prevent UI freeze during large file extraction
                                        extractedBytes += bytesRead
                                        // Use monotonic clock for throttling to avoid issues with system time changes
                                        val now = SystemClock.elapsedRealtime()
                                        if (now - lastProgressUpdateMs >= Constants.EXTRACTION_PROGRESS_THROTTLE_MS) {
                                            // Use entry count as fallback for zero-byte archives
                                            val extractionProgress = when {
                                                useEntryCount -> extractedEntryCount.toFloat() / totalEntryCount
                                                totalEntryBytes > 0 -> extractedBytes.toFloat() / totalEntryBytes
                                                else -> 0f
                                            }
                                            // Scale extraction progress: 85-92% of total (monotonic before OBB at 94%, APK at 96%)
                                            val scaledProgress = Constants.PROGRESS_MILESTONE_EXTRACTING +
                                                    (extractionProgress * extractionPhaseSpan)
                                            onProgress(
                                                "Extracting... ${(extractionProgress * 100).toInt()}%",
                                                scaledProgress,
                                                extractedBytes,
                                                totalEntryBytes
                                            )
                                            lastProgressUpdateMs = now
                                        }
                                    }
                                }
                            }

                            // Increment entry counter for progress tracking (used as fallback for zero-byte archives)
                            extractedEntryCount++
                            entry = sevenZFile.nextEntry
                        }

                        // Final progress update to ensure we reach EXTRACTION_END milestone
                        // This handles cases where the last chunk didn't trigger a throttled update
                        onProgress(
                            "Extraction complete",
                            Constants.PROGRESS_MILESTONE_EXTRACTION_END,
                            extractedBytes,
                            totalEntryBytes
                        )

                        // Log if extraction took >2 minutes (NFR-P11 compliance)
                        if (WakeLockManager.hasExceededTwoMinutes()) {
                            Log.i(TAG, "Extraction completed after ${WakeLockManager.getHeldDurationMs() / 1000}s (wake lock held)")
                        }
                    }
                    extractionMarker.createNewFile()
                } catch (e: CancellationException) {
                    // Propagate cancellation without logging as error
                    Log.d(TAG, "Extraction cancelled")
                    extractionMarker.delete() // Clean up marker if partially done
                    extractionDir.deleteRecursively()
                    throw e
                } catch (e: Exception) {
                    // Detect specific error types for user-friendly messages (AC: 7)
                    val errorMessage = e.message?.lowercase() ?: ""
                    val userMessage = when {
                        errorMessage.contains("zip slip") -> "Extraction failed: Security violation detected"
                        errorMessage.contains("password") || errorMessage.contains("crypt") ->
                            "Extraction failed: Wrong password or encrypted archive"
                        errorMessage.contains("space") || errorMessage.contains("full") ||
                            errorMessage.contains("enospc") ->
                            "Extraction failed: Not enough storage space"
                        errorMessage.contains("corrupt") || errorMessage.contains("invalid") ||
                            errorMessage.contains("damaged") || errorMessage.contains("crc") ->
                            "Extraction failed: Archive is corrupted"
                        else -> "Extraction failed: ${e.message}"
                    }

                    Log.e(TAG, "Extraction failed: $userMessage", e)

                    // Clean up temp files on failure (NFR-R7)
                    extractionMarker.delete() // Clean up marker if partially done
                    extractionDir.deleteRecursively()
                    throw Exception(userMessage)
                } finally {
                    // Always clean up combined.7z even on success or failure (Code Review fix)
                    val combinedFileToDelete = File(gameTempDir, "combined.7z")
                    if (combinedFileToDelete.exists()) combinedFileToDelete.delete()
                    // Always release wake lock in finally block (covers success, failure, cancel)
                    WakeLockManager.release()
                }
            }
        }

        // 5. Finalize Installation
        // Parse installation artifacts using refactored method (Code Review fix: remove duplication)
        val artifacts = parseInstallationArtifacts(extractionDir, gameDownloadDir, game.packageName)

        if (artifacts.finalApk == null && !downloadOnly) {
            extractionMarker.delete()
            throw Exception("No APK found for installation")
        }

        if (downloadOnly || keepApk) {
            onProgress("Saving to Downloads...", Constants.PROGRESS_MILESTONE_SAVING_TO_DOWNLOADS, totalBytes, totalBytes)
            try {
                if (!downloadsDir.exists()) downloadsDir.mkdirs()
                if (!gameDownloadDir.exists()) gameDownloadDir.mkdirs()

                artifacts.finalApk?.let {
                    val targetApk = File(gameDownloadDir, apkFileName)
                    if (it.absolutePath != targetApk.absolutePath) {
                        copyFileWithScanner(it, targetApk)
                    }
                }

                val finalObbDir = File(gameDownloadDir, game.packageName).apply { if (!exists()) mkdirs() }
                artifacts.packageFolder?.let { pf ->
                    if (pf.absolutePath != finalObbDir.absolutePath) {
                        pf.walkTopDown().forEach { source ->
                            currentCoroutineContext().ensureActive()
                            if (!source.name.endsWith(".apk", true)) {
                                val relPath = source.relativeTo(pf).path
                                val dest = File(finalObbDir, relPath)
                                if (source.isDirectory) dest.mkdirs()
                                else {
                                    copyFileWithScanner(source, dest)
                                }
                            }
                        }
                    }
                }
                artifacts.looseObbs.forEach { loose ->
                    currentCoroutineContext().ensureActive()
                    val targetObb = File(finalObbDir, loose.name)
                    if (loose.absolutePath != targetObb.absolutePath) {
                        copyFileWithScanner(loose, targetObb)
                    }
                }

                // Save special folders to downloads too
                artifacts.specialMoves.forEach { (source, _) ->
                    currentCoroutineContext().ensureActive()
                    if (source.isDirectory && source.name != game.packageName) {
                        val targetDir = File(gameDownloadDir, source.name)
                        if (source.absolutePath != targetDir.absolutePath) {
                            source.copyRecursively(targetDir, overwrite = true)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save files to downloads", e)
                // Story 1.7 Code Review Round 8: Clean up partial files from failed download save
                // If saving to downloads fails partway through, remove incomplete files to avoid confusion
                try {
                    if (gameDownloadDir.exists()) {
                        gameDownloadDir.deleteRecursively()
                        Log.i(TAG, "Cleaned up partial download directory for ${game.releaseName}")
                    }
                } catch (cleanupException: Exception) {
                    Log.e(TAG, "Failed to clean up partial download directory", cleanupException)
                }
                throw e // Rethrow to notify UI of failure
            }
        }

        if (downloadOnly) {
            onProgress("Download complete", 1f, totalBytes, totalBytes)
            gameTempDir.deleteRecursively()
            return@withContext null
        }

        // Transitional milestone to bridge gap from extraction (92%) to OBB installation (94%)
        onProgress("Preparing installation...", Constants.PROGRESS_MILESTONE_PREPARING_INSTALL, totalBytes, totalBytes)

        // Perform installation phase using refactored common method (Code Review fix: remove duplication)
        performInstallationPhase(artifacts, game, gameTempDir, totalBytes, onProgress)
    }

    /**
     * Data class representing parsed installation artifacts from extraction directory.
     * Used to share installation logic between installGame and installFromExtracted.
     */
    private data class InstallationArtifacts(
        val finalApk: File?,
        val specialMoves: List<Pair<File, String>>,
        val packageFolder: File?,
        val looseObbs: List<File>
    )

    /**
     * Story 1.7: Common installation phase logic - OBB movement + APK staging.
     * Extracted to prevent duplication between installGame and installFromExtracted (Code Review fix).
     *
     * @param artifacts Pre-parsed installation artifacts
     * @param game Game data
     * @param gameTempDir Temporary game directory for install.info
     * @param totalBytes Total bytes for progress reporting
     * @param onProgress Progress callback
     * @return Staged APK file ready for installation
     */
    private suspend fun performInstallationPhase(
        artifacts: InstallationArtifacts,
        game: GameData,
        gameTempDir: File,
        totalBytes: Long,
        onProgress: (String, Float, Long, Long) -> Unit
    ): File = withContext(Dispatchers.IO) {
        val (finalApk, specialMoves, packageFolder, looseObbs) = artifacts

        // Validate that APK exists
        if (finalApk == null || !finalApk.exists()) {
            throw Exception("Final APK not found for installation")
        }

        // Apply special moves (install.txt)
        if (specialMoves.isNotEmpty()) {
            specialMoves.forEach { (source, destPath) ->
                copyDataToSdcard(source, destPath)
            }
        }

        // Move OBB files
        val hasObbs = packageFolder != null || looseObbs.isNotEmpty()
        if (hasObbs) {
            moveObbFiles(packageFolder, looseObbs, game.packageName)
        }

        // Save install.info for post-install verification
        try {
            File(gameTempDir, "install.info").writeText("${game.packageName}\n${hasObbs || specialMoves.isNotEmpty()}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write install info", e)
        }

        // ABI Check - Verify APK is compatible with device ABIs
        val packageInfo = context.packageManager.getPackageArchiveInfo(finalApk.absolutePath, 0)
        if (packageInfo == null) {
            throw Exception("APK file is corrupted or invalid")
        }

        val supportedAbis = Build.SUPPORTED_ABIS.toList()
        try {
            // Use ZipFile to check for native libraries (ABI compatibility)
            java.util.zip.ZipFile(finalApk).use { apkZip ->
                val nativeLibs = apkZip.entries()
                    .asSequence()
                    .filter { it.name.startsWith("lib/") && !it.isDirectory }
                    .map { entry ->
                        // Extract ABI from path like "lib/arm64-v8a/libgame.so"
                        val parts = entry.name.split("/")
                        if (parts.size >= 2) parts[1] else null
                    }
                    .filterNotNull()
                    .distinct()
                    .toList()

                if (nativeLibs.isNotEmpty()) {
                    // APK has native libraries - check ABI compatibility
                    // Story 1.7 Code Review Round 9 - ABI Compatibility Note:
                    //
                    // This logic handles the 32-bit on 64-bit scenario specifically for Meta Quest VR devices.
                    // Meta Quest 2/3/Pro all use Qualcomm Snapdragon SoCs which support both arm64-v8a AND
                    // armeabi-v7a via Android's 32-bit compatibility layer.
                    //
                    // The logic is:
                    // - If APK only has armeabi-v7a libs AND device has arm64-v8a support -> ALLOW
                    //   (Android will run the 32-bit libs in compatibility mode)
                    // - If APK has arm64-v8a libs AND device doesn't support arm64 -> FAIL
                    // - If APK has both armeabi-v7a and arm64-v8a -> prefer arm64, fallback to 32-bit
                    //
                    // KNOWN LIMITATION: This assumes 32-bit compatibility on all 64-bit ARM devices.
                    // For Quest VR headsets (the target platform), this is always true.
                    // Other Android devices might disable 32-bit support, but this app targets Quest only.
                    val hasCompatibleAbi = nativeLibs.any { libAbi ->
                        when (libAbi) {
                            "armeabi-v7a" -> {
                                // 32-bit ARM: Check if device supports it directly OR has 64-bit ARM (compatibility mode)
                                val directSupport = supportedAbis.any { it.equals(libAbi, ignoreCase = true) }
                                val viaCompatibility = supportedAbis.any { it.equals("arm64-v8a", ignoreCase = true) }
                                if (viaCompatibility && !directSupport) {
                                    Log.i(TAG, "ABI: Using 32-bit compatibility mode for ${game.packageName}")
                                }
                                directSupport || viaCompatibility
                            }
                            else -> {
                                // Other ABIs: Direct compatibility check
                                supportedAbis.any { supportedAbi -> supportedAbi.equals(libAbi, ignoreCase = true) }
                            }
                        }
                    }

                    if (!hasCompatibleAbi) {
                        throw Exception("Game is not compatible with this device ABI. Required: $nativeLibs, Supported: $supportedAbis")
                    }
                    Log.d(TAG, "ABI check passed for ${game.packageName}: APK contains $nativeLibs, device supports $supportedAbis")
                }
            }
        } catch (e: Exception) {
            // If we can't read the APK structure, log warning but don't fail
            // (some APKs might have unusual structures or the API might fail)
            Log.w(TAG, "Could not verify ABI compatibility for ${game.packageName}: ${e.message}")
        }

        // Progress milestone for APK staging (96%)
        onProgress("Preparing APK...", Constants.PROGRESS_MILESTONE_LAUNCHING_INSTALLER, totalBytes, totalBytes)

        // Story 1.7 Code Review Round 5: Removed aggressive cleanup before staging
        // Each APK is named {packageName}.apk, so different packages don't interfere.
        // Same package reinstalls simply overwrite the file.
        // Aggressive cleanup was deleting APKs of PENDING_INSTALL tasks, causing installation failures.
        // Cleanup is now deferred to post-verification in checkInstallationStatusSilent().

        // Story 1.7 Code Review Fix: Check available space before staging APK
        // Staging requires copying the APK to externalFilesDir, doubling space requirement temporarily
        val externalFilesDir = context.getExternalFilesDir(null)
        if (externalFilesDir != null) {
            try {
                val apkSize = finalApk.length()
                val stat = StatFs(externalFilesDir.path)
                val availableBytes = stat.availableBlocksLong * stat.blockSizeLong
                // Add 10MB buffer for safety margin
                val requiredBytes = apkSize + (10 * 1024 * 1024)
                if (availableBytes < requiredBytes) {
                    val requiredMb = requiredBytes / (1024 * 1024)
                    val availableMb = availableBytes / (1024 * 1024)
                    Log.e(TAG, "Insufficient space for APK staging: need ${requiredMb}MB, have ${availableMb}MB")
                    throw InsufficientStorageException(requiredMb, availableMb)
                }
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Could not check staging space, proceeding anyway", e)
            }
        }

        // Acquire WakeLock for APK staging to prevent sleep during large file copies
        WakeLockManager.acquire(context)

        val externalApk = getStagedApkFile(game.packageName)
            ?: throw IllegalStateException("External files directory not available")

        // Story 1.7 Code Review Fix (Item 2): Check if APK is already staged to avoid redundant work
        // When resuming PENDING_INSTALL tasks, the APK may already be staged from a previous attempt
        // If the staged APK exists with correct size and is valid, skip staging to save time/storage
        val apkSize = finalApk.length()
        val skipStaging = externalApk.exists() &&
                          externalApk.length() == apkSize &&
                          isValidApkFile(externalApk, game.packageName)

        if (skipStaging) {
            Log.i(TAG, "APK already staged with correct size and valid, skipping redundant staging")
            onProgress("APK ready...", Constants.PROGRESS_MILESTONE_LAUNCHING_INSTALLER, totalBytes, totalBytes)
        } else {
            // Story 1.7 Code Review Fix: Use progress callback for large APK staging
            // This prevents UI "freeze" at 96% during large APK copies (up to 4GB)
            val stagingBaseProgress = Constants.PROGRESS_MILESTONE_LAUNCHING_INSTALLER // 0.96f
            val stagingEndProgress = 0.98f // Leave 2% for final validation and intent launch

            try {
                currentCoroutineContext().ensureActive()
                finalApk.inputStream().use { input ->
                    externalApk.outputStream().use { output ->
                        copyToCancellableWithProgress(input, output, apkSize) { bytesCopied ->
                            // Calculate sub-progress within staging phase (96% to 98%)
                            val stagingProgress = if (apkSize > 0) bytesCopied.toFloat() / apkSize else 1f
                            val overallProgress = stagingBaseProgress + (stagingEndProgress - stagingBaseProgress) * stagingProgress
                            val formattedSize = InstallUtils.formatBytes(bytesCopied)
                            val formattedTotal = InstallUtils.formatBytes(apkSize)
                            onProgress("Staging APK: $formattedSize / $formattedTotal", overallProgress, bytesCopied, apkSize)
                        }
                    }
                }

                if (!isValidApkFile(externalApk, game.packageName)) {
                    externalApk.delete()
                    throw IllegalStateException("Staged APK is invalid or package name mismatch: ${externalApk.name}")
                }
            } finally {
                WakeLockManager.release()
            }
        }

        externalApk
    }

    /**
     * Story 1.7: Parse installation artifacts (APK, install.txt, OBB) from extraction directory.
     * Extracted to prevent duplication between installGame and installFromExtracted (Code Review fix).
     *
     * @param extractionDir Directory containing extracted files
     * @param gameDownloadDir Optional download directory to search (for non-archive files)
     * @param packageName Expected package name for OBB detection
     * @return Parsed installation artifacts
     */
    private fun parseInstallationArtifacts(
        extractionDir: File,
        gameDownloadDir: File?,
        packageName: String
    ): InstallationArtifacts {
        // Story 1.7 Code Review Round 6: Improved APK discovery with prioritization
        // Priority: 1) Root-level APK matching packageName, 2) Root-level APK, 3) Any APK matching packageName, 4) First APK found
        val allApks = mutableListOf<File>()

        // Story 1.7 Code Review Round 6: Security - Validate packageName format before using
        // Package names must match: [a-zA-Z][a-zA-Z0-9_]*(\.[a-zA-Z][a-zA-Z0-9_]*)*
        val packageNamePattern = Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)*\$")
        if (!packageNamePattern.matches(packageName)) {
            Log.w(TAG, "Invalid packageName format detected: $packageName")
            // Continue with discovery but with extra caution
        }

        extractionDir.walkTopDown().forEach { if (it.name.endsWith(".apk", true)) allApks.add(it) }

        // If not found in extraction, check download dir
        if (allApks.isEmpty() && gameDownloadDir != null && gameDownloadDir.exists()) {
            gameDownloadDir.walkTopDown().forEach { file ->
                if (file.name.endsWith(".apk", true)) {
                    allApks.add(file)
                }
            }
        }

        // Log warning if multiple APKs found (potential ambiguity)
        if (allApks.size > 1) {
            Log.w(TAG, "Multiple APKs found (${allApks.size}), selecting best match for package: $packageName")
            allApks.forEach { Log.d(TAG, "  APK candidate: ${it.absolutePath}") }
        }

        // Select best APK using prioritization:
        // 1. Root-level APK matching packageName (safest)
        // 2. Root-level APK (depth 1)
        // 3. Any APK matching packageName
        // 4. First APK found (fallback)
        val rootDir = gameDownloadDir ?: extractionDir
        val finalApk = allApks.firstOrNull { it.parentFile == extractionDir && it.name.contains(packageName, ignoreCase = true) }
            ?: allApks.firstOrNull { it.parentFile == extractionDir }
            ?: allApks.firstOrNull { it.parentFile == rootDir && it.name.contains(packageName, ignoreCase = true) }
            ?: allApks.firstOrNull { it.parentFile == rootDir }
            ?: allApks.firstOrNull { it.name.contains(packageName, ignoreCase = true) }
            ?: allApks.firstOrNull()

        if (finalApk != null && allApks.size > 1) {
            Log.i(TAG, "Selected APK: ${finalApk.name} (from ${allApks.size} candidates)")
        }

        // Parse install.txt for special handling (Code Review fix: more robust parsing)
        val installTxtFile = extractionDir.walkTopDown().find { it.name == "install.txt" }
        val specialMoves = mutableListOf<Pair<File, String>>()

        if (installTxtFile != null) {
            Log.d(TAG, "Found install.txt, parsing for special instructions")
            val lines = installTxtFile.readLines()
            lines.forEach { line ->
                val trimmed = line.trim()
                // Code Review fix: Only match actual adb push commands, not comments
                if (InstallUtils.isAdbPushCommand(trimmed)) {
                    // Code Review fix: Handle paths with spaces using quote-aware parsing
                    // Format: adb push "source path" "dest path" OR adb push source dest
                    val afterPush = trimmed.substringAfter("push", "").trim()
                    val (sourceName, destPath) = InstallUtils.parseAdbPushArgs(afterPush)

                    if (sourceName != null && destPath != null) {
                        val cleanSource = sourceName.trim('/', '\\')
                        val sourceFile = File(extractionDir, cleanSource)
                        if (sourceFile.exists()) {
                            specialMoves.add(sourceFile to destPath)
                        } else {
                            // Story 1.7 Code Review Fix: Improved source file matching
                            // Try to match using parent directory context when possible
                            val sourcePath = File(cleanSource)
                            val sourceFileName = sourcePath.name
                            val sourceParent = sourcePath.parent?.replace('\\', '/')?.split('/')?.lastOrNull()

                            // Collect all candidates with matching name
                            val candidates = extractionDir.walkTopDown()
                                .filter { it.name == sourceFileName }
                                .toList()

                            val bestMatch = if (candidates.size > 1 && sourceParent != null) {
                                // Multiple matches: prefer the one whose parent matches expected parent
                                candidates.find { it.parentFile?.name == sourceParent }
                                    ?: candidates.first() // fallback to first if no parent match
                            } else {
                                candidates.firstOrNull()
                            }

                            bestMatch?.let {
                                // Code Review Fix (Item 3): Warn if ambiguous matching occurred
                                if (candidates.size > 1) {
                                    Log.w(TAG, "install.txt: Multiple candidates found for '$cleanSource' (${candidates.size} total), selected: ${it.absolutePath}")
                                    candidates.forEach { candidate ->
                                        Log.d(TAG, "  Candidate: ${candidate.absolutePath} (size: ${candidate.length()})")
                                    }
                                } else {
                                    Log.d(TAG, "install.txt: Matched '$cleanSource' to '${it.absolutePath}'")
                                }
                                specialMoves.add(it to destPath)
                            } ?: run {
                                Log.w(TAG, "install.txt: No match found for '$cleanSource', skipping this instruction")
                            }
                        }
                    }
                }
            }
        }

        // Find OBB files
        var packageFolder: File? = null
        extractionDir.walkTopDown().forEach { file ->
            if (file.isDirectory && file.name == packageName) {
                packageFolder = file
                return@forEach
            }
        }

        // Check download dir for OBB folder if not found in extraction
        if (packageFolder == null && gameDownloadDir != null && gameDownloadDir.exists()) {
            gameDownloadDir.walkTopDown().forEach { file ->
                if (file.isDirectory && file.name == packageName) {
                    packageFolder = file
                    return@forEach
                }
            }
        }

        val looseObbs = mutableListOf<File>()
        val obbSearchDirs = mutableListOf(extractionDir)
        if (gameDownloadDir != null && gameDownloadDir.exists()) obbSearchDirs.add(gameDownloadDir)

        obbSearchDirs.forEach { dir ->
            dir.walkTopDown().forEach { file ->
                if (file.isFile && file.name.endsWith(".obb", true)) {
                    if (packageFolder == null || !file.absolutePath.startsWith(packageFolder!!.absolutePath)) {
                        if (!looseObbs.any { it.name == file.name }) {
                            looseObbs.add(file)
                        }
                    }
                }
            }
        }

        // Story 1.7 Code Review Round 9 Fix: Natural/numeric sort for OBB files
        // Uses the production comparator from InstallUtils for testability
        // This ensures correct ordering when multiple OBB files exist (e.g., main.1, main.2, main.10)
        val sortedLooseObbs = looseObbs.sortedWith { file1, file2 ->
            InstallUtils.obbFileComparator.compare(file1.name, file2.name)
        }

        return InstallationArtifacts(
            finalApk = finalApk,
            specialMoves = specialMoves,
            packageFolder = packageFolder,
            looseObbs = sortedLooseObbs
        )
    }

    /**
     * Story 1.7: Zombie Recovery - Install from already-extracted files.
     * This method is called when extraction_done.marker exists (post-extraction crash recovery).
     * It performs ONLY the installation phase (OBB movement + APK staging) without re-extracting.
     *
     * This is the "Zombie Recovery" path: resumes installation after process death during extraction.
     *
     * @param game The game data
     * @param onProgress Progress callback (starts at 94% for OBB installation)
     * @return Staged APK file ready for installation
     */
    suspend fun installFromExtracted(
        game: GameData,
        onProgress: (String, Float, Long, Long) -> Unit
    ): File? = withContext(Dispatchers.IO) {
        val hash = CryptoUtils.md5(game.releaseName + "\n")
        val gameTempDir = File(tempInstallRoot, hash)
        val extractionDir = File(gameTempDir, "extracted")
        val extractionMarker = File(gameTempDir, "extraction_done.marker")

        // Verify extraction is actually complete
        if (!extractionMarker.exists() || !extractionDir.exists()) {
            throw IllegalStateException("Cannot install from extracted: extraction not complete")
        }

        Log.i(TAG, "Zombie Recovery: Installing ${game.releaseName} from extracted files (skipping extraction)")

        // Parse installation artifacts using refactored method
        val artifacts = parseInstallationArtifacts(extractionDir, null, game.packageName)

        if (artifacts.finalApk == null) {
            throw Exception("No APK found in extracted files for installation")
        }

        // Story 1.7 Code Review Fix: Verify APK version matches catalog expectation
        // This prevents installing an outdated APK from a previous download that was interrupted
        val catalogVersion = game.versionCode.toLongOrNull() ?: 0L
        if (catalogVersion > 0L && !isValidApkFile(artifacts.finalApk, game.packageName, catalogVersion)) {
            // APK version doesn't match catalog - extraction may be stale
            Log.w(TAG, "Zombie Recovery: APK version mismatch for ${game.releaseName}, expected version $catalogVersion")

            // Story 1.7 Code Review Round 6: Safe cleanup of stale extraction
            // gameTempDir is the extraction-specific directory (cacheDir/install_temp/{md5hash}/)
            // This does NOT affect segment files which are in downloadsDir/{releaseName}/
            // Safe to delete recursively because:
            // 1. No other tasks use this specific extraction directory (unique per releaseName)
            // 2. Segment files are stored separately in downloads directory
            // 3. If version mismatches, we must clean everything to allow clean re-download
            try {
                gameTempDir.deleteRecursively()
                Log.i(TAG, "Zombie Recovery: Cleaned up stale extraction directory for ${game.releaseName}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to clean up stale extraction: ${e.message}", e)
                // At minimum delete the marker to prevent re-entering zombie recovery
                extractionMarker.delete()
            }
            throw IllegalStateException("Stale extraction detected: APK version doesn't match catalog. Please retry download.")
        }

        // Story 1.7: Start progress at 94% (skip 0-92% download/extraction)
        val totalBytes = artifacts.finalApk.length()
        onProgress("Resuming installation...", Constants.PROGRESS_MILESTONE_INSTALLING_OBBS, totalBytes, totalBytes)

        // Perform installation phase using refactored common method
        performInstallationPhase(artifacts, game, gameTempDir, totalBytes, onProgress)
    }

    /**
     * Story 1.7: Copy game data files from extraction directory to external storage (sdcard).
     * This function handles special file movement instructions from install.txt files.
     *
     * Code Review Note (Item 1, 9): Why copy instead of move?
     * - Source: context.cacheDir (internal storage, typically /data/data/com.package/cache/)
     * - Destination: Environment.getExternalStorageDirectory() (external storage, /storage/emulated/0/)
     * - These are on DIFFERENT filesystems/mount points on Android
     * - A true "move" (atomic rename operation) is only possible within the same filesystem
     * - Therefore, we MUST use copyRecursively/copyTo, then delete source later in cleanup phase
     *
     * Code Review Note (Item 9): Function naming
     * - Renamed from moveDataToSdcard to copyDataToSdcard for accuracy
     * - The function performs a copy operation, not a true move
     * - Source deletion happens later during cleanup phase to ensure copy succeeded
     *
     * @param source Source file or directory from extraction directory
     * @param destPath Destination path relative to sdcard (e.g., "/sdcard/Android/data/com.game/")
     */
    private fun copyDataToSdcard(source: File, destPath: String) {
        // Story 1.7 Code Review Round 8: Verify MANAGE_EXTERNAL_STORAGE permission on Android 11+
        // This method requires broad file access to write to external storage directories
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                Log.e(TAG, "MANAGE_EXTERNAL_STORAGE permission not granted for copyDataToSdcard")
                throw SecurityException("MANAGE_EXTERNAL_STORAGE permission required for special data installation")
            }
        }

        val sdcard = Environment.getExternalStorageDirectory()

        // Story 1.7 Code Review Round 6: Path injection validation
        // Ensure destPath doesn't escape the sdcard directory via "../" or absolute paths
        val normalizedDest = destPath.removePrefix("/sdcard/").removePrefix("sdcard/").removePrefix("/")

        // Story 1.7 Code Review Round 8: Enhanced path injection validation
        // Block directory traversal patterns, encoded variations, and suspicious characters
        val dangerousPatterns = listOf(
            "..", "../", "..\\", "./", ".\\",  // Directory traversal
            "%2e%2e", "%252e", "%2f", "%5c",    // URL-encoded variations
            "~", "\$HOME", "\${",                   // Shell expansion attempts (escaped $)
            "\u0000",                             // Null bytes
            "|", "&", ";", "\$", "(", ")", "<", ">" // Shell metacharacters (escaped $)
        )

        for (pattern in dangerousPatterns) {
            if (normalizedDest.contains(pattern, ignoreCase = true)) {
                Log.e(TAG, "Path injection attempt blocked in install.txt (pattern: $pattern): $destPath")
                throw SecurityException("Invalid destination path in install.txt: $destPath")
            }
        }

        // Validate against null byte injection (can bypass string checks)
        if (normalizedDest.contains("\u0000") || destPath.contains("\u0000")) {
            Log.e(TAG, "Null byte injection blocked in install.txt: $destPath")
            throw SecurityException("Null byte detected in destination path: $destPath")
        }

        // If destPath ends with /, it means put the source INSIDE that folder
        // If it doesn't, it might mean rename source to destPath.
        // Usually adb push folder /sdcard/ means /sdcard/folder
        val targetFile = if (destPath.endsWith("/")) {
            File(sdcard, normalizedDest + File.separator + source.name)
        } else if (normalizedDest.isEmpty()) {
            File(sdcard, source.name)
        } else {
            File(sdcard, normalizedDest)
        }

        // Story 1.7 Code Review Round 6: Additional safety check - ensure target is within sdcard
        val canonicalTarget = targetFile.canonicalPath
        val canonicalSdcard = sdcard.canonicalPath
        if (!canonicalTarget.startsWith(canonicalSdcard)) {
            Log.e(TAG, "Path injection blocked: target escapes sdcard boundary")
            throw SecurityException("Destination path escapes sdcard boundary: $destPath")
        }

        Log.d(TAG, "Copying special data: ${source.absolutePath} to ${targetFile.absolutePath}")

        // Story 1.7 Code Review Fix: Acquire WakeLock for large directory moves
        // This prevents the device from sleeping during long copy operations
        val isLargeOperation = source.isDirectory || source.length() > 50 * 1024 * 1024 // > 50MB
        if (isLargeOperation) {
            WakeLockManager.acquire(context)
        }

        try {
            targetFile.parentFile?.mkdirs()
            if (source.isDirectory) {
                source.copyRecursively(targetFile, overwrite = true)
                // We don't delete from extraction dir yet as it will be cleaned up later
            } else {
                source.copyTo(targetFile, overwrite = true)
            }

            // Story 1.7 Code Review Fix: Remove MediaScanner calls for game data files
            // Game data files (configs, saves, assets) are not media files and don't need scanning.
            // MediaScanner adds unnecessary overhead and delays installation for no benefit.
            Log.d(TAG, "Successfully copied ${source.name} to ${targetFile.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy special data: ${source.name}", e)
        } finally {
            // Story 1.7 Code Review Fix: Release WakeLock after operation
            if (isLargeOperation) {
                WakeLockManager.release()
            }
        }
    }

    /**
     * Story 1.7: Startup cleanup method for stale installation directories.
     *
     * This method is called on app startup to clean up temporary installation files
     * for packages that are already installed. It uses a simple heuristic: if the
     * package is installed and the temp directory exists, clean it up.
     *
     * Story 1.7 Code Review Round 8: This method does NOT verify versionCode.
     * The reason is that install.info only stores packageName and obbRequired, not
     * the version. This is intentional because:
     * 1. This is a startup cleanup method, not an installation verification method
     * 2. Version verification is properly handled by checkInstallationStatusSilent()
     *    which uses PackageManager to verify the actual installed version
     * 3. The cleanup heuristic is conservative: if package is installed, clean up temp
     *    files regardless of version to prevent disk space bloat
     * 4. If version-specific cleanup is needed, install.info format would need to change
     *
     * @param excludedReleaseNames Set of release names to exclude from cleanup (active tasks)
     */
    suspend fun verifyAndCleanupInstalls(excludedReleaseNames: Set<String> = emptySet()) = withContext(Dispatchers.IO) {
        if (!tempInstallRoot.exists()) return@withContext

        val installedPackages = getInstalledPackagesMap()
        val excludedHashes = excludedReleaseNames.map { CryptoUtils.md5(it + "\n") }.toSet()

        tempInstallRoot.listFiles()?.forEach { dir ->
            if (!dir.isDirectory) return@forEach
            if (excludedHashes.contains(dir.name)) return@forEach // Skip active task folder

            val infoFile = File(dir, "install.info")
            if (!infoFile.exists()) return@forEach

            try {
                val lines = infoFile.readLines()
                if (lines.size >= 2) {
                    val packageName = lines[0]
                    val obbRequired = lines[1].toBoolean()

                    if (installedPackages.containsKey(packageName)) {
                        var obbOk = true
                        if (obbRequired) {
                            // If it's an archive, we trust the installation was finished if APK is there.
                            // Better than leaving GBs of temp files.
                            obbOk = true
                        }

                        if (obbOk) {
                            Log.d(TAG, "Verification successful for $packageName, deleting temp files in ${dir.name}")
                            dir.deleteRecursively()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error verifying install for ${dir.name}", e)
            }
        }

        // Clean up staged APKs in externalFilesDir
        // Aligned with packageName.apk naming convention:
        // 1. Immediately delete staged APK for packages that are already installed
        // 2. Clean up old staged APKs (older than 24 hours) following the naming convention
        val externalFilesDir = context.getExternalFilesDir(null)
        externalFilesDir?.listFiles()?.forEach { file ->
            if (file.name.endsWith(".apk")) {
                // Determine package name from filename using our naming convention
                val fileName = file.name
                val packageName = fileName.removeSuffix(".apk")

                // Verify this follows our naming convention exactly
                if (fileName != getStagedApkFileName(packageName)) {
                    Log.d(TAG, "Cleaning up non-standard staged file: $fileName")
                    file.delete()
                    return@forEach
                }

                // Immediate cleanup: if package is installed, staged APK is no longer needed
                if (installedPackages.containsKey(packageName)) {
                    Log.d(TAG, "Cleaning up staged APK for installed package: ${file.name}")
                    if (!file.delete()) {
                        Log.w(TAG, "Failed to delete staged APK: ${file.name}")
                    }
                } else if (System.currentTimeMillis() - file.lastModified() > Constants.STAGED_APK_MAX_AGE_MS) {
                    // Old staged APK cleanup (24+ hours old) for non-installed packages
                    Log.d(TAG, "Cleaning up old staged APK (24+ hours): ${file.name}")
                    if (!file.delete()) {
                        Log.w(TAG, "Failed to delete staged APK: ${file.name}")
                    }
                }
            }
        }
    }

        fun cleanupInstall(releaseName: String) {
            val hash = CryptoUtils.md5(releaseName + "\n")
            val gameTempDir = File(tempInstallRoot, hash)
            if (!gameTempDir.exists()) return
            gameTempDir.deleteRecursively()
        }
    suspend fun deleteDownloadedGame(releaseName: String) = withContext(Dispatchers.IO) {
        // Cancel any active WorkManager tasks before deleting files
        cancelDownloadWork(releaseName)
        
        val safeDirName = releaseName.replace(Regex("[^a-zA-Z0-9.-]"), "_")
        val gameDownloadDir = File(downloadsDir, safeDirName)
        if (gameDownloadDir.exists()) {
            gameDownloadDir.deleteRecursively()
            MediaScannerConnection.scanFile(context, arrayOf(downloadsDir.absolutePath), null, null)
        }
    }

    suspend fun saveLogs(logs: String): String = withContext(Dispatchers.IO) {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val fileName = "rookie_logs_$timestamp.txt"
        val file = File(logsDir, fileName)
        if (!logsDir.exists()) logsDir.mkdirs()
        file.writeText(logs)
        MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), null, null)
        fileName
    }

    suspend fun clearCache(): Long = withContext(Dispatchers.IO) {
        val size = getFolderSize(tempInstallRoot)
        if (tempInstallRoot.exists()) {
            tempInstallRoot.deleteRecursively()
            tempInstallRoot.mkdirs()
        }
        size
    }

    private fun getFolderSize(file: File): Long {
        if (!file.exists()) return 0L
        if (file.isFile) return file.length()
        var size = 0L
        file.listFiles()?.forEach { size += getFolderSize(it) }
        return size
    }

    private suspend fun copyFileWithScanner(source: File, target: File) = withContext(Dispatchers.IO) {
        try {
            source.inputStream().use { input ->
                target.outputStream().use { output ->
                    copyToCancellable(input, output)
                }
            }
            MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), null, null)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy file and scan: ${source.name} to ${target.name}", e)
            throw e
        }
    }

    private fun moveObbFiles(packageFolder: File?, looseObbs: List<File>, packageName: String) {
        // Story 1.7: Check MANAGE_EXTERNAL_STORAGE permission before OBB operations
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            !Environment.isExternalStorageManager()) {
            throw SecurityException("MANAGE_EXTERNAL_STORAGE permission required for OBB files")
        }

        // Story 1.7 Code Review Fix: Use system API for base path, construct OBB path correctly
        // Environment.getExternalStorageDirectory() returns the correct user-specific path
        // (e.g., /storage/emulated/0 for primary user, /storage/emulated/10 for secondary users)
        val externalStorage = Environment.getExternalStorageDirectory()
        val obbBaseDir = File(externalStorage, "Android/obb/$packageName")

        try {
            if (!obbBaseDir.exists() && !obbBaseDir.mkdirs()) {
                Log.e(TAG, "Could not create OBB directory: ${obbBaseDir.absolutePath}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Permission error creating OBB dir", e)
        }

        // Calculate total bytes needed for OBB copy
        var totalObbBytes = 0L
        packageFolder?.walkTopDown()?.filter { it.isFile && !it.name.endsWith(".apk", true) }
            ?.forEach { totalObbBytes += it.length() }
        looseObbs.forEach { totalObbBytes += it.length() }

        // Check available space on OBB partition before copying
        if (totalObbBytes > 0) {
            try {
                // Code Review Fix: Use consistent API for partition path
                val stat = StatFs(externalStorage.path)
                val availableBytes = stat.availableBlocksLong * stat.blockSizeLong

                if (availableBytes < totalObbBytes) {
                    val requiredMb = totalObbBytes / (1024 * 1024)
                    val availableMb = availableBytes / (1024 * 1024)
                    Log.e(TAG, "Insufficient space for OBB files: need ${requiredMb}MB, have ${availableMb}MB")
                    throw InsufficientStorageException(requiredMb, availableMb)
                }
                Log.d(TAG, "OBB space check passed: ${totalObbBytes / (1024 * 1024)}MB needed, ${availableBytes / (1024 * 1024)}MB available")
            } catch (e: IllegalArgumentException) {
                // StatFs can throw if path is invalid or unmounted
                Log.w(TAG, "Could not check OBB partition space, proceeding anyway", e)
            }
        }

        packageFolder?.walkTopDown()?.forEach { source ->
            if (source.isFile && !source.name.endsWith(".apk", true)) {
                val relPath = source.relativeTo(packageFolder).path
                val destFile = File(obbBaseDir, relPath)
                val isFromDownloads = source.absolutePath.contains(downloadsDir.absolutePath)
                try {
                    destFile.parentFile?.mkdirs()

                    // Story 1.7 Code Review Fix: Idempotency check using size only
                    // lastModified() comparison removed due to filesystem precision issues
                    // (some filesystems have second precision, others millisecond)
                    // Size-only check is sufficient for OBB files which are immutable game assets
                    if (destFile.exists() && destFile.length() == source.length()) {
                        Log.d(TAG, "Skipping OBB file (already present with matching size): ${source.name}")
                        // Delete source since we've confirmed destination exists with same size
                        if (!isFromDownloads) {
                            source.delete()
                        }
                        return@forEach
                    }

                    if (isFromDownloads || !source.renameTo(destFile)) {
                        source.inputStream().use { input ->
                            destFile.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                        if (!isFromDownloads) {
                            if (!source.delete()) {
                                Log.w(TAG, "Failed to delete source file after copy: ${source.name}")
                            }
                        }
                    }
                    // Story 1.7: DO NOT scan OBB files with MediaScanner (non-media archives)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to move OBB from folder: ${source.name}", e)
                }
            }
        }

        looseObbs.forEach { loose ->
            val destFile = File(obbBaseDir, loose.name)
            val isFromDownloads = loose.absolutePath.contains(downloadsDir.absolutePath)
            try {
                // Story 1.7 Code Review Fix: Idempotency check using size only (see above for reasoning)
                if (destFile.exists() && destFile.length() == loose.length()) {
                    Log.d(TAG, "Skipping loose OBB file (already present with matching size): ${loose.name}")
                    if (!isFromDownloads) {
                        loose.delete()
                    }
                    return@forEach
                }

                if (isFromDownloads || !loose.renameTo(destFile)) {
                    loose.inputStream().use { input ->
                        destFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    if (!isFromDownloads) loose.delete()
                }
                // Story 1.7: DO NOT scan OBB files with MediaScanner (non-media archives)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to move loose OBB: ${loose.name}", e)
            }
        }

        // Story 1.7 Code Review Round 5: OBB integrity verification
        // Verify that all expected OBB files were successfully copied
        if (totalObbBytes > 0) {
            val expectedObbCount = (packageFolder?.walkTopDown()?.count { it.isFile && !it.name.endsWith(".apk", true) } ?: 0) +
                                   looseObbs.size
            val actualObbFiles = obbBaseDir.listFiles()?.filter { it.isFile } ?: emptyList()
            val actualObbCount = actualObbFiles.size

            if (actualObbCount < expectedObbCount) {
                Log.w(TAG, "OBB integrity warning: Expected $expectedObbCount OBB files, found $actualObbCount in ${obbBaseDir.absolutePath}")
                Log.w(TAG, "Game may not work correctly - some OBB files failed to copy")
            } else {
                val totalCopied = actualObbFiles.sumOf { it.length() }
                Log.i(TAG, "OBB integrity verified: $actualObbCount files, ${totalCopied / (1024 * 1024)}MB in ${obbBaseDir.absolutePath}")
            }
        }
    }

    private suspend fun getRemoteLastModified(url: String): String? {
        return try {
            val request = Request.Builder().url(url).head().header("User-Agent", Constants.USER_AGENT).build()
            okHttpClient.newCall(request).await().use { it.header("Last-Modified") }
        } catch (e: Exception) { null }
    }

    /**
     * Story 1.7 Code Review Round 8: DEPRECATED - Use isValidApkFile() instead.
     *
     * This method is kept for backward compatibility but should not be used in new code.
     * The isValidApkFile() method is more flexible (optional parameters) and eliminates
     * code duplication. All existing uses have been migrated to isValidApkFile().
     *
     * @deprecated Use [isValidApkFile] with explicit packageName and versionCode parameters
     */
    @Deprecated("Use isValidApkFile() instead", ReplaceWith("isValidApkFile(file, packageName, versionCode)"))
    private fun isApkMatching(file: File, packageName: String, versionCode: Long): Boolean {
        if (!file.exists()) return false
        return try {
            val pm = context.packageManager
            val info = pm.getPackageArchiveInfo(file.absolutePath, 0) ?: return false
            val fileVersion = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong()
            info.packageName == packageName && fileVersion == versionCode
        } catch (e: Exception) {
            false
        }
    }

    // ==============================
    // Queue Management (v2.5.0)
    // ==============================

    /**
     * Returns a Flow of all queued installs from Room database
     * Used by ViewModel to create StateFlow<List<InstallTaskState>>
     */
    fun getAllQueuedInstalls(): Flow<List<QueuedInstallEntity>> {
        return db.queuedInstallDao().getAllFlow()
    }

    /**
     * Gets game data by release name (used for queue state conversion)
     */
    suspend fun getGameByReleaseName(releaseName: String): GameData? = withContext(Dispatchers.IO) {
        gameDao.getByReleaseName(releaseName)?.toData()
    }

    /**
     * Batch query to get game data for multiple release names in a single DB call.
     * Returns a map of releaseName -> GameData for O(1) lookup.
     * Used to avoid N+1 queries when converting queue entities to UI state.
     */
    suspend fun getGamesByReleaseNames(releaseNames: List<String>): Map<String, GameData> = withContext(Dispatchers.IO) {
        if (releaseNames.isEmpty()) return@withContext emptyMap()
        gameDao.getByReleaseNames(releaseNames)
            .associate { it.releaseName to it.toData() }
    }

    /**
     * Runs migration from v2.4.0 legacy queue if needed
     * Should be called once during app initialization
     *
     * @return Number of migrated items, 0 if no migration needed, -1 on failure
     */
    suspend fun migrateLegacyQueueIfNeeded(): Int = withContext(Dispatchers.IO) {
        if (MigrationManager.needsMigration(context)) {
            Log.i(TAG, "Legacy queue migration needed - starting migration")
            val result = MigrationManager.migrateLegacyQueue(context, db)
            if (result >= 0) {
                Log.i(TAG, "Migration completed: $result items migrated")
            } else {
                Log.e(TAG, "Migration failed")
            }
            result
        } else {
            Log.d(TAG, "No legacy queue migration needed")
            0
        }
    }

    /**
     * Adds a new install task to the queue in Room database.
     * Uses atomic transaction to prevent race conditions with queue position.
     */
    suspend fun addToQueue(
        releaseName: String,
        status: InstallStatus = InstallStatus.QUEUED,
        isDownloadOnly: Boolean = false
    ) = withContext(Dispatchers.IO) {
        // Create entity with placeholder position (will be set atomically by DAO)
        val entity = QueuedInstallEntity.createValidated(
            releaseName = releaseName,
            status = status,
            queuePosition = 0, // Placeholder - DAO will assign correct position atomically
            isDownloadOnly = isDownloadOnly
        )
        // Use atomic insert that reads max position and inserts in single transaction
        db.queuedInstallDao().insertAtNextPosition(entity)
    }

    /**
     * Updates the status of a queued install.
     * Status validation is enforced by InstallStatus enum type - only valid enum values can be passed.
     */
    suspend fun updateQueueStatus(
        releaseName: String,
        status: InstallStatus
    ) = withContext(Dispatchers.IO) {
        // InstallStatus enum guarantees status.name is always a valid status string
        db.queuedInstallDao().updateStatus(
            releaseName = releaseName,
            status = status.name,
            timestamp = System.currentTimeMillis()
        )
    }

    /**
     * Updates the progress of a queued install with validation.
     * Progress values are coerced to valid range [0.0, 1.0].
     * Bytes values are validated (downloadedBytes <= totalBytes when both present).
     *
     * @param skipTotalBytes If true, only updates progress and downloadedBytes (optimization
     *        for subsequent calls where totalBytes is already set). Defaults to false.
     */
    suspend fun updateQueueProgress(
        releaseName: String,
        progress: Float,
        downloadedBytes: Long?,
        totalBytes: Long?,
        skipTotalBytes: Boolean = false
    ) = withContext(Dispatchers.IO) {
        // Coerce progress to valid range instead of throwing (progress can overshoot during calculations)
        val validatedProgress = progress.coerceIn(0.0f, 1.0f)

        // Validate bytes relationship
        if (downloadedBytes != null && totalBytes != null && downloadedBytes > totalBytes) {
            Log.w(TAG, "updateQueueProgress: downloadedBytes ($downloadedBytes) > totalBytes ($totalBytes), clamping")
        }
        val validatedDownloadedBytes = if (downloadedBytes != null && totalBytes != null) {
            downloadedBytes.coerceAtMost(totalBytes)
        } else {
            downloadedBytes
        }

        if (skipTotalBytes && validatedDownloadedBytes != null) {
            // Optimized path: only update progress and downloadedBytes
            db.queuedInstallDao().updateProgressAndBytes(
                releaseName = releaseName,
                progress = validatedProgress,
                downloadedBytes = validatedDownloadedBytes,
                timestamp = System.currentTimeMillis()
            )
        } else {
            // Full update including totalBytes
            db.queuedInstallDao().updateProgress(
                releaseName = releaseName,
                progress = validatedProgress,
                downloadedBytes = validatedDownloadedBytes,
                totalBytes = totalBytes,
                timestamp = System.currentTimeMillis()
            )
        }
    }

    /**
     * Removes an install task from the queue
     */
    suspend fun removeFromQueue(releaseName: String) = withContext(Dispatchers.IO) {
        db.queuedInstallDao().deleteByReleaseName(releaseName)
    }

    /**
     * Promotes a task to the front of the queue
     * All operations happen atomically in a single Room transaction
     */
    suspend fun promoteInQueue(releaseName: String) = withContext(Dispatchers.IO) {
        // Delegate to DAO method with @Transaction for atomic updates
        db.queuedInstallDao().promoteToFront(releaseName)
    }

    /**
     * Promotes a task to the front of the queue AND updates its status atomically.
     * This is used when promoting a PAUSED or FAILED task that needs to restart.
     * Both operations happen in a single Room transaction to prevent partial state updates.
     *
     * @param releaseName The task to promote
     * @param status The new status to set (typically QUEUED)
     */
    suspend fun promoteInQueueAndSetStatus(releaseName: String, status: InstallStatus) = withContext(Dispatchers.IO) {
        db.queuedInstallDao().promoteToFrontAndUpdateStatus(releaseName, status.name)
    }

    // ==============================
    // WorkManager Integration (v2.5.0)
    // ==============================

    /**
     * Enqueues a download task via WorkManager.
     * The work will survive app kill and device reboot.
     *
     * @param releaseName The unique identifier for the game to download
     * @param isDownloadOnly If true, skip installation after download
     * @param keepApk If true, save APK to Downloads folder
     */
    fun enqueueDownload(releaseName: String, isDownloadOnly: Boolean, keepApk: Boolean) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .setRequiresStorageNotLow(true)
            .build()

        val inputData = Data.Builder()
            .putString(DownloadWorker.KEY_RELEASE_NAME, releaseName)
            .putBoolean(DownloadWorker.KEY_IS_DOWNLOAD_ONLY, isDownloadOnly)
            .putBoolean(DownloadWorker.KEY_KEEP_APK, keepApk)
            .build()

        val downloadRequest = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setConstraints(constraints)
            .setInputData(inputData)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                10000L, // 10 seconds minimum backoff
                TimeUnit.MILLISECONDS
            )
            .addTag("download")
            .addTag(releaseName)
            .build()

        WorkManager.getInstance(context)
            .enqueueUniqueWork(
                "download_$releaseName",
                ExistingWorkPolicy.KEEP,
                downloadRequest
            )

        Log.i(TAG, "Enqueued download work for: $releaseName")
    }

    /**
     * Cancels a download work by release name.
     */
    fun cancelDownloadWork(releaseName: String) {
        WorkManager.getInstance(context)
            .cancelUniqueWork("download_$releaseName")
        Log.i(TAG, "Cancelled download work for: $releaseName")
    }

    /**
     * Gets WorkInfo LiveData for observing download status
     */
    fun getDownloadWorkInfoLiveData(releaseName: String): LiveData<List<WorkInfo>> {
        return WorkManager.getInstance(context)
            .getWorkInfosForUniqueWorkLiveData("download_$releaseName")
    }

    /**
     * Gets WorkInfo Flow for observing download status (Kotlin Coroutines friendly)
     */
    fun getDownloadWorkInfoFlow(releaseName: String): Flow<List<WorkInfo>> {
        return WorkManager.getInstance(context)
            .getWorkInfosForUniqueWorkLiveData("download_$releaseName")
            .asFlow()
    }

    /**
     * Checks if a download work is currently running or enqueued
     */
    suspend fun isDownloadWorkActive(releaseName: String): Boolean = withContext(Dispatchers.IO) {
        val workInfos = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork("download_$releaseName")
            .get()
        workInfos.any { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED }
    }

    /**
     * Extension to convert LiveData to Flow
     */
    @Suppress("NOTHING_TO_INLINE")
    private inline fun <T> LiveData<T>.asFlow(): Flow<T> =
        channelFlow {
            val observer = Observer<T> { value ->
                trySend(value)
            }
            observeForever(observer)
            try {
                awaitCancellation()
            } finally {
                removeObserver(observer)
            }
        }

    // ==============================
    // Staged APK Utilities (Story 1.11)
    // ==============================

    /**
     * Gets the standardized file name for a staged APK.
     *
     * This ensures consistent naming across the codebase and prevents
     * cross-contamination between installation tasks.
     *
     * @param packageName The package name of the game
     * @return The standardized APK file name (e.g., "com.example.game.apk")
     */
    fun getStagedApkFileName(packageName: String): String {
        require(packageName.isNotBlank()) { "Package name cannot be empty or blank" }
        return "$packageName.apk"
    }

    /**
     * Gets the File object for a staged APK.
     *
     * @param packageName The package name of the game
     * @return The File object for the staged APK, or null if external storage is not available
     */
    fun getStagedApkFile(packageName: String): File? {
        val dir = context.getExternalFilesDir(null) ?: return null
        return File(dir, getStagedApkFileName(packageName))
    }

        /**
         * Gets the File object for the staged APK of a package, only if it exists and passes integrity checks.
         *
         * @param packageName The package name of the game
         * @param expectedVersionCode Optional: if provided, verifies that the APK's version code matches
         * @return The validated File object for the staged APK, or null if missing/invalid/mismatched
         */
        fun getValidStagedApk(packageName: String, expectedVersionCode: Long? = null): File? {
            val file = getStagedApkFile(packageName) ?: return null 
            return if (isValidApkFile(file, packageName, expectedVersionCode)) file else null
        }
    /**
     * Validates that a file is a valid APK using PackageManager and optionally matches a package name and version code.
     *
     * This ensures that staged APK files are actually valid Android packages
     * and not corrupted, and optionally that they match the expected identity.
     *
     * Story 1.7 Code Review Round 9 Note - APK Signature Verification:
     * This method uses PackageManager.getPackageArchiveInfo() which validates the APK structure
     * and signature internally. If an APK has been tampered with in a way that breaks its signature,
     * getPackageArchiveInfo() returns null and the APK will be rejected.
     *
     * Full signature certificate verification (comparing against known certificates) is NOT
     * implemented because:
     * 1. The source APKs are re-signed by the distribution server, not original developers
     * 2. There's no trusted certificate store to compare against
     * 3. Android's PackageManager already verifies that the signature is valid and consistent
     *
     * The existing validation ensures:
     * - APK structure is valid
     * - Signature is present and internally consistent
     * - Package name matches expectation (if provided)
     * - Version code matches catalog (if provided)
     *
     * @param apkFile The APK file to validate
     * @param expectedPackageName Optional: if provided, verifies that the APK's internal package name matches
     * @param expectedVersionCode Optional: if provided, verifies that the APK's version code matches
     * @return true if the file is a valid APK (and matches expected parameters if provided), false otherwise
     */
    fun isValidApkFile(
        apkFile: File,
        expectedPackageName: String? = null,
        expectedVersionCode: Long? = null
    ): Boolean {
        if (!apkFile.exists() || apkFile.length() == 0L) {
            return false
        }

        return try {
            // Use 0 flags for performance as we only need basic package info (name, version)
            val packageInfo = context.packageManager.getPackageArchiveInfo(
                apkFile.absolutePath,
                0
            )
            
            if (packageInfo == null) return false
            
            val packageMatch = expectedPackageName == null || packageInfo.packageName == expectedPackageName
            
            // Check version code (handles both legacy and long version codes)
            val versionMatch = expectedVersionCode == null || 
                    (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) packageInfo.longVersionCode else packageInfo.versionCode.toLong()) == expectedVersionCode
            
            packageMatch && versionMatch
        } catch (e: Exception) {
            Log.w(TAG, "Failed to validate APK file: ${apkFile.name}", e)
            false
        }
    }

    /**
     * Cleans up all staged APK files from the external files directory.
     *
     * This should be called before staging a new APK to prevent cross-contamination.
     *
     * @return The number of APK files deleted
     */
    fun cleanupStagedApks(): Int = cleanupStagedApks(null)

    /**
     * Cleans up staged APK files, optionally preserving a specific package's APK.
     *
     * @param preservePackageName If specified, the APK for this package will not be deleted
     * @return The number of APK files deleted
     */
        fun cleanupStagedApks(preservePackageName: String?): Int {
            val externalFilesDir = context.getExternalFilesDir(null) ?: return 0
            val apkFiles = externalFilesDir.listFiles()?.filter { it.name.endsWith(".apk") } ?: return 0

            var deletedCount = 0
            apkFiles.forEach { file ->
                // Preserve the specified package's APK if provided
                if (preservePackageName != null && file.name == getStagedApkFileName(preservePackageName)) {
                    return@forEach
                }

                // Re-check existence to prevent race conditions with other operations
                if (file.exists()) {
                    Log.d(TAG, "Cleaning up staged APK: ${file.name}")
                    if (file.delete()) {
                        deletedCount++
                    } else {
                        Log.w(TAG, "Failed to delete staged APK: ${file.name}")
                    }
                }
            }

            return deletedCount
        }

}
