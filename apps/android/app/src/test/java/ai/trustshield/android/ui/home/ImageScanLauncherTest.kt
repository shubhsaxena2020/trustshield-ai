/*
 * P3-05 (issue #84) unit tests: image selection -> bounded read -> bundled OCR -> the SAME local
 * pipeline, with the image data cleared afterwards.
 *
 * Plain JVM. No `android.*` object is constructed or executed here: the fake OCR recogniser, the
 * in-memory byte source and the real [LocalScanPipeline] are all pure Kotlin. Fixtures are raw bytes
 * built in this file, so the test itself obeys the "nothing is read from disk" invariant it checks.
 *
 * The Compose shell at the bottom of ImageScanLauncher.kt (photo picker, inbound share grant) is NOT
 * exercised here - see the PR's known gaps: P3-18 runs that on an emulator, P3-06 wires it.
 */
package ai.trustshield.android.ui.home

import ai.trustshield.android.core.analysis.LocalScanPipeline
import ai.trustshield.android.core.analysis.ScanOutcome
import ai.trustshield.android.core.model.InputType
import ai.trustshield.android.core.model.NormalizedText
import ai.trustshield.android.core.model.RiskLabel
import ai.trustshield.android.core.model.TextNormalizer
import ai.trustshield.android.core.normalize.HinglishNormalizer
import ai.trustshield.android.input.share.ByteRead
import ai.trustshield.android.input.share.ByteSource
import ai.trustshield.android.input.share.ImagePolicy
import ai.trustshield.android.input.share.InMemoryByteSource
import ai.trustshield.android.input.share.OcrFailure
import ai.trustshield.android.input.share.OcrOutcome
import ai.trustshield.android.input.share.OcrResult
import ai.trustshield.android.input.share.OcrScript
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageScanLauncherTest {

    // ---------------------------------------------------------------- fixtures (raw bytes, no disk)

    private val pngSignature =
        byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    private fun be32(value: Int): ByteArray = byteArrayOf(
        (value ushr 24).toByte(),
        (value ushr 16).toByte(),
        (value ushr 8).toByte(),
        value.toByte(),
    )

    /** PNG signature + IHDR: 33 bytes with real width/height, enough for the B07 sniffer. */
    private fun png(width: Int, height: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(pngSignature)
        out.write(byteArrayOf(0x00, 0x00, 0x00, 0x0D)) // IHDR payload length
        out.write("IHDR".toByteArray(Charsets.US_ASCII))
        out.write(be32(width))
        out.write(be32(height))
        out.write(byteArrayOf(8, 6, 0, 0, 0)) // bit depth, colour type, compression, filter, interlace
        out.write(byteArrayOf(0, 0, 0, 0)) // CRC placeholder - the sniffer never checks it
        return out.toByteArray()
    }

    /** A GIF header: a real container, just not one B07 accepts. */
    private fun gif(): ByteArray = ByteArray(32).also {
        "GIF89a".toByteArray(Charsets.US_ASCII).copyInto(it, 0)
    }

    private val photoUri = "content://com.whatsapp.provider.media/item/987654321"

    private val ocrMessage =
        "SBI KYC: aapka account 24 ghante mein band ho jayega. Turant is number par call karein " +
            "aur OTP 483920 batayein."

    // ---------------------------------------------------------------- doubles

    /** In-memory source that remembers the exact buffer it handed out, so zeroing is observable. */
    private class RecordingByteSource(private val content: ByteArray) : ByteSource {
        var reads: Int = 0
        var handedOut: ByteArray? = null

        override fun read(maxBytes: Int): ByteRead {
            reads++
            val take = if (maxBytes < content.size) maxBytes else content.size
            val out = content.copyOf(take)
            handedOut = out
            return ByteRead(bytes = out, truncated = content.size > take, totalBytes = content.size.toLong())
        }
    }

    private class FakeOcr(private val outcome: OcrOutcome) : OcrRecognizer {
        var calls: Int = 0
        var lastMime: String? = null
        var lastSize: Int = -1
        var lastBytes: ByteArray? = null

        override suspend fun recognize(bytes: ByteArray, declaredMime: String?): OcrOutcome {
            calls++
            lastMime = declaredMime
            lastSize = bytes.size
            lastBytes = bytes
            return outcome
        }
    }

    private class ThrowingOcr : OcrRecognizer {
        var calls: Int = 0

        override suspend fun recognize(bytes: ByteArray, declaredMime: String?): OcrOutcome {
            calls++
            throw IllegalStateException("recogniser blew up")
        }
    }

    /** Counts pipeline invocations without touching it: the pipeline normalises exactly once per scan. */
    private class CountingNormalizer(
        private val delegate: TextNormalizer = HinglishNormalizer(),
    ) : TextNormalizer {
        var calls: Int = 0

        override fun normalize(raw: String): NormalizedText {
            calls++
            return delegate.normalize(raw)
        }
    }

    private fun recognised(text: String = ocrMessage, script: OcrScript = OcrScript.LATIN): OcrOutcome =
        OcrOutcome.Recognized(
            OcrResult(text = text, script = script, blockCount = 2, meanConfidence = 0.91),
        )

    /** One controller wired to fakes; returns the controller and the collaborators the test inspects. */
    private class Harness(
        val controller: ImageScanController,
        val normalizer: CountingNormalizer,
        val ocr: OcrRecognizer,
        val source: RecordingByteSource?,
        val uriRequested: List<String>,
    )

    private fun harness(
        bytes: ByteArray?,
        ocr: OcrRecognizer,
        policy: ImagePolicy = ImagePolicy(),
        mime: String? = "image/png",
        source: RecordingByteSource? = null,
    ): Harness {
        val normalizer = CountingNormalizer()
        val pipeline = LocalScanPipeline(normalizer = normalizer)
        val recording = source ?: bytes?.let { RecordingByteSource(it) }
        val requested = ArrayList<String>()
        val controller = ImageScanController(
            pipeline = pipeline,
            recognizer = ocr,
            policy = policy,
            readBytes = { uri ->
                requested += uri
                recording ?: bytes?.let { InMemoryByteSource(it) }
            },
        )
        return Harness(controller, normalizer, ocr, recording, requested)
    }

    private fun scan(
        harness: Harness,
        uri: String = photoUri,
        mime: String? = "image/png",
    ): ImageScanState = runBlocking { harness.controller.scan(uri, mime) }

    // ---------------------------------------------------------------- happy path

    @Test
    fun `a valid png goes through bounds, ocr and the real pipeline to done`() {
        val ocr = FakeOcr(recognised())
        val bytes = png(1080, 2400)
        val h = harness(bytes, ocr)

        val state = scan(h)

        assertEquals(ImageScanPhase.DONE, state.phase)
        assertNull(state.error)
        assertNotNull("a finished scan must hand back an outcome", state.outcome)
        val outcome = state.outcome!!
        assertEquals(ocrMessage, outcome.analyzedText)
        assertTrue(outcome.result.unverifiableEvidence(outcome.analyzedText).isEmpty())
        assertEquals("image text must be stamped as IMAGE input", InputType.IMAGE, outcome.result.inputType)

        val preview = state.preview
        assertNotNull("the preview must be populated before OCR runs", preview)
        assertEquals(1080, preview!!.width)
        assertEquals(2400, preview.height)
        assertEquals(bytes.size.toLong(), preview.byteCount)

        assertEquals(1, ocr.calls)
        assertEquals("image/png", ocr.lastMime)
        assertEquals(bytes.size, ocr.lastSize)
        // One `analyzeImageText` normalises more than once internally, so the reference count is
        // measured rather than guessed: whatever one analysis costs, the controller must have paid it
        // exactly once.
        val perScan = CountingNormalizer().let { counter ->
            LocalScanPipeline(normalizer = counter).analyzeImageText(ocrMessage)
            counter.calls
        }
        assertTrue("a reference analysis must normalise at least once", perScan > 0)
        assertEquals("the pipeline must have run exactly once", perScan, h.normalizer.calls)
        assertEquals(listOf(photoUri), h.uriRequested)
    }

    @Test
    fun `the preview source is a redacted label, never the real uri`() {
        val h = harness(png(10, 10), FakeOcr(recognised()))

        val state = scan(h)

        val source = state.preview!!.source
        assertFalse("the picked uri must never be echoed", source.contains("whatsapp"))
        assertFalse(source.contains("987654321"))
        assertFalse(source.contains(photoUri))
        assertTrue(source.contains("**"))
    }

    // ---------------------------------------------------------------- rejections

    @Test
    fun `a truncated png fails as corrupt with no ocr and no pipeline call`() {
        val ocr = FakeOcr(recognised())
        val truncated = png(800, 600).copyOf(16)
        val h = harness(truncated, ocr)

        val state = scan(h)

        assertEquals(ImageScanPhase.FAILED, state.phase)
        assertEquals(ImageScanError.CORRUPT_IMAGE, state.error)
        assertNull(state.outcome)
        assertEquals("a rejected image must never reach OCR", 0, ocr.calls)
        assertEquals("a rejected image must never reach the pipeline", 0, h.normalizer.calls)
    }

    @Test
    fun `an image over the byte policy is too large and nothing is decoded`() {
        val ocr = FakeOcr(recognised())
        val h = harness(png(64, 64), ocr, policy = ImagePolicy(maxBytes = 8))

        val state = scan(h)

        assertEquals(ImageScanPhase.FAILED, state.phase)
        assertEquals(ImageScanError.TOO_LARGE, state.error)
        assertEquals(0, ocr.calls)
        assertEquals(0, h.normalizer.calls)
        assertTrue(state.imageDataCleared)
    }

    @Test
    fun `an unsupported container is reported as an unsupported type`() {
        val ocr = FakeOcr(recognised())
        val h = harness(gif(), ocr)

        val state = scan(h)

        assertEquals(ImageScanPhase.FAILED, state.phase)
        assertEquals(ImageScanError.UNSUPPORTED_TYPE, state.error)
        assertEquals(0, ocr.calls)
        assertEquals(0, h.normalizer.calls)
    }

    @Test
    fun `a uri the caller cannot open is reported as uri unavailable`() {
        val ocr = FakeOcr(recognised())
        val normalizer = CountingNormalizer()
        val controller = ImageScanController(
            pipeline = LocalScanPipeline(normalizer = normalizer),
            recognizer = ocr,
            readBytes = { null },
        )

        val state = runBlocking { controller.scan(photoUri, "image/png") }

        assertEquals(ImageScanPhase.FAILED, state.phase)
        assertEquals(ImageScanError.URI_UNAVAILABLE, state.error)
        assertEquals(0, ocr.calls)
        assertEquals(0, normalizer.calls)
        assertTrue(state.imageDataCleared)
    }

    @Test
    fun `a non content uri is refused before anything is opened`() {
        val ocr = FakeOcr(recognised())
        val h = harness(png(10, 10), ocr)

        val state = scan(h, uri = "https://example.test/kyc.png")

        assertEquals(ImageScanPhase.FAILED, state.phase)
        assertEquals(ImageScanError.URI_UNAVAILABLE, state.error)
        assertTrue("nothing may be opened for a link-shaped uri", h.uriRequested.isEmpty())
        assertEquals(0, ocr.calls)
        assertEquals(0, h.normalizer.calls)
    }

    // ---------------------------------------------------------------- ocr failures

    @Test
    fun `ocr no text says to send a clean screenshot and never runs the pipeline`() {
        val ocr = FakeOcr(OcrOutcome.Failed(OcrFailure.NO_TEXT))
        val h = harness(png(10, 10), ocr)

        val state = scan(h)

        assertEquals(ImageScanPhase.FAILED, state.phase)
        assertEquals(ImageScanError.NO_TEXT, state.error)
        assertTrue(
            "NO_TEXT must tell the user to send a clean screenshot",
            state.error!!.nextStepHinglish.lowercase().contains("saaf"),
        )
        assertTrue(state.error!!.nextStepEnglish.lowercase().contains("clean"))
        assertEquals(0, h.normalizer.calls)
        assertTrue(state.imageDataCleared)
    }

    @Test
    fun `an unavailable ocr model is reported honestly and never as a verdict`() {
        val ocr = FakeOcr(OcrOutcome.Failed(OcrFailure.MODEL_UNAVAILABLE))
        val h = harness(png(10, 10), ocr)

        val state = scan(h)

        assertEquals(ImageScanPhase.FAILED, state.phase)
        assertEquals(ImageScanError.OCR_UNAVAILABLE, state.error)
        assertNull(state.outcome)
        assertEquals(0, h.normalizer.calls)
    }

    @Test
    fun `a throwing recogniser becomes ocr unavailable instead of a crash`() {
        val ocr = ThrowingOcr()
        val h = harness(png(10, 10), ocr)

        val state = scan(h)

        assertEquals(ImageScanPhase.FAILED, state.phase)
        assertEquals(ImageScanError.OCR_UNAVAILABLE, state.error)
        assertEquals(1, ocr.calls)
        assertTrue(state.imageDataCleared)
    }

    // ---------------------------------------------------------------- unable to verify

    @Test
    fun `an unable to verify verdict keeps the outcome but is marked as not safe to show`() {
        // Emoji-only OCR text: the real pipeline (A05) answers UNABLE_TO_VERIFY for it.
        val ocr = FakeOcr(recognised(text = "\uD83D\uDE00\uD83D\uDE00\uD83D\uDE00"))
        val h = harness(png(10, 10), ocr)

        val state = scan(h)

        assertEquals(ImageScanPhase.DONE, state.phase)
        assertEquals(ImageScanError.ANALYSIS_UNABLE_TO_VERIFY, state.error)
        val outcome = state.outcome
        assertNotNull("the outcome is still returned for display", outcome)
        assertEquals(RiskLabel.UNABLE_TO_VERIFY, outcome!!.risk.label)
        assertTrue(outcome.result.limitations.isNotEmpty())
        assertTrue(state.imageDataCleared)
    }

    @Test
    fun `the reducer marks an unable to verify outcome without dropping it`() {
        val pipeline = LocalScanPipeline()
        val outcome = pipeline.analyzeImageText("\uD83D\uDE00\uD83D\uDE00")
        assertEquals(RiskLabel.UNABLE_TO_VERIFY, outcome.risk.label)

        val next = reduce(ImageScanState(phase = ImageScanPhase.ANALYSING), ImageScanEvent.Analysed(outcome))

        assertEquals(ImageScanPhase.DONE, next.phase)
        assertEquals(ImageScanError.ANALYSIS_UNABLE_TO_VERIFY, next.error)
        assertSame(outcome, next.outcome)
    }

    // ---------------------------------------------------------------- ephemeral data

    @Test
    fun `the scanned bytes are zero filled and no uri or text survives the controller`() {
        val ocr = FakeOcr(recognised())
        val bytes = png(1080, 2400)
        val h = harness(bytes, ocr)

        val state = scan(h)

        assertEquals(ImageScanPhase.DONE, state.phase)
        assertTrue("a terminal state must report the image data cleared", state.imageDataCleared)

        val handed = h.source!!.handedOut
        assertNotNull("the source must have handed a buffer to the controller", handed)
        assertTrue(
            "the buffer that held the pixels must be zero filled",
            handed!!.none { it != 0.toByte() },
        )

        val described = h.controller.describeLastScan()
        assertFalse("diagnostics must never carry the uri", described.contains("content://"))
        assertFalse(described.contains("whatsapp"))
        assertFalse(described.contains("987654321"))
        assertFalse("diagnostics must never carry the recognised text", described.contains(ocrMessage))
        assertFalse(described.contains("483920"))
        assertFalse(described.contains("KYC"))
    }

    @Test
    fun `a rejected image is cleared too and its diagnostics carry no uri`() {
        val ocr = FakeOcr(recognised())
        val h = harness(gif(), ocr)

        val state = scan(h)

        assertEquals(ImageScanPhase.FAILED, state.phase)
        assertTrue(state.imageDataCleared)
        assertTrue(h.source!!.handedOut!!.none { it != 0.toByte() })

        val described = h.controller.describeLastScan()
        assertFalse(described.contains("content://"))
        assertFalse(described.contains("whatsapp"))
    }

    @Test
    fun `state observers see the phases but never the recognised text`() {
        val ocr = FakeOcr(recognised())
        val h = harness(png(10, 10), ocr)
        val seen = ArrayList<ImageScanState>()
        h.controller.onState = { seen += it }

        scan(h)

        assertTrue(seen.size >= 4)
        assertEquals(ImageScanPhase.READING, seen.first().phase)
        assertEquals(ImageScanPhase.DONE, seen.last().phase)
        assertTrue(seen.last().imageDataCleared)
        assertTrue(
            "a phase update must not carry the recognised text",
            seen.none { it.toString().contains(ocrMessage) },
        )
    }

    // ---------------------------------------------------------------- reducer

    @Test
    fun `every event is handled from every phase and busy phases cannot retry`() {
        val outcome = LocalScanPipeline().analyzeImageText(ocrMessage)
        val preview = ImagePreview(width = 8, height = 8, byteCount = 33, source = "content://**")
        val events: List<ImageScanEvent> = listOf(
            ImageScanEvent.Started,
            ImageScanEvent.BoundsAccepted(preview),
            ImageScanEvent.Rejected(ImageScanError.CORRUPT_IMAGE),
            ImageScanEvent.OcrDone(ocrMessage, OcrScript.LATIN),
            ImageScanEvent.OcrFailed(ImageScanError.NO_TEXT),
            ImageScanEvent.Analysed(outcome),
            ImageScanEvent.ClearRequested,
        )

        for (phase in ImageScanPhase.entries) {
            for (error in listOf(null, ImageScanError.URI_UNAVAILABLE)) {
                val start = ImageScanState(
                    phase = phase,
                    preview = null,
                    outcome = null,
                    error = error,
                    imageDataCleared = false,
                )
                for (event in events) {
                    val next = reduce(start, event) // must not throw, from any phase
                    assertTrue(
                        "phase ${start.phase} plus $event must land in a known phase",
                        ImageScanPhase.entries.contains(next.phase),
                    )
                    if (next.isBusy) {
                        assertFalse("a busy state must not offer a retry", next.canRetry)
                    }
                }
            }
        }

        assertEquals(
            listOf(ImageScanPhase.READING, ImageScanPhase.OCR, ImageScanPhase.ANALYSING),
            ImageScanPhase.entries.filter { ImageScanState(phase = it).isBusy },
        )
    }

    @Test
    fun `started resets everything and clear requested keeps the phase`() {
        val outcome = LocalScanPipeline().analyzeImageText(ocrMessage)
        val dirty = ImageScanState(
            phase = ImageScanPhase.DONE,
            preview = ImagePreview(1, 2, 33, "content://**"),
            outcome = outcome,
            error = null,
            imageDataCleared = false,
        )

        val started = reduce(dirty, ImageScanEvent.Started)
        assertEquals(ImageScanPhase.READING, started.phase)
        assertNull(started.preview)
        assertNull(started.outcome)
        assertNull(started.error)
        assertFalse(started.imageDataCleared)

        val cleared = reduce(dirty, ImageScanEvent.ClearRequested)
        assertEquals(ImageScanPhase.DONE, cleared.phase)
        assertTrue(cleared.imageDataCleared)
        assertSame(outcome, cleared.outcome)
    }

    @Test
    fun `the preview and the state redact everything they print`() {
        // A caller that passes a raw uri by mistake must still not print it.
        val sloppy = ImagePreview(width = 4, height = 4, byteCount = 33, source = photoUri)
        assertFalse(sloppy.toString().contains("whatsapp"))
        assertFalse(sloppy.toString().contains("987654321"))

        val state = ImageScanState(phase = ImageScanPhase.DONE, preview = sloppy, imageDataCleared = true)
        assertFalse(state.toString().contains("987654321"))
    }

    // ---------------------------------------------------------------- copy

    @Test
    fun `every error carries hinglish, english and a next step and none claims safety`() {
        for (error in ImageScanError.entries) {
            assertTrue("${error.code} hinglish copy", error.messageHinglish.isNotBlank())
            assertTrue("${error.code} english copy", error.messageEnglish.isNotBlank())
            assertTrue("${error.code} hinglish next step", error.nextStepHinglish.isNotBlank())
            assertTrue("${error.code} english next step", error.nextStepEnglish.isNotBlank())
            assertTrue("${error.code} code", error.code.isNotBlank() && error.code == error.code.lowercase())
            for (text in listOf(
                error.messageHinglish,
                error.messageEnglish,
                error.nextStepHinglish,
                error.nextStepEnglish,
            )) {
                assertFalse("${error.code} must not claim safety: $text", text.contains("100% safe", true))
                assertFalse(text.contains("100%"))
            }
            assertEquals(error.messageHinglish, error.message(english = false))
            assertEquals(error.messageEnglish, error.message(english = true))
            assertEquals(error.nextStepEnglish, error.nextStep(english = true))
            assertEquals(error.nextStepHinglish, error.nextStep(english = false))
        }
    }

    @Test
    fun `the content uri guard only accepts the content scheme`() {
        assertTrue(isContentUri("content://media/external/images/media/42"))
        assertFalse(isContentUri("https://example.test/a.png"))
        assertFalse(isContentUri("file:///sdcard/a.png"))
        assertFalse(isContentUri(""))
        assertFalse(isContentUri(photoUri.replace("content://", "CONTENT://")))
    }
}
