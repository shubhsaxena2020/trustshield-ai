package ai.trustshield.android.core.model

/**
 * Severity of a single evidence item.
 *
 * Wire values mirror the backend `Evidence.severity` enum exactly so an adapter can map without
 * inventing a class. Severity is *not* a probability and is *not* the risk score.
 */
enum class Severity(val wire: String) {
    INFORMATIONAL("informational"),
    LOW("low"),
    MEDIUM("medium"),
    HIGH("high"),
    CRITICAL("critical");

    companion object {
        /** Parse a backend/wire value. Unknown or blank input yields null instead of a default. */
        fun fromWire(value: String?): Severity? {
            val v = value?.trim() ?: return null
            if (v.isEmpty()) return null
            return entries.firstOrNull { it.wire.equals(v, ignoreCase = true) }
        }
    }
}

/**
 * Evidence strength classes (report §3.3). Deliberately separate from [Severity]: strength is how
 * diagnostic the signal is, severity is how much it hurts if the reading is right.
 */
enum class Strength(val wire: String) {
    WEAK("weak"),
    MODERATE("moderate"),
    STRONG("strong");

    companion object {
        fun fromWire(value: String?): Strength? {
            val v = value?.trim() ?: return null
            if (v.isEmpty()) return null
            return entries.firstOrNull { it.wire.equals(v, ignoreCase = true) }
        }
    }
}

/**
 * Half-open character range `[start, end)` in the **original, pre-normalization** input.
 *
 * Every evidence quote must be reproducible from the original input by
 * `input.substring(span.start, span.end)`. Normalizer substitutions may move text, so they must
 * never be used to invent a quote.
 */
data class TextSpan(val start: Int, val end: Int) {
    init {
        require(start >= 0) { "span start must be >= 0, was $start" }
        require(end >= start) { "span end ($end) must be >= start ($start)" }
    }

    val length: Int get() = end - start

    /** True when this span covers a real non-empty range of the input. */
    val isNotEmpty: Boolean get() = end > start

    /** Safe substring read; returns null when the span does not fit the supplied text. */
    fun slice(text: String): String? =
        if (start <= end && end <= text.length) text.substring(start, end) else null
}

/**
 * One piece of evidence: `{id, module, titleHinglish, detail, severity, strength, quote}`.
 *
 * Contract rules enforced here:
 *  * a quote is either fully verifiable against the original input **with** its span, or absent
 *    (`quote == null && span == null`); the two can never disagree;
 *  * ids are deterministic for the same module and span, so fixtures compare byte-for-byte;
 *  * nothing here is a verdict — fusion (A05) decides the score.
 */
data class Evidence(
    val id: String,
    val module: String,
    val titleHinglish: String,
    val detail: String,
    val severity: Severity,
    val strength: Strength,
    val quote: String? = null,
    val span: TextSpan? = null,
) {
    init {
        require(module.isNotBlank()) { "evidence module must not be blank" }
        require(titleHinglish.isNotBlank()) { "evidence titleHinglish must not be blank" }
        require((quote == null) == (span == null)) {
            "quote and span must be present or absent together (quote=${quote?.let { "present" }}, span=$span)"
        }
        require(quote == null || quote.isNotEmpty()) { "an empty quote must be represented as null" }
    }

    /** Re-check the quote against the input that produced it. */
    fun quoteVerifiesAgainst(input: String): Boolean {
        val s = span ?: return quote == null
        val q = quote ?: return false
        return s.slice(input) == q
    }

    companion object {
        /**
         * Build evidence whose quote is verified against [input] before it is kept.
         *
         * A quote that is not an exact substring is **dropped** (quote and span both null) rather
         * than repaired or fuzzed: an unverifiable quote must never reach the UI.
         */
        fun verified(
            input: String,
            module: String,
            titleHinglish: String,
            detail: String,
            severity: Severity,
            strength: Strength,
            quote: String?,
        ): Evidence {
            val span = locate(input, quote)
            val kept = if (span == null) null else quote
            return Evidence(
                id = deterministicId(module, span),
                module = module,
                titleHinglish = titleHinglish,
                detail = detail,
                severity = severity,
                strength = strength,
                quote = kept,
                span = span,
            )
        }

        /**
         * Evidence with no quote at all (module-level statements such as "backend unavailable").
         * Use [verified] whenever a quote is claimed.
         */
        fun unquoted(
            module: String,
            titleHinglish: String,
            detail: String,
            severity: Severity,
            strength: Strength,
        ): Evidence = Evidence(
            id = deterministicId(module, null),
            module = module,
            titleHinglish = titleHinglish,
            detail = detail,
            severity = severity,
            strength = strength,
            quote = null,
            span = null,
        )

        /** Locate [quote] inside [input]; null when absent, blank, or not an exact substring. */
        fun locate(input: String, quote: String?): TextSpan? {
            if (quote.isNullOrEmpty()) return null
            val at = input.indexOf(quote)
            return if (at < 0) null else TextSpan(at, at + quote.length)
        }

        /** Stable id: identical evidence for identical input compares equal across runs. */
        fun deterministicId(module: String, span: TextSpan?): String =
            if (span == null) "$module@none" else "$module@${span.start}-${span.end}"
    }
}
