package com.vrpirates.rookieonquest.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Instrumented tests for validating that staged APK files are uniquely identified by package name
 * using the real MainRepository implementation.
 *
 * Related Story: 1.11 - Fix Staged APK Cross-Contamination
 */
@RunWith(AndroidJUnit4::class)
class StagedApkCrossContaminationTest {

    private lateinit var context: Context
    private lateinit var repository: MainRepository
    private lateinit var externalFilesDir: File

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext<Context>()
        repository = MainRepository(context)
        externalFilesDir = context.getExternalFilesDir(null)!!
        
        // Ensure starting with clean state
        repository.cleanupStagedApks()
    }

    @Test
    fun testApkNamingConvention_UsesPackageName() {
        val packageName = "com.example.game"
        val expectedFileName = "$packageName.apk"
        
        val actualFileName = repository.getStagedApkFileName(packageName)
        assertEquals("APK should use packageName.apk naming convention", expectedFileName, actualFileName)
    }

    @Test
    fun testFindApkByPackageName_FindsCorrectApk() {
        // Setup: Create multiple APK files with different package names
        // Note: We use the app's own APK to pass integrity checks in getValidStagedApk
        val packageName1 = "com.game.one"
        val appApk = File(context.packageCodePath)
        
        val stagedFile1 = repository.getStagedApkFile(packageName1)!!
        appApk.copyTo(stagedFile1, overwrite = true)

        // Use repository to find it
        val foundApk = repository.getStagedApkFile(packageName1)

        assertNotNull("Should find APK for package $packageName1", foundApk)
        assertTrue("Found APK should exist", foundApk?.exists() == true)
        assertEquals("Should find correct APK file", repository.getStagedApkFileName(packageName1), foundApk?.name)
    }

    @Test
    fun testGetValidStagedApk_ValidatesIntegrity() {
        val packageName = context.packageName // Use our own package name so it passes internal check
        val appApk = File(context.packageCodePath)
        
        val stagedFile = repository.getStagedApkFile(packageName)!!
        appApk.copyTo(stagedFile, overwrite = true)

        // Should be valid
        val validApk = repository.getValidStagedApk(packageName)
        assertNotNull("Valid APK should be returned", validApk)
        
        // Now corrupt it
        stagedFile.writeText("corrupted")
        val invalidApk = repository.getValidStagedApk(packageName)
        assertNull("Corrupted APK should be rejected", invalidApk)
    }

    @Test
    fun testCleanupStagedApks_RemovesAll() {
        // Setup: Create multiple APK files
        val pkg1 = "com.game.one"
        val pkg2 = "com.game.two"
        
        repository.getStagedApkFile(pkg1)!!.writeText("content1")
        repository.getStagedApkFile(pkg2)!!.writeText("content2")

        assertTrue("Files should exist before cleanup", repository.getStagedApkFile(pkg1)!!.exists())
        
        val deletedCount = repository.cleanupStagedApks()
        assertEquals("Should report 2 files deleted", 2, deletedCount)
        assertFalse("Files should not exist after cleanup", repository.getStagedApkFile(pkg1)!!.exists())
    }

    @Test
    fun testCleanupStagedApks_WithPreserve() {
        val pkg1 = "com.game.one"
        val pkg2 = "com.game.two"
        
        repository.getStagedApkFile(pkg1)!!.writeText("content1")
        repository.getStagedApkFile(pkg2)!!.writeText("content2")

        repository.cleanupStagedApks(preservePackageName = pkg1)
        
        assertTrue("pkg1 should be preserved", repository.getStagedApkFile(pkg1)!!.exists())
        assertFalse("pkg2 should be deleted", repository.getStagedApkFile(pkg2)!!.exists())
    }

    @Test
    fun testNewNamingPreventsCrossContamination() {
        val packageName1 = "com.vrpirates.game1"
        val packageName2 = "com.vrpirates.game2"

        // First installation stages its APK
        val stagedFile1 = repository.getStagedApkFile(packageName1)!!
        stagedFile1.writeText("game1 apk")

        // Second installation should find its own APK, not affected by pkg1
        val stagedFile2 = repository.getStagedApkFile(packageName2)!!
        stagedFile2.writeText("game2 apk")

        val foundApk = repository.getStagedApkFile(packageName2)
        assertNotNull("Should find game2's APK", foundApk)
        assertEquals("Should find correct APK for game2", repository.getStagedApkFileName(packageName2), foundApk?.name)
        
        // Verify pkg1 is still there (cross-contamination prevented by unique names)
        assertTrue(repository.getStagedApkFile(packageName1)!!.exists())
    }

    @Test
    fun testEndToEnd_CrossContaminationScenario() {
        // SCENARIO: 
        // 1. Task A starts, stages APK A, but fails before installation
        // 2. Task B starts, should clean up previous state but NOT use APK A
        
        val pkgA = "com.vrpirates.taskA"
        val pkgB = "com.vrpirates.taskB"
        val appApk = File(context.packageCodePath)
        
        // 1. Task A stages its APK
        val stagedA = repository.getStagedApkFile(pkgA)!!
        appApk.copyTo(stagedA, overwrite = true)
        
        // 2. Task B starts. It should cleanup staged APKs before staging its own
        repository.cleanupStagedApks()
        assertFalse("APK A should be cleaned up", stagedA.exists())
        
        // 3. Task B stages its own APK (which might be the same app for testing purposes)
        val stagedB = repository.getStagedApkFile(pkgB)!!
        appApk.copyTo(stagedB, overwrite = true)
        
        // 4. Verify Task B correctly retrieves ONLY its own APK
        val foundB = repository.getValidStagedApk(pkgB)
        assertNotNull("Task B should find its APK", foundB)
        assertEquals("Should match Task B's package", pkgB, foundB?.name?.removeSuffix(".apk"))
        
        // 5. Verify it definitely does NOT find APK A (even if it wasn't cleaned up)
        val foundA = repository.getValidStagedApk(pkgA)
        assertNull("Task B should NOT find Task A's APK", foundA)
    }
}
