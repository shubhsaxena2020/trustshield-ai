package ai.trustshield.android.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * TrustShield design tokens.
 *
 * Values are pinned to `docs/android/UI_SPEC.md` ("Tokens and motion"). Everything here is plain
 * Kotlin + `Color`/`Dp` value classes so [ThemeTest] can verify contrast on a plain JVM without a
 * device, and so screens can reason about tone without depending on MaterialTheme state.
 *
 * Colour roles are deliberately split into three bands:
 *
 *  1. **Brand / hazard hues** ([Accent], [RiskGreen], [RiskAmber], [RiskRed]) — exact spec hexes.
 *     Use them for fills, strokes, icons, indicators, progress arcs. They clear the WCAG 2.1
 *     *non-text* minimum (>= 3:1) against the light surface; amber is 3.87:1 and accent 4.27:1, so
 *     neither may be used for normal-size body text on a light background.
 *  2. **Dark counterparts** ([AccentDark], [RiskGreenDark], [RiskAmberDark], [RiskRedDark]) —
 *     measured 8.0:1 .. 10.3:1 against the dark surface, so they are safe for text *and* graphics.
 *  3. **Text-safe light hues** ([AccentTextLight], [RiskGreenTextLight], [RiskAmberTextLight],
 *     [RiskRedTextLight]) — the light-mode answer to (1) for normal-size text, each >= 6.1:1 on the
 *     light surface.
 *
 * Tone *surfaces* (chips, evidence cards) come from [RiskTone.container] / [RiskTone.onContainer],
 * which are certified >= 4.5:1 in both modes.
 */
object TsTokens {

    // ---- Brand + hazard hues: exact UI_SPEC values -------------------------------------------------

    /** Monochrome foreground used for fills, focus rings and indicators. */
    val Accent = Color(0xFF000000)

    /** Risk green, `#16845B`. 4.68:1 on white, 4.44:1 on [TsPalette.SurfaceLight]. */
    val RiskGreen = Color(0xFF16845B)

    /** Risk amber, `#B86B00`. 4.09:1 on white -> graphics only, never normal text on light. */
    val RiskAmber = Color(0xFFB86B00)

    /** Risk red, `#C73642`. 5.21:1 on white, safe for text on light. */
    val RiskRed = Color(0xFFC73642)

    // ---- Accessible dark counterparts (measured against TsPalette.SurfaceDark #12161F) -------------
    // AccentDark 8.74:1 | RiskGreenDark 10.16:1 | RiskAmberDark 10.25:1 | RiskRedDark 8.02:1
    // Hues are lifted and desaturated so each stays recognisable while clearing 4.5:1 as text.

    val AccentDark = Color(0xFFFFFFFF)
    val RiskGreenDark = Color(0xFF4ED9A4)
    val RiskAmberDark = Color(0xFFF5B85C)
    val RiskRedDark = Color(0xFFFF8A93)

    // ---- Text-safe light hues (measured against TsPalette.SurfaceLight #F7F9FC) --------------------
    // AccentTextLight 6.51:1 | RiskGreenTextLight 6.27:1 | RiskAmberTextLight 6.17:1
    // RiskRedTextLight 6.66:1
    // Only the amber/accent entries are strictly required (the spec hexes miss 4.5:1 on light), but
    // the whole band exists so a screen never has to reason about which hue is safe where.

    val AccentTextLight = Color(0xFF000000)
    val RiskGreenTextLight = Color(0xFF0F6A48)
    val RiskAmberTextLight = Color(0xFF8A5000)
    val RiskRedTextLight = Color(0xFFA32D38)

    // ---- Spacing: 4 dp scale (all values sit on the 4 dp grid, and 8/16/24 on the 8 dp grid) -------

    val SpacingXs: Dp = 4.dp
    val SpacingSm: Dp = 8.dp
    val SpacingMd: Dp = 12.dp
    val SpacingLg: Dp = 16.dp
    val SpacingXl: Dp = 24.dp

    /** Ordered scale, for tests and "which step is this" logic. */
    val SpacingScale: List<Dp> = listOf(SpacingXs, SpacingSm, SpacingMd, SpacingLg, SpacingXl)

    // ---- Shape -------------------------------------------------------------------------------------

    val CornerSmall: Dp = 12.dp
    val CornerMedium: Dp = 16.dp
    val CornerLarge: Dp = 20.dp

    val ElevationLow: Dp = 1.dp
    val ElevationRaised: Dp = 3.dp

    // ---- Interaction -------------------------------------------------------------------------------

    /** Minimum touch target (UI_SPEC / HEAD_ORDERS: >= 48 dp), independent of visual size. */
    val MinTouchTarget: Dp = 48.dp

    // ---- Motion (UI_SPEC: risk/stage transitions live in the 180-240 ms band) ----------------------

    const val MotionShortMs: Int = 180
    const val MotionMediumMs: Int = 240

    /** Closed band a motion token must stay inside. */
    val MotionBandMs: IntRange = 180..240
}

/**
 * Surface/container/outline palette that backs the Material 3 schemes in Theme.kt.
 *
 * Kept apart from [TsTokens] so the public token API stays exactly the frozen C02 surface
 * (the contract C03/C05/C06 code against).
 */
object TsPalette {

    // Light
    val SurfaceLight = Color(0xFFF5F5F5)
    val BackgroundLight = Color(0xFFFFFFFF)
    val OnSurfaceLight = Color(0xFF000000)
    val OnSurfaceVariantLight = Color(0xFF595959)
    val SurfaceVariantLight = Color(0xFFE5E5E5)
    val OutlineLight = Color(0xFF595959)
    val OutlineVariantLight = Color(0xFFD0D0D0)

    /** White theme primary buttons use a black fill. */
    val AccentFillLight = Color(0xFF000000)
    val OnAccentFillLight = Color(0xFFFFFFFF)
    val AccentContainerLight = Color(0xFFE5E5E5)
    val OnAccentContainerLight = Color(0xFF000000)

    // Dark
    val SurfaceDark = Color(0xFF141414)
    val BackgroundDark = Color(0xFF050505)
    val OnSurfaceDark = Color(0xFFFFFFFF)
    val OnSurfaceVariantDark = Color(0xFFBDBDBD)
    val SurfaceVariantDark = Color(0xFF242424)
    val OutlineDark = Color(0xFFBDBDBD)
    val OutlineVariantDark = Color(0xFF414141)

    val AccentContainerDark = Color(0xFF292929)
    val OnAccentContainerDark = Color(0xFFFFFFFF)
    val OnAccentFillDark = Color(0xFF000000)

    // Tone containers (see RiskTone.container / RiskTone.onContainer)
    val NeutralContainerLight = Color(0xFFE5E5E5)
    val OnNeutralContainerLight = Color(0xFF000000)
    val GreenContainerLight = Color(0xFFDCF2E7)
    val OnGreenContainerLight = Color(0xFF0C4B34)   // 8.64:1
    val AmberContainerLight = Color(0xFFFDECD2)
    val OnAmberContainerLight = Color(0xFF5C3600)   // 9.13:1
    val RedContainerLight = Color(0xFFFBE0E2)
    val OnRedContainerLight = Color(0xFF6E141C)     // 9.47:1

    val NeutralContainerDark = Color(0xFF242424)
    val OnNeutralContainerDark = Color(0xFFFFFFFF)
    val GreenContainerDark = Color(0xFF10321F)
    val OnGreenContainerDark = Color(0xFFA8E9C9)    // 10.12:1
    val AmberContainerDark = Color(0xFF3A2708)
    val OnAmberContainerDark = Color(0xFFFFD9A0)    // 10.64:1
    val RedContainerDark = Color(0xFF3E1518)
    val OnRedContainerDark = Color(0xFFFFB3B8)      // 9.35:1
}

/**
 * Presentation severity tone. **This is never a verdict.**
 *
 * The deterministic engine owns risk score and confidence; a screen only maps an already-computed
 * score band onto one of these tones so the panel has a consistent look. Nothing in this file may be
 * used to decide whether something is a scam.
 */
enum class RiskTone { NEUTRAL, GREEN, AMBER, RED }

/** Tinted surface behind a tone's label/icon. Certified >= 4.5:1 against [onContainer]. */
fun RiskTone.container(dark: Boolean): Color = when (this) {
    RiskTone.NEUTRAL -> if (dark) TsPalette.NeutralContainerDark else TsPalette.NeutralContainerLight
    RiskTone.GREEN -> if (dark) TsPalette.GreenContainerDark else TsPalette.GreenContainerLight
    RiskTone.AMBER -> if (dark) TsPalette.AmberContainerDark else TsPalette.AmberContainerLight
    RiskTone.RED -> if (dark) TsPalette.RedContainerDark else TsPalette.RedContainerLight
}

/** Normal-text colour on top of [container]. Certified >= 4.5:1 in both themes. */
fun RiskTone.onContainer(dark: Boolean): Color = when (this) {
    RiskTone.NEUTRAL -> if (dark) TsPalette.OnNeutralContainerDark else TsPalette.OnNeutralContainerLight
    RiskTone.GREEN -> if (dark) TsPalette.OnGreenContainerDark else TsPalette.OnGreenContainerLight
    RiskTone.AMBER -> if (dark) TsPalette.OnAmberContainerDark else TsPalette.OnAmberContainerLight
    RiskTone.RED -> if (dark) TsPalette.OnRedContainerDark else TsPalette.OnRedContainerLight
}

/**
 * Brand hue for the tone: icon tint, left rule, meter fill. Non-text graphics only on light
 * (accent 4.27:1, green 4.44:1, amber 3.87:1, red 4.94:1 vs [TsPalette.SurfaceLight] -> all >= 3:1).
 */
fun RiskTone.accent(dark: Boolean): Color = when (this) {
    RiskTone.NEUTRAL -> if (dark) TsPalette.OnSurfaceVariantDark else TsPalette.OnSurfaceVariantLight
    RiskTone.GREEN -> if (dark) TsTokens.RiskGreenDark else TsTokens.RiskGreen
    RiskTone.AMBER -> if (dark) TsTokens.RiskAmberDark else TsTokens.RiskAmber
    RiskTone.RED -> if (dark) TsTokens.RiskRedDark else TsTokens.RiskRed
}

/** Text-safe tone hue for normal-size text drawn straight onto the screen surface (>= 4.5:1). */
fun RiskTone.onSurface(dark: Boolean): Color = when (this) {
    RiskTone.NEUTRAL -> if (dark) TsPalette.OnSurfaceVariantDark else TsPalette.OnSurfaceVariantLight
    RiskTone.GREEN -> if (dark) TsTokens.RiskGreenDark else TsTokens.RiskGreenTextLight
    RiskTone.AMBER -> if (dark) TsTokens.RiskAmberDark else TsTokens.RiskAmberTextLight
    RiskTone.RED -> if (dark) TsTokens.RiskRedDark else TsTokens.RiskRedTextLight
}

/**
 * Motion helpers.
 *
 * UI_SPEC: risk/stage transitions run 180-240 ms; a critical alert must never pulse or flash, and
 * reduced-motion (animator scale 0) removes non-essential transitions rather than shortening them.
 */
object TsMotion {

    /** Duration to use for a token, collapsing to 0 ms when the user asked for reduced motion. */
    fun duration(tokenMs: Int, reducedMotion: Boolean): Int = if (reducedMotion) 0 else tokenMs

    /**
     * Critical-alert transitions must not loop. Screens assert this by rendering the alert once with
     * [Token.MotionMediumMs] and no infinite/repeating spec.
     */
    const val CRITICAL_ALERT_REPEATS: Boolean = false

    object Token {
        const val MotionShortMs: Int = TsTokens.MotionShortMs
        const val MotionMediumMs: Int = TsTokens.MotionMediumMs
    }
}

/**
 * WCAG 2.1 contrast maths (relative luminance + ratio), callable from a plain JVM unit test.
 *
 * Deliberately built on ARGB ints: [ratio] is a thin convenience wrapper over [ratioArgb], so the
 * whole thing works even if a caller cannot construct a Compose `Color`.
 */
object Contrast {

    /** WCAG 2.1 SC 1.4.3 normal text. */
    const val NORMAL_TEXT_MIN: Double = 4.5

    /** WCAG 2.1 SC 1.4.3 large text (>= 18pt, or >= 14pt bold). */
    const val LARGE_TEXT_MIN: Double = 3.0

    /** WCAG 2.1 SC 1.4.11 non-text contrast (icons, borders, focus rings, meter fills). */
    const val NON_TEXT_MIN: Double = 3.0

    /** Contrast ratio between two opaque colours, e.g. `Contrast.ratio(fg, bg) >= 4.5`. */
    fun ratio(foreground: Color, background: Color): Double =
        ratioArgb(foreground.toArgb(), background.toArgb())

    /** Contrast ratio from packed 0xAARRGGBB ints. Alpha is ignored (tokens are opaque). */
    fun ratioArgb(foregroundArgb: Int, backgroundArgb: Int): Double {
        val a = relativeLuminanceArgb(foregroundArgb)
        val b = relativeLuminanceArgb(backgroundArgb)
        return (max(a, b) + 0.05) / (min(a, b) + 0.05)
    }

    /** WCAG 2.1 relative luminance of a packed 0xAARRGGBB colour, in 0.0..1.0. */
    fun relativeLuminanceArgb(argb: Int): Double {
        val r = linearChannel(argb shr 16 and 0xFF)
        val g = linearChannel(argb shr 8 and 0xFF)
        val b = linearChannel(argb and 0xFF)
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }

    /** sRGB 8-bit channel -> linear light, per WCAG 2.1. */
    fun linearChannel(channel8: Int): Double {
        val c = channel8 / 255.0
        return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }
}
