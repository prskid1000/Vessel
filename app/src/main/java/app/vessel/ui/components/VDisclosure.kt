package app.vessel.ui.components

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import app.vessel.ui.theme.Vessel
import app.vessel.ui.theme.VesselTheme

/**
 * Which of the two jobs a [VDisclosureRow] is doing.
 *
 * The distinction is hierarchy, not decoration. [Item] is a thing *in* a sheet
 * that happens to open — it reads like the fields above it. [Group] is a
 * partition *inside* something already open, so it is set in `overline` and
 * carries the hairline that says where one group's contents end.
 */
enum class VDisclosureStyle { Item, Group }

/**
 * A row that opens something, with the state of what it opens on the right.
 *
 * **The state text is the whole reason this is not a [VSheetRow].** A sheet row
 * offers a destination and says what tapping it will do; this one has to say what
 * is *currently true* behind it — `all off`, `7 channels`, `14.2 MB` — because a
 * collapsed group whose state is invisible is a place the user has to open to
 * find out they did not need to. That readout, in mono on the right edge, is what
 * makes four collapsed rows a summary rather than a menu.
 *
 * There is no expand/collapse animation on the content: `docs/DESIGN.md`'s motion
 * rule is confirmation, never decoration, and a height animation on a group that
 * can hold nine rows is the sheet resizing under a finger. The chevron turns —
 * 150 ms, the standard duration — and the content is simply there.
 *
 * @param state the fact about what is behind this row, or null when there is
 *   nothing worth saying before it is opened.
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
                title,
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
 * One diagnostic control: what it is called, what Wine or the translator calls
 * it, what question it answers, and the control itself beside the name.
 *
 * **Beside, not beneath, and that is the difference from [VParamRow].** A param
 * row is one of four fields in a form and can afford a full-width control under
 * its label. This is one of nine rows in a list, and nine stacked
 * label-over-control pairs is a sheet nobody reaches the bottom of. The control
 * column is a fixed `diagnosticControlWidth` and is aligned to its right edge, so
 * a 36 dp toggle and a 126 dp dropdown line up down the same edge — which is what
 * lets the whole group be read down it without touching anything.
 *
 * [machineName] is not garnish either: the audience for this surface has been
 * told by a maintainer or an issue thread to "turn on `module`", and a row that
 * only says *Missing DLLs and exports* makes them guess which one that is.
 *
 * [help] is kept under the row rather than moved to a long-press. Everywhere else
 * in this product a control's sentence is visible — `params-manifest.json:9-12`
 * makes it the condition of a setting existing at all — and a diagnostics screen
 * is the last place to start hiding the explanation behind a gesture.
 */
@Composable
fun VDiagnosticRow(
    title: String,
    machineName: String,
    help: String?,
    modifier: Modifier = Modifier,
    caution: String? = null,
    tag: (@Composable () -> Unit)? = null,
    control: @Composable () -> Unit,
) {
    Column(modifier.fillMaxWidth().padding(vertical = Vessel.metrics.s6)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = Vessel.metrics.touchTarget),
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
                    Text(title, style = Vessel.type.body)
                    tag?.invoke()
                }
                Text(machineName, style = Vessel.type.monoSmall, color = Vessel.colors.textMuted)
            }
            Box(
                Modifier.width(Vessel.metrics.diagnosticControlWidth),
                contentAlignment = Alignment.CenterEnd,
            ) { control() }
        }
        if (help != null) {
            Text(
                help,
                style = Vessel.type.bodySmall,
                color = Vessel.colors.textMuted,
                modifier = Modifier.padding(top = Vessel.metrics.s3),
            )
        }
        if (caution != null) {
            Text(
                caution,
                style = Vessel.type.bodySmall,
                color = Vessel.colors.warn,
                modifier = Modifier.padding(top = Vessel.metrics.s3),
            )
        }
    }
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
            VRule(verticalMargin = Vessel.metrics.s6)
            VDisclosureRow(
                title = "Wine",
                style = VDisclosureStyle.Group,
                state = "9 channels",
                expanded = true,
                onToggle = {},
            )
            VDiagnosticRow(
                title = "Missing DLLs and exports",
                machineName = "module",
                help = "Says when a library loaded but an entry point inside it is missing, " +
                    "which is what later crashes far from the cause.",
                control = {
                    VDropdownField(
                        options = listOf("Off", "Errors", "+ Warnings"),
                        labelFor = { it },
                        selected = "+ Warnings",
                        onSelect = {},
                    )
                },
            )
            VDiagnosticRow(
                title = "Every call between libraries",
                machineName = "relay",
                help = "Names every cross-DLL call as it happens.",
                caution = "Hundreds of megabytes in seconds; it switches itself off after one " +
                    "launch.",
                tag = { VTag("one launch", tone = VTagTone.Neutral) },
                control = { VToggle(checked = false, onCheckedChange = {}) },
            )
        }
    }
}
