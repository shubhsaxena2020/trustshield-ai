/*
 * P3-10 - tests for the live Call Shield host (issue #89).
 *
 * Pure JVM. No android.* type is constructed: the recogniser is a recording fake, the clock is a
 * fake, and nothing sleeps. The host is driven exactly the way the Compose screen will drive it
 * (capability -> start -> utterances -> stop), so the whole join is exercised without a device
 * microphone.
 *
 * What is asserted, in the order the package asked for it:
 *  - a blocked host never reaches the recogniser handle factory;
 *  - mic denied (and permanently denied) and an unavailable recogniser are honest blocks;
 *  - a recogniser that fails while opening the microphone never yields SessionPhase.LISTENING;
 *  - one CBI line lights exactly the AUTHORITY chip through the real A05 scorer, and the meter's
 *    score equals the ScoredResult's score (nothing is hardcoded);
 *  - three cumulative scam lines never release a CRITICAL alert and never claim safety;
 *  - a money ask with two corroborating stages releases CRITICAL once (one haptic pulse) and does
 *    not re-pulse per ASR chunk;
 *  - a noisy chunk holds the alert instead of releasing it and lowers confidence only;
 *  - an early stage stays lit after its words leave the rolling transcript (A05 stage memory);
 *  - stop clears chips/tone/transcript and destroys the recogniser exactly once, twice is a no-op;
 *  - pausing stops the recogniser, resuming never restarts it silently;
 *  - no printable string the host exposes carries an OTP, phone number, card or UPI ID;
 *  - the same utterance sequence always produces identical state.
 *
 * Fixtures are hand-written Hinglish lines, not model output and not a new accuracy claim.
 */
package ai.trustshield.android.ui.call

import ai.trustshield.android.core.normalize.HinglishNormalizer
import ai.trustshield.android.core.score.CallSessionState
import ai.trustshield.android.core.score.LocalScorer
import ai.trustshield.android.core.score.ScoredResult
import ai.trustshield.android.input.speech.CallSpeechSession
import ai.trustshield.android.input.speech.MicPermission as SpeechMic
import ai.trustshield.android.input.speech.RecognizerErrorCode
import ai.trustshield.android.input.speech.RecognizerHandle
import ai.trustshield.android.input.speech.SpeechCapabilityReport
import ai.trustshield.android.input.speech.SpeechCapabilityRules
import ai.trustshield.android.input.speech.SpeechConsent
import ai.trustshield.android.input.speech.SpeechConsentState
import ai.trustshield.android.input.speech.SpeechEnvironment
import ai.trustshield.android.input.speech.SpeechRecognitionMode
import ai.trustshield.android.input.speech.SpeechSessionCoordinator
import ai.trustshield.android.ui.theme.RiskTone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CallShieldHostTest {

    // ------------------------------------------------------------------ fixtures

    private val authorityLine = "Hello, main Mumbai Cyber Crime se Inspector Rathore bol raha hoon."
    private val parcelLine = "Aapke Aadhaar se bheje gaye parcel mein drugs mile hain."
    private val secrecyLine = "Yeh confidential case hai, kisi ko mat batana."
    private val moneyAskLine = "Case clear karne ke liye ₹50,000 RBI verification account mein bhejo."
    private val fillerLine = "Haan ji theek hai bilkul"

    // ------------------------------------------------------------------ harness

    /** Deterministic clock; nothing here reads the wall clock. */
    private class FakeClock(startMillis: Long = 1_000L) {
        var now: Long = startMillis

        fun read(): Long = now

        fun advance(ms: Long) {
            now += ms
        }
    }

    /** Records start/destroy counts. Holds no text and no audio. */
    private class FakeHandle : RecognizerHandle {
        var onStart: () -> Unit = {}
        var startCount: Int = 0
            private set
        var destroyCount: Int = 0
            private set
        var alive: Boolean = false
            private set

        override fun start() {
            startCount++
            alive = true
            onStart()
        }

        override fun destroy() {
            destroyCount++
            alive = false
        }
    }

    /** The host plus the fakes it was built from, so a test can count recogniser creations. */
    private class Rig(
        report: SpeechCapabilityReport = availableReport(),
        consent: SpeechConsent = SpeechConsent.initial().grant(),
        mic: MicPermission = MicPermission.GRANTED,
        val clock: FakeClock = FakeClock(),
        failStart: Boolean = false,
    ) {
        val handle = FakeHandle()

        var handleFactoryCalls: Int = 0
            private set

        private val speechSession = CallSpeechSession(clock = { clock.read() }, consentGranted = true)

        val coordinator = SpeechSessionCoordinator(speechSession) { _: SpeechRecognitionMode ->
            handleFactoryCalls++
            handle
        }

        val host = CallShieldHost(
            coordinator = coordinator,
            clock = { clock.read() },
            callState = CallSessionState(),
        )

        init {
            if (failStart) {
                // The real handle reports a platform failure through B05 before the mic opens.
                handle.onStart = { coordinator.onError(RecognizerErrorCode.INSUFFICIENT_PERMISSIONS) }
            }
            host.onCapability(consent, report, mic)
        }
    }

    /** The one deterministic reading A05 produces for a single line, computed independently. */
    private fun expectedReading(text: String, asrConfidence: Float, atMillis: Long): ScoredResult {
        val state = CallSessionState()
        state.appendUtterance(text, atMillis, asrConfidence.toDouble())
        return LocalScorer(HinglishNormalizer()).analyzeTranscriptScored(text, state)
    }

    private fun CallShieldHost.speak(rig: Rig, text: String, confidence: Float, advanceMs: Long = 2_000L) {
        rig.clock.advance(advanceMs)
        onUtterance(text, confidence, rig.clock.read())
    }

    private fun printable(host: CallShieldHost): String = buildString {
        append(host.toString()).append('\n')
        append(host.state.toString()).append('\n')
        host.state.transcript.forEach { append(it.toString()).append('\n') }
        append(host.reading?.toString().orEmpty())
    }

    private fun assertNoSafetyClaim(host: CallShieldHost) {
        val text = printable(host).lowercase()
        for (word in listOf("safe", "100%", "pakka scam", "guaranteed risk-free")) {
            assertFalse("the host exposed the claim \"$word\"", text.contains(word))
        }
    }

    // ------------------------------------------------------------------ 1. consent gate

    @Test
    fun `no consent blocks the start and never touches the recogniser`() {
        val rig = Rig(consent = SpeechConsent.initial())

        assertEquals(ConsentState.NOT_GIVEN, rig.host.state.consent)
        assertEquals(BlockReason.NEEDS_CONSENT, rig.host.state.blocked)
        assertFalse(rig.host.state.canStart)

        val outcome = rig.host.onStartRequested()

        assertTrue(outcome is CallStartOutcome.Blocked)
        assertEquals(BlockReason.NEEDS_CONSENT, (outcome as CallStartOutcome.Blocked).reason)
        assertEquals(0, rig.handleFactoryCalls)
        assertEquals(0, rig.handle.startCount)
        assertEquals(SessionPhase.IDLE, rig.host.state.phase)
        assertFalse(rig.host.state.isListening)
        assertFalse(rig.coordinator.isActive)
    }

    // ------------------------------------------------------------------ 2. microphone gate

    @Test
    fun `mic permission denied and permanently denied both block without a recogniser`() {
        val denied = listOf(SpeechMic.Denied, SpeechMic.PermanentlyDenied)

        denied.forEach { speechMic ->
            val rig = Rig(report = reportFor(mic = speechMic), mic = MicPermission.DENIED)

            assertEquals(MicPermission.DENIED, rig.host.state.permission)
            assertEquals(BlockReason.MIC_PERMISSION_DENIED, rig.host.state.blocked)
            assertFalse(rig.host.state.canStart)

            val outcome = rig.host.onStartRequested()

            assertEquals(BlockReason.MIC_PERMISSION_DENIED, (outcome as CallStartOutcome.Blocked).reason)
            assertEquals(0, rig.handleFactoryCalls)
            assertEquals(SessionPhase.IDLE, rig.host.state.phase)
            assertFalse(rig.host.state.isListening)
        }
    }

    // ------------------------------------------------------------------ 3. recogniser gate

    @Test
    fun `an unavailable recogniser blocks and never reports LISTENING`() {
        val rig = Rig(report = reportFor(service = false), mic = MicPermission.GRANTED)

        assertEquals(RecognizerStatus.UNAVAILABLE, rig.host.state.recognizer)
        assertEquals(RecognitionDisclosure.UNAVAILABLE, rig.host.state.recognitionDisclosure)
        assertEquals(BlockReason.RECOGNIZER_UNAVAILABLE, rig.host.state.blocked)
        assertFalse(rig.host.state.canStart)

        val outcome = rig.host.onStartRequested()

        assertEquals(BlockReason.RECOGNIZER_UNAVAILABLE, (outcome as CallStartOutcome.Blocked).reason)
        assertEquals(0, rig.handleFactoryCalls)
        assertNotEquals(SessionPhase.LISTENING, rig.host.state.phase)
        assertFalse(rig.host.state.isListening)
    }

    // ------------------------------------------------------------------ 4. a handle that dies on start

    @Test
    fun `a recogniser that fails while opening the mic never yields LISTENING`() {
        val rig = Rig(failStart = true)

        val outcome = rig.host.onStartRequested()

        assertEquals(BlockReason.MIC_PERMISSION_DENIED, (outcome as CallStartOutcome.Blocked).reason)
        assertEquals(1, rig.handleFactoryCalls)
        assertEquals(1, rig.handle.destroyCount)
        assertFalse(rig.coordinator.isActive)
        assertEquals(SessionPhase.IDLE, rig.host.state.phase)
        assertFalse(rig.host.state.isListening)
        assertNull(rig.host.reading)
    }

    // ------------------------------------------------------------------ 5. authority, real A05 path

    @Test
    fun `a CBI authority line lights only AUTHORITY and the meter matches the scored result`() {
        val rig = Rig()
        rig.host.onStartRequested()
        rig.clock.advance(1_000)
        val atMillis = rig.clock.read()

        rig.host.onUtterance(authorityLine, 0.93f, atMillis)

        val state = rig.host.state
        assertEquals(SessionPhase.LISTENING, state.phase)
        assertEquals(listOf(CallStage.AUTHORITY), state.litStages)
        assertEquals(1, state.chip(CallStage.AUTHORITY)?.acceptedQuotes)
        val quote = state.chip(CallStage.AUTHORITY)?.quote
        assertNotNull(quote)
        assertTrue("the chip carries the heard words: $quote", quote!!.contains("Cyber Crime"))
        assertEquals(
            listOf(CallStage.THREAT_REWARD, CallStage.URGENCY, CallStage.SECRECY_ISOLATION, CallStage.ASK),
            state.stages.filter { !it.lit }.map { it.stage },
        )

        // The meter is A05's score, not a number this package chose.
        val reading = rig.host.reading
        assertNotNull(reading)
        val expected = expectedReading(authorityLine, 0.93f, atMillis)
        assertEquals(expected.result.risk.score, reading!!.score)
        assertEquals(expected.result.risk.label, reading.label)
        assertEquals(expected.result.risk.confidence, reading.confidence)
        assertEquals(expected.result.risk.score, rig.host.score)
        assertEquals(listOf(CallStage.AUTHORITY), reading.stages)

        // Score and confidence stay separate fields with separate vocabularies.
        assertEquals(ConfidenceLevel.MEDIUM, state.confidence)
        assertEquals(1, state.clearChunks)
        assertEquals(0, state.heldChunks)
        assertEquals(AudioQuality.CLEAR, state.audioQuality)
        assertNotEquals(RiskTone.NEUTRAL, state.tone)
        assertNull("authority alone never accuses", state.alert)
        assertNoSafetyClaim(rig.host)
    }

    // ------------------------------------------------------------------ 6. three stages, no alarm

    @Test
    fun `three cumulative scam lines never release a critical alert and never claim safe`() {
        val rig = Rig()
        rig.host.onStartRequested()

        rig.host.speak(rig, authorityLine, 0.94f)
        assertNotEquals(AlertSeverity.CRITICAL, rig.host.state.alert?.severity)
        assertEquals(0, rig.host.state.hapticPulses)

        rig.host.speak(rig, parcelLine, 0.94f)
        assertNotEquals(AlertSeverity.CRITICAL, rig.host.state.alert?.severity)
        assertEquals(0, rig.host.state.hapticPulses)

        rig.host.speak(rig, secrecyLine, 0.94f)
        val state = rig.host.state

        assertNotEquals(AlertSeverity.CRITICAL, state.alert?.severity)
        assertNull(state.heldAlert)
        assertEquals(AlertSeverity.CAUTION, state.alert?.severity)
        assertEquals(RiskTone.AMBER, state.tone)
        assertEquals(
            listOf(CallStage.AUTHORITY, CallStage.THREAT_REWARD, CallStage.SECRECY_ISOLATION),
            state.litStages,
        )
        assertEquals(0, state.hapticPulses)
        assertFalse(state.moneyOrCredentialAsk)
        assertNoSafetyClaim(rig.host)
    }

    // ------------------------------------------------------------------ 7. the real escalation

    @Test
    fun `a money ask with two corroborating stages escalates once and never re-pulses`() {
        val rig = Rig()
        rig.host.onStartRequested()

        rig.host.speak(rig, authorityLine, 0.95f)
        rig.host.speak(rig, parcelLine, 0.95f)
        assertNotEquals("two stages alone are below the bar", AlertSeverity.CRITICAL, rig.host.state.alert?.severity)

        rig.host.speak(rig, moneyAskLine, 0.95f)

        val escalated = rig.host.state
        assertEquals(AlertSeverity.CRITICAL, escalated.alert?.severity)
        assertEquals(RiskTone.RED, escalated.tone)
        assertEquals(1, escalated.hapticPulses)
        assertEquals(RiskTone.RED, escalated.lastHapticTone)
        assertTrue(escalated.moneyOrCredentialAsk)
        assertEquals(CallStage.ASK, escalated.litStages.last())

        // Another clear chunk must not pulse again per ASR chunk.
        rig.host.speak(rig, fillerLine, 0.96f)

        assertEquals(1, rig.host.state.hapticPulses)
        assertEquals(AlertSeverity.CRITICAL, rig.host.state.alert?.severity)
    }

    // ------------------------------------------------------------------ 8. the hold

    @Test
    fun `a noisy chunk marks audio unclear and holds the alert instead of releasing it`() {
        val rig = Rig()
        rig.host.onStartRequested()

        rig.host.speak(rig, authorityLine, 0.95f)
        rig.host.speak(rig, parcelLine, 0.95f)
        assertNull("nothing has been released yet", rig.host.state.alert)
        val credited = rig.host.reading
        assertNotNull(credited)

        rig.host.speak(rig, moneyAskLine, 0.31f)

        val held = rig.host.state
        assertEquals(AudioQuality.UNCLEAR, held.audioQuality)
        assertEquals(ConfidenceLevel.LOW, held.confidence)
        assertNull("a murky ask is never released", held.alert)
        val wouldBe = held.heldAlert
        assertNotNull(wouldBe)
        assertEquals(AlertSeverity.CRITICAL, wouldBe!!.severity)
        assertEquals(RiskTone.GREEN, held.tone)
        assertEquals(1, held.heldChunks)
        assertEquals(0, held.hapticPulses)
        assertFalse("a held ask never lights the chip", held.chip(CallStage.ASK)?.lit ?: false)

        // The meter keeps the last credited reading: a noisy chunk cannot raise the score.
        assertEquals(credited, rig.host.reading)
        assertEquals(credited!!.score, rig.host.score)
        assertNotNull(rig.host.engineConfidence)
    }

    // ------------------------------------------------------------------ 9. stage persistence

    @Test
    fun `an early stage stays lit after its words leave the rolling transcript`() {
        val rig = Rig()
        rig.host.onStartRequested()
        rig.host.speak(rig, authorityLine, 0.95f, advanceMs = 1_000L)

        assertEquals(listOf(CallStage.AUTHORITY), rig.host.state.litStages)

        repeat(CallShieldReducer.MAX_TRANSCRIPT_LINES + 8) {
            rig.host.speak(rig, fillerLine, 0.95f)
        }

        val state = rig.host.state
        assertTrue("the authority chip stays lit", state.chip(CallStage.AUTHORITY)?.lit == true)
        assertEquals(1, state.chip(CallStage.AUTHORITY)?.acceptedQuotes)
        assertNotNull(state.chip(CallStage.AUTHORITY)?.quote)

        val transcript = state.transcript.joinToString(" ") { it.text }
        assertTrue("the rolling transcript is bounded", state.transcript.size <= CallShieldReducer.MAX_TRANSCRIPT_LINES)
        assertFalse("the early words left the window", transcript.contains("Cyber Crime"))
        assertFalse("the early words left the window", transcript.contains("Rathore"))

        // A05's own stage memory still holds the stage, which is why the chip is still lit.
        val reading = rig.host.reading
        assertNotNull(reading)
        assertTrue(CallStage.AUTHORITY in reading!!.stages)
    }

    // ------------------------------------------------------------------ 10. stop

    @Test
    fun `stop clears the session, destroys the recogniser once and a second stop is a no-op`() {
        val rig = Rig()
        rig.host.onStartRequested()
        rig.host.speak(rig, authorityLine, 0.95f)
        rig.host.speak(rig, parcelLine, 0.95f)
        rig.host.speak(rig, moneyAskLine, 0.95f)

        assertEquals(AlertSeverity.CRITICAL, rig.host.state.alert?.severity)
        assertEquals(0, rig.handle.destroyCount)
        assertTrue(rig.handle.alive)

        rig.host.onStop()

        val stopped = rig.host.state
        assertEquals(SessionPhase.STOPPED, stopped.phase)
        assertFalse(stopped.isListening)
        assertTrue("no chip stays lit", stopped.litStages.isEmpty())
        assertEquals(CallStage.COUNT, stopped.stages.size)
        assertTrue(stopped.stages.all { it.acceptedQuotes == 0 && it.quote == null && it.seenKeys.isEmpty() })
        assertTrue(stopped.transcript.isEmpty())
        assertEquals(RiskTone.NEUTRAL, stopped.tone)
        assertNull(stopped.alert)
        assertNull(stopped.heldAlert)
        assertEquals(ConfidenceLevel.UNABLE_TO_VERIFY, stopped.confidence)
        assertEquals(AlertSeverity.NONE, CallRiskGate.evaluate(stopped.stages, stopped.moneyOrCredentialAsk).severity)

        assertEquals(1, rig.handle.destroyCount)
        assertFalse(rig.handle.alive)
        assertFalse(rig.coordinator.isActive)
        assertNull("the reading is gone with the session", rig.host.reading)
        assertTrue("nothing keeps the words", rig.host.retainsNoTranscript)
        assertTrue(rig.coordinator.stoppedTranscriptIsEmpty)

        rig.host.onStop()

        assertEquals(1, rig.handle.destroyCount)
        assertEquals(SessionPhase.STOPPED, rig.host.state.phase)
        assertTrue(rig.host.state.transcript.isEmpty())
    }

    // ------------------------------------------------------------------ 11. pause / resume

    @Test
    fun `pausing stops the recogniser and resuming never restarts it silently`() {
        val rig = Rig()
        rig.host.onStartRequested()
        rig.host.speak(rig, authorityLine, 0.95f)

        assertEquals(1, rig.handle.startCount)
        assertTrue(rig.host.state.isListening)
        assertTrue(rig.handle.alive)

        rig.host.onHostPaused()

        assertEquals(SessionPhase.PAUSED, rig.host.state.phase)
        assertFalse(rig.host.state.isListening)
        assertFalse(rig.handle.alive)
        assertEquals(1, rig.handle.destroyCount)
        assertFalse(rig.coordinator.isActive)
        assertEquals(1, rig.handleFactoryCalls)

        rig.host.onHostResumed()

        assertEquals("resume must not open a new recogniser", 1, rig.handleFactoryCalls)
        assertEquals(1, rig.handle.destroyCount)
        assertNotEquals(SessionPhase.LISTENING, rig.host.state.phase)
        assertFalse(rig.host.state.isListening)
        assertFalse(rig.coordinator.isActive)

        // A chunk arriving while paused moves nothing.
        val before = rig.host.state
        rig.host.onUtterance(moneyAskLine, 0.95f, rig.clock.read())
        assertSame(before, rig.host.state)

        // Only an explicit start re-opens the microphone, and it continues the same session.
        val restarted = rig.host.onStartRequested()
        assertTrue(restarted is CallStartOutcome.Listening)
        assertEquals(2, rig.handleFactoryCalls)
        assertEquals(2, rig.handle.startCount)
        assertEquals(1, rig.handle.destroyCount)
        assertEquals(SessionPhase.LISTENING, rig.host.state.phase)
        assertTrue(rig.host.state.isListening)
        assertTrue("a paused session keeps its chips", rig.host.state.chip(CallStage.AUTHORITY)?.lit == true)
    }

    @Test
    fun `resuming after consent was withdrawn keeps the host blocked`() {
        val rig = Rig()
        rig.host.onStartRequested()
        rig.host.onHostPaused()
        assertEquals(SessionPhase.PAUSED, rig.host.state.phase)

        rig.host.onCapability(
            SpeechConsent.fromStored(SpeechConsentState.Withdrawn),
            availableReport(),
            MicPermission.GRANTED,
        )

        assertEquals(ConsentState.NOT_GIVEN, rig.host.state.consent)
        assertEquals(BlockReason.NEEDS_CONSENT, rig.host.state.blocked)
        assertEquals(SessionPhase.STOPPED, rig.host.state.phase)
        assertFalse(rig.coordinator.isActive)

        rig.host.onHostResumed()

        assertFalse(rig.host.state.isListening)
        assertEquals(BlockReason.NEEDS_CONSENT, rig.host.state.blocked)
        assertEquals(1, rig.handleFactoryCalls)

        assertEquals(BlockReason.NEEDS_CONSENT, (rig.host.onStartRequested() as CallStartOutcome.Blocked).reason)
        assertEquals(1, rig.handleFactoryCalls)
    }

    @Test
    fun `terminal coordinator callback moves host out of listening`() {
        val rig = Rig()
        assertTrue(rig.host.onStartRequested() is CallStartOutcome.Listening)

        val event = rig.coordinator.onError(RecognizerErrorCode.SERVER)
        rig.host.onCoordinatorError(event)

        assertEquals(SessionPhase.STOPPED, rig.host.state.phase)
        assertFalse(rig.host.state.isListening)
        assertFalse(rig.host.isListening)
        assertTrue(rig.host.retainsNoTranscript)
    }

    @Test
    fun `missing platform service can start explicitly selected bundled offline route`() {
        val missingService = SpeechCapabilityRules.evaluate(
            ai.trustshield.android.input.speech.SpeechEnvironment(
                apiLevel = 36,
                recognizerServiceAvailable = false,
                onDeviceAvailable = false,
                hiInModelDownloaded = false,
                micPermission = SpeechMic.Granted,
            ),
            ai.trustshield.android.input.speech.SpeechLanguage.HI_IN,
        )
        val rig = Rig(report = missingService)
        rig.host.onCapability(
            SpeechConsent.initial().grant(), missingService, MicPermission.GRANTED,
            offlineEngineSelected = true,
        )

        val outcome = rig.host.onStartRequested()

        assertTrue(outcome is CallStartOutcome.Listening)
        assertEquals(SessionPhase.LISTENING, rig.host.state.phase)
        assertEquals(RecognizerStatus.AVAILABLE, rig.host.state.recognizer)
    }

    // ------------------------------------------------------------------ 12. clear

    @Test
    fun `clearing the host releases the recogniser once and the host cannot restart`() {
        val rig = Rig()
        rig.host.onStartRequested()
        rig.host.speak(rig, authorityLine, 0.9f)

        rig.host.onCleared()

        assertEquals(1, rig.handle.destroyCount)
        assertFalse(rig.handle.alive)
        assertEquals(SessionPhase.STOPPED, rig.host.state.phase)
        assertTrue(rig.host.retainsNoTranscript)
        assertNull(rig.host.reading)

        rig.host.onCleared()
        rig.host.onStop()
        assertEquals(1, rig.handle.destroyCount)

        assertEquals(BlockReason.NOT_LISTENING, (rig.host.onStartRequested() as CallStartOutcome.Blocked).reason)
        assertEquals("a cleared host never builds another recogniser", 1, rig.handleFactoryCalls)
    }

    // ------------------------------------------------------------------ 13. privacy

    @Test
    fun `no printable string the host exposes carries a sample secret`() {
        val rig = Rig()
        rig.host.onStartRequested()
        rig.clock.advance(1_000)
        rig.host.onUtterance(
            "Jo OTP 482913 aaya hai woh batao, mera phone 9876543210 hai aur UPI ramesh.kumar@okhdfcbank hai.",
            0.9f,
            rig.clock.read(),
        )

        assertTrue("the rolling transcript is populated", rig.host.state.transcript.isNotEmpty())
        assertNotNull(rig.host.state.chip(CallStage.ASK)?.quote)

        val exposed = printable(rig.host)
        val secrets = listOf(
            "482913",
            "9876543210",
            "ramesh.kumar@okhdfcbank",
            "4321 9876 5432 1098",
            "5432",
            "1098",
        )
        secrets.forEachIndexed { index, secret ->
            assertFalse("sample secret #$index leaked into a printable host string", exposed.contains(secret))
        }
        // The masking is real: the display line keeps the words and drops the digits.
        assertTrue(exposed.contains("OTP"))
        assertTrue(exposed.contains("batao"))

        rig.host.onStop()
        assertTrue("stop wipes every retained word", rig.host.retainsNoTranscript)
    }

    // ------------------------------------------------------------------ 14. determinism

    @Test
    fun `the same utterance sequence twice produces identical scores and chips`() {
        val sequence = listOf(
            authorityLine to 0.95f,
            parcelLine to 0.94f,
            secrecyLine to 0.93f,
            moneyAskLine to 0.96f,
            fillerLine to 0.92f,
        )

        fun run(): Pair<CallUiState, CallShieldReading?> {
            val rig = Rig()
            rig.host.onStartRequested()
            sequence.forEach { (line, confidence) ->
                rig.host.speak(rig, line, confidence)
            }
            return rig.host.state to rig.host.reading
        }

        val (firstState, firstReading) = run()
        val (secondState, secondReading) = run()

        assertEquals(firstState, secondState)
        assertEquals(firstReading, secondReading)
        assertEquals(firstState.litStages, secondState.litStages)
        assertEquals(firstState.stages.map { it.stage }, secondState.stages.map { it.stage })
        assertEquals(firstState.tone, secondState.tone)
        assertEquals(firstState.hapticPulses, secondState.hapticPulses)
        assertNotNull(firstReading)
    }
}

// ---------------------------------------------------------------------------------------------
// File-level capability fixtures: nested classes cannot see an outer instance's members, and the
// fakes need these in their default arguments. Built through the real rule object, so a test can
// never invent a status the rules do not emit.
// ---------------------------------------------------------------------------------------------

private fun reportFor(
    service: Boolean = true,
    apiLevel: Int = 33,
    mic: SpeechMic = SpeechMic.Granted,
): SpeechCapabilityReport = SpeechCapabilityRules.evaluate(
    SpeechEnvironment(
        apiLevel = apiLevel,
        recognizerServiceAvailable = service,
        onDeviceAvailable = false,
        hiInModelDownloaded = false,
        micPermission = mic,
    ),
)

/** Cloud-capable but usable: the honest default on a phone whose offline path is not verified. */
private fun availableReport(): SpeechCapabilityReport = reportFor()
