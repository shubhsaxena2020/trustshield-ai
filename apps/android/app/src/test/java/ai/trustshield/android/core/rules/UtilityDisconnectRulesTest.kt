package ai.trustshield.android.core.rules

import ai.trustshield.android.core.model.NormalizedText
import ai.trustshield.android.core.model.Severity
import ai.trustshield.android.core.model.StageId
import ai.trustshield.android.core.model.Strength
import ai.trustshield.android.core.model.TextNormalizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #118 / report §3.1 stage 2 + §3.3 + §3.4 row 4: the utility-disconnection threat and the
 * "call this personal number" ask.
 *
 * The catch side is the report's own flagship electricity-bill scam; the false-alarm side is the
 * routine due-date bill, a published toll-free number, official-channel advice, "hum aapko call
 * karenge", a protective "call mat karein", and the in-person delivery OTP. Both sides are asserted
 * here because a rule that fixes one and breaks the other is not a fix.
 *
 * Runs against the documented identity normalizer (A02 is a parallel package); [StageRules] itself is
 * exercised through the real `HinglishNormalizer` in `LegitScamPairsTest`.
 */
class UtilityDisconnectRulesTest {

    private val rules = StageRules(TextNormalizer { NormalizedText.identity(it) })

    // ---------------------------------------------------------------------------------------
    // Catch side: the utility/service disconnect threat (stage 2)
    // ---------------------------------------------------------------------------------------

    @Test
    fun `flagship bijli disconnect message produces a stage 2 threat with a verified quote`() {
        val raw = "Dear customer, aapka bijli connection aaj raat 9:30 baje kat jayega. " +
            "Turant is number par call karein 9876543210"

        val result = rules.detect(raw)

        assertTrue("expected THREAT_OR_REWARD, was ${result.stageIds}", result.stageIds.contains(StageId.THREAT_OR_REWARD))
        val threat = result.stages.first { it.stage == StageId.THREAT_OR_REWARD }
        assertTrue(threat.evidence.isNotEmpty())
        threat.evidence.forEach { item ->
            assertNotNull(item.quote)
            assertTrue(
                "quote must be a substring of the raw message: ${item.quote}",
                raw.contains(item.quote!!),
            )
            // Stage 2 alone is never an accusation (BEHAVIOR_SPEC "Rule gates").
            assertEquals(Severity.LOW, item.severity)
            assertEquals(Strength.WEAK, item.strength)
        }
    }

    @Test
    fun `every report disconnect phrasing is caught with a utility or service noun`() {
        val variants = listOf(
            "Aapka bijli connection kat jayega",
            "Aaj raat bijli kat jayegi",
            "Gas connection katega",
            "Aapka broadband band ho jayega",
            "Water supply band ho jayegi",
            "Aapka landline block ho jayega",
            "DTH connection suspend ho jayega",
            "Cable connection disconnect ho jayega",
            "Aapka meter cut ho jayega",
        )
        for (raw in variants) {
            val result = rules.detect(raw)
            assertTrue(
                "'$raw' must be a stage 2 threat, was ${result.stageIds}",
                result.stageIds.contains(StageId.THREAT_OR_REWARD),
            )
        }
    }

    @Test
    fun `hindi devanagari disconnect is caught`() {
        val raw = "आपका बिजली कनेक्शन आज रात कट जाएगा, तुरंत संपर्क करें"
        assertTrue(rules.detect(raw).stageIds.contains(StageId.THREAT_OR_REWARD))
    }

    @Test
    fun `routine bill with a due date has no disconnect threat and no ask`() {
        listOf(
            "Your electricity bill of Rs 1,240 is due on 30 Oct.",
            "Your electricity bill of ₹1,240 is due on 30 Oct.",
            "Bijli bill 1,240 rupaye ki due date 30 Oct hai.",
        ).forEach { raw ->
            val result = rules.detect(raw)
            assertFalse("'$raw' must not be a disconnect threat", result.stageIds.contains(StageId.THREAT_OR_REWARD))
            assertFalse("'$raw' must not be an ask", result.stageIds.contains(StageId.ASK))
            assertTrue(result.askTypes.isEmpty())
        }
    }

    @Test
    fun `a bare band instruction is not a disconnect threat`() {
        // "wifi band kar do" is an instruction, not an imminent loss of service.
        assertFalse(rules.detect("Wifi router ko band kar do").stageIds.contains(StageId.THREAT_OR_REWARD))
    }

    // ---------------------------------------------------------------------------------------
    // Catch side: "call this personal number" (stage 5)
    // ---------------------------------------------------------------------------------------

    @Test
    fun `personal mobile with a callback imperative is a strong ask next to a utility`() {
        val raw = "Dear customer, aapka bijli connection aaj raat kat jayega. " +
            "Turant is number par call karein 9876543210"

        val result = rules.detect(raw)

        assertTrue(result.stageIds.contains(StageId.ASK))
        assertEquals(setOf(AskType.CALLBACK), result.askTypes)
        val ask = result.stages.first { it.stage == StageId.ASK }
        val strong = ask.evidence.filter { it.strength == Strength.STRONG }
        assertTrue("expected one strong callback item, was ${ask.evidence}", strong.isNotEmpty())
        assertTrue(strong.all { it.severity == Severity.HIGH })
        strong.forEach { item ->
            assertEquals("quote must verify", true, item.quote?.let { raw.contains(it) })
        }
    }

    @Test
    fun `personal mobile next to a bank name is a callback ask`() {
        assertEquals(
            setOf(AskType.CALLBACK),
            rules.detect("SBI: KYC ke liye turant call karein 9876543210").askTypes,
        )
        assertEquals(
            setOf(AskType.CALLBACK),
            rules.detect("HDFC bank se: is number par phone karein 98765 43210").askTypes,
        )
        assertEquals(
            setOf(AskType.CALLBACK),
            rules.detect("SBI bank: call this number 9876543210 for KYC").askTypes,
        )
    }

    @Test
    fun `plus 91 prefix is still a personal mobile`() {
        assertTrue(
            rules.detect("Aapka bijli connection kat jayega. call karein +91 9876543210")
                .askTypes.contains(AskType.CALLBACK),
        )
    }

    @Test
    fun `explicit personal number callback without digits is an ask`() {
        // The Home screen's own "Bijli bill" example chip: no digits, but it names the channel.
        val raw = "Bijli vibhag: Aaj raat 9 baje aapka connection kat jayega. Turant is personal " +
            "number par call karke bill clear karein."

        assertTrue(rules.detect(raw).askTypes.contains(AskType.CALLBACK))
    }

    // ---------------------------------------------------------------------------------------
    // False-alarm side: numbers that are not a personal callback
    // ---------------------------------------------------------------------------------------

    @Test
    fun `toll free helpline and short codes are never a personal callback ask`() {
        listOf(
            "SBI: KYC ke liye 1800 123 4567 par call karein",
            "Bijli vibhag helpline: 1912 par call karein",
            "Cyber helpline 1930 par call karein",
            "Bank: customer care 1860 500 0000 par call karein",
            "SBI: short code 57575 par SMS karein",
        ).forEach { raw ->
            val result = rules.detect(raw)
            assertFalse("'$raw' must not be a personal callback ask", result.askTypes.contains(AskType.CALLBACK))
        }
    }

    @Test
    fun `official branch and net banking advice is not a callback ask`() {
        listOf(
            "Your KYC is due. Visit your branch or update via net banking.",
            "KYC ke liye apni branch visit karein ya net banking use karein. 022 12345678",
            "KYC pending hai. Official app par update karein.",
        ).forEach { raw ->
            val result = rules.detect(raw)
            assertFalse("'$raw' must not be an ask", result.stageIds.contains(StageId.ASK))
            assertTrue(result.askTypes.isEmpty())
        }
    }

    @Test
    fun `we will call you is not an ask`() {
        listOf(
            "SBI: hum aapko call karenge 9876543210 par",
            "Bank se hum aapko 9876543210 par call karenge",
            "SBI will call you on 9876543210",
        ).forEach { raw ->
            assertFalse(
                "'$raw' must not be an ask: ${rules.detect(raw).askTypes}",
                rules.detect(raw).askTypes.contains(AskType.CALLBACK),
            )
        }
    }

    @Test
    fun `protective do not call is not an ask`() {
        listOf(
            "SBI: is number par call mat karein 9876543210",
            "Do not call this number 9876543210, yeh fraud hai",
            "Kisi bhi number par call na karein 9876543210",
        ).forEach { raw ->
            val result = rules.detect(raw)
            assertFalse("'$raw' must stay protective", result.askTypes.contains(AskType.CALLBACK))
            assertFalse("'$raw' must have no ask stage", result.stageIds.contains(StageId.ASK))
        }
    }

    @Test
    fun `a personal number without a bank or utility context is not an ask`() {
        listOf(
            "Bhai call karein 9876543210 jab free ho",
            "Call me on 9876543210",
            "Mummy aap call karein 9876543210 par",
        ).forEach { raw ->
            assertFalse("'$raw' must not be an ask", rules.detect(raw).askTypes.isNotEmpty())
        }
    }

    @Test
    fun `delivery otp at the door is not a callback ask`() {
        val raw = "Delivery OTP 4521, share only with the agent at your door"
        val result = rules.detect(raw)
        assertFalse(result.stageIds.contains(StageId.ASK))
        assertTrue(result.askTypes.isEmpty())
    }

    // ---------------------------------------------------------------------------------------
    // KYC-through-an-unofficial-link ask (report §3.3 shortener, §3.4 row 3)
    // ---------------------------------------------------------------------------------------

    @Test
    fun `kyc pushed through a shortener under time pressure is a personal info ask`() {
        val raw = "KYC pending, account 24 ghante mein band, yahan update karo: bit.ly/3xYz"
        val result = rules.detect(raw)

        assertEquals(setOf(AskType.PERSONAL_INFO), result.askTypes)
        val ask = result.stages.first { it.stage == StageId.ASK }
        assertTrue(ask.evidence.any { it.strength == Strength.STRONG })
        ask.evidence.forEach { item ->
            assertTrue(item.quote?.let { raw.contains(it) } == true)
        }
    }

    @Test
    fun `branch or net banking kyc update is not a personal info ask`() {
        listOf(
            "Your KYC is due. Visit your branch or update via net banking.",
            "KYC update karein apni branch mein ya official app se.",
            "KYC ke liye https://sbi.co.in/kyc par jaayein",
        ).forEach { raw ->
            assertFalse("'$raw' must not be an ask", rules.detect(raw).askTypes.contains(AskType.PERSONAL_INFO))
        }
    }
}
