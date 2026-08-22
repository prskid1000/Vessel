package app.vessel.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import app.vessel.core.ADDABLE_LOGGABLES
import app.vessel.core.ENV_FAMILY
import app.vessel.core.FAMILIES
import app.vessel.core.ContainerDiagnostics
import app.vessel.core.EnvSetting
import app.vessel.core.KNOWN_ENV
import app.vessel.core.knownEnvFor
import app.vessel.core.DiagnosticRow
import app.vessel.core.SessionLogLimits
import app.vessel.core.costWarning
import app.vessel.core.diagnosticRows
import app.vessel.core.loggableFor
import app.vessel.ui.components.VButton
import app.vessel.ui.components.VCaution
import app.vessel.ui.components.VComboField
import app.vessel.ui.components.VTextField
import app.vessel.ui.components.VButtonStyle
import app.vessel.ui.components.VConfirmSheet
import app.vessel.ui.components.VDiagnosticEntry
import app.vessel.ui.components.VDialogCard
import app.vessel.ui.components.VDisclosure
import app.vessel.ui.components.VDropdownField
import app.vessel.ui.components.VIconButton
import app.vessel.ui.components.VIcons
import app.vessel.ui.components.VLabeledField
import app.vessel.ui.components.VProgressBar
import app.vessel.ui.components.VSectionHeader
import app.vessel.ui.components.VSheetHeader
import app.vessel.ui.theme.Vessel
import app.vessel.ui.theme.VesselTheme
import app.vessel.ui.theme.vRing
import app.vessel.ui.vm.DiagnosticsUiState

/**
 * Diagnostics: two sections, and the first is an inventory.
 *
 * **"What to log" is one uniform list**, not a set of groups per subsystem. A
 * Wine channel, `DXVK_LOG_LEVEL`, the FEX logging switch and a Turnip flag are
 * all answers to the same question, and splitting them by which layer implements
 * them is the mistake `params-manifest.json:14-19` already warns about for the
 * container editor: group by what a setting affects, never by the subsystem
 * behind it.
 *
 * **What Vessel already sends is present, disabled, and folded away.** Those
 * rows are the reason there is no explanatory paragraph at the top: they say
 * what a paragraph used to claim, in a form the reader can inspect. But they
 * are also the majority of the list and none of them can be changed here, so
 * flat they were eight read-only rows standing between the reader and the only
 * control that does anything. They sit behind a collapsed disclosure instead —
 * still a truthful account of the environment, one tap away, rather than the
 * first thing in the way.
 *
 * **Nothing in this file names a channel or a variable.** Every row comes out of
 * `diagnosticRows`, which comes out of `LOGGABLES`. There is no `when` over a
 * kind of row here, and adding a loggable thing changes no line of this file.
 */
@Composable
fun DiagnosticsPanel(
    state: DiagnosticsUiState,
    onChange: (ContainerDiagnostics) -> Unit,
    onDeleteLogs: () -> Unit,
    onCopyTo: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // The value the user has asked for and the warning standing between them.
    // Held rather than applied, so cancelling leaves the record as it was.
    var pending by remember { mutableStateOf<Pair<String, ContainerDiagnostics>?>(null) }
    var confirmingDeleteLogs by remember { mutableStateOf(false) }

    val diagnostics = state.diagnostics
    val rows = diagnosticRows(diagnostics)

    /*
     * Collapsed by default. The baseline is reference material — worth being
     * able to read, not worth reading every time — and the reason to open this
     * screen at all is to add a row or change one you added.
     */
    var baselineExpanded by remember { mutableStateOf(false) }

    /** The graphics table's own baseline, folded for the same reason. */
    var turnipBaselineExpanded by remember { mutableStateOf(false) }

    /**
     * Apply an edit, unless it newly turns on something that costs.
     *
     * The gate is *newly* cautioned rather than cautioned: moving DXVK from
     * `debug` to `trace` has already been asked about, and a second dialog for
     * the same decision is how a warning becomes a thing people tap through.
     */
    fun propose(next: ContainerDiagnostics, index: Int) {
        val row = next.rows.getOrNull(index)
        val caution = row?.let { loggableFor(it.name).caution }
        val wasCautioned = diagnostics.rows.getOrNull(index)
            ?.let { loggableFor(it.name).caution } != null
        if (row == null || caution == null || wasCautioned) {
            onChange(next)
        } else {
            // The volume of the stop being moved *to*, not of the row as it
            // stands: the dialog is the last moment before the cost is
            // committed, and the figure it should carry is the one the user is
            // about to pay. See `Loggable.volumes`.
            val volume = loggableFor(row.name, row.turnip).volume(row.level)
            pending = costWarning(caution, row.isOneSession, volume) to next
        }
    }

    // **One list, and the type column is what made three into one.**
    //
    // There were three: *What to log* for Wine channels and scalars, *Graphics
    // driver* for TU_DEBUG members, and *Environment variables* for anything
    // else. That partition was never a fact about the stack — it was the row
    // model expressing a single boolean, `turnip`, and this screen inheriting
    // its shape. It cost a real question with no good answer: which of three
    // tables does a knob you have never heard of live in? Picking wrong was
    // silent, and it *was* silent — `VKD3D_CONFIG=breadcrumbs` typed into the
    // environment table did nothing at all, because that table drops every
    // reserved name, and a device run went into finding out.
    //
    // Now the family is column one, `diagnosticRows` folds the legacy env list
    // in as `env`-typed rows, and there is one Add.
    val (added, fixed) = rows.partition { it.removable }

    // Every declared family, plus any type the reader has already typed that is
    // not one — so a row keeps offering its own value back rather than looking
    // like a mistake.
    val typeOptions = remember(added) {
        (FAMILIES.map { it.wire } + added.map { it.type }).filter { it.isNotBlank() }.distinct()
    }

    Column(modifier.fillMaxWidth()) {
        VDisclosure(
            "Always on",
            expanded = baselineExpanded,
            onToggle = { baselineExpanded = !baselineExpanded },
            summary = "${fixed.size} Vessel always sends",
        ) {
            fixed.forEach { row -> InventoryRow(row, diagnostics, typeOptions, ::propose, onChange) }
        }
        added.forEach { row -> InventoryRow(row, diagnostics, typeOptions, ::propose, onChange) }

        VButton(
            "Add",
            { onChange(diagnostics.withRowAdded(FAMILIES.first().wire)) },
            style = VButtonStyle.Primary,
            icon = VIcons.Plus,
            modifier = Modifier.fillMaxWidth().padding(top = Vessel.metrics.s8),
        )
        Text(
            "Every field takes what you type as well as what it offers. Reserved " +
                "names are refused. A later row wins over an earlier one, and the " +
                "environment is composed at launch, so a running container keeps what " +
                "it started with.",
            style = Vessel.type.bodySmall,
            color = Vessel.colors.textMuted,
            modifier = Modifier.padding(top = Vessel.metrics.s6),
        )

        VSectionHeader("How much to keep")
        KeepSection(state, onChange, onDeleteLogs = { confirmingDeleteLogs = true })

        Row(
            Modifier.fillMaxWidth().padding(top = Vessel.metrics.s11),
            horizontalArrangement = Arrangement.spacedBy(Vessel.metrics.s8),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CopyToContainer(state.otherContainers, onCopyTo)
            Row(Modifier.weight(1f)) {}
            VButton(
                "Reset all",
                { onChange(ContainerDiagnostics.DEFAULT) },
                style = VButtonStyle.Ghost,
                enabled = !diagnostics.isDefault,
            )
        }
    }

    val cost = pending
    if (cost != null) {
        VConfirmSheet(
            title = "This one is expensive",
            // Plus the consequence a container that has never run adds: WINEDEBUG
            // is in BOOTSTRAP_SESSION_ENV, so whatever is on here also reaches
            // `wineboot` while the prefix is being built — and a wineboot given
            // too much is a hang with an empty drive_c two minutes later.
            message = cost.first + if (state.neverLaunched) {
                " This container has never been launched, so the next launch also builds its " +
                    "Windows prefix with this on: expect it to take much longer than usual."
            } else {
                ""
            },
            confirmLabel = "Turn it on",
            onConfirm = {
                onChange(cost.second)
                pending = null
            },
            onDismiss = { pending = null },
        )
    }

    if (confirmingDeleteLogs) {
        VConfirmSheet(
            title = "Delete this container's logs?",
            message = "All ${state.sessionCount} recorded sessions are removed from this " +
                "device, freeing ${state.usageLabel}. The container itself is not touched.",
            confirmLabel = "Delete",
            onConfirm = {
                confirmingDeleteLogs = false
                onDeleteLogs()
            },
            onDismiss = { confirmingDeleteLogs = false },
        )
    }
}

/**
 * One row, wired.
 *
 * The whole of the dispatch: a fixed row has index -1 and no writers, an added
 * row has an index and three of them. There is no branch on what *kind* of thing
 * the row is, because [DiagnosticRow] has already resolved that from the
 * declaration.
 */
@Composable
private fun InventoryRow(
    row: DiagnosticRow,
    diagnostics: ContainerDiagnostics,
    typeOptions: List<String>,
    propose: (ContainerDiagnostics, Int) -> Unit,
    onChange: (ContainerDiagnostics) -> Unit,
) {
    VDiagnosticEntry(
        type = row.type,
        typeLabel = row.typeLabel,
        typeOptions = typeOptions,
        onType = { onChange(diagnostics.normalised().withRowTyped(row.index, it)) },
        typeEditable = row.typeEditable,
        flag = row.name,
        // Only this family's flags, and only the ones no other row holds.
        // Offering every declared name would let a Turnip flag be picked into a
        // vkd3d row, where it would be composed into the wrong variable and
        // ignored in silence; offering one already present invites a second row
        // for a setting that can only have one value.
        // **`KNOWN_ENV` for the environment family, and its absence was why that
        // column offered nothing at all.** Every other family's names are
        // declared `LOGGABLES`; the environment table's are not -- they are a
        // separate curated list, because a plain variable has no level ladder and
        // nothing to compose. The unified table replaced a composable that read
        // `KNOWN_ENV` directly and did not carry that half over, so sixteen
        // described variables were reachable only by typing their names exactly,
        // which is the one thing the list exists to spare the reader.
        flagOptions = (
            if (row.type == ENV_FAMILY) KNOWN_ENV.map { it.name }
            else ADDABLE_LOGGABLES.filter { it.family == row.type }.map { it.name }
            )
            .filter { name -> name == row.name || diagnostics.normalised().rows.none { it.name == name } },
        onFlag = { propose(diagnostics.normalised().withRowNamed(row.index, it), row.index) },
        flagEditable = row.nameEditable,
        flagIsInvalid = row.nameIsInvalid,
        level = row.level,
        levelOptions = row.levels,
        levelLabel = { row.levelLabels[it] ?: it },
        levelIsMachine = row.levelIsMachine,
        onLevel = { propose(diagnostics.normalised().withRowLevel(row.index, it), row.index) },
        levelEditable = row.levelEditable,
        oneSession = row.oneSession,
        onRemove = if (row.removable) {
            { onChange(diagnostics.normalised().withRowRemoved(row.index)) }
        } else {
            null
        },
        // The volume rides on the secondary line rather than getting a column or
        // a tone of its own. It belongs next to the description because it *is*
        // part of the description — "every exception raised" and "191,000 lines a
        // session" are one fact about this stop, and separating them is how the
        // second half stopped being read.
        secondary = listOfNotNull(row.secondary, row.volume?.let { "Expect $it." })
            .joinToString(" ")
            .ifBlank { null },
        caution = row.caution,
    )
}

/**
 * One typed variable: name, value, and a cross.
 *
 * Two plain fields rather than the diagnostic row's name-and-ladder, because
 * there is no ladder — a variable Vessel does not know has no levels to offer.
 * The reserved case is drawn as a caution on the row rather than by refusing the
 * keystroke: a field that silently will not accept `WINEPREFIX` looks broken,
 * and the useful thing to say is *why* it cannot be set.
 */
@Composable
private fun EnvRow(
    row: EnvSetting,
    /** Names the other rows already hold: not offered, and named if typed. */
    taken: Set<String>,
    onName: (String) -> Unit,
    onValue: (String) -> Unit,
    onRemove: () -> Unit,
) {
    val known = knownEnvFor(row.name)
    Column(Modifier.fillMaxWidth().padding(top = Vessel.metrics.s6)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Vessel.metrics.s6),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // A combo, not a dropdown: the six Vessel knows about are worth
            // offering, and the seventh is the reason this table exists.
            VComboField(
                value = row.name,
                // A name another row already holds is not offered. Two rows for
                // one variable is not an error -- the composer is a
                // LinkedHashMap and the last put wins -- but it is a way to set
                // something twice and read the wrong half.
                options = KNOWN_ENV.map { it.name }.filter { it !in taken },
                onValueChange = onName,
                placeholder = "TU_AUTOTUNE_ALGO",
                modifier = Modifier.weight(1f),
            )
            // The value is a picker only where the answers are a closed set.
            // `DXVK_CONFIG` takes config lines and has no list to offer, so it
            // gets a plain field with a real example in it.
            if (known != null && known.values.isNotEmpty()) {
                VComboField(
                    value = row.value,
                    options = known.values,
                    onValueChange = onValue,
                    placeholder = known.values.first(),
                    modifier = Modifier.weight(1f),
                )
            } else {
                VTextField(
                    value = row.value,
                    onValueChange = onValue,
                    placeholder = known?.placeholder ?: "value",
                    modifier = Modifier.weight(1f),
                )
            }
            VIconButton(VIcons.X, "Remove", onRemove)
        }
        // The sentence is the half worth keeping from the manifest: knowing a
        // variable exists is nearly useless on its own.
        known?.secondary?.let {
            Text(
                it,
                style = Vessel.type.bodySmall,
                color = Vessel.colors.textMuted,
                modifier = Modifier.padding(top = Vessel.metrics.s3),
            )
        }
        // Typed rather than picked, and already on another row. Said rather
        // than refused, for the reason in this function's own note about
        // reserved names: a field that will not accept a keystroke looks
        // broken, and the useful thing is to say what will actually happen.
        if (row.name.isNotBlank() && row.name in taken) {
            VCaution(
                "${'$'}{row.name} is set on another row too. The environment is composed in " +
                    "order and the last one wins, so this row is the one that counts.",
            )
        }
        known?.caution?.let { VCaution(it) }
        if (row.isReserved) {
            VCaution(
                "Vessel sets this one itself — it names a path inside this container, " +
                    "so a session cannot be allowed to move it. This row is ignored.",
            )
        }
    }
}

/**
 * The three caps, the storage they imply, and the storage actually in use.
 *
 * **A screen that raises a storage ceiling has to show the storage**, so the
 * readout and *Delete all logs* sit directly under the controls that produced
 * them. The worst case is computed from the chosen values and the fixed
 * ten-session history, so the number moves as the dropdowns do.
 *
 * The two byte caps and the rate limit carry one sentence between them because
 * they are one decision: at roughly 120 bytes a line the rate decides how fast
 * the byte caps are reached, so raising either alone buys nothing.
 */
@Composable
private fun KeepSection(
    state: DiagnosticsUiState,
    onChange: (ContainerDiagnostics) -> Unit,
    onDeleteLogs: () -> Unit,
) {
    val diagnostics = state.diagnostics
    val limits = diagnostics.limits
    Column(verticalArrangement = Arrangement.spacedBy(Vessel.metrics.s11)) {
        Row(horizontalArrangement = Arrangement.spacedBy(Vessel.metrics.s8)) {
            Column(Modifier.weight(1f)) {
                VLabeledField(label = "First part of a session") {
                    VDropdownField(
                        options = SessionLogLimits.HEAD_LADDER.map { it.toString() },
                        labelFor = { megabytes(it.toLong()) },
                        selected = limits.headBytes.toString(),
                        onSelect = {
                            onChange(diagnostics.withLimits(limits.copy(headBytes = it.toLong())))
                        },
                        valueIsMachine = true,
                    )
                }
            }
            Column(Modifier.weight(1f)) {
                VLabeledField(label = "Last part kept") {
                    VDropdownField(
                        options = SessionLogLimits.TAIL_LADDER.map { it.toString() },
                        labelFor = { megabytes(it.toLong()) },
                        selected = limits.tailBytes.toString(),
                        onSelect = {
                            onChange(diagnostics.withLimits(limits.copy(tailBytes = it.toLong())))
                        },
                        valueIsMachine = true,
                    )
                }
            }
        }
        VLabeledField(
            label = "Lines a second before dropping",
            help = "These three move together. At about 120 bytes a line, this rate fills the " +
                "two caps above in ${secondsToFill(limits)} seconds, so raising one without the " +
                "others buys nothing. Ten sessions are kept per container either way, and every " +
                "drop says so in the log.",
        ) {
            VDropdownField(
                options = SessionLogLimits.RATE_LADDER.map { it.toString() },
                labelFor = { thousands(it.toInt()) },
                selected = limits.rateLimitLines.toString(),
                onSelect = {
                    onChange(diagnostics.withLimits(limits.copy(rateLimitLines = it.toInt())))
                },
                valueIsMachine = true,
            )
        }

        Column(
            Modifier
                .fillMaxWidth()
                .background(Vessel.colors.surfaceRaised, Vessel.metrics.shapeMd)
                .vRing(Vessel.colors.divider, Vessel.metrics.shapeMd)
                .padding(Vessel.metrics.s11),
            verticalArrangement = Arrangement.spacedBy(Vessel.metrics.s8),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Vessel.metrics.s8),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Logs stored for this container",
                    style = Vessel.type.bodySmall,
                    color = Vessel.colors.textLabel,
                    modifier = Modifier.weight(1f),
                )
                Text(state.usageLabel, style = Vessel.type.mono)
            }
            VProgressBar(state.usageFraction)
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Vessel.metrics.s8),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    state.ceilingLabel,
                    style = Vessel.type.monoSmall,
                    color = Vessel.colors.textMuted,
                    modifier = Modifier.weight(1f),
                )
                VButton(
                    "Delete all logs",
                    onDeleteLogs,
                    style = VButtonStyle.Danger,
                    icon = VIcons.Trash,
                    enabled = state.sessionCount > 0,
                )
            }
        }
    }
}

/** The panel's own header, which replaces the container sheet's while it is open. */
@Composable
fun DiagnosticsHeader(containerName: String, onCollapse: () -> Unit) {
    VSheetHeader(
        title = "Diagnostics",
        // The container, in mono, because the hazard of a screen like this is
        // arming something expensive on the wrong one.
        subtitle = containerName,
        trailing = {
            VIconButton(
                VIcons.CaretDown,
                contentDescription = "Close diagnostics",
                onClick = onCollapse,
                tint = Vessel.colors.textMuted,
            )
        },
    )
}

/**
 * *Copy to another container*, as a short list of the containers there are.
 *
 * Diagnosing usually means comparing two containers, and re-entering a dozen rows
 * by hand is where people give up.
 */
@Composable
private fun CopyToContainer(targets: List<Pair<String, String>>, onCopyTo: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column {
        VButton(
            "Copy to another container",
            { open = true },
            style = VButtonStyle.Secondary,
            icon = VIcons.Copy,
            enabled = targets.isNotEmpty(),
        )
        if (open) {
            VDialogCard(onDismiss = { open = false }) {
                Text("Copy diagnostics to", style = Vessel.type.subtitle)
                Text(
                    "The rows and limits on this screen replace that container's. Its own " +
                        "settings are not touched.",
                    style = Vessel.type.bodySmall,
                    color = Vessel.colors.textMuted,
                )
                targets.forEach { (id, name) ->
                    VButton(
                        name,
                        {
                            open = false
                            onCopyTo(id)
                        },
                        style = VButtonStyle.Secondary,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Vessel.metrics.s8, Alignment.End),
                ) {
                    VButton("Cancel", { open = false }, style = VButtonStyle.Ghost)
                }
            }
        }
    }
}

/** A cap as the label a dropdown shows. Whole megabytes; every rung is one. */
private fun megabytes(bytes: Long): String = "${bytes / (1024 * 1024)} MB"

/** `2 000`, thin-spaced, because a four-digit rate reads as a year otherwise. */
private fun thousands(value: Int): String =
    value.toString().reversed().chunked(3).joinToString(" ").reversed()

/**
 * How long a runaway takes to fill both caps, at the chosen rate.
 *
 * 120 bytes a line is the figure `docs/DIAGNOSTICS-UI.md` §5 uses and it is a
 * round number rather than a measurement — which is why the sentence around it
 * says "about". Shown because the whole argument for moving these three together
 * is arithmetic the user cannot be expected to do.
 */
private fun secondsToFill(limits: SessionLogLimits): Long {
    val bytesPerSecond = limits.rateLimitLines.toLong() * BYTES_PER_LINE
    return (limits.worstCaseBytesPerSession / bytesPerSecond).coerceAtLeast(1)
}

private const val BYTES_PER_LINE = 120L

@Preview(showBackground = true, backgroundColor = 0xFF161826, widthDp = 421, heightDp = 927)
@Composable
private fun DiagnosticsPanelPreview() {
    VesselTheme {
        Column(Modifier.padding(Vessel.metrics.s17)) {
            DiagnosticsHeader("Display proof", {})
            DiagnosticsPanel(
                state = DiagnosticsUiState(
                    // Out of the declaration rather than typed here: no name
                    // from LOGGABLES appears as a literal in this layer, and a
                    // preview is not an excuse to be the first.
                    diagnostics = ADDABLE_LOGGABLES.first { it.caution != null }.let { pick ->
                        ContainerDiagnostics()
                            .withRowAdded(pick.family)
                            .withRowNamed(0, pick.name)
                    },
                    usageLabel = "14.2 MB",
                    usageFraction = 0.03f,
                    ceilingLabel = "10 sessions · 480 MB at these limits",
                    sessionCount = 4,
                    neverLaunched = false,
                    otherContainers = listOf("c2" to "Canoe test"),
                ),
                onChange = {},
                onDeleteLogs = {},
                onCopyTo = {},
            )
        }
    }
}
