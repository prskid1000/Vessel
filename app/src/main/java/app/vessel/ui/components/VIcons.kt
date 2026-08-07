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
