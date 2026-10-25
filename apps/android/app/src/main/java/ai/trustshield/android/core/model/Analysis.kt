package ai.trustshield.android.core.model

/**
 * Version of the local Android analysis contract.
 *
 * Bump the minor part for additive fields and the major part for a breaking shape change. Fixtures
 * in `app/src/test/.../data/api` pin this string so schema drift fails loudly.
 */
const val CONTRACT_VERSION: String = "android-contract/1.0"

/** Where the analysed content came from. Call audio is transcript text by the time it gets here. */
enum class InputType(val wire: String) {
    TEXT("text"),
    URL("url"),
    IMAGE("image"),
    QR("qr"),
    CALL("call");

    companion object {
        fun fromWire(value: String?): InputType? {
            val v = value?.trim() ?: return null
            return entries.firstOrNull { it.wire.equals(v, ignoreCase = true) }
        }
    }
}

/** The five-stage scam playbook (report §3.1). Ids are stable; labels are Hinglish. */
enum class StageId(val titleHinglish: String) {
    AUTHORITY("Pehchaan (authority)"),
    THREAT_OR_REWARD("Darr ya laalach"),
    URGENCY("Jaldbaazi"),
    SECRECY("Akela karna"),
    ASK("Maang");

    companion object {
        /** Playbook order, used for display and for counting independent stages. */
        val playbookOrder: List<StageId> = listOf(AUTHORITY, THREAT_OR_REWARD, URGENCY, SECRECY, ASK)
    }
}

/**
 * Risk label. Wire values match the backend `risk.label` so adapters map without translation.
 *
 * [UNABLE_TO_VERIFY] is a first-class outcome: unreadable or unsupported input never becomes
 * "safe".
 */
enum class RiskLabel(val wire: String) {
    LOW_RISK("low_risk"),
    CAUTION("caution"),
    SUSPICIOUS("suspicious"),
    HIGH_RISK("high_risk"),
    UNABLE_TO_VERIFY("unable_to_verify");

    companion object {
        fun fromWire(value: String?): RiskLabel? {
            val v = value?.trim() ?: return null
            return entries.firstOrNull { it.wire.equals(v, ignoreCase = true) }
        }
    }
}

/** Output confidence, deliberately independent of [RiskAssessment.score]. */
enum class Confidence(val wire: String) {
    LOW("low"),
    MEDIUM("medium"),
    HIGH("high");

    companion object {
        fun fromWire(value: String?): Confidence? {
            val v = value?.trim() ?: return null
            return entries.firstOrNull { it.wire.equals(v, ignoreCase = true) }
        }
    }
}

/** Which engine produced a value. Local and backend results stay separately attributable. */
enum class DataOrigin { LOCAL, BACKEND }

/** Backend enrichment state. Disabled by default until the privacy gate (A07) passes. */
enum class BackendStatus { DISABLED, AVAILABLE, UNAVAILABLE }

/** Overall analysis status. */
enum class AnalysisStatus { COMPLETED, PARTIAL, FAILED, UNABLE_TO_VERIFY }

/**
 * Risk score and confidence, kept as two fields on purpose.
 *
 * A high score with low confidence is legal and must stay legal: fusion lowers confidence when a
 * module is missing or recognition quality is poor, without pretending the risk signal vanished.
 */
data class RiskAssessment(
    val score: Int,
    val label: RiskLabel,
    val confidence: Confidence,
) {
    init {
        require(score in 0..100) { "risk score must be within 0..100, was $score" }
    }

    companion object {
        /** Deterministic banding shared by local and backend paths (backend `RISK_BANDS`). */
        fun labelFor(score: Int): RiskLabel = when {
            score >= 70 -> RiskLabel.HIGH_RISK
            score >= 50 -> RiskLabel.SUSPICIOUS
            score >= 25 -> RiskLabel.CAUTION
            else -> RiskLabel.LOW_RISK
        }

        /** An explicit "we could not judge this" assessment. Never rendered as safe. */
        fun unableToVerify(confidence: Confidence = Confidence.LOW): RiskAssessment =
            RiskAssessment(score = 0, label = RiskLabel.UNABLE_TO_VERIFY, confidence = confidence)
    }
}

/**
 * The single result shape the UI renders.
 *
 * Invariants (tested in `ContractTest`):
 *  * [RiskAssessment.score] and [RiskAssessment.confidence] are independent fields;
 *  * an [RiskLabel.UNABLE_TO_VERIFY] result carries at least one [limitations] entry explaining
 *    what failed;
 *  * every [Evidence] quote, when present, is an exact substring of the analysed input;
 *  * nothing here can assert absolute safety - wording lives in the UI copy rules, and a
 *    [RiskLabel.LOW_RISK] result still carries [limitations] when coverage is partial.
 */
data class AnalysisResult(
    val analysisId: String,
    val inputType: InputType,
    val status: AnalysisStatus,
    val risk: RiskAssessment,
    val evidence: List<Evidence> = emptyList(),
    val stages: List<StageId> = emptyList(),
    val limitations: List<String> = emptyList(),
    val actions: List<String> = emptyList(),
    val origin: DataOrigin = DataOrigin.LOCAL,
    val backend: BackendStatus = BackendStatus.DISABLED,
    val contractVersion: String = CONTRACT_VERSION,
) {
    init {
        require(analysisId.isNotBlank()) { "analysisId must not be blank" }
        require(risk.label != RiskLabel.UNABLE_TO_VERIFY || limitations.isNotEmpty()) {
            "unable_to_verify requires at least one limitation explaining what failed"
        }
        require(risk.label != RiskLabel.UNABLE_TO_VERIFY || status == AnalysisStatus.UNABLE_TO_VERIFY ||
            status == AnalysisStatus.PARTIAL || status == AnalysisStatus.FAILED) {
            "unable_to_verify cannot be reported with status $status"
        }
    }

    /** Evidence whose quote still verifies against the analysed input. */
    fun verifiableEvidence(input: String): List<Evidence> =
        evidence.filter { it.hasVerifiableQuote(input) }

    /** Evidence that claims a quote the input does not contain. Must be empty in a shipped result. */
    fun unverifiableEvidence(input: String): List<Evidence> =
        evidence.filterNot { it.hasVerifiableQuote(input) }

    private fun Evidence.hasVerifiableQuote(input: String): Boolean =
        if (quote == null) span == null else quoteVerifiesAgainst(input)
}

/**
 * Text with a character-level map back into the raw input.
 *
 * Implementations (A02 `HinglishNormalizer`) may substitute, drop zero-width characters or collapse
 * obfuscations, but every normalized index must resolve to a raw span so evidence quotes stay
 * verifiable against the text the user actually saw. This type is part of the contract so rule
 * packages can be written against it in parallel; the normalizer *implementation* is not.
 */
class NormalizedText private constructor(
    val raw: String,
    val text: String,
    private val starts: IntArray,
    private val ends: IntArray,
) {
    val length: Int get() = text.length

    init {
        require(starts.size == text.length) { "start map must be as long as the normalized text" }
        require(ends.size == text.length) { "end map must be as long as the normalized text" }
    }

    /** Raw start offset of normalized index [index], clamped to `[0, raw.length]`. */
    fun rawStart(index: Int): Int = when {
        index <= 0 -> 0
        index >= text.length -> raw.length
        else -> starts[index].coerceIn(0, raw.length)
    }

    /** Raw end offset of normalized index [index], clamped to `[0, raw.length]`. */
    fun rawEnd(index: Int): Int = when {
        index <= 0 -> 0
        index >= text.length -> raw.length
        else -> ends[index].coerceIn(0, raw.length)
    }

    /** Map a normalized half-open range back to a raw span. */
    fun spanOf(normalizedStart: Int, normalizedEnd: Int): TextSpan {
        val start = rawStart(normalizedStart.coerceIn(0, text.length))
        val end = rawEnd((normalizedEnd - 1).coerceIn(0, maxOf(text.length - 1, 0)))
        return if (end >= start) TextSpan(start, end) else TextSpan(start, text.length.coerceAtMost(raw.length))
    }

    /** Raw text for a normalized range, taken from the raw input (never from substituted text). */
    fun rawQuoteOf(normalizedStart: Int, normalizedEnd: Int): String {
        val span = spanOf(normalizedStart, normalizedEnd)
        return raw.substring(span.start.coerceAtMost(raw.length), span.end.coerceAtMost(raw.length))
    }

    /** Substring of the normalized text, for rule matching only. */
    fun normalizedSubstring(normalizedStart: Int, normalizedEnd: Int): String =
        if (normalizedStart < 0 || normalizedEnd > text.length || normalizedEnd < normalizedStart) {
            ""
        } else {
            text.substring(normalizedStart, normalizedEnd)
        }

    companion object {
        /** Identity mapping: normalized == raw, index for index. */
        fun identity(raw: String): NormalizedText {
            val starts = IntArray(raw.length) { it }
            val ends = IntArray(raw.length) { it + 1 }
            return NormalizedText(raw, raw, starts, ends)
        }

        /**
         * Build from explicit maps. [starts] and [ends] must each be as long as [text] and must be
         * monotonically usable; this is the constructor normalizers use.
         */
        fun of(raw: String, text: String, starts: IntArray, ends: IntArray): NormalizedText =
            NormalizedText(raw, text, starts, ends)
    }
}

/** Produces [NormalizedText] from raw user input. Implemented by A02; faked in other packages' tests. */
interface TextNormalizer {
    fun normalize(raw: String): NormalizedText
}
