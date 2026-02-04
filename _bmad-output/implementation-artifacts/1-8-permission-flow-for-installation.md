# Story 1.8: Permission Flow for Installation

Status: in-progress

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a user,
I want the app to guide me through granting required permissions before I attempt to install a game,
So that installations don't fail mid-process due to missing permissions.

## Acceptance Criteria

1. **Given** app is launched for the first time on v2.5.0
   **When** user navigates to game list
   **Then** app checks for all required installation permissions
   **And** displays permission request dialogs in sequence if not granted

2. **Given** user launches app without required permissions
   **When** user clicks "Install" on any game
   **Then** app checks for required permissions before starting installation
   **And** if permissions are missing, triggers permission request flow before installation
   **And** shows "Please grant required permissions to install games" message

3. **Given** permission request flow is active
   **When** first permission dialog is shown (INSTALL_UNKNOWN_APPS)
   **Then** displays clear explanation: "Allow installing apps from unknown sources"
   **And** guides user to system settings to grant permission
   **And** waits for user to return before checking next permission

4. **Given** INSTALL_UNKNOWN_APPS permission is granted
   **When** MANAGE_EXTERNAL_STORAGE permission check begins
   **Then** displays clear explanation: "Allow access to manage all files for game data (OBB files)"
   **And** on Android 11+, guides user to system "Manage all files" access
   **And** on Android 10 or earlier, uses standard storage permission flow

5. **Given** all required permissions are granted
   **When** permission flow completes
   **Then** stores permission state in SharedPreferences
   **And** resumes user's intended action (install game or continue browsing)
   **And** does not show permission dialogs again unless manually revoked

6. **Given** user denies a permission
   **When** user returns from system settings without granting
   **Then** app detects permission was not granted
   **And** shows "This permission is required for installation. You can grant it later in Settings."
   **And** allows user to continue browsing but blocks installation attempts
   **And** shows permission request again on next install attempt

7. **Given** user manually revokes a permission in system settings
   **When** app detects permission is no longer granted
   **Then** updates internal permission state
   **And** shows toast message: "Permission revoked. Please grant it again to install games."
   **And** blocks installation attempts until permission is re-granted

## Tasks / Subtasks

- [ ] **Task 1: Create Permission Manager Component** (AC: 1, 2, 5, 7)
  - [ ] Create `PermissionManager` singleton in `data/` folder
  - [ ] Add `hasAllRequiredPermissions()` method returning Boolean
  - [ ] Add `hasInstallUnknownAppsPermission()` method using `PackageInfo.REQUESTED_PERMISSION_GRANTED`
  - [ ] Add `hasManageExternalStoragePermission()` method using `Environment.isExternalStorageManager()`
  - [ ] Add `checkAndRequestPermissions()` method returning flow of permission states
  - [ ] Store permission state in SharedPreferences using `PREFS_PERMISSIONS_GRANTED` key
  - [ ] Add timestamp tracking to avoid stale permission checks (cache for 30 seconds)

- [ ] **Task 2: Implement INSTALL_UNKNOWN_APPS Permission Handling** (AC: 3)
  - [x] Create permission request dialog with explanation text
  - [x] Use `Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES` intent
  - [x] Pass package name in intent: `intent.data = Uri.parse("package:$packageName")`
  - [x] Register `ActivityResultLauncher` in MainActivity
  - [ ] Handle permission grant result and update PermissionManager state
  - [ ] Log permission grant/deny events for debugging
  - [ ] Show "Permission granted. Continuing..." toast on success

- [ ] **Task 3: Implement MANAGE_EXTERNAL_STORAGE Permission Handling** (AC: 4)
  - [x] Detect Android version (API 30+ uses `MANAGE_EXTERNAL_STORAGE`, API 29 uses scoped storage)
  - [x] For API 30+: Create dialog explaining "Manage all files" access requirement
  - [x] Use `Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION` intent
  - [ ] For API 29: Use standard `WRITE_EXTERNAL_STORAGE` permission flow
  - [x] Register `ActivityResultLauncher` in MainActivity
  - [ ] Handle permission grant result and update PermissionManager state
  - [ ] Show "Storage access granted. Games can now copy OBB files." toast on success

- [ ] **Task 4: Create Permission Request UI Flow** (AC: 1, 2, 3, 4, 5, 6)
  - [ ] Add `permissionFlowState` to MainViewModel (IDLE, CHECKING, REQUESTING, COMPLETED, DENIED)
  - [x] Add `isPermissionFlowActive` flag to prevent duplicate permission requests
  - [ ] Add `pendingInstallAfterPermissions` to store game user wants to install
  - [x] Create `PermissionRequestDialog` composable with explanation text (implemented as PermissionOverlay)
  - [ ] Add "Grant Permission" and "Cancel" buttons to dialog
  - [ ] Show dialog sequentially: INSTALL_UNKNOWN_APPS → MANAGE_EXTERNAL_STORAGE
  - [ ] Update UI to show "Permissions required" badge on game list items if permissions missing

- [ ] **Task 5: Integrate Permission Check with Installation Flow** (AC: 2, 6, 7)
  - [x] Modify `MainViewModel.installGame()` to check permissions before starting
  - [ ] If permissions missing: start permission flow, store pending game in `pendingInstallAfterPermissions`
  - [ ] If permissions granted: proceed with normal installation flow
  - [ ] If permission denied: show "Permission required. Grant it in Settings." toast, do not start installation
  - [ ] After permission flow completes: automatically retry `installGame()` for pending game
  - [x] Add permission check to queue processor before processing tasks

- [ ] **Task 6: Add Permission State Persistence** (AC: 5, 7)
  - [ ] Save permission state to SharedPreferences after each permission grant
  - [ ] Load permission state on app startup in `MainViewModel.init`
  - [ ] Validate stored state against actual permissions on startup (in case user revoked)
  - [ ] Clear pending install if permissions were revoked
  - [ ] Add `checkPermissionState()` method called on app resume

- [ ] **Task 7: Enhance Error Handling** (AC: 6, 7)
  - [ ] Detect when user denies permission without leaving app (Activity.RESULT_CANCELED)
  - [ ] Show user-friendly error message: "Installation requires storage permission"
  - [ ] Add "Open Settings" button to manually grant revoked permissions
  - [ ] Log permission denial events with permission type and timestamp
  - [ ] Track permission denial count for analytics (optional)

- [ ] **Task 8: Automated Tests**
  - [ ] Unit Test: PermissionManager permission check methods return correct states
  - [ ] Unit Test: SharedPreferences permission state persistence
  - [ ] Unit Test: Permission state caching (30 second timeout)
  - [ ] Integration Test: INSTALL_UNKNOWN_APPS permission request flow
  - [ ] Integration Test: MANAGE_EXTERNAL_STORAGE permission request flow
  - [ ] UI Test: Permission dialog display and button interactions

## Dev Notes

### Target Components

| Component | Path | Responsibility |
|-----------|------|----------------|
| PermissionManager | `data/PermissionManager.kt` | Permission state management, checks, persistence (NEW) |
| MainViewModel | `ui/MainViewModel.kt` | Permission flow orchestration, UI state |
| MainActivity | `ui/MainActivity.kt` | ActivityResultLauncher registration, intent handling |
| PermissionRequestDialog | `ui/composables/PermissionRequestDialog.kt` | Permission request UI (NEW) |
| Constants | `data/Constants.kt` | Permission-related constants, SharedPreferences keys |

### Critical Implementation Details

**Required Permissions:**

1. **INSTALL_UNKNOWN_APPS** (API 26+)
   - Purpose: Allow installing APK files from FileProvider
   - Check method: `packageManager.canRequestPackageInstalls()`
   - Intent: `Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES`
   - Required by: Story 1.7 (APK installation via FileProvider)

2. **MANAGE_EXTERNAL_STORAGE** (API 30+)
   - Purpose: Move OBB files to `/Android/obb/{packageName}/`
   - Check method: `Environment.isExternalStorageManager()`
   - Intent: `Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION`
   - Required by: Story 1.7 Task 3 (OBB file movement)

3. **IGNORE_BATTERY_OPTIMIZATIONS** (already implemented)
   - Purpose: Prevent Quest from sleeping during long downloads/extractions
   - Already requested in existing codebase
   - Not part of this story (already implemented)

**Permission Flow Architecture:**

```
User clicks Install
    ↓
MainViewModel.installGame()
    ↓
PermissionManager.hasAllRequiredPermissions()?
    ↓ No
MainViewModel.startPermissionFlow()
    ↓
Show PermissionRequestDialog (INSTALL_UNKNOWN_APPS)
    ↓
User clicks "Grant Permission"
    ↓
MainActivity.launchInstallUnknownAppsIntent()
    ↓
User returns to app
    ↓
Check permission granted?
    ├─ Yes → Next permission (MANAGE_EXTERNAL_STORAGE)
    └─ No → Show "Permission required" toast, allow cancel
    ↓
[Repeat for MANAGE_EXTERNAL_STORAGE]
    ↓
All permissions granted?
    ├─ Yes → PermissionManager.saveState(), retry installGame()
    └─ No → Block installation, show "Grant permissions in Settings" message
```

**Existing Permission Handling (from MainViewModel.kt):**
```kotlin
// Existing battery optimization permission flow (for reference)
private fun requestIgnoreBatteryOptimizations() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
        }
    }
}
```

**Permission State Persistence:**
```kotlin
// SharedPreferences structure
const val PREFS_PERMISSIONS_GRANTED = "permissions_granted"
const val PREFS_INSTALL_UNKNOWN_APPS_GRANTED = "install_unknown_apps_granted"
const val PREFS_MANAGE_EXTERNAL_STORAGE_GRANTED = "manage_external_storage_granted"
const val PREFS_PERMISSION_CHECK_TIMESTAMP = "permission_check_timestamp"

// Cache duration: 30 seconds (avoid excessive PackageManager calls)
const val PERMISSION_CACHE_DURATION_MS = 30_000L
```

**UI State Flow:**
```kotlin
// MainViewModel additions
data class PermissionFlowState(
    val isActive: Boolean = false,
    val currentPermission: PermissionType? = null, // INSTALL_UNKNOWN_APPS, MANAGE_EXTERNAL_STORAGE
    val pendingGameInstall: String? = null, // releaseName
    val allGranted: Boolean = false
)

enum class PermissionType {
    INSTALL_UNKNOWN_APPS,
    MANAGE_EXTERNAL_STORAGE
}
```

### Error Handling Patterns

| Error Type | Detection | User Message | Recovery Action |
|------------|-----------|--------------|-----------------|
| Permission denied | Activity.RESULT_CANCELED | "Permission required. Grant it in Settings to install games." | Show "Open Settings" button |
| Permission revoked | `hasAllRequiredPermissions()` returns false on resume | "Permission was revoked. Please grant it again to install games." | Trigger permission flow |
| System settings unavailable | ActivityNotFoundException | "Unable to open settings. Please manually grant the permission." | Log error, show manual setup instructions |
| Cache timeout | System.currentTimeMillis() - timestamp > CACHE_DURATION | Refresh permission state | Re-check permissions |

### Anti-Patterns (DO NOT DO)

| ❌ Anti-Pattern | ✅ Correct Approach |
|----------------|---------------------|
| Assume permissions are granted once and never check again | Check permissions on app resume and before each installation |
| Request all permissions simultaneously without explanation | Request sequentially with clear explanation for each |
| Block app launch with permission dialogs | Check permissions on-demand (when user clicks Install) |
| Ignore permission denial and proceed with installation | Block installation if required permissions are denied |
| Store permission state without validation | Validate stored state against actual permissions on startup |
| Show permission dialogs every app launch | Cache permission state and respect user's choice |

### Android Version Compatibility

| API Level | Version | Permission Handling |
|-----------|---------|---------------------|
| 30+ | Android 11+ | MANAGE_EXTERNAL_STORAGE required, INSTALL_UNKNOWN_APPS required |
| 29 | Android 10 | Scoped storage (no MANAGE_EXTERNAL_STORAGE), INSTALL_UNKNOWN_APPS required |
| 28 | Android 9 | Standard storage permissions, INSTALL_UNKNOWN_APPS required |

**Minimum SDK:** 29 (Android 10)
**Target SDK:** 34

On API 29 (Android 10), MANAGE_EXTERNAL_STORAGE doesn't exist. Use scoped storage APIs instead. However, Quest headsets typically run on Android 10+ with special storage access, so MANAGE_EXTERNAL_STORAGE may still be needed for OBB file access.

### Previous Story Intelligence

**From Story 1.7 (APK Installation with FileProvider):**
- Task 3 requires `MANAGE_EXTERNAL_STORAGE` for OBB file movement
- Task 4 requires `INSTALL_UNKNOWN_APPS` for APK installation via FileProvider
- Story 1.7 is marked `ready-for-dev` but blocked by missing permission flow

**From Story 1.6 (7z Extraction):**
- Battery optimization permission already implemented
- Pattern for permission request intents exists in MainActivity
- WakeLock pattern shows how to handle system permissions

**From MainViewModel.kt (existing code):**
- `isPermissionFlowActive` flag already exists (line ~500)
- Battery optimization permission flow already implemented
- Event system (`MainEvent.ShowToast`) can be reused for permission feedback

### Testing Requirements

**Manual Test Procedure:**
1. Fresh install on Quest device (no permissions granted)
2. Navigate to game list
3. Click "Install" on any game
4. Verify INSTALL_UNKNOWN_APPS permission dialog appears
5. Grant permission
6. Verify MANAGE_EXTERNAL_STORAGE permission dialog appears
7. Grant permission
8. Verify installation starts automatically
9. Revoke one permission in system settings
10. Return to app, verify permission revocation detected
11. Try to install another game, verify permission request appears again

**Automated Test Cases:**
```kotlin
@Test
fun permissionManager_returnsCorrectState_forAllPermissions()
@Test
fun permissionManager_cachesPermissionState_for30Seconds()
@Test
fun permissionFlow_requestsPermissionsInSequence()
@Test
fun permissionFlow_deniedPermission_blocksInstallation()
@Test
fun permissionFlow_revokedPermission_updatesState()
```

### Architecture Compliance

- **MVVM Pattern:** PermissionManager (data) → MainViewModel (viewmodel) → Compose UI (view)
- **StateFlow:** Use `StateFlow<PermissionFlowState>` for reactive UI updates
- **SharedFlow:** Use `MainEvent.PermissionRequestCompleted` for one-time events
- **SharedPreferences:** Use `context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)`
- **ActivityResultLauncher:** Register in MainActivity, trigger from ViewModel via event

### References

- [Source: Story 1.7] - Prerequisite for OBB movement and APK installation
- [Source: ui/MainViewModel.kt:500] - Existing isPermissionFlowActive flag
- [Source: AndroidManifest.xml] - Manifest permission declarations (to be verified)
- [Source: FR26] - Functional requirement for OBB file handling
- [Source: FR39] - Functional requirement for APK installation
- [Source: Android Docs - Request Permissions] - https://developer.android.com/training/permissions/requesting

### Dependencies

| Story | Dependency | Reason |
|-------|------------|--------|
| None | None | Foundational story for installation flow |

### Blocking Stories

| Story | Blocked | Reason |
|-------|---------|--------|
| Story 1.7 | Yes | Story 1.7 requires MANAGE_EXTERNAL_STORAGE and INSTALL_UNKNOWN_APPS permissions before installation |

## Change Log

- 2026-01-27: Story 1.8 created - Permission flow for installation, resolves Story 1.7 dependency block
