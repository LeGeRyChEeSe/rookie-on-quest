# Asset Inventory - Rookie On Quest (App)

## Overview
As a catalog-driven application, the majority of visual assets are dynamic (downloaded from remote sources). Static assets are kept to a minimum to maintain a small APK size.

## Static Assets

### Icons & Drawables
Located in `app/src/main/res/`:
- `drawable/app_icon.png`: The main application icon.
- `drawable/ic_launcher_foreground.xml`: Adaptive icon foreground.
- `mipmap-*/ic_launcher.xml`: Adaptive icon definitions.

## Dynamic Assets (Cached)
The application downloads and caches the following assets in its internal storage:

### Game Icons
- **Source**: VRPirates repository.
- **Cache Location**: Internal storage `icons/` directory.
- **Format**: PNG/JPG (mapped by `packageName`).

### Screenshots & Thumbnails
- **Source**: VRPirates repository / GitHub.
- **Cache Location**: Internal storage `thumbnails/` directory.
- **Format**: PNG/JPG.

## Downloadable Artifacts
The app manages the download of large binary assets:
- **APKs**: Android application packages.
- **OBBs**: Opaque Binary Blobs (game data).
- **Archives**: 7z or Zip files containing both of the above.

## Asset Management Strategy
- **Image Loading**: Handled by **Coil**, which provides automatic memory and disk caching for remote images.
- **Cleanup**: The `MainRepository` provides methods to clear cached icons and thumbnails to free up space.
- **Extraction**: Archives are extracted using `Apache Commons Compress`, with OBBs automatically moved to `/sdcard/Android/obb/`.