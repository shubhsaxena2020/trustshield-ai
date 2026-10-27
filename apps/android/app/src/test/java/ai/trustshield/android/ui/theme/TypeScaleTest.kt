package ai.trustshield.android.ui.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * Type-ramp proof for `ui/theme/Type.kt`.
 *
 * Two properties are asserted, both from `docs/android/UI_SPEC.md` ("Tokens and motion"):
 *
 *  1. every style sits on the documented 14 / 16 / 20 / 28 sp ladder; and
 *  2. every declared line height is at least 1.2x its font size, so a 200 % font scale at 320 dp
 *     cannot clip or overlap lines — Devanagari matras above and below the baseline need the most
 *     room, and the app ships Hindi copy.
 *
 * Plain JVM: JUnit 4 + Compose `TextStyle`/`TextUnit` value classes, no Robolectric, no device.
 */
class TypeScaleTest {

    private val styles: List<Pair<String, TextStyle>> = listOf(
        "displayLarge" to TsTypography.displayLarge,
        "displayMedium" to TsTypography.displayMedium,
        "displaySmall" to TsTypography.displaySmall,
        "headlineLarge" to TsTypography.headlineLarge,
        "headlineMedium" to TsTypography.headlineMedium,
        "headlineSmall" to TsTypography.headlineSmall,
        "titleLarge" to TsTypography.titleLarge,
        "titleMedium" to TsTypography.titleMedium,
        "titleSmall" to TsTypography.titleSmall,
        "bodyLarge" to TsTypography.bodyLarge,
        "bodyMedium" to TsTypography.bodyMedium,
        "bodySmall" to TsTypography.bodySmall,
        "labelLarge" to TsTypography.labelLarge,
        "labelMedium" to TsTypography.labelMedium,
        "labelSmall" to TsTypography.labelSmall
    )

    // --------------------------------------------------------------------------- the ramp itself

    @Test
    fun rampDeclaresExactlyTheFourteenSixteenTwentyTwentyEightSteps() {
        assertEquals("UI_SPEC type ladder", listOf(14f, 16f, 20f, 28f), TsTypeScale.AllowedSp)
        assertEquals(14f, TsTypeScale.SupportSp, 0f)
        assertEquals(16f, TsTypeScale.BodySp, 0f)
        assertEquals(20f, TsTypeScale.TitleSp, 0f)
        assertEquals(28f, TsTypeScale.HeadlineSp, 0f)
        TsTypeScale.AllowedSp.zipWithNext().forEach { (small, larger) ->
            assertTrue("the ramp must increase: $small -> $larger", larger > small)
        }
        // Nothing below 14 sp: support text must stay legible at 320 dp.
        assertTrue(
            "smallest step is ${TsTypeScale.AllowedSp.min()}, must be >= 14 sp",
            TsTypeScale.AllowedSp.min() >= 14f
        )
    }

    @Test
    fun everyStyleSitsOnAnAllowedStep() {
        styles.forEach { (name, style) ->
            val sp = style.fontSize.value
            assertTrue(
                String.format(Locale.ROOT, "%s is %.2f sp, outside the 14/16/20/28 ladder", name, sp),
                TsTypeScale.isAllowed(sp)
            )
            assertTrue("$name must not exceed the 28 sp headline step", sp <= TsTypeScale.HeadlineSp)
        }
        // Sanity: the ladder is actually exercised, not all one size.
        assertEquals(
            "the ramp must use every step",
            TsTypeScale.AllowedSp.toSet(),
            styles.map { it.second.fontSize.value }.toSet()
        )
    }

    // ------------------------------------------------------------------------- line heights

    @Test
    fun everyStyleDeclaresALineHeightInSp() {
        styles.forEach { (name, style) ->
            assertNotEquals("$name must declare a lineHeight", TextUnit.Unspecified, style.lineHeight)
            assertEquals(
                "$name lineHeight must be in sp so it scales with the font-size setting",
                TextUnitType.Sp, style.lineHeight.type
            )
        }
    }

    /** The 200 % font-scale guard: `lineHeight >= 1.2 * fontSize` for every style that declares one. */
    @Test
    fun everyDeclaredLineHeightIsAtLeastOnePointTwoTimesItsFontSize() {
        val tooTight = styles.mapNotNull { (name, style) ->
            if (style.lineHeight == TextUnit.Unspecified) return@mapNotNull null
            val fontSp = style.fontSize.value
            val lineSp = style.lineHeight.value
            val ratio = if (fontSp > 0f) lineSp / fontSp else Float.MAX_VALUE
            if (ratio < MIN_LINE_HEIGHT_RATIO) {
                String.format(
                    Locale.ROOT,
                    "%s: lineHeight %.1f sp on %.1f sp = %.3fx, below %.1fx — a 200%% font scale would clip",
                    name, lineSp, fontSp, ratio, MIN_LINE_HEIGHT_RATIO
                )
            } else {
                null
            }
        }
        assertTrue(
            "line heights too tight for a 200 % font scale:\n  " + tooTight.joinToString("\n  "),
            tooTight.isEmpty()
        )
    }

    /**
     * The 28 sp headline step must keep the generous 1.4x-1.5x band `Type.kt` documents.
     *
     * It is the largest style, so it is the one most likely to collide with the line above when a
     * Hindi headline wraps — matras sit above the ascender line.
     */
    @Test
    fun headlineStepKeepsGenerousLineHeightForDevanagari() {
        styles.filter { it.second.fontSize.value >= TsTypeScale.HeadlineSp }
            .forEach { (name, style) ->
                val ratio = style.lineHeight.value / style.fontSize.value
                assertTrue(
                    String.format(
                        Locale.ROOT,
                        "%s: 28 sp step must keep >= %.1fx line height for Devanagari, was %.3fx",
                        name, HEADLINE_LINE_HEIGHT_RATIO, ratio
                    ),
                    ratio >= HEADLINE_LINE_HEIGHT_RATIO
                )
            }
    }

    @Test
    fun lineHeightsSitOnTheFourDpGrid() {
        styles.forEach { (name, style) ->
            val sp = style.lineHeight.value
            assertEquals(
                String.format(Locale.ROOT, "%s lineHeight %.1f sp is off the 4 sp grid", name, sp),
                0f, sp % 4f, 0.0001f
            )
            assertTrue(
                "$name lineHeight must exceed its font size (${style.fontSize.value} sp)",
                sp > style.fontSize.value
            )
        }
    }

    // ------------------------------------------------------------------------- font families

    @Test
    fun everyStyleUsesOneSystemFontFamily() {
        styles.forEach { (name, style) ->
            val family = style.fontFamily
            assertTrue(
                "$name must use the system font family (no bundled fonts, no res/ usage)",
                family == null || family == FontFamily.Default
            )
        }
        assertEquals(
            "every style must share the same family so Devanagari falls back consistently",
            1,
            styles.map { it.second.fontFamily }.toSet().size
        )
    }

    private companion object {
        /** Minimum line box height as a multiple of font size (200 % scale, no clipping). */
        const val MIN_LINE_HEIGHT_RATIO: Float = 1.2f

        /** Documented band for the 28 sp headline step. */
        const val HEADLINE_LINE_HEIGHT_RATIO: Float = 1.4f
    }
}
