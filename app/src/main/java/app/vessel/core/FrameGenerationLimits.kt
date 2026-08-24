package app.vessel.core

/**
 * How high the frame generation multiple may go, for a given container.
 *
 * **The ceiling is not one number, and the two modes disagree about which way it
 * points.** A table baked into the manifest would be wrong for one of them, and
 * wrong again on the first phone with a different panel.
 *
 * In `efficiency` the guest is capped at `limit / multiple`, so the multiple
 * *slows the game down* and the constraint is not to slow it too far. Everything
 * this pipeline does degrades with the distance between the two real frames it
 * interpolates across: the block matcher's search range, the area uncovered
 * during the interval, and the assumption that anything moved in a straight line
 * while it passed. Measured on this project at a 66 ms gap -- a 15 fps guest --
 * displacing a chosen vector by two whole search blocks changes the matching cost
 * by less than the margin across 73 to 95 per cent of the frame. That is the
 * floor: below 15 fps the field stops describing the scene.
 *
 * In `smoothness` the guest keeps the whole limit and is never slowed, so that
 * constraint does not exist. The binding one is the panel: the compositor
 * presents `limit * multiple` and there has to be a refresh for each. On a 165 Hz
 * screen a 120 fps limit at 2x asks for 240 frames a second, and half of them
 * have nowhere to go.
 *
 * So the two ceilings point in opposite directions -- a high limit *earns* a high
 * multiple in efficiency and *forbids* one in smoothness -- and only one of them
 * is device-independent:
 *
 * ```
 *              efficiency          smoothness (165 Hz)   smoothness (240 Hz)
 *   24 fps        2x                     6x                    8x
 *   30 fps        2x                     5x                    8x
 *   60 fps        4x                     2x                    4x
 *   90 fps        6x                     1x                    2x
 *  120 fps        8x                     1x                    2x
 * ```
 *
 * Both are arithmetic over numbers the device can be asked for, so neither is
 * written down. A 240 Hz phone widens the right-hand column by itself.
 */
object FrameGenerationLimits {

    /**
     * The slowest the guest may be driven before interpolation stops meaning
     * anything, in frames per second.
     *
     * <p>Fifteen, because that is where this project measured the field becoming
     * ambiguous across most of the frame, and it is the number that reproduces
     * the intended ladder exactly: 120/8, 90/6, 60/4 and 30/2 are all 15.
     */
    const val MIN_GUEST_FPS: Int = 15

    /**
     * The highest multiple the pipeline itself will run.
     *
     * <p>Not a quality judgement -- the synthesiser's own clamp -- and the only
     * reason the ladder stops where it does. Raising it and adding entries to the
     * manifest is all a faster panel would need.
     */
    const val MAX_MULTIPLE: Int = 8

    /**
     * The largest multiple worth offering.
     *
     * @param fpsLimit the container's `display.fpsLimit`, or null for uncapped
     * @param divides true for `efficiency`, false for `smoothness`
     * @param panelHz the fastest refresh the display offers, or 0 if unknown
     */
    fun ceiling(fpsLimit: Int?, divides: Boolean, panelHz: Int): Int {
        val limit = fpsLimit?.takeIf { it > 0 }
        // Uncapped: the guest's rate is unknown, so neither rule can be applied
        // and the pipeline's own runtime clamp is the only honest arbiter.
            ?: return MAX_MULTIPLE

        val bound = if (divides) {
            // Never slow the guest below the point where the field stops
            // describing the scene. Two is the floor rather than the result,
            // because a 24 fps limit divides to 12 and the alternative is
            // offering nothing at all on a container that works.
            maxOf(2, limit / MIN_GUEST_FPS)
        } else {
            // Never present more frames a second than the panel can show. One
            // means frame generation cannot help at this limit on this screen,
            // which is a true answer and better than a promise it cannot keep.
            if (panelHz > 0) maxOf(1, panelHz / limit) else MAX_MULTIPLE
        }
        return bound.coerceIn(1, MAX_MULTIPLE)
    }

    /**
     * Narrow a manifest option list to what this container can actually use.
     *
     * <p>Non-numeric options -- `off` -- are always kept: turning the feature off
     * is valid at every limit.
     */
    fun allowed(offered: List<String>, fpsLimit: Int?, divides: Boolean, panelHz: Int): List<String> {
        val top = ceiling(fpsLimit, divides, panelHz)
        return offered.filter { option ->
            val multiple = option.toIntOrNull() ?: return@filter true
            multiple <= top
        }
    }

    /**
     * Clamp a stored value to what is currently allowed.
     *
     * <p>A container saved at 8x and later moved to a 30 fps limit still holds
     * "8" in its document, and nothing on the launch path would otherwise notice.
     * Returns the value unchanged when it is already within range, so the common
     * case is free and the document is never rewritten behind the user's back.
     */
    fun clamp(multiple: Int, fpsLimit: Int?, divides: Boolean, panelHz: Int): Int {
        if (multiple < 2) return multiple
        val top = ceiling(fpsLimit, divides, panelHz)
        return if (multiple <= top) multiple else top
    }
}
