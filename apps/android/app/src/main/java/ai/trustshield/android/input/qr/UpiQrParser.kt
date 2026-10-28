package ai.trustshield.android.input.qr

import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.nio.charset.StandardCharsets

/**
 * Classification of a raw QR payload.
 *
 * Mirrors `classify_payload` in `services/analysis/extractors/qr.py` one for one, including the
 * evaluation order: `upi://` is decided first, then generic `scheme://` URLs plus `javascript:` and
 * `data:`, then non-web schemes (`tel|mailto|sms|smsto|geo|wifi`) and vCard/WiFi blocks, else text.
 * [wire] is the exact string the Python backend stores, so parity is assertable in tests.
 */
enum class QrPayloadKind(val wire: String) {
    /** `upi://...` — parsed structurally by [UpiQrParser.parse]. */
    UPI("upi"),

    /** Web URL, `javascript:` or `data:`. Never opened, never resolved. */
    URL("url"),

    /** `tel:`, `mailto:`, `sms:`, `smsto:`, `geo:`, `wifi:`, `BEGIN:VCARD`, `BEGIN:WIFI`. Never executed. */
    OTHER("other"),

    /** Anything else, including an empty payload. */
    TEXT("text"),
}

/**
 * An explicit anomaly flag. Each value carries the evidence category string the Python backend
 * `_parse_upi` emits for the same condition, so the two can be compared in tests.
 */
enum class UpiFlag(val evidenceCategory: String?) {
    /** `qr_payment_request` — always present for a structurally parsed `upi://` payload. */
    PAYMENT_REQUEST("qr_payment_request"),

    /** `qr_malformed_fields` — a field exceeded its structural limit and was nulled. */
    OVERSIZED_FIELDS("qr_malformed_fields"),

    /** `qr_missing_payee` — `pa` absent or blank. */
    MISSING_PAYEE("qr_missing_payee"),

    /** `qr_malformed_payee` — `pa` failed the structural VPA pattern. */
    MALFORMED_PAYEE("qr_malformed_payee"),

    /** Android-only fail-closed signal: more than one `pa` value was supplied. */
    AMBIGUOUS_PAYEE(null),

    /** `qr_prefilled_amount` — `am` is a well-formed amount inside the accepted range. */
    PREFILLED_AMOUNT("qr_prefilled_amount"),

    /** `qr_malformed_amount` — `am` present but not an acceptable money value. */
    MALFORMED_AMOUNT("qr_malformed_amount"),

    /** `qr_collect_mode` — `mode` is a collect request, which asks the payer to approve a debit. */
    COLLECT_MODE("qr_collect_mode"),

    /**
     * Android-only hardening bound: the payload exceeded [UpiQrParser.MAX_PAYLOAD_CHARS] and only
     * the leading window was inspected. Has no backend evidence category (the backend is unbounded).
     */
    TRUNCATED_PAYLOAD(null),
}

/**
 * Result of a structural QR parse. Holds no raw payload, only the selected fields.
 *
 * Contract, matching the backend's `upi` dict exactly:
 * - `null` means the parameter was absent.
 * - `""` means the parameter was present but blank (Python `parse_qs(keep_blank_values=True)`).
 * - values are control-stripped and truncated to the backend field limit; a field that was too long
 *   to trust is `null` plus [UpiFlag.OVERSIZED_FIELDS] (except `pa`, which the backend keeps truncated
 *   to 320 chars and flags as [UpiFlag.MALFORMED_PAYEE]).
 * - a flag is the only trustworthy signal: e.g. with [UpiFlag.MALFORMED_AMOUNT] the [amount] string
 *   must not be shown as an amount.
 *
 * [toString] never prints raw content (VPA masked, everything else reduced to a length), so an
 * accidental log line cannot leak a payee or a note.
 */
data class UpiPayload(
    val kind: QrPayloadKind,
    val payeeVpa: String? = null,
    val payeeName: String? = null,
    val amount: String? = null,
    val currency: String? = null,
    val note: String? = null,
    val merchantCode: String? = null,
    val mode: String? = null,
    /**
     * Backend-mirrored flags in the backend's evidence order; [UpiFlag.TRUNCATED_PAYLOAD] last.
     */
    val flags: Set<UpiFlag> = emptySet(),
    /**
     * Backend field names (`payee_name`, `amount`, ...) that exceeded their limit, in the backend's
     * `field_limits` declaration order. Mirrors `fields=` metadata on `qr_malformed_fields`.
     */
    val oversizedFields: Set<String> = emptySet(),
) {
    val isUpi: Boolean get() = kind == QrPayloadKind.UPI

    fun hasFlag(flag: UpiFlag): Boolean = flags.contains(flag)

    /** `ra***@okaxis` — safe for a screen or a diagnostic line. */
    fun maskedPayeeVpa(): String? = UpiQrParser.maskVpa(payeeVpa)

    override fun toString(): String = buildString {
        append("UpiPayload(kind=").append(kind)
        append(", payeeVpa=").append(UpiQrParser.maskVpa(payeeVpa))
        append(", payeeName=").append(sizeOf(payeeName))
        append(", amount=").append(sizeOf(amount))
        append(", currency=").append(sizeOf(currency))
        append(", note=").append(sizeOf(note))
        append(", merchantCode=").append(sizeOf(merchantCode))
        append(", mode=").append(sizeOf(mode))
        append(", flags=").append(flags)
        append(", oversizedFields=").append(oversizedFields)
        append(')')
    }

    private fun sizeOf(value: String?): String = if (value == null) "null" else "${value.length}ch"
}

/**
 * Structural `upi://pay` parser. Pure Kotlin, no `android.*`, no `java.net`, no I/O: the payload is
 * DATA only. Nothing here opens, resolves, fetches or navigates to a payload — there is no `Intent`,
 * no `startActivity`, no socket. Verdict/scoring is not this class's business.
 *
 * Field-for-field parity target: `_parse_upi` in `services/analysis/pipeline.py` and
 * `classify_payload` in `services/analysis/extractors/qr.py`.
 */
object UpiQrParser {

    /** Hard bound on how much of a payload is inspected. 8 KiB is roughly twice a max-size QR. */
    const val MAX_PAYLOAD_CHARS: Int = 8_192

    /** Hard bound on how many chars of a single name/value are percent-decoded. */
    private const val MAX_FIELD_INPUT_CHARS: Int = 4_096

    /** Longer decoded names cannot match any UPI parameter, so they are not decoded at all. */
    private const val MAX_NAME_CHARS: Int = 64

    private const val VPA_LIMIT = 320
    private const val PAYEE_NAME_LIMIT = 200
    private const val AMOUNT_LIMIT = 32
    private const val CURRENCY_LIMIT = 8
    private const val NOTE_LIMIT = 500
    private const val MERCHANT_CODE_LIMIT = 16
    private const val MODE_LIMIT = 32

    private val SCHEME_PREFIX = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://")
    private val SCRIPT_PREFIX = Regex("^(?:javascript|data):", RegexOption.IGNORE_CASE)
    private val NON_WEB_PREFIX = Regex("^(?:tel|mailto|sms|smsto|geo|wifi):", RegexOption.IGNORE_CASE)

    /** Same pattern as the backend. `matches` = full-string, which is stricter than Python's `$`. */
    private val VPA_PATTERN = Regex("^[A-Za-z0-9._-]{2,256}@[A-Za-z0-9.-]{2,64}$")

    /** ASCII decimal only; see [amountIsAcceptable] for the deliberate tightening versus `Decimal`. */
    private val DECIMAL_PATTERN = Regex("^[+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][+-]?\\d+)?$")

    private val MAX_AMOUNT: BigDecimal = BigDecimal("100000000")

    private val COLLECT_MODES: Set<String> = setOf("04", "collect", "collect-request")

    /** The seven parameters the backend selects, with their structural limits, in backend order. */
    private val FIELD_LIMITS: List<Pair<String, Int>> = listOf(
        "payee_name" to PAYEE_NAME_LIMIT,
        "amount" to AMOUNT_LIMIT,
        "currency" to CURRENCY_LIMIT,
        "note" to NOTE_LIMIT,
        "merchant_code" to MERCHANT_CODE_LIMIT,
        "mode" to MODE_LIMIT,
    )

    private val PAYLOAD_KEYS: Set<String> = setOf("pa", "pn", "am", "cu", "tn", "mc", "mode")

    /**
     * Classifies a payload. `null` and blank classify as [QrPayloadKind.TEXT], like the backend's
     * `(payload or "").strip()`.
     */
    fun classify(payload: String?): QrPayloadKind {
        val raw = (payload ?: "").trim()
        if (raw.length >= 6 && raw.substring(0, 6).lowercase() == "upi://") return QrPayloadKind.UPI
        if (SCHEME_PREFIX.containsMatchIn(raw)) return QrPayloadKind.URL
        if (SCRIPT_PREFIX.containsMatchIn(raw)) return QrPayloadKind.URL
        if (NON_WEB_PREFIX.containsMatchIn(raw)) return QrPayloadKind.OTHER
        val upper = raw.uppercase()
        if (upper.startsWith("BEGIN:VCARD") || upper.startsWith("BEGIN:WIFI")) return QrPayloadKind.OTHER
        return QrPayloadKind.TEXT
    }

    /**
     * Structurally parses a payload. Never throws for any input, never navigates, and does no I/O.
     * Non-UPI kinds come back with every field `null` and no flags.
     */
    fun parse(payload: String?): UpiPayload {
        val kind = classify(payload)
        if (kind != QrPayloadKind.UPI) return UpiPayload(kind = kind)

        // The backend hands urlsplit the *untrimmed* payload, so trailing spaces stay inside values.
        val payloadText = payload ?: ""
        val truncated = payloadText.length > MAX_PAYLOAD_CHARS
        val scan = if (truncated) payloadText.substring(0, MAX_PAYLOAD_CHARS) else payloadText

        val flags = LinkedHashSet<UpiFlag>()
        val oversized = LinkedHashSet<String>()
        flags.add(UpiFlag.PAYMENT_REQUEST)

        val query = parseQuery(extractQuery(scan))
        val fields = query.values
        if ("pa" in query.duplicateKeys) flags.add(UpiFlag.AMBIGUOUS_PAYEE)

        val rawSelected = linkedMapOf<String, String?>(
            "payee_vpa" to fields["pa"],
            "payee_name" to fields["pn"],
            "amount" to fields["am"],
            "currency" to fields["cu"],
            "note" to fields["tn"],
            "merchant_code" to fields["mc"],
            "mode" to fields["mode"],
        )

        // Backend: a non-empty value longer than its limit is nulled and reported, in field_limits order.
        for ((key, limit) in FIELD_LIMITS) {
            val value = rawSelected[key]
            if (!value.isNullOrEmpty() && value.length > limit) oversized.add(key)
        }
        if (oversized.isNotEmpty()) {
            flags.add(UpiFlag.OVERSIZED_FIELDS)
            oversized.forEach { rawSelected[it] = null }
        }

        val limits: Map<String, Int> = FIELD_LIMITS.toMap() + ("payee_vpa" to VPA_LIMIT)
        val selected: Map<String, String?> = rawSelected.mapValues { (key, value) ->
            value?.let { stripControls(it, limits.getValue(key)) }
        }

        // VPA: checked on the *raw* (already nulled-if-oversized) value, kept truncated to 320 chars.
        val rawVpa = rawSelected["payee_vpa"] ?: ""
        if (rawVpa.isEmpty()) {
            flags.add(UpiFlag.MISSING_PAYEE)
        } else if (rawVpa.length > VPA_LIMIT || !VPA_PATTERN.matches(rawVpa)) {
            flags.add(UpiFlag.MALFORMED_PAYEE)
        }

        // Amount: only a non-empty selected value is inspected (blank `am=` produces no flag).
        val selectedAmount = selected["amount"]
        if (!selectedAmount.isNullOrEmpty()) {
            if (amountIsAcceptable(selectedAmount)) {
                flags.add(UpiFlag.PREFILLED_AMOUNT)
            } else {
                flags.add(UpiFlag.MALFORMED_AMOUNT)
            }
        }

        if ((selected["mode"] ?: "").lowercase() in COLLECT_MODES) flags.add(UpiFlag.COLLECT_MODE)

        if (truncated) flags.add(UpiFlag.TRUNCATED_PAYLOAD)

        return UpiPayload(
            kind = QrPayloadKind.UPI,
            payeeVpa = selected["payee_vpa"],
            payeeName = selected["payee_name"],
            amount = selected["amount"],
            currency = selected["currency"],
            note = selected["note"],
            merchantCode = selected["merchant_code"],
            mode = selected["mode"],
            flags = flags,
            oversizedFields = oversized,
        )
    }

    /**
     * `ra***@okaxis`. Keeps at most two leading chars of the local part and never returns the full
     * VPA; keeps the handle so a person can still recognise the payee.
     */
    fun maskVpa(vpa: String?): String? {
        val value = stripControls(vpa ?: "", VPA_LIMIT)
        if (value.isBlank()) return null
        val at = value.lastIndexOf('@')
        if (at <= 0 || at == value.length - 1) return maskLocalPart(value)
        return maskLocalPart(value.substring(0, at)) + "@" + value.substring(at + 1)
    }

    private fun maskLocalPart(local: String): String {
        val keep = when {
            local.length >= 3 -> 2
            local.length == 2 -> 1
            else -> 0
        }
        return local.take(keep) + "***"
    }

    /**
     * Bounded equivalent of `urlsplit(payload).query`: CPython drops `\t`, `\r` and `\n`, then the
     * fragment wins over the query, and the query is everything after the first `?`.
     */
    private fun extractQuery(payload: String): String {
        val cleaned = StringBuilder(payload.length)
        for (ch in payload) {
            if (ch != '\t' && ch != '\r' && ch != '\n') cleaned.append(ch)
        }
        val hash = cleaned.indexOf("#")
        val head = if (hash >= 0) cleaned.substring(0, hash) else cleaned.toString()
        val mark = head.indexOf('?')
        return if (mark < 0) "" else head.substring(mark + 1)
    }

    /**
     * Bounded equivalent of `parse_qs(query, keep_blank_values=True)` with last-value-wins:
     * pairs split on `&` only, `=` split once, names lowercased after percent-decoding, a pair without
     * `=` keeps a blank value, and an empty pair is skipped.
     */
    private data class ParsedQuery(val values: Map<String, String>, val duplicateKeys: Set<String>)

    private fun parseQuery(query: String): ParsedQuery {
        val out = LinkedHashMap<String, String>()
        val duplicateKeys = LinkedHashSet<String>()
        for (pair in query.split('&')) {
            if (pair.isEmpty()) continue
            val eq = pair.indexOf('=')
            val rawName = if (eq < 0) pair else pair.substring(0, eq)
            val rawValue = if (eq < 0) "" else pair.substring(eq + 1)
            val name = decodeComponent(rawName, MAX_NAME_CHARS)
            if (name.length > MAX_NAME_CHARS) continue
            val key = name.lowercase()
            if (key !in PAYLOAD_KEYS) continue
            if (key in out) duplicateKeys.add(key)
            out[key] = decodeComponent(rawValue, MAX_FIELD_INPUT_CHARS)
        }
        return ParsedQuery(out, duplicateKeys)
    }

    /**
     * Bounded `unquote_plus`: `+` is a space, `%XX` is one byte, a stray `%` stays literal, invalid
     * UTF-8 becomes U+FFFD. Input is capped at [inputLimit] chars and output at `inputLimit + 8` bytes,
     * so a huge payload cannot expand without bound.
     */
    private fun decodeComponent(source: String, inputLimit: Int): String {
        val end = minOf(source.length, inputLimit)
        val out = ByteArrayOutputStream(minOf(end, 512) + 8)
        val cap = inputLimit + 8
        var i = 0
        while (i < end && out.size() < cap) {
            val ch = source[i]
            when {
                ch == '%' && i + 2 < end && isHexDigit(source[i + 1]) && isHexDigit(source[i + 2]) -> {
                    out.write(hexValue(source[i + 1]) * 16 + hexValue(source[i + 2]))
                    i += 3
                }

                ch == '+' -> {
                    out.write(0x20)
                    i += 1
                }

                ch.code < 0x80 -> {
                    out.write(ch.code)
                    i += 1
                }

                else -> {
                    val codePoint = source.codePointAt(i)
                    val text = String(Character.toChars(codePoint)).toByteArray(StandardCharsets.UTF_8)
                    out.write(text, 0, text.size)
                    i += Character.charCount(codePoint)
                }
            }
        }
        return String(out.toByteArray(), StandardCharsets.UTF_8)
    }

    private fun isHexDigit(ch: Char): Boolean =
        ch in '0'..'9' || ch in 'a'..'f' || ch in 'A'..'F'

    private fun hexValue(ch: Char): Int = when {
        ch in '0'..'9' -> ch - '0'
        ch in 'a'..'f' -> ch - 'a' + 10
        else -> ch - 'A' + 10
    }

    /**
     * Backend `_strip_controls(value, limit)`: drop every code point whose Unicode category starts with
     * `C` (control, format, unassigned, private-use, surrogate), then keep at most [limit] code points.
     */
    private fun stripControls(value: String, limit: Int): String {
        val out = StringBuilder(minOf(value.length, limit))
        var kept = 0
        var i = 0
        while (i < value.length && kept < limit) {
            val codePoint = value.codePointAt(i)
            if (isDisplayable(codePoint)) {
                out.appendCodePoint(codePoint)
                kept += 1
            }
            i += Character.charCount(codePoint)
        }
        return out.toString()
    }

    private fun isDisplayable(codePoint: Int): Boolean {
        val type = Character.getType(codePoint)
        return type != Character.UNASSIGNED.toInt() &&
            type != Character.CONTROL.toInt() &&
            type != Character.FORMAT.toInt() &&
            type != Character.PRIVATE_USE.toInt() &&
            type != Character.SURROGATE.toInt()
    }

    /**
     * Backend `Decimal(amount)` range check: malformed, negative or above 100000000 is unacceptable.
     *
     * Deliberate tightening (documented divergence, fails safe to [UpiFlag.MALFORMED_AMOUNT]):
     * Python's `Decimal` also accepts `_` digit grouping (`1_000`), non-ASCII digits (`१५`),
     * `NaN`/`Inf`, and a trailing newline. This accepts only ASCII decimal syntax, so those inputs are
     * flagged instead of trusted.
     */
    private fun amountIsAcceptable(value: String): Boolean {
        val text = value.trim()
        if (!DECIMAL_PATTERN.matches(text)) return false
        return try {
            val parsed = BigDecimal(text)
            parsed.signum() >= 0 && parsed <= MAX_AMOUNT
        } catch (_: NumberFormatException) {
            false
        }
    }
}
