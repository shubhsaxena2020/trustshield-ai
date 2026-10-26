package ai.trustshield.android.ui.call

import ai.trustshield.android.ui.navigation.demoBandSegments
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Issue #119 defect B follow-up: the demo meter's two remaining gaps.
 *
 * 1. The demo screen must vibrate only when the user has haptics enabled in Settings.
 * 2. The four-position band must fill one segment per rung, and none before the demo runs (a "0/100"
 *    pre-start reading would be a fabricated number).
 *
 * The band mapping is asserted directly ([demoBandSegments]); the haptic gate is a Compose effect, so
 * it is asserted against the source the same way the Result-screen stray-text guard does.
 */
class CallDemoMeterBandTest {

    private fun appScreensSource(): String {
        val candidates = listOf(
            File("src/main/java/ai/trustshield/android/ui/navigation/AppScreens.kt"),
            File("apps/android/app/src/main/java/ai/trustshield/android/ui/navigation/AppScreens.kt"),
        )
        val file = candidates.firstOrNull { it.exists() }
        assertTrue(
            "could not locate AppScreens.kt from ${File(".").absolutePath}",
            file != null,
        )
        return file!!.readText()
    }

    @Test
    fun `band fills one segment per rung`() {
        assertEquals(1, demoBandSegments(DemoRiskPosition.LOW))
        assertEquals(2, demoBandSegments(DemoRiskPosition.MEDIUM))
        assertEquals(3, demoBandSegments(DemoRiskPosition.HIGH))
        assertEquals(4, demoBandSegments(DemoRiskPosition.CRITICAL))
    }

    @Test
    fun `band is empty before the demo runs`() {
        assertEquals(0, demoBandSegments(null))
    }

    @Test
    fun `band segments are monotonic across the ladder`() {
        val ladder = listOf(
            DemoRiskPosition.LOW,
            DemoRiskPosition.MEDIUM,
            DemoRiskPosition.HIGH,
            DemoRiskPosition.CRITICAL,
        ).map { demoBandSegments(it) }
        assertEquals(listOf(1, 2, 3, 4), ladder)
    }

    @Test
    fun `demo haptics are gated on the settings preference`() {
        val source = appScreensSource()
        val hapticLine = source.lines().firstOrNull { it.contains("performHapticFeedback") }
        assertTrue("the demo screen must still be able to vibrate", hapticLine != null)
        val window = source.lines()
            .filterIndexed { index, _ ->
                index > 0
            }
            .joinToString("\n")
        assertTrue(
            "the haptic call must be guarded by the haptics preference",
            window.contains("if (hapticsEnabled && step?.haptics == true)"),
        )
        assertTrue(
            "the demo screen must receive the preference",
            source.contains("private fun CallDemoExperience(") && source.contains("hapticsEnabled: Boolean"),
        )
    }

    @Test
    fun `demo mode does not compose the live session controls`() {
        val source = appScreensSource()
        // The demo branch returns its own screen; the live screen (with the Stop bar) is the else branch.
        assertTrue(
            "demo mode must render its own screen instead of the live one",
            source.contains("if (demoOpen)") && source.contains("} else {"),
        )
        assertTrue(
            "the demo screen must not draw a Stop control",
            !source.substringAfter("private fun CallDemoExperience(").substringBefore("\nprivate fun ")
                .contains("CallEvent.Stop"),
        )
    }
}
