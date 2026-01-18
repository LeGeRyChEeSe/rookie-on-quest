package com.vrpirates.rookieonquest.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Manages migration from v2.4.0 in-memory queue to v2.5.0 Room-backed queue
 */
object MigrationManager {
    private const val TAG = "MigrationManager"
    private const val LEGACY_QUEUE_KEY = "queue_snapshot_v2_4_0"
    private const val MIGRATION_COMPLETE_KEY = "migration_v2_4_0_complete"

    /**
     * Data class representing the legacy v2.4.0 InstallTaskState structure
     * Used for JSON deserialization from SharedPreferences
     *
     * IMPORTANT: All fields are nullable to handle corrupted/partial JSON gracefully.
     * Gson can set non-nullable fields to null on malformed JSON, causing crashes.
     * Validation happens in convertLegacyTask() where required fields are checked.
     */
    data class LegacyInstallTaskState(
        val releaseName: String?, // Required - validated in conversion
        val gameName: String?,
        val packageName: String?,
        val status: String?, // Required - validated in conversion
        val progress: Float?,
        val message: String?,
        val currentSize: String?,
        val totalSize: String?,
        val isDownloadOnly: Boolean?,
        val totalBytes: Long?,
        val downloadedBytes: Long?,
        val error: String?,
        val queuePosition: Int?,
        val createdAt: Long?
    )

    /**
     * Checks if migration from v2.4.0 is needed
     */
    fun needsMigration(context: Context): Boolean {
        val sharedPrefs = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
        val hasLegacyQueue = sharedPrefs.contains(LEGACY_QUEUE_KEY)
        val migrationComplete = sharedPrefs.getBoolean(MIGRATION_COMPLETE_KEY, false)

        return hasLegacyQueue && !migrationComplete
    }

    /**
     * Performs the migration from legacy v2.4.0 queue to Room database
     *
     * @param context Application context
     * @param database AppDatabase instance
     * @return Number of successfully migrated items, or -1 on failure
     */
    suspend fun migrateLegacyQueue(context: Context, database: AppDatabase): Int = withContext(Dispatchers.IO) {
        val sharedPrefs = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)

        try {
            Log.i(TAG, "Starting migration from v2.4.0 queue to Room database")

            // Check if migration is needed
            if (!needsMigration(context)) {
                Log.i(TAG, "Migration not needed - already completed or no legacy data")
                return@withContext 0
            }

            // Load legacy queue JSON
            val legacyJson = sharedPrefs.getString(LEGACY_QUEUE_KEY, null)
            if (legacyJson == null) {
                Log.w(TAG, "Legacy queue key exists but value is null")
                markMigrationComplete(sharedPrefs)
                return@withContext 0
            }

            // Parse legacy queue JSON
            val gson = Gson()
            val type = object : TypeToken<List<LegacyInstallTaskState>>() {}.type
            val legacyQueue: List<LegacyInstallTaskState> = try {
                gson.fromJson(legacyJson, type)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse legacy queue JSON - data preserved for retry/diagnostics", e)
                // Log sanitized preview: redact potential PII, keep structure for debugging
                Log.e(TAG, "JSON preview (sanitized): ${sanitizeJsonForLog(legacyJson)}")
                // DO NOT mark as complete - preserve legacy data for retry or manual recovery
                // User can investigate logs and potentially fix the JSON manually
                return@withContext -1
            }

            Log.i(TAG, "Found ${legacyQueue.size} items in legacy queue")

            // Convert legacy items to Room entities with validation
            // Wrap in runCatching to handle QueuedInstallEntity.init exceptions
            val validEntities = legacyQueue.mapIndexedNotNull { index, legacyTask ->
                runCatching {
                    convertLegacyTask(legacyTask, index)
                }.getOrElse { e ->
                    Log.w(TAG, "Failed to convert legacy task: ${legacyTask.releaseName} - ${e.message}", e)
                    null
                }
            }

            if (validEntities.isEmpty()) {
                Log.w(TAG, "No valid entities after validation - starting with empty queue")
                markMigrationComplete(sharedPrefs)
                return@withContext 0
            }

            // Insert into Room database
            database.queuedInstallDao().insertAll(validEntities)
            Log.i(TAG, "Successfully inserted ${validEntities.size} items into Room database")

            // Mark migration as complete and remove legacy data
            markMigrationComplete(sharedPrefs)
            Log.i(TAG, "Migration completed successfully")

            validEntities.size
        } catch (e: Exception) {
            Log.e(TAG, "Migration failed with unexpected error", e)
            // Do NOT mark as complete - allow retry on next launch
            -1
        }
    }

    /**
     * Converts a legacy v2.4.0 InstallTaskState to v2.5.0 QueuedInstallEntity
     *
     * @throws IllegalArgumentException if required fields are missing or invalid
     */
    private fun convertLegacyTask(
        legacyTask: LegacyInstallTaskState,
        fallbackPosition: Int
    ): QueuedInstallEntity {
        // Validate required fields - throw to be caught by runCatching in caller
        val releaseName = legacyTask.releaseName
            ?: throw IllegalArgumentException("Missing required field: releaseName")

        if (releaseName.isBlank()) {
            throw IllegalArgumentException("releaseName cannot be blank")
        }

        val statusStr = legacyTask.status
            ?: throw IllegalArgumentException("Missing required field: status for $releaseName")

        val currentTime = System.currentTimeMillis()

        // Convert status enum - map old status names to new InstallStatus
        val newStatus = convertLegacyStatus(statusStr)

        // Ensure progress is in valid range (0.0 to 1.0), default to 0.0 if null
        val validProgress = (legacyTask.progress ?: 0.0f).coerceIn(0.0f, 1.0f)

        // Safe handling of totalBytes - treat null or <= 0 as null
        val totalBytes = legacyTask.totalBytes?.takeIf { it > 0 }

        return QueuedInstallEntity(
            releaseName = releaseName,
            status = newStatus.name,
            progress = validProgress,
            downloadedBytes = legacyTask.downloadedBytes,
            totalBytes = totalBytes,
            queuePosition = legacyTask.queuePosition ?: fallbackPosition,
            createdAt = legacyTask.createdAt ?: currentTime,
            lastUpdatedAt = currentTime,
            isDownloadOnly = legacyTask.isDownloadOnly ?: false
        )
    }

    /**
     * Converts legacy v2.4.0 InstallTaskStatus enum to v2.5.0 InstallStatus enum
     *
     * v2.4.0 Status: QUEUED, DOWNLOADING, EXTRACTING, INSTALLING, PAUSED, COMPLETED, FAILED
     * v2.5.0 Status: QUEUED, DOWNLOADING, EXTRACTING, COPYING_OBB, INSTALLING, PAUSED, COMPLETED, FAILED
     *
     * DESIGN DECISION: Legacy INSTALLING → INSTALLING (not EXTRACTING)
     * ================================================================
     * v2.4.0's INSTALLING phase combined both OBB copy + APK install into one state.
     * v2.5.0 splits this into COPYING_OBB → INSTALLING for better UI feedback.
     *
     * We map INSTALLING → INSTALLING (not EXTRACTING) because:
     * 1. Preserves progress: If task was genuinely installing, restarting at extraction loses work
     * 2. Minimal impact: Worst case is user sees "Installing..." instead of "Copying OBB..." briefly
     * 3. Queue processor handles state correctly regardless of migrated status
     *
     * Alternative considered: Map to EXTRACTING (safer, forces re-extraction)
     * - Rejected: Would cause re-download/re-extraction of potentially large games
     *
     * Post-migration, the queue processor will:
     * - If files exist: Continue from INSTALLING phase normally
     * - If files missing: Fail and retry from beginning (correct behavior)
     */
    private fun convertLegacyStatus(legacyStatus: String): InstallStatus {
        return when (legacyStatus) {
            "QUEUED" -> InstallStatus.QUEUED
            "DOWNLOADING" -> InstallStatus.DOWNLOADING
            "EXTRACTING" -> InstallStatus.EXTRACTING
            "INSTALLING" -> InstallStatus.INSTALLING // See DESIGN DECISION above
            "PAUSED" -> InstallStatus.PAUSED
            "COMPLETED" -> InstallStatus.COMPLETED
            "FAILED" -> InstallStatus.FAILED
            else -> {
                val displayStatus = legacyStatus.ifBlank { "<empty>" }
                Log.w(TAG, "Unknown legacy status: '$displayStatus', defaulting to QUEUED. Valid values: ${InstallStatus.entries.joinToString()}")
                InstallStatus.QUEUED
            }
        }
    }

    /**
     * Marks the migration as complete in SharedPreferences and removes legacy data
     */
    private fun markMigrationComplete(sharedPrefs: SharedPreferences) {
        sharedPrefs.edit()
            .putBoolean(MIGRATION_COMPLETE_KEY, true)
            .remove(LEGACY_QUEUE_KEY)
            .apply()
        Log.i(TAG, "Migration marked as complete, legacy data removed")
    }

    /**
     * Resets migration state - ONLY for debugging/testing
     */
    fun resetMigration(context: Context) {
        val sharedPrefs = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
        sharedPrefs.edit()
            .remove(MIGRATION_COMPLETE_KEY)
            .apply()
        Log.i(TAG, "Migration state reset - will run again on next launch")
    }

    /**
     * Sanitizes JSON for logging by keeping structure but truncating long string values
     * to avoid leaking potentially sensitive game names or other PII.
     *
     * Uses a more robust approach than simple regex to handle:
     * - Formatted JSON with whitespace and newlines
     * - Escaped quotes within strings
     * - Various JSON structures
     */
    private fun sanitizeJsonForLog(json: String): String {
        // Truncate to 1000 chars first to limit processing
        val truncated = json.take(1000)

        // Build sanitized output by processing character by character
        // This handles escaped quotes and whitespace better than simple regex
        val result = StringBuilder()
        var inString = false
        var escapeNext = false
        var currentStringLength = 0

        for (char in truncated) {
            when {
                escapeNext -> {
                    // Skip escaped character
                    escapeNext = false
                    if (inString) currentStringLength++
                    result.append(char)
                }
                char == '\\' -> {
                    escapeNext = true
                    result.append(char)
                }
                char == '"' -> {
                    if (!inString) {
                        // Starting a string
                        inString = true
                        currentStringLength = 0
                        result.append(char)
                    } else {
                        // Ending a string - truncate if > 25 chars
                        inString = false
                        if (currentStringLength > 25) {
                            // Keep first 15 chars, add "..."
                            result.append("...")
                        }
                        result.append(char)
                    }
                }
                inString -> {
                    currentStringLength++
                    // Only append first 15 chars of long strings
                    if (currentStringLength <= 15) {
                        result.append(char)
                    }
                }
                else -> result.append(char)
            }
        }

        return if (json.length > 1000) {
            "$result... [truncated, total ${json.length} chars]"
        } else {
            result.toString()
        }
    }
}
