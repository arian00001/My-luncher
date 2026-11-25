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
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
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
    private val originHost = "my-luncher.base44.app"
    private val startUrl = "https://my-luncher.base44.app/"
    private val wallpaperRequestCode = 4101
    private val homeRoleRequestCode = 4102

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        webView = WebView(this)
        setContentView(webView)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.allowFileAccess = false
        webView.settings.allowContentAccess = true
        webView.settings.javaScriptCanOpenWindowsAutomatically = false
        webView.settings.setSupportMultipleWindows(false)
        webView.settings.cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
        webView.webChromeClient = WebChromeClient()
        webView.addJavascriptInterface(AndroidBridge(), "AndroidLauncherNative")
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val uri = request?.url ?: return true
                if (uri.scheme == "https" && uri.host == originHost) return false
                if (uri.scheme == "http" || uri.scheme == "https") {
                    try { startActivity(Intent(Intent.ACTION_VIEW, uri)) } catch (_: Exception) { }
                }
                return true
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (Uri.parse(url ?: "").host == originHost) injectBridgeAdapter()
            }
        }
        if (savedInstanceState != null) webView.restoreState(savedInstanceState) else webView.loadUrl(startUrl)
    }

    private fun injectBridgeAdapter() {
        val js = """
          (function(){
            if (window.__myLauncherBridgeInstalled) return;
            window.__myLauncherBridgeInstalled = true;
            var nativeBridge = window.AndroidLauncherNative;
            var pending = window.__nativeBridgePending = window.__nativeBridgePending || {};
            function request(method, payload) {
              return new Promise(function(resolve, reject) {
                if (!nativeBridge || typeof nativeBridge.postMessage !== 'function') {
                  reject(new Error('NATIVE_BRIDGE_UNAVAILABLE')); return;
                }
                var id = 'ml_' + Date.now() + '_' + Math.random().toString(36).slice(2);
                var timeout = setTimeout(function() {
                  delete pending[id]; reject(new Error('NATIVE_TIMEOUT'));
                }, 20000);
                pending[id] = {resolve:resolve, reject:reject, timeout:timeout};
                try { nativeBridge.postMessage(JSON.stringify({type:'request', id:id, method:method, payload:payload || {}})); }
                catch(e) { clearTimeout(timeout); delete pending[id]; reject(e); }
              });
            }
            window.__nativeBridgeResolve = function(raw) {
              var response;
              try { response = typeof raw === 'string' ? JSON.parse(raw) : raw; } catch(e) { return; }
              var item = response && pending[response.id];
              if (!item) return;
              clearTimeout(item.timeout); delete pending[response.id];
              if (response.success) item.resolve(response.data || {});
              else item.reject(Object.assign(new Error(response.error && response.error.message || 'NATIVE_ERROR'),
                {code:response.error && response.error.code || 'NATIVE_ERROR'}));
            };
            var api = window.AndroidLauncher = window.AndroidLauncher || {};
            api.request = request;
            api.launchApp = function(value) {
              var p = value && typeof value === 'object' ? value : {appName:String(value || '')};
              return request('apps.launch', p);
            };
            api.getInstalledApps = function() { return request('apps.getInstalled', {}); };
            api.requestDefaultLauncher = function() { return request('launcher.requestDefault', {}); };
            api.pickWallpaper = function() { return request('wallpaper.pickImage', {}); };
            api.openWifiSettings = function() { return request('system.openWifiSettings', {}); };
            api.openAirplaneSettings = function() { return request('system.openAirplaneSettings', {}); };
            api.openAppInfo = function(value) {
              return request('apps.openAppInfo', value && typeof value === 'object' ? value : {packageName:String(value || '')});
            };
            api.getDeviceInfo = function() { return request('device.getInfo', {}); };
            window.dispatchEvent(new CustomEvent('android-bridge-ready', {detail:{version:2}}));
            window.dispatchEvent(new CustomEvent('bridge-ready', {detail:{version:2}}));
          })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    private fun sendResponse(id: String, success: Boolean, data: JSONObject = JSONObject(), code: String? = null, message: String? = null) {
        if (id.isBlank()) return
        val response = JSONObject().put("type", "response").put("id", id).put("success", success)
        if (success) response.put("data", data)
        else response.put("error", JSONObject().put("code", code ?: "NATIVE_ERROR").put("message", message ?: code ?: "Native operation failed"))
        val encoded = JSONObject.quote(response.toString())
        runOnUiThread { if (::webView.isInitialized) webView.evaluateJavascript("window.__nativeBridgeResolve && window.__nativeBridgeResolve($encoded);", null) }
    }

    private fun emitEvent(name: String, data: JSONObject) {
        val encodedName = JSONObject.quote(name)
        val encodedData = JSONObject.quote(data.toString())
        runOnUiThread {
            if (::webView.isInitialized) webView.evaluateJavascript(
                "window.dispatchEvent(new CustomEvent($encodedName,{detail:JSON.parse($encodedData)}));", null
            )
        }
    }

    inner class AndroidBridge {
        @JavascriptInterface
        fun postMessage(raw: String) {
            val request = try { JSONObject(raw) } catch (_: Exception) {
                emitEvent("android-bridge-error", JSONObject().put("code", "INVALID_JSON").put("message", "Request must be valid JSON"))
                return
            }
            val id = request.optString("id")
            val method = request.optString("method")
            val payload = request.optJSONObject("payload") ?: JSONObject()
            if (method.isBlank()) { sendResponse(id, false, code = "MISSING_METHOD", message = "Request method is required"); return }

            when (method) {
                "apps.getInstalled" -> worker.execute {
                    try { sendResponse(id, true, getInstalledAppsNative()) }
                    catch (e: Exception) { sendResponse(id, false, code = "APPS_QUERY_FAILED", message = e.message) }
                }
                "device.getInfo" -> sendResponse(id, true, getDeviceInfo())
                "bridge.getCapabilities" -> sendResponse(id, true, JSONObject()
                    .put("version", 2)
                    .put("methods", JSONArray(listOf("apps.getInstalled", "apps.launch", "apps.openAppInfo",
                        "launcher.requestDefault", "wallpaper.pickImage", "system.openWifiSettings",
                        "system.openAirplaneSettings", "device.getInfo"))))
                "apps.launch" -> runOnUiThread {
                    try { sendResponse(id, true, launchAppNative(extractAppIdentifier(payload))) }
                    catch (e: Exception) { sendResponse(id, false, code = errorCode(e), message = e.message) }
                }
                "apps.openAppInfo" -> runOnUiThread {
                    try { sendResponse(id, true, openAppInfo(extractAppIdentifier(payload))) }
                    catch (e: Exception) { sendResponse(id, false, code = errorCode(e), message = e.message) }
                }
                "launcher.requestDefault", "launcher.setDefault", "system.setDefaultLauncher", "apps.setDefaultLauncher" ->
                    runOnUiThread {
                        try {
                            val alreadyDefault = isDefaultLauncher()
                            if (!alreadyDefault) {
                                sendResponse(id, true, JSONObject().put("accepted", true).put("requiresUserConfirmation", true))
                                requestDefaultLauncherNative()
                            } else sendResponse(id, true, JSONObject().put("isDefault", true).put("alreadyDefault", true))
                        } catch (e: Exception) { sendResponse(id, false, code = "HOME_ROLE_FAILED", message = e.message) }
                    }
                "wallpaper.pickImage" -> runOnUiThread {
                    try {
                        sendResponse(id, true, JSONObject().put("accepted", true).put("resultEvent", "wallpaper-result"))
                        pickWallpaperNative()
                    } catch (e: Exception) { sendResponse(id, false, code = "WALLPAPER_PICKER_FAILED", message = e.message) }
                }
                "system.openWifiSettings" -> runOnUiThread {
                    try { openWifiSettings(); sendResponse(id, true, JSONObject().put("opened", true)) }
                    catch (e: Exception) { sendResponse(id, false, code = "SETTINGS_UNAVAILABLE", message = e.message) }
                }
                "system.openAirplaneSettings" -> runOnUiThread {
                    try { startActivity(Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS)); sendResponse(id, true, JSONObject().put("opened", true)) }
                    catch (e: Exception) { sendResponse(id, false, code = "SETTINGS_UNAVAILABLE", message = e.message) }
                }
                else -> sendResponse(id, false, code = "METHOD_NOT_SUPPORTED", message = "Unsupported native method: $method")
            }
        }

        // Legacy/simple JavaScript bridge methods retained for existing website integrations.
        @JavascriptInterface fun launchApp(value: String): String = JSONObject().put("accepted", dispatchDirect("apps.launch", value)).toString()
        @JavascriptInterface fun getInstalledApps(): String { worker.execute { try { emitEvent("installed-apps-result", getInstalledAppsNative()) } catch (e: Exception) { emitEvent("installed-apps-error", JSONObject().put("message", e.message)) } }; return JSONObject().put("accepted", true).toString() }
        @JavascriptInterface fun requestDefaultLauncher(): String = JSONObject().put("accepted", dispatchDirect("launcher.requestDefault", "")).toString()
        @JavascriptInterface fun pickWallpaper(): String = JSONObject().put("accepted", dispatchDirect("wallpaper.pickImage", "")).toString()
        @JavascriptInterface fun openWifiSettings(): String = JSONObject().put("accepted", dispatchDirect("system.openWifiSettings", "")).toString()
        @JavascriptInterface fun openAirplaneSettings(): String = JSONObject().put("accepted", dispatchDirect("system.openAirplaneSettings", "")).toString()
        @JavascriptInterface fun openAppInfo(value: String): String = JSONObject().put("accepted", dispatchDirect("apps.openAppInfo", value)).toString()
        @JavascriptInterface fun getDeviceInfo(): String = this@MainActivity.getDeviceInfo().toString()

        private fun dispatchDirect(method: String, value: String): Boolean {
            runOnUiThread {
                try {
                    when (method) {
                        "apps.launch" -> launchAppNative(value)
                        "apps.openAppInfo" -> openAppInfo(value)
                        "launcher.requestDefault" -> requestDefaultLauncherNative()
                        "wallpaper.pickImage" -> pickWallpaperNative()
                        "system.openWifiSettings" -> openWifiSettings()
                        "system.openAirplaneSettings" -> startActivity(Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS))
                    }
                } catch (e: Exception) { emitEvent("android-bridge-error", JSONObject().put("code", errorCode(e)).put("message", e.message)) }
            }
            return true
        }
    }

    private fun getDeviceInfo() = JSONObject()
        .put("manufacturer", Build.MANUFACTURER).put("brand", Build.BRAND)
        .put("model", Build.MODEL).put("device", Build.DEVICE)
        .put("androidVersion", Build.VERSION.RELEASE).put("sdk", Build.VERSION.SDK_INT)

    private fun extractAppIdentifier(payload: JSONObject): String = firstNonBlank(
        payload.optString("packageName"), payload.optString("package"), payload.optString("appPackage"),
        payload.optString("appName"), payload.optString("name"), payload.optString("label")
    )

    private fun firstNonBlank(vararg values: String) = values.firstOrNull { it.isNotBlank() && it != "null" }?.trim().orEmpty()

    private fun getInstalledAppsNative(): JSONObject {
        val pm = packageManager
        val apps = JSONArray()
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = pm.queryIntentActivities(intent, 0)
        val seen = HashSet<String>()
        for (info in resolved) {
            val activity = info.activityInfo ?: continue
            val pkg = activity.packageName
            if (!seen.add(pkg)) continue
            val icon = try {
                val drawable = info.loadIcon(pm)
                val bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(bitmap)
                drawable.setBounds(0, 0, canvas.width, canvas.height)
                drawable.draw(canvas)
                val out = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.PNG, 90, out)
                "data:image/png;base64," + android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP)
            } catch (_: Exception) { "" }
            apps.put(JSONObject().put("label", info.loadLabel(pm).toString()).put("packageName", pkg)
                .put("activityName", activity.name).put("iconDataUrl", icon).put("launchable", true))
        }
        return JSONObject().put("apps", apps).put("count", apps.length())
    }

    private fun resolvePackageName(value: String): String {
        val raw = value.trim()
        if (raw.isEmpty()) return ""
        if (raw.contains('.') && !raw.contains(' ')) return raw
        return when (raw.lowercase().replace(Regex("[^a-z0-9]"), "")) {
            "playstore", "googleplay", "googleplaystore" -> "com.android.vending"
            "gmail", "googlemail" -> "com.google.android.gm"
            "google", "googlesearch" -> "com.google.android.googlequicksearchbox"
            "facebook" -> "com.facebook.katana"
            "instagram" -> "com.instagram.android"
            "telegram" -> "org.telegram.messenger"
            "youtube" -> "com.google.android.youtube"
            "chrome", "googlechrome" -> "com.android.chrome"
            "googlemaps", "googlemapsnavigation", "maps", "navigation" -> "com.google.android.apps.maps"
            "youtubemusic", "ytmusic", "music" -> "com.google.android.apps.youtube.music"
            else -> raw
        }
    }

    private fun launchAppNative(value: String): JSONObject {
        val pkg = resolvePackageName(value)
        if (pkg.isBlank()) throw IllegalArgumentException("PACKAGE_NOT_SET: provide packageName or appName")
        val intent = packageManager.getLaunchIntentForPackage(pkg) ?: throw IllegalArgumentException("APP_NOT_FOUND: $pkg")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
        return JSONObject().put("launched", true).put("packageName", pkg)
    }

    private fun errorCode(e: Exception): String = when {
        e.message?.startsWith("APP_NOT_FOUND") == true -> "APP_NOT_FOUND"
        e.message?.startsWith("PACKAGE_NOT_SET") == true -> "PACKAGE_NOT_SET"
        e.message?.startsWith("INVALID_PACKAGE") == true -> "INVALID_PACKAGE"
        else -> "NATIVE_OPERATION_FAILED"
    }

    private fun isDefaultLauncher(): Boolean {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val resolved = packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) ?: return false
        return resolved.activityInfo?.packageName == packageName
    }

    private fun requestDefaultLauncherNative() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val manager = getSystemService(RoleManager::class.java)
            if (manager != null && manager.isRoleAvailable(RoleManager.ROLE_HOME)) {
                if (manager.isRoleHeld(RoleManager.ROLE_HOME)) {
                    completeHomeRequest(true, true)
                } else startActivityForResult(manager.createRequestRoleIntent(RoleManager.ROLE_HOME), homeRoleRequestCode)
                return
            }
        }
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        startActivityForResult(Intent.createChooser(home, "Choose My Launcher as your home app"), homeRoleRequestCode)
    }

    private fun completeHomeRequest(success: Boolean, already: Boolean = false) {
        emitEvent("default-launcher-result", JSONObject().put("success", success)
            .put("isDefault", isDefaultLauncher()).put("alreadyDefault", already))
    }

    private fun openAppInfo(value: String): JSONObject {
        val pkg = resolvePackageName(value)
        if (pkg.isBlank()) throw IllegalArgumentException("INVALID_PACKAGE: package name is required")
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")))
        return JSONObject().put("opened", true).put("packageName", pkg)
    }

    private fun openWifiSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try { startActivity(Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY)); return } catch (_: Exception) { }
        }
        startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
    }

    private fun pickWallpaperNative() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE)
        startActivityForResult(intent, wallpaperRequestCode)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == homeRoleRequestCode) {
            completeHomeRequest(resultCode == RESULT_OK || isDefaultLauncher())
        } else if (requestCode == wallpaperRequestCode) {
            if (resultCode != RESULT_OK || data?.data == null) {
                emitEvent("wallpaper-result", JSONObject().put("success", false).put("cancelled", true))
                return
            }
            val uri = data.data!!
            worker.execute {
                try {
                    try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) { }
                    val bitmap = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
                        ?: throw IllegalStateException("Unable to read selected image")
                    WallpaperManager.getInstance(this).setBitmap(bitmap)
                    emitEvent("wallpaper-result", JSONObject().put("success", true).put("applied", true).put("uri", uri.toString()))
                    emitEvent("wallpaper-changed", JSONObject().put("success", true))
                    runOnUiThread { Toast.makeText(this, "Wallpaper set", Toast.LENGTH_SHORT).show() }
                } catch (e: Exception) {
                    emitEvent("wallpaper-result", JSONObject().put("success", false).put("message", e.message ?: "Could not set wallpaper"))
                }
            }
        }
    }

    override fun onBackPressed() {
        if (::webView.isInitialized && webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (::webView.isInitialized) webView.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        if (::webView.isInitialized) { webView.removeJavascriptInterface("AndroidLauncherNative"); webView.destroy() }
        worker.shutdownNow()
        super.onDestroy()
    }
}
