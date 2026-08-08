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
}

/** What one control does in the guest. */
sealed interface GamepadAction {
    data class Key(val keycode: Int, val keysym: Int = 0) : GamepadAction
    data class Button(val button: PointerButton) : GamepadAction
    data object None : GamepadAction
}

/**
 * A binding table, and the reason there is no XInput here.
 *
 * A Windows game that reads XInput wants an `XINPUT_GAMEPAD` struct, which means
 * a DLL inside the guest talking to something outside it — Winlator's
 * `WinHandler`, which is not vendored (see `XServerDisplay`). Without it the
 * only channel into the guest is the X server, and the X server carries keys and
 * a pointer. So a gamepad is a keyboard and mouse here, and the honest place to
 * say so is the type name.
 *
 * The default is the layout most Windows games are playable with unmodified:
 * left stick walks, right stick looks, and the face buttons land on the keys a
 * keyboard-and-mouse player would use.
 */
data class GamepadProfile(
    val name: String,
    val bindings: Map<GamepadControl, GamepadAction>,
) {
    companion object {
        val Default = GamepadProfile(
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
    val deadzone: Float = 0.25f,
    /** Hysteresis: once a direction is held, it releases only below this. */
    val releaseZone: Float = 0.18f,
    /** Cursor pixels per second at full right-stick deflection. */
    val lookSpeed: Float = 900f,
    /** An analogue trigger past this counts as pressed. */
    val triggerThreshold: Float = 0.5f,
)

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
    private val config: GamepadConfig = GamepadConfig(),
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
     */
    fun onSticks(lx: Float, ly: Float, rx: Float, ry: Float): List<GuestInput> {
        lookX = deaden(rx)
        lookY = deaden(ry)
        return buildList {
            addAll(direction(GamepadControl.STICK_L_RIGHT, lx, positive = true))
            addAll(direction(GamepadControl.STICK_L_LEFT, lx, positive = false))
            addAll(direction(GamepadControl.STICK_L_DOWN, ly, positive = true))
            addAll(direction(GamepadControl.STICK_L_UP, ly, positive = false))
        }
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
            GamepadAction.None -> emptyList()
        }

    /**
     * One half-axis as a held control, with hysteresis.
     *
     * Two thresholds rather than one because a stick pushed to exactly the
     * deadzone chatters, and a chattering `W` in a game is a character that
     * stutters rather than walks.
     */
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
