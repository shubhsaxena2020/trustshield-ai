package ai.trustshield.android.core.normalize

import ai.trustshield.android.core.model.Evidence
import ai.trustshield.android.core.model.NormalizedText
import ai.trustshield.android.core.model.Severity
import ai.trustshield.android.core.model.Strength
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the two whitelist-gated obfuscation folds added to [HinglishNormalizer]: leetspeak
 * (`5bi`, `b1jli`) and letter spacing (`k y c`, `a a d h a a r`, `k🔥y🔥c`).
 *
 * Every fold is a no-op outside its whitelist, so the negative cases here matter as much as the
 * positive ones: ordinary numbers, dates, `a b c` runs and two-letter runs must come back exactly
 * as written. The span cases exist because the folds rewrite characters the user never typed —
 * `rawQuoteOf(...)` must still return the raw digits and spaces, or every `Evidence` quote built on
 * top of the normalizer would stop verifying against the input.
 *
 * JVM unit tests only: no Android runtime, no device, no network. Not a quality measurement.
 */
class ObfuscationNormalizerTest {

    private val normalizer = HinglishNormalizer()

    // ---- fold 1: leetspeak ------------------------------------------------------------------

    @Test
    fun `digit substituted whitelist words fold to their plain spelling`() {
        assertEquals("bijli", normalizer.normalize("b1jli").text)
        assertEquals("sbi", normalizer.normalize("5bi").text)
        assertEquals("upi", normalizer.normalize("up1").text)
        assertEquals("block", normalizer.normalize("bl0ck").text)
        assertEquals("account", normalizer.normalize("4ccount").text)
        // `1` is ambiguous: `b1ock` needs the `l` reading, `b1jli` the `i` reading.
        assertEquals("block", normalizer.normalize("b1ock").text)
        assertEquals("pin", normalizer.normalize("p1n").text)
    }

    @Test
    fun `leet fold respects the whitelist inside a sentence`() {
        val raw = "aapka b1jli bill 4ccount block ho gaya"
        val n = normalizer.normalize(raw)
        assertEquals("aapka bijli bill account block ho gaya", n.text)
    }

    @Test
    fun `otp still arrives as otp in either spelling`() {
        assertTrue(normalizer.normalize("0tp batao").text.contains("otp"))
        assertTrue(normalizer.normalize("0TP batao").text.contains("otp"))
        assertEquals("otp batao", normalizer.normalize("0tp batao").text)
    }

    @Test
    fun `leet fold keeps the raw digits in its quote`() {
        val raw = "b1jli bill aa gayi hai"
        val n = normalizer.normalize(raw)
        assertEquals("bijli bill aa gayi hai", n.text)

        // The whole token quotes the raw spelling, digits included, so no quote is fabricated.
        assertEquals("b1jli", n.rawQuoteOf(0, 5))
        assertTrue(raw.contains(n.rawQuoteOf(0, 5)))
        // A single folded character still quotes only the raw code point it replaced.
        assertEquals("1", n.rawQuoteOf(1, 2))
        assertEquals(1, n.rawStart(1))
        assertEquals(2, n.rawEnd(1))
        // The untouched rest of the message maps back to itself.
        assertEquals("bill", n.rawQuoteOf(6, 10))
    }

    // ---- fold 2: letter spacing --------------------------------------------------------------

    @Test
    fun `spaced letters fold to the whitelisted token`() {
        assertEquals("kyc update karo", normalizer.normalize("k y c update karo").text)
        assertEquals("sbi", normalizer.normalize("s b i").text)
        assertEquals("upi", normalizer.normalize("u p i").text)
        assertEquals("bank", normalizer.normalize("b a n k").text)
        assertEquals("aadhaar", normalizer.normalize("a a d h a a r").text)
        assertEquals("cvv", normalizer.normalize("c v v").text)
        assertEquals("dob", normalizer.normalize("d o b").text)
    }

    @Test
    fun `existing otp spellings are not disturbed by the spacing fold`() {
        assertEquals("otp", normalizer.normalize("O T P").text)
        assertEquals("otp", normalizer.normalize("O-T-P").text)
        assertEquals("otp", normalizer.normalize("O.T.P").text)
        assertEquals("otp bhejo", normalizer.normalize("O T P bhej do").text)
        assertTrue(normalizer.normalize("O T P bhej do").text.contains("otp"))
        assertEquals("pin", normalizer.normalize("P I N").text)
    }

    @Test
    fun `spaced fold keeps the raw spaces in its quote`() {
        val raw = "k y c update karo"
        val n = normalizer.normalize(raw)
        assertEquals("kyc update karo", n.text)

        assertEquals("k y c", n.rawQuoteOf(0, 3))
        assertTrue(raw.contains(n.rawQuoteOf(0, 3)))
        assertEquals(0, n.rawStart(0))
        assertEquals(5, n.rawEnd(2))
        // The words after the fold are still mapped one character at a time.
        assertEquals("update", n.rawQuoteOf(4, 10))
    }

    @Test
    fun `symbol and emoji separated tokens fold with an honest span`() {
        val spaced = normalizer.normalize("s.b.i")
        assertEquals("sbi", spaced.text)
        assertEquals("s.b.i", spaced.rawQuoteOf(0, 3))

        val raw = "k🔥y🔥c kar do"
        val emoji = normalizer.normalize(raw)
        assertEquals("kyc kar do", emoji.text)
        assertEquals("k🔥y🔥c", emoji.rawQuoteOf(0, 3))
        // The whole raw run, emoji included, is the span of the joined token.
        assertEquals("k🔥y🔥c".length, emoji.rawEnd(2))
        assertTrue(raw.contains(emoji.rawQuoteOf(0, 3)))
    }

    // ---- negatives: everything else must be untouched -----------------------------------------

    @Test
    fun `plain text passes through the new folds unchanged`() {
        for (raw in listOf(
            "aapka account safe hai",
            "the quick brown fox",
            "optional total stop topper",
            "mera pink phone",
            "turant abhi ab se pehle",
        )) {
            val n = normalizer.normalize(raw)
            assertEquals(raw, n.text)
            assertSpanInvariant(n)
        }
    }

    @Test
    fun `ordinary numbers and account numbers are not leetspeak`() {
        assertEquals("rs 5000", normalizer.normalize("Rs 5000").text)
        assertEquals("4567", normalizer.normalize("4567").text)
        assertEquals("a/c 1234567890", normalizer.normalize("a/c 1234567890").text)
        assertEquals("otp 1234 hai", normalizer.normalize("OTP 1234 hai").text)
        assertEquals("123456", normalizer.normalize("123456").text)
    }

    @Test
    fun `short letter runs and non whitelisted runs are not joined`() {
        assertEquals("a b c", normalizer.normalize("a b c").text)
        assertEquals("x y z", normalizer.normalize("x y z").text)
        assertEquals("i am fine", normalizer.normalize("i am fine").text)
        assertEquals("e d", normalizer.normalize("e d").text)
        assertEquals("i.e.", normalizer.normalize("i.e.").text)
        assertEquals("k y", normalizer.normalize("k y").text)
        // A longer run is never folded from its prefix.
        assertEquals("a a d h a a r s", normalizer.normalize("a a d h a a r s").text)
        assertEquals("k y c d e", normalizer.normalize("k y c d e").text)
    }

    // ---- quote discipline ---------------------------------------------------------------------

    @Test
    fun `evidence quotes built from folded spans verify against the raw input`() {
        val raw = "aapka b1jli bill aur k y c update"
        val n = normalizer.normalize(raw)

        val leetAt = n.text.indexOf("bijli")
        val leetQuote = n.rawQuoteOf(leetAt, leetAt + "bijli".length)
        assertEquals("b1jli", leetQuote)

        val spacedAt = n.text.indexOf("kyc")
        val spacedQuote = n.rawQuoteOf(spacedAt, spacedAt + "kyc".length)
        assertEquals("k y c", spacedQuote)

        for (quote in listOf(leetQuote, spacedQuote)) {
            val evidence = Evidence.verified(
                input = raw,
                module = "obfuscation-normalizer-test",
                titleHinglish = "Obfuscated token",
                detail = "Normalized token ka raw quote",
                severity = Severity.MEDIUM,
                strength = Strength.MODERATE,
                quote = quote,
            )
            assertNotNull(evidence.quote)
            assertTrue(evidence.quoteVerifiesAgainst(raw))
            assertEquals(quote, evidence.quote)
        }
    }

    @Test
    fun `every fold sample maps back to a real raw span`() {
        for (raw in SPAN_SAMPLES) {
            assertSpanInvariant(normalizer.normalize(raw))
        }
    }

    @Test
    fun `folds are deterministic and idempotent`() {
        for (raw in SPAN_SAMPLES) {
            val first = normalizer.normalize(raw)
            val second = normalizer.normalize(raw)
            assertEquals(first.text, second.text)
            assertEquals(first.text, normalizer.normalize(first.text).text)
        }
    }

    // ---- length cap ---------------------------------------------------------------------------

    @Test
    fun `truncation happens before the folds and is unchanged by them`() {
        val filler = "z".repeat(HinglishNormalizer.MAX_TEXT_CHARS - 4)
        val raw = filler + " k y c"
        assertEquals(HinglishNormalizer.MAX_TEXT_CHARS + 2, raw.length)
        assertTrue(normalizer.isTruncated(raw))

        val n = normalizer.normalize(raw)
        // The `c` sits past the cap, so the run is a two-letter `k y`: no fold, no growth.
        assertFalse(n.text.contains("kyc"))
        assertTrue(n.text, n.text.endsWith("k y"))
        assertEquals(HinglishNormalizer.MAX_TEXT_CHARS, n.length)
        assertEquals(raw, n.raw)
        assertEquals(HinglishNormalizer.MAX_TEXT_CHARS, n.rawEnd(n.length - 1))
        assertSpanInvariant(n)
    }

    @Test
    fun `a folded token at exactly the cap still normalizes`() {
        val pad = "z".repeat(HinglishNormalizer.MAX_TEXT_CHARS - 6) + " "
        val raw = pad + "k y c"
        assertEquals(HinglishNormalizer.MAX_TEXT_CHARS, raw.length)
        assertFalse(normalizer.isTruncated(raw))

        val n = normalizer.normalize(raw)
        assertTrue(n.text, n.text.endsWith(" kyc"))
        assertEquals("k y c", n.rawQuoteOf(n.length - 3, n.length))
        assertSpanInvariant(n)
    }

    // ---- helpers ------------------------------------------------------------------------------

    private fun assertSpanInvariant(n: NormalizedText) {
        for (i in 0 until n.length) {
            val start = n.rawStart(i)
            val end = n.rawEnd(i)
            assertTrue("rawStart($i)=$start must be >= 0", start >= 0)
            assertTrue("rawStart($i)=$start > rawEnd($i)=$end", start <= end)
            assertTrue("rawEnd($i)=$end past raw length ${n.raw.length}", end <= n.raw.length)
            val quote = n.rawQuoteOf(i, i + 1)
            assertTrue("empty quote at $i in <${n.raw}>", quote.isNotEmpty())
            assertEquals(n.raw.substring(start, end), quote)
        }
    }

    private companion object {
        val SPAN_SAMPLES: List<String> = listOf(
            "0tp batao",
            "b1jli bill aa gayi hai",
            "aapka b1jli bill 4ccount block ho gaya",
            "5bi up1 p1n bl0ck",
            "k y c update karo",
            "s b i",
            "a a d h a a r",
            "s.b.i",
            "k🔥y🔥c kar do",
            "a b c",
            "i am fine",
            "Rs 5000",
            "a/c 1234567890",
        )
    }
}
