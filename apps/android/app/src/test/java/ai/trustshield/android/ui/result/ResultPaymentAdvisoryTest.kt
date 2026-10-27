package ai.trustshield.android.ui.result

import java.io.File
import ai.trustshield.android.core.payment.PaymentHintKind
import ai.trustshield.android.ui.qr.RegisteredPayee
import ai.trustshield.android.ui.theme.RiskTone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val PAYMENT_RECEIPT = "Payment Successful. Rs 1,500 paid to shop@okaxis. UTR 412345678901."

private fun paymentResultInput(
    source: String = PAYMENT_RECEIPT,
    registeredPayees: List<RegisteredPayee> = emptyList(),
    registeredPayeeCount: Int = 0,
    payeeMatchesRegistered: Boolean = false
) = ResultInput(
    sourceText = source,
    coverage = InputCoverage.READABLE_SUPPORTED,
    riskTone = RiskTone.AMBER,
    confidence = ConfidenceLevel.HIGH,
    registeredPayeeCount = registeredPayeeCount,
    registeredPayees = registeredPayees,
    payeeMatchesRegistered = payeeMatchesRegistered
)

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

    @Test
    fun presenterAttachesAdvisoryForPaymentReceiptAndOmitsItForOrdinaryText() {
        val payment = ResultPresenter.build(paymentResultInput())
        assertNotNull("payment receipt should be presented with its advisory", payment.paymentAdvisory)
        assertTrue(payment.paymentAdvisory!!.isPaymentScreenshot)

        val ordinary = ResultPresenter.build(
            paymentResultInput(source = "Please call me tomorrow about the meeting.")
        )
        assertNull("ordinary text must not receive a payment advisory", ordinary.paymentAdvisory)
    }

    @Test
    fun presenterDistinguishesRegisteredPayeeMatchFromMismatch() {
        val match = ResultPresenter.build(
            paymentResultInput(registeredPayeeCount = 1, payeeMatchesRegistered = true)
        ).paymentAdvisory!!
        val matchHint = match.hints.single { it.kind == PaymentHintKind.PAYEE_MATCHES_REGISTERED }
        assertEquals("sho***@okaxis", matchHint.maskedPayee)
        assertTrue(match.hints.none { it.kind == PaymentHintKind.PAYEE_NOT_REGISTERED })

        val mismatch = ResultPresenter.build(
            paymentResultInput(registeredPayeeCount = 1, payeeMatchesRegistered = false)
        ).paymentAdvisory!!
        val mismatchHint = mismatch.hints.single { it.kind == PaymentHintKind.PAYEE_NOT_REGISTERED }
        assertEquals("sho***@okaxis", mismatchHint.maskedPayee)
        assertTrue(mismatch.hints.none { it.kind == PaymentHintKind.PAYEE_MATCHES_REGISTERED })
    }

    @Test
    fun presenterDerivesPayeeMatchAndMismatchFromRegisteredPayeeList() {
        val registered = listOf(RegisteredPayee(handle = "shop@okaxis", name = "Shop"))
        val matchState = ResultPresenter.build(
            paymentResultInput(registeredPayees = registered)
        )
        val match = matchState.paymentAdvisory!!.hints.single {
            it.kind == PaymentHintKind.PAYEE_MATCHES_REGISTERED
        }
        assertTrue(matchState.paymentAdvisory!!.hints.none {
            it.kind == PaymentHintKind.PAYEE_NOT_REGISTERED
        })

        val mismatchState = ResultPresenter.build(
            paymentResultInput(
                source = "Payment Successful. Rs 1,500 paid to other@okaxis. UTR 412345678901.",
                registeredPayees = registered
            )
        )
        val mismatch = mismatchState.paymentAdvisory!!.hints.single {
            it.kind == PaymentHintKind.PAYEE_NOT_REGISTERED
        }
        assertEquals("oth***@okaxis", mismatch.maskedPayee)
        assertTrue(mismatchState.paymentAdvisory!!.hints.none {
            it.kind == PaymentHintKind.PAYEE_MATCHES_REGISTERED
        })
    }

    @Test
    fun resultFixtureProvidesRegisteredMatchAndMismatchInputs() {
        val registeredCount = 1
        val matchInput = paymentResultInput(registeredPayeeCount = registeredCount, payeeMatchesRegistered = true)
        val mismatchInput = paymentResultInput(registeredPayeeCount = registeredCount, payeeMatchesRegistered = false)

        assertEquals(registeredCount, matchInput.registeredPayeeCount)
        assertTrue(matchInput.payeeMatchesRegistered)
        assertEquals(registeredCount, mismatchInput.registeredPayeeCount)
        assertTrue(!mismatchInput.payeeMatchesRegistered)
    }

    @Test
    fun confirmationCopyRemainsLastAfterAllAdvisoryHintRows() {
        val source = File("src/main/java/ai/trustshield/android/ui/result/PaymentAdvisoryCard.kt").readText()
        val hintRows = source.indexOf("hintRows.forEach { row ->")
        val confirmation = source.indexOf("AdvisoryConfirmationLine(text = confirmation)", hintRows)
        val laterHintRows = source.indexOf("hintRows.forEach { row ->", hintRows + 1)

        assertTrue("hint rows are rendered", hintRows >= 0)
        assertTrue("confirmation follows rendered hints", confirmation > hintRows)
        assertTrue("no further hint rows follow confirmation", laterHintRows < 0 || confirmation < laterHintRows)
    }

    @Test
    fun presenterCarriesRegisteredPayeeMismatchIntoAdvisory() {
        val state = ResultPresenter.build(
            paymentResultInput(registeredPayeeCount = 1, payeeMatchesRegistered = false)
        )
        val mismatch = state.paymentAdvisory!!.hints.single { it.kind == PaymentHintKind.PAYEE_NOT_REGISTERED }
        assertEquals("sho***@okaxis", mismatch.maskedPayee)
    }

    @Test
    fun confirmationCopyIsTheFinalClauseAfterVerdictAndHints() {
        val confirmation = PaymentAdvisoryCopy.CONFIRMATION.pick(UiLanguage.HINGLISH)
        val source = File("src/main/java/ai/trustshield/android/ui/result/PaymentAdvisoryCard.kt").readText()
        val verdict = source.indexOf("AdvisoryVerdictBlock(verdict = advisory.verdict")
        val hints = source.indexOf("hintRows.forEach { row ->", verdict)
        val confirmationCall = source.indexOf("AdvisoryConfirmationLine(text = confirmation)", hints)
        val confirmationDescription = source.indexOf("append(confirmation)", source.indexOf("val spokenDescription"))
        assertTrue("verdict is rendered before hints", verdict >= 0 && hints > verdict)
        assertTrue("confirmation line is rendered after hints", confirmationCall > hints)
        assertTrue("screen-reader confirmation clause follows all hint clauses", confirmationDescription > hints)
        assertEquals("Pakka confirmation sirf aapke bank SMS ya UPI app ki history mein milega.", confirmation)
    }
}
