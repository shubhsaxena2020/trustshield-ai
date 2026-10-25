package ai.trustshield.android.core.rules

import ai.trustshield.android.core.model.RiskLabel
import ai.trustshield.android.core.model.Severity
import ai.trustshield.android.core.model.StageId
import ai.trustshield.android.core.normalize.HinglishNormalizer
import ai.trustshield.android.core.score.LocalScorer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #114: the stage-4 secrecy rule must stop false-firing on a bank's own OTP safety line.
 *
 * "Yeh OTP kisi ko na batayein" is *protective* advice — A04 already surfaces it as a legitimate
 * indicator — so A03 must not also emit "Kisi ko batane se roka" for the same words. The two cards
 * contradicted each other on a 10/100 LOW_RISK credit alert.
 *
 * Both directions are pinned here (BEHAVIOR_SPEC "Negation and legitimate pairs": "Tests must check
 * both over- and under-negation"):
 *
 *  * **over-negation** — a credential-scoped tell phrase ("OTP kisi ko na batayein", "do not share it
 *    with anyone") is advice: no secrecy stage, no secrecy card, verdict stays LOW_RISK.
 *  * **under-negation** — the digital-arrest stage 4 ("Yeh confidential case hai, kisi ko mat
 *    batana", "warna police case kar degi") and every phrase that is *not* scoped to a credential
 *    still fires secrecy. A credential word anywhere in the message is never enough to silence it.
 *
 * Fixtures 1-7 are the issue's seven messages, asserted end-to-end through [LocalScorer] because the
 * defect was visible in the *verdict* (score, stage list, evidence cards), not only in the matcher.
 */
class SecrecyScopingTest {

    private val normalizer = HinglishNormalizer()

    private val secrecyCardTitle = "Kisi ko batane se roka"
    private val protectiveIndicatorTitle = "OTP/code share karne se mana kiya gaya hai"

    private fun scored(raw: String) = LocalScorer(normalizer).analyzeScored(raw)

    private fun secrecyItems(raw: String) =
        scored(raw).result.evidence.filter { it.module == "stage_rules" && it.titleHinglish == secrecyCardTitle }

    private fun assertNoSecrecy(raw: String) {
        val scored = scored(raw)
        assertFalse(
            "secrecy stage must not fire on a credential-scoped protective phrase: ${scored.result.stages}",
            scored.result.stages.contains(StageId.SECRECY),
        )
        assertTrue(
            "no '$secrecyCardTitle' card may be shown for: $raw",
            scored.result.evidence.none { it.module == "stage_rules" && it.titleHinglish == secrecyCardTitle },
        )
        // A protective reading must never hide a credential ask: none of these is a request at all.
        assertFalse("protective advice must not become an ask", scored.verifiedAsk)
    }

    private fun assertSecrecyFires(raw: String) {
        val scored = scored(raw)
        assertTrue(
            "secrecy stage must still fire here: ${scored.result.stages}",
            scored.result.stages.contains(StageId.SECRECY),
        )
        val items = scored.result.evidence.filter {
            it.module == "stage_rules" && it.severity == Severity.MEDIUM
        }
        assertTrue("secrecy evidence must survive verification", items.isNotEmpty())
        items.forEach { item ->
            val quote = item.quote
            assertNotNull("every secrecy card needs an exact quote", quote)
            assertTrue("quote must be a substring of the input: $quote", raw.contains(quote!!))
        }
    }

    // ---- fixture 1: the exact repro from issue #114 -------------------------------------------

    @Test
    fun `credit alert with OTP safety line stays low risk with no secrecy stage and no secrecy card`() {
        val raw = "Rs 5,000 credited to A/c XX0123 on 12-Oct. Avl bal Rs 12,450. Yeh OTP kisi ko na batayein."

        val scored = scored(raw)

        assertFalse(
            "issue #114: no SECRECY stage for a protective OTP line, was ${scored.result.stages}",
            scored.result.stages.contains(StageId.SECRECY),
        )
        assertTrue(
            "the amber '$secrecyCardTitle' card must be gone",
            scored.result.evidence.none { it.titleHinglish == secrecyCardTitle },
        )
        assertTrue(
            "score must stay LOW_RISK (<=24), was ${scored.result.risk.score}",
            scored.result.risk.score <= 24,
        )
        assertEquals(RiskLabel.LOW_RISK, scored.result.risk.label)
        assertFalse(scored.verifiedAsk)
    }

    @Test
    fun `credit alert keeps its protective legit indicator and the credit notice`() {
        val raw = "Rs 5,000 credited to A/c XX0123 on 12-Oct. Avl bal Rs 12,450. Yeh OTP kisi ko na batayein."

        val legit = scored(raw).result.evidence.filter { it.module == "legit_indicators" }

        assertTrue(
            "the protective indicator must survive: ${legit.map { it.titleHinglish }}",
            legit.any { it.titleHinglish == protectiveIndicatorTitle },
        )
        assertTrue(
            "the credit notice indicator must survive: ${legit.map { it.titleHinglish }}",
            legit.any { it.titleHinglish == "Credit/balance alert - koi maang nahi" },
        )
        // Both quotes must be real spans of what the user saw.
        legit.forEach { item ->
            val quote = item.quote
            assertNotNull("legit indicator needs a quote", quote)
            assertTrue("quote must exist in the raw input: $quote", raw.contains(quote!!))
        }
    }

    @Test
    fun `removing the false secrecy card never raises the score`() {
        // The only stage on the repro message was the false SECRECY one (10/100). With it gone the
        // score may only fall, and it must never climb into CAUTION.
        val raw = "Rs 5,000 credited to A/c XX0123 on 12-Oct. Avl bal Rs 12,450. Yeh OTP kisi ko na batayein."

        val score = scored(raw).result.risk.score

        assertTrue("score must not increase past the old 10/100 defect value, was $score", score <= 10)
    }

    // ---- fixtures 2-4: other protective credential advice ------------------------------------

    @Test
    fun `SBI do not share it with anyone warning has no secrecy stage`() {
        assertNoSecrecy("123456 is your OTP for txn of Rs 500. Do not share it with anyone. -SBI")
    }

    @Test
    fun `delivery OTP handed to the agent at the door has no secrecy stage`() {
        assertNoSecrecy("Delivery OTP 4521, share only with the agent at your door")
    }

    @Test
    fun `parcel delivery OTP sentence has no secrecy stage`() {
        // Secrecy facts only: the *ask* card on this sentence is a separate A03 delivery-context
        // defect (its skip needs "share only with"), owned by the ASK lane, not by this fix.
        val raw = "Your parcel will arrive today. Share the delivery OTP with the agent at your door."

        val scored = scored(raw)

        assertFalse(
            "secrecy stage must not fire: ${scored.result.stages}",
            scored.result.stages.contains(StageId.SECRECY),
        )
        assertTrue(
            "no '$secrecyCardTitle' card may be shown for: $raw",
            scored.result.evidence.none { it.titleHinglish == secrecyCardTitle },
        )
        assertTrue(
            "the in-person delivery indicator must be present",
            scored.result.evidence.any { it.titleHinglish == "Delivery OTP darwaze par dene ke liye" },
        )
    }

    // ---- fixtures 5-7: the scam direction must keep firing -----------------------------------

    @Test
    fun `confidential case with kisi ko mat batana still fires secrecy with a verified quote`() {
        val raw = "Yeh confidential case hai, kisi ko mat batana, video call cut mat karna."

        assertSecrecyFires(raw)
        assertEquals(RiskLabel.LOW_RISK, scored(raw).result.risk.label)
    }

    @Test
    fun `courier OTP ask keeps its ask and gains no secrecy from the OTP reference`() {
        val raw = "Courier se bol rahe hain, parcel cancel hoga, OTP phone pe bata do"

        val scored = scored(raw)

        assertTrue("the OTP ask must survive: ${scored.result.stages}", scored.result.stages.contains(StageId.ASK))
        assertEquals(setOf(AskType.OTP_PIN), StageRules(normalizer).detect(raw).askTypes)
        assertTrue(scored.verifiedAsk)
        assertFalse(
            "an OTP reference in an ask is not isolation pressure: ${scored.result.stages}",
            scored.result.stages.contains(StageId.SECRECY),
        )
        assertTrue(
            "no secrecy card may be borrowed from the OTP word",
            scored.result.evidence.none { it.titleHinglish == secrecyCardTitle },
        )
    }

    @Test
    fun `over-negation guard - threat scoped tell phrase keeps both secrecy and ask`() {
        val raw = "Kisi ko mat batana, warna police case kar degi. OTP batao."

        val scored = scored(raw)

        assertTrue("stage 4 must survive a threat clause: ${scored.result.stages}", scored.result.stages.contains(StageId.SECRECY))
        assertTrue("stage 5 must survive: ${scored.result.stages}", scored.result.stages.contains(StageId.ASK))
        assertTrue(scored.verifiedAsk)
        assertTrue(scored.result.risk.label == RiskLabel.HIGH_RISK || scored.result.risk.score >= 25)
    }

    // ---- the deterministic scoping rule itself ----------------------------------------------

    @Test
    fun `devanagari credential scoped advice is not secrecy`() {
        assertNoSecrecy("यह ओटीपी किसी को न बताएं, धन्यवाद")
    }

    @Test
    fun `ask verb between the credential and the phrase keeps secrecy`() {
        // "batao" between "password" and "kisi ko mat" makes the phrase part of a request, not advice.
        assertSecrecyFires("Mera password hai hunter2 batao kisi ko mat")
    }

    @Test
    fun `trailing ask after the protective phrase keeps secrecy`() {
        // Scoped advice plus a contradicting request is a scam: stage 4 must not be silenced.
        assertSecrecyFires("Aapka OTP 123456 kisi ko mat batana, turant batao")
    }

    @Test
    fun `confidential framing before a credential scoped phrase keeps secrecy`() {
        val raw = "Yeh confidential case hai, OTP kisi ko mat batana."

        assertSecrecyFires(raw)
        assertTrue(
            "the confidential-case card must still be present",
            scored(raw).result.evidence.any { it.titleHinglish == "Confidential case" },
        )
    }

    @Test
    fun `secrecy scoping is deterministic across runs`() {
        val raw = "Rs 5,000 credited to A/c XX0123 on 12-Oct. Avl bal Rs 12,450. Yeh OTP kisi ko na batayein."

        val first = scored(raw)
        val second = scored(raw)

        assertEquals(first.result.stages, second.result.stages)
        assertEquals(first.result.risk, second.result.risk)
        assertEquals(first.result.evidence.map { it.id }, second.result.evidence.map { it.id })
        assertEquals(first.result.evidence.map { it.quote }, second.result.evidence.map { it.quote })
    }

    @Test
    fun `protective credential advice does not shield an independent ask`() {
        // The scoped phrase lowers stage 4 only; a real request in the same message still lands.
        val scored = scored("OTP kisi ko na batayein. Jo OTP aaya hai woh batao.")

        assertTrue(scored.verifiedAsk)
        assertTrue("a verified ask stays at least caution, was ${scored.result.risk.score}", scored.result.risk.score >= 25)
    }
}
