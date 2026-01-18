package com.vrpirates.rookieonquest.data

import androidx.compose.runtime.Immutable
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Immutable
@Entity(
    tableName = "install_queue",
    indices = [
        Index(value = ["queuePosition"]),
        Index(value = ["status", "queuePosition"])
    ]
)
data class QueuedInstallEntity(
    @PrimaryKey
    val releaseName: String,
    val status: String,
    val progress: Float,
    val downloadedBytes: Long?,
    val totalBytes: Long?,
    val queuePosition: Int,
    val createdAt: Long,
    val lastUpdatedAt: Long,
    val isDownloadOnly: Boolean = false
) {
    // NOTE: Validation removed from init block to prevent crash loops when Room reads
    // invalid data written via @Query methods. Use validate() or create() for safe construction.

    companion object {
        // Cached valid status names for performance
        private val VALID_STATUS_NAMES: Set<String> = InstallStatus.entries.map { it.name }.toSet()

        /**
         * Validates entity data and returns a list of validation errors.
         * Empty list means data is valid.
         *
         * Use this in repository/factory layer before writing to database.
         */
        fun validate(
            releaseName: String,
            status: String,
            progress: Float,
            downloadedBytes: Long?,
            totalBytes: Long?,
            queuePosition: Int,
            createdAt: Long,
            lastUpdatedAt: Long
        ): List<String> {
            val errors = mutableListOf<String>()

            if (releaseName.isBlank()) {
                errors.add("releaseName cannot be blank")
            }
            if (progress !in 0.0f..1.0f) {
                errors.add("progress must be between 0.0 and 1.0, got $progress")
            }
            if (queuePosition < 0) {
                errors.add("queuePosition must be non-negative, got $queuePosition")
            }
            if (createdAt <= 0) {
                errors.add("createdAt must be positive, got $createdAt")
            }
            if (lastUpdatedAt <= 0) {
                errors.add("lastUpdatedAt must be positive, got $lastUpdatedAt")
            }
            if (lastUpdatedAt < createdAt) {
                errors.add("lastUpdatedAt must be >= createdAt")
            }
            if (status !in VALID_STATUS_NAMES) {
                errors.add("status must be one of $VALID_STATUS_NAMES, got $status")
            }
            downloadedBytes?.let { downloaded ->
                if (downloaded < 0) {
                    errors.add("downloadedBytes must be non-negative, got $downloaded")
                }
            }
            totalBytes?.let { total ->
                if (total <= 0) {
                    errors.add("totalBytes must be positive, got $total")
                }
            }
            if (downloadedBytes != null && totalBytes != null && downloadedBytes > totalBytes) {
                errors.add("downloadedBytes ($downloadedBytes) cannot exceed totalBytes ($totalBytes)")
            }

            return errors
        }

        /**
         * Creates a validated entity. Throws IllegalArgumentException if validation fails.
         * Use this when creating new entities to ensure data integrity.
         */
        fun createValidated(
            releaseName: String,
            status: InstallStatus,
            progress: Float = 0.0f,
            downloadedBytes: Long? = null,
            totalBytes: Long? = null,
            queuePosition: Int,
            createdAt: Long = System.currentTimeMillis(),
            lastUpdatedAt: Long = System.currentTimeMillis(),
            isDownloadOnly: Boolean = false
        ): QueuedInstallEntity {
            val errors = validate(
                releaseName = releaseName,
                status = status.name,
                progress = progress,
                downloadedBytes = downloadedBytes,
                totalBytes = totalBytes,
                queuePosition = queuePosition,
                createdAt = createdAt,
                lastUpdatedAt = lastUpdatedAt
            )
            if (errors.isNotEmpty()) {
                throw IllegalArgumentException("Invalid QueuedInstallEntity: ${errors.joinToString("; ")}")
            }
            return QueuedInstallEntity(
                releaseName = releaseName,
                status = status.name,
                progress = progress,
                downloadedBytes = downloadedBytes,
                totalBytes = totalBytes,
                queuePosition = queuePosition,
                createdAt = createdAt,
                lastUpdatedAt = lastUpdatedAt,
                isDownloadOnly = isDownloadOnly
            )
        }

        /**
         * Helper function to create entity with enum (without validation).
         * Prefer createValidated() for production code.
         */
        fun create(
            releaseName: String,
            status: InstallStatus,
            progress: Float = 0.0f,
            downloadedBytes: Long? = null,
            totalBytes: Long? = null,
            queuePosition: Int,
            createdAt: Long = System.currentTimeMillis(),
            lastUpdatedAt: Long = System.currentTimeMillis(),
            isDownloadOnly: Boolean = false
        ) = QueuedInstallEntity(
            releaseName = releaseName,
            status = status.name,
            progress = progress,
            downloadedBytes = downloadedBytes,
            totalBytes = totalBytes,
            queuePosition = queuePosition,
            createdAt = createdAt,
            lastUpdatedAt = lastUpdatedAt,
            isDownloadOnly = isDownloadOnly
        )
    }

    /**
     * Validates this entity instance.
     * Returns list of validation errors, or empty list if valid.
     */
    fun validate(): List<String> = Companion.validate(
        releaseName = releaseName,
        status = status,
        progress = progress,
        downloadedBytes = downloadedBytes,
        totalBytes = totalBytes,
        queuePosition = queuePosition,
        createdAt = createdAt,
        lastUpdatedAt = lastUpdatedAt
    )

    /**
     * Returns true if this entity passes all validation rules.
     */
    fun isValid(): Boolean = validate().isEmpty()

    // Convenience property to get status as enum
    val statusEnum: InstallStatus
        get() = InstallStatus.fromString(status)
}
