package ai.trustshield.android.ui.result

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Static Result-to-card path contract checks. The existing Compose UI-path coverage exercises the
 * card itself on device; these JVM checks pin its conditional mounting in ResultScreen without
 * requiring an emulator or Compose test runtime.
 */
class PaymentAdvisoryUiPathTest {
    @Test
    fun resultScreenMountsAdvisoryOnlyWhenPresentAndPassesCurrentLanguage() {
        val source = File("src/main/java/ai/trustshield/android/ui/result/ResultScreen.kt").readText()
        assertTrue(source.contains("state.paymentAdvisory?.let { advisory ->"))
        assertTrue(source.contains("PaymentAdvisoryCard(advisory = advisory, language = language)"))
    }

    @Test
    fun advisoryCardIsAVisibleMergedAccessibleCardWithOrderedContent() {
        val source = File("src/main/java/ai/trustshield/android/ui/result/PaymentAdvisoryCard.kt").readText()
        assertTrue(source.contains("Surface("))
        assertTrue(source.contains(".fillMaxWidth()"))
        assertTrue(source.contains(".semantics(mergeDescendants = true)"))
        assertTrue(source.contains("contentDescription = spokenDescription"))
        assertTrue(source.contains("append(sectionTitle)"))
        assertTrue(source.contains("append(headline)"))
        assertTrue(source.contains("append(advice)"))
        assertTrue(source.contains("hintRows.forEach"))
        assertTrue(source.contains("AdvisoryConfirmationLine(text = confirmation)"))
        assertTrue(source.indexOf("hintRows.forEach { row ->") < source.indexOf("AdvisoryConfirmationLine(text = confirmation)"))
        assertTrue(source.contains("modifier = Modifier.semantics { heading() }"))
    }

    @Test
    fun mandatoryConfirmationExistsForEveryVerdictAndLanguageAndIsLastInCard() {
        val copy = File("src/main/java/ai/trustshield/android/ui/result/PaymentAdvisoryCopy.kt").readText()
        val card = File("src/main/java/ai/trustshield/android/ui/result/PaymentAdvisoryCard.kt").readText()
        val expected = listOf(
            "Pakka confirmation sirf aapke bank SMS ya UPI app ki history mein milega.",
            "Final confirmation only comes from your bank SMS or your UPI app's history.",
            "अंतिम पुष्टि केवल आपके बैंक SMS या UPI ऐप के लेन-देन इतिहास से ही मिलती है।"
        )
        expected.forEach { assertTrue("missing localized confirmation: $it", copy.contains(it)) }
        assertTrue(copy.contains("val CONFIRMATION: ToneCopy"))
        assertTrue(card.contains("val confirmation = PaymentAdvisoryCopy.CONFIRMATION.pick(language)"))
        assertTrue(card.contains("append(confirmation)"))
        assertTrue(card.contains("// MUST stay the last element drawn: the mandatory confirmation line."))
        val drawnRows = card.indexOf("hintRows.forEach { row ->", card.indexOf("Column("))
        val confirmation = card.indexOf("AdvisoryConfirmationLine(text = confirmation)")
        assertTrue("confirmation must follow all rendered hint rows", drawnRows >= 0 && confirmation > drawnRows)
        assertTrue(card.contains("append(advice)"))
    }

    @Test
    fun eachVerdictHasLocalizedHeadlineAndAdviceCopy() {
        val copy = File("src/main/java/ai/trustshield/android/ui/result/PaymentAdvisoryCopy.kt").readText()
        listOf("PaymentAdvisoryVerdict.CLEAR", "PaymentAdvisoryVerdict.CHECK", "PaymentAdvisoryVerdict.STOP").forEach {
            assertTrue("headline missing $it", copy.substringAfter("fun verdictHeadline").substringBefore("fun verdictAdvice").contains(it))
            assertTrue("advice missing $it", copy.substringAfter("fun verdictAdvice").substringBefore("fun forHint").contains(it))
        }
        listOf("hinglish =", "english =", "hindi =").forEach { language ->
            assertTrue("headline copy missing $language", copy.substringAfter("fun verdictHeadline").substringBefore("fun verdictAdvice").contains(language))
            assertTrue("advice copy missing $language", copy.substringAfter("fun verdictAdvice").substringBefore("fun forHint").contains(language))
        }
    }

    @Test
    fun resultScreenPlacesPaymentCardBeforeConfidenceAndEvidence() {
        val source = File("src/main/java/ai/trustshield/android/ui/result/ResultScreen.kt").readText()
        val card = source.indexOf("PaymentAdvisoryCard(advisory = advisory, language = language)")
        val confidence = source.indexOf("ConfidenceBadgeView(badge = state.confidence, language = language)")
        val evidence = source.indexOf("EvidenceSection(state = state, language = language)")
        assertTrue("payment advisory should render before confidence", card >= 0 && confidence > card)
        assertTrue("evidence should follow confidence", evidence > confidence)
    }
}
