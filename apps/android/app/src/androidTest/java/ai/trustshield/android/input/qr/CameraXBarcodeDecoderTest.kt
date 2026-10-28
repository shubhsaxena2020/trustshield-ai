package ai.trustshield.android.input.qr

import android.graphics.ImageFormat
import android.graphics.Rect
import android.media.Image
import android.media.ImageReader
import android.media.ImageWriter
import android.os.Handler
import android.os.Looper
import androidx.camera.core.ImageInfo
import androidx.camera.core.ImageProxy
import androidx.camera.core.impl.TagBundle
import androidx.camera.core.impl.utils.ExifData
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.TaskCompletionSource
import com.google.android.gms.tasks.Tasks
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Instrumented tests for the CameraX + pure-JVM QR adapter (Phase 4, issue #133).
 *
 * What these tests prove:
 * - the frame-booking invariant of [CameraXBarcodeDecoder]: exactly one close per frame, on a
 *   successful decode, on a failed `Task`, on a malformed frame, and on shutdown;
 * - single-slot behaviour: a second frame is refused while one decode is outstanding, so the vendor
 *   decoder never holds two images at once;
 * - permission and low-light refusals happen *before* the vendor decoder is touched;
 * - the payload that crosses the seam is exactly the string the deterministic parser
 *   ([UpiQrParser]) expects, and no fixture payload appears in any string this package renders;
 * - ZXing reports no code for a synthetic blank frame without invoking native barcode code.
 *
 * What these tests cannot prove (reported as a gap in the PR): that a real printed QR decodes on real
 * hardware — that needs a physical camera, a printed code and a person aiming it.
 *
 * Privacy: the fixtures below are synthetic. No assertion echoes a payload, a VPA or a frame byte
 * into a failure message; payload mismatches report a label only.
 */
@RunWith(AndroidJUnit4::class)
class CameraXBarcodeDecoderTest {

    // ---------------------------------------------------------------- fakes

    /**
     * A [FrameSource] that *counts* closes instead of tolerating them: [close] is deliberately not
     * idempotent, so a double close shows up as `closeCount == 2` and fails the exactly-once checks.
     */
    private class FakeFrame(
        override val widthPx: Int = 640,
        override val heightPx: Int = 480,
        override val rotationDegrees: Int = 90,
        private val luma: Double = 0.55,
        private val luminanceFactory: () -> QrLuminanceFrame? = { null },
    ) : FrameSource {
        val closeCount = AtomicInteger(0)
        val luminanceRequests = AtomicInteger(0)

        override fun meanLuma0to1(): Double = luma

        override fun toQrLuminanceFrame(): QrLuminanceFrame {
            luminanceRequests.incrementAndGet()
            luminanceFactory()?.let { return it }
            val byte = (luma * 255.0).toInt().toByte()
            return QrLuminanceFrame(widthPx, heightPx, ByteArray(widthPx * heightPx) { byte }, rotationDegrees)
        }

        override fun close() {
            closeCount.incrementAndGet()
        }
    }

    /** Records how many frames reached the vendor decoder and can hold a decode open on demand. */
    private class FakeReader(private val task: () -> Task<String?>) : BarcodeReader {
        val reads = AtomicInteger(0)

        override fun read(frame: QrLuminanceFrame): Task<String?> {
            reads.incrementAndGet()
            return task()
        }

        override fun close() = Unit
    }

    private fun decoder(
        reader: BarcodeReader,
        timeoutMs: Long = 30_000L,
        executor: Executor = DirectExecutor,
    ) = CameraXBarcodeDecoder(reader, executor, timeoutMs)

    private fun analyzer(decoder: BarcodeDecoder) = QrCameraAnalyzer(decoder)

    private fun ingress(
        analyzer: QrCameraAnalyzer,
        decoder: CameraXBarcodeDecoder,
        clockMs: Long = 1_000L,
    ) = QrFrameIngress(analyzer, decoder) { clockMs }

    /** A `Task` that never completes: stands in for a vendor call whose callback has not fired yet. */
    private fun pendingTask(): Task<String?> = TaskCompletionSource<String?>().task

    // ---------------------------------------------------------------- success / failure

    @Test
    fun payloadFromVendorReaderIsReturnedAndFrameIsClosedExactlyOnce() {
        val frame = FakeFrame()
        val reader = FakeReader { Tasks.forResult(FIXTURE_PAYLOAD) }
        val decoder = decoder(reader)

        assertTrue("offer must accept the first frame", decoder.offer(1L, frame))
        val decoded = decoder.decode(1L)

        assertSamePayload("decoded payload", FIXTURE_PAYLOAD, decoded)
        assertEquals("vendor reads", 1, reader.reads.get())
        assertEquals("frame closes", 1, frame.closeCount.get())
        assertEquals("frames decoded", 1, decoder.framesDecoded)

        // A claim is one-shot: the same frame id can never be decoded twice.
        assertNull("second decode of a claimed frame", decoder.decode(1L))
        assertEquals("frame closes after re-decode", 1, frame.closeCount.get())

        decoder.close()
        assertEquals("frame closes after decoder close", 1, frame.closeCount.get())
    }

    @Test
    fun frameWithoutACodeIsClosedOnceAndReturnsNull() {
        val frame = FakeFrame()
        val decoder = decoder(FakeReader { Tasks.forResult(null) })

        assertTrue(decoder.offer(7L, frame))
        assertNull("no code means no payload", decoder.decode(7L))

        assertEquals("frame closes", 1, frame.closeCount.get())
        assertEquals("frames without code", 1, decoder.framesWithoutCode)
        assertEquals("frames decoded", 1, decoder.framesDecoded)
    }

    @Test
    fun failedTaskClosesTheFrameOnceAndThrowsASanitisedFailure() {
        val frame = FakeFrame()
        // The vendor message deliberately carries the fixture payload: it must never surface.
        val reader = FakeReader { Tasks.forException(IllegalStateException("vendor said $FIXTURE_PAYLOAD")) }
        val decoder = decoder(reader)

        assertTrue(decoder.offer(2L, frame))
        val failure = try {
            decoder.decode(2L)
            fail("a failed vendor read must not look like a successful decode")
            null
        } catch (expected: BarcodeDecodeException) {
            expected
        }

        assertNotNull("expected a sanitised failure", failure)
        assertNull("failure message must stay empty", failure!!.message)
        assertFalse("failure must not carry the payload", failure.toString().contains(FIXTURE_PAYLOAD))
        assertEquals("frame closes", 1, frame.closeCount.get())
        assertEquals("frames failed", 1, decoder.framesFailed)
    }

    @Test
    fun invalidLuminanceFrameClosesOnceAndThrowsASanitisedFailure() {
        val frame = FakeFrame(luminanceFactory = { throw IllegalStateException("invalid frame") })
        val reader = FakeReader { Tasks.forResult(FIXTURE_PAYLOAD) }
        val decoder = decoder(reader)

        assertTrue(decoder.offer(3L, frame))
        try {
            decoder.decode(3L)
            fail("an invalid frame must not look like a successful decode")
        } catch (expected: BarcodeDecodeException) {
            assertNull("failure message must stay empty", expected.message)
        }

        assertEquals("frame closes", 1, frame.closeCount.get())
        assertEquals("vendor reads", 0, reader.reads.get())
    }

    // ---------------------------------------------------------------- shutdown / cancellation

    @Test
    fun shutdownReleasesTheInFlightFrameExactlyOnce() {
        val frame = FakeFrame()
        val decoder = decoder(FakeReader { pendingTask() }, timeoutMs = 120_000L)
        assertTrue(decoder.offer(4L, frame))

        val decoded = AtomicReference<String?>()
        val finished = CountDownLatch(1)
        val worker = Thread {
            decoded.set(decoder.decode(4L))
            finished.countDown()
        }
        worker.start()
        assertTrue("decode must be in flight before shutdown", waitForDecodeStart(decoder))

        decoder.close()
        decoder.close() // idempotent: a second close must not close the frame again

        assertTrue("close must unblock the waiting decode", finished.await(10, TimeUnit.SECONDS))
        assertNull("a cancelled decode yields no payload", decoded.get())
        assertEquals("frame closes", 1, frame.closeCount.get())
        worker.join(10_000L)
    }

    @Test
    fun secondFrameIsRefusedWhileOneDecodeIsInFlight() {
        val first = FakeFrame()
        val decoder = decoder(FakeReader { pendingTask() }, timeoutMs = 120_000L)
        assertTrue(decoder.offer(10L, first))

        val finished = CountDownLatch(1)
        val worker = Thread {
            decoder.decode(10L)
            finished.countDown()
        }
        worker.start()
        assertTrue("first decode must be in flight", waitForDecodeStart(decoder))

        val second = FakeFrame()
        assertFalse("single slot: a second frame must be refused", decoder.offer(11L, second))
        assertNull("a refused frame is never decoded", decoder.decode(11L))
        assertEquals("refused frame stays open for the caller", 0, second.closeCount.get())
        assertEquals("refused frame never copies pixels", 0, second.luminanceRequests.get())
        second.close()
        assertEquals("caller closes the refused frame", 1, second.closeCount.get())
        assertEquals("offer rejections", 1, decoder.offerRejections)

        decoder.close()
        assertTrue(finished.await(10, TimeUnit.SECONDS))
        assertEquals("first frame closes", 1, first.closeCount.get())
        worker.join(10_000L)
    }

    @Test
    fun decoderRefusesFramesAndDecodesAfterClose() {
        val decoder = decoder(FakeReader { Tasks.forResult(FIXTURE_PAYLOAD) })
        decoder.close()

        val frame = FakeFrame()
        assertFalse("a closed decoder accepts no frame", decoder.offer(20L, frame))
        assertNull("a closed decoder never decodes", decoder.decode(20L))
        assertEquals("closed decoder stays out of pixel copying", 0, frame.luminanceRequests.get())
    }

    @Test
    fun darkFrameIsReleasedByTheIngressWithoutTouchingTheVendorDecoder() {
        val reader = FakeReader { Tasks.forResult(FIXTURE_PAYLOAD) }
        val decoder = decoder(reader)
        val analyzer = analyzer(decoder)
        assertEquals(StartResult.Started, analyzer.start(CameraPermissionState.Granted))
        // Luma 0.02 sits below the analyzer's low-light threshold, so B03 never asks for a decode.
        val frame = FakeFrame(luma = 0.02)

        val events = ingress(analyzer, decoder).onFrame(frame)

        assertEquals("dark frame is reported, not decoded", 1, events.size)
        assertTrue("expected a NoDecode event", events.first() is AnalyzerEvent.NoDecode)
        assertTrue((events.first() as AnalyzerEvent.NoDecode).lowLight)
        assertEquals("vendor reads", 0, reader.reads.get())
        assertEquals("offered then released", 1, frame.closeCount.get())
        assertEquals("released unclaimed frames", 1, decoder.releasedUnclaimed)
    }

    // ---------------------------------------------------------------- permission gate

    @Test
    fun framesSubmittedWithoutCameraPermissionNeverReachTheVendorDecoder() {
        val reader = FakeReader { Tasks.forResult(FIXTURE_PAYLOAD) }
        val decoder = decoder(reader)
        val analyzer = analyzer(decoder)
        assertTrue(analyzer.start(CameraPermissionState.Denied) is StartResult.Refused)

        val frame = FakeFrame()
        val events = ingress(analyzer, decoder).onFrame(frame)

        assertTrue("no event without permission", events.isEmpty())
        assertEquals("vendor reads", 0, reader.reads.get())
        assertEquals("frame still closed", 1, frame.closeCount.get())
        assertEquals("frames decoded", 0, analyzer.framesDecoded)
    }

    @Test
    fun zeroSizedFrameIsClosedBeforeAnyStatsAreBuilt() {
        val reader = FakeReader { Tasks.forResult(FIXTURE_PAYLOAD) }
        val decoder = decoder(reader)
        val analyzer = analyzer(decoder)
        assertEquals(StartResult.Started, analyzer.start(CameraPermissionState.Granted))

        val frame = FakeFrame(widthPx = 0, heightPx = 0)
        val events = ingress(analyzer, decoder).onFrame(frame)

        assertTrue("a pre-layout frame yields nothing", events.isEmpty())
        assertEquals("vendor reads", 0, reader.reads.get())
        assertEquals("frame closes", 1, frame.closeCount.get())
    }

    // ---------------------------------------------------------------- payload -> B02 seam

    @Test
    fun decodedPayloadReachesTheDeterministicUpiParserUnchanged() {
        val decoder = decoder(FakeReader { Tasks.forResult(FIXTURE_PAYLOAD) })
        val analyzer = analyzer(decoder)
        assertEquals(StartResult.Started, analyzer.start(CameraPermissionState.Granted))

        val events = ingress(analyzer, decoder).onFrame(FakeFrame())
        val decoded = events.filterIsInstance<AnalyzerEvent.Decoded>().singleOrNull()

        assertNotNull("a readable code must produce one Decoded event", decoded)
        assertSamePayload("event payload", FIXTURE_PAYLOAD, decoded!!.payload)

        val parsed = UpiQrParser.parse(decoded.payload)
        assertTrue("payload must classify as UPI", parsed.isUpi)
        assertSamePayload("payee vpa", FIXTURE_VPA, parsed.payeeVpa)
        // A decode is data only: this package never acts on the payload.
        assertEquals("no destination is opened by parsing", QrPayloadKind.UPI, parsed.kind)
    }

    // ---------------------------------------------------------------- ImageProxyFrame

    @Test
    fun imageProxyFrameDelegatesGeometryAndClosesTheProxyExactlyOnce() {
        val proxy = FakeImageProxy(widthPx = 1280, heightPx = 720, rotationDegrees = 270)
        val frame = ImageProxyFrame(proxy)

        assertEquals(1280, frame.widthPx)
        assertEquals(720, frame.heightPx)
        assertEquals(270, frame.rotationDegrees)

        frame.close()
        frame.close()
        assertEquals("proxy closes", 1, proxy.closeCount.get())
    }

    // ---------------------------------------------------------------- no leakage in strings

    @Test
    fun packageObjectsNeverRenderThePayloadInToString() {
        val decoder = decoder(FakeReader { Tasks.forResult(FIXTURE_PAYLOAD) })
        assertEquals(StartResult.Started, analyzer(decoder).start(CameraPermissionState.Granted))
        assertTrue(decoder.offer(30L, FakeFrame()))
        decoder.decode(30L)

        val rendered = listOf(
            decoder.toString(),
            ImageProxyFrame(FakeImageProxy()).toString(),
            QrFrameIngress(analyzer(decoder), decoder) { 0L }.toString(),
            CameraXQrScanner.FAULT_PROVIDER_UNAVAILABLE,
            CameraXQrScanner.FAULT_BIND_FAILED,
            CameraXQrScanner.FAULT_PERMISSION_REVOKED,
            CameraXQrScanner.FAULT_FRAME_PIPELINE,
            CameraXQrScanner.ANALYSIS_THREAD_NAME,
            BarcodeDecodeException().toString(),
        )
        rendered.forEach { text ->
            assertFalse("a package diagnostic must not quote a payload", text.contains(FIXTURE_PAYLOAD))
            assertFalse("a package diagnostic must not quote a payee", text.contains(FIXTURE_VPA))
        }
        decoder.close()
    }

    // ---------------------------------------------------------------- synthetic YUV frames

    @Test
    fun syntheticBlackFrameHasZeroLumaAndWhiteFrameHasFullLuma() {
        withSyntheticFrame(luma = 0) { image ->
            val plane = image.planes[0]
            assertEquals(
                "black Y plane mean",
                0.0,
                YPlaneLuma.mean0to1(plane.buffer, image.width, image.height, plane.rowStride, plane.pixelStride),
                0.001,
            )
        }
        withSyntheticFrame(luma = 255) { image ->
            val plane = image.planes[0]
            assertEquals(
                "white Y plane mean",
                1.0,
                YPlaneLuma.mean0to1(plane.buffer, image.width, image.height, plane.rowStride, plane.pixelStride),
                0.001,
            )
        }
    }

    @Test
    fun pureJvmQrReaderThroughTheDecoderYieldsNoCodeAndClosesTheFrame() {
        val frame = FakeFrame(widthPx = 480, heightPx = 480, luma = 0.0)
        val decoder = CameraXBarcodeDecoder(ZxingQrBarcodeReader(), DirectExecutor, 60_000L)
        assertTrue(decoder.offer(40L, frame))
        assertNull("a black frame decodes to nothing", decoder.decode(40L))
        assertEquals("frame closes", 1, frame.closeCount.get())
        assertEquals("frames without code", 1, decoder.framesWithoutCode)
        assertEquals("frames failed", 0, decoder.framesFailed)
        decoder.close()
    }

    @Test
    fun pureJvmQrReaderDecodesSyntheticQrAndClosesTheFrame() {
        val expected = "upi://pay?pa=demo@okaxis&pn=Test"
        val matrix = QRCodeWriter().encode(
            expected,
            BarcodeFormat.QR_CODE,
            256,
            256,
            mapOf(EncodeHintType.MARGIN to 2),
        )
        val pixels = ByteArray(matrix.width * matrix.height) { index ->
            val x = index % matrix.width
            val y = index / matrix.width
            if (matrix[x, y]) 0 else 0xFF.toByte()
        }
        val frame = FakeFrame(
            widthPx = matrix.width,
            heightPx = matrix.height,
            rotationDegrees = 0,
            luminanceFactory = {
                QrLuminanceFrame(matrix.width, matrix.height, pixels, 0)
            },
        )
        val decoder = CameraXBarcodeDecoder(ZxingQrBarcodeReader(), DirectExecutor, 60_000L)

        assertTrue(decoder.offer(41L, frame))
        assertSamePayload("synthetic QR", expected, decoder.decode(41L))
        assertEquals("frame closes", 1, frame.closeCount.get())
        assertEquals("frames failed", 0, decoder.framesFailed)
        decoder.close()
    }

    @Test
    fun pureJvmQrReaderDecodesInvertedSyntheticQrAndClosesTheFrame() {
        val expected = "upi://pay?pa=demo@okaxis&pn=Test"
        val matrix = QRCodeWriter().encode(
            expected,
            BarcodeFormat.QR_CODE,
            256,
            256,
            mapOf(EncodeHintType.MARGIN to 2),
        )
        val pixels = ByteArray(matrix.width * matrix.height) { index ->
            val x = index % matrix.width
            val y = index / matrix.width
            if (matrix[x, y]) 0xFF.toByte() else 0
        }
        val frame = FakeFrame(
            widthPx = matrix.width,
            heightPx = matrix.height,
            rotationDegrees = 0,
            luminanceFactory = {
                QrLuminanceFrame(matrix.width, matrix.height, pixels, 0)
            },
        )
        val decoder = CameraXBarcodeDecoder(ZxingQrBarcodeReader(), DirectExecutor, 60_000L)

        assertTrue(decoder.offer(42L, frame))
        assertSamePayload("inverted synthetic QR", expected, decoder.decode(42L))
        assertEquals("frame closes", 1, frame.closeCount.get())
        decoder.close()
    }

    // ---------------------------------------------------------------- helpers

    private fun waitForDecodeStart(decoder: CameraXBarcodeDecoder): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            if (decoder.framesDecoded > 0) return true
            Thread.sleep(5L)
        }
        return false
    }

    private fun assertSamePayload(label: String, expected: String, actual: String?) {
        // Deliberately a boolean assertion: a JUnit message would echo the payload.
        assertTrue("$label mismatch", expected == actual)
    }

    /** Runs [block] on a real `YUV_420_888` [Image] produced by an [ImageReader]/[ImageWriter] pair. */
    private fun withSyntheticFrame(
        widthPx: Int = 480,
        heightPx: Int = 480,
        luma: Int,
        block: (Image) -> Unit,
    ) {
        val reader = ImageReader.newInstance(widthPx, heightPx, ImageFormat.YUV_420_888, 2)
        val writer = ImageWriter.newInstance(reader.surface, 2)
        val available = CountDownLatch(1)
        reader.setOnImageAvailableListener({ available.countDown() }, Handler(Looper.getMainLooper()))
        try {
            val output = writer.dequeueInputImage()
            fillPlane(output.planes[0], luma)
            fillPlane(output.planes[1], 128)
            fillPlane(output.planes[2], 128)
            writer.queueInputImage(output)

            assertTrue("synthetic frame never arrived", available.await(20, TimeUnit.SECONDS))
            val image = reader.acquireLatestImage()
            assertNotNull("no synthetic frame", image)
            try {
                block(image!!)
            } finally {
                image.close()
            }
        } finally {
            writer.close()
            reader.close()
        }
    }

    /** Writes [value] into every sample of [plane] while honouring the real strides. */
    private fun fillPlane(plane: Image.Plane, value: Int) {
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val rows = (buffer.limit() / rowStride).coerceAtLeast(1)
        val byte = value.toByte()
        for (row in 0 until rows) {
            val rowStart = row * rowStride
            var column = 0
            while (true) {
                val index = rowStart + column * pixelStride
                if (index >= buffer.limit()) break
                buffer.put(index, byte)
                column++
            }
        }
    }

    /**
     * `ImageProxy` fake: only geometry, rotation and close count matter to [ImageProxyFrame].
     *
     * Written with explicit `get*()` overrides because camera-core 1.6.2 exposes these as plain
     * interface functions rather than Kotlin synthetic properties.
     */
    private class FakeImageProxy(
        private val widthPx: Int = 64,
        private val heightPx: Int = 48,
        private val rotationDegrees: Int = 0,
    ) : ImageProxy {
        val closeCount = AtomicInteger(0)
        private var crop = Rect(0, 0, widthPx, heightPx)

        override fun getCropRect(): Rect = crop

        override fun setCropRect(rect: Rect?) {
            crop = rect ?: Rect(0, 0, widthPx, heightPx)
        }

        override fun getFormat(): Int = ImageFormat.YUV_420_888

        override fun getHeight(): Int = heightPx

        override fun getWidth(): Int = widthPx

        override fun getPlanes(): Array<ImageProxy.PlaneProxy> = emptyArray()

        override fun getImageInfo(): ImageInfo = FakeImageInfo(rotationDegrees)

        override fun getImage(): Image = throw UnsupportedOperationException("fake proxy carries no MediaImage")

        override fun close() {
            closeCount.incrementAndGet()
        }
    }

    private class FakeImageInfo(private val rotationDegrees: Int) : ImageInfo {
        override fun getTagBundle(): TagBundle = TagBundle.emptyBundle()

        override fun getTimestamp(): Long = 0L

        override fun getRotationDegrees(): Int = rotationDegrees

        override fun populateExifData(builder: ExifData.Builder) = Unit
    }

    private companion object {
        /** Synthetic UPI payload: a demo merchant, not a real VPA. */
        const val FIXTURE_PAYLOAD = "upi://pay?pa=shop.demo@okaxis&pn=Demo%20Store&am=120.00&cu=INR"
        const val FIXTURE_VPA = "shop.demo@okaxis"

    }
}
