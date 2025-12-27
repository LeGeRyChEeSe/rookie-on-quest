# Action Plan - Rookie On Quest

This document outlines the action plan based on current GitHub issues and project goals.

## 1. Completed ✅

### [BUG] Nothing happens after download. (#5)
*   **Status:** Resolved. Installation now launches via `FileProvider` after extraction.

### [FEATURE] Resume download (#8)
*   **Status:** Resolved. HTTP `Range` headers support implemented in `MainRepository`.

### [FEATURE] Alphabetical Indexer
*   **Status:** Resolved. Fast alphabetical navigation implemented. Intelligent sorting (`_` -> `#` -> `A-Z`) and case-insensitive organization.

### [FEATURE] Display game size in list (#6)
*   **Status:** Resolved. Game sizes are displayed, cached via **Room Database**, and fetched using a priority system (visible items first).

### [FEATURE] Update popup (#10)
*   **Status:** Resolved. App version check via GitHub API implemented. Automatic in-app download and installation of updates with Markdown changelog formatting.

### [FEATURE] Keep downloaded files (#7) & Download only (#9)
*   **Status:** Resolved.
    *   Added **Settings** dialog to toggle "Keep APKs after installation".
    *   Added **Download Only** button in the list.
    *   All manual downloads are stored in `/sdcard/Download/RookieOnQuest/`.

## 2. In Progress / Immediate Priority 🚀

### [FEATURE] Multi-mirror support
*   **Goal:** Provide fallback mirrors if the primary one is slow or offline.
*   **Actions:**
    *   Implement mirror rotation logic in `MainRepository`.
    *   Add mirror selection in settings.

## 3. UX & Future Improvements

### [UX] Download Manager
*   **Goal:** Better visibility of concurrent or background downloads (queue management).

### [FEATURE] Offline Mode
*   **Goal:** Allow browsing previously loaded catalog and installed games without internet.
*   **Actions:**
    *   Leverage Room database for metadata.
    *   Handle network error states gracefully.
