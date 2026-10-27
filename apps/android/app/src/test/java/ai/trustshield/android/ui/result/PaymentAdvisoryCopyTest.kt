package ai.trustshield.android.ui.result

import ai.trustshield.android.core.payment.PaymentAdvisoryVerdict
import ai.trustshield.android.core.payment.PaymentHint
import ai.trustshield.android.core.payment.PaymentHintKind
import ai.trustshield.android.core.payment.PaymentHintSeverity
import ai.trustshield.android.ui.theme.ToneCopy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [PaymentAdvisoryCopy] — pure JVM, no device, no Compose, no `android.*`.
 *
 * They pin the four things the payment advisory copy must never break:
 *  1. the Hinglish confirmation line is character-for-character the mandated sentence;
 *  2. every [PaymentHintKind] has real copy in all three languages, with no leftover template
 *     marker and no raw UPI ID;
 *  3. no string carries an absolute claim ("100%", "guaranteed", "safe to pay", "verified") — the
 *     same class of wording `AbsoluteSafetyGuard` exists to scrub;
 *  4. rupee formatting is hand-grouped and locale-proof.
 *
 * The fixtures below are hand-written test data, not model output.
 */
class PaymentAdvisoryCopyTest {

    // ------------------------------------------------------------------ fixtures

    private val maskedPayeeFixture = "sho***@okaxis"

    private fun hint(
        kind: PaymentHintKind,
        severity: PaymentHintSeverity = PaymentHintSeverity.CAUTION,
        amountMinor: Long? = null,
        maskedPayee: String = "",
        count: Int = 0
    ) = PaymentHint(
        kind = kind,
        severity = severity,
        amountMinor = amountMinor,
        maskedPayee = maskedPayee,
        count = count
    )

    /** One representative hint per kind: the amount/payee kinds get a value, the rest do not. */
    private fun fixtureHints(): List<PaymentHint> = PaymentHintKind.entries.map { kind ->
        hint(
            kind = kind,
            severity = PaymentHintSeverity.WARNING,
            amountMinor = when (kind) {
                PaymentHintKind.AMOUNT_LARGE -> 1_200_000L
                PaymentHintKind.AMOUNT_ROUND_LARGE -> 2_000_000L
                else -> null
            },
            maskedPayee = when (kind) {
                PaymentHintKind.PAYEE_MATCHES_REGISTERED,
                PaymentHintKind.PAYEE_NOT_REGISTERED -> maskedPayeeFixture
                else -> ""
            }
        )
    }

    /** Same kinds but with no amount and no payee, to exercise the generic fallbacks. */
    private fun bareHints(): List<PaymentHint> =
        PaymentHintKind.entries.map { kind -> hint(kind = kind, severity = PaymentHintSeverity.INFO) }

    private fun allVerdicts(): List<PaymentAdvisoryVerdict> = PaymentAdvisoryVerdict.entries

    /**
     * Every distinct [ToneCopy] this object can produce for the fixture set (filled hints only, so
     * the list has no repeats — the bare fallbacks are scanned separately in [allStrings]).
     */
    private fun allCopy(): List<ToneCopy> {
        val verdictCopy = allVerdicts().flatMap { verdict ->
            listOf(
                PaymentAdvisoryCopy.verdictHeadline(verdict),
                PaymentAdvisoryCopy.verdictAdvice(verdict)
            )
        }
        return listOf(PaymentAdvisoryCopy.SECTION_TITLE, PaymentAdvisoryCopy.CONFIRMATION) +
            verdictCopy +
            fixtureHints().map { PaymentAdvisoryCopy.forHint(it) }
    }

    private fun stringsOf(copy: ToneCopy, into: MutableList<String>) {
        into += copy.hinglish
        into += copy.english
        into += copy.hindi
    }

    private fun allStrings(): List<String> {
        val out = mutableListOf<String>()
        allCopy().forEach { stringsOf(it, out) }
        // The no-amount / no-payee fallbacks too, so those sentences are scanned as well.
        bareHints().forEach { stringsOf(PaymentAdvisoryCopy.forHint(it), out) }
        return out
    }

    private fun devanagariRatio(text: String): Double {
        if (text.isEmpty()) return 0.0
        val devanagari = text.count { it in '\u0900'..'\u097F' }
        return devanagari.toDouble() / text.length
    }

    private val forbiddenClaimTokens = listOf(
        "100%",
        "100 %",
        "guaranteed",
        "guarantee",
        "safe to pay",
        "verified",
        "risk-free",
        "risk free"
    )

    // ------------------------------------------------------------------ the confirmation line

    @Test
    fun confirmationHinglishIsThePinnedSentenceCharacterForCharacter() {
        assertEquals(
            "Pakka confirmation sirf aapke bank SMS ya UPI app ki history mein milega.",
            PaymentAdvisoryCopy.CONFIRMATION.hinglish
        )
    }

    @Test
    fun confirmationEnglishIsThePinnedSentence() {
        assertEquals(
            "Final confirmation only comes from your bank SMS or your UPI app's history.",
            PaymentAdvisoryCopy.CONFIRMATION.english
        )
    }

    @Test
    fun confirmationHindiIsDevanagariAndNonBlank() {
        val hindi = PaymentAdvisoryCopy.CONFIRMATION.hindi
        assertTrue("confirmation Hindi must not be blank", hindi.isNotBlank())
        assertTrue("confirmation Hindi must be Devanagari", devanagariRatio(hindi) > 0.6)
        assertTrue(hindi.contains("पुष्टि"))
    }

    @Test
    fun confirmationAlwaysPointsToAuthoritativePaymentRecordsWithoutClaimingOutcome() {
        val confirmation = PaymentAdvisoryCopy.CONFIRMATION
        assertTrue(confirmation.hinglish.contains("bank SMS") && confirmation.hinglish.contains("UPI app"))
        assertTrue(confirmation.english.contains("bank SMS") && confirmation.english.contains("UPI app"))
        assertTrue(confirmation.hindi.contains("बैंक SMS") && confirmation.hindi.contains("UPI ऐप"))
        for (text in listOf(confirmation.hinglish, confirmation.english, confirmation.hindi)) {
            val lowered = text.lowercase()
            assertFalse(lowered.contains("success"))
            assertFalse(lowered.contains("fraud"))
            assertFalse(lowered.contains("payment received"))
        }
    }

    @Test
    fun confirmationRendersPerLanguage() {
        assertEquals(
            PaymentAdvisoryCopy.CONFIRMATION.hinglish,
            PaymentAdvisoryCopy.CONFIRMATION.pick(UiLanguage.HINGLISH)
        )
        assertEquals(
            PaymentAdvisoryCopy.CONFIRMATION.english,
            PaymentAdvisoryCopy.CONFIRMATION.pick(UiLanguage.ENGLISH)
        )
        assertEquals(
            PaymentAdvisoryCopy.CONFIRMATION.hindi,
            PaymentAdvisoryCopy.CONFIRMATION.pick(UiLanguage.HINDI)
        )
    }

    // ------------------------------------------------------------------ section title

    @Test
    fun sectionTitleIsBilingualAndPicksPerLanguage() {
        assertTrue(PaymentAdvisoryCopy.SECTION_TITLE.hinglish.isNotBlank())
        assertTrue(PaymentAdvisoryCopy.SECTION_TITLE.english.isNotBlank())
        assertTrue(PaymentAdvisoryCopy.SECTION_TITLE.hindi.isNotBlank())
        assertTrue(devanagariRatio(PaymentAdvisoryCopy.SECTION_TITLE.hindi) > 0.8)
        assertEquals("Payment check", PaymentAdvisoryCopy.SECTION_TITLE.pick(UiLanguage.ENGLISH))
        assertEquals(PaymentAdvisoryCopy.SECTION_TITLE.hindi, PaymentAdvisoryCopy.SECTION_TITLE.pick(UiLanguage.HINDI))
    }

    // ------------------------------------------------------------------ hint copy

    @Test
    fun forHintCoversEveryKindWithNonBlankCopyInAllThreeLanguages() {
        val hints = fixtureHints()
        assertEquals("fixture must cover every kind", PaymentHintKind.entries.size, hints.size)
        for (hint in hints) {
            val copy = PaymentAdvisoryCopy.forHint(hint)
            assertTrue("${hint.kind} hinglish blank", copy.hinglish.isNotBlank())
            assertTrue("${hint.kind} english blank", copy.english.isNotBlank())
            assertTrue("${hint.kind} hindi blank", copy.hindi.isNotBlank())
        }
    }

    @Test
    fun everyKindGetsItsOwnWording() {
        val hinglish = fixtureHints().map { PaymentAdvisoryCopy.forHint(it).hinglish }
        assertEquals(
            "two kinds share the same Hinglish copy",
            hinglish.size,
            hinglish.toSet().size
        )
    }

    @Test
    fun forHintLeavesNoTemplateMarkerInAnyLanguage() {
        for (hint in fixtureHints() + bareHints()) {
            val copy = PaymentAdvisoryCopy.forHint(hint)
            for (text in listOf(copy.hinglish, copy.english, copy.hindi)) {
                assertFalse("${hint.kind} left a template marker: $text", text.contains("{"))
                assertFalse("${hint.kind} left a template marker: $text", text.contains("}"))
            }
        }
    }

    @Test
    fun forHintFallsBackWithoutAmountOrPayee() {
        for (hint in bareHints()) {
            val copy = PaymentAdvisoryCopy.forHint(hint)
            for (text in listOf(copy.hinglish, copy.english, copy.hindi)) {
                assertFalse("${hint.kind} invented an amount: $text", text.contains("\u20B9"))
            }
        }
    }

    @Test
    fun amountHintsPrintTheFormattedAmount() {
        val large = PaymentAdvisoryCopy.forHint(
            hint(PaymentHintKind.AMOUNT_LARGE, amountMinor = 500_000L)
        )
        assertTrue(large.hinglish.contains("\u20B95,000"))
        assertTrue(large.english.contains("\u20B95,000"))
        assertTrue(large.hindi.contains("\u20B95,000"))

        val round = PaymentAdvisoryCopy.forHint(
            hint(PaymentHintKind.AMOUNT_ROUND_LARGE, amountMinor = 2_000_00L * 10L)
        )
        assertTrue(round.hinglish.contains("\u20B920,000"))
    }

    @Test
    fun maskedPayeeAppearsInNotRegisteredCopy() {
        val copy = PaymentAdvisoryCopy.forHint(
            hint(PaymentHintKind.PAYEE_NOT_REGISTERED, maskedPayee = maskedPayeeFixture)
        )
        assertTrue(copy.hinglish.contains(maskedPayeeFixture))
        assertTrue(copy.english.contains(maskedPayeeFixture))
        assertTrue(copy.hindi.contains(maskedPayeeFixture))
    }

    @Test
    fun rawUpiIdNeverAppearsInAnyHintCopy() {
        val raw = "shop@okaxis"
        for (hint in fixtureHints()) {
            val copy = PaymentAdvisoryCopy.forHint(hint)
            for (text in listOf(copy.hinglish, copy.english, copy.hindi)) {
                assertFalse("${hint.kind} leaked a raw UPI id", text.contains(raw))
            }
        }
    }

    // ------------------------------------------------------------------ claims and safety wording

    @Test
    fun noCopyContainsForbiddenClaimTokens() {
        for (text in allStrings()) {
            val lowered = text.lowercase()
            for (token in forbiddenClaimTokens) {
                assertFalse("forbidden claim '$token' in: $text", lowered.contains(token))
            }
        }
    }

    @Test
    fun everyCopyPassesAbsoluteSafetyGuard() {
        for (text in allStrings()) {
            assertTrue(
                "absolute-safety claim in: $text",
                AbsoluteSafetyGuard.isClean(text)
            )
        }
    }

    // ------------------------------------------------------------------ rupee formatting

    @Test
    fun formatRupeesZeroIsZeroRupees() {
        assertEquals("\u20B90", PaymentAdvisoryCopy.formatRupees(0L))
    }

    @Test
    fun formatRupeesNegativeIsZeroRupees() {
        assertEquals("\u20B90", PaymentAdvisoryCopy.formatRupees(-1L))
        assertEquals("\u20B90", PaymentAdvisoryCopy.formatRupees(-120_000L))
        assertEquals("\u20B90", PaymentAdvisoryCopy.formatRupees(Long.MIN_VALUE))
    }

    @Test
    fun formatRupeesBelowOneRupeeKeepsPaddedPaise() {
        assertEquals("\u20B90.99", PaymentAdvisoryCopy.formatRupees(99L))
        assertEquals("\u20B90.05", PaymentAdvisoryCopy.formatRupees(5L))
    }

    @Test
    fun formatRupeesWholeAmountsHaveNoPaise() {
        assertEquals("\u20B910", PaymentAdvisoryCopy.formatRupees(1_000L))
        assertEquals("\u20B91,234", PaymentAdvisoryCopy.formatRupees(123_400L))
    }

    @Test
    fun formatRupeesUsesThousandsGrouping() {
        assertEquals("\u20B91,200", PaymentAdvisoryCopy.formatRupees(120_000L))
    }

    @Test
    fun formatRupeesGroupsLakhsTheIndianWay() {
        assertEquals("\u20B91,20,000", PaymentAdvisoryCopy.formatRupees(12_000_000L))
    }

    @Test
    fun formatRupeesGroupsCroresWithPaise() {
        assertEquals("\u20B91,23,45,678.90", PaymentAdvisoryCopy.formatRupees(1_234_567_890L))
    }

    @Test
    fun formatRupeesNeverUsesWesternThousandGrouping() {
        val lakh = PaymentAdvisoryCopy.formatRupees(10_000_000L)
        assertEquals("\u20B91,00,000", lakh)
        assertFalse("western grouping leaked", lakh.contains("\u20B9100,000"))
    }

    @Test
    fun formatRupeesPadsHalfRupeePaise() {
        assertEquals("\u20B91,200.50", PaymentAdvisoryCopy.formatRupees(120_050L))
    }

    // ------------------------------------------------------------------ verdict copy

    @Test
    fun verdictHeadlinesDifferForAllThreeVerdicts() {
        val headlines = allVerdicts().map { PaymentAdvisoryCopy.verdictHeadline(it) }
        assertEquals(3, headlines.map { it.hinglish }.toSet().size)
        assertEquals(3, headlines.map { it.english }.toSet().size)
        assertEquals(3, headlines.map { it.hindi }.toSet().size)
    }

    @Test
    fun verdictAdviceDiffersForAllThreeVerdicts() {
        val advice = allVerdicts().map { PaymentAdvisoryCopy.verdictAdvice(it) }
        assertEquals(3, advice.map { it.hinglish }.toSet().size)
        assertEquals(3, advice.map { it.english }.toSet().size)
        assertEquals(3, advice.map { it.hindi }.toSet().size)
    }

    @Test
    fun stopHeadlineTellsUserNotToPayYet() {
        val stop = PaymentAdvisoryCopy.verdictHeadline(PaymentAdvisoryVerdict.STOP)
        assertTrue(stop.hinglish.contains("na karein"))
        assertTrue(stop.english.contains("Do not pay yet"))
        assertTrue(stop.hindi.contains("न करें"))
    }

    @Test
    fun clearHeadlineMakesNoSafetyClaim() {
        val clear = PaymentAdvisoryCopy.verdictHeadline(PaymentAdvisoryVerdict.CLEAR).pick(UiLanguage.ENGLISH)
        val lowered = clear.lowercase()
        assertFalse(lowered.contains("safe"))
        assertFalse(lowered.contains("verified"))
        assertTrue("CLEAR must say no warning hint was found", lowered.contains("no payment-specific warning hint"))
    }

    @Test
    fun clearAdviceTellsUserToCheckAmountThemselves() {
        val advice = PaymentAdvisoryCopy.verdictAdvice(PaymentAdvisoryVerdict.CLEAR)
        assertTrue(advice.hinglish.lowercase().contains("amount"))
        assertTrue(advice.hinglish.lowercase().contains("khud check"))
        assertTrue(advice.english.lowercase().contains("amount and payee"))
        assertTrue(advice.english.lowercase().contains("yourself"))
    }

    @Test
    fun stopAdviceMentionsTheUsersOwnUpiApp() {
        val advice = PaymentAdvisoryCopy.verdictAdvice(PaymentAdvisoryVerdict.STOP)
        assertTrue(advice.hinglish.contains("UPI app"))
        assertTrue(advice.english.contains("your own UPI app"))
        assertTrue(advice.hindi.contains("UPI ऐप"))
    }

    @Test
    fun checkAdviceAsksToConfirmPayeeBeforePaying() {
        val advice = PaymentAdvisoryCopy.verdictAdvice(PaymentAdvisoryVerdict.CHECK)
        assertTrue(advice.hinglish.contains("Payee"))
        assertTrue(advice.hinglish.contains("confirm"))
        assertTrue(advice.english.contains("before you pay"))
    }

    // ------------------------------------------------------------------ language invariants

    @Test
    fun allHindiCopyIsSubstantivelyDevanagariAndOtherVoicesAreReadable() {
        for (copy in allCopy() + bareHints().map { PaymentAdvisoryCopy.forHint(it) }) {
            assertTrue("Hindi is not substantially Devanagari: ${copy.hindi}", devanagariRatio(copy.hindi) > 0.5)
            assertTrue("Hinglish lacks Roman-script words: ${copy.hinglish}", copy.hinglish.any { it in 'a'..'z' || it in 'A'..'Z' })
            assertTrue("English lacks readable English words: ${copy.english}", copy.english.any { it in 'a'..'z' || it in 'A'..'Z' })
        }
    }

    @Test
    fun everyCopyHasADistinctNonBlankHindiValue() {
        val copies = allCopy()
        for (copy in copies) {
            assertTrue("blank Hindi for: ${copy.english}", copy.hindi.isNotBlank())
            assertNotEquals("Hindi equals English for: ${copy.english}", copy.english, copy.hindi)
            assertNotEquals("Hindi equals Hinglish for: ${copy.hinglish}", copy.hinglish, copy.hindi)
        }
        val hindi = copies.map { it.hindi }
        assertEquals("two copies share the same Hindi string", hindi.size, hindi.toSet().size)
    }

    @Test
    fun hinglishIsTheDefaultLanguage() {
        val copy = PaymentAdvisoryCopy.verdictHeadline(PaymentAdvisoryVerdict.CHECK)
        assertEquals(copy.hinglish, copy.pick(UiLanguage.Default))
        assertEquals(copy.hinglish, copy.pick())
    }

    // ------------------------------------------------------------------ severity glyphs

    @Test
    fun severityGlyphIsOneGlyphPerSeverity() {
        val glyphs = PaymentHintSeverity.entries.map { severity ->
            PaymentAdvisoryCopy.severityGlyph(hint(PaymentHintKind.STATUS_PENDING, severity = severity))
        }
        glyphs.forEach { glyph ->
            assertTrue("glyph must not be blank", glyph.isNotBlank())
            assertEquals("glyph must be a single character: $glyph", 1, glyph.length)
        }
        assertEquals("severities must not share a glyph", 3, glyphs.toSet().size)
    }

    @Test
    fun severityGlyphDependsOnlyOnSeverity() {
        val a = PaymentAdvisoryCopy.severityGlyph(
            hint(PaymentHintKind.PAYEE_MISSING, severity = PaymentHintSeverity.WARNING)
        )
        val b = PaymentAdvisoryCopy.severityGlyph(
            hint(PaymentHintKind.SCREENSHOT_ONLY, severity = PaymentHintSeverity.WARNING)
        )
        assertEquals(a, b)
        assertNotEquals(
            a,
            PaymentAdvisoryCopy.severityGlyph(
                hint(PaymentHintKind.PAYEE_MISSING, severity = PaymentHintSeverity.INFO)
            )
        )
    }
}
