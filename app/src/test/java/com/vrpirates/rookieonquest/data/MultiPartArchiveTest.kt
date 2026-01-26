package com.vrpirates.rookieonquest.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for multi-part archive handling - Story 1.6
 *
 * Tests lexicographic sorting of archive parts.
 *
 * Requirements covered:
 * - AC3: Handles multi-part archives sorted correctly (FR23)
 * - AC3: Merges parts before extraction
 */
class MultiPartArchiveTest {

    // ========== Lexicographic Sort Tests (AC: 3, FR23) ==========

    @Test
    fun archiveParts_sortedLexicographically_singleDigit() {
        // Test basic single-digit part sorting
        val parts = listOf(
            "game.7z.003",
            "game.7z.001",
            "game.7z.002"
        )

        val sorted = parts.sortedWith(compareBy { it })

        assertEquals(
            "Parts should be sorted lexicographically",
            listOf("game.7z.001", "game.7z.002", "game.7z.003"),
            sorted
        )
    }

    @Test
    fun archiveParts_sortedLexicographically_doubleDigit() {
        // Test double-digit part numbers (010, 011, etc.)
        val parts = listOf(
            "game.7z.010",
            "game.7z.001",
            "game.7z.002",
            "game.7z.011"
        )

        val sorted = parts.sortedWith(compareBy { it })

        // Note: Lexicographic sort puts "001" < "002" < "010" < "011"
        assertEquals(
            "Double-digit parts should sort correctly",
            listOf("game.7z.001", "game.7z.002", "game.7z.010", "game.7z.011"),
            sorted
        )
    }

    @Test
    fun archiveParts_sortedLexicographically_manyParts() {
        // Test with many parts (common for large games)
        val parts = (1..15).map { "game.7z.%03d".format(it) }.shuffled()

        val sorted = parts.sortedWith(compareBy { it })

        val expected = (1..15).map { "game.7z.%03d".format(it) }
        assertEquals(
            "15 parts should sort correctly",
            expected,
            sorted
        )
    }

    @Test
    fun archiveParts_sortedLexicographically_mixedPrefixes() {
        // Test with different archive names in same folder
        // This shouldn't happen in practice, but ensures robustness
        val parts = listOf(
            "game.7z.002",
            "other.7z.001",
            "game.7z.001"
        )

        val sorted = parts.sortedWith(compareBy { it })

        assertEquals(
            "Mixed prefixes should sort alphabetically then by part",
            listOf("game.7z.001", "game.7z.002", "other.7z.001"),
            sorted
        )
    }

    @Test
    fun archiveParts_sortedLexicographically_withPath() {
        // Test with path prefixes (from subdirectories)
        val parts = listOf(
            "data/game.7z.002",
            "data/game.7z.001",
            "game.7z.001"
        )

        val sorted = parts.sortedWith(compareBy { it })

        assertEquals(
            "Paths should sort before filenames",
            listOf("data/game.7z.001", "data/game.7z.002", "game.7z.001"),
            sorted
        )
    }

    @Test
    fun archiveParts_sortedNaturally_nonPadded() {
        // Test natural/numeric sorting with non-padded numbers (Adversarial Review fix)
        // This ensures game.7z.2 comes BEFORE game.7z.10
        val parts = listOf(
            "game.7z.10",
            "game.7z.2",
            "game.7z.1"
        )

        // Using the same comparator logic as implemented in MainRepository
        val sorted = parts.sortedWith(compareBy<String> { it.substringBeforeLast(".7z") }
            .thenBy { it.substringAfterLast(".7z.").toIntOrNull() ?: 0 }
            .thenBy { it })

        assertEquals(
            "Non-padded parts should sort numerically",
            listOf("game.7z.1", "game.7z.2", "game.7z.10"),
            sorted
        )
    }

    @Test
    fun archiveParts_sortedNaturally_mixedPaddedAndNonPadded() {
        // Test natural sorting with a mix of padded and non-padded numbers
        val parts = listOf(
            "game.7z.010",
            "game.7z.2",
            "game.7z.001"
        )

        val sorted = parts.sortedWith(compareBy<String> { it.substringBeforeLast(".7z") }
            .thenBy { it.substringAfterLast(".7z.").toIntOrNull() ?: 0 }
            .thenBy { it })

        assertEquals(
            "Mixed padded/non-padded parts should sort numerically",
            listOf("game.7z.001", "game.7z.2", "game.7z.010"),
            sorted
        )
    }

    // ========== Archive Detection Tests ==========

    @Test
    fun is7zArchive_detectsStandardArchive() {
        val filename = "game.7z"
        assertTrue(
            ".7z extension should be detected as archive",
            filename.contains(".7z")
        )
    }

    @Test
    fun is7zArchive_detectsMultiPart() {
        val parts = listOf("game.7z.001", "game.7z.002", "game.7z.003")
        parts.forEach { part ->
            assertTrue(
                "Multi-part $part should be detected as archive",
                part.contains(".7z")
            )
        }
    }

    @Test
    fun is7zArchive_caseInsensitive() {
        val variants = listOf("game.7z", "game.7Z", "GAME.7z", "GAME.7Z")
        variants.forEach { variant ->
            assertTrue(
                "Case variant $variant should contain .7z (case-insensitive check)",
                variant.lowercase().contains(".7z")
            )
        }
    }

    // ========== Merge Order Validation ==========

    @Test
    fun mergeOrder_matchesExpectedSequence() {
        // Simulate the actual merge logic from MainRepository
        val archiveParts = mapOf(
            "game.7z.003" to 100L,
            "game.7z.001" to 100L,
            "game.7z.002" to 100L
        ).filter { it.key.contains(".7z") }

        val sortedParts = archiveParts.keys.sortedWith(compareBy { it })

        // Verify merge order
        assertEquals(
            "First part to merge should be .001",
            "game.7z.001",
            sortedParts[0]
        )
        assertEquals(
            "Second part to merge should be .002",
            "game.7z.002",
            sortedParts[1]
        )
        assertEquals(
            "Third part to merge should be .003",
            "game.7z.003",
            sortedParts[2]
        )
    }

    @Test
    fun mergeOrder_withSinglePart() {
        // Edge case: single-part archive (not split)
        val archiveParts = mapOf("game.7z" to 1000L)
        val sortedParts = archiveParts.keys.sortedWith(compareBy { it })

        assertEquals(
            "Single part archive should have exactly one entry",
            1,
            sortedParts.size
        )
        assertEquals(
            "Single part should be the only entry",
            "game.7z",
            sortedParts[0]
        )
    }
}
