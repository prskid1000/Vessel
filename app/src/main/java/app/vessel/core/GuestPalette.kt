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
