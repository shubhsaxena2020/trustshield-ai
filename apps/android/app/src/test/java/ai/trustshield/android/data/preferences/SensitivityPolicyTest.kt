package ai.trustshield.android.data.preferences

import ai.trustshield.android.core.model.RiskAssessment
import ai.trustshield.android.core.model.RiskLabel
import ai.trustshield.android.core.model.TextNormalizer
import ai.trustshield.android.core.normalize.HinglishNormalizer
import ai.trustshield.android.core.score.LocalScorer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Gate for work package P3-16 (issue #95): the persisted sensitivity preference must actually move
 * the local deterministic scorer, inside documented bounds.
 *
 * These are JVM unit tests against the **real** A05 engine (`core/score/LocalScorer.kt`) and the real
 * A02 normalizer — no fakes for the parts that decide the verdict, because the whole point of this
 * package is that the setting stops being decorative. Fakes would let the test pass while the app
 * still scored everything identically.
 *
 * Fixtures are the report §3.4 / §6 legitimate-scam pairs, kept as masked strings (no phone number,
 * UPI id or real OTP) because nothing here may look like personal data even in a test file.
 */
class SensitivityPolicyTest {

    private val normalizer: TextNormalizer = HinglishNormalizer()

    // ---------------------------------------------------------------------------------------
    // Mapping: persisted preset -> A05 engine preset
    // ---------------------------------------------------------------------------------------

    @Test
    fun `persisted preset maps to the documented engine preset`() {
        assertEquals(LocalScorer.Sensitivity.LOW, SensitivityScoring.localPreset(Sensitivity.LOW))
        assertEquals(LocalScorer.Sensitivity.STANDARD, SensitivityScoring.localPreset(Sensitivity.BALANCED))
        assertEquals(LocalScorer.Sensitivity.HIGH, SensitivityScoring.localPreset(Sensitivity.HIGH))
    }

    @Test
    fun `the mapping is a bijection so no two presets share engine behaviour`() {
        val mapped = Sensitivity.entries.map { SensitivityScoring.localPreset(it) }
        assertEquals("each persisted preset must own a distinct engine preset", mapped.size, mapped.toSet().size)
        assertEquals(LocalScorer.Sensitivity.entries.size, mapped.toSet().size)
    }

    @Test
    fun `scorer built from a preset carries that preset`() {
        for (sensitivity in Sensitivity.entries) {
            val scorer = SensitivityScoring.scorer(sensitivity, normalizer)
            assertEquals(SensitivityScoring.localPreset(sensitivity), scorer.sensitivity)
        }
    }

    @Test
    fun `scorer defaults to the Hinglish normalizer so a caller needs only the stored preset`() {
        // The one-line integration seam: SensitivityScoring.scorer(prefs.sensitivity).
        val scorer = SensitivityScoring.scorer(Sensitivity.BALANCED)
        val expected = LocalScorer(HinglishNormalizer())
        assertEquals(expected.analyze(ARREST_LADDER), scorer.analyze(ARREST_LADDER))
    }

    // ---------------------------------------------------------------------------------------
    // Threshold bounds (C04's documented table, consumed not redefined)
    // ---------------------------------------------------------------------------------------

    @Test
    fun `every preset stays inside the documented threshold bounds`() {
        for (sensitivity in Sensitivity.entries) {
            assertTrue("${sensitivity.name} is outside bounds", SensitivityPolicy.isWithinBounds(sensitivity))
        }
    }

    @Test
    fun `thresholds are ordered strictly from LOW to HIGH`() {
        val low = SensitivityPolicy.thresholds(Sensitivity.LOW)
        val balanced = SensitivityPolicy.thresholds(Sensitivity.BALANCED)
        val high = SensitivityPolicy.thresholds(Sensitivity.HIGH)

        assertTrue("LOW.cautionAt > BALANCED.cautionAt", low.cautionAt > balanced.cautionAt)
        assertTrue("BALANCED.cautionAt > HIGH.cautionAt", balanced.cautionAt > high.cautionAt)
        assertTrue("LOW.highRiskAt > BALANCED.highRiskAt", low.highRiskAt > balanced.highRiskAt)
        assertTrue("BALANCED.highRiskAt > HIGH.highRiskAt", balanced.highRiskAt > high.highRiskAt)
    }

    @Test
    fun `the strong ask floor sits above every caution cut`() {
        // This is the arithmetic behind "a verified strong ask is never presented as safe".
        for (sensitivity in Sensitivity.entries) {
            assertTrue(
                "${sensitivity.name}: floor ${SensitivityPolicy.STRONG_ASK_FLOOR_SCORE} must clear " +
                    "cautionAt ${SensitivityPolicy.thresholds(sensitivity).cautionAt}",
                SensitivityPolicy.STRONG_ASK_FLOOR_SCORE >= SensitivityPolicy.thresholds(sensitivity).cautionAt,
            )
        }
    }

    // ---------------------------------------------------------------------------------------
    // Presentation band mapping
    // ---------------------------------------------------------------------------------------

    @Test
    fun `band mapping clamps the score into the contract range`() {
        assertEquals(0, SensitivityScoring.presentedScore(-40, hasVerifiedStrongAsk = false))
        assertEquals(100, SensitivityScoring.presentedScore(400, hasVerifiedStrongAsk = false))
        assertEquals(
            SensitivityPolicy.STRONG_ASK_FLOOR_SCORE,
            SensitivityScoring.presentedScore(0, hasVerifiedStrongAsk = true),
        )
    }

    @Test
    fun `verified strong ask is never presented as low risk at any preset`() {
        for (sensitivity in Sensitivity.entries) {
            for (score in 0..100) {
                val band = SensitivityScoring.bandFor(sensitivity, score, hasVerifiedStrongAsk = true)
                assertNotEquals(
                    "${sensitivity.name} turned a verified strong ask at $score into $band",
                    RiskLabel.LOW_RISK,
                    band,
                )
                assertNotEquals(
                    "${sensitivity.name} turned a verified strong ask at $score into unable to verify",
                    RiskLabel.UNABLE_TO_VERIFY,
                    band,
                )
            }
        }
    }

    @Test
    fun `band is monotone in sensitivity for a fixed score`() {
        for (score in 0..100) {
            for (ask in listOf(false, true)) {
                val low = rank(SensitivityScoring.bandFor(Sensitivity.LOW, score, ask))
                val balanced = rank(SensitivityScoring.bandFor(Sensitivity.BALANCED, score, ask))
                val high = rank(SensitivityScoring.bandFor(Sensitivity.HIGH, score, ask))
                assertTrue("score $score ask=$ask: HIGH $high < BALANCED $balanced", high >= balanced)
                assertTrue("score $score ask=$ask: BALANCED $balanced < LOW $low", balanced >= low)
            }
        }
    }

    @Test
    fun `low risk never appears first at a higher sensitivity for a fixed score`() {
        // LOW_RISK at HIGH implies LOW_RISK at BALANCED implies LOW_RISK at LOW - never the reverse.
        for (score in 0..100) {
            for (ask in listOf(false, true)) {
                val low = SensitivityScoring.bandFor(Sensitivity.LOW, score, ask) == RiskLabel.LOW_RISK
                val balanced = SensitivityScoring.bandFor(Sensitivity.BALANCED, score, ask) == RiskLabel.LOW_RISK
                val high = SensitivityScoring.bandFor(Sensitivity.HIGH, score, ask) == RiskLabel.LOW_RISK
                assertTrue("score $score ask=$ask: HIGH said safe but BALANCED did not", !high || balanced)
                assertTrue("score $score ask=$ask: BALANCED said safe but LOW did not", !balanced || low)
            }
        }
    }

    @Test
    fun `band mapping agrees with the contract bands at the balanced default where it can`() {
        // BALANCED keeps A01's 70 high-risk line, so high risk never disagrees with the contract.
        assertEquals(RiskLabel.HIGH_RISK, SensitivityScoring.bandFor(Sensitivity.BALANCED, 70, false))
        assertEquals(RiskAssessment.labelFor(70), SensitivityScoring.bandFor(Sensitivity.BALANCED, 70, false))
    }

    // ---------------------------------------------------------------------------------------
    // The real engine, per preset
    // ---------------------------------------------------------------------------------------

    @Test
    fun `scores are monotone in sensitivity for every fixture`() {
        for (fixture in FIXTURES) {
            val low = scoreAt(fixture.text, Sensitivity.LOW)
            val balanced = scoreAt(fixture.text, Sensitivity.BALANCED)
            val high = scoreAt(fixture.text, Sensitivity.HIGH)
            assertTrue("${fixture.name}: HIGH $high < BALANCED $balanced", high >= balanced)
            assertTrue("${fixture.name}: BALANCED $balanced < LOW $low", balanced >= low)
        }
    }

    @Test
    fun `presented band is monotone in sensitivity for every fixture`() {
        for (fixture in FIXTURES) {
            val low = rank(bandAt(fixture.text, Sensitivity.LOW))
            val balanced = rank(bandAt(fixture.text, Sensitivity.BALANCED))
            val high = rank(bandAt(fixture.text, Sensitivity.HIGH))
            assertTrue("${fixture.name}: HIGH < BALANCED", high >= balanced)
            assertTrue("${fixture.name}: BALANCED < LOW", balanced >= low)
        }
    }

    @Test
    fun `no fixture falls from a scam band to low risk as sensitivity rises`() {
        for (fixture in FIXTURES) {
            val bands = Sensitivity.entries.map { bandAt(fixture.text, it) }
            val lowRiskCount = bands.count { it == RiskLabel.LOW_RISK }
            assertTrue(
                "${fixture.name}: mixed safe/scam bands across presets: $bands",
                lowRiskCount == 0 || lowRiskCount == bands.size,
            )
        }
    }

    @Test
    fun `scam fixtures stay out of low risk at every preset`() {
        for (fixture in FIXTURES.filter { it.kind == Kind.SCAM }) {
            for (sensitivity in Sensitivity.entries) {
                val scored = SensitivityScoring.scorer(sensitivity, normalizer).analyzeScored(fixture.text)
                assertNotEquals(
                    "${fixture.name} at ${sensitivity.name} was presented safe",
                    RiskLabel.LOW_RISK,
                    SensitivityScoring.bandFor(sensitivity, scored),
                )
            }
        }
    }

    @Test
    fun `legitimate fixtures are never accused at any preset`() {
        for (fixture in FIXTURES.filter { it.kind == Kind.LEGIT }) {
            for (sensitivity in Sensitivity.entries) {
                val scored = SensitivityScoring.scorer(sensitivity, normalizer).analyzeScored(fixture.text)
                assertFalse("${fixture.name} at ${sensitivity.name} claimed a verified ask", scored.verifiedAsk)
                assertEquals(
                    "${fixture.name} at ${sensitivity.name}",
                    RiskLabel.LOW_RISK,
                    scored.result.risk.label,
                )
                assertFalse("${fixture.name} must never escalate", scored.escalateCritical)
            }
        }
    }

    @Test
    fun `unreadable input stays unable to verify at every preset`() {
        for (garbage in UNREADABLE) {
            for (sensitivity in Sensitivity.entries) {
                val scored = SensitivityScoring.scorer(sensitivity, normalizer).analyzeScored(garbage)
                assertEquals(
                    "unreadable input at ${sensitivity.name}",
                    RiskLabel.UNABLE_TO_VERIFY,
                    scored.result.risk.label,
                )
                assertFalse("unreadable input must not claim an ask", scored.verifiedAsk)
                assertTrue(
                    "unreadable input needs a limitation",
                    scored.result.risk.label != RiskLabel.UNABLE_TO_VERIFY || scored.result.limitations.isNotEmpty(),
                )
                assertEquals(
                    RiskLabel.UNABLE_TO_VERIFY,
                    SensitivityScoring.bandFor(sensitivity, scored),
                )
            }
        }
    }

    @Test
    fun `unreadable band is not produced by the numeric mapping`() {
        // UNABLE_TO_VERIFY comes from the engine's status, never from a sensitivity cut.
        for (sensitivity in Sensitivity.entries) {
            for (score in 0..100) {
                assertNotEquals(
                    RiskLabel.UNABLE_TO_VERIFY,
                    SensitivityScoring.bandFor(sensitivity, score, hasVerifiedStrongAsk = false),
                )
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // Determinism, ids, confidence
    // ---------------------------------------------------------------------------------------

    @Test
    fun `analysisId embeds the preset and repeats are byte identical`() {
        val ids = Sensitivity.entries.associateWith { sensitivity ->
            SensitivityScoring.scorer(sensitivity, normalizer).analyze(ARREST_LADDER).analysisId
        }
        assertEquals("three presets must produce three ids", 3, ids.values.toSet().size)
        assertTrue("LOW id carries the preset tag: ${ids[Sensitivity.LOW]}", ids.getValue(Sensitivity.LOW).endsWith("-low"))
        assertTrue(
            "BALANCED maps to the STANDARD engine preset: ${ids[Sensitivity.BALANCED]}",
            ids.getValue(Sensitivity.BALANCED).endsWith("-standard"),
        )
        assertTrue("HIGH id carries the preset tag: ${ids[Sensitivity.HIGH]}", ids.getValue(Sensitivity.HIGH).endsWith("-high"))

        for (sensitivity in Sensitivity.entries) {
            val first = SensitivityScoring.scorer(sensitivity, normalizer).analyzeScored(ARREST_LADDER)
            val second = SensitivityScoring.scorer(sensitivity, normalizer).analyzeScored(ARREST_LADDER)
            assertEquals("${sensitivity.name} run twice must be identical", first, second)
            assertEquals(first.result.analysisId, second.result.analysisId)
        }
    }

    @Test
    fun `confidence is untouched by sensitivity`() {
        // Score and confidence stay separate: moving a threshold must not launder the coverage story.
        for (fixture in FIXTURES) {
            val confidences = Sensitivity.entries.map { sensitivity ->
                SensitivityScoring.scorer(sensitivity, normalizer).analyze(fixture.text).risk.confidence
            }
            assertEquals("${fixture.name}: $confidences", 1, confidences.toSet().size)
        }
    }

    @Test
    fun `sensitivity does not change evidence, stages or status`() {
        for (fixture in FIXTURES) {
            val results = Sensitivity.entries.map { SensitivityScoring.scorer(it, normalizer).analyze(fixture.text) }
            assertEquals("${fixture.name}: stages moved", 1, results.map { it.stages }.toSet().size)
            assertEquals("${fixture.name}: status moved", 1, results.map { it.status }.toSet().size)
            assertEquals("${fixture.name}: evidence moved", 1, results.map { it.evidence.size }.toSet().size)
        }
    }

    // ---------------------------------------------------------------------------------------
    // Observed bounds table
    // ---------------------------------------------------------------------------------------

    @Test
    fun `observed bounds table is monotone and inside the documented bounds`() {
        val rows = ArrayList<String>(FIXTURES.size * 3 + 3)
        rows.add("| fixture | preset | engine score | shown score | contract label | band | confidence | ask |")
        rows.add("|---|---|---|---|---|---|---|---|")
        for (fixture in FIXTURES) {
            for (sensitivity in Sensitivity.entries) {
                val scored = SensitivityScoring.scorer(sensitivity, normalizer).analyzeScored(fixture.text)
                val risk = scored.result.risk
                val shown = SensitivityScoring.presentedScore(risk.score, scored.verifiedAsk)
                val band = SensitivityScoring.bandFor(sensitivity, scored)
                rows.add(
                    "| ${fixture.name} | ${sensitivity.name} | ${risk.score} | $shown | ${risk.label.wire} | " +
                        "${band.wire} | ${risk.confidence.wire} | ${scored.verifiedAsk} |",
                )
                assertTrue("${fixture.name} score out of range", risk.score in 0..100)
                assertEquals("${fixture.name}: shown score is the clamped/floor score", shown, risk.score.coerceIn(0, 100).let {
                    if (scored.verifiedAsk) maxOf(it, SensitivityPolicy.STRONG_ASK_FLOOR_SCORE) else it
                })
                assertTrue(
                    "${fixture.name} at ${sensitivity.name}: band $band must not be safer than the engine label ${risk.label}",
                    rank(band) >= rank(risk.label) - 1,
                )
            }
        }
        println("P3-16 observed sensitivity bounds")
        rows.forEach(::println)
    }

    @Test
    fun `observed engine scores are pinned and the presets that cannot move say so`() {
        // Real numbers observed from this branch's run (see the table test above); pinned so a change
        // in A05's presets or in C04's mapping is a visible, reviewable edit rather than a quiet drift.
        assertEquals(40, scoreAt(SBI_KYC_OTP_SCAM, Sensitivity.LOW))
        assertEquals(70, scoreAt(SBI_KYC_OTP_SCAM, Sensitivity.BALANCED))
        assertEquals(70, scoreAt(SBI_KYC_OTP_SCAM, Sensitivity.HIGH))

        assertEquals(70, scoreAt(ARREST_LADDER, Sensitivity.LOW))
        assertEquals(75, scoreAt(ARREST_LADDER, Sensitivity.BALANCED))
        assertEquals(75, scoreAt(ARREST_LADDER, Sensitivity.HIGH))

        assertEquals(37, scoreAt(ASK_PLUS_AUTHORITY, Sensitivity.LOW))
        assertEquals(37, scoreAt(ASK_PLUS_AUTHORITY, Sensitivity.BALANCED))
        assertEquals(45, scoreAt(ASK_PLUS_AUTHORITY, Sensitivity.HIGH))

        for (fixture in FIXTURES) {
            val low = scoreAt(fixture.text, Sensitivity.LOW)
            val high = scoreAt(fixture.text, Sensitivity.HIGH)
            if (fixture.moves) {
                assertTrue("${fixture.name} is marked as moving but LOW == HIGH ($low)", high > low)
            } else {
                // Reported honestly: this input is scored identically at every preset.
                assertEquals("${fixture.name} is marked as static but moved", low, high)
                println("P3-16 note: '${fixture.name}' scores $low at every preset (no engine movement)")
            }
        }
    }

    @Test
    fun `the sbi kyc otp scam crosses the high risk line between low and balanced`() {
        // The canonical proof that the setting is not decorative: same message, two verdicts.
        assertEquals(RiskLabel.CAUTION, bandAt(SBI_KYC_OTP_SCAM, Sensitivity.LOW))
        assertEquals(RiskLabel.HIGH_RISK, bandAt(SBI_KYC_OTP_SCAM, Sensitivity.BALANCED))
        assertEquals(RiskLabel.HIGH_RISK, bandAt(SBI_KYC_OTP_SCAM, Sensitivity.HIGH))
    }

    @Test
    fun `a bare verified ask at low preset is never presented as safe`() {
        // score 30 at every preset, but the C04 floor lifts it to 55 for presentation, which clears
        // LOW's 45 caution cut - the exact scenario the floor exists for.
        assertEquals(30, scoreAt(ASK_ALONE, Sensitivity.LOW))
        assertEquals(RiskLabel.CAUTION, bandAt(ASK_ALONE, Sensitivity.LOW))
        assertNotEquals(RiskLabel.LOW_RISK, bandAt(ASK_ALONE, Sensitivity.BALANCED))
    }

    @Test
    fun `suspicious cut sits between the documented caution and high risk cuts`() {
        for (sensitivity in Sensitivity.entries) {
            val thresholds = SensitivityPolicy.thresholds(sensitivity)
            val suspicious = SensitivityScoring.suspiciousAt(sensitivity)
            assertTrue("${sensitivity.name}: $suspicious < ${thresholds.cautionAt}", suspicious >= thresholds.cautionAt)
            assertTrue("${sensitivity.name}: $suspicious >= ${thresholds.highRiskAt}", suspicious < thresholds.highRiskAt)
        }
        assertEquals(62, SensitivityScoring.suspiciousAt(Sensitivity.LOW))
        assertEquals(52, SensitivityScoring.suspiciousAt(Sensitivity.BALANCED))
        assertEquals(41, SensitivityScoring.suspiciousAt(Sensitivity.HIGH))
    }

    // ---------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------

    private fun scoreAt(text: String, sensitivity: Sensitivity): Int =
        SensitivityScoring.scorer(sensitivity, normalizer).analyze(text).risk.score

    private fun bandAt(text: String, sensitivity: Sensitivity): RiskLabel =
        SensitivityScoring.bandFor(
            sensitivity,
            SensitivityScoring.scorer(sensitivity, normalizer).analyzeScored(text),
        )

    /** Severity order of the presentation bands; UNABLE_TO_VERIFY is not on this scale. */
    private fun rank(label: RiskLabel): Int = when (label) {
        RiskLabel.LOW_RISK -> 0
        RiskLabel.CAUTION -> 1
        RiskLabel.SUSPICIOUS -> 2
        RiskLabel.HIGH_RISK -> 3
        RiskLabel.UNABLE_TO_VERIFY -> 3
    }

    private enum class Kind { SCAM, LEGIT }

    private class Fixture(
        val name: String,
        val kind: Kind,
        val text: String,
        /** True when the observed engine score actually differs between the LOW and HIGH presets. */
        val moves: Boolean,
    )

    private companion object {

        /** report §3.1-3.2 KYC + OTP scam: authority, threat, urgency and a credential ask. */
        val SBI_KYC_OTP_SCAM = "Dear customer, aapka SBI account KYC update nahi hua hai. " +
            "24 ghante mein account band ho jayega. Turant jo OTP aaya hai woh batao."

        /** report §6 digital-arrest ladder: authority, threat, secrecy, money ask. */
        val ARREST_LADDER = "Main Mumbai Cyber Crime se Inspector Rathore bol raha hoon. " +
            "Aapke Aadhaar se bheje gaye parcel mein drugs mile hain. " +
            "Yeh confidential case hai, kisi ko mat batana, video call cut mat karna. " +
            "Case clear karne ke liye RBI verification account mein \u20B950,000 bhejo."

        /** A verified ask with no other stage: the friend / hacked-account shape. */
        val ASK_ALONE = "Bhai jo OTP aaya hai woh batao."

        /** A verified ask with exactly one other stage (authority). */
        val ASK_PLUS_AUTHORITY = "Main SBI officer bol raha hoon. Jo OTP aaya hai woh batao."

        /** report §3.4 legit OTP warning - protective, never an ask. */
        val LEGIT_OTP_WARNING = "123456 is your OTP for SBI net banking login. " +
            "Do not share it with anyone, not even bank staff."

        /** report §3.4 legit credit alert - informational. */
        val LEGIT_CREDIT_ALERT = "Rs 5,000 credited to your A/c XX1234 on 26-10-26. " +
            "Avl bal Rs 12,000. Not you? Call your branch."

        val FIXTURES: List<Fixture> = listOf(
            Fixture("sbi_kyc_otp_scam", Kind.SCAM, SBI_KYC_OTP_SCAM, moves = true),
            Fixture("digital_arrest", Kind.SCAM, ARREST_LADDER, moves = true),
            // Static: ask-only noise floor is 30 at all three presets, but the band still moves
            // (LOW caution -> BALANCED/HIGH suspicious) through the documented presentation cuts.
            Fixture("ask_alone", Kind.SCAM, ASK_ALONE, moves = false),
            Fixture("ask_plus_authority", Kind.SCAM, ASK_PLUS_AUTHORITY, moves = true),
            // Legitimate messages are not an alert at any preset; sensitivity must not invent one.
            Fixture("legit_otp_warning", Kind.LEGIT, LEGIT_OTP_WARNING, moves = false),
            Fixture("legit_credit_alert", Kind.LEGIT, LEGIT_CREDIT_ALERT, moves = false),
        )

        /** Unreadable inputs: emoji-only and a script the local rules cannot read. */
        val UNREADABLE: List<String> = listOf(
            "\uD83D\uDE42\uD83D\uDE42\uD83D\uDD25\uD83D\uDC4D",
            "\u0B89\u0B99\u0B4D\u0B95\u0BB3\u0BCD \u0B95\u0BA3\u0B95\u0BCD\u0B95\u0BC1 \u0BAE\u0BC1\u0B9F\u0B95\u0BCD\u0B95\u0BAA\u0BCD\u0BAA\u0B9F\u0BC1\u0BAE\u0BCD",
        )
    }
}
