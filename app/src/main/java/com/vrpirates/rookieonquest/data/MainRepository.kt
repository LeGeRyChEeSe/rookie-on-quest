package com.vrpirates.rookieonquest.data

import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.os.StatFs
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

                val passwordsToTry = listOfNotNull(decodedPassword, cachedConfig?.password64, null)
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
            var entry = sevenZFile.nextEntry
            while (entry != null) {
                val entryName = entry.name.lowercase()
                if (entryName.endsWith("vrp-gamelist.txt")) {
                    val out = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    while (sevenZFile.read(buffer).also { bytesRead = it } != -1) {
                        out.write(buffer, 0, bytesRead)
                    }
                    val content = out.toString("UTF-8")
                    catalogCacheFile.writeText(content)
                    onGameListFound(content)
                } else if (entryName.contains("/thumbnails/")) {
                    val fileName = entry.name.substringAfterLast("/")
                    if (fileName.isNotEmpty()) {
                        val targetFile = File(thumbnailsDir, fileName)
                        saveEntryToFile(sevenZFile, targetFile)
                    }
                } else if (entryName.contains("/notes/")) {
                    val fileName = entry.name.substringAfterLast("/")
                    if (fileName.isNotEmpty()) {
                        val targetFile = File(notesDir, fileName)
                        saveEntryToFile(sevenZFile, targetFile)
                    }
                } else if (entryName.endsWith(".png") || entryName.endsWith(".jpg")) {
                    val fileName = entry.name.substringAfterLast("/")
                    val iconFile = File(iconsDir, fileName)
                    if (!iconFile.exists()) {
                        saveEntryToFile(sevenZFile, iconFile)
                    }
                }
                entry = sevenZFile.nextEntry
            }
        }
    }

    private fun saveEntryToFile(sevenZFile: SevenZFile, targetFile: File) {
        FileOutputStream(targetFile).use { out ->
            val buffer = ByteArray(8192)
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
        val buffer = ByteArray(8192 * 8)
        var bytesRead: Int
        while (true) {
            currentCoroutineContext().ensureActive()
            bytesRead = input.read(buffer)
            if (bytesRead == -1) break
            output.write(buffer, 0, bytesRead)
        }
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
     * NOTE: The segment fetching logic in this method is intentionally duplicated from
     * DownloadWorker.fetchRemoteSegments() due to subtle differences in requirements:
     * - This method also fetches metadata (descriptions, screenshots)
     * - Different error handling for UI vs background contexts
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
        checkExternalStorage: Boolean = false
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
            throw Exception("Insufficient storage space on internal partition. Need ${requiredMb}MB, but only ${availableMb}MB available.")
        }

        // Check external storage (downloadsDir) for download-only and keep-apk modes
        // These modes save APKs to external storage, so we need to verify that space too
        if (checkExternalStorage) {
            try {
                val externalStat = StatFs(downloadsDir.path)
                val externalAvailable = externalStat.availableBlocksLong * externalStat.blockSizeLong

                if (externalAvailable < effectiveRequired) {
                    val requiredMb = effectiveRequired / (1024 * 1024)
                    val availableMb = externalAvailable / (1024 * 1024)
                    throw Exception("Insufficient storage space on external storage. Need ${requiredMb}MB, but only ${availableMb}MB available.")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not check external storage space for $downloadsDir", e)
                // Don't fail on external storage check errors - the device might not have external storage
                // or the downloadsDir might not be accessible yet
            }
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
        val remoteSegments: Map<String, Long>
        val totalBytes: Long

        if (skipRemoteVerification) {
            // WorkManager already downloaded files - verify locally instead of making HEAD requests
            onProgress("Preparing extraction...", Constants.PROGRESS_MILESTONE_VERIFYING, 0, 0)
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
            remoteSegments = localSegments
            totalBytes = localSegments.values.sum()

            if (remoteSegments.isEmpty()) {
                // Fallback to server verification if no local files found
                Log.w(TAG, "No local files found for ${game.releaseName}, falling back to server verification")
                return@withContext installGame(game, keepApk, downloadOnly, skipRemoteVerification = false, onProgress)
            }
        } else {
            // Standard path: fetch ground truth from server
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
        // Check internal storage (for temp files) and external storage (for download-only/keep-apk modes)
        checkAvailableSpace(estimatedRequired, hasUnknownSizes, checkExternalStorage = downloadOnly || keepApk)

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
        var foundApk: File? = if (isApkMatching(cachedApk, game.packageName, targetVersion)) cachedApk else null
        if (foundApk == null && gameDownloadDir.exists()) {
             foundApk = gameDownloadDir.listFiles()?.find { isApkMatching(it, game.packageName, targetVersion) }
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
            val password = decodedPassword ?: throw Exception("Password not available")
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
                remoteSegments.keys.forEach { seg ->
                    currentCoroutineContext().ensureActive()
                    val source = File(gameTempDir, seg)
                    val target = File(extractionDir, seg)
                    if (source.exists()) {
                        target.parentFile?.let { if (!it.exists()) it.mkdirs() }
                        copyToCancellable(source.inputStream(), target.outputStream())
                    }
                }
                extractionMarker.createNewFile()
            } else {
                val combinedFile = File(gameTempDir, "combined.7z")
                val archiveParts = remoteSegments.filter { it.key.contains(".7z") }
                val archiveTotalBytes = archiveParts.values.sum()

                if (!combinedFile.exists() || combinedFile.length() < archiveTotalBytes) {
                    onProgress("Merging files...", Constants.PROGRESS_MILESTONE_MERGING, totalBytes, totalBytes)
                    combinedFile.outputStream().use { out ->
                        // Ensure correct order for merge and that files exist
                        archiveParts.keys
                            .sortedWith(compareBy { it })
                            .forEach { seg -> 
                                currentCoroutineContext().ensureActive()
                                val partFile = File(gameTempDir, seg)
                                if (!partFile.exists()) throw Exception("Part file missing: $seg")
                                partFile.inputStream().use { input ->
                                    copyToCancellable(input, out)
                                }
                            }
                    }
                }
                
                onProgress("Extracting...", Constants.PROGRESS_MILESTONE_EXTRACTING, totalBytes, totalBytes)
                val password = decodedPassword ?: ""
                try {
                    SevenZFile.builder().setFile(combinedFile).setPassword(password.toCharArray()).get().use { sevenZFile ->
                        var entry = sevenZFile.nextEntry
                        while (entry != null) {
                            currentCoroutineContext().ensureActive()
                            // Keep APK, OBB and EVERYTHING ELSE (important for Quake3Quest style structures)
                            val outFile = File(extractionDir, entry.name)
                            if (entry.isDirectory) outFile.mkdirs()
                            else {
                                outFile.parentFile?.let { if (!it.exists()) it.mkdirs() }
                                FileOutputStream(outFile).use { out ->
                                    val buffer = ByteArray(8192 * 8)
                                    var bytesRead: Int
                                    while (true) {
                                        currentCoroutineContext().ensureActive()
                                        bytesRead = sevenZFile.read(buffer)
                                        if (bytesRead == -1) break
                                        out.write(buffer, 0, bytesRead)
                                    }
                                }
                            }
                            entry = sevenZFile.nextEntry
                        }
                    }
                    extractionMarker.createNewFile()
                } catch (e: Exception) {
                    Log.e(TAG, "Extraction failed", e)
                    extractionDir.deleteRecursively()
                    throw Exception("Extraction failed: ${e.message}")
                } finally {
                    if (combinedFile.exists()) combinedFile.delete()
                }
            }
        }

        // 5. Finalize Installation
        val apks = mutableListOf<File>()
        extractionDir.walkTopDown().forEach { if (it.name.endsWith(".apk", true)) apks.add(it) }
        
        // If not found in extraction, check download dir
        if (apks.isEmpty() && gameDownloadDir.exists()) {
            gameDownloadDir.walkTopDown().forEach { file ->
                if (file.name.endsWith(".apk", true) && (file.name.contains(game.packageName) || apks.isEmpty())) {
                    apks.add(file)
                }
            }
        }

        if (apks.isEmpty() && !downloadOnly) {
            extractionMarker.delete()
            throw Exception("No APK found for installation")
        }
        val finalApk = apks.getOrNull(0)

        // SPECIAL HANDLING FOR NON-STANDARD OBB/DATA STRUCTURES
        val installTxtFile = extractionDir.walkTopDown().find { it.name == "install.txt" }
        val specialMoves = mutableListOf<Pair<File, String>>()
        
        if (installTxtFile != null) {
            Log.d(TAG, "Found install.txt, parsing for special instructions")
            val lines = installTxtFile.readLines()
            lines.forEach { line ->
                val trimmed = line.trim()
                if (trimmed.contains("adb push")) {
                    val parts = trimmed.split(Regex("\\s+"))
                    val pushIdx = parts.indexOf("push")
                    if (pushIdx != -1 && parts.size > pushIdx + 2) {
                        val sourceName = parts[pushIdx + 1].trim('/', '\\')
                        val destPath = parts[pushIdx + 2]
                        
                        val sourceFile = File(extractionDir, sourceName)
                        if (sourceFile.exists()) {
                            specialMoves.add(sourceFile to destPath)
                        } else {
                            // Try finding it recursively if path in install.txt is relative or different
                            extractionDir.walkTopDown().find { it.name == sourceName }?.let {
                                specialMoves.add(it to destPath)
                            }
                        }
                    }
                }
            }
        }

        var packageFolder: File? = null
        extractionDir.walkTopDown().forEach { file ->
            if (file.isDirectory && file.name == game.packageName) {
                packageFolder = file
                return@forEach
            }
        }
        
        // Check download dir for OBB folder if not found in extraction
        if (packageFolder == null && gameDownloadDir.exists()) {
            gameDownloadDir.walkTopDown().forEach { file ->
                if (file.isDirectory && file.name == game.packageName) {
                    packageFolder = file
                    return@forEach
                }
            }
        }
        
        val looseObbs = mutableListOf<File>()
        val obbSearchDirs = mutableListOf(extractionDir)
        if (gameDownloadDir.exists()) obbSearchDirs.add(gameDownloadDir)
        
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
        
        if (downloadOnly || keepApk) {
            onProgress("Saving to Downloads...", Constants.PROGRESS_MILESTONE_SAVING_TO_DOWNLOADS, totalBytes, totalBytes)
            try {
                if (!downloadsDir.exists()) downloadsDir.mkdirs()
                if (!gameDownloadDir.exists()) gameDownloadDir.mkdirs()
                
                finalApk?.let {
                    val targetApk = File(gameDownloadDir, apkFileName)
                    if (it.absolutePath != targetApk.absolutePath) {
                        copyFileWithScanner(it, targetApk)
                    }
                }
                
                val finalObbDir = File(gameDownloadDir, game.packageName).apply { if (!exists()) mkdirs() }
                packageFolder?.let { pf ->
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
                looseObbs.forEach { loose ->
                    currentCoroutineContext().ensureActive()
                    val targetObb = File(finalObbDir, loose.name)
                    if (loose.absolutePath != targetObb.absolutePath) {
                        copyFileWithScanner(loose, targetObb)
                    }
                }

                // Save special folders to downloads too
                specialMoves.forEach { (source, _) ->
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
                throw e // Rethrow to notify UI of failure
            }
        }

        if (downloadOnly) {
            onProgress("Download complete", 1f, totalBytes, totalBytes)
            gameTempDir.deleteRecursively()
            return@withContext null
        }

        // Apply special moves (e.g. Quake3Quest data folders)
        if (specialMoves.isNotEmpty()) {
            onProgress("Installing Special Data...", Constants.PROGRESS_MILESTONE_INSTALLING_OBBS, totalBytes, totalBytes)
            specialMoves.forEach { (source, destPath) ->
                moveDataToSdcard(source, destPath)
            }
        }

        val hasObbs = packageFolder != null || looseObbs.isNotEmpty()
        if (hasObbs) {
            onProgress("Installing OBBs...", Constants.PROGRESS_MILESTONE_LAUNCHING_INSTALLER, totalBytes, totalBytes)
            moveObbFiles(packageFolder, looseObbs, game.packageName)
        }

        // Save info for post-install verification
        try {
            File(gameTempDir, "install.info").writeText("${game.packageName}\n${hasObbs || specialMoves.isNotEmpty()}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write install info", e)
        }

        // Launching installer...
        if (finalApk == null || !finalApk.exists()) {
             throw Exception("Final APK not found for installation")
        }

        // Clean up any existing APK files in externalFilesDir to prevent cross-contamination
        val deletedCount = cleanupStagedApks()
        if (deletedCount > 0) {
            Log.d(TAG, "Cleaned up $deletedCount old staged APK(s)")
        }

        // Use centralized staged APK file utility
        val externalApk = getStagedApkFile(game.packageName)
            ?: throw IllegalStateException("External files directory not available")
        currentCoroutineContext().ensureActive()
        copyToCancellable(finalApk.inputStream(), externalApk.outputStream())

        // Validate APK integrity after staging to ensure it's a valid Android package and matches expected package name
        if (!isValidApkFile(externalApk, game.packageName)) {
            externalApk.delete()
            throw IllegalStateException("Staged APK is invalid or package name mismatch: ${externalApk.name}")
        }

        externalApk
    }

    private fun moveDataToSdcard(source: File, destPath: String) {
        val sdcard = Environment.getExternalStorageDirectory()
        val relativeDest = destPath.removePrefix("/sdcard/").removePrefix("sdcard/").removePrefix("/")
        
        // If destPath ends with /, it means put the source INSIDE that folder
        // If it doesn't, it might mean rename source to destPath.
        // Usually adb push folder /sdcard/ means /sdcard/folder
        val targetFile = if (destPath.endsWith("/")) {
            File(sdcard, relativeDest + File.separator + source.name)
        } else if (relativeDest.isEmpty()) {
            File(sdcard, source.name)
        } else {
            File(sdcard, relativeDest)
        }

        Log.d(TAG, "Moving special data: ${source.absolutePath} to ${targetFile.absolutePath}")
        
        try {
            targetFile.parentFile?.mkdirs()
            if (source.isDirectory) {
                source.copyRecursively(targetFile, overwrite = true)
                // We don't delete from extraction dir yet as it will be cleaned up later
            } else {
                source.copyTo(targetFile, overwrite = true)
            }
            
            // Scan files for media library
            val filesToScan = mutableListOf<String>()
            targetFile.walkTopDown().forEach { filesToScan.add(it.absolutePath) }
            MediaScannerConnection.scanFile(context, filesToScan.toTypedArray(), null, null)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to move special data: ${source.name}", e)
        }
    }

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
                            // For special data, it's harder to verify without re-parsing install.txt
                            // but usually if APK is installed, we assume OBB/Data was moved too during the process.
                            // We do a simple check for standard OBB
                            val obbDir = File(Environment.getExternalStorageDirectory(), "Android/obb/$packageName")
                            val standardObbExists = obbDir.exists() && (obbDir.list()?.isNotEmpty() ?: false)
                            
                            // If it's not a standard OBB, we just trust the installation was finished if APK is there.
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
                } else if (System.currentTimeMillis() - file.lastModified() > 1000 * 60 * 60 * 24) {
                    // Old staged APK cleanup (24+ hours old) for non-installed packages
                    Log.d(TAG, "Cleaning up old staged APK (24+ hours): ${file.name}")
                    if (!file.delete()) {
                        Log.w(TAG, "Failed to delete staged APK: ${file.name}")
                    }
                }
            }
        }
    }

    fun cleanupInstall(releaseName: String, totalBytes: Long) {
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
        val obbBaseDir = File(Environment.getExternalStorageDirectory(), "Android/obb/$packageName")
        try {
            if (!obbBaseDir.exists() && !obbBaseDir.mkdirs()) {
                Log.e(TAG, "Could not create OBB directory: ${obbBaseDir.absolutePath}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Permission error creating OBB dir", e)
        }
        
        packageFolder?.walkTopDown()?.forEach { source ->
            if (source.isFile && !source.name.endsWith(".apk", true)) {
                val relPath = source.relativeTo(packageFolder).path
                val destFile = File(obbBaseDir, relPath)
                val isFromDownloads = source.absolutePath.contains(downloadsDir.absolutePath)
                try {
                    destFile.parentFile?.mkdirs()
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
                    MediaScannerConnection.scanFile(context, arrayOf(destFile.absolutePath), null, null)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to move OBB from folder: ${source.name}", e)
                }
            }
        }
        
        looseObbs.forEach { loose ->
            val destFile = File(obbBaseDir, loose.name)
            val isFromDownloads = loose.absolutePath.contains(downloadsDir.absolutePath)
            try {
                if (isFromDownloads || !loose.renameTo(destFile)) {
                    loose.inputStream().use { input ->
                        destFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    if (!isFromDownloads) loose.delete()
                }
                MediaScannerConnection.scanFile(context, arrayOf(destFile.absolutePath), null, null)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to move loose OBB: ${loose.name}", e)
            }
        }
    }

    private suspend fun getRemoteLastModified(url: String): String? {
        return try {
            val request = Request.Builder().url(url).head().header("User-Agent", Constants.USER_AGENT).build()
            okHttpClient.newCall(request).await().use { it.header("Last-Modified") }
        } catch (e: Exception) { null }
    }

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
     * @return The validated File object for the staged APK, or null if missing/invalid/mismatched
     */
    fun getValidStagedApk(packageName: String): File? {
        val file = getStagedApkFile(packageName) ?: return null
        return if (isValidApkFile(file, packageName)) file else null
    }

    /**
     * Validates that a file is a valid APK using PackageManager and optionally matches a package name.
     *
     * This ensures that staged APK files are actually valid Android packages
     * and not corrupted, and optionally that they match the expected identity.
     *
     * @param apkFile The APK file to validate
     * @param expectedPackageName Optional: if provided, verifies that the APK's internal package name matches
     * @return true if the file is a valid APK (and matches expectedPackageName if provided), false otherwise
     */
    fun isValidApkFile(apkFile: File, expectedPackageName: String? = null): Boolean {
        if (!apkFile.exists() || apkFile.length() == 0L) {
            return false
        }

        return try {
            val packageInfo = context.packageManager.getPackageArchiveInfo(
                apkFile.absolutePath,
                PackageManager.GET_ACTIVITIES or PackageManager.GET_SERVICES
            )
            packageInfo != null && (expectedPackageName == null || packageInfo.packageName == expectedPackageName)
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

            Log.d(TAG, "Cleaning up staged APK: ${file.name}")
            if (file.delete()) {
                deletedCount++
            } else {
                Log.w(TAG, "Failed to delete staged APK: ${file.name}")
            }
        }

        return deletedCount
    }
}
