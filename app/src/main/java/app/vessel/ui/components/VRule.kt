package app.vessel.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.vessel.ui.theme.VRulePosition
import app.vessel.ui.theme.Vessel
import app.vessel.ui.theme.VesselTheme
import app.vessel.ui.theme.vFadingRule

/**
 * Nocturne's `.hr` — and the one component here that is pure signature.
 *
 * A freestanding rule does not stop at its ends; it fades to transparent over
 * 48 dp at each one, painted from the `divider` token. That single detail is
 * most of what makes a Nocturne screen recognisable, and it is the thing most
 * easily lost by reaching for `HorizontalDivider`.
 *
 * The distinction is between *freestanding* and *structural*: use this between
 * groups on a screen, where the rule is separating content. A box outline, a
 * separator inside a control, or the edge of a bar stays solid — see
 * `Modifier.vRuleAbove` / `vRuleBelow` for that.
 *
 * The vertical margin is `--space-4` (11 dp) top and bottom, as in the
 * stylesheet, so a rule between two groups spaces them as well as marks them.
 */
@Composable
fun VRule(
    modifier: Modifier = Modifier,
    color: Color = Vessel.colors.divider,
    fadeWidth: Dp = 48.dp,
    verticalMargin: Dp = Vessel.metrics.s11,
) {
    Box(
        modifier
            .fillMaxWidth()
            .padding(vertical = verticalMargin)
            .height(1.dp)
            // Centre, not bottom: the box is exactly the rule, so there is no
            // edge to hang it off.
            .vFadingRule(color, fadeWidth, VRulePosition.Center),
    )
}

@Preview(showBackground = true, backgroundColor = 0xFF161826, widthDp = 392, heightDp = 140)
@Composable
private fun VRulePreview() {
    VesselTheme {
        Column(Modifier.padding(horizontal = 18.dp)) {
            VRule()
            VRule(color = Vessel.colors.accent)
            // Narrower than two fades: degrade to one soft mark rather than
            // losing the middle.
            Box(Modifier.width(60.dp)) { VRule() }
        }
    }
}
