# Deployment Guide - Rookie On Quest

**Generated:** 2026-01-09
**CI/CD Platform:** GitHub Actions
**Distribution:** GitHub Releases

## Overview

Rookie On Quest uses **GitHub Actions** for automated builds and **GitHub Releases** for distribution. Release APKs are automatically built, signed, and published when a new version tag is pushed.

---

## CI/CD Pipeline

### GitHub Actions Workflow

**Location:** `.github/workflows/`

**Workflows:**
- **Build & Test** - Runs on every push/PR
- **Release** - Triggers on version tags (`vX.X.X`)

### Build Workflow (`.github/workflows/build.yml`)

**Triggers:**
- Push to `main` or `dev` branches
- Pull requests

**Steps:**
1. Checkout code
2. Set up JDK 17
3. Cache Gradle dependencies
4. Run `./gradlew assembleDebug`
5. Upload debug APK as artifact

### Release Workflow (`.github/workflows/release.yml`)

**Triggers:**
- Tag push matching pattern `v*` (e.g., `v2.4.0`)

**Steps:**
1. Checkout code at tag
2. Set up JDK 17
3. Decode signing key from secrets
4. Run `./gradlew assembleRelease`
5. Create GitHub Release
6. Upload signed APK to release

---

## Release Process

### 1. Update Version

Edit `app/build.gradle.kts`:

```kotlin
android {
    defaultConfig {
        versionCode = 16
        versionName = "2.5.0"
    }
}
```

**Or use Makefile:**
```bash
make set-version V=2.5.0
```

This also updates `CHANGELOG.md` with a new version section.

### 2. Update Changelog

Edit `CHANGELOG.md` to document changes:

```markdown
## [2.5.0] - 2026-01-09

### Added
- Dark mode support
- Offline browsing improvements

### Fixed
- Queue processing memory leak
```

### 3. Commit and Tag

```bash
git add .
git commit -m "chore: release v2.5.0"
git tag v2.5.0
git push origin main
git push origin v2.5.0
```

### 4. Automated Build

GitHub Actions detects the tag and:
- Builds release APK
- Signs with keystore from secrets
- Creates GitHub Release with changelog
- Uploads APK as release asset

### 5. Verify Release

Visit [Releases Page](https://github.com/LeGeRyChEeSe/rookie-on-quest/releases) to confirm:
- ✅ Release created with correct tag
- ✅ APK attached (`RookieOnQuest-vX.X.X.apk`)
- ✅ Changelog displayed in release notes

---

## GitHub Secrets Configuration

**Required Secrets (Repository Settings > Secrets and Variables > Actions):**

| Secret Name | Description | Example |
|-------------|-------------|---------|
| `KEYSTORE_FILE` | Base64-encoded keystore file | `base64 keystore.jks` |
| `KEYSTORE_PASSWORD` | Keystore password | `myStorePass123` |
| `KEY_ALIAS` | Signing key alias | `rookie_release_key` |
| `KEY_PASSWORD` | Key password | `myKeyPass456` |

### Encoding Keystore

```bash
# Encode keystore to base64
base64 -w 0 keystore.jks > keystore_base64.txt

# Copy contents and add to GitHub Secrets as KEYSTORE_FILE
```

---

## Manual Release (Fallback)

If CI/CD fails, create a release manually:

### 1. Build Release APK

```bash
./gradlew assembleRelease
```

Output: `app/build/outputs/apk/release/RookieOnQuest-vX.X.X.apk`

### 2. Create GitHub Release

```bash
# Install GitHub CLI (gh)
gh release create v2.5.0 \
  --title "v2.5.0 - Feature Update" \
  --notes "See CHANGELOG.md for details" \
  app/build/outputs/apk/release/RookieOnQuest-v2.5.0.apk
```

**Or manually via GitHub Web UI:**
1. Go to Releases > Draft a new release
2. Choose tag `v2.5.0`
3. Upload APK
4. Paste changelog
5. Publish release

---

## Signing Configuration

### Release Signing

**File:** `app/build.gradle.kts`

```kotlin
android {
    signingConfigs {
        create("release") {
            storeFile = file(keystoreProperties["storeFile"] as String)
            storePassword = keystoreProperties["storePassword"] as String
            keyAlias = keystoreProperties["keyAlias"] as String
            keyPassword = keystoreProperties["keyPassword"] as String
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            proguardFiles(...)
        }
    }
}
```

### Generating a New Keystore

```bash
keytool -genkeypair -v \
  -keystore rookie_keystore.jks \
  -keyalg RSA \
  -keysize 2048 \
  -validity 10000 \
  -alias rookie_release_key
```

**⚠️ Important:** Store keystore securely! Loss = inability to update app on users' devices.

---

## APK Optimization

### ProGuard/R8

**Enabled in Release:** Yes
**Configuration:** `proguard-rules.pro`

**Features:**
- Code shrinking (removes unused code)
- Obfuscation (renames classes/methods)
- Optimization (bytecode improvements)

**Keep Rules:**
```proguard
# Keep Retrofit interfaces
-keep interface com.vrpirates.rookieonquest.network.** { *; }

# Keep Room entities
-keep class com.vrpirates.rookieonquest.data.GameEntity { *; }
```

### APK Size Reduction

**Current Size:** ~8 MB (release APK)

**Strategies:**
- R8 shrinking enabled
- No unused resources included
- Vector drawables preferred over PNGs
- Dynamic asset downloading (icons, thumbnails)

---

## Distribution Channels

### Primary: GitHub Releases

**URL:** https://github.com/LeGeRyChEeSe/rookie-on-quest/releases

**Advantages:**
- Free hosting
- Version history
- Direct APK downloads
- Changelog integration

### Alternative: SideQuest (Future)

**Status:** Not yet listed

**Requirements for SideQuest listing:**
- Privacy policy URL
- Age rating
- Screenshot set (6 images)
- Application submission

---

## Update Mechanism

### In-App Update Check

**Trigger:** On app launch
**API:** GitHub API `/repos/LeGeRyChEeSe/rookie-on-quest/releases/latest`

**Flow:**
1. Fetch latest release tag from GitHub API
2. Compare with `BuildConfig.VERSION_NAME`
3. If newer version available:
   - Display update dialog
   - Offer to download APK
   - Install via `FileProvider`

**Code:** See `MainViewModel.kt` → `checkForUpdates()`

### Update Installation

APK downloaded to:
`/sdcard/Download/RookieOnQuest/updates/RookieOnQuest-vX.X.X.apk`

Installation triggered via Android's `PackageInstaller`.

---

## Rollback Strategy

### If Release Has Critical Bug

1. **Unpublish Release** (GitHub > Releases > Edit > Delete)
2. **Revert Tag:**
   ```bash
   git tag -d v2.5.0
   git push origin :refs/tags/v2.5.0
   ```
3. **Fix Bug and Re-release as Patch:**
   ```bash
   make set-version V=2.5.1
   git commit -m "fix: critical bug from v2.5.0"
   git tag v2.5.1
   git push origin main --tags
   ```

---

## Monitoring & Analytics

**Current Status:** No analytics integrated

**Potential Tools:**
- Google Analytics for Firebase (privacy concerns)
- Self-hosted Plausible Analytics
- GitHub download counts (public metric)

---

## Infrastructure Dependencies

### External Services

| Service | Purpose | Criticality | Owner |
|---------|---------|-------------|-------|
| GitHub Actions | CI/CD | High | GitHub |
| GitHub Releases | APK hosting | High | GitHub |
| VRPirates Servers | Game catalog/downloads | **Critical** | VRPirates Team |
| GitHub API | Update checks | Medium | GitHub |

**⚠️ App Non-Functional Without:** VRPirates server infrastructure

---

## Security Considerations

### APK Signing

- **Algorithm:** RSA 2048-bit
- **Validity:** 10,000 days
- **Storage:** GitHub Secrets (encrypted)

### Keystore Backup

**Recommendation:**
- Store keystore in secure location (encrypted cloud backup)
- Document recovery procedure
- Share with trusted team members

### Secrets Management

- Never commit `keystore.properties` to git
- Rotate secrets if compromised
- Use environment-specific secrets for staging vs. production

---

## Post-Release Checklist

- [ ] Verify release published on GitHub
- [ ] Test APK download from release page
- [ ] Install APK on Quest device and verify functionality
- [ ] Monitor GitHub Issues for user reports
- [ ] Update README.md badge if version format changed
- [ ] Announce release in community channels (optional)

---

## Resources

- **GitHub Actions Docs:** https://docs.github.com/en/actions
- **Android App Signing:** https://developer.android.com/studio/publish/app-signing
- **ProGuard Manual:** https://www.guardsquare.com/manual/home
