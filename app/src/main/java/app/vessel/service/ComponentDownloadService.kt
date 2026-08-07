package app.vessel.service

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.ServiceCompat

/**
 * Where `.wcp` downloads run, so they survive the app being swiped away.
 *
 * TODO: no downloader behind it yet. It exists as the manifest's `dataSync`
 *  component and as the shape the resumable, hash-verified download will take.
 */
class ComponentDownloadService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildOngoingNotification(
                context = this,
                channelId = CHANNEL_ID,
                channelName = "Component downloads",
                title = "Downloading components",
                icon = android.R.drawable.stat_sys_download,
            ),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        return START_NOT_STICKY
    }

    private companion object {
        const val CHANNEL_ID = "components"
        const val NOTIFICATION_ID = 1
    }
}
