package app.vessel.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
import app.vessel.ui.components.VRule
import app.vessel.ui.components.VTextField
import app.vessel.ui.components.VToggle
import app.vessel.ui.theme.VElev
import app.vessel.ui.theme.Vessel
import app.vessel.ui.theme.vCard
import kotlin.math.roundToInt

/**
 * The binding editor, over the running desktop.
 *
 * **It is here rather than on a settings screen because a binding is only
 * knowable by testing it.** Whether `R2` should be left-click or `Space` is a
 * question about the game that is running, and every other placement makes the
 * loop *edit → save → launch → discover → back out → edit*, which on this device
 * is a two-minute cycle per binding. So the panel opens beside the rail, over the
 * desktop, and every change takes effect the moment it is made — there is no
 * draft and no Save, because the whole argument for being here is immediacy.
 *
 * **Nothing on it implies a gamepad reaches the guest.** Vessel ships no XInput:
 * the only channel into a Windows program is the X server, which carries keys and
 * a pointer. Every row therefore resolves to a keystroke, a mouse button or
 * pointer motion, and the word "pad" names the *device in your hands*, never what
 * the game sees. There is no rumble, no gyro and no "controller detected".
 */
@Composable
fun InputPanel(
    profile: InputProfile,
    held: Set<GamepadControl>,
    onProfile: (InputProfile) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
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
    val lit = if (held.isNotEmpty()) held else setOfNotNull(pinned)

    // Learn, driven by the pad itself. `gamepadControl` already names every
    // physical control, so this is free — and it is the best answer to "which of
    // these twenty-four rows is the button I am pressing".
    LaunchedEffect(learn, held) {
        if (learn) held.firstOrNull()?.let { picking = it }
    }

    Column(
        modifier
            .width(Vessel.metrics.inputPanelWidth)
            .fillMaxHeight()
            .systemBarsPadding()
            .padding(
                top = Vessel.metrics.s6,
                bottom = Vessel.metrics.s6,
                end = Vessel.metrics.s6,
            )
            .vCard(fill = Vessel.colors.surfaceFloating, elevation = VElev.md),
    ) {
        InputPanelHeader(profile.name, onClose)
        VRule(verticalMargin = 0.dp)
        Row(Modifier.fillMaxWidth().weight(1f)) {
            PadSettingsColumn(
                profile = profile,
                learn = learn,
                onLearn = { learn = it },
                lit = lit,
                onPin = { if (learn) picking = it else pinned = it },
                onProfile = onProfile,
            )
            Box(
                Modifier
                    .width(Vessel.metrics.hairline)
                    .fillMaxHeight()
                    .background(Vessel.colors.divider),
            )
            Box(Modifier.weight(1f).fillMaxHeight()) {
                val control = picking
                if (control == null) {
                    BindingList(
                        profile = profile,
                        lit = lit,
                        onPick = { picking = it },
                        onProfile = onProfile,
                    )
                } else {
                    KeyPicker(
                        control = control,
                        current = profile.pad.bindings[control] ?: GamepadAction.None,
                        onClose = { picking = null },
                        onChoose = { action ->
                            onProfile(profile.withBinding(control, action))
                            picking = null
                        },
                    )
                }
            }
        }
    }
}

/**
 * The panel's first row: which profile is being edited, and the way out.
 *
 * The name is a plain label rather than a control while there is only one way to
 * choose a profile — the container sheet. It becomes the picker in Phase 5.
 */
@Composable
private fun InputPanelHeader(name: String, onClose: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(
            start = Vessel.metrics.s11,
            end = Vessel.metrics.s6,
            top = Vessel.metrics.s8,
            bottom = Vessel.metrics.s8,
        ),
        horizontalArrangement = Arrangement.spacedBy(Vessel.metrics.s8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            VIcons.Gamepad,
            contentDescription = null,
            Modifier.size(Vessel.metrics.iconSm),
            tint = Vessel.colors.textLabel,
        )
        Text(
            name,
            style = Vessel.type.cardTitle,
            color = Vessel.colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        VIconAction(
            icon = VIcons.X,
            contentDescription = "Close the input panel",
            onClick = onClose,
            style = VButtonStyle.Ghost,
        )
    }
}

// — the left column: the pad, the roles, the two numbers ------------------------

@Composable
private fun PadSettingsColumn(
    profile: InputProfile,
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
            .padding(Vessel.metrics.s11),
        verticalArrangement = Arrangement.spacedBy(Vessel.metrics.s11),
    ) {
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

        PadDiagram(lit = lit, onPin = onPin)

        Stick.entries.forEach { stick ->
            StickRoleField(stick, profile) { onProfile(it) }
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

private val ROLES = listOf(StickRole.Keys, StickRole.Look, StickRole.None)

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
private fun PadDiagram(lit: Set<GamepadControl>, onPin: (GamepadControl) -> Unit) {
    Box(Modifier.size(PAD_DIAGRAM_WIDTH, PAD_DIAGRAM_HEIGHT)) {
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
    PadPin(GamepadControl.DPAD_UP, 26.dp, 52.dp, 18.dp, 20.dp),
    PadPin(GamepadControl.DPAD_DOWN, 26.dp, 96.dp, 18.dp, 20.dp),
    PadPin(GamepadControl.DPAD_LEFT, 4.dp, 74.dp, 20.dp, 18.dp),
    PadPin(GamepadControl.DPAD_RIGHT, 46.dp, 74.dp, 20.dp, 18.dp),
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
private val CLEAR_TARGET = 28.dp
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
private fun InputSlider(
    label: String,
    value: Float,
    min: Float,
    max: Float,
    readout: String,
    help: String,
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
        Text(help, style = Vessel.type.bodySmall, color = Vessel.colors.textMuted)
    }
}

// — the right column: the rows, and the picker that replaces them ---------------

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

private fun GamepadControl.rowLabel(): String = when (this) {
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

@Composable
private fun BindingList(
    profile: InputProfile,
    lit: Set<GamepadControl>,
    onPick: (GamepadControl) -> Unit,
    onProfile: (InputProfile) -> Unit,
) {
    val listState = rememberLazyListState()
    Column(Modifier.fillMaxHeight()) {
        Row(
            Modifier.fillMaxWidth().padding(
                start = Vessel.metrics.s11,
                end = Vessel.metrics.s11,
                top = Vessel.metrics.s8,
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
        LazyColumn(
            Modifier.fillMaxWidth().weight(1f),
            state = listState,
            contentPadding = PaddingValues(
                start = Vessel.metrics.s11,
                end = Vessel.metrics.s11,
                bottom = Vessel.metrics.s11,
            ),
        ) {
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
                        GroupNote(
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
    }
}

@Composable
private fun GroupNote(text: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = Vessel.metrics.s6),
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
        Box(
            Modifier
                .clip(Vessel.metrics.shapeTag)
                .background(if (unbound) Color.Transparent else Vessel.colors.accent800)
                .padding(horizontal = 10.dp, vertical = Vessel.metrics.s3),
        ) {
            Text(
                X11KeyCatalog.label(action),
                style = Vessel.type.monoSmall,
                color = if (unbound) Vessel.colors.textMuted else Vessel.colors.accent100,
                maxLines = 1,
                softWrap = false,
            )
        }
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

/**
 * Choosing what one control sends: a searchable catalogue, with capture beside it.
 *
 * **The list is primary and capture is the shortcut, not the other way round.** A
 * physical keyboard over Bluetooth captures exactly, which is why capture exists
 * at all. A soft keyboard has no `Esc`, `Ctrl`, `Alt`, arrows or function keys,
 * and a character it delivers as `ACTION_MULTIPLE` carries no keycode — so a
 * capture-only picker could not offer half of what a game wants. The catalogue
 * can, and it cannot offer a keycode the server would refuse.
 *
 * It takes the *list's* place rather than the screen's, so the pad diagram stays
 * on screen and Learn keeps working while a key is being chosen.
 */
@Composable
private fun KeyPicker(
    control: GamepadControl,
    current: GamepadAction,
    onClose: () -> Unit,
    onChoose: (GamepadAction) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var capturing by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val groups = remember(query) { X11KeyCatalog.searchGroups(query) }

    LaunchedEffect(capturing) { if (capturing) runCatching { focus.requestFocus() } }

    Column(
        Modifier
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
            Modifier.fillMaxWidth().padding(
                start = Vessel.metrics.s6,
                end = Vessel.metrics.s11,
                top = Vessel.metrics.s8,
            ),
            horizontalArrangement = Arrangement.spacedBy(Vessel.metrics.s6),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            VIconAction(
                icon = VIcons.ArrowLeft,
                contentDescription = "Back to the binding list",
                onClick = onClose,
                style = VButtonStyle.Ghost,
                size = CLEAR_TARGET,
            )
            Column(Modifier.weight(1f)) {
                Text(
                    control.rowLabel(),
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
            Modifier.fillMaxWidth().padding(Vessel.metrics.s11),
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
                    .padding(horizontal = Vessel.metrics.s11)
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
            contentPadding = PaddingValues(
                start = Vessel.metrics.s11,
                end = Vessel.metrics.s11,
                bottom = Vessel.metrics.s11,
            ),
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

private fun InputProfile.withBinding(control: GamepadControl, action: GamepadAction) =
    copy(pad = pad.copy(bindings = pad.bindings + (control to action)))

private fun InputProfile.withStick(stick: Stick, role: StickRole) =
    copy(pad = pad.copy(sticks = pad.sticks + (stick to role)))

private fun InputProfile.withConfig(next: GamepadConfig) = copy(config = next)

/**
 * Back to what a fresh container runs with — bindings *and* stick roles.
 *
 * The name is kept. Resetting the table is not the same as discarding the profile,
 * and a Reset that renamed it would lose every container's pointer at it.
 */
private fun InputProfile.resetToDefaults() = copy(
    pad = pad.copy(
        bindings = GamepadProfile.Default.bindings,
        sticks = GamepadProfile.Default.sticks,
    ),
    config = GamepadConfig(),
)
