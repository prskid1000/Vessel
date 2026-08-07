package app.vessel.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import app.vessel.ui.theme.Vessel
import app.vessel.ui.theme.VesselTheme
import app.vessel.ui.theme.vRing

/**
 * Which of Nocturne's four `.tag-*` forms a tag takes.
 *
 * The ramps are paired deliberately: an `-800` ground under `-100` text is dark
 * enough to sit on `surface` without becoming a button, and light enough that
 * the label is unambiguous. Picking a mid-ramp step for either side is what
 * makes a tag start reading as a tappable chip.
 *
 * [Ok] and [Danger] are the two status forms, and they are here because a
 * session that crashed has to say so in the colour the rest of the product uses
 * for danger — a `crashed` tag in the neutral ramp is a fact the eye skips over
 * in a list whose whole purpose is finding the run that went wrong. Nocturne has
 * no `-800` step for the status tokens, so the ground is the token itself at low
 * alpha and the ink is the token; that is the same derivation `divider` and the
 * button tints already use, rather than a new colour.
 */
enum class VTagTone { Accent, Accent2, Neutral, Outline, Ok, Danger }

/**
 * `.tag` — a small non-interactive label.
 *
 * Not a button and not a badge for machine facts: an architecture is
 * [VArchBadge], because that colour is functional and must survive. This is for
 * categorical labels — a build target, a section name, a state word.
 */
@Composable
fun VTag(
    text: String,
    modifier: Modifier = Modifier,
    tone: VTagTone = VTagTone.Neutral,
) {
    val colors = Vessel.colors
    val shape = Vessel.metrics.shapeTag
    val ground = when (tone) {
        VTagTone.Accent -> colors.accent800
        VTagTone.Accent2 -> colors.accent2800
        VTagTone.Neutral -> colors.neutral800
        VTagTone.Outline -> Color.Transparent
        VTagTone.Ok -> colors.ok.copy(alpha = STATUS_GROUND_ALPHA)
        VTagTone.Danger -> colors.danger.copy(alpha = STATUS_GROUND_ALPHA)
    }
    val ink = when (tone) {
        VTagTone.Accent -> colors.accent100
        VTagTone.Accent2 -> colors.accent2100
        VTagTone.Neutral -> colors.neutral100
        VTagTone.Outline -> colors.accent
        VTagTone.Ok -> colors.ok
        VTagTone.Danger -> colors.danger
    }

    Text(
        text,
        // `.tag` is 11px with `letter-spacing: 0.02em`; `overline` is the 11 sp
        // step, and the tag does not take the uppercase or the 0.08em with it.
        style = Vessel.type.overline.copy(letterSpacing = 0.02.em),
        color = ink,
        modifier = modifier
            .background(ground, shape)
            .let { if (tone == VTagTone.Outline) it.vRing(colors.accent, shape) else it }
            // `padding: 3px 10px`
            .padding(horizontal = 10.dp, vertical = 3.dp),
    )
}

/**
 * How far a status token is knocked back to become a ground.
 *
 * The same 16% the `divider` token sits at, which is the alpha in this system
 * that means "present, not loud".
 */
private const val STATUS_GROUND_ALPHA = 0.16f

@Preview(showBackground = true, backgroundColor = 0xFF161826, widthDp = 392)
@Composable
private fun VTagPreview() {
    VesselTheme {
        Row(
            Modifier.padding(18.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            VTagTone.entries.forEach { VTag(it.name.lowercase(), tone = it) }
        }
    }
}
