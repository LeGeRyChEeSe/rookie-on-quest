#!/bin/bash
# Script to validate CI logic (Lint summary generation and feedback preparation)

# Mock environment
export GITHUB_ENV=$(mktemp)
export GITHUB_STEP_SUMMARY=$(mktemp)
LINT_REPORT_DIR="app/build/reports"
mkdir -p "$LINT_REPORT_DIR"
LINT_XML="$LINT_REPORT_DIR/lint-results-debug.xml"

echo "Running CI Logic Validation..."

# Test Case 1: Lint XML exists with errors
cat <<EOF > "$LINT_XML"
<?xml version="1.0" encoding="UTF-8"?>
<issues format="6" by="lint 8.2.2">
    <issue id="HardcodedText" severity="Error" message="Hardcoded string" category="Internationalization" priority="5" summary="Hardcoded text" explanation="Hardcoded text should use @string resources">
        <location file="app/src/main/res/layout/activity_main.xml" line="10" column="26"/>
    </issue>
    <issue id="UnusedResources" severity="Warning" message="The resource 'R.string.unused' appears to be unused" category="Performance" priority="3" summary="Unused resources" explanation="Unused resources make the app larger">
        <location file="app/src/main/res/values/strings.xml" line="5" column="13"/>
    </issue>
</issues>
EOF

# Simulate Generate Lint Summary step
if [ -f "$LINT_XML" ]; then
    ERRORS=$(grep -c 'severity="Error"' "$LINT_XML" || true)
    [ -z "$ERRORS" ] && ERRORS=0
    WARNINGS=$(grep -c 'severity="Warning"' "$LINT_XML" || true)
    [ -z "$WARNINGS" ] && WARNINGS=0
    echo "LINT_ERRORS=$ERRORS" >> $GITHUB_ENV
    echo "LINT_WARNINGS=$WARNINGS" >> $GITHUB_ENV
    echo "LINT_STATUS=$([ "$ERRORS" -eq 0 ] && echo "✅ Pass" || echo "❌ Fail")" >> $GITHUB_ENV
else
    echo "LINT_ERRORS=N/A" >> $GITHUB_ENV
    echo "LINT_STATUS=⚠️ Not Found" >> $GITHUB_ENV
fi

# Validate output
grep "LINT_ERRORS=1" "$GITHUB_ENV" || { echo "❌ Failed: LINT_ERRORS should be 1"; exit 1; }
grep "LINT_WARNINGS=1" "$GITHUB_ENV" || { echo "❌ Failed: LINT_WARNINGS should be 1"; exit 1; }
grep "LINT_STATUS=❌ Fail" "$GITHUB_ENV" || { echo "❌ Failed: LINT_STATUS should be Fail"; exit 1; }

echo "✅ Lint Summary Logic Validated"

# Test Case 2: Duration calculation
export START_TIME=$(date +%s -d "5 minutes ago")
END_TIME=$(date +%s)
DURATION=$((END_TIME - START_TIME))
echo "BUILD_DURATION_SECONDS=$DURATION" >> $GITHUB_ENV

# Verify duration is around 300s
if [ "$DURATION" -ge 300 ] && [ "$DURATION" -le 305 ]; then
    echo "✅ Duration Logic Validated"
else
    echo "❌ Duration calculation failed: $DURATION"
    exit 1
fi

rm "$GITHUB_ENV" "$GITHUB_STEP_SUMMARY"
echo "All CI logic tests passed!"
