package ai.trustshield.android.core.payment

import java.time.DateTimeException
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.util.Locale
import kotlin.math.abs

/**
 * Deterministic field extraction from the text of a payment screenshot.
 *
 * Scope and limits (binding):
 *  - **Text in, [PaymentFields] out.** Regex and string operations only: no `android.*`, no I/O, no
 *    clock, no OCR, no network. The same text always yields the same fields.
 *  - **Never throws.** Input is bounded to [MAX_INPUT_CHARS] and each field extraction is guarded, so
 *    even a megabyte of junk returns blanks instead of an exception.
 *  - **Hints only.** A screenshot never proves settlement (`docs/android/BEHAVIOR_SPEC.md`, "QR and
 *    payment semantics"). These fields are a soft reading of a picture; the caller still has to show
 *    the bank-SMS / UPI-history confirmation line.
 *  - **Privacy.** Nothing is logged and nothing here overrides `toString`. The full UPI id only ever
 *    lives inside [PaymentFields], whose own `toString` masks it, so [PaymentFields.payeeUpi] must
 *    never be rendered or logged raw.
 *
 * Parsing is deliberately conservative: an amount exists only when a currency marker precedes the
 * number, a UTR only when it is a 12-digit token (labelled or standalone), and a masked UPI id is
 * dropped rather than guessed at. A field that cannot be read stays blank instead of being invented.
 */
object PaymentFieldsParser {

    /**
     * Hard bound on how many characters are inspected. Longer input is truncated, not rejected, so an
     * oversized OCR blob costs the same as this many characters and cannot blow up the caller's frame.
     */
    const val MAX_INPUT_CHARS: Int = 4000

    // ---- bounds ---------------------------------------------------------------------------

    /** How many currency-marked amount candidates are inspected before giving up. */
    private const val MAX_AMOUNT_CANDIDATES = 12

    /** How many UPI-shaped tokens are collected before "nearest the cue" selection runs. */
    private const val MAX_UPI_CANDIDATES = 16

    private const val MAX_UTR_CANDIDATES = 16

    private const val MAX_STATUS_CANDIDATES = 12

    /** How many occurrences of one payee cue are inspected. */
    private const val MAX_CUE_OCCURRENCES = 8

    /** Longest printed payee name kept, in characters. */
    private const val MAX_PAYEE_NAME_CHARS = 60

    /** A rupee part longer than this cannot be a real amount and is skipped instead of overflowing. */
    private const val MAX_WHOLE_DIGITS = 15

    /** How far after a payee cue a UPI id may start and still count as "next to" the cue. */
    private const val CUE_WINDOW_CHARS = 80

    /** How much text before a bare 12-digit token is inspected for a currency marker. */
    private const val UTR_CONTEXT_CHARS = 8

    // ---- amount ---------------------------------------------------------------------------

    /**
     * `₹`, `Rs`, `Rs.`, `INR` or `Rupees`. The RUPEES/INR/RS forms are guarded so that ordinary words
     * ("cars", "sir", "mein") cannot be read as a currency marker.
     */
    private const val CURRENCY_MARKER =
        """(?:\u20B9|(?<![A-Za-z])rs\.?|(?<![A-Za-z])inr(?![A-Za-z])|(?<![A-Za-z])rupees(?![A-Za-z]))"""

    /**
     * A number with optional thousands separators and at most two paise digits. A separator must be
     * followed by a digit, so a trailing comma in the OCR text is not swallowed into the amount.
     */
    private const val AMOUNT_NUMBER = """[0-9](?:[0-9]|,(?=[0-9]))*(?:\.[0-9]{1,2})?"""

    private val AMOUNT_PATTERN = Regex("""(?i)$CURRENCY_MARKER[ \t\u00A0]*($AMOUNT_NUMBER)(?![0-9,])""")

    // ---- payee UPI ------------------------------------------------------------------------
    //
    // MASKING: a masked UPI id (`sho***@okaxis`, or the middle-masked `ab***cd@paytm`) is never
    // reported as a payee. A masked id cannot be compared against a registered payee: a comparison
    // hashes the identifier, and the `*` characters are not part of the real VPA, so an HMAC of the
    // masked string is meaningless (it would never match, and it would never be *right* either). The
    // parser therefore reports a blank payeeUpi, which makes the advisory say "payee missing" instead
    // of pretending a comparison happened. The lookbehind excludes `*` so the tail of a middle-masked
    // id is not picked up as a bogus payee either.

    private val UPI_PATTERN = Regex(
        """(?<![A-Za-z0-9._*-])[A-Za-z0-9._-]{2,}@[A-Za-z][A-Za-z0-9.-]*[A-Za-z0-9](?=$|[\s.,!?;:&?#])"""
    )

    private val UPI_LIKE = Regex("""[A-Za-z0-9.*_-]+@[A-Za-z0-9.*_-]+""")

    /** Specific payee cues, tried in this order; each one beats the generic `to`. */
    private val PAYEE_CUES: List<Regex> = listOf(
        Regex("""(?i)paid[ \t]+to"""),
        Regex("""(?i)payee"""),
        Regex("""(?i)upi[ \t]*id"""),
        Regex("""(?i)vpa"""),
        Regex("""(?i)to[ \t]*:"""),
    )

    /** The bare word `to` (`Pay to shop@okaxis`), word-bounded so "storage" does not qualify. */
    private val PAYEE_CUE_GENERIC = Regex("""(?i)(?<![A-Za-z])to(?![A-Za-z])""")

    // ---- payee name -----------------------------------------------------------------------

    private val PAYEE_NAME_CUES: List<Regex> = listOf(
        Regex("""(?i)paid[ \t]+to[ \t]*[:\-]?"""),
        Regex("""(?i)(?<![A-Za-z0-9])pn[ \t]*="""),
        Regex("""(?i)(?<![A-Za-z])to[ \t]*:"""),
    )

    /** Index in [PAYEE_NAME_CUES] of the `pn=` cue, whose value ends at `&` too (a UPI URI). */
    private const val PN_CUE_INDEX = 1

    private val WHITESPACE_RUN = Regex("""[ \t\u00A0]+""")

    // ---- UTR ------------------------------------------------------------------------------

    private val UTR_LABELLED = Regex(
        """(?i)(?<![A-Za-z])(?:utr|rrn|ref(?:erence)?|txn|transaction)""" +
            """[ \t]*(?:no\.?|number|id|#)?[ \t]*[:#=\-]?[ \t]*""" +
            """([0-9]{4}[ \t\-]?[0-9]{4}[ \t\-]?[0-9]{4})(?![0-9])"""
    )

    private val UTR_STANDALONE = Regex("""(?<![0-9])([0-9]{12})(?![0-9])""")

    private val CURRENCY_CONTEXT = Regex("""(?i)$CURRENCY_MARKER""")

    // ---- status ---------------------------------------------------------------------------

    private val STATUS_FAILED = Regex(
        """(?i)(?<![A-Za-z])(?:failed|failure|declined|cancelled|canceled|reversed)(?![A-Za-z])"""
    )

    private val STATUS_PENDING = Regex(
        """(?i)(?<![A-Za-z])(?:pending|processing|awaiting|in[ \t]+progress)(?![A-Za-z])"""
    )

    private val STATUS_SUCCESS = Regex(
        """(?i)(?<![A-Za-z])(?:successful|success|paid|completed|done)(?![A-Za-z])"""
    )

    // ---- timestamp ------------------------------------------------------------------------

    private val TIMESTAMP_PATTERNS: List<Regex> = listOf(
        Regex("""(?<![0-9])([0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}(?::[0-9]{2})?)(?![0-9])"""),
        Regex(
            """(?<![0-9])([0-9]{1,2}[ \t]+[A-Za-z]{3,9}[ \t]+[0-9]{4},?[ \t]*""" +
                """[0-9]{1,2}:[0-9]{2}[ \t]*[AaPp]\.?[Mm]\.?)(?![A-Za-z])"""
        ),
        Regex("""(?<![0-9])([0-9]{1,2}/[0-9]{1,2}/[0-9]{4}[ \t]+[0-9]{1,2}:[0-9]{2}(?::[0-9]{2})?)(?![0-9])"""),
        Regex("""(?<![0-9])([0-9]{1,2}-[0-9]{1,2}-[0-9]{4}[ \t]+[0-9]{1,2}:[0-9]{2}(?::[0-9]{2})?)(?![0-9])"""),
    )

    private val MONTH_TIME_FORMATTER: DateTimeFormatter = DateTimeFormatterBuilder()
        .parseCaseInsensitive()
        .appendPattern("d MMM yyyy, h:mm a")
        .toFormatter(Locale.ENGLISH)

    private val MONTH_TIME_FORMATTER_NO_COMMA: DateTimeFormatter = DateTimeFormatterBuilder()
        .parseCaseInsensitive()
        .appendPattern("d MMM yyyy h:mm a")
        .toFormatter(Locale.ENGLISH)

    private val SLASH_FORMATTER: DateTimeFormatter = numericTimestampFormatter('/')

    private val DASH_FORMATTER: DateTimeFormatter = numericTimestampFormatter('-')

    // ---- app hint -------------------------------------------------------------------------

    private val BANK_MARKERS: List<String> =
        listOf("hdfc", "sbi", "icici", "axis", "kotak", "bank")

    // =======================================================================================
    // public API
    // =======================================================================================

    /**
     * Reads the payment fields out of [text].
     *
     * Never throws: blank, emoji-only, mismatched or arbitrarily large input comes back with blank
     * fields and [PaymentStatus.UNKNOWN]. Only the first [MAX_INPUT_CHARS] characters are inspected.
     *
     * Every value that can be read also keeps the exact source substring it came from, so a UI can
     * quote the receipt instead of paraphrasing it.
     */
    fun parse(text: String): PaymentFields {
        val scan = bounded(text)
        if (scan.isEmpty()) return PaymentFields()

        val amount: AmountHit? = guarded(null) { findAmount(scan) }
        val upis: List<UpiHit> = guarded(emptyList<UpiHit>()) { findUpis(scan) }
        val upi: UpiHit? = guarded(null) { chooseUpi(scan, upis) }
        // "Nearest the UPI/amount context" needs a position to measure against.
        val contextIndex: Int = upi?.start ?: amount?.start ?: 0

        val status: StatusHit? = guarded(null) { findStatus(scan, contextIndex) }
        val timestamp: TimestampHit? = guarded(null) { findTimestamp(scan) }

        return PaymentFields(
            amountMinor = amount?.minor,
            amountRaw = amount?.raw ?: "",
            payeeUpi = upi?.value ?: "",
            payeeName = guarded("") { findPayeeName(scan) },
            utr = guarded("") { findUtr(scan) },
            status = status?.status ?: PaymentStatus.UNKNOWN,
            statusRaw = status?.raw ?: "",
            timestampRaw = timestamp?.raw ?: "",
            timestampEpochMs = timestamp?.epochMs,
            appHint = guarded(PaymentAppHint.UNKNOWN) { findAppHint(scan) },
        )
    }

    /**
     * True when [text] reads as a payment receipt: a currency-marked amount **and** at least one
     * payment-specific marker (a UPI id, a UTR or a status word), i.e. [PaymentFields.looksLikeReceipt].
     *
     * The amount check runs first and is the cheap part, so a plain SMS ("aapka account band ho
     * jayega, call 9876543210") costs one pass and returns false without a full parse.
     */
    fun looksLikePaymentScreenshot(text: String): Boolean {
        val scan = bounded(text)
        if (scan.isEmpty()) return false
        val hasCurrencyAmount: Boolean = guarded(false) { findAmount(scan) != null }
        if (!hasCurrencyAmount) return false
        return guarded(false) { parse(scan).looksLikeReceipt }
    }

    // =======================================================================================
    // amount
    // =======================================================================================

    private class AmountHit(val raw: String, val minor: Long, val start: Int)

    /**
     * The first currency-marked amount. A bare number is never an amount, which is what keeps a UTR,
     * an account number or a phone number from being read as money.
     */
    private fun findAmount(scan: String): AmountHit? {
        var inspected = 0
        for (match in AMOUNT_PATTERN.findAll(scan)) {
            inspected += 1
            if (inspected > MAX_AMOUNT_CANDIDATES) return null
            val minor = paise(match.groupValues[1]) ?: continue
            if (minor <= 0L) continue // "₹0" is not a payment
            return AmountHit(raw = match.value, minor = minor, start = match.range.first)
        }
        return null
    }

    /**
     * `1,200.50` -> 120050. Separators are dropped rather than validated: Indian (`12,00,000`) and
     * western (`1,200,000`) grouping both read as the same number, and an OCR comma slip stays
     * harmless instead of discarding a real amount.
     */
    private fun paise(digits: String): Long? {
        val cleaned = digits.replace(",", "")
        val dot = cleaned.indexOf('.')
        val whole = if (dot < 0) cleaned else cleaned.substring(0, dot)
        val fraction = if (dot < 0) "" else cleaned.substring(dot + 1)
        if (whole.isEmpty() || whole.length > MAX_WHOLE_DIGITS) return null
        for (ch in whole) {
            if (ch !in '0'..'9') return null
        }
        for (ch in fraction) {
            if (ch !in '0'..'9') return null
        }
        if (fraction.length > 2) return null
        val rupees = whole.toLongOrNull() ?: return null
        val paiseOfRupee = when (fraction.length) {
            0 -> 0L
            1 -> (fraction[0] - '0').toLong() * 10L
            else -> (fraction[0] - '0').toLong() * 10L + (fraction[1] - '0').toLong()
        }
        return rupees * 100L + paiseOfRupee
    }

    // =======================================================================================
    // payee
    // =======================================================================================

    private class UpiHit(val value: String, val start: Int)

    private fun findUpis(scan: String): List<UpiHit> {
        val hits = ArrayList<UpiHit>(4)
        for (match in UPI_PATTERN.findAll(scan)) {
            if (hits.size >= MAX_UPI_CANDIDATES) break
            val value = normaliseUpi(match.value) ?: continue
            hits.add(UpiHit(value = value, start = match.range.first))
        }
        return hits
    }

    /** Trailing sentence punctuation and a leading dot are not part of a VPA. */
    private fun normaliseUpi(raw: String): String? {
        // Do not "repair" malformed VPAs: stripping punctuation can turn an OCR fragment or a
        // prefix of a larger invalid token into a different, apparently valid payee.
        if (raw.contains('*')) return null // masked: never comparable, see the MASKING note above
        val value = raw
        val at = value.indexOf('@')
        if (at < 2 || at != value.lastIndexOf('@') || at == value.length - 1) return null
        if (value.length > 255) return null
        val local = value.substring(0, at)
        val handle = value.substring(at + 1)
        if (local.first() !in 'A'..'Z' && local.first() !in 'a'..'z' && local.first() !in '0'..'9') return null
        if (local.last() !in 'A'..'Z' && local.last() !in 'a'..'z' && local.last() !in '0'..'9') return null
        if (".." in local || "--" in local || "_." in local || "._" in local) return null
        if (handle.first() !in 'A'..'Z' && handle.first() !in 'a'..'z') return null
        if (handle.last() !in 'A'..'Z' && handle.last() !in 'a'..'z' && handle.last() !in '0'..'9') return null
        if (".." in handle || handle.startsWith('-') || handle.contains(".-") || handle.contains("-.")) return null
        return value
    }

    /**
     * Prefers the UPI id printed next to a payee cue (a support address in the footer must not win over
     * the "Paid to" line), and falls back to the first valid id in the text.
     */
    private fun chooseUpi(scan: String, hits: List<UpiHit>): UpiHit? {
        // A masked recipient beside a footer address is ambiguous; do not promote the footer to payee.
        if (UPI_LIKE.findAll(scan).any { it.value.contains('*') }) return null
        if (hits.isEmpty()) return null
        for (cue in PAYEE_CUES) {
            val hit = firstUpiAfterCue(scan, hits, cue)
            if (hit != null) return hit
        }
        return firstUpiAfterCue(scan, hits, PAYEE_CUE_GENERIC) ?: hits.first()
    }

    private fun firstUpiAfterCue(scan: String, hits: List<UpiHit>, cue: Regex): UpiHit? {
        var from = 0
        var seen = 0
        while (seen < MAX_CUE_OCCURRENCES) {
            val match = cue.find(scan, from) ?: return null
            seen += 1
            val windowStart = match.range.last + 1
            val windowEnd = windowStart + CUE_WINDOW_CHARS
            for (hit in hits) {
                if (hit.start >= windowStart && hit.start <= windowEnd) return hit
            }
            from = match.range.first + 1
        }
        return null
    }

    /**
     * The printed payee name after `Paid to`, `To:` or `pn=`, up to the end of the line (or the next
     * non-blank line when the cue sits alone on its own line). Blank when absent, and never the UPI id
     * itself, never the amount: a name is a label a sender controls, not evidence.
     */
    private fun findPayeeName(scan: String): String {
        for ((index, cue) in PAYEE_NAME_CUES.withIndex()) {
            val match = cue.find(scan) ?: continue
            val line = firstNonBlankLine(scan.substring(match.range.last + 1), stopAtAmpersand = index == PN_CUE_INDEX)
            val cleaned = cleanPayeeName(line)
            if (cleaned.isNotEmpty()) return cleaned
        }
        if (PAYEE_NAME_CUES.any { it.containsMatchIn(scan) } ||
            !AMOUNT_PATTERN.containsMatchIn(scan) || findUpis(scan).isEmpty()
        ) return ""
        return cleanPayeeName(scan.substringBefore('\n').substringBefore('\r'))
    }

    private fun firstNonBlankLine(remainder: String, stopAtAmpersand: Boolean): String {
        var remaining = remainder
        var lines = 0
        while (remaining.isNotEmpty() && lines < 2) {
            val end = lineEnd(remaining, stopAtAmpersand)
            val line = if (end < 0) remaining else remaining.substring(0, end)
            if (line.isNotBlank()) return line.trim()
            if (end < 0) return ""
            remaining = remaining.substring(end + 1)
            lines += 1
        }
        return ""
    }

    private fun lineEnd(text: String, stopAtAmpersand: Boolean): Int {
        for (index in text.indices) {
            val ch = text[index]
            if (ch == '\n' || ch == '\r') return index
            if (stopAtAmpersand && (ch == '&' || ch == '?' || ch == '|')) return index
        }
        return -1
    }

    private fun cleanPayeeName(raw: String): String {
        var name = raw
        name = UPI_LIKE.replace(name, " ")
        name = AMOUNT_PATTERN.replace(name, " ")
        name = name.replace('|', ' ')
        name = WHITESPACE_RUN.replace(name, " ").trim()
        name = name.trim(':', '-', '.', ',', '#', '=', '(', ')', '/', '\\')
        if (name.isEmpty()) return ""
        if (name.none { it.isLetter() }) return "" // digits or punctuation only: not a name
        if (name.length > MAX_PAYEE_NAME_CHARS) name = name.substring(0, MAX_PAYEE_NAME_CHARS)
        return name.trim()
    }

    // =======================================================================================
    // UTR
    // =======================================================================================

    /**
     * A labelled reference (`UTR: 526312345678`, `RRN 1234 5678 9012`, `Txn ID ...`) wins; otherwise a
     * standalone 12-digit token, unless a currency marker sits right before it (then it is an amount,
     * not a reference). Ten-digit phone numbers cannot match: the token must be exactly 12 digits.
     */
    private fun findUtr(scan: String): String {
        var labelled = 0
        for (match in UTR_LABELLED.findAll(scan)) {
            labelled += 1
            if (labelled > MAX_UTR_CANDIDATES) break
            val digits = digitsOnly(match.groupValues[1])
            if (digits.length == 12) return digits
        }
        var inspected = 0
        for (match in UTR_STANDALONE.findAll(scan)) {
            inspected += 1
            if (inspected > MAX_UTR_CANDIDATES) break
            if (looksLikeAmountContext(scan, match.range.first)) continue
            return match.groupValues[1]
        }
        return ""
    }

    private fun digitsOnly(raw: String): String {
        val out = StringBuilder(raw.length)
        for (ch in raw) if (ch in '0'..'9') out.append(ch)
        return out.toString()
    }

    private fun looksLikeAmountContext(scan: String, start: Int): Boolean {
        val from = maxOf(0, start - UTR_CONTEXT_CHARS)
        if (from == start) return false
        return CURRENCY_CONTEXT.containsMatchIn(scan.substring(from, start))
    }

    // =======================================================================================
    // status
    // =======================================================================================

    private class StatusHit(val status: PaymentStatus, val start: Int, val raw: String)

    /**
     * FAILED beats PENDING beats SUCCESS. A pending or failed line on the same screen is a stronger
     * statement than the word "success" somewhere in the chrome, so SUCCESS is never reported when a
     * failure word is present anywhere in the text.
     *
     * Among hits of the winning status, the one nearest [contextIndex] (the UPI, else the amount) wins;
     * ties go to the one printed first.
     */
    private fun findStatus(scan: String, contextIndex: Int): StatusHit? {
        val hits = ArrayList<StatusHit>(4)
        collectStatus(scan, STATUS_FAILED, PaymentStatus.FAILED, hits)
        collectStatus(scan, STATUS_PENDING, PaymentStatus.PENDING, hits)
        collectStatus(scan, STATUS_SUCCESS, PaymentStatus.SUCCESS, hits)
        if (hits.isEmpty()) return null
        hits.sortBy { it.start }

        val winner = when {
            hits.any { it.status == PaymentStatus.FAILED } -> PaymentStatus.FAILED
            hits.any { it.status == PaymentStatus.PENDING } -> PaymentStatus.PENDING
            else -> PaymentStatus.SUCCESS
        }
        var best: StatusHit? = null
        var bestDistance = Int.MAX_VALUE
        for (hit in hits) {
            if (hit.status != winner) continue
            val distance = abs(hit.start - contextIndex)
            if (distance < bestDistance) {
                best = hit
                bestDistance = distance
            }
        }
        return best
    }

    private fun collectStatus(
        scan: String,
        pattern: Regex,
        status: PaymentStatus,
        into: MutableList<StatusHit>
    ) {
        var count = 0
        for (match in pattern.findAll(scan)) {
            count += 1
            if (count > MAX_STATUS_CANDIDATES) break
            into.add(StatusHit(status = status, start = match.range.first, raw = match.value))
        }
    }

    // =======================================================================================
    // timestamp
    // =======================================================================================

    private class TimestampHit(val raw: String, val start: Int, val epochMs: Long?)

    /**
     * The earliest timestamp-looking substring in the text, with its parsed value.
     *
     * A receipt time carries no zone, so it is read in the JVM default zone. [TimestampHit.epochMs] is
     * `null` when the value is not a real date (31 February) — never 0, so the caller can say "time
     * missing" instead of "time is 1970".
     */
    private fun findTimestamp(scan: String): TimestampHit? {
        var best: MatchResult? = null
        var bestStart = Int.MAX_VALUE
        for (pattern in TIMESTAMP_PATTERNS) {
            val match = pattern.find(scan) ?: continue
            if (match.range.first < bestStart) {
                best = match
                bestStart = match.range.first
            }
        }
        val match = best ?: return null
        val raw = match.groupValues[1].trim()
        return TimestampHit(raw = raw, start = match.range.first, epochMs = parseTimestamp(raw))
    }

    private fun parseTimestamp(raw: String): Long? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        // "7:04 p.m." -> "7:04 pm": the dots are OCR noise, and the format itself has no dots.
        val candidate = text.replace(".", "")
        return try {
            val zone = ZoneId.systemDefault()
            val parsed = tryParseTimestamp(text) ?: tryParseTimestamp(candidate) ?: return null
            parsed.atZone(zone).toInstant().toEpochMilli()
        } catch (_: DateTimeException) {
            null
        } catch (_: ArithmeticException) {
            null
        }
    }

    private fun tryParseTimestamp(text: String): LocalDateTime? {
        for (formatter in TIMESTAMP_FORMATTERS) {
            try {
                return LocalDateTime.parse(text, formatter)
            } catch (_: DateTimeException) {
                // try the next shape
            }
        }
        return null
    }

    private fun numericTimestampFormatter(separator: Char): DateTimeFormatter =
        DateTimeFormatterBuilder()
            .parseCaseInsensitive()
            .appendPattern("d" + separator + "M" + separator + "yyyy H:mm")
            .optionalStart()
            .appendPattern(":ss")
            .optionalEnd()
            .toFormatter(Locale.ENGLISH)

    private val TIMESTAMP_FORMATTERS: List<DateTimeFormatter> = listOf(
        MONTH_TIME_FORMATTER,
        MONTH_TIME_FORMATTER_NO_COMMA,
        SLASH_FORMATTER,
        DASH_FORMATTER,
        DateTimeFormatter.ISO_LOCAL_DATE_TIME,
    )

    // =======================================================================================
    // app hint
    // =======================================================================================

    /** Which app's chrome the text mentions. `gpay`/`google pay` first, then the wallets, then banks. */
    private fun findAppHint(scan: String): PaymentAppHint {
        val lower = scan.lowercase(Locale.ROOT)
        return when {
            lower.contains("gpay") || lower.contains("google pay") -> PaymentAppHint.GPAY
            lower.contains("phonepe") || lower.contains("phone pe") -> PaymentAppHint.PHONEPE
            lower.contains("paytm") -> PaymentAppHint.PAYTM
            lower.contains("bhim") -> PaymentAppHint.BHIM
            BANK_MARKERS.any { lower.contains(it) } -> PaymentAppHint.BANK_APP
            else -> PaymentAppHint.UNKNOWN
        }
    }

    // =======================================================================================
    // plumbing
    // =======================================================================================

    private fun bounded(text: String): String {
        if (text.isEmpty()) return ""
        return if (text.length <= MAX_INPUT_CHARS) text else text.substring(0, MAX_INPUT_CHARS)
    }

    /** Field-level fail-closed: one pathological field must not cost the caller the other nine. */
    private inline fun <T> guarded(fallback: T, block: () -> T): T =
        try {
            block()
        } catch (_: Throwable) {
            fallback
        }
}
