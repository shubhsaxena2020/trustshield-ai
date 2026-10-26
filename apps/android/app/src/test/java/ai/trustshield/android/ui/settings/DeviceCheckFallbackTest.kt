package ai.trustshield.android.ui.settings

import ai.trustshield.android.input.speech.HindiLocaleSupportOutcome
import ai.trustshield.android.input.speech.HindiLocaleSupportRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The fallback contract of the hidden Device check, pinned on a plain JVM.
 *
 * "Android could not answer" ([HindiLocaleSupportOutcome.NotChecked]) must never surface as
 * "this phone says no" ([DeviceCheckLocale.UNSUPPORTED]), the row must start honest, and the check
 * must never open the microphone. Nothing here constructs an `android.*` object: `SpeechRecognizer`,
 * `Context` and the permission APIs are compile-only stubs whose bodies throw at runtime, so this
 * file only touches the pure decision core in `input/speech/SpeechCapability.kt` and the pure row
 * rules/copy in `ui/settings/DeviceCheck.kt`.
 *
 * Privacy: only copy strings and synthetic placeholder values (no device, no text, no audio, no
 * numbers) appear below.
 */
class DeviceCheckFallbackTest {

    /**
     * Acceptance 1 - below API 33, or on a callback error, Android did not answer. That is
     * NOT_CHECKED, and the row must not read as UNSUPPORTED.
     *
     * Guaranteed by [HindiLocaleSupportRules.probeAllowed] (>= 33),
     * [HindiLocaleSupportRules.outcomeWhenNotMeasurable], [HindiLocaleSupportRules.outcomeForError]
     * and the exhaustive `when` in [DeviceCheckLocaleRules.statusFor].
     */
    @Test
    fun androidCannotAnswerMapsToNotCheckedNeverUnsupported() {
        // Android 13 and below expose no support callback, so the question cannot be asked.
        assertFalse(
            "probeAllowed(32) must be false: API 33 is the first version with the support callback",
            HindiLocaleSupportRules.probeAllowed(32),
        )

        val notMeasurable = DeviceCheckLocaleRules.statusFor(
            HindiLocaleSupportRules.outcomeWhenNotMeasurable(),
        )
        val afterError = DeviceCheckLocaleRules.statusFor(
            HindiLocaleSupportRules.outcomeForError(),
        )

        assertEquals(DeviceCheckLocale.NOT_CHECKED, notMeasurable)
        assertEquals(DeviceCheckLocale.NOT_CHECKED, afterError)
        assertNotEquals(DeviceCheckLocale.UNSUPPORTED, notMeasurable)
        assertNotEquals(DeviceCheckLocale.UNSUPPORTED, afterError)

        val english = DeviceCheckCopy.localeStatus(DeviceCheckLocale.NOT_CHECKED, english = true)
        val hinglish = DeviceCheckCopy.localeStatus(DeviceCheckLocale.NOT_CHECKED, english = false)

        assertEquals("Not checked on this Android version", english)
        assertTrue("the Hinglish NOT_CHECKED line must not be blank", hinglish.isNotBlank())
        assertNotEquals("both languages must carry their own wording", english, hinglish)
    }

    /**
     * Acceptance 2 - a phone with no recognition service has answered. That is a real "no"
     * (UNSUPPORTED), with its own copy, and it must stay visually distinct from NOT_CHECKED.
     *
     * Guaranteed by [HindiLocaleSupportRules.outcomeForMissingService],
     * [DeviceCheckLocaleRules.statusFor] and [DeviceCheckCopy.localeStatus].
     */
    @Test
    fun missingRecognitionServiceIsAnAnswerNotARefusal() {
        assertEquals(
            HindiLocaleSupportOutcome.Unsupported,
            HindiLocaleSupportRules.outcomeForMissingService(),
        )
        assertEquals(
            DeviceCheckLocale.UNSUPPORTED,
            DeviceCheckLocaleRules.statusFor(
                HindiLocaleSupportRules.outcomeForMissingService(),
            ),
        )

        val unsupportedEnglish = DeviceCheckCopy.localeStatus(DeviceCheckLocale.UNSUPPORTED, english = true)
        val unsupportedHinglish = DeviceCheckCopy.localeStatus(DeviceCheckLocale.UNSUPPORTED, english = false)
        val notCheckedEnglish = DeviceCheckCopy.localeStatus(DeviceCheckLocale.NOT_CHECKED, english = true)
        val notCheckedHinglish = DeviceCheckCopy.localeStatus(DeviceCheckLocale.NOT_CHECKED, english = false)

        assertEquals("Not supported", unsupportedEnglish)
        assertEquals("Support nahi mila", unsupportedHinglish)
        assertNotEquals(
            "an answered 'no' must not read like 'not checked' in English",
            notCheckedEnglish,
            unsupportedEnglish,
        )
        assertNotEquals(
            "an answered 'no' must not read like 'not checked' in Hinglish",
            notCheckedHinglish,
            unsupportedHinglish,
        )
    }

    /**
     * Acceptance 3 - a supported answer maps to SUPPORTED and has its own line in both languages.
     *
     * Guaranteed by [HindiLocaleSupportRules.outcomeForSupport],
     * [DeviceCheckLocaleRules.statusFor] and [DeviceCheckCopy.localeStatus].
     */
    @Test
    fun supportedAnswerMapsToSupportedWithItsOwnCopy() {
        assertEquals(
            HindiLocaleSupportOutcome.Supported,
            HindiLocaleSupportRules.outcomeForSupport(supported = true),
        )
        assertEquals(
            DeviceCheckLocale.SUPPORTED,
            DeviceCheckLocaleRules.statusFor(
                HindiLocaleSupportRules.outcomeForSupport(supported = true),
            ),
        )

        val supportedEnglish = DeviceCheckCopy.localeStatus(DeviceCheckLocale.SUPPORTED, english = true)
        val supportedHinglish = DeviceCheckCopy.localeStatus(DeviceCheckLocale.SUPPORTED, english = false)

        assertEquals("Supported", supportedEnglish)
        assertEquals("Support mila", supportedHinglish)
        assertNotEquals(
            DeviceCheckCopy.localeStatus(DeviceCheckLocale.UNSUPPORTED, english = true),
            supportedEnglish,
        )
        assertNotEquals(
            DeviceCheckCopy.localeStatus(DeviceCheckLocale.NOT_CHECKED, english = true),
            supportedEnglish,
        )
        assertNotEquals(supportedEnglish, supportedHinglish)
    }

    /**
     * Acceptance 4 - the row starts at NOT_CHECKED, and the check is offered only where the
     * platform can answer (API 33+). 36 is the compile/target SDK; 32 is the last version that cannot.
     *
     * Guaranteed by [DeviceCheckLocaleRules.initialStatus] and
     * [DeviceCheckLocaleRules.canCheckOnThisApiLevel] (which delegates to
     * [HindiLocaleSupportRules.probeAllowed]).
     */
    @Test
    fun initialStatusIsNotCheckedAndCheckAllowedOnlyFromApi33() {
        assertEquals(DeviceCheckLocale.NOT_CHECKED, DeviceCheckLocaleRules.initialStatus())
        assertEquals(
            "the pre-probe row must equal the 'could not ask' answer",
            DeviceCheckLocaleRules.statusFor(HindiLocaleSupportRules.outcomeWhenNotMeasurable()),
            DeviceCheckLocaleRules.initialStatus(),
        )

        assertFalse(DeviceCheckLocaleRules.canCheckOnThisApiLevel(32))
        assertTrue(DeviceCheckLocaleRules.canCheckOnThisApiLevel(33))
        assertTrue(DeviceCheckLocaleRules.canCheckOnThisApiLevel(36))
    }

    /**
     * Acceptance 5 - the invariant that keeps the Device check read-only: it asks a question and
     * never starts a recogniser session, on every Android version this app supports (minSdk 26).
     *
     * Guaranteed by [HindiLocaleSupportRules.startsRecognition], which returns false for every
     * apiLevel and is the machine-checked statement of that invariant.
     */
    @Test
    fun noRecognizerSessionStartsOnAnySupportedApiLevel() {
        val supportedApiLevels = listOf(26, 31, 32, 33, 36)
        for (apiLevel in supportedApiLevels) {
            assertFalse(
                "startsRecognition($apiLevel) must be false: the Device check never opens the microphone",
                HindiLocaleSupportRules.startsRecognition(apiLevel),
            )
        }
    }

    /**
     * Acceptance 6 - "no recogniser installed" and "microphone permission not granted" are two
     * different facts and must not share a line; and a snapshot keeps the locale value it was given
     * instead of inventing one.
     *
     * Guaranteed by [DeviceCheckCopy.availability], [DeviceCheckCopy.permission] and the plain
     * constructor of [DeviceCheckSnapshot]. The snapshot is built by hand with placeholder values so
     * no `Context`, `Build` or permission call is touched.
     */
    @Test
    fun availabilityAndPermissionAreNotConflated() {
        val unavailable = DeviceCheckCopy.availability(false, english = true)
        val notGranted = DeviceCheckCopy.permission(false, english = true)

        assertEquals("Unavailable", unavailable)
        assertEquals("Not granted", notGranted)
        assertNotEquals(
            "a missing recogniser and a denied microphone must not read the same",
            unavailable,
            notGranted,
        )

        val snapshot = DeviceCheckSnapshot(
            androidVersion = "test-version",
            apiLevel = 33,
            abi = "test-abi",
            recognizerAvailable = false,
            hindiLocale = DeviceCheckLocale.NOT_CHECKED,
            cameraPermission = false,
            microphonePermission = false,
        )

        assertEquals(
            "the locale row must stay 'not checked' when nothing was checked",
            DeviceCheckLocale.NOT_CHECKED,
            snapshot.hindiLocale,
        )
        assertNotEquals(DeviceCheckLocale.UNSUPPORTED, snapshot.hindiLocale)
        assertFalse(snapshot.recognizerAvailable)
        assertFalse(snapshot.microphonePermission)
        assertEquals(DeviceCheckCopy.availability(snapshot.recognizerAvailable, english = true), unavailable)
        assertEquals(DeviceCheckCopy.permission(snapshot.microphonePermission, english = true), notGranted)
    }
}
