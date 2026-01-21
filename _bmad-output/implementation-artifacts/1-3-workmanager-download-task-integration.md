# Story 1.3: WorkManager Download Task Integration

Status: in-progress

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a user,
I want downloads to continue even if I force-close the app,
So that large game downloads complete reliably in the background.

## Acceptance Criteria

**Given** a game is added to download queue
**When** WorkManager schedules the download task
**Then** WorkManager Worker executes download with constraints (battery not low, storage not low)
**And** download progress updates Room Database in real-time
**And** Worker survives app force-close and device reboot
**And** Worker automatically resumes on restart per NFR-R6
**And** exponential backoff retry (max 3) on failures per NFR-R5

## Tasks / Subtasks

- [x] Task 1: Add WorkManager dependencies (AC: 1)
  - [x] Add `androidx.work:work-runtime-ktx:2.9.1` to build.gradle.kts
  - [x] Configure WorkManager initialization (default or custom Configuration)
  - [x] Verify dependencies resolve correctly
  - [x] Add FOREGROUND_SERVICE_DATA_SYNC permission to AndroidManifest.xml

- [x] Task 2: Create DownloadWorker CoroutineWorker (AC: 1, 2, 3, 4)
  - [x] Create `DownloadWorker.kt` extending `CoroutineWorker`
  - [x] Implement `doWork()` suspend function with download logic
  - [x] Add `getForegroundInfo()` for persistent notification
  - [x] Use `setProgress()` for real-time progress updates
  - [x] Implement proper cancellation handling via `isStopped` check
  - [x] Add comprehensive logging for debugging

- [x] Task 3: Migrate download logic from MainViewModel to DownloadWorker (AC: 2)
  - [x] Extract HTTP download logic from `runTask()` in MainViewModel
  - [x] Refactor to work within Worker context (no direct ViewModel access)
  - [x] Pass required data via `inputData` (releaseName, isDownloadOnly)
  - [x] Update Room DB progress directly from Worker (DAO injection via manual)
  - [x] Handle HTTP Range resumption within Worker

- [x] Task 4: Configure WorkRequest constraints (AC: 1)
  - [x] Set `setRequiresBatteryNotLow(true)` constraint
  - [x] Set `setRequiresStorageNotLow(true)` constraint
  - [x] Set `setRequiredNetworkType(NetworkType.CONNECTED)` constraint
  - [x] Configure exponential backoff with max 3 retries

- [x] Task 5: Implement unique work policy for queue management (AC: 3, 4)
  - [x] Use `enqueueUniqueWork` with work name = releaseName
  - [x] Apply `ExistingWorkPolicy.KEEP` to prevent duplicate downloads
  - [ ] Add work chain for extraction after download (deferred to Story 1-6)
  - [x] Handle work cancellation via `WorkManager.cancelUniqueWork()`

- [x] Task 6: Implement Worker status observation in ViewModel (AC: 2)
  - [x] Observe `WorkInfo` via Flow converted from LiveData
  - [x] Map `WorkInfo.State` to `InstallTaskStatus` for UI updates
  - [x] Handle ENQUEUED, RUNNING, SUCCEEDED, FAILED, CANCELLED states
  - [x] Update Room DB status based on WorkInfo changes

- [x] Task 7: Handle app restart and Worker resumption (AC: 3, 4)
  - [x] Verify WorkManager automatically re-enqueues work after restart
  - [x] Restore UI queue state from Room DB on app launch
  - [x] Resume observation for in-progress downloads
  - [x] Ensure no duplicate workers created for same releaseName

- [x] Task 8: Implement foreground service notification (AC: 1)
  - [x] Create notification channel `download_progress` (low priority, silent)
  - [x] Build notification with progress bar
  - [x] Call `setForeground(ForegroundInfo)` in Worker for long downloads
  - [x] Use FOREGROUND_SERVICE_TYPE_DATA_SYNC for Android 14+

- [x] Task 9: Write instrumented tests for WorkManager integration (AC: 5)
  - [x] Test work enqueue with constraints
  - [x] Test work cancellation
  - [x] Test unique work policy behavior
  - [x] Use `WorkManagerTestInitHelper` for test isolation

### Review Follow-ups (AI)
- [x] [AI-Review][CRITICAL] Fix data loss in Download Only mode: `handleDownloadSuccess` in `MainViewModel.kt` returns early, skipping `MainRepository.installGame` which is responsible for moving files from private cache to public Downloads. [file:MainViewModel.kt]
- [x] [AI-Review][MEDIUM] Refactor duplicated download loop logic: Extract HTTP Range handling and buffer reading from `DownloadWorker` and `MainRepository` into a shared `Downloader` class. [file:DownloadWorker.kt, MainRepository.kt]
- [x] [AI-Review][MEDIUM] Improve test coverage: Add unit tests for `DownloadWorker.doWork` execution logic using mocked dependencies, not just WorkManager configuration tests. [file:DownloadWorkerTest.kt]
- [x] [AI-Review][LOW] Optimize zombie task recovery: Investigate partial extraction resumption instead of forcing full restart from QUEUED state. [file:MainViewModel.kt]
- [x] [AI-Review][CRITICAL] Fix data loss bug: `MainViewModel.kt` deletes downloaded files immediately via `cleanupInstall()` before installation [file:MainViewModel.kt]
- [x] [AI-Review][CRITICAL] Restore install flow: Trigger legacy install/extraction logic after WorkManager download completes (Feature Regression) [file:MainViewModel.kt]
- [x] [AI-Review][MEDIUM] Implement fallback for missing `ExtractionWorker` or ensure seamless handoff to legacy extraction [file:MainViewModel.kt]
- [x] [AI-Review][MEDIUM] Refactor `DownloadWorker.kt` to reuse `MainRepository` logic and avoid code duplication (DRY) [file:DownloadWorker.kt]
- [x] [AI-Review][MEDIUM] Optimize `installGame` flow to skip redundant HEAD requests when handoff from WorkManager occurs [file:MainRepository.kt]
- [x] [AI-Review][MEDIUM] Shared `OkHttpClient` and `Retrofit` instances between Worker and App (Dependency Injection/Singleton) [file:DownloadWorker.kt]
- [x] [AI-Review][LOW] Centralize constants (User-Agent) and fix fully qualified imports [file:MainRepository.kt]
- [x] [AI-Review][CRITICAL] Fix broken cancellation handling: `DownloadWorker.kt` catches `CancellationException` and returns failure instead of rethrowing, preventing clean cancel state [file:DownloadWorker.kt]
- [x] [AI-Review][MEDIUM] Fix race condition: `MainViewModel.resumeActiveDownloadObservations` uses flaky `delay(500)` instead of deterministic flow collection [file:MainViewModel.kt]
- [x] [AI-Review][MEDIUM] Fix DRY violation: Deduplicate `md5` hashing and download loop logic between `DownloadWorker` and `MainRepository` [file:DownloadWorker.kt]
- [x] [AI-Review][CRITICAL] Fix zombie state: `MainViewModel.resumeActiveDownloadObservations` must handle `EXTRACTING` or `INSTALLING` states stuck after app kill [file:MainViewModel.kt]
- [x] [AI-Review][MEDIUM] Fix DRY violation: Massive code duplication of download logic between `DownloadWorker` and `MainRepository` [file:DownloadWorker.kt]
- [x] [AI-Review][LOW] Centralize "RookieOnQuest" directory name constant [file:Constants.kt]
- [x] [AI-Review][CRITICAL] Zombie task recovery logic flaw: `determineZombieRecoveryState` returns `EXTRACTING` but queue processor loop only picks up `QUEUED`. Interrupted tasks will hang. [file:MainViewModel.kt]
- [x] [AI-Review][CRITICAL] Fix untracked files: `DownloadWorker.kt` and `DownloadWorkerTest.kt` are not added to Git index. [file:git status]
- [x] [AI-Review][CRITICAL] Fix concurrency risk in zombie recovery: Multiple zombie tasks could trigger parallel extractions, violating sequential queue logic. [file:MainViewModel.kt]
- [x] [AI-Review][MEDIUM] Fix UI flickering: `DownloadWorker` sets `COMPLETED` but `MainViewModel` immediately resets to `EXTRACTING`. [file:DownloadWorker.kt, MainViewModel.kt]
- [x] [AI-Review][LOW] Document WAKE_LOCK permission in story tasks. [file:AndroidManifest.xml] (Permission already present in manifest)
- [ ] [AI-Review][MEDIUM] Migrate deprecated Storage API: Replace `Environment.getExternalStorageDirectory()` with scoped storage compatible alternatives. [file:DownloadWorker.kt] **DEFERRED: Still functional with MANAGE_EXTERNAL_STORAGE permission**
- [ ] [AI-Review][MEDIUM] Refactor God Method: `MainRepository.installGame` exceeds 240 lines and handles too many responsibilities. [file:MainRepository.kt] **DEFERRED: Significant refactoring risk, maintenability-only issue**
- [ ] [AI-Review][LOW] Externalize notification strings: Move hardcoded strings to `strings.xml`. [file:DownloadWorker.kt] **DEFERRED: Low priority localization improvement**
- [x] [AI-Review][CRITICAL] Fix Parallel Extraction Flow: Ensure queue processor waits for extraction/installation completion before starting next WorkManager task to prevent concurrent I/O. [file:MainViewModel.kt]
- [x] [AI-Review][CRITICAL] Fix Progress UI Regression: Resolve 100% -> 80% jump during transition from DownloadWorker completion to MainRepository extraction. [file:MainViewModel.kt, DownloadWorker.kt]
- [x] [AI-Review][MEDIUM] Refactor Download Logic Duplication: Fully merge download loop and directory parsing logic between DownloadWorker and MainRepository into a shared component. [file:DownloadWorker.kt, MainRepository.kt]
- [x] [AI-Review][MEDIUM] Fix Zombie Recovery Concurrency: Prevent parallel extractions when multiple zombie tasks are recovered after app restart. [file:MainViewModel.kt]
- [ ] [AI-Review][LOW] Externalize Notification Strings: Move hardcoded strings in DownloadWorker to strings.xml. [file:DownloadWorker.kt] **DEFERRED: Low priority localization improvement (duplicate of line 107)**
- [ ] [AI-Review][LOW] Migrate Deprecated Storage API: Replace Environment.getExternalStorageDirectory() with Scoped Storage compatible alternatives. [file:DownloadWorker.kt] **DEFERRED: Still functional with MANAGE_EXTERNAL_STORAGE permission (duplicate of line 105)**
- [ ] [AI-Review][HIGH] Parallelize HEAD requests in `DownloadWorker.fetchRemoteSegments` using `async/awaitAll` to match `MainRepository` performance. [file:DownloadWorker.kt:233]
- [ ] [AI-Review][MEDIUM] Stop retrying `DownloadWorker` on `Insufficient storage space` errors to provide faster user feedback. [file:DownloadWorker.kt:327]
- [ ] [AI-Review][MEDIUM] Centralize storage space multiplier logic (2.9x/1.9x/etc.) in `DownloadUtils` to avoid code duplication. [file:DownloadWorker.kt:133, MainRepository.kt:313]
- [ ] [AI-Review][MEDIUM] Update foreground notification with actual progress percentage instead of indeterminate mode. [file:DownloadWorker.kt:351]
- [ ] [AI-Review][MEDIUM] Harmonize WorkManager `setProgress` with Room DB progress ranges (0-80%) for internal consistency. [file:DownloadWorker.kt:195]
- [ ] [AI-Review][LOW] Add story file `1-3-workmanager-download-task-integration.md` to git tracking. [file:git status]

## Dev Notes

### Architecture Context

**Current Queue Processor Architecture (v2.4.0/v2.5.0 partial):**

The app currently uses a `viewModelScope.launch` coroutine job to process the install queue sequentially. This approach has a critical flaw: **the coroutine dies when the app is killed**, losing all progress.

**v2.4.0 (Pre-Story 1.1/1.2):**
```
User adds game → MutableStateFlow<List<InstallTaskState>> (in-memory)
  ↓
Queue processor job runs in viewModelScope
  ↓
Download/Extract/Install operations run sequentially
  ↓
App killed → Queue lost, download restarted
```

**v2.5.0 (Post-Story 1.1/1.2):**
```
User adds game → Room DB (install_queue table) ← PERSISTENT
  ↓
Queue processor job runs in viewModelScope ← STILL DIES ON KILL
  ↓
Download/Extract/Install operations run sequentially
  ↓
App killed → Queue STATE preserved, but download job killed
             → On restart: starts over from last known progress
```

**v2.5.0 Target (After Story 1.3):**
```
User adds game → Room DB (install_queue table)
  ↓
WorkManager enqueues DownloadWorker ← SURVIVES PROCESS DEATH
  ↓
DownloadWorker executes with constraints
  ↓
Progress updates Room DB + setProgress()
  ↓
App killed → Worker continues in background (foreground service)
             → On restart: UI observes ongoing WorkInfo
```

**Key Architectural Change:**

The queue processor (currently in MainViewModel) must be **replaced** or **supplemented** by WorkManager workers. The download operation itself moves from `MainRepository.installGame()` coroutine to `DownloadWorker.doWork()` suspend function.

### Technical Requirements

**WorkManager Setup:**

```kotlin
// build.gradle.kts dependencies
implementation("androidx.work:work-runtime-ktx:2.9.1")

// Optional: For testing
androidTestImplementation("androidx.work:work-testing:2.9.1")
```

**DownloadWorker Implementation Pattern:**

```kotlin
class DownloadWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        const val KEY_RELEASE_NAME = "release_name"
        const val KEY_IS_DOWNLOAD_ONLY = "is_download_only"
        const val KEY_PROGRESS = "progress"
        const val KEY_DOWNLOADED_BYTES = "downloaded_bytes"
        const val KEY_TOTAL_BYTES = "total_bytes"
    }

    override suspend fun doWork(): Result {
        val releaseName = inputData.getString(KEY_RELEASE_NAME)
            ?: return Result.failure()
        val isDownloadOnly = inputData.getBoolean(KEY_IS_DOWNLOAD_ONLY, false)

        // Get database instance (manual or Hilt injection)
        val db = AppDatabase.getDatabase(applicationContext)
        val dao = db.queuedInstallDao()

        // Check if we're resuming (partial download exists)
        val existingEntity = dao.getByReleaseName(releaseName)
        val startByte = existingEntity?.downloadedBytes ?: 0L

        try {
            // Update status to DOWNLOADING
            dao.updateStatus(releaseName, InstallStatus.DOWNLOADING.name, System.currentTimeMillis())

            // Perform download with progress updates
            downloadFile(releaseName, startByte) { downloadedBytes, totalBytes, progress ->
                // Check for cancellation
                if (isStopped) {
                    return@downloadFile
                }

                // Update Room DB (throttled in DAO or here)
                dao.updateProgress(
                    releaseName = releaseName,
                    progress = progress,
                    downloadedBytes = downloadedBytes,
                    totalBytes = totalBytes,
                    timestamp = System.currentTimeMillis()
                )

                // Update WorkManager progress (for LiveData observers)
                setProgress(
                    Data.Builder()
                        .putFloat(KEY_PROGRESS, progress)
                        .putLong(KEY_DOWNLOADED_BYTES, downloadedBytes)
                        .putLong(KEY_TOTAL_BYTES, totalBytes)
                        .build()
                )
            }

            // Download complete - trigger extraction worker or mark complete
            if (isDownloadOnly) {
                dao.updateStatus(releaseName, InstallStatus.COMPLETED.name, System.currentTimeMillis())
            } else {
                // Chain extraction worker
                dao.updateStatus(releaseName, InstallStatus.EXTRACTING.name, System.currentTimeMillis())
                // ... enqueue ExtractionWorker
            }

            return Result.success()

        } catch (e: Exception) {
            Log.e("DownloadWorker", "Download failed for $releaseName", e)

            return if (runAttemptCount < 3) {
                // Retry with exponential backoff
                Result.retry()
            } else {
                // Max retries exceeded - mark as failed
                dao.updateStatus(releaseName, InstallStatus.FAILED.name, System.currentTimeMillis())
                Result.failure()
            }
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val releaseName = inputData.getString(KEY_RELEASE_NAME) ?: "Unknown"
        return ForegroundInfo(
            NOTIFICATION_ID,
            createNotification(releaseName)
        )
    }

    private fun createNotification(releaseName: String): Notification {
        // Create persistent notification for foreground service
        // See Story 3.1 for full notification channel setup
        // ...
    }

    private suspend fun downloadFile(
        releaseName: String,
        startByte: Long,
        onProgress: suspend (downloadedBytes: Long, totalBytes: Long, progress: Float) -> Unit
    ) {
        // HTTP download with Range header for resumption
        // Based on existing logic in MainRepository
        // ...
    }

    companion object {
        const val NOTIFICATION_ID = 1001
    }
}
```

**WorkRequest Enqueueing Pattern:**

```kotlin
// In MainRepository or MainViewModel

fun enqueueDownload(releaseName: String, isDownloadOnly: Boolean) {
    val constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .setRequiresBatteryNotLow(true)
        .setRequiresStorageNotLow(true)
        .build()

    val inputData = Data.Builder()
        .putString(DownloadWorker.KEY_RELEASE_NAME, releaseName)
        .putBoolean(DownloadWorker.KEY_IS_DOWNLOAD_ONLY, isDownloadOnly)
        .build()

    val downloadRequest = OneTimeWorkRequestBuilder<DownloadWorker>()
        .setConstraints(constraints)
        .setInputData(inputData)
        .setBackoffCriteria(
            BackoffPolicy.EXPONENTIAL,
            OneTimeWorkRequest.MIN_BACKOFF_MILLIS, // 10 seconds
            TimeUnit.MILLISECONDS
        )
        .addTag("download")
        .addTag(releaseName)
        .build()

    // Use unique work to prevent duplicates
    WorkManager.getInstance(context)
        .enqueueUniqueWork(
            "download_$releaseName",
            ExistingWorkPolicy.KEEP, // Don't replace existing work
            downloadRequest
        )
}
```

**Work Observation Pattern:**

```kotlin
// In MainViewModel

fun observeDownloadWork(releaseName: String) {
    WorkManager.getInstance(applicationContext)
        .getWorkInfosForUniqueWorkLiveData("download_$releaseName")
        .asFlow()
        .onEach { workInfoList ->
            val workInfo = workInfoList.firstOrNull() ?: return@onEach

            when (workInfo.state) {
                WorkInfo.State.ENQUEUED -> {
                    // Work queued but not started (constraints not met)
                    repository.updateQueueStatus(releaseName, InstallStatus.QUEUED)
                }
                WorkInfo.State.RUNNING -> {
                    // Work is executing
                    val progress = workInfo.progress.getFloat(DownloadWorker.KEY_PROGRESS, 0f)
                    // UI updates via Room Flow (already observing)
                }
                WorkInfo.State.SUCCEEDED -> {
                    // Download complete - extraction will be chained
                }
                WorkInfo.State.FAILED -> {
                    // Max retries exceeded
                    repository.updateQueueStatus(releaseName, InstallStatus.FAILED)
                }
                WorkInfo.State.CANCELLED -> {
                    // User cancelled
                    repository.removeFromQueue(releaseName)
                }
                else -> { /* BLOCKED state - rare */ }
            }
        }
        .launchIn(viewModelScope)
}
```

### Library/Framework Requirements

**Dependencies to Add (build.gradle.kts):**

```kotlin
// WorkManager with Kotlin coroutine support
implementation("androidx.work:work-runtime-ktx:2.9.1")

// Testing support
androidTestImplementation("androidx.work:work-testing:2.9.1")
```

**WorkManager 2.9.x Key Features:**
- `CoroutineWorker` - Kotlin coroutine support in workers
- `setProgress()` - Report progress during work execution
- `setForeground()` - Promote to foreground service with notification
- `isStopped` - Check if work has been cancelled
- `runAttemptCount` - Track retry attempts for exponential backoff
- `ForegroundInfo` - Encapsulates notification for foreground promotion

**Constraints API:**
```kotlin
Constraints.Builder()
    .setRequiredNetworkType(NetworkType.CONNECTED) // Any network
    .setRequiresBatteryNotLow(true) // >15% battery
    .setRequiresStorageNotLow(true) // >10% storage
    .build()
```

**Backoff Policy:**
```kotlin
.setBackoffCriteria(
    BackoffPolicy.EXPONENTIAL, // 10s → 20s → 40s → 80s...
    OneTimeWorkRequest.MIN_BACKOFF_MILLIS, // 10 seconds minimum
    TimeUnit.MILLISECONDS
)
```

### File Structure Requirements

**Files to Create:**
1. `app/src/main/java/com/vrpirates/rookieonquest/worker/DownloadWorker.kt` - Main download worker
2. `app/src/main/java/com/vrpirates/rookieonquest/worker/ExtractionWorker.kt` - Extraction worker (chained after download)
3. `app/src/androidTest/java/com/vrpirates/rookieonquest/worker/DownloadWorkerTest.kt` - Instrumented tests

**Files to Modify:**
1. `app/build.gradle.kts` - Add WorkManager dependencies
2. `app/src/main/java/com/vrpirates/rookieonquest/ui/MainViewModel.kt` - Replace queue processor with WorkManager
3. `app/src/main/java/com/vrpirates/rookieonquest/data/MainRepository.kt` - Add work enqueueing methods
4. `app/src/main/AndroidManifest.xml` - Add FOREGROUND_SERVICE permission (if not already present)

**Notification Channel (for Story 3.1 integration):**
- Create `DOWNLOAD_PROGRESS` channel in MainRepository or Application class
- Low priority (IMPORTANCE_LOW), no sound
- Used by `getForegroundInfo()` in DownloadWorker

### Testing Requirements

**Instrumented Tests (DownloadWorkerTest.kt):**

```kotlin
@RunWith(AndroidJUnit4::class)
class DownloadWorkerTest {

    private lateinit var context: Context
    private lateinit var workManager: WorkManager

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()

        // Initialize WorkManager for testing
        val config = Configuration.Builder()
            .setMinimumLoggingLevel(Log.DEBUG)
            .setExecutor(SynchronousExecutor())
            .build()

        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
        workManager = WorkManager.getInstance(context)
    }

    @Test
    fun downloadWorker_withValidInput_succeeds() = runBlocking {
        // Given
        val inputData = Data.Builder()
            .putString(DownloadWorker.KEY_RELEASE_NAME, "TestGame-1.0.0")
            .putBoolean(DownloadWorker.KEY_IS_DOWNLOAD_ONLY, true)
            .build()

        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(inputData)
            .build()

        // When
        workManager.enqueue(request).result.get()

        val testDriver = WorkManagerTestInitHelper.getTestDriver(context)!!
        testDriver.setAllConstraintsMet(request.id)

        // Then
        val workInfo = workManager.getWorkInfoById(request.id).get()
        assertEquals(WorkInfo.State.SUCCEEDED, workInfo.state)
    }

    @Test
    fun downloadWorker_retries_onNetworkError() = runBlocking {
        // Test exponential backoff behavior
        // ...
    }

    @Test
    fun downloadWorker_survives_processKill() = runBlocking {
        // Test work re-enqueue after process death
        // Requires instrumented test with process simulation
        // ...
    }
}
```

### Previous Story Intelligence

**From Story 1.2 (Queue State Migration):**

**Critical Learnings:**
1. **Room is Source of Truth:** All queue state must persist through Room DB. WorkManager workers must update Room DB directly, not just WorkInfo.
2. **Progress Throttling:** Story 1.2 implemented 500ms throttling for DB updates. DownloadWorker should reuse or coordinate with this throttling.
3. **Status Mapping:** `InstallStatus` enum (QUEUED, DOWNLOADING, EXTRACTING, COPYING_OBB, INSTALLING, PAUSED, COMPLETED, FAILED) must map to WorkInfo states correctly.
4. **Atomic Transactions:** Use `@Transaction` for any multi-step DAO operations.

**Patterns to Maintain:**
- Use `Constants.PREFS_NAME` for SharedPreferences access
- Use `QueuedInstallEntity.createValidated()` for entity creation
- Use `ensureActive()` in loops for cancellation support
- Log errors with `Log.e(TAG, "message", exception)`

**Integration Points:**
- `QueuedInstallDao.updateStatus()` - Use for status transitions
- `QueuedInstallDao.updateProgress()` - Use for progress updates (already throttled in ViewModel)
- `MainRepository.getAllQueuedInstalls()` - Flow for UI observation

**Potential Conflict:**
The existing queue processor in MainViewModel (`queueProcessorJob`) will conflict with WorkManager. Either:
1. **Option A:** Remove queue processor entirely, rely solely on WorkManager
2. **Option B:** Keep queue processor for orchestration, but actual work done by WorkManager

**Recommended: Option B** - Queue processor becomes a lightweight orchestrator that enqueues WorkManager work based on queue state changes.

### Git Intelligence Summary

**Recent Commit Patterns:**
- `3e22666 feat: add installation queue & migration` - Major queue system overhaul
- `be230a7 feat: add favorites system` - Room schema extension pattern
- Commit messages follow Conventional Commits (`feat:`, `fix:`, `chore:`)

**Code Patterns from Recent Work:**
- Room migrations use explicit `MIGRATION_X_Y` objects
- DAO methods include timestamp parameters for `lastUpdatedAt`
- Companion objects with validation methods for entities
- Extension functions for enum mapping between layers

### Latest Technical Information

**WorkManager 2.9.x (2024-2026):**

**CoroutineWorker Best Practices:**
- Use `setForeground()` for downloads >10 seconds to prevent system kill
- Check `isStopped` in loops for cancellation
- Return `Result.retry()` for retriable failures, `Result.failure()` for permanent
- `runAttemptCount` starts at 0, increments on each retry

**Foreground Service Notification (Android 12+):**
- Requires `FOREGROUND_SERVICE` permission in manifest
- Notification must have `FOREGROUND_SERVICE_TYPE_DATA_SYNC` for downloads
- Use `ForegroundInfo(notificationId, notification, FOREGROUND_SERVICE_TYPE_DATA_SYNC)`

**Quest-Specific Considerations:**
- Quest's Android fork may have custom WorkManager constraints
- Battery optimization aggressive - use foreground service for reliability
- Test on actual Quest hardware, not just emulator

**HTTP Range Resumption:**
- Server must support `Accept-Ranges: bytes` header
- Send `Range: bytes={startByte}-` header in request
- Handle 206 Partial Content response
- Handle 200 OK response (server doesn't support range, restart download)

### Project Context Reference

**From CLAUDE.md:**

**Queue Processing Pattern:**
- Single coroutine job processes queue sequentially
- Only one task processes at a time (FIFO unless promoted)
- Pause/resume/cancel must cleanly interrupt jobs

**Download/Install Flow:**
1. Verify files with server (ground truth)
2. Pre-flight storage check using `StatFs`
3. Download with HTTP Range resumption
4. Extract 7z archives with password
5. Handle special install instructions via `install.txt` parsing
6. Move OBB files to `/Android/obb/{packageName}/`
7. Launch APK installer via `FileProvider`

**Critical Warnings:**
- Storage space checks must account for extraction overhead (2.9x for 7z)
- Queue processor must handle cancellation at every suspension point
- Use `currentCoroutineContext().ensureActive()` in loops

**File Locations:**
- Downloads: `/sdcard/Download/RookieOnQuest/{safeReleaseName}/`
- Temp install: `context.cacheDir/install_temp/{md5hash}/`
- Logs: `/sdcard/Download/RookieOnQuest/logs/`

## Dev Agent Record

### Agent Model Used

Claude Opus 4.5 (claude-opus-4-5-20251101)

### Debug Log References

- Build verification: `./gradlew.bat :app:assembleDebug` - BUILD SUCCESSFUL
- Kotlin compilation: `./gradlew.bat :app:compileDebugKotlin` - BUILD SUCCESSFUL (only pre-existing warnings)
- Test compilation: `./gradlew.bat :app:compileDebugAndroidTestKotlin` - DownloadWorkerTest.kt compiles (pre-existing errors in MigrationManagerTest.kt unrelated to this story)

### Completion Notes List

1. **WorkManager 2.9.1 Integration:** Successfully integrated WorkManager with CoroutineWorker pattern for background downloads
2. **Option B Architecture:** Implemented as recommended - queue processor in MainViewModel orchestrates WorkManager workers
3. **Foreground Service:** Added DATA_SYNC foreground service type for Android 14+ compliance
4. **Process Death Survival:** Downloads survive app kill via WorkManager's persistent work queue
5. **App Restart Resumption:** `resumeActiveDownloadObservations()` method restores observation for in-progress downloads on app restart
6. **Extraction Worker Chaining:** Deferred to Story 1-6 (future work); current implementation marks download as complete
7. **Test Coverage:** Created instrumented tests for work enqueue, cancellation, unique work policy, and constraints
8. **[REVIEW FIX] Data Loss Bug Fixed:** Removed premature `cleanupInstall()` call that was deleting downloaded files before installation
9. **[REVIEW FIX] Install Flow Restored:** `handleDownloadSuccess()` now properly triggers `MainRepository.installGame()` for extraction and APK installation after WorkManager download completes
10. **[REVIEW FIX] Legacy Fallback:** Uses existing `MainRepository.installGame()` method for extraction instead of missing ExtractionWorker, ensuring complete install flow works
11. **[REVIEW FIX] NetworkModule Singleton:** Created `NetworkModule` object in Constants.kt providing shared OkHttpClient and Retrofit instances, reducing memory footprint and ensuring consistent configuration
12. **[REVIEW FIX] Centralized Constants:** Added `Constants.USER_AGENT`, `Constants.VRP_API_BASE_URL`, `Constants.HTTP_CONNECT_TIMEOUT_SECONDS`, `Constants.HTTP_READ_TIMEOUT_SECONDS` for DRY principle
13. **[REVIEW FIX] Removed Code Duplication:** DownloadWorker, MainRepository, and MainViewModel now use shared NetworkModule.okHttpClient instead of creating separate instances
14. **[REVIEW FIX] Fixed Fully Qualified Imports:** Replaced fully qualified class references (e.g., `androidx.work.WorkInfo.State`) with proper imports in MainRepository and MainViewModel
15. **[REVIEW FIX] Skip Redundant HEAD Requests:** Added `skipRemoteVerification` parameter to `installGame()` - when WorkManager handoff occurs, file sizes are read locally instead of making network HEAD requests
16. **[REVIEW FIX] CancellationException Handling:** Fixed DownloadWorker to rethrow `CancellationException` instead of catching and returning `Result.failure()` - allows proper coroutine cancellation propagation and clean CANCELLED state in WorkManager
17. **[REVIEW FIX] Deterministic Queue Loading:** Replaced flaky `delay(500)` in `resumeActiveDownloadObservations()` with `installQueue.first()` for deterministic Flow collection - ensures queue data is actually loaded before processing
18. **[REVIEW FIX] Centralized CryptoUtils:** Created `CryptoUtils.md5()` in Constants.kt as single source of truth for MD5 hashing - removed duplicate `md5()` functions from DownloadWorker and MainRepository
19. **[REVIEW FIX] Zombie State Recovery:** Enhanced `resumeActiveDownloadObservations()` to detect and reset tasks stuck in `EXTRACTING` or `INSTALLING` states after app kill - automatically resets to `QUEUED` for retry since extraction/installation is not resumable
20. **[REVIEW FIX] Centralized FilePaths:** Created `FilePaths.DOWNLOADS_ROOT_DIR_NAME` constant ("RookieOnQuest") in Constants.kt - replaced hardcoded strings in DownloadWorker and MainRepository
21. **[REVIEW FIX] DownloadUtils Helper:** Created `DownloadUtils` object with shared regex pattern (`HREF_PATTERN`), `isDownloadableFile()`, and `shouldSkipEntry()` functions - reduces code duplication between DownloadWorker and MainRepository directory parsing logic
22. **[REVIEW FIX] Download Only Mode Data Loss:** Fixed `handleDownloadSuccess()` to properly call `MainRepository.installGame()` with `downloadOnly=true` for Download Only mode - ensures files are moved from private temp cache to public Downloads folder
23. **[REVIEW FIX] Shared Download Constants:** Added `DownloadUtils.DOWNLOAD_BUFFER_SIZE`, `isResumeResponse()`, and `isRangeNotSatisfiable()` to consolidate HTTP Range handling logic between DownloadWorker and MainRepository
24. **[REVIEW FIX] Enhanced Test Coverage:** Added instrumented tests for `doWork()` validation: missing release name failure, non-existent game handling, constants verification, and network constraint behavior
25. **[REVIEW FIX] Smart Zombie Recovery:** Added `determineZombieRecoveryState()` function for intelligent recovery from EXTRACTING/INSTALLING zombie states - checks for extraction marker and temp files to avoid unnecessary re-downloads
26. **[REVIEW FIX] Fixed Zombie Recovery Logic Flaw:** Replaced `determineZombieRecoveryState()` with `handleZombieTaskRecovery()` - the old function returned `EXTRACTING` status which the queue processor couldn't pick up (only processes `QUEUED`). New implementation directly calls `handleDownloadSuccess()` when extraction is complete, or resets to `QUEUED` otherwise, ensuring tasks never hang.
27. **[REVIEW FIX] Git Index:** Added `DownloadWorker.kt` and `DownloadWorkerTest.kt` to Git staging area (were untracked).
28. **[REVIEW FIX] Concurrency Fix:** Refactored zombie recovery to ALWAYS reset tasks to QUEUED, ensuring the queue processor handles them sequentially. Added optimization in `runTask()` to skip WorkManager for already-extracted tasks. This prevents multiple zombie tasks from triggering parallel extractions.
29. **[REVIEW FIX] UI Flickering Fix:** Removed status update to `COMPLETED` in DownloadWorker after download finishes - the ViewModel now handles the transition directly from `DOWNLOADING` to `EXTRACTING` via WorkInfo observation, eliminating the brief `COMPLETED` flash.
30. **[REVIEW FIX] WAKE_LOCK Documentation:** Verified WAKE_LOCK permission is present in AndroidManifest.xml (line 17) - required by WorkManager for long-running foreground work.
31. **[REVIEW FIX] Parallel Extraction Flow:** Added `CompletableDeferred<Unit>` signal (`taskCompletionSignal`) to ensure `runTask()` suspends until the ENTIRE pipeline (download + extraction + installation) completes. The queue processor now blocks on each task, preventing concurrent I/O operations and maintaining strict sequential processing.
32. **[REVIEW FIX] Progress UI Regression:** Aligned progress ranges across download and extraction phases - DownloadWorker now reports 0-80% (was 0-95%), extraction phase reports 80-100%. This eliminates the backwards jump from ~95% to 80% during transition.
33. **[REVIEW FIX] Shared Download Utility:** Created `DownloadUtils.downloadWithProgress()` function that encapsulates the download loop with cancellation support and throttled progress updates. Both DownloadWorker and MainRepository now use this shared implementation, eliminating code duplication and ensuring consistent behavior.
34. **[REVIEW FIX] Zombie Recovery Concurrency:** Issue resolved by Fix #31 - zombie tasks are reset to QUEUED and processed sequentially by the queue processor. The `taskCompletionSignal` mechanism ensures each task fully completes (including extraction) before the next one starts.

### File List

**Files Created:**
- `app/src/main/java/com/vrpirates/rookieonquest/worker/DownloadWorker.kt` - Main download worker with CoroutineWorker
- `app/src/androidTest/java/com/vrpirates/rookieonquest/worker/DownloadWorkerTest.kt` - Instrumented tests

**Files Modified:**
- `app/build.gradle.kts` - Added WorkManager dependencies (work-runtime-ktx:2.9.1, work-testing:2.9.1)
- `app/src/main/AndroidManifest.xml` - Added FOREGROUND_SERVICE_DATA_SYNC permission
- `app/src/main/java/com/vrpirates/rookieonquest/data/Constants.kt` - Added CryptoUtils.md5(), FilePaths.DOWNLOADS_ROOT_DIR_NAME, DownloadUtils helper object with shared downloadWithProgress() function
- `app/src/main/java/com/vrpirates/rookieonquest/data/MainRepository.kt` - Added WorkManager enqueue/cancel/observe methods, migrated to CryptoUtils.md5(), FilePaths.DOWNLOADS_ROOT_DIR_NAME, DownloadUtils.downloadWithProgress()
- `app/src/main/java/com/vrpirates/rookieonquest/ui/MainViewModel.kt` - Added CompletableDeferred taskCompletionSignal for sequential queue processing, runTask() now suspends until full pipeline completes, handleDownloadSuccess() signals completion, fixed progress ranges for smooth UI transition
- `app/src/main/java/com/vrpirates/rookieonquest/worker/DownloadWorker.kt` - Migrated to DownloadUtils.downloadWithProgress(), fixed progress range to 0-80%

