/*
 * B04 - speech capability probe for Call Shield.
 *
 * Shape: a pure-Kotlin decision core (no android.* import below the adapter at the bottom of
 * this file) plus one thin Android adapter. The core is unit tested on the JVM; the adapter
 * compiles against android.jar and is NOT JVM-tested (android.jar bodies are stubs).
 *
 * Honesty rules encoded here, taken literally from docs/android/ARCHITECTURE.md:
 *  1. On-device (offline) recognition exists only on API >= 31 (`createOnDeviceSpeechRecognizer`).
 *  2. Below API 31 the default recogniser may stream audio to a remote service, so a session is
 *     never labelled offline: offlineCapable = false and cloudMayBeUsed = true.
 *  3. `RecognizerIntent.EXTRA_PREFER_OFFLINE` is only a hint and may be ignored, so it is never
 *     read by this code and never used to derive offlineCapable. Only a version check plus an
 *     on-device recogniser plus a present hi-IN model may produce an offline claim.
 *  4. No cloud path is ever labelled "private" or "on-device". The cloud copy says audio may
 *     leave the device.
 *  5. A missing hi-IN model keeps the session possible but marks it degraded with a Hinglish reason.
 *  6. This file makes no network call of any kind.
 *
 * Call Shield analyses audible speech that the user starts with the microphone. Android does not
 * give ordinary apps reliable capture of the remote side of cellular/VoIP calls, and nothing here
 * claims otherwise.
 */

package ai.trustshield.android.input.speech

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.concurrent.Executor

/** Lowest Android version the app supports (mirrors minSdk 26). */
const val SPEECH_MIN_API_LEVEL: Int = 26

/** On-device recognition starts at Android 12 / API 31; below this a default recogniser may stream audio. */
const val ON_DEVICE_RECOGNITION_MIN_API: Int = 31

/** Language tags the app offers. hi-IN is the Call Shield default. */
object SpeechLanguage {
    const val HI_IN: String = "hi-IN"
    const val EN_IN: String = "en-IN"
    const val DEFAULT: String = HI_IN
    val selectable: List<String> = listOf(HI_IN, EN_IN)

    fun isSelectable(tag: String): Boolean = selectable.contains(tag)
}

/**
 * What the hi-IN support probe actually learned, in the only three shapes Android can honestly give.
 *
 * [NotChecked] is not a soft "unsupported": it means Android could not answer at all - the support
 * callback only exists from API 33, and a callback error is not a verdict about Hindi. Callers must
 * keep [NotChecked] and [Unsupported] apart, because "we could not ask" and "this phone says no"
 * are different claims to the user (see `ui/settings/DeviceCheck.kt`).
 */
enum class HindiLocaleSupportOutcome { Supported, Unsupported, NotChecked }

/**
 * The hi-IN support probe as a pure decision core, so the JVM tests can drive every branch without
 * a device, a recogniser or a microphone. The Android adapter at the bottom of this file only reads
 * platform facts and calls these functions; it holds no rules of its own.
 *
 * Encoded honesty rules:
 *  - Below API 33 the platform exposes no support callback, so the outcome is [NotChecked].
 *  - A missing recognition service is a real, answerable "no": [Unsupported].
 *  - A callback error reports [NotChecked]; it is never turned into [Unsupported].
 *  - This probe NEVER starts a recognition session (`startListening`). It creates a recogniser,
 *    asks a question, destroys the recogniser. `startsRecognition` is the machine-checked statement
 *    of that invariant.
 */
object HindiLocaleSupportRules {

    /** `Build.VERSION_CODES.TIRAMISU`: the first API with `checkRecognitionSupport`. */
    const val SUPPORT_CALLBACK_MIN_API: Int = 33

    /** True only when Android can answer the hi-IN question at all. */
    fun probeAllowed(apiLevel: Int): Boolean = apiLevel >= SUPPORT_CALLBACK_MIN_API

    /** Case-insensitive hi-IN match; a null/blank tag never matches. */
    fun matchesHiIn(tag: String?): Boolean =
        SpeechLanguage.HI_IN.equals(tag?.trim(), ignoreCase = true)

    /** Reads a `RecognitionSupport` answer: hi-IN in either the online or on-device list. */
    fun supportedFromLanguages(onlineLanguages: List<String>, onDeviceLanguages: List<String>): Boolean =
        (onlineLanguages + onDeviceLanguages).any { matchesHiIn(it) }

    /** A completed callback that named no hi-IN path at all. */
    fun outcomeForSupport(supported: Boolean): HindiLocaleSupportOutcome =
        if (supported) HindiLocaleSupportOutcome.Supported else HindiLocaleSupportOutcome.Unsupported

    /** A callback error, or a recogniser that could not be created: Android did not answer. */
    fun outcomeForError(): HindiLocaleSupportOutcome = HindiLocaleSupportOutcome.NotChecked

    /** No recognition service exists: the phone can answer this, and the answer is "no". */
    fun outcomeForMissingService(): HindiLocaleSupportOutcome = HindiLocaleSupportOutcome.Unsupported

    /** The outcome when the probe is not allowed to run on this Android version: nothing was asked. */
    fun outcomeWhenNotMeasurable(): HindiLocaleSupportOutcome = HindiLocaleSupportOutcome.NotChecked

    /**
     * The invariant this file is built on: the support probe never starts recognition, on any API
     * level. `DeviceCheckFallbackTest` and `HindiRecognitionCapabilityTest` both assert it.
     */
    @Suppress("UNUSED_PARAMETER")
    fun startsRecognition(apiLevel: Int): Boolean = false

    /** The nullable form the UI callback uses: null means [HindiLocaleSupportOutcome.NotChecked]. */
    fun toNullable(outcome: HindiLocaleSupportOutcome): Boolean? = when (outcome) {
        HindiLocaleSupportOutcome.Supported -> true
        HindiLocaleSupportOutcome.Unsupported -> false
        HindiLocaleSupportOutcome.NotChecked -> null
    }
}

/** Microphone permission as observed by the caller. Never inferred from audio or text. */
enum class MicPermission { Unknown, Granted, Denied, PermanentlyDenied }

/**
 * Everything the decision core needs to answer "can this phone do Hindi speech right now?".
 * Deliberately free of android.* types so it can be built and tested on a plain JVM.
 *
 * @param apiLevel `Build.VERSION.SDK_INT`.
 * @param recognizerServiceAvailable result of `SpeechRecognizer.isRecognitionAvailable`.
 * @param onDeviceAvailable result of `isOnDeviceRecognitionAvailable`, only meaningful on API >= 31.
 * @param hiInModelDownloaded true only when the hi-IN on-device model is actually installed.
 * @param micPermission record microphone state; `Unknown` means "not asked yet".
 * @param hiInSupported what the available path reports for hi-IN support. Defaults to true because
 *   "unsupported" is the exception; a false value always degrades the report.
 */
data class SpeechEnvironment(
    val apiLevel: Int,
    val recognizerServiceAvailable: Boolean,
    val onDeviceAvailable: Boolean,
    val hiInModelDownloaded: Boolean,
    val micPermission: MicPermission,
    val hiInSupported: Boolean = true,
)

/** Outcome of the capability probe. */
enum class SpeechStatus {
    /** Android version or platform cannot run speech recognition at all. */
    Unavailable,

    /** No recognition service is installed or enabled. */
    MissingService,

    /** On-device recognition is usable for the selected language right now. */
    OnDeviceReady,

    /** On-device recognition exists but the hi-IN model is missing or hi-IN is unsupported. */
    OnDeviceUnavailable,

    /** A recognition path exists but only via the network-capable default recogniser. */
    CloudOnly,

    /** Recognition may be possible but microphone permission is not granted. */
    NoMicPermission,
}

/** Machine-readable reasons, so the UI can pick copy without parsing strings. */
enum class CapabilityReason {
    ApiLevelUnsupported,
    OnDeviceRequiresApi31,
    OnDeviceNotAvailable,
    HiInModelMissing,
    HiInLanguageUnsupported,
    LanguageNotSelectable,
    DefaultRecognizerMayUseNetwork,
    OnDeviceNotMeasured,
    NoRecognizerService,
    MicPermissionNotAsked,
    MicPermissionDenied,
    MicPermissionPermanentlyDenied,
}

/**
 * Honest capability report.
 *
 * @param offlineCapable true only when an on-device recogniser and the hi-IN model are both present
 *   on API >= 31. Never derived from `EXTRA_PREFER_OFFLINE`.
 * @param cloudMayBeUsed true when a recognition path on this device may send audio to a remote
 *   service. Never presented to the user as private or on-device.
 * @param degraded true when the session can still run but with a known limitation the user must see.
 */
data class SpeechCapabilityReport(
    val status: SpeechStatus,
    val languageTag: String,
    val offlineCapable: Boolean,
    val cloudMayBeUsed: Boolean,
    val degraded: Boolean,
    val reasons: List<CapabilityReason>,
) {
    /** True when a session could run once consent and permission are in place. */
    val sessionPossible: Boolean
        get() = offlineCapable || cloudMayBeUsed

    fun has(reason: CapabilityReason): Boolean = reasons.contains(reason)
}

/** A short user-facing line in both app languages. No certainty language anywhere. */
data class BilingualCopy(val hinglish: String, val english: String)

/**
 * Copy bank. Every line is short, hedged and non-absolute: the app says what it can verify and no
 * more. None of these strings is allowed to promise privacy it cannot measure.
 */
object SpeechCopy {
    val consentAsk = BilingualCopy(
        hinglish = "Shield sirf tab sunta hai jab aap khud shuru karte ho. Awaaz save nahi hoti. Aap kabhi bhi rok sakte ho.",
        english = "Shield listens only when you start it. Audio is not saved. You can stop at any time.",
    )
    val consentAskOnDevice = BilingualCopy(
        hinglish = "Is phone par awaaz phone ke andar process hoti hai. Recording save nahi hoti.",
        english = "On this phone, speech is processed on the device. No recording is saved.",
    )
    val consentAskCloudPossible = BilingualCopy(
        hinglish = "Is phone par built-in recognition internet use kar sakti hai, isliye kuch awaaz device se bahar jaa sakti hai.",
        english = "The built-in recogniser on this phone may use the internet, so some audio may leave the device.",
    )
    val unclearAudio = BilingualCopy(
        hinglish = "Awaaz saaf nahi",
        english = "Audio not clear",
    )
    val needsInternet = BilingualCopy(
        hinglish = "On-device nahi, internet chahiye",
        english = "Not on-device; needs internet",
    )
    val micPermissionMissing = BilingualCopy(
        hinglish = "Mic permission nahi mili",
        english = "Microphone permission not given",
    )
    val micPermissionNotAsked = BilingualCopy(
        hinglish = "Mic permission abhi nahi li",
        english = "Microphone permission not asked yet",
    )
    val recognizerUnavailable = BilingualCopy(
        hinglish = "Is phone par speech recognition nahi chal raha",
        english = "Speech recognition is not available on this phone",
    )
    val hindiModelMissing = BilingualCopy(
        hinglish = "Hindi model download nahi hai, internet wala path chalega",
        english = "Hindi model is not downloaded; the internet path will be used",
    )
    val onDeviceReady = BilingualCopy(
        hinglish = "Is phone par on-device recognition mila",
        english = "On-device recognition is available on this phone",
    )
    val onDeviceNotVerified = BilingualCopy(
        hinglish = "Is device par offline test abhi nahi hua",
        english = "The offline path has not been tested on this device yet",
    )
    val languageNotConfirmed = BilingualCopy(
        hinglish = "Is phone par hi-IN support confirm nahi hua",
        english = "hi-IN support is not confirmed on this phone",
    )
    val speechUnavailable = BilingualCopy(
        hinglish = "Is Android version par speech recognition support nahi hai",
        english = "Speech recognition is not supported on this Android version",
    )
    val consentNeeded = BilingualCopy(
        hinglish = "Pehle app ki permission chahiye",
        english = "App permission is needed first",
    )
    val consentNotGiven = BilingualCopy(
        hinglish = "Aapne permission nahi di",
        english = "You have not given permission",
    )
    val consentWithdrawn = BilingualCopy(
        hinglish = "Aapne permission wapas li hai",
        english = "You withdrew permission",
    )

    /** Every line the UI can show; used by the copy honesty test. */
    val all: List<BilingualCopy> = listOf(
        consentAsk, consentAskOnDevice, consentAskCloudPossible, unclearAudio, needsInternet,
        micPermissionMissing, micPermissionNotAsked, recognizerUnavailable, hindiModelMissing,
        onDeviceReady, onDeviceNotVerified, languageNotConfirmed, speechUnavailable,
        consentNeeded, consentNotGiven, consentWithdrawn,
    )
}

/**
 * The whole decision, as a pure function. No IO, no clock, no android.* - this is what the tests
 * exercise, and it is the only place a capability label comes from.
 */
object SpeechCapabilityRules {

    fun evaluate(
        env: SpeechEnvironment,
        languageTag: String = SpeechLanguage.DEFAULT,
    ): SpeechCapabilityReport {
        val reasons = mutableListOf<CapabilityReason>()

        if (env.apiLevel < SPEECH_MIN_API_LEVEL) {
            reasons += CapabilityReason.ApiLevelUnsupported
            return SpeechCapabilityReport(
                status = SpeechStatus.Unavailable,
                languageTag = normaliseTag(languageTag),
                offlineCapable = false,
                cloudMayBeUsed = false,
                degraded = false,
                reasons = reasons,
            )
        }

        val tag = normaliseTag(languageTag)
        if (!SpeechLanguage.isSelectable(tag)) {
            return SpeechCapabilityReport(
                status = SpeechStatus.Unavailable,
                languageTag = tag,
                offlineCapable = false,
                cloudMayBeUsed = false,
                degraded = true,
                reasons = listOf(CapabilityReason.LanguageNotSelectable),
            )
        }
        var degraded = false

        if (env.apiLevel < ON_DEVICE_RECOGNITION_MIN_API) {
            reasons += CapabilityReason.OnDeviceRequiresApi31
        }
        val isHiIn = tag == SpeechLanguage.HI_IN
        if (isHiIn && !env.hiInSupported) {
            degraded = true
            reasons += CapabilityReason.HiInLanguageUnsupported
        }

        val onDeviceUsable = isHiIn && env.apiLevel >= ON_DEVICE_RECOGNITION_MIN_API &&
            env.onDeviceAvailable &&
            env.hiInSupported
        if (onDeviceUsable && !env.hiInModelDownloaded) {
            degraded = true
            reasons += CapabilityReason.HiInModelMissing
        }

        val status: SpeechStatus
        var offlineCapable = false
        var cloudMayBeUsed = false

        when {
            !env.recognizerServiceAvailable -> {
                status = SpeechStatus.MissingService
                reasons += CapabilityReason.NoRecognizerService
            }

            onDeviceUsable && env.hiInModelDownloaded -> {
                // Only this branch may claim on-device. It still carries a "not measured" caveat,
                // because an OS-reported capability is not a device measurement.
                status = SpeechStatus.OnDeviceReady
                offlineCapable = true
                cloudMayBeUsed = false
                reasons += CapabilityReason.OnDeviceNotMeasured
            }

            onDeviceUsable -> {
                // On-device exists but the hi-IN model is not installed: session still possible
                // through the default recogniser, so it is degraded, never offline.
                status = SpeechStatus.OnDeviceUnavailable
                offlineCapable = false
                cloudMayBeUsed = true
                degraded = true
            }

            else -> {
                // API < 31, or no on-device recogniser, or hi-IN unsupported: the default recogniser
                // may stream audio to a remote service. No offline claim is possible from here.
                status = SpeechStatus.CloudOnly
                offlineCapable = false
                cloudMayBeUsed = true
                reasons += CapabilityReason.DefaultRecognizerMayUseNetwork
                if (env.apiLevel >= ON_DEVICE_RECOGNITION_MIN_API && !env.onDeviceAvailable) {
                    reasons += CapabilityReason.OnDeviceNotAvailable
                }
            }
        }

        // Microphone state is reported, not guessed. Any state other than Granted blocks the start.
        val micBlocked = when (env.micPermission) {
            MicPermission.Granted -> {
                false
            }

            MicPermission.Unknown -> {
                reasons += CapabilityReason.MicPermissionNotAsked
                true
            }

            MicPermission.Denied -> {
                reasons += CapabilityReason.MicPermissionDenied
                true
            }

            MicPermission.PermanentlyDenied -> {
                reasons += CapabilityReason.MicPermissionPermanentlyDenied
                true
            }
        }

        return SpeechCapabilityReport(
            status = if (micBlocked) SpeechStatus.NoMicPermission else status,
            languageTag = tag,
            offlineCapable = offlineCapable,
            cloudMayBeUsed = cloudMayBeUsed,
            degraded = degraded,
            reasons = reasons.distinct(),
        )
    }

    private fun normaliseTag(tag: String): String {
        val trimmed = tag.trim()
        return if (trimmed.isEmpty()) SpeechLanguage.DEFAULT else trimmed
    }
}

/** Maps a report to the one line the UI shows. Honest by construction: no cloud line says private. */
object SpeechLabels {

    fun consentFor(report: SpeechCapabilityReport): BilingualCopy = when {
        report.cloudMayBeUsed -> SpeechCopy.consentAskCloudPossible
        report.offlineCapable -> SpeechCopy.consentAskOnDevice
        else -> SpeechCopy.consentAsk
    }

    fun statusFor(report: SpeechCapabilityReport): BilingualCopy = when (report.status) {
        SpeechStatus.OnDeviceReady -> SpeechCopy.onDeviceReady
        SpeechStatus.CloudOnly -> if (report.has(CapabilityReason.HiInLanguageUnsupported)) {
            SpeechCopy.languageNotConfirmed
        } else {
            SpeechCopy.needsInternet
        }

        SpeechStatus.OnDeviceUnavailable -> if (report.has(CapabilityReason.HiInLanguageUnsupported)) {
            SpeechCopy.languageNotConfirmed
        } else {
            SpeechCopy.hindiModelMissing
        }

        SpeechStatus.MissingService -> SpeechCopy.recognizerUnavailable
        SpeechStatus.NoMicPermission -> if (report.has(CapabilityReason.MicPermissionNotAsked)) {
            SpeechCopy.micPermissionNotAsked
        } else {
            SpeechCopy.micPermissionMissing
        }

        SpeechStatus.Unavailable -> SpeechCopy.speechUnavailable
    }

    /** Optional second line; null when there is nothing extra to disclose. */
    fun caveatFor(report: SpeechCapabilityReport): BilingualCopy? = when {
        report.status == SpeechStatus.OnDeviceReady -> SpeechCopy.onDeviceNotVerified
        report.degraded && report.cloudMayBeUsed -> SpeechCopy.needsInternet
        else -> null
    }
}

/**
 * Thin Android adapter. Reads platform facts only; it never starts recognition and never touches
 * the network.
 *
 * NOT JVM-TESTED: it calls android.* stubs that throw at runtime, so the JVM harness compiles it
 * (android.jar on the compile classpath) but only the pure rules above are executed.
 *
 * `probe` is a conservative synchronous snapshot: hi-IN model presence cannot be read
 * synchronously, so it is reported as false until `probeHindiModel` has verified it. After the
 * callback returns true, re-evaluate with `environment.copy(hiInModelDownloaded = true)`.
 */
object SpeechCapabilityProbe {

    /**
     * @param micPermission pass the state the Activity already knows (it is the only place that can
     *   distinguish Denied from PermanentlyDenied via `shouldShowRequestPermissionRationale`).
     *   `Unknown` means "read the OS grant state", which yields Granted or Denied.
     */
    fun probe(context: Context, micPermission: MicPermission = MicPermission.Unknown): SpeechEnvironment {
        val app = context.applicationContext ?: context
        val apiLevel = Build.VERSION.SDK_INT
        val serviceAvailable = runCatching { SpeechRecognizer.isRecognitionAvailable(app) }.getOrDefault(false)
        val onDeviceAvailable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                SpeechRecognizer.isOnDeviceRecognitionAvailable(app)
            } catch (_: RuntimeException) {
                false
            }
        } else {
            false
        }
        return SpeechEnvironment(
            apiLevel = apiLevel,
            recognizerServiceAvailable = serviceAvailable,
            onDeviceAvailable = onDeviceAvailable,
            hiInModelDownloaded = false,
            micPermission = resolveMic(app, micPermission),
        )
    }

    /**
     * Verifies that the hi-IN on-device model is actually installed, via
     * `SpeechRecognizer.checkRecognitionSupport` (API 33+). Below API 33 the model cannot be verified
     * without starting recognition, so the callback receives false: the app does not claim what it
     * cannot check.
     */
    fun probeHindiModel(context: Context, executor: Executor, onResult: (Boolean) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            onResult(false)
            return
        }
        val app = context.applicationContext ?: context
        val onDeviceAvailable = try {
            SpeechRecognizer.isOnDeviceRecognitionAvailable(app)
        } catch (_: RuntimeException) {
            false
        }
        if (!onDeviceAvailable) {
            onResult(false)
            return
        }
        val recognizer = try {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(app)
        } catch (_: RuntimeException) {
            null
        }
        if (recognizer == null) {
            onResult(false)
            return
        }
        var finished = false
        fun finish(installed: Boolean) {
            if (finished) return
            finished = true
            runCatching { recognizer.destroy() }
            runCatching { onResult(installed) }
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, SpeechLanguage.HI_IN)
            // EXTRA_PREFER_OFFLINE is intentionally not set anywhere: it is a hint the platform
            // may ignore, and it never feeds an offline capability claim.
        }
        try {
            recognizer.checkRecognitionSupport(
                intent,
                executor,
                object : RecognitionSupportCallback {
                    override fun onSupportResult(support: RecognitionSupport) {
                        val installed = runCatching {
                            support.installedOnDeviceLanguages.any {
                                it.equals(SpeechLanguage.HI_IN, ignoreCase = true)
                            }
                        }.getOrDefault(false)
                        finish(installed)
                    }

                    override fun onError(error: Int) {
                        finish(false)
                    }
                },
            )
        } catch (_: RuntimeException) {
            finish(false)
        }
    }

    /**
     * Reports whether the installed default recogniser advertises hi-IN. This is a capability
     * probe only: it does not start recognition or open the microphone. Android only exposes the
     * support callback from API 33; older versions return `null` (not checked).
     *
     * The decisions themselves live in [HindiLocaleSupportRules]; this function only reads the
     * platform and forwards the outcome as `Boolean?` (null = not checked).
     */
    fun probeHindiLocaleSupport(context: Context, executor: Executor, onResult: (Boolean?) -> Unit) {
        if (!HindiLocaleSupportRules.probeAllowed(Build.VERSION.SDK_INT)) {
            onResult(HindiLocaleSupportRules.toNullable(HindiLocaleSupportRules.outcomeWhenNotMeasurable()))
            return
        }
        val app = context.applicationContext ?: context
        if (!runCatching { SpeechRecognizer.isRecognitionAvailable(app) }.getOrDefault(false)) {
            onResult(HindiLocaleSupportRules.toNullable(HindiLocaleSupportRules.outcomeForMissingService()))
            return
        }
        val recognizer = try {
            SpeechRecognizer.createSpeechRecognizer(app)
        } catch (_: RuntimeException) {
            null
        }
        if (recognizer == null) {
            onResult(HindiLocaleSupportRules.toNullable(HindiLocaleSupportRules.outcomeForError()))
            return
        }
        var finished = false
        fun finish(outcome: HindiLocaleSupportOutcome) {
            if (finished) return
            finished = true
            runCatching { recognizer.destroy() }
            runCatching { onResult(HindiLocaleSupportRules.toNullable(outcome)) }
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, SpeechLanguage.HI_IN)
        }
        try {
            recognizer.checkRecognitionSupport(
                intent,
                executor,
                object : RecognitionSupportCallback {
                    override fun onSupportResult(support: RecognitionSupport) {
                        val supported = runCatching {
                            HindiLocaleSupportRules.supportedFromLanguages(
                                onlineLanguages = support.onlineLanguages.orEmpty(),
                                onDeviceLanguages = support.supportedOnDeviceLanguages.orEmpty(),
                            )
                        }.getOrDefault(false)
                        finish(HindiLocaleSupportRules.outcomeForSupport(supported))
                    }

                    override fun onError(error: Int) {
                        finish(HindiLocaleSupportRules.outcomeForError())
                    }
                },
            )
        } catch (_: RuntimeException) {
            finish(HindiLocaleSupportRules.outcomeForError())
        }
    }

    private fun resolveMic(context: Context, hint: MicPermission): MicPermission = when {
        hint != MicPermission.Unknown -> hint
        hasRecordAudio(context) -> MicPermission.Granted
        else -> MicPermission.Denied
    }

    private fun hasRecordAudio(context: Context): Boolean = runCatching {
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)
}
