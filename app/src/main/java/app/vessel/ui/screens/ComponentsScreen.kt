package app.vessel.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.vessel.core.ComponentPackage
import app.vessel.ui.components.VButton
import app.vessel.ui.components.VButtonStyle
import app.vessel.ui.components.VEmptyState
import app.vessel.ui.components.VIconButton
import app.vessel.ui.components.VPushToolbar
import app.vessel.ui.components.VRule
import app.vessel.ui.components.VScaffold
import app.vessel.ui.components.VSectionHeader
import app.vessel.ui.components.VTag
import app.vessel.ui.components.VTagTone
import app.vessel.ui.theme.Vessel
import app.vessel.ui.theme.VesselTheme
import app.vessel.ui.theme.vCard
import app.vessel.ui.theme.vRing
import app.vessel.ui.vm.ComponentSection
import app.vessel.ui.vm.ComponentsUiState
import app.vessel.ui.vm.ComponentsViewModel
import app.vessel.ui.vm.SampleComponentSections

/**
 * Components, reached from Settings rather than the bottom bar.
 *
 * One current build per component, all compiled for this device, so this is a
 * status-and-update view rather than a store to shop in.
 */
@Composable
fun ComponentsScreen(
    onBack: () -> Unit,
    onInstall: (ComponentPackage) -> Unit,
    onRefresh: () -> Unit,
    viewModel: ComponentsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ComponentsContent(state, onBack, onInstall, onRefresh)
}

@Composable
private fun ComponentsContent(
    state: ComponentsUiState,
    onBack: () -> Unit,
    onInstall: (ComponentPackage) -> Unit,
    onRefresh: () -> Unit,
) {
    val installed = state.sections.sumOf { section -> section.items.count { it.installed } }

    VScaffold(
        toolbar = {
            VPushToolbar(
                title = "Components",
                subtitle = "$installed installed",
                onBack = onBack,
                trailing = { VIconButton(Icons.Filled.Refresh, "Check for updates", onRefresh) },
            )
        },
    ) {
        if (state.sections.isEmpty()) {
            VEmptyState(
                icon = Icons.Filled.Build,
                message = "The component registry could not be read. Nothing is installed and " +
                    "nothing can be, until it can.",
                actionLabel = "Retry",
                onAction = onRefresh,
            )
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = Vessel.metrics.s22),
            ) {
                state.sections.forEachIndexed { sectionIndex, section ->
                    // A freestanding rule between groups, so it fades at both
                    // ends. Not before the first group: a rule under the toolbar
                    // would be a bar edge, which is a different thing and stays
                    // solid.
                    if (sectionIndex > 0) {
                        item(key = "rule-${section.title}") { VRule() }
                    }
                    item(key = "header-${section.title}") {
                        VSectionHeader("${section.title} · ${section.items.size}")
                    }
                    section.warning?.let { warning ->
                        item(key = "warning-${section.title}") { MatchedSetWarning(warning) }
                    }
                    items(
                        count = section.items.size,
                        key = { index -> section.items[index].id },
                    ) { index ->
                        ComponentRow(section.items[index], onInstall)
                    }
                }
            }
        }
    }
}

/**
 * One component, with its provenance on the face of it.
 *
 * The build target, source commit and the flags the compiler actually got are
 * three lines of mono rather than a detail screen, because "compiled for your
 * device" is the product's central claim and a claim you cannot check is just an
 * assertion. `cpuFlags` in particular is worth the space: `resolve_cpu_flags`
 * falls back to `-march`/`-mtune` when a toolchain refuses `-mcpu`, and the
 * whole difference between a tuned build and a generic one is visible there.
 */
@Composable
private fun ComponentRow(component: ComponentPackage, onInstall: (ComponentPackage) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(bottom = Vessel.metrics.s8)
            .vCard()
            .padding(Vessel.metrics.s11),
        horizontalArrangement = Arrangement.spacedBy(Vessel.metrics.s11),
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(Vessel.metrics.s6),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    component.name,
                    style = Vessel.type.cardTitle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                VTag(component.target, tone = VTagTone.Accent)
            }
            Text(
                "${component.type.wire} ${component.versionName} · ${mebibytes(component.sizeBytes)}",
                style = Vessel.type.mono,
                color = Vessel.colors.textLabel,
            )
            VProvenance("src", component.sourceSha)
            VProvenance("flags", component.cpuFlags)
        }
        if (component.installed) {
            Text(
                "installed",
                style = Vessel.type.monoSmall,
                color = Vessel.colors.ok,
                modifier = Modifier.padding(top = Vessel.metrics.s3),
            )
        } else {
            VButton(
                label = "Install",
                onClick = { onInstall(component) },
                style = VButtonStyle.Primary,
            )
        }
    }
}

/** One provenance field: a fixed-width mono key and its value. */
@Composable
private fun VProvenance(key: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(Vessel.metrics.s6)) {
        Text(
            key,
            style = Vessel.type.monoSmall,
            color = Vessel.colors.textMuted,
            modifier = Modifier.width(36.dp),
        )
        Text(
            value,
            style = Vessel.type.monoSmall,
            color = Vessel.colors.textMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * A mismatched set, said out loud.
 *
 * Silently allowing a driver and a D3D layer that were never tested together is
 * the failure that gets reported as "the driver is broken", so it is flagged
 * here rather than discovered on a black screen.
 */
@Composable
private fun MatchedSetWarning(text: String) {
    val shape = Vessel.metrics.shapeMd
    Row(
        Modifier
            .fillMaxWidth()
            .padding(bottom = Vessel.metrics.s8)
            .background(Vessel.colors.warn.copy(alpha = 0.10f), shape)
            .vRing(Vessel.colors.warn.copy(alpha = 0.40f), shape)
            .padding(Vessel.metrics.s11),
        horizontalArrangement = Arrangement.spacedBy(Vessel.metrics.s8),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(Icons.Filled.Warning, null, Modifier.size(16.dp), tint = Vessel.colors.warn)
        Text(text, style = Vessel.type.body, color = Vessel.colors.textLabel)
    }
}

/** MiB, matching what `build/gen_registry.py` prints for the same number. */
private fun mebibytes(bytes: Long): String = "${bytes / (1024 * 1024)} MiB"

@Preview(showBackground = true, backgroundColor = 0xFF161826, widthDp = 392, heightDp = 824)
@Composable
private fun ComponentsPreview() {
    VesselTheme {
        ComponentsContent(
            state = ComponentsUiState(SampleComponentSections),
            onBack = {},
            onInstall = {},
            onRefresh = {},
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF161826, widthDp = 392, heightDp = 400)
@Composable
private fun ComponentSectionPreview() {
    VesselTheme {
        Column(Modifier.padding(16.dp)) {
            val section: ComponentSection = SampleComponentSections.last()
            VSectionHeader(section.title)
            section.warning?.let { MatchedSetWarning(it) }
            section.items.forEach { ComponentRow(it, {}) }
        }
    }
}
