# Development Guide - Rookie On Quest

**Generated:** 2026-01-09
**Target Platform:** Android (Meta Quest)
**Min SDK:** 29 (Android 10)
**Target SDK:** 34

## Prerequisites

### Required Software
- **Android Studio** Ladybug (2024.2.1) or newer
- **JDK** 17 or newer (bundled with Android Studio)
- **Android SDK 34** (installed via SDK Manager)
- **Git** for version control

### Optional Tools
- **ADB (Android Debug Bridge)** - For device installation and debugging
- **SideQuest** - Alternative installation method for Quest devices
- **Make** (Windows: via WSL or Git Bash) - For Makefile shortcuts

---

## Environment Setup

### 1. Clone Repository

```bash
git clone https://github.com/LeGeRyChEeSe/rookie-on-quest.git
cd rookie-on-quest
```

### 2. Open in Android Studio

1. Launch Android Studio
2. **File > Open** → Select `rookie-on-quest` folder
3. Wait for Gradle sync to complete (~2-5 minutes on first run)
4. SDK download prompts may appear - accept to install missing components

### 3. Configure Signing (Release Builds Only)

For release builds, create `keystore.properties` in project root:

```properties
storeFile=path/to/your/keystore.jks
storePassword=yourStorePassword
keyAlias=yourKeyAlias
keyPassword=yourKeyPassword
```

**Note:** This file is git-ignored. See `keystore.properties.example` for template.

---

## Build Commands

### Using Gradle (All Platforms)

```bash
# Clean build artifacts
./gradlew clean

# Build debug APK
./gradlew assembleDebug

# Build release APK (requires keystore.properties)
./gradlew assembleRelease

# Install debug build to connected device
./gradlew installDebug

# Run unit tests
./gradlew test

# Run lint checks
./gradlew lint
```

### Using Makefile (Windows/Linux)

```bash
# Clean project
make clean

# Build debug
make build

# Build release
make release

# Install to device
make install

# Set version and update changelog
make set-version V=2.5.0
```

---

## Project Structure

```
rookie-on-quest/
├── app/                        # Main application module
│   ├── src/
│   │   ├── main/
│   │   │   ├── java/.../       # Kotlin source files
│   │   │   ├── res/            # Resources (layouts, strings, images)
│   │   │   └── AndroidManifest.xml
│   │   └── test/               # Unit tests
│   └── build.gradle.kts        # Module build configuration
├── build.gradle.kts            # Root build configuration
├── settings.gradle.kts         # Project settings
└── gradle/                     # Gradle wrapper files
```

---

## Running the App

### On Physical Device (Meta Quest)

1. **Enable Developer Mode** on Quest:
   - Create organization at [Meta Quest Developer Dashboard](https://dashboard.oculus.com/)
   - Enable Developer Mode in Meta Quest mobile app

2. **Connect via USB-C**

3. **Install and Run:**
   ```bash
   ./gradlew installDebug
   ```

4. **Launch:** Find "Rookie On Quest" in App Library > Unknown Sources

### Debugging

**Logcat Filtering:**
```bash
adb logcat | grep "MainActivity\|MainViewModel\|MainRepository"
```

**View Database:**
```bash
adb shell
su
cd /data/data/com.vrpirates.rookieonquest/databases/
sqlite3 rookie_database
```

---

## Development Workflow

### Making Changes

1. **Create Feature Branch**
   ```bash
   git checkout -b feat/your-feature-name
   ```

2. **Make Code Changes**
   - Follow Kotlin coding conventions
   - Use `StateFlow` for reactive state
   - Keep UI logic in ViewModel

3. **Test Locally**
   ```bash
   ./gradlew assembleDebug
   ./gradlew installDebug
   ```

4. **Commit with Conventional Commits**
   ```bash
   git commit -m "feat: add dark mode toggle"
   ```

5. **Push and Create PR**
   ```bash
   git push origin feat/your-feature-name
   ```

### Code Style

- **Language:** Kotlin
- **Formatting:** Follow Android Kotlin Style Guide
- **Naming:** camelCase for variables/functions, PascalCase for classes
- **Imports:** Auto-organize in Android Studio (Ctrl+Alt+O)

---

## Common Tasks

### Adding a New Dependency

Edit `app/build.gradle.kts`:

```kotlin
dependencies {
    implementation("com.example:library:1.0.0")
}
```

Then sync Gradle.

### Updating Version

**Manual:**
Edit `app/build.gradle.kts`:
```kotlin
versionCode = 15
versionName = "2.5.0"
```

**Via Makefile:**
```bash
make set-version V=2.5.0
```

### Clearing App Data

```bash
adb shell pm clear com.vrpirates.rookieonquest
```

### Exporting Logs

App includes built-in diagnostic export (accessible via Settings in app).

---

## Testing

### Unit Tests

Location: `app/src/test/`

Run tests:
```bash
./gradlew test
```

**Note:** Current test coverage is minimal. Contributions welcome!

### Manual Testing Checklist

- [ ] Catalog sync completes successfully
- [ ] Game search works correctly
- [ ] Favorites toggle persists
- [ ] Queue installation works (download → extract → install)
- [ ] Update check displays correctly
- [ ] Permissions flow works on fresh install

---

## Troubleshooting

### Gradle Sync Failed

**Solution:**
- Check internet connection
- File > Invalidate Caches / Restart
- Delete `.gradle/` and retry

### APK Installation Failed

**Error:** `INSTALL_FAILED_UPDATE_INCOMPATIBLE`

**Solution:** Uninstall existing app first:
```bash
adb uninstall com.vrpirates.rookieonquest
```

### Device Not Detected

**Solution:**
```bash
adb kill-server
adb start-server
adb devices
```

Ensure USB debugging is enabled in Quest developer settings.

---

## Build Outputs

**Debug APK:**
`app/build/outputs/apk/debug/app-debug.apk`

**Release APK:**
`app/build/outputs/apk/release/RookieOnQuest-vX.X.X.apk`

---

## Resources

- **Android Developers:** https://developer.android.com/
- **Kotlin Docs:** https://kotlinlang.org/docs/
- **Jetpack Compose:** https://developer.android.com/jetpack/compose
- **VRPirates Rookie:** https://github.com/VRPirates/rookie
