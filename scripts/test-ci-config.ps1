# PowerShell script to validate CI Configuration loading logic

$ConfigFile = ".github/ci-config.env"
if (-not (Test-Path $ConfigFile)) {
    Write-Error "Error: $ConfigFile not found"
    exit 1
}

# Load the file manually since 'source' doesn't exist in PS for .env files
$Config = Get-Content $ConfigFile | Where-Object { $_ -match "^[^#].*=" } | ConvertFrom-StringData

# Test loading of variables
if ($Config.BUILD_TARGET_SECONDS_PR -ne "300") {
    Write-Error "Error: BUILD_TARGET_SECONDS_PR not loaded correctly (Expected 300, got $($Config.BUILD_TARGET_SECONDS_PR))"
    exit 1
}

if ($Config.BUILD_TARGET_SECONDS_RELEASE -ne "600") {
    Write-Error "Error: BUILD_TARGET_SECONDS_RELEASE not loaded correctly"
    exit 1
}

if ($Config.TIMEOUT_INSTRUMENTED_TESTS -ne "10") {
    Write-Error "Error: TIMEOUT_INSTRUMENTED_TESTS not loaded correctly (Expected 10, got $($Config.TIMEOUT_INSTRUMENTED_TESTS))"
    exit 1
}

if ($Config.TIMEOUT_RELEASE_BUILD -ne "20") {
    Write-Error "Error: TIMEOUT_RELEASE_BUILD not loaded correctly (Expected 20, got $($Config.TIMEOUT_RELEASE_BUILD))"
    exit 1
}

Write-Host "✅ CI Configuration variables loaded correctly from $ConfigFile"

# Test the loading logic used in workflows (simulating bash logic)
$TempOutput = [System.IO.Path]::GetTempFileName()
try {
    # Simulating: source .github/ci-config.env && echo "target_pr=$BUILD_TARGET_SECONDS_PR" >> $GITHUB_OUTPUT
    $target_pr = $Config.BUILD_TARGET_SECONDS_PR
    $timeout_instr = $Config.TIMEOUT_INSTRUMENTED_TESTS
    
    Add-Content $TempOutput "target_pr=$target_pr"
    Add-Content $TempOutput "timeout_instr=$timeout_instr"

    $content = Get-Content $TempOutput
    if ($content -contains "target_pr=300" -and $content -contains "timeout_instr=10") {
        Write-Host "✅ CI Workflow loading logic validated"
    } else {
        Write-Error "❌ Failed to write correctly to mock GITHUB_OUTPUT"
        exit 1
    }
} finally {
    if (Test-Path $TempOutput) { Remove-Item $TempOutput }
}
