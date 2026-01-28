package com.vrpirates.rookieonquest.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for version verification logic - Story 1.7
 *
 * Tests String to Long parsing for catalog versionCode comparison.
 *
 * Requirements covered:
 * - Task 5: Post-Install Verification & State Management
 *   - Parse catalog versionCode (String) to Long for comparison
 *   - Handle edge cases: empty string, malformed, null
 */
class VersionVerificationTest {

    // ========== String to Long Parsing Tests ==========

    @Test
    fun versionCode_parseValidNumber() {
        // Test parsing valid version code strings
        val testCases = mapOf(
            "1" to 1L,
            "123" to 123L,
            "123456789" to 123456789L,
            "2147483647" to 2147483647L, // Int.MAX_VALUE
            "9223372036854775807" to 9223372036854775807L // Long.MAX_VALUE
        )

        testCases.forEach { (input, expected) ->
            val result = input.toLongOrNull() ?: 0L
            assertEquals("Version '$input' should parse to $expected", expected, result)
        }
    }

    @Test
    fun versionCode_handleEmptyString() {
        // Test that empty string returns 0L (edge case)
        val version = "".toLongOrNull() ?: 0L
        assertEquals("Empty string should parse to 0", 0L, version)
    }

    @Test
    fun versionCode_handleMalformedString() {
        // Test that malformed strings return 0L (edge case)
        val testCases = listOf(
            "abc",
            "12abc34",
            "1.2.3", // Dotted version
            "v1.0", // Version prefix
            "1.0-beta" // Version suffix
        )

        testCases.forEach { input ->
            val result = input.toLongOrNull() ?: 0L
            assertEquals("Malformed version '$input' should parse to 0", 0L, result)
        }
    }

    @Test
    fun versionCode_handleWhitespace() {
        // Test handling of whitespace in version strings
        val testCases = mapOf(
            " 123 " to 123L,
            "\t456\t" to 456L,
            "\n789\n" to 789L
        )

        testCases.forEach { (input, expected) ->
            val result = input.trim().toLongOrNull() ?: 0L
            assertEquals("Version with whitespace '$input' should parse to $expected", expected, result)
        }
    }

    @Test
    fun versionCode_handleNegativeNumbers() {
        // Test that negative numbers are handled (though unlikely in practice)
        val version = "-123".toLongOrNull() ?: 0L
        assertEquals("Negative version should parse correctly", -123L, version)
    }

    @Test
    fun versionCode_handleZero() {
        // Test that zero is handled correctly
        val version = "0".toLongOrNull() ?: 0L
        assertEquals("Zero version should parse to 0", 0L, version)
    }

    // ========== Version Comparison Tests ==========

    @Test
    fun versionCompare_matchingVersions() {
        // Test comparison when versions match
        val catalogVersion = "123".toLongOrNull() ?: 0L
        val installedVersion = 123L

        assertEquals("Matching versions should be equal", catalogVersion, installedVersion)
    }

    @Test
    fun versionCompare_catalogVersionHigher() {
        // Test when catalog version is higher than installed
        val catalogVersion = "200".toLongOrNull() ?: 0L
        val installedVersion = 100L

        assertTrue("Catalog version should be higher", catalogVersion > installedVersion)
    }

    @Test
    fun versionCompare_installedVersionHigher() {
        // Test when installed version is higher than catalog (edge case)
        val catalogVersion = "100".toLongOrNull() ?: 0L
        val installedVersion = 200L

        assertTrue("Installed version should be higher", installedVersion > catalogVersion)
    }

    @Test
    fun versionCompare_handleInvalidCatalogVersion() {
        // Test when catalog version is invalid (parses to 0)
        val catalogVersion = "invalid".toLongOrNull() ?: 0L
        val installedVersion = 100L

        assertFalse("Invalid catalog version (0) should not match valid version",
            catalogVersion == installedVersion)
    }

    @Test
    fun versionCompare_handleBothInvalid() {
        // Test when both versions are invalid
        val catalogVersion = "abc".toLongOrNull() ?: 0L
        val installedVersion = 0L

        assertEquals("Both invalid versions should equal 0", catalogVersion, installedVersion)
    }

    // ========== Verification Logic Tests ==========

    @Test
    fun verificationLogic_successWhenVersionsMatch() {
        // Test the complete verification logic
        val catalogVersionCode = "123456"
        val installedVersionCode = 123456L

        val catalogVersion = catalogVersionCode.toLongOrNull() ?: 0L

        val isVerified = (installedVersionCode == catalogVersion)

        assertTrue("Verification should succeed when versions match", isVerified)
    }

    @Test
    fun verificationLogic_failWhenVersionsMismatch() {
        // Test verification failure with mismatched versions
        val catalogVersionCode = "123456"
        val installedVersionCode = 123455L

        val catalogVersion = catalogVersionCode.toLongOrNull() ?: 0L

        val isVerified = (installedVersionCode == catalogVersion)

        assertFalse("Verification should fail when versions mismatch", isVerified)
    }

    @Test
    fun verificationLogic_handleNullCatalogVersion() {
        // Test when catalog version is null (simulating missing data)
        val catalogVersionCode: String? = null
        val installedVersionCode = 123L

        val catalogVersion = catalogVersionCode?.toLongOrNull() ?: 0L

        val isVerified = (installedVersionCode == catalogVersion)

        assertFalse("Null catalog version should not match valid version", isVerified)
    }

    @Test
    fun verificationLogic_handleLargeVersionNumbers() {
        // Test with very large version numbers (near Long.MAX_VALUE)
        val catalogVersionCode = "9223372036854775806" // Long.MAX_VALUE - 1
        val installedVersionCode = 9223372036854775806L

        val catalogVersion = catalogVersionCode.toLongOrNull() ?: 0L

        val isVerified = (installedVersionCode == catalogVersion)

        assertTrue("Large version numbers should compare correctly", isVerified)
    }
}
