package ai.trustshield.android.ui.qr

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import ai.trustshield.android.input.qr.QrGuardState
import ai.trustshield.android.input.qr.QrSimulationFlow
import ai.trustshield.android.input.qr.SimulatedQrFixtures
import ai.trustshield.android.ui.theme.TrustShieldTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented Compose tests for the QR Guard host (P3-14, issue #93).
 *
 * ## What is under test
 * [QrGuardHostContent] is the exact composable the shipped [QrGuardHost] renders; the host differs
 * only in that it also owns the CameraX scanner, the `PreviewView` and the permission launcher. These
 * tests render the content with `showPreview = false` and an injected [QrPermissionUi], so **no
 * camera, no permission dialog and no device hardware are involved** — the run is deterministic on an
 * emulator or on any CI device.
 *
 * ## What these tests prove
 *  - the SETUP screen offers manual UPI-id entry (the camera-denied fallback) and always shows the
 *    mandatory "pakka confirmation" line verbatim;
 *  - the **simulate** control drives the *real* B06 watcher + C06 reducer path: a matching demo ends
 *    in a match presentation, three consecutive mismatches raise the tamper banner with **masked** ids
 *    and "payment mat karein" copy, and an unreadable demo shows the occlusion copy while provably
 *    **not** rendering any tamper copy;
 *  - a denied permission still renders the rationale + the app-settings action, and the simulate path
 *    still works (a judge with no camera is never stuck on a dead screen);
 *  - the camera panel and the simulation control carry TalkBack content descriptions;
 *  - the pure permission decision and the demo/verdict mapping agree with P3-15's fixtures.
 *
 * ## What these tests cannot prove (reported as a gap in the PR)
 *  - that a real printed QR decodes through CameraX + bundled ML Kit on real hardware (needs a camera
 *    and a printed code; that is the orchestrator's emulator run and a physical-phone check);
 *  - that the permission launcher itself shows the system dialog (the launcher is Android-owned);
 *  - the 5-second re-register hold timing: a hold that long cannot be advanced reliably on the real
 *    clock inside an instrumentation test, so only "a plain tap does not open the gate" is asserted.
 *
 * Privacy: every fixture here is synthetic (`SimulatedQrFixtures`), and no assertion echoes a payload
 * or a raw UPI id into a failure message — ids are asserted in masked form only.
 */
@RunWith(AndroidJUnit4::class)
class QrGuardHostTest {

    @get:Rule
    val compose = createComposeRule()

    // ------------------------------------------------------------------ fixtures / render helpers

    private val granted = QrPermissionUi(
        state = QrPermissionState.GRANTED,
        action = QrPermissionAction.NONE
    )

    private val permanentlyDenied = QrPermissionUi(
        state = QrPermissionState.PERMANENTLY_DENIED,
        action = QrPermissionAction.OPEN_SETTINGS
    )

    /**
     * A session on a frozen clock: the demo path shifts its own clock forward per step, so every
     * timer (including the five-minute occlusion) resolves without any real waiting.
     */
    private fun newSession(): QrGuardSession = QrGuardSession(clock = { 0L })

    private fun render(
        session: QrGuardSession = newSession(),
        permission: QrPermissionUi = granted
    ): QrGuardSession {
        compose.setContent {
            TrustShieldTheme {
                QrGuardHostContent(
                    session = session,
                    permission = permission,
                    showPreview = false,
                    hapticsEnabled = false,
                    onRequestPermission = {},
                    onOpenSettings = {},
                )
            }
        }
        return session
    }

    /** Any node carrying [text] as a substring of its text or content description. */
    private fun nodesWith(text: String) = compose
        .onAllNodesWithText(text, substring = true, useUnmergedTree = true)
        .fetchSemanticsNodes()

    private fun assertVisible(text: String, because: String = "") {
        assertTrue("expected text not rendered: $text $because", nodesWith(text).isNotEmpty())
    }

    private fun assertAbsent(text: String, because: String = "") {
        assertTrue("text must not be rendered: $text $because", nodesWith(text).isEmpty())
    }

    private fun click(label: String) {
        compose.onNodeWithText(label).performClick()
        compose.waitForIdle()
    }

    // ------------------------------------------------------- 1. setup: manual entry + footer ----

    @Test
    fun setupRendersManualEntryAndMandatoryFooter() {
        render()

        // Manual UPI-id entry is the honest fallback when the camera is refused or absent.
        assertVisible(QrStrings.addPayeeHint.hinglish, "(manual entry field)")
        assertVisible(QrStrings.addPayee.hinglish, "(register action)")

        // The mandatory settlement line is verbatim and always present (BEHAVIOR_SPEC).
        assertVisible(QrStrings.mandatoryConfirmation.hinglish, "(mandatory footer)")

        // No shipped dev wording survives anywhere on the screen.
        assertAbsent("abhi juda nahi", "(seam note removed)")
    }

    @Test
    fun confirmedLiveQrShowsMaskedRegistrationAndDoesNotReopenCapture() {
        val session = render()
        val liveQr = "upi://pay?pa=demo-recipient@upi&pn=TRUSTSHIELD%20DEMO%20ONLY&am=0.00&cu=INR"
        session.onUiEvent(QrEvent.SetLanguage(QrLanguage.ENGLISH))

        session.onDecoded(liveQr)
        compose.waitForIdle()
        click(QrHostCopy.setupCaptureConfirm.english)

        assertEquals(1, session.snapshot().state.payees.size)
        assertVisible("Registered: dem***@upi", "(confirmed payee is visibly registered)")
        assertVisible(QrStrings.startWatch.english, "(primary start action)")
        assertVisible(QrStrings.addPayeeHint.english, "(another QR can be added)")
        assertVisible(QrStrings.registeredNextQr.english, "(another QR can be scanned)")
        assertAbsent("demo-recipient@upi", "(full UPI ID must stay hidden)")

        repeat(5) { session.onDecoded(liveQr) }
        compose.waitForIdle()
        assertEquals(1, session.snapshot().state.payees.size)
        assertEquals(null, session.snapshot().pendingSetup)
        assertAbsent(QrHostCopy.setupCaptureConfirm.english, "(registered QR cannot reopen setup confirmation)")
    }

    // ------------------------------------------------------------ 2. simulate: matching flow ----

    @Test
    fun simulateMatchDrivesMatchingPresentation() {
        val session = render()

        click(QrHostCopy.simulateMatch.hinglish)

        val state = session.snapshot().state
        assertEquals(QrPhase.WATCH, state.phase)
        assertEquals(0, state.consecutiveMismatches)
        assertEquals(QrSignal.MATCH, state.signal)

        assertVisible(QrSignalCopy.of(QrSignal.MATCH).label.hinglish, "(match banner)")
        // The demo is labelled as a simulation and never claims live decoding.
        assertVisible(QrHostCopy.simulationBadge.hinglish, "(simulation label)")
    }

    // ------------------------------------------------ 3. simulate: three mismatches -> tamper ----

    @Test
    fun simulateMismatchRaisesTamperBannerWithMaskedIds() {
        val session = render()

        click(QrHostCopy.simulateMismatch.hinglish)

        val state = session.snapshot().state
        assertEquals(QrSignal.MISMATCH, state.signal)
        assertTrue("three consecutive valid mismatches must raise the alert", state.isTamperEvidence)
        assertEquals(3, state.consecutiveMismatches)

        assertVisible(QrSignalCopy.of(QrSignal.MISMATCH).label.hinglish, "(tamper banner)")
        assertVisible(QrStrings.stopPaymentNow.hinglish, "(stop paying copy)")

        // Masked ids only: the banner may be read by TalkBack or screenshotted for the demo.
        assertVisible(maskPayeeId(SimulatedQrFixtures.STICKER_PAYEE), "(seen id, masked)")
        assertVisible(maskPayeeId(SimulatedQrFixtures.MERCHANT_PAYEE), "(expected id, masked)")
        assertAbsent(SimulatedQrFixtures.STICKER_PAYEE, "(raw payee must never reach the screen)")
        assertAbsent(SimulatedQrFixtures.MERCHANT_PAYEE, "(raw payee must never reach the screen)")
    }

    // ------------------------------------------------ 4. simulate: unreadable is never tamper ----

    @Test
    fun simulateUnreadableShowsOcclusionAndNeverTamperCopy() {
        val session = render()

        click(QrHostCopy.simulateUnreadable.hinglish)

        val state = session.snapshot().state
        assertEquals(QrSignal.UNREADABLE_TIMEOUT, state.signal)
        assertFalse("unreadable must never be tamper evidence", state.isTamperEvidence)
        assertEquals(0, state.consecutiveMismatches)

        assertVisible(QrSignalCopy.of(QrSignal.UNREADABLE_TIMEOUT).label.hinglish, "(occlusion label)")
        assertVisible(QrStrings.occlusionNotTamper.hinglish, "(mandatory not-tamper line)")

        // Provably not tamper: neither the alert label nor the stop-payment line may appear.
        assertAbsent(QrSignalCopy.of(QrSignal.MISMATCH).label.hinglish, "(no tamper banner)")
        assertAbsent(QrStrings.stopPaymentNow.hinglish, "(no stop-payment copy)")
    }

    // --------------------------------------------- 5. permission denied: rationale + recovery ----

    @Test
    fun permissionDeniedShowsRationaleAndSettingsActionAndSimulateStillWorks() {
        val session = render(permission = permanentlyDenied)

        assertVisible(QrHostCopy.permanentlyDeniedHelp.hinglish, "(denial rationale)")
        assertVisible(QrHostCopy.openSettings.hinglish, "(settings action)")
        // The manual fallback stays available: a denied camera is never a dead end.
        assertVisible(QrStrings.addPayeeHint.hinglish, "(manual entry still offered)")

        // The demo path is intentionally independent of the camera, so a judge with no permission can
        // still see the watcher work.
        click(QrHostCopy.simulateMatch.hinglish)

        val state = session.snapshot().state
        assertEquals("the watcher must have run", true, state.lastObserved?.matched)
        assertEquals(0, state.consecutiveMismatches)
        assertTrue("the demo must be labelled as a demo", session.snapshot().demoActive)
        // Honesty check: the camera really is blocked, and the screen keeps saying so.
        assertEquals(QrSignal.CAMERA_BLOCKED, state.signal)
        assertVisible(QrSignalCopy.of(QrSignal.CAMERA_BLOCKED).label.hinglish, "(camera blocked banner)")
    }

    // ------------------------------------------------------------------ 6. TalkBack-facing labels ----

    @Test
    fun cameraPanelAndSimulationControlHaveContentDescriptions() {
        render(permission = granted)

        compose
            .onNodeWithContentDescription(QrHostCopy.cameraPanelDescription(granted.state))
            .assertExists()
        compose
            .onNodeWithContentDescription(QrHostCopy.simulationControlDescription())
            .assertExists()
    }

    // -------------------------------------------------- 7. re-register is not a one-tap action ----

    @Test
    fun tappingReRegisterWithoutHoldingDoesNotOpenTheGate() {
        val session = render()
        click(QrHostCopy.simulateMatch.hinglish)

        // A plain touch on the hold control: injected as a raw tap, because the control deliberately
        // exposes no click action at all (a click action would open the gate without the hold).
        val holdDescription = QrStrings.reRegister.text(QrLanguage.HINGLISH) + ". " +
            QrStrings.reRegisterHold.text(QrLanguage.HINGLISH)
        compose.onNodeWithContentDescription(holdDescription)
            .performScrollTo()
            .performTouchInput { click() }
        compose.waitForIdle()

        val state = session.snapshot().state
        assertEquals("a tap must not open the PIN gate", QrPhase.WATCH, state.phase)
        assertFalse("a tap must not open the PIN gate", state.pinGate.open)
    }

    // ------------------------------------------------------- 8. pure permission decision matrix ----

    @Test
    fun permissionDecisionCoversEveryRecoveryPath() {
        // First entry, never asked: we may ask, and we show the rationale beside the action.
        assertEquals(
            QrPermissionUi(QrPermissionState.CAN_ASK, QrPermissionAction.ASK),
            QrPermissionPolicy.decide(granted = false, hasAsked = false, showRationale = false, hasCamera = true)
        )

        // Denied once, Android still shows rationale: we can ask again.
        assertEquals(
            QrPermissionUi(QrPermissionState.CAN_ASK, QrPermissionAction.ASK),
            QrPermissionPolicy.decide(granted = false, hasAsked = true, showRationale = true, hasCamera = true)
        )

        // Denied with "don't ask again": only the app's own settings page can recover it.
        assertEquals(
            QrPermissionUi(QrPermissionState.PERMANENTLY_DENIED, QrPermissionAction.OPEN_SETTINGS),
            QrPermissionPolicy.decide(granted = false, hasAsked = true, showRationale = false, hasCamera = true)
        )

        // No camera hardware at all: honest state, and no permission request is ever offered.
        assertEquals(
            QrPermissionUi(QrPermissionState.NO_CAMERA, QrPermissionAction.MANUAL_ENTRY_ONLY),
            QrPermissionPolicy.decide(granted = false, hasAsked = true, showRationale = false, hasCamera = false)
        )

        // Granted wins over everything else, including a stale rationale flag.
        assertEquals(
            QrPermissionUi(QrPermissionState.GRANTED, QrPermissionAction.NONE),
            QrPermissionPolicy.decide(granted = true, hasAsked = true, showRationale = true, hasCamera = true)
        )
    }

    // ------------------------------------- 9. demo path agrees with P3-15's canonical fixtures ----

    @Test
    fun demoPathAgreesWithSimulatedQrFixturesForEveryRequiredFlow() {
        for (flow in listOf(QrSimulationFlow.Match, QrSimulationFlow.Mismatch, QrSimulationFlow.Unreadable)) {
            val session = newSession()
            session.simulate(flow)

            // P3-15's own driver is the canonical script; the shipped screen must land in the same
            // B06 state, otherwise the demo would be lying about what the watcher does.
            val canonical = SimulatedQrFixtures.run(flow).finalStatus
            val state = session.snapshot().state

            assertEquals("mismatch count for ${flow.wire}", canonical.consecutiveMismatches, state.consecutiveMismatches)
            assertEquals(
                "tamper for ${flow.wire}",
                canonical.state == QrGuardState.Tampered,
                state.mismatchAlert != null
            )
            assertEquals(
                "occlusion for ${flow.wire}",
                canonical.state == QrGuardState.Occluded,
                state.occlusion?.timedOut == true
            )
        }
    }
}
