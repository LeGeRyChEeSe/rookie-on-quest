#!/bin/bash
# Script to validate CI Configuration loading logic

CONFIG_FILE=".github/ci-config.env"
if [ ! -f "$CONFIG_FILE" ]; then
    echo "❌ Error: $CONFIG_FILE not found"
    exit 1
fi

source "$CONFIG_FILE"

# Test loading of variables
if [ "$BUILD_TARGET_SECONDS_PR" != "300" ]; then
    echo "❌ Error: BUILD_TARGET_SECONDS_PR not loaded correctly (Expected 300, got $BUILD_TARGET_SECONDS_PR)"
    exit 1
fi

if [ "$BUILD_TARGET_SECONDS_RELEASE" != "600" ]; then
    echo "❌ Error: BUILD_TARGET_SECONDS_RELEASE not loaded correctly"
    exit 1
fi

if [ "$TIMEOUT_INSTRUMENTED_TESTS" != "10" ]; then
    echo "❌ Error: TIMEOUT_INSTRUMENTED_TESTS not loaded correctly (Expected 10, got $TIMEOUT_INSTRUMENTED_TESTS)"
    exit 1
fi

if [ "$TIMEOUT_RELEASE_BUILD" != "20" ]; then
    echo "❌ Error: TIMEOUT_RELEASE_BUILD not loaded correctly (Expected 20, got $TIMEOUT_RELEASE_BUILD)"
    exit 1
fi

echo "✅ CI Configuration variables loaded correctly from $CONFIG_FILE"

# Test the loading logic used in workflows
GITHUB_OUTPUT=$(mktemp)
(
  source "$CONFIG_FILE"
  echo "target_pr=$BUILD_TARGET_SECONDS_PR" >> "$GITHUB_OUTPUT"
  echo "timeout_instr=$TIMEOUT_INSTRUMENTED_TESTS" >> "$GITHUB_OUTPUT"
)

grep "target_pr=300" "$GITHUB_OUTPUT" > /dev/null || { echo "❌ Failed to write target_pr to GITHUB_OUTPUT"; exit 1; }
grep "timeout_instr=10" "$GITHUB_OUTPUT" > /dev/null || { echo "❌ Failed to write timeout_instr to GITHUB_OUTPUT"; exit 1; }

rm "$GITHUB_OUTPUT"
echo "✅ CI Workflow loading logic validated"
