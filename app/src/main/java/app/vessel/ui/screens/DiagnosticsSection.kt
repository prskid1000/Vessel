package app.vessel.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import app.vessel.core.DRIVER_LOG_FLAG
import app.vessel.core.SUBSYSTEM_FLAGS
import app.vessel.core.SUBSYSTEM_LEVELS
import app.vessel.core.SessionLogLimits
import app.vessel.core.TURNIP_FLAGS
import app.vessel.core.WINE_CHANNEL_CATALOGUE
import app.vessel.core.WineChannelLevel
import app.vessel.core.isChannelName
import app.vessel.core.oneSessionWarning
import app.vessel.core.wineChannelInfo
import app.vessel.ui.components.VButton
import app.vessel.ui.components.VButtonStyle
import app.vessel.ui.components.VCaution
import app.vessel.ui.components.VCheckRow
import app.vessel.ui.components.VConfirmSheet
import app.vessel.ui.components.VDiagnosticRow
import app.vessel.ui.components.VDialogCard
import app.vessel.ui.components.VDisclosureRow
import app.vessel.ui.components.VDisclosureStyle
import app.vessel.ui.components.VDropdownField
import app.vessel.ui.components.VIconButton
import app.vessel.ui.components.VIcons
import app.vessel.ui.components.VInfoBox
import app.vessel.ui.components.VLabeledField
import app.vessel.ui.components.VProgressBar
import app.vessel.ui.components.VRule
import app.vessel.ui.components.VSheetHeader
import app.vessel.ui.components.VTag
import app.vessel.ui.components.VTagTone
import app.vessel.ui.components.VTextField
import app.vessel.ui.theme.Vessel
import app.vessel.ui.theme.VesselTheme
import app.vessel.ui.theme.vRing
import app.vessel.ui.vm.DiagnosticsUiState

/**
 * Diagnostics: what this container's next session is asked to say about itself,
 * **on top of what it already says**.
 *
 * **A nested sheet, not a pushed screen and not more rows in the form.** Every
 * short thing in this product is a sheet over the thing it is about, so a push
 * would lose the container behind it — but this is more controls than the
 * container sheet, which `docs/DESIGN.md:298-303` already calls full. Both are
 * satisfied by taking the sheet over: opening Diagnostics replaces the settings
 * form with this panel and swaps the header's Save for a collapse chevron, and
 * the three groups inside it open one at a time so the panel never holds more
 * than one group's worth of rows. Back collapses the panel before it closes the
 * sheet.
 *
 * **The invisible baseline is why the Wine group starts empty.**
 * `WINEDEBUG_CHANNELS` is always on and is never drawn. Everything here is an
 * *addition*, which is what makes *+ Add a channel* the primary affordance and
 * what makes a per-row remove cross mean something. The banner at the top is
 * load-bearing for the same reason: an empty screen otherwise reads as "logging
 * is disabled", which is the opposite of true.
 *
 * **Nothing in this file names a channel or a variable.** The rows come from
 * `WINE_CHANNEL_CATALOGUE`, `SUBSYSTEM_LEVELS`, `SUBSYSTEM_FLAGS` and
 * `TURNIP_FLAGS`; adding one is a data edit. The only `when` here is over the
 * three groups, which are the layout.
 */
@Composable
fun DiagnosticsPanel(
    state: DiagnosticsUiState,
    onChange: (ContainerDiagnostics) -> Unit,
    onDeleteLogs: () -> Unit,
    onCopyTo: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var group by remember { mutableStateOf<DiagnosticsGroup?>(null) }

    // The value the user has asked for and the warning that stands between them.
    // Held rather than applied, so cancelling leaves the record exactly as it was
    // rather than needing an undo.
    var pending by remember { mutableStateOf<Pair<String, ContainerDiagnostics>?>(null) }
    var confirmingDeleteLogs by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }

    val diagnostics = state.diagnostics

    /**
     * Apply an edit, unless it arms something that fills the log in seconds.
     *
     * The gate is *newly* armed rather than armed: moving DXVK from `debug` to
     * `trace` is already armed and does not need asking twice, and a second
     * dialog for the same decision is how a warning becomes a thing people tap
     * through.
     */
    fun propose(next: ContainerDiagnostics, detail: String?) {
        val fresh = next.oneSessionIds() - diagnostics.oneSessionIds()
        if (fresh.isEmpty()) onChange(next) else pending = oneSessionWarning(detail) to next
    }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Vessel.metrics.s11)) {
        VInfoBox(
            "Vessel already records errors, missing DLLs, loaded modules and the program's own " +
                "messages. Everything below adds to that.",
        )

        DiagnosticsGroup.entries.forEach { entry ->
            val expanded = group == entry
            VDisclosureRow(
                title = entry.title,
                style = VDisclosureStyle.Group,
                state = entry.state(state),
                expanded = expanded,
                // One at a time: opening a second closes the first, which is what
                // keeps a sheet from becoming a page.
                onToggle = { group = if (expanded) null else entry },
            )
            VRule(verticalMargin = Vessel.metrics.none)
            if (!expanded) return@forEach
            Column(Modifier.padding(top = Vessel.metrics.s6)) {
                when (entry) {
                    DiagnosticsGroup.WINE -> WineGroup(
                        diagnostics = diagnostics,
                        propose = ::propose,
                        onAdd = { picking = true },
                    )

                    DiagnosticsGroup.TRANSLATORS -> TranslatorGroup(diagnostics, ::propose, onChange)
                    DiagnosticsGroup.STORAGE -> StorageGroup(
                        state = state,
                        onChange = onChange,
                        onDeleteLogs = { confirmingDeleteLogs = true },
                    )
                }
            }
        }

        Row(
            Modifier.fillMaxWidth(),
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

    if (picking) {
        ChannelPicker(
            taken = diagnostics.addedChannels,
            onPick = { channel ->
                picking = false
                val next = diagnostics.withChannelAdded(channel)
                propose(next, wineChannelInfo(channel).caution)
            },
            onDismiss = { picking = false },
        )
    }

    val danger = pending
    if (danger != null) {
        VConfirmSheet(
            title = "Fills the log in seconds",
            // The three concrete things, plus the one consequence a container
            // that has never run adds: WINEDEBUG is in BOOTSTRAP_SESSION_ENV, so
            // whatever is armed also reaches `wineboot` while the prefix is being
            // built — and a wineboot given too much is a hang with an empty
            // drive_c two minutes later.
            message = danger.first + if (state.neverLaunched) {
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

/** The panel's own header, which replaces the container sheet's while it is open. */
@Composable
fun DiagnosticsHeader(containerName: String, onCollapse: () -> Unit) {
    VSheetHeader(
        title = "Diagnostics",
        // The container, in mono, because the whole hazard of a screen like this
        // is arming a firehose on the wrong one.
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
 * The three partitions, in the order they are drawn.
 *
 * Grouped by *who answers the question* rather than by variable name: Wine's own
 * channels, then the layers underneath it, then how much of the answer is kept.
 *
 * There was a fourth — a raw `WINEDEBUG` field — and it went because the *Add a
 * channel* dialog's own free-text field does the same job. See [ChannelPicker].
 */
enum class DiagnosticsGroup(val title: String) {
    WINE("Wine"),
    TRANSLATORS("Translators and driver"),
    STORAGE("How much is kept"),
    ;

    /** The one fact worth showing while this group is closed. */
    fun state(state: DiagnosticsUiState): String = when (this) {
        WINE -> when (val n = state.diagnostics.wineChannels.size) {
            0 -> "none added"
            1 -> "1 channel"
            else -> "$n channels"
        }

        TRANSLATORS -> if (state.translatorsAreDefault) "defaults" else "changed"
        STORAGE -> state.usageLabel
    }
}

/**
 * The Wine channels the user has added — starting at none.
 *
 * One row shape for every channel, drawn from its catalogue entry, so a channel
 * the catalogue has never heard of renders identically to `module`. The mono name
 * is the row; the English description did its work in the picker and is not
 * repeated fifteen times down a sheet.
 */
@Composable
private fun WineGroup(
    diagnostics: ContainerDiagnostics,
    propose: (ContainerDiagnostics, String?) -> Unit,
    onAdd: () -> Unit,
) {
    Column {
        if (diagnostics.wineChannels.isEmpty()) {
            Text(
                "No extra channels. Vessel's own set is already running; add a channel to ask " +
                    "the next session a louder question.",
                style = Vessel.type.bodySmall,
                color = Vessel.colors.textMuted,
                modifier = Modifier.padding(bottom = Vessel.metrics.s8),
            )
        }

        diagnostics.wineChannels.forEach { row ->
            val info = row.info
            VDiagnosticRow(
                name = row.channel,
                caution = info.caution,
                tag = if (info.oneSessionAt != null) {
                    { VTag("one session", tone = VTagTone.Neutral) }
                } else {
                    null
                },
                control = {
                    VDropdownField(
                        options = info.levels.map { it.name },
                        labelFor = { WineChannelLevel.valueOf(it).label },
                        selected = row.level.name,
                        onSelect = {
                            val level = WineChannelLevel.valueOf(it)
                            propose(
                                diagnostics.withChannelLevel(row.channel, level),
                                info.caution,
                            )
                        },
                    )
                },
                trailing = {
                    VIconButton(
                        VIcons.X,
                        contentDescription = "Remove ${row.channel}",
                        onClick = { propose(diagnostics.withChannelRemoved(row.channel), null) },
                        tint = Vessel.colors.textMuted,
                    )
                },
            )
        }

        if (diagnostics.wineChannels.any { it.info.oneSessionAt != null }) {
            VCaution(
                "A channel marked one session fills the log in seconds and slows the run; it " +
                    "switches itself off after the next launch.",
            )
        }

        VButton(
            "Add a channel",
            onAdd,
            style = VButtonStyle.Primary,
            icon = VIcons.Plus,
            modifier = Modifier.fillMaxWidth().padding(top = Vessel.metrics.s8),
        )
        Text(
            "Terms are written in the order above and appended to Vessel's own, so a channel " +
                "added here can change its own level and nothing else.",
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
 * boolean, Mesa's logger choice is another, and Turnip is a flag list with no
 * severity in it at all. Drawing these as one row type repeated would be a screen
 * that lies about what it sets; the wire words are in mono for the same reason,
 * because they are what an issue thread names.
 *
 * All four come out of the declared lists, so this function contains no variable
 * name and no channel name.
 */
@Composable
private fun TranslatorGroup(
    diagnostics: ContainerDiagnostics,
    propose: (ContainerDiagnostics, String?) -> Unit,
    onChange: (ContainerDiagnostics) -> Unit,
) {
    Column {
        SUBSYSTEM_LEVELS.forEach { spec ->
            VDiagnosticRow(
                name = spec.variable,
                secondary = spec.title,
                tag = if (spec.isOneSession(diagnostics.levelOf(spec))) {
                    { VTag("one session", tone = VTagTone.Neutral) }
                } else {
                    null
                },
                control = {
                    VDropdownField(
                        options = spec.options,
                        labelFor = { it },
                        selected = diagnostics.levelOf(spec),
                        onSelect = {
                            propose(
                                diagnostics.withSubsystemLevel(spec.id, it),
                                "${spec.variable}=$it reports per call.",
                            )
                        },
                        valueIsMachine = true,
                    )
                },
            )
        }
        Text(
            "Each translator keeps its own words and its own order: vkd3d puts info between err " +
                "and fixme, and it is not renamed to match DXVK.",
            style = Vessel.type.bodySmall,
            color = Vessel.colors.textMuted,
            modifier = Modifier.padding(bottom = Vessel.metrics.s8),
        )

        SUBSYSTEM_FLAGS.forEach { spec ->
            VCheckRow(
                label = spec.title,
                hint = spec.hint,
                help = spec.help,
                checked = diagnostics.flagOf(spec),
                onToggle = { onChange(diagnostics.withSubsystemFlag(spec.id, !diagnostics.flagOf(spec))) },
            )
        }

        // **The Turnip flags are present and visibly gated rather than hidden.**
        // The switch immediately above is the one that makes their output
        // readable at all, and a control that simply vanishes teaches nobody
        // that. `TU_DEBUG` is a flag list with no severity anywhere in it, so it
        // is a set of switches and not a ladder — drawing it as a level would be
        // a lie about what it holds. The row is a readout with no chevron,
        // because the value is the checkboxes' and not a choice of its own.
        val driverLogOn = diagnostics.flagOf(DRIVER_LOG_FLAG)
        val alpha = if (driverLogOn) 1f else Vessel.colors.disabledAlpha
        VDiagnosticRow(
            name = "TU_DEBUG",
            secondary = "Switches, not levels: " + TURNIP_FLAGS.joinToString(", ") { it.flag } + ".",
            control = {
                Text(
                    diagnostics.turnipFlags.joinToString(",").ifEmpty { "None" },
                    style = Vessel.type.mono,
                    color = Vessel.colors.textMuted.let { it.copy(alpha = it.alpha * alpha) },
                )
            },
        )
        TURNIP_FLAGS.forEach { flag ->
            VCheckRow(
                label = flag.flag,
                help = flag.summary,
                checked = flag.flag in diagnostics.turnipFlags,
                enabled = driverLogOn,
                onToggle = {
                    onChange(
                        diagnostics.withTurnipFlag(flag.flag, flag.flag !in diagnostics.turnipFlags),
                    )
                },
            )
        }
        if (!driverLogOn) {
            VCaution(
                "Unavailable until ${DRIVER_LOG_FLAG.title} is on — without it these flags " +
                    "produce output the product cannot read.",
            )
        }
    }
}

/**
 * The three caps, the storage they imply, and the storage actually in use.
 *
 * **A screen that raises a storage ceiling has to show the storage**, so the
 * readout and *Delete all logs* sit directly under the controls that produced
 * them rather than elsewhere in the sheet. The worst case is computed from the
 * chosen values and the fixed ten-session history, so the number moves as the
 * dropdowns do.
 *
 * The two byte caps and the rate limit carry one sentence between them because
 * they are one decision: at roughly 120 bytes a line the rate decides how fast
 * the byte caps are reached, so raising either alone buys nothing.
 */
@Composable
private fun StorageGroup(
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

/**
 * The channel picker: the catalogue, minus what is already on screen, plus a
 * field for anything it has never heard of.
 *
 * **This is not the 521-channel menu `docs/LOGGING.md:222-230` argues against**,
 * and the difference is the descriptions. That objection is that choosing well
 * among 521 requires knowing what each costs; a list where every entry says what
 * question it answers, and marks the ones that fill a log in seconds, is the
 * opposite thing. The free-text field is what keeps the catalogue a convenience
 * rather than a permitted set — an expert following advice about a channel nobody
 * anticipated types it, and gets the same row.
 *
 * **It is also the only free-text field on this surface, which is why the
 * sentence under it is load-bearing.** A second one — a raw `WINEDEBUG` box at
 * the foot of the screen — was here and was removed: it did the same job as this
 * field for everything except per-program scoping, and two ways to type a channel
 * name is two places for the answer to be. See `composeWineDebug` for the one
 * capability that went with it, and for where it should come back if it is
 * wanted.
 */
@Composable
private fun ChannelPicker(
    taken: Set<String>,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var typed by remember { mutableStateOf("") }
    val offered = WINE_CHANNEL_CATALOGUE.filterNot { it.channel in taken }

    VDialogCard(onDismiss = onDismiss) {
        Text("Add a channel", style = Vessel.type.subtitle)
        Column(
            Modifier
                .heightIn(max = Vessel.metrics.checklistMaxHeight)
                .verticalScroll(rememberScrollState()),
        ) {
            offered.forEach { info ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = Vessel.metrics.touchTarget)
                        .padding(vertical = Vessel.metrics.s6),
                    verticalArrangement = Arrangement.spacedBy(Vessel.metrics.s3),
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(bottom = Vessel.metrics.s3),
                        horizontalArrangement = Arrangement.spacedBy(Vessel.metrics.s6),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(info.channel, style = Vessel.type.mono)
                        if (info.oneSessionAt != null) {
                            VTag("one session", tone = VTagTone.Neutral)
                        }
                        Row(Modifier.weight(1f)) {}
                        VButton("Add", { onPick(info.channel) }, style = VButtonStyle.Secondary)
                    }
                    Text(
                        info.summary,
                        style = Vessel.type.bodySmall,
                        color = Vessel.colors.textMuted,
                    )
                    if (info.caution != null) {
                        Text(
                            info.caution,
                            style = Vessel.type.bodySmall,
                            color = Vessel.colors.warn,
                        )
                    }
                }
            }
            if (offered.isEmpty()) {
                Text(
                    "Every channel Vessel knows about is already on the list. Name another one " +
                        "below.",
                    style = Vessel.type.bodySmall,
                    color = Vessel.colors.textMuted,
                )
            }
        }
        val name = typed.trim()
        VLabeledField(
            label = "Or a channel Wine knows that is not listed",
            help = "The list above is a convenience, not the set Wine accepts.",
        ) {
            VTextField(typed, { typed = it }, placeholder = "winmm")
        }
        // Said out loud rather than left to a greyed-out button. A name Wine
        // would drop is the failure this whole screen exists to stop: it would
        // add a row that looks armed and logs nothing.
        if (name.isNotEmpty() && !isChannelName(name)) {
            Text(
                "One channel name: no spaces, and none of , + - : — Wine reads those as " +
                    "structure. Fourteen characters at most, or it registers nothing.",
                style = Vessel.type.bodySmall,
                color = Vessel.colors.danger,
            )
        } else if (name.isNotEmpty() && name in taken) {
            Text(
                "$name is already on the list.",
                style = Vessel.type.bodySmall,
                color = Vessel.colors.textMuted,
            )
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Vessel.metrics.s8, Alignment.End),
        ) {
            VButton("Cancel", onDismiss, style = VButtonStyle.Secondary)
            VButton(
                "Add",
                { onPick(name) },
                style = VButtonStyle.Primary,
                enabled = isChannelName(name) && name !in taken,
            )
        }
    }
}

/**
 * *Copy to another container*, as a menu of the containers there are.
 *
 * Diagnosing usually means comparing two containers, and re-arming a dozen
 * controls by hand is where people give up. A menu rather than a picker sheet
 * because the answer is one name from a short list — this product has two or
 * three containers, not a directory of them.
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
                    "The channels, levels and limits on this screen replace that container's. " +
                        "Its own settings are not touched.",
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
    value.toString().reversed().chunked(3).joinToString(" ").reversed()

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
                    diagnostics = ContainerDiagnostics()
                        .withChannelAdded("module")
                        .withChannelAdded("relay"),
                    usageLabel = "14.2 MB",
                    usageFraction = 0.03f,
                    ceilingLabel = "10 sessions · 480 MB at these limits",
                    sessionCount = 4,
                    summary = "2 on",
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
