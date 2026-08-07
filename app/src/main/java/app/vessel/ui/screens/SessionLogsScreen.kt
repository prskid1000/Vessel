package app.vessel.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.vessel.data.SessionExit
import app.vessel.ui.components.VConfirmSheet
import app.vessel.ui.components.VEmptyState
import app.vessel.ui.components.VIconButton
import app.vessel.ui.components.VPushToolbar
import app.vessel.ui.components.VScaffold
import app.vessel.ui.components.VTag
import app.vessel.ui.components.VTagTone
import app.vessel.ui.theme.Vessel
import app.vessel.ui.theme.VesselTheme
import app.vessel.ui.theme.vRuleBelow
import app.vessel.ui.vm.SessionLogsUiState
import app.vessel.ui.vm.SessionLogsViewModel
import app.vessel.ui.vm.SessionRow

/**
 * Pushed — every session this container has run, newest first.
 *
 * Reached from the container editor's toolbar and from nowhere else. A log is a
 * property of the container that produced it, so the route to it goes through
 * one; there is no global logs destination and no tab for it.
 *
 * One flat row per session, in the language the rest of the product uses for
 * lists of facts: no cards, four columns, and colour only on the status. The
 * row's job is to let someone who has just watched a game close itself find the
 * run it happened in, which is why the relative time leads and the status tag
 * is the only thing on the row that is coloured.
 */
@Composable
fun SessionLogsScreen(
    onBack: () -> Unit,
    onOpenSession: (Long) -> Unit,
    viewModel: SessionLogsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    SessionLogsContent(
        state = state,
        onBack = onBack,
        onOpenSession = onOpenSession,
        onDeleteAll = viewModel::deleteAll,
    )
}

@Composable
private fun SessionLogsContent(
    state: SessionLogsUiState,
    onBack: () -> Unit,
    onOpenSession: (Long) -> Unit,
    onDeleteAll: () -> Unit,
) {
    var confirmingClear by remember { mutableStateOf(false) }

    VScaffold(
        toolbar = {
            VPushToolbar(
                title = "Logs",
                subtitle = state.containerName.ifBlank { null },
                onBack = onBack,
                trailing = {
                    if (state.rows.isNotEmpty()) {
                        VIconButton(
                            Icons.Filled.Delete,
                            "Delete all logs",
                            { confirmingClear = true },
                            tint = Vessel.colors.danger,
                        )
                    }
                },
            )
        },
    ) {
        if (state.loading) return@VScaffold

        if (state.rows.isEmpty()) {
            VEmptyState(
                icon = Icons.AutoMirrored.Filled.List,
                message = "This container has never run, so there is nothing to read. A log is " +
                    "written from the moment you launch it until the program exits.",
            )
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    top = Vessel.metrics.s8,
                    bottom = Vessel.metrics.s22,
                ),
            ) {
                items(state.rows, key = { it.startedAt }) { row ->
                    SessionListRow(row) { onOpenSession(row.startedAt) }
                }
            }
        }
    }

    if (confirmingClear) {
        VConfirmSheet(
            title = "Delete every log for ${state.containerName.ifBlank { "this container" }}?",
            message = "All ${state.rows.size} sessions are removed from this device. The " +
                "container itself and its settings are not touched.",
            confirmLabel = "Delete",
            onConfirm = {
                confirmingClear = false
                onDeleteAll()
            },
            onDismiss = { confirmingClear = false },
        )
    }
}

/**
 * When it ran, how long for, how big, and how it ended.
 *
 * The three machine facts are mono and the status is a tag, so the column of
 * durations and sizes reads straight down the row without the eye having to
 * find each one. `crashed` is the only red thing on the screen, which is the
 * whole reason to open it.
 */
@Composable
private fun SessionListRow(row: SessionRow, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .vRuleBelow(Vessel.colors.divider)
            .padding(vertical = Vessel.metrics.s11),
        verticalArrangement = Arrangement.spacedBy(Vessel.metrics.s6),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Vessel.metrics.s8),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(row.whenLabel, style = Vessel.type.body, modifier = Modifier.weight(1f))
            VTag(statusLabel(row.status), tone = statusTone(row.status))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Vessel.metrics.s11)) {
            Text(row.durationLabel, style = Vessel.type.monoSmall, color = Vessel.colors.textMuted)
            Text(row.sizeLabel, style = Vessel.type.monoSmall, color = Vessel.colors.textMuted)
            // Only said when it is true, and only for a session that thinks it
            // ended well: "crashed" already implies errors, and a badge that
            // appears on every row stops being a signal.
            if (row.hasErrors && row.status == SessionExit.OK) {
                Text("errors logged", style = Vessel.type.monoSmall, color = Vessel.colors.warn)
            }
        }
    }
}

private fun statusLabel(exit: SessionExit) = when (exit) {
    SessionExit.RUNNING -> "running"
    SessionExit.OK -> "ok"
    SessionExit.CRASHED -> "crashed"
}

private fun statusTone(exit: SessionExit) = when (exit) {
    SessionExit.RUNNING -> VTagTone.Accent
    SessionExit.OK -> VTagTone.Ok
    SessionExit.CRASHED -> VTagTone.Danger
}

// — previews ---------------------------------------------------------------
//
// Fixed rows, and only here. The screen itself reads the log directory, so an
// empty device shows the empty state rather than a plausible-looking history of
// runs that never happened.

@Preview(showBackground = true, backgroundColor = 0xFF161826, widthDp = 392, heightDp = 824)
@Composable
private fun SessionLogsPreview() {
    VesselTheme {
        SessionLogsContent(
            state = SessionLogsUiState(
                loading = false,
                containerName = "Default",
                rows = listOf(
                    SessionRow(3, "just now", "running", "412 KB", SessionExit.RUNNING, false),
                    SessionRow(2, "12 minutes ago", "4 m 07 s", "2.1 MB", SessionExit.CRASHED, true),
                    SessionRow(1, "2 hours ago", "41 s", "88 KB", SessionExit.OK, true),
                    SessionRow(0, "3 days ago", "1 h 12 m", "8.0 MB", SessionExit.OK, false),
                ),
            ),
            onBack = {},
            onOpenSession = {},
            onDeleteAll = {},
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF161826, widthDp = 392, heightDp = 824)
@Composable
private fun SessionLogsEmptyPreview() {
    VesselTheme {
        SessionLogsContent(
            state = SessionLogsUiState(loading = false, containerName = "Default"),
            onBack = {},
            onOpenSession = {},
            onDeleteAll = {},
        )
    }
}
