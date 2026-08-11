package app.vessel.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.vessel.core.DisplayGeometry
import app.vessel.data.InputProfileTransfer
import app.vessel.input.GamepadAction
import app.vessel.input.GamepadControl
import app.vessel.input.InputProfile
import app.vessel.input.StickRole
import app.vessel.input.TouchControl
import app.vessel.input.TouchControls
import app.vessel.input.TouchEdit
import app.vessel.input.TouchKind
import app.vessel.input.TouchLayout
import app.vessel.input.TouchLayouts
import app.vessel.input.X11KeyCatalog
import app.vessel.ui.components.VButton
import app.vessel.ui.components.VButtonStyle
import app.vessel.ui.components.VCaution
import app.vessel.ui.components.VDropdownField
import app.vessel.ui.components.VIconAction
import app.vessel.ui.components.VIcons
import app.vessel.ui.components.VLabeledField
import app.vessel.ui.components.VRule
import app.vessel.ui.components.VTextField
import app.vessel.ui.components.VToggle
import app.vessel.ui.theme.Vessel
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The three things input is: a controller, a screen you touch, and the named
 * arrangement that holds both.
 *
 * The tabs are the design's, and the split is not arbitrary — each one answers a
 * different question. *Pad* is "what does this button send", *Touch* is "where is
 * that button on the glass", and *Profiles* is "which of my arrangements is this
 * container using". A single scrolling screen holding all three was the shape
 * this replaced, and it made the second and third invisible.
 */
enum class InputTab(val title: String) {
    PAD("Pad"),
    TOUCH("Touch"),
    PROFILES("Profiles"),
}

/**
 * Everything the editor draws, gathered so the two entry points hand it the same
 * thing.
 *
 * It is opened in two places and they are genuinely different situations. Over a
 * **running session** the pad diagram lights up, the overlay is on screen behind
 * the panel and a change takes effect on the next frame. From the **container
 * sheet** none of that is true: nothing is running, so the diagram is drawn
 * faintly, the overlay is a preview, and [live] is what tells the two apart in
 * one place rather than at every point where they differ.
 */
data class InputEditorState(
    val profile: InputProfile,
    /** Every stored profile. The built-in default is not in here; it is never stored. */
    val profiles: List<InputProfile> = emptyList(),
    /** What the container points at. Null is the built-in default. */
    val activeProfileId: String? = null,
    /** True when the container names a profile that has since been deleted. */
    val missingProfile: Boolean = false,
    val containerName: String = "",
    val guest: DisplayGeometry? = null,
    val live: Boolean = false,
    val held: Set<GamepadControl> = emptySet(),
    val touchVisible: Boolean = false,
    val editing: Boolean = false,
    val selected: String? = null,
    /** An import that was refused, or anything else worth saying once. */
    val notice: String? = null,
)

/** What the editor can do. One lambda per verb, so nothing here knows about a store. */
data class InputEditorActions(
    val onProfile: (InputProfile) -> Unit = {},
    val onPickProfile: (String?) -> Unit = {},
    val onRename: (String) -> Unit = {},
    val onNewProfile: () -> Unit = {},
    val onDuplicate: (InputProfile) -> Unit = {},
    val onDelete: (InputProfile) -> Unit = {},
    val onImportText: (String) -> Unit = {},
    val onExportText: (InputProfile) -> String? = { null },
    val onTouchVisible: (Boolean) -> Unit = {},
    val onEditing: (Boolean) -> Unit = {},
    val onSelect: (String?) -> Unit = {},
    val onDismissNotice: () -> Unit = {},
)

/**
 * The header: where you are, which container, and which profile.
 *
 * The profile picker is in the header rather than buried in the Profiles tab
 * because it is the one control that changes what every other tab is showing.
 * Putting it on its own tab would mean switching tabs to find out what the first
 * two were about.
 */
@Composable
fun InputEditorHeader(
    state: InputEditorState,
    actions: InputEditorActions,
    leading: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Vessel.metrics.s8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading()
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Vessel.metrics.s3)) {
            Text(
                "Input",
                style = Vessel.type.subtitle,
                color = Vessel.colors.textPrimary,
                maxLines = 1,
            )
            Text(
                state.subtitle(),
                style = Vessel.type.monoSmall,
                color = Vessel.colors.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box(Modifier.width(PROFILE_FIELD_WIDTH)) {
            val ids = listOf(InputProfile.DEFAULT_ID) + state.profiles.map { it.id }
            // The profile being edited is in the map even before the list of
            // stored ones has arrived. Without it the field renders a UUID for
            // the first frame or two after the screen opens, which is the one
            // string on it that means nothing to anybody.
            val names = (listOf(InputProfile.Default, state.profile) + state.profiles)
                .associate { it.id to it.name }
            VDropdownField(
                options = ids,
                labelFor = { names[it] ?: it },
                selected = state.activeProfileId ?: InputProfile.DEFAULT_ID,
                onSelect = { actions.onPickProfile(it.takeIf { id -> id != InputProfile.DEFAULT_ID }) },
            )
        }
    }
}

private fun InputEditorState.subtitle(): String {
    val name = containerName.ifBlank { "This container" }
    val size = guest?.let { "${it.width}×${it.height}" }
    return if (size == null) name else "$name · $size"
}

private val PROFILE_FIELD_WIDTH = 150.dp

/**
 * The tabs and whatever is under the one that is open.
 *
 * `BoxWithConstraints` rather than a flag from the caller: the panel over a
 * session is 560 dp and the sheet is 421 dp, and the difference that matters is
 * whether the settings fit beside the list — which is a question about width, not
 * about which screen this is. A two-column layout squeezed into 421 dp is two
 * columns of nothing.
 */
@Composable
fun InputEditor(
    state: InputEditorState,
    actions: InputEditorActions,
    tab: InputTab,
    onTab: (InputTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        InputTabs(tab, onTab)
        state.notice?.let { notice ->
            Column(Modifier.padding(top = Vessel.metrics.s8)) {
                VCaution(notice)
                VButton("Dismiss", actions.onDismissNotice, style = VButtonStyle.Ghost)
            }
        }
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            val wide = maxWidth >= TWO_COLUMN_WIDTH
            when (tab) {
                InputTab.PAD -> PadTab(
                    profile = state.profile,
                    lit = state.held,
                    live = state.live,
                    wide = wide,
                    onProfile = actions.onProfile,
                    modifier = Modifier.padding(top = Vessel.metrics.s8),
                )

                InputTab.TOUCH -> TouchTab(state, actions, wide)
                InputTab.PROFILES -> ProfilesTab(state, actions)
            }
        }
    }
}

/** Below this the settings cannot sit beside the list, so they sit above it. */
private val TWO_COLUMN_WIDTH = 500.dp

@Composable
private fun InputTabs(tab: InputTab, onTab: (InputTab) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(Vessel.metrics.shapeTag)
            .background(Vessel.colors.neutral900)
            .padding(Vessel.metrics.s3),
        horizontalArrangement = Arrangement.spacedBy(Vessel.metrics.s3),
    ) {
        InputTab.entries.forEach { entry ->
            val selected = entry == tab
            Box(
                Modifier
                    .weight(1f)
                    .height(TAB_HEIGHT)
                    .clip(Vessel.metrics.shapeMd)
                    .background(if (selected) Vessel.colors.accentHover else Color.Transparent)
                    .clickable(onClickLabel = entry.title) { onTab(entry) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    entry.title,
                    style = Vessel.type.bodySmall,
                    color = if (selected) Vessel.colors.accent else Vessel.colors.textLabel,
                    maxLines = 1,
                )
            }
        }
    }
}

private val TAB_HEIGHT = 28.dp

// — Touch -----------------------------------------------------------------------

@Composable
private fun TouchTab(state: InputEditorState, actions: InputEditorActions, wide: Boolean) {
    // Resolved, because a pad-linked control's binding lives in the pad table
    // and only the resolved layout knows what it sends. Edits still write the
    // stored form, which keeps the link.
    val layout = state.profile.overlay
    val onLayout: (TouchLayout) -> Unit = { actions.onProfile(state.profile.copy(touch = it)) }

    if (wide) {
        Row(Modifier.fillMaxSize().padding(top = Vessel.metrics.s8)) {
            Column(
                Modifier
                    .width(TOUCH_COLUMN_WIDTH)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState())
                    .padding(end = Vessel.metrics.s11),
                verticalArrangement = Arrangement.spacedBy(Vessel.metrics.s11),
            ) {
                TouchSettings(state, actions)
                TouchSelectionEditor(state, actions, onLayout)
                TouchAddRow(layout, actions, onLayout)
            }
            Box(
                Modifier
                    .width(Vessel.metrics.hairline)
                    .fillMaxHeight()
                    .background(Vessel.colors.divider),
            )
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState())
                    .padding(start = Vessel.metrics.s11),
            ) {
                TouchPreviewCard(state, onLayout, actions)
                TouchControlList(state, actions)
            }
        }
        return
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(top = Vessel.metrics.s8, bottom = Vessel.metrics.s22),
        verticalArrangement = Arrangement.spacedBy(Vessel.metrics.s11),
    ) {
        // Not while the real overlay is on screen behind this panel. A picture of
        // the thing you are dragging, next to the thing you are dragging, is one
        // of them lying.
        if (!(state.live && state.editing)) TouchPreviewCard(state, onLayout, actions)
        TouchSettings(state, actions)
        TouchControlList(state, actions)
        TouchSelectionEditor(state, actions, onLayout)
        TouchAddRow(layout, actions, onLayout)
    }
}

private val TOUCH_COLUMN_WIDTH = 232.dp

/**
 * The callout and the preview.
 *
 * **The preview is the shape of the *screen*, not of the guest desktop, and the
 * callout says so.** The design comp drew it at the container's 1280x720 because
 * that is what a desktop is; the data model is the opposite and deliberately so —
 * a control's position is a fraction of the surface, so that changing the guest
 * resolution does not move a button out from under a thumb. Drawing the preview
 * at the guest's aspect would therefore be a picture of somewhere the controls
 * are not.
 */
@Composable
private fun TouchPreviewCard(
    state: InputEditorState,
    onLayout: (TouchLayout) -> Unit,
    actions: InputEditorActions,
) {
    val short = sessionShortEdgeDp()
    val long = sessionLongEdgeDp()
    Column(verticalArrangement = Arrangement.spacedBy(Vessel.metrics.s8)) {
        InputNote(
            "Laid out against the screen in landscape, which is the shape the overlay " +
                "will have — not the shape of the screen you are holding. A control's " +
                "place is a fraction of the screen, so changing " +
                (state.guest?.let { "${it.width}×${it.height}" } ?: "the resolution") +
                " does not move it.",
        )
        TouchOverlayPreview(
            layout = state.profile.overlay,
            aspect = long.value / short.value.coerceAtLeast(1f),
            selected = state.selected,
            onSelect = actions.onSelect,
            onLayout = onLayout,
        )
    }
}

/**
 * The overlay, at the shape it will have, with every control where it will be.
 *
 * Draggable, because on the container sheet there is no session to drag the real
 * thing on — and a layout editor that can only be reached by starting a game is
 * the trap the whole cold entry point exists to avoid.
 */
@Composable
private fun TouchOverlayPreview(
    layout: TouchLayout,
    aspect: Float,
    selected: String?,
    onSelect: (String?) -> Unit,
    onLayout: (TouchLayout) -> Unit,
) {
    var size by remember { mutableStateOf(0f to 0f) }
    val density = LocalDensity.current
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(aspect.coerceAtLeast(0.2f))
            .clip(Vessel.metrics.shapeMd)
            .background(Vessel.colors.neutral900)
            .border(Vessel.metrics.hairline, Vessel.colors.border, Vessel.metrics.shapeMd)
            .onSizeChanged { size = it.width.toFloat() to it.height.toFloat() }
            .pointerInput(Unit) {
                // A press on bare canvas deselects, which is the only way back to
                // "nothing is selected" once something is.
                detectTapGestures { onSelect(null) }
            },
    ) {
        val (w, h) = size
        if (w <= 0f || h <= 0f) return@Box
        layout.controls.forEach { control ->
            val radius = control.radiusPx(w, h)
            val diameter = with(density) { (radius * 2).toDp() }
            val isSelected = control.id == selected
            Box(
                Modifier
                    .offset(
                        x = with(density) { (control.centreX(w) - radius).toDp() },
                        y = with(density) { (control.centreY(h) - radius).toDp() },
                    )
                    .size(diameter)
                    .clip(if (control.round) Vessel.metrics.shapePill else Vessel.metrics.shapeMd)
                    .background(Vessel.colors.surfaceFloating)
                    .border(
                        if (isSelected) 2.dp else Vessel.metrics.hairline,
                        if (isSelected) Vessel.colors.accent else Vessel.colors.accent700,
                        if (control.round) Vessel.metrics.shapePill else Vessel.metrics.shapeMd,
                    )
                    .pointerInput(control.id, w, h) {
                        detectDragGestures(
                            onDragStart = { onSelect(control.id) },
                        ) { change, _ ->
                            change.consume()
                            // The drag is reported inside the control's own box,
                            // so its position has to be put back into the canvas
                            // before it means anything.
                            val current = layout.byId(control.id) ?: return@detectDragGestures
                            val r = current.radiusPx(w, h)
                            val x = current.centreX(w) - r + change.position.x
                            val y = current.centreY(h) - r + change.position.y
                            onLayout(layout.with(TouchEdit.moved(current, x, y, w, h)))
                        }
                    }
                    .clickable(onClickLabel = control.designation) { onSelect(control.id) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    control.face,
                    style = Vessel.type.overline,
                    color = Vessel.colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                )
            }
        }
    }
}

/** Play or lay out, whether it is drawn at all, and how solid. */
@Composable
private fun TouchSettings(state: InputEditorState, actions: InputEditorActions) {
    Column(verticalArrangement = Arrangement.spacedBy(Vessel.metrics.s11)) {
        if (state.live) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(Vessel.metrics.shapeTag)
                    .background(Vessel.colors.neutral900)
                    .padding(Vessel.metrics.s3),
                horizontalArrangement = Arrangement.spacedBy(Vessel.metrics.s3),
            ) {
                listOf(false to "Play", true to "Edit layout").forEach { (mode, title) ->
                    val on = state.editing == mode
                    Box(
                        Modifier
                            .weight(1f)
                            .height(TAB_HEIGHT)
                            .clip(Vessel.metrics.shapeMd)
                            .background(if (on) Vessel.colors.accentHover else Color.Transparent)
                            .clickable(onClickLabel = title) { actions.onEditing(mode) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            title,
                            style = Vessel.type.bodySmall,
                            color = if (on) Vessel.colors.accent else Vessel.colors.textLabel,
                        )
                    }
                }
            }
        }

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Vessel.metrics.s8),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            VToggle(checked = state.touchVisible, onCheckedChange = actions.onTouchVisible)
            Text(
                "Show the overlay",
                style = Vessel.type.body,
                color = Vessel.colors.textPrimary,
                modifier = Modifier.weight(1f),
            )
        }

        // One slider for the whole overlay rather than one per control. The model
        // stores opacity per control — a stick a thumb rests on wants to be
        // fainter than a button you have to find — but nothing yet asks for that
        // difference, and two dozen sliders to express it would be a worse
        // editor than one.
        val opacity = state.profile.touch.controls.firstOrNull()?.opacity
            ?: TouchControls.DEFAULT_OPACITY
        InputSlider(
            label = "Opacity",
            value = opacity,
            min = TouchControls.MIN_OPACITY,
            max = TouchControls.MAX_OPACITY,
            readout = "${(opacity * 100).roundToInt()} %",
            help = "The overlay is on top of the guest, so it takes the touch before Wine " +
                "does. Anywhere a control is not, the touch goes through.",
            onValue = { next ->
                actions.onProfile(
                    state.profile.copy(
                        touch = TouchLayout(
                            state.profile.touch.controls.map { it.copy(opacity = next) },
                        ),
                    ),
                )
            },
        )

        if (state.editing) {
            InputNote("The guest is not receiving input while you edit.")
        }
    }
}

/** `6 CONTROLS ON THE OVERLAY`, and the six rows. */
@Composable
private fun TouchControlList(
    state: InputEditorState,
    actions: InputEditorActions,
    modifier: Modifier = Modifier,
) {
    val layout = state.profile.overlay
    val short = sessionShortEdgeDp()
    Column(modifier.fillMaxWidth()) {
        Text(
            if (layout.isEmpty) {
                "NOTHING ON THE OVERLAY"
            } else {
                "${layout.controls.size} CONTROLS ON THE OVERLAY"
            },
            style = Vessel.type.overline,
            color = Vessel.colors.textMuted,
            modifier = Modifier.padding(
                top = Vessel.metrics.s11,
                bottom = Vessel.metrics.s3,
            ),
        )
        if (layout.isEmpty) {
            StockLayoutOffer(state, actions)
            return@Column
        }
        // **A plain column, not a lazy one, and that is not laziness about
        // laziness.** In the one-column layout this list sits inside the tab's
        // own scroll, and a `LazyColumn` there is measured with an infinite
        // maximum height and throws — which it did, on the device, the first
        // time the Touch tab was opened. An overlay holds a dozen controls at the
        // outside; there is nothing here for a lazy list to save.
        Column(Modifier.padding(bottom = Vessel.metrics.s11)) {
            layout.controls.forEach { control ->
                TouchControlRow(
                    control = control,
                    selected = control.id == state.selected,
                    shortEdge = short,
                    onClick = { actions.onSelect(control.id) },
                )
            }
        }
    }
}

/**
 * What an empty overlay offers instead of an empty canvas.
 *
 * Plan §4.5's three stock layouts, and this is the moment they are for: a profile
 * whose overlay has never been touched, where the alternative is a rectangle with
 * nothing in it and a user who has to guess that Add is the way in.
 */
@Composable
private fun StockLayoutOffer(state: InputEditorState, actions: InputEditorActions) {
    Column(verticalArrangement = Arrangement.spacedBy(Vessel.metrics.s6)) {
        Text(
            "This profile draws nothing on the screen. Start from one of these, or add " +
                "controls one at a time.",
            style = Vessel.type.bodySmall,
            color = Vessel.colors.textMuted,
        )
        TouchLayouts.stock.filterNot { it.layout.isEmpty }.forEach { stock ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(Vessel.metrics.shapeMd)
                    .border(Vessel.metrics.hairline, Vessel.colors.border, Vessel.metrics.shapeMd)
                    .clickable(onClickLabel = stock.name) {
                        actions.onProfile(state.profile.copy(touch = stock.layout))
                    }
                    .heightIn(min = Vessel.metrics.touchTarget)
                    .padding(horizontal = Vessel.metrics.s11, vertical = Vessel.metrics.s8),
                horizontalArrangement = Arrangement.spacedBy(Vessel.metrics.s8),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stock.name, style = Vessel.type.body)
                    Text(
                        stock.note,
                        style = Vessel.type.bodySmall,
                        color = Vessel.colors.textMuted,
                    )
                }
            }
        }
    }
}

@Composable
private fun TouchControlRow(
    control: TouchControl,
    selected: Boolean,
    shortEdge: Dp,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(Vessel.metrics.shapeMd)
            .background(if (selected) Vessel.colors.accentHover else Color.Transparent)
            .clickable(onClickLabel = control.designation, onClick = onClick)
            .heightIn(min = Vessel.metrics.touchTarget)
            .padding(horizontal = Vessel.metrics.s6),
        horizontalArrangement = Arrangement.spacedBy(Vessel.metrics.s8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The dot is the control's own shape at 20 dp: round for a stick or a
        // button, square for a d-pad. A list of six identical dots would say
        // nothing that the kind column does not already say in words.
        Box(
            Modifier
                .size(20.dp)
                .clip(if (control.round) Vessel.metrics.shapePill else Vessel.metrics.shapeSm)
                .border(
                    Vessel.metrics.hairline,
                    if (selected) Vessel.colors.accent else Vessel.colors.border,
                    if (control.round) Vessel.metrics.shapePill else Vessel.metrics.shapeSm,
                ),
        )
        Column(Modifier.weight(1f)) {
            Text(control.title, style = Vessel.type.body, maxLines = 1)
            Text(
                control.metrics(shortEdge),
                style = Vessel.type.monoSmall,
                color = Vessel.colors.textMuted,
                maxLines = 1,
            )
        }
        BindingChip(control.bindingLabel, bound = control.bindingLabel != "Unbound")
    }
}

/**
 * `11% · 66% · 141 dp`.
 *
 * The third figure is the control's **diameter in dp of the landscape session**,
 * not in guest pixels. The design comp said guest pixels; the model says a
 * control's size is a fraction of the surface, and quoting it against the guest
 * desktop would be a number that changes when the resolution does while the
 * control on screen does not move at all.
 */
private fun TouchControl.metrics(shortEdge: Dp): String {
    val across = (size * 2f * shortEdge.value).roundToInt()
    return "${(cx * 100).roundToInt()}% · ${(cy * 100).roundToInt()}% · $across dp"
}

/**
 * The selected control's own settings: what it sends, how big, and Remove.
 *
 * A stick and a d-pad get four pickers rather than one, because they are four
 * keys — and a look pad gets none at all, because a pointer velocity is not a
 * key and pretending otherwise is what the [StickRole] distinction exists to
 * prevent.
 */
@Composable
private fun TouchSelectionEditor(
    state: InputEditorState,
    actions: InputEditorActions,
    onLayout: (TouchLayout) -> Unit,
) {
    val layout = state.profile.overlay
    val control = layout.byId(state.selected)
    var picking by remember { mutableStateOf<String?>(null) }

    if (control == null) {
        if (!layout.isEmpty) {
            InputNote(
                if (state.editing) {
                    "Drag a control to move it, drag its corner to resize. Tap one to " +
                        "change what it sends."
                } else {
                    "Tap a control to change what it sends, how big it is, or to remove it."
                },
            )
        }
        return
    }

    val slot = picking
    if (slot != null) {
        KeyPicker(
            title = "${control.title} · $slot",
            current = control.actionFor(slot),
            onClose = { picking = null },
            onChoose = { action ->
                // **A pad-linked control rebinds the pad table, not itself.** The
                // glass A button and the physical A button are the same control
                // seen twice; editing one to disagree with the other would undo
                // the whole reason the link exists.
                val linked = control.padFor(slot)
                if (linked != null) {
                    actions.onProfile(
                        state.profile.copy(
                            pad = state.profile.pad.copy(
                                bindings = state.profile.pad.bindings + (linked to action),
                            ),
                        ),
                    )
                } else {
                    onLayout(layout.with(control.withAction(slot, action)))
                }
                picking = null
            },
            modifier = Modifier.fillMaxWidth().height(KEY_PICKER_HEIGHT),
        )
        return
    }

    Column(verticalArrangement = Arrangement.spacedBy(Vessel.metrics.s8)) {
        VRule(verticalMargin = Vessel.metrics.s3)
        Text(control.title, style = Vessel.type.cardTitle)

        control.slots().forEach { name ->
            VLabeledField(label = if (name == SLOT_SENDS) "Sends" else name) {
                VButton(
                    X11KeyCatalog.label(control.actionFor(name)),
                    { picking = name },
                    style = VButtonStyle.Secondary,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        if (control.kind == TouchKind.STICK && control.role == StickRole.Look) {
            InputNote(
                "A look pad moves the mouse. There is no analogue axis a Windows game " +
                    "can read, so this is the only thing it can be.",
            )
        }

        val short = sessionShortEdgeDp()
        InputSlider(
            label = "Size",
            value = control.size,
            min = TouchControls.MIN_SIZE,
            max = TouchControls.MAX_SIZE,
            readout = "${(control.size * 2f * short.value).roundToInt()} dp",
            help = null,
            onValue = { onLayout(layout.with(control.copy(size = it))) },
        )
        Text(
            "${(control.cx * 100).roundToInt()}% from the left · " +
                "${(control.cy * 100).roundToInt()}% down",
            style = Vessel.type.monoSmall,
            color = Vessel.colors.textMuted,
        )
        VButton(
            "Remove",
            {
                onLayout(layout.without(control.id))
                actions.onSelect(null)
            },
            style = VButtonStyle.Danger,
            icon = VIcons.Trash,
        )
    }
}

private val KEY_PICKER_HEIGHT = 420.dp

private const val SLOT_SENDS = "Sends"

private fun TouchControl.slots(): List<String> = when {
    kind == TouchKind.BUTTON -> listOf(SLOT_SENDS)
    kind == TouchKind.STICK && role == StickRole.Look -> emptyList()
    else -> listOf("Up", "Down", "Left", "Right")
}

/**
 * Which pad control a slot on this control writes to, or null when the control
 * holds its own binding.
 *
 * A d-pad names one direction and owns all four, so the slot decides which; a
 * stick's four half-axes work the same way. A plain pad button has one slot and
 * one control.
 */
private fun TouchControl.padFor(slot: String): GamepadControl? {
    padStick?.let { stick ->
        return when (slot) {
            "Up" -> stick.up
            "Down" -> stick.down
            "Left" -> stick.left
            "Right" -> stick.right
            else -> null
        }
    }
    val linked = pad ?: return null
    if (kind != TouchKind.DPAD) return linked
    return when (slot) {
        "Up" -> GamepadControl.DPAD_UP
        "Down" -> GamepadControl.DPAD_DOWN
        "Left" -> GamepadControl.DPAD_LEFT
        "Right" -> GamepadControl.DPAD_RIGHT
        else -> null
    }
}

private fun TouchControl.actionFor(slot: String): GamepadAction = when (slot) {
    "Up" -> up
    "Down" -> down
    "Left" -> left
    "Right" -> right
    else -> action
}

private fun TouchControl.withAction(slot: String, next: GamepadAction): TouchControl = when (slot) {
    "Up" -> copy(up = next)
    "Down" -> copy(down = next)
    "Left" -> copy(left = next)
    "Right" -> copy(right = next)
    else -> copy(action = next, label = X11KeyCatalog.label(next))
}

/**
 * Add a control, having first asked what kind.
 *
 * A stick, a d-pad and a look pad are one each — the translator has exactly one
 * left stick, one hat and one right stick to give them — so a second is offered
 * as unavailable rather than hidden. Hiding it would make the limit look like a
 * missing feature.
 */
@Composable
private fun TouchAddRow(
    layout: TouchLayout,
    actions: InputEditorActions,
    onLayout: (TouchLayout) -> Unit,
) {
    var adding by remember { mutableStateOf(false) }
    if (!adding) {
        VButton(
            "Add a control",
            { adding = true },
            style = VButtonStyle.Secondary,
            icon = VIcons.Plus,
            modifier = Modifier.fillMaxWidth(),
        )
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(Vessel.metrics.s6)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "WHAT KIND",
                style = Vessel.type.overline,
                color = Vessel.colors.textMuted,
                modifier = Modifier.weight(1f),
            )
            VIconAction(
                icon = VIcons.X,
                contentDescription = "Stop adding a control",
                onClick = { adding = false },
                style = VButtonStyle.Ghost,
                size = CLEAR_TARGET,
            )
        }
        KINDS.forEach { kind ->
            val taken = kind.unique && layout.has(kind.title)
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(Vessel.metrics.shapeMd)
                    .border(
                        Vessel.metrics.hairline,
                        if (taken) Vessel.colors.border else Vessel.colors.accent700,
                        Vessel.metrics.shapeMd,
                    )
                    .clickable(enabled = !taken, onClickLabel = kind.title) {
                        val control = TouchEdit.placed(layout, kind.kind, kind.role)
                        onLayout(layout.with(control))
                        actions.onSelect(control.id)
                        adding = false
                    }
                    .heightIn(min = Vessel.metrics.touchTarget)
                    .padding(horizontal = Vessel.metrics.s11, vertical = Vessel.metrics.s8),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        kind.title,
                        style = Vessel.type.body,
                        color = if (taken) Vessel.colors.textMuted else Vessel.colors.textPrimary,
                    )
                    Text(
                        if (taken) "already on the overlay" else kind.note,
                        style = Vessel.type.bodySmall,
                        color = Vessel.colors.textMuted,
                    )
                }
            }
        }
        InputNote(
            "It lands in the middle of the screen, already selected. Drag it where your " +
                "thumb actually is.",
        )
    }
}

private data class AddableKind(
    val title: String,
    val note: String,
    val kind: TouchKind,
    val role: StickRole = StickRole.Keys,
    val unique: Boolean = false,
)

private val KINDS = listOf(
    AddableKind("Button", "one key or click", TouchKind.BUTTON),
    AddableKind("Stick", "four keys", TouchKind.STICK, StickRole.Keys, unique = true),
    AddableKind("D-pad", "four keys", TouchKind.DPAD, unique = true),
    AddableKind("Look pad", "relative mouse", TouchKind.STICK, StickRole.Look, unique = true),
)

// — Profiles ---------------------------------------------------------------------

/**
 * Every named arrangement on the device, and which one this container starts on.
 *
 * **A profile belongs to no container until one selects it.** Several containers
 * may share one, which is why deleting a profile does not rewrite the containers
 * that named it: a stale pointer resolves to the built-in default on the next
 * launch and the sheet says so, rather than the delete quietly editing a second
 * document.
 */
@Composable
private fun ProfilesTab(state: InputEditorState, actions: InputEditorActions) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(top = Vessel.metrics.s11, bottom = Vessel.metrics.s22),
        verticalArrangement = Arrangement.spacedBy(Vessel.metrics.s11),
    ) {
        if (state.missingProfile) {
            VCaution(
                "This container names a profile that has been deleted. It starts on the " +
                    "built-in default until something else is chosen — nothing was " +
                    "rewritten, so restoring the profile restores the choice.",
            )
        }

        // Rename is the current profile's name field rather than a button on
        // every row: renaming is the one profile action that is a *value*, and a
        // field is what this product uses for a value everywhere else.
        VLabeledField(
            label = "Name",
            help = if (state.profile.isBuiltInDefault) {
                "The built-in controller cannot be renamed or deleted — it is what a " +
                    "container falls back to, so there is always one. Duplicate it and " +
                    "the copy is yours to name, change and remove."
            } else {
                null
            },
        ) {
            VTextField(
                state.profile.name,
                actions.onRename,
                // Read-only rather than absent: the name is the thing being
                // explained, and a field that vanished would leave the sentence
                // under it talking about nothing.
                enabled = !state.profile.isBuiltInDefault,
                placeholder = "My controller",
            )
        }

        VRule(verticalMargin = Vessel.metrics.s3)

        (listOf(InputProfile.Default) + state.profiles).forEach { profile ->
            ProfileRow(
                profile = profile,
                active = (state.activeProfileId ?: InputProfile.DEFAULT_ID) == profile.id,
                actions = actions,
            )
        }

        InputNote(
            "A profile belongs to no container until one selects it. The built-in default " +
                "is what a container gets when it has never been given one, and it is never " +
                "written to disk.",
        )

        VRule(verticalMargin = Vessel.metrics.s3)

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Vessel.metrics.s8),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            VButton("New", actions.onNewProfile, style = VButtonStyle.Secondary, icon = VIcons.Plus)
            Box(Modifier.weight(1f))
            ProfileTransferButtons(state, actions)
        }
    }
}

@Composable
private fun ProfileRow(
    profile: InputProfile,
    active: Boolean,
    actions: InputEditorActions,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(Vessel.metrics.shapeMd)
            .background(if (active) Vessel.colors.accentHover else Color.Transparent)
            .clickable(onClickLabel = profile.name) {
                actions.onPickProfile(profile.id.takeIf { it != InputProfile.DEFAULT_ID })
            }
            .heightIn(min = Vessel.metrics.touchTarget)
            .padding(horizontal = Vessel.metrics.s8, vertical = Vessel.metrics.s6),
        horizontalArrangement = Arrangement.spacedBy(Vessel.metrics.s11),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(18.dp)
                .clip(Vessel.metrics.shapePill)
                .border(
                    Vessel.metrics.hairline,
                    if (active) Vessel.colors.accent else Vessel.colors.border,
                    Vessel.metrics.shapePill,
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (active) {
                Box(
                    Modifier
                        .size(8.dp)
                        .clip(Vessel.metrics.shapePill)
                        .background(Vessel.colors.accent),
                )
            }
        }
        Column(Modifier.weight(1f)) {
            Text(profile.name, style = Vessel.type.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${profile.boundCount} bound · " +
                    "${profile.touch.controls.size} on the overlay" +
                    if (profile.isBuiltInDefault) " · built in" else "",
                style = Vessel.type.monoSmall,
                color = Vessel.colors.textMuted,
                maxLines = 1,
            )
        }
        VIconAction(
            icon = VIcons.Copy,
            contentDescription = "Duplicate ${profile.name}",
            onClick = { actions.onDuplicate(profile) },
            style = VButtonStyle.Ghost,
            size = CLEAR_TARGET,
        )
        VIconAction(
            icon = VIcons.Trash,
            contentDescription = "Delete ${profile.name}",
            onClick = { actions.onDelete(profile) },
            style = VButtonStyle.Ghost,
            // The built-in default is a constant rather than a record: there is
            // nothing on disk to delete, and offering it would be a button that
            // could only ever do nothing.
            enabled = !profile.isBuiltInDefault,
            size = CLEAR_TARGET,
        )
    }
}

/**
 * Import and export, through the storage-access framework.
 *
 * SAF rather than writing beside the container, which was the other option the
 * plan left open. The deciding argument is that an exported profile is meant to
 * *leave the device* — to be sent to someone with the same game — and a file in
 * this app's private directory is one nothing else can open. The user picks
 * where; the app never guesses.
 */
@Composable
private fun ProfileTransferButtons(state: InputEditorState, actions: InputEditorActions) {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<String?>(null) }

    val create = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(EXPORT_MIME),
    ) { uri ->
        val text = pending
        pending = null
        if (uri == null || text == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.openOutputStream(uri)?.use { it.write(text.encodeToByteArray()) }
        }
    }

    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
        }.getOrNull()
        actions.onImportText(text.orEmpty())
    }

    VButton(
        "Import",
        { open.launch(arrayOf(EXPORT_MIME, "text/*", "*/*")) },
        style = VButtonStyle.Secondary,
        icon = VIcons.Import,
    )
    VButton(
        "Export",
        {
            val text = actions.onExportText(state.profile)
            if (text != null) {
                pending = text
                create.launch(InputProfileTransfer.fileName(state.profile))
            }
        },
        style = VButtonStyle.Secondary,
        icon = VIcons.Export,
    )
}

private const val EXPORT_MIME = "application/json"

/**
 * The shorter edge of the session's own window, in dp.
 *
 * The session route is orientation-locked to landscape, so whichever of the two
 * configuration numbers is smaller is the height the overlay will have — and a
 * control's radius is a fraction of exactly that. Read from the configuration
 * rather than from the session, so the container sheet can quote the same
 * numbers with nothing running.
 */
@Composable
private fun sessionShortEdgeDp(): Dp {
    val configuration = LocalConfiguration.current
    return min(configuration.screenWidthDp, configuration.screenHeightDp).dp
}

@Composable
private fun sessionLongEdgeDp(): Dp {
    val configuration = LocalConfiguration.current
    return maxOf(configuration.screenWidthDp, configuration.screenHeightDp).dp
}
