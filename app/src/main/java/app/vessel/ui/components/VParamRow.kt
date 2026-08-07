package app.vessel.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.vessel.ui.theme.Vessel
import app.vessel.ui.theme.VesselTheme
import app.vessel.ui.theme.vCard
import app.vessel.ui.theme.vRing

/**
 * One setting from the manifest: title, control, and the sentence that says what
 * it does.
 *
 * The sentence is the point of the container editor, not garnish. Every other
 * app in this space shows a wall of `BOX64_DYNAREC_*` and assumes you already
 * know; DESIGN.md's rule is that a knob which cannot be explained in one plain
 * sentence belongs in Diagnostics instead. So [help] renders under the control
 * rather than behind an info affordance, and nothing calls this without one.
 *
 * [note] is for a fact about the control's *bounds* — a clamp that is currently
 * active, or a component selector that resolved to nothing — and [warning] for a
 * value that is dangerous right now.
 */
@Composable
fun VParamRow(
    title: String,
    help: String?,
    modifier: Modifier = Modifier,
    note: String? = null,
    warning: String? = null,
    control: @Composable () -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(bottom = Vessel.metrics.s8)
            .vCard()
            .padding(Vessel.metrics.s11),
        verticalArrangement = Arrangement.spacedBy(Vessel.metrics.s8),
    ) {
        Text(title, style = Vessel.type.body)
        control()
        if (help != null) {
            Text(help, style = Vessel.type.bodySmall, color = Vessel.colors.textMuted)
        }
        if (note != null) {
            Text(note, style = Vessel.type.bodySmall, color = Vessel.colors.accent2)
        }
        if (warning != null) {
            Text(warning, style = Vessel.type.bodySmall, color = Vessel.colors.warn)
        }
    }
}

/**
 * The `enum` control: one chip per option, wrapping.
 *
 * `FlowRow` is still experimental in this Compose version, so the wrap is done
 * by hand — options come in twos and threes and a fixed two-per-row reads more
 * evenly than a ragged flow anyway.
 */
@Composable
fun VChoiceRow(
    options: List<String>,
    labelFor: (String) -> String,
    selected: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Vessel.metrics.s6)) {
        options.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(Vessel.metrics.s6)) {
                pair.forEach { option ->
                    VChoiceChip(
                        label = labelFor(option),
                        selected = option == selected,
                        onClick = { onSelect(option) },
                        modifier = Modifier.weight(1f),
                    )
                }
                // The odd option out keeps its half-width rather than stretching
                // across the row, so a three-option control still reads as a grid.
                if (pair.size == 1) Box(Modifier.weight(1f))
            }
        }
    }
}

/** `.seg-opt` — transparent ground, a ring, and the accent doing the selecting. */
@Composable
fun VChoiceChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = Vessel.metrics.shapeMd
    val fill = if (selected) Vessel.colors.accentSoft else Color.Transparent
    val stroke = if (selected) Vessel.colors.accent else Vessel.colors.divider
    val ink = if (selected) Vessel.colors.accent else Vessel.colors.textLabel

    Box(
        modifier
            .background(fill, shape)
            .vRing(stroke, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = Vessel.metrics.s11, vertical = Vessel.metrics.s8),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(label, style = Vessel.type.control, color = ink)
    }
}

/**
 * The `bool` control.
 *
 * Hand-built rather than Material's `Switch`, which arrives with its own palette
 * and its own spring. This is a ring, a token fill and one 150 ms slide — the
 * motion rule in DESIGN.md is confirmation, never decoration.
 */
@Composable
fun VToggle(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = Vessel.metrics.shapePill
    val track = if (checked) Vessel.colors.accentSoft else Color.Transparent
    val stroke = if (checked) Vessel.colors.accent else Vessel.colors.divider
    val thumb = if (checked) Vessel.colors.accent else Vessel.colors.textMuted
    val thumbOffset by animateDpAsState(
        targetValue = if (checked) 20.dp else 2.dp,
        animationSpec = tween(Vessel.metrics.durationStandardMs),
        label = "toggle",
    )

    Row(
        modifier.clickable { onCheckedChange(!checked) },
        horizontalArrangement = Arrangement.spacedBy(Vessel.metrics.s8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(42.dp)
                .height(24.dp)
                .background(track, shape)
                .vRing(stroke, shape),
        ) {
            Box(
                Modifier
                    .offset(x = thumbOffset, y = 2.dp)
                    .size(18.dp)
                    .background(thumb, Vessel.metrics.shapePill),
            )
        }
        Text(
            if (checked) "on" else "off",
            style = Vessel.type.mono,
            color = if (checked) Vessel.colors.accent else Vessel.colors.textMuted,
        )
    }
}

/**
 * The `int` control.
 *
 * A stepper, not a slider: every integer param in the manifest spans three to
 * five steps, each of which is a documented behaviour change rather than a
 * position on a continuum, and a slider would invite dragging past the one that
 * works. The range is printed in mono next to the value so the ceiling is
 * visible before it is hit — which matters most when a clamp has lowered it.
 */
@Composable
fun VStepper(
    value: Int,
    min: Int,
    max: Int,
    onChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(Vessel.metrics.s8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        VStepperButton("−", enabled = value > min) { onChange(value - 1) }
        Text(
            value.toString(),
            style = Vessel.type.metric,
            color = Vessel.colors.textPrimary,
            modifier = Modifier.width(32.dp),
        )
        VStepperButton("+", enabled = value < max) { onChange(value + 1) }
        Text(
            "$min…$max",
            style = Vessel.type.mono,
            color = Vessel.colors.textMuted,
            modifier = Modifier.padding(start = Vessel.metrics.s6),
        )
    }
}

@Composable
private fun VStepperButton(glyph: String, enabled: Boolean, onClick: () -> Unit) {
    val shape = Vessel.metrics.shapeMd
    val alpha = if (enabled) 1f else Vessel.colors.disabledAlpha
    Box(
        Modifier
            .size(36.dp)
            .vRing(Vessel.colors.divider.copy(alpha = Vessel.colors.divider.alpha * alpha), shape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(glyph, style = Vessel.type.subtitle, color = Vessel.colors.textPrimary.copy(alpha = alpha))
    }
}

/** One line of the `multi` control: a box, a tick, and the option's label. */
@Composable
fun VCheckRow(
    label: String,
    checked: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = Vessel.metrics.shapeSm
    Row(
        modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(vertical = Vessel.metrics.s3),
        horizontalArrangement = Arrangement.spacedBy(Vessel.metrics.s8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(18.dp)
                .background(if (checked) Vessel.colors.accentSoft else Color.Transparent, shape)
                .vRing(if (checked) Vessel.colors.accent else Vessel.colors.divider, shape),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) {
                Text("✓", style = Vessel.type.monoSmall, color = Vessel.colors.accent)
            }
        }
        Text(label, style = Vessel.type.bodySmall, color = Vessel.colors.textLabel)
    }
}

/**
 * A single-line text input.
 *
 * `BasicTextField` and a box we draw, because Material's `TextField` brings a
 * container tone, an indicator line and a label animation that are all from a
 * different design system. The cursor is the accent; nothing else is coloured.
 */
@Composable
fun VTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
) {
    val shape = Vessel.metrics.shapeMd
    Box(
        modifier
            .fillMaxWidth()
            .vRing(Vessel.colors.divider, shape)
            .padding(horizontal = Vessel.metrics.s11, vertical = Vessel.metrics.s8),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (value.isEmpty() && placeholder != null) {
            Text(placeholder, style = Vessel.type.body, color = Vessel.colors.textMuted)
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = Vessel.type.body.copy(color = Vessel.colors.textPrimary),
            cursorBrush = SolidColor(Vessel.colors.accent),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                imeAction = ImeAction.Done,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * The `component` control.
 *
 * There is nothing to pick. Vessel ships one current build of each component
 * compiled for this device, so a selector is a readout of what `@latest`
 * resolved to — and when it resolved to nothing, the row says that rather than
 * showing an empty field the user would try to fill.
 */
@Composable
fun VComponentReadout(
    selector: String,
    resolvedId: String?,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(Vessel.metrics.s8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        VTag(selector, tone = VTagTone.Outline)
        Text(
            resolvedId ?: "nothing installed",
            style = Vessel.type.mono,
            color = if (resolvedId != null) Vessel.colors.textLabel else Vessel.colors.textMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF161826, widthDp = 392)
@Composable
private fun VParamRowPreview() {
    VesselTheme {
        Column(Modifier.padding(18.dp)) {
            VParamRow(
                title = "Strict memory ordering",
                help = "Keep this on: turning it off is faster but breaks most multi-threaded " +
                    "programs.",
                warning = "Most programs will crash or corrupt data with this off.",
            ) {
                VToggle(checked = false, onCheckedChange = {})
            }
            VParamRow(
                title = "Call/return optimisation",
                help = "Speeds up function calls; lower it if a program crashes on startup.",
                note = "Box64 documents level 2 as not working under WowBox64.",
            ) {
                VStepper(value = 1, min = 0, max = 1, onChange = {})
            }
            VParamRow(title = "64-bit engine", help = "Which translator handles the program.") {
                VChoiceRow(listOf("FEX", "BOX64"), { it }, "FEX", {})
            }
            VParamRow(title = "GPU driver", help = "Turnip is the open driver this app builds.") {
                VComponentReadout("@latest", null)
            }
        }
    }
}
