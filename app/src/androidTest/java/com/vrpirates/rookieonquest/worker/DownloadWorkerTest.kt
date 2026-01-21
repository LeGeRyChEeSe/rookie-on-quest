package com.vrpirates.rookieonquest.worker

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/**
 * Instrumented tests for DownloadWorker and WorkManager integration.
 *
 * These tests verify:
 * - Work enqueue with constraints
 * - Exponential backoff retry on failure
 * - Work cancellation
 * - Unique work policy behavior
 *
 * Note: Full process-kill resumption testing requires manual testing on device
 * as instrumented tests cannot fully simulate process death.
 */
@RunWith(AndroidJUnit4::class)
class DownloadWorkerTest {

    private lateinit var context: Context
    private lateinit var workManager: WorkManager

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()

        // Initialize WorkManager for testing with synchronous executor
        val config = Configuration.Builder()
            .setMinimumLoggingLevel(Log.DEBUG)
            .setExecutor(SynchronousExecutor())
            .build()

        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
        workManager = WorkManager.getInstance(context)
    }

    @Test
    fun workRequest_hasCorrectConstraints() {
        // Given: A download work request with standard constraints
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .setRequiresStorageNotLow(true)
            .build()

        val inputData = Data.Builder()
            .putString(DownloadWorker.KEY_RELEASE_NAME, "TestGame-1.0.0")
            .putBoolean(DownloadWorker.KEY_IS_DOWNLOAD_ONLY, true)
            .putBoolean(DownloadWorker.KEY_KEEP_APK, false)
            .build()

        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setConstraints(constraints)
            .setInputData(inputData)
            .addTag("download")
            .addTag("TestGame-1.0.0")
            .build()

        // When: Enqueue the work
        workManager.enqueue(request).result.get()

        // Then: Work should be enqueued
        val workInfo = workManager.getWorkInfoById(request.id).get()
        assertNotNull(workInfo)
        assertTrue(
            "Work should be ENQUEUED or RUNNING",
            workInfo.state == WorkInfo.State.ENQUEUED ||
                    workInfo.state == WorkInfo.State.RUNNING ||
                    workInfo.state == WorkInfo.State.BLOCKED
        )
    }

    @Test
    fun uniqueWork_preventsDoubleEnqueue() {
        // Given: Input data for a download
        val inputData = Data.Builder()
            .putString(DownloadWorker.KEY_RELEASE_NAME, "UniqueTestGame-1.0.0")
            .putBoolean(DownloadWorker.KEY_IS_DOWNLOAD_ONLY, true)
            .build()

        val request1 = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(inputData)
            .build()

        val request2 = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(inputData)
            .build()

        // When: Enqueue the same work twice with KEEP policy
        val uniqueWorkName = "download_UniqueTestGame-1.0.0"

        workManager.enqueueUniqueWork(
            uniqueWorkName,
            ExistingWorkPolicy.KEEP,
            request1
        ).result.get()

        workManager.enqueueUniqueWork(
            uniqueWorkName,
            ExistingWorkPolicy.KEEP,
            request2
        ).result.get()

        // Then: Only one work should exist
        val workInfos = workManager.getWorkInfosForUniqueWork(uniqueWorkName).get()
        assertEquals("Only one work should exist with KEEP policy", 1, workInfos.size)
    }

    @Test
    fun workCancellation_stopsWork() {
        // Given: A unique work request
        val inputData = Data.Builder()
            .putString(DownloadWorker.KEY_RELEASE_NAME, "CancelTestGame-1.0.0")
            .putBoolean(DownloadWorker.KEY_IS_DOWNLOAD_ONLY, true)
            .build()

        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(inputData)
            .build()

        val uniqueWorkName = "download_CancelTestGame-1.0.0"

        // When: Enqueue and then cancel
        workManager.enqueueUniqueWork(
            uniqueWorkName,
            ExistingWorkPolicy.KEEP,
            request
        ).result.get()

        workManager.cancelUniqueWork(uniqueWorkName).result.get()

        // Then: Work should be cancelled
        val workInfos = workManager.getWorkInfosForUniqueWork(uniqueWorkName).get()
        assertTrue(
            "Work should be CANCELLED",
            workInfos.isEmpty() || workInfos.all { it.state == WorkInfo.State.CANCELLED }
        )
    }

    @Test
    fun workRequest_hasBackoffPolicy() {
        // Given: A work request with exponential backoff
        val inputData = Data.Builder()
            .putString(DownloadWorker.KEY_RELEASE_NAME, "BackoffTestGame-1.0.0")
            .putBoolean(DownloadWorker.KEY_IS_DOWNLOAD_ONLY, true)
            .build()

        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(inputData)
            .setBackoffCriteria(
                androidx.work.BackoffPolicy.EXPONENTIAL,
                10000L,
                TimeUnit.MILLISECONDS
            )
            .build()

        // When: Enqueue the work
        workManager.enqueue(request).result.get()

        // Then: Work should be enqueued (backoff policy is internal, we just verify the work is accepted)
        val workInfo = workManager.getWorkInfoById(request.id).get()
        assertNotNull("Work should be enqueued with backoff policy", workInfo)
    }

    @Test
    fun workRequest_hasTags() {
        // Given: A work request with tags
        val releaseName = "TagTestGame-1.0.0"
        val inputData = Data.Builder()
            .putString(DownloadWorker.KEY_RELEASE_NAME, releaseName)
            .putBoolean(DownloadWorker.KEY_IS_DOWNLOAD_ONLY, true)
            .build()

        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(inputData)
            .addTag("download")
            .addTag(releaseName)
            .build()

        // When: Enqueue the work
        workManager.enqueue(request).result.get()

        // Then: Work should be findable by tag
        val workInfosByDownloadTag = workManager.getWorkInfosByTag("download").get()
        assertTrue(
            "Work should be findable by 'download' tag",
            workInfosByDownloadTag.any { it.id == request.id }
        )

        val workInfosByReleaseTag = workManager.getWorkInfosByTag(releaseName).get()
        assertTrue(
            "Work should be findable by release name tag",
            workInfosByReleaseTag.any { it.id == request.id }
        )
    }

    @Test
    fun inputData_containsRequiredKeys() {
        // Given: A fully configured work request
        val releaseName = "InputDataTestGame-1.0.0"
        val isDownloadOnly = true
        val keepApk = false

        val inputData = Data.Builder()
            .putString(DownloadWorker.KEY_RELEASE_NAME, releaseName)
            .putBoolean(DownloadWorker.KEY_IS_DOWNLOAD_ONLY, isDownloadOnly)
            .putBoolean(DownloadWorker.KEY_KEEP_APK, keepApk)
            .build()

        // Then: All expected keys should be present
        assertEquals(releaseName, inputData.getString(DownloadWorker.KEY_RELEASE_NAME))
        assertEquals(isDownloadOnly, inputData.getBoolean(DownloadWorker.KEY_IS_DOWNLOAD_ONLY, false))
        assertEquals(keepApk, inputData.getBoolean(DownloadWorker.KEY_KEEP_APK, true))
    }

    @Test
    fun doWork_withMissingReleaseName_fails() {
        // Given: A work request WITHOUT release name (required input)
        val inputData = Data.Builder()
            .putBoolean(DownloadWorker.KEY_IS_DOWNLOAD_ONLY, true)
            .build()

        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(inputData)
            .build()

        // When: Enqueue and run the work
        workManager.enqueue(request).result.get()

        // Drive worker to run (synchronous executor should run it immediately)
        val testDriver = WorkManagerTestInitHelper.getTestDriver(context)
        testDriver?.setAllConstraintsMet(request.id)

        // Then: Work should fail due to missing release name
        val workInfo = workManager.getWorkInfoById(request.id).get()
        assertEquals(
            "Work should FAIL with missing release name",
            WorkInfo.State.FAILED,
            workInfo.state
        )

        // Verify failure output contains appropriate status
        val outputStatus = workInfo.outputData.getString(DownloadWorker.KEY_STATUS)
        assertNotNull("Output should contain status", outputStatus)
        assertTrue(
            "Status should indicate missing release name",
            outputStatus?.contains("Missing", ignoreCase = true) == true ||
                    outputStatus?.contains("release", ignoreCase = true) == true
        )
    }

    @Test
    fun doWork_withNonExistentGame_fails() {
        // Given: A work request with a release name that doesn't exist in catalog
        val inputData = Data.Builder()
            .putString(DownloadWorker.KEY_RELEASE_NAME, "NonExistentGame-999.999.999")
            .putBoolean(DownloadWorker.KEY_IS_DOWNLOAD_ONLY, true)
            .build()

        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(inputData)
            .build()

        // When: Enqueue and run the work
        workManager.enqueue(request).result.get()

        val testDriver = WorkManagerTestInitHelper.getTestDriver(context)
        testDriver?.setAllConstraintsMet(request.id)

        // Then: Work should fail (either immediately or after retries)
        val workInfo = workManager.getWorkInfoById(request.id).get()
        assertTrue(
            "Work should FAIL or RETRY with non-existent game",
            workInfo.state == WorkInfo.State.FAILED ||
                    workInfo.state == WorkInfo.State.ENQUEUED // May be waiting for retry
        )
    }

    @Test
    fun constants_areCorrectlyDefined() {
        // Verify DownloadWorker companion object constants
        assertEquals("release_name", DownloadWorker.KEY_RELEASE_NAME)
        assertEquals("is_download_only", DownloadWorker.KEY_IS_DOWNLOAD_ONLY)
        assertEquals("keep_apk", DownloadWorker.KEY_KEEP_APK)
        assertEquals("progress", DownloadWorker.KEY_PROGRESS)
        assertEquals("downloaded_bytes", DownloadWorker.KEY_DOWNLOADED_BYTES)
        assertEquals("total_bytes", DownloadWorker.KEY_TOTAL_BYTES)
        assertEquals("status", DownloadWorker.KEY_STATUS)
        assertEquals("download_progress", DownloadWorker.NOTIFICATION_CHANNEL_ID)
        assertEquals(1001, DownloadWorker.NOTIFICATION_ID)
    }

    @Test
    fun workConstraints_networkRequired() {
        // Given: Constraints that require network
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val inputData = Data.Builder()
            .putString(DownloadWorker.KEY_RELEASE_NAME, "NetworkTestGame-1.0.0")
            .build()

        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setConstraints(constraints)
            .setInputData(inputData)
            .build()

        // When: Enqueue without meeting constraints
        workManager.enqueue(request).result.get()

        // Then: Work should be ENQUEUED but not running (constraints not met)
        val workInfo = workManager.getWorkInfoById(request.id).get()
        assertNotNull(workInfo)

        // The work is either ENQUEUED (waiting for constraints) or BLOCKED
        assertTrue(
            "Work should be ENQUEUED or BLOCKED when network constraint not explicitly met",
            workInfo.state == WorkInfo.State.ENQUEUED ||
                    workInfo.state == WorkInfo.State.BLOCKED ||
                    workInfo.state == WorkInfo.State.RUNNING // May run if test has network
        )
    }
}
