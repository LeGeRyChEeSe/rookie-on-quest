# Universal Git Worktree System for Multi-Agent Development

## Overview

This system enables **agent-agnostic** isolated development using git worktrees. It works with **any AI agent** (Claude, Qwen, Cursor, Copilot, custom agents) and human developers.

**Key Innovation:** The worktree detection is built into the core workflow engine (`workflow.xml`), so ALL BMAD workflows inherit this capability automatically.

---

## Quick Start

### For ANY Agent (Universal)

```bash
# Step 1: Initialize worktree (one command)
./scripts/init-worktree.sh 1-8 agent1     # Linux/Mac
scripts\init-worktree.bat 1-8 agent1      # Windows

# Step 2: Navigate to worktree
cd worktrees/agent1-story-1-8

# Step 3: Run ANY BMAD workflow - auto-detects story from .story-id
# The workflow automatically reads .story-id and implements that story
```

**That's it!** No agent-specific configuration needed.

---

## How It Works

### Architecture

```
worktrees/agent1-story-1-8/
├── .story-id          # Contains: "1-8"
├── .story-files       # Tracks modified files for conflict prevention
└── [full repo copy]
```

### Detection Flow

```
1. User calls workflow (e.g., dev-story)
   ↓
2. workflow.xml executes detect_worktree protocol
   ↓
3. Protocol checks for .story-id file
   ↓
4. If found: Auto-configure story from .story-id
   If not found: Use normal sprint-status discovery
   ↓
5. Workflow executes with auto-detected story
```

### Universal Compatibility Matrix

| Agent | Works? | Why |
|-------|--------|-----|
| **Claude** | ✅ | Read tool + Bash tool |
| **Qwen** | ✅ | Same tools as Claude |
| **Cursor** | ✅ | Natural file reading |
| **Copilot** | ✅ | VS Code integration |
| **Custom Agent** | ✅ | Any agent with file read + bash |
| **Human** | ✅ | Manual workflow supported |

**Why it works:** Uses only standard files (`.story-id`) and bash commands - no agent-specific APIs.

---

## File System Convention (The Secret)

The system relies on **simple text files** that any agent can read:

### `.story-id`
```
1-8
```
Just the story ID. That's it.

### `.story-files`
```yaml
storyId: "1-8"
storyTitle: "Permission Flow for Installation"
storyFiles:
  - app/src/main/java/com/vrpirates/rookieonquest/MainActivity.kt
  - app/src/main/java/com/vrpirates/rookieonquest/ui/*.kt
modifiedDuringStory: []
```

**Detection Examples (any language):**

```bash
# Bash
STORY_ID=$(cat .story-id)

# PowerShell
$STORY_ID = Get-Content .story-id

# Python
story_id = open('.story-id').read().strip()

# Kotlin
val storyId = File(".story-id").readText().trim()

# Rust
let story_id = fs::read_to_string(".story-id")?.trim();
```

---

## Protocol Integration

### Core Workflow Engine (`workflow.xml`)

Added `detect_worktree` protocol that all workflows can invoke:

```xml
<protocol name="detect_worktree">
  <flow>
    <step n="1">Check for .story-id file</step>
    <step n="2">Load story context from .story-id</step>
    <step n="3">Configure workflow variables</step>
    <step n="4">Conflict prevention check</step>
  </flow>
</protocol>
```

### Dev Story Workflow (`dev-story`)

Modified to auto-invoke worktree detection:

```xml
<!-- Step 0: Worktree detection -->
<step n="0" goal="Detect worktree environment">
  <action>invoke-protocol name="detect_worktree"</action>

  <check if="{worktree_story_id} is NOT empty">
    <goto anchor="task_check" />  <!-- Skip sprint-status search -->
  </check>
</step>

<!-- Step 1: Normal story discovery (if no .story-id) -->
<step n="1" goal="Find next ready story">
  <!-- ... existing logic ... -->
</step>
```

**Result:** Worktree stories get priority over sprint-status search.

---

## Complete Workflow Example

### Scenario: Agent works on Story 1-8

```bash
# 1. Initialize worktree
./scripts/init-worktree.sh 1-8 agent1

# Output:
# ✅ Worktree created: worktrees/agent1-story-1-8
# ✅ Story ID set to: 1-8
# ✅ .story-files manifest created

# 2. Navigate to worktree
cd worktrees/agent1-story-1-8

# 3. Run dev-story workflow
# Agent auto-detects:
#   - Reading .story-id → "1-8"
#   - Loading story file: _bmad-output/implementation-artifacts/1-8-*.md
#   - Using isolated worktree for all changes

# 4. Agent implements story
# All changes stay in worktree

# 5. On completion, agent creates PR
git push -u origin agent1-story-1-8
gh pr create --title "[Story 1-8] Permission Flow"

# 6. After merge, cleanup
cd ../..
git worktree remove worktrees/agent1-story-1-8
git branch -D agent1-story-1-8
```

---

## Multi-Agent Parallel Development

### Scenario: 3 agents working simultaneously

```bash
# Terminal 1: Agent 1 on Story 1-8
./scripts/init-worktree.sh 1-8 agent1
cd worktrees/agent1-story-1-8
# Agent 1 implements...

# Terminal 2: Agent 2 on Story 2-1
./scripts/init-worktree.sh 2-1 agent2
cd worktrees/agent2-story-2-1
# Agent 2 implements...

# Terminal 3: Human on Story 1-9
./scripts/init-worktree.sh 1-9 john
cd worktrees/john-story-1-9
# John implements...
```

**No conflicts because:**
- Each agent has isolated git worktree
- `.story-files` tracks which files are being modified
- Each agent creates separate PR

---

## Conflict Prevention

### `.story-files` Manifest

```yaml
storyId: "1-8"
storyFiles:
  - app/src/main/java/com/vrpirates/rookieonquest/MainActivity.kt
  - app/src/main/java/com/vrpirates/rookieonquest/ui/*.kt
```

### Pre-Story Conflict Check

```bash
# Before starting story, check for file conflicts
grep -r "MainActivity.kt" worktrees/*/.story-files

# Output (if conflict):
# worktrees/agent1-story-1-7/.story-files:  - app/src/.../MainActivity.kt
# worktrees/agent2-story-2-1/.story-files:  - app/src/.../MainActivity.kt
#
# ⚠️ CONFLICT: MainActivity.kt locked by agent1-story-1-7
# Solution: Wait for story 1-7 to complete, or reassign
```

---

## Completion Workflow

### When Story is Complete

**Instead of merging directly, create a PR:**

```bash
# 1. Push branch
git push -u origin agent1-story-1-8

# 2. Create PR
gh pr create \
  --title "[Story 1-8] Permission Flow for Installation" \
  --body "## Summary
Story completed per acceptance criteria.

## Changes
- Implemented permission request flow
- Added tests for permission scenarios
- Updated UI with permission dialogs

## Test Results
- Unit tests: PASS
- Instrumented tests: PASS
- Coverage: 85%

## Files
See .story-files for complete list"

# 3. Wait for review and merge

# 4. Cleanup after merge
cd ../..
git worktree remove worktrees/agent1-story-1-8
git branch -D agent1-story-1-8
```

---

## Why This Is Universal

| Aspect | Solution | Works Everywhere |
|--------|----------|------------------|
| **Detection** | File: `.story-id` | Any agent can read files |
| **Isolation** | Git worktree | Standard git feature |
| **Initialization** | Bash scripts | Runs on any OS |
| **Tracking** | YAML manifest | Human-readable, parseable |
| **Completion** | GitHub CLI | `gh` works everywhere |

**No proprietary APIs, no agent-specific features, no magic.**

Just:
- Files (text)
- Git (standard)
- Bash (universal)

---

## Documentation References

| File | Purpose |
|------|---------|
| `CONTRIBUTING.md` | Contributor guide with worktree workflow |
| `DEVELOPMENT.md` | Internal development process |
| `scripts/init-worktree.sh` | Linux/Mac initialization script |
| `scripts/init-worktree.bat` | Windows initialization script |
| `_bmad/core/tasks/workflow.xml` | Core workflow engine with `detect_worktree` protocol |

---

## Troubleshooting

### Issue: Workflow doesn't detect story

**Symptoms:** Workflow searches sprint-status instead of using .story-id

**Fix:**
```bash
# Verify .story-id exists
cat .story-id

# Should output: "1-8" (or your story ID)

# If missing, recreate:
echo "1-8" > .story-id
```

### Issue: Worktree creation fails

**Symptoms:** `git worktree add` fails

**Fix:**
```bash
# Remove existing worktree/branch
git worktree remove agent1-story-1-8
git branch -D agent1-story-1-8

# Try again
./scripts/init-worktree.sh 1-8 agent1
```

### Issue: Changes appear in main repo

**Symptoms:** Files created in main directory instead of worktree

**Fix:**
```bash
# Verify you're in worktree
pwd
# Should show: .../worktrees/agent1-story-1-8

# If not, navigate:
cd worktrees/agent1-story-1-8
```

---

## Migration Guide

### From Manual Development to Worktree System

**Before:**
```bash
# Work directly in main repo
cd /path/to/rookie-on-quest
# Make changes...
# Commit to main...
```

**After:**
```bash
# Initialize worktree
./scripts/init-worktree.sh 1-8 myname
cd worktrees/myname-story-1-8

# Make changes...
# Push and create PR...
```

**Benefits:**
- Isolated changes
- Parallel development
- PR review process
- Conflict prevention

---

## Advanced Usage

### Custom Agent Integration

To integrate a custom agent with this system:

```python
# Example: Custom agent in Python
import os
import subprocess

def detect_story():
    """Universal story detection - works for any agent"""
    if os.path.exists('.story-id'):
        with open('.story-id') as f:
            return f.read().strip()
    return None

def init_worktree(story_id, agent_name):
    """Initialize worktree using provided script"""
    script = './scripts/init-worktree.sh'
    subprocess.run([script, story_id, agent_name])

# Usage
story_id = detect_story()
if story_id:
    print(f"Working on story: {story_id}")
else:
    story_id = input("Enter story ID: ")
    init_worktree(story_id, "custom-agent")
```

---

**Conclusion:** This system is **universal by design**. It relies on files, git, and bash - technologies available to every agent and platform. No agent-specific features required.
