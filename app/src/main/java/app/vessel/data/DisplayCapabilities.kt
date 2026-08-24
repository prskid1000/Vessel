package app.vessel.data

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What the screen in front of the user can actually do.
 *
 * Exists so the settings sheet can narrow the frame generation multiple to what
 * this panel can show, rather than offering a ladder written for the phone the
 * feature was developed on. See [app.vessel.core.FrameGenerationLimits].
 *
 * The *fastest* mode rather than the current one: the current refresh is
 * whatever the platform has settled on for what is on screen right now, which
 * during a settings sheet is a still page and says nothing about what a game
 * could be given. The session asks for a faster mode when it starts, so the
 * question here is what it will be able to get.
 */
@Singleton
class DisplayCapabilities @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /**
     * The fastest refresh the default display offers, in whole Hz, or 0 if the
     * display cannot be read.
     *
     * <p>Rounded down, and taken across every mode rather than from
     * `Display.getRefreshRate`, because a panel that lists 60, 90, 120 and 165
     * reports whichever one it is sitting at.
     */
    fun maxRefreshHz(): Int {
        val display: Display = runCatching {
            context.getSystemService(DisplayManager::class.java)
                ?.getDisplay(Display.DEFAULT_DISPLAY)
        }.getOrNull() ?: return 0

        val fastest = runCatching {
            display.supportedModes.maxOfOrNull { it.refreshRate } ?: display.refreshRate
        }.getOrNull() ?: return 0

        return if (fastest > 1f) fastest.toInt() else 0
    }
}
