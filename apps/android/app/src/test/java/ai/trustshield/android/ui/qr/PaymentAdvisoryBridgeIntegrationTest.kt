package ai.trustshield.android.ui.qr

import ai.trustshield.android.core.payment.PaymentAdvisoryEngine
import ai.trustshield.android.core.payment.PaymentAdvisoryVerdict
import ai.trustshield.android.core.payment.PaymentFieldsParser
import ai.trustshield.android.core.payment.PaymentHintKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Exercises the real QR payee -> advisory-input seam without Android or storage dependencies. */
class PaymentAdvisoryBridgeIntegrationTest {

    private val engine = PaymentAdvisoryEngine()

    @Test
    fun registeredQrPayeeFlowsThroughParsedReceiptAsMatch() {
        val receipt = "Successful payment\\nPaid to: Corner Shop\\nUPI ID: corner@okaxis\\nAmount: ₹250.00"
        val payees = listOf(RegisteredPayee(handle = " Corner@OkAxis ", name = "Shop"))
        val fields = PaymentFieldsParser.parse(receipt)

        val input = QrPayeePaymentBridge.toAdvisoryInput(
            sourceText = receipt,
            fields = fields,
            payees = payees,
            nowEpochMs = 1_800_000_000_000L
        )
        val advisory = engine.evaluate(input)

        assertEquals(1, input.registeredPayeeCount)
        assertTrue(input.payeeMatchesRegistered)
        assertTrue(advisory.hints.any { it.kind == PaymentHintKind.PAYEE_MATCHES_REGISTERED })
        assertFalse(advisory.hints.any { it.kind == PaymentHintKind.PAYEE_NOT_REGISTERED })
        assertTrue(advisory.confirmationRequired)
    }

    @Test
    fun differentIdWithSamePrintedNameIsNotRegisteredAndUrgencyReachesStop() {
        val receipt = "Successful payment\\nPaid to: Corner Shop\\nUPI ID: impostor@okaxis\\nAmount: ₹5000\\nPlease pay now only"
        val payees = listOf(RegisteredPayee(handle = "corner@okaxis", name = "Corner Shop"))
        val fields = PaymentFieldsParser.parse(receipt)

        val input = QrPayeePaymentBridge.toAdvisoryInput(
            sourceText = receipt,
            fields = fields,
            payees = payees,
            nowEpochMs = 1_800_000_000_000L
        )
        val advisory = engine.evaluate(input)

        assertEquals(1, input.registeredPayeeCount)
        assertFalse(input.payeeMatchesRegistered)
        assertTrue(advisory.hints.any { it.kind == PaymentHintKind.PAYEE_NOT_REGISTERED })
        assertTrue(advisory.hints.any { it.kind == PaymentHintKind.URGENCY_PRESSURE })
        assertEquals(PaymentAdvisoryVerdict.STOP, advisory.verdict)
    }

    @Test
    fun emptyRegistryAndMaskedCandidateAreNotMisreportedAsMismatch() {
        val fields = PaymentFieldsParser.parse("Paid to: Shop\\nUPI ID: corner@okaxis\\nAmount: ₹25")
        val noPayees = QrPayeePaymentBridge.toAdvisoryInput("", fields, emptyList(), 0L)
        val maskedCandidate = QrPayeePaymentBridge.toAdvisoryInput(
            "", fields.copy(payeeUpi = "cor***@okaxis"),
            listOf(RegisteredPayee(handle = "corner@okaxis")), 0L
        )

        assertEquals(0, noPayees.registeredPayeeCount)
        assertFalse(noPayees.payeeMatchesRegistered)
        assertTrue(engine.evaluate(noPayees).hints.any { it.kind == PaymentHintKind.NO_REGISTERED_PAYEES })
        assertFalse(maskedCandidate.payeeMatchesRegistered)
        assertTrue(engine.evaluate(maskedCandidate).hints.any { it.kind == PaymentHintKind.PAYEE_MISSING })
        assertFalse(engine.evaluate(maskedCandidate).hints.any { it.kind == PaymentHintKind.PAYEE_NOT_REGISTERED })
    }
}
