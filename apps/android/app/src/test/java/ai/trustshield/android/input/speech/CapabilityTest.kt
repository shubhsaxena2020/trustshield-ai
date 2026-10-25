package ai.trustshield.android.input.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier

/**
 * B04 tests. Pure JVM: no android.* type is constructed here, because android.jar bodies are stubs
 * that throw at runtime. Only the pure decision core and the consent state machine are executed.
 */
class CapabilityTest {

    private fun env(
        apiLevel: Int = 31,
        service: Boolean = true,
        onDevice: Boolean = true,
        model: Boolean = true,
        mic: MicPermission = MicPermission.Granted,
        hiInSupported: Boolean = true,
    ): SpeechEnvironment = SpeechEnvironment(
        apiLevel = apiLevel,
        recognizerServiceAvailable = service,
        onDeviceAvailable = onDevice,
        hiInModelDownloaded = model,
        micPermission = mic,
        hiInSupported = hiInSupported,
    )

    // ---------------------------------------------------------------- capability

    @Test
    fun api26WithServiceIsCloudOnlyAndNeverOffline() {
        val report = SpeechCapabilityRules.evaluate(env(apiLevel = 26, onDevice = false, model = false))
        assertEquals(SpeechStatus.CloudOnly, report.status)
        assertFalse(report.offlineCapable)
        assertTrue(report.cloudMayBeUsed)
        assertEquals(SpeechLanguage.HI_IN, report.languageTag)
        assertTrue(report.has(CapabilityReason.OnDeviceRequiresApi31))
        assertTrue(report.has(CapabilityReason.DefaultRecognizerMayUseNetwork))
        assertTrue(report.sessionPossible)
        // The cloud line may not be dressed up as private or on-device.
        assertEquals(SpeechCopy.needsInternet, SpeechLabels.statusFor(report))
    }

    @Test
    fun api26to30NeverClaimOfflineEvenWhenAPlatformFlagSaysOnDevice() {
        for (level in 26..30) {
            val report = SpeechCapabilityRules.evaluate(
                env(apiLevel = level, onDevice = true, model = true),
            )
            assertEquals("api $level", SpeechStatus.CloudOnly, report.status)
            assertFalse("api $level must not claim offline", report.offlineCapable)
            assertTrue("api $level may use the network path", report.cloudMayBeUsed)
        }
    }

    @Test
    fun api31WithOnDeviceAndHiInModelIsOnDeviceReady() {
        val report = SpeechCapabilityRules.evaluate(env(apiLevel = 31))
        assertEquals(SpeechStatus.OnDeviceReady, report.status)
        assertTrue(report.offlineCapable)
        assertFalse(report.cloudMayBeUsed)
        assertFalse(report.degraded)
        // An OS-reported capability is still not a device measurement.
        assertTrue(report.has(CapabilityReason.OnDeviceNotMeasured))
        assertNotNull(SpeechLabels.caveatFor(report))
    }

    @Test
    fun api31WithOnDeviceButMissingHiInModelIsDegradedOnDeviceUnavailable() {
        val report = SpeechCapabilityRules.evaluate(env(apiLevel = 31, model = false))
        assertEquals(SpeechStatus.OnDeviceUnavailable, report.status)
        assertFalse(report.offlineCapable)
        assertTrue(report.cloudMayBeUsed)
        assertTrue(report.degraded)
        assertTrue(report.has(CapabilityReason.HiInModelMissing))
        // The session stays possible, but the user sees a Hinglish note.
        assertTrue(report.sessionPossible)
        assertEquals(SpeechCopy.hindiModelMissing, SpeechLabels.statusFor(report))
    }

    @Test
    fun api31WithoutOnDeviceRecognizerFallsBackToCloud() {
        val report = SpeechCapabilityRules.evaluate(env(apiLevel = 31, onDevice = false))
        assertEquals(SpeechStatus.CloudOnly, report.status)
        assertFalse(report.offlineCapable)
        assertTrue(report.cloudMayBeUsed)
        assertTrue(report.has(CapabilityReason.OnDeviceNotAvailable))
    }

    @Test
    fun missingRecognizerServiceIsMissingService() {
        val report = SpeechCapabilityRules.evaluate(env(apiLevel = 31, service = false))
        assertEquals(SpeechStatus.MissingService, report.status)
        assertFalse(report.offlineCapable)
        assertFalse(report.cloudMayBeUsed)
        assertFalse(report.sessionPossible)
        assertTrue(report.has(CapabilityReason.NoRecognizerService))
        assertEquals(SpeechCopy.recognizerUnavailable, SpeechLabels.statusFor(report))
    }

    @Test
    fun hiInUnsupportedIsDegraded() {
        val report = SpeechCapabilityRules.evaluate(env(apiLevel = 31, hiInSupported = false))
        assertTrue(report.degraded)
        assertTrue(report.has(CapabilityReason.HiInLanguageUnsupported))
        assertFalse(report.offlineCapable)
        assertTrue(report.cloudMayBeUsed)
        assertEquals(SpeechCopy.languageNotConfirmed, SpeechLabels.statusFor(report))
    }

    @Test
    fun micDeniedIsNoMicPermission() {
        val report = SpeechCapabilityRules.evaluate(env(apiLevel = 31, mic = MicPermission.Denied))
        assertEquals(SpeechStatus.NoMicPermission, report.status)
        assertTrue(report.has(CapabilityReason.MicPermissionDenied))
        assertEquals(SpeechCopy.micPermissionMissing, SpeechLabels.statusFor(report))

        val decision = ConsentGate.check(
            SpeechConsent.initial().grant(),
            report,
        )
        assertFalse(decision.canStartSession)
        assertEquals(ConsentDecision.Block(ConsentBlockReason.MicPermissionDenied), decision)
    }

    @Test
    fun micPermanentlyDeniedKeepsItsOwnBlockReason() {
        val report = SpeechCapabilityRules.evaluate(
            env(apiLevel = 31, mic = MicPermission.PermanentlyDenied),
        )
        assertEquals(SpeechStatus.NoMicPermission, report.status)
        val decision = ConsentGate.check(SpeechConsent.initial().grant(), report)
        assertEquals(ConsentDecision.Block(ConsentBlockReason.MicPermissionPermanentlyDenied), decision)
        assertFalse(decision.canStartSession)
    }

    @Test
    fun micNotAskedYetIsDistinguishedFromDenied() {
        val report = SpeechCapabilityRules.evaluate(env(apiLevel = 31, mic = MicPermission.Unknown))
        assertEquals(SpeechStatus.NoMicPermission, report.status)
        assertTrue(report.has(CapabilityReason.MicPermissionNotAsked))
        // Different copy: "not asked yet" is not the same claim as "permission not given".
        assertEquals(SpeechCopy.micPermissionNotAsked, SpeechLabels.statusFor(report))
    }

    @Test
    fun belowMinSdkIsUnavailable() {
        val report = SpeechCapabilityRules.evaluate(env(apiLevel = 25))
        assertEquals(SpeechStatus.Unavailable, report.status)
        assertFalse(report.sessionPossible)
        assertTrue(report.has(CapabilityReason.ApiLevelUnsupported))
    }

    @Test
    fun unselectableLanguageDegradesAndBlankTagFallsBackToHindi() {
        val stranger = SpeechCapabilityRules.evaluate(env(), "fr-FR")
        assertTrue(stranger.degraded)
        assertTrue(stranger.has(CapabilityReason.LanguageNotSelectable))

        val blank = SpeechCapabilityRules.evaluate(env(), "   ")
        assertEquals(SpeechLanguage.HI_IN, blank.languageTag)
        assertFalse(blank.has(CapabilityReason.LanguageNotSelectable))
    }

    @Test
    fun englishSelectionKeepsTheSameHonestyRules() {
        val report = SpeechCapabilityRules.evaluate(env(apiLevel = 30, onDevice = false, model = false), "en-IN")
        assertEquals(SpeechLanguage.EN_IN, report.languageTag)
        assertFalse(report.offlineCapable)
        assertTrue(report.cloudMayBeUsed)
    }

    // ------------------------------------------------------------------- consent

    @Test
    fun consentStartsNotAskedAndBlocks() {
        val consent = SpeechConsent.initial()
        assertEquals(SpeechConsentState.NotAsked, consent.state)
        assertFalse(consent.canStartSession)
        val decision = ConsentGate.check(consent, SpeechCapabilityRules.evaluate(env(apiLevel = 31)))
        assertEquals(ConsentDecision.Block(ConsentBlockReason.ConsentNotAsked), decision)
    }

    @Test
    fun grantedConsentAllowsOnDeviceSession() {
        val consent = SpeechConsent.initial().grant()
        assertEquals(SpeechConsentState.Granted, consent.state)
        assertTrue(consent.canStartSession)
        val decision = ConsentGate.check(consent, SpeechCapabilityRules.evaluate(env(apiLevel = 31)))
        assertEquals(ConsentDecision.Allow, decision)
        assertTrue(decision.canStartSession)
    }

    @Test
    fun deniedConsentBlocks() {
        val consent = SpeechConsent.initial().deny()
        assertEquals(SpeechConsentState.Denied, consent.state)
        assertFalse(consent.canStartSession)
        val decision = ConsentGate.check(consent, SpeechCapabilityRules.evaluate(env(apiLevel = 31)))
        assertEquals(ConsentDecision.Block(ConsentBlockReason.ConsentDenied), decision)
    }

    @Test
    fun withdrawalClearsTheSession() {
        val withdrawn = SpeechConsent.initial().grant().withdraw()
        assertEquals(SpeechConsentState.Withdrawn, withdrawn.state)
        assertFalse(withdrawn.canStartSession)
        val decision = ConsentGate.check(withdrawn, SpeechCapabilityRules.evaluate(env(apiLevel = 31)))
        assertEquals(ConsentDecision.Block(ConsentBlockReason.ConsentWithdrawn), decision)
    }

    @Test
    fun staleEventsCannotResurrectConsent() {
        assertTrue(SpeechConsent.initial().grant().deny().canStartSession)
        assertEquals(SpeechConsentState.NotAsked, SpeechConsent.initial().withdraw().state)
        assertEquals(SpeechConsentState.Denied, SpeechConsent.initial().deny().grant().state)
        assertEquals(SpeechConsentState.Withdrawn, SpeechConsent.initial().grant().withdraw().withdraw().state)
        assertEquals(SpeechConsentState.Granted, SpeechConsent.initial().grant().reask().state)
    }

    @Test
    fun reaskIsTheOnlyWayBackFromARefusal() {
        val granted = SpeechConsent.initial().deny().reask().grant()
        assertEquals(SpeechConsentState.Granted, granted.state)
        assertTrue(granted.canStartSession)

        val restored = SpeechConsent.fromStored(SpeechConsentState.Withdrawn)
        assertEquals(SpeechConsentState.Withdrawn, restored.state)
        assertFalse(restored.canStartSession)
    }

    @Test
    fun consentRecordCarriesTheEnumAndNothingElse() {
        val instanceFields = SpeechConsent::class.java.declaredFields.filter {
            !Modifier.isStatic(it.modifiers) && !it.isSynthetic
        }
        assertEquals(1, instanceFields.size)
        assertEquals(SpeechConsentState::class.java, instanceFields[0].type)
        // The only serialisable value is the enum itself: no transcript, audio, timestamp or text.
        assertEquals(
            SpeechConsentState.Granted,
            SpeechConsent.initial().grant().recordedState(),
        )
        assertEquals("SpeechConsent(state=Granted)", SpeechConsent.initial().grant().toString())
    }

    @Test
    fun gateReportsMissingRecognizerBeforeAskingConsent() {
        val report = SpeechCapabilityRules.evaluate(env(service = false))
        val decision = ConsentGate.check(SpeechConsent.initial(), report)
        assertEquals(ConsentDecision.Block(ConsentBlockReason.RecognizerUnavailable), decision)
    }

    // ---------------------------------------------------------------------- copy

    @Test
    fun copyCarriesNoCertaintyLanguage() {
        val banned = listOf("100%", "guarantee", "bilkul", "pakka", "hamesha", "always", "never", "confirmed safe")
        for (line in SpeechCopy.all) {
            for (word in banned) {
                assertFalse(
                    "copy '${line.hinglish}' / '${line.english}' must not contain '$word'",
                    line.hinglish.contains(word, ignoreCase = true) ||
                        line.english.contains(word, ignoreCase = true),
                )
            }
        }
    }

    @Test
    fun requiredHinglishLinesExist() {
        val hinglish = SpeechCopy.all.map { it.hinglish }
        assertTrue(hinglish.contains("Awaaz saaf nahi"))
        assertTrue(hinglish.contains("On-device nahi, internet chahiye"))
        assertTrue(hinglish.contains("Mic permission nahi mili"))
        assertTrue(SpeechCopy.all.all { it.hinglish.isNotBlank() && it.english.isNotBlank() })
    }

    @Test
    fun cloudPathIsNeverCalledPrivateOrOnDevice() {
        val cloud = SpeechCapabilityRules.evaluate(env(apiLevel = 26, onDevice = false, model = false))
        val consent = SpeechLabels.consentFor(cloud)
        assertFalse(consent.english.contains("private", ignoreCase = true))
        assertFalse(consent.english.contains("on-device", ignoreCase = true))
        assertFalse(consent.hinglish.contains("phone ke andar", ignoreCase = true))
        assertTrue(consent.english.contains("may leave the device"))
        // The on-device line only appears for a verified on-device report.
        val device = SpeechCapabilityRules.evaluate(env(apiLevel = 31))
        assertEquals(SpeechCopy.consentAskOnDevice, SpeechLabels.consentFor(device))
    }

    @Test
    fun caveatIsNullWhenNothingExtraNeedsDisclosing() {
        val cloud = SpeechCapabilityRules.evaluate(env(apiLevel = 26, onDevice = false, model = false))
        assertNull(SpeechLabels.caveatFor(cloud))
    }
}
