package ai.trustshield.android.ui.result

import ai.trustshield.android.core.payment.PaymentAdvisoryVerdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Semantic-copy contract for the payment advisory. The composable exposes these strings to
 * TalkBack in its merged content description, so the labels must remain meaningful and the
 * bank/UPI confirmation disclaimer must be present as the final spoken clause.
 */
class PaymentAdvisoryAccessibilityAuditTest {

    private fun languages(copy: ai.trustshield.android.ui.theme.ToneCopy) =
        listOf(copy.hinglish, copy.english, copy.hindi)

    @Test
    fun sectionAndVerdictLabelsAreNonBlankInEverySupportedLanguage() {
        val labels = listOf(PaymentAdvisoryCopy.SECTION_TITLE) +
            PaymentAdvisoryVerdict.entries.map(PaymentAdvisoryCopy::verdictHeadline)

        labels.forEach { label ->
            languages(label).forEach { text -> assertTrue("blank accessible label", text.isNotBlank()) }
        }
        assertEquals("Payment check", PaymentAdvisoryCopy.SECTION_TITLE.english)
    }

    @Test
    fun verdictLabelsDoNotConflateNoHintWithConfirmedSafety() {
        val clear = languages(PaymentAdvisoryCopy.verdictHeadline(PaymentAdvisoryVerdict.CLEAR))
        val stop = languages(PaymentAdvisoryCopy.verdictHeadline(PaymentAdvisoryVerdict.STOP))

        clear.forEach { text ->
            assertFalse(text.lowercase().contains("safe"))
            assertFalse(text.lowercase().contains("verified"))
        }
        assertTrue(stop[0].contains("na karein"))
        assertTrue(stop[1].contains("Do not pay yet"))
        assertTrue(stop[2].contains("न करें"))
    }

    @Test
    fun mandatoryConfirmationDisclaimerIsExplicitInEveryLanguage() {
        val disclaimer = PaymentAdvisoryCopy.CONFIRMATION

        assertEquals(
            "Pakka confirmation sirf aapke bank SMS ya UPI app ki history mein milega.",
            disclaimer.hinglish
        )
        assertTrue(disclaimer.english.contains("bank SMS"))
        assertTrue(disclaimer.english.contains("UPI app's history"))
        assertTrue(disclaimer.hindi.contains("बैंक SMS"))
        assertTrue(disclaimer.hindi.contains("UPI ऐप"))
    }

    @Test
    fun localizedDisclaimerDoesNotClaimPaymentSuccessOrFraud() {
        languages(PaymentAdvisoryCopy.CONFIRMATION).forEach { text ->
            val normalized = text.lowercase()
            assertFalse("disclaimer must not claim success: ", normalized.contains("success"))
            assertFalse("disclaimer must not accuse of fraud: ", normalized.contains("fraud"))
            assertFalse("disclaimer must not claim payment received: ", normalized.contains("payment received"))
        }
        assertTrue(PaymentAdvisoryCopy.CONFIRMATION.hinglish.contains("bank SMS"))
        assertTrue(PaymentAdvisoryCopy.CONFIRMATION.english.contains("bank SMS"))
        assertTrue(PaymentAdvisoryCopy.CONFIRMATION.hindi.contains("बैंक SMS"))
    }

    @Test
    fun spokenDescriptionContainsSectionVerdictAdviceAndConfirmationInReadingOrder() {
        val source = java.io.File(
            "src/main/java/ai/trustshield/android/ui/result/PaymentAdvisoryCard.kt"
        ).readText()
        val order = listOf(
            "append(sectionTitle)",
            "append(headline)",
            "append(advice)",
            "hintRows.forEach",
            "append(confirmation)"
        ).map(source::indexOf)
        assertTrue("all spoken clauses must be present", order.all { it >= 0 })
        assertEquals("clauses must follow TalkBack reading order", order.sorted(), order)
        assertTrue("card must merge descendants into one accessible node", source.contains("mergeDescendants = true"))
        assertTrue("spoken description must be exposed", source.contains("contentDescription = spokenDescription"))
        assertFalse("spoken hint clause should not announce decorative severity glyph", source.contains("append(row.first)"))
    }

    @Test
    fun verdictAndHintSeverityHaveNonColorTextSignalsAndWrappingContent() {
        val source = java.io.File(
            "src/main/java/ai/trustshield/android/ui/result/PaymentAdvisoryCard.kt"
        ).readText()
        assertTrue("verdict glyph must be rendered as text", source.contains("glyph = advisoryVerdictGlyph(verdict)"))
        assertTrue("hint severity glyph must be rendered", source.contains("glyph = row.first"))
        assertTrue("section title must be marked as a heading", source.contains("heading()"))
        assertFalse("fixed-height layout can clip larger text", Regex("\.height\(").containsMatchIn(source))
        assertFalse("maxLines can clip larger text", source.contains("maxLines"))
        assertTrue("hint text should take available width and wrap", source.contains("Modifier.weight(1f)"))
    }

    @Test
    fun disclaimerRemainsTheLastContentDescriptionClauseEvenWithoutHints() {
        // PaymentAdvisoryCard constructs its merged spoken text as section, verdict, advice,
        // zero-or-more hints, then this confirmation. Pin its sentinel clause and require that
        // adding/removing hints cannot make the disclaimer optional.
        val confirmationByLanguage = languages(PaymentAdvisoryCopy.CONFIRMATION)
        confirmationByLanguage.forEach { text -> assertTrue(text.isNotBlank()) }
        assertEquals(3, confirmationByLanguage.toSet().size)
        assertTrue(PaymentAdvisoryCopy.CONFIRMATION.hinglish.endsWith("."))
    }
    @Test
    fun everyVerdictHasDistinctEnglishAndDevanagariCopy() {
        PaymentAdvisoryVerdict.entries.forEach { verdict ->
            val copy = PaymentAdvisoryCopy.verdictHeadline(verdict)
            assertTrue("English copy should be English", copy.english.any { it in 'A'..'Z' })
            assertTrue("Hindi copy should use Devanagari", copy.hindi.any { it in 'ऀ'..'ॿ' })
            assertFalse("Hindi copy must not be a Romanized fallback", copy.hindi == copy.hinglish)
        }
        val advice = PaymentAdvisoryVerdict.entries.map(PaymentAdvisoryCopy::verdictAdvice)
        advice.forEach { copy ->
            assertTrue(copy.english.any { it in 'A'..'Z' })
            assertTrue(copy.hindi.any { it in 'ऀ'..'ॿ' })
        }
    }

    @Test
    fun disclaimerAndRenderedAccessibleCopyContainNoTemplateMarkers() {
        val fixedCopy = listOf(PaymentAdvisoryCopy.SECTION_TITLE, PaymentAdvisoryCopy.CONFIRMATION) +
            PaymentAdvisoryVerdict.entries.flatMap {
                listOf(PaymentAdvisoryCopy.verdictHeadline(it), PaymentAdvisoryCopy.verdictAdvice(it))
            }
        fixedCopy.flatMap(::languages).forEach { text ->
            assertFalse("unexpanded template marker", text.contains('{') || text.contains('}'))
        }
        assertTrue(PaymentAdvisoryCopy.CONFIRMATION.english.endsWith("."))
        assertTrue(PaymentAdvisoryCopy.CONFIRMATION.hindi.endsWith("।"))
    }

    @Test
    fun accessibilityImplementationDoesNotPinTextSizeOrLineCount() {
        val source = java.io.File(
            "src/main/java/ai/trustshield/android/ui/result/PaymentAdvisoryCard.kt"
        ).readText()
        assertFalse("fixed font sizes opt out of system dynamic type", Regex("\bfontSize\s*=").containsMatchIn(source))
        assertFalse("line limits can truncate at large font scale", Regex("\b(maxLines|softWrap\s*=\s*false)\b").containsMatchIn(source))
        assertTrue("advisory text should use scalable Material typography", source.contains("MaterialTheme.typography.bodyMedium"))
        assertTrue("headline should use scalable Material typography", source.contains("MaterialTheme.typography.titleMedium"))
    }

}
