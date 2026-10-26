package com.smoliicek.vinyl

import android.service.notification.NotificationListenerService

/**
 * Intentionally empty. Being an enabled notification listener is what lets us call
 * MediaSessionManager.getActiveSessions() with our component name.
 */
class NowPlayingListener : NotificationListenerService()
