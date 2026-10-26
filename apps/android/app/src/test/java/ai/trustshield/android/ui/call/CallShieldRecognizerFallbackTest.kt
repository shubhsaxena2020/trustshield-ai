/*
 * P4 - Call Shield when the phone cannot do the live Hindi speech the screen wants.
 *
 * Scope: the three ways the live path disappears (no recognition service at all, the hi-IN on-device
 * model missing, hi-IN unsupported by the installed recogniser) and what the readiness decision is
 * allowed to claim in each of them. This is the fallback contract: refuse the live session honestly
 * and offer the microphone-free demo script instead.
 *
 * Phone hold: no device, no emulator, no recogniser and no microphone are involved. Every capability
 * report below is built by the REAL pure rules (`SpeechCapabilityRules.evaluate`) over a
 * `SpeechEnvironment` of hand-written platform facts, and every decision is the pure
 * `CallShieldReadiness.evaluate`. Nothing here constructs an `android.*` type or a `SpeechRecognizer`.
 *
 * Fixtures carry no speech, no audio, no transcript, no personal data, no OTP/PIN, no phone number
 * and no UPI id: capability flags and enums only. No copy is quoted from a real call.
 *
 * What is asserted:
 *  1. MissingService: RECOGNIZER_UNAVAILABLE, no live start and no "listening" in ANY session phase,
 *     the body names the missing service and says the demo script does not use the live microphone,
 *     and the offered actions are the speech-settings route plus the demo script.
 *  2. OnDeviceUnavailable + HiInModelMissing: ON_DEVICE_MODEL_MISSING, never READY_ON_DEVICE, the
 *     "audio may leave the device" limitation is present, and no English line claims on-device,
 *     offline or private processing.
 *  3. CloudOnly + HiInLanguageUnsupported: the production decision keeps the network route
 *     (READY_MAY_USE_NETWORK, asserted as produced, not as wished) while the honesty surface says the
 *     hi-IN support is unconfirmed and no English line claims on-device/offline/private processing.
 *  4. No live listening is ever claimed without a real path, even at phase LISTENING.
 *  5. The decision is deterministic: two identical evaluations give the same state, actions and
 *     limitation count.
 *
 * Known behaviour recorded here (not a bug, and not fixed by this file - production is read-only for
 * this package): a report that is OnDeviceUnavailable with NEITHER an offline nor a cloud flag has no
 * live path at all, so it lands on RECOGNIZER_UNAVAILABLE (test 4b). The real B04 rules never emit
 * that shape (OnDeviceUnavailable always sets cloudMayBeUsed = true), so 4b is a defensive fixture.
 */
package ai.trustshield.android.ui.call

import ai.trustshield.android.input.speech.CapabilityReason
import ai.trustshield.android.input.speech.SpeechCapabilityReport
import ai.trustshield.android.input.speech.SpeechCapabilityRules
import ai.trustshield.android.input.speech.SpeechConsentState
import ai.trustshield.android.input.speech.SpeechCopy
import ai.trustshield.android.input.speech.SpeechEnvironment
import ai.trustshield.android.input.speech.SpeechLabels
import ai.trustshield.android.input.speech.SpeechLanguage
import ai.trustshield.android.input.speech.SpeechStatus
import ai.trustshield.android.input.speech.MicPermission as SpeechMic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CallShieldRecognizerFallbackTest {

    // ------------------------------------------------------------------ fixtures

    /**
     * A capability report produced by the real B04 rules, so a test cannot invent a status the
     * product never emits. Defaults describe an Android 13 phone with a working default recogniser
     * and the hi-IN on-device model installed.
     */
    private fun capabilityReport(
        apiLevel: Int = 33,
        service: Boolean = true,
        onDevice: Boolean = true,
        hiInModel: Boolean = true,
        hiInSupported: Boolean = true,
        mic: SpeechMic = SpeechMic.Granted,
    ): SpeechCapabilityReport = SpeechCapabilityRules.evaluate(
        SpeechEnvironment(
            apiLevel = apiLevel,
            recognizerServiceAvailable = service,
            onDeviceAvailable = onDevice,
            hiInModelDownloaded = hiInModel,
            micPermission = mic,
            hiInSupported = hiInSupported,
        ),
        SpeechLanguage.HI_IN,
    )

    /** No recognition service installed or enabled on the phone. */
    private val missingServiceReport = capabilityReport(service = false)

    /** On-device recogniser exists, but the hi-IN model is not installed, so live would go to the net. */
    private val hindiModelMissingReport = capabilityReport(hiInModel = false)

    /** The installed recogniser does not offer hi-IN at all; the network path is an hi-IN-unknown one. */
    private val hindiUnsupportedReport = capabilityReport(hiInSupported = false)

    private fun readiness(
        report: SpeechCapabilityReport,
        consent: SpeechConsentState = SpeechConsentState.Granted,
        mic: MicPermission = MicPermission.GRANTED,
        phase: SessionPhase = SessionPhase.IDLE,
        demoAvailable: Boolean = true,
    ): ReadinessReport = CallShieldReadiness.evaluate(
        ReadinessInput(
            report = report,
            consent = consent,
            micPermission = mic,
            phase = phase,
            demoAvailable = demoAvailable,
        ),
    )

    /** Every English line of the report, in display order. */
    private fun ReadinessReport.englishLines(): List<String> = allCopy.map { it.english }

    private fun ReadinessReport.englishJoined(): String = englishLines().joinToString(" ")

    /**
     * The words that would upgrade a network-capable report into a processing promise the app cannot
     * keep. Checked case-insensitively on every English line (headline, body, actions, limitations).
     */
    private fun assertNoProcessingClaim(report: ReadinessReport, where: String) {
        for (line in report.allCopy) {
            val english = line.english.lowercase()
            for (word in FORBIDDEN_PROCESSING_WORDS) {
                assertFalse(
                    "$where: English copy claims '$word' but the audio path may leave the device: " +
                        "\"${line.english}\"",
                    english.contains(word),
                )
            }
        }
    }

    // ------------------------------------------------------------------ 1

    /**
     * No recognition service: everything else (consent, microphone) may be perfect, and the app still
     * must not open a session or claim to be listening. The user is left with the speech-settings
     * route and the microphone-free demo.
     */
    @Test
    fun missingRecognizerServiceRefusesLiveAndOffersSpeechSettingsPlusDemo() {
        // Guard the fixture itself: this is the real shape B04 emits for "no service".
        assertEquals(SpeechStatus.MissingService, missingServiceReport.status)
        assertTrue(missingServiceReport.has(CapabilityReason.NoRecognizerService))
        assertFalse(missingServiceReport.offlineCapable)
        assertFalse(missingServiceReport.cloudMayBeUsed)
        assertFalse(missingServiceReport.sessionPossible)

        val report = readiness(missingServiceReport)

        assertEquals(ReadinessState.RECOGNIZER_UNAVAILABLE, report.state)
        assertFalse(report.canStartLiveSession)
        assertFalse(report.isListening)
        // No live path means no processing claim of any kind.
        assertEquals(SpeechPrivacyMode.UNKNOWN, report.privacyMode)
        assertEquals(RecognitionDisclosure.UNAVAILABLE, report.disclosure)

        // The explanation names the missing service and separates the demo from live listening.
        val body = report.body.english.lowercase()
        assertTrue(
            "body must say no speech recognition service was found on this Android phone: " +
                report.body.english,
            body.contains("no speech recognition service was found on this android phone"),
        )
        assertTrue(
            "body must name the demo script mode as the fallback: ${report.body.english}",
            body.contains("demo script mode"),
        )
        assertTrue(
            "body must say the demo does not use the live microphone: ${report.body.english}",
            body.contains("does not use the live microphone"),
        )

        // The two offered routes: speech settings first, then the demo script. No live start.
        assertEquals(2, report.actions.size)
        assertTrue(
            "first action must be the speech-settings route: ${report.actions}",
            report.actions[0].english.contains("speech settings"),
        )
        assertTrue(
            "second action must be the demo script: ${report.actions}",
            report.actions[1].english.contains("demo script"),
        )
        assertTrue(
            "the demo script must not be blocked by a missing recogniser",
            report.canUseDemoScript,
        )

        // isListening stays false in every phase, including a phase that says "listening".
        for (phase in SessionPhase.entries) {
            val inPhase = readiness(missingServiceReport, phase = phase)
            assertFalse("$phase: nothing can be listening without a recogniser", inPhase.isListening)
            assertFalse("$phase: no live start without a recogniser", inPhase.canStartLiveSession)
            assertFalse("$phase: the state itself must refuse the live path", inPhase.state.canStartLiveSession)
        }
        assertEquals(
            ReadinessState.RECOGNIZER_UNAVAILABLE,
            readiness(missingServiceReport, phase = SessionPhase.IDLE).state,
        )
        assertEquals(
            ReadinessState.RECOGNIZER_UNAVAILABLE,
            readiness(missingServiceReport, phase = SessionPhase.LISTENING).state,
        )
        // Even at LISTENING the demo stays offered: the fallback is never taken away.
        assertTrue(readiness(missingServiceReport, phase = SessionPhase.LISTENING).canUseDemoScript)
    }

    // ------------------------------------------------------------------ 2

    /**
     * The on-device route exists but its Hindi model is gone, so a live session would silently take
     * the network route. The app refuses that quietly: ON_DEVICE_MODEL_MISSING, the disclosure line
     * says audio may leave the device, and nothing claims on-device/offline/private processing.
     */
    @Test
    fun missingHindiOnDeviceModelIsNeverReadyOnDeviceAndDisclosesTheNetworkPath() {
        assertEquals(SpeechStatus.OnDeviceUnavailable, hindiModelMissingReport.status)
        assertTrue(hindiModelMissingReport.has(CapabilityReason.HiInModelMissing))
        assertTrue(hindiModelMissingReport.cloudMayBeUsed)
        assertFalse(hindiModelMissingReport.offlineCapable)

        val report = readiness(hindiModelMissingReport)

        assertEquals(ReadinessState.ON_DEVICE_MODEL_MISSING, report.state)
        assertNotEquals(ReadinessState.READY_ON_DEVICE, report.state)
        assertFalse(report.canStartLiveSession)
        assertFalse(report.isListening)
        assertTrue(report.canUseDemoScript)
        assertEquals(SpeechPrivacyMode.MAY_LEAVE_DEVICE, report.privacyMode)
        assertEquals(RecognitionDisclosure.MAY_USE_NETWORK, report.disclosure)

        // The limitation the user must see: audio may leave the device.
        assertTrue(
            "report must carry the 'audio may leave the device' limitation: ${report.limitations}",
            report.limitations.any { it.english.contains("audio may leave the device") },
        )

        // No processing claim anywhere in the English copy.
        assertNoProcessingClaim(report, "ON_DEVICE_MODEL_MISSING")
        assertFalse(report.englishJoined().lowercase().contains("on-device"))
        assertFalse(report.englishJoined().lowercase().contains("offline"))
        assertFalse(report.englishJoined().lowercase().contains("private"))

        // Still no live start at LISTENING, and the demo stays offered as the fallback.
        val listening = readiness(hindiModelMissingReport, phase = SessionPhase.LISTENING)
        assertFalse(listening.isListening)
        assertFalse(listening.canStartLiveSession)
        assertTrue(listening.canUseDemoScript)
        assertEquals(2, report.actions.size)
        assertTrue(
            "the speech-settings route must be offered: ${report.actions}",
            report.actions[0].english.contains("speech settings"),
        )
        assertTrue(
            "the demo script must be offered: ${report.actions}",
            report.actions[1].english.contains("demo script"),
        )
    }

    // ------------------------------------------------------------------ 3

    /**
     * The installed recogniser does not support hi-IN. Production keeps the network route, so the
     * state is READY_MAY_USE_NETWORK - asserted exactly as the code produces it - and the honesty
     * surface carries the "hi-IN support is not confirmed" label instead of an on-device claim.
     */
    @Test
    fun unsupportedHiInLocaleKeepsOnlyTheNetworkRouteAndSaysSupportIsNotConfirmed() {
        assertEquals(SpeechStatus.CloudOnly, hindiUnsupportedReport.status)
        assertTrue(hindiUnsupportedReport.has(CapabilityReason.HiInLanguageUnsupported))
        assertTrue(hindiUnsupportedReport.cloudMayBeUsed)
        assertFalse(hindiUnsupportedReport.offlineCapable)

        val report = readiness(hindiUnsupportedReport)

        // Verified against CallShieldReadiness.readinessState/livePathFor: a cloud flag is still a
        // live path, so the decision is READY_MAY_USE_NETWORK, never READY_ON_DEVICE.
        assertEquals(ReadinessState.READY_MAY_USE_NETWORK, report.state)
        assertNotEquals(ReadinessState.READY_ON_DEVICE, report.state)
        assertEquals(SpeechPrivacyMode.MAY_LEAVE_DEVICE, report.privacyMode)
        assertEquals(RecognitionDisclosure.MAY_USE_NETWORK, report.disclosure)
        // READY_MAY_USE_NETWORK is one of the two states that may open the microphone.
        assertTrue(report.canStartLiveSession)
        assertFalse("IDLE is not listening", report.isListening)

        // The user-facing capability line says hi-IN support is unconfirmed, not that Hindi works.
        assertEquals(SpeechCopy.languageNotConfirmed, SpeechLabels.statusFor(hindiUnsupportedReport))

        // No English line of the report turns the network route into an on-device/offline/private claim.
        assertNoProcessingClaim(report, "CloudOnly/hi-IN unsupported")
        assertFalse(report.englishLines().any { it.lowercase().contains("on-device") })
        assertFalse(report.englishLines().any { it.lowercase().contains("offline") })
        assertFalse(report.englishLines().any { it.lowercase().contains("private") })
        assertFalse(
            "state READY_MAY_USE_NETWORK must not describe the recogniser as inside the phone: " +
                report.headline.english,
            report.headline.english.lowercase().contains("inside the phone"),
        )
        // The one honest thing it must say: audio may leave the device.
        assertTrue(
            "report must carry the 'audio may leave the device' limitation: ${report.limitations}",
            report.limitations.any { it.english.contains("audio may leave the device") },
        )
    }

    // ------------------------------------------------------------------ 4

    /**
     * The cross-cutting rule: without a real route, consent, a granted microphone and a LISTENING
     * phase are never enough to say "listening".
     */
    @Test
    fun noLiveListeningIsClaimedWithoutARealPath() {
        // (a) No recogniser service at all, everything else perfect.
        val noService = readiness(
            missingServiceReport,
            consent = SpeechConsentState.Granted,
            mic = MicPermission.GRANTED,
            phase = SessionPhase.LISTENING,
        )
        assertEquals(ReadinessState.RECOGNIZER_UNAVAILABLE, noService.state)
        assertFalse(noService.canStartLiveSession)
        assertFalse(noService.isListening)

        // (b) OnDeviceUnavailable with neither an offline nor a cloud flag: no live path remains.
        // Defensive fixture - the real rules never emit this shape, and this proves the consequence
        // is safe (no live claim) even if a future probe did.
        val noRoute = SpeechCapabilityReport(
            status = SpeechStatus.OnDeviceUnavailable,
            languageTag = SpeechLanguage.HI_IN,
            offlineCapable = false,
            cloudMayBeUsed = false,
            degraded = true,
            reasons = listOf(CapabilityReason.HiInModelMissing),
        )
        assertFalse(noRoute.sessionPossible)
        val noRouteReport = readiness(
            noRoute,
            consent = SpeechConsentState.Granted,
            mic = MicPermission.GRANTED,
            phase = SessionPhase.LISTENING,
        )
        assertEquals(ReadinessState.RECOGNIZER_UNAVAILABLE, noRouteReport.state)
        assertFalse(noRouteReport.canStartLiveSession)
        assertFalse(noRouteReport.isListening)

        // (c) A network route exists but the user has not allowed it yet.
        val notAsked = readiness(
            hindiUnsupportedReport,
            consent = SpeechConsentState.NotAsked,
            mic = MicPermission.GRANTED,
            phase = SessionPhase.LISTENING,
        )
        assertEquals(ReadinessState.NEEDS_CONSENT, notAsked.state)
        assertFalse(notAsked.canStartLiveSession)
        assertFalse(notAsked.isListening)
        assertTrue("the demo script is still offered before consent", notAsked.canUseDemoScript)
    }

    // ------------------------------------------------------------------ 5

    /** Same facts in, same report out: no clock, no state, no I/O behind the decision. */
    @Test
    fun identicalEvaluationsProduceIdenticalReports() {
        val first = readiness(hindiModelMissingReport, phase = SessionPhase.IDLE)
        val second = readiness(hindiModelMissingReport, phase = SessionPhase.IDLE)

        assertEquals(first.state, second.state)
        assertEquals(first.actions, second.actions)
        assertEquals(first.limitations.size, second.limitations.size)
        assertEquals(first.canStartLiveSession, second.canStartLiveSession)
        assertEquals(first.canUseDemoScript, second.canUseDemoScript)
        assertEquals(first.privacyMode, second.privacyMode)
        assertEquals(first.comparisonKey(), second.comparisonKey())

        // And the absence-of-route case is just as stable.
        val firstNoService = readiness(missingServiceReport, phase = SessionPhase.LISTENING)
        val secondNoService = readiness(missingServiceReport, phase = SessionPhase.LISTENING)
        assertEquals(firstNoService.state, secondNoService.state)
        assertEquals(firstNoService.actions, secondNoService.actions)
        assertEquals(firstNoService.limitations.size, secondNoService.limitations.size)
        assertEquals(firstNoService.comparisonKey(), secondNoService.comparisonKey())
    }

    /** Everything a user could see, flattened: state name plus every copy line in both languages. */
    private fun ReadinessReport.comparisonKey(): String =
        listOf(
            state.name,
            canStartLiveSession.toString(),
            canUseDemoScript.toString(),
            disclosure.name,
            privacyMode.name,
            isListening.toString(),
        ).joinToString("|") + "||" + allCopy.joinToString("|") { it.hinglish + "~" + it.english }

    private companion object {
        /** Words that would turn a network-capable path into a promise the app cannot keep. */
        val FORBIDDEN_PROCESSING_WORDS: List<String> = listOf("on-device", "offline", "private")
    }
}
