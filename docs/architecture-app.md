# Architecture Document - Rookie On Quest (App)

**Generated:** 2026-01-09
**Project Type:** Mobile (Android Application)
**Architecture Pattern:** MVVM (Model-View-ViewModel)
**Technology Stack:** Kotlin + Jetpack Compose

---

## Executive Summary

**Rookie On Quest** is a standalone Android application for Meta Quest VR headsets that enables users to browse, download, and install VR games directly on the device without requiring a PC. The application is built using modern Android development practices with **Kotlin**, **Jetpack Compose**, and follows the **MVVM architecture pattern**.

**Key Characteristics:**
- **Single-Activity Architecture** with Jetpack Compose UI
- **Repository Pattern** for data abstraction
- **Reactive State Management** using Kotlin Flow
- **Dependency on VRPirates Infrastructure** (critical external dependency)
- **Automated CI/CD Pipeline** (See [Infrastructure Architecture](architecture-infra.md))

---

## Architecture Overview

### High-Level Architecture

```
┌─────────────────────────────────────────────────────────┐
│                     Presentation Layer                   │
│  ┌─────────────────┐          ┌──────────────────────┐  │
│  │  MainActivity   │────────▶│   MainViewModel      │  │
│  │  (Compose UI)   │          │  (State Management)  │  │
│  └─────────────────┘          └──────────────────────┘  │
└─────────────────────────────────────────────────────────┘
                                      │
                                      ▼
┌─────────────────────────────────────────────────────────┐
│                     Business Layer                       │
│                 ┌──────────────────────┐                 │
│                 │   MainRepository     │                 │
│                 │ (Core Business Logic)│                 │
│                 └──────────────────────┘                 │
└─────────────────────────────────────────────────────────┘
                   │                  │
         ┌─────────┴─────────┬────────┴────────┐
         ▼                   ▼                  ▼
┌────────────────┐  ┌──────────────┐  ┌─────────────────┐
│  Data Layer    │  │Network Layer │  │  File System    │
│  ┌──────────┐  │  │ ┌──────────┐ │  │ ┌─────────────┐ │
│  │AppDatabase│ │  │ │VrpService│ │  │ │Downloads    │ │
│  │  (Room)  │  │  │ │ GitHub   │ │  │ │OBB/APK Mgmt │ │
│  └──────────┘  │  │ │(Retrofit)│ │  │ └─────────────┘ │
└────────────────┘  └──────────────┘  └─────────────────┘
```

---

## Layer Breakdown

### 1. Presentation Layer

**Technology:** Jetpack Compose + ViewModel

**Components:**
- **MainActivity.kt** - Single Activity hosting all UI
- **MainViewModel.kt** - State management and event handling
- **Composables** - GameListItem, Install Queue Overlay

**Responsibilities:**
- Render UI based on `StateFlow` emissions
- Handle user input (clicks, search, filters)
- Observe ViewModel state and events
- Request system permissions

**Communication:**
- **Unidirectional Data Flow (UDF)**
- UI observes `StateFlow<T>` via `.collectAsState()`
- UI triggers actions via ViewModel functions
- One-time events via `SharedFlow<MainEvent>`

**State Management Pattern:**

```kotlin
// State (multi-collector, replays last value)
StateFlow<List<GameItemState>> → UI observes → UI recomposes

// Events (one-time, no replay)
SharedFlow<MainEvent> → UI collects → Show toast/dialog

// User Actions
UI → viewModel.queueInstall() → State updated → UI recomposes
```

---

### 2. Business Layer

**Technology:** Kotlin Coroutines + MainRepository

**Components:**
- **MainRepository.kt** - Core business logic

**Key Responsibilities:**

#### Catalog Management
- Download `meta.7z` from VRPirates mirror
- Extract catalog (`VRP-GameList.txt`) and assets (icons, thumbnails)
- Parse catalog via `CatalogParser`
- Store in Room database

#### Installation Pipeline
1. **Verification Phase** - Contact server for file listing (ground truth)
2. **Pre-flight Check** - Validate available storage space
3. **Download Phase** - HTTP Range resumption support
4. **Extraction Phase** - 7z archive extraction with password
5. **Special Install Handling** - Parse `install.txt` for non-standard layouts
6. **OBB Management** - Move `.obb` files to `/Android/obb/{packageName}/`
7. **APK Installation** - Launch system installer via `FileProvider`

#### Metadata Fetching
- Priority-based background fetching (visible items first)
- Fetch sizes, descriptions, screenshots from mirror
- Update Room database asynchronously

#### Queue Processing
- Single coroutine job processes queue sequentially
- Pause/resume/cancel support
- Task promotion (move to front)
- Cleanup on cancellation

**Error Handling:**
- Network failures logged, retries attempted
- Storage errors surface to UI via state
- Cancellation-safe via `ensureActive()`

---

### 3. Data Layer

**Technology:** Room 2.6.1

**Schema:**

```
Table: games
─────────────────────────────────────────
 releaseName (PK)  │ String
 gameName          │ String
 packageName       │ String
 versionCode       │ String
 sizeBytes         │ Long?
 description       │ String?
 screenshotUrlsJson│ String?
 lastUpdated       │ Long
 popularity        │ Int
 isFavorite        │ Boolean
```

**Database Version:** 2
**Migration Strategy:** `fallbackToDestructiveMigration()` (acceptable for cache)

**DAO Operations:**
- `getAllGames()` → `Flow<List<GameEntity>>` (reactive)
- `searchGames(query)` → `Flow<List<GameEntity>>`
- `updateFavorite(releaseName, isFavorite)`
- `updateMetadata(releaseName, description, screenshots)`

**Data Flow:**
```
Room DB → Flow<List<GameEntity>> → Repository → ViewModel → UI
```

---

### 4. Network Layer

**Technology:** Retrofit 2.9.0 + OkHttp 4.12.0 + Gson

**Services:**

#### VrpService
```kotlin
interface VrpService {
    @GET("downloads/vrp-public.json")
    suspend fun getPublicConfig(): PublicConfig
}
```

**Fetches:**
- `baseUri` - Dynamic mirror URL
- `password64` - Base64-encoded 7z password

#### GitHubService
```kotlin
interface GitHubService {
    @GET("repos/LeGeRyChEeSe/rookie-on-quest/releases/latest")
    suspend fun getLatestRelease(): GitHubRelease
}
```

**Fetches:**
- Latest release version
- APK download URL
- Changelog

**Network Configuration:**
- Timeout: 30 seconds (connect), 60 seconds (read)
- Automatic retry on network failure (via repository logic)
- No authentication (public APIs)

---

### 5. File System Layer

**Local Storage Locations:**

| Data Type | Path | Purpose |
|-----------|------|---------|
| Catalog cache | `context.filesDir/VRP-GameList.txt` | Parsed catalog |
| Game icons | `context.filesDir/icons/{pkg}.png` | Cached icons |
| Thumbnails | `context.filesDir/thumbnails/{md5}.jpg` | Preview images |
| Notes | `context.filesDir/notes/{md5}.txt` | Game descriptions |
| Temp install | `context.cacheDir/install_temp/{md5}/` | Extraction staging |
| Downloads | `/sdcard/Download/RookieOnQuest/{release}/` | Downloaded files |
| OBB files | `/sdcard/Android/obb/{packageName}/` | Game data |
| Logs | `/sdcard/Download/RookieOnQuest/logs/` | Diagnostic logs |

**File Naming Convention:**
- MD5 hash of `packageName` for consistency with VRPirates server structure

**Cleanup Strategy:**
- Temp files deleted after successful install
- User can manually clear cache via settings
- Failed installs leave partial downloads for resume

---

## Key Architectural Decisions

### Decision 1: Single-Activity Architecture

**Rationale:**
- Jetpack Compose eliminates need for multiple Activities
- Simpler lifecycle management
- Easier state preservation

**Trade-offs:**
- All UI logic in one Activity (~1400 lines)
- Could be refactored into Navigation Compose in future

### Decision 2: Repository Pattern

**Rationale:**
- Abstracts data sources (network, database, file system)
- Testable business logic
- Clear separation of concerns

**Implementation:**
- `MainRepository` as single source of truth
- ViewModel never directly touches Room or Retrofit

### Decision 3: No Dependency Injection Framework

**Rationale:**
- Manual DI sufficient for single-module app
- Avoids Hilt/Dagger complexity
- Faster build times

**Trade-offs:**
- Harder to scale to multi-module
- Less testability (but app has minimal tests currently)

### Decision 4: Fallback to Destructive Migration

**Rationale:**
- Database is cache, not source of truth
- Catalog resyncs on app launch
- Simplifies migration strategy

**Trade-offs:**
- Users lose favorites on schema change (mitigated by future cloud sync)

### Decision 5: Synchronous Queue Processing

**Rationale:**
- Only one game installs at a time (system installer limitation)
- Simplifies state management
- Avoids file conflicts

**Implementation:**
- Coroutine job processes first `QUEUED` task
- Other tasks remain in queue with `QUEUED` status

---

## Data Flow Diagrams

### Install Flow

```
User clicks "Install"
  → ViewModel.queueInstall(game)
    → Add task to _installQueue: MutableStateFlow
      → Queue processor picks first QUEUED task
        → Repository.installGame(releaseName)
          [1. Server verification]
          [2. Storage check]
          [3. Download with progress callbacks]
          [4. 7z extraction]
          [5. Parse install.txt if present]
          [6. Move OBB files]
          [7. Launch APK installer]
        → Task status → COMPLETED
      → ViewModel emits InstallApk event
    → MainActivity observes event
      → Launches system installer
```

### Catalog Sync Flow

```
App Launch
  → ViewModel.init()
    → Repository.syncCatalog()
      [1. Fetch VRPirates public config]
      [2. Download meta.7z from mirror]
      [3. Extract VRP-GameList.txt + icons/thumbs]
      [4. Parse catalog]
      [5. Insert into Room database]
    → Database emits Flow<List<GameEntity>>
      → ViewModel combines with installed packages
        → UI displays game list
```

---

## Concurrency Model

### Coroutine Scopes

| Scope | Lifecycle | Purpose |
|-------|-----------|---------|
| `viewModelScope` | ViewModel lifecycle | All ViewModel coroutines |
| `Dispatchers.IO` | N/A | File I/O, network calls |
| `Dispatchers.Main` | N/A | UI updates, state emissions |

### Synchronization Patterns

**Queue Processor:**
```kotlin
private var queueProcessorJob: Job? = null

fun startQueueProcessor() {
    queueProcessorJob?.cancel()
    queueProcessorJob = viewModelScope.launch {
        while (isActive) {
            val task = _installQueue.value.firstOrNull { it.status == QUEUED }
            if (task != null) {
                runTask(task)
            } else {
                delay(500)
            }
        }
    }
}
```

**Metadata Fetcher:**
- Priority channel: `Channel<Unit>(CONFLATED)`
- Debounces scroll events
- Fetches metadata for visible items

**State Updates:**
- All `StateFlow` updates on Main dispatcher
- Ensures UI consistency

---

## Performance Considerations

### Lazy Loading
- Icons loaded on-demand via Coil
- Metadata fetched only for visible items
- Database queries use `Flow` for reactive updates

### Caching Strategy
- **L1 Cache:** Room database (persistent)
- **L2 Cache:** Coil in-memory image cache
- **L3 Cache:** Coil disk cache for images

### Memory Management
- `@Immutable` data classes for Compose stability
- Purge context after each workflow step (for documentation generation)
- Release resources on cancellation

### Background Processing
- Battery optimization exemption requested on first launch
- Wake locks during extraction (prevents sleep-induced failures)

---

## Security Architecture

### Permissions

**Runtime Permissions:**
- `REQUEST_INSTALL_PACKAGES` (Install APKs)
- `MANAGE_EXTERNAL_STORAGE` (Access OBB directories)

**Special Permissions:**
- `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (Background downloads)

### File Access

**FileProvider Configuration:**
```xml
<provider
    android:name="androidx.core.content.FileProvider"
    android:authorities="${applicationId}.fileprovider"
    android:exported="false"
    android:grantUriPermissions="true">
    <meta-data
        android:name="android.support.FILE_PROVIDER_PATHS"
        android:resource="@xml/file_provider_paths" />
</provider>
```

**Shared Paths:**
- `cache/` for temp APKs
- `external-path` for downloads

### Secrets Management
- 7z password fetched dynamically from VRPirates config (not hardcoded)
- No API keys or tokens stored in app

---

## Error Handling & Resilience

### Network Resilience
- Retry logic for transient failures
- HTTP Range support for download resumption
- Fallback to cached catalog if sync fails

### Storage Resilience
- Pre-flight storage check prevents out-of-space errors
- Cleanup temp files on failure
- Graceful degradation if external storage unavailable

### Crash Reporting
- **Current Status:** None
- **Recommendation:** Integrate Sentry or Firebase Crashlytics (privacy-respecting)

---

## Testing Strategy

### Current State
- **Unit Tests:** Minimal (placeholder directory exists)
- **Integration Tests:** None
- **UI Tests:** None

### Recommended Testing Approach

**Unit Tests:**
- `CatalogParser` - Test catalog parsing logic
- `GameEntity.toData()` mappers - Verify conversions

**Integration Tests:**
- Repository + Room interaction
- Mock network calls with OkHttp MockWebServer

**UI Tests:**
- Compose Testing - Verify game list rendering
- Screenshot tests for visual regression

---

## Scalability & Future Considerations

### Current Limitations
- Single-Activity approach may become unwieldy (1400+ lines)
- No modularization (single `app/` module)
- Manual DI limits testability

### Potential Improvements

**Architecture:**
- Migrate to Navigation Compose for multi-screen flows
- Introduce Use Cases layer between ViewModel and Repository
- Modularize by feature (`:feature-catalog`, `:feature-install`)

**Technology:**
- Introduce Hilt for DI
- Add Paging 3 for large game lists
- Implement WorkManager for background downloads

**Performance:**
- Implement incremental catalog updates (delta sync)
- Add in-memory LRU cache for game metadata

---

## Dependencies

### Critical Dependencies

| Dependency | Version | Purpose | Replacement Risk |
|------------|---------|---------|------------------|
| Jetpack Compose | 1.5+ | UI framework | Low (stable) |
| Room | 2.6.1 | Database | Low (stable) |
| Retrofit | 2.9.0 | Networking | Low (stable) |
| Kotlin Coroutines | 1.7+ | Async | Low (stable) |
| Apache Commons Compress | 1.21 | 7z extraction | Medium (no maintained alternatives) |

### External Infrastructure Dependencies

**Critical:**
- VRPirates servers (catalog, downloads) - **App non-functional without this**

**Important:**
- GitHub API (update checks) - Degrades gracefully if unavailable

---

## Deployment Architecture

**Build System:** Gradle (Kotlin DSL)
**CI/CD:** GitHub Actions
**Distribution:** GitHub Releases (APK downloads)

**Build Variants:**
- **Debug:** Development builds (unobfuscated)
- **Release:** Production builds (R8 obfuscation, signed)

**Minimum Requirements:**
- Android 10+ (API 29)
- Meta Quest 1/2/3/Pro

---

## Monitoring & Observability

**Current State:** Limited

**Available:**
- Logcat output via `Log.e()`, `Log.d()`
- In-app diagnostic log export (plaintext)

**Missing:**
- Analytics
- Crash reporting
- Performance monitoring

**Recommendation:**
- Integrate privacy-respecting analytics (e.g., Plausible, self-hosted)
- Add Sentry for crash tracking

---

## Conclusion

**Rookie On Quest** demonstrates a clean MVVM architecture with modern Android best practices. The application is well-suited for its current scope (single-module, single-activity) but would benefit from modularization and DI framework adoption as complexity grows.

**Strengths:**
- Clear separation of concerns (MVVM)
- Reactive state management (Flow)
- Efficient caching strategy
- Resilient network handling

**Areas for Improvement:**
- Test coverage
- Modularization
- Error reporting/analytics
- Multi-screen navigation architecture
