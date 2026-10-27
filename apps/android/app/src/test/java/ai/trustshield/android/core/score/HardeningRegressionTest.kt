package ai.trustshield.android.core.score

import ai.trustshield.android.core.model.RiskLabel
import ai.trustshield.android.core.normalize.HinglishNormalizer
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Post-review hardening gate (defect burn-down, follow-up to #114 / #118 / #119).
 *
 * An independent review measured four shapes where the merged rules were too eager or self-
 * contradictory. Each test here pins the *narrowing* only - every scam fixture these rules were
 * built for is asserted unchanged alongside it, so a future widening cannot sneak back in.
 */
class HardeningRegressionTest {

    private val scorer = LocalScorer(HinglishNormalizer())

    private fun score(text: String) = scorer.analyzeScored(text)

    // ---------------------------------------------------------------------------------------
    // 1. A digital-arrest AWARENESS / advisory / news message must not turn amber
    // ---------------------------------------------------------------------------------------

    private val advisories = listOf(
        "Cyber Crime police ki salah: digital arrest scam se bachein. CBI video call par arrest nahi " +
            "karti. Paisa mat bhejein, 1930 par call karein.",
        "Beware of digital arrest fraud. Fraudsters pretend to be CBI. Never pay. Call 1930.",
        "Digital arrest case: Mumbai police ne teen aaropiyon ko giraftar kiya.",
        "Bank advisory: digital arrest scam ke baare mein jaanein. Koi bank video call par paisa " +
            "verify nahi karwata.",
    )

    @Test
    fun `advisory and news text about digital arrest stays low`() {
        advisories.forEach { text ->
            val scored = score(text)
            assertTrue(
                "advisory must stay LOW_RISK, got ${scored.result.risk.score}/${scored.result.risk.label}: $text",
                scored.result.risk.score <= 24,
            )
            assertFalse("advisory must never escalate: $text", scored.escalateCritical)
        }
    }

    @Test
    fun `advisory text emits no digital arrest marker evidence`() {
        advisories.forEach { text ->
            assertTrue(
                "an advisory must not produce a digital-arrest evidence item: $text",
                score(text).result.evidence.none { it.module == "digital_arrest_rules" },
            )
        }
    }

    @Test
    fun `the scam itself still names the marker and still climbs`() {
        val scam = score(
            "Aapke Aadhaar se bheje gaye parcel mein drugs mile hain. Aap digital arrest mein hain. " +
                "Yeh confidential case hai, kisi ko mat batana.",
        )
        assertTrue(
            "a second-person digital-arrest marker must still fire",
            scam.result.evidence.any { it.module == "digital_arrest_rules" },
        )
        assertTrue(
            "authority + threat + secrecy + marker must leave LOW, got ${scam.result.risk.score}",
            scam.result.risk.score > 24,
        )
    }

    // ---------------------------------------------------------------------------------------
    // 2. The no-ask caution copy must not contradict the score on screen
    // ---------------------------------------------------------------------------------------

    @Test
    fun `a suspicious no-ask reading never prints the low-watch line`() {
        // authority + drugs-parcel threat + digital-arrest marker: a 50-69 no-ask reading.
        val scored = score(
            "Main Mumbai Cyber Crime se Inspector Rathore bol raha hoon. Aapke Aadhaar se bheje gaye " +
                "parcel mein drugs mile hain. Aap digital arrest mein hain. Yeh confidential case hai.",
        )
        assertTrue(
            "expected a watch band above LOW, got ${scored.result.risk.score}/${scored.result.risk.label}",
            scored.result.risk.label == RiskLabel.SUSPICIOUS || scored.result.risk.label == RiskLabel.CAUTION,
        )
        assertFalse(
            "a reading above LOW must not carry the 'akele khatra nahi dikhata' advice",
            scored.result.actions.contains(LocalScorer.ACTION_LOW_WATCH),
        )
        assertTrue(
            "the caution-tier advice must be present",
            scored.result.actions.contains(LocalScorer.ACTION_CAUTION_WATCH),
        )
    }

    @Test
    fun `a low reading keeps the low-watch line`() {
        val scored = score("Your electricity bill of Rs 1,240 is due on 30 Oct.")
        assertTrue(scored.result.risk.score <= 24)
        assertTrue(scored.result.actions.contains(LocalScorer.ACTION_LOW_WATCH))
    }

    @Test
    fun `routine bill support callback is not a verified ask`() {
        val scored = score(
            "Your electricity bill is due tomorrow. Call 9876543210 if you have questions.",
        )
        assertTrue(
            "routine bill support must stay LOW_RISK, got ${scored.result.risk.score}/${scored.result.risk.label}",
            scored.result.risk.score <= 24,
        )
        assertFalse("routine bill support must not become a verified callback ask", scored.verifiedAsk)
    }

    // ---------------------------------------------------------------------------------------
    // 3. Utility notices: a locality outage / an LPG delivery must not become an accusation
    // ---------------------------------------------------------------------------------------

    @Test
    fun `a completed locality outage notice does not escalate`() {
        val scored = score("Power cut ho gaya hai, turant bijli office 9812345678 par call karein.")
        assertFalse(
            "an outage notice must never release the full-screen alert, got ${scored.result.risk.score}",
            scored.escalateCritical,
        )
        assertTrue(
            "an outage notice must not reach the high band, got ${scored.result.risk.score}",
            scored.result.risk.score < 70,
        )
    }

    @Test
    fun `an lpg delivery notice raises no ask`() {
        val scored = score("Aapka LPG cylinder 3 din mein deliver hoga, 9812345678 par sampark karein.")
        assertFalse(
            "a delivery notice must not produce a verified money/credential/callback ask",
            scored.verifiedAsk,
        )
        assertTrue(
            "a delivery notice must not carry a callback evidence card",
            scored.result.evidence.none { it.titleHinglish.contains("call karne ko kaha") },
        )
    }

    @Test
    fun `naming a branch does not shield a personal callback number`() {
        val scored = score(
            "Aapka account block ho jayega. Apni branch mein ya 9876543210 par call karein, turant.",
        )
        assertTrue(
            "the branch word must not drop a scam back to LOW, got ${scored.result.risk.score}",
            scored.result.risk.score >= 25,
        )
    }

    // ---------------------------------------------------------------------------------------
    // 4. The flagship scam is unchanged, and a callback-only ask never escalates
    // ---------------------------------------------------------------------------------------

    private val flagship =
        "Dear customer, aapka bijli connection aaj raat 9:30 baje kat jayega. Turant is number par " +
            "call karein 9876543210"

    @Test
    fun `the flagship bill scam still scores high`() {
        val scored = score(flagship)
        assertTrue(
            "flagship must stay HIGH_RISK, got ${scored.result.risk.score}/${scored.result.risk.label}",
            scored.result.risk.score >= 70,
        )
        assertTrue("flagship must keep its verified ask", scored.verifiedAsk)
        assertTrue(
            "flagship must keep its disconnect threat",
            scored.result.stages.contains(ai.trustshield.android.core.model.StageId.THREAT_OR_REWARD),
        )
    }

    @Test
    fun `a callback-only ask earns the high floor but not the full-screen release`() {
        val scored = score(flagship)
        assertFalse(
            "a callback ask alone must not release the critical alert",
            scored.escalateCritical,
        )
        assertTrue(
            "the actions must still tell the user to verify through the official number",
            scored.result.actions.contains(LocalScorer.ACTION_HIGH_VERIFY),
        )
    }

    @Test
    fun `a credential ask at the same score still escalates`() {
        val scored = score(
            "SBI KYC 24 ghante main band ho jaayega. Link par KYC update karo aur jo OTP aaya hai " +
                "woh batao.",
        )
        assertTrue("expected a high reading, got ${scored.result.risk.score}", scored.result.risk.score >= 70)
        assertTrue("an OTP ask with corroboration must still escalate", scored.escalateCritical)
    }
}

/**
 * Prints the measured table for every fixture in this gate (the project keeps its measured artifacts
 * in the test output). Nothing here asserts; the assertions live in the tests above.
 */
class HardeningMeasureTest {

    private val scorer = LocalScorer(HinglishNormalizer())

    @Test
    fun printMeasurements() {
        val fixtures = listOf(
            "advisory-hinglish" to
                "Cyber Crime police ki salah: digital arrest scam se bachein. CBI video call par " +
                "arrest nahi karti. Paisa mat bhejein, 1930 par call karein.",
            "advisory-english" to
                "Beware of digital arrest fraud. Fraudsters pretend to be CBI. Never pay. Call 1930.",
            "news-headline" to
                "Digital arrest case: Mumbai police ne teen aaropiyon ko giraftar kiya.",
            "door-otp" to
                "Your parcel will arrive today. Share the delivery OTP with the agent at your door.",
            "power-cut-outage" to
                "Power cut ho gaya hai, turant bijli office 9812345678 par call karein.",
            "lpg-delivery" to
                "Aapka LPG cylinder 3 din mein deliver hoga, 9812345678 par sampark karein.",
            "branch-bypass" to
                "Aapka account block ho jayega. Apni branch mein ya 9876543210 par call karein, turant.",
            "flagship-bill-scam" to
                "Dear customer, aapka bijli connection aaj raat 9:30 baje kat jayega. Turant is " +
                "number par call karein 9876543210",
            "kyc-otp-scam" to
                "SBI KYC 24 ghante main band ho jaayega. Link par KYC update karo aur jo OTP aaya " +
                "hai woh batao.",
            "arrival-otp-phone-scam" to
                "Courier se bol rahe hain, parcel cancel hoga, OTP phone pe bata do",
        )
        fixtures.forEach { (name, text) ->
            val scored = scorer.analyzeScored(text)
            val r = scored.result
            println(
                "HARDEN|$name|score=${r.risk.score}|label=${r.risk.label}" +
                    "|stages=${r.stages}|ask=${scored.verifiedAsk}|escalate=${scored.escalateCritical}" +
                    "|actions=${r.actions.size}",
            )
        }
    }
}
