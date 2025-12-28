# 📋 Technical Roadmap - Rookie On Quest

This document outlines the strategic priorities for the project, categorized by impact and implementation urgency.

---

## ✅ Completed Milestones

| Milestone | Impact |
| :--- | :--- |
| **Extraction Resilience** | Implemented state markers and cleanup to fix interrupted extraction issues. (#11) |
| **Storage Validation** | Added pre-flight disk space checks using `StatFs` to prevent mid-download failures. |
| **Installed Version Tracking** | Compare `versionCode` against local `PackageInfo` to display "Update Available" badges. |
| **Silent Install Flow** | Fixed post-extraction installation using `FileProvider`. (#5) |
| **Download Resumption** | Implemented HTTP `Range` headers for partial downloads. (#8) |
| **Intelligent Sorting** | Added fast alphabetical indexing with custom symbol handling. |
| **Metadata Caching** | Local Room database for game sizes and metadata persistence. (#6) |
| **Auto-Update System** | GitHub API integration for in-app updates and changelogs. (#10) |
| **Advanced Settings** | Options for APK retention and "Download Only" workflows. (#7, #9) |
| **Unified Progress UI** | Reactive progress indicators for the entire Download → Extract → Install lifecycle. |

---

## 🔴 Priority 1: Critical Stability & Core Logic
*Essential fixes and fundamental features required for a reliable experience.*

### 🛠️ Resilience & Error Handling
- [x] **Extraction State Management:** State machine implementation for extraction to handle interruptions (#11).
- [x] **Pre-Flight Storage Checks:** Disk space validation using `StatFs` before initiating downloads.

### 📦 Package Management
- [x] **Update Detection:** Real-time "Update Available" indicators by comparing remote and local version codes.
- [ ] **Shizuku Integration:** Implement silent, background installation to remove manual ADB/FileProvider friction.

### 🎨 Core Feedback
- [x] **Unified Progress Tracking:** Replace static UI elements with reactive progress indicators for the entire lifecycle (Download → Extract → Install).

---

## 🟠 Priority 2: Workflow & Navigation
*Enhancements to streamline user interaction and download efficiency.*

### 📥 Queue Management
- [ ] **Background WorkManager:** Implement a robust sequential download queue that persists across app restarts.
- [ ] **Foreground Service:** Active notification system to track and manage ongoing background operations.

### 🔍 Discovery Tools
- [ ] **Clean Title Parsing:** Regex-based cleaning to improve catalog readability (stripping prefixes and underscores).
- [ ] **Smart Filtering:** Category-based chips (Games/Apps/Tools) and multi-criteria sorting.

### 🌐 Connectivity
- [ ] **Offline Resilience:** Full Room-backed browsing mode for users with intermittent connectivity.

---

## 🟡 Priority 3: UI Polish & Extended Features
*Aesthetic improvements and specialized tools for an immersive VR experience.*

### 🖼️ Visual Experience
- [ ] **Immersive Grid Layout:** High-density vertical grid featuring large game posters for HMD-optimized browsing.
- [ ] **Skeleton UI:** Shimmer effects for a smoother perceived loading experience during database or image fetches.

### 📝 Content Depth
- [ ] **Detailed Component:** BottomSheet/Detail view with descriptions, screenshots, and direct uninstallation controls.
- [ ] **Data Management:** Integrated backup and restore functionality for `/Android/data/` save files.

### 🎙️ Accessibility
- [ ] **Voice Commands:** Speech-to-text integration for hands-free search within the HMD.

---

## 🔵 Priority 4: Infrastructure & Scalability
*Long-term architectural health and remote support capabilities.*

### 🏗️ Architecture
- [ ] **Extensible Repositories:** Support for user-defined JSON catalog sources within settings.
- [ ] **Diagnostic Export:** "One-tap" log collection and export to facilitate remote troubleshooting of complex issues.
