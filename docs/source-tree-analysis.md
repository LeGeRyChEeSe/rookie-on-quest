# Source Tree Analysis - Rookie On Quest

**Generated:** 2026-01-09
**Project Type:** Monolith (Android Application)
**Architecture:** MVVM (Model-View-ViewModel)

## Overview

Rookie On Quest is a standalone Android application built with Kotlin and Jetpack Compose. The project follows a standard Android Gradle structure with a single `app/` module containing all application code.

## Project Root Structure

```
rookie-on-quest/
├── .github/                 # GitHub workflows and issue templates
│   ├── workflows/          # CI/CD pipeline definitions
│   └── ISSUE_TEMPLATE/     # Bug reports, feature requests
├── _bmad/                  # BMAD methodology artifacts
├── _bmad-output/           # Generated planning/implementation docs
├── app/                    # 🎯 Main Android application module (CRITICAL)
├── dist/                   # Build output distribution files
├── docs/                   # Project documentation (generated)
├── gradle/                 # Gradle wrapper files
├── build.gradle.kts        # Root build configuration
├── settings.gradle.kts     # Project settings
├── gradlew                 # Gradle wrapper script (Unix)
├── gradlew.bat             # Gradle wrapper script (Windows)
├── Makefile                # Build automation shortcuts
├── keystore.properties     # Release signing configuration
├── CLAUDE.md               # Claude Code project instructions
├── README.md               # Project overview
├── CHANGELOG.md            # Version history
├── ACTION_PLAN.md          # Development roadmap
└── LICENSE                 # Apache 2.0 license
```

---

## Application Module (`app/`)

### Critical Directories

```
app/
├── src/
│   ├── main/
│   │   ├── java/com/vrpirates/rookieonquest/    # 🎯 Application source code
│   │   │   ├── MainActivity.kt                  # 🚀 Entry Point - Main UI host
│   │   │   ├── data/                            # 💾 Data Layer (Repository + Room)
│   │   │   │   ├── AppDatabase.kt               # Room database singleton
│   │   │   │   ├── GameDao.kt                   # Data access object (queries)
│   │   │   │   ├── GameEntity.kt                # Database entity (games table)
│   │   │   │   ├── GameData.kt                  # Domain model
│   │   │   │   └── MainRepository.kt            # Business logic + network layer
│   │   │   ├── network/                         # 🌐 Network Layer (Retrofit)
│   │   │   │   ├── VrpService.kt                # VRPirates API interface
│   │   │   │   └── GitHubService.kt             # GitHub API interface (updates)
│   │   │   ├── ui/                              # 🎨 Presentation Layer (Compose + ViewModel)
│   │   │   │   ├── MainViewModel.kt             # State management + event handling
│   │   │   │   ├── GameListItem.kt              # Game card composable
│   │   │   │   └── theme/                       # Material 3 theme
│   │   │   │       └── Theme.kt                 # Color scheme + typography
│   │   │   └── logic/                           # 🧮 Business Logic
│   │   │       └── CatalogParser.kt             # VRP catalog parsing
│   │   ├── res/                                 # Android resources
│   │   │   ├── drawable/                        # Vector/raster images
│   │   │   ├── mipmap-anydpi-v26/               # Adaptive icons
│   │   │   ├── values/                          # Strings, colors, themes
│   │   │   └── xml/                             # XML configs (file provider)
│   │   └── AndroidManifest.xml                  # App manifest (permissions, activities)
│   └── test/                                    # Unit tests (placeholder)
├── build.gradle.kts                             # App module build script
└── proguard-rules.pro                           # ProGuard/R8 obfuscation rules
```

---

## Layer Breakdown

### Entry Point

**`MainActivity.kt`** - Single Activity Architecture
- Hosts all Compose UI screens
- Manages permission flow (Install Apps, Storage, Battery Optimization)
- Observes `MainViewModel` state via `StateFlow`
- Handles system events (file installation, uninstall broadcasts)

### Data Layer (`data/`)

**Purpose:** Persistent storage + network data sources

| File | Responsibility |
|------|---------------|
| `AppDatabase.kt` | Room database instance (games table v2) |
| `GameEntity.kt` | Database table schema + mappers |
| `GameDao.kt` | SQL queries (insert, update, search, favorites) |
| `GameData.kt` | Domain model (UI-facing data class) |
| `MainRepository.kt` | **Core Business Logic**: <br>- Catalog sync (download/extract meta.7z)<br>- Game installation (download, extract 7z, install APK, move OBB)<br>- Metadata fetching (sizes, descriptions, screenshots)<br>- File verification with server |

**Flow:** `ViewModel` → `Repository` → `Room/Retrofit` → `Database/Network`

### Network Layer (`network/`)

**Purpose:** External API communication

| File | API | Endpoints |
|------|-----|-----------|
| `VrpService.kt` | VRPirates | `GET /downloads/vrp-public.json` → Config (base URI, password) |
| `GitHubService.kt` | GitHub API | `GET /repos/.../releases/latest` → Update check |

**Technology:** Retrofit 2 + OkHttp + Gson

### Presentation Layer (`ui/`)

**Purpose:** UI rendering + state management

| File | Responsibility |
|------|---------------|
| `MainViewModel.kt` | **State Management Hub**:<br>- Game list state (filtered, sorted)<br>- Installation queue processing<br>- Permission checking<br>- Update checking<br>- Metadata background fetching<br>- Event emissions (toast, dialogs) |
| `GameListItem.kt` | Composable game card (icon, title, status badges) |
| `theme/Theme.kt` | Material 3 color scheme + typography |

**Pattern:** Unidirectional Data Flow (UDF)
- **State:** `StateFlow<T>` (reactive, multi-collector)
- **Events:** `SharedFlow<MainEvent>` (one-time side effects)
- **UI:** Compose functions observe flows via `.collectAsState()`

### Logic Layer (`logic/`)

**Purpose:** Pure business logic (no Android dependencies)

| File | Responsibility |
|------|---------------|
| `CatalogParser.kt` | Parses semicolon-delimited `VRP-GameList.txt`:<br>`GameName;ReleaseName;PackageName;VersionCode;SizeBytes;Popularity` |

---

## Resources (`res/`)

### Drawable

- `app_icon.png` - Static app icon
- `ic_launcher_*.xml` - Adaptive icon components

### Values

- `strings.xml` - UI text resources
- `colors.xml` - Theme color definitions
- `themes.xml` - Material theme configuration

### XML

- `file_provider_paths.xml` - File sharing paths for APK installation via `FileProvider`

---

## Key Integration Points

### 1. **Database ↔ Repository**

- `MainRepository` uses `GameDao` to cache catalog from VRPirates server
- Updates metadata (sizes, descriptions) asynchronously via background coroutines

### 2. **Repository ↔ ViewModel**

- `MainViewModel` calls `repository.syncCatalog()` on startup
- Queue processor calls `repository.installGame()` for each queued task

### 3. **ViewModel ↔ UI**

- MainActivity observes `games: StateFlow<List<GameItemState>>`
- UI triggers actions via ViewModel functions (e.g., `queueInstall()`, `toggleFavorite()`)
- One-time events flow through `events: SharedFlow<MainEvent>`

### 4. **Network ↔ File System**

- `MainRepository` downloads files to `/sdcard/Download/RookieOnQuest/{release}/`
- Extraction to `context.cacheDir/install_temp/{md5hash}/`
- OBB files moved to `/Android/obb/{packageName}/`

---

## Build System

- **Build Tool:** Gradle (Kotlin DSL)
- **Android Gradle Plugin:** 8.2.0+
- **Min SDK:** 29 (Android 10)
- **Target SDK:** 34 (Android 14)
- **Compile SDK:** 34

### Build Variants

- **Debug:** Development builds (debuggable, no obfuscation)
- **Release:** Production builds (signed, R8 obfuscation, minified)

### Signing

- Keystore configured via `keystore.properties` (excluded from git)
- Release APKs signed with SHA256-RSA

---

## Critical Paths Summary

| Path | Purpose | Technology |
|------|---------|-----------|
| `MainActivity.kt` | App entry point + UI host | Jetpack Compose |
| `MainViewModel.kt` | State management | Kotlin Coroutines + Flow |
| `MainRepository.kt` | Business logic | Coroutines + Retrofit + Apache Commons |
| `AppDatabase.kt` | Local storage | Room 2.6.1 |
| `VrpService.kt` | VRPirates API client | Retrofit 2.9.0 |
| `CatalogParser.kt` | Catalog parsing | Pure Kotlin |

---

## Development Workflow

### Local Development

```bash
# Build debug APK
./gradlew assembleDebug

# Install to device
./gradlew installDebug

# Run tests
./gradlew test
```

### Release Build

```bash
# Build signed release APK (requires keystore.properties)
./gradlew assembleRelease
```

**Output:** `app/build/outputs/apk/release/RookieOnQuest-vX.X.X.apk`

---

## File Locations (Runtime)

| Data Type | Location |
|-----------|----------|
| Catalog cache | `context.filesDir/VRP-GameList.txt` |
| Game icons | `context.filesDir/icons/{packageName}.png` |
| Thumbnails | `context.filesDir/thumbnails/{md5}.jpg` |
| Notes | `context.filesDir/notes/{md5}.txt` |
| Temp install | `context.cacheDir/install_temp/{md5}/` |
| Downloads | `/sdcard/Download/RookieOnQuest/{releaseName}/` |
| OBB files | `/sdcard/Android/obb/{packageName}/` |
| Logs | `/sdcard/Download/RookieOnQuest/logs/` |

---

## Notes

- **Single-Activity Architecture:** All UI in `MainActivity.kt` via Compose screens
- **No Navigation Component:** Simple overlay system for queue/details
- **Dependency Injection:** Manual (no Hilt/Dagger)
- **Testing:** Minimal test coverage (placeholder test directory)
- **Offline Support:** App works offline with cached catalog
