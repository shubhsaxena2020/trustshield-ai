package ai.trustshield.android.ui.navigation

import ai.trustshield.android.core.model.Evidence
import ai.trustshield.android.core.model.Severity
import ai.trustshield.android.core.model.Strength
import ai.trustshield.android.core.score.LocalScorer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class LocalEvidenceAdapterTest {
    @Test
    fun englishResultEvidenceUsesEnglishTitleAndDetail() {
        val evidence = Evidence.unquoted(
            module = "stage_rules",
            titleHinglish = "Jaldbaazi (time pressure)",
            detail = "Message turant act karne ka dabaav banata hai.",
            severity = Severity.LOW,
            strength = Strength.WEAK,
        )

        val result = LocalEvidenceAdapter.evidence(evidence)

        assertEquals("Urgency and time pressure", result.titleEnglish)
        assertEquals(
            "The message pressures you to act immediately. Urgency alone is not proof of a scam.",
            result.detailEnglish,
        )
        assertNotEquals(result.titleHinglish, result.titleEnglish)
        assertNotEquals(result.detailHinglish, result.detailEnglish)
    }

    @Test
    fun utilityBillRuleGetsASpecificEvidenceTitle() {
        val evidence = Evidence.unquoted(
            module = "stage_rules",
            titleHinglish = "Dhamki ke saath bill update karne ko kaha",
            detail = "Bill update karne ko kaha gaya.",
            severity = Severity.HIGH,
            strength = Strength.STRONG,
        )

        assertEquals(
            "Utility bill update requested with disconnection threat",
            LocalEvidenceAdapter.evidence(evidence).titleEnglish
        )
    }

    @Test
    fun unmappedEvidenceKeepsItsSpecificReasonInsteadOfGenericCopy() {
        val evidence = Evidence.unquoted(
            module = "local_fusion",
            titleHinglish = "Unknown sender pattern",
            detail = "The sender asks you to verify a payment through an unfamiliar channel.",
            severity = Severity.MODERATE,
            strength = Strength.MODERATE,
        )

        val result = LocalEvidenceAdapter.evidence(evidence)

        assertEquals("Unknown sender pattern", result.titleEnglish)
        assertEquals("The sender asks you to verify a payment through an unfamiliar channel.", result.detailEnglish)
    }

    @Test
    fun englishResultActionUsesEnglishCopy() {
        val result = LocalEvidenceAdapter.action(LocalScorer.ACTION_HIGH_STOP, 0)

        assertEquals("Stop for now. Do not share an OTP, PIN, password, or money.", result.labelEnglish)
        assertNotEquals(result.labelHinglish, result.labelEnglish)
        assertNotEquals(result.detailHinglish, result.detailEnglish)
    }

    @Test
    fun englishResultLimitationsUseEnglishCopy() {
        assertEquals(
            "No text was available to check.",
            LocalEvidenceAdapter.limitation(LocalScorer.LIMIT_NO_TEXT),
        )
        assertEquals(
            "This language is not fully supported yet: Tamil",
            LocalEvidenceAdapter.limitation(LocalScorer.LIMIT_UNSUPPORTED_SCRIPT_PREFIX + "Tamil"),
        )
    }
}
