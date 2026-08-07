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
import app.vessel.core.ComponentType
import app.vessel.ui.vm.ComponentSection
import app.vessel.ui.vm.ComponentsUiState
import app.vessel.ui.vm.ComponentsViewModel

/**
 * Components, reached from the overflow in the Containers toolbar rather than
 * from the bottom bar.
 *
 * One current build per component, all compiled for this device, so this is a
 * status view rather than a store to shop in. Everything it lists was read off
 * this phone's install directory — there is no seeded catalogue, which is why a
 * fresh device shows an empty screen and says so.
 */
@Composable
fun ComponentsScreen(
    onBack: () -> Unit,
    onInstall: (ComponentPackage) -> Unit,
    viewModel: ComponentsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ComponentsContent(state, onBack, onInstall, viewModel::refresh)
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
                subtitle = if (state.loaded) "$installed installed" else "reading",
                onBack = onBack,
                trailing = { VIconButton(Icons.Filled.Refresh, "Re-scan", onRefresh) },
            )
        },
    ) {
        if (state.sections.isEmpty()) {
            // Only once the directory has been read. Saying "nothing installed"
            // while the first scan is in flight would be a guess.
            if (state.loaded) {
                VEmptyState(
                    icon = Icons.Filled.Build,
                    message = "Nothing is installed yet. Components are the engine, Wine build, " +
                        "GPU driver and D3D layers this app compiles for this device, and none " +
                        "of them have been unpacked here.",
                    actionLabel = "Re-scan",
                    onAction = onRefresh,
                )
            }
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

// — previews ---------------------------------------------------------------
//
// Everything below this line is fixed data, and it exists only so the layout can
// be looked at without a device that has packages on it. It is deliberately not
// visible to the view model: the screen itself reads
// `files/components/*/profile.json` and shows exactly what is there, so a build
// listed here and absent from the phone stays a drawing rather than a claim.

@Preview(showBackground = true, backgroundColor = 0xFF161826, widthDp = 392, heightDp = 824)
@Composable
private fun ComponentsPreview() {
    VesselTheme {
        ComponentsContent(
            state = ComponentsUiState(PreviewSections, loaded = true),
            onBack = {},
            onInstall = {},
            onRefresh = {},
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF161826, widthDp = 392, heightDp = 824)
@Composable
private fun ComponentsEmptyPreview() {
    VesselTheme {
        ComponentsContent(
            state = ComponentsUiState(loaded = true),
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
            val section: ComponentSection = PreviewSections.last()
            VSectionHeader(section.title)
            section.warning?.let { MatchedSetWarning(it) }
            section.items.forEach { ComponentRow(it, {}) }
        }
    }
}

/** `build/targets/canoe.env` — Snapdragon SM8845 (Oryon) / Adreno 829. */
private const val PREVIEW_TARGET = "canoe"

/** What `resolve_cpu_flags` in `build/common.sh` settles on for this target. */
private const val PREVIEW_CPU_FLAGS = "-mcpu=oryon-1"

private fun previewPackage(
    id: String,
    type: ComponentType,
    name: String,
    version: String,
    versionCode: Int,
    sizeBytes: Long,
    sourceSha: String,
    installed: Boolean = true,
    cpuFlags: String = PREVIEW_CPU_FLAGS,
) = ComponentPackage(
    id = id,
    type = type,
    name = name,
    versionName = version,
    versionCode = versionCode,
    sizeBytes = sizeBytes,
    installed = installed,
    target = PREVIEW_TARGET,
    sourceSha = sourceSha,
    cpuFlags = cpuFlags,
)

/**
 * A fully provisioned device, drawn.
 *
 * One current build of each thing this phone needs and nothing else, which is
 * the shape the real screen takes once the packages are on disk: no Turnip for
 * an older Adreno, no legacy x86-64 Wine tree, no D3D majors older than the
 * driver they would pair with.
 */
private val PreviewSections = listOf(
    ComponentSection(
        title = "Engines",
        items = listOf(
            previewPackage(
                id = "fexcore-2608-canoe",
                type = ComponentType.FEXCORE,
                name = "FEXCore",
                version = "2608",
                versionCode = 2608,
                sizeBytes = 43_400_000,
                sourceSha = "c17ae5039f6b",
            ),
            previewPackage(
                id = "box64-0.4.4-canoe",
                type = ComponentType.BOX64,
                name = "Box64",
                version = "0.4.4",
                versionCode = 4004,
                sizeBytes = 9_300_000,
                sourceSha = "8f2c1d4a9b03",
            ),
        ),
    ),
    ComponentSection(
        title = "Wine builds",
        items = listOf(
            previewPackage(
                id = "wine-11.0-arm64ec-canoe",
                type = ComponentType.WINE,
                name = "Wine ARM64EC",
                version = "11.0",
                versionCode = 110_000,
                sizeBytes = 537_000_000,
                sourceSha = "a4d90b7e2c58",
                // build/wine.sh runs one configure for three PE targets, so
                // there is no single -mcpu to record.
                cpuFlags = "none (multi-target PE build)",
            ),
        ),
    ),
    ComponentSection(
        title = "GPU drivers",
        items = listOf(
            previewPackage(
                id = "turnip-25.2.0-gen8-canoe",
                type = ComponentType.TURNIP,
                name = "Turnip gen8",
                version = "25.2.0",
                versionCode = 250_200,
                sizeBytes = 29_100_000,
                sourceSha = "6b0f83ce1d47",
            ),
        ),
    ),
    ComponentSection(
        title = "D3D layers",
        items = listOf(
            previewPackage(
                id = "dxvk-2.7.1-canoe",
                type = ComponentType.DXVK,
                name = "DXVK",
                version = "2.7.1",
                versionCode = 20_701,
                sizeBytes = 14_900_000,
                sourceSha = "d52814fb96a0",
            ),
            previewPackage(
                id = "vkd3d-proton-3.0.1-canoe",
                type = ComponentType.VKD3D,
                name = "vkd3d-proton",
                version = "3.0.1",
                versionCode = 30_001,
                sizeBytes = 12_100_000,
                sourceSha = "1e7fa2c40b93",
                installed = false,
            ),
        ),
    ),
)
