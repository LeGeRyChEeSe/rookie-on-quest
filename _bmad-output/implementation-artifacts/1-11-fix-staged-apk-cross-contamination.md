# Story 1.11: Fix Staged APK Cross-Contamination

Status: review

## Story

As a user,
I want to install the specific game I selected,
so that I don't accidentally install a previously downloaded game due to cache contamination.

## Acceptance Criteria

1. The application must uniquely identify the APK file intended for the current installation task.
2. The APK staging area (`externalFilesDir`) must be cleaned of any previous APK files before a new installation starts.
3. The "staged APK" recovery logic in `MainViewModel` must verify that any found APK matches the current task's package name.
4. The APK file copied to the staging area should use a predictable naming convention: `{packageName}.apk`.

## Tasks / Subtasks

- [x] Modify `MainRepository.installGame` to cleanup `externalFilesDir` before staging. (AC: 2)
  - [x] Iterate through `externalFilesDir.listFiles()` and delete any file ending with `.apk`.
  - **NOTE:** Implementation completed in commit `f84f714` (Stories 1.3, 1.5)
- [x] Update `MainRepository.installGame` to name the staged APK as `{packageName}.apk`. (AC: 4)
  - [x] Replace `finalApk.name` with `"${game.packageName}.apk"` when creating the destination `File`.
  - **NOTE:** Implementation completed in commit `f84f714` (Stories 1.3, 1.5)
- [x] Update `MainViewModel.handleZombieTaskRecovery` to search for specific `{packageName}.apk`. (AC: 1, 3)
  - [x] Change the `find` criteria to match `file.name == "${task.packageName}.apk"`.
  - **NOTE:** Implementation completed in commit `943565a` (Story 1.10, partial 1.11)
- [x] Update `MainViewModel` queue processor logic to search for specific `{packageName}.apk`. (AC: 1, 3)
  - [x] Change the `find` criteria to match `file.name == "${task.packageName}.apk"`.
  - **NOTE:** Implementation completed in commit `943565a` (Story 1.10, partial 1.11)
- [x] Add comprehensive test coverage for the cross-contamination fix
  - **This story's primary contribution:** Created instrumented tests validating the fix

### Review Findings (2026-01-24)

#### HIGH Issues Found

1. **Story File List contains files NOT modified in story 1.11 commit**
   - The File List incorrectly includes files from other stories (1.3, 1.4, 1.5, 1.10)
   - MainRepository.kt, MainViewModel.kt, Constants.kt, DownloadWorker.kt, MainActivity.kt changes were NOT made in commit aad4430
   - **Action required:** Correct File List to only include files actually changed in this story

2. **Core implementation is in WRONG commits/stories**
   - MainRepository changes (cleanupStagedApks, getStagedApkFileName) → commit f84f714 (Stories 1.3, 1.5)
   - MainViewModel changes (handleZombieTaskRecovery, runTask) → commit 943565a (Story 1.10)
   - **Impact:** Story boundaries are blurred; Story 1.11 cannot be considered independently "done"

3. **Tests use app's own APK instead of mock APKs**
   - Tests in StagedApkCrossContaminationTest.kt use `context.packageCodePath` (app's APK)
   - Doesn't truly validate cross-contamination between different game packages
   - **Recommendation:** Create mock APK files with different package names for realistic testing

4. **Missing true end-to-end test for cross-contamination**
   - testEndToEnd_CrossContaminationScenario uses app's APK for both tasks
   - Doesn't simulate real scenario: Task A stages Game A.apk, Task B stages Game B.apk

#### MEDIUM Issues Found

5. **Completion Notes misattribute work**
   - Claims "Fixed cross-contamination bug" but implementation was done in other commits
   - Should clarify that Story 1.11 added TESTS for the fix, not the fix itself

#### LOW Issues Found

6. **Missing code documentation links to story**
   - Functions like cleanupStagedApks(), getStagedApkFileName() lack KDoc referencing Story 1.11

### Review Follow-ups (AI)

- [ ] [AI-Review][HIGH] Correct File List to only include files from commit aad4430 (tests + story file + sprint status)
- [ ] [AI-Review][HIGH] Update Completion Notes to clarify implementation was done in Stories 1.3, 1.5, 1.10; this story adds tests
- [ ] [AI-Review][HIGH] Create mock APK files with different package names for realistic cross-contamination testing
- [ ] [AI-Review][MEDIUM] Consider moving story 1.11 tasks to appropriate stories (1.3, 1.5, 1.10) and make 1.11 test-only
- [ ] [AI-Review][LOW] Add KDoc comments linking cleanupStagedApks() and getStagedApkFileName() to Story 1.11

## Dev Notes

- **Root Cause**: The current logic uses `file.name.endsWith(".apk")` which picks the first APK found in the directory. If a previous installation failed or wasn't cleaned up, that old APK persists and is used for all subsequent installation tasks.
- **Relevant Files**:
  - `app/src/main/java/com/vrpirates/rookieonquest/data/MainRepository.kt`: Look for `installGame` function (around line 1040).
  - `app/src/main/java/com/vrpirates/rookieonquest/ui/MainViewModel.kt`: Look for `handleZombieTaskRecovery` (around line 766) and the queue processor loop (around line 1373).

### Project Structure Notes

- Follow existing naming conventions in `MainRepository` and `MainViewModel`.
- Ensure `Dispatchers.IO` is used for file operations (already the case in these functions).

### References

- [Source: app/src/main/java/com/vrpirates/rookieonquest/data/MainRepository.kt#L1040]
- [Source: app/src/main/java/com/vrpirates/rookieonquest/ui/MainViewModel.kt#L766]
- [Source: app/src/main/java/com/vrpirates/rookieonquest/ui/MainViewModel.kt#L1373]

## Dev Agent Record

### Agent Model Used

Gemini 2.0 Flash

### Debug Log References

N/A

### Implementation History

**IMPORTANT:** The core implementation for fixing cross-contamination was completed in PREVIOUS commits:

- **Commit f84f714** (Stories 1.3, 1.5): Added `cleanupStagedApks()`, `getStagedApkFileName()`, `getStagedApkFile()`, `isValidApkFile()`, and `getValidStagedApk()` functions to MainRepository
- **Commit 943565a** (Story 1.10, partial 1.11): Updated MainViewModel's `handleZombieTaskRecovery()` and `runTask()` to use `repository.getValidStagedApk()` with package-specific APK lookup

**Story 1.11's contribution:** Added comprehensive test coverage to validate the fix

### Completion Notes List

**Tests Added:**
- Created `StagedApkCrossContaminationTest.kt` with 7 instrumented tests validating the fix:
  - `testApkNamingConvention_UsesPackageName`: Verifies naming convention
  - `testFindApkByPackageName_FindsCorrectApk`: Validates correct APK lookup
  - `testGetValidStagedApk_ValidatesIntegrity`: Validates integrity checks
  - `testCleanupStagedApks_RemovesAll`: Validates cleanup functionality
  - `testCleanupStagedApks_WithPreserve`: Validates selective cleanup
  - `testNewNamingPreventsCrossContamination`: Validates the complete fix
  - `testEndToEnd_CrossContaminationScenario`: Validates zombie recovery scenario
- Created `ApkIntegrityValidationTest.kt` with 5 instrumented tests:
  - `validApk_PassesIntegrityCheck`: Validates real APK passes check
  - `apkWithExpectedPackageName_PassesValidation`: Validates package name matching
  - `apkWithWrongPackageName_FailsValidation`: Validates package name mismatch detection
  - `invalidApk_FailsIntegrityCheck`: Validates corrupted APK rejection
  - `emptyFile_FailsIntegrityCheck`: Validates empty file rejection

**Test Limitations:**
- Tests use app's own APK instead of mock APKs with different package names
- End-to-end test doesn't fully simulate cross-contamination between different games
- Future improvement: Create mock APK files for more realistic testing

### File List

**Files modified in Story 1.11 commit (aad4430):**
- app/src/androidTest/java/com/vrpirates/rookieonquest/data/StagedApkCrossContaminationTest.kt (new)
- app/src/androidTest/java/com/vrpirates/rookieonquest/data/ApkIntegrityValidationTest.kt (new)
- app/src/test/java/com/vrpirates/rookieonquest/data/StagedApkCrossContaminationTest.kt (deleted - moved to androidTest)
- _bmad-output/implementation-artifacts/1-11-fix-staged-apk-cross-contamination.md (modified)
- _bmad-output/implementation-artifacts/sprint-status.yaml (modified)

**Core implementation files (modified in PREVIOUS commits):**
- app/src/main/java/com/vrpirates/rookieonquest/data/MainRepository.kt (modified in f84f714 - Stories 1.3, 1.5)
- app/src/main/java/com/vrpirates/rookieonquest/ui/MainViewModel.kt (modified in 943565a - Story 1.10)
- app/src/main/java/com/vrpirates/rookieonquest/data/Constants.kt (modified in f84f714)
- app/src/main/java/com/vrpirates/rookieonquest/worker/DownloadWorker.kt (modified in f84f714)
- app/src/main/java/com/vrpirates/rookieonquest/MainActivity.kt (modified in f624d63 - Story 1.4)
- app/build.gradle.kts (modified in f84f714)

### Change Log
- 2026-01-24: Created comprehensive test coverage for cross-contamination fix (Story 1.11)
- 2026-01-24: Core implementation completed in commits f84f714 (Stories 1.3, 1.5) and 943565a (Story 1.10)
- 2026-01-24: Code review revealed documentation issues - File List and Completion Notes incorrectly attributed work from other stories to Story 1.11
