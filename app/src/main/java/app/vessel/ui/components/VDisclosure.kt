package app.vessel.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import app.vessel.ui.theme.Vessel
import app.vessel.ui.theme.VesselTheme
import app.vessel.ui.theme.vRing

/**
 * Which of the two jobs a [VDisclosureRow] is doing.
 *
 * The distinction is hierarchy, not decoration. [Item] is a thing *in* a sheet
 * that happens to open — it reads like the fields above it. [Group] is a
 * partition *inside* something already open, so it is uppercase `overline` in the
 * muted tone, which is the kicker this product uses for every group heading.
 */
enum class VDisclosureStyle { Item, Group }

/**
 * A row that opens something, with the state of what it opens on the right.
 *
 * **The state text is the whole reason this is not a [VSheetRow].** A sheet row
 * offers a destination and says what tapping it will do; this one has to say what
 * is *currently true* behind it — `7 channels`, `defaults`, `14.2 MB`, `empty` —
 * because a collapsed group whose state is invisible is a place the user has to
 * open to find out they did not need to. That readout, in mono on the right edge,
 * is what makes four collapsed rows a summary rather than a menu.
 *
 * There is no expand/collapse animation on the content: `docs/DESIGN.md`'s motion
 * rule is confirmation, never decoration, and a height animation on a group that
 * can hold a dozen rows is the sheet resizing under a finger. The chevron turns —
 * 150 ms, the standard duration — and the content is simply there.
 */
@Composable
fun VDisclosureRow(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    style: VDisclosureStyle = VDisclosureStyle.Item,
    state: String? = null,
    help: String? = null,
) {
    val turn by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        label = "disclosure",
    )
    Row(
        modifier
            .fillMaxWidth()
            .clickable(onClickLabel = title, onClick = onToggle)
            .heightIn(min = Vessel.metrics.touchTarget)
            .padding(vertical = Vessel.metrics.s6),
        horizontalArrangement = Arrangement.spacedBy(Vessel.metrics.s8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(Vessel.metrics.s3),
        ) {
            Text(
                // Uppercasing happens here because Compose has no
                // `text-transform`, which is why `overline` carries the tracking
                // but not the case — the same reason [VSectionHeader] does it.
                if (style == VDisclosureStyle.Group) title.uppercase() else title,
                style = when (style) {
                    VDisclosureStyle.Item -> Vessel.type.body
                    VDisclosureStyle.Group -> Vessel.type.overline
                },
                color = when (style) {
                    VDisclosureStyle.Item -> Vessel.colors.textPrimary
                    VDisclosureStyle.Group -> Vessel.colors.textMuted
                },
            )
            if (help != null) {
                Text(help, style = Vessel.type.bodySmall, color = Vessel.colors.textMuted)
            }
        }
        if (state != null) {
            Text(
                state,
                style = Vessel.type.monoSmall,
                color = Vessel.colors.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            VIcons.CaretDown,
            contentDescription = null,
            tint = Vessel.colors.textMuted,
            modifier = Modifier.size(Vessel.metrics.iconMd).rotate(turn),
        )
    }
}

/**
 * One diagnostic control: **the name the tool knows it by**, whatever needs
 * saying under it, and the control beside the name.
 *
 * **The mono name is the primary line, and that is the audience showing through.**
 * Everywhere else in this product a control is titled in plain English, because
 * everywhere else the reader is someone with a container. Here the reader has
 * been told by a maintainer, an issue thread or a bug report to "turn on
 * `module`" or "set `VKD3D_SHADER_DEBUG` to `warn`", and a row titled *Missing
 * DLLs and exports* makes them guess which one that is. The English explanation
 * has already done its work in the picker; on the row the identifier is the thing
 * being looked for.
 *
 * **Beside, not beneath, unlike [VParamRow].** A param row is one of four fields
 * in a form and can afford a full-width control under its label. This is one of
 * a list, and stacked label-over-control pairs is a sheet nobody reaches the
 * bottom of. The control column is a fixed `diagnosticControlWidth`, aligned to
 * its right edge, so a checkbox hint and a dropdown line up down the same edge —
 * which is what lets a group be read down it without touching anything.
 *
 * @param secondary the line under the name, in the ordinary muted tone.
 * @param caution the line under the name in the warning tone, for a property of
 *   the thing itself rather than of the current value. Both may be present.
 * @param trailing sits after the control — the remove cross on a channel row.
 */
@Composable
fun VDiagnosticRow(
    name: String,
    modifier: Modifier = Modifier,
    secondary: String? = null,
    caution: String? = null,
    tag: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    control: @Composable () -> Unit,
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = Vessel.metrics.touchTarget)
            .padding(vertical = Vessel.metrics.s6),
        horizontalArrangement = Arrangement.spacedBy(Vessel.metrics.s8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(Vessel.metrics.s3),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(Vessel.metrics.s6),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(name, style = Vessel.type.mono)
                tag?.invoke()
            }
            if (secondary != null) {
                Text(secondary, style = Vessel.type.bodySmall, color = Vessel.colors.textMuted)
            }
            if (caution != null) {
                Text(caution, style = Vessel.type.bodySmall, color = Vessel.colors.warn)
            }
        }
        Box(
            Modifier.width(Vessel.metrics.diagnosticControlWidth),
            contentAlignment = Alignment.CenterEnd,
        ) { control() }
        trailing?.invoke()
    }
}

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
 * A panel of prose inside a sheet — the sentence that says what a screen is for.
 *
 * A ringed box rather than a bare paragraph, because the Diagnostics surface's
 * opening line has to be read *before* the controls rather than skimmed as one
 * more help sentence among fifteen: it is the line that stops a screen of `Off`
 * from reading as "logging is disabled".
 */
@Composable
fun VInfoBox(text: String, modifier: Modifier = Modifier) {
    val shape = Vessel.metrics.shapeMd
    Text(
        text,
        style = Vessel.type.bodySmall,
        color = Vessel.colors.textLabel,
        modifier = modifier
            .fillMaxWidth()
            .background(Vessel.colors.surfaceRaised, shape)
            .vRing(Vessel.colors.divider, shape)
            .padding(horizontal = Vessel.metrics.s11, vertical = Vessel.metrics.s8),
    )
}

@Preview(showBackground = true, backgroundColor = 0xFF161826, widthDp = 392)
@Composable
private fun VDisclosurePreview() {
    VesselTheme {
        Column(Modifier.padding(Vessel.metrics.s17)) {
            VDisclosureRow(
                title = "Diagnostics",
                help = "What the next session is asked to say about itself. Nothing here " +
                    "changes how the program runs.",
                state = "all off",
                expanded = false,
                onToggle = {},
            )
            VInfoBox(
                "Vessel already records errors, missing DLLs, loaded modules and the program's " +
                    "own messages. Everything below adds to that.",
            )
            VDisclosureRow(
                title = "Wine",
                style = VDisclosureStyle.Group,
                state = "2 channels",
                expanded = true,
                onToggle = {},
            )
            VDiagnosticRow(
                name = "d3d",
                caution = "Unbounded above warnings — 659 per-draw error sites.",
                control = {
                    VDropdownField(
                        options = listOf("Off", "Errors", "+ Warnings"),
                        labelFor = { it },
                        selected = "Errors",
                        onSelect = {},
                    )
                },
                trailing = { VIconButton(VIcons.X, "Remove d3d", {}) },
            )
            VDiagnosticRow(
                name = "relay",
                tag = { VTag("one session", tone = VTagTone.Neutral) },
                control = {
                    VDropdownField(
                        options = listOf("Off", "Everything"),
                        labelFor = { it },
                        selected = "Off",
                        onSelect = {},
                        valueIsMachine = false,
                    )
                },
                trailing = { VIconButton(VIcons.X, "Remove relay", {}) },
            )
            VCaution(
                "A channel marked one session fills the log in seconds and slows the run; it " +
                    "switches itself off after the next launch.",
            )
        }
    }
}
