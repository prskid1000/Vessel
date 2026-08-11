package app.vessel.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import app.vessel.core.DisplayGeometry
import app.vessel.data.InputProfileTransfer
import app.vessel.display.TouchOverlayPainter
import app.vessel.input.GamepadAction
import app.vessel.input.GamepadControl
import app.vessel.input.InputProfile
import app.vessel.input.Stick
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
 * One list of controls, and this is the note that says why.
 *
 * The screen used to show **one controller under two mental models**. The overlay
 * half was free-form — controls had positions, sizes and opacity, and you added
 * and removed them — and it answered "what is on my glass". The pad half was a
 * fixed table of twenty-four rows you could only bind, and it answered "what does
 * each control send". Nothing on the screen said that the glass d-pad *is* the row
 * called `D-pad up`, and reading top to bottom you met a d-pad in a picture, then
 * `Add a control`, then a second list containing d-pad rows you could not delete.
 * Taking the tabs out did not fix that; it moved the seam into the middle of one
 * scroll, where it became visible rather than gone.
 *
 * So: **every row is a control, and a control can be on the glass, on the pad, or
 * both.** That was already true of the data — a `TouchControl` carries
 * `pad`/`padStick` and borrows the pad table's binding — and this screen was the
 * last place still pretending otherwise. A row has a name, a thing it sends, a
 * toggle for whether it is drawn, and, when it is drawn, a place and a size. The
 * twenty-four are rows whose toggle happens to be off; `Add a control` makes a row
 * with it on. The only difference between the two kinds is that a pad row cannot
 * be deleted, because a profile missing `A` is not a profile — and the row says
 * so rather than offering a control that refuses.
 *
 * The order, top to bottom: the map, the selected control, every control, the
 * settings, the profile. **One layout, at every width.** There used to be a
 * two-column variant for the 560 dp session panel and a single column for the
 * 421 dp container sheet, which meant the same editor read two different ways
 * depending on where it was opened. `Vessel Input Mapping.dc.html` still shows
 * three tabs and is out of date by this commit.
 */

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
    /**
     * Which row is expanded, as its key.
     *
     * A control on the glass is its own `TouchControl.id`; one that is only on
     * the pad is [padRowKey], which no placed control can collide with — the
     * stock ids are words and a placed one is `c<base36>`.
     */
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
    /** Hand the whole screen over to placing controls. See [TouchArrange]. */
    val onArrange: () -> Unit = {},
    val onSelect: (String?) -> Unit = {},
    val onDismissNotice: () -> Unit = {},
)

// — the header -------------------------------------------------------------------

/**
 * The profile's name, and the five things you can do to a profile.
 *
 * **No picker.** The header used to hold a dropdown that chose the profile while
 * the list at the bottom of the screen chose it differently, so one screen had two
 * answers to the same question. The list selects; the header names what is
 * selected and acts on it — new, duplicate, import, export, delete.
 *
 * **It wraps rather than clips, and that is the whole of the layout.** Five icon
 * actions at [app.vessel.ui.theme.VMetrics.iconButton] plus their gaps are 212 dp;
 * with a 40 dp leading control and a name that has to stay readable, they do not
 * fit beside the title on the 387 dp the container sheet has inside its gutters.
 * A previous attempt put them in a fixed-width `Row` and the last two — import and
 * export — were simply not on screen. Below [HEADER_INLINE_WIDTH] they take a line
 * of their own, and the `FlowRow` means that even a width nobody anticipated wraps
 * instead of losing a button.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun InputEditorHeader(
    state: InputEditorState,
    actions: InputEditorActions,
    leading: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val inline = maxWidth >= HEADER_INLINE_WIDTH
        Column(verticalArrangement = Arrangement.spacedBy(Vessel.metrics.s6)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Vessel.metrics.s8),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                leading()
                Column(
                    Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(Vessel.metrics.s3),
                ) {
                    Text(
                        state.profile.name,
                        style = Vessel.type.subtitle,
                        color = Vessel.colors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        state.subtitle(),
                        style = Vessel.type.monoSmall,
                        color = Vessel.colors.textMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (inline) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(Vessel.metrics.s3),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ProfileActions(state, actions)
                    }
                }
            }
            if (!inline) {
                FlowRow(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(
                        Vessel.metrics.s3,
                        Alignment.End,
                    ),
                    verticalArrangement = Arrangement.spacedBy(Vessel.metrics.s3),
                ) {
                    ProfileActions(state, actions)
                }
            }
        }
    }
}

/**
 * The five, emitted bare so the same set can sit in a `Row` or wrap in a
 * `FlowRow`.
 *
 * **Delete is beside the name, not on a row of the list.** Putting it on each row
 * of an open picker was the other idea, and a destructive action a few pixels from
 * the row you meant to *switch to* is the wrong place for it. Out here it acts on
 * the profile named to its left, which is the one you are looking at.
 */
@Composable
private fun ProfileActions(state: InputEditorState, actions: InputEditorActions) {
    VIconAction(VIcons.Plus, "New profile", actions.onNewProfile)
    VIconAction(VIcons.Copy, "Duplicate this profile", { actions.onDuplicate(state.profile) })
    ProfileTransferButtons(state, actions, compact = true)
    // The built-in default is never deletable, and the control is absent rather
    // than disabled: a dead button asks to be pressed once.
    if (!state.profile.isBuiltInDefault) {
        VIconAction(
            VIcons.Trash,
            "Delete this profile",
            { actions.onDelete(state.profile) },
            style = VButtonStyle.Danger,
        )
    }
}

private fun InputEditorState.subtitle(): String {
    val name = containerName.ifBlank { "This container" }
    val size = guest?.let { "${it.width}×${it.height}" }
    return listOfNotNull("Input", name, size).joinToString(" · ")
}

/**
 * Below this the five actions take a line of their own.
 *
 * 420 dp, from the arithmetic in [InputEditorHeader]: the session panel is 560 dp
 * less 22 dp of card padding, so 538 dp keeps them inline; the container sheet is
 * 421 dp less 34 dp of gutters, so 387 dp wraps them.
 */
private val HEADER_INLINE_WIDTH = 420.dp

// — the screen -------------------------------------------------------------------

/**
 * The map, the selected control, every control, the settings, the profile — in
 * that order, in one `LazyColumn`.
 *
 * **One lazy list and nothing nested inside it.** A `LazyColumn` measured inside a
 * `verticalScroll` is given an infinite maximum height and throws, which this
 * feature hit on the device the first time the Touch tab was opened; so the
 * sections that are lists are `LazyListScope` extensions on this one and the
 * sections that are not are single items.
 */
@Composable
fun InputEditor(
    state: InputEditorState,
    actions: InputEditorActions,
    modifier: Modifier = Modifier,
) {
    val profile = state.profile

    /** Learn: a press on the pad opens that control's picker instead of its row. */
    var learn by remember { mutableStateOf(false) }

    /** The row and slot whose key is being chosen, or null while the list is showing. */
    var picking by remember { mutableStateOf<Picking?>(null) }

    /** A control tapped on the diagram, which the list scrolls to. */
    var pinned by remember { mutableStateOf<GamepadControl?>(null) }

    val entries = remember(profile) { controlEntries(profile) }

    // A physical press wins over a tap, always: the whole point of the indicator
    // is to answer "which row is the button under my thumb", and a stale tap
    // highlight sitting next to a live one would answer it wrongly.
    val highlighted = if (state.held.isNotEmpty()) state.held else setOfNotNull(pinned)

    // Learn, driven by the pad itself. `GamepadControl` already names every
    // physical control, so this is free — and it is the best answer to "which of
    // these rows is the button I am pressing".
    LaunchedEffect(learn, state.held, entries) {
        if (!learn) return@LaunchedEffect
        val control = state.held.firstOrNull() ?: return@LaunchedEffect
        picking = entries.pickingFor(control)
    }

    // **"A press on the diagram still finds its row" has to be true.** The
    // settings say that in as many words, and a tint on a row nobody can see is
    // indistinguishable from doing nothing — which is exactly how it was
    // reported. The index is read off the entry list rather than recomputed by a
    // parallel walk, so the scroll cannot drift from what was emitted.
    val listState = rememberLazyListState()
    LaunchedEffect(pinned, entries) {
        val control = pinned ?: return@LaunchedEffect
        val index = entries.indexOfFirst { it is ControlEntry.Row && control in it.speaksFor }
        if (index >= 0) listState.animateScrollToItem(index + LEADING_ITEMS)
    }

    // A tap that opens the picker is not also a tap that scrolls: the picker
    // replaces the list, so the highlight would be waiting behind it for a
    // question that has already been answered.
    val pin: (GamepadControl) -> Unit = { control ->
        val row = entries.rowFor(control)
        if (learn) {
            picking = entries.pickingFor(control)
            pinned = null
        } else {
            pinned = control
            actions.onSelect(row?.key)
        }
    }

    val selected = entries.rowByKey(state.selected)
    val target = picking?.let { pick ->
        entries.rowByKey(pick.row)?.let { row ->
            row.slots.firstOrNull { it.name == pick.slot }?.let { row to it }
        }
    }

    Column(modifier.fillMaxSize()) {
        state.notice?.let { notice ->
            Column(Modifier.padding(top = Vessel.metrics.s8)) {
                VCaution(notice)
                VButton("Dismiss", actions.onDismissNotice, style = VButtonStyle.Ghost)
            }
        }

        if (target != null) {
            val (row, slot) = target
            KeyPicker(
                title = "${row.name} · ${slot.name}",
                current = slot.action,
                onClose = { picking = null },
                onChoose = { action ->
                    actions.onProfile(profile.rebound(row, slot, action))
                    picking = null
                },
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
            return@Column
        }

        val shortEdge = sessionShortEdgeDp()
        LazyColumn(
            Modifier.fillMaxWidth().weight(1f),
            state = listState,
            contentPadding = LIST_PADDING,
        ) {
            item(key = "map") {
                TouchPreviewCard(state, actions, Modifier.padding(top = Vessel.metrics.s8))
            }
            item(key = "selected") {
                SelectedControl(
                    state = state,
                    actions = actions,
                    row = selected,
                    onPick = { slot -> picking = Picking(selected?.key.orEmpty(), slot) },
                    modifier = Modifier.padding(vertical = Vessel.metrics.s11),
                )
            }
            controlItems(
                entries = entries,
                state = state,
                actions = actions,
                lit = highlighted,
                shortEdge = shortEdge,
            )
            item(key = "settings") {
                InputSettings(
                    state = state,
                    actions = actions,
                    learn = learn,
                    onLearn = { learn = it },
                    lit = highlighted,
                    onPin = pin,
                    modifier = Modifier.padding(top = Vessel.metrics.s22),
                )
            }
            item(key = "profiles") {
                ProfilesSection(state, actions, Modifier.padding(top = Vessel.metrics.s22))
            }
        }
    }
}

/**
 * How many items the list emits before the control entries begin.
 *
 * The map and the selected control, both unconditional. It is a constant rather
 * than a count because the scroll-to-row arithmetic has to agree with the emitter
 * exactly, and a number written beside the two `item` calls it counts is the
 * cheapest way to notice when a third is added.
 */
private const val LEADING_ITEMS = 2

private val LIST_PADDING = PaddingValues(bottom = 22.dp)

// — the map ----------------------------------------------------------------------

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
    actions: InputEditorActions,
    modifier: Modifier = Modifier,
) {
    val short = sessionShortEdgeDp()
    val long = sessionLongEdgeDp()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Vessel.metrics.s8)) {
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
        )
        VButton(
            "Arrange the overlay",
            actions.onArrange,
            style = VButtonStyle.Secondary,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * The overlay, at the shape it will have, with every control where it will be.
 *
 * **A picture, and only a picture.** It used to be draggable, and dragging a
 * 15 dp button around a 232 dp thumbnail is not placing a control — it is
 * guessing where one will land, at a seventh of the scale it will land at. Its
 * one gesture now is a tap, which selects the control whose row you want.
 */
@Composable
private fun TouchOverlayPreview(
    layout: TouchLayout,
    aspect: Float,
    selected: String?,
    onSelect: (String?) -> Unit,
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
            .onSizeChanged { size = it.width.toFloat() to it.height.toFloat() },
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
                    // A d-pad is a cross, and a cross is not a `Shape` any of the
                    // theme's rounded rectangles can make. Everything else clips
                    // and rings itself the ordinary way; the cross draws itself
                    // below, and takes neither.
                    .then(
                        if (control.kind == TouchKind.DPAD) {
                            Modifier
                        } else {
                            Modifier
                                .clip(if (control.round) Vessel.metrics.shapePill else Vessel.metrics.shapeMd)
                                .background(Vessel.colors.surfaceFloating)
                                .border(
                                    if (isSelected) 2.dp else Vessel.metrics.hairline,
                                    if (isSelected) Vessel.colors.accent else Vessel.colors.accent700,
                                    if (control.round) Vessel.metrics.shapePill else Vessel.metrics.shapeMd,
                                )
                        },
                    )
                    // **A tap selects, and no longer opens the arranger.** This
                    // is the only picture of the controller here, so a tap has to
                    // do what a tap on a row does: choose the control you want.
                    // Arranging is a button of its own, because it is a different
                    // intent and deserved more than "you touched the card".
                    .clickable(onClickLabel = control.title) { onSelect(control.id) },
                contentAlignment = Alignment.Center,
            ) {
                if (control.kind == TouchKind.DPAD) {
                    DpadCross(
                        selected = isSelected,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    // **Sized by the overlay's own rule, not by a type token.**
                    // The painter shrinks a label until it fits the control, so
                    // `SELECT` is legible on a small button over a session. A
                    // fixed style here is roughly a seventh of that scale and
                    // clipped the same word to `SE`, which made the preview a
                    // picture of a control that does not exist. The floor goes
                    // with it, for the reason in `labelSize`.
                    Text(
                        control.face,
                        style = Vessel.type.overline.copy(
                            fontSize = with(density) {
                                TouchOverlayPainter
                                    .labelSize(radius, control.face, density.density, floorDp = 0f)
                                    .toSp()
                            },
                            lineHeight = TextUnit.Unspecified,
                        ),
                        color = Vessel.colors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Clip,
                    )
                }
            }
        }
        // **The affordance sits on the picture rather than beside it.** A button
        // under the card would be a second thing to explain and a second thing to
        // aim at, when the card is already the target. Translucent, and over the
        // middle where the default layout keeps nothing, so it names the gesture
        // without hiding the arrangement it is offering to change.
        Text(
            "ARRANGE THE OVERLAY",
            style = Vessel.type.overline,
            color = Vessel.colors.textPrimary.copy(alpha = ARRANGE_HINT_INK),
            maxLines = 1,
            modifier = Modifier
                .align(Alignment.Center)
                .clip(Vessel.metrics.shapeTag)
                .background(Vessel.colors.neutral900.copy(alpha = ARRANGE_HINT_GROUND))
                .padding(horizontal = Vessel.metrics.s8, vertical = Vessel.metrics.s3),
        )
    }
}

/** Legible over an overlay, and never mistaken for one of its controls. */
private const val ARRANGE_HINT_INK = 0.75f
private const val ARRANGE_HINT_GROUND = 0.55f

/**
 * The plus a d-pad is, in the preview.
 *
 * The same twelve corners `TouchOverlayPainter` draws over a running session,
 * because the preview's whole job is to be a picture of what the overlay will
 * look like — and the two disagreeing about the shape of a control would make it
 * a picture of something else.
 */
@Composable
private fun DpadCross(selected: Boolean, modifier: Modifier = Modifier) {
    val fill = Vessel.colors.surfaceFloating
    val ring = if (selected) Vessel.colors.accent else Vessel.colors.accent700
    val width = if (selected) 2.dp else Vessel.metrics.hairline
    Canvas(modifier) {
        val r = size.minDimension / 2f
        val cx = size.width / 2f
        val cy = size.height / 2f
        val a = r * TouchOverlayPainter.ARM
        val path = Path().apply {
            moveTo(cx - a, cy - r)
            lineTo(cx + a, cy - r)
            lineTo(cx + a, cy - a)
            lineTo(cx + r, cy - a)
            lineTo(cx + r, cy + a)
            lineTo(cx + a, cy + a)
            lineTo(cx + a, cy + r)
            lineTo(cx - a, cy + r)
            lineTo(cx - a, cy + a)
            lineTo(cx - r, cy + a)
            lineTo(cx - r, cy - a)
            lineTo(cx - a, cy - a)
            close()
        }
        drawPath(path, fill)
        drawPath(path, ring, style = Stroke(width = width.toPx()))
    }
}

// — the one list -----------------------------------------------------------------

/**
 * One line of the one list.
 *
 * A sealed list of what the controls section can emit, built once and then walked
 * twice: by the emitter, and by the scroll that has to find a control's index. Two
 * walks over the same list cannot disagree; two pieces of arithmetic over the same
 * rules did, and that is the bug this shape retires.
 */
private sealed interface ControlEntry {
    val key: String

    /** A group heading, and at most one action that applies to the group. */
    data class Heading(
        override val key: String,
        val title: String,
        val reset: Reset? = null,
    ) : ControlEntry

    /** A control: on the glass, on the pad, or both. */
    data class Row(
        override val key: String,
        val name: String,
        /** The overlay control, when this row is drawn. Null for a pad-only row. */
        val glass: TouchControl?,
        /** The pad control this row is, when it is one of the twenty-four. */
        val pad: GamepadControl?,
        val slots: List<Slot>,
        /** What it sends, as one chip. */
        val sends: String,
        val bound: Boolean,
        val round: Boolean,
        /** Every pad control this row answers for, which is what a press looks up. */
        val speaksFor: Set<GamepadControl>,
    ) : ControlEntry {
        /** A pad row cannot be deleted; a control the user placed is only ever theirs. */
        val deletable: Boolean get() = glass != null && speaksFor.isEmpty()
    }

    /** The sentence that stands in for a stick's four rows when they cannot fire. */
    data class Note(override val key: String, val text: String) : ControlEntry

    /** `Add a control`, at the end of the glass group. */
    data object Add : ControlEntry {
        override val key: String get() = "add"
    }

    /** The stock layouts, offered in place of an empty glass group. */
    data object Stock : ControlEntry {
        override val key: String get() = "stock"
    }
}

/** The two ways back: the shipped arrangement, and the shipped bindings. */
private enum class Reset { Layout, All }

/**
 * One binding a row owns.
 *
 * [pad] is what makes the glass and the pad one table: a slot naming a pad control
 * writes the *pad table*, so rebinding the on-screen `A` and rebinding the
 * physical `A` are the same edit. A slot with no pad control holds its binding on
 * the control itself, which is every slot of a layout built by hand.
 */
private data class Slot(val name: String, val action: GamepadAction, val pad: GamepadControl?)

/** Which row and which of its slots the key picker is open for. */
private data class Picking(val row: String, val slot: String)

private const val SLOT_SENDS = "Sends"

/**
 * Every control the profile has, once each.
 *
 * **Once each is the point.** A control on the glass and the pad row for the same
 * control are one thing seen twice, and listing both is the duplication that made
 * the screen unreadable — so the glass rows come first and the pad group is
 * whatever [TouchControl.padControls] has not already spoken for. On the built-in
 * profile that leaves the pad group empty, because a whole controller is on the
 * glass; on a hand-built layout it leaves all twenty-four, because none of them
 * is.
 */
private fun controlEntries(profile: InputProfile): List<ControlEntry> {
    val layout = profile.overlay
    val covered = layout.controls.flatMap { it.padControls }.toSet()
    val rest = PAD_READING_ORDER.filterNot { it in covered }
    val out = mutableListOf<ControlEntry>()

    out += ControlEntry.Heading(
        key = "h-all",
        title = "${layout.controls.size + rest.size} CONTROLS · " +
            "${profile.boundCount} OF ${GamepadControl.entries.size} BOUND",
        reset = Reset.All,
    )

    out += ControlEntry.Heading(
        key = "h-glass",
        title = if (layout.isEmpty) {
            "NOTHING ON THE GLASS"
        } else {
            "ON THE GLASS · ${layout.controls.size}"
        },
        // The *stored* layout, not the resolved one: resolution fills a
        // pad-linked control's action in from the binding table, so the resolved
        // form never equals the stock constant and the way back would be offered
        // on an arrangement nobody has touched.
        reset = Reset.Layout.takeIf { !layout.isEmpty && profile.touch != TouchLayouts.Gamepad },
    )
    if (layout.isEmpty) {
        out += ControlEntry.Stock
    } else {
        layout.controls.forEach { out += glassRow(it) }
    }
    out += ControlEntry.Add

    if (rest.isNotEmpty()) {
        out += ControlEntry.Heading("h-pad", "ON THE PAD ONLY · ${rest.size}")
        val noted = mutableSetOf<Stick>()
        rest.forEach { control ->
            val stick = Stick.entries.firstOrNull { control in it.halfAxes }
            val role = stick?.let { profile.pad.roleOf(it) }
            // **A row that cannot fire is worse than a missing one.** A stick
            // sending the pointer, an axis or nothing has no half-axes to bind, so
            // its four rows are one sentence saying how to get them back.
            if (stick != null && role != StickRole.Keys) {
                if (noted.add(stick)) {
                    out += ControlEntry.Note("n-${stick.name}", stickNote(stick, role!!))
                }
                return@forEach
            }
            out += padRow(profile, control)
        }
    }
    return out
}

private fun glassRow(control: TouchControl) = ControlEntry.Row(
    key = control.id,
    name = control.title,
    glass = control,
    pad = control.pad,
    slots = control.slotNames().map { Slot(it, control.actionFor(it), control.padFor(it)) },
    sends = control.bindingLabel,
    bound = control.bindingLabel != X11KeyCatalog.UNBOUND,
    round = control.round,
    speaksFor = control.padControls,
)

private fun padRow(profile: InputProfile, control: GamepadControl): ControlEntry.Row {
    val action = profile.pad.bindings[control] ?: GamepadAction.None
    return ControlEntry.Row(
        key = padRowKey(control),
        name = control.rowLabel(),
        glass = null,
        pad = control,
        slots = listOf(Slot(SLOT_SENDS, action, control)),
        sends = X11KeyCatalog.label(action),
        bound = action != GamepadAction.None,
        round = control !in DPAD_CONTROLS,
        speaksFor = setOf(control),
    )
}

/** The selection key of a control that is only on the pad. See [InputEditorState.selected]. */
private fun padRowKey(control: GamepadControl): String = "pad:${control.name}"

private val DPAD_CONTROLS = setOf(
    GamepadControl.DPAD_UP,
    GamepadControl.DPAD_DOWN,
    GamepadControl.DPAD_LEFT,
    GamepadControl.DPAD_RIGHT,
)

private fun stickNote(stick: Stick, role: StickRole): String {
    val which = if (stick == Stick.LEFT) "The left stick" else "The right stick"
    return when (role) {
        StickRole.Look -> "$which moves the mouse. Set it to Keys below to bind its four directions."
        StickRole.Pad -> "$which is a stick in the guest. Set it to Keys below to bind its four " +
            "directions instead."

        else -> "$which sends nothing. Set it to Keys below to bind its four directions."
    }
}

private fun List<ControlEntry>.rowByKey(key: String?): ControlEntry.Row? =
    firstOrNull { it is ControlEntry.Row && it.key == key } as ControlEntry.Row?

private fun List<ControlEntry>.rowFor(control: GamepadControl): ControlEntry.Row? =
    firstOrNull { it is ControlEntry.Row && control in it.speaksFor } as ControlEntry.Row?

/** The row and the one of its slots that this control writes, for Learn. */
private fun List<ControlEntry>.pickingFor(control: GamepadControl): Picking? {
    val row = rowFor(control) ?: return null
    val slot = row.slots.firstOrNull { it.pad == control } ?: row.slots.firstOrNull() ?: return null
    return Picking(row.key, slot.name)
}

/**
 * The profile with one slot rebound.
 *
 * **A pad-linked slot rebinds the pad table, not the control.** The glass `A`
 * button and the physical `A` button are the same control seen twice; editing one
 * to disagree with the other would undo the whole reason the link exists. The
 * write goes to the *stored* layout rather than the resolved one, so a pad link is
 * never flattened into a copy of the binding it was borrowing.
 */
private fun InputProfile.rebound(
    row: ControlEntry.Row,
    slot: Slot,
    action: GamepadAction,
): InputProfile {
    if (slot.pad != null) return withBinding(slot.pad, action)
    val stored = touch.byId(row.key) ?: return this
    return copy(touch = touch.with(stored.withAction(slot.name, action)))
}

private fun LazyListScope.controlItems(
    entries: List<ControlEntry>,
    state: InputEditorState,
    actions: InputEditorActions,
    lit: Set<GamepadControl>,
    shortEdge: Dp,
) {
    val profile = state.profile
    items(entries.size, key = { entries[it].key }) { index ->
        when (val entry = entries[index]) {
            is ControlEntry.Heading -> ControlHeading(entry, profile, actions)

            is ControlEntry.Row -> ControlRowView(
                row = entry,
                selected = entry.key == state.selected,
                lit = entry.speaksFor.any { it in lit },
                shortEdge = shortEdge,
                onClick = { actions.onSelect(entry.key) },
                onGlass = glassSwitch(profile, entry, actions),
            )

            is ControlEntry.Note -> InputNote(entry.text)

            ControlEntry.Add -> TouchAddRow(state, actions)

            ControlEntry.Stock -> StockLayoutOffer(state, actions)
        }
    }
}

@Composable
private fun ControlHeading(
    heading: ControlEntry.Heading,
    profile: InputProfile,
    actions: InputEditorActions,
) {
    Row(
        Modifier.fillMaxWidth().padding(top = Vessel.metrics.s11, bottom = Vessel.metrics.s3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            heading.title,
            style = Vessel.type.overline,
            color = Vessel.colors.textMuted,
            modifier = Modifier.weight(1f),
        )
        when (heading.reset) {
            // **The way back from a layout dragged into a mess.** Placing controls
            // is a fiddly one-finger operation with no undo, and without this the
            // only route back to the shipped arrangement is deleting every control
            // to reach the stock offer. It reads `Reset layout` rather than `Reset
            // positions` because it restores sizes and membership too.
            Reset.Layout -> VButton(
                "Reset layout",
                { actions.onProfile(profile.copy(touch = TouchLayouts.Gamepad)) },
                style = VButtonStyle.Ghost,
            )

            Reset.All -> VButton(
                "Reset all",
                { actions.onProfile(profile.resetToDefaults()) },
                style = VButtonStyle.Ghost,
            )

            null -> Unit
        }
    }
}

/**
 * What the row's toggle does, or null for a row that has no glass toggle to offer.
 *
 * A pad row goes on the glass as a link rather than as a copy, and comes off it by
 * being removed from the layout — it is still one of the twenty-four, so nothing
 * is lost. **A control the user placed has no toggle**, because the model has
 * nowhere to keep a control that is neither drawn nor one of the twenty-four:
 * taking it off the glass would be deleting it, and the row already offers Delete
 * by that name.
 */
private fun glassSwitch(
    profile: InputProfile,
    row: ControlEntry.Row,
    actions: InputEditorActions,
): ((Boolean) -> Unit)? {
    if (row.glass != null) {
        if (row.speaksFor.isEmpty()) return null
        return { on ->
            if (!on) {
                actions.onProfile(profile.copy(touch = profile.touch.without(row.glass.id)))
                actions.onSelect(row.pad?.let { padRowKey(it) })
            }
        }
    }
    val control = row.pad ?: return null
    return { on ->
        if (on) {
            val placed = TouchEdit.placedPad(profile.touch, control)
            actions.onProfile(profile.copy(touch = profile.touch.with(placed)))
            actions.onSelect(placed.id)
        }
    }
}

/**
 * A control, in one row: its shape, its name, where it is, and what it sends.
 *
 * The accent bar on the left is the live-press indicator, and it is on every row
 * for the same reason every row is here — a glass button and a pad row are one
 * control, so a press has to light whichever of them the profile is using.
 */
@Composable
private fun ControlRowView(
    row: ControlEntry.Row,
    selected: Boolean,
    lit: Boolean,
    shortEdge: Dp,
    onClick: () -> Unit,
    onGlass: ((Boolean) -> Unit)?,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(Vessel.metrics.shapeMd)
            .background(
                when {
                    selected -> Vessel.colors.accentHover
                    lit -> Vessel.colors.accentGhostHover
                    else -> Color.Transparent
                },
            )
            .clickable(onClickLabel = row.name, onClick = onClick)
            .heightIn(min = Vessel.metrics.touchTarget)
            .padding(horizontal = Vessel.metrics.s6),
        horizontalArrangement = Arrangement.spacedBy(Vessel.metrics.s8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(width = 2.dp, height = 20.dp)
                .clip(Vessel.metrics.shapePill)
                .background(if (lit) Vessel.colors.accent else Color.Transparent),
        )
        // The dot is the control's own shape at 20 dp: round for a stick or a
        // button, square for a d-pad. A list of identical dots would say nothing
        // the name does not already say in words — the shape is the one thing it
        // can add.
        Box(
            Modifier
                .size(20.dp)
                .clip(if (row.round) Vessel.metrics.shapePill else Vessel.metrics.shapeSm)
                .border(
                    Vessel.metrics.hairline,
                    if (selected) Vessel.colors.accent else Vessel.colors.border,
                    if (row.round) Vessel.metrics.shapePill else Vessel.metrics.shapeSm,
                ),
        )
        Column(Modifier.weight(1f)) {
            Text(
                row.name,
                style = Vessel.type.body,
                color = Vessel.colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                row.glass?.metrics(shortEdge) ?: "not on the glass",
                style = Vessel.type.monoSmall,
                color = Vessel.colors.textMuted,
                maxLines = 1,
            )
        }
        BindingChip(row.sends, bound = row.bound)
        if (onGlass != null) {
            VToggle(checked = row.glass != null, onCheckedChange = onGlass)
        }
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
 * What an empty overlay offers instead of an empty canvas.
 *
 * Plan §4.5's stock layouts, and this is the moment they are for: a profile whose
 * overlay has never been touched, where the alternative is a rectangle with
 * nothing in it and a user who has to guess that Add is the way in.
 */
@Composable
private fun StockLayoutOffer(state: InputEditorState, actions: InputEditorActions) {
    Column(verticalArrangement = Arrangement.spacedBy(Vessel.metrics.s6)) {
        Text(
            "This profile draws nothing on the screen. Start from one of these, add controls " +
                "one at a time, or put a control from the list below on the glass.",
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

// — the selected control ---------------------------------------------------------

/**
 * The row above, expanded: name, what it sends, whether it is drawn, and — only
 * when it is drawn — where and how big.
 *
 * All four for a control that is both, the first two for one that is only on the
 * pad. Nothing here is a special case: the same fields in the same order for a
 * control the user placed and for one of the twenty-four, and the only difference
 * is that one of them can be deleted.
 */
@Composable
private fun SelectedControl(
    state: InputEditorState,
    actions: InputEditorActions,
    row: ControlEntry.Row?,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val profile = state.profile
    if (row == null) {
        InputNote(
            "Tap a control — on the map, or in the list below — to name it, change what it " +
                "sends, or take it off the glass.",
            modifier = modifier,
        )
        return
    }

    val glass = row.glass
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Vessel.metrics.s8)) {
        VRule(verticalMargin = Vessel.metrics.s3)
        Text(row.name, style = Vessel.type.cardTitle)

        // **The field the redesign added.** A placed control used to be anonymous
        // until a binding gave it a word, so what it was called on the glass was
        // whatever key it happened to send. The name is a value the user owns; the
        // placeholder is what it falls back to when they clear it.
        VLabeledField(
            label = "Name",
            help = if (glass == null) {
                "Put it on the glass to give it a name of your own. Off the glass there is " +
                    "nothing to draw a name on."
            } else {
                null
            },
        ) {
            VTextField(
                value = glass?.label.orEmpty(),
                onValueChange = { name ->
                    glass?.let {
                        actions.onProfile(profile.withControl(it.id) { c -> c.copy(label = name) })
                    }
                },
                enabled = glass != null,
                placeholder = row.name,
            )
        }

        row.slots.forEach { slot ->
            VLabeledField(label = slot.name) {
                VButton(
                    X11KeyCatalog.label(slot.action),
                    { onPick(slot.name) },
                    style = VButtonStyle.Secondary,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        if (row.slots.isEmpty() && glass != null) {
            InputNote(
                when (glass.role) {
                    StickRole.Pad -> "It is a stick the guest reads as a stick, so there is no " +
                        "key to bind. Change what the stick sends in the settings below."

                    StickRole.Look -> "A look pad moves the mouse. There is no analogue axis a " +
                        "Windows game can read, so this is the only thing it can be."

                    else -> "It sends nothing. Change what the stick sends in the settings below."
                },
            )
        }

        val toggle = glassSwitch(profile, row, actions)
        if (toggle != null) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Vessel.metrics.s8),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                VToggle(checked = glass != null, onCheckedChange = toggle)
                Text(
                    "On the glass",
                    style = Vessel.type.body,
                    color = Vessel.colors.textPrimary,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        if (glass != null) {
            val short = sessionShortEdgeDp()
            InputSlider(
                label = "Size",
                value = glass.size,
                min = TouchControls.MIN_SIZE,
                max = TouchControls.MAX_SIZE,
                readout = "${(glass.size * 2f * short.value).roundToInt()} dp",
                help = null,
                onValue = { next ->
                    actions.onProfile(profile.withControl(glass.id) { it.copy(size = next) })
                },
            )
            Text(
                "${(glass.cx * 100).roundToInt()}% from the left · " +
                    "${(glass.cy * 100).roundToInt()}% down",
                style = Vessel.type.monoSmall,
                color = Vessel.colors.textMuted,
            )
        }

        if (row.deletable && glass != null) {
            VButton(
                "Delete",
                {
                    actions.onProfile(profile.copy(touch = profile.touch.without(glass.id)))
                    actions.onSelect(null)
                },
                style = VButtonStyle.Danger,
                icon = VIcons.Trash,
            )
        } else {
            // The row says it rather than offering a control that refuses. The
            // twenty-four are what a controller *has*, and a profile missing `A`
            // is not a profile.
            InputNote(
                "This is one of the controller's own controls, so it cannot be deleted. " +
                    "Take it off the glass instead.",
            )
        }
    }
}

/**
 * Change one control in the **stored** layout.
 *
 * Not the resolved one: resolution fills a pad-linked control's action in from the
 * binding table, and writing that back would freeze a copy of the binding into the
 * control — which is the same as breaking the link, silently, at the next
 * rebinding.
 */
private fun InputProfile.withControl(id: String, edit: (TouchControl) -> TouchControl): InputProfile {
    val stored = touch.byId(id) ?: return this
    return copy(touch = touch.with(edit(stored)))
}

/**
 * Which binding fields this control has.
 *
 * A stick and a d-pad get four rather than one, because they are four keys — and a
 * stick that is not sending keys gets none at all, because a pointer velocity and
 * a guest axis are not keys and pretending otherwise is what the [StickRole]
 * distinction exists to prevent.
 */
private fun TouchControl.slotNames(): List<String> = when {
    kind == TouchKind.BUTTON -> listOf(SLOT_SENDS)
    kind == TouchKind.STICK && role != StickRole.Keys -> emptyList()
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

/**
 * The control with one of its own bindings changed.
 *
 * **It no longer renames the control.** It used to write
 * `label = X11KeyCatalog.label(next)` alongside, which is exactly the fault the
 * redesign names: a control whose name came from its binding is a control with no
 * name of its own.
 */
private fun TouchControl.withAction(slot: String, next: GamepadAction): TouchControl = when (slot) {
    "Up" -> copy(up = next)
    "Down" -> copy(down = next)
    "Left" -> copy(left = next)
    "Right" -> copy(right = next)
    else -> copy(action = next)
}

// — adding -----------------------------------------------------------------------

/**
 * Add a control, having first asked what kind.
 *
 * A stick, a d-pad and a look pad are one each — the translator has exactly one
 * left stick, one hat and one right stick to give them — so a second is offered
 * as unavailable rather than hidden. Hiding it would make the limit look like a
 * missing feature.
 *
 * What it makes is **a full control**: a name, a binding, a place on the glass and
 * everything a pad row has except the one thing a pad row lacks, which is that
 * this one can be deleted.
 */
@Composable
private fun TouchAddRow(state: InputEditorState, actions: InputEditorActions) {
    val profile = state.profile
    val layout = profile.overlay
    var adding by remember { mutableStateOf(false) }
    if (!adding) {
        VButton(
            "Add a control",
            { adding = true },
            style = VButtonStyle.Secondary,
            icon = VIcons.Plus,
            modifier = Modifier.fillMaxWidth().padding(vertical = Vessel.metrics.s6),
        )
        return
    }
    Column(
        Modifier.padding(vertical = Vessel.metrics.s6),
        verticalArrangement = Arrangement.spacedBy(Vessel.metrics.s6),
    ) {
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
                        val control = TouchEdit.placed(profile.touch, kind.kind, kind.role)
                        actions.onProfile(profile.copy(touch = profile.touch.with(control)))
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
                        if (taken) "already on the glass" else kind.note,
                        style = Vessel.type.bodySmall,
                        color = Vessel.colors.textMuted,
                    )
                }
            }
        }
        InputNote(
            "It lands in the middle of the screen, already selected and already named. Give " +
                "it a name and a key above, then drag it where your thumb actually is.",
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

// — the settings -----------------------------------------------------------------

/**
 * Whether the overlay is drawn, how solid, and everything about the pad in your
 * hands.
 *
 * **The Play / Edit layout toggle used to be here, and it is gone.** Editing in
 * place meant editing *under this panel*: the panel covers the left of the screen,
 * so the d-pad, the left stick and L3 sat beneath it and could not be dragged at
 * all. The full-screen arrange surface exists precisely to give the whole screen
 * to placing controls, and it is one tap away on the map above.
 */
@Composable
private fun InputSettings(
    state: InputEditorState,
    actions: InputEditorActions,
    learn: Boolean,
    onLearn: (Boolean) -> Unit,
    lit: Set<GamepadControl>,
    onPin: (GamepadControl) -> Unit,
    modifier: Modifier = Modifier,
) {
    val profile = state.profile
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Vessel.metrics.s11)) {
        Text(
            "SETTINGS",
            style = Vessel.type.overline,
            color = Vessel.colors.textMuted,
        )
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
        // difference, and two dozen sliders to express it would be a worse editor
        // than one.
        val opacity = profile.touch.controls.firstOrNull()?.opacity
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
                    profile.copy(
                        touch = TouchLayout(profile.touch.controls.map { it.copy(opacity = next) }),
                    ),
                )
            },
        )

        VRule(verticalMargin = Vessel.metrics.s3)

        PadSettings(
            profile = profile,
            live = state.live,
            learn = learn,
            onLearn = onLearn,
            lit = lit,
            onPin = onPin,
            onProfile = actions.onProfile,
        )

        if (state.editing) {
            InputNote("The guest is not receiving input while you edit.")
        }
    }
}

// — the profile ------------------------------------------------------------------

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
private fun ProfilesSection(
    state: InputEditorState,
    actions: InputEditorActions,
    modifier: Modifier = Modifier,
) {
    // No scroll of its own: it is one item inside the screen's single list, and a
    // scroller nested in a scroller measures to nothing.
    Column(
        modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Vessel.metrics.s11),
    ) {
        Text("PROFILE", style = Vessel.type.overline, color = Vessel.colors.textMuted)

        if (state.missingProfile) {
            VCaution(
                "This container names a profile that has been deleted. It starts on the " +
                    "built-in default until something else is chosen — nothing was " +
                    "rewritten, so restoring the profile restores the choice.",
            )
        }

        // Rename is the current profile's name field rather than a button on every
        // row: renaming is the one profile action that is a *value*, and a field is
        // what this product uses for a value everywhere else.
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
                    "${profile.touch.controls.size} on the glass" +
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
private fun ProfileTransferButtons(
    state: InputEditorState,
    actions: InputEditorActions,
    /**
     * Icons rather than labelled buttons, for the header.
     *
     * The launchers have to live wherever the buttons do — `rememberLauncher…`
     * is composition-scoped — so this is one composable in two shapes rather
     * than two copies of the SAF plumbing.
     */
    compact: Boolean = false,
) {
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

    val onImport = { open.launch(arrayOf(EXPORT_MIME, "text/*", "*/*")); Unit }
    val onExport = {
        val text = actions.onExportText(state.profile)
        if (text != null) {
            pending = text
            create.launch(InputProfileTransfer.fileName(state.profile))
        }
    }

    if (compact) {
        VIconAction(VIcons.Import, "Import a profile", onImport)
        VIconAction(VIcons.Export, "Export this profile", onExport)
        return
    }

    VButton("Import", onImport, style = VButtonStyle.Secondary, icon = VIcons.Import)
    VButton("Export", onExport, style = VButtonStyle.Secondary, icon = VIcons.Export)
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
