package ai.trustshield.android.input.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the pure-Kotlin Call Shield session core (B05, issue #37).
 *
 * Nothing here touches `android.*`: the class under test links no Android type at runtime,
 * so these tests run on a plain JVM. `AndroidSpeechRecognizerHandle` is **not** covered -
 * it is UNMEASURED and cannot be constructed off-device (android.jar bodies only throw).
 */
class CallSpeechSessionTest {

    @Test
    fun onDeviceCapabilitySelectsOnlyOnDeviceRecognizer() {
        val report = SpeechCapabilityReport(
            status = SpeechStatus.OnDeviceReady,
            languageTag = SpeechLanguage.HI_IN,
            offlineCapable = true,
            cloudMayBeUsed = false,
            degraded = false,
            reasons = listOf(CapabilityReason.OnDeviceNotMeasured),
        )

        assertEquals(SpeechRecognitionMode.OnDevice, recognitionModeFor(report))
    }

    @Test
    fun cloudCapableReportSelectsExplicitPossiblyNetworkRecognizer() {
        val report = SpeechCapabilityReport(
            status = SpeechStatus.CloudOnly,
            languageTag = SpeechLanguage.EN_IN,
            offlineCapable = false,
            cloudMayBeUsed = true,
            degraded = true,
            reasons = listOf(CapabilityReason.DefaultRecognizerMayUseNetwork),
        )

        assertEquals(SpeechRecognitionMode.DefaultMayUseNetwork, recognitionModeFor(report))
    }

    @Test
    fun unavailableCapabilityCannotCreateARecognizer() {
        val report = SpeechCapabilityReport(
            status = SpeechStatus.Unavailable,
            languageTag = "fr-FR",
            offlineCapable = false,
            cloudMayBeUsed = false,
            degraded = true,
            reasons = listOf(CapabilityReason.LanguageNotSelectable),
        )

        assertNull(recognitionModeFor(report))
    }

    // ------------------------------------------------------------------ fakes

    private class MutableClock(var now: Long = 1_000L) {
        fun advance(ms: Long) {
            now += ms
        }
    }

    private class FakeHandle : RecognizerHandle {
        var starts = 0
        var destroys = 0
        override fun start() {
            starts++
        }

        override fun destroy() {
            destroys++
        }
    }

    private fun listeningSession(
        clock: () -> Long = { 1_000L },
        windowMs: Long = CallSpeechSession.DEFAULT_WINDOW_MS,
        maxUtterances: Int = CallSpeechSession.DEFAULT_MAX_UTTERANCES,
        lowConfidenceThreshold: Float = CallSpeechSession.DEFAULT_LOW_CONFIDENCE,
        maxTextChars: Int = CallSpeechSession.DEFAULT_MAX_TEXT_CHARS,
        handle: FakeHandle = FakeHandle(),
    ): Pair<CallSpeechSession, FakeHandle> {
        val session = CallSpeechSession(
            clock = clock,
            windowMs = windowMs,
            maxUtterances = maxUtterances,
            consentGranted = true,
            lowConfidenceThreshold = lowConfidenceThreshold,
            maxTextChars = maxTextChars,
        )
        assertEquals(StartResult.Started, session.start(handle))
        session.onReadyForSpeech()
        assertEquals(SpeechSessionState.Listening, session.state)
        return session to handle
    }

    // ------------------------------------------------------------------ start / consent

    @Test
    fun startWithoutConsentIsRefusedAndNeverAttachesTheRecogniser() {
        val handle = FakeHandle()
        val session = CallSpeechSession(clock = { 1_000L }, consentGranted = false)

        val result = session.start(handle)

        assertEquals(StartResult.Refused(RefusalReason.NO_CONSENT), result)
        assertEquals(SpeechSessionState.Idle, session.state)
        assertEquals(0, handle.starts)
        assertTrue(session.transcript().isEmpty())
    }

    @Test
    fun startWithConsentReachesStartingThenListening() {
        val handle = FakeHandle()
        val session = CallSpeechSession(clock = { 1_000L }, consentGranted = true)

        assertEquals(StartResult.Started, session.start(handle))
        assertEquals(1, handle.starts)
        assertEquals(SpeechSessionState.Starting, session.state)

        session.onReadyForSpeech()
        assertEquals(SpeechSessionState.Listening, session.state)
    }

    @Test
    fun startTwiceWhileRunningIsRefused() {
        val (session, handle) = listeningSession()

        val again = session.start(FakeHandle())

        assertEquals(StartResult.Refused(RefusalReason.ALREADY_RUNNING), again)
        assertEquals(1, handle.starts)
    }

    // ------------------------------------------------------------------ bounding

    @Test
    fun threeHundredUtterancesAreBoundedTo200AndKeepTheNewest() {
        val clock = MutableClock()
        val (session, _) = listeningSession(clock = { clock.now })

        repeat(300) { i ->
            session.appendUtterance("word $i")
            clock.advance(100L) // 30 s of speech: well inside the 120 s window
        }

        val transcript = session.transcript()
        assertEquals(CallSpeechSession.DEFAULT_MAX_UTTERANCES, transcript.size)
        assertEquals("word 100", transcript.first().text)
        assertEquals("word 299", transcript.last().text)
    }

    @Test
    fun evictionIsDrivenByTheInjectedClockNotByInsertOrder() {
        val clock = MutableClock()
        val (session, _) = listeningSession(clock = { clock.now })

        session.appendUtterance("early")
        clock.advance(60_000L)
        session.appendUtterance("middle")
        clock.advance(61_000L) // 121 s after "early", 61 s after "middle"
        session.appendUtterance("late")

        val texts = session.transcript().map { it.text }
        assertEquals(listOf("middle", "late"), texts)
    }

    @Test
    fun aTwoHourCallKeepsTheTranscriptBounded() {
        val clock = MutableClock()
        val (session, _) = listeningSession(clock = { clock.now })

        // 7,200 final results: one every second for two hours.
        repeat(7_200) { i ->
            session.appendUtterance("utterance $i")
            clock.advance(1_000L)
        }

        val transcript = session.transcript()
        assertTrue("transcript must stay capped", transcript.size <= CallSpeechSession.DEFAULT_MAX_UTTERANCES)
        // Everything retained is inside the 120 s window, measured from the newest utterance.
        val newest = transcript.last().tsMs
        for (u in transcript) {
            assertTrue(u.tsMs >= newest - CallSpeechSession.DEFAULT_WINDOW_MS)
        }
        // Memory bound is arithmetic, not a feeling: <= 200 utterances x 512 chars.
        val chars = transcript.sumOf { it.text.length }
        assertTrue(chars <= CallSpeechSession.DEFAULT_MAX_UTTERANCES * CallSpeechSession.DEFAULT_MAX_TEXT_CHARS)
    }

    @Test
    fun utteranceTextIsCappedWithAPrefixCutSoQuotesStaySubstrings() {
        val (session, _) = listeningSession(maxTextChars = 10)
        val long = "KYC band ho jayega aaj raat"

        session.appendUtterance(long)

        val stored = session.transcript().single().text
        assertEquals(long.substring(0, 10), stored)
        assertTrue(long.startsWith(stored))
    }

    @Test
    fun transcriptReturnsACopyThatLaterAppendsCannotChange() {
        val (session, _) = listeningSession()
        session.appendUtterance("Rs 5000 credited")

        val first = session.transcript()
        session.appendUtterance("galti se paise bheje, wapas bhejo")

        // The earlier list is frozen evidence, not a live view of the session.
        assertEquals(1, first.size)
        assertEquals(2, session.transcript().size)
        assertEquals("Rs 5000 credited", first.single().text)
    }

    // ------------------------------------------------------------------ stop / teardown

    @Test
    fun stopClearsTheTranscriptAndDestroysTheRecogniserExactlyOnce() {
        val (session, handle) = listeningSession()
        session.appendUtterance("jo OTP aaya hai woh batayein")
        assertEquals(1, session.transcript().size)

        session.stop()

        assertEquals(SpeechSessionState.Stopped, session.state)
        assertTrue(session.transcript().isEmpty())
        assertEquals(1, handle.destroys)
    }

    @Test
    fun doubleStopIsSafeAndNeverDestroysTwice() {
        val (session, handle) = listeningSession()

        session.stop()
        session.stop()
        session.stop()

        assertEquals(SpeechSessionState.Stopped, session.state)
        assertEquals(1, handle.destroys)
    }

    @Test
    fun callbacksAfterStopAreIgnored() {
        val (session, handle) = listeningSession()
        session.stop()

        assertEquals(SpeechEvent.Ignored, session.onPartialResult("aarha hai", 0.9f))
        assertEquals(SpeechEvent.Ignored, session.onFinalResult("police station bulao", 0.9f))
        assertEquals(SpeechEvent.Ignored, session.appendUtterance("text", 0.9f))
        assertEquals(SpeechEvent.Ignored, session.onRecognizerError(RecognizerErrorCode.NO_MATCH))
        assertEquals(SpeechEvent.Ignored, session.onRecognizerError(RecognizerErrorCode.RECOGNIZER_BUSY))

        assertTrue(session.transcript().isEmpty())
        assertEquals(SpeechSessionState.Stopped, session.state)
        assertEquals(1, handle.destroys)
        assertEquals(0, session.emptyResults)
        assertEquals(0, session.noSpeechCount)
    }

    @Test
    fun callbacksAfterATerminalErrorAreIgnored() {
        val (session, handle) = listeningSession()

        session.onRecognizerError(RecognizerErrorCode.INSUFFICIENT_PERMISSIONS)
        assertEquals(SpeechEvent.Ignored, session.onFinalResult("anything", 0.9f))

        assertTrue(session.transcript().isEmpty())
        assertEquals(1, handle.destroys)
    }

    // ------------------------------------------------------------------ errors vs silence

    @Test
    fun recogniserBusyIsATerminalRetryableError() {
        val (session, handle) = listeningSession()

        val event = session.onRecognizerError(RecognizerErrorCode.RECOGNIZER_BUSY)

        assertEquals(SpeechEvent.Failed(ErrorReason.BUSY, retryable = true, code = 8), event)
        val state = session.state as SpeechSessionState.Error
        assertEquals(ErrorReason.BUSY, state.reason)
        assertTrue(state.retryable)
        assertEquals(1, handle.destroys)
    }

    @Test
    fun noMatchAndSpeechTimeoutAreSilenceNotErrorsAndNotStages() {
        val (session, _) = listeningSession()

        val noMatch = session.onRecognizerError(RecognizerErrorCode.NO_MATCH)
        val silence = session.onRecognizerError(RecognizerErrorCode.SPEECH_TIMEOUT)

        assertEquals(SpeechEvent.NoSpeech(NoSpeechReason.NO_MATCH), noMatch)
        assertEquals(SpeechEvent.NoSpeech(NoSpeechReason.SILENCE), silence)
        // Crucially: still listening, not Error, and no stage/verdict surface exists at all.
        assertEquals(SpeechSessionState.Listening, session.state)
        assertEquals(2, session.noSpeechCount)
        assertTrue(session.transcript().isEmpty())
    }

    @Test
    fun silenceCallbacksDoNotDisturbAnExistingTranscript() {
        val (session, _) = listeningSession()
        session.appendUtterance("aapka account block ho jayega")

        session.onRecognizerError(RecognizerErrorCode.NO_MATCH)

        assertEquals(SpeechSessionState.Listening, session.state)
        assertEquals(1, session.transcript().size)
    }

    @Test
    fun insufficientPermissionsIsTerminalAndNotRetryable() {
        val (session, _) = listeningSession()

        val event = session.onRecognizerError(RecognizerErrorCode.INSUFFICIENT_PERMISSIONS)

        assertEquals(SpeechEvent.Failed(ErrorReason.NO_PERMISSION, retryable = false, code = 9), event)
        val state = session.state as SpeechSessionState.Error
        assertEquals(ErrorReason.NO_PERMISSION, state.reason)
        assertFalse(state.retryable)
    }

    @Test
    fun networkErrorIsTerminalAndRetryable() {
        val (session, _) = listeningSession()

        val event = session.onRecognizerError(RecognizerErrorCode.NETWORK)

        assertEquals(SpeechEvent.Failed(ErrorReason.NETWORK, retryable = true, code = 2), event)
        assertEquals(ErrorReason.NETWORK, (session.state as SpeechSessionState.Error).reason)
    }

    @Test
    fun unavailableRecognitionLanguageCanRetryWithFallback() {
        val (session, _) = listeningSession()

        val event = session.onRecognizerError(RecognizerErrorCode.LANGUAGE_UNAVAILABLE)

        assertEquals(
            SpeechEvent.Failed(ErrorReason.NETWORK, retryable = true, code = RecognizerErrorCode.LANGUAGE_UNAVAILABLE),
            event,
        )
    }

    @Test
    fun anUnknownErrorCodeFailsClosedRatherThanSilently() {
        val (session, _) = listeningSession()

        val event = session.onRecognizerError(4242)

        assertEquals(SpeechEvent.Failed(ErrorReason.UNKNOWN, retryable = true, code = 4242), event)
        assertTrue(session.state is SpeechSessionState.Error)
    }

    // ------------------------------------------------------------------ noise

    @Test
    fun blankFinalResultsAreCountedAsNoiseAndNeverStored() {
        val (session, _) = listeningSession()

        assertEquals(SpeechEvent.EmptyResult, session.onFinalResult("   ", 0.1f))
        assertEquals(SpeechEvent.EmptyResult, session.onFinalResult(null, null))
        assertEquals(SpeechEvent.EmptyResult, session.onFinalResult("\n\t", null))

        assertEquals(3, session.emptyResults)
        assertTrue(session.transcript().isEmpty())
        assertEquals(SpeechSessionState.Listening, session.state)
    }

    @Test
    fun blankPartialsAreDroppedWithoutBeingCountedOrStored() {
        val (session, _) = listeningSession()

        assertEquals(SpeechEvent.Ignored, session.onPartialResult("  ", 0.3f))

        assertEquals(0, session.emptyResults)
        assertTrue(session.transcript().isEmpty())
    }

    // ------------------------------------------------------------------ confidence

    @Test
    fun lowConfidenceIsFlaggedButNeverEscalated() {
        val (session, _) = listeningSession(lowConfidenceThreshold = 0.6f)

        session.onFinalResult("ED officer bol raha hoon", 0.2f)
        session.onFinalResult("clear words", 0.95f)
        session.onFinalResult("no score given", null)

        val transcript = session.transcript()
        assertTrue(transcript[0].lowConfidence)
        assertFalse(transcript[1].lowConfidence)
        assertFalse("unknown confidence is unmeasured, not confident", transcript[2].lowConfidence)
        assertNull(transcript[2].confidence)
        // The session stays in Listening: one misheard word changes nothing but a flag.
        assertEquals(SpeechSessionState.Listening, session.state)
    }

    @Test
    fun appendUtteranceRecomputesTheLowConfidenceFlag() {
        val (session, _) = listeningSession(lowConfidenceThreshold = 0.6f)

        session.appendUtterance(
            Utterance(
                text = "forged flag",
                tsMs = 0L,
                confidence = 0.99f,
                isFinal = true,
                lowConfidence = true, // caller lies; the session recomputes
            ),
        )

        assertFalse(session.transcript().single().lowConfidence)
    }

    // ------------------------------------------------------------------ partial results

    @Test
    fun partialResultsAreStoredAndForwardedAsNotFinal() {
        val (session, _) = listeningSession()

        val event = session.onPartialResult("kyc update karna hai", 0.7f)

        assertTrue(event is SpeechEvent.Accepted)
        val u = session.transcript().single()
        assertFalse(u.isFinal)
        assertEquals("kyc update karna hai", u.text)
    }

    @Test
    fun ttsOutputIsNotStoredOrForwardedAsCallerSpeech() {
        val session = CallSpeechSession(consentGranted = true)
        session.start(FakeHandle())
        session.onReadyForSpeech()
        session.setInputSuppressed(true)

        assertEquals(SpeechEvent.Ignored, session.onFinalResult("TrustShield warning", 0.9f))
        assertEquals(SpeechEvent.Ignored, session.onPartialResult("TrustShield warning", 0.9f))
        assertTrue(session.transcript().isEmpty())

        session.setInputSuppressed(false)
        assertTrue(session.onFinalResult("caller words", 0.9f) is SpeechEvent.Accepted)
    }

    @Test
    fun aResultDuringStartingPromotesTheSessionToListening() {
        val session = CallSpeechSession(clock = { 1_000L }, consentGranted = true)
        session.start(FakeHandle())
        assertEquals(SpeechSessionState.Starting, session.state)

        session.onFinalResult("hello", 0.9f)

        assertEquals(SpeechSessionState.Listening, session.state)
    }

    // ------------------------------------------------------------------ sink seam

    @Test
    fun theSinkReceivesEveryAcceptedUtterance() {
        val (session, _) = listeningSession()
        val seen = mutableListOf<String>()
        session.setUtteranceSink { u -> seen.add(u.text) }

        session.onFinalResult("pehla", 0.9f)
        session.onPartialResult("doosra", 0.9f)
        session.onFinalResult("   ", 0.1f) // noise is not forwarded

        assertEquals(listOf("pehla", "doosra"), seen)
    }

    @Test
    fun aThrowingSinkCannotCorruptTheSession() {
        val (session, _) = listeningSession()
        session.setUtteranceSink { throw IllegalStateException("sink blew up") }

        val event = session.onFinalResult("otp bata do", 0.9f)

        assertTrue(event is SpeechEvent.Accepted)
        assertEquals(1, session.transcript().size)
        assertEquals(SpeechSessionState.Listening, session.state)
    }

    @Test
    fun detachingTheSinkIsHarmless() {
        val (session, _) = listeningSession()
        session.setUtteranceSink { }
        session.setUtteranceSink(null)

        assertTrue(session.onFinalResult("still fine", 0.9f) is SpeechEvent.Accepted)
    }

    // ------------------------------------------------------------------ rotation

    @Test
    fun snapshotRestoreKeepsTheTranscriptButNeverResumesListening() {
        val clock = MutableClock()
        val (session, _) = listeningSession(clock = { clock.now })
        session.onFinalResult("aapke naam par FIR hai", 0.9f)
        session.onPartialResult("settlement ke liye", 0.8f)
        session.onRecognizerError(RecognizerErrorCode.NO_MATCH)

        val snapshot = session.snapshot()
        val stateBefore = session.state

        val restored = CallSpeechSession(clock = { clock.now }, consentGranted = true)
        assertTrue(restored.restore(snapshot))

        // The recogniser died with the old activity: we must not claim to be listening.
        assertEquals(SpeechSessionState.Idle, restored.state)
        assertEquals(2, restored.transcript().size)
        assertEquals(1, restored.noSpeechCount)
        assertEquals("aapke naam par FIR hai", restored.transcript()[0].text)
        // The snapshot itself is a copy, so the old session is unaffected.
        assertEquals(stateBefore, session.state)
    }

    @Test
    fun aRestoredSessionCanReattachAndKeepTheTranscriptWhenAsked() {
        val clock = MutableClock()
        val (session, _) = listeningSession(clock = { clock.now })
        session.onFinalResult("pichhla vaakya", 0.9f)

        val restored = CallSpeechSession(clock = { clock.now }, consentGranted = true)
        restored.restore(session.snapshot())
        val handle = FakeHandle()

        assertEquals(StartResult.Started, restored.start(handle, preserveTranscript = true))

        assertEquals(SpeechSessionState.Starting, restored.state)
        assertEquals(1, restored.transcript().size)
        assertEquals(1, handle.starts)
    }

    @Test
    fun aPlainStartClearsTheRestoredTranscript() {
        val clock = MutableClock()
        val (session, _) = listeningSession(clock = { clock.now })
        session.onFinalResult("purana", 0.9f)

        val restored = CallSpeechSession(clock = { clock.now }, consentGranted = true)
        restored.restore(session.snapshot())
        restored.start(FakeHandle()) // a brand new call, not a resume

        assertTrue(restored.transcript().isEmpty())
    }

    @Test
    fun aStoppedSnapshotNeverRestoresIntoListening() {
        val clock = MutableClock()
        val (session, _) = listeningSession(clock = { clock.now })
        session.onFinalResult("kuch bhi", 0.9f)
        session.stop()

        val snapshot = session.snapshot()
        assertEquals(SpeechSessionState.Stopped, snapshot.state)

        // Even a forged snapshot that carries text cannot resurrect a stopped session.
        val forged = snapshot.copy(utterances = listOf(Utterance("leaked", 1_000L, 0.9f, true, false)))
        val restored = CallSpeechSession(clock = { clock.now }, consentGranted = true)
        assertTrue(restored.restore(forged))

        assertEquals(SpeechSessionState.Stopped, restored.state)
        assertTrue(restored.transcript().isEmpty())
        assertEquals(0, restored.emptyResults)
        assertEquals(0, restored.noSpeechCount)
    }

    @Test
    fun anErrorSnapshotRestoresAsThatError() {
        val (session, _) = listeningSession()
        session.onRecognizerError(RecognizerErrorCode.INSUFFICIENT_PERMISSIONS)

        val restored = CallSpeechSession(clock = { 1_000L }, consentGranted = true)
        assertTrue(restored.restore(session.snapshot()))

        val state = restored.state as SpeechSessionState.Error
        assertEquals(ErrorReason.NO_PERMISSION, state.reason)
        assertFalse(state.retryable)
    }

    @Test
    fun restoreRefusesToOverwriteALiveSession() {
        val (session, _) = listeningSession()
        val other = CallSpeechSession(clock = { 1_000L }, consentGranted = true)
        other.start(FakeHandle())

        assertFalse(other.restore(session.snapshot()))
        assertEquals(SpeechSessionState.Starting, other.state)
    }

    // ------------------------------------------------------------------ "text only" by construction

    @Test
    fun noFieldAnywhereHoldsAudioBytes() {
        val audioishNames = listOf("pcm", "audio", "samples", "raw", "wav", "buffer", "bytes", "frame")
        val audioishTypes = listOf(
            "ByteArray", "ShortArray", "FloatArray", "IntArray", "CharArray",
            "ByteBuffer", "ShortBuffer", "FloatBuffer",
            "AudioRecord", "AudioFormat", "File", "OutputStream", "InputStream",
        )

        for (clazz in listOf(CallSpeechSession::class.java, Utterance::class.java, Snapshot::class.java)) {
            for (field in clazz.declaredFields) {
                assertFalse(
                    "${clazz.simpleName}.${field.name} is an array - audio could hide there",
                    field.type.isArray,
                )
                val typeName = field.type.simpleName
                assertFalse(
                    "${clazz.simpleName}.${field.name} has audio-ish type $typeName",
                    audioishTypes.any { it == typeName },
                )
                val fieldName = field.name.lowercase()
                assertFalse(
                    "${clazz.simpleName}.${field.name} has an audio-ish name",
                    audioishNames.any { fieldName.contains(it) },
                )
            }
        }
    }

    @Test
    fun thePublicSurfaceExposesNoAudioEntryPoint() {
        // No method may accept byte/short/float PCM and no method returns one.
        val forbidden = listOf("ByteArray", "ShortArray", "FloatArray", "ByteBuffer")
        for (m in CallSpeechSession::class.java.declaredMethods) {
            assertFalse(
                "method ${m.name} takes PCM",
                m.parameterTypes.any { it.simpleName in forbidden || it.isArray },
            )
            assertFalse("method ${m.name} returns PCM", m.returnType.simpleName in forbidden)
        }
    }
}
