package app.vessel.display

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import app.vessel.input.TouchControl
import app.vessel.input.TouchEdit
import app.vessel.input.TouchKind
import app.vessel.input.TouchLayout

/**
 * The on-screen controls, drawn.
 *
 * **An `android.graphics` painter rather than a Compose overlay, and the reason
 * is touch routing rather than taste.** Every finger has to arrive at one view,
 * because the overlay and the trackpad share the screen and a pointer is claimed
 * by whichever of them it landed on — see `PointerRouter`. Android delivers a
 * whole gesture to whichever view consumed its first DOWN, so a Compose layer
 * that consumed a press on a fire button would take every later finger with it
 * and the trackpad would go dead for the rest of the gesture. One view takes all
 * the touches; the same view therefore has to draw.
 *
 * The colours are transcribed from `VesselTheme` rather than read from it: a
 * `View` has no composition to read a theme out of, and the four values below are
 * the four this overlay uses. They are named after their theme tokens so the two
 * can be compared by eye when either moves.
 */
internal class TouchOverlayPainter(private val density: Float) {

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        isFakeBoldText = false
    }
    private val bounds = Rect()
    private val cross = Path()
    private val box = RectF()

    /**
     * Draw the whole overlay.
     *
     * [held] is the set of control ids with a finger on them, which is the only
     * feedback a glass button can give that a physical one gives for free.
     */
    fun draw(
        canvas: Canvas,
        layout: TouchLayout,
        width: Float,
        height: Float,
        editing: Boolean,
        selectedId: String?,
        held: Set<String>,
    ) {
        if (width <= 0f || height <= 0f) return
        layout.controls.forEach { control ->
            drawControl(
                canvas = canvas,
                control = control,
                width = width,
                height = height,
                editing = editing,
                selected = editing && control.id == selectedId,
                pressed = control.id in held,
            )
        }
    }

    private fun drawControl(
        canvas: Canvas,
        control: TouchControl,
        width: Float,
        height: Float,
        editing: Boolean,
        selected: Boolean,
        pressed: Boolean,
    ) {
        val cx = control.centreX(width)
        val cy = control.centreY(height)
        val r = control.radiusPx(width, height)
        if (r <= 0f) return

        // **Edit mode is drawn at full strength whatever the control's own
        // opacity says.** A control you are placing has to be visible to be
        // placed, and a 10% ghost being dragged around is the one state where the
        // per-control opacity actively fights the task.
        val alpha = if (editing) 1f else control.opacity
        val pressBoost = if (pressed) PRESS_BOOST else 0f

        fill.color = withAlpha(SURFACE, (alpha * SURFACE_ALPHA + pressBoost).coerceAtMost(1f))
        stroke.color = when {
            selected -> withAlpha(ACCENT, 1f)
            editing -> withAlpha(ACCENT, EDIT_RING_ALPHA)
            pressed -> withAlpha(ACCENT, 1f)
            else -> withAlpha(INK, alpha.coerceAtLeast(MIN_RING_ALPHA))
        }
        stroke.strokeWidth = (if (selected) 2f else 1f) * density

        // **A d-pad is drawn as a cross, not as a square with a cross on it.**
        // The silhouette is the whole affordance: a thumb finds four directions
        // by feel from the shape, and a rounded box with two scratched lines
        // reads as a button somebody drew a plus on.
        //
        // Its *hit* area stays the full square — see `TouchControl.contains`.
        // The corners are where a diagonal lives, and a d-pad you cannot go
        // north-east on is not one.
        if (control.kind == TouchKind.DPAD) {
            crossPath(cx, cy, r)
            canvas.drawPath(cross, fill)
            canvas.drawPath(cross, stroke)
        } else if (control.round) {
            canvas.drawCircle(cx, cy, r, fill)
            canvas.drawCircle(cx, cy, r, stroke)
        } else {
            box.set(cx - r, cy - r, cx + r, cy + r)
            val radius = CORNER_DP * density
            canvas.drawRoundRect(box, radius, radius, fill)
            canvas.drawRoundRect(box, radius, radius, stroke)
        }

        val label = control.face
        if (label.isNotEmpty()) {
            text.color = withAlpha(INK, (alpha + LABEL_BOOST).coerceAtMost(1f))
            text.textSize = labelSize(r, label)
            text.getTextBounds(label, 0, label.length, bounds)
            canvas.drawText(label, cx, cy - (bounds.top + bounds.bottom) / 2f, text)
        }

        if (editing && selected) drawHandle(canvas, control, width, height)
    }

    /**
     * The twelve corners of a plus, inscribed in the control's square.
     *
     * [ARM] is the half-width of each arm as a fraction of the radius, and so it
     * is what decides how long the arms read — see the constant.
     */
    private fun crossPath(cx: Float, cy: Float, r: Float) {
        val a = r * ARM
        cross.reset()
        cross.moveTo(cx - a, cy - r)
        cross.lineTo(cx + a, cy - r)
        cross.lineTo(cx + a, cy - a)
        cross.lineTo(cx + r, cy - a)
        cross.lineTo(cx + r, cy + a)
        cross.lineTo(cx + a, cy + a)
        cross.lineTo(cx + a, cy + r)
        cross.lineTo(cx - a, cy + r)
        cross.lineTo(cx - a, cy + a)
        cross.lineTo(cx - r, cy + a)
        cross.lineTo(cx - r, cy - a)
        cross.lineTo(cx - a, cy - a)
        cross.close()
    }

    /** The grip that resizes the selected control, on its lower-right diagonal. */
    private fun drawHandle(canvas: Canvas, control: TouchControl, width: Float, height: Float) {
        val hx = TouchEdit.handleX(control, width, height)
        val hy = TouchEdit.handleY(control, width, height)
        val hr = HANDLE_DP * density
        fill.color = withAlpha(ACCENT, 1f)
        canvas.drawCircle(hx, hy, hr, fill)
    }

    private fun labelSize(radius: Float, label: String): Float = labelSize(radius, label, density)

    private fun withAlpha(rgb: Int, alpha: Float): Int =
        Color.argb((alpha.coerceIn(0f, 1f) * 255).toInt(), Color.red(rgb), Color.green(rgb), Color.blue(rgb))

    internal companion object {
        /**
         * A label sized to fit inside the control it names.
         *
         * `W A S D` in a small stick and `Space` in a large button want different
         * numbers, and a fixed size makes one of them overflow. The cap keeps a
         * 20%-of-the-screen look pad from setting its label in a display face.
         *
         * It is on the companion, and internal, because the layout panel's
         * preview has to size its labels by the same rule. A preview that sets
         * `SELECT` in a fixed face clips it to `SE` at panel scale while the
         * overlay itself shows the whole word, and then the preview is lying
         * about the thing it exists to show.
         *
         * [floorDp] is why that is not simply the same call. The floor exists so
         * that a small button over a session never sets its name at four pixels;
         * it is a *legibility* rule and it belongs to the overlay. A preview
         * control is a seventh of overlay size, so the floor is more than twice
         * the width the glyphs have, and holding it is exactly what turned
         * `SELECT` into `SE` and `R2` into `R`. The preview passes zero: a
         * miniature that is too small to read is honest, and one that shows the
         * wrong word is not.
         */
        fun labelSize(
            radius: Float,
            label: String,
            density: Float,
            floorDp: Float = MIN_LABEL_DP,
        ): Float {
            val byWidth = (radius * 2f * LABEL_FIT) / label.length.coerceAtLeast(1)
            return byWidth.coerceIn(floorDp * density, MAX_LABEL_DP * density)
        }

        /** `--color-surface-2` — the ground every card in the product sits on. */
        const val SURFACE = 0xFF232532.toInt()

        /** `--color-accent`. */
        const val ACCENT = 0xFF9184D9.toInt()

        /** `--color-text-primary`. */
        const val INK = 0xFFE9E9ED.toInt()

        /** How solid the ground is relative to the control's opacity. */
        const val SURFACE_ALPHA = 0.85f

        /** Extra ground under a finger. The only press feedback glass can give. */
        const val PRESS_BOOST = 0.30f

        /** A ring never goes below this, or a 10% control is a rumour. */
        const val MIN_RING_ALPHA = 0.35f

        /** A label reads a little stronger than its control, so it stays legible. */
        const val LABEL_BOOST = 0.25f

        const val EDIT_RING_ALPHA = 0.55f

        const val CORNER_DP = 8f
        const val HANDLE_DP = 7f
        const val MIN_LABEL_DP = 8f
        const val MAX_LABEL_DP = 15f

        /** How much of a control's width a label may take. */
        const val LABEL_FIT = 1.1f

        /**
         * A d-pad arm's half-width, as a fraction of its radius.
         *
         * The arms reach the full radius either way, so this is what sets how
         * *long* they look: an arm's visible stub is `r - a`, and thinning the
         * waist is the only way to lengthen it without growing the control.
         * What the eye actually reads is the stub against its own width,
         * `(r - a) : 2a` — 0.42 gave 0.7:1 and looked like a fat plus, 0.30 gave
         * 1.2:1 and was still read as too short. 0.24 is 1.6:1, which keeps the
         * cross reading as a cross while leaving the arms something a thumb can
         * land on; below about 0.17 they go spindly and the shape reads as
         * decoration rather than as a control.
         *
         * Longer arms and thicker arms pull against each other at a fixed radius,
         * so the d-pad was made larger instead — see `TouchLayouts.Gamepad`. On
         * this panel that is a 73 px arm reaching 115 px, against 44 px reaching
         * 87 px before: both longer and thicker, which a waist alone cannot do.
         */
        const val ARM = 0.24f
    }
}
