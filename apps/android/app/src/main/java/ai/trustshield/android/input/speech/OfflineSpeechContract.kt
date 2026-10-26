/*
 * P0-148 / P0-146 - the offline speech contract.
 *
 * Pure JVM by design: no `android.*`, no `org.vosk.*`, no I/O, no clock of its own. Every type here is
 * a decision table the UI and the recogniser adapters both read, so the honest-status rules, the
 * 16 kHz mono format gate and the sub-second restart budget can be unit tested on the JVM instead of
 * being asserted in prose.
 *
 * What lives here:
 *  - [SpeechEngineId] / [OfflineEngineSelection] / [OfflineEnginePolicy]: which engine runs, and why
 *    we fell back. Selection is a pure function of [OfflineEnvironment].
 *  - [OfflineAsrStatus] + [OfflineStatusCopy] + [SpeechClaimGuard]: the on-screen status line, with a
 *    hard invariant that a network-capable engine can never be labelled offline/private/on-device.
 *  - [SpeechAudioFormat]: the one accepted input format (16 kHz, mono, PCM 16-bit) and the byte/frame
 *    arithmetic the AudioRecord feeder and the WAV fixtures share.
 *  - [RecognizerRestartPolicy]: issue 146. NO_MATCH / SPEECH_TIMEOUT are silence, not the end of the
 *    session; the restart must be scheduled inside the 1000 ms budget with widening backoff, and it
 *    must eventually give up instead of spinning forever.
 *
 * Not in scope here on purpose: no audio buffer, no transcript field, no persistence handle. Nothing
 * in this file can hold speech content, which is what makes the privacy claim mechanical.
 */
package ai.trustshield.android.input.speech

/**
 * Which recogniser produced (or will produce) the transcript.
 *
 * [VoskOffline] is the bundled Vosk engine reading AudioRecord directly. It never opens a socket.
 */
enum class SpeechEngineId {
    /** `SpeechRecognizer.createOnDeviceSpeechRecognizer` - on-device, but unproven offline per device. */
    PlatformOnDevice,

    /** `SpeechRecognizer.createSpeechRecognizer` - may stream audio to a remote service. */
    PlatformDefault,

    /** Bundled Vosk small Hindi model decoding 16 kHz mono PCM locally. */
    VoskOffline,
}

/** Machine-readable reason for the current engine, so the UI never has to parse a status string. */
enum class OfflineFallbackReason {
    /** The platform on-device recogniser is usable; no fallback happened. */
    None,

    /** No usable network route: the platform recogniser cannot be trusted to transcribe. */
    NetworkUnreachable,

    /** The Google app has no offline `hi-IN` language pack (the real phone's failure mode). */
    GoogleLanguagePackMissing,

    /** The platform recogniser returned a terminal error at least [OfflineEnginePolicy.MAX_PLATFORM_ERRORS_BEFORE_FALLBACK] times. */
    RecognizerErrorTwice,

    /** No platform recognition service at all (missing service, API too old, permission-free device). */
    PlatformUnavailable,
}

/**
 * Inputs to [OfflineEnginePolicy.select]. Every flag is something the platform or the app can observe;
 * none of them is an inference.
 *
 * @param voskModelBundled the model ships in the APK assets.
 * @param voskModelUnpacked the first-use unpack into app files has completed.
 * @param networkAvailable a network route exists right now. Callers pass `false` in airplane mode.
 * @param googlePackAvailable the platform on-device `hi-IN` pack is installed.
 * @param platformErrorsThisSession terminal platform errors so far in this session.
 * @param platformSessionPossible the B04 capability report said a session could run.
 */
data class OfflineEnvironment(
    val voskModelBundled: Boolean,
    val voskModelUnpacked: Boolean,
    val networkAvailable: Boolean,
    val googlePackAvailable: Boolean,
    val platformErrorsThisSession: Int = 0,
    val platformSessionPossible: Boolean = true,
) {
    /** The Vosk engine can actually start: bundled model present and unpacked. */
    val voskUsable: Boolean get() = voskModelBundled && voskModelUnpacked
}

/**
 * The chosen engine plus why. [engine] is null only when nothing can run; the caller must then show
 * [OfflineStatusCopy.unavailable] rather than pretending a session is possible.
 */
data class OfflineEngineSelection(
    val engine: SpeechEngineId?,
    val reason: OfflineFallbackReason,
) {
    val usable: Boolean get() = engine != null

    companion object {
        val none = OfflineEngineSelection(null, OfflineFallbackReason.PlatformUnavailable)
    }
}

/**
 * Deterministic engine choice (HEAD_DIRECTIVE_OFFLINE_ASR item 3).
 *
 * Precedence, first match wins - documented because a test pins it:
 *  1. Vosk is usable and the network is unreachable -> [SpeechEngineId.VoskOffline] / NetworkUnreachable.
 *  2. Vosk is usable and the `hi-IN` pack is missing -> VoskOffline / GoogleLanguagePackMissing.
 *  3. Vosk is usable and the platform already failed twice -> VoskOffline / RecognizerErrorTwice.
 *  4. The platform session is possible -> on-device when the pack is present, otherwise the
 *     network-capable default recogniser (labelled honestly; it is not private).
 *  5. Vosk is usable -> VoskOffline (platform unusable but the offline engine is not).
 *  6. Otherwise null / PlatformUnavailable.
 *
 * Rule 4 sits above rule 5 on purpose: with a working network and pack the platform recogniser is
 * cheaper and better than a 42 MB model, and a fallback engine is a fallback.
 */
object OfflineEnginePolicy {

    /** Two errors is the directive's trigger ("errors twice"); never one. */
    const val MAX_PLATFORM_ERRORS_BEFORE_FALLBACK: Int = 2

    fun select(env: OfflineEnvironment): OfflineEngineSelection = when {
        env.voskUsable && !env.networkAvailable ->
            OfflineEngineSelection(SpeechEngineId.VoskOffline, OfflineFallbackReason.NetworkUnreachable)

        env.voskUsable && !env.googlePackAvailable ->
            OfflineEngineSelection(SpeechEngineId.VoskOffline, OfflineFallbackReason.GoogleLanguagePackMissing)

        env.voskUsable && env.platformErrorsThisSession >= MAX_PLATFORM_ERRORS_BEFORE_FALLBACK ->
            OfflineEngineSelection(SpeechEngineId.VoskOffline, OfflineFallbackReason.RecognizerErrorTwice)

        env.platformSessionPossible && env.googlePackAvailable ->
            OfflineEngineSelection(SpeechEngineId.PlatformOnDevice, OfflineFallbackReason.None)

        env.platformSessionPossible ->
            OfflineEngineSelection(SpeechEngineId.PlatformDefault, OfflineFallbackReason.None)

        env.voskUsable ->
            OfflineEngineSelection(SpeechEngineId.VoskOffline, OfflineFallbackReason.PlatformUnavailable)

        else -> OfflineEngineSelection.none
    }
}

/**
 * What the status strip is allowed to say.
 *
 * The `init` block is the whole point: a status that claims on-device processing for a
 * network-capable engine, or the reverse, cannot be constructed. Callers therefore cannot lie by
 * passing a hand-built triple.
 *
 * @param onDevice true only when decoding happens entirely on the phone.
 * @param cloudMayBeUsed true when audio may leave the device.
 * @param measured true only when this exact engine was measured on the named device. Default is
 *   `false` and must stay `false` until somebody records a device run.
 */
data class OfflineAsrStatus(
    val engine: SpeechEngineId,
    val onDevice: Boolean,
    val cloudMayBeUsed: Boolean,
    val fallbackReason: OfflineFallbackReason = OfflineFallbackReason.None,
    val measured: Boolean = false,
) {
    init {
        require(!(onDevice && cloudMayBeUsed)) {
            "on-device and cloud-capable are mutually exclusive"
        }
        require(engine != SpeechEngineId.VoskOffline || onDevice) {
            "the bundled Vosk engine is on-device by construction"
        }
        require(engine != SpeechEngineId.VoskOffline || !cloudMayBeUsed) {
            "the bundled Vosk engine never opens a socket"
        }
        require(engine != SpeechEngineId.PlatformDefault || cloudMayBeUsed) {
            "the default platform recogniser may stream audio and must be labelled as such"
        }
        require(engine != SpeechEngineId.PlatformOnDevice || onDevice) {
            "the on-device platform recogniser is on-device"
        }
    }

    /** True only when the privacy claim is mechanically true. */
    val claimsPrivate: Boolean get() = onDevice && !cloudMayBeUsed

    /** The user-facing line. Never mentions certainty, and never promises unmeasured behaviour. */
    fun copy(): BilingualCopy = OfflineStatusCopy.forStatus(this)
}

/** Copy bank for the offline status strip. Short, hedged, and the same facts in both languages. */
object OfflineStatusCopy {

    /** Directive wording, English half verbatim. */
    val offlineOnDevice = BilingualCopy(
        hinglish = "Offline mode: phone par hi pehchan",
        english = "Offline mode: on-device recognizer",
    )

    /** The platform on-device path: honest that this device's offline pack is not proven. */
    val platformOnDeviceUnmeasured = BilingualCopy(
        hinglish = "On-device mode - is phone par offline test abhi nahi hua",
        english = "On-device mode - not yet tested offline on this phone",
    )

    /** The network-capable path. Must never read as private. */
    val cloudPossible = BilingualCopy(
        hinglish = "Online mode: awaaz network par ja sakti hai",
        english = "Online mode: audio may leave this phone",
    )

    /** Nothing can transcribe right now; the demo path and the reason stay visible. */
    val unavailable = BilingualCopy(
        hinglish = "Abhi speech nahi chal sakti - demo type karke dikhayein",
        english = "Speech cannot run right now - use the typed demo",
    )

    /** Fallback happened and the reason is shown in one clause, not hidden. */
    fun forStatus(status: OfflineAsrStatus): BilingualCopy = when {
        status.cloudMayBeUsed -> cloudPossible
        status.engine == SpeechEngineId.VoskOffline -> offlineOnDevice
        status.engine == SpeechEngineId.PlatformOnDevice -> platformOnDeviceUnmeasured
        else -> unavailable
    }
}

/**
 * Words a status line may only use when the audio genuinely stays on the phone. Used by tests and by
 * the UI string review; deliberately a plain list of lowercase needles.
 */
object SpeechClaimGuard {

    val privacyWords: List<String> =
        listOf("offline", "private", "privacy", "on-device", "on device", "phone par hi", "local only")

    /** True when [copy] uses privacy vocabulary in either language. */
    fun claimsPrivacy(copy: BilingualCopy): Boolean {
        val haystack = (copy.hinglish + " " + copy.english).lowercase()
        return privacyWords.any { haystack.contains(it) }
    }

    /**
     * The one rule the UI must satisfy: privacy vocabulary appears only when the privacy claim is
     * mechanically true.
     */
    fun isHonest(status: OfflineAsrStatus): Boolean {
        val copy = status.copy()
        return !claimsPrivacy(copy) || status.claimsPrivate
    }
}

/** The accepted capture format, named once. */
data class AudioFormatSpec(
    val sampleRateHz: Int,
    val channelCount: Int,
    val encoding: Int,
)

/** Why a candidate capture format is refused. */
enum class AudioFormatRejection {
    SampleRate,
    Channels,
    Encoding,
}

/**
 * Vosk's small Hindi model needs 16 kHz mono 16-bit PCM. `android.media.AudioFormat` is not imported
 * here (JVM tests), so [ENCODING_PCM_16BIT] mirrors `AudioFormat.ENCODING_PCM_16BIT == 2` and the
 * value is asserted in the fixture tests.
 */
object SpeechAudioFormat {

    const val SAMPLE_RATE_HZ: Int = 16_000
    const val CHANNEL_COUNT: Int = 1

    /** Mirrors `android.media.AudioFormat.ENCODING_PCM_16BIT`. */
    const val ENCODING_PCM_16BIT: Int = 2
    const val BYTES_PER_SAMPLE: Int = 2

    /** Vosk accepts chunks of any size; 100 ms keeps the partial-result cadence readable. */
    const val CHUNK_MS: Int = 100

    val required: AudioFormatSpec =
        AudioFormatSpec(SAMPLE_RATE_HZ, CHANNEL_COUNT, ENCODING_PCM_16BIT)

    fun accepts(spec: AudioFormatSpec): Boolean = rejectionFor(spec) == null

    /** Null when accepted; the first failing field in sample-rate, channel, encoding order. */
    fun rejectionFor(spec: AudioFormatSpec): AudioFormatRejection? = when {
        spec.sampleRateHz != SAMPLE_RATE_HZ -> AudioFormatRejection.SampleRate
        spec.channelCount != CHANNEL_COUNT -> AudioFormatRejection.Channels
        spec.encoding != ENCODING_PCM_16BIT -> AudioFormatRejection.Encoding
        else -> null
    }

    fun bytesPerSecond(): Int = SAMPLE_RATE_HZ * CHANNEL_COUNT * BYTES_PER_SAMPLE

    fun bytesFor(durationMs: Long): Long = bytesPerSecond() * durationMs / 1_000L

    fun framesFor(durationMs: Long): Long = SAMPLE_RATE_HZ.toLong() * durationMs / 1_000L

    /**
     * Splits a PCM byte array into [CHUNK_MS] chunks. A trailing partial chunk is returned as-is, so
     * no audio is silently dropped; the result is empty for empty input.
     */
    fun chunk(pcm: ByteArray, chunkMs: Int = CHUNK_MS): List<ByteArray> {
        val size = (bytesFor(chunkMs.toLong())).toInt()
        if (pcm.isEmpty() || size <= 0) return emptyList()
        val out = ArrayList<ByteArray>(pcm.size / size + 1)
        var offset = 0
        while (offset < pcm.size) {
            val end = minOf(offset + size, pcm.size)
            out.add(pcm.copyOfRange(offset, end))
            offset = end
        }
        return out
    }
}

/** Outcome of a silence/error callback: restart, or stop honestly. */
sealed class RestartDecision {

    /** Restart the recogniser after [afterMs]. Always `< `[RecognizerRestartPolicy.RESTART_BUDGET_MS]. */
    data class RestartNow(val afterMs: Long) : RestartDecision()

    /** Do not restart; the caller shows the reason and the typed demo path. */
    data class GiveUp(val why: RestartGiveUp) : RestartDecision()
}

/** Why the session stopped trying. */
enum class RestartGiveUp {
    NotRetryable,
    TooManyRestarts,
}

/**
 * Issue 146: `ERROR_NO_MATCH` / `ERROR_SPEECH_TIMEOUT` must not leave the session dead for tens of
 * seconds. The platform recogniser genuinely stops after either, so the fix is to restart it inside a
 * sub-second budget with widening backoff, and to stop restarting once it is clearly thrashing.
 *
 * Backoff: the first restart is [FIRST_DELAY_MS], then doubles to [MAX_DELAY_MS]. Both are below
 * [RESTART_BUDGET_MS] by construction, and [RestartDecision.RestartNow.afterMs] is asserted against
 * the budget in the tests.
 *
 * Thread-safe: the UI and the callback thread both call it.
 */
class RecognizerRestartPolicy(
    private val maxRestartsPerWindow: Int = 30,
    private val windowMs: Long = 60_000L,
    private val firstDelayMs: Long = FIRST_DELAY_MS,
) {
    private val lock = Any()
    private val steps = ArrayList<Long>()
    private val restartTimes = ArrayDeque<Long>()
    private var streak = 0

    init {
        require(firstDelayMs in 0..RESTART_BUDGET_MS) { "first delay must fit the restart budget" }
        require(maxRestartsPerWindow > 0) { "a session must allow at least one restart" }
    }

    /** Restarts performed so far. */
    val restarts: Int get() = synchronized(lock) { steps.size }

    /** Consecutive silence callbacks since the last successful utterance. */
    val consecutiveSilences: Int get() = synchronized(lock) { streak }

    /**
     * `ERROR_NO_MATCH` / `ERROR_SPEECH_TIMEOUT`. Silence is not a failure of the session, so this
     * always schedules a restart until the session is clearly thrashing.
     */
    fun onNoSpeech(nowMs: Long, reason: NoSpeechReason): RestartDecision = synchronized(lock) {
        noteRestart(nowMs) ?: schedule()
    }

    /**
     * A terminal recogniser error. Non-retryable reasons (no permission) never restart; retryable ones
     * use the same budget as silence.
     */
    fun onError(nowMs: Long, reason: ErrorReason, retryable: Boolean): RestartDecision {
        if (!retryable || reason == ErrorReason.NO_PERMISSION) {
            return RestartDecision.GiveUp(RestartGiveUp.NotRetryable)
        }
        synchronized(lock) {
            noteRestart(nowMs) ?: schedule()
        }
    }

    /** The recogniser reported speech or an utterance landed: the backoff resets to the first step. */
    fun onListening() {
        synchronized(lock) { streak = 0 }
    }

    /** Delay the next restart would use, without consuming a step. */
    fun nextDelayMs(): Long = synchronized(lock) { delayFor(streak) }

    /** Records one restart and returns a GiveUp when the window budget is spent, else null. */
    private fun noteRestart(nowMs: Long): RestartDecision? {
        while (restartTimes.isNotEmpty() && nowMs - restartTimes.first() > windowMs) {
            restartTimes.removeFirst()
        }
        if (restartTimes.size >= maxRestartsPerWindow) {
            return RestartDecision.GiveUp(RestartGiveUp.TooManyRestarts)
        }
        restartTimes.addLast(nowMs)
        steps.add(delayFor(streak))
        return null
    }

    private fun schedule(): RestartDecision {
        val delay = delayFor(streak)
        streak++
        return RestartDecision.RestartNow(delay)
    }

    private fun delayFor(streakIndex: Int): Long {
        if (streakIndex <= 0) return firstDelayMs
        var delay = firstDelayMs
        repeat(streakIndex) {
            delay = (delay * 2).coerceAtMost(MAX_DELAY_MS)
        }
        return delay.coerceAtMost(MAX_DELAY_MS)
    }

    companion object {
        /** Hard ceiling from the directive: restart inside one second, no 47 s gap. */
        const val RESTART_BUDGET_MS: Long = 1_000L

        /** First restart is fast enough to feel continuous. */
        const val FIRST_DELAY_MS: Long = 100L

        /** Backoff ceiling, still inside [RESTART_BUDGET_MS]. */
        const val MAX_DELAY_MS: Long = 800L
    }
}
