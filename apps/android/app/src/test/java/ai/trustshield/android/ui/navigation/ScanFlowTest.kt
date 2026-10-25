package ai.trustshield.android.ui.navigation

import ai.trustshield.android.core.analysis.LocalScanPipeline
import ai.trustshield.android.core.model.RiskLabel
import ai.trustshield.android.ui.result.ResultStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanFlowTest {

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
}
