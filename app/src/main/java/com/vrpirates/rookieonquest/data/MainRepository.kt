package com.vrpirates.rookieonquest.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.util.Base64
import android.util.Log
import androidx.core.content.FileProvider
import com.vrpirates.rookieonquest.logic.CatalogParser
import com.vrpirates.rookieonquest.network.PublicConfig
import com.vrpirates.rookieonquest.network.VrpService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
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
import java.util.regex.Pattern

class MainRepository(private val context: Context) {
    private val retrofit = Retrofit.Builder()
        .baseUrl("https://vrpirates.wiki/")
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    private val service = retrofit.create(VrpService::class.java)
    private val okHttpClient = OkHttpClient()
    private val prefs = context.getSharedPreferences("rookie_prefs", Context.MODE_PRIVATE)

    private var cachedConfig: PublicConfig? = null
    private var decodedPassword: String? = null
    
    val iconsDir = File(context.filesDir, "icons").apply { if (!exists()) mkdirs() }
    private val catalogCacheFile = File(context.filesDir, "VRP-GameList.txt")
    private val tempInstallFolder = File(context.cacheDir, "install_temp")

    init {
        // Clean up any remaining temp files from a previous session (e.g. after a crash)
        cleanupOrphanedFiles()
    }

    private fun cleanupOrphanedFiles() {
        try {
            if (tempInstallFolder.exists()) {
                tempInstallFolder.deleteRecursively()
                Log.d("MainRepository", "Cleaned up orphaned installation files from previous session.")
            }
        } catch (e: Exception) {
            Log.e("MainRepository", "Failed to cleanup orphaned files", e)
        }
    }

    suspend fun fetchConfig(): PublicConfig = withContext(Dispatchers.IO) {
        try {
            val config = service.getPublicConfig()
            cachedConfig = config
            decodedPassword = String(Base64.decode(config.password64, Base64.DEFAULT), Charsets.UTF_8)
            config
        } catch (e: Exception) {
            Log.e("MainRepository", "Error fetching config", e)
            throw e
        }
    }

    suspend fun downloadCatalog(baseUri: String): List<GameData> = withContext(Dispatchers.IO) {
        val sanitizedBase = if (baseUri.endsWith("/")) baseUri else "$baseUri/"
        val metaUrl = "${sanitizedBase}meta.7z"
        
        val lastModified = getRemoteLastModified(metaUrl)
        val savedModified = prefs.getString("meta_last_modified", "")
        
        if (catalogCacheFile.exists() && lastModified == savedModified && lastModified != null) {
            Log.d("MainRepository", "Catalog is up to date, loading from cache.")
            return@withContext CatalogParser.parse(catalogCacheFile.readText())
        }

        val tempMetaFile = File(context.cacheDir, "meta.7z")
        val request = Request.Builder().url(metaUrl).header("User-Agent", "rclone/v1.72.1").build()

        try {
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw Exception("Failed to download meta.7z")
                response.body?.byteStream()?.use { input ->
                    tempMetaFile.outputStream().use { output -> input.copyTo(output) }
                }
            }

            val password = decodedPassword ?: throw Exception("Password not available")
            var gameListContent = ""
            
            SevenZFile.builder().setFile(tempMetaFile).setPassword(password.toCharArray()).get().use { sevenZFile ->
                var entry = sevenZFile.nextEntry
                while (entry != null) {
                    if (entry.name.endsWith("VRP-GameList.txt", ignoreCase = true)) {
                        val out = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        while (sevenZFile.read(buffer).also { bytesRead = it } != -1) {
                            out.write(buffer, 0, bytesRead)
                        }
                        gameListContent = out.toString("UTF-8")
                        catalogCacheFile.writeText(gameListContent)
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
            
            if (lastModified != null) {
                prefs.edit().putString("meta_last_modified", lastModified).apply()
            }
            
            CatalogParser.parse(gameListContent)
        } finally {
            if (tempMetaFile.exists()) tempMetaFile.delete()
        }
    }

    private fun getRemoteLastModified(url: String): String? {
        return try {
            val request = Request.Builder().url(url).head().header("User-Agent", "rclone/v1.72.1").build()
            okHttpClient.newCall(request).execute().use { it.header("Last-Modified") }
        } catch (e: Exception) {
            null
        }
    }

    suspend fun installGame(game: GameData, onProgress: (String, Float) -> Unit) = withContext(Dispatchers.IO) {
        val config = cachedConfig ?: throw Exception("Config not loaded")
        val password = decodedPassword ?: throw Exception("Password not available")
        val sanitizedBase = if (config.baseUri.endsWith("/")) config.baseUri else "${config.baseUri}/"
        
        val hash = md5(game.releaseName + "\n")
        val dirUrl = "$sanitizedBase$hash/"
        
        onProgress("Connecting to mirror...", 0.05f)
        ensureActive()

        val request = Request.Builder().url(dirUrl).header("User-Agent", "rclone/v1.72.1").build()
        val segments = mutableListOf<String>()
        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("Mirror error: ${response.code}")
            val html = response.body?.string() ?: ""
            val matcher = Pattern.compile("href\\s*=\\s*\"([^\"]+\\.(7z\\.\\d{3}|apk))\"", Pattern.CASE_INSENSITIVE).matcher(html)
            while (matcher.find()) {
                segments.add(matcher.group(1))
            }
        }
        
        if (segments.isEmpty()) throw Exception("No installable files found")
        segments.sort()

        if (tempInstallFolder.exists()) tempInstallFolder.deleteRecursively()
        tempInstallFolder.mkdirs()
        val localPaths = mutableListOf<File>()
        
        try {
            for ((index, seg) in segments.withIndex()) {
                ensureActive()
                val localFile = File(tempInstallFolder, seg)
                localPaths.add(localFile)
                val segUrl = dirUrl + seg
                
                val segRequest = Request.Builder().url(segUrl).header("User-Agent", "rclone/v1.72.1").build()
                okHttpClient.newCall(segRequest).execute().use { response ->
                    if (!response.isSuccessful) throw Exception("Failed to download $seg")
                    val body = response.body ?: throw Exception("Empty body for $seg")
                    val contentLength = body.contentLength()
                    
                    body.byteStream().use { input ->
                        localFile.outputStream().use { output ->
                            val buffer = ByteArray(8192 * 4)
                            var bytesRead: Int
                            var partBytesRead = 0L
                            while (input.read(buffer).also { bytesRead = it } != -1) {
                                ensureActive()
                                output.write(buffer, 0, bytesRead)
                                partBytesRead += bytesRead
                                
                                val partProgress = if (contentLength > 0) partBytesRead.toFloat() / contentLength else 0f
                                val overallDownloadProgress = (index.toFloat() + partProgress) / segments.size
                                onProgress("Downloading ${game.gameName} (Part ${index + 1}/${segments.size})", overallDownloadProgress * 0.8f) 
                            }
                        }
                    }
                }
            }

            ensureActive()
            onProgress("Merging files...", 0.85f)
            val combinedFile = File(tempInstallFolder, "combined.7z")
            val extractionDir = File(tempInstallFolder, "extracted").apply { mkdirs() }
            
            if (localPaths.size == 1 && localPaths[0].name.endsWith(".apk")) {
                localPaths[0].copyTo(File(extractionDir, localPaths[0].name))
            } else {
                combinedFile.outputStream().use { out ->
                    localPaths.forEach { part -> 
                        ensureActive()
                        part.inputStream().use { it.copyTo(out) } 
                    }
                }
                
                onProgress("Preparing extraction...", 0.88f)
                ensureActive()

                SevenZFile.builder().setFile(combinedFile).setPassword(password.toCharArray()).get().use { sevenZFile ->
                    var entry = sevenZFile.nextEntry
                    while (entry != null) {
                        ensureActive()
                        val entryName = entry.name.lowercase()
                        if (entryName.endsWith(".apk") || entryName.endsWith(".obb")) {
                            val fileName = File(entry.name).name
                            val outFile = File(extractionDir, fileName)
                            val entryTotalSize = entry.size
                            
                            FileOutputStream(outFile).use { out ->
                                val buffer = ByteArray(8192 * 4)
                                var bytesRead: Int
                                var entryRead = 0L
                                while (sevenZFile.read(buffer).also { bytesRead = it } != -1) {
                                    ensureActive()
                                    out.write(buffer, 0, bytesRead)
                                    entryRead += bytesRead
                                    onProgress("Extracting $fileName...", 0.9f + (entryRead.toFloat() / entryTotalSize) * 0.05f)
                                }
                            }
                        }
                        entry = sevenZFile.nextEntry
                    }
                }
            }

            ensureActive()
            val apks = extractionDir.listFiles { _, name -> name.endsWith(".apk", true) }
            if (apks.isNullOrEmpty()) throw Exception("No APK found in extracted files")
            val finalApk = apks[0]

            onProgress("Installing OBB files...", 0.96f)
            val obbs = extractionDir.listFiles { _, name -> name.endsWith(".obb", true) }
            if (!obbs.isNullOrEmpty()) {
                moveObbFiles(obbs, game.packageName)
            }

            ensureActive()
            onProgress("Launching installer...", 1f)
            launchInstaller(finalApk)
            
        } finally {
            if (tempInstallFolder.exists()) {
                tempInstallFolder.deleteRecursively()
            }
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
                Log.d("MainRepository", "Moved OBB successfully: ${destFile.absolutePath}")
            } catch (e: Exception) {
                Log.e("MainRepository", "Failed to move OBB: ${obb.name}", e)
            }
        }
    }

    private fun launchInstaller(apkFile: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!context.packageManager.canRequestPackageInstalls()) {
                val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                return
            }
        }

        val externalApk = File(context.getExternalFilesDir(null), apkFile.name)
        try {
            apkFile.copyTo(externalApk, overwrite = true)
            
            val authority = "${context.packageName}.fileprovider"
            val uri = FileProvider.getUriForFile(context, authority, externalApk)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e("MainRepository", "Failed to launch installer", e)
        }
    }

    private fun md5(input: String): String {
        val bytes = MessageDigest.getInstance("MD5").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
