# Rookie On Quest

<p align="center">
  <img src="RookieOnQuest/Assets/Icons/app_icon.png" width="256" alt="Rookie On Quest Icon">
  <br>
  <img src="https://img.shields.io/github/v/release/LeGeRyChEeSe/rookie-on-quest?style=for-the-badge&color=orange" alt="Latest Release">
  <img src="https://img.shields.io/github/stars/LeGeRyChEeSe/rookie-on-quest?style=for-the-badge&color=2ea44f" alt="Stars">
  <img src="https://img.shields.io/github/last-commit/LeGeRyChEeSe/rookie-on-quest?style=for-the-badge" alt="Last Commit">
  <img src="https://img.shields.io/github/downloads/LeGeRyChEeSe/rookie-on-quest/total?style=for-the-badge&color=007ec6" alt="Downloads">
  <img src="https://komarev.com/ghpvc/?username=LeGeRyChEeSe&repo=rookie-on-quest&style=for-the-badge&label=VIEWS&color=blue" alt="Views">
</p>

A standalone Meta Quest application to browse, download, and install VR games natively. This project brings the power of the original Rookie Sideloader directly to your headset, eliminating the need for a PC during installation.

## Overview

**Rookie On Quest** is a standalone client for the Meta Quest, built with Unity 6. It is important to note that this application is **entirely dependent on the servers and infrastructure maintained by the Rookie/VRPirates team**. It functions as a specialized interface for their services, and its operation relies completely on their continued work and server availability.

## Special Thanks

A huge thank you to the **Rookie developers and the VRPirates community**. Their hard work in maintaining the servers, catalog, and the original sideloader ecosystem is what makes this project possible. This app is a tribute to their dedication to the VR community.

### Key Features
- **Zero Configuration**: Open the project and everything (Android settings, dependencies, scenes) is configured automatically.
- **Native Sideloading**: Direct APK installation from the headset.
- **Smart Caching & Indexing**: Fast startup and optimized icon management.
- **Progressive Loading**: Game list appears instantly while icons load in the background.
- **High Performance**: Virtualized scrolling and aggressive memory management for 2400+ entries.

> [!TIP]
> **Performance Note**: Upon the first launch or after an update, the application extracts over 2400 icons in the background. An icon toggle is available in the settings if you prefer maximum performance.

## How to Build

### Prerequisites
- **Unity 6** (Version 6000.3.2f1 or newer recommended).
- **Android Build Support** installed via Unity Hub.

### Steps
1. Open the `RookieOnQuest` folder in Unity Hub.
2. Wait for the automatic configuration to complete (check the Console for logs).
3. Go to `File > Build Settings` and click **Build**.

## How to Install

1. Enable **Developer Mode** on your Meta Quest.
2. Connect your Quest to your PC.
3. Use `adb install RookieOnQuest.apk` or drag and drop the APK into SideQuest.
4. Launch the app from the **Unknown Sources** section of your library.

---
*Disclaimer: This project is an unofficial client and is not affiliated with the official Rookie/VRPirates team.*