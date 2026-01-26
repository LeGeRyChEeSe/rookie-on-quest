package com.vrpirates.rookieonquest.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests for WakeLockManager - Story 1.6 Wake Lock Implementation
 *
 * Tests wake lock acquire/release lifecycle on actual Android device.
 *
 * Requirements covered:
 * - AC5: CPU wake lock prevents Quest sleep (NFR-P11, FR55)
 * - Wake lock acquire/release with tag "RookieOnQuest:Extraction"
 * - Timeout safety (max 30 minutes)
 * - Release on extraction complete, fail, or cancel
 */
@RunWith(AndroidJUnit4::class)
class WakeLockManagerInstrumentedTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // Ensure clean state before each test using forceRelease
        // to reset reference count and release any held wake lock
        WakeLockManager.forceRelease()
    }

    @After
    fun tearDown() {
        // Always release wake lock after each test to prevent battery drain
        // Use forceRelease to ensure clean state regardless of reference count
        WakeLockManager.forceRelease()
    }

    // ========== Acquire/Release Lifecycle Tests (AC: 5) ==========

    @Test
    fun acquire_sets_wakeLock_to_held() {
        WakeLockManager.acquire(context)
        assertTrue(
            "Wake lock should be held after acquire()",
            WakeLockManager.isHeld()
        )
    }

    @Test
    fun release_sets_wakeLock_to_not_held() {
        WakeLockManager.acquire(context)
        WakeLockManager.release()
        assertFalse(
            "Wake lock should not be held after release()",
            WakeLockManager.isHeld()
        )
    }

    @Test
    fun acquire_increments_reference_count() {
        // Multiple acquire calls should increment reference count
        WakeLockManager.acquire(context)
        assertEquals("Reference count should be 1 after first acquire", 1, WakeLockManager.getReferenceCount())

        WakeLockManager.acquire(context)
        assertEquals("Reference count should be 2 after second acquire", 2, WakeLockManager.getReferenceCount())

        WakeLockManager.acquire(context)
        assertEquals("Reference count should be 3 after third acquire", 3, WakeLockManager.getReferenceCount())

        assertTrue(
            "Wake lock should still be held after multiple acquire calls",
            WakeLockManager.isHeld()
        )
    }

    @Test
    fun release_decrements_reference_count_and_releases_at_zero() {
        // Acquire three times
        WakeLockManager.acquire(context)
        WakeLockManager.acquire(context)
        WakeLockManager.acquire(context)
        assertEquals("Reference count should be 3", 3, WakeLockManager.getReferenceCount())

        // Release once - should still be held
        WakeLockManager.release()
        assertEquals("Reference count should be 2 after first release", 2, WakeLockManager.getReferenceCount())
        assertTrue("Wake lock should still be held with refCount=2", WakeLockManager.isHeld())

        // Release twice more - should now be released
        WakeLockManager.release()
        WakeLockManager.release()
        assertEquals("Reference count should be 0 after all releases", 0, WakeLockManager.getReferenceCount())
        assertFalse("Wake lock should not be held when refCount=0", WakeLockManager.isHeld())
    }

    @Test
    fun release_does_not_go_below_zero() {
        // Multiple releases without acquire should not make refCount negative
        WakeLockManager.release()
        WakeLockManager.release()
        WakeLockManager.release()

        assertEquals(
            "Reference count should not go below 0",
            0,
            WakeLockManager.getReferenceCount()
        )
    }

    @Test
    fun forceRelease_resets_reference_count_and_releases() {
        // Acquire multiple times
        WakeLockManager.acquire(context)
        WakeLockManager.acquire(context)
        WakeLockManager.acquire(context)
        assertEquals("Reference count should be 3", 3, WakeLockManager.getReferenceCount())
        assertTrue("Wake lock should be held", WakeLockManager.isHeld())

        // Force release should reset everything
        WakeLockManager.forceRelease()
        assertEquals("Reference count should be 0 after forceRelease", 0, WakeLockManager.getReferenceCount())
        assertFalse("Wake lock should not be held after forceRelease", WakeLockManager.isHeld())
    }

    @Test
    fun release_is_safe_when_not_held() {
        // Release without acquire should not throw
        WakeLockManager.release()
        assertFalse(
            "Wake lock should not be held after release without acquire",
            WakeLockManager.isHeld()
        )
    }

    // ========== Duration Tracking Tests ==========

    @Test
    fun getHeldDurationMs_returns_positive_value_when_held() {
        WakeLockManager.acquire(context)

        // Small delay to ensure duration > 0
        Thread.sleep(50)

        val duration = WakeLockManager.getHeldDurationMs()
        assertTrue(
            "Duration should be positive when wake lock is held",
            duration > 0
        )
    }

    @Test
    fun getHeldDurationMs_returns_0_after_release() {
        WakeLockManager.acquire(context)
        Thread.sleep(50)
        WakeLockManager.release()

        val duration = WakeLockManager.getHeldDurationMs()
        assertTrue(
            "Duration should be 0 after wake lock is released",
            duration == 0L
        )
    }

    // ========== Two Minute Threshold Tests ==========

    @Test
    fun hasExceededTwoMinutes_returns_false_immediately_after_acquire() {
        WakeLockManager.acquire(context)

        assertFalse(
            "hasExceededTwoMinutes should return false immediately after acquire",
            WakeLockManager.hasExceededTwoMinutes()
        )
    }

    // ========== Exception Safety Tests ==========

    @Test
    fun acquire_release_cycle_works_in_try_finally() {
        var exceptionOccurred = false

        try {
            WakeLockManager.acquire(context)
            // Simulate some work
            Thread.sleep(10)
        } catch (e: Exception) {
            exceptionOccurred = true
        } finally {
            WakeLockManager.release()
        }

        assertFalse(
            "No exception should occur in acquire/release cycle",
            exceptionOccurred
        )
        assertFalse(
            "Wake lock should be released in finally block",
            WakeLockManager.isHeld()
        )
    }

    @Test
    fun release_in_finally_works_even_if_acquire_was_not_called() {
        var exceptionOccurred = false

        try {
            // Intentionally skip acquire()
            // Simulate some work
        } catch (e: Exception) {
            exceptionOccurred = true
        } finally {
            WakeLockManager.release() // Should not throw
        }

        assertFalse(
            "No exception should occur when releasing without acquire",
            exceptionOccurred
        )
    }
}
