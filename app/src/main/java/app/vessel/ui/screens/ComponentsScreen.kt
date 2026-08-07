package app.vessel.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import app.vessel.ui.BottomDestinations
import app.vessel.ui.Routes
import app.vessel.ui.components.VBottomNav
import app.vessel.ui.components.VButton
import app.vessel.ui.components.VButtonStyle
import app.vessel.ui.components.VEmptyState
import app.vessel.ui.components.VIconButton
import app.vessel.ui.components.VRootToolbar
import app.vessel.ui.components.VScaffold
import app.vessel.ui.components.VSectionHeader
import app.vessel.ui.theme.Vessel
import app.vessel.ui.theme.VesselTheme
import app.vessel.ui.theme.vCard
import app.vessel.ui.vm.ComponentSection
import app.vessel.ui.vm.ComponentsUiState
import app.vessel.ui.vm.ComponentsViewModel
import app.vessel.ui.vm.SampleComponentSections

/** Root 3 — the `.wcp` store. */
@Composable
fun ComponentsScreen(
    currentRoute: String?,
    onNavigate: (String) -> Unit,
    onInstall: (ComponentPackage) -> Unit,
    onRefresh: () -> Unit,
    viewModel: ComponentsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ComponentsContent(state, currentRoute, onNavigate, onInstall, onRefresh)
}

@Composable
private fun ComponentsContent(
    state: ComponentsUiState,
    currentRoute: String?,
    onNavigate: (String) -> Unit,
    onInstall: (ComponentPackage) -> Unit,
    onRefresh: () -> Unit,
) {
    val installed = state.sections.sumOf { section -> section.items.count { it.installed } }

    VScaffold(
        toolbar = {
            VRootToolbar(
                title = "Components",
                subtitle = "$installed installed",
                trailing = { VIconButton(Icons.Filled.Refresh, "Refresh registry", onRefresh) },
            )
        },
        bottomBar = {
            VBottomNav(BottomDestinations, currentRoute) { onNavigate(it.route) }
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
                contentPadding = PaddingValues(bottom = Vessel.metrics.s24),
            ) {
                state.sections.forEach { section ->
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

@Composable
private fun ComponentRow(component: ComponentPackage, onInstall: (ComponentPackage) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(bottom = Vessel.metrics.s8)
            .vCard()
            .padding(Vessel.metrics.s12),
        horizontalArrangement = Arrangement.spacedBy(Vessel.metrics.s12),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                component.name,
                style = Vessel.type.body,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${component.type.wire} ${component.versionName} · ${mebibytes(component.sizeBytes)}",
                style = Vessel.type.mono,
                color = Vessel.colors.textTertiary,
            )
        }
        if (component.installed) {
            Text("installed", style = Vessel.type.monoSmall, color = Vessel.colors.ok)
        } else {
            VButton(
                label = "Install",
                onClick = { onInstall(component) },
                style = VButtonStyle.Secondary,
            )
        }
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
            .border(Vessel.metrics.hairline, Vessel.colors.warn.copy(alpha = 0.40f), shape)
            .padding(Vessel.metrics.s12),
        horizontalArrangement = Arrangement.spacedBy(Vessel.metrics.s8),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(Icons.Filled.Warning, null, Modifier.size(16.dp), tint = Vessel.colors.warn)
        Text(text, style = Vessel.type.body, color = Vessel.colors.textSecondary)
    }
}

/** MiB, matching what `build/gen_registry.py` prints for the same number. */
private fun mebibytes(bytes: Long): String = "${bytes / (1024 * 1024)} MiB"

@Preview(showBackground = true, backgroundColor = 0xFF0A0C0F, widthDp = 392, heightDp = 824)
@Composable
private fun ComponentsPreview() {
    VesselTheme {
        ComponentsContent(
            state = ComponentsUiState(SampleComponentSections),
            currentRoute = Routes.COMPONENTS,
            onNavigate = {},
            onInstall = {},
            onRefresh = {},
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0A0C0F, widthDp = 392, heightDp = 400)
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
