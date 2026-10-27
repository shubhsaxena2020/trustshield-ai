package ai.trustshield.android.ui.qr

import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import ai.trustshield.android.core.payment.PaymentAdvisoryEngine
import ai.trustshield.android.core.payment.PaymentAdvisoryVerdict
import ai.trustshield.android.core.payment.PaymentFieldsParser
import ai.trustshield.android.core.payment.PaymentHintKind
import ai.trustshield.android.input.qr.QrSimulationFlow
import ai.trustshield.android.ui.result.PaymentAdvisoryCard
import ai.trustshield.android.ui.result.PaymentAdvisoryCopy
import ai.trustshield.android.ui.result.UiLanguage
import ai.trustshield.android.ui.theme.TrustShieldTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** End-to-end UI-path check: QR Guard's local payee list feeds advisory rules and visible copy. */
@RunWith(AndroidJUnit4::class)
class PaymentAdvisoryQrPathTest {
    @get:Rule
    val compose = createComposeRule()

    private val source = "Paid to dukaan.demo@okaxis Rs 2500 abhi"

    private fun advisoryFor(payeeUpi: String) = run {
        val fields = PaymentFieldsParser.parse(source)
        val qrPayees = listOf(RegisteredPayee(handle = payeeUpi))
        val input = QrPayeePaymentBridge.toAdvisoryInput(
            sourceText = source,
            fields = fields,
            payees = qrPayees,
            nowEpochMs = 0L
        )
        PaymentAdvisoryEngine().evaluate(input)
    }

    private fun render(advisory: ai.trustshield.android.core.payment.PaymentAdvisory) {
        compose.setContent {
            TrustShieldTheme {
                PaymentAdvisoryCard(advisory = advisory, language = UiLanguage.HINGLISH)
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun registeredQrGuardPayeeProducesMatchHintAndAdvisoryCard() {
        // The real QR Guard session's demo drives its watcher and populates its registered-payee state.
        val qrGuard = QrGuardSession(clock = { 0L })
        qrGuard.simulate(QrSimulationFlow.Match)
        val registered = qrGuard.snapshot().state.payees
        assertFalse("fixture must exercise QR Guard's registered payee state", registered.isEmpty())

        val fields = PaymentFieldsParser.parse(source)
        val input = QrPayeePaymentBridge.toAdvisoryInput(
            sourceText = source,
            fields = fields,
            payees = registered,
            nowEpochMs = 0L
        )
        val advisory = PaymentAdvisoryEngine().evaluate(input)
        assertEquals(PaymentAdvisoryVerdict.CHECK, advisory.verdict)
        assertTrue(PaymentHintKind.PAYEE_MATCHES_REGISTERED in advisory.hints.map { it.kind })
        assertFalse(PaymentHintKind.PAYEE_NOT_REGISTERED in advisory.hints.map { it.kind })

        render(advisory)
        compose.onNodeWithText(PaymentAdvisoryCopy.verdictHeadline(advisory.verdict).hinglish).assertExists()
        compose.onNodeWithText(PaymentAdvisoryCopy.forHint(advisory.hints.first { it.kind == PaymentHintKind.PAYEE_MATCHES_REGISTERED }).hinglish).assertExists()
        compose.onNodeWithText(PaymentAdvisoryCopy.CONFIRMATION.hinglish).assertExists()
    }

    @Test
    fun unrelatedQrGuardPayeeProducesCautionaryStopAndMasksIdentifier() {
        val advisory = advisoryFor("different@okaxis")
        assertEquals(PaymentAdvisoryVerdict.STOP, advisory.verdict)
        assertTrue(PaymentHintKind.PAYEE_NOT_REGISTERED in advisory.hints.map { it.kind })
        assertTrue(PaymentHintKind.URGENCY_PRESSURE in advisory.hints.map { it.kind })
        assertEquals("mer***@okaxis", advisory.hints.first { it.kind == PaymentHintKind.PAYEE_NOT_REGISTERED }.maskedPayee)

        render(advisory)
        compose.onNodeWithText(PaymentAdvisoryCopy.verdictHeadline(PaymentAdvisoryVerdict.STOP).hinglish).assertExists()
        compose.onNodeWithText("dukaan.demo@okaxis", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText(PaymentAdvisoryCopy.CONFIRMATION.hinglish).assertExists()
    }
}
