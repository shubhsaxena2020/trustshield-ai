package ai.trustshield.android.ui.qr

import ai.trustshield.android.core.payment.PaymentAdvisoryInput
import ai.trustshield.android.core.payment.PaymentFields

/**
 * Bridge from QR Guard's registered payees ([RegisteredPayee]) to the payment advisory's
 * [PaymentAdvisoryInput] (`core/payment/PaymentAdvisoryContract.kt`).
 *
 * ## Why a bridge and not a dependency
 * The advisory engine must stay free of QR Guard state and of storage: it receives
 * [PaymentAdvisoryInput.registeredPayeeCount] and [PaymentAdvisoryInput.payeeMatchesRegistered] as plain
 * values and can therefore be unit-tested on a bare JVM. This object is the one place those two values
 * are derived from the payee list, so there is exactly one definition of "the same payee" in the app.
 *
 * ## Privacy rules (binding)
 *  - A payee is compared **only** by its normalised id: [RegisteredPayee.key], which is
 *    `normalizePayeeId(handle)` (trimmed, case-insensitive) against the screenshot's `payeeUpi` after
 *    the same normalisation. Never by the printed payee name ([RegisteredPayee.name] / `pn`), which is
 *    attacker-controlled chrome, and never by [RegisteredPayee.maskedHandle], which has lost the
 *    characters needed to compare.
 *  - A **masked** id (`sho***@okaxis`, i.e. anything containing `***`) is never evidence: the hidden
 *    characters are exactly the ones a comparison would need, so a masked value can never match and can
 *    never be treated as a mismatch either. It simply yields `false`.
 *  - Nothing here logs. [comparisonSummary] is the only string this object returns, and it contains
 *    masked handles plus counts only — never a raw UPI id, never a printed name.
 *  - Every function is pure: the [payees] list is only read (`isEmpty`/`any`/`take`/`size`), never
 *    mutated, so callers may hand over a Compose state list without defensive copying.
 *
 * ## Honesty rules (binding)
 *  - A name match is **not** evidence. Two different UPI ids may print the same shop name; only the
 *    normalised id comparison below can say "this is a registered payee", and even that says nothing
 *    about whether money moved.
 *  - Matching is exact (no partial local-part, no fuzzy, no domain-only). `payee@okaxis` must never
 *    match `other@okaxis`, and `shop@ybl` must never match `shop@okaxis`: on a shared handle a wrong
 *    local part is precisely the swap QR-Guard exists to catch.
 */
object QrPayeePaymentBridge {

    /** Entries listed by [comparisonSummary] before it switches to a `+N more` tail. */
    private const val MAX_COMPARISON_ENTRIES = 3

    /** Marker produced by every masking helper in the app; its presence means "not comparable". */
    private const val MASK_MARKER = "***"

    /**
     * Builds the advisory engine's input from a payment screenshot and the currently registered payees.
     *
     * [registeredPayeeCount] is `payees.size` (how many payees could have matched at all, so the engine
     * can distinguish "no payee registered" from "registered but different"), and
     * [payeeMatchesRegistered] is [matchesRegisteredPayee] of the screenshot's id against the same list.
     * [sourceText], [fields], [nowEpochMs] and [ocrUsed] are passed through unchanged — this function
     * parses nothing and never rewrites the text it was given.
     *
     * @param sourceText the exact text handed to the advisory engine (pasted or OCR'd); not retained.
     * @param fields the parsed payment fields, including the raw `payeeUpi` used for comparison only.
     * @param payees the payees registered in QR Guard. Read-only here; ordering is irrelevant.
     * @param nowEpochMs "now" in epoch millis, a parameter so callers (and tests) control the clock.
     * @param ocrUsed true when [sourceText] came from OCR rather than a typed paste.
     */
    fun toAdvisoryInput(
        sourceText: String,
        fields: PaymentFields,
        payees: List<RegisteredPayee>,
        nowEpochMs: Long,
        ocrUsed: Boolean = false
    ): PaymentAdvisoryInput = PaymentAdvisoryInput(
        sourceText = sourceText,
        fields = fields,
        registeredPayeeCount = payees.size,
        payeeMatchesRegistered = matchesRegisteredPayee(fields.payeeUpi, payees),
        nowEpochMs = nowEpochMs,
        ocrUsed = ocrUsed
    )

    /**
     * True only when [payeeUpi] is a readable, non-masked id that equals one of [payees]' keys exactly
     * after trimming and lowercasing both sides.
     *
     * Returns `false` (never throws) for a blank [payeeUpi], for a masked value such as
     * `sho***@okaxis`, and for an empty [payees] list — in all three cases no comparison was possible,
     * which the engine reports as its own hint rather than as a match or a mismatch.
     */
    fun matchesRegisteredPayee(payeeUpi: String, payees: List<RegisteredPayee>): Boolean {
        val candidate = comparableId(payeeUpi) ?: return false
        if (payees.isEmpty()) return false
        return payees.any { it.key == candidate }
    }

    /**
     * One-line, log-safe description of what was compared, e.g.
     * `payees=2, masked=[sho***@okaxis, de***@ybl]`, or with a tail:
     * `payees=5, masked=[a***@..., ...] +2 more`.
     *
     * Masked handles only: no raw UPI id, no printed payee name, no length of the hidden local part.
     * Safe to place in an exception message, a debug line or a support bundle.
     */
    fun comparisonSummary(payees: List<RegisteredPayee>): String {
        val listed = payees.take(MAX_COMPARISON_ENTRIES).map { it.maskedHandle }
        val head = "payees=${payees.size}, masked=[" + listed.joinToString(", ") + "]"
        val omitted = payees.size - listed.size
        return if (omitted > 0) "$head +$omitted more" else head
    }

    /**
     * The only form of an id this object will compare: trimmed, lowercased, or `null` when it must not
     * be compared at all (blank, or already masked by `maskedUpi(...)` / [maskPayeeId] / `PayeeMasking`).
     */
    private fun comparableId(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        // `***` is what every mask in the app emits; the hidden characters are exactly what a
        // comparison needs, so a masked id is "not comparable", never a match and never a mismatch.
        if (trimmed.contains(MASK_MARKER)) return null
        return normalizePayeeId(trimmed)
    }
}
