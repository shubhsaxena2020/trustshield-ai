package ai.trustshield.android.core.payment

/**
 * Lane B / payment advisory — the pinned contract every payment-advisory file implements against.
 *
 * ## Why this file exists
 * A payment screenshot is **never proof that money moved** (`docs/android/BEHAVIOR_SPEC.md`,
 * "QR and payment semantics", and HEAD_ORDERS Phase 4 item 8). Every advisory this package produces
 * is a **soft hint plus the one mandatory confirmation line**; nothing here may claim settlement,
 * and nothing here may state that a person is a fraudster.
 *
 * ## Privacy rules (binding)
 *  - The raw source text is never retained by these types; only the exact substrings that a hint
 *    needs are kept, and the full UPI ID is never rendered or logged — see [maskedUpi].
 *  - [PaymentFields.toString] and [PaymentHint.toString] are the only debug surfaces and both are
 *    deliberately value-free, so an accidental log line cannot leak a UPI ID, an amount or a UTR.
 *  - Registered payees are compared by **salted HMAC identifier**, never by storing the raw UPI ID
 *    (`data/preferences/RegisteredPayeeStore`, `ARCHITECTURE.md` boundaries).
 *
 * ## Honesty rules (binding)
 *  - [PaymentAdvisoryVerdict] is at most [PaymentAdvisoryVerdict.STOP] for a *do-not-pay-yet*
 *    reading; `CLEAR` means "no payment-specific warning signal found", never "safe to pay".
 *  - Absolute claims ("100% safe", "guaranteed") are scrubbed by the Result layer's
 *    `AbsoluteSafetyGuard`; copy in this package must not contain them in the first place.
 */
enum class PaymentStatus {
    SUCCESS,
    PENDING,
    FAILED,
    PROCESSING,
    UNKNOWN
}

/** Which payment app the surrounding chrome (title bar / footer text) suggests. A hint only. */
enum class PaymentAppHint {
    GPAY,
    PHONEPE,
    PAYTM,
    BHIM,
    BANK_APP,
    UNKNOWN
}

/**
 * Everything a payment screenshot can reasonably tell us.
 *
 * Every field keeps the **exact** source substring it was parsed from ([amountRaw], [statusRaw],
 * [timestampRaw], [utr]) so a UI can show a literal quote instead of a paraphrase. Missing values are
 * `null` / blank — never a fabricated default.
 */
data class PaymentFields(
    /** Amount in paise (Rs 1,200.50 -> 120050). `null` when no amount was found. */
    val amountMinor: Long? = null,
    /** Exact amount substring as it appeared, e.g. "₹1,200.50". */
    val amountRaw: String = "",
    /** Raw UPI ID (e.g. `shop@okaxis`) when the screenshot shows one. Never rendered unmasked. */
    val payeeUpi: String = "",
    /** Printed payee name (`pn`-style label). Explicitly spoofable. */
    val payeeName: String = "",
    /** 12-digit UPI reference / UTR when present. */
    val utr: String = "",
    val status: PaymentStatus = PaymentStatus.UNKNOWN,
    val statusRaw: String = "",
    /** Date/time substring if the screenshot shows one, e.g. "27 Oct 2026, 7:04 pm". */
    val timestampRaw: String = "",
    /**
     * [timestampRaw] parsed to epoch millis when the format is unambiguous (a zone-less receipt time
     * is read in the device zone by the parser and marked as such there). `null` when it could not be
     * parsed — the engine then reports [PaymentHintKind.TIME_MISSING] rather than guessing.
     */
    val timestampEpochMs: Long? = null,
    val appHint: PaymentAppHint = PaymentAppHint.UNKNOWN
) {
    val hasAmount: Boolean get() = amountMinor != null && amountMinor > 0L
    val hasPayeeUpi: Boolean get() = payeeUpi.contains('@')
    val hasUtr: Boolean get() = utr.isNotBlank()

    /**
     * True only when there is enough structure to look like a payment receipt:
     * an amount **and** at least one payment-specific marker.
     */
    val looksLikeReceipt: Boolean
        get() = hasAmount && (hasPayeeUpi || hasUtr || status != PaymentStatus.UNKNOWN)

    /** `sho***@okaxis` — the only form of a UPI ID this package may show or log. */
    val maskedUpi: String get() = maskedUpi(payeeUpi)

    override fun toString(): String =
        "PaymentFields(hasAmount=$hasAmount, upi=${maskedUpi.ifBlank { "-" }}, " +
            "hasName=${payeeName.isNotBlank()}, hasUtr=$hasUtr, status=$status, app=$appHint)"
}

/** One soft hint. Language-neutral: the wording lives in `ui/result/PaymentAdvisoryCopy`. */
data class PaymentHint(
    val kind: PaymentHintKind,
    val severity: PaymentHintSeverity,
    /** Amount in paise when the hint is about money; the copy formats it. */
    val amountMinor: Long? = null,
    /** Masked payee only — never the raw UPI ID. */
    val maskedPayee: String = "",
    /** Counting hints (e.g. how many payees are registered). */
    val count: Int = 0
) {
    override fun toString(): String =
        "PaymentHint(kind=$kind, severity=$severity, hasAmount=${amountMinor != null}, " +
            "payee=${maskedPayee.ifBlank { "-" }}, count=$count)"
}

enum class PaymentHintKind {
    /** The screenshot's UPI ID hashes to a locally registered payee. */
    PAYEE_MATCHES_REGISTERED,

    /** UPI ID shown, but it is not one of the locally registered payees. */
    PAYEE_NOT_REGISTERED,

    /** No UPI ID could be read at all. */
    PAYEE_MISSING,

    /** A printed payee name is present; names are spoofable, only the UPI ID comparison counts. */
    PAYEE_NAME_SPOOFABLE,

    /** No payee is registered on this phone, so no comparison was possible. */
    NO_REGISTERED_PAYEES,

    /** Amount at or above the "large" policy threshold. */
    AMOUNT_LARGE,

    /** Amount is a large round figure — a common lure (e.g. a refund or fee). */
    AMOUNT_ROUND_LARGE,

    /** No amount could be read. */
    AMOUNT_MISSING,

    /** Screenshot says pending. */
    STATUS_PENDING,

    /** Screenshot says failed / declined / cancelled. */
    STATUS_FAILED,

    /** No readable status line — settlement unknown. */
    STATUS_UNKNOWN,

    /** Status says success but no UTR/reference is printed. */
    UTR_MISSING,

    /** A UTR/reference is printed. */
    UTR_PRESENT,

    /** No date/time readable, so the "recent" check cannot run. */
    TIME_MISSING,

    /** The screenshot carries a very recent timestamp. */
    TIME_RECENT,

    /** The accompanying message presses for speed ("jaldi", "abhi", "turant"). */
    URGENCY_PRESSURE,

    /** The only "evidence" is a picture; a screenshot proves nothing about settlement. */
    SCREENSHOT_ONLY
}

enum class PaymentHintSeverity {
    INFO,
    CAUTION,
    WARNING
}

/** Do-not-pay escalation for a payment screen. `CLEAR` is *not* a safety verdict. */
enum class PaymentAdvisoryVerdict {
    CLEAR,
    CHECK,
    STOP
}

/** The advisory handed to the UI. `hints` is ordered: WARNING first, then CAUTION, then INFO. */
data class PaymentAdvisory(
    val isPaymentScreenshot: Boolean,
    val fields: PaymentFields,
    val hints: List<PaymentHint>,
    val verdict: PaymentAdvisoryVerdict,
    /**
     * Always true: the confirmation line is not optional, and the UI must render it after the hints
     * (`BEHAVIOR_SPEC.md`: "Always show: Pakka confirmation sirf aapke bank SMS ya UPI app ki
     * history mein milega.").
     */
    val confirmationRequired: Boolean = true
) {
    val warnings: List<PaymentHint> get() = hints.filter { it.severity == PaymentHintSeverity.WARNING }
    val cautions: List<PaymentHint> get() = hints.filter { it.severity == PaymentHintSeverity.CAUTION }

    override fun toString(): String =
        "PaymentAdvisory(isScreenshot=$isPaymentScreenshot, verdict=$verdict, " +
            "hints=${hints.map { it.kind }})"
}

/** Drops parser source fragments before an advisory crosses into longer-lived UI state. */
fun PaymentAdvisory.maskedForPresentation(): PaymentAdvisory = copy(
    fields = fields.copy(
        amountRaw = "",
        payeeUpi = fields.maskedUpi,
        payeeName = "",
        utr = "",
        statusRaw = "",
        timestampRaw = ""
    )
)

/**
 * Thresholds, in paise. Kept as data so a fixture can pin a boundary instead of guessing, and so
 * sensitivity work can adjust them later without touching rule code.
 */
data class PaymentAdvisoryPolicy(
    val largeAmountMinor: Long = 500_000L,   // Rs 5,000
    val roundAmountMinor: Long = 200_000L,   // Rs 2,000
    val roundDivisorMinor: Long = 100_000L,  // ...and a multiple of Rs 1,000
    val recentMinutes: Long = 10L
)

/**
 * Everything the engine is allowed to look at.
 *
 * [payeeMatchesRegistered] / [registeredPayeeCount] are supplied by the caller (QR Guard's
 * registered payees via `ui/qr/QrPayeePaymentBridge`), so the engine itself never touches storage and
 * can be unit-tested on a JVM with no Android framework.
 */
data class PaymentAdvisoryInput(
    val sourceText: String,
    val fields: PaymentFields,
    val registeredPayeeCount: Int = 0,
    val payeeMatchesRegistered: Boolean = false,
    /** Epoch millis of "now" — a parameter so tests are deterministic. */
    val nowEpochMs: Long = 0L,
    /** True when the text came out of OCR rather than a typed paste (lowers certainty in copy). */
    val ocrUsed: Boolean = false
)

/**
 * `sho***@okaxis`. Keeps the first three local characters and the handle so a user can still
 * recognise the payee, while never showing a full UPI ID on screen or in a log.
 */
fun maskedUpi(raw: String): String {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return ""
    val at = trimmed.indexOf('@')
    if (at <= 0) return "***"
    val local = trimmed.substring(0, at)
    val handle = trimmed.substring(at)
    val head = if (local.length <= 3) local else local.substring(0, 3)
    return head + "***" + handle
}
