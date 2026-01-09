# Making the Repository Public & Creating Releases

This guide explains how to make your Roubao repository public and set up downloadable releases.

## Making the Repository Public

### Step 1: Prepare Your Repository

Before making the repo public, ensure:

- [ ] Remove any sensitive data (API keys, passwords, personal info)
- [ ] Add a proper LICENSE file (MIT, Apache 2.0, etc.)
- [ ] Update README.md with project description
- [ ] Add .gitignore for sensitive files
- [ ] Remove or secure any private credentials

### Step 2: Make Repository Public (GitHub)

1. **Navigate to your repository** on GitHub
   - Go to https://github.com/YOUR_USERNAME/roubao

2. **Open Settings**
   - Click the "Settings" tab (requires admin access)

3. **Scroll to Danger Zone**
   - Scroll down to the bottom of the settings page
   - Find the "Danger Zone" section

4. **Change Visibility**
   - Click "Change visibility"
   - Select "Make public"
   - Type the repository name to confirm
   - Click "I understand, make this repository public"

### Step 3: Verify Public Status

- Visit your repo URL in an incognito/private browser window
- You should be able to see the repository without logging in
- The repository will now appear in GitHub search results

## Setting Up Releases

### Automated Release Creation (Recommended)

The GitHub Actions workflow is already configured! To create a release:

1. **Update version number** in `app/build.gradle.kts`:
   ```kotlin
   versionCode = 8  // Increment by 1
   versionName = "1.4.3"  // New version
   ```

2. **Commit the changes**:
   ```bash
   git add app/build.gradle.kts
   git commit -m "Bump version to 1.4.3"
   git push origin claude/fix-goal-completion-90jSh
   ```

3. **Create and push a tag**:
   ```bash
   git tag -a v1.4.3 -m "Release v1.4.3 - Goal completion improvements"
   git push origin v1.4.3
   ```

4. **GitHub Actions will automatically**:
   - Build debug and release APKs
   - Create a GitHub Release
   - Upload APKs to the release
   - Generate release notes from commits

5. **Find your release**:
   - Go to https://github.com/YOUR_USERNAME/roubao/releases
   - Your release will be listed with downloadable APKs

### Manual Release Creation

If you prefer to create releases manually:

1. **Build the APK locally**:
   ```bash
   ./gradlew assembleRelease
   ```

2. **Create a release on GitHub**:
   - Go to your repo → Releases → "Draft a new release"
   - Click "Choose a tag" → Type `v1.4.3` → "Create new tag"
   - Fill in release title: "Roubao v1.4.3 - Goal Completion Fix"
   - Add release notes (see template below)
   - Drag and drop your APK file to attach it
   - Click "Publish release"

### Release Notes Template

```markdown
# Roubao Autopilot v1.4.3

## 🎯 What's New

### Goal Completion Fix
- Fixed issue where tasks would repeat after successful completion
- Enhanced detection of "Finished" status with comprehensive pattern matching
- System now properly stops after achieving goals (e.g., opening apps)

### Improvements
- More robust task completion logic
- Better handling of varied AI responses
- Prevents infinite retry loops on completed tasks

## 📥 Download

- **[roubao-release.apk](link)** - Production build (recommended)
- **[roubao-debug.apk](link)** - Debug build (for testing)

## 🔧 Installation

1. Download the APK file
2. Enable "Install from Unknown Sources" in Android settings
3. Open the APK file to install
4. Grant accessibility permissions when prompted

## 📋 Requirements

- Android 8.0 (API 26) or higher
- Accessibility service permissions
- OpenAI API key or compatible LLM endpoint

## 🐛 Bug Fixes

- Fixed goal completion detection not working with descriptive finish messages
- Resolved issue where "open Spotify" would retry after successful open
- Improved regex matching for completion status

## 🔗 Full Changelog

See all changes: [v1.4.2...v1.4.3](https://github.com/YOUR_USERNAME/roubao/compare/v1.4.2...v1.4.3)

---

**Previous version:** [v1.4.2](https://github.com/YOUR_USERNAME/roubao/releases/tag/v1.4.2)
```

## Adding a LICENSE

For open source distribution, add a license file:

### MIT License (Recommended - Permissive)

Create `LICENSE` file:

```
MIT License

Copyright (c) 2025 [Your Name]

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

## Publicizing Your Release

Once public and released:

1. **Update README.md** with installation badge:
   ```markdown
   [![Latest Release](https://img.shields.io/github/v/release/YOUR_USERNAME/roubao)](https://github.com/YOUR_USERNAME/roubao/releases/latest)
   [![Downloads](https://img.shields.io/github/downloads/YOUR_USERNAME/roubao/total)](https://github.com/YOUR_USERNAME/roubao/releases)
   ```

2. **Share on platforms**:
   - Reddit: r/androidapps, r/Android
   - XDA Developers forums
   - Android app directories
   - Your social media

3. **Add topics to repo** (for discoverability):
   - Settings → Topics
   - Add: `android`, `automation`, `ai`, `accessibility`, `llm`, `mobile-agent`

## Download Links for Users

After making public, users can download from:

- **Latest release**: `https://github.com/YOUR_USERNAME/roubao/releases/latest`
- **All releases**: `https://github.com/YOUR_USERNAME/roubao/releases`
- **Direct APK link**: Available after release is published

## Badges for README

Add these to make your repo more professional:

```markdown
![Build Status](https://github.com/YOUR_USERNAME/roubao/workflows/Build%20Android%20APK/badge.svg)
[![Latest Release](https://img.shields.io/github/v/release/YOUR_USERNAME/roubao)](https://github.com/YOUR_USERNAME/roubao/releases/latest)
[![License](https://img.shields.io/github/license/YOUR_USERNAME/roubao)](LICENSE)
[![Platform](https://img.shields.io/badge/platform-Android-green.svg)](https://www.android.com/)
[![API](https://img.shields.io/badge/API-26%2B-brightgreen.svg)](https://android-arsenal.com/api?level=26)
```

## Questions?

- Build issues? See [BUILD.md](BUILD.md)
- Installation help? See [README.md](README.md#installation)
- Contributing? See [CONTRIBUTING.md](CONTRIBUTING.md)
