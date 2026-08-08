package app.vessel.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * The glyphs this product needs and `material-icons-core` does not carry.
 *
 * The core artifact ships forty-eight icons; `material-icons-extended` ships
 * several thousand and about a megabyte of dex, which is not a trade worth
 * making for one toolbar button. These are the standard Material paths at the
 * standard 24 dp / 24 unit geometry, built once and held as `val`s, so they
 * behave exactly like an `Icons.Filled.*` at every call site — including the
 * tint, which `Icon` applies as a colour filter over the black fill.
 */
object VIcons {

    /** `content_copy` — copy the whole log to the clipboard. */
    val CopyAll: ImageVector by lazy { glyph("vessel.copyAll", COPY_ALL_PATH) }

    /** `keyboard` — raise the IME over a running desktop. */
    val Keyboard: ImageVector by lazy { glyph("vessel.keyboard", KEYBOARD_PATH) }

    /** `folder` — open Wine's own file manager on the guest's `C:` drive. */
    val Folder: ImageVector by lazy { glyph("vessel.folder", FOLDER_PATH) }

    /**
     * `pause` — suspend the guest's process tree.
     *
     * Its counterpart is `Icons.Filled.PlayArrow`, which the core artifact does
     * carry. Only one of the pair had to be drawn, which is the whole argument
     * for this file over `material-icons-extended`.
     */
    val Pause: ImageVector by lazy { glyph("vessel.pause", PAUSE_PATH) }

    /** `mouse` — the session rail's pointer mode, when it is about to become trackpad. */
    val Mouse: ImageVector by lazy { glyph("vessel.mouse", MOUSE_PATH) }

    /** `touch_app` — the same control, when it is about to become direct touch. */
    val TouchApp: ImageVector by lazy { glyph("vessel.touchApp", TOUCH_APP_PATH) }

    private fun glyph(name: String, pathData: String): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).addPath(
            pathData = addPathNodes(pathData),
            fill = SolidColor(Color.Black),
        ).build()
}

private const val COPY_ALL_PATH =
    "M16 1H4c-1.1 0-2 .9-2 2v14h2V3h12V1z" +
        "M19 5H8c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h11c1.1 0 2-.9 2-2V7c0-1.1-.9-2-2-2zm0 16H8V7h11v14z"

private const val KEYBOARD_PATH =
    "M20 5H4c-1.1 0-1.99.9-1.99 2L2 17c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V7c0-1.1-.9-2-2-2z" +
        "m-9 3h2v2h-2V8zm0 3h2v2h-2v-2zM8 8h2v2H8V8zm0 3h2v2H8v-2zm-1 2H5v-2h2v2zm0-3H5V8h2v2z" +
        "m9 7H8v-2h8v2zm0-4h-2v-2h2v2zm0-3h-2V8h2v2zm3 3h-2v-2h2v2zm0-3h-2V8h2v2z"

private const val FOLDER_PATH =
    "M10 4H4c-1.1 0-1.99.9-1.99 2L2 18c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V8c0-1.1-.9-2-2-2h-8l-2-2z"

private const val PAUSE_PATH = "M6 19h4V5H6v14zm8-14v14h4V5h-4z"

private const val MOUSE_PATH =
    "M13 1.07V9h7c0-4.08-3.05-7.44-7-7.93z" +
        "M4 15c0 4.42 3.58 8 8 8s8-3.58 8-8v-4H4v4z" +
        "m7-13.93C7.05 1.56 4 4.92 4 9h7V1.07z"

private const val TOUCH_APP_PATH =
    "M9 11.24V7.5C9 6.12 10.12 5 11.5 5S14 6.12 14 7.5v3.74c1.21-.81 2-2.18 2-3.74C16 5.01 " +
        "13.99 3 11.5 3S7 5.01 7 7.5c0 1.56.79 2.93 2 3.74z" +
        "m9.84 4.63l-4.54-2.26c-.17-.07-.35-.11-.54-.11H13v-6c0-.83-.67-1.5-1.5-1.5S10 6.67 10 " +
        "7.5v10.74l-3.43-.72c-.08-.01-.15-.03-.24-.03-.31 0-.59.13-.79.33l-.79.8 4.94 4.94c.27." +
        "27.65.44 1.06.44h6.79c.75 0 1.33-.55 1.44-1.28l.75-5.27c.01-.07.02-.14.02-.2 0-.62-.38" +
        "-1.16-.91-1.38z"
