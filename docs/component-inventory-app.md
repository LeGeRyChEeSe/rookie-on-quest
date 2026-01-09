# UI Component Inventory - Rookie On Quest (App)

## Overview
The UI is built entirely with **Jetpack Compose** using Material Design 3.
Current Architecture: **Monolithic UI** (Most logic resides in `MainActivity.kt`).

## Screens

### MainScreen
The primary entry point for the application UI.
- **Location**: `app/src/main/java/com/vrpirates/rookieonquest/MainActivity.kt`
- **State Source**: `MainViewModel`
- **Responsibilities**:
    - Hosting the game list grid/list.
    - Managing the top app bar and search functionality.
    - Displaying install queue overlays.
    - Handling permission request flows.
    - Displaying update dialogs.

## Components

### GameListItem
Represents a single game card in the list/grid.
- **Location**: `app/src/main/java/com/vrpirates/rookieonquest/ui/GameListItem.kt`
- **Props**: `GameItemState`
- **Features**:
    - Displays game icon (via Coil).
    - Shows title, version, and size.
    - Status indicators (Installed, Update Available, Queue Status).
    - Context menu or click actions for install/uninstall.

### InstallQueueOverlay (Inferred)
- **Location**: Embedded in `MainActivity.kt` (based on `_showInstallOverlay` in ViewModel).
- **Purpose**: Shows active downloads and extraction progress.

## Design System
- **Theme**: `RookieOnQuestTheme` (`ui/theme/`)
- **Colors**: Material 3 Color Scheme.
- **Typography**: Material 3 Typography.
