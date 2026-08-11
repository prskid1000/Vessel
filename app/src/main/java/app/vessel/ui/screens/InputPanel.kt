package app.vessel.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.vessel.ui.components.VButtonStyle
import app.vessel.ui.components.VIconAction
import app.vessel.ui.components.VIcons
import app.vessel.ui.components.VRule
import app.vessel.ui.theme.VElev
import app.vessel.ui.theme.Vessel
import app.vessel.ui.theme.vCard

/**
 * The input editor, over the running desktop.
 *
 * **It is here rather than on a settings screen because a binding is only
 * knowable by testing it.** Whether `R2` should be left-click or `Space` is a
 * question about the game that is running, and every other placement makes the
 * loop *edit → save → launch → discover → back out → edit*, which on this device
 * is a two-minute cycle per binding. So the panel opens beside the rail, over the
 * desktop, and every change takes effect the moment it is made — there is no
 * draft and no Save, because the whole argument for being here is immediacy.
 *
 * The same editor opens cold from the container sheet; see [InputEditor]. This
 * file is only the panel around it: a card, a header with a way out, and the one
 * thing the session adds — **laying the overlay out collapses the panel**, because
 * you cannot place a control you cannot see and a 560 dp panel covers three fifths
 * of where the controls go.
 */
@Composable
fun InputPanel(
    state: InputEditorState,
    actions: InputEditorActions,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .width(if (state.editing) Vessel.metrics.railWidth else Vessel.metrics.inputPanelWidth)
            .fillMaxHeight()
            .systemBarsPadding()
            .padding(
                top = Vessel.metrics.s6,
                bottom = Vessel.metrics.s6,
                end = Vessel.metrics.s6,
            )
            .vCard(fill = Vessel.colors.surfaceFloating, elevation = VElev.md)
            .padding(horizontal = Vessel.metrics.s11),
    ) {
        InputEditorHeader(
            state = state,
            actions = actions,
            leading = {
                VIconAction(
                    icon = VIcons.X,
                    contentDescription = "Close the input panel",
                    onClick = onClose,
                    style = VButtonStyle.Ghost,
                    size = CLEAR_TARGET,
                )
            },
            modifier = Modifier.padding(vertical = Vessel.metrics.s8),
        )
        VRule(verticalMargin = 0.dp)
        InputEditor(
            state = state,
            actions = actions,
            modifier = Modifier.padding(top = Vessel.metrics.s8),
        )
    }
}
