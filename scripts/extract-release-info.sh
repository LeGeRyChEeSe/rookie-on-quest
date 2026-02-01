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
BUILD_GRADLE="app/build.gradle.kts"
CHANGELOG="CHANGELOG.md"

case $MODE in
    version)
        # Extract default versionName from build.gradle.kts
        # Pattern: versionNameProperty == null -> "X.Y.Z"
        grep "^ *versionNameProperty == null -> " "$BUILD_GRADLE" | sed 's/.*-> \"\(.*\)\".*/\1/' | tr -d '"' | xargs
        ;;
    version-code)
        # Extract default versionCode from build.gradle.kts
        # Pattern: versionCodeProperty == null -> N
        grep "^ *versionCodeProperty == null -> " "$BUILD_GRADLE" | sed 's/.*-> \([0-9]*\).*/\1/' | xargs
        ;;
    changelog)
        if [ -z "$VERSION" ]; then
            echo "Error: Version required for changelog extraction" >&2
            exit 1
        fi
        
        # Escape version for regex (dots are literal)
        ESC_VERSION=$(echo "$VERSION" | sed 's/\./\\./g')
        
        # Find start line (matches ## [X.Y.Z])
        START_LINE=$(grep -n "## \[$ESC_VERSION\]" "$CHANGELOG" | head -n 1 | cut -d: -f1)
        
        if [ -z "$START_LINE" ]; then
            echo "Error: Version [$VERSION] not found in $CHANGELOG" >&2
            exit 1
        fi
        
        # Find next header start line (starting from line after START_LINE)
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