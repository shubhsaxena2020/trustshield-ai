package ai.trustshield.android.ui.qr

import org.junit.Assert.assertEquals
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
}
