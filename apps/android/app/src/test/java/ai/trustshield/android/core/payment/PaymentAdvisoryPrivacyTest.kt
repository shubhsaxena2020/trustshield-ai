package ai.trustshield.android.core.payment

import org.junit.Assert.assertFalse
import org.junit.Test

/** Adversarial checks for value-free debug/log-safe payment representations. */
class PaymentAdvisoryPrivacyTest {
    @Test
    fun `fields toString never exposes raw payment values`() {
        val rawUpi = "attacker.vpa.7Qx9@upi-example"
        val rawAmount = "₹987,654.32"
        val amountMinor = 98_765_432L
        val rawReference = "731904826517"
        val fields = PaymentFields(
            amountMinor = amountMinor,
            amountRaw = rawAmount,
            payeeUpi = rawUpi,
            payeeName = "Synthetic Recipient Zp4",
            utr = rawReference,
            status = PaymentStatus.SUCCESS,
            statusRaw = "SUCCESS-raw-status-N8",
            timestampRaw = "28 Oct 2026 09:41:17 Tm6",
        )

        val rendered = fields.toString()
        listOf(rawUpi, rawAmount, amountMinor.toString(), rawReference,
            fields.payeeName, fields.statusRaw, fields.timestampRaw).forEach { secret ->
            assertFalse("PaymentFields.toString leaked '$secret': $rendered", rendered.contains(secret))
        }
    }

    @Test
    fun `hint advisory and their combined log-safe representations omit amount and reference`() {
        val rawUpi = "adversarial.payee.K4m2@bank-test"
        val rawAmount = "Rs 876,543.21"
        val amountMinor = 87_654_321L
        val rawReference = "604829173506"
        val fields = PaymentFields(
            amountMinor = amountMinor,
            amountRaw = rawAmount,
            payeeUpi = rawUpi,
            payeeName = "Recipient Vx91",
            utr = rawReference,
            status = PaymentStatus.SUCCESS,
            statusRaw = "SUCCESS-reference-check",
            timestampRaw = "2026-10-28T11:22:33-Xq8",
        )
        val advisory = PaymentAdvisoryEngine().evaluate(
            PaymentAdvisoryInput(
                sourceText = "Synthetic receipt $rawAmount to $rawUpi ref $rawReference",
                fields = fields,
                registeredPayeeCount = 3,
                payeeMatchesRegistered = false,
            )
        )
        val rendered = buildString {
            append(fields)
            append(advisory)
            advisory.hints.forEach { append(it) }
        }

        listOf(rawUpi, rawAmount, amountMinor.toString(), rawReference,
            fields.payeeName, fields.statusRaw, fields.timestampRaw).forEach { secret ->
            assertFalse("Payment debug representation leaked '$secret': $rendered", rendered.contains(secret))
        }
    }
}
