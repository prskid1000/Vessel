package app.vessel.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import app.vessel.ui.theme.VElev
import app.vessel.ui.theme.Vessel
import app.vessel.ui.theme.VesselTheme
import app.vessel.ui.theme.vElevation
import app.vessel.ui.theme.vRing
import kotlin.math.roundToInt

/**
 * One setting from the manifest: a label line, its control directly beneath, and
 * the sentence that says what the control does.
 *
 * **The row is flat** — no card. Rows sit directly on `bg`, separated by
 * vertical rhythm alone, so the only ruled things on the screen are the
 * controls. The right-aligned [value] lets the whole configuration be read down
 * one edge without touching a control; [valueIsMachine] sets it in mono.
 *
 * [help] is the point of the container editor, not garnish — DESIGN.md's rule is
 * that a knob which cannot be explained in one plain sentence does not ship — so
 * it renders under the control, never truncated, wrapping as far as it needs.
 *
 * [note] is a fact about the control's *bounds* (an active clamp, a selector
 * that resolved to nothing); [warning] is a value that is dangerous right now.
 * [trailing] sits on the label line, where a boolean's switch goes; [control]
 * sits under it and spans the width.
 */
@Composable
fun VParamRow(
    title: String,
    help: String?,
    modifier: Modifier = Modifier,
    value: String? = null,
    valueIsMachine: Boolean = false,
    note: String? = null,
    warning: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    control: (@Composable () -> Unit)? = null,
) {
    Column(modifier.fillMaxWidth().padding(bottom = Vessel.metrics.s11)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Vessel.metrics.s8),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Weighted, so a long title wraps rather than shrinking the type or
            // clipping. The value is unweighted and capped instead: it measures
            // first, keeps the right edge of every row in one column, and cannot
            // grow far enough to squeeze the title out.
            Text(title, style = Vessel.type.body, modifier = Modifier.weight(1f))
            if (value != null) {
                Text(
                    value,
                    style = if (valueIsMachine) Vessel.type.mono else Vessel.type.body,
                    color = Vessel.colors.textPrimary,
                    textAlign = TextAlign.End,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = Vessel.metrics.valueMaxWidth),
                )
            }
            trailing?.invoke()
        }

        if (control != null) {
            Box(Modifier.fillMaxWidth().padding(top = Vessel.metrics.s6)) { control() }
        }
        if (help != null) {
            Text(
                help,
                style = Vessel.type.bodySmall,
                color = Vessel.colors.textMuted,
                modifier = Modifier.padding(top = Vessel.metrics.s3),
            )
        }
        if (note != null) {
            Text(
                note,
                style = Vessel.type.bodySmall,
                color = Vessel.colors.accent2,
                modifier = Modifier.padding(top = Vessel.metrics.s3),
            )
        }
        if (warning != null) {
            Text(
                warning,
                style = Vessel.type.bodySmall,
                color = Vessel.colors.warn,
                modifier = Modifier.padding(top = Vessel.metrics.s3),
            )
        }
    }
}

/**
 * The height of a field, a dropdown and a stepper button — they align in a column.
 *
 * `controlHeight` rather than a value of its own, so a text button placed beside
 * a dropdown is exactly as tall as it. It was 40 dp against a 40 dp button; both
 * came down together.
 */
private val fieldHeight: Dp
    @Composable @ReadOnlyComposable get() = Vessel.metrics.controlHeight

/**
 * The `enum` control: a full-width bordered box showing the current label, with
 * a chevron at the right edge and a menu behind it.
 *
 * This replaced a grid of chips. Two chips per row over four options is four
 * boxes, and once every row on the screen went flat those boxes were the only
 * thing left drawing panels — a control that reads as a set of buttons in a
 * screen that has no other buttons. One field with one value in it also matches
 * the text field and the component readout above and below it, which is what
 * makes the left and right edges of the whole screen line up.
 */
@Composable
fun VDropdownField(
    options: List<String>,
    labelFor: (String) -> String,
    selected: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Choose…",
    /**
     * Set the value in mono, for a choice whose options are a *tool's own words*
     * rather than English — `info`, `warn`, `fixme`, `5 MB`, `2 000`.
     *
     * Not cosmetic: those strings are what an issue thread names and what a
     * `/proc/<pid>/environ` dump will show, and setting them in the prose face
     * invites the reader to treat them as descriptions they may paraphrase. The
     * menu matches the field, or the value changes typeface when it is picked.
     */
    valueIsMachine: Boolean = false,
    enabled: Boolean = true,
) {
    var expanded by remember { mutableStateOf(false) }
    val shape = Vessel.metrics.shapeMd
    // Disabled is 45% opacity and never a different palette — Nocturne does not
    // recolour a disabled control, it dims it.
    val alpha = if (enabled) 1f else Vessel.colors.disabledAlpha
    val valueStyle = if (valueIsMachine) Vessel.type.mono else Vessel.type.body

    // The menu is a separate window and sizes itself to its widest item, so left
    // alone it is narrower than the field it drops out of and its right edge
    // lands nowhere — the one vertical line every other control on the screen
    // shares. Measuring the field is the only way to match it: DropdownMenu has
    // no "same width as anchor" option, and Material's ExposedDropdownMenuBox,
    // which does, brings a TextField and its whole look with it.
    val zeroWidth = Vessel.metrics.none
    var fieldWidth by remember { mutableStateOf(zeroWidth) }
    val density = LocalDensity.current

    Box(
        modifier
            .fillMaxWidth()
            .onSizeChanged { fieldWidth = with(density) { it.width.toDp() } },
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = fieldHeight)
                .background(Vessel.colors.surface, shape)
                .vRing(
                    (if (expanded) Vessel.colors.accent else Vessel.colors.divider)
                        .let { it.copy(alpha = it.alpha * alpha) },
                    shape,
                )
                .clickable(enabled = enabled) { expanded = true }
                .padding(horizontal = Vessel.metrics.s8, vertical = Vessel.metrics.s6),
            horizontalArrangement = Arrangement.spacedBy(Vessel.metrics.s8),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                selected?.let(labelFor) ?: placeholder,
                style = valueStyle,
                color = (if (selected == null) Vessel.colors.textMuted else Vessel.colors.textPrimary)
                    .let { it.copy(alpha = it.alpha * alpha) },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Icon(
                VIcons.CaretDown,
                contentDescription = null,
                tint = Vessel.colors.textMuted.let { it.copy(alpha = it.alpha * alpha) },
                modifier = Modifier.size(Vessel.metrics.iconMd),
            )
        }

        VMenuSurface(
            expanded = expanded,
            onDismiss = { expanded = false },
            width = fieldWidth,
        ) {
            options.forEach { option ->
                VMenuOption(
                    label = labelFor(option),
                    style = valueStyle,
                    selected = option == selected,
                    onClick = {
                        expanded = false
                        if (option != selected) onSelect(option)
                    },
                )
            }
        }
    }
}

/**
 * The one menu surface, shared by every field that drops one.
 *
 * **This replaced Material's `DropdownMenu`, which was the wrong system.** That
 * component brings a tonal-elevation container colour, its own corner radius, its
 * own vertical padding and a drop shadow — so a field with a 8 dp radius and a
 * hairline ring opened a rounder, greyer, shadowed panel that did not line up
 * with it. `VOverflowMenu` had already been built out of a `Popup` for exactly
 * that reason; this is the same chrome, so the two menus in the product are one
 * thing: `VElev.md`'s ring over `surface`, `shapeMd`, and the spacing scale.
 *
 * Anchored to the field rather than centred on the window: the popup is placed at
 * the anchor's top-start and dropped by the field's own height, and given the
 * field's measured [width], so its edges are the field's edges. That vertical
 * line is the one every control on a sheet shares.
 *
 * @param width the anchor's measured width. Zero until the first layout pass,
 *   which is harmless because the menu cannot be open before then.
 */
@Composable
fun VMenuSurface(
    expanded: Boolean,
    onDismiss: () -> Unit,
    width: Dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (!expanded) return
    val shape = Vessel.metrics.shapeMd
    val drop = with(LocalDensity.current) { Vessel.metrics.controlHeight.roundToPx() }
    Popup(
        alignment = Alignment.TopStart,
        offset = IntOffset(0, drop),
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Column(
            Modifier
                .width(width)
                // Bounded, then it scrolls. The resolution ladder is fourteen
                // rungs and an unbounded menu grows until it is the whole screen,
                // which buries the row it belongs to.
                .heightIn(max = Vessel.metrics.menuMaxHeight)
                .vElevation(VElev.md, shape)
                .background(Vessel.colors.surface, shape)
                .vRing(VElev.md.ring, shape)
                .verticalScroll(rememberScrollState())
                .padding(vertical = Vessel.metrics.s6),
            content = content,
        )
    }
}

/** One line of a [VMenuSurface]: the label, accent when it is the current value. */
@Composable
fun VMenuOption(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: TextStyle = Vessel.type.body,
) {
    Text(
        label,
        style = style,
        color = if (selected) Vessel.colors.accent else Vessel.colors.textPrimary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = Vessel.metrics.touchTarget)
            .padding(horizontal = Vessel.metrics.s11, vertical = Vessel.metrics.s8),
    )
}

/**
 * A field that is both a text input and a menu — type a value, or pick one.
 *
 * **The one control that would otherwise have been a modal.** A dialog whose only
 * job is "choose from this list, or type something else" is a second surface for
 * an answer that fits in the row it belongs to; as a combo the choice happens
 * where the result appears, and the same control covers the expert who knows the
 * name and the reader who is browsing.
 *
 * The chevron opens the menu; the text stays editable while it is open, so a
 * partial name filters nothing and the suggestions remain the whole list. That is
 * deliberate — the list is short enough to read, and a filtering combo that
 * silently empties itself on a typo is worse than one that does not filter.
 */
@Composable
fun VComboField(
    value: String,
    options: List<String>,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    enabled: Boolean = true,
    isError: Boolean = false,
) {
    var expanded by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = Vessel.metrics.shapeMd
    val alpha = if (enabled) 1f else Vessel.colors.disabledAlpha

    val zeroWidth = Vessel.metrics.none
    var fieldWidth by remember { mutableStateOf(zeroWidth) }
    val density = LocalDensity.current

    val ring = when {
        isError -> Vessel.colors.danger
        focused || expanded -> Vessel.colors.accent
        else -> Vessel.colors.divider
    }

    Box(
        modifier
            .fillMaxWidth()
            .onSizeChanged { fieldWidth = with(density) { it.width.toDp() } },
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = fieldHeight)
                .background(Vessel.colors.surface, shape)
                .vRing(ring.copy(alpha = ring.alpha * alpha), shape)
                .padding(horizontal = Vessel.metrics.s8, vertical = Vessel.metrics.s6),
            horizontalArrangement = Arrangement.spacedBy(Vessel.metrics.s6),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty() && placeholder != null) {
                    Text(
                        placeholder,
                        style = Vessel.type.mono,
                        color = Vessel.colors.textMuted.let { it.copy(alpha = it.alpha * alpha) },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    enabled = enabled,
                    singleLine = true,
                    // Mono, because what goes in here is a channel or a variable
                    // — a name the tool knows, not prose.
                    textStyle = Vessel.type.mono.copy(
                        color = Vessel.colors.textPrimary.let { it.copy(alpha = it.alpha * alpha) },
                    ),
                    cursorBrush = SolidColor(Vessel.colors.accent),
                    interactionSource = interaction,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Icon(
                VIcons.CaretDown,
                contentDescription = "Choose",
                tint = Vessel.colors.textMuted.let { it.copy(alpha = it.alpha * alpha) },
                modifier = Modifier
                    .size(Vessel.metrics.iconMd)
                    .clickable(enabled = enabled) { expanded = true },
            )
        }

        VMenuSurface(expanded = expanded, onDismiss = { expanded = false }, width = fieldWidth) {
            options.forEach { option ->
                VMenuOption(
                    label = option,
                    style = Vessel.type.mono,
                    selected = option == value,
                    onClick = {
                        expanded = false
                        if (option != value) onValueChange(option)
                    },
                )
            }
        }
    }
}

/**
 * The `bool` control: a pill switch on the right of the label line.
 *
 * Hand-built rather than Material's `Switch`, which is 52×32 with a shadowed
 * thumb and its own spring — a control a third taller than every field beside
 * it, and the one thing on the screen that would still be casting a shadow.
 * A `divider` hairline and one 150 ms slide, because DESIGN.md's motion rule is
 * confirmation and never decoration.
 */
@Composable
fun VToggle(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = Vessel.metrics.shapePill
    val motion = tween<Color>(Vessel.metrics.durationStandardMs)
    val track by animateColorAsState(
        if (checked) Vessel.colors.accent700 else Vessel.colors.neutral900,
        motion,
        label = "track",
    )
    val thumb by animateColorAsState(
        if (checked) Vessel.colors.accent200 else Vessel.colors.neutral600,
        motion,
        label = "thumb",
    )
    val thumbOffset by animateDpAsState(
        targetValue = if (checked) Vessel.metrics.toggleTravel else Vessel.metrics.none,
        animationSpec = tween(Vessel.metrics.durationStandardMs),
        label = "toggle",
    )

    Box(
        modifier
            .size(width = Vessel.metrics.toggleWidth, height = Vessel.metrics.toggleHeight)
            .background(track, shape)
            .vRing(Vessel.colors.divider, shape)
            .clickable { onCheckedChange(!checked) }
            .padding(Vessel.metrics.toggleInset),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(Modifier.offset(x = thumbOffset).size(Vessel.metrics.toggleThumb).background(thumb, shape))
    }
}

/**
 * The `int` control: minus, slider, plus.
 *
 * It was a bare stepper, on the reasoning that every integer param spans a
 * handful of documented steps and a slider would invite dragging past the one
 * that works. Both halves are here now for the same reason the reference app
 * pairs them: a slider alone cannot be aimed — spread a range across 500 px of
 * phone and each value is a few pixels wide, narrower than the part of a
 * fingertip the screen resolves — and a stepper alone makes a wide range a
 * hundred taps. The buttons make every value reachable; the track makes the
 * position readable at a glance. The number itself is on the row's label line,
 * where every other param's value is.
 */
@Composable
fun VStepper(
    value: Int,
    min: Int,
    max: Int,
    onChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    // An unbounded param would otherwise ask Material for a two-billion-wide
    // track. Nothing in the manifest declares one today; this is the guard that
    // keeps that from being a layout bug rather than a missing bound.
    val high = if (max > min) max else min + 1
    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Vessel.metrics.s8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        VStepperButton("−", "Decrease", enabled = value > min) { onChange(value - 1) }
        VSlider(
            value = value.toFloat(),
            onValueChange = { onChange(it.roundToInt().coerceIn(min, high)) },
            valueRange = min.toFloat()..high.toFloat(),
            // Material counts ticks strictly between the ends, so a grid of N
            // whole numbers is N-2. Capped because it allocates the list.
            steps = (high - min - 1).coerceIn(0, 200),
            modifier = Modifier.weight(1f),
        )
        VStepperButton("+", "Increase", enabled = value < max) { onChange(value + 1) }
    }
}

/** `VButton`'s outlined form, square and glyph-only. */
@Composable
private fun VStepperButton(
    glyph: String,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val shape = Vessel.metrics.shapeMd
    val alpha = if (enabled) 1f else Vessel.colors.disabledAlpha
    Box(
        Modifier
            .size(fieldHeight)
            .vRing(Vessel.colors.divider.copy(alpha = Vessel.colors.divider.alpha * alpha), shape)
            .clickable(enabled = enabled, onClickLabel = contentDescription, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(glyph, style = Vessel.type.cardTitle, color = Vessel.colors.accent.copy(alpha = alpha))
    }
}

/**
 * The range control, in Nocturne's palette.
 *
 * Material's `Slider` is kept for the drag mechanics and the accessibility
 * semantics, and given a track and thumb of our own: a 4 dp `neutral-800` rail
 * with an `accent` fill and a flat 16 dp `accent` thumb. The default is a purple
 * pill with a shadow, which is the wrong system.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun VSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    steps: Int = 0,
    enabled: Boolean = true,
) {
    val colors = Vessel.colors
    Slider(
        value = value.coerceIn(valueRange.start, valueRange.endInclusive),
        onValueChange = onValueChange,
        valueRange = valueRange,
        steps = steps,
        enabled = enabled,
        modifier = modifier.height(fieldHeight),
        colors = SliderDefaults.colors(
            thumbColor = colors.accent,
            activeTrackColor = colors.accent,
            inactiveTrackColor = colors.neutral800,
            activeTickColor = Color.Transparent,
            inactiveTickColor = Color.Transparent,
            disabledThumbColor = colors.neutral600,
            disabledActiveTrackColor = colors.neutral700,
            disabledInactiveTrackColor = colors.neutral900,
        ),
        thumb = {
            Box(
                Modifier
                    .size(Vessel.metrics.sliderThumb)
                    .background(
                        if (enabled) colors.accent else colors.neutral600,
                        Vessel.metrics.shapePill,
                    ),
            )
        },
        track = { state ->
            val span = state.valueRange.endInclusive - state.valueRange.start
            val fraction = if (span > 0f) (state.value - state.valueRange.start) / span else 0f
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(Vessel.metrics.sliderTrack)
                    .background(colors.neutral800, Vessel.metrics.shapePill),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(fraction.coerceIn(0f, 1f))
                        .height(Vessel.metrics.sliderTrack)
                        .background(
                            if (enabled) colors.accent else colors.neutral700,
                            Vessel.metrics.shapePill,
                        ),
                )
            }
        },
    )
}

/**
 * One line of the `multi` control: a box, a tick, and the option's label.
 *
 * [hint] and [help] are for the second caller this grew: a switch whose *variable
 * name* matters as much as its label. The hint sits on the right in mono, where
 * every other machine fact on a sheet sits; the help wraps underneath, indented
 * past the box so it reads as belonging to the row rather than to the next one.
 * Both default to absent, so the manifest's plain option rows are unchanged.
 */
@Composable
fun VCheckRow(
    label: String,
    checked: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    hint: String? = null,
    help: String? = null,
    enabled: Boolean = true,
) {
    val shape = Vessel.metrics.shapeSm
    val alpha = if (enabled) 1f else Vessel.colors.disabledAlpha
    fun Color.dim() = copy(alpha = this.alpha * alpha)

    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(enabled = enabled, onClick = onToggle)
                .heightIn(min = Vessel.metrics.touchTarget)
                .padding(vertical = Vessel.metrics.s3),
            horizontalArrangement = Arrangement.spacedBy(Vessel.metrics.s11),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(Vessel.metrics.iconMd)
                    .background(
                        if (checked) Vessel.colors.accentSoft.dim() else Color.Transparent,
                        shape,
                    )
                    .vRing(
                        if (checked) Vessel.colors.accent.dim() else Vessel.colors.divider.dim(),
                        shape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (checked) {
                    Text("✓", style = Vessel.type.monoSmall, color = Vessel.colors.accent.dim())
                }
            }
            Text(
                label,
                style = Vessel.type.body,
                color = (if (checked) Vessel.colors.textPrimary else Vessel.colors.textLabel).dim(),
                modifier = if (hint == null) Modifier else Modifier.weight(1f),
            )
            if (hint != null) {
                Text(
                    hint,
                    style = Vessel.type.monoSmall,
                    color = Vessel.colors.textMuted.dim(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (help != null) {
            Text(
                help,
                style = Vessel.type.bodySmall,
                color = Vessel.colors.textMuted.dim(),
                modifier = Modifier.padding(
                    // Past the box and its gap, so the sentence hangs under the
                    // label rather than under the tick.
                    start = Vessel.metrics.iconMd + Vessel.metrics.s11,
                    bottom = Vessel.metrics.s3,
                ),
            )
        }
    }
}

/**
 * A single-line text input.
 *
 * `BasicTextField` in a box we draw, because Material's `TextField` brings a
 * container tone, an indicator line and a label animation that are all from a
 * different design system. Same ground, same ring and same height as
 * [VDropdownField], so a name field and an enum field line up down the screen.
 * The ring goes accent while focused; nothing else is coloured but the cursor.
 */
@Composable
fun VTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    /**
     * A value that is shown and may not be changed.
     *
     * Dimmed rather than recoloured, and a *field* rather than a bare line of
     * text, because the one place this is used — the built-in profile's name —
     * has a sentence under it explaining why the name cannot move. A field that
     * turned into a label would leave that sentence talking about nothing.
     */
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = Vessel.metrics.shapeMd
    val alpha = if (enabled) 1f else Vessel.colors.disabledAlpha

    Box(
        modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = fieldHeight)
            .background(Vessel.colors.surface, shape)
            .vRing(
                (if (focused && enabled) Vessel.colors.accent else Vessel.colors.divider)
                    .let { it.copy(alpha = it.alpha * alpha) },
                shape,
            )
            .padding(horizontal = Vessel.metrics.s8, vertical = Vessel.metrics.s6),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (value.isEmpty() && placeholder != null) {
            Text(placeholder, style = Vessel.type.body, color = Vessel.colors.textMuted)
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            singleLine = true,
            textStyle = Vessel.type.body.copy(
                color = Vessel.colors.textPrimary.copy(
                    alpha = Vessel.colors.textPrimary.alpha * alpha,
                ),
            ),
            cursorBrush = SolidColor(Vessel.colors.accent),
            interactionSource = interaction,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * The `component` control.
 *
 * There is nothing to pick. Vessel ships one current build of each component
 * compiled for this device, so a selector is a readout of what `@latest`
 * resolved to — and when it resolved to nothing, the row says that rather than
 * showing an empty field the user would try to fill. It takes the field's box
 * anyway, without the chevron, so the column of controls keeps one left and one
 * right edge all the way down the screen.
 */
@Composable
fun VComponentReadout(
    selector: String,
    resolvedId: String?,
    modifier: Modifier = Modifier,
) {
    val shape = Vessel.metrics.shapeMd
    Row(
        modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = fieldHeight)
            .background(Vessel.colors.surface, shape)
            .vRing(Vessel.colors.divider, shape)
            .padding(horizontal = Vessel.metrics.s8, vertical = Vessel.metrics.s6),
        horizontalArrangement = Arrangement.spacedBy(Vessel.metrics.s8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            resolvedId ?: "nothing installed",
            style = Vessel.type.mono,
            color = if (resolvedId != null) Vessel.colors.textLabel else Vessel.colors.textMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        VTag(selector, tone = VTagTone.Outline)
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF161826, widthDp = 392)
@Composable
private fun VParamRowPreview() {
    VesselTheme {
        Column(Modifier.padding(Vessel.metrics.s11)) {
            VSectionHeader("Display")
            VParamRow(
                title = "Resolution",
                help = "Lower resolutions gain a lot of performance on this phone; it has a " +
                    "smaller GPU cache than the full-size chip.",
                value = "1280x720",
                control = {
                    VDropdownField(
                        options = listOf("1280x720", "1920x1080", "2780x1264"),
                        labelFor = { it },
                        selected = "1280x720",
                        onSelect = {},
                    )
                },
            )
            VParamRow(
                title = "Frame rate limit",
                help = "Capping frame rate keeps the phone cool, which keeps performance steady " +
                    "over a long session.",
                value = "60",
                control = {
                    VDropdownField(
                        options = listOf("24", "30", "60", "90", "120", "165"),
                        labelFor = { it },
                        selected = "60",
                        onSelect = {},
                    )
                },
            )

            VSectionHeader("Graphics")
            VParamRow(
                title = "GPU driver",
                help = "Turnip is the open driver this app builds for your Adreno 829.",
                value = "@latest",
                valueIsMachine = true,
                note = "Nothing installed for this component yet.",
                control = { VComponentReadout("@latest", null) },
            )
            VParamRow(
                title = "Worker threads",
                help = "How many cores the translator may compile on at once.",
                value = "4",
                control = { VStepper(value = 4, min = 1, max = 8, onChange = {}) },
            )

            VSectionHeader("Compatibility")
            VParamRow(
                title = "Strict memory ordering",
                help = "Keep this on: turning it off is faster but breaks most multi-threaded " +
                    "programs.",
                warning = "Most programs will crash or corrupt data with this off.",
                trailing = { VToggle(checked = false, onCheckedChange = {}) },
            )
            VParamRow(
                title = "Cheap ordering barriers",
                help = "Uses a lighter method to enforce memory ordering; leave on unless a " +
                    "program misbehaves.",
                trailing = { VToggle(checked = true, onCheckedChange = {}) },
            )
        }
    }
}
