package com.vrpirates.rookieonquest.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.util.Base64
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.vrpirates.rookieonquest.R
import com.vrpirates.rookieonquest.data.AppDatabase
import com.vrpirates.rookieonquest.data.Constants
import com.vrpirates.rookieonquest.data.CryptoUtils
import com.vrpirates.rookieonquest.data.DownloadUtils
import com.vrpirates.rookieonquest.data.FilePaths
import com.vrpirates.rookieonquest.data.InstallStatus
import com.vrpirates.rookieonquest.data.NetworkModule
import com.vrpirates.rookieonquest.data.toData
import com.vrpirates.rookieonquest.network.PublicConfig
import com.vrpirates.rookieonquest.network.VrpService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class DownloadWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "DownloadWorker"

        const val KEY_RELEASE_NAME = "release_name"
        const val KEY_IS_DOWNLOAD_ONLY = "is_download_only"
        const val KEY_KEEP_APK = "keep_apk"

        const val KEY_PROGRESS = "progress"
        const val KEY_DOWNLOADED_BYTES = "downloaded_bytes"
        const val KEY_TOTAL_BYTES = "total_bytes"
        const val KEY_STATUS = "status"

        const val NOTIFICATION_CHANNEL_ID = "download_progress"
        const val NOTIFICATION_ID = 1001
    }

    private val db = AppDatabase.getDatabase(applicationContext)
    private val queuedInstallDao = db.queuedInstallDao()
    private val gameDao = db.gameDao()

    // Use shared network instances from NetworkModule (singleton)
    private val okHttpClient = NetworkModule.okHttpClient
    private val service = NetworkModule.retrofit.create(VrpService::class.java)

    private val tempInstallRoot = File(applicationContext.cacheDir, "install_temp")
    private val downloadsDir = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
        FilePaths.DOWNLOADS_ROOT_DIR_NAME
    )

    private var cachedConfig: PublicConfig? = null
    private var decodedPassword: String? = null

    override suspend fun doWork(): Result {
        val releaseName = inputData.getString(KEY_RELEASE_NAME)
            ?: return Result.failure(workDataOf(KEY_STATUS to "Missing release name"))

        val isDownloadOnly = inputData.getBoolean(KEY_IS_DOWNLOAD_ONLY, false)
        val keepApk = inputData.getBoolean(KEY_KEEP_APK, false)

        Log.i(TAG, "Starting download work for: $releaseName (attempt: $runAttemptCount)")

        return try {
            setForeground(getForegroundInfo())
            executeDownload(releaseName, isDownloadOnly, keepApk)
        } catch (e: CancellationException) {
            // CRITICAL: Rethrow CancellationException to allow proper coroutine cancellation
            // WorkManager will handle this and set the work state to CANCELLED
            Log.d(TAG, "Download cancelled for $releaseName")
            updateStatus(releaseName, InstallStatus.PAUSED)
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Download failed for $releaseName", e)
            handleFailure(releaseName, e)
        }
    }

    private suspend fun executeDownload(
        releaseName: String,
        isDownloadOnly: Boolean,
        keepApk: Boolean
    ): Result = withContext(Dispatchers.IO) {
        updateStatus(releaseName, InstallStatus.DOWNLOADING)

        val config = fetchConfig()
        val game = gameDao.getByReleaseName(releaseName)?.toData()
            ?: throw Exception("Game not found in catalog: $releaseName")

        val hash = CryptoUtils.md5(releaseName + "\n")
        val sanitizedBase = if (config.baseUri.endsWith("/")) config.baseUri else "${config.baseUri}/"
        val dirUrl = "$sanitizedBase$hash/"

        val remoteSegments = fetchRemoteSegments(dirUrl, game.packageName)
        if (remoteSegments.isEmpty()) {
            throw Exception("No downloadable files found for $releaseName")
        }

        val totalBytes = remoteSegments.values.sum()
        val isSevenZ = remoteSegments.keys.any { it.contains(".7z") }
        val multiplier = if (isSevenZ) if (isDownloadOnly || keepApk) 2.9 else 1.9 else 1.1
        val estimatedRequired = (totalBytes * multiplier).toLong()
        checkAvailableSpace(estimatedRequired)

        val gameTempDir = File(tempInstallRoot, hash)
        if (!gameTempDir.exists()) gameTempDir.mkdirs()

        var totalBytesDownloaded = 0L
        remoteSegments.forEach { (seg, _) ->
            val f = File(gameTempDir, seg)
            if (f.exists()) totalBytesDownloaded += f.length()
        }

        for ((index, entry) in remoteSegments.entries.withIndex()) {
            if (isStopped) {
                Log.d(TAG, "Worker stopped during download loop")
                return@withContext Result.failure()
            }

            val seg = entry.key
            val remoteSize = entry.value
            currentCoroutineContext().ensureActive()

            val localFile = File(gameTempDir, seg)
            val existingSize = if (localFile.exists()) localFile.length() else 0L

            if (existingSize >= remoteSize) continue

            val segUrl = dirUrl + seg
            totalBytesDownloaded = downloadSegment(
                segUrl = segUrl,
                localFile = localFile,
                existingSize = existingSize,
                totalBytesDownloaded = totalBytesDownloaded,
                totalBytes = totalBytes,
                releaseName = releaseName,
                segmentIndex = index + 1,
                totalSegments = remoteSegments.size
            )
        }

        updateProgress(releaseName, 1.0f, totalBytes, totalBytes)
        updateStatus(releaseName, InstallStatus.COMPLETED)

        Log.i(TAG, "Download completed for $releaseName")
        Result.success(
            workDataOf(
                KEY_RELEASE_NAME to releaseName,
                KEY_STATUS to InstallStatus.COMPLETED.name,
                KEY_TOTAL_BYTES to totalBytes
            )
        )
    }

    private suspend fun downloadSegment(
        segUrl: String,
        localFile: File,
        existingSize: Long,
        totalBytesDownloaded: Long,
        totalBytes: Long,
        releaseName: String,
        segmentIndex: Int,
        totalSegments: Int
    ): Long {
        var downloaded = totalBytesDownloaded

        val segRequest = Request.Builder()
            .url(segUrl)
            .header("User-Agent", Constants.USER_AGENT)
            .header("Range", "bytes=$existingSize-")
            .build()

        okHttpClient.newCall(segRequest).await().use { response ->
            if (DownloadUtils.isRangeNotSatisfiable(response.code)) {
                return downloaded
            }

            if (!response.isSuccessful) {
                throw Exception("Failed to download segment: ${response.code}")
            }

            val isResume = DownloadUtils.isResumeResponse(response.code)
            val body = response.body ?: throw Exception("Empty response body")

            body.byteStream().use { input ->
                localFile.parentFile?.let { if (!it.exists()) it.mkdirs() }
                FileOutputStream(localFile, isResume).use { output ->
                    val buffer = ByteArray(DownloadUtils.DOWNLOAD_BUFFER_SIZE)
                    var bytesRead: Int
                    var lastUpdateTime = System.currentTimeMillis()
                    val throttleMs = 500L

                    while (true) {
                        if (isStopped) {
                            Log.d(TAG, "Worker stopped during segment download")
                            throw CancellationException("Worker stopped")
                        }

                        currentCoroutineContext().ensureActive()
                        bytesRead = input.read(buffer)
                        if (bytesRead == -1) break
                        output.write(buffer, 0, bytesRead)
                        downloaded += bytesRead

                        val now = System.currentTimeMillis()
                        if (now - lastUpdateTime >= throttleMs) {
                            val overallProgress = if (totalBytes > 0) downloaded.toFloat() / totalBytes else 0f
                            updateProgress(releaseName, overallProgress * 0.95f, downloaded, totalBytes)

                            setProgress(
                                Data.Builder()
                                    .putFloat(KEY_PROGRESS, overallProgress)
                                    .putLong(KEY_DOWNLOADED_BYTES, downloaded)
                                    .putLong(KEY_TOTAL_BYTES, totalBytes)
                                    .build()
                            )

                            lastUpdateTime = now
                        }
                    }
                }
            }
        }

        return downloaded
    }

    private suspend fun fetchRemoteSegments(dirUrl: String, packageName: String): Map<String, Long> {
        val rawSegments = mutableListOf<String>()

        val request = Request.Builder()
            .url(dirUrl)
            .header("User-Agent", Constants.USER_AGENT)
            .build()

        okHttpClient.newCall(request).await().use { response ->
            if (response.code == 404) {
                throw Exception("Mirror error: 404 - Game not found")
            }
            if (!response.isSuccessful) {
                throw Exception("Mirror error: ${response.code}")
            }

            val html = response.body?.string() ?: ""
            val entryMatcher = DownloadUtils.HREF_PATTERN.matcher(html)

            while (entryMatcher.find()) {
                val entry = entryMatcher.group(1) ?: continue
                if (DownloadUtils.shouldSkipEntry(entry)) continue

                if (entry.endsWith("/")) {
                    fetchAllFilesFromDir(dirUrl + entry, entry).forEach { rawSegments.add(it) }
                } else if (DownloadUtils.isDownloadableFile(entry)) {
                    rawSegments.add(entry)
                }
            }
        }

        val uniqueSegments = rawSegments.groupBy { it.substringAfterLast('/') }
            .map { (_, list) ->
                list.minByOrNull { it.count { c -> c == '/' } }!!
            }

        val segmentSizes = mutableMapOf<String, Long>()
        for (seg in uniqueSegments) {
            currentCoroutineContext().ensureActive()
            val headRequest = Request.Builder()
                .url(dirUrl + seg)
                .head()
                .header("User-Agent", Constants.USER_AGENT)
                .build()

            okHttpClient.newCall(headRequest).await().use { response ->
                val size = response.header("Content-Length")?.toLongOrNull() ?: 0L
                segmentSizes[seg] = size
            }
        }

        return segmentSizes
    }

    private suspend fun fetchAllFilesFromDir(baseUrl: String, prefix: String): List<String> {
        val files = mutableListOf<String>()
        try {
            val request = Request.Builder()
                .url(baseUrl)
                .header("User-Agent", Constants.USER_AGENT)
                .build()

            okHttpClient.newCall(request).await().use { response ->
                if (response.isSuccessful) {
                    val html = response.body?.string() ?: ""
                    val matcher = DownloadUtils.HREF_PATTERN.matcher(html)

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
        return files
    }

    private suspend fun fetchConfig(): PublicConfig {
        if (cachedConfig != null) return cachedConfig!!

        val config = service.getPublicConfig()
        cachedConfig = config
        try {
            val decoded = Base64.decode(config.password64, Base64.DEFAULT)
            decodedPassword = String(decoded, Charsets.UTF_8)
        } catch (e: Exception) {
            decodedPassword = config.password64
        }
        return config
    }

    private fun checkAvailableSpace(requiredBytes: Long) {
        val stat = StatFs(Environment.getExternalStorageDirectory().path)
        val availableBytes = stat.availableBlocksLong * stat.blockSizeLong

        if (availableBytes < requiredBytes) {
            val requiredMb = requiredBytes / (1024 * 1024)
            val availableMb = availableBytes / (1024 * 1024)
            throw Exception("Insufficient storage space. Need ${requiredMb}MB, but only ${availableMb}MB available.")
        }
    }

    private suspend fun updateStatus(releaseName: String, status: InstallStatus) {
        try {
            queuedInstallDao.updateStatus(releaseName, status.name, System.currentTimeMillis())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update status for $releaseName", e)
        }
    }

    private suspend fun updateProgress(
        releaseName: String,
        progress: Float,
        downloadedBytes: Long,
        totalBytes: Long
    ) {
        try {
            queuedInstallDao.updateProgress(
                releaseName = releaseName,
                progress = progress.coerceIn(0f, 1f),
                downloadedBytes = downloadedBytes,
                totalBytes = totalBytes,
                timestamp = System.currentTimeMillis()
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update progress for $releaseName", e)
        }
    }

    private suspend fun handleFailure(releaseName: String, e: Exception): Result {
        val errorMessage = e.message ?: "Unknown error"

        return if (runAttemptCount < 3) {
            Log.w(TAG, "Retrying download for $releaseName (attempt ${runAttemptCount + 1}/3)")
            Result.retry()
        } else {
            Log.e(TAG, "Max retries exceeded for $releaseName", e)
            updateStatus(releaseName, InstallStatus.FAILED)
            Result.failure(workDataOf(KEY_STATUS to errorMessage))
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val releaseName = inputData.getString(KEY_RELEASE_NAME) ?: "Download"
        return createForegroundInfo(releaseName)
    }

    private fun createForegroundInfo(releaseName: String): ForegroundInfo {
        createNotificationChannel()

        val notification = NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Downloading")
            .setContentText(releaseName)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setProgress(100, 0, true)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Download Progress",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows download progress for game installations"
                setSound(null, null)
            }

            val notificationManager = applicationContext.getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response)
            }

            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWithException(e)
            }
        })
    }

    private fun workDataOf(vararg pairs: Pair<String, Any?>): Data {
        val builder = Data.Builder()
        pairs.forEach { (key, value) ->
            when (value) {
                is String -> builder.putString(key, value)
                is Long -> builder.putLong(key, value)
                is Int -> builder.putInt(key, value)
                is Float -> builder.putFloat(key, value)
                is Boolean -> builder.putBoolean(key, value)
            }
        }
        return builder.build()
    }
}
