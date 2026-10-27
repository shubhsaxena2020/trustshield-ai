package ai.trustshield.android.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * WCAG 2.1 contrast proof for the shipped light and dark schemes.
 *
 * Runs on a plain JVM: JUnit 4 + Compose `Color` value-class maths only — no Robolectric, no
 * Compose UI-test dependency, no device. The pairs come from [TsContrastPairs], which reads the real
 * [TsColorSchemes] and [RiskTone] colours, so this asserts what a screen actually paints rather than
 * a hand-copied hex list.
 *
 * Failure messages carry the pair name, both hexes and the measured ratio, e.g.
 * `light: onPrimary / primary: #FFFFFFFF on #FF2B62EE = 4.31:1 < 4.5:1`.
 *
 * Thresholds are the WCAG minimums and are **not** relaxed here to make a pair pass: a real failure
 * must surface to the lane lead as a defect.
 */
class ThemeContrastTest {

    private fun hex(color: Color): String = String.format(Locale.ROOT, "#%08X", color.toArgb())

    private fun pairsFor(mode: String, band: ContrastBand): List<ContrastPair> =
        TsContrastPairs.all().filter { it.name.startsWith("$mode: ") && it.band == band }

    /**
     * Asserts every pair of [band] in [mode] clears its minimum, printing the measured table and
     * reporting *all* failures at once instead of stopping at the first one.
     */
    private fun assertBandClears(mode: String, band: ContrastBand) {
        val pairs = pairsFor(mode, band)
        assertTrue(
            "the audit table has no ${band.name} pairs for $mode — the proof would be vacuous",
            pairs.isNotEmpty()
        )

        println("$mode ${band.name} (min ${band.minimumRatio}:1)")
        pairs.forEach { println("  ${it.summary}  [${hex(it.foreground)} on ${hex(it.background)}]") }

        val failures = pairs.filterNot { it.passes }.map { it.summary }
        assertTrue(
            buildString {
                append("$mode: ${failures.size} of ${pairs.size} ${band.name} pair(s) below ")
                append("${band.minimumRatio}:1\n")
                failures.forEach { append("  ").append(it).append('\n') }
            },
            failures.isEmpty()
        )
    }

    // ------------------------------------------------------- normal text (14-16 sp), both modes

    @Test
    fun everyLightNormalTextPairClearsFourPointFiveToOne() {
        assertBandClears("light", ContrastBand.NORMAL_TEXT)
    }

    @Test
    fun everyDarkNormalTextPairClearsFourPointFiveToOne() {
        assertBandClears("dark", ContrastBand.NORMAL_TEXT)
    }

    // ---------------------------------------------------------------- large text (20/28 sp)

    @Test
    fun largeTextPairsClearThreeToOneInBothModes() {
        assertBandClears("light", ContrastBand.LARGE_TEXT)
        assertBandClears("dark", ContrastBand.LARGE_TEXT)
    }

    /**
     * The 3:1 large-text exemption from SC 1.4.3 must not be carrying any pair.
     *
     * 20 sp semibold is 15 pt, so whether it counts as "large" is arguable; 28 sp is unambiguously
     * large. Rather than argue, every pair filed under [ContrastBand.LARGE_TEXT] is additionally
     * held to the stricter 4.5:1 normal-text bar — which it already clears.
     */
    @Test
    fun largeTextBandIsNotLoadBearing() {
        val weak = TsContrastPairs.byBand(ContrastBand.LARGE_TEXT)
            .filter { it.ratio < TsContrast.NORMAL_TEXT_MIN }
            .map { it.summary }
        assertTrue(
            buildString {
                append("large-text pairs that only pass thanks to the 3:1 exemption:\n")
                weak.forEach { append("  ").append(it).append('\n') }
            },
            weak.isEmpty()
        )
    }

    // ------------------------------------------------------------- non-text graphics (3:1)

    @Test
    fun meaningfulGraphicsClearThreeToOneInBothModes() {
        assertBandClears("light", ContrastBand.NON_TEXT)
        assertBandClears("dark", ContrastBand.NON_TEXT)
    }

    // ---------------------------------------------------------------- table shape / coverage

    /**
     * Light and dark must cover the same pairs. A pair added to one scheme only would otherwise be
     * silently untested in the other mode.
     */
    @Test
    fun lightAndDarkTablesCoverTheSamePairs() {
        val light = TsContrastPairs.light.map { it.name.removePrefix("light: ") }
        val dark = TsContrastPairs.dark.map { it.name.removePrefix("dark: ") }
        assertEquals("light and dark audit tables must cover the same pairs", light, dark)
    }

    @Test
    fun pairNamesAreUniqueAndNeverPaintTextOnItself() {
        val all = TsContrastPairs.all()
        assertEquals("pair names must be unique", all.size, all.map { it.name }.toSet().size)
        all.forEach { pair ->
            assertTrue(
                "${pair.name}: foreground and background are the same colour (${hex(pair.foreground)})",
                pair.foreground.toArgb() != pair.background.toArgb()
            )
            assertTrue("${pair.name}: a ratio below 1.0 is impossible", pair.ratio >= 1.0)
            assertTrue("${pair.name}: contrast ratios cannot exceed 21:1", pair.ratio <= 21.0 + 1e-9)
        }
    }

    // ------------------------------------------------------------------------ maths correctness

    @Test
    fun mathsMatchesWcagReferenceValues() {
        assertEquals("black on white is 21:1", 21.0, TsContrast.ratio(Color.Black, Color.White), 0.01)
        assertEquals("a colour on itself is 1:1", 1.0, TsContrast.ratio(Color.White, Color.White), 1e-9)
        assertEquals(
            "ratio must be symmetric",
            TsContrast.ratio(Color.Black, Color.White),
            TsContrast.ratio(Color.White, Color.Black),
            1e-12
        )

        // sRGB coefficients from WCAG 2.1 for the primaries.
        assertEquals("red channel coefficient", 0.2126, TsContrast.relativeLuminance(Color.Red), 1e-4)
        assertEquals("green channel coefficient", 0.7152, TsContrast.relativeLuminance(Color.Green), 1e-4)
        assertEquals("blue channel coefficient", 0.0722, TsContrast.relativeLuminance(Color.Blue), 1e-4)

        // Linearisation knee: 10/255 is below 0.03928 (divide), 11/255 is above (power).
        assertEquals((10f / 255f).toDouble() / 12.92, TsContrast.linearize(10f / 255f), 1e-12)
        assertTrue(
            "channel 11 must take the power branch",
            TsContrast.linearize(11f / 255f) > (11f / 255f).toDouble() / 12.92
        )
    }

    /** [TsContrast] (Color-first) and `Color.kt`'s ARGB [Contrast] must agree on every pair. */
    @Test
    fun mathsAgreesWithTheArgbContrastObject() {
        TsContrastPairs.all().forEach { pair ->
            assertEquals(
                "${pair.name}: Color-first maths disagrees with Contrast.ratioArgb",
                Contrast.ratio(pair.foreground, pair.background),
                pair.ratio,
                1e-4
            )
        }
    }
}
