package app.vessel.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import app.vessel.ui.theme.VIconCanvas

/**
 * Phosphor, hand-built.
 *
 * `docs/DESIGN.md` and the Nocturne guide both say Phosphor, and Phosphor has no
 * Compose artifact — the options are a Material stand-in, an icon font, or the
 * path data. This file is the path data: each constant below is the `d` attribute
 * of the corresponding `assets/regular/<name>.svg` in `@phosphor-icons/core`
 * 2.1.1, transcribed unaltered.
 *
 * **The viewport is 256, not 24.** Phosphor draws on a 256-unit grid; Material
 * draws on 24. Building these at `viewportWidth = 24f` would scale every glyph to
 * a tenth of its box and render as a speck in the corner of the button — the kind
 * of failure that looks like a missing icon rather than a wrong number. The
 * `defaultWidth`/`defaultHeight` stay at 24 dp so an icon of ours and an icon of
 * Material's are interchangeable at every call site, including the tint, which
 * `Icon` applies as a colour filter over the black fill.
 *
 * Regular weight throughout: Phosphor's regular is a 16-unit stroke on the 256
 * grid, which at the 18 dp this product draws icons is the weight that sits
 * beside Inter 400 without shouting. Bold is for 12 dp and below, and nothing
 * here is.
 *
 * Why not `material-icons-extended`: it is several thousand glyphs and about a
 * megabyte of dex, for the dozen used here.
 *
 * Licence: Phosphor is MIT. See `CREDITS.md`.
 */
object VIcons {

    // — navigation and structure ---------------------------------------------

    /** `arrow-left` — back, on every pushed screen. */
    val ArrowLeft: ImageVector by lazy { glyph("phosphor.arrowLeft", ARROW_LEFT) }

    /** `caret-down` — a dropdown field's chevron. */
    val CaretDown: ImageVector by lazy { glyph("phosphor.caretDown", CARET_DOWN) }

    /** `plus` — new container, add a program, and the empty state's glyph. */
    val Plus: ImageVector by lazy { glyph("phosphor.plus", PLUS) }

    /** `x` — Stop, and a failed checklist step. */
    val X: ImageVector by lazy { glyph("phosphor.x", X_MARK) }

    /** `check` — Save, and a completed checklist step. */
    val Check: ImageVector by lazy { glyph("phosphor.check", CHECK) }

    /** `dots-three-vertical` — the overflow. */
    val DotsThreeVertical: ImageVector by lazy { glyph("phosphor.dotsThree", DOTS_THREE_VERTICAL) }

    // — actions ---------------------------------------------------------------

    /** `play` — launch a container or a program. */
    val Play: ImageVector by lazy { glyph("phosphor.play", PLAY) }

    /** `pause` — `SIGSTOP` the guest's process tree. */
    val Pause: ImageVector by lazy { glyph("phosphor.pause", PAUSE) }

    /** `arrow-clockwise` — Retry, after a failed launch. */
    val ArrowClockwise: ImageVector by lazy { glyph("phosphor.arrowClockwise", ARROW_CLOCKWISE) }

    /** `trash` — delete a container, or clear a container's logs. */
    val Trash: ImageVector by lazy { glyph("phosphor.trash", TRASH) }

    /** `copy` — copy a whole log to the clipboard. */
    val Copy: ImageVector by lazy { glyph("phosphor.copy", COPY) }

    /** `share-network` — send the log file. */
    val Share: ImageVector by lazy { glyph("phosphor.share", SHARE_NETWORK) }

    /** `download-simple` — import a file into a container's drive. */
    val Import: ImageVector by lazy { glyph("phosphor.import", DOWNLOAD_SIMPLE) }

    /** `upload-simple` — export a file out to Android storage. */
    val Export: ImageVector by lazy { glyph("phosphor.export", UPLOAD_SIMPLE) }

    // — objects ---------------------------------------------------------------

    /** `folder` — a directory, and the Files action on a container row. */
    val Folder: ImageVector by lazy { glyph("phosphor.folder", FOLDER) }

    /** `folder-open` — the parent entry, and a shared Android folder. */
    val FolderOpen: ImageVector by lazy { glyph("phosphor.folderOpen", FOLDER_OPEN) }

    /** `file` — an ordinary file in the browser. */
    val File: ImageVector by lazy { glyph("phosphor.file", FILE) }

    /** `file-code` — a PE executable, tinted by its architecture. */
    val FileCode: ImageVector by lazy { glyph("phosphor.fileCode", FILE_CODE) }

    /**
     * `terminal-window` — Command Prompt, the one shell Wine always provides.
     *
     * A framed console, against [Prompt]'s bare one. The three shells in the
     * launcher share a family on purpose — they are three of the same kind of
     * thing — and are told apart by whether the mark has a window around it, by
     * where its chevron sits, and by the three-character caption beneath.
     */
    val Terminal: ImageVector by lazy { glyph("phosphor.terminalWindow", TERMINAL_WINDOW) }

    /** `terminal` — a bare `>_` prompt. PowerShell in the launcher. */
    val Prompt: ImageVector by lazy { glyph("phosphor.terminal", TERMINAL) }

    /** `code` — `</>`. The POSIX shell, which is the one people write in. */
    val Code: ImageVector by lazy { glyph("phosphor.code", CODE) }

    /** `list-bullets` — the session log, and its history. */
    val List: ImageVector by lazy { glyph("phosphor.list", LIST_BULLETS) }

    /** `keyboard` — raise the IME over a running desktop. */
    val Keyboard: ImageVector by lazy { glyph("phosphor.keyboard", KEYBOARD) }

    /** `cursor` — the pointer control, when it is about to become a trackpad. */
    val Cursor: ImageVector by lazy { glyph("phosphor.cursor", CURSOR) }

    /** `hand-pointing` — the same control, when it is about to become direct touch. */
    val HandPointing: ImageVector by lazy { glyph("phosphor.handPointing", HAND_POINTING) }

    /** `monitor` — the desktop a container starts. */
    val Monitor: ImageVector by lazy { glyph("phosphor.monitor", MONITOR) }

    /**
     * `corners-out` — the taskbar menu's move/resize toggle.
     *
     * Four corner brackets, which is literally what the toggle puts on screen:
     * the guest has no caption to drag since `patches/wine/0010`, so the shell
     * draws its own handles at the corners and edges instead.
     */
    val CornersOut: ImageVector by lazy { glyph("phosphor.cornersOut", CORNERS_OUT) }

    /** `info` — an empty state that is reporting rather than inviting. */
    val Info: ImageVector by lazy { glyph("phosphor.info", INFO) }

    /** `warning` — a refusal that is not an error. */
    val Warning: ImageVector by lazy { glyph("phosphor.warning", WARNING) }

    private fun glyph(name: String, pathData: String): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = VIconCanvas,
            defaultHeight = VIconCanvas,
            viewportWidth = PHOSPHOR_GRID,
            viewportHeight = PHOSPHOR_GRID,
        ).addPath(
            pathData = addPathNodes(pathData),
            fill = SolidColor(Color.Black),
        ).build()
}

/** Phosphor's own grid. Every path below is authored against it. */
private const val PHOSPHOR_GRID = 256f

private const val ARROW_LEFT =
    "M224,128a8,8,0,0,1-8,8H59.31l58.35,58.34a8,8,0,0,1-11.32,11.32l-72-72a8,8,0,0,1,0-11.32l72-72a8," +
        "8,0,0,1,11.32,11.32L59.31,120H216A8,8,0,0,1,224,128Z"

private const val CARET_DOWN =
    "M213.66,101.66l-80,80a8,8,0,0,1-11.32,0l-80-80A8,8,0,0,1,53.66,90.34L128,164.69l74.34-74.35a8,8," +
        "0,0,1,11.32,11.32Z"

private const val PLUS =
    "M224,128a8,8,0,0,1-8,8H136v80a8,8,0,0,1-16,0V136H40a8,8,0,0,1,0-16h80V40a8,8,0,0,1,16,0v80h80A8," +
        "8,0,0,1,224,128Z"

private const val X_MARK =
    "M205.66,194.34a8,8,0,0,1-11.32,11.32L128,139.31,61.66,205.66a8,8,0,0,1-11.32-11.32L116.69,128,50" +
        ".34,61.66A8,8,0,0,1,61.66,50.34L128,116.69l66.34-66.35a8,8,0,0,1,11.32,11.32L139.31,128Z"

private const val CHECK =
    "M229.66,77.66l-128,128a8,8,0,0,1-11.32,0l-56-56a8,8,0,0,1,11.32-11.32L96,188.69,218.34,66.34a8,8" +
        ",0,0,1,11.32,11.32Z"

private const val DOTS_THREE_VERTICAL =
    "M140,128a12,12,0,1,1-12-12A12,12,0,0,1,140,128ZM128,72a12,12,0,1,0-12-12A12,12,0,0,0,128,72Zm0,1" +
        "12a12,12,0,1,0,12,12A12,12,0,0,0,128,184Z"

private const val PLAY =
    "M232.4,114.49,88.32,26.35a16,16,0,0,0-16.2-.3A15.86,15.86,0,0,0,64,39.87V216.13A15.94,15.94,0,0," +
        "0,80,232a16.07,16.07,0,0,0,8.36-2.35L232.4,141.51a15.81,15.81,0,0,0,0-27ZM80,215.94V40l143.83" +
        ",88Z"

private const val PAUSE =
    "M200,32H160a16,16,0,0,0-16,16V208a16,16,0,0,0,16,16h40a16,16,0,0,0,16-16V48A16,16,0,0,0,200,32Zm" +
        "0,176H160V48h40ZM96,32H56A16,16,0,0,0,40,48V208a16,16,0,0,0,16,16H96a16,16,0,0,0,16-16V48A16," +
        "16,0,0,0,96,32Zm0,176H56V48H96Z"

private const val ARROW_CLOCKWISE =
    "M240,56v48a8,8,0,0,1-8,8H184a8,8,0,0,1,0-16H211.4L184.81,71.64l-.25-.24a80,80,0,1,0-1.67,114.78," +
        "8,8,0,0,1,11,11.63A95.44,95.44,0,0,1,128,224h-1.32A96,96,0,1,1,195.75,60L224,85.8V56a8,8,0,1," +
        "1,16,0Z"

private const val TRASH =
    "M216,48H176V40a24,24,0,0,0-24-24H104A24,24,0,0,0,80,40v8H40a8,8,0,0,0,0,16h8V208a16,16,0,0,0,16," +
        "16H192a16,16,0,0,0,16-16V64h8a8,8,0,0,0,0-16ZM96,40a8,8,0,0,1,8-8h48a8,8,0,0,1,8,8v8H96Zm96,1" +
        "68H64V64H192ZM112,104v64a8,8,0,0,1-16,0V104a8,8,0,0,1,16,0Zm48,0v64a8,8,0,0,1-16,0V104a8,8,0," +
        "0,1,16,0Z"

private const val COPY =
    "M216,32H88a8,8,0,0,0-8,8V80H40a8,8,0,0,0-8,8V216a8,8,0,0,0,8,8H168a8,8,0,0,0,8-8V176h40a8,8,0,0," +
        "0,8-8V40A8,8,0,0,0,216,32ZM160,208H48V96H160Zm48-48H176V88a8,8,0,0,0-8-8H96V48H208Z"

private const val SHARE_NETWORK =
    "M176,160a39.89,39.89,0,0,0-28.62,12.09l-46.1-29.63a39.8,39.8,0,0,0,0-28.92l46.1-29.63a40,40,0,1," +
        "0-8.66-13.45l-46.1,29.63a40,40,0,1,0,0,55.82l46.1,29.63A40,40,0,1,0,176,160Zm0-128a24,24,0,1," +
        "1-24,24A24,24,0,0,1,176,32ZM64,152a24,24,0,1,1,24-24A24,24,0,0,1,64,152Zm112,72a24,24,0,1,1,2" +
        "4-24A24,24,0,0,1,176,224Z"

private const val DOWNLOAD_SIMPLE =
    "M224,144v64a8,8,0,0,1-8,8H40a8,8,0,0,1-8-8V144a8,8,0,0,1,16,0v56H208V144a8,8,0,0,1,16,0Zm-101.66" +
        ",5.66a8,8,0,0,0,11.32,0l40-40a8,8,0,0,0-11.32-11.32L136,124.69V32a8,8,0,0,0-16,0v92.69L93.66," +
        "98.34a8,8,0,0,0-11.32,11.32Z"

private const val UPLOAD_SIMPLE =
    "M224,144v64a8,8,0,0,1-8,8H40a8,8,0,0,1-8-8V144a8,8,0,0,1,16,0v56H208V144a8,8,0,0,1,16,0ZM93.66,7" +
        "7.66,120,51.31V144a8,8,0,0,0,16,0V51.31l26.34,26.35a8,8,0,0,0,11.32-11.32l-40-40a8,8,0,0,0-11" +
        ".32,0l-40,40A8,8,0,0,0,93.66,77.66Z"

private const val TERMINAL_WINDOW =
    "M128,128a8,8,0,0,1-3,6.25l-40,32a8,8,0,1,1-10-12.5L107.19,128,75,102.25a8,8,0,1,1,10-12.5l40,32A" +
        "8,8,0,0,1,128,128Zm48,24H136a8,8,0,0,0,0,16h40a8,8,0,0,0,0-16Zm56-96V200a16,16,0,0,1-16,16H" +
        "40a16,16,0,0,1-16-16V56A16,16,0,0,1,40,40H216A16,16,0,0,1,232,56ZM216,200V56H40V200H216Z"

private const val TERMINAL =
    "M117.31,134l-72,64a8,8,0,1,1-10.63-12L100,128,34.69,70A8,8,0,1,1,45.32,58l72,64a8,8,0,0,1,0,12ZM" +
        "216,184H120a8,8,0,0,0,0,16h96a8,8,0,0,0,0-16Z"

private const val CODE =
    "M69.12,94.15,28.5,128l40.62,33.85a8,8,0,1,1-10.24,12.29l-48-40a8,8,0,0,1,0-12.29l48-40a8,8,0,0,1" +
        ",10.24,12.3Zm176,27.7-48-40a8,8,0,1,0-10.24,12.3L227.5,128l-40.62,33.85a8,8,0,1,0,10.24,12." +
        "29l48-40a8,8,0,0,0,0-12.29ZM162.73,32.48a8,8,0,0,0-10.25,4.79l-64,176a8,8,0,0,0,4.79,10.26A" +
        "8.14,8.14,0,0,0,96,224a8,8,0,0,0,7.52-5.27l64-176A8,8,0,0,0,162.73,32.48Z"

private const val FOLDER =
    "M216,72H131.31L104,44.69A15.86,15.86,0,0,0,92.69,40H40A16,16,0,0,0,24,56V200.62A15.4,15.4,0,0,0," +
        "39.38,216H216.89A15.13,15.13,0,0,0,232,200.89V88A16,16,0,0,0,216,72ZM40,56H92.69l16,16H40ZM21" +
        "6,200H40V88H216Z"

private const val FOLDER_OPEN =
    "M245,110.64A16,16,0,0,0,232,104H216V88a16,16,0,0,0-16-16H130.67L102.94,51.2a16.14,16.14,0,0,0-9." +
        "6-3.2H40A16,16,0,0,0,24,64V208h0a8,8,0,0,0,8,8H211.1a8,8,0,0,0,7.59-5.47l28.49-85.47A16.05,16" +
        ".05,0,0,0,245,110.64ZM93.34,64,123.2,86.4A8,8,0,0,0,128,88h72v16H69.77a16,16,0,0,0-15.18,10." +
        "94L40,158.7V64Zm112,136H43.1l26.67-80H232Z"

private const val FILE =
    "M213.66,82.34l-56-56A8,8,0,0,0,152,24H56A16,16,0,0,0,40,40V216a16,16,0,0,0,16,16H200a16,16,0,0,0" +
        ",16-16V88A8,8,0,0,0,213.66,82.34ZM160,51.31,188.69,80H160ZM200,216H56V40h88V88a8,8,0,0,0,8,8h" +
        "48V216Z"

private const val FILE_CODE =
    "M181.66,146.34a8,8,0,0,1,0,11.32l-24,24a8,8,0,0,1-11.32-11.32L164.69,152l-18.35-18.34a8,8,0,0,1," +
        "11.32-11.32Zm-72-24a8,8,0,0,0-11.32,0l-24,24a8,8,0,0,0,0,11.32l24,24a8,8,0,0,0,11.32-11.32L9" +
        "1.31,152l18.35-18.34A8,8,0,0,0,109.66,122.34ZM216,88V216a16,16,0,0,1-16,16H56a16,16,0,0,1-16-" +
        "16V40A16,16,0,0,1,56,24h96a8,8,0,0,1,5.66,2.34l56,56A8,8,0,0,1,216,88Zm-56-8h28.69L160,51.31Z" +
        "m40,136V96H152a8,8,0,0,1-8-8V40H56V216H200Z"

private const val LIST_BULLETS =
    "M80,64a8,8,0,0,1,8-8H216a8,8,0,0,1,0,16H88A8,8,0,0,1,80,64Zm136,56H88a8,8,0,0,0,0,16H216a8,8,0,0" +
        ",0,0-16Zm0,64H88a8,8,0,0,0,0,16H216a8,8,0,0,0,0-16ZM44,52A12,12,0,1,0,56,64,12,12,0,0,0,44,52" +
        "Zm0,64a12,12,0,1,0,12,12A12,12,0,0,0,44,116Zm0,64a12,12,0,1,0,12,12A12,12,0,0,0,44,180Z"

private const val KEYBOARD =
    "M224,48H32A16,16,0,0,0,16,64V192a16,16,0,0,0,16,16H224a16,16,0,0,0,16-16V64A16,16,0,0,0,224,48Zm" +
        "0,144H32V64H224V192Zm-16-64a8,8,0,0,1-8,8H56a8,8,0,0,1,0-16H200A8,8,0,0,1,208,128Zm0-32a8,8,0" +
        ",0,1-8,8H56a8,8,0,0,1,0-16H200A8,8,0,0,1,208,96ZM72,160a8,8,0,0,1-8,8H56a8,8,0,0,1,0-16h8A8,8" +
        ",0,0,1,72,160Zm96,0a8,8,0,0,1-8,8H96a8,8,0,0,1,0-16h64A8,8,0,0,1,168,160Zm40,0a8,8,0,0,1-8,8h" +
        "-8a8,8,0,0,1,0-16h8A8,8,0,0,1,208,160Z"

private const val CURSOR =
    "M168,132.69,214.08,115l.33-.13A16,16,0,0,0,213,85.07L52.92,32.8A15.95,15.95,0,0,0,32.8,52.92L85." +
        "07,213a15.82,15.82,0,0,0,14.41,11l.78,0a15.84,15.84,0,0,0,14.61-9.59l.13-.33L132.69,168,184,2" +
        "19.31a16,16,0,0,0,22.63,0l12.68-12.68a16,16,0,0,0,0-22.63ZM195.31,208,144,156.69a16,16,0,0,0-" +
        "26,4.93c0,.11-.09.22-.13.32l-17.65,46L48,48l159.85,52.2-45.95,17.64-.32.13a16,16,0,0,0-4.93,2" +
        "6h0L208,195.31Z"

private const val HAND_POINTING =
    "M196,88a27.86,27.86,0,0,0-13.35,3.39A28,28,0,0,0,144,74.7V44a28,28,0,0,0-56,0v80l-3.82-6.13A28,2" +
        "8,0,0,0,35.73,146l4.67,8.23C74.81,214.89,89.05,240,136,240a88.1,88.1,0,0,0,88-88V116A28,28,0," +
        "0,0,196,88Zm12,64a72.08,72.08,0,0,1-72,72c-37.63,0-47.84-18-81.68-77.68l-4.69-8.27,0-.05A12,1" +
        "2,0,0,1,54,121.61a11.88,11.88,0,0,1,6-1.6,12,12,0,0,1,10.41,6,1.76,1.76,0,0,0,.14.23l18.67,3" +
        "0A8,8,0,0,0,104,152V44a12,12,0,0,1,24,0v68a8,8,0,0,0,16,0V100a12,12,0,0,1,24,0v20a8,8,0,0,0,1" +
        "6,0v-4a12,12,0,0,1,24,0Z"

private const val CORNERS_OUT =
    "M216,48V88a8,8,0,0,1-16,0V56H168a8,8,0,0,1,0-16h40A8,8,0,0,1,216,48ZM88,200H56V168a8,8,0,0,0-16," +
        "0v40a8,8,0,0,0,8,8H88a8,8,0,0,0,0-16Zm120-40a8,8,0,0,0-8,8v32H168a8,8,0,0,0,0,16h40a8,8,0,0," +
        "0,8-8V168A8,8,0,0,0,208,160ZM88,40H48a8,8,0,0,0-8,8V88a8,8,0,0,0,16,0V56H88a8,8,0,0,0,0-16Z"

private const val MONITOR =
    "M208,40H48A24,24,0,0,0,24,64V176a24,24,0,0,0,24,24H208a24,24,0,0,0,24-24V64A24,24,0,0,0,208,40Zm" +
        "8,136a8,8,0,0,1-8,8H48a8,8,0,0,1-8-8V64a8,8,0,0,1,8-8H208a8,8,0,0,1,8,8Zm-48,48a8,8,0,0,1-8,8" +
        "H96a8,8,0,0,1,0-16h64A8,8,0,0,1,168,224Z"

private const val INFO =
    "M128,24A104,104,0,1,0,232,128,104.11,104.11,0,0,0,128,24Zm0,192a88,88,0,1,1,88-88A88.1,88.1,0,0," +
        "1,128,216Zm16-40a8,8,0,0,1-8,8,16,16,0,0,1-16-16V128a8,8,0,0,1,0-16,16,16,0,0,1,16,16v40A8,8," +
        "0,0,1,144,176ZM112,84a12,12,0,1,1,12,12A12,12,0,0,1,112,84Z"

private const val WARNING =
    "M236.8,188.09,149.35,36.22h0a24.76,24.76,0,0,0-42.7,0L19.2,188.09a23.51,23.51,0,0,0,0,23.72A24.3" +
        "5,24.35,0,0,0,40.55,224h174.9a24.35,24.35,0,0,0,21.33-12.19A23.51,23.51,0,0,0,236.8,188.09ZM" +
        "222.93,203.8a8.5,8.5,0,0,1-7.48,4.2H40.55a8.5,8.5,0,0,1-7.48-4.2,7.59,7.59,0,0,1,0-7.72L120." +
        "52,44.21a8.75,8.75,0,0,1,15,0l87.45,151.87A7.59,7.59,0,0,1,222.93,203.8ZM120,144V104a8,8,0,0," +
        "1,16,0v40a8,8,0,0,1-16,0Zm20,36a12,12,0,1,1-12-12A12,12,0,0,1,140,180Z"
