/*
 * P3-12 - tests for the Call Shield readiness decision (issue #91).
 *
 * Pure JVM. No android.* type is constructed, no Compose, no clock, no I/O, no network: the whole
 * decision is a pure function of (consent, B04 report, microphone, interruption, phase).
 *
 * Every capability report used here is built through the REAL B04 rules
 * (`SpeechCapabilityRules.evaluate`), so a test cannot invent a status the product never emits.
 * Fixture lines are hand-written; nothing is model output and no accuracy is claimed.
 *
 * What is asserted, in the order the package asked for it:
 *  - the full matrix of 6 capability statuses x 4 consent states x 3 microphone states, against a
 *    hand-written expectation table (72 cells), plus the cross-cutting invariants in every cell;
 *  - a ready on-device phone is READY_ON_DEVICE and the copy says the offline path was NOT measured
 *    on this device, so the OS report is never upgraded into a measurement;
 *  - a network-capable phone is READY_MAY_USE_NETWORK, its privacy mode MAY_LEAVE_DEVICE, and the
 *    words "on-device", "private" and "offline" appear nowhere in that report;
 *  - MissingService / Unavailable / OnDeviceUnavailable fall back to the demo script with the live
 *    session refused, and the copy names the device limit;
 *  - a permanently denied microphone reads differently from a plain denial AND offers a different
 *    action (app settings vs the system prompt);
 *  - every Interruption value forces INTERRUPTED with a retry action even when consent, permission
 *    and capability are all ready, and the reason line differs per value;
 *  - STOPPED offers a restart and never listens;
 *  - isListening is true ONLY for (READY_*, phase LISTENING) - consent alone never makes it true;
 *  - a forbidden-claim sweep over every report in both languages, including the "nothing is being
 *    monitored" rule for every non-listening report;
 *  - no ReadinessState is dead (every value is produced by at least one input);
 *  - determinism, bilingual completeness (non-blank, Hinglish != English) and no digits/OTP/phone
 *    or UPI-shaped text in any copy line;
 *  - the fromState bridge reads the existing CallUiState without inventing consent.
 *
 * The copy is authored for the app and is NOT reviewed by a native speaker. No device or emulator
 * run happened in this package; nothing here is a measured offline or device result.
 */
package ai.trustshield.android.ui.call

import ai.trustshield.android.input.speech.CapabilityReason
import ai.trustshield.android.input.speech.SpeechCapabilityReport
import ai.trustshield.android.input.speech.SpeechCapabilityRules
import ai.trustshield.android.input.speech.SpeechConsentState
import ai.trustshield.android.input.speech.SpeechEnvironment
import ai.trustshield.android.input.speech.SpeechLanguage
import ai.trustshield.android.input.speech.SpeechStatus
import ai.trustshield.android.input.speech.MicPermission as SpeechMic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CallShieldReadinessTest {

    // ------------------------------------------------------------------ fixtures

    /** Built through the real rules, so the status is whatever B04 would really report. */
    private fun capabilityReport(
        apiLevel: Int = 33,
        service: Boolean = true,
        onDevice: Boolean = true,
        model: Boolean = true,
        mic: SpeechMic = SpeechMic.Granted,
    ): SpeechCapabilityReport = SpeechCapabilityRules.evaluate(
        SpeechEnvironment(
            apiLevel = apiLevel,
            recognizerServiceAvailable = service,
            onDeviceAvailable = onDevice,
            hiInModelDownloaded = model,
            micPermission = mic,
        ),
        SpeechLanguage.HI_IN,
    )

    private val unavailableReport = capabilityReport(apiLevel = 20)
    private val missingServiceReport = capabilityReport(service = false)
    private val onDeviceReport = capabilityReport()
    private val modelMissingReport = capabilityReport(model = false)
    private val cloudOnlyReport = capabilityReport(apiLevel = 26, onDevice = false, model = false)
    private val noMicReport = capabilityReport(mic = SpeechMic.Denied)
    private val permanentlyDeniedReport = capabilityReport(mic = SpeechMic.PermanentlyDenied)

    private data class Fixture(val status: SpeechStatus, val report: SpeechCapabilityReport)

    private val fixtures = listOf(
        Fixture(SpeechStatus.Unavailable, unavailableReport),
        Fixture(SpeechStatus.MissingService, missingServiceReport),
        Fixture(SpeechStatus.OnDeviceReady, onDeviceReport),
        Fixture(SpeechStatus.OnDeviceUnavailable, modelMissingReport),
        Fixture(SpeechStatus.CloudOnly, cloudOnlyReport),
        Fixture(SpeechStatus.NoMicPermission, noMicReport),
    )

    private fun evaluate(
        report: SpeechCapabilityReport,
        consent: SpeechConsentState = SpeechConsentState.Granted,
        mic: MicPermission = MicPermission.GRANTED,
        interruption: Interruption = Interruption.None,
        demoAvailable: Boolean = true,
        phase: SessionPhase = SessionPhase.IDLE,
        demoRequested: Boolean = false,
    ): ReadinessReport = CallShieldReadiness.evaluate(
        ReadinessInput(
            report = report,
            consent = consent,
            micPermission = mic,
            interruption = interruption,
            demoAvailable = demoAvailable,
            phase = phase,
            demoRequested = demoRequested,
        )
    )

    /** Every line of a report, both languages. */
    private fun ReadinessReport.copyLines(): List<Pair<String, String>> =
        allCopy.map { it.hinglish to it.english }

    private fun ReadinessReport.allText(): String =
        copyLines().joinToString(" ") { (hinglish, english) -> hinglish + " " + english }

    private fun ReadinessReport.englishText(): String =
        allCopy.joinToString(" ") { it.english }

    private fun ReadinessReport.hinglishText(): String =
        allCopy.joinToString(" ") { it.hinglish }

    // ------------------------------------------------------------------ expectation table

    /**
     * The hand-written expectation for one matrix cell. Deliberately literal: it does NOT call into
     * the production decision, so a change in precedence has to be re-argued here.
     *
     * Note the two cases that look odd but are honest:
     *  - a `NoMicPermission` report with a GRANTED live microphone is a stale probe (the user has
     *    just allowed it), so the live value wins and the report's capability flags decide the path;
     *  - a denial only becomes "permanently denied" when B04 says so, because `MicPermission` in the
     *    screen vocabulary cannot express the difference on its own.
     */
    private fun expectedFor(
        status: SpeechStatus,
        consent: SpeechConsentState,
        mic: MicPermission,
    ): ReadinessState = when (status) {
        // No live path exists at all: asking for consent first would be a dead end.
        SpeechStatus.Unavailable, SpeechStatus.MissingService -> ReadinessState.RECOGNIZER_UNAVAILABLE

        SpeechStatus.OnDeviceReady,
        SpeechStatus.CloudOnly,
        SpeechStatus.OnDeviceUnavailable,
        SpeechStatus.NoMicPermission,
        -> when (consent) {
            SpeechConsentState.Granted -> when (mic) {
                MicPermission.UNKNOWN -> ReadinessState.MIC_PERMISSION_NOT_ASKED
                MicPermission.DENIED -> ReadinessState.MIC_PERMISSION_DENIED
                MicPermission.GRANTED -> when (status) {
                    SpeechStatus.OnDeviceReady -> ReadinessState.READY_ON_DEVICE
                    SpeechStatus.NoMicPermission -> ReadinessState.READY_ON_DEVICE
                    SpeechStatus.CloudOnly -> ReadinessState.READY_MAY_USE_NETWORK
                    SpeechStatus.OnDeviceUnavailable -> ReadinessState.ON_DEVICE_MODEL_MISSING
                    SpeechStatus.Unavailable, SpeechStatus.MissingService ->
                        ReadinessState.RECOGNIZER_UNAVAILABLE
                }
            }

            SpeechConsentState.NotAsked,
            SpeechConsentState.Denied,
            SpeechConsentState.Withdrawn,
            -> ReadinessState.NEEDS_CONSENT
        }
    }

    // ------------------------------------------------------------------ the matrix

    @Test
    fun fixturesReallyCarryTheSixCapabilityStatuses() {
        for (fixture in fixtures) {
            assertEquals(fixture.report.reasons.toString(), fixture.status, fixture.report.status)
        }
        // Guard rails for the flags the decision leans on.
        assertTrue(onDeviceReport.offlineCapable)
        assertFalse(onDeviceReport.cloudMayBeUsed)
        assertTrue(cloudOnlyReport.cloudMayBeUsed)
        assertFalse(cloudOnlyReport.offlineCapable)
        assertTrue(modelMissingReport.has(CapabilityReason.HiInModelMissing))
        assertTrue(noMicReport.offlineCapable)
        assertFalse(unavailableReport.sessionPossible)
        assertFalse(missingServiceReport.sessionPossible)
    }

    @Test
    fun matrixOfEveryStatusConsentAndMicrophone() {
        var cells = 0
        for (fixture in fixtures) {
            for (consent in SpeechConsentState.entries) {
                for (mic in MicPermission.entries) {
                    val label = "${fixture.status}/$consent/$mic"
                    val report = evaluate(fixture.report, consent = consent, mic = mic)
                    assertEquals(label, expectedFor(fixture.status, consent, mic), report.state)
                    assertEquals(label, report.state.canStartLiveSession, report.canStartLiveSession)
                    assertTrue("$label: the demo script is always offered", report.canUseDemoScript)
                    assertFalse("$label: nothing may claim to be listening", report.isListening)
                    cells++
                }
            }
        }
        assertEquals("6 statuses x 4 consent states x 3 microphone states", 72, cells)
    }

    @Test
    fun noConsentNeverOpensTheMicrophone() {
        for (consent in listOf(
            SpeechConsentState.NotAsked,
            SpeechConsentState.Denied,
            SpeechConsentState.Withdrawn,
        )) {
            val report = evaluate(onDeviceReport, consent = consent, phase = SessionPhase.LISTENING)
            assertEquals(ReadinessState.NEEDS_CONSENT, report.state)
            assertFalse(report.canStartLiveSession)
            assertFalse("consent is not a listening state", report.isListening)
            assertTrue(report.actions.contains(ReadinessCopy.giveConsent))
        }
    }

    // ------------------------------------------------------------------ ready paths

    @Test
    fun readyOnDeviceIsHonestAboutTheUnmeasuredOfflinePath() {
        val report = evaluate(onDeviceReport)
        assertEquals(ReadinessState.READY_ON_DEVICE, report.state)
        assertTrue(report.canStartLiveSession)
        assertTrue(report.canUseDemoScript)
        assertEquals(RecognitionDisclosure.ON_DEVICE_UNVERIFIED, report.disclosure)
        assertEquals(SpeechPrivacyMode.ON_DEVICE_CLAIMED_BY_OS, report.privacyMode)
        assertFalse(report.isListening)

        // Says what the OS reported ...
        assertTrue(report.hinglishText().contains("OS ne bataya"))
        assertTrue(report.englishText().contains("OS reports"))
        // ... and that it was not measured here: capability is not a measurement.
        assertTrue(report.hinglishText().contains("naapa nahi gaya"))
        assertTrue(report.englishText().contains("has not been measured"))
        // No promise of a verified offline run.
        val text = report.allText().lowercase()
        assertFalse(text.contains("offline verified"))
        assertFalse(text.contains("verified offline"))
        assertFalse(text.contains("fully offline"))
        assertTrue(report.actions.contains(ReadinessCopy.startSession))
        assertTrue(report.limitations.contains(ReadinessCopy.onDeviceUnmeasured))
        assertTrue(report.limitations.contains(ReadinessCopy.scope))
        assertTrue(report.limitations.contains(ReadinessCopy.noCloneCheck))
    }

    @Test
    fun cloudCapableIsReadyButSaysAudioMayLeaveTheDevice() {
        val report = evaluate(cloudOnlyReport)
        assertEquals(ReadinessState.READY_MAY_USE_NETWORK, report.state)
        assertTrue(report.canStartLiveSession)
        assertEquals(RecognitionDisclosure.MAY_USE_NETWORK, report.disclosure)
        assertEquals(SpeechPrivacyMode.MAY_LEAVE_DEVICE, report.privacyMode)

        assertTrue(report.hinglishText().contains("kuch awaaz device se bahar jaa sakti hai"))
        assertTrue(report.englishText().contains("may leave the device"))

        // A network path is never dressed up as private, on-device or offline.
        val text = report.allText().lowercase()
        assertFalse(text, text.contains("on-device"))
        assertFalse(text, text.contains("on device"))
        assertFalse(text, text.contains("private"))
        assertFalse(text, text.contains("offline"))
        assertTrue(report.limitations.contains(ReadinessCopy.mayLeaveDevice))
    }

    @Test
    fun onDeviceModelMissingIsADeviceLimitWithTheDemoFallback() {
        val report = evaluate(modelMissingReport)
        assertEquals(ReadinessState.ON_DEVICE_MODEL_MISSING, report.state)
        assertFalse(report.canStartLiveSession)
        assertTrue(report.canUseDemoScript)
        assertTrue(report.actions.contains(ReadinessCopy.useDemoScript))
        assertTrue(report.hinglishText().contains("Hindi"))
        assertTrue(report.hinglishText().contains("phone ki limit"))
        assertTrue(report.englishText().contains("limit of this phone"))
        assertTrue(report.limitations.contains(ReadinessCopy.mayLeaveDevice))
        assertNotEquals(ReadinessState.READY_ON_DEVICE, report.state)
    }

    @Test
    fun missingOrUnsupportedSpeechServiceFallsBackToTheDemo() {
        for (fixture in listOf(
            Fixture(SpeechStatus.MissingService, missingServiceReport),
            Fixture(SpeechStatus.Unavailable, unavailableReport),
        )) {
            val report = evaluate(fixture.report)
            assertEquals(fixture.status.toString(), ReadinessState.RECOGNIZER_UNAVAILABLE, report.state)
            assertFalse(report.canStartLiveSession)
            assertTrue(report.canUseDemoScript)
            assertTrue(report.actions.contains(ReadinessCopy.useDemoScript))
            assertEquals(RecognitionDisclosure.UNAVAILABLE, report.disclosure)
            assertEquals(SpeechPrivacyMode.UNKNOWN, report.privacyMode)
            // Names it as a device limit and never as an app failure.
            assertTrue(report.hinglishText().contains("phone ki limit"))
            assertTrue(report.englishText().contains("limit of the phone"))
        }
    }

    @Test
    fun microphoneNotAskedIsItsOwnState() {
        val report = evaluate(onDeviceReport, mic = MicPermission.UNKNOWN)
        assertEquals(ReadinessState.MIC_PERMISSION_NOT_ASKED, report.state)
        assertFalse(report.canStartLiveSession)
        assertTrue(report.actions.contains(ReadinessCopy.allowMicPermission))
        assertFalse(report.hinglishText().contains("mana"))
    }

    @Test
    fun permanentlyDeniedDiffersFromPlainDenialInCopyAndAction() {
        val denied = evaluate(noMicReport, mic = MicPermission.DENIED)
        val permanent = evaluate(permanentlyDeniedReport, mic = MicPermission.DENIED)

        assertEquals(ReadinessState.MIC_PERMISSION_DENIED, denied.state)
        assertEquals(ReadinessState.MIC_PERMISSION_PERMANENTLY_DENIED, permanent.state)
        assertFalse(denied.canStartLiveSession)
        assertFalse(permanent.canStartLiveSession)

        // Different copy ...
        assertNotEquals(denied.headline, permanent.headline)
        assertNotEquals(denied.body, permanent.body)
        // ... and a different route to fix it: the system prompt cannot come back once it is
        // permanently blocked, so that state points at the app's settings page instead.
        assertTrue(denied.actions.contains(ReadinessCopy.allowMicPermission))
        assertFalse(denied.actions.contains(ReadinessCopy.openAppSettings))
        assertTrue(permanent.actions.contains(ReadinessCopy.openAppSettings))
        assertFalse(permanent.actions.contains(ReadinessCopy.allowMicPermission))
        assertEquals(ReadinessState.MIC_PERMISSION_PERMANENTLY_DENIED, permanent.state)

        // Both deny copying: what was refused, that nothing was recorded, and how to fix it.
        for (report in listOf(denied, permanent)) {
            assertTrue(report.hinglishText().contains("record nahi hua"))
            assertTrue(report.englishText().contains("nothing was recorded"))
        }
        assertTrue(denied.hinglishText().contains("system permission"))
        assertFalse(denied.englishText().contains("Settings"))
        assertTrue(permanent.englishText().contains("Settings"))
    }

    // ------------------------------------------------------------------ interruption

    @Test
    fun everyInterruptionStopsASessionThatWasOtherwiseReady() {
        val reasons = mutableSetOf<String>()
        for (interruption in Interruption.entries.filter { it != Interruption.None }) {
            val report = evaluate(
                onDeviceReport,
                interruption = interruption,
                phase = SessionPhase.LISTENING,
            )
            assertEquals(interruption.toString(), ReadinessState.INTERRUPTED, report.state)
            assertFalse("$interruption must not stay startable", report.canStartLiveSession)
            assertFalse("$interruption must not claim listening", report.isListening)
            assertTrue(report.actions.contains(ReadinessCopy.retrySession))
            assertTrue(report.hinglishText().contains("session ruk gaya"))
            assertTrue(report.limitations.contains(ReadinessCopy.interruptedCleared))
            reasons += report.body.hinglish
        }
        assertEquals("each interruption gets its own reason line", 5, reasons.size)

        // The same interruption also overrides a cloud-ready phone.
        val cloudCut = evaluate(cloudOnlyReport, interruption = Interruption.AudioFocusLost)
        assertEquals(ReadinessState.INTERRUPTED, cloudCut.state)
        assertFalse(cloudCut.canStartLiveSession)
    }

    @Test
    fun noInterruptionIsNotAnInterruption() {
        val report = evaluate(onDeviceReport, interruption = Interruption.None)
        assertEquals(ReadinessState.READY_ON_DEVICE, report.state)
        assertTrue(report.canStartLiveSession)
        assertFalse(report.hinglishText().contains("session ruk gaya"))
    }

    @Test
    fun interruptedCopyCoversEveryTriggerIncludingTheNeutralLine() {
        for (interruption in Interruption.entries) {
            val line = ReadinessCopy.interruptionReason(interruption)
            assertTrue("$interruption needs Hinglish copy", line.hinglish.isNotBlank())
            assertTrue("$interruption needs English copy", line.english.isNotBlank())
            assertNotEquals(line.hinglish, line.english)
        }
    }

    // ------------------------------------------------------------------ stopped and demo

    @Test
    fun stoppedNeverListensAndOffersARestart() {
        val report = evaluate(onDeviceReport, phase = SessionPhase.STOPPED)
        assertEquals(ReadinessState.STOPPED, report.state)
        assertFalse(report.canStartLiveSession)
        assertFalse(report.isListening)
        assertTrue(report.actions.contains(ReadinessCopy.startNewSession))
        assertTrue(report.limitations.contains(ReadinessCopy.stoppedMicOff))
        assertTrue(report.hinglishText().contains("Mic band hai"))
    }

    @Test
    fun demoOnlyModeIsNotALiveSession() {
        val report = evaluate(onDeviceReport, demoRequested = true)
        assertEquals(ReadinessState.DEMO_ONLY, report.state)
        assertFalse(report.canStartLiveSession)
        assertFalse(report.isListening)
        assertTrue(report.canUseDemoScript)
        assertTrue(report.actions.contains(ReadinessCopy.useDemoScript))
        assertTrue(report.limitations.contains(ReadinessCopy.demoIsScripted))
        assertTrue(report.hinglishText().contains("live mic band hai"))
    }

    @Test
    fun demoUnavailableIsHonouredEverywhere() {
        for (fixture in fixtures) {
            for (consent in SpeechConsentState.entries) {
                for (mic in MicPermission.entries) {
                    val report = evaluate(fixture.report, consent = consent, mic = mic, demoAvailable = false)
                    assertFalse(report.canUseDemoScript)
                    assertFalse(report.actions.contains(ReadinessCopy.useDemoScript))
                }
            }
        }
        val interrupted = evaluate(onDeviceReport, interruption = Interruption.RecognizerError, demoAvailable = false)
        assertFalse(interrupted.canUseDemoScript)
        assertFalse(interrupted.actions.contains(ReadinessCopy.useDemoScript))
    }

    // ------------------------------------------------------------------ isListening

    @Test
    fun listeningRequiresAReadyStateAndTheListeningPhase() {
        // The only true combination.
        val live = evaluate(onDeviceReport, phase = SessionPhase.LISTENING)
        assertEquals(ReadinessState.READY_ON_DEVICE, live.state)
        assertTrue(live.isListening)

        val liveCloud = evaluate(cloudOnlyReport, phase = SessionPhase.LISTENING)
        assertTrue(liveCloud.isListening)

        // Same readiness without the phase: not listening.
        for (phase in listOf(SessionPhase.IDLE, SessionPhase.PAUSED, SessionPhase.STOPPED)) {
            assertFalse("$phase must not be listening", evaluate(onDeviceReport, phase = phase).isListening)
        }

        // An interruption flips it to false even mid-flight.
        for (interruption in Interruption.entries.filter { it != Interruption.None }) {
            assertFalse(
                evaluate(onDeviceReport, phase = SessionPhase.LISTENING, interruption = interruption).isListening
            )
        }

        // Nothing that is not ready may ever be listening, whatever the phase says.
        val notReady = listOf(
            evaluate(onDeviceReport, consent = SpeechConsentState.NotAsked, phase = SessionPhase.LISTENING),
            evaluate(onDeviceReport, mic = MicPermission.UNKNOWN, phase = SessionPhase.LISTENING),
            evaluate(onDeviceReport, mic = MicPermission.DENIED, phase = SessionPhase.LISTENING),
            evaluate(noMicReport, mic = MicPermission.DENIED, phase = SessionPhase.LISTENING),
            evaluate(modelMissingReport, phase = SessionPhase.LISTENING),
            evaluate(missingServiceReport, phase = SessionPhase.LISTENING),
            evaluate(unavailableReport, phase = SessionPhase.LISTENING),
            evaluate(onDeviceReport, demoRequested = true, phase = SessionPhase.LISTENING),
        )
        for (report in notReady) {
            assertFalse("${report.state} must not be listening", report.isListening)
            assertTrue(report.canStartLiveSession == report.state.canStartLiveSession)
        }
    }

    // ------------------------------------------------------------------ honesty sweep

    private val forbiddenClaims = listOf(
        "deepfake detect",
        "call recording",
        "cellular",
        "voip",
        "100%",
        "fully safe",
        "guaranteed",
        "guarantee",
        "bilkul safe",
        "pakka safe",
        "verified offline",
        "offline verified",
    )

    /** Present-continuous speech that would imply the microphone is open when it is not. */
    private val monitoringClaims = listOf(
        "sun raha",
        "listening now",
        "mic chal raha",
        "mic on hai",
    )

    private fun sweepReports(): List<ReadinessReport> {
        val reports = mutableListOf<ReadinessReport>()
        for (fixture in fixtures) {
            for (consent in SpeechConsentState.entries) {
                for (mic in MicPermission.entries) {
                    reports += evaluate(fixture.report, consent = consent, mic = mic)
                }
            }
        }
        for (interruption in Interruption.entries) {
            reports += evaluate(onDeviceReport, interruption = interruption)
            reports += evaluate(cloudOnlyReport, interruption = interruption)
        }
        reports += evaluate(permanentlyDeniedReport, mic = MicPermission.DENIED)
        reports += evaluate(onDeviceReport, phase = SessionPhase.STOPPED)
        reports += evaluate(onDeviceReport, phase = SessionPhase.LISTENING)
        reports += evaluate(onDeviceReport, demoRequested = true)
        reports += evaluate(onDeviceReport, demoAvailable = false)
        return reports
    }

    @Test
    fun noReportMakesAForbiddenClaim() {
        val reports = sweepReports()
        assertTrue("the sweep must really cover the matrix", reports.size >= 72)
        for (report in reports) {
            val text = report.allText().lowercase()
            for (claim in forbiddenClaims) {
                assertFalse("${report.state} must not claim '$claim'", text.contains(claim))
            }
            if (!report.isListening) {
                for (claim in monitoringClaims) {
                    assertFalse("${report.state} must not imply '$claim'", text.contains(claim))
                }
            }
        }
    }

    @Test
    fun everyReportCarriesTheScopeAndCloneLimitations() {
        for (report in sweepReports()) {
            assertTrue("${report.state} must state the scope", report.limitations.contains(ReadinessCopy.scope))
            assertTrue(
                "${report.state} must state the voice-clone limit",
                report.limitations.contains(ReadinessCopy.noCloneCheck),
            )
            assertTrue(report.limitations.contains(ReadinessCopy.nothingStored))
            // The exact limitation the report exists for: no other side of a phone call.
            assertTrue(report.hinglishText().contains("phone call ke doosre taraf ki awaaz nahi pakadta"))
            assertTrue(report.englishText().contains("does not capture the other side of a phone call"))
            // And the deepfake limit in the words the report asks for.
            assertTrue(
                "the voice-clone limit must appear verbatim",
                report.hinglishText().lowercase().contains("awaaz asli hai ya nakli, yeh check nahi hota"),
            )
            assertTrue(report.englishText().contains("Whether a voice is real or cloned is not checked"))
        }
    }

    // ------------------------------------------------------------------ coverage + quality

    @Test
    fun everyReadinessStateIsProducedByAnInput() {
        val produced = sweepReports().map { it.state }.toSet()
        for (state in ReadinessState.entries) {
            assertTrue("no input produces $state", produced.contains(state))
        }
        assertEquals("no state is produced that is not declared", ReadinessState.entries.toSet(), produced)
    }

    @Test
    fun everyReportIsDeterministic() {
        for (fixture in fixtures) {
            val a = evaluate(fixture.report, phase = SessionPhase.LISTENING)
            val b = evaluate(fixture.report, phase = SessionPhase.LISTENING)
            assertEquals(a, b)
            assertEquals(a.hashCode(), b.hashCode())
        }
        val interruptedA = evaluate(onDeviceReport, interruption = Interruption.ProcessRecreated)
        val interruptedB = evaluate(onDeviceReport, interruption = Interruption.ProcessRecreated)
        assertEquals(interruptedA, interruptedB)
    }

    @Test
    fun bilingualCopyIsCompleteEverywhere() {
        val lines = mutableListOf<Pair<String, String>>()
        for (report in sweepReports()) {
            lines += report.copyLines()
        }
        lines += ReadinessCopy.all.map { it.hinglish to it.english }
        for (interruption in Interruption.entries) {
            val line = ReadinessCopy.interruptionReason(interruption)
            lines += line.hinglish to line.english
        }
        for ((hinglish, english) in lines) {
            assertTrue("Hinglish text is blank", hinglish.isNotBlank())
            assertTrue("English text is blank", english.isNotBlank())
            assertNotEquals("both languages are the same line", hinglish, english)
        }
    }

    @Test
    fun noCopyLineCarriesADigitOrCredentialShape() {
        val lines = mutableListOf<Pair<String, String>>()
        for (report in sweepReports()) {
            lines += report.copyLines()
        }
        lines += ReadinessCopy.all.map { it.hinglish to it.english }
        for ((hinglish, english) in lines) {
            for (text in listOf(hinglish, english)) {
                assertFalse("a copy line carries a digit: $text", Regex("\\d").containsMatchIn(text))
            }
        }
    }

    @Test
    fun reportToStringCarriesStateOnly() {
        val text = evaluate(onDeviceReport, phase = SessionPhase.LISTENING).toString().lowercase()
        assertTrue(text.contains("ready_on_device"))
        assertFalse(text.contains("awaaz"))
        assertFalse("no copy text in a log line", text.contains("mic band"))
        // Counts are the only digits a log line may carry: never a 4+ run (OTP, PIN, card, phone).
        assertFalse("no credential-shaped digits in a log line", Regex("\\d{4,}").containsMatchIn(text))
    }

    // ------------------------------------------------------------------ bridge + accessibility

    @Test
    fun fromStateReadsTheExistingScreenState() {
        val granted = CallUiState(
            consent = ConsentState.GIVEN,
            phase = SessionPhase.LISTENING,
            permission = MicPermission.GRANTED,
        )
        val input = CallShieldReadiness.fromState(granted, onDeviceReport)
        assertEquals(SpeechConsentState.Granted, input.consent)
        assertEquals(MicPermission.GRANTED, input.micPermission)
        assertEquals(SessionPhase.LISTENING, input.phase)
        assertEquals(Interruption.None, input.interruption)
        assertEquals(ReadinessState.READY_ON_DEVICE, CallShieldReadiness.evaluate(input).state)
        assertTrue(CallShieldReadiness.evaluate(input).isListening)

        // CallUiState cannot tell "never asked" from "denied": both read as not-yet-given consent,
        // which is the honest reading of the screen vocabulary.
        val fresh = CallShieldReadiness.fromState(CallUiState(), onDeviceReport)
        assertEquals(SpeechConsentState.NotAsked, fresh.consent)
        assertEquals(ReadinessState.NEEDS_CONSENT, CallShieldReadiness.evaluate(fresh).state)

        // A revoked permission reaches the microphone state through the bridge as well.
        val revoked = CallShieldReadiness.fromState(
            CallUiState(consent = ConsentState.GIVEN, permission = MicPermission.DENIED),
            noMicReport,
        )
        assertEquals(ReadinessState.MIC_PERMISSION_DENIED, CallShieldReadiness.evaluate(revoked).state)
    }

    @Test
    fun fromStateForwardsAnExplicitInterruption() {
        val state = CallUiState(
            consent = ConsentState.GIVEN,
            phase = SessionPhase.LISTENING,
            permission = MicPermission.GRANTED,
        )
        val input = CallShieldReadiness.fromState(
            state,
            onDeviceReport,
            interruption = Interruption.AudioFocusLost,
        )
        val report = CallShieldReadiness.evaluate(input)
        assertEquals(ReadinessState.INTERRUPTED, report.state)
        assertFalse(report.isListening)
        assertNotNull(report.actions.firstOrNull())
    }

    @Test
    fun everyStateHasAHeadlineBodyAndAtLeastOneAction() {
        for (state in ReadinessState.entries) {
            val headline = ReadinessCopy.headline(state)
            val body = ReadinessCopy.body(state, Interruption.None, onDeviceReport)
            assertTrue("$state headline", headline.hinglish.isNotBlank() && headline.english.isNotBlank())
            assertTrue("$state body", body.hinglish.isNotBlank() && body.english.isNotBlank())
            assertNotEquals("$state headline must differ per language", headline.hinglish, headline.english)
            assertNotEquals("$state body must differ per language", body.hinglish, body.english)
            assertTrue("$state needs an action", ReadinessCopy.actions(state, demoAvailable = true).isNotEmpty())
        }
    }
}
