package ai.trustshield.android.input.qr

import androidx.camera.core.CameraUnavailableException
import org.junit.Assert.assertEquals
import org.junit.Test

class CameraFaultMapperTest {
    @Test
    fun cameraInUseAndMaximumInUseAreMappedToRecoverableBusyState() {
        assertEquals(
            CameraXQrScanner.FAULT_CAMERA_BUSY,
            CameraFaultMapper.token(CameraUnavailableException(CameraUnavailableException.CAMERA_IN_USE)),
        )
        assertEquals(
            CameraXQrScanner.FAULT_CAMERA_BUSY,
            CameraFaultMapper.token(CameraUnavailableException(CameraUnavailableException.CAMERA_MAX_IN_USE)),
        )
        val wrapped = IllegalStateException("bind failed", CameraUnavailableException(CameraUnavailableException.CAMERA_IN_USE))
        assertEquals(CameraXQrScanner.FAULT_CAMERA_BUSY, CameraFaultMapper.token(wrapped))
    }

    @Test
    fun unrelatedCameraFailuresRemainGenericBindFailures() {
        assertEquals(CameraXQrScanner.FAULT_BIND_FAILED, CameraFaultMapper.token(IllegalStateException("busy?")))
        assertEquals(
            CameraXQrScanner.FAULT_BIND_FAILED,
            CameraFaultMapper.token(CameraUnavailableException(CameraUnavailableException.CAMERA_DISABLED)),
        )
    }
}
