package com.vrpirates.rookieonquest.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface QueuedInstallDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: QueuedInstallEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<QueuedInstallEntity>)

    @Update
    suspend fun update(entity: QueuedInstallEntity)

    @Delete
    suspend fun delete(entity: QueuedInstallEntity)

    @Query("SELECT * FROM install_queue ORDER BY queuePosition ASC")
    fun getAllFlow(): Flow<List<QueuedInstallEntity>>

    @Query("SELECT * FROM install_queue ORDER BY queuePosition ASC")
    suspend fun getAll(): List<QueuedInstallEntity>

    @Query("SELECT * FROM install_queue WHERE releaseName = :releaseName")
    suspend fun getByReleaseName(releaseName: String): QueuedInstallEntity?

    @Query("UPDATE install_queue SET status = :status, lastUpdatedAt = :timestamp WHERE releaseName = :releaseName")
    suspend fun updateStatus(releaseName: String, status: String, timestamp: Long): Int

    @Query("UPDATE install_queue SET progress = :progress, downloadedBytes = :downloadedBytes, totalBytes = :totalBytes, lastUpdatedAt = :timestamp WHERE releaseName = :releaseName")
    suspend fun updateProgress(
        releaseName: String,
        progress: Float,
        downloadedBytes: Long?,
        totalBytes: Long?,
        timestamp: Long
    ): Int

    // Granular update methods for better API clarity
    @Query("UPDATE install_queue SET progress = :progress, lastUpdatedAt = :timestamp WHERE releaseName = :releaseName")
    suspend fun updateProgressOnly(
        releaseName: String,
        progress: Float,
        timestamp: Long
    ): Int

    /**
     * Updates progress and downloadedBytes only (excludes totalBytes).
     * Optimization for repeated progress updates where totalBytes is constant.
     */
    @Query("UPDATE install_queue SET progress = :progress, downloadedBytes = :downloadedBytes, lastUpdatedAt = :timestamp WHERE releaseName = :releaseName")
    suspend fun updateProgressAndBytes(
        releaseName: String,
        progress: Float,
        downloadedBytes: Long,
        timestamp: Long
    ): Int

    @Query("UPDATE install_queue SET downloadedBytes = :downloadedBytes, lastUpdatedAt = :timestamp WHERE releaseName = :releaseName")
    suspend fun updateDownloadedBytes(
        releaseName: String,
        downloadedBytes: Long,
        timestamp: Long
    ): Int

    @Query("UPDATE install_queue SET queuePosition = :newPosition, lastUpdatedAt = :timestamp WHERE releaseName = :releaseName")
    suspend fun updateQueuePosition(
        releaseName: String,
        newPosition: Int,
        timestamp: Long
    ): Int

    @Query("DELETE FROM install_queue WHERE releaseName = :releaseName")
    suspend fun deleteByReleaseName(releaseName: String): Int

    @Query("DELETE FROM install_queue")
    suspend fun deleteAll(): Int

    @Query("SELECT COALESCE(MAX(queuePosition), -1) + 1 FROM install_queue")
    suspend fun getNextPosition(): Int

    /**
     * Atomically gets next queue position and inserts the entity.
     * This prevents race conditions where two concurrent addToQueue calls
     * could get the same position value.
     *
     * @param entity The entity to insert (queuePosition field will be overwritten)
     */
    @androidx.room.Transaction
    suspend fun insertAtNextPosition(entity: QueuedInstallEntity) {
        val nextPosition = getNextPosition()
        val entityWithPosition = entity.copy(queuePosition = nextPosition)
        insert(entityWithPosition)
    }

    /**
     * Updates multiple queue positions atomically
     *
     * Note: Room doesn't support bulk UPDATE with different values per row.
     * Individual UPDATEs within @Transaction are acceptable for queue sizes < 100.
     * The @Transaction ensures atomicity - all or nothing committed.
     */
    @androidx.room.Transaction
    suspend fun reorderQueue(entities: List<QueuedInstallEntity>) {
        // Capture timestamp once to ensure consistency
        val timestamp = System.currentTimeMillis()
        // Only update entities that actually need position changes
        entities.forEach { entity ->
            updateQueuePosition(
                releaseName = entity.releaseName,
                newPosition = entity.queuePosition,
                timestamp = timestamp
            )
        }
    }

    /**
     * Promotes a task to the front of the queue atomically
     * All operations happen in a single transaction to prevent corrupted queue state
     *
     * Optimization: Only updates entities whose positions actually change
     */
    @androidx.room.Transaction
    suspend fun promoteToFront(releaseName: String) {
        val currentQueue = getAll()
        val taskToPromote = currentQueue.find { it.releaseName == releaseName } ?: return

        // Early exit if already at front
        if (taskToPromote.queuePosition == 0) return

        // Only collect entities that need position updates
        val updates = mutableListOf<Pair<String, Int>>()
        updates.add(releaseName to 0) // Promoted task goes to front

        currentQueue.forEach { entity ->
            if (entity.releaseName != releaseName && entity.queuePosition < taskToPromote.queuePosition) {
                // Shift down by 1
                updates.add(entity.releaseName to entity.queuePosition + 1)
            }
        }

        // Apply all updates with single timestamp
        val timestamp = System.currentTimeMillis()
        updates.forEach { (name, position) ->
            updateQueuePosition(name, position, timestamp)
        }
    }

    /**
     * Promotes a task to the front of the queue AND sets its status to QUEUED atomically.
     * This ensures both operations succeed or fail together, preventing partial state updates.
     *
     * Use this method when promoting a PAUSED or FAILED task that needs to restart processing.
     *
     * @param releaseName The task to promote
     * @param newStatus The status to set (typically QUEUED)
     */
    @androidx.room.Transaction
    suspend fun promoteToFrontAndUpdateStatus(releaseName: String, newStatus: String) {
        val currentQueue = getAll()
        val taskToPromote = currentQueue.find { it.releaseName == releaseName } ?: return

        val timestamp = System.currentTimeMillis()

        // Update status first (this is the critical operation)
        updateStatus(releaseName, newStatus, timestamp)

        // Early exit if already at front - position update not needed
        if (taskToPromote.queuePosition == 0) return

        // Only collect entities that need position updates
        val updates = mutableListOf<Pair<String, Int>>()
        updates.add(releaseName to 0) // Promoted task goes to front

        currentQueue.forEach { entity ->
            if (entity.releaseName != releaseName && entity.queuePosition < taskToPromote.queuePosition) {
                // Shift down by 1
                updates.add(entity.releaseName to entity.queuePosition + 1)
            }
        }

        // Apply position updates
        updates.forEach { (name, position) ->
            updateQueuePosition(name, position, timestamp)
        }
    }
}
