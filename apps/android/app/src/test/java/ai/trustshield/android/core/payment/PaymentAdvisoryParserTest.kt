package ai.trustshield.android.core.payment

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Adversarial boundaries for currency amounts and UPI identities. All values are synthetic. */
class PaymentAdvisoryParserTest {
    private data class AmountCase(
        val label: String,
        val source: String,
        val minor: Long?,
        val raw: String = ""
    )

    private data class UpiCase(val label: String, val value: String, val accepted: Boolean)

    // Keep exact source snippets here: leading/trailing token characters are part of the boundary.
    private val amountFixtures = listOf(
        AmountCase("two decimal digits", "₹1.09", 109L, "₹1.09"),
        AmountCase("one decimal digit", "INR 0.5", 50L, "INR 0.5"),
        AmountCase("fifteen whole digits", "Rs 999999999999999", 99_999_999_999_999_900L, "Rs 999999999999999"),
        AmountCase("sixteen whole digits over limit", "₹1000000000000000", null),
        AmountCase("three fractional digits", "₹8.001", null),
        AmountCase("trailing decimal point", "₹8.", null),
        AmountCase("numeric token suffix", "₹8x", null),
        AmountCase("decimal token suffix", "₹8.00x", null),
        AmountCase("second decimal separator", "₹8.0.1", null),
        AmountCase("comma immediately after amount", "₹8,", null),
        AmountCase("currency-like word interior", "acmeINR 8", null),
        AmountCase("currency word prefix", "RsExtra 8", null),
        AmountCase("ordinary number is not marked", "amount 8", null),
        AmountCase("zero", "₹0.00", null),
        AmountCase("accepted exact input end", "₹8.25", 825L, "₹8.25"),
    )

    private val upiFixtures = listOf(
        UpiCase("minimum two-character local part", "ab@x", true),
        UpiCase("maximum 255-character identifier", "a".repeat(253) + "@x", true),
        UpiCase("identifier one past maximum", "a".repeat(254) + "@x", false),
        UpiCase("one-character local part", "a@x", false),
        UpiCase("local starts with separator", ".ab@x", false),
        UpiCase("local ends with separator", "ab-@x", false),
        UpiCase("repeated local dot", "a..b@x", false),
        UpiCase("repeated at sign", "ab@@x", false),
        UpiCase("empty handle", "ab@", false),
        UpiCase("handle starts with hyphen", "ab@-x", false),
        UpiCase("handle ends with dot", "ab@x.", false),
        UpiCase("masked identifier", "ab***cd@x", false),
        UpiCase("spaces inside identifier", "ab @x", false),
        UpiCase("embedded in larger token", "zab@xq", false),
    )

    @Test
    fun `amount fixtures enforce exact token boundaries and malformed forms`() {
        amountFixtures.forEach { fixture ->
            val fields = PaymentFieldsParser.parse(fixture.source)
            assertEquals(fixture.label, fixture.minor, fields.amountMinor)
            assertEquals(fixture.label, fixture.raw, fields.amountRaw)
        }
    }

    @Test
    fun `amount boundary adjacent invalid candidate does not truncate to prefix`() {
        val fields = PaymentFieldsParser.parse("₹8.001 then ₹12.34")
        assertEquals(1234L, fields.amountMinor)
        assertEquals("₹12.34", fields.amountRaw)
    }

    @Test
    fun `upi fixtures enforce identifier length and malformed boundaries`() {
        upiFixtures.forEach { fixture ->
            val fields = PaymentFieldsParser.parse("Paid to ${fixture.value}")
            assertEquals(fixture.label, if (fixture.accepted) fixture.value else "", fields.payeeUpi)
        }
    }

    @Test
    fun `malformed cued upi cannot be replaced by later unrelated valid identity`() {
        val fields = PaymentFieldsParser.parse("Paid to ab..cd@x\nSupport: support@trustshield.ai")
        assertEquals("", fields.payeeUpi)
    }

    @Test
    fun `uri amount parameters are not ordinary currency amounts`() {
        val fields = PaymentFieldsParser.parse("upi://pay?pa=shop@okaxis&am=₹500&note=Rs%2012")
        assertNull(fields.amountMinor)
        assertEquals("shop@okaxis", fields.payeeUpi)
    }
}
