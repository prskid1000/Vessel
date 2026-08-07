package app.vessel.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
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
): Notification {
    val manager = context.getSystemService(NotificationManager::class.java)
    manager.createNotificationChannel(
        NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_LOW),
    )
    return NotificationCompat.Builder(context, channelId)
        .setSmallIcon(icon)
        .setContentTitle(title)
        .setOngoing(true)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .build()
}
