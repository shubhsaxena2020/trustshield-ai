package ai.trustshield.android.ui.result

import ai.trustshield.android.core.payment.PaymentAdvisory
import ai.trustshield.android.core.payment.PaymentAdvisoryVerdict
import ai.trustshield.android.core.payment.PaymentFields
import ai.trustshield.android.core.payment.PaymentHint
import ai.trustshield.android.core.payment.PaymentHintKind
import ai.trustshield.android.core.payment.PaymentHintSeverity
import ai.trustshield.android.core.payment.PaymentStatus
import ai.trustshield.android.ui.theme.RiskTone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wiring tests for [ResultUiState.paymentAdvisory] — pure JVM, no `android.*`, no device, no Compose
 * UI-test dependency, and no `android.content.Context` anywhere (the contract types under
 * `core/payment` never touch the framework either, which is why this can run as a plain unit test).
 *
 * What is pinned here:
 *  1. [ResultPresenter] behaviour is **unchanged**: it keeps returning `paymentAdvisory = null`, so no
 *     scan silently gains an advisory the caller never asked for;
 *  2. adding the field did not disturb any other field — [ResultUiState.copy] with an advisory keeps
 *     status, risk, confidence, evidence, actions, limitations, backend and the counters identical,
 *     and a copy round-trip returns an equal state;
 *  3. the field is the **last** constructor parameter with a default, so the existing positional/named
 *     call sites stay source-compatible;
 *  4. an advisory with hints is stored and returned unchanged (value equality, no rewriting);
 *  5. the advisory is not folded into the spoken or drawn strings of the panel: it renders as its own
 *     card, so [ResultUiState.visibleStrings] and [ResultUiState.screenDescription] are identical
 *     with and without it.
 *
 * Every string below is hand-written test data. Nothing is model output.
 */
class PaymentAdvisoryWiringTest {

    // ------------------------------------------------------------------ helpers

    private fun evidenceItem(quote: String) = ResultEvidenceInput(
        id = "pay-e1",
        module = "rules/test",
        titleHinglish = "Sanket",
        titleEnglish = "Signal",
        detailHinglish = "Detail",
        detailEnglish = "Detail",
        severity = EvidenceSeverity.WATCH,
        quote = quote
    )

    /** Minimal [ResultInput] with one quote the source really contains, so the result verifies. */
    private fun input(
        source: String = "Turant OTP batao.",
        evidence: List<ResultEvidenceInput> = listOf(evidenceItem(quote = "Turant OTP batao"))
    ) = ResultInput(
        sourceText = source,
        coverage = InputCoverage.READABLE_SUPPORTED,
        riskTone = RiskTone.RED,
        confidence = ConfidenceLevel.HIGH,
        evidence = evidence
    )

    private fun advisory(hints: List<PaymentHint>? = null): PaymentAdvisory = PaymentAdvisory(
        isPaymentScreenshot = true,
        fields = PaymentFields(
            amountMinor = 150_000L,
            amountRaw = "Rs 1,500",
            payeeUpi = "shop@okaxis",
            payeeName = "Local Shop",
            utr = "412345678901",
            status = PaymentStatus.SUCCESS,
            statusRaw = "Success",
            timestampRaw = "27 Oct 2026, 7:04 pm"
        ),
        hints = hints ?: hintsWithValues(),
        verdict = PaymentAdvisoryVerdict.CHECK
    )

    private fun hintsWithValues(): List<PaymentHint> = listOf(
        PaymentHint(
            kind = PaymentHintKind.PAYEE_NOT_REGISTERED,
            severity = PaymentHintSeverity.WARNING,
            maskedPayee = "sho***@okaxis"
        ),
        PaymentHint(
            kind = PaymentHintKind.SCREENSHOT_ONLY,
            severity = PaymentHintSeverity.CAUTION,
            amountMinor = 150_000L
        ),
        PaymentHint(
            kind = PaymentHintKind.UTR_PRESENT,
            severity = PaymentHintSeverity.INFO,
            count = 1
        )
    )

    /** A source that reads like a payment receipt, used to prove the presenter still stays neutral. */
    private val paymentSource: String =
        "Payment Successful. Rs 1,500 paid to sho***@okaxis. UTR 412345678901. 27 Oct 2026, 7:04 pm."

    // ------------------------------------- 1. presenter behaviour is unchanged

    @Test
    fun presenterStillReturnsNoAdvisoryForAnOrdinaryScan() {
        val state = ResultPresenter.build(input())
        assertNull("an ordinary scan must not gain a payment advisory", state.paymentAdvisory)
    }

    @Test
    fun presenterParsesPaymentReceiptAndAttachesAnAdvisoryWithoutChangingRiskWiring() {
        val state = ResultPresenter.build(
            input(source = paymentSource, evidence = listOf(evidenceItem(quote = "Payment Successful")))
        )
        assertFalse(state.unableToVerify)
        val attached = state.paymentAdvisory
        assertTrue("payment receipt should receive a parsed advisory", attached != null)
        assertTrue(attached!!.isPaymentScreenshot)
        assertEquals(150_000L, attached.fields.amountMinor)
        assertEquals("sho***@okaxis", attached.hints.first().maskedPayee)
        assertEquals("Payment Successful", state.evidence.single().quote.text)
    }

    // ------------------------------------- 2. copy() leaves every other field intact

    @Test
    fun copyWithAnAdvisoryKeepsEveryOtherFieldIntact() {
        val before = ResultPresenter.build(input())
        val attached = advisory()
        val after = before.copy(paymentAdvisory = attached)

        assertEquals("the advisory must be the only difference", attached, after.paymentAdvisory)
        assertEquals(before.status, after.status)
        assertEquals(before.risk, after.risk)
        assertEquals(before.confidence, after.confidence)
        assertEquals(before.evidence, after.evidence)
        assertEquals(before.droppedEvidence, after.droppedEvidence)
        assertEquals(before.actions, after.actions)
        assertEquals(before.limitations, after.limitations)
        assertEquals(before.backend, after.backend)
        assertEquals(before.quoteLimit, after.quoteLimit)
        assertEquals(before.analysedCharacters, after.analysedCharacters)
        assertEquals(before.deterministicVerdict, after.deterministicVerdict)
        // ...and the derived predicates still answer the same way.
        assertTrue("unableToVerify must not flip", before.unableToVerify == after.unableToVerify)
        assertEquals(before.risk.tone, after.risk.tone)
    }

    @Test
    fun copyRoundTripLeavesStatusRiskAndEvidenceUnchanged() {
        val before = ResultPresenter.build(input())
        val roundTripped = before.copy(paymentAdvisory = advisory()).copy(paymentAdvisory = null)

        assertEquals("a round-trip must return an equal state", before, roundTripped)
        assertEquals(before.status, roundTripped.status)
        assertEquals(before.risk, roundTripped.risk)
        assertEquals(before.evidence, roundTripped.evidence)
        assertEquals(1, roundTripped.evidence.size)
        assertEquals("Turant OTP batao", roundTripped.evidence.single().quote.text)
        assertNull(roundTripped.paymentAdvisory)
    }

    // ------------------------------------- 3. the field is appended, with a default

    @Test
    fun advisoryIsTheLastConstructorParameterAndDefaultsToNull() {
        val primary = ResultUiState::class.java.declaredConstructors
            .filterNot { it.isSynthetic }
            .maxByOrNull { it.parameterCount }
        assertTrue("ResultUiState must have a primary constructor", primary != null)
        val parameters = primary!!.parameterTypes
        assertEquals(
            "the advisory must be the LAST parameter so positional call sites keep their meaning",
            PaymentAdvisory::class.java,
            parameters.last()
        )

        // Named-argument construction that omits the field still compiles and yields null.
        val base = ResultPresenter.build(input())
        val named = ResultUiState(
            status = ResultStatus.Verified,
            risk = base.risk,
            confidence = base.confidence,
            evidence = emptyList(),
            droppedEvidence = DroppedEvidence(0, emptyList(), ResultCopy.DROPPED_REASON_EMPTY),
            actions = emptyList(),
            limitations = emptyList(),
            backend = BackendEnrichment(false, ResultCopy.BACKEND_OFF_LABEL, ResultCopy.BACKEND_OFF_DETAIL),
            quoteLimit = ResultPresenter.MAX_QUOTE_CHARS,
            analysedCharacters = 17
        )
        assertNull(named.paymentAdvisory)
        assertTrue("deterministicVerdict keeps its existing default", named.deterministicVerdict)
    }

    // ------------------------------------- 4. the advisory is stored verbatim

    @Test
    fun advisoryWithHintsIsStoredAndReturnedUnchanged() {
        val attached = advisory()
        val state = ResultPresenter.build(input()).copy(paymentAdvisory = attached)

        val stored = state.paymentAdvisory
        assertTrue("the advisory must be present", stored != null)
        assertEquals(attached, stored)
        val kept = state.paymentAdvisory!!
        assertEquals(attached.hints, kept.hints)
        assertEquals(3, kept.hints.size)
        assertEquals(PaymentAdvisoryVerdict.CHECK, kept.verdict)
        assertTrue("the confirmation line stays required", kept.confirmationRequired)
        assertEquals("sho***@okaxis", kept.hints.first().maskedPayee)
        assertEquals(1, kept.warnings.size)
        assertEquals(1, kept.cautions.size)
        assertEquals(PaymentHintKind.UTR_PRESENT, kept.hints.last().kind)
    }

    @Test
    fun anAdvisoryWithoutHintsIsAlsoStoredUnchanged() {
        val bare = advisory(hints = emptyList())
        val state = ResultPresenter.build(input()).copy(paymentAdvisory = bare)

        assertEquals(bare, state.paymentAdvisory)
        assertTrue(state.paymentAdvisory!!.hints.isEmpty())
        assertTrue(state.paymentAdvisory!!.warnings.isEmpty())
        assertTrue(state.paymentAdvisory!!.cautions.isEmpty())
    }

    // ------------------------------------- 5. the panel's own strings do not move

    @Test
    fun attachingAnAdvisoryDoesNotChangeWhatThePanelDrawsOrSpeaks() {
        val before = ResultPresenter.build(input())
        val after = before.copy(paymentAdvisory = advisory())

        UiLanguage.entries.forEach { language ->
            assertEquals(
                "the advisory renders as its own card, not as panel copy ($language)",
                before.visibleStrings(language),
                after.visibleStrings(language)
            )
            assertEquals(
                "the announcement must not change ($language)",
                before.screenDescription(language),
                after.screenDescription(language)
            )
            assertEquals(
                "the spoken-only score clause must not change ($language)",
                before.screenDescription(language, 70),
                after.screenDescription(language, 70)
            )
        }
        // Nothing from the advisory leaks into the panel's own strings, so it cannot read as evidence:
        // neither the UTR nor the printed payee name may appear there.
        val drawn = after.visibleStrings(UiLanguage.ENGLISH)
        assertFalse(drawn.any { it.contains("412345678901") })
        assertFalse(drawn.any { it.contains("Local Shop") })
    }

    @Test
    fun resultScreenConditionallyMountsTheCardAfterRiskAndBeforeConfidence() {
        val source = java.io.File(
            "src/main/java/ai/trustshield/android/ui/result/ResultScreen.kt"
        ).readText()
        val risk = source.indexOf("RiskHeader(risk = state.risk, language = language)")
        val conditional = source.indexOf("state.paymentAdvisory?.let { advisory ->")
        val card = source.indexOf("PaymentAdvisoryCard(advisory = advisory, language = language)")
        val confidence = source.indexOf("ConfidenceBadgeView(badge = state.confidence, language = language)")
        val evidence = source.indexOf("EvidenceSection(state = state, language = language)")

        assertTrue("risk header should precede payment advisory", risk >= 0 && conditional > risk)
        assertTrue("card should render inside the optional advisory branch", card > conditional)
        assertTrue("advisory should precede confidence", confidence > card)
        assertTrue("evidence should follow confidence", evidence > confidence)
    }

    @Test
    fun resultAdvisoryPathKeepsRawReceiptContentOutOfPanelCopy() {
        val rawUpi = "private.person.928374@okaxis"
        val rawUtr = "987654321098765"
        val rawName = "Private Customer Name"
        val rawAmount = "Rs 9,876"
        val rawTimestamp = "27 Oct 2026, 7:04 pm"
        val receipt = "Payment Successful. $rawAmount paid to $rawUpi ($rawName). UTR $rawUtr at $rawTimestamp."
        val result = ResultPresenter.build(
            input(source = receipt, evidence = listOf(evidenceItem(quote = "Payment Successful")))
        )
        val attached = advisory().copy(
            fields = PaymentFields(
                amountMinor = 987_600L,
                amountRaw = rawAmount,
                payeeUpi = rawUpi,
                payeeName = rawName,
                utr = rawUtr,
                status = PaymentStatus.SUCCESS,
                statusRaw = "Success",
                timestampRaw = rawTimestamp
            )
        )
        val state = result.copy(paymentAdvisory = attached)

        assertEquals("the Result path retains the advisory for its separate card", attached, state.paymentAdvisory)
        val panelCopy = state.visibleStrings(UiLanguage.ENGLISH) + state.screenDescription(UiLanguage.ENGLISH)
        listOf(rawUpi, rawUtr, rawName, rawAmount, rawTimestamp).forEach { raw ->
            assertFalse("raw payment field leaked into Result panel copy: $raw", panelCopy.any { it.contains(raw) })
        }
    }
}
