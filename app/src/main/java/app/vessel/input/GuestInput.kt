package app.vessel.input

/**
 * Everything Vessel can ask the guest's input devices to do, as data.
 *
 * The whole point of this type is that the gesture machines, the gamepad
 * translator and the keycode table can be written — and tested — without an
 * `XServer`, a `MotionEvent` or a device. They produce these; one sink in
 * `app.vessel.display` turns them into X11 events. Nothing in this package
 * imports `com.winlator`, which is the arrangement `docs/LICENSING.md` asks for.
 */
sealed interface GuestInput {

    /**
     * Put the cursor at this point, in **view pixels**.
     *
     * View pixels rather than X screen pixels because the letterbox transform
     * lives with the compositor: the machine that decides *where* has no idea
     * how the desktop is fitted into the surface, and should not.
     */
    data class MoveTo(val x: Float, val y: Float) : GuestInput

    /** Move the cursor by this much, in view pixels. Gain is already applied. */
    data class MoveBy(val dx: Float, val dy: Float) : GuestInput

    data class Button(val button: PointerButton, val pressed: Boolean) : GuestInput

    /**
     * [ticks] wheel detents, signed: positive is up / right, which is buttons 4
     * and 7, and negative is down / left, buttons 5 and 6.
     */
    data class Scroll(val axis: ScrollAxis, val ticks: Int) : GuestInput

    /**
     * A pinch, as the only thing X11 can express it as: `Ctrl` held over
     * [ticks] wheel detents, positive being zoom in.
     *
     * Not decomposed into a Key and a Scroll here because the ordering matters
     * and is a property of the *sink* — the modifier has to be down before the
     * first detent and up after the last, and a caller that had to remember
     * that would eventually forget.
     */
    data class Zoom(val ticks: Int) : GuestInput

    /**
     * One key edge, addressed by **X11 keycode** — see [X11].
     *
     * [keysym] is 0 for any key the vendored server already has a keysym for,
     * and the X11 keysym otherwise; the server installs it and sends a
     * `MappingNotify` the first time it sees one it does not know.
     */
    data class Key(val keycode: Int, val keysym: Int = 0, val pressed: Boolean) : GuestInput
}

/**
 * The three buttons and the four wheel directions, as X11 numbers them.
 *
 * The vendored `Pointer.Button` is the same set in the same order; the display
 * sink maps across. Duplicated rather than imported so this package stays free
 * of `com.winlator`, and pinned by a test that compares the two.
 */
enum class PointerButton(val x11Code: Int) {
    LEFT(1),
    MIDDLE(2),
    RIGHT(3),
    SCROLL_UP(4),
    SCROLL_DOWN(5),
    SCROLL_LEFT(6),
    SCROLL_RIGHT(7),
}

enum class ScrollAxis { VERTICAL, HORIZONTAL }

/**
 * What one finger on the phone's own screen means.
 *
 * The two modes exist because the software cannot infer which is wanted, and
 * they differ in **exactly one thing**: whether one-finger motion puts the
 * cursor under the finger or pushes it along. Every other gesture — the taps,
 * the two-finger scroll, the pinch, the long-press drag — is identical, so
 * switching modes mid-session never changes what a gesture means, only where
 * the cursor ends up.
 */
enum class PointerMode {
    /** Relative, like a laptop trackpad. Correct for anything with mouselook. */
    TRACKPAD,

    /** Absolute: the cursor goes where the finger is. Correct for desktop UI. */
    DIRECT,
    ;

    fun toggled(): PointerMode = if (this == TRACKPAD) DIRECT else TRACKPAD
}
