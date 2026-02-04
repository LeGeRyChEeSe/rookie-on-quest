#!/bin/bash

# scripts/test-extraction.sh
# Automated tests for extract-release-info.sh

# Colors for output
GREEN='\033[0;32m'
RED='\033[0;31m'
NC='\033[0m'

SCRIPT="./scripts/extract-release-info.sh"
TEMP_DIR="tmp_test_extraction"
mkdir -p "$TEMP_DIR"

# Setup trap for cleanup
cleanup() {
    rm -rf "$TEMP_DIR"
}
trap cleanup EXIT

# Test counter
PASSED=0
FAILED=0

assert_eq() {
    local expected="$1"
    local actual="$2"
    local msg="$3"
    if [ "$expected" == "$actual" ]; then
        echo -e "${GREEN}[PASS]${NC} $msg"
        PASSED=$((PASSED + 1))
    else
        echo -e "${RED}[FAIL]${NC} $msg"
        echo "       Expected: '$expected'"
        echo "       Actual:   '$actual'"
        FAILED=$((FAILED + 1))
    fi
}

echo "Starting extraction tests..."

# Setup dummy files
DUMMY_GRADLE="$TEMP_DIR/build.gradle.kts"
DUMMY_CHANGELOG="$TEMP_DIR/CHANGELOG.md"

cat > "$DUMMY_GRADLE" << EOF
    defaultConfig {
        val versionCodeProperty = null
        val versionNameProperty = null
        versionCode = when {
            versionCodeProperty == null -> 99
            else -> 1
        }
        versionName = when {
            versionNameProperty == null -> "1.2.3-test"
            else -> "err"
        }
    }
EOF

cat > "$DUMMY_CHANGELOG" << EOF
# Changelog
## [Unreleased]
### Added
- Something

## [1.2.3-test] - 2026-02-01
### ✨ Features
- Test feature with emoji
- Another item

## [1.2.2] - 2026-01-01
### Fixes
- Old fix
EOF

# Override paths in script environment (if script supports it via variables)
# Since the script has hardcoded paths, we'll temporarily swap them or use a symlink
# Actually, I'll modify the script to allow overriding paths via ENV vars for better testability.

# Test 1: Version Extraction
# (Assuming I will update the script to use BUILD_GRADLE env var)
export BUILD_GRADLE="$DUMMY_GRADLE"
export CHANGELOG="$DUMMY_CHANGELOG"

# Before running, make sure script is executable
chmod +x "$SCRIPT"

# Run tests
assert_eq "1.2.3-test" "$($SCRIPT version)" "Extract versionName from Gradle"
assert_eq "99" "$($SCRIPT version-code)" "Extract versionCode from Gradle"

# Test 2: Changelog Extraction
CHANGELOG_OUT=$($SCRIPT changelog "1.2.3-test")
# Check for content
if echo "$CHANGELOG_OUT" | grep -q "Test feature with emoji" && ! echo "$CHANGELOG_OUT" | grep -q "## \[1.2.2]"; then
    assert_eq "0" "0" "Extract correct changelog section"
else
    assert_eq "Changelog content" "Wrong content" "Extract correct changelog section"
fi

# Test 3: RC Fallback Extraction
# Add 1.2.4 entry to dummy changelog
cat >> "$DUMMY_CHANGELOG" << EOF

## [1.2.4] - 2026-02-10
### Added
- RC feature
EOF

CHANGELOG_RC_OUT=$($SCRIPT changelog "1.2.4-rc.1" 2>/dev/null)
if echo "$CHANGELOG_RC_OUT" | grep -q "RC feature"; then
    assert_eq "0" "0" "Extract changelog with RC fallback (1.2.4-rc.1 -> 1.2.4)"
else
    assert_eq "RC fallback content" "Empty or wrong content" "Extract changelog with RC fallback"
fi

# Test 4: Error handling
$SCRIPT changelog "9.9.9" 2>/dev/null
assert_eq "1" "$?" "Fail on missing version in changelog"

echo -e "\nSummary: $PASSED passed, $FAILED failed"
if [ $FAILED -ne 0 ]; then
    exit 1
fi
