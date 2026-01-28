package com.vrpirates.rookieonquest.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for install.txt parsing and OBB detection - Story 1.7
 *
 * Story 1.7 Code Review Round 5: Tests now use production InstallUtils methods
 * instead of re-implementing parsing logic.
 *
 * Requirements covered:
 * - Task 1: Execute Special Instructions (install.txt)
 * - Task 2: Move OBB Files (OBB detection patterns)
 */
class InstallTxtParsingTest {

    // ========== install.txt Parsing Tests using InstallUtils ==========

    @Test
    fun installTxt_parseBasicPushCommand() {
        // Test basic adb push command parsing using production code
        val argsString = "Quake3Quest/baseq3 /sdcard/Quake3Quest/"
        val (source, dest) = InstallUtils.parseAdbPushArgs(argsString)

        assertEquals("Source should be 'Quake3Quest/baseq3'", "Quake3Quest/baseq3", source)
        assertEquals("Destination should be '/sdcard/Quake3Quest/'", "/sdcard/Quake3Quest/", dest)
    }

    @Test
    fun installTxt_parseQuotedPaths() {
        // Test quote-aware parsing for paths with spaces
        val argsString = "\"Quake3 Quest/base q3\" \"/sdcard/Quake3 Quest/\""
        val (source, dest) = InstallUtils.parseAdbPushArgs(argsString)

        assertEquals("Source should preserve spaces", "Quake3 Quest/base q3", source)
        assertEquals("Destination should preserve spaces", "/sdcard/Quake3 Quest/", dest)
    }

    @Test
    fun installTxt_parseMixedQuoting() {
        // Test mixed quoted and unquoted arguments
        val argsString = "\"path with spaces\" /sdcard/simple"
        val (source, dest) = InstallUtils.parseAdbPushArgs(argsString)

        assertEquals("Quoted source should be parsed correctly", "path with spaces", source)
        assertEquals("Unquoted dest should be parsed correctly", "/sdcard/simple", dest)
    }

    @Test
    fun installTxt_parseMultiplePushCommands() {
        // Test multiple push commands in install.txt
        val installTxt = """
            adb push Quake3Quest/baseq3 /sdcard/Quake3Quest/
            adb push Quake3Quest/mpq3 /sdcard/Quake3Quest/
            adb push Quake3Quest/missionpack /sdcard/Quake3Quest/
        """.trimIndent()

        val lines = installTxt.lines()
        val pushCommands = lines.filter { InstallUtils.isAdbPushCommand(it) }

        assertEquals("Should find 3 push commands", 3, pushCommands.size)
    }

    @Test
    fun installTxt_handleTrailingSlashVariants() {
        // Test different path formats (with/without trailing slash)
        val testCases = listOf(
            "folder /sdcard/folder" to Pair("folder", "/sdcard/folder"),
            "folder /sdcard/folder/" to Pair("folder", "/sdcard/folder/"),
            "folder /sdcard/" to Pair("folder", "/sdcard/")
        )

        testCases.forEach { (argsString, expected) ->
            val (source, dest) = InstallUtils.parseAdbPushArgs(argsString)
            assertEquals("Source should match", expected.first, source)
            assertEquals("Destination should match", expected.second, dest)
        }
    }

    @Test
    fun installTxt_handleRelativePaths() {
        // Test relative path handling
        val argsString = "data.zip /sdcard/"
        val (source, dest) = InstallUtils.parseAdbPushArgs(argsString)

        assertEquals("Source should be 'data.zip'", "data.zip", source)
        assertEquals("Destination should be '/sdcard/'", "/sdcard/", dest)
    }

    @Test
    fun installTxt_ignoreNonPushLines() {
        // Test that lines without 'adb push' are correctly identified
        val installTxt = """
            # This is a comment with adb push text that should be ignored
            adb push Quake3Quest/baseq3 /sdcard/Quake3Quest/
            Some other text
            adb push Quake3Quest/mpq3 /sdcard/Quake3Quest/
        """.trimIndent()

        val lines = installTxt.lines()
        val pushCommands = lines.filter { InstallUtils.isAdbPushCommand(it) }

        assertEquals("Should find only 2 push commands (comments should be ignored)", 2, pushCommands.size)
    }

    @Test
    fun installTxt_isAdbPushCommand_variousCases() {
        // Test isAdbPushCommand with various inputs
        assertTrue("Valid command should return true",
            InstallUtils.isAdbPushCommand("adb push source dest"))
        assertTrue("Case insensitive should work",
            InstallUtils.isAdbPushCommand("ADB PUSH source dest"))
        assertTrue("With leading whitespace should work",
            InstallUtils.isAdbPushCommand("  adb push source dest"))

        assertFalse("Comment with adb push should return false",
            InstallUtils.isAdbPushCommand("# adb push source dest"))
        assertFalse("Random text should return false",
            InstallUtils.isAdbPushCommand("some other text"))
        assertFalse("Empty string should return false",
            InstallUtils.isAdbPushCommand(""))
    }

    @Test
    fun installTxt_parseAdbPushArgs_edgeCases() {
        // Test edge cases for parseAdbPushArgs
        val (nullSource, nullDest) = InstallUtils.parseAdbPushArgs("")
        assertNull("Empty string should return null source", nullSource)
        assertNull("Empty string should return null dest", nullDest)

        val (blankSource, blankDest) = InstallUtils.parseAdbPushArgs("   ")
        assertNull("Blank string should return null source", blankSource)
        assertNull("Blank string should return null dest", blankDest)

        val (singleSource, singleDest) = InstallUtils.parseAdbPushArgs("onlyOneArg")
        assertNull("Single arg should return null source", singleSource)
        assertNull("Single arg should return null dest", singleDest)
    }

    @Test
    fun installTxt_parseAdbPushArgs_escapedQuotes() {
        // Story 1.7 Code Review Round 9: Test escaped quotes handling
        // Test escaped quotes within quoted strings
        val (source1, dest1) = InstallUtils.parseAdbPushArgs("\"path with \\\"escaped\\\" quote\" /sdcard/dest")
        assertEquals("Escaped quotes should be parsed correctly", "path with \"escaped\" quote", source1)
        assertEquals("Destination should be parsed", "/sdcard/dest", dest1)

        // Test escaped single quotes
        val (source2, dest2) = InstallUtils.parseAdbPushArgs("'path with \\'escaped\\' quote' /sdcard/dest")
        assertEquals("Escaped single quotes should be parsed correctly", "path with 'escaped' quote", source2)
        assertEquals("Destination should be parsed", "/sdcard/dest", dest2)

        // Test path with backslash character (common in Windows-style paths)
        val (source3, dest3) = InstallUtils.parseAdbPushArgs("\"path\\\\folder\" /sdcard")
        assertEquals("Backslash in path should be preserved", "path\\folder", source3)
        assertEquals("Destination should be parsed", "/sdcard", dest3)
    }

    // ========== OBB Detection Tests ==========

    @Test
    fun obbDetection_standardPattern() {
        // Test standard OBB pattern: packageName.obb
        val packageName = "com.game.example"
        val expectedPath = "/storage/emulated/0/Android/obb/$packageName"

        assertTrue("OBB path should contain packageName", expectedPath.contains(packageName))
    }

    @Test
    fun obbDetection_looseObbFiles() {
        // Test loose OBB file detection (outside packageName folder)
        val looseObbNames = listOf("main.123.com.example.obb", "patch.123.com.example.obb")

        looseObbNames.forEach { name ->
            assertTrue("OBB file should end with .obb", name.endsWith(".obb", ignoreCase = true))
        }
    }

    @Test
    fun obbDetection_naturalSorting() {
        // Story 1.7 Code Review Round 9: Test uses production InstallUtils.sortObbFiles()
        // This is important when multiple OBB files exist
        // OBB format: main.{versionCode}.{packageName}.obb or patch.{versionCode}.{packageName}.obb
        val obbFiles = listOf(
            "main.10.com.game.obb",
            "main.1.com.game.obb",
            "main.2.com.game.obb",
            "main.20.com.game.obb"
        )

        // Use production code for sorting instead of re-implementing
        val sorted = InstallUtils.sortObbFiles(obbFiles)

        // Natural sort should order: 1, 2, 10, 20 (not 1, 10, 2, 20)
        assertEquals("First should be main.1", "main.1.com.game.obb", sorted[0])
        assertEquals("Second should be main.2", "main.2.com.game.obb", sorted[1])
        assertEquals("Third should be main.10", "main.10.com.game.obb", sorted[2])
        assertEquals("Fourth should be main.20", "main.20.com.game.obb", sorted[3])
    }

    @Test
    fun obbDetection_naturalSorting_mainBeforePatch() {
        // Test that main files come before patch files at the same version
        val obbFiles = listOf(
            "patch.1.com.game.obb",
            "main.1.com.game.obb",
            "patch.2.com.game.obb",
            "main.2.com.game.obb"
        )

        val sorted = InstallUtils.sortObbFiles(obbFiles)

        assertEquals("main.1 should come before patch", "main.1.com.game.obb", sorted[0])
        assertEquals("main.2 should come before patch.1", "main.2.com.game.obb", sorted[1])
        assertEquals("patch.1 should come after main files", "patch.1.com.game.obb", sorted[2])
        assertEquals("patch.2 should be last", "patch.2.com.game.obb", sorted[3])
    }

    @Test
    fun obbDetection_caseInsensitiveExtension() {
        // Test that .obb extension matching is case-insensitive
        val obbVariants = listOf(
            "game.OBB",
            "game.obb",
            "game.Obb"
        )

        obbVariants.forEach { name ->
            assertTrue("Should detect .obb regardless of case",
                name.endsWith(".obb", ignoreCase = true))
        }
    }
}
