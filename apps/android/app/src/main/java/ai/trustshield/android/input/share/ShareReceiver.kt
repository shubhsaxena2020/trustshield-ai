package ai.trustshield.android.input.share

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

/*
 * B01 - Android share intake (issue #33, F01, report section 9.1).
 *
 * SCOPE
 *   ACTION_SEND / ACTION_SEND_MULTIPLE payload intake: text, link, text+link, one image,
 *   many images. Payloads are pure DATA: nothing here opens, resolves, fetches, navigates to
 *   or executes a URL, URI, deep link, attachment or APK. No network, anywhere.
 *
 * STRUCTURE (COMMON.md section BUILD REALITY item 4)
 *   (a) Pure Kotlin decision core - no android.* imports, fully unit tested in
 *       ShareReceiverTest.kt: SharePayloadParser, SharedContent, IncomingShare, ByteBudget,
 *       ShareMasking, ShareParts.
 *   (b) Thin Android adapter - the last section of this file imports android.*, compiles
 *       against android.jar, and is NOT JVM-tested (android.jar method bodies are stubs that
 *       throw). Everything decision-shaped is delegated to the pure core above.
 *
 * PRIVACY
 *   Raw text is held in memory only for the analysis the user asked for. It is never written
 *   to a file, SharedPreferences, a log or an exception message. Every toString() in this file
 *   is redacted; ShareMasking masks OTP/PIN/card/phone/UPI before any diagnostic string exists.
 */

/** Intent action / extra names as plain strings, so the pure core never imports android.*. */
object ShareActions {
    const val ACTION_SEND: String = "android.intent.action.SEND"
    const val ACTION_SEND_MULTIPLE: String = "android.intent.action.SEND_MULTIPLE"
    const val EXTRA_TEXT: String = "android.intent.extra.TEXT"
    const val EXTRA_SUBJECT: String = "android.intent.extra.SUBJECT"
    const val EXTRA_STREAM: String = "android.intent.extra.STREAM"
    const val MIME_TEXT_PLAIN: String = "text/plain"
    const val MIME_IMAGE_PREFIX: String = "image/"
}

/** Hard bounds for a single share intake. Enforced here and again by the byte source. */
object ShareBounds {
    /** 8 MB across every image in one share, not per image. */
    const val MAX_TOTAL_BYTES: Long = 8L * 1024 * 1024

    /** At most five images per share; the sixth is a rejection, not a silent trim. */
    const val MAX_IMAGES: Int = 5

    const val MIME_APK: String = "application/vnd.android.package-archive"
    const val MIME_HTML: String = "text/html"

    /** Only these image types are decoded downstream; everything else is refused up front. */
    val IMAGE_MIME_ALLOWLIST: Set<String> = setOf("image/png", "image/jpeg", "image/webp")

    /** "image/png; charset=binary" -> "image/png"; blank -> null. */
    fun normaliseMime(raw: String?): String? =
        raw?.substringBefore(';')?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }

    fun isImageMime(mime: String?): Boolean = mime != null && mime.startsWith(ShareActions.MIME_IMAGE_PREFIX)
}

/** Why a share was refused. [code] is stable for tests and support; [message] is simple Hinglish copy. */
enum class ShareRejectReason(val code: String, val message: String) {
    APK_ARCHIVE("apk_archive", "App file (APK) share nahi kar sakte."),
    HTML_DOCUMENT("html_document", "Web page file analyse nahi hoti."),
    DECLARED_IMAGE_NOT_IMAGE("declared_image_not_image", "Jo file image bataayi gayi thi, wo image nahi hai."),
    IMAGE_TYPE_NOT_ALLOWED("image_type_not_allowed", "Yeh image type support nahi hai (PNG, JPEG, WebP chalte hain)."),
    NO_URI("no_uri", "Share mein file ka address nahi mila."),
    UNOPENABLE_URI("unopenable_uri", "File kholi nahi ja saki."),
    OVERSIZED("oversized", "File 8 MB se badi hai."),
    TOO_MANY_IMAGES("too_many_images", "Ek saath 5 se zyada images nahi le sakte."),
    ;

    override fun toString(): String = "ShareRejectReason($code)"
}

/** Why a share carried nothing to analyse. */
enum class ShareEmptyReason(val code: String, val message: String) {
    NO_ACTION("no_action", "Koi share action nahi mila."),
    UNSUPPORTED_ACTION("unsupported_action", "Yeh share type support nahi hai."),
    NO_CONTENT("no_content", "Share mein kuch bhi nahi aaya."),
    BLANK_TEXT("blank_text", "Share kiya text khaali hai."),
    DISCARDED("discarded", "Aapne yeh share hata diya."),
    ;

    override fun toString(): String = "ShareEmptyReason($code)"
}

/** Which field a piece of analysable input came from - WhatsApp sends text, link and subject separately. */
enum class SharePartKind { TEXT, LINK, SUBJECT }

/** One addressable slice of an incoming share. [raw] is never logged; toString() is redacted. */
data class SharePart(val kind: SharePartKind, val raw: String) {
    val isBlank: Boolean get() = raw.isBlank()

    override fun toString(): String = "SharePart(kind=$kind, length=${raw.length})"
}
/**
 * Masks the sensitive shapes before any diagnostic string exists.
 * Not a security control on the payload (which stays in memory for analysis) - a guarantee that
 * logs, exceptions and crash reports cannot carry an OTP, PIN, card number, phone number or UPI ID.
 */
object ShareMasking {
    private const val REDACTED = "[redacted]"

    private val UPI = Regex("""\b[\w.\-]{2,}@[a-zA-Z]{2,}(?:\.[a-zA-Z]{2,}){0,2}\b""")
    private val CARD = Regex("""\b(?:\d[ -]?){13,19}\b""")
    private val PHONE = Regex("""(?:\+91[\s-]?)?\b[6-9]\d{9}\b""")
    private val OTP = Regex("""(?i)\b(otp|o\.t\.p|pin|code|cvv)\b[^0-9]{0,12}\d{3,8}""")

    fun mask(text: String?): String {
        if (text.isNullOrBlank()) return ""
        var out = text
        out = UPI.replace(out, REDACTED)
        out = CARD.replace(out, REDACTED)
        out = PHONE.replace(out, REDACTED)
        out = OTP.replace(out) { m -> m.groupValues[1] + " " + REDACTED }
        return out
    }

    /** Redacted one-line shape of a payload for diagnostics: masks first, then shortens. */
    fun summary(text: String?, maxChars: Int = 80): String {
        val masked = mask(text)
        if (masked.isEmpty()) return ""
        return if (masked.length <= maxChars) masked else masked.take(maxChars) + "..."
    }
}

/**
 * A `content://` handle as plain data. The adapter fills it in without opening anything;
 * [openable] is a declared hint, and the real read is bounded separately by [ShareByteSource].
 */
data class SharedStreamRef(
    val uriString: String?,
    val declaredMime: String? = null,
    val declaredBytes: Long? = null,
    val openable: Boolean = true,
    /** Optional header sniff performed by the reader; used to catch "declared image, is not image". */
    val sniffedMime: String? = null,
) {
    val effectiveMime: String? get() = ShareBounds.normaliseMime(sniffedMime) ?: ShareBounds.normaliseMime(declaredMime)

    /** Never prints the URI itself - a content URI can identify a personal file. */
    override fun toString(): String =
        "SharedStreamRef(uri=${if (uriString.isNullOrBlank()) "null" else "content://**"}, " +
            "declaredMime=$effectiveMime, declaredBytes=$declaredBytes, openable=$openable)"
}

/** One accepted image reference. Bytes are read later, bounded; nothing is copied to disk. */
data class ShareImageRef(
    val uriString: String,
    val mime: String,
    val sizeBytes: Long?,
) {
    override fun toString(): String = "ShareImageRef(uri=content://**, mime=$mime, sizeBytes=$sizeBytes)"
}

/**
 * What a share actually carried. Text, link and subject stay separately addressable
 * ([parts]) so the analyser can say which field carried the signal, while
 * [analysableText] gives the merged single input the pipeline wants.
 */
sealed interface SharedContent {

    /** True only for content that a later stage may analyse. */
    val isAnalysable: Boolean
    /** The addressable slices, in TEXT, LINK, SUBJECT order. Empty for rejected/empty shares. */
    val parts: List<SharePart>
    /** Merged input for the analyser; empty when there is nothing to analyse. */
    val analysableText: String

    data class Text(val text: String, val subject: String? = null) : SharedContent {
        override val parts: List<SharePart> = buildList {
            add(SharePart(SharePartKind.TEXT, text))
            // Senders often put the same string in title and body; keep one addressable part.
            if (!subject.isNullOrBlank() && subject != text) add(SharePart(SharePartKind.SUBJECT, subject))
        }
        override val analysableText: String = SharedContent.merge(parts)
        override val isAnalysable: Boolean get() = analysableText.isNotBlank()
        override fun toString(): String = "SharedContent.Text(len=${text.length}, subjectLen=${subject?.length ?: 0})"
    }

    data class Link(val url: String, val subject: String? = null) : SharedContent {
        override val parts: List<SharePart> = buildList {
            add(SharePart(SharePartKind.LINK, url))
            if (!subject.isNullOrBlank() && subject != url) add(SharePart(SharePartKind.SUBJECT, subject))
        }
        override val analysableText: String = SharedContent.merge(parts)
        override val isAnalysable: Boolean get() = analysableText.isNotBlank()
        override fun toString(): String = "SharedContent.Link(urlLen=${url.length}, subjectLen=${subject?.length ?: 0})"
    }

    data class TextAndLink(val text: String, val url: String, val subject: String? = null) : SharedContent {
        override val parts: List<SharePart> = buildList {
            add(SharePart(SharePartKind.TEXT, text))
            add(SharePart(SharePartKind.LINK, url))
            if (!subject.isNullOrBlank() && subject != text) add(SharePart(SharePartKind.SUBJECT, subject))
        }
        override val analysableText: String = SharedContent.merge(parts)
        override val isAnalysable: Boolean get() = analysableText.isNotBlank()
        override fun toString(): String =
            "SharedContent.TextAndLink(textLen=${text.length}, urlLen=${url.length}, subjectLen=${subject?.length ?: 0})"
    }

    data class Images(val image: ShareImageRef) : SharedContent {
        override val parts: List<SharePart> get() = emptyList()
        override val analysableText: String get() = ""
        override val isAnalysable: Boolean get() = true
        override fun toString(): String = "SharedContent.Images($image)"
    }

    data class MultipleImages(val images: List<ShareImageRef>) : SharedContent {
        override val parts: List<SharePart> get() = emptyList()
        override val analysableText: String get() = ""
        override val isAnalysable: Boolean get() = images.isNotEmpty()
        override fun toString(): String =
            "SharedContent.MultipleImages(count=${images.size}, mimes=${images.map { it.mime }}, bytes=${images.sumOf { it.sizeBytes ?: 0L }})"
    }

    /** Nothing to analyse, and nothing was refused. */
    data class Empty(val reason: ShareEmptyReason) : SharedContent {
        override val parts: List<SharePart> get() = emptyList()
        override val analysableText: String get() = ""
        override val isAnalysable: Boolean get() = false
        override fun toString(): String = "SharedContent.Empty(${reason.code})"
    }

    /** Explicitly refused, with a reason the review screen can show verbatim. */
    data class Rejected(val reason: ShareRejectReason, val detail: String? = null) : SharedContent {
        override val parts: List<SharePart> get() = emptyList()
        override val analysableText: String get() = ""
        override val isAnalysable: Boolean get() = false
        override fun toString(): String = "SharedContent.Rejected(${reason.code})"
    }

    companion object {
        /** Joins the addressable slices, skipping blanks, keeping their original bytes untouched. */
        fun merge(parts: List<SharePart>): String =
            parts.filter { !it.isBlank }.joinToString("\n") { it.raw }
    }
}
/**
 * Pure decision core: turns the raw fields of a share intent into [SharedContent].
 *
 * Reads nothing. Opens nothing. Resolves nothing. Every URI is treated as an opaque string, and
 * bounded byte access only happens later through [ShareByteSource] after the user accepts the review.
 */
object SharePayloadParser {

    /** Deliberately conservative: only http(s) and www hosts count as a "link" part. */
    private val URL = Regex("""(?i)\b(?:https?://|www\.)[^\s<>"'`{}|\\^\[\]]+""")

    /** Trailing punctuation that belongs to the sentence, not the URL. */
    private const val URL_TRAILING = ".,;:!?)\u00bb\"'"

    fun parse(
        action: String?,
        mimeType: String?,
        text: String?,
        subject: String?,
        streams: List<SharedStreamRef> = emptyList(),
        clipCount: Int = 0,
    ): SharedContent {
        val act = action?.trim()
        if (act.isNullOrEmpty()) return SharedContent.Empty(ShareEmptyReason.NO_ACTION)
        if (act != ShareActions.ACTION_SEND && act != ShareActions.ACTION_SEND_MULTIPLE) {
            return SharedContent.Empty(ShareEmptyReason.UNSUPPORTED_ACTION)
        }

        val declared = ShareBounds.normaliseMime(mimeType)
        // A lying intent MIME is refused before anything else is even looked at.
        if (declared == ShareBounds.MIME_APK) return SharedContent.Rejected(ShareRejectReason.APK_ARCHIVE)
        if (declared == ShareBounds.MIME_HTML) return SharedContent.Rejected(ShareRejectReason.HTML_DOCUMENT)

        val hasStreams = streams.isNotEmpty() || clipCount > 0

        return when {
            // SEND_MULTIPLE is always an image share for this app.
            act == ShareActions.ACTION_SEND_MULTIPLE -> parseImages(streams, clipCount)
            // A stream attached to a text share is still an image share.
            hasStreams && declared != ShareActions.MIME_TEXT_PLAIN -> parseImages(streams, clipCount)
            // "image/png" declared but the sender attached no readable handle.
            declared != null && ShareBounds.isImageMime(declared) -> SharedContent.Rejected(ShareRejectReason.NO_URI)
            else -> parseTextual(text, subject)
        }
    }

    // ---------------------------------------------------------------- text / link

    private fun parseTextual(rawText: String?, rawSubject: String?): SharedContent {
        val text = rawText?.trim()?.takeIf { it.isNotEmpty() }
        val subject = rawSubject?.trim()?.takeIf { it.isNotEmpty() }

        if (text == null && subject == null) {
            // Distinguish "sender sent a blank body" from "sender sent nothing at all".
            return if (rawText != null) {
                SharedContent.Empty(ShareEmptyReason.BLANK_TEXT)
            } else {
                SharedContent.Empty(ShareEmptyReason.NO_CONTENT)
            }
        }

        val body = text ?: subject!!
        val links = extractLinks(body)
        if (links.isEmpty()) {
            // Subject-only share: the subject is the analysable text and stays labelled as a SUBJECT part.
            return if (text == null) SharedContent.Text(subject!!, subject) else SharedContent.Text(text, subject)
        }

        val firstLink = links.first()
        val withoutLinks = URL.replace(body, " ").trim().replace(Regex("""\s{2,}"""), " ")
        return when {
            links.size == 1 && withoutLinks.isEmpty() -> SharedContent.Link(firstLink, subject)
            links.size == 1 -> SharedContent.TextAndLink(withoutLinks, firstLink, subject)
            // Several links in one field: keep the field intact as text so no link is silently dropped.
            else -> SharedContent.TextAndLink(body, links.joinToString(" "), subject)
        }
    }

    /** Links found in a field, punctuation-trimmed, de-duplicated, order preserved. Never navigated. */
    fun extractLinks(raw: String): List<String> =
        URL.findAll(raw)
            .map { it.value.trim().trimEnd { c -> URL_TRAILING.indexOf(c) >= 0 } }
            .filter { it.length > 4 }
            .distinct()
            .toList()

    // ---------------------------------------------------------------- images

    private fun parseImages(streams: List<SharedStreamRef>, clipCount: Int): SharedContent {
        if (streams.isEmpty()) {
            // clipCount > 0 with no parseable handle means the sender gave us nothing readable.
            return SharedContent.Rejected(ShareRejectReason.NO_URI)
        }
        if (clipCount > ShareBounds.MAX_IMAGES || streams.size > ShareBounds.MAX_IMAGES) {
            return SharedContent.Rejected(
                ShareRejectReason.TOO_MANY_IMAGES,
                detail = "count=${maxOf(streams.size, clipCount)} max=${ShareBounds.MAX_IMAGES}",
            )
        }
        if (streams.any { it.uriString.isNullOrBlank() }) {
            return SharedContent.Rejected(ShareRejectReason.NO_URI)
        }

        val accepted = ArrayList<ShareImageRef>(streams.size)
        var totalBytes = 0L
        for (ref in streams) {
            val mime: String = ref.effectiveMime
                ?: return SharedContent.Rejected(ShareRejectReason.IMAGE_TYPE_NOT_ALLOWED, detail = "mime=null")
            // APK / HTML smuggled in as an image, before any type-allowlist talk.
            if (mime == ShareBounds.MIME_APK) return SharedContent.Rejected(ShareRejectReason.APK_ARCHIVE)
            if (mime == ShareBounds.MIME_HTML) return SharedContent.Rejected(ShareRejectReason.HTML_DOCUMENT)

            val declaredImage = ShareBounds.isImageMime(ShareBounds.normaliseMime(ref.declaredMime))
            if (declaredImage && !ShareBounds.isImageMime(mime)) {
                return SharedContent.Rejected(
                    ShareRejectReason.DECLARED_IMAGE_NOT_IMAGE,
                    detail = "declared=${ShareBounds.normaliseMime(ref.declaredMime)} actual=$mime",
                )
            }
            if (mime !in ShareBounds.IMAGE_MIME_ALLOWLIST) {
                return SharedContent.Rejected(
                    ShareRejectReason.IMAGE_TYPE_NOT_ALLOWED,
                    detail = "mime=$mime allowed=${ShareBounds.IMAGE_MIME_ALLOWLIST.joinToString(",")}",
                )
            }
            if (!ref.openable) return SharedContent.Rejected(ShareRejectReason.UNOPENABLE_URI)

            val size = ref.declaredBytes
            if (size != null) {
                if (size < 0) return SharedContent.Rejected(ShareRejectReason.UNOPENABLE_URI)
                totalBytes += size
                if (totalBytes > ShareBounds.MAX_TOTAL_BYTES) {
                    return SharedContent.Rejected(
                        ShareRejectReason.OVERSIZED,
                        detail = "declaredBytes=$totalBytes limit=${ShareBounds.MAX_TOTAL_BYTES}",
                    )
                }
            }
            accepted += ShareImageRef(uriString = ref.uriString!!, mime = mime, sizeBytes = size)
        }

        return if (accepted.size == 1) {
            SharedContent.Images(accepted.first())
        } else {
            SharedContent.MultipleImages(accepted.toList())
        }
    }
}
/** Explicit user review state. Content is analysable only in [Accepted]. */
enum class ReviewState { Pending, Accepted, Discarded }

/**
 * A parsed share plus its review state. Nothing downstream may read [content] for analysis
 * unless [isAnalysable] is true - that is the "explicit user review" gate from F01.
 *
 * Immutable: [accept] and [discard] return new instances, so a UI can hold a stale copy without
 * the payload silently becoming analysable underneath it.
 */
data class IncomingShare(
    val content: SharedContent,
    val reviewState: ReviewState,
    val requiresReview: Boolean,
) {
    /** The single question every consumer must ask before touching the payload. */
    val isAnalysable: Boolean
        get() = reviewState == ReviewState.Accepted && content.isAnalysable

    /** Merged analysable input, or null while pending / after discard / when rejected. */
    fun analysableOrNull(): String? = if (isAnalysable) content.analysableText else null

    fun accept(): IncomingShare = copy(reviewState = ReviewState.Accepted)

    /**
     * Drops the payload reference: the content is replaced by [SharedContent.Empty] with
     * [ShareEmptyReason.DISCARDED], so the raw text and every content URI become unreachable
     * through this share. Also clears the review requirement.
     */
    fun discard(): IncomingShare = IncomingShare(
        content = SharedContent.Empty(ShareEmptyReason.DISCARDED),
        reviewState = ReviewState.Discarded,
        requiresReview = false,
    )

    /** Redacted: content's own toString never prints payload text or URIs. */
    override fun toString(): String =
        "IncomingShare(content=$content, reviewState=$reviewState, requiresReview=$requiresReview)"

    companion object {
        /** Review is required for anything that carried material; empty/rejected shares have nothing to confirm. */
        fun from(content: SharedContent, requiresReview: Boolean = content.isAnalysable): IncomingShare =
            IncomingShare(
                content = content,
                reviewState = if (requiresReview) ReviewState.Pending else ReviewState.Accepted,
                requiresReview = requiresReview,
            )
    }
}

/** Bounded byte access to one shared stream. Adapter-implemented; the bound is not negotiable. */
interface ShareByteSource {
    val declaredMime: String?
    val sizeBytes: Long?

    /**
     * Reads at most [maxBytes] and consumes from the share-wide [budget]. The same budget must be
     * passed to every source belonging to one share. Throws [ShareBoundExceeded] instead of
     * truncating silently.
     */
    fun readAtMost(maxBytes: Int, budget: ByteBudget): ByteArray
}

/**
 * Enforces the 8 MB ceiling across several images, which per-URI caps cannot do.
 * Pure Kotlin so it is unit tested; the streaming adapter calls [consume] per chunk.
 */
class ByteBudget(private val limit: Long = ShareBounds.MAX_TOTAL_BYTES) {
    private var used = 0L

    val limitBytes: Long get() = limit
    val consumed: Long get() = used
    val remaining: Long get() = (limit - used).coerceAtLeast(0L)

    /** Returns false and consumes nothing when the request would cross the ceiling. */
    fun tryConsume(bytes: Long): Boolean {
        if (bytes < 0L || bytes > remaining) return false
        used += bytes
        return true
    }

    /** Throws when the request would cross the ceiling. */
    fun consume(bytes: Long) {
        if (!tryConsume(bytes)) {
            throw ShareBoundExceeded(read = bytes, remaining = remaining, limit = limit)
        }
    }
}

/** Pure bounded reader so aggregate enforcement is covered by JVM tests without a provider. */
object BoundedShareStream {
    fun readAtMost(input: InputStream, maxBytes: Int, budget: ByteBudget): ByteArray {
        require(maxBytes > 0) { "maxBytes must be positive" }
        val limit = minOf(maxBytes.toLong(), budget.remaining)
        if (limit == 0L) throw ShareBoundExceeded(1L, budget.remaining, budget.limitBytes)

        val out = ByteArrayOutputStream(minOf(limit, 64L * 1024L).toInt())
        val buffer = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            val allowed = limit - total
            val readLimit = minOf(buffer.size.toLong(), allowed + 1L).toInt()
            val read = input.read(buffer, 0, readLimit)
            if (read < 0) return out.toByteArray()
            budget.consume(read.toLong())
            total += read
            if (total > limit) {
                throw ShareBoundExceeded(total, limit - (total - read), limit)
            }
            out.write(buffer, 0, read)
        }
    }
}

/** Bound breach. Carries sizes only - never payload bytes or file names. */
class ShareBoundExceeded(val read: Long, val remaining: Long, val limit: Long) :
    IllegalStateException("share byte bound exceeded: read=$read remaining=$remaining limit=$limit")
// =============================================================================================
// (b) THIN ANDROID ADAPTER - the only section that imports android.*.
//     Compiles against android.jar; NOT JVM-tested, because android.jar method bodies are stubs
//     that throw at runtime. Every decision is made by the pure core above.
// =============================================================================================

/**
 * Turns a received share Intent into a reviewed-pending [IncomingShare].
 *
 * Opens nothing. It reads intent extras and, when a [resolver] is supplied, only provider
 * *metadata* (MIME via `getType`, size via `OpenableColumns.SIZE`) - never the stream bytes,
 * never a URL, never a destination. Bytes stay unread until the user accepts the review and
 * [ContentUriShareByteSource] is used.
 *
 * Call off the main thread when passing a [resolver]: a metadata query is a cross-process call.
 */
fun intentToShare(intent: Intent): IncomingShare = intentToShare(intent, null)

/** As [intentToShare], but fills declared MIME and size from the content provider when available. */
fun intentToShare(intent: Intent, resolver: ContentResolver?): IncomingShare {
    val action = intent.action
    val declaredMime = intent.type ?: intent.clipData?.description?.getMimeType(0)

    val text = intent.getCharSequenceExtra(ShareActions.EXTRA_TEXT)?.toString()
    val subject = intent.getCharSequenceExtra(ShareActions.EXTRA_SUBJECT)?.toString()

    val uris = sharedUris(intent)
    val streams = uris.map { uri ->
        SharedStreamRef(
            uriString = uri?.toString(),
            declaredMime = resolver?.safeType(uri) ?: singleStreamMimeHint(action, declaredMime),
            declaredBytes = resolver?.safeSize(uri),
            // Optimistic: the handle exists, but whether it opens is only known at read time.
            // A non-openable provider surfaces as ShareBoundExceeded / IOException from the source.
            openable = true,
        )
    }
    val clipCount = intent.clipData?.itemCount ?: 0

    return IncomingShare.from(
        SharePayloadParser.parse(
            action = action,
            mimeType = declaredMime,
            text = text,
            subject = subject,
            streams = streams,
            clipCount = clipCount,
        ),
    )
}

/**
 * Every stream handle this intent carries: ClipData items first (that is what modern senders use),
 * then EXTRA_STREAM, then the intent data URI as a last resort. Nulls are preserved on purpose so
 * the parser can report NO_URI instead of pretending the share was empty.
 */
@Suppress("DEPRECATION")
private fun sharedUris(intent: Intent): List<Uri?> {
    val out = ArrayList<Uri?>(4)
    val clip = intent.clipData
    if (clip != null) {
        for (i in 0 until clip.itemCount) clip.getItemAt(i)?.uri?.let { out += it }
        if (out.isNotEmpty()) return out
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        intent.getParcelableExtra(ShareActions.EXTRA_STREAM, Uri::class.java)?.let { out += it }
        intent.getParcelableArrayListExtra(ShareActions.EXTRA_STREAM, Uri::class.java)?.let { out.addAll(it) }
    } else {
        @Suppress("UNCHECKED_CAST")
        val single = intent.getParcelableExtra<Uri>(ShareActions.EXTRA_STREAM)
        if (single != null) out += single
        @Suppress("UNCHECKED_CAST")
        val many = intent.getParcelableArrayListExtra<Uri>(ShareActions.EXTRA_STREAM)
        if (many != null) out.addAll(many)
    }
    if (out.isEmpty()) intent.data?.let { out += it }
    return out
}

/**
 * For a single-image ACTION_SEND the intent MIME *is* the attachment MIME, so it can stand in as
 * the declared MIME. For SEND_MULTIPLE the intent type is only a prefix (e.g. "image" + "/" + wildcard),
 * which is not a file type - return null and let the allowlist reject rather than guess.
 */
private fun singleStreamMimeHint(action: String?, intentMime: String?): String? {
    val mime = ShareBounds.normaliseMime(intentMime) ?: return null
    if (action == ShareActions.ACTION_SEND && mime != "image/*") return mime
    return null
}

private fun ContentResolver.safeType(uri: Uri?): String? {
    if (uri == null) return null
    return try {
        getType(uri)
    } catch (e: Exception) {
        // Provider refused or died: no type, no message content. Never rethrow payload-bearing errors.
        null
    }
}

private fun ContentResolver.safeSize(uri: Uri?): Long? {
    if (uri == null) return null
    return try {
        query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
        }
    } catch (e: Exception) {
        null
    }
}

/**
 * Bounded read of one `content://` handle. NOT JVM-tested (needs a live ContentResolver).
 *
 * Enforces the ceiling twice: the caller's [maxBytes] and this source's own [capBytes], and
 * throws [ShareBoundExceeded] instead of silently truncating - a truncated image would be a lie
 * to the OCR stage. Nothing is written to disk and no bytes are retained by this class.
 */
class ContentUriShareByteSource(
    private val resolver: ContentResolver,
    private val uri: Uri,
) : ShareByteSource {

    override val declaredMime: String? get() = resolver.safeType(uri)

    override val sizeBytes: Long? get() = resolver.safeSize(uri)

    override fun readAtMost(maxBytes: Int, budget: ByteBudget): ByteArray {
        val stream = resolver.openInputStream(uri) ?: throw IOException("no stream for share uri")
        return stream.use { input -> BoundedShareStream.readAtMost(input, maxBytes, budget) }
    }
}




