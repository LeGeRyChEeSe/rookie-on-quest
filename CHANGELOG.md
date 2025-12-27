# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [2.1] - 2025-12-27

### Added
- **Database Caching:** Implemented Room database for efficient game list caching and faster startup.
- **Game Size Display:** Added display of game sizes in the list with local caching.
- **Smart Fetching:** Prioritized fetching of game sizes based on visible items in the list for better UX.
- **Permissions Management:** New guided flow for granting necessary permissions (Install Unknown Apps, Manage External Storage).
- **Resume Capability:** Downloads can now resume if interrupted.

### Changed
- **Architecture:** Refactored `MainViewModel` and `MainRepository` to use Reactive Streams (Flow) and database as the single source of truth.
- **Performance:** Significant improvements to UI responsiveness and catalog loading. Heavy calculations moved off the main thread.
- **Installation Logic:** More robust APK and OBB installation process.
- **UI Modularization:** Cleaner code structure for UI components.
- **Gradle:** Adjusted Gradle wrapper version for better stability.

### Fixed
- **Typography:** Fixed potential font resolution freezes.

## [2.0.0] - 2025-12-22

### Changed
- **Complete Rebuild:** The app has been entirely rewritten as a native Android application for better performance and stability (goodbye Unity!).
- **New Interface:** A completely new, modern, and cleaner user interface.

### Added
- **Game Management:** You can now uninstall games directly from the app.
- **Smart Updates:** The app now automatically detects installed games and shows you when a new version is available.
- **Improved Downloads:** Better handling of downloads and installations.

## [1.0.0] - 2025-12-21

### Added
- Standalone Meta Quest application ("Rookie On Quest") for native sideloading.