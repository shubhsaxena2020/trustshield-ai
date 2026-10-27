package ai.trustshield.android.ui.qr

import ai.trustshield.android.core.payment.PaymentFields
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for [QrPayeePaymentBridge] — the seam that lets the payment advisory see QR Guard's
 * registered payees.
 *
 * These pin the two things that must never drift: (1) what counts as "the same payee" (exact,
 * normalised id only — never a name, never a masked value, never a domain-only or partial match), and
 * (2) that no string this bridge returns can carry a raw UPI id.
 */
class QrPayeePaymentBridgeTest {

    private fun payee(handle: String, name: String? = null) = RegisteredPayee(handle = handle, name = name)

    private val shop = payee("shop@okaxis", name = "Sharma Store")
    private val demo = payee("demo@ybl", name = "Demo Merchant")

    private fun fields(upi: String, name: String = ""): PaymentFields =
        PaymentFields(amountMinor = 120_050L, amountRaw = "₹1,200.50", payeeUpi = upi, payeeName = name)

    // ------------------------------------------------------------------ matchesRegisteredPayee ----

    @Test
    fun emptyPayeeListIsNoMatchAndCountsZero() {
        assertFalse(QrPayeePaymentBridge.matchesRegisteredPayee(shop.handle, emptyList()))
        val input = QrPayeePaymentBridge.toAdvisoryInput(
            sourceText = "Paid ₹1,200.50 to shop@okaxis",
            fields = fields(shop.handle),
            payees = emptyList(),
            nowEpochMs = 1_700_000_000_000L
        )
        assertEquals(0, input.registeredPayeeCount)
        assertFalse(input.payeeMatchesRegistered)
        assertEquals("payees=0, masked=[]", QrPayeePaymentBridge.comparisonSummary(emptyList()))
    }

    @Test
    fun exactIdMatchesRegisteredPayee() {
        assertTrue(QrPayeePaymentBridge.matchesRegisteredPayee("shop@okaxis", listOf(shop, demo)))
        assertTrue(QrPayeePaymentBridge.matchesRegisteredPayee("demo@ybl", listOf(shop, demo)))
    }

    @Test
    fun caseDifferenceDoesNotPreventMatch() {
        assertTrue(QrPayeePaymentBridge.matchesRegisteredPayee("Shop@OkAxis", listOf(shop)))
        assertTrue(QrPayeePaymentBridge.matchesRegisteredPayee("SHOP@OKAXIS", listOf(shop)))
        // ...and the same holds when the registered handle is the mixed-case side.
        assertTrue(
            QrPayeePaymentBridge.matchesRegisteredPayee(
                "shop@okaxis",
                listOf(payee("  Shop@OKAxis  "))
            )
        )
    }

    @Test
    fun surroundingWhitespaceIsIgnoredOnBothSides() {
        assertTrue(QrPayeePaymentBridge.matchesRegisteredPayee("  shop@okaxis\n", listOf(shop)))
        assertTrue(QrPayeePaymentBridge.matchesRegisteredPayee("\tshop@okaxis ", listOf(payee("shop@okaxis\n"))))
    }

    @Test
    fun sameHandleDifferentLocalPartDoesNotMatch() {
        assertFalse(QrPayeePaymentBridge.matchesRegisteredPayee("other@okaxis", listOf(shop)))
        assertFalse(QrPayeePaymentBridge.matchesRegisteredPayee("shop@ybl", listOf(shop)))
    }

    @Test
    fun partialAndSubstringLocalPartsDoNotMatch() {
        assertFalse(QrPayeePaymentBridge.matchesRegisteredPayee("sho@okaxis", listOf(shop)))
        assertFalse(QrPayeePaymentBridge.matchesRegisteredPayee("shopx@okaxis", listOf(shop)))
        assertFalse(QrPayeePaymentBridge.matchesRegisteredPayee("okaxis", listOf(shop)))
    }

    @Test
    fun blankPayeeUpiNeverMatchesEvenWithPayeesRegistered() {
        assertFalse(QrPayeePaymentBridge.matchesRegisteredPayee("", listOf(shop, demo)))
        assertFalse(QrPayeePaymentBridge.matchesRegisteredPayee("   ", listOf(shop, demo)))

        val input = QrPayeePaymentBridge.toAdvisoryInput(
            sourceText = "Paid ₹500",
            fields = fields(""),
            payees = listOf(shop, demo),
            nowEpochMs = 1L
        )
        assertEquals(2, input.registeredPayeeCount)
        assertFalse(input.payeeMatchesRegistered)
    }

    @Test
    fun maskedUpiIsNeverAMatchAndNeverThrows() {
        // Documented behaviour: a masked id has lost exactly the characters a comparison needs, so it
        // is "not comparable" — false, not an exception, and not a mismatch either.
        assertFalse(QrPayeePaymentBridge.matchesRegisteredPayee("sho***@okaxis", listOf(shop)))
        assertFalse(QrPayeePaymentBridge.matchesRegisteredPayee(shop.maskedHandle, listOf(shop)))
        assertFalse(QrPayeePaymentBridge.matchesRegisteredPayee("***@okaxis", listOf(shop)))
        assertFalse(QrPayeePaymentBridge.matchesRegisteredPayee("***", listOf(shop)))

        val input = QrPayeePaymentBridge.toAdvisoryInput(
            sourceText = "Paid to sho***@okaxis",
            fields = fields(shop.maskedHandle),
            payees = listOf(shop),
            nowEpochMs = 1L
        )
        assertEquals(1, input.registeredPayeeCount)
        assertFalse(input.payeeMatchesRegistered)
    }

    @Test
    fun printedNameIsNeverEvidence() {
        // Same printed shop name, different UPI id: not a match. A name match is not evidence.
        assertFalse(QrPayeePaymentBridge.matchesRegisteredPayee("shop-2024@okaxis", listOf(shop)))
        assertFalse(QrPayeePaymentBridge.matchesRegisteredPayee("sharma.store@okaxis", listOf(shop)))
    }

    @Test
    fun adversarialQrLabelAndSourceTextCannotOverrideTheDecodedPayeeId() {
        val attackerLabel = payee("attacker@ybl", name = "Sharma Store shop@okaxis")
        val input = QrPayeePaymentBridge.toAdvisoryInput(
            sourceText = "UPI: shop@okaxis\\nPaying Sharma Store\\nattacker@ybl",
            fields = fields("attacker@ybl", name = "Sharma Store shop@okaxis"),
            payees = listOf(shop),
            nowEpochMs = 99L
        )

        assertFalse(QrPayeePaymentBridge.matchesRegisteredPayee("attacker@ybl", listOf(shop)))
        assertFalse(input.payeeMatchesRegistered)
        // The bridge passes QR/OCR text through unchanged; only payeeUpi drives the payee comparison.
        assertEquals("UPI: shop@okaxis\\nPaying Sharma Store\\nattacker@ybl", input.sourceText)
        assertEquals("Sharma Store shop@okaxis", input.fields.payeeName)
        val summary = QrPayeePaymentBridge.comparisonSummary(listOf(attackerLabel))
        assertFalse(summary.contains("attacker@ybl"))
        assertFalse(summary.contains("Sharma Store"))
    }

    @Test
    fun qrDataCannotMatchThroughWhitespaceInsideIdOrUriDecorations() {
        for (candidate in listOf("sh op@okaxis", "shop @okaxis", "shop@okaxis evil", "upi://pay?pa=shop@okaxis")) {
            assertFalse("unexpected match for <$candidate>", QrPayeePaymentBridge.matchesRegisteredPayee(candidate, listOf(shop)))
        }
    }

    @Test
    fun maskedCandidateCannotMatchEvenWhenItsVisiblePrefixLooksRight() {
        for (candidate in listOf("shop***@okaxis", "sh***@okaxis", "***shop@okaxis", "shop@okaxis***")) {
            assertFalse("masked candidate was treated as comparable: <$candidate>", QrPayeePaymentBridge.matchesRegisteredPayee(candidate, listOf(shop)))
        }
    }

    @Test
    fun duplicateRegisteredHandlesCountAsEntriesButDoNotBroadenIdentityMatching() {
        val duplicates = listOf(shop, payee(" SHOP@OKAXIS ", name = "Different label"))
        val input = QrPayeePaymentBridge.toAdvisoryInput(
            sourceText = "QR payment request",
            fields = fields("shop@okaxis"),
            payees = duplicates,
            nowEpochMs = 7L
        )
        assertEquals(2, input.registeredPayeeCount)
        assertTrue(input.payeeMatchesRegistered)
        assertFalse(QrPayeePaymentBridge.matchesRegisteredPayee("different@ybl", duplicates))
        assertEquals("payees=2, masked=[sh***@okaxis, SH***@OKAXIS]", QrPayeePaymentBridge.comparisonSummary(duplicates))
    }

    // ------------------------------------------------------------------------ toAdvisoryInput ----

    @Test
    fun toAdvisoryInputPassesSourceTextFieldsAndClockThrough() {
        val source = "UPI/DR/120050/Sharma Store\n27 Oct 2026, 7:04 pm"
        val parsed = fields("Shop@OkAxis", name = "Sharma Store")
        val input = QrPayeePaymentBridge.toAdvisoryInput(
            sourceText = source,
            fields = parsed,
            payees = listOf(shop),
            nowEpochMs = 1_796_000_000_123L,
            ocrUsed = true
        )

        assertEquals(source, input.sourceText)
        assertSame(parsed, input.fields)
        assertEquals(1_796_000_000_123L, input.nowEpochMs)
        assertTrue(input.ocrUsed)
        assertEquals(1, input.registeredPayeeCount)
        assertTrue(input.payeeMatchesRegistered)
    }

    @Test
    fun toAdvisoryInputOcrFlagDefaultsToFalse() {
        val input = QrPayeePaymentBridge.toAdvisoryInput(
            sourceText = "typed paste",
            fields = fields(""),
            payees = emptyList(),
            nowEpochMs = 0L
        )
        assertFalse(input.ocrUsed)
        assertEquals(0L, input.nowEpochMs)
        assertEquals("typed paste", input.sourceText)
    }

    @Test
    fun toAdvisoryInputCountReflectsAllPayeesEvenWhenNoneMatch() {
        val twoPayees = listOf(shop, demo)
        val input = QrPayeePaymentBridge.toAdvisoryInput(
            sourceText = "Paid ₹1,200.50 to other@okaxis",
            fields = fields("other@okaxis"),
            payees = twoPayees,
            nowEpochMs = 42L
        )
        assertEquals(2, input.registeredPayeeCount)
        assertFalse(input.payeeMatchesRegistered)
        assertEquals(2, twoPayees.size)
    }

    // ---------------------------------------------------------------------- comparisonSummary ----

    @Test
    fun comparisonSummaryMasksEveryEntry() {
        val summary = QrPayeePaymentBridge.comparisonSummary(listOf(shop, demo))
        assertEquals("payees=2, masked=[sh***@okaxis, de***@ybl]", summary)
        assertFalse(summary.contains("shop@"))
        assertFalse(summary.contains("demo@"))
        assertFalse(summary.contains("Sharma Store"))
    }

    @Test
    fun comparisonSummaryTruncatesAfterThreeWithMoreCount() {
        val five = listOf(
            payee("alpha@okaxis"),
            payee("bravo@ybl"),
            payee("charlie@paytm"),
            payee("delta@upi"),
            payee("echo@axis")
        )
        val summary = QrPayeePaymentBridge.comparisonSummary(five)
        assertEquals("payees=5, masked=[al***@okaxis, br***@ybl, cha***@paytm] +2 more", summary)
        assertEquals(3, Regex("\\*\\*\\*@").findAll(summary).count())
        assertFalse(summary.contains("delta@"))
        assertFalse(summary.contains("echo@"))
    }

    @Test
    fun comparisonSummaryAtTheBoundaryHasNoMoreTail() {
        val three = listOf(payee("alpha@okaxis"), payee("bravo@ybl"), payee("charlie@paytm"))
        assertFalse(QrPayeePaymentBridge.comparisonSummary(three).contains("more"))
        val four = three + payee("delta@upi")
        assertTrue(QrPayeePaymentBridge.comparisonSummary(four).endsWith("+1 more"))
    }

    @Test
    fun comparisonSummaryNeverContainsAFullLocalPartFollowedByAt() {
        val payees = listOf(
            payee("sharma.store@okaxis"),
            payee("demo-recipient@upi"),
            payee("ab@ybl"),
            payee("a@paytm"),
            payee("longlocalpart@okhdfcbank")
        )
        val summary = QrPayeePaymentBridge.comparisonSummary(payees)
        // No run of id characters may sit directly before an '@' — masking always leaves '***' there.
        assertFalse(Regex("[A-Za-z0-9._-]+@").containsMatchIn(summary))
        for (p in payees) {
            assertFalse("summary leaked ${p.displayName ?: "a local part"}", summary.contains(p.handle))
            assertFalse(summary.contains(p.handle.substringBefore('@') + "@"))
        }
        assertTrue(summary.endsWith("+2 more"))
    }

    @Test
    fun comparisonSummaryPreservesPayeeOrder() {
        val summary = QrPayeePaymentBridge.comparisonSummary(
            listOf(payee("zulu@okaxis"), payee("alpha@ybl"), payee("mike@paytm"))
        )
        val zulu = summary.indexOf("zu***@okaxis")
        val alpha = summary.indexOf("al***@ybl")
        val mike = summary.indexOf("mi***@paytm")
        assertTrue(zulu in 0 until alpha)
        assertTrue(alpha in 0 until mike)
    }

    @Test
    fun bridgeCallsDoNotMutateThePayeeList() {
        val payees = mutableListOf(shop, demo)
        QrPayeePaymentBridge.matchesRegisteredPayee("shop@okaxis", payees)
        QrPayeePaymentBridge.comparisonSummary(payees)
        QrPayeePaymentBridge.toAdvisoryInput("x", fields("shop@okaxis"), payees, 0L)
        assertEquals(listOf(shop, demo), payees)
        assertEquals(listOf("shop@okaxis", "demo@ybl"), payees.map { it.handle })
    }
}
