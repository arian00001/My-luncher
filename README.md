# My Launcher Android source

Native Kotlin Android WebView launcher wrapper for https://my-luncher.base44.app/.
The website's React/Base44 source is hosted remotely and is not included or modified by this Android project.

## Native bridge

The WebView injects `window.AndroidLauncher` after the trusted website finishes loading and emits `android-bridge-ready` (also `bridge-ready`).

Promise API:
- `AndroidLauncher.launchApp("Play Store")` or `AndroidLauncher.launchApp({packageName:"com.android.vending"})`
- `AndroidLauncher.getInstalledApps()`
- `AndroidLauncher.requestDefaultLauncher()`
- `AndroidLauncher.pickWallpaper()`
- `AndroidLauncher.openWifiSettings()`
- `AndroidLauncher.openAirplaneSettings()`
- `AndroidLauncher.openAppInfo("com.android.vending")`
- `AndroidLauncher.getDeviceInfo()`
- `AndroidLauncher.request(method, payload)` for the request/response API

Request format:
`{"type":"request","id":"unique-id","method":"apps.launch","payload":{"appName":"Play Store"}}`

Response format:
`{"type":"response","id":"unique-id","success":true,"data":{...}}`
or `{"type":"response","id":"unique-id","success":false,"error":{"code":"APP_NOT_FOUND","message":"..."}}`.

Supported request methods: `bridge.getCapabilities`, `apps.getInstalled`, `apps.launch`, `apps.openAppInfo`, `launcher.requestDefault`, `launcher.setDefault`, `system.setDefaultLauncher`, `apps.setDefaultLauncher`, `wallpaper.pickImage`, `system.openWifiSettings`, `system.openAirplaneSettings`, `device.getInfo`.

Events:
- `android-bridge-ready` / `bridge-ready`
- `default-launcher-result` with `{success,isDefault,alreadyDefault?}`
- `wallpaper-result` with `{success,applied?,cancelled?,message?}`
- `wallpaper-changed` after successful wallpaper application
- `android-bridge-error` for legacy direct-bridge errors

The original JavaScript interface name `AndroidLauncherNative` is retained. Its direct compatibility methods are `postMessage`, `launchApp`, `getInstalledApps`, `requestDefaultLauncher`, `pickWallpaper`, `openWifiSettings`, `openAirplaneSettings`, `openAppInfo`, and `getDeviceInfo`.

App launch accepts package names or common labels including Play Store, Gmail, Google, Facebook, Instagram, Telegram, YouTube, Chrome, Google Maps/Navigation, and YouTube Music. Apps not installed return `APP_NOT_FOUND`. Installed-app enumeration returns label, packageName, activityName, iconDataUrl (PNG data URL when available), and launchable.

## Android behavior and limitations

- HOME selection uses Android's HOME role confirmation UI on Android 10+ and the system HOME chooser on older versions. The user must confirm the selection.
- Wallpaper uses the Android document picker and WallpaperManager. Picker cancellation is reported without treating it as a crash.
- Wi-Fi uses the Internet connectivity panel on Android 10+ with Wi-Fi settings fallback. Airplane Mode opens system settings; Android does not permit silently toggling it.
- App enumeration queries launcher activities through package visibility `<queries>` rather than requesting broad `QUERY_ALL_PACKAGES`.
- The app requires internet access to load the hosted website. This ZIP does not include or change the website's source code.
- This source has not been compiled on a device in this environment. GitHub Actions is configured to produce the debug APK; a successful workflow run is required to verify the actual Android build.

## Build with GitHub Actions

Push to `main` or `master`, or manually run **Build Android APK** in the repository's Actions tab. Download the `MyLauncher-debug-apk` artifact from the completed run and extract `app-debug.apk`.

Workflow toolchain: JDK 17, Gradle 8.11.1, Android SDK platform 35, build-tools 35.0.0.

## Push to GitHub

```bash
git init
git add .
git commit -m "Fix My Launcher Android bridge"
git branch -M main
git remote add origin https://github.com/YOUR_USERNAME/YOUR_REPOSITORY.git
git push -u origin main
```
