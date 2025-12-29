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
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

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

    private fun downloadFile(url: String, targetFile: File) {
        val request = Request.Builder().url(url).header("User-Agent", "rclone/v1.72.1").build()
        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("HTTP ${response.code}")
            response.body?.byteStream()?.use { input ->
                targetFile.outputStream().use { output -> input.copyTo(output) }
            }
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

    suspend fun getGameRemoteInfo(game: GameData): Triple<List<String>, Long, Map<String, Any?>> = withContext(Dispatchers.IO) {
        val config = cachedConfig ?: throw Exception("Config not loaded")
        val hash = md5(game.releaseName + "\n")
        val sanitizedBase = if (config.baseUri.endsWith("/")) config.baseUri else "${config.baseUri}/"
        val dirUrl = "$sanitizedBase$hash/"

        val segments = mutableListOf<String>()
        val screenshotUrls = mutableListOf<String>()
        
        // Use local metadata if available
        val localNote = File(notesDir, "${game.releaseName}.txt")
        var description: String? = if (localNote.exists()) localNote.readText() else game.description

        try {
            val request = Request.Builder().url(dirUrl).header("User-Agent", "rclone/v1.72.1").build()
            okHttpClient.newCall(request).execute().use { response ->
                if (response.code == 404) {
                    gameDao.updateSize(game.releaseName, -1L)
                    throw Exception("Mirror error: 404")
                }
                if (!response.isSuccessful) throw Exception("Mirror error: ${response.code}")
                
                val html = response.body?.string() ?: ""
                
                // Extract install segments
                val segmentMatcher = java.util.regex.Pattern.compile("href\\s*=\\s*\"([^\"]+\\.(7z\\.\\d{3}|apk))\"", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(html)
                while (segmentMatcher.find()) {
                    segmentMatcher.group(1)?.let { segments.add(it) }
                }

                // Extract screenshots ( gameplay images found on mirror )
                val imgMatcher = java.util.regex.Pattern.compile("href\\s*=\\s*\"([^\"]+\\.(jpg|png|jpeg))\"", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(html)
                while (imgMatcher.find()) {
                    val imgName = imgMatcher.group(1) ?: continue
                    // Skip images that are likely icons/thumbnails (contain package name)
                    if (!imgName.contains(game.packageName, ignoreCase = true)) {
                        screenshotUrls.add(dirUrl + imgName)
                    }
                }

                // Extract description from remote notes.txt if local is missing
                if (description == null && html.contains("notes.txt", ignoreCase = true)) {
                    val notesUrl = dirUrl + "notes.txt"
                    try {
                        val notesRequest = Request.Builder().url(notesUrl).header("User-Agent", "rclone/v1.72.1").build()
                        okHttpClient.newCall(notesRequest).execute().use { notesResponse ->
                            if (notesResponse.isSuccessful) {
                                description = notesResponse.body?.string()?.trim()
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to fetch remote notes.txt for ${game.gameName}")
                    }
                }
            }
            segments.sort()

            val totalSize = segments.map { seg ->
                async {
                    val headRequest = Request.Builder()
                        .url(dirUrl + seg)
                        .head()
                        .header("User-Agent", "rclone/v1.72.1")
                        .build()
                    okHttpClient.newCall(headRequest).execute().use { response ->
                        response.header("Content-Length")?.toLongOrNull() ?: 0L
                    }
                }
            }.awaitAll().sum()
            
            gameDao.updateSize(game.releaseName, totalSize)
            gameDao.updateMetadata(game.releaseName, description, screenshotUrls.joinToString("|"))
            
            Triple(segments, totalSize, mapOf("description" to description, "screenshots" to screenshotUrls))
        } catch (e: Exception) {
            if (e.message?.contains("404") == true) {
                 gameDao.updateSize(game.releaseName, -1L)
            }
            throw e
        }
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

        // 2. Check for existing APK in downloads or external storage
        val safeDirName = game.releaseName.replace(Regex("[^a-zA-Z0-9.-]"), "_")
        val gameDownloadDir = File(downloadsDir, safeDirName)
        val safeGameName = game.gameName.replace(Regex("[^a-zA-Z0-9.-]"), "_")
        val cachedApk = File(gameDownloadDir, "${safeGameName}_v${game.versionCode}.apk")

        if (downloadOnly && isApkMatching(cachedApk, game.packageName, targetVersion)) {
            onProgress("Already downloaded", 1f, 0, 0)
            delay(1000)
            return@withContext null
        }

        var readyApk: File? = null
        
        // Check if it's already in the external installer dir (cancelled previous install)
        val externalApkDir = context.getExternalFilesDir(null)
        readyApk = externalApkDir?.listFiles()?.find { isApkMatching(it, game.packageName, targetVersion) }
        
        // If not found, check in downloads directory
        if (readyApk == null && isApkMatching(cachedApk, game.packageName, targetVersion)) {
            onProgress("Found in downloads...", 0.85f, 0, 0)
            val externalApk = File(context.getExternalFilesDir(null), cachedApk.name)
            try {
                cachedApk.copyTo(externalApk, overwrite = true)
                readyApk = externalApk
                
                // Also try to move OBBs if they exist in the download dir
                val obbs = gameDownloadDir.listFiles { _, name -> name.endsWith(".obb", true) }
                if (!obbs.isNullOrEmpty()) {
                    onProgress("Installing OBBs from downloads...", 0.95f, 0, 0)
                    moveObbFiles(obbs, game.packageName)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to copy APK from downloads: ${e.message}")
            }
        }

        if (!downloadOnly && readyApk != null) {
            onProgress("Launching installer...", 1f, 0, 0)
            delay(500)
            return@withContext readyApk
        }

        val config = cachedConfig ?: throw Exception("Config not loaded")
        val password = decodedPassword ?: throw Exception("Password not available")
        val hash = md5(game.releaseName + "\n")
        val sanitizedBase = if (config.baseUri.endsWith("/")) config.baseUri else "${config.baseUri}/"
        val dirUrl = "$sanitizedBase$hash/"
        
        onProgress("Connecting to mirror...", 0.05f, 0, 0)

        val (segments, totalBytes, _) = getGameRemoteInfo(game)
        if (segments.isEmpty()) throw Exception("No installable files found")

        // Pre-flight space check
        val isSevenZ = segments.any { it.contains(".7z") }
        val multiplier = if (isSevenZ) 1.9 else 1.1
        checkAvailableSpace((totalBytes * multiplier).toLong())

        val gameTempDir = File(tempInstallRoot, hash)
        if (!gameTempDir.exists()) gameTempDir.mkdirs()
        
        val localPaths = mutableListOf<File>()
        var totalBytesDownloaded = 0L

        for (seg in segments) {
            val f = File(gameTempDir, seg)
            if (f.exists()) totalBytesDownloaded += f.length()
        }

        for ((index, seg) in segments.withIndex()) {
            ensureActive()
            val localFile = File(gameTempDir, seg)
            localPaths.add(localFile)
            val segUrl = dirUrl + seg
            
            val existingSize = if (localFile.exists()) localFile.length() else 0L
            
            val segRequest = Request.Builder()
                .url(segUrl)
                .header("User-Agent", "rclone/v1.72.1")
                .header("Range", "bytes=$existingSize-")
                .build()

            try {
                okHttpClient.newCall(segRequest).execute().use { response ->
                    if (response.code == 416) {
                        // Already done or range not satisfiable
                    } else {
                        if (!response.isSuccessful) throw Exception("Failed to download $seg: ${response.code}")
                        val isResume = response.code == 206
                        val body = response.body ?: throw Exception("Empty body for $seg")
                        
                        body.byteStream().use { input ->
                            FileOutputStream(localFile, isResume).use { output ->
                                val buffer = ByteArray(8192 * 8)
                                var bytesRead: Int
                                while (input.read(buffer).also { bytesRead = it } != -1) {
                                    ensureActive()
                                    output.write(buffer, 0, bytesRead)
                                    totalBytesDownloaded += bytesRead
                                    
                                    val overallProgress = if (totalBytes > 0) totalBytesDownloaded.toFloat() / totalBytes else 0f
                                    onProgress(
                                        "Downloading ${game.gameName} (Part ${index + 1}/${segments.size})", 
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

        ensureActive()
        val extractionDir = File(gameTempDir, "extracted").apply { if (!exists()) mkdirs() }
        val extractionMarker = File(gameTempDir, "extraction_done.marker")

        if (!extractionMarker.exists()) {
            onProgress("Preparing extraction...", 0.82f, totalBytes, totalBytes)
            
            if (localPaths.size == 1 && localPaths[0].name.endsWith(".apk", true)) {
                localPaths[0].copyTo(File(extractionDir, localPaths[0].name), overwrite = true)
            } else {
                val combinedFile = File(gameTempDir, "combined.7z")
                
                if (!combinedFile.exists() || combinedFile.length() < totalBytes) {
                    onProgress("Merging files...", 0.85f, totalBytes, totalBytes)
                    combinedFile.outputStream().use { out ->
                        localPaths.forEach { part -> 
                            ensureActive()
                            part.inputStream().use { it.copyTo(out) } 
                        }
                    }
                }
                
                onProgress("Extracting...", 0.88f, totalBytes, totalBytes)
                ensureActive()
                try {
                    SevenZFile.builder().setFile(combinedFile).setPassword(password.toCharArray()).get().use { sevenZFile ->
                        var entry = sevenZFile.nextEntry
                        while (entry != null) {
                            ensureActive()
                            if (entry.name.endsWith(".apk", true) || entry.name.endsWith(".obb", true)) {
                                val fileName = File(entry.name).name
                                val outFile = File(extractionDir, fileName)
                                
                                FileOutputStream(outFile).use { out ->
                                    val buffer = ByteArray(8192 * 8)
                                    var bytesRead: Int
                                    while (sevenZFile.read(buffer).also { bytesRead = it } != -1) {
                                        out.write(buffer, 0, bytesRead)
                                    }
                                }
                            }
                            entry = sevenZFile.nextEntry
                        }
                    }
                    extractionMarker.createNewFile()
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Log.e(TAG, "Extraction failed", e)
                    extractionDir.deleteRecursively()
                    extractionDir.mkdirs()
                    throw Exception("Extraction failed: ${e.message ?: "Check your storage or password"}")
                } finally {
                    if (combinedFile.exists()) combinedFile.delete()
                }
            }
        }

        val apks = extractionDir.listFiles { _, name -> name.endsWith(".apk", true) }
        if (apks.isNullOrEmpty()) {
            extractionMarker.delete()
            throw Exception("No APK found in extracted files")
        }
        val finalApk = apks[0]

        val obbs = extractionDir.listFiles { _, name -> name.endsWith(".obb", true) }
        
        if (downloadOnly || keepApk) {
            onProgress("Saving files...", 0.92f, totalBytes, totalBytes)
            try {
                if (!downloadsDir.exists()) downloadsDir.mkdirs()
                val safeDirName = game.releaseName.replace(Regex("[^a-zA-Z0-9.-]"), "_")
                val gameDownloadDir = File(downloadsDir, safeDirName)
                if (!gameDownloadDir.exists()) gameDownloadDir.mkdirs()
                
                // Use a better name for the saved APK
                val safeGameName = game.gameName.replace(Regex("[^a-zA-Z0-9.-]"), "_")
                val targetApk = File(gameDownloadDir, "${safeGameName}_v${game.versionCode}.apk")
                
                copyFileWithScanner(finalApk, targetApk)
                
                obbs?.forEach { obb ->
                    val targetObb = File(gameDownloadDir, obb.name)
                    copyFileWithScanner(obb, targetObb)
                }
                Log.d(TAG, "Successfully saved files to ${gameDownloadDir.absolutePath}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save files to downloads", e)
                if (downloadOnly) throw e
            }
        }

        if (downloadOnly) {
            onProgress("Download complete", 1f, totalBytes, totalBytes)
            gameTempDir.deleteRecursively()
            return@withContext null
        }

        if (!obbs.isNullOrEmpty()) {
            onProgress("Installing OBBs...", 0.96f, totalBytes, totalBytes)
            moveObbFiles(obbs, game.packageName)
        }

        onProgress("Launching installer...", 1f, totalBytes, totalBytes)
        val externalApk = File(context.getExternalFilesDir(null), finalApk.name)
        try {
            finalApk.copyTo(externalApk, overwrite = true)
        } catch (e: Exception) {
            throw Exception("Failed to copy APK to installer directory: ${e.message}")
        }
        
        gameTempDir.deleteRecursively()
        externalApk
    }

    fun cleanupInstall(releaseName: String, totalBytes: Long) {
        val hash = md5(releaseName + "\n")
        val gameTempDir = File(tempInstallRoot, hash)
        if (!gameTempDir.exists()) return

        val combinedFile = File(gameTempDir, "combined.7z")
        val extractionMarker = File(gameTempDir, "extraction_done.marker")
        val extractionDir = File(gameTempDir, "extracted")

        // 1. If extraction is incomplete, delete extracted files
        if (!extractionMarker.exists() && extractionDir.exists()) {
            extractionDir.deleteRecursively()
        }

        // 2. If archive is incomplete, delete it
        if (combinedFile.exists() && combinedFile.length() < totalBytes) {
            combinedFile.delete()
        }
        
        // Also check segments if combined.7z doesn't exist yet
        if (!combinedFile.exists()) {
            val segments = gameTempDir.listFiles { _, name -> name.endsWith(".7z.001") || name.endsWith(".apk") }
            // If we are cancelling, we might want to delete them if they are not complete
            // But usually "Cancel" implies cleanup everything. 
            // The requirement says "si l'archive est incomplète on supprime l'archive".
            // If we have segments, they are the archive.
            gameTempDir.deleteRecursively()
        }

        if (gameTempDir.exists() && gameTempDir.listFiles()?.isEmpty() == true) {
            gameTempDir.delete()
        }
    }

    private fun copyFileWithScanner(source: File, target: File) {
        try {
            source.inputStream().use { input ->
                target.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), null, null)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy file and scan: ${source.name} to ${target.name}", e)
            throw e
        }
    }

    private fun moveObbFiles(obbs: Array<File>, packageName: String) {
        val obbBaseDir = File(Environment.getExternalStorageDirectory(), "Android/obb/$packageName")
        if (!obbBaseDir.exists()) obbBaseDir.mkdirs()
        
        for (obb in obbs) {
            val destFile = File(obbBaseDir, obb.name)
            try {
                if (!obb.renameTo(destFile)) {
                    FileInputStream(obb).use { input ->
                        FileOutputStream(destFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                    obb.delete()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to move OBB: ${obb.name}", e)
            }
        }
    }

    private fun getRemoteLastModified(url: String): String? {
        return try {
            val request = Request.Builder().url(url).head().header("User-Agent", "rclone/v1.72.1").build()
            okHttpClient.newCall(request).execute().use { it.header("Last-Modified") }
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
