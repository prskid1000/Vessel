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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
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
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import app.vessel.ui.theme.VElev
import app.vessel.ui.theme.Vessel
import app.vessel.ui.theme.vElevation
import app.vessel.ui.theme.vRing
import app.vessel.ui.theme.vRuleAbove
import app.vessel.ui.theme.vRuleBelow

/** The frame every screen sits in. */
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
    ) {
        toolbar?.invoke()
        Column(
            Modifier.weight(1f).fillMaxWidth().padding(contentPadding),
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
            .fillMaxWidth()
            .padding(
                start = Vessel.metrics.screenGutter,
                end = Vessel.metrics.screenGutter,
                top = Vessel.metrics.s11,
                bottom = Vessel.metrics.s11,
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
            .fillMaxWidth()
            .vRuleBelow(Vessel.colors.divider)
            .padding(
                start = Vessel.metrics.s8,
                end = Vessel.metrics.screenGutter,
                top = Vessel.metrics.s11,
                bottom = Vessel.metrics.s11,
            ),
        horizontalArrangement = Arrangement.spacedBy(Vessel.metrics.s8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        VIconButton(Icons.AutoMirrored.Filled.ArrowBack, "Back", onBack)
        Column(Modifier.weight(1f)) {
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

/** One destination in the bottom bar. */
@Immutable
data class VNavDestination(
    val label: String,
    val icon: ImageVector,
    val route: String,
)

/** Two roots and no more — see the design principle, not an oversight. */
@Composable
fun VBottomNav(
    destinations: List<VNavDestination>,
    currentRoute: String?,
    modifier: Modifier = Modifier,
    onSelect: (VNavDestination) -> Unit,
) {
    Row(
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
    ) {
        destinations.forEach { destination ->
            val selected = currentRoute == destination.route
            val tint = if (selected) Vessel.colors.accent else Vessel.colors.textMuted
            Column(
                Modifier
                    .weight(1f)
                    .clickable { onSelect(destination) }
                    .padding(vertical = Vessel.metrics.s3),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Icon(destination.icon, null, Modifier.size(20.dp), tint = tint)
                Text(destination.label, style = Vessel.type.label, color = tint)
            }
        }
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
 * on hover and 22% on press. A solid accent slab is not a form this system has,
 * and it used to be one here: the previous version filled Primary with the flat
 * accent and set the label to `bg`, which read as a Material FAB dropped onto a
 * Nocturne screen and made the Launch button the loudest thing in the product.
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
            .defaultMinSize(minHeight = 40.dp)
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
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val tinted = content.copy(alpha = alpha)
        if (icon != null) Icon(icon, null, Modifier.size(16.dp), tint = tinted)
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
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val hovered by interaction.collectIsHoveredAsState()
    val shape = Vessel.metrics.shapeMd
    val colors = Vessel.colors
    val alpha = if (enabled) 1f else colors.disabledAlpha

    Box(
        modifier
            .size(Vessel.metrics.touchTarget)
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
            Modifier.size(20.dp),
            tint = buttonContent(style).copy(alpha = alpha),
        )
    }
}

/** A square icon action, at the touch-target floor. */
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
        Icon(icon, contentDescription, Modifier.size(20.dp), tint = tint)
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
        VIconButton(Icons.Filled.MoreVert, contentDescription, onClick = { open = true })
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
                        .width(MENU_WIDTH)
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

/** Wide enough for one line of `bodySmall` help, narrow enough to read as a menu. */
private val MENU_WIDTH = 260.dp

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
        modifier = modifier.padding(top = Vessel.metrics.s17, bottom = Vessel.metrics.s8),
    )
}
