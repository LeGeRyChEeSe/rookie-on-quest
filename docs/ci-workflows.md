# CI/CD Workflows Documentation

## Gradle Executable Permissions (gradlew)

In all GitHub Actions workflows, we ensure the Gradle wrapper (`gradlew`) has the executable bit set using `chmod +x gradlew`.

### Why we keep this step:
1.  **Zero-cost safety:** The command takes less than a second and ensures the workflow never fails due to permission issues.
2.  **Cross-platform consistency:** While Git usually preserves the executable bit (100755), some CI environments or manual operations might accidentally strip it.
3.  **Self-healing:** If a developer accidentally commits `gradlew` without the executable bit, the CI pipeline remains functional and can even be used to identify the issue.

## GitHub Actions Versioning Policy

To ensure both stability and security, we follow a dual pinning strategy for GitHub Actions:

1.  **Standard Actions (`actions/*`):** Pinned to their latest minor version (e.g., `@v4.2.0`) to ensure reproducibility within a major release while allowing for manual updates to incorporate bug fixes and features.
2.  **Third-Party Actions:** Pinned to specific tags or commit hashes to mitigate supply chain risks.

*Note: Different actions (e.g., `cache` vs `upload-artifact`) may have different minor versions even if they share the same major version. We always aim for the latest stable minor release for each.*

### Version Update Process:
- **When:** Updates should be reviewed quarterly or when security vulnerabilities (CVEs) are reported.
- **How:** Manually update the version tags in workflow YAML files and composite actions. After updating, run `PR Validation` and `Release Build` (dry run if possible) to ensure no breaking changes.
- **By Whom:** Repository maintainers or developers specifically working on CI/CD infrastructure stories.
- **Automation:** While Dependabot can be used for version tracking, manual review is required to ensure compatibility with the Android build environment.

## Build Caching Strategy

We use a custom composite action located at `.github/actions/gradle-cache` for explicit control over Gradle caching. This ensures consistency across all workflows (PR Validation, Release, etc.).

### Composite Action benefits:
- **DRY (Don't Repeat Yourself):** Cache configuration is defined in one place.
- **Reporting:** Automatically logs cache hit/miss status.
- **Maintainability:** Easier to update paths or keys project-wide.

### Cached Paths:
- `~/.gradle/caches`: Dependency caches.
- `~/.gradle/wrapper`: Gradle distribution.
- `~/.gradle/notifications`: Gradle build notifications and metadata.

### Cache Keys:
- **Primary Key:** `gradle-${{ runner.os }}-${{ hashFiles('**/*.gradle.kts', '**/gradle.properties', '**/gradle-wrapper.properties') }}`.
- **Intermediate Key:** `gradle-${{ runner.os }}-${{ hashFiles('**/gradle-wrapper.properties') }}-`.
- **Fallback Key:** `gradle-${{ runner.os }}-`.

**Trade-off on Key Granularity:**
The use of `hashFiles` on `**/*.gradle.kts` means that any change—including comments or formatting—will invalidate the primary cache key. While more granular hashing (excluding comments) was considered, we chose to prioritize build integrity and simplicity. The intermediate key provides a safety net, ensuring that even on a primary cache miss, the Gradle distribution and some dependencies remain cached.

### Cache Invalidation and Troubleshooting:
If the build fails due to a corrupted cache or you need to force a fresh download of dependencies:
1. **Manual Invalidation (Key Rotation):** Change the cache key prefix in `.github/actions/gradle-cache/action.yml` (e.g., from `gradle-` to `gradle-v2-`).
2. **GitHub UI:** Navigate to `Actions > Caches` in the repository settings and delete the relevant cache entries.
3. **Aggressive Invalidation:** The `**/*.gradle.kts` pattern ensures that any change to build scripts (dependencies, plugins, logic) will trigger a fresh cache entry to maintain build integrity.

## Parallel Execution Strategy

To achieve the performance targets, we split the PR Validation workflow into parallel jobs:
1. **Lint:** Checks code quality.
2. **Unit Tests:** Runs JUnit tests.
3. **Instrumented Tests:** Runs Android Emulator tests (Exception: See below).
4. **Build:** Compiles the debug APK.

A final **Consolidation & Feedback** job aggregates results and posts a summary to the PR.

### Instrumented Tests Exception:
Instrumented tests run on a software-emulated Android device, which is significantly slower than native execution. While we include them in the parallel flow, they are a performance outlier. The timeout is set to 10 minutes (reduced from 15 minutes) to enforce efficiency while allowing for emulator overhead.

## Performance Monitoring

### Externalized Configuration:
Core performance targets and timeouts are externalized to `.github/ci-config.env` to ensure consistency across all workflows and allow for easier maintenance without modifying workflow YAML files directly.

### BUILD_TARGET_SECONDS:
- **PR Validation:** Target is 300 seconds (5 minutes) for the core validation jobs (Lint, Unit Tests, Build). This target **excludes** instrumented tests as they run on emulated hardware.
- **Release Build:** Target is 600 seconds (10 minutes) for a full clean release build, including extraction and signing.

These targets are enforced via dedicated performance check steps or jobs in each workflow.

### Benchmarking:
The `Build Benchmarking` workflow (`.github/workflows/benchmarking.yml`) provides empirical verification of cache effectiveness. It uses separate jobs for cold and warm builds to ensure clean isolation:
1. **Cold Build:** Compiles without any cache, then saves a unique benchmark cache.
2. **Warm Build:** Restores the benchmark cache and performs a cached build.
3. **Comparison:** Calculates time reduction (Target: > 50%).
