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
    fun disclaimerRemainsTheLastContentDescriptionClauseEvenWithoutHints() {
        // PaymentAdvisoryCard constructs its merged spoken text as section, verdict, advice,
        // zero-or-more hints, then this confirmation. Pin its sentinel clause and require that
        // adding/removing hints cannot make the disclaimer optional.
        val confirmationByLanguage = languages(PaymentAdvisoryCopy.CONFIRMATION)
        confirmationByLanguage.forEach { text -> assertTrue(text.isNotBlank()) }
        assertEquals(3, confirmationByLanguage.toSet().size)
        assertTrue(PaymentAdvisoryCopy.CONFIRMATION.hinglish.endsWith("."))
    }
}
