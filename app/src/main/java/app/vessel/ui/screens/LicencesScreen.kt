package app.vessel.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import app.vessel.ui.components.VEmptyState
import app.vessel.ui.components.VIcons
import app.vessel.ui.components.VPushToolbar
import app.vessel.ui.components.VScaffold
import app.vessel.ui.components.VTag
import app.vessel.ui.components.VTagTone
import app.vessel.ui.theme.Vessel
import app.vessel.ui.theme.VesselTheme
import app.vessel.ui.theme.vRuleBelow

/**
 * Pushed — the notice LGPL 2.1 section 6 asks for, and the licences themselves.
 *
 * **This screen is an obligation before it is a feature.** Section 6's first
 * sentence wants prominent notice, with each copy of the work, that the Library
 * is used in it; its second wants a copy of the licence supplied. The APK has
 * carried the licence texts for a while and had nowhere to show them, which
 * satisfied the second sentence and not the first. Until this existed the APK
 * should not have been distributed.
 *
 * So the paragraph at the top is not an introduction that can be trimmed for
 * length — it *is* the notice. The rows below it are the copies.
 */
@Composable
fun LicencesScreen(
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
) {
    VScaffold(
        toolbar = { VPushToolbar(title = "Licences", subtitle = "What Vessel is made of", onBack = onBack) },
    ) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = Vessel.metrics.s3, bottom = Vessel.metrics.s22),
        ) {
            item {
                Column(
                    Modifier.padding(bottom = Vessel.metrics.s6),
                    verticalArrangement = Arrangement.spacedBy(Vessel.metrics.s3),
                ) {
                    Text(
                        "Vessel uses the Winlator X server, by Bruno Rodrigues and " +
                            "contributors, which is covered by the GNU Lesser General Public " +
                            "License version 2.1. Vessel itself is licensed under the GNU LGPL " +
                            "version 2.1 or later.",
                        style = Vessel.type.body,
                    )
                    Text(
                        "Every licence below is included in full and can be read here. " +
                            "Vessel's source is at ${Licences.SOURCE}.",
                        style = Vessel.type.bodySmall,
                        color = Vessel.colors.textMuted,
                    )
                }
            }

            items(Licences.entries, key = { it.title }) { entry ->
                LicenceRow(entry) { onOpen(entry.title) }
            }
        }
    }
}

@Composable
private fun LicenceRow(entry: LicenceEntry, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .vRuleBelow(Vessel.colors.divider)
            .padding(vertical = Vessel.metrics.s8),
        verticalArrangement = Arrangement.spacedBy(Vessel.metrics.s3),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Vessel.metrics.s8),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(entry.title, style = Vessel.type.cardTitle, modifier = Modifier.weight(1f))
            VTag(entry.licence, tone = VTagTone.Neutral)
        }
        if (entry.author.isNotEmpty()) {
            Text(entry.author, style = Vessel.type.bodySmall, color = Vessel.colors.textMuted)
        }
        Text(entry.role, style = Vessel.type.bodySmall, color = Vessel.colors.textMuted)
        entry.source?.let {
            Text(it, style = Vessel.type.monoSmall, color = Vessel.colors.textMuted)
        }
    }
}

/**
 * One licence, in full, as its own text.
 *
 * Mono and horizontally scrollable rather than wrapped: these documents are hard
 * wrapped at 80 columns by their own authors, and re-wrapping them to a phone
 * makes the numbered clauses of the LGPL unreadable. Read line by line so a
 * 500-line licence is not one 30 KB `Text` measured on every frame.
 */
@Composable
fun LicenceTextScreen(title: String, onBack: () -> Unit) {
    val entry = remember(title) { Licences.byTitle(title) }
    val context = LocalContext.current
    val lines = remember(entry) {
        entry?.let {
            runCatching {
                context.resources.openRawResource(it.text)
                    .bufferedReader()
                    .use { reader -> reader.readLines() }
            }.getOrNull()
        }
    }

    VScaffold(
        toolbar = {
            VPushToolbar(
                title = entry?.licence ?: "Licence",
                subtitle = entry?.title,
                onBack = onBack,
            )
        },
    ) {
        if (lines == null) {
            // Not reachable through the interface — every entry names a resource
            // that is in the APK and `keep.xml` stops the shrinker taking one. It
            // is here because the alternative to a sentence is a blank screen,
            // and a licence that will not open is the one failure on this screen
            // that has consequences outside the app.
            VEmptyState(
                icon = VIcons.Warning,
                message = "This licence could not be read from the app. It is also in the " +
                    "source at ${Licences.SOURCE}.",
            )
            return@VScaffold
        }
        val scroll = rememberScrollState()
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = Vessel.metrics.s3, bottom = Vessel.metrics.s22),
        ) {
            items(lines.size) { index ->
                Text(
                    lines[index].ifEmpty { " " },
                    style = Vessel.type.monoSmall,
                    color = Vessel.colors.textMuted,
                    softWrap = false,
                    modifier = Modifier.horizontalScroll(scroll),
                )
            }
        }
    }
}

// — previews ---------------------------------------------------------------

@Preview(showBackground = true, backgroundColor = 0xFF161826, widthDp = 392, heightDp = 824)
@Composable
private fun LicencesPreview() {
    VesselTheme { LicencesScreen(onBack = {}, onOpen = {}) }
}
