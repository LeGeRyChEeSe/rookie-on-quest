# Rookie On Quest - Project Context

## Project Overview
**Rookie On Quest** is a native Android application designed for Meta Quest headsets. It serves as a standalone client for the VRPirates "Rookie Sideloader" ecosystem, allowing users to browse, download, and install VR games (APK + OBB) directly on the device without a PC.

## Tech Stack & Architecture
*   **Language:** Kotlin
*   **UI Framework:** Jetpack Compose (Material 3)
*   **Architecture:** MVVM (Model-View-ViewModel)
*   **Dependency Injection:** Manual / Service Locator pattern (implied, no Hilt/Dagger seen in build.gradle yet).
*   **Networking:** Retrofit 2 + Gson
*   **Local Storage:** Room Database
*   **Image Loading:** Coil
*   **Async/Concurrency:** Kotlin Coroutines & Flow
*   **Compression:** Apache Commons Compress + XZ (for 7z archive handling)
*   **Minimum SDK:** 29 (Android 10)
*   **Target SDK:** 34 (Android 14)

## Key Directories
*   `app/src/main/java/com/vrpirates/rookieonquest/`
    *   `data/`: Data layer (Room entities, DAOs, Repositories).
    *   `logic/`: Business logic (e.g., `CatalogParser`).
    *   `network/`: Network services (`GitHubService`, `VrpService`).
    *   `ui/`: Jetpack Compose UI components (`MainActivity`, `MainViewModel`, `GameListItem`).
*   `app/src/main/res/`: Resources (drawables, strings, themes).
*   `rookie/`: Likely a submodule or reference to the original C# Desktop Rookie Sideloader (contains `.NET` style folders like `Properties`, `Sideloader`).

## Build & Run
### Commands
*   **Build Debug APK:** `./gradlew assembleDebug`
*   **Build Release APK:** `./gradlew assembleRelease`
*   **Install Debug:** `./gradlew installDebug`

### Installation
The app is typically installed via SideQuest or ADB.
*   **ADB:** `adb install RookieOnQuest.apk`

## Development Status & Roadmap
*   **Current Version:** 2.1.1
*   **Recent Features:** Extraction state management, Pre-flight storage checks, Unified progress UI.
*   **Immediate Priorities (from ACTION_PLAN.md):**
    *   Shizuku Integration (for silent/background installs).
    *   Background WorkManager (for download queues).
    *   Clean Title Parsing.

## Coding Conventions
*   **Style:** Kotlin official style guide.
*   **Commits:** Conventional Commits (`feat:`, `fix:`, `chore:`, etc.).
*   **UI:** 100% Jetpack Compose.
*   **State Management:** ViewModels exposing `StateFlow` or Compose `State` to UI.

## Notes
*   **Infrastructure Dependency:** The application relies entirely on VRPirates infrastructure for game catalogs and downloads.
*   **Complex File Operations:** It handles downloading large archives (often multi-GB), extracting 7z/zip formats, and correctly placing OBB files in Android's restricted storage directories.
*   **Performance:** Optimized for handling large lists (2400+ items) using Jetpack Compose LazyLists.
*   **Background Processes:** Includes logic to handle background downloads and initial icon extraction, which are critical for user experience on the Quest's aggressive battery management system.
*   **Permissions:** Requires extensive storage permissions and relies on Developer Mode being enabled on the headset.

## Agent Instructions
*   **Internal Tools Priority:** Gemini must always prioritize using its built-in tools (`read_file`, `search_file_content`, `replace`, etc.) for any file manipulation or searching tasks.
*   **Shell Usage:** PowerShell commands should only be used when no internal tool is suitable for the specific task.
*   **Fallback:** As a last resort, if PowerShell is unavailable or inappropriate, use standard CMD commands.
*   **Project Context:** Always refer to `README.md`, `ACTION_PLAN.md`, and `PROJECT_DASHBOARD.md` to maintain alignment with current project goals.