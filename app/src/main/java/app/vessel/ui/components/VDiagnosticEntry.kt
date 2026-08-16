package app.vessel.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import app.vessel.ui.theme.Vessel
import app.vessel.ui.theme.VesselTheme
import androidx.compose.material3.Text

/**
 * One diagnostic setting: **type, flag, level** — and nothing about which table
 * it came from.
 *
 * **This replaces a screen with three lists, and the three were never a fact
 * about the stack.** *What to log* held Wine channels and a few scalars,
 * *Graphics driver* held `TU_DEBUG` members, and *Environment variables* held
 * anything else — a partition that existed because the row model could express
 * one boolean, `turnip`, and the screen inherited its shape. The cost was a
 * question with no good answer: a reader who wants `VKD3D_CONFIG=breadcrumbs`
 * has to know which of three tables a knob they have never heard of lives in,
 * and picking wrong is silent. It was silent: setting that variable in the
 * environment table did nothing at all, because the table drops every reserved
 * name, and a device run was spent discovering it.
 *
 * So the family becomes a column. One list, one Add, and the type says which
 * subsystem is being addressed.
 *
 * **THE THREE CONTROLS ARE ON TWO LINES, AND THAT IS A MEASUREMENT RATHER THAN A
 * PREFERENCE.** The row this replaces recorded the arithmetic and it still
 * holds: on a 421 dp sheet the content column is 387 dp, so a level control at
 * 126 dp, a cross at 44 dp and two 6 dp gaps leave 205 dp for the flag — barely
 * enough to set `VKD3D_SHADER_DEBUG` in mono. Putting a third control on that
 * line takes the flag to 93 dp and ellipsizes it to `VKD3D_SHADE…`, destroying
 * the one thing the row exists to say. Type and flag therefore share the first
 * line, where the type is the narrow one because its vocabulary is short and
 * curated; the level sits on the second, indented under the flag it qualifies.
 *
 * **The level is a combo and not a dropdown**, which the old row could not be. A
 * declared thing has a ladder to pick from, and a plain environment variable has
 * no ladder at all — its level is whatever the user types. One control that
 * offers what is known and accepts what is not is the only shape that serves
 * both, and it is what lets the environment table stop being a separate widget.
 *
 * @param typeOptions the declared families. Free text is still accepted: a name
 *   that matches none composes as a plain variable, which is the same escape
 *   hatch the environment table always was.
 * @param levelOptions the ladder in that subsystem's own vocabulary and order,
 *   or empty for something with no ladder.
 * @param typeEditable false for a row Vessel always sends: the declaration
 *   decides its family, not the reader.
 * @param flagEditable false only for a fixed row. **Not the same flag as
 *   [levelEditable]** — a row that has just been added has no flag yet, and one
 *   flag for both is what once made Add produce a permanently greyed row.
 */
@Composable
fun VDiagnosticEntry(
    type: String,
    typeLabel: String,
    typeOptions: List<String>,
    onType: (String) -> Unit,
    flag: String,
    flagOptions: List<String>,
    onFlag: (String) -> Unit,
    level: String,
    levelOptions: List<String>,
    levelLabel: (String) -> String,
    onLevel: (String) -> Unit,
    onRemove: (() -> Unit)?,
    modifier: Modifier = Modifier,
    secondary: String? = null,
    caution: String? = null,
    typeEditable: Boolean = true,
    flagEditable: Boolean = true,
    levelEditable: Boolean = true,
    flagIsInvalid: Boolean = false,
    levelIsMachine: Boolean = false,
    oneSession: Boolean = false,
) {
    Column(modifier.fillMaxWidth().padding(vertical = Vessel.metrics.s6)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = Vessel.metrics.touchTarget),
            horizontalArrangement = Arrangement.spacedBy(Vessel.metrics.s6),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.width(Vessel.metrics.diagnosticControlWidth)) {
                VComboField(
                    // The label rather than the wire, so the column reads
                    // "Turnip" and not "turnip" — while what is stored and what
                    // a user may type stays the wire, which is the thing the
                    // container document keeps.
                    value = if (typeEditable) type else typeLabel,
                    options = typeOptions,
                    onValueChange = onType,
                    placeholder = "type",
                    enabled = typeEditable,
                )
            }
            Box(Modifier.weight(1f)) {
                VComboField(
                    value = flag,
                    options = flagOptions,
                    onValueChange = onFlag,
                    placeholder = "flag",
                    enabled = flagEditable,
                    isError = flagIsInvalid,
                )
            }
            // The column stays whether or not there is a cross in it, so the
            // controls line up down the list rather than shifting sideways at
            // the boundary between what is fixed and what is not.
            Box(Modifier.size(Vessel.metrics.touchTarget), contentAlignment = Alignment.Center) {
                if (onRemove != null) {
                    VIconButton(
                        VIcons.X,
                        contentDescription = "Remove $flag",
                        onClick = onRemove,
                        tint = Vessel.colors.textMuted,
                    )
                }
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(top = Vessel.metrics.s3),
            horizontalArrangement = Arrangement.spacedBy(Vessel.metrics.s6),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Indented to the flag column, so the level reads as qualifying the
            // flag above it rather than as a third peer.
            Spacer(Modifier.width(Vessel.metrics.diagnosticControlWidth))
            Box(Modifier.weight(1f)) {
                VComboField(
                    value = levelLabel(level).takeIf { level.isNotEmpty() }.orEmpty(),
                    options = levelOptions.map(levelLabel),
                    // Back through the label map, because the list shows labels
                    // and the record keeps wire values. A typed value that
                    // matches no label is itself the wire — which is exactly the
                    // free-text case a variable with no ladder needs.
                    onValueChange = { shown ->
                        onLevel(levelOptions.firstOrNull { levelLabel(it) == shown } ?: shown)
                    },
                    placeholder = if (levelOptions.isEmpty()) "value" else "level",
                    enabled = levelEditable,
                )
            }
            Box(Modifier.size(Vessel.metrics.touchTarget))
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
                // Dimmed with the row it explains. Keyed on the *level*, which is
                // the column that says whether this row is doing anything.
                color = Vessel.colors.textMuted.let {
                    it.copy(alpha = it.alpha * if (levelEditable) 1f else Vessel.colors.disabledAlpha)
                },
                overflow = TextOverflow.Visible,
            )
        }
        if (caution != null) VCaution(caution)
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF161826, widthDp = 421)
@Composable
private fun VDiagnosticEntryPreview() {
    VesselTheme {
        Column(Modifier.padding(Vessel.metrics.s11)) {
            VDiagnosticEntry(
                type = "vkd3dconfig",
                typeLabel = "vkd3d config",
                typeOptions = listOf("wine", "vkd3d", "vkd3dconfig", "turnip", "env"),
                onType = {},
                flag = "breadcrumbs",
                flagOptions = listOf("breadcrumbs", "breadcrumbs_sync"),
                onFlag = {},
                level = "on",
                levelOptions = listOf("off", "on"),
                levelLabel = { it.replaceFirstChar(Char::uppercase) },
                onLevel = {},
                onRemove = {},
                secondary = "VKD3D_CONFIG — on device loss, name the last command the GPU acknowledged.",
            )
            VDiagnosticEntry(
                type = "env",
                typeLabel = "Environment variable",
                typeOptions = listOf("wine", "vkd3d", "env"),
                onType = {},
                flag = "VKD3D_SHADER_DUMP_PATH",
                flagOptions = emptyList(),
                onFlag = {},
                level = "C:\\vessel\\shaderdump",
                levelOptions = emptyList(),
                levelLabel = { it },
                onLevel = {},
                onRemove = {},
                secondary = "A variable this build has no description for.",
            )
        }
    }
}
