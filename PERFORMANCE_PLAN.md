# Performance Optimization Plan - Rookie On Quest

This document outlines the strategy to reduce UI freezes (frame skips) and improve general responsiveness, specifically addressing "Davey" warnings and main thread blocking.

## 1. Diagnostics & Profiling
*   **System Tracing:** Use Android Studio Profiler (System Trace) to identify which functions are blocking the Main Thread during startup and catalog loading.
*   **Composition Tracking:** Use the Layout Inspector to check for unnecessary recompositions in `MainScreen` and `GameListItem`.

## 2. UI Thread Offloading (Immediate Actions)
*   **Alphabet Index Calculation:** Currently, `alphabetInfo` is calculated inside the `MainScreen` composable. For a large catalog, this should be moved to the `MainViewModel` and exposed as a `StateFlow`.
*   **Icon Loading:** Ensure `GameListItem` icons are loaded efficiently.
    *   Verify `Coil` is using a properly configured `ImageLoader` with memory/disk cache.
    *   Use `AsyncImage` correctly to avoid placeholder flickering.
*   **Catalog Parsing:** Verify `CatalogParser.parse` is ALWAYS called within `withContext(Dispatchers.IO)`.

## 3. Compose Optimization
*   **DerivedStateOf:** Use `derivedStateOf` for UI logic that depends on other states (like search filtering results) to prevent over-composition.
*   **Stable Models:** Ensure `GameItemState` and `GameData` are treated as stable by the Compose compiler (using `@Immutable` or `@Stable` if necessary).
*   **List Optimization:** 
    *   Use `contentType` in `LazyColumn` to improve item reuse.
    *   Minimize the complexity of the `PermissionOverlay`.

## 4. Initialization Refactoring
*   **Lazy Initialization:** Defer non-critical startup tasks.
*   **Heavy Init Blocks:** Audit `MainViewModel` and `MainRepository` `init` blocks to ensure no blocking I/O or heavy computation is performed.

## 5. Persistence & Cache
*   **Icon Caching:** Implement a more robust icon caching strategy if icons are being re-decoded frequently.
*   **Repository Sync:** Ensure `catalogMutex` in `MainRepository` is not causing UI-visible delays when multiple components request data.
