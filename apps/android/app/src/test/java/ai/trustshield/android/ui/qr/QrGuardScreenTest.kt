package ai.trustshield.android.ui.qr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM reducer tests for the QR Guard (C06, issue #47).
 *
 * No Robolectric, no Compose, no instrumentation: `QrUiState.kt` has no `android.*` /
 * `androidx.compose.*` imports, so everything the screen decides can be asserted here. That is the
 * point of the state/renderer split — the app has no Compose UI-test dependency (Codex-owned).
 *
 * Run with:
 *   ./gradlew --no-daemon --init-script <agent3-testdeps.init.gradle.kts> testDebugUnitTest
 */
class QrGuardScreenTest {

    // A shop that takes PhonePe + Paytm, per report §7 ("Stand pe 2 QR").
    private val shopUpi = "dukaan.ravi@okaxis"
    private val secondUpi = "sharmakirana@ybl"

    // A different, plausible UPI id — what a swapped sticker would decode to.
    private val strangerUpi = "9876543210@ybl"

    private val policy: QrPolicy = QrPolicy.withPin("1234")

    // ------------------------------------------------------------------------------- fixtures ----

    /** Sets up a session the way the UI does: permission, register the real QR(s), start watching. */
    private fun watchOf(vararg payees: Pair<String, String?>): QrUiState {
        var s = QrReducer.reduce(QrUiState(), QrEvent.PermissionGranted, policy)
        for ((id, name) in payees) {
            s = QrReducer.reduce(s, QrEvent.RegisterPayee(id, name), policy)
        }
        return QrReducer.reduce(s, QrEvent.StartWatch(atMs = 0L), policy)
    }

    private fun watching(): QrUiState = watchOf(shopUpi to "Sharma Kirana")

    private fun QrUiState.frame(id: String, atMs: Long, name: String? = null): QrUiState =
        QrReducer.reduce(this, QrEvent.ValidFrame(id, name, atMs), policy)

    private fun QrUiState.noDecode(atMs: Long): QrUiState =
        QrReducer.reduce(this, QrEvent.UnreadableFrame(atMs), policy)

    private fun QrUiState.act(event: QrEvent): QrUiState = QrReducer.reduce(this, event, policy)

    // --------------------------------------------------------------------- 1. mismatch streak ----

    @Test
    fun threeConsecutiveValidMismatchesRaiseTheAlert() {
        var s = watching()
        s = s.frame(strangerUpi, 1_000L)
        assertEquals(1, s.consecutiveMismatches)
        assertNull("one mismatch must not alert", s.mismatchAlert)
        assertEquals(QrSignal.IDLE, s.signal)

        s = s.frame(strangerUpi, 2_000L)
        assertEquals(2, s.consecutiveMismatches)
        assertNull("two mismatches must not alert", s.mismatchAlert)

        s = s.frame(strangerUpi, 3_000L)
        assertEquals(3, s.consecutiveMismatches)
        assertEquals(QrSignal.MISMATCH, s.signal)
        assertTrue("three valid mismatches are tamper evidence", s.isTamperEvidence)

        val alert = s.mismatchAlert
        assertNotNull("third consecutive valid mismatch must raise the alert", alert)
        requireNotNull(alert)
        assertEquals(3, alert.consecutive)
        assertEquals(3, alert.threshold)
    }

    @Test
    fun alertFreezesFurtherEvidenceUntilAcknowledged() {
        var s = watching()
        s = s.frame(strangerUpi, 1_000L)
        s = s.frame(strangerUpi, 2_000L)
        s = s.frame(strangerUpi, 3_000L)
        assertEquals(3, s.consecutiveMismatches)

        // Frames keep arriving from the decode loop; the raised alert must stay stable and readable.
        s = s.frame(strangerUpi, 4_000L)
        assertEquals(3, s.consecutiveMismatches)
        assertNotNull(s.mismatchAlert)

        s = s.act(QrEvent.AcknowledgeMismatch)
        assertNull(s.mismatchAlert)
        assertEquals(0, s.consecutiveMismatches)
        assertEquals(QrPhase.WATCH, s.phase)
    }

    @Test
    fun aValidMatchResetsTheMismatchCounter() {
        var s = watching()
        s = s.frame(strangerUpi, 1_000L)
        s = s.frame(strangerUpi, 2_000L)
        assertEquals(2, s.consecutiveMismatches)

        s = s.frame(shopUpi, 3_000L, "Sharma Kirana")
        assertEquals("a real match resets the streak", 0, s.consecutiveMismatches)
        assertEquals(QrSignal.MATCH, s.signal)
        assertNull(s.occlusion)
        assertTrue(requireNotNull(s.lastObserved).matched)

        // And the count restarts from zero, not from the old streak.
        s = s.frame(strangerUpi, 4_000L)
        assertEquals(1, s.consecutiveMismatches)
        assertNull(s.mismatchAlert)
    }

    // ------------------------------------------------------- 2. unreadable is never tamper ----

    @Test
    fun unreadableFrameNeitherCountsNorResetsTamperEvidence() {
        var s = watching()
        s = s.frame(strangerUpi, 1_000L)
        s = s.frame(strangerUpi, 2_000L)
        assertEquals(2, s.consecutiveMismatches)

        // Glare / sticker half-on / covered lens: many frames, all before the 5-minute budget.
        var t = 3_000L
        repeat(10) {
            s = s.noDecode(t)
            assertEquals(
                "a no-decode frame must never change the mismatch count",
                2,
                s.consecutiveMismatches
            )
            assertNull("no-decode must never raise the mismatch alert", s.mismatchAlert)
            assertFalse("no-decode is not tamper evidence", s.isTamperEvidence)
            t += 1_000L
        }

        // The evidence already collected still counts: the very next valid mismatch is the third.
        s = s.frame(strangerUpi, t)
        assertEquals(3, s.consecutiveMismatches)
        assertEquals(QrSignal.MISMATCH, s.signal)
        assertNotNull(s.mismatchAlert)
    }

    @Test
    fun noDecodeNeverEscalatesToATamperVerdict() {
        var s = watching()
        // An hour of nothing readable, well past the occlusion budget.
        var t = 1_000L
        repeat(120) {
            s = s.noDecode(t)
            t += 30_000L
        }
        assertEquals("no decode must not invent mismatches", 0, s.consecutiveMismatches)
        assertNull(s.mismatchAlert)
        assertFalse("unreadable is not a tamper verdict", s.isTamperEvidence)
        assertEquals(QrSignal.UNREADABLE_TIMEOUT, s.signal)
    }

    @Test
    fun fiveMinutesOfOcclusionYieldsUnreadableNotTampered() {
        var s = watching()
        s = s.frame(strangerUpi, 1_000L)
        assertEquals(1, s.consecutiveMismatches)

        // Timer starts at the first no-decode frame.
        s = s.noDecode(10_000L)
        val justStarted = requireNotNull(s.occlusion)
        assertFalse("timer must not fire immediately", justStarted.timedOut)
        assertEquals(QrSignal.IDLE, s.signal)

        // 4:59 later: still just waiting.
        s = s.noDecode(10_000L + 299_999L)
        assertFalse(requireNotNull(s.occlusion).timedOut)
        assertEquals(QrSignal.IDLE, s.signal)

        // 5:00 exactly: occluded/unreadable — explicitly not tampered.
        s = s.noDecode(10_000L + 300_000L)
        assertTrue(requireNotNull(s.occlusion).timedOut)
        assertEquals(QrSignal.UNREADABLE_TIMEOUT, s.signal)
        assertFalse(s.signal.isTamperEvidence)
        assertNull("occlusion is not a mismatch alert", s.mismatchAlert)
        assertEquals("the streak is paused, not reset", 1, s.consecutiveMismatches)

        // A decode that finally works clears the timer.
        s = s.frame(shopUpi, 10_000L + 301_000L, "Sharma Kirana")
        assertNull(s.occlusion)
        assertEquals(QrSignal.MATCH, s.signal)
        assertEquals(0, s.consecutiveMismatches)
    }

    @Test
    fun occlusionTimerAlsoAdvancesOnTicksWithoutFrames() {
        var s = watching()
        s = s.noDecode(1_000L)
        s = QrReducer.reduce(s, QrEvent.Tick(1_000L + 300_000L), policy)
        assertEquals(QrSignal.UNREADABLE_TIMEOUT, s.signal)
        assertFalse(s.isTamperEvidence)
    }

    @Test
    fun malformedDecodesAreTreatedAsNoDecodeNotAsMismatch() {
        var s = watching()
        val garbage = listOf("", "   ", "hello", "a@b", "two@at@okaxis", "no-at-sign", "x y@okaxis")
        for (bad in garbage) {
            s = s.frame(bad, 1_000L)
        }
        assertEquals("garbage decodes are not evidence", 0, s.consecutiveMismatches)
        assertNull(s.mismatchAlert)
        assertNotNull("garbage decodes start the occlusion timer instead", s.occlusion)
        assertFalse(s.isTamperEvidence)
    }

    @Test
    fun upiIdValidationAcceptsRealHandlesAndRejectsJunk() {
        assertTrue(isPlausibleUpiId("dukaan.ravi@okaxis"))
        assertTrue(isPlausibleUpiId("9876543210@ybl"))
        assertTrue(isPlausibleUpiId("shop-name_1@paytm"))
        assertFalse(isPlausibleUpiId(""))
        assertFalse(isPlausibleUpiId("@okaxis"))
        assertFalse(isPlausibleUpiId("shop@"))
        assertFalse(isPlausibleUpiId("shop@a"))
        assertFalse(isPlausibleUpiId("shop@@okaxis"))
        assertFalse(isPlausibleUpiId("shop name@okaxis"))
        assertFalse(isPlausibleUpiId("shop@ok axis"))
    }

    // ------------------------------------------------------------------ 3. dual merchants ----

    @Test
    fun multipleRegisteredPayeesAreMatchedCorrectly() {
        var s = watchOf(shopUpi to "Sharma Kirana", secondUpi to "Sharma Kirana")
        assertEquals(2, s.payees.size)

        // PhonePe QR (the second registered payee) matches — no mismatch, no alert.
        s = s.frame(secondUpi, 1_000L)
        assertTrue(requireNotNull(s.lastObserved).matched)
        assertEquals(0, s.consecutiveMismatches)
        assertEquals(QrSignal.MATCH, s.signal)

        // Paytm QR (the first registered payee) matches too.
        s = s.frame(shopUpi, 2_000L)
        assertTrue(requireNotNull(s.lastObserved).matched)
        assertEquals(0, s.consecutiveMismatches)

        // A third id that nobody registered is the mismatch case.
        s = s.frame(strangerUpi, 3_000L)
        assertFalse(requireNotNull(s.lastObserved).matched)
        assertEquals(1, s.consecutiveMismatches)
        assertNull(s.mismatchAlert)
    }

    @Test
    fun caseAndWhitespaceVariantsOfARegisteredPayeeStillMatch() {
        var s = watching()
        s = s.frame("  Dukaan.Ravi@OKAXIS  ", 1_000L)
        assertTrue(requireNotNull(s.lastObserved).matched)
        assertEquals(0, s.consecutiveMismatches)
    }

    // ------------------------------------------------------------------------ 4. PIN gate ----

    @Test
    fun pinGateBlocksReRegisterWithoutThePinAndAllowsItWithIt() {
        var s = watching()
        val replacement = "dukaan.ravi.new@okicici"

        // Ask to re-register: the overlay opens but the gate is shut.
        s = s.act(QrEvent.RequestReRegister)
        assertEquals(QrPhase.RE_REGISTER, s.phase)
        assertFalse(s.pinGate.open)

        // No PIN -> the registration is silently refused (and never half-applied).
        val before = s.payees
        s = s.act(QrEvent.RegisterPayee(replacement, "Sharma Kirana"))
        assertEquals("re-register without the PIN must not change the payee list", before, s.payees)

        // Wrong PIN -> still shut, still nothing registered.
        s = s.act(QrEvent.SubmitPin("0000"))
        assertFalse(s.pinGate.open)
        assertEquals(QrPinError.WRONG_PIN, s.pinGate.lastError)
        s = s.act(QrEvent.RegisterPayee(replacement, "Sharma Kirana"))
        assertEquals(before, s.payees)

        // Correct PIN -> gate opens and the new expected QR can be registered.
        s = s.act(QrEvent.SubmitPin("1234"))
        assertTrue("correct PIN must open the gate", s.pinGate.open)
        assertEquals(QrPinError.NONE, s.pinGate.lastError)

        s = s.act(QrEvent.RegisterPayee(replacement, "Sharma Kirana"))
        assertEquals(2, s.payees.size)
        assertTrue(s.payees.any { it.handle == replacement })

        // And the watcher accepts the new payee immediately.
        s = s.act(QrEvent.CloseReRegister)
        assertEquals(QrPhase.WATCH, s.phase)
        assertFalse(s.pinGate.open)
        s = s.frame(replacement, 5_000L)
        assertTrue(requireNotNull(s.lastObserved).matched)
    }

    @Test
    fun reRegisteringRequiresThePinEvenForRemoval() {
        var s = watching()
        s = s.act(QrEvent.RequestReRegister)
        s = s.act(QrEvent.RemovePayee(shopUpi))
        assertEquals("removal is gated too", 1, s.payees.size)

        s = s.act(QrEvent.SubmitPin("1234"))
        s = s.act(QrEvent.RemovePayee(shopUpi))
        assertEquals("removal works once the gate is open", 0, s.payees.size)
    }

    @Test
    fun pinLockoutClosesTheGateAfterRepeatedWrongPins() {
        var s = watching()
        s = s.act(QrEvent.RequestReRegister)
        repeat(3) { s = s.act(QrEvent.SubmitPin("9999")) }

        assertTrue(s.pinGate.lockedOut)
        assertFalse(s.pinGate.open)
        assertEquals(QrPinError.LOCKED_OUT, s.pinGate.lastError)
        assertEquals("lockout drops back to watching", QrPhase.WATCH, s.phase)

        // Even the right PIN cannot get through a lockout.
        s = s.act(QrEvent.RequestReRegister)
        s = s.act(QrEvent.SubmitPin("1234"))
        assertFalse(s.pinGate.open)
        assertEquals(QrPinError.LOCKED_OUT, s.pinGate.lastError)
    }

    @Test
    fun pinGateIsNotConfiguredHonestly() {
        // No PIN configured: the screen must say so instead of silently allowing a re-register.
        val noPin = QrPolicy()
        var s = QrReducer.reduce(QrUiState(), QrEvent.PermissionGranted, noPin)
        s = QrReducer.reduce(s, QrEvent.RegisterPayee(shopUpi, "Sharma Kirana"), noPin)
        s = QrReducer.reduce(s, QrEvent.StartWatch(0L), noPin)
        assertEquals(QrPhase.WATCH, s.phase)

        s = QrReducer.reduce(s, QrEvent.RequestReRegister, noPin)
        assertEquals("no PIN -> no re-register overlay", QrPhase.WATCH, s.phase)
        assertEquals(QrPinError.NOT_CONFIGURED, s.pinGate.lastError)
        assertEquals(QrGuidance.PIN_NOT_CONFIGURED, s.guidance)

        s = QrReducer.reduce(s, QrEvent.SubmitPin("1234"), noPin)
        assertFalse(s.pinGate.open)
        s = QrReducer.reduce(s, QrEvent.RegisterPayee(strangerUpi, null), noPin)
        assertEquals(1, s.payees.size)
    }

    @Test
    fun openingThePinGateDoesNotDisturbWatcherEvidence() {
        var s = watching()
        s = s.frame(strangerUpi, 1_000L)
        s = s.frame(strangerUpi, 2_000L)
        s = s.noDecode(3_000L)
        val occlusionBefore = s.occlusion
        assertEquals(2, s.consecutiveMismatches)

        s = s.act(QrEvent.RequestReRegister)
        assertEquals(QrPhase.RE_REGISTER, s.phase)
        assertEquals("the watcher keeps its evidence", 2, s.consecutiveMismatches)
        assertEquals("the occlusion timer keeps running", occlusionBefore, s.occlusion)

        // Frames keep arriving while the PIN is being typed — and still count.
        s = s.frame(strangerUpi, 4_000L)
        assertEquals(3, s.consecutiveMismatches)
    }

    @Test
    fun aRisingAlertForceClosesAnOpenPinGate() {
        var s = watching()
        s = s.act(QrEvent.RequestReRegister)
        s = s.act(QrEvent.SubmitPin("1234"))
        assertTrue(s.pinGate.open)

        s = s.frame(strangerUpi, 1_000L)
        s = s.frame(strangerUpi, 2_000L)
        s = s.frame(strangerUpi, 3_000L)
        assertNotNull(s.mismatchAlert)
        assertEquals("tamper alert returns to the watch screen", QrPhase.WATCH, s.phase)
        assertFalse("nobody edits the expected QR mid-alert", s.pinGate.open)
    }

    // ------------------------------------------------------------- 5. masking / no leaking ----

    @Test
    fun payeeIdIsShownMaskedOnly() {
        val s = watching()
        val payee = s.payees.single()
        val full = payee.handle

        assertEquals(shopUpi, full) // the model keeps it for comparison
        assertTrue("display form must be masked", payee.maskedHandle.contains("***"))
        assertFalse("display form must not contain the full id", payee.maskedHandle.contains(full))
        assertEquals("duk***@okaxis", payee.maskedHandle)

        // Nothing that can be logged or read out carries the full id.
        assertFalse(s.toString().contains(full))
        assertFalse(payee.toString().contains(full))
        assertFalse(ObservedQr(shopUpi, "Sharma Kirana", true, 1L).toString().contains(full))
        assertFalse(s.expectedMaskedJoined.contains(full))

        // The alert renders masked ids only, for both sides of the comparison.
        var alertState = s.frame(strangerUpi, 1_000L)
        alertState = alertState.frame(strangerUpi, 2_000L)
        alertState = alertState.frame(strangerUpi, 3_000L)
        val alert = requireNotNull(alertState.mismatchAlert)
        assertFalse(alert.toString().contains(strangerUpi))
        assertFalse(alert.observedMasked.contains(strangerUpi))
        assertTrue(alert.observedMasked.contains("***"))
        assertFalse(alert.expectedMasked.any { it.contains(shopUpi) })
        assertFalse(alertState.toString().contains(shopUpi))
    }

    @Test
    fun maskNeverRevealsTheWholeLocalPart() {
        val cases = listOf(
            "a@okaxis", "ab@okaxis", "abc@okaxis", "abcd@okaxis", "abcde@okaxis",
            "abcdef@okaxis", "abcdefghij@okaxis", "9876543210@ybl", "dukaan.ravi@okaxis"
        )
        for (id in cases) {
            val masked = maskPayeeId(id)
            assertTrue("$id -> $masked must be masked", masked.contains("***"))
            assertFalse("$id -> $masked leaks the id", masked.contains(id))
            val local = id.substringBefore('@')
            assertFalse(
                "$id -> $masked leaks the whole local part",
                local.length > 1 && masked.contains(local)
            )
        }
        assertEquals("***", maskPayeeId(""))
        assertEquals("duk***@okaxis", maskPayeeId("dukaan.ravi@okaxis"))
        assertEquals("987***@ybl", maskPayeeId("9876543210@ybl"))
    }

    // ------------------------------------------------------------------- 6. teardown/lifecycle ----

    @Test
    fun stopClearsTransientPayloadButKeepsTheRegisteredPayee() {
        var s = watching()
        s = s.frame(strangerUpi, 1_000L)
        s = s.frame(strangerUpi, 2_000L)
        s = s.noDecode(3_000L)

        s = s.act(QrEvent.Stop)
        assertEquals(QrPhase.STOPPED, s.phase)
        assertEquals(0, s.consecutiveMismatches)
        assertNull(s.occlusion)
        assertNull(s.lastObserved)
        assertNull(s.mismatchAlert)
        assertFalse(s.pinGate.open)
        assertEquals("registered expectations survive a stop", 1, s.payees.size)
        assertEquals(QrSignal.STOPPED, s.signal)
        assertFalse(s.isTamperEvidence)
    }

    @Test
    fun startWatchNeedsACameraAndAtLeastOnePayee() {
        // No payee yet.
        var s = QrReducer.reduce(QrUiState(), QrEvent.PermissionGranted, policy)
        s = s.act(QrEvent.StartWatch(0L))
        assertEquals("cannot watch with nothing to compare", QrPhase.SETUP, s.phase)
        assertEquals(QrGuidance.REGISTER_PAYEE, s.guidance)

        // Payee registered but no camera permission.
        s = QrReducer.reduce(QrUiState(), QrEvent.PermissionDenied, policy)
        s = s.act(QrEvent.RegisterPayee(shopUpi, "Sharma Kirana"))
        s = s.act(QrEvent.StartWatch(0L))
        assertEquals(QrPhase.SETUP, s.phase)
        assertEquals(QrGuidance.GRANT_CAMERA_PERMISSION, s.guidance)

        // Now it works.
        s = s.act(QrEvent.PermissionGranted)
        s = s.act(QrEvent.StartWatch(0L))
        assertEquals(QrPhase.WATCH, s.phase)
    }

    @Test
    fun noCameraDeviceGuidesInsteadOfWatching() {
        var s = watching()
        s = s.act(QrEvent.NoCamera)
        assertEquals(CameraUiStatus.NO_CAMERA, s.camera)
        assertEquals(QrSignal.CAMERA_BLOCKED, s.signal)
        assertEquals(QrGuidance.NO_CAMERA, s.guidance)
        assertFalse("a missing camera is not tamper", s.isTamperEvidence)
    }

    @Test
    fun revokedPermissionPausesWithoutResettingEvidence() {
        var s = watching()
        s = s.frame(strangerUpi, 1_000L)
        s = s.frame(strangerUpi, 2_000L)

        s = s.act(QrEvent.PermissionDenied)
        assertEquals(CameraUiStatus.PERMISSION_DENIED, s.camera)
        assertEquals("evidence survives a revoked permission", 2, s.consecutiveMismatches)
        assertNull(s.occlusion)
        assertEquals(QrSignal.CAMERA_BLOCKED, s.signal)
        assertFalse(s.isTamperEvidence)
        assertEquals(QrGuidance.GRANT_CAMERA_PERMISSION, s.guidance)
    }

    @Test
    fun framesAreIgnoredWhenNotWatching() {
        // SETUP: a stray frame from a half-started session must not create evidence.
        var s = QrReducer.reduce(QrUiState(), QrEvent.PermissionGranted, policy)
        s = s.act(QrEvent.RegisterPayee(shopUpi, "Sharma Kirana"))
        s = s.frame(strangerUpi, 1_000L)
        assertEquals(0, s.consecutiveMismatches)

        // STOPPED: nothing accumulates after Stop.
        s = s.act(QrEvent.StartWatch(0L))
        s = s.frame(strangerUpi, 2_000L)
        assertEquals(1, s.consecutiveMismatches)
        s = s.act(QrEvent.Stop)
        s = s.frame(strangerUpi, 3_000L)
        assertEquals(0, s.consecutiveMismatches)
        assertNull(s.lastObserved)
    }

    // ------------------------------------------------------------------------------ 7. copy ----

    @Test
    fun mandatoryConfirmationLineIsVerbatimAndBilingual() {
        val line = QrStrings.mandatoryConfirmation
        assertEquals(
            "Pakka confirmation sirf aapke bank SMS ya UPI app ki history mein milega.",
            line.hinglish
        )
        assertTrue(line.english.isNotBlank())
        assertEquals(line.hinglish, line.text(QrLanguage.HINGLISH))
        assertEquals(line.english, line.text(QrLanguage.ENGLISH))
        assertEquals(QrLanguage.HINGLISH, QrUiState().language)
    }

    @Test
    fun languageToggleSwitchesTheWholeScreen() {
        var s = QrUiState()
        assertEquals(QrLanguage.HINGLISH, s.language)
        assertEquals(QrStrings.title.hinglish, QrStrings.title.text(s.language))
        s = s.act(QrEvent.ToggleLanguage)
        assertEquals(QrLanguage.ENGLISH, s.language)
        assertEquals(QrStrings.title.english, QrStrings.title.text(s.language))
        s = s.act(QrEvent.SetLanguage(QrLanguage.HINGLISH))
        assertEquals(QrLanguage.HINGLISH, s.language)
    }

    @Test
    fun mismatchAndUnreadableAreVisuallyDistinctStates() {
        val mismatch = QrSignalCopy.of(QrSignal.MISMATCH)
        val unreadable = QrSignalCopy.of(QrSignal.UNREADABLE_TIMEOUT)

        assertEquals(QrTone.RED, mismatch.tone)
        assertEquals(QrTone.AMBER, unreadable.tone)
        assertTrue(
            "the two states must not share a label",
            mismatch.label.hinglish != unreadable.label.hinglish
        )
        assertTrue(mismatch.label.hinglish.isNotBlank() && mismatch.detail.hinglish.isNotBlank())
        assertTrue(unreadable.label.hinglish.isNotBlank() && unreadable.detail.hinglish.isNotBlank())
        assertTrue("icon channel present", mismatch.glyph.isNotBlank() && unreadable.glyph.isNotBlank())
        assertEquals("QR nahi dikh raha", unreadable.label.hinglish)

        // Every signal satisfies label + icon + text (never colour-only).
        for (signal in QrSignal.values()) {
            assertTrue("$signal must be self-describing", QrSignalCopy.isSelfDescribing(signal))
        }
        // Only mismatch is ever called tampering.
        assertEquals(
            listOf(QrSignal.MISMATCH),
            QrSignal.values().filter { it.isTamperEvidence }
        )
    }

    @Test
    fun unreadableCopyExplicitlySaysItIsNotTamper() {
        val copy = QrSignalCopy.of(QrSignal.UNREADABLE_TIMEOUT)
        val lower = copy.detail.hinglish.lowercase()
        assertTrue("must say 5 minutes", copy.detail.hinglish.contains("5 minute"))
        assertTrue("must say it is not proof of tampering", lower.contains("tamper ka saboot nahi"))
        assertTrue(QrStrings.occlusionNotTamper.hinglish.lowercase().contains("tamper"))
    }

    @Test
    fun mismatchCopyNeverClaimsAbsoluteSafety() {
        for (presentation in QrSignalCopy.all) {
            assertFalse(presentation.detail.hinglish.contains("100%"))
            assertFalse(presentation.detail.english.contains("100%"))
            assertFalse(presentation.label.hinglish.contains("safe", ignoreCase = true))
            assertFalse(presentation.label.english.contains("safe", ignoreCase = true))
        }
        // Even a match must leave the final confirmation to the bank.
        val match = QrSignalCopy.of(QrSignal.MATCH)
        assertTrue(match.detail.hinglish.contains("khud check"))
    }

    @Test
    fun removingEveryPayeeDropsBackToSetup() {
        var s = watching()
        s = s.act(QrEvent.RequestReRegister)
        s = s.act(QrEvent.SubmitPin("1234"))
        s = s.act(QrEvent.RemovePayee(shopUpi))
        assertEquals(0, s.payees.size)
        s = s.act(QrEvent.CloseReRegister)
        assertEquals(QrPhase.SETUP, s.phase)
        assertEquals(QrSignal.NOT_READY, s.signal)
        assertFalse(s.canStartWatch)
    }
}
