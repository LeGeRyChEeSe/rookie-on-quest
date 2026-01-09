# Rookie On Quest - Documentation Index

**Generated:** 2026-01-09
**Documentation Version:** 1.2.0
**Project Version:** v2.4.0

---

## 📋 Project Overview

**Rookie On Quest** is a standalone Android application for Meta Quest VR headsets that enables users to browse, download, and install VR games natively without requiring a PC. Built with **Kotlin** and **Jetpack Compose**, the app provides modern, reactive UI for accessing the VRPirates game catalog.

**Repository:** [LeGeRyChEeSe/rookie-on-quest](https://github.com/LeGeRyChEeSe/rookie-on-quest)
**License:** Apache 2.0
**Platform:** Android (API 29+, Target API 34)

---

## 🎯 Quick Reference

### Project Classification
- **Type:** Monolith (Single Android Application)
- **Primary Language:** Kotlin
- **Architecture:** MVVM (Model-View-ViewModel)
- **UI Framework:** Jetpack Compose (Material 3)

### Technology Stack
| Category | Technology | Version |
|----------|------------|---------|
| **Database** | Room | 2.6.1 |
| **Networking** | Retrofit 2 | 2.9.0 |
| **HTTP Client** | OkHttp | 4.12.0 |
| **JSON** | Gson | 2.10.1 |
| **Image Loading** | Coil | 2.5.0 |
| **Archive Handling** | Apache Commons Compress | 1.21 |
| **State Management** | Kotlin Flow | 1.7+ |

### Entry Point
**MainActivity.kt** (`app/src/main/java/com/vrpirates/rookieonquest/MainActivity.kt`)
- Single Activity Architecture
- Hosts all Compose UI screens
- Manages permission flows and system events

---

## 📚 Core Documentation

### 🏗️ Architecture & Design

- **[Architecture Document](./architecture-app.md)** - Comprehensive architecture overview
  - MVVM pattern explanation
  - Layer-by-layer breakdown
  - Data flow diagrams
  - Concurrency model
  - Performance considerations
  - Security architecture

- **[Project Overview](./project-overview.md)** - High-level project summary
  - Executive summary
  - Technology stack summary
  - Key features
  - External dependencies
  - Quick links and metrics

- **[Source Tree Analysis](./source-tree-analysis.md)** - Annotated codebase structure
  - Directory tree with descriptions
  - Critical paths identification
  - File locations (runtime)
  - Layer breakdown

---

### 💻 Development Documentation

- **[Development Guide](./development-guide-app.md)** - Setup and build instructions
  - Prerequisites and environment setup
  - Build commands (Gradle + Makefile)
  - Running on Meta Quest
  - Debugging techniques
  - Common tasks and troubleshooting

- **[Deployment Guide](./deployment-guide.md)** - CI/CD and release process
  - GitHub Actions workflows
  - Release process (version tagging)
  - Signing configuration
  - APK optimization (R8/ProGuard)
  - Distribution channels
  - Update mechanism

---

### 🔧 Component Documentation

- **[API Contracts](./api-contracts-app.md)** - External API integration
  - **VRPirates Public API** - Configuration and catalog
  - **GitHub API** - Update checks
  - Request/response schemas
  - Error handling

- **[Data Models](./data-models-app.md)** - Database schema
  - **GameEntity** table structure
  - DAO operations (Room queries)
  - Mappers (Entity ↔ Domain models)
  - Migration strategy

- **[State Management](./state-management-app.md)** - MVVM patterns
  - StateFlow usage (reactive UI state)
  - SharedFlow for events
  - Queue processor (installation pipeline)
  - Metadata fetching (priority-based)
  - Concurrency & synchronization

- **[UI Components](./component-inventory-app.md)** - Jetpack Compose components
  - MainScreen structure
  - GameListItem composable
  - InstallQueueOverlay
  - Material 3 theming

- **[Asset Inventory](./asset-inventory-app.md)** - Static and dynamic assets
  - Static assets (app icons)
  - Dynamic assets (cached icons, thumbnails)
  - Downloadable artifacts (APKs, OBBs)
  - Asset management strategy

---

## 🚀 Getting Started

### For Users
1. **Download:** [Latest Release](https://github.com/LeGeRyChEeSe/rookie-on-quest/releases/latest)
2. **Enable Developer Mode** on Meta Quest
3. **Install via SideQuest or ADB:**
   ```bash
   adb install RookieOnQuest-vX.X.X.apk
   ```
4. **Launch from** App Library > Unknown Sources

### For Developers
1. **Clone Repository:**
   ```bash
   git clone https://github.com/LeGeRyChEeSe/rookie-on-quest.git
   cd rookie-on-quest
   ```

2. **Open in Android Studio** (Ladybug or newer)

3. **Build Debug APK:**
   ```bash
   ./gradlew assembleDebug
   ```

4. **Read:** [Development Guide](./development-guide-app.md) for detailed setup

### For Contributors
1. **Read:** [Contributing Guidelines](../README.md#contributing)
2. **Follow:** [Conventional Commits](https://www.conventionalcommits.org/)
3. **Submit PRs** with clear descriptions
4. **Report Bugs:** [GitHub Issues](https://github.com/LeGeRyChEeSe/rookie-on-quest/issues/new?template=bug_report.md)

---

## 🗂️ Existing Project Documentation

### User-Facing Documentation
- **[README.md](../README.md)** - User guide, installation instructions, features
- **[CHANGELOG.md](../CHANGELOG.md)** - Version history and release notes
- **[ACTION_PLAN.md](../ACTION_PLAN.md)** - Development roadmap and planned features

### Meta Documentation
- **[CLAUDE.md](../CLAUDE.md)** - Claude Code AI assistant instructions
- **[GEMINI.md](../GEMINI.md)** - Gemini AI assistant instructions (legacy)
- **[LICENSE](../LICENSE)** - Apache 2.0 license

### Issue Templates
- **[Bug Report](.github/ISSUE_TEMPLATE/bug_report.md)** - Report bugs
- **[Feature Request](.github/ISSUE_TEMPLATE/feature_request.md)** - Suggest features
- **[Question](.github/ISSUE_TEMPLATE/question.md)** - Ask questions

---

## 🛠️ Technical Reference

### Key Files & Locations

| File | Path | Purpose |
|------|------|---------|
| **MainActivity** | `app/src/.../MainActivity.kt` | Main UI host (1400+ lines) |
| **MainViewModel** | `app/src/.../ui/MainViewModel.kt` | State management (1100+ lines) |
| **MainRepository** | `app/src/.../data/MainRepository.kt` | Core business logic (1000+ lines) |
| **AppDatabase** | `app/src/.../data/AppDatabase.kt` | Room database singleton |
| **VrpService** | `app/src/.../network/VrpService.kt` | VRPirates API client |
| **GitHubService** | `app/src/.../network/GitHubService.kt` | GitHub API client |
| **CatalogParser** | `app/src/.../logic/CatalogParser.kt` | Catalog parsing logic |

### Build Configuration

| File | Purpose |
|------|---------|
| `build.gradle.kts` (root) | Root build configuration |
| `app/build.gradle.kts` | App module build config (dependencies, signing) |
| `settings.gradle.kts` | Project settings |
| `gradle.properties` | Gradle properties |
| `keystore.properties` | Release signing config (git-ignored) |
| `Makefile` | Build automation shortcuts |

### Runtime File Locations

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

## 🔍 Use Cases for This Documentation

### When Creating a Brownfield PRD
**Point the PRD workflow to:** `docs/index.md` (this file)

The workflow will discover:
- Architecture patterns to preserve
- Data models to extend
- API contracts for integration
- State management patterns to follow

### For UI-Only Features
**Reference:**
- [UI Components](./component-inventory-app.md)
- [State Management](./state-management-app.md)
- MainViewModel patterns from [Architecture Document](./architecture-app.md)

### For API/Backend Features
**Reference:**
- [API Contracts](./api-contracts-app.md)
- MainRepository patterns from [Architecture Document](./architecture-app.md)
- [Data Models](./data-models-app.md)

### For Full-Stack Features
**Reference:**
- [Architecture Document](./architecture-app.md) (complete flow)
- [Data Models](./data-models-app.md) + [API Contracts](./api-contracts-app.md)
- [State Management](./state-management-app.md)

---

## 🎓 Learning Resources

### Understanding the Codebase
1. Start with **[Project Overview](./project-overview.md)** for high-level context
2. Read **[Architecture Document](./architecture-app.md)** for design patterns
3. Explore **[Source Tree Analysis](./source-tree-analysis.md)** for file locations
4. Check **[State Management](./state-management-app.md)** for MVVM patterns

### Building & Deploying
1. Follow **[Development Guide](./development-guide-app.md)** for environment setup
2. Use **[Deployment Guide](./deployment-guide.md)** for release process
3. Review CI/CD workflows in `.github/workflows/`

### Extending Functionality
1. Review **[Data Models](./data-models-app.md)** if touching database
2. Check **[API Contracts](./api-contracts-app.md)** if adding network calls
3. Study **[UI Components](./component-inventory-app.md)** if adding screens
4. Reference **[Asset Inventory](./asset-inventory-app.md)** for asset management

---

## 🤝 Contributing to Documentation

### Updating Documentation
When making significant code changes, update relevant docs:

- **Architecture changes** → Update `architecture-app.md`
- **New API endpoints** → Update `api-contracts-app.md`
- **Database schema changes** → Update `data-models-app.md`
- **New UI components** → Update `component-inventory-app.md`
- **Build process changes** → Update `development-guide-app.md`

### Regenerating Documentation
If project structure changes significantly, re-run the documentation workflow:

```bash
# Via Analyst agent
/bmad:bmm:agents:analyst
# Then select: Document Project workflow
```

---

## 📞 Support & Contact

### Issue Tracking
- **Bug Reports:** [Template](https://github.com/LeGeRyChEeSe/rookie-on-quest/issues/new?template=bug_report.md)
- **Feature Requests:** [Template](https://github.com/LeGeRyChEeSe/rookie-on-quest/issues/new?template=feature_request.md)
- **Questions:** [Template](https://github.com/LeGeRyChEeSe/rookie-on-quest/issues/new?template=question.md)

### Community
- **GitHub Discussions:** (Enable if needed)
- **VRPirates Community:** [Original Rookie Project](https://github.com/VRPirates/rookie)

---

## 🔖 Document Generation Info

**Workflow:** BMM Document Project (v1.2.0)
**Mode:** Initial Scan (Deep)
**Scan Level:** Deep (selective file reading in critical directories)
**Generated:** 2026-01-09
**Generated By:** BMAD Analyst Agent (Mary)

### Documentation Completeness

✅ **Completed Documents:**
- Architecture Document
- Project Overview
- Source Tree Analysis
- Development Guide
- Deployment Guide
- API Contracts
- Data Models
- State Management Analysis
- UI Component Inventory
- Asset Inventory
- Master Index (this file)

📊 **Total Files Generated:** 11 documentation files
📝 **Total Lines Documented:** ~5000+ lines

---

**Last Updated:** 2026-01-09
**Maintained By:** Project Contributors
**Documentation Status:** ✅ Complete for v2.4.0

---

💡 **Tip:** Bookmark this file (`docs/index.md`) as your primary entry point for all project documentation. Use the navigation links to explore specific topics.
