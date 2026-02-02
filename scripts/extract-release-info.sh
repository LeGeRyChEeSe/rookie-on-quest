#!/bin/bash

# extract-release-info.sh
# Extracts version, versionCode, and changelog section from project files.
# Usage: ./scripts/extract-release-info.sh <mode> [version]
# Modes:
#   version      - Extract versionName from build.gradle.kts
#   version-code - Extract versionCode from build.gradle.kts
#   changelog    - Extract changelog section for [version] from CHANGELOG.md

MODE=$1
VERSION=$2

# Allow overriding paths for testing
BUILD_GRADLE="${BUILD_GRADLE:-app/build.gradle.kts}"
CHANGELOG="${CHANGELOG:-CHANGELOG.md}"

case $MODE in
    version)
        # Extract default versionName from build.gradle.kts
        # Pattern matches the 'versionNameProperty == null -> "X.Y.Z"' line
        # Handles optional spaces and semver strings
        VAL=$(sed -n 's/.*versionNameProperty[[:space:]]*==[[:space:]]*null[[:space:]]*->[[:space:]]*"\([^"]*\)".*/\1/p' "$BUILD_GRADLE" | head -n 1 | xargs)
        if [ -z "$VAL" ]; then
            echo "Error: Could not extract versionName from $BUILD_GRADLE" >&2
            exit 1
        fi
        echo "$VAL"
        ;;
    version-code)
        # Extract default versionCode from build.gradle.kts
        # Pattern matches the 'versionCodeProperty == null -> N' line
        # Handles underscores in numbers (valid Kotlin syntax)
        VAL=$(sed -n 's/.*versionCodeProperty[[:space:]]*==[[:space:]]*null[[:space:]]*->[[:space:]]*\([0-9_]*\).*/\1/p' "$BUILD_GRADLE" | head -n 1 | sed 's/_//g' | xargs)
        if [ -z "$VAL" ]; then
            echo "Error: Could not extract versionCode from $BUILD_GRADLE" >&2
            exit 1
        fi
        echo "$VAL"
        ;;
    changelog)
        if [ -z "$VERSION" ]; then
            echo "Error: Version required for changelog extraction" >&2
            exit 1
        fi
        
        # Escape version for regex (dots are literal)
        ESC_VERSION=$(echo "$VERSION" | sed 's/\./\\./g')
        
        # Find start line (matches ^## [X.Y.Z])
        # Using start-of-line anchor ^ to avoid false positives
        START_LINE=$(grep -n "^## \[$ESC_VERSION\]" "$CHANGELOG" | head -n 1 | cut -d: -f1)
        
        if [ -z "$START_LINE" ]; then
            # Try without brackets as fallback for robustness
            START_LINE=$(grep -n "^## $ESC_VERSION" "$CHANGELOG" | head -n 1 | cut -d: -f1)
        fi

        if [ -z "$START_LINE" ]; then
            echo "Error: Version [$VERSION] not found in $CHANGELOG" >&2
            exit 1
        fi
        
        # Find next version header (## [X.Y.Z] or ## [Unreleased] or any ##)
        # We look for the next line starting with ## that isn't the current one
        # Use tail to start searching after the found version header
        NEXT_HEADER_RELATIVE=$(tail -n +$((START_LINE + 1)) "$CHANGELOG" | grep -n "^## " | head -n 1 | cut -d: -f1)
        
        if [ -z "$NEXT_HEADER_RELATIVE" ]; then
            # No more headers, take everything until EOF
            tail -n +$((START_LINE + 1)) "$CHANGELOG"
        else
            # Extract lines between current header and next header
            END_OFFSET=$((NEXT_HEADER_RELATIVE - 1))
            tail -n +$((START_LINE + 1)) "$CHANGELOG" | head -n "$END_OFFSET"
        fi
        ;;
    *)
        echo "Usage: $0 {version|version-code|changelog} [version]" >&2
        exit 1
        ;;
esac