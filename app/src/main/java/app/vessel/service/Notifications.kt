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
    /**
     * A determinate bar, as `current to max`, or null for no bar at all.
     *
     * Null rather than an indeterminate spinner by default, because a bar that
     * moves without meaning anything is the same lie as a spinner on the
     * Preparing checklist — see DESIGN.md. The download path passes a real pair
     * only once the server has said how long the body is, and passes
     * [INDETERMINATE] while it has not.
     */
    progress: Pair<Long, Long>? = null,
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
            progress?.let { (current, max) ->
                if (max == INDETERMINATE) {
                    setProgress(0, 0, true)
                } else {
                    // Notification progress is an Int and a .wcp is measured in
                    // tens of megabytes, so bytes are scaled into permille
                    // rather than cast. 1000 steps is far finer than the bar can
                    // draw and cannot overflow at any package size.
                    val scaled = if (max > 0) (current * PROGRESS_SCALE / max).toInt() else 0
                    setProgress(PROGRESS_SCALE, scaled.coerceIn(0, PROGRESS_SCALE), false)
                }
            }
        }
        .setOngoing(true)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setOnlyAlertOnce(true)
        .build()
}

/** Pass as the `max` of `progress` for a bar with no known end. */
internal const val INDETERMINATE = -1L

private const val PROGRESS_SCALE = 1000
