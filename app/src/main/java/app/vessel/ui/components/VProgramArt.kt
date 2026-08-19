package app.vessel.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import app.vessel.ui.theme.VIconCanvas

/**
 * Colour icons for the Wine programs that ship none.
 *
 * **Why this file exists at all**, since extracting a program's own icon is
 * always better than drawing one: four of them have nothing to extract. Measured
 * against the Wine tree this project builds, by counting `.ico` files and `ICON`
 * resources under `programs/`:
 *
 *     winecfg   2 ico    taskmgr   3 ico    regedit  6 ico
 *     notepad   1 ico    winemine  1 ico
 *     explorer  0        control   0        write    0    oleview  0
 *
 * The first five resolve through [rememberBuiltInIcon] and never reach this
 * file. The last four have no icon resource in Wine at all, so the launcher drew
 * three of them — `control`, `write`, `oleview` — with the same generic document
 * glyph, and they were indistinguishable from each other.
 *
 * **These are Vessel's marks, not Microsoft's, and that is deliberate.** A folder
 * that apes Explorer's real icon would be both a copy of someone else's artwork
 * and a promise this shell cannot keep — the program behind it is Wine's
 * `explorer.exe`, not Windows'. So each is drawn plainly from the flat shapes
 * that name what the program does: a folder, two sliders, a page, two stacked
 * objects.
 *
 * **Not [VIcons], because these are not glyphs.** Everything in that file is one
 * black path that `Icon` tints at the call site; everything here is several paths
 * carrying their own fills, drawn with `Image` so the colour survives. Same 256
 * grid and same 24 dp box, so the two are interchangeable in a layout even though
 * they are not interchangeable in how they are painted.
 *
 * A program that later grows a real icon upstream needs no change here: the
 * bitmap path is checked first and wins.
 */
object VProgramArt {

    /** `explorer.exe` — a folder, because that is the whole of what it opens. */
    val Files: ImageVector by lazy {
        art(
            "vessel.art.files",
            AMBER_DEEP to "M20,60 H98 L124,90 H236 V206 H20 Z",
            AMBER to "M20,104 H236 V206 H20 Z",
        )
    }

    /** `control.exe` — two sliders, the one shape every settings panel shares. */
    val Control: ImageVector by lazy {
        art(
            "vessel.art.control",
            SLATE to "M28,44 H228 V212 H28 Z",
            STEEL to "M56,94 H200 V108 H56 Z",
            ACCENT to "M88,80 H116 V122 H88 Z",
            STEEL to "M56,150 H200 V164 H56 Z",
            ACCENT to "M144,136 H172 V178 H144 Z",
        )
    }

    /** `write.exe` — a page with a turned corner and three lines of text. */
    val Write: ImageVector by lazy {
        art(
            "vessel.art.write",
            PAPER to "M56,28 H158 L200,70 V228 H56 Z",
            PAPER_FOLD to "M158,28 L200,70 H158 Z",
            INK to "M80,110 H176 V124 H80 Z",
            INK to "M80,146 H176 V160 H80 Z",
            INK to "M80,182 H138 V196 H80 Z",
        )
    }

    /** `oleview.exe` — two stacked objects, which is what a COM browser lists. */
    val OleView: ImageVector by lazy {
        art(
            "vessel.art.oleview",
            VIOLET_DEEP to "M36,36 H148 V148 H36 Z",
            VIOLET to "M100,100 H220 V220 H100 Z",
        )
    }

    /**
     * The art for a program, by executable name, or null for one that has none.
     *
     * Lowercased and extension-tolerant: the taskbar asks with whatever
     * `WM_CLASS` carried, and `patches/wine/0058` is what makes that the real
     * file name rather than `steam_proton`.
     */
    fun forProgram(program: String): ImageVector? =
        when (program.trim().lowercase().removeSuffix(".exe")) {
            "explorer" -> Files
            "control" -> Control
            "write" -> Write
            "oleview" -> OleView
            else -> null
        }

    /** One vector, several filled subpaths, in the order they paint. */
    private fun art(name: String, vararg paths: Pair<Color, String>): ImageVector {
        val builder = ImageVector.Builder(
            name = name,
            defaultWidth = VIconCanvas,
            defaultHeight = VIconCanvas,
            viewportWidth = ART_GRID,
            viewportHeight = ART_GRID,
        )
        paths.forEach { (colour, data) ->
            builder.addPath(pathData = addPathNodes(data), fill = SolidColor(colour))
        }
        return builder.build()
    }
}

/** The same grid VIcons uses, so the two sit at one size. */
private const val ART_GRID = 256f

private val AMBER = Color(0xFFE8A33D)
private val AMBER_DEEP = Color(0xFFB8801F)
private val SLATE = Color(0xFF3B4252)
private val STEEL = Color(0xFF8FA1BF)
private val ACCENT = Color(0xFF7C6FF0)
private val PAPER = Color(0xFFE8E8EA)
private val PAPER_FOLD = Color(0xFFB9BAC4)
private val INK = Color(0xFF6B7A99)
private val VIOLET = Color(0xFF8B7BF7)
private val VIOLET_DEEP = Color(0xFF5B4FD6)
