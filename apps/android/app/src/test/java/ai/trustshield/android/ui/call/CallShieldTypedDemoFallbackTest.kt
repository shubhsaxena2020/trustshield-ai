/*
 * P4 fallback gate: the typed / tap-through demo ladder on a phone with NO microphone capability.
 *
 * This file is the "no phone, no mic" proof for Call Shield. Nothing here constructs a microphone,
 * a recogniser, a permission request or an `android.*` type: the demo is a typed script, and these
 * tests drive it exactly the way the screen drives it on a phone whose mic is denied or whose
 * speech service is missing.
 *
 * What is asserted, one test per point:
 *  1. the full ladder is reachable and functional without any microphone capability, and its
 *     (score, position) sequence is exactly the one documented in `CallDemoScript`'s own header
 *     table (10 -> LOW, 44 -> MEDIUM, 52 -> HIGH, 75 -> CRITICAL);
 *  2. the typed path needs no recognition: one synthetic scam line from the repo's own script
 *     produces a step whose score is the deterministic engine's, the transcript is live, and blank
 *     input adds no step;
 *  3. `reset()` empties the fallback state and `back()` re-derives the previous reading through the
 *     engine, so no stale step (and no stale CRITICAL alert) survives;
 *  4. readiness offers this demo exactly where live speech is impossible - mic denied and no
 *     recognition service - with the demo action present and a live session refused;
 *  5. two independent runs produce an identical (score, position) sequence;
 *  6. nothing is stored: the transcript is empty after reset and no persistence API is used.
 *
 * Honesty note: the four expected readings are copied from the `CallDemoScript` file KDoc table and
 * are pinned identically by `CallDemoScriptTest`; the source and the table agree. This suite was NOT
 * executed while it was written (no Gradle run, no Kotlin compiler on the authoring host), so the
 * numbers are source-pinned, not re-measured here - a real `:app:testDebugUnitTest` run is what
 * confirms them.
 *
 * Every line fed to the engine comes from `CallDemoScript` (the repo's own synthetic report §6
 * script). No real personal data, OTP/PIN, phone number or UPI id appears in this file.
 */
package ai.trustshield.android.ui.call

import ai.trustshield.android.core.model.RiskLabel
import ai.trustshield.android.core.normalize.HinglishNormalizer
import ai.trustshield.android.core.score.CallSessionState
import ai.trustshield.android.core.score.LocalScorer
import ai.trustshield.android.input.speech.SpeechCapabilityReport
import ai.trustshield.android.input.speech.SpeechCapabilityRules
import ai.trustshield.android.input.speech.SpeechConsentState
import ai.trustshield.android.input.speech.SpeechEnvironment
import ai.trustshield.android.input.speech.SpeechLanguage
import ai.trustshield.android.input.speech.SpeechStatus
import ai.trustshield.android.input.speech.MicPermission as SpeechMic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CallShieldTypedDemoFallbackTest {

    /** One engine reading as the meter shows it: the score and the position it maps to. */
    private data class Reading(val score: Int, val position: DemoRiskPosition)

    /**
     * The ladder `CallDemoScript`'s header table documents, in play order.
     *
     * Copied field-for-field from that table (`score` -> `position`); if a rule change moves a rung,
     * the table, this list and `CallDemoScriptTest` all change together.
     */
    private val pinnedLadder = listOf(
        Reading(score = 10, position = DemoRiskPosition.LOW),
        Reading(score = 44, position = DemoRiskPosition.MEDIUM),
        Reading(score = 52, position = DemoRiskPosition.HIGH),
        Reading(score = 75, position = DemoRiskPosition.CRITICAL),
    )

    /** Built through the REAL B04 rules, so no test can invent a status the product never emits. */
    private fun capabilityReport(
        service: Boolean = true,
        mic: SpeechMic = SpeechMic.Granted,
    ): SpeechCapabilityReport = SpeechCapabilityRules.evaluate(
        SpeechEnvironment(
            apiLevel = 33,
            recognizerServiceAvailable = service,
            onDeviceAvailable = true,
            hiInModelDownloaded = true,
            micPermission = mic,
        ),
        SpeechLanguage.HI_IN,
    )

    // ---------------------------------------------------------------------------------------------
    // 1. The ladder works with no microphone capability at all
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `ladder climbs low to critical on a device with no microphone capability`() {
        // No microphone, no permission prompt, no recogniser: this file imports no `android.*` type,
        // and `CallDemoRunner` only ever appends typed text to an in-memory engine.
        assertEquals("the script is the four report lines", 4, CallDemoScript.size)
        assertTrue(
            "the shipped script must not carry a SYSTEM stage direction",
            CallDemoScript.lines.none { it.speaker == DemoSpeaker.SYSTEM },
        )

        val runner = CallDemoRunner()
        val measured = ArrayList<Reading>(CallDemoScript.size)

        var guard = 0
        while (!runner.progress().finished && guard < 16) {
            val step = runner.advance().steps.last()
            measured += Reading(score = step.score, position = step.position)
            assertTrue(
                "the in-memory transcript must exist while the ladder runs",
                runner.transcript().isNotBlank(),
            )
            guard++
        }

        assertEquals("every script line must be reachable", CallDemoScript.size, measured.size)
        assertEquals("documented ladder", pinnedLadder, measured)
        assertEquals(listOf(10, 44, 52, 75), measured.map { it.score })
        assertEquals(
            listOf(
                DemoRiskPosition.LOW,
                DemoRiskPosition.MEDIUM,
                DemoRiskPosition.HIGH,
                DemoRiskPosition.CRITICAL,
            ),
            measured.map { it.position },
        )

        val steps = runner.progress().steps
        // The steps are the script's lines, in order, read by the engine - not a hand-set number.
        assertEquals(CallDemoScript.lines, steps.map { it.line })
        assertEquals(
            listOf(RiskLabel.LOW_RISK, RiskLabel.CAUTION, RiskLabel.SUSPICIOUS, RiskLabel.HIGH_RISK),
            steps.map { it.label },
        )
        // The takeover is engine-gated: release and one haptic pulse, only on the step into CRITICAL.
        assertEquals(listOf(false, false, false, true), steps.map { it.escalated })
        assertEquals(listOf(false, false, false, true), steps.map { it.haptics })
        assertEquals(listOf(false, false, false, true), steps.map { it.alert != null })
    }

    // ---------------------------------------------------------------------------------------------
    // 2. The typed path needs no recognition
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `typed line scores through the deterministic engine and blank input adds no step`() {
        val line = CallDemoScript.LINE_3_SECRECY // a synthetic scam line from the repo's own script

        val runner = CallDemoRunner()
        val progress = runner.submitTypedLine(line)

        assertEquals("one typed line is one step", 1, progress.steps.size)
        val step = progress.steps.single()
        assertEquals(0, step.index)
        assertEquals(DemoSpeaker.SCAMMER, step.line.speaker)
        assertEquals(DemoStep.TYPED_LINE_NOTE, step.line.noteHinglish)

        // THE POINT: the step's reading is an independent engine run over the same single line. No
        // recogniser exists anywhere in this test, so a matching score cannot come from audio.
        val state = CallSessionState()
        state.appendUtterance(text = line, atMillis = 0L, asrConfidence = 1.0)
        val engine = LocalScorer(HinglishNormalizer()).analyzeTranscriptScored(line, state)

        assertEquals(engine.result.risk.score, step.score)
        assertEquals(engine.result.risk.label, step.label)
        assertEquals(positionFor(engine), step.position)
        assertEquals(engine.escalateCritical, step.escalated)

        // The transcript is live memory while the demo runs.
        assertEquals("the transcript must hold the typed line", line, runner.transcript())
        assertTrue(runner.transcript().isNotBlank())

        // Blank input is refused rather than becoming a silent extra step.
        val afterSpaces = runner.type("   ")
        assertEquals(1, afterSpaces.steps.size)
        assertEquals(line, runner.transcript())

        val afterEmpty = runner.type("")
        assertEquals(1, afterEmpty.steps.size)
        assertEquals(progress.position, afterEmpty.position)
        assertEquals(progress.currentIndex, afterEmpty.currentIndex)
        assertEquals(line, runner.transcript())
    }

    // ---------------------------------------------------------------------------------------------
    // 3. Reset clears the fallback state; back() re-derives instead of un-highlighting
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `reset empties the fallback state and back re-derives the previous reading`() {
        val runner = CallDemoRunner()
        runner.advance()
        runner.advance()
        assertEquals(2, runner.progress().steps.size)
        assertEquals(DemoRiskPosition.MEDIUM, runner.progress().position)

        // back() replays the remaining lines through a fresh engine, so the old step cannot survive.
        val oneBack = runner.back()
        assertEquals(1, oneBack.steps.size)
        assertEquals(0, oneBack.currentIndex)
        assertEquals(10, oneBack.steps.single().score)
        assertEquals(DemoRiskPosition.LOW, oneBack.position)
        assertEquals(CallDemoScript.LINE_1_AUTHORITY, runner.transcript())
        assertFalse(
            "the dropped line must leave the transcript",
            runner.transcript().contains(CallDemoScript.LINE_2_THREAT),
        )

        // Backing out of CRITICAL must drop the takeover copy and the haptic with the line.
        repeat(CallDemoScript.size) { runner.advance() }
        assertEquals(DemoRiskPosition.CRITICAL, runner.progress().position)
        val outOfCritical = runner.back()
        assertEquals(DemoRiskPosition.HIGH, outOfCritical.position)
        assertEquals(3, outOfCritical.steps.size)
        assertTrue(outOfCritical.steps.none { it.alert != null })
        assertTrue(outOfCritical.steps.none { it.haptics })

        // reset() is Stop: nothing read, meter back at the first step, and it is not a verdict.
        val reset = runner.reset()
        assertTrue(reset.steps.isEmpty())
        assertNull(reset.current)
        assertEquals(-1, reset.currentIndex)
        assertEquals(DemoRiskPosition.LOW, reset.position)
        assertFalse(reset.finished)
        assertEquals(0, reset.scriptCursor)
        assertEquals("", runner.transcript())
    }

    // ---------------------------------------------------------------------------------------------
    // 4. Readiness offers the demo exactly where live speech is impossible
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `readiness offers the demo exactly where live speech is impossible`() {
        // Microphone denied. B04's real rules turn this environment into NoMicPermission.
        val micDenied = capabilityReport(mic = SpeechMic.Denied)
        assertEquals(SpeechStatus.NoMicPermission, micDenied.status)

        val denied = CallShieldReadiness.evaluate(
            ReadinessInput(
                report = micDenied,
                consent = SpeechConsentState.Granted,
                micPermission = MicPermission.DENIED,
            ),
        )
        assertEquals(ReadinessState.MIC_PERMISSION_DENIED, denied.state)
        assertFalse("a denied mic must not start a live session", denied.canStartLiveSession)
        assertTrue("the typed demo is the fallback here", denied.canUseDemoScript)
        assertTrue(denied.actions.contains(ReadinessCopy.useDemoScript))
        assertFalse(denied.isListening)

        // No recognition service at all: no live route exists, so the demo is the honest offer.
        val noService = capabilityReport(service = false)
        assertEquals(SpeechStatus.MissingService, noService.status)
        assertFalse(noService.cloudMayBeUsed)

        val unavailable = CallShieldReadiness.evaluate(ReadinessInput(report = noService))
        assertEquals(ReadinessState.RECOGNIZER_UNAVAILABLE, unavailable.state)
        assertFalse(unavailable.canStartLiveSession)
        assertTrue(unavailable.canUseDemoScript)
        assertTrue(unavailable.actions.contains(ReadinessCopy.useDemoScript))
        assertTrue(unavailable.actions.contains(ReadinessCopy.openSpeechSettings))
        assertFalse(unavailable.isListening)

        // Where live speech IS possible the demo is still offered - as the alternative, not instead.
        val ready = CallShieldReadiness.evaluate(
            ReadinessInput(
                report = capabilityReport(),
                consent = SpeechConsentState.Granted,
                micPermission = MicPermission.GRANTED,
            ),
        )
        assertEquals(ReadinessState.READY_ON_DEVICE, ready.state)
        assertTrue(ready.canStartLiveSession)
        assertEquals(ReadinessCopy.startSession, ready.actions.first())
        assertTrue(ready.actions.contains(ReadinessCopy.useDemoScript))

        // A build without the demo must not claim it in any of these states.
        val noDemo = CallShieldReadiness.evaluate(
            ReadinessInput(report = noService, demoAvailable = false),
        )
        assertFalse(noDemo.canUseDemoScript)
        assertFalse(noDemo.actions.contains(ReadinessCopy.useDemoScript))
    }

    // ---------------------------------------------------------------------------------------------
    // 5. Determinism
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `two independent runs give the identical score and position sequence`() {
        fun run(): List<Reading> {
            val runner = CallDemoRunner()
            repeat(CallDemoScript.size) { runner.advance() }
            return runner.progress().steps.map { Reading(score = it.score, position = it.position) }
        }

        val first = run()
        val second = run()
        assertEquals("the same script in must give the same readings out", first, second)
        assertEquals(pinnedLadder, first)

        // The pure value is deterministic too: no hidden state is shared with the runner above.
        var progress = DemoProgress.start()
        repeat(CallDemoScript.size) { progress = progress.advance() }
        assertEquals(first, progress.steps.map { Reading(score = it.score, position = it.position) })
    }

    // ---------------------------------------------------------------------------------------------
    // 6. Nothing is stored
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `the fallback stores nothing after reset`() {
        // This test deliberately uses no file, preference, database or persistence API: the demo's
        // only state is the in-memory CallSessionState owned by CallDemoRunner, so "nothing is
        // stored" is observable as the transcript and the steps both going empty again.
        val runner = CallDemoRunner()
        repeat(CallDemoScript.size) { runner.advance() }
        assertTrue(runner.transcript().isNotBlank())

        runner.reset()

        assertTrue("reset must leave an empty transcript", runner.transcript().isEmpty())
        assertEquals("", runner.transcript())

        val progress = runner.progress()
        assertTrue("no executed line may survive reset", progress.executed.isEmpty())
        assertTrue("no step may survive reset", progress.steps.isEmpty())
        assertTrue("no retained quote may survive reset", progress.steps.none { it.quotes.isNotEmpty() })
        assertNull(progress.current)
    }
}
