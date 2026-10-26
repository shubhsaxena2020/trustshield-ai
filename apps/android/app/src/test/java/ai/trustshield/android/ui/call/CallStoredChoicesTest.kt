package ai.trustshield.android.ui.call

import ai.trustshield.android.ui.theme.RiskTone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #138 — the stored language / haptics choices must survive every snapshot taken from
 * `CallShieldHost`.
 *
 * These are JVM tests: `withStoredChoices` is pure Kotlin, so the rule that used to be enforced by
 * five independent copies of `if (prefs.language == ENGLISH)` inside a composable is testable
 * without a device. The device-level reproduction (Hinglish copy on an English, haptics-off app) is
 * recorded in `docs/android/evidence/p4-138-*`.
 */
class CallStoredChoicesTest {

    /**
     * The host's own defaults are the reason a verbatim `state = host.state` leaked. This pins them,
     * so if `CallUiState` ever changes its defaults the sync rule is revisited instead of silently
     * becoming a no-op.
     */
    @Test
    fun `host snapshot defaults are hinglish with haptics on`() {
        val hostState = CallUiState()
        assertEquals(CallLanguage.HINGLISH, hostState.language)
        assertTrue(hostState.hapticsEnabled)
        assertFalse(hostState.reducedMotion)
    }

    /** The measured regression: an English, haptics-off, reduced-motion-on preference. */
    @Test
    fun `english with haptics off survives a host snapshot`() {
        val hostState = CallUiState(recognizer = RecognizerStatus.UNAVAILABLE)

        val synced = hostState.withStoredChoices(
            language = CallLanguage.ENGLISH,
            hapticsEnabled = false,
            reducedMotion = true
        )

        assertEquals(CallLanguage.ENGLISH, synced.language)
        assertFalse(synced.hapticsEnabled)
        assertTrue(synced.reducedMotion)
        // The session's own fields are not rebuilt from settings.
        assertEquals(RecognizerStatus.UNAVAILABLE, synced.recognizer)
    }

    /** Switching back to the Hinglish default restores the same three fields, nothing else. */
    @Test
    fun `hinglish with haptics on restores only the three choices`() {
        val synced = CallUiState(language = CallLanguage.ENGLISH, hapticsEnabled = false)
            .withStoredChoices(CallLanguage.HINGLISH, hapticsEnabled = true, reducedMotion = false)

        assertEquals(CallLanguage.HINGLISH, synced.language)
        assertTrue(synced.hapticsEnabled)
        assertFalse(synced.reducedMotion)
    }

    /**
     * A live session's content must pass through untouched: syncing is a presentation re-apply, not
     * a re-evaluation. If any of these moved, the sync would be a second scoring path.
     */
    @Test
    fun `session content and counters are never rebuilt from settings`() {
        val transcript = listOf(TranscriptLine(atSecond = 3, text = "jo OTP aaya hai woh batao"))
        val stages = CallStage.ordered.map { StageChip(stage = it, acceptedQuotes = if (it == CallStage.ASK) 1 else 0) }
        val session = CallUiState(
            transcript = transcript,
            stages = stages,
            tone = RiskTone.RED,
            confidence = ConfidenceLevel.HIGH,
            elapsedSeconds = 42,
            moneyOrCredentialAsk = true,
            hapticPulses = 1,
            lastHapticTone = RiskTone.RED
        )

        val synced = session.withStoredChoices(CallLanguage.ENGLISH, hapticsEnabled = false, reducedMotion = true)

        assertEquals(transcript, synced.transcript)
        assertEquals(stages, synced.stages)
        assertEquals(RiskTone.RED, synced.tone)
        assertEquals(ConfidenceLevel.HIGH, synced.confidence)
        assertEquals(42, synced.elapsedSeconds)
        assertTrue(synced.moneyOrCredentialAsk)
        // The pulse counter must not reset: `CallShieldScreen` fires one pulse per increment, so a
        // reset here would re-arm the vibration.
        assertEquals(1, synced.hapticPulses)
        assertEquals(RiskTone.RED, synced.lastHapticTone)
    }

    /** Idempotent, and returns the same instance when nothing changes (Compose equality). */
    @Test
    fun `applying the same stored choices twice returns the same instance`() {
        val once = CallUiState().withStoredChoices(CallLanguage.ENGLISH, hapticsEnabled = false, reducedMotion = false)
        val twice = once.withStoredChoices(CallLanguage.ENGLISH, hapticsEnabled = false, reducedMotion = false)

        assertSame(once, twice)
    }

    /**
     * The exact hazard the fix removes, expressed end to end: repeated host snapshots (start, pause,
     * resume, utterance, lifecycle) cannot walk the screen back to the host's defaults.
     */
    @Test
    fun `repeated host snapshots cannot walk the choices back`() {
        var state = CallUiState()
        repeat(5) {
            // What the host hands back after an event; it never carries the user's settings.
            val snapshot = CallUiState(recognizer = RecognizerStatus.UNAVAILABLE, elapsedSeconds = it + 1)
            state = snapshot.withStoredChoices(
                language = CallLanguage.ENGLISH,
                hapticsEnabled = false,
                reducedMotion = true
            )
        }

        assertEquals(CallLanguage.ENGLISH, state.language)
        assertFalse(state.hapticsEnabled)
        assertEquals(5, state.elapsedSeconds)
    }
}
