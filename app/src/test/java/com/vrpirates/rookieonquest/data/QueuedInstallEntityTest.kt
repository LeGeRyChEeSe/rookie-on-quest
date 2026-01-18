package com.vrpirates.rookieonquest.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for QueuedInstallEntity
 *
 * Test timestamp constants to avoid magic numbers and ensure deterministic tests
 */
class QueuedInstallEntityTest {

    companion object {
        // Test timestamp constants for deterministic tests
        private const val BASE_TIMESTAMP = 1704067200000L // 2024-01-01 00:00:00 UTC
        private const val LATER_TIMESTAMP = 1704153600000L // 2024-01-02 00:00:00 UTC

        // Test byte constants
        private const val HALF_MEGABYTE = 500_000L
        private const val ONE_MEGABYTE = 1_000_000L
        private const val ONE_AND_HALF_MEGABYTE = 1_500_000L
    }

    @Test
    fun entityCreation_withValidData_succeeds() {
        val entity = QueuedInstallEntity(
            releaseName = "TestGame-v1.0",
            status = "QUEUED",
            progress = 0.0f,
            downloadedBytes = null,
            totalBytes = null,
            queuePosition = 0,
            createdAt = BASE_TIMESTAMP,
            lastUpdatedAt = BASE_TIMESTAMP
        )

        assertEquals("TestGame-v1.0", entity.releaseName)
        assertEquals("QUEUED", entity.status)
        assertEquals(0.0f, entity.progress, 0.001f)
        assertEquals(null, entity.downloadedBytes)
        assertEquals(null, entity.totalBytes)
        assertEquals(0, entity.queuePosition)
        assertEquals(BASE_TIMESTAMP, entity.createdAt)
        assertEquals(BASE_TIMESTAMP, entity.lastUpdatedAt)
    }

    @Test
    fun entityCreation_withDownloadProgress_succeeds() {
        val entity = QueuedInstallEntity(
            releaseName = "TestGame-v1.0",
            status = "DOWNLOADING",
            progress = 0.5f,
            downloadedBytes = HALF_MEGABYTE,
            totalBytes = ONE_MEGABYTE,
            queuePosition = 0,
            createdAt = BASE_TIMESTAMP,
            lastUpdatedAt = LATER_TIMESTAMP
        )

        assertEquals("DOWNLOADING", entity.status)
        assertEquals(0.5f, entity.progress, 0.001f)
        assertEquals(HALF_MEGABYTE, entity.downloadedBytes)
        assertEquals(ONE_MEGABYTE, entity.totalBytes)
    }

    @Test
    fun entityEquality_withSameData_isEqual() {
        val entity1 = QueuedInstallEntity(
            releaseName = "TestGame",
            status = "QUEUED",
            progress = 0.0f,
            downloadedBytes = null,
            totalBytes = null,
            queuePosition = 0,
            createdAt = BASE_TIMESTAMP,
            lastUpdatedAt = BASE_TIMESTAMP
        )
        val entity2 = QueuedInstallEntity(
            releaseName = "TestGame",
            status = "QUEUED",
            progress = 0.0f,
            downloadedBytes = null,
            totalBytes = null,
            queuePosition = 0,
            createdAt = BASE_TIMESTAMP,
            lastUpdatedAt = BASE_TIMESTAMP
        )

        assertEquals(entity1, entity2)
        assertEquals(entity1.hashCode(), entity2.hashCode())
    }

    @Test
    fun entityEquality_withDifferentData_isNotEqual() {
        val entity1 = QueuedInstallEntity(
            releaseName = "TestGame1",
            status = "QUEUED",
            progress = 0.0f,
            downloadedBytes = null,
            totalBytes = null,
            queuePosition = 0,
            createdAt = BASE_TIMESTAMP,
            lastUpdatedAt = BASE_TIMESTAMP
        )
        val entity2 = QueuedInstallEntity(
            releaseName = "TestGame2",
            status = "QUEUED",
            progress = 0.0f,
            downloadedBytes = null,
            totalBytes = null,
            queuePosition = 0,
            createdAt = BASE_TIMESTAMP,
            lastUpdatedAt = BASE_TIMESTAMP
        )

        assertNotEquals(entity1, entity2)
    }

    @Test
    fun dataClass_copy_createsNewInstance() {
        val original = QueuedInstallEntity(
            releaseName = "TestGame",
            status = "QUEUED",
            progress = 0.0f,
            downloadedBytes = null,
            totalBytes = null,
            queuePosition = 0,
            createdAt = BASE_TIMESTAMP,
            lastUpdatedAt = BASE_TIMESTAMP
        )

        val copy = original.copy(status = "DOWNLOADING", progress = 0.3f)

        assertEquals("TestGame", copy.releaseName)
        assertEquals("DOWNLOADING", copy.status)
        assertEquals(0.3f, copy.progress, 0.001f)
        assertNotEquals(original.status, copy.status)
    }

    @Test
    fun progressValue_withinValidRange() {
        val entity = QueuedInstallEntity(
            releaseName = "TestGame",
            status = "DOWNLOADING",
            progress = 0.75f,
            downloadedBytes = 750_000L,
            totalBytes = ONE_MEGABYTE,
            queuePosition = 0,
            createdAt = BASE_TIMESTAMP,
            lastUpdatedAt = BASE_TIMESTAMP
        )

        assertTrue(entity.progress >= 0.0f)
        assertTrue(entity.progress <= 1.0f)
    }

    @Test
    fun queuePosition_ordering() {
        val entity1 = QueuedInstallEntity(
            releaseName = "Game1",
            status = "QUEUED",
            progress = 0.0f,
            downloadedBytes = null,
            totalBytes = null,
            queuePosition = 0,
            createdAt = BASE_TIMESTAMP,
            lastUpdatedAt = BASE_TIMESTAMP
        )
        val entity2 = QueuedInstallEntity(
            releaseName = "Game2",
            status = "QUEUED",
            progress = 0.0f,
            downloadedBytes = null,
            totalBytes = null,
            queuePosition = 1,
            createdAt = BASE_TIMESTAMP,
            lastUpdatedAt = BASE_TIMESTAMP
        )

        assertTrue(entity1.queuePosition < entity2.queuePosition)
    }

    @Test
    fun statusTransitions_validValues() {
        val statuses = listOf(
            "QUEUED",
            "DOWNLOADING",
            "EXTRACTING",
            "COPYING_OBB",
            "INSTALLING",
            "PAUSED",
            "COMPLETED",
            "FAILED"
        )

        statuses.forEach { status ->
            val entity = QueuedInstallEntity(
                releaseName = "TestGame",
                status = status,
                progress = 0.0f,
                downloadedBytes = null,
                totalBytes = null,
                queuePosition = 0,
                createdAt = BASE_TIMESTAMP,
                lastUpdatedAt = BASE_TIMESTAMP
            )
            assertEquals(status, entity.status)
        }
    }

    @Test
    fun nullableFields_handledCorrectly() {
        val entity = QueuedInstallEntity(
            releaseName = "TestGame",
            status = "QUEUED",
            progress = 0.0f,
            downloadedBytes = null,
            totalBytes = null,
            queuePosition = 0,
            createdAt = BASE_TIMESTAMP,
            lastUpdatedAt = BASE_TIMESTAMP
        )

        assertEquals(null, entity.downloadedBytes)
        assertEquals(null, entity.totalBytes)
    }

    @Test
    fun createHelper_withEnum_createsValidEntity() {
        val entity = QueuedInstallEntity.create(
            releaseName = "TestGame",
            status = InstallStatus.DOWNLOADING,
            progress = 0.5f,
            downloadedBytes = HALF_MEGABYTE,
            totalBytes = ONE_MEGABYTE,
            queuePosition = 0,
            createdAt = BASE_TIMESTAMP,
            lastUpdatedAt = BASE_TIMESTAMP
        )

        assertEquals("TestGame", entity.releaseName)
        assertEquals(InstallStatus.DOWNLOADING.name, entity.status)
        assertEquals(InstallStatus.DOWNLOADING, entity.statusEnum)
        assertEquals(0.5f, entity.progress, 0.001f)
    }

    // ========== Validation Tests (using validate() method instead of init block) ==========

    @Test
    fun validation_invalidProgress_returnsError() {
        val errors = QueuedInstallEntity.validate(
            releaseName = "TestGame",
            status = InstallStatus.QUEUED.name,
            progress = 1.5f,
            downloadedBytes = null,
            totalBytes = null,
            queuePosition = 0,
            createdAt = BASE_TIMESTAMP,
            lastUpdatedAt = BASE_TIMESTAMP
        )

        assertTrue("Should have progress validation error", errors.any { it.contains("progress") })
    }

    @Test
    fun validation_negativeQueuePosition_returnsError() {
        val errors = QueuedInstallEntity.validate(
            releaseName = "TestGame",
            status = InstallStatus.QUEUED.name,
            progress = 0.0f,
            downloadedBytes = null,
            totalBytes = null,
            queuePosition = -1,
            createdAt = BASE_TIMESTAMP,
            lastUpdatedAt = BASE_TIMESTAMP
        )

        assertTrue("Should have queuePosition validation error", errors.any { it.contains("queuePosition") })
    }

    @Test
    fun validation_blankReleaseName_returnsError() {
        val errors = QueuedInstallEntity.validate(
            releaseName = "",
            status = InstallStatus.QUEUED.name,
            progress = 0.0f,
            downloadedBytes = null,
            totalBytes = null,
            queuePosition = 0,
            createdAt = BASE_TIMESTAMP,
            lastUpdatedAt = BASE_TIMESTAMP
        )

        assertTrue("Should have releaseName validation error", errors.any { it.contains("releaseName") })
    }

    @Test
    fun validation_downloadedBytesExceedsTotalBytes_returnsError() {
        val errors = QueuedInstallEntity.validate(
            releaseName = "TestGame",
            status = InstallStatus.DOWNLOADING.name,
            progress = 0.5f,
            downloadedBytes = ONE_AND_HALF_MEGABYTE,
            totalBytes = ONE_MEGABYTE,
            queuePosition = 0,
            createdAt = BASE_TIMESTAMP,
            lastUpdatedAt = BASE_TIMESTAMP
        )

        assertTrue("Should have bytes validation error", errors.any { it.contains("downloadedBytes") })
    }

    @Test
    fun validation_invalidStatus_returnsError() {
        val errors = QueuedInstallEntity.validate(
            releaseName = "TestGame",
            status = "INVALID_STATUS",
            progress = 0.0f,
            downloadedBytes = null,
            totalBytes = null,
            queuePosition = 0,
            createdAt = BASE_TIMESTAMP,
            lastUpdatedAt = BASE_TIMESTAMP
        )

        assertTrue("Should have status validation error", errors.any { it.contains("status") })
    }

    @Test
    fun validation_invalidTimestamps_returnsError() {
        // Test lastUpdatedAt < createdAt
        val errors = QueuedInstallEntity.validate(
            releaseName = "TestGame",
            status = InstallStatus.QUEUED.name,
            progress = 0.0f,
            downloadedBytes = null,
            totalBytes = null,
            queuePosition = 0,
            createdAt = LATER_TIMESTAMP,
            lastUpdatedAt = BASE_TIMESTAMP
        )

        assertTrue("Should have timestamp validation error", errors.any { it.contains("lastUpdatedAt") })
    }

    @Test
    fun validation_validData_returnsEmptyList() {
        val errors = QueuedInstallEntity.validate(
            releaseName = "TestGame",
            status = InstallStatus.QUEUED.name,
            progress = 0.0f,
            downloadedBytes = null,
            totalBytes = null,
            queuePosition = 0,
            createdAt = BASE_TIMESTAMP,
            lastUpdatedAt = BASE_TIMESTAMP
        )

        assertTrue("Valid data should return no errors", errors.isEmpty())
    }

    @Test
    fun createValidated_withValidData_succeeds() {
        val entity = QueuedInstallEntity.createValidated(
            releaseName = "TestGame",
            status = InstallStatus.DOWNLOADING,
            progress = 0.5f,
            downloadedBytes = HALF_MEGABYTE,
            totalBytes = ONE_MEGABYTE,
            queuePosition = 0,
            createdAt = BASE_TIMESTAMP,
            lastUpdatedAt = BASE_TIMESTAMP
        )

        assertEquals("TestGame", entity.releaseName)
        assertEquals(InstallStatus.DOWNLOADING.name, entity.status)
        assertTrue(entity.isValid())
    }

    @Test(expected = IllegalArgumentException::class)
    fun createValidated_withInvalidData_throwsException() {
        QueuedInstallEntity.createValidated(
            releaseName = "", // Invalid: blank
            status = InstallStatus.QUEUED,
            progress = 0.0f,
            downloadedBytes = null,
            totalBytes = null,
            queuePosition = 0,
            createdAt = BASE_TIMESTAMP,
            lastUpdatedAt = BASE_TIMESTAMP
        )
    }

    @Test
    fun isValid_withValidEntity_returnsTrue() {
        val entity = QueuedInstallEntity(
            releaseName = "TestGame",
            status = InstallStatus.QUEUED.name,
            progress = 0.0f,
            downloadedBytes = null,
            totalBytes = null,
            queuePosition = 0,
            createdAt = BASE_TIMESTAMP,
            lastUpdatedAt = BASE_TIMESTAMP
        )

        assertTrue(entity.isValid())
    }

    @Test
    fun isValid_withInvalidEntity_returnsFalse() {
        // Entity with invalid data (possible if written via @Query)
        val entity = QueuedInstallEntity(
            releaseName = "TestGame",
            status = "UNKNOWN_STATUS", // Invalid status
            progress = 0.0f,
            downloadedBytes = null,
            totalBytes = null,
            queuePosition = 0,
            createdAt = BASE_TIMESTAMP,
            lastUpdatedAt = BASE_TIMESTAMP
        )

        assertFalse(entity.isValid())
    }

    @Test
    fun validate_instanceMethod_matchesCompanionMethod() {
        val entity = QueuedInstallEntity(
            releaseName = "TestGame",
            status = InstallStatus.QUEUED.name,
            progress = 0.0f,
            downloadedBytes = null,
            totalBytes = null,
            queuePosition = 0,
            createdAt = BASE_TIMESTAMP,
            lastUpdatedAt = BASE_TIMESTAMP
        )

        val instanceErrors = entity.validate()
        val companionErrors = QueuedInstallEntity.validate(
            releaseName = entity.releaseName,
            status = entity.status,
            progress = entity.progress,
            downloadedBytes = entity.downloadedBytes,
            totalBytes = entity.totalBytes,
            queuePosition = entity.queuePosition,
            createdAt = entity.createdAt,
            lastUpdatedAt = entity.lastUpdatedAt
        )

        assertEquals(companionErrors, instanceErrors)
    }

    @Test
    fun statusEnum_convertsCorrectly() {
        InstallStatus.entries.forEach { expectedStatus ->
            val entity = QueuedInstallEntity(
                releaseName = "TestGame",
                status = expectedStatus.name,
                progress = 0.0f,
                downloadedBytes = null,
                totalBytes = null,
                queuePosition = 0,
                createdAt = BASE_TIMESTAMP,
                lastUpdatedAt = BASE_TIMESTAMP
            )
            assertEquals(expectedStatus, entity.statusEnum)
        }
    }

    @Test
    fun isDownloadOnly_defaultValue_isFalse() {
        val entity = QueuedInstallEntity(
            releaseName = "TestGame",
            status = "QUEUED",
            progress = 0.0f,
            downloadedBytes = null,
            totalBytes = null,
            queuePosition = 0,
            createdAt = BASE_TIMESTAMP,
            lastUpdatedAt = BASE_TIMESTAMP
        )

        assertFalse(entity.isDownloadOnly)
    }

    @Test
    fun isDownloadOnly_explicitValue_preserved() {
        val entityTrue = QueuedInstallEntity(
            releaseName = "TestGame1",
            status = "DOWNLOADING",
            progress = 0.5f,
            downloadedBytes = HALF_MEGABYTE,
            totalBytes = ONE_MEGABYTE,
            queuePosition = 0,
            createdAt = BASE_TIMESTAMP,
            lastUpdatedAt = BASE_TIMESTAMP,
            isDownloadOnly = true
        )

        val entityFalse = QueuedInstallEntity(
            releaseName = "TestGame2",
            status = "DOWNLOADING",
            progress = 0.5f,
            downloadedBytes = HALF_MEGABYTE,
            totalBytes = ONE_MEGABYTE,
            queuePosition = 0,
            createdAt = BASE_TIMESTAMP,
            lastUpdatedAt = BASE_TIMESTAMP,
            isDownloadOnly = false
        )

        assertTrue(entityTrue.isDownloadOnly)
        assertFalse(entityFalse.isDownloadOnly)
    }

    @Test
    fun createHelper_withIsDownloadOnly_createsValidEntity() {
        val entity = QueuedInstallEntity.create(
            releaseName = "TestGame",
            status = InstallStatus.DOWNLOADING,
            progress = 0.5f,
            downloadedBytes = HALF_MEGABYTE,
            totalBytes = ONE_MEGABYTE,
            queuePosition = 0,
            createdAt = BASE_TIMESTAMP,
            lastUpdatedAt = BASE_TIMESTAMP,
            isDownloadOnly = true
        )

        assertEquals("TestGame", entity.releaseName)
        assertEquals(InstallStatus.DOWNLOADING.name, entity.status)
        assertTrue(entity.isDownloadOnly)
    }

    @Test
    fun validation_negativeDownloadedBytes_returnsError() {
        val errors = QueuedInstallEntity.validate(
            releaseName = "TestGame",
            status = InstallStatus.DOWNLOADING.name,
            progress = 0.0f,
            downloadedBytes = -100L,
            totalBytes = ONE_MEGABYTE,
            queuePosition = 0,
            createdAt = BASE_TIMESTAMP,
            lastUpdatedAt = BASE_TIMESTAMP
        )

        assertTrue("Should have downloadedBytes validation error", errors.any { it.contains("downloadedBytes") })
    }

    @Test
    fun validation_zeroTotalBytes_returnsError() {
        val errors = QueuedInstallEntity.validate(
            releaseName = "TestGame",
            status = InstallStatus.DOWNLOADING.name,
            progress = 0.0f,
            downloadedBytes = null,
            totalBytes = 0L,
            queuePosition = 0,
            createdAt = BASE_TIMESTAMP,
            lastUpdatedAt = BASE_TIMESTAMP
        )

        assertTrue("Should have totalBytes validation error", errors.any { it.contains("totalBytes") })
    }

    @Test
    fun validation_multipleErrors_returnsAllErrors() {
        val errors = QueuedInstallEntity.validate(
            releaseName = "",
            status = "INVALID",
            progress = -0.5f,
            downloadedBytes = null,
            totalBytes = null,
            queuePosition = -1,
            createdAt = BASE_TIMESTAMP,
            lastUpdatedAt = BASE_TIMESTAMP
        )

        assertTrue("Should have multiple errors", errors.size >= 4)
        assertTrue("Should have releaseName error", errors.any { it.contains("releaseName") })
        assertTrue("Should have status error", errors.any { it.contains("status") })
        assertTrue("Should have progress error", errors.any { it.contains("progress") })
        assertTrue("Should have queuePosition error", errors.any { it.contains("queuePosition") })
    }
}
