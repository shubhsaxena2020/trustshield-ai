package ai.trustshield.android.core.payment

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The honest cross-check on the payment advisory: every row of [PaymentFixtures] is parsed by the
 * real [PaymentFieldsParser] and evaluated by the real [PaymentAdvisoryEngine], and the row that
 * disagrees names itself in the failure message.
 *
 * ## What a green run means
 *
 * Only this: the payment rules still do on twenty-four synthetic demo texts what the gate table in
 * [PaymentFixtures] says they do. It is not a quality measurement, it is not drawn from any
 * evaluation set, and it changes no reported number.
 *
 * ## Why the expectations are not edited to fit a result
 *
 * Every verdict and every hint set in the fixture file was written from the four gates (G1-G4,
 * documented on [PaymentFixtures]) and the pinned contract, before a single assertion ran. When the
 * engine disagrees the assertion fails and the caller reconciles the gate with the engine - widening
 * an expectation to make a red run green would delete the only check on the warning direction.
 *
 * ## Deliberately not pinned
 *
 * `TIME_MISSING` / `TIME_RECENT` are never asserted, because the fixture clock is fixed at
 * [PaymentFixtures.FIXED_NOW_EPOCH_MS] = `14 Nov 2023, 10:13 pm` IST while the receipts are stamped
 * minutes earlier; whether a row reads as "recent" is therefore a property of that offset, not of
 * the scenario. `AMOUNT_ROUND_LARGE` is pinned only where the round figure *is* the lure (S05).
 * `STATUS_UNKNOWN` and `AMOUNT_MISSING` are never pinned in either direction: they are honest
 * remarks about an unreadable field, and forbidding them on a QR text would be asserting that the
 * parser must invent a status line the text does not contain.
 *
 * ## Known disagreements, measured 2026-10-27 and left red on purpose
 *
 * Run against the parser and engine as they stood in this worktree: 24 tests, 5 failures, 3 rows out
 * of 24. No expectation below was widened to clear them - the fix for each belongs to
 * `PaymentFieldsParser` / `PaymentAdvisoryEngine`, not to this table.
 *
 *  * **S11** (success with no reference, registered payee) - expected `CHECK`, measured `CLEAR` with
 *    hints `[UTR_MISSING, PAYEE_MATCHES_REGISTERED]`. The contract defines `CLEAR` as *no
 *    payment-specific warning signal found*, and a caution about a missing reference is such a
 *    signal, so what the lead has to settle is the engine's `CHECK` threshold (any `WARNING`, or two
 *    `CAUTION`s). Until it is settled, a claimed success that nobody can verify reads as `CLEAR`.
 *  * **L02** (registered payee, large amount) - expected `CHECK`, measured `CLEAR` with hints
 *    `[AMOUNT_LARGE, PAYEE_MATCHES_REGISTERED, UTR_PRESENT]`. Same threshold, benign direction: the
 *    same contradiction, on the user's own registered shop.
 *  * **S10** (QR stand: printed name, wrong UPI) - expected `CHECK`, measured `CHECK`, but
 *    `PAYEE_NAME_SPOOFABLE` never appears; the hints were
 *    `[PAYEE_NOT_REGISTERED, STATUS_UNKNOWN, TIME_MISSING]`. The parser only reads a printed payee
 *    name after a cue (`paid to`, `pn=`, `to:`), while a stand prints the shop name as a bare first
 *    line - the most common layout on a real counter. The engine has the rule; the parser never
 *    feeds it.
 *
 * The three rows are also why the summary gates fail: `no suspicious row comes out clear` names S11,
 * and the table test names S10. Deleting a row or relaxing a note would hide the finding, so neither
 * is done here.
 *
 * Plain JVM only: no Android framework object, no device, no network, no storage. If the advisory
 * ever needed one of those, this class could not run.
 */
class PaymentFixturesTest {

    private val engine = PaymentAdvisoryEngine()

    // ---------------------------------------------------------------------------------------
    // The row-under-test harness
    // ---------------------------------------------------------------------------------------

    /**
     * Builds the advisory exactly the way a caller does: parse the receipt, compare the parsed UPI
     * id against the registered payees the row carries, and hand the engine plain values only.
     */
    private fun advise(row: PaymentFixtures.Row): PaymentAdvisory {
        val parsed = PaymentFieldsParser.parse(row.text)
        val input = PaymentAdvisoryInput(
            sourceText = row.messageText,
            fields = parsed,
            registeredPayeeCount = row.registeredPayees.size,
            payeeMatchesRegistered = parsed.payeeUpi.isNotBlank() &&
                row.registeredPayees.any {
                    it.trim().equals(parsed.payeeUpi.trim(), ignoreCase = true)
                },
            nowEpochMs = PaymentFixtures.FIXED_NOW_EPOCH_MS,
            ocrUsed = row.ocrUsed,
        )
        return engine.evaluate(input)
    }

    /** Asserts a row against its own declared expectations and hands the advisory back. */
    private fun assertRow(row: PaymentFixtures.Row): PaymentAdvisory {
        val advisory = advise(row)
        val produced = advisory.hints.map { it.kind }.toSet()
        val where = "row ${row.id} (${row.note})"

        assertEquals(
            "$where: verdict must be ${row.expectedVerdict}, hints produced=$produced",
            row.expectedVerdict,
            advisory.verdict,
        )
        for (kind in row.expectedKindsPresent) {
            assertTrue("$where: hint $kind is missing, hints produced=$produced", kind in produced)
        }
        for (kind in row.expectedKindsAbsent) {
            assertFalse("$where: hint $kind must not appear, hints produced=$produced", kind in produced)
        }
        return advisory
    }

    private fun rowById(id: String): PaymentFixtures.Row =
        PaymentFixtures.byId(id) ?: throw AssertionError("row '$id' disappeared from PaymentFixtures.rows")

    /**
     * `WARNING` first, then `CAUTION`, then `INFO` - the display order the contract requires of
     * [PaymentAdvisory.hints]. Deliberately not `PaymentHintSeverity.ordinal`, which is the
     * declaration order (INFO, CAUTION, WARNING) and the reverse of the required display order.
     */
    private fun severityRank(severity: PaymentHintSeverity): Int = when (severity) {
        PaymentHintSeverity.WARNING -> 0
        PaymentHintSeverity.CAUTION -> 1
        PaymentHintSeverity.INFO -> 2
    }

    // ---------------------------------------------------------------------------------------
    // The table: all twenty-four rows, one failure message per row
    // ---------------------------------------------------------------------------------------

    @Test
    fun `every row matches its expected verdict and hint set`() {
        for (row in PaymentFixtures.rows) {
            assertRow(row)
        }
    }

    // ---------------------------------------------------------------------------------------
    // Inventory: a deletion or a silent re-label must not pass
    // ---------------------------------------------------------------------------------------

    @Test
    fun `there are exactly twelve suspicious and twelve legitimate rows with unique ids`() {
        assertEquals(
            "the table ships exactly 24 rows; a missing one hides a scenario",
            PaymentFixtures.ROW_COUNT,
            PaymentFixtures.rows.size,
        )
        assertEquals(24, PaymentFixtures.rows.size)
        assertEquals(
            "exactly 12 suspicious-shaped rows are required",
            PaymentFixtures.SUSPICIOUS_COUNT,
            PaymentFixtures.suspiciousRows.size,
        )
        assertEquals(12, PaymentFixtures.suspiciousRows.size)
        assertEquals(
            "exactly 12 legitimate-shaped rows are required",
            PaymentFixtures.LEGITIMATE_COUNT,
            PaymentFixtures.legitimateRows.size,
        )
        assertEquals(12, PaymentFixtures.legitimateRows.size)
        assertEquals(
            "every row must belong to exactly one block",
            24,
            PaymentFixtures.suspiciousRows.size + PaymentFixtures.legitimateRows.size,
        )
        assertEquals(
            "ids must be unique",
            PaymentFixtures.rows.size,
            PaymentFixtures.rows.map { it.id }.toSet().size,
        )
        assertEquals(
            "the fixture clock is part of the documented contract",
            1_700_000_000_000L,
            PaymentFixtures.FIXED_NOW_EPOCH_MS,
        )
        assertEquals(
            "the four message-shaped rows are the ones the payment card must stay hidden for",
            4,
            PaymentFixtures.nonReceiptIds.size,
        )

        for (row in PaymentFixtures.rows) {
            val where = "row ${row.id} (${row.note})"
            assertTrue("$where: id must not be blank", row.id.isNotBlank())
            assertTrue("$where: note must explain the row", row.note.isNotBlank())
            assertTrue("$where: text must not be blank", row.text.isNotBlank())
            assertTrue(
                "$where: the message may be blank, but the parsed text may not be",
                row.text.trim().isNotEmpty(),
            )
            assertTrue(
                "$where: a kind cannot be both required and forbidden",
                row.expectedKindsPresent.none { it in row.expectedKindsAbsent },
            )
            if (row.id.startsWith("S")) {
                assertTrue(
                    "$where: a suspicious-shaped row must declare at least one required hint",
                    row.expectedKindsPresent.isNotEmpty(),
                )
                assertFalse(
                    "$where: a suspicious-shaped row must never declare CLEAR as its expectation",
                    row.expectedVerdict == PaymentAdvisoryVerdict.CLEAR,
                )
            } else {
                assertFalse(
                    "$where: a legitimate-shaped row must never declare STOP as its expectation",
                    row.expectedVerdict == PaymentAdvisoryVerdict.STOP,
                )
            }
        }

        assertTrue(
            "every non-receipt id must exist in the table: ${PaymentFixtures.nonReceiptIds}",
            PaymentFixtures.nonReceiptIds.all { PaymentFixtures.byId(it) != null },
        )
        assertNull(
            "byId must return null, not throw, for an unknown id",
            PaymentFixtures.byId("no-such-row"),
        )
    }

    // ---------------------------------------------------------------------------------------
    // Synthetic content guards
    // ---------------------------------------------------------------------------------------

    @Test
    fun `no row carries a real looking mobile number bank domain or url`() {
        // An Indian mobile number is ten digits starting 6-9. The lookarounds matter: the 12-digit
        // demo UTRs contain such runs internally, and a run inside a longer number is not a phone.
        val indianMobile = Regex("""(?<![0-9])[6-9][0-9]{9}(?![0-9])""")
        val anyThirteenDigits = Regex("""[0-9]{13,}""")
        val uiaToken = Regex("""[A-Za-z0-9._-]+@[A-Za-z0-9.-]+""")
        val demoHandles = setOf("okaxis", "okhdfcbank", "okicici", "ybl")
        val realBankDomains = listOf(
            "sbi.co.in",
            "onlinesbi",
            "hdfcbank.com",
            "icicibank.com",
            "axisbank.com",
            "kotak.com",
            "yesbank.in",
            "bankofbaroda",
        )
        val forbiddenSubstrings = listOf("http", "www.", "bit.ly", "+91", "@gmail", "@yahoo")

        for (row in PaymentFixtures.rows) {
            val where = "row ${row.id} (${row.note})"
            val both = row.text + " " + row.messageText
            val lowered = both.lowercase()

            assertFalse(
                "$where: '$both' contains a run shaped like an Indian mobile number",
                indianMobile.containsMatchIn(both),
            )
            assertFalse(
                "$where: a 13+ digit run is a card or account number, never a demo token",
                anyThirteenDigits.containsMatchIn(both),
            )
            for (domain in realBankDomains) {
                assertFalse(
                    "$where: a real bank domain ('$domain') must not appear in a fixture",
                    lowered.contains(domain),
                )
            }
            for (forbidden in forbiddenSubstrings) {
                assertFalse(
                    "$where: '$forbidden' must not appear in a fixture ('$both')",
                    lowered.contains(forbidden.lowercase()),
                )
            }
            for (token in uiaToken.findAll(both).map { it.value }) {
                val handle = token.substringAfter('@').lowercase()
                assertTrue(
                    "$where: '$token' must use a demo-only UPI handle, was '$handle'",
                    handle in demoHandles,
                )
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // The hidden-card guard: a message is not a payment screenshot
    // ---------------------------------------------------------------------------------------

    @Test
    fun `message shaped rows are not reported as payment screenshots`() {
        for (id in PaymentFixtures.nonReceiptIds) {
            val row = rowById(id)
            val advisory = advise(row)
            assertFalse(
                "row $id (${row.note}): text '${row.text}' is not a payment screen, so " +
                    "isPaymentScreenshot must stay false and the payment card hidden; " +
                    "hints=${advisory.hints.map { it.kind }}",
                advisory.isPaymentScreenshot,
            )
        }

        // The flag must not simply be hardcoded false: a genuine receipt still has to be seen.
        val receipt = rowById("L04")
        assertTrue(
            "row L04 (${receipt.note}): a complete success receipt must still be recognised as a " +
                "payment screenshot, otherwise the card never appears at all",
            advise(receipt).isPaymentScreenshot,
        )
    }

    // ---------------------------------------------------------------------------------------
    // The two direction-of-error gates, stated on their own
    // ---------------------------------------------------------------------------------------

    @Test
    fun `no suspicious row comes out clear`() {
        val silent = PaymentFixtures.suspiciousRows
            .filter { advise(it).verdict == PaymentAdvisoryVerdict.CLEAR }
            .map { it.id }
        assertTrue(
            "these suspicious-shaped rows produced CLEAR, which is the app failing to warn: $silent",
            silent.isEmpty(),
        )
    }

    @Test
    fun `no legitimate row reaches stop`() {
        val cryingWolf = PaymentFixtures.legitimateRows
            .filter { advise(it).verdict == PaymentAdvisoryVerdict.STOP }
            .map { it.id }
        assertTrue(
            "these legitimate-shaped rows reached STOP, which is a false alarm on a real " +
                "payment: $cryingWolf",
            cryingWolf.isEmpty(),
        )
    }

    // ---------------------------------------------------------------------------------------
    // Highest-value rows, one method each so a failure names the scenario not the table
    // ---------------------------------------------------------------------------------------

    @Test
    fun `S01 unregistered upi with a large amount stops`() {
        val row = rowById("S01")
        val advisory = assertRow(row)
        assertEquals(
            "row S01 (${row.note}): paying a large amount to a handle that is not the registered " +
                "shop must reach STOP",
            PaymentAdvisoryVerdict.STOP,
            advisory.verdict,
        )
        assertTrue(
            "row S01: the parsed payee must be the unregistered handle, was '${advisory.fields.maskedUpi}'",
            advisory.fields.payeeUpi.equals("demo.shop@okaxis", ignoreCase = true),
        )
    }

    @Test
    fun `S02 unregistered upi with urgency keeps the urgency hint`() {
        val row = rowById("S02")
        val advisory = assertRow(row)
        assertTrue(
            "row S02 (${row.note}): 'jaldi karo abhi' must produce an urgency hint",
            advisory.hints.any { it.kind == PaymentHintKind.URGENCY_PRESSURE },
        )
        assertTrue(
            "row S02: an urgency hint is at least a caution, never INFO-only",
            advisory.hints.any {
                it.kind == PaymentHintKind.URGENCY_PRESSURE &&
                    it.severity != PaymentHintSeverity.INFO
            },
        )
    }

    @Test
    fun `S03 pending receipt claimed as paid is never read as settled`() {
        val row = rowById("S03")
        val advisory = assertRow(row)
        assertEquals(
            "row S03 (${row.note}): a pending receipt the sender calls paid is the contradiction " +
                "that must reach STOP",
            PaymentAdvisoryVerdict.STOP,
            advisory.verdict,
        )
        assertFalse(
            "row S03: the receipt says pending, so the parsed status must not be SUCCESS",
            advisory.fields.status == PaymentStatus.SUCCESS,
        )
    }

    @Test
    fun `S04 failed payment followed by pay again now stops`() {
        val row = rowById("S04")
        val advisory = assertRow(row)
        assertEquals(
            "row S04 (${row.note}): a failed receipt plus 'abhi dubara pay karo' must reach STOP",
            PaymentAdvisoryVerdict.STOP,
            advisory.verdict,
        )
        assertTrue(
            "row S04: the failed status must survive parsing as FAILED",
            advisory.fields.status == PaymentStatus.FAILED,
        )
    }

    @Test
    fun `S05 round refund lure is pinned as a round large amount`() {
        val row = rowById("S05")
        val advisory = assertRow(row)
        val roundHint = advisory.hints.firstOrNull { it.kind == PaymentHintKind.AMOUNT_ROUND_LARGE }
        assertTrue(
            "row S05 (${row.note}): the ₹5,000 refund lure must produce AMOUNT_ROUND_LARGE",
            roundHint != null,
        )
        assertEquals(
            "row S05: the round-amount hint must carry the amount it is about, in paise",
            500_000L,
            roundHint?.amountMinor,
        )
    }

    @Test
    fun `S07 one mismatch on a complete receipt is check not stop`() {
        val row = rowById("S07")
        val advisory = assertRow(row)
        assertEquals(
            "row S07 (${row.note}): a registration-fee receipt that is complete, sub-large and " +
                "unpressured has exactly one signal - the wrong payee - so CHECK is the verdict",
            PaymentAdvisoryVerdict.CHECK,
            advisory.verdict,
        )
        assertFalse(
            "row S07: STOP here would flag every payment to a shop the user has not registered yet",
            advisory.verdict == PaymentAdvisoryVerdict.STOP,
        )
        assertTrue(
            "row S07: the printed reference must be kept, it is what makes the receipt complete",
            advisory.hints.any { it.kind == PaymentHintKind.UTR_PRESENT },
        )
    }

    @Test
    fun `S08 electricity fee to a look-alike personal handle stops`() {
        val row = rowById("S08")
        val advisory = assertRow(row)
        assertEquals(
            "row S08 (${row.note}): the look-alike handle must not match the registered board " +
                "payee, and the row must reach STOP",
            PaymentAdvisoryVerdict.STOP,
            advisory.verdict,
        )
        assertFalse(
            "row S08: demo.bijlihelp is not demo.bijli, so no match may be reported",
            advisory.hints.any { it.kind == PaymentHintKind.PAYEE_MATCHES_REGISTERED },
        )
    }

    @Test
    fun `S10 printed shop name over a different upi is caught`() {
        val row = rowById("S10")
        val advisory = assertRow(row)
        assertTrue(
            "row S10 (${row.note}): the printed shop name must be reported as a spoofable label - " +
                "a stand that says Sharma Kirana Store while carrying demo.kirana2 is the whole " +
                "reason the name is never treated as evidence",
            advisory.hints.any { it.kind == PaymentHintKind.PAYEE_NAME_SPOOFABLE },
        )
        assertEquals(
            "row S10: one mismatch on a single stand is CHECK (BEHAVIOR_SPEC needs three " +
                "consecutive valid mismatches before a warning), not STOP",
            PaymentAdvisoryVerdict.CHECK,
            advisory.verdict,
        )
        assertTrue(
            "row S10: the mismatch itself must still be reported, the name may not hide it",
            advisory.hints.any { it.kind == PaymentHintKind.PAYEE_NOT_REGISTERED },
        )
    }

    @Test
    fun `S11 success without a reference is check not stop`() {
        val row = rowById("S11")
        val advisory = assertRow(row)
        assertTrue(
            "row S11 (${row.note}): 'Successful' with no UTR must produce UTR_MISSING",
            advisory.hints.any { it.kind == PaymentHintKind.UTR_MISSING },
        )
        assertFalse(
            "row S11: the payee is registered and there is no pressure, so STOP would be too " +
                "strong; CHECK is the honest verdict",
            advisory.verdict == PaymentAdvisoryVerdict.STOP,
        )
    }

    @Test
    fun `S12 OCR-corrupted receipt does not invent a readable status`() {
        val row = rowById("S12")
        val advisory = assertRow(row)
        assertTrue(
            "row S12 (${row.note}): OCR text must produce SCREENSHOT_ONLY",
            advisory.hints.any { it.kind == PaymentHintKind.SCREENSHOT_ONLY },
        )
        assertTrue("row S12: the row must declare OCR input", row.ocrUsed)
        assertEquals(
            "row S12: damaged status letters must not be repaired into SUCCESS or PENDING",
            PaymentStatus.UNKNOWN,
            advisory.fields.status,
        )
        assertTrue(
            "row S12: keep the urgency and payee-mismatch signals despite OCR status loss",
            advisory.hints.map { it.kind }.containsAll(
                listOf(PaymentHintKind.URGENCY_PRESSURE, PaymentHintKind.PAYEE_NOT_REGISTERED),
            ),
        )
    }

    @Test
    fun `L01 registered payee small amount is clear`() {
        val row = rowById("L01")
        val advisory = assertRow(row)
        assertEquals(
            "row L01 (${row.note}): a complete ₹250 receipt to the registered payee must be CLEAR",
            PaymentAdvisoryVerdict.CLEAR,
            advisory.verdict,
        )
        assertEquals("row L01: no warning may be raised", emptyList<PaymentHint>(), advisory.warnings)
    }

    @Test
    fun `L02 registered payee large amount stays below stop`() {
        val row = rowById("L02")
        val advisory = assertRow(row)
        assertTrue(
            "row L02 (${row.note}): the large-amount signal must be visible for a registered " +
                "payee too, it is the reason this row is CHECK and not CLEAR",
            advisory.hints.any { it.kind == PaymentHintKind.AMOUNT_LARGE },
        )
        assertFalse(
            "row L02: paying your own registered shop a large sum is not a STOP case",
            advisory.verdict == PaymentAdvisoryVerdict.STOP,
        )
    }

    @Test
    fun `L04 complete receipt to a registered payee is clear`() {
        val row = rowById("L04")
        val advisory = assertRow(row)
        assertEquals(
            "row L04 (${row.note}): success plus UTR plus a registered payee is the CLEAR shape",
            PaymentAdvisoryVerdict.CLEAR,
            advisory.verdict,
        )
        assertTrue(
            "row L04: the printed UTR must be kept, not read as missing",
            advisory.hints.any { it.kind == PaymentHintKind.UTR_PRESENT },
        )
        assertTrue(
            "row L04: the confirmation line is not optional on any payment screen",
            advisory.confirmationRequired,
        )
    }

    @Test
    fun `L05 failed receipt with no pressure is not a stop`() {
        val row = rowById("L05")
        val advisory = assertRow(row)
        assertFalse(
            "row L05 (${row.note}): a failed receipt nobody pushed must not reach STOP",
            advisory.verdict == PaymentAdvisoryVerdict.STOP,
        )
        assertFalse(
            "row L05: 'try again later' is not pressure, so no urgency hint may appear",
            advisory.hints.any { it.kind == PaymentHintKind.URGENCY_PRESSURE },
        )
    }

    @Test
    fun `L10 small payment to an unregistered payee is check at most`() {
        val row = rowById("L10")
        val advisory = assertRow(row)
        assertEquals(
            "row L10 (${row.note}): one mismatch on a ₹49 tea bill is CHECK, not STOP",
            PaymentAdvisoryVerdict.CHECK,
            advisory.verdict,
        )
        assertFalse(
            "row L10: STOP on a ₹49 payment would be crying wolf",
            advisory.verdict == PaymentAdvisoryVerdict.STOP,
        )
    }

    @Test
    fun `L03 credit alert is not a payment screenshot and carries no warning`() {
        val row = rowById("L03")
        val advisory = assertRow(row)
        assertFalse(
            "row L03 (${row.note}): a credit alert SMS is not a payment screen",
            advisory.isPaymentScreenshot,
        )
        assertEquals(
            "row L03: an incoming credit is informational, so no warning is honest here",
            emptyList<PaymentHint>(),
            advisory.warnings,
        )
    }

    // ---------------------------------------------------------------------------------------
    // Contract-level properties that hold for every row
    // ---------------------------------------------------------------------------------------

    @Test
    fun `every advisory asks for the bank confirmation line`() {
        for (row in PaymentFixtures.rows) {
            val advisory = advise(row)
            assertTrue(
                "row ${row.id} (${row.note}): confirmationRequired must always be true - no " +
                    "screenshot proves settlement",
                advisory.confirmationRequired,
            )
            val ranks = advisory.hints.map { severityRank(it.severity) }
            assertTrue(
                "row ${row.id}: hints must be ordered WARNING, then CAUTION, then INFO, was " +
                    "${advisory.hints.map { it.severity }}",
                ranks == ranks.sorted(),
            )
        }
    }

    @Test
    fun `benign payment notice stays distinct from an added transfer ask and pressure`() {
        val benignNotice = rowById("L03")
        val pressuredAsk = benignNotice.copy(
            id = "L03-adversarial-ask",
            note = "same informational credit notice with an added urgent request to return money",
            messageText = "Rs 5,000 credited. Galti se bheja, abhi turant wapas isi UPI par bhejo. " +
                "Kisi ko mat batana.",
        )

        val benign = advise(benignNotice)
        val adversarial = advise(pressuredAsk)

        assertFalse("L03: an informational incoming-credit notice is not a payment screenshot", benign.isPaymentScreenshot)
        assertFalse("L03: a credit notice alone must not invent a transfer ask", benign.hints.any {
            it.kind == PaymentHintKind.URGENCY_PRESSURE
        })
        assertTrue("L03-adversarial-ask: the added immediate transfer request must be surfaced", adversarial.hints.any {
            it.kind == PaymentHintKind.URGENCY_PRESSURE
        })
        assertFalse("L03-adversarial-ask: a message is still not a payment screenshot", adversarial.isPaymentScreenshot)
    }

    @Test
    fun `receipt screenshot remains non-proof even when its parsed status says success`() {
        val receipt = rowById("L04")
        val advisory = advise(receipt)

        assertTrue("L04 fixture should exercise screenshot recognition", advisory.isPaymentScreenshot)
        assertEquals("L04 fixture status should be parsed from its receipt", PaymentStatus.SUCCESS, advisory.fields.status)
        assertTrue("L04: a screenshot must always be paired with bank-history confirmation", advisory.confirmationRequired)
        assertTrue(
            "L04: success text is not proof and must not be represented as a success/fraud warning",
            advisory.hints.none { it.kind == PaymentHintKind.URGENCY_PRESSURE },
        )
    }

    @Test
    fun `evaluating the same row twice yields the same verdict and hints`() {
        for (row in PaymentFixtures.rows) {
            val first = advise(row)
            val second = advise(row)
            assertEquals(
                "row ${row.id} (${row.note}): verdict must be deterministic",
                first.verdict,
                second.verdict,
            )
            assertEquals(
                "row ${row.id}: hint kinds must be deterministic",
                first.hints.map { it.kind },
                second.hints.map { it.kind },
            )
            assertEquals(
                "row ${row.id}: isPaymentScreenshot must be deterministic",
                first.isPaymentScreenshot,
                second.isPaymentScreenshot,
            )
        }
    }
}
