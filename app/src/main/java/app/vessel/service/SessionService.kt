package app.vessel.service

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.vessel.MainActivity
import app.vessel.core.DisplayGeometry
import app.vessel.data.SessionPhase
import app.vessel.data.SessionRuntime
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The process host for a running container.
 *
 * It has to exist for the same reason the reference app's inference service does:
 * without a foreground service the system reclaims a process holding a running
 * Wine tree, and the session dies as soon as the screen turns off. The service
 * owns the *lifetime*; [SessionRuntime] owns the processes, because the Session
 * screen has to read the same state and a `ViewModel` cannot bind to a service
 * for it.
 *
 * **`specialUse`, and the justification is that none of the other types is
 * honest.** Android 14+ requires a foreground service type, and the defined set
 * describes what a phone app does — media playback, a sync, a location fix, a
 * call. Hosting a Windows process tree the user explicitly started is none of
 * them. `mediaPlayback` would be the convenient lie (a game does render and play
 * audio) and would misdeclare a session that is compiling shaders or running an
 * installer. `dataSync` is the download service's, and it is wrong here for the
 * same reason: nothing is being synchronised. `specialUse` carries a
 * human-readable subtype in the manifest saying exactly what it is for, which is
 * the mechanism Google provides for precisely this case, and the app is
 * sideloaded rather than Play-distributed on the `sideload` flavour.
 *
 * `START_NOT_STICKY`: a session that the system killed must not silently come
 * back without the user asking. It would restart a Wine prefix, with no screen
 * showing it, on a device that has just been under memory pressure.
 */
@AndroidEntryPoint
class SessionService : Service() {

    @Inject
    lateinit var runtime: SessionRuntime

    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())

    /** The container this service was started for, so the notification can open it. */
    private var running: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // The notification tracks the phase, and the service stops itself when
        // the runtime reaches a terminal one. Nothing else decides when a
        // session is over — a second owner of that decision is how a foreground
        // service outlives its work and gets the app killed for it.
        scope.launch {
            runtime.state.collectLatest { state ->
                if (state.phase == SessionPhase.IDLE) return@collectLatest
                if (state.finished) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                } else {
                    notify(state.containerName, state.phase)
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Promoted before anything else: Android gives a service a few seconds
        // from `startForegroundService` to call this, and provisioning a
        // container is far longer than that.
        intent?.getStringExtra(EXTRA_CONTAINER_ID)?.takeIf { it.isNotBlank() }?.let { running = it }
        // The phase has to match what this call is actually doing. It used to be
        // PREPARING unconditionally, and the notification's own Stop button is a
        // getService PendingIntent that arrives here — so pressing Stop relabelled
        // the notification "Preparing the container" while the session tore down.
        notify(
            intent?.getStringExtra(EXTRA_NAME).orEmpty(),
            SessionPhase.PREPARING,
            stopping = intent?.action == ACTION_STOP,
        )

        when (intent?.action) {
            ACTION_STOP -> {
                runtime.stop()
                // Stop with nothing running leaves the state IDLE, which the
                // collector deliberately ignores — so without this the service
                // promoted itself to the foreground two lines ago and would
                // never come back down.
                if (!runtime.state.value.active) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf(startId)
                }
            }

            else -> {
                val containerId = intent?.getStringExtra(EXTRA_CONTAINER_ID)
                if (containerId.isNullOrBlank()) {
                    stopSelf(startId)
                    return START_NOT_STICKY
                }
                val width = intent.getIntExtra(EXTRA_WIDTH, 0)
                val height = intent.getIntExtra(EXTRA_HEIGHT, 0)
                val native = if (width > 0 && height > 0) DisplayGeometry(width, height) else null
                requestAudioFocus()
                runtime.start(containerId, native)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        abandonAudioFocus()
        scope.cancel()
        super.onDestroy()
    }

    /**
     * Ask for audio focus for as long as a session is running.
     *
     * **This is not politeness, it is the difference between audible and
     * silent.** Measured 2026-08-10: the guest opens an AAudio stream, writes a
     * full buffer into it and the stream reports `AAUDIO_STREAM_STATE_STARTED`,
     * but `getFramesRead()` never advances and AudioFlinger's own dump shows the
     * track with `Active: no` and a server position of zero — it never pulled a
     * frame. Nothing in the app had ever called [AudioManager.requestAudioFocus],
     * and this device runs Android's playback hardening, which was observed in
     * `dumpsys audio` muting other packages by name.
     *
     * `USAGE_GAME` rather than `USAGE_MEDIA`: a session is interactive, and the
     * distinction is what tells the platform this should duck a podcast rather
     * than queue behind it. The guest's own stream is `USAGE_MEDIA` because
     * that is what `wineoss.drv` opens; the focus request is about the app.
     *
     * Deliberately no [AudioManager.OnAudioFocusChangeListener] behaviour beyond
     * holding the request. Pausing a Windows program on focus loss is not
     * something this layer can do — there is no pause to send — and ducking
     * would mean attenuating in the driver, which contradicts the rule that
     * Wine outputs at full scale and Android owns the volume.
     */
    private val audioManager: AudioManager? by lazy {
        ContextCompat.getSystemService(this, AudioManager::class.java)
    }

    private var audioFocus: AudioFocusRequest? = null

    private fun requestAudioFocus() {
        val manager = audioManager ?: return
        if (audioFocus != null) return
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_UNKNOWN)
                    .build(),
            )
            // The guest cannot be paused, so a transient loss it cannot act on
            // would leave the user with a silent game and no way to recover it.
            .setWillPauseWhenDucked(false)
            .setOnAudioFocusChangeListener {}
            .build()
        audioFocus = request
        manager.requestAudioFocus(request)
    }

    private fun abandonAudioFocus() {
        val manager = audioManager ?: return
        audioFocus?.let { manager.abandonAudioFocusRequest(it) }
        audioFocus = null
    }

    private fun notify(containerName: String, phase: SessionPhase, stopping: Boolean = false) {
        val stop = PendingIntent.getService(
            this,
            REQUEST_STOP,
            Intent(this, SessionService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildOngoingNotification(
                context = this,
                channelId = CHANNEL_ID,
                channelName = "Running session",
                title = containerName.ifBlank { "Session" },
                icon = android.R.drawable.ic_media_play,
                // Stopping is not a SessionPhase — the runtime goes straight to a
                // terminal phase and the collector tears the notification down —
                // so it is carried separately rather than invented as a state the
                // rest of the app would then have to handle.
                text = when {
                    stopping -> "Stopping the session"
                    phase == SessionPhase.PREPARING -> "Preparing the container"
                    phase == SessionPhase.STARTING -> "Starting Wine"
                    else -> "Running"
                },
                // Straight to the session, not to the container list. The
                // notification exists because a session is running; landing
                // anywhere else makes the user go and find it.
                contentIntent = PendingIntent.getActivity(
                    this,
                    REQUEST_OPEN,
                    Intent(this, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        .putExtra(MainActivity.EXTRA_OPEN_SESSION, running),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
                action = NotificationCompat.Action.Builder(0, "Stop", stop).build(),
            ),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
    }

    companion object {
        private const val CHANNEL_ID = "session"
        private const val NOTIFICATION_ID = 2
        private const val REQUEST_STOP = 1
        private const val REQUEST_OPEN = 2

        private const val ACTION_STOP = "app.vessel.session.STOP"
        private const val EXTRA_CONTAINER_ID = "containerId"
        private const val EXTRA_NAME = "containerName"
        private const val EXTRA_WIDTH = "nativeWidth"
        private const val EXTRA_HEIGHT = "nativeHeight"

        /**
         * Launch [containerId].
         *
         * [native] is the phone's own panel size, measured by the caller because
         * only a UI context can. It is what `display.resolution: native`
         * resolves to.
         */
        fun launch(
            context: Context,
            containerId: String,
            containerName: String,
            native: DisplayGeometry?,
        ) {
            val intent = Intent(context, SessionService::class.java)
                .putExtra(EXTRA_CONTAINER_ID, containerId)
                .putExtra(EXTRA_NAME, containerName)
                .putExtra(EXTRA_WIDTH, native?.width ?: 0)
                .putExtra(EXTRA_HEIGHT, native?.height ?: 0)
            ContextCompat.startForegroundService(context, intent)
        }

        /**
         * Stop the running session.
         *
         * Routed through the service rather than straight to [SessionRuntime] so
         * that the notification's Stop action and the on-screen one are the same
         * call, and neither can leave a foreground service running with nothing
         * behind it.
         */
        fun stop(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, SessionService::class.java).setAction(ACTION_STOP),
            )
        }
    }
}
