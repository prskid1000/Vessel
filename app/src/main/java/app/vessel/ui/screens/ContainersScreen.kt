package app.vessel.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.vessel.ui.BottomDestinations
import app.vessel.ui.Routes
import app.vessel.ui.components.VBottomNav
import app.vessel.ui.components.VContainerCard
import app.vessel.ui.components.VEmptyState
import app.vessel.ui.components.VIconButton
import app.vessel.ui.components.VRootToolbar
import app.vessel.ui.components.VScaffold
import app.vessel.ui.theme.Vessel
import app.vessel.ui.theme.VesselTheme
import app.vessel.ui.vm.ContainersUiState
import app.vessel.ui.vm.ContainersViewModel
import app.vessel.ui.vm.SampleContainerRows

/** Root 1 — the home screen. */
@Composable
fun ContainersScreen(
    currentRoute: String?,
    onNavigate: (String) -> Unit,
    onOpenContainer: (String) -> Unit,
    onCreateContainer: () -> Unit,
    onLaunch: (String) -> Unit,
    viewModel: ContainersViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ContainersContent(state, currentRoute, onNavigate, onOpenContainer, onCreateContainer, onLaunch)
}

@Composable
private fun ContainersContent(
    state: ContainersUiState,
    currentRoute: String?,
    onNavigate: (String) -> Unit,
    onOpenContainer: (String) -> Unit,
    onCreateContainer: () -> Unit,
    onLaunch: (String) -> Unit,
) {
    VScaffold(
        toolbar = {
            VRootToolbar(
                title = "Containers",
                subtitle = "${state.rows.size} configured",
                trailing = {
                    VIconButton(Icons.Filled.Add, "New container", onCreateContainer)
                },
            )
        },
        bottomBar = {
            VBottomNav(BottomDestinations, currentRoute) { onNavigate(it.route) }
        },
    ) {
        if (state.rows.isEmpty()) {
            VEmptyState(
                icon = Icons.Filled.Add,
                message = "No containers yet. A new one is already configured correctly for this " +
                    "device — you do not have to know what any of it means.",
                actionLabel = "New container",
                onAction = onCreateContainer,
            )
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = Vessel.metrics.s24),
                verticalArrangement = Arrangement.spacedBy(Vessel.metrics.s12),
            ) {
                items(state.rows, key = { it.profile.id }) { row ->
                    VContainerCard(
                        container = row.profile,
                        lastRunLabel = row.lastRunLabel,
                        onOpen = { onOpenContainer(row.profile.id) },
                        onLaunch = { onLaunch(row.profile.id) },
                    )
                }
            }
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0A0C0F, widthDp = 392, heightDp = 824)
@Composable
private fun ContainersPreview() {
    VesselTheme {
        ContainersContent(
            state = ContainersUiState(SampleContainerRows),
            currentRoute = Routes.CONTAINERS,
            onNavigate = {},
            onOpenContainer = {},
            onCreateContainer = {},
            onLaunch = {},
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0A0C0F, widthDp = 392, heightDp = 824)
@Composable
private fun ContainersEmptyPreview() {
    VesselTheme {
        ContainersContent(
            state = ContainersUiState(),
            currentRoute = Routes.CONTAINERS,
            onNavigate = {},
            onOpenContainer = {},
            onCreateContainer = {},
            onLaunch = {},
        )
    }
}
