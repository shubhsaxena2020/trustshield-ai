package ai.trustshield.android.core.rules

import ai.trustshield.android.core.model.NormalizedText
import ai.trustshield.android.core.model.Severity
import ai.trustshield.android.core.model.TextNormalizer
import ai.trustshield.android.core.model.TextSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

/**
 * A04 (#27) gate: legitimate indicators, protective negation scoping and bounded risk reduction.
 *
 * The five BEHAVIOR_SPEC "legitimate vs scam" pairs are pinned as *pairs*: the legitimate message
 * must produce at least one protective indicator with a small reduction, and its scam twin must
 * produce none. Everything runs on the JVM: no Android runtime, no network.
 *
 * A02 (`HinglishNormalizer`) is not on this branch, so the identity normalizer stands in - the
 * contract promises every normalized index maps back to a raw span, which identity satisfies.
 */
class LegitimateIndicatorsTest {

    private fun engine() = LegitimateIndicators(TextNormalizer { NormalizedText.identity(it) })

    private fun LegitAssessment.quotes(): List<String> = indicators.mapNotNull { it.quote }

    private fun spanOf(raw: String, needle: String): TextSpan {
        val at = raw.indexOf(needle)
        require(at >= 0) { "test setup: '$needle' is not in the fixture" }
        return TextSpan(at, at + needle.length)
    }

    // ---- shape / contract -------------------------------------------------------------------

    @Test
    fun `blank input yields a neutral assessment`() {
        val e = engine()
        val a = e.evaluate("")
        assertTrue(a.indicators.isEmpty())
        assertTrue(a.negations.isEmpty())
        assertTrue(a.quotedRegions.isEmpty())
        assertEquals(1.0, a.riskReduction, 0.0)
        assertFalse(a.hasProtectiveIndicator)

        val blank = e.evaluate("   \n  ")
        assertTrue(blank.indicators.isEmpty())
        assertEquals(1.0, blank.riskReduction, 0.0)
    }

    @Test
    fun `isNegated without a prior evaluate is false, not an exception`() {
        assertFalse(LegitimateIndicators(TextNormalizer { NormalizedText.identity(it) })
            .isNegated(TextSpan(0, 3)))
    }

    @Test
    fun `every indicator is low or informational severity with a verified quote`() {
        val raw = "Rs 5,000 credited to A/c XX1234. Avl bal Rs 12,340. OTP kisi ke saath share na karein."
        val a = engine().evaluate(raw)
        assertTrue(a.indicators.isNotEmpty())
        for (ev in a.indicators) {
            assertEquals(LegitimateIndicators.MODULE, ev.module)
            assertTrue(
                "severity ${ev.severity} must stay INFORMATIONAL or LOW",
                ev.severity == Severity.INFORMATIONAL || ev.severity == Severity.LOW,
            )
            assertNotNull(ev.quote)
            assertNotNull(ev.span)
            assertTrue("quote must verify against raw", ev.quoteVerifiesAgainst(raw))
            assertEquals(ev.quote, ev.span!!.slice(raw))
        }
    }

    @Test
    fun `no indicator quote is ever invented across fixtures`() {
        val fixtures = listOf(
            "123456 is your OTP for txn of Rs 500. Do not share it with anyone. -SBI",
            "Rs 5,000 credited to A/c XX1234. Avl bal Rs 12,340.",
            "Your electricity bill of Rs 1,240 is due on 30 Oct.",
            "Delivery OTP 4521, share only with the agent at your door.",
            "Yeh message bank ki taraf se jaankari ke liye hai.",
            "Beware: \"jo OTP aaya hai woh batao\" is a scam, never share",
            "Jo OTP aaya hai woh batayein.",
            "Galti se paise bheje, wapas bhejo.",
        )
        val e = engine()
        for (raw in fixtures) {
            val a = e.evaluate(raw)
            for (ev in a.indicators) {
                assertTrue("invented quote in '$raw': ${ev.quote}", ev.quoteVerifiesAgainst(raw))
            }
            assertTrue("reduction floor violated for '$raw'", a.riskReduction >= 0.65)
        }
    }

    // ---- pair (a): OTP do-not-share vs "jo OTP aaya hai woh batayein" -----------------------

    @Test
    fun `pair a - genuine OTP notice is protective`() {
        val raw = "123456 is your OTP for txn of Rs 500. Do not share it with anyone. -SBI"
        val a = engine().evaluate(raw)
        assertTrue("expected a protective indicator", a.hasProtectiveIndicator)
        assertTrue(a.quotes().any { it.contains("Do not share") })
        assertTrue(a.riskReduction < 0.95)
        assertTrue(a.negations.isNotEmpty())
    }

    @Test
    fun `pair a - scam twin asking for the OTP carries no protective indicator`() {
        val raw = "Sir, Rs 500 debit rok diya hai, jo OTP aaya hai woh batayein"
        val a = engine().evaluate(raw)
        assertFalse("a bare 'batayein' request must not look protective", a.hasProtectiveIndicator)
        assertEquals(1.0, a.riskReduction, 0.0)
        assertTrue(a.negations.isEmpty())
    }

    @Test
    fun `pair a - isNegated is true on the sharing clause and false before it`() {
        val raw = "123456 is your OTP for txn of Rs 500. Do not share it with anyone. -SBI"
        val e = engine()
        e.evaluate(raw)
        assertTrue(e.isNegated(spanOf(raw, "share it with anyone")))
        assertFalse(e.isNegated(spanOf(raw, "123456")))
        assertFalse(e.isNegated(spanOf(raw, "Rs 500")))
    }

    // ---- pair (b): credit notice vs "wapas bhejo" ------------------------------------------

    @Test
    fun `pair b - credit notice is informational`() {
        val raw = "Rs 5,000 credited to A/c XX1234. Avl bal Rs 12,340."
        val a = engine().evaluate(raw)
        assertTrue(a.hasProtectiveIndicator)
        assertTrue(a.quotes().any { it.contains("5,000") || it.contains("Avl bal") })
        assertTrue(a.riskReduction < 0.95)
    }

    @Test
    fun `pair b - refund scam twin is not treated as a credit notice`() {
        val raw = "Rs 5,000 credited to A/c XX1234. Galti se paise bheje, wapas bhejo."
        val a = engine().evaluate(raw)
        assertFalse(a.hasProtectiveIndicator)
        assertEquals(1.0, a.riskReduction, 0.0)
    }

    // ---- pair (c): KYC visit-branch vs bit.ly -------------------------------------------------

    @Test
    fun `pair c - official channel KYC advice is protective`() {
        val raw = "Your KYC is due. Visit your branch or update via net banking."
        val a = engine().evaluate(raw)
        assertTrue(a.hasProtectiveIndicator)
        assertTrue(a.quotes().any { it.contains("branch", ignoreCase = true) })
        assertTrue(a.riskReduction < 1.0)
    }

    @Test
    fun `pair c - KYC link scam twin is not protective`() {
        val raw = "KYC pending, account 24 ghante mein band, yahan update karo: bit.ly/xyz"
        val a = engine().evaluate(raw)
        assertFalse(a.hasProtectiveIndicator)
        assertEquals(1.0, a.riskReduction, 0.0)
    }

    // ---- pair (d): bill due date vs "aaj raat kat jayegi" ----------------------------------

    @Test
    fun `pair d - routine bill due date is informational`() {
        val raw = "Your electricity bill of Rs 1,240 is due on 30 Oct."
        val a = engine().evaluate(raw)
        assertTrue(a.hasProtectiveIndicator)
        assertTrue(a.quotes().any { it.contains("due on 30 Oct") })
        assertTrue(a.riskReduction < 1.0)
    }

    @Test
    fun `pair d - bill disconnection scam twin is not protective`() {
        val raw = "Bijli aaj raat 9:30 kat jayegi, officer ko call karo 98xxxx"
        val a = engine().evaluate(raw)
        assertFalse(a.hasProtectiveIndicator)
        assertEquals(1.0, a.riskReduction, 0.0)
    }

    // ---- pair (e): delivery OTP at the door vs "phone par OTP batao" -----------------------

    @Test
    fun `pair e - delivery OTP for the agent at the door is protective`() {
        val raw = "Delivery OTP 4521, share only with the agent at your door."
        val a = engine().evaluate(raw)
        assertTrue(a.hasProtectiveIndicator)
        assertTrue(a.quotes().any { it.contains("agent") })
        assertTrue(a.riskReduction < 0.95)
    }

    @Test
    fun `pair e - parcel cancellation twin is not protective`() {
        val raw = "Courier se bol rahe hain, parcel cancel hoga, OTP phone pe bata do"
        val a = engine().evaluate(raw)
        assertFalse(a.hasProtectiveIndicator)
        assertEquals(1.0, a.riskReduction, 0.0)
    }

    // ---- quoted scam inside a warning -------------------------------------------------------

    @Test
    fun `quoted scam inside a warning is protective and the quote is exposed`() {
        val raw = "Beware: \"jo OTP aaya hai woh batao\" is a scam, never share"
        val a = engine().evaluate(raw)
        assertTrue("the warning itself is protective", a.hasProtectiveIndicator)
        assertTrue(a.quotes().any { it.contains("never share") })
        assertTrue("the quoted region must be exposed", a.quotedRegions.isNotEmpty())
        assertTrue("an ask inside the quote is not the sender's ask",
            a.isQuoted(spanOf(raw, "jo OTP aaya hai woh batao")))
        assertTrue("scam example must be named as a quote",
            a.indicators.any { it.titleHinglish.contains("scam", ignoreCase = true) })
    }

    @Test
    fun `single quotes and reported speech also expose quoted regions`() {
        val raw = "Log 'jo OTP aaya hai woh batao' bolte hue paise maangte hain, kisi ko na batayein"
        val a = engine().evaluate(raw)
        assertTrue(a.quotedRegions.isNotEmpty())
        assertTrue(a.isQuoted(spanOf(raw, "jo OTP aaya hai woh batao")))
        assertTrue(a.hasProtectiveIndicator)
    }

    @Test
    fun `an apostrophe in a contraction is not a quoted region`() {
        val raw = "Don't share your OTP with anyone."
        val a = engine().evaluate(raw)
        assertTrue(a.quotedRegions.isEmpty())
        assertTrue(a.hasProtectiveIndicator)
    }

    // ---- negation scoping -------------------------------------------------------------------

    @Test
    fun `negation scope stops at the clause separator`() {
        val raw = "Do not share this code with anyone. Please call us for help."
        val a = engine().evaluate(raw)
        assertTrue(a.negations.isNotEmpty())
        val fullStop = raw.indexOf('.')
        for (scope in a.negations) {
            assertTrue("scope $scope leaked past the sentence break at $fullStop", scope.end <= fullStop)
        }
    }

    @Test
    fun `devanagari negation is recognised and scoped`() {
        val raw = "\u0906\u092A\u0915\u093E OTP \u0915\u093F\u0938\u0940 \u0915\u0947 \u0938\u093E\u0925 \u0938\u093E\u091D\u093E \u0928 \u0915\u0930\u0947\u0902\u0964"
        val e = engine()
        val a = e.evaluate(raw)
        assertTrue("protective indicator expected for Devanagari advice", a.hasProtectiveIndicator)
        assertTrue(a.negations.isNotEmpty())
        assertTrue(e.isNegated(spanOf(raw, "\u0938\u093E\u091D\u093E")))
    }

    @Test
    fun `roman-hindi post-positional negation is scoped around the verb`() {
        val raw = "OTP kisi ke saath share na karein"
        val e = engine()
        val a = e.evaluate(raw)
        assertTrue(a.hasProtectiveIndicator)
        assertTrue(e.isNegated(spanOf(raw, "share")))
    }

    // ---- over- and under-negation -----------------------------------------------------------

    @Test
    fun `under-negation - 'share karna hai' is an ask, not protective advice`() {
        val raw = "Bhai OTP share karna hai jaldi"
        val a = engine().evaluate(raw)
        assertFalse(a.hasProtectiveIndicator)
        assertTrue(a.negations.isEmpty())
        assertEquals(1.0, a.riskReduction, 0.0)
    }

    @Test
    fun `over-negation - 'mat bhoolna OTP bata dena' is a reminder, not protection`() {
        val raw = "Kal tak mat bhoolna OTP bata dena"
        val a = engine().evaluate(raw)
        assertFalse("a bare 'mat' must not become protective", a.hasProtectiveIndicator)
        assertTrue(a.negations.isEmpty())
        assertEquals(1.0, a.riskReduction, 0.0)
    }

    @Test
    fun `over-negation - the secrecy stage 'kisi ko mat batana' is not protective`() {
        val raw = "Kisi ko mat batana, yeh confidential case hai, warna FIR ho jayegi."
        val a = engine().evaluate(raw)
        assertFalse("secrecy pressure must not reduce risk", a.hasProtectiveIndicator)
        assertTrue(a.negations.isEmpty())
        assertEquals(1.0, a.riskReduction, 0.0)
    }

    @Test
    fun `secrecy pressure with a threat is not protective and does not reduce the score`() {
        val raw = "Main CBI officer hoon. Kisi ko na batayein, warna FIR ho jayegi. " +
            "Abhi Rs 50,000 transfer karo."
        val e = engine()
        val a = e.evaluate(raw)
        assertFalse("'kisi ko na batayein, warna FIR' must not read as protective advice",
            a.hasProtectiveIndicator)
        assertEquals(1.0, a.riskReduction, 0.0)
        assertEquals("no reduction may be applied", 100, e.reduce(100, false))
    }

    @Test
    fun `bare do-not-tell without a named credential is not protective`() {
        val raw = "Kisi ko na batayein."
        val a = engine().evaluate(raw)
        assertFalse(a.hasProtectiveIndicator)
        assertEquals(1.0, a.riskReduction, 0.0)
    }

    @Test
    fun `do-not-tell next to a named credential is protective`() {
        val raw = "Aapka OTP 123456 hai. Kisi ko na batayein."
        val a = engine().evaluate(raw)
        assertTrue(a.hasProtectiveIndicator)
        assertTrue(a.quotes().isNotEmpty())
        assertTrue(a.riskReduction < 1.0)
    }

    // ---- other legitimate classes ------------------------------------------------------------

    @Test
    fun `bank never asks phrasing is protective`() {
        val raw = "Aapka bank kabhi bhi OTP nahi maangta."
        val a = engine().evaluate(raw)
        assertTrue(a.hasProtectiveIndicator)
        assertTrue(a.quotes().any { it.contains("nahi maangta") })
    }

    @Test
    fun `information-only phrasing is protective`() {
        val raw = "Yeh message bank ki taraf se jaankari ke liye hai."
        val a = engine().evaluate(raw)
        assertTrue(a.hasProtectiveIndicator)
        assertTrue(a.riskReduction < 1.0)
    }

    // ---- bounded reduction -------------------------------------------------------------------

    @Test
    fun `reduce is bypassed by an independent verified strong ask`() {
        val raw = "123456 is your OTP. Do not share it with anyone."
        val e = engine()
        val a = e.evaluate(raw)
        assertTrue(a.riskReduction < 1.0)
        assertEquals("a protective phrase must never erase a real ask", 100, e.reduce(100, true))
        assertEquals(90, e.reduce(90, true))
        assertEquals((100 * a.riskReduction).roundToInt(), e.reduce(100, false))
        assertTrue(e.reduce(100, false) > 60)
        assertEquals(0, e.reduce(0, false))
    }

    @Test
    fun `reduce is floored at 65 percent no matter how many indicators fire`() {
        val raw = "Rs 5,000 credited to A/c XX1234. Bank kabhi OTP nahi maangta. " +
            "OTP kisi ke saath share na karein. Yeh jaankari ke liye hai. Visit your branch for help."
        val e = engine()
        val a = e.evaluate(raw)
        assertTrue("several indicators expected", a.indicators.size >= 3)
        assertEquals(LegitimateIndicators.MIN_REDUCTION, a.riskReduction, 1e-9)
        assertEquals(65, e.reduce(100, false))
        assertEquals(52, e.reduce(80, false))
        assertEquals(80, e.reduce(80, true))
    }

    @Test
    fun `no indicators means no reduction at all`() {
        val e = engine()
        e.evaluate("Aaj shaam 7 baje milte hain.")
        assertEquals(1.0, e.currentRiskReduction, 0.0)
        assertEquals(73, e.reduce(73, false))
    }

    @Test
    fun `indicators are stable across repeated evaluation`() {
        val raw = "Rs 5,000 credited to A/c XX1234. OTP kisi ke saath share na karein."
        val e = engine()
        val first = e.evaluate(raw)
        val second = e.evaluate(raw)
        assertEquals(first, second)
        assertEquals(first.indicators.map { it.id }, second.indicators.map { it.id })
    }
}
