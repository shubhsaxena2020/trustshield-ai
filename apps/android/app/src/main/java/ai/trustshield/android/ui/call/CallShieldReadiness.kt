/*
 * P3-12 - the single Call Shield readiness / limits decision point (issue #91).
 *
 * ## Why this file exists
 * The Call Shield screen could render "listening" or nothing at all. A user whose microphone was
 * denied, whose phone has no Hindi recognition service, whose session was interrupted by another
 * app, or whose audio may leave the device got no honest explanation. This file is the ONE place
 * that turns those facts into a state the screen renders: one enum, one honest sentence, the exact
 * limitation lines, and the actions that actually unblock the user.
 *
 * ## Shape
 * Pure Kotlin. No `android.*`, no Compose, no Context, no clock, no coroutines, no I/O and no
 * network: the decision is a pure function of (consent, B04 capability report, microphone
 * permission, interruption, phase). It is exercised on the JVM by `CallShieldReadinessTest`.
 *
 * Nothing here re-decides what another package owns:
 *  - B04 (`input/speech/SpeechCapability.kt`) owns the capability probe and its reasons;
 *  - B04 (`SpeechConsent.kt`) owns the consent record and `ConsentGate`;
 *  - P3-09 (`input/speech/AndroidSpeechRecognizerAdapter.kt`) owns the recogniser mode, the
 *    disclosure and the session coordination;
 *  - P3-10 (`ui/call/CallShieldHost.kt`) owns the live session and produces `CallUiState`;
 *  - P3-11 owns the demo script runner - this file only *offers* it and never drives it.
 *
 * ## Precedence (documented on purpose, and asserted by the tests)
 *  1. **Interruption first.** A live break is the newest fact and the only one that invalidates a
 *     previous "ready": the microphone is already closed, so reporting READY (or LISTENING) would
 *     be a false claim. A mid-session interruption therefore produces `INTERRUPTED` even when
 *     consent, permission and capability are all perfect. The host clears `Interruption` back to
 *     `None` when it starts a new session.
 *  2. **STOPPED phase.** Terminal for that session; offers a restart.
 *  3. **Demo requested.** An explicit mode: no live session is offered from here.
 *  4. **No live path** (`Unavailable` / `MissingService`, or neither an on-device nor a network
 *     route). Checked before consent so the app never asks for permission on a phone that cannot
 *     run speech at all - the same order `ConsentGate` uses.
 *  5. **Consent.** Nothing is captured before the user grants it.
 *  6. **Microphone.** Not asked / denied / permanently denied - the last one only when B04 says so,
 *     because the screen's `MicPermission` cannot express the difference.
 *  7. **Missing Hindi model.** The on-device route exists but the model is absent, so a live session
 *     would fall back to a network route; the app refuses that quietly and offers the demo instead.
 *  8. **Ready.** On-device (`READY_ON_DEVICE`) or network-capable (`READY_MAY_USE_NETWORK`).
 *
 * ## Honesty rules encoded here
 *  - `isListening` is derived from a genuinely ready state AND `SessionPhase.LISTENING`. Consent,
 *    or a granted permission, never makes it true.
 *  - `ON_DEVICE_CLAIMED_BY_OS` is a claim about what the OS *reported*, never a measured offline
 *    run: the on-device copy says the offline path was not measured on this device.
 *  - `MAY_LEAVE_DEVICE` copy says audio may leave the device, and never uses the words
 *    "on-device", "private" or "offline".
 *  - Every report carries the scope limit (only speech that reaches the phone microphone, only
 *    while the app is visible; the other side of a phone call is NOT captured) and the voice-clone
 *    limit ("awaaz asli hai ya nakli, yeh check nahi hota"). No report claims deepfake detection.
 *  - No copy line contains a digit, an OTP/PIN, a phone number or a UPI id, and `toString()`
 *    prints state names only - there is no transcript, no quote and no speech in this file.
 */

package ai.trustshield.android.ui.call

import ai.trustshield.android.input.speech.BilingualCopy
import ai.trustshield.android.input.speech.CapabilityReason
import ai.trustshield.android.input.speech.SpeechCapabilityReport
import ai.trustshield.android.input.speech.SpeechConsentState

// -------------------------------------------------------------------------------------------------
// Vocabulary
// -------------------------------------------------------------------------------------------------

/**
 * Why a session stopped mid-flight, as reported by the host.
 *
 * Every non-`None` value forces [ReadinessState.INTERRUPTED]; `None` means "no interruption". The
 * reasons are kept apart because they need different sentences: a revoked permission is the user's
 * to re-grant, a lost audio focus is another app's doing, and a recreated process is Android's.
 */
enum class Interruption {
    None,
    AudioFocusLost,
    RecognizerBusy,
    PermissionRevokedDuringSession,
    ProcessRecreated,
    RecognizerError,
}

/**
 * One state per real condition on the screen. Eleven values, no "unknown" dumping ground: a state
 * that nobody can produce would be a lie of a different kind, so each one is reachable and tested.
 */
enum class ReadinessState {
    /** On-device recognition is reported available and everything else is in place. */
    READY_ON_DEVICE,

    /** A recognition path exists, but audio may leave the device. */
    READY_MAY_USE_NETWORK,

    /** Capability is fine; the user has not allowed Call Shield to listen yet. */
    NEEDS_CONSENT,

    /** The microphone permission was never requested. */
    MIC_PERMISSION_NOT_ASKED,

    /** The microphone permission was refused (the system prompt can still be shown). */
    MIC_PERMISSION_DENIED,

    /** Android has permanently blocked the microphone; only the app's settings page can fix it. */
    MIC_PERMISSION_PERMANENTLY_DENIED,

    /** No speech recognition service, or an Android version that cannot run one. */
    RECOGNIZER_UNAVAILABLE,

    /** The on-device route exists but the Hindi model is missing, so live would use the network. */
    ON_DEVICE_MODEL_MISSING,

    /** The session stopped because of an interruption. */
    INTERRUPTED,

    /** The user stopped the session; the microphone is off. */
    STOPPED,

    /** The screen is on the microphone-free demo script. */
    DEMO_ONLY;

    /** True only for the two states where the microphone may be opened right now. */
    val canStartLiveSession: Boolean
        get() = when (this) {
            READY_ON_DEVICE, READY_MAY_USE_NETWORK -> true
            NEEDS_CONSENT,
            MIC_PERMISSION_NOT_ASKED,
            MIC_PERMISSION_DENIED,
            MIC_PERMISSION_PERMANENTLY_DENIED,
            RECOGNIZER_UNAVAILABLE,
            ON_DEVICE_MODEL_MISSING,
            INTERRUPTED,
            STOPPED,
            DEMO_ONLY,
            -> false
        }
}

/**
 * Where the audio of a live session goes, in the only three shapes the app can honestly describe.
 *
 * [ON_DEVICE_CLAIMED_BY_OS] is what the operating system reported - not a measurement by this app.
 */
enum class SpeechPrivacyMode {
    ON_DEVICE_CLAIMED_BY_OS,
    MAY_LEAVE_DEVICE,
    UNKNOWN,
}

// -------------------------------------------------------------------------------------------------
// Input
// -------------------------------------------------------------------------------------------------

/**
 * Everything the decision needs. No text, no audio, no clock: enums and B04's report only.
 *
 * @param report B04's capability report for the selected language.
 * @param consent the persisted consent record's state.
 * @param micPermission the LIVE microphone value the screen observes. This is the truth for
 *   "granted or not"; the report can only add the permanently-denied nuance, so a probe made before
 *   the user granted does not dead-end them.
 * @param interruption why a running session stopped, or [Interruption.None].
 * @param demoAvailable whether the microphone-free demo script is offered on this build.
 * @param phase the screen's session phase; only `LISTENING` can ever be reported as listening.
 * @param demoRequested true when the screen has explicitly switched to the demo script, which
 *   produces [ReadinessState.DEMO_ONLY] and never a live session.
 */
data class ReadinessInput(
    val report: SpeechCapabilityReport,
    val consent: SpeechConsentState = SpeechConsentState.NotAsked,
    val micPermission: MicPermission = MicPermission.UNKNOWN,
    val interruption: Interruption = Interruption.None,
    val demoAvailable: Boolean = true,
    val phase: SessionPhase = SessionPhase.IDLE,
    val demoRequested: Boolean = false,
)

// -------------------------------------------------------------------------------------------------
// Output
// -------------------------------------------------------------------------------------------------

/**
 * What the screen renders: one state, the two yes/no answers, the disclosure, the privacy mode, and
 * the bilingual copy with its actions and the limitations that must always be visible.
 *
 * @param disclosure the existing `RecognitionDisclosure` value (B04 -> screen vocabulary).
 * @param privacyMode what the live path would do with the audio.
 * @param limitations shown in order; always contains the scope, storage and voice-clone limits.
 * @param phase the phase this report was decided for, so [isListening] cannot be re-derived wrongly.
 */
data class ReadinessReport(
    val state: ReadinessState,
    val canStartLiveSession: Boolean,
    val canUseDemoScript: Boolean,
    val disclosure: RecognitionDisclosure,
    val privacyMode: SpeechPrivacyMode,
    val headline: BilingualCopy,
    val body: BilingualCopy,
    val actions: List<BilingualCopy>,
    val limitations: List<BilingualCopy>,
    val phase: SessionPhase = SessionPhase.IDLE,
    val interruption: Interruption = Interruption.None,
) {

    /**
     * True only when the state genuinely allows listening AND the screen is in the listening phase.
     * Consent, a granted microphone or a finished start request are never enough on their own.
     */
    val isListening: Boolean
        get() = phase == SessionPhase.LISTENING && state.canStartLiveSession

    /** Every line of this report in both languages, in display order. */
    val allCopy: List<BilingualCopy>
        get() = listOf(headline, body) + actions + limitations

    /** State names only: no copy text, no speech, and no digits. Safe to print. */
    override fun toString(): String = "ReadinessReport(" +
        "state=$state, " +
        "canStartLiveSession=$canStartLiveSession, " +
        "canUseDemoScript=$canUseDemoScript, " +
        "disclosure=$disclosure, " +
        "privacyMode=$privacyMode, " +
        "phase=$phase, " +
        "interruption=$interruption, " +
        "actions=${actions.size}, " +
        "limitations=${limitations.size})"
}

// -------------------------------------------------------------------------------------------------
// The decision
// -------------------------------------------------------------------------------------------------

/** The two live routes this app can actually run. `null` (no route) is handled separately. */
private enum class LivePath { OnDevice, Cloud }

/** The single readiness/limits decision point. No state, no side effects, deterministic. */
object CallShieldReadiness {

    /**
     * Decides one readiness report. See the file header for the documented precedence order.
     *
     * The function is total: every combination of inputs produces exactly one [ReadinessState], and
     * no branch falls through to a default.
     */
    fun evaluate(input: ReadinessInput): ReadinessReport {
        val path = livePathFor(input.report)
        val state = readinessState(input, path)
        val listening = state.canStartLiveSession && input.phase == SessionPhase.LISTENING
        val limitations = buildList {
            addAll(ReadinessCopy.baseLimitations)
            ReadinessCopy.limitation(state)?.let { add(it) }
        }
        return ReadinessReport(
            state = state,
            canStartLiveSession = state.canStartLiveSession,
            // The demo script is microphone-free, so it is available in every state - except while a
            // real live session is running, where two engines would compete for the same screen.
            canUseDemoScript = input.demoAvailable && !listening,
            disclosure = input.report.toCallRecognitionDisclosure(),
            privacyMode = privacyModeFor(input.report),
            headline = ReadinessCopy.headline(state),
            body = ReadinessCopy.body(state, input.interruption, input.report),
            actions = ReadinessCopy.actions(state, input.demoAvailable),
            limitations = limitations,
            phase = input.phase,
            interruption = input.interruption,
        )
    }

    /**
     * Bridge from the state the Call Shield screen already holds. Useful for the screen wiring, and
     * deliberately lossy in one documented way: `CallUiState.consent` cannot distinguish "never
     * asked" from "denied", so both read as not-yet-given and produce [ReadinessState.NEEDS_CONSENT].
     * A host that knows the real consent record should pass [ReadinessInput] directly.
     */
    fun fromState(
        state: CallUiState,
        report: SpeechCapabilityReport,
        interruption: Interruption = Interruption.None,
        demoAvailable: Boolean = true,
        demoRequested: Boolean = false,
    ): ReadinessInput = ReadinessInput(
        report = report,
        consent = when (state.consent) {
            ConsentState.GIVEN -> SpeechConsentState.Granted
            ConsentState.NOT_GIVEN -> SpeechConsentState.NotAsked
        },
        micPermission = state.permission,
        interruption = interruption,
        demoAvailable = demoAvailable,
        phase = state.phase,
        demoRequested = demoRequested,
    )

    // ---------------------------------------------------------------- internals

    /**
     * The precedence chain. Each [ReadinessState] literal appears exactly once, so a state that
     * stops being produced fails the coverage test instead of quietly disappearing.
     */
    private fun readinessState(input: ReadinessInput, path: LivePath?): ReadinessState {
        // 1. A live break invalidates every earlier conclusion, ready or not.
        if (input.interruption != Interruption.None) return ReadinessState.INTERRUPTED
        // 2. A stopped session is finished for this run.
        if (input.phase == SessionPhase.STOPPED) return ReadinessState.STOPPED
        // 3. An explicit demo mode never opens the microphone.
        if (input.demoRequested) return ReadinessState.DEMO_ONLY
        // 4. No route at all: asking for consent first would be a dead end.
        if (path == null) return ReadinessState.RECOGNIZER_UNAVAILABLE
        // 5. Consent gate.
        if (input.consent != SpeechConsentState.Granted) return ReadinessState.NEEDS_CONSENT
        // 6. Microphone, then the path that is left.
        return when (input.micPermission) {
            MicPermission.UNKNOWN -> ReadinessState.MIC_PERMISSION_NOT_ASKED
            MicPermission.DENIED -> when {
                input.report.has(CapabilityReason.MicPermissionPermanentlyDenied) ->
                    ReadinessState.MIC_PERMISSION_PERMANENTLY_DENIED

                else -> ReadinessState.MIC_PERMISSION_DENIED
            }

            MicPermission.GRANTED -> when (path) {
                LivePath.OnDevice -> ReadinessState.READY_ON_DEVICE
                LivePath.Cloud -> when {
                    // The on-device route exists but its Hindi model is missing: a live session here
                    // would silently take the network route, so the app offers the demo instead.
                    input.report.has(CapabilityReason.HiInModelMissing) ->
                        ReadinessState.ON_DEVICE_MODEL_MISSING

                    else -> ReadinessState.READY_MAY_USE_NETWORK
                }
            }
        }
    }

    /**
     * B04's flags, read as the one route a live session could use. `offlineCapable` wins when both
     * are set, which the real rules never do; `null` means "no recognition path on this phone".
     */
    private fun livePathFor(report: SpeechCapabilityReport): LivePath? = when {
        report.offlineCapable -> LivePath.OnDevice
        report.cloudMayBeUsed -> LivePath.Cloud
        else -> null
    }

    private fun privacyModeFor(report: SpeechCapabilityReport): SpeechPrivacyMode = when {
        report.offlineCapable -> SpeechPrivacyMode.ON_DEVICE_CLAIMED_BY_OS
        report.cloudMayBeUsed -> SpeechPrivacyMode.MAY_LEAVE_DEVICE
        else -> SpeechPrivacyMode.UNKNOWN
    }
}

// -------------------------------------------------------------------------------------------------
// Copy
// -------------------------------------------------------------------------------------------------

/**
 * Every sentence Call Shield readiness can show, in Hinglish and English.
 *
 * Rules enforced by `CallShieldReadinessTest`:
 *  - no line contains a digit (so no OTP, PIN, card, phone number or amount can appear);
 *  - no line promises safety, certainty or privacy, and no line claims the other side of a phone
 *    call is captured or that a voice is checked for cloning;
 *  - no cloud line contains the words "on-device", "private" or "offline";
 *  - Hinglish and English are never the same string.
 */
internal object ReadinessCopy {

    // ---- limitations every report carries -------------------------------------------------------

    /** The scope: audible microphone speech, visible app, and NOT the other side of a phone call. */
    val scope = BilingualCopy(
        hinglish = "Sirf woh awaaz jo phone ke mic tak pahunchti hai, aur sirf jab aap khud Call Shield " +
            "khol kar shuru karte ho, woh check hoti hai. App phone call ke doosre taraf ki awaaz nahi pakadta.",
        english = "Only speech that reaches the phone microphone, and only when you open Call Shield " +
            "and start it yourself, is checked. The app does not capture the other side of a phone call.",
    )

    /** The voice-clone limit, worded exactly as the report asks for it. */
    val noCloneCheck = BilingualCopy(
        hinglish = "Awaaz asli hai ya nakli, yeh check nahi hota.",
        english = "Whether a voice is real or cloned is not checked.",
    )

    /** Structural privacy: no audio is stored, recognised text dies with the session. */
    val nothingStored = BilingualCopy(
        hinglish = "Awaaz save nahi hoti. Pehchana gaya text sirf is session ki memory mein rehta hai " +
            "aur session band hone par hata diya jaata hai.",
        english = "Audio is not saved. Recognised text stays in this session's memory only and is " +
            "cleared when the session ends.",
    )

    // ---- state-specific limitations -------------------------------------------------------------

    val onDeviceUnmeasured = BilingualCopy(
        hinglish = "Recogniser phone ke andar bataya gaya hai, par offline chalna is device par naapa " +
            "nahi gaya.",
        english = "The recogniser is reported inside the phone, but running offline has not been " +
            "measured on this device.",
    )

    val mayLeaveDevice = BilingualCopy(
        hinglish = "Recogniser internet use kar sakta hai, isliye kuch awaaz device se bahar jaa sakti hai.",
        english = "The recogniser may use the internet, so some audio may leave the device.",
    )

    val noLiveAlert = BilingualCopy(
        hinglish = "Speech service ke bina live alert nahi milega. Demo script app ke andar ki lines " +
            "dikhata hai, asli call par kuch nahi chalta.",
        english = "Without a speech service there is no live alert. The demo script plays lines from " +
            "inside the app; nothing runs on a real call.",
    )

    val consentGate = BilingualCopy(
        hinglish = "Anumati dene se pehle mic band rehta hai; kuch suna nahi jaata.",
        english = "Until you allow it the microphone stays closed; nothing is heard.",
    )

    val micClosed = BilingualCopy(
        hinglish = "Mic band hai, isliye app kuch sun nahi sakta aur kuch record nahi hota.",
        english = "The microphone is closed, so the app cannot hear anything and nothing is recorded.",
    )

    val interruptedCleared = BilingualCopy(
        hinglish = "Session rukne par pehchana gaya text hata diya jaata hai; koi audio save nahi hota.",
        english = "When the session stops the recognised text is cleared; no audio is saved.",
    )

    val stoppedMicOff = BilingualCopy(
        hinglish = "Stop ke baad mic band rehta hai; app background mein sunta nahi.",
        english = "After Stop the microphone stays off; the app does not listen in the background.",
    )

    val demoIsScripted = BilingualCopy(
        hinglish = "Demo sirf app ke andar ki scripted lines par chalta hai; woh live sunna nahi hai.",
        english = "The demo only works on scripted lines inside the app; it is not live listening.",
    )

    // ---- actions --------------------------------------------------------------------------------

    val startSession = BilingualCopy(
        hinglish = "Session shuru karein",
        english = "Start session",
    )

    val startNewSession = BilingualCopy(
        hinglish = "Nayi session shuru karein",
        english = "Start a new session",
    )

    val giveConsent = BilingualCopy(
        hinglish = "Main samajh gaya, anumati deta hoon",
        english = "I understand, give permission",
    )

    /** The system permission prompt route - only offered when Android can still show it. */
    val allowMicPermission = BilingualCopy(
        hinglish = "Mic ki anumati dein",
        english = "Allow the microphone",
    )

    /** The settings route - the only one left once Android has blocked the permission for good. */
    val openAppSettings = BilingualCopy(
        hinglish = "App settings kholein",
        english = "Open app settings",
    )

    val openSpeechSettings = BilingualCopy(
        hinglish = "Speech settings kholein",
        english = "Open speech settings",
    )

    val useDemoScript = BilingualCopy(
        hinglish = "Demo script chalaayein",
        english = "Run the demo script",
    )

    val retrySession = BilingualCopy(
        hinglish = "Dobara koshish karein",
        english = "Try again",
    )

    // ---- headlines ------------------------------------------------------------------------------

    fun headline(state: ReadinessState): BilingualCopy = when (state) {
        ReadinessState.READY_ON_DEVICE -> BilingualCopy(
            hinglish = "Taiyaar hai: recogniser phone ke andar bataya gaya",
            english = "Ready: the recogniser is reported inside the phone",
        )

        ReadinessState.READY_MAY_USE_NETWORK -> BilingualCopy(
            hinglish = "Taiyaar hai, par kuch awaaz phone se bahar jaa sakti hai",
            english = "Ready, but some audio may leave this phone",
        )

        ReadinessState.NEEDS_CONSENT -> BilingualCopy(
            hinglish = "Pehle aapki anumati chahiye",
            english = "Your permission is needed first",
        )

        ReadinessState.MIC_PERMISSION_NOT_ASKED -> BilingualCopy(
            hinglish = "Mic ki anumati abhi nahi maangi",
            english = "Microphone permission has not been asked yet",
        )

        ReadinessState.MIC_PERMISSION_DENIED -> BilingualCopy(
            hinglish = "Mic ki anumati nahi mili",
            english = "Microphone permission was not granted",
        )

        ReadinessState.MIC_PERMISSION_PERMANENTLY_DENIED -> BilingualCopy(
            hinglish = "Mic ki anumati Settings mein band hai",
            english = "Microphone access is switched off in Settings",
        )

        ReadinessState.RECOGNIZER_UNAVAILABLE -> BilingualCopy(
            hinglish = "Is phone par speech service nahi mili",
            english = "No speech service was found on this phone",
        )

        ReadinessState.ON_DEVICE_MODEL_MISSING -> BilingualCopy(
            hinglish = "Hindi ka speech model download nahi hai",
            english = "The Hindi speech model is not downloaded",
        )

        ReadinessState.INTERRUPTED -> BilingualCopy(
            hinglish = "Session ruk gaya",
            english = "The session stopped",
        )

        ReadinessState.STOPPED -> BilingualCopy(
            hinglish = "Session band hai",
            english = "Session stopped",
        )

        ReadinessState.DEMO_ONLY -> BilingualCopy(
            hinglish = "Demo script mode chalu hai",
            english = "Demo script mode is on",
        )
    }

    // ---- bodies ---------------------------------------------------------------------------------

    fun body(
        state: ReadinessState,
        interruption: Interruption,
        report: SpeechCapabilityReport,
    ): BilingualCopy = when (state) {
        ReadinessState.READY_ON_DEVICE -> BilingualCopy(
            hinglish = "Anumati, mic aur speech service teeno theek hain. Phone ke OS ne bataya ki " +
                "recogniser phone ke andar hai. Is device par offline chalna naapa nahi gaya, isliye " +
                "yeh sirf OS ka report hai. Start dabate hi sunna shuru hota hai, aur Stop dabate hi band.",
            english = "Permission, microphone and the speech service are all in place. The phone's OS " +
                "reports the recogniser works inside the phone. Running offline has not been measured on " +
                "this device, so this is only what the OS reports. Listening starts when you press Start " +
                "and stops when you press Stop.",
        )

        ReadinessState.READY_MAY_USE_NETWORK -> BilingualCopy(
            hinglish = "Anumati, mic aur speech service theek hain, lekin is phone ka built-in " +
                "recogniser internet use kar sakta hai, isliye kuch awaaz device se bahar jaa sakti hai. " +
                "Start dabate hi sunna shuru hota hai; Stop dabate hi band.",
            english = "Permission, microphone and the speech service are in place, but the built-in " +
                "recogniser on this phone may use the internet, so some audio may leave the device. " +
                "Listening starts when you press Start and stops when you press Stop.",
        )

        ReadinessState.NEEDS_CONSENT -> BilingualCopy(
            hinglish = "Call Shield tabhi sunta hai jab aap khud shuru karte ho. Anumati dene se pehle " +
                "kuch suna nahi jaata aur kuch record nahi hota. Anumati ke baad bhi session sirf Start " +
                "dabane par chalta hai.",
            english = "Call Shield only listens after you start it yourself. Nothing is heard and " +
                "nothing is recorded before you allow it. Even after you allow it, a session runs only " +
                "when you press Start.",
        )

        ReadinessState.MIC_PERMISSION_NOT_ASKED -> BilingualCopy(
            hinglish = "Android se mic ki anumati chahiye hogi. Jab tak anumati nahi milti, kuch suna " +
                "nahi jaata aur kuch record nahi hota.",
            english = "Android will ask for microphone access. Until it is granted nothing is heard and " +
                "nothing is recorded.",
        )

        ReadinessState.MIC_PERMISSION_DENIED -> BilingualCopy(
            hinglish = "Mic ki anumati nahi mili, isliye kuch suna nahi gaya aur kuch record nahi hua. " +
                "Phone ki system permission se mic allow karein, phir wapas aakar session shuru karein. " +
                "Bina anumati ke app sun nahi sakta.",
            english = "Microphone access was not granted, so nothing was heard and nothing was " +
                "recorded. Allow the microphone from the phone's system permission prompt, then come " +
                "back and start the session. Without it the app cannot listen.",
        )

        ReadinessState.MIC_PERMISSION_PERMANENTLY_DENIED -> BilingualCopy(
            hinglish = "Phone ne is app ke liye mic ki anumati permanently roki hai, isliye kuch suna " +
                "nahi gaya aur kuch record nahi hua. Fix: phone Settings > Apps > TrustShield > " +
                "Permissions > Microphone > Allow, phir app mein wapas aayein.",
            english = "The phone has permanently blocked microphone access for this app, so nothing was " +
                "heard and nothing was recorded. Fix: phone Settings > Apps > TrustShield > " +
                "Permissions > Microphone > Allow, then come back to the app.",
        )

        ReadinessState.RECOGNIZER_UNAVAILABLE -> BilingualCopy(
            hinglish = "Yeh phone ki limit hai, app ki galti nahi: Android par koi speech recognition " +
                "service nahi mili, ya is Android version par support nahi hai. Isliye live sunna band " +
                "hai aur kuch record nahi hota. Demo script mode se poora Call Shield flow chala sakte " +
                "ho; woh live mic nahi hai.",
            english = "This is a limit of the phone, not an app fault: no speech recognition service was " +
                "found on this Android phone, or this Android version does not support it. Live " +
                "listening is therefore off and nothing is being recorded. Demo script mode can still " +
                "walk you through the whole Call Shield flow; it does not use the live microphone.",
        )

        ReadinessState.ON_DEVICE_MODEL_MISSING -> BilingualCopy(
            hinglish = "Yeh phone ki limit hai: is phone par Hindi ka speech model install nahi hai, " +
                "isliye live sunna sirf internet wale recogniser se hota hai, aur us path par kuch " +
                "awaaz device se bahar jaa sakti hai. Isliye yahan live session nahi kholte. Phone ki " +
                "speech settings se Hindi download karein (agar phone offer karta hai), ya Demo script " +
                "mode chalaayein.",
            english = "This is a limit of this phone: the Hindi speech model is not installed, so live " +
                "listening would have to use the internet recogniser, and on that path some audio may " +
                "leave the device. A live session is therefore not opened here. Download Hindi from the " +
                "phone's speech settings if it offers that, or use demo script mode.",
        )

        ReadinessState.INTERRUPTED -> BilingualCopy(
            hinglish = interruptionReason(interruption).hinglish + " Ab mic band hai, kuch suna nahi " +
                "ja raha, aur session ka pehchana gaya text hata diya gaya hai. Dobara sunne ke liye " +
                "Retry dabayein.",
            english = interruptionReason(interruption).english + " The microphone is off, nothing is " +
                "being heard, and the text recognised in this session has been cleared. Press Try again " +
                "to listen once more.",
        )

        ReadinessState.STOPPED -> BilingualCopy(
            hinglish = "Mic band hai aur kuch suna nahi ja raha. Neeche ke stages sirf is session ke " +
                "natije hain, aur baat-cheet ka text hata diya gaya hai. Nayi session ke liye start " +
                "dabayein.",
            english = "The microphone is off and nothing is being heard. The stages below are this " +
                "session's outcome only, and the spoken text has been cleared. Press start to begin a " +
                "new session.",
        )

        ReadinessState.DEMO_ONLY -> BilingualCopy(
            hinglish = "Demo script mode chalu hai: live mic band hai, kuch suna nahi ja raha, aur kuch " +
                "record nahi hota. Demo sirf app ke andar ki scripted lines par chalta hai.",
            english = "Demo script mode is on: the live microphone is off, nothing is being heard, and " +
                "nothing is recorded. The demo only runs on scripted lines inside the app.",
        )
    }

    // ---- actions --------------------------------------------------------------------------------

    /**
     * The buttons for one state, in priority order. The demo script is offered wherever it is
     * available, because it is the honest fallback on a phone that cannot do live speech - except in
     * [ReadinessState.DEMO_ONLY], where it is already the active mode.
     */
    fun actions(state: ReadinessState, demoAvailable: Boolean): List<BilingualCopy> = when (state) {
        // A live session is possible; the demo is the documented alternative (HEAD_ORDERS item 4).
        ReadinessState.READY_ON_DEVICE,
        ReadinessState.READY_MAY_USE_NETWORK,
        -> listOf(startSession) + demoAction(demoAvailable)

        ReadinessState.NEEDS_CONSENT -> listOf(giveConsent) + demoAction(demoAvailable)

        ReadinessState.MIC_PERMISSION_NOT_ASKED,
        ReadinessState.MIC_PERMISSION_DENIED,
        -> listOf(allowMicPermission) + demoAction(demoAvailable)

        // Android will not show the system prompt again: only the settings page can fix this.
        ReadinessState.MIC_PERMISSION_PERMANENTLY_DENIED ->
            listOf(openAppSettings) + demoAction(demoAvailable)

        ReadinessState.RECOGNIZER_UNAVAILABLE,
        ReadinessState.ON_DEVICE_MODEL_MISSING,
        -> listOf(openSpeechSettings) + demoAction(demoAvailable)

        ReadinessState.INTERRUPTED -> listOf(retrySession) + demoAction(demoAvailable)
        ReadinessState.STOPPED -> listOf(startNewSession) + demoAction(demoAvailable)

        // Already in the demo: the only action is the demo itself, or a way back out if the demo is
        // not offered on this build at all.
        ReadinessState.DEMO_ONLY -> when {
            demoAvailable -> listOf(useDemoScript)
            else -> listOf(startNewSession)
        }
    }

    /** The extra limitation line that belongs to a state, or `null` when the base three suffice. */
    fun limitation(state: ReadinessState): BilingualCopy? = when (state) {
        ReadinessState.READY_ON_DEVICE -> onDeviceUnmeasured
        ReadinessState.READY_MAY_USE_NETWORK,
        ReadinessState.ON_DEVICE_MODEL_MISSING,
        -> mayLeaveDevice

        ReadinessState.RECOGNIZER_UNAVAILABLE -> noLiveAlert
        ReadinessState.NEEDS_CONSENT -> consentGate

        ReadinessState.MIC_PERMISSION_NOT_ASKED,
        ReadinessState.MIC_PERMISSION_DENIED,
        ReadinessState.MIC_PERMISSION_PERMANENTLY_DENIED,
        -> micClosed

        ReadinessState.INTERRUPTED -> interruptedCleared
        ReadinessState.STOPPED -> stoppedMicOff
        ReadinessState.DEMO_ONLY -> demoIsScripted
    }

    // ---- interruption reasons -------------------------------------------------------------------

    /**
     * The "why it stopped" sentence. `Interruption.None` is not a trigger - the evaluator never
     * builds an INTERRUPTED report from it - but the mapping stays total so every enum value has
     * copy, and the completeness test says so.
     */
    fun interruptionReason(interruption: Interruption): BilingualCopy = when (interruption) {
        Interruption.AudioFocusLost -> BilingualCopy(
            hinglish = "Doosri app ne audio focus le liya, isliye session ruk gaya.",
            english = "Another app took the audio focus, so the session stopped.",
        )

        Interruption.RecognizerBusy -> BilingualCopy(
            hinglish = "Speech recogniser kisi doosre kaam mein busy tha, isliye session ruk gaya.",
            english = "The speech recogniser was busy with another job, so the session stopped.",
        )

        Interruption.PermissionRevokedDuringSession -> BilingualCopy(
            hinglish = "Session chalte-chalte mic ki anumati hat gayi, isliye session ruk gaya.",
            english = "Microphone access was revoked while the session was running, so the session " +
                "stopped.",
        )

        Interruption.ProcessRecreated -> BilingualCopy(
            hinglish = "Android ne app ko dobara banaya, isliye session ruk gaya.",
            english = "Android recreated the app process, so the session stopped.",
        )

        Interruption.RecognizerError -> BilingualCopy(
            hinglish = "Speech recogniser ne error diya, isliye session ruk gaya.",
            english = "The speech recogniser reported an error, so the session stopped.",
        )

        Interruption.None -> BilingualCopy(
            hinglish = "Session ruk gaya, wajah report nahi hui.",
            english = "The session stopped and no reason was reported.",
        )
    }

    // ---- shared ---------------------------------------------------------------------------------

    /** The three lines every single report carries. */
    val baseLimitations: List<BilingualCopy> = listOf(scope, noCloneCheck, nothingStored)

    /** Every sentence above; used by the copy honesty test. */
    val all: List<BilingualCopy> = listOf(
        scope,
        noCloneCheck,
        nothingStored,
        onDeviceUnmeasured,
        mayLeaveDevice,
        noLiveAlert,
        consentGate,
        micClosed,
        interruptedCleared,
        stoppedMicOff,
        demoIsScripted,
        startSession,
        startNewSession,
        giveConsent,
        allowMicPermission,
        openAppSettings,
        openSpeechSettings,
        useDemoScript,
        retrySession,
    )

    private fun demoAction(demoAvailable: Boolean): List<BilingualCopy> =
        if (demoAvailable) listOf(useDemoScript) else emptyList()
}
