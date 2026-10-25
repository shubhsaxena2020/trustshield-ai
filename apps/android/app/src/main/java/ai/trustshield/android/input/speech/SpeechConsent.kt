/*
 * B04 - microphone consent record for Call Shield.
 *
 * The record is ONE enum. There is no transcript, no audio path, no free text and no timestamp in
 * it, so nothing a user says can ever be reconstructed from stored consent state. This is a pure
 * Kotlin state machine: no android.* import, no IO, no clock, no network.
 *
 * Transitions (deliberately narrow):
 *   NotAsked --grant--> Granted
 *   NotAsked --deny-->  Denied
 *   Granted  --withdraw--> Withdrawn      (withdrawal clears the session: canStartSession turns false
 *                                          and the caller must tear the recogniser down)
 *   Denied | Withdrawn --reask--> NotAsked (a fresh explicit ask, never a silent re-ask)
 * Everything else is a no-op, so a stale UI event cannot resurrect consent.
 */

package ai.trustshield.android.input.speech

/** The persisted consent value. Nothing else about the user is stored. */
enum class SpeechConsentState { NotAsked, Granted, Denied, Withdrawn }

/** Why a session may not start. Each maps to one short Hinglish line. */
enum class ConsentBlockReason {
    RecognizerUnavailable,
    ConsentNotAsked,
    ConsentDenied,
    ConsentWithdrawn,
    MicPermissionNotAsked,
    MicPermissionDenied,
    MicPermissionPermanentlyDenied,
}

/** Result of [ConsentGate.check]. */
sealed interface ConsentDecision {
    val canStartSession: Boolean

    data object Allow : ConsentDecision {
        override val canStartSession: Boolean = true
    }

    data class Block(val reason: ConsentBlockReason) : ConsentDecision {
        override val canStartSession: Boolean = false
    }
}

/**
 * Immutable consent record. Holds exactly one field: the state enum.
 *
 * Microphone privacy note: this object cannot hold speech, so "withdrawal clears the session" is
 * structural rather than a cleanup routine.
 */
class SpeechConsent private constructor(val state: SpeechConsentState) {

    /** True only when the user has granted. Withdrawn and Denied both block. */
    val canStartSession: Boolean
        get() = state == SpeechConsentState.Granted

    /** NotAsked (or a fresh attempt after a refusal via [reask]) becomes Granted. */
    fun grant(): SpeechConsent = when (state) {
        SpeechConsentState.NotAsked -> SpeechConsent(SpeechConsentState.Granted)
        else -> this
    }

    /** Only an unasked record can be denied; denying a granted record is a no-op, use [withdraw]. */
    fun deny(): SpeechConsent = when (state) {
        SpeechConsentState.NotAsked -> SpeechConsent(SpeechConsentState.Denied)
        else -> this
    }

    /** Granted becomes Withdrawn; the session must be stopped by the caller. */
    fun withdraw(): SpeechConsent = when (state) {
        SpeechConsentState.Granted -> SpeechConsent(SpeechConsentState.Withdrawn)
        else -> this
    }

    /** Explicit new ask after a refusal. Granted consent is not reset by this call. */
    fun reask(): SpeechConsent = when (state) {
        SpeechConsentState.Denied,
        SpeechConsentState.Withdrawn,
        -> SpeechConsent(SpeechConsentState.NotAsked)

        else -> this
    }

    /** The only value that may be written to storage. */
    fun recordedState(): SpeechConsentState = state

    override fun toString(): String = "SpeechConsent(state=$state)"

    companion object {
        /** A record for a user who has not been asked. */
        fun initial(): SpeechConsent = SpeechConsent(SpeechConsentState.NotAsked)

        /** Rehydrate a stored enum. The enum is the whole persisted record. */
        fun fromStored(stored: SpeechConsentState): SpeechConsent = SpeechConsent(stored)
    }
}

/**
 * The single place that answers "may a Call Shield session start right now?".
 *
 * Order matters: a missing recogniser is reported first (asking consent would be pointless), then
 * consent, then the microphone. A cloud path is never called private here; it is simply allowed.
 */
object ConsentGate {

    fun check(consent: SpeechConsent, report: SpeechCapabilityReport): ConsentDecision {
        if (report.status == SpeechStatus.MissingService) {
            return ConsentDecision.Block(ConsentBlockReason.RecognizerUnavailable)
        }
        when (consent.state) {
            SpeechConsentState.NotAsked -> return ConsentDecision.Block(ConsentBlockReason.ConsentNotAsked)
            SpeechConsentState.Denied -> return ConsentDecision.Block(ConsentBlockReason.ConsentDenied)
            SpeechConsentState.Withdrawn -> return ConsentDecision.Block(ConsentBlockReason.ConsentWithdrawn)
            SpeechConsentState.Granted -> Unit
        }
        if (report.status == SpeechStatus.NoMicPermission) {
            return ConsentDecision.Block(micBlockReason(report))
        }
        return ConsentDecision.Allow
    }

    private fun micBlockReason(report: SpeechCapabilityReport): ConsentBlockReason = when {
        report.has(CapabilityReason.MicPermissionPermanentlyDenied) ->
            ConsentBlockReason.MicPermissionPermanentlyDenied

        report.has(CapabilityReason.MicPermissionDenied) -> ConsentBlockReason.MicPermissionDenied
        else -> ConsentBlockReason.MicPermissionNotAsked
    }
}
