package com.vrpirates.rookieonquest.data

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.BufferedInputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Non-retryable download exceptions.
 * These indicate permanent failures that should not trigger retry attempts.
 */
sealed class NonRetryableDownloadException(message: String) : Exception(message)

/** Thrown when there's insufficient storage space for the download */
class InsufficientStorageException(requiredMb: Long, availableMb: Long) :
    NonRetryableDownloadException("Insufficient storage space. Need ${requiredMb}MB, but only ${availableMb}MB available.")

/** Thrown when no downloadable files are found for a release */
class NoDownloadableFilesException(releaseName: String) :
    NonRetryableDownloadException("No downloadable files found for $releaseName")

/** Thrown when a game is not found in the catalog */
class GameNotFoundException(releaseName: String) :
    NonRetryableDownloadException("Game not found in catalog: $releaseName")

/** Thrown when the mirror returns 404 for a game */
class MirrorNotFoundException(releaseName: String) :
    NonRetryableDownloadException("Mirror error: 404 - Game not found: $releaseName")

/**
 * Application-wide constants
 */
object Constants {
    /**
     * SharedPreferences file name used across the application
     * Used by: MainRepository, MainViewModel, MigrationManager
     */
    const val PREFS_NAME = "rookie_prefs"

    /**
     * User-Agent string used for all HTTP requests to VRPirates servers
     * Required for server compatibility
     */
    const val USER_AGENT = "rclone/v1.72.1"

    /**
     * Base URL for VRPirates API
     */
    const val VRP_API_BASE_URL = "https://vrpirates.wiki/"

    /**
     * HTTP connection timeout in seconds
     */
    const val HTTP_CONNECT_TIMEOUT_SECONDS = 30L

    /**
     * HTTP read timeout in seconds
     */
    const val HTTP_READ_TIMEOUT_SECONDS = 30L

    /**
     * Throttle interval for progress updates to Room DB (in milliseconds).
     * Progress updates happen every 64KB (~hundreds/second), so we limit DB writes
     * to prevent excessive I/O. Completion (progress >= 1.0) always updates immediately.
     */
    const val PROGRESS_THROTTLE_MS = 500L

    /**
     * Progress scaling factor for download phase within DownloadWorker.
     * Download progress (0.0-1.0) is scaled to 0.0-0.8 of total installation progress.
     *
     * NOTE: There is intentionally a 2% gap between PROGRESS_DOWNLOAD_PHASE_END (0.8)
     * and PROGRESS_MILESTONE_EXTRACTION_START (0.82). This gap represents the
     * "Preparing files..." phase where:
     * - Multi-part 7z files are merged
     * - Download temp files are verified
     * - Extraction directory is prepared
     *
     * Progress flow: Download (0-80%) → Preparing (80-82%) → Extraction (82-100%)
     */
    const val PROGRESS_DOWNLOAD_PHASE_END = 0.8f

    /**
     * Default minimum space buffer (1GB) when all segment sizes are unknown.
     * Used as a safety buffer to prevent downloads from proceeding without any space validation
     * when HEAD requests fail and all sizes are reported as -1L.
     */
    const val UNKNOWN_SIZE_SPACE_BUFFER = 1_000_000_000L

    /**
     * Maximum number of retry attempts for 416 Range Not Satisfiable responses
     * when file size mismatch is detected. This prevents infinite recursion while
     * allowing recovery from temporary server inconsistencies.
     */
    const val MAX_416_RETRIES = 3

    /**
     * Progress milestone for initial download phase start (2%).
     * Used for pre-flight checks and server verification.
     */
    const val PROGRESS_MILESTONE_VERIFYING = 0.02f

    /**
     * Progress milestone for extraction phase start (82%).
     * Download phase occupies 0-82%, extraction occupies 82-100%.
     */
    const val PROGRESS_MILESTONE_EXTRACTION_START = 0.82f

    /**
     * Progress milestone for file merging (85%).
     * Used for multi-part 7z archives before extraction.
     */
    const val PROGRESS_MILESTONE_MERGING = 0.85f

    /**
     * Progress milestone for extraction (88%).
     * Actual 7z extraction happens at this stage.
     */
    const val PROGRESS_MILESTONE_EXTRACTING = 0.88f

    /**
     * Progress milestone for OBB installation (94%).
     * Moving OBB files to external storage.
     */
    const val PROGRESS_MILESTONE_INSTALLING_OBBS = 0.94f

    /**
     * Progress milestone for final APK installation (96%).
     * Preparing APK for installer launch.
     */
    const val PROGRESS_MILESTONE_LAUNCHING_INSTALLER = 0.96f

    /**
     * Progress milestone for saving to Downloads (92%).
     * Copying APK/OBB to Downloads folder in download-only/keep-apk modes.
     */
    const val PROGRESS_MILESTONE_SAVING_TO_DOWNLOADS = 0.92f
}

/**
 * Singleton network module providing shared OkHttpClient and Retrofit instances.
 * Ensures consistent configuration across the app and reduces memory footprint.
 *
 * Usage:
 * - MainRepository: Uses both okHttpClient and vrpService for catalog and download operations
 * - DownloadWorker: Uses okHttpClient for background downloads
 * - MainViewModel: Uses okHttpClient for app update downloads
 */
object NetworkModule {
    /**
     * Shared OkHttpClient instance configured for VRPirates server communication.
     * Thread-safe and connection-pooled for efficiency.
     */
    val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(Constants.HTTP_CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(Constants.HTTP_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Shared Retrofit instance for VRPirates API.
     * Uses Gson converter for JSON parsing.
     */
    val retrofit: Retrofit by lazy {
        Retrofit.Builder()
            .baseUrl(Constants.VRP_API_BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }
}

/**
 * Cryptographic utility functions shared across the application.
 */
object CryptoUtils {
    /**
     * Computes MD5 hash of a string.
     * Used for generating directory names consistent with VRPirates server structure.
     *
     * @param input The string to hash
     * @return Lowercase hexadecimal MD5 hash string
     */
    fun md5(input: String): String {
        val bytes = MessageDigest.getInstance("MD5").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}

/**
 * File path constants used across the application.
 */
object FilePaths {
    /**
     * Root directory name for downloads in external storage.
     * All game downloads, logs, and related files are stored under this directory.
     * Location: /sdcard/Download/RookieOnQuest/
     */
    const val DOWNLOADS_ROOT_DIR_NAME = "RookieOnQuest"
}

/**
 * Shared download utilities used by DownloadWorker and MainRepository.
 *
 * This object centralizes common download operations to reduce code duplication.
 * Provides shared buffer size, directory parsing utilities, and HTTP response handling.
 */
object DownloadUtils {
    /**
     * Standard buffer size for downloads (64KB).
     * Optimized for network throughput while maintaining reasonable memory usage.
     */
    const val DOWNLOAD_BUFFER_SIZE = 8192 * 8

    /**
     * Maximum concurrent HEAD requests for file size verification.
     * Limits parallel connections to prevent socket exhaustion on mirror servers.
     * Value of 5 balances speed and server load.
     */
    const val MAX_CONCURRENT_HEAD_REQUESTS = 5

    /**
     * Semaphore for limiting concurrent HEAD requests.
     * Shared across DownloadWorker and MainRepository for consistent rate limiting.
     */
    val headRequestSemaphore = Semaphore(MAX_CONCURRENT_HEAD_REQUESTS)

    /**
     * Regex pattern for extracting href links from HTML directory listings.
     * Used for parsing VRPirates mirror directory contents.
     */
    val HREF_REGEX: Regex = """href\s*=\s*"([^"]+)"""".toRegex(RegexOption.IGNORE_CASE)

    /**
     * Checks if a filename represents a downloadable game file.
     * @param filename The filename to check (case-insensitive)
     * @return true if the file is an APK, OBB, or 7z archive
     */
    fun isDownloadableFile(filename: String): Boolean {
        val lower = filename.lowercase()
        return lower.endsWith(".apk") || lower.endsWith(".obb") ||
                lower.contains(".7z.") || lower.endsWith(".7z")
    }

    /**
     * Checks if an entry should be skipped when parsing directory listings.
     * @param entry The directory entry to check
     * @return true if the entry should be skipped
     */
    fun shouldSkipEntry(entry: String): Boolean {
        return entry.startsWith(".") || entry.startsWith("_") ||
                entry.contains("notes.txt") || entry.contains("screenshot") ||
                entry == "../"
    }

    /**
     * Determines if an HTTP response indicates a resumable download.
     * @param responseCode The HTTP response code
     * @return true if 206 (Partial Content), false otherwise
     */
    fun isResumeResponse(responseCode: Int): Boolean = responseCode == 206

    /**
     * Determines if the response indicates range not satisfiable (already complete).
     * @param responseCode The HTTP response code
     * @return true if 416 (Range Not Satisfiable)
     */
    fun isRangeNotSatisfiable(responseCode: Int): Boolean = responseCode == 416

    /**
     * Storage space multiplier for 7z archives when keeping APK/download-only.
     * 7z archives need ~2.9x space: original archive + extracted content + APK copy
     */
    const val STORAGE_MULTIPLIER_7Z_KEEP_APK = 2.9

    /**
     * Storage space multiplier for 7z archives without keeping APK.
     * 7z archives need ~1.9x space: original archive + extracted content
     */
    const val STORAGE_MULTIPLIER_7Z_NO_KEEP = 1.9

    /**
     * Storage space multiplier for non-archived files (direct APK/OBB).
     * Small buffer (1.1x) for file system overhead and temp files.
     */
    const val STORAGE_MULTIPLIER_NON_ARCHIVE = 1.1

    /**
     * Calculates the estimated storage space required for a download.
     *
     * @param totalBytes Total bytes to download
     * @param isSevenZArchive Whether the download contains 7z archives
     * @param keepApkOrDownloadOnly Whether APK should be kept or download-only mode
     * @return Estimated bytes required including extraction overhead
     */
    fun calculateRequiredStorage(totalBytes: Long, isSevenZArchive: Boolean, keepApkOrDownloadOnly: Boolean): Long {
        val multiplier = when {
            isSevenZArchive && keepApkOrDownloadOnly -> STORAGE_MULTIPLIER_7Z_KEEP_APK
            isSevenZArchive -> STORAGE_MULTIPLIER_7Z_NO_KEEP
            else -> STORAGE_MULTIPLIER_NON_ARCHIVE
        }
        return (totalBytes * multiplier).toLong()
    }

    /**
     * Downloads a file segment with progress reporting and cancellation support.
     *
     * This shared implementation is used by both DownloadWorker and MainRepository
     * to ensure consistent download behavior and reduce code duplication.
     *
     * Note: For optimal performance, the inputStream is automatically wrapped in
     * BufferedInputStream if not already buffered. This reduces system call overhead.
     *
     * @param inputStream The input stream from the HTTP response body
     * @param outputStream The output stream to write to (file)
     * @param initialDownloaded Bytes already downloaded before this call (for resume)
     * @param totalBytes Total expected bytes for progress calculation
     * @param throttleMs Minimum milliseconds between progress callbacks
     * @param isCancelled Lambda to check if download should be cancelled
     * @param onProgress Callback for progress updates (downloadedBytes, totalBytes, progress 0.0-1.0)
     * @return Total bytes downloaded (including initial)
     * @throws kotlinx.coroutines.CancellationException if cancelled
     */
    suspend fun downloadWithProgress(
        inputStream: java.io.InputStream,
        outputStream: java.io.OutputStream,
        initialDownloaded: Long,
        totalBytes: Long,
        throttleMs: Long = 500L,
        isCancelled: () -> Boolean,
        onProgress: suspend (downloadedBytes: Long, totalBytes: Long, progress: Float) -> Unit
    ): Long {
        var downloaded = initialDownloaded
        val buffer = ByteArray(DOWNLOAD_BUFFER_SIZE)
        var lastUpdateTime = System.currentTimeMillis()

        // Wrap in BufferedInputStream for improved I/O performance (reduces syscall overhead)
        val bufferedInput = if (inputStream is BufferedInputStream) inputStream
            else BufferedInputStream(inputStream, DOWNLOAD_BUFFER_SIZE)

        while (true) {
            // Check cancellation
            if (isCancelled()) {
                throw kotlinx.coroutines.CancellationException("Download cancelled")
            }
            kotlinx.coroutines.currentCoroutineContext().ensureActive()

            val bytesRead = bufferedInput.read(buffer)
            if (bytesRead == -1) break

            outputStream.write(buffer, 0, bytesRead)
            downloaded += bytesRead

            // Throttled progress update
            val now = System.currentTimeMillis()
            if (now - lastUpdateTime >= throttleMs) {
                val progress = if (totalBytes > 0) downloaded.toFloat() / totalBytes else 0f
                onProgress(downloaded, totalBytes, progress)
                lastUpdateTime = now
            }
        }

        return downloaded
    }
}
