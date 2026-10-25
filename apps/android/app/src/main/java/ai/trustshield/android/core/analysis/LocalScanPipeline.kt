package ai.trustshield.android.core.analysis

import ai.trustshield.android.core.model.AnalysisResult
import ai.trustshield.android.core.model.InputType
import ai.trustshield.android.core.model.RiskAssessment
import ai.trustshield.android.core.model.TextNormalizer
import ai.trustshield.android.core.normalize.HinglishNormalizer
import ai.trustshield.android.core.score.LocalScorer

/**
 * What one offline scan hands to the UI (P3-02, #81).
 *
 * @property result the A01 contract result: risk assessment, evidence, stages, limitations, actions.
 *   Always `origin = LOCAL`, `backend = DISABLED`. Risk score and confidence stay two independent
 *   fields inside [AnalysisResult.risk] - this class never merges, re-scores or re-derives them.
 * @property escalateCritical A05's UI escalation switch: a verified money/credential ask with multiple
 *   independent corroborations. It is *not* a risk label; a `HIGH_RISK` result may legally carry
 *   `escalateCritical = false` (the calmer "call back on the official number" path).
 * @property verifiedAsk true when a directional ask (OTP/PIN, money, remote app, APK, personal data)
 *   survived quote verification for this input. A protective "OTP share na karein" is not an ask.
 * @property analyzedText the exact string the evidence quotes were verified against, so
 *   `result.unverifiableEvidence(analyzedText)` must be empty. It is the trimmed input; when
 *   [inputTruncated] is true only its first [maxInputChars] characters were used for the verdict.
 * @property inputTruncated true when the input was longer than [maxInputChars]. Coverage was partial
 *   and [AnalysisResult.risk] confidence already reflects that; the risk signal itself is untouched.
 * @property maxInputChars the bound this outcome was produced against
 *   ([LocalScanPipeline.MAX_INPUT_CHARS]), carried so the UI can show "pehle N characters check hue"
 *   without re-declaring the constant.
 */
data class ScanOutcome(
    val result: AnalysisResult,
    val escalateCritical: Boolean,
    val verifiedAsk: Boolean,
    val analyzedText: String,
    val inputTruncated: Boolean,
    val maxInputChars: Int = LocalScanPipeline.MAX_INPUT_CHARS,
) {
    /** Convenience for the result screen: risk and confidence are two fields of this one object. */
    val risk: RiskAssessment get() = result.risk
}

/**
 * The single offline entry point the Android UI calls to judge a message (P3-02, #81).
 *
 * Runs the existing deterministic engine end to end, in one place, with nothing else attached:
 *
 *  1. A02 [HinglishNormalizer] normalization (Roman-Hinglish spelling variants, obfuscated `OTP`/`PIN`,
 *     zero-width characters, Devanagari digits) - every normalized character keeps its raw span;
 *  2. A03 [ai.trustshield.android.core.rules.StageRules] five-stage detection with negation guards;
 *  3. A04 [ai.trustshield.android.core.rules.LegitimateIndicators] protective/legitimate evidence and a
 *     bounded risk reduction;
 *  4. A05 [LocalScorer] fusion: score, label, confidence, evidence, stages, limitations, actions.
 *
 * ## Offline, private, deterministic
 *
 * Pure Kotlin: no `android.*` import, no `Context`, no network, no disk, no clock, no randomness, no
 * logging. Nothing is cached here - the returned [ScanOutcome] is the only carrier of the text, so
 * dropping the outcome drops the message. The same input with the same normalizer and sensitivity
 * always produces an equal [AnalysisResult], `analysisId` and evidence ids included.
 *
 * ## Risk versus confidence
 *
 * A05 already keeps them apart and this class passes both through untouched. Truncated or unreadable
 * input lowers **confidence** (and adds a Hinglish limitation); it never rewrites the risk score and
 * never turns an unreadable message into "safe". Unsupported script, emoji-only and blank input come
 * back as `UNABLE_TO_VERIFY` with at least one limitation, never as `LOW_RISK`.
 *
 * ## The 20 000-character bound
 *
 * [sanitizeInput] trims and caps at [MAX_INPUT_CHARS] without splitting a surrogate pair, so the scan
 * screen can show an honest character count and truncation notice. The bound is deliberately not
 * re-implemented for scoring: [analyzeText] hands the trimmed text to A05, which applies the *same*
 * [HinglishNormalizer.MAX_TEXT_CHARS] window through the normalizer's surrogate-safe raw limit and
 * reports `LocalScorer.LIMIT_TRUNCATED` plus the confidence cap itself. [ScanOutcome.inputTruncated]
 * tells the UI that this happened.
 *
 * ## Threading
 *
 * One [LocalScorer] instance is owned per pipeline. A05 (and A04 inside it) keeps a "last assessment"
 * field for its no-argument convenience overloads, so calls on one pipeline must be serialized - call
 * it from a background dispatcher, one scan at a time - or create one pipeline per screen/session.
 * Every method here is safe to call from a background thread and none of them block on I/O.
 *
 * ```kotlin
 * val pipeline = LocalScanPipeline()                 // one per screen; serialize its calls
 * val outcome = pipeline.analyzeText(pastedText)     // or analyzeImageText(ocrText)
 * if (pipeline.isAnalysable(pastedText)) { /* enable the check action */ }
 * ```
 */
class LocalScanPipeline(
    normalizer: TextNormalizer = HinglishNormalizer(),
    sensitivity: LocalScorer.Sensitivity = LocalScorer.Sensitivity.STANDARD,
) {

    private val scorer: LocalScorer = LocalScorer(normalizer, sensitivity = sensitivity)

    /**
     * Analyse typed or pasted text. The default path for the scan screen, share intake and a call
     * transcript: everything the user can see as characters.
     *
     * Never throws for any string, never touches the network or disk, and never decides a verdict
     * itself - the deterministic engine does.
     */
    fun analyzeText(raw: String, inputType: InputType = InputType.TEXT): ScanOutcome {
        // Trim only. The 20 000-character window is A05's job: it owns the truncation limitation and
        // the confidence cap, so the pipeline must not silently pre-cut and then re-derive them.
        val analyzed = raw.trim()
        val truncated = analyzed.length > MAX_INPUT_CHARS
        val scored = scorer.analyzeScored(analyzed, inputType)
        return ScanOutcome(
            result = scored.result,
            escalateCritical = scored.escalateCritical,
            verifiedAsk = scored.verifiedAsk,
            analyzedText = scored.analyzedText,
            inputTruncated = truncated,
            maxInputChars = MAX_INPUT_CHARS,
        )
    }

    /**
     * Analyse text that came out of an OCR pass (image share, screenshot). Same engine, same bounds,
     * same verdict as [analyzeText]; only [InputType.IMAGE] is stamped so the UI can say the text was
     * read from a picture. Poor recognition quality keeps the existing confidence handling - this
     * method does not invent an OCR confidence number.
     */
    fun analyzeImageText(recognizedText: String): ScanOutcome =
        analyzeText(recognizedText, InputType.IMAGE)

    /**
     * The input the UI should submit: surrounding whitespace removed, length capped at
     * [MAX_INPUT_CHARS], and a cap that would fall inside a surrogate pair backs off one character so
     * no emoji is ever cut in half. Same bound as the window fusion applies, so the counter the user
     * sees and the coverage behind the verdict cannot disagree.
     */
    fun sanitizeInput(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.length <= MAX_INPUT_CHARS) return trimmed
        var cut = MAX_INPUT_CHARS
        if (Character.isHighSurrogate(trimmed[cut - 1])) cut--
        return trimmed.substring(0, cut)
    }

    /** True when [raw] (after trimming) is longer than [MAX_INPUT_CHARS]; the UI shows the notice. */
    fun isOverLimit(raw: String): Boolean = raw.trim().length > MAX_INPUT_CHARS

    /**
     * False only for blank/whitespace-only input, which the scan screen uses to disable its check
     * action. This is not a verdict: emoji-only text is "analysable" as input but still comes back
     * `UNABLE_TO_VERIFY` from the scorer, and unsupported scripts are judged by the engine, not here.
     */
    fun isAnalysable(raw: String): Boolean = raw.isNotBlank()

    companion object {
        /**
         * Hard input bound, shared by the UI counter and the analysis window. Pinned to
         * [HinglishNormalizer.MAX_TEXT_CHARS] so there is exactly one number in the app.
         */
        const val MAX_INPUT_CHARS: Int = HinglishNormalizer.MAX_TEXT_CHARS
    }
}
