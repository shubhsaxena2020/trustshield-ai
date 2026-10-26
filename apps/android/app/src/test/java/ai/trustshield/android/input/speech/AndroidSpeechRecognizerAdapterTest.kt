/*
 * P3-09 - tests for the join between B04 (capability + consent), B05 (bounded session) and a
 * recogniser handle.
 *
 * Pure JVM. `android.jar` in this harness only stubs method bodies, so no android.* type is
 * constructed here: the recogniser is a recording fake, the clock is a fake, and nothing sleeps.
 *
 * What is asserted:
 *  - capability is checked before consent, exactly like `ConsentGate`, and a block never reaches
 *    the handle factory;
 *  - `SpeechBlockReason` maps 1:1 from `ConsentBlockReason` (no invented gates);
 *  - an on-device report yields on-device mode with a disclosure that says the offline path is
 *    NOT tested on this device; a cloud report says audio may leave the device and never says
 *    on-device / private / offline;
 *  - a second start cannot create a second recogniser;
 *  - stop and a terminal error each destroy the recogniser exactly once, and the transcript is
 *    gone and unreachable afterwards (checked through every reachable accessor and toString()).
 */
package ai.trustshield.android.input.speech

import ai.trustshield.android.ui.call.RecognitionDisclosure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AndroidSpeechRecognizerAdapterTest {

    // ------------------------------------------------------------------ harness

    private class FakeClock(private var now: Long = 1_000L) {
        fun advance(ms: Long) {
            now += ms
        }

        fun read(): Long = now
    }

    /** Records exactly how often the session started and destroyed it. Holds no text. */
    private class FakeHandle(private val onStart: () -> Unit = {}) : RecognizerHandle {
        var startCount: Int = 0
            private set
        var destroyCount: Int = 0
            private set

        override fun start() {
            startCount++
            onStart()
        }

        override fun destroy() {
            destroyCount++
        }
    }

    private class Harness(consentGranted: Boolean = true) {
        val clock = FakeClock()
        val session = CallSpeechSession(clock = clock::read, consentGranted = consentGranted)
        val handles = mutableListOf<FakeHandle>()
        val modes = mutableListOf<SpeechRecognitionMode>()
        var factoryCalls: Int = 0

        val coordinator = SpeechSessionCoordinator(session) { mode ->
            factoryCalls++
            modes += mode
            // The real recogniser reports onReadyForSpeech from the platform listener.
            FakeHandle(onStart = { session.onReadyForSpeech() }).also { handles += it }
        }

        val handle: FakeHandle
            get() = handles.single()
    }

    /** Built through the real rule object, so a test cannot invent a status the rules never emit. */
    private fun report(
        apiLevel: Int = 33,
        service: Boolean = true,
        onDevice: Boolean = true,
        model: Boolean = true,
        mic: MicPermission = MicPermission.Granted,
        hiInSupported: Boolean = true,
        languageTag: String = SpeechLanguage.HI_IN,
    ): SpeechCapabilityReport = SpeechCapabilityRules.evaluate(
        SpeechEnvironment(
            apiLevel = apiLevel,
            recognizerServiceAvailable = service,
            onDeviceAvailable = onDevice,
            hiInModelDownloaded = model,
            micPermission = mic,
            hiInSupported = hiInSupported,
        ),
        languageTag,
    )

    private fun granted(): SpeechConsent = SpeechConsent.initial().grant()

    private fun blocked(outcome: SpeechStartOutcome): SpeechStartOutcome.Blocked = when (outcome) {
        is SpeechStartOutcome.Blocked -> outcome
        is SpeechStartOutcome.Ready -> throw AssertionError("expected Blocked, got Ready(${outcome.plan})")
    }

    private fun ready(outcome: SpeechStartOutcome): SpeechSessionPlan = when (outcome) {
        is SpeechStartOutcome.Ready -> outcome.plan
        is SpeechStartOutcome.Blocked -> throw AssertionError("expected Ready, got Blocked(${outcome.reason})")
    }

    /** Both languages of every line the plan asks the UI to show. */
    private fun SpeechSessionPlan.allCopy(): String = limitationsAsText()

    private fun startPlan(harness: Harness): SpeechSessionPlan {
        val plan = ready(harness.coordinator.prepare(granted(), report()))
        assertEquals(StartResult.Started, harness.coordinator.start(plan))
        return plan
    }

    /** Drives one prepare() through a throwaway harness, mirroring how the UI would. */
    private fun planCall(
        capability: SpeechCapabilityReport,
        consent: SpeechConsent = granted(),
        languageTag: String = SpeechLanguage.DEFAULT,
    ): SpeechStartOutcome = Harness().coordinator.prepare(consent, capability, languageTag)

    /** The copy the UI gets for one block reason, produced by the same prepare() path as production. */
    private fun blockCopyFor(reason: SpeechBlockReason): SpeechBlockCopy = when (reason) {
        SpeechBlockReason.RecognizerUnavailable -> blocked(planCall(report(service = false))).copy
        SpeechBlockReason.CapabilityUnavailable ->
            blocked(planCall(report(apiLevel = 20, onDevice = false, model = false))).copy
        SpeechBlockReason.ConsentNotAsked -> blocked(planCall(report(), SpeechConsent.initial())).copy
        SpeechBlockReason.ConsentDenied -> blocked(planCall(report(), SpeechConsent.initial().deny())).copy
        SpeechBlockReason.ConsentWithdrawn ->
            blocked(planCall(report(), SpeechConsent.initial().grant().withdraw())).copy
        SpeechBlockReason.MicPermissionNotAsked -> blocked(planCall(report(mic = MicPermission.Unknown))).copy
        SpeechBlockReason.MicPermissionDenied -> blocked(planCall(report(mic = MicPermission.Denied))).copy
        SpeechBlockReason.MicPermissionPermanentlyDenied ->
            blocked(planCall(report(mic = MicPermission.PermanentlyDenied))).copy
        SpeechBlockReason.LanguageNotSelectable -> blocked(planCall(report(), granted(), "ta-IN")).copy
        SpeechBlockReason.LanguageNotVerified ->
            blocked(planCall(report(), granted(), SpeechLanguage.EN_IN)).copy
        SpeechBlockReason.RecognizerBusy -> {
            val harness = Harness()
            startPlan(harness)
            blocked(harness.coordinator.prepare(granted(), report())).copy
        }
    }

    // ------------------------------------------------------- capability before consent

    @Test
    fun missingServiceBlocksWithRecognizerUnavailableAndCreatesNoHandle() {
        val harness = Harness()
        // Consent is deliberately NotAsked: the missing service must be reported first.
        val outcome = blocked(harness.coordinator.prepare(SpeechConsent.initial(), report(service = false)))
        assertEquals(SpeechBlockReason.RecognizerUnavailable, outcome.reason)
        assertEquals(SpeechCopy.recognizerUnavailable, outcome.copy.title)
        assertEquals(0, harness.factoryCalls)
        assertTrue(harness.handles.isEmpty())
    }

    @Test
    fun unsupportedApiLevelBlocksWithCapabilityUnavailableAndCreatesNoHandle() {
        val harness = Harness()
        val capability = report(apiLevel = 20, onDevice = false, model = false)
        assertEquals(SpeechStatus.Unavailable, capability.status)
        val outcome = blocked(harness.coordinator.prepare(granted(), capability))
        assertEquals(SpeechBlockReason.CapabilityUnavailable, outcome.reason)
        assertEquals(SpeechCopy.speechUnavailable, outcome.copy.title)
        assertEquals(0, harness.factoryCalls)
        assertTrue(harness.handles.isEmpty())
    }

    @Test
    fun consentStatesBlockWithTheirOwnReasonAndCreateNoHandleEvenWhenOnDeviceReady() {
        val cases = listOf(
            SpeechConsent.initial() to SpeechBlockReason.ConsentNotAsked,
            SpeechConsent.initial().deny() to SpeechBlockReason.ConsentDenied,
            SpeechConsent.initial().grant().withdraw() to SpeechBlockReason.ConsentWithdrawn,
        )
        for ((consent, expected) in cases) {
            val harness = Harness()
            assertEquals(SpeechStatus.OnDeviceReady, report().status) // the capability itself is fine
            val outcome = blocked(harness.coordinator.prepare(consent, report()))
            assertEquals("consent ${consent.state}", expected, outcome.reason)
            assertTrue(outcome.copy.title.hinglish.isNotBlank())
            assertTrue(outcome.copy.title.english.isNotBlank())
            assertEquals(0, harness.factoryCalls)
            assertTrue(harness.handles.isEmpty())
        }
    }

    @Test
    fun micPermissionStatesBlockWithThreeDistinctReasons() {
        val cases = listOf(
            MicPermission.Unknown to SpeechBlockReason.MicPermissionNotAsked,
            MicPermission.Denied to SpeechBlockReason.MicPermissionDenied,
            MicPermission.PermanentlyDenied to SpeechBlockReason.MicPermissionPermanentlyDenied,
        )
        for ((mic, expected) in cases) {
            val harness = Harness()
            assertEquals(SpeechStatus.NoMicPermission, report(mic = mic).status)
            val outcome = blocked(harness.coordinator.prepare(granted(), report(mic = mic)))
            assertEquals(mic.name, expected, outcome.reason)
            assertEquals(0, harness.factoryCalls)
        }
        // The permanently-denied case is the only one that points at system settings.
        assertEquals(
            SpeechCopy.micPermissionMissing,
            blockCopyFor(SpeechBlockReason.MicPermissionPermanentlyDenied).title,
        )
        assertEquals(
            SpeechPlanCopy.openSettingsForMic,
            blockCopyFor(SpeechBlockReason.MicPermissionPermanentlyDenied).detail,
        )
        assertNull(blockCopyFor(SpeechBlockReason.MicPermissionDenied).detail)
        assertEquals(
            SpeechCopy.micPermissionNotAsked,
            blockCopyFor(SpeechBlockReason.MicPermissionNotAsked).title,
        )
    }

    // ------------------------------------------------------- plan: on-device vs cloud

    @Test
    fun onDeviceReadyPlanUsesOnDeviceModeAndAnHonestDisclosure() {
        val harness = Harness()
        val capability = report(apiLevel = 33)
        assertEquals(SpeechStatus.OnDeviceReady, capability.status)
        val plan = ready(harness.coordinator.prepare(granted(), capability))

        assertEquals(SpeechRecognitionMode.OnDevice, plan.recognitionMode)
        assertEquals(RecognitionDisclosure.ON_DEVICE_UNVERIFIED, plan.disclosure)
        assertEquals(SpeechLanguage.HI_IN, plan.languageTag)
        assertTrue(plan.offlineVerified)
        assertFalse(plan.cloudMayBeUsed)
        assertTrue(plan.onDeviceOnly)
        assertEquals(0, harness.factoryCalls)

        // "reported available" plus "not measured" -- never a verified offline accuracy claim.
        assertTrue(plan.limitations.contains(SpeechCopy.onDeviceReady))
        assertTrue(plan.limitations.contains(SpeechCopy.onDeviceNotVerified))
        assertTrue(plan.limitations.contains(SpeechPlanCopy.audibleSpeechOnly))
        assertTrue(plan.limitations.contains(SpeechPlanCopy.audioNotRetained))
        val copy = plan.allCopy().lowercase()
        assertTrue(copy.contains("not been tested on this device"))
        assertFalse("must not claim measured offline accuracy", copy.contains("verified offline"))
        assertFalse(copy.contains("100%"))
        assertFalse(copy.contains("guaranteed"))
    }

    @Test
    fun cloudOnlyPlanDisclosesThatAudioMayLeaveTheDevice() {
        val harness = Harness()
        val capability = report(apiLevel = 26, onDevice = false, model = false)
        assertEquals(SpeechStatus.CloudOnly, capability.status)
        val plan = ready(harness.coordinator.prepare(granted(), capability))

        assertEquals(SpeechRecognitionMode.DefaultMayUseNetwork, plan.recognitionMode)
        assertEquals(RecognitionDisclosure.MAY_USE_NETWORK, plan.disclosure)
        assertFalse(plan.offlineVerified)
        assertTrue(plan.cloudMayBeUsed)
        assertFalse(plan.onDeviceOnly)

        assertTrue(plan.limitations.contains(SpeechCopy.consentAskCloudPossible))
        assertTrue(plan.allCopy().lowercase().contains("may leave the device"))

        // No privacy or offline promise may survive on the cloud-capable path.
        val copy = plan.allCopy().lowercase()
        assertFalse("cloud copy must not say on-device: $copy", copy.contains("on-device"))
        assertFalse("cloud copy must not say private: $copy", copy.contains("private"))
        assertFalse("cloud copy must not say offline: $copy", copy.contains("offline"))
    }

    @Test
    fun degradedHindiModelStillPlansTheCloudCapableModeWithTheMissingModelLine() {
        val harness = Harness()
        val capability = report(apiLevel = 33, model = false)
        assertEquals(SpeechStatus.OnDeviceUnavailable, capability.status)
        assertTrue(capability.degraded)
        val plan = ready(harness.coordinator.prepare(granted(), capability))

        assertEquals(SpeechRecognitionMode.DefaultMayUseNetwork, plan.recognitionMode)
        assertEquals(RecognitionDisclosure.MAY_USE_NETWORK, plan.disclosure)
        assertTrue(plan.limitations.contains(SpeechCopy.hindiModelMissing))
        assertFalse(plan.offlineVerified)
    }

    @Test
    fun nonSelectableLanguageIsBlockedBeforeAnyHandleIsCreated() {
        val harness = Harness()
        val outcome = blocked(harness.coordinator.prepare(granted(), report(), languageTag = "ta-IN"))
        assertEquals(SpeechBlockReason.LanguageNotSelectable, outcome.reason)
        assertEquals(SpeechPlanCopy.languageNotSupported, outcome.copy.title)
        assertEquals(0, harness.factoryCalls)

        // A report the rules themselves built for an unsupported language stays blocked.
        val unsupported = report(languageTag = "ta-IN")
        assertTrue(unsupported.degraded)
        assertEquals(
            SpeechBlockReason.LanguageNotSelectable,
            blocked(harness.coordinator.prepare(granted(), unsupported, languageTag = "ta-IN")).reason,
        )
        assertEquals(0, harness.factoryCalls)
    }

    @Test
    fun reportProbedForAnotherLanguageIsBlockedInsteadOfGuessed() {
        val harness = Harness()
        val outcome = blocked(
            harness.coordinator.prepare(granted(), report(), languageTag = SpeechLanguage.EN_IN),
        )
        assertEquals(SpeechBlockReason.LanguageNotVerified, outcome.reason)
        assertEquals(0, harness.factoryCalls)
    }

    @Test
    fun anActiveSessionBlocksASecondPrepare() {
        val harness = Harness()
        startPlan(harness)
        val outcome = blocked(harness.coordinator.prepare(granted(), report()))
        assertEquals(SpeechBlockReason.RecognizerBusy, outcome.reason)
        assertEquals(SpeechPlanCopy.sessionAlreadyRunning, outcome.copy.title)
        assertEquals(1, harness.factoryCalls)
    }

    // ------------------------------------------------------- start / stop

    @Test
    fun startCreatesExactlyOneHandleAndReachesListening() {
        val harness = Harness()
        val plan = ready(harness.coordinator.prepare(granted(), report(apiLevel = 33)))

        assertEquals(StartResult.Started, harness.coordinator.start(plan))

        assertEquals(1, harness.factoryCalls)
        assertEquals(listOf(SpeechRecognitionMode.OnDevice), harness.modes)
        assertEquals(1, harness.handle.startCount)
        assertEquals(SpeechSessionState.Listening, harness.session.state)
        assertTrue(harness.coordinator.isActive)
        assertEquals(plan, harness.coordinator.currentPlan)
    }

    @Test
    fun doubleStartDoesNotCreateASecondRecogniser() {
        val harness = Harness()
        val plan = startPlan(harness)

        val second = harness.coordinator.start(plan)

        assertEquals(StartResult.Refused(RefusalReason.ALREADY_RUNNING), second)
        assertEquals(1, harness.factoryCalls)
        assertEquals(1, harness.handles.size)
        assertEquals(1, harness.handle.startCount)
        assertEquals(0, harness.handle.destroyCount)
    }

    @Test
    fun anExternallyStoppedSessionDoesNotLeaveTheCoordinatorBusy() {
        val harness = Harness()
        startPlan(harness)
        harness.session.stop() // somebody else tore the session down and it destroyed its recogniser
        assertEquals(1, harness.handles[0].destroyCount)
        assertFalse(harness.coordinator.isActive)

        // A new attempt must be possible: the stale handle cannot block it.
        val again = ready(harness.coordinator.prepare(granted(), report()))
        assertEquals(StartResult.Started, harness.coordinator.start(again))

        assertEquals(2, harness.factoryCalls)
        assertEquals(2, harness.handles.size)
        assertEquals(1, harness.handles[1].startCount)
        assertTrue(harness.coordinator.isActive)
    }

    @Test
    fun stopDestroysOnceWipesTheTranscriptAndIsIdempotent() {
        val harness = Harness()
        startPlan(harness)
        harness.coordinator.onUtterance("pehla vaakya", 0.9f)
        harness.coordinator.onUtterance("doosra vaakya", 0.9f)
        assertEquals(2, harness.session.transcript().size)
        assertEquals(0, harness.handle.destroyCount)

        harness.coordinator.stop()

        assertEquals(1, harness.handle.destroyCount)
        assertEquals(SpeechSessionState.Stopped, harness.session.state)
        assertTrue(harness.session.transcript().isEmpty())
        assertTrue(harness.coordinator.stoppedTranscriptIsEmpty)
        assertFalse(harness.coordinator.isActive)

        harness.coordinator.stop() // the second stop changes nothing
        assertEquals(1, harness.handle.destroyCount)
        assertTrue(harness.coordinator.stoppedTranscriptIsEmpty)
    }

    @Test
    fun stopBeforeStartDoesNotThrowAndCreatesNothing() {
        val harness = Harness()
        harness.coordinator.stop()
        harness.coordinator.stop()
        assertEquals(0, harness.factoryCalls)
        assertTrue(harness.handles.isEmpty())
        assertTrue(harness.coordinator.stoppedTranscriptIsEmpty)
        assertNull(harness.coordinator.currentPlan)
    }

    @Test
    fun startWithAnUngrantedSessionRefusesAndReleasesTheHandleItJustBuilt() {
        // A session built without consent is a wiring bug: it must not leak a recogniser.
        val harness = Harness(consentGranted = false)
        val plan = ready(harness.coordinator.prepare(granted(), report()))

        val result = harness.coordinator.start(plan)

        assertEquals(StartResult.Refused(RefusalReason.NO_CONSENT), result)
        assertEquals(1, harness.factoryCalls)
        assertEquals(1, harness.handle.destroyCount)
        assertFalse(harness.coordinator.isActive)
    }

    // ------------------------------------------------------- errors

    @Test
    fun insufficientPermissionsErrorIsTerminalAndLeavesNoTranscript() {
        val harness = Harness()
        startPlan(harness)
        harness.coordinator.onUtterance("OTP bata dijiye", 0.8f)
        assertEquals(1, harness.session.transcript().size)

        val event = harness.coordinator.onError(RecognizerErrorCode.INSUFFICIENT_PERMISSIONS)

        assertEquals(SpeechEvent.Failed(ErrorReason.NO_PERMISSION, retryable = false, code = 9), event)
        assertEquals(1, harness.handle.destroyCount)
        assertTrue(harness.session.transcript().isEmpty())
        assertTrue(harness.coordinator.stoppedTranscriptIsEmpty)
        assertFalse(harness.coordinator.isActive)
        assertEquals(ErrorReason.NO_PERMISSION, harness.coordinator.lastError?.reason)
        assertEquals(1, harness.factoryCalls)
    }

    @Test
    fun busyErrorRetriesAndKeepsTheSessionTranscript() {
        val harness = Harness()
        startPlan(harness)
        harness.coordinator.onUtterance("aakhri vaakya", 0.7f)

        val event = harness.coordinator.onError(RecognizerErrorCode.RECOGNIZER_BUSY)

        assertEquals(SpeechEvent.Failed(ErrorReason.BUSY, retryable = true, code = 8), event)
        assertEquals(1, harness.handles[0].destroyCount)
        assertEquals(2, harness.factoryCalls)
        assertEquals(1, harness.session.transcript().size)
        assertTrue(harness.coordinator.isActive)
        assertNull(harness.coordinator.lastError)
    }

    @Test
    fun silenceIsNotAnErrorAndKeepsTheSessionListening() {
        val harness = Harness()
        startPlan(harness)

        val event = harness.coordinator.onError(RecognizerErrorCode.NO_MATCH)

        assertEquals(SpeechEvent.NoSpeech(NoSpeechReason.NO_MATCH), event)
        assertEquals(1, harness.session.noSpeechCount)
        assertEquals(1, harness.handles[0].destroyCount)
        assertEquals(SpeechSessionState.Listening, harness.session.state)
        assertNull(harness.coordinator.lastError)
    }

    @Test
    fun repeatedSilenceAndClientErrorsRecreateImmediatelyWithoutKillingSession() {
        val clock = FakeClock()
        val session = CallSpeechSession(clock = clock::read, consentGranted = true)
        val handles = mutableListOf<FakeHandle>()
        val delays = mutableListOf<Long>()
        val coordinator = SpeechSessionCoordinator(session, { delay, action -> delays += delay; action() }, {
            FakeHandle(onStart = { session.onReadyForSpeech() }).also { handles += it }
        })
        val plan = ready(coordinator.prepare(granted(), report()))
        assertEquals(StartResult.Started, coordinator.start(plan))

        repeat(4) {
            coordinator.onError(RecognizerErrorCode.SPEECH_TIMEOUT)
            coordinator.onError(RecognizerErrorCode.NO_MATCH)
            coordinator.onError(RecognizerErrorCode.CLIENT)
            assertTrue("session remains active", coordinator.isActive)
            assertEquals(SpeechSessionState.Listening, session.state)
        }

        assertEquals(List(4) { 0L }, delays)
        assertEquals(13, handles.size)
        assertEquals(12, handles.count { it.destroyCount == 1 })
        assertEquals(0, handles.last().destroyCount)
    }

    @Test
    fun busyErrorsUseCappedExponentialRetryAndSessionCanStartAgainAfterStop() {
        val session = CallSpeechSession(consentGranted = true)
        val handles = mutableListOf<FakeHandle>()
        val pending = mutableListOf<Pair<Long, () -> Unit>>()
        val delays = mutableListOf<Long>()
        val coordinator = SpeechSessionCoordinator(session, { delay, action -> delays += delay; pending += delay to action }, {
            FakeHandle(onStart = { session.onReadyForSpeech() }).also { handles += it }
        })
        val plan = ready(coordinator.prepare(granted(), report()))
        assertEquals(StartResult.Started, coordinator.start(plan))

        repeat(5) {
            coordinator.onError(RecognizerErrorCode.RECOGNIZER_BUSY)
            pending.removeAt(0).second()
            assertTrue(coordinator.isActive)
        }
        assertEquals(listOf(500L, 1_000L, 2_000L, 4_000L, 4_000L), delays)
        coordinator.stop()
        assertEquals(StartResult.Started, coordinator.start(plan))
        assertTrue(coordinator.isActive)
    }

    // ------------------------------------------------------- transcript discard

    @Test
    fun transcriptIsDiscardedOnStopAndNoRetainedObjectExposesIt() {
        val sentinel = "ZQ9137sentinel"
        val harness = Harness()
        val plan = startPlan(harness)
        harness.coordinator.onUtterance("$sentinel ek", 0.9f)
        harness.clock.advance(1_000)
        harness.coordinator.onUtterance("$sentinel do", 0.9f)
        harness.clock.advance(1_000)
        harness.coordinator.utteranceSink.onUtterance(
            Utterance(text = "$sentinel teen", tsMs = 0L, confidence = 0.9f, isFinal = true, lowConfidence = false),
        )
        assertEquals(3, harness.session.transcript().size)

        // Nothing the coordinator prints may carry speech, before or after the stop.
        assertFalse(harness.coordinator.toString().contains(sentinel))

        harness.coordinator.stop()

        assertTrue(harness.session.transcript().isEmpty())
        assertTrue(harness.session.snapshot().utterances.isEmpty())
        assertTrue(harness.coordinator.stoppedTranscriptIsEmpty)
        assertFalse(harness.coordinator.toString().contains(sentinel))
        assertFalse(plan.toString().contains(sentinel))
    }

    @Test
    fun utteranceSinkIsTheSamePathAsOnUtterance() {
        val harness = Harness()
        startPlan(harness)

        val viaMethod = harness.coordinator.onUtterance("ek", 0.5f)
        harness.coordinator.utteranceSink.onUtterance(
            Utterance("do", 0L, 0.5f, isFinal = true, lowConfidence = false),
        )

        assertTrue(viaMethod is SpeechEvent.Accepted)
        assertEquals(2, harness.session.transcript().size)
    }

    // ------------------------------------------------------- copy honesty

    @Test
    fun everyBlockReasonHasCopyAndNoneOfItClaimsGuaranteedSafety() {
        val forbidden = listOf("100%", "guaranteed", "guarantee", "pakka safe", "definitely safe", "deepfake")
        for (reason in SpeechBlockReason.entries) {
            val copy = blockCopyFor(reason)
            assertFalse("$reason needs a Hinglish line", copy.title.hinglish.isBlank())
            assertFalse("$reason needs an English line", copy.title.english.isBlank())
            val joined = "${copy.title.hinglish} ${copy.title.english} " +
                "${copy.detail?.hinglish.orEmpty()} ${copy.detail?.english.orEmpty()}".lowercase()
            for (word in forbidden) {
                assertFalse("$reason copy claims '$word': $joined", joined.contains(word))
            }
        }
    }

    @Test
    fun planCopyNeverPromisesPrivacyItCannotMeasure() {
        val forbidden = listOf("100%", "guaranteed", "guarantee", "private", "deepfake", " safe")
        for (line in SpeechPlanCopy.all) {
            val joined = "${line.hinglish} ${line.english}".lowercase()
            for (word in forbidden) {
                assertFalse("'${line.english}' claims '$word'", joined.contains(word))
            }
        }
    }

    @Test
    fun blockReasonMapsOneToOneFromTheConsentGate() {
        // `SpeechBlockReason.of` has no `else`, so a new ConsentBlockReason breaks the build
        // instead of silently falling through to a wrong block reason.
        val mapped = ConsentBlockReason.entries.map { SpeechBlockReason.of(it) }
        assertEquals(ConsentBlockReason.entries.size, mapped.distinct().size)
        assertEquals(
            SpeechBlockReason.RecognizerUnavailable,
            SpeechBlockReason.of(ConsentBlockReason.RecognizerUnavailable),
        )
        assertEquals(SpeechBlockReason.ConsentDenied, SpeechBlockReason.of(ConsentBlockReason.ConsentDenied))
        // The interruption and language cases are ours, not the consent gate's.
        assertFalse(mapped.contains(SpeechBlockReason.RecognizerBusy))
        assertFalse(mapped.contains(SpeechBlockReason.LanguageNotSelectable))
    }

    @Test
    fun aReadyPlanOnlyEverUsesOneOfTwoModes() {
        val onDevice = ready(Harness().coordinator.prepare(granted(), report(apiLevel = 33)))
        val cloud = ready(
            Harness().coordinator.prepare(granted(), report(apiLevel = 30, onDevice = true, model = true)),
        )
        assertEquals(SpeechRecognitionMode.OnDevice, onDevice.recognitionMode)
        assertEquals(SpeechRecognitionMode.DefaultMayUseNetwork, cloud.recognitionMode)
        assertTrue(cloud.cloudMayBeUsed)
        assertFalse(cloud.offlineVerified)
    }

    @Test
    fun thePlanHelpersRejectTheWrongOutcomeInsteadOfSilentlyPassing() {
        try {
            ready(planCall(report(service = false)))
            fail("ready() must throw for a Blocked outcome")
        } catch (_: AssertionError) {
            // expected
        }
        try {
            blocked(planCall(report()))
            fail("blocked() must throw for a Ready outcome")
        } catch (_: AssertionError) {
            // expected
        }
    }
}
