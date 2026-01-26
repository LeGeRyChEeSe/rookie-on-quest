package com.vrpirates.rookieonquest.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for DownloadUtils - Story 1.5 HTTP Range Resumption
 *
 * Tests HTTP response code handling for resume functionality:
 * - 206 Partial Content: Resume download from last byte
 * - 200 OK: Server ignored Range header, restart from beginning
 * - 416 Range Not Satisfiable: File already complete
 */
class DownloadUtilsTest {

    // ========== isResumeResponse Tests (AC 1, 2) ==========

    @Test
    fun isResumeResponse_returns_true_for_206() {
        assertTrue(
            "HTTP 206 Partial Content should indicate resumable response",
            DownloadUtils.isResumeResponse(206)
        )
    }

    @Test
    fun isResumeResponse_returns_false_for_200() {
        assertFalse(
            "HTTP 200 OK should NOT indicate resumable response (server ignored Range header)",
            DownloadUtils.isResumeResponse(200)
        )
    }

    @Test
    fun isResumeResponse_returns_false_for_416() {
        assertFalse(
            "HTTP 416 Range Not Satisfiable should NOT indicate resumable response",
            DownloadUtils.isResumeResponse(416)
        )
    }

    @Test
    fun isResumeResponse_returns_false_for_404() {
        assertFalse(
            "HTTP 404 Not Found should NOT indicate resumable response",
            DownloadUtils.isResumeResponse(404)
        )
    }

    @Test
    fun isResumeResponse_returns_false_for_500() {
        assertFalse(
            "HTTP 500 Server Error should NOT indicate resumable response",
            DownloadUtils.isResumeResponse(500)
        )
    }

    // ========== isRangeNotSatisfiable Tests (AC 5) ==========

    @Test
    fun isRangeNotSatisfiable_returns_true_for_416() {
        assertTrue(
            "HTTP 416 should indicate Range Not Satisfiable (file complete)",
            DownloadUtils.isRangeNotSatisfiable(416)
        )
    }

    @Test
    fun isRangeNotSatisfiable_returns_false_for_206() {
        assertFalse(
            "HTTP 206 should NOT indicate Range Not Satisfiable",
            DownloadUtils.isRangeNotSatisfiable(206)
        )
    }

    @Test
    fun isRangeNotSatisfiable_returns_false_for_200() {
        assertFalse(
            "HTTP 200 should NOT indicate Range Not Satisfiable",
            DownloadUtils.isRangeNotSatisfiable(200)
        )
    }

    @Test
    fun isRangeNotSatisfiable_returns_false_for_404() {
        assertFalse(
            "HTTP 404 should NOT indicate Range Not Satisfiable",
            DownloadUtils.isRangeNotSatisfiable(404)
        )
    }

    // ========== isDownloadableFile Tests ==========

    @Test
    fun isDownloadableFile_returns_true_for_apk() {
        assertTrue(DownloadUtils.isDownloadableFile("game.apk"))
        assertTrue(DownloadUtils.isDownloadableFile("Game.APK"))
        assertTrue(DownloadUtils.isDownloadableFile("com.example.game-v1.0.apk"))
    }

    @Test
    fun isDownloadableFile_returns_true_for_obb() {
        assertTrue(DownloadUtils.isDownloadableFile("main.123.com.example.obb"))
        assertTrue(DownloadUtils.isDownloadableFile("patch.456.com.example.OBB"))
    }

    @Test
    fun isDownloadableFile_returns_true_for_7z_archives() {
        assertTrue(DownloadUtils.isDownloadableFile("game.7z"))
        assertTrue(DownloadUtils.isDownloadableFile("game.7z.001"))
        assertTrue(DownloadUtils.isDownloadableFile("game.7z.002"))
        assertTrue(DownloadUtils.isDownloadableFile("archive.7Z.003"))
    }

    @Test
    fun isDownloadableFile_returns_false_for_other_files() {
        assertFalse(DownloadUtils.isDownloadableFile("readme.txt"))
        assertFalse(DownloadUtils.isDownloadableFile("screenshot.png"))
        assertFalse(DownloadUtils.isDownloadableFile("notes.txt"))
        assertFalse(DownloadUtils.isDownloadableFile("index.html"))
    }

    // ========== shouldSkipEntry Tests ==========

    @Test
    fun shouldSkipEntry_returns_true_for_hidden_files() {
        assertTrue(DownloadUtils.shouldSkipEntry(".hidden"))
        assertTrue(DownloadUtils.shouldSkipEntry(".gitignore"))
    }

    @Test
    fun shouldSkipEntry_returns_true_for_underscored_files() {
        assertTrue(DownloadUtils.shouldSkipEntry("_metadata"))
        assertTrue(DownloadUtils.shouldSkipEntry("_internal"))
    }

    @Test
    fun shouldSkipEntry_returns_true_for_parent_directory() {
        assertTrue(DownloadUtils.shouldSkipEntry("../"))
    }

    @Test
    fun shouldSkipEntry_returns_true_for_notes_and_screenshots() {
        assertTrue(DownloadUtils.shouldSkipEntry("notes.txt"))
        assertTrue(DownloadUtils.shouldSkipEntry("screenshot.jpg"))
        assertTrue(DownloadUtils.shouldSkipEntry("screenshot_1.png"))
    }

    @Test
    fun shouldSkipEntry_returns_false_for_valid_entries() {
        assertFalse(DownloadUtils.shouldSkipEntry("game.apk"))
        assertFalse(DownloadUtils.shouldSkipEntry("game.7z.001"))
        assertFalse(DownloadUtils.shouldSkipEntry("data/"))
    }

    // ========== calculateRequiredStorage Tests ==========

    @Test
    fun calculateRequiredStorage_7z_keepApk_uses_3_5x_multiplier() {
        // 3.5x accounts for: archive parts (1x) + combined.7z (1x for multi-part) + extracted (~1.2x) + APK copy (~0.1-0.3x)
        val totalBytes = 1_000_000_000L // 1 GB
        val result = DownloadUtils.calculateRequiredStorage(
            totalBytes = totalBytes,
            isSevenZArchive = true,
            keepApkOrDownloadOnly = true
        )
        assertEquals((totalBytes * 3.5).toLong(), result)
    }

    @Test
    fun calculateRequiredStorage_7z_noKeep_uses_2_5x_multiplier() {
        // 2.5x accounts for: archive parts (1x) + combined.7z (1x for multi-part) + extracted (~1.2x)
        val totalBytes = 1_000_000_000L // 1 GB
        val result = DownloadUtils.calculateRequiredStorage(
            totalBytes = totalBytes,
            isSevenZArchive = true,
            keepApkOrDownloadOnly = false
        )
        assertEquals((totalBytes * 2.5).toLong(), result)
    }

    @Test
    fun calculateRequiredStorage_nonArchive_uses_1_1x_multiplier() {
        val totalBytes = 1_000_000_000L // 1 GB
        val result = DownloadUtils.calculateRequiredStorage(
            totalBytes = totalBytes,
            isSevenZArchive = false,
            keepApkOrDownloadOnly = false
        )
        assertEquals((totalBytes * 1.1).toLong(), result)
    }

    // ========== HREF_REGEX Tests ==========

    @Test
    fun hrefRegex_matches_standard_href() {
        val html = """<a href="game.apk">Download</a>"""
        val matches = DownloadUtils.HREF_REGEX.findAll(html).map { it.groupValues[1] }.toList()
        assertEquals(listOf("game.apk"), matches)
    }

    @Test
    fun hrefRegex_matches_multiple_hrefs() {
        val html = """
            <a href="game.7z.001">Part 1</a>
            <a href="game.7z.002">Part 2</a>
            <a href="game.7z.003">Part 3</a>
        """.trimIndent()
        val matches = DownloadUtils.HREF_REGEX.findAll(html).map { it.groupValues[1] }.toList()
        assertEquals(listOf("game.7z.001", "game.7z.002", "game.7z.003"), matches)
    }

    @Test
    fun hrefRegex_handles_case_insensitive() {
        val html = """<a HREF="game.apk">Download</a>"""
        val matches = DownloadUtils.HREF_REGEX.findAll(html).map { it.groupValues[1] }.toList()
        assertEquals(listOf("game.apk"), matches)
    }

    @Test
    fun hrefRegex_handles_spaces_around_equals() {
        val html = """<a href = "game.apk">Download</a>"""
        val matches = DownloadUtils.HREF_REGEX.findAll(html).map { it.groupValues[1] }.toList()
        assertEquals(listOf("game.apk"), matches)
    }

    // ========== Buffer Size and Semaphore Constants Tests ==========

    @Test
    fun downloadBufferSize_is_64KB() {
        assertEquals(
            "Buffer size should be 64KB (8192 * 8)",
            64 * 1024, // 64KB
            DownloadUtils.DOWNLOAD_BUFFER_SIZE
        )
    }

    @Test
    fun maxConcurrentHeadRequests_is_5() {
        assertEquals(
            "Max concurrent HEAD requests should be 5",
            5,
            DownloadUtils.MAX_CONCURRENT_HEAD_REQUESTS
        )
    }
}
