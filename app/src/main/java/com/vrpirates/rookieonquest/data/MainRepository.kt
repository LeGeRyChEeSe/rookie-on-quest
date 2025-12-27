package com.vrpirates.rookieonquest.data

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.util.Base64
import android.util.Log
import com.vrpirates.rookieonquest.logic.CatalogParser
import com.vrpirates.rookieonquest.network.PublicConfig
import com.vrpirates.rookieonquest.network.VrpService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
    private val catalogCacheFile = File(context.filesDir, "VRP-GameList.txt")
    private val tempInstallRoot = File(context.cacheDir, "install_temp")

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

    suspend fun downloadCatalog(baseUri: String): List<GameData> = withContext(Dispatchers.IO) {
        catalogMutex.withLock {
            val sanitizedBase = if (baseUri.endsWith("/")) baseUri else "$baseUri/"
            val metaUrl = "${sanitizedBase}meta.7z"
            
            val lastModified = getRemoteLastModified(metaUrl)
            val savedModified = prefs.getString("meta_last_modified", "")
            
            if (catalogCacheFile.exists() && lastModified == savedModified && lastModified != null) {
                return@withContext CatalogParser.parse(catalogCacheFile.readText())
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
                    CatalogParser.parse(gameListContent)
                } else if (catalogCacheFile.exists()) {
                    CatalogParser.parse(catalogCacheFile.readText())
                } else {
                    throw Exception("Failed to extract catalog")
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
                if (entry.name.endsWith("VRP-GameList.txt", ignoreCase = true)) {
                    val out = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    while (sevenZFile.read(buffer).also { bytesRead = it } != -1) {
                        out.write(buffer, 0, bytesRead)
                    }
                    val content = out.toString("UTF-8")
                    catalogCacheFile.writeText(content)
                    onGameListFound(content)
                } else if (entry.name.endsWith(".png", ignoreCase = true) || entry.name.endsWith(".jpg", ignoreCase = true)) {
                    val fileName = entry.name.substringAfterLast("/")
                    val iconFile = File(iconsDir, fileName)
                    if (!iconFile.exists()) {
                        FileOutputStream(iconFile).use { out ->
                            val buffer = ByteArray(8192)
                            var bytesRead: Int
                            while (sevenZFile.read(buffer).also { bytesRead = it } != -1) {
                                out.write(buffer, 0, bytesRead)
                            }
                        }
                    }
                }
                entry = sevenZFile.nextEntry
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

    suspend fun getGameRemoteInfo(game: GameData): Pair<List<String>, Long> = withContext(Dispatchers.IO) {
        val config = cachedConfig ?: throw Exception("Config not loaded")
        val hash = md5(game.releaseName + "\n")
        val sanitizedBase = if (config.baseUri.endsWith("/")) config.baseUri else "${config.baseUri}/"
        val dirUrl = "$sanitizedBase$hash/"

        val request = Request.Builder().url(dirUrl).header("User-Agent", "rclone/v1.72.1").build()
        val segments = mutableListOf<String>()
        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("Mirror error: ${response.code}")
            val html = response.body?.string() ?: ""
            val matcher = java.util.regex.Pattern.compile("href\\s*=\\s*\"([^\"]+\\.(7z\\.\\d{3}|apk))\"", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(html)
            while (matcher.find()) {
                matcher.group(1)?.let { segments.add(it) }
            }
        }
        segments.sort()

        var totalSize = 0L
        for (seg in segments) {
            val headRequest = Request.Builder().url(dirUrl + seg).head().header("User-Agent", "rclone/v1.72.1").build()
            okHttpClient.newCall(headRequest).execute().use { response ->
                totalSize += response.header("Content-Length")?.toLongOrNull() ?: 0L
            }
        }

        segments to totalSize
    }

    suspend fun getDownloadedSize(gameReleaseName: String): Long {
        val hash = md5(gameReleaseName + "\n")
        val gameTempDir = File(tempInstallRoot, hash)
        if (!gameTempDir.exists()) return 0L
        return gameTempDir.listFiles()?.filter { !it.isDirectory }?.sumOf { it.length() } ?: 0L
    }

    fun cancelInstallation(gameReleaseName: String) {
        val hash = md5(gameReleaseName + "\n")
        val gameTempDir = File(tempInstallRoot, hash)
        if (gameTempDir.exists()) {
            gameTempDir.deleteRecursively()
        }
    }

    suspend fun installGame(
        game: GameData, 
        onProgress: (String, Float, Long, Long) -> Unit
    ): File? = withContext(Dispatchers.IO) {
        val config = cachedConfig ?: throw Exception("Config not loaded")
        val password = decodedPassword ?: throw Exception("Password not available")
        val hash = md5(game.releaseName + "\n")
        val sanitizedBase = if (config.baseUri.endsWith("/")) config.baseUri else "${config.baseUri}/"
        val dirUrl = "$sanitizedBase$hash/"
        
        onProgress("Connecting to mirror...", 0.05f, 0, 0)

        val (segments, totalBytes) = getGameRemoteInfo(game)
        if (segments.isEmpty()) throw Exception("No installable files found")

        val gameTempDir = File(tempInstallRoot, hash)
        if (!gameTempDir.exists()) gameTempDir.mkdirs()
        
        val localPaths = mutableListOf<File>()
        var totalBytesDownloaded = 0L

        // PRE-CALCULATE already downloaded bytes for global progress
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

            okHttpClient.newCall(segRequest).execute().use { response ->
                if (response.code == 416) {
                    // Already done
                } else {
                    if (!response.isSuccessful) throw Exception("Failed to download $seg: ${response.code}")
                    val isResume = response.code == 206
                    val body = response.body ?: throw Exception("Empty body")
                    
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
        }

        ensureActive()
        onProgress("Merging files...", 0.85f, totalBytes, totalBytes)
        val extractionDir = File(gameTempDir, "extracted").apply { if (exists()) deleteRecursively(); mkdirs() }
        
        if (localPaths.size == 1 && localPaths[0].name.endsWith(".apk", true)) {
            localPaths[0].copyTo(File(extractionDir, localPaths[0].name), overwrite = true)
        } else {
            val combinedFile = File(gameTempDir, "combined.7z")
            combinedFile.outputStream().use { out ->
                localPaths.forEach { part -> 
                    ensureActive()
                    part.inputStream().use { it.copyTo(out) } 
                }
            }
            
            onProgress("Extracting...", 0.88f, totalBytes, totalBytes)
            ensureActive()
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
            if (combinedFile.exists()) combinedFile.delete()
        }

        val apks = extractionDir.listFiles { _, name -> name.endsWith(".apk", true) }
        if (apks.isNullOrEmpty()) throw Exception("No APK found")
        val finalApk = apks[0]

        val obbs = extractionDir.listFiles { _, name -> name.endsWith(".obb", true) }
        if (!obbs.isNullOrEmpty()) {
            onProgress("Installing OBBs...", 0.96f, totalBytes, totalBytes)
            moveObbFiles(obbs, game.packageName)
        }

        onProgress("Launching installer...", 1f, totalBytes, totalBytes)
        val externalApk = File(context.getExternalFilesDir(null), finalApk.name)
        finalApk.copyTo(externalApk, overwrite = true)
        
        gameTempDir.deleteRecursively()
        externalApk
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

    private fun md5(input: String): String {
        val bytes = MessageDigest.getInstance("MD5").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
