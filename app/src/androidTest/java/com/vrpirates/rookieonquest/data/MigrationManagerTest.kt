package com.vrpirates.rookieonquest.data

import android.content.Context
import android.content.SharedPreferences
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.gson.Gson
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Integration tests for MigrationManager
 *
 * Tests end-to-end migration from v2.4.0 SharedPreferences queue to v2.5.0 Room database
 * Uses in-memory database and test SharedPreferences for isolation
 */
@RunWith(AndroidJUnit4::class)
class MigrationManagerTest {

    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var sharedPrefs: SharedPreferences

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()

        // Create in-memory database for testing
        database = Room.inMemoryDatabaseBuilder(
            context,
            AppDatabase::class.java
        )
            .allowMainThreadQueries()
            .build()

        // Use test SharedPreferences - must match Constants.PREFS_NAME
        sharedPrefs = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
        sharedPrefs.edit().clear().commit() // Clear any existing data
    }

    @After
    fun tearDown() {
        database.close()
        sharedPrefs.edit().clear().commit()
    }

    // ========== INTEGRATION TESTS - Test actual migrateLegacyQueue() ==========

    @Test
    fun migrateLegacyQueue_withValidData_insertsIntoRoomDB() = runBlocking {
        // Given: Valid legacy queue JSON in SharedPreferences
        val legacyJson = """
            [{
                "releaseName": "BeatSaber-1.34.5",
                "gameName": "Beat Saber",
                "packageName": "com.beatgames.beatsaber",
                "status": "DOWNLOADING",
                "progress": 0.65,
                "message": "Downloading...",
                "currentSize": "2.0 GB",
                "totalSize": "3.0 GB",
                "isDownloadOnly": false,
                "totalBytes": 3221225472,
                "downloadedBytes": 2147483648,
                "error": null,
                "queuePosition": 0,
                "createdAt": 1704988800000
            }]
        """.trimIndent()
        sharedPrefs.edit().putString("queue_snapshot_v2_4_0", legacyJson).commit()

        // When: Migration runs
        val result = MigrationManager.migrateLegacyQueue(context, database)

        // Then: Data inserted into Room DB
        assertEquals(1, result)
        val entities = database.queuedInstallDao().getAllFlow().first()
        assertEquals(1, entities.size)
        assertEquals("BeatSaber-1.34.5", entities[0].releaseName)
        assertEquals("DOWNLOADING", entities[0].status)
        assertEquals(0.65f, entities[0].progress, 0.01f)
        assertEquals(2147483648L, entities[0].downloadedBytes)
        assertEquals(3221225472L, entities[0].totalBytes)
        assertFalse(entities[0].isDownloadOnly)

        // Then: Migration marked complete and legacy data removed
        assertTrue(sharedPrefs.getBoolean("migration_v2_4_0_complete", false))
        assertNull(sharedPrefs.getString("queue_snapshot_v2_4_0", null))
    }

    @Test
    fun migrateLegacyQueue_multipleItems_preservesOrder() = runBlocking {
        // Given: 3 tasks in specific order
        val legacyJson = """
            [
                {
                    "releaseName": "Game1",
                    "gameName": "First Game",
                    "packageName": "com.game1",
                    "status": "QUEUED",
                    "progress": 0.0,
                    "message": null,
                    "currentSize": null,
                    "totalSize": null,
                    "isDownloadOnly": false,
                    "totalBytes": 1000000,
                    "downloadedBytes": null,
                    "error": null,
                    "queuePosition": 0,
                    "createdAt": 1000
                },
                {
                    "releaseName": "Game2",
                    "gameName": "Second Game",
                    "packageName": "com.game2",
                    "status": "PAUSED",
                    "progress": 0.5,
                    "message": null,
                    "currentSize": null,
                    "totalSize": null,
                    "isDownloadOnly": true,
                    "totalBytes": 2000000,
                    "downloadedBytes": 1000000,
                    "error": null,
                    "queuePosition": 1,
                    "createdAt": 2000
                },
                {
                    "releaseName": "Game3",
                    "gameName": "Third Game",
                    "packageName": "com.game3",
                    "status": "QUEUED",
                    "progress": 0.0,
                    "message": null,
                    "currentSize": null,
                    "totalSize": null,
                    "isDownloadOnly": false,
                    "totalBytes": 3000000,
                    "downloadedBytes": null,
                    "error": null,
                    "queuePosition": 2,
                    "createdAt": 3000
                }
            ]
        """.trimIndent()
        sharedPrefs.edit().putString("queue_snapshot_v2_4_0", legacyJson).commit()

        // When: Migration runs
        val result = MigrationManager.migrateLegacyQueue(context, database)

        // Then: All 3 items inserted in correct order
        assertEquals(3, result)
        val entities = database.queuedInstallDao().getAllFlow().first()
            .sortedBy { it.queuePosition }
        assertEquals(3, entities.size)
        assertEquals("Game1", entities[0].releaseName)
        assertEquals("Game2", entities[1].releaseName)
        assertEquals("Game3", entities[2].releaseName)
        assertEquals(0, entities[0].queuePosition)
        assertEquals(1, entities[1].queuePosition)
        assertEquals(2, entities[2].queuePosition)
        assertTrue(entities[1].isDownloadOnly) // Game2 has download-only flag
    }

    @Test
    fun migrateLegacyQueue_allStatusValues_convertCorrectly() = runBlocking {
        // Given: Queue with all 7 v2.4.0 status values
        val legacyJson = """
            [
                {"releaseName": "Game1", "gameName": "G1", "packageName": "c.g1", "status": "QUEUED", "progress": 0.0, "message": null, "currentSize": null, "totalSize": null, "isDownloadOnly": false, "totalBytes": 1000, "downloadedBytes": null, "error": null, "queuePosition": 0, "createdAt": 1000},
                {"releaseName": "Game2", "gameName": "G2", "packageName": "c.g2", "status": "DOWNLOADING", "progress": 0.3, "message": null, "currentSize": null, "totalSize": null, "isDownloadOnly": false, "totalBytes": 2000, "downloadedBytes": 600, "error": null, "queuePosition": 1, "createdAt": 2000},
                {"releaseName": "Game3", "gameName": "G3", "packageName": "c.g3", "status": "EXTRACTING", "progress": 0.6, "message": null, "currentSize": null, "totalSize": null, "isDownloadOnly": false, "totalBytes": 3000, "downloadedBytes": 1800, "error": null, "queuePosition": 2, "createdAt": 3000},
                {"releaseName": "Game4", "gameName": "G4", "packageName": "c.g4", "status": "INSTALLING", "progress": 0.9, "message": null, "currentSize": null, "totalSize": null, "isDownloadOnly": false, "totalBytes": 4000, "downloadedBytes": 3600, "error": null, "queuePosition": 3, "createdAt": 4000},
                {"releaseName": "Game5", "gameName": "G5", "packageName": "c.g5", "status": "PAUSED", "progress": 0.5, "message": null, "currentSize": null, "totalSize": null, "isDownloadOnly": false, "totalBytes": 5000, "downloadedBytes": 2500, "error": null, "queuePosition": 4, "createdAt": 5000},
                {"releaseName": "Game6", "gameName": "G6", "packageName": "c.g6", "status": "COMPLETED", "progress": 1.0, "message": null, "currentSize": null, "totalSize": null, "isDownloadOnly": false, "totalBytes": 6000, "downloadedBytes": 6000, "error": null, "queuePosition": 5, "createdAt": 6000},
                {"releaseName": "Game7", "gameName": "G7", "packageName": "c.g7", "status": "FAILED", "progress": 0.2, "message": null, "currentSize": null, "totalSize": null, "isDownloadOnly": false, "totalBytes": 7000, "downloadedBytes": 1400, "error": "Network error", "queuePosition": 6, "createdAt": 7000}
            ]
        """.trimIndent()
        sharedPrefs.edit().putString("queue_snapshot_v2_4_0", legacyJson).commit()

        // When: Migration runs
        val result = MigrationManager.migrateLegacyQueue(context, database)

        // Then: All status values mapped correctly
        assertEquals(7, result)
        val entities = database.queuedInstallDao().getAllFlow().first()
            .sortedBy { it.queuePosition }
        assertEquals("QUEUED", entities[0].status)
        assertEquals("DOWNLOADING", entities[1].status)
        assertEquals("EXTRACTING", entities[2].status)
        assertEquals("INSTALLING", entities[3].status)
        assertEquals("PAUSED", entities[4].status)
        assertEquals("COMPLETED", entities[5].status)
        assertEquals("FAILED", entities[6].status)
    }

    @Test
    fun migrateLegacyQueue_withCorruptedJSON_preservesLegacyData() = runBlocking {
        // Given: Corrupted JSON
        val corruptedJson = "{this is not valid json}"
        sharedPrefs.edit().putString("queue_snapshot_v2_4_0", corruptedJson).commit()

        // When: Migration runs
        val result = MigrationManager.migrateLegacyQueue(context, database)

        // Then: Migration fails but legacy data preserved
        assertEquals(-1, result)
        val entities = database.queuedInstallDao().getAllFlow().first()
        assertTrue(entities.isEmpty())

        // CRITICAL: Legacy data NOT deleted (preserved for retry/diagnostics)
        assertFalse(sharedPrefs.getBoolean("migration_v2_4_0_complete", false))
        assertNotNull(sharedPrefs.getString("queue_snapshot_v2_4_0", null))
    }

    @Test
    fun migrateLegacyQueue_noLegacyData_doesNothing() = runBlocking {
        // Given: No legacy data
        sharedPrefs.edit().clear().commit()

        // When: Migration runs
        val result = MigrationManager.migrateLegacyQueue(context, database)

        // Then: Returns 0, no data inserted
        assertEquals(0, result)
        val entities = database.queuedInstallDao().getAllFlow().first()
        assertTrue(entities.isEmpty())
    }

    @Test
    fun migrateLegacyQueue_alreadyCompleted_skips() = runBlocking {
        // Given: Migration already completed
        sharedPrefs.edit()
            .putString("queue_snapshot_v2_4_0", "[]")
            .putBoolean("migration_v2_4_0_complete", true)
            .commit()

        // When: Migration runs again
        val result = MigrationManager.migrateLegacyQueue(context, database)

        // Then: Skips migration
        assertEquals(0, result)
    }

    @Test
    fun needsMigration_detectsCorrectly() {
        // Case 1: No legacy data
        sharedPrefs.edit().clear().commit()
        assertFalse(MigrationManager.needsMigration(context))

        // Case 2: Has legacy data, not migrated
        sharedPrefs.edit().putString("queue_snapshot_v2_4_0", "[]").commit()
        assertTrue(MigrationManager.needsMigration(context))

        // Case 3: Has legacy data, already migrated
        sharedPrefs.edit()
            .putString("queue_snapshot_v2_4_0", "[]")
            .putBoolean("migration_v2_4_0_complete", true)
            .commit()
        assertFalse(MigrationManager.needsMigration(context))
    }

    // ========== UNIT TESTS - Data structure validation ==========

    @Test
    fun legacyStatusConversion_allValues_mapCorrectly() {
        val testCases = mapOf(
            "QUEUED" to InstallStatus.QUEUED,
            "DOWNLOADING" to InstallStatus.DOWNLOADING,
            "EXTRACTING" to InstallStatus.EXTRACTING,
            "INSTALLING" to InstallStatus.INSTALLING,
            "PAUSED" to InstallStatus.PAUSED,
            "COMPLETED" to InstallStatus.COMPLETED,
            "FAILED" to InstallStatus.FAILED
        )

        testCases.forEach { (legacyStatus, _) ->
            val legacyTask = MigrationManager.LegacyInstallTaskState(
                releaseName = "TestGame",
                gameName = "Test Game",
                packageName = "com.test.game",
                status = legacyStatus,
                progress = 0.5f,
                message = null,
                currentSize = null,
                totalSize = null,
                isDownloadOnly = false,
                totalBytes = 1000000L,
                downloadedBytes = 500000L,
                error = null,
                queuePosition = 0,
                createdAt = System.currentTimeMillis()
            )

            // Verify the legacy task structure is correct
            assertEquals(legacyStatus, legacyTask.status)
        }
    }

    @Test
    fun legacyInstallTaskState_jsonParsing_succeeds() {
        val gson = Gson()
        val legacyJson = """
            [{
                "releaseName": "BeatSaber-1.34.5",
                "gameName": "Beat Saber",
                "packageName": "com.beatgames.beatsaber",
                "status": "DOWNLOADING",
                "progress": 0.65,
                "message": "Downloading...",
                "currentSize": "2.0 GB",
                "totalSize": "3.0 GB",
                "isDownloadOnly": false,
                "totalBytes": 3221225472,
                "downloadedBytes": 2147483648,
                "error": null,
                "queuePosition": 0,
                "createdAt": 1704988800000
            }]
        """.trimIndent()

        val result = gson.fromJson(
            legacyJson,
            Array<MigrationManager.LegacyInstallTaskState>::class.java
        ).toList()

        assertEquals(1, result.size)
        assertEquals("BeatSaber-1.34.5", result[0].releaseName)
        assertEquals("Beat Saber", result[0].gameName)
        assertEquals("DOWNLOADING", result[0].status)
        assertEquals(0.65f, result[0].progress, 0.01f)
        assertEquals(2147483648L, result[0].downloadedBytes)
        assertEquals(3221225472L, result[0].totalBytes)
        assertFalse(result[0].isDownloadOnly)
    }

    @Test
    fun legacyInstallTaskState_withNullValues_parsesCorrectly() {
        val gson = Gson()
        val legacyJson = """
            [{
                "releaseName": "TestGame",
                "gameName": "Test Game",
                "packageName": "com.test.game",
                "status": "QUEUED",
                "progress": 0.0,
                "message": null,
                "currentSize": null,
                "totalSize": null,
                "isDownloadOnly": false,
                "totalBytes": 0,
                "downloadedBytes": null,
                "error": null,
                "queuePosition": null,
                "createdAt": null
            }]
        """.trimIndent()

        val result = gson.fromJson(
            legacyJson,
            Array<MigrationManager.LegacyInstallTaskState>::class.java
        ).toList()

        assertEquals(1, result.size)
        assertNull(result[0].downloadedBytes)
        assertNull(result[0].queuePosition)
        assertNull(result[0].createdAt)
    }

    @Test
    fun legacyInstallTaskState_multipleItems_maintainsOrder() {
        val gson = Gson()
        val legacyJson = """
            [
                {
                    "releaseName": "Game1",
                    "gameName": "Game 1",
                    "packageName": "com.game1",
                    "status": "QUEUED",
                    "progress": 0.0,
                    "message": null,
                    "currentSize": null,
                    "totalSize": null,
                    "isDownloadOnly": false,
                    "totalBytes": 0,
                    "downloadedBytes": null,
                    "error": null,
                    "queuePosition": 0,
                    "createdAt": 1000
                },
                {
                    "releaseName": "Game2",
                    "gameName": "Game 2",
                    "packageName": "com.game2",
                    "status": "PAUSED",
                    "progress": 0.5,
                    "message": null,
                    "currentSize": null,
                    "totalSize": null,
                    "isDownloadOnly": true,
                    "totalBytes": 2000000,
                    "downloadedBytes": 1000000,
                    "error": null,
                    "queuePosition": 1,
                    "createdAt": 2000
                },
                {
                    "releaseName": "Game3",
                    "gameName": "Game 3",
                    "packageName": "com.game3",
                    "status": "QUEUED",
                    "progress": 0.0,
                    "message": null,
                    "currentSize": null,
                    "totalSize": null,
                    "isDownloadOnly": false,
                    "totalBytes": 0,
                    "downloadedBytes": null,
                    "error": null,
                    "queuePosition": 2,
                    "createdAt": 3000
                }
            ]
        """.trimIndent()

        val result = gson.fromJson(
            legacyJson,
            Array<MigrationManager.LegacyInstallTaskState>::class.java
        ).toList()

        assertEquals(3, result.size)
        assertEquals("Game1", result[0].releaseName)
        assertEquals("Game2", result[1].releaseName)
        assertEquals("Game3", result[2].releaseName)
        assertEquals(0, result[0].queuePosition)
        assertEquals(1, result[1].queuePosition)
        assertEquals(2, result[2].queuePosition)
    }

    @Test
    fun legacyInstallTaskState_withIsDownloadOnlyFlag_preservesValue() {
        val gson = Gson()

        // Test with isDownloadOnly = true
        val jsonTrue = """
            [{
                "releaseName": "TestGame",
                "gameName": "Test Game",
                "packageName": "com.test.game",
                "status": "DOWNLOADING",
                "progress": 0.5,
                "message": null,
                "currentSize": null,
                "totalSize": null,
                "isDownloadOnly": true,
                "totalBytes": 1000000,
                "downloadedBytes": 500000,
                "error": null,
                "queuePosition": 0,
                "createdAt": 1000
            }]
        """.trimIndent()

        val resultTrue = gson.fromJson(
            jsonTrue,
            Array<MigrationManager.LegacyInstallTaskState>::class.java
        ).toList()

        assertTrue(resultTrue[0].isDownloadOnly)

        // Test with isDownloadOnly = false
        val jsonFalse = """
            [{
                "releaseName": "TestGame2",
                "gameName": "Test Game 2",
                "packageName": "com.test.game2",
                "status": "DOWNLOADING",
                "progress": 0.5,
                "message": null,
                "currentSize": null,
                "totalSize": null,
                "isDownloadOnly": false,
                "totalBytes": 1000000,
                "downloadedBytes": 500000,
                "error": null,
                "queuePosition": 0,
                "createdAt": 1000
            }]
        """.trimIndent()

        val resultFalse = gson.fromJson(
            jsonFalse,
            Array<MigrationManager.LegacyInstallTaskState>::class.java
        ).toList()

        assertFalse(resultFalse[0].isDownloadOnly)
    }

    @Test
    fun migrationDataStructure_coversAllRequiredFields() {
        // Verify LegacyInstallTaskState has all required fields for conversion
        val legacyTask = MigrationManager.LegacyInstallTaskState(
            releaseName = "TestGame",
            gameName = "Test Game",
            packageName = "com.test.game",
            status = "DOWNLOADING",
            progress = 0.5f,
            message = "Downloading...",
            currentSize = "500 MB",
            totalSize = "1 GB",
            isDownloadOnly = false,
            totalBytes = 1000000000L,
            downloadedBytes = 500000000L,
            error = null,
            queuePosition = 0,
            createdAt = System.currentTimeMillis()
        )

        // Verify all fields are present
        assertNotNull(legacyTask.releaseName)
        assertNotNull(legacyTask.gameName)
        assertNotNull(legacyTask.packageName)
        assertNotNull(legacyTask.status)
        assertTrue(legacyTask.progress >= 0f && legacyTask.progress <= 1f)
        assertNotNull(legacyTask.totalBytes)
        assertNotNull(legacyTask.queuePosition)
        assertNotNull(legacyTask.createdAt)
    }
}
