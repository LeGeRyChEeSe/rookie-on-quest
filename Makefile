# Force CMD as the shell for Windows compatibility
SHELL := cmd.exe
.SHELLFLAGS := /c

# Variables - Use forward slashes for paths to avoid escape issues
APP_NAME := RookieOnQuest
DISPLAY_NAME := Rookie On Quest
BUILD_GRADLE := app/build.gradle.kts
CHANGELOG := CHANGELOG.md
DIST_DIR := dist

# Version extraction via PowerShell (targeted and robust)
VERSION := $(shell powershell -NoProfile -Command "(Get-Content $(BUILD_GRADLE) | Select-String 'versionName = \".*\"' | Select-Object -First 1).Line.Split([char]34)[1]")
DATE := $(shell powershell -NoProfile -Command "Get-Date -Format 'yyyy-MM-dd'")
APK_NAME := $(APP_NAME)-v$(VERSION).apk
APK_PATH := app/build/outputs/apk/release/$(APK_NAME)

# Git Variables (can be overridden: make commit GIT_MSG="My message")
GIT_MSG ?= Release v$(VERSION)

.PHONY: help clean build release install commit tag push sync gh-release set-version

help:
	@echo RookieOnQuest Makefile (Windows)
	@echo -----------------------
	@echo make set-version V=x.x.x - Update version and changelog
	@echo make build          - Generate debug APK
	@echo make release        - Generate release APK
	@echo make install        - Install on device via ADB
	@echo -----------------------
	@echo make sync           - git pull
	@echo make commit         - git add + commit (default msg: "Release vX.X.X")
	@echo make tag            - create git tag vX.X.X
	@echo make push           - git push + git push --tags
	@echo make gh-release     - publish release on GitHub (APK + notes in description)
	@echo -----------------------
	@echo make clean          - Clean project

clean:
	@if exist $(DIST_DIR) rd /s /q $(DIST_DIR)
	@cmd /c gradlew.bat clean

set-version:
	@powershell -NoProfile -Command "if ('$(V)' -eq '') { Write-Error 'Provide a version, e.g., make set-version V=2.1.2'; exit 1 }"
	@echo Updating version to $(V)...
	@powershell -NoProfile -Command "$$p='$(BUILD_GRADLE)'; $$c=Get-Content $$p -Raw; $$c=$$c -replace 'versionName = \".*\"', 'versionName = \"$(V)\"'; $$m=[regex]::Match($$c, 'versionCode = (\d+)'); if($$m.Success){ $$ov=$$m.Groups[1].Value; $$nv=[int]$$ov+1; $$c=$$c -replace 'versionCode = \d+', \"versionCode = $$nv\"; Write-Host 'versionCode updated to ' -NoNewline; Write-Host $$nv -ForegroundColor Green } $$c | Set-Content $$p -Encoding UTF8"
	@echo Updating $(CHANGELOG)...
	@powershell -NoProfile -Command "$$p='$(CHANGELOG)'; $$v='$(V)'; $$d='$(DATE)'; $$c=Get-Content $$p -Raw; if ($$c -notmatch '## \[' + [regex]::Escape($$v) + '\]') { $$new='## [' + $$v + '] - ' + $$d + \"`r`n`r`n### Added`r`n- `r`n`r`n\"; $$c = $$c -replace '(?s)(.*?Semantic Versioning.*?\r?\n\r?\n)', \"$$1$$new\"; $$c | Set-Content $$p -Encoding UTF8; Write-Host 'Changelog updated' -ForegroundColor Green } else { Write-Host 'Version already exists in changelog' -ForegroundColor Yellow }"

build:
	@cmd /c gradlew.bat assembleDebug

release:
	@echo Preparing release v$(VERSION)...
	@cmd /c gradlew.bat assembleRelease
	@if not exist $(DIST_DIR) mkdir $(DIST_DIR)
	@powershell -NoProfile -Command "if (Test-Path '$(APK_PATH)') { Copy-Item '$(APK_PATH)' '$(DIST_DIR)/$(APK_NAME)' -Force } else { Write-Error 'APK not found at $(APK_PATH)'; exit 1 }"
	@echo Extracting notes...
	@powershell -NoProfile -Command "$$v='$(VERSION)'; $$c=Get-Content $(CHANGELOG) -Raw; $$m=[regex]::Match($$c, '(?s)## \[' + [regex]::Escape($$v) + '\][^\r\n]*\r?\n\r?\n(.*?)(?=\r?\n## \[|$$)'); if($$m.Success){ $$m.Groups[1].Value.Trim() | Out-File -FilePath $(DIST_DIR)/temp_notes.md -Encoding UTF8 } else { Write-Warning 'Could not extract clean notes for v' + $$v }"
	@echo Release v$(VERSION) ready in $(DIST_DIR)

install:
	@powershell -NoProfile -Command "if (Test-Path '$(APK_PATH)') { adb install -r '$(APK_PATH)' } else { Write-Error 'APK not found. Run make release first.'; exit 1 }"

sync:
	git pull

commit:
	git add .
	git commit -m "$(GIT_MSG)"

tag:
	git tag -a v$(VERSION) -m "Release v$(VERSION)"

push:
	git push
	git push --tags

gh-release:
	@echo Creating GitHub release v$(VERSION)...
	@powershell -NoProfile -Command "if (Test-Path '$(DIST_DIR)/temp_notes.md') { gh release create v$(VERSION) '$(DIST_DIR)/$(APK_NAME)' --title '$(DISPLAY_NAME) v$(VERSION)' --notes-file '$(DIST_DIR)/temp_notes.md'; Write-Host 'Removing temporary notes...'; Remove-Item '$(DIST_DIR)/temp_notes.md' } else { Write-Error 'Notes missing. Run make release first.'; exit 1 }"
