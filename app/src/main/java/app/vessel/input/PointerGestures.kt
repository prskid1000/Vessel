package app.vessel.input

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/** One finger, in **view pixels**. */
data class Touch(val id: Int, val x: Float, val y: Float)

/** What Android's `actionMasked` says happened, with the identity of `MotionEvent` dropped. */
enum class TouchPhase { DOWN, POINTER_DOWN, MOVE, POINTER_UP, UP, CANCEL }

/**
 * Distances in view pixels and times in milliseconds.
 *
 * Defaults are for a phone held in landscape at arm's length. The two that were
 * actually tuned rather than guessed are [tapSlop] — a resting thumb wanders
 * about 8 px on this panel, so anything under that is still a tap — and
 * [scrollDetent], which is the distance that makes a two-finger drag scroll a
 * Windows list at roughly the speed the same drag scrolls an Android one.
 */
data class GestureConfig(
    val tapSlop: Float = 12f,
    val tapTimeoutMs: Long = 220,
    val longPressMs: Long = 380,
    val scrollDetent: Float = 48f,
    /** Distance the finger pair must change by before the gesture commits to pinch. */
    val pinchDetent: Float = 56f,
    /** Distance either interpretation must move before the pinch/scroll choice is made. */
    val decisionSlop: Float = 20f,
    /** Cursor travel per finger travel in [PointerMode.TRACKPAD]. */
    val trackpadGain: Float = 1.5f,
)

/**
 * Fingers in, [GuestInput] out. No Android types, no clock, no `XServer`.
 *
 * Every gesture this understands is listed in one place, because the set is the
 * contract a user has to learn and it is short on purpose:
 *
 * | Gesture | Result |
 * |---|---|
 * | one finger moving | cursor — absolute in `DIRECT`, relative in `TRACKPAD` |
 * | one-finger tap | left click |
 * | two-finger tap | right click |
 * | three-finger tap | middle click |
 * | one finger held, then moved | left button drag |
 * | two fingers dragged | scroll, vertical and horizontal |
 * | two fingers pinched | zoom, as `Ctrl` + wheel |
 *
 * [PointerMode] changes exactly one row of that table, which is the property
 * that makes switching mid-session safe: a user who has learned the gestures
 * does not have to learn them again.
 *
 * ## Time
 *
 * There is no `Handler` here — a long press is the one gesture that fires when
 * *nothing* happens, so the machine cannot produce it unprompted. Instead
 * [timeoutAt] says when it next wants to be asked, and the caller schedules
 * that and calls [onTimeout]. A caller that never does loses the long press and
 * nothing else, and a test drives it without a looper.
 */
class PointerGestures(
    var mode: PointerMode = PointerMode.TRACKPAD,
    private val config: GestureConfig = GestureConfig(),
) {

    private enum class State { IDLE, ONE_FINGER, DRAGGING, MULTI, SPENT }

    /** What a two-finger gesture turned out to be, once it is no longer ambiguous. */
    private enum class Multi { UNDECIDED, SCROLL, PINCH }

    private var state = State.IDLE
    private var multi = Multi.UNDECIDED

    private var downAtMs = 0L
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var travel = 0f

    /** The most fingers seen at once during this gesture — what a tap is counted by. */
    private var peakFingers = 0

    private var scrollResidualX = 0f
    private var scrollResidualY = 0f
    private var pinchBaseline = 0f
    private var centroidTravel = 0f
    private var lastCentroidX = 0f
    private var lastCentroidY = 0f

    /**
     * When the caller should next call [onTimeout], or null if the machine is
     * not waiting on anything. Re-read after every call; it changes freely.
     */
    var timeoutAt: Long? = null
        private set

    /**
     * Feed one touch event.
     *
     * [pointers] is every finger currently down, including the one this event is
     * about — on [TouchPhase.POINTER_UP] that is the finger that is leaving,
     * which is what Android reports too and what makes the count line up with
     * [peakFingers].
     */
    fun onTouch(phase: TouchPhase, pointers: List<Touch>, timeMs: Long): List<GuestInput> =
        when (phase) {
            TouchPhase.DOWN -> onFirstDown(pointers, timeMs)
            TouchPhase.POINTER_DOWN -> onExtraDown(pointers)
            TouchPhase.MOVE -> onMove(pointers)
            TouchPhase.POINTER_UP -> onPointerUp(pointers)
            TouchPhase.UP -> onLastUp(timeMs)
            TouchPhase.CANCEL -> onCancel()
        }

    /**
     * The scheduled moment arrived.
     *
     * Idempotent and safe to call late: the only timeout is the long press, and
     * it is only honoured while a single finger is still resting inside the slop.
     */
    fun onTimeout(timeMs: Long): List<GuestInput> {
        val deadline = timeoutAt ?: return emptyList()
        if (timeMs < deadline) return emptyList()
        timeoutAt = null
        if (state != State.ONE_FINGER || travel > config.tapSlop) return emptyList()
        state = State.DRAGGING
        return listOf(GuestInput.Button(PointerButton.LEFT, pressed = true))
    }

    /** Release anything held. For a session that is stopping, or a view losing its window. */
    fun reset(): List<GuestInput> {
        val held = state == State.DRAGGING
        hardReset()
        return if (held) listOf(GuestInput.Button(PointerButton.LEFT, pressed = false)) else emptyList()
    }

    // — phases ---------------------------------------------------------------------

    private fun onFirstDown(pointers: List<Touch>, timeMs: Long): List<GuestInput> {
        hardReset()
        val finger = pointers.firstOrNull() ?: return emptyList()
        state = State.ONE_FINGER
        peakFingers = 1
        downAtMs = timeMs
        downX = finger.x
        downY = finger.y
        lastX = finger.x
        lastY = finger.y
        // **DIRECT presses on contact; TRACKPAD waits.** In direct touch the
        // finger *is* the pointer, so putting the button down the moment it
        // lands is what makes a drag a drag — dragging a title bar, pulling a
        // window's edge, sweeping a text selection. It was measured not
        // working: a finger drag across a caption moved the cursor and left the
        // window where it was, because the button only went down after a 380 ms
        // hold and any movement before that cancelled the hold. A window you can
        // only move by pressing still first, then moving, is one nobody
        // discovers they can move.
        //
        // The cost is real and worth naming: in DIRECT there is no longer any
        // way to move the cursor without pressing. That is what direct touch
        // means everywhere else, and TRACKPAD is the mode for moving a pointer
        // without clicking — which is why there are two.
        //
        // Release is [onUp]'s existing job, and a down-then-up with no movement
        // is still a click, so tapping is unchanged.
        state = if (mode == PointerMode.DIRECT) State.DRAGGING else State.ONE_FINGER
        timeoutAt = if (mode == PointerMode.DIRECT) null else timeMs + config.longPressMs
        return if (mode == PointerMode.DIRECT) {
            listOf(
                GuestInput.MoveTo(finger.x, finger.y),
                GuestInput.Button(PointerButton.LEFT, pressed = true),
            )
        } else {
            emptyList()
        }
    }

    private fun onExtraDown(pointers: List<Touch>): List<GuestInput> {
        peakFingers = maxOf(peakFingers, pointers.size)
        // A second finger cancels a pending long press but not a drag already in
        // progress: lifting a finger mid-drag must not drop the button.
        timeoutAt = null

        // **A second finger that arrives before the first has moved takes the
        // press back.** DIRECT presses on contact, so by the time a two-finger
        // right click has both fingers down the left button is already held —
        // and without this it would stay held, the multi-touch branch would
        // never be reached, and right click, scroll and pinch would all be gone
        // from the mode that needs them most. The undo is bounded by the tap
        // slop: once the first finger has actually dragged something, a second
        // finger landing is a stray touch and must not drop the button.
        val undoContactPress = mode == PointerMode.DIRECT &&
            state == State.DRAGGING &&
            travel <= config.tapSlop &&
            pointers.size >= 2
        if (!undoContactPress && state == State.DRAGGING) return emptyList()
        if (pointers.size < 2) return emptyList()
        val release = if (undoContactPress) {
            listOf(GuestInput.Button(PointerButton.LEFT, pressed = false))
        } else {
            emptyList()
        }

        state = State.MULTI
        multi = Multi.UNDECIDED
        scrollResidualX = 0f
        scrollResidualY = 0f
        centroidTravel = 0f
        pinchBaseline = spread(pointers)
        lastCentroidX = centroidX(pointers)
        lastCentroidY = centroidY(pointers)
        return release
    }

    private fun onMove(pointers: List<Touch>): List<GuestInput> = when (state) {
        State.ONE_FINGER, State.DRAGGING -> oneFingerMove(pointers)
        State.MULTI -> multiMove(pointers)
        State.IDLE, State.SPENT -> emptyList()
    }

    private fun oneFingerMove(pointers: List<Touch>): List<GuestInput> {
        val finger = pointers.firstOrNull() ?: return emptyList()
        val dx = finger.x - lastX
        val dy = finger.y - lastY
        lastX = finger.x
        lastY = finger.y
        travel += hypot(dx, dy)

        // Moving past the slop means this was never a tap, and never a long
        // press either — dropping the timeout here is what stops a slow drag
        // from suddenly pressing the button halfway through.
        if (state == State.ONE_FINGER && travel > config.tapSlop) timeoutAt = null

        return when (mode) {
            PointerMode.DIRECT -> listOf(GuestInput.MoveTo(finger.x, finger.y))
            PointerMode.TRACKPAD ->
                if (dx == 0f && dy == 0f) {
                    emptyList()
                } else {
                    listOf(GuestInput.MoveBy(dx * config.trackpadGain, dy * config.trackpadGain))
                }
        }
    }

    private fun multiMove(pointers: List<Touch>): List<GuestInput> {
        if (pointers.size < 2) return emptyList()
        val cx = centroidX(pointers)
        val cy = centroidY(pointers)
        val dCx = cx - lastCentroidX
        val dCy = cy - lastCentroidY
        lastCentroidX = cx
        lastCentroidY = cy
        centroidTravel += hypot(dCx, dCy)

        val spread = spread(pointers)
        val spreadDelta = spread - pinchBaseline

        if (multi == Multi.UNDECIDED) {
            // Whichever interpretation has moved further wins, and only once one
            // of them is unambiguous. Deciding on the first frame instead reads
            // as "pinch sometimes scrolls", because two fingers never land at
            // exactly the same instant and the first frame is mostly noise.
            val pinchEvidence = abs(spreadDelta)
            val scrollEvidence = centroidTravel
            if (maxOf(pinchEvidence, scrollEvidence) < config.decisionSlop) return emptyList()
            multi = if (pinchEvidence > scrollEvidence) Multi.PINCH else Multi.SCROLL
            // The evidence itself is not replayed as movement: it was spent
            // deciding, and replaying it makes every gesture start with a jump.
            scrollResidualX = 0f
            scrollResidualY = 0f
            pinchBaseline = spread
            return emptyList()
        }

        return if (multi == Multi.PINCH) pinch(spread) else scroll(dCx, dCy)
    }

    private fun pinch(spread: Float): List<GuestInput> {
        val detents = ((spread - pinchBaseline) / config.pinchDetent).toInt()
        if (detents == 0) return emptyList()
        pinchBaseline += detents * config.pinchDetent
        return listOf(GuestInput.Zoom(detents))
    }

    private fun scroll(dx: Float, dy: Float): List<GuestInput> {
        scrollResidualX += dx
        scrollResidualY += dy
        val out = mutableListOf<GuestInput>()

        // Content follows the fingers, which is the convention every touch
        // surface uses and the opposite of what a wheel does: dragging *down*
        // pulls the page down, and pulling the page down is wheel-up.
        val vertical = (scrollResidualY / config.scrollDetent).toInt()
        if (vertical != 0) {
            scrollResidualY -= vertical * config.scrollDetent
            out += GuestInput.Scroll(ScrollAxis.VERTICAL, vertical)
        }
        val horizontal = (scrollResidualX / config.scrollDetent).toInt()
        if (horizontal != 0) {
            scrollResidualX -= horizontal * config.scrollDetent
            out += GuestInput.Scroll(ScrollAxis.HORIZONTAL, horizontal)
        }
        return out
    }

    private fun onPointerUp(pointers: List<Touch>): List<GuestInput> {
        // Down to one finger. Do not fall back to one-finger cursor motion: the
        // remaining finger has not been tracked and would teleport the cursor by
        // the distance between the two.
        if (pointers.size <= 2 && state == State.MULTI) state = State.SPENT
        return emptyList()
    }

    private fun onLastUp(timeMs: Long): List<GuestInput> {
        // Everything the verdict depends on, read before the reset clears it.
        // `peakFingers` in particular: resetting first makes every tap a
        // zero-finger tap, which is silently no click at all.
        val finishing = state
        val fingers = peakFingers
        val quick = timeMs - downAtMs <= config.tapTimeoutMs
        val still = travel <= config.tapSlop
        hardReset()

        return when {
            finishing == State.DRAGGING ->
                listOf(GuestInput.Button(PointerButton.LEFT, pressed = false))

            // A tap is counted by the most fingers that were ever down, not by
            // how many are left: a two-finger tap almost always lifts one finger
            // a frame before the other, and counting the survivors makes it a
            // left click about half the time.
            quick && still -> tap(fingers)

            else -> emptyList()
        }
    }

    private fun tap(fingers: Int): List<GuestInput> {
        val button = when (fingers) {
            1 -> PointerButton.LEFT
            2 -> PointerButton.RIGHT
            3 -> PointerButton.MIDDLE
            else -> return emptyList()
        }
        return listOf(
            GuestInput.Button(button, pressed = true),
            GuestInput.Button(button, pressed = false),
        )
    }

    private fun onCancel(): List<GuestInput> = reset()

    private fun hardReset() {
        state = State.IDLE
        multi = Multi.UNDECIDED
        timeoutAt = null
        travel = 0f
        peakFingers = 0
        scrollResidualX = 0f
        scrollResidualY = 0f
        centroidTravel = 0f
    }

    private fun centroidX(p: List<Touch>) = p.sumOf { it.x.toDouble() }.toFloat() / p.size
    private fun centroidY(p: List<Touch>) = p.sumOf { it.y.toDouble() }.toFloat() / p.size

    /** Distance between the two fingers a pinch is measured by. */
    private fun spread(p: List<Touch>): Float {
        if (p.size < 2) return 0f
        return hypot(p[0].x - p[1].x, p[0].y - p[1].y)
    }
}

/**
 * A scroll of [ticks] detents, as the button presses X11 actually carries it as.
 *
 * Here rather than in the sink because the sink is the one file that may not be
 * unit tested — it needs an `XServer` — and "up is 4, down is 5" is exactly the
 * kind of thing that is wrong for a week.
 */
fun GuestInput.Scroll.buttons(): List<GuestInput.Button> {
    val button = when {
        axis == ScrollAxis.VERTICAL && ticks > 0 -> PointerButton.SCROLL_UP
        axis == ScrollAxis.VERTICAL -> PointerButton.SCROLL_DOWN
        ticks > 0 -> PointerButton.SCROLL_RIGHT
        else -> PointerButton.SCROLL_LEFT
    }
    return buildList {
        repeat(abs(ticks)) {
            add(GuestInput.Button(button, pressed = true))
            add(GuestInput.Button(button, pressed = false))
        }
    }
}

/** Round a float delta to whole pixels, keeping the remainder for the next call. */
internal class SubPixel {
    private var residualX = 0f
    private var residualY = 0f

    /**
     * Accumulate [dx]/[dy] and return the whole pixels available now.
     *
     * A gain of 1.5 on a 60 Hz finger produces deltas like 0.4 px, and truncating
     * each of those independently loses most of a slow drag — the cursor sticks
     * and then jumps. Keeping the remainder is what makes slow motion smooth.
     */
    fun take(dx: Float, dy: Float): Pair<Int, Int> {
        residualX += dx
        residualY += dy
        val x = residualX.toInt()
        val y = residualY.toInt()
        residualX -= x
        residualY -= y
        return x to y
    }

    fun reset() {
        residualX = 0f
        residualY = 0f
    }
}

/** Whole-pixel rounding for an absolute position; no residual to keep. */
internal fun Float.toPixel(): Int = roundToInt()
