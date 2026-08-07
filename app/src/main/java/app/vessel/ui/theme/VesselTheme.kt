package app.vessel.ui.theme

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * The palette from `docs/DESIGN.md`.
 *
 * Dark-only, and deliberately not Material dynamic colour: architecture is
 * carried by colour here, so `archX64` meaning cyan on one phone and something
 * wallpaper-derived on another would destroy the one signal the product leans
 * on hardest.
 */
@Immutable
data class VColors(
    val bg: Color = Color(0xFF0A0C0F),
    val surface: Color = Color(0xFF12161B),
    val surfaceRaised: Color = Color(0xFF1A1F26),
    val surfaceSunken: Color = Color(0xFF070A0D),
    val border: Color = Color(0xFF2A323C),
    val borderStrong: Color = Color(0xFF3A444F),
    val textPrimary: Color = Color(0xFFE6EDF3),
    val textSecondary: Color = Color(0xFF9AA7B4),
    val textTertiary: Color = Color(0xFF6B7885),

    val accent: Color = Color(0xFF4CC9F0),
    val accentPressed: Color = Color(0xFF35A8CC),
    val accentSoft: Color = Color(0x244CC9F0),

    /** ARM64 / ARM64EC — runs natively, no translation. Green reads as free. */
    val archNative: Color = Color(0xFF3FD98B),
    /** x86-64 — translated by FEX. */
    val archX64: Color = Color(0xFF4CC9F0),
    /** x86-32 — the WoW64 path. */
    val archX86: Color = Color(0xFFB98CFF),
    /** Not yet inspected, or the PE header would not read. */
    val archUnknown: Color = Color(0xFF6B7885),

    val ok: Color = Color(0xFF3FD98B),
    val warn: Color = Color(0xFFF5B14C),
    val danger: Color = Color(0xFFFF6B6B),
    val info: Color = Color(0xFF4CC9F0),
)

/** Radius, spacing and motion. Border is one hairline everywhere; there are no shadows. */
@Immutable
data class VMetrics(
    val radiusSm: Dp = 8.dp,
    val radiusMd: Dp = 12.dp,
    val radiusLg: Dp = 16.dp,
    val hairline: Dp = 1.dp,

    val s4: Dp = 4.dp,
    val s8: Dp = 8.dp,
    val s12: Dp = 12.dp,
    val s16: Dp = 16.dp,
    val s20: Dp = 20.dp,
    val s24: Dp = 24.dp,
    val s32: Dp = 32.dp,

    val screenGutter: Dp = 16.dp,
    val touchTarget: Dp = 44.dp,

    /** Motion is confirmation, never decoration: one duration, no springs. */
    val durationStandardMs: Int = 150,
    val durationSheetMs: Int = 250,
) {
    val shapeSm: Shape = RoundedCornerShape(radiusSm)
    val shapeMd: Shape = RoundedCornerShape(radiusMd)
    val shapeLg: Shape = RoundedCornerShape(radiusLg)
    val shapePill: Shape = RoundedCornerShape(percent = 50)
}

// TODO: DESIGN.md specifies Inter and JetBrains Mono, bundled as variable fonts
//  so the product is identical on every device. Until the .ttf files are in
//  res/font these are the system families, which keeps the scale and the
//  sans/mono split honest but not the letterforms.
private val VSans = FontFamily.SansSerif
private val VMono = FontFamily.Monospace

/** Tabular figures, so a live metric's digits do not shift as the value changes. */
private const val TABULAR_FIGURES = "tnum"

/** The type scale from `docs/DESIGN.md`. Every machine fact is one of the mono styles. */
@Immutable
data class VType(
    val display: TextStyle = TextStyle(
        fontFamily = VSans,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        lineHeight = 34.sp,
        letterSpacing = (-0.02).em,
    ),
    val title: TextStyle = TextStyle(
        fontFamily = VSans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
        letterSpacing = (-0.01).em,
    ),
    val subtitle: TextStyle = TextStyle(
        fontFamily = VSans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ),
    val body: TextStyle = TextStyle(
        fontFamily = VSans,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    val label: TextStyle = TextStyle(
        fontFamily = VSans,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
    val mono: TextStyle = TextStyle(
        fontFamily = VMono,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        fontFeatureSettings = TABULAR_FIGURES,
    ),
    val monoSmall: TextStyle = TextStyle(
        fontFamily = VMono,
        fontWeight = FontWeight.Normal,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        fontFeatureSettings = TABULAR_FIGURES,
    ),
    /** Live numbers only — FPS, frame time, memory. */
    val metric: TextStyle = TextStyle(
        fontFamily = VMono,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 26.sp,
        fontFeatureSettings = TABULAR_FIGURES,
    ),
)

val LocalVColors = staticCompositionLocalOf { VColors() }
val LocalVMetrics = staticCompositionLocalOf { VMetrics() }
val LocalVType = staticCompositionLocalOf { VType() }

/** The tokens, at every call site: `Vessel.colors`, `Vessel.metrics`, `Vessel.type`. */
object Vessel {
    val colors: VColors
        @Composable @ReadOnlyComposable get() = LocalVColors.current

    val metrics: VMetrics
        @Composable @ReadOnlyComposable get() = LocalVMetrics.current

    val type: VType
        @Composable @ReadOnlyComposable get() = LocalVType.current
}

@Composable
fun VesselTheme(
    colors: VColors = VColors(),
    metrics: VMetrics = VMetrics(),
    type: VType = VType(),
    content: @Composable () -> Unit,
) {
    val scheme = remember(colors) { colors.toMaterialScheme() }
    MaterialTheme(colorScheme = scheme) {
        CompositionLocalProvider(
            LocalVColors provides colors,
            LocalVMetrics provides metrics,
            LocalVType provides type,
            LocalContentColor provides colors.textPrimary,
            LocalTextStyle provides type.body.copy(color = colors.textPrimary),
            LocalTextSelectionColors provides TextSelectionColors(
                handleColor = colors.accent,
                backgroundColor = colors.accent.copy(alpha = 0.30f),
            ),
            LocalIndication provides ripple(color = colors.accent),
            content = content,
        )
    }
}

/**
 * Material's scheme, filled from the same tokens.
 *
 * Nothing in this app is meant to reach for `MaterialTheme.colorScheme`
 * directly, but Material components that draw themselves — text fields,
 * sliders, the ripple — read it whether we do or not.
 */
private fun VColors.toMaterialScheme() = darkColorScheme(
    primary = accent,
    onPrimary = bg,
    primaryContainer = accentSoft,
    onPrimaryContainer = accent,
    secondary = archX86,
    onSecondary = bg,
    background = bg,
    onBackground = textPrimary,
    surface = surface,
    onSurface = textPrimary,
    surfaceVariant = surfaceRaised,
    onSurfaceVariant = textSecondary,
    surfaceContainerLowest = surfaceSunken,
    surfaceContainerLow = bg,
    surfaceContainer = surface,
    surfaceContainerHigh = surfaceRaised,
    surfaceContainerHighest = surfaceRaised,
    outline = border,
    outlineVariant = borderStrong,
    error = danger,
    onError = bg,
    scrim = Color.Black,
)

/** A card: `surface`, one hairline of `border`, `lg` radius, no shadow. */
@Composable
fun Modifier.vCard(
    fill: Color = Vessel.colors.surface,
    stroke: Color = Vessel.colors.border,
    shape: Shape = Vessel.metrics.shapeLg,
): Modifier = background(fill, shape).border(Vessel.metrics.hairline, stroke, shape)

/** A hairline along the top edge — bottom bars and overlays. */
fun Modifier.vRuleAbove(color: Color): Modifier = drawBehind {
    drawLine(color, Offset(0f, 0.5f), Offset(size.width, 0.5f), strokeWidth = 1f)
}

/** The same rule under a row. */
fun Modifier.vRuleBelow(color: Color): Modifier = drawBehind {
    val y = size.height - 0.5f
    drawLine(color, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
}
