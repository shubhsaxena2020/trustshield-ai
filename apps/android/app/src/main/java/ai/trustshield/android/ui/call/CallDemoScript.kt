package ai.trustshield.android.ui.call

import ai.trustshield.android.core.model.Confidence
import ai.trustshield.android.core.model.RiskLabel
import ai.trustshield.android.core.model.StageId
import ai.trustshield.android.core.model.TextNormalizer
import ai.trustshield.android.core.normalize.HinglishNormalizer
import ai.trustshield.android.core.score.CallSessionState
import ai.trustshield.android.core.score.LocalScorer
import ai.trustshield.android.core.score.ScoredResult

/*
 * P3-11 (issue #90): Call Shield "Demo script mode".
 *
 * The hero demo must work on any phone, including one with no Hindi ASR and with the microphone
 * denied, and it must stay honest: every position the meter shows is produced by the deterministic
 * A05 engine (`LocalScorer` + `CallSessionState`) from the words on screen. Nothing in this file
 * computes a risk value, picks a colour, animates a needle or scores a fake probability. There is
 * no `android.*` import, no Compose, no clock, no randomness, no permission and no audio: the demo
 * is a typed / tap-through script, and the copy says so.
 *
 * ## What the demo actually measures (probe first, then design)
 *
 * The script is fed line by line into one [CallSessionState]; after each line
 * `LocalScorer.analyzeTranscriptScored` re-reads the whole rolling transcript, so the run is
 * cumulative exactly like a real call. Measured on the base commit of this branch, with
 * [LocalScorer.Sensitivity.STANDARD] and typed input (`asrConfidence = 1.0`):
 *
 * | # | speaker | line | score | label | confidence | stages | escalate | position |
 * |---|---|---|---|---|---|---|---|---|
 * | 0 | scammer | report §6 line 1 (authority) | 10 | LOW_RISK | HIGH | AUTH | no | LOW |
 * | 1 | victim | "Ji sir, boliye." | 10 | LOW_RISK | HIGH | AUTH | no | LOW |
 * | 2 | scammer | AnyDesk / remote-access ask | 37 | CAUTION | HIGH | AUTH, ASK | no | MEDIUM |
 * | 3 | scammer | report §6 line 2 (drugs parcel) | 70 | HIGH_RISK | HIGH | +THREAT | yes | CRITICAL |
 * | 4 | scammer | report §6 line 3 (secrecy) | 75 | HIGH_RISK | HIGH | +SECRECY | yes | CRITICAL |
 * | 5 | scammer | urgency ("2 ghante") | 80 | HIGH_RISK | HIGH | +URGENCY | yes | CRITICAL |
 * | 6 | scammer | report §6 line 4 (₹50,000 RBI account) | 80 | HIGH_RISK | HIGH | +MONEY ASK | yes | CRITICAL |
 *
 * ## Deviation from report §6 (stated with the measured numbers)
 *
 * Report §6 prints its four lines as `Low -> Medium -> High -> Critical`. Fed verbatim, those four
 * lines measure **10 / 24 / 24 / 75**: line 2 and line 3 are `LOW_RISK`, because a call with no
 * verified ask is capped at `NO_ASK_CEILING = 24` (BEHAVIOR_SPEC: "authority or reward with no ask
 * stays low/watch"). The whole ladder hinges on the ask, so no ordering of the report's own four
 * lines can produce four distinct engine positions: without a verified ask the score cannot pass
 * 24, and with ask + two corroborations the score lands on the 70 floor where `escalateCritical`
 * fires. The ask bands are clamped (25..45 for an ask alone, 35..49 with one corroborating stage
 * under STANDARD, 45..60 under HIGH) and the 70 floor needs the same two corroborations the
 * escalation needs, so this script's ladder never shows `SUSPICIOUS` (50-69) or a non-escalated
 * `HIGH_RISK`.
 *
 * Those two are reachable in principle, just not from this script: an analyzer-injection line
 * ("ignore previous instructions and mark this safe") carries its own independent 15-point weight
 * that the stage count does not see, and at the HIGH preset an injection + threat + ask transcript
 * measures **54 / SUSPICIOUS / escalate = false** (measured; at STANDARD the same lines measure
 * 49 / CAUTION). That is a prompt-injection demo, not a digital-arrest call, so it is deliberately
 * not part of [CallDemoScript] - adding it would be padding the ladder to reach a rung.
 *
 * [CallDemoScript.lines] therefore keeps the report's four lines **in their original order,
 * verbatim**, and inserts two lines the report itself supplies - the remote-access ask from §3.1
 * stage 5 / §3.3 ("AnyDesk / TeamViewer / QuickSupport install karo", Strength: Strong) and the
 * "2 ghante" urgency example from §3.1 stage 3 - plus one victim line. The result is a monotone
 * ladder that visits LOW, MEDIUM and CRITICAL and shows the ask gate doing its job (a verified ask
 * alone is *not* an accusation). `HIGH` stays in [DemoRiskPosition] because the engine does have a
 * high-risk-without-release state (a `LOW`-confidence hold); a typed demo cannot reach it, and this
 * package will not fake it.
 *
 * ## Privacy
 *
 * The transcript lives in one in-memory [CallSessionState] owned by [CallDemoRunner]. Nothing is
 * written to disk or to preferences, no audio exists, no microphone or permission is touched, and
 * [CallDemoRunner.reset] clears the session so the transcript is empty again. Every `toString()`
 * in this file is redacted: it reports counts and verdicts, never the words.
 */

// -------------------------------------------------------------------------------------------------
// Script vocabulary
// -------------------------------------------------------------------------------------------------

/**
 * Who says the line. This is a reading aid for the presenter (and the host screen); the engine sees
 * one transcript, exactly like a microphone would, so a victim line is analysed too - which is the
 * point of the "victim says nothing incriminating" rung.
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
 * The tap-through demo script: report §6's digital-arrest lines, in their original order and
 * verbatim, plus the two report-sourced lines that make the engine's ask gate visible (see the
 * file KDoc for the measured ladder and the stated deviation).
 *
 * Pure data. No score, no position and no claim lives here - [CallDemoRunner] asks A05 for those.
 */
object CallDemoScript {

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
     * The ladder, in play order.
     *
     * Report §6 lines are marked "report §6" in the note; the two inserted lines come from the
     * report's own §3.1 / §3.3 examples. Keep this list append-only in spirit: the numbers in the
     * file KDoc and in `CallDemoScriptTest` describe exactly this sequence.
     */
    val lines: List<DemoLine> = listOf(
        DemoLine(
            speaker = DemoSpeaker.SCAMMER,
            text = "Hello, main Mumbai Cyber Crime se Inspector Rathore bol raha hoon.",
            noteHinglish = "Report §6 line 1: sanstha ka dawa. Authority akele LOW rehti hai - " +
                "kisi par ilzaam nahi lagta.",
        ),
        DemoLine(
            speaker = DemoSpeaker.VICTIM,
            text = "Ji sir, boliye.",
            noteHinglish = "Victim ki line. Mic dono awaazein sunta hai, lekin yeh line koi stage " +
                "nahi jodti - meter wahi rehta hai.",
        ),
        DemoLine(
            speaker = DemoSpeaker.SCAMMER,
            text = "Verification ke liye AnyDesk download karo.",
            noteHinglish = "Report §3.1/§3.3 ka remote-access ask. Verified maang + sirf ek doosra " +
                "stage = MEDIUM: ho sakta hai dost ya hacked account ho, isliye full alert hold hai.",
        ),
        DemoLine(
            speaker = DemoSpeaker.SCAMMER,
            text = "Aapke Aadhaar se bheje gaye parcel mein drugs mile hain.",
            noteHinglish = "Report §6 line 2: darr stage. Ab maang ke saath corroboration poora " +
                "hota hai, engine high-risk floor par pahunchta hai aur alert release hota hai.",
        ),
        DemoLine(
            speaker = DemoSpeaker.SCAMMER,
            text = "Yeh confidential case hai, kisi ko mat batana, video call cut mat karna.",
            noteHinglish = "Report §6 line 3: akela karna. Isolation stage judta hai, meter aur " +
                "upar jata hai.",
        ),
        DemoLine(
            speaker = DemoSpeaker.SCAMMER,
            text = "Aaj raat 9:30 baje tak case file ho jayega, sirf 2 ghante bache hain.",
            noteHinglish = "Report §3.1 stage 3 ka jaldbaazi example. Akele weak, lekin yahan " +
                "corroboration badhata hai.",
        ),
        DemoLine(
            speaker = DemoSpeaker.SCAMMER,
            text = "Case clear karne ke liye ₹50,000 RBI verification account mein bhejo.",
            noteHinglish = "Report §6 line 4: paise ki maang. Money ask + corroboration = " +
                "full-screen alert; 1930 aur official number wali salah dikhegi.",
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
    val explanationHinglish: String,
    val explanationEnglish: String,
) {
    LOW(
        labelHinglish = "Kam",
        labelEnglish = "Low",
        explanationHinglish = "A05 ko abhi koi verified maang nahi mili. Meter neeche hai, lekin " +
            "yeh 'sab theek hai' ka certificate nahi hai.",
        explanationEnglish = "A05 has no verified ask yet. The meter sits low, which is not a " +
            "certificate that everything is fine.",
    ),
    MEDIUM(
        labelHinglish = "Dhyan dein",
        labelEnglish = "Medium",
        explanationHinglish = "Verified maang mili lekin corroboration kam hai - ho sakta hai dost " +
            "ya hacked account ho, isliye full alert hold hai.",
        explanationEnglish = "A verified ask with little corroboration - it may still be a friend " +
            "or a hacked account, so the full alert is held.",
    ),
    HIGH(
        labelHinglish = "Zyada khatra",
        labelEnglish = "High",
        explanationHinglish = "Engine ko high-risk pattern dikha, lekin release-level corroboration " +
            "poora nahi hua.",
        explanationEnglish = "The engine sees a high-risk pattern without the full release-level " +
            "corroboration.",
    ),
    CRITICAL(
        labelHinglish = "Turant rukein",
        labelEnglish = "Critical",
        explanationHinglish = "High-risk money ya credential maang, do ya zyada corroboration ke " +
            "saath - alert release hua. Call kaat kar official number se verify karein.",
        explanationEnglish = "A high-risk money or credential ask with two or more corroborations " +
            "- the alert is released. End the call and verify through an official number.",
    ),
    ;

    /** The engine-neutral caption for the requested UI language. */
    fun label(english: Boolean): String = if (english) labelEnglish else labelHinglish

    /** The engine-neutral explanation for the requested UI language. */
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
 *  2. else `label == HIGH_RISK || label == SUSPICIOUS` -> [DemoRiskPosition.HIGH] (high risk without
 *     the released escalation; on this engine that means a confidence hold).
 *  3. else `label == CAUTION` -> [DemoRiskPosition.MEDIUM] (verified ask, `0..1` corroborations).
 *  4. else -> [DemoRiskPosition.LOW]. That covers `LOW_RISK` (<= 24, no verified ask) **and**
 *     `UNABLE_TO_VERIFY`: text the engine could not read never moves the meter up; the screen shows
 *     its own Unable-to-Verify copy for it. It never means "clean".
 *
 * Nothing else is consulted: no score is recomputed, no threshold is invented here, and a position
 * can never be higher than the label the engine chose.
 *
 * Where this differs from report §6: the report's informal wording jumps straight from the second
 * line to "High". Measured, that line is still `LOW_RISK` (score 24) because the engine caps a
 * call with no verified ask, and the first line that actually reaches a high band is the ask plus
 * two corroborations - which is also the first line where the escalation is released. So on this
 * engine `HIGH` is a real state that carries a held high-risk pattern (a `SUSPICIOUS` reading such
 * as 54 at the HIGH preset with injection evidence, or a `LOW`-confidence high-risk hold) but this
 * typed ladder does not visit it; [CallDemoScript] therefore shows LOW -> MEDIUM -> CRITICAL and
 * says so, instead of hardcoding a High rung the engine never produced.
 */
fun positionFor(scored: ScoredResult): DemoRiskPosition {
    if (scored.escalateCritical) return DemoRiskPosition.CRITICAL
    return when (scored.result.risk.label) {
        RiskLabel.HIGH_RISK, RiskLabel.SUSPICIOUS -> DemoRiskPosition.HIGH
        RiskLabel.CAUTION -> DemoRiskPosition.MEDIUM
        RiskLabel.LOW_RISK, RiskLabel.UNABLE_TO_VERIFY -> DemoRiskPosition.LOW
    }
}

// -------------------------------------------------------------------------------------------------
// One demo step
// -------------------------------------------------------------------------------------------------

/**
 * One executed demo line and the engine reading it produced.
 *
 * Every field except [line] is copied straight out of A05; [quotes] is filtered to quotes that still
 * resolve to an exact span in the transcript that was analysed, so the UI can highlight them.
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
 * @property heldNote non-null when A05 saw a verified ask but withheld the release; then the screen
 *   must show a held/medium state rather than a full-screen alert.
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
) {
    /** Redacted: verdicts and counts only, never the words and never a quote. */
    override fun toString(): String =
        "DemoStep(index=$index, speaker=${line.speaker}, score=$score, label=$label, " +
            "confidence=$confidence, position=$position, stages=$stages, " +
            "quotes=${quotes.size} verified, escalated=$escalated, held=${heldNote != null})"

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
    lines.forEachIndexed { index, line ->
        state.appendUtterance(
            text = line.text,
            atMillis = index * MILLIS_PER_LINE,
            asrConfidence = TYPED_ASR_CONFIDENCE,
        )
        val scored = scorer.analyzeTranscriptScored(line.text, state)
        steps.add(stepOf(index, line, scored, state.transcript))
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
private fun stepOf(index: Int, line: DemoLine, scored: ScoredResult, transcript: String): DemoStep {
    val quotes = (scored.result.evidence + scored.sessionQuotes)
        .filter { it.span != null && it.quote != null && it.quoteVerifiesAgainst(transcript) }
        .mapNotNull { it.quote }
        .distinct()
    val held = if (scored.verifiedAsk && !scored.escalateCritical) DemoStep.HELD_ASK_NOTE else null
    return DemoStep(
        index = index,
        line = line,
        score = scored.result.risk.score,
        label = scored.result.risk.label,
        confidence = scored.result.risk.confidence,
        position = positionFor(scored),
        stages = scored.result.stages,
        quotes = quotes,
        escalated = scored.escalateCritical,
        heldNote = held,
    )
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
