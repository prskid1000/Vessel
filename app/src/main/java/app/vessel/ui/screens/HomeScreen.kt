package app.vessel.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.vessel.ui.components.VContainerCard
import app.vessel.ui.components.VEmptyState
import app.vessel.ui.components.VIconButton
import app.vessel.ui.components.VIcons
import app.vessel.ui.components.VRootToolbar
import app.vessel.ui.components.VScaffold
import app.vessel.ui.shell.AppShortcut
import app.vessel.ui.theme.Vessel
import app.vessel.ui.theme.VesselTheme
import app.vessel.ui.vm.HomeUiState
import app.vessel.ui.vm.HomeViewModel
import app.vessel.ui.vm.SampleHomeContainers

/**
 * Which sheet, if any, is over home.
 *
 * Sheet state lives in the screen rather than in the back stack, and that is the
 * navigation decision this pass makes. A sheet is not a place: it has no title
 * bar, no back arrow of its own, and its whole reason for existing is that the
 * thing it is about stays visible behind it. Giving each one a route would put
 * three destinations back into a graph that now has three in total.
 */
private sealed interface HomeSheet {
    /** Settings for a container, or a new one when [id] is null. */
    data class Container(val id: String?) : HomeSheet

    /** Add a program to a container. [executable] is a file already picked in the browser. */
    data class AddApp(val containerId: String, val executable: String? = null) : HomeSheet

    /** A program's profile. */
    data class AppProfile(val shortcut: AppShortcut) : HomeSheet
}

/**
 * Home — containers, the programs inside them, and the only root there is.
 *
 * **There is one root and no bottom navigation.** Apps was the second, and it
 * stopped existing when a program became something listed inside the container
 * that owns it. The bottom edge is left clear, because over a running session
 * that edge is the taskbar's reveal gesture and a nav bar there would fight it.
 *
 * Everything short is a sheet over this screen; only Files and the log viewer are
 * pushes. Back leaves the app, because home is the root and nothing is below it.
 *
 * @param pickedExecutable a guest path handed back by the file browser, or null.
 * @param onPickConsumed called once [pickedExecutable] has been applied, so the
 *   same pick is not re-applied on every recomposition.
 */
@Composable
fun HomeScreen(
    onOpenFiles: (String) -> Unit,
    onPickFile: (String) -> Unit,
    onOpenLogs: (String) -> Unit,
    onLaunch: (String) -> Unit,
    onLaunchApp: (AppShortcut) -> Unit,
    onOpenLicences: () -> Unit,
    pickedExecutable: String? = null,
    onPickConsumed: () -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Which container a browse was started for. Held across the push to Files —
    // home stays on the back stack, so this survives the round trip.
    var pickingFor by remember { mutableStateOf<String?>(null) }
    var sheet by remember { mutableStateOf<HomeSheet?>(null) }

    // A file came back from the browser: reopen the sheet that asked for it.
    // In an effect rather than inline, because writing state during composition
    // is how a screen ends up recomposing forever.
    LaunchedEffect(pickedExecutable) {
        val pending = pickingFor
        if (pickedExecutable != null && pending != null) {
            sheet = HomeSheet.AddApp(pending, pickedExecutable)
            pickingFor = null
            onPickConsumed()
        }
    }

    HomeContent(
        state = state,
        onNewContainer = { sheet = HomeSheet.Container(null) },
        onOpenSettings = { sheet = HomeSheet.Container(it) },
        onLaunch = onLaunch,
        onLaunchApp = onLaunchApp,
        onOpenAppProfile = { sheet = HomeSheet.AppProfile(it) },
        onAddApp = { sheet = HomeSheet.AddApp(it) },
        onBrowseFiles = onOpenFiles,
        onOpenLicences = onOpenLicences,
    )

    when (val open = sheet) {
        null -> Unit

        is HomeSheet.Container -> ContainerSheet(
            containerId = open.id,
            onDismiss = { sheet = null },
            onOpenLogs = { containerId ->
                sheet = null
                onOpenLogs(containerId)
            },
        )

        is HomeSheet.AddApp -> AppSheet(
            containerId = open.containerId,
            shortcut = null,
            prefilledExecutable = open.executable,
            onDismiss = { sheet = null },
            onLaunch = onLaunchApp,
            onBrowse = { containerId ->
                pickingFor = containerId
                sheet = null
                onPickFile(containerId)
            },
        )

        is HomeSheet.AppProfile -> AppSheet(
            containerId = open.shortcut.containerId,
            shortcut = open.shortcut,
            onDismiss = { sheet = null },
            onLaunch = { shortcut ->
                sheet = null
                onLaunchApp(shortcut)
            },
            onBrowse = { },
        )
    }
}

@Composable
private fun HomeContent(
    state: HomeUiState,
    onNewContainer: () -> Unit,
    onOpenSettings: (String) -> Unit,
    onLaunch: (String) -> Unit,
    onLaunchApp: (AppShortcut) -> Unit,
    onOpenAppProfile: (AppShortcut) -> Unit,
    onAddApp: (String) -> Unit,
    onBrowseFiles: (String) -> Unit,
    onOpenLicences: () -> Unit,
) {
    VScaffold(
        toolbar = {
            VRootToolbar(
                title = "Vessel",
                subtitle = state.subtitle,
                trailing = {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(Vessel.metrics.s3),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // **The licence notice, as an icon rather than a line of
                        // prose.** LGPL 2.1 section 6 wants prominent notice with
                        // each copy that the Library is used in the work, and a
                        // full-width sentence at the foot of home was the literal
                        // reading of "prominent". It is not the only one: an About
                        // affordance on the root screen, one tap from every launch,
                        // is what essentially every shipped application does and is
                        // discoverable without hunting. The words themselves did not
                        // move — `LicencesScreen` still names the X server, its
                        // authors and the licence, in full.
                        VIconButton(VIcons.Info, "Licences", onOpenLicences)
                        VIconButton(VIcons.Plus, "New container", onNewContainer)
                    }
                },
            )
        },
    ) {
        if (state.containers.isEmpty()) {
            // Only once the store has answered. Showing this while the first read
            // is in flight would say "no containers" to somebody who has several.
            if (state.loaded) {
                VEmptyState(
                    icon = VIcons.Plus,
                    message = "No containers yet. A new one is already configured correctly for " +
                        "this device — you do not have to know what any of it means.",
                    actionLabel = "New container",
                    onAction = onNewContainer,
                )
            }
            return@VScaffold
        }

        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = Vessel.metrics.s3,
                // The bottom edge belongs to the taskbar's gesture on a session
                // and to the system's own on every other screen. Nothing of ours
                // sits in it, so the list simply ends short of it.
                bottom = Vessel.metrics.s22,
            ),
            verticalArrangement = Arrangement.spacedBy(Vessel.metrics.s6),
        ) {
            items(state.containers, key = { it.profile.id }) { row ->
                VContainerCard(
                    container = row.profile,
                    shortcuts = row.shortcuts,
                    meta = row.meta,
                    onOpenSettings = { onOpenSettings(row.profile.id) },
                    onLaunch = { onLaunch(row.profile.id) },
                    onLaunchApp = onLaunchApp,
                    onOpenAppProfile = onOpenAppProfile,
                    onAddApp = { onAddApp(row.profile.id) },
                    onBrowseFiles = if (row.hasPrefix) {
                        { onBrowseFiles(row.profile.id) }
                    } else {
                        null
                    },
                )
            }
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF161826, widthDp = 421, heightDp = 927)
@Composable
private fun HomePreview() {
    VesselTheme {
        HomeContent(
            state = HomeUiState(SampleHomeContainers, loaded = true),
            onNewContainer = {},
            onOpenSettings = {},
            onLaunch = {},
            onLaunchApp = {},
            onOpenAppProfile = {},
            onAddApp = {},
            onBrowseFiles = {},
            onOpenLicences = {},
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF161826, widthDp = 421, heightDp = 927)
@Composable
private fun HomeEmptyPreview() {
    VesselTheme {
        Box {
            HomeContent(
                state = HomeUiState(loaded = true),
                onNewContainer = {},
                onOpenSettings = {},
                onLaunch = {},
                onLaunchApp = {},
                onOpenAppProfile = {},
                onAddApp = {},
                onBrowseFiles = {},
            onOpenLicences = {},
            )
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF161826, widthDp = 927, heightDp = 422)
@Composable
private fun HomeLandscapePreview() {
    VesselTheme {
        HomeContent(
            state = HomeUiState(SampleHomeContainers, loaded = true),
            onNewContainer = {},
            onOpenSettings = {},
            onLaunch = {},
            onLaunchApp = {},
            onOpenAppProfile = {},
            onAddApp = {},
            onBrowseFiles = {},
            onOpenLicences = {},
        )
    }
}
