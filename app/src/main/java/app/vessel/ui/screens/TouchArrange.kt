package app.vessel.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.vessel.display.TouchArrangeView
import app.vessel.input.TouchControl
import app.vessel.input.TouchLayout
import app.vessel.ui.HoldOrientation
import app.vessel.ui.SESSION_LANDSCAPE
import app.vessel.ui.components.VButton
import app.vessel.ui.components.VButtonStyle
import app.vessel.ui.theme.VElev
import app.vessel.ui.theme.Vessel
import app.vessel.ui.theme.vCard

/**
 * The whole screen, turned over to placing controls.
 *
 * **The panel's map is a preview and this is the editor, because one 232 dp
 * rectangle cannot be both.** Dragging a 15 dp button around a thumbnail is not
 * placing it, it is guessing where it will land; and over a running session the
 * panel and the rail together cover the left three fifths of the glass, which is
 * where the d-pad, the left stick and both left shoulders live. Reported exactly
 * that way — "it is hiding left side buttons" — and narrowing the panel does not
 * fix it, because those controls are under the *rail* as well.
 *
 * So arranging happens with nothing else on screen. It is the same
 * `TouchOverlayPainter` that draws the session's overlay, at the session's own
 * dimensions, and the same `TouchEdit` that moves and resizes there.
 *
 * **Landscape, always, including from the container sheet.** A control's place is
 * a fraction of a landscape screen because that is the shape a session has, so
 * arranging one in portrait would be arranging it against a rectangle it will
 * never appear in. It asks for the orientation through
 * [app.vessel.ui.LocalOrientationOverride] rather than setting it, so that
 * `VesselApp` stays the one writer.
 */
@Composable
fun TouchArrange(
    layout: TouchLayout,
    selected: String?,
    onSelect: (String?) -> Unit,
    onLayout: (TouchLayout) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    /** How far the gesture bar reaches up the bottom edge. See [TouchArrangeDialog]. */
    bottomInset: Dp = 0.dp,
) {
    HoldOrientation(SESSION_LANDSCAPE)
    BackHandler { onDone() }
    Box(modifier.fillMaxSize().background(Vessel.colors.neutral900)) {
        AndroidView(
            factory = { context -> TouchArrangeView(context) },
            modifier = Modifier.fillMaxSize(),
            update = { view ->
                view.layout = layout
                view.selected = selected
                view.onSelect = onSelect
                view.onLayout = onLayout
            },
        )
        ArrangeBar(
            control = layout.byId(selected),
            onDone = onDone,
            bottomInset = bottomInset,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

/**
 * [TouchArrange] over whatever asked for it.
 *
 * **A `Dialog` rather than a `Box` in the host, because the two hosts are in
 * awkward places.** Cold, the editor is opened from inside a bottom sheet, whose
 * content is clipped to the sheet; live, it is opened from a panel inside the
 * session's own layout. Neither can put a child across the whole screen without
 * being restructured around a surface that is only up for a minute. A dialog
 * window sits above both and needs nothing from either.
 *
 * Back closes it, which is the same gesture that leaves every other takeover in
 * this product.
 */
@Composable
fun TouchArrangeDialog(
    layout: TouchLayout,
    selected: String?,
    onSelect: (String?) -> Unit,
    onLayout: (TouchLayout) -> Unit,
    onDone: () -> Unit,
) {
    // **Measured out here, in the host, because the dialog is handed nothing.**
    // A dialog window whose decor does not fit system windows gets no insets
    // dispatched to it, and this one has to be configured that way — fitting them
    // would inset the canvas, and then a control placed against that rectangle
    // would land somewhere else in the session, which is the one thing this
    // screen must not do. The host does have insets, so the bar's clearance is
    // read from there and handed down. Watched being needed twice: without any
    // clearance the bar sat half under the gesture pill, and with a guessed 22 dp
    // the pill still crossed the Done button's corner.
    val bottomInset = WindowInsets.systemBars.asPaddingValues().calculateBottomPadding()
    Dialog(
        onDismissRequest = onDone,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        TouchArrange(
            layout = layout,
            selected = selected,
            onSelect = onSelect,
            onLayout = onLayout,
            onDone = onDone,
            bottomInset = bottomInset,
        )
    }
}

/**
 * The one strip of chrome, along the bottom edge, in the middle.
 *
 * **Bottom centre because that is the one region of a full pad that nothing
 * claims.** The obvious place was the top middle, and it was wrong the first time
 * it was looked at on the phone: `SEL` and `START` live at 46% and 54% of the top
 * edge, so the bar sat exactly on the two controls it was covering for. The
 * bottom corners belong to the thumbs and hold a stick each; the span between
 * them holds nothing, in this layout or in any arrangement a pair of thumbs would
 * choose.
 */
@Composable
private fun ArrangeBar(
    control: TouchControl?,
    onDone: () -> Unit,
    bottomInset: Dp,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .padding(Vessel.metrics.s8)
            .padding(bottom = bottomInset + Vessel.metrics.s11)
            .vCard(fill = Vessel.colors.surfaceFloating, elevation = VElev.md)
            .padding(horizontal = Vessel.metrics.s11, vertical = Vessel.metrics.s6),
        horizontalArrangement = Arrangement.spacedBy(Vessel.metrics.s11),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(
                control?.title ?: "Arrange the overlay",
                style = Vessel.type.body,
                color = Vessel.colors.textPrimary,
                maxLines = 1,
            )
            Text(
                // Both gestures, said once. A grip that is only drawn on the
                // selected control is one nobody finds by looking for it.
                if (control == null) {
                    "Tap a control to pick it up"
                } else {
                    "Drag to move · drag the corner grip to resize"
                },
                style = Vessel.type.monoSmall,
                color = Vessel.colors.textMuted,
                maxLines = 1,
            )
        }
        VButton("Done", onDone, style = VButtonStyle.Primary)
    }
}
