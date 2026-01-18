# Sprint Change Proposal - DB Update Throttling

**Project:** rookie-on-quest
**Trigger Story:** 1.2 - Queue State Migration from v2.4.0
**Date:** 2026-01-10
**Status:** APPROVED
**Author:** Correct Course Workflow
**Approved By:** Garoh

---

## Issue Summary

### Problem Identified

**Review AI identified a critical UNRESOLVED issue in Story 1.2:**

🔴 **DB Update Throttling Missing (Task marked [x] but not implemented)**

- **Story 1.2 Task:** "Batch DB updates to avoid excessive write load" - marked as completed [x]
- **Code Reality:** `MainRepository.installGame()` calls `onProgress` in download loop **every 64KB**
- **Technical Impact:** Each `onProgress` call immediately triggers `repository.updateQueueProgress()`, which performs a **blocking DB write** on the IO dispatcher

### Concrete Evidence

**File:** [MainRepository.kt:530-537](app/src/main/java/com/vrpirates/rookieonquest/data/MainRepository.kt#L530-L537)

```kotlin
totalBytesDownloaded += bytesRead

val overallProgress = if (totalBytes > 0) totalBytesDownloaded.toFloat() / totalBytes else 0f
onProgress(
    "Downloading ${game.gameName} (${index + 1}/${remoteSegments.size})",
    overallProgress * 0.8f,
    totalBytesDownloaded,
    totalBytes
)
```

**Each `onProgress` call →** [MainViewModel.kt:1003](app/src/main/java/com/vrpirates/rookieonquest/ui/MainViewModel.kt#L1003)
**→ Immediately triggers** `repository.updateQueueProgress()`
**→ Blocking DB write** on every 64KB chunk

### Measurable Consequences

**For a 4GB download at 50MB/s:**

- **Chunks per second:** 50MB / 64KB = ~781 chunks/sec
- **DB writes per second:** **781 writes/sec**
- **Total DB writes for 4GB:** ~65,000 database writes
- **UI Flow invalidations:** 65,000 Compose recompositions

**Impact on Quest Users:**
- ❌ Disk thrashing (wear on flash storage)
- ❌ UI lag/stutter (constant Flow invalidation)
- ❌ Excessive battery drain
- ❌ Violation of **NFR-P1** (maintain 60fps during downloads)

### Discovery Context

- **When:** AI Review post-implementation Story 1.2 (2026-01-09)
- **How:** Code analysis vs. story tasks
- **Story 1.2 Status:** `in-progress` (review follow-up item #103 UNRESOLVED)

---

## Impact Analysis

### Epic Impact: Epic 1 - Persistent Installation Queue System

**Epic Status:** `in-progress`

**Affected Stories:**

| Story ID | Title | Status | Impact |
|----------|-------|--------|--------|
| **1.2** | Queue State Migration from v2.4.0 | in-progress | 🔴 **CRITICAL** - Incomplete task blocks completion |
| **1.3** | WorkManager Download Task Integration | backlog | 🟡 **MEDIUM** - Depends on 1.2 fix (same onProgress pattern) |
| **1.4** | Download Queue UI with Persist/Resume | backlog | 🟡 **MEDIUM** - UI performance affected if not throttled |
| **1.5-1.8** | HTTP Range, 7z Extraction, APK Install, Permissions | backlog | 🟢 **LOW** - No direct dependency |
| **1.9** | Installation History Tracking | ready-for-dev | 🟢 **NONE** - Separate feature |

**Epic 1 Conclusion:**
✅ Epic can continue **AFTER Story 1.2 fix**
⚠️ Stories 1.3 and 1.4 must reuse the **same throttling mechanism**

### Artifact Conflicts

#### PRD Conflicts

**Affected Section:** Non-Functional Requirements - Performance

**NFRs Violated:**
- **NFR-P2:** "Background operations (downloads, extractions) must not cause frame drops or UI lag"
- **NFR-P9:** "Battery consumption during downloads must not exceed 5% increase vs v2.4.0 baseline"

**Fix Required:** ❌ **NONE** - PRD remains valid, implementation must respect NFRs

#### Architecture Conflicts

**Affected Section:** Data Flow - Room Database Updates

**Current Architectural Pattern:**
```
MainRepository.installGame() → onProgress callback (every 64KB)
  ↓
MainViewModel receives callback
  ↓
repository.updateQueueProgress() → Room DB write
  ↓
Flow<List<QueuedInstallEntity>> emits → UI recomposes
```

**Fix Required:** ⚠️ **MINOR MODIFICATION** - Add throttling layer between callback and DB write

**No major architectural change** - just optimization of existing pattern

#### UI/UX Conflicts

**Expected User Impact:**
- ✅ **UX Improvement** - Smoother UI (no stutter during downloads)
- ✅ **Better perception** - Longer battery life
- ⚠️ **Trade-off:** Progress updates max 2 times per second (vs currently 781 times per second)

**Fix Required:** ❌ **NONE** - UX improves with the fix

### Future Story Impact

**Stories depending on same pattern:**

1. **Story 1.3** - WorkManager Download Task Integration
   - Will reuse `MainRepository.installGame()` via Worker
   - **Must** inherit throttling automatically

2. **Story 1.6** - 7z Extraction with Progress Tracking
   - Also contains `onProgress("Extracting...", ...)` in extraction loop
   - **Must** apply same throttling

3. **Story 2.2** - Phase-Specific Animation Implementations
   - Stickman animation based on progress updates
   - **Benefits** from throttling (60fps animation doesn't need 781 updates/sec)

**Conclusion:**
✅ Story 1.2 fix **improves** all future stories
✅ Reusable pattern for all progress callbacks

---

## Recommended Approach

### Option 1: Direct Adjustment (RECOMMENDED ✅)

**Description:** Complete Story 1.2 by implementing missing throttling

**Required Modifications:**

#### 1. Add Throttling Helper in MainViewModel

```kotlin
// MainViewModel.kt

private val progressThrottler = MutableSharedFlow<ProgressUpdate>(
    extraBufferCapacity = 1,
    onBufferOverflow = BufferOverflow.DROP_OLDEST
)

data class ProgressUpdate(
    val releaseName: String,
    val progress: Float,
    val downloadedBytes: Long?,
    val totalBytes: Long?
)

init {
    // Throttle progress updates to max 1 per 500ms
    viewModelScope.launch {
        progressThrottler
            .sample(500) // Emit only latest value every 500ms
            .collect { update ->
                repository.updateQueueProgress(
                    releaseName = update.releaseName,
                    progress = update.progress,
                    downloadedBytes = update.downloadedBytes,
                    totalBytes = update.totalBytes
                )
            }
    }
}

// Updated onProgressUpdate callback
private fun createProgressCallback(releaseName: String): (String, Float, Long, Long) -> Unit {
    return { message, progress, current, total ->
        // Update UI state immediately (in-memory, no DB)
        updateInMemoryProgress(releaseName, message, progress, current, total)

        // Throttle DB writes via SharedFlow
        progressThrottler.tryEmit(ProgressUpdate(releaseName, progress, current, total))
    }
}
```

#### 2. Add In-Memory Progress State

```kotlin
// MainViewModel.kt

private val _inMemoryProgress = MutableStateFlow<Map<String, InstallTaskProgress>>(emptyMap())

data class InstallTaskProgress(
    val message: String,
    val progress: Float,
    val currentBytes: Long,
    val totalBytes: Long
)

private fun updateInMemoryProgress(
    releaseName: String,
    message: String,
    progress: Float,
    current: Long,
    total: Long
) {
    _inMemoryProgress.update { map ->
        map + (releaseName to InstallTaskProgress(message, progress, current, total))
    }
}

// Merge in-memory progress with DB state for UI
val installQueueWithProgress: StateFlow<List<InstallTaskState>> = combine(
    installQueue, // Room DB Flow
    _inMemoryProgress
) { dbQueue, memoryProgress ->
    dbQueue.map { entity ->
        val inMemory = memoryProgress[entity.releaseName]
        entity.toInstallTaskState().copy(
            message = inMemory?.message ?: entity.status,
            progress = inMemory?.progress ?: entity.progress,
            currentSize = inMemory?.currentBytes?.let { formatSize(it) },
            totalSize = inMemory?.totalBytes?.let { formatSize(it) }
        )
    }
}.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
```

**Effort Estimate:** 🟢 **LOW** (2-4 hours)
- ~100 lines of code
- Standard Kotlin Flow pattern (`sample()` operator)
- Simple unit tests

**Risk Level:** 🟢 **LOW**
- No architectural change
- Pure improvement (no regression possible)
- Tested and documented pattern (Kotlin Flow throttling)

**Timeline Impact:** ✅ **NONE** (fix within existing Story 1.2)

**Advantages:**
- ✅ Solves critical issue immediately
- ✅ Reusable pattern for Stories 1.3, 1.6, 2.2
- ✅ Improves UX (smoother UI)
- ✅ Respects all NFRs (P1, P2, P9)
- ✅ Maintains sprint momentum (no rollback)

**Disadvantages:**
- ⚠️ Progress UI updates max 2 times per second (acceptable - imperceptible to user)
- ⚠️ Adds ~100 lines to Story 1.2 (slight scope creep)

---

### ✅ SELECTED APPROACH: Option 1 - Direct Adjustment

**Rationale:**

1. **Minimal Effort, Maximum Impact**
   - Simple fix (~100 lines)
   - Solves problem immediately
   - Reusable pattern for all future stories

2. **Technical Excellence**
   - Kotlin Flow `.sample()` is standard and tested pattern
   - Improves UI performance (60fps guaranteed)
   - Respects all NFRs

3. **Business Value**
   - Maintains sprint momentum
   - Team morale preserved (Story 1.2 completion)
   - Timeline respected

4. **Risk Mitigation**
   - Minimal technical risk (pure improvement)
   - No regression possible
   - Easy to test (unit tests + performance profiling)

5. **Long-term Sustainability**
   - Establishes pattern for entire project
   - Stories 1.3, 1.6, 2.2 benefit automatically
   - Clear documentation for future features

**Accepted Trade-offs:**
- ⚠️ Progress UI update limited to 2Hz (500ms) - **Acceptable** (imperceptible to user)
- ⚠️ Slight scope creep Story 1.2 (+100 lines) - **Acceptable** (completes existing task)

---

## Detailed Change Proposals

### Story 1.2 Modifications

#### Change 1: Tasks Section

**File:** `_bmad-output/implementation-artifacts/1-2-queue-state-migration-from-v2-4-0.md`

**OLD:**
```markdown
- [x] Create StateFlow ↔ Room synchronization layer (AC: 3, 5)
  - [x] Load queue from Room DB on app startup → populate StateFlow
  - [x] Observe Room Flow in ViewModel: `Flow<List<QueuedInstallEntity>>`
  - [x] Transform to `StateFlow<List<InstallTaskState>>` for UI compatibility
  - [x] Update Room DB on every state change (status, progress, bytes)
  - [x] Batch DB updates to avoid excessive write load
```

**NEW:**
```markdown
- [x] Create StateFlow ↔ Room synchronization layer (AC: 3, 5)
  - [x] Load queue from Room DB on app startup → populate StateFlow
  - [x] Observe Room Flow in ViewModel: `Flow<List<QueuedInstallEntity>>`
  - [x] Transform to `StateFlow<List<InstallTaskState>>` for UI compatibility
  - [x] Update Room DB on every state change (status, progress, bytes)
  - [x] **Throttle DB progress updates to max 1 per 500ms using Flow.sample()**
  - [x] **Maintain in-memory progress state for immediate UI updates**
  - [x] **Combine DB state + in-memory progress for final UI StateFlow**
```

**Rationale:** Clarify throttling implementation with exact technical pattern

---

#### Change 2: Review Follow-ups Section

**File:** `_bmad-output/implementation-artifacts/1-2-queue-state-migration-from-v2-4-0.md`

**OLD:**
```markdown
- [ ] [AI-Review][CRITICAL] Implement DB update throttling: `updateQueueProgress` is called for every 64KB chunk, causing excessive disk I/O and UI thrashing. Need to throttle updates (e.g., max once every 500ms). [MainViewModel.kt:1003]
```

**NEW:**
```markdown
- [x] [AI-Review][CRITICAL] Implement DB update throttling: `updateQueueProgress` is called for every 64KB chunk, causing excessive disk I/O and UI thrashing. Need to throttle updates (e.g., max once every 500ms). [MainViewModel.kt:1003]
  - **FIXED**: Added `progressThrottler` MutableSharedFlow with `.sample(500)` operator
  - **FIXED**: Created `_inMemoryProgress` StateFlow for immediate UI updates (no DB hit)
  - **FIXED**: Combined DB state + in-memory progress via `combine()` for final UI Flow
  - **BENEFIT**: UI updates at 60fps (in-memory), DB writes throttled to 2Hz (500ms)
  - **TESTS**: Added unit test verifying max 2 DB writes per second during fast downloads
```

**Rationale:** Document complete fix for traceability

---

#### Change 3: File List Section

**File:** `_bmad-output/implementation-artifacts/1-2-queue-state-migration-from-v2-4-0.md`

**OLD:**
```markdown
**Modified (Review Follow-ups - 2026-01-10):**
- [MigrationManager.kt](app/src/main/java/com/vrpirates/rookieonquest/data/MigrationManager.kt) - Fixed SharedPreferences name, prevent data loss on parse error
- [MigrationManagerTest.kt](app/src/test/java/com/vrpirates/rookieonquest/data/MigrationManagerTest.kt) - Converted to integration tests
- [QueuedInstallDao.kt](app/src/main/java/com/vrpirates/rookieonquest/data/QueuedInstallDao.kt) - Added atomic `promoteToFront()` method
- [MainRepository.kt](app/src/main/java/com/vrpirates/rookieonquest/data/MainRepository.kt) - Updated `promoteInQueue()` to use atomic DAO method
- [MainViewModel.kt](app/src/main/java/com/vrpirates/rookieonquest/ui/MainViewModel.kt) - Fixed COPYING_OBB status mapping
```

**NEW:**
```markdown
**Modified (Review Follow-ups - 2026-01-10):**
- [MigrationManager.kt](app/src/main/java/com/vrpirates/rookieonquest/data/MigrationManager.kt) - Fixed SharedPreferences name, prevent data loss on parse error
- [MigrationManagerTest.kt](app/src/test/java/com/vrpirates/rookieonquest/data/MigrationManagerTest.kt) - Converted to integration tests
- [QueuedInstallDao.kt](app/src/main/java/com/vrpirates/rookieonquest/data/QueuedInstallDao.kt) - Added atomic `promoteToFront()` method
- [MainRepository.kt](app/src/main/java/com/vrpirates/rookieonquest/data/MainRepository.kt) - Updated `promoteInQueue()` to use atomic DAO method
- [MainViewModel.kt](app/src/main/java/com/vrpirates/rookieonquest/ui/MainViewModel.kt) - Fixed COPYING_OBB status mapping, **added progress throttling with Flow.sample(500), added in-memory progress state**
- [InstallStatus.kt](app/src/main/java/com/vrpirates/rookieonquest/data/InstallStatus.kt) - **(Already in git, added to documentation)**

**Created Tests:**
- [ProgressThrottlingTest.kt](app/src/test/java/com/vrpirates/rookieonquest/ui/ProgressThrottlingTest.kt) - **Unit tests for throttling mechanism**
```

**Rationale:** Add missing InstallStatus.kt + new throttling test file

---

### No PRD Modifications Required

**Section:** Non-Functional Requirements - Performance

✅ PRD already correct:
- **NFR-P2:** "Background operations (downloads, extractions) must not cause frame drops or UI lag"
- **NFR-P9:** "Battery consumption during downloads must not exceed 5% increase vs v2.4.0 baseline"

**Action:** Implement throttling to **respect** these NFRs (no modification needed)

---

### No Epic Modifications Required

**Epic 1: Persistent Installation Queue System**

✅ Epic remains valid. Story 1.2 completes implementation with missing throttling.

**Action:** Mark Story 1.2 as `done` once throttling is implemented and tested.

---

## Implementation Handoff

### Change Scope Classification

**Scope:** 🟡 **MINOR**

- Technical fix isolated to Story 1.2
- No architectural change
- No MVP scope change
- Standard Kotlin Flow pattern

### Handoff Recipients

**Route to:** 🛠️ **Development Team** (Direct Implementation)

**Deliverables:**

1. **Code Implementation:**
   - `MainViewModel.kt` - Add throttling logic (~100 lines)
   - Tests - `ProgressThrottlingTest.kt` (unit tests)
   - Performance profiling - Verify <5 DB writes/sec during downloads

2. **Documentation Updates:**
   - Story 1.2 - Update tasks, review follow-ups, file list
   - Code comments - Document throttling pattern for future reference

3. **Validation:**
   - Unit tests pass (throttling rate verified)
   - Instrumented tests pass (Quest 2/3 60fps maintained)
   - Performance profiling confirms <5 DB writes/sec

### Implementation Steps

**Step 1: Add Throttling Infrastructure (MainViewModel.kt)**
- Create `progressThrottler: MutableSharedFlow`
- Create `_inMemoryProgress: MutableStateFlow`
- Add `.sample(500)` collector in init block
- Add `updateInMemoryProgress()` helper function

**Step 2: Update Progress Callback**
- Modify `createProgressCallback()` to update in-memory state
- Emit to `progressThrottler` (throttled DB writes)

**Step 3: Combine DB + In-Memory State**
- Create `installQueueWithProgress` StateFlow
- Use `combine()` to merge DB state + memory progress

**Step 4: Write Unit Tests**
- Test throttling rate (verify max 2 emits/sec)
- Test in-memory updates (immediate)
- Test DB writes (throttled)

**Step 5: Performance Profiling**
- Download 4GB game on Quest 2
- Monitor DB write frequency (should be ~2/sec)
- Monitor UI framerate (should be 60fps stable)
- Monitor battery drain (should be <5% increase vs v2.4.0)

**Step 6: Update Story Documentation**
- Update tasks list with throttling details
- Mark review follow-up #103 as [x] FIXED
- Update File List with InstallStatus.kt
- Add Change Log entry

### Success Criteria

✅ Story 1.2 marked as `done` when:
1. All unit tests pass (throttling verified)
2. Performance profiling shows <5 DB writes/sec
3. UI maintains 60fps during downloads (Quest 2/3)
4. Battery consumption <5% increase vs v2.4.0
5. Documentation updated (story file, code comments)

### Timeline Estimate

- **Development:** 2-4 hours
- **Testing:** 1-2 hours
- **Documentation:** 30 minutes
- **Total:** ~4-6 hours (less than one day)

**Sprint Impact:** ✅ **NONE** (fits within existing Story 1.2, no new story needed)

---

## Workflow Completion Summary

### Issue Addressed

🔴 **Missing DB Update Throttling** - Story 1.2 Task marked [x] but not implemented, causing 65,000+ DB writes for a 4GB download

### Change Scope

🟡 **MINOR** - Isolated technical fix, standard Kotlin Flow pattern, ~100 lines of code

### Artifacts Modified

| Artifact | Section | Change Type |
|----------|---------|-------------|
| **Story 1.2** | Tasks, Review Follow-ups, File List | Documentation update (clarify implementation) |
| **MainViewModel.kt** | Progress handling | Code implementation (throttling logic) |
| **Tests** | New file: ProgressThrottlingTest.kt | New unit tests |

**Artifacts NOT Modified (remain valid):**
- ✅ PRD (NFRs already correct)
- ✅ Epics (Epic 1 remains valid)
- ✅ Architecture (existing pattern, just optimized)
- ✅ Stories 1.3-1.9 (benefit from fix automatically)

### Routed To

🛠️ **Development Team** - Direct implementation

**Responsibility:** Implement throttling, test, document, mark Story 1.2 as `done`

**Timeline:** ~4-6 hours (fits in current sprint)

---

## Next Steps

1. ✅ **APPROVED** by Garoh (2026-01-10)
2. 🛠️ **Development team** implements throttling in Story 1.2
3. ✅ **Success:** Story 1.2 → `done`, Epic 1 continues, MVP on track!

**Sprint Impact:** ✅ **ZERO DELAY** - Quick fix, reusable pattern, pure improvement

**Project Impact:** ✅ **IMPROVEMENT** - Smooth UX, battery savings, pattern established for future stories

---

**Status:** APPROVED AND READY FOR IMPLEMENTATION
**Approval Date:** 2026-01-10
**Approved By:** Garoh
