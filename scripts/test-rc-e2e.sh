#!/bin/bash
# scripts/test-rc-e2e.sh
# E2E logic test for Story 8.5 Release Candidate flow

GREEN='\033[0;32m'
RED='\033[0;31m'
NC='\033[0m'

echo "=== Story 8.5: E2E RC Flow Validation ==="

# Setup trap for cleanup
cleanup() {
    rm -f "CHANGELOG_TEST.md"
}
trap cleanup EXIT

# 1. Test Regex Validation
test_regex() {
    local version="$1"
    local expected="$2"
    local regex='^[0-9]+\.[0-9]+\.[0-9]+(-(rc|alpha|beta)\.[0-9]+)?$'
    
    if [[ "$version" =~ $regex ]]; then
        actual="MATCH"
    else
        actual="NO_MATCH"
    fi
    
    if [ "$actual" == "$expected" ]; then
        echo -e "${GREEN}[PASS]${NC} Regex: '$version' -> $actual"
    else
        echo -e "${RED}[FAIL]${NC} Regex: '$version' -> $actual (Expected $expected)"
        exit 1
    fi
}

test_regex "2.5.0" "MATCH"
test_regex "2.5.0-rc.1" "MATCH"
test_regex "2.5.0-alpha.5" "MATCH"
test_regex "2.5.0-beta.2" "MATCH"
test_regex "2.5.0-hotfix" "NO_MATCH" # Per Story 8.5 refined regex
test_regex "2.5.0-rc1" "NO_MATCH" # Missing dot

# 2. Test Changelog Extraction for RC with Fallback
CHANGELOG="CHANGELOG.md"
cat > "CHANGELOG_TEST.md" << EOF
# Changelog
## [2.5.1] - 2026-02-10
### ✨ Features
- Future feature

## [2.5.0-rc.2] - 2026-02-05
### 🐞 Bug Fixes
- RC2 specific fix

## [2.5.0] - 2026-02-04
### ✨ Features
- Amazing RC feature

## [2.4.0]
### ✨ Features
- Older feature
EOF

export CHANGELOG="CHANGELOG_TEST.md"
chmod +x scripts/extract-release-info.sh

echo "Testing changelog extraction for 2.5.0-rc.2 (exact match with date)..."
NOTES=$(./scripts/extract-release-info.sh changelog "2.5.0-rc.2")
if echo "$NOTES" | grep -q "RC2 specific fix"; then
    echo -e "${GREEN}[PASS]${NC} RC2 specific extraction successful"
else
    echo -e "${RED}[FAIL]${NC} RC2 specific extraction failed"
    exit 1
fi

echo "Testing changelog fallback for 2.5.0-rc.1..."
NOTES=$(./scripts/extract-release-info.sh changelog "2.5.0-rc.1")

if echo "$NOTES" | grep -q "Amazing RC feature"; then
    echo -e "${GREEN}[PASS]${NC} Changelog fallback successful"
else
    echo -e "${RED}[FAIL]${NC} Changelog fallback failed"
    echo "Output: $NOTES"
    exit 1
fi

# 3. Test Workflow Logic for Release Title and Prerelease Flag
test_release_logic() {
    local version="$1"
    local expected_name="$2"
    local expected_prerelease="$3"
    
    # Simulate release title logic from release.yml
    if [[ "$version" =~ -rc\.[0-9]+$ ]]; then
        RELEASE_NAME="Rookie On Quest v$version (Release Candidate)"
        PRERELEASE="true"
    elif [[ "$version" == *"-"* ]]; then
        RELEASE_NAME="Rookie On Quest v$version"
        PRERELEASE="true"
    else
        RELEASE_NAME="Rookie On Quest v$version"
        PRERELEASE="false"
    fi
    
    echo "Version: $version -> Name: '$RELEASE_NAME', Prerelease: $PRERELEASE"
    
    if [[ "$RELEASE_NAME" == "$expected_name" ]] && [[ "$PRERELEASE" == "$expected_prerelease" ]]; then
         echo -e "${GREEN}[PASS]${NC} Logic correct for $version"
    else
         echo -e "${RED}[FAIL]${NC} Logic incorrect for $version"
         echo "Expected: Name='$expected_name', Prerelease=$expected_prerelease"
         exit 1
    fi
}

test_release_logic "2.5.0-rc.1" "Rookie On Quest v2.5.0-rc.1 (Release Candidate)" "true"
test_release_logic "2.5.0-alpha.1" "Rookie On Quest v2.5.0-alpha.1" "true"
test_release_logic "2.5.0" "Rookie On Quest v2.5.0" "false"

echo -e "
${GREEN}=== Story 8.5 E2E Validation Complete ===${NC}"
