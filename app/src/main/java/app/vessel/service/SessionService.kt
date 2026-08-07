package app.vessel.service

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.ServiceCompat

/**
 * The process host for a running container.
 *
 * It has to exist for the same reason the reference app's inference service
 * does: without a foreground service the system reclaims a process holding a
 * running Wine tree, and the session dies as soon as the screen turns off.
 *
 * TODO: no container runtime behind it yet.
 */
class SessionService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildOngoingNotification(
                context = this,
                channelId = CHANNEL_ID,
                channelName = "Running session",
                title = "Session running",
                icon = android.R.drawable.ic_media_play,
            ),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
        return START_NOT_STICKY
    }

    private companion object {
        const val CHANNEL_ID = "session"
        const val NOTIFICATION_ID = 2
    }
}
