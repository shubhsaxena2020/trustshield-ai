package ai.trustshield.android.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * WCAG 2.1 contrast maths over `androidx.compose.ui.graphics.Color`, plus the audit table of the
 * text/background pairs this app actually paints.
 *
 * Why this file exists next to `Color.kt`'s [Contrast] object:
 *
 *  * [Contrast] (in `Color.kt`) is the ARGB-first core the C02 token contract froze, and other
 *    suites already call it. It cannot be renamed or removed, so a second `object Contrast` in this
 *    package would be a redeclaration and break the build.
 *  * [TsContrast] is the *Color-first* entry point: callers hold [Color] tokens, never packed ints.
 *    It is an independent implementation (float sRGB channels instead of `toArgb()`), and
 *    `ThemeContrastTest.mathsAgreesWithTheArgbContrastObject` pins the two to agree on every pair in
 *    [TsContrastPairs], so the two paths can never drift apart silently.
 *
 * Everything here is pure Kotlin value-class maths: no `Context`, no composition, no Compose
 * runtime. `ThemeContrastTest` therefore runs on a plain JVM with no Robolectric and no UI-test
 * dependency.
 *
 * Reference: WCAG 2.1 SC 1.4.3 (text contrast) and SC 1.4.11 (non-text contrast).
 */
object TsContrast {

    /** SC 1.4.3, normal text (< 18 pt, or < 14 pt bold). */
    const val NORMAL_TEXT_MIN: Double = 4.5

    /** SC 1.4.3, large text (>= 18 pt, or >= 14 pt bold — 20 sp semibold and 28 sp qualify). */
    const val LARGE_TEXT_MIN: Double = 3.0

    /** SC 1.4.11, non-text: icons, focus rings, meter fills, component boundaries. */
    const val NON_TEXT_MIN: Double = 3.0

    /** sRGB linearisation knee from WCAG 2.1 (`0.03928`, not the 2.0-era `0.039`). */
    private const val LINEAR_KNEE: Double = 0.03928

    /** WCAG 2.1 relative luminance of [color], in `0.0..1.0`. Alpha is ignored (tokens are opaque). */
    fun relativeLuminance(color: Color): Double =
        0.2126 * linearize(color.red) + 0.7152 * linearize(color.green) + 0.0722 * linearize(color.blue)

    /** One sRGB channel (`0.0..1.0`) -> linear light, per WCAG 2.1. */
    fun linearize(channel: Float): Double {
        val c = channel.toDouble().coerceIn(0.0, 1.0)
        return if (c <= LINEAR_KNEE) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }

    /**
     * Contrast ratio between [foreground] and [background], in `1.0..21.0`.
     *
     * Order does not matter: `ratio(a, b) == ratio(b, a)`.
     */
    fun ratio(foreground: Color, background: Color): Double {
        val a = relativeLuminance(foreground)
        val b = relativeLuminance(background)
        return (max(a, b) + 0.05) / (min(a, b) + 0.05)
    }
}

/** Which WCAG minimum a [ContrastPair] is judged against. */
enum class ContrastBand(val minimumRatio: Double) {

    /** Body/support text at 14-16 sp — SC 1.4.3 normal text. */
    NORMAL_TEXT(TsContrast.NORMAL_TEXT_MIN),

    /** Headlines/titles at 20 sp semibold and 28 sp — SC 1.4.3 large text. */
    LARGE_TEXT(TsContrast.LARGE_TEXT_MIN),

    /** Icons, focus rings, meter fills, left rules — SC 1.4.11. */
    NON_TEXT(TsContrast.NON_TEXT_MIN)
}

/**
 * One foreground/background pair the app really draws, with the minimum ratio it must clear.
 *
 * [name] is written so a failing assertion says *which* text on *which* surface is unreadable,
 * e.g. `light: RiskTone.RED onSurface / surface`.
 */
data class ContrastPair(
    val name: String,
    val foreground: Color,
    val background: Color,
    val band: ContrastBand
) {
    /** Measured WCAG 2.1 contrast ratio for this pair. */
    val ratio: Double get() = TsContrast.ratio(foreground, background)

    /** The minimum this pair must reach, from [band]. */
    val minimum: Double get() = band.minimumRatio

    /** True when the measured ratio clears [minimum]. */
    val passes: Boolean get() = ratio >= minimum

    /** `<name> — <ratio>:1 (min <minimum>:1)`, for test output and the audit doc. */
    val summary: String
        get() = String.format(Locale.ROOT, "%s — %.2f:1 (min %.1f:1)", name, ratio, minimum)
}

/**
 * The audit table: every text/background (and meaningful graphic/background) pair the shipped
 * schemes in `Theme.kt` plus the [RiskTone] pairs in `Color.kt` can paint.
 *
 * The colours are read straight off [TsColorSchemes] and [RiskTone], never re-typed as hex, so the
 * table cannot drift from what a screen actually renders. Worst measured normal-text pair today is
 * `light: onPrimary / primary` at 5.14:1.
 *
 * Deliberately **not** in this table: `outlineVariant` (documented in `Color.kt` as decorative
 * dividers only) — it is not text and it is not a meaningful boundary, so certifying it would
 * promise something the spec never asked for.
 */
object TsContrastPairs {

    /** Every pair the light scheme paints. */
    val light: List<ContrastPair> = pairsFor(dark = false, mode = "light")

    /** Every pair the dark scheme paints — same names as [light] apart from the mode prefix. */
    val dark: List<ContrastPair> = pairsFor(dark = true, mode = "dark")

    /** Both modes. */
    fun all(): List<ContrastPair> = light + dark

    /** Pairs judged against [band]. */
    fun byBand(band: ContrastBand): List<ContrastPair> = all().filter { it.band == band }

    private fun pairsFor(dark: Boolean, mode: String): List<ContrastPair> {
        val scheme: ColorScheme = TsColorSchemes.of(dark)
        return buildList {
            // ---- scheme text slots, normal size (14-16 sp body/support/label) ---------------------
            add(normal("$mode: onSurface / surface", scheme.onSurface, scheme.surface))
            add(normal("$mode: onBackground / background", scheme.onBackground, scheme.background))
            add(normal("$mode: onSurfaceVariant / surface", scheme.onSurfaceVariant, scheme.surface))
            add(
                normal(
                    "$mode: onSurfaceVariant / surfaceVariant",
                    scheme.onSurfaceVariant, scheme.surfaceVariant
                )
            )
            add(normal("$mode: onPrimary / primary", scheme.onPrimary, scheme.primary))
            add(
                normal(
                    "$mode: onPrimaryContainer / primaryContainer",
                    scheme.onPrimaryContainer, scheme.primaryContainer
                )
            )
            add(normal("$mode: onSecondary / secondary", scheme.onSecondary, scheme.secondary))
            add(
                normal(
                    "$mode: onSecondaryContainer / secondaryContainer",
                    scheme.onSecondaryContainer, scheme.secondaryContainer
                )
            )
            add(normal("$mode: onTertiary / tertiary", scheme.onTertiary, scheme.tertiary))
            add(normal("$mode: onError / error", scheme.onError, scheme.error))
            add(
                normal(
                    "$mode: onErrorContainer / errorContainer",
                    scheme.onErrorContainer, scheme.errorContainer
                )
            )
            add(
                normal(
                    "$mode: inverseOnSurface / inverseSurface",
                    scheme.inverseOnSurface, scheme.inverseSurface
                )
            )
            // Outline carries label text as well as borders, so it is held to the text minimum.
            add(normal("$mode: outline / surface", scheme.outline, scheme.surface))

            // ---- RiskTone text (see RiskToneSemantics.kt for the label+icon+text contract) --------
            RiskTone.entries.forEach { tone ->
                val toneName = tone.name
                // Tone-coloured label drawn straight on the screen surface (text-safe band).
                add(normal("$mode: RiskTone.$toneName onSurface / surface", tone.onSurface(dark), scheme.surface))
                // Chip label inside its own tinted container.
                add(normal("$mode: RiskTone.$toneName onContainer / container", tone.onContainer(dark), tone.container(dark)))
                // Call Shield stage chip: container fill + tone.onSurface() as contentColor.
                add(normal("$mode: RiskTone.$toneName onSurface / container", tone.onSurface(dark), tone.container(dark)))
            }

            // ---- large text: 20 sp semibold titles and 28 sp headlines (>= 3:1) ------------------
            add(large("$mode: 28 sp headline — onSurface / surface", scheme.onSurface, scheme.surface))
            add(large("$mode: 28 sp headline — onBackground / background", scheme.onBackground, scheme.background))
            add(large("$mode: 20 sp section title — onSurface / surface", scheme.onSurface, scheme.surface))
            add(
                large(
                    "$mode: 20 sp confidence glyph — onSurfaceVariant / surfaceVariant",
                    scheme.onSurfaceVariant, scheme.surfaceVariant
                )
            )
            RiskTone.entries.forEach { tone ->
                add(
                    large(
                        "$mode: 20 sp RiskTone.${tone.name} heading — onSurface / surface",
                        tone.onSurface(dark), scheme.surface
                    )
                )
            }

            // ---- meaningful graphics: focus rings, icons, left rules, meter fills (>= 3:1) -------
            add(graphic("$mode: focus ring — accent / surface", scheme.primary, scheme.surface))
            add(graphic("$mode: focus ring — accent / background", scheme.primary, scheme.background))
            RiskTone.entries.forEach { tone ->
                add(
                    graphic(
                        "$mode: RiskTone.${tone.name} accent / surface",
                        tone.accent(dark), scheme.surface
                    )
                )
            }
        }
    }

    private fun normal(name: String, foreground: Color, background: Color): ContrastPair =
        ContrastPair(name, foreground, background, ContrastBand.NORMAL_TEXT)

    private fun large(name: String, foreground: Color, background: Color): ContrastPair =
        ContrastPair(name, foreground, background, ContrastBand.LARGE_TEXT)

    private fun graphic(name: String, foreground: Color, background: Color): ContrastPair =
        ContrastPair(name, foreground, background, ContrastBand.NON_TEXT)
}
