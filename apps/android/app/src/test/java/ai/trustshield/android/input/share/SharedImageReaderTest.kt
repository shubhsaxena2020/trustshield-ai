/*
 * B07 unit tests (issue #39). Fixtures are built here as raw bytes on purpose: nothing is read from
 * disk, so the test itself obeys the ephemeral invariant it is checking.
 *
 * NOTE: `:app:testDebugUnitTest` cannot compile any test in this repo yet - app/build.gradle.kts has no
 * `testImplementation`, so `org.junit.Test` is unresolved (Codex owns that file). These tests are
 * executed by the orchestrator's out-of-tree JVM harness; see the PR body for the verbatim run.
 */
package ai.trustshield.android.input.share

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SharedImageReaderTest {

    // ---------------------------------------------------------------- fixtures (raw bytes, no disk)

    private class Bytes {
        private val out = ByteArrayOutputStream()

        fun u8(v: Int) = apply { out.write(v and 0xFF) }

        fun u16be(v: Int) = apply { u8(v ushr 8); u8(v) }

        fun u16le(v: Int) = apply { u8(v); u8(v ushr 8) }

        fun u24le(v: Int) = apply { u8(v); u8(v ushr 8); u8(v ushr 16) }

        fun u32be(v: Long) = apply { u8((v ushr 24).toInt()); u8((v ushr 16).toInt()); u8((v ushr 8).toInt()); u8(v.toInt()) }

        fun u32le(v: Long) = apply { u8(v.toInt()); u8((v ushr 8).toInt()); u8((v ushr 16).toInt()); u8((v ushr 24).toInt()) }

        fun ascii(s: String) = apply { s.forEach { out.write(it.code and 0xFF) } }

        fun bytes(a: ByteArray) = apply { out.write(a, 0, a.size) }

        fun zeros(n: Int) = apply { repeat(n) { out.write(0) } }

        fun build(): ByteArray = out.toByteArray()
    }

    private val pngSignature = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    /** PNG signature + IHDR; 33 bytes, enough for a sniffer and nothing more. */
    private fun png(width: Long, height: Long, ihdrLength: Long = 13L): ByteArray = Bytes()
        .bytes(pngSignature)
        .u32be(ihdrLength)
        .ascii("IHDR")
        .u32be(width)
        .u32be(height)
        .u8(8) // bit depth
        .u8(6) // colour type
        .u8(0).u8(0).u8(0) // compression, filter, interlace
        .zeros(4) // CRC placeholder - the sniffer never checks it
        .build()

    /** One JPEG segment: 0xFF, marker, big-endian length that includes itself, then the payload. */
    private fun jpegSegment(marker: Int, payload: ByteArray): ByteArray = Bytes()
        .u8(0xFF).u8(marker).u16be(payload.size + 2).bytes(payload).build()

    /** JPEG with an APP0 segment before the frame header, like a real camera file. */
    private fun jpeg(width: Int, height: Int, sofMarker: Int = 0xC0): ByteArray {
        val app0 = Bytes()
            .ascii("JFIF").u8(0) // identifier
            .u8(1).u8(1) // version 1.1
            .u8(0) // density units
            .u16be(1).u16be(1) // X/Y density
            .u8(0).u8(0) // no thumbnail
            .build()
        assertEquals(14, app0.size)

        val sof = Bytes()
            .u8(8) // precision
            .u16be(height)
            .u16be(width)
            .u8(3) // components
            .zeros(9) // 3 x (id, sampling, quant table)
            .build()
        assertEquals(15, sof.size)

        return Bytes()
            .u8(0xFF).u8(0xD8) // SOI
            .bytes(jpegSegment(0xE0, app0))
            .bytes(jpegSegment(sofMarker, sof))
            .u8(0xFF).u8(0xD9) // EOI
            .build()
    }

    private fun webpVp8x(width: Int, height: Int): ByteArray = Bytes()
        .ascii("RIFF").u32le(22).ascii("WEBP")
        .ascii("VP8X").u32le(10).u8(0x02).u8(0).u8(0).u8(0) // flags + reserved
        .u24le(width - 1).u24le(height - 1)
        .build()

    private fun webpVp8l(width: Int, height: Int): ByteArray {
        val bits = ((width - 1) and 0x3FFF) or (((height - 1) and 0x3FFF) shl 14)
        return Bytes()
            .ascii("RIFF").u32le(17).ascii("WEBP")
            .ascii("VP8L").u32le(5).u8(0x2F).u32le(bits.toLong() and 0xFFFFFFFFL)
            .build()
    }

    private fun webpVp8(width: Int, height: Int): ByteArray = Bytes()
        .ascii("RIFF").u32le(22).ascii("WEBP")
        .ascii("VP8 ").u32le(10)
        .u8(0x30).u8(0x00).u8(0x00) // frame tag
        .u8(0x9D).u8(0x01).u8(0x2A) // key-frame start code
        .u16le(width).u16le(height)
        .build()

    private fun apk(): ByteArray = Bytes().ascii("PK").u8(0x03).u8(0x04).u8(0x14).u8(0x00).zeros(40).build()

    private fun html(): ByteArray = "<!DOCTYPE html><html><body>hi</body></html>".toByteArray(Charsets.UTF_8)

    private fun reader(maxBytes: Int = 8 * 1024 * 1024): SharedImageReader =
        SharedImageReader(ImagePolicy(maxBytes = maxBytes))

    private class RecordingSource(private val content: ByteArray) : ByteSource {
        var requested: Int = -1

        override fun read(maxBytes: Int): ByteRead {
            requested = maxBytes
            val take = minOf(maxBytes, content.size)
            return ByteRead(content.copyOf(take), content.size > take, content.size.toLong())
        }
    }

    private class ExplodingSource : ByteSource {
        var readCount = 0

        override fun read(maxBytes: Int): ByteRead {
            readCount++
            throw AssertionError("this source must never be read")
        }
    }

    // ---------------------------------------------------------------- PNG

    @Test
    fun acceptsMinimalPngHeaderWithRealDimensions() {
        val image = reader().read(InMemoryByteSource(png(1, 1)), "image/png")

        assertTrue(image.accepted)
        assertNull(image.reason)
        assertEquals("image/png", image.detectedMime)
        assertEquals(1, image.width)
        assertEquals(1, image.height)
        assertEquals(33L, image.byteCount)
        assertFalse(image.mimeMismatch)
        assertTrue("result must be marked ephemeral", image.ephemeral)
    }

    @Test
    fun pngWithNonIhdrChunkLengthIsCorrupt() {
        val image = reader().read(InMemoryByteSource(png(1, 1, ihdrLength = 99)), null)

        assertFalse(image.accepted)
        assertEquals(ImageReason.Corrupt, image.reason)
    }

    // ---------------------------------------------------------------- JPEG

    @Test
    fun parsesJpegSof0Dimensions() {
        val image = reader().read(InMemoryByteSource(jpeg(200, 100)), "image/jpeg")

        assertTrue(image.accepted)
        assertEquals("image/jpeg", image.detectedMime)
        assertEquals(200, image.width)
        assertEquals(100, image.height)
    }

    @Test
    fun parsesJpegProgressiveFrameHeader() {
        val image = reader().read(InMemoryByteSource(jpeg(640, 480, sofMarker = 0xC2)), null)

        assertTrue(image.accepted)
        assertEquals(640, image.width)
        assertEquals(480, image.height)
    }

    @Test
    fun truncatedJpegIsCorruptWithoutCrashing() {
        // Cut 25 bytes into a 41-byte JPEG: the SOF0 marker and its length arrive, the dimensions do not.
        val truncated = jpeg(200, 100).copyOf(25)
        assertEquals(41, jpeg(200, 100).size)

        val image = reader().read(InMemoryByteSource(truncated), "image/jpeg")

        assertFalse(image.accepted)
        assertEquals(ImageReason.Corrupt, image.reason)
        assertEquals(25L, image.byteCount)
    }

    @Test
    fun jpegWithHostileSegmentLengthIsCorrupt() {
        // SOI, then a segment that claims a length reaching past the end of the buffer.
        val hostile = Bytes().u8(0xFF).u8(0xD8).u8(0xFF).u8(0xE1).u16be(0xFFFF).u8(0).build()

        val image = reader().read(InMemoryByteSource(hostile), "image/jpeg")

        assertFalse(image.accepted)
        assertEquals(ImageReason.Corrupt, image.reason)
    }

    // ---------------------------------------------------------------- WEBP

    @Test
    fun parsesWebpVp8xDimensions() {
        val image = reader().read(InMemoryByteSource(webpVp8x(320, 240)), "image/webp")

        assertTrue(image.accepted)
        assertEquals("image/webp", image.detectedMime)
        assertEquals(320, image.width)
        assertEquals(240, image.height)
    }

    @Test
    fun parsesWebpLosslessDimensions() {
        val image = reader().read(InMemoryByteSource(webpVp8l(64, 48)), null)

        assertTrue(image.accepted)
        assertEquals(64, image.width)
        assertEquals(48, image.height)
    }

    @Test
    fun parsesWebpLossyDimensions() {
        val image = reader().read(InMemoryByteSource(webpVp8(800, 600)), null)

        assertTrue(image.accepted)
        assertEquals(800, image.width)
        assertEquals(600, image.height)
    }

    @Test
    fun truncatedWebpChunkHeaderIsCorruptWithoutThrowing() {
        val header = Bytes().ascii("RIFF").u32le(12).ascii("WEBP").ascii("VP8 ").zeros(3).build()
        assertEquals(19, header.size)

        val image = reader().read(InMemoryByteSource(header), "image/webp")

        assertFalse(image.accepted)
        assertEquals(ImageReason.Corrupt, image.reason)
    }

    @Test
    fun riffThatIsNotWebpIsUnsupported() {
        val riff = Bytes().ascii("RIFF").u32le(16).ascii("WAVE").zeros(8).build()

        val image = reader().read(InMemoryByteSource(riff), null)

        assertFalse(image.accepted)
        assertEquals(ImageReason.UnsupportedType, image.reason)
    }

    // ---------------------------------------------------------------- MIME: declared vs detected

    @Test
    fun declaredPngWithJpegBytesIsMimeMismatchAndDetectionWins() {
        val image = reader().read(InMemoryByteSource(jpeg(200, 100)), "image/png")

        assertFalse(image.accepted)
        assertEquals(ImageReason.MimeMismatch, image.reason)
        assertTrue(image.mimeMismatch)
        assertEquals("image/png", image.declaredMime)
        assertEquals("image/jpeg", image.detectedMime)
        assertEquals(200, image.width)
    }

    @Test
    fun declaredTypeWithParametersIsNormalisedBeforeComparison() {
        val image = reader().read(InMemoryByteSource(png(4, 4)), " Image/PNG ; charset=binary")

        assertTrue(image.accepted)
        assertFalse(image.mimeMismatch)
        assertEquals("image/png", image.declaredMime)
    }

    @Test
    fun genericDeclaredMimeNeverBlocksAValidImage() {
        val image = reader().read(InMemoryByteSource(png(4, 4)), "application/octet-stream")

        assertTrue(image.accepted)
        assertFalse(image.mimeMismatch)
        assertEquals("image/png", image.detectedMime)
    }

    @Test
    fun missingDeclaredMimeIsFine() {
        val image = reader().read(InMemoryByteSource(png(2, 2)), null)

        assertTrue(image.accepted)
        assertNull(image.declaredMime)
        assertFalse(image.mimeMismatch)
    }

    @Test
    fun confidentlyDeclaredUnsupportedImageTypeIsRejected() {
        // validate_image's fast reject: image/* we do not support never reaches detection.
        val image = reader().read(InMemoryByteSource(png(2, 2)), "image/gif")

        assertFalse(image.accepted)
        assertEquals(ImageReason.UnsupportedType, image.reason)
        assertNull(image.detectedMime)
    }

    // ---------------------------------------------------------------- other containers

    @Test
    fun apkMagicIsUnsupportedType() {
        val image = reader().read(InMemoryByteSource(apk()), "application/vnd.android.package-archive")

        assertFalse(image.accepted)
        assertEquals(ImageReason.UnsupportedType, image.reason)
    }

    @Test
    fun htmlBytesAreUnsupportedType() {
        val image = reader().read(InMemoryByteSource(html()), "text/html")

        assertFalse(image.accepted)
        assertEquals(ImageReason.UnsupportedType, image.reason)
    }

    // ---------------------------------------------------------------- byte policy

    @Test
    fun nineMegabyteStreamUnderEightMegabytePolicyIsTooLargeWithRealByteCount() {
        val nineMegabytes = 9L * 1024L * 1024L
        val source = SyntheticStreamByteSource(nineMegabytes, pngSignature)

        val image = reader(maxBytes = 8 * 1024 * 1024).read(source, "image/png")

        assertFalse(image.accepted)
        assertEquals(ImageReason.TooLarge, image.reason)
        assertEquals(nineMegabytes, image.byteCount)
        assertEquals(0, image.width)
        assertEquals(0, image.height)
    }

    @Test
    fun byteCapBoundaryIsExact() {
        val bytes = png(1, 1) // 33 bytes
        assertEquals(33, bytes.size)

        assertTrue(reader(maxBytes = 33).read(InMemoryByteSource(bytes), "image/png").accepted)

        val overCap = reader(maxBytes = 32).read(InMemoryByteSource(bytes), "image/png")
        assertFalse(overCap.accepted)
        assertEquals(ImageReason.TooLarge, overCap.reason)
        assertEquals(33L, overCap.byteCount)
    }

    @Test
    fun readerNeverRequestsMoreThanTheCapPlusOneByte() {
        val source = RecordingSource(png(1, 1))

        SharedImageReader(ImagePolicy(maxBytes = 1024)).read(source, "image/png")

        assertEquals("must read cap+1 to tell 'at cap' from 'over cap'", 1025, source.requested)
    }

    @Test
    fun emptySourceIsEmpty() {
        val image = reader().read(InMemoryByteSource(ByteArray(0)), "image/png")

        assertFalse(image.accepted)
        assertEquals(ImageReason.Empty, image.reason)
        assertEquals(0L, image.byteCount)
    }

    @Test
    fun sourceIsReadExactlyOnce() {
        var reads = 0
        val source = object : ByteSource {
            override fun read(maxBytes: Int): ByteRead {
                reads++
                val bytes = png(1, 1)
                return ByteRead(bytes, truncated = false, totalBytes = bytes.size.toLong())
            }
        }

        reader().read(source, "image/png")

        assertEquals(1, reads)
    }

    // ---------------------------------------------------------------- dimension policy

    @Test
    fun maxPixelsExceededIsTooManyPixels() {
        // 9000 x 9000 = 81 MP: inside maxDimension (10 000) per side, outside maxPixels (40 MP).
        val image = reader().read(InMemoryByteSource(png(9000, 9000)), "image/png")

        assertFalse(image.accepted)
        assertEquals(ImageReason.TooManyPixels, image.reason)
        assertEquals("image/png", image.detectedMime)
        assertEquals(9000, image.width)
        assertEquals(9000, image.height)
    }

    @Test
    fun headerClaiming100000x100000IsRejectedWithoutAllocatingPixels() {
        // The fixture is 33 bytes: the claim is in the header, and nothing is ever allocated for it.
        val bytes = png(100_000, 100_000)
        assertEquals(33, bytes.size)

        val image = reader().read(InMemoryByteSource(bytes), "image/png")

        assertFalse(image.accepted)
        assertEquals(ImageReason.TooManyPixels, image.reason)
        assertEquals(100_000, image.width)
        assertEquals(100_000, image.height)
        assertEquals(33L, image.byteCount)
    }

    @Test
    fun zeroWidthHeaderIsCorrupt() {
        val image = reader().read(InMemoryByteSource(png(0, 5)), "image/png")

        assertFalse(image.accepted)
        assertEquals(ImageReason.Corrupt, image.reason)
    }

    // ---------------------------------------------------------------- multiple images

    @Test
    fun sixImagesAreCountedWithTheUnreadOneRejectedByCountPolicy() {
        val sources = (1..5).map { SharedImageCandidate(InMemoryByteSource(png(1, 1)), "image/png") } +
            SharedImageCandidate(ExplodingSource(), "image/png")

        val batch = reader().readAll(sources)

        assertEquals(6, batch.total)
        assertEquals(5, batch.accepted)
        assertEquals(1, batch.rejected)
        assertEquals(1, batch.skippedOverLimit)
        assertEquals(6, batch.images.size)
        assertEquals(ImageReason.TooManyImages, batch.images.last().reason)
        assertFalse(batch.images.last().accepted)
        assertEquals("the over-limit image must not be read at all", 0, (sources.last().source as ExplodingSource).readCount)
    }

    @Test
    fun mixedBatchReportsEachRejectionReason() {
        val batch = reader().readAll(
            listOf(
                SharedImageCandidate(InMemoryByteSource(png(1, 1)), "image/png"),
                SharedImageCandidate(InMemoryByteSource(jpeg(10, 10)), "image/png"),
                SharedImageCandidate(InMemoryByteSource(html()), null),
                SharedImageCandidate(InMemoryByteSource(ByteArray(0)), null),
            ),
        )

        assertEquals(4, batch.total)
        assertEquals(1, batch.accepted)
        assertEquals(3, batch.rejected)
        assertEquals(0, batch.skippedOverLimit)
        assertEquals(ImageReason.MimeMismatch, batch.images[1].reason)
        assertEquals(ImageReason.UnsupportedType, batch.images[2].reason)
        assertEquals(ImageReason.Empty, batch.images[3].reason)
    }

    // ---------------------------------------------------------------- handoff

    @Test
    fun acceptedImageHandsOffToBothOcrAndQrAndSaysQrIsDownstream() {
        val image = reader().read(InMemoryByteSource(png(1, 1)), "image/png")

        val handoff = reader().handoffFor(image)

        assertTrue(handoff.toOcr)
        assertTrue("a screenshot may hold a QR; the reader must not guess it away", handoff.toQr)
        assertTrue(
            "note must say plainly that QR recognition belongs to the decoder",
            handoff.note.contains("QR decoder's job downstream"),
        )
    }

    @Test
    fun rejectedImageIsNotHandedOff() {
        val image = reader().read(InMemoryByteSource(html()), null)

        val handoff = reader().handoffFor(image)

        assertFalse(handoff.toOcr)
        assertFalse(handoff.toQr)
        assertTrue(handoff.note.isNotEmpty())
    }

    @Test
    fun handoffNeverDependsOnDimensions() {
        // Two accepted images with wildly different bounds get the same handoff: no dimension heuristic.
        val tiny = reader().read(InMemoryByteSource(png(1, 1)), "image/png")
        val phoneScreenshot = reader().read(InMemoryByteSource(png(1080, 2400)), "image/png")

        assertEquals(reader().handoffFor(tiny), reader().handoffFor(phoneScreenshot))
    }

    // ---------------------------------------------------------------- ephemeral invariant

    @Test
    fun noProductionTypeCanHoldImageBytesOrAFileHandle() {
        val forbidden = setOf<Class<*>>(
            ByteArray::class.java,
            java.io.File::class.java,
            java.io.FileOutputStream::class.java,
            java.io.FileInputStream::class.java,
            java.io.OutputStream::class.java,
        )

        listOf(SharedImage::class.java, SharedImageReader::class.java).forEach { type ->
            type.declaredFields.forEach { field ->
                assertFalse(
                    "${type.simpleName}.${field.name} must not carry image bytes or a file handle",
                    field.type in forbidden,
                )
            }
            type.declaredMethods.forEach { method ->
                assertFalse(
                    "${type.simpleName}.${method.name} must not return a file handle",
                    method.returnType in forbidden,
                )
                method.parameterTypes.forEach { parameter ->
                    assertFalse(
                        "${type.simpleName}.${method.name} must not take a file handle",
                        parameter in forbidden,
                    )
                }
            }
        }
    }

    @Test
    fun readResultToStringNeverPrintsByteContent() {
        val read = InMemoryByteSource(png(1, 1)).read(64)

        val text = read.toString()

        assertTrue(text.contains("size=33"))
        assertFalse("diagnostics must not contain payload bytes", text.contains("IHDR"))
        assertFalse(text.contains("89"))
    }

    @Test
    fun everyProducedResultIsMarkedEphemeral() {
        val results = listOf(
            reader().read(InMemoryByteSource(png(1, 1)), "image/png"),
            reader().read(InMemoryByteSource(html()), null),
            reader().read(InMemoryByteSource(ByteArray(0)), null),
        )

        results.forEach { assertTrue(it.ephemeral) }
    }

    @Test
    fun defaultPolicyMatchesTheBriefNumbers() {
        val policy = ImagePolicy()

        assertEquals(8 * 1024 * 1024, policy.maxBytes)
        assertEquals(40_000_000L, policy.maxPixels)
        assertEquals(10_000, policy.maxDimension)
        assertEquals(5, policy.maxImages)
    }

    @Test
    fun snifferRejectsGarbageWithoutThrowing() {
        val garbage = ByteArray(3) { 0x7F }
        try {
            val result = ImageHeaderSniffer.sniff(garbage)
            assertEquals(HeaderResult.UnsupportedType, result)
        } catch (e: Throwable) {
            fail("sniffer must never throw on garbage input: ${e::class.java.simpleName}")
        }
    }

    @Test
    fun snifferOnEmptyInputIsCorruptNotACrash() {
        assertEquals(HeaderResult.Corrupt, ImageHeaderSniffer.sniff(ByteArray(0)))
    }

    @Test
    fun batchResultIsNonNullAndOrdered() {
        val batch = reader().readAll(listOf(SharedImageCandidate(InMemoryByteSource(png(1, 1)), "image/png")))
        assertNotNull(batch.images)
        assertEquals(1, batch.images.size)
        assertTrue(batch.images.single().accepted)
    }
}
