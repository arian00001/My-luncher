package com.example.mylauncher

import android.app.Activity
import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private lateinit var webView: WebView
    private val worker = Executors.newSingleThreadExecutor()
    private val originHost = "my-luncher.base44.app"
    private val startUrl = "https://my-luncher.base44.app/"
    private val PICK_WALLPAPER = 4101

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        webView = WebView(this)
        setContentView(webView)
        WebView.setWebContentsDebuggingEnabled(false)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.databaseEnabled = true
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
                    try { startActivity(Intent(Intent.ACTION_VIEW, uri)) } catch (_: Exception) {}
                    return true
                }
                return true
            }
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                injectBridgeAdapter()
            }
        }
        if (savedInstanceState != null) webView.restoreState(savedInstanceState)
        else webView.loadUrl(startUrl)
    }

    private fun injectBridgeAdapter() {
        val js = """
            (function(){
              if (window.__nativeBridgeAdapterInstalled) return;
              window.__nativeBridgeAdapterInstalled = true;
              window.AndroidLauncher = window.AndroidLauncher || {};
              window.AndroidLauncher.request = function(method, payload) {
                return new Promise(function(resolve, reject) {
                  var id = 'req_' + Date.now() + '_' + Math.random().toString(36).slice(2);
                  var timeout = setTimeout(function(){ reject(new Error('NATIVE_TIMEOUT')); }, 12000);
                  window.__nativeBridgePending = window.__nativeBridgePending || {};
                  window.__nativeBridgePending[id] = {resolve:resolve,reject:reject,timeout:timeout};
                  try {
                    window.AndroidLauncherNative.postMessage(JSON.stringify({type:'request',id:id,method:method,payload:payload||{}}));
                  } catch(e) {
                    clearTimeout(timeout); delete window.__nativeBridgePending[id]; reject(e);
                  }
                });
              };
              window.__nativeBridgeResolve = function(raw) {
                try {
                  var r = typeof raw === 'string' ? JSON.parse(raw) : raw;
                  var p = window.__nativeBridgePending && window.__nativeBridgePending[r.id];
                  if (!p) return;
                  clearTimeout(p.timeout); delete window.__nativeBridgePending[r.id];
                  if (r.success) p.resolve(r.data); else p.reject(new Error((r.error&&r.error.code)||'NATIVE_ERROR'));
                } catch(e) {}
              };
              window.dispatchEvent(new CustomEvent('android-bridge-ready',{detail:{version:1}}));
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    inner class AndroidBridge {
        @JavascriptInterface
        fun postMessage(raw: String) {
            worker.execute {
                val request = try { JSONObject(raw) } catch (_: Exception) { return@execute }
                val id = request.optString("id")
                val method = request.optString("method")
                val payload = request.optJSONObject("payload") ?: JSONObject()
                val response = JSONObject().put("type", "response").put("id", id)
                try {
                    val result = when (method) {
                        "bridge.getCapabilities" -> JSONObject()
                            .put("version", 1)
                            .put("methods", JSONArray(listOf("apps.getInstalled","apps.launch","apps.openAppInfo","wallpaper.pickImage","system.openWifiSettings","system.openAirplaneSettings","device.getInfo")))
                        "device.getInfo" -> JSONObject().put("manufacturer", android.os.Build.MANUFACTURER).put("model", android.os.Build.MODEL).put("sdk", android.os.Build.VERSION.SDK_INT)
                        "apps.getInstalled" -> getInstalledApps()
                        "apps.launch" -> launchApp(payload.optString("packageName"))
                        "apps.openAppInfo" -> openAppInfo(payload.optString("packageName"))
                        "wallpaper.pickImage" -> { runOnUiThread { pickWallpaper() }; JSONObject().put("accepted", true) }
                        "system.openWifiSettings" -> { runOnUiThread { startActivity(Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY)) }; JSONObject().put("opened", true) }
                        "system.openAirplaneSettings" -> { runOnUiThread { startActivity(Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS)) }; JSONObject().put("opened", true) }
                        else -> throw IllegalArgumentException("METHOD_NOT_SUPPORTED")
                    }
                    response.put("success", true).put("data", result)
                } catch (e: Exception) {
                    response.put("success", false).put("error", JSONObject().put("code", e.message ?: "NATIVE_ERROR").put("message", e.message ?: "Native operation failed"))
                }
                val encoded = JSONObject.quote(response.toString())
                runOnUiThread { webView.evaluateJavascript("window.__nativeBridgeResolve && window.__nativeBridgeResolve($encoded);", null) }
            }
        }
    }

    private fun getInstalledApps(): JSONObject {
        val pm = packageManager
        val apps = JSONArray()
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
        val seen = HashSet<String>()
        for (info in resolved) {
            val packageName = info.activityInfo.packageName
            if (seen.contains(packageName)) continue
            seen.add(packageName)
            val label = info.loadLabel(pm).toString()
            val iconBytes = try {
                val drawable = info.loadIcon(pm)
                val bmp = android.graphics.Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(bmp)
                drawable.setBounds(0, 0, canvas.width, canvas.height)
                drawable.draw(canvas)
                val out = ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.PNG, 90, out)
                Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
            } catch (_: Exception) { "" }
            apps.put(JSONObject().put("label", label).put("packageName", packageName)
                .put("activityName", info.activityInfo.name)
                .put("iconDataUrl", if (iconBytes.isNotEmpty()) "data:image/png;base64,$iconBytes" else "")
                .put("launchable", true))
        }
        return JSONObject().put("apps", apps)
    }

    private fun launchApp(packageName: String): JSONObject {
        if (packageName.isBlank()) throw IllegalArgumentException("INVALID_PACKAGE")
        val launch = packageManager.getLaunchIntentForPackage(packageName) ?: throw IllegalArgumentException("APP_NOT_FOUND")
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(launch)
        return JSONObject().put("launched", true).put("packageName", packageName)
    }

    private fun openAppInfo(packageName: String): JSONObject {
        if (packageName.isBlank()) throw IllegalArgumentException("INVALID_PACKAGE")
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        return JSONObject().put("opened", true)
    }

    private fun pickWallpaper() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE)
        startActivityForResult(intent, PICK_WALLPAPER)
    }

    @Deprecated("Legacy callback retained for broad Android compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PICK_WALLPAPER && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                val bitmap = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) } ?: return
                WallpaperManager.getInstance(this).setBitmap(bitmap)
                Toast.makeText(this, "Wallpaper set", Toast.LENGTH_SHORT).show()
                webView.evaluateJavascript("window.dispatchEvent(new CustomEvent('wallpaper-changed'));", null)
            } catch (e: Exception) {
                Toast.makeText(this, "Could not set wallpaper: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onBackPressed() {
        if (::webView.isInitialized && webView.canGoBack()) webView.goBack()
        else super.onBackPressed()
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
