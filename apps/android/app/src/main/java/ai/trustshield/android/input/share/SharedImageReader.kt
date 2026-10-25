/*
 * TrustShield Android - B07 (issue #39): shared image bounds and OCR handoff.
 *
 * Scope: bound the bytes of a shared image, identify its real container from the header bytes only,
 * read width/height out of that header, and apply the size/type/dimension policy before anything
 * downstream is allowed to look at it. NO pixel decoding, NO allocation proportional to the image's
 * declared dimensions, and NO file or disk operation anywhere in this file.
 *
 * Mirrors services/analysis/extractors/image_validation.py::validate_image, which is the product's
 * behavioural spec for this input:
 *   - the declared MIME is never trusted in either direction: a generic/missing declared type
 *     (application/octet-stream, blank) never blocks a valid image, and a confidently claimed
 *     image/<subtype> type that disagrees with the detected container is a hard reject, not a warning;
 *   - the detected container decides, and a mismatch is reported, never silently accepted;
 *   - size, then declared-but-unsupported image type, then empty, then detect, then dimensions.
 * Deliberate divergence, stated in the PR: the backend accepts HEIC/HEIF, this reader reports
 * UnsupportedType because sniffing HEIF dimensions needs a full ISO-BMFF box walk.
 *
 * Pure Kotlin core: nothing here imports android.* except the optional ContentResolverByteSource
 * adapter at the bottom, which is not JVM-tested and is never constructed by the unit tests.
 */
package ai.trustshield.android.input.share

import android.content.ContentResolver
import android.net.Uri
import java.io.InputStream

/**
 * A bounded, read-only source of image bytes.
 *
 * Implementations must be **ephemeral**: `read` returns bytes in memory and performs no file, cache,
 * database or network operation. [SharedImageReader] never asks for more than `maxBytes + 1` bytes and
 * never re-reads, copies to disk, or hands the buffer to anything outside this call.
 */
interface ByteSource {
    /** Reads at most [maxBytes] bytes from the start of the source. Never throws for a short source. */
    fun read(maxBytes: Int): ByteRead
}

/**
 * The result of one bounded read.
 *
 * @param bytes the prefix actually read; at most the requested bound.
 * @param truncated true when the source holds more bytes than were requested.
 * @param totalBytes the source's real length when the source can state it without reading it
 *   (an in-memory buffer or a stream that exposes its length). Null when unknown; then [bytes] size is
 *   only a lower bound and the reader says so instead of inventing an exact count.
 */
class ByteRead(
    val bytes: ByteArray,
    val truncated: Boolean,
    val totalBytes: Long? = null,
) {
    val size: Int get() = bytes.size

    /** Deliberately never prints byte content: image payloads are not diagnostic data. */
    override fun toString(): String =
        "ByteRead(size=$size, truncated=$truncated, totalBytes=$totalBytes)"

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ByteRead) return false
        return truncated == other.truncated &&
            totalBytes == other.totalBytes &&
            bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int {
        var result = bytes.contentHashCode()
        result = 31 * result + truncated.hashCode()
        result = 31 * result + (totalBytes?.hashCode() ?: 0)
        return result
    }
}

/** Read-only in-memory source. The only thing it can do is hand back a bounded prefix. */
class InMemoryByteSource(private val content: ByteArray) : ByteSource {
    override fun read(maxBytes: Int): ByteRead {
        require(maxBytes > 0) { "maxBytes must be positive" }
        val take = if (maxBytes < content.size) maxBytes else content.size
        return ByteRead(
            bytes = content.copyOf(take),
            truncated = content.size > take,
            totalBytes = content.size.toLong(),
        )
    }
}

/**
 * A read-only stream of [totalLength] bytes that never materialises more than the caller's bound.
 * Used to prove the byte policy against a 9 MB source without allocating 9 MB.
 */
class SyntheticStreamByteSource(
    private val totalLength: Long,
    private val pattern: ByteArray = byteArrayOf(),
) : ByteSource {
    init {
        require(totalLength >= 0) { "totalLength must not be negative" }
    }

    override fun read(maxBytes: Int): ByteRead {
        require(maxBytes > 0) { "maxBytes must be positive" }
        val take = if (totalLength < maxBytes.toLong()) totalLength.toInt() else maxBytes
        val out = ByteArray(take)
        if (pattern.isNotEmpty()) {
            for (i in 0 until take) out[i] = pattern[i % pattern.size]
        }
        return ByteRead(bytes = out, truncated = totalLength > take, totalBytes = totalLength)
    }
}

/** Size, count and dimension policy. Defaults are the B07 numbers. */
data class ImagePolicy(
    val maxBytes: Int = 8 * 1024 * 1024,
    val maxPixels: Long = 40_000_000L,
    val maxDimension: Int = 10_000,
    val maxImages: Int = 5,
) {
    init {
        require(maxBytes > 0) { "maxBytes must be positive" }
        require(maxPixels > 0) { "maxPixels must be positive" }
        require(maxDimension > 0) { "maxDimension must be positive" }
        require(maxImages > 0) { "maxImages must be positive" }
    }
}

/** Why an image was not accepted. A rejected image is never handed to OCR or the QR decoder. */
enum class ImageReason {
    /** Real byte count exceeds [ImagePolicy.maxBytes]; the header was never parsed. */
    TooLarge,

    /** Width, height or width*height exceeds the dimension/pixel limit. */
    TooManyPixels,

    /** Recognisable container signature, but the header is truncated or malformed. */
    Corrupt,

    /** No supported container signature, or a declared image/<subtype> type we cannot accept. */
    UnsupportedType,

    /** The declared image/<subtype> type disagrees with the detected container. Detection wins and this is a reject. */
    MimeMismatch,

    /** Zero bytes. */
    Empty,

    /** Beyond [ImagePolicy.maxImages] in one share; its bytes were never read. */
    TooManyImages,
}

/**
 * One shared image after validation. Carries metadata only - never the bytes, never a file handle.
 */
data class SharedImage(
    val declaredMime: String?,
    val detectedMime: String?,
    val byteCount: Long,
    val width: Int,
    val height: Int,
    val accepted: Boolean,
    val reason: ImageReason?,
    val mimeMismatch: Boolean = false,
    /** Invariant, not a knob: this pipeline persists nothing. */
    val ephemeral: Boolean = true,
)

/** What must look at an accepted image downstream. */
data class Handoff(
    val toOcr: Boolean,
    val toQr: Boolean,
    val note: String,
)

object SharedImageHandoff {
    /**
     * Stated plainly because it is the part an operator would otherwise misread: this reader measures
     * bounds and nothing else. It cannot and does not say whether a screenshot contains a QR code -
     * dimensions carry no such signal - so an accepted image goes to both downstream decoders and the
     * QR decoder decides.
     */
    const val SCREENSHOT_NOTE: String =
        "Bounds only: width and height carry no content signal, so this reader does not guess whether a " +
            "screenshot contains a QR code. Recognising a QR inside a screenshot is the QR decoder's job " +
            "downstream; OCR runs on the same image independently."

    fun forImage(image: SharedImage): Handoff =
        if (image.accepted) {
            Handoff(toOcr = true, toQr = true, note = SCREENSHOT_NOTE)
        } else {
            Handoff(
                toOcr = false,
                toQr = false,
                note = "Not handed off: image was rejected (${image.reason}). Nothing downstream sees it.",
            )
        }
}

/** One candidate from a share intent: a bounded source plus whatever MIME the sender claimed. */
data class SharedImageCandidate(
    val source: ByteSource,
    val declaredMime: String? = null,
)

/** Result of a multi-image share. [images] is in input order and always has `total` entries. */
data class SharedImageBatch(
    val images: List<SharedImage>,
    val total: Int,
    val accepted: Int,
    val rejected: Int,
    val skippedOverLimit: Int,
)

/** The outcome of sniffing header bytes. No pixels are read to produce this. */
sealed class HeaderResult {
    data class Detected(val mime: String, val width: Int, val height: Int) : HeaderResult()

    /** Signature is not a container we handle. */
    object UnsupportedType : HeaderResult()

    /** Signature matched (or the walk started) but the header is truncated or self-inconsistent. */
    object Corrupt : HeaderResult()
}

/**
 * Header-only image sniffer. Reads magic bytes and dimensions; never decodes a pixel and never
 * allocates anything sized by the values it finds.
 */
object ImageHeaderSniffer {

    private val PNG_SIGNATURE = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
    )

    fun sniff(bytes: ByteArray): HeaderResult = sniff(bytes, bytes.size)

    fun sniff(bytes: ByteArray, length: Int): HeaderResult {
        val n = minOf(length, bytes.size)
        if (n <= 0) return HeaderResult.Corrupt
        return when {
            startsWith(bytes, n, PNG_SIGNATURE) -> sniffPng(bytes, n)
            u(bytes[0]) == 0xFF && n >= 2 && u(bytes[1]) == 0xD8 -> sniffJpeg(bytes, n)
            tagAt(bytes, n, 0, "RIFF") -> sniffWebp(bytes, n)
            else -> HeaderResult.UnsupportedType
        }
    }

    /** PNG: 8-byte signature, then the IHDR chunk whose payload starts at offset 16. */
    private fun sniffPng(b: ByteArray, n: Int): HeaderResult {
        if (n < 24) return HeaderResult.Corrupt
        if (be32(b, 8) != 13L) return HeaderResult.Corrupt
        if (!tagAt(b, n, 12, "IHDR")) return HeaderResult.Corrupt
        val width = be32(b, 16)
        val height = be32(b, 20)
        if (width <= 0L || height <= 0L) return HeaderResult.Corrupt
        return HeaderResult.Detected(MIME_PNG, width.toInt(), height.toInt())
    }

    /**
     * JPEG: walk the marker chain to the first SOFn frame header and read its real dimensions.
     * Every step is bounds-checked, so a truncated or hostile segment table ends as Corrupt instead
     * of an exception or a loop.
     */
    private fun sniffJpeg(b: ByteArray, n: Int): HeaderResult {
        if (n < 4) return HeaderResult.Corrupt
        var i = 2 // past SOI
        var guard = 0
        while (i < n) {
            if (guard++ > 4096) return HeaderResult.Corrupt
            if (u(b[i]) != 0xFF) return HeaderResult.Corrupt
            var j = i + 1
            while (j < n && u(b[j]) == 0xFF) j++ // 0xFF fill bytes before a marker
            if (j >= n) return HeaderResult.Corrupt
            val marker = u(b[j])
            when {
                marker == 0xD8 -> i = j + 1 // stray SOI
                marker == 0xD9 -> return HeaderResult.Corrupt // EOI before any SOF
                marker == 0x01 || marker in 0xD0..0xD7 -> i = j + 1 // standalone markers
                else -> {
                    if (j + 2 >= n) return HeaderResult.Corrupt
                    val segmentLength = (u(b[j + 1]) shl 8) or u(b[j + 2])
                    if (segmentLength < 2) return HeaderResult.Corrupt
                    val dataStart = j + 3
                    if (isFrameHeader(marker)) {
                        if (segmentLength < 7) return HeaderResult.Corrupt
                        if (dataStart + 5 > n) return HeaderResult.Corrupt
                        val height = (u(b[dataStart + 1]) shl 8) or u(b[dataStart + 2])
                        val width = (u(b[dataStart + 3]) shl 8) or u(b[dataStart + 4])
                        if (width <= 0 || height <= 0) return HeaderResult.Corrupt
                        return HeaderResult.Detected(MIME_JPEG, width, height)
                    }
                    if (marker == 0xDA) return HeaderResult.Corrupt // scan data before a frame header
                    val next = dataStart + (segmentLength - 2)
                    if (next > n || next <= j) return HeaderResult.Corrupt
                    i = next
                }
            }
        }
        return HeaderResult.Corrupt // ran out of bytes before finding a SOFn
    }

    /** WEBP: RIFF container; the first chunk tells us which of the three layouts to read. */
    private fun sniffWebp(b: ByteArray, n: Int): HeaderResult {
        if (n < 20) return HeaderResult.Corrupt // chunk size occupies bytes 16..19
        // A RIFF that is not WEBP is simply a container we do not handle (WAV, AVI, ...).
        if (!tagAt(b, n, 8, "WEBP")) return HeaderResult.UnsupportedType
        val chunkSize = le32(b, 16)
        if (chunkSize <= 0L) return HeaderResult.Corrupt
        val payload = 20
        return when {
            tagAt(b, n, 12, "VP8 ") -> {
                if (n < payload + 10) return HeaderResult.Corrupt
                // 3-byte frame tag, then the 0x9D 0x01 0x2A key-frame start code.
                if (u(b[payload + 3]) != 0x9D || u(b[payload + 4]) != 0x01 || u(b[payload + 5]) != 0x2A) {
                    return HeaderResult.Corrupt
                }
                val width = (u(b[payload + 6]) or (u(b[payload + 7]) shl 8)) and 0x3FFF
                val height = (u(b[payload + 8]) or (u(b[payload + 9]) shl 8)) and 0x3FFF
                if (width <= 0 || height <= 0) return HeaderResult.Corrupt
                HeaderResult.Detected(MIME_WEBP, width, height)
            }

            tagAt(b, n, 12, "VP8L") -> {
                if (n < payload + 5) return HeaderResult.Corrupt
                if (u(b[payload]) != 0x2F) return HeaderResult.Corrupt // lossless signature
                val bits = u(b[payload + 1]) or
                    (u(b[payload + 2]) shl 8) or
                    (u(b[payload + 3]) shl 16) or
                    (u(b[payload + 4]) shl 24)
                val width = (bits and 0x3FFF) + 1
                val height = ((bits ushr 14) and 0x3FFF) + 1
                HeaderResult.Detected(MIME_WEBP, width, height)
            }

            tagAt(b, n, 12, "VP8X") -> {
                if (n < payload + 10) return HeaderResult.Corrupt
                val width = le24(b, payload + 4) + 1
                val height = le24(b, payload + 7) + 1
                HeaderResult.Detected(MIME_WEBP, width, height)
            }

            else -> HeaderResult.UnsupportedType
        }
    }

    private fun isFrameHeader(marker: Int): Boolean =
        marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC

    private fun startsWith(b: ByteArray, n: Int, prefix: ByteArray): Boolean {
        if (n < prefix.size) return false
        for (k in prefix.indices) if (b[k] != prefix[k]) return false
        return true
    }

    private fun tagAt(b: ByteArray, n: Int, offset: Int, tag: String): Boolean {
        if (offset < 0 || offset + tag.length > n) return false
        for (k in tag.indices) if (u(b[offset + k]) != tag[k].code) return false
        return true
    }

    private fun u(value: Byte): Int = value.toInt() and 0xFF

    private fun be32(b: ByteArray, offset: Int): Long =
        (u(b[offset]).toLong() shl 24) or
            (u(b[offset + 1]).toLong() shl 16) or
            (u(b[offset + 2]).toLong() shl 8) or
            u(b[offset + 3]).toLong()

    private fun le32(b: ByteArray, offset: Int): Long =
        u(b[offset]).toLong() or
            (u(b[offset + 1]).toLong() shl 8) or
            (u(b[offset + 2]).toLong() shl 16) or
            (u(b[offset + 3]).toLong() shl 24)

    private fun le24(b: ByteArray, offset: Int): Int =
        u(b[offset]) or (u(b[offset + 1]) shl 8) or (u(b[offset + 2]) shl 16)
}

const val MIME_PNG = "image/png"
const val MIME_JPEG = "image/jpeg"
const val MIME_WEBP = "image/webp"

/**
 * Validates a shared image from header bytes only.
 *
 * Every decision is made on at most `policy.maxBytes + 1` bytes held in memory for the duration of
 * the call. There is no `File`, no stream copy to disk, no cache directory and no log line anywhere in
 * this class - the raw image never lands anywhere.
 */
class SharedImageReader(private val policy: ImagePolicy = ImagePolicy()) {

    val policySummary: ImagePolicy get() = policy

    /** Reads and validates one image. [declaredMime] is the sender's claim; it is checked, never trusted. */
    fun read(source: ByteSource, declaredMime: String? = null): SharedImage {
        val declared = normalizeMime(declaredMime)

        // Ask for one byte past the cap: that single extra byte is how "at the limit" and
        // "over the limit" are told apart without trusting a declared length.
        val bounded = source.read(readLimit())
        val byteCount = bounded.totalBytes ?: bounded.bytes.size.toLong()

        if (bounded.truncated || bounded.bytes.size > policy.maxBytes) {
            // Rejected before the header is looked at: nothing about this image is parsed.
            return rejected(declared, byteCount, ImageReason.TooLarge)
        }
        if (bounded.bytes.isEmpty()) {
            return rejected(declared, 0L, ImageReason.Empty)
        }
        // Mirrors validate_image: only a confidently claimed image/<subtype> type can be fast-rejected for
        // being unsupported; octet-stream, blank or a non-image type falls through to detection.
        if (declared != null && declared.startsWith("image/") && declared !in SUPPORTED_MIME) {
            return rejected(declared, byteCount, ImageReason.UnsupportedType)
        }

        return when (val header = ImageHeaderSniffer.sniff(bounded.bytes)) {
            is HeaderResult.Detected -> validateDetected(declared, header, byteCount)
            HeaderResult.UnsupportedType -> rejected(declared, byteCount, ImageReason.UnsupportedType)
            HeaderResult.Corrupt -> rejected(declared, byteCount, ImageReason.Corrupt)
        }
    }

    /**
     * Reads a multi-image share. Images past [ImagePolicy.maxImages] are counted and rejected without
     * their bytes being read at all.
     */
    fun readAll(candidates: List<SharedImageCandidate>): SharedImageBatch {
        val images = ArrayList<SharedImage>(candidates.size)
        var skipped = 0
        candidates.forEachIndexed { index, candidate ->
            if (index >= policy.maxImages) {
                skipped++
                images += rejected(normalizeMime(candidate.declaredMime), 0L, ImageReason.TooManyImages)
            } else {
                images += read(candidate.source, candidate.declaredMime)
            }
        }
        val accepted = images.count { it.accepted }
        return SharedImageBatch(
            images = images,
            total = images.size,
            accepted = accepted,
            rejected = images.size - accepted,
            skippedOverLimit = skipped,
        )
    }

    /** What must look at an accepted image downstream. */
    fun handoffFor(image: SharedImage): Handoff = SharedImageHandoff.forImage(image)

    private fun validateDetected(declared: String?, header: HeaderResult.Detected, byteCount: Long): SharedImage {
        if (header.width <= 0 || header.height <= 0) {
            return rejected(declared, byteCount, ImageReason.Corrupt)
        }
        val mismatch = declared != null && declared.startsWith("image/") && declared != header.mime
        if (mismatch) {
            // Detection wins and the disagreement is a reject, exactly like validate_image's mime_mismatch.
            return SharedImage(
                declaredMime = declared,
                detectedMime = header.mime,
                byteCount = byteCount,
                width = header.width,
                height = header.height,
                accepted = false,
                reason = ImageReason.MimeMismatch,
                mimeMismatch = true,
            )
        }
        val pixels = header.width.toLong() * header.height.toLong()
        if (header.width > policy.maxDimension ||
            header.height > policy.maxDimension ||
            pixels > policy.maxPixels
        ) {
            // Dimensions are reported so the caller can say what was refused, but nothing was allocated
            // for them: width and height were read straight out of the header.
            return SharedImage(
                declaredMime = declared,
                detectedMime = header.mime,
                byteCount = byteCount,
                width = header.width,
                height = header.height,
                accepted = false,
                reason = ImageReason.TooManyPixels,
            )
        }
        return SharedImage(
            declaredMime = declared,
            detectedMime = header.mime,
            byteCount = byteCount,
            width = header.width,
            height = header.height,
            accepted = true,
            reason = null,
        )
    }

    private fun rejected(declared: String?, byteCount: Long, reason: ImageReason) = SharedImage(
        declaredMime = declared,
        detectedMime = null,
        byteCount = byteCount,
        width = 0,
        height = 0,
        accepted = false,
        reason = reason,
    )

    private fun readLimit(): Int =
        if (policy.maxBytes >= Int.MAX_VALUE - 1) Int.MAX_VALUE else policy.maxBytes + 1

    private fun normalizeMime(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val mediaType = raw.substringBefore(';').trim().lowercase()
        if (mediaType.isEmpty()) return null
        // Same equivalence as validate_image: one still HEIF image has one decoded MIME.
        return if (mediaType == "image/heif") "image/heic" else mediaType
    }

    private companion object {
        val SUPPORTED_MIME = setOf(MIME_PNG, MIME_JPEG, MIME_WEBP)
    }
}

/**
 * Android adapter: bounded read of a `content://` share without any local copy.
 *
 * NOT JVM-TESTED - it touches android.* and its bodies are android.jar stubs off-device. It reads at
 * most `maxBytes` from the provider's own stream into memory and never writes, caches or re-opens a
 * file: the whole point of the reader above is that a shared image is measured, not materialised.
 * Bound it from the provider's own length (`openAssetFileDescriptor().length`) when it is offered, so
 * [ByteRead.totalBytes] is the real size and not just what was read.
 */
class ContentResolverByteSource(
    private val resolver: ContentResolver,
    private val uri: Uri,
) : ByteSource {

    override fun read(maxBytes: Int): ByteRead {
        require(maxBytes > 0) { "maxBytes must be positive" }
        val declaredLength = runCatching { resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } }
            .getOrNull()
            ?.takeIf { it >= 0L }

        val stream: InputStream = resolver.openInputStream(uri)
            ?: return ByteRead(ByteArray(0), truncated = false, totalBytes = declaredLength)

        // One bounded allocation, size maxBytes at most. No file, no cache dir, no temp copy.
        val out = ByteArray(maxBytes)
        var filled = 0
        var overflow = false
        stream.use { input ->
            val chunk = ByteArray(minOf(maxBytes, 64 * 1024))
            while (filled < maxBytes) {
                val want = minOf(chunk.size, maxBytes - filled)
                val read = input.read(chunk, 0, want)
                if (read <= 0) break
                System.arraycopy(chunk, 0, out, filled, read)
                filled += read
            }
            // One extra probe byte is what separates "exactly at the cap" from "over the cap".
            overflow = input.read() >= 0
        }
        val total = when {
            overflow -> declaredLength?.takeIf { it > filled.toLong() } ?: (filled.toLong() + 1L)
            else -> declaredLength ?: filled.toLong()
        }
        return ByteRead(out.copyOf(filled), truncated = overflow, totalBytes = total)
    }
}
