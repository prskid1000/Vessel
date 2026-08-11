package app.vessel.input

/**
 * What one finger on the overlay did, with the identity of `MotionEvent` dropped.
 *
 * [Down.control] is null only in edit mode, where a tap on bare screen is a
 * meaningful thing — it deselects — and is still claimed so that nothing reaches
 * the guest.
 */
sealed interface OverlayTouch {
    val pointerId: Int

    data class Down(
        override val pointerId: Int,
        val control: TouchControl?,
        val x: Float,
        val y: Float,
    ) : OverlayTouch

    data class Move(override val pointerId: Int, val x: Float, val y: Float) : OverlayTouch

    data class Up(override val pointerId: Int) : OverlayTouch
}

/**
 * One touch event, split between the overlay and the gesture machine.
 *
 * [gesturePhase] is null when [PointerGestures] must not be told anything at
 * all — which is most events once a control is under a thumb.
 */
data class RoutedTouch(
    val overlay: List<OverlayTouch> = emptyList(),
    val gesturePhase: TouchPhase? = null,
    val gesturePointers: List<Touch> = emptyList(),
)

/**
 * Which of two machines each finger belongs to, decided once and kept.
 *
 * **This is the crux of the overlay, and written inline it will break.**
 * `SessionSurfaceView` hands every finger to [PointerGestures] today; with an
 * overlay on top, each pointer has to be routed at its own DOWN and stay routed
 * for its whole life. Three things make that harder than it sounds, and each of
 * them is a bug with a symptom nobody would trace back to here:
 *
 * - **A claimed finger must never appear in the list [PointerGestures] sees.**
 *   That machine counts `peakFingers` and maps one, two and three fingers to
 *   left, right and middle click. Without the filter, holding an on-screen fire
 *   button and tapping the screen is a **right click**.
 *
 * - **The phase has to be rewritten.** If pointer 0 is claimed and pointer 1 is
 *   not, pointer 1 arrives from Android as `ACTION_POINTER_DOWN`; the gesture
 *   machine has to see it as [TouchPhase.DOWN] or it will never start a gesture
 *   at all. The mirror case matters as much: a claimed `POINTER_DOWN` must not
 *   reach the machine, or `onExtraDown` takes the contact press back in
 *   `DIRECT` mode. The same rewriting happens on the way up — the last *free*
 *   finger leaving is an [TouchPhase.UP], whatever Android called it.
 *
 * - **Cancel clears both**, and both machines' `reset()` results go to the sink.
 *
 * The router itself is deliberately free of any of that machinery: it decides
 * ownership and rewrites phases, and the caller does the rest. That is what
 * makes `PointerRouterTest` able to state the three-finger trap as an assertion.
 */
class PointerRouter {

    /**
     * Whether the overlay is on screen and taking touches.
     *
     * False routes everything to the gesture machine unchanged, which is exactly
     * what the product did before the overlay existed.
     */
    var enabled: Boolean = false

    /**
     * Edit mode: **every** finger is claimed, including one on bare screen.
     *
     * Not a filtered subset — none. A mode that half-forwarded would be one where
     * you rebind a button by accidentally shooting, and the panel's promise that
     * the guest is not receiving input while you edit has to be literally true.
     */
    var editing: Boolean = false

    private val claimed = mutableSetOf<Int>()

    /** Whether the gesture machine currently believes a gesture is in progress. */
    private var gestureActive = false

    /** Which fingers the overlay owns. For a caller that has to release them. */
    val claimedPointers: Set<Int> get() = claimed.toSet()

    fun route(
        phase: TouchPhase,
        pointers: List<Touch>,
        changedId: Int,
        layout: TouchLayout,
        width: Float,
        height: Float,
    ): RoutedTouch = when (phase) {
        TouchPhase.DOWN, TouchPhase.POINTER_DOWN ->
            onDown(pointers, changedId, layout, width, height)

        TouchPhase.MOVE -> onMove(pointers)
        TouchPhase.POINTER_UP, TouchPhase.UP -> onUp(pointers, changedId)
        TouchPhase.CANCEL -> onCancel()
    }

    /**
     * Forget everything, and say which fingers the overlay was holding.
     *
     * For a view being detached, an overlay being switched off, or a layout being
     * replaced under a thumb. The caller feeds the ids to the translator so the
     * guest is not left holding a key.
     */
    fun reset(): Set<Int> {
        val held = claimed.toSet()
        claimed.clear()
        gestureActive = false
        return held
    }

    // — phases ---------------------------------------------------------------------

    private fun onDown(
        pointers: List<Touch>,
        changedId: Int,
        layout: TouchLayout,
        width: Float,
        height: Float,
    ): RoutedTouch {
        val finger = pointers.firstOrNull { it.id == changedId } ?: return RoutedTouch()
        val control = if (enabled) layout.hitTest(finger.x, finger.y, width, height) else null
        if (enabled && (editing || control != null)) {
            claimed += changedId
            return RoutedTouch(
                overlay = listOf(OverlayTouch.Down(changedId, control, finger.x, finger.y)),
            )
        }
        val free = pointers.filterNot { it.id in claimed }
        // The rewrite. Whether Android called this DOWN or POINTER_DOWN says
        // nothing about whether the gesture machine has a gesture yet.
        val gesturePhase = if (gestureActive) TouchPhase.POINTER_DOWN else TouchPhase.DOWN
        gestureActive = true
        return RoutedTouch(gesturePhase = gesturePhase, gesturePointers = free)
    }

    private fun onMove(pointers: List<Touch>): RoutedTouch {
        val overlay = pointers.filter { it.id in claimed }
            .map { OverlayTouch.Move(it.id, it.x, it.y) }
        val free = pointers.filterNot { it.id in claimed }
        val gesturePhase = if (gestureActive && free.isNotEmpty()) TouchPhase.MOVE else null
        return RoutedTouch(overlay, gesturePhase, free)
    }

    private fun onUp(pointers: List<Touch>, changedId: Int): RoutedTouch {
        if (changedId in claimed) {
            claimed -= changedId
            return RoutedTouch(overlay = listOf(OverlayTouch.Up(changedId)))
        }
        if (!gestureActive) return RoutedTouch()
        // `pointers` still contains the finger that is leaving, which is what
        // Android reports and what makes `peakFingers` line up.
        val free = pointers.filterNot { it.id in claimed }
        val last = free.size <= 1
        if (last) gestureActive = false
        return RoutedTouch(
            gesturePhase = if (last) TouchPhase.UP else TouchPhase.POINTER_UP,
            gesturePointers = free,
        )
    }

    /**
     * Everything let go of, both sides.
     *
     * The gesture machine is told unconditionally: its `onCancel` is `reset`,
     * which is harmless when it was idle and essential when it was mid-drag.
     */
    private fun onCancel(): RoutedTouch {
        val held = claimed.toList().sorted()
        claimed.clear()
        gestureActive = false
        return RoutedTouch(
            overlay = held.map { OverlayTouch.Up(it) },
            gesturePhase = TouchPhase.CANCEL,
            gesturePointers = emptyList(),
        )
    }
}
