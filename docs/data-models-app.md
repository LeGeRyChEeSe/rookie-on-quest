# Data Models & Database Schema - Rookie On Quest (App)

## Overview
The application uses **Room Database** for local storage. The database caches the game catalog to allow offline browsing and efficient filtering.

## Entities

### GameEntity
**Table Name**: `games`

Represents a single game title or application available in the catalog.

| Field | Type | Description | Constraints |
|-------|------|-------------|-------------|
| `releaseName` | `String` | Unique identifier for the release (e.g., "Beat Saber-v1.2.3"). | **Primary Key** |
| `gameName` | `String` | Display name of the game. | |
| `packageName` | `String` | Android package name (e.g., "com.beatgames.beatsaber"). | |
| `versionCode` | `String` | Version code of the APK. | |
| `sizeBytes` | `Long?` | Size of the download in bytes. | Nullable |
| `description` | `String?` | Game description. | Nullable |
| `screenshotUrlsJson` | `String?` | JSON string or pipe-delimited list of screenshot URLs. | Nullable |
| `lastUpdated` | `Long` | Timestamp of last update. | Default: `System.currentTimeMillis()` |
| `popularity` | `Int` | Popularity metric (e.g., download count). | Default: 0 |
| `isFavorite` | `Boolean` | User favorite status. | Default: false |

## Mappers

The application uses extension functions to map between Database Entities and Domain Models:

- `GameEntity.toData()` -> `GameData`
- `GameData.toEntity()` -> `GameEntity`

## Migration Strategy
*Current Version*: Not explicitly versioned in inspected files, assumed version 1.
*Schema Changes*: Handled via Room's auto-migration or manual migration scripts (none found in current scan).
