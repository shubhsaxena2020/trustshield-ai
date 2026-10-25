/*
 * P3-10 - the live Call Shield host (issue #89).
 *
 * ## Why this file exists
 * B05 delivers utterances, A05 can score a transcript and remember stages, and C05 can render
 * chips and a risk meter - but nothing joined them, so the Call Shield screen had no engine behind
 * it. This class is that join, and it is a plain state holder: no `android.*`, no Compose, no
 * Context, no coroutines, no I/O, no network. Everything it needs is injected, which is why the
 * whole live path is exercised on the JVM without a device microphone.
 *
 * ## Who owns what (nothing here is re-decided)
 *  - **B04/P3-09 owns the gates.** [onStartRequested] asks [SpeechSessionCoordinator.prepare] and
 *    [SpeechSessionCoordinator.start]; consent, capability, language and the one-recogniser rule are
 *    decided there. This file never re-implements them, and a blocked start never reaches the handle
 *    factory, so no recogniser object can exist for a session that is not allowed to run.
 *  - **A05 owns every score, stage and quote.** [onUtterance] appends to [CallSessionState] and calls
 *    [LocalScorer.analyzeTranscriptScored]; the stage chips are built only from evidence whose span
 *    is present and whose quote still verifies against `ScoredResult.analyzedText`. No word spotting,
 *    no invented quote, no invented number. Stage memory (so an early stage stays lit after its
 *    words leave the rolling window) is A05's `CallSessionState`, not a flag kept here.
 *  - **C05 owns the presentation rules.** Chips, tone, confidence, the hold, the single haptic pulse
 *    and the stop/clear rules all come from [CallShieldReducer]; this file dispatches
 *    [CallEvent.Chunk] and copies lifecycle facts, never a severity.
 *
 * ## Honesty and privacy rules enforced here
 *  - Visible, user-started microphone analysis only. Nothing here claims capture of the other side
 *    of a cellular/VoIP call and nothing detects deepfakes.
 *  - [SessionPhase.LISTENING] is only ever published after the coordinator reports an active
 *    session; a handle that fails while opening the microphone leaves the host blocked instead.
 *  - Audio quality never raises risk: a chunk below [CallShieldReducer.LOW_ASR_CONFIDENCE] is handed
 *    to the reducer, which holds any alert it would have raised and reports "awaaz saaf nahi".
 *    Confidence and score stay separate fields, and the meter keeps the last *credited* reading so a
 *    murky chunk cannot push it up.
 *  - The words live in RAM only: [CallSessionState] holds the rolling transcript and
 *    [onStop]/[onCleared] wipe it, so nothing survives Stop or process death. No audio is stored or
 *    forwarded anywhere; the only audio sink in the chain is B05's handle, which discards the buffer.
 *  - Text that becomes printable (the rolling UI transcript and the chip quotes, which
 *    [CallUiState] renders and its `toString()` prints) is masked through [CallShieldRedaction], so
 *    an OTP, PIN, card, phone number or UPI ID cannot reach a log line, a crash report or a test
 *    assertion message. Masking is display-only: detection and scoring always run on the exact
 *    words, and masking never changes a stage, a score or a verdict.
 *  - There is no logging call and no network call in this file.
 */

package ai.trustshield.android.ui.call

import ai.trustshield.android.core.model.Confidence
import ai.trustshield.android.core.model.Evidence
import ai.trustshield.android.core.model.RiskLabel
import ai.trustshield.android.core.model.StageId
import ai.trustshield.android.core.normalize.HinglishNormalizer
import ai.trustshield.android.core.rules.AskType
import ai.trustshield.android.core.score.CallSessionState
import ai.trustshield.android.core.score.LocalScorer
import ai.trustshield.android.core.score.ScoredResult
import ai.trustshield.android.input.speech.ErrorReason
import ai.trustshield.android.input.speech.SpeechBlockCopy
import ai.trustshield.android.input.speech.SpeechBlockReason
import ai.trustshield.android.input.speech.SpeechCapabilityReport
import ai.trustshield.android.input.speech.SpeechConsent
import ai.trustshield.android.input.speech.SpeechEvent
import ai.trustshield.android.input.speech.SpeechSessionCoordinator
import ai.trustshield.android.input.speech.SpeechSessionPlan
import ai.trustshield.android.input.speech.SpeechStartOutcome
import ai.trustshield.android.input.speech.SpeechStatus
import ai.trustshield.android.input.speech.StartResult
import ai.trustshield.android.ui.theme.RiskTone

// -------------------------------------------------------------------------------------------------
// Public value types
// -------------------------------------------------------------------------------------------------

/**
 * What one [CallShieldHost.onStartRequested] did.
 *
 * [Listening] is only returned when the coordinator reports the session active, so the phase the
 * screen renders can never be a wish.
 */
sealed interface CallStartOutcome {

    /** The recogniser is running (or was already running) and the meter is live. */
    data class Listening(val plan: SpeechSessionPlan?) : CallStartOutcome

    /**
     * Nothing was started and no microphone is open.
     *
     * @param reason the [BlockReason] the screen explains instead of a dead Start control.
     * @param copy P3-09's honest one/two-line copy when the block came from its own gates.
     */
    data class Blocked(val reason: BlockReason, val copy: SpeechBlockCopy? = null) : CallStartOutcome
}

/**
 * Read-only diagnostics of the last reading the meter was allowed to show.
 *
 * Deliberately carries **no text**: not the transcript, not a quote, not the analysed string. Only
 * the numbers and enums A05 produced plus counts, so this object is safe to print.
 *
 * The two channels are different vocabularies on purpose and must never overwrite each other:
 * [CallUiState.tone] is the reducer's presentation gate (the documented rule hand-off), while
 * [score] and [label] here are A05's bands. On a call with no verified ask they can disagree - A05
 * caps such a call at its no-ask ceiling while the UI still says "dhyan dein" once three stages are
 * lit - and that disagreement is the honest reading, not a bug to smooth over.
 *
 * @param score A05's deterministic 0..100 score. Never computed or adjusted here.
 * @param label A05's risk band. Kept as a separate field from [confidence] on purpose.
 * @param confidence A05's coverage-derived confidence. A high score with low confidence is legal.
 * @param verifiedAsk true when a directional ask survived A05 verification.
 * @param escalateCritical A05's own escalation flag (verified directional ask plus corroboration).
 * @param stages the playbook stages A05 reported for this reading, in playbook order.
 */
data class CallShieldReading(
    val score: Int,
    val label: RiskLabel,
    val confidence: Confidence,
    val verifiedAsk: Boolean,
    val escalateCritical: Boolean,
    val stages: List<CallStage>,
)

// -------------------------------------------------------------------------------------------------
// The host
// -------------------------------------------------------------------------------------------------

/**
 * Non-Compose state holder that drives the existing [CallUiState] with the real deterministic engine.
 *
 * Threading: like B05 and A05, this is meant to be driven from one thread (the main thread, which is
 * where the recogniser calls back). No field here is guarded, and nothing spawns a thread.
 */
class CallShieldHost(
    private val coordinator: SpeechSessionCoordinator,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val scorer: LocalScorer = LocalScorer(HinglishNormalizer()),
    private val callState: CallSessionState = CallSessionState(),
) {

    /** The reducer's output. The screen renders this; no score or stage is added here. */
    var state: CallUiState = CallUiState()
        private set

    /** The last reading the meter was allowed to show; `null` before the first credited chunk. */
    var reading: CallShieldReading? = null
        private set

    /** The plan of the last allowed start (mode, disclosure, limitation lines), for the screen. */
    val currentPlan: SpeechSessionPlan?
        get() = coordinator.currentPlan

    /** A05's score of the last credited reading. Never derived from confidence or from ASR. */
    val score: Int?
        get() = reading?.score

    /** A05's confidence of the last credited reading, in A05's own vocabulary. */
    val engineConfidence: Confidence?
        get() = reading?.confidence

    /**
     * True only when the screen says LISTENING **and** the coordinator still holds an active
     * session. A dead recogniser can therefore never be presented as listening.
     */
    val isListening: Boolean
        get() = state.isListening && coordinator.isActive

    /** True when neither the rolling UI transcript nor A05's session retains any word. */
    val retainsNoTranscript: Boolean
        get() = state.transcript.isEmpty() && callState.transcript.isEmpty()

    private var consent: SpeechConsent = SpeechConsent.initial()
    private var report: SpeechCapabilityReport? = null
    private var startedAtMillis: Long? = null
    private var cleared: Boolean = false

    // ------------------------------------------------------------------ capability + consent

    /**
     * Maps B04's consent record and capability report (plus the Activity's microphone answer) onto
     * the state the screen already renders. Never opens a microphone and never probes the platform.
     *
     * Removing consent is a gate, not a preference: a session that is running is ended here rather
     * than left listening.
     */
    fun onCapability(consent: SpeechConsent, report: SpeechCapabilityReport, micPermission: MicPermission) {
        if (cleared) return
        this.consent = consent
        this.report = report
        state = state.copy(
            consent = if (consent.canStartSession) ConsentState.GIVEN else ConsentState.NOT_GIVEN,
            permission = micPermission,
            recognizer = recognizerFor(report),
            recognitionDisclosure = report.toCallRecognitionDisclosure(),
        )
        if (!consent.canStartSession &&
            (state.phase == SessionPhase.LISTENING || state.phase == SessionPhase.PAUSED)
        ) {
            endSession()
            state = state.copy(blocked = BlockReason.NEEDS_CONSENT)
            return
        }
        refreshBlocked()
    }

    /**
     * Screen tick: the active-session timer. Purely presentational, and it also ages the rolling
     * transcript window out, exactly as the reducer documents.
     */
    fun onTick(elapsedSeconds: Int) {
        if (cleared) return
        state = CallShieldReducer.reduce(state, CallEvent.Tick(elapsedSeconds))
    }

    // ------------------------------------------------------------------ lifecycle

    /**
     * Asks for a session. The reducer's gates run first (consent, microphone, recogniser), then
     * P3-09's [SpeechSessionCoordinator]. A blocked call never touches the recogniser.
     */
    fun onStartRequested(): CallStartOutcome {
        if (cleared) return CallStartOutcome.Blocked(BlockReason.NOT_LISTENING)
        if (state.isListening) return CallStartOutcome.Listening(coordinator.currentPlan)

        // A restart after a pause continues the same session: the chips the reducer already shows
        // keep their stage memory in A05, so the evidence behind them does not disappear.
        val resumesPausedSession = state.phase == SessionPhase.PAUSED

        // 1. The reducer owns the presentation gates, including the fresh-session reset out of STOPPED.
        val gated = CallShieldReducer.reduce(state, CallEvent.Start)
        if (gated.phase != SessionPhase.LISTENING) {
            val reason = if (gated.blocked != BlockReason.NONE) gated.blocked else BlockReason.NOT_LISTENING
            state = gated.copy(phase = SessionPhase.IDLE, blocked = reason)
            return CallStartOutcome.Blocked(reason)
        }

        val capability = report
        if (capability == null) {
            // Consent and permission may be given, but nothing about this phone was verified.
            state = gated.copy(phase = SessionPhase.IDLE, blocked = BlockReason.RECOGNIZER_UNAVAILABLE)
            return CallStartOutcome.Blocked(BlockReason.RECOGNIZER_UNAVAILABLE)
        }

        // 2. P3-09 decides, in its own order: language, one-session guard, capability, consent, mode.
        when (val prepared = coordinator.prepare(consent, capability, capability.languageTag)) {
            is SpeechStartOutcome.Blocked -> {
                val reason = blockReasonFor(prepared.reason)
                state = gated.copy(phase = SessionPhase.IDLE, blocked = reason)
                return CallStartOutcome.Blocked(reason, prepared.copy)
            }

            is SpeechStartOutcome.Ready -> {
                val started = coordinator.start(prepared.plan)
                if (started is StartResult.Refused || !coordinator.isActive) {
                    // The handle refused, or it failed while opening the microphone (for example a
                    // revoked RECORD_AUDIO). Claiming LISTENING here would be a lie.
                    val reason = failureReason()
                    state = gated.copy(phase = SessionPhase.IDLE, blocked = reason)
                    return CallStartOutcome.Blocked(reason)
                }
                if (!resumesPausedSession) {
                    callState.reset()
                    startedAtMillis = clock()
                } else if (startedAtMillis == null) {
                    startedAtMillis = clock()
                }
                state = gated.copy(blocked = BlockReason.NONE)
                return CallStartOutcome.Listening(prepared.plan)
            }
        }
    }

    /**
     * One recognised chunk from the speech layer. Appends to A05's rolling session, scores it, and
     * dispatches the reducer's own [CallEvent.Chunk] with ONLY verified evidence.
     *
     * Ignored while the host is not listening, so a stray callback cannot move the meter.
     *
     * @param confidence the recogniser's own score for this chunk, or `null` when it reported none.
     *   `null` is not "confident": [UNMEASURED_ASR_CONFIDENCE] is used, which caps A05's confidence at
     *   MEDIUM and never holds an alert on a value that was never received.
     * @param atMillis when the words were heard, from the same clock the host was built with.
     */
    fun onUtterance(text: String, confidence: Float?, atMillis: Long) {
        if (cleared || !state.isListening || !coordinator.isActive) return
        val words = text.trim()
        if (words.isEmpty()) return

        val asrConfidence = (confidence ?: UNMEASURED_ASR_CONFIDENCE).coerceIn(0f, 1f)
        val at = atMillis.coerceAtLeast(0L)

        // A05: rolling transcript + persisted stage memory + deterministic fusion.
        callState.appendUtterance(words, at, asrConfidence.toDouble())
        val scored = scorer.analyzeTranscriptScored(words, callState)

        // The reducer's own tick, so transcript lines carry a real second and the window ages out.
        state = CallShieldReducer.reduce(state, CallEvent.Tick(elapsedSecondsFor(at)))
        state = CallShieldReducer.reduce(
            state,
            CallEvent.Chunk(
                SpeechChunk(
                    asrConfidence = asrConfidence,
                    signals = verifiedSignals(scored, at),
                    text = CallShieldRedaction.mask(words),
                    audible = true,
                    languageSupported = true,
                ),
            ),
        )

        // The meter follows the reducer's own verdict about this chunk: only a chunk it accepted as
        // clear becomes the reading. A held/noisy chunk leaves the meter where it was.
        if (state.phase == SessionPhase.LISTENING && state.audioQuality == AudioQuality.CLEAR) {
            reading = CallShieldReading(
                score = scored.result.risk.score,
                label = scored.result.risk.label,
                confidence = scored.result.risk.confidence,
                verifiedAsk = scored.verifiedAsk,
                escalateCritical = scored.escalateCritical,
                stages = scored.result.stages.map { toCallStage(it) },
            )
        }
    }

    /** A failed recogniser callback, classified by B05. A terminal failure ends the session. */
    fun onRecognizerError(code: Int) {
        if (cleared) return
        val event = coordinator.onError(code)
        if (event is SpeechEvent.Failed) {
            // The recogniser is gone: nothing may stay listening and nothing may keep the words.
            endSession()
        }
    }

    /**
     * Stop. The recogniser is destroyed (once - B05 guarantees that), the rolling transcript and the
     * stage memory are wiped, and the screen is left with no lit chip, a neutral tone, an empty
     * transcript and [SessionPhase.STOPPED]. Safe to call twice and safe before any start.
     */
    fun onStop() {
        if (cleared) return
        endSession()
    }

    /**
     * The screen went to the background or lost visibility. Call Shield is foreground-only, so the
     * recogniser is stopped here and nothing keeps listening.
     */
    fun onHostPaused() {
        if (cleared || state.phase != SessionPhase.LISTENING) return
        coordinator.stop()
        state = CallShieldReducer.reduce(state, CallEvent.Pause)
    }

    /**
     * The screen is visible again. Deliberately does **not** restart the recogniser: only an explicit
     * [onStartRequested] may open the microphone, and only while consent and capability still allow
     * it. This call just re-derives the blocked reason from the last capability report.
     */
    fun onHostResumed() {
        if (cleared) return
        refreshBlocked()
    }

    /**
     * Screen/process teardown. Releases the recogniser exactly once, wipes A05's session and leaves
     * nothing behind that could be rehydrated; later calls are no-ops.
     */
    fun onCleared() {
        if (cleared) return
        endSession()
        cleared = true
        report = null
    }

    override fun toString(): String = "CallShieldHost(" +
        "phase=${state.phase}, " +
        "listening=$isListening, " +
        "consent=${state.consent}, " +
        "permission=${state.permission}, " +
        "recognizer=${state.recognizer}, " +
        "blocked=${state.blocked}, " +
        "tone=${state.tone}, " +
        "chips=${state.litStages.size}, " +
        "score=${reading?.score}, " +
        "engineConfidence=${reading?.confidence}, " +
        "pulses=${state.hapticPulses})"

    // ------------------------------------------------------------------ internals

    private fun endSession() {
        coordinator.stop()
        callState.reset()
        startedAtMillis = null
        reading = null
        state = clearedPresentation(CallShieldReducer.reduce(state, CallEvent.Stop))
    }

    /**
     * The reducer's Stop keeps the stage ids and the released alert for the active session (the
     * screen can still show *why* it warned). This host additionally ends the session outright -
     * chips unlit, tone neutral - because A05's stage memory is gone with [CallSessionState.reset],
     * and a lit chip with no evidence behind it would be a claim nobody can inspect.
     */
    private fun clearedPresentation(stopped: CallUiState): CallUiState = stopped.copy(
        stages = emptyStageChips(),
        tone = RiskTone.NEUTRAL,
        alert = null,
        heldAlert = null,
        heldChunks = 0,
        clearChunks = 0,
        moneyOrCredentialAsk = false,
        confidence = ConfidenceLevel.UNABLE_TO_VERIFY,
        audioQuality = AudioQuality.UNKNOWN,
        unableToVerifyReason = UnableToVerifyReason.NONE,
        elapsedSeconds = 0,
        transcript = emptyList(),
        lastHapticTone = null,
        blocked = BlockReason.NONE,
    )

    /** Only [StageSignal]s A05 can still verify become chip evidence. Nothing is word-spotted. */
    private fun verifiedSignals(scored: ScoredResult, atMillis: Long): List<StageSignal> {
        val liveQuotes = callState.stageEvidence(atMillis)
        val asks = callState.askTypes(atMillis)
        // "Critical" is the documented UI escalation for a money/credential ask, so an ask only
        // counts as directional when A05 reports one of those kinds.
        val moneyOrCredentialAsk = scored.verifiedAsk && asks.any { it in MONEY_OR_CREDENTIAL_ASKS }

        return scored.result.stages.mapNotNull { stageId ->
            val evidence = liveQuotes[stageId].orEmpty()
            val quote = evidence.firstNotNullOfOrNull { item -> verifiedQuote(item, scored.analyzedText) }
                ?: return@mapNotNull null
            val stage = toCallStage(stageId)
            StageSignal(
                stage = stage,
                quote = CallShieldRedaction.mask(quote),
                verified = true,
                moneyOrCredentialAsk = stage == CallStage.ASK && moneyOrCredentialAsk,
            )
        }
    }

    /** The exact words, re-checked against the text A05 analysed: span present and quote present. */
    private fun verifiedQuote(evidence: Evidence, analyzedText: String): String? {
        if (evidence.span == null) return null
        val quote = evidence.quote ?: return null
        if (!evidence.quoteVerifiesAgainst(analyzedText)) return null
        return quote.trim().ifEmpty { null }
    }

    private fun toCallStage(stage: StageId): CallStage = when (stage) {
        StageId.AUTHORITY -> CallStage.AUTHORITY
        StageId.THREAT_OR_REWARD -> CallStage.THREAT_REWARD
        StageId.URGENCY -> CallStage.URGENCY
        StageId.SECRECY -> CallStage.SECRECY_ISOLATION
        StageId.ASK -> CallStage.ASK
    }

    private fun elapsedSecondsFor(atMillis: Long): Int {
        val start = startedAtMillis ?: return state.elapsedSeconds
        val derived = ((atMillis - start).coerceAtLeast(0L) / 1_000L).toInt()
        return maxOf(state.elapsedSeconds, derived)
    }

    /** Same order as [CallShieldReducer]'s start: consent, then microphone, then recogniser. */
    private fun refreshBlocked() {
        if (state.phase == SessionPhase.STOPPED) return
        state = state.copy(blocked = blockReasonFor(state))
    }

    private fun blockReasonFor(current: CallUiState): BlockReason = when {
        current.consent != ConsentState.GIVEN -> BlockReason.NEEDS_CONSENT
        current.permission == MicPermission.DENIED -> BlockReason.MIC_PERMISSION_DENIED
        current.recognizer == RecognizerStatus.UNAVAILABLE -> BlockReason.RECOGNIZER_UNAVAILABLE
        // Unknown capability is not permission: we are simply not listening.
        current.permission != MicPermission.GRANTED ||
            current.recognizer != RecognizerStatus.AVAILABLE -> BlockReason.NOT_LISTENING
        else -> BlockReason.NONE
    }

    /** P3-09's block vocabulary mapped onto the screen's, without inventing a new gate. */
    private fun blockReasonFor(reason: SpeechBlockReason): BlockReason = when (reason) {
        // The recogniser cannot run at all, or this language was never verified for it.
        SpeechBlockReason.RecognizerUnavailable,
        SpeechBlockReason.CapabilityUnavailable,
        SpeechBlockReason.LanguageNotSelectable,
        SpeechBlockReason.LanguageNotVerified,
        -> BlockReason.RECOGNIZER_UNAVAILABLE

        SpeechBlockReason.ConsentNotAsked,
        SpeechBlockReason.ConsentDenied,
        SpeechBlockReason.ConsentWithdrawn,
        -> BlockReason.NEEDS_CONSENT

        SpeechBlockReason.MicPermissionDenied,
        SpeechBlockReason.MicPermissionPermanentlyDenied,
        -> BlockReason.MIC_PERMISSION_DENIED

        // Not asked yet, or already running: nothing is open, but nobody denied anything.
        SpeechBlockReason.MicPermissionNotAsked,
        SpeechBlockReason.RecognizerBusy,
        -> BlockReason.NOT_LISTENING
    }

    private fun failureReason(): BlockReason = when (coordinator.lastError?.reason) {
        ErrorReason.NO_PERMISSION -> BlockReason.MIC_PERMISSION_DENIED
        else -> BlockReason.NOT_LISTENING
    }

    /** A report is "recogniser available" only when the real rules say a session could run. */
    private fun recognizerFor(report: SpeechCapabilityReport): RecognizerStatus = when {
        report.status == SpeechStatus.MissingService || report.status == SpeechStatus.Unavailable ->
            RecognizerStatus.UNAVAILABLE
        report.sessionPossible -> RecognizerStatus.AVAILABLE
        else -> RecognizerStatus.UNAVAILABLE
    }

    companion object {

        /**
         * Used when the recogniser reports no confidence score at all. It is a "usable but not
         * perfect" value, not a claim of clarity: it sits above
         * [CallShieldReducer.LOW_ASR_CONFIDENCE] (so an alert is not held on a number nobody
         * received) and below A05's `ASR_MEDIUM` (so A05 caps its confidence at MEDIUM).
         */
        const val UNMEASURED_ASR_CONFIDENCE: Float = 0.75f

        /** Ask kinds that move money or credentials out, per BEHAVIOR_SPEC / UI_SPEC "critical". */
        private val MONEY_OR_CREDENTIAL_ASKS: Set<AskType> = setOf(
            AskType.OTP_PIN,
            AskType.MONEY_TRANSFER,
            AskType.PERSONAL_INFO,
        )
    }
}

// -------------------------------------------------------------------------------------------------
// Helpers
// -------------------------------------------------------------------------------------------------

/**
 * Masks credential-shaped tokens in text that becomes printable.
 *
 * The exact words still drive detection and scoring; this only protects the strings
 * [CallUiState] renders and its `toString()` prints. Anything that looks like a UPI id is replaced
 * first, then any run of four or more digits (OTP, PIN, card, account, phone). Both replacements are
 * digit-free, so a masked line cannot leak the masked value back through the substitute.
 */
internal object CallShieldRedaction {

    private const val UPI_MASK: String = "xxxx@xxxx"
    private const val DIGITS_MASK: String = "xxxx"

    private val UPI_ID = Regex("""[A-Za-z0-9][A-Za-z0-9._%+-]{1,}@[A-Za-z][A-Za-z0-9.]{1,}""")
    private val LONG_DIGITS = Regex("""\d{4,}""")

    fun mask(text: String): String =
        text.replace(UPI_ID, UPI_MASK).replace(LONG_DIGITS, DIGITS_MASK)
}
