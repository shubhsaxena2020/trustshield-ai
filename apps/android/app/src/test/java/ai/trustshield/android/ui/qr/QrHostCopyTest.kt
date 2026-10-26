package ai.trustshield.android.ui.qr

import ai.trustshield.android.input.qr.CameraXQrScanner
import org.junit.Assert.assertTrue
import org.junit.Test

class QrHostCopyTest {
    @Test
    fun busyCameraExplainsRecoveryAndKeepsDemoAvailable() {
        val copy = QrHostCopy.faultText(CameraXQrScanner.FAULT_CAMERA_BUSY)
        assertTrue(copy.english.contains("Another app is using the camera"))
        assertTrue(copy.english.contains("run the QR Guard demo"))
    }
}
