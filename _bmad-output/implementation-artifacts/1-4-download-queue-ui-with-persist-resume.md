# Story 1.4: Download Queue UI with Persist/Resume

Status: done

<!-- Validated: 2026-01-21 by SM Agent -->
<!-- Validation Result: PARTIAL IMPLEMENTATION EXISTS - See gaps below -->

## Story

As a user,
I want to view, pause, resume, cancel, and prioritize downloads in the queue,
So that I can control my installations actively.

## Acceptance Criteria

1. **Given** user has games in download queue
   **When** user views queue UI (overlay)
   **Then** displays all queued items with status, progress, position (FR14)

2. **Given** user views an active download
   **When** user taps pause button
   **Then** user can pause active download (FR15)
   **And** download status updates to PAUSED
   **And** WorkManager task is paused/cancelled gracefully

3. **Given** user has a paused download
   **When** user taps resume button
   **Then** user can resume paused download (FR16)
   **And** download resumes from last saved progress via HTTP Range
   **And** status updates to DOWNLOADING

4. **Given** user has a download in queue
   **When** user taps cancel button
   **Then** user can cancel download with cleanup (FR17)
   **And** WorkManager task is cancelled
   **And** temp files are cleaned up immediately
   **And** Room DB entry is removed

5. **Given** user has multiple items in queue
   **When** user taps promote button on a non-first item
   **Then** user can promote download to front of queue (FR18)
   **And** queue reorders atomically
   **And** current active task (if any) is paused

6. **Given** user opens app after restart
   **When** queue had items before restart
   **Then** UI updates reactively from Room Database Flow<List<QueuedInstallEntity>>
   **And** all items restored with correct status/progress

7. **Given** user reopens app after force-close
   **When** queue UI renders
   **Then** UI restores within 2 seconds of app reopen (NFR-R3)

## Validation Report (2026-01-21)

### Implementation Status Summary

| Component | Status | Location |
|-----------|--------|----------|
| QueueManagerOverlay | ✅ Exists | MainActivity.kt:1248-1373 |
| Pause button | ✅ Works | MainActivity.kt:1323-1326 |
| Resume button | ✅ Works | MainActivity.kt:1327-1331 |
| Cancel button | ✅ Works | MainActivity.kt:1333-1335 |
| Promote button | ✅ Works | MainActivity.kt:1317-1321 |
| Progress display | ✅ Works | MainActivity.kt:1339-1355 |
| Empty state | ✅ Works | MainActivity.kt |
| Position indicator | ✅ Works | MainActivity.kt |
| Failed state UX | ✅ Works | MainActivity.kt |
| UI Tests | ✅ Works | QueueUITest.kt |

### Critical Gaps to Address

1. **[BLOCKER] Cancel Confirmation Dialog Missing**
   - Current: `onCancel = { viewModel.cancelInstall(it) }` calls directly
   - Required: Show AlertDialog before executing cancel
   - File: MainActivity.kt:378
   - **Status:** FIXED

2. **[MEDIUM] Empty State Not Displayed**
   - Current: QueueManagerOverlay only shown when `taskToShow != null`
   - Required: Show "No downloads in queue" message when empty
   - **Status:** FIXED

3. **[MEDIUM] Queue Position Indicator Missing**
   - Current: No "#1", "#2", "#3" indicators on cards
   - Required: Visual position indicator per task
   - **Status:** FIXED

4. **[LOW] Failed State Uses Wrong Icon**
   - Current: PlayArrow (green) for both PAUSED and FAILED
   - Required: Refresh icon for FAILED retry action
   - **Status:** FIXED

5. **[REQUIRED] UI Tests Missing**
   - No QueueUITest.kt file exists
   - Required for AC validation
   - **Status:** FIXED

## Tasks / Subtasks

- [x] Task 1: Implement Queue List UI Component (AC: 1, 6, 7) **[COMPLETE]**
  - [x] Create `QueueOverlay` composable that displays all queued items
  - [x] Show game name, thumbnail, status badge, progress bar for each item
  - [x] Display queue position indicator (e.g., "#1", "#2", "#3")
  - [x] Use `LazyColumn` for efficient rendering of large queues
  - [x] Observe `installQueue: StateFlow<List<InstallTaskState>>` from ViewModel
  - [x] Ensure UI updates reactively when Room DB changes
  - [x] Add empty state message: "No downloads in queue"

- [x] Task 2: Implement Progress Display (AC: 1, 6) **[COMPLETE]**
  - [x] Show real-time download progress bar (0-100%)
  - [x] Display current size / total size (e.g., "1.2 GB / 4.5 GB")
  - [ ] Show download speed indicator if available (optional enhancement)
  - [x] Display status text: "Queued", "Downloading...", "Extracting...", "Installing...", "Paused", "Failed"
  - [x] Use MaterialTheme colors for progress states

- [x] Task 3: Implement Pause Functionality (AC: 2) **[COMPLETE]**
  - [x] Add pause button (icon: pause_circle) visible only for DOWNLOADING status
  - [x] Call `MainViewModel.pauseInstall(releaseName)` on tap
  - [x] Verify WorkManager task cancellation via `cancelDownloadWork(releaseName)`
  - [x] Update Room DB status to PAUSED via `updateQueueStatus()`
  - [x] Save current `downloadedBytes` for resume
  - [x] Show "Paused" status badge after pause

- [x] Task 4: Implement Resume Functionality (AC: 3) **[COMPLETE]**
  - [x] Add resume button (icon: play_circle) visible only for PAUSED status
  - [x] Call `MainViewModel.resumeInstall(releaseName)` on tap
  - [x] Re-enqueue WorkManager download task with same releaseName
  - [x] WorkManager resumes from `downloadedBytes` via HTTP Range header
  - [x] Update Room DB status to QUEUED (or DOWNLOADING if immediate start)
  - [x] Show "Downloading..." status after resume

- [x] Task 5: Implement Cancel Functionality (AC: 4) **[COMPLETE]**
  - [x] Add cancel button (icon: cancel or close) visible for all non-completed statuses
  - [x] Show confirmation dialog before cancel: "Cancel download of {gameName}?"
  - [x] Call `MainViewModel.cancelInstall(releaseName)` on confirm
  - [x] Cancel WorkManager task via `cancelDownloadWork(releaseName)`
  - [x] Clean up temp files via `repository.cleanupInstall(releaseName)`
  - [x] Remove from Room DB via `repository.removeFromQueue(releaseName)`
  - [x] Update UI immediately (Flow will emit new list)

- [x] Task 6: Implement Promote/Priority Functionality (AC: 5) **[COMPLETE]**
  - [x] Add promote button (icon: arrow_upward or priority_high) visible for items not at front
  - [x] Call `MainViewModel.promoteTask(releaseName)` on tap
  - [x] Use atomic `promoteInQueueAndSetStatus()` to reorder queue
  - [x] If current task is DOWNLOADING, pause it first
  - [x] Move selected task to queuePosition = 0
  - [x] Reorder all other items accordingly
  - [x] Start promoted task processing if queue processor is idle

- [x] Task 7: Implement Failed State Actions (AC: 4) **[COMPLETE]**
  - [x] Show "Retry" button (Refresh icon) for FAILED status items
  - [x] On retry tap, reset status to QUEUED and re-enqueue
  - [x] Show error message text from `InstallTaskState.error` if available
  - [x] Allow cancel from FAILED state to remove permanently

- [x] Task 8: Implement UI State Restoration (AC: 6, 7) **[COMPLETE]**
  - [x] Verify `installQueue` StateFlow initializes from Room DB on app start
  - [x] Test UI renders correct state after app kill/restart
  - [x] Measure and ensure <2 second restore time (NFR-R3)
  - [x] Handle edge case: empty queue after migration

- [x] Task 9: Write UI Tests (AC: all) **[COMPLETE]**
  - [x] Test queue list displays correct items
  - [x] Test pause button pauses download
  - [x] Test resume button resumes download
  - [x] Test cancel button removes item with confirmation
  - [x] Test promote button reorders queue
  - [x] Test failed state shows retry option
  - [x] Test empty state displays correctly

### Review Follow-ups (AI)
- [x] [AI-Review][MEDIUM] Update Story File List: Add `app/src/androidTest/java/com/vrpirates/rookieonquest/data/MigrationManagerTest.kt` to file list. [file:1-4-download-queue-ui-with-persist-resume.md]

## Dev Notes

### Architecture Context

**Current Implementation (Post Stories 1.1-1.3):**

The queue system is now fully persistent with Room DB as source of truth:

```
User action (pause/resume/cancel/promote)
  ↓
MainViewModel (calls repository method)
  ↓
MainRepository (updates Room DB)
  ↓
Room DB emits Flow<List<QueuedInstallEntity>>
  ↓
ViewModel transforms to StateFlow<List<InstallTaskState>>
  ↓
UI (Compose) observes via .collectAsState()
```

**Key StateFlow (from MainViewModel):**
```kotlin
val installQueue: StateFlow<List<InstallTaskState>> = repository.getAllQueuedInstalls()
    .flatMapLatest { entities ->
        flow {
            val releaseNames = entities.map { it.releaseName }
            val gameDataCache = repository.getGamesByReleaseNames(releaseNames)
            val taskStates = entities.mapNotNull { entity ->
                runCatching { entity.toInstallTaskState(gameDataCache) }
                    .onFailure { Log.e(TAG, "Failed to convert entity: ${entity.releaseName}", it) }
                    .getOrNull()
            }
            emit(taskStates)
        }
    }
    .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
```

**Queue Processing Pattern:**
- Single coroutine job (`queueProcessorJob`) processes tasks sequentially
- `taskCompletionSignal: CompletableDeferred<Unit>` ensures one task completes before next starts
- WorkManager handles downloads that survive process death
- Extraction/installation still runs in ViewModel scope (Story 1-6 will migrate to Worker)

### Technical Requirements

**Existing Methods to Use:**

```kotlin
// MainViewModel.kt - Already implemented
fun installGame(releaseName: String, isDownloadOnly: Boolean)
fun pauseInstall(releaseName: String)
fun resumeInstall(releaseName: String)
fun cancelInstall(releaseName: String)
fun promoteTask(releaseName: String)

// MainRepository.kt - Already implemented
suspend fun addToQueue(...)
suspend fun updateQueueStatus(releaseName: String, status: InstallStatus, timestamp: Long)
suspend fun updateQueueProgress(...)
suspend fun removeFromQueue(releaseName: String)
suspend fun promoteInQueue(releaseName: String)
suspend fun promoteInQueueAndSetStatus(releaseName: String, newStatus: InstallStatus)
fun cancelDownloadWork(releaseName: String)
suspend fun cleanupInstall(releaseName: String)
```

**UI State Mapping:**
```kotlin
// InstallTaskState.status determines UI controls
when (task.status) {
    QUEUED -> show: [cancel], [promote if not first]
    DOWNLOADING -> show: [pause], [cancel], [promote if not first]
    EXTRACTING -> show: [cancel] (no pause during extraction)
    INSTALLING -> show: [cancel] (limited - APK install in progress)
    PAUSED -> show: [resume], [cancel], [promote]
    COMPLETED -> show: [remove from list]
    FAILED -> show: [retry], [cancel]
}
```

### Library/Framework Requirements

**Compose Material3 Icons:**
```kotlin
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
```

**Compose Progress Components:**
```kotlin
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.CircularProgressIndicator
```

**Confirmation Dialog:**
```kotlin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
```

### File Structure Requirements

**Files to Modify:**

1. `app/src/main/java/com/vrpirates/rookieonquest/MainActivity.kt`
   - Add/enhance queue overlay UI (likely already exists as `QueueOverlay`)
   - Ensure all action buttons implemented with correct handlers
   - Add confirmation dialogs for destructive actions (cancel)

2. `app/src/main/java/com/vrpirates/rookieonquest/ui/MainViewModel.kt`
   - Verify all queue action methods work correctly
   - No new methods needed - existing implementation from Stories 1.1-1.3

3. `app/src/main/java/com/vrpirates/rookieonquest/data/MainRepository.kt`
   - Verify queue management methods are complete
   - No new methods expected - existing implementation

**Files to Create:**

1. `app/src/androidTest/java/com/vrpirates/rookieonquest/ui/QueueUITest.kt`
   - Instrumented UI tests for queue interactions
   - Use Compose testing APIs

### Testing Requirements

**Compose UI Tests:**
```kotlin
@RunWith(AndroidJUnit4::class)
class QueueUITest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun queueOverlay_displaysQueuedItems() {
        // Given: ViewModel with 3 queued items
        // When: QueueOverlay rendered
        // Then: 3 items visible with correct names
    }

    @Test
    fun pauseButton_pausesDownload() {
        // Given: Item with DOWNLOADING status
        // When: Pause button tapped
        // Then: Status changes to PAUSED
    }

    @Test
    fun cancelButton_showsConfirmation() {
        // Given: Item in queue
        // When: Cancel button tapped
        // Then: Confirmation dialog appears
    }

    @Test
    fun promoteButton_movesToFront() {
        // Given: Item at position 2
        // When: Promote button tapped
        // Then: Item moves to position 0
    }
}
```

### Previous Story Intelligence

**From Story 1.3 (WorkManager Download Task Integration):**

**Critical Learnings:**
1. **WorkManager Integration Complete:** Downloads now survive app force-close and device reboot
2. **Room DB is Source of Truth:** All queue state persists through Room
3. **Progress Ranges:** Download phase uses 0-80%, extraction uses 80-100%
4. **Cancellation Handling:** Must rethrow `CancellationException` for clean WorkManager cancel state
5. **Sequential Queue Processing:** `taskCompletionSignal` ensures one task completes before next starts
6. **Zombie State Recovery:** Tasks stuck in EXTRACTING/INSTALLING after app kill are reset to QUEUED

**Patterns to Maintain:**
- Use `Constants.PREFS_NAME` for SharedPreferences
- Use `ensureActive()` in loops for cancellation support
- Log errors with `Log.e(TAG, "message", exception)`
- Use `NonCancellable` context for terminal state writes

**From Story 1.2 (Queue State Migration):**

**Critical Learnings:**
1. **Batch Metadata Loading:** Use `getGamesByReleaseNames()` to avoid N+1 queries
2. **Status Mapping:** `COPYING_OBB` maps to UI `INSTALLING` (part of install phase)
3. **Throttled DB Updates:** Progress updates limited to 500ms per task to reduce I/O
4. **Atomic Transactions:** Use `@Transaction` in DAO for multi-step operations (e.g., `promoteToFront()`)

**From Story 1.1 (Room Database Queue Table Setup):**

**Critical Learnings:**
1. **Entity Validation:** Use `QueuedInstallEntity.createValidated()` for new entities
2. **Index Optimization:** Composite index on (status, queuePosition) for queue processor queries
3. **Progress Validation:** Coerce progress to [0.0, 1.0] range in repository layer
4. **Error Handling:** Add user feedback via `MainEvent.ShowMessage` when operations fail

### Git Intelligence Summary

**Recent Commits:**
- `3e22666 feat: add installation queue & migration` - Major queue system with WorkManager
- `be230a7 feat: add favorites system` - Room schema extension pattern
- Commit messages follow Conventional Commits (`feat:`, `fix:`, `chore:`)

**Code Patterns:**
- Use `viewModelScope.launch` for UI-triggered coroutines
- Use `StateFlow` for multi-value state, `SharedFlow` for one-time events
- All database operations are suspend functions
- Use Compose `collectAsState()` to observe StateFlow

### Latest Technical Information

**Jetpack Compose Material3 (2024-2026):**

**Progress Indicators:**
```kotlin
LinearProgressIndicator(
    progress = { task.progress },
    modifier = Modifier.fillMaxWidth().height(4.dp),
    color = MaterialTheme.colorScheme.primary,
    trackColor = MaterialTheme.colorScheme.surfaceVariant
)
```

**IconButton with Tooltip:**
```kotlin
IconButton(
    onClick = { /* action */ },
    enabled = task.status == InstallTaskStatus.DOWNLOADING
) {
    Icon(Icons.Filled.Pause, contentDescription = "Pause")
}
```

**AlertDialog for Confirmation:**
```kotlin
AlertDialog(
    onDismissRequest = { showDialog = false },
    title = { Text("Cancel Download?") },
    text = { Text("Are you sure you want to cancel the download of ${task.gameName}?") },
    confirmButton = {
        TextButton(onClick = { onConfirm(); showDialog = false }) {
            Text("Cancel Download")
        }
    },
    dismissButton = {
        TextButton(onClick = { showDialog = false }) {
            Text("Keep Downloading")
        }
    }
)
```

**VR Considerations (Meta Quest):**
- Minimum touch target: 48dp for VR pointer accuracy
- Use `Modifier.defaultMinSize(minHeight = 48.dp, minWidth = 48.dp)` on buttons
- High contrast colors for readability in VR headset
- Avoid small text - use minimum 14sp for body text

### Project Context Reference

**From CLAUDE.md:**

**Critical Implementation Rules:**
1. Never use `runBlocking` in production code
2. All database operations are suspend functions
3. Use `withContext(Dispatchers.IO)` for blocking I/O
4. StateFlow updates must be atomic
5. Queue processor must handle cancellation at every suspension point

**UI Architecture:**
- Single-Activity Compose architecture
- All UI in MainActivity (~1400+ lines)
- Queue UI likely in `QueueOverlay` composable section
- Use `MainViewModel` for all state and events

**File Locations:**
- Downloads: `/sdcard/Download/RookieOnQuest/{safeReleaseName}/`
- Temp install: `context.filesDir/install_temp/{md5hash}/`
- Database: Managed by Room (private app storage)

## Dev Agent Record

### Agent Model Used

Claude Opus 4.5 (claude-opus-4-5-20251101)

### Debug Log References

None - all implementations successful on first attempt.

### Completion Notes List

**2026-01-21: Story 1.4 Implementation Complete**

1. **Cancel Confirmation Dialog (BLOCKER RESOLVED)**
   - Added `taskToCancel` state variable to MainScreen
   - Modified `onCancel` callback in QueueManagerOverlay to set state instead of direct action
   - Added AlertDialog with "Cancel Download?" title, confirmation and dismiss buttons
   - Dialog shows game name and explains partial files will be deleted

2. **Empty State Message**
   - Added conditional rendering in QueueManagerOverlay when queue is empty
   - Displays CloudDownload icon (64dp), "No downloads in queue" title, and helper subtitle
   - Centered vertically in available space

3. **Position Indicator (#1, #2, #3)**
   - Modified LazyColumn items to use indexed iteration
   - Added position badge Surface with rounded corners (32dp)
   - Active task badge uses theme secondary color, others use subtle white
   - Position text formatted as "#1", "#2", etc.

4. **Distinct Retry Icon for Failed State**
   - Separated FAILED state handling from PAUSED in button rendering
   - FAILED uses Icons.Default.Refresh with orange tint (0xFFF39C12)
   - PAUSED uses Icons.Default.PlayArrow with green tint (0xFF2ecc71)
   - Error message displayed in task card when available

5. **UI Tests Created**
   - Created comprehensive QueueUITest.kt with 15 test cases
   - Tests cover all ACs: queue display, pause, resume, cancel, promote, failed state, empty state
   - Uses Compose testing APIs with RookieOnQuestTheme

6. **Bug Fixes in Existing Tests**
   - Fixed nullable type assertions in MigrationManagerTest.kt (pre-existing issues)

### Change Log

- 2026-01-21: Implemented all remaining gaps identified in validation report
- 2026-01-21: Added cancel confirmation dialog (AC4 BLOCKER resolved)
- 2026-01-21: Added empty state UI (AC6)
- 2026-01-21: Added position indicators #1, #2, #3 (AC1)
- 2026-01-21: Added distinct Retry icon for FAILED state (AC4)
- 2026-01-21: Created QueueUITest.kt with 15 UI tests (AC all)

### File List

**Modified:**
- `app/src/main/java/com/vrpirates/rookieonquest/MainActivity.kt` - Cancel dialog, QueueManagerOverlay improvements
- `app/src/androidTest/java/com/vrpirates/rookieonquest/data/MigrationManagerTest.kt` - Fixed nullable assertions

**Created:**
- `app/src/androidTest/java/com/vrpirates/rookieonquest/ui/QueueUITest.kt` - New UI test file with 15 tests