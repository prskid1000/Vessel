package app.vessel.input

import kotlin.math.abs

/**
 * Every control on a gamepad Vessel can bind, named by what it is rather than by
 * which Android constant reports it — the sticks arrive as axes and the buttons
 * as key codes, and a binding table should not have to care.
 */
enum class GamepadControl {
    A, B, X, Y,
    L1, R1, L2, R2,
    SELECT, START, THUMB_L, THUMB_R,
    DPAD_UP, DPAD_DOWN, DPAD_LEFT, DPAD_RIGHT,
    STICK_L_UP, STICK_L_DOWN, STICK_L_LEFT, STICK_L_RIGHT,
    STICK_R_UP, STICK_R_DOWN, STICK_R_LEFT, STICK_R_RIGHT,
}

/** Which of the two analogue sticks a [StickRole] is about. */
enum class Stick {
    LEFT,
    RIGHT,
    ;

    /** The half-axis control this stick pushed up is. */
    val up: GamepadControl
        get() = if (this == LEFT) GamepadControl.STICK_L_UP else GamepadControl.STICK_R_UP

    val down: GamepadControl
        get() = if (this == LEFT) GamepadControl.STICK_L_DOWN else GamepadControl.STICK_R_DOWN

    val left: GamepadControl
        get() = if (this == LEFT) GamepadControl.STICK_L_LEFT else GamepadControl.STICK_R_LEFT

    val right: GamepadControl
        get() = if (this == LEFT) GamepadControl.STICK_L_RIGHT else GamepadControl.STICK_R_RIGHT

    /** Its four half-axes, in the order [GamepadTranslator.onSticks] emits them. */
    val halfAxes: List<GamepadControl> get() = listOf(right, left, down, up)
}

/**
 * What a whole stick does, which is a property of the stick and not of its four
 * half-axes.
 *
 * There is deliberately no `Look` member on [GamepadAction]: a user able to bind
 * "the left half of the right stick" to look would produce a translator state
 * nobody can reason about, and the pointer has one velocity, not four.
 */
sealed interface StickRole {
    /** Relative pointer motion at [GamepadConfig.lookSpeed]. Two sticks set to this sum. */
    data object Look : StickRole

    /** Four half-axis controls, thresholded at the deadzone with hysteresis. */
    data object Keys : StickRole

    /**
     * An axis on the gamepad the guest sees, and nothing else.
     *
     * **The role a stick has when there is a gamepad to be.** The other three
     * are all answers to "there is no pad in the guest, so what should this
     * pretend to be" — and until `patches/wine/0016` gave the guest a real HID
     * device that was the only question worth asking. A stick set to this emits
     * no keys and no pointer motion at all: its deflection reaches the guest as
     * a stick, through `PadBridge`, which is a path this class knows nothing
     * about and should not.
     *
     * That is why it produces nothing here rather than something smaller. A
     * stick that sent an axis *and* a keystroke would move a game reading both
     * twice as far, which is the same doubling the bridge's mute exists to
     * prevent for a physical pad.
     */
    data object Pad : StickRole

    /** Nothing at all. Anything the stick was holding is released. */
    data object None : StickRole

    companion object {
        /** The stored form: the object's own name, so an unknown one degrades. */
        fun byName(name: String): StickRole? = when (name) {
            "Look" -> Look
            "Keys" -> Keys
            "Pad" -> Pad
            "None" -> None
            else -> null
        }

        fun nameOf(role: StickRole): String = when (role) {
            Pad -> "Pad"
            Look -> "Look"
            Keys -> "Keys"
            None -> "None"
        }
    }
}

/** What one control does in the guest. */
sealed interface GamepadAction {
    data class Key(val keycode: Int, val keysym: Int = 0) : GamepadAction
    data class Button(val button: PointerButton) : GamepadAction

    /**
     * A control on the guest's own gamepad.
     *
     * **The binding that only became possible once the guest had a pad.** Before
     * `patches/wine/0016` there was nothing on the other side to name, so every
     * binding was a keystroke or a mouse button and a glass `A` reached a game as
     * the space bar. Now `A` can simply be `A` — and, because it is a binding
     * rather than an identity, the `A` on the glass can be `B` if you would
     * rather, which is what "I can switch the mapping if I want" asks for.
     *
     * It produces no [GuestInput] anywhere. Keys and pointer buttons travel the
     * X11 seam; this travels the pad socket, and the two are different wires.
     * Everything that turns actions into [GuestInput] returns nothing for it,
     * deliberately and not by omission.
     */
    data class Pad(val control: GamepadControl) : GamepadAction

    data object None : GamepadAction
}

/** What a control is called on screen, in the one place that decides it. */
fun GamepadControl.displayName(): String = when (this) {
    // "A button", not "A": the catalogue promises every label is distinct, and
    // the letter keys are already called A, B, X and Y. A picker offering two
    // rows both reading "A" is the one thing this text exists to prevent.
    GamepadControl.A -> "A button"
    GamepadControl.B -> "B button"
    GamepadControl.X -> "X button"
    GamepadControl.Y -> "Y button"
    GamepadControl.L1 -> "L1 bumper"
    GamepadControl.R1 -> "R1 bumper"
    GamepadControl.L2 -> "L2 trigger"
    GamepadControl.R2 -> "R2 trigger"
    GamepadControl.SELECT -> "Select"
    GamepadControl.START -> "Start"
    GamepadControl.THUMB_L -> "Left stick press"
    GamepadControl.THUMB_R -> "Right stick press"
    GamepadControl.DPAD_UP -> "D-pad up"
    GamepadControl.DPAD_DOWN -> "D-pad down"
    GamepadControl.DPAD_LEFT -> "D-pad left"
    GamepadControl.DPAD_RIGHT -> "D-pad right"
    GamepadControl.STICK_L_UP -> "Left stick up"
    GamepadControl.STICK_L_DOWN -> "Left stick down"
    GamepadControl.STICK_L_LEFT -> "Left stick left"
    GamepadControl.STICK_L_RIGHT -> "Left stick right"
    GamepadControl.STICK_R_UP -> "Right stick up"
    GamepadControl.STICK_R_DOWN -> "Right stick down"
    GamepadControl.STICK_R_LEFT -> "Right stick left"
    GamepadControl.STICK_R_RIGHT -> "Right stick right"
}

/**
 * A binding table, and the reason there is no XInput here.
 *
 * **This used to say there was no XInput, and that is no longer true.**
 * `patches/wine/0016` gives the guest a real HID gamepad fed over a socket, so
 * a control can now simply *be* itself: `A` arrives as `A`, a stick arrives as
 * an axis, and no binding is involved. That is what [StickRole.Pad] and
 * [Gamepad] below are for.
 *
 * This table remains, because a great many Windows games read only the keyboard
 * — and for those, a pad that sends `W` is the difference between playable and
 * not. So a binding is now a deliberate choice for a game that needs one,
 * rather than the only channel that exists.
 *
 * The default is the layout most Windows games are playable with unmodified:
 * left stick walks, right stick looks, and the face buttons land on the keys a
 * keyboard-and-mouse player would use.
 */
data class GamepadProfile(
    val name: String,
    val bindings: Map<GamepadControl, GamepadAction>,
    /**
     * What each stick as a whole does. Absent means [StickRole.None].
     *
     * The default reproduces the hardwiring this used to have exactly — left
     * walks, right looks — which is the regression gate: `GamepadTest` passes
     * unmodified, and if it does not, this refactor is wrong.
     */
    val sticks: Map<Stick, StickRole> = mapOf(
        Stick.LEFT to StickRole.Keys,
        Stick.RIGHT to StickRole.Look,
    ),
) {
    /** The role of one stick, with the same "absent is nothing" rule as [bindings]. */
    fun roleOf(stick: Stick): StickRole = sticks[stick] ?: StickRole.None

    companion object {
        /**
         * Every control sends itself, on the guest's own gamepad.
         *
         * **This used to be a keyboard, and the change is the whole point of
         * `patches/wine/0016`.** `A` was the space bar, the left stick was WASD
         * and the right stick moved the mouse, because there was no controller in
         * the guest to send anything to. There is one now, so the default is a
         * controller: `A` is `A`, both sticks are sticks, and a game that reads
         * XInput gets what it asked for without anyone opening this screen.
         *
         * Written as a binding per control rather than as an empty map meaning
         * "pass through", so the editor has twenty-four rows to show and every
         * one of them can be pointed somewhere else. That is what makes the
         * mapping switchable: the identity is a value, not a hardwiring.
         *
         * **What this costs when the bridge is not there.** A guest running an
         * older Wine has no pad, and a profile that sends only pad controls
         * reaches it with nothing at all. That is the correct behaviour rather
         * than a silent fallback to keys — a game walking twice as far because it
         * read both is a worse failure than one that reads neither — and the way
         * out is [KeyboardAndMouse], one tap away in the profile list.
         */
        val Default = GamepadProfile(
            name = "Controller",
            bindings = GamepadControl.entries.associateWith { GamepadAction.Pad(it) },
            sticks = mapOf(Stick.LEFT to StickRole.Pad, Stick.RIGHT to StickRole.Pad),
        )

        /**
         * The old default, kept whole and offered by name.
         *
         * For the many Windows games that read the keyboard and nothing else,
         * and for a guest whose Wine predates the pad. Left stick walks, right
         * stick looks, face buttons on the keys a keyboard-and-mouse player uses.
         */
        val KeyboardAndMouse = GamepadProfile(
            name = "Keyboard and mouse",
            bindings = mapOf(
                GamepadControl.STICK_L_UP to GamepadAction.Key(X11.W),
                GamepadControl.STICK_L_DOWN to GamepadAction.Key(X11.S),
                GamepadControl.STICK_L_LEFT to GamepadAction.Key(X11.A),
                GamepadControl.STICK_L_RIGHT to GamepadAction.Key(X11.D),

                GamepadControl.DPAD_UP to GamepadAction.Key(X11.UP),
                GamepadControl.DPAD_DOWN to GamepadAction.Key(X11.DOWN),
                GamepadControl.DPAD_LEFT to GamepadAction.Key(X11.LEFT),
                GamepadControl.DPAD_RIGHT to GamepadAction.Key(X11.RIGHT),

                GamepadControl.A to GamepadAction.Key(X11.SPACE),
                GamepadControl.B to GamepadAction.Key(X11.CTRL_L),
                GamepadControl.X to GamepadAction.Key(X11.E),
                GamepadControl.Y to GamepadAction.Key(X11.R),

                GamepadControl.L1 to GamepadAction.Key(X11.Q),
                GamepadControl.R1 to GamepadAction.Key(X11.F),
                GamepadControl.L2 to GamepadAction.Button(PointerButton.RIGHT),
                GamepadControl.R2 to GamepadAction.Button(PointerButton.LEFT),

                GamepadControl.SELECT to GamepadAction.Key(X11.TAB),
                GamepadControl.START to GamepadAction.Key(X11.ESC),
                GamepadControl.THUMB_L to GamepadAction.Key(X11.SHIFT_L),
                GamepadControl.THUMB_R to GamepadAction.Key(X11.C),
            ),
            sticks = mapOf(Stick.LEFT to StickRole.Keys, Stick.RIGHT to StickRole.Look),
        )
    }
}

/** Deadzones and speeds, all in stick units (−1..1) except [lookSpeed]. */
data class GamepadConfig(
    /**
     * Below this a stick is centred.
     *
     * 0.25 rather than the more common 0.15 because the left stick drives *keys*
     * here, not an analogue axis: a worn stick resting at 0.18 would hold `W`
     * down forever, and the failure looks like the guest being possessed rather
     * than like a hardware problem.
     */
    val deadzone: Float = DEFAULT_DEADZONE,
    /** Cursor pixels per second at full look-stick deflection. */
    val lookSpeed: Float = DEFAULT_LOOK_SPEED,
    /**
     * An analogue trigger past this counts as pressed.
     *
     * Not a preference and not exposed: it is where a pad's trigger stops being
     * noise, which is a fact about pads rather than a taste.
     */
    val triggerThreshold: Float = 0.5f,
) {
    /**
     * Hysteresis: once a direction is held, it releases only below this.
     *
     * **Derived rather than exposed.** Two independent sliders invite
     * `releaseZone > deadzone`, and the chatter that follows is the exact
     * failure the two-threshold design exists to prevent. The ratio reproduces
     * the original 0.18/0.25 pair exactly.
     */
    val releaseZone: Float get() = deadzone * RELEASE_RATIO

    companion object {
        const val DEFAULT_DEADZONE: Float = 0.25f
        const val DEFAULT_LOOK_SPEED: Float = 900f

        /** 0.18 / 0.25. Arithmetic from the two original defaults, not a tuning result. */
        const val RELEASE_RATIO: Float = 0.72f

        /** What the deadzone slider offers. */
        const val MIN_DEADZONE: Float = 0.10f
        const val MAX_DEADZONE: Float = 0.40f

        /** What the look-speed slider offers, in guest pixels a second. */
        const val MIN_LOOK_SPEED: Float = 200f
        const val MAX_LOOK_SPEED: Float = 2400f
    }
}

/**
 * Stick and button state in, [GuestInput] out. Same contract as [PointerGestures]:
 * no Android types and no clock of its own.
 *
 * The right stick is the one control that produces output while nothing is
 * happening, so it is driven by [tick] rather than by events — a held stick
 * generates no `MotionEvent`s at all, and a translator that only reacted to
 * events would move the cursor once and stop.
 */
class GamepadTranslator(
    var profile: GamepadProfile = GamepadProfile.Default,
    /**
     * A `var` so a sensitivity change lands without rebuilding the translator —
     * rebuilding drops every held key and leaves the guest holding it forever.
     */
    var config: GamepadConfig = GamepadConfig(),
) {

    private val held = mutableSetOf<GamepadControl>()
    private var lookX = 0f
    private var lookY = 0f
    private var lastTickMs = 0L

    /**
     * True while the look stick is off centre, which is exactly when [tick] can
     * produce anything. The caller drives its timer off this rather than ticking
     * forever: a pad plugged in and left alone should cost nothing.
     */
    val looking: Boolean get() = lookX != 0f || lookY != 0f

    /**
     * Which controls are physically down, whatever they are bound to.
     *
     * Published on the display seam so the binding editor can highlight a row
     * the moment its control is pressed — the only honest way to answer "which
     * of these twenty rows is the button under my thumb" on a pad nobody can
     * identify. A copy, because the caller holds it across events.
     */
    val heldControls: Set<GamepadControl> get() = held.toSet()

    /**
     * A digital button changed.
     *
     * Returns nothing for an unbound control, which is not the same as an
     * unknown one: [GamepadControl] is closed, so a pad with a button Android
     * does not name never reaches here at all.
     */
    fun onButton(control: GamepadControl, pressed: Boolean): List<GuestInput> {
        if (pressed) {
            if (!held.add(control)) return emptyList()
        } else {
            if (!held.remove(control)) return emptyList()
        }
        return emit(control, pressed)
    }

    /**
     * The sticks moved. [lx]/[ly]/[rx]/[ry] are −1..1 with y positive downwards,
     * which is Android's convention and the screen's.
     *
     * What each stick *does* is [GamepadProfile.sticks] rather than a hardwiring,
     * which is the whole of "bind a stick to look, or to keys, or to nothing".
     * Two sticks both set to [StickRole.Look] sum their deflections; the sum is
     * clamped, because two sticks pushed the same way is still full speed.
     */
    fun onSticks(lx: Float, ly: Float, rx: Float, ry: Float): List<GuestInput> {
        var sumX = 0f
        var sumY = 0f
        val out = mutableListOf<GuestInput>()
        for (stick in Stick.entries) {
            val x = if (stick == Stick.LEFT) lx else rx
            val y = if (stick == Stick.LEFT) ly else ry
            when (profile.roleOf(stick)) {
                StickRole.Look -> {
                    sumX += deaden(x)
                    sumY += deaden(y)
                    out += releaseHalfAxes(stick)
                }

                StickRole.Keys -> {
                    out += direction(stick.right, x, positive = true)
                    out += direction(stick.left, x, positive = false)
                    out += direction(stick.down, y, positive = true)
                    out += direction(stick.up, y, positive = false)
                }

                // Its deflection goes to the guest's own gamepad instead; see the role.
                StickRole.Pad -> out += releaseHalfAxes(stick)
                StickRole.None -> out += releaseHalfAxes(stick)
            }
        }
        lookX = sumX.coerceIn(-1f, 1f)
        lookY = sumY.coerceIn(-1f, 1f)
        return out
    }

    /** An analogue trigger, 0..1. Mapped to a digital control at [GamepadConfig.triggerThreshold]. */
    fun onTrigger(control: GamepadControl, value: Float): List<GuestInput> =
        onButton(control, value >= config.triggerThreshold)

    /** The D-pad, which most pads report as a pair of hat axes rather than as buttons. */
    fun onHat(x: Float, y: Float): List<GuestInput> = buildList {
        addAll(direction(GamepadControl.DPAD_RIGHT, x, positive = true))
        addAll(direction(GamepadControl.DPAD_LEFT, x, positive = false))
        addAll(direction(GamepadControl.DPAD_DOWN, y, positive = true))
        addAll(direction(GamepadControl.DPAD_UP, y, positive = false))
    }

    /**
     * Advance the look stick by however long has passed.
     *
     * Time-based rather than per-frame so the cursor moves at the same speed
     * whether the view is rendering at 165 Hz or dropping to 20 under load. The
     * first tick after a gap produces nothing: [lastTickMs] is unknown then, and
     * guessing gives one enormous jump.
     */
    fun tick(timeMs: Long): List<GuestInput> {
        val previous = lastTickMs
        lastTickMs = timeMs
        if (previous == 0L || timeMs <= previous) return emptyList()
        if (lookX == 0f && lookY == 0f) return emptyList()
        val seconds = (timeMs - previous).coerceAtMost(MAX_TICK_MS) / 1000f
        val dx = lookX * config.lookSpeed * seconds
        val dy = lookY * config.lookSpeed * seconds
        return listOf(GuestInput.MoveBy(dx, dy))
    }

    /** Release every held control. For a pad that disconnects, or a session stopping. */
    fun reset(): List<GuestInput> {
        val out = held.toList().flatMap { emit(it, pressed = false) }
        held.clear()
        lookX = 0f
        lookY = 0f
        lastTickMs = 0L
        return out
    }

    // — internals ------------------------------------------------------------------

    private fun emit(control: GamepadControl, pressed: Boolean): List<GuestInput> =
        when (val action = profile.bindings[control] ?: GamepadAction.None) {
            is GamepadAction.Key -> listOf(GuestInput.Key(action.keycode, action.keysym, pressed))
            is GamepadAction.Button -> listOf(GuestInput.Button(action.button, pressed))
            // The guest's own pad, reached over the socket rather than through
            // this seam. `XServerDisplay` applies it to the HID report.
            is GamepadAction.Pad -> emptyList()
            GamepadAction.None -> emptyList()
        }

    /**
     * One half-axis as a held control, with hysteresis.
     *
     * Two thresholds rather than one because a stick pushed to exactly the
     * deadzone chatters, and a chattering `W` in a game is a character that
     * stutters rather than walks.
     */
    /**
     * Let go of anything a stick that is no longer sending keys was holding.
     *
     * Without this, switching a stick to look while it is pushed leaves the
     * guest holding `W` with nothing left that can ever release it.
     */
    private fun releaseHalfAxes(stick: Stick): List<GuestInput> {
        if (stick.halfAxes.none { it in held }) return emptyList()
        return stick.halfAxes.filter { it in held }.flatMap { onButton(it, false) }
    }

    private fun direction(control: GamepadControl, value: Float, positive: Boolean): List<GuestInput> {
        val magnitude = if (positive) value else -value
        val on = control in held
        return when {
            !on && magnitude >= config.deadzone -> onButton(control, true)
            on && magnitude < config.releaseZone -> onButton(control, false)
            else -> emptyList()
        }
    }

    private fun deaden(value: Float): Float =
        if (abs(value) < config.deadzone) {
            0f
        } else {
            // Rescale so the cursor starts from a standstill at the deadzone edge
            // instead of jumping to a quarter speed the moment the stick moves.
            val sign = if (value > 0) 1f else -1f
            sign * (abs(value) - config.deadzone) / (1f - config.deadzone)
        }

    private companion object {
        /** A tick after the app was backgrounded should not fling the cursor. */
        const val MAX_TICK_MS = 100L
    }
}
