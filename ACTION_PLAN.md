# 📋 Technical Roadmap - Rookie On Quest

This document outlines the strategic priorities for the project, categorized by impact and implementation urgency.

---

## ✅ Completed Milestones

| Milestone                      | Impact                                                                                  |
|:-------------------------------|:----------------------------------------------------------------------------------------|
| **Install Queue & Manager**    | Implemented a robust background queue with pause/resume and task promotion.             |
| **Rich Metadata System**       | Integrated game descriptions and screenshots into a new expandable UI.                  |
| **File Management**            | Added ability to delete downloaded APKs and manage storage directly from the app.       |
| **Smart Filtering**            | Added status-based filtering (Downloaded, Installed, Updates) and better sorting.       |
| **Unified Setup Flow**         | Integrated permission handling and update checks into a cohesive startup experience.     |
| **Extraction Resilience**      | Implemented state markers and cleanup to fix interrupted extraction issues. (#11)       |
| **Storage Validation**         | Added pre-flight disk space checks using `StatFs` to prevent mid-download failures.     |
| **Installed Version Tracking** | Compare `versionCode` against local `PackageInfo` to display "Update Available" badges. |
| **Silent Install Flow**        | Fixed post-extraction installation using `FileProvider`. (#5)                           |
| **Download Resumption**        | Implemented HTTP `Range` headers for partial downloads. (#8)                            |
| **Intelligent Sorting**        | Added fast alphabetical indexing with custom symbol handling.                           |
| **Metadata Caching**           | Local Room database for game sizes and metadata persistence. (#6)                       |
| **Auto-Update System**         | GitHub API integration for in-app updates and changelogs. (#10)                         |
| **Diagnostic Export**         | Added one-tap log collection and clipboard export in settings for troubleshooting.      |
| **Cache Management**           | Added settings option to clear temporary download cache and auto-cleanup logic.         |
| **Custom Install Script**      | Added support for `install.txt` parsing to handle complex data placement (e.g. Quake3). |
| **Battery Optimization**       | Added permission flow to ignore battery optimizations for reliable background downloads.  |
| **Favorites System**           | Implemented local favorites with persistent database storage and UI filtering.          |
| **Unified Progress Tracking**  | Reactive progress indicators for the entire lifecycle (Download → Extract → Install).   |
| **Advanced Sorting Options**   | Added sorting by Name, Size, Date, and Popularity plus a "New" games filter.           |

---

## 🔴 Priority 1: Critical Stability & Core Logic
*Essential fixes and fundamental features required for a reliable experience.*

### 📦 Package Management
- [ ] **Shizuku Integration:** Implement silent, background installation to remove manual ADB/FileProvider friction.

---

## 🟠 Priority 2: Workflow & Navigation
*Enhancements to streamline user interaction and download efficiency.*

### 📥 Queue Management
- [ ] **Background WorkManager:** Migrate the current queue to WorkManager for persistence across app restarts and OS-level scheduling.
- [ ] **Foreground Service:** Active notification system to track and manage ongoing background operations.

### 🔍 Discovery Tools
- [ ] **Clean Title Parsing:** Regex-based cleaning to improve catalog readability (stripping prefixes and underscores).

---

## 🟡 Priority 3: UI Polish & Extended Features
*Aesthetic improvements and specialized tools for an immersive VR experience.*

### 🖼️ Visual Experience
- [ ] **Immersive Grid Layout:** High-density vertical grid featuring large game posters for HMD-optimized browsing.
- [ ] **Skeleton UI:** Shimmer effects for a smoother perceived loading experience during database or image fetches.

### 📝 Content Depth
- [ ] **Data Management:** Integrated backup and restore functionality for `/Android/data/` save files.

---

## 🔵 Priority 4: Infrastructure & Scalability
*Long-term architectural health and remote support capabilities.*

### 🏗️ Architecture
- [ ] **Extensible Repositories:** Support for user-defined JSON catalog sources within settings.
