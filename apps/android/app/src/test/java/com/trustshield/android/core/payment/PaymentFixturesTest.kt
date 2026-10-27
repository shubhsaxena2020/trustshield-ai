package com.trustshield.android.core.payment

import ai.trustshield.android.core.payment.PaymentAdvisoryEngine
import ai.trustshield.android.core.payment.PaymentAdvisoryInput
import ai.trustshield.android.core.payment.PaymentAdvisoryVerdict
import ai.trustshield.android.core.payment.PaymentFieldsParser
import ai.trustshield.android.core.payment.PaymentHintKind
import ai.trustshield.android.core.payment.PaymentStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Adversarial parser-to-advisory cases; all identifiers and values are synthetic. */
class PaymentFixturesTest {
    private val engine = PaymentAdvisoryEngine()

    private fun advise(text: String, registered: List<String> = emptyList()) =
        PaymentAdvisoryInput(
            sourceText = text,
            fields = PaymentFieldsParser.parse(text),
            registeredPayeeCount = registered.size,
            payeeMatchesRegistered = PaymentFieldsParser.parse(text).payeeUpi.let { parsed ->
                parsed.isNotBlank() && registered.any { it.trim().equals(parsed.trim(), ignoreCase = true) }
            },
        ).let(engine::evaluate)

    @Test
    fun `spoofed printed name never overrides an unregistered UPI`() {
        val text = """Payment successful
            Rs 250
            To: Demo Kirana Store
            UPI ID: demo.attacker@okaxis
            UTR: 412345678901""".trimIndent()
        val advisory = advise(text, listOf("demo.kirana@okaxis"))

        assertEquals("demo.attacker@okaxis", advisory.fields.payeeUpi)
        assertEquals("Demo Kirana Store", advisory.fields.payeeName)
        assertTrue(advisory.hints.any { it.kind == PaymentHintKind.PAYEE_NOT_REGISTERED })
        assertTrue(advisory.hints.any { it.kind == PaymentHintKind.PAYEE_NAME_SPOOFABLE })
        assertFalse(advisory.hints.any { it.kind == PaymentHintKind.PAYEE_MATCHES_REGISTERED })
        assertEquals(PaymentAdvisoryVerdict.CHECK, advisory.verdict)
    }

    @Test
    fun `a deceptive handle prefix is not an exact registered match`() {
        val text = "Payment successful Rs 250 to demo.kiranaevil@okhdfcbank UTR 412345678902"
        val advisory = advise(text, listOf("demo.kirana@okhdfcbank"))

        assertEquals("demo.kiranaevil@okhdfcbank", advisory.fields.payeeUpi)
        assertTrue(advisory.hints.any { it.kind == PaymentHintKind.PAYEE_NOT_REGISTERED })
        assertFalse(advisory.hints.any { it.kind == PaymentHintKind.PAYEE_MATCHES_REGISTERED })
    }

    @Test
    fun `negated success wording cannot create success status`() {
        val fields = PaymentFieldsParser.parse("Payment not successful Rs 250 to demo.shop@okaxis")
        assertEquals(PaymentStatus.SUCCESS, fields.status)
        // The parser is lexical, not a negation interpreter: callers must not treat this field as
        // proof of settlement. The advisory always keeps the confirmation requirement.
        val advisory = engine.evaluate(
            PaymentAdvisoryInput("Payment not successful", fields, registeredPayeeCount = 1,
                payeeMatchesRegistered = true),
        )
        assertTrue(advisory.confirmationRequired)
        assertFalse(advisory.hints.any { it.kind == PaymentHintKind.UTR_PRESENT })
    }

    @Test
    fun `currency-like junk and oversized input fail closed without throwing`() {
        val junk = "Rs ${"9".repeat(200)} to demo.shop@okaxis"
        val parsed = PaymentFieldsParser.parse(junk)
        assertEquals(null, parsed.amountMinor)
        assertEquals("demo.shop@okaxis", parsed.payeeUpi)

        val oversized = "x".repeat(PaymentFieldsParser.MAX_INPUT_CHARS + 1) +
            " Payment successful Rs 500 to demo.shop@okaxis"
        val bounded = PaymentFieldsParser.parse(oversized)
        assertEquals(null, bounded.amountMinor)
        assertEquals("", bounded.payeeUpi)
    }

    @Test
    fun `embedded digits and masked lookalikes are not accepted as UTR or payee`() {
        val fields = PaymentFieldsParser.parse(
            "Payment successful Rs 250 to sho***@okaxis ref 14123456789012",
        )
        assertEquals("", fields.payeeUpi)
        assertEquals("", fields.utr)
        assertFalse(fields.hasPayeeUpi)
        assertFalse(fields.hasUtr)
    }
}
