# Building Roubao Autopilot

This guide explains how to build the Roubao Autopilot Android app.

## Prerequisites

- **Java Development Kit (JDK) 17** or newer
- **Android SDK** with:
  - Android API 34 (compile SDK)
  - Android API 26 or higher (minimum SDK)
- **Git** for version control
- **4GB+ RAM** recommended for building

## Quick Build Instructions

### Option 1: Using Android Studio (Recommended)

1. **Install Android Studio**
   - Download from [developer.android.com](https://developer.android.com/studio)
   - Install with default settings (includes Android SDK)

2. **Clone the repository**
   ```bash
   git clone https://github.com/YOUR_USERNAME/roubao.git
   cd roubao
   ```

3. **Open in Android Studio**
   - Launch Android Studio
   - Click "Open" and select the `roubao` folder
   - Wait for Gradle sync to complete (first time takes 5-10 minutes)

4. **Build the app**
   - **For testing**: Build → Build Bundle(s) / APK(s) → Build APK(s)
   - **For release**: Build → Generate Signed Bundle / APK

5. **Find your APK**
   - Debug APK: `app/build/outputs/apk/debug/app-debug.apk`
   - Release APK: `app/build/outputs/apk/release/app-release.apk`

### Option 2: Command Line Build

1. **Install JDK 17**
   ```bash
   # Ubuntu/Debian
   sudo apt install openjdk-17-jdk

   # macOS (using Homebrew)
   brew install openjdk@17

   # Windows: Download from adoptium.net
   ```

2. **Clone and build**
   ```bash
   git clone https://github.com/YOUR_USERNAME/roubao.git
   cd roubao
   chmod +x gradlew
   ./gradlew assembleDebug
   ```

3. **For release build**
   ```bash
   ./gradlew assembleRelease
   ```

4. **APK location**
   - `app/build/outputs/apk/debug/app-debug.apk`
   - `app/build/outputs/apk/release/app-release-unsigned.apk`

## Automated Builds (GitHub Actions)

This repository includes automated builds via GitHub Actions.

### Getting Pre-built APKs

1. **From Actions tab** (after each commit)
   - Go to your repo → Actions tab
   - Click on the latest "Build Android APK" workflow
   - Download artifacts: `roubao-debug-apk` or `roubao-release-apk`

2. **From Releases** (for tagged versions)
   - Go to your repo → Releases
   - Download the APK from any release

### Creating a New Release

To trigger a release build with attached APKs:

```bash
# Tag the current version
git tag -a v1.4.3 -m "Release version 1.4.3 - Goal completion fix"

# Push the tag
git push origin v1.4.3
```

This will:
- Build both debug and release APKs
- Create a GitHub Release
- Attach APKs to the release
- Generate release notes automatically

## Troubleshooting

### Build fails with "SDK not found"

**Solution**: Set `ANDROID_HOME` environment variable

```bash
# Linux/macOS
export ANDROID_HOME=$HOME/Android/Sdk
export PATH=$PATH:$ANDROID_HOME/tools:$ANDROID_HOME/platform-tools

# Windows
set ANDROID_HOME=C:\Users\YourName\AppData\Local\Android\Sdk
```

### Gradle sync fails

**Solution**: Clear Gradle cache

```bash
./gradlew clean
rm -rf ~/.gradle/caches/
./gradlew assembleDebug --refresh-dependencies
```

### Out of memory during build

**Solution**: Increase Gradle memory in `gradle.properties`

```properties
org.gradle.jvmargs=-Xmx4096m -XX:MaxMetaspaceSize=512m
```

### Build succeeds but APK is unsigned (release)

**Solution**: Sign the APK manually

```bash
# Generate keystore (first time only)
keytool -genkey -v -keystore my-release-key.jks -keyalg RSA -keysize 2048 -validity 10000 -alias my-key-alias

# Sign APK
jarsigner -verbose -sigalg SHA256withRSA -digestalg SHA-256 \
  -keystore my-release-key.jks \
  app/build/outputs/apk/release/app-release-unsigned.apk \
  my-key-alias

# Align APK (optimize)
zipalign -v 4 app/build/outputs/apk/release/app-release-unsigned.apk roubao-release-signed.apk
```

## APK Signing for Distribution

For distributing release builds:

1. **Create a signing key** (once)
   ```bash
   keytool -genkey -v -keystore roubao-release.jks -keyalg RSA -keysize 2048 -validity 10000 -alias roubao
   ```

2. **Configure signing in `app/build.gradle.kts`**
   ```kotlin
   android {
       signingConfigs {
           create("release") {
               storeFile = file("../roubao-release.jks")
               storePassword = "your-store-password"
               keyAlias = "roubao"
               keyPassword = "your-key-password"
           }
       }
       buildTypes {
           release {
               signingConfig = signingConfigs.getByName("release")
               isMinifyEnabled = true
               proguardFiles(...)
           }
       }
   }
   ```

3. **Build signed release**
   ```bash
   ./gradlew assembleRelease
   ```

**Security Note**: Never commit your keystore or passwords to the repository! Use environment variables or GitHub Secrets for CI/CD.

## Next Steps

- [Installing the APK](INSTALL.md) - How to install on Android devices
- [Making the repo public](CONTRIBUTING.md#making-repository-public) - Share with the community
- [Contributing](CONTRIBUTING.md) - How to contribute to the project

## Version Information

Current version: **1.4.2**
Latest improvements:
- Enhanced goal completion detection (v1.4.3-dev)
- English translation complete
- Gemini API support
- Improved task completion logic
