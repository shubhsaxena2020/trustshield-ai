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
 * A02 gate for [HinglishNormalizer] (REQUIREMENTS F02, report §5).
 *
 * JVM unit tests only: no Android runtime, no device, no network. Each case pins one of the
 * behaviours the rule packages (A03/A04) and the evidence contract (A01) rely on.
 */
class NormalizerTest {

    private val normalizer = HinglishNormalizer()

    // ---- empty / junk input ----------------------------------------------------------------

    @Test
    fun `empty input produces an empty normalized text`() {
        val n = normalizer.normalize("")
        assertEquals("", n.text)
        assertEquals(0, n.length)
        assertEquals("", n.rawQuoteOf(0, 0))
        assertFalse(normalizer.isTruncated(""))
    }

    @Test
    fun `emoji-only input yields no letters and a valid span map`() {
        val raw = "🔥🔥😀👨\u200D💻"
        val n = normalizer.normalize(raw)
        assertFalse(n.text.any { Character.isLetter(it) })
        assertEquals(0, normalizer.scriptProfile(raw).unsupportedScripts.size)
        assertSpanInvariant(n)
    }

    // ---- the OTP token ---------------------------------------------------------------------

    @Test
    fun `emoji between otp letters collapses to the otp token`() {
        val raw = "O🔥T🔥P"
        val n = normalizer.normalize(raw)
        assertEquals("otp", n.text)
        assertEquals(raw, n.rawQuoteOf(0, 3))
    }

    @Test
    fun `zero-width characters inside otp are removed`() {
        val raw = "O\u200BT\u200DP\uFEFF"
        val n = normalizer.normalize(raw)
        assertEquals("otp", n.text)
        assertSpanInvariant(n)
    }

    @Test
    fun `dotted otp normalizes to otp`() {
        val n = normalizer.normalize("mera O.T.P batao")
        assertEquals("mera otp batao", n.text)
    }

    @Test
    fun `spaced and hyphenated otp normalize to otp`() {
        assertEquals("otp", normalizer.normalize("O T P").text)
        assertEquals("otp", normalizer.normalize("O-T-P").text)
        assertEquals("otp", normalizer.normalize("O_T_P").text)
    }

    @Test
    fun `zero standing in for the letter o in otp normalizes`() {
        assertEquals("otp", normalizer.normalize("0TP").text)
        assertEquals("otp", normalizer.normalize("0tp").text)
    }

    @Test
    fun `devanagari otp word normalizes to otp`() {
        assertEquals("otp", normalizer.normalize("ओटीपी").text)
        assertEquals("otp", normalizer.normalize("ओ टी पी").text)
    }

    @Test
    fun `pin obfuscation normalizes while pink survives`() {
        assertEquals("pin", normalizer.normalize("P.I.N").text)
        assertEquals("मेरा pin है", normalizer.normalize("मेरा पिन है").text)
        assertEquals("pink", normalizer.normalize("pink").text)
        assertEquals("pin", normalizer.normalize("pin").text)
    }

    @Test
    fun `ordinary words that contain the otp letters are untouched`() {
        val raw = "optional total stop ott topper"
        val n = normalizer.normalize(raw)
        assertEquals(raw, n.text)
        assertFalse(n.text.contains("otp"))
    }

    // ---- digits ----------------------------------------------------------------------------

    @Test
    fun `devanagari digits and otp normalize together`() {
        val n = normalizer.normalize("आपका ओटीपी ४५६७ है")
        assertTrue(n.text, n.text.contains("4567"))
        assertTrue(n.text, n.text.contains("otp"))
    }

    @Test
    fun `bengali and tamil digits become ascii digits`() {
        assertTrue(normalizer.normalize("৳ ৪৫৬৭").text.contains("4567"))
        assertTrue(normalizer.normalize("௪௫௬௭").text.contains("4567"))
    }

    // ---- length cap ------------------------------------------------------------------------

    @Test
    fun `exactly the cap is accepted and not truncated`() {
        val raw = "a".repeat(HinglishNormalizer.MAX_TEXT_CHARS)
        assertFalse(normalizer.isTruncated(raw))
        val n = normalizer.normalize(raw)
        assertEquals(HinglishNormalizer.MAX_TEXT_CHARS, n.length)
        assertSpanInvariant(n)
    }

    @Test
    fun `one char over the cap truncates without throwing and keeps spans`() {
        val raw = "a".repeat(HinglishNormalizer.MAX_TEXT_CHARS + 1)
        assertTrue(normalizer.isTruncated(raw))
        val n = normalizer.normalize(raw)
        assertEquals(HinglishNormalizer.MAX_TEXT_CHARS, n.length)
        assertSpanInvariant(n)
        assertEquals(raw, n.raw)
    }

    // ---- Roman-Hindi canonicalization ------------------------------------------------------

    @Test
    fun `tell variants collapse to batao`() {
        assertEquals("batao", normalizer.normalize("btao").text)
        assertEquals("batao", normalizer.normalize("bataiye").text)
        assertEquals("batao", normalizer.normalize("bata do").text)
        assertEquals("batao", normalizer.normalize("bataao").text)
        assertEquals("batao", normalizer.normalize("bolo").text)
        assertEquals("batao", normalizer.normalize("bataye").text)
        assertEquals("batao", normalizer.normalize("bata").text)
    }

    @Test
    fun `send variants collapse to bhejo`() {
        assertEquals("bhejo", normalizer.normalize("bhej do").text)
        assertEquals("bhejo", normalizer.normalize("bhejna").text)
        assertEquals("bhejo", normalizer.normalize("bhejo").text)
    }

    @Test
    fun `money word variants collapse to paise`() {
        val n = normalizer.normalize("paisa pese rupaye paise")
        assertEquals("paise paise paise paise", n.text)
    }

    @Test
    fun `negation words are never deleted`() {
        val n = normalizer.normalize("OTP mat karo, kisi ko na bhejo")
        assertTrue(n.text, n.text.contains("mat"))
        assertTrue(n.text, n.text.contains(" na "))
        assertTrue(n.text, n.text.contains("bhejo"))
        assertTrue(n.text, n.text.contains("otp"))
    }

    @Test
    fun `urgency words are left alone`() {
        val n = normalizer.normalize("turant abhi ab se pehle")
        assertEquals("turant abhi ab se pehle", n.text)
    }

    // ---- mixed script and unsupported script ----------------------------------------------

    @Test
    fun `mixed roman and devanagari message keeps both scripts`() {
        val n = normalizer.normalize("Aapka SBI खाता block हो गया hai")
        assertEquals("aapka sbi खाता block हो गया hai", n.text)
        val profile = normalizer.scriptProfile("Aapka SBI खाता block हो गया hai")
        assertTrue(profile.hasRoman)
        assertTrue(profile.hasDevanagari)
        assertTrue(profile.unsupportedScripts.isEmpty())
    }

    @Test
    fun `tamil input is reported as an unsupported script`() {
        val profile = normalizer.scriptProfile("இது ஒரு செய்தி")
        assertFalse(profile.hasRoman)
        assertTrue(profile.unsupportedScripts.contains("Tamil"))
    }

    @Test
    fun `cyrillic input is reported as an unsupported script`() {
        val profile = normalizer.scriptProfile("Привет друг")
        assertTrue(profile.unsupportedScripts.contains("Cyrillic"))
        assertFalse(profile.hasDevanagari)
    }

    @Test
    fun `every other unsupported block in the requirement list is detected`() {
        val samples = mapOf(
            "Telugu" to "తెలుగు",
            "Bengali" to "বাংলা",
            "Gujarati" to "ગુજરાતી",
            "Gurmukhi" to "ਪੰਜਾਬੀ",
            "Kannada" to "ಕನ್ನಡ",
            "Malayalam" to "മലയാളം",
            "Arabic" to "مرحبا",
            "Han" to "你好",
        )
        for ((expected, sample) in samples) {
            val profile = normalizer.scriptProfile(sample)
            assertTrue("$expected not detected in $sample", profile.unsupportedScripts.contains(expected))
        }
    }

    @Test
    fun `pure hindi and english report no unsupported script`() {
        assertTrue(normalizer.scriptProfile("आपका ओटीपी 4567 है").unsupportedScripts.isEmpty())
        assertTrue(normalizer.scriptProfile("Please verify your account").unsupportedScripts.isEmpty())
        val profile = normalizer.scriptProfile("hi")
        assertTrue(profile.hasRoman)
        assertTrue(profile.hasSupportedScript)
        assertFalse(profile.hasUnsupportedScript)
    }

    // ---- whitespace / controls -------------------------------------------------------------

    @Test
    fun `tabs collapse, controls drop and newlines survive`() {
        val n = normalizer.normalize("  aapka\t\t\u0007OTP\nbatao  ")
        assertEquals("aapka otp\nbatao", n.text)
        assertFalse(n.text.contains('\t'))
        assertFalse(n.text.contains('\u0007'))
    }

    // ---- the span invariant ----------------------------------------------------------------

    @Test
    fun `every normalized character maps back to a real raw span`() {
        for (raw in SPAN_SAMPLES) {
            val n = normalizer.normalize(raw)
            assertSpanInvariant(n)
        }
    }

    @Test
    fun `first normalized character maps to its source after trimming`() {
        val n = normalizer.normalize("  OTP batao")
        assertEquals(2, n.rawStart(0))
        assertEquals(5, n.rawEnd(0))
        assertEquals("OTP", n.rawQuoteOf(0, 1))
        assertEquals("OTP", n.rawQuoteOf(0, 3))
    }

    @Test
    fun `known tokens map back to an exact raw quote`() {
        val raw = "Sir 0TP batao jaldi"
        val n = normalizer.normalize(raw)
        assertEquals("sir otp batao jaldi", n.text)

        val otpAt = n.text.indexOf("otp")
        val otpQuote = n.rawQuoteOf(otpAt, otpAt + 3)
        assertEquals("0TP", otpQuote)
        assertTrue(raw.contains(otpQuote))

        val bataoAt = n.text.indexOf("batao")
        val bataoQuote = n.rawQuoteOf(bataoAt, bataoAt + "batao".length)
        assertEquals("batao", bataoQuote)
        assertTrue(raw.contains(bataoQuote))
    }

    @Test
    fun `substituted token quotes the raw spelling it replaced`() {
        val raw = "Sir btao jaldi"
        val n = normalizer.normalize(raw)
        val at = n.text.indexOf("batao")
        val quote = n.rawQuoteOf(at, at + "batao".length)
        assertEquals("btao", quote)
        assertTrue(raw.contains(quote))
    }

    @Test
    fun `evidence built from a normalized raw quote verifies against the input`() {
        val raw = "Sir, mera O.T.P batao warna account band"
        val n = normalizer.normalize(raw)
        val at = n.text.indexOf("otp")
        val quote = n.rawQuoteOf(at, at + 3)
        assertTrue(raw.contains(quote))

        val evidence = Evidence.verified(
            input = raw,
            module = "normalizer-test",
            titleHinglish = "OTP maanga ja raha hai",
            detail = "Normalized token ka raw quote",
            severity = Severity.HIGH,
            strength = Strength.MODERATE,
            quote = quote,
        )
        assertNotNull(evidence.quote)
        assertTrue(evidence.quoteVerifiesAgainst(raw))
        assertEquals(quote, evidence.quote)
    }

    @Test
    fun `normalization is deterministic and idempotent`() {
        for (raw in SPAN_SAMPLES) {
            val first = normalizer.normalize(raw)
            val second = normalizer.normalize(raw)
            assertEquals(first.text, second.text)
            assertEquals(first.text, normalizer.normalize(first.text).text)
        }
    }

    // ---- helpers ---------------------------------------------------------------------------

    private fun assertSpanInvariant(n: NormalizedText) {
        for (i in 0 until n.length) {
            val start = n.rawStart(i)
            val end = n.rawEnd(i)
            assertTrue("rawStart($i)=$start must be >= 0", start >= 0)
            assertTrue("rawStart($i)=$start > rawEnd($i)=$end", start <= end)
            assertTrue("rawEnd($i)=$end past raw length ${n.raw.length}", end <= n.raw.length)
            val quote = n.rawQuoteOf(i, i + 1)
            assertTrue("empty quote at $i in <${n.raw}>", quote.isNotEmpty())
            assertTrue(
                "quote <$quote> is not a substring of <${n.raw}>",
                n.raw.substring(start, end) == quote,
            )
        }
    }

    private companion object {
        val SPAN_SAMPLES: List<String> = listOf(
            "",
            "O🔥T🔥P",
            "O\u200BT\u200BP",
            "Sir 0TP batao jaldi",
            "आपका ओटीपी ४५६७ है",
            "Aapka SBI खाता block हो गया hai",
            "btao bhej do paisa pese rupaye",
            "  O.T.P   batao\t\tjaldi  ",
            "optional total stop ott topper",
            "P.I.N मेरा पिन है",
            "৳ ৪৫৬৭ ௪௫௬௭ \u0007 \n",
        )
    }
}
