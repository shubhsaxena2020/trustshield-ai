package ai.trustshield.android.ui.call

import ai.trustshield.android.core.model.Confidence
import ai.trustshield.android.core.model.RiskLabel
import ai.trustshield.android.core.model.StageId
import ai.trustshield.android.core.model.TextNormalizer
import ai.trustshield.android.core.normalize.HinglishNormalizer
import ai.trustshield.android.core.rules.DigitalArrestSignals
import ai.trustshield.android.core.score.CallSessionState
import ai.trustshield.android.core.score.LocalScorer
import ai.trustshield.android.core.score.ScoredResult
import ai.trustshield.android.ui.theme.ToneCopy

/*
 * P3-11 (issue #90): Call Shield "Demo script mode". Issue #119 defect A: the meter must climb.
 *
 * The hero demo must work on any phone, including one with no Hindi ASR and with the microphone
 * denied, and it must stay honest: every position the meter shows is produced by the deterministic
 * A05 engine (`LocalScorer` + `CallSessionState`) from the words on screen. Nothing in this file
 * computes a risk value, picks a colour, animates a needle or scores a fake probability. There is
 * no `android.*` import, no Compose layout, no clock, no randomness, no permission and no audio: the
 * demo is a typed / tap-through script, and the copy says so. The one `androidx`-free theme type it
 * uses is [ToneCopy], a plain data class of two strings.
 *
 * ## What the demo measures today (probe first, then design)
 *
 * The script is fed line by line into one [CallSessionState]; after each line
 * `LocalScorer.analyzeTranscriptScored` re-reads the whole rolling transcript, so the run is
 * cumulative exactly like a real call. Measured on `android-finals` + this change, with
 * [LocalScorer.Sensitivity.STANDARD] and typed input (`asrConfidence = 1.0`):
 *
 * | # | line | score | label | confidence | stages | escalate | position |
 * |---|---|---|---|---|---|---|---|
 * | 0 | report §6 line 1 (authority) | 10 | LOW_RISK | HIGH | AUTH | no | LOW |
 * | 1 | report §6 line 2 (drugs parcel) + §6 marker | 44 | CAUTION | HIGH | AUTH, THREAT | no | MEDIUM |
 * | 2 | report §6 line 3 (confidential + isolation) | 52 | SUSPICIOUS | HIGH | +SECRECY | no | HIGH |
 * | 3 | report §6 line 4 (₹50,000 RBI ask) | 75 | HIGH_RISK | HIGH | +ASK | yes | CRITICAL |
 *
 * Four lines, four different engine positions, in the report's own order: LOW -> MEDIUM -> HIGH ->
 * CRITICAL. The numbers are A05's, not this file's, and `CallDemoScriptTest` pins them so a
 * rule change that flattens the ladder fails the build.
 *
 * ## How the ladder was made reachable (issue #119 defect A)
 *
 * Before this change every no-ask transcript was hard-clamped to `NO_ASK_CEILING = 24`, so the
 * report's four lines measured **10 / 24 / 24 / 75**: two rungs collapsed into `LOW_RISK` and the
 * fourth jumped straight to the escalated floor. `MEDIUM` (25-49) and non-escalated `HIGH`
 * (50-69) were unreachable from any ordering of the report's lines.
 *
 * Two things changed, both deterministic and both in one documented place each:
 *
 *  1. `core/rules/DigitalArrestSignals.kt` (new) adds the **separate strong deterministic signal**
 *     BEHAVIOR_SPEC reserves for exactly this case: report §6's digital-arrest phrase markers
 *     ("digital arrest", "video call on rakhiye", "camera band mat karna", "ghar walon ko mat
 *     batana", "kamre mein akele raho", a fake "Supreme Court ka order" / "arrest warrant", "FD tod
 *     kar transfer karo"). Each hit is `STRONG` evidence with an exact, verified quote.
 *  2. `LocalScorer.noAskBand()` replaces the blanket clamp with a documented band ladder: no-ask
 *     stays `<= 24` unless that strong signal is present, and then `>= 2` other independent stages
 *     lift it into `CAUTION` (25-49) and `>= 3` into `SUSPICIOUS` (50-69). A no-ask transcript still
 *     can never reach `HIGH_RISK` or set the escalation - only the verified money ask does that.
 *
 * ## The one place the script is not verbatim (stated plainly)
 *
 * Lines 1, 3 and 4 are report §6's lines verbatim. Line 2 keeps the report's drugs-parcel threat and
 * appends one marker **from the same report** (§6 voice rules, "Digital arrest ki baat": *"Aap
 * digital arrest mein hain"*), because the threat line alone carries no strong non-ask signal and
 * would stay at the watch ceiling - i.e. the report's own second rung would be unreachable. Nothing
 * else is inserted: no invented stage, no victim line, no remote-access line, no urgency padding. If
 * a future rule change makes the rung reachable without the marker, delete the marker rather than
 * the test.
 *
 * ## What the UI gets per step
 *
 * [DemoStep] carries the engine reading (score / label / confidence / position / stages / verified
 * quotes), five [DemoStageChip]s with the exact quoted line behind each lit stage, the full-screen
 * alert copy that is non-null only at [DemoRiskPosition.CRITICAL], and [DemoStep.haptics], true only
 * on the transition **into** CRITICAL. `positionFor()` stays the single mapping; the screen never
 * derives a position from a score of its own.
 *
 * ## Privacy
 *
 * The transcript lives in one in-memory [CallSessionState] owned by [CallDemoRunner]. Nothing is
 * written to disk or to preferences, no audio exists, no microphone or permission is touched, and
 * [CallDemoRunner.reset] clears the session so the transcript is empty again. Every `toString()` in
 * this file is redacted: it reports counts and verdicts, never the words.
 */

// -------------------------------------------------------------------------------------------------
// Script vocabulary
// -------------------------------------------------------------------------------------------------

/**
 * Who says the line. This is a reading aid for the presenter (and the host screen); the engine sees
 * one transcript, exactly like a microphone would.
 *
 * [SYSTEM] is reserved for host-screen directions (a readiness or "awaaz saaf nahi" note). The
 * shipped [CallDemoScript.lines] contains no [SYSTEM] line, because such a note is not speech and
 * must never enter the transcript.
 */
enum class DemoSpeaker { SCAMMER, VICTIM, SYSTEM }

/**
 * One line of the demo script.
 *
 * @property speaker who says it (presenter cue only).
 * @property text the exact words that go into the transcript. It is a demo script, not a recording.
 * @property noteHinglish a one-line presenter note explaining what the line is supposed to teach.
 */
data class DemoLine(
    val speaker: DemoSpeaker,
    val text: String,
    val noteHinglish: String,
) {
    /** Redacted: a demo line holds no personal data, but the transcript never belongs in a log. */
    override fun toString(): String =
        "DemoLine(speaker=$speaker, text=<redacted:${text.length} chars>, note=$noteHinglish)"
}

/**
 * The tap-through demo script: report §6's four digital-arrest lines, in their original order, with
 * one report-sourced marker appended to line 2 (see the file KDoc for why, and for the measured
 * ladder).
 *
 * Pure data. No score, no position and no claim lives here - [CallDemoRunner] asks A05 for those.
 */
object CallDemoScript {

    /** The report §6 demo lines, in play order, with the one documented addition to line 2. */
    const val LINE_1_AUTHORITY: String =
        "Hello, main Mumbai Cyber Crime se Inspector Rathore bol raha hoon."

    /** Report §6 line 2 + the report's own "digital arrest" marker (see the file KDoc). */
    const val LINE_2_THREAT: String =
        "Aapke Aadhaar se bheje gaye parcel mein drugs mile hain. Aap digital arrest mein hain."

    /** Report §6 line 3, verbatim. */
    const val LINE_3_SECRECY: String =
        "Yeh confidential case hai, kisi ko mat batana, video call cut mat karna."

    /** Report §6 line 4, verbatim. */
    const val LINE_4_ASK: String =
        "Case clear karne ke liye ₹50,000 RBI verification account mein bhejo."

    /** The honest limits the demo screen must show next to the meter. Never optional. */
    val honestLimits: List<String> = listOf(
        "Yeh ek typed demo script hai, asli call record nahi hoti.",
        "Meter sirf is script ke deterministic rules se badalta hai.",
        "Deepfake awaaz check nahi hoti.",
        "Microphone ki zaroorat nahi: yeh script tap-through ya type karke chalti hai.",
        "Koi audio save nahi hota; transcript sirf memory mein rehta hai aur reset par clear ho jata hai.",
        "Score aur confidence do alag readings hain; meter kisi par bhi ilzaam nahi lagata.",
    )

    /**
     * The ladder, in play order: report §6's four lines with their measured engine positions.
     *
     * Keep this list append-only in spirit: the numbers in the file KDoc and in `CallDemoScriptTest`
     * describe exactly this sequence, so adding a rung means re-measuring both.
     */
    val lines: List<DemoLine> = listOf(
        DemoLine(
            speaker = DemoSpeaker.SCAMMER,
            text = LINE_1_AUTHORITY,
            noteHinglish = "Report §6 line 1: sanstha ka dawa. Authority akele LOW rehti hai - " +
                "kisi par ilzaam nahi lagta.",
        ),
        DemoLine(
            speaker = DemoSpeaker.SCAMMER,
            text = LINE_2_THREAT,
            noteHinglish = "Report §6 line 2: drugs-parcel ka darr, aur report ke apne voice-rule " +
                "marker ke saath. Do stage + strong signal = MEDIUM: full alert abhi hold hai.",
        ),
        DemoLine(
            speaker = DemoSpeaker.SCAMMER,
            text = LINE_3_SECRECY,
            noteHinglish = "Report §6 line 3: confidential case aur akela karna. Teesra stage " +
                "judta hai, meter HIGH tak jata hai - par maang abhi aayi nahi.",
        ),
        DemoLine(
            speaker = DemoSpeaker.SCAMMER,
            text = LINE_4_ASK,
            noteHinglish = "Report §6 line 4: RBI 'verification' account mein paise bhejne ki maang. " +
                "Verified money ask + corroboration = CRITICAL alert release; 1930 wali salah dikhegi.",
        ),
    )

    /** Line at [index], or null when out of range. Never wraps, never throws. */
    fun lineAt(index: Int): DemoLine? = lines.getOrNull(index)

    /** Number of script lines. */
    val size: Int get() = lines.size
}

// -------------------------------------------------------------------------------------------------
// Meter positions
// -------------------------------------------------------------------------------------------------

/**
 * The demo meter's four positions (report §6's "meter: Low / Medium / High / Critical").
 *
 * These are presentation positions derived from A05 output by [positionFor]; they are not risk
 * labels and not probabilities, and no position is ever asserted without an engine reading behind
 * it.
 *
 * @property labelHinglish / [labelEnglish] short meter captions.
 * @property explanationHinglish / [explanationEnglish] one honest line for the presenter.
 */
enum class DemoRiskPosition(
    val labelHinglish: String,
    val labelEnglish: String,
    val labelHindi: String,
    val explanationHinglish: String,
    val explanationEnglish: String,
    val explanationHindi: String,
) {
    LOW(
        labelHinglish = "Kam",
        labelEnglish = "Low",
        labelHindi = "कम",
        explanationHinglish = "A05 ko abhi koi verified maang nahi mili aur koi strong signal " +
            "akele khatra nahi banata. Meter neeche hai, lekin yeh 'sab theek hai' ka " +
            "certificate nahi hai.",
        explanationEnglish = "A05 has no verified ask and no single strong signal. The meter sits " +
            "low, which is not a certificate that everything is fine.",
        explanationHindi = "A05 को अभी कोई सत्यापित मांग नहीं मिली है। कोई एक मजबूत संकेत अकेले खतरा नहीं बनाता। " +
            "मीटर नीचे है, लेकिन यह सब ठीक होने का प्रमाण नहीं है।",
    ),
    MEDIUM(
        labelHinglish = "Dhyan dein",
        labelEnglish = "Medium",
        labelHindi = "सावधानी",
        explanationHinglish = "Pattern ke kai stage mil chuke hain, lekin paise ya code ki maang " +
            "abhi nahi aayi - isliye full alert hold hai.",
        explanationEnglish = "Several pattern stages have matched, but no request for money or a " +
            "code has been made yet, so the full alert is held.",
        explanationHindi = "पैटर्न के कई चरण मिले हैं, लेकिन अभी पैसे या कोड की मांग नहीं हुई है, " +
            "इसलिए पूरा अलर्ट जारी नहीं किया गया।",
    ),
    HIGH(
        labelHinglish = "Zyada khatra",
        labelEnglish = "High",
        labelHindi = "ज़्यादा जोखिम",
        explanationHinglish = "Digital arrest ka poora pattern khada hai, sirf maang baaki hai. " +
            "Engine ko isi liye suspicious reading mili, release abhi nahi hua.",
        explanationEnglish = "The full digital-arrest pattern is in place and only the demand is " +
            "missing, so the engine reads suspicious without releasing.",
        explanationHindi = "डिजिटल गिरफ़्तारी का पूरा पैटर्न मौजूद है, केवल मांग बाकी है। " +
            "इसलिए इंजन इसे संदिग्ध मानता है, लेकिन अलर्ट अभी जारी नहीं करता।",
    ),
    CRITICAL(
        labelHinglish = "Turant rukein",
        labelEnglish = "Critical",
        labelHindi = "तुरंत रुकें",
        explanationHinglish = "High-risk money ya credential maang, do ya zyada corroboration ke " +
            "saath - alert release hua. Call kaat kar official number se verify karein.",
        explanationEnglish = "A high-risk money or credential ask with two or more corroborations " +
            "- the alert is released. End the call and verify through an official number.",
        explanationHindi = "पैसे या गोपनीय जानकारी की ज़्यादा जोखिम वाली मांग के साथ दो या अधिक पुष्ट संकेत मिले हैं। " +
            "कॉल समाप्त करें और आधिकारिक नंबर से पुष्टि करें।",
    ),
    ;

    /** The engine-neutral caption for the requested UI language. */
    fun label(language: CallLanguage): String = when (language) {
        CallLanguage.HINGLISH -> labelHinglish
        CallLanguage.ENGLISH -> labelEnglish
        CallLanguage.HINDI -> labelHindi
    }

    fun label(english: Boolean): String = if (english) labelEnglish else labelHinglish

    /** The engine-neutral explanation for the requested UI language. */
    fun explanation(language: CallLanguage): String = when (language) {
        CallLanguage.HINGLISH -> explanationHinglish
        CallLanguage.ENGLISH -> explanationEnglish
        CallLanguage.HINDI -> explanationHindi
    }

    fun explanation(english: Boolean): String = if (english) explanationEnglish else explanationHinglish
}

/**
 * The one and only mapping from an A05 reading to a demo meter position.
 *
 * Exact rule, in order:
 *
 *  1. `escalateCritical == true` -> [DemoRiskPosition.CRITICAL]. A05 sets that flag only for a
 *     verified directional ask with `>= 2` independent corroborations, a score on the 70 floor and
 *     confidence that is not `LOW`; it is the UI escalation switch, not a risk label.
 *  2. else `label == HIGH_RISK || label == SUSPICIOUS` -> [DemoRiskPosition.HIGH]. On this engine
 *     `SUSPICIOUS` (50-69) is exactly the no-ask strong-signal rung, and `HIGH_RISK` without the
 *     release is a confidence hold.
 *  3. else `label == CAUTION` -> [DemoRiskPosition.MEDIUM] (a verified ask with `0..1`
 *     corroborations, or the 25-49 no-ask strong-signal rung).
 *  4. else -> [DemoRiskPosition.LOW]. That covers `LOW_RISK` (<= 24) **and** `UNABLE_TO_VERIFY`: text
 *     the engine could not read never moves the meter up; the screen shows its own Unable-to-Verify
 *     copy for it. It never means "clean".
 *
 * Nothing else is consulted: no score is recomputed, no threshold is invented here, and a position
 * can never be higher than the label the engine chose.
 */
fun positionFor(scored: ScoredResult): DemoRiskPosition {
    if (scored.escalateCritical) return DemoRiskPosition.CRITICAL
    return when (scored.result.risk.label) {
        RiskLabel.HIGH_RISK, RiskLabel.SUSPICIOUS -> DemoRiskPosition.HIGH
        RiskLabel.CAUTION -> DemoRiskPosition.MEDIUM
        RiskLabel.LOW_RISK, RiskLabel.UNABLE_TO_VERIFY -> DemoRiskPosition.LOW
    }
}

/** The chip vocabulary for one demo step: the five playbook stages, in render order. */
fun demoCallStage(stage: StageId): CallStage = when (stage) {
    StageId.AUTHORITY -> CallStage.AUTHORITY
    StageId.THREAT_OR_REWARD -> CallStage.THREAT_REWARD
    StageId.URGENCY -> CallStage.URGENCY
    StageId.SECRECY -> CallStage.SECRECY_ISOLATION
    StageId.ASK -> CallStage.ASK
}

// -------------------------------------------------------------------------------------------------
// One demo step
// -------------------------------------------------------------------------------------------------

/**
 * One chip of the demo meter.
 *
 * @property stage the playbook stage this chip draws, always one of the five in [CallStage.ordered].
 * @property lit true when A05 holds this stage for the transcript at this step.
 * @property quotes the exact, verified lines behind the stage, newest-last, in engine order. A lit
 *   chip with no live quote means the stage was detected earlier and its words have left the rolling
 *   window; the chip stays lit (a detected stage is never lost) and the UI must not invent a quote.
 */
data class DemoStageChip(
    val stage: CallStage,
    val lit: Boolean,
    val quotes: List<String>,
)

/**
 * One executed demo line and the engine reading it produced.
 *
 * Every field except [line] and [chips] is copied straight out of A05; [quotes] and the chip quotes
 * are filtered to quotes that still resolve to an exact span in the transcript that was analysed, so
 * the UI can highlight them.
 *
 * @property index position of this step in the run, starting at 0.
 * @property line the speaker + words that were fed in.
 * @property score A05's 0..100 score, verbatim. Never recomputed here.
 * @property label A05's risk band for that score.
 * @property confidence A05's coverage reading, deliberately separate from [score].
 * @property position the meter position via [positionFor].
 * @property stages the playbook stages A05 still holds for the transcript at this point, in
 *   playbook order. A stage that lights up is never lost (the session remembers it).
 * @property quotes exact, verified evidence quotes (substrings of the transcript at this step).
 * @property escalated A05's `escalateCritical` for this step.
 * @property heldNote non-null when A05 saw a verified ask but withheld the release.
 * @property chips exactly five stage chips in render order, with the exact quoted line behind each.
 * @property alert the full-screen alert copy, non-null **only** at [DemoRiskPosition.CRITICAL].
 *   Hinglish is the default; [ToneCopy.english] is the toggle. Nothing else may be rendered as the
 *   alarm text.
 * @property haptics true only on the step where the meter **enters** CRITICAL, so a host fires one
 *   vibration per escalation and never on CRITICAL -> CRITICAL.
 */
data class DemoStep(
    val index: Int,
    val line: DemoLine,
    val score: Int,
    val label: RiskLabel,
    val confidence: Confidence,
    val position: DemoRiskPosition,
    val stages: List<StageId>,
    val quotes: List<String>,
    val escalated: Boolean,
    val heldNote: String?,
    val chips: List<DemoStageChip> = emptyList(),
    val alert: ToneCopy? = null,
    val haptics: Boolean = false,
) {
    /** The alert text for the requested language, or null when this step is not CRITICAL. */
    fun alertText(language: CallLanguage): String? = alert?.forLanguage(language)
    fun alertText(english: Boolean): String? = alert?.let { if (english) it.english else it.hinglish }

    /** Redacted: verdicts and counts only, never the words and never a quote. */
    override fun toString(): String =
        "DemoStep(index=$index, speaker=${line.speaker}, score=$score, label=$label, " +
            "confidence=$confidence, position=$position, stages=$stages, " +
            "quotes=${quotes.size} verified, escalated=$escalated, held=${heldNote != null}, " +
            "litChips=${chips.count { it.lit }}, alert=${alert != null}, haptics=$haptics)"

    companion object {
        /** Shown when a verified ask is real but corroboration is short of the release bar. */
        const val HELD_ASK_NOTE: String =
            "Maang verified hai, lekin corroboration abhi kam hai - isliye full-screen alert release " +
                "nahi hua."

        /** The note attached to a line the presenter typed instead of tapping. */
        const val TYPED_LINE_NOTE: String = "Presenter ne yeh line type ki (script se nahi)."
    }
}

// -------------------------------------------------------------------------------------------------
// Progress - a pure value that can move itself
// -------------------------------------------------------------------------------------------------

/**
 * Immutable demo progress: the steps executed so far, where the cursor is, and the meter position.
 *
 * It is a value, not a controller: [advance], [back], [reset] and [type] each replay the executed
 * lines through a fresh [CallSessionState] + [LocalScorer] and return a new value, so a host can
 * hold it in Compose state without any hidden mutation. The replay is what makes "back" honest: the
 * meter drops because the engine was re-run on fewer lines, not because a number was dialled down.
 *
 * @property steps executed steps, in order.
 * @property currentIndex index of the last executed step (-1 before the first line).
 * @property position meter position of the last step, [DemoRiskPosition.LOW] before the first.
 *   An empty demo is not a verdict; the screen must show its own "demo start" copy rather than a
 *   reading.
 * @property finished true once every script line has been fed.
 * @property executed every line fed so far, scripted and typed, in order (the replay input).
 * @property scriptCursor how many script lines have been consumed; typed lines do not move it.
 * @property sensitivity the A05 preset this run re-plays with.
 * @property normalizer the normalizer this run re-plays with (kept so the value stays pure).
 */
data class DemoProgress(
    val steps: List<DemoStep>,
    val currentIndex: Int,
    val position: DemoRiskPosition,
    val finished: Boolean,
    val executed: List<DemoLine> = emptyList(),
    val scriptCursor: Int = 0,
    val sensitivity: LocalScorer.Sensitivity = LocalScorer.Sensitivity.STANDARD,
    val normalizer: TextNormalizer = HinglishNormalizer(),
) {

    /** The reading the screen is showing right now, or null before the first line. */
    val current: DemoStep? get() = steps.lastOrNull()

    /** Feed the next script line. No-op once [finished]. */
    fun advance(): DemoProgress =
        advanceInto(CallSessionState(), LocalScorer(normalizer, sensitivity = sensitivity))

    /** Drop the last executed line and re-derive the meter from what is left. */
    fun back(): DemoProgress =
        backInto(CallSessionState(), LocalScorer(normalizer, sensitivity = sensitivity))

    /** Forget everything: no steps, no transcript, meter back at the bottom. */
    fun reset(): DemoProgress =
        resetInto(CallSessionState(), LocalScorer(normalizer, sensitivity = sensitivity))

    /**
     * Run an ARBITRARY typed line through the same engine, so a presenter can type instead of
     * tapping. Blank input is ignored rather than turned into a step.
     */
    fun type(text: String): DemoProgress =
        typeInto(CallSessionState(), LocalScorer(normalizer, sensitivity = sensitivity), text)

    /** Alias for [type], named for the UI action it backs. */
    fun submitTypedLine(text: String): DemoProgress = type(text)

    /** [advance] against a session/scorer the caller owns (the runner reuses its own pair). */
    internal fun advanceInto(state: CallSessionState, scorer: LocalScorer): DemoProgress {
        if (finished) return this
        val next = CallDemoScript.lineAt(scriptCursor) ?: return copy(finished = true)
        return rebuildInto(state, scorer, executed + next, scriptCursor + 1)
    }

    /** [back] against a caller-owned session/scorer. */
    internal fun backInto(state: CallSessionState, scorer: LocalScorer): DemoProgress {
        if (executed.isEmpty()) return rebuildInto(state, scorer, emptyList(), 0)
        val dropped = executed.last()
        val previousScript = scriptCursor - 1
        val cursor = if (previousScript >= 0 && CallDemoScript.lineAt(previousScript) == dropped) {
            previousScript
        } else {
            scriptCursor
        }
        return rebuildInto(state, scorer, executed.dropLast(1), cursor)
    }

    /** [reset] against a caller-owned session/scorer. */
    internal fun resetInto(state: CallSessionState, scorer: LocalScorer): DemoProgress =
        rebuildInto(state, scorer, emptyList(), 0)

    /** [type] against a caller-owned session/scorer. */
    internal fun typeInto(state: CallSessionState, scorer: LocalScorer, text: String): DemoProgress {
        if (text.isBlank()) return this
        val line = DemoLine(DemoSpeaker.SCAMMER, text, DemoStep.TYPED_LINE_NOTE)
        return rebuildInto(state, scorer, executed + line, scriptCursor)
    }

    /** Redacted: counts and the current position, never the transcript. */
    override fun toString(): String =
        "DemoProgress(steps=${steps.size}, currentIndex=$currentIndex, position=$position, " +
            "finished=$finished, scriptCursor=$scriptCursor)"

    companion object {
        /** The state before the first line: nothing read, nothing claimed. */
        fun start(
            normalizer: TextNormalizer = HinglishNormalizer(),
            sensitivity: LocalScorer.Sensitivity = LocalScorer.Sensitivity.STANDARD,
        ): DemoProgress = DemoProgress(
            steps = emptyList(),
            currentIndex = -1,
            position = DemoRiskPosition.LOW,
            finished = false,
            executed = emptyList(),
            scriptCursor = 0,
            sensitivity = sensitivity,
            normalizer = normalizer,
        )
    }
}

/**
 * Replay [lines] through the caller's [state] and [scorer] and return the resulting progress.
 *
 * The state is reset first, so the transcript after this call is exactly the joined [lines] - that
 * is what makes [CallDemoRunner.reset] and `back` leave nothing behind. Our own synthetic clock
 * stamps line `i` at `i * 4 s`; the demo never reads a wall clock.
 */
private fun DemoProgress.rebuildInto(
    state: CallSessionState,
    scorer: LocalScorer,
    lines: List<DemoLine>,
    scriptCursor: Int,
): DemoProgress {
    state.reset()
    val steps = ArrayList<DemoStep>(lines.size)
    var previousPosition = DemoRiskPosition.LOW
    lines.forEachIndexed { index, line ->
        state.appendUtterance(
            text = line.text,
            atMillis = index * MILLIS_PER_LINE,
            asrConfidence = TYPED_ASR_CONFIDENCE,
        )
        val scored = scorer.analyzeTranscriptScored(line.text, state)
        val step = stepOf(index, line, scored, state)
        // One pulse per transition INTO critical; a CRITICAL -> CRITICAL step never pulses.
        val haptics = step.position == DemoRiskPosition.CRITICAL &&
            previousPosition != DemoRiskPosition.CRITICAL
        steps.add(if (haptics) step.copy(haptics = true) else step)
        previousPosition = step.position
    }
    return DemoProgress(
        steps = steps,
        currentIndex = steps.size - 1,
        position = steps.lastOrNull()?.position ?: DemoRiskPosition.LOW,
        finished = scriptCursor >= CallDemoScript.size,
        executed = lines,
        scriptCursor = scriptCursor,
        sensitivity = this.sensitivity,
        normalizer = this.normalizer,
    )
}

/** Build one step from an A05 reading, keeping only quotes that still resolve in the transcript. */
private fun stepOf(
    index: Int,
    line: DemoLine,
    scored: ScoredResult,
    state: CallSessionState,
): DemoStep {
    val transcript = scored.analyzedText
    val quotes = (scored.result.evidence + scored.sessionQuotes)
        .filter { it.span != null && it.quote != null && it.quoteVerifiesAgainst(transcript) }
        .mapNotNull { it.quote }
        .distinct()
    val position = positionFor(scored)
    // Per-chip quotes come from the session's stage memory, which is where A05 stores the exact
    // words it accepted for each stage; a quote is kept only if it still verifies against the
    // transcript that was analysed.
    val liveQuotes = state.stageEvidence(state.nowMillis)
    val chips = CallStage.ordered.map { callStage ->
        val stageId = STAGE_FOR_CALL.getValue(callStage)
        val lit = stageId in scored.result.stages
        val stageQuotes = if (!lit) {
            emptyList()
        } else {
            liveQuotes[stageId].orEmpty()
                .filter { it.span != null && it.quote != null && it.quoteVerifiesAgainst(transcript) }
                .mapNotNull { it.quote }
                .distinct()
        }
        DemoStageChip(stage = callStage, lit = lit, quotes = stageQuotes)
    }
    val held = if (scored.verifiedAsk && !scored.escalateCritical) DemoStep.HELD_ASK_NOTE else null
    return DemoStep(
        index = index,
        line = line,
        score = scored.result.risk.score,
        label = scored.result.risk.label,
        confidence = scored.result.risk.confidence,
        position = position,
        stages = scored.result.stages,
        quotes = quotes,
        escalated = scored.escalateCritical,
        heldNote = held,
        chips = chips,
        alert = if (position == DemoRiskPosition.CRITICAL) CallShieldCopy.digitalArrestAlert else null,
    )
}

/** The five chips mapped back to the engine's stage ids (the inverse of [demoCallStage]). */
private val STAGE_FOR_CALL: Map<CallStage, StageId> = CallStage.ordered.associateWith { callStage ->
    when (callStage) {
        CallStage.AUTHORITY -> StageId.AUTHORITY
        CallStage.THREAT_REWARD -> StageId.THREAT_OR_REWARD
        CallStage.URGENCY -> StageId.URGENCY
        CallStage.SECRECY_ISOLATION -> StageId.SECRECY
        CallStage.ASK -> StageId.ASK
    }
}

/** Synthetic per-line interval for the demo clock. Deterministic and well inside the stage TTL. */
private const val MILLIS_PER_LINE: Long = 4_000L

/** Typed demo lines are not recognition output, so they are treated as perfectly understood. */
private const val TYPED_ASR_CONFIDENCE: Double = 1.0

// -------------------------------------------------------------------------------------------------
// Runner
// -------------------------------------------------------------------------------------------------

/**
 * Drives the demo script through the real A05 engine. No microphone, no permission, no audio, no
 * consent gate: this is a script, and the honesty copy says so.
 *
 * It owns one [CallSessionState] and one [LocalScorer]; every public call delegates to the pure
 * [DemoProgress] transitions with that pair, so the transcript exposed by [transcript] is always the
 * one A05 actually analysed and [reset] empties it. Deterministic: the same script in, the same
 * scores and positions out, every time.
 */
class CallDemoRunner(
    private val normalizer: TextNormalizer = HinglishNormalizer(),
    private val sensitivity: LocalScorer.Sensitivity = LocalScorer.Sensitivity.STANDARD,
) {

    private val state = CallSessionState()
    private val scorer = LocalScorer(normalizer, sensitivity = sensitivity)
    private var current: DemoProgress = DemoProgress.start(normalizer, sensitivity)

    /** The current progress value. Holding it does not move the demo. */
    fun progress(): DemoProgress = current

    /** Execute the next script line and return the new progress. */
    fun advance(): DemoProgress = move(current.advanceInto(state, scorer))

    /** Step the demo back one line (re-derived by the engine, so the meter can drop). */
    fun back(): DemoProgress = move(current.backInto(state, scorer))

    /** Stop: the transcript is cleared and the meter returns to the first step. */
    fun reset(): DemoProgress = move(current.resetInto(state, scorer))

    /** Type a line instead of tapping: it goes through the same engine as the script. */
    fun type(text: String): DemoProgress = move(current.typeInto(state, scorer, text))

    /** Alias for [type]. */
    fun submitTypedLine(text: String): DemoProgress = type(text)

    /**
     * The rolling transcript A05 analysed for the current progress, in memory only. Empty after
     * [reset]. Exposed for the host (and for tests that re-check quote spans); it must not be
     * logged or persisted.
     */
    fun transcript(): String = state.transcript

    private fun move(next: DemoProgress): DemoProgress {
        current = next
        return next
    }
}
