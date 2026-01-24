package com.vrpirates.rookieonquest.data

import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Integration tests for APK integrity validation using PackageManager.
 *
 * These tests verify that staged APK files used for recovery are validated
 * to ensure they are valid Android packages and not corrupted files.
 *
 * Related Story: 1.11 - Fix Staged APK Cross-Contamination (Review Follow-up)
 */
@RunWith(AndroidJUnit4::class)
class ApkIntegrityValidationTest {

    private lateinit var context: Context
    private lateinit var tempTestDir: File

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext<Context>()
        tempTestDir = File(context.cacheDir, "apk_integrity_test_${System.currentTimeMillis()}")
        tempTestDir.mkdirs()
    }

    /**
     * Verify that a valid APK file passes validation.
     *
     * Note: This test uses the app's own APK as a valid APK reference.
     * In a real scenario, you would use actual game APKs for testing.
     */
    @Test
    fun validApk_PassesIntegrityCheck() {
        // Given: The app's own APK file (which is guaranteed to be valid)
        val appApkPath = context.packageCodePath
        val appApk = File(appApkPath)

        // When: Validating with PackageManager
        val packageInfo = context.packageManager.getPackageArchiveInfo(
            appApkPath,
            0
        )

        // Then: Package info should be available
        assertNotNull("Valid APK should return package info", packageInfo)
        assertTrue("App APK should exist", appApk.exists())
        assertTrue("App APK should have content", appApk.length() > 0)
    }

    /**
     * Verify that package name verification works correctly.
     */
    @Test
    fun apkWithExpectedPackageName_PassesValidation() {
        // Given: The app's own APK file
        val appApkPath = context.packageCodePath
        val appPackageName = context.packageName
        val repository = MainRepository(context)

        // When: Validating with correct package name using real repository
        val isValid = repository.isValidApkFile(File(appApkPath), appPackageName)

        // Then: Should be valid
        assertTrue("APK with correct package name should pass", isValid)
    }

    /**
     * Verify that package name mismatch is caught.
     */
    @Test
    fun apkWithWrongPackageName_FailsValidation() {
        // Given: The app's own APK file but expecting a different package name
        val appApkPath = context.packageCodePath
        val wrongPackageName = "com.wrong.package"
        val repository = MainRepository(context)

        // When: Validating with wrong package name using real repository
        val isValid = repository.isValidApkFile(File(appApkPath), wrongPackageName)

        // Then: Should be invalid
        assertFalse("APK with wrong package name should fail", isValid)
    }

    /**
     * Verify that a non-APK file fails validation.
     */
    @Test
    fun invalidApk_FailsIntegrityCheck() {
        // Given: A text file with .apk extension (not a real APK)
        val fakeApk = File(tempTestDir, "fake.apk")
        fakeApk.writeText("This is not a valid APK file")
        val repository = MainRepository(context)

        // When: Validating using real repository
        val isValid = repository.isValidApkFile(fakeApk)

        // Then: Should be invalid
        assertFalse("Fake APK should not be valid", isValid)
    }

    /**
     * Verify that an empty file fails validation.
     */
    @Test
    fun emptyFile_FailsIntegrityCheck() {
        // Given: An empty file with .apk extension
        val emptyApk = File(tempTestDir, "empty.apk")
        emptyApk.createNewFile()
        val repository = MainRepository(context)

        // When: Validating using real repository
        val isValid = repository.isValidApkFile(emptyApk)

        // Then: Should be invalid
        assertFalse("Empty APK should not be valid", isValid)
    }
}