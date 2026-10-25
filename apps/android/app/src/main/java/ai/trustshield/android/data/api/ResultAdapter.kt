package ai.trustshield.android.data.api

import ai.trustshield.android.core.model.AnalysisResult
import ai.trustshield.android.core.model.AnalysisStatus
import ai.trustshield.android.core.model.BackendStatus
import ai.trustshield.android.core.model.CONTRACT_VERSION
import ai.trustshield.android.core.model.Confidence
import ai.trustshield.android.core.model.DataOrigin
import ai.trustshield.android.core.model.Evidence
import ai.trustshield.android.core.model.InputType
import ai.trustshield.android.core.model.RiskAssessment
import ai.trustshield.android.core.model.RiskLabel
import ai.trustshield.android.core.model.Severity
import ai.trustshield.android.core.model.Strength

/**
 * Thrown by [ResultAdapter.fromWire] when a payload cannot be adapted without inventing data.
 *
 * Prefer [ResultAdapter.fromWireSafely]: it turns the same condition into
 * [ResultAdapter.AdaptOutcome.Drift] so callers never need a try/catch.
 */
class SchemaDriftException(message: String) : Exception(message)

/**
 * Describes a local/backend verdict difference **without** resolving it.
 *
 * F09/ARCHITECTURE: never silently replace a local warning with a lower backend score. This object
 * carries both numbers and both labels so the UI can show "on-device said X, backend said Y".
 */
data class Disagreement(
    val backendScore: Int,
    val localScore: Int,
    val backendLabel: RiskLabel,
    val localLabel: RiskLabel,
    val message: String,
)

/**
 * Maps the backend wire schema to the A01 Android contract. This is the honesty boundary of the
 * package - everything questionable is dropped, lowered, or reported, never guessed.
 *
 * ## What the backend actually sends
 *
 * `services/analysis/fusion/result.py`:
 * ```
 * {schema_version, analysis_id, created_at, input_type, status, risk{score,label,confidence},
 *  classification{primary,secondary}, evidence[], modules{}, explanation,
 *  recommended_actions[], limitations[]}
 * ```
 * `services/analysis/evidence/models.py` evidence:
 * ```
 * {id, source, category, severity, confidence, title, description, metadata, timestamp}
 * ```
 *
 * ## Consequences, deliberately encoded here
 *
 *  * **No strength on the wire.** Backend evidence has no `strength`, so per-item precision cannot be
 *    reproduced. The derivation is a conservative function of severity only:
 *    `medium|high|critical -> MODERATE`, `informational|low -> WEAK`. It never says STRONG, because
 *    nothing in the payload supports that claim. Documented in the result's `limitations`.
 *  * **No quote on the wire.** `quote` and `span` are therefore always null for backend evidence: a
 *    quote must be an exact substring of the analysed input, and the backend never supplies one, so
 *    inventing one would break the contract's verifiability rule (N03).
 *  * **Titles are English.** `title` -> `titleHinglish` keeps the text byte-for-byte with no prefix
 *    and no translation. It is honest-but-not-localised: the UI must label backend items as such
 *    rather than implying a Hinglish title exists.
 *  * **`description` -> `detail`**; `metadata`, `confidence`, `category` and `timestamp` are *not*
 *    copied into the UI. Metadata is backend-internal (and may contain URLs or raw fragments).
 *  * **`modules` is ignored entirely**, which is what keeps OCR `raw_text` / `normalized_text` from
 *    reaching the UI (N01: no raw image text persistence or re-display).
 *  * **`id` is not carried over.** Contract ids must be deterministic (`Evidence.deterministicId`);
 *    backend ids are random hex, so `Evidence.unquoted(...)` rebuilds them.
 *  * **URLs are dropped.** Nothing in `AnalysisResult` can hold a URL, and this adapter copies no url
 *    field, so a payload URL cannot become a tappable link on device.
 */
object ResultAdapter {

    /** The backend schema this adapter was written against (`SCHEMA_VERSION` in result.py). */
    const val BACKEND_SCHEMA_VERSION: String = "1.0"

    /** Result of an adaptation attempt. */
    sealed class AdaptOutcome {
        data class Ok(val result: AnalysisResult) : AdaptOutcome()

        /** The payload could not be adapted; [reason] is a short, log-safe description. */
        data class Drift(val reason: String) : AdaptOutcome()
    }

    /**
     * Adapts [wire] or throws [SchemaDriftException].
     *
     * Kept because the package contract names this signature; [AnalysisApi] uses
     * [fromWireSafely] and turns drift into [BackendOutcome.Unavailable] with reason
     * `SCHEMA_DRIFT`, so the throw never reaches the UI.
     *
     * @param expectedInputType used only when the payload omits `input_type`, or names a type the
     *   local contract does not have (the backend still emits `document`). The caller knows which
     *   endpoint it called, so this is a fact, not a guess.
     */
    fun fromWire(wire: Map<String, Any?>, expectedInputType: InputType? = null): AnalysisResult =
        when (val outcome = fromWireSafely(wire, expectedInputType)) {
            is AdaptOutcome.Ok -> outcome.result
            is AdaptOutcome.Drift -> throw SchemaDriftException(outcome.reason)
        }

    /** Adapts [wire], or returns [AdaptOutcome.Drift]. Never throws for bad input. */
    fun fromWireSafely(
        wire: Map<String, Any?>,
        expectedInputType: InputType? = null,
    ): AdaptOutcome {
        val analysisId = (wire["analysis_id"] as? String)?.trim().orEmpty()
        if (analysisId.isEmpty()) {
            // Without an id the result cannot be attributed to a scan; refuse rather than invent one.
            return AdaptOutcome.Drift("missing analysis_id")
        }

        val riskMap = wire["risk"] as? Map<*, *> ?: return AdaptOutcome.Drift("missing risk")
        val score = scoreOf(riskMap["score"])
            ?: return AdaptOutcome.Drift("risk.score is missing or not a number")
        if (score !in 0..100) return AdaptOutcome.Drift("risk.score out of 0..100")

        val label = RiskLabel.fromWire(riskMap["label"] as? String)
            ?: return AdaptOutcome.Drift("unknown or missing risk.label")

        val evidenceRaw = wire["evidence"] ?: return AdaptOutcome.Drift("missing evidence")
        val evidenceList = evidenceRaw as? List<*> ?: return AdaptOutcome.Drift("evidence is not a list")

        val inputType = InputType.fromWire(wire["input_type"] as? String) ?: expectedInputType
            ?: return AdaptOutcome.Drift("unknown or missing input_type")

        val limitations = mutableListOf<String>()

        val wireConfidence = Confidence.fromWire(riskMap["confidence"] as? String)
        val confidence = wireConfidence ?: Confidence.LOW
        if (wireConfidence == null) {
            // Never upgrade: an unreadable confidence is reported as the lowest one.
            limitations += "Backend did not report a usable confidence; shown as low."
        }

        val schemaVersion = (wire["schema_version"] as? String)?.trim()
        if (!schemaVersion.isNullOrEmpty() && schemaVersion != BACKEND_SCHEMA_VERSION) {
            // Additive versions still adapt, but the difference is visible instead of silent.
            limitations += "Backend schema_version $schemaVersion differs from the pinned " +
                "$BACKEND_SCHEMA_VERSION; unknown fields were ignored."
        }

        val backendLimitations = strings(wire["limitations"])
        limitations += backendLimitations

        val evidence = mutableListOf<Evidence>()
        var dropped = 0
        for ((index, item) in evidenceList.withIndex()) {
            val adapted = adaptEvidence(item, index)
            if (adapted == null) dropped++ else evidence += adapted
        }
        if (dropped > 0) {
            limitations += "$dropped backend evidence item(s) were dropped: unknown severity, " +
                "blank title or blank source."
        }
        if (evidence.isNotEmpty()) {
            limitations += "Backend evidence carries no verbatim quote and no strength: quotes are " +
                "omitted and strength is derived from severity only."
        }

        val status = statusOf(wire["status"] as? String, label)
        if (label == RiskLabel.UNABLE_TO_VERIFY && limitations.none { it.isNotBlank() }) {
            // Contract invariant: an unable_to_verify result must say why. If the backend gave no
            // reason, state the fact instead of leaving an unexplained verdict.
            limitations += "Backend reported unable_to_verify without a stated reason."
        }

        return AdaptOutcome.Ok(
            AnalysisResult(
                analysisId = analysisId,
                inputType = inputType,
                status = status,
                risk = RiskAssessment(score = score, label = label, confidence = confidence),
                evidence = evidence,
                // The backend has no notion of the local five-stage playbook; nothing to map.
                stages = emptyList(),
                limitations = limitations,
                actions = strings(wire["recommended_actions"]),
                // Attribution stays explicit: this result came from the server, not from rules.
                origin = DataOrigin.BACKEND,
                backend = BackendStatus.AVAILABLE,
                // The *local* contract version, not the backend's: this object is an A01 instance.
                contractVersion = CONTRACT_VERSION,
            ),
        )
    }

    /**
     * Null means "drop this item". Dropping is the only honest option when a required field is
     * unusable: a severity we cannot read must not become MEDIUM, and a blank title would violate the
     * contract.
     *
     * [index] only feeds the deterministic id. Every backend item from one module is otherwise
     * unquoted and would collide on `module@none`, so the payload position is folded into the id:
     * stable for the same payload, unique inside one result.
     */
    private fun adaptEvidence(item: Any?, index: Int): Evidence? {
        val map = item as? Map<*, *> ?: return null

        // Severity gates the item: an unknown severity is a drop, not a default (N03).
        val severity = Severity.fromWire(map["severity"] as? String) ?: return null

        val module = (map["source"] as? String)?.trim().orEmpty()
        if (module.isEmpty()) return null

        val title = (map["title"] as? String)?.trim().orEmpty()
        if (title.isEmpty()) return null

        val detail = (map["description"] as? String)?.trim().orEmpty()
        val category = (map["category"] as? String)?.trim()?.lowercase().orEmpty().ifEmpty { "uncategorised" }

        return Evidence(
            // Deterministic, not the backend's random hex id: the contract requires reproducible ids.
            id = "$module@backend:$category:$index",
            module = module,
            // English backend title kept verbatim: no prefix, no translation, no invented Hinglish.
            titleHinglish = title,
            detail = detail,
            severity = severity,
            // Conservative derivation, not a measurement. See class docs.
            strength = strengthFor(severity),
            // The backend never sends a quote, so none is claimed (a quote must be an exact substring).
            quote = null,
            span = null,
        )
    }

    /**
     * `medium+ -> MODERATE`, otherwise `WEAK`.
     *
     * This is a documented floor, not per-item precision: the payload has no strength field, so
     * anything claiming STRONG (or distinguishing medium from critical) would be fabricated.
     */
    private fun strengthFor(severity: Severity): Strength = when (severity) {
        Severity.MEDIUM, Severity.HIGH, Severity.CRITICAL -> Strength.MODERATE
        Severity.LOW, Severity.INFORMATIONAL -> Strength.WEAK
    }

    /**
     * Backend `status` -> local status. Unknown/absent becomes PARTIAL: we know the backend answered
     * but not that it finished. An `unable_to_verify` label can never be reported as COMPLETED.
     */
    private fun statusOf(raw: String?, label: RiskLabel): AnalysisStatus {
        val base = when (raw?.trim()?.lowercase()) {
            "completed" -> AnalysisStatus.COMPLETED
            "partial" -> AnalysisStatus.PARTIAL
            "failed" -> AnalysisStatus.FAILED
            "queued", "sandboxing" -> AnalysisStatus.PARTIAL
            else -> AnalysisStatus.PARTIAL
        }
        return if (label == RiskLabel.UNABLE_TO_VERIFY && base == AnalysisStatus.COMPLETED) {
            AnalysisStatus.UNABLE_TO_VERIFY
        } else {
            base
        }
    }

    /** Integral number only. `"80"`, `80.5` and booleans are not scores. */
    private fun scoreOf(value: Any?): Int? = when (value) {
        is Int -> value
        is Long -> if (value in Int.MIN_VALUE..Int.MAX_VALUE) value.toInt() else null
        is Double -> if (!value.isNaN() && !value.isInfinite() && value % 1.0 == 0.0 &&
            value >= Int.MIN_VALUE && value <= Int.MAX_VALUE
        ) {
            value.toInt()
        } else {
            null
        }
        else -> null
    }

    private fun strings(value: Any?): List<String> =
        (value as? List<*>)
            ?.mapNotNull { (it as? String)?.trim() }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()

    /**
     * Local vs backend verdict difference, or null when the labels agree (scores may still differ,
     * which is not a disagreement about what the user should be told).
     *
     * The message always names both scores and both labels and never claims one engine won: this
     * method describes, it does not merge. A local HIGH_RISK is *not* replaced by a backend LOW_RISK.
     */
    fun describeDisagreement(local: AnalysisResult, backend: AnalysisResult): Disagreement? {
        if (local.risk.label == backend.risk.label) return null

        val message = when {
            local.risk.label == RiskLabel.UNABLE_TO_VERIFY ->
                "On-device rules could not verify this (${local.risk.score}/100), backend says " +
                    "${backend.risk.label.wire} (${backend.risk.score}/100). That is a difference in " +
                    "coverage, not a downgrade, and neither result was replaced."

            backend.risk.label == RiskLabel.UNABLE_TO_VERIFY ->
                "Backend could not verify this (${backend.risk.score}/100). On-device result stays " +
                    "${local.risk.label.wire} (${local.risk.score}/100) and remains the shown verdict."

            else ->
                "Sources disagree: backend ${backend.risk.score}/100 ${backend.risk.label.wire}, " +
                    "on-device ${local.risk.score}/100 ${local.risk.label.wire}. The on-device result " +
                    "is shown and was not replaced."
        }

        return Disagreement(
            backendScore = backend.risk.score,
            localScore = local.risk.score,
            backendLabel = backend.risk.label,
            localLabel = local.risk.label,
            message = message,
        )
    }
}

/**
 * Minimal, dependency-free JSON reader for backend payloads.
 *
 * Why hand-written: this module must not drag `kotlinx.serialization`, `org.json` or any `android.*`
 * class into the JVM unit-test classpath, and a reflective parser is a larger attack surface than a
 * 120-line recursive-descent one. The parser is intentionally strict about structure and lenient
 * about nothing that could hide a schema change.
 *
 * Guarantees:
 *  * **Bounded nesting**: deeper than [MAX_DEPTH] (32) returns null instead of overflowing the stack
 *    on a hostile payload.
 *  * Handles escaped quotes/backslashes/`\uXXXX` (including surrogate pairs), integers, decimals,
 *    exponents, `true`/`false`/`null`, arrays and objects.
 *  * Malformed input returns null - never a partial value, never an exception.
 *  * Object keys that are not strings cannot occur (the grammar requires strings); later duplicate
 *    keys win, matching common JSON parser behaviour.
 *  * A document that is exactly `null` also yields null; callers treat null as "nothing to adapt".
 */
object MiniJson {

    /** Maximum container nesting. A harder limit than any legitimate backend payload needs. */
    const val MAX_DEPTH: Int = 32

    /** Parses [text], or returns null when it is not well-formed JSON within [MAX_DEPTH]. */
    fun parse(text: String): Any? = try {
        val reader = Reader(text)
        val value = reader.readValue(depth = 0)
        reader.skipWhitespace()
        if (reader.position != text.length) null else value
    } catch (_: ParseFailure) {
        null
    }

    /** Quotes [value] as a JSON string literal. Used for request bodies and by tests. */
    fun encodeString(value: String): String {
        val out = StringBuilder(value.length + 2)
        out.append('"')
        for (c in value) {
            when (c) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                '\b' -> out.append("\\b")
                '\u000C' -> out.append("\\f")
                else -> if (c < ' ') {
                    out.append("\\u").append(c.code.toString(16).padStart(4, '0'))
                } else {
                    out.append(c)
                }
            }
        }
        out.append('"')
        return out.toString()
    }

    private class ParseFailure : RuntimeException() {
        // No stack trace: a malformed payload is an expected event, not a bug.
        override fun fillInStackTrace(): Throwable = this
    }

    private class Reader(private val text: String) {
        var position: Int = 0

        private fun fail(): Nothing = throw ParseFailure()

        fun skipWhitespace() {
            while (position < text.length) {
                when (text[position]) {
                    ' ', '\t', '\n', '\r' -> position++
                    else -> return
                }
            }
        }

        fun readValue(depth: Int): Any? {
            if (depth > MiniJson.MAX_DEPTH) fail()
            skipWhitespace()
            if (position >= text.length) fail()
            return when (val c = text[position]) {
                '{' -> readObject(depth)
                '[' -> readArray(depth)
                '"' -> readString()
                't' -> readLiteral("true", true)
                'f' -> readLiteral("false", false)
                'n' -> readLiteral("null", null)
                else -> if (c == '-' || c.isDigit()) readNumber() else fail()
            }
        }

        private fun readLiteral(word: String, value: Any?): Any? {
            if (!text.startsWith(word, position)) fail()
            position += word.length
            return value
        }

        private fun readObject(depth: Int): Map<String, Any?> {
            position++ // '{'
            val map = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (position < text.length && text[position] == '}') {
                position++
                return map
            }
            while (true) {
                skipWhitespace()
                if (position >= text.length || text[position] != '"') fail()
                val key = readString()
                skipWhitespace()
                if (position >= text.length || text[position] != ':') fail()
                position++
                map[key] = readValue(depth + 1)
                skipWhitespace()
                if (position >= text.length) fail()
                when (text[position]) {
                    ',' -> position++
                    '}' -> {
                        position++
                        return map
                    }
                    else -> fail()
                }
            }
        }

        private fun readArray(depth: Int): List<Any?> {
            position++ // '['
            val list = ArrayList<Any?>()
            skipWhitespace()
            if (position < text.length && text[position] == ']') {
                position++
                return list
            }
            while (true) {
                list.add(readValue(depth + 1))
                skipWhitespace()
                if (position >= text.length) fail()
                when (text[position]) {
                    ',' -> position++
                    ']' -> {
                        position++
                        return list
                    }
                    else -> fail()
                }
            }
        }

        private fun readString(): String {
            position++ // opening quote
            val out = StringBuilder()
            while (true) {
                if (position >= text.length) fail()
                when (val c = text[position]) {
                    '"' -> {
                        position++
                        return out.toString()
                    }
                    '\\' -> {
                        position++
                        if (position >= text.length) fail()
                        when (val esc = text[position]) {
                            '"' -> {
                                out.append('"'); position++
                            }
                            '\\' -> {
                                out.append('\\'); position++
                            }
                            '/' -> {
                                out.append('/'); position++
                            }
                            'b' -> {
                                out.append('\b'); position++
                            }
                            'f' -> {
                                out.append('\u000C'); position++
                            }
                            'n' -> {
                                out.append('\n'); position++
                            }
                            'r' -> {
                                out.append('\r'); position++
                            }
                            't' -> {
                                out.append('\t'); position++
                            }
                            'u' -> {
                                if (position + 4 >= text.length) fail()
                                val hex = text.substring(position + 1, position + 5)
                                val code = hex.toIntOrNull(16) ?: fail()
                                // Surrogate halves are appended individually; the JVM strings they
                                // form together are correct UTF-16, same as any other parser.
                                out.append(code.toChar())
                                position += 5
                            }
                            else -> fail()
                        }
                    }
                    else -> {
                        out.append(c)
                        position++
                    }
                }
            }
        }

        private fun readNumber(): Any {
            val start = position
            if (position < text.length && text[position] == '-') position++
            var digits = 0
            while (position < text.length && text[position].isDigit()) {
                position++
                digits++
            }
            if (digits == 0) fail()
            var integral = true
            if (position < text.length && text[position] == '.') {
                integral = false
                position++
                var fraction = 0
                while (position < text.length && text[position].isDigit()) {
                    position++
                    fraction++
                }
                if (fraction == 0) fail()
            }
            if (position < text.length && (text[position] == 'e' || text[position] == 'E')) {
                integral = false
                position++
                if (position < text.length && (text[position] == '+' || text[position] == '-')) position++
                var exponent = 0
                while (position < text.length && text[position].isDigit()) {
                    position++
                    exponent++
                }
                if (exponent == 0) fail()
            }
            val raw = text.substring(start, position)
            if (integral) {
                raw.toLongOrNull()?.let { return it }
            }
            return raw.toDoubleOrNull() ?: fail()
        }
    }
}
