package ai.trustshield.android.core.payment

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Independent adversarial checks for the payment advisory's public contract. */
class PaymentAdvisoryContractTest {
    private val engine = PaymentAdvisoryEngine()

    private fun evaluate(fields: PaymentFields, text: String = "", registered: Int = 0,
                         matched: Boolean = false, ocr: Boolean = true) =
        engine.evaluate(PaymentAdvisoryInput(text, fields, registered, matched, 2_000_000L, ocr))

    @Test
    fun `confirmation is mandatory even for non receipt clear and escalated stop`() {
        val clear = evaluate(PaymentFields(), ocr = false)
        val stop = evaluate(
            PaymentFields(amountMinor = 200_000L, payeeUpi = "violet-shop@okaxis", status = PaymentStatus.PENDING),
            registered = 1
        )
        assertEquals(PaymentAdvisoryVerdict.CLEAR, clear.verdict)
        assertEquals(PaymentAdvisoryVerdict.STOP, stop.verdict)
        assertTrue(clear.confirmationRequired)
        assertTrue(stop.confirmationRequired)

        // A single warning yields CHECK; disclaimer presence is unconditional across every verdict.
        val check = evaluate(
            PaymentFields(amountMinor = 100_000L, payeeUpi = "violet-shop@okaxis"),
            registered = 1
        )
        assertEquals(PaymentAdvisoryVerdict.CHECK, check.verdict)
        assertTrue(check.confirmationRequired)
    }

    @Test
    fun `all debug strings redact raw upi amount utr and source`() {
        val rawUpi = "private-vpa-731@okaxis"
        val rawUtr = "731946205817"
        val rawAmount = "731946.28"
        val rawSource = "internal-note-731946"
        val fields = PaymentFields(
            amountMinor = 73_194_628L, amountRaw = rawAmount, payeeUpi = rawUpi,
            payeeName = "private-payee-731", utr = rawUtr, status = PaymentStatus.SUCCESS,
            statusRaw = "completed-private-731", timestampRaw = "private-time-731",
            timestampEpochMs = 1_999_999L
        )
        val advisory = evaluate(fields, rawSource, registered = 1)
        val surfaces = listOf(fields.toString(), advisory.toString()) + advisory.hints.map { it.toString() }
        listOf(rawUpi, rawUtr, rawAmount, rawSource, "private-payee-731", "completed-private-731", "private-time-731")
            .forEach { secret -> surfaces.forEach { rendered -> assertFalse("leaked $secret in $rendered", rendered.contains(secret)) } }
        assertTrue(fields.toString().contains(fields.maskedUpi))
    }

    @Test
    fun `multi-signal hints sort by severity then stable kind order`() {
        val advisory = evaluate(
            PaymentFields(
                amountMinor = 500_000L, payeeUpi = "violet-shop@okaxis", payeeName = "Label-731",
                status = PaymentStatus.FAILED, timestampEpochMs = null
            ),
            text = "Pay immediately", registered = 2
        )
        val expected = listOf(
            PaymentHintKind.PAYEE_NOT_REGISTERED,
            PaymentHintKind.AMOUNT_ROUND_LARGE,
            PaymentHintKind.STATUS_FAILED,
            PaymentHintKind.URGENCY_PRESSURE,
            PaymentHintKind.AMOUNT_LARGE,
            PaymentHintKind.PAYEE_NAME_SPOOFABLE,
            PaymentHintKind.TIME_MISSING
        )
        assertEquals(expected, advisory.hints.map { it.kind })
        val ranks = advisory.hints.map { it.severity.ordinal }
        assertEquals(ranks.sorted(), ranks)
        assertEquals(advisory.hints.size, advisory.hints.map { it.kind }.toSet().size)
        assertEquals(PaymentAdvisoryVerdict.STOP, advisory.verdict)
        assertTrue(advisory.confirmationRequired)
    }
}
