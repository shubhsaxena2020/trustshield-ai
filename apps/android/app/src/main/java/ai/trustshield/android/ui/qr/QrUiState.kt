package ai.trustshield.android.ui.qr

import ai.trustshield.android.ui.theme.ToneCopy

// =====================================================================================================
// QR Guard — pure Kotlin state + reducer (work package C06, issue #47).
//
// DELIBERATELY DEPENDENCY-FREE: no `android.*`, no `androidx.compose.*`. Everything here runs in a
// plain JVM unit test (`testDebugUnitTest`), which is the only automated gate this package has —
// there is no Compose UI-test dependency in `app/build.gradle.kts` (Codex-owned). The composable in
// `QrGuardScreen.kt` is a thin renderer over this state and holds no rules.
//
// Grounded in `docs/android/BEHAVIOR_SPEC.md` §"QR and payment semantics" and
// `docs/android/SOURCE_REPORT_senior_dev.md` §7 (QR Tamper Guard states SETUP / WATCH / RE-REGISTER):
//
//  * A QR payload is INERT DATA. Nothing here resolves, opens or fetches a URI. The parser (B02)
//    hands us a decoded `pa` (payee address) string; we only ever compare it locally.
//  * Only a **valid decoded** mismatch is evidence. Three consecutive ones raise the alert.
//  * "No decode" (glare, dark, sticker half-covering, camera covered) is NEVER tamper. It PAUSES the
//    mismatch counter (neither increments nor resets it — so evidence already collected survives)
//    and starts a 5-minute occlusion timer. After 5 minutes the state is UNREADABLE, not TAMPERED.
//  * Re-registering the expected QR requires the app PIN, and that PIN gate is separate from the
//    watcher: an active alert force-closes the gate, and opening the gate never disturbs the
//    mismatch count or the occlusion timer.
//  * The registered payee id is masked everywhere it can be seen or logged — `RegisteredPayee`,
//    `ObservedQr` and `QrUiState` all override `toString()` to the masked form.
//
// SEAM (B02/B03/B06 are merged; their adapters are not wired into this UI yet):
//   B03 analyzer + QR decode -> produces `QrEvent.ValidFrame` / `QrEvent.UnreadableFrame`
//   B02 UPI QR parser        -> validates/normalises `pa` before it reaches this reducer
//   B06 watcher state machine -> owns the 10-30 s poll loop and calls `QrReducer.reduce`
// This file does not create `input/qr/**`, open a camera, or depend on CameraX / ML Kit.
// =====================================================================================================

// ------------------------------------------------------------------------------------ language ----

/** Default is Hinglish; English is the opt-in toggle (HEAD_ORDERS §2 rule 6). */
enum class QrLanguage { HINGLISH, ENGLISH, HINDI }

/** Picks the right half of a bilingual string. */
fun ToneCopy.text(language: QrLanguage): String = when (language) {
    QrLanguage.HINGLISH -> hinglish
    QrLanguage.ENGLISH -> english
    QrLanguage.HINDI -> hindi
}

/** Same as [text] but never blank: falls back to the other language so a panel can never be empty. */
fun ToneCopy.textOrFallback(language: QrLanguage): String {
    val picked = text(language)
    if (picked.isNotBlank()) return picked
    return when (language) {
        QrLanguage.HINGLISH -> english
        QrLanguage.ENGLISH -> hinglish
        QrLanguage.HINDI -> english.ifBlank { hinglish }
    }
}

// -------------------------------------------------------------------------------------- camera ----

/**
 * Camera availability as the screen must show it (UI_SPEC: "Camera denied/no decode — explain
 * grant/retry or better light/angle"; never call no-decode "tamper").
 */
enum class CameraUiStatus {
    /** Not probed yet. */
    UNKNOWN,

    /** Preview/decode loop is running. */
    ACTIVE,

    /** Runtime permission refused or revoked — needs a Settings trip. */
    PERMISSION_DENIED,

    /** Device reports no rear/front camera at all. */
    NO_CAMERA
}

// ---------------------------------------------------------------------------------- flow phase ----

/**
 * Where the user is in the SETUP -> WATCH -> RE-REGISTER loop.
 *
 * Note that the WATCHER keeps running while [RE_REGISTER] is on screen (the phone stays aimed at the
 * QR); frames are still folded into the counters. Only [SETUP] and [STOPPED] are not watching.
 */
enum class QrPhase { SETUP, WATCH, RE_REGISTER, STOPPED }

// -------------------------------------------------------------------------------------- signal ----

/**
 * The single thing the screen highlights right now. Derived from the state, never stored — one
 * source of truth means the banner can't disagree with the counters.
 *
 * [isTamperEvidence] is true for [MISMATCH] only. [UNREADABLE_TIMEOUT] is explicitly NOT tamper
 * evidence (BEHAVIOR_SPEC: "after five minutes display unreadable/occluded, not tampered").
 */
enum class QrSignal {
    NOT_READY,
    IDLE,
    MATCH,
    MISMATCH,
    UNREADABLE_TIMEOUT,
    CAMERA_BLOCKED,
    STOPPED;

    val isTamperEvidence: Boolean get() = this == QrSignal.MISMATCH
}

// --------------------------------------------------------------------------------------- tones ----

/**
 * Presentation tone for QR copy. Deliberately *not* `ai.trustshield.android.ui.theme.RiskTone`:
 * this file must stay free of Compose types so the reducer is testable on a bare JVM. The composable
 * maps [QrTone] -> `RiskTone` one-to-one (`QrGuardScreen.kt`, `toRiskTone()`).
 */
enum class QrTone { NEUTRAL, GREEN, AMBER, RED }

// ------------------------------------------------------------------------------- masking helpers ----

/**
 * Masks a UPI id for display and for logs: first (at most) three characters of the local part plus
 * `***` plus the domain, e.g. `ramesh@okaxis` -> `ram***@okaxis`.
 *
 * The visible prefix is `localPart.length / 2`, capped at 3, so it is always strictly shorter than
 * the local part: the full id can never be reconstructed from the mask (there is no case where the
 * whole local part is shown). The domain is kept because it is not the account identity and the
 * report's own example keeps it (`ram***@okaxis`).
 *
 * Pure string maths — unit tested, and used by `toString()` overrides so nothing leaks into logs.
 */
fun maskPayeeId(raw: String): String {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return "***"
    val at = trimmed.lastIndexOf('@')
    if (at <= 0) {
        val visible = (trimmed.length / 2).coerceAtMost(3)
        return trimmed.take(visible) + "***"
    }
    val local = trimmed.substring(0, at)
    val domain = trimmed.substring(at) // includes the '@'
    val visible = (local.length / 2).coerceAtMost(3)
    return local.take(visible) + "***" + domain
}

/**
 * Structural check for a decoded UPI payee address (`pa`).
 *
 * Only a **valid decoded** value may count as mismatch evidence; a garbage decode is handled exactly
 * like "no decode" (pause + occlusion timer), never as tamper. This is the reducer's own guard so a
 * missing/partial B02 parser cannot turn noise into an accusation.
 */
fun isPlausibleUpiId(raw: String): Boolean {
    val trimmed = raw.trim()
    if (trimmed.length !in 3..64) return false
    if (trimmed.any { it.isWhitespace() }) return false
    val at = trimmed.lastIndexOf('@')
    if (at <= 0 || at != trimmed.indexOf('@')) return false // exactly one '@', not first char
    if (at == trimmed.length - 1) return false // non-empty domain
    val local = trimmed.substring(0, at)
    val domain = trimmed.substring(at + 1)
    // A UPI handle: letters/digits/.-_ in the local part; a dotted-or-bare domain after the '@'
    // (real handles are e.g. `@okaxis`, `@ybl`, `@paytm` — a dot is common but not required).
    if (!local.all { it.isLetterOrDigit() || it == '.' || it == '-' || it == '_' }) return false
    if (!domain.all { it.isLetterOrDigit() || it == '.' || it == '-' }) return false
    if (domain.length < 2) return false
    return true
}

/** Comparison key: case-insensitive, trimmed. Kept private to this contract via [RegisteredPayee.key]. */
fun normalizePayeeId(raw: String): String = raw.trim().lowercase()

/**
 * (Almost) constant-time string compare for the PIN. Pure Kotlin, no crypto dependency: the real
 * secret store and hashing belong to the platform (SEAM: C04 settings + Codex `data/preferences`).
 */
internal fun pinsMatch(expected: String, candidate: String): Boolean {
    if (expected.isEmpty()) return false
    var diff = expected.length xor candidate.length
    val max = maxOf(expected.length, candidate.length)
    for (i in 0 until max) {
        val a = if (i < expected.length) expected[i].code else 0
        val b = if (i < candidate.length) candidate[i].code else 0
        diff = diff or (a xor b)
    }
    return diff == 0
}

// -------------------------------------------------------------------------------------- payees ----

/**
 * One expected payee. Shops against PhonePe + Paytm register two, so the list is the unit of
 * truth and holds any number (>= 2 supported, report §7 edge case "Stand pe 2 QR").
 *
 * [handle] is the real decoded `pa` and must only ever be used for comparison. Render
 * [maskedHandle]. `toString()` is overridden to the masked form so a stray log line cannot leak it.
 */
data class RegisteredPayee(
    val handle: String,
    /** `pn` — a printed label only. Explicitly spoofable; shown as "not proof" in the UI. */
    val name: String? = null
) {
    val key: String get() = normalizePayeeId(handle)
    val maskedHandle: String get() = maskPayeeId(handle)
    val displayName: String? get() = name?.trim()?.takeIf { it.isNotEmpty() }

    override fun toString(): String =
        "RegisteredPayee(maskedHandle=$maskedHandle, hasName=${displayName != null})"
}

/** A single decoded frame, kept only as long as it is on screen (cleared on Stop). */
data class ObservedQr(
    val handle: String,
    val name: String? = null,
    val matched: Boolean,
    val atMs: Long
) {
    val maskedHandle: String get() = maskPayeeId(handle)
    val displayName: String? get() = name?.trim()?.takeIf { it.isNotEmpty() }

    override fun toString(): String =
        "ObservedQr(maskedHandle=$maskedHandle, matched=$matched, atMs=$atMs)"
}

// ----------------------------------------------------------------------------------- occlusion ----

/**
 * The "QR dhak gaya ya hila" timer. Reaching the budget shows the unreadable state — it is not, and
 * never becomes, a tamper verdict.
 */
data class Occlusion(
    val startedAtMs: Long,
    val elapsedMs: Long,
    val timedOut: Boolean
)

// ------------------------------------------------------------------------------------ pin gate ----

enum class QrPinError { NONE, WRONG_PIN, LOCKED_OUT, NOT_CONFIGURED }

/**
 * Re-register gate. [open] is the ONLY way `RegisterPayee`/`RemovePayee` are accepted during an
 * active watch; the watcher itself is untouched by anything in here.
 */
data class PinGateState(
    val open: Boolean = false,
    val failures: Int = 0,
    val lockedOut: Boolean = false,
    val lastError: QrPinError = QrPinError.NONE
)

// ----------------------------------------------------------------------------- mismatch alert ----

/**
 * Raised by the third consecutive *valid decoded* mismatch. Holds only masked ids — an alert that is
 * read out by TalkBack or screenshotted for the demo must not contain a full payee address.
 */
data class MismatchAlert(
    val observedMasked: String,
    val observedName: String?,
    val expectedMasked: List<String>,
    val consecutive: Int,
    val threshold: Int,
    val atMs: Long
) {
    override fun toString(): String =
        "MismatchAlert(observedMasked=$observedMasked, consecutive=$consecutive, threshold=$threshold)"
}

// -------------------------------------------------------------------------------------- policy ----

/**
 * PIN verification seam. In the app this is backed by the user's stored app PIN (C04 / Codex
 * preferences, hashed); in tests it is `PinVerifier.of("1234")`. Keeping it a function keeps the
 * reducer pure and keeps the plain PIN out of the state object entirely.
 */
fun interface PinVerifier {
    fun verify(candidate: String): Boolean

    companion object {
        /** Used when the user never configured a PIN: every attempt fails. */
        val REJECT_ALL: PinVerifier = PinVerifier { false }

        /** Demo/test verifier. SEAM: production uses a salted hash in platform storage. */
        fun of(expectedPin: String): PinVerifier = PinVerifier { candidate -> pinsMatch(expectedPin, candidate) }
    }
}

/**
 * Thresholds and the PIN seam. Defaults come straight from the spec: 3 consecutive valid mismatches,
 * 5 minutes of occlusion, 3 PIN tries.
 */
data class QrPolicy(
    val mismatchThreshold: Int = DEFAULT_MISMATCH_THRESHOLD,
    val occlusionBudgetMs: Long = DEFAULT_OCCLUSION_BUDGET_MS,
    val maxPinAttempts: Int = DEFAULT_MAX_PIN_ATTEMPTS,
    val pinConfigured: Boolean = false,
    val pinVerifier: PinVerifier = PinVerifier.REJECT_ALL
) {
    companion object {
        const val DEFAULT_MISMATCH_THRESHOLD: Int = 3
        const val DEFAULT_OCCLUSION_BUDGET_MS: Long = 5L * 60L * 1000L // 5 minutes
        const val DEFAULT_MAX_PIN_ATTEMPTS: Int = 3

        /** Policy with a working PIN — used by the screen host, previews and tests. */
        fun withPin(pin: String): QrPolicy =
            QrPolicy(pinConfigured = true, pinVerifier = PinVerifier.of(pin))
    }
}

// --------------------------------------------------------------------------------------- state ----

/**
 * The whole QR Guard screen as data.
 *
 * Transient fields ([lastObserved], [mismatchAlert], [occlusion], [consecutiveMismatches]) are
 * cleared by [QrEvent.Stop] so nothing about a session survives teardown (HEAD_ORDERS §2 rule 4).
 * [payees] and [language] are settings-shaped and survive.
 */
data class QrUiState(
    val phase: QrPhase = QrPhase.SETUP,
    val language: QrLanguage = QrLanguage.HINGLISH,
    val payees: List<RegisteredPayee> = emptyList(),
    val camera: CameraUiStatus = CameraUiStatus.UNKNOWN,
    val consecutiveMismatches: Int = 0,
    val occlusion: Occlusion? = null,
    val lastObserved: ObservedQr? = null,
    val mismatchAlert: MismatchAlert? = null,
    val pinGate: PinGateState = PinGateState(),
    val mismatchThreshold: Int = QrPolicy.DEFAULT_MISMATCH_THRESHOLD,
    val occlusionBudgetMs: Long = QrPolicy.DEFAULT_OCCLUSION_BUDGET_MS,
    val maxPinAttempts: Int = QrPolicy.DEFAULT_MAX_PIN_ATTEMPTS
) {
    /** What the screen highlights. Single derivation, so banner and counters cannot drift apart. */
    val signal: QrSignal
        get() = when (phase) {
            QrPhase.STOPPED -> QrSignal.STOPPED
            QrPhase.SETUP -> if (payees.isEmpty()) QrSignal.NOT_READY else QrSignal.IDLE
            QrPhase.RE_REGISTER -> QrSignal.IDLE
            QrPhase.WATCH -> when {
                mismatchAlert != null -> QrSignal.MISMATCH
                camera == CameraUiStatus.PERMISSION_DENIED -> QrSignal.CAMERA_BLOCKED
                camera == CameraUiStatus.NO_CAMERA -> QrSignal.CAMERA_BLOCKED
                occlusion?.timedOut == true -> QrSignal.UNREADABLE_TIMEOUT
                lastObserved?.matched == true -> QrSignal.MATCH
                else -> QrSignal.IDLE
            }
        }

    /** True only for a valid decoded mismatch streak. Unreadable/occluded is never tamper. */
    val isTamperEvidence: Boolean get() = signal.isTamperEvidence

    /** "2 / 3" style progress for the visible valid-mismatch count. */
    val mismatchProgress: String get() = "$consecutiveMismatches / $mismatchThreshold"

    /** True when the watcher is folding frames into the counters. */
    val isWatching: Boolean get() = phase == QrPhase.WATCH || phase == QrPhase.RE_REGISTER

    val canStartWatch: Boolean
        get() = camera == CameraUiStatus.ACTIVE && payees.isNotEmpty() && phase != QrPhase.WATCH

    /** Masked, comma-joined expected ids — the only form the UI may render. */
    val expectedMaskedJoined: String get() = payees.joinToString(", ") { it.maskedHandle }

    /** The guidance panel the screen must show, if any. */
    val guidance: QrGuidance
        get() = when {
            mismatchAlert != null -> QrGuidance.MISMATCH_ACTION
            pinGate.lockedOut -> QrGuidance.PIN_LOCKED_OUT
            pinGate.lastError == QrPinError.NOT_CONFIGURED -> QrGuidance.PIN_NOT_CONFIGURED
            camera == CameraUiStatus.PERMISSION_DENIED -> QrGuidance.GRANT_CAMERA_PERMISSION
            camera == CameraUiStatus.NO_CAMERA -> QrGuidance.NO_CAMERA
            occlusion?.timedOut == true -> QrGuidance.OCCLUDED
            phase == QrPhase.SETUP && payees.isEmpty() -> QrGuidance.REGISTER_PAYEE
            else -> QrGuidance.NONE
        }

    // Never let a log line carry a full payee id: `payees` and `lastObserved` already mask
    // themselves, and this override drops nothing else that is sensitive.
    override fun toString(): String =
        "QrUiState(phase=$phase, camera=$camera, payees=${payees.size}, " +
            "mismatches=$consecutiveMismatches/$mismatchThreshold, signal=$signal, " +
            "occluded=${occlusion?.timedOut == true}, pinGate=$pinGate, alert=${mismatchAlert != null})"
}

enum class QrGuidance {
    NONE,
    REGISTER_PAYEE,
    GRANT_CAMERA_PERMISSION,
    NO_CAMERA,
    OCCLUDED,
    MISMATCH_ACTION,
    PIN_LOCKED_OUT,
    PIN_NOT_CONFIGURED
}

// -------------------------------------------------------------------------------------- events ----

/**
 * Everything the screen can raise. Timestamps are carried by the caller so the reducer needs no
 * clock and stays deterministic under test.
 */
sealed interface QrEvent {

    /** Runtime camera permission granted (point of use, per UI_SPEC). */
    data object PermissionGranted : QrEvent

    /** Runtime camera permission denied/revoked. Pauses; never resets tamper evidence. */
    data object PermissionDenied : QrEvent

    /** No camera on the device. */
    data object NoCamera : QrEvent

    /** User starts the watch loop (needs camera + at least one registered payee). */
    data class StartWatch(val atMs: Long = 0L) : QrEvent

    /** User stops the watch (Back/Stop always ends the session). Clears transient payload. */
    data object Stop : QrEvent

    /**
     * A decoded QR whose `pa` this reducer receives from B02 (parser) / B03 (decode).
     * Only plausible UPI ids count; anything else is folded into "no decode".
     */
    data class ValidFrame(
        val payeeAddress: String,
        val payeeName: String? = null,
        val atMs: Long
    ) : QrEvent

    /** Glare, dark, camera covered, sticker half on, or a decode that produced nothing usable. */
    data class UnreadableFrame(val atMs: Long) : QrEvent

    /** Timer beat so a 5-minute occlusion resolves even if the decode loop goes silent. */
    data class Tick(val atMs: Long) : QrEvent

    /** Registers/replaces an expected payee (allowed in SETUP, or in RE-REGISTER with the gate open). */
    data class RegisterPayee(
        val payeeAddress: String,
        val payeeName: String? = null
    ) : QrEvent

    /** Removes an expected payee (same gating as [RegisterPayee]). */
    data class RemovePayee(val payeeAddress: String) : QrEvent

    /** Asks for the re-register overlay; the PIN gate stays closed until [SubmitPin] succeeds. */
    data object RequestReRegister : QrEvent

    /** PIN attempt for the re-register gate. */
    data class SubmitPin(val pin: String) : QrEvent

    /** Leaves the re-register overlay without changing anything. */
    data object CloseReRegister : QrEvent

    /** User has read the mismatch alert; count returns to zero. */
    data object AcknowledgeMismatch : QrEvent

    data class SetLanguage(val language: QrLanguage) : QrEvent

    data object ToggleLanguage : QrEvent
}

// ------------------------------------------------------------------------------------ reducer ----

/**
 * The rules. Every branch is pure: same state + same event => same state.
 *
 * Invariants (each one is a test in `QrGuardScreenTest`):
 *  1. `UnreadableFrame` never increments, never resets and never escalates:
 *     `consecutiveMismatches` is byte-identical before and after, and the signal is
 *     [QrSignal.UNREADABLE_TIMEOUT] (after 5 min) — not [QrSignal.MISMATCH].
 *  2. A decoded match resets `consecutiveMismatches` to 0 and clears the occlusion timer.
 *  3. `consecutiveMismatches >= threshold` (3) raises [MismatchAlert] and force-closes the PIN gate.
 *  4. `RegisterPayee`/`RemovePayee` outside SETUP are ignored unless `pinGate.open` — no PIN, no
 *     re-registration.
 *  5. `RequestReRegister` does not touch `consecutiveMismatches` / `occlusion` (separate concerns).
 *  6. `Stop` clears `lastObserved`, `mismatchAlert`, `occlusion`, the counter and the PIN gate;
 *     registered payees and language survive.
 */
object QrReducer {

    fun reduce(state: QrUiState, event: QrEvent, policy: QrPolicy = QrPolicy()): QrUiState {
        val s = state.copy(
            mismatchThreshold = policy.mismatchThreshold,
            occlusionBudgetMs = policy.occlusionBudgetMs,
            maxPinAttempts = policy.maxPinAttempts
        )
        return when (event) {
            QrEvent.PermissionGranted ->
                s.copy(camera = CameraUiStatus.ACTIVE)

            QrEvent.PermissionDenied ->
                // Pause, don't punish: counters are evidence, a missing permission is not.
                s.copy(camera = CameraUiStatus.PERMISSION_DENIED, occlusion = null)

            QrEvent.NoCamera ->
                s.copy(camera = CameraUiStatus.NO_CAMERA, occlusion = null)

            is QrEvent.StartWatch -> startWatch(s)

            QrEvent.Stop -> stop(s)

            is QrEvent.ValidFrame -> onValidFrame(s, event)

            is QrEvent.UnreadableFrame -> onNoDecode(s, event.atMs)

            is QrEvent.Tick -> onNoDecode(s, event.atMs)

            is QrEvent.RegisterPayee -> registerPayee(s, event)

            is QrEvent.RemovePayee -> removePayee(s, event)

            QrEvent.RequestReRegister -> requestReRegister(s, policy)

            is QrEvent.SubmitPin -> submitPin(s, event, policy)

            QrEvent.CloseReRegister -> closeReRegister(s)

            QrEvent.AcknowledgeMismatch -> acknowledge(s)

            is QrEvent.SetLanguage -> s.copy(language = event.language)

            QrEvent.ToggleLanguage -> s.copy(
                language = if (s.language == QrLanguage.HINGLISH) QrLanguage.ENGLISH else QrLanguage.HINGLISH
            )
        }
    }

    // -- flow ---------------------------------------------------------------------------------------

    private fun startWatch(s: QrUiState): QrUiState {
        if (s.phase == QrPhase.WATCH) return s
        if (s.camera != CameraUiStatus.ACTIVE) return s // no camera / denied: nothing to watch with
        if (s.payees.isEmpty()) return s // nothing to compare against
        return s.copy(
            phase = QrPhase.WATCH,
            consecutiveMismatches = 0,
            occlusion = null,
            lastObserved = null,
            mismatchAlert = null,
            pinGate = PinGateState()
        )
    }

    private fun stop(s: QrUiState): QrUiState = s.copy(
        phase = QrPhase.STOPPED,
        consecutiveMismatches = 0,
        occlusion = null,
        lastObserved = null,
        mismatchAlert = null,
        pinGate = PinGateState()
    )

    // -- frames -------------------------------------------------------------------------------------

    private fun onValidFrame(s: QrUiState, event: QrEvent.ValidFrame): QrUiState {
        if (!s.isWatching) return s
        if (s.mismatchAlert != null) return s // frozen until acknowledged: evidence stays readable
        // A decode that isn't a structurally valid UPI id is "no decode", not evidence.
        if (!isPlausibleUpiId(event.payeeAddress)) return onNoDecode(s, event.atMs)

        val key = normalizePayeeId(event.payeeAddress)
        val matched = s.payees.any { it.key == key }
        val observed = ObservedQr(
            handle = event.payeeAddress.trim(),
            name = event.payeeName,
            matched = matched,
            atMs = event.atMs
        )

        if (matched) {
            // Invariant 2: a real match is the only thing that resets the streak.
            return s.copy(consecutiveMismatches = 0, occlusion = null, lastObserved = observed)
        }

        val count = s.consecutiveMismatches + 1
        val alert = if (count >= s.mismatchThreshold) {
            MismatchAlert(
                observedMasked = observed.maskedHandle,
                observedName = observed.displayName,
                expectedMasked = s.payees.map { it.maskedHandle },
                consecutive = count,
                threshold = s.mismatchThreshold,
                atMs = event.atMs
            )
        } else {
            null
        }
        return s.copy(
            consecutiveMismatches = count,
            occlusion = null, // a decode happened: the QR is visible again
            lastObserved = observed,
            mismatchAlert = alert,
            // Nobody re-registers while a tamper alert is up.
            phase = if (alert != null) QrPhase.WATCH else s.phase,
            pinGate = if (alert != null) PinGateState() else s.pinGate
        )
    }

    /**
     * No-decode path: glare, darkness, a covered lens, a half-peeled sticker, or a garbage decode.
     *
     * Invariant 1: the mismatch counter is passed through untouched — evidence is neither inflated
     * nor erased — and the 5-minute timer decides between "keep trying" and UNREADABLE. This path can
     * never produce [QrSignal.MISMATCH].
     */
    private fun onNoDecode(s: QrUiState, atMs: Long): QrUiState {
        if (!s.isWatching) return s
        if (s.mismatchAlert != null) return s // alert already up; timer paused
        val started = s.occlusion?.startedAtMs ?: atMs
        val elapsed = (atMs - started).coerceAtLeast(0L)
        return s.copy(
            occlusion = Occlusion(
                startedAtMs = started,
                elapsedMs = elapsed,
                timedOut = elapsed >= s.occlusionBudgetMs
            ),
            // The QR currently in front of the camera is unproven, so drop the last verdict.
            lastObserved = null
        )
    }

    // -- payee list ---------------------------------------------------------------------------------

    private fun canEditPayees(s: QrUiState): Boolean = when (s.phase) {
        QrPhase.SETUP -> true
        QrPhase.RE_REGISTER -> s.pinGate.open // invariant 4
        else -> false
    }

    private fun registerPayee(s: QrUiState, event: QrEvent.RegisterPayee): QrUiState {
        if (!canEditPayees(s) || s.mismatchAlert != null) return s
        if (!isPlausibleUpiId(event.payeeAddress)) return s
        val payee = RegisteredPayee(
            handle = event.payeeAddress.trim(),
            name = event.payeeName?.trim()?.takeIf { it.isNotEmpty() }
        )
        val index = s.payees.indexOfFirst { it.key == payee.key }
        val next = if (index >= 0) {
            s.payees.toMutableList().also { it[index] = payee }
        } else {
            s.payees + payee
        }
        return s.copy(payees = next)
    }

    private fun removePayee(s: QrUiState, event: QrEvent.RemovePayee): QrUiState {
        if (!canEditPayees(s) || s.mismatchAlert != null) return s
        val key = normalizePayeeId(event.payeeAddress)
        val next = s.payees.filterNot { it.key == key }
        return if (next.size == s.payees.size) s else s.copy(payees = next)
    }

    // -- pin gate -----------------------------------------------------------------------------------

    private fun requestReRegister(s: QrUiState, policy: QrPolicy): QrUiState {
        if (s.phase != QrPhase.WATCH) return s // SETUP is already editable; STOPPED has no session
        if (s.mismatchAlert != null) return s // resolve the alert first
        if (s.pinGate.lockedOut) {
            return s.copy(pinGate = s.pinGate.copy(open = false, lastError = QrPinError.LOCKED_OUT))
        }
        if (!policy.pinConfigured) {
            // Honest failure: no PIN means no re-register. Copy points at Settings.
            return s.copy(pinGate = s.pinGate.copy(open = false, lastError = QrPinError.NOT_CONFIGURED))
        }
        // Invariant 5: watcher evidence (counter, occlusion) is deliberately left exactly as it was.
        return s.copy(
            phase = QrPhase.RE_REGISTER,
            pinGate = s.pinGate.copy(open = false, lastError = QrPinError.NONE)
        )
    }

    private fun submitPin(s: QrUiState, event: QrEvent.SubmitPin, policy: QrPolicy): QrUiState {
        if (s.phase != QrPhase.RE_REGISTER) return s
        if (s.pinGate.lockedOut) {
            return s.copy(pinGate = s.pinGate.copy(open = false, lastError = QrPinError.LOCKED_OUT))
        }
        if (!policy.pinConfigured) {
            return s.copy(pinGate = s.pinGate.copy(open = false, lastError = QrPinError.NOT_CONFIGURED))
        }
        if (policy.pinVerifier.verify(event.pin)) {
            return s.copy(pinGate = PinGateState(open = true))
        }
        val failures = s.pinGate.failures + 1
        val locked = failures >= s.maxPinAttempts
        return s.copy(
            pinGate = PinGateState(
                open = false,
                failures = failures,
                lockedOut = locked,
                lastError = if (locked) QrPinError.LOCKED_OUT else QrPinError.WRONG_PIN
            ),
            // Locked out: drop back to watching (the gate cannot be opened any more today).
            phase = if (locked) QrPhase.WATCH else s.phase
        )
    }

    private fun closeReRegister(s: QrUiState): QrUiState {
        val back = if (s.payees.isEmpty()) QrPhase.SETUP else QrPhase.WATCH
        val phase = when (s.phase) {
            QrPhase.RE_REGISTER -> back
            else -> s.phase
        }
        return s.copy(phase = phase, pinGate = PinGateState())
    }

    private fun acknowledge(s: QrUiState): QrUiState = s.copy(
        mismatchAlert = null,
        consecutiveMismatches = 0,
        lastObserved = null,
        phase = if (s.payees.isEmpty()) QrPhase.SETUP else QrPhase.WATCH
    )
}

// ---------------------------------------------------------------------------------------- copy ----

/**
 * QR Guard copy (Hinglish default + English). Lives in Kotlin, not a `res/` string entry:
 * eight packages write in parallel and only Codex may create resource files.
 */
object QrStrings {

    val title = ToneCopy("QR Guard", "QR Guard")
    val subtitle = ToneCopy(
        "Dukaan ke QR par nazar — upar chipke nakli sticker ko pakadne ke liye",
        "Keeps an eye on the shop QR to catch a sticker pasted on top"
    )
    val languageLabel = ToneCopy("Bhasha", "Language")
    val hinglish = ToneCopy("Hinglish", "Hinglish")
    val english = ToneCopy("English", "English")

    // camera
    val cameraHeading = ToneCopy("Camera", "Camera")
    val cameraUnknown = ToneCopy("Camera check ho raha hai", "Checking the camera")
    val cameraActive = ToneCopy(
        "Camera chalu hai — QR ko frame ke beech mein rakhein",
        "Camera is on — keep the QR inside the frame"
    )
    val cameraEmulatorOff = ToneCopy(
        "Emulator par live camera band hai",
        "Live camera scanning is off on this emulator"
    )
    val cameraDenied = ToneCopy("Camera ki permission nahi mili", "Camera permission was denied")
    val cameraDeniedHelp = ToneCopy(
        "Phone ki Settings mein camera permission dein, phir yahan wapas aayein. Permission ke bina QR Guard kaam nahi karta.",
        "Allow camera access in the phone's Settings, then come back here. QR Guard cannot work without it."
    )
    val cameraNone = ToneCopy("Is phone par camera nahi mila", "No camera found on this phone")
    val cameraNoneHelp = ToneCopy(
        "QR Guard ko QR dekhne ke liye camera chahiye. Aap UPI ID haath se likh kar register kar sakte hain, lekin live nazar sirf camera se hoti hai.",
        "QR Guard needs a camera to see the QR. You can still register a UPI ID by typing it, but live watching needs the camera."
    )

    // expected merchant
    val expectedHeading = ToneCopy("Expected merchant", "Expected merchant")
    val expectedEmpty = ToneCopy(
        "Abhi koi UPI ID register nahi hai. Pehle asli QR register karein.",
        "No UPI ID is registered yet. Register the real QR first."
    )
    val payeeNameWarning = ToneCopy(
        "QR par likha naam badla ja sakta hai. Bharosa sirf UPI ID ki tulna par karein.",
        "The name printed on a QR can be swapped. Trust only the UPI ID comparison."
    )
    val addPayeeHint = ToneCopy("UPI ID (jaise dukaan@okaxis)", "UPI ID (e.g. shop@okaxis)")
    val addPayeeName = ToneCopy("Naam (optional)", "Name (optional)")
    val addPayee = ToneCopy("Register karein", "Register")
    val removePayee = ToneCopy("Hatayein", "Remove")
    val maskedNote = ToneCopy(
        "Poora UPI ID kabhi screen par ya log mein nahi dikhta.",
        "The full UPI ID is never shown on screen or written to logs."
    )
    val setupNeedsPayee = ToneCopy(
        "Kam se kam ek UPI ID register karein, phir nazar shuru karein.",
        "Register at least one UPI ID, then start watching."
    )
    val setupNeedsCamera = ToneCopy(
        "Camera ke bina nazar shuru nahi hoti.",
        "Watching cannot start without the camera."
    )
    val registeredPayee = ToneCopy("Register ho gaya: {id}", "Registered: {id}")
    val registeredNextQr = ToneCopy(
        "Ek aur QR scan karein ya nazar shuru karein.",
        "Scan another QR or start watching."
    )

    val startWatch = ToneCopy("Nazar shuru karein", "Start watching")
    val stopWatch = ToneCopy("Band karein", "Stop")
    val stoppedNote = ToneCopy(
        "Monitoring band hai. Camera dobara on karne ke liye nazar shuru karein.",
        "Monitoring is stopped. Start watching to turn the camera back on."
    )

    // counters
    val mismatchCounterLabel = ToneCopy("Lagatar valid mismatch", "Consecutive valid mismatch")
    val occlusionTimerLabel = ToneCopy("QR dikhna band (timer)", "No QR visible (timer)")
    val elapsedSeconds = ToneCopy("s", "s", "सेकंड")
    val occlusionNotTamper = ToneCopy(
        "QR na dikhna tamper ka saboot nahi hai.",
        "Not seeing the QR is not proof of tampering."
    )

    // alerts
    val acknowledge = ToneCopy("Samajh gaya — alert band karein", "Understood — dismiss alert")
    val stopPaymentNow = ToneCopy(
        "Abhi payment mat karein.",
        "Do not pay right now."
    )

    // re-register / pin
    val reRegister = ToneCopy("Naya QR register karein (PIN)", "Register a new QR (PIN)")
    val reRegisterHold = ToneCopy(
        "Naya QR lagane ke liye 5 second dabayein",
        "Press and hold for 5 seconds to set a new QR"
    )
    val reRegisterHolding = ToneCopy(
        "Pakad kar rakhein… 5 second pure hone par PIN poochha jayega",
        "Keep holding… the PIN is asked once the 5 seconds are up"
    )
    val reRegisterHint = ToneCopy(
        "Asli dukaandaar hi naya QR register kar sakta hai — isliye app PIN chahiye.",
        "Only the real shopkeeper can register a new QR, so the app PIN is required."
    )
    val pinLabel = ToneCopy("App PIN", "App PIN")
    val pinSubmit = ToneCopy("PIN se kholein", "Unlock with PIN")
    val pinCancel = ToneCopy("Wapas", "Back")
    val pinWrong = ToneCopy("PIN galat hai. Dobara koshish karein.", "Wrong PIN. Try again.")
    val pinLockedOut = ToneCopy(
        "Bahut baar galat PIN. Re-register band hai — app ko dobara kholein.",
        "Too many wrong PINs. Re-register is locked — reopen the app."
    )
    val pinNotConfigured = ToneCopy(
        "Pehle Settings mein app PIN set karein, tabhi naya QR register ho sakta hai.",
        "Set an app PIN in Settings first; only then can a new QR be registered."
    )
    val pinUnlocked = ToneCopy(
        "PIN sahi hai — ab asli QR register karein.",
        "PIN accepted — now register the real QR."
    )

    // mandatory footer

    /** MANDATORY on the QR Guard screen, verbatim (BEHAVIOR_SPEC §"QR and payment semantics"). */
    val mandatoryConfirmation = ToneCopy(
        "Pakka confirmation sirf aapke bank SMS ya UPI app ki history mein milega.",
        "Final confirmation only comes from your bank SMS or your UPI app history."
    )
}

/** Label + icon + text for every signal: colour is never the only channel. */
data class QrSignalPresentation(
    val signal: QrSignal,
    val tone: QrTone,
    val label: ToneCopy,
    val detail: ToneCopy,
    /** Material icon key the screen may resolve later (no icon font is on the classpath today). */
    val iconKey: String,
    /** Drawn glyph fallback — always visible, therefore always a non-colour channel. */
    val glyph: String,
    val contentDescription: ToneCopy
)

object QrSignalCopy {

    private val NOT_READY_P = QrSignalPresentation(
        signal = QrSignal.NOT_READY,
        tone = QrTone.NEUTRAL,
        label = ToneCopy("Setup adhura hai", "Setup is incomplete"),
        detail = ToneCopy(
            "Pehle asli dukaan ka QR register karein, phir camera se nazar rakhein.",
            "Register the shop's real QR first, then keep an eye on it with the camera."
        ),
        iconKey = "Icons.Outlined.Info",
        glyph = "i",
        contentDescription = ToneCopy("Setup adhura hai", "Setup is incomplete")
    )

    private val IDLE_P = QrSignalPresentation(
        signal = QrSignal.IDLE,
        tone = QrTone.NEUTRAL,
        label = ToneCopy("Nazar chal rahi hai", "Watching"),
        detail = ToneCopy(
            "QR ka intezaar hai. Customer ke saamne bhi phone QR ki taraf rakha rahe.",
            "Waiting for the QR. Keep the phone pointed at it while a customer is paying."
        ),
        iconKey = "Icons.Outlined.Visibility",
        glyph = "◎",
        contentDescription = ToneCopy("Nazar chal rahi hai, QR ka intezaar", "Watching, waiting for the QR")
    )

    private val MATCH_P = QrSignalPresentation(
        signal = QrSignal.MATCH,
        tone = QrTone.GREEN,
        label = ToneCopy("QR match hua", "QR matches"),
        detail = ToneCopy(
            "Yeh QR aapke register kiye UPI ID se milta hai. Amount aur naam payment screen par khud check karein.",
            "This QR matches a registered UPI ID. Still check the amount and name on the payment screen yourself."
        ),
        iconKey = "Icons.Outlined.CheckCircle",
        glyph = "✓",
        contentDescription = ToneCopy("QR aapke register kiye UPI ID se match hua", "QR matches a registered UPI ID")
    )

    private val MISMATCH_P = QrSignalPresentation(
        signal = QrSignal.MISMATCH,
        tone = QrTone.RED,
        label = ToneCopy("QR badal gaya lagta hai", "QR looks changed"),
        detail = ToneCopy(
            "Lagatar valid mismatch mile hain — QR ka UPI ID aapke register kiye ID se alag hai. Payment mat karein, dukaandaar se poochhein.",
            "Consecutive valid mismatches — the QR's UPI ID differs from the registered one. Do not pay; check with the shopkeeper."
        ),
        iconKey = "Icons.Filled.Report",
        glyph = "!!",
        contentDescription = ToneCopy(
            "Khatra: QR ka UPI ID aapke register kiye ID se alag hai",
            "Warning: the QR's UPI ID differs from the registered one"
        )
    )

    private val UNREADABLE_P = QrSignalPresentation(
        signal = QrSignal.UNREADABLE_TIMEOUT,
        tone = QrTone.AMBER,
        label = ToneCopy("QR nahi dikh raha", "QR not visible"),
        detail = ToneCopy(
            "5 minute se QR padha nahi ja saka — dhak gaya, hil gaya ya sticker aadha chipka hai. Yeh tamper ka saboot nahi hai: camera ka angle, roshni aur QR saaf karein.",
            "The QR has not been readable for 5 minutes — covered, moved, or half a sticker. This is not proof of tampering: fix the angle, the light and clear the QR."
        ),
        iconKey = "Icons.Outlined.WarningAmber",
        glyph = "!",
        contentDescription = ToneCopy("QR nahi dikh raha — tamper ka saboot nahi", "QR not visible — not proof of tampering")
    )

    private val CAMERA_BLOCKED_P = QrSignalPresentation(
        signal = QrSignal.CAMERA_BLOCKED,
        tone = QrTone.AMBER,
        label = ToneCopy("Camera band hai", "Camera is blocked"),
        detail = ToneCopy(
            "Camera ke bina nazar nahi ho sakti. Permission dein ya doosra phone use karein.",
            "Without the camera there is no watching. Grant permission or use another phone."
        ),
        iconKey = "Icons.Outlined.NoPhotography",
        glyph = "✕",
        contentDescription = ToneCopy("Camera band hai, nazar ruki hui", "Camera is blocked, watching is paused")
    )

    private val STOPPED_P = QrSignalPresentation(
        signal = QrSignal.STOPPED,
        tone = QrTone.NEUTRAL,
        label = ToneCopy("Monitoring band hai", "Monitoring stopped"),
        detail = ToneCopy(
            "Session band hai aur QR ka data clear ho gaya. Dobara shuru karne ke liye nazar shuru karein.",
            "The session is over and the QR data is cleared. Start watching to begin again."
        ),
        iconKey = "Icons.Outlined.Stop",
        glyph = "■",
        contentDescription = ToneCopy("Monitoring band hai", "Monitoring stopped")
    )

    val all: List<QrSignalPresentation> = listOf(
        NOT_READY_P, IDLE_P, MATCH_P, MISMATCH_P, UNREADABLE_P, CAMERA_BLOCKED_P, STOPPED_P
    )

    fun of(signal: QrSignal): QrSignalPresentation = when (signal) {
        QrSignal.NOT_READY -> NOT_READY_P
        QrSignal.IDLE -> IDLE_P
        QrSignal.MATCH -> MATCH_P
        QrSignal.MISMATCH -> MISMATCH_P
        QrSignal.UNREADABLE_TIMEOUT -> UNREADABLE_P
        QrSignal.CAMERA_BLOCKED -> CAMERA_BLOCKED_P
        QrSignal.STOPPED -> STOPPED_P
    }

    /** Non-colour contract, mirroring C02's `RiskToneCopy.isSelfDescribing`. */
    fun isSelfDescribing(signal: QrSignal): Boolean {
        val p = of(signal)
        return p.label.hinglish.isNotBlank() && p.label.english.isNotBlank() &&
            p.detail.hinglish.isNotBlank() && p.detail.english.isNotBlank() &&
            p.glyph.isNotBlank() && p.iconKey.isNotBlank() &&
            p.contentDescription.hinglish.isNotBlank() && p.contentDescription.english.isNotBlank()
    }
}

/** Copy for the guidance panel. */
object QrGuidanceCopy {
    fun of(guidance: QrGuidance): ToneCopy = when (guidance) {
        QrGuidance.NONE -> ToneCopy("", "")
        QrGuidance.REGISTER_PAYEE -> QrStrings.setupNeedsPayee
        QrGuidance.GRANT_CAMERA_PERMISSION -> ToneCopy(
            QrStrings.cameraDenied.hinglish + " " + QrStrings.cameraDeniedHelp.hinglish,
            QrStrings.cameraDenied.english + " " + QrStrings.cameraDeniedHelp.english,
            QrStrings.cameraDenied.hindi + " " + QrStrings.cameraDeniedHelp.hindi
        )
        QrGuidance.NO_CAMERA -> ToneCopy(
            QrStrings.cameraNone.hinglish + " " + QrStrings.cameraNoneHelp.hinglish,
            QrStrings.cameraNone.english + " " + QrStrings.cameraNoneHelp.english,
            QrStrings.cameraNone.hindi + " " + QrStrings.cameraNoneHelp.hindi
        )
        QrGuidance.OCCLUDED -> QrSignalCopy.of(QrSignal.UNREADABLE_TIMEOUT).detail
        QrGuidance.MISMATCH_ACTION -> ToneCopy(
            QrSignalCopy.of(QrSignal.MISMATCH).detail.hinglish + " " + QrStrings.stopPaymentNow.hinglish,
            QrSignalCopy.of(QrSignal.MISMATCH).detail.english + " " + QrStrings.stopPaymentNow.english,
            QrSignalCopy.of(QrSignal.MISMATCH).detail.hindi + " " + QrStrings.stopPaymentNow.hindi
        )
        QrGuidance.PIN_LOCKED_OUT -> QrStrings.pinLockedOut
        QrGuidance.PIN_NOT_CONFIGURED -> QrStrings.pinNotConfigured
    }
}
