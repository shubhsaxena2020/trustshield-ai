package ai.trustshield.android.core.analysis

import ai.trustshield.android.core.model.AnalysisStatus
import ai.trustshield.android.core.model.Confidence
import ai.trustshield.android.core.model.InputType
import ai.trustshield.android.core.model.NormalizedText
import ai.trustshield.android.core.model.RiskLabel
import ai.trustshield.android.core.model.StageId
import ai.trustshield.android.core.model.TextNormalizer
import ai.trustshield.android.core.normalize.HinglishNormalizer
import ai.trustshield.android.core.score.LocalScorer
import ai.trustshield.android.core.rules.LegitimateIndicators
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Package P3-02 gate (#81): one offline entry point that runs A02 normalization, A03 stage rules, A04
 * legitimate indicators and A05 fusion, and hands the UI a result that never leaks a fabricated quote.
 *
 * JVM unit tests only. That is the point: the pipeline is pure Kotlin, so every test below runs on a
 * plain JVM with no Android framework object, no device and no network. If the pipeline ever reached
 * for `Context`, a `Looper`, storage or a socket, these tests could not run at all.
 *
 * Fixtures are the report §6 digital-arrest script, the BEHAVIOR_SPEC protective-OTP twin, and the
 * coverage edge cases (empty, emoji-only, unsupported script, truncation, determinism, risk versus
 * confidence).
 */
class LocalScanPipelineTest {

    private val pipeline = LocalScanPipeline()

    // ---------------------------------------------------------------------------------------
    // Scam path: five-stage ladder, exact quotes
    // ---------------------------------------------------------------------------------------

    @Test
    fun `digital arrest message reaches high risk with the expected stages`() {
        val outcome = pipeline.analyzeText(DIGITAL_ARREST)

        assertEquals(RiskLabel.HIGH_RISK, outcome.risk.label)
        assertTrue("expected >= 70, was ${outcome.risk.score}", outcome.risk.score >= 70)
        assertEquals(
            listOf(StageId.AUTHORITY, StageId.THREAT_OR_REWARD, StageId.SECRECY, StageId.ASK),
            outcome.result.stages,
        )
        assertTrue("a clear OTP ask is a verified ask", outcome.verifiedAsk)
        assertTrue("ask + three corroborations must escalate", outcome.escalateCritical)
        assertTrue(outcome.result.actions.isNotEmpty())
        assertEquals(outcome.analyzedText, outcome.analyzedText.trim())
    }

    @Test
    fun `every evidence quote in a scam result is an exact substring of the analysed text`() {
        val outcome = pipeline.analyzeText(DIGITAL_ARREST)

        assertTrue("evidence must not be empty", outcome.result.evidence.isNotEmpty())
        assertEquals(
            "no evidence item may claim a quote the text does not contain",
            emptyList<Any>(),
            outcome.result.unverifiableEvidence(outcome.analyzedText),
        )
        assertEquals(outcome.result.evidence, outcome.result.verifiableEvidence(outcome.analyzedText))
        for (item in outcome.result.evidence) {
            val quote = item.quote ?: continue
            assertTrue(
                "quote '$quote' must be present verbatim",
                outcome.analyzedText.contains(quote),
            )
        }
    }

    // ---------------------------------------------------------------------------------------
    // Legitimate path: protective OTP notice
    // ---------------------------------------------------------------------------------------

    @Test
    fun `protective otp notice is not high risk and carries the legitimate indicator`() {
        val outcome = pipeline.analyzeText(LEGIT_OTP)

        assertNotEquals(RiskLabel.HIGH_RISK, outcome.risk.label)
        assertNotEquals(RiskLabel.SUSPICIOUS, outcome.risk.label)
        assertNotEquals(RiskLabel.UNABLE_TO_VERIFY, outcome.risk.label)
        assertEquals(RiskLabel.LOW_RISK, outcome.risk.label)
        assertTrue("expected < 25, was ${outcome.risk.score}", outcome.risk.score < 25)
        assertFalse("a warning is never an ask", outcome.verifiedAsk)
        assertFalse(outcome.escalateCritical)
        assertFalse(outcome.result.stages.contains(StageId.ASK))
        assertTrue(
            "the protective advice must be visible as evidence",
            outcome.result.evidence.any { it.module == LegitimateIndicators.MODULE },
        )
        assertTrue(outcome.result.unverifiableEvidence(outcome.analyzedText).isEmpty())
    }

    // ---------------------------------------------------------------------------------------
    // Unsupported / unreadable input must degrade, never say safe
    // ---------------------------------------------------------------------------------------

    @Test
    fun `empty input is unable to verify and never low risk`() {
        val outcome = pipeline.analyzeText("")

        assertEquals(RiskLabel.UNABLE_TO_VERIFY, outcome.risk.label)
        assertEquals(AnalysisStatus.UNABLE_TO_VERIFY, outcome.result.status)
        assertNotEquals(RiskLabel.LOW_RISK, outcome.risk.label)
        assertEquals(Confidence.LOW, outcome.risk.confidence)
        assertTrue(outcome.result.limitations.isNotEmpty())
        assertEquals("", outcome.analyzedText)
    }

    @Test
    fun `whitespace only input is unable to verify`() {
        val outcome = pipeline.analyzeText("   \n\t  ")

        assertEquals(RiskLabel.UNABLE_TO_VERIFY, outcome.risk.label)
        assertTrue(outcome.result.limitations.isNotEmpty())
        assertEquals("", outcome.analyzedText)
    }

    @Test
    fun `emoji only input is unable to verify and never low risk`() {
        val outcome = pipeline.analyzeText("😀😀😀")

        assertEquals(RiskLabel.UNABLE_TO_VERIFY, outcome.risk.label)
        assertTrue(
            "status must be unable or partial, was ${outcome.result.status}",
            outcome.result.status == AnalysisStatus.UNABLE_TO_VERIFY ||
                outcome.result.status == AnalysisStatus.PARTIAL,
        )
        assertTrue(outcome.result.limitations.isNotEmpty())
        assertNotEquals(RiskLabel.LOW_RISK, outcome.risk.label)
    }

    @Test
    fun `unsupported script is unable to verify and names the script`() {
        val outcome = pipeline.analyzeText(TAMIL_ONLY)

        assertEquals(RiskLabel.UNABLE_TO_VERIFY, outcome.risk.label)
        assertTrue(outcome.result.limitations.any {
            it.startsWith(LocalScorer.LIMIT_UNSUPPORTED_SCRIPT_PREFIX)
        })
        assertTrue(
            "the limitation must name the script, was ${outcome.result.limitations}",
            outcome.result.limitations.any { it.contains("Tamil") },
        )
        assertTrue(outcome.result.actions.isNotEmpty())
    }

    // ---------------------------------------------------------------------------------------
    // The input bound: 20 000 characters, surrogate-safe
    // ---------------------------------------------------------------------------------------

    @Test
    fun `long input reports truncation and the bound`() {
        val long = DIGITAL_ARREST + " " + PADDING
        assertTrue("fixture must exceed the bound", long.length > LocalScanPipeline.MAX_INPUT_CHARS)

        val outcome = pipeline.analyzeText(long)

        assertTrue(outcome.inputTruncated)
        assertEquals(LocalScanPipeline.MAX_INPUT_CHARS, outcome.maxInputChars)
        assertEquals(HinglishNormalizer.MAX_TEXT_CHARS, LocalScanPipeline.MAX_INPUT_CHARS)
        assertTrue(
            "the truncated window must be disclosed to the user",
            outcome.result.limitations.contains(LocalScorer.LIMIT_TRUNCATED),
        )
        // What the pipeline analysed must start with exactly what the UI would have sent.
        assertTrue(outcome.analyzedText.startsWith(pipeline.sanitizeInput(long)))
        // The scam still reads through the window: truncation lowers confidence, not the risk signal.
        assertEquals(RiskLabel.HIGH_RISK, outcome.risk.label)
    }

    @Test
    fun `sanitize input trims and caps at the bound`() {
        assertEquals("OTP batao", pipeline.sanitizeInput("   OTP batao \n\t "))
        assertEquals("", pipeline.sanitizeInput("   "))
        assertEquals(
            LocalScanPipeline.MAX_INPUT_CHARS,
            pipeline.sanitizeInput("b".repeat(LocalScanPipeline.MAX_INPUT_CHARS + 500)).length,
        )
        assertEquals(
            "short input must pass through untouched",
            "OTP batao",
            pipeline.sanitizeInput("OTP batao"),
        )
    }

    @Test
    fun `sanitize input never splits a surrogate pair`() {
        val raw = "a".repeat(LocalScanPipeline.MAX_INPUT_CHARS - 1) + "😀" + "tail"
        val sanitized = pipeline.sanitizeInput(raw)

        assertEquals(LocalScanPipeline.MAX_INPUT_CHARS - 1, sanitized.length)
        assertFalse(
            "a cut must not end on a lone high surrogate",
            Character.isHighSurrogate(sanitized[sanitized.length - 1]),
        )
        assertFalse(
            "the sanitized prefix must not contain a replacement character",
            sanitized.contains('\uFFFD'),
        )
    }

    @Test
    fun `isOverLimit mirrors the sanitize bound`() {
        assertFalse(pipeline.isOverLimit("OTP batao"))
        assertFalse(pipeline.isOverLimit("a".repeat(LocalScanPipeline.MAX_INPUT_CHARS)))
        assertTrue(pipeline.isOverLimit("a".repeat(LocalScanPipeline.MAX_INPUT_CHARS + 1)))
        assertFalse(
            "surrounding whitespace is trimmed before the bound applies",
            pipeline.isOverLimit("  " + "a".repeat(LocalScanPipeline.MAX_INPUT_CHARS) + "  "),
        )
        assertFalse(pipeline.analyzeText("OTP batao").inputTruncated)
    }

    // ---------------------------------------------------------------------------------------
    // Determinism and the risk/confidence split
    // ---------------------------------------------------------------------------------------

    @Test
    fun `analysis is deterministic for the same input`() {
        val first = pipeline.analyzeText(DIGITAL_ARREST)
        val second = pipeline.analyzeText(DIGITAL_ARREST)

        assertEquals(first.result, second.result)
        assertEquals(first.result.analysisId, second.result.analysisId)
        assertEquals(
            first.result.evidence.map { it.id },
            second.result.evidence.map { it.id },
        )
        assertEquals(first.analyzedText, second.analyzedText)
        assertEquals(first.escalateCritical, second.escalateCritical)
        assertEquals(first.verifiedAsk, second.verifiedAsk)
    }

    @Test
    fun `a second analysis does not change the first outcome`() {
        val first = pipeline.analyzeText(DIGITAL_ARREST)
        val snapshot = first.result
        pipeline.analyzeText(LEGIT_OTP)
        pipeline.analyzeText("")

        assertEquals(snapshot, first.result)
        assertEquals(RiskLabel.HIGH_RISK, first.risk.label)
    }

    @Test
    fun `truncation lowers confidence without moving score or label`() {
        val plain = pipeline.analyzeText(DIGITAL_ARREST)
        val truncated = pipeline.analyzeText(DIGITAL_ARREST + " " + PADDING)

        assertEquals(RiskLabel.HIGH_RISK, plain.risk.label)
        assertEquals(RiskLabel.HIGH_RISK, truncated.risk.label)
        assertEquals(
            "the risk signal must not change because coverage dropped",
            plain.risk.score,
            truncated.risk.score,
        )
        assertEquals(Confidence.HIGH, plain.risk.confidence)
        assertEquals(Confidence.MEDIUM, truncated.risk.confidence)
        assertNotEquals(plain.risk.confidence, truncated.risk.confidence)
        assertFalse(plain.inputTruncated)
        assertTrue(truncated.inputTruncated)
    }

    @Test
    fun `a very short ask is judged the same but with lower confidence`() {
        val short = pipeline.analyzeText("OTP batao")
        val longer = pipeline.analyzeText("Bhai please jo OTP aaya hai woh mujhe batao")

        assertEquals(short.risk.label, longer.risk.label)
        assertEquals(short.risk.score, longer.risk.score)
        assertEquals(Confidence.MEDIUM, short.risk.confidence)
        assertEquals(Confidence.HIGH, longer.risk.confidence)
        assertNotEquals(RiskLabel.LOW_RISK, short.risk.label)
    }

    // ---------------------------------------------------------------------------------------
    // Image (OCR) path and the input-type contract
    // ---------------------------------------------------------------------------------------

    @Test
    fun `image text reports the image input type and keeps the verdict`() {
        val typed = pipeline.analyzeText(DIGITAL_ARREST)
        val ocr = pipeline.analyzeImageText(DIGITAL_ARREST)

        assertEquals(InputType.TEXT, typed.result.inputType)
        assertEquals(InputType.IMAGE, ocr.result.inputType)
        assertEquals(typed.risk.label, ocr.risk.label)
        assertEquals(typed.risk.score, ocr.risk.score)
        assertEquals(typed.risk.confidence, ocr.risk.confidence)
        assertEquals(typed.result.stages, ocr.result.stages)
        assertEquals(typed.verifiedAsk, ocr.verifiedAsk)
        assertEquals(typed.escalateCritical, ocr.escalateCritical)
        assertEquals(typed.analyzedText, ocr.analyzedText)
        assertTrue(ocr.result.unverifiableEvidence(ocr.analyzedText).isEmpty())
    }

    @Test
    fun `image text degrades exactly like typed text`() {
        val typed = pipeline.analyzeText(TAMIL_ONLY)
        val ocr = pipeline.analyzeImageText(TAMIL_ONLY)

        assertEquals(RiskLabel.UNABLE_TO_VERIFY, ocr.risk.label)
        assertEquals(typed.result.limitations, ocr.result.limitations)
        assertEquals(InputType.IMAGE, ocr.result.inputType)
    }

    // ---------------------------------------------------------------------------------------
    // Plain-JVM / no-retention proof
    // ---------------------------------------------------------------------------------------

    @Test
    fun `an outcome is produced on a plain jvm with no android framework object`() {
        // This test class is a JVM unit test: android.jar stubs throw when touched, and no Context,
        // Looper, file, clock or socket is available to the pipeline. Producing a finished outcome
        // here is the behavioural proof that the entry point is framework-free and offline.
        val outcome = pipeline.analyzeText(DIGITAL_ARREST)

        assertEquals(RiskLabel.HIGH_RISK, outcome.risk.label)
        assertTrue(outcome.result.analysisId.isNotBlank())
        assertEquals(ai.trustshield.android.core.model.DataOrigin.LOCAL, outcome.result.origin)
        assertEquals(ai.trustshield.android.core.model.BackendStatus.DISABLED, outcome.result.backend)
        assertTrue(outcome.result.unverifiableEvidence(outcome.analyzedText).isEmpty())
    }

    @Test
    fun `the pipeline keeps no reference to the analysed text`() {
        val instance = LocalScanPipeline()
        instance.analyzeText(DIGITAL_ARREST)

        val textFields = LocalScanPipeline::class.java.declaredFields
            .filter { CharSequence::class.java.isAssignableFrom(it.type) }
            .map { it.name }
        assertTrue(
            "the pipeline must not cache raw input, found $textFields",
            textFields.isEmpty(),
        )
    }

    @Test
    fun `helper predicates describe the input not the verdict`() {
        assertFalse(pipeline.isAnalysable(""))
        assertFalse(pipeline.isAnalysable("   \n\t "))
        assertTrue(pipeline.isAnalysable("OTP batao"))
        // Emoji-only text is present but unreadable: the helper does not litigate the verdict, the
        // scorer does, and it says unable-to-verify (tested above).
        assertTrue(pipeline.isAnalysable("😀"))
        assertTrue(pipeline.isAnalysable("a".repeat(LocalScanPipeline.MAX_INPUT_CHARS + 10)))
    }

    // ---------------------------------------------------------------------------------------
    // Injectability (a package test may swap the normalizer; the pipeline must still be usable)
    // ---------------------------------------------------------------------------------------

    @Test
    fun `an injected normalizer is supported`() {
        val identity = TextNormalizer { NormalizedText.identity(it) }
        val fake = LocalScanPipeline(normalizer = identity)

        val outcome = fake.analyzeText(DIGITAL_ARREST)
        assertEquals(RiskLabel.HIGH_RISK, outcome.risk.label)
        assertTrue(outcome.result.unverifiableEvidence(outcome.analyzedText).isEmpty())
    }

    @Test
    fun `sensitivity presets are accepted and stay inside the documented bounds`() {
        val low = LocalScanPipeline(sensitivity = LocalScorer.Sensitivity.LOW)
        val standard = LocalScanPipeline(sensitivity = LocalScorer.Sensitivity.STANDARD)
        val high = LocalScanPipeline(sensitivity = LocalScorer.Sensitivity.HIGH)

        val cadence = listOf(low, standard, high).map { it.analyzeText(DIGITAL_ARREST) }
        for (outcome in cadence) {
            assertEquals(RiskLabel.HIGH_RISK, outcome.risk.label)
        }
        assertNotEquals(cadence[0].result.analysisId, cadence[1].result.analysisId)
        assertNotEquals(cadence[1].result.analysisId, cadence[2].result.analysisId)
    }

    private companion object {
        /** report §6 digital-arrest script: authority -> drugs parcel -> secrecy -> OTP ask. */
        const val DIGITAL_ARREST = "Main Mumbai Cyber Crime se Inspector Rathore bol raha hoon. " +
            "Aapke Aadhaar se bheje gaye parcel mein drugs mile hain. " +
            "Yeh confidential case hai, kisi ko mat batana. " +
            "Jo OTP aaya hai woh batao."

        /** BEHAVIOR_SPEC §"Negation and legitimate pairs" protective twin. */
        const val LEGIT_OTP = "123456 is your OTP for your SBI account. " +
            "Do not share it with anyone. -SBI"

        /** Unsupported script: the local rules can read Roman and Devanagari only. */
        const val TAMIL_ONLY = "உங்கள் கணக்கு முடக்கப்படும், விவரங்களை அனுப்புங்கள்"

        /** Filler that adds no stage, used to push the input past the 20 000-character bound. */
        val PADDING: String = "KYC update karein. ".repeat(1_200)
    }
}
