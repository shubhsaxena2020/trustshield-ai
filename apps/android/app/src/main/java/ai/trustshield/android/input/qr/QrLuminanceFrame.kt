package ai.trustshield.android.input.qr

import com.google.zxing.LuminanceSource

/** A copied Y-plane frame. It contains no Android or native image object. */
internal class QrLuminanceFrame(
    val width: Int,
    val height: Int,
    private val y: ByteArray,
    val rotationDegrees: Int,
) {
    init {
        require(width > 0 && height > 0 && width.toLong() * height <= MAX_PIXELS)
        require(y.size == width * height)
        require(rotationDegrees % 90 == 0)
    }

    /** Creates the orientation-correct grayscale input ZXing expects. */
    fun toUprightSource(): LuminanceSource {
        val rotation = ((rotationDegrees % 360) + 360) % 360
        val outWidth = if (rotation == 90 || rotation == 270) height else width
        val outHeight = if (rotation == 90 || rotation == 270) width else height
        val upright = if (rotation == 0) y else ByteArray(y.size).also { out ->
            for (sy in 0 until height) {
                for (sx in 0 until width) {
                    val (dx, dy) = when (rotation) {
                        90 -> height - 1 - sy to sx
                        180 -> width - 1 - sx to height - 1 - sy
                        else -> sy to width - 1 - sx
                    }
                    out[dy * outWidth + dx] = y[sy * width + sx]
                }
            }
        }
        val uprightWidth = outWidth
        val uprightHeight = outHeight
        return object : LuminanceSource(uprightWidth, uprightHeight) {
            override fun getRow(y: Int, row: ByteArray?): ByteArray {
                require(y in 0 until uprightHeight)
                val result = row?.takeIf { it.size >= uprightWidth } ?: ByteArray(uprightWidth)
                upright.copyInto(result, 0, y * uprightWidth, (y + 1) * uprightWidth)
                return result
            }

            override fun getMatrix(): ByteArray = upright
        }
    }

    companion object {
        const val MAX_PIXELS: Long = 1_500_000L
    }
}
