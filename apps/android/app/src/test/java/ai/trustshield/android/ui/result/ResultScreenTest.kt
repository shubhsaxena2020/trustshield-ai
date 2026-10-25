package ai.trustshield.android.ui.result

import ai.trustshield.android.ui.theme.Contrast
import ai.trustshield.android.ui.theme.RiskTone
import ai.trustshield.android.ui.theme.RiskToneCopy
import ai.trustshield.android.ui.theme.ToneCopy
import ai.trustshield.android.ui.theme.container
import ai.trustshield.android.ui.theme.onContainer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * C03 unit tests — pure JVM, no device, no Compose UI-test dependency.
 *
 * They are the executable form of the C03 definition of done (WORK_PACKAGES "separate
 * risk/confidence, quotes, actions, limitations and Unable to Verify") plus the edge cases the issue
 * names: empty evidence, long quote, backend disagreement (an evidence item whose quote the source
 * cannot support).
 *
 * Every fixture string here is hand-written test data. Nothing is model output and no accuracy
 * metric is implied.
 */
class ResultScreenTest {

    // ------------------------------------------------------------------ helpers

    private fun evidence(
        id: String = "e1",
        quote: String,
        severity: EvidenceSeverity = EvidenceSeverity.WATCH,
        titleHinglish: String = "Sanket",
        titleEnglish: String = "Signal",
        detailHinglish: String = "Detail",
        detailEnglish: String = "Detail"
    ) = ResultEvidenceInput(
        id = id,
        module = "rules/test",
        titleHinglish = titleHinglish,
        titleEnglish = titleEnglish,
        detailHinglish = detailHinglish,
        detailEnglish = detailEnglish,
        severity = severity,
        quote = quote
    )

    private fun input(
        source: String,
        coverage: InputCoverage = InputCoverage.READABLE_SUPPORTED,
        riskTone: RiskTone = RiskTone.RED,
        confidence: ConfidenceLevel = ConfidenceLevel.HIGH,
        evidence: List<ResultEvidenceInput> = emptyList(),
        actions: List<ResultActionInput> = emptyList(),
        limitations: List<ToneCopy> = emptyList()
    ) = ResultInput(
        sourceText = source,
        coverage = coverage,
        riskTone = riskTone,
        confidence = confidence,
        evidence = evidence,
        actions = actions,
        limitations = limitations
    )

    private fun assertUnableToVerify(state: ResultUiState) {
        assertTrue("expected Unable to Verify, was ${state.status}", state.unableToVerify)
        assertEquals(RiskTone.NEUTRAL, state.risk.tone)
        assertEquals(ConfidenceLevel.LOW, state.confidence.level)
    }

    private fun assertNoAbsoluteSafetyCopy(state: ResultUiState, where: String) {
        for (language in UiLanguage.entries) {
            state.visibleStrings(language).forEach { text ->
                val violations = AbsoluteSafetyGuard.violations(text)
                assertTrue(
                    "$where/${language}: absolute-safety copy leaked in \"$text\" -> $violations",
                    violations.isEmpty()
                )
                assertFalse(
                    "$where/${language}: literal \"100% safe\" produced",
                    text.lowercase(Locale.ROOT).contains("100% safe")
                )
            }
        }
    }

    // ------------------------------------------------- 1. risk and confidence never collapse

    @Test
    fun riskAndConfidenceAreSeparateValuesWithSeparateLabels() {
        val state = ResultPresenter.build(ResultFixtures.digitalArrestInput)

        // Two distinct fields, two distinct values, neither derived from the other.
        assertNotSame(state.risk, state.confidence)
        assertNotEquals(state.risk.label.hinglish, state.confidence.label.hinglish)
        assertEquals(RiskTone.RED, state.risk.tone)
        assertEquals(ConfidenceLevel.MEDIUM, state.confidence.level)
        // The confidence badge carries no risk tone at all (ConfidenceBadge has no tone field), and
        // the risk header carries no confidence number.
        assertFalse(state.risk.label.hinglish.contains(state.confidence.label.hinglish))
        assertFalse(state.confidence.label.hinglish.contains(state.risk.label.hinglish))
        // Hinglish is the default rendering; English is an option.
        assertEquals(RiskToneCopy.RED.label.hinglish, state.risk.label.pick())
        assertEquals(RiskToneCopy.RED.label.english, state.risk.label.pick(UiLanguage.ENGLISH))
    }

    @Test
    fun changingConfidenceNeverMovesTheRiskBand() {
        val source = ResultFixtures.DIGITAL_ARREST_SOURCE
        val ev = evidence(quote = "Abhi turant apna OTP batao", severity = EvidenceSeverity.STRONG)

        val low = ResultPresenter.build(input(source, confidence = ConfidenceLevel.LOW, evidence = listOf(ev)))
        val high = ResultPresenter.build(input(source, confidence = ConfidenceLevel.HIGH, evidence = listOf(ev)))

        assertEquals(low.risk, high.risk)
        assertNotEquals(low.confidence, high.confidence)
        assertEquals(ConfidenceLevel.LOW, low.confidence.level)
        assertEquals(ConfidenceLevel.HIGH, high.confidence.level)
    }

    @Test
    fun verifiedResultShowsBoundedEngineScoreInsideRiskBand() {
        val state = ResultPresenter.build(ResultFixtures.digitalArrestInput.copy(riskScore = 70))

        assertFalse(state.unableToVerify)
        assertEquals(70, state.risk.score)
        assertEquals("Score: 70/100", state.visibleStrings(UiLanguage.ENGLISH)
            .firstOrNull { it.startsWith("Score:") })
    }

    @Test
    fun unverifiedResultDoesNotShowEngineScore() {
        val state = ResultPresenter.build(
            ResultFixtures.unreadableInput.copy(riskScore = 70)
        )

        assertTrue(state.unableToVerify)
        assertNull(state.risk.score)
    }

    @Test
    fun changingTheRiskBandNeverMovesConfidence() {
        val source = ResultFixtures.DIGITAL_ARREST_SOURCE
        val ev = evidence(quote = "Kisi ko mat batana")

        val green = ResultPresenter.build(
            input(source, riskTone = RiskTone.GREEN, confidence = ConfidenceLevel.MEDIUM, evidence = listOf(ev))
        )
        val red = ResultPresenter.build(
            input(source, riskTone = RiskTone.RED, confidence = ConfidenceLevel.MEDIUM, evidence = listOf(ev))
        )

        assertEquals(green.confidence, red.confidence)
        assertNotEquals(green.risk, red.risk)
        assertEquals(RiskTone.GREEN, green.risk.tone)
        assertEquals(ConfidenceLevel.MEDIUM, red.confidence.level)
    }

    // ------------------------------------------------- 2. quote verification / backend disagreement

    @Test
    fun unverifiedQuoteIsDroppedAndResultDegradesToUnableToVerify() {
        val source = "Namaste, aapka parcel ready hai."
        val fabricated = evidence(id = "fake-1", quote = "turant 50000 bhejo warna arrest hoga")
        val state = ResultPresenter.build(
            input(source, riskTone = RiskTone.RED, confidence = ConfidenceLevel.HIGH, evidence = listOf(fabricated))
        )

        assertUnableToVerify(state)
        assertTrue("fabricated evidence must not be rendered", state.evidence.isEmpty())
        assertEquals(1, state.droppedEvidence.count)
        assertEquals(listOf("fake-1"), state.droppedEvidence.ids)
        assertEquals(ResultCopy.DROPPED_REASON_EMPTY.hinglish, state.droppedEvidence.reason.hinglish)
        assertEquals(UnverifiedReason.NO_VERIFIED_EVIDENCE, (state.status as ResultStatus.UnableToVerify).reason)
        assertNoAbsoluteSafetyCopy(state, "fabricated-quote")
    }

    @Test
    fun onlyTheUnsupportedEvidenceIsDroppedAndConfidenceStepsDown() {
        val source = "CBI officer bol raha hoon. Turant OTP batao."
        val ok = evidence(id = "ok", quote = "Turant OTP batao", severity = EvidenceSeverity.STRONG)
        val bad = evidence(id = "bad", quote = "aapka beta kidnap hai")

        val state = ResultPresenter.build(
            input(source, riskTone = RiskTone.RED, confidence = ConfidenceLevel.HIGH, evidence = listOf(ok, bad))
        )

        assertEquals(1, state.evidence.size)
        assertEquals("ok", state.evidence[0].id)
        assertEquals(1, state.droppedEvidence.count)
        assertEquals(listOf("bad"), state.droppedEvidence.ids)
        assertFalse(state.unableToVerify)
        // Risk is untouched by the dropped quote; only confidence steps down, one step.
        assertEquals(RiskTone.RED, state.risk.tone)
        assertEquals(ConfidenceLevel.MEDIUM, state.confidence.level)
        assertEquals(ResultCopy.DROPPED_REASON.hinglish, state.confidence.reason.hinglish)
    }

    @Test
    fun blankQuoteIsNeverRendered() {
        val state = ResultPresenter.build(
            input("Kuch text hai.", evidence = listOf(evidence(id = "blank", quote = "   ")))
        )
        assertUnableToVerify(state)
        assertEquals(listOf("blank"), state.droppedEvidence.ids)
    }

    @Test
    fun renderedQuoteIsSlicedFromTheSourceSoCasingAndSpacingAreTheUsers() {
        val source = "Hello,  aapke naam par PARCEL mila hai. Turant OTP batao."
        val state = ResultPresenter.build(
            input(source, evidence = listOf(evidence(quote = "aapke naam par parcel mila hai")))
        )

        val quote = state.evidence.single().quote
        assertEquals("aapke naam par PARCEL mila hai", quote.text)
        assertEquals(source.substring(quote.start, quote.endExclusive), quote.text)
        assertFalse(quote.truncated)
        // The source itself still starts with the double space we typed; nothing was reformatted.
        assertTrue(source.startsWith("Hello,  aapke"))
    }

    @Test
    fun zeroWidthCharactersAndNewlinesDoNotBreakVerification() {
        val source = "Turant OTP\u200B batao\nwarna account band ho jayega."
        val state = ResultPresenter.build(
            input(source, evidence = listOf(evidence(quote = "OTP batao warna account band ho jayega")))
        )

        val quote = state.evidence.single().quote
        assertEquals(source.substring(quote.start, quote.endExclusive), quote.text)
        assertTrue("the raw span keeps the newline", quote.text.contains("\n"))
        assertTrue("the raw span keeps the zero-width char", quote.text.contains('\u200B'))
        assertEquals(
            "otp batao warna account band ho jayega",
            TextNormalise.normalise(quote.text)
        )
    }

    @Test
    fun everyRenderedQuoteIsALiteralSubstringOfItsSource() {
        ResultFixtures.allInputs.forEach { src ->
            val state = ResultPresenter.build(src)
            state.evidence.forEach { item ->
                val span = src.sourceText.substring(item.quote.start, item.quote.endExclusive)
                if (item.quote.truncated) {
                    val prefix = item.quote.text.removeSuffix(ResultPresenter.ELLIPSIS)
                    assertTrue("truncated quote must be a prefix of its span", span.startsWith(prefix))
                } else {
                    assertEquals(span, item.quote.text)
                }
                assertTrue(
                    "quote must be a literal substring of the source text",
                    src.sourceText.contains(item.quote.text.replace(ResultPresenter.ELLIPSIS, ""))
                )
            }
        }
    }

    // ------------------------------------------------- 3. long-quote truncation

    @Test
    fun longQuoteIsTruncatedToAnExactPrefixWithEllipsis() {
        val longQuote =
            "aapka account 24 ghante mein band ho jayega, ".repeat(6).trim()
        assertTrue("fixture must exceed the limit", longQuote.length > ResultPresenter.MAX_QUOTE_CHARS)
        val source = "Notice: $longQuote turant link kholo."

        val state = ResultPresenter.build(input(source, evidence = listOf(evidence(quote = longQuote))))
        val quote = state.evidence.single().quote

        assertTrue(quote.truncated)
        assertTrue(quote.text.endsWith(ResultPresenter.ELLIPSIS))
        assertTrue(
            "rendered length must stay bounded",
            quote.text.length <= ResultPresenter.MAX_QUOTE_CHARS + ResultPresenter.ELLIPSIS.length
        )
        val prefix = quote.text.removeSuffix(ResultPresenter.ELLIPSIS)
        assertTrue("prefix must be non-empty", prefix.isNotEmpty())
        assertTrue("prefix must be an exact prefix of the quote", longQuote.startsWith(prefix))
        assertTrue("prefix must be an exact prefix of the source", source.contains(prefix))
        // Shortening must not reword: no character of the prefix may differ from the source.
        assertEquals(prefix, source.substring(quote.start, quote.start + prefix.length))
    }

    @Test
    fun shortQuoteIsNotMarkedAsTruncated() {
        val state = ResultPresenter.build(
            input("Turant OTP batao.", evidence = listOf(evidence(quote = "Turant OTP batao")))
        )
        val quote = state.evidence.single().quote
        assertFalse(quote.truncated)
        assertFalse(quote.text.endsWith(ResultPresenter.ELLIPSIS))
        assertEquals("Turant OTP batao", quote.text)
    }

    // ------------------------------------------------- 4. empty / unreadable / unsupported

    @Test
    fun emptyEvidenceListYieldsUnableToVerifyAndLowConfidence() {
        val state = ResultPresenter.build(
            input(
                ResultFixtures.DIGITAL_ARREST_SOURCE,
                riskTone = RiskTone.GREEN,
                confidence = ConfidenceLevel.HIGH,
                evidence = emptyList()
            )
        )

        assertUnableToVerify(state)
        assertEquals(UnverifiedReason.NO_VERIFIED_EVIDENCE, (state.status as ResultStatus.UnableToVerify).reason)
        assertTrue(state.evidence.isEmpty())
        assertEquals(ResultCopy.UNABLE_LABEL.hinglish, state.risk.label.hinglish)
        assertNotEquals("Kam khatra", state.risk.label.hinglish)
        // Nothing may read as reassurance, and the safest next step is always shown.
        val unable = state.status as ResultStatus.UnableToVerify
        assertEquals(ResultCopy.SAFEST_NEXT_STEP.hinglish, unable.safestNextStep.hinglish)
        assertNoAbsoluteSafetyCopy(state, "empty-evidence")
    }

    @Test
    fun unreadableInputLowersConfidenceInsteadOfAssertingSafety() {
        // Hostile input: an engine that claims low risk with high confidence on unreadable text.
        val state = ResultPresenter.build(
            input("", coverage = InputCoverage.UNREADABLE, riskTone = RiskTone.GREEN, confidence = ConfidenceLevel.HIGH)
        )

        assertUnableToVerify(state)
        assertEquals(UnverifiedReason.UNREADABLE_INPUT, (state.status as ResultStatus.UnableToVerify).reason)
        assertEquals(ResultCopy.UNREADABLE_FAILED.hinglish, (state.status as ResultStatus.UnableToVerify).whatFailed.hinglish)
        assertNotEquals("Kam khatra", state.risk.label.hinglish)
        assertEquals(0, state.analysedCharacters)
        assertNoAbsoluteSafetyCopy(state, "unreadable")
    }

    @Test
    fun unsupportedLanguageUsesTheSpecCopyAndNeverGuesses() {
        val state = ResultPresenter.build(ResultFixtures.unsupportedLanguageInput)

        assertUnableToVerify(state)
        val unable = state.status as ResultStatus.UnableToVerify
        assertEquals(UnverifiedReason.UNSUPPORTED_LANGUAGE, unable.reason)
        // UI_SPEC "Copy and states" wording, verbatim.
        assertEquals("Is bhasha ko abhi poori tarah check nahi kar sakte", unable.whatFailed.hinglish)
        assertTrue(state.limitations.any { it.hinglish == ResultCopy.DEFAULT_LIMITATION_LANGUAGE.hinglish })
        assertNoAbsoluteSafetyCopy(state, "unsupported-language")
    }

    @Test
    fun supportedReadableInputWithVerifiedEvidenceIsNotUnableToVerify() {
        val state = ResultPresenter.build(ResultFixtures.digitalArrestInput)
        assertFalse(state.unableToVerify)
        assertEquals(4, state.evidence.size)
        assertEquals(0, state.droppedEvidence.count)
        assertTrue(state.deterministicVerdict)
    }

    // ------------------------------------------------- 5. absolute-safety copy can never be produced

    @Test
    fun noFixtureCanProduceAbsoluteSafetyCopy() {
        ResultFixtures.all.forEach { state -> assertNoAbsoluteSafetyCopy(state, "fixture") }
        // The guard itself must reject the phrasings the product bans.
        listOf(
            "Yeh message 100% safe hai",
            "100% SAFE",
            "Yeh bilkul safe hai",
            "It is risk free",
            "Zero risk",
            "guaranteed safe"
        ).forEach { text ->
            assertFalse("guard missed: $text", AbsoluteSafetyGuard.isClean(text))
        }
        // ...and must not fire on the app's own ordinary copy.
        listOf(
            ResultCopy.UNABLE_SUMMARY.hinglish,
            ResultCopy.UNABLE_SUMMARY.english,
            ResultCopy.NOT_PROOF.hinglish,
            ResultCopy.NOT_PROOF.english,
            RiskToneCopy.NEUTRAL.contentDescription.hinglish,
            RiskToneCopy.GREEN.contentDescription.english
        ).filter { it.isNotBlank() }.forEach { text ->
            assertTrue("false positive on own copy: $text", AbsoluteSafetyGuard.isClean(text))
        }
    }

    @Test
    fun absolutistEngineDetailIsScrubbedButTheRestOfTheDetailSurvives() {
        val source = "Aapka account update ho gaya hai."
        val state = ResultPresenter.build(
            input(
                source,
                evidence = listOf(
                    evidence(
                        quote = "Aapka account update ho gaya hai",
                        detailHinglish = "Yeh message 100% safe hai. Koi dikkat nahi hai.",
                        detailEnglish = "This message is 100% safe. Nothing to worry about."
                    )
                )
            )
        )

        val detail = state.evidence.single().detail
        assertFalse(detail.hinglish.contains("100%"))
        assertTrue(detail.hinglish.startsWith(ResultCopy.ABSOLUTE_CLAIM_REPLACEMENT.hinglish))
        assertTrue("the clean sentence is preserved", detail.hinglish.contains("Koi dikkat nahi hai"))
        assertFalse(detail.english.contains("100%"))
        assertTrue(detail.english.contains("Nothing to worry about"))
        assertNoAbsoluteSafetyCopy(state, "scrubbed-detail")
    }

    // ------------------------------------------------- 6. backend enrichment is visibly off

    @Test
    fun backendEnrichmentIsOffByDefaultAndLabelled() {
        assertFalse(BackendEnrichment.ENABLED_BY_DEFAULT)
        val state = ResultPresenter.build(ResultFixtures.digitalArrestInput)
        assertFalse(state.backend.available)
        assertEquals(ResultCopy.BACKEND_OFF_LABEL.hinglish, state.backend.label.hinglish)
        assertTrue(state.limitations.any { it.hinglish == ResultCopy.DEFAULT_LIMITATION_OFFLINE.hinglish })

        val on = ResultPresenter.build(
            ResultInput(
                sourceText = ResultFixtures.DIGITAL_ARREST_SOURCE,
                coverage = InputCoverage.READABLE_SUPPORTED,
                riskTone = RiskTone.RED,
                confidence = ConfidenceLevel.MEDIUM,
                evidence = listOf(evidence(quote = "Kisi ko mat batana")),
                backendAvailable = true
            )
        )
        assertTrue(on.backend.available)
        assertEquals(ResultCopy.BACKEND_ON_LABEL.hinglish, on.backend.label.hinglish)
    }

    // ------------------------------------------------- 7. actions / limitations

    @Test
    fun actionsAreOrderedPrimaryFirstAndKeepBothLanguages() {
        val state = ResultPresenter.build(
            ResultInput(
                sourceText = ResultFixtures.DIGITAL_ARREST_SOURCE,
                coverage = InputCoverage.READABLE_SUPPORTED,
                riskTone = RiskTone.RED,
                confidence = ConfidenceLevel.MEDIUM,
                evidence = listOf(evidence(quote = "Kisi ko mat batana")),
                actions = listOf(
                    ResultActionInput("secondary", "Baad mein", "Later", "", "", ActionPriority.SECONDARY),
                    ResultActionInput("primary", "Call kaat dein", "End the call", "", "", ActionPriority.PRIMARY)
                )
            )
        )

        assertEquals(listOf("primary", "secondary"), state.actions.map { it.id })
        assertEquals(ActionPriority.PRIMARY, state.actions.first().priority)
        assertEquals("End the call", state.actions.first().label.pick(UiLanguage.ENGLISH))
        assertEquals("Call kaat dein", state.actions.first().label.pick(UiLanguage.HINGLISH))
    }

    @Test
    fun limitationsAreAlwaysPresentAndAlwaysSayWhatIsNotChecked() {
        val state = ResultPresenter.build(ResultFixtures.bankCreditInput)
        assertTrue(state.limitations.size >= 2)
        assertTrue(state.limitations.any { it.hinglish == ResultCopy.DEFAULT_LIMITATION_SOURCE_SCOPE.hinglish })
        assertTrue(state.limitations.any { it.hinglish == ResultCopy.DEFAULT_LIMITATION_NOT_PROOF.hinglish })
        // Caller-supplied limitations are kept, not replaced.
        val withExtra = ResultPresenter.build(
            ResultInput(
                sourceText = "Turant OTP batao.",
                coverage = InputCoverage.READABLE_SUPPORTED,
                riskTone = RiskTone.AMBER,
                confidence = ConfidenceLevel.MEDIUM,
                evidence = listOf(evidence(quote = "Turant OTP batao")),
                limitations = listOf(ToneCopy("Sirf is message par check", "Checked on this message only"))
            )
        )
        assertTrue(withExtra.limitations.any { it.hinglish == "Sirf is message par check" })
    }

    // ------------------------------------------------- 8. accessibility + language

    @Test
    fun talkBackDescriptionIsRiskThenConfidence() {
        val state = ResultPresenter.build(ResultFixtures.digitalArrestInput)
        UiLanguage.entries.forEach { language ->
            val description = state.screenDescription(language)
            val risk = state.risk.contentDescription.pick(language)
            val confidence = state.confidence.label.pick(language)
            assertTrue("announcement must open with the risk", description.startsWith(risk))
            assertTrue(
                "confidence must be announced after risk",
                description.indexOf(confidence) > description.indexOf(risk)
            )
        }
    }

    @Test
    fun everyToneIsReadableWithoutColour() {
        val tones = mutableSetOf<RiskTone>()
        ResultFixtures.all.forEach { state ->
            tones += state.risk.tone
            state.evidence.forEach { tones += it.severityTone }
        }
        // Every tone in the design system is exercised by at least one fixture state.
        assertEquals(
            setOf(RiskTone.NEUTRAL, RiskTone.GREEN, RiskTone.AMBER, RiskTone.RED),
            tones
        )

        tones.forEach { tone ->
            assertTrue("tone $tone must be self-describing", RiskToneCopy.isSelfDescribing(tone))
            val p = RiskToneCopy.of(tone)
            assertTrue(p.label.hinglish.isNotBlank() && p.label.english.isNotBlank())
            assertTrue(p.glyph.isNotBlank())
            assertTrue(p.contentDescription.hinglish.isNotBlank())
        }
        EvidenceSeverity.entries.forEach { severity ->
            assertTrue(severity.label().hinglish.isNotBlank())
            assertTrue(severity.glyph.isNotBlank())
        }
    }

    @Test
    fun evidenceTextOnToneContainersMeetsNormalTextContrast() {
        // The quote block and severity chip are normal-size text, so 4.5:1 applies in both themes.
        RiskTone.entries.forEach { tone ->
            listOf(false, true).forEach { dark ->
                val ratio = Contrast.ratio(tone.onContainer(dark), tone.container(dark))
                println(
                    String.format(
                        Locale.ROOT,
                        "  %-8s dark=%-5s onContainer/container = %.3f:1",
                        tone, dark, ratio
                    )
                )
                assertTrue(
                    "tone $tone (dark=$dark) container text is $ratio:1, below 4.5:1",
                    ratio >= Contrast.NORMAL_TEXT_MIN
                )
            }
        }
    }

    @Test
    fun englishFallsBackToHinglishWhenTheTranslationIsMissing() {
        val state = ResultPresenter.build(
            input(
                "Turant OTP batao.",
                evidence = listOf(
                    evidence(quote = "Turant OTP batao", titleHinglish = "OTP maanga", titleEnglish = "")
                )
            )
        )
        val title = state.evidence.single().title
        assertEquals("OTP maanga", title.hinglish)
        assertEquals("OTP maanga", title.pick(UiLanguage.ENGLISH))
        // Default rendering stays Hinglish.
        assertEquals("OTP maanga", title.pick())
        assertEquals(UiLanguage.HINGLISH, UiLanguage.Default)
    }

    @Test
    fun severityKeepsTheEngineVerdictUntouched() {
        // The screen never re-derives a band from evidence: severity only tints the card.
        val source = "Turant OTP batao."
        val info = ResultPresenter.build(
            input(source, riskTone = RiskTone.GREEN, evidence = listOf(evidence(quote = source, severity = EvidenceSeverity.INFO)))
        )
        val strong = ResultPresenter.build(
            input(source, riskTone = RiskTone.GREEN, evidence = listOf(evidence(quote = source, severity = EvidenceSeverity.STRONG)))
        )
        assertEquals(RiskTone.GREEN, info.risk.tone)
        assertEquals(RiskTone.GREEN, strong.risk.tone)
        assertEquals(EvidenceSeverity.INFO, info.evidence.single().severity)
        assertEquals(RiskTone.RED, strong.evidence.single().severityTone)
    }
}
