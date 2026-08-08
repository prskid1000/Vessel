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

import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import app.vessel.core.LogEntry
import app.vessel.core.LogLevel
import app.vessel.core.LogSource
import app.vessel.data.SessionExit
import app.vessel.data.SessionMetricsState
import app.vessel.ui.components.VEmptyState
import app.vessel.ui.components.VIconButton
import app.vessel.ui.components.VIcons
import app.vessel.ui.components.VPushToolbar
import app.vessel.ui.components.VScaffold
import app.vessel.ui.theme.Vessel
import app.vessel.ui.theme.VesselTheme
import app.vessel.ui.theme.vRing
import app.vessel.ui.vm.SessionLogTab
import app.vessel.ui.vm.SessionLogUiState
import app.vessel.ui.vm.SessionLogViewModel
import kotlinx.coroutines.launch

/**
 * Pushed — one session, in two tabs: everything it printed, and what it cost.
 *
 * **Log** is monospace at `monoSmall`, coloured by level, and drawn by a
 * `LazyColumn` keyed on each line's position in the file. A hundred thousand
 * lines is an ordinary size for a session that went wrong, so nothing here ever
 * holds the whole file as text: the view model pages it in from a byte cursor
 * and Copy and Share go to the store, which streams.
 *
 * **Every line, always.** There was a two-way severity filter here — All against
 * errors-and-warnings — and it is gone. It asked the reader to decide which
 * layer had failed before showing them anything, and the `fixme` that explains a
 * crash is routinely two hundred lines above it and not a warning at all.
 *
 * **Metrics** is the run's telemetry — live while it is running, and replayed
 * from the trace sidecar beside the log once it is not. The samples are
 * deliberately *not* in the log: a telemetry line every second is noise in the
 * one place someone is trying to read a stack of `err:` lines.
 *
 * Two affordances above both. **Copy all**, because the thing anyone does with a
 * log is paste it somewhere. **Share**, because the thing anyone does with a log
 * too big to paste is send the file.
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

    // Collected only while the Metrics tab is in front and the screen is
    // started. That is not tidiness: the subscription is what tells the recorder
    // to sample at 1 Hz instead of once every ten seconds, and this screen sits
    // over a session that is competing with us for the CPU we are measuring.
    val lifecycleOwner = LocalLifecycleOwner.current
    var metrics by remember { mutableStateOf<SessionMetricsState?>(null) }
    LaunchedEffect(state.tab, lifecycleOwner) {
        if (state.tab != SessionLogTab.METRICS) {
            metrics = null
            return@LaunchedEffect
        }
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.metrics.collect { metrics = it }
        }
    }

    SessionLogContent(
        state = state,
        metrics = metrics,
        onBack = onBack,
        onTab = viewModel::setTab,
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
    metrics: SessionMetricsState?,
    onBack: () -> Unit,
    onTab: (SessionLogTab) -> Unit,
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
                        VIconButton(VIcons.Copy, "Copy all", onCopy)
                        VIconButton(VIcons.Share, "Share log", onShare)
                    }
                },
            )
        },
    ) {
        if (state.missing) {
            VEmptyState(
                icon = VIcons.Info,
                message = "This session's log is no longer on the device. Ten are kept per " +
                    "container, and the oldest is removed when an eleventh starts.",
            )
            return@VScaffold
        }

        LogTabRow(state.tab, onTab)

        if (state.tab == SessionLogTab.METRICS) {
            SessionMetricsPanel(metrics)
            return@VScaffold
        }

        if (state.loading) return@VScaffold

        if (state.entries.isEmpty()) {
            VEmptyState(
                icon = VIcons.Info,
                message = "This session produced no output at all.",
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
        // A hairline of vertical padding, and it is not decoration: `monoSmall`
        // wraps a long Wine line to two or three, and without a gap between rows
        // a wrapped line and the next line are indistinguishable.
        Modifier.fillMaxWidth().padding(vertical = Vessel.metrics.s3),
        horizontalArrangement = Arrangement.spacedBy(Vessel.metrics.s6),
    ) {
        Text(
            entry.source.label,
            style = Vessel.type.monoSmall,
            color = Vessel.colors.neutral700,
            modifier = Modifier.width(Vessel.metrics.logGutterWidth),
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
 * Two tabs, in the segmented form this product already uses for a two-way
 * choice.
 *
 * Deliberately not Material's `TabRow`: its indicator is a 3 dp underline in the
 * primary colour, which in Nocturne is a violet slab appearing under a heading,
 * and it carries Material's own ripple and 48 dp minimum. This is the same
 * geometry the severity filter it replaces had — a `divider` ring around two
 * equal cells, the active one on the 12% accent ground that [VTag]'s Outline
 * form and every selected control in the product use.
 */
@Composable
private fun LogTabRow(selected: SessionLogTab, onSelect: (SessionLogTab) -> Unit) {
    val shape = Vessel.metrics.shapeMd
    Row(
        Modifier
            .fillMaxWidth()
            .padding(bottom = Vessel.metrics.s8)
            .vRing(Vessel.colors.divider, shape)
            .padding(Vessel.metrics.s3),
        horizontalArrangement = Arrangement.spacedBy(Vessel.metrics.s3),
    ) {
        SessionLogTab.entries.forEach { option ->
            val active = option == selected
            Box(
                Modifier
                    .weight(1f)
                    .background(
                        if (active) Vessel.colors.accentSoft else Color.Transparent,
                        Vessel.metrics.shapeSm,
                    )
                    .clickable { onSelect(option) }
                    .padding(vertical = Vessel.metrics.s8),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    tabLabel(option),
                    style = Vessel.type.control,
                    color = if (active) Vessel.colors.accent else Vessel.colors.textMuted,
                )
            }
        }
    }
}

private fun tabLabel(tab: SessionLogTab) = when (tab) {
    SessionLogTab.LOG -> "Log"
    SessionLogTab.METRICS -> "Metrics"
}




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
            metrics = null,
            onBack = {},
            onTab = {},
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
