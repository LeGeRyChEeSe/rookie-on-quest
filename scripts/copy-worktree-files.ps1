# Worktree File Copy Script
# Reads .worktreeinclude and copies items to new worktree

param(
    [Parameter(Mandatory=$true)]
    [string]$ProjectRoot,
    [Parameter(Mandatory=$true)]
    [string]$WorktreeDir
)

$includeFile = Join-Path $ProjectRoot ".worktreeinclude"

if (-not (Test-Path $includeFile)) {
    Write-Host "[INFO] No .worktreeinclude file found - skipping custom file copy"
    exit 0
}

Write-Host "[INFO] Copying files from .worktreeinclude..."

# Read and parse .worktreeinclude (skip comments and empty lines)
$items = Get-Content $includeFile |
    Where-Object { $_ -notmatch '^#' -and $_.Trim() -ne '' } |
    ForEach-Object { $_.Trim() }

$copied = 0

foreach ($item in $items) {
    $sourcePath = Join-Path $ProjectRoot $item
    $destPath = Join-Path $WorktreeDir $item

    if (Test-Path $sourcePath) {
        Write-Host "  - Copying $item"

        # Handle files vs folders
        if (Test-Path $sourcePath -PathType Leaf) {
            # Create parent directory if needed
            $destDir = Split-Path $destPath -Parent
            if (-not (Test-Path $destDir)) {
                New-Item -ItemType Directory -Path $destDir -Force | Out-Null
            }
            Copy-Item $sourcePath $destPath -Force
        } else {
            # Copy folder recursively
            Copy-Item $sourcePath $destPath -Recurse -Force
        }
        $copied++
    }
}

if ($copied -gt 0) {
    Write-Host "[OK] Copied $copied item(s) from .worktreeinclude"
} else {
    Write-Host "[INFO] No matching items found in .worktreeinclude"
}
