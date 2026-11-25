package com.example.mylauncher

import android.app.Activity
import android.app.WallpaperManager
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private lateinit var webView: WebView

    private val worker = Executors.newSingleThreadExecutor()

    private val startUrl = "https://my-luncher.base44.app/"
    private val originHost = "my-luncher.base44.app"

    private val wallpaperRequestCode = 4101
    private val homeRoleRequestCode = 4102

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this)
        setContentView(webView)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = true
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            cacheMode = WebSettings.LOAD_DEFAULT
        }

        webView.webChromeClient = WebChromeClient()

        webView.addJavascriptInterface(
            AndroidBridge(),
            "AndroidLauncherNative"
        )

        webView.webViewClient = object : WebViewClient() {

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {

                val uri = request?.url ?: return true

                if (
                    uri.scheme == "https" &&
                    uri.host == originHost
                ) {
                    return false
                }

                if (
                    uri.scheme == "https" ||
                    uri.scheme == "http"
                ) {
                    try {
                        startActivity(
                            Intent(Intent.ACTION_VIEW, uri)
                        )
                    } catch (_: Exception) {
                    }
                }

                return true
            }

            override fun onPageFinished(
                view: WebView?,
                url: String?
            ) {
                super.onPageFinished(view, url)

                if (Uri.parse(url ?: "").host == originHost) {
                    injectBridgeAdapter()
                }
            }
        }

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState)
        } else {
            webView.loadUrl(startUrl)
        }
    }

    // --------------------------------------------------
    // JAVASCRIPT BRIDGE
    // --------------------------------------------------

    private fun injectBridgeAdapter() {

        val javascript = """
            (function() {

                if (window.__myLauncherBridgeInstalled) return;

                window.__myLauncherBridgeInstalled = true;

                var nativeBridge = window.AndroidLauncherNative;

                var pending =
                    window.__nativeBridgePending ||
                    {};

                window.__nativeBridgePending = pending;

                function request(method, payload) {

                    return new Promise(function(resolve, reject) {

                        if (
                            !nativeBridge ||
                            typeof nativeBridge.postMessage !== 'function'
                        ) {
                            reject(new Error('NATIVE_BRIDGE_UNAVAILABLE'));
                            return;
                        }

                        var id =
                            'ml_' +
                            Date.now() +
                            '_' +
                            Math.random().toString(36).slice(2);

                        var timeout = setTimeout(function() {

                            delete pending[id];

                            reject(new Error('NATIVE_TIMEOUT'));

                        }, 20000);

                        pending[id] = {
                            resolve: resolve,
                            reject: reject,
                            timeout: timeout
                        };

                        try {

                            nativeBridge.postMessage(
                                JSON.stringify({
                                    type: 'request',
                                    id: id,
                                    method: method,
                                    payload: payload || {}
                                })
                            );

                        } catch (error) {

                            clearTimeout(timeout);
                            delete pending[id];

                            reject(error);
                        }
                    });
                }

                window.__nativeBridgeResolve = function(raw) {

                    var response;

                    try {
                        response =
                            typeof raw === 'string'
                                ? JSON.parse(raw)
                                : raw;
                    } catch (error) {
                        return;
                    }

                    var item =
                        response &&
                        pending[response.id];

                    if (!item) return;

                    clearTimeout(item.timeout);

                    delete pending[response.id];

                    if (response.success) {

                        item.resolve(response.data || {});

                    } else {

                        var error = new Error(
                            response.error &&
                            response.error.message
                                ? response.error.message
                                : 'NATIVE_ERROR'
                        );

                        error.code =
                            response.error &&
                            response.error.code
                                ? response.error.code
                                : 'NATIVE_ERROR';

                        item.reject(error);
                    }
                };

                var api =
                    window.AndroidLauncher ||
                    {};

                window.AndroidLauncher = api;

                api.request = request;

                api.launchApp = function(value) {

                    var payload =
                        value && typeof value === 'object'
                            ? value
                            : { appName: String(value || '') };

                    return request('apps.launch', payload);
                };

                api.getInstalledApps = function() {
                    return request('apps.getInstalled', {});
                };

                api.requestDefaultLauncher = function() {
                    return request('launcher.requestDefault', {});
                };

                api.pickWallpaper = function() {
                    return request('wallpaper.pickImage', {});
                };

                api.openWifiSettings = function() {
                    return request('system.openWifiSettings', {});
                };

                api.openAirplaneSettings = function() {
                    return request('system.openAirplaneSettings', {});
                };

                api.openAppInfo = function(value) {

                    var payload =
                        value && typeof value === 'object'
                            ? value
                            : { packageName: String(value || '') };

                    return request('apps.openAppInfo', payload);
                };

                api.getDeviceInfo = function() {
                    return request('device.getInfo', {});
                };

                window.dispatchEvent(
                    new CustomEvent('android-bridge-ready', {
                        detail: { version: 2 }
                    })
                );

                window.dispatchEvent(
                    new CustomEvent('bridge-ready', {
                        detail: { version: 2 }
                    })
                );

            })();
        """.trimIndent()

        webView.evaluateJavascript(javascript, null)
    }

    // --------------------------------------------------
    // RESPONSE HANDLING
    // --------------------------------------------------

    private fun sendResponse(
        id: String,
        success: Boolean,
        data: JSONObject = JSONObject(),
        code: String = "NATIVE_ERROR",
        message: String = "Native operation failed"
    ) {

        if (id.isBlank()) return

        val response = JSONObject()
            .put("type", "response")
            .put("id", id)
            .put("success", success)

        if (success) {
            response.put("data", data)
        } else {
            response.put(
                "error",
                JSONObject()
                    .put("code", code)
                    .put("message", message)
            )
        }

        val encoded = JSONObject.quote(response.toString())

        runOnUiThread {

            if (::webView.isInitialized) {

                webView.evaluateJavascript(
                    "window.__nativeBridgeResolve && " +
                    "window.__nativeBridgeResolve($encoded);",
                    null
                )
            }
        }
    }

    private fun emitEvent(
        name: String,
        data: JSONObject
    ) {

        val eventName = JSONObject.quote(name)
        val eventData = JSONObject.quote(data.toString())

        runOnUiThread {

            if (::webView.isInitialized) {

                webView.evaluateJavascript(
                    "window.dispatchEvent(" +
                    "new CustomEvent($eventName," +
                    "{detail:JSON.parse($eventData)}));",
                    null
                )
            }
        }
    }

    // --------------------------------------------------
    // NATIVE BRIDGE METHODS
    // --------------------------------------------------

    inner class AndroidBridge {

        @JavascriptInterface
        fun postMessage(raw: String) {

            val request = try {
                JSONObject(raw)
            } catch (_: Exception) {

                emitEvent(
                    "android-bridge-error",
                    JSONObject()
                        .put("code", "INVALID_JSON")
                        .put("message", "Invalid JSON request")
                )

                return
            }

            val id = request.optString("id")
            val method = request.optString("method")

            val payload =
                request.optJSONObject("payload")
                    ?: JSONObject()

            if (method.isBlank()) {

                sendResponse(
                    id,
                    false,
                    code = "MISSING_METHOD",
                    message = "Request method is required"
                )

                return
            }

            when (method) {

                "apps.getInstalled" -> {

                    worker.execute {

                        try {

                            sendResponse(
                                id,
                                true,
                                getInstalledAppsNative()
                            )

                        } catch (e: Exception) {

                            sendResponse(
                                id,
                                false,
                                code = "APPS_QUERY_FAILED",
                                message = e.message ?: "Could not retrieve apps"
                            )
                        }
                    }
                }

                "device.getInfo" -> {

                    sendResponse(
                        id,
                        true,
                        getDeviceInfo()
                    )
                }

                "bridge.getCapabilities" -> {

                    sendResponse(
                        id,
                        true,
                        JSONObject()
                            .put("version", 2)
                            .put(
                                "methods",
                                JSONArray(
                                    listOf(
                                        "apps.getInstalled",
                                        "apps.launch",
                                        "apps.openAppInfo",
                                        "launcher.requestDefault",
                                        "wallpaper.pickImage",
                                        "system.openWifiSettings",
                                        "system.openAirplaneSettings",
                                        "device.getInfo"
                                    )
                                )
                            )
                    )
                }

                "apps.launch" -> {

                    runOnUiThread {

                        try {

                            sendResponse(
                                id,
                                true,
                                launchAppNative(
                                    extractAppIdentifier(payload)
                                )
                            )

                        } catch (e: Exception) {

                            sendResponse(
                                id,
                                false,
                                code = errorCode(e),
                                message = e.message ?: "App launch failed"
                            )
                        }
                    }
                }

                "apps.openAppInfo" -> {

                    runOnUiThread {

                        try {

                            sendResponse(
                                id,
                                true,
                                openAppInfo(
                                    extractAppIdentifier(payload)
                                )
                            )

                        } catch (e: Exception) {

                            sendResponse(
                                id,
                                false,
                                code = errorCode(e),
                                message = e.message ?: "Could not open app information"
                            )
                        }
                    }
                }

                "launcher.requestDefault",
                "launcher.setDefault",
                "system.setDefaultLauncher",
                "apps.setDefaultLauncher" -> {

                    runOnUiThread {

                        try {

                            if (isDefaultLauncher()) {

                                sendResponse(
                                    id,
                                    true,
                                    JSONObject()
                                        .put("isDefault", true)
                                        .put("alreadyDefault", true)
                                )

                            } else {

                                sendResponse(
                                    id,
                                    true,
                                    JSONObject()
                                        .put("accepted", true)
                                        .put("requiresUserConfirmation", true)
                                )

                                requestDefaultLauncherNative()
                            }

                        } catch (e: Exception) {

                            sendResponse(
                                id,
                                false,
                                code = "HOME_ROLE_FAILED",
                                message = e.message ?: "Home role request failed"
                            )
                        }
                    }
                }

                "wallpaper.pickImage" -> {

                    runOnUiThread {

                        try {

                            sendResponse(
                                id,
                                true,
                                JSONObject()
                                    .put("accepted", true)
                                    .put("resultEvent", "wallpaper-result")
                            )

                            pickWallpaperNative()

                        } catch (e: Exception) {

                            sendResponse(
                                id,
                                false,
                                code = "WALLPAPER_PICKER_FAILED",
                                message = e.message ?: "Wallpaper picker failed"
                            )
                        }
                    }
                }

                "system.openWifiSettings" -> {

                    runOnUiThread {

                        try {

                            openWifiSettings()

                            sendResponse(
                                id,
                                true,
                                JSONObject().put("opened", true)
                            )

                        } catch (e: Exception) {

                            sendResponse(
                                id,
                                false,
                                code = "SETTINGS_UNAVAILABLE",
                                message = e.message ?: "Wi-Fi settings unavailable"
                            )
                        }
                    }
                }

                "system.openAirplaneSettings" -> {

                    runOnUiThread {

                        try {

                            startActivity(
                                Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS)
                            )

                            sendResponse(
                                id,
                                true,
                                JSONObject().put("opened", true)
                            )

                        } catch (e: Exception) {

                            sendResponse(
                                id,
                                false,
                                code = "SETTINGS_UNAVAILABLE",
                                message = e.message ?: "Settings unavailable"
                            )
                        }
                    }
                }

                else -> {

                    sendResponse(
                        id,
                        false,
                        code = "METHOD_NOT_SUPPORTED",
                        message = "Unsupported method: $method"
                    )
                }
            }
        }

        // Legacy JavaScript compatibility methods

        @JavascriptInterface
        fun launchApp(value: String): String {
            dispatchDirect("apps.launch", value)
            return JSONObject().put("accepted", true).toString()
        }

        @JavascriptInterface
        fun getInstalledApps(): String {

            worker.execute {

                try {

                    emitEvent(
                        "installed-apps-result",
                        getInstalledAppsNative()
                    )

                } catch (e: Exception) {

                    emitEvent(
                        "installed-apps-error",
                        JSONObject().put("message", e.message)
                    )
                }
            }

            return JSONObject().put("accepted", true).toString()
        }

        @JavascriptInterface
        fun requestDefaultLauncher(): String {
            dispatchDirect("launcher.requestDefault", "")
            return JSONObject().put("accepted", true).toString()
        }

        @JavascriptInterface
        fun pickWallpaper(): String {
            dispatchDirect("wallpaper.pickImage", "")
            return JSONObject().put("accepted", true).toString()
        }

        @JavascriptInterface
        fun openWifiSettings(): String {
            dispatchDirect("system.openWifiSettings", "")
            return JSONObject().put("accepted", true).toString()
        }

        @JavascriptInterface
        fun openAirplaneSettings(): String {
            dispatchDirect("system.openAirplaneSettings", "")
            return JSONObject().put("accepted", true).toString()
        }

        @JavascriptInterface
        fun openAppInfo(value: String): String {
            dispatchDirect("apps.openAppInfo", value)
            return JSONObject().put("accepted", true).toString()
        }

        @JavascriptInterface
        fun getDeviceInfo(): String {
            return this@MainActivity.getDeviceInfo().toString()
        }

        private fun dispatchDirect(
            method: String,
            value: String
        ) {

            runOnUiThread {

                try {

                    when (method) {

                        "apps.launch" -> launchAppNative(value)

                        "apps.openAppInfo" -> openAppInfo(value)

                        "launcher.requestDefault" ->
                            requestDefaultLauncherNative()

                        "wallpaper.pickImage" ->
                            pickWallpaperNative()

                        "system.openWifiSettings" ->
                            openWifiSettings()

                        "system.openAirplaneSettings" ->
                            startActivity(
                                Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS)
                            )
                    }

                } catch (e: Exception) {

                    emitEvent(
                        "android-bridge-error",
                        JSONObject()
                            .put("code", errorCode(e))
                            .put("message", e.message)
                    )
                }
            }
        }
    }

    // --------------------------------------------------
    // DEVICE INFORMATION
    // --------------------------------------------------

    private fun getDeviceInfo(): JSONObject {

        return JSONObject()
            .put("manufacturer", Build.MANUFACTURER)
            .put("brand", Build.BRAND)
            .put("model", Build.MODEL)
            .put("device", Build.DEVICE)
            .put("androidVersion", Build.VERSION.RELEASE)
            .put("sdk", Build.VERSION.SDK_INT)
    }

    // --------------------------------------------------
    // APP NAME AND PACKAGE RESOLUTION
    // --------------------------------------------------

    private fun extractAppIdentifier(
        payload: JSONObject
    ): String {

        val keys = arrayOf(
            "packageName",
            "package",
            "appPackage",
            "appName",
            "name",
            "label"
        )

        for (key in keys) {

            val value = payload.optString(key, "")

            if (
                value.isNotBlank() &&
                value != "null"
            ) {
                return value.trim()
            }
        }

        return ""
    }

    private fun resolvePackageName(value: String): String {

        val raw = value.trim()

        if (raw.isEmpty()) return ""

        if (raw.contains('.') && !raw.contains(' ')) {
            return raw
        }

        val normalized = raw
            .lowercase()
            .replace(Regex("[^a-z0-9]"), "")

        return when (normalized) {

            "playstore",
            "googleplay",
            "googleplaystore" ->
                "com.android.vending"

            "gmail",
            "googlemail" ->
                "com.google.android.gm"

            "google",
            "googlesearch" ->
                "com.google.android.googlequicksearchbox"

            "facebook" ->
                "com.facebook.katana"

            "instagram" ->
                "com.instagram.android"

            "telegram" ->
                "org.telegram.messenger"

            "youtube" ->
                "com.google.android.youtube"

            "chrome",
            "googlechrome" ->
                "com.android.chrome"

            "googlemaps",
            "googlemapsnavigation",
            "maps",
            "navigation" ->
                "com.google.android.apps.maps"

            "youtubemusic",
            "ytmusic",
            "music" ->
                "com.google.android.apps.youtube.music"

            else -> raw
        }
    }

    // --------------------------------------------------
    // APP LAUNCHING
    // --------------------------------------------------

    private fun launchAppNative(value: String): JSONObject {

        val packageName = resolvePackageName(value)

        if (packageName.isBlank()) {

            throw IllegalArgumentException(
                "PACKAGE_NOT_SET: App name or package name required"
            )
        }

        val launchIntent =
            packageManager.getLaunchIntentForPackage(packageName)
                ?: throw IllegalArgumentException(
                    "APP_NOT_FOUND: $packageName"
                )

        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        startActivity(launchIntent)

        return JSONObject()
            .put("launched", true)
            .put("packageName", packageName)
    }

    private fun errorCode(e: Exception): String {

        val message = e.message ?: ""

        return when {

            message.startsWith("APP_NOT_FOUND") ->
                "APP_NOT_FOUND"

            message.startsWith("PACKAGE_NOT_SET") ->
                "PACKAGE_NOT_SET"

            message.startsWith("INVALID_PACKAGE") ->
                "INVALID_PACKAGE"

            else ->
                "NATIVE_OPERATION_FAILED"
        }
    }

    // --------------------------------------------------
    // INSTALLED APPLICATIONS
    // --------------------------------------------------

    private fun getInstalledAppsNative(): JSONObject {

        val apps = JSONArray()

        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)

        val resolved =
            packageManager.queryIntentActivities(intent, 0)

        val seen = HashSet<String>()

        for (info in resolved) {

            val activityInfo = info.activityInfo ?: continue

            val packageName = activityInfo.packageName

            if (!seen.add(packageName)) continue

            val iconData = try {

                val drawable = info.loadIcon(packageManager)

                val bitmap = Bitmap.createBitmap(
                    96,
                    96,
                    Bitmap.Config.ARGB_8888
                )

                val canvas = android.graphics.Canvas(bitmap)

                drawable.setBounds(
                    0,
                    0,
                    canvas.width,
                    canvas.height
                )

                drawable.draw(canvas)

                val output = ByteArrayOutputStream()

                bitmap.compress(
                    Bitmap.CompressFormat.PNG,
                    90,
                    output
                )

                "data:image/png;base64," +
                    Base64.encodeToString(
                        output.toByteArray(),
                        Base64.NO_WRAP
                    )

            } catch (_: Exception) {
                ""
            }

            apps.put(
                JSONObject()
                    .put("label", info.loadLabel(packageManager).toString())
                    .put("packageName", packageName)
                    .put("activityName", activityInfo.name)
                    .put("iconDataUrl", iconData)
                    .put("launchable", true)
            )
        }

        return JSONObject()
            .put("apps", apps)
            .put("count", apps.length())
    }

    // --------------------------------------------------
    // DEFAULT LAUNCHER
    // --------------------------------------------------

    private fun isDefaultLauncher(): Boolean {

        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_HOME)

        val resolved = packageManager.resolveActivity(
            intent,
            PackageManager.MATCH_DEFAULT_ONLY
        ) ?: return false

        return resolved.activityInfo?.packageName == packageName
    }

    private fun requestDefaultLauncherNative() {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {

            val manager = getSystemService(RoleManager::class.java)

            if (
                manager != null &&
                manager.isRoleAvailable(RoleManager.ROLE_HOME)
            ) {

                if (manager.isRoleHeld(RoleManager.ROLE_HOME)) {

                    completeHomeRequest(true, true)

                } else {

                    startActivityForResult(
                        manager.createRequestRoleIntent(
                            RoleManager.ROLE_HOME
                        ),
                        homeRoleRequestCode
                    )
                }

                return
            }
        }

        val homeIntent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_HOME)

        startActivityForResult(
            Intent.createChooser(
                homeIntent,
                "Choose My Launcher as your home app"
            ),
            homeRoleRequestCode
        )
    }

    private fun completeHomeRequest(
        success: Boolean,
        already: Boolean = false
    ) {

        emitEvent(
            "default-launcher-result",
            JSONObject()
                .put("success", success)
                .put("isDefault", isDefaultLauncher())
                .put("alreadyDefault", already)
        )
    }

    // --------------------------------------------------
    // APP INFORMATION
    // --------------------------------------------------

    private fun openAppInfo(value: String): JSONObject {

        val packageName = resolvePackageName(value)

        if (packageName.isBlank()) {

            throw IllegalArgumentException(
                "INVALID_PACKAGE: Package name required"
            )
        }

        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:$packageName")
        )

        startActivity(intent)

        return JSONObject()
            .put("opened", true)
            .put("packageName", packageName)
    }

    // --------------------------------------------------
    // SYSTEM SETTINGS
    // --------------------------------------------------

    private fun openWifiSettings() {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {

            try {

                startActivity(
                    Intent(
                        "android.settings.panel.action.INTERNET_CONNECTIVITY"
                    )
                )

                return

            } catch (_: Exception) {
            }
        }

        startActivity(
            Intent(Settings.ACTION_WIFI_SETTINGS)
        )
    }

    // --------------------------------------------------
    // WALLPAPER
    // --------------------------------------------------

    private fun pickWallpaperNative() {

        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .setType("image/*")
            .addCategory(Intent.CATEGORY_OPENABLE)

        startActivityForResult(
            intent,
            wallpaperRequestCode
        )
    }

    // --------------------------------------------------
    // ACTIVITY RESULTS
    // --------------------------------------------------

    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {

        super.onActivityResult(
            requestCode,
            resultCode,
            data
        )

        if (requestCode == homeRoleRequestCode) {

            completeHomeRequest(
                resultCode == RESULT_OK || isDefaultLauncher()
            )

            return
        }

        if (requestCode == wallpaperRequestCode) {

            if (
                resultCode != RESULT_OK ||
                data?.data == null
            ) {

                emitEvent(
                    "wallpaper-result",
                    JSONObject()
                        .put("success", false)
                        .put("cancelled", true)
                )

                return
            }

            val uri = data.data ?: return

            worker.execute {

                try {

                    try {

                        contentResolver.takePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )

                    } catch (_: Exception) {
                    }

                    val bitmap = contentResolver
                        .openInputStream(uri)
                        ?.use {
                            BitmapFactory.decodeStream(it)
                        }
                        ?: throw IllegalStateException(
                            "Unable to read selected image"
                        )

                    WallpaperManager
                        .getInstance(this)
                        .setBitmap(bitmap)

                    bitmap.recycle()

                    emitEvent(
                        "wallpaper-result",
                        JSONObject()
                            .put("success", true)
                            .put("applied", true)
                            .put("uri", uri.toString())
                    )

                    emitEvent(
                        "wallpaper-changed",
                        JSONObject().put("success", true)
                    )

                    runOnUiThread {

                        Toast.makeText(
                            this,
                            "Wallpaper set successfully",
                            Toast.LENGTH_SHORT
                        ).show()
                    }

                } catch (e: Exception) {

                    emitEvent(
                        "wallpaper-result",
                        JSONObject()
                            .put("success", false)
                            .put(
                                "message",
                                e.message ?: "Could not set wallpaper"
                            )
                    )
                }
            }
        }
    }

    // --------------------------------------------------
    // BACK BUTTON
    // --------------------------------------------------

    @Suppress("DEPRECATION")
    override fun onBackPressed() {

        if (
            ::webView.isInitialized &&
            webView.canGoBack()
        ) {

            webView.goBack()

        } else {

            super.onBackPressed()
        }
    }

    // --------------------------------------------------
    // SAVE STATE
    // --------------------------------------------------

    override fun onSaveInstanceState(
        outState: Bundle
    ) {

        if (::webView.isInitialized) {
            webView.saveState(outState)
        }

        super.onSaveInstanceState(outState)
    }

    // --------------------------------------------------
    // CLEANUP
    // --------------------------------------------------

    override fun onDestroy() {

        if (::webView.isInitialized) {

            webView.removeJavascriptInterface(
                "AndroidLauncherNative"
            )

            webView.destroy()
        }

        worker.shutdownNow()

        super.onDestroy()
    }
}
