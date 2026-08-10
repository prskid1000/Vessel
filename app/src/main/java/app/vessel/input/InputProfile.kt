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

    val isBuiltInDefault: Boolean get() = id == DEFAULT_ID

    companion object {
        /**
         * The id of the profile that is never written to disk.
         *
         * A container with no `profileId`, and a container whose `profileId`
         * names a profile that has since been deleted, both resolve here — and
         * neither is rewritten, because a stale id is ordinary rather than
         * corruption. An untouched container therefore produces byte-identically
         * what it produced before this feature existed.
         */
        const val DEFAULT_ID: String = "default"

        val Default: InputProfile = InputProfile(
            id = DEFAULT_ID,
            name = GamepadProfile.Default.name,
            pad = GamepadProfile.Default,
        )
    }
}
