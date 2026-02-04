#!/bin/bash

# extract-release-info.sh
# Extracts version, versionCode, and changelog section from project files.
# Usage: ./scripts/extract-release-info.sh <mode> [version]
# Modes:
#   version      - Extract versionName from build.gradle.kts
#   version-code - Extract versionCode from build.gradle.kts
#   changelog    - Extract changelog section for [version] from CHANGELOG.md
#                  (Supports optional date suffix e.g., ## [2.5.0] - 2026-02-04)
#                  (Falls back to base version if RC/beta/alpha header is missing)

MODE=$1
VERSION=$2

# Allow overriding paths for testing
BUILD_GRADLE="${BUILD_GRADLE:-app/build.gradle.kts}"
CHANGELOG="${CHANGELOG:-CHANGELOG.md}"

case $MODE in
    version)
        # Extract default versionName from build.gradle.kts
        # Pattern matches the 'versionNameProperty == null -> "X.Y.Z"' line
        # Anchored to start of line to avoid matching documentation comments
        VAL=$(grep "^[[:space:]]*versionNameProperty == null ->" "$BUILD_GRADLE" | head -n 1 | sed 's/[^"]*"//;s/".*//')
        if [ -z "$VAL" ]; then
            echo "Error: Could not extract versionName from $BUILD_GRADLE" >&2
            exit 1
        fi
        echo "$VAL"
        ;;
    version-code)
        # Extract default versionCode from build.gradle.kts
        # Pattern matches the 'versionCodeProperty == null -> N' line
        # Anchored to start of line to avoid matching documentation comments
        VAL=$(grep "^[[:space:]]*versionCodeProperty == null ->" "$BUILD_GRADLE" | head -n 1 | sed 's/.*->//;s/\/\/.*//;s/[^0-9]//g')
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
        
        # Find start line (matches ^## [X.Y.Z] or ^## [X.Y.Z] - YYYY-MM-DD)
        # Using start-of-line anchor ^ to avoid false positives
        # Regex matches the version followed by optional suffix
        START_LINE=$(grep -nE "^## \[$ESC_VERSION\]( - .*)?$" "$CHANGELOG" | head -n 1 | cut -d: -f1)
        
        if [ -z "$START_LINE" ]; then
            # Try without brackets as fallback for robustness
            START_LINE=$(grep -nE "^## $ESC_VERSION( - .*)?$" "$CHANGELOG" | head -n 1 | cut -d: -f1)
        fi

        if [ -z "$START_LINE" ] && [[ "$VERSION" == *"-"* ]]; then
            # AC6 Fallback: If specific RC version (e.g., 2.5.0-rc.1) not found in CHANGELOG.md,
            # we fall back to the base version (2.5.0).
            BASE_VERSION=$(echo "$VERSION" | cut -d- -f1)
            ESC_BASE=$(echo "$BASE_VERSION" | sed 's/\./\\./g')
            echo "Notice: Changelog section for RC version [$VERSION] not found. Falling back to base version [$BASE_VERSION] for release notes."
            
            START_LINE=$(grep -nE "^## \[$ESC_BASE\]( - .*)?$" "$CHANGELOG" | head -n 1 | cut -d: -f1)
            if [ -z "$START_LINE" ]; then
                START_LINE=$(grep -nE "^## $ESC_BASE( - .*)?$" "$CHANGELOG" | head -n 1 | cut -d: -f1)
            fi
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