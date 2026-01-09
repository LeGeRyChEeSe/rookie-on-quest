# Project Overview - Rookie On Quest

**Generated:** 2026-01-09
**Project Type:** Mobile Application (Android)
**Repository Structure:** Monolith
**Primary Language:** Kotlin

---

## Executive Summary

**Rookie On Quest** is a standalone Android application for Meta Quest VR headsets that enables users to browse, download, and install VR games natively without requiring a PC connection. Built with Kotlin and Jetpack Compose, the app provides a modern, reactive UI for accessing the VRPirates game catalog directly on the headset.

**Critical Context:** This application is entirely dependent on VRPirates servers and infrastructure. It functions as a specialized client interface for their services.

---

## Project Identity

| Attribute | Value |
|-----------|-------|
| **Project Name** | Rookie On Quest |
| **GitHub Repository** | [LeGeRyChEeSe/rookie-on-quest](https://github.com/LeGeRyChEeSe/rookie-on-quest) |
| **License** | Apache 2.0 |
| **Current Version** | v2.4.0 (as of 2026-01-09) |
| **Target Platform** | Android (Meta Quest VR headsets) |
| **Minimum SDK** | API 29 (Android 10) |
| **Target SDK** | API 34 (Android 14) |

---

## Technology Stack Summary

### Core Technologies

| Layer | Technology | Version | Purpose |
|-------|------------|---------|---------|
| **Language** | Kotlin | 1.9+ | Primary development language |
| **UI Framework** | Jetpack Compose | 1.5+ | Declarative UI |
| **Architecture** | MVVM | - | Separation of concerns |
| **Reactive State** | Kotlin Flow | 1.7+ | State management |
| **Database** | Room | 2.6.1 | Local persistence |
| **Networking** | Retrofit 2 | 2.9.0 | HTTP client |
| **HTTP Engine** | OkHttp | 4.12.0 | Network layer |
| **JSON Parsing** | Gson | 2.10.1 | Serialization |
| **Image Loading** | Coil | 2.5.0 | Async image loading |
| **Archive Extraction** | Apache Commons Compress | 1.21 | 7z support |

### Build & Deployment

| Tool | Purpose |
|------|---------|
| Gradle (Kotlin DSL) | Build system |
| Android Gradle Plugin 8.2+ | Android builds |
| GitHub Actions | CI/CD |
| ProGuard/R8 | Code shrinking & obfuscation |

---

## Architecture Classification

### Repository Type
**Monolith** - Single cohesive codebase with one application module (`app/`)

### Architecture Pattern
**MVVM (Model-View-ViewModel)**

```
UI Layer (MainActivity + Compose)
    ↓
ViewModel Layer (MainViewModel)
    ↓
Repository Layer (MainRepository)
    ↓
Data Sources (Room, Retrofit, FileSystem)
```

### Communication Pattern
**Unidirectional Data Flow (UDF)**
- State flows from ViewModel to UI via `StateFlow`
- UI sends events to ViewModel via function calls
- One-time effects via `SharedFlow`

---

## Project Structure Overview

```
rookie-on-quest/
├── app/                          # Main application module
│   ├── src/main/java/.../
│   │   ├── MainActivity.kt       # Entry point (Compose host)
│   │   ├── data/                 # Data layer (Room + Repository)
│   │   ├── network/              # Network layer (Retrofit)
│   │   ├── ui/                   # Presentation layer (ViewModel + Composables)
│   │   └── logic/                # Business logic (Catalog parser)
│   └── build.gradle.kts          # Module build config
├── docs/                         # Generated documentation
├── .github/workflows/            # CI/CD pipelines
├── build.gradle.kts              # Root build config
├── CLAUDE.md                     # AI assistant instructions
├── README.md                     # User-facing documentation
└── CHANGELOG.md                  # Version history
```

---

## Key Features

### User-Facing Features
1. **Game Catalog Browsing** - Browse 2400+ VR games
2. **Search & Filter** - Find games by name or category
3. **Favorites System** - Mark games for quick access
4. **Installation Queue** - Batch download/install multiple games
5. **Background Downloads** - Continue downloads during sleep
6. **Auto-Updates** - Check for app updates via GitHub
7. **Offline Mode** - Browse cached catalog without internet

### Technical Features
1. **7z Archive Extraction** - Password-protected archives
2. **HTTP Range Resumption** - Resume interrupted downloads
3. **Special Install Handling** - Parse `install.txt` for non-standard layouts
4. **OBB Management** - Automatic placement in correct directories
5. **Metadata Caching** - Icons, thumbnails, descriptions stored locally
6. **Storage Pre-flight Checks** - Prevent out-of-space errors

---

## Data Flow Architecture

### Catalog Sync Flow

```
App Launch
    ↓
Fetch VRPirates Config (mirror URL, password)
    ↓
Download meta.7z
    ↓
Extract catalog + assets (icons, thumbnails)
    ↓
Parse VRP-GameList.txt
    ↓
Insert into Room Database
    ↓
UI displays game list
```

### Installation Flow

```
User queues game
    ↓
[1] Server Verification (get file list)
    ↓
[2] Storage Check (2.9x space required)
    ↓
[3] Download with progress (HTTP Range)
    ↓
[4] Extract 7z archive
    ↓
[5] Parse install.txt (if present)
    ↓
[6] Move OBB files to /Android/obb/{pkg}/
    ↓
[7] Launch APK installer (system)
```

---

## External Dependencies

### Critical Infrastructure

**VRPirates Servers** (CRITICAL)
- **Purpose:** Game catalog, downloads, metadata
- **Impact if unavailable:** App non-functional
- **Ownership:** VRPirates Team
- **URLs:** Dynamic (fetched from `vrp-public.json`)

**GitHub API** (Important)
- **Purpose:** Update checks
- **Impact if unavailable:** No auto-update notifications
- **Ownership:** GitHub
- **URL:** `api.github.com/repos/.../releases/latest`

**GitHub Releases** (Important)
- **Purpose:** APK distribution
- **Impact if unavailable:** Users cannot download app
- **Ownership:** GitHub
- **URL:** `github.com/LeGeRyChEeSe/rookie-on-quest/releases`

---

## File Locations

### Internal Storage (`context.filesDir`)
- `VRP-GameList.txt` - Cached catalog
- `icons/{packageName}.png` - Game icons
- `thumbnails/{md5}.jpg` - Preview images
- `notes/{md5}.txt` - Game descriptions

### External Storage (`/sdcard`)
- `Download/RookieOnQuest/{releaseName}/` - Downloaded APKs/OBBs
- `Android/obb/{packageName}/` - Installed OBB files
- `Download/RookieOnQuest/logs/` - Diagnostic logs

### Cache (`context.cacheDir`)
- `install_temp/{md5}/` - Extraction staging area

---

## Build Variants

### Debug Build
- **Purpose:** Development and testing
- **Obfuscation:** Disabled
- **Signing:** Debug keystore (auto-generated)
- **Output:** `app-debug.apk` (~10 MB)

### Release Build
- **Purpose:** Production distribution
- **Obfuscation:** R8 enabled (ProGuard rules)
- **Signing:** Release keystore (from `keystore.properties`)
- **Output:** `RookieOnQuest-vX.X.X.apk` (~8 MB)

---

## Development Workflow

### Local Development
```bash
# Clone and build
git clone https://github.com/LeGeRyChEeSe/rookie-on-quest.git
cd rookie-on-quest
./gradlew assembleDebug

# Install to device
./gradlew installDebug
```

### Release Process
```bash
# Update version
make set-version V=2.5.0

# Commit and tag
git commit -m "chore: release v2.5.0"
git tag v2.5.0
git push origin main --tags

# CI/CD builds and publishes automatically
```

---

## Code Quality & Standards

### Commit Convention
**Conventional Commits** (enforced)
- `feat:` - New features
- `fix:` - Bug fixes
- `docs:` - Documentation changes
- `chore:` - Maintenance tasks

### Code Style
- **Language:** Kotlin (idiomatic)
- **Formatting:** Android Kotlin Style Guide
- **Architecture:** MVVM with Repository pattern
- **State Management:** Kotlin Flow (StateFlow/SharedFlow)

### Testing
**Current Status:** Minimal
- Unit tests: Placeholder directory exists
- UI tests: None
- Integration tests: None

**Recommendations:** Add CatalogParser unit tests, Repository integration tests

---

## Performance Characteristics

### App Size
- **Release APK:** ~8 MB (R8 shrinking enabled)
- **Installed Size:** ~12 MB (app) + dynamic assets

### Startup Performance
- **Cold Start:** ~1-2 seconds (Quest 2)
- **Catalog Sync:** ~10-30 seconds (first launch)
- **Icon Extraction:** Background task (non-blocking)

### Memory Usage
- **Typical:** ~150 MB RAM
- **Peak:** ~300 MB (during extraction)

### Storage Requirements
- **App:** 12 MB
- **Catalog Cache:** ~50 MB (icons, thumbnails, notes)
- **Downloads:** Varies per game (500 MB - 10 GB)

---

## Security & Permissions

### Required Permissions
1. **INTERNET** - Catalog sync and downloads
2. **REQUEST_INSTALL_PACKAGES** - Install APKs
3. **MANAGE_EXTERNAL_STORAGE** - Access OBB directories
4. **FOREGROUND_SERVICE** - Background downloads (future)

### Special Permissions
- **IGNORE_BATTERY_OPTIMIZATIONS** - Prevent download interruption

### Security Measures
- No hardcoded secrets (password fetched dynamically)
- FileProvider for secure APK sharing
- Release builds signed with private keystore

---

## Documentation Index

### User Documentation
- [README.md](../README.md) - User guide and installation
- [CHANGELOG.md](../CHANGELOG.md) - Version history

### Technical Documentation
- [Architecture Document](./architecture-app.md) - Detailed architecture
- [Development Guide](./development-guide-app.md) - Setup and build instructions
- [Deployment Guide](./deployment-guide.md) - CI/CD and release process
- [API Contracts](./api-contracts-app.md) - External API documentation
- [Data Models](./data-models-app.md) - Database schema
- [Source Tree Analysis](./source-tree-analysis.md) - Codebase structure

### Component Documentation
- [UI Components](./component-inventory-app.md) - Compose components
- [State Management](./state-management-app.md) - MVVM patterns
- [Asset Inventory](./asset-inventory-app.md) - Static and dynamic assets

---

## Roadmap & Future Enhancements

### Planned Features (from ACTION_PLAN.md)
- Cloud sync for favorites
- Multi-language support (i18n)
- Dark mode
- Game metadata rich editing
- Download scheduler

### Technical Improvements
- Migrate to Navigation Compose
- Introduce Hilt for DI
- Add comprehensive test suite
- Implement Paging 3 for game list
- Add crash reporting (Sentry)

---

## Community & Support

### Issue Tracking
- **Bug Reports:** [GitHub Issues](https://github.com/LeGeRyChEeSe/rookie-on-quest/issues/new?template=bug_report.md)
- **Feature Requests:** [GitHub Issues](https://github.com/LeGeRyChEeSe/rookie-on-quest/issues/new?template=feature_request.md)
- **Questions:** [GitHub Issues](https://github.com/LeGeRyChEeSe/rookie-on-quest/issues/new?template=question.md)

### Contributing
- Follow [Conventional Commits](https://www.conventionalcommits.org/)
- Submit PRs with clear descriptions
- Respect existing architecture patterns

---

## Credits & Acknowledgments

**Primary Developer:** LeGeRyChEeSe

**Special Thanks:**
- **VRPirates Team** - Server infrastructure and original [Rookie sideloader](https://github.com/VRPirates/rookie)
- **Rookie Community** - Catalog maintenance and support

**Open Source Dependencies:**
- Jetpack Compose, Room, Retrofit (Google/JetBrains)
- Apache Commons Compress (Apache Software Foundation)
- Coil (Coil Contributors)

---

## Quick Links

- **Latest Release:** https://github.com/LeGeRyChEeSe/rookie-on-quest/releases/latest
- **Source Code:** https://github.com/LeGeRyChEeSe/rookie-on-quest
- **VRPirates Rookie:** https://github.com/VRPirates/rookie
- **Android Developers:** https://developer.android.com/

---

## Project Health Metrics

| Metric | Value | Last Updated |
|--------|-------|--------------|
| **Current Version** | v2.4.0 | 2026-01-09 |
| **GitHub Stars** | (Live Badge) | N/A |
| **Total Downloads** | (Live Badge) | N/A |
| **Open Issues** | (Check GitHub) | N/A |
| **Last Commit** | (Live Badge) | N/A |
| **Build Status** | (GitHub Actions Badge) | N/A |

---

## Getting Started

**For Users:**
1. Download latest APK from [Releases](https://github.com/LeGeRyChEeSe/rookie-on-quest/releases/latest)
2. Enable Developer Mode on Meta Quest
3. Install via SideQuest or ADB
4. Launch from App Library > Unknown Sources

**For Developers:**
1. Read [Development Guide](./development-guide-app.md)
2. Clone repository
3. Open in Android Studio
4. Build with `./gradlew assembleDebug`

**For Contributors:**
1. Fork repository
2. Create feature branch (`feat/your-feature`)
3. Follow Conventional Commits
4. Submit PR with clear description

---

**Need Help?** Open an [issue](https://github.com/LeGeRyChEeSe/rookie-on-quest/issues/new) or check existing documentation.
