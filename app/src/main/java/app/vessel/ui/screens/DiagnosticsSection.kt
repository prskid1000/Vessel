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
import app.vessel.core.ContainerDiagnostics
import app.vessel.core.DIAGNOSTIC_WINE_CHANNELS
import app.vessel.core.DangerousControl
import app.vessel.core.DxvkLogLevel
import app.vessel.core.SessionLogLimits
import app.vessel.core.Vkd3dLogLevel
import app.vessel.core.WineChannelLevel
import app.vessel.core.isSwitchChannel
import app.vessel.core.rawTermsIssue
import app.vessel.ui.components.VButton
import app.vessel.ui.components.VButtonStyle
import app.vessel.ui.components.VConfirmSheet
import app.vessel.ui.components.VDiagnosticRow
import app.vessel.ui.components.VDisclosureRow
import app.vessel.ui.components.VDisclosureStyle
import app.vessel.ui.components.VDropdownField
import app.vessel.ui.components.VIcons
import app.vessel.ui.components.VLabeledField
import app.vessel.ui.components.VProgressBar
import app.vessel.ui.components.VRule
import app.vessel.ui.components.VTag
import app.vessel.ui.components.VTagTone
import app.vessel.ui.components.VTextField
import app.vessel.ui.components.VToggle
import app.vessel.ui.theme.Vessel
import app.vessel.ui.theme.VesselTheme
import app.vessel.ui.theme.vRing
import app.vessel.ui.vm.DiagnosticsUiState

/**
 * Diagnostics: what this container's next session is asked to say about itself.
 *
 * **A section of the container sheet, not a pushed screen**, which is
 * `docs/DIAGNOSTICS-UI.md` §9's first open question answered. Every short thing
 * in this product is a sheet over the thing it is about, and a push would lose
 * the container behind it — but the surface is fifteen-odd controls, which is
 * longer than the sheet `docs/DESIGN.md:298-303` already calls full. Both are
 * satisfied by making it four groups that collapse to one row each, with **one
 * group open at a time**: the sheet never holds more than one group's worth of
 * rows, and the four collapsed rows carry their own state so the whole
 * configuration is legible without opening anything.
 *
 * **Not a manifest group, and not more rows in the container sheet.** The
 * manifest's law is that a setting must be explainable in one plain sentence to
 * someone who does not know what a translator is (`params-manifest.json:9-12`),
 * and `VKD3D_SHADER_DEBUG` is not. Diagnostics has a different audience —
 * someone whose session is already broken, who has been told what to switch on —
 * so it gets a different surface with different rules. Putting the resolution
 * picker here would be the same mistake in the other direction.
 *
 * **The banner at the top is load-bearing.** A screen of Off switches reads as
 * "logging is disabled", which is the opposite of true: Vessel already records
 * errors on every channel, missing DLLs, loaded modules and the program's own
 * messages, and everything here adds to that.
 */
@Composable
fun DiagnosticsSection(
    state: DiagnosticsUiState,
    onChange: (ContainerDiagnostics) -> Unit,
    onDeleteLogs: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    var group by remember { mutableStateOf<DiagnosticsGroup?>(null) }

    // The value the user has asked for and the control that made it dangerous.
    // Held rather than applied, so cancelling the warning leaves the record
    // exactly as it was rather than needing an undo.
    var pending by remember { mutableStateOf<Pair<DangerousControl, ContainerDiagnostics>?>(null) }
    var confirmingDeleteLogs by remember { mutableStateOf(false) }

    val diagnostics = state.diagnostics

    /**
     * Apply an edit, unless it arms something that fills the log in seconds.
     *
     * The gate is *newly* dangerous rather than dangerous: moving DXVK from
     * `debug` to `trace` is already armed and does not need asking twice, and a
     * second dialog for the same decision is how a warning becomes a thing people
     * tap through. See [DangerousControl].
     */
    fun propose(next: ContainerDiagnostics) {
        val armed = DangerousControl.entries.firstOrNull {
            it.isDangerous(next) && !it.isDangerous(diagnostics)
        }
        if (armed == null) onChange(next) else pending = armed to next
    }

    Column(modifier.fillMaxWidth()) {
        VDisclosureRow(
            title = "Diagnostics",
            help = "What the next session is asked to say about itself. Nothing here changes " +
                "how the program runs.",
            state = state.summary,
            expanded = open,
            onToggle = { open = !open },
        )

        if (!open) return@Column

        Column(verticalArrangement = Arrangement.spacedBy(Vessel.metrics.s11)) {
            Text(
                "Vessel already records errors, missing DLLs, loaded modules and the program's " +
                    "own messages. Everything below adds to that.",
                style = Vessel.type.bodySmall,
                color = Vessel.colors.textLabel,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Vessel.colors.surfaceRaised, Vessel.metrics.shapeMd)
                    .vRing(Vessel.colors.divider, Vessel.metrics.shapeMd)
                    .padding(horizontal = Vessel.metrics.s11, vertical = Vessel.metrics.s8),
            )

            DiagnosticsGroup.entries.forEach { entry ->
                val expanded = group == entry
                VDisclosureRow(
                    title = entry.title,
                    style = VDisclosureStyle.Group,
                    state = entry.state(state),
                    expanded = expanded,
                    // One at a time: opening a second closes the first, which is
                    // what keeps the sheet from becoming a page.
                    onToggle = { group = if (expanded) null else entry },
                )
                VRule(verticalMargin = Vessel.metrics.none)
                if (!expanded) return@forEach
                Column(Modifier.padding(top = Vessel.metrics.s6)) {
                    when (entry) {
                        DiagnosticsGroup.WINE -> WineGroup(diagnostics, ::propose)
                        DiagnosticsGroup.TRANSLATORS -> TranslatorGroup(diagnostics, ::propose)
                        DiagnosticsGroup.STORAGE -> StorageGroup(
                            state,
                            ::propose,
                            onDeleteLogs = { confirmingDeleteLogs = true },
                        )

                        DiagnosticsGroup.RAW -> RawGroup(diagnostics, ::propose)
                    }
                }
            }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Vessel.metrics.s8),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(Modifier.weight(1f)) {}
                VButton(
                    "Reset all",
                    { onChange(ContainerDiagnostics.DEFAULT) },
                    style = VButtonStyle.Ghost,
                    enabled = !diagnostics.isDefault,
                )
            }
        }
    }

    val danger = pending
    if (danger != null) {
        VConfirmSheet(
            title = danger.first.title,
            // The three concrete things, plus the one consequence a container
            // that has never run adds: WINEDEBUG is in BOOTSTRAP_SESSION_ENV, so
            // whatever is armed here also reaches `wineboot` while the prefix is
            // being built — and a wineboot given too much looks like a hang.
            message = danger.first.warning + if (state.neverLaunched) {
                " This container has never been launched, so the next launch also builds its " +
                    "Windows prefix with this switched on: expect it to take much longer than " +
                    "usual."
            } else {
                ""
            },
            confirmLabel = "Turn it on",
            onConfirm = {
                onChange(danger.second)
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
 * The four partitions, in the order they are drawn.
 *
 * Grouped by *who answers the question* rather than by variable name: Wine's own
 * channels, then the layers underneath it, then how much of the answer is kept,
 * then the escape hatch. The raw field is last on purpose — see [RawGroup].
 */
enum class DiagnosticsGroup(val title: String) {
    WINE("Wine"),
    TRANSLATORS("Translators and driver"),
    STORAGE("How much is kept"),
    RAW("Raw terms"),
    ;

    /** The one fact worth showing while this group is closed. */
    fun state(state: DiagnosticsUiState): String = when (this) {
        WINE -> {
            val changed = state.diagnostics.wineChannels.size
            if (changed == 0) "defaults" else "$changed changed"
        }

        TRANSLATORS -> if (state.translatorsAreDefault) "defaults" else "changed"
        STORAGE -> state.usageLabel
        RAW -> if (state.diagnostics.rawTerms.isBlank()) "empty" else "set"
    }
}

/**
 * Wine's channels, one row each, with the level each runs at.
 *
 * **Two control shapes, because the channels are two different things.** Seven
 * of them are ladders: a level is a real question for a channel whose classes
 * carry different kinds of message. `relay` and `seh` are switches, and drawing
 * them as ladders would be a lie about what they contain — every `_(relay)` site
 * in ntdll is `TRACE_(relay)`, so its Errors and Warnings stops are silence, and
 * `seh`'s error tier is already on through `err+all`, so its only stop that
 * changes anything is the trace one.
 *
 * The four channels the fixed prefix already names come first and show the levels
 * they are already running at. That ordering is the hierarchy, the same rule the
 * manifest states for itself.
 */
@Composable
private fun WineGroup(
    diagnostics: ContainerDiagnostics,
    propose: (ContainerDiagnostics) -> Unit,
) {
    Column {
        DIAGNOSTIC_WINE_CHANNELS.forEach { spec ->
            val level = diagnostics.levelOf(spec.channel)
            val switch = isSwitchChannel(spec.channel)
            VDiagnosticRow(
                title = spec.title,
                machineName = spec.channel,
                help = spec.help,
                caution = spec.caution,
                // The tag appears on the two switch channels always — they are
                // one-launch by construction — and on any ladder that is sitting
                // at a stop that arms it, which today is `d3d` above Errors.
                tag = armedTag(
                    always = switch,
                    armed = DangerousControl.entries.any {
                        it.id.removePrefix("wine.") == spec.channel && it.isDangerous(diagnostics)
                    },
                ),
                control = {
                    if (switch) {
                        VToggle(
                            checked = level == WineChannelLevel.EVERYTHING,
                            onCheckedChange = { on ->
                                propose(
                                    diagnostics.withWineChannel(
                                        spec.channel,
                                        if (on) {
                                            WineChannelLevel.EVERYTHING
                                        } else {
                                            spec.defaultLevel
                                        },
                                    ),
                                )
                            },
                        )
                    } else {
                        VDropdownField(
                            options = spec.levels.map { it.name },
                            labelFor = { WineChannelLevel.valueOf(it).label },
                            selected = level.name,
                            onSelect = {
                                propose(
                                    diagnostics.withWineChannel(
                                        spec.channel,
                                        WineChannelLevel.valueOf(it),
                                    ),
                                )
                            },
                        )
                    }
                },
            )
        }
        Text(
            "Every change here is appended to Vessel's own channel list, so a later term wins " +
                "and the defaults above cannot be deleted by accident.",
            style = Vessel.type.bodySmall,
            color = Vessel.colors.textMuted,
            modifier = Modifier.padding(top = Vessel.metrics.s6),
        )
    }
}

/**
 * The layers under Wine, each in its own vocabulary.
 *
 * **Four subsystems, four different controls, and the difference is not smoothed
 * out.** DXVK is one minimum severity over six names; vkd3d is a different six
 * names in a different order — `info` sits between `err` and `fixme`, so `warn`
 * already carries both — and it has two independent channels, not one. FEX is a
 * single boolean. Mesa's logger choice is another boolean. Drawing these as one
 * row type repeated four times would be a screen that lies about what it sets;
 * the wire words are shown in mono for the same reason, because they are what an
 * issue thread will name.
 *
 * **Turnip's flags are not here, and that is a finding rather than an omission.**
 * `TU_DEBUG` is a flag list and would have to be a multi-select — but Mesa picks
 * its logger at init and under Android the default is logcat, which Vessel does
 * not read, so every one of those flags is a switch whose output the product
 * cannot show. *Driver messages in the log* below is the fix; the flags follow it
 * once a device run confirms it works.
 */
@Composable
private fun TranslatorGroup(
    diagnostics: ContainerDiagnostics,
    propose: (ContainerDiagnostics) -> Unit,
) {
    Column {
        VDiagnosticRow(
            title = "Direct3D 9 to 11",
            machineName = "DXVK_LOG_LEVEL",
            help = "How the Direct3D translator reports itself. info already names the reason a " +
                "device was rejected, which is the usual question.",
            tag = armedTag(always = false, armed = DangerousControl.DXVK_VERBOSE.isDangerous(diagnostics)),
            control = {
                VDropdownField(
                    options = DxvkLogLevel.entries.map { it.name },
                    labelFor = { DxvkLogLevel.valueOf(it).wire },
                    selected = diagnostics.dxvkLevel.name,
                    onSelect = { propose(diagnostics.withDxvkLevel(DxvkLogLevel.valueOf(it))) },
                )
            },
        )
        VDiagnosticRow(
            title = "Direct3D 12",
            machineName = "VKD3D_DEBUG",
            help = "The Direct3D 12 translator's own messages.",
            tag = armedTag(always = false, armed = DangerousControl.VKD3D_TRACE.isDangerous(diagnostics)),
            control = {
                VDropdownField(
                    options = Vkd3dLogLevel.entries.map { it.name },
                    labelFor = { Vkd3dLogLevel.valueOf(it).wire },
                    selected = diagnostics.vkd3dLevel.name,
                    onSelect = { propose(diagnostics.withVkd3dLevel(Vkd3dLogLevel.valueOf(it))) },
                )
            },
        )
        VDiagnosticRow(
            title = "Direct3D 12 shader translation",
            machineName = "VKD3D_SHADER_DEBUG",
            help = "Shader compilation failures, which the row above does not carry — it is a " +
                "separate channel with its own level.",
            tag = armedTag(
                always = false,
                armed = DangerousControl.VKD3D_SHADER_TRACE.isDangerous(diagnostics),
            ),
            control = {
                VDropdownField(
                    options = Vkd3dLogLevel.entries.map { it.name },
                    labelFor = { Vkd3dLogLevel.valueOf(it).wire },
                    selected = diagnostics.vkd3dShaderLevel.name,
                    onSelect = {
                        propose(diagnostics.withVkd3dShaderLevel(Vkd3dLogLevel.valueOf(it)))
                    },
                )
            },
        )
        Text(
            "Each translator keeps its own words and its own order: vkd3d puts info between err " +
                "and fixme, and it is not renamed to match DXVK.",
            style = Vessel.type.bodySmall,
            color = Vessel.colors.textMuted,
            modifier = Modifier.padding(bottom = Vessel.metrics.s6),
        )
        VDiagnosticRow(
            title = "x86 translator messages",
            machineName = "FEX_SILENTLOG",
            help = "Whether the x86 translator is allowed to speak. Off hides mistakes in its " +
                "own configuration as well as its crashes, which is why Vessel leaves it on.",
            control = {
                VToggle(
                    checked = diagnostics.fexMessages,
                    onCheckedChange = { propose(diagnostics.withFexMessages(it)) },
                )
            },
        )
        VDiagnosticRow(
            title = "Driver messages in the log",
            machineName = "MESA_LOG",
            help = "Without this the graphics driver writes to the Android system log, where " +
                "Vessel cannot read it — including the one line that proves Turnip loaded.",
            control = {
                VToggle(
                    checked = diagnostics.driverMessagesInLog,
                    onCheckedChange = { propose(diagnostics.withDriverMessagesInLog(it)) },
                )
            },
        )
    }
}

/**
 * The three caps, the storage they imply, and the storage actually in use.
 *
 * **A screen that raises a storage ceiling has to show the storage**, so the
 * readout and *Delete all logs* sit directly under the controls that produced
 * them rather than somewhere else in the sheet. The worst case is computed from
 * the chosen values and the fixed ten-session history, so the number moves as the
 * dropdowns do.
 *
 * The two byte caps and the rate limit are drawn as one block with one sentence
 * because they are one decision: at roughly 120 bytes a line the rate limit
 * decides how fast the byte caps are reached, so raising either alone buys
 * nothing.
 */
@Composable
private fun StorageGroup(
    state: DiagnosticsUiState,
    propose: (ContainerDiagnostics) -> Unit,
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
                            propose(diagnostics.withLimits(limits.copy(headBytes = it.toLong())))
                        },
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
                            propose(diagnostics.withLimits(limits.copy(tailBytes = it.toLong())))
                        },
                    )
                }
            }
        }
        VLabeledField(
            label = "Lines a second before dropping",
            help = "These three move together. At about 120 bytes a line, this rate fills the " +
                "two caps above in ${secondsToFill(limits)} seconds, so raising one without the " +
                "others buys nothing. Every drop says so in the log.",
        ) {
            VDropdownField(
                options = SessionLogLimits.RATE_LADDER.map { it.toString() },
                labelFor = { it },
                selected = limits.rateLimitLines.toString(),
                onSelect = {
                    propose(diagnostics.withLimits(limits.copy(rateLimitLines = it.toInt())))
                },
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

/**
 * The raw `WINEDEBUG` escape hatch, and it is last for a reason.
 *
 * It is the one control here that can produce anything Wine understands,
 * including the firehoses the curated rows deliberately gate — so it sits in its
 * own group, behind its own disclosure, at the bottom of the surface, where it
 * cannot be reached by scrolling past something else. `ParamType.TEXT` in the
 * manifest calls itself "the deliberate exception, not a loophole"; this is a
 * second instance of the same exception and carries the same caveat.
 *
 * Appended after every row above, so a later term wins and the fixed prefix
 * cannot be deleted. Two validations, both read out of the parser — see
 * [rawTermsIssue].
 */
@Composable
private fun RawGroup(
    diagnostics: ContainerDiagnostics,
    propose: (ContainerDiagnostics) -> Unit,
) {
    val issue = rawTermsIssue(diagnostics.rawTerms)
    Column {
        VLabeledField(
            label = "Anything else, in Wine's own terms",
            help = "Appended after every channel above, so a later term wins and Vessel's own " +
                "list cannot be deleted. Leave empty unless following specific advice; scope a " +
                "loud channel to one program with program.exe:+channel.",
        ) {
            VTextField(
                value = diagnostics.rawTerms,
                onValueChange = { propose(diagnostics.withRawTerms(it)) },
                placeholder = "metro.exe:+relay,-heap",
            )
        }
        if (issue != null) {
            Text(
                issue.message,
                style = Vessel.type.bodySmall,
                color = if (issue.blocking) Vessel.colors.danger else Vessel.colors.warn,
                modifier = Modifier.padding(top = Vessel.metrics.s6),
            )
        }
    }
}

/**
 * The *one launch* marker, or nothing.
 *
 * A tag rather than a colour on the row: the fact is temporal — this control is
 * spent by the next launch — and a warning tone would say "this value is
 * dangerous", which is already what the confirmation and the caution line say.
 * Two ways of saying the same thing is how a warning stops being read.
 */
private fun armedTag(always: Boolean, armed: Boolean): (@Composable () -> Unit)? =
    if (always || armed) {
        { VTag("one launch", tone = VTagTone.Neutral) }
    } else {
        null
    }

/** A cap as the label a dropdown shows. Whole megabytes; every rung is one. */
private fun megabytes(bytes: Long): String = "${bytes / (1024 * 1024)} MB"

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
private fun DiagnosticsSectionPreview() {
    VesselTheme {
        Column(Modifier.padding(Vessel.metrics.s17)) {
            DiagnosticsSection(
                state = DiagnosticsUiState(
                    diagnostics = ContainerDiagnostics(),
                    usageLabel = "14.2 MB",
                    usageFraction = 0.18f,
                    ceilingLabel = "10 sessions · 480 MB at these limits",
                    sessionCount = 4,
                    summary = "all off",
                    neverLaunched = false,
                ),
                onChange = {},
                onDeleteLogs = {},
            )
        }
    }
}
