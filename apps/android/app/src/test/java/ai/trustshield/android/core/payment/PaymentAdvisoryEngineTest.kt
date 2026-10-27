package ai.trustshield.android.core.payment

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the payment advisory engine's rule table, verdict table, hint order and hardening promises.
 *
 * Every value here is synthetic: no real UPI ID, amount, UTR or person appears in this file. The
 * behaviours a reader might otherwise call bugs are marked **DOCUMENTED** and asserted on purpose:
 *
 *  * `URGENCY_PRESSURE` is a lexical speed marker with no negation or intent analysis, so "abhi" in a
 *    legitimate credit line still fires; it is a `WARNING` that reaches `CHECK` on its own.
 *  * A cue only counts when it is not glued to a letter or digit, so `Abhishek` and `15 minute` do not
 *    fire `abhi` / `5 minute`.
 */
class PaymentAdvisoryEngineTest {

    private val engine: PaymentAdvisoryEngine = PaymentAdvisoryEngine()

    /** Synthetic, deliberately unmatchable VPA used only to exercise masking and comparison. */
    private val sampleUpi: String = "shop@okaxis"

    private val cleanText: String = "Payment successful Rs 250 to shop"

    // ---- fixtures ---------------------------------------------------------------------------

    private fun fields(
        amountMinor: Long? = 25_000L,
        payeeUpi: String = sampleUpi,
        payeeName: String = "",
        utr: String = "",
        status: PaymentStatus = PaymentStatus.SUCCESS,
        timestampEpochMs: Long? = 1_000_000L
    ): PaymentFields = PaymentFields(
        amountMinor = amountMinor,
        amountRaw = if (amountMinor == null) "" else "Rs " + (amountMinor / 100L),
        payeeUpi = payeeUpi,
        payeeName = payeeName,
        utr = utr,
        status = status,
        statusRaw = "",
        timestampRaw = "",
        timestampEpochMs = timestampEpochMs,
        appHint = PaymentAppHint.UNKNOWN
    )

    private fun input(
        text: String = cleanText,
        fields: PaymentFields = fields(),
        registeredPayeeCount: Int = 0,
        payeeMatchesRegistered: Boolean = false,
        nowEpochMs: Long = 0L,
        ocrUsed: Boolean = false
    ): PaymentAdvisoryInput = PaymentAdvisoryInput(
        sourceText = text,
        fields = fields,
        registeredPayeeCount = registeredPayeeCount,
        payeeMatchesRegistered = payeeMatchesRegistered,
        nowEpochMs = nowEpochMs,
        ocrUsed = ocrUsed
    )

    private fun kinds(advisory: PaymentAdvisory): List<PaymentHintKind> =
        advisory.hints.map { hint -> hint.kind }

    private fun hint(advisory: PaymentAdvisory, kind: PaymentHintKind): PaymentHint =
        advisory.hints.first { produced -> produced.kind == kind }

    private fun has(advisory: PaymentAdvisory, kind: PaymentHintKind): Boolean =
        kinds(advisory).contains(kind)

    /** The shipped thresholds this test file reasons about. */
    private fun assertShippedPolicy(policy: PaymentAdvisoryPolicy) {
        assertEquals(500_000L, policy.largeAmountMinor)
        assertEquals(200_000L, policy.roundAmountMinor)
        assertEquals(100_000L, policy.roundDivisorMinor)
        assertEquals(10L, policy.recentMinutes)
    }

    // ---- policy / shape ---------------------------------------------------------------------

    @Test
    fun `default policy thresholds are pinned`() {
        assertShippedPolicy(PaymentAdvisoryPolicy())
    }

    @Test
    fun `non payment text is not a payment screenshot and carries no hints`() {
        val advisory: PaymentAdvisory =
            engine.evaluate(input(text = "Kal milte hain, ghar aa jaana", fields = PaymentFields()))

        assertFalse(advisory.isPaymentScreenshot)
        assertTrue(advisory.hints.isEmpty())
        assertEquals(PaymentAdvisoryVerdict.CLEAR, advisory.verdict)
        assertTrue(advisory.confirmationRequired)
    }

    @Test
    fun `ocr text is a payment view even without receipt fields`() {
        val advisory: PaymentAdvisory =
            engine.evaluate(input(text = "kuch text", fields = PaymentFields(), ocrUsed = true))

        assertTrue(advisory.isPaymentScreenshot)
        assertEquals(
            listOf(
                PaymentHintKind.PAYEE_MISSING,
                PaymentHintKind.STATUS_UNKNOWN,
                PaymentHintKind.AMOUNT_MISSING,
                PaymentHintKind.TIME_MISSING,
                PaymentHintKind.SCREENSHOT_ONLY
            ),
            kinds(advisory)
        )
        assertEquals(PaymentAdvisoryVerdict.CHECK, advisory.verdict)
    }

    @Test
    fun `confirmation stays required for clear check and stop`() {
        val clear: PaymentAdvisory =
            engine.evaluate(input(text = "hello", fields = PaymentFields()))
        val check: PaymentAdvisory = engine.evaluate(input(fields = fields(), registeredPayeeCount = 0))
        val stop: PaymentAdvisory = engine.evaluate(
            input(fields = fields(amountMinor = 200_000L), registeredPayeeCount = 1)
        )

        assertEquals(PaymentAdvisoryVerdict.CLEAR, clear.verdict)
        assertEquals(PaymentAdvisoryVerdict.CHECK, check.verdict)
        assertEquals(PaymentAdvisoryVerdict.STOP, stop.verdict)
        assertTrue(clear.confirmationRequired)
        assertTrue(check.confirmationRequired)
        assertTrue(stop.confirmationRequired)
    }

    // ---- payee rules ------------------------------------------------------------------------

    @Test
    fun `unregistered payee warns and exposes only the masked id`() {
        val advisory: PaymentAdvisory = engine.evaluate(
            input(
                fields = fields(utr = "123456789012"),
                registeredPayeeCount = 2,
                payeeMatchesRegistered = false
            )
        )

        val produced: PaymentHint = hint(advisory, PaymentHintKind.PAYEE_NOT_REGISTERED)
        assertEquals(PaymentHintSeverity.WARNING, produced.severity)
        assertEquals("sho***@okaxis", produced.maskedPayee)
        assertFalse(advisory.toString().contains(sampleUpi))
        assertFalse(produced.toString().contains(sampleUpi))
        assertEquals(PaymentAdvisoryVerdict.CHECK, advisory.verdict)
    }

    @Test
    fun `registered payee suppresses the unregistered warning even with a huge amount`() {
        val advisory: PaymentAdvisory = engine.evaluate(
            input(
                fields = fields(amountMinor = 5_000_000L, utr = "123456789012"),
                registeredPayeeCount = 1,
                payeeMatchesRegistered = true
            )
        )

        assertFalse(has(advisory, PaymentHintKind.PAYEE_NOT_REGISTERED))
        assertTrue(has(advisory, PaymentHintKind.PAYEE_MATCHES_REGISTERED))
        assertTrue(has(advisory, PaymentHintKind.AMOUNT_LARGE))
        assertFalse(has(advisory, PaymentHintKind.AMOUNT_ROUND_LARGE))
        // One caution only, so the verdict does not lift.
        assertEquals(PaymentAdvisoryVerdict.CLEAR, advisory.verdict)
    }

    @Test
    fun `no registered payees is a caution carrying count zero`() {
        val advisory: PaymentAdvisory = engine.evaluate(
            input(fields = fields(utr = "123456789012"), registeredPayeeCount = 0)
        )

        val produced: PaymentHint = hint(advisory, PaymentHintKind.NO_REGISTERED_PAYEES)
        assertEquals(PaymentHintSeverity.CAUTION, produced.severity)
        assertEquals(0, produced.count)
        assertFalse(has(advisory, PaymentHintKind.PAYEE_NOT_REGISTERED))
        assertEquals(PaymentAdvisoryVerdict.CLEAR, advisory.verdict)
    }

    @Test
    fun `missing payee upi is a caution`() {
        val advisory: PaymentAdvisory = engine.evaluate(
            input(fields = fields(payeeUpi = "", utr = "123456789012"), registeredPayeeCount = 0)
        )

        assertEquals(PaymentHintSeverity.CAUTION, hint(advisory, PaymentHintKind.PAYEE_MISSING).severity)
        assertFalse(has(advisory, PaymentHintKind.NO_REGISTERED_PAYEES))
        assertFalse(has(advisory, PaymentHintKind.PAYEE_NOT_REGISTERED))
    }

    @Test
    fun `printed payee name is flagged as spoofable`() {
        val advisory: PaymentAdvisory = engine.evaluate(
            input(
                fields = fields(payeeName = "Rahul Kumar", utr = "123456789012"),
                registeredPayeeCount = 1,
                payeeMatchesRegistered = true
            )
        )

        assertEquals(
            PaymentHintSeverity.CAUTION,
            hint(advisory, PaymentHintKind.PAYEE_NAME_SPOOFABLE).severity
        )
        assertEquals(PaymentAdvisoryVerdict.CLEAR, advisory.verdict)
    }

    // ---- amount rules -----------------------------------------------------------------------

    @Test
    fun `amount exactly at the large threshold is flagged`() {
        val advisory: PaymentAdvisory = engine.evaluate(
            input(
                fields = fields(amountMinor = 500_000L, utr = "123456789012"),
                registeredPayeeCount = 1,
                payeeMatchesRegistered = true
            )
        )

        val produced: PaymentHint = hint(advisory, PaymentHintKind.AMOUNT_LARGE)
        assertEquals(PaymentHintSeverity.CAUTION, produced.severity)
        assertEquals(500_000L, produced.amountMinor)
        assertFalse(has(advisory, PaymentHintKind.AMOUNT_ROUND_LARGE))
    }

    @Test
    fun `amount just below the large threshold is not flagged`() {
        val advisory: PaymentAdvisory = engine.evaluate(
            input(
                fields = fields(amountMinor = 499_999L, utr = "123456789012"),
                registeredPayeeCount = 1,
                payeeMatchesRegistered = true
            )
        )

        assertFalse(has(advisory, PaymentHintKind.AMOUNT_LARGE))
        assertFalse(has(advisory, PaymentHintKind.AMOUNT_ROUND_LARGE))
    }

    @Test
    fun `round amount without a registered match is a warning`() {
        val advisory: PaymentAdvisory = engine.evaluate(
            input(fields = fields(amountMinor = 200_000L), registeredPayeeCount = 1)
        )

        val produced: PaymentHint = hint(advisory, PaymentHintKind.AMOUNT_ROUND_LARGE)
        assertEquals(PaymentHintSeverity.WARNING, produced.severity)
        assertEquals(200_000L, produced.amountMinor)
    }

    @Test
    fun `round amount with a registered match is not flagged`() {
        val advisory: PaymentAdvisory = engine.evaluate(
            input(
                fields = fields(amountMinor = 200_000L),
                registeredPayeeCount = 1,
                payeeMatchesRegistered = true
            )
        )

        assertFalse(has(advisory, PaymentHintKind.AMOUNT_ROUND_LARGE))
    }

    @Test
    fun `non round amount above the round floor is not flagged`() {
        val advisory: PaymentAdvisory = engine.evaluate(
            input(fields = fields(amountMinor = 200_500L), registeredPayeeCount = 1)
        )

        assertFalse(has(advisory, PaymentHintKind.AMOUNT_ROUND_LARGE))
        assertFalse(has(advisory, PaymentHintKind.AMOUNT_LARGE))
    }

    @Test
    fun `missing amount is info only`() {
        val advisory: PaymentAdvisory = engine.evaluate(
            input(
                fields = fields(amountMinor = null, utr = "123456789012", timestampEpochMs = 1L),
                registeredPayeeCount = 1,
                payeeMatchesRegistered = true,
                ocrUsed = true
            )
        )

        assertEquals(PaymentHintSeverity.INFO, hint(advisory, PaymentHintKind.AMOUNT_MISSING).severity)
        assertFalse(has(advisory, PaymentHintKind.AMOUNT_LARGE))
        assertFalse(has(advisory, PaymentHintKind.AMOUNT_ROUND_LARGE))
        assertTrue(advisory.isPaymentScreenshot)
    }

    @Test
    fun `a custom policy moves the large amount threshold`() {
        val sensitive: PaymentAdvisoryEngine =
            PaymentAdvisoryEngine(PaymentAdvisoryPolicy(largeAmountMinor = 100_000L))
        val input: PaymentAdvisoryInput = input(
            fields = fields(amountMinor = 120_000L, utr = "123456789012"),
            registeredPayeeCount = 1,
            payeeMatchesRegistered = true
        )

        assertTrue(has(sensitive.evaluate(input), PaymentHintKind.AMOUNT_LARGE))
        assertFalse(has(engine.evaluate(input), PaymentHintKind.AMOUNT_LARGE))
    }

    @Test
    fun `a zero round divisor is inert instead of a crash`() {
        val broken: PaymentAdvisoryEngine =
            PaymentAdvisoryEngine(PaymentAdvisoryPolicy(roundDivisorMinor = 0L))
        val advisory: PaymentAdvisory = broken.evaluate(
            input(fields = fields(amountMinor = 200_000L), registeredPayeeCount = 1)
        )

        assertFalse(has(advisory, PaymentHintKind.AMOUNT_ROUND_LARGE))
        assertTrue(has(advisory, PaymentHintKind.PAYEE_NOT_REGISTERED))
    }

    // ---- status / UTR rules -----------------------------------------------------------------

    @Test
    fun `failed status is a warning`() {
        val advisory: PaymentAdvisory = engine.evaluate(
            input(
                fields = fields(status = PaymentStatus.FAILED, utr = "999888777666"),
                registeredPayeeCount = 1,
                payeeMatchesRegistered = true
            )
        )

        assertEquals(PaymentHintSeverity.WARNING, hint(advisory, PaymentHintKind.STATUS_FAILED).severity)
        assertEquals(PaymentAdvisoryVerdict.CHECK, advisory.verdict)
    }

    @Test
    fun `failed without urgency never stops`() {
        val advisory: PaymentAdvisory = engine.evaluate(
            input(
                fields = fields(status = PaymentStatus.FAILED, utr = ""),
                registeredPayeeCount = 0,
                payeeMatchesRegistered = false
            )
        )

        assertTrue(has(advisory, PaymentHintKind.STATUS_FAILED))
        assertFalse(has(advisory, PaymentHintKind.URGENCY_PRESSURE))
        assertNotEquals(PaymentAdvisoryVerdict.STOP, advisory.verdict)
        assertEquals(PaymentAdvisoryVerdict.CHECK, advisory.verdict)
    }

    @Test
    fun `pending with an unregistered payee stops`() {
        val advisory: PaymentAdvisory = engine.evaluate(
            input(
                fields = fields(status = PaymentStatus.PENDING),
                registeredPayeeCount = 1,
                payeeMatchesRegistered = false
            )
        )

        assertTrue(has(advisory, PaymentHintKind.PAYEE_NOT_REGISTERED))
        assertTrue(has(advisory, PaymentHintKind.STATUS_PENDING))
        assertEquals(PaymentAdvisoryVerdict.STOP, advisory.verdict)
    }

    @Test
    fun `unknown status is a caution`() {
        val advisory: PaymentAdvisory = engine.evaluate(
            input(
                fields = fields(status = PaymentStatus.UNKNOWN, utr = "123456789012"),
                registeredPayeeCount = 1,
                payeeMatchesRegistered = true
            )
        )

        assertEquals(
            PaymentHintSeverity.CAUTION,
            hint(advisory, PaymentHintKind.STATUS_UNKNOWN).severity
        )
    }

    @Test
    fun `processing carries no status hint`() {
        val advisory: PaymentAdvisory = engine.evaluate(
            input(
                fields = fields(status = PaymentStatus.PROCESSING, utr = "123456789012"),
                registeredPayeeCount = 1,
                payeeMatchesRegistered = true
            )
        )

        assertFalse(has(advisory, PaymentHintKind.STATUS_PENDING))
        assertFalse(has(advisory, PaymentHintKind.STATUS_FAILED))
        assertFalse(has(advisory, PaymentHintKind.STATUS_UNKNOWN))
        assertFalse(has(advisory, PaymentHintKind.UTR_MISSING))
        assertEquals(PaymentAdvisoryVerdict.CLEAR, advisory.verdict)
    }

    @Test
    fun `success without a utr is a caution`() {
        val advisory: PaymentAdvisory = engine.evaluate(
            input(
                fields = fields(status = PaymentStatus.SUCCESS, utr = ""),
                registeredPayeeCount = 1,
                payeeMatchesRegistered = true
            )
        )

        assertEquals(PaymentHintSeverity.CAUTION, hint(advisory, PaymentHintKind.UTR_MISSING).severity)
        assertFalse(has(advisory, PaymentHintKind.UTR_PRESENT))
    }

    @Test
    fun `a printed utr suppresses the missing reference hint`() {
        val advisory: PaymentAdvisory = engine.evaluate(
            input(
                fields = fields(status = PaymentStatus.SUCCESS, utr = "123456789012"),
                registeredPayeeCount = 1,
                payeeMatchesRegistered = true
            )
        )

        assertFalse(has(advisory, PaymentHintKind.UTR_MISSING))
        assertEquals(PaymentHintSeverity.INFO, hint(advisory, PaymentHintKind.UTR_PRESENT).severity)
    }

    @Test
    fun `a utr on a failed receipt is still reported as present`() {
        val advisory: PaymentAdvisory = engine.evaluate(
            input(
                fields = fields(status = PaymentStatus.FAILED, utr = "999888777666"),
                registeredPayeeCount = 1,
                payeeMatchesRegistered = true
            )
        )

        assertTrue(has(advisory, PaymentHintKind.UTR_PRESENT))
        assertFalse(has(advisory, PaymentHintKind.UTR_MISSING))
    }

    // ---- time rules -------------------------------------------------------------------------

    @Test
    fun `missing timestamp is info`() {
        val advisory: PaymentAdvisory = engine.evaluate(
            input(
                fields = fields(utr = "123456789012", timestampEpochMs = null),
                registeredPayeeCount = 1,
                payeeMatchesRegistered = true
            )
        )

        assertTrue(has(advisory, PaymentHintKind.TIME_MISSING))
        assertFalse(has(advisory, PaymentHintKind.TIME_RECENT))
        assertEquals(PaymentAdvisoryVerdict.CLEAR, advisory.verdict)
    }

    @Test
    fun `a five minute old timestamp is recent`() {
        val advisory: PaymentAdvisory = engine.evaluate(
            input(
                fields = fields(utr = "123456789012", timestampEpochMs = 1_000_000L),
                registeredPayeeCount = 1,
                payeeMatchesRegistered = true,
                nowEpochMs = 1_300_000L
            )
        )

        assertEquals(PaymentHintSeverity.CAUTION, hint(advisory, PaymentHintKind.TIME_RECENT).severity)
    }

    @Test
    fun `the ten minute edge is inclusive`() {
        val advisory: PaymentAdvisory = engine.evaluate(
            input(
                fields = fields(utr = "123456789012", timestampEpochMs = 1_000_000L),
                registeredPayeeCount = 1,
                payeeMatchesRegistered = true,
                nowEpochMs = 1_600_000L
            )
        )

        assertTrue(has(advisory, PaymentHintKind.TIME_RECENT))
    }

    @Test
    fun `a timestamp past the window is not recent`() {
        val advisory: PaymentAdvisory = engine.evaluate(
            input(
                fields = fields(utr = "123456789012", timestampEpochMs = 1_000_000L),
                registeredPayeeCount = 1,
                payeeMatchesRegistered = true,
                nowEpochMs = 1_600_001L
            )
        )

        assertFalse(has(advisory, PaymentHintKind.TIME_RECENT))
    }

    @Test
    fun `a future timestamp is not recent`() {
        val advisory: PaymentAdvisory = engine.evaluate(
            input(
                fields = fields(utr = "123456789012", timestampEpochMs = 2_000_000L),
                registeredPayeeCount = 1,
                payeeMatchesRegistered = true,
                nowEpochMs = 1_000_000L
            )
        )

        assertFalse(has(advisory, PaymentHintKind.TIME_RECENT))
    }

    @Test
    fun `now of zero disables the recent check`() {
        val advisory: PaymentAdvisory = engine.evaluate(
            input(
                fields = fields(utr = "123456789012", timestampEpochMs = 1L),
                registeredPayeeCount = 1,
                payeeMatchesRegistered = true,
                nowEpochMs = 0L
            )
        )

        assertFalse(has(advisory, PaymentHintKind.TIME_RECENT))
        assertFalse(has(advisory, PaymentHintKind.TIME_MISSING))
    }

    // ---- urgency rules ----------------------------------------------------------------------

    @Test
    fun `a speed cue fires the urgency warning`() {
        val advisory: PaymentAdvisory = engine.evaluate(
            input(
                text = "JALDI paise bhejo, deal khatam ho rahi hai",
                fields = fields(utr = "123456789012"),
                registeredPayeeCount = 1,
                payeeMatchesRegistered = true
            )
        )

        assertEquals(
            PaymentHintSeverity.WARNING,
            hint(advisory, PaymentHintKind.URGENCY_PRESSURE).severity
        )
        assertEquals(PaymentAdvisoryVerdict.CHECK, advisory.verdict)
    }

    @Test
    fun `urgency alone reaches check`() {
        val advisory: PaymentAdvisory = engine.evaluate(
            input(
                text = "turant transfer karo",
                fields = fields(utr = "123456789012"),
                registeredPayeeCount = 1,
                payeeMatchesRegistered = true
            )
        )

        assertEquals(1, advisory.warnings.size)
        assertEquals(PaymentAdvisoryVerdict.CHECK, advisory.verdict)
    }

    @Test
    fun `DOCUMENTED abhi inside a legitimate credit line still fires`() {
        // Pinned on purpose: the cue is lexical, with no negation or intent analysis. The honest
        // reading of this behaviour is "the message pushes speed", and its weight is capped at CHECK
        // because the payee is registered and the amount is small.
        val advisory: PaymentAdvisory = engine.evaluate(
            input(
                text = "aapke account me abhi paise credit hue hain",
                fields = fields(utr = "123456789012"),
                registeredPayeeCount = 1,
                payeeMatchesRegistered = true
            )
        )

        assertTrue(has(advisory, PaymentHintKind.URGENCY_PRESSURE))
        assertEquals(PaymentAdvisoryVerdict.CHECK, advisory.verdict)
    }

    @Test
    fun `DOCUMENTED abhi glued inside a name does not fire`() {
        val advisory: PaymentAdvisory = engine.evaluate(
            input(
                text = "Abhishek ne 500 rupaye bheje",
                fields = fields(utr = "123456789012"),
                registeredPayeeCount = 1,
                payeeMatchesRegistered = true
            )
        )

        assertFalse(has(advisory, PaymentHintKind.URGENCY_PRESSURE))
        assertEquals(PaymentAdvisoryVerdict.CLEAR, advisory.verdict)
    }

    @Test
    fun `DOCUMENTED five minute fires but fifteen minute does not`() {
        val hit: PaymentAdvisory = engine.evaluate(
            input(
                text = "5 minute me paise bhejo",
                fields = fields(utr = "123456789012"),
                registeredPayeeCount = 1,
                payeeMatchesRegistered = true
            )
        )
        val miss: PaymentAdvisory = engine.evaluate(
            input(
                text = "15 minute me paise bhejo",
                fields = fields(utr = "123456789012"),
                registeredPayeeCount = 1,
                payeeMatchesRegistered = true
            )
        )

        assertTrue(has(hit, PaymentHintKind.URGENCY_PRESSURE))
        assertFalse(has(miss, PaymentHintKind.URGENCY_PRESSURE))
    }

    @Test
    fun `the plural minute cue fires`() {
        val advisory: PaymentAdvisory = engine.evaluate(
            input(
                text = "sirf 10 minutes hain",
                fields = fields(utr = "123456789012"),
                registeredPayeeCount = 1,
                payeeMatchesRegistered = true
            )
        )

        assertTrue(has(advisory, PaymentHintKind.URGENCY_PRESSURE))
    }

    @Test
    fun `a cue beyond character four thousand is ignored`() {
        val text: String = "a".repeat(4_500) + " jaldi karo"
        assertEquals(4_511, text.length)
        val advisory: PaymentAdvisory = engine.evaluate(
            input(
                text = text,
                fields = fields(utr = "123456789012"),
                registeredPayeeCount = 1,
                payeeMatchesRegistered = true
            )
        )

        assertFalse(has(advisory, PaymentHintKind.URGENCY_PRESSURE))
    }

    @Test
    fun `a cue just inside the scan window still fires`() {
        val advisory: PaymentAdvisory = engine.evaluate(
            input(
                text = "a".repeat(3_900) + " jaldi karo",
                fields = fields(utr = "123456789012"),
                registeredPayeeCount = 1,
                payeeMatchesRegistered = true
            )
        )

        assertTrue(has(advisory, PaymentHintKind.URGENCY_PRESSURE))
    }

    @Test
    fun `urgency is not evaluated for a non payment view`() {
        val advisory: PaymentAdvisory = engine.evaluate(
            input(text = "jaldi aa jaana ghar", fields = PaymentFields())
        )

        assertTrue(advisory.hints.isEmpty())
        assertEquals(PaymentAdvisoryVerdict.CLEAR, advisory.verdict)
    }

    // ---- verdict table ----------------------------------------------------------------------

    @Test
    fun `stop when an unregistered payee meets a large amount`() {
        val advisory: PaymentAdvisory = engine.evaluate(
            input(
                fields = fields(amountMinor = 550_000L, utr = "123456789012"),
                registeredPayeeCount = 1,
                payeeMatchesRegistered = false
            )
        )

        assertTrue(has(advisory, PaymentHintKind.PAYEE_NOT_REGISTERED))
        assertTrue(has(advisory, PaymentHintKind.AMOUNT_LARGE))
        assertEquals(PaymentAdvisoryVerdict.STOP, advisory.verdict)
    }

    @Test
    fun `stop when an unregistered payee meets a round amount`() {
        val advisory: PaymentAdvisory = engine.evaluate(
            input(fields = fields(amountMinor = 200_000L), registeredPayeeCount = 1)
        )

        assertTrue(has(advisory, PaymentHintKind.AMOUNT_ROUND_LARGE))
        assertEquals(PaymentAdvisoryVerdict.STOP, advisory.verdict)
    }

    @Test
    fun `stop when an unregistered payee meets urgency`() {
        val advisory: PaymentAdvisory = engine.evaluate(
            input(
                text = "turant karo warna deal chali jayegi",
                fields = fields(amountMinor = 25_000L),
                registeredPayeeCount = 1,
                payeeMatchesRegistered = false
            )
        )

        assertTrue(has(advisory, PaymentHintKind.URGENCY_PRESSURE))
        assertEquals(PaymentAdvisoryVerdict.STOP, advisory.verdict)
    }

    @Test
    fun `stop when a failed status meets urgency`() {
        val advisory: PaymentAdvisory = engine.evaluate(
            input(
                text = "jaldi dobara bhejo",
                fields = fields(status = PaymentStatus.FAILED),
                registeredPayeeCount = 1,
                payeeMatchesRegistered = true
            )
        )

        assertTrue(has(advisory, PaymentHintKind.STATUS_FAILED))
        assertEquals(PaymentAdvisoryVerdict.STOP, advisory.verdict)
    }

    @Test
    fun `unregistered payee with a failed status is check not stop`() {
        val advisory: PaymentAdvisory = engine.evaluate(
            input(
                fields = fields(status = PaymentStatus.FAILED, utr = ""),
                registeredPayeeCount = 1,
                payeeMatchesRegistered = false
            )
        )

        assertTrue(has(advisory, PaymentHintKind.PAYEE_NOT_REGISTERED))
        assertTrue(has(advisory, PaymentHintKind.STATUS_FAILED))
        assertEquals(PaymentAdvisoryVerdict.CHECK, advisory.verdict)
    }

    @Test
    fun `DOCUMENTED a single caution stays clear`() {
        // Large but not round (550_000 % 100_000 != 0), payee registered: exactly one caution.
        val advisory: PaymentAdvisory = engine.evaluate(
            input(
                fields = fields(amountMinor = 550_000L, utr = "123456789012"),
                registeredPayeeCount = 1,
                payeeMatchesRegistered = true
            )
        )

        assertEquals(1, advisory.cautions.size)
        assertTrue(has(advisory, PaymentHintKind.AMOUNT_LARGE))
        assertEquals(PaymentAdvisoryVerdict.CLEAR, advisory.verdict)
    }

    @Test
    fun `two cautions reach check`() {
        val advisory: PaymentAdvisory = engine.evaluate(
            input(
                fields = fields(
                    amountMinor = 550_000L,
                    payeeName = "Rahul Kumar",
                    utr = "123456789012"
                ),
                registeredPayeeCount = 1,
                payeeMatchesRegistered = true
            )
        )

        assertEquals(2, advisory.cautions.size)
        assertEquals(PaymentAdvisoryVerdict.CHECK, advisory.verdict)
    }

    // ---- ordering / uniqueness --------------------------------------------------------------

    @Test
    fun `hints are ordered warning then caution then info`() {
        val advisory: PaymentAdvisory = engine.evaluate(
            input(
                fields = fields(utr = "", status = PaymentStatus.SUCCESS, timestampEpochMs = null),
                registeredPayeeCount = 1,
                payeeMatchesRegistered = false
            )
        )

        assertEquals(
            listOf(
                PaymentHintKind.PAYEE_NOT_REGISTERED,
                PaymentHintKind.UTR_MISSING,
                PaymentHintKind.TIME_MISSING
            ),
            kinds(advisory)
        )
        assertEquals(
            listOf(
                PaymentHintSeverity.WARNING,
                PaymentHintSeverity.CAUTION,
                PaymentHintSeverity.INFO
            ),
            advisory.hints.map { produced -> produced.severity }
        )
    }

    @Test
    fun `within a severity hints follow the declaration order`() {
        val advisory: PaymentAdvisory = engine.evaluate(
            input(
                text = "jaldi karo",
                fields = fields(amountMinor = 200_000L, utr = "123456789012", timestampEpochMs = null),
                registeredPayeeCount = 1,
                payeeMatchesRegistered = false
            )
        )

        assertEquals(
            listOf(
                PaymentHintKind.PAYEE_NOT_REGISTERED,
                PaymentHintKind.AMOUNT_ROUND_LARGE,
                PaymentHintKind.URGENCY_PRESSURE
            ),
            advisory.warnings.map { produced -> produced.kind }
        )
        assertEquals(PaymentAdvisoryVerdict.STOP, advisory.verdict)
    }

    @Test
    fun `a maximal input never duplicates a kind`() {
        val advisory: PaymentAdvisory = engine.evaluate(
            input(
                text = "jaldi karo abhi",
                fields = fields(
                    amountMinor = 500_000L,
                    payeeName = "Rahul Kumar",
                    utr = "",
                    status = PaymentStatus.PENDING,
                    timestampEpochMs = 1_000_000L
                ),
                registeredPayeeCount = 1,
                payeeMatchesRegistered = false,
                nowEpochMs = 1_060_000L,
                ocrUsed = true
            )
        )

        val produced: List<PaymentHintKind> = kinds(advisory)
        assertEquals(produced.size, produced.distinct().size)
        assertEquals(8, produced.size)
        assertTrue(produced.contains(PaymentHintKind.SCREENSHOT_ONLY))
        assertTrue(produced.contains(PaymentHintKind.TIME_RECENT))
        assertEquals(PaymentAdvisoryVerdict.STOP, advisory.verdict)
    }

    @Test
    fun `failed plus urgency stops even when payee matches`() {
        val advisory: PaymentAdvisory = engine.evaluate(
            input(
                text = "jaldi dobara bhejo",
                fields = fields(status = PaymentStatus.FAILED, utr = "123456789012"),
                registeredPayeeCount = 1,
                payeeMatchesRegistered = true
            )
        )

        assertTrue(has(advisory, PaymentHintKind.STATUS_FAILED))
        assertTrue(has(advisory, PaymentHintKind.URGENCY_PRESSURE))
        assertFalse(has(advisory, PaymentHintKind.PAYEE_NOT_REGISTERED))
        assertEquals(PaymentAdvisoryVerdict.STOP, advisory.verdict)
    }

    @Test
    fun `pending and unregistered payee stops without amount or urgency`() {
        val advisory: PaymentAdvisory = engine.evaluate(
            input(fields = fields(amountMinor = null, status = PaymentStatus.PENDING), registeredPayeeCount = 1)
        )

        assertTrue(has(advisory, PaymentHintKind.PAYEE_NOT_REGISTERED))
        assertTrue(has(advisory, PaymentHintKind.STATUS_PENDING))
        assertFalse(has(advisory, PaymentHintKind.AMOUNT_LARGE))
        assertFalse(has(advisory, PaymentHintKind.URGENCY_PRESSURE))
        assertEquals(PaymentAdvisoryVerdict.STOP, advisory.verdict)
    }

    @Test
    fun `unregistered payee and a failed status without urgency is check`() {
        val advisory: PaymentAdvisory = engine.evaluate(
            input(
                fields = fields(status = PaymentStatus.FAILED, utr = "123456789012"),
                registeredPayeeCount = 1,
                payeeMatchesRegistered = false
            )
        )

        assertFalse(has(advisory, PaymentHintKind.URGENCY_PRESSURE))
        assertEquals(PaymentAdvisoryVerdict.CHECK, advisory.verdict)
    }

    @Test
    fun `round amount floor is inclusive and one paisa below is not round-large`() {
        val atFloor: PaymentAdvisory = engine.evaluate(
            input(fields = fields(amountMinor = 200_000L), registeredPayeeCount = 1)
        )
        val belowFloor: PaymentAdvisory = engine.evaluate(
            input(fields = fields(amountMinor = 199_999L), registeredPayeeCount = 1)
        )

        assertTrue(has(atFloor, PaymentHintKind.AMOUNT_ROUND_LARGE))
        assertFalse(has(belowFloor, PaymentHintKind.AMOUNT_ROUND_LARGE))
    }

    @Test
    fun `nonpositive amounts do not trigger amount flags`() {
        for (amount: Long in listOf(0L, -1L, Long.MIN_VALUE)) {
            val advisory: PaymentAdvisory = engine.evaluate(
                input(fields = fields(amountMinor = amount), registeredPayeeCount = 1)
            )
            assertFalse(has(advisory, PaymentHintKind.AMOUNT_LARGE))
            assertFalse(has(advisory, PaymentHintKind.AMOUNT_ROUND_LARGE))
        }
    }

    @Test
    fun `clear verdict is not represented as a success or safety claim`() {
        val advisory: PaymentAdvisory = engine.evaluate(
            input(fields = fields(utr = "123456789012"), registeredPayeeCount = 1, payeeMatchesRegistered = true)
        )

        assertEquals(PaymentAdvisoryVerdict.CLEAR, advisory.verdict)
        assertTrue(advisory.confirmationRequired)
        assertFalse(advisory.toString().contains("safe", ignoreCase = true))
        assertFalse(advisory.toString().contains("success", ignoreCase = true))
        assertFalse(advisory.toString().contains("fraud", ignoreCase = true))
    }

    // ---- hardening --------------------------------------------------------------------------

    @Test
    fun `empty and blank text never throw`() {
        val empty: PaymentAdvisory = engine.evaluate(input(text = "", fields = PaymentFields()))
        val blank: PaymentAdvisory = engine.evaluate(input(text = "    ", fields = PaymentFields()))
        val receiptOnly: PaymentAdvisory = engine.evaluate(input(text = "", fields = fields()))

        assertTrue(empty.hints.isEmpty())
        assertTrue(blank.hints.isEmpty())
        assertEquals(PaymentAdvisoryVerdict.CLEAR, empty.verdict)
        assertEquals(PaymentAdvisoryVerdict.CLEAR, blank.verdict)
        assertFalse(has(receiptOnly, PaymentHintKind.URGENCY_PRESSURE))
        assertTrue(receiptOnly.isPaymentScreenshot)
    }

    @Test
    fun `hostile field combinations never throw and never leak a raw upi id`() {
        val hostile: List<PaymentFields> = listOf(
            PaymentFields(
                amountMinor = Long.MIN_VALUE,
                payeeUpi = sampleUpi,
                utr = " ",
                status = PaymentStatus.FAILED,
                timestampEpochMs = Long.MAX_VALUE
            ),
            PaymentFields(
                amountMinor = Long.MAX_VALUE,
                payeeUpi = sampleUpi,
                utr = "u",
                status = PaymentStatus.SUCCESS,
                timestampEpochMs = 1L
            ),
            PaymentFields(amountMinor = -1L, payeeUpi = "@", status = PaymentStatus.PROCESSING),
            PaymentFields(amountMinor = 0L, payeeUpi = "no-at-sign", payeeName = "x"),
            PaymentFields(amountMinor = 1L, payeeUpi = "k@", status = PaymentStatus.PENDING),
            PaymentFields(
                amountMinor = 100_000L,
                payeeUpi = sampleUpi,
                payeeName = "  ",
                status = PaymentStatus.SUCCESS,
                timestampEpochMs = 0L
            )
        )
        val hugeText: String = "x".repeat(5_000)
        var produced: Int = 0

        for (field in hostile) {
            for (count in listOf(0, 3)) {
                for (matched in listOf(true, false)) {
                    for (now in listOf(0L, Long.MAX_VALUE)) {
                        val advisory: PaymentAdvisory = engine.evaluate(
                            input(
                                text = hugeText,
                                fields = field,
                                registeredPayeeCount = count,
                                payeeMatchesRegistered = matched,
                                nowEpochMs = now,
                                ocrUsed = true
                            )
                        )
                        produced += 1
                        assertTrue(advisory.hints.size <= PaymentHintKind.entries.size)
                        assertEquals(
                            advisory.hints.size,
                            advisory.hints.map { hintValue -> hintValue.kind }.distinct().size
                        )
                        assertFalse(advisory.toString().contains(sampleUpi))
                        assertTrue(advisory.confirmationRequired)
                    }
                }
            }
        }

        assertEquals(48, produced)
    }
}
