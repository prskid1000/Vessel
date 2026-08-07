package app.vessel

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
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
                )
            }
        }
    }
}
