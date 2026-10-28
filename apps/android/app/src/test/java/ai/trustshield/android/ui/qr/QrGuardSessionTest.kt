package ai.trustshield.android.ui.qr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QrGuardSessionTest {

    private val demoQr = "upi://pay?pa=demo-recipient@upi&pn=TRUSTSHIELD%20DEMO%20ONLY&am=0.00&cu=INR"
    private val otherQr = "upi://pay?pa=demo-other@upi&pn=Other&am=0.00&cu=INR"

    @Test
    fun confirmedSetupIgnoresRepeatedFramesAndWatchesRegisteredPayee() {
        val session = QrGuardSession(clock = { 0L })
        session.applyPermission(QrPermissionUi(QrPermissionState.GRANTED, QrPermissionAction.NONE))

        session.onDecoded(demoQr)
        session.confirmSetupCapture()

        assertEquals(QrPhase.SETUP, session.snapshot().state.phase)
        assertEquals("dem***@upi", session.snapshot().state.payees.single().maskedHandle)
        assertEquals("Registered: {id}", QrStrings.registeredPayee.english)
        assertEquals("Register ho gaya: {id}", QrStrings.registeredPayee.hinglish)
        assertEquals("Start watching", QrStrings.startWatch.english)
        assertEquals("Nazar shuru karein", QrStrings.startWatch.hinglish)
        assertEquals("Scan another QR or start watching.", QrStrings.registeredNextQr.english)
        assertEquals("Ek aur QR scan karein ya nazar shuru karein.", QrStrings.registeredNextQr.hinglish)

        repeat(5) { session.onDecoded(demoQr) }
        assertNull("a registered QR must not reopen setup confirmation", session.snapshot().pendingSetup)
        assertEquals(1, session.snapshot().state.payees.size)

        session.onUiEvent(QrEvent.StartWatch())
        session.onDecoded(demoQr)
        assertEquals(QrSignal.MATCH, session.snapshot().state.signal)
        assertTrue(requireNotNull(session.snapshot().state.lastObserved).matched)

        repeat(3) { session.onDecoded(otherQr) }
        assertEquals(QrSignal.MISMATCH, session.snapshot().state.signal)
        assertEquals(3, session.snapshot().state.consecutiveMismatches)
        assertTrue(session.snapshot().state.isTamperEvidence)
    }

    @Test
    fun startWatchResetsOcclusionTimerAfterLongSetup() {
        var now = 0L
        val session = QrGuardSession(clock = { now })
        session.applyPermission(QrPermissionUi(QrPermissionState.GRANTED, QrPermissionAction.NONE))
        session.onDecoded(demoQr)
        session.confirmSetupCapture()
        now = 400_000L
        session.onUiEvent(QrEvent.StartWatch())
        session.tick()
        assertFalse(session.snapshot().state.signal == QrSignal.UNREADABLE_TIMEOUT)
        assertNull(session.snapshot().state.occlusion)
    }

    @Test
    fun noPinIsReportedInsteadOfAcceptingWrongPinAttempts() {
        val session = registeredSession()
        session.onUiEvent(QrEvent.StartWatch())
        session.onUiEvent(QrEvent.RequestReRegister)
        session.onUiEvent(QrEvent.SubmitPin("1234"))
        assertEquals(QrPinError.NOT_CONFIGURED, session.snapshot().state.pinGate.lastError)
        assertFalse(session.snapshot().state.pinGate.lockedOut)
    }

    @Test
    fun stopClearsThePaymentAdvisory() {
        val session = registeredSession()
        session.onUiEvent(QrEvent.StartWatch())
        session.onDecoded("upi://pay?pa=demo-recipient@upi&pn=TRUSTSHIELD&am=125.00&cu=INR")
        assertTrue(session.snapshot().paymentAdvisory != null)
        session.onUiEvent(QrEvent.Stop)
        assertNull(session.snapshot().paymentAdvisory)
    }

    @Test
    fun malformedPayeeDoesNotCreatePaymentAdvisory() {
        val session = registeredSession()
        session.onUiEvent(QrEvent.StartWatch())
        session.onDecoded("upi://pay?pa=a@b&am=5000&cu=INR")
        assertNull(session.snapshot().paymentAdvisory)
        assertFalse(session.snapshot().state.isTamperEvidence)
    }

    @Test
    fun setupDoesNotOfferPayeeThatReducerWillReject() {
        val session = QrGuardSession(clock = { 0L })
        session.applyPermission(QrPermissionUi(QrPermissionState.GRANTED, QrPermissionAction.NONE))
        session.onDecoded("upi://pay?pa=${"a".repeat(70)}@okaxis")
        assertNull(session.snapshot().pendingSetup)
    }

    @Test
    fun qrNoteDoesNotTriggerAccompanyingTextUrgencyRule() {
        val session = registeredSession()
        session.onUiEvent(QrEvent.StartWatch())
        session.onDecoded("upi://pay?pa=demo-recipient@upi&pn=TRUSTSHIELD&am=125.00&tn=jaldi+abhi&cu=INR")
        val advisory = requireNotNull(session.snapshot().paymentAdvisory)
        assertFalse(advisory.hints.any { it.kind == ai.trustshield.android.core.payment.PaymentHintKind.URGENCY_PRESSURE })
    }

    @Test
    fun prefilledAmountAdvisoryRetainsMaskedPayeeAndAmount() {
        val session = registeredSession()
        session.onUiEvent(QrEvent.StartWatch())
        session.onDecoded("upi://pay?pa=demo-recipient@upi&pn=TRUSTSHIELD&am=125.00&cu=INR")
        val fields = requireNotNull(session.snapshot().paymentAdvisory).fields
        assertEquals("dem***@upi", fields.payeeUpi)
        assertEquals(12_500L, fields.amountMinor)
    }

    @Test
    fun watcherAndAdvisoryAgreeOnCaseInsensitivePayeeIdentity() {
        val session = QrGuardSession(clock = { 0L })
        session.applyPermission(QrPermissionUi(QrPermissionState.GRANTED, QrPermissionAction.NONE))
        session.onDecoded("upi://pay?pa=shop@okaxis")
        session.confirmSetupCapture()
        session.onUiEvent(QrEvent.StartWatch())
        repeat(3) { session.onDecoded("upi://pay?pa=Shop@OkAxis&am=125.00&cu=INR") }
        assertFalse(session.snapshot().state.isTamperEvidence)
        assertTrue(requireNotNull(session.snapshot().paymentAdvisory).hints.none {
            it.kind == ai.trustshield.android.core.payment.PaymentHintKind.PAYEE_NOT_REGISTERED
        })
    }

    private fun registeredSession(): QrGuardSession {
        val session = QrGuardSession(clock = { 0L })
        session.applyPermission(QrPermissionUi(QrPermissionState.GRANTED, QrPermissionAction.NONE))
        session.onDecoded(demoQr)
        session.confirmSetupCapture()
        return session
    }
}
