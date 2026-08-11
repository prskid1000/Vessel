package app.vessel.input

/**
 * Fingers on the overlay in, [GuestInput] out. Same contract as
 * [PointerGestures] and [GamepadTranslator]: no Android types, no clock of its
 * own, no `XServer`.
 *
 * **It does not reimplement stick hysteresis, and that is the point.** An
 * on-screen stick computes a normalised deflection and feeds a second
 * [GamepadTranslator] through [GamepadTranslator.onSticks], so there is exactly
 * one implementation of the deadzone, the release zone and the look tick in the
 * product — and a thumb on the glass behaves identically to a thumb on a pad by
 * construction rather than by two pieces of arithmetic agreeing.
 *
 * The overlay may hold at most one stick, one look pad and one d-pad, which is
 * what lets the whole of it map onto a single [GamepadProfile]: the stick is the
 * left stick, the look pad is the right, and the d-pad is the d-pad. Buttons are
 * unlimited and are handled here directly, because a button is one edge and
 * there is nothing about it a translator could add.
 *
 * A pointer is bound to the control it landed on for its whole life. **Sliding
 * off a button does not release it** — that is what a physical pad does under a
 * thumb, and the opposite makes a sprint button unusable — and sliding off a
 * stick keeps steering, clamped to the radius.
 */
class TouchControlTranslator(
    layout: TouchLayout = TouchLayout(),
    config: GamepadConfig = GamepadConfig(),
) {

    /**
     * The stick and look-pad half of the overlay, as an ordinary pad.
     *
     * Its profile is synthesised from [layout] rather than being a profile a user
     * ever sees: the on-screen stick's four bindings become the left stick's four
     * half-axes, and a look pad becomes the right stick's role.
     */
    private val sticks = GamepadTranslator(padProfileOf(layout), config)

    /**
     * Which control drives each of the two stick slots.
     *
     * By identity rather than by role, because a full-controller overlay can have
     * both sticks sending keys — and then "the one that is not Look" names two
     * controls and the right stick would silently drive the left.
     */
    private var leftStickId: String? = leftStickOf(layout)?.id
    private var rightStickId: String? = rightStickOf(layout)?.id

    /** Which control each finger currently on the overlay landed on. */
    private val fingers = mutableMapOf<Int, String>()

    /**
     * How many fingers are holding each action down.
     *
     * Two buttons bound to `Space` are ordinary — a layout can have a big one for
     * a thumb and a small one for a finger — and without a count, lifting either
     * would release a key the other is still holding.
     */
    private val holding = mutableMapOf<GamepadAction, Int>()

    private var stickX = 0f
    private var stickY = 0f
    private var lookX = 0f
    private var lookY = 0f
    private var hatX = 0f
    private var hatY = 0f

    var layout: TouchLayout = layout
        private set

    var config: GamepadConfig
        get() = sticks.config
        set(value) {
            sticks.config = value
        }

    /** True while a look pad is off centre — exactly when [tick] can produce anything. */
    val looking: Boolean get() = sticks.looking

    /**
     * The overlay as a gamepad, for the guest's HID pad rather than for keys.
     *
     * **Why this exists at all.** Everything else this class produces is
     * [GuestInput] — keystrokes and pointer motion — which is the right answer
     * for a guest with no gamepad and the wrong one for a game that reads
     * XInput and nothing else. A control in the virtual-controller layout
     * already knows the pad control it *is* ([TouchControl.pad],
     * [TouchControl.padStick]); this hands that identity out unchanged so the
     * session can put it on the wire, instead of the overlay being a keyboard
     * wearing a controller's clothes.
     *
     * A snapshot rather than an event: the deflections below are the same
     * fields [apply] already maintains, so there is one place a stick's position
     * is known and no second copy to drift. Empty when the layout has no pad
     * identities at all, which is every layout a user built by hand.
     */
    fun padSnapshot(): TouchPadSnapshot = TouchPadSnapshot(
        leftX = stickX,
        leftY = stickY,
        rightX = lookX,
        rightY = lookY,
        hatX = hatX,
        hatY = hatY,
        // Held by *identity*, not by action: two controls bound to the same key
        // are one key and two different pad buttons, and `holding` counts the
        // former. A finger on a control with no pad identity contributes
        // nothing, which is what makes a hand-built layout inert here.
        pressed = fingers.values
            .mapNotNull { id -> layout.byId(id)?.pad }
            .toSet(),
    )

    /** Whether any finger is on the overlay at all. */
    val busy: Boolean get() = fingers.isNotEmpty()

    /**
     * Point the overlay at a different set of controls.
     *
     * **Releases everything first.** A control rebound or moved out from under a
     * finger would otherwise send its press under the old layout and never send a
     * release under either, and nothing left in the system could let it go — the
     * same failure `SessionDisplayServer.setInputProfile` exists to prevent for
     * the pad.
     */
    fun setLayout(next: TouchLayout): List<GuestInput> {
        val released = reset()
        layout = next
        leftStickId = leftStickOf(next)?.id
        rightStickId = rightStickOf(next)?.id
        sticks.profile = padProfileOf(next)
        return released
    }

    /** A finger landed on [control]. */
    fun onDown(
        pointerId: Int,
        control: TouchControl,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
    ): List<GuestInput> {
        // A second DOWN for a pointer already tracked is a dropped UP somewhere
        // upstream. Let go of what it was on rather than leaking the hold.
        val out = mutableListOf<GuestInput>()
        if (fingers.containsKey(pointerId)) out += onUp(pointerId)
        fingers[pointerId] = control.id
        out += when (control.kind) {
            TouchKind.BUTTON -> press(control.action)
            TouchKind.STICK, TouchKind.DPAD -> steer(control, x, y, width, height)
        }
        return out
    }

    /** A tracked finger moved. Silent for a button, which does not care. */
    fun onMove(pointerId: Int, x: Float, y: Float, width: Float, height: Float): List<GuestInput> {
        val control = layout.byId(fingers[pointerId]) ?: return emptyList()
        if (control.kind == TouchKind.BUTTON) return emptyList()
        return steer(control, x, y, width, height)
    }

    /** A tracked finger lifted. */
    fun onUp(pointerId: Int): List<GuestInput> {
        val control = layout.byId(fingers.remove(pointerId)) ?: return emptyList()
        return when (control.kind) {
            TouchKind.BUTTON -> release(control.action)
            TouchKind.STICK, TouchKind.DPAD -> centre(control)
        }
    }

    /**
     * Advance a look pad by however long has passed. See [GamepadTranslator.tick]
     * — the same method, for the same reason: a thumb held still on a look pad
     * generates no events at all, so the cursor needs a heartbeat.
     */
    fun tick(timeMs: Long): List<GuestInput> = sticks.tick(timeMs)

    /**
     * Let go of everything. For a cancelled gesture, a hidden overlay, a stopping
     * session.
     *
     * The button releases are worked out here rather than delegated, because the
     * pad translator does not know a button exists — it only ever sees the two
     * sticks and the hat this class synthesises for it.
     */
    fun reset(): List<GuestInput> {
        val buttons = holding.keys.toList()
        fingers.clear()
        holding.clear()
        stickX = 0f
        stickY = 0f
        lookX = 0f
        lookY = 0f
        hatX = 0f
        hatY = 0f
        return sticks.reset() + buttons.flatMap { edges(it, pressed = false) }
    }

    // — internals ------------------------------------------------------------------

    private fun press(action: GamepadAction): List<GuestInput> {
        val count = holding.getOrDefault(action, 0)
        holding[action] = count + 1
        return if (count == 0) edges(action, pressed = true) else emptyList()
    }

    private fun release(action: GamepadAction): List<GuestInput> {
        val count = holding.getOrDefault(action, 0)
        if (count <= 0) return emptyList()
        if (count == 1) holding.remove(action) else holding[action] = count - 1
        return if (count == 1) edges(action, pressed = false) else emptyList()
    }

    private fun edges(action: GamepadAction, pressed: Boolean): List<GuestInput> = when (action) {
        is GamepadAction.Key -> listOf(GuestInput.Key(action.keycode, action.keysym, pressed))
        is GamepadAction.Button -> listOf(GuestInput.Button(action.button, pressed))
        GamepadAction.None -> emptyList()
    }

    /**
     * A finger's offset from a stick's centre, as a deflection the pad translator
     * understands.
     *
     * Clamped to the radius rather than to the finger: a thumb that slides past
     * the ring is still asking for full deflection in that direction, and letting
     * the number grow past 1 would make the look speed a function of how far off
     * the control the thumb wandered.
     */
    private fun steer(
        control: TouchControl,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
    ): List<GuestInput> {
        val radius = control.radiusPx(width, height)
        if (radius <= 0f) return emptyList()
        val dx = ((x - control.centreX(width)) / radius).coerceIn(-1f, 1f)
        val dy = ((y - control.centreY(height)) / radius).coerceIn(-1f, 1f)
        return apply(control, dx, dy)
    }

    /** The thumb left: the stick springs back, and whatever it held is let go. */
    private fun centre(control: TouchControl): List<GuestInput> = apply(control, 0f, 0f)

    private fun apply(control: TouchControl, dx: Float, dy: Float): List<GuestInput> =
        when {
            control.kind == TouchKind.DPAD -> {
                hatX = dx
                hatY = dy
                sticks.onHat(hatX, hatY)
            }

            control.id == rightStickId -> {
                lookX = dx
                lookY = dy
                sticks.onSticks(stickX, stickY, lookX, lookY)
            }

            else -> {
                stickX = dx
                stickY = dy
                sticks.onSticks(stickX, stickY, lookX, lookY)
            }
        }

    private companion object {
        /**
         * Which control drives each of the two stick slots.
         *
         * A control that *is* the pad's left or right stick says so and takes
         * that slot. Anything else falls back on its role, which is what makes a
         * hand-built layout — one stick and one look pad, neither claiming a
         * side — behave the way it always did: the stick walks, the pad looks.
         */
        fun leftStickOf(layout: TouchLayout): TouchControl? =
            layout.controls.firstOrNull { it.padStick == Stick.LEFT }
                ?: layout.controls.firstOrNull {
                    it.kind == TouchKind.STICK && it.padStick == null && it.role != StickRole.Look
                }

        fun rightStickOf(layout: TouchLayout): TouchControl? =
            layout.controls.firstOrNull { it.padStick == Stick.RIGHT }
                ?: layout.controls.firstOrNull {
                    it.kind == TouchKind.STICK && it.padStick == null && it.role == StickRole.Look
                }

        /**
         * The overlay's sticks and d-pad, as a [GamepadProfile].
         *
         * Each slot takes the role its own control carries rather than a fixed
         * one, because a full-controller overlay can have a right stick sending
         * keys just as a pad can. A slot with no control is [StickRole.None],
         * which is silent.
         */
        fun padProfileOf(layout: TouchLayout): GamepadProfile {
            val left = leftStickOf(layout)
            val right = rightStickOf(layout)
            val dpad = layout.controls.firstOrNull { it.kind == TouchKind.DPAD }

            val bindings = buildMap {
                left?.let {
                    put(GamepadControl.STICK_L_UP, it.up)
                    put(GamepadControl.STICK_L_DOWN, it.down)
                    put(GamepadControl.STICK_L_LEFT, it.left)
                    put(GamepadControl.STICK_L_RIGHT, it.right)
                }
                right?.let {
                    put(GamepadControl.STICK_R_UP, it.up)
                    put(GamepadControl.STICK_R_DOWN, it.down)
                    put(GamepadControl.STICK_R_LEFT, it.left)
                    put(GamepadControl.STICK_R_RIGHT, it.right)
                }
                dpad?.let {
                    put(GamepadControl.DPAD_UP, it.up)
                    put(GamepadControl.DPAD_DOWN, it.down)
                    put(GamepadControl.DPAD_LEFT, it.left)
                    put(GamepadControl.DPAD_RIGHT, it.right)
                }
            }

            return GamepadProfile(
                name = "overlay",
                bindings = bindings,
                sticks = mapOf(
                    Stick.LEFT to (left?.role ?: StickRole.None),
                    Stick.RIGHT to (right?.role ?: StickRole.None),
                ),
            )
        }
    }
}
