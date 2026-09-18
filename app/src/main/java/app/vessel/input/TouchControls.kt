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
    /**
     * Whether a long press on this button latches it down.
     *
     * For the buttons a hand cannot hold and play at the same time. `L3` is
     * pressed by the thumb that is steering with the stick it sits beside --
     * sprint is the ordinary case, and hold-and-steer with one thumb on glass
     * is not a thing a hand does. `L2` is the aim held for as long as a fight
     * lasts, by the finger the phone is also resting on.
     *
     * Per control rather than a list of the four, because which buttons a game
     * wants held is the game s business: a flight sim holds a trigger nobody
     * else does. The built-in pad turns it on for L1, L2, L3 and R3 and leaves
     * every other button alone, which is where the argument above lands, not a
     * rule the model enforces.
     *
     * Meaningless for a stick or a d-pad, which have no press to hold.
     */
    val latching: Boolean = false,
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

    /**
     * Round, except a d-pad and the four directions it is made of.
     *
     * **A direction placed on its own is a square, because it is an arm of a
     * cross rather than a button.** That is the shape the Pad tab's own diagram
     * already gives the four -- it has drawn them square since it was written,
     * see the editor's `padRow` -- and the glass disagreeing with the diagram
     * two taps away is the thing the whole "one table seen twice" posture
     * exists to prevent.
     *
     * The shape follows the *identity*, not a field of its own: a control that
     * is `D-pad up` looks like `D-pad up` wherever it is drawn, and there is no
     * second setting to be dragged out of agreement with the first.
     */
    val round: Boolean
        get() = kind != TouchKind.DPAD && !TouchControls.isDpadDirection(pad)

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
    val designation: String get() = TouchControls.designationOf(kind, role, padStick)

    /**
     * Which of the twenty-four this control *is*.
     *
     * **The editor's one list is built on this.** A control on the glass and the
     * pad row for the same control are one thing, and showing both is the
     * duplication the redesign exists to remove — so the list draws the glass
     * control and then everything this set does not already speak for. A stick
     * speaks for its four half-axes and a d-pad for its four directions, because
     * each is one control with four bindings. Empty for a control the user
     * placed, which is one the pad table has never heard of.
     */
    val padControls: Set<GamepadControl>
        get() {
            padStick?.let { return it.halfAxes.toSet() }
            val linked = pad ?: return emptySet()
            if (kind != TouchKind.DPAD) return setOf(linked)
            return TouchControls.DPAD_DIRECTIONS
        }

    /**
     * What the control is called, in a list and on the glass.
     *
     * **[label] first, because a control has a name and the name is a field the
     * user owns.** That is the field the redesign added: a control you place is
     * named when you place it and renamed whenever you like, rather than being
     * anonymous until a binding gives it a word. A control that *is* a pad
     * control and has not been renamed falls back on the pad's own name — `A`,
     * `L2 trigger`, `Left stick click` — because "Button" twelve times over is a
     * list that says nothing.
     *
     * A d-pad is the exception, and takes its shape's name: it *names* one of its
     * four directions because the model has one field for the link, so borrowing
     * that name would call the whole cross `D-pad up`.
     */
    val title: String
        get() = label.ifBlank {
            if (kind == TouchKind.DPAD) designation else pad?.padLabel() ?: designation
        }

    /**
     * The mark drawn *on* the control.
     *
     * The name, when it has one. Otherwise a pad control wears its own glyph —
     * `A`, `L2`, `SEL` — and not the key it happens to send: that is what a
     * controller looks like, and it is also the only thing that fits, since
     * `Left Ctrl` in a 42 dp circle is `Lef` and four buttons all reading `Lef`
     * is a pad nobody can aim at. What each one sends is in the list beside the
     * preview, where there is room for it.
     */
    val face: String
        get() = when {
            // **A d-pad never wears a word.** Its shape says everything — four
            // directions arranged as a cross — and the alternative is what it
            // used to draw: `Up Left Down Right` crammed into an 80 dp box,
            // which is illegible and tells a thumb nothing it did not already
            // know from the shape.
            kind == TouchKind.DPAD -> ""
            label.isNotBlank() -> label
            padStick != null -> if (padStick == Stick.LEFT) "L" else "R"
            pad != null -> pad.padGlyph()
            else -> bindingLabel
        }

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
            // A stick that is not sending keys has no keys to name, and saying
            // `Unbound` of one the guest reads as a stick is the opposite of the
            // truth — [StickRole.Pad] is the one role that reaches the guest as
            // itself.
            TouchKind.STICK -> when (role) {
                StickRole.Look -> "Mouse look"
                StickRole.Pad -> "Gamepad axis"
                StickRole.None -> X11KeyCatalog.UNBOUND
                StickRole.Keys -> directionLabel()
            }

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

    /**
     * Every control that would drive one of the guest's two sticks.
     *
     * **One definition of the rule, used by the translator and by the editor.**
     * A control that *is* the pad's left or right stick says so and takes that
     * slot; anything else falls back on its role, which is what makes a
     * hand-built layout — one stick and one look pad, neither claiming a side —
     * behave the way it always did: the stick walks, the pad looks.
     *
     * A list rather than the single answer because the editor has to know when a
     * side is contested: two unsided non-Look sticks are both candidates for the
     * left, only one can have it, and a layout that does that is malformed.
     */
    fun stickCandidates(side: Stick): List<TouchControl> {
        val claimed = controls.filter { it.padStick == side }
        if (claimed.isNotEmpty()) return claimed
        return controls.filter {
            it.kind == TouchKind.STICK && it.padStick == null &&
                (it.role == StickRole.Look) == (side == Stick.RIGHT)
        }
    }

    /** The one control driving a side, or null when nothing does. */
    fun stickFor(side: Stick): TouchControl? = stickCandidates(side).firstOrNull()
}

/** The two or three characters a pad prints on the button itself. */
fun GamepadControl.padGlyph(): String = when (this) {
    GamepadControl.SELECT -> "SEL"
    GamepadControl.START -> "STA"
    GamepadControl.THUMB_L -> "L3"
    GamepadControl.THUMB_R -> "R3"
    // **Arrows, now that a direction can be a control of its own.** These read
    // as "" for as long as the only thing wearing them was a whole cross, which
    // says everything with its silhouette and needs no word -- and still wears
    // nothing, because [TouchControl.face] answers "" for a `DPAD` before it
    // ever asks here. Four separate squares are not a silhouette, so they say
    // which way they go.
    GamepadControl.DPAD_UP -> "↑"
    GamepadControl.DPAD_DOWN -> "↓"
    GamepadControl.DPAD_LEFT -> "←"
    GamepadControl.DPAD_RIGHT -> "→"

    else -> name
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
    /**
     * The four directions a d-pad is made of.
     *
     * **One copy.** There were three -- an inline `setOf` in
     * [TouchControl.padControls], `TouchEdit.DPAD` and the editor's
     * `DPAD_CONTROLS` -- and this change wanted a fourth, for the shape. Four
     * copies of a four-element set is four chances for them to disagree about
     * what a d-pad is, which is exactly the kind of drift that made a cross
     * report `DPAD_UP` for every direction in the first place.
     */
    val DPAD_DIRECTIONS: Set<GamepadControl> = setOf(
        GamepadControl.DPAD_UP,
        GamepadControl.DPAD_DOWN,
        GamepadControl.DPAD_LEFT,
        GamepadControl.DPAD_RIGHT,
    )

    /** Null-tolerant, because [TouchControl.pad] is absent on a control nobody linked. */
    fun isDpadDirection(control: GamepadControl?): Boolean =
        control != null && control in DPAD_DIRECTIONS

    /** Radius as a fraction of the shorter edge. 0.04 of 421 dp is a 34 dp target. */
    const val MIN_SIZE: Float = 0.04f
    const val MAX_SIZE: Float = 0.20f

    /**
     * 0 to 1, and the ends mean what they say: 0 is **not drawn at all** -- the
     * controls still take their touches, which is how someone who has learned a
     * layout plays over a clean screen -- and 1 is fully solid.
     *
     * It was 0.10 to 0.80, and below the slider the painter floored a control's
     * ring at 0.35 and drew its label 0.25 stronger, so the bottom of the
     * slider was a clearly visible overlay and there was no way to hide one.
     */
    const val MIN_OPACITY: Float = 0.0f
    const val MAX_OPACITY: Float = 1.0f

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
        TouchKind.DPAD -> 0.13f
    }

    /**
     * What a control of this shape is called before anyone has named it.
     *
     * Taken out of [TouchControl.designation] so the editor can name a control
     * it is *about to* create — the whole point of the redesign's name field is
     * that a control has a name from the moment it is placed, and until it
     * exists there is no `TouchControl` to ask.
     */
    fun designationOf(
        kind: TouchKind,
        role: StickRole = StickRole.Keys,
        padStick: Stick? = null,
    ): String = when (kind) {
        TouchKind.BUTTON -> "Button"
        TouchKind.DPAD -> "D-pad"
        // **No "Look pad".** A look pad is a stick whose role is Look, and the
        // role is a field on the control — naming the same ring two things
        // depending on one of its own settings made the editor look like it had
        // four shapes when it has three.
        TouchKind.STICK -> when (padStick) {
            Stick.LEFT -> "Left stick"
            Stick.RIGHT -> "Right stick"
            null -> "Stick"
        }
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
            // 0.77 rather than the comp's 0.74. The comp is drawn at 927x421 --
            // 2.2:1 -- where `use` clears the look pad with room to spare; the
            // gap closes as a screen squares up, because a radius is a fraction
            // of the short edge and `cx` a fraction of the width, and at 16:10
            // the two circles crossed. `use` is declared later, so a thumb on
            // the lower-left of the look pad sent `E` instead of looking.
            button("use", 0.79f, 0.77f, 0.061f, GamepadAction.Key(X11.E)),
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
            // **The two thumbs own the bottom corners, and each cluster sits
            // directly above its own thumb.** A phone is held by its ends, so
            // the reachable ground is two arcs swept up from the lower corners —
            // not the middle, which is where the d-pad used to sit and where
            // neither thumb goes without the hand letting go of the phone.
            //
            // So the left mirrors the right exactly: stick low and outboard,
            // its cluster above it, its stick-click inboard. That symmetry is
            // also the fastest thing to learn — whatever is true of one hand is
            // true of the other.
            // Inboard of the corners rather than in them. A stick's radius is a
            // fraction of the *short* side, so on a wide desktop 0.09 put half
            // the circle under the screen's own edge — and a thumb resting on a
            // bezel is not resting on a stick.
            stick("stick-l", Stick.LEFT, 0.150f, 0.72f, 0.120f),
            stick("stick-r", Stick.RIGHT, 0.850f, 0.72f, 0.120f),

            // **Four buttons, not one cross, and each one is its own control.**
            //
            // A cross is a single control with four directions, and the model
            // has one `pad` field to link it with -- so the stock cross carried
            // `DPAD_UP` as its *identity* and every consumer had to know that
            // the field named the shape rather than a direction. One did not:
            // `padSnapshot` read it as a press, so every direction also sent up.
            // That is fixed, but the shape is what made the mistake available.
            //
            // Split, each direction carries the direction it actually sends,
            // there is nothing left to misread, and the editor lists four rows a
            // user can bind and move one at a time instead of one row that
            // silently spoke for four.
            //
            // **The cost, stated plainly: one thumb no longer goes diagonal.** A
            // cross is one hit area, so a thumb in its corner deflected both
            // axes at once; four hit areas take one finger each, and up-and-right
            // now needs two. A `D-pad` is still in the editor's Add list for
            // anyone who wants the old behaviour back on a layout of their own.
            //
            // The geometry keeps the cluster about the size a thumb had learned:
            // arms of 0.042 offset by 0.108 span 0.150 of the short edge against
            // the old cross's 0.120, and the 0.024 left between adjacent arms is
            // the gap that says "four things" rather than "one cross" at a
            // glance. Horizontal offsets are halved because `cx` is a fraction
            // of the *width* and `cy` of the *height* -- the 2:1 the face
            // diamond above is already drawn for.
            //
            // **The one number that is a constraint rather than a taste.** That
            // same split means the horizontal gap closes as a screen gets
            // squarer: `size` is a fraction of the short edge, so at an aspect
            // ratio R the arms are 2 x 0.042 apart in short-edge units but only
            // 0.054 x R. They meet at R = 1.56 and would *overlap* below it --
            // and overlapping controls do not merely look wrong, they misroute,
            // because `hitTest` hands the touch to whichever was declared last.
            // 0.054 keeps them apart through 16:10, which is the squarest
            // landscape this runs on. `every stock layout is unambiguous under a
            // finger` is the test that holds it there.
            padButton("dpad-up", GamepadControl.DPAD_UP, 0.115f, 0.292f, 0.042f),
            padButton("dpad-down", GamepadControl.DPAD_DOWN, 0.115f, 0.508f, 0.042f),
            padButton("dpad-left", GamepadControl.DPAD_LEFT, 0.061f, 0.400f, 0.042f),
            padButton("dpad-right", GamepadControl.DPAD_RIGHT, 0.169f, 0.400f, 0.042f),

            // The face diamond, in the arrangement the Pad tab draws it, above
            // the right thumb exactly as the d-pad is above the left.
            padButton("btn-y", GamepadControl.Y, 0.885f, 0.29f, 0.05f),
            padButton("btn-x", GamepadControl.X, 0.830f, 0.40f, 0.05f),
            padButton("btn-b", GamepadControl.B, 0.940f, 0.40f, 0.05f),
            padButton("btn-a", GamepadControl.A, 0.885f, 0.51f, 0.05f),

            // Along the top edge, which is where an index finger reaches on a
            // phone held in two hands.
            //
            // **Triggers outboard, shoulders inboard**, which is the way round a
            // hand actually meets them. On a real pad the trigger is the one the
            // index finger curls onto first and the shoulder sits behind it;
            // held flat against glass, the outer position is what the finger
            // reaches without the hand giving up its grip on the end of the
            // phone. This was the other way round -- L1 and R1 outermost -- so
            // the two that get held down through a whole fight, and the two
            // marked latching for exactly that reason, were the ones furthest
            // from where the finger rests.
            padButton("btn-l2", GamepadControl.L2, 0.055f, 0.09f, 0.048f, latching = true),
            padButton("btn-l1", GamepadControl.L1, 0.140f, 0.09f, 0.048f, latching = true),
            padButton("btn-r1", GamepadControl.R1, 0.860f, 0.09f, 0.048f),
            padButton("btn-r2", GamepadControl.R2, 0.945f, 0.09f, 0.048f),

            padButton("btn-select", GamepadControl.SELECT, 0.460f, 0.09f, 0.040f),
            padButton("btn-start", GamepadControl.START, 0.540f, 0.09f, 0.040f),

            // **The one place this is not a picture of a pad.** A thumb cannot
            // press a stick it is steering with, so the stick clicks are their
            // own small buttons inboard of each stick rather than a press on the
            // stick itself.
            //
            // 0.110 inboard rather than 0.095, which is the same short-edge-vs-
            // width trap the d-pad's arms are spaced against. A stick and its
            // click need 0.160 of the short edge between centres to clear each
            // other; 0.095 of the width is only 0.152 of it at 16:10, so on a
            // tablet the click's circle overlapped the stick's by 8 px -- and
            // being declared second, it won those touches. The outer edge of the
            // left stick pressed L3.
            padButton("btn-l3", GamepadControl.THUMB_L, 0.260f, 0.72f, 0.040f, latching = true),
            padButton("btn-r3", GamepadControl.THUMB_R, 0.740f, 0.72f, 0.040f, latching = true),
        ),
    )

    /**
     * **Keyboard and mouse: two rings of keys, one around each thumb.**
     *
     * A phone is held by its ends, so the ground a thumb covers is a disc
     * centred on where it rests -- and the middle of the screen is the one place
     * neither thumb goes. That shape is the layout: a stick with its keys in
     * rings around it on each side, the middle left for the two keys pressed so
     * often they deserve their own ground, and the top edge for what an index
     * finger reaches.
     *
     * Thirty-two keys fit inside that reach. Every letter a game binds is here
     * -- WASD on the left stick, the rest split between the rings -- with the
     * whole number row, both mouse buttons, both hands' Shift and Control, Esc,
     * Enter, Tab and Space.
     *
     * **Distance from the stick is how often a game presses the key.** Inner
     * ring: the number row on the left, the verbs on the right. Outer ring: the
     * rest of the alphabet, alphabetical, which is what a game asks for between
     * fights rather than during them and the only order a key can be found in
     * without hunting.
     *
     * Not `Wasd`, which stays: that one is six controls for a game played with
     * two thumbs and no thought. This is the full hand.
     */
    val KeyboardAndMouse: TouchLayout = TouchLayout(
        listOf(
            // **A stick with two arcs of keys sweeping outboard of it, twice,
            // and the middle left empty.** A phone is held by its ends, so the
            // ground a thumb covers is an arc swept from the corner it rests in;
            // the middle of the screen is the one place neither thumb reaches.
            //
            // **Both layers sweep outward, and the inboard halves are left
            // out.** A full ring would put half its keys between the hands --
            // the far side of the stick from the thumb, and the longest reach on
            // the screen -- so those slots would be the worst on the layout and
            // would crowd the middle at the same time.
            //
            // **The two sides are mirror images, seven and nine each.** Not for
            // symmetry's own sake: a hand learns one side and then knows the
            // other, and an arc with more keys on it than its twin has them at a
            // different spacing, so neither hand can trust what it learned.
            //
            // **The sticks sit on the screen's vertical middle**, which puts the
            // body of the layout -- both arcs and the four corner keys -- inside
            // 0.182 to 0.818, symmetric about 0.500. The top row is anchored to
            // the top edge instead and is not part of that balance, so what the
            // centring buys is a clear band along the bottom: the editor draws
            // `Arrange the overlay` and its Done button there, and a control
            // under that is one nobody can pick up and move.
            TouchControl(
                id = "stick",
                kind = TouchKind.STICK,
                cx = 0.230f,
                cy = 0.500f,
                size = 0.115f,
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
                cx = 0.770f,
                cy = 0.500f,
                size = 0.115f,
                label = "Look",
                role = StickRole.Look,
            ),

            // **The arcs are circular in pixels at the squarest screen and widen
            // from there.** `cx` is a fraction of the width and `size` a
            // fraction of the short edge, so a constant `cx` arm is a larger
            // pixel offset on a longer screen: dividing the horizontal arm by
            // 1.6 makes each arc round at 16:10 and flatter-but-further-apart at
            // 2.4:1. Outward is the safe direction -- buttons only separate.
            //
            // **How many fit is arithmetic, not taste.** Over a half circle of
            // radius R, n keys sit 2 * R * sin(90 / (n - 1)) apart and two radii
            // are 0.080: 0.093 at seven on the inner arc, 0.109 at nine on the
            // outer. Ten and eleven were both tried on the outer arc; eleven
            // measured 0.077, an overlap, which `hitTest` resolves by handing
            // the touch to whichever control was declared later.
            //
            // The number row runs down the inner arc and continues onto the
            // outer one, then the alphabet follows: top to bottom on both, so
            // the order reads the way the thumb travels.
            button("key-1", 0.230f, 0.315f, 0.040f, GamepadAction.Key(X11.N1)),
            button("key-2", 0.172f, 0.340f, 0.040f, GamepadAction.Key(X11.N2)),
            button("key-3", 0.130f, 0.408f, 0.040f, GamepadAction.Key(X11.N3)),
            button("key-4", 0.114f, 0.500f, 0.040f, GamepadAction.Key(X11.N4)),
            button("key-5", 0.130f, 0.593f, 0.040f, GamepadAction.Key(X11.N5)),
            button("key-6", 0.172f, 0.660f, 0.040f, GamepadAction.Key(X11.N6)),
            button("key-7", 0.230f, 0.685f, 0.040f, GamepadAction.Key(X11.N7)),

            button("key-8", 0.230f, 0.222f, 0.040f, GamepadAction.Key(X11.N8)),
            button("key-9", 0.164f, 0.243f, 0.040f, GamepadAction.Key(X11.N9)),
            button("key-0", 0.107f, 0.303f, 0.040f, GamepadAction.Key(X11.N0)),
            button("key-h", 0.069f, 0.394f, 0.040f, GamepadAction.Key(X11.H)),
            button("key-i", 0.056f, 0.500f, 0.040f, GamepadAction.Key(X11.I)),
            button("key-j", 0.069f, 0.606f, 0.040f, GamepadAction.Key(X11.J)),
            button("key-k", 0.107f, 0.697f, 0.040f, GamepadAction.Key(X11.K)),
            button("key-l", 0.164f, 0.757f, 0.040f, GamepadAction.Key(X11.L)),
            button("key-n", 0.230f, 0.778f, 0.040f, GamepadAction.Key(X11.N)),

            // The right thumb: the verbs on the inner arc, what a game presses
            // while something is happening.
            button("key-e", 0.770f, 0.315f, 0.040f, GamepadAction.Key(X11.E)),
            button("key-r", 0.828f, 0.340f, 0.040f, GamepadAction.Key(X11.R)),
            button("key-f", 0.870f, 0.408f, 0.040f, GamepadAction.Key(X11.F)),
            button("key-g", 0.886f, 0.500f, 0.040f, GamepadAction.Key(X11.G)),
            button("key-c", 0.870f, 0.593f, 0.040f, GamepadAction.Key(X11.C)),
            button("key-v", 0.828f, 0.660f, 0.040f, GamepadAction.Key(X11.V)),
            button("key-q", 0.770f, 0.685f, 0.040f, GamepadAction.Key(X11.Q)),

            button("key-b", 0.770f, 0.222f, 0.040f, GamepadAction.Key(X11.B)),
            button("key-m", 0.836f, 0.243f, 0.040f, GamepadAction.Key(X11.M)),
            button("key-o", 0.893f, 0.303f, 0.040f, GamepadAction.Key(X11.O)),
            button("key-p", 0.931f, 0.394f, 0.040f, GamepadAction.Key(X11.P)),
            button("key-t", 0.944f, 0.500f, 0.040f, GamepadAction.Key(X11.T)),
            button("key-u", 0.931f, 0.606f, 0.040f, GamepadAction.Key(X11.U)),
            button("key-x", 0.893f, 0.697f, 0.040f, GamepadAction.Key(X11.X)),
            button("key-y", 0.836f, 0.757f, 0.040f, GamepadAction.Key(X11.Y)),
            button("key-z", 0.770f, 0.778f, 0.040f, GamepadAction.Key(X11.Z)),

            // **W and S outboard of the left arcs, A and D outboard of the
            // right ones -- one above, one below, in the four corners the arcs
            // leave empty.**
            //
            // The stick already sends all four -- that is what `StickRole.Keys`
            // means -- but a stick sends them the way a stick does: one
            // direction at a time, from a centre it springs back to. A game that
            // wants `S` held while a menu is open, or `D` tapped once, wants a
            // button, and those four were the only letters on this keyboard with
            // nowhere to tap them. Both routes stay live: holding `S` on the
            // glass while pushing the stick forward is the same contradiction as
            // pressing two keys at once on a keyboard, and the guest resolves it
            // the same way.
            //
            // **Two and two rather than four on one side**, which is what a WASD
            // cluster looked like when it was tried: a crowd under one thumb
            // while the other side had nothing, and the two halves stopped being
            // mirror images. Forward and back go to the hand that walks; strafe
            // goes to the hand that aims.
            //
            // The corners are the only ground left that is neither on an arc nor
            // in the middle -- and the middle is spoken for, because the editor
            // draws `Arrange the overlay` and its Done button bottom-centre, and
            // a control under that is one nobody can pick up and move.
            button("key-w", 0.052f, 0.250f, 0.040f, GamepadAction.Key(X11.W)),
            button("key-s", 0.052f, 0.750f, 0.040f, GamepadAction.Key(X11.S)),

            button("key-a", 0.948f, 0.250f, 0.040f, GamepadAction.Key(X11.A)),
            button("key-d", 0.948f, 0.750f, 0.040f, GamepadAction.Key(X11.D)),

            // **Along the top edge: the mouse in the corners where an index
            // finger rests, then both hands' modifiers, then the four keys that
            // are not letters.** Esc, Tab, Space and Enter sit together in the
            // middle rather than in the gap between the hands, which leaves that
            // gap empty -- and keeps every control clear of `Arrange the
            // overlay`, whose Done button is drawn bottom-centre over the
            // layout. Two controls under it were two a user could not pick up.
            //
            // The cost is real and worth stating: `Space` is a top-edge reach
            // rather than a thumb roll, which is the wrong place for a jump
            // button in a shooter. It is one drag away for anyone who wants it
            // back, and this is a starting point rather than a verdict.
            //
            // **Three letters each, so the row reads as one set.** Left and
            // right Shift and Control are different keycodes and a game bound to
            // one will not answer the other, so all four are here -- but "Left
            // Ctrl" on a button sitting on the left says the same thing twice,
            // and the catalogue's long names are for a picker listing every key
            // at once, not for a 48 dp circle. The side is the position; the
            // label is the key.
            //
            // `CTL` rather than `CTR`, which reads as "centre" on a row whose
            // middle four are exactly that.
            button("mouse-left", 0.055f, 0.09f, 0.048f, GamepadAction.Button(PointerButton.LEFT), label = "LMB"),
            button("key-ctrl-l", 0.140f, 0.09f, 0.048f, GamepadAction.Key(X11.CTRL_L), latching = true, label = "CTL"),
            button("key-shift-l", 0.225f, 0.09f, 0.048f, GamepadAction.Key(X11.SHIFT_L), latching = true, label = "SFT"),
            button("key-esc", 0.395f, 0.09f, 0.040f, GamepadAction.Key(X11.ESC), label = "ESC"),
            button("key-tab", 0.465f, 0.09f, 0.040f, GamepadAction.Key(X11.TAB), label = "TAB"),
            button("key-space", 0.535f, 0.09f, 0.040f, GamepadAction.Key(X11.SPACE), label = "SPC"),
            button("key-enter", 0.605f, 0.09f, 0.040f, GamepadAction.Key(X11.ENTER), label = "ENT"),
            button("key-shift-r", 0.775f, 0.09f, 0.048f, GamepadAction.Key(X11.SHIFT_R), latching = true, label = "SFT"),
            button("key-ctrl-r", 0.860f, 0.09f, 0.048f, GamepadAction.Key(X11.CTRL_R), latching = true, label = "CTL"),
            button("mouse-right", 0.945f, 0.09f, 0.048f, GamepadAction.Button(PointerButton.RIGHT), label = "RMB"),
        ),
    )

    /** Nothing on screen, for a container played with a real pad. */
    val None: TouchLayout = TouchLayout()

    val stock: List<Stock> = listOf(
        Stock("A whole controller", "Both sticks, the d-pad, and every button.", Gamepad),
        Stock("Keyboard and mouse", "WASD, mouse look, the mouse buttons and the keys games use.", KeyboardAndMouse),
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
        latching: Boolean = false,
    ) = TouchControl(
        id = id,
        kind = TouchKind.BUTTON,
        cx = cx,
        cy = cy,
        size = size,
        pad = control,
        latching = latching,
    )

    /**
     * No `label`: a stock button wears whatever it sends, and follows a rebinding.
     *
     * It used to be given `X11KeyCatalog.label(action)` as a name, which was the
     * same string one frame later and a *stale* one the moment the key changed —
     * the name is the user's field now, and a layout the user has not named
     * anything is better read straight off its binding.
     */
    private fun button(
        id: String,
        cx: Float,
        cy: Float,
        size: Float,
        action: GamepadAction,
        latching: Boolean = false,
        label: String = "",
    ) =
        TouchControl(
            id = id,
            kind = TouchKind.BUTTON,
            cx = cx,
            cy = cy,
            size = size,
            action = action,
            latching = latching,
            label = label,
        )
}
