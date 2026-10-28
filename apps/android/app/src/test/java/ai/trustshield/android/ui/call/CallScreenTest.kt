package ai.trustshield.android.ui.call

import ai.trustshield.android.ui.theme.RiskTone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the Call Shield presenter (work package C05, issue #46).
 *
 * These cover the behaviour the screen is judged on: the consent gate, the five fixed stage chips,
 * duplicate-utterance handling, the "awaaz saaf nahi" hold, the single-shot haptic rule, stop
 * clearing transcript-derived state, and the honest degraded states.
 */
class CallScreenTest {

    @Test
    fun noSpeechHintHasHindiCopy() {
        assertEquals(
            "सुन रहा है - अभी कोई आवाज़ नहीं सुनी",
            CallShieldCopy.micNoSpeechYet.forLanguage(CallLanguage.HINDI),
        )
    }

    // ---- fixtures (realistic Hinglish scam lines; NOT model output) ------------------------------

    private val authorityClaim = StageSignal(
        stage = CallStage.AUTHORITY,
        quote = "Main Mumbai Cyber Crime se Inspector Rathore bol raha hoon"
    )
    private val secrecyPush = StageSignal(
        stage = CallStage.SECRECY_ISOLATION,
        quote = "Yeh confidential case hai, kisi ko mat batana"
    )
    private val urgencyPush = StageSignal(
        stage = CallStage.URGENCY,
        quote = "Aaj raat tak case clear karna hoga"
    )
    private val moneyAsk = StageSignal(
        stage = CallStage.ASK,
        quote = "Case clear karne ke liye 50,000 RBI verification account mein bhejo",
        moneyOrCredentialAsk = true
    )
    private val otpAsk = StageSignal(
        stage = CallStage.ASK,
        quote = "Jo OTP aaya hai woh batao",
        moneyOrCredentialAsk = true
    )

    /** Consent given, mic granted, recogniser available, session listening. */
    private fun ready(): CallUiState {
        var state = CallUiState()
        state = CallShieldReducer.reduce(state, CallEvent.GiveConsent)
        state = CallShieldReducer.reduce(state, CallEvent.MicPermissionGranted)
        state = CallShieldReducer.reduce(state, CallEvent.RecognizerAvailable)
        return CallShieldReducer.reduce(state, CallEvent.Start)
    }

    private fun clearChunk(text: String, vararg signals: StageSignal): SpeechChunk =
        SpeechChunk(asrConfidence = 0.92f, signals = signals.toList(), text = text)

    private fun murkyChunk(text: String, vararg signals: StageSignal): SpeechChunk =
        SpeechChunk(asrConfidence = 0.31f, signals = signals.toList(), text = text)

    // ---- (a) consent gate -----------------------------------------------------------------------

    @Test
    fun consentGate_blocksListeningAndIgnoresEvidence() {
        var state = CallUiState()
        state = CallShieldReducer.reduce(state, CallEvent.Start)

        assertEquals(SessionPhase.IDLE, state.phase)
        assertEquals(BlockReason.NEEDS_CONSENT, state.blocked)
        assertFalse(state.canStart)

        // A stray chunk while the gate is closed must not light a single stage.
        val afterChunk = CallShieldReducer.reduce(
            state,
            CallEvent.Chunk(clearChunk("main CBI se bol raha hoon, OTP batao", authorityClaim, otpAsk))
        )
        assertEquals(0, afterChunk.litStages.size)
        assertEquals(RiskTone.NEUTRAL, afterChunk.tone)
        assertNull(afterChunk.alert)

        // Consent alone is not enough: permission and recognition must be affirmatively ready.
        val consented = CallShieldReducer.reduce(state, CallEvent.GiveConsent)
        assertEquals(SessionPhase.IDLE, consented.phase)
        assertFalse(consented.canStart)

        val stillWaiting = CallShieldReducer.reduce(consented, CallEvent.Start)
        assertEquals(SessionPhase.IDLE, stillWaiting.phase)

        val permissionGranted = CallShieldReducer.reduce(consented, CallEvent.MicPermissionGranted)
        assertFalse(permissionGranted.canStart)
        assertEquals(SessionPhase.IDLE, CallShieldReducer.reduce(permissionGranted, CallEvent.Start).phase)

        val recognizerReady = CallShieldReducer.reduce(permissionGranted, CallEvent.RecognizerAvailable)
        assertTrue(recognizerReady.canStart)

        val listening = CallShieldReducer.reduce(recognizerReady, CallEvent.Start)
        assertEquals(SessionPhase.LISTENING, listening.phase)
        assertEquals(BlockReason.NONE, listening.blocked)
    }

    // ---- (d) five stage chips ------------------------------------------------------------------

    @Test
    fun stages_areAlwaysExactlyFiveInFixedOrder() {
        val state = ready()
        assertEquals(CallStage.COUNT, state.stages.size)
        assertEquals(CallStage.ordered, state.stages.map { it.stage })
        assertTrue(state.stages.none { it.lit })
    }

    @Test
    fun duplicateUtterance_doesNotCreateASecondStageOrExtraCorroboration() {
        var state = ready()
        val chunk = clearChunk("Main CBI se bol raha hoon", authorityClaim)

        state = CallShieldReducer.reduce(state, CallEvent.Chunk(chunk))
        val first = state.chip(CallStage.AUTHORITY)!!
        assertTrue(first.lit)
        assertEquals(1, first.acceptedQuotes)
        assertEquals(1, state.litStages.size)

        // Same words heard again (and again) -- identical repeat, not independent evidence.
        state = CallShieldReducer.reduce(state, CallEvent.Chunk(chunk))
        state = CallShieldReducer.reduce(state, CallEvent.Chunk(chunk))

        assertEquals(CallStage.COUNT, state.stages.size)
        assertEquals(1, state.litStages.size)
        assertEquals(1, state.chip(CallStage.AUTHORITY)!!.acceptedQuotes)
        // Authority alone is watch-level: it must never produce a high-risk tone.
        assertEquals(RiskTone.GREEN, state.tone)
        assertNotEquals(RiskTone.RED, state.tone)
    }

    @Test
    fun repeatedAskAlone_staysCaution_notHighRisk() {
        var state = ready()
        state = CallShieldReducer.reduce(state, CallEvent.Chunk(clearChunk("jo OTP aaya hai batao", otpAsk)))
        state = CallShieldReducer.reduce(state, CallEvent.Chunk(clearChunk("jo OTP aaya hai batao", otpAsk)))

        assertEquals(RiskTone.AMBER, state.tone)
        assertEquals(AlertSeverity.CAUTION, state.alert!!.severity)
        assertEquals(1, state.litStages.size)
    }

    // ---- (e)/(f) risk + confidence, low-confidence hold ------------------------------------------

    @Test
    fun lowConfidenceInput_suppressesCriticalEscalationUntilCorroborated() {
        var state = ready()
        // Everything a critical alert needs, in ONE line that was not heard clearly.
        state = CallShieldReducer.reduce(
            state,
            CallEvent.Chunk(murkyChunk("CBI, kisi ko mat batana, 50,000 bhejo", authorityClaim, secrecyPush, moneyAsk))
        )

        assertEquals(RiskTone.NEUTRAL, state.tone)
        assertNull(state.alert)
        assertNotNull("the alert must be held, not dropped", state.heldAlert)
        assertEquals(AlertSeverity.CRITICAL, state.heldAlert!!.severity)
        assertEquals(AudioQuality.UNCLEAR, state.audioQuality)
        assertEquals(ConfidenceLevel.LOW, state.confidence)
        assertEquals(0, state.litStages.size)
        assertEquals(0, state.hapticPulses)

        // A clear line that corroborates releases it.
        state = CallShieldReducer.reduce(
            state,
            CallEvent.Chunk(clearChunk("main CBI se bol raha hoon", authorityClaim, secrecyPush, moneyAsk))
        )
        assertEquals(RiskTone.RED, state.tone)
        assertEquals(AlertSeverity.CRITICAL, state.alert!!.severity)
        assertNull(state.heldAlert)
        assertEquals(3, state.litStages.size)
        assertEquals(1, state.hapticPulses)
    }

    @Test
    fun retryAfterLowConfidence_clearsTheHoldWithoutInventingEvidence() {
        var state = ready()
        state = CallShieldReducer.reduce(
            state,
            CallEvent.Chunk(murkyChunk("kuch saaf nahi", authorityClaim, secrecyPush, moneyAsk))
        )
        assertNotNull(state.heldAlert)

        state = CallShieldReducer.reduce(state, CallEvent.RetryListening)
        assertNull(state.heldAlert)
        assertEquals(0, state.litStages.size)
        assertEquals(RiskTone.NEUTRAL, state.tone)
        assertNull(state.alert)
    }

    @Test
    fun riskAndConfidence_areSeparateReadings() {
        var state = ready()
        repeat(4) { index ->
            state = CallShieldReducer.reduce(
                state,
                CallEvent.Chunk(clearChunk("line $index", StageSignal(CallStage.URGENCY, "abhi turant $index")))
            )
        }
        assertEquals(ConfidenceLevel.HIGH, state.confidence)
        assertEquals(RiskTone.GREEN, state.tone) // urgency only: weak signal, watch tone.
    }

    // ---- (i) haptics -----------------------------------------------------------------------------

    @Test
    fun haptic_firesOnceWhenHighRiskStateIsEntered_andNeverPerChunk() {
        var state = ready()

        // Caution (ask alone) is not the high-risk state.
        state = CallShieldReducer.reduce(state, CallEvent.Chunk(clearChunk("OTP batao", otpAsk)))
        assertEquals(0, state.hapticPulses)

        // Entering high risk fires exactly one pulse.
        state = CallShieldReducer.reduce(
            state,
            CallEvent.Chunk(clearChunk("CBI se bol raha hoon, kisi ko mat batana", authorityClaim, secrecyPush))
        )
        assertEquals(RiskTone.RED, state.tone)
        assertEquals(1, state.hapticPulses)

        // More chunks while already red, and a repeated identical utterance, must not pulse again.
        state = CallShieldReducer.reduce(
            state,
            CallEvent.Chunk(clearChunk("aaj raat tak, jaldi karo", urgencyPush))
        )
        state = CallShieldReducer.reduce(
            state,
            CallEvent.Chunk(clearChunk("aaj raat tak, jaldi karo", urgencyPush))
        )
        assertEquals(RiskTone.RED, state.tone)
        assertEquals(1, state.hapticPulses)
    }

    @Test
    fun haptic_neverFiresWhenHapticsAreDisabled() {
        var state = ready()
        state = CallShieldReducer.reduce(state, CallEvent.SetHaptics(false))
        state = CallShieldReducer.reduce(
            state,
            CallEvent.Chunk(clearChunk("CBI, kisi ko mat batana, 50,000 bhejo", authorityClaim, secrecyPush, moneyAsk))
        )

        assertEquals(RiskTone.RED, state.tone)
        assertEquals(0, state.hapticPulses)
    }

    // ---- (j) stop ---------------------------------------------------------------------------------

    @Test
    fun stop_clearsTranscriptDerivedStateButKeepsStageIds() {
        var state = ready()
        state = CallShieldReducer.reduce(
            state,
            CallEvent.Chunk(clearChunk("main CBI se bol raha hoon", authorityClaim, secrecyPush))
        )
        state = CallShieldReducer.reduce(state, CallEvent.Tick(47))
        assertEquals(2, state.litStages.size)
        assertTrue(state.stages.any { it.quote != null })
        assertTrue(state.transcript.isNotEmpty())

        state = CallShieldReducer.reduce(state, CallEvent.Stop)

        assertEquals(SessionPhase.STOPPED, state.phase)
        assertTrue("transcript must be cleared on stop", state.transcript.isEmpty())
        assertTrue("no raw quote may survive stop", state.stages.all { it.quote == null })
        assertTrue("dedupe keys are transcript-derived", state.stages.all { it.seenKeys.isEmpty() })
        // Stage ids for the active session are retained, so the user can still see why.
        assertEquals(2, state.litStages.size)
        assertEquals(47, state.elapsedSeconds)
        assertNull(state.heldAlert)
    }

    @Test
    fun stop_isIdempotent_andStartAfterStopBeginsANewSession() {
        var state = ready()
        state = CallShieldReducer.reduce(
            state,
            CallEvent.Chunk(clearChunk("CBI, 50,000 bhejo, kisi ko mat batana", authorityClaim, secrecyPush, moneyAsk))
        )
        state = CallShieldReducer.reduce(state, CallEvent.Tick(30))
        assertEquals(RiskTone.RED, state.tone)

        state = CallShieldReducer.reduce(state, CallEvent.Stop)
        val stoppedAgain = CallShieldReducer.reduce(state, CallEvent.Stop)
        assertEquals(state, stoppedAgain)

        val fresh = CallShieldReducer.reduce(state, CallEvent.Start)
        assertEquals(SessionPhase.LISTENING, fresh.phase)
        assertEquals(0, fresh.elapsedSeconds)
        assertEquals(0, fresh.litStages.size)
        assertNull(fresh.alert)
        assertEquals(RiskTone.NEUTRAL, fresh.tone)
    }

    // ---- (g)/(h) degraded states ------------------------------------------------------------------

    @Test
    fun permissionDenied_andRecognizerUnavailable_areDistinctAndNeverEscalate() {
        var denied = ready()
        denied = CallShieldReducer.reduce(denied, CallEvent.MicPermissionDenied)
        assertEquals(MicPermission.DENIED, denied.permission)
        assertEquals(BlockReason.MIC_PERMISSION_DENIED, denied.blocked)
        assertEquals(UnableToVerifyReason.MIC_UNAVAILABLE, denied.unableToVerifyReason)
        assertNotEquals(SessionPhase.LISTENING, denied.phase)
        assertEquals(ConfidenceLevel.UNABLE_TO_VERIFY, denied.confidence)
        assertEquals(RiskTone.NEUTRAL, denied.tone)
        assertNull(denied.alert)

        // Evidence arriving after a denial cannot escalate anything either.
        val deniedWithChunk = CallShieldReducer.reduce(
            denied,
            CallEvent.Chunk(clearChunk("CBI, 50,000 bhejo", authorityClaim, secrecyPush, moneyAsk))
        )
        assertEquals(0, deniedWithChunk.litStages.size)
        assertEquals(RiskTone.NEUTRAL, deniedWithChunk.tone)
        assertNull(deniedWithChunk.alert)
        assertEquals(0, deniedWithChunk.hapticPulses)

        var unavailable = ready()
        unavailable = CallShieldReducer.reduce(unavailable, CallEvent.RecognizerUnavailable)
        assertEquals(RecognizerStatus.UNAVAILABLE, unavailable.recognizer)
        assertEquals(BlockReason.RECOGNIZER_UNAVAILABLE, unavailable.blocked)
        assertEquals(UnableToVerifyReason.RECOGNIZER_UNAVAILABLE, unavailable.unableToVerifyReason)
        assertEquals(ConfidenceLevel.UNABLE_TO_VERIFY, unavailable.confidence)
        assertEquals(RiskTone.NEUTRAL, unavailable.tone)
        assertNull(unavailable.alert)

        // The two states are genuinely different, and each blocks Start.
        assertNotEquals(denied.blocked, unavailable.blocked)
        assertNotEquals(denied.unableToVerifyReason, unavailable.unableToVerifyReason)
        assertFalse(denied.canStart)
        assertFalse(unavailable.canStart)
        assertEquals(SessionPhase.IDLE, CallShieldReducer.reduce(denied, CallEvent.Start).phase)
        assertEquals(SessionPhase.IDLE, CallShieldReducer.reduce(unavailable, CallEvent.Start).phase)
    }

    @Test
    fun unreadableInput_yieldsUnableToVerify_notHighRisk() {
        var state = ready()
        val unreadable = SpeechChunk(
            asrConfidence = 0.95f, // "confident" ASR but nothing usable was actually heard
            signals = listOf(authorityClaim, secrecyPush, moneyAsk),
            text = "CBI 50,000 bhejo",
            audible = false
        )
        state = CallShieldReducer.reduce(state, CallEvent.Chunk(unreadable))

        assertEquals(ConfidenceLevel.UNABLE_TO_VERIFY, state.confidence)
        assertEquals(UnableToVerifyReason.UNREADABLE_AUDIO, state.unableToVerifyReason)
        assertEquals(AudioQuality.UNREADABLE, state.audioQuality)
        assertEquals(0, state.litStages.size)
        assertEquals(RiskTone.NEUTRAL, state.tone)
        assertNull(state.alert)
        assertEquals(0, state.hapticPulses)
    }

    @Test
    fun unsupportedLanguage_yieldsUnableToVerify_notHighRisk() {
        var state = ready()
        val unsupported = SpeechChunk(
            asrConfidence = 0.95f,
            signals = listOf(authorityClaim, secrecyPush, moneyAsk),
            text = "some unreadable script",
            languageSupported = false
        )
        state = CallShieldReducer.reduce(state, CallEvent.Chunk(unsupported))

        assertEquals(ConfidenceLevel.UNABLE_TO_VERIFY, state.confidence)
        assertEquals(UnableToVerifyReason.UNSUPPORTED_LANGUAGE, state.unableToVerifyReason)
        assertEquals(RiskTone.NEUTRAL, state.tone)
        assertNull(state.alert)
        assertEquals(0, state.litStages.size)
    }

    @Test
    fun unverifiedQuotes_areDropped() {
        var state = ready()
        val unverified = StageSignal(
            stage = CallStage.ASK,
            quote = "maange ka koi saboot nahi",
            verified = false,
            moneyOrCredentialAsk = true
        )
        state = CallShieldReducer.reduce(state, CallEvent.Chunk(clearChunk("???", unverified, authorityClaim)))

        assertEquals(0, state.chip(CallStage.ASK)!!.acceptedQuotes)
        assertEquals(1, state.chip(CallStage.AUTHORITY)!!.acceptedQuotes)
        assertNotEquals(RiskTone.RED, state.tone)
    }

    // ---- (c) timer supplied as input -------------------------------------------------------------

    @Test
    fun timer_isSuppliedAsInput_andPrunesTheTwoMinuteWindow() {
        var state = ready()
        state = CallShieldReducer.reduce(state, CallEvent.Chunk(clearChunk("main CBI se", authorityClaim)))
        state = CallShieldReducer.reduce(state, CallEvent.Chunk(clearChunk("kisi ko mat batana", secrecyPush)))
        assertEquals(2, state.transcript.size)

        state = CallShieldReducer.reduce(state, CallEvent.Tick(90))
        assertEquals(90, state.elapsedSeconds)
        assertEquals(2, state.transcript.size)

        // 90 + 120s later the old lines fall out of the rolling window...
        state = CallShieldReducer.reduce(state, CallEvent.Tick(200))
        assertEquals(200, state.elapsedSeconds)
        assertTrue(state.transcript.isEmpty())
        // ...but the stage ids for this session stay.
        assertEquals(2, state.litStages.size)

        // No internal clock: a tick before the session started is ignored.
        val idle = CallUiState()
        assertEquals(0, CallShieldReducer.reduce(idle, CallEvent.Tick(45)).elapsedSeconds)
    }

    @Test
    fun formatElapsed_padsMinutesAndSeconds() {
        assertEquals("00:00", formatElapsed(0))
        assertEquals("00:07", formatElapsed(7))
        assertEquals("01:05", formatElapsed(65))
        assertEquals("120:00", formatElapsed(7200))
        assertEquals("00:00", formatElapsed(-5))
    }

    @Test
    fun languageToggle_switchesEveryStringSource() {
        val state = ready()
        val english = CallShieldReducer.reduce(state, CallEvent.SetLanguage(CallLanguage.ENGLISH))
        assertEquals(CallLanguage.ENGLISH, english.language)
        assertEquals("Stop — stop listening", CallShieldCopy.stop.forLanguage(english.language))
        assertEquals("Pehchaan", CallShieldCopy.stageLabel(CallStage.AUTHORITY).forLanguage(state.language))
    }

    // ---- copy honesty ----------------------------------------------------------------------------

    @Test
    fun warningCopy_tellsUserToEndTheCallAndVerifyThroughAnOfficialNumber() {
        listOf(AlertSeverity.CAUTION, AlertSeverity.HIGH, AlertSeverity.CRITICAL).forEach { severity ->
            val hinglish = CallShieldCopy.warning(severity).hinglish.lowercase()
            val english = CallShieldCopy.warning(severity).english.lowercase()
            listOf(hinglish, english).forEach { text ->
                assertTrue("$severity copy must name 1930", text.contains("1930"))
                assertTrue("$severity copy must say official", text.contains("official"))
                assertTrue("$severity copy must say end/kat", text.contains("kaat") || text.contains("end the call"))
            }
        }
    }

    @Test
    fun copy_neverClaimsAbsoluteSafetyAndNeverAccusesAPerson() {
        val forbidden = listOf("100% safe", "100% surakshit", "pakka scam", "chor hai", "thief", "criminal hai")
        val all = listOf(
            CallShieldCopy.subtitle,
            CallShieldCopy.honestyNote,
            CallShieldCopy.consentBody(RecognitionDisclosure.NOT_CHECKED),
            CallShieldCopy.warningCaution,
            CallShieldCopy.warningHigh,
            CallShieldCopy.warningCritical,
            CallShieldCopy.noAccusation,
            CallShieldCopy.lowConfidenceBody,
            CallShieldCopy.permissionDeniedBody,
            CallShieldCopy.recognizerUnavailableBody
        )
        all.forEach { copy ->
            val text = (copy.hinglish + " " + copy.english).lowercase()
            forbidden.forEach { bad -> assertFalse("copy must not contain '$bad': $text", text.contains(bad)) }
            assertTrue("hinglish variant is required", copy.hinglish.isNotBlank())
            assertTrue("english variant is required", copy.english.isNotBlank())
        }
        assertTrue(
            "the alert must state it does not accuse anyone",
            CallShieldCopy.noAccusation.hinglish.contains("ilzaam")
        )
    }

    @Test
    fun copy_neverClaimsToCaptureTheRemoteSideOfACall() {
        val text = (CallShieldCopy.subtitle.hinglish + " " + CallShieldCopy.subtitle.english +
            " " + CallShieldCopy.honestyNote.hinglish + " " + CallShieldCopy.honestyNote.english).lowercase()
        assertTrue(text.contains("mic"))
        assertFalse(text.contains("records the other side"))
        assertTrue(
            "honesty note must say the other side is not captured",
            CallShieldCopy.honestyNote.hinglish.contains("doosre taraf ki awaaz nahi") ||
                CallShieldCopy.honestyNote.english.contains("does not capture")
        )
    }

    @Test
    fun uncheckedRecognitionPathWarnsThatAudioMayLeaveTheDevice() {
        val copy = CallShieldCopy.consentBody(RecognitionDisclosure.NOT_CHECKED)
        assertTrue(copy.english.contains("recognition path") && copy.english.contains("has not been checked"))
        assertTrue(copy.english.contains("audio may leave this device"))
        assertTrue(copy.english.contains("does not store audio recordings"))
    }

    @Test
    fun onDeviceRecognitionCopySaysOfflineBehaviorIsUnverified() {
        val copy = CallShieldCopy.consentBody(RecognitionDisclosure.ON_DEVICE_UNVERIFIED)
        assertTrue(copy.english.contains("offline behavior is unverified"))
        assertTrue(copy.hinglish.contains("test nahi hua"))
    }

    @Test
    fun cloudCapableRecognitionCopyDoesNotClaimOfflineProcessing() {
        val copy = CallShieldCopy.consentBody(RecognitionDisclosure.MAY_USE_NETWORK)
        assertTrue(copy.english.contains("may use the internet"))
        assertTrue(copy.english.contains("audio may leave this device"))
        assertFalse(copy.english.contains("offline"))
    }

    @Test
    fun b04CloudOnlyCapabilityMapsToPossibleOffDeviceDisclosure() {
        val report = ai.trustshield.android.input.speech.SpeechCapabilityReport(
            status = ai.trustshield.android.input.speech.SpeechStatus.CloudOnly,
            languageTag = "hi-IN",
            offlineCapable = false,
            cloudMayBeUsed = true,
            degraded = true,
            reasons = emptyList()
        )

        assertEquals(RecognitionDisclosure.MAY_USE_NETWORK, report.toCallRecognitionDisclosure())
    }

    @Test
    fun b04OnDeviceCapabilityMapsToUnverifiedOfflineDisclosure() {
        val report = ai.trustshield.android.input.speech.SpeechCapabilityReport(
            status = ai.trustshield.android.input.speech.SpeechStatus.OnDeviceReady,
            languageTag = "hi-IN",
            offlineCapable = true,
            cloudMayBeUsed = false,
            degraded = false,
            reasons = listOf(ai.trustshield.android.input.speech.CapabilityReason.OnDeviceNotMeasured)
        )

        assertEquals(RecognitionDisclosure.ON_DEVICE_UNVERIFIED, report.toCallRecognitionDisclosure())
    }
}
