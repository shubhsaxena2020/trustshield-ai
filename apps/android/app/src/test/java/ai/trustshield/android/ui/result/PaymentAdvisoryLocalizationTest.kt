package ai.trustshield.android.ui.result

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pinned mandatory payment disclaimer wording and regression checks for complete, ordered rendering. */
class PaymentAdvisoryLocalizationTest {
    @Test
    fun mandatoryDisclaimerMatchesExactTextInAllThreeLanguages() {
        val copy = PaymentAdvisoryCopy.CONFIRMATION
        assertEquals("Pakka confirmation sirf aapke bank SMS ya UPI app ki history mein milega.", copy.hinglish)
        assertEquals("Final confirmation only comes from your bank SMS or your UPI app's history.", copy.english)
        assertEquals("अंतिम पुष्टि केवल आपके बैंक SMS या UPI ऐप के लेन-देन इतिहास से ही मिलती है।", copy.hindi)
        assertTrue(copy.hindi.any { it in 'ऀ'..'ॿ' })
        assertEquals(copy.hinglish, copy.pick(UiLanguage.HINGLISH))
        assertEquals(copy.english, copy.pick(UiLanguage.ENGLISH))
        assertEquals(copy.hindi, copy.pick(UiLanguage.HINDI))
    }

    @Test
    fun confirmationIsUnconditionallyAfterAllOptionalHintsInDrawAndAnnouncement() {
        val source = java.io.File("src/main/java/ai/trustshield/android/ui/result/PaymentAdvisoryCard.kt").readText()
        val drawHints = source.indexOf("hintRows.forEach { row ->", source.indexOf("Column("))
        val drawConfirmation = source.indexOf("AdvisoryConfirmationLine(text = confirmation)")
        assertTrue("hints are drawn", drawHints >= 0)
        assertTrue("mandatory confirmation follows hint rows", drawConfirmation > drawHints)
        val spokenHints = source.indexOf("hintRows.forEach")
        val spokenConfirmation = source.indexOf("append(confirmation)")
        assertTrue("spoken confirmation follows optional hints", spokenHints >= 0 && spokenConfirmation > spokenHints)
        assertTrue(source.contains("PaymentAdvisoryCopy.CONFIRMATION.pick(language)"))
    }

    @Test
    fun disclaimerAndCardTextAreNotConfiguredForTruncation() {
        val card = java.io.File("src/main/java/ai/trustshield/android/ui/result/PaymentAdvisoryCard.kt").readText()
        assertFalse(Regex("\b(maxLines|softWrap\s*=\s*false)\b").containsMatchIn(card))
        assertFalse(Regex("\bfontSize\s*=").containsMatchIn(card))
        assertTrue(card.contains("MaterialTheme.typography.bodyMedium"))
    }
}
