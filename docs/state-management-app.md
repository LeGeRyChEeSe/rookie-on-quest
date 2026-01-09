# State Management Analysis - Rookie On Quest (App)

## Overview
The application follows the **MVVM (Model-View-ViewModel)** architectural pattern. State management is handled primarily using **Kotlin Coroutines** and **Flow**, specifically **StateFlow** for reactive UI updates in Jetpack Compose.

## Core State Patterns

### Reactive UI State
The `MainViewModel` exposes several `StateFlow` objects that the Compose UI observes:
- `games`: `StateFlow<List<GameItemState>>` - The primary list of games, filtered and sorted.
- `searchQuery`: `StateFlow<String>` - Current search term.
- `selectedFilter`: `StateFlow<FilterStatus>` - Active category filter (All, Installed, Updates, etc.).
- `installQueue`: `StateFlow<List<InstallTaskState>>` - Active and pending installation tasks.

### Event Handling
One-time events (toasts, dialogs, navigation) are handled via `SharedFlow`:
- `events`: `asSharedFlow<MainEvent>()` - Emits events like `Uninstall`, `InstallApk`, `ShowUpdatePopup`, and `ShowMessage`.

## State Models

### GameItemState (Immutable)
Represents the UI state for a single game item.
```kotlin
data class GameItemState(
    val name: String,
    val version: String,
    val installedVersion: String?,
    val packageName: String,
    val releaseName: String,
    val iconFile: File?,
    val installStatus: InstallStatus,
    val queueStatus: InstallTaskStatus?,
    // ... metadata flags
)
```

### InstallTaskState (Immutable)
Tracks the lifecycle of a download/install operation.
```kotlin
data class InstallTaskState(
    val releaseName: String,
    val status: InstallTaskStatus,
    val progress: Float,
    val message: String?,
    val currentSize: String?,
    val totalSize: String?
)
```

## Concurrency & Synchronization
- **Queue Processor**: A dedicated Coroutine job (`queueProcessorJob`) managed within `viewModelScope` processes installation tasks sequentially.
- **Metadata Fetching**: A background loop (`sizeFetchJob`) fetches remote game info (size, descriptions) for visible items in the list.
- **Flow Combination**: Complex states (like the filtered game list) are derived using the `combine` operator, ensuring the UI stays in sync with changes to the catalog, installed packages, and download status.

## Persistence
- **Room Database**: Serves as the source of truth for the game catalog.
- **SharedPreferences**: Stores user preferences (`keep_apks`, `last_catalog_sync_time`).