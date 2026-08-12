package app.vessel.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.tooling.preview.Preview
import app.vessel.ui.theme.Vessel
import app.vessel.ui.theme.VesselTheme

/**
 * A warning line: the triangle, then the sentence, both in the warning tone.
 *
 * The glyph is not decoration on a screen whose ordinary state is a wall of muted
 * grey — it is the only thing that distinguishes a sentence the reader must act
 * on from the several around it that merely explain. Top-aligned against the
 * first line, because these wrap to two and a centred glyph beside a two-line
 * paragraph reads as a bullet.
 */
@Composable
fun VCaution(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().padding(vertical = Vessel.metrics.s3),
        horizontalArrangement = Arrangement.spacedBy(Vessel.metrics.s6),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            VIcons.Warning,
            contentDescription = null,
            tint = Vessel.colors.warn,
            modifier = Modifier.size(Vessel.metrics.iconSm).padding(top = Vessel.metrics.hairline),
        )
        Text(text, style = Vessel.type.bodySmall, color = Vessel.colors.warn)
    }
}

/**
 * A titled block that starts closed.
 *
 * **Closed is the whole point, so there is no way to ask for it open.** The
 * callers are runs of settings whose right value is the default — the shape is
 * "here are four more knobs if you came looking for them", and one that opened
 * itself would be four controls the reader has to scroll past, which is the
 * thing being fixed.
 *
 * The count is in the header rather than left to be discovered by opening it: a
 * disclosure whose size is unknown is one a reader has to open to dismiss.
 *
 * The caret turns rather than swapping glyph, because the rotation is what says
 * *this same block* changed state; two different arrows read as two controls.
 */
@Composable
fun VExpander(
    title: String,
    count: Int,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    val turn by animateFloatAsState(if (open) 180f else 0f, label = "caret")
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(Vessel.metrics.shapeMd)
                .clickable { open = !open }
                .heightIn(min = Vessel.metrics.touchTarget)
                .padding(horizontal = Vessel.metrics.s6),
            horizontalArrangement = Arrangement.spacedBy(Vessel.metrics.s6),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                title,
                style = Vessel.type.body,
                color = Vessel.colors.text,
                modifier = Modifier.weight(1f),
            )
            Text("$count", style = Vessel.type.bodySmall, color = Vessel.colors.textMuted)
            Icon(
                VIcons.CaretDown,
                contentDescription = null,
                tint = Vessel.colors.textMuted,
                modifier = Modifier.size(Vessel.metrics.iconSm).rotate(turn),
            )
        }
        AnimatedVisibility(open) {
            Column(
                Modifier.padding(top = Vessel.metrics.s8),
                verticalArrangement = Arrangement.spacedBy(Vessel.metrics.s11),
            ) {
                content()
            }
        }
    }
}

/**
 * One row of the diagnostics inventory: what is logged, how loudly, and a cross
 * when the user put it there.
 *
 * **One shape for everything.** A Wine debug channel, `DXVK_LOG_LEVEL`, the FEX
 * logging switch, a Turnip flag and a term out of the fixed prefix are all drawn
 * by this, and what differs between them arrives as arguments: the ladder its
 * level dropdown offers, whether the columns are live, whether there is a cross.
 * **No caller passes a name this file knows anything about** — that is the point,
 * and it is the test for whether the surface is really generic.
 *
 * **Two columns and a cross, and the width budget is why there is no third.** On
 * a 421 dp sheet the content column is 387 dp; the level dropdown is 126 dp, the
 * cross 44 dp and the two gaps 12 dp, which leaves 205 dp for the name. That is
 * enough for `VKD3D_SHADER_DEBUG` set in mono and not much more — a third
 * dropdown would take the name to 93 dp and ellipsize the longest variable to
 * `VKD3D_SHADE…`, destroying the one thing the row exists to say. So *one
 * session* is a chip under the name rather than a control of its own: it is a
 * consequence of the level, not an independent choice.
 *
 * **The name is a combo, not a label.** Column one takes a value from the
 * suggestion list or anything the user types, which is what retired the "add a
 * channel" modal: the choice now happens in the row where the result appears.
 *
 * **A read-only row and an `Off` row must not look alike**, because they mean
 * opposite things. Read-only is "Vessel always sends this and you may not change
 * it": every column at the disabled opacity, no cross, and the level showing its
 * *real* value — never `Off`. An `Off` row is a live control somebody set to
 * silence. The one visual they share is dimness, and what separates them is the
 * cross: a gated row — one the user added that cannot act yet — is dim *and*
 * keeps its cross *and* carries a caution saying what is missing.
 */
@Composable
fun VDiagnosticRow(
    name: String,
    nameOptions: List<String>,
    onName: (String) -> Unit,
    levels: List<String>,
    levelLabel: (String) -> String,
    level: String,
    levelIsMachine: Boolean,
    onLevel: (String) -> Unit,
    onRemove: (() -> Unit)?,
    modifier: Modifier = Modifier,
    secondary: String? = null,
    caution: String? = null,
    /**
     * Whether the name may be typed. **Not the same flag as [levelEditable]**: a
     * row that has just been added has no name yet, and one flag for both is what
     * made Add produce a permanently greyed row nobody could fill in.
     */
    nameEditable: Boolean = true,
    levelEditable: Boolean = true,
    nameIsInvalid: Boolean = false,
    /** Shown as a chip under the name; see the width note above. */
    oneSession: Boolean = false,
) {
    Column(modifier.fillMaxWidth().padding(vertical = Vessel.metrics.s6)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = Vessel.metrics.touchTarget),
            horizontalArrangement = Arrangement.spacedBy(Vessel.metrics.s6),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f)) {
                VComboField(
                    value = name,
                    options = nameOptions,
                    onValueChange = onName,
                    placeholder = "name",
                    enabled = nameEditable,
                    isError = nameIsInvalid,
                )
            }
            Box(Modifier.width(Vessel.metrics.diagnosticControlWidth)) {
                VDropdownField(
                    options = levels,
                    labelFor = levelLabel,
                    selected = level.takeIf { it.isNotEmpty() },
                    onSelect = onLevel,
                    placeholder = "—",
                    valueIsMachine = levelIsMachine,
                    enabled = levelEditable && levels.size > 1,
                )
            }
            // The column stays whether or not there is a cross in it, so the
            // three fields above line up down the list rather than shifting
            // sideways at the boundary between what is fixed and what is not.
            Box(Modifier.size(Vessel.metrics.touchTarget), contentAlignment = Alignment.Center) {
                if (onRemove != null) {
                    VIconButton(
                        VIcons.X,
                        contentDescription = "Remove $name",
                        onClick = onRemove,
                        tint = Vessel.colors.textMuted,
                    )
                }
            }
        }
        if (oneSession) {
            Box(Modifier.padding(top = Vessel.metrics.s3)) {
                VTag("one session", tone = VTagTone.Neutral)
            }
        }
        if (secondary != null) {
            Text(
                secondary,
                style = Vessel.type.bodySmall,
                // Dimmed with the row it explains. Keyed on the *level*, which
                // is the column that says whether this row is doing anything.
                color = Vessel.colors.textMuted.let {
                    it.copy(alpha = it.alpha * if (levelEditable) 1f else Vessel.colors.disabledAlpha)
                },
            )
        }
        if (caution != null) VCaution(caution)
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF161826, widthDp = 421)
@Composable
private fun VDiagnosticRowPreview() {
    VesselTheme {
        Column(Modifier.padding(Vessel.metrics.s11)) {
            VDiagnosticRow(
                name = "winediag",
                nameOptions = emptyList(),
                onName = {},
                levels = listOf("EVERYTHING"),
                levelLabel = { "Everything" },
                level = "EVERYTHING",
                levelIsMachine = false,
                onLevel = {},
                onRemove = null,
                secondary = "Wine's report on its own health, and which renderer it chose.",
                nameEditable = false,
                levelEditable = false,
            )
            VDiagnosticRow(
                name = "relay",
                nameOptions = listOf("relay", "seh", "d3d"),
                onName = {},
                levels = listOf("OFF", "ERRORS", "WARNINGS", "STUBS", "EVERYTHING"),
                levelLabel = { it.lowercase() },
                level = "EVERYTHING",
                levelIsMachine = false,
                onLevel = {},
                onRemove = {},
                oneSession = true,
                secondary = "Names every call between libraries as it happens.",
                caution = "Hundreds of megabytes in seconds.",
            )
            // The row Add makes: no name yet, so no level to choose — but the
            // name column has to be live or the row can never become anything.
            VDiagnosticRow(
                name = "",
                nameOptions = listOf("relay", "seh", "d3d"),
                onName = {},
                levels = listOf("OFF", "ERRORS", "WARNINGS", "STUBS", "EVERYTHING"),
                levelLabel = { it.lowercase() },
                level = "",
                levelIsMachine = false,
                onLevel = {},
                onRemove = {},
                levelEditable = false,
            )
        }
    }
}
