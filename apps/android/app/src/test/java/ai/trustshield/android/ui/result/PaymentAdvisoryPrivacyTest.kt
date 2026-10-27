package ai.trustshield.android.ui.result

import ai.trustshield.android.core.payment.PaymentFields
import ai.trustshield.android.core.payment.PaymentHint
import ai.trustshield.android.core.payment.PaymentHintKind
import ai.trustshield.android.core.payment.PaymentHintSeverity
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Adversarial JVM checks: raw payment identifiers must not enter display copy or loggable models. */
class PaymentAdvisoryPrivacyTest {
    @Test
    fun stringificationOfPaymentModelsNeverContainsRawIdentifiersOrSensitiveValues() {
        val rawUpi = "private.payee.948217@upi"
        val rawName = "Nisha Rao Secret"
        val rawUtr = "987654321098"
        val fields = PaymentFields(
            amountMinor = 990000L,
            amountRaw = "₹9,900",
            payeeUpi = rawUpi,
            payeeName = rawName,
            utr = rawUtr
        )
        val loggedFields = fields.toString()
        assertTrue("PaymentFields stringification should use the exact mask", loggedFields.contains("upi=pri***@upi"))
        listOf(rawUpi, rawName, rawUtr, "990000", "9,900").forEach { secret ->
            assertFalse("PaymentFields log form leaked a sensitive value", loggedFields.contains(secret))
        }
        val fieldsSource = File("src/main/java/ai/trustshield/android/core/payment/PaymentAdvisoryContract.kt").readText()

        assertTrue("PaymentFields stringification should use the masked identifier", fieldsSource.contains("upi=${'$'}{maskedUpi.ifBlank"))
        assertTrue("PaymentFields stringification should expose only presence of name/UTR", fieldsSource.contains("hasName=${'$'}{payeeName.isNotBlank()}") && fieldsSource.contains("hasUtr=${'$'}hasUtr"))
        assertFalse("field toString must not interpolate raw UPI, name, or UTR", Regex("toString\\(\\): String[\\s\\S]*?payeeUpi|toString\\(\\): String[\\s\\S]*?payeeName|toString\\(\\): String[\\s\\S]*?utr").containsMatchIn(fieldsSource))

        val hint = PaymentHint(
            kind = PaymentHintKind.PAYEE_NOT_REGISTERED,
            severity = PaymentHintSeverity.CAUTION,
            maskedPayee = "pri***@upi",
            amountMinor = 990000L
        )
        val logged = hint.toString()
        listOf(rawUpi, rawName, rawUtr).forEach { secret -> assertFalse("hint log form leaked a sensitive value", logged.contains(secret)) }
        assertTrue("hint may log its masked payee", logged.contains("pri***@upi"))
        assertFalse("hint log form must not include amount", logged.contains("990000"))
    }

    @Test
    fun advisoryCopyShowsOnlyTheSuppliedMaskAndNeverRawOrMisleadingInput() {
        val raw = "private.payee.948217@upi"
        val hint = PaymentHint(
            kind = PaymentHintKind.PAYEE_NOT_REGISTERED,
            severity = PaymentHintSeverity.CAUTION,
            maskedPayee = "pri***@upi"
        )
        val copy = PaymentAdvisoryCopy.forHint(hint)
        listOf(copy.hinglish, copy.english, copy.hindi).forEach { text ->
            assertTrue("masked payee should be displayed exactly", text.contains("pri***@upi"))
            assertFalse("raw identifier must never enter user-visible copy", text.contains(raw))
            assertFalse("copy must not imply a confirmed fraud", text.contains("fraud hai", ignoreCase = true))
        }
    }

    @Test
    fun copyUsesTheMaskedHintFieldAndDoesNotReadRawPaymentFields() {
        val source = File("src/main/java/ai/trustshield/android/ui/result/PaymentAdvisoryCopy.kt").readText()
        assertTrue("copy should interpolate only the explicitly masked hint field", source.contains("hint.maskedPayee"))
        assertFalse("copy must not receive or read raw payment identifiers", source.contains("hint.payeeUpi"))
        assertFalse("copy must not receive or read raw payee names", source.contains("hint.payeeName"))
    }
}
