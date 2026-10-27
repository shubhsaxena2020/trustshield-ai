package ai.trustshield.android.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SystemMotion] verification — the reduced-motion contract, proved on a plain JVM.
 *
 * No Robolectric, no instrumentation, no mocks: the policy is a function of `(Float?, Boolean)`, so
 * every row of the table in `SystemMotion`'s KDoc is asserted directly. [readAnimatorDurationScale]
 * is deliberately **not** exercised here — it needs a real `ContentResolver`, and the stub
 * `android.jar` used by JVM unit tests throws `RuntimeException("Stub!")` for `Settings.Global`
 * rather than returning a value, so a passing JVM test of the reader would prove nothing about a
 * device. It is verified by inspection only; see the lane report.
 *
 * One contract is asserted throughout, and it is written out exactly once, in [referenceCollapse]:
 *
 * > collapse == userPrefReducedMotion || (scale is a finite number <= 0f)
 *
 * `0.5f` and `2f` do **not** collapse, `null` does **not** collapse, and the user preference alone
 * is enough. Anything that disagrees with that expression is a bug in the policy.
 */
class SystemMotionTest {

    // ---------------------------------------------------------------- the contract, written once

    /** The documented contract, transcribed independently of the implementation under test. */
    private fun referenceCollapse(scale: Float?, pref: Boolean): Boolean =
        pref || (scale != null && scale.isFinite() && scale <= 0f)

    // ---------------------------------------------------------------- the individual rows

    @Test
    fun unreadableScaleDoesNotForceReducedMotion() {
        // A host that could not read Settings.Global must not silently switch motion off for
        // everyone; "unknown" is not "off".
        assertFalse(
            "null scale + pref off must keep motion",
            SystemMotion.mustCollapseTransitions(animatorDurationScale = null, userPrefReducedMotion = false)
        )
    }

    @Test
    fun unreadableScaleStillHonoursTheInAppSwitch() {
        // The app's own switch is a real user choice, so it survives an unreadable platform value.
        assertTrue(
            "null scale + pref on must still collapse",
            SystemMotion.mustCollapseTransitions(animatorDurationScale = null, userPrefReducedMotion = true)
        )
    }

    @Test
    fun zeroScaleIsTheSystemRemoveAnimationsSetting() {
        // Settings.Global.ANIMATOR_DURATION_SCALE == 0 is Android's "Remove animations".
        assertTrue(
            "scale 0 must collapse with the pref off (system floor)",
            SystemMotion.mustCollapseTransitions(animatorDurationScale = 0f, userPrefReducedMotion = false)
        )
        assertTrue(
            "scale 0 must collapse with the pref on",
            SystemMotion.mustCollapseTransitions(animatorDurationScale = 0f, userPrefReducedMotion = true)
        )
        assertEquals("the documented 'off' value is a pinned contract", 0f, SystemMotion.COLLAPSED_SCALE)
    }

    @Test
    fun nearZeroScaleIsNotZero() {
        // 0.1f is Android's slowest *non-zero* step: animations still run, so they are not removed.
        assertFalse(
            "scale 0.1 must not collapse",
            SystemMotion.mustCollapseTransitions(animatorDurationScale = 0.1f, userPrefReducedMotion = false)
        )
    }

    @Test
    fun halfScaleIsSlowMotionNotNoMotion() {
        // 0.5x is a request for slower transitions, not for none. Emulating the multiplier here
        // would double-apply it on top of the framework's own motion-duration scale.
        assertFalse(
            "scale 0.5 must not collapse",
            SystemMotion.mustCollapseTransitions(animatorDurationScale = 0.5f, userPrefReducedMotion = false)
        )
    }

    @Test
    fun defaultScaleKeepsMotion() {
        assertFalse(
            "scale 1 must not collapse",
            SystemMotion.mustCollapseTransitions(animatorDurationScale = 1f, userPrefReducedMotion = false)
        )
    }

    @Test
    fun fasterScaleKeepsMotion() {
        // 2x / 10x are faster, never "remove". A caller must not read "not 1f" as "reduced".
        assertFalse(
            "scale 2 must not collapse",
            SystemMotion.mustCollapseTransitions(animatorDurationScale = 2f, userPrefReducedMotion = false)
        )
        assertFalse(
            "scale 10 must not collapse",
            SystemMotion.mustCollapseTransitions(animatorDurationScale = 10f, userPrefReducedMotion = false)
        )
    }

    @Test
    fun appSwitchAloneCollapsesAtFullSystemSpeed() {
        // The in-app "Reduce animations" toggle works on a phone whose animator scale is 1.
        assertTrue(
            "pref on + scale 1 must collapse",
            SystemMotion.mustCollapseTransitions(animatorDurationScale = 1f, userPrefReducedMotion = true)
        )
    }

    @Test
    fun appSwitchOffCannotOverrideTheSystemFloor() {
        // The important direction: the two inputs OR, so turning the app switch off while Android
        // says "remove animations" must not bring the transitions back.
        assertTrue(
            "pref off + scale 0 must stay collapsed",
            SystemMotion.mustCollapseTransitions(animatorDurationScale = 0f, userPrefReducedMotion = false)
        )
    }

    @Test
    fun malformedNegativeAndNonFiniteScalesAreTreatedAsUnreadable() {
        // A negative duration cannot mean "animate", so it reads as off; NaN and the infinities are
        // not durations at all, so they read as unknown rather than as "off" or "on".
        assertTrue(
            "scale -1 must collapse",
            SystemMotion.mustCollapseTransitions(animatorDurationScale = -1f, userPrefReducedMotion = false)
        )
        assertFalse(
            "scale NaN must not collapse",
            SystemMotion.mustCollapseTransitions(animatorDurationScale = Float.NaN, userPrefReducedMotion = false)
        )
        assertFalse(
            "scale +Infinity must not collapse",
            SystemMotion.mustCollapseTransitions(
                animatorDurationScale = Float.POSITIVE_INFINITY,
                userPrefReducedMotion = false
            )
        )
        assertFalse(
            "scale -Infinity must not collapse",
            SystemMotion.mustCollapseTransitions(
                animatorDurationScale = Float.NEGATIVE_INFINITY,
                userPrefReducedMotion = false
            )
        )
    }

    @Test
    fun everyScaleAgreesWithTheDocumentedContract() {
        // Exhaustive sweep, including the values Android and OEMs actually write and the float
        // edges a corrupt setting could produce.
        val scales: List<Float?> = listOf(
            null,
            -100f,
            -1f,
            -0f,
            0f,
            Float.MIN_VALUE, // smallest positive float: a real, tiny animation duration
            0.01f,
            0.1f,
            0.25f,
            0.5f,
            0.75f,
            1f,
            1.5f,
            2f,
            3f,
            10f,
            Float.MAX_VALUE,
            Float.NaN,
            Float.POSITIVE_INFINITY,
            Float.NEGATIVE_INFINITY
        )
        for (scale in scales) {
            val systemAsks = referenceCollapse(scale, pref = false)
            for (pref in booleanArrayOf(false, true)) {
                val expected = referenceCollapse(scale, pref)
                val actual = SystemMotion.mustCollapseTransitions(
                    animatorDurationScale = scale,
                    userPrefReducedMotion = pref
                )
                assertEquals("scale=$scale pref=$pref must equal the documented contract", expected, actual)
            }
            // The narrow question must ignore the preference and answer only "does the OS ask for
            // it"; that is what lets a caller report "your phone asked for this" honestly.
            assertEquals(
                "systemRequestsReducedMotion($scale) must equal the contract's system half",
                systemAsks,
                SystemMotion.systemRequestsReducedMotion(scale)
            )
        }
        assertEquals("null is the only unreadable input tested", 1, scales.count { it == null })
    }

    @Test
    fun systemQuestionIsIndependentOfTheUserSwitch() {
        // The narrow helper must never consult the preference: it answers "does the OS ask for it".
        assertTrue(SystemMotion.systemRequestsReducedMotion(0f))
        assertTrue(SystemMotion.systemRequestsReducedMotion(-0.5f))
        assertFalse(SystemMotion.systemRequestsReducedMotion(null))
        assertFalse(SystemMotion.systemRequestsReducedMotion(0.5f))
        assertFalse(SystemMotion.systemRequestsReducedMotion(1f))
        assertFalse(SystemMotion.systemRequestsReducedMotion(Float.NaN))
    }

    @Test
    fun collapsedPolicyActuallyRemovesTheTransitionDuration() {
        // End-to-end of the contract that matters: the boolean feeds TsMotion.duration, and a
        // collapsed policy really yields a 0 ms token (so tween(...) is an instant jump, not a
        // shortened animation).
        for (scale in listOf<Float?>(0f, 1f, 0.5f, null)) {
            val collapse = SystemMotion.mustCollapseTransitions(scale, userPrefReducedMotion = false)
            val short = TsMotion.duration(TsTokens.MotionShortMs, collapse)
            val medium = TsMotion.duration(TsTokens.MotionMediumMs, collapse)
            if (collapse) {
                assertEquals("scale=$scale must yield 0 ms short", 0, short)
                assertEquals("scale=$scale must yield 0 ms medium", 0, medium)
            } else {
                assertEquals("scale=$scale must keep the short token", TsTokens.MotionShortMs, short)
                assertEquals("scale=$scale must keep the medium token", TsTokens.MotionMediumMs, medium)
                assertTrue("a kept token must stay inside the UI_SPEC band", short in TsTokens.MotionBandMs)
                assertTrue("a kept token must stay inside the UI_SPEC band", medium in TsTokens.MotionBandMs)
            }
        }
    }
}
