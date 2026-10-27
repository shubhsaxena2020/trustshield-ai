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
    fun resultScreenPlacesPaymentCardBeforeConfidenceAndEvidence() {
        val source = File("src/main/java/ai/trustshield/android/ui/result/ResultScreen.kt").readText()
        val card = source.indexOf("PaymentAdvisoryCard(advisory = advisory, language = language)")
        val confidence = source.indexOf("ConfidenceBadgeView(badge = state.confidence, language = language)")
        val evidence = source.indexOf("EvidenceSection(state = state, language = language)")
        assertTrue("payment advisory should render before confidence", card >= 0 && confidence > card)
        assertTrue("evidence should follow confidence", evidence > confidence)
    }
}
