package app.vessel.display

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.view.MotionEvent
import android.view.View
import app.vessel.input.TouchControl
import app.vessel.input.TouchEdit
import app.vessel.input.TouchLayout

/**
 * The overlay, alone on the screen, with a finger arranging it.
 *
 * **A `View` drawing through [TouchOverlayPainter] rather than a Compose canvas,
 * and the reason is that this screen's whole claim is to be the overlay.** The
 * panel's little map is a picture *of* the overlay and has already been caught
 * twice telling the truth about the wrong thing — a d-pad at the wrong
 * proportion, then labels at the wrong size. A full-screen editor that redrew
 * the controls a third way would be the same bug waiting to happen at the size
 * where it matters most. So the painter is the one that draws here too, at the
 * real screen's dimensions, and what you drag is what you will play with.
 *
 * The touch dispatch is its own, and much smaller than the session's. There is
 * no guest to route a stray finger to, no trackpad sharing the glass and no
 * `PointerRouter`: one pointer at a time either grabs a control, grabs the
 * selected control's resize grip, or lands on nothing and clears the selection.
 *
 * The layout is published on **release**, not per frame, for the same reason
 * `XServerDisplay.editTouch` does it: a drag emits a position per frame and the
 * profile is a DataStore two layers away.
 */
@SuppressLint("ViewConstructor")
internal class TouchArrangeView(context: Context) : View(context) {

    private val painter = TouchOverlayPainter(resources.displayMetrics.density)

    /** Which control the finger has, and whether it is moving or resizing it. */
    private var dragId: String? = null
    private var dragPointer = -1
    private var resizing = false

    private var drawn: TouchLayout = TouchLayout()

    /**
     * What is on screen.
     *
     * A drag owns it until it lets go. Without that guard the state round trip
     * — publish, recompose, hand it back — lands mid-gesture and the control
     * jumps to where it was two frames ago.
     */
    var layout: TouchLayout
        get() = drawn
        set(value) {
            if (dragId != null) return
            drawn = value
            invalidate()
        }

    var selected: String? = null
        set(value) {
            field = value
            invalidate()
        }

    var onSelect: (String?) -> Unit = {}
    var onLayout: (TouchLayout) -> Unit = {}

    override fun onDraw(canvas: Canvas) {
        painter.draw(
            canvas = canvas,
            layout = drawn,
            width = width.toFloat(),
            height = height.toFloat(),
            // Always. This screen exists to place controls, and a control at 10%
            // opacity is one you cannot see to place — the same argument the
            // painter already makes for the session's edit mode.
            editing = true,
            selectedId = selected,
            held = emptySet(),
        )
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val x = event.x
                val y = event.y
                val control = hit(x, y, w, h)
                if (control == null) {
                    selected = null
                    onSelect(null)
                    return true
                }
                // Read before the selection moves: the grip is only live on the
                // control that was *already* selected, because that is the only
                // one it is drawn on.
                val wasSelected = selected == control.id
                resizing = wasSelected && TouchEdit.onHandle(control, x, y, w, h)
                selected = control.id
                onSelect(control.id)
                dragId = control.id
                dragPointer = event.getPointerId(0)
            }

            MotionEvent.ACTION_MOVE -> {
                val id = dragId ?: return true
                val index = event.findPointerIndex(dragPointer)
                if (index < 0) return true
                val control = drawn.byId(id) ?: return true
                val next = if (resizing) {
                    TouchEdit.resized(control, event.getX(index), event.getY(index), w, h)
                } else {
                    TouchEdit.moved(control, event.getX(index), event.getY(index), w, h)
                }
                drawn = drawn.with(next)
                invalidate()
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val had = dragId != null
                dragId = null
                dragPointer = -1
                resizing = false
                if (had) onLayout(drawn)
            }
        }
        return true
    }

    /**
     * The control under a finger, topmost first.
     *
     * Reverse order because the painter draws the list front to back, so the last
     * one drawn is the one on top — and where two controls overlap, the one you
     * can see is the one you meant.
     */
    private fun hit(x: Float, y: Float, w: Float, h: Float): TouchControl? {
        drawn.controls.asReversed().forEach { control ->
            if (selected == control.id && TouchEdit.onHandle(control, x, y, w, h)) return control
            if (control.contains(x, y, w, h)) return control
        }
        return null
    }
}
