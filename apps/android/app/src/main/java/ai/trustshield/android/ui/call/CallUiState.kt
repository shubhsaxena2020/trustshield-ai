package ai.trustshield.android.ui.call

import ai.trustshield.android.ui.theme.RiskTone
import ai.trustshield.android.ui.theme.ToneCopy
import ai.trustshield.android.input.speech.SpeechCapabilityReport
import ai.trustshield.android.input.speech.SpeechStatus

/*
 * Pure-Kotlin UI state + reducer for the Call Shield screen (work package C05, issue #46).
 *
 * This file carries NO `android.*` and NO `androidx.compose.*` imports: it is plain Kotlin and is
 * exercised on the JVM by `CallScreenTest`. The only outside types are [RiskTone] (an enum) and
 * [ToneCopy] (a data class) from the frozen C02 theme contract -- both are Compose-free
 * declarations themselves.
 *
 * Every rule in here is user-VISIBLE behaviour, not detection: the screen never decides a verdict,
 * it only presents what an evidence source handed it. Deterministic risk scoring belongs to A05
 * (`core/score/LocalScorer.kt`); stage detection belongs to A03 (`core/rules/StageRules.kt`).
 *
 * // SEAM: B05 (`input/speech/CallSpeechSession.kt`) owns the SpeechRecognizer and turns
 * // RecognizerListener callbacks into [CallEvent.Chunk] / [CallEvent.Tick] and the lifecycle events
 * // below. B04 (`input/speech/SpeechCapability.kt`) supplies [MicPermission] and [RecognizerStatus].
 * // A05 replaces [CallRiskGate] with the real deterministic scorer and real StageRules evidence.
 * // B04/B05 are merged, but this package remains a presentation layer until the host wires their
 * // capability report and session events into this state.
 */

// -------------------------------------------------------------------------------------------------
// Vocabulary
// -------------------------------------------------------------------------------------------------

/**
 * The five playbook stages (SOURCE_REPORT sec 3.1 / BEHAVIOR_SPEC "Five-stage playbook").
 *
 * `order` is the fixed render order. The screen always draws exactly these five chips, whether or
 * not evidence has arrived, so the user sees the frame the scam is expected to follow.
 */
enum class CallStage(val order: Int) {
    AUTHORITY(1),
    THREAT_REWARD(2),
    URGENCY(3),
    SECRECY_ISOLATION(4),
    ASK(5);

    companion object {
        const val COUNT: Int = 5
        val ordered: List<CallStage> = entries.sortedBy { it.order }
    }
}

/** Explicit, revocable consent gate. Nothing is captured before [ConsentState.GIVEN]. */
enum class ConsentState { NOT_GIVEN, GIVEN }

/** Visible session lifecycle. [STOPPED] is terminal for that session; a later Start begins a new one. */
enum class SessionPhase { IDLE, LISTENING, PAUSED, STOPPED }

/** Hinglish is the default; English is an explicit user choice (HEAD_ORDERS sec 2 rule 6). */
enum class CallLanguage { HINGLISH, ENGLISH }

/** Runtime microphone permission, reported by B04/B05 -- never assumed. */
enum class MicPermission { UNKNOWN, GRANTED, DENIED }

/** Whether an on-device speech recogniser exists for the locale (B04 probe). */
enum class RecognizerStatus { UNKNOWN, AVAILABLE, UNAVAILABLE }

/** Disclosure selected from B04's capability report; the default must not imply offline recognition. */
enum class RecognitionDisclosure { NOT_CHECKED, ON_DEVICE_UNVERIFIED, MAY_USE_NETWORK, UNAVAILABLE }

/** Map B04 capability to copy that never upgrades an OS report into a measured offline claim. */
fun SpeechCapabilityReport.toCallRecognitionDisclosure(): RecognitionDisclosure = when {
    status == SpeechStatus.Unavailable || status == SpeechStatus.MissingService ->
        RecognitionDisclosure.UNAVAILABLE
    offlineCapable -> RecognitionDisclosure.ON_DEVICE_UNVERIFIED
    cloudMayBeUsed -> RecognitionDisclosure.MAY_USE_NETWORK
    else -> RecognitionDisclosure.NOT_CHECKED
}

/** Audio quality as reported by the speech layer, not inferred from risk. */
enum class AudioQuality { UNKNOWN, CLEAR, UNCLEAR, UNREADABLE }

/**
 * Confidence is reported SEPARATELY from the risk tone and describes how well the audio was
 * understood. It is never "probability that this person is a criminal".
 */
enum class ConfidenceLevel { UNABLE_TO_VERIFY, LOW, MEDIUM, HIGH }

/** Presentation severity of an alert. "CRITICAL" is a UI escalation, never a backend risk label. */
enum class AlertSeverity { NONE, WATCH, CAUTION, HIGH, CRITICAL }

/** Why a Start/listen action could not proceed. Explicit so the screen explains, never dead-ends. */
enum class BlockReason { NONE, NEEDS_CONSENT, MIC_PERMISSION_DENIED, RECOGNIZER_UNAVAILABLE, NOT_LISTENING }

/** Why the screen shows `Unable to Verify` instead of a risk tone. */
enum class UnableToVerifyReason {
    NONE,
    NO_SPEECH_YET,
    UNREADABLE_AUDIO,
    UNSUPPORTED_LANGUAGE,
    MIC_UNAVAILABLE,
    RECOGNIZER_UNAVAILABLE
}

// -------------------------------------------------------------------------------------------------
// Inputs
// -------------------------------------------------------------------------------------------------

/**
 * One already-detected stage hit coming from the evidence source.
 *
 * [quote] must be the EXACT words heard (BEHAVIOR_SPEC: every evidence item carries an exact quote).
 * It is never paraphrased here and it is never displayed after the session stops.
 */
data class StageSignal(
    val stage: CallStage,
    val quote: String,
    /** False when the upstream quote check could not confirm the words; unverified hits are dropped. */
    val verified: Boolean = true,
    /** Only meaningful for [CallStage.ASK]: does the request move money/credentials/access out? */
    val moneyOrCredentialAsk: Boolean = false
)

/**
 * One chunk of recognised speech as delivered by the speech layer (B05, via the SEAM above).
 *
 * @param asrConfidence 0f..1f confidence of THIS chunk's transcript.
 * @param audible        false when nothing usable was heard (noise, silence, muffled speaker phone).
 * @param languageSupported false when the recogniser could not handle the language at all.
 * @param text          the recognised words, for the bounded rolling transcript only.
 */
data class SpeechChunk(
    val asrConfidence: Float,
    val signals: List<StageSignal> = emptyList(),
    val text: String = "",
    val audible: Boolean = true,
    val languageSupported: Boolean = true
)

/** One line of the bounded rolling transcript (in-memory only, dropped after 2 minutes). */
data class TranscriptLine(val atSecond: Int, val text: String)

/** A five-chip state: [lit] flips as evidence arrives, and never un-flips for the active session. */
data class StageChip(
    val stage: CallStage,
    /** Count of DISTINCT clear utterances that supported this stage (identical repeats do not count). */
    val acceptedQuotes: Int = 0,
    /** First exact quote retained for the user; cleared on Stop. */
    val quote: String? = null,
    /** Dedupe keys of accepted utterances; cleared on Stop to drop transcript-derived memory. */
    val seenKeys: Set<String> = emptySet()
) {
    val lit: Boolean get() = acceptedQuotes > 0
}

/** A released warning. [stages] are stage IDs only, so nothing here reproduces raw speech. */
data class CallAlert(
    val severity: AlertSeverity,
    val tone: RiskTone,
    val stages: List<CallStage>,
    val moneyOrCredentialAsk: Boolean
)

// -------------------------------------------------------------------------------------------------
// State
// -------------------------------------------------------------------------------------------------

data class CallUiState(
    val consent: ConsentState = ConsentState.NOT_GIVEN,
    val phase: SessionPhase = SessionPhase.IDLE,
    val language: CallLanguage = CallLanguage.HINGLISH,
    val permission: MicPermission = MicPermission.UNKNOWN,
    val recognizer: RecognizerStatus = RecognizerStatus.UNKNOWN,
    /** Caller maps B04 capability to this value before showing the corresponding recognition copy. */
    val recognitionDisclosure: RecognitionDisclosure = RecognitionDisclosure.NOT_CHECKED,
    /** Active-session timer, supplied as input by the screen's tick; there is no clock in here. */
    val elapsedSeconds: Int = 0,
    /** Exactly five chips, always in [CallStage.ordered] order. */
    val stages: List<StageChip> = emptyStageChips(),
    /** Risk tone of the RELEASED alert (NEUTRAL when nothing has been released). */
    val tone: RiskTone = RiskTone.NEUTRAL,
    val confidence: ConfidenceLevel = ConfidenceLevel.UNABLE_TO_VERIFY,
    val audioQuality: AudioQuality = AudioQuality.UNKNOWN,
    val alert: CallAlert? = null,
    /** An alert that WOULD have been shown but is held: audio was not clear enough to corroborate. */
    val heldAlert: CallAlert? = null,
    val heldChunks: Int = 0,
    val clearChunks: Int = 0,
    val moneyOrCredentialAsk: Boolean = false,
    /** Bounded rolling transcript: 2-minute window, in memory only, cleared on Stop. */
    val transcript: List<TranscriptLine> = emptyList(),
    val hapticsEnabled: Boolean = true,
    val reducedMotion: Boolean = false,
    /** Monotonic count of haptic pulses; the screen fires one pulse per increment. */
    val hapticPulses: Int = 0,
    val lastHapticTone: RiskTone? = null,
    val blocked: BlockReason = BlockReason.NONE,
    val unableToVerifyReason: UnableToVerifyReason = UnableToVerifyReason.NONE
) {
    fun chip(stage: CallStage): StageChip? = stages.firstOrNull { it.stage == stage }

    val litStages: List<CallStage> get() = CallStage.ordered.filter { chip(it)?.lit == true }

    val isListening: Boolean get() = phase == SessionPhase.LISTENING

    /** True when the screen may offer the primary Start control. */
    val canStart: Boolean
        get() = consent == ConsentState.GIVEN &&
            permission == MicPermission.GRANTED &&
            recognizer == RecognizerStatus.AVAILABLE
}

fun emptyStageChips(): List<StageChip> = CallStage.ordered.map { StageChip(it) }

/** "MM:SS" for the active-session timer. Hours are allowed (a two-hour call reads 120:00). */
fun formatElapsed(totalSeconds: Int): String {
    val safe = if (totalSeconds < 0) 0 else totalSeconds
    val minutes = safe / 60
    val seconds = safe % 60
    return minutes.toString().padStart(2, '0') + ":" + seconds.toString().padStart(2, '0')
}

/** Hinglish by default, English on request. */
fun ToneCopy.forLanguage(language: CallLanguage): String = when (language) {
    CallLanguage.HINGLISH -> hinglish
    CallLanguage.ENGLISH -> english
}

// -------------------------------------------------------------------------------------------------
// Events
// -------------------------------------------------------------------------------------------------

sealed interface CallEvent {
    data object GiveConsent : CallEvent
    data object Start : CallEvent
    data object Pause : CallEvent
    data object Resume : CallEvent

    /** Ends the session: microphone must stop, transcript-derived state is cleared. */
    data object Stop : CallEvent

    /** Active-session timer, supplied as input (seconds since the session started). */
    data class Tick(val elapsedSeconds: Int) : CallEvent

    /** Evidence arrives from the speech layer (B05) -- see the SEAM note at the top. */
    data class Chunk(val chunk: SpeechChunk) : CallEvent

    /** User asked for another attempt after "awaaz saaf nahi". */
    data object RetryListening : CallEvent

    data object MicPermissionGranted : CallEvent
    data object MicPermissionDenied : CallEvent
    data object RecognizerAvailable : CallEvent
    data object RecognizerUnavailable : CallEvent

    data class SetHaptics(val enabled: Boolean) : CallEvent
    data class SetReducedMotion(val reducedMotion: Boolean) : CallEvent
    data class SetLanguage(val language: CallLanguage) : CallEvent
}

// -------------------------------------------------------------------------------------------------
// Presentation gate (mirrors BEHAVIOR_SPEC "Rule gates"; A05 replaces it -- see SEAM)
// -------------------------------------------------------------------------------------------------

data class CallGateResult(
    val tone: RiskTone,
    val severity: AlertSeverity,
    val stages: List<CallStage>,
    val moneyOrCredentialAsk: Boolean
)

/**
 * UI-level mirror of the documented rule gate, used until A05 lands.
 *
 *  - ask + any two other independent stages -> high risk (CRITICAL when the ask moves money or
 *    credentials out, otherwise HIGH);
 *  - ask alone -> caution at most ("Bhai jo code aaya woh bhej de" can be a hacked friend);
 *  - no ask, one or two stages -> watch (authority alone never accuses anyone);
 *  - no ask, three or more stages -> caution.
 *
 * It is deliberately the same shape as BEHAVIOR_SPEC so that swapping in A05 changes wiring, not
 * expectations.
 */
object CallRiskGate {

    fun evaluate(chips: List<StageChip>, moneyOrCredentialAsk: Boolean): CallGateResult {
        val lit = CallStage.ordered.filter { stage -> chips.any { it.stage == stage && it.lit } }
        val others = lit.filter { it != CallStage.ASK }
        val ask = lit.contains(CallStage.ASK)
        return when {
            ask && others.size >= 2 -> CallGateResult(
                tone = RiskTone.RED,
                severity = if (moneyOrCredentialAsk) AlertSeverity.CRITICAL else AlertSeverity.HIGH,
                stages = lit,
                moneyOrCredentialAsk = moneyOrCredentialAsk
            )

            ask -> CallGateResult(RiskTone.AMBER, AlertSeverity.CAUTION, lit, moneyOrCredentialAsk)
            others.size >= 3 -> CallGateResult(RiskTone.AMBER, AlertSeverity.CAUTION, lit, moneyOrCredentialAsk)
            others.isNotEmpty() -> CallGateResult(RiskTone.GREEN, AlertSeverity.WATCH, lit, moneyOrCredentialAsk)
            else -> CallGateResult(RiskTone.NEUTRAL, AlertSeverity.NONE, emptyList(), false)
        }
    }
}

// -------------------------------------------------------------------------------------------------
// Reducer
// -------------------------------------------------------------------------------------------------

object CallShieldReducer {

    /** Below this, a transcript line is "awaaz saaf nahi": it corroborates nothing and holds alerts. */
    const val LOW_ASR_CONFIDENCE: Float = 0.55f

    /** Rolling transcript window (SOURCE_REPORT sec 6: "rolling transcript (pichle ~2 minute)"). */
    const val TRANSCRIPT_WINDOW_SECONDS: Int = 120

    const val MAX_TRANSCRIPT_LINES: Int = 40
    const val MAX_QUOTES_PER_STAGE: Int = 3
    const val MAX_QUOTE_CHARS: Int = 160

    fun reduce(state: CallUiState, event: CallEvent): CallUiState = when (event) {
        CallEvent.GiveConsent -> state.copy(
            consent = ConsentState.GIVEN,
            blocked = if (state.blocked == BlockReason.NEEDS_CONSENT) BlockReason.NONE else state.blocked
        )

        CallEvent.Start -> start(state)
        CallEvent.Pause -> if (state.phase == SessionPhase.LISTENING) {
            state.copy(phase = SessionPhase.PAUSED, blocked = BlockReason.NONE)
        } else {
            state
        }

        CallEvent.Resume -> if (state.phase == SessionPhase.PAUSED) {
            state.copy(phase = SessionPhase.LISTENING, blocked = BlockReason.NONE)
        } else {
            state
        }

        CallEvent.Stop -> stop(state)
        is CallEvent.Tick -> tick(state, event.elapsedSeconds)
        is CallEvent.Chunk -> chunk(state, event.chunk)
        CallEvent.RetryListening -> retry(state)

        CallEvent.MicPermissionGranted -> state.copy(
            permission = MicPermission.GRANTED,
            blocked = if (state.blocked == BlockReason.MIC_PERMISSION_DENIED) BlockReason.NONE else state.blocked
        )

        CallEvent.MicPermissionDenied -> permissionDenied(state)
        CallEvent.RecognizerAvailable -> state.copy(
            recognizer = RecognizerStatus.AVAILABLE,
            blocked = if (state.blocked == BlockReason.RECOGNIZER_UNAVAILABLE) BlockReason.NONE else state.blocked
        )

        CallEvent.RecognizerUnavailable -> recognizerUnavailable(state)
        is CallEvent.SetHaptics -> state.copy(hapticsEnabled = event.enabled)
        is CallEvent.SetReducedMotion -> state.copy(reducedMotion = event.reducedMotion)
        is CallEvent.SetLanguage -> state.copy(language = event.language)
    }

    // -- lifecycle -------------------------------------------------------------------------------

    private fun start(state: CallUiState): CallUiState = when {
        // Consent gate: no consent, no session. Never start listening silently.
        state.consent != ConsentState.GIVEN ->
            state.copy(phase = SessionPhase.IDLE, blocked = BlockReason.NEEDS_CONSENT)

        state.permission == MicPermission.DENIED ->
            state.copy(phase = SessionPhase.IDLE, blocked = BlockReason.MIC_PERMISSION_DENIED)

        state.recognizer == RecognizerStatus.UNAVAILABLE ->
            state.copy(phase = SessionPhase.IDLE, blocked = BlockReason.RECOGNIZER_UNAVAILABLE)

        // Unknown capability is not permission to claim an active listening session.
        state.permission != MicPermission.GRANTED || state.recognizer != RecognizerStatus.AVAILABLE ->
            state.copy(phase = SessionPhase.IDLE, blocked = BlockReason.NONE)

        // A stopped session is finished; Start begins a NEW one with a clean sheet.
        state.phase == SessionPhase.STOPPED -> state.copy(
            phase = SessionPhase.LISTENING,
            blocked = BlockReason.NONE,
            elapsedSeconds = 0,
            stages = emptyStageChips(),
            alert = null,
            heldAlert = null,
            heldChunks = 0,
            clearChunks = 0,
            moneyOrCredentialAsk = false,
            tone = RiskTone.NEUTRAL,
            confidence = ConfidenceLevel.UNABLE_TO_VERIFY,
            audioQuality = AudioQuality.UNKNOWN,
            transcript = emptyList(),
            lastHapticTone = null,
            unableToVerifyReason = UnableToVerifyReason.NO_SPEECH_YET
        )

        else -> state.copy(
            phase = SessionPhase.LISTENING,
            blocked = BlockReason.NONE,
            unableToVerifyReason = if (state.clearChunks == 0) {
                UnableToVerifyReason.NO_SPEECH_YET
            } else {
                state.unableToVerifyReason
            }
        )
    }

    /**
     * Stop: clears everything transcript-derived (quotes, dedupe keys, rolling transcript, held
     * alert) so no raw speech survives the session, but keeps the stage IDs and the released alert
     * for the active session so the user can still see WHY a warning appeared.
     */
    private fun stop(state: CallUiState): CallUiState {
        if (state.phase == SessionPhase.STOPPED) return state
        return state.copy(
            phase = SessionPhase.STOPPED,
            transcript = emptyList(),
            heldAlert = null,
            heldChunks = 0,
            blocked = BlockReason.NONE,
            stages = state.stages.map { it.copy(quote = null, seenKeys = emptySet()) }
        )
    }

    private fun tick(state: CallUiState, elapsedSeconds: Int): CallUiState {
        if (state.phase != SessionPhase.LISTENING && state.phase != SessionPhase.PAUSED) return state
        val safe = if (elapsedSeconds < 0) 0 else elapsedSeconds
        return state.copy(
            elapsedSeconds = safe,
            transcript = state.transcript.filter { it.atSecond >= safe - TRANSCRIPT_WINDOW_SECONDS }
        )
    }

    private fun permissionDenied(state: CallUiState): CallUiState = state.copy(
        permission = MicPermission.DENIED,
        phase = if (state.phase == SessionPhase.LISTENING) SessionPhase.IDLE else state.phase,
        blocked = BlockReason.MIC_PERMISSION_DENIED,
        audioQuality = AudioQuality.UNKNOWN,
        confidence = ConfidenceLevel.UNABLE_TO_VERIFY,
        unableToVerifyReason = UnableToVerifyReason.MIC_UNAVAILABLE,
        heldAlert = null,
        heldChunks = 0,
        tone = state.tone
    )

    private fun recognizerUnavailable(state: CallUiState): CallUiState = state.copy(
        recognizer = RecognizerStatus.UNAVAILABLE,
        phase = if (state.phase == SessionPhase.LISTENING) SessionPhase.IDLE else state.phase,
        blocked = BlockReason.RECOGNIZER_UNAVAILABLE,
        audioQuality = AudioQuality.UNKNOWN,
        confidence = ConfidenceLevel.UNABLE_TO_VERIFY,
        unableToVerifyReason = UnableToVerifyReason.RECOGNIZER_UNAVAILABLE,
        heldAlert = null,
        heldChunks = 0,
        tone = state.tone
    )

    private fun retry(state: CallUiState): CallUiState {
        if (state.phase != SessionPhase.LISTENING) return state
        val cleared = state.copy(
            heldAlert = null,
            heldChunks = 0,
            audioQuality = AudioQuality.UNKNOWN,
            unableToVerifyReason = UnableToVerifyReason.NO_SPEECH_YET
        )
        return cleared.copy(confidence = confidenceFor(cleared))
    }

    // -- evidence --------------------------------------------------------------------------------

    private fun chunk(state: CallUiState, chunk: SpeechChunk): CallUiState {
        // Consent / lifecycle / capability gates: a stray chunk can never move the meter.
        val gateClosed = state.consent != ConsentState.GIVEN ||
            state.phase != SessionPhase.LISTENING ||
            state.permission == MicPermission.DENIED ||
            state.recognizer == RecognizerStatus.UNAVAILABLE
        if (gateClosed) return state

        // (1) Unreadable or unsupported audio: honest "Unable to Verify", never a risk escalation.
        if (!chunk.audible || !chunk.languageSupported) {
            return state.copy(
                audioQuality = AudioQuality.UNREADABLE,
                confidence = ConfidenceLevel.UNABLE_TO_VERIFY,
                unableToVerifyReason = if (chunk.languageSupported) {
                    UnableToVerifyReason.UNREADABLE_AUDIO
                } else {
                    UnableToVerifyReason.UNSUPPORTED_LANGUAGE
                },
                tone = state.tone
            )
        }

        val signals = chunk.signals.filter { it.verified && it.quote.isNotBlank() }
        val at = state.elapsedSeconds

        // (2) Murky ASR: "awaaz saaf nahi". Nothing is accepted as evidence and any alert this line
        //     would have raised is HELD until a clear line corroborates it.
        if (chunk.asrConfidence < LOW_ASR_CONFIDENCE) {
            val wouldBeStages = acceptSignals(state.stages, signals)
            val wouldBeAsk = state.moneyOrCredentialAsk ||
                signals.any { it.stage == CallStage.ASK && it.moneyOrCredentialAsk }
            val gate = CallRiskGate.evaluate(wouldBeStages, wouldBeAsk)
            val held = if (gate.tone == RiskTone.RED || gate.tone == RiskTone.AMBER) {
                CallAlert(gate.severity, gate.tone, gate.stages, wouldBeAsk)
            } else {
                state.heldAlert
            }
            return state.copy(
                audioQuality = AudioQuality.UNCLEAR,
                confidence = ConfidenceLevel.LOW,
                unableToVerifyReason = UnableToVerifyReason.NONE,
                heldAlert = held,
                heldChunks = (state.heldChunks + 1).coerceAtMost(MAX_TRANSCRIPT_LINES),
                tone = state.tone
            )
        }

        // (3) Clear chunk: accept distinct evidence, drop the hold, re-evaluate, then fire haptics
        //     only if this transition is the one that entered a high-risk state.
        val accepted = state.copy(
            audioQuality = AudioQuality.CLEAR,
            clearChunks = state.clearChunks + 1,
            unableToVerifyReason = UnableToVerifyReason.NONE,
            heldAlert = null,
            heldChunks = 0,
            moneyOrCredentialAsk = state.moneyOrCredentialAsk ||
                signals.any { it.stage == CallStage.ASK && it.moneyOrCredentialAsk },
            stages = acceptSignals(state.stages, signals),
            transcript = appendTranscript(state, at, chunk.text)
        )
        return applyHaptic(state, settle(accepted))
    }

    /** Recompute tone/alert/confidence from the chips. Tone always mirrors the RELEASED alert. */
    private fun settle(state: CallUiState): CallUiState {
        val gate = CallRiskGate.evaluate(state.stages, state.moneyOrCredentialAsk)
        val alert = if (gate.tone == RiskTone.AMBER || gate.tone == RiskTone.RED) {
            CallAlert(gate.severity, gate.tone, gate.stages, state.moneyOrCredentialAsk)
        } else {
            null
        }
        return state.copy(
            // GREEN is a real reading ("kam khatra"): a weak signal was heard, nothing more.
            tone = gate.tone,
            alert = alert,
            confidence = confidenceFor(state)
        )
    }

    private fun confidenceFor(state: CallUiState): ConfidenceLevel = when {
        state.permission == MicPermission.DENIED -> ConfidenceLevel.UNABLE_TO_VERIFY
        state.recognizer == RecognizerStatus.UNAVAILABLE -> ConfidenceLevel.UNABLE_TO_VERIFY
        state.audioQuality == AudioQuality.UNREADABLE -> ConfidenceLevel.UNABLE_TO_VERIFY
        state.audioQuality == AudioQuality.UNCLEAR -> ConfidenceLevel.LOW
        state.clearChunks == 0 -> ConfidenceLevel.UNABLE_TO_VERIFY
        state.clearChunks >= 4 -> ConfidenceLevel.HIGH
        else -> ConfidenceLevel.MEDIUM
    }

    /**
     * One pulse per transition INTO high risk (RED). A second chunk while already red, a repeated
     * identical utterance, or a session stop never pulses again; haptics disabled never pulses.
     */
    private fun applyHaptic(previous: CallUiState, next: CallUiState): CallUiState {
        val enteredHighRisk = next.tone == RiskTone.RED && previous.tone != RiskTone.RED
        if (!enteredHighRisk || !next.hapticsEnabled) return next
        return next.copy(
            hapticPulses = previous.hapticPulses + 1,
            lastHapticTone = RiskTone.RED
        )
    }

    // -- helpers ---------------------------------------------------------------------------------

    private fun acceptSignals(chips: List<StageChip>, signals: List<StageSignal>): List<StageChip> {
        if (signals.isEmpty()) return chips
        var result = chips
        for (signal in signals) {
            val key = dedupeKey(signal.stage, signal.quote)
            if (key.isEmpty()) continue
            result = result.map { chip ->
                when {
                    // Exactly five chips: a stage is never added, only lit.
                    chip.stage != signal.stage -> chip
                    // Identical repeated utterance: no new stage, no extra corroboration.
                    key in chip.seenKeys -> chip
                    chip.acceptedQuotes >= MAX_QUOTES_PER_STAGE -> chip
                    else -> chip.copy(
                        acceptedQuotes = chip.acceptedQuotes + 1,
                        quote = chip.quote ?: capQuote(signal.quote),
                        seenKeys = chip.seenKeys + key
                    )
                }
            }
        }
        return result
    }

    private fun dedupeKey(stage: CallStage, quote: String): String {
        val normalized = quote.lowercase()
            .map { if (it.isLetterOrDigit()) it else ' ' }
            .joinToString("")
            .split(' ')
            .filter { it.isNotEmpty() }
            .joinToString(" ")
        return if (normalized.isEmpty()) "" else stage.name + ":" + normalized
    }

    private fun capQuote(quote: String): String = quote.trim().take(MAX_QUOTE_CHARS)

    private fun appendTranscript(state: CallUiState, at: Int, text: String): List<TranscriptLine> {
        val line = text.trim().take(MAX_QUOTE_CHARS)
        if (line.isEmpty()) return state.transcript
        return (state.transcript + TranscriptLine(at, line))
            .filter { it.atSecond >= at - TRANSCRIPT_WINDOW_SECONDS }
            .takeLast(MAX_TRANSCRIPT_LINES)
    }
}
