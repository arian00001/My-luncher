package com.example.mylauncher

import android.app.Activity
import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.provider.Settings
import android.view.KeyEvent
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Parallax wallpaper and real media controls, kept out of the activity. */
object NativeExtras {

    fun applyParallaxWallpaper(activity: Activity, payload: JSONObject): JSONObject {
        val backgroundUrl = payload.optString("backgroundUrl", "")
            .ifBlank { payload.optString("background", "") }
            .trim()
        val frontUrl = payload.optString("frontUrl", "").trim().let { if (it == "null") "" else it }
        val target = payload.optString("target", "both").ifBlank { "both" }

        if (backgroundUrl.isEmpty()) {
            throw IllegalArgumentException("WALLPAPER_URL_MISSING: a background image is required")
        }

        Thread {
            try {
                val backFile = File(activity.filesDir, "wall_back.jpg")
                val frontFile = File(activity.filesDir, "wall_front.png")
                download(backgroundUrl, backFile)
                if (frontUrl.isNotEmpty()) {
                    download(frontUrl, frontFile)
                } else if (frontFile.exists()) {
                    frontFile.delete()
                }

                // Static layers first so home + lock screen change right away.
                BitmapFactory.decodeFile(backFile.path)?.let { bitmap ->
                    try {
                        WallpaperManager.getInstance(activity).setBitmap(
                            bitmap,
                            null,
                            true,
                            WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK
                        )
                    } catch (_: Exception) {
                        /* the live wallpaper below is the real target */
                    }
                }
            } catch (_: Exception) {
                /* still open the picker: the old layers stay if the download failed */
            }
            activity.runOnUiThread { openLiveWallpaperPicker(activity) }
        }.start()

        return JSONObject()
            .put("accepted", true)
            .put("requiresUserConfirmation", true)
            .put("liveWallpaper", true)
            .put("target", target)
    }

    fun currentMediaState(activity: Activity): JSONObject {
        val audio = activity.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        var playing = audio.isMusicActive
        var title = ""
        var artist = ""

        try {
            val manager = activity.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val component = ComponentName(activity, NowPlayingListener::class.java)
            val sessions: List<MediaController> = manager.getActiveSessions(component)
            val controller = sessions.firstOrNull {
                it.playbackState?.state == PlaybackState.STATE_PLAYING
            } ?: sessions.firstOrNull()

            if (controller != null) {
                val metadata = controller.metadata
                title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: ""
                artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                    ?: metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
                    ?: ""
                playing = controller.playbackState?.state == PlaybackState.STATE_PLAYING
            }
        } catch (_: Exception) {
            /* notification access not granted yet: keys still control the player */
        }

        return JSONObject()
            .put("available", true)
            .put("playing", playing)
            .put("title", title)
            .put("artist", artist)
    }

    fun dispatchMediaKey(activity: Activity, keyCode: Int) {
        val audio = activity.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
    }

    fun openNotificationAccess(activity: Activity) {
        try {
            activity.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        } catch (_: Exception) {
            try {
                activity.startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
            } catch (_: Exception) {
                /* settings unavailable on this build */
            }
        }
    }

    private fun openLiveWallpaperPicker(activity: Activity) {
        val component = ComponentName(activity, ParallaxWallpaperService::class.java)
        try {
            activity.startActivity(
                Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
                    .putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, component)
            )
        } catch (_: Exception) {
            try {
                activity.startActivity(Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER))
            } catch (_: Exception) {
                /* no wallpaper picker on this build */
            }
        }
    }

    private fun download(url: String, destination: File) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 20000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "MonolithLauncher")
        }
        try {
            if (connection.responseCode !in 200..299) {
                throw IllegalStateException("HTTP " + connection.responseCode)
            }
            connection.inputStream.use { input ->
                destination.outputStream().use { output -> input.copyTo(output) }
            }
        } finally {
            connection.disconnect()
        }
    }
}
