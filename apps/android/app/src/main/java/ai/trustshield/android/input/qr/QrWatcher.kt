package ai.trustshield.android.input.qr

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * B06 / issue #38 — QR tamper watcher state machine (pure Kotlin decision core).
 *
 * Product source: `docs/android/SOURCE_REPORT_senior_dev.md` §7 (QR Tamper Guard), adapted from the
 * ESP32 stand to an attended phone camera; `docs/android/BEHAVIOR_SPEC.md` ("QR and payment semantics")
 * and `docs/android/REQUIREMENTS.md` F07.
 *
 * Rules implemented here:
 *  - A decoded QR is DATA only. Nothing in this file fetches, opens or navigates to a payload, and there
 *    is no network, socket, file or clock access beyond the injected `clock`.
 *  - A **valid decoded** payee that does not match any registered payee counts one mismatch; three
 *    consecutive valid mismatches raise [QrGuardState.Tampered].
 *  - An unreadable / glare / no-decode frame (or a payload no payee can be extracted from) **never**
 *    increments the mismatch counter and **never** refreshes the last-valid-decode time. After five
 *    minutes with no valid decode the state is [QrGuardState.Occluded] — never `Tampered`.
 *  - Several registered payees (PhonePe + Paytm on one stand) all count as matches.
 *  - Re-register needs a 5 second long press **and** a passed PIN check.
 *  - Only a salted HMAC-SHA256 payee identifier is stored; the raw VPA is never retained, never placed in
 *    a state string, a message, a `toString()` or an exception message.
 *
 * No `android.*` import: this file is unit-testable on a plain JVM (see QrWatcherTest.kt).
 */

/**
 * Seam for UPI payee extraction (the `pa` value of a UPI intent).
 *
 * Dependency boundary (B02/B03 live on other branches): this package does **not** parse UPI and does not
 * import them. Integration binds this to `UpiQrParser` — `PayeeExtractor { payload -> upiQrParser.extractPa(payload) }`.
 * Returning `null` means "no payee could be extracted"; such a frame is treated like an unreadable frame.
 */
fun interface PayeeExtractor {
    fun extract(decodedPayload: String): String?
}

/** Watcher states. Mirrors report §7 SETUP / WATCH / RE-REGISTER plus the occlusion alarm. */
enum class QrGuardState {
    /** No payee registered yet (or a re-register was just accepted): capture the merchant's QR. */
    Setup,

    /** Comparing decoded payees against the registry. */
    Watching,

    /** [QrWatcherPolicy.mismatchLimit] consecutive valid mismatches. Sticky until acknowledged. */
    Tampered,

    /** No valid decode for [QrWatcherPolicy.occlusionMs] — QR covered, moved, glare or too dark. */
    Occluded,

    /** A re-register was attempted and refused (long press too short or PIN not passed). */
    ReRegisterPending,
}

/** Coarse alert level for the UI layer (C06). */
enum class QrAlertLevel { None, Watch, Tamper, Occluded }

/** Tunables. Defaults come from report §7 / BEHAVIOR_SPEC and must not be silently weakened. */
data class QrWatcherPolicy(
    val mismatchLimit: Int = 3,
    val occlusionMs: Long = 300_000L,
    val reRegisterLongPressMs: Long = 5_000L,
) {
    init {
        require(mismatchLimit >= 1) { "mismatchLimit must be >= 1" }
        require(occlusionMs > 0L) { "occlusionMs must be > 0" }
        require(reRegisterLongPressMs >= 0L) { "reRegisterLongPressMs must be >= 0" }
    }
}

/**
 * Masking helper. `rahul.sharma@okaxis` -> `ra***@okaxis`. The local part is never shown beyond two
 * characters and a local part shorter than three characters is masked completely.
 */
object PayeeMasking {
    const val HIDDEN = "***"
    private const val STARS = "***"

    fun mask(vpa: String): String {
        val trimmed = vpa.trim()
        val at = trimmed.indexOf('@')
        if (at <= 0 || at == trimmed.lastIndex) return HIDDEN
        val local = trimmed.substring(0, at)
        val domain = trimmed.substring(at + 1)
        if (local.any { it.isWhitespace() } || domain.any { it.isWhitespace() }) return HIDDEN
        val keep = if (local.length >= 3) 2 else 0
        return local.take(keep) + STARS + "@" + domain
    }
}

/** A registered payee as the rest of the app is allowed to see it: salted id + masked display string. */
data class MaskedPayee(val identifier: String, val masked: String)

/**
 * Registered expected payees. Only salted identifiers are stored; there is no accessor for the raw VPA.
 */
interface PayeeRegistry {
    /** Registers [vpa] (hashed with the supplied salt). Idempotent for the same VPA. */
    fun register(vpa: String)

    /** Registered payees as salted id + masked string. Never the raw VPA. */
    fun registered(): List<MaskedPayee>

    /** True when [vpa] hashes to a registered identifier. */
    fun matches(vpa: String): Boolean

    /** Forgets every registered payee. */
    fun clear()
}

/**
 * In-memory salted HMAC-SHA256 registry.
 *
 * **Integration item:** this class does not persist. Codex must supply a durable store (C04's preferences
 * file is not ready) and a keystore-backed salt; a human-chosen constant salt is not a privacy guarantee
 * for a low-entropy UPI ID (`docs/android/ARCHITECTURE.md` boundaries).
 */
class SaltedPayeeRegistry(private val salt: ByteArray) : PayeeRegistry {

    init {
        require(salt.size >= 16) { "salt must be at least 16 bytes (supply a keystore-backed salt)" }
    }

    private val entries = LinkedHashMap<String, String>()

    override fun register(vpa: String) {
        val identifier = identifierOf(vpa)
        entries.getOrPut(identifier) { PayeeMasking.mask(vpa) }
    }

    override fun registered(): List<MaskedPayee> =
        entries.entries.map { MaskedPayee(identifier = it.key, masked = it.value) }

    override fun matches(vpa: String): Boolean = entries.containsKey(identifierOf(vpa))

    override fun clear() = entries.clear()

    private fun identifierOf(vpa: String): String {
        val normalized = vpa.trim()
        // Never echo the value in the message: exception text must not carry a raw VPA.
        require(normalized.contains('@') && normalized.substringAfter('@').isNotEmpty()) {
            "payee identifier must be a UPI id of the form local@handle"
        }
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(SecretKeySpec(salt, HMAC_ALGORITHM))
        return toHex(mac.doFinal(normalized.toByteArray(Charsets.UTF_8)))
    }

    private fun toHex(bytes: ByteArray): String {
        val out = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            if (v < 0x10) out.append('0')
            out.append(v.toString(16))
        }
        return out.toString()
    }

    private companion object {
        const val HMAC_ALGORITHM = "HmacSHA256"
    }
}

/** Immutable view of the machine. Contains masked data only — safe to render or log. */
data class QrWatcherStatus(
    val state: QrGuardState,
    val alertLevel: QrAlertLevel,
    val consecutiveMismatches: Int,
    val registeredCount: Int,
    val lastValidDecodeAt: Long?,
    val lastMismatchMasked: String?,
    val message: String,
)

/** Why a re-register attempt was refused. */
enum class ReRegisterRefusal {
    /** The button was not held for [QrWatcherPolicy.reRegisterLongPressMs]. */
    LONG_PRESS_INCOMPLETE,

    /** The local PIN check did not pass. */
    PIN_REQUIRED,
}

/** Result of [QrWatcher.requestReRegister]. */
sealed interface ReRegisterResult {
    /** Long press and PIN both satisfied; the machine returns to [QrGuardState.Setup]. */
    data object Accepted : ReRegisterResult

    /** Refused; the machine stays in [QrGuardState.ReRegisterPending]. */
    data class Refused(val reason: ReRegisterRefusal) : ReRegisterResult
}

/**
 * Hinglish UI copy. Constants only — never interpolated with a raw VPA.
 * (UI_SPEC owns final wording; this is the deterministic string source for the QR state machine.)
 */
object QrGuardCopy {
    const val SETUP = "Pehle apne dukaan ka QR register karein."
    const val WATCHING = "Aapke QR pe nazar hai."
    const val WATCH_MISMATCH = "QR ka UPI ID alag lag raha hai. Camera QR pe tikaye rakhein."
    const val TAMPERED = "Aapke QR ka UPI ID badal gaya. Payment mat karein — dukaandaar se poochhein."
    const val OCCLUDED = "QR dikh nahi raha (dhak gaya, hil gaya ya roshni kam hai). Camera seedha QR pe rakhein."
    const val RE_REGISTER = "Naya QR register karne ke liye 5 second dabayein, phir PIN daalein."
    const val SETTLEMENT_REMINDER =
        "Pakka confirmation sirf aapke bank SMS ya UPI app ki history mein milega."

    /** "Expected ra***@okaxis, mila 98***@ybl" — masked values only. */
    fun tamperDetail(expectedMasked: List<String>, seenMasked: String?): String {
        val expected = if (expectedMasked.isEmpty()) {
            "(koi payee register nahi)"
        } else {
            expectedMasked.joinToString(", ")
        }
        return "Expected $expected, mila ${seenMasked ?: PayeeMasking.HIDDEN}"
    }
}

/**
 * QR watcher state machine (B06).
 *
 * Feed it camera results with [observeDecoded] / [observeUnreadable]; read [status] or the returned
 * [QrWatcherStatus]. All time comes from the injected [clock] (milliseconds), so tests are deterministic
 * and behaviour is identical with a frozen clock or no network.
 *
 * @param extractor bound to `UpiQrParser` at integration (see [PayeeExtractor]).
 * @param registry salted identifier store (see [SaltedPayeeRegistry]).
 * @param clock injected time source, milliseconds; monotonic per watcher lifetime.
 */
class QrWatcher(
    private val extractor: PayeeExtractor,
    private val registry: PayeeRegistry,
    private val clock: () -> Long,
    private val policy: QrWatcherPolicy = QrWatcherPolicy(),
) {

    private var state: QrGuardState =
        if (registry.registered().isEmpty()) QrGuardState.Setup else QrGuardState.Watching
    private var registrationAuthorized: Boolean = registry.registered().isEmpty()

    private var consecutiveMismatches: Int = 0
    private var lastValidDecodeAt: Long? = null
    private var lastMismatchMasked: String? = null
    private var monitoringSince: Long = clock()

    val currentState: QrGuardState get() = state

    /** Current view of the machine, evaluated against the clock (occlusion included). */
    fun status(): QrWatcherStatus = refresh()

    /** Re-evaluates the occlusion timer against the clock without consuming a frame. */
    fun refresh(): QrWatcherStatus {
        val now = clock()
        evaluateOcclusion(now)
        return snapshot(now)
    }

    /**
     * A frame that decoded to [decodedPayload]. The payload is passed to [PayeeExtractor] and then dropped;
     * it is never stored, logged, opened or fetched.
     */
    fun observeDecoded(decodedPayload: String): QrWatcherStatus {
        val now = clock()
        val payee = extractor.extract(decodedPayload)

        // No payee in the payload: unreadable for our purposes. Never a mismatch, never a decode time.
        if (payee == null) {
            evaluateOcclusion(now)
            return snapshot(now)
        }

        lastValidDecodeAt = now

        // A raised tamper alert is sticky: covering or restoring the QR must not silently clear it.
        if (state == QrGuardState.Tampered) return snapshot(now)

        if (registry.registered().isEmpty()) {
            consecutiveMismatches = 0
            state = QrGuardState.Setup
            return snapshot(now)
        }

        if (registry.matches(payee)) {
            consecutiveMismatches = 0
            lastMismatchMasked = null
            if (state != QrGuardState.ReRegisterPending) state = QrGuardState.Watching
            return snapshot(now)
        }

        consecutiveMismatches += 1
        lastMismatchMasked = PayeeMasking.mask(payee)
        state = if (consecutiveMismatches >= policy.mismatchLimit) {
            QrGuardState.Tampered
        } else {
            QrGuardState.Watching
        }
        return snapshot(now)
    }

    /**
     * A frame that did not decode (glare, blur, darkness, QR covered or half-peeled). Pauses the mismatch
     * counter, never touches the last-valid-decode time, and advances the occlusion timer.
     */
    fun observeUnreadable(): QrWatcherStatus {
        val now = clock()
        evaluateOcclusion(now)
        return snapshot(now)
    }

    /**
     * Re-register gate: 5 second long press **and** a passed PIN. A refusal leaves the machine in
     * [QrGuardState.ReRegisterPending] so the UI keeps the flow open instead of silently watching.
     * An acceptance returns to [QrGuardState.Setup]; the next [registerPayee] installs the new payee.
     */
    fun requestReRegister(longPressMs: Long, pinVerified: Boolean): ReRegisterResult {
        val longPressOk = longPressMs >= policy.reRegisterLongPressMs
        if (!longPressOk) {
            state = QrGuardState.ReRegisterPending
            return ReRegisterResult.Refused(ReRegisterRefusal.LONG_PRESS_INCOMPLETE)
        }
        if (!pinVerified) {
            state = QrGuardState.ReRegisterPending
            return ReRegisterResult.Refused(ReRegisterRefusal.PIN_REQUIRED)
        }
        val now = clock()
        registry.clear()
        registrationAuthorized = true
        state = QrGuardState.Setup
        consecutiveMismatches = 0
        lastMismatchMasked = null
        lastValidDecodeAt = null
        monitoringSince = now
        return ReRegisterResult.Accepted
    }

    /** Registers [vpa] as an expected payee (hashed by the registry) and resumes watching. */
    fun registerPayee(vpa: String): QrWatcherStatus {
        check(registrationAuthorized) { "payee registration requires setup or approved re-register" }
        val now = clock()
        registry.register(vpa)
        registrationAuthorized = false
        consecutiveMismatches = 0
        lastMismatchMasked = null
        lastValidDecodeAt = now
        monitoringSince = now
        state = QrGuardState.Watching
        return snapshot(now)
    }

    /** Operator dismissed a tamper alert after checking the QR physically. */
    fun acknowledgeTamper(): QrWatcherStatus {
        val now = clock()
        if (state == QrGuardState.Tampered) {
            state = QrGuardState.Watching
            consecutiveMismatches = 0
            lastMismatchMasked = null
            lastValidDecodeAt = now
            monitoringSince = now
        }
        return snapshot(now)
    }

    private fun evaluateOcclusion(now: Long) {
        if (state != QrGuardState.Watching) return
        val reference = lastValidDecodeAt ?: monitoringSince
        if (now - reference >= policy.occlusionMs) state = QrGuardState.Occluded
    }

    private fun alertLevel(): QrAlertLevel = when (state) {
        QrGuardState.Setup, QrGuardState.ReRegisterPending -> QrAlertLevel.None
        QrGuardState.Watching -> if (consecutiveMismatches > 0) QrAlertLevel.Watch else QrAlertLevel.None
        QrGuardState.Tampered -> QrAlertLevel.Tamper
        QrGuardState.Occluded -> QrAlertLevel.Occluded
    }

    private fun message(): String = when (state) {
        QrGuardState.Setup -> QrGuardCopy.SETUP
        QrGuardState.Watching ->
            if (consecutiveMismatches > 0) QrGuardCopy.WATCH_MISMATCH else QrGuardCopy.WATCHING
        QrGuardState.ReRegisterPending -> QrGuardCopy.RE_REGISTER
        QrGuardState.Occluded -> QrGuardCopy.OCCLUDED
        QrGuardState.Tampered -> QrGuardCopy.TAMPERED
    }

    private fun snapshot(now: Long): QrWatcherStatus {
        val registered = registry.registered()
        val detail = if (state == QrGuardState.Tampered) {
            " " + QrGuardCopy.tamperDetail(registered.map { it.masked }, lastMismatchMasked)
        } else {
            ""
        }
        return QrWatcherStatus(
            state = state,
            alertLevel = alertLevel(),
            consecutiveMismatches = consecutiveMismatches,
            registeredCount = registered.size,
            lastValidDecodeAt = lastValidDecodeAt,
            lastMismatchMasked = lastMismatchMasked,
            message = message() + detail,
        )
    }
}
