package app.vessel

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import app.vessel.ui.VesselApp
import app.vessel.ui.theme.Vessel
import app.vessel.ui.theme.VesselTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    /**
     * Asked once, and refusal costs only the notification.
     *
     * Downloads and sessions both run in foreground services, which Android
     * allows without this permission — but their notifications are then dropped
     * silently, so a component download would have nothing in the shade saying
     * it was happening and no way to stop it.
     */
    private val askForNotifications = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        takeOpenSession(intent)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            askForNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            VesselTheme {
                VesselApp(
                    modifier = Modifier.fillMaxSize().background(Vessel.colors.bg),
                    openSession = openSession,
                    openSessionTicket = openSessionTicket,
                )
            }
        }
    }

    /**
     * A second intent for the same task, which is what the notification sends.
     *
     * `FLAG_ACTIVITY_SINGLE_TOP` means the activity is reused rather than
     * recreated, so `onCreate` does not run again and the new extra would be
     * dropped. Recording it and letting the state read it is what makes tapping
     * the notification while the app is already open land on the session.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        takeOpenSession(intent)
    }

    /**
     * Record the extra, and record *that it arrived again*.
     *
     * The id alone is not enough. Tapping the running-session notification twice
     * delivers the same string, `mutableStateOf` sees no change, and the
     * `LaunchedEffect` keyed on it in `VesselApp` never re-runs — so the second
     * tap, which is exactly the one a user makes after backing out of the
     * desktop, does nothing at all. The ticket is what makes "the same request,
     * again" distinguishable from "still the same request".
     */
    private fun takeOpenSession(intent: Intent?) {
        val id = intent?.getStringExtra(EXTRA_OPEN_SESSION) ?: return
        openSession = id
        openSessionTicket++
    }

    private var openSession by mutableStateOf<String?>(null)
    private var openSessionTicket by mutableIntStateOf(0)

    companion object {
        /** A container id; opens the Session screen on it. See `VesselApp`. */
        const val EXTRA_OPEN_SESSION: String = "openSession"
    }
}
