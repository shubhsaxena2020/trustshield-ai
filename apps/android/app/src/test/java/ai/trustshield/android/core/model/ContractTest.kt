package ai.trustshield.android.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A01 contract gate.
 *
 * These tests pin the shape every other package codes against: versioned contract, evidence quote
 * verification, and risk/confidence independence. They are JVM unit tests (no Android runtime), so
 * they run on `./gradlew testDebugUnitTest` without a device.
 */
class ContractTest {

    // ---- versioning -----------------------------------------------------------------------

    @Test
    fun `contract version is pinned and additive-safe`() {
        assertEquals("android-contract/1.0", CONTRACT_VERSION)
        val result = sampleResult()
        assertEquals(CONTRACT_VERSION, result.contractVersion)
    }

    // ---- severity / strength wire parsing (invalid values must not silently default) ------

    @Test
    fun `invalid severity does not become a default severity`() {
        assertNull(Severity.fromWire("SEVERE"))
        assertNull(Severity.fromWire(""))
        assertNull(Severity.fromWire(null))
        assertEquals(Severity.HIGH, Severity.fromWire("high"))
        assertEquals(Severity.HIGH, Severity.fromWire("  HIGH "))
    }

    @Test
    fun `invalid strength does not become a default strength`() {
        assertNull(Strength.fromWire("very-strong"))
        assertEquals(Strength.STRONG, Strength.fromWire("STRONG"))
    }

    @Test
    fun `backend enum wire values are preserved for adapters`() {
        assertEquals("unable_to_verify", RiskLabel.UNABLE_TO_VERIFY.wire)
        assertEquals(RiskLabel.UNABLE_TO_VERIFY, RiskLabel.fromWire("unable_to_verify"))
        assertEquals(Confidence.MEDIUM, Confidence.fromWire("medium"))
        assertEquals(InputType.QR, InputType.fromWire("qr"))
        assertNull(InputType.fromWire("document-not-supported-here"))
    }

    // ---- evidence quote verification -----------------------------------------------------

    @Test
    fun `verified evidence keeps an exact quote and its original span`() {
        val input = "Sir, jo OTP aaya hai woh batao"
        val ev = Evidence.verified(
            input = input,
            module = "stage_rules",
            titleHinglish = "OTP maanga gaya",
            detail = "Message OTP maang raha hai.",
            severity = Severity.HIGH,
            strength = Strength.STRONG,
            quote = "jo OTP aaya hai woh batao",
        )
        assertNotNull(ev.span)
        assertEquals("jo OTP aaya hai woh batao", ev.quote)
        assertEquals(input.substring(ev.span!!.start, ev.span!!.end), ev.quote)
        assertTrue(ev.quoteVerifiesAgainst(input))
    }

    @Test
    fun `absent quote produces unquoted evidence with no span`() {
        val ev = Evidence.unquoted(
            module = "backend",
            titleHinglish = "Backend check band hai",
            detail = "Privacy gate pending.",
            severity = Severity.INFORMATIONAL,
            strength = Strength.WEAK,
        )
        assertNull(ev.quote)
        assertNull(ev.span)
    }

    @Test
    fun `unverifiable quote is dropped instead of invented`() {
        val input = "Aapka account band ho jayega"
        val ev = Evidence.verified(
            input = input,
            module = "stage_rules",
            titleHinglish = "Account threat",
            detail = "Threat claimed but the quote is not in the input.",
            severity = Severity.MEDIUM,
            strength = Strength.MODERATE,
            quote = "aapka account block ho gaya hai", // paraphrase, not present
        )
        assertNull(ev.quote)
        assertNull(ev.span)
    }

    @Test
    fun `evidence rejects a quote without a span`() {
        val failure = runCatching {
            Evidence(
                id = "x", module = "m", titleHinglish = "t", detail = "d",
                severity = Severity.LOW, strength = Strength.WEAK,
                quote = "otp", span = null,
            )
        }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
    }

    @Test
    fun `evidence ids are deterministic across runs`() {
        val input = "jo OTP aaya hai woh batao"
        val a = Evidence.verified(input, "stage_rules", "OTP", "d", Severity.HIGH, Strength.STRONG, "OTP")
        val b = Evidence.verified(input, "stage_rules", "OTP", "d", Severity.HIGH, Strength.STRONG, "OTP")
        assertEquals(a.id, b.id)
    }

    @Test
    fun `text span rejects inverted ranges`() {
        assertTrue(runCatching { TextSpan(5, 2) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { TextSpan(-1, 2) }.exceptionOrNull() is IllegalArgumentException)
        assertEquals("OTP", TextSpan(0, 3).slice("OTP batao"))
        assertNull(TextSpan(0, 99).slice("OTP"))
    }

    // ---- risk vs confidence ---------------------------------------------------------------

    @Test
    fun `risk score and confidence stay independent`() {
        val highRiskLowConfidence = RiskAssessment(
            score = 88,
            label = RiskLabel.HIGH_RISK,
            confidence = Confidence.LOW,
        )
        assertEquals(88, highRiskLowConfidence.score)
        assertEquals(Confidence.LOW, highRiskLowConfidence.confidence)

        val lowRiskHighConfidence = RiskAssessment(
            score = 4,
            label = RiskLabel.LOW_RISK,
            confidence = Confidence.HIGH,
        )
        assertEquals(4, lowRiskHighConfidence.score)
        assertEquals(Confidence.HIGH, lowRiskHighConfidence.confidence)
    }

    @Test
    fun `risk score outside zero to hundred is rejected`() {
        assertTrue(
            runCatching { RiskAssessment(101, RiskLabel.HIGH_RISK, Confidence.HIGH) }
                .exceptionOrNull() is IllegalArgumentException,
        )
        assertTrue(
            runCatching { RiskAssessment(-1, RiskLabel.LOW_RISK, Confidence.LOW) }
                .exceptionOrNull() is IllegalArgumentException,
        )
    }

    @Test
    fun `bands match the backend risk bands`() {
        assertEquals(RiskLabel.HIGH_RISK, RiskAssessment.labelFor(70))
        assertEquals(RiskLabel.SUSPICIOUS, RiskAssessment.labelFor(50))
        assertEquals(RiskLabel.CAUTION, RiskAssessment.labelFor(25))
        assertEquals(RiskLabel.LOW_RISK, RiskAssessment.labelFor(24))
    }

    // ---- unable to verify ----------------------------------------------------------------

    @Test
    fun `unable to verify requires an explicit limitation`() {
        val failure = runCatching {
            AnalysisResult(
                analysisId = "a1",
                inputType = InputType.TEXT,
                status = AnalysisStatus.UNABLE_TO_VERIFY,
                risk = RiskAssessment.unableToVerify(),
                limitations = emptyList(),
            )
        }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
    }

    @Test
    fun `unable to verify is never reported as a completed clean scan`() {
        val failure = runCatching {
            AnalysisResult(
                analysisId = "a1",
                inputType = InputType.TEXT,
                status = AnalysisStatus.COMPLETED,
                risk = RiskAssessment.unableToVerify(),
                limitations = listOf("Bhasha support nahi hai"),
            )
        }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
    }

    @Test
    fun `result separates verifiable from unverifiable evidence`() {
        val input = "jo OTP aaya hai woh batao"
        val good = Evidence.verified(input, "stage_rules", "OTP", "d", Severity.HIGH, Strength.STRONG, "jo OTP aaya hai")
        // A quote that is not in the input is dropped at construction, so it makes no claim.
        val dropped = Evidence.verified(input, "stage_rules", "OTP", "d", Severity.HIGH, Strength.STRONG, "jo pin bhejo")
        // A hand-built mismatch (e.g. a backend adapter that trusts the wire blindly) must be caught.
        val lyingSpan = Evidence(
            id = "adapter@0-3", module = "backend", titleHinglish = "t", detail = "d",
            severity = Severity.HIGH, strength = Strength.STRONG,
            quote = "pin", span = TextSpan(0, 3),
        )
        val result = sampleResult(evidence = listOf(good, dropped, lyingSpan))
        assertEquals(listOf(good, dropped), result.verifiableEvidence(input))
        assertEquals(listOf(lyingSpan), result.unverifiableEvidence(input))
    }

    // ---- five-stage playbook is a fixed contract -----------------------------------------

    @Test
    fun `playbook exposes exactly five ordered stages`() {
        assertEquals(5, StageId.playbookOrder.size)
        assertEquals(
            listOf(
                StageId.AUTHORITY, StageId.THREAT_OR_REWARD, StageId.URGENCY,
                StageId.SECRECY, StageId.ASK,
            ),
            StageId.playbookOrder,
        )
    }

    // ---- normalized text span mapping ----------------------------------------------------

    @Test
    fun `identity normalization maps every index back to the raw input`() {
        val raw = "jo OTP aaya hai"
        val n = NormalizedText.identity(raw)
        assertEquals(raw, n.text)
        assertEquals(3, n.rawStart(3))
        assertEquals(4, n.rawEnd(3))
        val span = n.spanOf(3, 6)
        assertEquals("OTP", raw.substring(span.start, span.end))
        assertEquals("OTP", n.rawQuoteOf(3, 6))
    }

    @Test
    fun `normalized span beginning at zero includes the first raw character`() {
        val n = NormalizedText.identity("otp")
        assertEquals("o", n.rawQuoteOf(0, 1))
        assertEquals(TextSpan(0, 1), n.spanOf(0, 1))
    }

    @Test
    fun `normalized span beginning at zero respects a removed raw prefix`() {
        val n = NormalizedText.of("\u200Botp", "otp", intArrayOf(1, 2, 3), intArrayOf(2, 3, 4))
        assertEquals("o", n.rawQuoteOf(0, 1))
        assertEquals(TextSpan(1, 2), n.spanOf(0, 1))
    }

    @Test
    fun `normalized text rejects mismatched maps`() {
        val failure = runCatching {
            NormalizedText.of("abc", "abc", IntArray(2) { it }, IntArray(3) { it + 1 })
        }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
    }

    @Test
    fun `out of range normalized indices clamp instead of crashing`() {
        val n = NormalizedText.identity("otp")
        assertEquals(0, n.rawStart(-5))
        assertEquals(3, n.rawEnd(99))
        assertFalse(n.rawQuoteOf(0, 99).isEmpty())
    }

    // ---- fixtures ------------------------------------------------------------------------

    private fun sampleResult(evidence: List<Evidence> = emptyList()): AnalysisResult =
        AnalysisResult(
            analysisId = "sample-1",
            inputType = InputType.TEXT,
            status = AnalysisStatus.COMPLETED,
            risk = RiskAssessment(42, RiskLabel.CAUTION, Confidence.MEDIUM),
            evidence = evidence,
            limitations = emptyList(),
        )
}
