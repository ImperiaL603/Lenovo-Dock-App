package com.lenovodock.app

import android.service.notification.NotificationListenerService

/**
 * Placeholder NotificationListenerService. Declaring it surfaces the app under
 * Settings > Notification access so the grant can be tested now. This grant is
 * what MediaSessionManager.getActiveSessions() will need in step 2.
 */
class MediaListenerService : NotificationListenerService()
