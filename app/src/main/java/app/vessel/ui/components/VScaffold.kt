package app.vessel.ui.components

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import app.vessel.ui.theme.VElev
import app.vessel.ui.theme.Vessel
import app.vessel.ui.theme.vContentColumn
import app.vessel.ui.theme.vElevation
import app.vessel.ui.theme.vRing
import app.vessel.ui.theme.vRuleAbove
import app.vessel.ui.theme.vRuleBelow

/**
 * The frame every screen sits in.
 *
 * The three slots share one centred column: `horizontalAlignment` here plus
 * [vContentColumn] on the toolbar and the content is what keeps a landscape
 * window from stretching a list row across 927 dp. The bars are still handed the
 * full width so their ground and their hairline reach both edges — only what is
 * *in* them is capped, which is why each toolbar applies the modifier itself
 * rather than being wrapped in it here.
 */
@Composable
fun VScaffold(
    modifier: Modifier = Modifier,
    toolbar: (@Composable () -> Unit)? = null,
    bottomBar: (@Composable () -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(horizontal = Vessel.metrics.screenGutter),
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxSize()
            .background(Vessel.colors.bg)
            .padding(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding())
            // The activity is edge-to-edge, so the window is not resized when
            // the keyboard opens and `adjustResize` counts for nothing.
            .imePadding(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        toolbar?.invoke()
        Column(
            Modifier.weight(1f).vContentColumn().padding(contentPadding),
            content = content,
        )
        bottomBar?.invoke()
    }
}

/** A root destination's header: title flush left, actions right. */
@Composable
fun VRootToolbar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    Row(
        modifier
            .vContentColumn()
            .padding(
                start = Vessel.metrics.screenGutter,
                end = Vessel.metrics.s6,
                top = Vessel.metrics.s8,
                bottom = Vessel.metrics.s8,
            ),
        horizontalArrangement = Arrangement.spacedBy(Vessel.metrics.s8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = Vessel.type.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) {
                Text(subtitle, style = Vessel.type.monoSmall, color = Vessel.colors.textMuted)
            }
        }
        trailing?.invoke(this)
    }
}

/** A pushed screen's toolbar: back, title, optional mono subtitle, optional action. */
@Composable
fun VPushToolbar(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    Row(
        modifier
            .vContentColumn()
            .vRuleBelow(Vessel.colors.divider)
            .padding(
                // No start padding, and it is not an oversight: the back glyph
                // is centred in a 44 dp touch box, so 13 dp of that box is
                // already inside the button. Adding the 11 dp gutter on top puts
                // the arrow 13 dp right of the content column it heads.
                end = Vessel.metrics.s6,
                top = Vessel.metrics.s6,
                bottom = Vessel.metrics.s6,
            ),
        horizontalArrangement = Arrangement.spacedBy(Vessel.metrics.s3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        VIconButton(VIcons.ArrowLeft, "Back", onBack)
        Column(Modifier.weight(1f).padding(start = Vessel.metrics.s3)) {
            Text(title, style = Vessel.type.subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
 * A bar along the bottom of a pushed screen — Files' import/export/add row.
 *
 * **There is no bottom navigation, and this is not it.** Vessel had two roots,
 * Containers and Apps, and Apps stopped existing when a program became something
 * listed inside the container that owns it: a bar with one destination on it is a
 * bar advertising that the app has somewhere else to be when it does not. The
 * bottom edge is now left clear on every root, because on a running session that
 * edge belongs to the taskbar's reveal gesture and a nav bar there would fight it.
 *
 * The ground and the hairline run edge to edge while the actions sit inside the
 * same capped column as the content above them, so the bar reads as structure and
 * its contents line up with the list.
 */
@Composable
fun VBottomBar(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    Box(
        modifier
            .fillMaxWidth()
            // Fill first, rule second: `drawBehind` paints before it delegates,
            // so a rule declared above the background is painted over by it.
            .background(Vessel.colors.bg)
            .vRuleAbove(Vessel.colors.divider)
            .padding(
                top = Vessel.metrics.s8,
                bottom = Vessel.metrics.s11 +
                    WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier.vContentColumn().padding(horizontal = Vessel.metrics.screenGutter),
            horizontalArrangement = Arrangement.spacedBy(Vessel.metrics.s8),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}

/**
 * Danger is the same outlined form in the `danger` token rather than a fourth
 * shape. Nocturne has no filled destructive slab either, and a delete that
 * looked heavier than a launch would be the loudest thing in the product.
 */
enum class VButtonStyle { Primary, Secondary, Ghost, Danger }

/**
 * The one button.
 *
 * **Buttons in Nocturne are outlined, not filled.** A primary button is accent
 * text with a 1 dp accent border on a transparent ground, tinting to 12% accent
 * on hover and 22% on press. A solid accent slab is not a form this system has;
 * filling Primary reads as a Material FAB dropped onto a Nocturne screen.
 *
 * `.btn-secondary` is the same geometry with a `divider` border and text
 * colour; `.btn-ghost` drops the border entirely. Disabled is 45% opacity, not
 * a different palette — Nocturne never recolours a disabled control.
 */
@Composable
fun VButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: VButtonStyle = VButtonStyle.Secondary,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val hovered by interaction.collectIsHoveredAsState()
    val shape = Vessel.metrics.shapeMd
    val colors = Vessel.colors

    val fill = buttonFill(style, enabled, pressed, hovered)
    val stroke = buttonStroke(style)
    val content = buttonContent(style)
    val alpha = if (enabled) 1f else colors.disabledAlpha

    Row(
        modifier
            .defaultMinSize(minHeight = Vessel.metrics.controlHeight)
            .background(fill, shape)
            .border(Vessel.metrics.hairline, stroke.copy(alpha = stroke.alpha * alpha), shape)
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                enabled = enabled,
                onClick = onClick,
            )
            .padding(
                // `.btn` padding: `var(--space-2) calc(var(--space-3) * 1.2)`,
                // and `.btn-ghost` pulls its inline padding back to `--space-1`.
                horizontal = if (style == VButtonStyle.Ghost) Vessel.metrics.s8 else Vessel.metrics.s11,
                vertical = Vessel.metrics.s6,
            ),
        horizontalArrangement = Arrangement.spacedBy(
            Vessel.metrics.s6,
            Alignment.CenterHorizontally,
        ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val tinted = content.copy(alpha = alpha)
        if (icon != null) Icon(icon, null, Modifier.size(Vessel.metrics.iconSm), tint = tinted)
        Text(label, style = Vessel.type.control, color = tinted, maxLines = 1)
    }
}

// The three colour rules a button follows, shared by the text form and the icon
// form so the two cannot drift. Transparent at rest, always: the tint is the
// only thing a state changes, and a solid accent slab is not a Nocturne shape.

@Composable
private fun buttonFill(
    style: VButtonStyle,
    enabled: Boolean,
    pressed: Boolean,
    hovered: Boolean,
): Color {
    val colors = Vessel.colors
    return when {
        !enabled -> Color.Transparent
        style == VButtonStyle.Ghost && pressed -> colors.accentGhostPressed
        style == VButtonStyle.Ghost && hovered -> colors.accentGhostHover
        style == VButtonStyle.Primary && pressed -> colors.accentPressed
        style == VButtonStyle.Primary && hovered -> colors.accentHover
        style == VButtonStyle.Secondary && pressed -> colors.neutralPressed
        style == VButtonStyle.Secondary && hovered -> colors.neutralHover
        style == VButtonStyle.Danger && pressed -> colors.danger.copy(alpha = 0.22f)
        style == VButtonStyle.Danger && hovered -> colors.danger.copy(alpha = 0.12f)
        else -> Color.Transparent
    }
}

@Composable
private fun buttonStroke(style: VButtonStyle): Color = when (style) {
    VButtonStyle.Primary -> Vessel.colors.accent
    VButtonStyle.Secondary -> Vessel.colors.divider
    VButtonStyle.Ghost -> Color.Transparent
    VButtonStyle.Danger -> Vessel.colors.danger
}

@Composable
private fun buttonContent(style: VButtonStyle): Color = when (style) {
    VButtonStyle.Primary, VButtonStyle.Ghost -> Vessel.colors.accent
    VButtonStyle.Secondary -> Vessel.colors.textPrimary
    VButtonStyle.Danger -> Vessel.colors.danger
}

/**
 * [VButton]'s outlined form with a glyph where the label would be.
 *
 * For an action whose meaning survives without a word — Launch on a card that
 * has already said what it is, Save and Delete in a toolbar. It keeps the ring,
 * the tint states and the disabled treatment of the text button, so an icon
 * action and a text action beside each other are the same control.
 *
 * [contentDescription] is required rather than optional: an icon with no label
 * is a mystery glyph to a screen reader and to the next person reading the call
 * site, and it costs one string to not be.
 *
 * A destructive *confirmation* never takes this form. Cancel and Delete inside
 * [VConfirmSheet] stay words, because that is the one place where reading the
 * button is the whole point of the button.
 */
@Composable
fun VIconAction(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: VButtonStyle = VButtonStyle.Secondary,
    enabled: Boolean = true,
    /**
     * The box, which is also the touch target — so it never goes below
     * `touchTarget`. The session rail passes the floor; a card passes the
     * smaller [VMetrics.iconButton], which is the size that lets a container
     * tile be a list row rather than a slab.
     */
    size: Dp = Vessel.metrics.iconButton,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val hovered by interaction.collectIsHoveredAsState()
    val shape = Vessel.metrics.shapeMd
    val colors = Vessel.colors
    val alpha = if (enabled) 1f else colors.disabledAlpha

    Box(
        modifier
            .size(size)
            .background(buttonFill(style, enabled, pressed, hovered), shape)
            .border(
                Vessel.metrics.hairline,
                buttonStroke(style).let { it.copy(alpha = it.alpha * alpha) },
                shape,
            )
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                enabled = enabled,
                onClickLabel = contentDescription,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription,
            Modifier.size(Vessel.metrics.iconMd),
            tint = buttonContent(style).copy(alpha = alpha),
        )
    }
}

/**
 * A bare icon action — no ring, no ground.
 *
 * The glyph is [VMetrics.iconMd] and the box stays at the touch floor around it.
 * Shrinking the *target* with the glyph is the usual way a compact toolbar
 * becomes unusable, and it is not a trade this pass makes anywhere.
 */
@Composable
fun VIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = Vessel.colors.textPrimary,
) {
    Box(
        modifier.size(Vessel.metrics.touchTarget).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription, Modifier.size(Vessel.metrics.iconMd), tint = tint)
    }
}

/**
 * One line of a [VOverflowMenu].
 *
 * [help] is not decoration. Every destination behind this menu is a technical
 * screen, and the sentence is what tells someone who opened the menu by accident
 * that they do not need any of it.
 */
@Immutable
data class VMenuItem(
    val label: String,
    val help: String,
    val onSelect: () -> Unit,
)

/**
 * The way to the screens a user never needs and a maintainer occasionally does.
 *
 * Vessel has two roots, and the technical screens — Components, GPU drivers —
 * hang off this rather than off a Settings tab. They are still ordinary pushed
 * routes; only the door moved. A tab would spend a third of the bottom bar on a
 * menu, and a bottom bar that is one-third menu is telling the user that
 * configuring the app is one of the three things it is for.
 *
 * Drawn with a [Popup] rather than Material's `DropdownMenu`: that arrives with
 * its own container tone, its own corner radius and a drop shadow, and this
 * system's elevation is a hairline ring. `VElev.md` is the raised form —
 * `neutral-700` ring over an ambient darkening — which is what a menu floating
 * above a list should be.
 */
@Composable
fun VOverflowMenu(
    items: List<VMenuItem>,
    modifier: Modifier = Modifier,
    contentDescription: String = "More",
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        VIconButton(VIcons.DotsThreeVertical, contentDescription, onClick = { open = true })
        if (open) {
            // Anchored to the button's own bounds: aligned to its trailing edge
            // and dropped by its height, so the menu hangs directly below the
            // glyph rather than being centred on the window.
            val drop = with(LocalDensity.current) { Vessel.metrics.touchTarget.roundToPx() }
            Popup(
                alignment = Alignment.TopEnd,
                offset = IntOffset(0, drop),
                onDismissRequest = { open = false },
                properties = PopupProperties(focusable = true),
            ) {
                val shape = Vessel.metrics.shapeMd
                Column(
                    Modifier
                        .width(Vessel.metrics.menuWidth)
                        .vElevation(VElev.md, shape)
                        .background(Vessel.colors.surface, shape)
                        .vRing(VElev.md.ring, shape)
                        .padding(vertical = Vessel.metrics.s6),
                ) {
                    items.forEach { item ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    // Closed before the navigation, so the popup
                                    // window is gone by the time the destination
                                    // it opened is composed.
                                    open = false
                                    item.onSelect()
                                }
                                .padding(
                                    horizontal = Vessel.metrics.s11,
                                    vertical = Vessel.metrics.s8,
                                ),
                            verticalArrangement = Arrangement.spacedBy(Vessel.metrics.s3),
                        ) {
                            Text(item.label, style = Vessel.type.body)
                            Text(
                                item.help,
                                style = Vessel.type.bodySmall,
                                color = Vessel.colors.textMuted,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * The kicker that names a group on every screen.
 *
 * `h6` in Nocturne: 11 sp, `0.08em` tracking, uppercase. The uppercasing has to
 * happen here because Compose has no `text-transform`, which is also why
 * `Vessel.type.overline` carries the tracking but not the case.
 */
@Composable
fun VSectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        style = Vessel.type.overline,
        color = Vessel.colors.textMuted,
        modifier = modifier.padding(top = Vessel.metrics.s11, bottom = Vessel.metrics.s6),
    )
}
