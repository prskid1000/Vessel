package app.vessel.input

import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * The arithmetic of laying a control out with a finger, as pure functions.
 *
 * It is here rather than inside the view that draws the overlay for the reason
 * everything else in this package is: a drag that puts a button half off the
 * screen is a bug you find by running the app on a phone, and a drag that puts a
 * button half off the screen *in a test* is one you find in a second. The view
 * keeps the gesture state — which finger is doing what — and asks this for every
 * number.
 */
object TouchEdit {

    /**
     * How big the resize handle is, as a fraction of the control's own radius.
     *
     * Bounded below by [MIN_HANDLE_PX] because a small button's handle would
     * otherwise be smaller than a fingertip, and a control you cannot resize
     * without first making it bigger is a joke.
     */
    private const val HANDLE_FRACTION = 0.5f
    private const val MIN_HANDLE_PX = 24f

    /** Where the resize handle sits: the control's lower-right, on the diagonal. */
    fun handleX(control: TouchControl, width: Float, height: Float): Float =
        control.centreX(width) + control.radiusPx(width, height) * DIAGONAL

    fun handleY(control: TouchControl, width: Float, height: Float): Float =
        control.centreY(height) + control.radiusPx(width, height) * DIAGONAL

    fun handleRadius(control: TouchControl, width: Float, height: Float): Float =
        max(MIN_HANDLE_PX, control.radiusPx(width, height) * HANDLE_FRACTION)

    /** Whether a finger landed on the resize handle rather than on the control. */
    fun onHandle(
        control: TouchControl,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
    ): Boolean {
        val r = handleRadius(control, width, height)
        val dx = x - handleX(control, width, height)
        val dy = y - handleY(control, width, height)
        return hypot(dx, dy) <= r
    }

    /**
     * The control moved so its centre is at [x], [y], and still wholly on screen.
     *
     * The finger takes the centre rather than keeping the offset it grabbed at,
     * which is the opposite of what a desktop drag does and is right here: a
     * thumb covers the whole control, so there is no visible grab point to
     * preserve, and centring makes "put it where my thumb is" exact.
     */
    fun moved(
        control: TouchControl,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
    ): TouchControl {
        if (width <= 0f || height <= 0f) return control
        return control.copy(cx = x / width, cy = y / height).clampedIn(width, height)
    }

    /**
     * The control resized so its handle is under [x], [y].
     *
     * The radius follows the diagonal distance from the centre, so a drag
     * outwards grows it whichever way the finger goes. Clamped to the sizes the
     * editor offers, then re-clamped into the surface: growing a control near an
     * edge pushes it back in rather than letting it hang off.
     */
    fun resized(
        control: TouchControl,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
    ): TouchControl {
        val short = min(width, height)
        if (short <= 0f) return control
        val dx = x - control.centreX(width)
        val dy = y - control.centreY(height)
        val radius = hypot(dx, dy) / DIAGONAL
        val size = (radius / short).coerceIn(TouchControls.MIN_SIZE, TouchControls.MAX_SIZE)
        return control.copy(size = size).clampedIn(width, height)
    }

    /**
     * A fresh control of this shape, in the middle of the surface and out of the
     * way of whatever is already there.
     *
     * **The middle, not a corner.** The design's own words: it lands in the
     * middle of the desktop, already selected, and the user drags it where their
     * thumb actually is. A control that placed itself cleverly would be a control
     * the user has to find.
     */
    fun placed(layout: TouchLayout, kind: TouchKind, role: StickRole = StickRole.Keys): TouchControl {
        val size = TouchControls.defaultSize(kind)
        var cx = 0.5f
        val cy = 0.5f
        var guard = 0
        while (layout.controls.any { near(it, cx, cy) } && guard < STEP_LIMIT) {
            cx = if (cx + STEP > 0.92f) 0.12f else cx + STEP
            guard++
        }
        return TouchControl(
            id = "c${System.nanoTime().toString(RADIX)}",
            kind = kind,
            cx = cx,
            cy = cy,
            size = size,
            role = role,
            label = "",
        )
    }

    private fun near(control: TouchControl, cx: Float, cy: Float): Boolean =
        kotlin.math.abs(control.cx - cx) < STEP && kotlin.math.abs(control.cy - cy) < STEP

    /** cos 45°: the handle is on the corner of the control's bounding box. */
    private const val DIAGONAL = 0.7071f

    private const val STEP = 0.07f
    private const val STEP_LIMIT = 12
    private const val RADIX = 36
}
