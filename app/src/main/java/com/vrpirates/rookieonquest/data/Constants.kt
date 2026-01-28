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
     * Progress flow: Download (0-80%) → Merging (80-82%) → Extraction (85-100%)
     * The extraction phase includes smooth progress updates for visual continuity.
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
     * Progress milestone for extraction phase start (80%).
     * Immediately follows download phase for smooth visual transition.
     * Download phase occupies 0-80%, extraction occupies 80-100%.
     */
    const val PROGRESS_MILESTONE_EXTRACTION_START = 0.80f

    /**
     * Progress milestone for file merging (81%).
     * Used for multi-part 7z archives before extraction.
     */
    const val PROGRESS_MILESTONE_MERGING = 0.81f

    /**
     * Progress milestone for extraction (85%).
     * Actual 7z extraction happens at this stage.
     * Extraction spans 85-92% for monotonic progress (not 85-100% which causes backwards jump).
     */
    const val PROGRESS_MILESTONE_EXTRACTING = 0.85f

    /**
     * Progress milestone for extraction completion (92%).
     * Marks the end of 7z extraction phase before OBB/APK installation.
     * This ensures monotonic progress: 85% (extract start) → 92% (extract end) → 93% (prepare) → 94% (OBB) → 96% (APK).
     */
    const val PROGRESS_MILESTONE_EXTRACTION_END = 0.92f

    /**
     * Progress milestone for preparing installation (93%).
     * Transitional phase between extraction and OBB/APK installation.
     * Bridges the gap from extraction end (92%) to OBB installation (94%).
     */
    const val PROGRESS_MILESTONE_PREPARING_INSTALL = 0.93f

    /**
     * Progress milestone for OBB installation (94%).
     * Moving OBB files to external storage.
     */
    const val PROGRESS_MILESTONE_INSTALLING_OBBS = 0.94f

    /**
     * Progress milestone for final APK installation (96%).
     * Preparing APK for installer launch.
     *
     * Story 1.7 Code Review Round 5: Progress milestone documentation
     * ================================================================
     * The progress milestones (92% → 93% → 94% → 96% → 98%) are intentionally
     * non-linear to represent distinct installation phases:
     *
     * - 92%: Extraction complete
     * - 93%: Preparing installation (parsing install.txt, detecting OBBs)
     * - 94%: OBB movement phase
     * - 96-98%: APK staging (with sub-progress for large APKs)
     * - 98%: Launching system installer
     *
     * These discrete jumps provide clear phase transitions to users.
     * Sub-progress within phases (e.g., 96-98% during staging) provides
     * smooth feedback for time-consuming operations.
     */
    const val PROGRESS_MILESTONE_LAUNCHING_INSTALLER = 0.96f

    /**
     * Progress milestone for saving to Downloads (92%).
     * Copying APK/OBB to Downloads folder in download-only/keep-apk modes.
     */
    const val PROGRESS_MILESTONE_SAVING_TO_DOWNLOADS = 0.92f

    /**
     * Maximum age for staged APK files (24 hours in milliseconds).
     * Files older than this are considered stale and eligible for cleanup.
     */
    const val STAGED_APK_MAX_AGE_MS = 1000 * 60 * 60 * 24L

    /**
     * Minimum estimated APK size for space checks (500 MB).
     * Used when exact APK size is unknown during pre-flight space verification.
     * APK is staged to externalFilesDir before installation, requiring external storage space.
     */
    const val MIN_ESTIMATED_APK_SIZE = 500L * 1024L * 1024L

    /**
     * Throttle interval for extraction progress updates (in milliseconds).
     * Extraction progress updates at minimum 1Hz as required by NFR-P10.
     * This value (1000ms) ensures updates happen at least once per second.
     */
    const val EXTRACTION_PROGRESS_THROTTLE_MS = 1000L
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
     * Multi-part 7z archives need ~3.5x space:
     *   - Original archive parts (1x)
     *   - combined.7z during merge (1x for multi-part)
     *   - Extracted content (~1.2x, varies by compression)
     *   - APK copy to externalFilesDir (~0.1-0.3x)
     * Provides a safer buffer than 3.2x for large games.
     */
    const val STORAGE_MULTIPLIER_7Z_KEEP_APK = 3.5

    /**
     * Storage space multiplier for 7z archives without keeping APK.
     * Multi-part 7z archives need ~2.5x space:
     *   - Original archive parts (1x)
     *   - combined.7z during merge (1x for multi-part)
     *   - Extracted content (~1.2x, varies by compression)
     * Provides a safer buffer than 2.2x for large games.
     */
    const val STORAGE_MULTIPLIER_7Z_NO_KEEP = 2.5

    /**
     * Storage space multiplier for non-archived files (direct APK/OBB).
     * Small buffer (1.1x) for file system overhead and temp files.
     */
    const val STORAGE_MULTIPLIER_NON_ARCHIVE = 1.1

    /**
     * Storage space multiplier for OBB files during installation (1.0x).
     * OBB files are moved or copied to /Android/obb, requiring their own space
     * on the external storage partition.
     */
    const val STORAGE_MULTIPLIER_OBB = 1.0

    /**
     * Calculates the estimated storage space required for OBB files.
     * @param remoteSegments Map of remote files and their sizes
     * @param packageName The package name to identify OBB folder
     * @return Estimated bytes required for OBB installation
     */
    fun calculateRequiredObbStorage(remoteSegments: Map<String, Long>, packageName: String): Long {
        return remoteSegments.entries
            .filter { (name, _) -> name.contains(packageName) || name.endsWith(".obb", ignoreCase = true) }
            .sumOf { if (it.value > 0) it.value else 0L }
    }

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

/**
 * Story 1.7 Code Review Round 5: Extracted install.txt parsing utilities for testability.
 *
 * This object provides pure functions for parsing install.txt files and related operations.
 * Previously these were private methods in MainRepository, making them untestable.
 */
object InstallUtils {
    /**
     * Parse adb push command arguments with quote-aware splitting.
     * Handles paths with spaces when quoted: adb push "path with spaces" "/sdcard/dest"
     * Also handles unquoted simple paths: adb push source /sdcard/dest
     *
     * Story 1.7 Code Review Round 9: Added support for escaped quotes.
     * Handles escape sequences: \" and \' within quoted strings.
     * Example: adb push "path with \"escaped\" quote" /sdcard/dest
     *
     * This function is extracted from MainRepository to enable unit testing.
     *
     * @param argsString The string after "adb push " (e.g., '"source" "/dest"' or 'source /dest')
     * @return Pair of (sourcePath, destPath), or (null, null) if parsing fails
     */
    fun parseAdbPushArgs(argsString: String): Pair<String?, String?> {
        if (argsString.isBlank()) return null to null

        val args = mutableListOf<String>()
        var current = StringBuilder()
        var inQuote = false
        var quoteChar = ' '
        var escapeNext = false

        for (char in argsString) {
            when {
                // Handle escape character
                escapeNext -> {
                    // Add the escaped character literally
                    current.append(char)
                    escapeNext = false
                }
                // Backslash starts an escape sequence inside quotes
                inQuote && char == '\\' -> {
                    escapeNext = true
                }
                // Start of quoted string
                !inQuote && (char == '"' || char == '\'') -> {
                    inQuote = true
                    quoteChar = char
                }
                // End of quoted string
                inQuote && char == quoteChar -> {
                    inQuote = false
                    // Add the current token (may be empty for adjacent quotes)
                    if (current.isNotEmpty()) {
                        args.add(current.toString())
                        current = StringBuilder()
                    }
                }
                // Whitespace outside quotes = delimiter
                !inQuote && char.isWhitespace() -> {
                    if (current.isNotEmpty()) {
                        args.add(current.toString())
                        current = StringBuilder()
                    }
                }
                // Regular character
                else -> current.append(char)
            }
        }

        // Add final token if any (including if escape was pending - add backslash)
        if (escapeNext) {
            current.append('\\')
        }
        if (current.isNotEmpty()) {
            args.add(current.toString())
        }

        // Need at least 2 arguments (source, dest)
        return if (args.size >= 2) {
            args[0] to args[1]
        } else {
            null to null
        }
    }

    /**
     * Check if a line from install.txt is a valid adb push command.
     * Only matches lines that START with "adb push" (case-insensitive).
     *
     * @param line The line to check
     * @return true if this is an adb push command line
     */
    fun isAdbPushCommand(line: String): Boolean {
        return line.trim().startsWith("adb push", ignoreCase = true)
    }

    /**
     * Story 1.7 Code Review: Format bytes to human-readable string (e.g., "1.5 GB").
     * Extracted to eliminate duplication between MainRepository and MainViewModel.
     *
     * @param bytes Number of bytes to format
     * @return Formatted string with appropriate unit (B, KB, MB, GB, TB)
     */
    fun formatBytes(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            bytes < 1024 * 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
            else -> String.format(java.util.Locale.US, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
        }
    }

    /**
     * Story 1.7 Code Review Round 9: OBB file comparator for natural/numeric sorting.
     *
     * OBB format: main.{versionCode}.{packageName}.obb or patch.{versionCode}.{packageName}.obb
     * This comparator ensures:
     * 1. main files come before patch files (alphabetically)
     * 2. Version codes are sorted numerically (1, 2, 10, 20 not 1, 10, 2, 20)
     *
     * Extracted from MainRepository.parseInstallationArtifacts() for testability.
     */
    val obbFileComparator: Comparator<String> = compareBy<String> { fileName ->
        // Sort by prefix (main before patch - 'm' < 'p' alphabetically)
        fileName.substringBefore(".", "").lowercase()
    }.thenBy { fileName ->
        // Extract version code (second part after first dot) and sort numerically
        val parts = fileName.split(".")
        if (parts.size >= 2) parts[1].toIntOrNull() ?: 0 else 0
    }

    /**
     * Sort a list of OBB file names using natural/numeric sorting.
     *
     * @param obbFileNames List of OBB file names to sort
     * @return Sorted list with main before patch, version codes in numeric order
     */
    fun sortObbFiles(obbFileNames: List<String>): List<String> {
        return obbFileNames.sortedWith(obbFileComparator)
    }
}
