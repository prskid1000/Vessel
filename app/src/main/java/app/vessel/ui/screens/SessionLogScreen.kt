package app.vessel.ui.screens

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.vessel.core.LogEntry
import app.vessel.core.LogFilter
import app.vessel.core.LogLevel
import app.vessel.core.LogSource
import app.vessel.data.SessionExit
import app.vessel.ui.components.VEmptyState
import app.vessel.ui.components.VIconButton
import app.vessel.ui.components.VIcons
import app.vessel.ui.components.VPushToolbar
import app.vessel.ui.components.VScaffold
import app.vessel.ui.theme.Vessel
import app.vessel.ui.theme.VesselTheme
import app.vessel.ui.theme.vRing
import app.vessel.ui.vm.SessionLogUiState
import app.vessel.ui.vm.SessionLogViewModel
import kotlinx.coroutines.launch

/**
 * Pushed — one session's output.
 *
 * Monospace at `monoSmall`, coloured by level, and drawn by a `LazyColumn` keyed
 * on each line's position in the file. A hundred thousand lines is an ordinary
 * size for a session that went wrong, so nothing here ever holds the whole file
 * as text: the view model pages it in from a byte cursor and Copy and Share go
 * to the store, which streams.
 *
 * Two affordances above everything else. **Copy all**, because the thing anyone
 * does with a log is paste it somewhere. **Share**, because the thing anyone
 * does with a log too big to paste is send the file. The severity filter is a
 * view over lines already captured and not a capture setting — this product does
 * not ask the user to predict, before the crash, which layer will turn out to be
 * at fault.
 */
@Composable
fun SessionLogScreen(
    onBack: () -> Unit,
    viewModel: SessionLogViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

    SessionLogContent(
        state = state,
        onBack = onBack,
        onFilter = viewModel::setFilter,
        onLoadMore = viewModel::loadMore,
        onCopy = {
            scope.launch { clipboard.setText(AnnotatedString(viewModel.clipboardText())) }
        },
        onShare = {
            scope.launch {
                val file = viewModel.exportFile() ?: return@launch
                // A content URI rather than EXTRA_TEXT: a Binder transaction is
                // a megabyte, and a log is routinely larger than that. The
                // provider is declared in the manifest against `cacheDir`.
                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.logs",
                    file,
                )
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, "Vessel session log")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(send, "Share log"))
            }
        },
    )
}

@Composable
private fun SessionLogContent(
    state: SessionLogUiState,
    onBack: () -> Unit,
    onFilter: (LogFilter) -> Unit,
    onLoadMore: () -> Unit,
    onCopy: () -> Unit,
    onShare: () -> Unit,
) {
    val listState = rememberLazyListState()

    // Follow-tail, and the one rule that makes it bearable: the moment the user
    // scrolls up, following stops. Scrolling back to the bottom turns it on
    // again, which is the same contract every terminal has.
    var following by remember { mutableStateOf(true) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress to listState.canScrollForward }
            .collect { (scrolling, more) -> if (scrolling) following = !more }
    }
    LaunchedEffect(state.entries.size, state.live, following) {
        if (state.live && following && state.entries.isNotEmpty()) {
            listState.scrollToItem(state.entries.lastIndex)
        }
    }

    // Paging: ask for the next page while there is still a screenful in hand,
    // so a flick never lands on an empty list waiting for a disk read.
    //
    // Restarted on every size change rather than held in a `derivedStateOf`,
    // because `state` here is a plain value and an unkeyed `remember` would
    // close over the list as it was when the screen first composed.
    LaunchedEffect(listState, state.entries.size, state.atEnd) {
        if (state.atEnd) return@LaunchedEffect
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .collect { last -> if (last >= state.entries.size - LOAD_AHEAD_LINES) onLoadMore() }
    }

    VScaffold(
        toolbar = {
            VPushToolbar(
                title = "Session log",
                subtitle = state.whenLabel.ifBlank { null },
                onBack = onBack,
                trailing = {
                    if (!state.missing) {
                        VIconButton(VIcons.CopyAll, "Copy all", onCopy)
                        VIconButton(Icons.Filled.Share, "Share log", onShare)
                    }
                },
            )
        },
    ) {
        if (state.missing) {
            VEmptyState(
                icon = Icons.Filled.Info,
                message = "This session's log is no longer on the device. Ten are kept per " +
                    "container, and the oldest is removed when an eleventh starts.",
            )
            return@VScaffold
        }

        SeverityFilter(state.filter, onFilter)

        if (state.loading) return@VScaffold

        if (state.entries.isEmpty()) {
            VEmptyState(
                icon = Icons.Filled.Info,
                message = when (state.filter) {
                    LogFilter.PROBLEMS ->
                        "No errors or warnings in this session. Switch to All to read it."

                    LogFilter.ALL -> "This session produced no output at all."
                },
            )
            return@VScaffold
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = Vessel.metrics.s8,
                bottom = Vessel.metrics.s22,
            ),
        ) {
            items(state.entries, key = { it.index }) { entry -> LogRow(entry) }
            if (state.truncated) {
                item(key = "truncated") {
                    Text(
                        "This is as far as the viewer reads. Share exports the whole log.",
                        style = Vessel.type.bodySmall,
                        color = Vessel.colors.warn,
                        modifier = Modifier.padding(vertical = Vessel.metrics.s11),
                    )
                }
            }
        }
    }
}

/**
 * One line: a source gutter, then the text in the level's colour.
 *
 * The gutter is fixed-width so the text starts on one column all the way down,
 * which is most of what makes a wall of log output scannable. It is also the
 * only thing on the row that is not the line itself, because a timestamp column
 * on a phone would cost a third of the width to say something the session's own
 * ordering already says.
 */
@Composable
private fun LogRow(entry: LogEntry) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Vessel.metrics.s8),
    ) {
        Text(
            entry.source.label,
            style = Vessel.type.monoSmall,
            color = Vessel.colors.neutral700,
            modifier = Modifier.width(GUTTER_WIDTH),
        )
        // Wraps rather than clips. A truncated `err:module:import_dll` is a line
        // that has kept the part everybody already knows and dropped the name of
        // the DLL that is missing.
        Text(entry.text, style = Vessel.type.monoSmall, color = levelColor(entry.level))
    }
}

/**
 * The status tokens, straight through.
 *
 * `textTertiary` is an alias of `textMuted` in this theme — both are `text` at
 * 55% — so TRACE takes `neutral-600` instead: the next step down that is
 * actually a different colour. Two levels rendered identically would be two
 * levels the eye cannot separate, which is the whole job of colouring them.
 */
@Composable
private fun levelColor(level: LogLevel): Color = when (level) {
    LogLevel.ERROR -> Vessel.colors.danger
    LogLevel.WARN -> Vessel.colors.warn
    LogLevel.INFO -> Vessel.colors.textMuted
    LogLevel.TRACE -> Vessel.colors.neutral600
}

/**
 * Two segments, and two is all there should be.
 *
 * "Errors and warnings" is the only filter worth having on a phone: it is the
 * question "what went wrong", and every finer cut — by channel, by source — is a
 * question you can only ask once you already know the answer.
 */
@Composable
private fun SeverityFilter(selected: LogFilter, onSelect: (LogFilter) -> Unit) {
    val shape = Vessel.metrics.shapeMd
    Row(
        Modifier
            .fillMaxWidth()
            .padding(bottom = Vessel.metrics.s8)
            .vRing(Vessel.colors.divider, shape)
            .padding(Vessel.metrics.s3),
        horizontalArrangement = Arrangement.spacedBy(Vessel.metrics.s3),
    ) {
        LogFilter.entries.forEach { option ->
            val active = option == selected
            Box(
                Modifier
                    .weight(1f)
                    .background(
                        if (active) Vessel.colors.accentSoft else Color.Transparent,
                        Vessel.metrics.shapeSm,
                    )
                    .clickable { onSelect(option) }
                    .padding(vertical = Vessel.metrics.s6),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    filterLabel(option),
                    style = Vessel.type.control,
                    color = if (active) Vessel.colors.accent else Vessel.colors.textMuted,
                )
            }
        }
    }
}

private fun filterLabel(filter: LogFilter) = when (filter) {
    LogFilter.ALL -> "All"
    LogFilter.PROBLEMS -> "Errors + warnings"
}

/** Wide enough for `driver`, the longest source name. */
private val GUTTER_WIDTH = 44.dp

/** How close to the loaded end the list gets before the next page is asked for. */
private const val LOAD_AHEAD_LINES = 200

// — previews ---------------------------------------------------------------
//
// The only fabricated log in the product, and it exists so the level colours and
// the mono column can be judged without a device. The screen reads a real file.

@Preview(showBackground = true, backgroundColor = 0xFF161826, widthDp = 392, heightDp = 824)
@Composable
private fun SessionLogPreview() {
    VesselTheme {
        SessionLogContent(
            state = SessionLogUiState(
                loading = false,
                entries = SampleLog,
                whenLabel = "12 minutes ago",
                status = SessionExit.CRASHED,
                atEnd = true,
            ),
            onBack = {},
            onFilter = {},
            onLoadMore = {},
            onCopy = {},
            onShare = {},
        )
    }
}

private val SampleLog = listOf(
    LogEntry(0, LogSource.VESSEL, LogLevel.INFO, "wine    wine-11.0-arm64ec"),
    LogEntry(1, LogSource.VESSEL, LogLevel.INFO, "driver  turnip-25.2.0-gen8-canoe"),
    LogEntry(2, LogSource.VESSEL, LogLevel.INFO, "d3d     dxvk-2.7.1"),
    LogEntry(3, LogSource.WINE, LogLevel.TRACE, "loaddll:load_native_dll loaded L\"kernel32.dll\""),
    LogEntry(4, LogSource.DRIVER, LogLevel.INFO, "MESA-INTEL: warning: Performance support disabled"),
    LogEntry(5, LogSource.DXVK, LogLevel.INFO, "DXVK: v2.7.1"),
    LogEntry(6, LogSource.DXVK, LogLevel.INFO, "DXVK: Device : Turnip Adreno (TM) 829"),
    LogEntry(7, LogSource.FEX, LogLevel.INFO, "Core: TSO enabled, vector TSO disabled"),
    LogEntry(8, LogSource.WINE, LogLevel.WARN, "d3d:wined3d_check_device_format stub  ×1204"),
    LogEntry(9, LogSource.VKD3D, LogLevel.WARN, "vkd3d_instance_init: extension not available"),
    LogEntry(10, LogSource.WINE, LogLevel.ERROR, "module:import_dll Library d3dx9_43.dll not found"),
    LogEntry(11, LogSource.VESSEL, LogLevel.WARN, "… logging rate-limited, 8412 lines dropped …"),
    LogEntry(12, LogSource.WINE, LogLevel.ERROR, "seh:NtRaiseException Unhandled exception c0000005"),
)
