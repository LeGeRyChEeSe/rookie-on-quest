package com.vrpirates.rookieonquest.data

import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log

/**
 * Singleton manager for CPU wake locks during extraction operations.
 *
 * Prevents Quest VR headset from going to sleep during long extractions (>2 minutes)
 * as required by NFR-P11 and FR55.
 *
 * Thread-safety: All public methods are synchronized to prevent race conditions
 * between acquire/release calls from different coroutines.
 *
 * Reference counting: Multiple callers can acquire the wake lock. The actual
 * PowerManager wake lock is only released when all callers have released.
 * This prevents premature release in multi-threaded scenarios.
 *
 * Usage:
 * ```kotlin
 * WakeLockManager.acquire(context)
 * try {
 *     // Long-running extraction
 * } finally {
 *     WakeLockManager.release()
 * }
 * ```
 */
object WakeLockManager {
    private const val TAG = "WakeLockManager"

    /**
     * Wake lock tag identifier for Android power management.
     * Format: "AppName:Feature" as recommended by Android documentation.
     */
    private const val WAKE_LOCK_TAG = "RookieOnQuest:Extraction"

    /**
     * Maximum wake lock hold time in milliseconds (30 minutes).
     * Acts as a safety timeout to prevent battery drain if release() is never called.
     * Most game extractions complete in 5-15 minutes.
     */
    const val MAX_WAKE_LOCK_TIMEOUT_MS = 30L * 60L * 1000L

    /**
     * The active wake lock, or null if not held.
     * Thread-safety: All access is through @Synchronized methods.
     */
    private var wakeLock: PowerManager.WakeLock? = null

    /**
     * Reference count for wake lock acquisitions.
     * The actual wake lock is released only when this reaches 0.
     * This prevents premature release when multiple operations are running.
     * Thread-safety: All access is through @Synchronized methods.
     */
    private var referenceCount: Int = 0

    /**
     * Timestamp when the wake lock was first acquired, using monotonic time.
     * Uses SystemClock.elapsedRealtime() instead of System.currentTimeMillis()
     * to ensure accurate duration tracking even if system time changes.
     * Thread-safety: All access is through @Synchronized methods.
     */
    private var acquisitionTimeMs: Long = 0L

    /**
     * Acquires a partial CPU wake lock for extraction operations.
     *
     * PARTIAL_WAKE_LOCK ensures the CPU stays on but allows the screen to turn off,
     * which is the appropriate level for background processing.
     *
     * The wake lock includes a 30-minute timeout as a safety mechanism.
     * If release() is not called within this time, the lock is automatically released.
     *
     * Reference counting: Multiple calls to acquire() are tracked. The wake lock
     * is only actually released when release() has been called the same number of times.
     *
     * @param context Application context (used to access PowerManager)
     * @throws IllegalStateException if PowerManager service is unavailable
     */
    @Synchronized
    fun acquire(context: Context) {
        if (wakeLock?.isHeld == true) {
            referenceCount++
            // Only log at debug level when incrementing reference (reduces log noise)
            Log.d(TAG, "Wake lock already held, incrementing reference count to $referenceCount")
            return
        }
        // Note: "acquire requested" log removed (Code Review fix) - the "Wake lock acquired" log below is sufficient

        // Increment reference count BEFORE checking PowerManager availability
        // This ensures release() can be safely called even if PowerManager is unavailable
        // (prevents referenceCount from going negative in try-finally patterns)
        referenceCount++

        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (powerManager == null) {
            Log.e(TAG, "PowerManager service not available - proceeding without wake lock (refCount: $referenceCount)")
            return
        }
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            WAKE_LOCK_TAG
        ).apply {
            // Acquire with timeout for safety
            acquire(MAX_WAKE_LOCK_TIMEOUT_MS)
        }

        acquisitionTimeMs = SystemClock.elapsedRealtime()
        Log.i(TAG, "Wake lock acquired (timeout: ${MAX_WAKE_LOCK_TIMEOUT_MS / 1000 / 60} min)")
    }

    /**
     * Releases the wake lock if held and reference count reaches zero.
     *
     * Reference counting: Each call to acquire() must be matched with a call to release().
     * The actual wake lock is only released when all acquirers have released.
     *
     * Safe to call multiple times or when wake lock is not held.
     * Always call this in a finally block to ensure proper cleanup.
     */
    @Synchronized
    fun release() {
        if (referenceCount > 0) {
            referenceCount--
        }
        // Only log at debug level when there are still active references (reduces log noise)
        if (referenceCount > 0) {
            Log.d(TAG, "Wake lock release (refCount: $referenceCount, still held)")
        }

        // Only actually release when all references are gone
        if (referenceCount > 0) {
            return
        }

        wakeLock?.let { lock ->
            // Calculate duration before checking isHeld, as we want to log it regardless
            val heldDurationMs = if (acquisitionTimeMs > 0L) {
                SystemClock.elapsedRealtime() - acquisitionTimeMs
            } else 0L
            val heldDurationSec = heldDurationMs / 1000

            if (lock.isHeld) {
                lock.release()
                Log.i(TAG, "Wake lock released after ${heldDurationSec}s")
            } else {
                // Wake lock timed out (auto-released by system after MAX_WAKE_LOCK_TIMEOUT_MS)
                Log.w(TAG, "Wake lock had timed out after ${heldDurationSec}s (max: ${MAX_WAKE_LOCK_TIMEOUT_MS / 1000 / 60} min)")
            }
        }
        wakeLock = null
        acquisitionTimeMs = 0L
    }

    /**
     * Force releases the wake lock regardless of reference count.
     *
     * Use this only for emergency cleanup or testing. Normal code should
     * use release() to respect reference counting.
     */
    @Synchronized
    fun forceRelease() {
        Log.w(TAG, "Force releasing wake lock (was refCount: $referenceCount)")
        referenceCount = 0
        wakeLock?.let { lock ->
            // Calculate duration before checking isHeld for complete logging
            val heldDurationMs = if (acquisitionTimeMs > 0L) {
                SystemClock.elapsedRealtime() - acquisitionTimeMs
            } else 0L
            val heldDurationSec = heldDurationMs / 1000

            if (lock.isHeld) {
                lock.release()
                Log.i(TAG, "Wake lock force released after ${heldDurationSec}s")
            } else {
                Log.w(TAG, "Wake lock had already timed out after ${heldDurationSec}s")
            }
        }
        wakeLock = null
        acquisitionTimeMs = 0L
    }

    /**
     * Checks if the wake lock is currently held.
     *
     * @return true if wake lock is held, false otherwise
     */
    @Synchronized
    fun isHeld(): Boolean = wakeLock?.isHeld == true

    /**
     * Gets the current reference count.
     *
     * @return Number of active acquirers
     */
    @Synchronized
    fun getReferenceCount(): Int = referenceCount

    /**
     * Gets the duration in milliseconds since the wake lock was acquired.
     *
     * @return Duration in milliseconds, or 0 if wake lock is not held
     */
    @Synchronized
    fun getHeldDurationMs(): Long {
        return if (wakeLock?.isHeld == true && acquisitionTimeMs > 0L) {
            SystemClock.elapsedRealtime() - acquisitionTimeMs
        } else {
            0L
        }
    }

    /**
     * Checks if the extraction has exceeded 2 minutes.
     * Used for logging and NFR-P11 compliance verification.
     *
     * @return true if wake lock has been held for more than 2 minutes
     */
    @Synchronized
    fun hasExceededTwoMinutes(): Boolean {
        val durationMs = getHeldDurationMs()
        return durationMs > 2L * 60L * 1000L
    }
}
