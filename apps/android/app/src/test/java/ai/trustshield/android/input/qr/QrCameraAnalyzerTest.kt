package ai.trustshield.android.input.qr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for the pure QR frame/scan policy (B03, issue #35).
 *
 * No `android.*` type is constructed here: the analyzer is the pure decision core, so these tests run
 * unchanged under JUnit 4 outside the Android runtime. Run with the orchestrator's out-of-tree harness
 * while `:app:testDebugUnitTest` cannot compile test sources (no `testImplementation` in the module yet).
 */
class QrCameraAnalyzerTest {

    // ---------------------------------------------------------------- fixtures

    /** Records the frame ids handed to the decoder and can throw or re-enter the analyzer on a chosen call. */
    private class FakeDecoder(
        private val payloads: List<String?> = emptyList(),
        private val onCall: ((Long, FakeDecoder) -> Unit)? = null,
    ) : BarcodeDecoder {
        val calls = mutableListOf<Long>()
        var error: Throwable? = null
        var errorAtCall: Int? = null

        override fun decode(frameId: Long): String? {
            calls += frameId
            val call = calls.size
            onCall?.invoke(frameId, this)
            error?.let { if (errorAtCall == null || errorAtCall == call) throw it }
            return payloads.getOrNull(call - 1)
        }
    }

    private fun frame(id: Long, ts: Long = id * 100L, luma: Double = 0.55) =
        FrameStats(frameId = id, widthPx = 1280, heightPx = 720, meanLuma0to1 = luma, timestampMs = ts)

    private fun started(decoder: BarcodeDecoder, debounceMs: Long = QrCameraAnalyzer.DEBOUNCE_MS) =
        QrCameraAnalyzer(decoder = decoder, debounceMs = debounceMs).also {
            assertEquals(StartResult.Started, it.start(CameraPermissionState.Granted))
        }

    private fun noDecode(streak: Int, lowLight: Boolean = false) = AnalyzerEvent.NoDecode(streak, lowLight)

    // ---------------------------------------------------------------- latest-frame-wins

    @Test
    fun rapidFramesCoalesceToLatestFrameAndCountDroppedStale() {
        lateinit var analyzer: QrCameraAnalyzer
        // While frame 1 is being decoded, the camera keeps delivering: only the newest may survive.
        val decoder = FakeDecoder(onCall = { _, self ->
            if (self.calls.size == 1) listOf(2L, 3L, 4L, 5L).forEach { analyzer.submitFrame(frame(it)) }
        })
        analyzer = QrCameraAnalyzer(decoder)
        assertEquals(StartResult.Started, analyzer.start(CameraPermissionState.Granted))

        val events = analyzer.submitFrame(frame(1))

        assertEquals(listOf(1L, 5L), decoder.calls)
        assertEquals(3, analyzer.droppedStaleFrames)
        assertEquals(listOf(noDecode(1), noDecode(2)), events)
    }

    @Test
    fun burstOfManySubmissionsNeverGrowsTheQueue() {
        lateinit var analyzer: QrCameraAnalyzer
        val decoder = FakeDecoder(onCall = { _, self ->
            if (self.calls.size == 1) (2L..40L).forEach { analyzer.submitFrame(frame(it)) }
        })
        analyzer = QrCameraAnalyzer(decoder)
        analyzer.start(CameraPermissionState.Granted)

        analyzer.submitFrame(frame(1))

        assertEquals(2, decoder.calls.size)
        assertEquals(40L, decoder.calls.last())
        assertEquals(38, analyzer.droppedStaleFrames)
    }

    // ---------------------------------------------------------------- no decode / unreadable

    @Test
    fun fiveConsecutiveNoDecodesReportUnreadableOnceAtTheStreakCrossing() {
        val analyzer = started(FakeDecoder())

        for (i in 1..4) {
            assertEquals(listOf(noDecode(i)), analyzer.submitFrame(frame(i.toLong())))
        }
        assertEquals(4, analyzer.noDecodeStreak)

        // sinceMs is the timestamp of the FIRST no-decode in the run (frame 1 -> 100 ms).
        assertEquals(
            listOf(noDecode(5), AnalyzerEvent.Unreadable(sinceMs = 100L)),
            analyzer.submitFrame(frame(5, ts = 500L)),
        )
        // The streak keeps counting but Unreadable is reported once, at the crossing.
        assertEquals(listOf(noDecode(6)), analyzer.submitFrame(frame(6, ts = 600L)))
    }

    @Test
    fun successfulDecodeResetsTheStreakAndRestartsTheUnreadableTimer() {
        val payload = "upi://pay?pa=sharma.store@okhdfcbank&pn=Sharma%20General%20Store"
        val decoder = FakeDecoder(listOf(null, null, null, null, payload, null, null, null, null, null))
        val analyzer = started(decoder)

        for (i in 1..4) analyzer.submitFrame(frame(i.toLong()))
        assertEquals(
            listOf(AnalyzerEvent.Decoded(payload = payload, frameId = 5L, tsMs = 500L)),
            analyzer.submitFrame(frame(5, ts = 500L)),
        )
        assertEquals(0, analyzer.noDecodeStreak)

        for (i in 6..9) {
            assertEquals(listOf(noDecode(i - 5)), analyzer.submitFrame(frame(i.toLong(), ts = i * 100L)))
        }
        assertEquals(
            listOf(noDecode(5), AnalyzerEvent.Unreadable(sinceMs = 600L)),
            analyzer.submitFrame(frame(10, ts = 1000L)),
        )
    }

    @Test
    fun blankDecoderPayloadIsANoDecodeNotAPayload() {
        val analyzer = started(FakeDecoder(listOf("", "   ", "upi://pay?pa=a@okaxis")))

        assertEquals(listOf(noDecode(1)), analyzer.submitFrame(frame(1)))
        assertEquals(listOf(noDecode(2)), analyzer.submitFrame(frame(2)))
        assertEquals(1, analyzer.submitFrame(frame(3)).size)
    }

    @Test
    fun darkFrameIsFlaggedLowLightAndNeverReachesTheDecoder() {
        val decoder = FakeDecoder(listOf("upi://pay?pa=shop@okaxis"))
        val analyzer = started(decoder)

        val events = analyzer.submitFrame(frame(1, luma = 0.05))

        assertEquals(listOf(noDecode(1, lowLight = true)), events)
        assertTrue(decoder.calls.isEmpty())
        assertEquals(1, analyzer.framesSkippedDark)
        assertEquals(0, analyzer.framesDecoded)
        // Dark is a readability problem, never tamper: the only event type produced is NoDecode.
        assertTrue(events.none { it is AnalyzerEvent.Decoded })
    }

    @Test
    fun glareFrameAboveThresholdThatFailsToDecodeIsLowLightFalse() {
        val analyzer = started(FakeDecoder())

        assertEquals(listOf(noDecode(1, lowLight = false)), analyzer.submitFrame(frame(1, luma = 0.31)))
        assertEquals(1, analyzer.framesDecoded)
        assertEquals(0, analyzer.framesSkippedDark)
    }

    // ---------------------------------------------------------------- decoder failure

    @Test
    fun decoderExceptionBecomesErrorAndNeverPropagatesOrLeaksItsMessage() {
        val decoder = FakeDecoder().apply {
            error = IllegalStateException("boom upi://pay?pa=secret@okaxis")
            errorAtCall = 1
        }
        val analyzer = started(decoder)

        val events = analyzer.submitFrame(frame(1))

        val error = events.single() as AnalyzerEvent.Error
        assertEquals("decoder-failed:IllegalStateException", error.message)
        assertFalse(error.message.contains("secret"))
        assertFalse(error.message.contains("upi://"))
        assertFalse(error.message.contains("boom"))
        // A decoder fault is neither a no-decode nor a reset.
        assertEquals(0, analyzer.noDecodeStreak)
        assertEquals(1, analyzer.framesDecoded)
    }

    @Test
    fun decoderErrorKeepsTheStreakIntactAndTheScannerAlive() {
        val decoder = FakeDecoder(listOf(null, null, null, null, null, null), onCall = { _, self ->
            if (self.calls.size == 3) {
                self.error = IllegalArgumentException("vendor failure")
                self.errorAtCall = 3
            }
        })
        val analyzer = started(decoder)

        analyzer.submitFrame(frame(1))
        analyzer.submitFrame(frame(2))
        assertEquals(listOf(AnalyzerEvent.Error("decoder-failed:IllegalArgumentException")), analyzer.submitFrame(frame(3)))
        assertEquals(2, analyzer.noDecodeStreak)

        // Frames 4-6 continue the same run: 3, 4, 5 -> Unreadable since the first no-decode (100 ms).
        assertEquals(listOf(noDecode(3)), analyzer.submitFrame(frame(4)))
        assertEquals(listOf(noDecode(4)), analyzer.submitFrame(frame(5)))
        assertEquals(listOf(noDecode(5), AnalyzerEvent.Unreadable(sinceMs = 100L)), analyzer.submitFrame(frame(6)))
    }

    // ---------------------------------------------------------------- rapid-scan debounce

    @Test
    fun identicalPayloadInsideDebounceWindowIsNotReEmitted() {
        val payload = "upi://pay?pa=shop@okaxis"
        val analyzer = started(FakeDecoder(listOf(payload, payload, payload)))

        assertEquals(listOf(AnalyzerEvent.Decoded(payload, 1L, 1000L)), analyzer.submitFrame(frame(1, ts = 1000L)))
        assertTrue(analyzer.submitFrame(frame(2, ts = 2000L)).isEmpty())
        // 1600 ms after the last emission: the window has expired, so it is a fresh scan.
        assertEquals(listOf(AnalyzerEvent.Decoded(payload, 3L, 2600L)), analyzer.submitFrame(frame(3, ts = 2600L)))
    }

    @Test
    fun suppressedDuplicateStillResetsTheNoDecodeStreak() {
        val payload = "upi://pay?pa=shop@okaxis"
        val analyzer = started(FakeDecoder(listOf(payload, null, null, payload)))

        assertEquals(1, analyzer.submitFrame(frame(1, ts = 1000L)).size)
        analyzer.submitFrame(frame(2, ts = 1100L))
        analyzer.submitFrame(frame(3, ts = 1200L))
        assertEquals(2, analyzer.noDecodeStreak)

        assertTrue(analyzer.submitFrame(frame(4, ts = 1300L)).isEmpty())
        assertEquals(0, analyzer.noDecodeStreak)
    }

    @Test
    fun differentPayloadIsEmittedImmediately() {
        val first = "upi://pay?pa=sharma.store@okhdfcbank"
        val second = "upi://pay?pa=98xxxxxx@ybl"
        val analyzer = started(FakeDecoder(listOf(first, second)))

        assertEquals(listOf(AnalyzerEvent.Decoded(first, 1L, 1000L)), analyzer.submitFrame(frame(1, ts = 1000L)))
        assertEquals(listOf(AnalyzerEvent.Decoded(second, 2L, 1050L)), analyzer.submitFrame(frame(2, ts = 1050L)))
    }

    @Test
    fun zeroDebounceEmitsEveryDecode() {
        val payload = "upi://pay?pa=shop@okaxis"
        val decoder = FakeDecoder(listOf(payload, payload))
        val analyzer = started(decoder, debounceMs = 0L)

        assertEquals(1, analyzer.submitFrame(frame(1, ts = 1000L)).size)
        assertEquals(1, analyzer.submitFrame(frame(2, ts = 1001L)).size)
    }

    // ---------------------------------------------------------------- lifecycle

    @Test
    fun startWithoutGrantedPermissionIsRefusedAndFramesAreIgnored() {
        val decoder = FakeDecoder(listOf("upi://pay?pa=shop@okaxis"))
        val analyzer = QrCameraAnalyzer(decoder)

        assertEquals(
            StartResult.Refused(QrCameraAnalyzer.REASON_PERMISSION_UNKNOWN),
            analyzer.start(CameraPermissionState.Unknown),
        )
        assertEquals(
            StartResult.Refused(QrCameraAnalyzer.REASON_PERMISSION_DENIED),
            analyzer.start(CameraPermissionState.Denied),
        )
        assertEquals(
            StartResult.Refused(QrCameraAnalyzer.REASON_PERMISSION_PERMANENTLY_DENIED),
            analyzer.start(CameraPermissionState.PermanentlyDenied),
        )
        assertEquals(QrCameraAnalyzer.State.Idle, analyzer.state)

        assertTrue(analyzer.submitFrame(frame(1)).isEmpty())
        assertTrue(decoder.calls.isEmpty())
        assertEquals(1, analyzer.framesIgnoredWhileNotRunning)

        assertEquals(StartResult.Started, analyzer.start(CameraPermissionState.Granted))
        assertEquals(QrCameraAnalyzer.State.Running, analyzer.state)
        assertEquals(1, analyzer.submitFrame(frame(2)).size)
    }

    @Test
    fun closingDuringAnInFlightDecodeEmitsNothingAfterwards() {
        lateinit var analyzer: QrCameraAnalyzer
        val decoder = FakeDecoder(listOf("upi://pay?pa=shop@okaxis"), onCall = { _, self ->
            if (self.calls.size == 1) {
                // Teardown from the adapter callback while a decode is still in flight, with a frame
                // already waiting in the pending slot.
                analyzer.submitFrame(frame(9))
                assertEquals(listOf(AnalyzerEvent.Closed), analyzer.close())
            }
        })
        analyzer = QrCameraAnalyzer(decoder)
        analyzer.start(CameraPermissionState.Granted)

        assertTrue(analyzer.submitFrame(frame(1)).isEmpty())

        assertEquals(QrCameraAnalyzer.State.Closed, analyzer.state)
        // The pending frame 9 was dropped by close(), the in-flight payload was discarded.
        assertEquals(listOf(1L), decoder.calls)
    }

    @Test
    fun framesAfterCloseAreIgnoredAndCounted() {
        val decoder = FakeDecoder(listOf("upi://pay?pa=shop@okaxis"))
        val analyzer = started(decoder)
        analyzer.close()

        assertTrue(analyzer.submitFrame(frame(1)).isEmpty())
        assertTrue(analyzer.submitFrame(frame(2)).isEmpty())

        assertEquals(2, analyzer.framesIgnoredAfterClose)
        assertTrue(decoder.calls.isEmpty())
        assertEquals(0, analyzer.framesIgnoredWhileNotRunning)
    }

    @Test
    fun closeIsIdempotent() {
        val analyzer = started(FakeDecoder())

        assertEquals(listOf(AnalyzerEvent.Closed), analyzer.close())
        assertTrue(analyzer.close().isEmpty())
        assertTrue(analyzer.close().isEmpty())
        assertEquals(QrCameraAnalyzer.State.Closed, analyzer.state)
    }

    @Test
    fun startAfterCloseIsRefused() {
        val analyzer = started(FakeDecoder())
        analyzer.close()

        assertEquals(
            StartResult.Refused(QrCameraAnalyzer.REASON_ANALYZER_CLOSED),
            analyzer.start(CameraPermissionState.Granted),
        )
        assertEquals(QrCameraAnalyzer.State.Closed, analyzer.state)
    }

    @Test
    fun startWhileRunningIsANoOpThatKeepsCounters() {
        val analyzer = started(FakeDecoder())
        analyzer.submitFrame(frame(1))

        assertEquals(StartResult.Started, analyzer.start(CameraPermissionState.Granted))
        assertEquals(1, analyzer.noDecodeStreak)
        assertEquals(1, analyzer.framesDecoded)
    }

    // ---------------------------------------------------------------- data contract

    @Test(expected = IllegalArgumentException::class)
    fun frameStatsRejectsLumaOutsideTheUnitRange() {
        FrameStats(frameId = 1L, widthPx = 640, heightPx = 480, meanLuma0to1 = 1.4, timestampMs = 0L)
    }

    @Test(expected = IllegalArgumentException::class)
    fun frameStatsRejectsAZeroSizedFrame() {
        FrameStats(frameId = 1L, widthPx = 0, heightPx = 480, meanLuma0to1 = 0.5, timestampMs = 0L)
    }
}
