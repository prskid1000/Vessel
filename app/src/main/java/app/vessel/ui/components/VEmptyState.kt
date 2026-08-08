package app.vessel.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.vessel.ui.theme.Vessel
import app.vessel.ui.theme.VesselTheme

/**
 * An icon, one sentence, one action. Never two of anything.
 *
 * The sentence is capped at [MESSAGE_MAX_WIDTH] rather than at the screen's
 * width. Centred prose set the full width of a landscape phone is one line
 * 900 dp long, which is unreadable in the literal sense — the eye loses the
 * start of the line on the way back from the end of it.
 */
@Composable
fun VEmptyState(
    icon: ImageVector,
    message: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier.fillMaxSize().padding(Vessel.metrics.s17),
        verticalArrangement = Arrangement.spacedBy(Vessel.metrics.s11, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, Modifier.size(Vessel.metrics.s22), tint = Vessel.colors.textMuted)
        Text(
            message,
            style = Vessel.type.bodySmall,
            color = Vessel.colors.textLabel,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = MESSAGE_MAX_WIDTH),
        )
        if (actionLabel != null && onAction != null) {
            VButton(actionLabel, onAction, style = VButtonStyle.Primary)
        }
    }
}

/** About sixty characters of `bodySmall` — a readable measure, not a screen width. */
private val MESSAGE_MAX_WIDTH = 320.dp

@Preview(showBackground = true, backgroundColor = 0xFF161826, widthDp = 392, heightDp = 400)
@Composable
private fun VEmptyStatePreview() {
    VesselTheme {
        VEmptyState(
            icon = Icons.Filled.Add,
            message = "No containers yet. A new one is configured correctly for this device " +
                "without you setting anything.",
            actionLabel = "New container",
            onAction = {},
        )
    }
}
