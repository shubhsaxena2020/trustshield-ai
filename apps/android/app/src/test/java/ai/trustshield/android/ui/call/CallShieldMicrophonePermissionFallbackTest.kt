/*
 * P4 - Call Shield when RECORD_AUDIO was refused: the microphone-permission fallback.
 *
 * Scope: the three shapes a missing microphone permission can take on the Call Shield screen
 * (never asked, denied, permanently denied by Android) and what the readiness decision may claim -
 * and offer - in each of them. This is the fallback contract: no live session, no "listening", an
 * honest explanation that nothing was heard, and the microphone-free ladder (typed transcript /
 * staged demo script) instead.
 *
 * Phone hold: no device, no emulator, no adb, no recogniser and no microphone are involved. No test
 * here requests RECORD_AUDIO, opens a capture session or constructs an `android.*` type. Every
 * capability report below is built by the REAL pure B04 rules
 * (`SpeechCapabilityRules.evaluate`) over a `SpeechEnvironment` of hand-written platform facts, and
 * every decision is the pure `CallShieldReadiness.evaluate`. Production code is READ-ONLY for this
 * package: the copy pinned below was read out of the source, never edited to fit a test.
 *
 * Fixtures carry no speech, no audio, no transcript, no personal data, no OTP/PIN, no phone number
 * and no UPI id: capability flags and enums only.
 *
 * What is asserted:
 *  1. RECORD_AUDIO denied on a phone whose cloud recogniser is otherwise usable, with consent
 *     granted: MIC_PERMISSION_DENIED, canStartLiveSession false, and isListening false even at
 *     phase LISTENING - plus the structural fact that only the two READY_* states can ever open the
 *     microphone, so no capture can start from here. Granting the permission on the same phone does
 *     produce READY_MAY_USE_NETWORK, which proves the denial is the deciding input.
 *  2. The explanation is present in both languages: the headline says the permission was not
 *     granted, the body says nothing was heard and nothing was recorded, and every report still
 *     carries the scope + voice-clone + nothing-stored limitations, with no digit on any line.
 *  3. The fallback ladder is offered: canUseDemoScript true and the actions are exactly the
 *     allow-microphone action plus the demo action, in every microphone-failure state.
 *  4. CallShieldCopy is honest about the typed/staged fallback (verified substrings, below).
 *  5. Permanently denied is its own state with a different route (app settings), never READY_*, and
 *     it is derived from the capability report because the screen-level MicPermission cannot
 *     express "permanently".
 *  6. Determinism: the same input evaluated twice gives the same state and the same line count.
 *
 * Verified deviation from the brief, pinned here rather than papered over: `CallShieldCopy.
 * typedTranscript` uses the SAME text for both languages ("Typed transcript"), so the "English !=
 * Hinglish" rule cannot hold for that one label; `stagedDemo` does differ per language. Copy source
 * is read-only for this package, so test 4 asserts the real values and names the difference.
 */
package ai.trustshield.android.ui.call

import ai.trustshield.android.input.speech.CapabilityReason
import ai.trustshield.android.input.speech.SpeechCapabilityReport
import ai.trustshield.android.input.speech.SpeechCapabilityRules
import ai.trustshield.android.input.speech.SpeechConsentState
import ai.trustshield.android.input.speech.SpeechEnvironment
import ai.trustshield.android.input.speech.SpeechLanguage
import ai.trustshield.android.input.speech.SpeechStatus
// B04 has its own microphone enum (Unknown/Granted/Denied/PermanentlyDenied); the screen-level
// MicPermission below is this package's (UNKNOWN/GRANTED/DENIED) and is NOT aliased.
import ai.trustshield.android.input.speech.MicPermission as SpeechMic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CallShieldMicrophonePermissionFallbackTest {

    // ------------------------------------------------------------------ fixtures

    /**
     * A phone whose default recogniser works, but only over the network: API 26, no on-device
     * recogniser, no hi-IN model. `cloudMayBeUsed` is therefore true and `offlineCapable` false, so
     * the only thing standing between the user and a live session is the microphone permission.
     */
    private fun capability(mic: SpeechMic): SpeechCapabilityReport = SpeechCapabilityRules.evaluate(
        SpeechEnvironment(
            apiLevel = 26,
            recognizerServiceAvailable = true,
            onDeviceAvailable = false,
            hiInModelDownloaded = false,
            micPermission = mic,
        ),
        SpeechLanguage.HI_IN,
    )

    /** RECORD_AUDIO refused; the recogniser itself is usable. */
    private val cloudRecogniserMicDenied = capability(SpeechMic.Denied)

    /** The SAME phone once the permission is granted: the control case for test 1. */
    private val cloudRecogniserMicGranted = capability(SpeechMic.Granted)

    private val micNeverAsked = capability(SpeechMic.Unknown)
    private val micPermanentlyDenied = capability(SpeechMic.PermanentlyDenied)

    private fun evaluate(
        report: SpeechCapabilityReport,
        consent: SpeechConsentState = SpeechConsentState.Granted,
        mic: MicPermission = MicPermission.DENIED,
        phase: SessionPhase = SessionPhase.IDLE,
        demoAvailable: Boolean = true,
    ): ReadinessReport = CallShieldReadiness.evaluate(
        ReadinessInput(
            report = report,
            consent = consent,
            micPermission = mic,
            demoAvailable = demoAvailable,
            phase = phase,
        )
    )

    /** No line may carry a digit: no OTP, PIN, amount, card, phone number or UPI id can appear. */
    private fun assertNoDigits(report: ReadinessReport) {
        val digit = Regex("[0-9]")
        for (copy in report.allCopy) {
            assertFalse("Hinglish line carries a digit: ${copy.hinglish}", digit.containsMatchIn(copy.hinglish))
            assertFalse("English line carries a digit: ${copy.english}", digit.containsMatchIn(copy.english))
        }
    }

    /** The three limits every report carries, in source order, then the state's extra line. */
    private fun assertBaseLimitations(report: ReadinessReport) {
        assertEquals("base limitation count", 3, ReadinessCopy.baseLimitations.size)
        assertEquals(
            listOf(ReadinessCopy.scope, ReadinessCopy.noCloneCheck, ReadinessCopy.nothingStored),
            ReadinessCopy.baseLimitations,
        )
        assertEquals(ReadinessCopy.baseLimitations, report.limitations.take(3))
        assertTrue(report.limitations.contains(ReadinessCopy.scope))
        assertTrue(report.limitations.contains(ReadinessCopy.noCloneCheck))
        assertTrue(report.limitations.contains(ReadinessCopy.nothingStored))
    }

    // ------------------------------------------------------------------ 1

    @Test
    fun recordAudioDeniedCannotOpenTheMicrophoneEvenAtListeningPhase() {
        // "Otherwise usable cloud recogniser": the network path exists, so permission is the only
        // thing the user is missing. B04 reports it as NoMicPermission and says why.
        assertTrue(cloudRecogniserMicDenied.cloudMayBeUsed)
        assertFalse(cloudRecogniserMicDenied.offlineCapable)
        assertEquals(SpeechStatus.NoMicPermission, cloudRecogniserMicDenied.status)
        assertTrue(cloudRecogniserMicDenied.has(CapabilityReason.MicPermissionDenied))

        val idle = evaluate(cloudRecogniserMicDenied, phase = SessionPhase.IDLE)
        assertEquals(ReadinessState.MIC_PERMISSION_DENIED, idle.state)
        assertFalse(idle.canStartLiveSession)
        assertFalse(idle.state.canStartLiveSession)
        assertFalse(idle.isListening)

        // Phase alone must never fabricate a session: the same denied input at LISTENING stays
        // silent. This is the assertion that proves no capture can start from a denied state.
        val listening = evaluate(cloudRecogniserMicDenied, phase = SessionPhase.LISTENING)
        assertEquals(ReadinessState.MIC_PERMISSION_DENIED, listening.state)
        assertFalse(listening.canStartLiveSession)
        assertFalse(listening.isListening)
        assertFalse(listening.state.canStartLiveSession)

        // No capture route is even offered: the Start action never appears in the denied report.
        assertFalse(listening.actions.contains(ReadinessCopy.startSession))
        assertNotEquals(ReadinessState.READY_ON_DEVICE, listening.state)
        assertNotEquals(ReadinessState.READY_MAY_USE_NETWORK, listening.state)

        // Structural gate: exactly two states may open the microphone, and no microphone-failure
        // state is one of them.
        assertEquals(
            listOf(ReadinessState.READY_ON_DEVICE, ReadinessState.READY_MAY_USE_NETWORK),
            ReadinessState.entries.filter { it.canStartLiveSession }.toList(),
        )
        for (state in listOf(
            ReadinessState.MIC_PERMISSION_NOT_ASKED,
            ReadinessState.MIC_PERMISSION_DENIED,
            ReadinessState.MIC_PERMISSION_PERMANENTLY_DENIED,
        )) {
            assertFalse("$state must not open the microphone", state.canStartLiveSession)
        }

        // Control: grant the permission on the very same phone and the live network path appears,
        // so the denial - not the capability - is what blocked the session.
        val granted = evaluate(cloudRecogniserMicGranted, mic = MicPermission.GRANTED)
        assertEquals(ReadinessState.READY_MAY_USE_NETWORK, granted.state)
        assertTrue(granted.canStartLiveSession)
    }

    // ------------------------------------------------------------------ 2

    @Test
    fun deniedCopySaysPermissionWasNotGrantedAndNothingWasHeardOrRecorded() {
        val denied = evaluate(cloudRecogniserMicDenied, phase = SessionPhase.LISTENING)

        // Headline (verified verbatim from ReadinessCopy.headline(MIC_PERMISSION_DENIED)).
        assertEquals("Microphone permission was not granted", denied.headline.english)
        assertEquals("Mic ki anumati nahi mili", denied.headline.hinglish)

        // Body, English: nothing was heard, nothing was recorded, and the app cannot listen.
        assertTrue(
            "English body must say nothing was heard or recorded",
            denied.body.english.contains("nothing was heard and nothing was recorded"),
        )
        assertTrue(
            "English body must say the app cannot listen without permission",
            denied.body.english.contains("Without it the app cannot listen."),
        )
        // Body, Hinglish: the same two claims.
        assertTrue(
            "Hinglish body must say kuch suna nahi gaya aur kuch record nahi hua",
            denied.body.hinglish.contains("kuch suna nahi gaya aur kuch record nahi hua"),
        )
        assertTrue(
            "Hinglish body must say the app cannot hear without permission",
            denied.body.hinglish.contains("Bina anumati ke app sun nahi sakta"),
        )

        // The state-specific limit names the closed microphone, in both languages.
        assertTrue(denied.limitations.contains(ReadinessCopy.micClosed))
        assertTrue(denied.limitations.last().english.contains("The microphone is closed"))
        assertTrue(denied.limitations.last().hinglish.contains("Mic band hai"))

        // Scope + voice-clone + nothing-stored survive in EVERY microphone-failure report, and no
        // line anywhere carries a digit.
        for (report in listOf(
            evaluate(cloudRecogniserMicDenied),
            evaluate(micNeverAsked, mic = MicPermission.UNKNOWN),
            evaluate(micPermanentlyDenied),
        )) {
            assertBaseLimitations(report)
            assertNoDigits(report)
        }
    }

    // ------------------------------------------------------------------ 3

    @Test
    fun demoLadderIsOfferedInEveryMicrophoneFailureState() {
        // Denied: the exact two actions, in priority order - ask again, then the mic-free demo.
        val denied = evaluate(cloudRecogniserMicDenied)
        assertTrue(denied.canUseDemoScript)
        assertEquals(
            listOf(ReadinessCopy.allowMicPermission, ReadinessCopy.useDemoScript),
            denied.actions,
        )
        // The demo stays offered even if the screen reports the listening phase: it is mic-free.
        assertTrue(evaluate(cloudRecogniserMicDenied, phase = SessionPhase.LISTENING).canUseDemoScript)

        val cases = listOf(
            Triple(micNeverAsked, MicPermission.UNKNOWN, ReadinessState.MIC_PERMISSION_NOT_ASKED),
            Triple(cloudRecogniserMicDenied, MicPermission.DENIED, ReadinessState.MIC_PERMISSION_DENIED),
            Triple(micPermanentlyDenied, MicPermission.DENIED, ReadinessState.MIC_PERMISSION_PERMANENTLY_DENIED),
        )
        for ((report, mic, expected) in cases) {
            val readiness = evaluate(report, mic = mic)
            assertEquals(expected, readiness.state)
            assertTrue("$expected must still offer the demo script", readiness.canUseDemoScript)
            assertTrue("$expected actions must contain the demo action",
                readiness.actions.contains(ReadinessCopy.useDemoScript))
            assertFalse("$expected must not offer a live start",
                readiness.actions.contains(ReadinessCopy.startSession))
        }

        // Not-asked keeps the same re-ask route as denied.
        assertEquals(
            listOf(ReadinessCopy.allowMicPermission, ReadinessCopy.useDemoScript),
            evaluate(micNeverAsked, mic = MicPermission.UNKNOWN).actions,
        )
        // Permanently denied swops the re-ask for the settings route, and still keeps the demo.
        assertTrue(
            evaluate(micPermanentlyDenied).actions.contains(ReadinessCopy.useDemoScript),
        )
    }

    // ------------------------------------------------------------------ 4

    @Test
    fun callShieldCopyIsHonestAboutTheTypedAndStagedFallback() {
        // The screen's own denial copy (verified from CallShieldCopy.permissionDeniedBody).
        assertTrue(
            CallShieldCopy.permissionDeniedBody.english.contains("Without permission the app cannot listen."),
        )
        assertTrue(
            CallShieldCopy.permissionDeniedBody.english.contains("your phone Settings"),
        )
        assertTrue(
            CallShieldCopy.permissionDeniedBody.hinglish.contains("Bina anumati ke app sun nahi sakta"),
        )

        // The "unable to verify" reason that a revoked permission produces names the typed fallback.
        val micUnavailable = CallShieldCopy.unableToVerifyBody(UnableToVerifyReason.MIC_UNAVAILABLE)
        assertTrue(micUnavailable.english.contains("typed transcript"))
        assertTrue(micUnavailable.hinglish.contains("typed transcript use karein"))

        // Both fallback labels exist and are non-blank in each language.
        assertTrue(CallShieldCopy.typedTranscript.hinglish.isNotBlank())
        assertTrue(CallShieldCopy.typedTranscript.english.isNotBlank())
        assertTrue(CallShieldCopy.stagedDemo.hinglish.isNotBlank())
        assertTrue(CallShieldCopy.stagedDemo.english.isNotBlank())

        // The staged demo says, per language, that it is not the live microphone.
        assertTrue(CallShieldCopy.stagedDemo.english.contains("not live mic"))
        assertTrue(CallShieldCopy.stagedDemo.hinglish.contains("live mic nahi"))
        assertNotEquals(CallShieldCopy.stagedDemo.hinglish, CallShieldCopy.stagedDemo.english)

        // Verified deviation (see the file header): typedTranscript is ONE label used for both
        // languages in the copy source, so English == Hinglish there by design. Pinned as it is.
        assertEquals("Typed transcript", CallShieldCopy.typedTranscript.english)
        assertEquals(
            CallShieldCopy.typedTranscript.hinglish,
            CallShieldCopy.typedTranscript.english,
        )

        // The grant action is distinct from the settings action, so the two routes cannot be
        // confused on screen.
        assertNotEquals(
            CallShieldCopy.grantPermission.english,
            ReadinessCopy.openAppSettings.english,
        )
    }

    // ------------------------------------------------------------------ 5

    @Test
    fun permanentlyDeniedIsItsOwnStateWithTheSettingsRouteAndIsNeverReady() {
        assertTrue(micPermanentlyDenied.has(CapabilityReason.MicPermissionPermanentlyDenied))

        // The screen-level MicPermission has no "permanently" value, so the distinction has to come
        // from the capability report. Passing the production DENIED value must still route to the
        // permanent state - this is the real wiring path, not a wished-for one.
        val permanent = evaluate(micPermanentlyDenied, mic = MicPermission.DENIED)
        assertEquals(ReadinessState.MIC_PERMISSION_PERMANENTLY_DENIED, permanent.state)
        assertFalse(permanent.canStartLiveSession)
        assertFalse(permanent.isListening)
        assertTrue(permanent.actions.contains(ReadinessCopy.openAppSettings))
        assertFalse(permanent.actions.contains(ReadinessCopy.allowMicPermission))
        assertFalse(permanent.actions.contains(ReadinessCopy.startSession))
        // The route is spelled out in the body copy: phone Settings > Apps > TrustShield > ...
        assertTrue(
            permanent.body.english.contains(
                "Settings > Apps > TrustShield > Permissions > Microphone > Allow",
            ),
        )
        assertTrue(permanent.headline.english.contains("Settings"))

        // A plain denial with the SAME microphone value is a different state with a different route.
        val plain = evaluate(cloudRecogniserMicDenied, mic = MicPermission.DENIED)
        assertEquals(ReadinessState.MIC_PERMISSION_DENIED, plain.state)
        assertNotEquals(permanent.state, plain.state)
        assertNotEquals(permanent.actions, plain.actions)
        assertNotEquals(permanent.body.english, plain.body.english)
        assertTrue(plain.actions.contains(ReadinessCopy.allowMicPermission))
        assertFalse(plain.actions.contains(ReadinessCopy.openAppSettings))

        // Never a ready state, in any session phase, and never listening.
        for (phase in SessionPhase.entries) {
            val atPhase = evaluate(micPermanentlyDenied, phase = phase)
            assertFalse("$phase must not be a READY state", atPhase.state.name.startsWith("READY"))
            assertFalse(atPhase.canStartLiveSession)
            assertFalse(atPhase.isListening)
        }
    }

    // ------------------------------------------------------------------ 6

    @Test
    fun sameInputEvaluatesToTheSameStateAndTheSameLineCount() {
        val inputs = listOf(
            ReadinessInput(
                report = cloudRecogniserMicDenied,
                consent = SpeechConsentState.Granted,
                micPermission = MicPermission.DENIED,
            ),
            ReadinessInput(
                report = micNeverAsked,
                consent = SpeechConsentState.Granted,
                micPermission = MicPermission.UNKNOWN,
                phase = SessionPhase.LISTENING,
            ),
            ReadinessInput(
                report = micPermanentlyDenied,
                consent = SpeechConsentState.Granted,
                micPermission = MicPermission.DENIED,
                phase = SessionPhase.LISTENING,
            ),
        )

        for (input in inputs) {
            val first = CallShieldReadiness.evaluate(input)
            val second = CallShieldReadiness.evaluate(input)
            assertEquals(first.state, second.state)
            assertEquals(first.allCopy.size, second.allCopy.size)
            assertEquals(first.limitations.size, second.limitations.size)
            assertEquals(first.actions, second.actions)
            // The whole report is a data class over enums and frozen copy: identical input, identical
            // output - no clock, no I/O, no randomness anywhere in the decision.
            assertEquals(first, second)
        }
    }
}
