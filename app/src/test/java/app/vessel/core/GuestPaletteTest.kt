package app.vessel.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The mirror between `ui/theme/VesselTheme.kt` and the guest's palette.
 *
 * These constants exist twice because [PrefixRegistry] is pure data and
 * `VesselTheme`'s tokens are Compose `Color`s — the alternative was dragging
 * Compose into the registry seed and losing its JVM tests. The duplication is
 * only safe if it is pinned, so every value below is transcribed from
 * `VesselTheme.kt` by hand and this file is what fails when one of them moves.
 * `docs/DESIGN.md` names the same values.
 */
class GuestPaletteTest {

    @Test
    fun `the core tokens match VesselTheme`() {
        assertEquals(0xFF0C0C0C.toInt(), GuestPalette.BG)
        assertEquals(0xFF1A1D1A.toInt(), GuestPalette.SURFACE)
        assertEquals(0xFFDDE3DC.toInt(), GuestPalette.TEXT)
        assertEquals(0xFF8FBF7F.toInt(), GuestPalette.ACCENT)
    }

    @Test
    fun `the neutral ramp matches VesselTheme`() {
        assertEquals(0xFF909A90.toInt(), GuestPalette.NEUTRAL_500)
        assertEquals(0xFF727C72.toInt(), GuestPalette.NEUTRAL_600)
        assertEquals(0xFF565F56.toInt(), GuestPalette.NEUTRAL_700)
        assertEquals(0xFF3D433D.toInt(), GuestPalette.NEUTRAL_800)
        assertEquals(0xFF282C28.toInt(), GuestPalette.NEUTRAL_900)
    }

    @Test
    fun `the accent ramp matches VesselTheme`() {
        assertEquals(0xFF42583B.toInt(), GuestPalette.ACCENT_800)
        assertEquals(0xFFF1F7EF.toInt(), GuestPalette.ACCENT_100)
    }

    @Test
    fun `the Windows desktop is painted the app's own window ground`() {
        // Not a fallback any more — the wallpaper feature is gone, and this is
        // simply what colour the desktop is. A colour lifted from the app's own
        // palette reads as a Vessel decision; a plausible teal would read as an
        // image that failed to load.
        assertEquals(
            GuestPalette.BG,
            0xFF0C0C0C.toInt(),
        )
        assertEquals(
            rgbTriplet(GuestPalette.BG),
            PrefixRegistry.desktopTheme.values.single { it.name == "Background" }.data,
        )
    }

    @Test
    fun `the console ground is the terminal scheme's slot 0, not a VesselTheme token`() {
        // The one constant in GuestPalette that is deliberately not transcribed
        // from VesselTheme.kt, so this test is the thing that stops someone
        // "fixing" it back to a Nocturne value. Seed 30 gave the console the whole
        // Campbell scheme -- `patches/wine/0052` quantises 38;5;n and 38;2;r;g;b to
        // the nearest of sixteen, so a half-retuned set makes "nearest" wrong -- and
        // this names slot 0 in it. It was 0xFF000000 up to seed 29 and Campbell's
        // 0C0C0C from 30; seed 34 moved the scheme to Cursor's terminal colours,
        // which is VS Code's Dark Modern, and 1E1E1E is its background.
        assertEquals(0xFF1E1E1E.toInt(), GuestPalette.CONSOLE_BG)
    }

    @Test
    fun `every token is opaque, because a registry triplet has nowhere to put alpha`() {
        val tokens = listOf(
            GuestPalette.BG, GuestPalette.SURFACE, GuestPalette.TEXT, GuestPalette.ACCENT,
            GuestPalette.ACCENT_800, GuestPalette.ACCENT_100, GuestPalette.NEUTRAL_500,
            GuestPalette.NEUTRAL_600, GuestPalette.NEUTRAL_700, GuestPalette.NEUTRAL_800,
            GuestPalette.NEUTRAL_900, GuestPalette.CONSOLE_BG,
        )
        assertTrue(tokens.all { (it ushr 24) == 0xFF })
    }
}
