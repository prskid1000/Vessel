package app.vessel.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.vessel.display.TouchOverlayPainter
import app.vessel.input.GamepadAction
import app.vessel.input.GamepadConfig
import app.vessel.input.GamepadControl
import app.vessel.input.GamepadProfile
import app.vessel.input.InputProfile
import app.vessel.input.KeyChoice
import app.vessel.input.Stick
import app.vessel.input.StickRole
import app.vessel.input.X11KeyCatalog
import app.vessel.input.X11KeyMap
import app.vessel.ui.components.VButton
import app.vessel.ui.components.VButtonStyle
import app.vessel.ui.components.VIconAction
import app.vessel.ui.components.VIcons
import app.vessel.ui.components.VTextField
import app.vessel.ui.components.VToggle
import app.vessel.ui.theme.Vessel
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The Pad tab: a picture of a controller, the two numbers that tune a stick, and
 * a table of twenty-four rows.
 *
 * **Nothing on it implies a gamepad reaches the guest.** Vessel ships no XInput:
 * the only channel into a Windows program is the X server, which carries keys and
 * a pointer. Every row therefore resolves to a keystroke, a mouse button or
 * pointer motion, and the word "pad" names the *device in your hands*, never what
 * the game sees. There is no rumble, no gyro and no "controller detected".
 *
 * It is laid out two ways from one set of pieces. Over a running session the
 * panel is 560 dp and the settings sit beside the table, which is what keeps the
 * diagram — and therefore Learn — on screen while a key is being chosen. On the
 * container sheet the same content is 421 dp and reads down one column, because
 * two 210 dp columns in 421 dp is two columns of nothing.
 */
@Composable
internal fun PadTab(
    profile: InputProfile,
    lit: Set<GamepadControl>,
    live: Boolean,
    wide: Boolean,
    onProfile: (InputProfile) -> Unit,
    modifier: Modifier = Modifier,
    /**
     * The overlay's own sections, emitted into this list rather than beside it.
     *
     * **Why they are passed in instead of drawn here.** This screen is one
     * LazyColumn and the binding rows are `LazyListScope` items; a Column of
     * touch controls placed above it would have to be a second scroller, and a
     * LazyColumn inside a scroll is the crash this feature already hit once.
     * Handing the caller the same scope is what lets "the map, then the
     * settings, then every control" be one list instead of three.
     */
    before: (LazyListScope.() -> Unit)? = null,
    after: (LazyListScope.() -> Unit)? = null,
) {
    /** The control whose key is being chosen, or null while the list is showing. */
    var picking by remember { mutableStateOf<GamepadControl?>(null) }

    /** Learn: a press on the pad opens that control's picker instead of the list. */
    var learn by remember { mutableStateOf(false) }

    /** The row lit by a tap on the diagram, when no physical control is down. */
    var pinned by remember { mutableStateOf<GamepadControl?>(null) }

    // A physical press wins over a tap, always: the whole point of the indicator
    // is to answer "which row is the button under my thumb", and a stale tap
    // highlight sitting next to a live one would answer it wrongly.
    val highlighted = if (lit.isNotEmpty()) lit else setOfNotNull(pinned)

    // Learn, driven by the pad itself. `gamepadControl` already names every
    // physical control, so this is free — and it is the best answer to "which of
    // these twenty-four rows is the button I am pressing".
    LaunchedEffect(learn, lit) {
        if (learn) lit.firstOrNull()?.let { picking = it }
    }

    val control = picking
    val choose: (GamepadAction) -> Unit = { action ->
        control?.let { onProfile(profile.withBinding(it, action)) }
        picking = null
    }

    // **"A press on the diagram still finds its row" has to be true.** The tab
    // says that in as many words, and until now it only tinted the row — which
    // for the d-pad, the triggers and everything below them is a row nobody can
    // see. Tinting something off-screen is indistinguishable from doing nothing,
    // and "clicking on these does nothing" is exactly how it was reported.
    //
    // One item earlier than the row itself, so its group heading comes with it
    // and the highlight lands in context rather than flush against the top edge.
    val listState = rememberLazyListState()
    val leadingItems = if (wide) 0 else 1
    LaunchedEffect(pinned, profile) {
        val row = pinned?.let { bindingRowIndex(profile, it) } ?: return@LaunchedEffect
        listState.animateScrollToItem((row + leadingItems - 1).coerceAtLeast(0))
    }

    // A tap that opens the picker is not also a tap that pins a row: the picker
    // replaces the list, so the highlight would be waiting behind it for a
    // question that has already been answered.
    val pin: (GamepadControl) -> Unit = {
        if (learn) {
            picking = it
            pinned = null
        } else {
            pinned = it
        }
    }

    if (wide) {
        Row(modifier.fillMaxWidth().fillMaxHeight()) {
            PadSettingsColumn(
                profile = profile,
                live = live,
                learn = learn,
                onLearn = { learn = it },
                lit = highlighted,
                onPin = pin,
                onProfile = onProfile,
            )
            Box(
                Modifier
                    .width(Vessel.metrics.hairline)
                    .fillMaxHeight()
                    .background(Vessel.colors.divider),
            )
            Box(Modifier.weight(1f).fillMaxHeight()) {
                if (control == null) {
                    LazyColumn(
                        Modifier.fillMaxHeight(),
                        state = listState,
                        contentPadding = LIST_PADDING,
                    ) {
                        before?.invoke(this)
                        bindingListItems(profile, highlighted, { pinned = it; picking = it }, onProfile)
                        after?.invoke(this)
                    }
                } else {
                    KeyPicker(
                        title = control.rowLabel(),
                        current = profile.pad.bindings[control] ?: GamepadAction.None,
                        onClose = { picking = null },
                        onChoose = choose,
                    )
                }
            }
        }
        return
    }

    if (control != null) {
        KeyPicker(
            title = control.rowLabel(),
            current = profile.pad.bindings[control] ?: GamepadAction.None,
            onClose = { picking = null },
            onChoose = choose,
            modifier = modifier,
        )
        return
    }

    LazyColumn(
        modifier.fillMaxWidth().fillMaxHeight(),
        state = listState,
        contentPadding = LIST_PADDING,
    ) {
        before?.invoke(this)
        padSettingsItems(
            profile = profile,
            live = live,
            learn = learn,
            onLearn = { learn = it },
            lit = highlighted,
            onPin = pin,
            onProfile = onProfile,
        )
        bindingListItems(profile, highlighted, { pinned = it; picking = it }, onProfile)
        after?.invoke(this)
    }
}

private val LIST_PADDING = PaddingValues(bottom = 22.dp)

// — the settings: the pad, the roles, the two numbers ----------------------------

@Composable
private fun PadSettingsColumn(
    profile: InputProfile,
    live: Boolean,
    learn: Boolean,
    onLearn: (Boolean) -> Unit,
    lit: Set<GamepadControl>,
    onPin: (GamepadControl) -> Unit,
    onProfile: (InputProfile) -> Unit,
) {
    Column(
        Modifier
            .width(PAD_COLUMN_WIDTH)
            .fillMaxHeight()
            .verticalScroll(rememberScrollState())
            .padding(end = Vessel.metrics.s11, bottom = Vessel.metrics.s11),
        verticalArrangement = Arrangement.spacedBy(Vessel.metrics.s11),
    ) {
        PadSettings(profile, live, learn, onLearn, lit, onPin, onProfile)
    }
}

/** The same settings as items, for the single column the sheet reads down. */
private fun LazyListScope.padSettingsItems(
    profile: InputProfile,
    live: Boolean,
    learn: Boolean,
    onLearn: (Boolean) -> Unit,
    lit: Set<GamepadControl>,
    onPin: (GamepadControl) -> Unit,
    onProfile: (InputProfile) -> Unit,
) {
    item(key = "pad-settings") {
        Column(verticalArrangement = Arrangement.spacedBy(Vessel.metrics.s11)) {
            PadSettings(profile, live, learn, onLearn, lit, onPin, onProfile)
        }
    }
}

@Composable
private fun PadSettings(
    profile: InputProfile,
    live: Boolean,
    learn: Boolean,
    onLearn: (Boolean) -> Unit,
    lit: Set<GamepadControl>,
    onPin: (GamepadControl) -> Unit,
    onProfile: (InputProfile) -> Unit,
) {
    // **Cold, the diagram cannot light up, and it says so.** A picture of a pad
    // that never responds otherwise reads as broken — which is the same reason it
    // is drawn faintly rather than at full strength.
    if (!live) {
        InputNote(
            "No session is running, so the diagram cannot light up. Every row still " +
                "binds, and a press on the diagram still finds its row.",
        )
    }

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Vessel.metrics.s6),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (learn) "Press a control to bind it" else "Press a control to find its row",
            style = Vessel.type.overline,
            color = Vessel.colors.textMuted,
            modifier = Modifier.weight(1f),
        )
        VToggle(checked = learn, onCheckedChange = onLearn)
        Text("Learn", style = Vessel.type.bodySmall, color = Vessel.colors.textLabel)
    }


    Row(horizontalArrangement = Arrangement.spacedBy(Vessel.metrics.s8)) {
        Stick.entries.forEach { stick ->
            Box(Modifier.weight(1f)) { StickRoleField(stick, profile, onProfile) }
        }
    }

    InputSlider(
        label = "Deadzone",
        value = profile.config.deadzone,
        min = GamepadConfig.MIN_DEADZONE,
        max = GamepadConfig.MAX_DEADZONE,
        readout = "%.2f".format(profile.config.deadzone),
        // The derived number is *shown* and not offered. Two sliders would let
        // the release sit above the engage, and the chatter that follows is
        // the exact failure two thresholds exist to prevent.
        help = "Below this the stick is at rest. It lets go at " +
            "%.2f".format(profile.config.releaseZone) +
            ", so a key does not chatter on the edge.",
        onValue = { onProfile(profile.withConfig(profile.config.copy(deadzone = it))) },
    )

    InputSlider(
        label = "Look speed",
        value = profile.config.lookSpeed,
        min = GamepadConfig.MIN_LOOK_SPEED,
        max = GamepadConfig.MAX_LOOK_SPEED,
        readout = "${(profile.config.lookSpeed / 50f).roundToInt() * 50} px/s",
        help = "Guest pixels a second at full deflection. A stick bound to look " +
            "moves the mouse: there is no analogue axis a Windows game can read.",
        onValue = { onProfile(profile.withConfig(profile.config.copy(lookSpeed = it))) },
    )
}

@Composable
private fun StickRoleField(stick: Stick, profile: InputProfile, onProfile: (InputProfile) -> Unit) {
    val role = profile.pad.roleOf(stick)
    val label = if (stick == Stick.LEFT) "Left stick sends" else "Right stick sends"
    Column(verticalArrangement = Arrangement.spacedBy(Vessel.metrics.s3)) {
        Text(label, style = Vessel.type.bodySmall, color = Vessel.colors.textLabel)
        Row(horizontalArrangement = Arrangement.spacedBy(Vessel.metrics.s3)) {
            ROLES.forEach { option ->
                val selected = option == role
                Box(
                    Modifier
                        .weight(1f)
                        .height(Vessel.metrics.controlHeight)
                        .clip(Vessel.metrics.shapeMd)
                        .background(
                            if (selected) Vessel.colors.accentHover else Color.Transparent,
                        )
                        .border(
                            Vessel.metrics.hairline,
                            if (selected) Vessel.colors.accent else Vessel.colors.border,
                            Vessel.metrics.shapeMd,
                        )
                        .clickable(onClickLabel = StickRole.nameOf(option)) {
                            onProfile(profile.withStick(stick, option))
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        StickRole.nameOf(option),
                        style = Vessel.type.monoSmall,
                        color = if (selected) Vessel.colors.accent else Vessel.colors.textLabel,
                    )
                }
            }
        }
    }
}

/**
 * `Pad` first, because it is now the right answer for most games and the others
 * are what you choose when a game cannot read a gamepad. It reads "Pad" rather
 * than "Gamepad" only because four options share one row.
 */
private val ROLES = listOf(StickRole.Pad, StickRole.Keys, StickRole.Look, StickRole.None)

/**
 * A picture of a pad, lit where a control is down.
 *
 * **Absolute placement against a fixed box, on purpose.** It is a diagram of a
 * physical object, not a layout: the shoulders are above the face buttons because
 * they are, and a flow that reordered them at some width would stop being a
 * picture of anything. It draws no pad that is not there either — nothing here
 * says a controller is connected, only which of its controls is currently held.
 */
@Composable
private fun PadDiagram(lit: Set<GamepadControl>, dim: Boolean, onPin: (GamepadControl) -> Unit) {
    // **Scaled to the panel, not pinned to 210 dp.** Every pin inside is placed
    // at an absolute offset in a fixed box, which is the right way to describe a
    // controller's shape and the wrong way to fill a column: on a 421 dp sheet
    // the whole pad sat in the left half with the face buttons bunched around
    // the middle. Scaling the box keeps one set of coordinates -- the shape is
    // still described once -- and Compose carries the transform into hit testing,
    // so a pin stays tappable where it is drawn.
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val scale = (maxWidth / PAD_DIAGRAM_WIDTH).coerceIn(1f, PAD_DIAGRAM_MAX_SCALE)
        Box(
            Modifier
                .align(Alignment.Center)
                .size(PAD_DIAGRAM_WIDTH * scale, PAD_DIAGRAM_HEIGHT * scale),
        ) {
    Box(
        Modifier
            .size(PAD_DIAGRAM_WIDTH, PAD_DIAGRAM_HEIGHT)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                transformOrigin = TransformOrigin(0f, 0f)
            }
            .alpha(if (dim) COLD_DIAGRAM_ALPHA else 1f),
    ) {
        PadDpad(lit, onPin)
        PAD_PINS.forEach { pin ->
            val on = pin.control in lit
            Box(
                Modifier
                    .offset(pin.x, pin.y)
                    .size(pin.width, pin.height)
                    .clip(pin.shape())
                    .background(if (on) Vessel.colors.accentPressed else Color.Transparent)
                    .border(Vessel.metrics.hairline, if (on) Vessel.colors.accent else Vessel.colors.border, pin.shape())
                    // Tappable as well as lit: a pad you have not plugged in yet
                    // still needs a way to reach a row, and with Learn on this is
                    // the same gesture as pressing the control itself.
                    .clickable(onClickLabel = pin.control.rowLabel()) { onPin(pin.control) }
                    .padding(1.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (pin.label.isNotEmpty()) {
                    Text(
                        pin.label,
                        style = Vessel.type.monoSmall,
                        color = if (on) Vessel.colors.accent100 else Vessel.colors.textMuted,
                        maxLines = 1,
                        softWrap = false,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
        }
    }
}

/**
 * The d-pad, as one cross that four directions share.
 *
 * **It was four detached 18 dp rectangles and it did not work.** Reported as
 * "clicking on these does nothing", and the geometry says why: the four arms
 * enclosed a 22x24 dp hole at the exact centre that belonged to no pin at all, so
 * the natural place to put a thumb was the one place with no handler behind it.
 * The arms themselves were 18 dp against a 44 dp house minimum, `clickable` adds
 * no slop of its own, and the whole diagram sits inside a scroll that claims any
 * tap with a few pixels of travel — so even the aimed hits were being eaten.
 *
 * The fix is the shape the rest of the product already uses. One square, one
 * cross drawn inside it at [TouchOverlayPainter.ARM] — the same proportion the
 * overlay and the layout preview draw — and the direction taken from *where in
 * the square* the tap landed, the way a real d-pad rocker resolves it. There is
 * no dead centre because there are no gaps, and the target is the whole 64 dp
 * cluster rather than four islands inside it.
 */
@Composable
private fun PadDpad(lit: Set<GamepadControl>, onPin: (GamepadControl) -> Unit) {
    val ring = Vessel.colors.border
    val ringLit = Vessel.colors.accent
    val fillLit = Vessel.colors.accentPressed
    val hairline = Vessel.metrics.hairline
    val down = DPAD_DIRECTIONS.filter { it.control in lit }
    Box(
        Modifier
            .offset(DPAD_X, DPAD_Y)
            .size(DPAD_SIZE)
            .pointerInput(Unit) {
                detectTapGestures { at ->
                    // Dominant axis from the centre, which is what the four
                    // quadrants of a rocker mean. A tap dead in the middle is
                    // vertical by the tie-break, and no thumb ever notices.
                    val dx = at.x - size.width / 2f
                    val dy = at.y - size.height / 2f
                    onPin(
                        if (abs(dx) > abs(dy)) {
                            if (dx < 0f) GamepadControl.DPAD_LEFT else GamepadControl.DPAD_RIGHT
                        } else {
                            if (dy < 0f) GamepadControl.DPAD_UP else GamepadControl.DPAD_DOWN
                        },
                    )
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val r = size.minDimension / 2f
            val cx = size.width / 2f
            val cy = size.height / 2f
            val a = r * TouchOverlayPainter.ARM
            val cross = Path().apply {
                moveTo(cx - a, cy - r); lineTo(cx + a, cy - r); lineTo(cx + a, cy - a)
                lineTo(cx + r, cy - a); lineTo(cx + r, cy + a); lineTo(cx + a, cy + a)
                lineTo(cx + a, cy + r); lineTo(cx - a, cy + r); lineTo(cx - a, cy + a)
                lineTo(cx - r, cy + a); lineTo(cx - r, cy - a); lineTo(cx - a, cy - a)
                close()
            }
            // Only the arm that is down lights, so a diagram of a pad with north
            // held reads as north held rather than as "the d-pad exists".
            down.forEach { arm ->
                drawRect(
                    color = fillLit,
                    topLeft = Offset(cx + arm.left * r + arm.leftArm * a, cy + arm.top * r + arm.topArm * a),
                    size = Size(
                        (arm.right - arm.left) * r + (arm.rightArm - arm.leftArm) * a,
                        (arm.bottom - arm.top) * r + (arm.bottomArm - arm.topArm) * a,
                    ),
                )
            }
            drawPath(cross, if (down.isEmpty()) ring else ringLit, style = Stroke(width = hairline.toPx()))
        }
    }
}

/**
 * One arm of the cross, as multiples of the radius and of the waist.
 *
 * Both, because an arm's rectangle is bounded by the radius on its outer edge and
 * by the waist on its inner one, and writing it as `r` and `a` terms keeps the
 * four in step with `ARM` instead of pinning numbers that would rot the moment
 * the proportion moves again.
 */
private data class DpadArm(
    val control: GamepadControl,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val leftArm: Float = 0f,
    val topArm: Float = 0f,
    val rightArm: Float = 0f,
    val bottomArm: Float = 0f,
)

private val DPAD_DIRECTIONS = listOf(
    DpadArm(GamepadControl.DPAD_UP, 0f, -1f, 0f, 0f, leftArm = -1f, rightArm = 1f, bottomArm = -1f),
    DpadArm(GamepadControl.DPAD_DOWN, 0f, 0f, 0f, 1f, leftArm = -1f, topArm = 1f, rightArm = 1f),
    DpadArm(GamepadControl.DPAD_LEFT, -1f, 0f, 0f, 0f, topArm = -1f, rightArm = -1f, bottomArm = 1f),
    DpadArm(GamepadControl.DPAD_RIGHT, 0f, 0f, 1f, 0f, leftArm = 1f, topArm = -1f, bottomArm = 1f),
)

/** The box the four old arms spanned, kept so the rest of the diagram does not move. */
private val DPAD_X = 2.dp
private val DPAD_Y = 52.dp
private val DPAD_SIZE = 64.dp

/** The design's 45%: a pad that cannot light up must not look like one that will not. */
private const val COLD_DIAGRAM_ALPHA = 0.45f

/**
 * Past this the pad stops reading as a diagram and starts reading as furniture.
 *
 * 2.2 was the first attempt -- the width of a 421 dp sheet -- and it looked
 * crude: the proportions were drawn at 210 dp and enlarging them by that much
 * makes the d-pad enormous and the gaps between clusters read as mistakes. The
 * original complaint was that the pad sat in the left half of the column, and
 * centring is most of that answer; a modest scale is the rest.
 */
private const val PAD_DIAGRAM_MAX_SCALE = 1.45f

private data class PadPin(
    val control: GamepadControl,
    val x: Dp,
    val y: Dp,
    val width: Dp,
    val height: Dp,
    val round: Boolean = false,
    val label: String = "",
) {
    @Composable
    fun shape() = if (round) Vessel.metrics.shapePill else Vessel.metrics.shapeSm
}

/** The design's own geometry, in a 210 x 156 dp box. */
private val PAD_PINS = listOf(
    PadPin(GamepadControl.L1, 6.dp, 0.dp, 40.dp, 13.dp, label = "L1"),
    PadPin(GamepadControl.L2, 6.dp, 17.dp, 40.dp, 13.dp, label = "L2"),
    PadPin(GamepadControl.R1, 164.dp, 0.dp, 40.dp, 13.dp, label = "R1"),
    PadPin(GamepadControl.R2, 164.dp, 17.dp, 40.dp, 13.dp, label = "R2"),
    // The four d-pad directions are not here: they are one cross, drawn and hit
    // as a unit by `PadDpad`, for the reasons in its header.
    PadPin(GamepadControl.THUMB_L, 14.dp, 116.dp, 36.dp, 36.dp, round = true, label = "L"),
    PadPin(GamepadControl.THUMB_R, 104.dp, 116.dp, 36.dp, 36.dp, round = true, label = "R"),
    PadPin(GamepadControl.Y, 160.dp, 50.dp, 22.dp, 22.dp, round = true, label = "Y"),
    PadPin(GamepadControl.X, 138.dp, 72.dp, 22.dp, 22.dp, round = true, label = "X"),
    PadPin(GamepadControl.B, 182.dp, 72.dp, 22.dp, 22.dp, round = true, label = "B"),
    PadPin(GamepadControl.A, 160.dp, 94.dp, 22.dp, 22.dp, round = true, label = "A"),
    PadPin(GamepadControl.SELECT, 70.dp, 60.dp, 30.dp, 13.dp, label = "SEL"),
    PadPin(GamepadControl.START, 108.dp, 60.dp, 30.dp, 13.dp, label = "STA"),
)

private val PAD_COLUMN_WIDTH = 232.dp

/** The clear cross and the picker's back arrow, both inside a 44 dp row. */
internal val CLEAR_TARGET = 28.dp
private val PAD_DIAGRAM_WIDTH = 210.dp
private val PAD_DIAGRAM_HEIGHT = 156.dp

/**
 * A track, a fill and a square thumb.
 *
 * Built here rather than pulled from the design system because the design system
 * has no slider: nothing else in the product has a continuous value. It stays
 * local until a second screen wants one.
 */
@Composable
internal fun InputSlider(
    label: String,
    value: Float,
    min: Float,
    max: Float,
    readout: String,
    help: String?,
    onValue: (Float) -> Unit,
) {
    var width by remember { mutableStateOf(1f) }
    val density = LocalDensity.current
    val thumb = 16.dp
    val fraction = ((value - min) / (max - min)).coerceIn(0f, 1f)

    fun emit(x: Float) {
        onValue(min + ((x / width).coerceIn(0f, 1f)) * (max - min))
    }

    Column(verticalArrangement = Arrangement.spacedBy(Vessel.metrics.s6)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Text(
                label,
                style = Vessel.type.bodySmall,
                color = Vessel.colors.textLabel,
                modifier = Modifier.weight(1f),
            )
            Text(readout, style = Vessel.type.metricSmall, color = Vessel.colors.textPrimary)
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(thumb)
                .onSizeChanged { width = it.width.toFloat().coerceAtLeast(1f) }
                .pointerInput(min, max, width) {
                    detectHorizontalDragGestures(
                        onDragStart = { emit(it.x) },
                        onHorizontalDrag = { change, _ -> emit(change.position.x) },
                    )
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(Vessel.metrics.shapePill)
                    .background(Vessel.colors.divider),
            )
            Box(
                Modifier
                    .width(with(density) { (width * fraction).toDp() })
                    .height(4.dp)
                    .clip(Vessel.metrics.shapePill)
                    .background(Vessel.colors.accent),
            )
            Box(
                Modifier
                    .offset(x = with(density) { (width * fraction - thumb.toPx() * fraction).toDp() })
                    .size(thumb)
                    .clip(Vessel.metrics.shapeSm)
                    .background(Vessel.colors.accent),
            )
        }
        if (help != null) {
            Text(help, style = Vessel.type.bodySmall, color = Vessel.colors.textMuted)
        }
    }
}

// — the rows, and the picker that replaces them ---------------------------------

/** One heading in the binding list, and the rows under it. */
private data class BindingGroup(
    val title: String,
    val controls: List<GamepadControl>,
    val stick: Stick? = null,
)

private val BINDING_GROUPS = listOf(
    BindingGroup("Left stick", Stick.LEFT.halfAxes.reversedForReading(), Stick.LEFT),
    BindingGroup("Right stick", Stick.RIGHT.halfAxes.reversedForReading(), Stick.RIGHT),
    BindingGroup(
        "D-pad",
        listOf(
            GamepadControl.DPAD_UP,
            GamepadControl.DPAD_DOWN,
            GamepadControl.DPAD_LEFT,
            GamepadControl.DPAD_RIGHT,
        ),
    ),
    BindingGroup(
        "Face buttons",
        listOf(GamepadControl.A, GamepadControl.B, GamepadControl.X, GamepadControl.Y),
    ),
    BindingGroup(
        "Shoulders and triggers",
        listOf(GamepadControl.L1, GamepadControl.R1, GamepadControl.L2, GamepadControl.R2),
    ),
    BindingGroup("Stick clicks", listOf(GamepadControl.THUMB_L, GamepadControl.THUMB_R)),
    BindingGroup("System", listOf(GamepadControl.SELECT, GamepadControl.START)),
)

/** Up, down, left, right — how a person reads a stick, not how the enum is ordered. */
private fun List<GamepadControl>.reversedForReading(): List<GamepadControl> =
    listOf(this[3], this[2], this[1], this[0])

internal fun GamepadControl.rowLabel(): String = when (this) {
    GamepadControl.STICK_L_UP -> "Left stick up"
    GamepadControl.STICK_L_DOWN -> "Left stick down"
    GamepadControl.STICK_L_LEFT -> "Left stick left"
    GamepadControl.STICK_L_RIGHT -> "Left stick right"
    GamepadControl.STICK_R_UP -> "Right stick up"
    GamepadControl.STICK_R_DOWN -> "Right stick down"
    GamepadControl.STICK_R_LEFT -> "Right stick left"
    GamepadControl.STICK_R_RIGHT -> "Right stick right"
    GamepadControl.DPAD_UP -> "D-pad up"
    GamepadControl.DPAD_DOWN -> "D-pad down"
    GamepadControl.DPAD_LEFT -> "D-pad left"
    GamepadControl.DPAD_RIGHT -> "D-pad right"
    GamepadControl.L2 -> "L2 trigger"
    GamepadControl.R2 -> "R2 trigger"
    GamepadControl.THUMB_L -> "Left stick click"
    GamepadControl.THUMB_R -> "Right stick click"
    GamepadControl.SELECT -> "Select"
    GamepadControl.START -> "Start"
    else -> name
}

private fun LazyListScope.bindingListItems(
    profile: InputProfile,
    lit: Set<GamepadControl>,
    onPick: (GamepadControl) -> Unit,
    onProfile: (InputProfile) -> Unit,
) {
    item(key = "bound-count") {
        Row(
            Modifier.fillMaxWidth().padding(
                top = Vessel.metrics.s11,
                bottom = Vessel.metrics.s6,
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "${profile.boundCount} of ${GamepadControl.entries.size} bound",
                style = Vessel.type.overline,
                color = Vessel.colors.textMuted,
                modifier = Modifier.weight(1f),
            )
            VButton(
                "Reset all",
                { onProfile(profile.resetToDefaults()) },
                style = VButtonStyle.Ghost,
            )
        }
    }
    BINDING_GROUPS.forEach { group ->
        val role = group.stick?.let { profile.pad.roleOf(it) }
        item(key = "h-${group.title}") {
            Text(
                group.title,
                style = Vessel.type.overline,
                color = Vessel.colors.textMuted,
                modifier = Modifier.padding(
                    top = Vessel.metrics.s11,
                    bottom = Vessel.metrics.s3,
                ),
            )
        }
        // **A row that cannot fire is worse than a missing one.** A stick
        // sending the pointer has no half-axes to bind, so the four rows
        // are replaced by the sentence that says how to get them back.
        if (role != null && role != StickRole.Keys) {
            item(key = "n-${group.title}") {
                InputNote(
                    if (role == StickRole.Look) {
                        "Moves the mouse. Set it to Keys to bind its four directions."
                    } else {
                        "Sends nothing. Set it to Keys to bind its four directions."
                    },
                )
            }
        } else {
            items(group.controls.size, key = { "r-${group.controls[it]}" }) { index ->
                val control = group.controls[index]
                BindingRow(
                    control = control,
                    action = profile.pad.bindings[control] ?: GamepadAction.None,
                    lit = control in lit,
                    onClick = { onPick(control) },
                    onClear = {
                        onProfile(profile.withBinding(control, GamepadAction.None))
                    },
                )
            }
        }
    }
}

/**
 * Where a control's row sits in the binding list, or null if it has none.
 *
 * **It walks the list the same way [bindingListItems] emits it, and the two have
 * to move together.** A `LazyColumn` scrolls by index and nothing else; the index
 * is only knowable by repeating the walk, because whether a stick contributes one
 * note or four rows depends on the profile's role for it. Written immediately
 * above the emitter so that a change to one is in front of the eye that changes
 * the other.
 */
private fun bindingRowIndex(profile: InputProfile, control: GamepadControl): Int? {
    var index = 1 // `bound-count`, which the list opens with.
    BINDING_GROUPS.forEach { group ->
        index++ // the group's own heading
        val role = group.stick?.let { profile.pad.roleOf(it) }
        if (role != null && role != StickRole.Keys) {
            index++ // the note that stands in for the four rows
        } else {
            val at = group.controls.indexOf(control)
            if (at >= 0) return index + at
            index += group.controls.size
        }
    }
    return null
}

/** An icon and a sentence — the shape every explanatory line in this editor takes. */
@Composable
internal fun InputNote(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().padding(vertical = Vessel.metrics.s6),
        horizontalArrangement = Arrangement.spacedBy(Vessel.metrics.s6),
    ) {
        Icon(
            VIcons.Info,
            contentDescription = null,
            Modifier.size(Vessel.metrics.iconSm),
            tint = Vessel.colors.textMuted,
        )
        Text(text, style = Vessel.type.bodySmall, color = Vessel.colors.textMuted)
    }
}

/**
 * Name, what it sends, and a cross.
 *
 * The same shape as `VDiagnosticRow`, because it is the same kind of list and the
 * product already has a rendering for it. The accent bar on the left is the
 * live-press indicator: it appears the instant the physical control goes down.
 */
@Composable
private fun BindingRow(
    control: GamepadControl,
    action: GamepadAction,
    lit: Boolean,
    onClick: () -> Unit,
    onClear: () -> Unit,
) {
    val unbound = action == GamepadAction.None
    Row(
        Modifier
            .fillMaxWidth()
            .clip(Vessel.metrics.shapeMd)
            .background(if (lit) Vessel.colors.accentHover else Color.Transparent)
            .clickable(onClickLabel = control.rowLabel(), onClick = onClick)
            .heightIn(min = Vessel.metrics.touchTarget)
            .padding(horizontal = Vessel.metrics.s6),
        horizontalArrangement = Arrangement.spacedBy(Vessel.metrics.s8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(2.dp)
                .height(20.dp)
                .clip(Vessel.metrics.shapePill)
                .background(if (lit) Vessel.colors.accent else Color.Transparent),
        )
        Text(
            control.rowLabel(),
            style = Vessel.type.body,
            color = Vessel.colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        BindingChip(X11KeyCatalog.label(action), bound = !unbound)
        if (unbound) {
            Box(Modifier.size(CLEAR_TARGET))
        } else {
            VIconAction(
                icon = VIcons.X,
                contentDescription = "Clear ${control.rowLabel()}",
                onClick = onClear,
                style = VButtonStyle.Ghost,
                size = CLEAR_TARGET,
            )
        }
    }
}

/** What a control sends, as one chip. The same mark on a pad row and a touch row. */
@Composable
internal fun BindingChip(label: String, bound: Boolean) {
    Box(
        Modifier
            .clip(Vessel.metrics.shapeTag)
            .background(if (bound) Vessel.colors.accent800 else Color.Transparent)
            .padding(horizontal = 10.dp, vertical = Vessel.metrics.s3),
    ) {
        Text(
            label,
            style = Vessel.type.monoSmall,
            color = if (bound) Vessel.colors.accent100 else Vessel.colors.textMuted,
            maxLines = 1,
            softWrap = false,
        )
    }
}

/**
 * Choosing what one control sends: a searchable catalogue, with capture beside it.
 *
 * **The list is primary and capture is the shortcut, not the other way round.** A
 * physical keyboard over Bluetooth captures exactly, which is why capture exists
 * at all. A soft keyboard has no `Esc`, `Ctrl`, `Alt`, arrows or function keys,
 * and a character it delivers as `ACTION_MULTIPLE` carries no keycode — so a
 * capture-only picker could not offer half of what a game wants. The catalogue
 * can, and it cannot offer a keycode the vendored server would refuse.
 *
 * It takes the *list's* place rather than the screen's, so the pad diagram stays
 * on screen and Learn keeps working while a key is being chosen. The touch
 * overlay's own binding sheet uses it too, which is why the title is a string
 * rather than a [GamepadControl].
 */
@Composable
internal fun KeyPicker(
    title: String,
    current: GamepadAction,
    onClose: () -> Unit,
    onChoose: (GamepadAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by remember { mutableStateOf("") }
    var capturing by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val groups = remember(query) { X11KeyCatalog.searchGroups(query) }

    LaunchedEffect(capturing) { if (capturing) runCatching { focus.requestFocus() } }

    Column(
        modifier
            .fillMaxHeight()
            .focusRequester(focus)
            .focusable(enabled = capturing)
            .onPreviewKeyEvent { event ->
                if (!capturing || event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val binding = X11KeyMap[event.nativeKeyEvent.keyCode]
                    ?: return@onPreviewKeyEvent false
                capturing = false
                onChoose(GamepadAction.Key(binding.keycode, binding.keysym))
                true
            },
    ) {
        Row(
            Modifier.fillMaxWidth().padding(top = Vessel.metrics.s8),
            horizontalArrangement = Arrangement.spacedBy(Vessel.metrics.s6),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            VIconAction(
                icon = VIcons.ArrowLeft,
                contentDescription = "Back to the list",
                onClick = onClose,
                style = VButtonStyle.Ghost,
                size = CLEAR_TARGET,
            )
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = Vessel.type.cardTitle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "now: ${X11KeyCatalog.label(current)}",
                    style = Vessel.type.monoSmall,
                    color = Vessel.colors.textMuted,
                )
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(vertical = Vessel.metrics.s11),
            horizontalArrangement = Arrangement.spacedBy(Vessel.metrics.s6),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            VTextField(query, { query = it }, Modifier.weight(1f), placeholder = "Filter keys")
            VButton(
                if (capturing) "Listening" else "Press a key",
                { capturing = !capturing },
                style = if (capturing) VButtonStyle.Primary else VButtonStyle.Secondary,
            )
        }

        if (capturing) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = Vessel.metrics.s8)
                    .clip(Vessel.metrics.shapeMd)
                    .background(Vessel.colors.accentHover)
                    .border(Vessel.metrics.hairline, Vessel.colors.accent, Vessel.metrics.shapeMd)
                    .padding(Vessel.metrics.s8),
            ) {
                // Honest about its limit: a soft keyboard cannot send most of
                // these, so the list is named in the same breath.
                Text(
                    "Press the key on a physical keyboard, or pick one below.",
                    style = Vessel.type.bodySmall,
                    color = Vessel.colors.textPrimary,
                )
            }
        }

        LazyColumn(
            Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(bottom = Vessel.metrics.s22),
        ) {
            item(key = "none") {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(Vessel.metrics.shapeMd)
                        .clickable(onClickLabel = "Leave it unbound") {
                            onChoose(GamepadAction.None)
                        }
                        .heightIn(min = Vessel.metrics.touchTarget)
                        .padding(horizontal = Vessel.metrics.s6),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Leave it unbound",
                        style = Vessel.type.body,
                        color = Vessel.colors.textLabel,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        X11KeyCatalog.UNBOUND,
                        style = Vessel.type.monoSmall,
                        color = Vessel.colors.textMuted,
                    )
                }
            }
            groups.forEach { (group, keys) ->
                item(key = "g-${group.name}") {
                    Text(
                        group.title,
                        style = Vessel.type.overline,
                        color = Vessel.colors.textMuted,
                        modifier = Modifier.padding(
                            top = Vessel.metrics.s11,
                            bottom = Vessel.metrics.s3,
                        ),
                    )
                }
                item(key = "k-${group.name}") {
                    KeyChips(keys, current, onChoose)
                }
            }
            if (groups.isEmpty()) {
                item(key = "empty") {
                    Text(
                        "No key matches that.",
                        style = Vessel.type.bodySmall,
                        color = Vessel.colors.textMuted,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(vertical = Vessel.metrics.s22),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun KeyChips(keys: List<KeyChoice>, current: GamepadAction, onChoose: (GamepadAction) -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(Vessel.metrics.s6),
        verticalArrangement = Arrangement.spacedBy(Vessel.metrics.s6),
    ) {
        keys.forEach { choice ->
            val selected = choice.action == current
            Box(
                Modifier
                    .heightIn(min = Vessel.metrics.controlHeight)
                    .clip(Vessel.metrics.shapeMd)
                    .background(if (selected) Vessel.colors.accentHover else Color.Transparent)
                    .border(
                        Vessel.metrics.hairline,
                        if (selected) Vessel.colors.accent else Vessel.colors.border,
                        Vessel.metrics.shapeMd,
                    )
                    .clickable(onClickLabel = choice.label) { onChoose(choice.action) }
                    .padding(horizontal = 10.dp, vertical = Vessel.metrics.s8),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    choice.label,
                    style = Vessel.type.monoSmall,
                    color = if (selected) Vessel.colors.accent100 else Vessel.colors.textPrimary,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}

// — the edits themselves --------------------------------------------------------

internal fun InputProfile.withBinding(control: GamepadControl, action: GamepadAction) =
    copy(pad = pad.copy(bindings = pad.bindings + (control to action)))

internal fun InputProfile.withStick(stick: Stick, role: StickRole) =
    copy(pad = pad.copy(sticks = pad.sticks + (stick to role)))

internal fun InputProfile.withConfig(next: GamepadConfig) = copy(config = next)

/**
 * Back to what a fresh container runs with — bindings *and* stick roles.
 *
 * The name is kept, and so is the overlay. Resetting the table is not the same as
 * discarding the profile: a Reset that renamed it would lose every container's
 * pointer at it, and one that cleared the overlay would throw away a layout the
 * user placed with their thumbs to fix a keybinding.
 */
internal fun InputProfile.resetToDefaults() = copy(
    pad = pad.copy(
        bindings = GamepadProfile.Default.bindings,
        sticks = GamepadProfile.Default.sticks,
    ),
    config = GamepadConfig(),
)
