package ai.trustshield.android.ui.result

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** Static production Result-path regressions; Compose UI tests are not configured in this JVM suite. */
class ResultPaymentAdvisoryTest {
    @Test
    fun resultScreenKeepsConfirmationCardAsTheFinalResultContent() {
        val source = File("src/main/java/ai/trustshield/android/ui/result/ResultScreen.kt").readText()
        val footer = source.indexOf("FooterNotes(state = state, language = language)")
        val advisoryBranch = source.indexOf("state.paymentAdvisory?.let { advisory ->")
        val card = source.indexOf("PaymentAdvisoryCard(advisory = advisory, language = language)")
        val resultColumnEnd = source.indexOf("\n    }\n}", card)

        assertTrue("footer must be rendered before the advisory", footer >= 0 && advisoryBranch > footer)
        assertTrue("the card must be inside the optional advisory branch", card > advisoryBranch)
        assertTrue("the advisory must remain the final Result content", resultColumnEnd > card)
        val resultContentAfterCard = source.substring(card + "PaymentAdvisoryCard(advisory = advisory, language = language)".length, resultColumnEnd)
            .replace("// Keep the payment confirmation disclaimer as the final Result content.", "")
            .replace("}", "")
            .replace("{", "")
            .trim()
        assertTrue("no Result content may follow the confirmation advisory", resultContentAfterCard.isEmpty())
    }

    @Test
    fun resultScreenDoesNotClaimPaymentSuccessOrFraudFromReceiptAdvisory() {
        val source = File("src/main/java/ai/trustshield/android/ui/result/ResultScreen.kt").readText()
        val advisoryBranch = source.indexOf("state.paymentAdvisory?.let { advisory ->")
        val card = source.indexOf("PaymentAdvisoryCard(advisory = advisory, language = language)")
        assertTrue("Result should render only the advisory card, not infer a verdict in the screen", card > advisoryBranch)
        val branch = source.substring(advisoryBranch, source.indexOf("\n        }", card) + "\n        }".length)
        assertTrue("advisory screen branch must not assert payment success", !branch.contains("SUCCESS"))
        assertTrue("advisory screen branch must not label the sender a fraudster", !branch.contains("fraudster"))
    }
}
