# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [2.2.0] - 2025-12-30

### ✨ New Features
- **Install Queue & Manager:** Implemented a background installation queue with pause/resume capabilities, task promotion, and cancellable operations.
- **Game Metadata:** Added support for game descriptions and screenshots with an expandable UI.
- **File Management:** Added ability to delete downloaded game files with a confirmation dialog.
- **Smart Features:** Smart install and download caching, better game identification, and catalog deduplication.
- **UI/UX Overhaul:** Major interface updates, optimizations for large screens, and unified progress display.
- **Filtering:** Added status filtering and visual indicators for downloaded games.
- **Setup Experience:** Unified setup layout with immersive overlays for updates and permissions.

### 🚀 Improvements
- **Performance:** Reduced memory usage and optimized for large screens.
- **Infrastructure:** Windows compatibility for build tools (Makefile) and release signing configuration.
- **User Feedback:** Added snackbar messages for better user feedback.

## [2.1.1] - 2025-12-28

### 🔧 Fixes
- **Installation Resume:** Fixed a bug where stopping the installation during the extraction phase would prevent it from resuming correctly. It now remembers where it left off. (#11)
- **Storage Check:** The app now checks if you have enough space before starting a download to prevent errors.

## [2.1.0] - 2025-12-27

### ✨ New Features
- **Automatic Updates:** The app now updates itself easily without needing a computer.
- **See Game Sizes:** You can now see how big a game is before downloading it.
- **Download Options:** You can choose to "Download Only" (without installing) or "Keep Files" after installation to save them for later.
- **Settings Menu:** A new menu to configure your preferences (like keeping downloaded files).
- **Resume Downloads:** If your internet cuts out, downloads now pick up right where they left off.

### 🚀 Improvements
- **Clearer Status:** Changed "Checking for updates" to "Syncing catalog" so you know when it's just refreshing the game list.
- **Background Installation:** Game installations now continue even if you leave the app.
- **Faster Startup:** The app loads much faster and feels smoother to use.
- **Better Battery Life:** The app now pauses background tasks when you're not using it to save resources.
- **Easier Setup:** A simple guide helps you set up permissions on the first run.
- **Smarter Sorting:** Games are organized better, making them easier to find.
- **Visual Refresh:** Updated launcher icon and background colors for a better look.

### 🔧 Fixes
- **Interaction Lock:** Prevents clicking buttons while an app update is pending to avoid conflicts.
- **Installation:** Made game installations more reliable (especially for large games) and added checks for app visibility before launching installers.
- **Package Refresh:** Improved the detection of installed packages on startup.
- **Update Flow:** Allowed manual refresh and permission checks during the update process.
- **General:** Various small bug fixes and performance tweaks.

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
