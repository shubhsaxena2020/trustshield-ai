package ai.trustshield.android.core.privacy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A07 gate: the redactor must mask the secret shapes N01 names, must not mangle ordinary prose,
 * must be idempotent and bounded, and must never throw.
 *
 * All values here are synthetic. No real phone number, card, VPA or OTP appears in this file.
 */
class RedactionTest {

    private fun assertMasked(output: String, vararg secrets: String) {
        for (secret in secrets) {
            assertFalse("secret '$secret' survived as: $output", output.contains(secret))
        }
        assertTrue("expected ${Redactor.MASK} in: $output", output.contains(Redactor.MASK))
    }

    private fun redacted(text: String): String = Redactor.redact(text)

    // ---- contract surface ------------------------------------------------------------------

    @Test
    fun `mask constant is pinned`() {
        assertEquals("[REDACTED]", Redactor.MASK)
    }

    // ---- OTP / PIN / CVV / password --------------------------------------------------------

    @Test
    fun `otp after its keyword is masked`() {
        val out = redacted("Your OTP is 482913, do not share it.")
        assertMasked(out, "482913")
        assertTrue(out.startsWith("Your OTP is "))
        assertTrue(out.contains("do not share it."))
    }

    @Test
    fun `otp before its keyword is masked`() {
        val out = redacted("482913 is the OTP for your login")
        assertMasked(out, "482913")
        assertTrue(out.contains("is the OTP for your login"))
    }

    @Test
    fun `three digit cvv after its keyword is masked`() {
        val out = redacted("the CVV is 445 on my card")
        assertMasked(out, "445")
    }

    @Test
    fun `upi pin is masked`() {
        val out = redacted("UPI PIN 1234 mat batana")
        assertMasked(out, "1234")
        assertTrue(out.contains("mat batana"))
    }

    @Test
    fun `alphanumeric password value is masked`() {
        val out = redacted("password: tiger99")
        assertMasked(out, "tiger99")
    }

    @Test
    fun `password keyword without a value is not mangled`() {
        val text = "Never share your password with anyone"
        assertEquals(text, redacted(text))
    }

    @Test
    fun `devanagari digits after an otp keyword are masked`() {
        val out = redacted("आपका OTP १२३४५६ है, किसी को न बताएं")
        assertMasked(out, "१२३४५६")
        assertTrue(out.contains("किसी को न बताएं"))
    }

    // ---- cards / phone / UPI / email / account --------------------------------------------

    @Test
    fun `card number with a valid luhn check is masked`() {
        // Synthetic test PAN: 4111 1111 1111 1111 passes Luhn.
        val out = redacted("card number 4111111111111111 was charged")
        assertMasked(out, "4111111111111111", "4111")
    }

    @Test
    fun `grouped card number is masked`() {
        val out = redacted("Card: 4111 1111 1111 1111 exp 09/28")
        assertMasked(out, "4111 1111 1111 1111")
        assertTrue(out.contains("exp 09/28"))
    }

    @Test
    fun `ten digit indian phone number is masked`() {
        val out = redacted("call me on 9876543210 now")
        assertMasked(out, "9876543210")
        assertTrue(out.contains("call me on "))
    }

    @Test
    fun `phone number with country code and separators is masked`() {
        val out = redacted("reach me at +91 98765 43210 or 0-91234-56789")
        assertMasked(out, "98765 43210", "91234-56789", "43210")
    }

    @Test
    fun `upi vpa is masked`() {
        val out = redacted("pay to ramesh.kumar@okhdfcbank please")
        assertMasked(out, "ramesh.kumar@okhdfcbank", "okhdfcbank")
        assertTrue(out.contains("pay to "))
    }

    @Test
    fun `email address is masked`() {
        val out = redacted("mail me at someone.test@example.com today")
        assertMasked(out, "someone.test@example.com", "example.com")
    }

    @Test
    fun `account number after a-c is masked`() {
        val out = redacted("Amount debited from A/c 123456789012, IFSC HDFC0001234")
        assertMasked(out, "123456789012")
        assertTrue(out.contains("Amount debited from A/c "))
    }

    @Test
    fun `ungrouped thirteen digit run failing luhn is left alone`() {
        // Documented limit: a bare 13-14 digit run with no separators and no Luhn checksum is not
        // treated as a card; label it (`A/c ...`) so the account pass catches it.
        val out = redacted("ref 1234567890123")
        assertEquals("ref 1234567890123", out)
    }

    // ---- false positives -------------------------------------------------------------------

    @Test
    fun `ordinary amount and count text is not over masked`() {
        val text = "order 1234 of ₹500 will arrive on 7 October 2026 with 2 items"
        assertEquals(text, redacted(text))
    }

    @Test
    fun `plain hinglish sentence without secrets is unchanged`() {
        val text = "Sir aapka courier kal aayega, please ghar par rahiye."
        assertEquals(text, redacted(text))
    }

    // ---- idempotency -----------------------------------------------------------------------

    @Test
    fun `redaction is idempotent on mixed text`() {
        val text = "OTP 482913, card 4111111111111111, call 9876543210, pay ramesh@okaxis"
        val once = redacted(text)
        val twice = redacted(once)
        assertEquals(once, twice)
        assertEquals(once, redacted(twice))
    }

    // ---- JSON ------------------------------------------------------------------------------

    @Test
    fun `json values under otp and pin keys are masked`() {
        val out = Redactor.redactJson("""{"otp": "482913", "pin": "1234"}""")
        assertMasked(out, "482913", "1234")
        assertTrue(out.contains(""""otp": "[REDACTED]""""))
    }

    @Test
    fun `json values under card phone and upi keys are masked`() {
        val json = """{"card_number": "4111 1111 1111 1111", "phone": "9876543210", "upi_id": "ramesh@okaxis"}"""
        val out = Redactor.redactJson(json)
        assertMasked(out, "4111 1111 1111 1111", "9876543210", "ramesh@okaxis")
    }

    @Test
    fun `json keeps non sensitive fields intact`() {
        val json = """{"analysis_id": "abc", "risk_score": 82, "risk_label": "high_risk", "otp": "482913"}"""
        val out = Redactor.redactJson(json)
        assertMasked(out, "482913")
        assertTrue(out.contains(""""risk_score": 82"""))
        assertTrue(out.contains("high_risk"))
        assertTrue(out.contains(""""analysis_id": "abc"""))
    }

    @Test
    fun `json redaction is idempotent`() {
        val json = """{"otp": "482913", "risk_score": 82, "raw_text": "OTP 482913"}"""
        val once = Redactor.redactJson(json)
        assertEquals(once, Redactor.redactJson(once))
    }

    @Test
    fun `json tolerates null and truncated payloads`() {
        val out = Redactor.redactJson("""{"otp": null, "pin": "1234"""")
        assertFalse("null handling leaked a pin: $out", out.contains("1234"))
        assertTrue(out.contains("null"))
    }

    @Test
    fun `ocr raw text inside a json payload is masked`() {
        val json = """{"modules": {"ocr": {"data": {"raw_text": "Dear customer, your OTP is 482913. Card 4111 1111 1111 1111 charged. Call 9876543210. Pay ramesh.k@okhdfcbank"}}}}"""
        val out = Redactor.redactJson(json)
        assertMasked(out, "482913", "4111 1111 1111 1111", "9876543210", "ramesh.k@okhdfcbank")
        assertTrue(out.contains("Dear customer"))
        assertTrue(out.contains(""""raw_text""""))
    }

    // ---- key-name sensitivity --------------------------------------------------------------

    @Test
    fun `known sensitive and non sensitive key names are classified`() {
        for (key in listOf(
            "otp", "OTP", "pin", "mpin", "cvv", "cvc", "card_number", "phoneNumber", "upi_id",
            "email", "account_no", "user_otp", "apiKey", "vpa", "ifsc", "aadhaar",
        )) {
            assertTrue("expected '$key' to be sensitive", Redactor.isSensitive(key))
        }
        for (key in listOf(
            "shipping", "microphone", "risk_score", "risk_label", "input_summary", "analysis_id",
            "user_id", "raw_text", "confidence", "stage", "",
        )) {
            assertFalse("expected '$key' not to be sensitive", Redactor.isSensitive(key))
        }
    }

    // ---- robustness ------------------------------------------------------------------------

    @Test
    fun `empty and whitespace input is returned unchanged`() {
        assertEquals("", Redactor.redact(""))
        assertEquals("", Redactor.redactJson(""))
        assertEquals("   ", Redactor.redact("   "))
        assertEquals("\n\t", Redactor.redact("\n\t"))
    }

    @Test
    fun `hostile input never throws`() {
        val hostile = listOf(
            "@@@@@@@@", "1@2@3@4@5@6", "\"key\": ", "१२३४५६७८९०", "PIN", "otp ",
            "[[[{{{{", "\\u0041\\u0042", "card" + " ".repeat(1000), "9".repeat(500),
            "a\u0000b", "₹".repeat(500), "-".repeat(300) + "1",
        )
        for (input in hostile) {
            Redactor.redact(input)
            Redactor.redactJson(input)
        }
    }

    @Test
    fun `twenty thousand character input is bounded and fast`() {
        val line = "Aapka OTP 482913 hai, card 4111 1111 1111 1111, phone 9876543210, pay ramesh@okaxis. "
        val builder = StringBuilder()
        while (builder.length < 20_000) builder.append(line)
        val input = builder.substring(0, 20_000)

        val startedAt = System.nanoTime()
        val out = redacted(input)
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000

        assertFalse("otp survived: length=${out.length}", out.contains("482913"))
        assertFalse("card survived", out.contains("4111 1111 1111 1111"))
        assertFalse("phone survived", out.contains("9876543210"))
        // Every masked span replaces >= 4 source characters with an 10-character placeholder, so a
        // bounded input can only grow by a small constant factor. Anything near 10x is a bug.
        assertTrue("output grew to ${out.length} from ${input.length}", out.length <= 5 * input.length)
        assertTrue("redaction of 20k chars took ${elapsedMs}ms", elapsedMs < 2_000)
    }
}
