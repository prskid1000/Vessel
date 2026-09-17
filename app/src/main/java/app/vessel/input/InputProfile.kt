package app.vessel.input

/**
 * Everything one named arrangement of input is: the pad table, the two numbers
 * that tune a stick, and the touch overlay.
 *
 * A profile belongs to no container until one selects it, and several containers
 * may share one. It is the unit that is created, renamed, duplicated, exported
 * and deleted, which is why it carries an [id] separate from its [name] — a
 * rename must not orphan every container pointing at it.
 *
 * `app.vessel.input` and not `core`, because nothing here is Android and nothing
 * here is storage: [app.vessel.data.StoredInputProfile] is the on-disk near-copy,
 * and the mapping between the two is what lets this move while the format holds
 * still.
 */
data class InputProfile(
    val id: String,
    val name: String,
    val pad: GamepadProfile = GamepadProfile.Default,
    val config: GamepadConfig = GamepadConfig(),
    val touch: TouchLayout = TouchLayout(),
) {
    /** How many pad controls this profile actually sends something for. */
    val boundCount: Int
        get() = GamepadControl.entries.count {
            (pad.bindings[it] ?: GamepadAction.None) != GamepadAction.None
        }

    /**
     * Controls a user can bind right now: the half-axes of a stick that is not
     * sending keys cannot fire, and a row that cannot fire is worse than a
     * missing one.
     */
    val bindableControls: List<GamepadControl>
        get() = GamepadControl.entries.filter { control ->
            val stick = Stick.entries.firstOrNull { control in it.halfAxes }
            stick == null || pad.roleOf(stick) == StickRole.Keys
        }

    /**
     * Whether this is the profile a container falls back to.
     *
     * True of an *edited* default too: the id is what carries the meaning, and
     * the only meaning left is that it cannot be deleted. Renaming it, rebinding
     * it and rearranging its overlay are all ordinary edits.
     */
    val isBuiltInDefault: Boolean get() = id == DEFAULT_ID

    /**
     * Whether this is one of the seeded profiles, which cannot be deleted.
     *
     * [DEFAULT_ID] because something has to resolve when a container names
     * nothing; [KEYBOARD_ID] because it is seeded too, and a delete undone by
     * the next read is not a delete.
     */
    val isBuiltIn: Boolean get() = id == DEFAULT_ID || id == KEYBOARD_ID

    /**
     * What this profile resets to.
     *
     * Its own seed when it is one of the seeded profiles, and the pad otherwise.
     * **`Reset layout` used to name [TouchLayouts.Gamepad] outright**, so a
     * keyboard profile reset itself into a controller -- the one arrangement its
     * user had chosen against.
     *
     * A profile of the user's own falls back to the pad, which is a guess: there
     * is nothing recorded about which seed it was duplicated from. Reset is a
     * button of last resort on a layout that is already a mess, and offering the
     * shipped controller is better than offering nothing.
     */
    val seed: InputProfile get() = builtIn.firstOrNull { it.id == id } ?: Default

    /**
     * The overlay as it will actually behave: every pad-linked control resolved
     * against the pad table.
     *
     * **This is what makes the touch overlay and a controller one feature rather
     * than two.** A control carrying [TouchControl.pad] or
     * [TouchControl.padStick] stores no binding of its own; it borrows the pad
     * table's, which means rebinding `A` in the Pad tab moves the glass button
     * too, and a stick set to Look on the pad is a look pad on the glass without
     * anything having to say so twice.
     *
     * Everything that draws, hit-tests or translates the overlay reads this;
     * [touch] is only what is *stored*, and the link survives in it so a later
     * rebinding still reaches the control.
     */
    val overlay: TouchLayout
        get() {
            if (touch.controls.none { it.pad != null || it.padStick != null }) return touch
            return TouchLayout(touch.controls.map { resolve(it) })
        }

    private fun resolve(control: TouchControl): TouchControl {
        val stick = control.padStick
        if (stick != null) {
            return control.copy(
                role = pad.roleOf(stick),
                up = bound(stick.up),
                down = bound(stick.down),
                left = bound(stick.left),
                right = bound(stick.right),
            )
        }
        val linked = control.pad ?: return control
        // A d-pad names one of its four directions and takes all four, because a
        // cross is one control with four bindings and the model has one field.
        if (control.kind == TouchKind.DPAD) {
            return control.copy(
                up = bound(GamepadControl.DPAD_UP),
                down = bound(GamepadControl.DPAD_DOWN),
                left = bound(GamepadControl.DPAD_LEFT),
                right = bound(GamepadControl.DPAD_RIGHT),
            )
        }
        return control.copy(action = bound(linked))
    }

    private fun bound(control: GamepadControl): GamepadAction =
        pad.bindings[control] ?: GamepadAction.None

    companion object {
        /**
         * The id of the profile a container falls back to.
         *
         * A container with no `profileId`, and a container whose `profileId`
         * names a profile that has since been deleted, both resolve here — and
         * neither is rewritten, because a stale id is ordinary rather than
         * corruption. An untouched container therefore produces byte-identically
         * what it produced before this feature existed.
         */
        const val DEFAULT_ID: String = "default"

        /**
         * **A whole controller, drawn on the glass — and it cannot be deleted or
         * renamed.**
         *
         * The default used to be a keyboard-and-mouse layout, and that was the
         * wrong shipped answer for this device. Almost nobody here has a pad
         * paired, so the thing a fresh container most needs is *a pad*: both
         * sticks, the d-pad, four face buttons, four shoulders and triggers, the
         * two system buttons and the two stick clicks, laid out where thumbs
         * actually go. See [TouchLayouts.Gamepad].
         *
         * Every control on it is a pad control rather than a key — see
         * [InputProfile.overlay] — so the overlay and a physical controller are
         * one table seen twice, and the Pad tab governs both.
         *
         * Keyboard and mouse is not gone: it is [TouchLayouts.Wasd], one of the
         * stock layouts any profile can adopt. What it lost is its status as the
         * thing a container starts with.
         *
         * **This is the seed, not a constant the product defends.** It is what
         * [DEFAULT_ID] resolves to until something writes a profile under that
         * id, after which the written one is the default and this is only what
         * `Reset all` restores. Editing it is an ordinary edit; the single thing
         * its id still means is that `InputProfileRepository.delete` refuses it,
         * which is what makes "there is always a profile" true.
         */
        val Default: InputProfile = InputProfile(
            id = DEFAULT_ID,
            name = "Virtual controller",
            pad = GamepadProfile.Default,
            touch = TouchLayouts.Gamepad,
        )

        /**
         * The id of the second profile that is always there.
         *
         * It shares one property with [DEFAULT_ID] -- it cannot be deleted,
         * because a profile that reappears after being deleted is worse than one
         * that refuses -- and none of the others: nothing resolves here, and a
         * container that names nothing still gets [Default].
         */
        const val KEYBOARD_ID: String = "keyboard"

        /**
         * **Keyboard and mouse, for the games that cannot read a pad at all.**
         *
         * Caribbean Legend is why this is seeded rather than left as a layout to
         * be found: it has no native controller support -- nothing about a
         * gamepad in its configuration, and the community plays it through Steam
         * Input mapping a pad onto keys -- so a fresh container opens
         * [TouchLayouts.Gamepad] on a game that reads none of it. Every control
         * here sends a key or a mouse button instead.
         *
         * It carries [TouchLayouts.KeyboardAndMouse], which is
         * [TouchLayouts.Gamepad]'s geometry with keys on it, so switching
         * between the two moves nothing under either thumb.
         *
         * The pad table is the default one and the overlay does not use it -- no
         * control here carries `pad` or `padStick`, so nothing borrows it. It is
         * there for a real controller plugged into a container running this
         * profile, which still deserves a working table.
         */
        val Keyboard: InputProfile = InputProfile(
            id = KEYBOARD_ID,
            name = "Keyboard and mouse",
            pad = GamepadProfile.Default,
            touch = TouchLayouts.KeyboardAndMouse,
        )

        /** The profiles that are always offered, in the order they are shown. */
        val builtIn: List<InputProfile> = listOf(Default, Keyboard)
    }
}
