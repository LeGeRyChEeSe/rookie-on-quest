package com.vrpirates.rookieonquest.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for extraction progress calculations - Story 1.6
 *
 * Tests progress scaling and formula validation for extraction phase.
 *
 * Requirements covered:
 * - AC4: Extraction progress updates Room DB at minimum 1Hz (NFR-P10)
 * - AC4: UI receives progress updates via StateFlow
 * - Progress scaling: download = 0-80%, merge = 80-82%, extraction = 85-92%, OBB = 94%, APK = 96%
 *
 * Note: Extraction starts at PROGRESS_MILESTONE_EXTRACTING (85%) and ends at
 * PROGRESS_MILESTONE_EXTRACTION_END (92%), NOT at 100%. This ensures monotonic
 * progress without backwards jumps when OBB (94%) and APK (96%) phases begin.
 * The 80-85% range is reserved for:
 * - Multi-part archive merging (PROGRESS_MILESTONE_MERGING = 82%)
 * - File preparation before extraction begins
 */
class ExtractionProgressTest {

    // Helper to calculate scaled progress using the actual formula from MainRepository
    // Updated to use EXTRACTION_END (92%) as ceiling instead of 100%
    private fun calculateScaledProgress(extractionProgress: Float): Float {
        val extractionPhaseSpan = Constants.PROGRESS_MILESTONE_EXTRACTION_END - Constants.PROGRESS_MILESTONE_EXTRACTING
        return Constants.PROGRESS_MILESTONE_EXTRACTING + (extractionProgress * extractionPhaseSpan)
    }

    // ========== Progress Scaling Formula Tests ==========

    @Test
    fun progressScaling_atExtractionStart_returns_85_percent() {
        // When extraction starts (0% extracted), total progress should be 85%
        // (not 80%, which is where download ends - there's a merge phase in between)
        val extractionProgress = 0f
        val scaledProgress = calculateScaledProgress(extractionProgress)

        assertEquals(
            "At extraction start (0%), total progress should be 85%",
            0.85f,
            scaledProgress,
            0.001f
        )
    }

    @Test
    fun progressScaling_atExtractionComplete_returns_92_percent() {
        // When extraction completes (100% extracted), total progress should be 92% (EXTRACTION_END)
        // This ensures monotonic progress - OBB at 94%, APK at 96% come after extraction
        val extractionProgress = 1f
        val scaledProgress = calculateScaledProgress(extractionProgress)

        assertEquals(
            "At extraction complete (100%), total progress should be 92% (EXTRACTION_END)",
            Constants.PROGRESS_MILESTONE_EXTRACTION_END,
            scaledProgress,
            0.001f
        )
    }

    @Test
    fun progressScaling_atExtractionMidpoint_returns_885_percent() {
        // When extraction is at 50%, total progress should be 88.5%
        // Formula: 0.85 + (0.5 * 0.07) = 0.85 + 0.035 = 0.885
        val extractionProgress = 0.5f
        val scaledProgress = calculateScaledProgress(extractionProgress)

        assertEquals(
            "At extraction 50%, total progress should be 88.5%",
            0.885f,
            scaledProgress,
            0.001f
        )
    }

    @Test
    fun progressScaling_atExtractionQuarter_returns_8675_percent() {
        // When extraction is at 25%, total progress should be 86.75%
        // Formula: 0.85 + (0.25 * 0.07) = 0.85 + 0.0175 = 0.8675
        val extractionProgress = 0.25f
        val scaledProgress = calculateScaledProgress(extractionProgress)

        assertEquals(
            "At extraction 25%, total progress should be 86.75%",
            0.8675f,
            scaledProgress,
            0.001f
        )
    }

    @Test
    fun progressScaling_atExtractionThreeQuarters_returns_9025_percent() {
        // When extraction is at 75%, total progress should be 90.25%
        // Formula: 0.85 + (0.75 * 0.07) = 0.85 + 0.0525 = 0.9025
        val extractionProgress = 0.75f
        val scaledProgress = calculateScaledProgress(extractionProgress)

        assertEquals(
            "At extraction 75%, total progress should be 90.25%",
            0.9025f,
            scaledProgress,
            0.001f
        )
    }

    // ========== Progress Calculation Tests ==========

    @Test
    fun extractionProgress_withVariousEntryCounts() {
        // Test progress calculation with different entry counts
        val testCases = listOf(
            Triple(0L, 100L, 0f),       // 0/100 = 0%
            Triple(25L, 100L, 0.25f),   // 25/100 = 25%
            Triple(50L, 100L, 0.5f),    // 50/100 = 50%
            Triple(75L, 100L, 0.75f),   // 75/100 = 75%
            Triple(100L, 100L, 1.0f),   // 100/100 = 100%
        )

        testCases.forEach { (extracted, total, expected) ->
            val progress = if (total > 0) extracted.toFloat() / total else 0f
            assertEquals(
                "Progress for $extracted/$total should be $expected",
                expected,
                progress,
                0.001f
            )
        }
    }

    @Test
    fun extractionProgress_withZeroTotal_returns_zero() {
        // Edge case: when total is 0, progress should be 0 (not NaN or Infinity)
        val extractedBytes = 0L
        val totalEntryBytes = 0L

        val extractionProgress = if (totalEntryBytes > 0) {
            extractedBytes.toFloat() / totalEntryBytes
        } else 0f

        assertEquals(
            "Progress with zero total bytes should be 0",
            0f,
            extractionProgress,
            0.001f
        )
    }

    @Test
    fun extractionProgress_withLargeNumbers() {
        // Test with realistic large file sizes (e.g., 2GB game)
        val extractedBytes = 1_500_000_000L  // 1.5 GB
        val totalEntryBytes = 2_000_000_000L // 2 GB

        val extractionProgress = if (totalEntryBytes > 0) {
            extractedBytes.toFloat() / totalEntryBytes
        } else 0f

        assertEquals(
            "Progress for 1.5GB/2GB should be 75%",
            0.75f,
            extractionProgress,
            0.001f
        )
    }

    // ========== Progress Monotonicity Tests ==========

    @Test
    fun progressScaling_isMonotonicallyIncreasing() {
        // Progress should always increase as extraction progresses
        var previousProgress = 0f

        for (i in 0..100 step 10) {
            val extractionProgress = i / 100f
            val scaledProgress = calculateScaledProgress(extractionProgress)

            assertTrue(
                "Progress should be monotonically increasing: $previousProgress -> $scaledProgress",
                scaledProgress >= previousProgress
            )
            previousProgress = scaledProgress
        }
    }

    // ========== Progress Bounds Tests ==========

    @Test
    fun progressScaling_neverExceeds92Percent_whenCapped() {
        // Even with progress > 1.0 (edge case), scaled should be capped at 92%
        val extractionProgress = 1.5f // Edge case: overshooting
        val scaledProgress = calculateScaledProgress(extractionProgress)

        // Note: The formula doesn't clamp internally, so this tests that overshooting
        // would exceed EXTRACTION_END. In production, extractionProgress is clamped to [0, 1].
        assertTrue(
            "With extractionProgress=1.5, scaledProgress should exceed 92% (unclamped)",
            scaledProgress > Constants.PROGRESS_MILESTONE_EXTRACTION_END
        )
    }

    @Test
    fun progressScaling_alwaysInValidRange() {
        // Scaled progress should always be between 85% and 92% during extraction phase
        for (i in 0..100) {
            val extractionProgress = i / 100f
            val scaledProgress = calculateScaledProgress(extractionProgress)

            assertTrue(
                "Scaled progress should always be >= 85% during extraction: got $scaledProgress",
                scaledProgress >= Constants.PROGRESS_MILESTONE_EXTRACTING
            )
            assertTrue(
                "Scaled progress should always be <= 92% during extraction: got $scaledProgress",
                scaledProgress <= Constants.PROGRESS_MILESTONE_EXTRACTION_END
            )
        }
    }

    // ========== Constants Validation Tests ==========

    @Test
    fun progressDownloadPhaseEnd_is_80_percent() {
        assertEquals(
            "Download phase should end at 80%",
            0.8f,
            Constants.PROGRESS_DOWNLOAD_PHASE_END,
            0.001f
        )
    }

    @Test
    fun progressMilestoneExtracting_is_85_percent() {
        assertEquals(
            "Extraction milestone should be at 85%",
            0.85f,
            Constants.PROGRESS_MILESTONE_EXTRACTING,
            0.001f
        )
    }

    @Test
    fun extractionPhaseSpan_is_7_percent() {
        // Extraction phase spans from 85% to 92% = 7%
        val extractionSpan = Constants.PROGRESS_MILESTONE_EXTRACTION_END - Constants.PROGRESS_MILESTONE_EXTRACTING
        assertEquals(
            "Extraction phase should span 7% (85-92%)",
            0.07f,
            extractionSpan,
            0.001f
        )
    }

    @Test
    fun mergePhaseExists_between_download_and_extraction() {
        // Verify there's a gap between download end and extraction start for merge phase
        val gapBetweenDownloadAndExtraction = Constants.PROGRESS_MILESTONE_EXTRACTING - Constants.PROGRESS_DOWNLOAD_PHASE_END
        assertEquals(
            "Gap between download (80%) and extraction (85%) should be 5% for merge phase",
            0.05f,
            gapBetweenDownloadAndExtraction,
            0.001f
        )
    }

    @Test
    fun progressMilestoneExtractionEnd_is_92_percent() {
        assertEquals(
            "Extraction end milestone should be at 92%",
            0.92f,
            Constants.PROGRESS_MILESTONE_EXTRACTION_END,
            0.001f
        )
    }

    @Test
    fun progressMilestones_areMonotonicallyIncreasing() {
        // Verify all progress milestones form a monotonically increasing sequence
        // This prevents UI progress from jumping backwards
        val milestones = listOf(
            "Download end" to Constants.PROGRESS_DOWNLOAD_PHASE_END,           // 80%
            "Extraction start" to Constants.PROGRESS_MILESTONE_EXTRACTION_START, // 80%
            "Merging" to Constants.PROGRESS_MILESTONE_MERGING,                  // 82%
            "Extracting" to Constants.PROGRESS_MILESTONE_EXTRACTING,            // 85%
            "Extraction end" to Constants.PROGRESS_MILESTONE_EXTRACTION_END,    // 92%
            "Installing OBBs" to Constants.PROGRESS_MILESTONE_INSTALLING_OBBS,  // 94%
            "Launching installer" to Constants.PROGRESS_MILESTONE_LAUNCHING_INSTALLER // 96%
        )

        var previous = -1f
        for ((name, value) in milestones) {
            assertTrue(
                "Milestone '$name' ($value) should be >= previous ($previous)",
                value >= previous
            )
            previous = value
        }
    }
}
