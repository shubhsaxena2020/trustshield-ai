package ai.trustshield.android.core.score

import ai.trustshield.android.core.model.AnalysisResult
import ai.trustshield.android.core.model.AnalysisStatus
import ai.trustshield.android.core.model.BackendStatus
import ai.trustshield.android.core.model.Confidence
import ai.trustshield.android.core.model.DataOrigin
import ai.trustshield.android.core.model.Evidence
import ai.trustshield.android.core.model.InputType
import ai.trustshield.android.core.model.RiskAssessment
import ai.trustshield.android.core.model.RiskLabel
import ai.trustshield.android.core.model.Severity
import ai.trustshield.android.core.model.StageId
import ai.trustshield.android.core.model.Strength
import ai.trustshield.android.core.model.TextNormalizer
import ai.trustshield.android.core.normalize.HinglishNormalizer
import ai.trustshield.android.core.rules.AskType
import ai.trustshield.android.core.rules.LegitAssessment
import ai.trustshield.android.core.rules.LegitimateIndicators
import ai.trustshield.android.core.rules.StageDetection
import ai.trustshield.android.core.rules.StageMatch
import ai.trustshield.android.core.rules.StageRules
import kotlin.math.roundToInt

/**
 * A scored result plus the two things the UI needs that the A01 contract deliberately does not carry.
 *
 * @property result the contract result (risk + evidence + stages + limitations + actions). Always
 *   `origin = LOCAL`, `backend = DISABLED`.
 * @property escalateCritical true only for a **verified** money/credential ask with multiple
 *   independent corroborations (see [LocalScorer]). This is the UI escalation switch the spec asks
 *   for — it is *not* a risk label and it is *not* an evidence severity. B05 flips the full-screen
 *   alert and haptics on this flag; it must never be derived from a single stage.
 * @property verifiedAsk true when a directional ask survived verification for the analysed input.
 * @property analyzedText the exact string the quotes in [result] were verified against. In call mode
 *   this is the rolling transcript, not the single newest utterance, so
 *   `result.verifiableEvidence(analyzedText)` holds.
 * @property sessionQuotes session-only quotes for earlier utterances that may already have left the
 *   rolling window (the "2 ghante ki call" case). They are **not** part of [result] because they no
 *   longer verify against [analyzedText]; the Call Shield UI shows them as history. Empty for text
 *   analysis.
 */
data class ScoredResult(
    val result: AnalysisResult,
    val escalateCritical: Boolean,
    val verifiedAsk: Boolean,
    val analyzedText: String,
    val sessionQuotes: List<Evidence> = emptyList(),
)

/**
 * Local deterministic fusion for the Android app (package A05, work package #28).
 *
 * Input in, one [AnalysisResult] out. No network, no LLM, no clock, no randomness: the same text with
 * the same [sensitivity] always yields a byte-identical result (evidence ids are deterministic in
 * A01, [analysisId] is a hash of the analysed text).
 *
 * ## Scoring formula
 *
 * Per-stage weights (a stage contributes **once**, no matter how many lines back it):
 *
 * | stage | weight |
 * |---|---|
 * | AUTHORITY | 10 |
 * | THREAT_OR_REWARD | 22 |
 * | URGENCY | 8 |
 * | SECRECY | 14 |
 * | ASK (only when a verified directional ask exists) | 30 |
 * | analyzer-directed injection evidence | 15 |
 *
 * 1. Base score is a noisy-OR over the present stage weights, the same mental model as the backend
 *    (`1 - Π(1 - w/100)`, rounded), so independent signals compound and none can exceed 100:
 *    `score = round(100 * (1 - Π(1 - wᵢ/100)))`.
 * 2. **Independence.** Stages are counted by id, and each id only counts when at least one evidence
 *    item survives verification: quote present, an exact substring of the analysed text, and not
 *    inside a region A04 says the sender was *quoting* (`LegitAssessment.isQuoted`). Duplicate lines
 *    collapse (dedupe by quote), so repeating one sentence five times is still one stage.
 * 3. **Verified ask gates the bands.** "Verified" means A03 emitted a `STRONG` ask evidence item with
 *    a real span for this input, and A03 reported at least one [AskType]. A protective
 *    "OTP share na karein" is not an ask: A03 negation-guards it and no [AskType] is reported.
 * 4. **High-risk floor.** `verified ask` + at least [Sensitivity.corroborationMin] *other* independent
 *    stages => `score >= 70` (`HIGH_RISK`), rising by 5 per extra stage above the bar, capped at 85
 *    (five stages is the whole playbook).
 * 5. **Ask alone stays at most caution.** With no other stage the score is clamped into
 *    `[askAloneFloor, askAloneCeiling]`; with exactly one other stage into
 *    `[askPlusOneFloor, askPlusOneCeiling]` (numbers per preset, see [Sensitivity]). A verified ask is
 *    therefore never rendered as safe and never accused on its own — it may be a friend or a hacked
 *    account.
 * 6. **No verified ask => watch, not alarm.** `score <= 24` (`LOW_RISK`). BEHAVIOR_SPEC: an authority
 *    or reward claim with no ask stays low/watch. A03's severity discipline emits no `STRONG`
 *    non-ask evidence, so the "separate strong deterministic signal" escape hatch is intentionally
 *    not implemented here — when such a rule is added it must also raise this ceiling in one place.
 * 7. **Bounded legitimate reduction.** `legit.reduce(score, hasIndependentStrongAsk = verifiedAsk)`
 *    is applied last: A04's multiplier is `0.65..1.0`, so protective advice lowers risk but can never
 *    erase an independent verified ask (A04 returns the score untouched in that case). The ask floor
 *    is re-asserted afterwards.
 * 8. **`critical` is a UI escalation, not a label.** [ScoredResult.escalateCritical] is true only when
 *    the ask is verified and directional, at least 2 other independent stages corroborate it, the
 *    score reaches the 70 floor, and output confidence is not `LOW` (UI_SPEC: a noisy call holds the
 *    alert until corroboration lands). Being high-risk without `escalateCritical` is legal: the UI
 *    then uses the calmer "verify by calling back" path. Evidence severity is never escalated to
 *    `CRITICAL` here; the label set stays inside A01's enum.
 *
 * ## Confidence (independent of the score)
 *
 * Coverage-derived, never score-derived (see [confidenceFor]): starts at `HIGH`, capped at `MEDIUM`
 * for truncation (> 20 000 chars), unsupported scripts mixed with Hinglish, under 12 analysable
 * characters, or zero evidence; capped at `LOW` when ASR quality is below 0.5. A high score with low
 * confidence is legal and must stay legal.
 *
 * ## Unable to Verify
 *
 * Empty/emoji-only input, input whose letters are not Latin/Devanagari (or that has more letters in
 * an unsupported script than in a supported one — a Latin link cannot make a Tamil sentence
 * readable), and input with fewer than [MIN_ANALYSABLE_CHARS] analysable characters produce
 * `UNABLE_TO_VERIFY` with at least one Hinglish limitation. It is never `LOW_RISK`/"safe". A static link that is still attributable is kept as an
 * informational evidence item — the link is *never* opened or fetched.
 *
 * Instances hold A04's "last assessment" internally; use one instance per screen (analysis itself is
 * pure given a normalizer, but callers should not share an instance across concurrent analyses).
 */
class LocalScorer(
    private val normalizer: TextNormalizer,
    private val stages: StageRules = StageRules(normalizer),
    private val legit: LegitimateIndicators = LegitimateIndicators(normalizer),
    val sensitivity: Sensitivity = Sensitivity.STANDARD,
) {

    /**
     * Sensitivity presets (F08). They may move **only** the documented thresholds below and can never
     * suppress a verified strong ask or turn unverified input into safe.
     *
     * @property corroborationMin how many *other* independent stages are needed for the 70 floor.
     * @property askAloneFloor / [askAloneCeiling] band for a verified ask with zero other stages.
     * @property askPlusOneFloor / [askPlusOneCeiling] band for a verified ask with exactly one other
     *   stage. The documented bounds are `25..49` for the ask-alone band and `25..60` for the
     *   ask-plus-one band; no preset may push a bare ask into `HIGH_RISK` (>= 70), and no preset may
     *   drop a verified ask below `CAUTION` (25).
     */
    enum class Sensitivity(
        val corroborationMin: Int,
        val askAloneFloor: Int,
        val askAloneCeiling: Int,
        val askPlusOneFloor: Int,
        val askPlusOneCeiling: Int,
    ) {
        /** Stricter: three other stages needed before an accusation; a bare ask stays quieter. */
        LOW(corroborationMin = 3, askAloneFloor = 25, askAloneCeiling = 30, askPlusOneFloor = 25, askPlusOneCeiling = 40),

        /** Default: ask + any two other stages reaches the 70 floor (BEHAVIOR_SPEC rule gate). */
        STANDARD(corroborationMin = 2, askAloneFloor = 25, askAloneCeiling = 45, askPlusOneFloor = 35, askPlusOneCeiling = 49),

        /**
         * More sensitive: a bare ask sits higher inside caution and a single corroborating stage is
         * promoted to `SUSPICIOUS`. The floor bar stays at 2 — the documented minimum — because one
         * corroborating stage is exactly the friend / hacked-account case rule 2 protects.
         */
        HIGH(corroborationMin = 2, askAloneFloor = 30, askAloneCeiling = 49, askPlusOneFloor = 45, askPlusOneCeiling = 60),
    }

    /** Plain text / URL / image / QR transcription analysis. Deterministic for a given input. */
    fun analyze(raw: String, inputType: InputType = InputType.TEXT): AnalysisResult =
        analyzeScored(raw, inputType).result

    /** Same as [analyze] but also returns the UI escalation flag. */
    fun analyzeScored(raw: String, inputType: InputType = InputType.TEXT): ScoredResult =
        score(raw, inputType, null, asrConfidence = 1.0)

    /**
     * Conversation / Call Shield analysis (F05).
     *
     * [raw] is the newest utterance/chunk; [state] owns the rolling transcript and the persisted
     * stage memory. Each call records the stages it can still verify into [state] (at
     * `state.nowMillis`) so a stage survives the words leaving the window, then fuses:
     * the window's own evidence plus every stage the session still remembers inside the TTL.
     *
     * Quotes from utterances that already left the window are returned in
     * [ScoredResult.sessionQuotes] (session history) instead of `result.evidence`, because A01
     * forbids an evidence quote that does not verify against the analysed input. Callers pass
     * `state.transcript` — exposed as [ScoredResult.analyzedText] — when they want to re-verify.
     */
    fun analyzeTranscript(raw: String, state: CallSessionState): AnalysisResult =
        analyzeTranscriptScored(raw, state).result

    /** Same as [analyzeTranscript] but also returns the UI escalation flag. */
    fun analyzeTranscriptScored(raw: String, state: CallSessionState): ScoredResult {
        val analyzed = sessionText(raw, state)
        if (analyzed.isNotBlank()) {
            val assessment = legit.evaluate(analyzed)
            val detection = stages.detect(analyzed)
            for (match in accepted(detection, assessment, analyzed)) {
                state.record(match.stage, match.evidence, state.nowMillis)
            }
            if (detection.askTypes.isNotEmpty()) {
                state.recordAskTypes(detection.askTypes, state.nowMillis)
            }
        }
        return score(analyzed, InputType.CALL, state, state.asrQuality(state.nowMillis))
    }

    // ---------------------------------------------------------------------------------------
    // Fusion
    // ---------------------------------------------------------------------------------------

    private fun score(
        analyzed: String,
        inputType: InputType,
        session: CallSessionState?,
        asrConfidence: Double,
    ): ScoredResult {
        val hinglish = normalizer as? HinglishNormalizer
        val truncated = hinglish?.isTruncated(analyzed) ?: (analyzed.length > HinglishNormalizer.MAX_TEXT_CHARS)
        val scripts = scanScripts(analyzed)
        val assessment = legit.evaluate(analyzed)
        val detection = stages.detect(analyzed)
        val matches = accepted(detection, assessment, analyzed)

        val sessionNow = session?.nowMillis ?: 0L
        val persisted = session?.stageEvidence(sessionNow).orEmpty()
        val persistedAskTypes = session?.askTypes(sessionNow).orEmpty()

        val present = LinkedHashSet<StageId>()
        matches.forEach { present.add(it.stage) }
        persisted.keys.forEach { present.add(it) }

        val windowAsk = matches.firstOrNull { it.stage == StageId.ASK }?.evidence.orEmpty()
        val verifiedAsk = windowAsk.any { it.strength == Strength.STRONG } ||
            persisted[StageId.ASK]?.any { it.strength == Strength.STRONG } == true
        val askTypes = LinkedHashSet<AskType>(detection.askTypes).apply { addAll(persistedAskTypes) }

        val evidence = ArrayList<Evidence>(matches.sumOf { it.evidence.size } + detection.injection.size + 4)
        matches.forEach { evidence.addAll(it.evidence) }
        detection.injection.forEach { item ->
            val span = item.span ?: return@forEach
            if (assessment.isQuoted(span)) return@forEach
            evidence.add(item)
        }
        evidence.addAll(assessment.indicators)

        val sessionQuotes = if (session == null) {
            emptyList()
        } else {
            persisted.values.flatten().filter { quote -> evidence.none { it.id == quote.id } }
        }

        val analyzable = analyzed.count { it.isLetterOrDigit() }
        val unreadable = analyzed.isBlank() ||
            scripts.supportedLetters == 0 ||
            scripts.unsupportedLetters > scripts.supportedLetters ||
            analyzable < MIN_ANALYSABLE_CHARS
        if (unreadable) {
            return unableToVerify(
                analyzed = analyzed,
                inputType = inputType,
                scripts = scripts,
                truncated = truncated,
                stages = present.toList(),
                extraEvidence = urlEvidence(analyzed),
                sessionQuotes = sessionQuotes,
            )
        }

        val weights = ArrayList<Int>(present.size + 1)
        for (match in matches) {
            if (match.stage == StageId.ASK && !verifiedAsk) continue
            weights.add(WEIGHT.getValue(match.stage))
        }
        if (evidence.any { it in detection.injection }) weights.add(WEIGHT_INJECTION)

        var score = noisyOr(weights)
        val others = present.count { it != StageId.ASK }
        val bar = sensitivity.corroborationMin
        if (verifiedAsk) {
            score = when {
                others >= bar -> maxOf(score, HIGH_FLOOR + ESCALATION_STEP * minOf(others - bar, 3))
                others >= 1 -> score.coerceIn(sensitivity.askPlusOneFloor, sensitivity.askPlusOneCeiling)
                else -> score.coerceIn(sensitivity.askAloneFloor, sensitivity.askAloneCeiling)
            }
        } else {
            score = minOf(score, NO_ASK_CEILING)
        }
        // A04's bounded reduction; a no-op when an independent verified ask exists.
        score = legit.reduce(score, verifiedAsk, assessment)
        if (verifiedAsk) score = maxOf(score, sensitivity.askAloneFloor)

        val confidence = confidenceFor(
            analyzed = analyzed,
            evidenceCount = evidence.size,
            truncated = truncated,
            hasUnsupportedScript = scripts.unsupportedNames.isNotEmpty(),
            asrConfidence = asrConfidence,
        )
        val label = RiskAssessment.labelFor(score)

        val escalate = verifiedAsk &&
            others >= CRITICAL_CORROBORATIONS &&
            score >= HIGH_FLOOR &&
            confidence != Confidence.LOW &&
            askTypes.isNotEmpty()

        val limitations = ArrayList<String>(3)
        if (truncated) limitations.add(LIMIT_TRUNCATED)
        if (scripts.unsupportedNames.isNotEmpty()) limitations.add(limitationForScripts(scripts.unsupportedNames))
        if (asrConfidence < ASR_MEDIUM) limitations.add(LIMIT_LOW_ASR)
        if (evidence.isEmpty() && present.isEmpty()) limitations.add(LIMIT_NO_EVIDENCE)
        if (session != null && session.droppedUtterances > 0) limitations.add(LIMIT_WINDOW_DROPPED)

        val result = AnalysisResult(
            analysisId = analysisId(analyzed, inputType),
            inputType = inputType,
            status = AnalysisStatus.COMPLETED,
            risk = RiskAssessment(score = score, label = label, confidence = confidence),
            evidence = evidence,
            stages = ordered(present),
            limitations = limitations,
            actions = actionsFor(label = label, verifiedAsk = verifiedAsk, askTypes = askTypes),
            origin = DataOrigin.LOCAL,
            backend = BackendStatus.DISABLED,
        )
        return ScoredResult(
            result = result,
            escalateCritical = escalate,
            verifiedAsk = verifiedAsk,
            analyzedText = analyzed,
            sessionQuotes = sessionQuotes,
        )
    }

    /**
     * The "we could not judge this" path. Never `LOW_RISK`, always at least one limitation.
     *
     * Any static link that is still attributable is kept as informational evidence (the link is never
     * opened), which is why this can report [AnalysisStatus.PARTIAL] rather than a bare
     * [AnalysisStatus.UNABLE_TO_VERIFY].
     */
    private fun unableToVerify(
        analyzed: String,
        inputType: InputType,
        scripts: ScriptScan,
        truncated: Boolean,
        stages: List<StageId>,
        extraEvidence: List<Evidence>,
        sessionQuotes: List<Evidence>,
    ): ScoredResult {
        val limitations = ArrayList<String>(3)
        val anyUnsupported = scripts.unsupportedNames.isNotEmpty()
        when {
            anyUnsupported && scripts.supportedLetters == 0 -> limitations.add(limitationForScripts(scripts.unsupportedNames))
            scripts.supportedLetters == 0 -> limitations.add(LIMIT_NO_TEXT)
            anyUnsupported -> limitations.add(limitationForScripts(scripts.unsupportedNames))
            else -> limitations.add(LIMIT_TOO_LITTLE)
        }
        if (truncated) limitations.add(LIMIT_TRUNCATED)

        val result = AnalysisResult(
            analysisId = analysisId(analyzed, inputType),
            inputType = inputType,
            status = if (extraEvidence.isEmpty()) AnalysisStatus.UNABLE_TO_VERIFY else AnalysisStatus.PARTIAL,
            risk = RiskAssessment.unableToVerify(Confidence.LOW),
            evidence = extraEvidence,
            stages = ordered(LinkedHashSet(stages)),
            limitations = limitations,
            actions = listOf(ACTION_UNABLE_HOLD, ACTION_UNABLE_RETRY),
            origin = DataOrigin.LOCAL,
            backend = BackendStatus.DISABLED,
        )
        return ScoredResult(
            result = result,
            escalateCritical = false,
            verifiedAsk = false,
            analyzedText = analyzed,
            sessionQuotes = sessionQuotes,
        )
    }

    // ---------------------------------------------------------------------------------------
    // Evidence acceptance
    // ---------------------------------------------------------------------------------------

    /**
     * Keep only evidence that (a) the user can actually see, (b) is not text the *sender* was quoting,
     * and (c) still verifies against [analyzed]. A stage with no surviving item is dropped entirely,
     * so it cannot count towards independence or the floor.
     */
    private fun accepted(
        detection: StageDetection,
        assessment: LegitAssessment,
        analyzed: String,
    ): List<StageMatch> {
        val out = ArrayList<StageMatch>(detection.stages.size)
        for (match in detection.stages) {
            val items = ArrayList<Evidence>(match.evidence.size)
            val quotes = HashSet<String>(match.evidence.size)
            for (item in match.evidence) {
                val span = item.span ?: continue
                val quote = item.quote ?: continue
                if (assessment.isQuoted(span)) continue
                if (!item.quoteVerifiesAgainst(analyzed)) continue
                if (!quotes.add(normalizeKey(quote))) continue
                items.add(item)
            }
            if (items.isNotEmpty()) out.add(StageMatch(match.stage, items))
        }
        return out
    }

    /** Repeated identical lines collapse to one key so they cannot inflate a stage or the floor. */
    private fun normalizeKey(quote: String): String {
        val sb = StringBuilder(quote.length)
        var lastSpace = false
        for (ch in quote) {
            if (ch.isLetterOrDigit()) {
                sb.append(ch.lowercaseChar())
                lastSpace = false
            } else if (!lastSpace) {
                sb.append(' ')
                lastSpace = true
            }
        }
        return sb.toString().trim()
    }

    /** One static, attributable link signal. The URL is never opened, resolved or fetched. */
    private fun urlEvidence(input: String): List<Evidence> {
        val out = ArrayList<Evidence>(2)
        for (match in URL_PATTERN.findAll(input).take(MAX_URL_EVIDENCE)) {
            val quote = match.value
            if (quote.isBlank()) continue
            out.add(
                Evidence.verified(
                    input = input,
                    module = MODULE,
                    titleHinglish = "Message mein link hai (khula nahi gaya)",
                    detail = "Message mein link hai. Hum link kholte nahi aur uska destination check nahi " +
                        "kar sakte; official app ya site se hi kholein.",
                    severity = Severity.INFORMATIONAL,
                    strength = Strength.WEAK,
                    quote = quote,
                ),
            )
        }
        return out
    }

    // ---------------------------------------------------------------------------------------
    // Score / confidence helpers
    // ---------------------------------------------------------------------------------------

    /** Noisy-OR over stage weights: many independent signals compound, none can exceed 100. */
    private fun noisyOr(weights: List<Int>): Int {
        if (weights.isEmpty()) return 0
        var safe = 1.0
        for (weight in weights) {
            if (weight <= 0) continue
            safe *= 1.0 - (weight.coerceAtMost(100) / 100.0)
        }
        return ((1.0 - safe) * 100.0).roundToInt().coerceIn(0, 100)
    }

    /**
     * Confidence from **coverage only** (never from the score): truncation, unsupported script mixed
     * with Hinglish, a very short input, zero evidence, and ASR quality each cap the output.
     */
    private fun confidenceFor(
        analyzed: String,
        evidenceCount: Int,
        truncated: Boolean,
        hasUnsupportedScript: Boolean,
        asrConfidence: Double,
    ): Confidence {
        var confidence = if (evidenceCount == 0) Confidence.MEDIUM else Confidence.HIGH
        if (analyzed.count { it.isLetterOrDigit() } < MIN_CHARS_FOR_HIGH_CONFIDENCE) {
            confidence = lower(confidence, Confidence.MEDIUM)
        }
        if (truncated) confidence = lower(confidence, Confidence.MEDIUM)
        if (hasUnsupportedScript) confidence = lower(confidence, Confidence.MEDIUM)
        if (asrConfidence < ASR_MEDIUM) confidence = lower(confidence, Confidence.MEDIUM)
        if (asrConfidence < ASR_LOW) confidence = lower(confidence, Confidence.LOW)
        return confidence
    }

    private fun lower(current: Confidence, cap: Confidence): Confidence =
        if (rank(current) <= rank(cap)) current else cap

    private fun rank(value: Confidence): Int = when (value) {
        Confidence.LOW -> 0
        Confidence.MEDIUM -> 1
        Confidence.HIGH -> 2
    }

    private fun ordered(stages: Set<StageId>): List<StageId> =
        StageId.playbookOrder.filter { it in stages }

    private fun actionsFor(
        label: RiskLabel,
        verifiedAsk: Boolean,
        askTypes: Set<AskType>,
    ): List<String> = when {
        label == RiskLabel.HIGH_RISK -> buildList {
            add(ACTION_HIGH_STOP)
            add(ACTION_HIGH_VERIFY)
            if (AskType.MONEY_TRANSFER in askTypes) add(ACTION_HIGH_REPORT_MONEY)
        }
        label == RiskLabel.SUSPICIOUS && verifiedAsk -> listOf(ACTION_CAUTION_ASK, ACTION_HIGH_VERIFY)
        label == RiskLabel.CAUTION && verifiedAsk -> listOf(ACTION_CAUTION_ASK, ACTION_HIGH_VERIFY)
        else -> listOf(ACTION_LOW_WATCH)
    }

    private fun limitationForScripts(names: List<String>): String =
        LIMIT_UNSUPPORTED_SCRIPT_PREFIX + names.sorted().joinToString(", ")

    /**
     * Which scripts the input actually uses, counted with `java.lang.Character.UnicodeScript`.
     *
     * Latin and Devanagari are the two scripts the local rules can read (Roman Hinglish and Hindi).
     * Every other letter is counted as unsupported and named for the UI copy, because the app must
     * never claim "safe" for text it cannot read. Digits, emoji and punctuation are not letters and
     * take no part in the majority test.
     */
    private fun scanScripts(input: String): ScriptScan {
        var supported = 0
        var unsupported = 0
        val names = LinkedHashSet<String>(2)
        var i = 0
        while (i < input.length) {
            val cp = Character.codePointAt(input, i)
            i += Character.charCount(cp)
            // Marks belong to the script of the letters they decorate: counting `க` but not `்`
            // would let a Tamil sentence look shorter than the Latin URL sitting next to it.
            val type = Character.getType(cp)
            val letterish = Character.isLetter(cp) ||
                type == Character.NON_SPACING_MARK.toInt() ||
                type == Character.COMBINING_SPACING_MARK.toInt() ||
                type == Character.ENCLOSING_MARK.toInt()
            if (!letterish) continue
            when (val script = Character.UnicodeScript.of(cp)) {
                Character.UnicodeScript.LATIN, Character.UnicodeScript.DEVANAGARI -> supported++
                else -> {
                    unsupported++
                    names.add(prettyScriptName(script.name))
                }
            }
        }
        return ScriptScan(supported, unsupported, names.toList())
    }

    /** `TAMIL` -> `Tamil`, so the limitation reads "Is bhasha ko ... : Tamil". */
    private fun prettyScriptName(raw: String): String =
        if (raw.isEmpty()) raw else raw[0].uppercaseChar() + raw.substring(1).lowercase()

    /** Supported/unsupported letter counts plus the unsupported script names, for the limitation copy. */
    private class ScriptScan(
        val supportedLetters: Int,
        val unsupportedLetters: Int,
        val unsupportedNames: List<String>,
    )

    /** Session text: the rolling window, with [raw] appended when the caller did not append it. */
    private fun sessionText(raw: String, state: CallSessionState): String {
        val window = state.transcript
        return when {
            raw.isBlank() -> window
            window.isEmpty() -> raw
            window.endsWith(raw) -> window
            else -> window + " " + raw
        }
    }

    /** Deterministic id: a hash of the analysed text, not a clock or a counter. */
    private fun analysisId(analyzed: String, inputType: InputType): String {
        var hash = FNV_OFFSET
        for (ch in analyzed) {
            hash = hash xor (ch.code.toLong() and 0xFFFF)
            hash *= FNV_PRIME
        }
        val sensitivityTag = sensitivity.name.lowercase()
        return "local-${java.lang.Long.toHexString(hash)}-${inputType.wire}-$sensitivityTag"
    }

    companion object {
        /** Module id for evidence this package emits itself. */
        const val MODULE: String = "local_fusion"

        /** Below this many letter/digit characters the input is "too little to judge". */
        const val MIN_ANALYSABLE_CHARS: Int = 3

        /** Below this many letter/digit characters an otherwise readable input cannot be `HIGH`. */
        const val MIN_CHARS_FOR_HIGH_CONFIDENCE: Int = 12

        /** ASR quality below this is `LOW` confidence; below [ASR_MEDIUM] it is capped at `MEDIUM`. */
        const val ASR_LOW: Double = 0.5
        const val ASR_MEDIUM: Double = 0.85

        private const val HIGH_FLOOR = 70
        private const val NO_ASK_CEILING = 24
        private const val ESCALATION_STEP = 5
        private const val CRITICAL_CORROBORATIONS = 2
        private const val MAX_URL_EVIDENCE = 2

        /** Stage weights, documented in the class KDoc. */
        private val WEIGHT: Map<StageId, Int> = mapOf(
            StageId.AUTHORITY to 10,
            StageId.THREAT_OR_REWARD to 22,
            StageId.URGENCY to 8,
            StageId.SECRECY to 14,
            StageId.ASK to 30,
        )
        private const val WEIGHT_INJECTION = 15

        /** Fixed Hinglish limitation vocabulary. */
        const val LIMIT_TRUNCATED: String = "Message bahut lamba tha, sirf pehla hissa check hua"
        const val LIMIT_NO_TEXT: String = "Check karne layak text nahi mila"
        const val LIMIT_TOO_LITTLE: String = "Text bahut chhota tha, poora check mushkil tha"
        const val LIMIT_UNSUPPORTED_SCRIPT_PREFIX: String = "Is bhasha ko abhi poori tarah check nahi kar sakte: "
        const val LIMIT_LOW_ASR: String = "Awaaz saaf nahi thi, isliye result kam bharosemand hai"
        const val LIMIT_NO_EVIDENCE: String = "Koi scam pattern nahi mila, lekin iska matlab 100% safe nahi hai"
        const val LIMIT_WINDOW_DROPPED: String =
            "Call ka purana hissa window se nikal gaya, sirf naye shabd poore check hue"

        /** Fixed Hinglish action vocabulary. */
        const val ACTION_HIGH_STOP: String = "Abhi ruk jaayein: OTP, PIN, password ya paise mat dein."
        const val ACTION_HIGH_VERIFY: String =
            "Bank ya police ko official number par khud call back karke verify karein."
        const val ACTION_HIGH_REPORT_MONEY: String =
            "Paise bhej diye hon to turant 1930 par report karein."
        const val ACTION_CAUTION_ASK: String =
            "Is message mein code ya paise maange gaye hain. Kisi ko OTP, PIN ya paise na dein."
        const val ACTION_LOW_WATCH: String =
            "Yeh message akele khatra nahi dikhata. Anjaan link par OTP ya paise phir bhi na dein."
        const val ACTION_UNABLE_HOLD: String =
            "Hum ise poora check nahi kar sake. OTP, PIN ya paise kisi ko na dein."
        const val ACTION_UNABLE_RETRY: String =
            "Text ko Roman ya Devanagari Hinglish mein dobara likh kar check karein."

        private val URL_PATTERN = Regex("""(?i)\b(?:https?://|www\.)[^\s<>"']{3,}""")

        private const val FNV_OFFSET = -3750763034362895579L // 0xcbf29ce484222325
        private const val FNV_PRIME = 1099511628211L
    }
}
