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

    /**
     * Stick-click controls a long press has latched down, by control id.
     *
     * A thumb cannot press a stick it is steering with, which is why L3 and R3
     * are separate buttons at all -- and it cannot *hold* one either, because
     * that same thumb is on the stick. Sprint is the case that makes it plain:
     * hold L3 and steer, on glass, with one thumb, is not a thing a hand does.
     *
     * So a long press latches the button down and the finger leaves; another
     * long press lets it go. A short press stays exactly what it always was.
     */
    private val latched = mutableSetOf<String>()

    /** When each tracked finger landed, for telling a long press from a tap. */
    private val downAt = mutableMapOf<Int, Long>()

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
        // **The binding first, then the identity.** A control bound to
        // `GamepadAction.Pad` says outright which control it sends, and that has
        // to win: it is how the glass `A` is made to send `B`. Identity is the
        // fallback for a control that carries a pad control but no explicit
        // binding, which is every layout written before the binding existed.
        //
        // Note this is deliberately *not* keyed on the action for the other
        // kinds: two controls bound to the same key are one key and two
        // different pad buttons. A finger on a control that is neither bound to
        // a pad control nor is one contributes nothing here, which is what keeps
        // a hand-built keyboard layout out of the guest's gamepad.
        // Latched ids as well as fingers: a latched button is held as far as
        // the guest is concerned, and the finger that latched it is long gone.
        pressed = (fingers.values + latched)
            .mapNotNull { id ->
                val control = layout.byId(id) ?: return@mapNotNull null
                (control.action as? GamepadAction.Pad)?.control ?: control.pad
            }
            .toSet(),
    )

    /** Controls a long press is holding down, so they can be drawn as held. */
    val latchedIds: Set<String> get() = latched

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
        atMs: Long = 0L,
    ): List<GuestInput> {
        // A second DOWN for a pointer already tracked is a dropped UP somewhere
        // upstream. Let go of what it was on rather than leaking the hold.
        val out = mutableListOf<GuestInput>()
        if (fingers.containsKey(pointerId)) out += onUp(pointerId, atMs)
        fingers[pointerId] = control.id
        downAt[pointerId] = atMs
        out += when (control.kind) {
            // A latched control is already down. Pressing it again would take a
            // second reference on the key and the matching release would never
            // come, so the finger that arrives to *un*latch it must not press.
            TouchKind.BUTTON ->
                if (control.id in latched) emptyList() else press(control.action)

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

    /**
     * A tracked finger lifted.
     *
     * Where a long press on a latchable button is decided: held past
     * [LATCH_HOLD_MS] it toggles, and anything shorter is the press it always
     * was. A tap on a latched button is deliberately *not* a release -- the way
     * out is the way in, and a game where the same button is tapped in play
     * would otherwise drop the latch by accident.
     */
    fun onUp(pointerId: Int, atMs: Long = 0L): List<GuestInput> {
        val control = layout.byId(fingers.remove(pointerId)) ?: run {
            downAt.remove(pointerId)
            return emptyList()
        }
        val heldFor = atMs - (downAt.remove(pointerId) ?: atMs)
        return when (control.kind) {
            TouchKind.BUTTON -> when {
                !latches(control) -> release(control.action)
                heldFor < LATCH_HOLD_MS ->
                    if (control.id in latched) emptyList() else release(control.action)

                control.id in latched -> {
                    latched -= control.id
                    release(control.action)
                }

                else -> {
                    latched += control.id
                    emptyList()
                }
            }

            TouchKind.STICK, TouchKind.DPAD -> centre(control)
        }
    }

    /**
     * Whether a long press on this control latches it down.
     *
     * The four a hand cannot hold and play at the same time. `L3` and `R3` are
     * pressed by a thumb that is steering with the stick they belong to -- sprint
     * is the ordinary case, and hold-and-steer with one thumb on glass is not a
     * thing a hand does. `L1` and `L2` are the aim and the alt-fire an index
     * finger holds for as long as a fight lasts, and on a phone that finger is
     * also what the device is resting on.
     *
     * The control carries the answer, so a layout decides rather than this file:
     * which buttons a game wants held is the game s business, and a flight sim
     * holds a trigger nobody else does. The built-in pad turns it on for L1, L2,
     * L3 and R3 -- the ones a hand cannot hold and play at the same time -- and
     * leaves every other button alone.
     *
     * `R1` and `R2` are left off there on purpose: on the right they are fire,
     * and a weapon that keeps firing after the finger leaves is a different
     * feature with a different failure mode. A user who wants it can turn it on.
     */
    private fun latches(control: TouchControl): Boolean =
        control.kind == TouchKind.BUTTON && control.latching

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
        downAt.clear()
        // Latches go with everything else. A latch is a key the guest is holding
        // with no finger anywhere near it, so a session that stops or a layout
        // that changes underneath one would leave it held with nothing left in
        // the system able to let it go.
        latched.clear()
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
        // Travels the pad socket, not the X11 seam: `padSnapshot` carries it.
        is GamepadAction.Pad -> emptyList()
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
         * How long a press has to last to latch, in milliseconds.
         *
         * Three seconds is long enough that no press made in play reaches it --
         * a tapped button is tens of milliseconds and a deliberate hold in a
         * fight is under one second -- and short enough to be worth waiting out
         * on purpose. The same threshold releases it, so there is one gesture to
         * learn rather than two.
         */
        const val LATCH_HOLD_MS = 3_000L

        /**
         * Which control drives each of the two stick slots.
         *
         * The rule itself lives on [TouchLayout], because the editor needs the
         * same answer to know whether a side is free, and two copies of it would
         * drift the first time either was touched.
         */
        fun leftStickOf(layout: TouchLayout): TouchControl? = layout.stickFor(Stick.LEFT)

        fun rightStickOf(layout: TouchLayout): TouchControl? = layout.stickFor(Stick.RIGHT)

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
