package app.vessel.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.vessel.ui.theme.VElev
import app.vessel.ui.theme.Vessel
import app.vessel.ui.theme.VesselTheme
import app.vessel.ui.theme.vContentColumn
import app.vessel.ui.theme.vElevation
import app.vessel.ui.theme.vRing
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * The bottom sheet — the product's second surface, and the one that replaced
 * three screens.
 *
 * New container, editing a container and a program's profile were all pushed
 * destinations with a toolbar and a back arrow. Each was a handful of fields
 * *about the thing the user had just tapped*, and pushing a screen to show them
 * threw away the one piece of context that made them make sense. As sheets they
 * sit over the container they belong to, which is also why the sheet's own title
 * is not a second copy of a title bar.
 *
 * **Drawn in the composition, not in a `Dialog` window.** Two reasons, and the
 * second is the one with scars on it. The background has to stay *visible* — the
 * whole argument for a sheet is the context behind it — and a dialog window
 * paints its own scrim over a window it does not own. And a `Dialog` given
 * `usePlatformDefaultWidth = false` is handed the full display height while being
 * positioned below the status bar, so a panel aligned to its bottom edge puts its
 * own buttons off-screen; see the note on [VConfirmSheet], which is a *centred*
 * panel for exactly that reason.
 *
 * Dismiss is three gestures, all of which must work: swipe the sheet down, tap
 * the scrim, or press back.
 *
 * @param onDismiss called for all three. The caller owns the open/closed state.
 */
@Composable
fun VSheet(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * The row that stays put — a title and the action that commits the sheet.
     *
     * **Outside the scroll, and that is not tidiness.** A landscape window on
     * this phone is 422 dp tall and a five-field form does not fit in it, so the
     * body scrolls; with the header inside that scroll, Save leaves the screen
     * the moment anybody reaches the field they came to change. This is the same
     * mistake the launch checklist made with its last log line, fixed the same
     * way.
     */
    header: (@Composable ColumnScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    BackHandler(onBack = onDismiss)

    BoxWithConstraints(modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val maxSheetHeight = maxHeight * Vessel.metrics.sheetMaxHeightFraction

        // How far the sheet has been dragged down, in pixels. An `Animatable` so
        // releasing short of the threshold springs it back under the 150 ms rule
        // rather than snapping, which is the one place motion here is doing more
        // than confirming.
        val drag = remember { Animatable(0f) }
        val scope = rememberCoroutineScope()
        val settleMs = Vessel.metrics.durationStandardMs
        val dismissAfterPx = with(density) { DISMISS_TRAVEL_FRACTION * maxSheetHeight.toPx() }

        // Nocturne's sheet elevation is `0 0 0 1px #9397ab, 0 -16px 40px` — a ring
        // plus an *upward* shadow. Compose has neither: `Modifier.shadow` blurs
        // evenly around the outline and `border` strokes all four sides.
        //
        // An earlier fix pushed the whole panel one corner-radius below the window
        // so its bottom edge fell off-screen. That did hide the bottom edge, and it
        // was not the artifact people were seeing. Measured off a screencap: the
        // ring was painted at x=0 and x=1263 — the literal screen edges — for 1091
        // rows, straight down to the last pixel. On a full-bleed sheet the side
        // strokes are not panel edges at all, they are a hairline frame drawn
        // around the phone, and where they stop the eye reads a chipped corner.
        //
        // So the ring fades instead of stopping, which is the design system's own
        // rule for a free-standing rule ("rules fade to transparent at their ends
        // rather than stopping cleanly"). The top arc is solid, the sides dissolve
        // over the last [SHEET_RING_FADE], and there is no terminating corner left
        // to look broken. That also retires the overshoot: with the stroke
        // transparent down there, nothing needs pushing off-screen, and the bottom
        // padding no longer has to compensate for a shift the user cannot see.

        // The scrim. Nocturne's `.dialog-backdrop` — `neutral-900` at 50%, never
        // black — so the screen behind stays legible as context.
        Box(
            Modifier
                .fillMaxSize()
                .background(Vessel.colors.scrim)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClickLabel = "Close",
                    onClick = onDismiss,
                ),
        )

        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .offset { IntOffset(0, drag.value.roundToInt()) }
                // Capped and centred, like every other column in the product: a
                // 927 dp landscape window would otherwise set a five-field form
                // across a metre of surface.
                .vContentColumn()
                // A tall sheet must still clear the status bar, or its handle ends
                // up under the clock.
                .statusBarsPadding()
                .heightIn(max = maxSheetHeight)
                .vElevation(VElev.lg, Vessel.metrics.shapeSheet)
                .background(Vessel.colors.surface, Vessel.metrics.shapeSheet)
                .vSheetRing(VElev.lg.ring, Vessel.metrics.shapeSheet)
                // Swallow taps, or every press inside the sheet falls through to
                // the scrim behind it and closes the thing being filled in.
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                )
                .pointerInput(dismissAfterPx) {
                    detectVerticalDragGestures(
                        onDragEnd = {
                            if (drag.value > dismissAfterPx) {
                                onDismiss()
                            } else {
                                scope.launch {
                                    drag.animateTo(0f, tween(settleMs))
                                }
                            }
                        },
                        onDragCancel = { scope.launch { drag.snapTo(0f) } },
                    ) { _, delta ->
                        // Downward only. Dragging a bottom sheet *up* past its own
                        // top edge would open a gap under it, and there is nothing
                        // above it to reveal.
                        scope.launch { drag.snapTo((drag.value + delta).coerceAtLeast(0f)) }
                    }
                }
                .imePadding()
                .navigationBarsPadding()
                .padding(
                    start = Vessel.metrics.s17,
                    end = Vessel.metrics.s17,
                    top = Vessel.metrics.s11,
                    // The gesture bar's inset is already applied above; this is
                    // clear space over it.
                    bottom = Vessel.metrics.s22,
                ),
        ) {
            // The grab handle. It is the affordance for the drag gesture above,
            // and it is the only reason that gesture is discoverable.
            Box(
                Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(bottom = Vessel.metrics.s6)
                    .width(Vessel.metrics.sheetHandleWidth)
                    .size(
                        width = Vessel.metrics.sheetHandleWidth,
                        height = Vessel.metrics.sheetHandleHeight,
                    )
                    .background(Vessel.colors.neutral700, Vessel.metrics.shapePill),
            )

            if (header != null) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(Vessel.metrics.s11),
                    content = header,
                )
                // The rule under a pinned header is what says the body moves and
                // this does not.
                VRule(verticalMargin = Vessel.metrics.s6)
            }

            Column(
                // `weight` rather than a bare scroll: inside a `heightIn`-capped
                // column an unweighted scrolling child measures at its content's
                // height and pushes the header off the top instead of scrolling
                // underneath it.
                Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Vessel.metrics.s11),
                content = content,
            )
        }
    }
}

/** How far down the sheet has to be dragged before releasing it closes it. */
private const val DISMISS_TRAVEL_FRACTION = 0.25f

/**
 * A sheet's first row: what this is, and the one action that commits it.
 *
 * The title is `subtitle` type rather than `title`, because a sheet is not a root
 * — it is an inset panel over one, and a 21 sp heading inside it competes with
 * the screen heading still visible above.
 */
@Composable
fun VSheetHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Vessel.metrics.s11),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading?.invoke()
        Column(
            Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(Vessel.metrics.s3),
        ) {
            Text(
                title,
                style = Vessel.type.subtitle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = Vessel.type.monoSmall,
                    color = Vessel.colors.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        trailing?.invoke(this)
    }
}

/**
 * A choice inside a sheet: a glyph, a name, and the sentence that says what it
 * will do.
 *
 * Used where a sheet offers routes rather than fields — *Browse this container's
 * C:*, *Import from Android storage*. Flat, because the sheet is already a
 * surface and a card inside it would be a box in a box.
 */
@Composable
fun VSheetRow(
    icon: ImageVector,
    title: String,
    help: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val alpha = if (enabled) 1f else Vessel.colors.disabledAlpha
    Row(
        modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClickLabel = title, onClick = onClick)
            .heightIn(min = Vessel.metrics.touchTarget)
            .padding(vertical = Vessel.metrics.s8),
        horizontalArrangement = Arrangement.spacedBy(Vessel.metrics.s11),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            Modifier.size(Vessel.metrics.iconMd),
            tint = Vessel.colors.textMuted.copy(alpha = Vessel.colors.textMuted.alpha * alpha),
        )
        Column(
            Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(Vessel.metrics.s3),
        ) {
            Text(title, style = Vessel.type.body, color = Vessel.colors.textPrimary.copy(alpha = alpha))
            Text(
                help,
                style = Vessel.type.bodySmall,
                color = Vessel.colors.textMuted.copy(alpha = Vessel.colors.textMuted.alpha * alpha),
            )
        }
    }
}

/**
 * A label over a control — Nocturne's `.field > label` plus whatever the field is.
 *
 * The label is above rather than beside, unlike [VParamRow]. A sheet is a form
 * and a form reads down one column; the param row's side-by-side layout exists so
 * a *settings screen* can be read down its right edge, which is a different job.
 */
@Composable
fun VLabeledField(
    label: String,
    modifier: Modifier = Modifier,
    help: String? = null,
    control: @Composable () -> Unit,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Vessel.metrics.s3)) {
        Text(label, style = Vessel.type.label, color = Vessel.colors.textLabel)
        control()
        if (help != null) {
            Text(
                help,
                style = Vessel.type.bodySmall,
                color = Vessel.colors.textMuted,
                modifier = Modifier.padding(top = Vessel.metrics.s3),
            )
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF161826, widthDp = 421, heightDp = 500)
@Composable
private fun VSheetPreview() {
    VesselTheme {
        VSheet(onDismiss = {}) {
            VSheetHeader(
                title = "Add a program",
                subtitle = "to Display proof",
                trailing = { VButton("Add", {}, enabled = false) },
            )
            VLabeledField(
                label = "Executable",
                help = "The name, icon and architecture are read from the file. Nothing here " +
                    "is typed twice.",
            ) {
                VTextField(value = "", onValueChange = {}, placeholder = "choose an .exe on C:")
            }
            VRule(verticalMargin = Vessel.metrics.s6)
            VSheetRow(
                icon = VIcons.Folder,
                title = "Browse this container's C:",
                help = "Opens the file browser to pick the file.",
                onClick = {},
            )
        }
    }
}

/**
 * How far up from the bottom the sheet's hairline dissolves.
 *
 * Nocturne fades a free-standing rule over 48 px a side. This is that number,
 * applied to the two edges of a sheet that have nowhere to terminate.
 */
private val SHEET_RING_FADE: Dp = 48.dp

/**
 * The sheet's hairline: solid across the top arc, transparent by the bottom.
 *
 * `Modifier.border` cannot do this — it strokes the whole outline in one colour,
 * and on a full-bleed sheet that paints a 1 px frame down both screen edges which
 * then simply stops. Measured on the device before this existed: ring pixels at
 * x=0 and x=1263 for 1091 consecutive rows. What a user reports is "the bottom
 * corners are chipped", because a line that ends without closing reads as
 * damage rather than as a choice.
 *
 * So the same outline is stroked with a vertical gradient instead. The stops are
 * in fractions of the sheet's own height, which is why the fade is computed per
 * draw rather than baked in: a short sheet and a full-height one must dissolve
 * over the same 48 dp, not over the same proportion.
 *
 * `drawWithCache` and not `drawBehind`: the outline and the brush depend only on
 * the size, so they are rebuilt when it changes and not once per frame while the
 * sheet is being dragged.
 */
private fun Modifier.vSheetRing(color: Color, shape: Shape, width: Dp = 1.dp): Modifier =
    this.drawWithCache {
        val outline = shape.createOutline(size, layoutDirection, this)
        val fade = (SHEET_RING_FADE.toPx() / size.height).coerceIn(0f, 1f)
        val brush = Brush.verticalGradient(
            // Solid until the fade begins, then out. Two stops at the same colour
            // rather than one, or the gradient would start dissolving at the top
            // arc — which is the one part of this outline that must stay crisp.
            0f to color,
            (1f - fade) to color,
            1f to Color.Transparent,
            startY = 0f,
            endY = size.height,
        )
        onDrawWithContent {
            drawContent()
            drawOutline(outline, brush = brush, style = Stroke(width = width.toPx()))
        }
    }
