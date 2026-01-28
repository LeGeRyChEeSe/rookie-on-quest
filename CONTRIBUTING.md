# Contributing to Rookie On Quest

This document provides guidelines for contributing to Rookie On Quest, including setup for multi-agent development with isolated git worktrees.

## Project Overview

**Rookie On Quest** is a standalone Android application for Meta Quest VR headsets allowing users to browse, download, and install VR games natively.

- **Language:** Kotlin
- **Build System:** Gradle
- **Min SDK:** 29 (Android 10)
- **Target SDK:** 34
- **Architecture:** MVVM with Jetpack Compose

## Development Environment Setup

### Prerequisites

- **JDK:** 17 or higher
- **Android Studio:** Hedgehog (2023.1.1) or newer
- **Git:** Latest version with worktree support
- **ADB:** Android Debug Bridge for Quest device deployment

### Project Structure

```
rookie-on-quest/
├── app/                      # Main application module
├── worktrees/                 # Git worktrees for isolated development (auto-created)
├── docs/                      # Project documentation
├── _bmad-/                    # BMAD methodology artifacts (PRD, sprint status, etc.)
└── scripts/                   # Build and utility scripts
```

## Multi-Agent Development with Git Worktrees

### Overview

This project supports parallel development by multiple AI agents (or human developers) working on different stories simultaneously using isolated git worktrees.

**Key Concepts:**
- **Git Worktree:** Isolated working directories pointing to the same repository
- **Story Isolation:** Each story has its own worktree and branch to prevent conflicts
- **Automatic Detection:** Agents detect their assigned story from `.story-id` file
- **Naming Convention:** Structured branch names for easy identification

### Worktree Structure

```
worktrees/
├── agent1-story-1-1/          # Agent 1, Epic 1, Story 1
│   ├── .story-id              # Contains: "1-1"
│   └── [full repository copy]
├── agent2-story-2-1/          # Agent 2, Epic 2, Story 1
│   ├── .story-id              # Contains: "2-1"
│   └── [full repository copy]
└── agent1-story-1-2/          # Agent 1, Epic 1, Story 2 (next story)
    ├── .story-id              # Contains: "1-2"
    └── [full repository copy]
```

### Branch Naming Convention

**Format:** `{agent}-story-{epic}-{story-number}`

**Examples:**
- `agent1-story-1-1` → Agent 1, Epic 1, Story 1
- `agent2-story-3-2` → Agent 2, Epic 3, Story 2
- `dev-story-feature-x` → Human developer, feature branch

**Rules:**
- **Agent identification:** Use `agent1`, `agent2`, etc. for AI agents
- **Human developers:** Use `dev-{feature-name}` or `{username}-{story-id}`
- **Epic and Story:** Must match sprint-status.yaml epic/story numbers
- **No special characters:** Use only hyphens, no spaces or underscores

## Story Assignment Workflow

### Quick Start (Recommended - Universal Script)

**For ANY agent (Claude, Qwen, Cursor, Copilot, or human):**

```bash
# Linux/Mac
./scripts/init-worktree.sh 1-8 agent1

# Windows
scripts\init-worktree.bat 1-8 agent1

# Then navigate to worktree
cd worktrees/agent1-story-1-8

# Run your agent workflow - it will auto-detect story from .story-id
```

The script automatically:
- Creates git worktree and branch
- Creates `.story-id` file
- Creates `.story-files` manifest
- Verifies setup

### Manual Setup (Alternative)

When starting work on a new story:

```bash
# 1. Create worktree for your story
git worktree add ../rookie-on-quest worktrees/agent1-story-1-1 -b agent1-story-1-1

# 2. Navigate to worktree
cd worktrees/agent1-story-1-1

# 3. Create .story-id file
echo "1-1" > .story-id

# 4. Verify setup
git status
cat .story-id
```

### 2. Switching Stories

When moving from one completed story to another:

```bash
# 1. Return to main repository root
cd ../..

# 2. Create new worktree for next story
git worktree add ../rookie-on-quest worktrees/agent1-story-1-2 -b agent1-story-1-2

# 3. Update .story-id
echo "1-2" > worktrees/agent1-story-1-2/.story-id

# 4. Navigate to new worktree
cd worktrees/agent1-story-1-2

# 5. Verify
git status
cat .story-id
```

### 3. Detecting Active Story

An agent (or developer) can detect their current story assignment at any time:

```bash
# From any location in the repository
cd worktrees/agent1-story-1-1
cat .story-id  # Outputs: "1-1"
```

## Story File Locking

### Purpose

Prevent multiple agents from working on stories that modify the same files, causing merge conflicts.

### Implementation

Each story worktree contains a `.story-files` manifest:

```yaml
# worktrees/agent1-story-1-1/.story-files
storyId: "1-1"
storyTitle: "Room Database Queue Table Setup"
storyFiles:
  - app/src/main/java/com/vrpirates/rookieonquest/data/*.kt
  - app/src/main/java/com/vrpirates/rookieonquest/ui/MainViewModel.kt
  - app/build.gradle.kts
modifiedDuringStory: []
```

### Conflict Prevention Workflow

Before creating a pull request:

```bash
# 1. Check your story's file manifest
cat .story-files

# 2. Check for conflicts with other in-progress stories
git worktree list

# 3. If no conflicts, create PR
gh pr create --title "[Story 1-1] Story Title" --body "See description"

# 4. If conflicts exist, resolve with story assignment team
```

## Story Completion Workflow

### For Human Developers

1. **Complete Implementation:** Finish all story tasks
2. **Run Tests:** Execute unit and instrumented tests
3. **Update Status:** Mark story as "review" in sprint-status.yaml
4. **Code Review:** Submit pull request for review
5. **Validation:** Story marked as "done" after approval

### For AI Agents

1. **Complete Implementation:** Finish all story tasks
2. **Run Tests:** Execute unit and instrumented tests, verify coverage ≥80%
3. **Create Commit:** Generate summary commit with story completion
4. **Create Pull Request:** Use `gh pr create` to submit PR for review
5. **Wait for Approval:** PR must pass CI checks and receive approval
6. **Merge After Approval:** PR merged to main by maintainer or automation
7. **Cleanup:** Delete story worktree and branch after successful merge

### Commit Message Format

```bash
# For story completion
git commit -m "[1-1] Implemented Room Database Queue Table Setup

- Created QueuedInstallEntity with Room DAO
- Added WorkManager integration for auto-restart
- Implemented 100% queue persistence across app restarts
- Added instrumented tests for crash/restart scenarios
- Validated with 100+ crash scenarios

Files modified:
- data/QueuedInstallEntity.kt
- data/GameDao.kt
- ui/MainViewModel.kt
- tests/QueuePersistenceTest.kt
- build.gradle.kts

Story completed and validated per sprint-status.yaml"

# For quick commits during development
git commit -m "[1-1] WIP: Added Room table schema"
```

## Branch Management

### Main Branch

- **Name:** `main`
- **Purpose:** Integration of all completed stories
- **Protection:** Protected branch, requires PR for direct commits

### Story Branches

- **Naming:** `{agent}-story-{epic}-{story-number}`
- **Lifecycle:** Temporary, deleted after story completion
- **Cleanup:** After PR merge to main, delete both branch and worktree:
  ```bash
  git worktree remove worktrees/agent1-story-1-1
  git branch -D agent1-story-1-1
  ```

### Release Branches

- **Naming:** `release/v{major}.{minor}.{patch}`
- **Purpose:** Stable release builds
- **Creation:** Created by CI/CD workflow (FR61-FR73)

## Testing

### Run Tests from Worktree

All tests must be executed from within the story worktree:

```bash
# Unit tests
./gradlew test

# Instrumented tests (requires connected device)
./gradlew connectedAndroidTest

# Specific test class
./gradlew test --tests "*QueuePersistenceTest"
```

### Test Validation Before PR

Before creating pull request:

1. **All unit tests pass:** `./gradlew test`
2. **Code coverage ≥80%:** JaCoCo report verification
3. **Zero lint errors:** `./gradlew lint`
4. **Build succeeds:** `./gradlew assembleDebug`

## Code Style Guidelines

### Kotlin

- Follow Android Kotlin style guide
- Use 4 spaces for indentation
- Maximum line length: 120 characters
- Use explicit types (avoid `val` when type is not obvious)

### Compose

- Follow Jetpack Compose best practices
- Components should be stateless when possible
- Use `@Composable` functions for reusable UI
- Prefix composables with desired content type

### Git Commits

- Write clear, descriptive commit messages
- Reference story IDs when applicable
- Use conventional commit format: `[type] scope: description`

## Pull Request Process

### For Story Completion

1. **Create PR:** From story branch to `main`
2. **Title:** `[Story {story-id}] {story title}`
3. **Description:** Include summary of changes and test results
4. **Requirements:**
   - All tests pass
   - Code coverage maintained
   - No lint errors
   - Story status marked "done" in sprint-status.yaml

### For Bug Fixes

1. **Create PR:** From fix branch to `main`
2. **Title:** `Fix {bug-description}: {issue-number}`
3. **Description:** Explain bug, root cause, and fix
4. **Requirements:** Same as story completion

## Getting Help

### Questions About Project

- Check `docs/` directory for architecture and design documentation
- Review PRD at `_bmad-output/planning-artifacts/prd.md`
- Open an issue on GitHub for questions or problems

### Technical Support

- For build issues: Check `CONTRIBUTING.md` troubleshooting section
- For development setup: See `DEVELOPMENT.md`
- For workflow questions: Contact maintainers

## Troubleshooting

### Worktree Issues

**Problem:** Cannot create worktree
```bash
# Ensure git worktree support
git worktree list

# If worktree already exists, remove it first
git worktree remove worktrees/agent1-story-1-1
```

**Problem:** Story branch not found
```bash
# Verify branch exists in main repository
cd ..  # Return to main repository root
git branch -a | grep agent1-story-1-1
```

**Problem:** Merge conflicts during PR
```bash
# Check for conflicting stories
ls worktrees/*/.story-files

# Resolve conflicts locally then push to PR
# Or contact team for reassignment if conflicts are severe
```

### Multi-Agent Coordination

### Checking Active Stories

```bash
# List all worktrees and their story assignments
for worktree in worktrees/*; do
    echo "Worktree: $worktree"
    if [ -f "$worktree/.story-id" ]; then
        echo "  Story: $(cat $worktree/.story-id)"
    fi
done
```

### Checking File Conflicts

```bash
# Check for overlapping file modifications
grep -h "storyFiles:" worktrees/*/.story-files
```

## Summary

- **Use git worktrees** for isolated story development
- **Follow naming convention** for branches and worktrees
- **Check story status** before creating PR
- **Create clear commit messages** with story references
- **Delete worktrees** after story completion
- **Coordinate with team** to prevent file conflicts

Happy contributing! 🚀
