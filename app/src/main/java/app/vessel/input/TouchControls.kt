package app.vessel.input

import kotlin.math.min

/** The three shapes an on-screen control comes in. */
enum class TouchKind {
    /** One key or one pointer button, held while a finger is on it. */
    BUTTON,

    /** A thumbstick: four keys at the deadzone, or the pointer, per [TouchControl.role]. */
    STICK,

    /** Four keys arranged as a cross, any two of which can be held at once. */
    DPAD,
}

/**
 * One control on the touch overlay.
 *
 * **Positioned as fractions of the surface, never of the guest desktop.** The
 * desktop resolution is a container setting and the panel is 1264x2780; a thumb
 * rests where a thumb rests, and changing the guest resolution must not move a
 * button out from under it. The overlay is drawn and hit-tested in view pixels
 * only — `GuestViewport` is never consulted. That is the opposite decision from
 * `WindowDragBorders`, and the reason is that a window lives in the guest and a
 * thumb does not.
 *
 * @property cx centre, as a fraction of the surface's width.
 * @property cy centre, as a fraction of the surface's height.
 * @property size the control's **radius**, as a fraction of the surface's
 *   *shorter* edge. Shorter rather than each axis: on 1264x2780 a radius taken
 *   as a fraction of width and of height would make every button a 2.2:1
 *   ellipse.
 * @property opacity how solid it is drawn at, [TouchControls.MIN_OPACITY] to
 *   [TouchControls.MAX_OPACITY]. Per control, because the stick a thumb rests on
 *   wants to be fainter than the button it has to find.
 * @property action what a [TouchKind.BUTTON] sends.
 * @property role what a [TouchKind.STICK] is, reusing the physical stick's own
 *   vocabulary so the two cannot drift apart.
 * @property up the four directions of a [TouchKind.DPAD], and of a
 *   [TouchKind.STICK] whose role is [StickRole.Keys].
 */
data class TouchControl(
    val id: String,
    val kind: TouchKind,
    val cx: Float,
    val cy: Float,
    val size: Float = TouchControls.defaultSize(kind),
    val opacity: Float = TouchControls.DEFAULT_OPACITY,
    val label: String = "",
    val action: GamepadAction = GamepadAction.None,
    val role: StickRole = StickRole.Keys,
    val up: GamepadAction = GamepadAction.None,
    val down: GamepadAction = GamepadAction.None,
    val left: GamepadAction = GamepadAction.None,
    val right: GamepadAction = GamepadAction.None,
) {
    /**
     * The same control with every number inside the range the editor offers.
     *
     * Aspect-free, so it can run in the store where no surface exists: it keeps
     * a centre on screen and a size sane. Keeping a control *fully* hittable
     * needs the surface's shape and is [clampedIn]'s job.
     */
    fun sane(): TouchControl = copy(
        cx = cx.coerceIn(0f, 1f).orZero(),
        cy = cy.coerceIn(0f, 1f).orZero(),
        size = size.coerceIn(TouchControls.MIN_SIZE, TouchControls.MAX_SIZE).orDefault(
            TouchControls.defaultSize(kind),
        ),
        opacity = opacity.coerceIn(TouchControls.MIN_OPACITY, TouchControls.MAX_OPACITY)
            .orDefault(TouchControls.DEFAULT_OPACITY),
    )

    /**
     * The centre this control may actually sit at on a [width] x [height] surface,
     * so that none of it is off screen.
     *
     * The radius is a fraction of the shorter edge, so it is a *different*
     * fraction of each axis — which is why this needs the surface and the
     * aspect-free [sane] cannot do it. On a 927x421 landscape session a radius
     * of 0.14 is 14% of the height and 6.4% of the width, and clamping both to
     * 0.14 would forbid the whole outer sixth of the screen, where a thumb
     * actually rests.
     */
    fun clampedIn(width: Float, height: Float): TouchControl {
        if (width <= 0f || height <= 0f) return this
        val short = min(width, height)
        val rx = (size * short) / width
        val ry = (size * short) / height
        return copy(
            cx = cx.coerceIn(rx.coerceAtMost(0.5f), (1f - rx).coerceAtLeast(0.5f)),
            cy = cy.coerceIn(ry.coerceAtMost(0.5f), (1f - ry).coerceAtLeast(0.5f)),
        )
    }

    /** Radius in view pixels on a surface of this size. */
    fun radiusPx(width: Float, height: Float): Float = size * min(width, height)

    private fun Float.orZero(): Float = if (isNaN()) 0f else this

    private fun Float.orDefault(fallback: Float): Float = if (isNaN()) fallback else this
}

/**
 * A whole overlay.
 *
 * A wrapper type rather than a bare list so a per-orientation variant is an added
 * field rather than a schema break. The session route is orientation-locked to
 * `sensorLandscape`, so one layout per profile is enough today.
 */
data class TouchLayout(val controls: List<TouchControl> = emptyList()) {
    val isEmpty: Boolean get() = controls.isEmpty()

    fun sane(): TouchLayout = TouchLayout(controls.map { it.sane() })
}

/** The numbers the overlay is allowed to take, and what a fresh control gets. */
object TouchControls {
    /** Radius as a fraction of the shorter edge. 0.04 of 421 dp is a 34 dp target. */
    const val MIN_SIZE: Float = 0.04f
    const val MAX_SIZE: Float = 0.20f

    const val MIN_OPACITY: Float = 0.10f
    const val MAX_OPACITY: Float = 0.80f

    /**
     * 0.35.
     *
     * There is no auto-hide and no fade-on-idle anywhere in this feature: a
     * control that fades is one you cannot aim at.
     */
    const val DEFAULT_OPACITY: Float = 0.35f

    fun defaultSize(kind: TouchKind): Float = when (kind) {
        TouchKind.BUTTON -> 0.07f
        TouchKind.STICK -> 0.12f
        TouchKind.DPAD -> 0.11f
    }
}
