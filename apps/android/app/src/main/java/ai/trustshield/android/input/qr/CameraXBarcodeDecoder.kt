package ai.trustshield.android.input.qr

import android.content.Context
import android.os.Looper
import android.os.SystemClock
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.common.HybridBinarizer
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * The one place a QR frame can be closed: a single frame handed over for decoding.
 *
 * This seam exists so the frame-booking logic (close exactly once, one frame in flight, no second
 * decode) can be tested without a camera, an `ImageReader` or a vendor SDK. In production the only
 * implementation is [ImageProxyFrame], which wraps the CameraX `ImageProxy` the analyzer was given.
 *
 * Ownership rule: whoever creates a `FrameSource` transfers it to the next owner. When
 * [CameraXBarcodeDecoder.offer] accepts it, the decoder owns it and guarantees exactly one [close];
 * when `offer` refuses it, the caller still owns it and must call [close] itself.
 */
internal interface FrameSource {
    /** Pixel width of the captured frame; `0` before the first layout. */
    val widthPx: Int

    /** Pixel height of the captured frame; `0` before the first layout. */
    val heightPx: Int

    /** Rotation the vendor decoder must apply to reach an upright image (0/90/180/270). */
    val rotationDegrees: Int

    /**
     * Mean luma of the Y plane in `0.0..1.0`, computed without a `Bitmap`.
     *
     * This is a *statistic*, not content: it is the only pixel-derived value that ever leaves the
     * frame, and it is used solely to tell a dark/covered lens (skip the decode, tell the user to
     * light the code) from a real decode attempt.
     */
    fun meanLuma0to1(): Double

    /** Copies grayscale QR pixels into a bounded, contiguous JVM byte array. */
    fun toQrLuminanceFrame(): QrLuminanceFrame

    /**
     * Releases the underlying buffer. Implementations are idempotent and safe from any thread; the
     * frame must never be used afterwards.
     */
    fun close()
}

/**
 * [FrameSource] over one CameraX `ImageProxy`.
 *
 * This class is the **only** place in the app that calls `ImageProxy.close()`. Every other layer
 * closes a `FrameSource`, so the "close exactly once" review question has one answer to inspect.
 * CameraX stalls the analysis pipeline if an `ImageProxy` is never closed, and crashes or corrupts
 * the buffer if it is closed twice, so the count matters more than the timing.
 */
internal class ImageProxyFrame(private val proxy: ImageProxy) : FrameSource {

    private val closeIssued = AtomicBoolean(false)

    override val widthPx: Int get() = proxy.getWidth()

    override val heightPx: Int get() = proxy.getHeight()

    override val rotationDegrees: Int get() = proxy.getImageInfo().getRotationDegrees()

    override fun meanLuma0to1(): Double {
        val planes = proxy.getPlanes()
        if (planes.isEmpty()) return 0.0
        val luma = planes[0]
        return YPlaneLuma.mean0to1(
            yPlane = luma.getBuffer(),
            widthPx = widthPx,
            heightPx = heightPx,
            rowStride = luma.getRowStride(),
            pixelStride = luma.getPixelStride(),
        )
    }

    override fun toQrLuminanceFrame(): QrLuminanceFrame {
        val planes = proxy.getPlanes()
        if (planes.isEmpty()) throw IllegalStateException("frame-without-luma")
        val plane = planes[0]
        val width = widthPx
        val height = heightPx
        require(width > 0 && height > 0 && width.toLong() * height <= QrLuminanceFrame.MAX_PIXELS)
        val source = plane.getBuffer().duplicate()
        val bytes = ByteArray(width * height)
        val rowStride = plane.getRowStride()
        val pixelStride = plane.getPixelStride()
        for (y in 0 until height) {
            val sourceRow = y * rowStride
            val destRow = y * width
            for (x in 0 until width) {
                bytes[destRow + x] = source.get(sourceRow + x * pixelStride)
            }
        }
        return QrLuminanceFrame(width, height, bytes, rotationDegrees)
    }

    override fun close() {
        if (closeIssued.compareAndSet(false, true)) proxy.close()
    }
}

/**
 * Mean luma of a `YUV_420_888`/`NV21` luma plane, normalised to `0.0..1.0`.
 *
 * Sampled on a fixed grid instead of every pixel: a 720p frame is ~900k samples and the value only
 * decides "too dark to bother" against a 0.12 threshold, so 32x32 samples are indistinguishable from
 * the full mean while costing ~1000 byte reads on the analysis thread. No allocation, no `Bitmap`,
 * and **the value is never logged** - it is a statistic about the user's room.
 */
internal object YPlaneLuma {

    /** Samples per axis. 32x32 = 1,024 reads regardless of resolution. */
    const val SAMPLES_PER_AXIS: Int = 32

    fun mean0to1(
        yPlane: ByteBuffer,
        widthPx: Int,
        heightPx: Int,
        rowStride: Int,
        pixelStride: Int,
    ): Double {
        if (widthPx <= 0 || heightPx <= 0) return 0.0
        val limit = yPlane.limit()
        if (limit <= 0) return 0.0

        val columnStep = maxOf(1, widthPx / SAMPLES_PER_AXIS)
        val rowStep = maxOf(1, heightPx / SAMPLES_PER_AXIS)
        val safeRowStride = maxOf(rowStride, widthPx)
        val safePixelStride = maxOf(pixelStride, 1)

        var sum = 0L
        var samples = 0
        var row = 0
        while (row < heightPx) {
            val rowStart = row * safeRowStride
            var column = 0
            while (column < widthPx) {
                val index = rowStart + column * safePixelStride
                if (index in 0 until limit) {
                    // Y is unsigned 8-bit: sign-extending the byte would turn 128 into -128.
                    sum += yPlane.get(index).toInt() and 0xFF
                    samples++
                }
                column += columnStep
            }
            row += rowStep
        }
        if (samples == 0) return 0.0
        return (sum.toDouble() / samples.toDouble()) / 255.0
    }
}

/**
 * QR reading seam: one pure-JVM decode of one copied luminance frame.
 *
 * Kept as a one-method interface so the booking rules can be tested with a `Task` that succeeds,
 * fails, or never completes, while the production decoder remains a single visible implementation.
 */
internal interface BarcodeReader {
    /**
     * Starts a read. The returned `Task` completes with the QR payload, or with `null` when the
     * frame holds no readable code. Implementations must not retain [frame].
     */
    fun read(frame: QrLuminanceFrame): Task<String?>

    /** Releases vendor resources. Idempotent. */
    fun close() = Unit
}

/**
 * QR-only ZXing decoder. It runs on CameraX's analysis executor and has no native barcode library,
 * so malformed or synthetic frames cannot crash the process inside `libbarhopper`.
 */
internal class ZxingQrBarcodeReader : BarcodeReader {
    private val reader = MultiFormatReader().apply {
        setHints(mapOf(
            DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
            DecodeHintType.TRY_HARDER to true,
        ))
    }

    override fun read(frame: QrLuminanceFrame): Task<String?> = try {
        val bitmap = BinaryBitmap(HybridBinarizer(frame.toUprightSource()))
        val payload = try {
            reader.decodeWithState(bitmap).text
        } catch (_: NotFoundException) {
            null
        } finally {
            reader.reset()
        }
        Tasks.forResult(payload)
    } catch (failure: Throwable) {
        if (failure is VirtualMachineError) throw failure
        Tasks.forException(BarcodeDecodeException())
    }
}

/**
 * Raised when a frame could not be read for a reason that is not "there is no code here".
 *
 * Carries **no message and no cause**: a vendor throwable can quote the payload, a frame index or a
 * buffer address in its message, and [QrCameraAnalyzer] derives its `decoder-failed:<SimpleName>`
 * token from the class name only. Dropping the message is the privacy-preserving choice, at the cost
 * of stack-trace detail in a vendor SDK we cannot vouch for.
 */
class BarcodeDecodeException internal constructor() : Exception()

/**
 * [BarcodeDecoder] for B03 backed by pure-JVM QR decoding (Phase 4, issue #133).
 *
 * ## Why this class looks the way it does
 * B03's seam is synchronous: `decode(frameId)` must return "the payload in this frame" or `null`.
 * ZXing is synchronous; this bridge keeps the existing bounded booking and release contract:
 *
 * 1. the CameraX analyzer thread owns the `ImageProxy` and hands it in as a [FrameSource] via
 *    [offer];
 * 2. [decode] claims that frame, starts the ML Kit task, and waits for it - the *analysis* thread
 *    blocks, never the main thread, and `STRATEGY_KEEP_ONLY_LATEST` means the camera simply drops the
 *    frames produced meanwhile;
 * 3. the task's `addOnCompleteListener` is the **single** place the frame is released, for success,
 *    failure, cancellation and shutdown alike.
 *
 * The alternative - decode asynchronously and report the payload out of band - is not available here
 * because B03 (already merged) owns streak counting, debounce and the `Closed` ordering, and it must
 * see every payload to do that.
 *
 * ## Booking invariants (each one has an instrumented test)
 * - **Exactly one close per accepted frame.** [offer] is the only way in; the resulting slot can be
 *   released by the completion listener, by [close] (shutdown) or by [releaseUnclaimed]; all three
 *   go through the same idempotent [InFlight.release].
 * - **One frame in flight.** While a decode is outstanding, [offer] refuses new frames and returns
 *   `false`; the caller keeps ownership and closes its own frame. B03's own single-slot queue means
 *   this refusal is a safety net, not a routine path.
 * - **Bounded wait.** A vendor task that never completes cannot wedge the analysis thread forever:
 *   the wait is capped at [decodeTimeoutMs] and the frame stays owned by the completion listener, so
 *   it is still closed exactly once - merely later.
 * - **No payload retention.** The decoded string is returned to the caller and never stored on this
 *   object; the counters here are handfuls of ints.
 *
 * Threading: every method is safe from any thread. [decode] blocks the calling thread by design and
 * must never be called on the main thread - the only caller is the CameraX analysis executor.
 *
 * Visibility: `internal`, with the module-internal [offer]/[releaseUnclaimed] booking calls. The app
 * reaches the camera through [CameraXQrScanner]; outside this module only [BarcodeDecoder] matters.
 */
internal class CameraXBarcodeDecoder internal constructor(
    private val reader: BarcodeReader,
    private val completionExecutor: Executor,
    private val decodeTimeoutMs: Long,
) : BarcodeDecoder, AutoCloseable {

    /** Production decoder: pure-JVM QR decode, direct-executor completion callback. */
    constructor() : this(ZxingQrBarcodeReader(), DirectExecutor, DEFAULT_DECODE_TIMEOUT_MS)

    private val lock = Any()

    /** Offered, not yet claimed. At most one. Guarded by [lock]. */
    private var slot: InFlight? = null

    /** Claimed and decoding. At most one, and never together with [slot]. Guarded by [lock]. */
    private var inFlight: InFlight? = null

    private var closed = false
    private var lastFrameId = Long.MIN_VALUE
    private var framesOfferedCount = 0
    private var framesDecodedCount = 0
    private var framesWithoutCodeCount = 0
    private var framesFailedCount = 0
    private var offerRejectionCount = 0
    private var releasedUnclaimedCount = 0
    private var abandonedAfterTimeoutCount = 0

    init {
        require(decodeTimeoutMs > 0L) { "decodeTimeoutMs must be positive" }
    }

    /** Accepted frames, whether or not they were ever decoded. */
    val framesOffered: Int get() = synchronized(lock) { framesOfferedCount }

    /** Frames actually handed to the vendor decoder. */
    val framesDecoded: Int get() = synchronized(lock) { framesDecodedCount }

    /** Decodes that completed without a readable code (an unreadable frame, never tamper evidence). */
    val framesWithoutCode: Int get() = synchronized(lock) { framesWithoutCodeCount }

    /** Decodes whose vendor task failed. */
    val framesFailed: Int get() = synchronized(lock) { framesFailedCount }

    /** Frames refused because a decode was already in flight or the decoder was closed. */
    val offerRejections: Int get() = synchronized(lock) { offerRejectionCount }

    /** Frames the pipeline handed over that B03 never asked to decode (dark, denied, not running). */
    val releasedUnclaimed: Int get() = synchronized(lock) { releasedUnclaimedCount }

    /** Decodes that outlived the timeout. Their frames are still released by their own listener. */
    val abandonedAfterTimeout: Int get() = synchronized(lock) { abandonedAfterTimeoutCount }

    /**
     * Bind [source] to [frameId] and take ownership of it.
     *
     * @return `true` when the decoder now owns [source] and will close it exactly once; `false` when
     *         it refused (a decode is in flight, or the decoder is closed) - the caller still owns
     *         the frame and must close it.
     */
    fun offer(frameId: Long, source: FrameSource): Boolean = synchronized(lock) {
        val accepted = !closed && slot == null && inFlight == null && frameId > lastFrameId
        if (accepted) {
            lastFrameId = frameId
            slot = InFlight(frameId, source)
            framesOfferedCount++
        } else {
            offerRejectionCount++
        }
        accepted
    }

    /**
     * Decode the frame previously bound to [frameId]. Blocks the calling thread until the vendor
     * answers, the [decodeTimeoutMs] budget runs out, or the decoder is closed.
     *
     * @return the barcode `rawValue`, or `null` when this frame holds no code / is no longer owned /
     *         the wait was abandoned. **Never** returns a value that was not decoded from this frame.
     * @throws BarcodeDecodeException when the vendor task failed. The exception carries no message.
     */
    override fun decode(frameId: Long): String? {
        val target = claim(frameId) ?: return null
        try {
            return decodeClaimed(target)
        } finally {
            synchronized(lock) { if (inFlight === target) inFlight = null }
        }
    }

    /**
     * Release the offered-but-never-decoded frame, if any.
     *
     * Called by the frame pipeline immediately after B03 had its turn with the frame: when B03
     * ignores a frame (camera starting, permission revoked, dark lens, closed analyzer) nobody will
     * ever call [decode] for it, and an unclosed `ImageProxy` stalls CameraX. Safe to call at any
     * time; it can never release a claimed frame.
     */
    fun releaseUnclaimed() {
        val unclaimed = synchronized(lock) {
            val current = slot
            slot = null
            current
        }
        if (unclaimed != null) {
            synchronized(lock) { releasedUnclaimedCount++ }
            unclaimed.release()
        }
    }

    /**
     * Release the in-flight and the offered frame and stop accepting new ones. Idempotent.
     *
     * Closing an in-flight frame while the vendor still holds it is deliberate: on shutdown the
     * camera is unbinding and a retained `ImageProxy` would leak a gralloc buffer. The vendor task's
     * own completion listener then finds the frame already closed and does nothing, so the
     * exactly-once rule still holds.
     */
    override fun close() {
        val pending: InFlight?
        val active: InFlight?
        synchronized(lock) {
            if (closed) return
            closed = true
            pending = slot
            slot = null
            active = inFlight
        }
        pending?.release()
        active?.release()
        reader.close()
    }

    // ---------------------------------------------------------------- internals

    /** One frame somewhere in its life: offered, claimed, released. */
    private class InFlight(val frameId: Long, val source: FrameSource) {

        val gate = CountDownLatch(1)

        private val released = AtomicBoolean(false)

        /** Set by the completion listener; read only after [gate] has been counted down. */
        @Volatile
        var payload: String? = null

        /** Set by the completion listener when the vendor task failed. */
        @Volatile
        var failed: Boolean = false

        /**
         * Close the frame exactly once and wake any waiter.
         *
         * `close()` on the underlying frame must never escape: a throwing close would leave the gate
         * shut and the analysis thread blocked.
         */
        fun release() {
            if (released.compareAndSet(false, true)) {
                runCatching { source.close() }
            }
            gate.countDown()
        }
    }

    private fun claim(frameId: Long): InFlight? = synchronized(lock) {
        val waiting = slot
        if (closed || waiting == null || waiting.frameId != frameId) {
            null
        } else {
            slot = null
            inFlight = waiting
            framesDecodedCount++
            waiting
        }
    }

    private fun decodeClaimed(target: InFlight): String? {
        val image = try {
            target.source.toQrLuminanceFrame()
        } catch (failure: Throwable) {
            if (failure is VirtualMachineError) throw failure
            failDecode(target)
        }

        val task = try {
            reader.read(image)
        } catch (failure: Throwable) {
            if (failure is VirtualMachineError) throw failure
            failDecode(target)
        }

        // The completion listener owns the frame from here on. It runs on a thread that is never the
        // one blocked below, so it cannot deadlock against the wait.
        task.addOnCompleteListener(completionExecutor) { finished ->
            try {
                if (finished.isSuccessful) target.payload = finished.result else target.failed = true
            } catch (failure: Throwable) {
                // getResult() on a failed task throws; treat anything unexpected as a failure.
                target.failed = true
            } finally {
                target.release()
            }
        }

        val completed = try {
            target.gate.await(decodeTimeoutMs, TimeUnit.MILLISECONDS)
        } catch (interrupted: InterruptedException) {
            // Shutdown interrupts the analysis thread. The frame stays with the listener (exactly
            // once) and this frame reports "no code" instead of pretending it read something.
            Thread.currentThread().interrupt()
            return null
        }
        if (!completed) {
            synchronized(lock) { abandonedAfterTimeoutCount++ }
            return null
        }
        if (target.failed) failDecode(target)
        val payload = target.payload
        if (payload == null) {
            synchronized(lock) { framesWithoutCodeCount++ }
        }
        return payload
    }

    /**
     * Count a failed decode, make sure the frame is released and report the sanitised failure. The
     * exception deliberately has no message and no cause.
     */
    private fun failDecode(target: InFlight): Nothing {
        synchronized(lock) { framesFailedCount++ }
        target.release()
        throw BarcodeDecodeException()
    }

    companion object {
        /**
         * Upper bound on one vendor read. Cold ML Kit init on a low-end device can take a second or
         * two; past three seconds the analysis thread must go back to the camera so the user sees
         * "code nahi padh paya" instead of a frozen preview.
         */
        const val DEFAULT_DECODE_TIMEOUT_MS: Long = 3_000L
    }
}

/**
 * The single entry point for camera frames: [CameraXQrScanner] calls [onFrame] for every
 * `ImageProxy` and nothing else in the app touches a frame.
 *
 * Responsibilities, in order, because each one owns the frame on a different path:
 * 1. reject geometry the vendor SDK cannot use (pre-layout `0x0` frame) and close it;
 * 2. build [FrameStats] - mean luma plus dimensions, no pixels;
 * 3. offer the frame to [CameraXBarcodeDecoder]; if refused, close it here;
 * 4. let B03 decide (`submitFrame`), which either decodes the frame through the decoder or ignores
 *    it;
 * 5. whatever B03 did not decode is closed by `releaseUnclaimed()` in a `finally`.
 *
 * `onFrame` returns the events instead of dispatching them so the booking logic is testable without
 * a UI. Ownership of [source] always transfers to this method, on every path.
 */
internal class QrFrameIngress(
    private val analyzer: QrCameraAnalyzer,
    private val decoder: CameraXBarcodeDecoder,
    private val clockMs: () -> Long,
) {

    private val frameCounter = AtomicLong(0L)

    fun onFrame(source: FrameSource): List<AnalyzerEvent> {
        if (source.widthPx <= 0 || source.heightPx <= 0) {
            // A frame produced before the preview has a surface: CameraX reports 0x0 and any decode
            // would be noise. Closing it keeps the pipeline moving.
            source.close()
            return emptyList()
        }

        val frameId = frameCounter.incrementAndGet()
        val stats = try {
            FrameStats(
                frameId = frameId,
                widthPx = source.widthPx,
                heightPx = source.heightPx,
                meanLuma0to1 = source.meanLuma0to1(),
                timestampMs = clockMs(),
            )
        } catch (rejected: Throwable) {
            if (rejected is VirtualMachineError) throw rejected
            // A frame whose luma is not a finite 0..1 value cannot be trusted to be "dark" or "lit";
            // drop it rather than inventing a statistic.
            source.close()
            return emptyList()
        }

        if (!decoder.offer(frameId, source)) {
            source.close()
            return emptyList()
        }

        return try {
            analyzer.submitFrame(stats)
        } finally {
            // B03 ignores frames while the camera is arming, without permission or when they are too
            // dark. It has no way to tell us, so we ask the decoder what is still unclaimed.
            decoder.releaseUnclaimed()
        }
    }
}

/**
 * Real CameraX binding for the QR Guard watcher (P3-13, issue #92).
 *
 * Binds `Preview` + `ImageAnalysis` to a [LifecycleOwner], feeds every analysed frame through
 * [QrFrameIngress] into the merged B03 policy, and dispatches [AnalyzerEvent]s to [onEvent] on the
 * main thread. It never decides a verdict, never opens a payload and never stores a frame.
 *
 * ## Lifecycle
 * [start] is a resume: calling it while running returns [StartResult.Started] and changes nothing.
 * [stop] is the pause: it clears the analyzer, unbinds every use case, shuts the analysis executor
 * down and reports [AnalyzerEvent.Closed]. Both are idempotent, and both create/destroy a fresh
 * [QrCameraAnalyzer] + [CameraXBarcodeDecoder], because B03's `close()` is terminal by design.
 *
 * ## Permission
 * Camera permission is passed in as [CameraPermissionState] and handed to B03, which refuses to run
 * without [CameraPermissionState.Granted]. That refusal is honoured: with any other state we do not
 * even ask CameraX for a camera, so a revoked permission can never produce a decode.
 *
 * ## Threading
 * Frames are analysed on one dedicated thread named [ANALYSIS_THREAD_NAME]; a decode blocks that
 * thread, never the main thread. [onEvent] always arrives on the main thread, so a Compose consumer
 * can put the event straight into state.
 *
 * ## Faults
 * CameraX failures are reported as stable tokens in [onFault], never as exception messages (a vendor
 * exception can quote a device path). The tokens are also used in [StartResult.Refused] reasons when
 * the failure is known synchronously.
 */
class CameraXQrScanner(
    context: Context,
    private val onEvent: (AnalyzerEvent) -> Unit,
    private val onFault: (String) -> Unit = {},
) {

    private val appContext: Context = context.applicationContext
    private val mainExecutor: Executor = ContextCompat.getMainExecutor(appContext)
    private val lock = Any()

    private var session: Session? = null

    /** `true` while a camera session exists and has not been stopped. */
    val isRunning: Boolean get() = synchronized(lock) { session != null }

    /**
     * Start (or resume) the camera watcher.
     *
     * @param owner lifecycle the use cases are bound to; CameraX unbinds them when it is destroyed.
     * @param previewSurface where the preview is drawn, usually `PreviewView.surfaceProvider`.
     * @param permission current `CAMERA` permission state. Anything but
     *        [CameraPermissionState.Granted] is refused before the camera is touched.
     * @return [StartResult.Started], or [StartResult.Refused] with a stable token:
     *         B03's own permission tokens, [FAULT_PROVIDER_UNAVAILABLE] when CameraX could not be
     *         obtained, [FAULT_BIND_FAILED] when binding was rejected, or
     *         [FAULT_PERMISSION_REVOKED] when the OS refused the camera despite the reported state.
     */
    fun start(
        owner: LifecycleOwner,
        previewSurface: Preview.SurfaceProvider,
        permission: CameraPermissionState,
    ): StartResult {
        val prepared: Session
        synchronized(lock) {
            if (session != null) return StartResult.Started

            val decoder = CameraXBarcodeDecoder()
            val analyzer = QrCameraAnalyzer(decoder)
            val startResult = analyzer.start(permission)
            if (startResult is StartResult.Refused) {
                // Refused before any camera resource exists: release the vendor client we just built.
                decoder.close()
                return startResult
            }

            prepared = Session(
                owner = owner,
                previewSurface = previewSurface,
                decoder = decoder,
                analyzer = analyzer,
                ingress = QrFrameIngress(analyzer, decoder) { SystemClock.elapsedRealtime() },
                executor = newAnalysisExecutor(),
                mainExecutor = mainExecutor,
                dispatch = ::dispatch,
            )
            session = prepared
        }
        requestCameraProvider(prepared)
        return StartResult.Started
    }

    /**
     * Stop the watcher: unbind the camera, stop the analysis thread and close the analyzer and
     * decoder. Idempotent and safe from any thread; the [AnalyzerEvent.Closed] event is delivered
     * last, exactly once.
     */
    fun stop() {
        val stopped = synchronized(lock) {
            val current = session
            session = null
            current
        } ?: return
        stopped.shutdown()
    }

    // ---------------------------------------------------------------- camera plumbing

    private fun requestCameraProvider(target: Session) {
        val future = try {
            ProcessCameraProvider.getInstance(appContext)
        } catch (failure: Throwable) {
            if (failure is VirtualMachineError) throw failure
            fail(target, FAULT_PROVIDER_UNAVAILABLE)
            return
        }
        future.addListener({
            if (!isActive(target)) return@addListener
            val provider = try {
                future.get()
            } catch (failure: Throwable) {
                if (failure is VirtualMachineError) throw failure
                fail(target, FAULT_PROVIDER_UNAVAILABLE)
                return@addListener
            }
            bind(target, provider)
        }, mainExecutor)
    }

    private fun bind(target: Session, provider: ProcessCameraProvider) {
        try {
            val preview = Preview.Builder().build()
            preview.setSurfaceProvider(target.previewSurface)

            val analysis = ImageAnalysis.Builder()
                // The camera drops the frames we cannot keep up with; a QR code that is still in
                // front of the lens will be in the next one.
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                // ~720p is plenty for a QR payload and keeps the luma scan and the vendor decode
                // inside a frame budget on mid-range hardware.
                .setResolutionSelector(ANALYSIS_RESOLUTION)
                .build()
            analysis.setAnalyzer(target.executor) { proxy -> analyse(target, proxy) }

            provider.unbindAll()
            provider.bindToLifecycle(target.owner, cameraSelector(provider), preview, analysis)
            target.attach(provider, analysis)
        } catch (revoked: SecurityException) {
            fail(target, FAULT_PERMISSION_REVOKED)
        } catch (failure: Throwable) {
            if (failure is VirtualMachineError) throw failure
            fail(target, CameraFaultMapper.token(failure))
        }
    }

    /**
     * QR Guard prefers the back camera (the customer holds the phone towards the merchant's code) and
     * falls back to whatever lens exists, so a tablet or an emulator without a back camera still
     * reaches the preview instead of failing silently.
     */
    private fun cameraSelector(provider: ProcessCameraProvider): CameraSelector =
        try {
            if (provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) {
                CameraSelector.DEFAULT_BACK_CAMERA
            } else {
                CameraSelector.DEFAULT_FRONT_CAMERA
            }
        } catch (unavailable: Throwable) {
            if (unavailable is VirtualMachineError) throw unavailable
            CameraSelector.DEFAULT_BACK_CAMERA
        }

    /** Runs on the analysis thread: one frame in, events out, frame always released. */
    private fun analyse(target: Session, proxy: ImageProxy) {
        val events = try {
            target.ingress.onFrame(ImageProxyFrame(proxy))
        } catch (failure: Throwable) {
            if (failure is VirtualMachineError) throw failure
            // The ingress closes the frame on every path it owns; a throwable here means the frame
            // pipeline itself broke, which the UI must show instead of a silent black preview.
            reportFault(FAULT_FRAME_PIPELINE)
            emptyList()
        }
        events.forEach { event -> dispatch(event) }
    }

    private fun dispatch(event: AnalyzerEvent) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            deliver(event)
        } else {
            mainExecutor.execute { deliver(event) }
        }
    }

    private fun deliver(event: AnalyzerEvent) {
        try {
            onEvent(event)
        } catch (failure: Throwable) {
            if (failure is VirtualMachineError) throw failure
            // A consumer that throws must not take down the analysis thread or crash the app from a
            // posted main-thread runnable.
        }
    }

    private fun reportFault(token: String) {
        try {
            onFault(token)
        } catch (failure: Throwable) {
            if (failure is VirtualMachineError) throw failure
        }
    }

    private fun fail(target: Session, token: String) {
        reportFault(token)
        // Tear the session down so `isRunning` stops claiming a camera that is not bound.
        val stopped = synchronized(lock) {
            if (session === target) {
                session = null
                target
            } else {
                null
            }
        }
        stopped?.shutdown()
    }

    private fun isActive(target: Session): Boolean = synchronized(lock) { session === target }

    private fun newAnalysisExecutor(): ExecutorService =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, ANALYSIS_THREAD_NAME).apply { isDaemon = true }
        }

    /**
     * One start/stop cycle. Holds the objects B03 closes terminally, so a resume always builds a new
     * one; [shutdown] runs at most once because the scanner drops its reference first.
     */
    private class Session(
        val owner: LifecycleOwner,
        val previewSurface: Preview.SurfaceProvider,
        val decoder: CameraXBarcodeDecoder,
        val analyzer: QrCameraAnalyzer,
        val ingress: QrFrameIngress,
        val executor: ExecutorService,
        private val mainExecutor: Executor,
        private val dispatch: (AnalyzerEvent) -> Unit,
    ) {
        @Volatile
        private var provider: ProcessCameraProvider? = null

        @Volatile
        private var analysis: ImageAnalysis? = null

        fun attach(provider: ProcessCameraProvider, analysis: ImageAnalysis) {
            this.provider = provider
            this.analysis = analysis
        }

        fun shutdown() {
            val boundProvider = provider
            val boundAnalysis = analysis
            provider = null
            analysis = null
            // CameraX requires the main thread for bind/unbind; stop() may be called from a Compose
            // effect on main, from a lifecycle callback, or from a worker.
            val unbind = Runnable {
                runCatching { boundAnalysis?.clearAnalyzer() }
                runCatching { boundProvider?.unbindAll() }
            }
            if (Looper.myLooper() == Looper.getMainLooper()) unbind.run() else mainExecutor.execute(unbind)

            // Close what is still ours, in this order: a frame offered but not decoded has no owner
            // left, then the analyzer emits its terminal Closed event, then the vendor client goes.
            decoder.releaseUnclaimed()
            val events = analyzer.close()
            decoder.close()
            executor.shutdown()
            events.forEach(dispatch)
        }
    }

    companion object {
        /** Name of the dedicated analysis thread; appears in `adb shell ps -T` and nowhere else. */
        const val ANALYSIS_THREAD_NAME: String = "ts-qr-analysis"

        /** CameraX could not be initialised on this device. */
        const val FAULT_PROVIDER_UNAVAILABLE: String = "camera-provider-unavailable"

        /** CameraX refused the use-case binding (no usable lens, configuration clash). */
        const val FAULT_BIND_FAILED: String = "camera-bind-failed"

        /** Another app or camera session currently owns the camera. */
        const val FAULT_CAMERA_BUSY: String = "camera-busy"

        /** The OS refused the camera even though the app believed permission was granted. */
        const val FAULT_PERMISSION_REVOKED: String = "camera-permission-revoked"

        /** The frame pipeline threw outside the decoder (dropped frame, never a verdict). */
        const val FAULT_FRAME_PIPELINE: String = "camera-frame-pipeline-failed"
        const val FAULT_EMULATOR_CAMERA_DISABLED: String = "camera-disabled-emulator"

        private val ANALYSIS_RESOLUTION: ResolutionSelector = ResolutionSelector.Builder()
            .setResolutionStrategy(
                ResolutionStrategy(
                    Size(1280, 720),
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                ),
            )
            .build()
    }
}

/** Pure CameraX exception mapping so a busy-camera path can be verified without opening a camera. */
internal object CameraFaultMapper {
    fun token(failure: Throwable): String {
        val unavailable = generateSequence(failure as Throwable?) { it.cause }
            .filterIsInstance<androidx.camera.core.CameraUnavailableException>()
            .firstOrNull()
        return if (
            unavailable?.reason == androidx.camera.core.CameraUnavailableException.CAMERA_IN_USE ||
            unavailable?.reason == androidx.camera.core.CameraUnavailableException.CAMERA_MAX_IN_USE
        ) {
            CameraXQrScanner.FAULT_CAMERA_BUSY
        } else {
            CameraXQrScanner.FAULT_BIND_FAILED
        }
    }
}

/**
 * Direct executor: runs a completion callback on the thread that completed the `Task`.
 *
 * Used in two places where a hop would be wrong: the decoder's completion listener (which closes the
 * frame) must not queue behind the analysis thread it is waking, and ML Kit's result mapping should
 * not cost a main-thread round trip for a string extraction.
 */
internal val DirectExecutor: Executor = Executor { command -> command.run() }
