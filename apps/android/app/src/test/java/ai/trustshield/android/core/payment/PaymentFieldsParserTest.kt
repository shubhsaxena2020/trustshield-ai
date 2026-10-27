package ai.trustshield.android.core.payment

import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract gate for [PaymentFieldsParser]: the fields a payment screenshot can honestly yield, the
 * things it must *not* infer (bare numbers, phone numbers, masked UPIs), and the "never throws" bound.
 *
 * Fixtures are synthetic. No real UPI id, phone number, amount or UTR from a person appears here.
 * A receipt time carries no zone, so expected epochs are built in the JVM default zone — the same
 * assumption the parser documents.
 */
class PaymentFieldsParserTest {

    private val gpayReceipt = """
        GPay
        Payment successful
        ₹1,200.50
        Paid to Sharma Store
        UPI ID: sharma.store@okaxis
        UTR: 526312345678
        27 Oct 2026, 7:04 pm
    """.trimIndent()

    private val phonePePending = """
        PhonePe
        Payment Pending
        ₹1,200
        To: Sharma Store
        UPI ID: sharma@ybl
        27/10/2026 19:04
    """.trimIndent()

    private val paytmFailed = """
        Paytm
        Payment failed
        Rs 500
        Paid to Verma Kirana
        verma@paytm
        RRN 987654321098
    """.trimIndent()

    private fun parse(text: String): PaymentFields = PaymentFieldsParser.parse(text)

    private fun epochOf(
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int,
        second: Int = 0
    ): Long = LocalDateTime.of(year, month, day, hour, minute, second)
        .atZone(ZoneId.systemDefault())
        .toInstant()
        .toEpochMilli()

    // ---- contract surface ------------------------------------------------------------------

    @Test
    fun `max input chars constant is pinned`() {
        assertEquals(4000, PaymentFieldsParser.MAX_INPUT_CHARS)
    }

    @Test
    fun `gpay style receipt yields every field`() {
        val fields = parse(gpayReceipt)

        assertEquals(120050L, fields.amountMinor)
        assertEquals("₹1,200.50", fields.amountRaw)
        assertEquals("sharma.store@okaxis", fields.payeeUpi)
        assertEquals("Sharma Store", fields.payeeName)
        assertEquals("526312345678", fields.utr)
        assertEquals(PaymentStatus.SUCCESS, fields.status)
        assertTrue("status word must be quoted", fields.statusRaw.isNotEmpty())
        assertEquals("27 Oct 2026, 7:04 pm", fields.timestampRaw)
        assertEquals(epochOf(2026, 10, 27, 19, 4), fields.timestampEpochMs)
        assertEquals(PaymentAppHint.GPAY, fields.appHint)
        assertTrue(fields.looksLikeReceipt)
    }

    @Test
    fun `gpay style receipt looks like a payment screenshot`() {
        assertTrue(PaymentFieldsParser.looksLikePaymentScreenshot(gpayReceipt))
    }

    @Test
    fun `phonepe pending receipt reports pending`() {
        val fields = parse(phonePePending)

        assertEquals(120000L, fields.amountMinor)
        assertEquals("₹1,200", fields.amountRaw)
        assertEquals(PaymentStatus.PENDING, fields.status)
        assertEquals("Pending", fields.statusRaw)
        assertEquals("sharma@ybl", fields.payeeUpi)
        assertEquals("Sharma Store", fields.payeeName)
        assertEquals("", fields.utr)
        assertEquals("27/10/2026 19:04", fields.timestampRaw)
        assertEquals(epochOf(2026, 10, 27, 19, 4), fields.timestampEpochMs)
        assertEquals(PaymentAppHint.PHONEPE, fields.appHint)
        assertTrue(PaymentFieldsParser.looksLikePaymentScreenshot(phonePePending))
    }

    @Test
    fun `paytm failed receipt reports failed`() {
        val fields = parse(paytmFailed)

        assertEquals(50000L, fields.amountMinor)
        assertEquals("Rs 500", fields.amountRaw)
        assertEquals(PaymentStatus.FAILED, fields.status)
        assertEquals("failed", fields.statusRaw)
        assertEquals("verma@paytm", fields.payeeUpi)
        assertEquals("Verma Kirana", fields.payeeName)
        assertEquals("987654321098", fields.utr)
        assertEquals(PaymentAppHint.PAYTM, fields.appHint)
        assertTrue(PaymentFieldsParser.looksLikePaymentScreenshot(paytmFailed))
    }

    // ---- amount ---------------------------------------------------------------------------

    @Test
    fun `amount with indian grouping converts to paise`() {
        val fields = parse("₹12,00,000 transferred to shop@okaxis")

        assertEquals(120_000_000L, fields.amountMinor)
        assertEquals("₹12,00,000", fields.amountRaw)
    }

    @Test
    fun `amount without decimals converts to whole paise`() {
        val fields = parse("Rs 500")

        assertEquals(50000L, fields.amountMinor)
        assertEquals("Rs 500", fields.amountRaw)
    }

    @Test
    fun `amount with paise keeps the exact matched substring`() {
        val fields = parse("Rs.1,200.50")

        assertEquals(120050L, fields.amountMinor)
        assertEquals("Rs.1,200.50", fields.amountRaw)
    }

    @Test
    fun `devanagari digits are not read as an amount`() {
        // Documented behaviour: the amount pattern is ASCII-only, so a Devanagari numeral stays
        // unread rather than being guessed at. The rest of the receipt still parses.
        val fields = parse("₹५०० paid to shop@okaxis")

        assertNull(fields.amountMinor)
        assertEquals("", fields.amountRaw)
        assertEquals("shop@okaxis", fields.payeeUpi)
        assertEquals(PaymentStatus.SUCCESS, fields.status)
    }

    @Test
    fun `first currency marked amount wins`() {
        val fields = parse("Cashback 5000 milega\nFee ₹99\nTotal ₹1,200")

        assertEquals(9900L, fields.amountMinor)
        assertEquals("₹99", fields.amountRaw)
    }

    @Test
    fun `bare numbers are never amounts`() {
        val fields = parse("A/c 12345, order 500")

        assertNull(fields.amountMinor)
        assertEquals("", fields.amountRaw)
    }

    @Test
    fun `zero amount is not a payment`() {
        val fields = parse("₹0 paid")

        assertNull(fields.amountMinor)
        assertFalse(fields.looksLikeReceipt)
        assertFalse(PaymentFieldsParser.looksLikePaymentScreenshot("₹0 paid"))
    }

    // ---- payee ----------------------------------------------------------------------------

    @Test
    fun `masked upi yields a blank payee but still looks like a screenshot`() {
        val fields = parse("₹1,200.50\nPayment successful\nsho***@okaxis")

        assertEquals("", fields.payeeUpi)
        assertFalse(fields.hasPayeeUpi)
        assertEquals(120050L, fields.amountMinor)
        assertEquals(PaymentStatus.SUCCESS, fields.status)
        assertTrue(PaymentFieldsParser.looksLikePaymentScreenshot("₹1,200.50\nPayment successful\nsho***@okaxis"))
    }

    @Test
    fun `middle masked upi is rejected too`() {
        val fields = parse("₹500 paid\nab***cd@okaxis")

        assertEquals("", fields.payeeUpi)
        assertEquals(50000L, fields.amountMinor)
    }

    @Test
    fun `payee upi next to the payee cue beats a stray address`() {
        val fields = parse("Report to support@trustshield.ai\nPaid to merchant@okhdfcbank")

        assertEquals("merchant@okhdfcbank", fields.payeeUpi)
    }

    @Test
    fun `payee name is never the upi id itself`() {
        val fields = parse("Paid to sho***@okaxis")

        assertEquals("", fields.payeeName)
        assertEquals("", fields.payeeUpi)
    }

    @Test
    fun `payee name is read from the pn parameter`() {
        val fields = parse("upi://pay?pa=merchant@okaxis&pn=Sharma Store&am=1200")

        assertEquals("merchant@okaxis", fields.payeeUpi)
        assertEquals("Sharma Store", fields.payeeName)
        assertNull(fields.amountMinor)
    }

    @Test
    fun `encoded uri values are not silently decoded into payment fields`() {
        val fields = parse("upi://pay?pa=merchant%40okaxis&pn=Sharma%20Store&am=1200")

        assertEquals("", fields.payeeUpi)
        assertEquals("Sharma%20Store", fields.payeeName)
        assertNull(fields.amountMinor)
    }

    @Test
    fun `duplicate pa parameters select the payee adjacent to its cue`() {
        val fields = parse("upi://pay?pa=first@okaxis&pn=First&pa=second@okhdfcbank&pn=Second")

        assertEquals("first@okaxis", fields.payeeUpi)
        assertEquals("First", fields.payeeName)
        assertNull(fields.amountMinor)
    }

    @Test
    fun `hostile uri values do not become amount or payee identity`() {
        val fields = parse("upi://pay?pa=attacker@okaxis&pn=₹9,999.99%26&am=999999999999999999")

        assertEquals("attacker@okaxis", fields.payeeUpi)
        assertEquals("%26", fields.payeeName)
        assertNull(fields.amountMinor)
    }

    @Test
    fun `masked and malformed duplicate pa values are not repaired`() {
        val fields = parse("upi://pay?pa=ab***cd@okaxis&pa=bad..name@okaxis")

        assertEquals("", fields.payeeUpi)
    }

    @Test
    fun `hostile uri delimiters cannot make parameter text an amount`() {
        val fields = parse("upi://pay?pa=merchant@okaxis&pn=₹500&am=500")

        assertEquals("merchant@okaxis", fields.payeeUpi)
        assertNull(fields.amountMinor)
        assertEquals("", fields.payeeName)
    }

    @Test
    fun `payee name on the line after the cue is read`() {
        val fields = parse("Paid to\nSharma Store\n₹500")

        assertEquals("Sharma Store", fields.payeeName)
    }

    @Test
    fun `toString never carries the raw upi id`() {
        val rendered = parse(gpayReceipt).toString()

        assertFalse(rendered, rendered.contains("sharma.store@okaxis"))
        assertTrue(rendered, rendered.contains("sha***@okaxis"))
    }

    // ---- utr ------------------------------------------------------------------------------

    @Test
    fun `utr only text yields a reference and no amount`() {
        val fields = parse("UTR 526312345678")

        assertEquals("526312345678", fields.utr)
        assertNull(fields.amountMinor)
        assertEquals("", fields.amountRaw)
        assertEquals(PaymentStatus.UNKNOWN, fields.status)
        assertFalse(PaymentFieldsParser.looksLikePaymentScreenshot("UTR 526312345678"))
    }

    @Test
    fun `labelled reference variants are recognised`() {
        assertEquals("111122223333", parse("Ref No 111122223333").utr)
        assertEquals("987654321098", parse("Txn ID 987654321098").utr)
        assertEquals("123456789012", parse("Transaction ID: 123456789012").utr)
        assertEquals("000000000001", parse("RRN: 000000000001").utr)
    }

    @Test
    fun `spaced reference digits are joined into the utr`() {
        assertEquals("123456789012", parse("UTR 1234 5678 9012").utr)
    }

    @Test
    fun `twelve digit token inside an amount is not a utr`() {
        val fields = parse("₹123456789012 paid to shop@okaxis")

        assertEquals(12_345_678_901_200L, fields.amountMinor)
        assertEquals("₹123456789012", fields.amountRaw)
        assertEquals("", fields.utr)
    }

    @Test
    fun `ten digit phone number is not a utr`() {
        assertEquals("", parse("Call 9876543210 for help").utr)
    }

    // ---- status ---------------------------------------------------------------------------

    @Test
    fun `pending beats success`() {
        val fields = parse("Payment successful\nStatus: Processing")

        assertEquals(PaymentStatus.PENDING, fields.status)
        assertEquals("Processing", fields.statusRaw)
    }

    @Test
    fun `failed beats success`() {
        val fields = parse("Payment failed\nPayment successful")

        assertEquals(PaymentStatus.FAILED, fields.status)
        assertEquals("failed", fields.statusRaw)
    }

    @Test
    fun `failed beats pending`() {
        val fields = parse("Status: Pending\nPayment failed")

        assertEquals(PaymentStatus.FAILED, fields.status)
        assertEquals("failed", fields.statusRaw)
    }

    @Test
    fun `status is unknown without a status word`() {
        val fields = parse("₹500 to shop@okaxis")

        assertEquals(PaymentStatus.UNKNOWN, fields.status)
        assertEquals("", fields.statusRaw)
        assertTrue(fields.looksLikeReceipt) // amount + payee is enough structure
        assertTrue(PaymentFieldsParser.looksLikePaymentScreenshot("₹500 to shop@okaxis"))
    }

    // ---- timestamp ------------------------------------------------------------------------

    @Test
    fun `day month year timestamp is parsed in the device zone`() {
        val fields = parse("Payment successful\n27 Oct 2026, 7:04 pm")

        assertEquals("27 Oct 2026, 7:04 pm", fields.timestampRaw)
        assertEquals(epochOf(2026, 10, 27, 19, 4), fields.timestampEpochMs)
    }

    @Test
    fun `slash timestamp is parsed`() {
        val fields = parse("₹500 paid\n27/10/2026 19:04")

        assertEquals("27/10/2026 19:04", fields.timestampRaw)
        assertEquals(epochOf(2026, 10, 27, 19, 4), fields.timestampEpochMs)
    }

    @Test
    fun `dash timestamp is parsed`() {
        val fields = parse("₹500 paid\n27-10-2026 09:05")

        assertEquals("27-10-2026 09:05", fields.timestampRaw)
        assertEquals(epochOf(2026, 10, 27, 9, 5), fields.timestampEpochMs)
    }

    @Test
    fun `iso timestamp is parsed with and without seconds`() {
        val withSeconds = parse("2026-10-27T19:04:30")
        assertEquals("2026-10-27T19:04:30", withSeconds.timestampRaw)
        assertEquals(epochOf(2026, 10, 27, 19, 4, 30), withSeconds.timestampEpochMs)

        val withoutSeconds = parse("₹500 paid at 2026-10-27T19:04")
        assertEquals("2026-10-27T19:04", withoutSeconds.timestampRaw)
        assertEquals(epochOf(2026, 10, 27, 19, 4), withoutSeconds.timestampEpochMs)
    }

    @Test
    fun `impossible date keeps the raw quote and yields a null epoch`() {
        val fields = parse("₹500 paid\n32 Oct 2026, 9:15 pm")

        assertEquals("32 Oct 2026, 9:15 pm", fields.timestampRaw)
        assertNull(fields.timestampEpochMs)
    }

    @Test
    fun `absent timestamp yields blank raw and null epoch`() {
        val fields = parse("₹500 paid to shop@okaxis")

        assertEquals("", fields.timestampRaw)
        assertNull(fields.timestampEpochMs)
    }

    // ---- app hint -------------------------------------------------------------------------

    @Test
    fun `app hint recognises the wallets`() {
        assertEquals(PaymentAppHint.GPAY, parse("Google Pay ₹500 paid").appHint)
        assertEquals(PaymentAppHint.GPAY, parse("GPay ₹500 paid").appHint)
        assertEquals(PaymentAppHint.PHONEPE, parse("PhonePe ₹500 paid").appHint)
        assertEquals(PaymentAppHint.PAYTM, parse("Paytm ₹500 paid").appHint)
        assertEquals(PaymentAppHint.BHIM, parse("BHIM ₹500 paid").appHint)
    }

    @Test
    fun `app hint recognises a bank app`() {
        assertEquals(PaymentAppHint.BANK_APP, parse("HDFC Bank ₹500 paid").appHint)
        assertEquals(PaymentAppHint.BANK_APP, parse("SBI ₹500 paid").appHint)
    }

    @Test
    fun `app hint is unknown for unrecognised chrome`() {
        assertEquals(PaymentAppHint.UNKNOWN, parse("SomeWallet ₹500 paid").appHint)
    }

    // ---- hostile and ambiguous OCR ---------------------------------------------------------

    @Test
    fun `currency marker embedded in ordinary words is not an amount`() {
        val fields = parse("cars 500, sir 900, INRish 700, shop@okaxis")

        assertNull(fields.amountMinor)
        assertEquals("", fields.amountRaw)
        assertEquals("shop@okaxis", fields.payeeUpi)
    }

    @Test
    fun `currency amount cannot be a prefix of an alphanumeric token`() {
        val fields = parse("₹500abc then ₹75")

        assertEquals(7500L, fields.amountMinor)
        assertEquals("₹75", fields.amountRaw)
    }

    @Test
    fun `malformed grouping is tolerated without consuming punctuation`()
        val fields = parse("₹1,2,,,@@ ₹500")

        assertEquals(50000L, fields.amountMinor)
        assertEquals("₹500", fields.amountRaw)
    }

    @Test
    fun `masked upi embedded beside a valid address cannot become a payee`() {
        val fields = parse("Paid to ab***cd@okaxis\\nSupport: help@trustshield.ai")

        assertEquals("", fields.payeeUpi)
    }

    @Test
    fun `phone-like and amount-like digit strings do not become utrs`() {
        val fields = parse("Call 9876543210; balance 1234567890123")

        assertEquals("", fields.utr)
        assertNull(fields.amountMinor)
    }

    @Test
    fun `currency followed by oversized number is skipped for later plausible amount`() {
        val fields = parse("₹12345678901234567890
Total ₹75")

        assertEquals(7500L, fields.amountMinor)
        assertEquals("₹75", fields.amountRaw)
    }

    @Test
    fun `text beyond inspection limit cannot supply a payment field`() {
        val fields = parse("x".repeat(PaymentFieldsParser.MAX_INPUT_CHARS) + " ₹500 shop@okaxis")

        assertNull(fields.amountMinor)
        assertEquals("", fields.payeeUpi)
    }

    // ---- not a payment screenshot ---------------------------------------------------------

    @Test
    fun `plain hinglish scam sms is not a payment screenshot`() {
        val text = "Sir aapka SBI account band ho jayega, abhi 9876543210 par call karo, OTP mat share karo"

        assertFalse(PaymentFieldsParser.looksLikePaymentScreenshot(text))
        assertNull(parse(text).amountMinor)
        assertEquals("", parse(text).utr)
    }

    @Test
    fun `empty input yields blank fields`() {
        val fields = parse("")

        assertNull(fields.amountMinor)
        assertEquals("", fields.amountRaw)
        assertEquals("", fields.payeeUpi)
        assertEquals("", fields.payeeName)
        assertEquals("", fields.utr)
        assertEquals(PaymentStatus.UNKNOWN, fields.status)
        assertEquals("", fields.statusRaw)
        assertEquals("", fields.timestampRaw)
        assertNull(fields.timestampEpochMs)
        assertEquals(PaymentAppHint.UNKNOWN, fields.appHint)
        assertFalse(fields.looksLikeReceipt)
        assertFalse(PaymentFieldsParser.looksLikePaymentScreenshot(""))
    }

    @Test
    fun `whitespace only input yields blank fields`() {
        val fields = parse("   \n\t \r\n  ")

        assertNull(fields.amountMinor)
        assertEquals("", fields.payeeUpi)
        assertEquals(PaymentStatus.UNKNOWN, fields.status)
        assertFalse(PaymentFieldsParser.looksLikePaymentScreenshot("   \n\t \r\n  "))
    }

    @Test
    fun `emoji only input yields blank fields`() {
        val fields = parse("🙏🙏🔥🔥💸😭")

        assertNull(fields.amountMinor)
        assertEquals("", fields.payeeUpi)
        assertEquals("", fields.payeeName)
        assertEquals(PaymentStatus.UNKNOWN, fields.status)
        assertFalse(PaymentFieldsParser.looksLikePaymentScreenshot("🙏🙏🔥🔥💸😭"))
    }

    @Test
    fun `oversized junk does not throw and stays fast`() {
        val junk = "₹@@@" + "x₹1,2,,,@@ 9 ".repeat(16_000) + "@@₹₹₹"
        assertTrue(junk.length > 200_000)

        val startedAt = System.nanoTime()
        val fields = parse(junk)
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L

        // The bound is deliberately loose: this asserts "bounded work", not a benchmark.
        assertTrue("parse of ${junk.length} chars took ${elapsedMs}ms", elapsedMs < 3_000L)
        assertEquals(PaymentStatus.UNKNOWN, fields.status)
        assertEquals("", fields.utr)
    }
}
