package ai.trustshield.android.ui.result

import ai.trustshield.android.ui.theme.Contrast
import ai.trustshield.android.ui.theme.RiskTone
import ai.trustshield.android.ui.theme.ToneCopy
import ai.trustshield.android.ui.theme.TsColorSchemes
import androidx.compose.material3.ColorScheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * Regression test for issue #115 — the stray `"Risk score: N/100"` line above the Result header.
 *
 * The defect was not in this package: the navigation host drew a plain `Text(...)` *before* the panel,
 * so a bare debug-looking line sat in the status-bar zone above the header row. There is no Compose
 * UI-test dependency in this project, so the guarantee is asserted on the two surfaces that can be
 * checked on a plain JVM:
 *
 *  1. **nothing the screen can draw** is the stray line — [ResultUiState.visibleStrings] is the flat
 *     list of every string the screen puts on screen, and it is checked for every fixture state in both
 *     languages;
 *  2. **no main-source file draws it either** — the two composables that make up the Result journey are
 *     scanned for a `Risk score` literal on a *code* line (comments and KDoc are allowed to name the
 *     bug they document).
 *
 * The score itself must not disappear from accessibility: [ResultUiState.screenDescription] with a
 * score appends a spoken-only clause, which these tests pin to "present, localised, clamped, and never
 * one of the visible strings".
 */
class ResultScreenStrayTextTest {

    // ------------------------------------------------------------------ helpers

    private fun input(
        source: String,
        coverage: InputCoverage = InputCoverage.READABLE_SUPPORTED,
        riskTone: RiskTone = RiskTone.RED,
        confidence: ConfidenceLevel = ConfidenceLevel.HIGH,
        evidence: List<ResultEvidenceInput> = emptyList()
    ) = ResultInput(
        sourceText = source,
        coverage = coverage,
        riskTone = riskTone,
        confidence = confidence,
        evidence = evidence
    )

    /** The visible-offender shape from the issue: a label, a number and "/100". */
    private val scoreLineShape = Regex("""risk\s*score\s*:?\s*(\d+\s*/\s*100|unable)""", RegexOption.IGNORE_CASE)

    /** Code lines of a Kotlin file: every line that does not start a comment. */
    private fun codeLines(text: String): List<Pair<Int, String>> =
        text.lines().withIndex()
            .filterNot { (_, raw) ->
                val t = raw.trim()
                t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
            }
            .map { (index, raw) -> (index + 1) to raw }

    /** Resolves `app/src/main/java/.../<relative>` from wherever Gradle ran the test. */
    private fun mainSource(relative: String): File? {
        var dir: File = File("").absoluteFile
        repeat(6) {
            val candidate = File(dir, "src/main/java/ai/trustshield/android/$relative")
            if (candidate.isFile) return candidate
            dir = dir.parentFile ?: return null
        }
        return null
    }

    // ------------------------------------------------- 1. nothing drawn is the stray line

    @Test
    fun noFixtureStateCanDrawTheStrayScoreLine() {
        ResultFixtures.all.forEach { state ->
            UiLanguage.entries.forEach { language ->
                state.visibleStrings(language).forEach { text ->
                    assertFalse(
                        "stray score line reachable in ${state.risk.tone}/$language: \"$text\"",
                        RiskScoreSpeech.isStrayScoreLine(text)
                    )
                    assertFalse(
                        "score-line shape reachable in ${state.risk.tone}/$language: \"$text\"",
                        scoreLineShape.containsMatchIn(text)
                    )
                }
            }
        }
    }

    @Test
    fun unableToVerifyNeverDrawsAScoreOrAZeroScore() {
        // A fabricated quote degrades the result to Unable to Verify; the removed host line used to
        // print "Risk score: Unable to Verify", which is exactly the shape this test forbids.
        val state = ResultPresenter.build(
            input(
                source = "Namaste, aapka parcel ready hai.",
                evidence = listOf(
                    ResultEvidenceInput(
                        id = "fake-1",
                        module = "rules/test",
                        titleHinglish = "Sanket",
                        titleEnglish = "Signal",
                        detailHinglish = "Detail",
                        detailEnglish = "Detail",
                        severity = EvidenceSeverity.STRONG,
                        quote = "turant 50000 bhejo warna arrest hoga"
                    )
                )
            )
        )
        assertTrue("fixture must degrade", state.unableToVerify)
        UiLanguage.entries.forEach { language ->
            state.visibleStrings(language).forEach { text ->
                assertFalse("stray line in $language: \"$text\"", RiskScoreSpeech.isStrayScoreLine(text))
                assertFalse("zero score claimed in $language: \"$text\"", scoreLineShape.containsMatchIn(text))
            }
            // The host passes `null` for this state, so the announcement must carry no figure at all.
            val description = state.screenDescription(language, null)
            assertFalse(
                "unverified result must not announce a figure: \"$description\"",
                description.contains("/100") || description.contains("out of 100") ||
                    description.contains("mein se")
            )
        }
    }

    // ------------------------------------------------- 2. the score is still announced

    @Test
    fun talkBackStillHearsTheScoreWhileNothingDrawsIt() {
        val state = ResultPresenter.build(ResultFixtures.digitalArrestInput)
        UiLanguage.entries.forEach { language ->
            val spoken = state.screenDescription(language, 70)
            val risk = state.risk.contentDescription.pick(language)
            val confidence = state.confidence.label.pick(language)

            // Reading order is unchanged: risk first, then confidence, then the score clause.
            assertTrue("announcement must still open with the risk", spoken.startsWith(risk))
            assertTrue(
                "confidence must still follow risk",
                spoken.indexOf(confidence) > spoken.indexOf(risk)
            )
            assertTrue("the score must be announced: \"$spoken\"", spoken.contains("70"))
            assertTrue(
                "the announcement must name the scale: \"$spoken\"",
                spoken.contains("70 out of 100") || spoken.contains("70, 100 mein se") || spoken.contains("70 में से 100")
            )

            // ... and the very same wording must not be one of the strings the screen draws.
            val drawn = state.visibleStrings(language)
            assertFalse(
                "spoken score leaked into visible strings: $drawn",
                drawn.any { it.contains("out of 100") || it.contains("mein se") }
            )
            assertFalse(drawn.any { it == RiskScoreSpeech.announcement(70, language) })
        }
    }

    @Test
    fun aResultWithoutAScoreKeepsTheOldAnnouncementExactly() {
        // ResultScreen's default is `riskScore = null`; previews and any caller that does not know the
        // engine figure must get the pre-fix wording, not an invented number.
        val state = ResultPresenter.build(ResultFixtures.bankCreditInput)
        UiLanguage.entries.forEach { language ->
            val expected = state.risk.contentDescription.pick(language) + ". " +
                state.confidence.label.pick(language) + "."
            assertEquals(expected, state.screenDescription(language))
            assertEquals(expected, state.screenDescription(language, null))
        }
    }

    @Test
    fun spokenScoreIsLocalisedAndClampedToTheHundredPointRange() {
        // Hinglish is the default rendering, English is the option.
        assertEquals(RiskScoreSpeech.announcement(70), RiskScoreSpeech.announcement(70, UiLanguage.HINGLISH))
        assertTrue(RiskScoreSpeech.announcement(70).endsWith("70, 100 mein se"))
        assertTrue(RiskScoreSpeech.announcement(70, UiLanguage.ENGLISH).endsWith("70 out of 100"))

        // A bad engine value can never be read out as "140/100" or "-3/100".
        assertTrue(RiskScoreSpeech.announcement(140, UiLanguage.ENGLISH).contains("100 out of 100"))
        assertTrue(RiskScoreSpeech.announcement(-3, UiLanguage.ENGLISH).contains("0 out of 100"))
    }

    @Test
    fun theStrayLinePredicateMatchesTheReportedBugAndNotTheRealCopy() {
        // The exact shapes issue #115 reports.
        listOf(
            "Risk score: 70/100",
            "Risk score: 10/100",
            "Risk score: Unable to Verify",
            "risk score 42/100"
        ).forEach { bug ->
            assertTrue("predicate must catch \"$bug\"", RiskScoreSpeech.isStrayScoreLine(bug))
            assertTrue("regex must catch \"$bug\"", scoreLineShape.containsMatchIn(bug))
        }

        // Real screen copy is untouched by the predicate.
        val real = ResultPresenter.build(ResultFixtures.digitalArrestInput)
        UiLanguage.entries.forEach { language ->
            real.visibleStrings(language).forEach { text ->
                if (text.isNotBlank()) {
                    assertFalse("false positive on real copy \"$text\"", scoreLineShape.containsMatchIn(text))
                }
            }
        }
        assertFalse(RiskScoreSpeech.isStrayScoreLine(ToneCopy("Zyada khatra", "High risk").english))
    }

    // ------------------------------------------------- 3. the host draws nothing above the panel

    @Test
    fun noResultComposableDrawsTheStrayScoreLiteralInCode() {
        // AppScreens.kt hosted the removed line; ResultScreen.kt is the panel it wrapped.
        listOf("ui/navigation/AppScreens.kt", "ui/result/ResultScreen.kt").forEach { relative ->
            val file = mainSource(relative)
            assumeTrue("$relative is not visible from the unit-test working dir", file != null)
            val offenders = codeLines(file!!.readText()).filter { (_, line) ->
                line.contains("Risk score") || line.contains("risk score")
            }
            assertTrue(
                "$relative still contains a drawable score literal at " +
                    offenders.joinToString { (line, text) -> "line $line: ${text.trim()}" },
                offenders.isEmpty()
            )
        }
    }

    @Test
    fun resultScreenIsCalledWithoutAnExtraTextWrapper() {
        val host = mainSource("ui/navigation/AppScreens.kt")
        assumeTrue("AppScreens.kt is not visible from the unit-test working dir", host != null)
        val lines = codeLines(host!!.readText())
        val start = lines.indexOfFirst { (_, line) -> line.contains("fun ResultRouteHost(") }
        assertTrue("ResultRouteHost must exist", start >= 0)
        // The function body runs from its declaration to the first closing brace at column 0.
        val body = lines.drop(start)
            .takeWhile { (_, line) -> line != "}" }
            .joinToString("\n") { it.second }

        assertTrue("host must still render the panel", body.contains("ResultScreen("))
        // The regression: a Text(...) sibling drawn above ResultScreen inside the host.
        val strayTextBeforePanel = body.substringBefore("ResultScreen(")
        assertFalse(
            "ResultRouteHost draws a Text above the Result panel:\n$strayTextBeforePanel",
            strayTextBeforePanel.contains("Text(")
        )
        // The score still reaches the panel, but as the TalkBack-only parameter.
        assertTrue("host must pass the engine score for the announcement", body.contains("riskScore ="))
        assertTrue(
            "the host mounts exactly one result panel",
            body.split("ResultScreen(").size == 2
        )
    }

    // ------------------------------------------------- 4. no absolute-safety copy sneaks back in

    @Test
    fun theAnnouncementStaysFreeOfAbsoluteSafetyClaims() {
        ResultFixtures.all.forEach { state ->
            UiLanguage.entries.forEach { language ->
                val spoken = state.screenDescription(language, 100)
                assertFalse(
                    "absolute-safety copy in announcement: \"$spoken\"",
                    spoken.lowercase(Locale.ROOT).contains("100% safe")
                )
                assertTrue(
                    "forbidden phrase in announcement: $spoken",
                    AbsoluteSafetyGuard.isClean(spoken)
                )
            }
        }
    }

    // ------------------------------------------------- 5. dark mode and large font scale

    @Test
    fun resultScreenTextPairsStayReadableInDarkMode() {
        // Every text/badge pair the panel draws, in both schemes. The tone containers are already
        // pinned by ResultScreenTest#evidenceTextOnToneContainersMeetsNormalTextContrast; these are
        // the MaterialTheme.colorScheme pairs the header, cards and actions sit on.
        val pairs = listOf(
            "onSurface / surface" to ({ s: ColorScheme -> s.onSurface to s.surface }),
            "onSurface / background" to ({ s: ColorScheme -> s.onSurface to s.background }),
            "onSurfaceVariant / surface" to ({ s: ColorScheme -> s.onSurfaceVariant to s.surface }),
            "onSurfaceVariant / surfaceVariant" to ({ s: ColorScheme -> s.onSurfaceVariant to s.surfaceVariant }),
            "onSecondaryContainer / secondaryContainer" to
                ({ s: ColorScheme -> s.onSecondaryContainer to s.secondaryContainer }),
            "onPrimary / primary" to ({ s: ColorScheme -> s.onPrimary to s.primary })
        )
        listOf(false, true).forEach { dark ->
            val scheme = TsColorSchemes.of(dark)
            pairs.forEach { (label, pick) ->
                val (fg, bg) = pick(scheme)
                val ratio = Contrast.ratio(fg, bg)
                println(String.format(Locale.ROOT, "  %-44s dark=%-5s %.3f:1", label, dark, ratio))
                assertTrue(
                    "$label (dark=$dark) is $ratio:1, below ${Contrast.NORMAL_TEXT_MIN}:1",
                    ratio >= Contrast.NORMAL_TEXT_MIN
                )
            }
        }
    }

    @Test
    fun resultScreenTextHasNoFixedHeightOrLineLimitSoLargeFontScaleWraps() {
        // The 1.3x / 200% acceptance rule is a property of the source: text that may not call
        // maxLines/singleLine/TextOverflow, and no container may pin a height around text.
        val file = mainSource("ui/result/ResultScreen.kt")
        assumeTrue("ResultScreen.kt is not visible from the unit-test working dir", file != null)
        val code = codeLines(file!!.readText())
        listOf("maxLines", "singleLine", "TextOverflow").forEach { forbidden ->
            val hits = code.filter { (_, line) -> line.contains(forbidden) }
            assertTrue(
                "ResultScreen must let text wrap at large font scales, found $forbidden at " +
                    hits.joinToString { (n, t) -> "line $n: ${t.trim()}" },
                hits.isEmpty()
            )
        }
        // `.height(...)` is only allowed on the static four-step band bar, never around text.
        val heights = code.filter { (_, line) -> line.contains(".height(") }
        assertEquals(
            "only the risk band bar may pin a height: " +
                heights.joinToString { (n, t) -> "line $n: ${t.trim()}" },
            1,
            heights.size
        )
        assertTrue(
            "the pinned height must be the band bar's thickness",
            heights.single().second.contains("TsTokens.SpacingXs")
        )
    }
}
