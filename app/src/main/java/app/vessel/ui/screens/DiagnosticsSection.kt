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
import app.vessel.core.ContainerDiagnostics
import app.vessel.core.DiagnosticRow
import app.vessel.core.SessionLogLimits
import app.vessel.core.costWarning
import app.vessel.core.diagnosticRows
import app.vessel.core.loggableFor
import app.vessel.ui.components.VButton
import app.vessel.ui.components.VButtonStyle
import app.vessel.ui.components.VConfirmSheet
import app.vessel.ui.components.VDiagnosticRow
import app.vessel.ui.components.VDialogCard
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
 * **The list starts with what Vessel already sends.** Those rows are present and
 * disabled — the screen is a truthful account of the environment rather than a
 * list of additions over something invisible. That is also why there is no
 * explanatory paragraph at the top any more: the rows say what a paragraph used
 * to claim, in a form the reader can inspect.
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
            pending = costWarning(caution, row.isOneSession) to next
        }
    }

    Column(modifier.fillMaxWidth()) {
        VSectionHeader("What to log")
        rows.forEach { row -> InventoryRow(row, diagnostics, ::propose, onChange) }

        VButton(
            "Add",
            { onChange(diagnostics.withRowAdded()) },
            style = VButtonStyle.Primary,
            icon = VIcons.Plus,
            modifier = Modifier.fillMaxWidth().padding(top = Vessel.metrics.s8),
        )
        Text(
            "Rows without a cross are what Vessel always sends; they cannot be changed here. " +
                "Everything below them is written after, and a later term wins.",
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
    propose: (ContainerDiagnostics, Int) -> Unit,
    onChange: (ContainerDiagnostics) -> Unit,
) {
    VDiagnosticRow(
        name = row.name,
        nameOptions = ADDABLE_LOGGABLES.map { it.name },
        onName = { propose(diagnostics.withRowNamed(row.index, it), row.index) },
        levels = row.levels,
        levelLabel = { row.levelLabels[it] ?: it },
        level = row.level,
        levelIsMachine = row.levelIsMachine,
        onLevel = { propose(diagnostics.withRowLevel(row.index, it), row.index) },
        oneSession = row.oneSession,
        onRemove = if (row.removable) {
            { onChange(diagnostics.withRowRemoved(row.index)) }
        } else {
            null
        },
        secondary = row.secondary,
        caution = row.caution,
        nameEditable = row.nameEditable,
        levelEditable = row.levelEditable,
        nameIsInvalid = row.nameIsInvalid,
    )
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
                    diagnostics = ContainerDiagnostics()
                        .withRowAdded()
                        .withRowNamed(0, ADDABLE_LOGGABLES.first { it.caution != null }.name),
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
