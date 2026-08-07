package app.vessel.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.vessel.data.DeviceReport
import app.vessel.data.ReportField
import app.vessel.data.ReportTone
import app.vessel.data.ReportSection
import app.vessel.ui.components.VButton
import app.vessel.ui.components.VButtonStyle
import app.vessel.ui.components.VEmptyState
import app.vessel.ui.components.VPushToolbar
import app.vessel.ui.components.VRule
import app.vessel.ui.components.VScaffold
import app.vessel.ui.components.VSectionHeader
import app.vessel.ui.theme.Vessel
import app.vessel.ui.theme.VesselTheme
import app.vessel.ui.theme.vCard
import app.vessel.ui.vm.DiagnosticsUiState
import app.vessel.ui.vm.DiagnosticsViewModel

/**
 * Pushed 10 — the device capability report.
 *
 * Every value on this screen was read from this phone at the moment the screen
 * opened: `Build`, `getprop`, `/proc`, `/sys`, and a one-pixel GL context for the
 * driver strings. Nothing is a constant and nothing is inferred, because the
 * whole use of the screen is being pasted into a bug report — and a report
 * carrying a value the app assumed is worse than one with a hole in it. Where a
 * read failed the row says which source failed, in `textMuted`.
 *
 * TODO: the log pane over Wine/FEX/Box64/Turnip output and the export bundle are
 *  the other half of this screen in DESIGN.md. Both need a session that produces
 *  output first; the capability report does not, so it is here.
 */
@Composable
fun DiagnosticsScreen(
    onBack: () -> Unit,
    viewModel: DiagnosticsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    DiagnosticsContent(state, onBack, viewModel::copyReport)
}

@Composable
private fun DiagnosticsContent(
    state: DiagnosticsUiState,
    onBack: () -> Unit,
    onCopy: () -> Unit,
) {
    VScaffold(
        toolbar = {
            VPushToolbar(
                title = "Diagnostics",
                subtitle = if (state.loading) "reading the device" else "read just now",
                onBack = onBack,
                trailing = {
                    if (state.report != null) {
                        VButton("Copy report", onCopy, style = VButtonStyle.Secondary)
                    }
                },
            )
        },
    ) {
        val report = state.report
        when {
            report == null && state.loading -> Unit
            report == null -> VEmptyState(
                icon = Icons.Filled.Info,
                message = "The device report could not be read at all, which should not be " +
                    "possible — every source it uses is on this phone.",
            )

            else -> LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = Vessel.metrics.s22),
            ) {
                report.sections.forEachIndexed { index, section ->
                    if (index > 0) item(key = "rule-${section.title}") { VRule() }
                    item(key = "header-${section.title}") { VSectionHeader(section.title) }
                    items(
                        count = section.fields.size,
                        key = { i -> "${section.title}-${section.fields[i].label}" },
                    ) { i ->
                        FieldRow(section.fields[i])
                    }
                }
            }
        }
    }
}

/**
 * One fact.
 *
 * Mono on both sides, per the design rule that machine facts are monospaced —
 * the label is a `/proc` path or a `Build` field as often as it is a word, so
 * setting it in sans would be claiming it is prose. The value wraps rather than
 * ellipsising: a truncated hwcap list or kernel string is useless in a bug
 * report, and this screen exists for bug reports.
 */
@Composable
private fun FieldRow(field: ReportField) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(bottom = Vessel.metrics.s6)
            .vCard()
            .padding(Vessel.metrics.s11),
        verticalArrangement = Arrangement.spacedBy(Vessel.metrics.s3),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Vessel.metrics.s8),
            verticalAlignment = Alignment.Top,
        ) {
            Text(
                field.label,
                style = Vessel.type.monoSmall,
                color = Vessel.colors.textMuted,
                modifier = Modifier.width(96.dp),
            )
            Text(
                field.value,
                style = Vessel.type.mono,
                color = toneColor(field.tone),
                modifier = Modifier.weight(1f),
            )
        }
        if (field.note != null) {
            Text(
                field.note,
                style = Vessel.type.bodySmall,
                color = Vessel.colors.textMuted,
                modifier = Modifier.padding(start = 96.dp + Vessel.metrics.s8),
            )
        }
    }
}

/**
 * Colour carries meaning here as everywhere: a capability present is `ok`, one
 * absent is `warn` — not because absence is a fault, but because on this chip
 * every absence is a setting somewhere else in the product — and a value that
 * could not be read is muted so it cannot be mistaken for a reading.
 */
@Composable
private fun toneColor(tone: ReportTone): Color = when (tone) {
    ReportTone.Plain -> Vessel.colors.textLabel
    ReportTone.Present -> Vessel.colors.ok
    ReportTone.Absent -> Vessel.colors.warn
    ReportTone.Unknown -> Vessel.colors.textMuted
}

@Preview(showBackground = true, backgroundColor = 0xFF161826, widthDp = 392, heightDp = 824)
@Composable
private fun DiagnosticsPreview() {
    VesselTheme {
        DiagnosticsContent(
            // Preview data. The screen itself reads the device it is running on.
            state = DiagnosticsUiState(
                loading = false,
                report = DeviceReport(
                    listOf(
                        ReportSection(
                            "Device",
                            listOf(
                                ReportField("model", "motorola signature"),
                                ReportField("soc", "QTI SM8845"),
                            ),
                        ),
                        ReportSection(
                            "CPU features",
                            listOf(
                                ReportField("sve2", "present", ReportTone.Present),
                                ReportField(
                                    "tso",
                                    "absent",
                                    ReportTone.Absent,
                                    "Every memory-ordering setting exists for this.",
                                ),
                            ),
                        ),
                    ),
                ),
            ),
            onBack = {},
            onCopy = {},
        )
    }
}
