package ai.trustshield.android.input.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B04 — hi-IN speech-recognition capability probe.
 *
 * Plain JVM, JUnit 4. Only the pure decision core in `SpeechCapability.kt` is exercised:
 * `HindiLocaleSupportRules` and `SpeechLanguage`. No `android.*` type is imported, instantiated or
 * called here, because android.jar bodies are runtime stubs that throw. A phone hold is in force,
 * so nothing in this file needs a recogniser, a microphone, a camera or a device.
 *
 * The three honest outcomes from the probe must stay distinguishable end to end:
 *  - Supported   -> "this phone says yes"  -> true
 *  - Unsupported -> "this phone says no"   -> false
 *  - NotChecked  -> "we could not ask"     -> null
 */
class HindiRecognitionCapabilityTest {

    // ------------------------------------------------ 1. a yes is a yes

    @Test
    fun supportedHiInCallbackReportsSupportFromEitherLanguageList() {
        // hi-IN advertised on the online path only.
        assertTrue(HindiLocaleSupportRules.supportedFromLanguages(listOf("hi-IN"), emptyList()))
        // hi-IN advertised on the on-device path only.
        assertTrue(HindiLocaleSupportRules.supportedFromLanguages(emptyList(), listOf("hi-IN")))
        // Mixed list: another language present must not mask hi-IN.
        assertTrue(
            HindiLocaleSupportRules.supportedFromLanguages(
                onlineLanguages = listOf("en-IN", "hi-IN"),
                onDeviceLanguages = emptyList(),
            ),
        )
        // The platform is sloppy about case and padding; a real "yes" still counts.
        assertTrue(
            HindiLocaleSupportRules.supportedFromLanguages(
                onlineLanguages = listOf(" HI-in "),
                onDeviceLanguages = emptyList(),
            ),
        )
        // A yes folds to the Supported outcome and to the non-null `true` the UI reads.
        val supported = HindiLocaleSupportRules.supportedFromLanguages(listOf("hi-IN"), emptyList())
        assertEquals(
            HindiLocaleSupportOutcome.Supported,
            HindiLocaleSupportRules.outcomeForSupport(supported),
        )
        assertEquals(true, HindiLocaleSupportRules.toNullable(HindiLocaleSupportOutcome.Supported))
    }

    // ------------------------------------------- 2. a no is a real answer

    @Test
    fun completedNegativeResponseIsUnsupportedNotNotChecked() {
        // A completed callback that named no hi-IN path anywhere is a negative answer.
        assertFalse(HindiLocaleSupportRules.supportedFromLanguages(listOf("en-IN"), listOf("en-GB")))
        assertFalse(HindiLocaleSupportRules.supportedFromLanguages(emptyList(), emptyList()))

        val outcome = HindiLocaleSupportRules.outcomeForSupport(
            HindiLocaleSupportRules.supportedFromLanguages(listOf("en-IN"), emptyList()),
        )
        // "This phone says no" — a machine-readable false, never a shrug.
        assertEquals(HindiLocaleSupportOutcome.Unsupported, outcome)
        assertEquals(false, HindiLocaleSupportRules.toNullable(outcome))
        assertNotEquals(HindiLocaleSupportOutcome.NotChecked, outcome)

        // A missing recogniser service is also an answerable "no", not "we could not ask".
        assertEquals(
            HindiLocaleSupportOutcome.Unsupported,
            HindiLocaleSupportRules.outcomeForMissingService(),
        )
        assertEquals(false, HindiLocaleSupportRules.toNullable(HindiLocaleSupportRules.outcomeForMissingService()))
    }

    // --------------------------------------- 3. an error is not a verdict

    @Test
    fun callbackErrorsAndOldApisStayNotChecked() {
        // A callback error, or a recogniser that could not be created, told us nothing about Hindi.
        assertEquals(HindiLocaleSupportOutcome.NotChecked, HindiLocaleSupportRules.outcomeForError())
        assertNull(HindiLocaleSupportRules.toNullable(HindiLocaleSupportRules.outcomeForError()))

        // Below API 33 the platform cannot be asked at all.
        assertEquals(HindiLocaleSupportOutcome.NotChecked, HindiLocaleSupportRules.outcomeWhenNotMeasurable())
        assertNull(HindiLocaleSupportRules.toNullable(HindiLocaleSupportRules.outcomeWhenNotMeasurable()))

        // The gate is exactly the API that first exposes checkRecognitionSupport.
        assertEquals(33, HindiLocaleSupportRules.SUPPORT_CALLBACK_MIN_API)
        assertFalse(HindiLocaleSupportRules.probeAllowed(32))
        assertTrue(HindiLocaleSupportRules.probeAllowed(33))

        // The two negative-looking outcomes are different claims and must never collapse.
        assertNotEquals(HindiLocaleSupportOutcome.Unsupported, HindiLocaleSupportOutcome.NotChecked)
        assertEquals(false, HindiLocaleSupportRules.toNullable(HindiLocaleSupportOutcome.Unsupported))
        assertNull(HindiLocaleSupportRules.toNullable(HindiLocaleSupportOutcome.NotChecked))
        assertNotEquals(
            HindiLocaleSupportRules.toNullable(HindiLocaleSupportOutcome.Unsupported),
            HindiLocaleSupportRules.toNullable(HindiLocaleSupportOutcome.NotChecked),
        )
    }

    // ----------------------------- 4. the probe never starts a session

    @Test
    fun probeNeverStartsRecognitionOnAnyApiLevel() {
        // Asking "does this phone support hi-IN" must never open the microphone. This assertion is
        // the machine-checked form of that invariant, across the app's whole API range.
        for (apiLevel in listOf(26, 31, 32, 33, 36)) {
            assertFalse(
                "probe must not start recognition at API $apiLevel",
                HindiLocaleSupportRules.startsRecognition(apiLevel),
            )
        }
    }

    // ------------------------------------- 5. tag matching is strict

    @Test
    fun hiInTagMatchingIsStrict() {
        assertEquals("hi-IN", SpeechLanguage.HI_IN)
        assertTrue(HindiLocaleSupportRules.matchesHiIn(SpeechLanguage.HI_IN))
        assertTrue(HindiLocaleSupportRules.matchesHiIn(" hi-in "))

        assertFalse(HindiLocaleSupportRules.matchesHiIn("en-IN"))
        assertFalse(HindiLocaleSupportRules.matchesHiIn(null))
        // Underscore is not a BCP-47 separator, and a variant or garbage is not hi-IN either.
        assertFalse(HindiLocaleSupportRules.matchesHiIn("hi_IN"))
        assertFalse(HindiLocaleSupportRules.matchesHiIn("hi-IN-x-fake"))
        assertFalse(HindiLocaleSupportRules.matchesHiIn("hi"))
        assertFalse(HindiLocaleSupportRules.matchesHiIn("garbage"))
        assertFalse(HindiLocaleSupportRules.matchesHiIn(""))
    }

    // --------------------------------- 6. nullable mapping is faithful

    @Test
    fun toNullableMapsAllThreeOutcomesDistinctly() {
        assertEquals(true, HindiLocaleSupportRules.toNullable(HindiLocaleSupportOutcome.Supported))
        assertEquals(false, HindiLocaleSupportRules.toNullable(HindiLocaleSupportOutcome.Unsupported))
        assertNull(HindiLocaleSupportRules.toNullable(HindiLocaleSupportOutcome.NotChecked))

        // All three survive as three different values: no two outcomes share a nullable form.
        val outcomes = listOf(
            HindiLocaleSupportOutcome.Supported,
            HindiLocaleSupportOutcome.Unsupported,
            HindiLocaleSupportOutcome.NotChecked,
        )
        val mapped = outcomes.map { HindiLocaleSupportRules.toNullable(it) }
        assertEquals(3, mapped.size)
        assertEquals(3, mapped.toSet().size)
        assertEquals(setOf(true, false, null), mapped.toSet())

        // ...and the outcomes themselves stay three distinct constants.
        assertEquals(3, outcomes.toSet().size)
    }
}
