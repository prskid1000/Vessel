package app.vessel.core

/**
 * Nocturne, as plain integers, for the code that has to dress the *guest*.
 *
 * These are transcribed from `ui/theme/VesselTheme.kt` — same values, same
 * names — and the duplication is deliberate. That file's tokens are
 * `androidx.compose.ui.graphics.Color`, and [PrefixRegistry] is pure data that
 * renders a `.reg` file: pulling Compose into it would make the registry seed
 * untestable on the JVM to save nine constants. `GuestPaletteTest` pins every
 * value, so the two cannot drift silently.
 *
 * `docs/DESIGN.md` already describes this shape of mirroring — Nocturne's own
 * source of truth is the On-Device AI app's stylesheet, `VesselTheme` mirrors
 * that, and this mirrors `VesselTheme`. Retune upstream, then follow the chain
 * down.
 *
 * Only the tokens the Windows system-colour set can actually use are here. There
 * is no point mirroring the architecture palette: nothing in a Win32 window is
 * coloured by whether the binary is ARM64.
 */
object GuestPalette {

    /** `bg` — the window ground. */
    const val BG: Int = 0xFF161826.toInt()

    /**
     * The console's ground, and deliberately **not** [BG].
     *
     * A console is a near-black box on the themed desktop, not a pane of it. It
     * was black for months by accident -- conhost fills its buffer with the
     * built-in 0x000F before any configuration is read, and nothing repainted
     * those cells when `ScreenColors` was applied, so the theming was inert and
     * black came through. `patches/wine/0052` makes the attribute reach existing
     * cells, which made the theme take effect for the first time and turned the
     * console into a navy pane that blended into the desktop behind it.
     *
     * **This is the one value here that is not Nocturne's**, so do not go looking
     * for it in `VesselTheme.kt`: it is the console scheme's slot 0, `1E1E1E`,
     * which is Cursor's terminal background. Seed 30 gave
     * the console the whole Campbell scheme rather than two retuned entries --
     * `patches/wine/0052` quantises `38;5;n` and `38;2;r;g;b` to the nearest of
     * sixteen, so the sixteen have to be a coherent set -- and this constant is
     * what names slot 0 in it. It was pure black before that, and the two
     * differ by 12/255 on each channel; agreeing with the scheme is worth more than
     * a deviation nobody can see and nobody could later justify.
     *
     * **It is entry 0 now, where it used to be entry 8.** Seed 26 put the ground on
     * slot 8 so `ScreenColors 0x87` would select it; that made slot 8 do double duty
     * as "the background", and Claude Code's most frequent colour quantises to
     * exactly slot 8 -- 297 sequences measured in the device's `vt-trace.log`,
     * mostly `38;5;238`, which is RGB 68,68,68. Dark grey text on a dark grey ground
     * is invisible text. `PrefixRegistry.consoleColours` has the whole measurement.
     * The rim reasoning that used to be here is dropped rather than carried
     * forward: `wndclass.hbrBackground` is `GetStockObject(BLACK_BRUSH)`
     * (`programs/conhost/window.c:1927`), so what is painted outside the blit comes
     * from the window class and not from a palette entry. Nothing in this value
     * depends on which it was.
     */
    const val CONSOLE_BG: Int = 0xFF1E1E1E.toInt()

    /** `surface` — cards, sheets, bars; here, dialogs and menus. */
    const val SURFACE: Int = 0xFF232532.toInt()

    /** `text` — primary text. */
    const val TEXT: Int = 0xFFE9E9ED.toInt()

    /** `accent` — the one thing in the guest allowed to be violet. */
    const val ACCENT: Int = 0xFF9184D9.toInt()

    /** `accent-800` — Nocturne's tag fill, and so the selection ground. */
    const val ACCENT_800: Int = 0xFF423A6A.toInt()

    /** `accent-100` — what sits on [ACCENT_800]. */
    const val ACCENT_100: Int = 0xFFF5F4FF.toInt()

    /** `neutral-500` — muted text that still has to be read. */
    const val NEUTRAL_500: Int = 0xFF9397AB.toInt()

    /** `neutral-600` — `textMuted`'s opaque equivalent; disabled text. */
    const val NEUTRAL_600: Int = 0xFF75798C.toInt()

    /** `neutral-700` — `borderStrong`, the edge of something touchable. */
    const val NEUTRAL_700: Int = 0xFF595D6C.toInt()

    /** `neutral-800` — `elevSm`, a hairline ring. */
    const val NEUTRAL_800: Int = 0xFF3F424D.toInt()

    /** `neutral-900` — `surfaceRaised` and `surfaceSunken`, which are the same. */
    const val NEUTRAL_900: Int = 0xFF292B31.toInt()
}
