package app.vessel.ui

import android.app.Activity
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext

/**
 * Which way up a destination is held.
 *
 * **Orientation is pinned per surface rather than adapted to.** Everything except
 * the running session is portrait: home, the sheets, the file browser, the log
 * screens and the dialogs are all portrait-first by nature, and 422 dp of height
 * in landscape buys nothing but clipped forms. The session is the one genuinely
 * landscape-first surface — the Wine virtual desktop is 1280×720 and cannot be
 * resized after it is created, so a portrait session was always a letterboxed
 * compromise.
 *
 * **`sensorLandscape`, not `landscape`.** Somebody holding the phone the other
 * way round should get a desktop the right way up, not an upside-down one.
 *
 * This drives `requestedOrientation` from the composition rather than from the
 * manifest, so the lock follows the destination instead of the process. It is
 * independent of `android:configChanges`, which must stay: that is what stops an
 * Activity recreation taking the EGL surface with it and leaving a black
 * rectangle over a running Wine session. Both are needed and neither substitutes
 * for the other.
 *
 * **Exactly one composable owns `requestedOrientation`, and it is not a screen.**
 * The obvious shape — every destination setting its own on enter and restoring on
 * dispose — races: `NavHost` composes the incoming destination before disposing
 * the outgoing one, so the old screen's `onDispose` runs last and puts the *old*
 * value back over the new screen's. So this is called once, from
 * [VesselApp], with the orientation the current route asks for. One owner, one
 * write, no ordering to get wrong.
 *
 * It is also what makes the session's release free. A session can end by a clean
 * exit, a failure, Stop from the rail, or the user simply backing out; every one
 * of those pops [Routes.SESSION], which changes the route, which changes this
 * value. Nothing has to remember to unlock.
 *
 * A [DisposableEffect] rather than a `LaunchedEffect` so leaving the app entirely
 * hands the orientation back too.
 */
@Composable
fun LockOrientation(orientation: Int) {
    val activity = LocalContext.current.findActivity() ?: return
    DisposableEffect(activity, orientation) {
        activity.requestedOrientation = orientation
        onDispose { activity.requestedOrientation = FREE }
    }
}

/**
 * Which way up [route] is held.
 *
 * **Sheets and dialogs are absent on purpose.** Every one of them
 * — new container, container settings, a program's profile, the launch checklist,
 * the outcome dialogs, the stop and delete confirms — appears over a destination
 * that has already decided. An overlay that sets an orientation wins over its own
 * host and then restores the wrong value when it closes.
 */
fun orientationFor(route: String?): Int =
    // The guest desktop is 1280×720 and cannot be resized after it is created, so
    // it is the only surface that is landscape-*first* rather than landscape-
    // tolerant. Everything else in the product is a vertical list of rows, cards
    // or log lines, and 422 dp of height buys none of them anything.
    if (route == Routes.SESSION) SESSION_LANDSCAPE else PORTRAIT

/** Portrait — every destination but the running desktop. */
const val PORTRAIT: Int = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT

/** Either way round, but landscape — the running desktop, and only it. */
const val SESSION_LANDSCAPE: Int = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE

/** Whatever the user is holding — restored when Vessel is no longer on screen. */
const val FREE: Int = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

/**
 * The `Activity` behind a Compose `Context`.
 *
 * Composables are handed a `ContextThemeWrapper` rather than the Activity itself,
 * so this unwraps until it finds one. Null rather than a cast: this composable is
 * also used from previews, where there is no Activity and nothing to lock.
 */
private fun android.content.Context.findActivity(): Activity? {
    var context = this
    while (context is ContextWrapper) {
        if (context is Activity) return context
        context = context.baseContext
    }
    return null
}
