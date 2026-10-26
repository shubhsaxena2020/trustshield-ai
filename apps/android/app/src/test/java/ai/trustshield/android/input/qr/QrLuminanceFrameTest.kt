package ai.trustshield.android.input.qr

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class QrLuminanceFrameTest {
    @Test
    fun rotatesCameraLumaIntoUprightClockwiseOrder() {
        val frame = QrLuminanceFrame(
            width = 2,
            height = 3,
            y = byteArrayOf(1, 2, 3, 4, 5, 6),
            rotationDegrees = 90,
        )

        val source = frame.toUprightSource()

        assertEquals(3, source.width)
        assertEquals(2, source.height)
        assertArrayEquals(byteArrayOf(5, 3, 1, 6, 4, 2), source.matrix)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsOversizedFrameBeforeAllocatingRotationBuffer() {
        QrLuminanceFrame(1501, 1000, ByteArray(1), 0)
    }
}
