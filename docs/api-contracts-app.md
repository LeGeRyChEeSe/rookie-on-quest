# API Contracts - Rookie On Quest (App)

## Overview
The application interacts with two primary external services:
1. **VRPirates Public Config**: Fetches the configuration for game downloads.
2. **GitHub API**: Checks for application updates.

## Services

### VRPirates Service
**Base URL**: Configurable (fetched dynamically)

| Method | Endpoint | Description | Response Type |
|--------|----------|-------------|---------------|
| `GET` | `downloads/vrp-public.json` | Fetches the public configuration containing the rclone password and base URI. | `PublicConfig` |

#### Data Models
**PublicConfig**
```kotlin
data class PublicConfig(
    val baseUri: String,
    val password64: String // Base64 encoded password
)
```

### GitHub Service
**Base URL**: `https://api.github.com/`

| Method | Endpoint | Description | Response Type |
|--------|----------|-------------|---------------|
| `GET` | `repos/LeGeRyChEeSe/rookie-on-quest/releases/latest` | Fetches the latest release information to check for updates. | `GitHubRelease` |

#### Data Models
**GitHubRelease**
```kotlin
data class GitHubRelease(
    val tagName: String,
    val htmlUrl: String,
    val body: String,
    val assets: List<GitHubAsset>
)
```

**GitHubAsset**
```kotlin
data class GitHubAsset(
    val name: String,
    val downloadUrl: String
)
```
