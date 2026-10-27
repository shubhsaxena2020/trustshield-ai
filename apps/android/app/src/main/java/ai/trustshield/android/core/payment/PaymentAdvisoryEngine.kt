package ai.trustshield.android.core.payment

/**
 * Deterministic payment advisory engine: one [PaymentAdvisoryInput] in, one [PaymentAdvisory] out.
 *
 * ## Why this file exists
 *
 * A payment screenshot is **never proof that money moved** (`docs/android/BEHAVIOR_SPEC.md`, "QR and
 * payment semantics"). What a screenshot *can* support is a short list of soft hints plus a
 * do-not-pay-yet reading, and a user should get that same reading every single time for the same
 * input. This class is that mapping: pure, ordered, no clock, no I/O, no `android.*`, no randomness,
 * no logging, and no dependency beyond `core/payment/PaymentAdvisoryContract.kt`.
 *
 * The engine decides **hints and verdict only**. It writes no user-visible sentence, so no wording
 * rule (Hinglish copy, the mandatory confirmation line, the ban on any absolute safety claim) is
 * enforced here; it is enforced in the copy layer that reads [PaymentAdvisory]. See
 * `PaymentAdvisoryContract.kt` for the honesty and privacy rules that bind both layers.
 *
 * ## What it refuses to claim
 *
 *  * [PaymentAdvisoryVerdict.CLEAR] means "no payment-specific warning signal was found in this
 *    input", never "safe to pay". Nothing here inspects a real ledger.
 *  * A hint is about the *screenshot and the text around it*, never about a person. No kind in this
 *    engine names a fraudster.
 *  * [PaymentHintSeverity] is a display weight, not a probability: the rules are literal predicates,
 *    so two runs on the same input always produce the same hints in the same order.
 *  * [PaymentAdvisory.confirmationRequired] is always true, including for `CLEAR`.
 *
 * ## Privacy
 *
 * The engine keeps nothing. The only field it copies out of [PaymentFields] is
 * [PaymentFields.maskedUpi], and only into [PaymentHint.maskedPayee]; the raw UPI ID never reaches a
 * hint, a string or a log line. No `toString` is overridden here, so every produced object keeps the
 * contract's value-free debug form.
 *
 * ## Hint rule table (identical to the code below)
 *
 * Fires at most once per kind. `fields` is [PaymentAdvisoryInput.fields]; `input` is the whole input.
 *
 * | kind | severity | fires when | carries |
 * |---|---|---|---|
 * | `PAYEE_NOT_REGISTERED` | WARNING | `fields.hasPayeeUpi && input.registeredPayeeCount > 0 && !input.payeeMatchesRegistered` | `maskedPayee = fields.maskedUpi` |
 * | `STATUS_FAILED` | WARNING | `fields.status == FAILED` | - |
 * | `AMOUNT_ROUND_LARGE` | WARNING | `fields.amountMinor != null && amount > 0 && amount >= policy.roundAmountMinor && policy.roundDivisorMinor > 0 && amount % policy.roundDivisorMinor == 0L && !input.payeeMatchesRegistered` | `amountMinor = fields.amountMinor` |
 * | `URGENCY_PRESSURE` | WARNING | the first [MAX_SCANNED_CHARS] chars of `input.sourceText` contain a speed cue (see below) | - |
 * | `PAYEE_MISSING` | CAUTION | `!fields.hasPayeeUpi` | - |
 * | `NO_REGISTERED_PAYEES` | CAUTION | `fields.hasPayeeUpi && input.registeredPayeeCount == 0` | `count = 0` |
 * | `AMOUNT_LARGE` | CAUTION | `fields.amountMinor != null && amount >= policy.largeAmountMinor` | `amountMinor = fields.amountMinor` |
 * | `STATUS_UNKNOWN` | CAUTION | `fields.status == UNKNOWN` | - |
 * | `STATUS_PENDING` | CAUTION | `fields.status == PENDING` | - |
 * | `UTR_MISSING` | CAUTION | `fields.status == SUCCESS && !fields.hasUtr` | - |
 * | `TIME_RECENT` | CAUTION | `fields.timestampEpochMs != null && input.nowEpochMs > 0 && (input.nowEpochMs - fields.timestampEpochMs) in 0..policy.recentMinutes * 60_000L` | - |
 * | `PAYEE_NAME_SPOOFABLE` | CAUTION | `fields.payeeName.isNotBlank()` | - |
 * | `PAYEE_MATCHES_REGISTERED` | INFO | `input.payeeMatchesRegistered` | - |
 * | `AMOUNT_MISSING` | INFO | `!fields.hasAmount` | - |
 * | `UTR_PRESENT` | INFO | `fields.hasUtr` (any status) | - |
 * | `TIME_MISSING` | INFO | `fields.timestampEpochMs == null` | - |
 * | `SCREENSHOT_ONLY` | INFO | `input.ocrUsed` | - |
 *
 * `SUCCESS` and `PROCESSING` carry no status hint of their own; `SUCCESS` is still covered by
 * `UTR_MISSING` / `UTR_PRESENT`. No other kind can be emitted.
 *
 * ### Speed cues for [PaymentHintKind.URGENCY_PRESSURE]
 *
 * `jaldi`, `turant`, `abhi`, `aaj hi`, `10 minute`, `10 minutes`, `5 minute`, `5 minutes`,
 * `last chance`, `warna`, `now only`, `immediately`, `jldi` — matched case-insensitively, literally
 * (no regex, so no backtracking), and only inside the first [MAX_SCANNED_CHARS] characters of
 * `sourceText` so the scan is bounded whatever the caller pastes.
 *
 * Two deliberate properties of that scan, both pinned by tests:
 *
 *  1. A cue only counts when it is **not glued** to a letter or digit on either side. That keeps
 *     `15 minute` / `45 minute` from firing on `5 minute`, and keeps the name `Abhishek` from firing
 *     on `abhi`. `abhi` as a standalone word always counts — including in a legitimate credit line
 *     such as "aapke account me abhi paise credit hue", which *does* emit `URGENCY_PRESSURE`. The
 *     engine does no negation or intent analysis on this cue; it is a lexical speed marker only, and
 *     its weight is bounded by the verdict table (a speed cue can reach `CHECK` or, with a
 *     non-registered payee, `STOP` — never an accusation).
 *  2. The scan stops at character 4000. A cue at char 4001 or later is invisible to the engine, by
 *     design: OCR output that long has already lost the reliability this cue assumes.
 *
 * ## Verdict table (first match wins, identical to the code below)
 *
 * | order | verdict | condition |
 * |---|---|---|
 * | 0 | `CLEAR` | `!fields.looksLikeReceipt && !input.ocrUsed` — not a payment view at all, so `isPaymentScreenshot = false` and `hints = emptyList()` (no rule runs, not even the cues) |
 * | 1 | `STOP` | `PAYEE_NOT_REGISTERED` present **and** (`AMOUNT_LARGE` or `AMOUNT_ROUND_LARGE` or `URGENCY_PRESSURE` present) |
 * | 2 | `STOP` | `STATUS_FAILED` present **and** `URGENCY_PRESSURE` present |
 * | 3 | `STOP` | `PAYEE_NOT_REGISTERED` present **and** `STATUS_PENDING` present |
 * | 4 | `CHECK` | any `WARNING`, or one G1 signal: `AMOUNT_LARGE`, `STATUS_PENDING`, `STATUS_UNKNOWN`, `UTR_MISSING`, or `SCREENSHOT_ONLY` |
 * | 5 | `CHECK` | at least two other `CAUTION` hints are present |
 * | 6 | `CLEAR` | otherwise — including a single non-G1 `CAUTION`, which is shown as a hint but does not lift the verdict |
 *
 * A UPI payee with a known status also counts as a payment view when the amount is unreadable. This
 * lets a pending unregistered payee reach the STOP rule without guessing an amount.
 * `isPaymentScreenshot` is `false` only in row 0; every other row returns `true`.
 *
 * ## Hint order
 *
 * Always `WARNING` → `CAUTION` → `INFO`, and inside one severity the declaration order of
 * [PaymentHintKind] (e.g. `PAYEE_NOT_REGISTERED` before `AMOUNT_ROUND_LARGE` before
 * `URGENCY_PRESSURE`). The order is a documented contract for the UI, which renders `hints` as given.
 *
 * ## Hardening
 *
 * [evaluate] never throws: it reads only immutable values, guards the one division
 * (`policy.roundDivisorMinor > 0`) and the one multiplication (`recentMinutes * 60_000L`, an overflow
 * merely widens or empties the window), treats an unparseable or absurd timestamp as "no recent
 * hint", and bounds every string scan. `nowEpochMs = 0` (the default) disables `TIME_RECENT`
 * entirely, so a caller without a clock still gets a usable advisory.
 *
 * @param policy thresholds in paise; defaults are the shipped `PaymentAdvisoryPolicy()` values.
 */
class PaymentAdvisoryEngine(
    private val policy: PaymentAdvisoryPolicy = PaymentAdvisoryPolicy()
) {

    /**
     * Builds the advisory for one payment screenshot or pasted receipt.
     *
     * Pure and total: the same [input] always yields the same [PaymentAdvisory], and no combination of
     * [PaymentFields] values (including negative amounts, blank strings, non-ASCII text and a
     * timestamp in the future) can make this throw. It never logs and never retains [input].
     *
     * @param input the parsed fields plus the caller-supplied payee comparison, clock and OCR flag.
     * @return the ordered hints, the verdict, and `confirmationRequired = true`.
     */
    fun evaluate(input: PaymentAdvisoryInput): PaymentAdvisory {
        val fields: PaymentFields = input.fields
        val hasPaymentView = fields.looksLikeReceipt ||
            (fields.hasPayeeUpi && fields.status != PaymentStatus.UNKNOWN)
        if (!hasPaymentView && !input.ocrUsed) {
            return PaymentAdvisory(
                isPaymentScreenshot = false,
                fields = fields,
                hints = emptyList(),
                verdict = PaymentAdvisoryVerdict.CLEAR,
                confirmationRequired = CONFIRMATION_REQUIRED,
            )
        }
        val hints: List<PaymentHint> = buildHints(input)
        return PaymentAdvisory(
            isPaymentScreenshot = true,
            fields = fields,
            hints = hints,
            verdict = verdictFor(hints),
            confirmationRequired = CONFIRMATION_REQUIRED,
        )
    }

    /**
     * The rules, in compile-clean order and then sorted for display. Each kind is emitted at most once
     * (a `LinkedHashMap` keyed by kind), so a caller can never see a duplicate.
     */
    private fun buildHints(input: PaymentAdvisoryInput): List<PaymentHint> {
        val fields: PaymentFields = input.fields
        val amountMinor: Long? = fields.amountMinor
        val hints: LinkedHashMap<PaymentHintKind, PaymentHint> = LinkedHashMap()

        // ---- payee ------------------------------------------------------------------------------
        if (fields.hasPayeeUpi && input.registeredPayeeCount > 0 && !input.payeeMatchesRegistered) {
            add(
                hints,
                PaymentHint(
                    kind = PaymentHintKind.PAYEE_NOT_REGISTERED,
                    severity = PaymentHintSeverity.WARNING,
                    maskedPayee = fields.maskedUpi,
                ),
            )
        }
        if (input.payeeMatchesRegistered) {
            add(
                hints,
                PaymentHint(
                    kind = PaymentHintKind.PAYEE_MATCHES_REGISTERED,
                    severity = PaymentHintSeverity.INFO,
                ),
            )
        }
        if (!fields.hasPayeeUpi) {
            add(
                hints,
                PaymentHint(
                    kind = PaymentHintKind.PAYEE_MISSING,
                    severity = PaymentHintSeverity.CAUTION,
                ),
            )
        }
        if (fields.hasPayeeUpi && input.registeredPayeeCount == 0) {
            add(
                hints,
                PaymentHint(
                    kind = PaymentHintKind.NO_REGISTERED_PAYEES,
                    severity = PaymentHintSeverity.CAUTION,
                    count = 0,
                ),
            )
        }
        if (fields.payeeName.isNotBlank()) {
            add(
                hints,
                PaymentHint(
                    kind = PaymentHintKind.PAYEE_NAME_SPOOFABLE,
                    severity = PaymentHintSeverity.CAUTION,
                ),
            )
        }

        // ---- amount -----------------------------------------------------------------------------
        if (amountMinor != null && amountMinor >= policy.largeAmountMinor) {
            add(
                hints,
                PaymentHint(
                    kind = PaymentHintKind.AMOUNT_LARGE,
                    severity = PaymentHintSeverity.CAUTION,
                    amountMinor = amountMinor,
                ),
            )
        }
        if (isLargeRoundAmount(amountMinor, input.payeeMatchesRegistered)) {
            add(
                hints,
                PaymentHint(
                    kind = PaymentHintKind.AMOUNT_ROUND_LARGE,
                    severity = PaymentHintSeverity.WARNING,
                    amountMinor = amountMinor,
                ),
            )
        }
        if (!fields.hasAmount) {
            add(
                hints,
                PaymentHint(
                    kind = PaymentHintKind.AMOUNT_MISSING,
                    severity = PaymentHintSeverity.INFO,
                ),
            )
        }

        // ---- status / reference -----------------------------------------------------------------
        when (fields.status) {
            PaymentStatus.FAILED -> add(
                hints,
                PaymentHint(
                    kind = PaymentHintKind.STATUS_FAILED,
                    severity = PaymentHintSeverity.WARNING,
                ),
            )

            PaymentStatus.PENDING -> add(
                hints,
                PaymentHint(
                    kind = PaymentHintKind.STATUS_PENDING,
                    severity = PaymentHintSeverity.CAUTION,
                ),
            )

            PaymentStatus.UNKNOWN -> add(
                hints,
                PaymentHint(
                    kind = PaymentHintKind.STATUS_UNKNOWN,
                    severity = PaymentHintSeverity.CAUTION,
                ),
            )

            // A success is described by UTR_MISSING / UTR_PRESENT below; processing is silent.
            PaymentStatus.SUCCESS, PaymentStatus.PROCESSING -> Unit
        }
        if (fields.status == PaymentStatus.SUCCESS && !fields.hasUtr) {
            add(
                hints,
                PaymentHint(
                    kind = PaymentHintKind.UTR_MISSING,
                    severity = PaymentHintSeverity.CAUTION,
                ),
            )
        }
        if (fields.hasUtr) {
            add(
                hints,
                PaymentHint(
                    kind = PaymentHintKind.UTR_PRESENT,
                    severity = PaymentHintSeverity.INFO,
                ),
            )
        }

        // ---- time -------------------------------------------------------------------------------
        if (isRecent(fields.timestampEpochMs, input.nowEpochMs)) {
            add(
                hints,
                PaymentHint(
                    kind = PaymentHintKind.TIME_RECENT,
                    severity = PaymentHintSeverity.CAUTION,
                ),
            )
        }
        if (fields.timestampEpochMs == null) {
            add(
                hints,
                PaymentHint(
                    kind = PaymentHintKind.TIME_MISSING,
                    severity = PaymentHintSeverity.INFO,
                ),
            )
        }

        // ---- surrounding text -------------------------------------------------------------------
        if (hasUrgencyCue(input.sourceText)) {
            add(
                hints,
                PaymentHint(
                    kind = PaymentHintKind.URGENCY_PRESSURE,
                    severity = PaymentHintSeverity.WARNING,
                ),
            )
        }
        if (input.ocrUsed) {
            add(
                hints,
                PaymentHint(
                    kind = PaymentHintKind.SCREENSHOT_ONLY,
                    severity = PaymentHintSeverity.INFO,
                ),
            )
        }

        return hints.values.sortedWith(HINT_ORDER)
    }

    /**
     * The verdict table in the class KDoc, in the same order. `kinds` is a set so "present" means
     * "emitted at least once", which the dedupe in [buildHints] already ensures.
     */
    private fun verdictFor(hints: List<PaymentHint>): PaymentAdvisoryVerdict {
        val kinds: Set<PaymentHintKind> = hints.mapTo(LinkedHashSet()) { hint -> hint.kind }
        val warningCount: Int = hints.count { hint -> hint.severity == PaymentHintSeverity.WARNING }
        val cautionCount: Int = hints.count { hint -> hint.severity == PaymentHintSeverity.CAUTION }

        val unregisteredPayee: Boolean = kinds.contains(PaymentHintKind.PAYEE_NOT_REGISTERED)
        val urgency: Boolean = kinds.contains(PaymentHintKind.URGENCY_PRESSURE)
        val bigMoney: Boolean = kinds.contains(PaymentHintKind.AMOUNT_LARGE) ||
            kinds.contains(PaymentHintKind.AMOUNT_ROUND_LARGE)

        return when {
            unregisteredPayee && (bigMoney || urgency) -> PaymentAdvisoryVerdict.STOP
            kinds.contains(PaymentHintKind.STATUS_FAILED) && urgency -> PaymentAdvisoryVerdict.STOP
            unregisteredPayee && kinds.contains(PaymentHintKind.STATUS_PENDING) ->
                PaymentAdvisoryVerdict.STOP

            warningCount > 0 || kinds.any { kind -> kind in G1_CHECK_SIGNALS } -> PaymentAdvisoryVerdict.CHECK
            cautionCount >= CHECK_CAUTION_THRESHOLD -> PaymentAdvisoryVerdict.CHECK
            else -> PaymentAdvisoryVerdict.CLEAR
        }
    }

    /**
     * A large round figure with no registered payee to vouch for it: the shape a "refund", "fee" or
     * "customs charge" lure uses. Suppressed when the payee matches a registered one, and inert for a
     * non-positive amount or a non-positive [PaymentAdvisoryPolicy.roundDivisorMinor] (no division).
     */
    private fun isLargeRoundAmount(amountMinor: Long?, payeeMatchesRegistered: Boolean): Boolean {
        if (amountMinor == null || amountMinor <= 0L) return false
        if (payeeMatchesRegistered) return false
        if (amountMinor < policy.roundAmountMinor) return false
        val divisor: Long = policy.roundDivisorMinor
        if (divisor <= 0L) return false
        return amountMinor % divisor == 0L
    }

    /**
     * `TIME_RECENT`: the screenshot is at most [PaymentAdvisoryPolicy.recentMinutes] old. A "now" of
     * `0` (no clock), a missing timestamp, a future timestamp and a negative window all mean "not
     * recent" rather than an exception or a guess.
     */
    private fun isRecent(timestampEpochMs: Long?, nowEpochMs: Long): Boolean {
        if (timestampEpochMs == null) return false
        if (nowEpochMs <= 0L) return false
        val windowMs: Long = policy.recentMinutes * MILLIS_PER_MINUTE
        if (windowMs < 0L) return false
        val ageMs: Long = nowEpochMs - timestampEpochMs
        return ageMs in 0L..windowMs
    }

    /**
     * Bounded, literal, case-insensitive speed-cue scan over the first [MAX_SCANNED_CHARS] characters.
     * See the class KDoc for the two documented properties (word boundaries, and the 4000-char stop).
     */
    private fun hasUrgencyCue(sourceText: String): Boolean {
        if (sourceText.isEmpty()) return false
        val scanned: String =
            if (sourceText.length <= MAX_SCANNED_CHARS) sourceText
            else sourceText.substring(0, MAX_SCANNED_CHARS)
        val haystack: String = scanned.lowercase()
        for (cue in URGENCY_CUES) {
            if (containsCue(haystack, cue)) return true
        }
        return false
    }

    /**
     * True when [cue] occurs in [haystack] at least once without a letter or digit immediately before
     * or after it. Linear per occurrence and bounded by the caller's scan limit — no regex is used, so
     * there is no pattern that can backtrack.
     */
    private fun containsCue(haystack: String, cue: String): Boolean {
        if (cue.isEmpty()) return false
        var from: Int = 0
        while (from <= haystack.length - cue.length) {
            val index: Int = haystack.indexOf(cue, from)
            if (index < 0) return false
            val before: Int = index - 1
            val after: Int = index + cue.length
            val leftClear: Boolean = before < 0 || !haystack[before].isLetterOrDigit()
            val rightClear: Boolean = after >= haystack.length || !haystack[after].isLetterOrDigit()
            if (leftClear && rightClear) return true
            from = index + 1
        }
        return false
    }

    /** Records [hint] unless an earlier rule already emitted the same kind (so: no duplicates). */
    private fun add(hints: MutableMap<PaymentHintKind, PaymentHint>, hint: PaymentHint): Unit {
        if (!hints.containsKey(hint.kind)) {
            hints[hint.kind] = hint
        }
    }

    private companion object {

        /** The advisory always demands the confirmation line; there is no mode that relaxes it. */
        const val CONFIRMATION_REQUIRED: Boolean = true

        /** Two independent cautions are the minimum for `CHECK` (see the verdict table). */
        const val CHECK_CAUTION_THRESHOLD: Int = 2

        val G1_CHECK_SIGNALS: Set<PaymentHintKind> = setOf(
            PaymentHintKind.AMOUNT_LARGE,
            PaymentHintKind.STATUS_PENDING,
            PaymentHintKind.STATUS_UNKNOWN,
            PaymentHintKind.UTR_MISSING,
            PaymentHintKind.SCREENSHOT_ONLY,
        )

        /** Speed cues are only read from this many characters of `sourceText`. */
        const val MAX_SCANNED_CHARS: Int = 4_000

        const val MILLIS_PER_MINUTE: Long = 60_000L

        /**
         * Display order: `WARNING` (0) before `CAUTION` (1) before `INFO` (2), then the declaration
         * order of [PaymentHintKind] so equal-severity hints never reshuffle between runs.
         */
        val HINT_ORDER: Comparator<PaymentHint> = compareBy<PaymentHint>(
            { hint: PaymentHint -> severityRank(hint.severity) },
            { hint: PaymentHint -> hint.kind.ordinal },
        )

        /**
         * Speed cues, already lowercase. The minute cues appear with and without the plural "s" so
         * "10 minutes" matches while the trailing letter still cannot glue to a digit ("15 minutes").
         * Latin script only; a Devanagari cue list is deliberately out of scope for this engine.
         */
        val URGENCY_CUES: List<String> = listOf(
            "jaldi",
            "turant",
            "abhi",
            "aaj hi",
            "10 minute",
            "10 minutes",
            "5 minute",
            "5 minutes",
            "last chance",
            "warna",
            "now only",
            "immediately",
            "jldi",
        )

        /** `WARNING` = 0, `CAUTION` = 1, `INFO` = 2; exhaustive so a new severity cannot be ignored. */
        fun severityRank(severity: PaymentHintSeverity): Int = when (severity) {
            PaymentHintSeverity.WARNING -> 0
            PaymentHintSeverity.CAUTION -> 1
            PaymentHintSeverity.INFO -> 2
        }
    }
}
