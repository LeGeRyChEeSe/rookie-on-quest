package com.vrpirates.rookieonquest.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests for QueuedInstallDao
 *
 * Uses deterministic timestamps instead of System.currentTimeMillis() for stable, reproducible tests.
 */
@RunWith(AndroidJUnit4::class)
class QueuedInstallDaoTest {
    private lateinit var database: AppDatabase
    private lateinit var dao: QueuedInstallDao

    companion object {
        // Deterministic test timestamps (2024-01-01 00:00:00 UTC, 2024-01-01 01:00:00 UTC)
        private const val BASE_TIMESTAMP = 1704067200000L
        private const val LATER_TIMESTAMP = 1704070800000L
        private const val UPDATE_TIMESTAMP = 1704074400000L

        // Test byte constants
        private const val HALF_MEGABYTE = 500_000L
        private const val THREE_QUARTERS_MEGABYTE = 750_000L
        private const val ONE_MEGABYTE = 1_000_000L
    }

    @Before
    fun setup() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        dao = database.queuedInstallDao()
    }

    @After
    fun teardown() {
        database.close()
    }

    @Test
    fun insertAndRetrieve_returnsCorrectEntity() = runTest {
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
        dao.insert(entity)

        val result = dao.getAllFlow().first()
        assertEquals(1, result.size)
        assertEquals(entity.releaseName, result[0].releaseName)
        assertEquals(entity.status, result[0].status)
    }

    @Test
    fun getAllFlow_emitsOnInsert() = runTest {
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

        dao.insert(entity1)
        val result1 = dao.getAllFlow().first()
        assertEquals(1, result1.size)

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
        dao.insert(entity2)

        val result2 = dao.getAllFlow().first()
        assertEquals(2, result2.size)
    }

    @Test
    fun updateStatus_modifiesCorrectField() = runTest {
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
        dao.insert(entity)

        val rowsAffected = dao.updateStatus("TestGame", "DOWNLOADING", UPDATE_TIMESTAMP)
        assertEquals(1, rowsAffected)

        val updated = dao.getByReleaseName("TestGame")
        assertNotNull(updated)
        assertEquals("DOWNLOADING", updated?.status)
        assertEquals(UPDATE_TIMESTAMP, updated?.lastUpdatedAt)
    }

    @Test
    fun updateProgress_modifiesCorrectFields() = runTest {
        val entity = QueuedInstallEntity(
            releaseName = "TestGame",
            status = "DOWNLOADING",
            progress = 0.0f,
            downloadedBytes = null,
            totalBytes = null,
            queuePosition = 0,
            createdAt = BASE_TIMESTAMP,
            lastUpdatedAt = BASE_TIMESTAMP
        )
        dao.insert(entity)

        val rowsAffected = dao.updateProgress(
            releaseName = "TestGame",
            progress = 0.5f,
            downloadedBytes = HALF_MEGABYTE,
            totalBytes = ONE_MEGABYTE,
            timestamp = UPDATE_TIMESTAMP
        )
        assertEquals(1, rowsAffected)

        val updated = dao.getByReleaseName("TestGame")
        assertNotNull(updated)
        assertEquals(0.5f, updated?.progress)
        assertEquals(HALF_MEGABYTE, updated?.downloadedBytes)
        assertEquals(ONE_MEGABYTE, updated?.totalBytes)
    }

    @Test
    fun delete_removesEntity() = runTest {
        val entity = QueuedInstallEntity(
            releaseName = "TestGame",
            status = "COMPLETED",
            progress = 1.0f,
            downloadedBytes = ONE_MEGABYTE,
            totalBytes = ONE_MEGABYTE,
            queuePosition = 0,
            createdAt = BASE_TIMESTAMP,
            lastUpdatedAt = BASE_TIMESTAMP
        )
        dao.insert(entity)

        dao.delete(entity)

        val result = dao.getAllFlow().first()
        assertEquals(0, result.size)

        val retrieved = dao.getByReleaseName("TestGame")
        assertNull(retrieved)
    }

    @Test
    fun getByReleaseName_returnsCorrectEntity() = runTest {
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
            status = "DOWNLOADING",
            progress = 0.3f,
            downloadedBytes = 300_000L,
            totalBytes = ONE_MEGABYTE,
            queuePosition = 1,
            createdAt = BASE_TIMESTAMP,
            lastUpdatedAt = BASE_TIMESTAMP
        )

        dao.insert(entity1)
        dao.insert(entity2)

        val retrieved = dao.getByReleaseName("Game2")
        assertNotNull(retrieved)
        assertEquals("Game2", retrieved?.releaseName)
        assertEquals("DOWNLOADING", retrieved?.status)
        assertEquals(0.3f, retrieved?.progress)
    }

    @Test
    fun getAllFlow_orderedByQueuePosition() = runTest {
        val entity1 = QueuedInstallEntity(
            releaseName = "Game1",
            status = "QUEUED",
            progress = 0.0f,
            downloadedBytes = null,
            totalBytes = null,
            queuePosition = 2,
            createdAt = BASE_TIMESTAMP,
            lastUpdatedAt = BASE_TIMESTAMP
        )
        val entity2 = QueuedInstallEntity(
            releaseName = "Game2",
            status = "QUEUED",
            progress = 0.0f,
            downloadedBytes = null,
            totalBytes = null,
            queuePosition = 0,
            createdAt = BASE_TIMESTAMP,
            lastUpdatedAt = BASE_TIMESTAMP
        )
        val entity3 = QueuedInstallEntity(
            releaseName = "Game3",
            status = "QUEUED",
            progress = 0.0f,
            downloadedBytes = null,
            totalBytes = null,
            queuePosition = 1,
            createdAt = BASE_TIMESTAMP,
            lastUpdatedAt = BASE_TIMESTAMP
        )

        dao.insert(entity1)
        dao.insert(entity2)
        dao.insert(entity3)

        val result = dao.getAllFlow().first()
        assertEquals(3, result.size)
        assertEquals("Game2", result[0].releaseName) // queuePosition 0
        assertEquals("Game3", result[1].releaseName) // queuePosition 1
        assertEquals("Game1", result[2].releaseName) // queuePosition 2
    }

    @Test
    fun insertWithConflict_replacesExisting() = runTest {
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
        dao.insert(entity1)

        val entity2 = QueuedInstallEntity(
            releaseName = "TestGame",
            status = "DOWNLOADING",
            progress = 0.5f,
            downloadedBytes = HALF_MEGABYTE,
            totalBytes = ONE_MEGABYTE,
            queuePosition = 0,
            createdAt = LATER_TIMESTAMP,
            lastUpdatedAt = LATER_TIMESTAMP
        )
        dao.insert(entity2)

        val result = dao.getAllFlow().first()
        assertEquals(1, result.size)
        assertEquals("DOWNLOADING", result[0].status)
        assertEquals(0.5f, result[0].progress)
    }

    @Test
    fun updateProgressOnly_modifiesOnlyProgress() = runTest {
        val entity = QueuedInstallEntity(
            releaseName = "TestGame",
            status = "DOWNLOADING",
            progress = 0.0f,
            downloadedBytes = HALF_MEGABYTE,
            totalBytes = ONE_MEGABYTE,
            queuePosition = 0,
            createdAt = BASE_TIMESTAMP,
            lastUpdatedAt = BASE_TIMESTAMP
        )
        dao.insert(entity)

        val rowsAffected = dao.updateProgressOnly("TestGame", 0.75f, UPDATE_TIMESTAMP)
        assertEquals(1, rowsAffected)

        val updated = dao.getByReleaseName("TestGame")
        assertNotNull(updated)
        assertEquals(0.75f, updated?.progress)
        // downloadedBytes should remain unchanged
        assertEquals(HALF_MEGABYTE, updated?.downloadedBytes)
        assertEquals(UPDATE_TIMESTAMP, updated?.lastUpdatedAt)
    }

    @Test
    fun updateQueuePosition_modifiesCorrectField() = runTest {
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
        dao.insert(entity)

        val rowsAffected = dao.updateQueuePosition("TestGame", 5, UPDATE_TIMESTAMP)
        assertEquals(1, rowsAffected)

        val updated = dao.getByReleaseName("TestGame")
        assertNotNull(updated)
        assertEquals(5, updated?.queuePosition)
        assertEquals(UPDATE_TIMESTAMP, updated?.lastUpdatedAt)
    }

    @Test
    fun deleteByReleaseName_removesCorrectEntity() = runTest {
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
        dao.insert(entity1)
        dao.insert(entity2)

        val rowsAffected = dao.deleteByReleaseName("Game1")
        assertEquals(1, rowsAffected)

        val result = dao.getAllFlow().first()
        assertEquals(1, result.size)
        assertEquals("Game2", result[0].releaseName)
    }

    @Test
    fun deleteAll_removesAllEntities() = runTest {
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
        dao.insert(entity1)
        dao.insert(entity2)

        val rowsAffected = dao.deleteAll()
        assertEquals(2, rowsAffected)

        val result = dao.getAllFlow().first()
        assertEquals(0, result.size)
    }

    @Test
    fun insertAll_insertsMultipleEntities() = runTest {
        val entities = listOf(
            QueuedInstallEntity(
                releaseName = "Game1",
                status = "QUEUED",
                progress = 0.0f,
                downloadedBytes = null,
                totalBytes = null,
                queuePosition = 0,
                createdAt = BASE_TIMESTAMP,
                lastUpdatedAt = BASE_TIMESTAMP
            ),
            QueuedInstallEntity(
                releaseName = "Game2",
                status = "QUEUED",
                progress = 0.0f,
                downloadedBytes = null,
                totalBytes = null,
                queuePosition = 1,
                createdAt = BASE_TIMESTAMP,
                lastUpdatedAt = BASE_TIMESTAMP
            ),
            QueuedInstallEntity(
                releaseName = "Game3",
                status = "QUEUED",
                progress = 0.0f,
                downloadedBytes = null,
                totalBytes = null,
                queuePosition = 2,
                createdAt = BASE_TIMESTAMP,
                lastUpdatedAt = BASE_TIMESTAMP
            )
        )

        dao.insertAll(entities)

        val result = dao.getAllFlow().first()
        assertEquals(3, result.size)
    }

    @Test
    fun getAll_returnsListNotFlow() = runTest {
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
        dao.insert(entity)

        val result = dao.getAll()
        assertEquals(1, result.size)
        assertEquals("TestGame", result[0].releaseName)
    }
}
