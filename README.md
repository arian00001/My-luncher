# My Launcher Android source

This is a **native Kotlin Android WebView launcher wrapper** for `https://my-luncher.base44.app/`.
It loads the website in a WebView; it does not bundle the website's React/Base44 source into the APK.

## What is included
- Android HOME/launcher activity
- Website loading and JavaScript bridge adapter
- Native bridge methods currently implemented in `MainActivity.kt`:
  - device info
  - list and launch installed apps
  - open app info
  - choose/set wallpaper
  - open Wi-Fi/internet panel and Airplane Mode settings

## Important limitations
- This is not a complete native launcher implementation. The website must provide its own UI and compatible bridge calls.
- The website requires internet unless its own PWA/service-worker caching supports offline startup. WebView cache alone does not guarantee offline use.
- Native home-screen widgets, notification shade expansion, direct Airplane Mode toggling, robust app-install/uninstall refresh, wallpaper crop/lock-screen selection, and complete native event push are not implemented.
- `QUERY_ALL_PACKAGES` is restricted by Google Play policy. This project is intended for personal/local sideloading unless Play policy eligibility is reviewed.
- The source has not been built or device-tested in this workspace. The GitHub Actions workflow is configured to build a debug APK; the first workflow run is the actual build verification.

## Build APK on GitHub
1. Create a GitHub repository and push this project to its `main` branch.
2. Open the repository's **Actions** tab.
3. Run **Build Android APK** (or push a commit to `main`).
4. Open the completed workflow run and download the `MyLauncher-debug-apk` artifact.
5. Extract the artifact ZIP to get `app-debug.apk`.

The workflow uses JDK 17, Gradle 8.11.1, Android SDK platform 35, and build-tools 35.0.0.

## Build locally
Open the project in Android Studio, sync Gradle, then choose **Build > Build APK(s)**.
