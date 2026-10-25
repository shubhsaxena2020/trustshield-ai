/*
 * P3-09 - one adapter that joins B04 (capability + consent) to B05 (the bounded session) and to the
 * platform `SpeechRecognizer` (issue #88).
 *
 * Why this file exists: B04 can probe a phone, and B05 can run a bounded, text-only session, but
 * nothing joined them. This file is that join, and it does four things in one place:
 *  1. reads capability FIRST, so nothing is promised before it is checked, and a block happens
 *     before any recogniser object exists;
 *  2. picks the platform recogniser mode from the verified report only (`recognitionModeFor`);
 *  3. hands the UI the honest disclosure plus the exact limitation lines it must show - an
 *     on-device report is "reported available, offline not measured on this device", and a
 *     cloud-capable report says audio may leave the device;
 *  4. guarantees one recogniser per session, one destroy on stop or on a terminal error, and no
 *     transcript surviving the stop.
 *
 * ## Shape
 * A pure-Kotlin core (no `android.*` type is used below; the imports at the top exist only for the
 * thin adapter at the bottom) plus one android-only adapter. The core is exercised on the JVM by
 * `AndroidSpeechRecognizerAdapterTest`. The adapter is compile-only: `android.jar` method bodies are
 * stubs in that harness, so the adapter was **not** run on a device or emulator in this package.
 *
 * ## Honesty rules, taken literally from docs/android/ARCHITECTURE.md and B04/B05
 * - `offlineVerified` means "the OS reports an on-device recogniser plus the installed model for
 *   this language". It is a capability report, never a measured offline run, and its copy always
 *   says the offline path is not tested on this device.
 * - A cloud-capable path is never labelled on-device, private or offline. Its copy says some audio
 *   may leave the device.
 * - Call Shield analyses visible, user-started microphone speech. Android gives ordinary apps no
 *   reliable capture of the remote side of cellular/VoIP calls; nothing here claims it, and nothing
 *   here detects deepfakes.
 * - No verdict is produced here. Scoring belongs to the deterministic local engine (A05).
 * - No raw audio is stored or forwarded: the only audio sink in the chain is B05's handle, which
 *   discards the buffer. The transcript is text-only and is wiped on stop and on a terminal error.
 * - This file makes no network call, fetches no URL and has no logging call of any kind.
 */

package ai.trustshield.android.input.speech

import ai.trustshield.android.ui.call.RecognitionDisclosure
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.speech.SpeechRecognizer

// ---------------------------------------------------------------------------------------------
// Pure core (JVM-tested, no android.* type below this line until the adapter at the bottom)
// ---------------------------------------------------------------------------------------------

/**
 * Why a Call Shield session could not be prepared.
 *
 * The first eight values mirror [ConsentBlockReason] exactly, so `ConsentGate` can be mapped 1:1
 * without inventing a gate of our own ([of] is an exhaustive `when`). The rest are the two input
 * contract failures and the one interruption this layer is responsible for.
 */
enum class SpeechBlockReason {
    /** No recognition service is installed or enabled on this phone. */
    RecognizerUnavailable,

    /** This Android version / configuration cannot run speech recognition at all. */
    CapabilityUnavailable,

    ConsentNotAsked,
    ConsentDenied,
    ConsentWithdrawn,
    MicPermissionNotAsked,
    MicPermissionDenied,
    MicPermissionPermanentlyDenied,

    /** The requested language tag is not one of the two the app ships ([SpeechLanguage.selectable]). */
    LanguageNotSelectable,

    /**
     * The capability report was probed for a different language than the one requested, so nothing
     * about this request has been verified. Refuse rather than reuse another language's answer.
     */
    LanguageNotVerified,

    /** The session is already live (`Starting` / `Listening`); one recogniser at a time. */
    RecognizerBusy,
    ;

    companion object {
        /** 1:1 mapping from the consent gate. No extra gate is introduced on this path. */
        fun of(reason: ConsentBlockReason): SpeechBlockReason = when (reason) {
            ConsentBlockReason.RecognizerUnavailable -> RecognizerUnavailable
            ConsentBlockReason.CapabilityUnavailable -> CapabilityUnavailable
            ConsentBlockReason.ConsentNotAsked -> ConsentNotAsked
            ConsentBlockReason.ConsentDenied -> ConsentDenied
            ConsentBlockReason.ConsentWithdrawn -> ConsentWithdrawn
            ConsentBlockReason.MicPermissionNotAsked -> MicPermissionNotAsked
            ConsentBlockReason.MicPermissionDenied -> MicPermissionDenied
            ConsentBlockReason.MicPermissionPermanentlyDenied -> MicPermissionPermanentlyDenied
        }
    }
}

/**
 * What the UI shows for a block: one line, plus an optional second line that only exists when it
 * tells the user something actionable (for example, that a permanently denied microphone needs
 * system Settings).
 */
data class SpeechBlockCopy(
    val title: BilingualCopy,
    val detail: BilingualCopy? = null,
)

/**
 * Copy that belongs to the session plan rather than to B04. Everything here is hedged; none of it
 * may claim a measured offline run, privacy, safety or remote-call capture.
 */
object SpeechPlanCopy {

    /** The scope every session states: audible microphone speech, while the screen is open. */
    val audibleSpeechOnly = BilingualCopy(
        hinglish = "Sirf woh awaaz jo phone ke mic tak pahunchti hai, aur sirf jab Call Shield khula ho, check hoti hai.",
        english = "Only speech that reaches the phone microphone, and only while Call Shield is open, is analysed.",
    )

    /** Structural promise: the transcript is in memory only and is wiped by `stop()`. */
    val audioNotRetained = BilingualCopy(
        hinglish = "Awaaz save nahi hoti. Stop karte hi transcript mit jaata hai.",
        english = "Audio is not saved. The transcript is cleared as soon as you stop.",
    )

    val sessionAlreadyRunning = BilingualCopy(
        hinglish = "Ek session pehle se chal raha hai",
        english = "A session is already running",
    )

    val openSettingsForMic = BilingualCopy(
        hinglish = "Settings mein jaakar mic permission on karni padegi",
        english = "Microphone permission has to be turned on in Settings",
    )

    val languageNotSupported = BilingualCopy(
        hinglish = "Yeh bhasha app support nahi karta",
        english = "The app does not support this language",
    )

    /** Every line above; used by the copy-honesty test. */
    val all: List<BilingualCopy> = listOf(
        audibleSpeechOnly,
        audioNotRetained,
        sessionAlreadyRunning,
        openSettingsForMic,
        languageNotSupported,
    )
}

/**
 * The one copy line for a block reason. Ordered so the user sees the most actionable truth first.
 */
fun blockCopyFor(reason: SpeechBlockReason): SpeechBlockCopy = when (reason) {
    SpeechBlockReason.RecognizerUnavailable ->
        SpeechBlockCopy(SpeechCopy.recognizerUnavailable, SpeechPlanCopy.audibleSpeechOnly)

    SpeechBlockReason.CapabilityUnavailable -> SpeechBlockCopy(SpeechCopy.speechUnavailable)
    SpeechBlockReason.ConsentNotAsked -> SpeechBlockCopy(SpeechCopy.consentNeeded, SpeechCopy.consentAsk)
    SpeechBlockReason.ConsentDenied -> SpeechBlockCopy(SpeechCopy.consentNotGiven, SpeechCopy.consentAsk)
    SpeechBlockReason.ConsentWithdrawn -> SpeechBlockCopy(SpeechCopy.consentWithdrawn, SpeechCopy.consentAsk)
    SpeechBlockReason.MicPermissionNotAsked -> SpeechBlockCopy(SpeechCopy.micPermissionNotAsked)
    SpeechBlockReason.MicPermissionDenied -> SpeechBlockCopy(SpeechCopy.micPermissionMissing)
    SpeechBlockReason.MicPermissionPermanentlyDenied ->
        SpeechBlockCopy(SpeechCopy.micPermissionMissing, SpeechPlanCopy.openSettingsForMic)

    SpeechBlockReason.LanguageNotSelectable -> SpeechBlockCopy(SpeechPlanCopy.languageNotSupported)
    SpeechBlockReason.LanguageNotVerified -> SpeechBlockCopy(SpeechCopy.languageNotConfirmed)
    SpeechBlockReason.RecognizerBusy -> SpeechBlockCopy(SpeechPlanCopy.sessionAlreadyRunning)
}

/**
 * Everything the host needs to run one session honestly, decided before any recogniser exists.
 *
 * @param recognitionMode the platform route this plan is allowed to use.
 * @param disclosure the enum the Call Shield screen already renders ([RecognitionDisclosure]).
 * @param offlineVerified true only when the verified report says an on-device recogniser AND the
 *   installed model for this language are present. OS-reported capability, **not** a measured
 *   offline run; the plan's copy always says so.
 * @param cloudMayBeUsed true when audio may leave the device on this path.
 * @param limitations the exact lines to display, in order, in both app languages.
 */
data class SpeechSessionPlan(
    val recognitionMode: SpeechRecognitionMode,
    val disclosure: RecognitionDisclosure,
    val languageTag: String,
    val offlineVerified: Boolean,
    val cloudMayBeUsed: Boolean,
    val limitations: List<BilingualCopy>,
) {
    /** True only for the fully on-device route; the copy still carries the "not measured" caveat. */
    val onDeviceOnly: Boolean
        get() = offlineVerified && !cloudMayBeUsed

    /** Both languages of every limitation line, for a semantics/TalkBack summary and the copy test. */
    fun limitationsAsText(): String =
        limitations.joinToString(" ") { "${it.hinglish} ${it.english}" }
}

/** Result of [SpeechSessionCoordinator.prepare]. There is no third possibility. */
sealed interface SpeechStartOutcome {

    /** Session is allowed and the plan says exactly which mode and copy to use. */
    data class Ready(val plan: SpeechSessionPlan) : SpeechStartOutcome

    /** Session must not start; [copy] is what the UI shows instead of a dead Start button. */
    data class Blocked(
        val reason: SpeechBlockReason,
        val copy: SpeechBlockCopy = blockCopyFor(reason),
    ) : SpeechStartOutcome
}

/**
 * The coordination core. Owns no audio, no text beyond what B05 already bounds, and no clock.
 *
 * Order of decisions in [prepare] is deliberate: the language input first, then the one-session
 * guard, then [ConsentGate] (recogniser service, then consent, then the microphone). Only after
 * those pass is a mode chosen, so a cloud-capable path can never be presented before the user has
 * consented.
 *
 * Ownership of the recogniser is split exactly one way:
 *  - the coordinator creates the handle and hands it to [CallSpeechSession.start];
 *  - from then on the **session** owns it, and both `stop()` and a terminal recogniser error destroy
 *    it there. The coordinator therefore never destroys a handle the session already owns, which is
 *    what keeps `destroy` at exactly one call per session.
 *  - a handle that was created but never transferred (the session refused to start) is destroyed
 *    here, immediately, so a refused start cannot leak a recogniser.
 *
 * Threading: like B05, this is meant to be driven from one thread (the main thread, which is where
 * the recogniser calls back). The monitor only guards publication of the plan and the last error.
 */
class SpeechSessionCoordinator(
    private val session: CallSpeechSession,
    private val handleFactory: (SpeechRecognitionMode) -> RecognizerHandle,
) {

    private val lock = Any()
    private var plan: SpeechSessionPlan? = null
    private var error: SpeechSessionState.Error? = null

    /** The last plan [prepare] handed out; `null` before the first successful prepare. */
    val currentPlan: SpeechSessionPlan?
        get() = synchronized(lock) { plan }

    /**
     * True while the session is live (`Starting` / `Listening`). Derived from the session - the
     * owner of the recogniser - so an externally stopped session can never leave a stale "busy"
     * flag behind and block the next attempt.
     */
    val isActive: Boolean
        get() = when (session.state) {
            is SpeechSessionState.Starting, is SpeechSessionState.Listening -> true
            else -> false
        }

    /** The last terminal recogniser error, or `null` when the last attempt had none. */
    val lastError: SpeechSessionState.Error?
        get() = synchronized(lock) { error }

    /**
     * True when the session holds no transcript. `stop()` and a terminal error both make this true;
     * it is read from the session itself rather than from a cached flag, so it cannot drift.
     */
    val stoppedTranscriptIsEmpty: Boolean
        get() = session.transcript().isEmpty()

    /**
     * Inbound seam: feeds an already-built [Utterance] down the same path as [onUtterance], so a host
     * (demo script, custom recogniser) never has to reach into B05 directly.
     */
    val utteranceSink: UtteranceSink = UtteranceSink { utterance -> session.appendUtterance(utterance) }

    /**
     * Decides whether this session may run, and with which mode and copy.
     *
     * @param consent the persisted consent record.
     * @param report the verified capability report for [languageTag].
     * @param languageTag must be one of [SpeechLanguage.selectable] and must be the language the
     *   report was probed for; anything else is refused instead of guessed.
     */
    fun prepare(
        consent: SpeechConsent,
        report: SpeechCapabilityReport,
        languageTag: String = SpeechLanguage.DEFAULT,
    ): SpeechStartOutcome {
        // 1. Input contract. Not a device fact, so it is checked before anything is promised.
        if (!SpeechLanguage.isSelectable(languageTag)) {
            return SpeechStartOutcome.Blocked(SpeechBlockReason.LanguageNotSelectable)
        }
        // 2. One session at a time; the recogniser is a shared system resource.
        if (isActive) return SpeechStartOutcome.Blocked(SpeechBlockReason.RecognizerBusy)

        // 3. Capability and consent, in the same order ConsentGate uses: a missing service or an
        //    unsupported Android version blocks before consent is even considered.
        when (val decision = ConsentGate.check(consent, report)) {
            is ConsentDecision.Block ->
                return SpeechStartOutcome.Blocked(SpeechBlockReason.of(decision.reason))

            ConsentDecision.Allow -> Unit
        }

        // 4. The report must be about this language. Reusing another language's answer would be a
        //    capability claim we never checked.
        if (report.languageTag != languageTag) {
            return SpeechStartOutcome.Blocked(SpeechBlockReason.LanguageNotVerified)
        }

        // 5. Mode comes from the verified report alone.
        val mode = recognitionModeFor(report)
            ?: return SpeechStartOutcome.Blocked(SpeechBlockReason.CapabilityUnavailable)

        val ready = buildPlan(report, mode, languageTag)
        synchronized(lock) { plan = ready }
        return SpeechStartOutcome.Ready(ready)
    }

    /**
     * Starts the session with exactly one recogniser.
     *
     * A second call while the session is live returns [StartResult.Refused] with
     * [RefusalReason.ALREADY_RUNNING] and does **not** ask [handleFactory] for another one, so two
     * recognisers can never compete for the microphone.
     */
    fun start(plan: SpeechSessionPlan): StartResult {
        // The session already holds a recogniser: never create a competing one.
        if (isActive) return StartResult.Refused(RefusalReason.ALREADY_RUNNING)

        val created = handleFactory(plan.recognitionMode)
        synchronized(lock) {
            this.plan = plan
            error = null
        }
        val result = session.start(created)
        if (result is StartResult.Refused) {
            // Created but never transferred: the session does not own it, so release it here.
            created.destroy()
        }
        return result
    }

    /**
     * Stops the session: the session destroys the recogniser exactly once, the transcript is wiped,
     * and every later callback is ignored by B05. Safe to call twice and safe to call before
     * [start].
     */
    fun stop() {
        session.stop()
    }

    /**
     * Terminal error path. B05 classifies the code (silence stays silence), destroys the recogniser,
     * and - because a dead session must not keep words - the transcript is wiped here as well.
     *
     * The error is republished through [lastError] because wiping the transcript requires
     * `session.stop()`, after which the session's own state reads `Stopped`. No restart is attempted:
     * a retry is the host's explicit decision, using [lastError]`.retryable`.
     */
    fun onError(code: Int): SpeechEvent {
        val event = session.onRecognizerError(code)
        if (event is SpeechEvent.Failed) {
            synchronized(lock) { error = SpeechSessionState.Error(event.reason, event.retryable, event.code) }
            session.stop()
        }
        return event
    }

    /** Final hypothesis from a recogniser. Text only; B05 bounds and timestamps it. */
    fun onUtterance(text: String?, confidence: Float? = null): SpeechEvent =
        session.onFinalResult(text, confidence)

    /** Interim hypothesis, forwarded so the host sees the same words the user hears. */
    fun onPartialUtterance(text: String?, confidence: Float? = null): SpeechEvent =
        session.onPartialResult(text, confidence)

    /** Never prints speech, only lifecycle facts. */
    override fun toString(): String = "SpeechSessionCoordinator(" +
        "active=${isActive}, " +
        "mode=${currentPlan?.recognitionMode}, " +
        "state=${session.state}, " +
        "utterances=${session.transcript().size}, " +
        "error=${lastError?.reason})"

    private fun buildPlan(
        report: SpeechCapabilityReport,
        mode: SpeechRecognitionMode,
        languageTag: String,
    ): SpeechSessionPlan {
        val onDevice = mode == SpeechRecognitionMode.OnDevice
        val limitations = if (onDevice) {
            listOf(
                SpeechPlanCopy.audibleSpeechOnly,
                SpeechCopy.onDeviceReady,
                SpeechCopy.onDeviceNotVerified,
                SpeechPlanCopy.audioNotRetained,
            )
        } else {
            listOfNotNull(
                SpeechPlanCopy.audibleSpeechOnly,
                SpeechCopy.consentAskCloudPossible,
                if (report.degraded) degradedLine(report) else null,
                SpeechPlanCopy.audioNotRetained,
            )
        }
        return SpeechSessionPlan(
            recognitionMode = mode,
            disclosure = if (onDevice) {
                RecognitionDisclosure.ON_DEVICE_UNVERIFIED
            } else {
                RecognitionDisclosure.MAY_USE_NETWORK
            },
            languageTag = languageTag,
            offlineVerified = onDevice,
            cloudMayBeUsed = report.cloudMayBeUsed,
            limitations = limitations,
        )
    }

    private fun degradedLine(report: SpeechCapabilityReport): BilingualCopy =
        if (report.has(CapabilityReason.HiInLanguageUnsupported)) {
            SpeechCopy.languageNotConfirmed
        } else {
            SpeechCopy.hindiModelMissing
        }
}

// ---------------------------------------------------------------------------------------------
// Android adapter (compile-only; NOT run on a device or emulator in this package)
// ---------------------------------------------------------------------------------------------

/** What one Call Shield start attempt did. */
sealed interface SpeechRunOutcome {

    /** The session is listening through [plan]. */
    data class Running(val plan: SpeechSessionPlan) : SpeechRunOutcome

    /** No recogniser was created and none should be. Show [copy]. */
    data class Blocked(val reason: SpeechBlockReason, val copy: SpeechBlockCopy) : SpeechRunOutcome

    /** The core allowed it but the session refused; no recogniser is left alive. */
    data class Refused(val plan: SpeechSessionPlan, val reason: RefusalReason) : SpeechRunOutcome
}

/**
 * ## UNMEASURED - compile-only, never run on a device or emulator in this package.
 *
 * `android.speech.*` is a compile-time stub in the JVM harness (`android.jar` bodies throw), so this
 * class has no unit test of its own: [AndroidSpeechRecognizerAdapterTest] covers the core above.
 * Nothing about this class is measured - not the probe results, not `hi-IN` availability, not
 * whether the on-device recogniser works offline on any real phone.
 *
 * It is deliberately thin: it reads the platform facts B04 needs, builds a report through the real
 * rules, and delegates every decision to [SpeechSessionCoordinator]. A recogniser object is created
 * only from the handle factory, which the core invokes after a [SpeechStartOutcome.Ready] outcome -
 * a blocked start therefore cannot touch `SpeechRecognizer` at all.
 *
 * The platform reads that can fail - recognition-service availability, on-device availability, the
 * `RECORD_AUDIO` grant, recogniser construction - are wrapped, so a platform failure becomes an
 * honest unavailable / degraded state instead of a crash. No exception message here can carry
 * speech: the only strings that reach this class are a language tag and enum names.
 *
 * Call Shield remains visible, user-started microphone analysis of audible speech. This class cannot
 * capture the remote side of a cellular/VoIP call and does not try.
 */
class AndroidSpeechRecognizerAdapter(
    private val context: Context,
    private val session: CallSpeechSession,
    private val languageTag: String = SpeechLanguage.DEFAULT,
    private val micPermissionHint: MicPermission = MicPermission.Unknown,
    private val recognizerFactory: (Context, SpeechRecognitionMode) -> SpeechRecognizer? =
        ::createPlatformRecognizer,
) {

    /**
     * The report the handle factory builds its handle from. Seeded by a real probe at construction
     * (a probe creates no recogniser) and refreshed by [begin].
     */
    @Volatile
    private var report: SpeechCapabilityReport =
        SpeechCapabilityRules.evaluate(SpeechCapabilityProbe.probe(context, observedMicPermission()), languageTag)

    private val coordinator = SpeechSessionCoordinator(session) { _ ->
        // The mode parameter is informational: the handle re-derives it from the same report, so a
        // caller cannot hand it a mode the report does not support.
        AndroidSpeechRecognizerHandle(context, session, report, languageTag, recognizerFactory)
    }

    /** True while the session this adapter drives is live (`Starting` / `Listening`). */
    val isActive: Boolean
        get() = coordinator.isActive

    /** The plan of the last attempt, for the screen to keep showing the disclosure. */
    val currentPlan: SpeechSessionPlan?
        get() = coordinator.currentPlan

    /** The last terminal recogniser error, if any. */
    val lastError: SpeechSessionState.Error?
        get() = coordinator.lastError

    /**
     * Capability snapshot as the UI should read it: probe first, then the real rules. Never starts
     * recognition and never opens a socket.
     */
    fun probeReport(): SpeechCapabilityReport {
        val probed = SpeechCapabilityRules.evaluate(
            SpeechCapabilityProbe.probe(context, observedMicPermission()),
            languageTag,
        )
        report = probed
        return probed
    }

    /**
     * Probes, decides and (only if the decision is `Ready`) starts one session.
     *
     * This is the single entry point on purpose: there is no way to ask this class for a recogniser
     * without the capability and consent checks having run first.
     */
    fun begin(consent: SpeechConsent): SpeechRunOutcome {
        val probed = probeReport()
        return when (val outcome = coordinator.prepare(consent, probed, languageTag)) {
            is SpeechStartOutcome.Blocked -> SpeechRunOutcome.Blocked(outcome.reason, outcome.copy)

            is SpeechStartOutcome.Ready -> when (val started = coordinator.start(outcome.plan)) {
                is StartResult.Started -> SpeechRunOutcome.Running(outcome.plan)
                is StartResult.Refused -> SpeechRunOutcome.Refused(outcome.plan, started.reason)
            }
        }
    }

    /** Stops and releases the recogniser and wipes the transcript. Safe before [begin], twice, etc. */
    fun stop() = coordinator.stop()

    /** Forwards a platform error code; see [SpeechSessionCoordinator.onError] for the terminal rule. */
    fun onError(code: Int): SpeechEvent = coordinator.onError(code)

    /** Forwards a final hypothesis. Text only; nothing is logged or persisted. */
    fun onUtterance(text: String?, confidence: Float? = null): SpeechEvent =
        coordinator.onUtterance(text, confidence)

    /** Forwards an interim hypothesis. */
    fun onPartialUtterance(text: String?, confidence: Float? = null): SpeechEvent =
        coordinator.onPartialUtterance(text, confidence)

    override fun toString(): String = "AndroidSpeechRecognizerAdapter(${coordinator})"

    /**
     * Read-only microphone state. The Activity is the only place that can tell Denied from
     * PermanentlyDenied (`shouldShowRequestPermissionRationale`), so it passes that in
     * [micPermissionHint]; this fallback only answers "granted or not".
     */
    private fun observedMicPermission(): MicPermission = when (micPermissionHint) {
        MicPermission.Unknown -> if (hasRecordAudio()) MicPermission.Granted else MicPermission.Denied
        else -> micPermissionHint
    }

    private fun hasRecordAudio(): Boolean = runCatching {
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    companion object {

        /**
         * The only place a platform recogniser is constructed. `createOnDeviceSpeechRecognizer` is
         * API 31+; below that the on-device mode is not requested, and B04 never reports it anyway.
         */
        private fun createPlatformRecognizer(
            context: Context,
            mode: SpeechRecognitionMode,
        ): SpeechRecognizer? = runCatching {
            when (mode) {
                SpeechRecognitionMode.OnDevice -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                } else {
                    null
                }

                SpeechRecognitionMode.DefaultMayUseNetwork ->
                    SpeechRecognizer.createSpeechRecognizer(context)
            }
        }.getOrNull()
    }
}
