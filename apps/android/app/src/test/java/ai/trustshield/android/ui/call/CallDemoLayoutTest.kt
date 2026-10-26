package ai.trustshield.android.ui.call

import ai.trustshield.android.core.model.Evidence
import ai.trustshield.android.ui.navigation.CallDemoLayout
import ai.trustshield.android.ui.navigation.DemoControl
import ai.trustshield.android.ui.navigation.DemoSection
import ai.trustshield.android.ui.theme.RiskTone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Gate for issue #119 defect B (demo-mode layout) and the language-mixing nit.
 *
 * Plain JUnit4 on the JVM: `CallDemoLayout` is the pure model `CallDemoScreen` renders from (it has
 * no `android.*` and no `androidx.compose.*` import), so the acceptance criteria are asserted here
 * without a device, an emulator or a Compose UI-test dependency:
 *
 *  1. demo mode offers exactly Back / Next line / Reset -- no disabled Stop, no session control;
 *  2. the meter is section 0 and reads score / 4-position band / label / confidence separately;
 *  3. every lit chip carries the engine's exact quoted line, and no quote is ever composed here;
 *  4. the CRITICAL alert is report section 6's text verbatim and exists only at CRITICAL;
 *  5. reset returns to the LOW pre-start state, and Back re-derives through the engine;
 *  6. the header and every control label exist in one language per Settings toggle.
 *
 * The ladder numbers come from the deterministic A05 engine (`CallDemoRunner` -> `DemoProgress`),
 * never from this test and never from the UI.
 */
class CallDemoLayoutTest {

    /** Progress after each script line: index 0 is pre-start, 1..4 are the four rungs. */
    private fun ladder(): List<DemoProgress> {
        val runner = CallDemoRunner()
        val trail = mutableListOf(runner.progress())
        repeat(CallDemoScript.size) { trail += runner.advance() }
        return trail
    }

    private fun stepProgress(): List<DemoProgress> = ladder().drop(1)

    // ---- 1. control set -------------------------------------------------------------------------

    @Test
    fun demoModeOffersExactlyBackNextResetAndNoDeadStopOrSessionControl() {
        // Pinned: the demo screen's control vocabulary, so a dead Stop bar cannot come back.
        assertEquals(
            listOf("BACK", "NEXT", "RESET"),
            DemoControl.entries.map { it.name }
        )

        val forbiddenLiveLabels = listOf(
            CallShieldCopy.stop.forLanguage(CallLanguage.HINGLISH),
            CallShieldCopy.stop.forLanguage(CallLanguage.ENGLISH),
            CallShieldCopy.start.forLanguage(CallLanguage.HINGLISH),
            CallShieldCopy.start.forLanguage(CallLanguage.ENGLISH),
            CallShieldCopy.pause.forLanguage(CallLanguage.HINGLISH),
            CallShieldCopy.resume.forLanguage(CallLanguage.ENGLISH),
        )

        // Every state the screen can be in: pre-start, each rung, finished, after reset.
        val states = ladder() + ladder().last() + ladder().first()
        for (progress in states) {
            for (language in CallLanguage.entries) {
                val controls = CallDemoLayout.controls(progress, language)
                assertEquals(
                    "the demo row is exactly three controls, in this order",
                    listOf(DemoControl.BACK, DemoControl.NEXT, DemoControl.RESET),
                    controls.map { it.control }
                )
                controls.forEach { spec ->
                    assertTrue("a control label must never be blank", spec.label.isNotBlank())
                    assertFalse(
                        "demo mode must not render a live-session control: ${spec.label}",
                        forbiddenLiveLabels.contains(spec.label)
                    )
                }

                // Pre-start: nothing to go back to and nothing to reset yet; Next is the way in.
                if (progress.steps.isEmpty()) {
                    assertFalse(controls.first { it.control == DemoControl.BACK }.enabled)
                    assertTrue(controls.first { it.control == DemoControl.NEXT }.enabled)
                    assertFalse(controls.first { it.control == DemoControl.RESET }.enabled)
                } else {
                    assertTrue(controls.first { it.control == DemoControl.BACK }.enabled)
                    assertTrue(controls.first { it.control == DemoControl.RESET }.enabled)
                }

                // Finished: Next cannot act, and it says so instead of going dead silently.
                if (progress.finished) {
                    val next = controls.first { it.control == DemoControl.NEXT }
                    assertFalse(next.enabled)
                    assertEquals(CallDemoLayout.scriptFinished.forLanguage(language), next.label)
                }
            }
        }
    }

    @Test
    fun touchTargetsStayAtLeast48Dp() {
        assertTrue(
            "the compact row must keep a >= 48 dp touch target",
            CallDemoLayout.MIN_TOUCH_TARGET_DP >= 48
        )
    }

    // ---- 2. meter first, and what it shows -------------------------------------------------------

    @Test
    fun theMeterIsTheFirstSectionOfTheDemoScreen() {
        val sections = CallDemoLayout.sections()
        assertEquals("the meter must be the top section", DemoSection.METER, sections.first())
        assertEquals("no section may repeat", sections.distinct(), sections)
        assertEquals(4, sections.size)
    }

    @Test
    fun meterReadsTheEngineLadderNotAComputedNumber() {
        val steps = stepProgress().map { it.current!! }

        val expected = listOf(
            Triple(10, DemoRiskPosition.LOW, "Kam"),
            Triple(44, DemoRiskPosition.MEDIUM, "Dhyan dein"),
            Triple(52, DemoRiskPosition.HIGH, "Zyada khatra"),
            Triple(75, DemoRiskPosition.CRITICAL, "Turant rukein"),
        )
        assertEquals("the script has four rungs", expected.size, steps.size)

        steps.forEachIndexed { index, step ->
            val (score, position, label) = expected[index]
            assertEquals("score is A05's, verbatim", score, step.score)
            assertEquals("position comes from positionFor()", position, step.position)

            val reading = CallDemoLayout.meter(step, stepProgress()[index], CallLanguage.HINGLISH)
            assertEquals(score.toString(), reading.scoreText)
            assertEquals("/100", reading.scoreOutOf)
            assertEquals(label, reading.positionLabel)
            assertEquals("one filled segment per position", index + 1, reading.bandFilled)
            assertFalse(reading.isPreStart)
            assertEquals(CallDemoLayout.stepCounter(index), reading.stepCounter)
            assertNotNull("the card carries a TalkBack description", reading.contentDescription)
            assertTrue(
                "the TalkBack description names the level and the score",
                reading.contentDescription.contains(label) && reading.contentDescription.contains("$score/100")
            )
        }

        // Tone map: LOW green, MEDIUM amber, HIGH and CRITICAL red (never green above LOW).
        assertEquals(RiskTone.GREEN, CallDemoLayout.toneOf(DemoRiskPosition.LOW))
        assertEquals(RiskTone.AMBER, CallDemoLayout.toneOf(DemoRiskPosition.MEDIUM))
        assertEquals(RiskTone.RED, CallDemoLayout.toneOf(DemoRiskPosition.HIGH))
        assertEquals(RiskTone.RED, CallDemoLayout.toneOf(DemoRiskPosition.CRITICAL))
    }

    @Test
    fun confidenceIsShownAsASeparateReadingFromTheScore() {
        val progress = ladder().last()
        val reading = CallDemoLayout.meter(progress.current, progress, CallLanguage.HINGLISH)

        assertTrue(
            "confidence lives in its own line, not inside the score: ${reading.confidenceText}",
            reading.confidenceText.contains(CallShieldCopy.confidenceHeading.hinglish)
        )
        assertTrue(reading.confidenceText.contains("Achhi")) // HIGH coverage, A05's reading
        assertFalse("the confidence line must not restate the score", reading.confidenceText.contains("/100"))
        assertEquals("75", reading.scoreText)
    }

    @Test
    fun beforeTheFirstLineTheMeterShowsNoReadingRatherThanZero() {
        val start = ladder().first()
        val reading = CallDemoLayout.meter(start.current, start, CallLanguage.HINGLISH)

        assertTrue(reading.isPreStart)
        assertNull("an empty demo is not 0/100", reading.scoreText)
        assertEquals("no segment is filled", 0, reading.bandFilled)
        assertEquals(DemoRiskPosition.LOW, reading.position)
        assertEquals(CallDemoLayout.preStartPosition.hinglish, reading.positionLabel)
        assertNull(reading.stepCounter)
    }

    // ---- 3. stage chips -------------------------------------------------------------------------

    @Test
    fun exactlyFiveChipsInPlaybookOrderWithOnlyLitChipsCarryingAQuote() {
        val trail = ladder()
        for (progress in trail) {
            val chips = CallDemoLayout.chips(progress.current, CallLanguage.HINGLISH)
            assertEquals(5, chips.size)
            assertEquals(CallStage.ordered, chips.map { it.stage })

            if (progress.current == null) {
                assertTrue("nothing is lit before the first line", chips.none { it.lit })
                assertTrue(chips.all { it.quoteText == null && it.note == null })
            }
        }

        // Lit chips carry a quote; unlit chips carry none and stay quiet.
        for (progress in stepProgress()) {
            val chips = CallDemoLayout.chips(progress.current, CallLanguage.HINGLISH)
            for (chip in chips) {
                if (chip.lit) {
                    assertNotNull("lit chip ${chip.stage} must show its quoted line", chip.quoteText)
                    assertNull("a lit chip with a live quote needs no excusing note", chip.note)
                    assertFalse(chip.litWithoutLiveQuote)
                    assertTrue("a chip label must never be blank", chip.label.isNotBlank())
                    assertEquals(CallShieldCopy.stageFound.hinglish, chip.statusText)
                } else {
                    assertNull("unlit chip ${chip.stage} must never carry a quote", chip.quoteText)
                    assertEquals(CallShieldCopy.stageNotFound.hinglish, chip.statusText)
                }
            }
        }
    }

    @Test
    fun everyLitChipQuoteIsTheEngineLineVerifiedInTheTranscript() {
        val runner = CallDemoRunner()
        val trail = mutableListOf(runner.progress())
        repeat(CallDemoScript.size) { trail += runner.advance() }
        val transcript = runner.transcript()

        var checked = 0
        for (progress in trail.drop(1)) {
            for (chip in CallDemoLayout.chips(progress.current, CallLanguage.HINGLISH)) {
                val quote = chip.quoteText ?: continue
                assertNotNull(
                    "chip quote is not in the transcript: $quote",
                    Evidence.locate(transcript, quote)
                )
                checked += 1
            }
        }
        assertTrue("the ladder must surface chip quotes", checked >= 4)
    }

    @Test
    fun theAuthorityChipShowsTheAuthorityLineAndNothingInvented() {
        val firstRung = stepProgress().first()
        val chips = CallDemoLayout.chips(firstRung.current, CallLanguage.HINGLISH)
        val authority = chips.first { it.stage == CallStage.AUTHORITY }

        assertTrue(authority.lit)
        assertNotNull(authority.quoteText)
        assertTrue(
            "the authority chip must quote the authority line: ${authority.quoteText}",
            CallDemoScript.LINE_1_AUTHORITY.contains(authority.quoteText!!)
        )
        // Chips the engine did not light at this rung stay empty rather than borrowing a quote.
        chips.filter { !it.lit }.forEach { assertNull(it.quoteText) }
    }

    @Test
    fun allFourStagesAreLitAtCritical() {
        val critical = ladder().last()
        val lit = CallDemoLayout.chips(critical.current, CallLanguage.HINGLISH).filter { it.lit }
        assertEquals(
            listOf(CallStage.AUTHORITY, CallStage.THREAT_REWARD, CallStage.SECRECY_ISOLATION, CallStage.ASK),
            lit.map { it.stage }
        )
        assertTrue("the money ask is one of the lit stages", lit.any { it.stage == CallStage.ASK })
    }

    // ---- 4. the critical alert + haptics ---------------------------------------------------------

    @Test
    fun criticalAlertIsReportSectionSixTextVerbatimAndExistsOnlyAtCritical() {
        val expectedHinglish = "Yeh digital arrest scam ka pattern hai. Koi bhi police ya CBI video " +
            "call pe arrest nahi karti. Call kaat dijiye aur 1930 pe call karein."

        val trail = ladder()
        trail.forEachIndexed { index, progress ->
            val hinglish = CallDemoLayout.alert(progress.current, CallLanguage.HINGLISH)
            if (index < CallDemoScript.size) {
                assertNull("the alert must stay held below CRITICAL (step $index)", hinglish)
            } else {
                assertEquals(expectedHinglish, hinglish)
                assertEquals(
                    "the English screen gets the English alert, not a Hinglish one",
                    CallShieldCopy.digitalArrestAlert.english,
                    CallDemoLayout.alert(progress.current, CallLanguage.ENGLISH)
                )
            }
        }
    }

    @Test
    fun hapticsAreFlaggedOnceOnTheTransitionIntoCriticalOnly() {
        val flags = stepProgress().map { it.current!!.haptics }
        assertEquals(
            "one pulse, on the step that enters CRITICAL",
            listOf(false, false, false, true),
            flags
        )
        // Back out of critical and it is no longer a transition step, so no repeat pulse.
        val runner = CallDemoRunner()
        repeat(4) { runner.advance() }
        val back = runner.back()
        assertEquals(DemoRiskPosition.HIGH, back.position)
        assertNull("stepping back out of CRITICAL clears the alert", CallDemoLayout.alert(back.current, CallLanguage.HINGLISH))
    }

    // ---- 5. reset + back ------------------------------------------------------------------------

    @Test
    fun resetReturnsTheScreenToTheLowPreStartState() {
        val runner = CallDemoRunner()
        repeat(4) { runner.advance() }
        assertEquals(DemoRiskPosition.CRITICAL, runner.progress().position)

        val reset = runner.reset()

        assertNull("reset leaves no step to read", reset.current)
        assertEquals(DemoRiskPosition.LOW, reset.position)
        assertFalse(reset.finished)
        assertEquals(0, reset.steps.size)
        assertTrue("the transcript is cleared", runner.transcript().isEmpty())

        val meter = CallDemoLayout.meter(reset.current, reset, CallLanguage.HINGLISH)
        assertTrue(meter.isPreStart)
        assertNull(meter.scoreText)
        assertEquals(0, meter.bandFilled)
        assertEquals(CallDemoLayout.preStartPosition.hinglish, meter.positionLabel)
        assertEquals("Low is the position reset returns to", DemoRiskPosition.LOW, meter.position)
        assertTrue(CallDemoLayout.chips(reset.current, CallLanguage.HINGLISH).none { it.lit })
    }

    @Test
    fun steppingBackReDerivesTheRungInsteadOfDiallingItDown() {
        val runner = CallDemoRunner()
        repeat(4) { runner.advance() }

        val back1 = runner.back()
        assertEquals(52, back1.current!!.score)
        assertEquals(DemoRiskPosition.HIGH, back1.position)

        val back2 = runner.back()
        assertEquals(44, back2.current!!.score)
        assertEquals(DemoRiskPosition.MEDIUM, back2.position)

        val back3 = runner.back()
        assertEquals(10, back3.current!!.score)
        assertEquals(DemoRiskPosition.LOW, back3.position)

        val back4 = runner.back()
        assertNull("back past the first line is the pre-start state", back4.current)
        assertEquals(DemoRiskPosition.LOW, back4.position)

        // Forward again re-derives the same rung: nothing was remembered from before.
        val forward = runner.advance()
        assertEquals(10, forward.current!!.score)
        assertEquals(DemoRiskPosition.LOW, forward.position)
    }

    // ---- 6. one language per Settings toggle ----------------------------------------------------

    @Test
    fun headerAndControlsAreOneLanguageEach() {
        val hinglish = CallDemoLayout.header(CallLanguage.HINGLISH)
        val english = CallDemoLayout.header(CallLanguage.ENGLISH)

        // The reported bug: an English header ("Call Shield") over a Hinglish body.
        assertNotEquals("Call Shield", hinglish.title)
        assertEquals("Demo: khatre ka level", hinglish.title)
        assertTrue(hinglish.subtitle.startsWith("Sirf typed demo"))
        assertEquals("Demo band karein", hinglish.exitLabel)
        assertEquals("English", hinglish.languageToggleLabel) // button names the language it switches to

        assertEquals("Demo script: risk level", english.title)
        assertTrue(english.subtitle.startsWith("Typed demo only"))
        assertEquals("Close demo script", english.exitLabel)
        assertEquals("Hinglish", english.languageToggleLabel)

        // Every demo control label is translated, not borrowed from the other language. (A mid-run
        // state: once the script is finished, Next reads "Script poora hua" / "Script finished".)
        val progress = ladder()[1]
        assertEquals(
            listOf("Peeche", "Agli line", "Shuru se"),
            CallDemoLayout.controls(progress, CallLanguage.HINGLISH).map { it.label }
        )
        assertEquals(
            listOf("Back", "Next line", "Reset"),
            CallDemoLayout.controls(progress, CallLanguage.ENGLISH).map { it.label }
        )

        // The meter card, chips and limits follow the same toggle (read at the critical rung).
        val critical = ladder().last()
        assertEquals("Khatre ka level", CallDemoLayout.meter(critical.current, critical, CallLanguage.HINGLISH).heading)
        assertEquals("Risk level", CallDemoLayout.meter(critical.current, critical, CallLanguage.ENGLISH).heading)
        assertEquals(
            "Turant rukein",
            CallDemoLayout.meter(critical.current, critical, CallLanguage.HINGLISH).positionLabel
        )
        assertEquals(
            "Critical",
            CallDemoLayout.meter(critical.current, critical, CallLanguage.ENGLISH).positionLabel
        )
        assertEquals("Pehchaan", CallDemoLayout.chips(critical.current, CallLanguage.HINGLISH).first().label)
        assertEquals("Authority", CallDemoLayout.chips(critical.current, CallLanguage.ENGLISH).first().label)
    }

    @Test
    fun everyDemoStringExistsInBothLanguagesAndTheyDiffer() {
        assertTrue("the copy map must not be empty", CallDemoLayout.allCopy.isNotEmpty())
        CallDemoLayout.allCopy.forEach { (name, copy) ->
            assertTrue("$name needs a Hinglish variant", copy.hinglish.isNotBlank())
            assertTrue("$name needs an English variant", copy.english.isNotBlank())
            assertNotEquals("$name is not actually translated", copy.hinglish, copy.english)
        }
        assertEquals(
            "every limit line is translated too",
            CallDemoLayout.limits.size,
            CallDemoLayout.limitLines(CallLanguage.ENGLISH).size
        )
    }

    @Test
    fun engineOwnedHinglishOnlyNotesAreOmittedOnTheEnglishScreen() {
        val critical = ladder().last()

        val hinglish = CallDemoLayout.line(critical.current, CallLanguage.HINGLISH)
        assertNotNull(hinglish)
        assertEquals("Abhi ki line", hinglish!!.heading)
        assertEquals("Call karne wala", hinglish.speakerLabel)
        assertNotNull("the Hinglish screen shows the Hinglish presenter note", hinglish.note)

        val english = CallDemoLayout.line(critical.current, CallLanguage.ENGLISH)
        assertNotNull(english)
        assertEquals("Current line", english!!.heading)
        assertEquals("Caller", english.speakerLabel)
        assertNull("no Hinglish sentence is mixed into the English screen", english.note)
        assertNull(english.heldNote)

        // The spoken line itself is demo content, identical in both modes (like a quoted transcript).
        assertEquals(CallDemoScript.LINE_4_ASK, english.text)
    }

    // ---- honesty --------------------------------------------------------------------------------

    @Test
    fun limitsKeepTheFourHonestFactsAndNoAbsoluteClaim() {
        val hinglish = CallDemoLayout.limitLines(CallLanguage.HINGLISH)
        val english = CallDemoLayout.limitLines(CallLanguage.ENGLISH)
        assertTrue(hinglish.size >= 4)

        assertTrue(hinglish.any { it.contains("typed demo") })
        assertTrue(hinglish.any { it.contains("deterministic") })
        assertTrue(hinglish.any { it.contains("Deepfake") })
        assertTrue(hinglish.any { it.contains("audio save nahi") })
        assertTrue(english.any { it.contains("typed demo") })
        assertTrue(english.any { it.contains("deterministic") })
        assertTrue(english.any { it.contains("Deepfake") })

        val forbidden = listOf("100%", "safe", "live call", "call capture", "deepfake detect")
        (hinglish + english + CallDemoLayout.allCopy.values.flatMap { listOf(it.hinglish, it.english) })
            .forEach { text ->
                forbidden.forEach { bad ->
                    assertFalse("demo copy must not contain '$bad': $text", text.lowercase().contains(bad))
                }
            }
    }
}
