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
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class MainRepository(private val context: Context) {
    private val TAG = "MainRepository"
    private val catalogMutex = Mutex()
    private val db = AppDatabase.getDatabase(context)
    private val gameDao = db.gameDao()
    
    private val retrofit = Retrofit.Builder()
        .baseUrl("https://vrpirates.wiki/")
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    private val service = retrofit.create(VrpService::class.java)
    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
        
    private val prefs = context.getSharedPreferences("rookie_prefs", Context.MODE_PRIVATE)

    private var cachedConfig: PublicConfig? = null
    private var decodedPassword: String? = null
    
    val iconsDir = File(context.filesDir, "icons").apply { if (!exists()) mkdirs() }
    val thumbnailsDir = File(context.filesDir, "thumbnails").apply { if (!exists()) mkdirs() }
    val notesDir = File(context.filesDir, "notes").apply { if (!exists()) mkdirs() }
    
    private val catalogCacheFile = File(context.filesDir, "VRP-GameList.txt")
    private val tempInstallRoot = File(context.cacheDir, "install_temp")
    val downloadsDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "RookieOnQuest")
    val logsDir = File(downloadsDir, "logs").apply { if (!exists()) mkdirs() }

    fun getAllGamesFlow(): Flow<List<GameData>> = gameDao.getAllGames().map { entities ->
        entities.map { it.toData() }
    }

    fun searchGamesFlow(query: String): Flow<List<GameData>> = gameDao.searchGames("%$query%").map { entities ->
        entities.map { it.toData() }
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
                    
                    val existingData = gameDao.getAllGamesList().associate { 
                        it.releaseName to Triple(it.sizeBytes, it.description, it.screenshotUrlsJson) 
                    }
                    
                    val entities = newList.map { game ->
                        val existing = existingData[game.releaseName]
                        
                        // Local description check
                        val localNote = File(notesDir, "${game.releaseName}.txt")
                        val description = if (localNote.exists()) localNote.readText() else existing?.second
                        
                        game.copy(
                            sizeBytes = existing?.first,
                            description = description,
                            screenshotUrls = existing?.third?.split("|")?.filter { it.isNotEmpty() }
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
        val request = Request.Builder().url(url).header("User-Agent", "rclone/v1.72.1").build()
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

    suspend fun getGameRemoteInfo(game: GameData): Triple<Map<String, Long>, Long, Map<String, Any?>> = withContext(Dispatchers.IO) {
        val config = cachedConfig ?: throw Exception("Config not loaded")
        val hash = md5(game.releaseName + "\n")
        val sanitizedBase = if (config.baseUri.endsWith("/")) config.baseUri else "${config.baseUri}/"
        val dirUrl = "$sanitizedBase$hash/"

        val rawSegments = mutableListOf<String>()
        val screenshotUrls = mutableListOf<String>()
        
        // Use local metadata if available
        val localNote = File(notesDir, "${game.releaseName}.txt")
        var description: String? = if (localNote.exists()) localNote.readText() else game.description

        try {
            val request = Request.Builder().url(dirUrl).header("User-Agent", "rclone/v1.72.1").build()
            okHttpClient.newCall(request).await().use { response ->
                if (response.code == 404) {
                    gameDao.updateSize(game.releaseName, -1L)
                    throw Exception("Mirror error: 404")
                }
                if (!response.isSuccessful) throw Exception("Mirror error: ${response.code}")
                
                val html = response.body?.string() ?: ""
                
                // Extract all relevant entries (files or folders)
                val entryMatcher = java.util.regex.Pattern.compile("href\\s*=\\s*\"([^\"]+)\"", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(html)
                while (entryMatcher.find()) {
                    val entry = entryMatcher.group(1) ?: continue
                    if (entry.startsWith(".") || entry.startsWith("_") || entry.contains("notes.txt") || entry.contains("screenshot") || entry == "../") continue
                    
                    if (entry.endsWith("/")) {
                        // It's a folder (like com.beatgames.beatsaber/)
                        fetchAllFilesFromDir(dirUrl + entry, entry).forEach { rawSegments.add(it) }
                    } else if (entry.lowercase().let { it.endsWith(".apk") || it.endsWith(".obb") || it.contains(".7z.") || it.endsWith(".7z") }) {
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
                        val notesRequest = Request.Builder().url(notesUrl).header("User-Agent", "rclone/v1.72.1").build()
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
            
            // Deduplicate segments by filename (prefer root version)
            val uniqueSegments = rawSegments.groupBy { it.substringAfterLast('/') }
                .map { (_, list) -> 
                    list.minByOrNull { it.count { c -> c == '/' } }!!
                }

            val segmentMap = uniqueSegments.map { seg ->
                async {
                    val headRequest = Request.Builder()
                        .url(dirUrl + seg)
                        .head()
                        .header("User-Agent", "rclone/v1.72.1")
                        .build()
                    okHttpClient.newCall(headRequest).await().use { response ->
                        val size = response.header("Content-Length")?.toLongOrNull() ?: 0L
                        seg to size
                    }
                }
            }.awaitAll().toMap()
            
            val totalSize = segmentMap.values.sum()
            
            gameDao.updateSize(game.releaseName, totalSize)
            gameDao.updateMetadata(game.releaseName, description, screenshotUrls.joinToString("|"))
            
            Triple(segmentMap, totalSize, mapOf("description" to description, "screenshots" to screenshotUrls))
        } catch (e: Exception) {
            if (e.message?.contains("404") == true) {
                 gameDao.updateSize(game.releaseName, -1L)
            }
            throw e
        }
    }

    private suspend fun fetchAllFilesFromDir(baseUrl: String, prefix: String): List<String> = withContext(Dispatchers.IO) {
        val files = mutableListOf<String>()
        try {
            val request = Request.Builder().url(baseUrl).header("User-Agent", "rclone/v1.72.1").build()
            okHttpClient.newCall(request).await().use { response ->
                if (response.isSuccessful) {
                    val html = response.body?.string() ?: ""
                    val matcher = java.util.regex.Pattern.compile("href\\s*=\\s*\"([^\"]+)\"", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(html)
                    while (matcher.find()) {
                        val entry = matcher.group(1) ?: continue
                        if (entry.startsWith(".") || entry == "../") continue
                        if (entry.endsWith("/")) {
                            files.addAll(fetchAllFilesFromDir(baseUrl + entry, prefix + entry))
                        } else {
                            files.add(prefix + entry)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error listing dir $baseUrl", e)
        }
        files
    }

    private fun checkAvailableSpace(requiredBytes: Long) {
        val stat = StatFs(context.cacheDir.path)
        val availableBytes = stat.availableBlocksLong * stat.blockSizeLong 
        
        if (availableBytes < requiredBytes) {
            val requiredMb = requiredBytes / (1024 * 1024)
            val availableMb = availableBytes / (1024 * 1024)
            throw Exception("Insufficient storage space. Need ${requiredMb}MB, but only ${availableMb}MB available.")
        }
    }

    suspend fun installGame(
        game: GameData, 
        keepApk: Boolean = false,
        downloadOnly: Boolean = false,
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

        // 2. IMPORTANT: Always fetch ground truth from server first to verify what we have locally
        onProgress("Verifying with server...", 0.02f, 0, 0)
        val (remoteSegments, totalBytes, _) = getGameRemoteInfo(game)
        if (remoteSegments.isEmpty()) throw Exception("No installable files found on server")

        val hash = md5(game.releaseName + "\n")
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
            val config = cachedConfig ?: throw Exception("Config not loaded")
            val password = decodedPassword ?: throw Exception("Password not available")
            val sanitizedBase = if (config.baseUri.endsWith("/")) config.baseUri else "${config.baseUri}/"
            val dirUrl = "$sanitizedBase$hash/"

            // Pre-flight space check
            val isSevenZ = remoteSegments.keys.any { it.contains(".7z") }
            val multiplier = if (isSevenZ) if (downloadOnly || keepApk) 2.9 else 1.9 else 1.1
            checkAvailableSpace((totalBytes * multiplier).toLong())

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
                if (existingSize >= remoteSize) continue // Already have this part

                val segUrl = dirUrl + seg
                val segRequest = Request.Builder()
                    .url(segUrl)
                    .header("User-Agent", "rclone/v1.72.1")
                    .header("Range", "bytes=$existingSize-")
                    .build()

                try {
                    okHttpClient.newCall(segRequest).await().use { response ->
                        if (response.code != 416) {
                            if (!response.isSuccessful) throw Exception("Failed to download $seg: ${response.code}")
                            val isResume = response.code == 206
                            val body = response.body ?: throw Exception("Empty body for $seg")
                            
                            body.byteStream().use { input ->
                                localFile.parentFile?.let { if (!it.exists()) it.mkdirs() }
                                FileOutputStream(localFile, isResume).use { output ->
                                    val buffer = ByteArray(8192 * 8)
                                    var bytesRead: Int
                                    while (true) {
                                        currentCoroutineContext().ensureActive()
                                        bytesRead = input.read(buffer)
                                        if (bytesRead == -1) break
                                        output.write(buffer, 0, bytesRead)
                                        totalBytesDownloaded += bytesRead
                                        
                                        val overallProgress = if (totalBytes > 0) totalBytesDownloaded.toFloat() / totalBytes else 0f
                                        onProgress(
                                            "Downloading ${game.gameName} (${index + 1}/${remoteSegments.size})", 
                                            overallProgress * 0.8f,
                                            totalBytesDownloaded,
                                            totalBytes
                                        ) 
                                    }
                                }
                            }
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
            onProgress("Preparing files...", 0.82f, totalBytes, totalBytes)
            val isArchive = remoteSegments.keys.any { it.contains(".7z") }
            
            if (!isArchive) {
                remoteSegments.keys.forEach { seg ->
                    val source = File(gameTempDir, seg)
                    val target = File(extractionDir, seg)
                    if (source.exists()) {
                        target.parentFile?.let { if (!it.exists()) it.mkdirs() }
                        source.copyTo(target, overwrite = true)
                    }
                }
                extractionMarker.createNewFile()
            } else {
                val combinedFile = File(gameTempDir, "combined.7z")
                val archiveParts = remoteSegments.filter { it.key.contains(".7z") }
                val archiveTotalBytes = archiveParts.values.sum()

                if (!combinedFile.exists() || combinedFile.length() < archiveTotalBytes) {
                    onProgress("Merging files...", 0.85f, totalBytes, totalBytes)
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
                
                onProgress("Extracting...", 0.88f, totalBytes, totalBytes)
                val password = decodedPassword ?: ""
                try {
                    SevenZFile.builder().setFile(combinedFile).setPassword(password.toCharArray()).get().use { sevenZFile ->
                        var entry = sevenZFile.nextEntry
                        while (entry != null) {
                            currentCoroutineContext().ensureActive()
                            if (entry.name.endsWith(".apk", true) || entry.name.endsWith(".obb", true) || entry.name.contains(game.packageName)) {
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
            onProgress("Saving to Downloads...", 0.92f, totalBytes, totalBytes)
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
                            if (!source.name.endsWith(".apk", true)) {
                                val relPath = source.relativeTo(pf).path
                                val dest = File(finalObbDir, relPath)
                                if (source.isDirectory) dest.mkdirs()
                                else {
                                    source.copyTo(dest, overwrite = true)
                                    MediaScannerConnection.scanFile(context, arrayOf(dest.absolutePath), null, null)
                                }
                            }
                        }
                    }
                }
                looseObbs.forEach { loose ->
                    val targetObb = File(finalObbDir, loose.name)
                    if (loose.absolutePath != targetObb.absolutePath) {
                        copyFileWithScanner(loose, targetObb)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save files to downloads", e)
            }
        }

        if (downloadOnly) {
            onProgress("Download complete", 1f, totalBytes, totalBytes)
            gameTempDir.deleteRecursively()
            return@withContext null
        }

        val hasObbs = packageFolder != null || looseObbs.isNotEmpty()
        if (hasObbs) {
            onProgress("Installing OBBs...", 0.96f, totalBytes, totalBytes)
            moveObbFiles(packageFolder, looseObbs, game.packageName)
        }

        // Save info for post-install verification
        try {
            File(gameTempDir, "install.info").writeText("${game.packageName}\n$hasObbs")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write install info", e)
        }

        onProgress("Launching installer...", 1f, totalBytes, totalBytes)
        if (finalApk == null || !finalApk.exists()) {
             throw Exception("Final APK not found for installation")
        }
        val externalApk = File(context.getExternalFilesDir(null), finalApk.name)
        finalApk.copyTo(externalApk, overwrite = true)
        
        externalApk
    }

    suspend fun verifyAndCleanupInstalls(excludedReleaseNames: Set<String> = emptySet()) = withContext(Dispatchers.IO) {
        if (!tempInstallRoot.exists()) return@withContext
        
        val installedPackages = getInstalledPackagesMap()
        val excludedHashes = excludedReleaseNames.map { md5(it + "\n") }.toSet()
        
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
                            val obbDir = File(Environment.getExternalStorageDirectory(), "Android/obb/$packageName")
                            obbOk = obbDir.exists() && (obbDir.list()?.isNotEmpty() ?: false)
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
        
        context.getExternalFilesDir(null)?.listFiles()?.forEach { file ->
            if (file.name.endsWith(".apk") && System.currentTimeMillis() - file.lastModified() > 1000 * 60 * 60 * 24) {
                file.delete()
            }
        }
    }

    fun cleanupInstall(releaseName: String, totalBytes: Long) {
        val hash = md5(releaseName + "\n")
        val gameTempDir = File(tempInstallRoot, hash)
        if (!gameTempDir.exists()) return
        gameTempDir.deleteRecursively()
    }

    suspend fun deleteDownloadedGame(releaseName: String) = withContext(Dispatchers.IO) {
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
                        if (!isFromDownloads) source.delete()
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
            val request = Request.Builder().url(url).head().header("User-Agent", "rclone/v1.72.1").build()
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

    private fun md5(input: String): String {
        val bytes = MessageDigest.getInstance("MD5").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
