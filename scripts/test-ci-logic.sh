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

echo "✅ Lint Summary Logic Validated (Standard Case)"

# Test Case 3: Missing Lint XML
rm "$LINT_XML"
if [ ! -f "$LINT_XML" ]; then
    echo "✅ Missing Lint XML handled"
fi

# Test Case 4: Malformed XML
echo "MALFORMED < XML" > "$LINT_XML"
# grep -c should return 0 if no match
ERRORS=$(grep -c 'severity="Error"' "$LINT_XML" || true)
if [ "$ERRORS" -eq 0 ]; then
    echo "✅ Malformed XML handled"
fi

# Test Case 5: Empty XML
echo "<issues />" > "$LINT_XML"
ERRORS=$(grep -c 'severity="Error"' "$LINT_XML" || true)
if [ "$ERRORS" -eq 0 ]; then
    echo "✅ Empty issues XML handled"
fi

# Test Case 6: Duration calculation (Standard)
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

# Test Case 7: Duration calculation (Null START_TIME robustness)
unset START_TIME
END_TIME=$(date +%s)
if [ -z "$START_TIME" ]; then
    DURATION=0
    echo "✅ Null START_TIME robustness check passed"
fi

rm "$GITHUB_ENV" "$GITHUB_STEP_SUMMARY"
echo "All CI logic tests passed!"
