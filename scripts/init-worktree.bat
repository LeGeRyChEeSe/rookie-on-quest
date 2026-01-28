@echo off
REM Universal worktree initialization script (Windows)
REM Compatible with any AI agent or human developer
REM Usage: scripts\init-worktree.bat <story-id> [agent-name]

setlocal enabledelayedexpansion

REM Parse arguments
set "STORY_ID=%~1"
set "AGENT_NAME=%~2"
if "%AGENT_NAME%"=="" set "AGENT_NAME=agent"

if "%STORY_ID%"=="" (
    echo [ERROR] STORY_ID is required
    echo Usage: %~nx0 ^<story-id^> [agent-name]
    echo Example: %~nx0 1-8 dev
    exit /b 1
)

REM Get project root (assumes script is in project-root\scripts\)
set "PROJECT_ROOT=%~dp0.."
set "WORKTREE_DIR=%PROJECT_ROOT%\worktrees\%AGENT_NAME%-story-%STORY_ID%"
set "BRANCH_NAME=%AGENT_NAME%-story-%STORY_ID%"

echo === Git Worktree Initialization for Story %STORY_ID% ===
echo Project Root: %PROJECT_ROOT%
echo Worktree Path: %WORKTREE_DIR%
echo Branch Name: %BRANCH_NAME%
echo.

REM Step 1: Check if worktree already exists
if exist "%WORKTREE_DIR%" (
    echo [WARNING] Worktree already exists at %WORKTREE_DIR%
    echo Options:
    echo   1. Use existing worktree (cd %WORKTREE_DIR%)
    echo   2. Remove and recreate
    echo.
    set /p USE_EXISTING="Use existing worktree? (y/n): "
    if /i "!USE_EXISTENT!"=="y" (
        echo [OK] Using existing worktree
        echo Worktree location: %WORKTREE_DIR%
        type "%WORKTREE_DIR%\.story-id" 2>nul || echo Story ID: Not found
        exit /b 0
    ) else (
        echo [ABORTED] Please remove existing worktree first
        exit /b 1
    )
)

REM Step 2: Create worktree and branch
echo [INFO] Creating git worktree...
cd /d "%PROJECT_ROOT%"
git worktree add "%WORKTREE_DIR%" -b "%BRANCH_NAME%"

if errorlevel 1 (
    echo [ERROR] Failed to create worktree
    echo Possible causes:
    echo   - Branch '%BRANCH_NAME%' already exists
    echo   - Worktree path already exists
    echo.
    echo Fix:
    echo   git worktree remove %BRANCH_NAME%
    echo   git branch -D %BRANCH_NAME%
    exit /b 1
)

echo [OK] Worktree created successfully

REM Step 3: Create .story-id file in worktree
echo [INFO] Creating .story-id file...
echo %STORY_ID%> "%WORKTREE_DIR%\.story-id"
echo [OK] Story ID set to: %STORY_ID%

REM Step 4: Create .story-files manifest
echo [INFO] Creating .story-files manifest...
(
echo # Story Files Manifest
echo # This file tracks which files are modified by this story to prevent conflicts
echo storyId: "%STORY_ID%"
echo storyTitle: "Auto-generated - update from story file"
echo storyFiles:
echo   # Add file patterns here as you work (e.g., app/src/main/java/**/*.kt)
echo modifiedDuringStory: []
) > "%WORKTREE_DIR%\.story-files"
echo [OK] .story-files manifest created

REM Step 5: Verify setup
echo.
echo === Setup Complete ===
echo.
echo 📍 Worktree Location: %WORKTREE_DIR%
echo 🌿 Branch Name: %BRANCH_NAME%
echo 🆔 Story ID: %STORY_ID%
echo.
echo Next Steps for ANY Agent:
echo   1. Navigate to worktree: cd /d "%WORKTREE_DIR%"
echo   2. Verify story ID: type .story-id
echo   3. Start development (agent will auto-detect story from .story-id)
echo.
echo For Developers:
echo   - Open IDE/Editor at: %WORKTREE_DIR%
echo   - Update .story-files as you modify files
echo   - When done: push branch and create PR
echo.
echo Ready for development!
endlocal
