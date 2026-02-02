# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [2.5.0] - 2026-02-01

### ✨ New Features
- **Automated Release Workflow:** Integrated version extraction and changelog discovery for automated GitHub releases.
- **Fail-Fast Validation:** Added validation to ensure tag/version inputs match project configuration.

### 🔧 Technical Notes
- **AGP 9.0.0 Upgrade Rejected:** Android Studio recommended upgrading to Android Gradle Plugin 9.0.0, but this caused build failures due to Room 2.6.1 incompatibility with Kotlin 2.2.x (kotlinx-metadata-jvm version mismatch). Reverted to AGP 8.13.2 + Kotlin 1.9.22. Future upgrade will require migrating Room to 2.8.x with KSP.

## [2.4.0] - 2026-01-05

### ✨ New Features
- **Advanced Sorting:** Sort games by Name (A-Z/Z-A), Size, Last Updated, and Popularity to find exactly what you want.
- **"New" Filter:** A new filter tab to quickly see games added since your last visit.
- **Popularity Tracking:** Games now have popularity data to help you find trending titles.
- **Favorites System:** You can now mark games as favorites! They will appear with a gold border and can be easily accessed via a new "Favorites" filter tab.
- **Special Install Support:** Added support for games with custom installation scripts (e.g., ports like Quake3Quest) via `install.txt` parsing to handle complex data placement.
- **Battery Optimization:** Integrated a check to request ignoring battery optimizations, preventing the system from killing the app during long downloads.

### 🚀 Improvements
- **Smarter Space Check:** Improved pre-flight storage validation with better estimation for 7z archives (accounting for extraction overhead) and correct external storage path checking.
- **Setup UI:** Enhanced the permission setup screen with better scrolling and clarity for a smoother onboarding experience.
- **Error Handling:** Better reporting for storage errors during installation to alert users immediately.

## [2.3.0] - 2026-01-01

### ✨ New Features
- **Cache Management:** Added a "Clear Download Cache" option in settings to free up storage space.
- **Smart Cleaning:** The app now automatically verifies installed games and cleans up temporary installation files to save space.

### 🚀 Improvements
- **Enhanced Installation Logic:** Significant improvements to how the app handles complex game file structures (nested folders, split OBBs).
- **Download Verification:** Better detection of already downloaded files to prevent unnecessary redownloads.
- **Recursive Parsing:** Added support for downloading games with deep folder structures from the catalog.

### 🔧 Fixes
- **OBB Placement:** Fixed issues where OBB files in subfolders weren't being placed correctly.

## [2.2.1] - 2025-12-30

### 🔧 Fixes
- **Game Status Refresh:** Fixed an issue where game statuses (downloaded/installed) were not automatically updating after a catalog sync.
- **App Update UI:** Fixed navigation and layout issues during mandatory app updates to ensure a smoother experience.

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
