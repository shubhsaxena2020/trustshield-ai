package ai.trustshield.android.ui.qr

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
import android.os.Build
import android.provider.Settings
import android.view.HapticFeedbackConstants
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.security.SecureRandom
import java.math.BigDecimal
import java.math.RoundingMode
import kotlinx.coroutines.delay
import ai.trustshield.android.data.preferences.AppLanguage
import ai.trustshield.android.core.payment.PaymentAdvisory
import ai.trustshield.android.core.payment.PaymentAdvisoryEngine
import ai.trustshield.android.core.payment.PaymentFields
import ai.trustshield.android.core.payment.PaymentAppHint
import ai.trustshield.android.core.payment.PaymentStatus
import ai.trustshield.android.core.payment.maskedForPresentation
import ai.trustshield.android.ui.result.UiLanguage
import ai.trustshield.android.data.preferences.resolveLanguage
import ai.trustshield.android.input.qr.AnalyzerEvent
import ai.trustshield.android.input.qr.CameraPermissionState
import ai.trustshield.android.input.qr.CameraXQrScanner
import ai.trustshield.android.input.qr.PayeeExtractor
import ai.trustshield.android.input.qr.PayeeMasking
import ai.trustshield.android.input.qr.PayeeRegistry
import ai.trustshield.android.input.qr.QrGuardState
import ai.trustshield.android.input.qr.QrSimulationFlow
import ai.trustshield.android.input.qr.QrWatcher
import ai.trustshield.android.input.qr.QrWatcherPolicy
import ai.trustshield.android.input.qr.QrWatcherStatus
import ai.trustshield.android.input.qr.ReRegisterResult
import ai.trustshield.android.input.qr.SaltedPayeeRegistry
import ai.trustshield.android.input.qr.SimulatedQrFixtures
import ai.trustshield.android.input.qr.UpiQrParser
import ai.trustshield.android.ui.navigation.LocalPreferencesSink
import ai.trustshield.android.ui.navigation.LocalUserPreferences
import ai.trustshield.android.ui.theme.RiskTone
import ai.trustshield.android.ui.theme.ToneCopy
import ai.trustshield.android.ui.theme.TsTokens
import ai.trustshield.android.ui.theme.accent
import ai.trustshield.android.ui.theme.container
import ai.trustshield.android.ui.theme.onContainer

// =====================================================================================================
// QR Guard host — work package P3-14, issue #93.
//
// Phase 2 shipped the parts: B02 (UPI parser), B03 (camera-frame policy), B06 (tamper watcher),
// P3-13 (CameraX + pure-JVM ZXing adapter), P3-15 (deterministic demo fixtures) and C06 (pure QR
// screen state + renderer). What was missing is the *wire* between them, and that is exactly what
// this file is: the only place in the app that owns a camera, a permission request and the mapping
// from a decoded frame to what the shopkeeper sees.
//
// ## Division of ownership (do not blur this)
//  * **B06 `QrWatcher` owns the verdict.** How many consecutive valid mismatches count, when an alert
//    is sticky, when the occlusion timer fires — all of it is B06's. This file never re-implements
//    those rules; it *projects* `QrWatcherStatus` into `QrUiState`.
//  * **C06 `QrReducer` owns the UI transitions** that are not verdicts: language, the PIN gate,
//    acknowledge, Stop/Start, payee list validation.
//  * **This file owns only the seams** that need Android: the camera bind/unbind, the runtime
//    permission recovery, the occlusion tick, the opt-in haptic, and the demo replay.
//
// ## Hard invariants (HEAD_ORDERS §2, QUALITY_GATES 4/5/6)
//  1. A decoded payload is **inert data**. Nothing in this file resolves, fetches, previews or opens
//     a destination. The only `startActivity` in the whole QR flow is the app's *own* system settings
//     page, used to recover a permanently denied camera permission.
//  2. No raw payload, URI, VPA, bitmap, OTP or PIN is ever logged, persisted or put in an exception
//     message. Only salted identifiers (B06 registry) and masked strings (`ra***@okaxis`) leave the
//     session, and `QrUiState`/`QrGuardView` `toString()` are masked/aggregate.
//  3. **Unreadable is never tamper.** A dark, glared, covered or half-peeled QR drives
//     `observeUnreadable()`, which B06 maps to the occlusion timer — never to `Tampered`.
//  4. The camera is released on Stop, on Back/leaving the screen and on ON_STOP. No frame is analysed
//     after the screen is gone, and the analysis executor is shut down by `CameraXQrScanner.stop()`.
//  5. The analyzer is never started without a granted permission (B03 refuses by design; this file
//     does not work around that).
// =====================================================================================================

/** One deliberate UI tick for the occlusion timer (see [QrGuardSession.tick]). */
private const val OCCLUSION_TICK_MS: Long = 5_000L

/** B06's re-register gate: a 5 second press, then the app PIN. */
internal const val RE_REGISTER_HOLD_MS: Long = 5_000L

/** Session PIN bounds. The PIN is compared, never stored or logged. */
internal const val PIN_MIN_DIGITS: Int = 4
internal const val PIN_MAX_DIGITS: Int = 6

// =====================================================================================================
// 1. Permission decision — pure, so the recovery matrix is testable without an Activity
// =====================================================================================================

/**
 * What the OS currently allows, as far as the screen can tell.
 *
 * [UNPROBED] does not exist on purpose: the host probes on first composition and on every ON_RESUME,
 * so the screen never renders a "checking camera" state that could hang on a machine where the probe
 * throws.
 */
internal enum class QrPermissionState {
    /** `CAMERA` is granted: the preview and the analyzer may run. */
    GRANTED,

    /** Not granted, but Android would still show the system dialog (first ask, or rationale shown). */
    CAN_ASK,

    /** Not granted and the dialog would be suppressed: only the app's settings page can recover it. */
    PERMANENTLY_DENIED,

    /** The device reports no camera at all (`FEATURE_CAMERA_ANY` absent). */
    NO_CAMERA
}

/** The single action the screen must offer for a [QrPermissionState]. */
internal enum class QrPermissionAction {
    /** Nothing to recover. */
    NONE,

    /** Show the rationale and the "Camera allow karein" button. */
    ASK,

    /** The system dialog is suppressed: open the app's own settings page. */
    OPEN_SETTINGS,

    /** No camera hardware: manual UPI-id entry only. */
    MANUAL_ENTRY_ONLY
}

/** Immutable permission view model for the screen. */
internal data class QrPermissionUi(
    val state: QrPermissionState,
    val action: QrPermissionAction
) {
    /** True only when a camera session may be started. */
    val canUseCamera: Boolean get() = state == QrPermissionState.GRANTED
}

/**
 * The permission decision, as a pure function of the four facts Android will tell us.
 *
 * ## Why these four facts
 * - `granted` — `checkSelfPermission(CAMERA)`.
 * - `hasAsked` — whether *this screen instance* has already launched the request. Android's
 *   `shouldShowRequestPermissionRationale` returns `false` both before the first ask and after the
 *   user chose "don't ask again", so a single boolean cannot distinguish "never asked" from
 *   "permanently refused". Keeping our own "asked at least once" flag is what makes the difference
 *   between showing the rationale and sending someone to Settings.
 * - `showRationale` — `Activity.shouldShowRequestPermissionRationale(CAMERA)`.
 * - `hasCamera` — `PackageManager.FEATURE_CAMERA_ANY`. A device with no lens is not a permission
 *   problem, and telling the user to grant a permission for a camera that does not exist is a lie.
 *
 * Precedence matters: granted wins over everything (a stale rationale flag must never hide a working
 * camera), and "no camera hardware" wins over every permission state.
 */
internal object QrPermissionPolicy {

    fun decide(
        granted: Boolean,
        hasAsked: Boolean,
        showRationale: Boolean,
        hasCamera: Boolean
    ): QrPermissionUi = when {
        granted -> QrPermissionUi(QrPermissionState.GRANTED, QrPermissionAction.NONE)
        !hasCamera -> QrPermissionUi(QrPermissionState.NO_CAMERA, QrPermissionAction.MANUAL_ENTRY_ONLY)
        !hasAsked || showRationale -> QrPermissionUi(QrPermissionState.CAN_ASK, QrPermissionAction.ASK)
        else -> QrPermissionUi(QrPermissionState.PERMANENTLY_DENIED, QrPermissionAction.OPEN_SETTINGS)
    }
}

// =====================================================================================================
// 2. Session state — AnalyzerEvent -> B06 watcher -> QrUiState (pure, camera-free)
// =====================================================================================================

/** The merchant QR that was just read during SETUP, waiting for the shopkeeper's explicit "yes". */
internal data class SetupCapture(
    /** Raw `pa`, kept in memory only for the registration call. Never rendered, never logged. */
    val vpa: String,
    /** `ra***@okaxis` — the only form the screen shows. */
    val masked: String,
    /** `pn`, a printed label. Spoofable by definition; shown with the warning, never compared. */
    val name: String?
) {
    override fun toString(): String = "SetupCapture(masked=$masked, hasName=${name != null})"
}

/** The last valid decode, kept so the screen can show what it is looking at (masked on render). */
private data class DecodedPayee(
    val vpa: String,
    val name: String?,
    val matched: Boolean,
    val atMs: Long
) {
    override fun toString(): String = "DecodedPayee(masked=${PayeeMasking.mask(vpa)}, matched=$matched)"
}

/**
 * Everything the screen renders, as one immutable value.
 *
 * Compose observes [QrGuardSession.view], so a single `publish()` after every mutation is the only
 * recomposition trigger in the flow (no ad-hoc `mutableStateOf` per field to forget).
 */
internal data class QrGuardView(
    val state: QrUiState,
    val pendingSetup: SetupCapture? = null,
    val demoFlow: QrSimulationFlow? = null,
    /** B06's own (masked) closing line for the last demo run. */
    val demoMessage: String? = null,
    /** Stable CameraX fault token, mapped to user copy by [QrHostCopy.faultText]. */
    val faultToken: String? = null,
    /** True once the shopkeeper has created the local re-register PIN for this session. */
    val hasPin: Boolean = false,
    val paymentAdvisory: PaymentAdvisory? = null
) {
    /** True while a demo has been run: the screen must say so instead of implying live decoding. */
    val demoActive: Boolean get() = demoFlow != null

    override fun toString(): String =
        "QrGuardView(phase=${state.phase}, camera=${state.camera}, signal=${state.signal}, " +
            "demo=${demoFlow?.wire}, fault=$faultToken, hasPin=$hasPin)"
}

/**
 * The QR Guard session: the one object that knows both the camera events and the watcher.
 *
 * ## Why a session object rather than state inside the composable
 * The mapping (`AnalyzerEvent` -> `QrWatcher` -> `QrUiState`) is the part most likely to be wrong and
 * the part that cannot be exercised by a Compose test if it lives in a `remember { }` block. Keeping
 * it in a plain class means the instrumented test (and the demo path) can drive *exactly* the
 * production object with `showPreview = false`.
 *
 * ## Threading
 * Every mutator is expected on the main thread: `CameraXQrScanner` dispatches `AnalyzerEvent`s there,
 * the tick runs in a `LaunchedEffect`, and the demo is a button handler. No mutator is called from
 * the analysis thread, so no internal synchronisation is needed (and none is pretended).
 *
 * ## Privacy
 * `registry` holds salted HMAC identifiers only; `pendingSetup` and `lastDecoded` hold a raw `pa` in
 * memory for at most one screen's lifetime and override `toString()` to the masked form. The clock
 * shift used by the demo is a plain `Long`, never a timestamp derived from content.
 *
 * @param clock monotonic milliseconds (`SystemClock.elapsedRealtime` in the app, frozen in tests).
 * @param language initial copy language; C06's `SetLanguage` event changes it afterwards.
 */
internal class QrGuardSession(
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
    language: QrLanguage = QrLanguage.HINGLISH
) {

    // -- injected collaborators ---------------------------------------------------------------------

    /**
     * The single payee extraction binding. Deliberately P3-15's shipped binding rather than a second
     * copy: it turns a missing, malformed or non-UPI `pa` into `null`, which B06 defines as the
     * unreadable path. Re-implementing that here is exactly how a bad read would become a false
     * tamper alarm.
     */
    private val extractor: PayeeExtractor = SimulatedQrFixtures.payeeExtractor()

    private var sessionPin: String? = null
    private var paymentAdvisory: PaymentAdvisory? = null

    /**
     * C06's policy, with a real PIN verifier wired to [sessionPin].
     *
     * The PIN is created on this device, in this session, and compared with [pinsMatch] (constant
     * time, no hashing claim). It is never persisted and never logged; C04 has no PIN field yet, so a
     * session PIN is the honest option — the alternative would be copy pointing at a Settings screen
     * that has no such control.
     */
    private val policy: QrPolicy = QrPolicy(
        pinConfigured = true,
        pinVerifier = PinVerifier { candidate -> sessionPin?.let { pinsMatch(it, candidate) } ?: false }
    )

    private val watcherPolicy: QrWatcherPolicy = QrWatcherPolicy(
        mismatchLimit = policy.mismatchThreshold,
        occlusionMs = policy.occlusionBudgetMs,
        reRegisterLongPressMs = RE_REGISTER_HOLD_MS
    )

    /**
     * Per-session salt. A fresh `SecureRandom` salt per session beats a compiled-in constant: a fixed
     * salt would make the stored identifiers reversible for the low-entropy UPI ids this app sees.
     *
     * GAP (declared in the PR): this registry is **in memory**, so registered payees do not survive
     * process death. Durable storage plus a keystore-backed salt is data-layer work and out of this
     * package's file ownership.
     */
    private val registry: PayeeRegistry = SaltedPayeeRegistry(newSessionSalt())

    // -- clock -------------------------------------------------------------------------------------

    /**
     * Demo time offset. P3-15's flows need five minutes of "no valid decode" to reach occlusion; the
     * demo jumps this offset forward instead of waiting, so the *same* B06 timer resolves on an
     * emulator in milliseconds. Real camera frames always arrive with the note at zero.
     */
    private var demoShiftMs: Long = 0L

    /** When the current watcher started monitoring; the occlusion reference when nothing decoded. */
    private var watchReferenceMs: Long = 0L

    private var watcher: QrWatcher = newWatcher()

    // -- published view ----------------------------------------------------------------------------

    private var published: QrGuardView by mutableStateOf(
        QrGuardView(
            state = QrUiState(
                language = language,
                mismatchThreshold = policy.mismatchThreshold,
                occlusionBudgetMs = policy.occlusionBudgetMs,
                maxPinAttempts = policy.maxPinAttempts
            ),
            hasPin = false
        )
    )

    /** The screen's whole input. Reading this inside a composable subscribes to every change. */
    val view: QrGuardView get() = published

    /** One-shot read for non-composable callers (tests, the PR evidence run). */
    fun snapshot(): QrGuardView = published

    private val state: QrUiState get() = published.state
    private var pendingSetup: SetupCapture? = null
    private var demoFlow: QrSimulationFlow? = null
    private var demoMessage: String? = null
    private var faultToken: String? = null
    private var lastDecoded: DecodedPayee? = null
    private var lastFrameUnreadable: Boolean = false

    // -- public API --------------------------------------------------------------------------------

    /**
     * Feed one analyzer event from the CameraX adapter.
     *
     * `Closed` is intentionally ignored: the scanner emits it on `stop()`, and stopping is the host's
     * decision, not something the session should react to (reacting would resurrect state on teardown).
     */
    fun onAnalyzerEvent(event: AnalyzerEvent) {
        when (event) {
            is AnalyzerEvent.Decoded -> onDecoded(event.payload, event.tsMs)
            is AnalyzerEvent.NoDecode -> onUnreadable()
            is AnalyzerEvent.Unreadable -> onUnreadable()
            is AnalyzerEvent.Error -> onCameraFault(event.message)
            AnalyzerEvent.Closed -> Unit
        }
    }

    /** A stable token from `CameraXQrScanner.onFault` (never an exception message). */
    fun onCameraFault(token: String) {
        faultToken = token
        publish()
    }

    /**
     * A frame decoded to [payload]. In SETUP the payload is *not* compared — it becomes a candidate
     * the shopkeeper must confirm; otherwise it goes to B06, which owns the verdict.
     */
    fun onDecoded(payload: String, atMs: Long = now()) {
        when (state.phase) {
            // A stopped session has no camera; SETUP captures a candidate instead of comparing.
            QrPhase.STOPPED -> return
            QrPhase.SETUP -> {
                captureSetup(payload)
                return
            }
            // RE_REGISTER keeps watching: the phone is still aimed at the QR while the PIN is typed,
            // so a swap during the gate must still raise B06's alert (which force-closes the gate).
            QrPhase.WATCH, QrPhase.RE_REGISTER -> Unit
        }

        val payee = extractor.extract(payload)
        paymentAdvisory = paymentAdvisoryForQr(payload)
        lastFrameUnreadable = payee == null
        lastDecoded = payee?.let {
            DecodedPayee(
                vpa = it,
                name = UpiQrParser.parse(payload).payeeName,
                // A single registry lookup for *presentation* only; the count/verdict is B06's.
                matched = runCatching { registry.matches(it) }.getOrDefault(false),
                atMs = atMs
            )
        }
        apply(watcher.observeDecoded(payload))
    }

    /**
     * No code in this frame — glare, blur, darkness, a covered lens or a half-peeled sticker.
     *
     * B06 pauses the mismatch counter and advances the occlusion timer. This method cannot, by
     * construction, reach `Tampered`.
     */
    fun onUnreadable() {
        if (state.phase == QrPhase.SETUP || state.phase == QrPhase.STOPPED) return
        lastFrameUnreadable = true
        lastDecoded = null
        apply(watcher.observeUnreadable())
    }

    /**
     * The periodic beat. The camera goes silent when the phone is put down, so without this the
     * five-minute occlusion rule could never fire — the one timer the product depends on must not
     * depend on frames arriving.
     */
    fun tick() {
        if (demoFlow != null) return
        if (state.phase != QrPhase.WATCH && state.phase != QrPhase.RE_REGISTER) return
        apply(watcher.refresh(), tick = true)
    }

    /** A UI event from C06's renderer. The reducer decides; B06 is mirrored where it owns the rule. */
    fun onUiEvent(event: QrEvent) {
        val before = state
        // 1. C06 owns the UI transition: language, PIN gate, acknowledge, phase, payee validation.
        putState(QrReducer.reduce(before, event, policy))

        // 2. Mirror only the rules B06 owns, then let the projection reconcile the two.
        when (event) {
            is QrEvent.RegisterPayee, is QrEvent.RemovePayee ->
                if (state.payees != before.payees) syncRegistry(state.payees)

            is QrEvent.StartWatch -> {
                demoShiftMs = 0L
                demoFlow = null
                demoMessage = null
                lastDecoded = null
                lastFrameUnreadable = false
                faultToken = null
            }

            QrEvent.Stop -> {
                demoShiftMs = 0L
                demoFlow = null
                demoMessage = null
                pendingSetup = null
                lastDecoded = null
                lastFrameUnreadable = false
                // A stopped session keeps no verdict memory: a fresh watcher over the same registry.
                watcher = newWatcher()
            }

            QrEvent.RequestReRegister ->
                // The reducer accepted the request (PIN configured, no active alert). B06 still owns
                // the 5 s gate, so the hold is replayed as complete and the PIN as not yet given.
                if (state.phase == QrPhase.RE_REGISTER) {
                    watcher.requestReRegister(RE_REGISTER_HOLD_MS, pinVerified = false)
                }

            is QrEvent.SubmitPin ->
                if (state.pinGate.open && !before.pinGate.open) {
                    // PIN passed: B06 accepts the re-register, clears its registry and returns to SETUP.
                    val accepted = watcher.requestReRegister(RE_REGISTER_HOLD_MS, pinVerified = true)
                    if (accepted is ReRegisterResult.Accepted) {
                        // B06 cleared its own registry: the screen's expected list must follow.
                        putState(state.copy(payees = emptyList()))
                        pendingSetup = null
                    }
                } else if (state.phase == QrPhase.WATCH &&
                    watcher.currentState == QrGuardState.ReRegisterPending
                ) {
                    // Locked out or refused: leave B06's pending gate instead of trapping the screen.
                    watcher = newWatcher()
                }

            QrEvent.CloseReRegister -> {
                if (watcher.currentState == QrGuardState.ReRegisterPending) watcher = newWatcher()
            }

            QrEvent.AcknowledgeMismatch -> watcher.acknowledgeTamper()

            else -> Unit
        }

        putState(project(watcher.status()))
        publish()
    }

    /** The shopkeeper confirmed the QR that was just scanned during SETUP. */
    fun confirmSetupCapture() {
        val capture = pendingSetup ?: return
        pendingSetup = null
        onUiEvent(QrEvent.RegisterPayee(capture.vpa, capture.name))
    }

    /** The scanned QR was not the merchant's: forget the candidate and keep scanning. */
    fun dismissSetupCapture() {
        pendingSetup = null
        publish()
    }

    /** Creates the local re-register PIN. Returns `false` when [pin] is not 4-6 digits. */
    fun setSessionPin(pin: String): Boolean {
        val trimmed = pin.trim()
        val usable = trimmed.length in PIN_MIN_DIGITS..PIN_MAX_DIGITS && trimmed.all { it.isDigit() }
        if (!usable) return false
        sessionPin = trimmed
        publish()
        return true
    }

    /** Reflects the OS permission state into `QrUiState.camera` (C06's own events). */
    fun applyPermission(permission: QrPermissionUi) {
        val event = when (permission.state) {
            QrPermissionState.GRANTED -> QrEvent.PermissionGranted
            QrPermissionState.CAN_ASK, QrPermissionState.PERMANENTLY_DENIED -> QrEvent.PermissionDenied
            QrPermissionState.NO_CAMERA -> QrEvent.NoCamera
        }
        putState(QrReducer.reduce(state, event, policy))
        publish()
    }

    /**
     * Replays one of P3-15's flows through the **same** watcher and the same projection as a real
     * decode. The payloads, the flows and their expected payees come from [SimulatedQrFixtures]; only
     * the arrival offsets live here, mirroring that file's published script (a parity test in
     * `QrGuardHostTest` asserts the two agree on the final B06 state).
     *
     * This is a labelled demo: the screen renders [QrHostCopy.simulationBadge] next to it and never
     * claims live decoding. Nothing here opens a destination.
     */
    fun simulate(flow: QrSimulationFlow) {
        demoFlow = flow
        demoMessage = null
        faultToken = null
        demoShiftMs = 0L
        pendingSetup = null
        lastDecoded = null
        lastFrameUnreadable = false

        installDemoPayees(flow.registeredPayees)
        putState(
            state.copy(
                phase = QrPhase.WATCH,
                consecutiveMismatches = 0,
                mismatchAlert = null,
                occlusion = null,
                lastObserved = null,
                pinGate = PinGateState()
            )
        )

        when (flow) {
            QrSimulationFlow.Match -> repeat(3) { iteration ->
                at(FrameIntervalMs * (iteration + 1)) { onDecoded(SimulatedQrFixtures.merchantQr()) }
            }

            QrSimulationFlow.Mismatch -> repeat(3) { iteration ->
                at(FrameIntervalMs * (iteration + 1)) { onDecoded(SimulatedQrFixtures.stickerQr()) }
            }

            QrSimulationFlow.Unreadable -> {
                // One clean decode, then every flavour of "cannot read": garbage text, a UPI intent
                // with no `pa`, a web URL, a structurally broken `pa`, and finally real no-decode
                // frames until the five-minute occlusion timer fires.
                at(FrameIntervalMs) { onDecoded(SimulatedQrFixtures.merchantQr()) }
                at(FrameIntervalMs * 2) { onDecoded(SimulatedQrFixtures.garbageFrame()) }
                at(FrameIntervalMs * 4) { onDecoded(SimulatedQrFixtures.noPayeeQr()) }
                at(FrameIntervalMs * 6) { onDecoded(SimulatedQrFixtures.urlQr()) }
                at(FrameIntervalMs * 9) { onDecoded(SimulatedQrFixtures.malformedPayeeQr()) }
                at(FrameIntervalMs * 15) { onUnreadable() }
                at(FrameIntervalMs * 31) { onUnreadable() }
            }

            QrSimulationFlow.DualStand -> {
                val stand = listOf(
                    SimulatedQrFixtures.merchantQr(),
                    SimulatedQrFixtures.secondMerchantQr(),
                    SimulatedQrFixtures.merchantQr(),
                    SimulatedQrFixtures.secondMerchantQr()
                )
                stand.forEachIndexed { index, payload ->
                    at(FrameIntervalMs * (index + 1)) { onDecoded(payload) }
                }
            }

            QrSimulationFlow.MismatchAfterDarkFrames -> {
                at(FrameIntervalMs) { onDecoded(SimulatedQrFixtures.stickerQr()) }
                at(FrameIntervalMs * 2) { onDecoded(SimulatedQrFixtures.stickerQr()) }
                at(FrameIntervalMs * 3) { onUnreadable() }
                at(FrameIntervalMs * 5) { onDecoded(SimulatedQrFixtures.garbageFrame()) }
                at(FrameIntervalMs * 6) { onDecoded(SimulatedQrFixtures.stickerQr()) }
            }
        }

        // B06's own message, already masked by the watcher: the demo cannot invent alarm copy.
        demoMessage = watcher.status().message
        publish()
    }

    // ----------------------------------------------------------------------------------- internals

    private fun now(): Long = clock() + demoShiftMs

    private fun at(offsetMs: Long, block: () -> Unit) {
        demoShiftMs = offsetMs
        block()
    }

    private fun putState(next: QrUiState) {
        published = published.copy(state = next)
    }

    private fun publish() {
        published = published.copy(
            pendingSetup = pendingSetup,
            demoFlow = demoFlow,
            demoMessage = demoMessage,
            faultToken = faultToken,
            hasPin = sessionPin != null,
            paymentAdvisory = paymentAdvisory
        )
    }

    private fun paymentAdvisoryForQr(payload: String): PaymentAdvisory? {
        val qr = UpiQrParser.parse(payload)
        val vpa = qr.payeeVpa?.takeIf { qr.isUpi && it.isNotBlank() } ?: return null
        val amount = qr.amount?.takeIf { !qr.hasFlag(ai.trustshield.android.input.qr.UpiFlag.MALFORMED_AMOUNT) }
        val minor = runCatching { amount?.let { BigDecimal(it).movePointRight(2).setScale(0, RoundingMode.UNNECESSARY).longValueExact() } }.getOrNull()
        val fields = PaymentFields(
            amountMinor = minor,
            amountRaw = if (minor != null) "\u20B9${amount}" else "",
            payeeUpi = vpa,
            payeeName = qr.payeeName.orEmpty(),
            status = PaymentStatus.UNKNOWN,
            appHint = PaymentAppHint.UNKNOWN
        )
        return PaymentAdvisoryEngine().evaluate(QrPayeePaymentBridge.toAdvisoryInput(
            sourceText = payload,
            fields = fields,
            payees = state.payees,
            nowEpochMs = System.currentTimeMillis()
        )).takeIf { it.isPaymentScreenshot }?.maskedForPresentation()
    }

    private fun newWatcher(): QrWatcher {
        watchReferenceMs = now()
        return QrWatcher(extractor, registry, ::now, watcherPolicy)
    }

    /**
     * SETUP capture: hold the decoded payee as a *candidate* and wait for an explicit confirmation.
     * Nothing is compared and nothing is registered here — the shopkeeper is the one who knows which
     * QR is on their counter.
     */
    private fun captureSetup(payload: String) {
        val vpa = extractor.extract(payload) ?: return
        if (state.payees.any { it.key == normalizePayeeId(vpa) }) {
            if (pendingSetup != null) {
                pendingSetup = null
                publish()
            }
            return
        }
        val name = UpiQrParser.parse(payload).payeeName?.trim()?.takeIf { it.isNotEmpty() }
        // Masked with the renderer's own helper: one screen must not use two masking styles.
        pendingSetup = SetupCapture(vpa = vpa, masked = maskPayeeId(vpa), name = name)
        publish()
    }

    /**
     * Projects B06's status onto C06's state.
     *
     * Only the verdict fields are overwritten — counters, the alert, the occlusion timer and the last
     * observation. `payees`, `language`, `camera` and the PIN gate stay owned by the reducer.
     *
     * @param tick when true the running occlusion timer is re-read; per-frame projections deliberately
     *        leave it alone so a 30 fps no-decode stream cannot recompose the screen 30 times a second.
     */
    private fun project(status: QrWatcherStatus, tick: Boolean = false): QrUiState {
        val current = state
        val previousAlert = current.mismatchAlert

        // Masked with the same helper the expected-merchant panel uses, so one screen never shows two
        // masking styles for the same shop. Sticky: the first capture of the alert wins.
        val alert = if (status.state == QrGuardState.Tampered) {
            MismatchAlert(
                observedMasked = previousAlert?.observedMasked
                    ?: maskPayeeId(lastDecoded?.vpa ?: status.lastMismatchMasked ?: ""),
                observedName = lastDecoded?.name ?: previousAlert?.observedName,
                expectedMasked = current.payees.map { it.maskedHandle },
                consecutive = status.consecutiveMismatches,
                threshold = policy.mismatchThreshold,
                atMs = previousAlert?.atMs ?: now()
            )
        } else {
            null
        }

        val phase = when {
            current.phase == QrPhase.STOPPED -> QrPhase.STOPPED
            // C06 invariant 3, projected onto B06: an active alert force-closes the PIN gate and puts
            // the screen back into watching, so the alert is never hidden behind a PIN prompt.
            alert != null -> QrPhase.WATCH
            current.phase == QrPhase.SETUP -> QrPhase.SETUP
            current.phase == QrPhase.RE_REGISTER ->
                if (status.state == QrGuardState.Setup) QrPhase.SETUP else QrPhase.RE_REGISTER
            else -> QrPhase.WATCH
        }

        val reference = status.lastValidDecodeAt ?: watchReferenceMs
        val elapsed = (now() - reference).coerceAtLeast(0L)
        val occlusion = when {
            status.state == QrGuardState.Occluded ->
                Occlusion(startedAtMs = reference, elapsedMs = elapsed, timedOut = true)
            // A decode happened: the QR is visible again, so the timer is gone.
            !lastFrameUnreadable -> null
            tick -> Occlusion(
                startedAtMs = reference,
                elapsedMs = elapsed,
                timedOut = elapsed >= policy.occlusionBudgetMs
            )
            else -> current.occlusion
        }

        val observed = lastDecoded?.let {
            ObservedQr(handle = it.vpa, name = it.name, matched = it.matched, atMs = it.atMs)
        }
        val showingVerdict = alert != null || occlusion?.timedOut == true
        val lastObserved = if (
            phase == QrPhase.WATCH && !showingVerdict && !lastFrameUnreadable
        ) {
            observed
        } else {
            null
        }

        return current.copy(
            phase = phase,
            consecutiveMismatches = status.consecutiveMismatches,
            mismatchAlert = alert,
            occlusion = occlusion,
            lastObserved = lastObserved,
            // C06 invariant 3: an active alert force-closes the PIN gate.
            pinGate = if (alert != null) PinGateState() else current.pinGate,
            mismatchThreshold = policy.mismatchThreshold,
            occlusionBudgetMs = policy.occlusionBudgetMs,
            maxPinAttempts = policy.maxPinAttempts
        )
    }

    private fun apply(status: QrWatcherStatus, tick: Boolean = false) {
        putState(project(status, tick = tick))
        publish()
    }

    /**
     * Brings B06's registry in line with the reducer's payee list.
     *
     * The first payee goes through B06's own registration gate when it is still in SETUP (that is the
     * `SETUP -> WATCH` transition the state machine owns). Extra payees — the second counter on a
     * stand, `report §7` — go into the same shared registry, which is what P3-15's dual-stand driver
     * documents. Any change rebuilds the watcher so no verdict carries over from a different payee set.
     */
    private fun syncRegistry(payees: List<RegisteredPayee>) {
        val handles = payees.map { it.handle }
        val viaGate = watcher.currentState == QrGuardState.Setup &&
            handles.isNotEmpty() &&
            runCatching { watcher.registerPayee(handles.first()) }.isSuccess

        if (viaGate) {
            handles.drop(1).forEach { vpa -> runCatching { registry.register(vpa) } }
        } else {
            registry.clear()
            handles.forEach { vpa -> runCatching { registry.register(vpa) } }
            watcher = newWatcher()
        }
    }

    /** Installs a demo's synthetic payees without discarding a real registration. */
    private fun installDemoPayees(vpas: List<String>) {
        val demo = vpas
            .map { it.trim() }
            .filter { isPlausibleUpiId(it) }
            .map { RegisteredPayee(handle = it, name = null) }
        val merged = (state.payees + demo).distinctBy { it.key }
        putState(state.copy(payees = merged))
        syncRegistry(merged)
    }

    private companion object {
        /** P3-15's fixed arrival offset: 10 s, inside the report's 10-30 s watch loop. */
        const val FrameIntervalMs: Long = 10_000L

        fun newSessionSalt(): ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) }
    }
}

// =====================================================================================================
// 3. Copy — Hinglish default, English variant, in Kotlin (res/ strings are not this package's)
// =====================================================================================================

internal object QrHostCopy {

    val cameraHeading = ToneCopy("Camera", "Camera")
    val cameraGranted = ToneCopy("Camera chalu hai", "Camera is on")
    val cameraEmulatorOff = ToneCopy(
        "Emulator par live camera band hai", "Live camera scanning is off on this emulator"
    )
    val cameraHint = ToneCopy(
        "QR ko frame ke beech mein rakhein. Camera sirf QR padhta hai — photo save nahi hoti.",
        "Keep the QR inside the frame. The camera only reads the QR — no photo is saved."
    )
    val cameraAskHelp = ToneCopy(
        "QR Guard ko QR dekhne ke liye camera chahiye. 'Camera allow karein' dabayein — permission sirf QR padhne ke liye hai.",
        "QR Guard needs the camera to see the QR. Tap 'Allow camera' — the permission is only used to read the QR."
    )
    val permanentlyDeniedHelp = ToneCopy(
        "Camera permission band hai. Phone ki Settings mein QR Guard ke liye Camera allow karein, phir yahan wapas aayein — camera khud chalu ho jaayega.",
        "Camera permission is blocked. Allow Camera for QR Guard in the phone's Settings, then come back here — the camera starts on its own."
    )
    val noCameraHelp = ToneCopy(
        "Is phone par camera nahi mila. UPI ID neeche haath se likh kar register kar sakte hain; live nazar is phone par nahi chalegi.",
        "No camera was found on this phone. You can still register a UPI ID by typing it below; live watching will not run here."
    )
    val allowCamera = ToneCopy("Camera allow karein", "Allow camera")
    val openSettings = ToneCopy("Settings kholein", "Open settings")
    val manualFallback = ToneCopy(
        "Camera na ho to UPI ID neeche haath se likh kar register karein.",
        "If the camera is not available, type the UPI ID below to register it."
    )

    val setupCaptureHeading = ToneCopy("Yeh QR mila", "This QR was read")
    val setupCaptureHelp = ToneCopy(
        "Camera ne yeh UPI ID padhi. Agar yeh aapke dukaan ka asli QR hai to register karein — tabhi nazar shuru hogi.",
        "The camera read this UPI ID. Register it only if this is your shop's real QR — watching starts after that."
    )
    val setupCaptureConfirm = ToneCopy("Haan, yeh mera QR hai — register karein", "Yes, this is my QR — register")
    val setupCaptureDismiss = ToneCopy("Nahi, dobara dekhein", "No, scan again")

    val simulationHeading = ToneCopy("Simulate scan (demo)", "Simulate scan (demo)")
    val simulationBadge = ToneCopy(
        "Yeh simulation hai — camera se live decode nahi hota.",
        "This is a simulation — it is not live camera decoding."
    )
    val simulationHelp = ToneCopy(
        "Bina doosre QR ke demo chalane ke liye. Wahi watcher chalta hai jo asli camera par chalta hai.",
        "For demos without a second QR. It drives the same watcher the real camera drives."
    )
    val simulateMatch = ToneCopy("Demo: match", "Demo: match")
    val simulateMismatch = ToneCopy("Demo: sticker tamper", "Demo: sticker tamper")
    val simulateUnreadable = ToneCopy("Demo: unreadable", "Demo: unreadable")
    val simulateDualStand = ToneCopy("Demo: do QR ek stand", "Demo: two QRs, one stand")

    val pinSetupHeading = ToneCopy("Pehle QR badalne ka PIN banayein", "First create a PIN for changing the QR")
    val pinSetupHelp = ToneCopy(
        "4 se 6 ank ka PIN chunein. Naya QR register karne ke liye yahi PIN lagega, taaki koi doosra aapka QR na badal sake.",
        "Choose a 4 to 6 digit PIN. The same PIN is required to register a new QR, so nobody else can swap yours."
    )
    val pinSetupField = ToneCopy("Naya PIN", "New PIN")
    val pinSetupAction = ToneCopy("PIN set karein", "Set PIN")
    val pinSetupInvalid = ToneCopy("PIN 4 se 6 ank ka hona chahiye.", "The PIN must be 4 to 6 digits.")
    val pinSessionOnly = ToneCopy(
        "Yeh PIN sirf is phone par rehta hai aur app band hone par dobara banana hoga.",
        "This PIN stays on this phone only and has to be created again after the app closes."
    )

    val faultGeneric = ToneCopy(
        "Camera is phone par shuru nahi ho paya. Demo se QR Guard chal kar dekh sakte hain.",
        "The camera could not start on this phone. You can still run QR Guard from the demo."
    )
    val faultCameraBusy = ToneCopy(
        "Camera kisi aur app mein chal raha hai. Use band karke dobara try karein, ya QR Guard ka demo chalayein.",
        "Another app is using the camera. Close it and try again, or run the QR Guard demo."
    )
    val faultFrame = ToneCopy(
        "Camera ka frame padha nahi ja raha. QR saaf karein ya phone ko hila kar dobara try karein.",
        "The camera frames cannot be read. Clean the QR or move the phone and try again."
    )
    val faultEmulator = ToneCopy(
        "Emulator par camera default se band hai. QR Guard ko Simulate scan se try karein.",
        "Camera is off by default on emulators. Try QR Guard with Simulate scan."
    )

    /** TalkBack label for the camera panel; includes the state so colour is never the only channel. */
    fun cameraPanelDescription(
        state: QrPermissionState,
        language: QrLanguage = QrLanguage.HINGLISH,
        emulatorCameraOff: Boolean = false,
    ): String {
        if (emulatorCameraOff && state == QrPermissionState.GRANTED) {
            return ToneCopy(
                "Camera permission mili hai, emulator par live scan band hai",
                "Camera permission is granted, live scan is off on this emulator"
            ).text(language)
        }
        val label = when (state) {
            QrPermissionState.GRANTED -> ToneCopy("camera chalu hai", "camera is on")
            QrPermissionState.CAN_ASK -> ToneCopy("camera ki permission chahiye", "camera permission is needed")
            QrPermissionState.PERMANENTLY_DENIED ->
                ToneCopy("camera permission band hai", "camera permission is blocked")
            QrPermissionState.NO_CAMERA -> ToneCopy("is phone par camera nahi hai", "this phone has no camera")
        }
        return ToneCopy("Camera panel — ", "Camera panel — ").text(language) + label.text(language)
    }

    /** TalkBack label for the demo control: a screen reader must hear that this is not live. */
    fun simulationControlDescription(language: QrLanguage = QrLanguage.HINGLISH): String =
        ToneCopy(
            "Simulate scan demo — camera se live decode nahi",
            "Simulate scan demo — not live camera decoding"
        ).text(language)

    /** User copy for a stable CameraX fault token. Never echoes the token itself. */
    fun faultText(token: String): ToneCopy = when (token) {
        CameraXQrScanner.FAULT_CAMERA_BUSY -> faultCameraBusy
        CameraXQrScanner.FAULT_FRAME_PIPELINE -> faultFrame
        CameraXQrScanner.FAULT_EMULATOR_CAMERA_DISABLED -> faultEmulator
        else -> faultGeneric
    }
}

// =====================================================================================================
// 4. The screen content — what the shipped host renders, and what the instrumented test drives
// =====================================================================================================

/**
 * QR Guard content.
 *
 * Split from [QrGuardHost] so the whole screen can be rendered without a camera, a permission dialog
 * or a `LifecycleOwner`: [showPreview] `false` means "no preview surface exists", which is exactly the
 * state of a phone whose camera is refused. The production host supplies the real `PreviewView`.
 */
@Composable
internal fun QrGuardHostContent(
    session: QrGuardSession,
    permission: QrPermissionUi,
    showPreview: Boolean,
    emulatorCameraOff: Boolean = false,
    hapticsEnabled: Boolean,
    onRequestPermission: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    onPreviewCreated: (PreviewView) -> Unit = {},
    onLanguageChanged: (QrLanguage) -> Unit = {},
    onPayeesChanged: (List<RegisteredPayee>) -> Unit = {}
) {
    val view = session.view
    LaunchedEffect(view.state.payees) { onPayeesChanged(view.state.payees) }
    val lang = view.state.language
    val dark = isSystemInDarkTheme()
    val hapticView = LocalView.current

    // C06's `camera` field must tell the truth, otherwise `canStartWatch` and the CAMERA_BLOCKED
    // signal disagree with the OS.
    LaunchedEffect(permission.state) { session.applyPermission(permission) }

    // One haptic on the *transition* into tamper: never per frame, never per decode.
    val tampered = view.state.mismatchAlert != null
    LaunchedEffect(tampered) {
        if (tampered && hapticsEnabled) {
            hapticView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        }
    }

    // The occlusion timer must resolve even when the camera goes silent (QR covered, phone put down).
    val watching = view.state.phase == QrPhase.WATCH || view.state.phase == QrPhase.RE_REGISTER
    LaunchedEffect(watching) {
        while (watching) {
            delay(OCCLUSION_TICK_MS)
            session.tick()
        }
    }

    val dispatch: (QrEvent) -> Unit = { event ->
        session.onUiEvent(event)
        if (event is QrEvent.SetLanguage) onLanguageChanged(event.language)
    }

    Column(modifier = modifier.fillMaxSize()) {
        CameraPanel(
            permission = permission,
            showPreview = showPreview && permission.canUseCamera,
            dark = dark,
            lang = lang,
            emulatorCameraOff = emulatorCameraOff,
            onPreviewCreated = onPreviewCreated,
            onRequestPermission = onRequestPermission,
            onOpenSettings = onOpenSettings
        )

        // C06's renderer, given the rest of the height; it owns its own vertical scroll so nothing
        // here can nest a second scrollable (which Compose refuses to measure).
        QrGuardScreen(
            state = view.state,
            onEvent = dispatch,
            emulatorCameraOff = emulatorCameraOff,
            paymentAdvisory = view.paymentAdvisory,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
        )

        view.faultToken?.let { token ->
            HostPanel(tone = RiskTone.AMBER, dark = dark) {
                Text(
                    text = QrHostCopy.faultText(token).text(lang),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        if (view.state.phase == QrPhase.SETUP) {
            SetupCapturePanel(
                session = session,
                state = view.state,
                capture = view.pendingSetup,
                dark = dark,
                lang = lang
            )
        }

        if (view.state.phase == QrPhase.RE_REGISTER && !view.hasPin) {
            PinSetupPanel(session = session, dark = dark, lang = lang)
        }

        SimulatePanel(session = session, view = view, dark = dark, lang = lang)
    }
}

/**
 * The camera panel: a live preview when there is one, and an honest status panel otherwise.
 *
 * The whole panel carries one content description that names the state, so TalkBack users hear
 * "camera is on" / "camera permission is blocked" rather than a silent grey rectangle.
 */
@Composable
private fun CameraPanel(
    permission: QrPermissionUi,
    showPreview: Boolean,
    dark: Boolean,
    lang: QrLanguage,
    emulatorCameraOff: Boolean,
    onPreviewCreated: (PreviewView) -> Unit,
    onRequestPermission: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val tone = when (permission.state) {
        QrPermissionState.GRANTED -> if (emulatorCameraOff) RiskTone.AMBER else RiskTone.GREEN
        QrPermissionState.NO_CAMERA -> RiskTone.AMBER
        QrPermissionState.CAN_ASK, QrPermissionState.PERMANENTLY_DENIED -> RiskTone.AMBER
    }
    val statusLabel = when (permission.state) {
        QrPermissionState.GRANTED -> if (emulatorCameraOff) QrHostCopy.cameraEmulatorOff else QrHostCopy.cameraGranted
        QrPermissionState.CAN_ASK -> ToneCopy("Camera ki permission chahiye", "Camera permission is needed")
        QrPermissionState.PERMANENTLY_DENIED ->
            ToneCopy("Camera permission band hai", "Camera permission is blocked")
        QrPermissionState.NO_CAMERA -> QrStrings.cameraNone
    }
    val glyph = when (permission.state) {
        QrPermissionState.GRANTED -> if (emulatorCameraOff) "\u2298" else "\u25CF"
        QrPermissionState.NO_CAMERA -> "\u2715"
        else -> "\u2715"
    }

    HostPanel(
        tone = tone,
        dark = dark,
        modifier = Modifier.semantics {
            contentDescription = QrHostCopy.cameraPanelDescription(permission.state, lang, emulatorCameraOff)
        }
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(TsTokens.SpacingSm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            HostBadge(glyph = glyph, tone = tone, dark = dark)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = QrHostCopy.cameraHeading.text(lang),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(text = statusLabel.text(lang), style = MaterialTheme.typography.bodyLarge)
            }
        }

        if (showPreview) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    // Bounded, not aspect-locked: on a 5-inch phone a 4:3 preview would push the
                    // mismatch counter and the demo controls off the screen. 120-200 dp keeps the
                    // preview legible and leaves the rest of the column for the state.
                    .heightIn(min = 120.dp, max = 200.dp)
                    .background(Color.Black, RoundedCornerShape(TsTokens.CornerSmall))
            ) {
                AndroidView(
                    factory = { context ->
                        PreviewView(context).also { view ->
                            view.scaleType = PreviewView.ScaleType.FILL_CENTER
                            onPreviewCreated(view)
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
            Text(
                text = QrHostCopy.cameraHint.text(lang),
                style = MaterialTheme.typography.bodySmall
            )
        }

        when (permission.state) {
            QrPermissionState.GRANTED -> Unit

            QrPermissionState.CAN_ASK -> {
                Text(
                    text = QrHostCopy.cameraAskHelp.text(lang),
                    style = MaterialTheme.typography.bodyMedium
                )
                Button(
                    onClick = onRequestPermission,
                    modifier = Modifier.heightIn(min = TsTokens.MinTouchTarget)
                ) { Text(QrHostCopy.allowCamera.text(lang)) }
            }

            QrPermissionState.PERMANENTLY_DENIED -> {
                Text(
                    text = QrHostCopy.permanentlyDeniedHelp.text(lang),
                    style = MaterialTheme.typography.bodyMedium
                )
                Button(
                    onClick = onOpenSettings,
                    modifier = Modifier.heightIn(min = TsTokens.MinTouchTarget)
                ) { Text(QrHostCopy.openSettings.text(lang)) }
            }

            QrPermissionState.NO_CAMERA -> Text(
                text = QrHostCopy.noCameraHelp.text(lang),
                style = MaterialTheme.typography.bodyMedium
            )
        }

        if (!permission.canUseCamera) {
            Text(
                text = QrHostCopy.manualFallback.text(lang),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** SETUP confirmation: the scanned QR becomes an expected payee only after an explicit tap. */
@Composable
private fun SetupCapturePanel(
    session: QrGuardSession,
    state: QrUiState,
    capture: SetupCapture?,
    dark: Boolean,
    lang: QrLanguage
) {
    HostPanel(tone = RiskTone.NEUTRAL, dark = dark) {
        Text(
            text = QrHostCopy.setupCaptureHeading.text(lang),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() }
        )
        if (capture == null) {
            if (state.payees.isEmpty()) {
                Text(
                    text = QrStrings.setupNeedsPayee.text(lang),
                    style = MaterialTheme.typography.bodyMedium
                )
            } else {
                state.payees.forEach { payee ->
                    Text(
                        text = QrStrings.registeredPayee.text(lang).replace("{id}", payee.maskedHandle),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium
                    )
                }
                Text(
                    text = QrStrings.registeredNextQr.text(lang),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            return@HostPanel
        }
        Text(text = QrHostCopy.setupCaptureHelp.text(lang), style = MaterialTheme.typography.bodySmall)
        // Masked only: the full UPI id never reaches the screen.
        Text(
            text = capture.masked,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium
        )
        capture.name?.let { name ->
            Text(
                text = name,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            text = QrStrings.payeeNameWarning.text(lang),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Button(
            onClick = { session.confirmSetupCapture() },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = TsTokens.MinTouchTarget)
        ) { Text(QrHostCopy.setupCaptureConfirm.text(lang)) }
        TextButton(
            onClick = { session.dismissSetupCapture() },
            modifier = Modifier.heightIn(min = TsTokens.MinTouchTarget)
        ) { Text(QrHostCopy.setupCaptureDismiss.text(lang)) }
    }
}

/**
 * First-run PIN creation for the re-register gate.
 *
 * B06 requires a 5 second press **and** a passed PIN; C06's reducer supplies the attempts/lockout
 * state. C04 has no persisted PIN field yet, so the PIN is created here, kept in memory, compared and
 * then discarded. The screen says so plainly instead of pretending it is saved.
 */
@Composable
private fun PinSetupPanel(session: QrGuardSession, dark: Boolean, lang: QrLanguage) {
    var pin by remember { mutableStateOf("") }
    var invalid by remember { mutableStateOf(false) }

    HostPanel(tone = RiskTone.AMBER, dark = dark) {
        Text(
            text = QrHostCopy.pinSetupHeading.text(lang),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() }
        )
        Text(text = QrHostCopy.pinSetupHelp.text(lang), style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(
            value = pin,
            onValueChange = {
                pin = it.filter { ch -> ch.isDigit() }.take(PIN_MAX_DIGITS)
                invalid = false
            },
            label = { Text(QrHostCopy.pinSetupField.text(lang)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )
        if (invalid) {
            Text(
                text = QrHostCopy.pinSetupInvalid.text(lang),
                style = MaterialTheme.typography.bodyMedium
            )
        }
        Button(
            onClick = {
                if (session.setSessionPin(pin)) {
                    pin = ""
                } else {
                    invalid = true
                }
            },
            modifier = Modifier.heightIn(min = TsTokens.MinTouchTarget)
        ) { Text(QrHostCopy.pinSetupAction.text(lang)) }
        Text(
            text = QrHostCopy.pinSessionOnly.text(lang),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * The demo control. Always labelled as a simulation, in Hinglish and English, because the one thing a
 * QR Guard demo must never imply is that a live camera decoded anything.
 */
@Composable
private fun SimulatePanel(
    session: QrGuardSession,
    view: QrGuardView,
    dark: Boolean,
    lang: QrLanguage
) {
    HostPanel(
        tone = RiskTone.NEUTRAL,
        dark = dark,
        modifier = Modifier.semantics {
            contentDescription = QrHostCopy.simulationControlDescription(lang)
        }
    ) {
        Text(
            text = QrHostCopy.simulationHeading.text(lang),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() }
        )
        Text(
            text = QrHostCopy.simulationBadge.text(lang),
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium
        )
        Text(
            text = QrHostCopy.simulationHelp.text(lang),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        DemoButtonRow(
            left = QrHostCopy.simulateMatch to { session.simulate(QrSimulationFlow.Match) },
            right = QrHostCopy.simulateMismatch to { session.simulate(QrSimulationFlow.Mismatch) }
        )
        DemoButtonRow(
            left = QrHostCopy.simulateUnreadable to { session.simulate(QrSimulationFlow.Unreadable) },
            right = QrHostCopy.simulateDualStand to { session.simulate(QrSimulationFlow.DualStand) }
        )

        val flow = view.demoFlow
        if (flow != null) {
            Text(
                text = flow.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            view.demoMessage?.let { message ->
                // B06's own already-masked message: the demo cannot invent alarm wording.
                Text(text = message, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun DemoButtonRow(
    left: Pair<ToneCopy, () -> Unit>,
    right: Pair<ToneCopy, () -> Unit>
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(TsTokens.SpacingSm)
    ) {
        DemoButton(label = left.first, onClick = left.second)
        DemoButton(label = right.first, onClick = right.second)
    }
}

@Composable
private fun RowScope.DemoButton(label: ToneCopy, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier
            .weight(1f)
            .heightIn(min = TsTokens.MinTouchTarget)
    ) {
        Text(
            text = label.hinglish,
            style = MaterialTheme.typography.labelMedium,
            textAlign = TextAlign.Center
        )
    }
}

/** Bordered, tone-aware panel; same visual language as C06's renderer without importing its privates. */
@Composable
private fun HostPanel(
    tone: RiskTone,
    dark: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        color = tone.container(dark),
        contentColor = tone.onContainer(dark),
        shape = RoundedCornerShape(TsTokens.CornerLarge),
        border = BorderStroke(1.dp, tone.accent(dark)),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = TsTokens.SpacingLg, vertical = TsTokens.SpacingXs)
    ) {
        Column(
            modifier = Modifier.padding(TsTokens.SpacingMd),
            verticalArrangement = Arrangement.spacedBy(TsTokens.SpacingXs),
            content = content
        )
    }
}

/** Tone + glyph badge: a real second channel next to the colour (QUALITY_GATES gate 6). */
@Composable
private fun HostBadge(glyph: String, tone: RiskTone, dark: Boolean) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .background(tone.accent(dark), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = glyph,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (dark) Color(0xFF10141C) else Color.White,
            textAlign = TextAlign.Center
        )
    }
}

// =====================================================================================================
// 5. The shipped host — camera, permission launcher, lifecycle
// =====================================================================================================

/**
 * QR Guard host: the only place in the app that opens a camera for QR purposes.
 *
 * ## Camera lifecycle
 * The scanner is bound to the screen's `LocalLifecycleOwner` and additionally observes the lifecycle
 * itself, because CameraX unbinds on ON_STOP without telling the analyzer: without the explicit
 * `stop()` the analysis executor and the in-flight frame would outlive the screen. Stop, Back and
 * configuration teardown all funnel into the same `stop()`, which is idempotent and shuts the executor
 * down. Nothing is analysed after the screen is gone (`DisposableEffect` + `onDispose`).
 *
 * ## Permission recovery
 * Requested at the point of use. A denial with rationale still asks; a denial without rationale opens
 * the app's **own** settings page (`ACTION_APPLICATION_DETAILS_SETTINGS` for this package — not a
 * scanned destination), and the permission is re-probed on ON_RESUME so returning from Settings starts
 * the camera without leaving the screen. The analyzer is never started without `GRANTED`.
 *
 * ## What never happens here
 * A QR payload is inert: no intent, no URL, no WebView, no network. The only `startActivity` is the
 * app's settings page above.
 */
@Composable
fun QrGuardHost(modifier: Modifier = Modifier, onPayeesChanged: (List<RegisteredPayee>) -> Unit = {}) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val preferences = LocalUserPreferences.current
    val preferencesSink = LocalPreferencesSink.current
    val activity = remember(context) { context.findActivity() }

    val session = remember { QrGuardSession(language = preferences.language.toQrLanguage()) }

    LaunchedEffect(preferences.language) {
        session.onUiEvent(QrEvent.SetLanguage(preferences.language.toQrLanguage()))
    }

    val hasCamera = remember(context) {
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
    }
    val emulatorBuild = remember { isEmulatorBuild() }
    var hasAsked by rememberSaveable { mutableStateOf(false) }

    fun probe(): QrPermissionUi = QrPermissionPolicy.decide(
        granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED,
        hasAsked = hasAsked,
        showRationale = activity?.shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)
            ?: false,
        hasCamera = hasCamera
    )

    var permission by remember { mutableStateOf(probe()) }

    LaunchedEffect(emulatorBuild) {
        if (emulatorBuild) session.onCameraFault(CameraXQrScanner.FAULT_EMULATOR_CAMERA_DISABLED)
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        // From here on we know the user has seen the dialog, which is the only way to tell "never
        // asked" from "don't ask again".
        hasAsked = true
        permission = QrPermissionPolicy.decide(
            granted = granted,
            hasAsked = true,
            showRationale = activity?.shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)
                ?: false,
            hasCamera = hasCamera
        )
    }

    // Re-probe on ON_RESUME: coming back from the app's settings page must start the camera without
    // making the user leave and re-enter the screen.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) permission = probe()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var previewView by remember { mutableStateOf<PreviewView?>(null) }

    val scanner = remember(context) {
        CameraXQrScanner(
            context = context.applicationContext,
            onEvent = { event -> session.onAnalyzerEvent(event) },
            onFault = { token -> session.onCameraFault(token) }
        )
    }

    // The scanner owns a single-thread executor and decoder: release both when the screen
    // leaves the composition, whatever the reason.
    DisposableEffect(scanner) { onDispose { scanner.stop() } }

    val granted = permission.canUseCamera
    val phase = session.view.state.phase
    val cameraShouldRun = granted && phase != QrPhase.STOPPED && !emulatorBuild

    DisposableEffect(lifecycleOwner, previewView, cameraShouldRun) {
        val surface = previewView

        fun startIfPossible() {
            if (cameraShouldRun && surface != null) {
                scanner.start(
                    owner = lifecycleOwner,
                    previewSurface = surface.surfaceProvider,
                    permission = CameraPermissionState.Granted
                )
            }
        }

        startIfPossible()

        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> scanner.stop()
                Lifecycle.Event.ON_START, Lifecycle.Event.ON_RESUME -> startIfPossible()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            // No camera use after leaving the screen.
            scanner.stop()
        }
    }

    QrGuardHostContent(
        session = session,
        permission = permission,
        showPreview = granted && !emulatorBuild,
        emulatorCameraOff = emulatorBuild,
        hapticsEnabled = preferences.haptics,
        onRequestPermission = { launcher.launch(Manifest.permission.CAMERA) },
        onOpenSettings = { context.openAppSettings() },
        modifier = modifier,
        onPreviewCreated = { previewView = it },
        onLanguageChanged = { language ->
            preferencesSink(
                preferences.copy(
                    language = when (language) {
                        QrLanguage.HINGLISH -> AppLanguage.HINGLISH
                        QrLanguage.ENGLISH -> AppLanguage.ENGLISH
                        QrLanguage.HINDI -> AppLanguage.HINDI
                    }
                )
            )
        },
        onPayeesChanged = onPayeesChanged
    )
}

// --------------------------------------------------------------------------------- context helpers ----

/** Unwraps the activity behind a (possibly themed) Compose context. */
private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        val base = current.baseContext
        if (base === current) return null
        current = base
    }
    return null
}

private fun isEmulatorBuild(): Boolean =
    Build.FINGERPRINT.startsWith("generic", ignoreCase = true) ||
        Build.FINGERPRINT.contains("emulator", ignoreCase = true) ||
        Build.MODEL.contains("Emulator", ignoreCase = true) ||
        Build.MODEL.contains("Android SDK built for", ignoreCase = true) ||
        Build.HARDWARE.equals("goldfish", ignoreCase = true) ||
        Build.HARDWARE.equals("ranchu", ignoreCase = true) ||
        Build.HARDWARE.equals("qemu", ignoreCase = true) ||
        Build.PRODUCT.contains("sdk_gphone", ignoreCase = true)

/**
 * Opens this app's own system settings page so a permanently denied camera can be re-enabled.
 *
 * This is the *only* `startActivity` in the QR flow and it targets our own package — never a scanned
 * payload. A device without a settings activity for the package simply does nothing (`runCatching`),
 * which is still better than crashing a shopkeeper's phone mid-demo.
 */
private fun Context.openAppSettings() {
    val intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", packageName, null)
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { startActivity(intent) }
}

/** Maps the persisted language choice onto QR copy. */
private fun AppLanguage.toQrLanguage(): QrLanguage =
    when (resolveLanguage(this, java.util.Locale.getDefault().toLanguageTag())) {
        AppLanguage.SYSTEM, AppLanguage.HINGLISH -> QrLanguage.HINGLISH
        AppLanguage.ENGLISH -> QrLanguage.ENGLISH
        AppLanguage.HINDI -> QrLanguage.HINDI
    }
