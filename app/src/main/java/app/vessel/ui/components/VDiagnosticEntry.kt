package app.vessel.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
 * **THE THREE CONTROLS ARE STACKED, EACH FULL WIDTH, INSIDE ONE BORDERED CARD.**
 * The row this replaces put two of them side by side and recorded why a third
 * would not fit: on a 421 dp sheet the content column is 387 dp, so a level
 * control at 126 dp, a cross at 44 dp and two gaps leave 205 dp for the flag —
 * barely enough for `VKD3D_SHADER_DEBUG` in mono, and a third control took it to
 * 93 dp and ellipsized to `VKD3D_SHADE…`. Stacking removes the constraint rather
 * than working around it: every field gets the whole width, nothing is ever
 * truncated, and the longest variable name in the stack still fits.
 *
 * The border is what makes that readable. Three full-width boxes in a flat list
 * give no way to see where one setting ends and the next begins — the card is
 * the only thing saying "these three belong together", and it is why the remove
 * control sits on the card's own header line rather than beside a field: it acts
 * on the whole entry, not on the flag.
 *
 * Each field is labelled for the same reason. Side by side, position told you
 * which control was which; stacked, three identical boxes do not, and a label
 * costs one line of 11 sp text to remove the guess.
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
    Column(
        modifier
            .fillMaxWidth()
            .padding(top = Vessel.metrics.s8)
            .border(Vessel.metrics.hairline, Vessel.colors.divider, Vessel.metrics.shapeMd)
            .padding(Vessel.metrics.s8),
    ) {
        // The remove control sits on its own line above the fields rather than
        // beside one of them. Beside a field it would have to steal width from
        // that field alone, which is what made the previous row asymmetric — and
        // it belongs to the whole entry, not to the flag.
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (onRemove == null) "Always on" else typeLabel.ifBlank { "Setting" },
                style = Vessel.type.label,
                color = Vessel.colors.textMuted,
            )
            if (onRemove != null) {
                VIconButton(
                    VIcons.X,
                    contentDescription = "Remove $flag",
                    onClick = onRemove,
                    tint = Vessel.colors.textMuted,
                )
            } else {
                Box(Modifier.size(Vessel.metrics.touchTarget))
            }
        }

        Field("Type") {
            VComboField(
                value = type,
                options = typeOptions,
                onValueChange = onType,
                placeholder = "subsystem",
                enabled = typeEditable,
            )
        }
        Field("Flag") {
            VComboField(
                value = flag,
                options = flagOptions,
                onValueChange = onFlag,
                placeholder = "name",
                enabled = flagEditable,
                isError = flagIsInvalid,
            )
        }
        Field(if (levelOptions.isEmpty()) "Value" else "Level") {
            VComboField(
                value = levelLabel(level).takeIf { level.isNotEmpty() }.orEmpty(),
                options = levelOptions.map(levelLabel),
                // Back through the label map, because the list shows labels and
                // the record keeps wire values. A typed value matching no label
                // is itself the wire, which is the free-text case a variable with
                // no ladder needs.
                onValueChange = { shown ->
                    onLevel(levelOptions.firstOrNull { levelLabel(it) == shown } ?: shown)
                },
                placeholder = if (levelOptions.isEmpty()) "value" else "level",
                enabled = levelEditable,
            )
        }

        if (oneSession) {
            Box(Modifier.padding(top = Vessel.metrics.s6)) {
                VTag("one session", tone = VTagTone.Neutral)
            }
        }
        if (secondary != null) {
            Text(
                secondary,
                style = Vessel.type.bodySmall,
                // Dimmed with the entry it explains. Keyed on the *level*, which
                // is the field that says whether this entry is doing anything.
                color = Vessel.colors.textMuted.let {
                    it.copy(alpha = it.alpha * if (levelEditable) 1f else Vessel.colors.disabledAlpha)
                },
                modifier = Modifier.padding(top = Vessel.metrics.s6),
            )
        }
        if (caution != null) VCaution(caution)
    }
}

/**
 * One labelled field, full width.
 *
 * The label is what a stacked layout buys and a side-by-side one cannot: three
 * bare combos in a column are three identical boxes, and nothing on screen says
 * which is the subsystem and which is the value. It costs a line of 11 sp text
 * per field and removes the only thing the old layout was still guessing at.
 */
@Composable
private fun Field(label: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = Vessel.metrics.s6)) {
        Text(label, style = Vessel.type.label, color = Vessel.colors.textMuted)
        Box(Modifier.fillMaxWidth().padding(top = Vessel.metrics.s3)) { content() }
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
