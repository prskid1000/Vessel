package app.vessel.ui.theme

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
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
 * Nocturne, as Vessel expresses it.
 *
 * Every value is transcribed from `_ds/nocturne-<id>/styles.css` in the
 * On-Device AI project by way of `docs/DESIGN.md`, which is the source of truth:
 * retune there, mirror here.
 *
 * Dark-only, and deliberately not Material dynamic colour — architecture is
 * carried by colour here, so `archX64` meaning blue on one phone and something
 * wallpaper-derived on another would destroy the signal the product leans on
 * hardest.
 *
 * Derived tokens (`divider`, `textMuted`, the interaction tints) are default
 * expressions naming an earlier parameter, so they cannot drift from the role
 * they are derived from.
 */
@Immutable
data class VColors(
    // — core roles —
    /** `--color-bg`. A deep indigo, deliberately not black. */
    val bg: Color = Color(0xFF161826),
    /** `--color-surface`: cards, sheets, bars. */
    val surface: Color = Color(0xFF232532),
    /** `--color-text`. */
    val text: Color = Color(0xFFE9E9ED),
    /** `--color-accent`. */
    val accent: Color = Color(0xFF9184D9),
    /** `--color-accent-2`: secondary accent, second series in charts. */
    val accent2: Color = Color(0xFFA7A1DB),

    // — neutral ramp —
    val neutral100: Color = Color(0xFFF3F5FE),
    val neutral200: Color = Color(0xFFE4E7F5),
    val neutral300: Color = Color(0xFFCFD3E5),
    val neutral400: Color = Color(0xFFB2B6CA),
    val neutral500: Color = Color(0xFF9397AB),
    val neutral600: Color = Color(0xFF75798C),
    val neutral700: Color = Color(0xFF595D6C),
    val neutral800: Color = Color(0xFF3F424D),
    val neutral900: Color = Color(0xFF292B31),

    // — accent ramp —
    val accent100: Color = Color(0xFFF5F4FF),
    val accent200: Color = Color(0xFFE7E5FE),
    val accent300: Color = Color(0xFFD2CEFD),
    val accent400: Color = Color(0xFFB5ABFC),
    val accent500: Color = Color(0xFF968AE0),
    val accent600: Color = Color(0xFF796CBF),
    val accent700: Color = Color(0xFF5D5294),
    val accent800: Color = Color(0xFF423A6A),
    val accent900: Color = Color(0xFF2B2741),

    // — accent-2 ramp, for the second chart series and its tag —
    val accent2800: Color = Color(0xFF423E5D),
    val accent2100: Color = Color(0xFFF5F4FF),

    // — text opacities, all derived from `text` —
    /** Primary text. The same colour as [text]; the name is what call sites read. */
    val textPrimary: Color = text,
    /** `.field > label` — text at 70%. Form labels and secondary prose. */
    val textLabel: Color = text.copy(alpha = 0.70f),
    /** `.text-muted` — text at 55%. Captions and metadata. */
    val textMuted: Color = text.copy(alpha = 0.55f),
    /**
     * The pre-Nocturne names for the two derived text tones, kept so an older
     * call site still compiles. Prefer [textLabel] and [textMuted]: those are
     * the stylesheet's own names, and the stylesheet says what each is *for*
     * (form label, caption) rather than how far down a ladder it sits.
     */
    val textSecondary: Color = textLabel,
    val textTertiary: Color = textMuted,

    /** `--color-divider`: text at 16%. Every hairline in the product. */
    val divider: Color = text.copy(alpha = 0.16f),
    /** The pre-Nocturne name for [divider]. */
    val border: Color = divider,
    /**
     * The one step up from a hairline, for a control that wants to look
     * touchable without becoming an accent. `neutral-700` is `--shadow-md`'s
     * ring, which is exactly what "raised" means in this system.
     */
    val borderStrong: Color = neutral700,

    /**
     * Nocturne has exactly one auxiliary panel tone, because elevation here is
     * a ring rather than a surface tone — `--shadow-sm/md` are hairlines, not
     * shadows. So a raised panel and a sunken one are the same `neutral-900`;
     * what separates them is the ring, not the fill.
     */
    val surfaceRaised: Color = neutral900,
    val surfaceSunken: Color = neutral900,

    // — interaction tints, from Nocturne's `.btn-*:hover` / `:active` rules —
    /** `.btn-primary:hover` — accent at 12%. */
    val accentHover: Color = accent.copy(alpha = 0.12f),
    /** `.btn-primary:active` — accent at 22%. */
    val accentPressed: Color = accent.copy(alpha = 0.22f),
    /** `.btn-ghost:hover` — accent at 10%. */
    val accentGhostHover: Color = accent.copy(alpha = 0.10f),
    /** `.btn-ghost:active` — accent at 18%. */
    val accentGhostPressed: Color = accent.copy(alpha = 0.18f),
    /** `.btn-secondary:hover` — text at 7%. */
    val neutralHover: Color = text.copy(alpha = 0.07f),
    /** `.btn-secondary:active` — text at 14%. */
    val neutralPressed: Color = text.copy(alpha = 0.14f),
    /** A selected-but-not-pressed accent ground, at the hover tint. */
    val accentSoft: Color = accentHover,

    // — architecture palette: functional, never decorative —
    /**
     * ARM64 / ARM64EC — runs natively, no translation. Green reads as "free",
     * and that is exactly what a native ARM64 app is.
     *
     * These four avoid violet on purpose: Nocturne's accent is itself violet,
     * so a violet badge stops reading as information and starts reading as a
     * button.
     */
    val archNative: Color = Color(0xFF5BD99A),
    /** x86-64 — translated by FEX. */
    val archX64: Color = Color(0xFF7FB0F0),
    /** x86-32 — the WoW64 path. */
    val archX86: Color = Color(0xFFE0A458),
    /** Not yet inspected, or the PE header would not read. `neutral-600`. */
    val archUnknown: Color = neutral600,

    // — status —
    val ok: Color = Color(0xFF5BD99A),
    val warn: Color = Color(0xFFE0A458),
    val danger: Color = Color(0xFFE5697A),
    val info: Color = accent,
) {
    /** Nocturne drops a disabled control to 45% rather than recolouring it. */
    val disabledAlpha: Float get() = 0.45f
}

/**
 * Radius, spacing and motion.
 *
 * The spacing scale is Nocturne's 2.8 dp base rounded to whole dp — 3, 6, 8,
 * 11, 17, 22 — and it stops at 22. There is no 32: a screen that wants more air
 * than 22 dp is asking for a different layout, not a bigger token.
 */
@Immutable
data class VMetrics(
    val radiusSm: Dp = 4.dp,
    val radiusMd: Dp = 8.dp,
    val radiusLg: Dp = 14.dp,
    val hairline: Dp = 1.dp,

    /** `--space-1` … `--space-8`, named for the dp they are. */
    val s3: Dp = 3.dp,
    val s6: Dp = 6.dp,
    val s8: Dp = 8.dp,
    val s11: Dp = 11.dp,
    val s17: Dp = 17.dp,
    val s22: Dp = 22.dp,

    /**
     * The margin every screen's content sits inside.
     *
     * `--space-4`, and not a value of its own. It was 18 dp, which is off the
     * scale entirely and made a settings list read as a consumer form with a lot
     * of air around it. This product is an instrument: dense, and aligned to the
     * same grid as everything inside it.
     */
    val screenGutter: Dp = s11,
    val touchTarget: Dp = 44.dp,

    /**
     * How wide a column of content is allowed to get.
     *
     * This phone is 927 dp across in landscape. Left alone, every list row
     * stretches to that: a session's status tag ends up 800 dp away from the
     * timestamp it belongs to, and the container card becomes a metre of empty
     * card with a play button stranded on the far edge. Capping the column and
     * centring it is the whole of the landscape adaptation — the layouts
     * themselves are already correct at phone width, and were only ever wrong
     * because nothing stopped them growing.
     *
     * 560 dp rather than a typographic measure: this content is rows of controls
     * and mono readouts, not prose, and at 480 dp the param rows start wrapping
     * their help sentences to four lines on a screen that has the room.
     */
    val contentMaxWidth: Dp = 560.dp,

    /** A dialog is narrower than a screen column, or it stops reading as a dialog. */
    val dialogMaxWidth: Dp = 420.dp,

    /**
     * The height every field, dropdown, stepper button and text button shares.
     *
     * One number, because the thing that makes a settings screen look built
     * rather than assembled is that a text field and the button beside it are
     * the same height. 36 dp is Nocturne's control size; it is under the touch
     * floor on purpose and the *tappable* controls extend past it — see
     * [touchTarget], which is what a bare icon action takes.
     */
    val controlHeight: Dp = 36.dp,

    /** A square icon control in a toolbar or a card: smaller than the touch floor, padded to it. */
    val iconButton: Dp = 40.dp,

    /** Glyph sizes. `md` is a toolbar or button icon; `sm` is inline with text. */
    val iconMd: Dp = 18.dp,
    val iconSm: Dp = 14.dp,

    /** Motion is confirmation, never decoration: one duration, no springs. */
    val durationStandardMs: Int = 150,
    val durationSheetMs: Int = 250,
) {
    val shapeSm: Shape = RoundedCornerShape(radiusSm)
    val shapeMd: Shape = RoundedCornerShape(radiusMd)
    val shapeLg: Shape = RoundedCornerShape(radiusLg)

    /** `.tag` — `calc(var(--radius-md) * 0.75)`. */
    val shapeTag: Shape = RoundedCornerShape(6.dp)
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

/**
 * `--font-heading-weight: 500`. Never bolder — the stylesheet is explicit, and
 * a 600 heading is the single most common way to make a Nocturne screen look
 * like something else.
 */
private val Heading = FontWeight.Medium

/**
 * The type scale from `docs/DESIGN.md`, which is `styles.css`'s scale **as the
 * phone screens use it**, not as the desktop stylesheet declares it.
 *
 * That distinction is the whole of this scale's history. The sizes here were
 * transcribed straight out of the CSS — 25 sp screen titles, 17 sp card titles,
 * a 15 sp body, a 22 sp metric — and on a 480 dpi phone the result read as a
 * consumer app with the accessibility text size turned up: a container tile
 * 78 dp tall to say one word, four metrics that could not fit on one line of a
 * rail. The reference app (`On Device AI`, `ui/theme/NocturneType.kt`) is the
 * same design system shipped on the same class of device, and its screens run a
 * scale step below the CSS: 21 root title, 17 pushed title, 14 card title,
 * 12.5–13 rows, 11 meta, 10–12 mono. This matches that, because two products in
 * one family should not disagree about how big a card title is.
 *
 * Headings carry `-0.015em` tracking. Every machine fact is one of the mono
 * styles.
 */
@Immutable
data class VType(
    /** The largest thing in the product, and nothing on a phone currently uses it. */
    val display: TextStyle = TextStyle(
        fontFamily = VSans,
        fontWeight = Heading,
        fontSize = 26.sp,
        lineHeight = 30.sp,
        letterSpacing = (-0.015).em,
    ),
    /** A root destination's header — the reference app's `RootTitle`, 21 / 25. */
    val title: TextStyle = TextStyle(
        fontFamily = VSans,
        fontWeight = Heading,
        fontSize = 21.sp,
        lineHeight = 25.sp,
        letterSpacing = (-0.015).em,
    ),
    /** A pushed toolbar's title and a dialog's — `ScreenTitle`, 17 / 21. */
    val subtitle: TextStyle = TextStyle(
        fontFamily = VSans,
        fontWeight = Heading,
        fontSize = 17.sp,
        lineHeight = 21.sp,
    ),
    /** `.card-title` at screen scale — 14 / 18. */
    val cardTitle: TextStyle = TextStyle(
        fontFamily = VSans,
        fontWeight = Heading,
        fontSize = 14.sp,
        lineHeight = 18.sp,
    ),
    /** An ordinary interface row: a param's title, a list row's label. 13.5 / 19. */
    val body: TextStyle = TextStyle(
        fontFamily = VSans,
        fontWeight = FontWeight.Normal,
        fontSize = 13.5.sp,
        lineHeight = 19.sp,
    ),
    /** `.card-body` — the help sentence under a control. 12 / 17. */
    val bodySmall: TextStyle = TextStyle(
        fontFamily = VSans,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 17.sp,
    ),
    /** `.field > label` and the bottom-nav label — 11 / 14. */
    val label: TextStyle = TextStyle(
        fontFamily = VSans,
        fontWeight = FontWeight.Normal,
        fontSize = 11.sp,
        lineHeight = 14.sp,
    ),
    /**
     * `h6` — 10 / 13, `letter-spacing: 0.08em`, uppercase.
     *
     * The uppercasing is not in the style: Compose has no `text-transform`, so
     * the call site passes `text.uppercase()`. [app.vessel.ui.components.VSectionHeader]
     * is the one place that should need to.
     */
    val overline: TextStyle = TextStyle(
        fontFamily = VSans,
        fontWeight = FontWeight.Normal,
        fontSize = 10.sp,
        lineHeight = 13.sp,
        letterSpacing = 0.08.em,
    ),
    /** Every version, flag, hash and path — 11.5 / 16, tabular. */
    val mono: TextStyle = TextStyle(
        fontFamily = VMono,
        fontWeight = FontWeight.Normal,
        fontSize = 11.5.sp,
        lineHeight = 16.sp,
        fontFeatureSettings = TABULAR_FIGURES,
    ),
    /** Log lines, captions and legends — 10 / 14, tabular. */
    val monoSmall: TextStyle = TextStyle(
        fontFamily = VMono,
        fontWeight = FontWeight.Normal,
        fontSize = 10.sp,
        lineHeight = 14.sp,
        fontFeatureSettings = TABULAR_FIGURES,
    ),
    /**
     * Live numbers with room around them — the Metrics tab's card headline.
     * 17 / 20, tabular.
     *
     * It was 22 / 26, sized for a full-width strip across a 392 dp phone. The
     * rail is not that: four readings in a column 150 dp wide, where 22 sp meant
     * `573 MB` broke across three lines and rendered as `57 / 3 / MB`. Numbers
     * that wrap are worse than no numbers, because they are still read.
     */
    val metric: TextStyle = TextStyle(
        fontFamily = VMono,
        fontWeight = Heading,
        fontSize = 17.sp,
        lineHeight = 20.sp,
        fontFeatureSettings = TABULAR_FIGURES,
    ),
    /**
     * The same reading where space is the constraint — the session rail.
     *
     * A separate step rather than a scale factor at the call site: the rail's
     * cell is sized around this, and a caller free to shrink the type is a
     * caller free to break the grid it sits in.
     */
    val metricSmall: TextStyle = TextStyle(
        fontFamily = VMono,
        fontWeight = Heading,
        fontSize = 13.sp,
        lineHeight = 16.sp,
        fontFeatureSettings = TABULAR_FIGURES,
    ),
) {
    /** `.btn` / `.input` sit at one size so they align in a row: 12.5 / 500. */
    val control: TextStyle get() = TextStyle(
        fontFamily = VSans,
        fontWeight = Heading,
        fontSize = 12.5.sp,
        lineHeight = 16.sp,
    )
}

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
                // `::selection { background: color-mix(... accent 30%, transparent) }`
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
 *
 * `onPrimary` is `accent-100` rather than `bg`, because in Nocturne nothing is
 * ever filled with the accent: there is no accent slab for a `bg` label to sit
 * on.
 */
private fun VColors.toMaterialScheme() = darkColorScheme(
    primary = accent,
    onPrimary = accent100,
    primaryContainer = accent900,
    onPrimaryContainer = accent200,
    secondary = accent2,
    onSecondary = accent2100,
    secondaryContainer = accent900,
    onSecondaryContainer = accent200,
    tertiary = accent,
    onTertiary = accent100,
    background = bg,
    onBackground = textPrimary,
    surface = surface,
    onSurface = textPrimary,
    surfaceVariant = neutral900,
    onSurfaceVariant = textMuted,
    surfaceContainerLowest = bg,
    surfaceContainerLow = neutral900,
    surfaceContainer = surface,
    surfaceContainerHigh = surface,
    surfaceContainerHighest = neutral800,
    outline = neutral700,
    outlineVariant = neutral800,
    error = danger,
    onError = bg,
    scrim = neutral900,
)

/**
 * `--shadow-sm` / `--shadow-md`.
 *
 * **Elevation here is a hairline ring, not a shadow.** `--shadow-sm` is
 * `0 0 0 1px #3f424d` and nothing else; `--shadow-md` adds
 * `0 6px 18px rgba(0,0,0,0.55)` behind a lighter ring. A Material drop shadow
 * with no ring is the wrong shape for this system.
 */
@Immutable
data class VElevation(
    val ring: Color,
    val ambientOffsetY: Dp = 0.dp,
    val ambientAlpha: Float = 0f,
)

/** The two elevations the product uses. `lg` is dialog-only and lives with the sheet. */
object VElev {
    /** `0 0 0 1px #3f424d` — a ring, no shadow at all. */
    val sm: VElevation
        @Composable @ReadOnlyComposable get() = VElevation(Vessel.colors.neutral800)

    /** `0 0 0 1px #595d6c, 0 6px 18px rgba(0,0,0,0.55)`. */
    val md: VElevation
        @Composable @ReadOnlyComposable get() =
            VElevation(Vessel.colors.neutral700, ambientOffsetY = 6.dp, ambientAlpha = 0.55f)

    /** `0 0 0 1px #9397ab, 0 16px 40px rgba(0,0,0,0.65)` — dialogs. */
    val lg: VElevation
        @Composable @ReadOnlyComposable get() =
            VElevation(Vessel.colors.neutral500, ambientOffsetY = 16.dp, ambientAlpha = 0.65f)
}

/**
 * A card: `surface` ground, `md` radius, and a ring.
 *
 * Order is load-bearing. The shadow has to be behind the fill, and the ring has
 * to be in front of it — an opaque `background` declared after a `border` paints
 * straight over the hairline, which is how a card silently loses its edge.
 */
@Composable
fun Modifier.vCard(
    fill: Color = Vessel.colors.surface,
    shape: Shape = Vessel.metrics.shapeMd,
    elevation: VElevation = VElev.sm,
): Modifier = vElevation(elevation, shape).background(fill, shape).vRing(elevation.ring, shape)

/**
 * The column every screen's content lives in.
 *
 * **The order is the whole modifier.** `widthIn` narrows the incoming maximum;
 * `fillMaxWidth` then pins the child to whatever maximum it was handed. Written
 * the other way round the cap does nothing at all — `fillMaxWidth` has already
 * fixed `min == max == window`, and `widthIn` coerces its own bound back into
 * that range. The result compiles, reads correctly, and stretches to 927 dp.
 *
 * Both halves are needed: `widthIn` alone leaves a wrap-content child hugging
 * its text. The parent's `horizontalAlignment` centres what is left.
 *
 * Bars are deliberately *not* wrapped in this: a bottom nav's ground and its
 * hairline run edge to edge, and only the destinations inside it are capped.
 */
@Composable
fun Modifier.vContentColumn(): Modifier =
    widthIn(max = Vessel.metrics.contentMaxWidth).fillMaxWidth()

/** The shadow half of an elevation, if it has one. */
@Composable
fun Modifier.vElevation(elevation: VElevation, shape: Shape = Vessel.metrics.shapeMd): Modifier =
    if (elevation.ambientAlpha > 0f) {
        shadow(
            elevation = elevation.ambientOffsetY,
            shape = shape,
            clip = false,
            ambientColor = Color.Black.copy(alpha = elevation.ambientAlpha),
            spotColor = Color.Black.copy(alpha = elevation.ambientAlpha),
        )
    } else {
        this
    }

/** `box-shadow: inset 0 0 0 1px <color>` — the hairline that does the work of elevation. */
@Composable
fun Modifier.vRing(
    color: Color,
    shape: Shape = Vessel.metrics.shapeMd,
    width: Dp = Vessel.metrics.hairline,
): Modifier = border(width, color, shape)

/** Where a rule is drawn inside the box it decorates. */
enum class VRulePosition { Top, Center, Bottom }

/**
 * The Nocturne signature: a freestanding rule fades to transparent over 48 dp
 * at each end instead of stopping cleanly.
 *
 * Box outlines, in-control separators and short accent marks stay solid — see
 * [vRuleAbove] and [vRuleBelow], which are the bar-edge form.
 */
fun Modifier.vFadingRule(
    color: Color,
    fadeWidth: Dp = 48.dp,
    position: VRulePosition = VRulePosition.Bottom,
): Modifier = drawBehind {
    if (size.width <= 0f) return@drawBehind
    val fade = fadeWidth.toPx().coerceAtMost(size.width / 2f)
    val stops = if (fade * 2f >= size.width) {
        // See VRule: narrower than two fades becomes one soft mark.
        arrayOf(0f to Color.Transparent, 0.5f to color, 1f to Color.Transparent)
    } else {
        arrayOf(
            0f to Color.Transparent,
            fade / size.width to color,
            1f - fade / size.width to color,
            1f to Color.Transparent,
        )
    }
    val y = when (position) {
        VRulePosition.Top -> 0.5f
        VRulePosition.Center -> size.height / 2f
        VRulePosition.Bottom -> size.height - 0.5f
    }
    drawLine(
        brush = Brush.horizontalGradient(colorStops = stops),
        start = Offset(0f, y),
        end = Offset(size.width, y),
        strokeWidth = 1f,
    )
}

/** A solid hairline along the top edge — bottom bars and overlays, which are boxes. */
fun Modifier.vRuleAbove(color: Color): Modifier = drawBehind {
    drawLine(color, Offset(0f, 0.5f), Offset(size.width, 0.5f), strokeWidth = 1f)
}

/** The same rule under a row. */
fun Modifier.vRuleBelow(color: Color): Modifier = drawBehind {
    val y = size.height - 0.5f
    drawLine(color, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
}
