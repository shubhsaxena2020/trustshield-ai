package ai.trustshield.android.ui.theme

import ai.trustshield.android.data.preferences.AppLanguage
import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontFamily
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import kotlin.math.abs

/**
 * C02 token / theme verification.
 *
 * Every contrast claim is *computed* with [Contrast] (WCAG 2.1 relative luminance) instead of being
 * asserted by eye, and the suite runs on a plain JVM (`testDebugUnitTest`) with no device and no
 * Compose UI-test dependency. Verified empirically on this toolchain that
 * `androidx.compose.ui.graphics.Color`, `androidx.compose.ui.unit.Dp`, Material 3 `ColorScheme` and
 * `Typography` all construct normally inside a JVM unit test, so the tokens are tested as shipped.
 */
class ThemeTest {
    @Test
    fun systemCopyFollowsTheCurrentPhoneLocale() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("hi-IN"))
            assertEquals("हिंदी", ToneCopy("Hinglish", "English", "हिंदी").forLanguage(AppLanguage.SYSTEM))
            Locale.setDefault(Locale.forLanguageTag("en-IN"))
            assertEquals("English", ToneCopy("Hinglish", "English", "हिंदी").forLanguage(AppLanguage.SYSTEM))
        } finally {
            Locale.setDefault(original)
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun argb(color: Color): Long = color.toArgb().toLong() and 0xFFFFFFFFL

    private fun hex(color: Color): String = String.format(Locale.ROOT, "#%08X", argb(color))

    /** WCAG 2.1 SC 1.4.3 — normal-size text. */
    private fun assertNormalText(what: String, fg: Color, bg: Color) {
        val r = Contrast.ratio(fg, bg)
        println(String.format(Locale.ROOT, "  %-52s %s on %s = %.3f:1", what, hex(fg), hex(bg), r))
        assertTrue(
            "$what failed: ${hex(fg)} on ${hex(bg)} = $r < ${Contrast.NORMAL_TEXT_MIN}:1",
            r >= Contrast.NORMAL_TEXT_MIN
        )
    }

    /** WCAG 2.1 SC 1.4.11 — icons, borders, focus rings, meter fills. */
    private fun assertNonText(what: String, fg: Color, bg: Color) {
        val r = Contrast.ratio(fg, bg)
        println(String.format(Locale.ROOT, "  %-52s %s on %s = %.3f:1 (non-text)", what, hex(fg), hex(bg), r))
        assertTrue(
            "$what failed: ${hex(fg)} on ${hex(bg)} = $r < ${Contrast.NON_TEXT_MIN}:1",
            r >= Contrast.NON_TEXT_MIN
        )
    }

    private fun assertHex(what: String, expected: Long, actual: Color) {
        assertEquals(
            "$what expected #${String.format(Locale.ROOT, "%08X", expected)} but was ${hex(actual)}",
            expected, argb(actual)
        )
    }

    private fun schemePairs(scheme: ColorScheme): List<Pair<String, Pair<Color, Color>>> = listOf(
        "onSurface / surface" to (scheme.onSurface to scheme.surface),
        "onBackground / background" to (scheme.onBackground to scheme.background),
        "onSurfaceVariant / surface" to (scheme.onSurfaceVariant to scheme.surface),
        "onSurfaceVariant / surfaceVariant" to (scheme.onSurfaceVariant to scheme.surfaceVariant),
        "onPrimary / primary" to (scheme.onPrimary to scheme.primary),
        "onPrimaryContainer / primaryContainer" to (scheme.onPrimaryContainer to scheme.primaryContainer),
        "onSecondary / secondary" to (scheme.onSecondary to scheme.secondary),
        "onSecondaryContainer / secondaryContainer" to (scheme.onSecondaryContainer to scheme.secondaryContainer),
        "onTertiary / tertiary" to (scheme.onTertiary to scheme.tertiary),
        "onError / error" to (scheme.onError to scheme.error),
        "onErrorContainer / errorContainer" to (scheme.onErrorContainer to scheme.errorContainer),
        "inverseOnSurface / inverseSurface" to (scheme.inverseOnSurface to scheme.inverseSurface)
    )

    private fun assertPairwiseDistinct(what: String, colors: List<Color>, minChannelDistance: Int) {
        assertTrue("$what needs at least two values", colors.size >= 2)
        for (i in colors.indices) {
            for (j in i + 1 until colors.size) {
                val a = argb(colors[i])
                val b = argb(colors[j])
                assertNotEquals("$what: entries $i and $j are identical (${hex(colors[i])})", a, b)
                val distance = abs(((a shr 16 and 0xFF) - (b shr 16 and 0xFF)).toInt()) +
                    abs(((a shr 8 and 0xFF) - (b shr 8 and 0xFF)).toInt()) +
                    abs(((a and 0xFF) - (b and 0xFF)).toInt())
                assertTrue(
                    "$what: entries $i (${hex(colors[i])}) and $j (${hex(colors[j])}) differ by only " +
                        "$distance channel steps (need >= $minChannelDistance)",
                    distance >= minChannelDistance
                )
            }
        }
    }

    // ------------------------------------------------ 1. exact spec values

    @Test
    fun brandAndRiskHuesAreExactlyTheSpecValues() {
        assertHex("monochrome Accent", 0xFF000000L, TsTokens.Accent)
        assertHex("RiskGreen", 0xFF16845BL, TsTokens.RiskGreen)
        assertHex("RiskAmber", 0xFFB86B00L, TsTokens.RiskAmber)
        assertHex("RiskRed", 0xFFC73642L, TsTokens.RiskRed)

        // Interface accents are monochrome; only the risk tones retain distinct hues.
        assertPairwiseDistinct(
            "light risk hues",
            listOf(TsTokens.RiskGreen, TsTokens.RiskAmber, TsTokens.RiskRed),
            minChannelDistance = 60
        )
        assertPairwiseDistinct(
            "dark risk hues",
            listOf(TsTokens.RiskGreenDark, TsTokens.RiskAmberDark, TsTokens.RiskRedDark),
            minChannelDistance = 60
        )
    }

    @Test
    fun darkCounterpartsAreLighterAndClearNormalTextOnDarkSurface() {
        listOf(
            "RiskGreen" to (TsTokens.RiskGreen to TsTokens.RiskGreenDark),
            "RiskAmber" to (TsTokens.RiskAmber to TsTokens.RiskAmberDark),
            "RiskRed" to (TsTokens.RiskRed to TsTokens.RiskRedDark)
        ).forEach { (name, pair) ->
            val lightLum = Contrast.relativeLuminanceArgb(pair.first.toArgb())
            val darkLum = Contrast.relativeLuminanceArgb(pair.second.toArgb())
            assertTrue("$name dark counterpart must be lighter than its light-mode hue", darkLum > lightLum)
            assertNormalText("$name dark counterpart on SurfaceDark", pair.second, TsPalette.SurfaceDark)
        }
    }

    // ------------------------------------------------ 2. contrast in BOTH themes

    @Test
    fun everyNormalTextPairPassesInLightTheme() {
        println("LIGHT normal-text contrast (WCAG 2.1, must be >= 4.5:1)")
        schemePairs(TsColorSchemes.Light).forEach { (what, pair) ->
            assertNormalText(what, pair.first, pair.second)
        }
        RiskTone.entries.forEach { tone ->
            assertNormalText(
                "RiskTone.$tone onContainer / container",
                tone.onContainer(false), tone.container(false)
            )
            assertNormalText(
                "RiskTone.$tone onSurface / surface",
                tone.onSurface(false), TsPalette.SurfaceLight
            )
            // The container itself sits on the screen surface and carries its label.
            assertNormalText(
                "RiskTone.$tone onContainer / surface",
                tone.onContainer(false), TsPalette.SurfaceLight
            )
        }
    }

    @Test
    fun everyNormalTextPairPassesInDarkTheme() {
        println("DARK normal-text contrast (WCAG 2.1, must be >= 4.5:1)")
        schemePairs(TsColorSchemes.Dark).forEach { (what, pair) ->
            assertNormalText(what, pair.first, pair.second)
        }
        RiskTone.entries.forEach { tone ->
            assertNormalText(
                "RiskTone.$tone onContainer / container (dark)",
                tone.onContainer(true), tone.container(true)
            )
            assertNormalText(
                "RiskTone.$tone onSurface / surface (dark)",
                tone.onSurface(true), TsPalette.SurfaceDark
            )
            assertNormalText(
                "RiskTone.$tone onContainer / surface (dark)",
                tone.onContainer(true), TsPalette.SurfaceDark
            )
        }
    }

    @Test
    fun offlineSpeechBannerUsesReadableDarkThemeText() {
        val scheme = TsColorSchemes.Dark
        assertNormalText(
            "offline speech banner onSurfaceVariant / surface (dark)",
            scheme.onSurfaceVariant,
            scheme.surface
        )
    }

    @Test
    fun nonTextGraphicsClearThreeToOneInBothThemes() {
        // Brand/risk hues drive icons, left rules and meter fills -> 3:1, not 4.5:1.
        assertNonText("Accent / SurfaceLight", TsTokens.Accent, TsPalette.SurfaceLight)
        RiskTone.entries.forEach {
            assertNonText("RiskTone.$it accent / SurfaceLight", it.accent(false), TsPalette.SurfaceLight)
            assertNonText("RiskTone.$it accent / SurfaceDark", it.accent(true), TsPalette.SurfaceDark)
        }
        assertNonText("outline / SurfaceLight", TsColorSchemes.Light.outline, TsPalette.SurfaceLight)
        assertNonText("outline / SurfaceDark", TsColorSchemes.Dark.outline, TsPalette.SurfaceDark)
    }

    /**
     * Monochrome controls and risk colors retain safe text contrast on the white theme.
     */
    @Test
    fun specHexesThatMissNormalTextOnLightAreDocumentedAndHaveSafeAlternatives() {
        val accentOnLight = Contrast.ratio(TsTokens.Accent, TsPalette.SurfaceLight)
        val amberOnLight = Contrast.ratio(TsTokens.RiskAmber, TsPalette.SurfaceLight)
        println(
            String.format(
                Locale.ROOT,
                "  measured on SurfaceLight: Accent %.4f:1, RiskAmber %.4f:1", accentOnLight, amberOnLight
            )
        )

        assertTrue("Monochrome accent must meet normal-text contrast", accentOnLight >= Contrast.NORMAL_TEXT_MIN)
        assertTrue(
            "RiskAmber should sit under 4.5:1 on the light surface; measured $amberOnLight",
            amberOnLight < Contrast.NORMAL_TEXT_MIN && amberOnLight >= Contrast.NON_TEXT_MIN
        )

        // ...and the alternatives must actually clear 4.5:1, not merely claim to.
        assertNormalText("AccentTextLight / SurfaceLight", TsTokens.AccentTextLight, TsPalette.SurfaceLight)
        assertNormalText("white label / AccentFillLight", TsPalette.OnAccentFillLight, TsPalette.AccentFillLight)
        assertNormalText("RiskAmberTextLight / SurfaceLight", TsTokens.RiskAmberTextLight, TsPalette.SurfaceLight)
        assertNormalText("RiskGreenTextLight / SurfaceLight", TsTokens.RiskGreenTextLight, TsPalette.SurfaceLight)
        assertNormalText("RiskRedTextLight / SurfaceLight", TsTokens.RiskRedTextLight, TsPalette.SurfaceLight)
    }

    // ------------------------------------------------ 3. contrast maths correctness

    @Test
    fun contrastMathsMatchesWcagReferenceValues() {
        val white = 0xFFFFFFFF.toInt()
        val black = 0xFF000000.toInt()

        // WCAG 2.1 reference points: black/white = 21:1, a colour against itself = 1:1.
        assertEquals(21.0, Contrast.ratioArgb(black, white), 0.01)
        assertEquals(1.0, Contrast.ratioArgb(white, white), 0.0001)
        assertEquals(Contrast.ratioArgb(black, white), Contrast.ratioArgb(white, black), 1e-12)

        // Relative luminance of the primaries (sRGB coefficients).
        assertEquals(0.2126, Contrast.relativeLuminanceArgb(0xFFFF0000.toInt()), 1e-4)
        assertEquals(0.7152, Contrast.relativeLuminanceArgb(0xFF00FF00.toInt()), 1e-4)
        assertEquals(0.0722, Contrast.relativeLuminanceArgb(0xFF0000FF.toInt()), 1e-4)

        // The sRGB linearisation knee: 10/255 is below 0.03928 (divide), 11/255 is above (power).
        assertEquals((10 / 255.0) / 12.92, Contrast.linearChannel(10), 1e-12)
        assertTrue(
            "channel 11 must be in the power branch",
            Contrast.linearChannel(11) > (11 / 255.0) / 12.92
        )

        // ratio(Color, Color) must agree with the ARGB path it delegates to.
        assertEquals(
            Contrast.ratioArgb(TsTokens.RiskRed.toArgb(), TsPalette.SurfaceLight.toArgb()),
            Contrast.ratio(TsTokens.RiskRed, TsPalette.SurfaceLight),
            1e-12
        )
    }

    // ------------------------------------------------ 4. risk tones distinct + never colour-only

    @Test
    fun riskTonesAreMutuallyDistinctInBothThemes() {
        assertPairwiseDistinct("light containers", RiskTone.entries.map { it.container(false) }, 8)
        assertPairwiseDistinct("light onContainers", RiskTone.entries.map { it.onContainer(false) }, 8)
        assertPairwiseDistinct("dark containers", RiskTone.entries.map { it.container(true) }, 8)
        assertPairwiseDistinct("dark onContainers", RiskTone.entries.map { it.onContainer(true) }, 8)
        assertPairwiseDistinct("light accents", RiskTone.entries.map { it.accent(false) }, 60)

        // Each tone must actually change between themes, otherwise dark mode is untested.
        RiskTone.entries.forEach {
            assertNotEquals(
                "RiskTone.$it container is identical in both themes",
                it.container(false), it.container(true)
            )
            assertNotEquals(
                "RiskTone.$it onContainer is identical in both themes",
                it.onContainer(false), it.onContainer(true)
            )
        }
    }

    @Test
    fun riskToneIsNeverTheSoleDifferentiator() {
        // Every tone carries text + icon + content description; colour is supplementary only.
        RiskTone.entries.forEach { tone ->
            val p = RiskToneCopy.of(tone)
            assertEquals("presentation/tone mismatch for $tone", tone, p.tone)
            assertTrue("$tone needs a non-blank Hinglish label", p.label.hinglish.isNotBlank())
            assertTrue("$tone needs a non-blank English label", p.label.english.isNotBlank())
            assertTrue("$tone needs a Material icon name", p.iconName.isNotBlank())
            assertTrue("$tone needs a glyph fallback", p.glyph.isNotBlank())
            assertTrue("$tone needs a Hinglish content description", p.contentDescription.hinglish.isNotBlank())
            assertTrue("$tone needs an English content description", p.contentDescription.english.isNotBlank())
            assertTrue("$tone is not self-describing without colour", RiskToneCopy.isSelfDescribing(tone))
        }
        // Visible text alone must distinguish tones -> dropping colour loses no information.
        assertEquals(
            "Hinglish labels must be unique across tones",
            RiskTone.entries.size, RiskTone.entries.map { RiskToneCopy.of(it).label.hinglish }.toSet().size
        )
        assertEquals(
            "English labels must be unique across tones",
            RiskTone.entries.size, RiskTone.entries.map { RiskToneCopy.of(it).label.english }.toSet().size
        )
        assertEquals(
            "icon names must be unique across tones",
            RiskTone.entries.size, RiskTone.entries.map { RiskToneCopy.of(it).iconName }.toSet().size
        )
        assertEquals(
            "glyphs must be unique across tones",
            RiskTone.entries.size, RiskTone.entries.map { RiskToneCopy.of(it).glyph }.toSet().size
        )

        // The contract screens must honour.
        assertTrue(RiskToneCopy.REQUIRES_LABEL_TEXT)
        assertTrue(RiskToneCopy.REQUIRES_ICON)
        assertTrue(RiskToneCopy.REQUIRES_CONTENT_DESCRIPTION)
        assertFalse("colour must never be the sole differentiator", RiskToneCopy.COLOUR_IS_SOLE_DIFFERENTIATOR)

        // Tone is presentation only: no verdict-shaped values may creep into the enum.
        assertEquals(
            "RiskTone must hold presentation tones only",
            setOf("NEUTRAL", "GREEN", "AMBER", "RED"),
            RiskTone.entries.map { it.name }.toSet()
        )
    }

    // ------------------------------------------------ 5. spacing / shape / touch target

    @Test
    fun spacingScaleSitsOnTheFourDpGridAndIsMonotonic() {
        val scale = TsTokens.SpacingScale
        assertEquals("spacing scale must be 4/8/12/16/24", listOf(4f, 8f, 12f, 16f, 24f), scale.map { it.value })
        scale.forEach { dp ->
            assertEquals("${dp.value} dp is off the 4 dp grid", 0f, dp.value % 4f, 0.0001f)
            assertTrue("spacing must be positive", dp.value > 0f)
        }
        scale.zipWithNext().forEach { (a, b) ->
            assertTrue("spacing scale must increase: ${a.value} -> ${b.value}", b.value > a.value)
        }
        // 8 dp is the layout grid; 4 and 12 are optical insets that still nest on it.
        assertEquals(8f, TsTokens.SpacingSm.value, 0f)
        assertEquals(16f, TsTokens.SpacingLg.value, 0f)
        assertEquals(24f, TsTokens.SpacingXl.value, 0f)
        assertEquals(TsTokens.SpacingXs + TsTokens.SpacingXs, TsTokens.SpacingSm)
        assertEquals(TsTokens.SpacingMd + TsTokens.SpacingXs, TsTokens.SpacingLg)
    }

    @Test
    fun shapeAndTouchTargetMeetSpec() {
        assertEquals(12f, TsTokens.CornerSmall.value, 0f)
        assertEquals(20f, TsTokens.CornerLarge.value, 0f)
        assertTrue(
            "corners must stay inside the 12-20 dp band",
            TsTokens.CornerSmall.value >= 12f && TsTokens.CornerLarge.value <= 20f
        )
        assertTrue(
            "CornerSmall must be smaller than CornerLarge",
            TsTokens.CornerSmall.value < TsTokens.CornerLarge.value
        )
        assertTrue(
            "touch target must be >= 48 dp, was ${TsTokens.MinTouchTarget.value}",
            TsTokens.MinTouchTarget.value >= 48f
        )
        assertEquals("touch target must sit on the 4 dp grid", 0f, TsTokens.MinTouchTarget.value % 4f, 0.0001f)
        assertTrue(TsTokens.MinTouchTarget.value >= TsTokens.SpacingXl.value)
    }

    // ------------------------------------------------ 6. motion band + reduced motion

    @Test
    fun motionDurationsStayInsideThe180To240Band() {
        listOf("MotionShortMs" to TsTokens.MotionShortMs, "MotionMediumMs" to TsTokens.MotionMediumMs)
            .forEach { (name, ms) ->
                assertTrue("$name=$ms is outside ${TsTokens.MotionBandMs}", ms in TsTokens.MotionBandMs)
            }
        assertEquals(180, TsTokens.MotionShortMs)
        assertEquals(240, TsTokens.MotionMediumMs)
        assertTrue("short must be shorter than medium", TsTokens.MotionShortMs < TsTokens.MotionMediumMs)
        assertEquals(180..240, TsTokens.MotionBandMs)
    }

    @Test
    fun reducedMotionRemovesTransitionsInsteadOfShorteningThem() {
        assertEquals(0, TsMotion.duration(TsTokens.MotionShortMs, reducedMotion = true))
        assertEquals(0, TsMotion.duration(TsTokens.MotionMediumMs, reducedMotion = true))
        assertEquals(TsTokens.MotionShortMs, TsMotion.duration(TsTokens.MotionShortMs, reducedMotion = false))
        assertEquals(TsTokens.MotionMediumMs, TsMotion.duration(TsTokens.MotionMediumMs, reducedMotion = false))
        assertFalse("critical alerts must never loop", TsMotion.CRITICAL_ALERT_REPEATS)
    }

    // ------------------------------------------------ 7. theme wiring

    @Test
    fun themeBuildsBothSchemesFromTokensAndKeepsDynamicColourOff() {
        val light = TsColorSchemes.Light
        val dark = TsColorSchemes.Dark

        assertEquals(TsPalette.SurfaceLight, light.surface)
        assertEquals(TsPalette.OnSurfaceLight, light.onSurface)
        assertEquals(TsPalette.AccentFillLight, light.primary)
        assertEquals(TsTokens.RiskRed, light.error)

        assertEquals(TsPalette.SurfaceDark, dark.surface)
        assertEquals(TsPalette.OnSurfaceDark, dark.onSurface)
        assertEquals(TsTokens.AccentDark, dark.primary)
        assertEquals(TsTokens.RiskRedDark, dark.error)

        // Dynamic colour would repaint primary/surface from the wallpaper; pinning the scheme to our
        // own tokens is what proves it is not in play.
        assertNotEquals(light.primary, dark.primary)
        assertNotEquals(light.surface, dark.surface)
        assertNotEquals(light.error, dark.error)
        assertEquals(TsColorSchemes.Light, TsColorSchemes.of(dark = false))
        assertEquals(TsColorSchemes.Dark, TsColorSchemes.of(dark = true))

        // Risk colours reach screens through the scheme's error slot as well as through RiskTone.
        assertNormalText("RiskRed (error) / SurfaceLight", TsTokens.RiskRed, TsPalette.SurfaceLight)
        assertNormalText("RiskRedDark (error) / SurfaceDark", TsTokens.RiskRedDark, TsPalette.SurfaceDark)
    }

    @Test
    fun typographyUsesTheFourteenSixteenTwentyTwentyEightScale() {
        assertEquals(listOf(14f, 16f, 20f, 28f), TsTypeScale.AllowedSp)
        TsTypeScale.AllowedSp.zipWithNext().forEach { (a, b) ->
            assertTrue("type scale must increase: $a -> $b", b > a)
        }

        val typography = TsTypography
        val used = listOf(
            typography.displayLarge, typography.displayMedium, typography.displaySmall,
            typography.headlineLarge, typography.headlineMedium, typography.headlineSmall,
            typography.titleLarge, typography.titleMedium, typography.titleSmall,
            typography.bodyLarge, typography.bodyMedium, typography.bodySmall,
            typography.labelLarge, typography.labelMedium, typography.labelSmall
        ).map { it.fontSize.value }
        used.forEach { sp ->
            assertTrue("$sp sp is outside the 14/16/20/28 scale", TsTypeScale.isAllowed(sp))
        }
        // Body 16 / support 14 / headline 28, per the spec reading order.
        assertEquals(16f, typography.bodyLarge.fontSize.value, 0f)
        assertEquals(14f, typography.bodyMedium.fontSize.value, 0f)
        assertEquals(28f, typography.headlineLarge.fontSize.value, 0f)

        // System/default family only — C02 bundles no font files and creates no res/ resources.
        assertTrue(
            "body text must use the system font family",
            typography.bodyLarge.fontFamily == null || typography.bodyLarge.fontFamily == FontFamily.Default
        )
    }
}
