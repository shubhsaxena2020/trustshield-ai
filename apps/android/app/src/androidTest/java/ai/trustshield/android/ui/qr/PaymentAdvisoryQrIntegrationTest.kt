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

/** Isolated contract checks for adapting QR Guard payees to the advisory UI.
 *
 * These tests do not render [QrGuardScreen] or exercise a production scan-to-advisory path: the
 * current QR Guard screen does not invoke this bridge. Keep that distinction explicit.
 */
@RunWith(AndroidJUnit4::class)
class PaymentAdvisoryQrIntegrationTest {
    @get:Rule
    val compose = createComposeRule()

    private val receipt = "Successful payment\nPaid to: Corner Shop\nUPI ID: corner@okaxis\nAmount: ₹250.00"

    private fun evaluate(payees: List<RegisteredPayee>) = PaymentAdvisoryEngine().evaluate(
        QrPayeePaymentBridge.toAdvisoryInput(
            sourceText = receipt,
            fields = PaymentFieldsParser.parse(receipt),
            payees = payees,
            nowEpochMs = 0L
        )
    )

    private fun show(advisory: ai.trustshield.android.core.payment.PaymentAdvisory) {
        compose.setContent {
            TrustShieldTheme {
                PaymentAdvisoryCard(advisory = advisory, language = UiLanguage.HINGLISH)
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun simulatedQrGuardPayeeProducesMatchWhenManuallyAdaptedToAdvisory() {
        val guard = QrGuardSession(clock = { 0L })
        guard.simulate(QrSimulationFlow.Match)
        val payees = guard.snapshot().state.payees
        assertTrue("QR Guard fixture must register a payee", payees.isNotEmpty())

        val advisory = evaluate(payees)
        assertEquals(PaymentAdvisoryVerdict.CHECK, advisory.verdict)
        assertTrue(advisory.hints.any { it.kind == PaymentHintKind.PAYEE_MATCHES_REGISTERED })
        assertFalse(advisory.hints.any { it.kind == PaymentHintKind.PAYEE_NOT_REGISTERED })
        assertTrue("A matching payee is not proof of settlement", advisory.confirmationRequired)

        show(advisory)
        compose.onNodeWithText(PaymentAdvisoryCopy.verdictHeadline(advisory.verdict).hinglish).assertExists()
        compose.onNodeWithText(PaymentAdvisoryCopy.CONFIRMATION.hinglish).assertExists()
    }

    @Test
    fun differentPayeeWithSamePrintedNameProducesMismatchWhenManuallyAdapted() {
        val advisory = evaluate(listOf(RegisteredPayee(handle = "impostor@okaxis", name = "Corner Shop")))
        assertEquals(PaymentAdvisoryVerdict.STOP, advisory.verdict)
        assertTrue(advisory.hints.any { it.kind == PaymentHintKind.PAYEE_NOT_REGISTERED })
        assertEquals("cor***@okaxis", advisory.hints.first { it.kind == PaymentHintKind.PAYEE_NOT_REGISTERED }.maskedPayee)

        show(advisory)
        compose.onNodeWithText(PaymentAdvisoryCopy.verdictHeadline(PaymentAdvisoryVerdict.STOP).hinglish).assertExists()
        compose.onNodeWithText("corner@okaxis", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText(PaymentAdvisoryCopy.CONFIRMATION.hinglish).assertExists()
    }

    @Test
    fun noRegisteredPayeesIsNotReportedAsSpecificMismatch() {
        val advisory = evaluate(emptyList())
        assertTrue(advisory.hints.any { it.kind == PaymentHintKind.NO_REGISTERED_PAYEES })
        assertFalse(advisory.hints.any { it.kind == PaymentHintKind.PAYEE_NOT_REGISTERED })
        assertTrue(advisory.confirmationRequired)
    }
}
