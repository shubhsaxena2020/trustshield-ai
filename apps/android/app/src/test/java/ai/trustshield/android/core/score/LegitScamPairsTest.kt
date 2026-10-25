package ai.trustshield.android.core.score

import ai.trustshield.android.core.model.RiskLabel
import ai.trustshield.android.core.model.StageId
import ai.trustshield.android.core.normalize.HinglishNormalizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #118 / report §3.4 and BEHAVIOR_SPEC "Negation and legitimate pairs": all five
 * legitimate-vs-scam pairs in one table-driven gate, plus the two controls the issue names.
 *
 * The contract per row: the **legitimate** twin stays in the watch band (`<= 24`, `LOW_RISK`) and the
 * **scam** twin reaches at least caution (`>= 25`). Nothing here widens an expectation to turn a red
 * run green; the measured score of every row is printed in the failure message of the row that
 * breaks, and the same numbers are reported in the lane's hand-off note.
 *
 * Real `HinglishNormalizer` + [LocalScorer]: this is the same path the app runs.
 */
class LegitScamPairsTest {

    private val normalizer = HinglishNormalizer()
    private val scorer = LocalScorer(normalizer)

    /** Report §3.4 rows 1-5 (and BEHAVIOR_SPEC's table), in the report's own order. */
    private data class Pair(val name: String, val legit: String, val scam: String)

    private val pairs = listOf(
        Pair(
            name = "1 OTP notice vs OTP request",
            legit = "123456 is your OTP for txn of Rs 500. Do not share it with anyone. -SBI",
            scam = "Sir, Rs 500 debit rok diya hai, jo OTP aaya hai woh batayein",
        ),
        Pair(
            name = "2 credit alert vs money back request",
            legit = "Rs 5,000 credited to A/c XX1234. Avl bal Rs 12,340",
            scam = "Galti se Rs 5,000 bhej diye, please wapas bhej do",
        ),
        Pair(
            name = "3 branch/net-banking KYC advice vs KYC link pressure",
            legit = "Your KYC is due. Visit your branch or update via net banking.",
            scam = "KYC pending, account 24 ghante mein band, yahan update karo: bit.ly/3xYz",
        ),
        Pair(
            name = "4 bill due date vs night disconnection + personal number",
            legit = "Your electricity bill of Rs 1,240 is due on 30 Oct.",
            scam = "Dear customer, aapka bijli connection aaj raat 9:30 baje kat jayega. " +
                "Turant is number par call karein 9876543210",
        ),
        Pair(
            name = "5 delivery OTP at the door vs courier OTP on the phone",
            legit = "Delivery OTP 4521, share only with the agent at your door",
            scam = "Courier se bol rahe hain, parcel cancel hoga, OTP phone pe bata do",
        ),
    )

    // ---------------------------------------------------------------------------------------
    // The five pairs
    // ---------------------------------------------------------------------------------------

    @Test
    fun `every legitimate twin stays in the watch band`() {
        for (pair in pairs) {
            val scored = scorer.analyzeScored(pair.legit)
            val where = "${pair.name} legit: score=${scored.result.risk.score} " +
                "label=${scored.result.risk.label} stages=${scored.result.stages}"

            assertTrue("$where - a legitimate message must stay <= 24", scored.result.risk.score <= 24)
            assertEquals("$where - must be LOW_RISK", RiskLabel.LOW_RISK, scored.result.risk.label)
            assertFalse("$where - a legitimate twin must not carry a verified ask", scored.verifiedAsk)
        }
    }

    @Test
    fun `every scam twin reaches at least caution`() {
        for (pair in pairs) {
            val scored = scorer.analyzeScored(pair.scam)
            val where = "${pair.name} scam: score=${scored.result.risk.score} " +
                "label=${scored.result.risk.label} stages=${scored.result.stages}"

            assertTrue("$where - the scam twin must reach >= 25", scored.result.risk.score >= 25)
            assertTrue("$where - must not be LOW_RISK", scored.result.risk.label != RiskLabel.LOW_RISK)
        }
    }

    @Test
    fun `every evidence quote in every row verifies against the analysed text`() {
        for (pair in pairs) {
            for (text in listOf(pair.legit, pair.scam)) {
                val scored = scorer.analyzeScored(text)
                val unverified = scored.result.unverifiableEvidence(scored.analyzedText)
                assertTrue(
                    "${pair.name}: ${unverified.size} evidence item(s) carry a quote the text does not " +
                        "contain: ${unverified.map { it.quote }}",
                    unverified.isEmpty(),
                )
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // Controls named by issue #118
    // ---------------------------------------------------------------------------------------

    @Test
    fun `flagship electricity scam reaches high risk with a verified callback ask`() {
        val scored = scorer.analyzeScored(FLAGSHIP)
        val risk = scored.result.risk

        assertTrue("expected >= 70, was ${risk.score}", risk.score >= 70)
        assertEquals(RiskLabel.HIGH_RISK, risk.label)
        assertTrue(scored.verifiedAsk)
        assertTrue("expected the thief stage to be visible", scored.result.stages.contains(StageId.ASK))
        assertTrue(
            "expected threat + urgency + ask, was ${scored.result.stages}",
            scored.result.stages.containsAll(
                listOf(StageId.THREAT_OR_REWARD, StageId.URGENCY, StageId.ASK),
            ),
        )
        assertTrue("two corroborations + a strong ask is the documented escalation", scored.escalateCritical)
    }

    @Test
    fun `home screen Bijli bill example chip reaches at least caution`() {
        // Verbatim from ui/home/ScanScreen.kt (ScanExamples.DEFAULT_ENTRIES "electricity_bill").
        val scored = scorer.analyzeScored(BIJLI_CHIP)
        val risk = scored.result.risk

        assertTrue("expected >= 25, was ${risk.score}", risk.score >= 25)
        assertTrue("must not be LOW_RISK", risk.label != RiskLabel.LOW_RISK)
        assertTrue("the disconnect threat must be visible", scored.result.stages.contains(StageId.THREAT_OR_REWARD))
    }

    @Test
    fun `issue 114 legit credit message gains no ask evidence`() {
        val scored = scorer.analyzeScored(ISSUE_114_LEGIT)

        assertFalse("no ask may be invented on a credit notice", scored.verifiedAsk)
        assertFalse("no ASK stage may appear", scored.result.stages.contains(StageId.ASK))
        assertTrue(scored.result.risk.score <= 24)
        assertEquals(RiskLabel.LOW_RISK, scored.result.risk.label)
        assertTrue(
            "the protective 'OTP kisi ko na batayein' must still be visible as legit evidence",
            scored.result.evidence.any { it.module == "legit_indicators" },
        )
    }

    @Test
    fun `plain due date notices of any utility stay low`() {
        listOf(
            "Your electricity bill of Rs 1,240 is due on 30 Oct.",
            "Your water bill of Rs 480 is due on 5 Nov.",
            "Aapka broadband bill Rs 999 due date 12 Nov hai.",
            "Gas cylinder booking 15 Nov ko deliver hoga.",
        ).forEach { text ->
            val risk = scorer.analyze(text).risk
            assertTrue("'$text' must stay <= 24, was ${risk.score}", risk.score <= 24)
        }
    }

    private companion object {
        const val FLAGSHIP = "Dear customer, aapka bijli connection aaj raat 9:30 baje kat jayega. " +
            "Turant is number par call karein 9876543210"

        const val BIJLI_CHIP = "Bijli vibhag: Aaj raat 9 baje aapka connection kat jayega. Turant is " +
            "personal number par call karke bill clear karein."

        const val ISSUE_114_LEGIT = "Rs 5,000 credited to A/c XX0123 on 12-Oct. Avl bal Rs 12,450. " +
            "Yeh OTP kisi ko na batayein."
    }
}
