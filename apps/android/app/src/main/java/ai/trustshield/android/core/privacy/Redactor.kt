package ai.trustshield.android.core.privacy

/**
 * Deterministic masking of the secrets that must never reach a log line, a crash report, an
 * analytics event or a stored history row (requirement N01).
 *
 * Design constraints, all deliberate:
 *  * **No dependencies and no `android.*` imports.** It is plain Kotlin so it runs in JVM unit
 *    tests, in the share/camera adapters and in any future foreground service without a Context.
 *  * **Deterministic.** Same input, same output, no randomness, no clock, no locale lookups that
 *    change per device.
 *  * **Never throws.** Every public entry point is fail-closed: if a pattern engine failure ever
 *    happened, the payload is replaced with [MASK] rather than emitted unmasked.
 *  * **Idempotent.** `redact(redact(x)) == redact(x)`. [MASK] contains no digits and no `@`, so a
 *    masked span can never be re-matched by a later pass.
 *  * **Bounded.** Every quantifier is bounded and each pass is a single linear scan of the input,
 *    so a 20 000-character OCR blob is masked in milliseconds and the output grows by a small
 *    constant per masked span.
 *
 * Honest limitation, same as the backend's `redact_secrets`: this is best-effort pattern masking,
 * not a proof of anonymisation. It does not recognise a secret written as words ("twelve
 * thirty-four"), a number split across a line break, or an identifier type nobody listed. Treat
 * redacted text as *low risk to log*, not as *safe to publish*.
 */
object Redactor {

    /** Placeholder that replaces every masked span. Contains no digits and no `@` (idempotency). */
    const val MASK = "[REDACTED]"

    // ---- key names -------------------------------------------------------------------------

    /** Segment names that make a JSON key sensitive. Matched per camelCase/snake segment. */
    private val SENSITIVE_SEGMENTS: Set<String> = setOf(
        "otp", "pins", "pin", "mpin", "upin", "cvv", "cvc", "csc", "password", "passwd",
        "passcode", "pwd", "secret", "secrets", "token", "tokens", "bearer", "credential",
        "credentials", "card", "cards", "cardno", "pan", "phone", "phones", "mobile", "msisdn",
        "contact", "email", "mail", "upi", "vpa", "account", "acct", "acc", "ifsc", "aadhaar",
        "aadhar", "uan", "dob", "telephone", "whatsapp",
    )

    /** Whole keys whose meaning only appears across segments (`api_key`, `card_number`, ...). */
    private val SENSITIVE_COMPOSITE_KEYS: Set<String> = setOf(
        "apikey", "accesstoken", "authtoken", "refreshtoken", "sessiontoken", "privatkey",
        "privatekey", "secretkey", "cardnumber", "cardno", "accountnumber", "accountno",
        "phonenumber", "mobilenumber", "upid", "upiid", "vpaid", "emailaddress",
    )

    private val CAMEL_BOUNDARY = Regex("(?<=[a-z0-9])(?=[A-Z])")
    private val NON_ALNUM = Regex("[^A-Za-z0-9]+")
    private val TRAILING_DIGITS = Regex("\\d+$")

    /**
     * True when a field named [key] carries a value that must be masked: OTP/PIN/CVV/password,
     * card, phone, UPI/email, account and similar identifiers.
     *
     * Matching is per key segment, not by substring, so `shipping` is not `pin` and `microphone`
     * is not `phone`. Unknown keys fail closed only when they cannot be parsed at all.
     */
    fun isSensitive(key: String): Boolean {
        return try {
            val trimmed = key.trim().trim('"').trim('\'').trim()
            if (trimmed.isEmpty()) return false
            val cased = CAMEL_BOUNDARY.replace(trimmed, " ")
            val lowered = cased.lowercase()
            if (SENSITIVE_COMPOSITE_KEYS.contains(NON_ALNUM.replace(lowered, ""))) return true
            NON_ALNUM.split(lowered).any { segment ->
                segment.isNotEmpty() && SENSITIVE_SEGMENTS.contains(TRAILING_DIGITS.replace(segment, ""))
            }
        } catch (_: Throwable) {
            true // an unparseable key name must not silently become "not sensitive"
        }
    }

    // ---- patterns --------------------------------------------------------------------------
    //
    // All patterns are raw strings so backslashes stay literal. `\p{Nd}` (any Unicode decimal
    // digit) is used where Devanagari digits ०-९ must be caught; `\d` (ASCII only) is used for
    // card/phone/account shapes, which are always ASCII.

    private const val LABELS =
        """otp|o\.t\.p\.?|one[\s-]*(?:time|t)[\s-]*(?:password|passcode|code|pin)|""" +
            """verification[\s-]*code|verify[\s-]*code|auth(?:entication)?[\s-]*code|""" +
            """security[\s-]*code|confirmation[\s-]*code|transaction[\s-]*pin|atm[\s-]*pin|""" +
            """upi[\s-]*pin|m[\s-]?pin|passcode|password|passwd|pwd|code|pin"""

    /** CVV is legitimately three digits, so it gets its own pass before the 4-8 digit label rule. */
    private val CVV_LABELLED = Regex(
        """(?i)(?<![A-Za-z])(?:cvv|cvc|csc|card[\s-]*verification[\s-]*(?:value|code))""" +
            """(?![A-Za-z])[^\p{Nd}\n]{0,12}?(\p{Nd}{3,4})(?!\p{Nd})""",
    )

    /** `OTP 482913`, `your pin is 1234`, `code: 5544` - value follows the keyword. */
    private val LABEL_THEN_DIGITS = Regex(
        """(?i)(?<![A-Za-z])(?:$LABELS)(?![A-Za-z])[^\p{Nd}\n]{0,15}?(\p{Nd}{4,8})(?!\p{Nd})""",
    )

    /** `482913 is the OTP` - value precedes the keyword. */
    private val DIGITS_THEN_LABEL = Regex(
        """(?i)(?<!\p{Nd})(\p{Nd}{4,8})(?!\p{Nd})[^\p{Nd}\n]{0,12}?""" +
            """(?<![A-Za-z])(?:otp|code|pin|cvv|cvc|password|verification)(?![A-Za-z])""",
    )

    /** `password: hunter2`, `passcode is tiger` - alphanumeric secrets after a keyword. */
    private val LABEL_THEN_TOKEN = Regex(
        """(?i)(?<![A-Za-z])(?:password|passcode|passwd|pwd|secret)(?![A-Za-z])""" +
            """\s*(?:is|was|:|=|-)?\s*(\S{3,})""",
    )

    /** `token=...`, `api key: ...` - labelled machine credentials. */
    private val LABEL_THEN_CREDENTIAL = Regex(
        """(?i)(?<![A-Za-z])(?:token|api[\s_-]?key|secret[\s_-]?key|bearer)(?![A-Za-z])""" +
            """\s*(?:is|:|=)?\s*([A-Za-z0-9._~+/=-]{8,})""",
    )

    /** 13-19 digits with optional single spaces/dashes: card-shaped, validated by Luhn below. */
    private val CARD_SHAPED = Regex("""(?<!\d)((?:\d[ -]?){12,18}\d)(?!\d)""")

    /** Indian mobile: 10 digits starting 6-9, optional +91 / 0 prefix, spaces or dashes between groups. */
    private val PHONE_SHAPED = Regex("""(?<!\d)(?:\+?91[\s-]?|0)?([6-9]\d{4}[\s-]?\d{5})(?!\d)""")

    private val EMAIL_SHAPED = Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}""")

    /** UPI VPA: localpart@handle, no TLD required (`name@okhdfcbank`). */
    private val UPI_SHAPED = Regex("""(?<![A-Za-z0-9._-])[A-Za-z0-9._-]{2,}@[A-Za-z]{2,}[A-Za-z0-9.-]*""")

    private val ACCOUNT_SHAPED = Regex(
        """(?i)(?<![A-Za-z])(?:a/c|acct|account|acc)(?![A-Za-z])\.?""" +
            """\s*(?:no\.?|number|num)?\s*[:#-]?\s*(\d[\d -]{4,20}\d)""",
    )

    /** Words that follow "password" in ordinary prose and are not a value. */
    private val STOPWORDS_AFTER_LABEL = setOf(
        "with", "or", "and", "to", "for", "anyone", "nobody", "never", "share", "shared", "it",
        "this", "that", "your", "my", "the", "a", "an", "is", "was", "if", "when", "would",
    )

    // ---- public API ------------------------------------------------------------------------

    /**
     * Mask every recognised secret in [text], leaving the surrounding prose intact.
     *
     * Empty input returns empty. Text with no recognised pattern is returned unchanged (no
     * over-masking of ordinary numbers such as `order 1234 of ₹500`).
     */
    fun redact(text: String): String {
        if (text.isEmpty()) return text
        return try {
            var out = text
            out = maskGroup(out, CVV_LABELLED, 1)
            out = maskGroup(out, LABEL_THEN_DIGITS, 1)
            out = maskGroup(out, DIGITS_THEN_LABEL, 1)
            out = maskLabelledToken(out)
            out = maskLabelledCredential(out)
            // Email/VPA before phone: a VPA whose localpart is a phone number must be masked whole,
            // not left as "[REDACTED]@ybl".
            out = EMAIL_SHAPED.replace(out, MASK)
            out = UPI_SHAPED.replace(out, MASK)
            out = maskCards(out)
            out = maskAccountNumbers(out)
            out = maskGroup(out, PHONE_SHAPED, 1)
            out
        } catch (_: Throwable) {
            // Fail closed: an unmasked diagnostic is worse than an unreadable one.
            MASK
        }
    }

    /**
     * Mask secrets in a JSON payload, both structurally (values under keys such as `otp`, `pin`,
     * `card`, `phone`, `upi`) and textually (free-form values such as an OCR `raw_text` blob).
     *
     * Output stays valid JSON for every input that was valid JSON: a sensitive key's value is
     * replaced with the quoted string `"[REDACTED]"`, other keys and values are preserved.
     */
    fun redactJson(json: String): String {
        if (json.isEmpty()) return json
        return try {
            val keyed = redactSensitiveJsonValues(json) ?: return MASK
            redact(keyed)
        } catch (_: Throwable) {
            MASK
        }
    }

    /** Replaces a sensitive JSON member's complete value, including any nested arrays or objects. */
    private fun redactSensitiveJsonValues(json: String): String? {
        val out = StringBuilder(json.length)
        var i = 0
        while (i < json.length) {
            if (json[i] != '"') {
                out.append(json[i++])
                continue
            }
            val keyEnd = jsonStringEnd(json, i) ?: return null
            val key = decodeJsonString(json, i, keyEnd) ?: return null
            var colon = keyEnd
            while (colon < json.length && json[colon].isWhitespace()) colon++
            if (colon < json.length && json[colon] == ':') {
                var valueStart = colon + 1
                while (valueStart < json.length && json[valueStart].isWhitespace()) valueStart++
                if (isSensitive(key)) {
                    val valueEnd = jsonValueEnd(json, valueStart) ?: return null
                    if (json.substring(valueStart, valueEnd) == "null") {
                        out.append(json, i, valueEnd)
                        i = valueEnd
                        continue
                    }
                    out.append(json, i, valueStart)
                    out.append('"').append(MASK).append('"')
                    i = valueEnd
                    continue
                }
            }
            out.append(json, i, keyEnd)
            i = keyEnd
        }
        return out.toString()
    }

    private fun jsonStringEnd(json: String, start: Int): Int? {
        var i = start + 1
        while (i < json.length) {
            when (json[i]) {
                '\\' -> i += 2
                '"' -> return i + 1
                else -> i++
            }
        }
        return null
    }

    private fun decodeJsonString(json: String, start: Int, end: Int): String? {
        val out = StringBuilder(end - start - 2)
        var i = start + 1
        while (i < end - 1) {
            val ch = json[i++]
            if (ch != '\\') {
                out.append(ch)
                continue
            }
            if (i >= end - 1) return null
            when (val escaped = json[i++]) {
                '"', '\\', '/' -> out.append(escaped)
                'b' -> out.append('\b')
                'f' -> out.append('\u000C')
                'n' -> out.append('\n')
                'r' -> out.append('\r')
                't' -> out.append('\t')
                'u' -> {
                    if (i + 4 > end - 1) return null
                    val code = json.substring(i, i + 4).toIntOrNull(16) ?: return null
                    out.append(code.toChar())
                    i += 4
                }
                else -> return null
            }
        }
        return out.toString()
    }

    /** Returns the exclusive end of one complete JSON value, or null when it is truncated. */
    private fun jsonValueEnd(json: String, start: Int): Int? {
        if (start >= json.length) return null
        if (json[start] == '"') return jsonStringEnd(json, start)
        if (json[start] == '{' || json[start] == '[') {
            val stack = ArrayDeque<Char>()
            stack.addLast(if (json[start] == '{') '}' else ']')
            var i = start + 1
            while (i < json.length) {
                when (json[i]) {
                    '"' -> i = jsonStringEnd(json, i) ?: return null
                    '{' -> { stack.addLast('}'); i++ }
                    '[' -> { stack.addLast(']'); i++ }
                    '}', ']' -> {
                        if (stack.removeLastOrNull() != json[i]) return null
                        i++
                        if (stack.isEmpty()) return i
                    }
                    else -> i++
                }
            }
            return null
        }
        var end = start
        while (end < json.length && json[end] != ',' && json[end] != '}' && json[end] != ']') end++
        while (end > start && json[end - 1].isWhitespace()) end--
        return end.takeIf { it > start }
    }

    // ---- passes ----------------------------------------------------------------------------

    /** Replace capture group [group] of every match with [MASK], leaving the rest of the match. */
    private fun maskGroup(text: String, pattern: Regex, group: Int): String =
        pattern.replace(text) { match ->
            val secret = match.groupValues.getOrNull(group).orEmpty()
            if (secret.isEmpty()) match.value else match.value.replace(secret, MASK)
        }

    private fun maskLabelledToken(text: String): String =
        LABEL_THEN_TOKEN.replace(text) { match ->
            val value = match.groupValues.getOrNull(1).orEmpty()
            val bare = value.trimEnd('.', ',', ';', ':', '!', '?')
            when {
                value.isEmpty() -> match.value
                bare.isEmpty() -> match.value
                STOPWORDS_AFTER_LABEL.contains(bare.lowercase()) -> match.value
                else -> match.value.replace(value, MASK)
            }
        }

    private fun maskLabelledCredential(text: String): String =
        LABEL_THEN_CREDENTIAL.replace(text) { match ->
            val value = match.groupValues.getOrNull(1).orEmpty()
            if (value.isEmpty()) match.value else match.value.replace(value, MASK)
        }

    private fun maskCards(text: String): String =
        CARD_SHAPED.replace(text) { match ->
            val raw = match.groupValues.getOrNull(1).orEmpty()
            val digits = raw.filter { it in '0'..'9' }
            val grouped = raw.length > digits.length
            // Luhn decides for ungrouped 13-14 digit runs (so a bare timestamp is left alone);
            // grouped runs and 15+ digit runs are masked regardless, because a separated 13-14
            // digit group is card-shaped and a >=15 digit run is never benign prose.
            val mask = digits.length in 13..19 &&
                (grouped || digits.length >= 15 || luhn(digits))
            if (mask) match.value.replace(raw, MASK) else match.value
        }

    private fun maskAccountNumbers(text: String): String =
        ACCOUNT_SHAPED.replace(text) { match ->
            val raw = match.groupValues.getOrNull(1).orEmpty()
            if (raw.isEmpty()) match.value else match.value.replace(raw, MASK)
        }

    /** Luhn mod-10 check over an ASCII digit string. */
    private fun luhn(digits: String): Boolean {
        if (digits.isEmpty()) return false
        var sum = 0
        var doubled = false
        for (index in digits.indices.reversed()) {
            val digit = digits[index] - '0'
            if (digit !in 0..9) return false
            var value = digit
            if (doubled) {
                value *= 2
                if (value > 9) value -= 9
            }
            sum += value
            doubled = !doubled
        }
        return sum % 10 == 0
    }
}
