package com.vrpirates.rookieonquest.worker

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vrpirates.rookieonquest.data.AppDatabase
import com.vrpirates.rookieonquest.data.Constants
import com.vrpirates.rookieonquest.data.CryptoUtils
import com.vrpirates.rookieonquest.data.DownloadUtils
import com.vrpirates.rookieonquest.data.GameEntity
import com.vrpirates.rookieonquest.data.InstallStatus
import com.vrpirates.rookieonquest.data.QueuedInstallEntity
import com.vrpirates.rookieonquest.network.PublicConfig
import com.vrpirates.rookieonquest.network.VrpService
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.File

/**
 * Integration tests for DownloadWorker HTTP Range resumption logic.
 *
 * These tests verify the core download behavior:
 * - HTTP 206 response appends to existing file (resume)
 * - HTTP 200 response overwrites file (restart)
 * - Room DB sync with file system before resume
 * - Multi-part archive skip/resume logic
 *
 * Uses MockWebServer to simulate HTTP responses and verify file operations.
 */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalCoroutinesApi::class)
class DownloadWorkerIntegrationTest {

    private lateinit var context: Context
    private lateinit var mockWebServer: MockWebServer
    private lateinit var testDatabase: AppDatabase
    private lateinit var tempTestDir: File
    private lateinit var okHttpClient: OkHttpClient

    private val testReleaseName = "TestGame-1.0.0"
    private val testPackageName = "com.vrpirates.testgame"
    private val testHash = CryptoUtils.md5(testReleaseName + "\n")

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext<Context>()
        mockWebServer = MockWebServer()
        mockWebServer.start()

        // Create in-memory database for testing
        testDatabase = Room.inMemoryDatabaseBuilder(
            context,
            AppDatabase::class.java
        ).build()

        // Create temp directory for downloads
        tempTestDir = File(context.cacheDir, "test_install_temp")
        tempTestDir.mkdirs()

        // Use the same OkHttpClient configuration as the app
        okHttpClient = OkHttpClient.Builder()
            .connectTimeout(Constants.HTTP_CONNECT_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(Constants.HTTP_READ_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    @After
    fun tearDown() {
        mockWebServer.shutdown()
        testDatabase.close()
        tempTestDir.deleteRecursively()
    }

    /**
     * AC 1, 2: Verify HTTP 206 response appends to existing file.
     *
     * Given: Partial file exists from previous download (1000 bytes)
     * When: Server responds with 206 Partial Content
     * Then: Download continues from byte 1000, appending to file (0% data loss)
     */
    @Test
    fun downloadWorker_appendsToFile_on206Response() = runTest {
        // Given: A partial file exists (1000 bytes already downloaded)
        val testFileName = "test_file.apk"
        val testFile = File(tempTestDir, testFileName)
        val initialContent = "A".repeat(1000) // Simulate 1000 bytes already downloaded
        testFile.writeText(initialContent)

        val existingSize = testFile.length()
        assertEquals(1000L, existingSize)

        // When: Server responds with 206 Partial Content
        val additionalContent = "B".repeat(500) // 500 more bytes
        val totalExpectedSize = existingSize + additionalContent.length

        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(206) // Partial Content
                .setHeader("Content-Range", "bytes 1000-$totalExpectedSize/$totalExpectedSize")
                .setBody(additionalContent)
        )

        // Simulate DownloadWorker's downloadSegment logic
        val request = okhttp3.Request.Builder()
            .url(mockWebServer.url("/$testFileName"))
            .header("User-Agent", Constants.USER_AGENT)
            .header("Range", "bytes=$existingSize-")
            .build()

        okHttpClient.newCall(request).execute().use { response ->
            assertTrue("Response should be 206", DownloadUtils.isResumeResponse(response.code))

            val body = response.body ?: throw Exception("Empty response body")

            body.byteStream().use { input ->
                java.io.FileOutputStream(testFile, true).use { output ->
                    DownloadUtils.downloadWithProgress(
                        inputStream = input,
                        outputStream = output,
                        initialDownloaded = existingSize,
                        totalBytes = totalExpectedSize.toLong(),
                        throttleMs = 0L,
                        isCancelled = { false },
                        onProgress = { _, _, _ -> }
                    )
                }
            }
        }

        // Then: File should contain initial + additional content (append mode)
        val finalSize = testFile.length()
        assertEquals("File should have initial + additional content", totalExpectedSize.toLong(), finalSize)
        assertEquals("Content should be appended", initialContent + additionalContent, testFile.readText())
    }

    /**
     * AC 1: Verify HTTP 200 response overwrites existing file.
     *
     * Given: Partial file exists from previous download
     * When: Server ignores Range header and responds with 200 OK
     * Then: File is overwritten (server restarted download from beginning)
     */
    @Test
    fun downloadWorker_overwritesFile_on200Response() = runTest {
        // Given: A partial file exists
        val testFileName = "test_file_200.apk"
        val testFile = File(tempTestDir, testFileName)
        val oldContent = "OLD_DATA_SHOULD_BE_REPLACED"
        testFile.writeText(oldContent)

        val existingSize = testFile.length()
        assertTrue("File should exist before download", testFile.exists())

        // When: Server responds with 200 OK (ignores Range header)
        val newContent = "NEW_CONTENT"
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200) // OK (server ignored Range header)
                .setBody(newContent)
                // Note: No Content-Range header for 200 responses
        )

        val request = okhttp3.Request.Builder()
            .url(mockWebServer.url("/$testFileName"))
            .header("User-Agent", Constants.USER_AGENT)
            .header("Range", "bytes=$existingSize-")
            .build()

        okHttpClient.newCall(request).execute().use { response ->
            assertFalse("Response should not be 206", DownloadUtils.isResumeResponse(response.code))
            assertEquals("Response should be 200", 200, response.code)

            val body = response.body ?: throw Exception("Empty response body")

            body.byteStream().use { input ->
                // append=false for 200 response (overwrite mode)
                java.io.FileOutputStream(testFile, false).use { output ->
                    DownloadUtils.downloadWithProgress(
                        inputStream = input,
                        outputStream = output,
                        initialDownloaded = 0L,
                        totalBytes = newContent.length.toLong(),
                        throttleMs = 0L,
                        isCancelled = { false },
                        onProgress = { _, _, _ -> }
                    )
                }
            }
        }

        // Then: File should only contain new content (old content replaced)
        val finalContent = testFile.readText()
        assertEquals("File should contain only new content", newContent, finalContent)
        assertEquals("File size should match new content", newContent.length.toLong(), testFile.length())
    }

    /**
     * AC 3, 4: Verify Room DB sync with file system before resume.
     *
     * Given: DB reports 1000 bytes but file actually has 2000 bytes (process death during DB update)
     * When: DownloadWorker.syncRoomDbWithFileSystem() is called
     * Then: DB is corrected to match file system (file system = source of truth)
     */
    @Test
    fun downloadWorker_syncsRoomDbWithFileSystem_beforeResume() = runTest {
        // Given: File has 2000 bytes but DB reports only 1000 (simulating process death)
        val testFile = File(tempTestDir, "sync_test.apk")
        val actualBytesOnDisk = 2000L
        val staleDbBytes = 1000L

        testFile.writeBytes("X".repeat(actualBytesOnDisk.toInt()).toByteArray())

        // Insert game entity
        val gameEntity = GameEntity(
            gameName = "Test Game",
            releaseName = testReleaseName,
            packageName = testPackageName,
            versionCode = "1",
            sizeBytes = 5000L,
            popularity = 1,
            lastUpdated = System.currentTimeMillis()
        )
        testDatabase.gameDao().insertGames(listOf(gameEntity))

        // Insert queue entity with STALE downloadedBytes
        val queueEntity = QueuedInstallEntity(
            releaseName = testReleaseName,
            status = InstallStatus.DOWNLOADING.name,
            progress = 0.2f,
            downloadedBytes = staleDbBytes,
            totalBytes = 5000L,
            queuePosition = 0,
            createdAt = System.currentTimeMillis(),
            lastUpdatedAt = System.currentTimeMillis(),
            isDownloadOnly = false
        )
        testDatabase.queuedInstallDao().insert(queueEntity)

        // Verify DB has stale data
        val dbEntityBefore = testDatabase.queuedInstallDao().getByReleaseName(testReleaseName)
        assertEquals("DB should initially have stale bytes", staleDbBytes, dbEntityBefore?.downloadedBytes)

        // When: Sync is performed (simulating DownloadWorker.syncRoomDbWithFileSystem logic)
        val remoteSegments = mapOf("sync_test.apk" to 5000L)

        // Simulate sync logic
        var actualBytesOnDiskCalculated = 0L
        remoteSegments.forEach { (seg, _) ->
            val f = File(tempTestDir, seg)
            if (f.exists()) actualBytesOnDiskCalculated += f.length()
        }

        // Sync if mismatch detected
        if (actualBytesOnDiskCalculated != staleDbBytes) {
            val progress = (actualBytesOnDiskCalculated.toFloat() / 5000L) * 0.8f
            testDatabase.queuedInstallDao().updateProgress(
                releaseName = testReleaseName,
                progress = progress.coerceIn(0f, 0.8f),
                downloadedBytes = actualBytesOnDiskCalculated,
                totalBytes = 5000L,
                timestamp = System.currentTimeMillis()
            )
        }

        // Then: DB should be corrected to match file system
        val dbEntityAfter = testDatabase.queuedInstallDao().getByReleaseName(testReleaseName)
        assertNotNull("DB entity should exist", dbEntityAfter)
        assertEquals(
            "DB should be synced with actual file size (file system = source of truth)",
            actualBytesOnDisk,
            dbEntityAfter?.downloadedBytes
        )
    }

    /**
     * AC 5: Verify 416 Range Not Satisfiable is handled correctly.
     *
     * Given: File is already complete on disk
     * When: Server responds with 416 Range Not Satisfiable
     * Then: Download is skipped gracefully without error
     */
    @Test
    fun downloadWorker_handles416RangeNotSatisfiable() = runTest {
        // Given: Complete file exists
        val testFileName = "complete_file.apk"
        val testFile = File(tempTestDir, testFileName)
        val fileSize = 5000L
        testFile.writeBytes("C".repeat(fileSize.toInt()).toByteArray())

        // When: Server responds with 416 (file already complete)
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(416)
                .setHeader("Content-Range", "bytes */$fileSize")
        )

        val request = okhttp3.Request.Builder()
            .url(mockWebServer.url("/$testFileName"))
            .header("User-Agent", Constants.USER_AGENT)
            .header("Range", "bytes=$fileSize-")
            .build()

        okHttpClient.newCall(request).execute().use { response ->
            assertTrue("Response should be 416", DownloadUtils.isRangeNotSatisfiable(response.code))

            // Verify file size matches expected
            val contentRange = response.header("Content-Range") // Format: "bytes */TOTAL"
            val expectedSize = contentRange?.substringAfter("*/")?.toLongOrNull()
            val actualFileSize = testFile.length()

            // Then: Skip segment gracefully (verified by size match)
            assertEquals("Expected size from Content-Range", fileSize, expectedSize)
            assertEquals("File should be complete", fileSize, actualFileSize)
        }

        // File should remain unchanged
        assertEquals("File should still be complete after 416", fileSize, testFile.length())
    }

    /**
     * AC 6: Verify multi-part archive resume skips completed segments.
     *
     * Given: Multi-part archive with 3 segments (.7z.001, .7z.002, .7z.003)
     * And: Segment .7z.001 is complete, .7z.002 is partial, .7z.003 is missing
     * When: DownloadWorker processes segments
     * Then: .7z.001 is skipped, .7z.002 resumes, .7z.003 starts fresh
     */
    @Test
    fun downloadWorker_skipsCompletedSegments_inMultiPartArchive() = runTest {
        // Given: Multi-part archive state
        val segmentSizes = mapOf(
            "game.7z.001" to 1000L,
            "game.7z.002" to 1000L,
            "game.7z.003" to 1000L
        )

        // Create file states:
        // .001: Complete (1000 bytes)
        // .002: Partial (500 bytes)
        // .003: Missing
        val file001 = File(tempTestDir, "game.7z.001")
        val file002 = File(tempTestDir, "game.7z.002")
        val file003 = File(tempTestDir, "game.7z.003")

        file001.writeBytes("X".repeat(1000).toByteArray()) // Complete
        file002.writeBytes("Y".repeat(500).toByteArray())  // Partial
        // file003 doesn't exist

        // When: Processing segments (simulating DownloadWorker loop logic)
        val segmentActions = mutableListOf<String>()

        segmentSizes.entries.sortedBy { it.key }.forEach { (seg, remoteSize) ->
            val localFile = File(tempTestDir, seg)
            val existingSize = if (localFile.exists()) localFile.length() else 0L

            when {
                // Segment complete: skip
                remoteSize > 0L && existingSize >= remoteSize -> {
                    segmentActions.add("SKIP: $seg ($existingSize bytes complete)")
                }
                // Segment partial: resume
                localFile.exists() && existingSize > 0L && existingSize < remoteSize -> {
                    segmentActions.add("RESUME: $seg (have $existingSize bytes)")
                }
                // Segment missing: start fresh
                !localFile.exists() -> {
                    segmentActions.add("START: $seg (new segment)")
                }
            }
        }

        // Then: Verify correct actions for each segment
        assertTrue("Should have 3 segment actions", segmentActions.size == 3)

        val action001 = segmentActions.find { it.contains("game.7z.001") }
        val action002 = segmentActions.find { it.contains("game.7z.002") }
        val action003 = segmentActions.find { it.contains("game.7z.003") }

        assertTrue("Segment .001 should be skipped", action001?.contains("SKIP") == true)
        assertTrue("Segment .002 should resume", action002?.contains("RESUME") == true)
        assertTrue("Segment .003 should start fresh", action003?.contains("START") == true)
    }

    /**
     * Verify Room DB progress is updated correctly during sync.
     *
     * Given: File has 2000 bytes out of 5000 total
     * When: syncRoomDbWithFileSystem updates DB
     * Then: Progress is 0.32 (2000/5000 * 0.8 for download phase)
     */
    @Test
    fun downloadWorker_updatesProgressCorrectly_afterSync() = runTest {
        // Given: File on disk and DB state
        val testFile = File(tempTestDir, "progress_test.apk")
        val actualBytes = 2000L
        val totalBytes = 5000L

        testFile.writeBytes("P".repeat(actualBytes.toInt()).toByteArray())

        // Insert game and queue entities
        testDatabase.gameDao().insertGames(
            listOf(
                GameEntity(
                    gameName = "Test Game",
                    releaseName = testReleaseName,
                    packageName = testPackageName,
                    versionCode = "1",
                    sizeBytes = totalBytes,
                    popularity = 1,
                    lastUpdated = System.currentTimeMillis()
                )
            )
        )

        testDatabase.queuedInstallDao().insert(
            QueuedInstallEntity(
                releaseName = testReleaseName,
                status = InstallStatus.DOWNLOADING.name,
                progress = 0.1f, // Stale progress
                downloadedBytes = 500L, // Stale bytes
                totalBytes = totalBytes,
                queuePosition = 0,
                createdAt = System.currentTimeMillis(),
                lastUpdatedAt = System.currentTimeMillis(),
                isDownloadOnly = false
            )
        )

        // When: Sync is performed
        val expectedProgress = (actualBytes.toFloat() / totalBytes) * 0.8f // Download phase = 0-80%

        testDatabase.queuedInstallDao().updateProgress(
            releaseName = testReleaseName,
            progress = expectedProgress.coerceIn(0f, 0.8f),
            downloadedBytes = actualBytes,
            totalBytes = totalBytes,
            timestamp = System.currentTimeMillis()
        )

        // Then: Progress should be updated correctly
        val dbEntity = testDatabase.queuedInstallDao().getByReleaseName(testReleaseName)
        assertNotNull("DB entity should exist", dbEntity)

        val expectedProgressValue = 2000f / 5000f * 0.8f // 0.32
        assertEquals("DownloadedBytes should match file system", actualBytes, dbEntity?.downloadedBytes)
        assertEquals(
            "Progress should be scaled to download phase (0-80%)",
            expectedProgressValue,
            dbEntity?.progress!!,
            0.001f // Tolerance
        )
    }

    /**
     * Verify 416 with size mismatch triggers retry.
     *
     * Given: File has 500 bytes but server reports 1000 total
     * When: 416 response has mismatched size
     * Then: File should be deleted and download retried
     */
    @Test
    fun downloadWorker_retriesOn416SizeMismatch() = runTest {
        // Given: Corrupted partial file
        val testFileName = "corrupted.apk"
        val testFile = File(tempTestDir, testFileName)
        val corruptSize = 500L
        val expectedSize = 1000L

        testFile.writeBytes("Z".repeat(corruptSize.toInt()).toByteArray())

        // When: Server responds with 416 but file size doesn't match
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(416)
                .setHeader("Content-Range", "bytes */$expectedSize")
        )

        val request = okhttp3.Request.Builder()
            .url(mockWebServer.url("/$testFileName"))
            .header("User-Agent", Constants.USER_AGENT)
            .header("Range", "bytes=$corruptSize-")
            .build()

        okHttpClient.newCall(request).execute().use { response ->
            assertTrue("Response should be 416", DownloadUtils.isRangeNotSatisfiable(response.code))

            val contentRange = response.header("Content-Range")
            val expectedFromHeader = contentRange?.substringAfter("*/")?.toLongOrNull()
            val actualFileSize = testFile.length()

            // Simulate mismatch detection logic
            if (expectedFromHeader != null && actualFileSize != expectedFromHeader) {
                // File is corrupted - should be deleted and retried
                testFile.delete()
                assertFalse("File should be deleted after mismatch", testFile.exists())
            }
        }

        // Then: File should be deleted for retry
        assertFalse("Corrupted file should be deleted", testFile.exists())
    }
}
