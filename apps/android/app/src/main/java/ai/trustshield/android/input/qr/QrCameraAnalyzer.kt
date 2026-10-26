package ai.trustshield.android.input.qr

/**
 * Decodes one frame that was already captured by the camera.
 *
 * Production binding: AndroidX CameraX `ImageAnalysis` on a single-thread executor feeding ZXing's
 * pure-JVM QR decoder. No native barcode model or network model download is required.
 *
 * Because a decoder needs the pixels and this seam deliberately carries no image type, the
 * adapter keeps the in-flight `ImageProxy` in a slot keyed by `frameId`, decodes exactly one frame
 * at a time and closes the proxy in `addOnCompleteListener`. Only the decoded payload string crosses
 * this seam; pixels never reach this package and are never retained, written or logged.
 */
fun interface BarcodeDecoder {
    /**
     * @param frameId identifier of the frame currently in flight; the adapter maps it back to the
     *        live `ImageProxy` it captured.
     * @return the barcode raw value, or `null` when the frame holds no readable code.
     *         Implementations may throw: [QrCameraAnalyzer] converts any throwable into
     *         [AnalyzerEvent.Error] instead of propagating it to the caller.
     */
    fun decode(frameId: Long): String?
}

/**
 * Cheap per-frame measurements the camera adapter can compute without decoding.
 *
 * Deliberately statistics only - no pixels, no bitmap, no path. [meanLuma0to1] is the mean of the
 * luma plane normalised to `0.0..1.0` (`meanLuma0to1 < 0.12` is a dark/covered frame). The adapter
 * must clamp or skip frames whose `ImageProxy` reports zero size before first layout.
 */
data class FrameStats(
    val frameId: Long,
    val widthPx: Int,
    val heightPx: Int,
    val meanLuma0to1: Double,
    val timestampMs: Long,
) {
    init {
        require(frameId >= 0L) { "frameId must be non-negative" }
        require(widthPx > 0 && heightPx > 0) { "frame dimensions must be positive" }
        require(meanLuma0to1.isFinite() && meanLuma0to1 >= 0.0 && meanLuma0to1 <= 1.0) {
            "meanLuma0to1 must be within 0.0..1.0"
        }
    }
}

/**
 * Everything the QR watcher can learn from one submitted frame.
 *
 * The payload is **data only**: nothing in this package fetches, opens, resolves or executes it.
 * [NoDecode] and [Unreadable] are *not* tamper signals - a covered, glared or dark code says
 * nothing about the merchant (work package B06 owns the "unreadable is not tamper" state machine).
 */
sealed interface AnalyzerEvent {
    /** A barcode was read. [payload] is the raw `rawValue`; never log or store it. */
    data class Decoded(val payload: String, val frameId: Long, val tsMs: Long) : AnalyzerEvent

    /** This frame produced no code. [streak] counts consecutive no-decodes; [lowLight] = dark frame. */
    data class NoDecode(val streak: Int, val lowLight: Boolean) : AnalyzerEvent

    /**
     * [noDecodeStreakLimit] consecutive frames produced no code. Emitted once per run, at the
     * crossing. [sinceMs] is the timestamp of the first no-decode of the run, so a consumer can
     * run an occlusion timer ("QR dhak gaya ya hil gaya") without its own clock.
     */
    data class Unreadable(val sinceMs: Long) : AnalyzerEvent

    /** The analyzer is closed; no further event is ever emitted after this one. */
    data object Closed : AnalyzerEvent

    /**
     * The decoder threw. [message] is a sanitised token (`decoder-failed:<SimpleName>`), never the
     * exception message and never any payload or pixel-derived text.
     */
    data class Error(val message: String) : AnalyzerEvent
}

/** Camera permission, kept as separate explicit state so scanning never runs unpermitted. */
enum class CameraPermissionState { Unknown, Granted, Denied, PermanentlyDenied }

/** Outcome of [QrCameraAnalyzer.start]. Refusal reasons are stable tokens, mapped to Hinglish copy by the UI. */
sealed interface StartResult {
    data object Started : StartResult
    data class Refused(val reason: String) : StartResult
}

/**
 * Frame and scan *policy* for the QR Guard camera watcher (B03, issue #35). Pure Kotlin: no
 * `android.*` and no vendor SDK, so it is fully unit-testable on the JVM.
 *
 * Policy:
 * - **Latest frame wins.** While a decode is in flight a newly submitted frame replaces the single
 *   pending slot; the replaced frame is counted in [droppedStaleFrames] and never queued. A burst of
 *   N frames therefore costs at most two decodes (in flight + newest).
 * - **Dark / glared frames** (`meanLuma0to1 < lowLightThreshold`) are not sent to the decoder at all
 *   - the battery trade-off is that a tiny bright code inside an otherwise near-black frame is
 *   skipped; the resulting [AnalyzerEvent.NoDecode] with `lowLight = true` is what tells the user to
 *   move or light the code. Lower the threshold to `0.0` to decode every frame.
 * - **[AnalyzerEvent.Unreadable]** after [noDecodeStreakLimit] consecutive no-decodes. Never tamper.
 * - **Rapid-scan debounce**: the identical payload inside [debounceMs] is not re-emitted; a different
 *   payload is emitted immediately. A successful decode always resets the streak, suppressed or not.
 * - **Decoder failures** become [AnalyzerEvent.Error]; they neither break the streak nor count as a
 *   no-decode, and they never escape.
 * - **[close] is idempotent**; nothing is emitted afterwards and later frames are ignored/counted.
 *
 * Threading: [submitFrame], [start] and [close] are safe from any thread, including from inside
 * [BarcodeDecoder.decode] (the CameraX analysis executor performs decoding, while shutdown may run
 * from the main thread). Events are returned
 * to the caller; the adapter dispatches them to the UI, this class never touches a callback or a view.
 */
class QrCameraAnalyzer(
    private val decoder: BarcodeDecoder,
    val lowLightThreshold: Double = LOW_LIGHT_THRESHOLD,
    val noDecodeStreakLimit: Int = NO_DECODE_STREAK_LIMIT,
    val debounceMs: Long = DEBOUNCE_MS,
) {

    /** Lifecycle of the analyzer. Register is explicit: frames are ignored until [start] succeeds. */
    enum class State { Idle, Running, Closed }

    private val lock = Any()

    @Volatile
    private var currentState: State = State.Idle

    @Volatile
    private var currentPermission: CameraPermissionState = CameraPermissionState.Unknown

    private data class PendingFrame(val frame: FrameStats, val permissionGeneration: Long)

    /** Single-slot pending frame (bounded queue of 1). Guarded by [lock]. */
    private var pending: PendingFrame? = null
    private var permissionGeneration: Long = 0L
    private var decoderBusy = false
    private var streakCount = 0
    private var streakStartMs = 0L
    private var lastPayload: String? = null
    private var lastEmitMs: Long? = null
    private var droppedStaleCount = 0
    private var ignoredAfterCloseCount = 0
    private var ignoredWhileNotRunningCount = 0
    private var decodedFrameCount = 0
    private var skippedDarkFrameCount = 0

    init {
        require(lowLightThreshold.isFinite() && lowLightThreshold >= 0.0 && lowLightThreshold <= 1.0) {
            "lowLightThreshold must be within 0.0..1.0"
        }
        require(noDecodeStreakLimit >= 1) { "noDecodeStreakLimit must be >= 1" }
        require(debounceMs >= 0L) { "debounceMs must be >= 0" }
    }

    val state: State get() = currentState

    /** Last permission passed to [start]; [CameraPermissionState.Unknown] until the first call. */
    val permissionState: CameraPermissionState get() = currentPermission

    /** Frames replaced in the single pending slot while a decode was in flight. */
    val droppedStaleFrames: Int get() = synchronized(lock) { droppedStaleCount }

    /** Frames submitted after [close]; ignored and counted, never decoded. */
    val framesIgnoredAfterClose: Int get() = synchronized(lock) { ignoredAfterCloseCount }

    /** Frames submitted before [start] succeeded (Idle) or while [State.Closed]; ignored. */
    val framesIgnoredWhileNotRunning: Int get() = synchronized(lock) { ignoredWhileNotRunningCount }

    /** Frames actually handed to [BarcodeDecoder]. */
    val framesDecoded: Int get() = synchronized(lock) { decodedFrameCount }

    /** Frames short-circuited as too dark to decode. */
    val framesSkippedDark: Int get() = synchronized(lock) { skippedDarkFrameCount }

    /** Current run of consecutive no-decodes; reset by any successful decode. */
    val noDecodeStreak: Int get() = synchronized(lock) { streakCount }

    /**
     * Enter [State.Running] and arm the camera path.
     *
     * Camera permission is explicit, separate state: without [CameraPermissionState.Granted] the
     * analyzer refuses to start and returns [StartResult.Refused] with a stable token
     * (`permission-unknown` / `permission-denied` / `permission-permanently-denied`, or
     * `analyzer-closed` after [close]). Calling it again while running is a no-op that returns
     * [StartResult.Started], so a configuration change can re-call it without resetting counters.
     */
    fun start(permission: CameraPermissionState = currentPermission): StartResult = synchronized(lock) {
        if (currentState == State.Closed) {
            StartResult.Refused(REASON_ANALYZER_CLOSED)
        } else {
            currentPermission = permission
            when (permission) {
                CameraPermissionState.Granted -> {
                    if (currentState == State.Idle) currentState = State.Running
                    StartResult.Started
                }
                CameraPermissionState.Unknown -> revokePermission(REASON_PERMISSION_UNKNOWN)
                CameraPermissionState.Denied -> revokePermission(REASON_PERMISSION_DENIED)
                CameraPermissionState.PermanentlyDenied -> revokePermission(REASON_PERMISSION_PERMANENTLY_DENIED)
            }
        }
    }

    /** Caller holds [lock]. Invalidates pending/in-flight work before refusing the camera start. */
    private fun revokePermission(reason: String): StartResult.Refused {
        if (currentState == State.Running) {
            currentState = State.Idle
            permissionGeneration++
            pending = null
            streakCount = 0
            lastPayload = null
            lastEmitMs = null
        }
        return StartResult.Refused(reason)
    }

    /**
     * Offer one camera frame. Returns the events produced by this call (usually none while idle or
     * while another frame is decoding). Safe from any thread and re-entrant: a frame submitted while
     * a decode is running replaces the pending slot, and the newest pending frame is decoded as soon
     * as the in-flight one finishes.
     */
    fun submitFrame(frame: FrameStats): List<AnalyzerEvent> {
        val accepted: PendingFrame? = synchronized(lock) {
            when {
                currentState == State.Closed -> {
                    ignoredAfterCloseCount++
                    null
                }
                currentState != State.Running -> {
                    ignoredWhileNotRunningCount++
                    null
                }
                decoderBusy -> {
                    // Bounded pending queue of 1: the newest frame wins, the displaced one is dropped.
                    if (pending != null) droppedStaleCount++
                    pending = PendingFrame(frame, permissionGeneration)
                    null
                }
                else -> {
                    decoderBusy = true
                    PendingFrame(frame, permissionGeneration)
                }
            }
        }
        return if (accepted == null) emptyList() else drain(accepted)
    }

    /**
     * Close the analyzer. Idempotent. The first call returns `[Closed]` and is the **last** event this
     * analyzer ever produces: an in-flight decode that is still running is discarded, the pending
     * frame is dropped, and later frames are ignored and counted in [framesIgnoredAfterClose].
     *
     * The adapter must call this from `onDestroy`/`DisposableEffect` and also stop the CameraX use
     * case so the vendor pipeline releases the camera; this class never touches a camera handle.
     */
    fun close(): List<AnalyzerEvent> = synchronized(lock) {
        if (currentState == State.Closed) {
            emptyList()
        } else {
            currentState = State.Closed
            pending = null
            streakCount = 0
            listOf(AnalyzerEvent.Closed)
        }
    }

    // ---------------------------------------------------------------- internals

    private sealed interface Outcome {
        data class Payload(val raw: String?) : Outcome
        data class Failure(val error: Throwable) : Outcome
    }

    /**
     * Decode one frame, then keep draining the single pending slot until it is empty or the analyzer
     * closes. Exactly one decoder call is outstanding at a time.
     */
    private fun drain(first: PendingFrame): List<AnalyzerEvent> {
        val events = ArrayList<AnalyzerEvent>(4)
        var current: PendingFrame? = first
        while (current != null) {
            val work = current
            val frame = work.frame
            val produced: List<AnalyzerEvent>? = synchronized(lock) {
                if (currentState != State.Running || work.permissionGeneration != permissionGeneration) {
                    emptyList()
                } else if (frame.meanLuma0to1 < lowLightThreshold) {
                    // Dark or covered lens: skip the decode entirely, report it as low light.
                    skippedDarkFrameCount++
                    applyNoDecode(frame)
                } else {
                    null // decode outside the lock
                }
            }
            if (produced != null) {
                events += produced
            } else {
                val outcome = runDecoder(frame)
                synchronized(lock) {
                    if (currentState == State.Running && work.permissionGeneration == permissionGeneration) {
                        decodedFrameCount++
                        events += applyOutcome(frame, outcome)
                    }
                }
            }
            current = synchronized(lock) {
                val next = pending?.takeIf {
                    currentState == State.Running && it.permissionGeneration == permissionGeneration
                }
                pending = null
                if (next == null) decoderBusy = false
                next
            }
        }
        return events
    }

    private fun runDecoder(frame: FrameStats): Outcome = try {
        Outcome.Payload(decoder.decode(frame.frameId))
    } catch (failure: Throwable) {
        // A vendor decoder must never crash the scanner. VM-wide failures are not decoder failures,
        // so they are rethrown rather than swallowed as a per-frame error.
        if (failure is VirtualMachineError) throw failure
        Outcome.Failure(failure)
    }

    private fun applyOutcome(frame: FrameStats, outcome: Outcome): List<AnalyzerEvent> = when (outcome) {
        is Outcome.Payload -> {
            val raw = outcome.raw
            if (raw.isNullOrBlank()) applyNoDecode(frame) else applyDecoded(frame, raw)
        }
        is Outcome.Failure -> listOf(AnalyzerEvent.Error(errorToken(outcome.error)))
    }

    /** Caller holds [lock]. */
    private fun applyNoDecode(frame: FrameStats): List<AnalyzerEvent> {
        streakCount++
        if (streakCount == 1) streakStartMs = frame.timestampMs
        val lowLight = frame.meanLuma0to1 < lowLightThreshold
        val events = ArrayList<AnalyzerEvent>(2)
        events += AnalyzerEvent.NoDecode(streak = streakCount, lowLight = lowLight)
        if (streakCount == noDecodeStreakLimit) {
            events += AnalyzerEvent.Unreadable(sinceMs = streakStartMs)
        }
        return events
    }

    /** Caller holds [lock]. A decode resets the streak whether or not the payload is re-emitted. */
    private fun applyDecoded(frame: FrameStats, payload: String): List<AnalyzerEvent> {
        streakCount = 0
        val previous = lastEmitMs
        val withinDebounce = previous != null &&
            payload == lastPayload &&
            frame.timestampMs >= previous &&
            frame.timestampMs - previous < debounceMs
        if (withinDebounce) return emptyList()
        lastPayload = payload
        lastEmitMs = frame.timestampMs
        return listOf(AnalyzerEvent.Decoded(payload = payload, frameId = frame.frameId, tsMs = frame.timestampMs))
    }

    /** Never carries the exception message: it could quote payload text or a filesystem/frame path. */
    private fun errorToken(error: Throwable): String {
        val simple = error.javaClass.simpleName.filter { it.isLetterOrDigit() || it == '_' }
        return "decoder-failed:" + (simple.ifEmpty { "Throwable" })
    }

    companion object {
        /** Below this mean luma the frame is treated as dark/covered, not as a tamper signal. */
        const val LOW_LIGHT_THRESHOLD: Double = 0.12

        /** Consecutive no-decodes before [AnalyzerEvent.Unreadable] is reported (B06 consumes it). */
        const val NO_DECODE_STREAK_LIMIT: Int = 5

        /** Window in which an identical payload is treated as the same scan continuing. */
        const val DEBOUNCE_MS: Long = 1_500L

        const val REASON_PERMISSION_UNKNOWN: String = "permission-unknown"
        const val REASON_PERMISSION_DENIED: String = "permission-denied"
        const val REASON_PERMISSION_PERMANENTLY_DENIED: String = "permission-permanently-denied"
        const val REASON_ANALYZER_CLOSED: String = "analyzer-closed"
    }
}
