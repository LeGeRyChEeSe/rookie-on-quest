# Infrastructure Architecture

## CI/CD Pipeline (GitHub Actions)

The project uses GitHub Actions to automate validation, testing, and release processes.

### PR Validation Pipeline

The PR validation pipeline (`.github/workflows/pr-validation.yml`) is designed to provide fast feedback to developers on every Pull Request targeting the `main` branch.

#### Workflow Triggers
- **Pull Requests:** Triggered on `opened`, `synchronize`, and `reopened` events for the `main` branch.

#### Quality Gates
The pipeline executes the following checks in order:
1. **Linting:** Runs `./gradlew lintDebug` to ensure code style and Android lint rules are respected.
2. **Unit Tests:** Runs `./gradlew testDebugUnitTest` to verify business logic.
3. **Instrumented Tests:** Runs `./gradlew connectedDebugAndroidTest` using an Android Emulator (API 29) to verify UI and integration logic.
4. **Compilation:** Runs `./gradlew assembleDebug` to ensure the project builds successfully.

#### Performance Targets
- **Build Duration (AC7):** Target is < 5 minutes (300s) for the entire validation suite (Lint, Unit Tests, Instrumented Tests, Build). The build will fail if this target is exceeded to prevent performance regressions.
- **Feedback Delivery (AC6):** Target is < 2 minutes for the PR feedback comment to appear **after** the build completion. The consolidated summary script is optimized for rapid execution to meet this requirement.
- **Caching:** Uses `gradle/actions/setup-gradle` to cache Gradle dependencies and build outputs, which is critical for meeting the 5-minute build target.

#### PR Feedback
- **Consolidated Summary:** A single comment is posted/updated on the PR with the status of all checks.
- **Test Reporting:** Uses `EnricoMi/publish-unit-test-result-action` for detailed test summaries in the PR conversation.
- **Artifacts:** Lint HTML reports and test results are uploaded as artifacts for each run.

### Release Pipeline

The release pipeline (`.github/workflows/release.yml`) handles the automated generation of production-ready APKs.

*(Note: The release pipeline is part of the global build automation strategy described in Epic 8).*

## Local Validation

To validate the CI logic locally without waiting for GitHub Actions:
- **Windows:** Use `scripts/test-ci-logic.ps1`
- **Linux/macOS:** Use `scripts/test-ci-logic.sh`

These scripts provide identical validation logic for the CI pipeline's internal state (lint parsing, duration calculation) to ensure the feedback mechanism is robust before pushing changes.

## Design Decisions

- **Fail-Fast Strategy:** Checks are split into separate steps to identify failures as early as possible.
- **GitHub Script for Feedback:** Custom `actions/github-script` is used to provide a tailored PR comment that includes build duration and lint error counts, improving developer experience.
- **Emulator for Instrumented Tests:** Uses `reactivecircus/android-emulator-runner` to run tests on an **Android emulator environment** in the cloud.

## Troubleshooting

If a PR validation fails:
1. Check the **PR Validation Summary** comment in the Pull Request.
2. Click on the **Action Run Summary** link to see detailed logs.
3. Download the **lint-report** artifact if lint errors are reported.
4. Review the **Test Results** tab in the GitHub Action run for failed unit or instrumented tests.
