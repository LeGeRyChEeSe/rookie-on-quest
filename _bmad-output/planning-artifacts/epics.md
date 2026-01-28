---
stepsCompleted: ['step-01-validate-prerequisites', 'step-02-design-epics', 'step-03-create-stories', 'step-04-final-validation']
inputDocuments:
  - '_bmad-output/planning-artifacts/prd.md'
  - 'docs/architecture-app.md'
lastStep: 4
workflowStatus: 'complete'
completedDate: '2026-01-28'
---

# rookie-on-quest - Epic Breakdown

## Overview

This document provides the complete epic and story breakdown for rookie-on-quest, decomposing the requirements from the PRD and Architecture into implementable stories.

## Requirements Inventory

### Functional Requirements

**Catalog Management & Discovery:**
- **FR1:** Users can browse the complete VRPirates game catalog with thumbnails and icons
- **FR2:** Users can search games by name
- **FR3:** Users can filter games by category/genre
- **FR4:** Users can sort games by name (alphabetical ascending/descending)
- **FR5:** Users can sort games by size when 80%+ of metadata is loaded
- **FR6:** Users can view game details including description, screenshots, size, and version
- **FR7:** Users can mark games as favorites for quick access
- **FR8:** Users can view their favorites list
- **FR9:** System can sync catalog from VRPirates mirror automatically
- **FR10:** System can detect available catalog updates and notify users

**Download & Installation Queue:**
- **FR11:** Users can add games to download queue
- **FR12:** Users can choose "Download Only" mode (no automatic installation)
- **FR13:** Users can choose "Download & Install" mode (automatic installation after download)
- **FR14:** Users can view current download queue with position and status
- **FR15:** Users can pause active downloads
- **FR16:** Users can resume paused downloads
- **FR17:** Users can cancel downloads at any time
- **FR18:** Users can promote queued downloads to front of queue (priority installation)
- **FR19:** System can persist download queue across app restarts and device reboots
- **FR20:** System can automatically resume interrupted downloads after restart

**Download & Extraction Operations:**
- **FR21:** System can download game files with HTTP range resumption support
- **FR22:** System can extract password-protected 7z archives
- **FR23:** System can handle multi-part 7z archives (game.7z.001, game.7z.002, etc.)
- **FR24:** System can verify downloaded files against server checksums
- **FR25:** System can handle special installation instructions from install.txt files
- **FR26:** System can move OBB files to correct Android directories
- **FR27:** System can perform pre-flight storage space checks before downloads

**Progress Feedback & Visualization:**
- **FR28:** Users can view real-time download progress with percentage and bytes downloaded
- **FR29:** Users can view animated stickman character representing current operation phase
- **FR30:** Users can see stickman animations specific to each phase (downloading, extracting, copying OBB, installing)
- **FR31:** Users can see global progress indicator showing current step (e.g., "Step 2/4")
- **FR32:** Users can view contextual messages distinguishing "Download Only" vs "Install" modes
- **FR33:** System can display stickman "pause" animation during long operations (>2min)

**Notifications & User Alerts:**
- **FR34:** Users can receive local notifications when installations complete
- **FR35:** Users can receive notifications with optional sound alerts (configurable)
- **FR36:** Users can enable/disable sound notifications in settings
- **FR37:** Users can receive error notifications for failed downloads or installations
- **FR38:** System can display Quest VR overlay notifications compatible with active gameplay

**Game Installation:**
- **FR39:** System can install APK files via Android FileProvider
- **FR40:** System can install APKs silently when Shizuku is available and authorized
- **FR41:** System can fallback to manual confirmation installation when Shizuku unavailable
- **FR42:** System can detect Shizuku availability at runtime
- **FR43:** System can clean up temporary files after installation completes
- **FR44:** System can verify installed game version against catalog version

**Offline Mode & Synchronization:**
- **FR45:** Users can browse cached catalog when offline
- **FR46:** Users can view pre-downloaded games ready for installation when offline
- **FR47:** Users can install pre-downloaded games without internet connection
- **FR48:** System can detect network connectivity status
- **FR49:** System can display offline mode indicator in UI
- **FR50:** System can sync catalog when connection restored

**Permission & System Integration:**
- **FR51:** System can request required Android permissions sequentially
- **FR52:** System can provide clear explanations for each permission request
- **FR53:** System can function with graceful degradation if optional permissions denied
- **FR54:** System can detect battery optimization status
- **FR55:** System can maintain CPU wake lock during long extractions

**Settings & Configuration:**
- **FR56:** Users can configure notification sound preferences
- **FR57:** Users can view app version and check for updates
- **FR58:** Users can manually trigger catalog synchronization
- **FR59:** Users can export diagnostic logs for troubleshooting
- **FR60:** System can check GitHub for app updates on startup

### Build Automation & Release Management

- **FR61:** System can build release APK via automated CI/CD workflow on manual trigger
- **FR62:** System can sign APK with proper keystore configuration stored in secure credential manager
- **FR63:** System can extract version from build configuration file automatically
- **FR64:** System can extract changelog entries from changelog file for current version
- **FR65:** System can format release title as "Rookie On Quest vX.Y.Z"
- **FR66:** System can format APK filename as "RookieOnQuest-vX.Y.Z.apk"
- **FR67:** System can create release with proper version tag (vX.Y.Z)
- **FR68:** System can attach formatted APK to release
- **FR69:** System can populate release body with formatted changelog (✨🚀🔧 sections)
- **FR70:** System can support release candidate builds (RC suffix: vX.Y.Z-rc.1)
- **FR71:** System can run build validation on code change reviews (debug build + lint checks)
- **FR72:** System can cache build dependencies for faster builds
- **FR73:** System can run automated tests before creating release (if tests exist)

### Non-Functional Requirements

**Performance - VR Frame Rate (Critical):**
- **NFR-P1:** UI must maintain 60fps during all operations (downloads, installations, catalog browsing)
- **NFR-P2:** Background operations (downloads, extractions) must not cause frame drops or UI lag
- **NFR-P3:** Stickman animations must render at 60fps consistently without stuttering

**Performance - Response Time:**
- **NFR-P4:** User interactions (tap, scroll, search) must respond within 100ms
- **NFR-P5:** Catalog sync must complete initial load within 10 seconds on first launch
- **NFR-P6:** Search results must appear within 500ms of user input

**Performance - Resource Efficiency:**
- **NFR-P7:** Memory usage for stickman animation must not exceed 10MB
- **NFR-P8:** App background memory footprint must not exceed 150MB during downloads
- **NFR-P9:** Battery consumption during downloads must not exceed 5% increase vs v2.4.0 baseline

**Performance - Extraction Performance:**
- **NFR-P10:** 7z extraction progress must update UI at minimum 1Hz (once per second)
- **NFR-P11:** CPU wake lock must prevent Quest sleep during extractions >2 minutes

**Reliability - Data Persistence (Critical):**
- **NFR-R1:** Download queue must persist 100% across app crashes, force quits, and device reboots
- **NFR-R2:** Room Database transactions must be atomic (all-or-nothing) to prevent corrupted state
- **NFR-R3:** UI must restore complete download state within 2 seconds of app reopening

**Reliability - Download Resumption:**
- **NFR-R4:** HTTP range resumption must work for interrupted downloads with 0% data loss
- **NFR-R5:** WorkManager must automatically retry failed downloads with exponential backoff (max 3 retries)
- **NFR-R6:** Partial downloads must be resumable even after device reboot

**Reliability - Error Recovery:**
- **NFR-R7:** Failed extractions must clean up temp files automatically
- **NFR-R8:** Storage full errors must be detected pre-flight before download starts
- **NFR-R9:** Corrupted 7z archives must fail gracefully with clear error message and cleanup

**Reliability - Crash Resilience:**
- **NFR-R10:** No installation data loss during crash (WorkManager + Room guarantee)
- **NFR-R11:** App must handle Quest system kill (low memory) without queue corruption

**Usability - VR User Experience:**
- **NFR-U1:** All touch targets must be minimum 48dp for VR pointer accuracy
- **NFR-U2:** Critical errors must be visible and actionable without removing headset
- **NFR-U3:** Progress feedback must update continuously (no freezes >5 seconds without visual change)

**Usability - Installation Feedback:**
- **NFR-U4:** Stickman animation must change state visibly within 2 seconds of operation phase change
- **NFR-U5:** Completion notifications must appear within 3 seconds of installation success
- **NFR-U6:** Sound notifications must be audible but non-jarring (<1 second duration, moderate volume)

**Usability - Offline Experience:**
- **NFR-U7:** Offline mode must be detectable and indicated within 1 second of connection loss
- **NFR-U8:** Cached catalog must remain functional 100% offline (browse, search, sort cached data)
- **NFR-U9:** Network state changes must update UI within 2 seconds

**Usability - Permission Flow:**
- **NFR-U10:** Permission requests must be sequential (never request multiple simultaneously)
- **NFR-U11:** Each permission must have clear in-context explanation before request
- **NFR-U12:** App must function with graceful degradation if optional permissions denied

**Maintainability - Code Quality:**
- **NFR-M1:** All coroutine operations must use `ensureActive()` for clean cancellation
- **NFR-M2:** StateFlow updates must be atomic to prevent race conditions
- **NFR-M3:** Diagnostic logs must capture sufficient context for remote troubleshooting

**Maintainability - Backward Compatibility:**
- **NFR-M4:** Migration from v2.4.0 in-memory queue to v2.5.0 Room queue must be automatic and lossless
- **NFR-M5:** Min SDK must remain API 29 to support Quest 1 devices

**Maintainability - Testing:**
- **NFR-M6:** All FRs must have corresponding automated tests (unit, integration, or UI)
- **NFR-M7:** WorkManager restart scenarios must have instrumented tests with process kill simulation

**Maintainability - Deployment:**
- **NFR-M8:** APK size must not exceed 50MB (sideloading constraint)
- **NFR-M9:** App updates must not break existing downloads in progress

### Build & Release Infrastructure

**Build Performance:**
- **NFR-B1:** CI/CD automated build workflow must complete release build within 10 minutes
- **NFR-B2:** Build dependency caching must reduce build time by minimum 50% vs cold build
- **NFR-B3:** Workflow must support parallel execution of code quality and test validation tasks

**Release Reliability:**
- **NFR-B4:** Zero manual steps required between workflow trigger and release creation
- **NFR-B5:** Release APK must be byte-identical (SHA-256 hash match) to local Gradle release build output with same keystore and version, verified by `apksigner verify --print-certs` passing all checks and automated hash comparison in CI workflow
- **NFR-B6:** Changelog extraction must preserve formatting (✨🚀🔧 emojis, bullet points)
- **NFR-B7:** Release creation must fail within 30 seconds of version/tag mismatch detection, display error with specific mismatch reason (tag "v2.5.0" vs build "2.4.0") in CI workflow logs, and create zero artifacts on failure

**Security & Credentials:**
- **NFR-B8:** Keystore signing credentials must be stored in secure credential manager (never in repository)
- **NFR-B9:** Workflow must have explicit permissions for release creation
- **NFR-B10:** Workflow must validate tag format matches version in build configuration file

**Developer Experience:**
- **NFR-B11:** Workflow must be manually triggerable via web interface "Run workflow" button
- **NFR-B12:** Workflow must accept version input parameter for custom builds (RCs, hotfixes)
- **NFR-B13:** Release workflow must create version tag if it doesn't exist (with confirmation)
- **NFR-B14:** PR validation builds must display build status with 3 levels of feedback: pass/fail icon, error count by category (lint, test, compilation), and direct link to failing test output, all visible in PR conversation view within 2 minutes of build completion

### Additional Requirements

**Architecture & Technical:**
- MVVM architecture pattern with Kotlin + Jetpack Compose (existing)
- Single-Activity Compose architecture (existing)
- Room Database for local persistence (existing + new table `install_queue` required)
- WorkManager integration for persistent background operations (NEW - critical for v2.5.0)
- StateFlow reactive state management (existing)
- Coroutine-based async operations with cancellation support (existing)
- Android Notification Channels: DOWNLOAD_PROGRESS, INSTALL_COMPLETE, ERRORS (NEW)
- Wake Lock management for long extractions (NEW - uses WAKE_LOCK permission)
- Network state monitoring (NEW - uses ACCESS_NETWORK_STATE permission)
- Foreground Service for WorkManager (NEW - uses FOREGROUND_SERVICE permission)

**Stickman Animation System (NEW - Critical UX Feature):**
- Custom Compose animation with state machine (downloading, extracting, copying, installing, paused)
- 60fps rendering requirement
- Memory footprint <10MB
- State transitions <2 seconds
- Asset requirements: 5 animation states (vector or bitmap sprites)

**Migration Requirements:**
- Automatic migration from v2.4.0 StateFlow queue to v2.5.0 Room-backed queue
- Lossless migration of any active downloads at time of update
- Backward compatibility: v2.5.0 must handle v2.4.0 data structures gracefully

**Build & Release Infrastructure:**
- Build System: Gradle (Kotlin DSL) with build variants Debug (unobfuscated) and Release (R8 obfuscation, signed)
- CI/CD Platform: GitHub Actions for automated builds and releases
- Distribution: GitHub Releases (APK downloads)
- Minimum SDK: Android 10+ (API 29) maintained across all releases
- Secure credential management: Keystore stored in GitHub Secrets (never in repository)

### FR Coverage Map

**Epic 1: Persistent Installation Queue System**
- FR11: Users can add games to download queue
- FR12: Users can choose "Download Only" mode
- FR13: Users can choose "Download & Install" mode
- FR14: Users can view current download queue with position and status
- FR15: Users can pause active downloads
- FR16: Users can resume paused downloads
- FR17: Users can cancel downloads at any time
- FR18: Users can promote queued downloads to front of queue
- FR19: System can persist download queue across app restarts and device reboots
- FR20: System can automatically resume interrupted downloads after restart
- FR21: System can download game files with HTTP range resumption support
- FR22: System can extract password-protected 7z archives
- FR23: System can handle multi-part 7z archives
- FR24: System can verify downloaded files against server checksums
- FR25: System can handle special installation instructions from install.txt files
- FR26: System can move OBB files to correct Android directories
- FR27: System can perform pre-flight storage space checks before downloads
- FR39: System can install APK files via Android FileProvider
- FR43: System can clean up temporary files after installation completes
- FR44: System can verify installed game version against catalog version
- FR51: System can request required Android permissions sequentially
- FR52: System can provide clear explanations for each permission request
- FR53: System can function with graceful degradation if optional permissions denied
- FR54: System can detect battery optimization status
- FR55: System can maintain CPU wake lock during long extractions

**Epic 2: Enhanced Progress Visualization**
- FR28: Users can view real-time download progress with percentage and bytes downloaded
- FR29: Users can view animated stickman character representing current operation phase
- FR30: Users can see stickman animations specific to each phase
- FR31: Users can see global progress indicator showing current step
- FR32: Users can view contextual messages distinguishing "Download Only" vs "Install" modes
- FR33: System can display stickman "pause" animation during long operations

**Epic 3: Installation Completion Notifications**
- FR34: Users can receive local notifications when installations complete
- FR35: Users can receive notifications with optional sound alerts
- FR36: Users can enable/disable sound notifications in settings
- FR37: Users can receive error notifications for failed downloads or installations
- FR38: System can display Quest VR overlay notifications compatible with active gameplay
- FR56: Users can configure notification sound preferences

**Epic 4: Intelligent Catalog Sorting**
- FR5: Users can sort games by size when 80%+ of metadata is loaded
- FR9: System can sync catalog from VRPirates mirror automatically
- FR10: System can detect available catalog updates and notify users

**Epic 5: Robust Offline Mode**
- FR45: Users can browse cached catalog when offline
- FR46: Users can view pre-downloaded games ready for installation when offline
- FR47: Users can install pre-downloaded games without internet connection
- FR48: System can detect network connectivity status
- FR49: System can display offline mode indicator in UI
- FR50: System can sync catalog when connection restored

**Epic 6: Silent Installation (Shizuku) - Optional**
- FR40: System can install APKs silently when Shizuku is available and authorized
- FR41: System can fallback to manual confirmation installation when Shizuku unavailable
- FR42: System can detect Shizuku availability at runtime

**Epic 7: Catalog Discovery & Management**
- FR1: Users can browse the complete VRPirates game catalog with thumbnails and icons
- FR2: Users can search games by name
- FR3: Users can filter games by category/genre
- FR4: Users can sort games by name (alphabetical ascending/descending)
- FR6: Users can view game details including description, screenshots, size, and version
- FR7: Users can mark games as favorites for quick access
- FR8: Users can view their favorites list
- FR57: Users can view app version and check for updates
- FR58: Users can manually trigger catalog synchronization
- FR59: Users can export diagnostic logs for troubleshooting
- FR60: System can check GitHub for app updates on startup

**Epic 8: Build Automation & Release Management**
- FR61: System can build release APK via automated CI/CD workflow on manual trigger
- FR62: System can sign APK with proper keystore configuration stored in secure credential manager
- FR63: System can extract version from build configuration file automatically
- FR64: System can extract changelog entries from changelog file for current version
- FR65: System can format release title as "Rookie On Quest vX.Y.Z"
- FR66: System can format APK filename as "RookieOnQuest-vX.Y.Z.apk"
- FR67: System can create release with proper version tag (vX.Y.Z)
- FR68: System can attach formatted APK to release
- FR69: System can populate release body with formatted changelog (✨🚀🔧 sections)
- FR70: System can support release candidate builds (RC suffix: vX.Y.Z-rc.1)
- FR71: System can run build validation on code change reviews (debug build + lint checks)
- FR72: System can cache build dependencies for faster builds
- FR73: System can run automated tests before creating release (if tests exist)

## Epic List

### Epic 1: Persistent Installation Queue System
Users can trust that downloads and installations never disappear, even after app crashes, force quits, or device reboots. The queue persists across all disruptions and automatically resumes where it left off.

**FRs covered:** FR11-FR27, FR39, FR43-FR44, FR51-FR55 (25 FRs)

**Key Deliverables:**
- Room Database table `install_queue` for persistent storage
- WorkManager integration for background task resilience
- Automatic migration from v2.4.0 StateFlow queue to v2.5.0 Room-backed queue
- HTTP range resumption for interrupted downloads
- Pre-flight storage checks and error recovery
- Sequential permission flow with clear explanations

### Epic 2: Enhanced Progress Visualization
Users always know exactly what's happening during downloads and installations through an engaging animated stickman character that represents each operation phase with global progress tracking.

**FRs covered:** FR28-FR33 (6 FRs)

**Key Deliverables:**
- Stickman animation system with 5 states (downloading, extracting, copying OBB, installing, paused)
- 60fps Compose animations meeting NFR-P3
- Global progress indicator (Step X/4)
- Contextual messages for "Download Only" vs "Install" modes
- Memory-efficient animation (<10MB per NFR-P7)

### Epic 3: Installation Completion Notifications
Users can multitask in VR and get notified when installations finish, with configurable sound alerts that don't interrupt gameplay.

**FRs covered:** FR34-FR38, FR56 (6 FRs)

**Key Deliverables:**
- Android Notification Channels: DOWNLOAD_PROGRESS, INSTALL_COMPLETE, ERRORS
- Quest VR overlay notifications compatible with active gameplay
- Configurable sound notifications (gentle chime, <1 second)
- Settings toggle for sound alerts
- Notification delivery within 3 seconds (NFR-U5)

### Epic 4: Intelligent Catalog Sorting
Users can sort games by size without experiencing UI jumping, with clear feedback about metadata synchronization progress.

**FRs covered:** FR5, FR9-FR10 (3 FRs)

**Key Deliverables:**
- Metadata load progress tracking (StateFlow)
- Conditional sort availability (disabled until 80% metadata loaded)
- UI badge showing sync progress ("🔄 Sync... X%")
- Automatic sort activation when threshold reached
- Zero visual jumping after sort enabled

### Epic 5: Robust Offline Mode
Users can browse cached catalogs and install pre-downloaded games without internet connection, with clear offline indicators and automatic sync when connection returns.

**FRs covered:** FR45-FR50 (6 FRs)

**Key Deliverables:**
- Network state detection using ACCESS_NETWORK_STATE permission
- Offline mode UI indicators (<1 second detection per NFR-U7)
- Cached catalog fully functional (browse, search, sort)
- Disable download buttons for non-cached games when offline
- Automatic catalog sync on connection restoration

### Epic 6: Silent Installation (Shizuku) - Optional
Advanced users can install games without manual confirmation using Shizuku, achieving zero-friction batch installations.

**FRs covered:** FR40-FR42 (3 FRs)

**Key Deliverables:**
- Shizuku SDK integration (v13.1.5)
- Runtime Shizuku availability detection
- Silent APK installation when authorized
- Graceful fallback to FileProvider when Shizuku unavailable
- User documentation for Shizuku setup

### Epic 7: Catalog Discovery & Management
Users can efficiently find, filter, search, and favorite games with enhanced diagnostic capabilities for troubleshooting.

**FRs covered:** FR1-FR4, FR6-FR8, FR57-FR60 (11 FRs)

**Key Deliverables:**
- Enhanced catalog browsing with thumbnails/icons
- Search and filter capabilities
- Favorites system (mark/unmark, view list)
- App version display and GitHub update checks
- Manual catalog sync trigger
- Diagnostic log export functionality

### Epic 8: Build Automation & Release Management
Developers can create reliable, automated release builds with proper signing, changelog extraction, and GitHub releases through a streamlined CI/CD workflow.

**FRs covered:** FR61-FR73 (13 FRs)

**Key Deliverables:**
- GitHub Actions workflow for automated release builds
- Secure keystore management via GitHub Secrets
- Automatic version and changelog extraction from build config
- Proper APK signing and release creation
- PR validation builds with status feedback
- Build dependency caching for faster builds
- Support for release candidates (RC builds)

## Epic 1: Persistent Installation Queue System

Users can trust that downloads and installations never disappear, even after app crashes, force quits, or device reboots. The queue persists across all disruptions and automatically resumes where it left off.

### Story 1.1: Room Database Queue Table Setup

As a developer,
I want to create the Room Database table for persistent queue storage,
So that installation queue state survives app restarts and device reboots.

**Acceptance Criteria:**

**Given** the app is launched for the first time on v2.5.0
**When** Room Database initializes
**Then** table `install_queue` is created with columns: releaseName (PK), status, progress, downloadedBytes, totalBytes, queuePosition, createdAt, lastUpdatedAt
**And** appropriate indexes are created for query performance
**And** DAO methods support CRUD operations (insert, update, delete, query)

### Story 1.2: Queue State Migration from v2.4.0

As a user upgrading from v2.4.0,
I want my existing download queue to be automatically migrated,
So that I don't lose my active downloads during the update.

**Acceptance Criteria:**

**Given** user has v2.4.0 with active downloads in memory (StateFlow)
**When** app updates to v2.5.0 and launches
**Then** migration logic detects v2.4.0 queue state
**And** migrates all queue items to Room Database table
**And** preserves download progress, status, and queue position
**And** clears old v2.4.0 memory-based queue
**And** migration is lossless (0% data loss per NFR-R1)

### Story 1.3: WorkManager Download Task Integration

As a user,
I want downloads to continue even if I force-close the app,
So that large game downloads complete reliably in the background.

**Acceptance Criteria:**

**Given** a game is added to download queue
**When** WorkManager schedules the download task
**Then** WorkManager Worker executes download with constraints (battery not low, storage not low)
**And** download progress updates Room Database in real-time
**And** Worker survives app force-close and device reboot
**And** Worker automatically resumes on restart per NFR-R6
**And** exponential backoff retry (max 3) on failures per NFR-R5

### Story 1.4: Download Queue UI with Persist/Resume

As a user,
I want to view, pause, resume, cancel, and prioritize downloads in the queue,
So that I can control my installations actively.

**Acceptance Criteria:**

**Given** user has games in download queue
**When** user views queue UI (overlay)
**Then** displays all queued items with status, progress, position (FR14)
**And** user can pause active download (FR15)
**And** user can resume paused download (FR16)
**And** user can cancel download with cleanup (FR17)
**And** user can promote download to front of queue (FR18)
**And** UI updates reactively from Room Database Flow<List<QueuedInstallEntity>>
**And** UI restores within 2 seconds of app reopen (NFR-R3)

### Story 1.5: HTTP Range Resumption for Interrupted Downloads

As a user,
I want interrupted downloads to resume from where they stopped,
So that I don't waste bandwidth re-downloading large files.

**Acceptance Criteria:**

**Given** download is interrupted (app crash, network loss, manual pause)
**When** download resumes
**Then** HTTP Range header sends last downloaded byte position
**And** download continues from interruption point with 0% data loss (NFR-R4)
**And** downloadedBytes and totalBytes update correctly in Room DB
**And** partial file is validated and resumed seamlessly

### Story 1.6: 7z Extraction with Progress Tracking

As a user,
I want to see extraction progress for compressed game files,
So that I know the app hasn't frozen during long extractions.

**Acceptance Criteria:**

**Given** game download completes and 7z archive needs extraction
**When** extraction begins
**Then** WorkManager Worker extracts with Apache Commons Compress
**And** handles password-protected archives (FR22)
**And** handles multi-part archives (.7z.001, .7z.002) sorted correctly (FR23)
**And** extraction progress updates Room DB at minimum 1Hz (NFR-P10)
**And** CPU wake lock prevents Quest sleep during extraction >2min (NFR-P11, FR55)
**And** extraction completes with marker file `extraction_done.marker`
**And** failed extractions clean up temp files automatically (NFR-R7)

### Story 1.7: APK Installation with FileProvider

As a user,
I want games to install automatically after download/extraction,
So that I can launch them immediately without manual steps.

**Acceptance Criteria:**

**Given** game extraction completes successfully
**When** installation phase begins
**Then** handles special install.txt instructions if present (FR25)
**And** moves OBB files to /Android/obb/{packageName}/ (FR26)
**And** installs APK via FileProvider intent (FR39)
**And** verifies installed version matches catalog (FR44)
**And** cleans up temp files after installation (FR43)
**And** handles installation errors gracefully with user notification

### Story 1.8: Sequential Permission Flow

As a user launching the app for the first time,
I want to grant necessary permissions one at a time with clear explanations,
So that I understand why each permission is needed.

**Acceptance Criteria:**

**Given** user launches app for first time
**When** permission flow begins
**Then** requests permissions sequentially (never multiple at once per NFR-U10, FR51)
**And** shows clear explanation dialog before each request (FR52, NFR-U11)
**And** permission order: INSTALL_UNKNOWN_APPS → MANAGE_EXTERNAL_STORAGE → IGNORE_BATTERY_OPTIMIZATIONS
**And** app functions with graceful degradation if optional permissions denied (FR53, NFR-U12)
**And** detects battery optimization status (FR54)
**And** stores permission grant state to avoid re-requesting

## Epic 2: Enhanced Progress Visualization

Users always know exactly what's happening during downloads and installations through an engaging animated stickman character that represents each operation phase with global progress tracking.

### Story 2.1: Stickman Animation State Machine Foundation

As a developer,
I want to create a robust state machine for stickman animations,
So that phase transitions are smooth and state is always consistent with queue operations.

**Acceptance Criteria:**

**Given** the app is running with active downloads/installations
**When** queue processor changes operation phase
**Then** state machine maintains 5 distinct states: DOWNLOADING, EXTRACTING, COPYING_OBB, INSTALLING, PAUSED
**And** state transitions are atomic and thread-safe
**And** state changes trigger animation updates within 16ms (60fps requirement per NFR-P3)
**And** state machine tracks current phase progress (0-100%)
**And** invalid state transitions are prevented (e.g., PAUSED can only follow active states)

### Story 2.2: Phase-Specific Animation Implementations

As a user,
I want to see distinct animations for each installation phase,
So that I understand what the app is doing at any moment.

**Acceptance Criteria:**

**Given** an installation task is active
**When** each phase executes
**Then** DOWNLOADING animation shows stickman moving right with download progress (FR29, FR30)
**And** EXTRACTING animation shows stickman rotating/decompressing with extraction progress
**And** COPYING_OBB animation shows stickman transferring files
**And** INSTALLING animation shows stickman installing APK package
**And** PAUSED animation shows stickman in idle/waiting pose (FR33)
**And** all animations render at 60fps constant (NFR-P3)
**And** animations use Compose `animateFloatAsState` for smooth interpolation
**And** memory footprint per animation <2MB (total <10MB per NFR-P7)

### Story 2.3: Global Progress Indicator Integration

As a user,
I want to see a global progress indicator showing which step I'm on,
So that I know how close the installation is to completion.

**Acceptance Criteria:**

**Given** an installation task is in progress
**When** user views the queue UI
**Then** displays global progress as "Step X/4" or "Step X/5" depending on mode (FR31)
**And** Download Only mode: Step 1 (Download) → Step 2 (Extract)
**And** Download & Install mode: Step 1 (Download) → Step 2 (Extract) → Step 3 (Copy OBB) → Step 4 (Install APK)
**And** global indicator updates immediately on phase change (<16ms per NFR-P3)
**And** indicator is visible on all queue item cards
**And** indicator uses clear iconography (download icon, extract icon, etc.)

### Story 2.4: Contextual Message System for Download/Install Modes

As a user,
I want to see contextual messages that adapt to the current phase and mode,
So that I always know exactly what's happening and what to expect next.

**Acceptance Criteria:**

**Given** an installation task is active
**When** user views progress messages
**Then** downloading phase shows: "Téléchargement de [nom]... X% (Y MB/s)" (FR32)
**And** extracting phase shows: "Extraction... X%" with estimated time remaining (FR33)
**And** copying OBB phase shows: "Copie des fichiers OBB... X%"
**And** installing APK phase shows: "Installation de l'APK... Appuyez sur Installer" (FR33)
**And** messages distinguish between "Download Only" and "Download & Install" modes (FR32)
**And** Download Only mode: messages omit installation steps
**And** error messages are clear and actionable (e.g., "Échec du téléchargement - Vérifiez votre connexion")
**And** messages update at minimum 1Hz (NFR-P10)

### Story 2.5: Animation Performance Optimization for Quest VR

As a Quest VR user,
I want animations to run smoothly without draining battery or causing thermal throttling,
So that I can keep the app open during long installations without performance issues.

**Acceptance Criteria:**

**Given** animations are rendering during active installations
**When** profiling on Meta Quest 2 and Quest 3
**Then** maintains 60fps constant framerate (NFR-P3)
**And** total animation memory footprint <10MB (NFR-P7)
**And** CPU usage for animations <5% on Quest 2 (NFR-P9)
**And** uses Compose `remember` and `derivedStateOf` for optimization
**And** animations pause when app is backgrounded to save battery
**And** no frame drops during state transitions
**And** thermal impact minimal (no thermal throttling after 30min continuous use)

## Epic 3: Installation Completion Notifications

Users can multitask in VR and get notified when installations finish, with configurable sound alerts that don't interrupt gameplay.

### Story 3.1: Android Notification Channels Setup

As a developer,
I want to create properly configured Android notification channels,
So that users have granular control over notification types and behaviors.

**Acceptance Criteria:**

**Given** the app launches for the first time on v2.5.0
**When** notification system initializes
**Then** creates 3 notification channels with proper configuration
**And** DOWNLOAD_PROGRESS channel (low priority, silent, shows ongoing downloads)
**And** INSTALL_COMPLETE channel (default priority, optional sound, shows completion)
**And** ERRORS channel (high priority, sound enabled, shows failures)
**And** each channel has proper name, description, and importance level
**And** channels respect Android 8.0+ (API 26+) requirements
**And** channels appear in system settings for user customization

### Story 3.2: Installation Complete Notifications with Sound

As a VR user,
I want to receive notifications when installations complete with optional sound alerts,
So that I know when games are ready to play without checking the app constantly.

**Acceptance Criteria:**

**Given** a game installation completes successfully
**When** the app sends completion notification
**Then** notification displays game name and "Installation Complete" message (FR34)
**And** notification includes action button "Launch Game"
**And** optional sound alert plays (gentle chime, <1 second duration) (FR35)
**And** sound respects user's toggle setting in app Settings (FR36, FR56)
**And** notification appears as Quest VR overlay without interrupting active gameplay (FR38)
**And** notification persists until user dismisses or launches game
**And** notification delivery occurs within 3 seconds of completion (NFR-U5)
**And** sound preference toggle in Settings is clearly labeled and accessible

### Story 3.3: Error Notifications System

As a user,
I want to receive clear notifications when downloads or installations fail,
So that I can quickly understand what went wrong and take corrective action.

**Acceptance Criteria:**

**Given** a download or installation fails
**When** the error is detected
**Then** notification displays clear error message (FR37)
**And** error types include: "Download failed - Check connection", "Extraction failed - Corrupted file", "Installation failed - Insufficient storage"
**And** notification includes action buttons: "Retry" and "View Details"
**And** "Retry" button immediately re-queues the failed task
**And** "View Details" button opens detailed error log view
**And** error notifications use ERRORS channel (high priority, sound enabled)
**And** error notifications appear as Quest VR overlay (FR38)
**And** notification delivery within 3 seconds of error detection (NFR-U5)

### Story 3.4: Quest VR Overlay Notification Compatibility

As a Quest VR user playing games,
I want notifications to appear as non-intrusive overlays,
So that I'm informed of installation status without breaking my VR immersion.

**Acceptance Criteria:**

**Given** user is actively playing a VR game on Quest headset
**When** Rookie On Quest sends any notification
**Then** notification appears as Quest system overlay (heads-up notification)
**And** notification doesn't interrupt or pause active VR gameplay (FR38)
**And** notification is readable in VR without removing headset
**And** notification respects Quest's Do Not Disturb mode
**And** notification delivery latency <3 seconds (NFR-U5)
**And** tested on Meta Quest 2 and Quest 3 with active VR apps
**And** sound alerts (if enabled) are audible in VR without overpowering game audio
**And** notifications auto-dismiss after 10 seconds if not interacted with

## Epic 4: Intelligent Catalog Sorting

Users can sort games by size without experiencing UI jumping, with clear feedback about metadata synchronization progress.

### Story 4.1: Metadata Load Progress Tracking System

As a developer,
I want to track metadata loading progress in real-time,
So that the app knows when enough data exists to enable size-based sorting.

**Acceptance Criteria:**

**Given** the app launches and catalog is loaded
**When** metadata fetching begins in background
**Then** StateFlow tracks metadata completion percentage: (games with size != null) / total games
**And** prioritizes metadata fetching for visible games first (current scroll position)
**And** prioritizes metadata fetching for search results when user searches
**And** metadata fetch loop runs continuously in background (low priority coroutine)
**And** progress updates trigger UI recomposition for sync badge
**And** metadata fetching respects network connectivity (pauses when offline per NFR-U7)
**And** fetching uses exponential backoff on server errors

### Story 4.2: Conditional Sort by Size with UI Feedback

As a user,
I want to sort games by size only when enough metadata is loaded,
So that I don't see games jumping around the list as sizes load in.

**Acceptance Criteria:**

**Given** user views the catalog with sort options
**When** metadata completion is below 80%
**Then** "Sort by Size" option is disabled with badge "🔄 Sync... X%" (FR5)
**And** badge updates in real-time as metadata loads
**And** badge shows exact percentage (e.g., "🔄 Sync... 73%")
**And** tooltip explains: "Size sorting available when 80% of games synced"
**When** metadata completion reaches 80%
**Then** "Sort by Size" option becomes enabled automatically
**And** badge changes to "✓ Ready" for 2 seconds then disappears
**And** enabling sort causes zero visual jumping (NFR-U9)
**And** games without size data appear at bottom of sorted list
**And** sort persists in user preferences across app restarts

### Story 4.3: Catalog Update Detection and Synchronization

As a user,
I want to be notified when new catalog versions are available,
So that I can access newly added games without manually checking.

**Acceptance Criteria:**

**Given** the app is running with network connectivity
**When** app checks VRPirates mirror for catalog updates (on startup + every 6 hours)
**Then** compares local VRP-GameList.txt hash with server hash (FR10)
**And** detects when server has newer catalog version
**When** newer catalog detected
**Then** displays notification: "New catalog available - X games added/updated" (FR10)
**And** notification includes action button "Sync Now"
**And** user can dismiss notification to sync later
**When** user taps "Sync Now" or manually triggers sync from Settings (FR58)
**Then** downloads meta.7z from VRPirates mirror (FR9)
**And** extracts catalog, thumbnails, and icons
**And** updates Room Database with new/changed game entries
**And** preserves user favorites during catalog update
**And** shows progress indicator during sync (<5 seconds for typical catalog per NFR-P1)
**And** sync completes with success message: "Catalog updated - X new games"

## Epic 5: Robust Offline Mode

Users can browse cached catalogs and install pre-downloaded games without internet connection, with clear offline indicators and automatic sync when connection returns.

### Story 5.1: Network State Detection System

As a developer,
I want to detect network connectivity changes in real-time,
So that the app can adapt its behavior based on online/offline state.

**Acceptance Criteria:**

**Given** the app is running on a Quest device
**When** network connectivity changes
**Then** uses ACCESS_NETWORK_STATE permission to detect connectivity (FR48)
**And** ConnectivityManager.NetworkCallback registers for real-time network changes
**And** StateFlow<Boolean> tracks online/offline state
**And** detection latency <1 second from actual network change (NFR-U7)
**And** BroadcastReceiver handles network state transitions
**And** differentiates between Wi-Fi, cellular, and no connection
**And** handles airplane mode and Wi-Fi toggle events
**And** persists last known connectivity state for app restart
**And** connectivity state accessible throughout app via repository

### Story 5.2: Offline Mode UI Indicators and Cached Catalog Functionality

As a user,
I want to see clear indicators when I'm offline and still be able to browse the catalog,
So that I understand what features are available without internet.

**Acceptance Criteria:**

**Given** the device loses network connectivity
**When** offline state is detected
**Then** displays "Mode Hors-ligne" indicator in app header/toolbar (FR49)
**And** offline indicator uses distinct icon (e.g., ⚠️ or 🔌) and color (amber/orange)
**And** catalog remains fully browsable using cached data (FR45)
**And** search functionality works on cached catalog
**And** sort functionality works on cached catalog (including size sort if previously loaded)
**And** filter functionality works on cached catalog
**And** favorites marking/unmarking works offline
**And** game detail views show cached thumbnails, icons, descriptions
**And** download buttons are disabled for non-cached games with tooltip: "Download requires internet connection"
**And** download buttons remain enabled for pre-downloaded games (shows "Install" instead)
**And** cached catalog data includes all metadata from last successful sync

### Story 5.3: Pre-Downloaded Game Installation Offline

As a user,
I want to install games I've already downloaded even when offline,
So that I don't need internet to complete installations.

**Acceptance Criteria:**

**Given** user previously downloaded games in "Download Only" mode
**When** viewing catalog while offline
**Then** pre-downloaded games display badge "📥 Ready to Install" (FR46)
**And** "Install" button is enabled for pre-downloaded games
**And** clicking "Install" starts installation without network check
**And** installation proceeds completely offline (FR47)
**And** extracts 7z archives from local cache
**And** handles install.txt instructions from local files
**And** moves OBB files to /Android/obb/{packageName}/
**And** installs APK via FileProvider
**And** verifies all required files present before starting (fail fast if incomplete)
**And** error message if files missing: "Installation incomplete - Re-download required when online"
**And** installation completes successfully with notification
**And** installed game marked as such in catalog

### Story 5.4: Automatic Catalog Sync on Connection Restoration

As a user,
I want the app to automatically sync when I come back online,
So that I always have the latest catalog without manual intervention.

**Acceptance Criteria:**

**Given** the device regains network connectivity after being offline
**When** online state is detected
**Then** displays toast notification: "Back online - Syncing catalog..." (FR50)
**And** automatically triggers catalog sync from VRPirates mirror
**And** sync happens in background without blocking UI
**And** updates catalog, thumbnails, and icons if newer version available
**And** preserves user favorites during sync
**And** resumes any paused downloads automatically (if they were paused due to offline)
**And** updates download queue status from PAUSED_OFFLINE to QUEUED
**And** sync completion shows toast: "Catalog synced - X new/updated games"
**And** offline indicator disappears from UI
**And** download buttons re-enable for all games
**And** metadata fetching resumes for games missing size/description data

## Epic 6: Silent Installation (Shizuku) - Optional

Advanced users can install games without manual confirmation using Shizuku, achieving zero-friction batch installations.

### Story 6.1: Shizuku SDK Integration and Runtime Detection

As a developer,
I want to integrate Shizuku SDK and detect its availability at runtime,
So that the app can use silent installation when possible while maintaining compatibility when unavailable.

**Acceptance Criteria:**

**Given** the app is running on a Quest device
**When** app initializes
**Then** integrates Shizuku SDK v13.1.5 with proper dependencies
**And** detects Shizuku availability at runtime (FR42)
**And** checks if Shizuku service is running
**And** checks if Shizuku authorization is granted to Rookie On Quest
**And** StateFlow<ShizukuStatus> tracks three states: AVAILABLE, UNAVAILABLE, UNAUTHORIZED
**And** AVAILABLE: Shizuku running and app authorized
**And** UNAVAILABLE: Shizuku not installed or service not running
**And** UNAUTHORIZED: Shizuku running but app not granted permission
**And** status updates automatically when Shizuku state changes
**And** handles Shizuku permission request flow if unauthorized
**And** persists Shizuku preference (user opted in/out)

### Story 6.2: Silent APK Installation with Shizuku

As an advanced user with Shizuku configured,
I want games to install silently without manual confirmation,
So that I can queue multiple games and have them install automatically.

**Acceptance Criteria:**

**Given** Shizuku is available and authorized (ShizukuStatus.AVAILABLE)
**When** installation phase begins for a queued game
**Then** uses Shizuku PackageInstaller API for silent installation (FR40)
**And** installs APK without showing system installation dialog
**And** installation proceeds in background with progress tracking
**And** updates queue status to INSTALLING_SILENT
**And** handles installation callbacks from Shizuku API
**And** detects installation success via callback
**And** detects installation failure via callback with error code
**And** shows completion notification after successful silent install
**And** zero-friction batch installations (no user interaction required)
**And** processes multiple queued installations sequentially without confirmation prompts

### Story 6.3: Graceful Fallback to FileProvider Installation

As a user without Shizuku configured,
I want the app to work normally with manual installation confirmation,
So that Shizuku remains optional and doesn't break standard functionality.

**Acceptance Criteria:**

**Given** Shizuku is unavailable or unauthorized (ShizukuStatus.UNAVAILABLE or UNAUTHORIZED)
**When** installation phase begins for a queued game
**Then** automatically falls back to FileProvider installation method (FR41)
**And** uses standard Android PackageInstaller intent
**And** shows system installation dialog requiring user confirmation
**And** updates queue status to INSTALLING_MANUAL
**And** installation completes normally via FileProvider flow
**And** no errors or crashes when Shizuku unavailable
**When** Shizuku installation fails (exception or error callback)
**Then** catches Shizuku exceptions gracefully
**And** logs error with details for debugging
**And** automatically retries installation using FileProvider fallback
**And** displays toast: "Silent install failed - Manual confirmation required"
**And** user sees standard installation dialog
**And** includes in-app help section: "How to setup Shizuku for silent installations"
**And** help includes: Shizuku download link, authorization steps, benefits explanation

## Epic 7: Catalog Discovery & Management

Users can efficiently find, filter, search, and favorite games with enhanced diagnostic capabilities for troubleshooting.

### Story 7.1: Enhanced Catalog Browsing with Thumbnails and Icons

As a user,
I want to browse the complete game catalog with visual thumbnails and icons,
So that I can quickly identify games and make informed download decisions.

**Acceptance Criteria:**

**Given** the app has synced the catalog from VRPirates mirror
**When** user views the main catalog screen
**Then** displays complete VRPirates game catalog (FR1)
**And** each game card shows thumbnail image, icon, name, and size
**And** uses staggered grid layout with 3 columns on Quest (800dp+ width)
**And** uses Coil library for efficient image loading and caching
**And** lazy loading with pagination (load 50 games at a time)
**And** smooth scrolling at 60fps (NFR-P3)
**And** placeholder images shown while thumbnails load
**And** handles missing thumbnails gracefully (default placeholder)
**And** memory-efficient image loading (<50MB for visible thumbnails per NFR-P7)
**And** tapping game card navigates to game detail view

### Story 7.2: Search and Filter System

As a user,
I want to search for games by name and filter by category,
So that I can quickly find specific games in a large catalog.

**Acceptance Criteria:**

**Given** user is viewing the catalog
**When** user uses search or filter features
**Then** search bar filters games by name in real-time (FR2)
**And** search uses case-insensitive matching
**And** search debounce of 300ms prevents excessive filtering
**And** search highlights matching text in results
**And** filter dropdown allows selection by category/genre (FR3)
**And** categories include: Action, Adventure, Puzzle, Sports, Simulation, etc.
**And** sort options include: Name A-Z, Name Z-A, Size (conditional) (FR4)
**And** sort by name ascending (A-Z) and descending (Z-A)
**And** search/filter/sort state persists during session
**And** "Clear Filters" button resets all filters and search
**And** shows result count: "Showing X of Y games"
**And** search and filter work offline on cached catalog

### Story 7.3: Favorites System

As a user,
I want to mark games as favorites and view my favorites list,
So that I can quickly access games I'm interested in.

**Acceptance Criteria:**

**Given** user is browsing the catalog
**When** user marks/unmarks favorites
**Then** game cards display star icon for favorite toggle (FR7)
**And** tapping star marks game as favorite (filled star ⭐)
**And** tapping filled star unmarks game as favorite (empty star ☆)
**And** favorite state persists in Room Database `isFavorite` column
**And** favorite action works offline (syncs state locally)
**And** navigation drawer includes "Favorites" menu item (FR8)
**And** tapping "Favorites" filters catalog to show only favorited games
**And** favorites list shows same card layout as main catalog
**And** favorites list is empty state: "No favorites yet - Star games to add them here"
**And** favorites persist across app restarts and device reboots
**And** favorites preserved during catalog sync/updates

### Story 7.4: Game Details View

As a user,
I want to view comprehensive game details including screenshots and version info,
So that I can make informed decisions before downloading.

**Acceptance Criteria:**

**Given** user taps on a game card
**When** game detail view opens
**Then** displays full game information (FR6)
**And** shows game name, icon, description (scrollable text)
**And** shows size in human-readable format (e.g., "4.2 GB")
**And** shows catalog version and installed version (if installed)
**And** version comparison: "Update available" badge if catalog > installed
**And** swipeable screenshots gallery (horizontal pager)
**And** screenshots load lazily with Coil
**And** screenshots zoomable with pinch gesture
**And** shows download button states: "Download", "Install", "Installed", "Update"
**And** "Download" button allows mode selection: "Download Only" or "Download & Install"
**And** "Installed" state shows "Launch" button (opens game via package manager)
**And** favorite toggle visible in detail view
**And** detail view accessible offline with cached data

### Story 7.5: App Version Display and GitHub Update Check

As a user,
I want to see the current app version and be notified of updates,
So that I always have the latest features and bug fixes.

**Acceptance Criteria:**

**Given** the app is running
**When** app initializes and on Settings view
**Then** displays current app version in Settings (FR57)
**And** version format: "Version X.X.X (Build YYYY)"
**And** shows version in "About" section of Settings
**And** checks GitHub Releases API for updates on startup (FR60)
**And** compares semantic version (strips "v" prefix, compares major.minor.patch)
**And** detects when newer version available on GitHub
**When** newer version detected
**Then** shows notification: "New version X.X.X available"
**And** notification includes action button "Download Update"
**And** tapping "Download Update" downloads latest APK from GitHub release
**And** shows download progress in notification
**And** after download completes, triggers APK installation via FileProvider
**And** update check respects network connectivity (skips if offline)
**And** update check has 10-second timeout (fails gracefully if GitHub unreachable)
**And** "Check for Updates" button in Settings for manual check

### Story 7.6: Diagnostic Log Export and Manual Catalog Sync

As a user experiencing issues,
I want to export diagnostic logs and manually sync the catalog,
So that I can troubleshoot problems and share logs with support.

**Acceptance Criteria:**

**Given** user needs to troubleshoot issues or force catalog sync
**When** user accesses Settings
**Then** "Manual Catalog Sync" button triggers immediate sync (FR58)
**And** manual sync downloads meta.7z from VRPirates mirror
**And** sync shows progress indicator with percentage
**And** sync completes with toast: "Catalog synced successfully"
**And** sync fails gracefully with error message if offline or server unreachable
**And** "Export Diagnostic Logs" button generates log archive (FR59)
**And** logs include: queue state, network events, errors, installation history, app version
**And** log export creates timestamped ZIP file: `RookieOnQuest_logs_YYYY-MM-DD_HH-mm-ss.zip`
**And** ZIP saved to `/sdcard/Download/RookieOnQuest/logs/`
**And** success toast: "Logs exported to Download/RookieOnQuest/logs/"
**And** includes file path in toast for user reference
**And** logs limited to last 7 days to prevent excessive file size
**And** log export works offline (exports cached logs)

## Epic 8: Build Automation & Release Management

Developers can create reliable, automated release builds with proper signing, changelog extraction, and GitHub releases through a streamlined CI/CD workflow.

### Story 8.1: GitHub Actions Workflow Foundation

As a developer,
I want to create a GitHub Actions workflow for automated release builds,
So that I can trigger release builds manually from the GitHub interface without running builds locally.

**Acceptance Criteria:**

**Given** the project is hosted on GitHub
**When** I create the GitHub Actions workflow file at `.github/workflows/release.yml`
**Then** workflow is manually triggerable via "Run workflow" button in GitHub Actions tab (FR61, NFR-B11)
**And** workflow accepts `version` input parameter for custom builds (NFR-B12)
**And** workflow has explicit permissions for contents write and releases creation (NFR-B9)
**And** workflow runs on Ubuntu latest runner
**And** workflow checks out code with `actions/checkout@v4`
**And** workflow sets up JDK with proper version for Android development
**And** workflow grants execute permission for gradlew
**And** workflow completes release build within 10 minutes (NFR-B1)
**And** workflow logs are visible in GitHub Actions interface

### Story 8.2: Secure APK Signing with Keystore Management

As a developer,
I want to configure automated APK signing using secure credentials,
So that release builds are signed properly without exposing keystore secrets in the repository.

**Acceptance Criteria:**

**Given** the GitHub Actions workflow is running
**When** the release build step executes
**Then** workflow retrieves keystore credentials from GitHub Secrets (NFR-B8)
**And** required secrets include: `KEYSTORE_FILE` (base64 encoded), `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`
**And** secrets are never logged or exposed in workflow output
**And** workflow creates `keystore.properties` file from secrets during build
**And** `keystore.properties` is used by `build.gradle.kts` for signing configuration
**And** temporary `keystore.properties` is deleted after build completes
**And** build fails with clear error message if any required secret is missing
**And** signing configuration produces properly signed APK verifiable by `apksigner verify --print-certs` (NFR-B5)

### Story 8.3: Version and Changelog Extraction

As a developer,
I want to automatically extract version and changelog from project files,
So that releases are populated with correct version info and formatted release notes without manual editing.

**Acceptance Criteria:**

**Given** the workflow is building a release for version X.Y.Z
**When** the extraction step executes
**Then** workflow extracts version name from `build.gradle.kts` `versionName` field (FR63)
**And** workflow extracts version code from `build.gradle.kts` `versionCode` field
**And** workflow validates tag format matches version in build config (NFR-B10)
**And** workflow fails within 30 seconds if version/tag mismatch detected with specific error message (NFR-B7)
**And** workflow reads `CHANGELOG.md` file from repository root (FR64)
**And** workflow extracts changelog section for current version X.Y.Z
**And** workflow preserves changelog formatting including ✨🚀🔧 emojis and bullet points (NFR-B6, FR69)
**And** workflow validates that changelog entry exists for current version
**And** workflow fails with clear error if changelog entry is missing

### Story 8.4: Release Creation and Formatting

As a developer,
I want to automatically create GitHub releases with proper formatting and APK attachment,
So that users can download signed APKs with complete release information.

**Acceptance Criteria:**

**Given** the build has completed successfully and produced signed APK
**When** the release creation step executes
**Then** workflow generates APK filename as `RookieOnQuest-vX.Y.Z.apk` (FR66)
**And** workflow creates GitHub release with title "Rookie On Quest vX.Y.Z" (FR65)
**And** workflow creates or validates version tag `vX.Y.Z` exists (FR67)
**And** workflow attaches signed APK to release (FR68)
**And** workflow populates release body with extracted changelog (FR69)
**And** release body includes sections: ✨ Added, 🚀 Changed, 🔧 Fixed, 📝 Other
**And** workflow verifies APK is byte-identical to local Gradle release build (NFR-B5)
**And** zero manual steps required between workflow trigger and release creation (NFR-B4)
**And** failed releases create zero artifacts (NFR-B7)

### Story 8.5: Release Candidate Build Support

As a developer,
I want to build and release release candidate versions,
So that I can distribute pre-release versions for testing before final release.

**Acceptance Criteria:**

**Given** I need to create a release candidate build
**When** I trigger workflow with version parameter `2.5.0-rc.1`
**Then** workflow accepts RC suffix format (FR70)
**And** workflow creates release tag `v2.5.0-rc.1`
**And** workflow generates APK filename as `RookieOnQuest-v2.5.0-rc.1.apk`
**And** workflow creates release title "Rookie On Quest v2.5.0-rc.1 (Release Candidate)"
**And** release body includes "Pre-release" badge/indicator
**And** workflow extracts changelog for RC version from `CHANGELOG.md`
**And** workflow validates version code is higher than previous release
**And** workflow supports custom version input for hotfixes and RCs (NFR-B12)

### Story 8.6: PR Validation Build Pipeline

As a developer,
I want to automatically validate pull requests with debug builds and quality checks,
So that I catch issues early before merging to main branch.

**Acceptance Criteria:**

**Given** a pull request is opened or updated
**When** the PR validation workflow triggers
**Then** workflow runs debug build (not release) to verify compilation (FR71)
**And** workflow runs Android lint checks and reports issues
**And** workflow runs automated unit tests if test suite exists (FR73)
**And** workflow runs automated instrumented tests if they exist
**And** workflow displays build status with 3 feedback levels in PR conversation (NFR-B14):
  - Pass/fail icon (✅/❌)
  - Error count by category (lint, test, compilation)
  - Direct link to failing test output
**And** status feedback appears within 2 minutes of build completion (NFR-B14)
**And** workflow runs on code push to PR branch
**And** workflow uses build dependency caching for faster validation (NFR-B3, FR72)

### Story 8.7: Build Dependency Caching and Performance

As a developer,
I want to cache Gradle dependencies for faster builds,
So that CI/CD builds complete quickly and save GitHub Actions minutes.

**Acceptance Criteria:**

**Given** the GitHub Actions workflow is running
**When** the build step executes
**Then** workflow caches Gradle dependencies using `actions/cache@v4` (FR72)
**And** cache key includes `gradle-wrapper.properties` hash for cache invalidation
**And** cache key includes operating system for multi-OS compatibility
**And** workflow caches Gradle wrapper, caches, and build cache directories
**And** cached dependencies reduce build time by minimum 50% vs cold build (NFR-B2)
**And** workflow supports parallel execution of code quality and test validation tasks (NFR-B3)
**And** workflow completes full release build within 10 minutes (NFR-B1)
**And** cache restoration and save steps are visible in workflow logs