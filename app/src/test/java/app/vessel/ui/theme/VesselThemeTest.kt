package app.vessel.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tokens, against Nocturne's own `styles.css`.
 *
 * These are not tautologies. `docs/DESIGN.md` says the stylesheet is the source
 * of truth and this file mirrors it, which is a promise nothing else enforces —
 * a hand edit that drifts one hex digit is invisible on a phone and obvious here.
 * The spacing assertions matter more still: the whole no-hardcoded-`dp` rule is
 * worth nothing if the scale itself is wrong.
 */
class VesselThemeTest {

    private val colors = VColors()
    private val metrics = VMetrics()

    @Test
    fun `the core roles are the stylesheet's own values`() {
        assertEquals(Color(0xFF0C0C0C), colors.bg)
        assertEquals(Color(0xFF1A1D1A), colors.surface)
        assertEquals(Color(0xFFDDE3DC), colors.text)
        assertEquals(Color(0xFF8FBF7F), colors.accent)
        assertEquals(Color(0xFFA8BEA7), colors.accent2)
    }

    @Test
    fun `the neutral ramp is the OKLCH scale, all nine steps`() {
        assertEquals(
            listOf(
                Color(0xFFF2F5F1), Color(0xFFE2E8E1), Color(0xFFCCD4CB),
                Color(0xFFAFB8AE), Color(0xFF909A90), Color(0xFF727C72),
                Color(0xFF565F56), Color(0xFF3D433D), Color(0xFF282C28),
            ),
            listOf(
                colors.neutral100, colors.neutral200, colors.neutral300,
                colors.neutral400, colors.neutral500, colors.neutral600,
                colors.neutral700, colors.neutral800, colors.neutral900,
            ),
        )
    }

    @Test
    fun `the accent ramp is the OKLCH scale, all nine steps`() {
        assertEquals(
            listOf(
                Color(0xFFF1F7EF), Color(0xFFE0EDDB), Color(0xFFC9E0C0),
                Color(0xFFA9CF9B), Color(0xFF8FBF7F), Color(0xFF779F69),
                Color(0xFF5C7C51), Color(0xFF42583B), Color(0xFF2A3627),
            ),
            listOf(
                colors.accent100, colors.accent200, colors.accent300,
                colors.accent400, colors.accent500, colors.accent600,
                colors.accent700, colors.accent800, colors.accent900,
            ),
        )
    }

    @Test
    fun `the derived text tones are alphas of one colour, not new colours`() {
        assertEquals(colors.text, colors.textPrimary)
        assertEquals(colors.text.copy(alpha = 0.70f), colors.textLabel)
        assertEquals(colors.text.copy(alpha = 0.55f), colors.textMuted)
        assertEquals(colors.text.copy(alpha = 0.16f), colors.divider)
        // The pre-Nocturne aliases must stay aliases; two names for one tone is
        // fine, two tones behind one name is not.
        assertEquals(colors.textLabel, colors.textSecondary)
        assertEquals(colors.textMuted, colors.textTertiary)
        assertEquals(colors.divider, colors.border)
    }

    @Test
    fun `the architecture palette avoids the accent's own hue`() {
        // Nocturne's accent is violet, so a violet badge would stop reading as
        // information and start reading as a button. Green, blue, amber, grey.
        assertEquals(Color(0xFF9FD48C), colors.archNative)
        assertEquals(Color(0xFF8FBBD4), colors.archX64)
        assertEquals(Color(0xFFEFB080), colors.archX86)
        assertEquals(colors.neutral600, colors.archUnknown)
    }

    @Test
    fun `the rail is the only translucent surface, at 92 percent`() {
        assertEquals(colors.surface.copy(alpha = 0.92f), colors.surfaceFloating)
        assertEquals(1f, colors.surface.alpha, 0f)
    }

    @Test
    fun `the spacing scale is Nocturne's 2_8dp base rounded, and stops at 22`() {
        assertEquals(listOf(3.dp, 6.dp, 8.dp, 11.dp, 17.dp, 22.dp), metrics.scale)
        // The screen gutter is a step of the scale and not a value of its own.
        assertTrue(metrics.screenGutter in metrics.scale)
    }

    @Test
    fun `radii are 4, 8 and 14`() {
        assertEquals(4.dp, metrics.radiusSm)
        assertEquals(8.dp, metrics.radiusMd)
        assertEquals(14.dp, metrics.radiusLg)
    }

    @Test
    fun `every touch target clears the 44dp floor`() {
        // The one rule in this file that is about a person rather than a
        // stylesheet. `controlHeight` is deliberately below it — see its KDoc —
        // and everything a finger actually aims at is not.
        val targets: List<Dp> = listOf(
            metrics.touchTarget,
            metrics.tileIcon,
        )
        targets.forEach { assertTrue("$it is under the 44 dp touch floor", it >= 44.dp) }
    }

    @Test
    fun `the toggle thumb stays inside its own track`() {
        // The arithmetic that keeps the thumb from overhanging the right edge
        // when the geometry is retuned.
        assertEquals(
            metrics.toggleWidth - metrics.toggleThumb - metrics.toggleInset * 2,
            metrics.toggleTravel,
        )
        assertTrue(metrics.toggleThumb + metrics.toggleInset * 2 <= metrics.toggleHeight)
    }

    @Test
    fun `landscape is handled by one cap, and the dialog caps tighter`() {
        // This phone is 927 dp across in landscape. Both numbers are well under
        // it, which is the whole of the landscape adaptation.
        assertTrue(metrics.contentMaxWidth < 927.dp)
        assertTrue(metrics.dialogMaxWidth < metrics.contentMaxWidth)
    }

    @Test
    fun `elevation is a ring drawn from the ramp`() {
        // `--shadow-sm` is `0 0 0 1px #3d433d` and nothing else; md and lg add
        // ambient darkness behind a lighter ring.
        assertEquals(colors.neutral800, VElevation(colors.neutral800).ring)
        assertEquals(0f, VElevation(colors.neutral800).ambientAlpha, 0f)
    }

    @Test
    fun `the type scale is the reference app's phone scale, not the CSS`() {
        val type = VType()
        assertEquals(21f, type.title.fontSize.value, 0f)
        assertEquals(17f, type.subtitle.fontSize.value, 0f)
        assertEquals(14f, type.cardTitle.fontSize.value, 0f)
        assertEquals(13.5f, type.body.fontSize.value, 0f)
        assertEquals(12f, type.bodySmall.fontSize.value, 0f)
        assertEquals(11f, type.label.fontSize.value, 0f)
        assertEquals(10f, type.overline.fontSize.value, 0f)
        assertEquals(12.5f, type.control.fontSize.value, 0f)
        assertEquals(11.5f, type.mono.fontSize.value, 0f)
        assertEquals(10f, type.monoSmall.fontSize.value, 0f)
        assertEquals(17f, type.metric.fontSize.value, 0f)
        assertEquals(13f, type.metricSmall.fontSize.value, 0f)
    }
}
