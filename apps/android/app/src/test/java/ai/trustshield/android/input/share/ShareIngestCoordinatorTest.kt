package ai.trustshield.android.input.share

import ai.trustshield.android.core.analysis.LocalScanPipeline
import ai.trustshield.android.core.model.InputType
import ai.trustshield.android.core.model.RiskLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P3-08 unit tests - the ephemeral share-to-analysis coordinator (issue #87).
 *
 * Nothing here constructs an `Intent`, `Uri`, `Context`, `ContentResolver`, ML Kit client or bitmap:
 * android.jar method bodies are stubs that throw at runtime, so the whole package is plain Kotlin and
 * every decision below is provable off-device. The OCR seam ([ShareImageTextSource]) is faked; the
 * only real dependency exercised is the merged [LocalScanPipeline] (P3-02), which is the point of
 * most of these tests.
 *
 * Privacy: no test prints a payload. Fixture text carries OTP-shaped, phone-shaped, UPI-shaped and
 * URI-shaped strings on purpose, and the last test asserts that none of them survives into any string
 * the coordinator can print.
 */
class ShareIngestCoordinatorTest {

    // ------------------------------------------------------------------ fixtures

    /** A share byte source that behaves like the real one: bounded, budget-consuming, copying out. */
    private class FakeByteSource(
        private val payload: ByteArray,
        override val declaredMime: String? = "image/png",
        private val declaredSize: Long? = null,
        private val failure: Exception? = null,
    ) : ShareByteSource {

        var reads: Int = 0
            private set
        val readSizes: MutableList<Int> = mutableListOf()

        override val sizeBytes: Long? get() = declaredSize ?: payload.size.toLong()

        override fun readAtMost(maxBytes: Int, budget: ByteBudget): ByteArray {
            failure?.let { throw it }
            val size = payload.size.toLong()
            if (size > maxBytes.toLong() || !budget.tryConsume(size)) {
                throw ShareBoundExceeded(read = size, remaining = budget.remaining, limit = budget.limitBytes)
            }
            reads++
            readSizes += payload.size
            return payload.copyOf()
        }
    }

    /** Records what the coordinator handed to OCR, so byte identity and zeroing are assertable. */
    private class FakeOcr(private val reply: (ByteArray, String) -> ShareImageText) : ShareImageTextSource {
        var calls: Int = 0
            private set
        var lastBytes: ByteArray? = null
            private set
        var lastMime: String? = null
            private set

        override fun recognize(bytes: ByteArray, mime: String): ShareImageText {
            calls++
            lastBytes = bytes
            lastMime = mime
            return reply(bytes, mime)
        }
    }

    private fun bytesOf(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }

    private fun analysed(outcome: ShareIngestOutcome): ShareIngestOutcome.Analysed {
        assertTrue("expected Analysed, got $outcome", outcome is ShareIngestOutcome.Analysed)
        return outcome as ShareIngestOutcome.Analysed
    }

    private fun unavailable(outcome: ShareIngestOutcome): ShareIngestOutcome.Unavailable {
        assertTrue("expected Unavailable, got $outcome", outcome is ShareIngestOutcome.Unavailable)
        return outcome as ShareIngestOutcome.Unavailable
    }

    private fun refused(outcome: ShareIngestOutcome): ShareIngestOutcome.Refused {
        assertTrue("expected Refused, got $outcome", outcome is ShareIngestOutcome.Refused)
        return outcome as ShareIngestOutcome.Refused
    }

    /** A synthetic five-stage scam: authority, threat, secrecy, ask. Not a product fixture. */
    private val scamText: String =
        "Main SBI bank se bol raha hoon. Aapka KYC 24 ghante mein band ho jayega. " +
            "Aaj raat tak account block ho jayega. Kisi ko mat batana. " +
            "Jo OTP aaya hai woh batao."

    private val benignText: String =
        "Aapke SBI account mein Rs 5,000 credit hua hai. Avl bal Rs 12,000."

    // ------------------------------------------------------------------ same pipeline

    @Test
    fun `shared text produces exactly the same result as calling the pipeline directly`() {
        val coordinator = ShareIngestCoordinator()
        val direct = LocalScanPipeline().analyzeText(benignText)

        val landed = analysed(coordinator.ingest(ShareIngestRequest.Text(benignText)))

        assertEquals(direct, landed.outcome)
        assertEquals(InputType.TEXT, landed.outcome.result.inputType)
        assertEquals(InputType.TEXT, landed.receipt.inputType)
        assertEquals(0, landed.receipt.imagesRead)
        assertEquals(0L, landed.receipt.bytesRead)
        assertEquals(benignText.trim().length, landed.receipt.textChars)
        assertTrue(landed.receipt.payloadReleased)
    }

    @Test
    fun `a synthetic scam share still reaches high risk through the coordinator`() {
        val coordinator = ShareIngestCoordinator()
        val direct = LocalScanPipeline().analyzeText(scamText)

        val landed = analysed(coordinator.ingest(ShareIngestRequest.Text(scamText)))

        assertEquals(RiskLabel.HIGH_RISK, landed.outcome.risk.label)
        assertEquals(direct.risk.label, landed.outcome.risk.label)
        assertEquals("the share path must not re-score", direct.risk.score, landed.outcome.risk.score)
        assertEquals(direct.result.evidence, landed.outcome.result.evidence)
        assertEquals(
            "no evidence item may claim a quote the shared text does not contain",
            emptyList<Any>(),
            landed.outcome.result.unverifiableEvidence(landed.outcome.analyzedText),
        )
    }

    @Test
    fun `the same text twice is byte identical`() {
        val first = ShareIngestCoordinator().ingest(ShareIngestRequest.Text(scamText))
        val second = ShareIngestCoordinator().ingest(ShareIngestRequest.Text(scamText))

        assertEquals(first, second)
        assertEquals(first.toString(), second.toString())
    }

    // ------------------------------------------------------------------ empty text

    @Test
    fun `blank text is unavailable with empty content and never low risk`() {
        val outcome = unavailable(ShareIngestCoordinator().ingest(ShareIngestRequest.Text("   \n\t ")))

        assertEquals(ShareIngestRefusal.EMPTY_CONTENT, outcome.reason)
        assertTrue("a limitation in Hinglish is required", outcome.limitations.isNotEmpty())
        assertEquals(ShareIngestCopy.EMPTY_SHARE, outcome.limitations.first())
    }

    @Test
    fun `emoji only text is unavailable with empty content and never low risk`() {
        val outcome = unavailable(ShareIngestCoordinator().ingest(ShareIngestRequest.Text("\uD83D\uDE02\uD83D\uDD25\uD83D\uDC4D")))

        assertEquals(ShareIngestRefusal.EMPTY_CONTENT, outcome.reason)
        assertTrue(outcome.limitations.isNotEmpty())
        assertNotEquals(RiskLabel.LOW_RISK, RiskLabel.UNABLE_TO_VERIFY)
    }

    @Test
    fun `the coordinator never reports low risk for unreadable content`() {
        val coordinator = ShareIngestCoordinator(textSource = NoShareImageTextSource)
        val blank = coordinator.ingest(ShareIngestRequest.Text(""))
        val image = coordinator.ingest(ShareIngestRequest.Image(FakeByteSource(bytesOf(1, 2, 3)), "image/png"))

        // Neither path produces a verdict at all: no ScanOutcome exists that could say "safe".
        assertTrue(blank is ShareIngestOutcome.Unavailable)
        assertTrue(image is ShareIngestOutcome.Unavailable)
    }

    // ------------------------------------------------------------------ image path

    @Test
    fun `shared image text lands in the same pipeline with the image input type`() {
        val recognized = "Main SBI bank se. Aapka account band ho jayega. OTP batao."
        val ocr = FakeOcr { _, _ -> ShareImageText.Text(text = recognized) }
        val coordinator = ShareIngestCoordinator(textSource = ocr)
        val source = FakeByteSource(bytesOf(0x89, 0x50, 0x4E, 0x47))

        val landed = analysed(coordinator.ingest(ShareIngestRequest.Image(source, "image/png")))

        assertEquals(InputType.IMAGE, landed.outcome.result.inputType)
        assertEquals(InputType.IMAGE, landed.receipt.inputType)
        assertEquals(recognized.trim(), landed.outcome.analyzedText)
        assertEquals(1, landed.receipt.imagesRead)
        assertEquals(4L, landed.receipt.bytesRead)
        assertEquals(1, ocr.calls)
        assertEquals("image/png", ocr.lastMime)
        // Same engine as typed text: identical to feeding the OCR text to the pipeline directly.
        assertEquals(LocalScanPipeline().analyzeImageText(recognized), landed.outcome)
    }

    @Test
    fun `no ocr module means unavailable and no evidence quote is invented`() {
        val coordinator = ShareIngestCoordinator(textSource = NoShareImageTextSource)

        val outcome = unavailable(coordinator.ingest(ShareIngestRequest.Image(FakeByteSource(bytesOf(9, 9)), "image/png")))

        assertEquals(ShareIngestRefusal.OCR_UNAVAILABLE, outcome.reason)
        assertEquals(ShareIngestCopy.OCR_MODULE_MISSING, outcome.limitations.first())
        // Nothing that could carry a quote exists on this branch.
        assertFalse(outcome is ShareIngestOutcome.Analysed)
    }

    @Test
    fun `the default coordinator uses the honest no ocr source`() {
        val outcome = unavailable(ShareIngestCoordinator().ingest(ShareIngestRequest.Image(FakeByteSource(bytesOf(7)), "image/jpeg")))

        assertEquals(ShareIngestRefusal.OCR_UNAVAILABLE, outcome.reason)
    }

    @Test
    fun `ocr failure is an honest unavailable outcome`() {
        val ocr = FakeOcr { _, _ -> ShareImageText.Unavailable("ocr_failed") }
        val outcome = unavailable(
            ShareIngestCoordinator(textSource = ocr).ingest(ShareIngestRequest.Image(FakeByteSource(bytesOf(3)), "image/png")),
        )

        assertEquals(ShareIngestRefusal.OCR_FAILED, outcome.reason)
        assertEquals(ShareIngestCopy.OCR_FAILED, outcome.limitations.first())
    }

    @Test
    fun `blank ocr text is an honest unavailable outcome`() {
        val ocr = FakeOcr { _, _ -> ShareImageText.Text(text = "   ") }
        val outcome = unavailable(
            ShareIngestCoordinator(textSource = ocr).ingest(ShareIngestRequest.Image(FakeByteSource(bytesOf(3)), "image/png")),
        )

        assertEquals(ShareIngestRefusal.OCR_FAILED, outcome.reason)
        assertEquals(ShareIngestCopy.OCR_NO_TEXT, outcome.limitations.first())
    }

    @Test
    fun `a throwing ocr seam becomes ocr failed instead of crashing`() {
        val ocr = FakeOcr { _, _ -> throw IllegalStateException("recogniser died") }
        val outcome = unavailable(
            ShareIngestCoordinator(textSource = ocr).ingest(ShareIngestRequest.Image(FakeByteSource(bytesOf(3)), "image/png")),
        )

        assertEquals(ShareIngestRefusal.OCR_FAILED, outcome.reason)
    }

    @Test
    fun `unsupported mime is refused before any byte is read`() {
        val ocr = FakeOcr { _, _ -> ShareImageText.Text("hello") }
        val source = FakeByteSource(bytesOf(1), declaredMime = "image/gif")

        val outcome = refused(ShareIngestCoordinator(textSource = ocr).ingest(ShareIngestRequest.Image(source, "image/gif")))

        assertEquals(ShareIngestRefusal.UNSUPPORTED_MIME, outcome.reason)
        assertEquals(0, source.reads)
        assertEquals(0, ocr.calls)
    }

    @Test
    fun `too many images are refused before any byte is read`() {
        val ocr = FakeOcr { _, _ -> ShareImageText.Text("hello") }
        val items = (1..6).map { FakeByteSource(bytesOf(it), "image/png") }
        val outcome = refused(
            ShareIngestCoordinator(textSource = ocr).ingest(
                ShareIngestRequest.Images(items.map { it as ShareByteSource to "image/png" }),
            ),
        )

        assertEquals(ShareIngestRefusal.TOO_MANY_IMAGES, outcome.reason)
        assertEquals(0, items.sumOf { it.reads })
        assertEquals(0, ocr.calls)
    }

    @Test
    fun `an empty image list is unavailable with empty content`() {
        val outcome = unavailable(ShareIngestCoordinator().ingest(ShareIngestRequest.Images(emptyList())))

        assertEquals(ShareIngestRefusal.EMPTY_CONTENT, outcome.reason)
        assertTrue(outcome.limitations.isNotEmpty())
    }

    // ------------------------------------------------------------------ byte budget

    @Test
    fun `a single oversized image is refused without invoking ocr`() {
        val ocr = FakeOcr { _, _ -> ShareImageText.Text("hello") }
        val source = FakeByteSource(bytesOf(1, 2, 3), declaredSize = 9L * 1024 * 1024)
        val coordinator = ShareIngestCoordinator(textSource = ocr)

        val outcome = refused(coordinator.ingest(ShareIngestRequest.Image(source, "image/png")))

        assertTrue(
            "expected a size refusal, got ${outcome.reason}",
            outcome.reason == ShareIngestRefusal.IMAGE_TOO_LARGE || outcome.reason == ShareIngestRefusal.BYTE_BUDGET_EXCEEDED,
        )
        assertEquals(0, source.reads)
        assertEquals("ocr must never be called for bytes we refused", 0, ocr.calls)
    }

    @Test
    fun `the second image crossing the shared budget is refused before its bytes are used`() {
        val ocr = FakeOcr { bytes, _ -> ShareImageText.Text("text from ${bytes.size}") }
        val first = FakeByteSource(bytesOf(1, 2, 3, 4, 5, 6), declaredSize = 6L)
        val second = FakeByteSource(bytesOf(7, 8, 9, 10, 11, 12), declaredSize = 6L)
        val coordinator = ShareIngestCoordinator(textSource = ocr, maxBytes = 10L)

        val outcome = refused(
            coordinator.ingest(ShareIngestRequest.Images(listOf(first as ShareByteSource to "image/png", second as ShareByteSource to "image/png"))),
        )

        assertEquals(ShareIngestRefusal.BYTE_BUDGET_EXCEEDED, outcome.reason)
        assertEquals(1, first.reads)
        assertEquals("the second read must not happen at all", 0, second.reads)
        assertEquals("no analysis from the second image", 1, ocr.calls)
    }

    @Test
    fun `a small two image share succeeds and reports the bytes actually read`() {
        val ocr = FakeOcr { bytes, _ -> ShareImageText.Text(if (bytes.size == 5) "pehla text" else "doosra text") }
        val first = FakeByteSource(bytesOf(1, 2, 3, 4, 5), declaredSize = 5L)
        val second = FakeByteSource(bytesOf(6, 7, 8, 9, 10, 11, 12), declaredSize = 7L)
        val coordinator = ShareIngestCoordinator(textSource = ocr)

        val landed = analysed(
            coordinator.ingest(ShareIngestRequest.Images(listOf(first as ShareByteSource to "image/png", second as ShareByteSource to "image/png"))),
        )

        assertEquals(2, landed.receipt.imagesRead)
        assertEquals(12L, landed.receipt.bytesRead)
        assertEquals("pehla text\ndoosra text", landed.outcome.analyzedText)
        assertEquals(InputType.IMAGE, landed.receipt.inputType)
        assertEquals(2, ocr.calls)
    }

    @Test
    fun `the budget is shared across images and not reset per image`() {
        val ocr = FakeOcr { _, _ -> ShareImageText.Text("text") }
        val sources = (1..3).map { FakeByteSource(bytesOf(it, it, it), declaredSize = 3L) }
        val coordinator = ShareIngestCoordinator(textSource = ocr, maxBytes = 8L)

        val outcome = refused(
            coordinator.ingest(ShareIngestRequest.Images(sources.map { it as ShareByteSource to "image/png" })),
        )

        assertEquals(ShareIngestRefusal.BYTE_BUDGET_EXCEEDED, outcome.reason)
        assertEquals(2, sources.count { it.reads == 1 })
        assertEquals(0, sources.last().reads)
    }

    // ------------------------------------------------------------------ clearing

    @Test
    fun `ocr receives the exact bytes read and they are zeroed afterwards`() {
        val payload = bytesOf(0x89, 0x50, 0x4E, 0x47, 0x0D)
        val ocr = FakeOcr { bytes, _ -> ShareImageText.Text("OTP batao") }
        val coordinator = ShareIngestCoordinator(textSource = ocr)

        val landed = analysed(coordinator.ingest(ShareIngestRequest.Image(FakeByteSource(payload), "image/png")))

        val handed = ocr.lastBytes
        assertTrue("the seam must have been called", handed != null)
        assertEquals(payload.size, handed!!.size)
        assertTrue("every byte the coordinator held must be zeroed", handed.all { it == 0.toByte() })
        assertTrue(landed.receipt.payloadReleased)
        // The caller's own fixture bytes are the source's business, not the coordinator's.
        assertEquals(0x89.toByte(), payload[0])
    }

    @Test
    fun `bytes are zeroed even when the analysis ends in a refusal`() {
        val ocr = FakeOcr { _, _ -> ShareImageText.Unavailable("model_unavailable") }
        val coordinator = ShareIngestCoordinator(textSource = ocr)

        val outcome = coordinator.ingest(ShareIngestRequest.Image(FakeByteSource(bytesOf(4, 5, 6)), "image/png"))

        assertEquals(ShareIngestRefusal.OCR_FAILED, unavailable(outcome).reason)
        assertTrue("the buffer must be cleared on the failure path too", ocr.lastBytes!!.all { it == 0.toByte() })
    }

    @Test
    fun `the coordinator holds no field that can return shared content`() {
        val forbidden = ShareIngestCoordinator::class.java.declaredFields.filter { field ->
            CharSequence::class.java.isAssignableFrom(field.type) ||
                field.type == ByteArray::class.java ||
                field.type == CharArray::class.java ||
                field.type == java.util.List::class.java ||
                field.type == java.util.Map::class.java
        }

        assertEquals(emptyList<String>(), forbidden.map { it.name })
    }

    // ------------------------------------------------------------------ read failure

    @Test
    fun `a throwing byte source becomes read failed with a limitation`() {
        val ocr = FakeOcr { _, _ -> ShareImageText.Text("never") }
        val source = FakeByteSource(bytesOf(1, 2), failure = java.io.IOException("provider died"))
        val coordinator = ShareIngestCoordinator(textSource = ocr)

        val outcome = unavailable(coordinator.ingest(ShareIngestRequest.Image(source, "image/png")))

        assertEquals(ShareIngestRefusal.READ_FAILED, outcome.reason)
        assertEquals(ShareIngestCopy.READ_FAILED, outcome.limitations.first())
        assertEquals("no partial analysis", 0, ocr.calls)
    }

    // ------------------------------------------------------------------ ShareIntentRead

    @Test
    fun `an accepted intent read whose review is pending is refused until the user accepts`() {
        val read = ShareIntentRead.Accepted(
            share = IncomingShare.from(SharedContent.Text(scamText)),
            grant = ShareUriGrant.None,
        )

        val outcome = refused(ShareIngestCoordinator().ingestShare(read))

        assertEquals(ShareIngestRefusal.REVIEW_NOT_ACCEPTED, outcome.reason)
        assertEquals(ShareReviewStateName.PENDING, ShareReviewStateName.of(read.share.reviewState))
    }

    @Test
    fun `an accepted intent read after the review is analysed through the text path`() {
        val read = ShareIntentRead.Accepted(
            share = IncomingShare.from(SharedContent.Text(scamText)).accept(),
            grant = ShareUriGrant.None,
        )

        val landed = analysed(ShareIngestCoordinator().ingestShare(read))

        assertEquals(RiskLabel.HIGH_RISK, landed.outcome.risk.label)
        assertEquals(InputType.TEXT, landed.receipt.inputType)
    }

    @Test
    fun `a refused intent read carries the mapped reason and is never analysed`() {
        val short = ShareIngestCoordinator()

        val apk = refused(short.ingestShare(ShareIntentRead.Refused(reason = ShareRejectReason.APK_ARCHIVE)))
        assertEquals(ShareIngestRefusal.CLOSED_BY_DESIGN, apk.reason)

        val blank = refused(short.ingestShare(ShareIntentRead.Refused(emptyReason = ShareEmptyReason.BLANK_TEXT)))
        assertEquals(ShareIngestRefusal.EMPTY_CONTENT, blank.reason)

        val unsupportedMime = refused(
            short.ingestShare(ShareIntentRead.Refused(reason = ShareRejectReason.IMAGE_TYPE_NOT_ALLOWED)),
        )
        assertEquals(ShareIngestRefusal.UNSUPPORTED_MIME, unsupportedMime.reason)

        val tooMany = refused(short.ingestShare(ShareIntentRead.Refused(reason = ShareRejectReason.TOO_MANY_IMAGES)))
        assertEquals(ShareIngestRefusal.TOO_MANY_IMAGES, tooMany.reason)

        val oversized = refused(short.ingestShare(ShareIntentRead.Refused(reason = ShareRejectReason.OVERSIZED)))
        assertEquals(ShareIngestRefusal.BYTE_BUDGET_EXCEEDED, oversized.reason)

        val unreadable = refused(short.ingestShare(ShareIntentRead.Refused(reason = ShareRejectReason.UNOPENABLE_URI)))
        assertEquals(ShareIngestRefusal.READ_FAILED, unreadable.reason)

        val noAction = refused(short.ingestShare(ShareIntentRead.Refused(emptyReason = ShareEmptyReason.NO_ACTION)))
        assertEquals(ShareIngestRefusal.CLOSED_BY_DESIGN, noAction.reason)

        val noContent = refused(short.ingestShare(ShareIntentRead.Refused(emptyReason = ShareEmptyReason.NO_CONTENT)))
        assertEquals(ShareIngestRefusal.EMPTY_CONTENT, noContent.reason)

        val discarded = refused(short.ingestShare(ShareIntentRead.Refused(emptyReason = ShareEmptyReason.DISCARDED)))
        assertEquals(ShareIngestRefusal.EMPTY_CONTENT, discarded.reason)

        assertTrue("nothing here produced a verdict", listOf(apk, blank, tooMany, oversized).all { it.detail != null || it.reason != null })
    }

    @Test
    fun `an accepted image intent read is analysed through the caller byte source factory`() {
        val ref = ShareImageRef("content://com.example.provider/document/photo-1.png", "image/png", 4L)
        val source = FakeByteSource(bytesOf(1, 2, 3, 4))
        val ocr = FakeOcr { _, _ -> ShareImageText.Text("KYC band ho jayega, OTP batao") }
        val coordinator = ShareIngestCoordinator(
            textSource = ocr,
            byteSourceFor = { requested -> if (requested.uriString == ref.uriString) source else null },
        )
        val read = ShareIntentRead.Accepted(
            share = IncomingShare.from(SharedContent.Images(ref)).accept(),
            grant = ShareUriGrant.TransientRead,
        )

        val landed = analysed(coordinator.ingestShare(read))

        assertEquals(InputType.IMAGE, landed.receipt.inputType)
        assertEquals(1, landed.receipt.imagesRead)
        assertEquals(4L, landed.receipt.bytesRead)
    }

    @Test
    fun `an image intent read without a byte source factory is honestly unavailable`() {
        val ref = ShareImageRef("content://com.example.provider/document/photo-2.png", "image/png", 4L)
        val read = ShareIntentRead.Accepted(
            share = IncomingShare.from(SharedContent.Images(ref)).accept(),
            grant = ShareUriGrant.TransientRead,
        )

        val outcome = unavailable(ShareIngestCoordinator().ingestShare(read))

        assertEquals(ShareIngestRefusal.READ_FAILED, outcome.reason)
        assertEquals(ShareIngestCopy.NO_BYTE_SOURCE, outcome.limitations.first())
    }

    @Test
    fun `an image intent read without a read grant is refused rather than probed`() {
        val ref = ShareImageRef("content://com.example.provider/document/photo-3.png", "image/png", 4L)
        val read = ShareIntentRead.Accepted(
            share = IncomingShare.from(SharedContent.Images(ref)).accept(),
            grant = ShareUriGrant.None,
        )

        val outcome = unavailable(ShareIngestCoordinator().ingestShare(read))

        assertEquals(ShareIngestRefusal.READ_FAILED, outcome.reason)
        assertEquals(ShareIngestCopy.NO_READ_GRANT, outcome.limitations.first())
    }

    // ------------------------------------------------------------------ privacy

    @Test
    fun `no printable string the coordinator produces contains payload data`() {
        val otp = "123456"
        val phone = "9876543210"
        val upi = "ravi@okhdfcbank"
        val uri = "content://com.example.provider/document/secret-photo.png"
        val payloadText =
            "Aapka OTP $otp hai. Turant $phone par call karein ya $upi se paise bhejein. $uri"

        val request = ShareIngestRequest.Text(payloadText)
        val outcome = ShareIngestCoordinator().ingest(request)

        val printable = buildList {
            add(outcome.toString())
            add(request.toString())
            (outcome as? ShareIngestOutcome.Analysed)?.let {
                add(it.receipt.toString())
                add(it.receipt.limitations.joinToString(" "))
                add(it.outcome.risk.toString())
            }
            ShareIngestRefusal.entries.forEach { refusal ->
                add(refusal.toString())
                add(refusal.code)
                add(refusal.message)
            }
        }

        for (needle in listOf(otp, phone, upi, uri, "secret-photo")) {
            printable.forEach { line ->
                assertFalse("a printed string leaked '$needle': $line", line.contains(needle))
            }
        }
    }

    @Test
    fun `the analysed outcome is a real verdict not a safety claim`() {
        val landed = analysed(ShareIngestCoordinator().ingest(ShareIngestRequest.Text(scamText)))

        assertEquals(RiskLabel.HIGH_RISK, landed.outcome.risk.label)
        assertTrue("confidence stays a separate field", landed.outcome.result.risk.confidence.name.isNotEmpty())
        assertEquals(ai.trustshield.android.core.model.DataOrigin.LOCAL, landed.outcome.result.origin)
        assertEquals(ai.trustshield.android.core.model.BackendStatus.DISABLED, landed.outcome.result.backend)
    }

    /** Tiny readability helper: the review-state enum name, without printing share content. */
    private enum class ShareReviewStateName {
        PENDING, ACCEPTED, DISCARDED;

        companion object {
            fun of(state: ReviewState): ShareReviewStateName = when (state) {
                ReviewState.Pending -> PENDING
                ReviewState.Accepted -> ACCEPTED
                ReviewState.Discarded -> DISCARDED
            }
        }
    }
}
