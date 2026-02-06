# Story 8.2: Secure APK Signing with Keystore Management

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a developer,
I want to configure automated APK signing using secure credentials,
So that release builds are signed properly without exposing keystore secrets in the repository.

## Acceptance Criteria

1. **Given** the GitHub Actions workflow is running
   **When** the release build step executes
   **Then** workflow retrieves keystore credentials from GitHub Secrets (NFR-B8)
   **And** required secrets include: `KEYSTORE_FILE` (base64 encoded), `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`
   **And** secrets are never logged or exposed in workflow output
   **And** workflow creates `keystore.properties` file from secrets during build
   **And** `keystore.properties` is used by `build.gradle.kts` for signing configuration
   **And** temporary `keystore.properties` is deleted after build completes
   **And** build fails with clear error message if any required secret is missing
   **And** signing configuration produces properly signed APK verifiable by `apksigner verify --print-certs` (NFR-B5)

## Tasks / Subtasks

- [x] Task 1: Create GitHub Secrets configuration steps for repository (AC: 1)
  - [x] Subtask 1.1: Document required secrets in README or CONTRIBUTING.md
  - [x] Subtask 1.2: Create base64 encoding instructions for keystore file
  - [x] Subtask 1.3: Verify GitHub Secrets structure matches expected format

- [x] Task 2: Add secret retrieval step to release.yml workflow (AC: 1)
  - [x] Subtask 2.1: Add step to decode `KEYSTORE_FILE` from base64 to binary file
  - [x] Subtask 2.2: Add step to create `keystore.properties` file from secrets
  - [x] Subtask 2.3: Ensure secrets are not exposed in logs (use `add-mask`)
  - [x] Subtask 2.4: Add validation that all required secrets exist before build

- [x] Task 3: Implement secure secret handling (AC: 1)
  - [x] Subtask 3.1: Store decoded keystore file in secure temp location
  - [x] Subtask 3.2: Use GitHub's secret masking to prevent log exposure
  - [x] Subtask 3.3: Delete temporary keystore.properties after build completes
  - [x] Subtask 3.4: Delete temporary keystore file after build completes

- [x] Task 4: Add build failure handling for missing secrets (AC: 1)
  - [x] Subtask 4.1: Add secret existence validation step before build
  - [x] Subtask 4.2: Generate clear error messages indicating which secret is missing
  - [x] Subtask 4.3: Fail fast with actionable error message

- [x] Task 5: Add APK signature verification step (AC: 1)
  - [x] Subtask 5.1: Add `apksigner verify --print-certs` validation step (handled by Story 8.1 foundations and updated in 8.2)
  - [x] Subtask 5.2: Verify signing certificate matches expected keystore
  - [x] Subtask 5.3: Fail build if signature verification fails
  - [x] Subtask 5.4: Log signing certificate details (not sensitive info) for audit

- [x] Task 6: Update build.gradle.kts for CI keystore path (AC: 1)
  - [x] Subtask 6.1: Update signing config to handle CI-generated keystore path
  - [x] Subtask 6.2: Ensure keystore.properties format matches CI-generated file
  - [x] Subtask 6.3: Remove CI failure fallback (now secrets-based signing is required)

## Dev Notes

### Epic 8 Context - Build Automation & Release Management

Story 8.2 builds directly on top of Story 8.1 (GitHub Actions Workflow Foundation). Story 8.1 established the workflow structure with manual trigger, version inputs, and build execution. Story 8.2 adds secure keystore signing to produce properly signed release APKs.

**Dependencies:**
- Story 8.1 (done - in review): GitHub Actions workflow foundation with `release.yml`
- Current signing: Falls back to debug key in CI (CRITICAL security gap to fix)

**Stories building on 8.2:**
- Story 8.3: Version and Changelog Extraction
- Story 8.4: Release Creation and Formatting
- Story 8.5: Release Candidate Build Support

### Architecture Patterns and Constraints

**Security Requirements (NFR-B8):**
- Keystore signing credentials MUST NEVER be in the repository
- Credentials MUST be stored in GitHub Secrets (encrypted at rest)
- Workflow MUST NOT expose secrets in logs (use GitHub's automatic masking)

**Build System:**
- Gradle (Kotlin DSL) with `app/build.gradle.kts`
- Current signing config reads from `keystore.properties` (file-based)
- CI signing will inject `keystore.properties` from GitHub Secrets

**Current Signing Flow (Story 8.1 - to be replaced):**
```kotlin
// app/build.gradle.kts (lines 155-175)
signingConfig = when {
    hasReleaseKeystore -> signingConfigs.getByName("release")
    isCI -> throw GradleException("CI/CD release build requires keystore.properties...")
    else -> signingConfigs.getByName("debug") // Local debug fallback
}
```

**Target Signing Flow (Story 8.2):**
```kotlin
// CI builds: keystore.properties created from GitHub Secrets
// Local builds: keystore.properties must exist or use debug fallback
// All release builds: properly signed with production key
```

### GitHub Secrets Structure

**Required Secrets:**
| Secret Name | Description | Format |
|-------------|-------------|--------|
| `KEYSTORE_FILE` | Keystore file | Base64 encoded binary |
| `KEYSTORE_PASSWORD` | Store password | Plain text |
| `KEY_ALIAS` | Key alias name | Plain text |
| `KEY_PASSWORD` | Key password | Plain text |

**Base64 Encoding Command (for documentation):**
```bash
# Linux/Mac
base64 -w 0 your-keystore.jks > keystore-base64.txt

# Windows (PowerShell)
[Convert]::ToBase64String([IO.File]::ReadAllBytes("your-keystore.jks")) > keystore-base64.txt
```

### Workflow Step Structure

**Proposed new steps to add to release.yml:**

```yaml
- name: Validate signing secrets
  shell: bash
  run: |
    if [ -z "${{ secrets.KEYSTORE_FILE }}" ]; then
      echo "::error::Missing required secret: KEYSTORE_FILE"
      exit 1
    fi
    # ... validate other secrets

- name: Setup signing configuration
  shell: bash
  run: |
    # Decode keystore from base64
    echo "${{ secrets.KEYSTORE_FILE }}" | base64 -d > /tmp/release.keystore

    # Create keystore.properties
    cat > keystore.properties << EOF
    storeFile=/tmp/release.keystore
    storePassword=${{ secrets.KEYSTORE_PASSWORD }}
    keyAlias=${{ secrets.KEY_ALIAS }}
    keyPassword=${{ secrets.KEY_PASSWORD }}
    EOF

    # Mask sensitive values
    echo "::add-mask::${{ secrets.KEYSTORE_PASSWORD }}"
    echo "::add-mask::${{ secrets.KEY_PASSWORD }}"

- name: Verify APK signature
  shell: bash
  run: |
    # Use build-tools apksigner to verify
    $ANDROID_HOME/build-tools/34.0.0/apksigner verify --print-certs \
      app/build/outputs/apk/release/RookieOnQuest-v*.apk
```

### Source Tree Components to Touch

**Files to modify:**
- `.github/workflows/release.yml` - Add signing steps
- `app/build.gradle.kts` - Update CI handling (remove failure, use secrets-based signing)

**Files to create:**
- `docs/SIGNING.md` or update `CONTRIBUTING.md` - Document keystore setup

**Structure:**
```
project-root/
  .github/
    workflows/
      release.yml          # Modify: add signing steps
  app/
    build.gradle.kts       # Modify: update CI signing logic
  docs/
    SIGNING.md             # Create: keystore setup documentation
```

### Testing Standards Summary

**Workflow Validation (manual testing required):**
1. Create a test keystore for GitHub Secrets
2. Encode keystore to base64 and add to repo secrets
3. Trigger workflow and verify:
   - Build completes successfully
   - APK is signed with production key (not debug)
   - `apksigner verify` passes
   - No secrets exposed in workflow logs
4. Test failure cases:
   - Missing `KEYSTORE_FILE` secret -> clear error
   - Missing `KEYSTORE_PASSWORD` secret -> clear error
   - Invalid base64 encoding -> clear error

**Signature Verification:**
```bash
# Verify APK is properly signed
apksigner verify --print-certs RookieOnQuest-v2.5.0.apk

# Expected output:
# Signer #1 certificate DN: CN=..., OU=..., O=..., L=..., ST=..., C=...
# Signer #1 certificate SHA-256 digest: ...
# Signer #1 certificate SHA-1 digest: ...
# Signer #1 certificate MD5 digest: ...
```

### Project Structure Notes

**Alignment with unified project structure:**
- `.github/workflows/` follows standard GitHub Actions conventions
- YAML files use 2 spaces indentation
- Secrets follow GitHub naming conventions (UPPER_SNAKE_CASE)

**Detected conflicts or variances:**
- None - Story 8.1 already established the workflow structure
- This story enhances the existing workflow with signing capability

### Previous Story Intelligence (Story 8.1)

**Key learnings from Story 8.1 implementation:**
- Workflow uses `env:` mapping for secure input handling (prevents command injection)
- Step-level timeouts prevent runaway builds
- `shell: bash` explicit on all run steps for consistency
- Build summary uses `if: always()` to report even on failure
- ProGuard rules established for R8 minification

**Files created/modified in 8.1:**
- `.github/workflows/release.yml` - Main workflow file
- `app/build.gradle.kts` - Signing config, version validation
- `app/proguard-rules.pro` - R8 minification rules

**Code patterns established:**
- Deterministic APK selection using bash arrays (not `head -n 1`)
- Version validation with regex before use
- Consolidated signing logic with `hasReleaseKeystore` variable

### Git Intelligence

**Recent commits (Story 8.1):**
```
2edcdd0 feat(ci): GitHub Actions Workflow Foundation (Story 8.1)
57b01d9 fix(workflow): resolve final adversarial review 5 findings (Story 8.1)
1008a11 fix(workflow): resolve adversarial review 4 findings (Story 8.1)
```

**Key patterns from commits:**
- Extensive review process (192 items resolved)
- Security-focused changes (command injection prevention, secret masking)
- Documentation within code (extensive comments explaining decisions)

### Critical Implementation Notes

**1. Secret Masking:**
GitHub automatically masks secrets in logs, but ensure additional sensitive values (like decoded keystore path) are also masked using `::add-mask::`.

**2. Keystore File Cleanup:**
Temporary keystore file MUST be deleted after build completes:
```yaml
- name: Cleanup signing artifacts
  if: always()
  run: |
    rm -f /tmp/release.keystore
    rm -f keystore.properties
```

**3. Build Failure Behavior:**
Update `build.gradle.kts` to remove the CI failure path (lines 160-175):
- Story 8.1: CI without keystore -> throw GradleException
- Story 8.2: CI always has keystore from secrets -> remove exception

**4. apksigner Location:**
Use `$ANDROID_HOME/build-tools/*/apksigner` pattern:
```bash
APKSIGNER=$(find $ANDROID_HOME/build-tools -name "apksigner" | sort -V | tail -n 1)
```

**5. Keystore.properties Format:**
Must match existing format expected by `build.gradle.kts`:
```properties
storeFile=/tmp/release.keystore
storePassword=<password>
keyAlias=<alias>
keyPassword=<password>
```

### References

**Epic and Story Sources:**
- [Source: _bmad-output/planning-artifacts/epics.md#Epic 8] - Epic 8: Build Automation & Release Management
- [Source: _bmad-output/planning-artifacts/epics.md#Story 8.2] - Story 8.2: Secure APK Signing with Keystore Management

**Story 8.1 Implementation:**
- [Source: .github/workflows/release.yml] - Existing workflow to enhance
- [Source: app/build.gradle.kts:90-196] - Signing configuration to update

**Requirements:**
- FR62: System can sign APK with proper keystore configuration stored in secure credential manager
- NFR-B5: Release APK must be byte-identical (SHA-256 hash match) to local build with same keystore
- NFR-B8: Keystore signing credentials must be stored in secure credential manager (never in repository)

**GitHub Actions Documentation:**
- https://docs.github.com/en/actions/security-guides/encrypted-secrets
- https://docs.github.com/en/actions/using-workflows/workflow-commands-for-github-actions#masking-a-value-in-a-log

**Android Signing Documentation:**
- https://developer.android.com/studio/publish/app-signing
- https://developer.android.com/tools/apksigner

## Dev Agent Record

### Agent Model Used

{{agent_model_name_version}}

### Debug Log References

### Completion Notes List

### File List
