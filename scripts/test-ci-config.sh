#!/bin/bash
# Script to validate CI Configuration loading logic

CONFIG_FILE=".github/ci-config.env"
if [ ! -f "$CONFIG_FILE" ]; then
    echo "❌ Error: $CONFIG_FILE not found"
    exit 1
fi

source "$CONFIG_FILE"

# Function to validate if a variable is a positive integer
validate_numeric() {
    local var_name=$1
    local var_value=$2
    if ! [[ "$var_value" =~ ^[0-9]+$ ]]; then
        echo "❌ Error: $var_name must be a positive integer (got '$var_value')"
        exit 1
    fi
    if [ "$var_value" -le 0 ]; then
        echo "❌ Error: $var_name must be greater than zero (got $var_value)"
        exit 1
    fi
}

# Test loading and numeric validity of variables
validate_numeric "BUILD_TARGET_SECONDS_PR" "$BUILD_TARGET_SECONDS_PR"
validate_numeric "BUILD_TARGET_SECONDS_RELEASE" "$BUILD_TARGET_SECONDS_RELEASE"
validate_numeric "TIMEOUT_INSTRUMENTED_TESTS" "$TIMEOUT_INSTRUMENTED_TESTS"
validate_numeric "TIMEOUT_RELEASE_BUILD" "$TIMEOUT_RELEASE_BUILD"

echo "✅ CI Configuration variables loaded and validated correctly from $CONFIG_FILE"

# Test the loading logic used in workflows
GITHUB_OUTPUT=$(mktemp)
(
  source "$CONFIG_FILE"
  echo "target_pr=$BUILD_TARGET_SECONDS_PR" >> "$GITHUB_OUTPUT"
  echo "timeout_instr=$TIMEOUT_INSTRUMENTED_TESTS" >> "$GITHUB_OUTPUT"
)

# Verify that the logic can write numeric values to GITHUB_OUTPUT
grep "target_pr=[0-9]\+" "$GITHUB_OUTPUT" > /dev/null || { echo "❌ Failed to write target_pr to GITHUB_OUTPUT"; exit 1; }
grep "timeout_instr=[0-9]\+" "$GITHUB_OUTPUT" > /dev/null || { echo "❌ Failed to write timeout_instr to GITHUB_OUTPUT"; exit 1; }

rm "$GITHUB_OUTPUT"
echo "✅ CI Workflow loading logic validated"
