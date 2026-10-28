package ai.trustshield.android.input.qr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parity and safety tests for [UpiQrParser].
 *
 * Every test names the backend behaviour it locks down:
 * `classify_payload` = `services/analysis/extractors/qr.py`, `_parse_upi` = `services/analysis/pipeline.py`.
 * Payloads are DATA: nothing here resolves, fetches or opens a payload, and no test asserts on I/O
 * because the parser performs none.
 */
class UpiQrParserTest {

    // ---------------------------------------------------------------- classification parity

    @Test
    fun `classify upi scheme case insensitively like backend`() {
        assertEquals(QrPayloadKind.UPI, UpiQrParser.classify("upi://pay?pa=ravi@okaxis"))
        assertEquals(QrPayloadKind.UPI, UpiQrParser.classify("UPI://PAY?pa=ravi@okaxis"))
    }

    @Test
    fun `classify web javascript and data payloads as url`() {
        // classify_payload: `^[scheme]://` or `^(javascript|data):` -> "url"
        assertEquals(QrPayloadKind.URL, UpiQrParser.classify("https://example.com/kyc"))
        assertEquals(QrPayloadKind.URL, UpiQrParser.classify("http://sbi-kyc.xyz/login"))
        assertEquals(QrPayloadKind.URL, UpiQrParser.classify("ftp://files.example.com"))
        assertEquals(QrPayloadKind.URL, UpiQrParser.classify("javascript:alert(1)"))
        assertEquals(QrPayloadKind.URL, UpiQrParser.classify("JavaScript:alert(1)"))
        assertEquals(QrPayloadKind.URL, UpiQrParser.classify("data:text/html;base64,QUJD"))
    }

    @Test
    fun `classify tel mailto sms geo wifi and vcard payloads as other`() {
        assertEquals(QrPayloadKind.OTHER, UpiQrParser.classify("tel:+919876543210"))
        assertEquals(QrPayloadKind.OTHER, UpiQrParser.classify("mailto:help@example.com"))
        assertEquals(QrPayloadKind.OTHER, UpiQrParser.classify("sms:+919876543210"))
        assertEquals(QrPayloadKind.OTHER, UpiQrParser.classify("smsto:12345"))
        assertEquals(QrPayloadKind.OTHER, UpiQrParser.classify("geo:19.07,72.87"))
        assertEquals(QrPayloadKind.OTHER, UpiQrParser.classify("wifi:S:Home;T:WPA;P:secret;;"))
        assertEquals(QrPayloadKind.OTHER, UpiQrParser.classify("BEGIN:VCARD\nFN:Ram\nEND:VCARD"))
        assertEquals(QrPayloadKind.OTHER, UpiQrParser.classify("begin:vcard\nFN:Ram"))
    }

    @Test
    fun `classify plain text empty and null like backend`() {
        // backend: `raw = (payload or "").strip()`
        assertEquals(QrPayloadKind.TEXT, UpiQrParser.classify("Samne se OTP batao"))
        assertEquals(QrPayloadKind.TEXT, UpiQrParser.classify(""))
        assertEquals(QrPayloadKind.TEXT, UpiQrParser.classify("   "))
        assertEquals(QrPayloadKind.TEXT, UpiQrParser.classify(null))
        // `upi:pay` has no `//`, so the backend does not treat it as a UPI payload.
        assertEquals(QrPayloadKind.TEXT, UpiQrParser.classify("upi:pay?pa=ravi@okaxis"))
        // Extra leading whitespace is stripped before the scheme test.
        assertEquals(QrPayloadKind.UPI, UpiQrParser.classify("  upi://pay?pa=ravi@okaxis"))
    }

    @Test
    fun `kind wire names and flag evidence categories match backend strings`() {
        assertEquals("upi", QrPayloadKind.UPI.wire)
        assertEquals("url", QrPayloadKind.URL.wire)
        assertEquals("other", QrPayloadKind.OTHER.wire)
        assertEquals("text", QrPayloadKind.TEXT.wire)
        assertEquals("qr_payment_request", UpiFlag.PAYMENT_REQUEST.evidenceCategory)
        assertEquals("qr_malformed_fields", UpiFlag.OVERSIZED_FIELDS.evidenceCategory)
        assertEquals("qr_missing_payee", UpiFlag.MISSING_PAYEE.evidenceCategory)
        assertEquals("qr_malformed_payee", UpiFlag.MALFORMED_PAYEE.evidenceCategory)
        assertEquals("qr_prefilled_amount", UpiFlag.PREFILLED_AMOUNT.evidenceCategory)
        assertEquals("qr_malformed_amount", UpiFlag.MALFORMED_AMOUNT.evidenceCategory)
        assertEquals("qr_collect_mode", UpiFlag.COLLECT_MODE.evidenceCategory)
        // Android-only bound: no backend category, and that is deliberate.
        assertNull(UpiFlag.TRUNCATED_PAYLOAD.evidenceCategory)
    }

    // ---------------------------------------------------------------- field selection parity

    @Test
    fun `payee only payload yields vpa and payment request flag`() {
        val parsed = UpiQrParser.parse("upi://pay?pa=ravi.kumar@okaxis")
        assertEquals(QrPayloadKind.UPI, parsed.kind)
        assertTrue(parsed.isUpi)
        assertEquals("ravi.kumar@okaxis", parsed.payeeVpa)
        assertNull(parsed.payeeName)
        assertNull(parsed.amount)
        assertNull(parsed.currency)
        assertNull(parsed.note)
        assertNull(parsed.merchantCode)
        assertNull(parsed.mode)
        assertEquals(listOf(UpiFlag.PAYMENT_REQUEST), parsed.flags.toList())
    }

    @Test
    fun `pa pn am payload yields all three fields and prefilled amount flag`() {
        val parsed = UpiQrParser.parse("upi://pay?pa=shop@okhdfcbank&pn=Ram%20Kirana&am=250.00&cu=INR")
        assertEquals("shop@okhdfcbank", parsed.payeeVpa)
        assertEquals("Ram Kirana", parsed.payeeName)
        assertEquals("250.00", parsed.amount)
        assertEquals("INR", parsed.currency)
        assertEquals(
            listOf(UpiFlag.PAYMENT_REQUEST, UpiFlag.PREFILLED_AMOUNT),
            parsed.flags.toList(),
        )
    }

    @Test
    fun `percent encoded payee name decodes plus signs spaces and unicode`() {
        // `+` is a space for parse_qs (unquote_plus); multi-byte UTF-8 is decoded once.
        val parsed = UpiQrParser.parse(
            "upi://pay?pa=shop@okaxis&pn=Ram+Kirana+%E0%A4%A6%E0%A5%81%E0%A4%95%E0%A4%BE%E0%A4%A8",
        )
        assertEquals("Ram Kirana \u0926\u0941\u0915\u093E\u0928", parsed.payeeName)
        // %20 form agrees with the `+` form.
        val spaced = UpiQrParser.parse("upi://pay?pa=shop@okaxis&pn=Ram%20Kirana")
        assertEquals("Ram Kirana", spaced.payeeName)
    }

    @Test
    fun `duplicate parameters keep the last value like parse_qs`() {
        // Backend: `{key.lower(): values[-1] ...}`
        val parsed = UpiQrParser.parse(
            "upi://pay?pa=first@okaxis&pa=second@okaxis&pn=First&pn=Second&am=1&am=2",
        )
        assertEquals("second@okaxis", parsed.payeeVpa)
        assertEquals("Second", parsed.payeeName)
        assertEquals("2", parsed.amount)
    }

    @Test
    fun `duplicate payee parameter is marked ambiguous without changing selected value`() {
        val parsed = UpiQrParser.parse("upi://pay?pa=attacker@ybl&pa=shop@okaxis&cu=INR")

        assertEquals("shop@okaxis", parsed.payeeVpa)
        assertTrue(parsed.hasFlag(UpiFlag.AMBIGUOUS_PAYEE))
    }

    @Test
    fun `duplicate parameter whose last value is blank clears the field`() {
        // keep_blank_values=True: the last value wins even when it is empty.
        val parsed = UpiQrParser.parse("upi://pay?pa=ravi@okaxis&pa=")
        assertEquals("", parsed.payeeVpa)
        assertEquals(
            listOf(UpiFlag.PAYMENT_REQUEST, UpiFlag.AMBIGUOUS_PAYEE, UpiFlag.MISSING_PAYEE),
            parsed.flags.toList(),
        )
    }

    @Test
    fun `present but blank parameter stays empty string while absent stays null`() {
        // Backend keeps '' for `pn=` and None for a missing `pn`; the Android contract mirrors that.
        val blank = UpiQrParser.parse("upi://pay?pa=ravi@okaxis&pn=&tn=")
        assertEquals("", blank.payeeName)
        assertEquals("", blank.note)
        val absent = UpiQrParser.parse("upi://pay?pa=ravi@okaxis")
        assertNull(absent.payeeName)
        assertNull(absent.note)
    }

    @Test
    fun `uppercase parameter names are looked up lowercased`() {
        // Backend lowercases decoded keys before the lookup.
        val parsed = UpiQrParser.parse("upi://pay?PA=Ravi@Okaxis&PN=Shop&AM=10&MODE=04")
        assertEquals("Ravi@Okaxis", parsed.payeeVpa)
        assertEquals("Shop", parsed.payeeName)
        assertEquals("10", parsed.amount)
        assertEquals("04", parsed.mode)
        assertTrue(parsed.hasFlag(UpiFlag.COLLECT_MODE))
    }

    @Test
    fun `percent encoded parameter name is decoded before lookup`() {
        val parsed = UpiQrParser.parse("upi://pay?%70a=ravi@okaxis&p%6E=Shop")
        assertEquals("ravi@okaxis", parsed.payeeVpa)
        assertEquals("Shop", parsed.payeeName)
    }

    @Test
    fun `parameters beyond the fragment are ignored`() {
        // urlsplit puts everything after `#` into the fragment, so its query does not exist.
        val parsed = UpiQrParser.parse("upi://pay#frag?pa=evil@okaxis")
        assertNull(parsed.payeeVpa)
        assertTrue(parsed.hasFlag(UpiFlag.MISSING_PAYEE))
    }

    @Test
    fun `tabs and newlines inside the payload are removed like urlsplit`() {
        val parsed = UpiQrParser.parse("upi://pay?pa=ravi@\tokaxis\n&pn=Shop\r")
        assertEquals("ravi@okaxis", parsed.payeeVpa)
        assertEquals("Shop", parsed.payeeName)
    }

    @Test
    fun `parameter without equals sign keeps a blank value`() {
        // parse_qs(keep_blank_values=True) appends '' for a bare control name.
        val parsed = UpiQrParser.parse("upi://pay?pa&pn=Shop")
        assertEquals("", parsed.payeeVpa)
        assertTrue(parsed.hasFlag(UpiFlag.MISSING_PAYEE))
        assertEquals("Shop", parsed.payeeName)
    }

    // ---------------------------------------------------------------- payee VPA

    @Test
    fun `missing pa is flagged not fatal`() {
        val parsed = UpiQrParser.parse("upi://pay?pn=Ram%20Kirana&am=250")
        assertNull(parsed.payeeVpa)
        assertEquals("Ram Kirana", parsed.payeeName)
        assertEquals(
            listOf(UpiFlag.PAYMENT_REQUEST, UpiFlag.MISSING_PAYEE, UpiFlag.PREFILLED_AMOUNT),
            parsed.flags.toList(),
        )
        val noQuery = UpiQrParser.parse("upi://pay")
        assertNull(noQuery.payeeVpa)
        assertEquals(listOf(UpiFlag.PAYMENT_REQUEST, UpiFlag.MISSING_PAYEE), noQuery.flags.toList())
    }

    @Test
    fun `malformed pa without at sign is flagged and value survives`() {
        val parsed = UpiQrParser.parse("upi://pay?pa=raviokaxis")
        assertEquals("raviokaxis", parsed.payeeVpa)
        assertTrue(parsed.hasFlag(UpiFlag.MALFORMED_PAYEE))
    }

    @Test
    fun `malformed pa shapes are all flagged`() {
        // Backend regex `^[A-Za-z0-9._-]{2,256}@[A-Za-z0-9.-]{2,64}$`
        val cases = listOf(
            "upi://pay?pa=@okaxis",            // empty local part
            "upi://pay?pa=ravi@",              // empty handle
            "upi://pay?pa=r@o",                // both parts under two chars
            "upi://pay?pa=ravi@@okaxis",       // extra at sign
            "upi://pay?pa=ravi@okaxis/../x",   // slash outside the allowed class
            "upi://pay?pa=ravi kumar@okaxis",  // space outside the allowed class
        )
        for (payload in cases) {
            assertTrue("expected MALFORMED_PAYEE for $payload", UpiQrParser.parse(payload).hasFlag(UpiFlag.MALFORMED_PAYEE))
        }
    }

    @Test
    fun `control characters in pa are flagged and stripped from the stored value`() {
        val parsed = UpiQrParser.parse("upi://pay?pa=ravi%00@okaxis")
        // Raw value fails the pattern, so the flag is set even though the stored copy looks clean.
        assertTrue(parsed.hasFlag(UpiFlag.MALFORMED_PAYEE))
        assertEquals("ravi@okaxis", parsed.payeeVpa)
        assertFalse(parsed.payeeVpa!!.any { it.code < 0x20 })
    }

    @Test
    fun `oversized pa is flagged and truncated to 320 chars like backend`() {
        // `pa` is not in field_limits, so it is never nulled: the backend truncates to 320 and flags.
        val longLocal = "a".repeat(400)
        val parsed = UpiQrParser.parse("upi://pay?pa=$longLocal@okaxis")
        assertTrue(parsed.hasFlag(UpiFlag.MALFORMED_PAYEE))
        assertFalse(parsed.hasFlag(UpiFlag.OVERSIZED_FIELDS))
        assertEquals(320, parsed.payeeVpa!!.length)
        assertTrue(parsed.oversizedFields.isEmpty())
    }

    // ---------------------------------------------------------------- amount

    @Test
    fun `amount zero is prefilled not malformed`() {
        // Backend accepts 0: only `< 0` and `> 100000000` are rejected.
        val parsed = UpiQrParser.parse("upi://pay?pa=ravi@okaxis&am=0")
        assertTrue(parsed.hasFlag(UpiFlag.PREFILLED_AMOUNT))
        assertFalse(parsed.hasFlag(UpiFlag.MALFORMED_AMOUNT))
    }

    @Test
    fun `amount bounds are inclusive at one hundred million`() {
        assertTrue(UpiQrParser.parse("upi://pay?pa=ravi@okaxis&am=100000000").hasFlag(UpiFlag.PREFILLED_AMOUNT))
        assertTrue(UpiQrParser.parse("upi://pay?pa=ravi@okaxis&am=100000001").hasFlag(UpiFlag.MALFORMED_AMOUNT))
        assertTrue(UpiQrParser.parse("upi://pay?pa=ravi@okaxis&am=1e9").hasFlag(UpiFlag.MALFORMED_AMOUNT))
        assertTrue(UpiQrParser.parse("upi://pay?pa=ravi@okaxis&am=-5").hasFlag(UpiFlag.MALFORMED_AMOUNT))
        // Decimal('-0') is not less than zero, so the backend treats it as a pre-filled amount.
        assertTrue(UpiQrParser.parse("upi://pay?pa=ravi@okaxis&am=-0").hasFlag(UpiFlag.PREFILLED_AMOUNT))
        assertTrue(UpiQrParser.parse("upi://pay?pa=ravi@okaxis&am=.5").hasFlag(UpiFlag.PREFILLED_AMOUNT))
    }

    @Test
    fun `non numeric amount is flagged malformed`() {
        val parsed = UpiQrParser.parse("upi://pay?pa=ravi@okaxis&am=abc")
        assertEquals("abc", parsed.amount)
        assertTrue(parsed.hasFlag(UpiFlag.MALFORMED_AMOUNT))
        assertFalse(parsed.hasFlag(UpiFlag.PREFILLED_AMOUNT))
        for (bad in listOf("NaN", "Infinity", "12,50", "1_000", "\u0967\u096B", "Rs 250")) {
            val step = UpiQrParser.parse("upi://pay?pa=ravi@okaxis&am=$bad")
            assertTrue("expected MALFORMED_AMOUNT for am=$bad", step.hasFlag(UpiFlag.MALFORMED_AMOUNT))
        }
    }

    @Test
    fun `blank amount produces no amount flag at all`() {
        // Backend guards with `if selected["amount"]:` so an empty string is skipped entirely.
        val parsed = UpiQrParser.parse("upi://pay?pa=ravi@okaxis&am=")
        assertEquals("", parsed.amount)
        assertFalse(parsed.hasFlag(UpiFlag.PREFILLED_AMOUNT))
        assertFalse(parsed.hasFlag(UpiFlag.MALFORMED_AMOUNT))
        assertEquals(listOf(UpiFlag.PAYMENT_REQUEST), parsed.flags.toList())
    }

    // ---------------------------------------------------------------- collect mode

    @Test
    fun `collect mode variants raise collect flag`() {
        for (mode in listOf("04", "collect", "COLLECT", "collect-request", "Collect-Request")) {
            val parsed = UpiQrParser.parse("upi://pay?pa=ravi@okaxis&mode=$mode")
            assertTrue("expected COLLECT_MODE for mode=$mode", parsed.hasFlag(UpiFlag.COLLECT_MODE))
        }
        assertFalse(UpiQrParser.parse("upi://pay?pa=ravi@okaxis&mode=05").hasFlag(UpiFlag.COLLECT_MODE))
        assertFalse(UpiQrParser.parse("upi://pay?pa=ravi@okaxis").hasFlag(UpiFlag.COLLECT_MODE))
    }

    // ---------------------------------------------------------------- oversized fields

    @Test
    fun `oversized payee name is nulled and flagged in backend order`() {
        val longName = "N".repeat(250) // limit 200
        val parsed = UpiQrParser.parse("upi://pay?pa=ravi@okaxis&pn=$longName&tn=note&am=" + "9".repeat(40))
        assertNull(parsed.payeeName)
        assertNull(parsed.amount)
        assertEquals("note", parsed.note)
        assertEquals(setOf("payee_name", "amount"), parsed.oversizedFields)
        assertTrue(parsed.hasFlag(UpiFlag.OVERSIZED_FIELDS))
        assertTrue(parsed.flags.contains(UpiFlag.OVERSIZED_FIELDS))
        // Oversized fields are nulled before the amount check, so no amount flag appears.
        assertFalse(parsed.hasFlag(UpiFlag.MALFORMED_AMOUNT))
        assertFalse(parsed.hasFlag(UpiFlag.PREFILLED_AMOUNT))
    }

    @Test
    fun `oversized fields are reported in field_limits declaration order`() {
        val parsed = UpiQrParser.parse(
            "upi://pay?pa=ravi@okaxis&pn=${"N".repeat(201)}&cu=${"I".repeat(9)}&tn=${"t".repeat(501)}&mc=${"m".repeat(17)}&mode=${"0".repeat(33)}",
        )
        assertEquals(listOf("payee_name", "currency", "note", "merchant_code", "mode"), parsed.oversizedFields.toList())
    }

    @Test
    fun `note at exactly the limit is kept`() {
        val note = "t".repeat(500)
        val parsed = UpiQrParser.parse("upi://pay?pa=ravi@okaxis&tn=$note")
        assertEquals(500, parsed.note!!.length)
        assertFalse(parsed.hasFlag(UpiFlag.OVERSIZED_FIELDS))
    }

    @Test
    fun `control and format characters are stripped before the value is stored`() {
        // Backend `_strip_controls` drops every category-C code point (Cc, Cf, Cn, Co, Cs).
        val parsed = UpiQrParser.parse("upi://pay?pa=ravi@okaxis&tn=A%00B%E2%80%8BC")
        assertEquals("ABC", parsed.note)
        // A surrogate pair (emoji) is outside category C and must survive the strip.
        val emoji = UpiQrParser.parse("upi://pay?pa=ravi@okaxis&tn=Pay%20%F0%9F%98%80")
        assertEquals("Pay \uD83D\uDE00", emoji.note)
    }

    // ---------------------------------------------------------------- non-UPI payloads are never parsed

    @Test
    fun `https payload is classified url and carries no upi fields`() {
        val parsed = UpiQrParser.parse("https://example.com/pay?pa=ravi@okaxis&am=250")
        assertEquals(QrPayloadKind.URL, parsed.kind)
        assertFalse(parsed.isUpi)
        assertNull(parsed.payeeVpa)
        assertNull(parsed.amount)
        assertTrue(parsed.flags.isEmpty())
        assertNull(parsed.oversizedFields.firstOrNull())
    }

    @Test
    fun `javascript tel and text payloads carry no upi fields`() {
        val script = UpiQrParser.parse("javascript:alert(document.cookie)")
        assertEquals(QrPayloadKind.URL, script.kind)
        assertNull(script.payeeVpa)
        val phone = UpiQrParser.parse("tel:+919876543210")
        assertEquals(QrPayloadKind.OTHER, phone.kind)
        assertNull(phone.payeeVpa)
        val text = UpiQrParser.parse("payment zaroor karein")
        assertEquals(QrPayloadKind.TEXT, text.kind)
        assertTrue(text.flags.isEmpty())
        // The backend only prefix-tests `upi://`, so this is "upi" with no `pa` — parity, not a bug.
        val nearMiss = UpiQrParser.parse("upi://pay zaroor karein")
        assertEquals(QrPayloadKind.UPI, nearMiss.kind)
        assertNull(nearMiss.payeeVpa)
        assertEquals(listOf(UpiFlag.PAYMENT_REQUEST, UpiFlag.MISSING_PAYEE), nearMiss.flags.toList())
    }

    @Test
    fun `empty string and null payloads classify text without flags`() {
        for (payload in listOf("", "   ", null)) {
            val parsed = UpiQrParser.parse(payload)
            assertEquals(QrPayloadKind.TEXT, parsed.kind)
            assertTrue(parsed.flags.isEmpty())
            assertNull(parsed.payeeVpa)
        }
    }

    // ---------------------------------------------------------------- bounds

    @Test
    fun `hundred thousand character payload is bounded and flagged`() {
        val payload = "upi://pay?pa=ravi@okaxis&pn=" + "x".repeat(100_000)
        val started = System.nanoTime()
        val parsed = UpiQrParser.parse(payload)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertEquals("ravi@okaxis", parsed.payeeVpa)
        assertNull(parsed.payeeName)
        assertEquals(setOf("payee_name"), parsed.oversizedFields)
        assertTrue(parsed.hasFlag(UpiFlag.TRUNCATED_PAYLOAD))
        assertTrue(parsed.hasFlag(UpiFlag.OVERSIZED_FIELDS))
        assertTrue("parse took ${elapsedMs}ms, expected well under 2000ms", elapsedMs < 2_000)
    }

    @Test
    fun `hundred thousand characters of query pairs stay bounded`() {
        val payload = "upi://pay?" + "pa=ravi@okaxis&".repeat(7_000)
        val started = System.nanoTime()
        val parsed = UpiQrParser.parse(payload)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        // The window may cut the final value, so only the shape is asserted, not the exact tail.
        assertTrue(parsed.payeeVpa!!.isNotEmpty())
        assertTrue("ravi@okaxis".startsWith(parsed.payeeVpa!!))
        assertTrue(parsed.hasFlag(UpiFlag.TRUNCATED_PAYLOAD))
        assertTrue("parse took ${elapsedMs}ms, expected well under 2000ms", elapsedMs < 2_000)
    }

    @Test
    fun `stray percent escapes stay literal and invalid utf8 is replaced`() {
        val stray = UpiQrParser.parse("upi://pay?pa=ravi@okaxis&tn=100%zz")
        assertEquals("100%zz", stray.note)
        val invalid = UpiQrParser.parse("upi://pay?pa=ravi@okaxis&tn=%E0%A4")
        assertEquals("\uFFFD", invalid.note)
    }

    // ---------------------------------------------------------------- masking and logging

    @Test
    fun `masked vpa keeps handle and hides the local part`() {
        val parsed = UpiQrParser.parse("upi://pay?pa=ravi.kumar@okaxis")
        assertEquals("ra***@okaxis", parsed.maskedPayeeVpa())
        assertNotEquals(parsed.payeeVpa, parsed.maskedPayeeVpa())
        assertFalse(parsed.maskedPayeeVpa()!!.contains("ravi.kumar"))
        assertEquals("ra***@okaxis", UpiQrParser.maskVpa("ravi.kumar@okaxis"))
    }

    @Test
    fun `mask handles short local parts and non vpa strings`() {
        assertEquals("r***@okaxis", UpiQrParser.maskVpa("ra@okaxis"))
        assertEquals("***@okaxis", UpiQrParser.maskVpa("a@okaxis"))
        assertEquals("no***", UpiQrParser.maskVpa("notavpa"))
        assertNull(UpiQrParser.maskVpa(null))
        assertNull(UpiQrParser.maskVpa(""))
        assertNull(UpiQrParser.maskVpa("   "))
        // Never reproduces the complete local part, so the VPA is not recoverable from a log line.
        val vpa = "ravi@okaxis"
        assertFalse(UpiQrParser.maskVpa(vpa)!!.contains("ravi"))
    }

    @Test
    fun `toString leaks no raw content`() {
        val parsed = UpiQrParser.parse(
            "upi://pay?pa=ravi.kumar@okaxis&pn=Ram%20Kirana&am=250&tn=Secrete%20note",
        )
        val rendered = parsed.toString()
        assertTrue(rendered.contains("ra***@okaxis"))
        assertFalse(rendered.contains("ravi.kumar"))
        assertFalse(rendered.contains("Ram"))
        assertFalse(rendered.contains("Secrete"))
        assertFalse(rendered.contains("250"))
        assertTrue(rendered.contains("10ch")) // payeeName length only
    }

    // ------------------------------------------------ backend parity fixtures (generated)

    /**
     * One generated row per payload: [kind] is `classify_payload`, [fields] is the `_parse_upi`
     * dict in `payee_vpa, payee_name, amount, currency, note, merchant_code, mode` order and
     * [flags] are its evidence categories in the order the backend emits them.
     *
     * Produced by running the real Python functions on Python 3.11.15 (the functions are standard
     * library only; the image/ML imports of `pipeline.py` were stubbed, nothing was fetched).
     * Regenerate rather than hand-edit.
     */
    private data class ParityRow(
        val payload: String,
        val kind: String,
        val fields: List<String?>,
        val flags: List<String>,
    )

    private val BACKEND_PARITY: List<ParityRow> = listOf(
        ParityRow(
            payload = "upi://pay?pa=ravi.kumar@okaxis",
            kind = "upi",
            fields = listOf("ravi.kumar@okaxis", null, null, null, null, null, null),
            flags = listOf("qr_payment_request"),
        ),
        ParityRow(
            payload = "upi://pay?pa=shop@okhdfcbank&pn=Ram%20Kirana&am=250.00&cu=INR",
            kind = "upi",
            fields = listOf("shop@okhdfcbank", "Ram Kirana", "250.00", "INR", null, null, null),
            flags = listOf("qr_payment_request", "qr_prefilled_amount"),
        ),
        ParityRow(
            payload = "upi://pay?pa=shop@okaxis&pn=Ram+Kirana+%E0%A4%A6%E0%A5%81%E0%A4%95%E0%A4%BE%E0%A4%A8",
            kind = "upi",
            fields = listOf("shop@okaxis", "Ram Kirana \u0926\u0941\u0915\u093e\u0928", null, null, null, null, null),
            flags = listOf("qr_payment_request"),
        ),
        ParityRow(
            payload = "upi://pay?pa=first@okaxis&pa=second@okaxis&pn=First&pn=Second&am=1&am=2",
            kind = "upi",
            fields = listOf("second@okaxis", "Second", "2", null, null, null, null),
            flags = listOf("qr_payment_request", "qr_prefilled_amount"),
        ),
        ParityRow(
            payload = "upi://pay?pa=ravi@okaxis&pa=",
            kind = "upi",
            fields = listOf("", null, null, null, null, null, null),
            flags = listOf("qr_payment_request", "qr_missing_payee"),
        ),
        ParityRow(
            payload = "upi://pay?pa=ravi@okaxis&pn=&tn=",
            kind = "upi",
            fields = listOf("ravi@okaxis", "", null, null, "", null, null),
            flags = listOf("qr_payment_request"),
        ),
        ParityRow(
            payload = "upi://pay?PA=Ravi@Okaxis&PN=Shop&AM=10&MODE=04",
            kind = "upi",
            fields = listOf("Ravi@Okaxis", "Shop", "10", null, null, null, "04"),
            flags = listOf("qr_payment_request", "qr_prefilled_amount", "qr_collect_mode"),
        ),
        ParityRow(
            payload = "upi://pay?%70a=ravi@okaxis&p%6E=Shop",
            kind = "upi",
            fields = listOf("ravi@okaxis", "Shop", null, null, null, null, null),
            flags = listOf("qr_payment_request"),
        ),
        ParityRow(
            payload = "upi://pay#frag?pa=evil@okaxis",
            kind = "upi",
            fields = listOf(null, null, null, null, null, null, null),
            flags = listOf("qr_payment_request", "qr_missing_payee"),
        ),
        ParityRow(
            payload = "upi://pay?pa=ravi@\tokaxis\n&pn=Shop\r",
            kind = "upi",
            fields = listOf("ravi@okaxis", "Shop", null, null, null, null, null),
            flags = listOf("qr_payment_request"),
        ),
        ParityRow(
            payload = "upi://pay?pa&pn=Shop",
            kind = "upi",
            fields = listOf("", "Shop", null, null, null, null, null),
            flags = listOf("qr_payment_request", "qr_missing_payee"),
        ),
        ParityRow(
            payload = "upi://pay",
            kind = "upi",
            fields = listOf(null, null, null, null, null, null, null),
            flags = listOf("qr_payment_request", "qr_missing_payee"),
        ),
        ParityRow(
            payload = "upi://pay?pn=Ram%20Kirana&am=250",
            kind = "upi",
            fields = listOf(null, "Ram Kirana", "250", null, null, null, null),
            flags = listOf("qr_payment_request", "qr_missing_payee", "qr_prefilled_amount"),
        ),
        ParityRow(
            payload = "upi://pay?pa=raviokaxis",
            kind = "upi",
            fields = listOf("raviokaxis", null, null, null, null, null, null),
            flags = listOf("qr_payment_request", "qr_malformed_payee"),
        ),
        ParityRow(
            payload = "upi://pay?pa=@okaxis",
            kind = "upi",
            fields = listOf("@okaxis", null, null, null, null, null, null),
            flags = listOf("qr_payment_request", "qr_malformed_payee"),
        ),
        ParityRow(
            payload = "upi://pay?pa=r@o",
            kind = "upi",
            fields = listOf("r@o", null, null, null, null, null, null),
            flags = listOf("qr_payment_request", "qr_malformed_payee"),
        ),
        ParityRow(
            payload = "upi://pay?pa=ravi@@okaxis",
            kind = "upi",
            fields = listOf("ravi@@okaxis", null, null, null, null, null, null),
            flags = listOf("qr_payment_request", "qr_malformed_payee"),
        ),
        ParityRow(
            payload = "upi://pay?pa=ravi%00@okaxis",
            kind = "upi",
            fields = listOf("ravi@okaxis", null, null, null, null, null, null),
            flags = listOf("qr_payment_request", "qr_malformed_payee"),
        ),
        ParityRow(
            payload = "upi://pay?pa=" + "a".repeat(400) + "@okaxis",
            kind = "upi",
            fields = listOf("a".repeat(320), null, null, null, null, null, null),
            flags = listOf("qr_payment_request", "qr_malformed_payee"),
        ),
        ParityRow(
            payload = "upi://pay?pa=ravi@okaxis&am=0",
            kind = "upi",
            fields = listOf("ravi@okaxis", null, "0", null, null, null, null),
            flags = listOf("qr_payment_request", "qr_prefilled_amount"),
        ),
        ParityRow(
            payload = "upi://pay?pa=ravi@okaxis&am=100000000",
            kind = "upi",
            fields = listOf("ravi@okaxis", null, "100000000", null, null, null, null),
            flags = listOf("qr_payment_request", "qr_prefilled_amount"),
        ),
        ParityRow(
            payload = "upi://pay?pa=ravi@okaxis&am=100000001",
            kind = "upi",
            fields = listOf("ravi@okaxis", null, "100000001", null, null, null, null),
            flags = listOf("qr_payment_request", "qr_malformed_amount"),
        ),
        ParityRow(
            payload = "upi://pay?pa=ravi@okaxis&am=1e9",
            kind = "upi",
            fields = listOf("ravi@okaxis", null, "1e9", null, null, null, null),
            flags = listOf("qr_payment_request", "qr_malformed_amount"),
        ),
        ParityRow(
            payload = "upi://pay?pa=ravi@okaxis&am=-5",
            kind = "upi",
            fields = listOf("ravi@okaxis", null, "-5", null, null, null, null),
            flags = listOf("qr_payment_request", "qr_malformed_amount"),
        ),
        ParityRow(
            payload = "upi://pay?pa=ravi@okaxis&am=-0",
            kind = "upi",
            fields = listOf("ravi@okaxis", null, "-0", null, null, null, null),
            flags = listOf("qr_payment_request", "qr_prefilled_amount"),
        ),
        ParityRow(
            payload = "upi://pay?pa=ravi@okaxis&am=.5",
            kind = "upi",
            fields = listOf("ravi@okaxis", null, ".5", null, null, null, null),
            flags = listOf("qr_payment_request", "qr_prefilled_amount"),
        ),
        ParityRow(
            payload = "upi://pay?pa=ravi@okaxis&am=abc",
            kind = "upi",
            fields = listOf("ravi@okaxis", null, "abc", null, null, null, null),
            flags = listOf("qr_payment_request", "qr_malformed_amount"),
        ),
        ParityRow(
            payload = "upi://pay?pa=ravi@okaxis&am=",
            kind = "upi",
            fields = listOf("ravi@okaxis", null, "", null, null, null, null),
            flags = listOf("qr_payment_request"),
        ),
        ParityRow(
            payload = "upi://pay?pa=ravi@okaxis&mode=04",
            kind = "upi",
            fields = listOf("ravi@okaxis", null, null, null, null, null, "04"),
            flags = listOf("qr_payment_request", "qr_collect_mode"),
        ),
        ParityRow(
            payload = "upi://pay?pa=ravi@okaxis&mode=COLLECT",
            kind = "upi",
            fields = listOf("ravi@okaxis", null, null, null, null, null, "COLLECT"),
            flags = listOf("qr_payment_request", "qr_collect_mode"),
        ),
        ParityRow(
            payload = "upi://pay?pa=ravi@okaxis&mode=05",
            kind = "upi",
            fields = listOf("ravi@okaxis", null, null, null, null, null, "05"),
            flags = listOf("qr_payment_request"),
        ),
        ParityRow(
            payload = "upi://pay?pa=ravi@okaxis&pn=" + "N".repeat(250) + "&tn=note&am=" + "9".repeat(40),
            kind = "upi",
            fields = listOf("ravi@okaxis", null, null, null, "note", null, null),
            flags = listOf("qr_payment_request", "qr_malformed_fields"),
        ),
        ParityRow(
            payload = "upi://pay?pa=ravi@okaxis&pn=" + "N".repeat(201) + "&cu=IIIIIIIII&tn=" + "t".repeat(501) + "&mc=" + "m".repeat(17) + "&mode=" + "0".repeat(33),
            kind = "upi",
            fields = listOf("ravi@okaxis", null, null, null, null, null, null),
            flags = listOf("qr_payment_request", "qr_malformed_fields"),
        ),
        ParityRow(
            payload = "upi://pay?pa=ravi@okaxis&tn=" + "t".repeat(500),
            kind = "upi",
            fields = listOf("ravi@okaxis", null, null, null, "t".repeat(500), null, null),
            flags = listOf("qr_payment_request"),
        ),
        ParityRow(
            payload = "upi://pay?pa=ravi@okaxis&tn=" + "t".repeat(501),
            kind = "upi",
            fields = listOf("ravi@okaxis", null, null, null, null, null, null),
            flags = listOf("qr_payment_request", "qr_malformed_fields"),
        ),
        ParityRow(
            payload = "upi://pay?pa=ravi@okaxis&tn=A%00B%E2%80%8BC",
            kind = "upi",
            fields = listOf("ravi@okaxis", null, null, null, "ABC", null, null),
            flags = listOf("qr_payment_request"),
        ),
        ParityRow(
            payload = "upi://pay?pa=ravi@okaxis&tn=Pay%20%F0%9F%98%80",
            kind = "upi",
            fields = listOf("ravi@okaxis", null, null, null, "Pay \ud83d\ude00", null, null),
            flags = listOf("qr_payment_request"),
        ),
        ParityRow(
            payload = "upi://pay?pa=ravi@okaxis&tn=100%zz",
            kind = "upi",
            fields = listOf("ravi@okaxis", null, null, null, "100%zz", null, null),
            flags = listOf("qr_payment_request"),
        ),
        ParityRow(
            payload = "upi://pay?pa=ravi@okaxis&tn=%E0%A4",
            kind = "upi",
            fields = listOf("ravi@okaxis", null, null, null, "\ufffd", null, null),
            flags = listOf("qr_payment_request"),
        ),
        ParityRow(
            payload = "upi://pay zaroor karein",
            kind = "upi",
            fields = listOf(null, null, null, null, null, null, null),
            flags = listOf("qr_payment_request", "qr_missing_payee"),
        ),
        ParityRow(
            payload = "https://example.com/pay?pa=ravi@okaxis",
            kind = "url",
            fields = listOf(null, null, null, null, null, null, null),
            flags = emptyList(),
        ),
        ParityRow(
            payload = "javascript:alert(1)",
            kind = "url",
            fields = listOf(null, null, null, null, null, null, null),
            flags = emptyList(),
        ),
        ParityRow(
            payload = "data:text/html;base64,QUJD",
            kind = "url",
            fields = listOf(null, null, null, null, null, null, null),
            flags = emptyList(),
        ),
        ParityRow(
            payload = "tel:100",
            kind = "other",
            fields = listOf(null, null, null, null, null, null, null),
            flags = emptyList(),
        ),
        ParityRow(
            payload = "mailto:help@example.com",
            kind = "other",
            fields = listOf(null, null, null, null, null, null, null),
            flags = emptyList(),
        ),
        ParityRow(
            payload = "wifi:S:Home;T:WPA;P:x;;",
            kind = "other",
            fields = listOf(null, null, null, null, null, null, null),
            flags = emptyList(),
        ),
        ParityRow(
            payload = "BEGIN:VCARD\\nFN:Ram",
            kind = "other",
            fields = listOf(null, null, null, null, null, null, null),
            flags = emptyList(),
        ),
    )

    @Test
    fun `parser agrees with backend classify_payload and _parse_upi on every fixture`() {
        assertEquals(47, BACKEND_PARITY.size)
        BACKEND_PARITY.forEachIndexed { index, row ->
            val parsed = UpiQrParser.parse(row.payload)
            assertEquals("kind at row $index", row.kind, parsed.kind.wire)
            if (row.kind != "upi") {
                assertTrue("flags at row $index", parsed.flags.isEmpty())
                assertTrue("payee at row $index", parsed.payeeVpa == null)
                return@forEachIndexed
            }
            assertEquals(
                "fields at row $index",
                row.fields,
                listOf(
                    parsed.payeeVpa,
                    parsed.payeeName,
                    parsed.amount,
                    parsed.currency,
                    parsed.note,
                    parsed.merchantCode,
                    parsed.mode,
                ),
            )
            assertEquals(
                "evidence order at row $index",
                row.flags,
                parsed.flags.mapNotNull { it.evidenceCategory },
            )
        }
    }
}
