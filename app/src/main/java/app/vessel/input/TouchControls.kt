package app.vessel.input

import kotlin.math.abs
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
    /**
     * The pad control this **is**, when it is one.
     *
     * A control carrying this does not hold its own binding: what it sends is
     * whatever the profile's pad table binds that control to, resolved by
     * [InputProfile.overlay]. That is what makes an on-screen A button and a
     * physical A button one thing rather than two tables to keep in step — bind
     * `A` in the Pad tab and the glass button follows, and the day a real
     * controller reaches the guest as a controller, both arrive together.
     *
     * Null for a control the user placed and bound themselves, which is every
     * control in a layout built by hand.
     */
    val pad: GamepadControl? = null,

    /** The whole stick this **is**, for the two analogue sticks. See [pad]. */
    val padStick: Stick? = null,
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

    /** Centre in view pixels on a surface of this size. */
    fun centreX(width: Float): Float = cx * width

    fun centreY(height: Float): Float = cy * height

    /**
     * Whether a finger at [x], [y] is on this control.
     *
     * **A square control is hit-tested as a square and a round one as a circle,**
     * because the drawn shape is the promise: a finger just off the corner of a
     * round button that lit up anyway is a press the user cannot account for, and
     * a d-pad whose corners do nothing is a d-pad you cannot go diagonally on.
     */
    fun contains(x: Float, y: Float, width: Float, height: Float): Boolean {
        val r = radiusPx(width, height)
        if (r <= 0f) return false
        val dx = x - centreX(width)
        val dy = y - centreY(height)
        return if (round) dx * dx + dy * dy <= r * r else abs(dx) <= r && abs(dy) <= r
    }

    /** Round, except a d-pad, which is a cross drawn inside a square. */
    val round: Boolean get() = kind != TouchKind.DPAD

    /**
     * What this control is called in a list: the design's four kinds, out of the
     * three the model has.
     *
     * A look pad is not a fourth [TouchKind] because it is a stick whose whole
     * role is the pointer — exactly the distinction [StickRole] already draws for
     * a physical stick, and duplicating it as a kind would let a control be a
     * look pad *and* have four half-axis bindings, which is a state nothing can
     * act on.
     */
    val designation: String
        get() = when (kind) {
            TouchKind.BUTTON -> "Button"
            TouchKind.DPAD -> "D-pad"
            TouchKind.STICK -> when {
                padStick == Stick.LEFT -> "Left stick"
                padStick == Stick.RIGHT -> "Right stick"
                role == StickRole.Look -> "Look pad"
                else -> "Stick"
            }
        }

    /**
     * What the control is called in a list.
     *
     * A control that *is* a pad control is named after it — `A`, `L2 trigger`,
     * `Left stick click` — because "Button" twelve times over is a list that says
     * nothing. One the user placed keeps its shape as its name.
     */
    val title: String get() = pad?.padLabel() ?: designation

    /**
     * What this control sends, as one chip: `W A S D`, `Mouse look`, `Space`.
     *
     * Derived rather than read from [label] so a rebinding cannot leave the chip
     * saying what the control used to do. [label] survives as the free-text name
     * a stock layout gives a control before anything has been bound.
     */
    val bindingLabel: String
        get() = when (kind) {
            TouchKind.BUTTON -> X11KeyCatalog.label(action)
            TouchKind.STICK -> if (role == StickRole.Look) "Mouse look" else directionLabel()
            TouchKind.DPAD -> directionLabel()
        }

    private fun directionLabel(): String {
        val parts = listOf(up, left, down, right)
            .map { X11KeyCatalog.label(it) }
            .filter { it != X11KeyCatalog.UNBOUND }
        return if (parts.isEmpty()) X11KeyCatalog.UNBOUND else parts.joinToString(" ")
    }

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

    /**
     * Which control a finger at [x], [y] is on, or null for bare screen.
     *
     * **Last declared wins**, which is the same rule the drawing uses: the list is
     * painted in order, so the control on top of an overlap is the last one, and
     * the finger has to land on whatever it can see. Anywhere no control is, this
     * answers null and the touch goes through to the guest.
     */
    fun hitTest(x: Float, y: Float, width: Float, height: Float): TouchControl? =
        controls.lastOrNull { it.contains(x, y, width, height) }

    fun byId(id: String?): TouchControl? = controls.firstOrNull { it.id == id }

    /** Replace one control in place, keeping the draw order. */
    fun with(control: TouchControl): TouchLayout {
        val index = controls.indexOfFirst { it.id == control.id }
        if (index < 0) return TouchLayout(controls + control)
        return TouchLayout(controls.toMutableList().also { it[index] = control })
    }

    fun without(id: String): TouchLayout = TouchLayout(controls.filterNot { it.id == id })

    /**
     * Whether the overlay already has a control of this designation.
     *
     * A stick, a d-pad and a look pad are one each: a second of any of them would
     * be a second left stick, and the translator has exactly one to give it. A
     * button has no such limit, and neither does the editor.
     */
    fun has(designation: String): Boolean = controls.any { it.designation == designation }
}

/** A pad control's name, as the overlay's list and the binding rows both say it. */
fun GamepadControl.padLabel(): String = when (this) {
    GamepadControl.L2 -> "L2 trigger"
    GamepadControl.R2 -> "R2 trigger"
    GamepadControl.THUMB_L -> "Left stick click"
    GamepadControl.THUMB_R -> "Right stick click"
    GamepadControl.SELECT -> "Select"
    GamepadControl.START -> "Start"
    GamepadControl.DPAD_UP -> "D-pad up"
    GamepadControl.DPAD_DOWN -> "D-pad down"
    GamepadControl.DPAD_LEFT -> "D-pad left"
    GamepadControl.DPAD_RIGHT -> "D-pad right"
    else -> name
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

/**
 * The overlays a container can be given without drawing one.
 *
 * **Provided in code and copied into a profile rather than persisted as
 * defaults**, which is the same posture [GamepadProfile.Default] takes: an
 * untouched container writes nothing, and the bytes on disk stay identical to
 * what they were before this feature existed. The built-in default profile
 * carries [Wasd] for the same reason a fresh container gets working pad
 * bindings — an empty canvas is not a starting point, it is a puzzle.
 *
 * The three are the three shapes of thing this device is actually asked to run:
 * a game with mouselook, an installer with a Next button, and a container played
 * with a real pad where the overlay is in the way.
 */
object TouchLayouts {

    /** One named layout, as the editor offers it. */
    data class Stock(val name: String, val note: String, val layout: TouchLayout)

    /**
     * Left thumb walks, right thumb looks, four buttons where the fingers are.
     *
     * The geometry is the design's own, converted once: it states a control's
     * size as a diameter in percent of the *width* of a 927 dp landscape session,
     * and [TouchControl.size] is a radius in fractions of the *shorter* edge. The
     * conversion is `size% * 927 / (2 * 421)`, which is where the odd-looking
     * numbers below come from — they are not tuned, they are transcribed.
     */
    val Wasd: TouchLayout = TouchLayout(
        listOf(
            TouchControl(
                id = "stick",
                kind = TouchKind.STICK,
                cx = 0.11f,
                cy = 0.66f,
                size = 0.121f,
                label = "Move",
                role = StickRole.Keys,
                up = GamepadAction.Key(X11.W),
                down = GamepadAction.Key(X11.S),
                left = GamepadAction.Key(X11.A),
                right = GamepadAction.Key(X11.D),
            ),
            TouchControl(
                id = "look",
                kind = TouchKind.STICK,
                cx = 0.87f,
                cy = 0.60f,
                size = 0.143f,
                label = "Look",
                role = StickRole.Look,
            ),
            button("fire", 0.70f, 0.86f, 0.066f, GamepadAction.Key(X11.SPACE)),
            button("use", 0.79f, 0.74f, 0.061f, GamepadAction.Key(X11.E)),
            button("sprint", 0.61f, 0.93f, 0.061f, GamepadAction.Key(X11.SHIFT_L)),
            button("menu", 0.96f, 0.08f, 0.055f, GamepadAction.Key(X11.ESC)),
        ),
    )

    /**
     * A d-pad and two keys, for a setup wizard.
     *
     * The case this exists for is real and is the first thing a new container
     * does: an installer is four arrows, Enter and Esc, and driving one through a
     * trackpad cursor on a phone is far worse than four buttons.
     */
    val Installer: TouchLayout = TouchLayout(
        listOf(
            TouchControl(
                id = "dpad",
                kind = TouchKind.DPAD,
                cx = 0.12f,
                cy = 0.70f,
                size = 0.13f,
                label = "Arrows",
                up = GamepadAction.Key(X11.UP),
                down = GamepadAction.Key(X11.DOWN),
                left = GamepadAction.Key(X11.LEFT),
                right = GamepadAction.Key(X11.RIGHT),
            ),
            button("enter", 0.88f, 0.74f, 0.08f, GamepadAction.Key(X11.ENTER)),
            button("escape", 0.88f, 0.30f, 0.065f, GamepadAction.Key(X11.ESC)),
        ),
    )

    /**
     * A whole controller, on the glass.
     *
     * **This is the built-in default, and every control on it *is* a pad control
     * rather than a key.** Nothing here holds its own binding: each one carries a
     * [TouchControl.pad] or [TouchControl.padStick], and what it sends is
     * whatever the profile's pad table says that control sends. So the Pad tab
     * and the overlay are one table seen twice — rebind `A` and the glass button
     * follows — and a phone with no controller and a phone with one are playing
     * the same game in the same way.
     *
     * **The arrangement is the Pad tab's own diagram, stretched to the screen.**
     * The design comp specifies no full-pad overlay, only that diagram, and a
     * picture of a controller that disagreed with the controller two taps away
     * would be worse than either. So: sticks low and outboard where thumbs rest,
     * the d-pad under the left thumb, the face diamond under the right,
     * shoulders and triggers along the top edge where index fingers reach, and
     * the two system buttons in the middle of the top, out of the way of both.
     *
     * The stick clicks are the one concession. A thumb cannot press a stick it is
     * steering with, so `L3` and `R3` are separate small buttons inboard of each
     * stick rather than a press on the stick itself.
     */
    val Gamepad: TouchLayout = TouchLayout(
        listOf(
            // The two thumbs, low and outboard, where they rest when the phone is
            // held. Everything else is placed around them.
            stick("stick-l", Stick.LEFT, 0.085f, 0.72f, 0.115f),
            stick("stick-r", Stick.RIGHT, 0.915f, 0.72f, 0.115f),

            TouchControl(
                id = "dpad",
                kind = TouchKind.DPAD,
                cx = 0.245f,
                cy = 0.42f,
                size = 0.075f,
                pad = GamepadControl.DPAD_UP,
            ),

            // The face diamond, in the arrangement the Pad tab draws it.
            padButton("btn-y", GamepadControl.Y, 0.845f, 0.29f, 0.05f),
            padButton("btn-x", GamepadControl.X, 0.790f, 0.40f, 0.05f),
            padButton("btn-b", GamepadControl.B, 0.900f, 0.40f, 0.05f),
            padButton("btn-a", GamepadControl.A, 0.845f, 0.51f, 0.05f),

            // Along the top edge, which is where an index finger reaches on a
            // phone held in two hands.
            padButton("btn-l1", GamepadControl.L1, 0.060f, 0.08f, 0.048f),
            padButton("btn-l2", GamepadControl.L2, 0.145f, 0.08f, 0.048f),
            padButton("btn-r2", GamepadControl.R2, 0.855f, 0.08f, 0.048f),
            padButton("btn-r1", GamepadControl.R1, 0.940f, 0.08f, 0.048f),

            padButton("btn-select", GamepadControl.SELECT, 0.460f, 0.08f, 0.040f),
            padButton("btn-start", GamepadControl.START, 0.540f, 0.08f, 0.040f),

            // **The one place this is not a picture of a pad.** A thumb cannot
            // press a stick it is steering with, so the stick clicks are their
            // own small buttons inboard of each stick rather than a press on the
            // stick itself.
            padButton("btn-l3", GamepadControl.THUMB_L, 0.235f, 0.72f, 0.040f),
            padButton("btn-r3", GamepadControl.THUMB_R, 0.765f, 0.72f, 0.040f),
        ),
    )

    /** Nothing on screen, for a container played with a real pad. */
    val None: TouchLayout = TouchLayout()

    val stock: List<Stock> = listOf(
        Stock("A whole controller", "Both sticks, the d-pad, and every button.", Gamepad),
        Stock("WASD and look", "A stick, a look pad and four buttons.", Wasd),
        Stock("Arrows and Enter", "A d-pad, Enter and Esc — for an installer.", Installer),
        Stock("Nothing", "An empty overlay. Add controls yourself.", None),
    )

    private fun stick(id: String, which: Stick, cx: Float, cy: Float, size: Float) = TouchControl(
        id = id,
        kind = TouchKind.STICK,
        cx = cx,
        cy = cy,
        size = size,
        padStick = which,
    )

    private fun padButton(
        id: String,
        control: GamepadControl,
        cx: Float,
        cy: Float,
        size: Float,
    ) = TouchControl(
        id = id,
        kind = TouchKind.BUTTON,
        cx = cx,
        cy = cy,
        size = size,
        pad = control,
    )

    private fun button(id: String, cx: Float, cy: Float, size: Float, action: GamepadAction) =
        TouchControl(
            id = id,
            kind = TouchKind.BUTTON,
            cx = cx,
            cy = cy,
            size = size,
            label = X11KeyCatalog.label(action),
            action = action,
        )
}
