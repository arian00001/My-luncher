package com.example.mylauncher

import android.app.Activity
import android.app.WallpaperManager
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
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

class MainActivity : Activity(), SensorEventListener {

    private lateinit var webView: WebView

    private val worker = Executors.newSingleThreadExecutor()

    // Smoothed tilt stream for the wallpaper and the web parallax layers.
    private var gravityX = 0f
    private var gravityY = 0f
    private var tiltBaseX: Float? = null
    private var tiltBaseY: Float? = null
    private var lastTiltAt = 0L
    private var lastTiltX = 2f
    private var lastTiltY = 2f

    private val startUrl = "https://my-luncher.base44.app/"
    private val originHost = "my-luncher.base44.app"

    private val wallpaperRequestCode = 4101
    private val homeRoleRequestCode = 4102

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this)
        setContentView(webView)

        applyImmersive(true)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = true
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            // Open from the on-device cache when there is no network, so the
            // launcher still starts offline instead of showing a blank screen.
            cacheMode = WebSettings.LOAD_CACHE_ELSE_NETWORK
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

        val sensors = getSystemService(SENSOR_SERVICE) as? SensorManager
        sensors?.let { manager ->
            manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { sensor ->
                manager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
            }
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

                function send(method, payload) {

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
                                    v: 2,
                                    id: id,
                                    requestId: id,
                                    method: method,
                                    payload: payload || {},
                                    params: payload || {}
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

                    var id =
                        response &&
                        (response.id || response.requestId);

                    if (
                        id &&
                        window.LauncherBridge &&
                        typeof window.LauncherBridge.resolve === 'function'
                    ) {
                        window.LauncherBridge.resolve(id, response);
                        return;
                    }

                    var item =
                        id &&
                        pending[id];

                    if (!item) return;

                    clearTimeout(item.timeout);

                    delete pending[id];

                    if (response.success) {

                        item.resolve(response.data || {});

                    } else {

                        var failure = new Error(
                            response.error &&
                            response.error.message
                                ? response.error.message
                                : 'NATIVE_ERROR'
                        );

                        failure.code =
                            response.error &&
                            response.error.code
                                ? response.error.code
                                : 'NATIVE_ERROR';

                        item.reject(failure);
                    }
                };

                var api =
                    window.AndroidLauncher ||
                    {};

                window.AndroidLauncher = api;

                // The website sends one JSON envelope string: forward it untouched.
                api.request = function(envelope) {

                    var raw =
                        typeof envelope === 'string'
                            ? envelope
                            : JSON.stringify(envelope || {});

                    if (
                        !nativeBridge ||
                        typeof nativeBridge.postMessage !== 'function'
                    ) {
                        throw new Error('NATIVE_BRIDGE_UNAVAILABLE');
                    }

                    nativeBridge.postMessage(raw);
                };

                api.launchApp = function(value) {

                    var payload =
                        value && typeof value === 'object'
                            ? value
                            : { appName: String(value || '') };

                    return send('apps.launch', payload);
                };

                api.getInstalledApps = function() {
                    return send('apps.getInstalled', {});
                };

                api.requestDefaultLauncher = function() {
                    return send('launcher.requestDefault', {});
                };

                api.pickWallpaper = function() {
                    return send('wallpaper.pickImage', {});
                };

                api.openWifiSettings = function() {
                    return send('system.openWifiSettings', {});
                };

                api.openAirplaneSettings = function() {
                    return send('system.openAirplaneSettings', {});
                };

                api.openAppInfo = function(value) {

                    var payload =
                        value && typeof value === 'object'
                            ? value
                            : { packageName: String(value || '') };

                    return send('apps.openAppInfo', payload);
                };

                api.getDeviceInfo = function() {
                    return send('device.getInfo', {});
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
                    "(function(){" +
                    "var n=$eventName,d=JSON.parse($eventData);" +
                    "try{if(window.LauncherEvents&&typeof window.LauncherEvents.emit==='function')" +
                    "{window.LauncherEvents.emit(n,d);}}catch(e){}" +
                    "try{window.dispatchEvent(new CustomEvent(n,{detail:d}));}catch(e){}});",
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
                        this@MainActivity.getDeviceInfo()
                    )
                }

                "system.setImmersive" -> {

                    val immersiveOn = payload.optBoolean("enabled", true)

                    runOnUiThread {

                        try {

                            applyImmersive(immersiveOn)

                            sendResponse(
                                id,
                                true,
                                JSONObject().put("enabled", immersiveOn)
                            )

                        } catch (e: Exception) {

                            sendResponse(
                                id,
                                false,
                                code = "IMMERSIVE_FAILED",
                                message = e.message ?: "Could not change the system bars"
                            )
                        }
                    }
                }

                "system.openUrl" -> {

                    runOnUiThread {

                        try {

                            sendResponse(
                                id,
                                true,
                                openUrlNative(
                                    payload.optString("url", "")
                                )
                            )

                        } catch (e: Exception) {

                            sendResponse(
                                id,
                                false,
                                code = "URL_FAILED",
                                message = e.message ?: "Could not open the link"
                            )
                        }
                    }
                }

                "launcher.openHomeSettings" -> {

                    runOnUiThread {

                        try {

                            sendResponse(
                                id,
                                true,
                                openHomeSettingsNative()
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

                "launcher.getStatus" -> {

                    sendResponse(
                        id,
                        true,
                        JSONObject().put("isDefault", isDefaultLauncher())
                    )
                }

                "settings.openDefaultLauncher" -> {

                    runOnUiThread {

                        try {

                            requestDefaultLauncherNative()

                            sendResponse(
                                id,
                                true,
                                JSONObject().put("requested", true)
                            )

                        } catch (e: Exception) {

                            sendResponse(
                                id,
                                false,
                                code = "LAUNCHER_UNAVAILABLE",
                                message = e.message ?: "Could not open the default launcher dialog"
                            )
                        }
                    }
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
                                        "wallpaper.setParallax",
                                        "media.getState",
                                        "media.play",
                                        "media.pause",
                                        "media.next",
                                        "media.previous",
                                        "media.requestAccess",
                                        "system.openWifiSettings",
                                        "system.openUrl",
                                        "system.openAirplaneSettings",
                                        "device.getInfo",
                                        "launcher.getStatus",
                                        "launcher.openHomeSettings",
                                        "settings.openDefaultLauncher"
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
                                    resolveLaunchTarget(payload),
                                    extractActivityName(payload)
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
                                this@MainActivity.openAppInfo(
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

                "wallpaper.setParallax" -> {

                    try {

                        sendResponse(
                            id,
                            true,
                            NativeExtras.applyParallaxWallpaper(this@MainActivity, payload)
                        )

                    } catch (e: Exception) {

                        sendResponse(
                            id,
                            false,
                            code = "WALLPAPER_FAILED",
                            message = e.message ?: "Could not apply the wallpaper"
                        )
                    }
                }

                "media.getState" -> {

                    try {

                        sendResponse(
                            id,
                            true,
                            NativeExtras.currentMediaState(this@MainActivity)
                        )

                    } catch (e: Exception) {

                        sendResponse(
                            id,
                            false,
                            code = "MEDIA_FAILED",
                            message = e.message ?: "Could not read the media session"
                        )
                    }
                }

                "media.play" -> {

                    NativeExtras.dispatchMediaKey(this@MainActivity, KeyEvent.KEYCODE_MEDIA_PLAY)
                    sendResponse(id, true, JSONObject().put("ok", true))
                }

                "media.pause" -> {

                    NativeExtras.dispatchMediaKey(this@MainActivity, KeyEvent.KEYCODE_MEDIA_PAUSE)
                    sendResponse(id, true, JSONObject().put("ok", true))
                }

                "media.next" -> {

                    NativeExtras.dispatchMediaKey(this@MainActivity, KeyEvent.KEYCODE_MEDIA_NEXT)
                    sendResponse(id, true, JSONObject().put("ok", true))
                }

                "media.previous" -> {

                    NativeExtras.dispatchMediaKey(this@MainActivity, KeyEvent.KEYCODE_MEDIA_PREVIOUS)
                    sendResponse(id, true, JSONObject().put("ok", true))
                }

                "media.requestAccess" -> {

                    NativeExtras.openNotificationAccess(this@MainActivity)
                    sendResponse(id, true, JSONObject().put("opened", true))
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
            val payload = try {
                JSONObject(value)
            } catch (_: Exception) {
                JSONObject().put("label", value)
            }
            dispatchDirect(
                "apps.launch",
                extractAppIdentifier(payload),
                extractActivityName(payload)
            )
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
            value: String,
            activityName: String = ""
        ) {

            runOnUiThread {

                try {

                    when (method) {

                        "apps.launch" ->
                            launchAppNative(value, activityName)

                        "apps.openAppInfo" ->
                            this@MainActivity.openAppInfo(value)

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

    /**
     * A stored package this phone does not have (a cloned or renamed app) must
     * not block the launch: fall back to the app's own name.
     */
    private fun resolveLaunchTarget(payload: JSONObject): String {

        val identifier = extractAppIdentifier(payload)

        val label = payload.optString("appName", "")
            .ifBlank { payload.optString("label", "") }
            .trim()

        if (
            identifier.contains('.') &&
            !identifier.contains(' ') &&
            !isInstalled(identifier) &&
            label.isNotEmpty()
        ) {
            return label
        }

        return identifier
    }

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

    private fun resolveByLabel(raw: String): String {

        if (raw.isBlank()) return ""

        val wanted = raw
            .lowercase()
            .replace(Regex("[^a-z0-9]"), "")

        if (wanted.isEmpty()) return ""

        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)

        for (info in packageManager.queryIntentActivities(intent, 0)) {

            val label = info.loadLabel(packageManager)
                .toString()
                .lowercase()
                .replace(Regex("[^a-z0-9]"), "")

            if (label == wanted) {
                return info.activityInfo.packageName
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

            else -> {
                val byLabel = resolveByLabel(raw)
                if (byLabel.isNotBlank()) byLabel else raw
            }
        }
    }

    // --------------------------------------------------
    // APP LAUNCHING
    // --------------------------------------------------

    private fun isInstalled(packageName: String): Boolean {

        return try {
            packageManager.getPackageInfo(packageName, 0)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun extractActivityName(payload: JSONObject): String {

        val keys = arrayOf("activityName", "activity", "component")

        for (key in keys) {

            val value = payload.optString(key, "")

            if (value.isNotBlank() && value != "null") {
                return value.trim()
            }
        }

        return ""
    }

    private fun launchAppNative(
        value: String,
        activityName: String = ""
    ): JSONObject {

        var packageName = resolvePackageName(value)

        if (packageName.isBlank()) {

            throw IllegalArgumentException(
                "PACKAGE_NOT_SET: App name or package name required"
            )
        }

        if (!isInstalled(packageName)) {

            val byLabel = resolveByLabel(value)

            if (byLabel.isNotBlank()) packageName = byLabel
        }

        // 1. the exact activity the phone reported for this app
        if (activityName.isNotBlank()) {

            try {

                val explicit = Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LAUNCHER)
                    .setComponent(
                        ComponentName(packageName, activityName)
                    )
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

                if (packageManager.resolveActivity(explicit, 0) != null) {

                    startActivity(explicit)

                    return JSONObject()
                        .put("launched", true)
                        .put("packageName", packageName)
                }

            } catch (_: Exception) {
                /* fall through to the system intent */
            }
        }

        // 2. the system's own launch intent
        var launchIntent =
            packageManager.getLaunchIntentForPackage(packageName)

        // 3. the first launcher activity the package declares
        if (launchIntent == null) {

            val query = Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)

            val match = packageManager
                .queryIntentActivities(query, 0)
                .firstOrNull {
                    it.activityInfo.packageName == packageName
                }

            if (match != null) {

                launchIntent = Intent(query)
                    .setComponent(
                        ComponentName(
                            packageName,
                            match.activityInfo.name
                        )
                    )
            }
        }

        if (launchIntent == null) {

            throw IllegalArgumentException(
                "APP_NOT_FOUND: $packageName"
            )
        }

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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {

            val manager = getSystemService(RoleManager::class.java)

            if (manager != null && manager.isRoleAvailable(RoleManager.ROLE_HOME)) {
                return manager.isRoleHeld(RoleManager.ROLE_HOME)
            }
        }

        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_HOME)

        val resolved = packageManager.resolveActivity(
            intent,
            PackageManager.MATCH_DEFAULT_ONLY
        ) ?: return false

        return resolved.activityInfo?.packageName == packageName
    }

    private fun openUrlNative(url: String): JSONObject {

        val trimmed = url.trim()

        if (
            trimmed.isBlank() ||
            !(
                trimmed.startsWith("https://") ||
                trimmed.startsWith("http://")
            )
        ) {

            throw IllegalArgumentException(
                "INVALID_URL: Only http and https links"
            )
        }

        startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(trimmed))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )

        return JSONObject()
            .put("opened", true)
            .put("url", trimmed)
    }

    private fun openHomeSettingsNative(): JSONObject {

        try {

            startActivity(Intent(Settings.ACTION_HOME_SETTINGS))

        } catch (_: Exception) {

            startActivity(Intent(Settings.ACTION_SETTINGS))
        }

        return JSONObject()
            .put("opened", true)
            .put("isDefault", isDefaultLauncher())
    }

    private fun requestDefaultLauncherNative() {

        try {

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {

                val manager = getSystemService(RoleManager::class.java)

                if (
                    manager != null &&
                    manager.isRoleAvailable(RoleManager.ROLE_HOME)
                ) {

                    if (manager.isRoleHeld(RoleManager.ROLE_HOME)) {
                        completeHomeRequest(true, true)
                        return
                    }

                    startActivityForResult(
                        manager.createRequestRoleIntent(
                            RoleManager.ROLE_HOME
                        ),
                        homeRoleRequestCode
                    )

                    return
                }
            }

        } catch (_: Exception) {
            /* some phones have no working role dialog: fall through */
        }

        try {

            startActivity(Intent(Settings.ACTION_HOME_SETTINGS))

            return

        } catch (_: Exception) {
            /* fall through to the chooser */
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
    // TILT STREAM
    // --------------------------------------------------

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return

        // Low-pass the raw accelerometer so the layers glide instead of shaking.
        gravityX += (event.values[0] - gravityX) * 0.14f
        gravityY += (event.values[1] - gravityY) * 0.14f

        if (tiltBaseX == null) {
            tiltBaseX = gravityX
            tiltBaseY = gravityY
        }

        val tiltX = (-((gravityX - (tiltBaseX ?: gravityX)) / 3.5f)).coerceIn(-1f, 1f)
        val tiltY = (-((gravityY - (tiltBaseY ?: gravityY)) / 3.5f)).coerceIn(-1f, 1f)

        if (Math.abs(tiltX - lastTiltX) < 0.008f && Math.abs(tiltY - lastTiltY) < 0.008f) return

        val now = System.currentTimeMillis()
        if (now - lastTiltAt < 16L) return
        lastTiltAt = now
        lastTiltX = tiltX
        lastTiltY = tiltY

        emitEvent(
            "device.tilt",
            JSONObject()
                .put("x", tiltX.toDouble())
                .put("y", tiltY.toDouble())
        )
    }

    // --------------------------------------------------
    // IMMERSIVE MODE
    // --------------------------------------------------

    /** Hides the status bar (and its small clock) while a full-screen page is open. */
    private fun applyImmersive(enabled: Boolean) {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {

            window.setDecorFitsSystemWindows(!enabled)
            val controller = window.insetsController ?: return

            if (enabled) {
                controller.hide(WindowInsets.Type.statusBars())
                controller.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            } else {
                controller.show(WindowInsets.Type.statusBars())
            }

            return
        }

        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = if (enabled) {
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        } else {
            0
        }
    }

    // The status bar (and its small clock) is never shown again: re-hide it
    // whenever Android brings it back after a dialog, a swipe or a resume.
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyImmersive(true)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

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

        (getSystemService(SENSOR_SERVICE) as? SensorManager)?.unregisterListener(this)

        worker.shutdownNow()

        super.onDestroy()
    }
}
