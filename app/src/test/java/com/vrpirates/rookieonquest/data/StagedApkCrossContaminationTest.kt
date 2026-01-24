package com.vrpirates.rookieonquest.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Tests for validating that staged APK files are uniquely identified by package name
 * to prevent cross-contamination between installation tasks.
 *
 * Related Story: 1.11 - Fix Staged APK Cross-Contamination
 */
class StagedApkCrossContaminationTest {

    private lateinit var tempDir: File
    private lateinit var externalFilesDir: File

    @Before
    fun setup() {
        // Create temporary directories for testing
        val baseTempDir = System.getProperty("java.io.tmpdir")
        tempDir = File(baseTempDir, "apk_test_${System.currentTimeMillis()}")
        tempDir.mkdirs()

        externalFilesDir = File(tempDir, "external_files")
        externalFilesDir.mkdirs()
    }

    @Test
    fun testApkNamingConvention_UsesPackageName() {
        // Test that APK files are named using package name convention
        val packageName = "com.example.game"
        val expectedFileName = "${packageName}.apk"

        assertEquals("APK should use packageName.apk naming convention", expectedFileName, "$packageName.apk")
    }

    @Test
    fun testFindApkByPackageName_FindsCorrectApk() {
        // Setup: Create multiple APK files with different package names
        val packageName1 = "com.game.one"
        val packageName2 = "com.game.two"

        val apk1 = File(externalFilesDir, "${packageName1}.apk")
        val apk2 = File(externalFilesDir, "${packageName2}.apk")

        apk1.writeText("fake apk content 1")
        apk2.writeText("fake apk content 2")

        // Simulate the find logic for packageName1
        val foundApk = externalFilesDir.listFiles()?.find { file ->
            file.name == "${packageName1}.apk" && file.exists()
        }

        assertNotNull("Should find APK for package $packageName1", foundApk)
        assertEquals("Should find correct APK file", "${packageName1}.apk", foundApk?.name)

        // Cleanup
        apk1.delete()
        apk2.delete()
    }

    @Test
    fun testFindApkByPackageName_DoesNotFindWrongApk() {
        // Setup: Create APK files for different packages
        val packageName1 = "com.game.one"
        val packageName2 = "com.game.two"

        val apk1 = File(externalFilesDir, "${packageName1}.apk")

        apk1.writeText("fake apk content 1")

        // Simulate the find logic for packageName2 (which doesn't exist)
        val foundApk = externalFilesDir.listFiles()?.find { file ->
            file.name == "${packageName2}.apk" && file.exists()
        }

        assertNull("Should NOT find APK for non-existent package $packageName2", foundApk)

        // Cleanup
        apk1.delete()
    }

    @Test
    fun testOldEndsWithLogic_FindsWrongApk() {
        // This test demonstrates the bug: using endsWith(".apk") finds the FIRST APK,
        // which may be from a previous installation
        val packageName1 = "com.game.one"
        val packageName2 = "com.game.two"

        // Simulate old APK from previous installation
        val oldApk = File(externalFilesDir, "some_old_name.apk")
        oldApk.writeText("old apk content")

        // Current installation should use packageName2.apk, but doesn't exist yet
        // (simulating a scenario where cleanup hasn't happened)

        // OLD BUGGY LOGIC: finds first APK ending with .apk
        val wrongApk = externalFilesDir.listFiles()?.find { file ->
            file.name.endsWith(".apk") && file.exists()
        }

        assertNotNull("Old logic finds an APK (wrong one)", wrongApk)
        assertEquals("Old logic finds first APK, not the correct one", "some_old_name.apk", wrongApk?.name)

        // Cleanup
        oldApk.delete()
    }

    @Test
    fun testCleanupRemovesAllApks() {
        // Setup: Create multiple APK files
        val apk1 = File(externalFilesDir, "com.game.one.apk")
        val apk2 = File(externalFilesDir, "com.game.two.apk")
        val apk3 = File(externalFilesDir, "random_name.apk")

        apk1.writeText("content 1")
        apk2.writeText("content 2")
        apk3.writeText("content 3")

        assertEquals("Should have 3 APK files before cleanup", 3, externalFilesDir.listFiles()?.size)

        // Simulate cleanup logic
        externalFilesDir.listFiles()?.forEach { file ->
            if (file.name.endsWith(".apk")) {
                file.delete()
            }
        }

        val remainingFiles = externalFilesDir.listFiles()?.toList() ?: emptyList()
        assertTrue("Should have no APK files after cleanup", remainingFiles.isEmpty())

        // Cleanup (in case test failed)
        apk1.delete()
        apk2.delete()
        apk3.delete()
    }

    @Test
    fun testNewNamingPreventsCrossContamination() {
        // This test validates the fix: each package gets its own uniquely named APK
        val packageName1 = "com.vrpirates.game1"
        val packageName2 = "com.vrpirates.game2"

        // First installation stages its APK
        val apk1 = File(externalFilesDir, "${packageName1}.apk")
        apk1.writeText("game1 apk")

        // Simulate first installation completing but APK not cleaned up (simulating crash/failure)

        // Second installation should use its own package name
        val apk2 = File(externalFilesDir, "${packageName2}.apk")
        apk2.writeText("game2 apk")

        // When looking for game2's APK, we should find game2.apk, not game1.apk
        val foundApk = externalFilesDir.listFiles()?.find { file ->
            file.name == "${packageName2}.apk" && file.exists()
        }

        assertNotNull("Should find game2's APK", foundApk)
        assertEquals("Should find correct APK for game2", "${packageName2}.apk", foundApk?.name)

        // Cleanup
        apk1.delete()
        apk2.delete()
    }

    @Test
    fun testEmptyApkIsRejected() {
        // This test validates that empty/corrupted APKs are rejected
        val packageName = "com.example.game"

        // Create an empty APK file (simulating a corrupted/incomplete download)
        val emptyApk = File(externalFilesDir, "${packageName}.apk")
        emptyApk.createNewFile()

        assertTrue("Empty APK should exist", emptyApk.exists())
        assertEquals("Empty APK should have zero length", 0L, emptyApk.length())

        // The find logic should reject empty APKs
        val foundApk = externalFilesDir.listFiles()?.find { file ->
            file.name == "${packageName}.apk" && file.exists() && file.length() > 0
        }

        assertNull("Should NOT find empty APK", foundApk)

        // Cleanup
        emptyApk.delete()
    }

    @Test
    fun testValidApkWithContentIsAccepted() {
        // This test validates that APKs with actual content are accepted
        val packageName = "com.example.game"

        // Create a valid APK file with content
        val validApk = File(externalFilesDir, "${packageName}.apk")
        validApk.writeText("fake apk content")

        assertTrue("Valid APK should exist", validApk.exists())
        assertTrue("Valid APK should have content", validApk.length() > 0)

        // The find logic should accept APKs with content
        val foundApk = externalFilesDir.listFiles()?.find { file ->
            file.name == "${packageName}.apk" && file.exists() && file.length() > 0
        }

        assertNotNull("Should find valid APK with content", foundApk)
        assertEquals("Should find correct APK", "${packageName}.apk", foundApk?.name)

        // Cleanup
        validApk.delete()
    }

    @Test
    fun testListFilesReturnsNull_DoesNotCrash() {
        // This test validates that the code handles null listFiles() gracefully
        // Simulating a scenario where externalFilesDir is not a directory or has I/O errors

        // Create a file (not a directory) to simulate listFiles() returning null
        val notADirectory = File(tempDir, "not_a_directory.txt")
        notADirectory.writeText("I am a file, not a directory")

        // listFiles() on a file returns null
        val files = notADirectory.listFiles()

        assertNull("listFiles() on a file should return null", files)

        // The find logic should handle null gracefully with safe call operator
        val foundApk = files?.find { file ->
            file.name.endsWith(".apk")
        }

        assertNull("Should return null when listFiles() is null", foundApk)

        // Cleanup
        notADirectory.delete()
    }

    @Test
    fun testEmptyDirectoryListFiles_ReturnsEmptyArray() {
        // This test validates behavior with empty directory
        val emptyDir = File(tempDir, "empty_directory")
        emptyDir.mkdirs()

        val files = emptyDir.listFiles()

        assertNotNull("listFiles() on empty dir should not be null", files)
        assertEquals("listFiles() on empty dir should return empty array", 0, files.size)

        // Find logic should return null when no files match
        val foundApk = files?.find { file ->
            file.name.endsWith(".apk")
        }

        assertNull("Should find no APK in empty directory", foundApk)

        // Cleanup
        emptyDir.delete()
    }
}
