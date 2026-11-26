package com.example.mylauncher

import android.service.notification.NotificationListenerService

/**
 * Only exists so the launcher may read the active MediaSession (title, artist,
 * playing state). Controls work without it; the user grants access in settings.
 */
class NowPlayingListener : NotificationListenerService()
