package ai.trustshield.android.input.speech

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * TrustShield AI - Call Shield active speech session (work package B05, issue #37).
 *
 * ## What this file is
 * The bounded, text-only session that sits between an Android speech recogniser and the
 * on-device scorer. It owns exactly three things:
 *  1. the lifecycle of one visible, **user-started** recognition session
 *     (`Idle -> Starting -> Listening -> Stopped | Error(reason)`),
 *  2. a bounded rolling transcript (default: last 120 s, at most 200 utterances),
 *  3. a faithful classification of recogniser callbacks/errors, including the rule that
 *     "no match" and "speech timeout" are *silence*, not errors and not stages.
 *
 * It owns nothing else. Stage detection, playbook matching, scoring, chips and alerts
 * belong to A05 (`LocalScorer` / `CallSessionState`) and to the UI packages. This class
 * only flags `lowConfidence` per utterance; A05 decides what that means.
 *
 * ## Hard rules enforced here (by construction, not by convention)
 * - **Text only.** No field of this class or of [Utterance] holds audio bytes. The
 *   recognition buffer is deliberately discarded in [AndroidSpeechRecognizerHandle] and
 *   there is no `ByteArray`/`ShortArray`/`FloatArray`/PCM/`ByteBuffer` member anywhere.
 * - **Nothing persisted.** No file, no `SharedPreferences`, no database, no logging of
 *   transcript text, no text inside exception messages. `stop()` wipes the transcript.
 * - **No network.** This file performs no I/O of any kind; it never opens a URL.
 * - **Never a claim of remote audio capture.** Call Shield analyses audible microphone
 *   speech in a visible, user-started session. It cannot and does not capture
 *   cellular/VoIP call audio (Android gives no such API to third-party apps).
 * - **One misheard word can never alarm.** Confidence is only *flagged*; the session has
 *   no alarm/verdict surface at all, so a single low-confidence token cannot escalate.
 * - `stop()` is idempotent, clears the transcript, destroys the recogniser exactly once,
 *   and every callback that arrives afterwards is ignored.
 *
 * ## Threading
 * Not thread-safe in the sense of lock-free publication, but every public method takes
 * the same monitor. Call it from one thread (the main thread, as the recogniser does).
 *
 * ## Dependency boundary
 * A05 and B04 are not merged. This file therefore takes a plain `consentGranted: Boolean`
 * and exposes two seams to be bound at integration:
 *  - [UtteranceSink] - A05 subscribes here to receive every accepted utterance.
 *  - [RecognizerHandle] - the real implementation is [AndroidSpeechRecognizerHandle];
 *    a test double is used everywhere else.
 */
class CallSpeechSession(
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val windowMs: Long = DEFAULT_WINDOW_MS,
    private val maxUtterances: Int = DEFAULT_MAX_UTTERANCES,
    private val consentGranted: Boolean = false,
    private val lowConfidenceThreshold: Float = DEFAULT_LOW_CONFIDENCE,
    private val maxTextChars: Int = DEFAULT_MAX_TEXT_CHARS,
) {

    init {
        require(windowMs > 0L) { "windowMs must be positive" }
        require(maxUtterances > 0) { "maxUtterances must be positive" }
        require(maxTextChars > 0) { "maxTextChars must be positive" }
    }

    private val lock = Any()
    private val rolling = ArrayDeque<Utterance>()

    private var currentState: SpeechSessionState = SpeechSessionState.Idle
    private var handle: RecognizerHandle? = null
    private var sink: UtteranceSink? = null
    @Volatile private var inputSuppressed: Boolean = false
    private var emptyResultsCount: Int = 0
    private var noSpeechEvents: Int = 0

    // ---------------------------------------------------------------- observations

    /** Current lifecycle state. */
    val state: SpeechSessionState
        get() = synchronized(lock) { currentState }

    /** Final results that were pure noise (blank after trim). Dropped, never stored. */
    val emptyResults: Int
        get() = synchronized(lock) { emptyResultsCount }

    /**
     * Count of `ERROR_NO_MATCH` / `ERROR_SPEECH_TIMEOUT` callbacks. These are silence,
     * not failures: they never move the session to [SpeechSessionState.Error].
     */
    val noSpeechCount: Int
        get() = synchronized(lock) { noSpeechEvents }

    /**
     * Copy of the bounded transcript, oldest first. Callers can never mutate internal
     * state through the returned list.
     */
    fun transcript(): List<Utterance> = synchronized(lock) { rolling.toList() }

    /** Binds the A05 seam. Pass `null` to detach. */
    fun setUtteranceSink(sink: UtteranceSink?) {
        synchronized(lock) { this.sink = sink }
    }

    /** Drops recognizer callbacks while the app's own spoken alert is playing. */
    fun setInputSuppressed(suppressed: Boolean) {
        inputSuppressed = suppressed
    }

    // ---------------------------------------------------------------- lifecycle

    /**
     * Starts the session. Refused outright when consent was not granted, so a session can
     * never reach `Listening` by accident.
     *
     * @param preserveTranscript keep the rolling transcript across the start. Used for the
     *   rotation path (`snapshot()` -> new instance -> `restore()` -> `start(handle, true)`),
     *   where the last two minutes of speech should survive an activity recreation.
     */
    fun start(handle: RecognizerHandle, preserveTranscript: Boolean = false): StartResult {
        synchronized(lock) {
            if (!consentGranted) return StartResult.Refused(RefusalReason.NO_CONSENT)
            val s = currentState
            if (s is SpeechSessionState.Starting || s is SpeechSessionState.Listening) {
                return StartResult.Refused(RefusalReason.ALREADY_RUNNING)
            }
            if (!preserveTranscript) {
                rolling.clear()
                emptyResultsCount = 0
                noSpeechEvents = 0
            }
            currentState = SpeechSessionState.Starting
            this.handle = handle
        }
        // Outside the lock: a handle's start() may call back into this session.
        handle.start()
        return StartResult.Started
    }

    /**
     * Stops the session. Idempotent: the second call changes nothing, does not clear twice,
     * and never destroys the recogniser again. Also the only place the transcript is wiped.
     */
    fun stop() {
        val toDestroy = synchronized(lock) {
            if (currentState is SpeechSessionState.Stopped) return
            rolling.clear()
            emptyResultsCount = 0
            noSpeechEvents = 0
            currentState = SpeechSessionState.Stopped
            val h = handle
            handle = null
            h
        }
        toDestroy?.destroy()
    }

    /** Replaces a failed recogniser while preserving the user's active session and transcript. */
    fun restart(handle: RecognizerHandle): Boolean {
        synchronized(lock) {
            if (currentState !is SpeechSessionState.Error) return false
            currentState = SpeechSessionState.Starting
            this.handle = handle
        }
        handle.start()
        return true
    }

    /** Replaces the platform recogniser after a callback while keeping this session active. */
    fun replaceRecognizer(handle: RecognizerHandle): Boolean {
        val previous = synchronized(lock) {
            if (!acceptingResults()) return false
            val old = this.handle
            this.handle = handle
            currentState = SpeechSessionState.Starting
            old
        }
        previous?.destroy()
        handle.start()
        return true
    }

    // ---------------------------------------------------------------- recogniser callbacks

    /** `onReadyForSpeech`: the microphone is genuinely open. */
    fun onReadyForSpeech(): SpeechSessionState = synchronized(lock) {
        if (currentState is SpeechSessionState.Starting) {
            currentState = SpeechSessionState.Listening
        }
        currentState
    }

    /** Interim hypothesis. Stored and forwarded so A05 sees the same words the user hears. */
    fun onPartialResult(text: String?, confidence: Float?): SpeechEvent {
        if (inputSuppressed) return SpeechEvent.Ignored
        val clean = sanitize(text)
        // A blank partial is not evidence and not noise worth counting; drop it silently.
        if (clean.isEmpty()) return SpeechEvent.Ignored
        return accept(clean, confidence, isFinal = false)
    }

    /**
     * Final hypothesis. Blank finals are noise: dropped and counted in [emptyResults],
     * never stored, never an error, never a stage.
     */
    fun onFinalResult(text: String?, confidence: Float?): SpeechEvent {
        if (inputSuppressed) return SpeechEvent.Ignored
        val clean = sanitize(text)
        if (clean.isEmpty()) return dropAsEmpty()
        return accept(clean, confidence, isFinal = true)
    }

    /** Noise: counted, dropped, never stored, never an error, never a stage. */
    private fun dropAsEmpty(): SpeechEvent = synchronized(lock) {
        if (!acceptingResults()) {
            SpeechEvent.Ignored
        } else {
            emptyResultsCount++
            SpeechEvent.EmptyResult
        }
    }

    /**
     * Classifies a raw `android.speech.SpeechRecognizer` error code (values mirrored in
     * [RecognizerErrorCode] so this class needs no `android.*` at runtime).
     *
     * `ERROR_NO_MATCH` / `ERROR_SPEECH_TIMEOUT` are silence -> [SpeechEvent.NoSpeech] and
     * the session keeps listening. Everything else terminates the session and tears the
     * recogniser down exactly once; `retryable` says whether a plain retry is honest.
     */
    fun onRecognizerError(errorCode: Int): SpeechEvent {
        val reason = mapError(errorCode)
        if (reason == null) {
            val event = synchronized(lock) {
                if (!acceptingResults()) return SpeechEvent.Ignored
                noSpeechEvents++
                SpeechEvent.NoSpeech(
                    if (errorCode == RecognizerErrorCode.SPEECH_TIMEOUT) {
                        NoSpeechReason.SILENCE
                    } else {
                        NoSpeechReason.NO_MATCH
                    },
                )
            }
            return event
        }
        val retryable = reason != ErrorReason.NO_PERMISSION
        val toDestroy = synchronized(lock) {
            if (!acceptingResults()) return SpeechEvent.Ignored
            currentState = SpeechSessionState.Error(reason = reason, retryable = retryable, code = errorCode)
            val h = handle
            handle = null
            h
        }
        toDestroy?.destroy()
        return SpeechEvent.Failed(reason = reason, retryable = retryable, code = errorCode)
    }

    // ---------------------------------------------------------------- transcript

    /**
     * Appends an utterance the caller already built. `lowConfidence` is recomputed from
     * [confidence] so the flag invariant cannot be bypassed.
     */
    fun appendUtterance(utterance: Utterance): SpeechEvent =
        accept(sanitize(utterance.text), utterance.confidence, utterance.isFinal)

    /** Convenience overload; timestamp comes from the injected clock. */
    fun appendUtterance(text: String, confidence: Float? = null, isFinal: Boolean = true): SpeechEvent {
        val clean = sanitize(text)
        if (clean.isEmpty()) return dropAsEmpty()
        return accept(clean, confidence, isFinal)
    }

    // ---------------------------------------------------------------- rotation

    /**
     * Rotation / process-recreation snapshot. Carries the lifecycle state plus the bounded
     * transcript and the two counters. It never carries audio, and the transcript stays
     * bounded by construction.
     */
    fun snapshot(): Snapshot = synchronized(lock) {
        Snapshot(
            state = currentState,
            utterances = rolling.toList(),
            emptyResults = emptyResultsCount,
            noSpeechCount = noSpeechEvents,
        )
    }

    /**
     * Restores a snapshot into a fresh instance.
     *
     * Terminal states stay terminal: a [SpeechSessionState.Stopped] snapshot restores as
     * `Stopped` with an empty transcript, so a stopped session can never come back alive.
     * An [SpeechSessionState.Error] snapshot restores as the same error.
     *
     * Active states (`Starting`/`Listening`) come back as [SpeechSessionState.Idle]: the
     * recogniser died with the old process/activity, so claiming to listen would be a lie.
     * The caller re-attaches with `start(handle, preserveTranscript = true)`, which keeps
     * the restored two minutes. Stage persistence across the whole call is A05's job.
     *
     * @return `false` when this instance is not `Idle` (restore is one-shot, never over a
     *   live session).
     */
    fun restore(snapshot: Snapshot): Boolean = synchronized(lock) {
        if (currentState !is SpeechSessionState.Idle) return false
        val restored = snapshot.state
        currentState = when (restored) {
            is SpeechSessionState.Stopped -> SpeechSessionState.Stopped
            is SpeechSessionState.Error -> restored
            else -> SpeechSessionState.Idle
        }
        rolling.clear()
        // stop() wipes the transcript, so a stopped snapshot has nothing to restore.
        if (currentState !is SpeechSessionState.Stopped) {
            for (u in snapshot.utterances.takeLast(maxUtterances)) {
                rolling.addLast(u)
            }
        }
        emptyResultsCount = if (currentState is SpeechSessionState.Stopped) 0 else snapshot.emptyResults
        noSpeechEvents = if (currentState is SpeechSessionState.Stopped) 0 else snapshot.noSpeechCount
        evict(clock())
        true
    }

    // ---------------------------------------------------------------- internals

    private fun acceptingResults(): Boolean =
        currentState is SpeechSessionState.Starting || currentState is SpeechSessionState.Listening

    private fun accept(text: String, confidence: Float?, isFinal: Boolean): SpeechEvent {
        var emitted: Utterance? = null
        var target: UtteranceSink? = null
        val event = synchronized(lock) {
            if (!acceptingResults()) return SpeechEvent.Ignored
            val clean = sanitize(text)
            if (clean.isEmpty()) {
                emptyResultsCount++
                return SpeechEvent.EmptyResult
            }
            val u = Utterance(
                text = clean,
                tsMs = clock(),
                confidence = confidence,
                isFinal = isFinal,
                lowConfidence = confidence != null && confidence < lowConfidenceThreshold,
            )
            rolling.addLast(u)
            // A delivered result proves the recogniser is running.
            currentState = SpeechSessionState.Listening
            evict(u.tsMs)
            emitted = u
            target = sink
            SpeechEvent.Accepted(u)
        }
        val s = target
        val u = emitted
        if (s != null && u != null) {
            // A misbehaving sink must not corrupt session state. Text is never logged.
            try {
                s.onUtterance(u)
            } catch (_: Exception) {
                // deliberately swallowed
            }
        }
        return event
    }

    /** Drops anything older than the window, then enforces the hard utterance cap. */
    private fun evict(nowMs: Long) {
        val cutoff = nowMs - windowMs
        while (rolling.isNotEmpty() && rolling.first().tsMs < cutoff) {
            rolling.removeFirst()
        }
        while (rolling.size > maxUtterances) {
            rolling.removeFirst()
        }
    }

    /**
     * Trims and caps a single utterance. Truncation is a **prefix** cut, so whatever A05
     * quotes later is still an exact substring of the original hypothesis.
     */
    private fun sanitize(text: String?): String {
        if (text == null) return ""
        val trimmed = text.trim()
        return if (trimmed.length > maxTextChars) trimmed.substring(0, maxTextChars) else trimmed
    }

    companion object {
        const val DEFAULT_WINDOW_MS: Long = 120_000L
        const val DEFAULT_MAX_UTTERANCES: Int = 200
        const val DEFAULT_LOW_CONFIDENCE: Float = 0.6f
        const val DEFAULT_MAX_TEXT_CHARS: Int = 512

        /**
         * Maps an `android.speech.SpeechRecognizer` error code to a terminal reason, or
         * `null` for the two codes that mean "the user said nothing".
         */
        internal fun mapError(code: Int): ErrorReason? = when (code) {
            RecognizerErrorCode.NO_MATCH, RecognizerErrorCode.SPEECH_TIMEOUT -> null
            RecognizerErrorCode.RECOGNIZER_BUSY -> ErrorReason.BUSY
            RecognizerErrorCode.INSUFFICIENT_PERMISSIONS -> ErrorReason.NO_PERMISSION
            RecognizerErrorCode.NETWORK, RecognizerErrorCode.NETWORK_TIMEOUT,
            RecognizerErrorCode.LANGUAGE_UNAVAILABLE -> ErrorReason.NETWORK
            RecognizerErrorCode.AUDIO -> ErrorReason.AUDIO
            RecognizerErrorCode.SERVER -> ErrorReason.SERVER
            RecognizerErrorCode.CLIENT -> ErrorReason.CLIENT
            else -> ErrorReason.UNKNOWN
        }
    }
}

// ---------------------------------------------------------------------- value types

/**
 * One recognised hypothesis. **Text only** - there is no audio field, and adding one would
 * break the "no raw audio stored" hard rule.
 *
 * @param text trimmed, capped at [CallSpeechSession.DEFAULT_MAX_TEXT_CHARS] by a prefix cut
 * @param tsMs when the session accepted it, from the injected clock
 * @param confidence the recogniser's own score, or `null` when it gave none (unmeasured -
 *   not the same as confident; A05 must decide)
 * @param isFinal final hypothesis vs interim
 * @param lowConfidence `true` only when [confidence] is known and below the threshold.
 *   A flag, never an alarm: one misheard word must not be able to escalate anything.
 */
data class Utterance(
    val text: String,
    val tsMs: Long,
    val confidence: Float?,
    val isFinal: Boolean,
    val lowConfidence: Boolean,
)

/**
 * Numeric mirror of `android.speech.SpeechRecognizer.ERROR_*`. Public, stable platform
 * constants (API 8+); mirrored here so the decision core links no `android.*` at runtime
 * and stays unit-testable off-device. The adapter asserts nothing at compile time, so
 * these values must be re-checked if the platform ever changes them.
 */
object RecognizerErrorCode {
    const val NETWORK_TIMEOUT = 1
    const val NETWORK = 2
    const val AUDIO = 3
    const val SERVER = 4
    const val CLIENT = 5
    const val SPEECH_TIMEOUT = 6
    const val NO_MATCH = 7
    const val RECOGNIZER_BUSY = 8
    const val INSUFFICIENT_PERMISSIONS = 9
    const val LANGUAGE_UNAVAILABLE = 12
}

/** Lifecycle of one visible session. `Error` is terminal for that attempt. */
sealed class SpeechSessionState {
    data object Idle : SpeechSessionState()
    data object Starting : SpeechSessionState()
    data object Listening : SpeechSessionState()
    data object Stopped : SpeechSessionState()
    data class Error(
        val reason: ErrorReason,
        val retryable: Boolean,
        val code: Int? = null,
    ) : SpeechSessionState()
}

/** Why a session ended in [SpeechSessionState.Error]. */
enum class ErrorReason { BUSY, NO_PERMISSION, NETWORK, AUDIO, SERVER, CLIENT, UNKNOWN }

/**
 * Why a callback turned out to be silence rather than an error. **Not** a stage and **not**
 * an error: A05 sees the counter and decides.
 */
enum class NoSpeechReason { NO_MATCH, SILENCE }

/** What a recogniser callback meant. Nothing is persisted. */
sealed class SpeechEvent {
    data class Accepted(val utterance: Utterance) : SpeechEvent()
    data class Failed(val reason: ErrorReason, val retryable: Boolean, val code: Int) : SpeechEvent()
    data class NoSpeech(val reason: NoSpeechReason) : SpeechEvent()
    data object EmptyResult : SpeechEvent()
    data object Ignored : SpeechEvent()
}

/** Result of [CallSpeechSession.start]. */
sealed class StartResult {
    data object Started : StartResult()
    data class Refused(val reason: RefusalReason) : StartResult()
}

enum class RefusalReason { NO_CONSENT, ALREADY_RUNNING }

/**
 * Rotation payload. State + bounded transcript + counters, no audio, nothing persisted.
 */
data class Snapshot(
    val state: SpeechSessionState,
    val utterances: List<Utterance>,
    val emptyResults: Int,
    val noSpeechCount: Int,
)

// ---------------------------------------------------------------------- seams

/** A05 subscribes here. Called on the session's thread, after internal state is consistent. */
fun interface UtteranceSink {
    fun onUtterance(utterance: Utterance)
}

/**
 * The only thing the session may do to a recogniser: start it, then destroy it once.
 *
 * A plain `interface`, not a `fun interface`: Kotlin's `fun interface` allows exactly one
 * abstract method and this seam needs two (`start`, `destroy`). Lambdas therefore need
 * `object : RecognizerHandle { ... }`; the real implementation is
 * [AndroidSpeechRecognizerHandle].
 */
interface RecognizerHandle {
    fun start()
    fun destroy()
}

/** The explicit platform route selected from the current B04 capability report. */
enum class SpeechRecognitionMode {
    OnDevice,
    DefaultMayUseNetwork,
}

/** An OnDeviceReady report must use the on-device API; all other usable reports may use default. */
internal fun recognitionModeFor(
    report: SpeechCapabilityReport,
    bundledOfflineSelected: Boolean = false,
): SpeechRecognitionMode? = when {
    bundledOfflineSelected && report.status == SpeechStatus.MissingService -> SpeechRecognitionMode.OnDevice
    !report.sessionPossible -> null
    report.offlineCapable && !report.cloudMayBeUsed -> SpeechRecognitionMode.OnDevice
    report.cloudMayBeUsed -> SpeechRecognitionMode.DefaultMayUseNetwork
    else -> null
}

// ---------------------------------------------------------------------- Android adapter

/**
 * ## UNMEASURED - not JVM-tested, never run on a device or emulator in this package.
 *
 * `android.speech.*` exists only as compile-time stubs in the unit-test harness
 * (`android.jar` bodies throw), so this class has **no** unit test and no device evidence.
 * Everything it does beyond the [CallSpeechSession] state machine is unverified: permission
 * prompts, `hi-IN` availability, partial-result behaviour, and whether the device recogniser
 * works offline at all. Do not claim any of it works until a device run is recorded.
 *
 * A thin forwarder, deliberately dumb:
 *  - builds `ACTION_RECOGNIZE_SPEECH` with `EXTRA_LANGUAGE = "hi-IN"`, partial results on and
 *    `EXTRA_PREFER_OFFLINE` set (a **hint** - it does not guarantee offline recognition),
 *  - forwards ready/partial/final/error into [CallSpeechSession],
 *  - **discards the audio buffer**: `onBufferReceived` is an empty body with no copy, no
 *    field, no log. That is the mechanical guarantee behind "text only".
 *
 * Main thread only, one instance per session. `destroy()` is null-guarded, so a double call
 * cannot double-destroy even if a caller misbehaves; the session already guarantees once.
 */
class AndroidSpeechRecognizerHandle(
    private val context: Context,
    private val session: CallSpeechSession,
    private val capability: SpeechCapabilityReport,
    private val languageTag: String = DEFAULT_LANGUAGE_TAG,
    private val recognizerFactory: (Context, SpeechRecognitionMode) -> SpeechRecognizer? = ::createPlatformRecognizer,
    private val finalResultListener: ((String?, Float?) -> Unit)? = null,
    private val errorListener: ((Int) -> Unit)? = null,
) : RecognizerHandle {

    private var recognizer: SpeechRecognizer? = null

    override fun start() {
        if (recognizer != null) return
        if (!hasAudioPermission(context)) {
            // Honest failure through the normal error path; never a silent no-op.
            session.onRecognizerError(RecognizerErrorCode.INSUFFICIENT_PERMISSIONS)
            return
        }
        if (!isAvailable(context)) {
            session.onRecognizerError(RecognizerErrorCode.CLIENT)
            return
        }
        val mode = recognitionModeFor(capability)
        if (mode == null) {
            session.onRecognizerError(RecognizerErrorCode.CLIENT)
            return
        }
        val r = recognizerFactory(context, mode)
        if (r == null) {
            session.onRecognizerError(RecognizerErrorCode.CLIENT)
            return
        }
        r.setRecognitionListener(Forwarder())
        r.startListening(buildIntent())
        recognizer = r
    }

    override fun destroy() {
        val r = recognizer ?: return
        recognizer = null
        r.setRecognitionListener(null)
        r.cancel()
        r.destroy()
    }

    private fun buildIntent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, languageTag)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
    }

    private inner class Forwarder : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            session.onReadyForSpeech()
        }

        override fun onBeginningOfSpeech() = Unit

        /** Loudness only; no content, nothing stored. */
        override fun onRmsChanged(rmsdB: Float) = Unit

        /**
         * The raw microphone buffer. Deliberately discarded: no copy, no field, no log.
         * This is the "no raw audio stored" rule, enforced at the only point it could leak.
         */
        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() = Unit

        override fun onError(error: Int) {
            errorListener?.invoke(error) ?: session.onRecognizerError(error)
        }

        override fun onResults(results: Bundle?) {
            val text = firstAlternative(results)
            val confidence = firstConfidence(results)
            finalResultListener?.invoke(text, confidence) ?: session.onFinalResult(text, confidence)
        }

        override fun onPartialResults(partialResults: Bundle?) {
            session.onPartialResult(firstAlternative(partialResults), firstConfidence(partialResults))
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    companion object {
        const val DEFAULT_LANGUAGE_TAG: String = "hi-IN"

        private fun createPlatformRecognizer(
            context: Context,
            mode: SpeechRecognitionMode,
        ): SpeechRecognizer? = when (mode) {
            SpeechRecognitionMode.OnDevice -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            } else {
                null
            }
            SpeechRecognitionMode.DefaultMayUseNetwork -> SpeechRecognizer.createSpeechRecognizer(context)
        }

        /** `RECORD_AUDIO` must be granted at runtime by whoever owns consent (B04/C05). */
        fun hasAudioPermission(context: Context): Boolean =
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED

        /**
         * Ask this **before** offering Call Shield. A device with no recognition service
         * must get "Unable to Verify"-style wording, not a silently dead session.
         */
        fun isAvailable(context: Context): Boolean =
            SpeechRecognizer.isRecognitionAvailable(context)

        private fun firstAlternative(bundle: Bundle?): String? =
            bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()

        private fun firstConfidence(bundle: Bundle?): Float? =
            bundle?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)?.firstOrNull()
    }
}
