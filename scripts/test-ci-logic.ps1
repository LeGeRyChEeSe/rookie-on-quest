# PowerShell script to validate CI logic (Lint summary generation and feedback preparation)

$TmpEnv = [System.IO.Path]::GetTempFileName()
$LINT_REPORT_DIR = "app/build/reports"
New-Item -ItemType Directory -Force -Path $LINT_REPORT_DIR | Out-Null
$LINT_XML = Join-Path $LINT_REPORT_DIR "lint-results-debug.xml"

Write-Host "Running CI Logic Validation..."

# Test Case 1: Lint XML exists with errors
$LintContent = @"
<?xml version="1.0" encoding="UTF-8"?>
<issues format="6" by="lint 8.2.2">
    <issue id="HardcodedText" severity="Error" message="Hardcoded string" category="Internationalization" priority="5" summary="Hardcoded text" explanation="Hardcoded text should use @string resources">
        <location file="app/src/main/res/layout/activity_main.xml" line="10" column="26"/>
    </issue>
    <issue id="UnusedResources" severity="Warning" message="The resource 'R.string.unused' appears to be unused" category="Performance" priority="3" summary="Unused resources" explanation="Unused resources make the app larger">
        <location file="app/src/main/res/values/strings.xml" line="5" column="13"/>
    </issue>
</issues>
"@
$LintContent | Out-File -FilePath $LINT_XML -Encoding UTF8

# Simulate Generate Lint Summary step
if (Test-Path $LINT_XML) {
    [xml]$Xml = Get-Content $LINT_XML
    $Issues = @($Xml.issues.issue)
    Write-Host "Debug: Total issues found: $($Issues.Count)"
    $Errors = ($Issues | Where-Object { $_.severity -eq "Error" }).Count
    $Warnings = ($Issues | Where-Object { $_.severity -eq "Warning" }).Count
    
    # Handle PowerShell's behavior of returning null for count of single item if not properly handled
    if ($null -eq $Errors) { 
        if ($Issues.Count -gt 0 -and ($Issues | Where-Object { $_.severity -eq "Error" })) { $Errors = 1 } else { $Errors = 0 }
    }
    if ($null -eq $Warnings) {
        if ($Issues.Count -gt 0 -and ($Issues | Where-Object { $_.severity -eq "Warning" })) { $Warnings = 1 } else { $Warnings = 0 }
    }

    Write-Host "Debug: Errors: $Errors, Warnings: $Warnings"
    
    Add-Content -Path $TmpEnv -Value "LINT_ERRORS=$Errors"
    Add-Content -Path $TmpEnv -Value "LINT_WARNINGS=$Warnings"
    $Status = if ($Errors -gt 0) { "❌ Fail" } else { "✅ Pass" }
    Add-Content -Path $TmpEnv -Value "LINT_STATUS=$Status"
}

# Validate output
$EnvContent = Get-Content $TmpEnv
if (($EnvContent -contains "LINT_ERRORS=1") -and ($EnvContent -contains "LINT_WARNINGS=1") -and ($EnvContent -contains "LINT_STATUS=❌ Fail")) {
    Write-Host "✅ Lint Summary Logic Validated"
} else {
    Write-Host "❌ Failed: Env content incorrect"
    $EnvContent | ForEach-Object { Write-Host $_ }
    Exit 1
}

# Test Case 2: Duration calculation
$StartTime = (Get-Date).AddMinutes(-5)
$EndTime = Get-Date
$Duration = [int]($EndTime - $StartTime).TotalSeconds
Add-Content -Path $TmpEnv -Value "BUILD_DURATION_SECONDS=$Duration"

if ($Duration -ge 300 -and $Duration -le 305) {
    Write-Host "✅ Duration Logic Validated"
} else {
    Write-Host "❌ Duration calculation failed: $Duration"
    Exit 1
}

Remove-Item $TmpEnv
Write-Host "All CI logic tests passed!"
