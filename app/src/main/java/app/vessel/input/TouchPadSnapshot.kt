package app.vessel.input

/**
 * The on-screen controller's position, as a gamepad rather than as keystrokes.
 *
 * **A plain value in `app.vessel.input`, deliberately.** The thing that puts
 * this on the wire lives in `app.vessel.display` and knows about sockets and
 * HID reports; the thing that produces it knows about fingers and rectangles.
 * Neither should have to import the other to describe a stick's position, so
 * the description is its own type and both depend on it instead.
 *
 * Deflections are -1..1 with the screen's sign convention — right and *down*
 * positive, the same as [GamepadTranslator] takes from a physical stick, so a
 * glass stick and a real one need no different handling anywhere downstream.
 *
 * @property pressed the pad controls a finger is currently holding, by
 *   identity. A control the user placed and bound themselves has no pad
 *   identity and is absent, which is what keeps a hand-built layout out of the
 *   guest's gamepad entirely.
 */
data class TouchPadSnapshot(
    val leftX: Float = 0f,
    val leftY: Float = 0f,
    val rightX: Float = 0f,
    val rightY: Float = 0f,
    val hatX: Float = 0f,
    val hatY: Float = 0f,
    val pressed: Set<GamepadControl> = emptySet(),
) {
    /** Nothing held and nothing deflected — the overlay contributing no pad at all. */
    val idle: Boolean
        get() = pressed.isEmpty() &&
            leftX == 0f && leftY == 0f && rightX == 0f && rightY == 0f &&
            hatX == 0f && hatY == 0f
}
