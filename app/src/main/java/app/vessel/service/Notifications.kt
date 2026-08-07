package app.vessel.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationCompat

/**
 * The ongoing notification both foreground services need before they may run.
 *
 * The channel is created on every call: `createNotificationChannel` is
 * idempotent, and a service that assumes the channel already exists is a
 * silently-dropped notification the first time it starts after an install.
 */
internal fun buildOngoingNotification(
    context: Context,
    channelId: String,
    channelName: String,
    title: String,
    icon: Int,
    text: String? = null,
    /** Tapping the notification. A running session needs a way back to its screen. */
    contentIntent: PendingIntent? = null,
    /** One action at most: Stop. A shade entry is not a control panel. */
    action: NotificationCompat.Action? = null,
): Notification {
    val manager = context.getSystemService(NotificationManager::class.java)
    manager.createNotificationChannel(
        NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_LOW),
    )
    return NotificationCompat.Builder(context, channelId)
        .setSmallIcon(icon)
        .setContentTitle(title)
        .apply {
            text?.let { setContentText(it) }
            contentIntent?.let { setContentIntent(it) }
            action?.let { addAction(it) }
        }
        .setOngoing(true)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .build()
}
