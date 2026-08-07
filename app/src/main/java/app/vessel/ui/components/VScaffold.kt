package app.vessel.ui.components

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import app.vessel.ui.theme.Vessel
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
                top = Vessel.metrics.s12,
                bottom = Vessel.metrics.s12,
            ),
        horizontalArrangement = Arrangement.spacedBy(Vessel.metrics.s8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = Vessel.type.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) {
                Text(subtitle, style = Vessel.type.monoSmall, color = Vessel.colors.textTertiary)
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
            .vRuleBelow(Vessel.colors.border)
            .padding(
                start = Vessel.metrics.s8,
                end = Vessel.metrics.screenGutter,
                top = Vessel.metrics.s12,
                bottom = Vessel.metrics.s12,
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
                    color = Vessel.colors.textTertiary,
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

/** Four roots and no more — see the design principle, not an oversight. */
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
            .vRuleAbove(Vessel.colors.border)
            .padding(
                top = Vessel.metrics.s8,
                bottom = Vessel.metrics.s12 +
                    WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
            ),
    ) {
        destinations.forEach { destination ->
            val selected = currentRoute == destination.route
            val tint = if (selected) Vessel.colors.accent else Vessel.colors.textTertiary
            Column(
                Modifier
                    .weight(1f)
                    .clickable { onSelect(destination) }
                    .padding(vertical = Vessel.metrics.s4),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Icon(destination.icon, null, Modifier.size(20.dp), tint = tint)
                Text(destination.label, style = Vessel.type.label, color = tint)
            }
        }
    }
}

enum class VButtonStyle { Primary, Secondary, Ghost }

/**
 * The one button.
 *
 * Not Material's, because rule 3 is flat and precise: a fill, one hairline, no
 * elevation and no shadow at any state. Pressed is a colour change and nothing
 * else.
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
    val shape = Vessel.metrics.shapeMd

    val fill = when {
        !enabled -> Vessel.colors.surfaceRaised
        style == VButtonStyle.Primary && pressed -> Vessel.colors.accentPressed
        style == VButtonStyle.Primary -> Vessel.colors.accent
        style == VButtonStyle.Secondary -> Vessel.colors.surfaceRaised
        else -> Color.Transparent
    }
    val stroke = when {
        style == VButtonStyle.Primary -> Color.Transparent
        pressed -> Vessel.colors.borderStrong
        else -> Vessel.colors.border
    }
    val content = when {
        !enabled -> Vessel.colors.textTertiary
        style == VButtonStyle.Primary -> Vessel.colors.bg
        style == VButtonStyle.Ghost -> Vessel.colors.textSecondary
        else -> Vessel.colors.textPrimary
    }

    Row(
        modifier
            .defaultMinSize(minHeight = 40.dp)
            .background(fill, shape)
            .border(Vessel.metrics.hairline, stroke, shape)
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                enabled = enabled,
                onClick = onClick,
            )
            .padding(horizontal = Vessel.metrics.s16, vertical = Vessel.metrics.s8),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) Icon(icon, null, Modifier.size(16.dp), tint = content)
        Text(label, style = Vessel.type.label, color = content, maxLines = 1)
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

/** The mono rule that names a group on every screen. */
@Composable
fun VSectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        style = Vessel.type.monoSmall.copy(letterSpacing = 0.1.em),
        color = Vessel.colors.textTertiary,
        modifier = modifier.padding(top = Vessel.metrics.s16, bottom = Vessel.metrics.s8),
    )
}
