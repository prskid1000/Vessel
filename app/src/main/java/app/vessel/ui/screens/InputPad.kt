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
 * The physical pad: a picture of a controller, Learn, the stick roles and the two
 * numbers that tune a stick.
 *
 * **Nothing on it implies a gamepad reaches the guest through this table.** The
 * table carries keystrokes and pointer motion, and the word "pad" names the
 * *device in your hands*, never what the game sees; a stick set to
 * [StickRole.Pad] bypasses it entirely and reaches the guest's own HID device.
 * There is no rumble, no gyro and no "controller detected".
 *
 * **It is a section of one screen now, not a tab and not a second column.** The
 * twenty-four binding rows that used to live under here are gone from this file:
 * every control the profile has — on the glass, on the pad, or both — is one list
 * in [InputEditor], because the two halves were one table seen twice and saying
 * it twice is what made the screen unreadable. What is left here is the pad
 * itself.
 */
@Composable
internal fun PadSettings(
    profile: InputProfile,
    live: Boolean,
    learn: Boolean,
    onLearn: (Boolean) -> Unit,
    onProfile: (InputProfile) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Vessel.metrics.s6),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // **These say "your controller", because there is no longer a picture of
        // one to press.** A schematic pad used to sit here and answer both
        // sentences; it was a second drawing of the controller the map at the top
        // already draws, in the wrong positions, and reading one screen as two
        // controllers is what the redesign set out to end. What replaces it is
        // better than the diagram was: the real pad in your hands finds the row,
        // and the map finds the ones on the glass.
        Text(
            if (learn) "Press a control on your pad to bind it"
            else "Press a control on your pad to find its row",
            style = Vessel.type.overline,
            color = Vessel.colors.textMuted,
            modifier = Modifier.weight(1f),
        )
        VToggle(checked = learn, onCheckedChange = onLearn)
        Text("Learn", style = Vessel.type.bodySmall, color = Vessel.colors.textLabel)
    }

    // Cold, nothing can answer a press, and it says so rather than leaving the
    // sentence above looking broken.
    if (!live) {
        InputNote("No session is running, so a press cannot find its row. Every control still binds.")
    }

    // The two stick roles used to sit here, side by side. They are properties of
    // one stick, not of the screen, so they moved to the selected control — where
    // a stick's role is one field of the stick you are looking at rather than a
    // pair you have to match to the right one by name.

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
internal fun StickRoleField(stick: Stick, profile: InputProfile, onProfile: (InputProfile) -> Unit) {
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

/** The clear cross and the picker's back arrow, both inside a 44 dp row. */
internal val CLEAR_TARGET = 28.dp

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

// — reading order, and the picker ------------------------------------------------

/**
 * The twenty-four, in the order a person looks for them.
 *
 * **Flat, and no longer seven headed groups.** The groups were the shape of a
 * table of its own; the editor has one list now and this is only the order the
 * controls a profile has not put on the glass come out in. Sticks first because
 * they are the two a thumb reaches for, then the cross, then the face diamond,
 * then the fingers, then the two things nobody presses by accident.
 */
internal val PAD_READING_ORDER: List<GamepadControl> =
    Stick.LEFT.halfAxes.reversedForReading() +
        Stick.RIGHT.halfAxes.reversedForReading() +
        listOf(
            GamepadControl.DPAD_UP,
            GamepadControl.DPAD_DOWN,
            GamepadControl.DPAD_LEFT,
            GamepadControl.DPAD_RIGHT,
            GamepadControl.A,
            GamepadControl.B,
            GamepadControl.X,
            GamepadControl.Y,
            GamepadControl.L1,
            GamepadControl.R1,
            GamepadControl.L2,
            GamepadControl.R2,
            GamepadControl.THUMB_L,
            GamepadControl.THUMB_R,
            GamepadControl.SELECT,
            GamepadControl.START,
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

/** What a control sends, as one chip. The same mark on every row of the list. */
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
