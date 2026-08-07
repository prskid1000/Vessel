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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.vessel.ui.BottomDestinations
import app.vessel.ui.Routes
import app.vessel.ui.components.VBottomNav
import app.vessel.ui.components.VConfirmSheet
import app.vessel.ui.components.VContainerCard
import app.vessel.ui.components.VEmptyState
import app.vessel.ui.components.VIconButton
import app.vessel.ui.components.VRootToolbar
import app.vessel.ui.components.VScaffold
import app.vessel.ui.theme.Vessel
import app.vessel.ui.theme.VesselTheme
import app.vessel.ui.vm.ContainerRow
import app.vessel.ui.vm.ContainersUiState
import app.vessel.ui.vm.ContainersViewModel
import app.vessel.ui.vm.SampleContainerRows

/**
 * Root 1 — the home screen, and the only place the technical screens are reached
 * from.
 *
 * The two callbacks after `onLaunch` are the ones Settings used to own. They
 * live behind the overflow in this toolbar now: Components and GPU drivers are
 * things a maintainer opens on purpose and a user never has to find, which is
 * exactly what an overflow is for and exactly what a bottom-nav tab is not.
 */
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
    ContainersContent(
        state = state,
        currentRoute = currentRoute,
        onNavigate = onNavigate,
        onOpenContainer = onOpenContainer,
        onCreateContainer = onCreateContainer,
        onLaunch = onLaunch,
        onDelete = viewModel::delete,
    )
}

@Composable
private fun ContainersContent(
    state: ContainersUiState,
    currentRoute: String?,
    onNavigate: (String) -> Unit,
    onOpenContainer: (String) -> Unit,
    onCreateContainer: () -> Unit,
    onLaunch: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    // The row a long press is asking about, held here rather than in the view
    // model: it is a question the screen is asking, and it should not survive
    // the screen going away.
    var pendingDelete by remember { mutableStateOf<ContainerRow?>(null) }

    VScaffold(
        toolbar = {
            VRootToolbar(
                title = "Containers",
                subtitle = "${state.rows.size} configured",
                trailing = {
                    // No overflow. Both entries it used to hold — Components and
                    // GPU drivers — listed things this build compiles in and the
                    // user cannot change, so the menu was a route to two screens
                    // that could only ever report what was already decided.
                    VIconButton(Icons.Filled.Add, "New container", onCreateContainer)
                },
            )
        },
        bottomBar = {
            VBottomNav(BottomDestinations, currentRoute) { onNavigate(it.route) }
        },
    ) {
        if (state.rows.isEmpty()) {
            // Only once the store has answered. Showing this while the first read
            // is in flight would say "no containers" to someone who has several.
            if (state.loaded) {
                VEmptyState(
                    icon = Icons.Filled.Add,
                    message = "No containers yet. A new one is already configured correctly for " +
                        "this device — you do not have to know what any of it means.",
                    actionLabel = "New container",
                    onAction = onCreateContainer,
                )
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = Vessel.metrics.s22),
                verticalArrangement = Arrangement.spacedBy(Vessel.metrics.s11),
            ) {
                items(state.rows, key = { it.profile.id }) { row ->
                    VContainerCard(
                        container = row.profile,
                        onOpen = { onOpenContainer(row.profile.id) },
                        onLaunch = { onLaunch(row.profile.id) },
                        onLongPress = { pendingDelete = row },
                    )
                }
            }
        }
    }

    pendingDelete?.let { row ->
        VConfirmSheet(
            title = "Delete ${row.profile.name}?",
            message = "Its settings are removed from this device. Installed components are " +
                "shared and are not touched.",
            confirmLabel = "Delete",
            onConfirm = {
                onDelete(row.profile.id)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null },
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF161826, widthDp = 392, heightDp = 824)
@Composable
private fun ContainersPreview() {
    VesselTheme {
        ContainersContent(
            state = ContainersUiState(SampleContainerRows, loaded = true),
            currentRoute = Routes.CONTAINERS,
            onNavigate = {},
            onOpenContainer = {},
            onCreateContainer = {},
            onLaunch = {},
            onDelete = {},
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF161826, widthDp = 392, heightDp = 824)
@Composable
private fun ContainersEmptyPreview() {
    VesselTheme {
        ContainersContent(
            state = ContainersUiState(loaded = true),
            currentRoute = Routes.CONTAINERS,
            onNavigate = {},
            onOpenContainer = {},
            onCreateContainer = {},
            onLaunch = {},
            onDelete = {},
        )
    }
}
