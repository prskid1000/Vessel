package app.vessel

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
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
import app.vessel.core.SessionDisplayServer
import app.vessel.ui.VesselApp
import app.vessel.ui.theme.Vessel
import app.vessel.ui.theme.VesselTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

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
    /**
     * The compositor's view, or null when nothing is running.
     *
     * Injected here only so a pad event can be handed to it; the activity does
     * nothing else with the display seam. See [dispatchKeyEvent].
     */
    @Inject
    lateinit var display: SessionDisplayServer

    /**
     * **A gamepad goes to the session first, and this is the whole fix for
     * "the controller does nothing in the game".**
     *
     * Key events reach the *focused view*, and inside a Compose hierarchy that is
     * `AndroidComposeView` — not the `AndroidView` embedded three levels down
     * inside it. The compositor view asks for focus on attach and does not
     * reliably keep it: opening the rail, the Input panel or anything else with a
     * focusable in it moves focus away, and after that a pad press is delivered
     * to Compose, which has nothing to do with it, and is swallowed.
     *
     * Measured, not inferred: with the Input panel open on Learn — a mode whose
     * whole job is to open a picker the instant a pad control is pressed —
     * `adb shell input gamepad keyevent BUTTON_A` produced nothing at all.
     *
     * **Only a pad, and deliberately not the keyboard.** A gamepad event is never
     * something a Compose control in this app wants: there is no pad-driven
     * navigation here, and the one place a key press means something to the UI is
     * the binding picker's *keyboard* capture. Routing keys as well would take
     * typing away from every text field in the product to give it to a background
     * game. So the rule is narrow and stays narrow.
     *
     * The view is asked only while it is attached, so a pad pressed on the home
     * screen after backing out of a session does not drive the desktop that is
     * still running behind it.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val view = if (event.isFromPad()) sessionView() else null
        if (view != null && view.dispatchKeyEvent(event)) {
            // `hadFocus` is the diagnosis, not decoration: false means the event
            // would have gone to Compose and been dropped, which is the failure
            // this override exists for. True means the view would have got it
            // anyway and this only saved a hop.
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "pad key ${event.keyCode} routed; view hadFocus=${view.hasFocus()}")
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    /** The sticks and the triggers, which arrive as axes rather than as keys. */
    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.isFromSource(InputDevice.SOURCE_JOYSTICK) &&
            sessionView()?.onGenericMotionEvent(event) == true
        ) {
            if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, "pad axes routed to the session")
            return true
        }
        return super.dispatchGenericMotionEvent(event)
    }

    private fun sessionView(): View? = display.surface.value?.takeIf { it.isAttachedToWindow }

    private fun KeyEvent.isFromPad(): Boolean =
        source and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
            source and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK

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

        /** `adb shell setprop log.tag.VesselInput DEBUG` to watch pad routing. */
        private const val TAG = "VesselInput"
    }
}
