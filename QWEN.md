# Rookie On Quest - Project Context

## Project Overview

**Rookie On Quest** is a standalone Android application designed specifically for Meta Quest headsets. It serves as a native interface to browse, download, and install VR games from the VRPirates ecosystem without requiring a PC connection.

The application is built using **Kotlin** with **Jetpack Compose** UI framework, leveraging modern Android development practices. It's a client application that depends entirely on the servers and infrastructure maintained by the VRPirates team.

## Key Features

- Standalone sideloading of VR games (APK + OBB files) directly on Meta Quest
- Full catalog access to the VRPirates library
- Favorites system for quick access to preferred games
- Advanced sorting options (by name, size, date, popularity)
- Background downloads that continue even when device sleeps
- Optimized performance for navigating through 2400+ game entries
- Zero-setup experience - open and start browsing immediately

## Project Structure

```
rookie-on-quest/
├── app/                    # Main Android application module
│   ├── src/main/java/com/vrpirates/rookieonquest/
│   │   ├── data/          # Data models and database logic
│   │   ├── logic/         # Business logic and core functionality
│   │   ├── network/       # Network handling and API interactions
│   │   ├── ui/            # UI components and screens (Jetpack Compose)
│   │   ├── worker/        # Background work managers
│   │   └── MainActivity.kt # Main entry point
│   ├── src/main/res/      # Resources (drawables, layouts, etc.)
│   └── build.gradle.kts   # Module-specific Gradle configuration
├── build.gradle.kts       # Top-level build configuration
├── settings.gradle.kts    # Project settings and module inclusion
├── gradle.properties        # Gradle properties
├── Makefile                 # Build automation scripts
├── README.md                # Project documentation
├── PROJECT_DASHBOARD.md     # Project status and roadmap
└── ACTION_PLAN.md           # Technical roadmap and priorities
```

## Building and Running

### Prerequisites
- Android Studio (Ladybug or newer)
- Android SDK 34 (API 34)

### Build Commands
The project uses a Makefile for build automation with the following commands:

1. **Build Debug APK**: `make build`
2. **Build Release APK**: `make release`
3. **Install on Device**: `make install` (requires ADB)
4. **Clean Project**: `make clean`

### Gradle Build
Alternatively, you can use Gradle directly:
- `./gradlew assembleDebug` - Build debug version
- `./gradlew assembleRelease` - Build release version

## Development Conventions

- Follows **Conventional Commits** specification for commit messages
- Uses **Kotlin** with **Jetpack Compose** for UI development
- Implements **Room Database** for local data persistence
- Uses **WorkManager** for background tasks (planned)
- Adheres to Android best practices and Material Design principles

## Current Status

The project is in a stable state (version 2.4.0) with core features including:
- Download, extract, and install functionality
- Favorites system
- Advanced sorting options
- Install scripts support
- Auto-update system
- Battery optimization for background downloads

## Upcoming Priorities

Based on the ACTION_PLAN.md, current priorities include:
1. **Shizuku Integration** - Enable silent installation without manual confirmation
2. **WorkManager Migration** - Replace current queue with WorkManager for better persistence
3. **Foreground Service** - Persistent notification system for background operations
4. **Title Cleaning** - Regex-based cleaning to improve catalog readability

## Installation Process

To install on a Meta Quest:
1. Enable Developer Mode on the headset
2. Download the APK from GitHub releases
3. Install using SideQuest or ADB (`adb install RookieOnQuest.apk`)