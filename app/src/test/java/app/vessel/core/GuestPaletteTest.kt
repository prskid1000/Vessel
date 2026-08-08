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
        assertEquals(0xFF161826.toInt(), GuestPalette.BG)
        assertEquals(0xFF232532.toInt(), GuestPalette.SURFACE)
        assertEquals(0xFFE9E9ED.toInt(), GuestPalette.TEXT)
        assertEquals(0xFF9184D9.toInt(), GuestPalette.ACCENT)
    }

    @Test
    fun `the neutral ramp matches VesselTheme`() {
        assertEquals(0xFF9397AB.toInt(), GuestPalette.NEUTRAL_500)
        assertEquals(0xFF75798C.toInt(), GuestPalette.NEUTRAL_600)
        assertEquals(0xFF595D6C.toInt(), GuestPalette.NEUTRAL_700)
        assertEquals(0xFF3F424D.toInt(), GuestPalette.NEUTRAL_800)
        assertEquals(0xFF292B31.toInt(), GuestPalette.NEUTRAL_900)
    }

    @Test
    fun `the accent ramp matches VesselTheme`() {
        assertEquals(0xFF423A6A.toInt(), GuestPalette.ACCENT_800)
        assertEquals(0xFFF5F4FF.toInt(), GuestPalette.ACCENT_100)
    }

    @Test
    fun `the Windows desktop is painted the app's own window ground`() {
        // Not a fallback any more — the wallpaper feature is gone, and this is
        // simply what colour the desktop is. A colour lifted from the app's own
        // palette reads as a Vessel decision; a plausible teal would read as an
        // image that failed to load.
        assertEquals(
            GuestPalette.BG,
            0xFF161826.toInt(),
        )
        assertEquals(
            rgbTriplet(GuestPalette.BG),
            PrefixRegistry.desktopTheme.values.single { it.name == "Background" }.data,
        )
    }

    @Test
    fun `every token is opaque, because a registry triplet has nowhere to put alpha`() {
        val tokens = listOf(
            GuestPalette.BG, GuestPalette.SURFACE, GuestPalette.TEXT, GuestPalette.ACCENT,
            GuestPalette.ACCENT_800, GuestPalette.ACCENT_100, GuestPalette.NEUTRAL_500,
            GuestPalette.NEUTRAL_600, GuestPalette.NEUTRAL_700, GuestPalette.NEUTRAL_800,
            GuestPalette.NEUTRAL_900,
        )
        assertTrue(tokens.all { (it ushr 24) == 0xFF })
    }
}
