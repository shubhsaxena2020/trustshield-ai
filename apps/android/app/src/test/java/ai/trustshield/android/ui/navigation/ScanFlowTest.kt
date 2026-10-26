package ai.trustshield.android.ui.navigation

import ai.trustshield.android.ResultOutcomeHolder
import ai.trustshield.android.core.analysis.LocalScanPipeline
import ai.trustshield.android.core.model.RiskLabel
import ai.trustshield.android.ui.result.AbsoluteSafetyGuard
import ai.trustshield.android.ui.result.ResultStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanFlowTest {

    /** Synthetic scam message: a KYC deadline plus a direct OTP ask. Nothing here is real data. */
    private val scamMessage = "SBI: Aapka KYC 24 ghante mein band ho jayega. Turant is link se KYC " +
        "update karein aur jo OTP aaya hai woh batao. OTP 839201."

    /** Synthetic legitimate message: a bank OTP with no ask. */
    private val legitMessage = "123456 is your OTP for txn of Rs 500. Do not share it with anyone. -SBI"

    private fun scamOutcome() = LocalScanPipeline().analyzeText(scamMessage)

    @Test
    fun sbiKycOtpScamFlowsThroughTheLocalEngineWithVerifiableEvidence() {
        val text = "SBI: Aapka KYC 24 ghante mein band ho jayega. Turant is link se KYC update " +
            "karein aur jo OTP aaya hai woh batao."
        val outcome = LocalScanPipeline().analyzeText(text)
        val ui = AnalysisToResult.build(outcome)

        assertTrue(outcome.result.risk.label in setOf(RiskLabel.SUSPICIOUS, RiskLabel.HIGH_RISK))
        assertEquals(text, outcome.analyzedText)
        assertTrue(outcome.result.unverifiableEvidence(text).isEmpty())
        assertTrue(ui.status is ResultStatus.Verified)
        assertTrue(ui.evidence.all { text.contains(it.quote.text.removeSuffix("…")) })
        assertTrue(ui.limitations.isNotEmpty())
    }

    @Test
    fun legitimateBankOtpStaysLowRiskAndDoesNotInventEvidence() {
        val text = "123456 is your OTP for txn of Rs 500. Do not share it with anyone. -SBI"
        val outcome = LocalScanPipeline().analyzeText(text)
        val ui = AnalysisToResult.build(outcome)

        assertEquals(RiskLabel.LOW_RISK, outcome.result.risk.label)
        assertTrue(outcome.result.unverifiableEvidence(text).isEmpty())
        assertFalse(ui.status is ResultStatus.UnableToVerify)
        assertEquals(outcome.result.risk.confidence.name, ui.confidence.level.name)
    }

    // ---------------------------------------------------------------------------------------------
    // Result route: no outcome is an honest state, never a blank screen (issue #85, defect B)
    // ---------------------------------------------------------------------------------------------

    @Test
    fun resultRouteViewIsEmptyOnlyWhenThereIsNoOutcome() {
        assertEquals(ResultRouteView.Empty, resultRouteView(null))

        val outcome = scamOutcome()
        val view = resultRouteView(outcome)
        assertTrue(view is ResultRouteView.Loaded)
        assertEquals(outcome, (view as ResultRouteView.Loaded).outcome)
        // The return value must carry the real analysed outcome, not a copy or a fixture.
        assertEquals(scamMessage, view.outcome.analyzedText)
    }

    @Test
    fun emptyResultCopyExistsInBothLanguagesAndNamesTheNextStep() {
        val hinglish = ResultEmptyCopy.HINGLISH
        val english = ResultEmptyCopy.ENGLISH

        assertTrue(hinglish.isNotBlank())
        assertTrue(english.isNotBlank())
        assertNotEquals(hinglish, english)

        // Selected by the same language flag every other screen uses.
        assertEquals(hinglish, ResultEmptyCopy.text(english = false))
        assertEquals(english, ResultEmptyCopy.text(english = true))

        // Says what happened (no scan) and what to do next (check a message again from Home).
        assertTrue(hinglish.contains("scan", ignoreCase = true))
        assertTrue(english.contains("scan", ignoreCase = true))
        assertTrue(hinglish.contains("Home"))
        assertTrue(english.contains("Home"))

        // The empty state has a real control with a label in both languages, and each label pairs
        // with the copy in the same language (a swapped branch would show Hinglish copy with an
        // English button, which the emulator evidence caught the first time round).
        assertEquals(ResultEmptyCopy.ACTION_HINGLISH, ResultEmptyCopy.action(english = false))
        assertEquals(ResultEmptyCopy.ACTION_ENGLISH, ResultEmptyCopy.action(english = true))
        assertNotEquals(
            ResultEmptyCopy.action(english = false),
            ResultEmptyCopy.action(english = true)
        )
        assertTrue(ResultEmptyCopy.action(english = false).isNotBlank())
        assertTrue(ResultEmptyCopy.action(english = true).isNotBlank())

        // Never an absolute-safety claim, in either language.
        assertTrue(AbsoluteSafetyGuard.violations(hinglish).isEmpty())
        assertTrue(AbsoluteSafetyGuard.violations(english).isEmpty())
        assertTrue(AbsoluteSafetyGuard.isClean(ResultEmptyCopy.action(english = false)))
        assertTrue(AbsoluteSafetyGuard.isClean(ResultEmptyCopy.action(english = true)))
    }

    // ---------------------------------------------------------------------------------------------
    // Activity-scoped outcome holder: survives rotation in RAM, never accumulates, never prints
    // ---------------------------------------------------------------------------------------------

    @Test
    fun outcomeHolderKeepsOnlyTheLatestScanAndClearsToNothing() {
        val holder = ResultOutcomeHolder()
        assertNull(holder.outcome)

        val first = scamOutcome()
        holder.outcome = first
        assertEquals(first, holder.outcome)

        // A new scan replaces the previous result: one slot, no history.
        val second = LocalScanPipeline().analyzeText(legitMessage)
        holder.outcome = second
        assertEquals(second, holder.outcome)
        assertNotEquals(first, holder.outcome)

        holder.clear()
        assertNull(holder.outcome)
    }

    @Test
    fun outcomeHolderStoresNoRawTextFieldOfItsOwn() {
        // ScanOutcome.analyzedText is the only copy of the input; the holder must not add a second
        // String field that could outlive it.
        val declaredStringFields = ResultOutcomeHolder::class.java.declaredFields
            .filter { it.type == String::class.java }
        assertTrue(declaredStringFields.isEmpty())
    }

    @Test
    fun outcomeHolderNeverPrintsAnalysedContent() {
        val holder = ResultOutcomeHolder()
        holder.outcome = scamOutcome()

        val printed = holder.toString()
        assertFalse(printed.contains("839201"))
        assertFalse(printed.contains("KYC"))
        assertFalse(printed.contains(scamMessage))
    }

    // ---------------------------------------------------------------------------------------------
    // Re-entry contract: one intent is applied once (unchanged, pinned here)
    // ---------------------------------------------------------------------------------------------

    @Test
    fun oneIntentEntryIsAppliedExactlyOnce() {
        assertTrue(shouldApplyIntentEntry(AppRoute.RESULT, requestSequence = 3, lastAppliedSequence = 2))
        assertFalse(shouldApplyIntentEntry(AppRoute.RESULT, requestSequence = 3, lastAppliedSequence = 3))
        assertFalse(shouldApplyIntentEntry(null, requestSequence = 4, lastAppliedSequence = 3))
        assertEquals(
            NavBackStack.of(AppRoute.HOME, AppRoute.RESULT),
            NavBackStack.fromIntentEntry(AppRoute.RESULT.wireName)
        )
    }
}
