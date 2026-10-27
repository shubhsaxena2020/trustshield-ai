package com.trustshield.android.ui.qr

import ai.trustshield.android.core.payment.PaymentFields
import ai.trustshield.android.ui.qr.QrPayeePaymentBridge
import ai.trustshield.android.ui.qr.RegisteredPayee
import ai.trustshield.android.ui.result.PaymentAdvisoryCopy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Additional hostile-input and disclaimer-order regression tests for the QR/payment bridge. */
class QrPayeePaymentBridgeTest {
    private val registered = RegisteredPayee(handle = "shop@okaxis", name = "Sharma Store")

    private fun fields(upi: String) = PaymentFields(
        amountMinor = 50000L,
        amountRaw = "₹500",
        payeeUpi = upi,
        payeeName = "Sharma Store"
    )

    @Test
    fun malformedAndDecoratedIdentifiersNeverMatch() {
        listOf(
            "shop@okaxis.evil", "shop@okaxis@evil", "shop@", "@okaxis", "upi://pay?pa=shop@okaxis",
            "shop%40okaxis", "shop@okaxis\u0000", "shop @okaxis", "shop@okaxis/extra"
        ).forEach { candidate ->
            assertFalse("must not match <$candidate>", QrPayeePaymentBridge.matchesRegisteredPayee(candidate, listOf(registered)))
        }
    }

    @Test
    fun comparisonTrimsWhitespaceAndIgnoresCaseOnBothSides() {
        val variants = listOf(
            RegisteredPayee(handle = "  SHOP@OKAXIS  ", name = "Sharma Store"),
            RegisteredPayee(handle = "shop@okaxis", name = "Sharma Store")
        )
        listOf("SHOP@OKAXIS", "  shop@okaxis  ", "\tShOp@OkAxIs\n").forEach { candidate ->
            assertTrue("trimmed, case-insensitive exact id should match <$candidate>",
                QrPayeePaymentBridge.matchesRegisteredPayee(candidate, variants))
        }
    }

    @Test
    fun malformedAndMaskedIdentifiersNeverMatchEvenWhenRegistrationLooksSimilar() {
        val malformedOrMaskedRegistrations = listOf(
            RegisteredPayee(handle = "sho***@okaxis", name = "Sharma Store"),
            RegisteredPayee(handle = "shop @okaxis", name = "Sharma Store"),
            RegisteredPayee(handle = "shop@okaxis.evil", name = "Sharma Store")
        )
        listOf("sho***@okaxis", "shop***@okaxis", "shop @okaxis", "shop@okaxis.evil")
            .forEach { candidate ->
                assertFalse("masked/malformed id must not match <$candidate>",
                    QrPayeePaymentBridge.matchesRegisteredPayee(candidate, malformedOrMaskedRegistrations))
            }
        assertFalse("masked candidate must not match a valid registration",
            QrPayeePaymentBridge.matchesRegisteredPayee("sho***@okaxis", listOf(registered)))
    }

    @Test
    fun emptyAndPathologicalIdsAreNonMatchesWithoutThrowing() {
        listOf("", " ", "***", "shop***@okaxis", "x".repeat(100_000), "\n\t").forEach { candidate ->
            assertFalse(QrPayeePaymentBridge.matchesRegisteredPayee(candidate, listOf(registered)))
        }
    }

    @Test
    fun attackerControlledLabelsCannotRescueDifferentPayeeOrLeakIntoSummary() {
        val hostile = RegisteredPayee(handle = "attacker@ybl", name = "shop@okaxis")
        val input = QrPayeePaymentBridge.toAdvisoryInput(
            sourceText = "Pay shop@okaxis; attacker@ybl",
            fields = fields("attacker@ybl"),
            payees = listOf(registered),
            nowEpochMs = 123L
        )
        assertFalse(input.payeeMatchesRegistered)
        val summary = QrPayeePaymentBridge.comparisonSummary(listOf(hostile))
        assertFalse(summary.contains("attacker@ybl"))
        assertFalse(summary.contains("shop@okaxis"))
    }

    @Test
    fun disclaimerIsMandatoryAndLastInSpokenCardAssembly() {
        val source = java.io.File(
            "src/main/java/ai/trustshield/android/ui/result/PaymentAdvisoryCard.kt"
        ).readText()
        val finalAppend = source.indexOf("append(confirmation)")
        assertTrue("confirmation clause is assembled", finalAppend >= 0)
        assertTrue("confirmation must follow every optional hint", source.indexOf("hintRows.forEach") in 0 until finalAppend)
        assertTrue(source.contains("AdvisoryConfirmationLine(text = confirmation)"))
        assertEquals("Pakka confirmation sirf aapke bank SMS ya UPI app ki history mein milega.", PaymentAdvisoryCopy.CONFIRMATION.hinglish)
        assertTrue(PaymentAdvisoryCopy.CONFIRMATION.english.contains("bank SMS"))
        assertTrue(PaymentAdvisoryCopy.CONFIRMATION.hindi.contains("बैंक SMS"))
    }
}
