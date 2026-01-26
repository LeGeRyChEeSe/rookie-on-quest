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
import androidx.work.workDataOf
import com.vrpirates.rookieonquest.R
import com.vrpirates.rookieonquest.data.AppDatabase
import com.vrpirates.rookieonquest.data.Constants
import com.vrpirates.rookieonquest.data.CryptoUtils
import com.vrpirates.rookieonquest.data.DownloadUtils
import com.vrpirates.rookieonquest.data.FilePaths
import com.vrpirates.rookieonquest.data.GameNotFoundException
import com.vrpirates.rookieonquest.data.InsufficientStorageException
import com.vrpirates.rookieonquest.data.InstallStatus
import com.vrpirates.rookieonquest.data.MirrorNotFoundException
import com.vrpirates.rookieonquest.data.NetworkModule
import com.vrpirates.rookieonquest.data.NoDownloadableFilesException
import com.vrpirates.rookieonquest.data.NonRetryableDownloadException
import com.vrpirates.rookieonquest.data.toData
import com.vrpirates.rookieonquest.network.PublicConfig
import com.vrpirates.rookieonquest.network.VrpService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.withPermit
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

    // Use filesDir instead of cacheDir to prevent Android from purging large game archives
    // during extraction. cacheDir can be cleaned by the system when storage is low.
    private val tempInstallRoot = File(applicationContext.filesDir, "install_temp")
    private val downloadsDir = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
        FilePaths.DOWNLOADS_ROOT_DIR_NAME
    )

    private var cachedConfig: PublicConfig? = null
    private var decodedPassword: String? = null

    // Reusable NotificationCompat.Builder to reduce GC pressure during progress updates
    private var notificationBuilder: NotificationCompat.Builder? = null

    // Track if notification channel has been created (optimization: create once per Worker lifecycle)
    private var notificationChannelCreated = false

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
            // CRITICAL FIX for AC 7: Do NOT set status to PAUSED here.
            //
            // CancellationException occurs in two scenarios:
            // 1. User explicitly paused/cancelled: Status is already PAUSED (set by pauseInstall/cancelInstall
            //    before cancelDownloadWork was called). Leaving it as-is preserves user intent.
            // 2. WorkManager stopped due to constraints (network loss, battery low, etc.): Status is
            //    DOWNLOADING. It must remain DOWNLOADING so resumeActiveDownloadObservations() can
            //    auto-resume after app restart per AC 7.
            //
            // Setting PAUSED here breaks AC 7 because system stops would leave DB in PAUSED state,
            // which MainViewModel.ignore()s on resume, preventing auto-resume.
            Log.d(TAG, "Download cancelled for $releaseName (status unchanged)")
            throw e
        } catch (e: Exception) {
            // Error logging is handled in handleFailure() with more detail
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
            ?: throw GameNotFoundException(releaseName)

        val hash = CryptoUtils.md5(releaseName + "\n")
        val sanitizedBase = if (config.baseUri.endsWith("/")) config.baseUri else "${config.baseUri}/"
        val dirUrl = "$sanitizedBase$hash/"

        val remoteSegments = fetchRemoteSegments(dirUrl, game.packageName, releaseName)
        if (remoteSegments.isEmpty()) {
            throw NoDownloadableFilesException(releaseName)
        }

        // Filter out failed HEAD requests (-1L) before summing to prevent invalid totalBytes
        val validSegments = remoteSegments.filter { it.value > 0L }
        val totalBytes = validSegments.values.sum()
        val hasUnknownSizes = remoteSegments.values.any { it == -1L }

        if (hasUnknownSizes) {
            Log.w(TAG, "Some segments have unknown sizes (HEAD request failed). Progress may be inaccurate until download completes.")
        }

        val isSevenZ = remoteSegments.keys.any { it.contains(".7z") }
        val estimatedRequired = DownloadUtils.calculateRequiredStorage(
            totalBytes = totalBytes,
            isSevenZArchive = isSevenZ,
            keepApkOrDownloadOnly = isDownloadOnly || keepApk
        )

        // Check internal storage (for temp files) and external storage (for download-only/keep-apk modes)
        checkAvailableSpace(estimatedRequired, hasUnknownSizes, checkExternalStorage = isDownloadOnly || keepApk)

        val gameTempDir = File(tempInstallRoot, hash)
        if (!gameTempDir.exists()) gameTempDir.mkdirs()

        // Task 1.5: Sync Room DB with file system before resuming download
        // File system is the source of truth for bytes downloaded
        var totalBytesDownloaded = syncRoomDbWithFileSystem(
            releaseName = releaseName,
            gameTempDir = gameTempDir,
            remoteSegments = remoteSegments,
            totalBytes = totalBytes
        )

        // Process segments in sorted order for multi-part archives (.7z.001, .7z.002, ...)
        val sortedSegments = remoteSegments.entries.sortedBy { it.key }

        for ((index, entry) in sortedSegments.withIndex()) {
            if (isStopped) {
                Log.d(TAG, "Worker stopped during download loop")
                return@withContext Result.failure()
            }

            val seg = entry.key
            val remoteSize = entry.value
            currentCoroutineContext().ensureActive()

            val localFile = File(gameTempDir, seg)
            val existingSize = if (localFile.exists()) localFile.length() else 0L

            // Multi-part archive resumption logic (AC 6)
            when {
                // Segment complete: skip only if file exists and EXACTLY matches expected size
                // Using exact match prevents skipping corrupted or oversized files
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

        // Report 80% progress - extraction phase (80-100%) is handled by MainViewModel
        // This ensures smooth progress transition without backwards jump
        updateProgress(releaseName, Constants.PROGRESS_DOWNLOAD_PHASE_END, totalBytes, totalBytes)
        // Note: We do NOT update status to COMPLETED here to avoid UI flickering.
        // The ViewModel will observe WorkInfo.State.SUCCEEDED and transition to EXTRACTING.
        // Setting COMPLETED here would cause a brief flicker: COMPLETED → EXTRACTING

        Log.i(TAG, "Download completed for $releaseName")
        Result.success(
            workDataOf(
                KEY_RELEASE_NAME to releaseName,
                KEY_STATUS to InstallStatus.COMPLETED.name,
                KEY_TOTAL_BYTES to totalBytes
            )
        )
    }

    /**
     * Synchronizes Room DB downloadedBytes with actual file system state.
     * File system is the source of truth - if DB and files disagree, DB is corrected.
     *
     * This is critical for resume reliability (AC 4): partial files may have been
     * written but the DB update may not have persisted (e.g., process death).
     *
     * @return Total bytes downloaded across all segments (from file system)
     */
    private suspend fun syncRoomDbWithFileSystem(
        releaseName: String,
        gameTempDir: File,
        remoteSegments: Map<String, Long>,
        totalBytes: Long
    ): Long {
        // Calculate actual bytes on disk
        var actualBytesOnDisk = 0L
        remoteSegments.forEach { (seg, _) ->
            val f = File(gameTempDir, seg)
            if (f.exists()) actualBytesOnDisk += f.length()
        }

        // Read current Room DB state
        val dbEntity = queuedInstallDao.getByReleaseName(releaseName)
        val dbDownloadedBytes = dbEntity?.downloadedBytes ?: 0L

        // Sync if mismatch detected
        if (actualBytesOnDisk != dbDownloadedBytes) {
            Log.i(TAG, "Synced downloadedBytes: DB had [$dbDownloadedBytes], file has [$actualBytesOnDisk]")

            val progress = if (totalBytes > 0) {
                // Download phase is 0-80%, scale accordingly
                (actualBytesOnDisk.toFloat() / totalBytes) * Constants.PROGRESS_DOWNLOAD_PHASE_END
            } else {
                // When totalBytes is 0 (all segments have unknown sizes), we cannot calculate progress
                // Use 0f to avoid division by zero producing Infinity
                0f
            }

            queuedInstallDao.updateProgress(
                releaseName = releaseName,
                progress = progress.coerceIn(0f, Constants.PROGRESS_DOWNLOAD_PHASE_END),
                downloadedBytes = actualBytesOnDisk,
                totalBytes = totalBytes,
                timestamp = System.currentTimeMillis()
            )
        } else {
            Log.d(TAG, "Room DB in sync with file system: $actualBytesOnDisk bytes")
        }

        return actualBytesOnDisk
    }

    private suspend fun downloadSegment(
        segUrl: String,
        localFile: File,
        existingSize: Long,
        totalBytesDownloaded: Long,
        totalBytes: Long,
        releaseName: String,
        segmentIndex: Int,
        totalSegments: Int,
        retryCount: Int = 0
    ): Long {
        // Prevent infinite recursion on 416 size mismatch
        if (retryCount > Constants.MAX_416_RETRIES) {
            throw Exception("Failed to download ${localFile.name} after ${Constants.MAX_416_RETRIES} retries on 416 size mismatch")
        }
        var downloaded = totalBytesDownloaded
        var currentExistingSize = existingSize

        val segRequest = Request.Builder()
            .url(segUrl)
            .header("User-Agent", Constants.USER_AGENT)
            .header("Range", "bytes=$currentExistingSize-")
            .build()

        okHttpClient.newCall(segRequest).await().use { response ->
            // Handle 416 Range Not Satisfiable - file may be complete
            if (DownloadUtils.isRangeNotSatisfiable(response.code)) {
                // Verify file is actually complete before skipping (AC 5)
                val actualFileSize = localFile.length()
                val contentRange = response.header("Content-Range") // Format: "bytes */TOTAL"
                val expectedSize = contentRange?.substringAfter("*/")?.toLongOrNull()

                if (expectedSize != null && actualFileSize != expectedSize) {
                    Log.w(TAG, "416 but file size mismatch: local=$actualFileSize, expected=$expectedSize. Truncating and retrying (attempt ${retryCount + 1}/${Constants.MAX_416_RETRIES}).")
                    // File is corrupted/mismatched - delete and retry recursively with retry limit
                    localFile.delete()
                    downloaded -= actualFileSize // Adjust downloaded count
                    return downloadSegment(
                        segUrl, localFile, 0L, downloaded, totalBytes, releaseName, segmentIndex, totalSegments, retryCount + 1
                    )
                } else {
                    Log.i(TAG, "Segment complete (416): ${localFile.name} ($actualFileSize bytes)")
                    return downloaded
                }
            }

            if (!response.isSuccessful) {
                throw Exception("Failed to download segment: ${response.code}")
            }

            val isResume = DownloadUtils.isResumeResponse(response.code)

            // Enhanced logging for resume vs restart (AC 1, 2)
            if (isResume) {
                Log.i(TAG, "Resuming download from byte [$currentExistingSize] for ${localFile.name}")
            } else {
                Log.w(TAG, "Server ignored Range header, restarting download from beginning for ${localFile.name}")
                // Reset downloaded count since we're starting fresh (200 means overwrite)
                downloaded = totalBytesDownloaded - currentExistingSize
            }

            val body = response.body ?: throw Exception("Empty response body")

            body.byteStream().use { input ->
                localFile.parentFile?.let { if (!it.exists()) it.mkdirs() }
                FileOutputStream(localFile, isResume).use { output ->
                    // Use shared download utility for consistent behavior
                                                        downloaded = DownloadUtils.downloadWithProgress(
                                                            inputStream = input,
                                                            outputStream = output,
                                                            initialDownloaded = downloaded,
                                                            totalBytes = totalBytes,
                                                            throttleMs = Constants.PROGRESS_THROTTLE_MS,
                                                            isCancelled = { isStopped },
                                                            onProgress = { downloadedBytes, total, progress ->                            // Download phase is 0-80%, extraction (handled by MainViewModel) is 80-100%
                            val scaledProgress = progress * Constants.PROGRESS_DOWNLOAD_PHASE_END
                            updateProgress(releaseName, scaledProgress, downloadedBytes, total)

                            // Update foreground notification with actual progress
                            updateNotificationProgress(releaseName, scaledProgress)

                            setProgress(
                                Data.Builder()
                                    .putFloat(KEY_PROGRESS, scaledProgress)
                                    .putLong(KEY_DOWNLOADED_BYTES, downloadedBytes)
                                    .putLong(KEY_TOTAL_BYTES, total)
                                    .build()
                            )
                        }
                    )
                }
            }
        }

        return downloaded
    }

    /**
     * Fetches remote segment information from the mirror directory.
     *
     * SHARED CODE: Uses DownloadUtils for common operations:
     * - DownloadUtils.HREF_REGEX, shouldSkipEntry(), isDownloadableFile()
     * - DownloadUtils.headRequestSemaphore for rate limiting
     *
     * INTENTIONALLY SEPARATE from MainRepository.getGameRemoteInfo() because:
     * - DownloadWorker runs in WorkManager background context with retry semantics
     * - MainRepository runs in UI coroutine context with additional metadata fetching
     * - Error handling differs (Worker retries, Repository propagates to UI)
     *
     * If modifying this method, also review MainRepository.getGameRemoteInfo() for consistency.
     */
    private suspend fun fetchRemoteSegments(dirUrl: String, packageName: String, releaseName: String): Map<String, Long> {
        val rawSegments = mutableListOf<String>()

        val request = Request.Builder()
            .url(dirUrl)
            .header("User-Agent", Constants.USER_AGENT)
            .build()

        okHttpClient.newCall(request).await().use { response ->
            if (response.code == 404) {
                throw MirrorNotFoundException(releaseName)
            }
            if (!response.isSuccessful) {
                throw Exception("Mirror error: ${response.code}")
            }

            val html = response.body?.string() ?: ""

            // Use idiomatic Kotlin Regex instead of deprecated HREF_PATTERN
            DownloadUtils.HREF_REGEX.findAll(html).forEach { matchResult ->
                val entry = matchResult.groupValues[1]
                if (DownloadUtils.shouldSkipEntry(entry)) return@forEach

                if (entry.endsWith("/")) {
                    fetchAllFilesFromDir(dirUrl + entry, entry).forEach { rawSegments.add(it) }
                } else if (DownloadUtils.isDownloadableFile(entry)) {
                    rawSegments.add(entry)
                }
            }
        }

        // Use full path for deduplication to prevent data loss in special directory structures
        // Files with same name but different paths are likely different files (e.g., Quake3Quest data folders)
        val uniqueSegments = rawSegments.distinct()

        // Parallelize HEAD requests using async/awaitAll with Semaphore rate limiting
        // Semaphore prevents socket exhaustion on mirror servers by limiting concurrent requests
        // runCatching ensures a single mirror timeout doesn't fail the entire download
        val segmentSizes = supervisorScope {
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

                    // Use idiomatic Kotlin Regex instead of deprecated HREF_PATTERN
                    DownloadUtils.HREF_REGEX.findAll(html).forEach { matchResult ->
                        val entry = matchResult.groupValues[1]
                        if (entry.startsWith(".") || entry == "../") return@forEach
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

    private fun checkAvailableSpace(
        requiredBytes: Long,
        hasUnknownSizes: Boolean = false,
        checkExternalStorage: Boolean = false
    ) {
        // CRITICAL: Check space on filesDir partition where tempInstallRoot writes to
        // Previously checked externalFilesDir but wrote to filesDir (partition mismatch bug)
        val internalPath = applicationContext.filesDir.path
        val internalStat = StatFs(internalPath)
        val internalAvailable = internalStat.availableBlocksLong * internalStat.blockSizeLong

        // When sizes are unknown, require a conservative minimum space check
        // This prevents downloads with all -1L sizes from bypassing space validation
        val effectiveRequired = if (hasUnknownSizes && requiredBytes == 0L) {
            // Require at least 1GB as a safety buffer when all sizes are unknown
            Constants.UNKNOWN_SIZE_SPACE_BUFFER
        } else {
            requiredBytes
        }

        // Check internal storage (filesDir where tempInstallRoot writes)
        if (internalAvailable < effectiveRequired) {
            val requiredMb = effectiveRequired / (1024 * 1024)
            val availableMb = internalAvailable / (1024 * 1024)
            throw InsufficientStorageException(requiredMb, availableMb)
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
                    throw InsufficientStorageException(requiredMb, availableMb)
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

        // Non-retryable errors: fail immediately instead of wasting retry attempts
        // Uses type-safe exception checking instead of string matching for robustness
        val isNonRetryable = e is NonRetryableDownloadException

        return if (!isNonRetryable && runAttemptCount < 3) {
            Log.w(TAG, "Retrying download for $releaseName (attempt ${runAttemptCount + 1}/3)")
            Result.retry()
        } else {
            if (isNonRetryable) {
                Log.e(TAG, "Non-retryable error for $releaseName: $errorMessage")
            } else {
                Log.e(TAG, "Max retries exceeded for $releaseName", e)
            }
            updateStatus(releaseName, InstallStatus.FAILED)
            Result.failure(workDataOf(KEY_STATUS to errorMessage))
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val releaseName = inputData.getString(KEY_RELEASE_NAME) ?: "Download"
        return createForegroundInfo(releaseName)
    }

    /**
     * Creates ForegroundInfo for the WorkManager notification.
     *
     * UX NOTE: Initial notification shows 0% determinate progress (not indeterminate)
     * to immediately convey that a download is starting. Indeterminate mode is only
     * used during brief setup phases. Once downloading begins, real progress is shown.
     *
     * @param releaseName Game name for notification text
     * @param progress Current progress percentage (0-100)
     * @param indeterminate If true, shows spinner instead of progress bar. Default false
     *        to show 0% progress immediately when download starts.
     */
    private fun createForegroundInfo(releaseName: String, progress: Int = 0, indeterminate: Boolean = false): ForegroundInfo {
        createNotificationChannel()

        val contentText = when {
            indeterminate -> "Preparing $releaseName..."
            progress == 0 -> "$releaseName - Starting..."
            else -> "$releaseName - $progress%"
        }

        val notification = NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Downloading")
            .setContentText(contentText)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setProgress(100, progress, indeterminate)
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

    private val notificationManager: NotificationManager by lazy {
        applicationContext.getSystemService(NotificationManager::class.java)
    }

    /**
     * Updates the foreground notification with actual progress percentage.
     * Called from the download progress callback for real-time updates.
     * Reuses NotificationCompat.Builder instance to reduce GC pressure.
     */
    private fun updateNotificationProgress(releaseName: String, progress: Float) {
        val progressPercent = (progress * 100).toInt().coerceIn(0, 100)

        // Ensure channel exists before creating notification (required for Android O+)
        // Optimization: Only create once per Worker lifecycle instead of on every progress update
        ensureNotificationChannelCreated()

        // Reuse builder to reduce object allocation. Thread-safe: Worker runs on single thread.
        val builder = notificationBuilder ?: NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Downloading")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .also { notificationBuilder = it }

        val notification = builder
            .setContentText("$releaseName - $progressPercent%")
            .setProgress(100, progressPercent, false)
            .build()

        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    /**
     * Ensures notification channel is created exactly once per Worker lifecycle.
     * Optimization: Prevents redundant NotificationManager calls on every progress update.
     */
    private fun ensureNotificationChannelCreated() {
        if (notificationChannelCreated) return
        createNotificationChannel()
        notificationChannelCreated = true
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
}
