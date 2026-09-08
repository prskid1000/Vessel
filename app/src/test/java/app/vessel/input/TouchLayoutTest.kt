package app.vessel.input


import kotlin.math.max
import kotlin.math.min
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Where a control is, and whether a finger is on it.
 *
 * Every number in here is a fraction of the *surface*, never of the guest
 * desktop — see [TouchControl]. The tests that matter are the ones about the
 * gaps: a touch between two controls must reach the guest, or the overlay
 * silently becomes a lid.
 */
class TouchLayoutTest {

    private val w = 900f
    private val h = 400f

    private fun button(id: String, cx: Float, cy: Float, size: Float = 0.1f) = TouchControl(
        id = id,
        kind = TouchKind.BUTTON,
        cx = cx,
        cy = cy,
        size = size,
    )

    @Test
    fun `inside claims and the gap does not`() {
        val layout = TouchLayout(listOf(button("a", 0.2f, 0.5f)))
        // 0.1 of the shorter edge, 400, is a 40 px radius at (180, 200).
        assertEquals("a", layout.hitTest(180f, 200f, w, h)?.id)
        assertEquals("a", layout.hitTest(215f, 200f, w, h)?.id)
        assertNull(layout.hitTest(230f, 200f, w, h))
        assertNull(layout.hitTest(180f, 250f, w, h))
    }

    /**
     * A round control is hit-tested round.
     *
     * The corner of the bounding box is 1.41 radii out, so a finger there is on
     * the square and off the circle — and the circle is what is drawn.
     */
    @Test
    fun `a round control does not claim its corners`() {
        val layout = TouchLayout(listOf(button("a", 0.5f, 0.5f)))
        assertNull(layout.hitTest(450f + 39f, 200f + 39f, w, h))
        assertEquals("a", layout.hitTest(450f + 27f, 200f + 27f, w, h)?.id)
    }

    @Test
    fun `a d-pad claims its corners, because it is a square`() {
        val layout = TouchLayout(
            listOf(button("d", 0.5f, 0.5f).copy(kind = TouchKind.DPAD)),
        )
        assertEquals("d", layout.hitTest(450f + 39f, 200f + 39f, w, h)?.id)
    }

    /** Painted in order, so the one on top is the last one, and it takes the touch. */
    @Test
    fun `overlaps resolve to the last declared`() {
        val layout = TouchLayout(listOf(button("under", 0.5f, 0.5f), button("over", 0.5f, 0.5f)))
        assertEquals("over", layout.hitTest(450f, 200f, w, h)?.id)
    }

    /**
     * A control clamped to the edge is still wholly on screen, which means wholly
     * hittable — a button whose left half is off the panel cannot be pressed and
     * cannot be dragged back.
     */
    @Test
    fun `a clamped edge control is fully hittable`() {
        val clamped = button("edge", -0.5f, 0.5f).clampedIn(w, h)
        val layout = TouchLayout(listOf(clamped))
        val left = clamped.centreX(w) - clamped.radiusPx(w, h)
        assertTrue("left edge is on screen", left >= -0.01f)
        assertEquals("edge", layout.hitTest(clamped.centreX(w), 200f, w, h)?.id)
    }

    /**
     * The radius is a fraction of the *shorter* edge, so the clamp is a different
     * fraction on each axis — and clamping both to the same number would forbid
     * the outer sixth of a landscape screen, where a thumb actually rests.
     */
    @Test
    fun `the clamp is asymmetric because the radius is not`() {
        val control = button("a", 0.5f, 0.5f, size = 0.14f).clampedIn(w, h)
        assertEquals(0.5f, control.cx, 0.001f)
        val far = button("a", 0.97f, 0.97f, size = 0.14f).clampedIn(w, h)
        // 0.14 * 400 = 56 px. As a fraction of 900 that is 0.062; of 400, 0.14.
        assertEquals(1f - 0.062f, far.cx, 0.002f)
        assertEquals(1f - 0.14f, far.cy, 0.002f)
    }

    @Test
    fun `sane pulls every number back into range`() {
        val wild = TouchControl(
            id = "x",
            kind = TouchKind.BUTTON,
            cx = 4f,
            cy = -2f,
            size = 9f,
            opacity = 3f,
        ).sane()
        assertEquals(1f, wild.cx, 0f)
        assertEquals(0f, wild.cy, 0f)
        assertEquals(TouchControls.MAX_SIZE, wild.size, 0f)
        assertEquals(TouchControls.MAX_OPACITY, wild.opacity, 0f)
    }

    @Test
    fun `a not-a-number never survives into the layout`() {
        val nan = TouchControl(
            id = "x",
            kind = TouchKind.BUTTON,
            cx = Float.NaN,
            cy = Float.NaN,
            size = Float.NaN,
            opacity = Float.NaN,
        ).sane()
        assertFalse(nan.cx.isNaN())
        assertFalse(nan.size.isNaN())
        assertEquals(TouchControls.DEFAULT_OPACITY, nan.opacity, 0f)
    }

    /**
     * The three stock layouts have to be legal, because they are what a fresh
     * container gets and nothing sanitises them on the way in.
     */
    @Test
    fun `every stock layout is already sane and uniquely shaped`() {
        TouchLayouts.stock.forEach { stock ->
            assertEquals(stock.name, stock.layout, stock.layout.sane())
            // A side, not a designation: a stick and a look pad are the same
            // shape now, so counting names would count them as one thing. What
            // must be unique is which of the guest's two sticks a control drives.
            Stick.entries.forEach { side ->
                assertTrue(
                    "${stock.name} has at most one control driving the $side stick",
                    stock.layout.stickCandidates(side).size <= 1,
                )
            }
            assertTrue(
                "${stock.name} has at most one d-pad",
                stock.layout.controls.count { it.kind == TouchKind.DPAD } <= 1,
            )
        }
    }

    /**
     * No two controls on a stock layout may share a pixel, on any screen it can
     * be shown at.
     *
     * **Overlap is not a cosmetic fault, it is a misroute.** [TouchLayout.hitTest]
     * answers with the *last declared* control, so a finger in the overlap gets
     * whichever one happens to be later in the list -- silently, and only on the
     * screens where the shapes happen to collide.
     *
     * Which is the trap this layout is built over: [TouchControl.size] is a
     * fraction of the *short* edge and [TouchControl.cx] a fraction of the
     * *width*, so every horizontal gap in it narrows as a screen gets squarer
     * while the controls themselves do not. A cluster spaced by eye on a 2.2:1
     * phone can collide on a 16:10 tablet. The d-pad's four arms are the
     * tightest cluster here and the reason this test exists.
     *
     * Asked of [TouchControl.contains] rather than of the radii, because that is
     * the function the router actually routes on -- a circle and a square are
     * different questions and only one of them is the promise the drawing made.
     * Sampled over the box where two controls could possibly meet, which is a
     * few hundred points per pair and exact to the step.
     */
    @Test
    fun `every stock layout is unambiguous under a finger`() {
        // 16:10 is the squarest landscape this runs on; 2.4:1 the widest phone.
        val screens = listOf(1600f to 1000f, 1920f to 1080f, 2400f to 1080f, 2880f to 1200f)
        TouchLayouts.stock.forEach { stock ->
            screens.forEach { (w, h) ->
                stock.layout.controls.forEachIndexed { i, a ->
                    stock.layout.controls.drop(i + 1).forEach { b ->
                        sharedPoint(a, b, w, h)?.let { (x, y) ->
                            fail(
                                "${stock.name}: ${a.id} and ${b.id} both take " +
                                    "(${x.toInt()}, ${y.toInt()}) on ${w.toInt()}x${h.toInt()} " +
                                    "-- ${b.id} is declared later, so it wins that touch",
                            )
                        }
                    }
                }
            }
        }
    }

    /** A point both controls answer [TouchControl.contains] for, or null. */
    private fun sharedPoint(
        a: TouchControl,
        b: TouchControl,
        w: Float,
        h: Float,
        step: Float = 2f,
    ): Pair<Float, Float>? {
        val ra = a.radiusPx(w, h)
        val rb = b.radiusPx(w, h)
        val x0 = max(a.centreX(w) - ra, b.centreX(w) - rb)
        val x1 = min(a.centreX(w) + ra, b.centreX(w) + rb)
        val y0 = max(a.centreY(h) - ra, b.centreY(h) - rb)
        val y1 = min(a.centreY(h) + ra, b.centreY(h) + rb)
        var x = x0
        while (x <= x1) {
            var y = y0
            while (y <= y1) {
                if (a.contains(x, y, w, h) && b.contains(x, y, w, h)) return x to y
                y += step
            }
            x += step
        }
        return null
    }

    /** Nothing on a stock layout may sit off the screen it is laid out against. */
    @Test
    fun `every stock control is wholly on a landscape screen`() {
        TouchLayouts.stock.forEach { stock ->
            stock.layout.controls.forEach { control ->
                val clamped = control.clampedIn(2780f, 1264f)
                assertEquals("${stock.name}/${control.id} cx", control.cx, clamped.cx, 0.0001f)
                assertEquals("${stock.name}/${control.id} cy", control.cy, clamped.cy, 0.0001f)
            }
        }
    }

    /** Two controls of a full pad must not sit on top of each other. */
    @Test
    fun `the controller layout does not overlap itself`() {
        val w = 2780f
        val h = 1264f
        val controls = TouchLayouts.Gamepad.controls
        controls.forEachIndexed { i, a ->
            controls.drop(i + 1).forEach { b ->
                val dx = a.centreX(w) - b.centreX(w)
                val dy = a.centreY(h) - b.centreY(h)
                val apart = kotlin.math.hypot(dx, dy)
                val touching = a.radiusPx(w, h) + b.radiusPx(w, h)
                assertTrue("${a.id} and ${b.id} overlap", apart >= touching)
            }
        }
    }

    /**
     * The built-in default is a whole controller, and every control on it is a
     * pad control rather than a key.
     *
     * That is the property the feature rests on: the Pad tab and the overlay are
     * one table seen twice. A control on this layout holding its own binding
     * would be one that stopped following a rebinding, silently.
     */
    @Test
    fun `the built-in default is a whole controller, linked to the pad table`() {
        val stored = InputProfile.Default.touch
        assertFalse(stored.isEmpty)
        assertTrue("both sticks", stored.controls.count { it.padStick != null } == 2)
        assertTrue(
            "four separate d-pad directions",
            stored.controls.mapNotNull { it.pad }.containsAll(TouchControls.DPAD_DIRECTIONS),
        )
        assertTrue(
            "and no joined cross among them",
            stored.controls.none { it.kind == TouchKind.DPAD },
        )
        assertTrue(
            "every control is a pad control",
            stored.controls.all { it.pad != null || it.padStick != null },
        )
        // Nothing on it carries a binding of its own; they are all borrowed.
        assertTrue(
            "no control holds its own action",
            stored.controls.all { it.action == GamepadAction.None },
        )
    }

    /** Twelve buttons and two sticks is a controller; fewer is a subset of one. */
    @Test
    fun `every pad control a person would look for is on the default`() {
        val linked = InputProfile.Default.touch.controls.mapNotNull { it.pad }.toSet()
        listOf(
            GamepadControl.A, GamepadControl.B, GamepadControl.X, GamepadControl.Y,
            GamepadControl.L1, GamepadControl.R1, GamepadControl.L2, GamepadControl.R2,
            GamepadControl.SELECT, GamepadControl.START,
            GamepadControl.THUMB_L, GamepadControl.THUMB_R,
        ).forEach { assertTrue("$it is on the overlay", it in linked) }
    }

    /**
     * Resolution is what turns the link into behaviour: the glass A button sends
     * whatever the pad table binds A to, and follows it when that changes.
     */
    @Test
    fun `a pad-linked control borrows the pad table's binding`() {
        val a = InputProfile.Default.overlay.controls.first { it.pad == GamepadControl.A }
        assertEquals(GamepadProfile.Default.bindings[GamepadControl.A], a.action)

        val rebound = InputProfile.Default.let {
            it.copy(
                pad = it.pad.copy(
                    bindings = it.pad.bindings + (GamepadControl.A to GamepadAction.Key(X11.F1)),
                ),
            )
        }
        val moved = rebound.overlay.controls.first { it.pad == GamepadControl.A }
        assertEquals(GamepadAction.Key(X11.F1), moved.action)
    }

    /** A stick takes its role from the pad table too, so Look is not said twice. */
    @Test
    fun `a linked stick takes the pad table's role and half-axes`() {
        val overlay = InputProfile.Default.overlay
        val left = overlay.controls.first { it.padStick == Stick.LEFT }
        val right = overlay.controls.first { it.padStick == Stick.RIGHT }
        // Both are sticks now: the default profile is a controller, and a stick
        // that reaches the guest as a stick is what `StickRole.Pad` means.
        assertEquals(StickRole.Pad, left.role)
        assertEquals(StickRole.Pad, right.role)
        assertEquals(GamepadProfile.Default.bindings[GamepadControl.STICK_L_UP], left.up)
        // And the designations stay unique, which is what the editor's
        // one-of-each rule is checked against.
        assertEquals("Left stick", left.designation)
        assertEquals("Right stick", right.designation)
    }

    /**
     * There is always a profile.
     *
     * The default is an ordinary record now — editable, renameable, saved under
     * its own id — and the single thing its id still means is that
     * `InputProfileRepository.delete` refuses it. `InputProfile.Default` is the
     * seed used until something writes over it.
     */
    @Test
    fun `the default is the seed and is marked undeletable`() {
        assertTrue(InputProfile.Default.isBuiltInDefault)
        assertEquals("Virtual controller", InputProfile.Default.name)
        // Renaming it does not stop it being the one that cannot be deleted.
        assertTrue(InputProfile.Default.copy(name = "Mine").isBuiltInDefault)
    }

    /** Keyboard and mouse survives as a stock layout; it is just not the default. */
    @Test
    fun `the keyboard and mouse layout is still offered`() {
        assertTrue(TouchLayouts.stock.any { it.layout == TouchLayouts.Wasd })
        assertTrue(TouchLayouts.stock.first().layout == TouchLayouts.Gamepad)
    }

    @Test
    fun `a stick reads as its four keys and a look pad reads as the mouse`() {
        val stick = TouchLayouts.Wasd.byId("stick")!!
        assertEquals("W A S D", stick.bindingLabel)
        assertEquals("Mouse look", TouchLayouts.Wasd.byId("look")!!.bindingLabel)
    }

    /** The one role that reaches the guest as itself must not read as `Unbound`. */
    @Test
    fun `a stick the guest reads as a stick says so`() {
        val stick = TouchLayouts.Wasd.byId("stick")!!.copy(role = StickRole.Pad)
        assertEquals("Gamepad axis", stick.bindingLabel)
    }

    /**
     * **Nothing is listed twice.**
     *
     * The editor is one list: the controls on the glass, then whatever of the
     * twenty-four they do not already speak for. A whole controller on the glass
     * therefore leaves no remainder at all — and if [TouchControl.padControls]
     * ever stopped covering one, that control would appear both as a button in
     * the picture and as a row underneath it, which is the duplication the
     * redesign exists to remove.
     */
    @Test
    fun `the built-in default speaks for every pad control exactly once`() {
        val spoken = InputProfile.Default.touch.controls.flatMap { it.padControls }
        assertEquals(GamepadControl.entries.size, spoken.size)
        assertEquals(GamepadControl.entries.toSet(), spoken.toSet())
    }

    /**
     * A stick speaks for four half-axes; a d-pad direction speaks for itself.
     *
     * **The second half is the point of splitting the cross.** One control that
     * spoke for four is how a single `pad` field came to mean "this is the
     * d-pad" rather than "this sends up", and how `padSnapshot` came to send up
     * for every direction. Four controls that each speak for one leave nothing
     * to interpret -- the identity a direction carries *is* the direction it
     * sends, and its name says so too.
     */
    @Test
    fun `a stick speaks for four, and each d-pad direction for itself`() {
        val stored = InputProfile.Default.touch
        assertEquals(
            Stick.LEFT.halfAxes.toSet(),
            stored.controls.first { it.padStick == Stick.LEFT }.padControls,
        )
        TouchControls.DPAD_DIRECTIONS.forEach { direction ->
            val control = stored.controls.single { it.pad == direction }
            assertEquals(setOf(direction), control.padControls)
            assertEquals(direction.padLabel(), control.title)
            assertFalse("$direction is drawn square", control.round)
        }
    }

    /**
     * A joined cross is still a control the model has -- the Installer's arrow
     * pad is one, and the editor still offers one, because a diagonal under a
     * single thumb is the one thing four buttons cannot do.
     */
    @Test
    fun `a cross still speaks for all four`() {
        val cross = TouchControl(
            id = "dpad",
            kind = TouchKind.DPAD,
            cx = 0.5f,
            cy = 0.5f,
            size = 0.12f,
            pad = GamepadControl.DPAD_UP,
        )
        assertEquals(TouchControls.DPAD_DIRECTIONS, cross.padControls)
        assertEquals("D-pad", cross.title)
    }

    /**
     * A control you add is a full control, and that starts with having a name.
     *
     * It used to arrive anonymous and stay that way until a binding gave it a
     * word, so the thing drawn on the glass read `Unbound`.
     */
    @Test
    fun `a control you place is born with a name and can be renamed`() {
        val placed = TouchEdit.placed(TouchLayout(), TouchKind.BUTTON)
        assertEquals("Button", placed.label)
        assertEquals("Button", placed.title)
        assertEquals("Button", placed.face)

        val named = placed.copy(label = "Jump")
        assertEquals("Jump", named.title)
        assertEquals("Jump", named.face)
        // Renaming does not touch what it sends, and binding does not rename it.
        assertEquals(GamepadAction.None, named.action)
    }

    /** A pad row put on the glass is a *link*, so it still follows the pad table. */
    @Test
    fun `a pad row put on the glass borrows rather than copies`() {
        val placed = TouchEdit.placedPad(TouchLayout(), GamepadControl.A)
        assertEquals(GamepadControl.A, placed.pad)
        assertEquals(GamepadAction.None, placed.action)
        assertEquals("A", placed.title)

        val stick = TouchEdit.placedPad(TouchLayout(), GamepadControl.STICK_R_UP)
        assertEquals(Stick.RIGHT, stick.padStick)
        assertEquals(TouchKind.STICK, stick.kind)

        // A direction off the pad list is that direction, not a cross that
        // swallows the other three and leaves them nowhere to be placed.
        val dpad = TouchEdit.placedPad(TouchLayout(), GamepadControl.DPAD_LEFT)
        assertEquals(TouchKind.BUTTON, dpad.kind)
        assertEquals(GamepadControl.DPAD_LEFT, dpad.pad)
        assertEquals(setOf(GamepadControl.DPAD_LEFT), dpad.padControls)
        assertEquals("D-pad left", dpad.title)

        // And what it borrows is the profile's answer, resolved on the way out.
        val profile = InputProfile.Default.copy(touch = TouchLayout(listOf(placed)))
        assertEquals(
            GamepadProfile.Default.bindings[GamepadControl.A],
            profile.overlay.controls.single().action,
        )
    }
}
