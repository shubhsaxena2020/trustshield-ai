package ai.trustshield.android.ui.result

import ai.trustshield.android.ui.theme.RiskTone
import ai.trustshield.android.ui.theme.RiskToneCopy
import ai.trustshield.android.ui.theme.ToneCopy

/**
 * C03 — Result / evidence screen: **pure** state, copy and presenter.
 *
 * Deliberately free of `android.*` and `androidx.compose.*`: everything here is plain Kotlin so
 * `testDebugUnitTest` can exercise it on a JVM with no device, no emulator and no Compose UI-test
 * dependency (which the app does not have). [ResultScreen] is the only file in this package that
 * touches Compose.
 *
 * Product rules encoded here (docs/android/HEAD_ORDERS_ANDROID.md section 2, UI_SPEC, BEHAVIOR_SPEC):
 *  - risk and confidence are **two separate values**, never merged into one figure;
 *  - evidence only ever shows an **exact quote taken from the source text**; a quote that does not
 *    verify as a normalised substring of the source is dropped and the result degrades to
 *    `Unable to Verify` rather than inventing support;
 *  - unsupported language / unreadable input => `Unable to Verify` + low confidence, never "safe";
 *  - no absolute-safety claim can be produced (see [AbsoluteSafetyGuard]);
 *  - colour is never the only differentiator (labels, glyphs and content descriptions travel with
 *    every tone).
 *
 * ## SEAM
 * A01's shared contract is available, while A05's local deterministic scorer is not merged yet, so
 * this file defines a **presentation-layer input** ([ResultInput]) that mirrors the engine output
 * shape documented in BEHAVIOR_SPEC (`{id, module, title_hinglish, detail, severity, strength, quote}`).
 * No domain verdict type, no model under `core/` and no scoring logic is created here: A05 will compute
 * the band, the confidence and the evidence list, and the caller maps that onto [ResultInput].
 * `// SEAM:` markers below show the exact places an adapter will plug in.
 */

// ---------------------------------------------------------------------------------------------
// Language
// ---------------------------------------------------------------------------------------------

/**
 * Display language for this screen. Hinglish is the default (HEAD_ORDERS section 2.6).
 *
 * Result-local on purpose: the app-wide language *preference* is owned by C04, which passes the
 * chosen value into [ResultScreen].
 */
enum class UiLanguage {
    HINGLISH,
    ENGLISH,
    HINDI;

    companion object {
        val Default: UiLanguage = HINGLISH

        fun of(english: Boolean): UiLanguage = if (english) ENGLISH else HINGLISH
        fun of(language: ai.trustshield.android.data.preferences.AppLanguage): UiLanguage = when (language) {
            ai.trustshield.android.data.preferences.AppLanguage.SYSTEM,
            ai.trustshield.android.data.preferences.AppLanguage.HINGLISH -> HINGLISH
            ai.trustshield.android.data.preferences.AppLanguage.ENGLISH -> ENGLISH
            ai.trustshield.android.data.preferences.AppLanguage.HINDI -> HINDI
        }
    }
}

/** The one place a bilingual string becomes a rendered one. */
fun ToneCopy.pick(language: UiLanguage = UiLanguage.Default): String = when (language) {
    UiLanguage.HINGLISH -> hinglish
    UiLanguage.ENGLISH -> english.ifBlank { hinglish }
    UiLanguage.HINDI -> hindi.ifBlank { english.ifBlank { hinglish } }
}

/** Builds a bilingual pair, falling back to Hinglish when the English variant is missing. */
internal fun copy(hinglish: String, english: String): ToneCopy {
    val hi = hinglish.trim()
    val en = english.trim()
    return ToneCopy(hi, en.ifBlank { hi })
}

// ---------------------------------------------------------------------------------------------
// Confidence — its own axis, never a percentage of "how scammy"
// ---------------------------------------------------------------------------------------------

/**
 * How much the app trusts its own reading of the input. This is **not** a risk level and is never
 * multiplied into the risk band: a high-risk result can carry low confidence (bad OCR, unsupported
 * language, unverified quotes) and that must stay visible as its own badge.
 */
enum class ConfidenceLevel {
    LOW,
    MEDIUM,
    HIGH;

    /** One step down, floored at [LOW]. */
    fun lower(): ConfidenceLevel = when (this) {
        HIGH -> MEDIUM
        MEDIUM -> LOW
        LOW -> LOW
    }
}

/** The separate confidence badge. Rendered next to — never inside — the risk header. */
data class ConfidenceBadge(
    val level: ConfidenceLevel,
    val label: ToneCopy,
    /** Why the level is what it is (e.g. quotes dropped, language unsupported). */
    val reason: ToneCopy,
    /** Text glyph so confidence is not conveyed by colour or by a bar alone. */
    val glyph: String
)

// ---------------------------------------------------------------------------------------------
// Risk band — tone + human label, never a bare colour
// ---------------------------------------------------------------------------------------------

/**
 * The risk band as the screen shows it.
 *
 * [tone] is the C02 presentation tone (never a verdict). [label], [summary], [glyph] and
 * [contentDescription] make the band fully readable with colour switched off, which is the
 * HEAD_ORDERS section 2 / UI_SPEC accessibility requirement.
 */
data class RiskBand(
    val tone: RiskTone,
    val label: ToneCopy,
    val summary: ToneCopy,
    val glyph: String,
    val contentDescription: ToneCopy,
    /** Deterministic engine score, shown only when source evidence verifies the result. */
    val score: Int? = null
)

// ---------------------------------------------------------------------------------------------
// Status — explicit Unable to Verify
// ---------------------------------------------------------------------------------------------

/** Why the engine/UI could not verify. Drives the copy shown inside the unable banner. */
enum class UnverifiedReason {
    UNSUPPORTED_LANGUAGE,
    UNREADABLE_INPUT,
    NO_VERIFIED_EVIDENCE
}

/**
 * Whether this result may state a risk band at all.
 *
 * [UnableToVerify] is a first-class outcome, not an error: it says what failed and the safest next
 * step (UI_SPEC "Copy and states"), and it never degrades into a reassuring "safe" claim.
 */
sealed interface ResultStatus {

    /** A band backed by at least one quote verified against the source text. */
    data object Verified : ResultStatus

    data class UnableToVerify(
        val reason: UnverifiedReason,
        /** What failed, in the user's language. */
        val whatFailed: ToneCopy,
        /** Safest next step while nothing is verified. */
        val safestNextStep: ToneCopy
    ) : ResultStatus
}

/** Convenience for composables/tests that only need the boolean. */
val ResultUiState.unableToVerify: Boolean
    get() = status is ResultStatus.UnableToVerify

// ---------------------------------------------------------------------------------------------
// Evidence
// ---------------------------------------------------------------------------------------------

/**
 * Severity of one evidence item. Presentation mapping only: INFO/WATCH/STRONG are tone hints, not
 * extra verdicts, and the strong level is the deterministic engine's call — never the UI's.
 */
enum class EvidenceSeverity {
    INFO,
    WATCH,
    STRONG;

    val tone: RiskTone
        get() = when (this) {
            INFO -> RiskTone.NEUTRAL
            WATCH -> RiskTone.AMBER
            STRONG -> RiskTone.RED
        }

    val glyph: String
        get() = when (this) {
            INFO -> "\u2139"   // ℹ
            WATCH -> "!"
            STRONG -> "\u203C" // ‼
        }

    fun label(): ToneCopy = when (this) {
        INFO -> copy("Jaankari", "Information")
        WATCH -> copy("Dhyan dein", "Watch")
        STRONG -> copy("Pakka sanket", "Strong signal")
    }
}

/**
 * A quote that was verified against the source text.
 *
 * [text] is always taken **from the source string itself** (original casing, punctuation and
 * spacing), so it is a literal substring by construction — never the engine's own paraphrase.
 * [start]/[endExclusive] are offsets into the original source, so a future "show in context" view
 * can highlight the span without re-searching.
 *
 * When the span is longer than [ResultPresenter.MAX_QUOTE_CHARS] the rendering is shortened to an
 * exact prefix plus an ellipsis; [truncated] records that, and the shortened text remains a literal
 * prefix of `source.substring(start, endExclusive)`.
 */
data class ExactQuote(
    val text: String,
    val truncated: Boolean,
    val start: Int,
    val endExclusive: Int
) {
    /** True when [text] is exactly the source span (no shortening applied). */
    val isFullSpan: Boolean get() = !truncated
}

/** One verified evidence card. */
data class EvidenceItem(
    val id: String,
    /** Which deterministic module produced this signal, shown as a small caption for transparency. */
    val module: String,
    val title: ToneCopy,
    val detail: ToneCopy,
    val severity: EvidenceSeverity,
    val severityLabel: ToneCopy,
    val severityTone: RiskTone,
    val glyph: String,
    val quote: ExactQuote
)

/** Evidence the presenter refused to show, with the reason. Never silently swallowed. */
data class DroppedEvidence(
    val count: Int,
    val ids: List<String>,
    val reason: ToneCopy
) {
    val isClean: Boolean get() = count == 0
}

// ---------------------------------------------------------------------------------------------
// Actions, limitations, backend state
// ---------------------------------------------------------------------------------------------

enum class ActionPriority { PRIMARY, SECONDARY }

/** A recommended next step. Always a human decision — the app never acts on the user's behalf. */
data class ResultAction(
    val id: String,
    val label: ToneCopy,
    val detail: ToneCopy,
    val priority: ActionPriority
)

/**
 * Backend enrichment state. Offline local analysis is mandatory and backend enrichment is disabled
 * by default (HEAD_ORDERS section 2 / UI_SPEC "Offline"), so this is labelled explicitly instead of
 * being hidden.
 */
data class BackendEnrichment(
    val available: Boolean,
    val label: ToneCopy,
    val detail: ToneCopy
) {
    companion object {
        /** The product default; pinned so a caller cannot accidentally imply online enrichment. */
        const val ENABLED_BY_DEFAULT: Boolean = false
    }
}

// ---------------------------------------------------------------------------------------------
// The screen state
// ---------------------------------------------------------------------------------------------

/**
 * Everything [ResultScreen] renders. Immutable and fully derived: the composable does no mapping,
 * no filtering and no copy-building, which is why all of the behaviour below is unit-tested.
 */
data class ResultUiState(
    val status: ResultStatus,
    /** Risk band — its own field, with its own label. */
    val risk: RiskBand,
    /** Confidence — its own field, deliberately not derivable from [risk]. */
    val confidence: ConfidenceBadge,
    val evidence: List<EvidenceItem>,
    val droppedEvidence: DroppedEvidence,
    val actions: List<ResultAction>,
    val limitations: List<ToneCopy>,
    val backend: BackendEnrichment,
    /** Max characters a rendered quote may occupy (prefix + ellipsis). Pinned by the presenter. */
    val quoteLimit: Int,
    /** Number of source characters analysed, for an honest "what was looked at" line. */
    val analysedCharacters: Int,
    /** The verdict always comes from the deterministic engine; this is a constant, not a toggle. */
    val deterministicVerdict: Boolean = true
) {

    /** One flat list of everything this state can put on screen, used by the safety tests. */
    fun visibleStrings(language: UiLanguage = UiLanguage.Default): List<String> {
        val out = mutableListOf<String>()
        out += risk.label.pick(language)
        out += risk.summary.pick(language)
        out += risk.contentDescription.pick(language)
        risk.score?.let { out += "Score: $it/100" }
        out += confidence.label.pick(language)
        out += confidence.reason.pick(language)
        (status as? ResultStatus.UnableToVerify)?.let {
            out += it.whatFailed.pick(language)
            out += it.safestNextStep.pick(language)
        }
        evidence.forEach {
            out += it.title.pick(language)
            out += it.detail.pick(language)
            out += it.severityLabel.pick(language)
            out += it.quote.text
        }
        out += droppedEvidence.reason.pick(language)
        actions.forEach {
            out += it.label.pick(language)
            out += it.detail.pick(language)
        }
        limitations.forEach { out += it.pick(language) }
        out += backend.label.pick(language)
        out += backend.detail.pick(language)
        return out.filter { it.isNotBlank() }
    }

    /**
     * The TalkBack announcement for the whole panel: **risk first, then confidence**, exactly the
     * order QUALITY_GATES section 6 asks for (evidence follows separately, card by card).
     *
     * [score] is optional and is *spoken only*: when the host knows the engine's figure it is appended
     * as the third clause, because a screen reader should still hear the number even though the screen
     * deliberately draws no number (see [RiskScoreSpeech]). Passing `null` keeps the previous wording
     * byte for byte, so the announcement never invents a figure it was not given.
     */
    fun screenDescription(language: UiLanguage = UiLanguage.Default, score: Int? = null): String {
        val head = risk.contentDescription.pick(language) + ". " + confidence.label.pick(language) + "."
        return if (score == null) head else head + " " + RiskScoreSpeech.announcement(score, language)
    }
}

// ---------------------------------------------------------------------------------------------
// Risk score -- spoken, never drawn (issue #115)
// ---------------------------------------------------------------------------------------------

/**
 * The wording for the deterministic score behind the band, for TalkBack only.
 *
 * The Result screen draws a coarse four-step band and no figure, because a precise-looking number
 * implies precision the local rules do not have (UI_SPEC "Tokens and motion"). Issue #115 was the
 * opposite mistake: the navigation host drew `Text("Risk score: 70/100")` above the header row, so a
 * bare line sat in the status-bar zone. The number now reaches users through
 * [ResultUiState.screenDescription]'s content description only -- it is never passed to a `Text`.
 *
 * [VISIBLE_LITERAL] is the exact stray label that must not come back; keeping it here (rather than in
 * a comment) lets `ResultScreenStrayTextTest` assert the predicate over every string the screen draws.
 */
object RiskScoreSpeech {

    /** The stray user-visible label removed in issue #115. */
    const val VISIBLE_LITERAL: String = "Risk score"

    /** True when [text] is (or contains) the stray score line -- the regression predicate. */
    fun isStrayScoreLine(text: String): Boolean =
        text.contains(VISIBLE_LITERAL, ignoreCase = true)

    /**
     * Spoken form of [score] out of 100.
     *
     * Clamped to 0..100 so a bad engine value cannot be read out as "140/100"; the spoken phrasing is
     * Hinglish by default like the rest of the screen, and "out of" is spelled out because TalkBack
     * reads a slash as noise.
     */
    fun announcement(score: Int, language: UiLanguage = UiLanguage.Default): String {
        val clamped = score.coerceIn(0, 100)
        return when (language) {
            UiLanguage.ENGLISH -> "$VISIBLE_LITERAL $clamped out of 100"
            UiLanguage.HINGLISH -> "$VISIBLE_LITERAL $clamped, 100 mein se"
            UiLanguage.HINDI -> "स्कोर $clamped में से 100"
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Presenter input (presentation mirror of the engine result — see the SEAM note at the top)
// ---------------------------------------------------------------------------------------------

/** What the reader could actually make of the input. */
enum class InputCoverage {
    /** Text read and language supported: a band may be shown. */
    READABLE_SUPPORTED,

    /** OCR/ASR produced nothing usable, or the text is unreadable. */
    UNREADABLE,

    /** Language the local rules do not cover: never guessed. */
    UNSUPPORTED_LANGUAGE
}

/**
 * One evidence item as handed over by the deterministic engine.
 * `// SEAM:` A01's contract type lands here via an adapter; nothing else in this package knows it.
 */
data class ResultEvidenceInput(
    val id: String,
    val module: String,
    val titleHinglish: String,
    val titleEnglish: String,
    val detailHinglish: String,
    val detailEnglish: String,
    val severity: EvidenceSeverity,
    /** Must be an exact quote from the analysed text; unverifiable quotes are dropped. */
    val quote: String
)

data class ResultActionInput(
    val id: String,
    val labelHinglish: String,
    val labelEnglish: String,
    val detailHinglish: String,
    val detailEnglish: String,
    val priority: ActionPriority = ActionPriority.SECONDARY
)

/**
 * Everything the presenter needs.
 *
 * `// SEAM:` A05 supplies [sourceText], [coverage], [riskTone], [confidence] and [evidence];
 * C01/C04 supply navigation and the language preference (via [ResultScreen]'s parameter).
 */
data class ResultInput(
    val sourceText: String,
    val coverage: InputCoverage,
    /** Band chosen by the deterministic engine — already mapped to a presentation tone. */
    val riskTone: RiskTone,
    /** Engine confidence, kept on its own axis. */
    val confidence: ConfidenceLevel,
    val evidence: List<ResultEvidenceInput> = emptyList(),
    val actions: List<ResultActionInput> = emptyList(),
    val limitations: List<ToneCopy> = emptyList(),
    /** Backend enrichment is off by default; a caller must opt in explicitly. */
    val backendAvailable: Boolean = BackendEnrichment.ENABLED_BY_DEFAULT,
    /** A05's deterministic 0..100 reading; omitted for unsupported/unreadable inputs. */
    val riskScore: Int? = null
)

// ---------------------------------------------------------------------------------------------
// Normalisation + exact-quote verification
// ---------------------------------------------------------------------------------------------

/**
 * The quote verifier required by HEAD_ORDERS section 2.2: a claim is only shown if its quote is a
 * **normalised substring** of the input, and the displayed text is the source span itself.
 *
 * Normalisation (documented, deterministic, no rewriting of content):
 *  - Unicode zero-width characters are removed (they are invisible and would let a fake quote hide);
 *  - every run of whitespace (including NBSP and other Unicode spaces) collapses to one space;
 *  - leading/trailing whitespace is trimmed;
 *  - characters are lower-cased for **comparison only**.
 *
 * The comparison string never reaches the UI: on a match we slice the *original* source, so case,
 * punctuation and spacing are preserved and the rendered quote is a literal substring.
 */
object TextNormalise {

    private fun isZeroWidth(c: Char): Boolean =
        c == '\u200B' || c == '\u200C' || c == '\u200D' || c == '\u2060' || c == '\uFEFF'

    private fun isSpace(c: Char): Boolean =
        c == ' ' || c == '\u00A0' || c == '\u2007' || c == '\u202F' || Character.isWhitespace(c)

    /** Normalised text plus, per normalised char, the index of the source char it came from. */
    class Indexed internal constructor(val text: String, internal val offsets: IntArray)

    fun index(source: String): Indexed {
        val out = StringBuilder(source.length)
        val offsets = IntArray(source.length)
        var n = 0
        var pendingSpace = -1
        for (i in source.indices) {
            val c = source[i]
            if (isZeroWidth(c)) continue
            if (isSpace(c)) {
                if (n > 0 && pendingSpace < 0) pendingSpace = i
                continue
            }
            if (pendingSpace >= 0) {
                offsets[n] = pendingSpace
                out.append(' ')
                n++
                pendingSpace = -1
            }
            offsets[n] = i
            out.append(c.lowercaseChar())
            n++
        }
        return Indexed(out.toString(), offsets.copyOf(n))
    }

    /** Normalised form used for substring matching only. */
    fun normalise(text: String): String = index(text).text

    /** True when [quote] appears in [source] after normalisation. */
    fun isExactQuote(source: String, quote: String): Boolean = findQuote(source, quote) != null

    /**
     * Locates [quote] in [source] and returns the original span, or null when the quote is not a
     * literal (normalised) substring — the caller must then drop the evidence.
     */
    fun findQuote(source: String, quote: String): QuoteMatch? {
        if (source.isEmpty() || quote.isBlank()) return null
        val src = index(source)
        val needle = index(quote).text
        if (needle.isEmpty() || needle.length > src.text.length) return null
        val at = src.text.indexOf(needle)
        if (at < 0) return null
        val last = at + needle.length - 1
        val start = src.offsets[at]
        val endExclusive = src.offsets[last] + 1
        if (start < 0 || endExclusive > source.length || start >= endExclusive) return null
        return QuoteMatch(start, endExclusive, source.substring(start, endExclusive))
    }
}

/** A verified span of the original source. */
data class QuoteMatch(val start: Int, val endExclusive: Int, val text: String)

// ---------------------------------------------------------------------------------------------
// Absolute-safety guard
// ---------------------------------------------------------------------------------------------

/**
 * Blocks the one class of copy the product must never emit: an absolute safety guarantee.
 *
 * Used defensively in two ways:
 *  1. the presenter never builds a risk label from engine text (labels come from C02's
 *     [RiskToneCopy]), so an absolutist phrase cannot reach the header through a label;
 *  2. any engine-supplied detail/limitation/action string is scanned, and a *sentence* that
 *     contains a forbidden phrase is replaced wholesale by a neutral line — the claim is removed,
 *     never reworded into a weaker guarantee, and no support is invented.
 */
object AbsoluteSafetyGuard {

    /** Lower-cased, whitespace-normalised phrases that may never reach the screen. */
    val forbiddenPhrases: List<String> = listOf(
        "100% safe",
        "100 % safe",
        "100 percent safe",
        "100 per cent safe",
        "hundred percent safe",
        "absolutely safe",
        "completely safe",
        "fully safe",
        "totally safe",
        "perfectly safe",
        "entirely safe",
        "guaranteed safe",
        "guarantee safe",
        "guaranteed to be safe",
        "risk-free",
        "risk free",
        "zero risk",
        "no risk at all",
        "bilkul safe",
        "bilkul surakshit",
        "poori tarah safe",
        "pura safe",
        "safe hai guarantee"
    )

    /** Forbidden phrases found in [text]; empty means clean. */
    fun violations(text: String): List<String> {
        val flat = TextNormalise.normalise(text)
        if (flat.isEmpty()) return emptyList()
        return forbiddenPhrases.filter { flat.contains(it) }
    }

    fun isClean(text: String): Boolean = violations(text).isEmpty()

    /**
     * Replaces every sentence containing a forbidden phrase with [replacement].
     * Sentence boundaries are `.`, `!`, `?` and newlines; the replacement itself must be neutral
     * (see [ResultCopy.ABSOLUTE_CLAIM_REPLACEMENT]) and is never scanned for violations.
     */
    fun sanitize(text: String, replacement: String = ResultCopy.ABSOLUTE_CLAIM_REPLACEMENT.hinglish): String {
        if (isClean(text)) return text
        val sentences = SENTENCE.findAll(text).map { it.value }.filter { it.isNotBlank() }.toList()
        if (sentences.isEmpty()) return replacement
        return sentences.joinToString(" ") { sentence ->
            if (isClean(sentence)) sentence.trim() else replacement
        }
    }

    private val SENTENCE = Regex("[^.!?\\n]+[.!?\\n]*")
}

// ---------------------------------------------------------------------------------------------
// Copy (this package owns its strings; no res/values — see the shared contract)
// ---------------------------------------------------------------------------------------------

/** Every user-facing string this screen can show, in Hinglish and English. */
object ResultCopy {

    const val TITLE_HINGLISH: String = "Result"
    const val TITLE_ENGLISH: String = "Result"

    val EVIDENCE_SECTION = copy("Kya mila (exact quote ke saath)", "What we found (with exact quotes)")
    val ACTIONS_SECTION = copy("Aap kya kar sakte hain", "What you can do")
    val LIMITS_SECTION = copy("Kya yeh check nahi kar sakta", "What this check cannot do")
    val QUOTE_LABEL = copy("Exact quote", "Exact quote")
    val QUOTE_TRUNCATED = copy("Quote chhota kiya gaya hai", "Quote shortened")
    val CONFIDENCE_TITLE = copy("Bharosa", "Confidence")
    val RISK_TITLE = copy("Khatra", "Risk")
    val LANGUAGE_TOGGLE_HINGLISH = "English"
    val LANGUAGE_TOGGLE_ENGLISH = "Hinglish"
    val LANGUAGE_TOGGLE_DESCRIPTION = copy("Bhasha badlein", "Switch language")

    /** Neutral replacement for a sentence that contained an absolute-safety claim. */
    val ABSOLUTE_CLAIM_REPLACEMENT = copy(
        "Sirf itna pakka hai jitna quote se dikh raha hai.",
        "Only what the quotes show is certain."
    )

    val VERDICT_NOTE = copy(
        "Yeh verdict deterministic rules se aata hai, kisi AI ke andaze se nahi.",
        "This verdict comes from deterministic rules, not from an AI guess."
    )

    val NOT_PROOF = copy(
        "Yeh result final legal proof nahi hai. Paise ya OTP diye hain to turant bank aur 1930 par report karein.",
        "This result is not legal proof. If you shared money or an OTP, report to your bank and 1930 immediately."
    )

    /** Band summaries. Never absolutist: "kam khatra" is not "safe". */
    fun bandSummary(tone: RiskTone): ToneCopy = when (tone) {
        RiskTone.NEUTRAL -> copy(
            "Koi khatre ka pattern nahi mila, par yeh guarantee nahi hai.",
            "No risk pattern found, but this is not a guarantee."
        )
        RiskTone.GREEN -> copy(
            "Kam khatre ke sanket mile. Phir bhi anjaan link ya OTP request par dhyan dein.",
            "Few warning signals. Still be careful with unknown links or OTP requests."
        )
        RiskTone.AMBER -> copy(
            "Kuch khatre ke sanket mile. Paisa bhejne se pehle khud verify karein.",
            "Some warning signals. Verify independently before sending money."
        )
        RiskTone.RED -> copy(
            "Scam ka pattern mila. Turant rukein aur official number se verify karein.",
            "A scam pattern was found. Stop now and verify through an official number."
        )
    }

    fun confidenceLabel(level: ConfidenceLevel): ToneCopy = when (level) {
        ConfidenceLevel.LOW -> copy("Kam bharosa", "Low confidence")
        ConfidenceLevel.MEDIUM -> copy("Theek thak bharosa", "Medium confidence")
        ConfidenceLevel.HIGH -> copy("Zyada bharosa", "High confidence")
    }

    fun confidenceGlyph(level: ConfidenceLevel): String = when (level) {
        ConfidenceLevel.LOW -> "\u25CB"   // ○
        ConfidenceLevel.MEDIUM -> "\u25D1" // ◑
        ConfidenceLevel.HIGH -> "\u25CF"   // ●
    }

    fun confidenceReason(level: ConfidenceLevel): ToneCopy = when (level) {
        ConfidenceLevel.LOW -> copy(
            "Text ya bhasha poora padha nahi ja saka, isliye bharosa kam hai.",
            "The text or language could not be read fully, so confidence is low."
        )
        ConfidenceLevel.MEDIUM -> copy(
            "Kuch quotes verify hue, par sab nahi.",
            "Some quotes verified, not all."
        )
        ConfidenceLevel.HIGH -> copy(
            "Saare dikhaye gaye quotes source text se match hue.",
            "Every quote shown matched the source text."
        )
    }

    val DROPPED_REASON = copy(
        "Jin quotes ko source text mein exact nahi mila, woh hata diye gaye.",
        "Quotes that did not match the source text exactly were removed."
    )

    val DROPPED_REASON_EMPTY = copy(
        "Koi evidence quote verify nahi hua.",
        "No evidence quote could be verified."
    )

    val UNABLE_LABEL = copy("Verify nahi kar paye", "Unable to verify")
    val UNABLE_SUMMARY = copy(
        "Is input par hum pakka verdict nahi de sakte. Neeche likha hai kya fail hua aur sabse safe next step.",
        "We cannot give a firm verdict on this input. Below is what failed and the safest next step."
    )
    val UNABLE_CONTENT_DESCRIPTION = copy(
        "Verify nahi kar paye — koi khatra ya safe ka dawa nahi",
        "Unable to verify — no risk or safety claim is made"
    )
    val UNABLE_GLYPH = "?"

    val UNSUPPORTED_LANGUAGE_FAILED = copy(
        "Is bhasha ko abhi poori tarah check nahi kar sakte",
        "This language cannot be fully checked yet"
    )
    val UNREADABLE_FAILED = copy(
        "Text ya awaaz saaf nahi thi, isliye usse padha nahi ja saka",
        "The text or audio was not clear enough to read"
    )
    val NO_EVIDENCE_FAILED = copy(
        "Koi bhi quote source text se match nahi hua",
        "None of the quotes matched the source text"
    )
    val SAFEST_NEXT_STEP = copy(
        "Kisi link par click na karein, OTP ya paisa na bhejein, aur bank/police ke official number par khud call karke verify karein.",
        "Do not tap any link, do not share an OTP or money, and verify by calling the official bank/police number yourself."
    )

    val BACKEND_OFF_LABEL = copy("Backend check band hai", "Backend checks are off")
    val BACKEND_OFF_DETAIL = copy(
        "Yeh result sirf device par chale local rules se bana hai. Online enrichment abhi available nahi hai.",
        "This result comes from on-device local rules only. Online enrichment is not available."
    )
    val BACKEND_ON_LABEL = copy("Backend check chalu hai", "Backend checks are on")
    val BACKEND_ON_DETAIL = copy(
        "Local result ke saath online enrichment bhi chala hai; dono alag se dikhaye gaye hain.",
        "Online enrichment also ran alongside the local result; both are attributed separately."
    )

    val DEFAULT_LIMITATION_SOURCE_SCOPE = copy(
        "Sirf isi text par check hua hai, aapki poori chat ya call par nahi.",
        "Only this text was checked, not your whole chat or call."
    )
    val DEFAULT_LIMITATION_NOT_PROOF = copy(
        "Yeh legal proof nahi hai aur kisi vyakti ko scammer declare nahi karta.",
        "This is not legal proof and does not declare any person a scammer."
    )
    val DEFAULT_LIMITATION_OFFLINE = copy(
        "Backend check band hai, isliye kuch signal miss ho sakte hain.",
        "Backend checks are off, so some signals may be missed."
    )
    val DEFAULT_LIMITATION_LANGUAGE = copy(
        "Unsupported bhasha mein kuch signal chhoot sakte hain.",
        "Some signals may be missed in unsupported languages."
    )

    val ANALYSED_CHARS = copy("Itne characters check hue: ", "Characters checked: ")
}

// ---------------------------------------------------------------------------------------------
// Presenter
// ---------------------------------------------------------------------------------------------

/**
 * Pure mapper from [ResultInput] to [ResultUiState]. The only place the rules live.
 *
 * Order of operations matters and is tested:
 *  1. coverage gate (unreadable / unsupported language) => `Unable to Verify`, forced low confidence;
 *  2. quote verification — every evidence item needs a literal normalised substring of the source,
 *     otherwise it is dropped and the result degrades to `Unable to Verify` instead of pretending;
 *  3. truncation of long quotes to an exact prefix plus ellipsis;
 *  4. absolute-safety scrubbing of every engine-supplied string;
 *  5. risk band and confidence assembled into **separate** fields.
 */
object ResultPresenter {

    /** Bounded quote length for display. Long quotes keep an exact prefix, never a paraphrase. */
    const val MAX_QUOTE_CHARS: Int = 160

    const val ELLIPSIS: String = "\u2026"

    fun build(input: ResultInput): ResultUiState {
        val verified = mutableListOf<EvidenceItem>()
        val droppedIds = mutableListOf<String>()

        for (item in input.evidence) {
            val quote = item.quote
            val match = if (quote.isBlank()) null else TextNormalise.findQuote(input.sourceText, quote)
            if (match == null) {
                droppedIds += item.id
                continue
            }
            verified += toEvidence(item, match)
        }

        val dropped = DroppedEvidence(
            count = droppedIds.size,
            ids = droppedIds.toList(),
            reason = if (verified.isEmpty()) ResultCopy.DROPPED_REASON_EMPTY else ResultCopy.DROPPED_REASON
        )

        val status: ResultStatus = when {
            input.coverage == InputCoverage.UNREADABLE -> ResultStatus.UnableToVerify(
                UnverifiedReason.UNREADABLE_INPUT,
                ResultCopy.UNREADABLE_FAILED,
                ResultCopy.SAFEST_NEXT_STEP
            )
            input.coverage == InputCoverage.UNSUPPORTED_LANGUAGE -> ResultStatus.UnableToVerify(
                UnverifiedReason.UNSUPPORTED_LANGUAGE,
                ResultCopy.UNSUPPORTED_LANGUAGE_FAILED,
                ResultCopy.SAFEST_NEXT_STEP
            )
            verified.isEmpty() -> ResultStatus.UnableToVerify(
                UnverifiedReason.NO_VERIFIED_EVIDENCE,
                ResultCopy.NO_EVIDENCE_FAILED,
                ResultCopy.SAFEST_NEXT_STEP
            )
            else -> ResultStatus.Verified
        }

        val risk = when (status) {
            ResultStatus.Verified -> RiskBand(
                tone = input.riskTone,
                // Label always comes from C02's tone copy, never from engine text.
                label = RiskToneCopy.of(input.riskTone).label,
                summary = ResultCopy.bandSummary(input.riskTone),
                glyph = RiskToneCopy.of(input.riskTone).glyph,
                contentDescription = RiskToneCopy.of(input.riskTone).contentDescription,
                score = input.riskScore?.coerceIn(0, 100)
            )
            is ResultStatus.UnableToVerify -> RiskBand(
                tone = RiskTone.NEUTRAL,
                label = ResultCopy.UNABLE_LABEL,
                summary = ResultCopy.UNABLE_SUMMARY,
                glyph = ResultCopy.UNABLE_GLYPH,
                contentDescription = ResultCopy.UNABLE_CONTENT_DESCRIPTION
            )
        }

        // Confidence is computed on its own axis and never from the risk tone.
        var level = input.confidence
        if (droppedIds.isNotEmpty()) level = level.lower()
        if (status is ResultStatus.UnableToVerify) level = ConfidenceLevel.LOW
        val confidence = ConfidenceBadge(
            level = level,
            label = ResultCopy.confidenceLabel(level),
            reason = if (droppedIds.isNotEmpty() && status is ResultStatus.Verified) {
                ResultCopy.DROPPED_REASON
            } else {
                ResultCopy.confidenceReason(level)
            },
            glyph = ResultCopy.confidenceGlyph(level)
        )

        val backend = if (input.backendAvailable) {
            BackendEnrichment(true, ResultCopy.BACKEND_ON_LABEL, sanitizeCopy(ResultCopy.BACKEND_ON_DETAIL))
        } else {
            BackendEnrichment(false, ResultCopy.BACKEND_OFF_LABEL, sanitizeCopy(ResultCopy.BACKEND_OFF_DETAIL))
        }

        val limitations = buildLimitations(input, status, backend)

        val actions = input.actions
            .map { a ->
                ResultAction(
                    id = a.id,
                    label = sanitizeCopy(copy(a.labelHinglish, a.labelEnglish)),
                    detail = sanitizeCopy(copy(a.detailHinglish, a.detailEnglish)),
                    priority = a.priority
                )
            }
            .sortedBy { it.priority.ordinal } // PRIMARY steps first; stable within a group

        return ResultUiState(
            status = status,
            risk = risk,
            confidence = confidence,
            evidence = verified.toList(),
            droppedEvidence = dropped,
            actions = actions,
            limitations = limitations,
            backend = backend,
            quoteLimit = MAX_QUOTE_CHARS,
            analysedCharacters = input.sourceText.length
        )
    }

    private fun toEvidence(item: ResultEvidenceInput, match: QuoteMatch): EvidenceItem {
        val shortened = truncateQuote(match.text, MAX_QUOTE_CHARS)
        return EvidenceItem(
            id = item.id,
            module = item.module,
            title = sanitizeCopy(copy(item.titleHinglish, item.titleEnglish)),
            detail = sanitizeCopy(copy(item.detailHinglish, item.detailEnglish)),
            severity = item.severity,
            severityLabel = item.severity.label(),
            severityTone = item.severity.tone,
            glyph = item.severity.glyph,
            quote = ExactQuote(
                text = shortened.text,
                truncated = shortened.truncated,
                start = match.start,
                endExclusive = match.endExclusive
            )
        )
    }

    private fun buildLimitations(
        input: ResultInput,
        status: ResultStatus,
        backend: BackendEnrichment
    ): List<ToneCopy> {
        val out = mutableListOf<ToneCopy>()
        out += ResultCopy.DEFAULT_LIMITATION_SOURCE_SCOPE
        out += ResultCopy.DEFAULT_LIMITATION_NOT_PROOF
        if (!backend.available) out += ResultCopy.DEFAULT_LIMITATION_OFFLINE
        if (status is ResultStatus.UnableToVerify &&
            status.reason == UnverifiedReason.UNSUPPORTED_LANGUAGE
        ) {
            out += ResultCopy.DEFAULT_LIMITATION_LANGUAGE
        }
        input.limitations.forEach { out += sanitizeCopy(it) }
        return out.distinctBy { it.hinglish }
    }

    private fun sanitizeCopy(source: ToneCopy): ToneCopy = ToneCopy(
        AbsoluteSafetyGuard.sanitize(source.hinglish, ResultCopy.ABSOLUTE_CLAIM_REPLACEMENT.hinglish),
        AbsoluteSafetyGuard.sanitize(source.english, ResultCopy.ABSOLUTE_CLAIM_REPLACEMENT.english)
    )

    class Shortened internal constructor(val text: String, val truncated: Boolean)

    /**
     * Shortens [text] to at most [limit] characters, keeping an **exact prefix** of the original and
     * appending [ELLIPSIS]. Never reflows, re-cases or re-words the quote.
     */
    fun truncateQuote(text: String, limit: Int = MAX_QUOTE_CHARS): Shortened {
        if (limit <= 0 || text.length <= limit) return Shortened(text, false)
        var cut = limit
        // Never split a surrogate pair: back off if we would end on a high surrogate.
        if (cut > 0 && Character.isHighSurrogate(text[cut - 1])) cut -= 1
        var prefix = text.substring(0, cut)
        // Trailing whitespace before an ellipsis reads as a typo; trimming the end keeps a prefix.
        prefix = prefix.trimEnd()
        return Shortened(prefix + ELLIPSIS, true)
    }
}
