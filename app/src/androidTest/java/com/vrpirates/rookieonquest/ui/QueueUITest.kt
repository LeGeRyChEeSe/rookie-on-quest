package com.vrpirates.rookieonquest.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vrpirates.rookieonquest.QueueManagerOverlay
import com.vrpirates.rookieonquest.ui.theme.RookieOnQuestTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * UI Tests for QueueManagerOverlay composable
 *
 * Tests cover:
 * - AC1: Queue list displays items with status, progress, position
 * - AC2: Pause button visibility for active downloads
 * - AC3: Resume button visibility for paused downloads
 * - AC4: Cancel button with confirmation (dialog tested separately)
 * - AC5: Promote button visibility for non-first items
 * - AC6/7: Empty state and UI restoration
 */
@RunWith(AndroidJUnit4::class)
class QueueUITest {

    @get:Rule
    val composeTestRule = createComposeRule()

    // Test data helpers
    private fun createTestTask(
        releaseName: String,
        gameName: String,
        status: InstallTaskStatus,
        progress: Float = 0f,
        error: String? = null
    ) = InstallTaskState(
        releaseName = releaseName,
        gameName = gameName,
        packageName = "com.test.$releaseName",
        status = status,
        progress = progress,
        error = error
    )

    // ========== AC1: Queue displays all items with status, progress, position ==========

    @Test
    fun queueOverlay_displaysAllQueuedItems() {
        val queue = listOf(
            createTestTask("game1", "Beat Saber", InstallTaskStatus.DOWNLOADING, 0.5f),
            createTestTask("game2", "Superhot VR", InstallTaskStatus.QUEUED),
            createTestTask("game3", "Pistol Whip", InstallTaskStatus.QUEUED)
        )

        composeTestRule.setContent {
            RookieOnQuestTheme {
                QueueManagerOverlay(
                    queue = queue,
                    viewedReleaseName = null,
                    onTaskClick = {},
                    onCancel = {},
                    onPause = {},
                    onResume = {},
                    onPromote = {},
                    onClose = {}
                )
            }
        }

        // Verify all games are displayed
        composeTestRule.onNodeWithText("Beat Saber").assertIsDisplayed()
        composeTestRule.onNodeWithText("Superhot VR").assertIsDisplayed()
        composeTestRule.onNodeWithText("Pistol Whip").assertIsDisplayed()

        // Verify queue count in header
        composeTestRule.onNodeWithText("INSTALLATION QUEUE (3)").assertIsDisplayed()
    }

    @Test
    fun queueOverlay_displaysPositionIndicators() {
        val queue = listOf(
            createTestTask("game1", "Game One", InstallTaskStatus.DOWNLOADING, 0.5f),
            createTestTask("game2", "Game Two", InstallTaskStatus.QUEUED),
            createTestTask("game3", "Game Three", InstallTaskStatus.QUEUED)
        )

        composeTestRule.setContent {
            RookieOnQuestTheme {
                QueueManagerOverlay(
                    queue = queue,
                    viewedReleaseName = null,
                    onTaskClick = {},
                    onCancel = {},
                    onPause = {},
                    onResume = {},
                    onPromote = {},
                    onClose = {}
                )
            }
        }

        // Verify position indicators are displayed
        composeTestRule.onNodeWithText("#1").assertIsDisplayed()
        composeTestRule.onNodeWithText("#2").assertIsDisplayed()
        composeTestRule.onNodeWithText("#3").assertIsDisplayed()
    }

    // ========== AC2: Pause button for active downloads ==========

    @Test
    fun pauseButton_visibleForDownloadingStatus() {
        val queue = listOf(
            createTestTask("game1", "Downloading Game", InstallTaskStatus.DOWNLOADING, 0.5f)
        )

        composeTestRule.setContent {
            RookieOnQuestTheme {
                QueueManagerOverlay(
                    queue = queue,
                    viewedReleaseName = null,
                    onTaskClick = {},
                    onCancel = {},
                    onPause = {},
                    onResume = {},
                    onPromote = {},
                    onClose = {}
                )
            }
        }

        // Pause button should be visible for downloading task
        composeTestRule.onNodeWithContentDescription("Pause").assertIsDisplayed()
    }

    @Test
    fun pauseButton_triggersCallback() {
        var pausedReleaseName: String? = null
        val queue = listOf(
            createTestTask("game1", "Downloading Game", InstallTaskStatus.DOWNLOADING, 0.5f)
        )

        composeTestRule.setContent {
            RookieOnQuestTheme {
                QueueManagerOverlay(
                    queue = queue,
                    viewedReleaseName = null,
                    onTaskClick = {},
                    onCancel = {},
                    onPause = { pausedReleaseName = it },
                    onResume = {},
                    onPromote = {},
                    onClose = {}
                )
            }
        }

        composeTestRule.onNodeWithContentDescription("Pause").performClick()
        assert(pausedReleaseName == "game1") { "Expected game1 to be paused, got $pausedReleaseName" }
    }

    // ========== AC3: Resume button for paused downloads ==========

    @Test
    fun resumeButton_visibleForPausedStatus() {
        val queue = listOf(
            createTestTask("game1", "Paused Game", InstallTaskStatus.PAUSED, 0.3f)
        )

        composeTestRule.setContent {
            RookieOnQuestTheme {
                QueueManagerOverlay(
                    queue = queue,
                    viewedReleaseName = null,
                    onTaskClick = {},
                    onCancel = {},
                    onPause = {},
                    onResume = {},
                    onPromote = {},
                    onClose = {}
                )
            }
        }

        // Resume button (PlayArrow) should be visible for paused task
        composeTestRule.onNodeWithContentDescription("Resume").assertIsDisplayed()
    }

    @Test
    fun resumeButton_triggersCallback() {
        var resumedReleaseName: String? = null
        val queue = listOf(
            createTestTask("game1", "Paused Game", InstallTaskStatus.PAUSED, 0.3f)
        )

        composeTestRule.setContent {
            RookieOnQuestTheme {
                QueueManagerOverlay(
                    queue = queue,
                    viewedReleaseName = null,
                    onTaskClick = {},
                    onCancel = {},
                    onPause = {},
                    onResume = { resumedReleaseName = it },
                    onPromote = {},
                    onClose = {}
                )
            }
        }

        composeTestRule.onNodeWithContentDescription("Resume").performClick()
        assert(resumedReleaseName == "game1") { "Expected game1 to be resumed, got $resumedReleaseName" }
    }

    // ========== AC4: Cancel button ==========

    @Test
    fun cancelButton_triggersCallback() {
        var cancelledReleaseName: String? = null
        val queue = listOf(
            createTestTask("game1", "Queued Game", InstallTaskStatus.QUEUED)
        )

        composeTestRule.setContent {
            RookieOnQuestTheme {
                QueueManagerOverlay(
                    queue = queue,
                    viewedReleaseName = null,
                    onTaskClick = {},
                    onCancel = { cancelledReleaseName = it },
                    onPause = {},
                    onResume = {},
                    onPromote = {},
                    onClose = {}
                )
            }
        }

        composeTestRule.onNodeWithContentDescription("Cancel").performClick()
        assert(cancelledReleaseName == "game1") { "Expected game1 to be cancelled, got $cancelledReleaseName" }
    }

    // ========== AC5: Promote button for non-first items ==========

    @Test
    fun promoteButton_notVisibleForFirstItem() {
        val queue = listOf(
            createTestTask("game1", "First Game", InstallTaskStatus.QUEUED),
            createTestTask("game2", "Second Game", InstallTaskStatus.QUEUED)
        )

        composeTestRule.setContent {
            RookieOnQuestTheme {
                QueueManagerOverlay(
                    queue = queue,
                    viewedReleaseName = null,
                    onTaskClick = {},
                    onCancel = {},
                    onPause = {},
                    onResume = {},
                    onPromote = {},
                    onClose = {}
                )
            }
        }

        // There should be only ONE promote button (for the second item)
        composeTestRule.onAllNodesWithContentDescription("Prioritize").assertCountEquals(1)
    }

    @Test
    fun promoteButton_triggersCallback() {
        var promotedReleaseName: String? = null
        val queue = listOf(
            createTestTask("game1", "First Game", InstallTaskStatus.QUEUED),
            createTestTask("game2", "Second Game", InstallTaskStatus.QUEUED)
        )

        composeTestRule.setContent {
            RookieOnQuestTheme {
                QueueManagerOverlay(
                    queue = queue,
                    viewedReleaseName = null,
                    onTaskClick = {},
                    onCancel = {},
                    onPause = {},
                    onResume = {},
                    onPromote = { promotedReleaseName = it },
                    onClose = {}
                )
            }
        }

        composeTestRule.onNodeWithContentDescription("Prioritize").performClick()
        assert(promotedReleaseName == "game2") { "Expected game2 to be promoted, got $promotedReleaseName" }
    }

    // ========== AC6: Failed state shows retry option ==========

    @Test
    fun retryButton_visibleForFailedStatus() {
        val queue = listOf(
            createTestTask("game1", "Failed Game", InstallTaskStatus.FAILED, error = "Network error")
        )

        composeTestRule.setContent {
            RookieOnQuestTheme {
                QueueManagerOverlay(
                    queue = queue,
                    viewedReleaseName = null,
                    onTaskClick = {},
                    onCancel = {},
                    onPause = {},
                    onResume = {},
                    onPromote = {},
                    onClose = {}
                )
            }
        }

        // Retry button (Refresh icon) should be visible for failed task
        composeTestRule.onNodeWithContentDescription("Retry").assertIsDisplayed()
    }

    @Test
    fun failedStatus_displaysErrorMessage() {
        val queue = listOf(
            createTestTask("game1", "Failed Game", InstallTaskStatus.FAILED, error = "Network error")
        )

        composeTestRule.setContent {
            RookieOnQuestTheme {
                QueueManagerOverlay(
                    queue = queue,
                    viewedReleaseName = null,
                    onTaskClick = {},
                    onCancel = {},
                    onPause = {},
                    onResume = {},
                    onPromote = {},
                    onClose = {}
                )
            }
        }

        // Error message should be displayed
        composeTestRule.onNodeWithText("Network error").assertIsDisplayed()
    }

    // ========== Empty State ==========

    @Test
    fun emptyState_displayedWhenQueueIsEmpty() {
        composeTestRule.setContent {
            RookieOnQuestTheme {
                QueueManagerOverlay(
                    queue = emptyList(),
                    viewedReleaseName = null,
                    onTaskClick = {},
                    onCancel = {},
                    onPause = {},
                    onResume = {},
                    onPromote = {},
                    onClose = {}
                )
            }
        }

        // Empty state message should be displayed
        composeTestRule.onNodeWithText("No downloads in queue").assertIsDisplayed()
        composeTestRule.onNodeWithText("INSTALLATION QUEUE (0)").assertIsDisplayed()
    }

    // ========== Close Button ==========

    @Test
    fun closeButton_triggersCallback() {
        var closeCalled = false
        val queue = listOf(
            createTestTask("game1", "Test Game", InstallTaskStatus.QUEUED)
        )

        composeTestRule.setContent {
            RookieOnQuestTheme {
                QueueManagerOverlay(
                    queue = queue,
                    viewedReleaseName = null,
                    onTaskClick = {},
                    onCancel = {},
                    onPause = {},
                    onResume = {},
                    onPromote = {},
                    onClose = { closeCalled = true }
                )
            }
        }

        // Click the X close button
        composeTestRule.onNodeWithContentDescription("Close").performClick()
        assert(closeCalled) { "Expected close callback to be triggered" }
    }

    @Test
    fun backToCatalogButton_triggersCloseCallback() {
        var closeCalled = false
        val queue = listOf(
            createTestTask("game1", "Test Game", InstallTaskStatus.QUEUED)
        )

        composeTestRule.setContent {
            RookieOnQuestTheme {
                QueueManagerOverlay(
                    queue = queue,
                    viewedReleaseName = null,
                    onTaskClick = {},
                    onCancel = {},
                    onPause = {},
                    onResume = {},
                    onPromote = {},
                    onClose = { closeCalled = true }
                )
            }
        }

        composeTestRule.onNodeWithText("BACK TO CATALOG").performClick()
        assert(closeCalled) { "Expected close callback to be triggered" }
    }
}
