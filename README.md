# Rookie On Quest

<p align="center">
  <img src="RookieOnQuest/Assets/Icons/app_icon.png" width="256" alt="Rookie On Quest Icon">
  <br>
  <img src="https://img.shields.io/github/v/release/LeGeRyChEeSe/rookie-on-quest?style=for-the-badge&color=orange" alt="Latest Release">
  <img src="https://img.shields.io/github/stars/LeGeRyChEeSe/rookie-on-quest?style=for-the-badge&color=2ea44f" alt="Stars">
  <img src="https://img.shields.io/github/last-commit/LeGeRyChEeSe/rookie-on-quest?style=for-the-badge" alt="Last Commit">
  <img src="https://img.shields.io/github/downloads/LeGeRyChEeSe/rookie-on-quest/total?style=for-the-badge&color=007ec6" alt="Downloads">
  <img src="https://komarev.com/ghpvc/?username=LeGeRyChEeSe&repo=rookie-on-quest&style=for-the-badge&label=VIEWS&color=blue" alt="Views">
  <br><br>
  <a href="https://github.com/LeGeRyChEeSe/rookie-on-quest/releases/latest">
    <img src="https://img.shields.io/badge/DOWNLOAD-LATEST_APK-orange?style=for-the-badge&logo=android&logoColor=white" alt="Download Latest APK">
  </a>
</p>

A standalone Meta Quest application to browse, download, and install VR games natively. This project brings the power of the original Rookie Sideloader directly to your headset, eliminating the need for a PC during installation.

---

### Table of Contents
- [Overview](#overview)
- [Special Thanks](#special-thanks)
- [Key Features](#key-features)
- [Download & Installation](#download--installation)
- [Build from Source](#build-from-source)

---

## Overview

**Rookie On Quest** is a standalone client for the Meta Quest, built with Unity 6. It is important to note that this application is **entirely dependent on the servers and infrastructure maintained by the Rookie/VRPirates team**. It functions as a specialized interface for their services, and its operation relies completely on their continued work and server availability.

## Special Thanks

A huge thank you to the **Rookie developers and the VRPirates community**. Their hard work in maintaining the servers, catalog, and the original sideloader ecosystem is what makes this project possible. This app is a tribute to their dedication to the VR community.

### Key Features
- **Standalone Sideloading**: Install games directly on your Meta Quest without needing a PC.
- **Full Catalog Access**: Browse and search through the complete VRPirates library natively.
- **Optimized Performance**: Smooth and fast navigation through 2400+ game entries.
- **Zero Setup**: Open the app and start browsing immediately with no configuration required.

> [!TIP]
> **Performance Note**: Upon the first launch or after an update, the application extracts over 2400 icons in the background. An icon toggle is available in the settings if you prefer maximum performance.

---

## Download & Installation

### 1. Download the App
Get the latest version of **Rookie On Quest**:

[![Download Latest APK](https://img.shields.io/badge/DOWNLOAD-LATEST_APK-orange?style=for-the-badge&logo=android&logoColor=white)](https://github.com/LeGeRyChEeSe/rookie-on-quest/releases/latest)

### 2. Prepare your Quest
Ensure your Meta Quest is in **Developer Mode**. If you haven't enabled it yet:
1. Go to the [Meta Quest Developer Dashboard](https://dashboard.oculus.com/).
2. Create an "Organization".
3. Open the Meta Quest app on your phone, go to **Devices > Headset Settings > Developer Mode**, and toggle it on.

### 3. Install the APK
You can install the downloaded `.apk` file using one of the following methods:

#### Method A: SideQuest (Recommended)
1. Open **SideQuest** on your PC.
2. Connect your Quest via USB.
3. Drag and drop the `RookieOnQuest.apk` file into the SideQuest window.

#### Method B: ADB (Command Line)
If you have ADB installed, run:
```bash
adb install RookieOnQuest.apk
```

---

## Build from Source

### Prerequisites
- **Unity 6** (Version 6000.3.2f1 or newer recommended).
- **Android Build Support** installed via Unity Hub.

### Steps
1. Open the `RookieOnQuest` folder in Unity Hub.
2. Wait for the automatic configuration to complete (check the Console for logs).
3. Go to `File > Build Settings` and click **Build**.