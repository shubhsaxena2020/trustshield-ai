package ai.trustshield.android.core.payment

/**
 * The payment-advisory fixture table (Lane B, item 8 / A09).
 *
 * Twenty-four hand-written, synthetic texts: twelve **suspicious-shaped** payments (`S01`-`S12`) and
 * twelve **legitimate-shaped** payments (`L01`-`L12`). Each row states the verdict it *expects* and
 * the hint kinds it requires or forbids. [PaymentFixturesTest] runs every row through the real
 * `PaymentFieldsParser` and `PaymentAdvisoryEngine` and names the row that disagrees.
 *
 * ## What this table is, and is not
 *
 * These are **rule fixtures**, not a quality measurement. They are not sampled from any evaluation
 * set, they move no reported number, and a green run means only "the payment rules still do on
 * twenty-four demo texts what the spec says they do". Nothing here claims a screenshot proves
 * settlement, and `CLEAR` is never read as "safe to pay" — the contract defines it as "no
 * payment-specific warning signal found".
 *
 * ## The rule table the expectations come from
 *
 * `docs/android/BEHAVIOR_SPEC.md` states the payment position (parse, compare `pa` against
 * registered payees, `pn` is a spoofable label, a screenshot is never proof of settlement) and
 * `PaymentAdvisoryContract` states what each hint kind means, but neither fixes a verdict per
 * pattern. The four gates below are the reading this table is written against, so a disagreement
 * points at one named gate instead of at "the verdict felt wrong":
 *
 *  - **G1 — at least `CHECK`.** Any one of: the screenshot's UPI id is not a registered payee
 *    (`PAYEE_NOT_REGISTERED`), an amount at or above `PaymentAdvisoryPolicy.largeAmountMinor`,
 *    a pending or failed status, "success" with no reference printed, screenshot-only evidence, or
 *    no readable status at all.
 *  - **G2 — `STOP`.** Two or more independent G1 signals, or any G1 signal alongside urgency
 *    pressure in the accompanying message, or a settlement contradiction: a receipt that says
 *    pending/failed while the sender claims the money has already gone.
 *  - **G3 — `CLEAR`.** A registered payee, a modest amount, a readable success status with a UTR,
 *    and no pressure in the message.
 *  - **G4 — not a payment at all.** A credit alert, a cashback line, a delivery OTP or an ordinary
 *    debit message is not a payment screenshot; it is `CLEAR` whatever its amount, and the payment
 *    card must stay hidden (`PaymentAdvisory.isPaymentScreenshot == false`).
 *
 * Two rules hold across all four: a printed payee name (`pn`) never counts as a match, only the UPI
 * id does; and no row is allowed to be `CLEAR` if it carries a G1 or G2 signal — that would be the
 * app failing to warn, which is the expensive direction of error.
 *
 * The parser and engine are checked against all 24 rows by [PaymentFixturesTest].
 *
 * ## The fixed clock
 *
 * [FIXED_NOW_EPOCH_MS] is `2023-11-14T22:13:20Z` (`14 Nov 2023, 10:13 pm` IST) — the instant tests
 * inject as "now". Receipt timestamps are therefore dated a few minutes to a couple of hours before
 * that, deliberately: a receipt dated 2026 next to a 2023 clock would be a *future* timestamp, and
 * the recency rule would then be measured against nonsense instead of against the receipt. If a
 * `TIME_*` hint ever fires here it is because of this offset, so no row pins a `TIME_*` kind.
 *
 * ## Synthetic-content rules (checked by the test, not just promised here)
 *
 * Every name, shop, handle and reference is invented. UPI handles use demo-only app domains
 * (`@okaxis`, `@okhdfcbank`, `@okicici`, `@ybl`), no row contains a URL, an international prefix, a
 * 10-digit run that could be an Indian mobile number, or a real bank domain, and the only 12-digit
 * runs are obviously-sequential demo UTRs like `412345678901`. No real person's data appears
 * anywhere in this file.
 *
 * @property id stable id. `S` prefix is suspicious-shaped, `L` prefix is legitimate-shaped; tests,
 *   the miss report and any demo script key off the id, never off list position.
 * @property note one line of self-documentation: what the row is and which gate it tests. Blank
 *   notes are rejected, so a row can never arrive without its reasoning.
 * @property text the payment text itself: the receipt / QR stand / request the fields are parsed
 *   from. Synthetic.
 * @property messageText the chat message that arrived with the picture, if any. This is what the
 *   urgency rule sees (`PaymentAdvisoryInput.sourceText`); blank when the text *is* the message
 *   (an SMS or an alert), in which case the row passes the text as the message too.
 * @property registeredPayees the payees already registered on this phone, as normalised UPI ids.
 *   Empty means nothing was registered, so no comparison was possible.
 * @property expectedVerdict the verdict this row pins. Chosen from the gates above before the
 *   engine was ever run.
 * @property expectedKindsPresent hint kinds that must appear. Kept to kinds that follow directly
 *   from an unambiguous feature of [text] (a printed UPI id, a status word, an amount, a pressure
 *   word), never to kinds that depend on parser subtlety.
 * @property expectedKindsAbsent hint kinds that must NOT appear. Used where the row would be a
 *   false alarm: a legitimate notice must not be reported as an unregistered payee, a protected
 *   receipt must not be reported as missing its reference, and a non-payment must not be reported
 *   as a screenshot.
 * @property ocrUsed true when the text came off an image rather than a paste ([text] is then the
 *   OCR output).
 */
object PaymentFixtures {

    data class Row(
        val id: String,
        val note: String,
        val text: String,
        val messageText: String = "",
        val registeredPayees: List<String> = emptyList(),
        val expectedVerdict: PaymentAdvisoryVerdict,
        val expectedKindsPresent: Set<PaymentHintKind> = emptySet(),
        val expectedKindsAbsent: Set<PaymentHintKind> = emptySet(),
        val ocrUsed: Boolean = false,
    )

    /** `14 Nov 2023, 10:13 pm` IST — the instant every row is evaluated against. */
    const val FIXED_NOW_EPOCH_MS: Long = 1_700_000_000_000L

    /** How many rows this table ships. A deletion must not pass silently. */
    const val ROW_COUNT: Int = 24

    /** Suspicious-shaped rows: the app may warn, and must never say `CLEAR`. */
    const val SUSPICIOUS_COUNT: Int = 12

    /** Legitimate-shaped rows: the app must not cry wolf, and must never reach `STOP`. */
    const val LEGITIMATE_COUNT: Int = 12

    private const val SUSPICIOUS_PREFIX = "S"
    private const val LEGITIMATE_PREFIX = "L"

    /** The shop the user actually registered — the "real" payee behind the sticker-swap rows. */
    private const val PAYEE_KIRANA = "demo.kirana@okhdfcbank"

    /** The electricity board payee, used where a look-alike personal handle is the whole trick. */
    private const val PAYEE_BIJLI = "demo.bijli@okhdfcbank"

    private const val STAMP = "Date: 14 Nov 2023, "

    /**
     * Rows whose text is a message, an alert or a code — not a payment screen at all. The advisory
     * must report `isPaymentScreenshot = false` for each of these so the payment card stays hidden
     * (gate G4). Listed by id rather than derived, because "is this a receipt" is exactly the thing
     * the guard must not take from the engine it is checking.
     */
    val nonReceiptIds: Set<String> = setOf("L03", "L07", "L08", "L12")

    /**
     * The twenty-four rows, stable order, suspicious block first (the order the demo walks).
     */
    val rows: List<Row> = listOf(

        // -------------------------------------------------------------------------------------
        // Suspicious-shaped: S01-S12
        // -------------------------------------------------------------------------------------

        Row(
            id = "S01",
            note = "unregistered UPI + large amount: a complete-looking success receipt paid to a " +
                "handle that is not the registered shop (G1 mismatch + G1 large -> G2 STOP)",
            text = """
                Payment Successful
                ₹18,500
                To: demo.shop@okaxis
                ${STAMP}9:48 pm
            """.trimIndent(),
            registeredPayees = listOf(PAYEE_KIRANA),
            expectedVerdict = PaymentAdvisoryVerdict.STOP,
            expectedKindsPresent = setOf(
                PaymentHintKind.PAYEE_NOT_REGISTERED,
                PaymentHintKind.AMOUNT_LARGE,
            ),
            expectedKindsAbsent = setOf(
                PaymentHintKind.PAYEE_MATCHES_REGISTERED,
                PaymentHintKind.UTR_PRESENT,
            ),
        ),
        Row(
            id = "S02",
            note = "unregistered UPI + large amount + 'jaldi karo abhi': the same receipt as S01 " +
                "with pressure in the message, so urgency is pinned as a third signal (G2)",
            text = """
                Payment Successful
                ₹14,250
                To: demo.shop@okaxis
                ${STAMP}9:44 pm
            """.trimIndent(),
            messageText = "Aap ye UPI par ₹14,250 bhej do, jaldi karo abhi. Item sirf aaj ke liye " +
                "rakha hai.",
            registeredPayees = listOf(PAYEE_KIRANA),
            expectedVerdict = PaymentAdvisoryVerdict.STOP,
            expectedKindsPresent = setOf(
                PaymentHintKind.PAYEE_NOT_REGISTERED,
                PaymentHintKind.AMOUNT_LARGE,
                PaymentHintKind.URGENCY_PRESSURE,
            ),
            expectedKindsAbsent = setOf(PaymentHintKind.PAYEE_MATCHES_REGISTERED),
        ),
        Row(
            id = "S03",
            note = "pending status claimed as paid: the receipt says pending, the sender says the " +
                "money has gone; that contradiction is the STOP, not the pending word alone",
            text = """
                Status: Pending
                ₹9,000
                To: demo.shop@okaxis
                ${STAMP}9:20 pm
            """.trimIndent(),
            messageText = "Payment ho gaya bhai, screenshot bheja hai. Aap saman pack kar do.",
            registeredPayees = listOf(PAYEE_KIRANA),
            expectedVerdict = PaymentAdvisoryVerdict.STOP,
            expectedKindsPresent = setOf(
                PaymentHintKind.STATUS_PENDING,
                PaymentHintKind.PAYEE_NOT_REGISTERED,
            ),
            expectedKindsAbsent = setOf(
                PaymentHintKind.PAYEE_MATCHES_REGISTERED,
                PaymentHintKind.STATUS_FAILED,
            ),
        ),
        Row(
            id = "S04",
            note = "failed payment followed by 'pay again now': the money never moved, so the " +
                "second transfer is asked for under pressure (G1 failed + G1 mismatch + urgency)",
            text = """
                Payment Failed
                ₹7,500
                To: demo.shop@okaxis
                ${STAMP}9:05 pm
            """.trimIndent(),
            messageText = "Payment fail dikha raha hai, aap abhi dubara pay karo turant.",
            registeredPayees = listOf(PAYEE_KIRANA),
            expectedVerdict = PaymentAdvisoryVerdict.STOP,
            expectedKindsPresent = setOf(
                PaymentHintKind.STATUS_FAILED,
                PaymentHintKind.PAYEE_NOT_REGISTERED,
                PaymentHintKind.URGENCY_PRESSURE,
            ),
            expectedKindsAbsent = setOf(
                PaymentHintKind.STATUS_PENDING,
                PaymentHintKind.PAYEE_MATCHES_REGISTERED,
            ),
        ),
        Row(
            id = "S05",
            note = "large round refund lure: ₹5,000 'refund' that can only be released by paying " +
                "₹5,000 first; the round figure is pinned as its own lure hint",
            text = """
                Refund Approved
                ₹5,000
                To: demo.refund@okaxis
                ${STAMP}8:55 pm
            """.trimIndent(),
            messageText = "Aapka ₹5,000 ka refund approve hua hai. Refund process karne ke liye " +
                "pehle ye UPI par ₹5,000 bhejo, turant wapas milega.",
            registeredPayees = listOf(PAYEE_KIRANA),
            expectedVerdict = PaymentAdvisoryVerdict.STOP,
            expectedKindsPresent = setOf(
                PaymentHintKind.AMOUNT_ROUND_LARGE,
                PaymentHintKind.PAYEE_NOT_REGISTERED,
            ),
            expectedKindsAbsent = setOf(PaymentHintKind.PAYEE_MATCHES_REGISTERED),
        ),
        Row(
            id = "S06",
            note = "lottery / KBC processing-fee receipt: a prize that costs money up front, to an " +
                "unregistered handle, still pending (G1 mismatch + G1 pending -> G2 STOP)",
            text = """
                KBC Lottery 2026 Winner
                Processing Fee ₹4,500
                Pay To: demo.kbc@okicici
                Status: Pending
                ${STAMP}8:30 pm
            """.trimIndent(),
            messageText = "Sir aapko KBC se 25 lakh mila hai. Ye processing fee jaldi bhejo, warna " +
                "prize cancel ho jayega.",
            registeredPayees = listOf(PAYEE_KIRANA),
            expectedVerdict = PaymentAdvisoryVerdict.STOP,
            expectedKindsPresent = setOf(
                PaymentHintKind.PAYEE_NOT_REGISTERED,
                PaymentHintKind.STATUS_PENDING,
            ),
            expectedKindsAbsent = setOf(PaymentHintKind.PAYEE_MATCHES_REGISTERED),
        ),
        Row(
            id = "S07",
            note = "job-registration fee receipt, sub-large, complete and unpressured: only the " +
                "payee is wrong, so this is the one-mismatch row and pins CHECK, not STOP",
            text = """
                Job Registration Fee
                ₹1,999
                To: demo.jobs@okaxis
                UTR: 412345678907
                ${STAMP}8:10 pm
            """.trimIndent(),
            messageText = "Registration fee bhar do, job pakki ho jayegi.",
            registeredPayees = listOf(PAYEE_KIRANA),
            expectedVerdict = PaymentAdvisoryVerdict.CHECK,
            expectedKindsPresent = setOf(
                PaymentHintKind.PAYEE_NOT_REGISTERED,
                PaymentHintKind.UTR_PRESENT,
            ),
            expectedKindsAbsent = setOf(
                PaymentHintKind.PAYEE_MATCHES_REGISTERED,
                PaymentHintKind.URGENCY_PRESSURE,
            ),
        ),
        Row(
            id = "S08",
            note = "electricity disconnection fee to a personal look-alike handle: demo.bijlihelp " +
                "next to the registered demo.bijli, pending, with 'aaj raat' pressure",
            text = """
                Bijli Bill Disconnection Notice
                Reconnection Fee ₹1,250
                Pay To: demo.bijlihelp@okaxis
                Status: Pending
                ${STAMP}7:50 pm
            """.trimIndent(),
            messageText = "Aaj raat 10 baje connection kat jayega. Abhi isi UPI par fee bhejo.",
            registeredPayees = listOf(PAYEE_BIJLI),
            expectedVerdict = PaymentAdvisoryVerdict.STOP,
            expectedKindsPresent = setOf(
                PaymentHintKind.PAYEE_NOT_REGISTERED,
                PaymentHintKind.STATUS_PENDING,
                PaymentHintKind.URGENCY_PRESSURE,
            ),
            expectedKindsAbsent = setOf(
                PaymentHintKind.PAYEE_MATCHES_REGISTERED,
                PaymentHintKind.AMOUNT_LARGE,
            ),
        ),
        Row(
            id = "S09",
            note = "courier / customs clearance fee: a parcel held hostage for a fee paid to an " +
                "unregistered handle, pending, under time pressure (G2 STOP)",
            text = """
                Customs Clearance Fee
                ₹2,400
                Pay To: demo.courier@okaxis
                Status: Pending
                ${STAMP}7:20 pm
            """.trimIndent(),
            messageText = "Aapka parcel customs mein atka hai. Abhi ye fee bhejo warna parcel " +
                "wapas chala jayega.",
            registeredPayees = listOf(PAYEE_KIRANA),
            expectedVerdict = PaymentAdvisoryVerdict.STOP,
            expectedKindsPresent = setOf(
                PaymentHintKind.PAYEE_NOT_REGISTERED,
                PaymentHintKind.STATUS_PENDING,
                PaymentHintKind.URGENCY_PRESSURE,
            ),
            expectedKindsAbsent = setOf(
                PaymentHintKind.PAYEE_MATCHES_REGISTERED,
                PaymentHintKind.AMOUNT_LARGE,
            ),
        ),
        Row(
            id = "S10",
            note = "second QR on one stand: the printed name is the shop the user knows, the UPI " +
                "is demo.kirana2 not demo.kirana. One mismatch is below BEHAVIOR_SPEC's " +
                "'three consecutive valid mismatches' warning line, so CHECK; the assertion this " +
                "row exists to make is that the printed name is still reported as a spoofable label",
            text = """
                Sharma Kirana Store
                Scan And Pay
                demo.kirana2@okaxis
                ₹2,450
            """.trimIndent(),
            registeredPayees = listOf(PAYEE_KIRANA),
            expectedVerdict = PaymentAdvisoryVerdict.CHECK,
            expectedKindsPresent = setOf(
                PaymentHintKind.PAYEE_NOT_REGISTERED,
                PaymentHintKind.PAYEE_NAME_SPOOFABLE,
            ),
            expectedKindsAbsent = setOf(PaymentHintKind.PAYEE_MATCHES_REGISTERED),
        ),
        Row(
            id = "S11",
            note = "success without a reference: the payee IS registered, but 'Successful' with no " +
                "UTR cannot be settled from a picture, so CHECK and the missing-reference hint",
            text = """
                Payment Successful
                ₹3,200
                To: demo.kirana@okhdfcbank
                ${STAMP}6:58 pm
            """.trimIndent(),
            registeredPayees = listOf(PAYEE_KIRANA),
            expectedVerdict = PaymentAdvisoryVerdict.CHECK,
            expectedKindsPresent = setOf(
                PaymentHintKind.UTR_MISSING,
                PaymentHintKind.PAYEE_MATCHES_REGISTERED,
            ),
            expectedKindsAbsent = setOf(
                PaymentHintKind.UTR_PRESENT,
                PaymentHintKind.PAYEE_NOT_REGISTERED,
            ),
        ),
        Row(
            id = "S12",
            note = "OCR-corrupted forwarded receipt: status lettering is damaged, so the parser must not " +
                "invent success or pending; unregistered payee plus pressure still warrants CHECK",
            text = """
                Payrnent Pendi ng
                ₹6,000
                To: demo.shop@okaxis
                screenshot bhej diya hai
            """.trimIndent(),
            messageText = "Bhai paisa bhej diya, abhi screenshot dekh lo.",
            registeredPayees = listOf(PAYEE_KIRANA),
            expectedVerdict = PaymentAdvisoryVerdict.STOP,
            expectedKindsPresent = setOf(
                PaymentHintKind.SCREENSHOT_ONLY,
                PaymentHintKind.PAYEE_NOT_REGISTERED,
                PaymentHintKind.URGENCY_PRESSURE,
                PaymentHintKind.STATUS_UNKNOWN,
            ),
            expectedKindsAbsent = setOf(
                PaymentHintKind.PAYEE_MATCHES_REGISTERED,
                PaymentHintKind.UTR_PRESENT,
            ),
            ocrUsed = true,
        ),

        // -------------------------------------------------------------------------------------
        // Legitimate-shaped: L01-L12
        // -------------------------------------------------------------------------------------

        Row(
            id = "L01",
            note = "registered payee, ₹250: the whole gate-G3 set is present, so CLEAR is the " +
                "honest reading (and CLEAR still only means nothing payment-specific was found)",
            text = """
                Payment Successful
                ₹250
                To: demo.kirana@okhdfcbank
                UTR: 412345678901
                ${STAMP}9:40 pm
            """.trimIndent(),
            messageText = "Kirana ka hisaab de diya.",
            registeredPayees = listOf(PAYEE_KIRANA),
            expectedVerdict = PaymentAdvisoryVerdict.CLEAR,
            expectedKindsPresent = setOf(PaymentHintKind.PAYEE_MATCHES_REGISTERED),
            expectedKindsAbsent = setOf(
                PaymentHintKind.PAYEE_NOT_REGISTERED,
                PaymentHintKind.AMOUNT_LARGE,
            ),
        ),
        Row(
            id = "L02",
            note = "registered payee, large amount (₹8,500): the payee is right and the receipt is " +
                "complete, but size alone is a payment-specific signal, so the table pins CHECK",
            text = """
                Payment Successful
                ₹8,500
                To: demo.kirana@okhdfcbank
                UTR: 412345678902
                ${STAMP}9:32 pm
            """.trimIndent(),
            messageText = "Mahine ka rashan le liya.",
            registeredPayees = listOf(PAYEE_KIRANA),
            expectedVerdict = PaymentAdvisoryVerdict.CHECK,
            expectedKindsPresent = setOf(
                PaymentHintKind.PAYEE_MATCHES_REGISTERED,
                PaymentHintKind.AMOUNT_LARGE,
            ),
            expectedKindsAbsent = setOf(PaymentHintKind.PAYEE_NOT_REGISTERED),
        ),
        Row(
            id = "L03",
            note = "bank CREDIT alert SMS: no currency-marked payee UPI and no receipt structure, " +
                "so it is not a payment screenshot at all (gate G4) and must stay CLEAR",
            text = "Rs 5,000 credited to A/c XX1234 on 14-11-23. Avl bal Rs 12,340.",
            messageText = "Rs 5,000 credited to A/c XX1234 on 14-11-23. Avl bal Rs 12,340.",
            expectedVerdict = PaymentAdvisoryVerdict.CLEAR,
            expectedKindsAbsent = setOf(
                PaymentHintKind.PAYEE_NOT_REGISTERED,
                PaymentHintKind.SCREENSHOT_ONLY,
            ),
        ),
        Row(
            id = "L04",
            note = "success + UTR + registered payee: the only fully settleable shape a picture can " +
                "have, and even here the confirmation line is still required",
            text = """
                Payment Successful
                ₹1,200
                To: demo.kirana@okhdfcbank
                UTR: 412345678903
                ${STAMP}9:15 pm
            """.trimIndent(),
            messageText = "Doodh aur ande ka bill.",
            registeredPayees = listOf(PAYEE_KIRANA),
            expectedVerdict = PaymentAdvisoryVerdict.CLEAR,
            expectedKindsPresent = setOf(
                PaymentHintKind.PAYEE_MATCHES_REGISTERED,
                PaymentHintKind.UTR_PRESENT,
            ),
            expectedKindsAbsent = setOf(
                PaymentHintKind.UTR_MISSING,
                PaymentHintKind.PAYEE_NOT_REGISTERED,
            ),
        ),
        Row(
            id = "L05",
            note = "FAILED receipt with no pressure ('payment failed, try again later'): nothing " +
                "asks for a second transfer, so CHECK for the failed status and never STOP",
            text = """
                Payment Failed
                ₹600
                To: demo.kirana@okhdfcbank
                ${STAMP}9:02 pm
            """.trimIndent(),
            messageText = "Payment failed, try again later.",
            registeredPayees = listOf(PAYEE_KIRANA),
            expectedVerdict = PaymentAdvisoryVerdict.CHECK,
            expectedKindsPresent = setOf(
                PaymentHintKind.STATUS_FAILED,
                PaymentHintKind.PAYEE_MATCHES_REGISTERED,
            ),
            expectedKindsAbsent = setOf(
                PaymentHintKind.STATUS_PENDING,
                PaymentHintKind.URGENCY_PRESSURE,
                PaymentHintKind.PAYEE_NOT_REGISTERED,
            ),
        ),
        Row(
            id = "L06",
            note = "routine electricity bill paid to the registered board payee, complete receipt: " +
                "two payees are registered here, so the count that reaches the engine is 2",
            text = """
                Payment Successful
                ₹1,780
                To: demo.bijli@okhdfcbank
                UTR: 412345678904
                ${STAMP}8:48 pm
            """.trimIndent(),
            messageText = "Bijli bill bhar diya.",
            registeredPayees = listOf(PAYEE_KIRANA, PAYEE_BIJLI),
            expectedVerdict = PaymentAdvisoryVerdict.CLEAR,
            expectedKindsPresent = setOf(
                PaymentHintKind.PAYEE_MATCHES_REGISTERED,
                PaymentHintKind.UTR_PRESENT,
            ),
            expectedKindsAbsent = setOf(PaymentHintKind.PAYEE_NOT_REGISTERED),
        ),
        Row(
            id = "L07",
            note = "delivery OTP SMS: an in-person code, not a payment screenshot (gate G4); the " +
                "BEHAVIOR_SPEC legitimate pair for 'share only with the agent at your door'",
            text = "Delivery OTP 4521, share only with the agent at your door.",
            messageText = "Delivery OTP 4521, share only with the agent at your door.",
            expectedVerdict = PaymentAdvisoryVerdict.CLEAR,
            expectedKindsAbsent = setOf(
                PaymentHintKind.PAYEE_NOT_REGISTERED,
                PaymentHintKind.SCREENSHOT_ONLY,
                PaymentHintKind.AMOUNT_LARGE,
            ),
        ),
        Row(
            id = "L08",
            note = "cashback line: money coming in, no payee and no amount owed, so not a payment " +
                "screenshot (gate G4) and no warning to give",
            text = "Rs 50 cashback credited to your wallet on 14 Nov. Use by 30 Nov.",
            messageText = "Rs 50 cashback credited to your wallet on 14 Nov. Use by 30 Nov.",
            expectedVerdict = PaymentAdvisoryVerdict.CLEAR,
            expectedKindsAbsent = setOf(
                PaymentHintKind.PAYEE_NOT_REGISTERED,
                PaymentHintKind.SCREENSHOT_ONLY,
            ),
        ),
        Row(
            id = "L09",
            note = "shop receipt with the printed name and the registered UPI: the name matches " +
                "and the id matches, so the label adds nothing and CLEAR stands",
            text = """
                Sharma Kirana Store
                Payment Successful
                ₹420
                To: demo.kirana@okhdfcbank
                UTR: 412345678905
                ${STAMP}8:35 pm
            """.trimIndent(),
            messageText = "Kirana ka bill.",
            registeredPayees = listOf(PAYEE_KIRANA),
            expectedVerdict = PaymentAdvisoryVerdict.CLEAR,
            expectedKindsPresent = setOf(PaymentHintKind.PAYEE_MATCHES_REGISTERED),
            expectedKindsAbsent = setOf(PaymentHintKind.PAYEE_NOT_REGISTERED),
        ),
        Row(
            id = "L10",
            note = "₹49 receipt to an unregistered payee (a tea stall nobody registered): one " +
                "mismatch on a trivial amount is CHECK at most - STOP here would be crying wolf",
            text = """
                Payment Successful
                ₹49
                To: demo.chai@okaxis
                UTR: 412345678906
                ${STAMP}8:20 pm
            """.trimIndent(),
            messageText = "Chai aur samosa ka paisa.",
            registeredPayees = listOf(PAYEE_KIRANA),
            expectedVerdict = PaymentAdvisoryVerdict.CHECK,
            expectedKindsPresent = setOf(PaymentHintKind.PAYEE_NOT_REGISTERED),
            expectedKindsAbsent = setOf(
                PaymentHintKind.PAYEE_MATCHES_REGISTERED,
                PaymentHintKind.AMOUNT_LARGE,
                PaymentHintKind.AMOUNT_ROUND_LARGE,
            ),
        ),
        Row(
            id = "L11",
            note = "UPI collect / request message with no amount: the payee is registered and " +
                "nothing has been paid or claimed, so there is no payment warning to raise. With no " +
                "amount there is no receipt to classify either, so this row pins only what must not " +
                "happen (a mismatch warning or a large-amount warning) and not which good news the " +
                "engine chooses to report",
            text = """
                UPI Request
                demo.kirana@okhdfcbank
                Accept request?
            """.trimIndent(),
            messageText = "Kirana bhaiya ne request bheji hai.",
            registeredPayees = listOf(PAYEE_KIRANA),
            expectedVerdict = PaymentAdvisoryVerdict.CLEAR,
            expectedKindsPresent = emptySet(),
            expectedKindsAbsent = setOf(
                PaymentHintKind.PAYEE_NOT_REGISTERED,
                PaymentHintKind.AMOUNT_LARGE,
            ),
        ),
        Row(
            id = "L12",
            note = "ordinary Hinglish bank message with an amount and no UPI id: an account " +
                "movement, not a receipt (gate G4), so no payee comparison and no warning",
            text = "Aapke account se Rs 1,150 debit hua 14 Nov ko. Balance Rs 3,400.",
            messageText = "Aapke account se Rs 1,150 debit hua 14 Nov ko. Balance Rs 3,400.",
            expectedVerdict = PaymentAdvisoryVerdict.CLEAR,
            expectedKindsAbsent = setOf(
                PaymentHintKind.PAYEE_NOT_REGISTERED,
                PaymentHintKind.SCREENSHOT_ONLY,
                PaymentHintKind.AMOUNT_LARGE,
            ),
        ),
    )

    /** Suspicious-shaped rows, in table order. */
    val suspiciousRows: List<Row> = rows.filter { it.id.startsWith(SUSPICIOUS_PREFIX) }

    /** Legitimate-shaped rows, in table order. */
    val legitimateRows: List<Row> = rows.filter { it.id.startsWith(LEGITIMATE_PREFIX) }

    /** Stable ids of [suspiciousRows]. */
    val suspiciousIds: Set<String> = suspiciousRows.map { it.id }.toSet()

    /** Stable ids of [legitimateRows]. */
    val legitimateIds: Set<String> = legitimateRows.map { it.id }.toSet()

    /** Look a row up by its stable id. Null (not an exception) so a caller can report a miss. */
    fun byId(id: String): Row? = rows.firstOrNull { it.id == id }
}
