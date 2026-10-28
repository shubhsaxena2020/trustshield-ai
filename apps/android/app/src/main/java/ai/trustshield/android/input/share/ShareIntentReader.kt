/*
 * TrustShield Android - P3-07 (issue #86): read a share Intent without trusting it.
 *
 * WHY THIS FILE EXISTS
 *   B01 ([ShareReceiver.kt]) already decides the *shape* of a share: text, link, text+link, one
 *   image, many images, and the 8 MB / 5-image bounds. What it could not decide was everything
 *   about the Intent itself - which extras arrived, whether the app was granted read access to the
 *   URIs, whether a handle is even a `content://` handle - because that adapter is android-only and
 *   has zero JVM tests. This file splits that job:
 *
 *   (a) A pure Kotlin decision core ([ShareIntentSnapshot] -> [ShareIntentRead]) that describes an
 *       intent as plain data, refuses everything unsafe, and delegates every shape decision to the
 *       existing [SharePayloadParser]. Fully JVM-tested in ShareIntentReaderTest.kt.
 *   (b) A thin Android adapter ([ShareIntentReader]) at the bottom of this file. It only copies
 *       Intent/ClipData fields into a snapshot. NOT JVM-tested: android.jar method bodies are stubs
 *       that throw, so it is compile-verified by `:app:assembleDebug` and exercised on device only.
 *
 * SAFETY
 *   Nothing here fetches, opens, resolves or executes a URL, URI, deep link, attachment or APK.
 *   A handle is accepted only when its scheme is `content` AND the intent carried a read grant; a
 *   `file:`, `http(s):`, `android.resource:`, `javascript:` or `intent:` address is refused by name.
 *   Stream bytes are never read here - only provider *metadata* (MIME, display size) through the
 *   ContentResolver, and only in the adapter. No network, no file IO, no persistence: a URI grant
 *   is *described* from the intent flags, never claimed or persisted.
 *
 * PRIVACY
 *   Raw share text is held in memory for the analysis the user asked for and nothing else. Every
 *   toString() in this file is redacted: lengths and enum codes only, never text, subject, or a
 *   URI string, because a content URI can identify a personal document.
 */
package ai.trustshield.android.input.share

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns

// =============================================================================================
// (a) PURE DECISION CORE - plain Kotlin, no android type appears in any declaration below.
// =============================================================================================

/**
 * What the app was permitted to do with the URIs in one share intent, derived **only** from the
 * intent flags plus the scheme of the handles it carried. It is a description, not a capability
 * call: this file never takes a persistable URI permission and never opens a stream.
 */
enum class ShareUriGrant {
    /** No read flag, or no `content://` handle in the share. Treated as unreadable. */
    None,

    /** `FLAG_GRANT_READ_URI_PERMISSION` without the persistable flag: valid for this intent only. */
    TransientRead,

    /** Read + `FLAG_GRANT_PERSISTABLE_URI_PERMISSION`: the grant *could* be persisted; it is not. */
    PersistableRead,
    ;
}

/**
 * The flag bits [ShareUriGrant] is derived from, duplicated here as plain ints so the decision core
 * stays android-free. Values are `Intent.FLAG_GRANT_READ_URI_PERMISSION` (0x00000001) and
 * `Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION` (0x00000040); the unit tests pin both.
 */
object ShareIntentFlags {
    const val FLAG_GRANT_READ_URI_PERMISSION: Int = 0x00000001
    const val FLAG_GRANT_PERSISTABLE_URI_PERMISSION: Int = 0x00000040

    /**
     * Maps intent flags to a [ShareUriGrant].
     *
     * @param hasContentStream true only when the share actually carried a `content://` handle; a
     *   read flag on a text-only share grants access to nothing, so it must not be reported as a
     *   readable grant.
     */
    fun grantOf(flags: Int, hasContentStream: Boolean): ShareUriGrant = when {
        !hasContentStream -> ShareUriGrant.None
        flags and FLAG_GRANT_READ_URI_PERMISSION == 0 -> ShareUriGrant.None
        flags and FLAG_GRANT_PERSISTABLE_URI_PERMISSION != 0 -> ShareUriGrant.PersistableRead
        else -> ShareUriGrant.TransientRead
    }
}

/**
 * Something noteworthy about how a share arrived, that did not on its own stop analysis.
 * [code] is stable for tests and support; [message] is the short Hinglish line the UI can show.
 */
enum class ShareIntentIssue(val code: String, val message: String) {
    NON_CONTENT_SCHEME(
        "non_content_scheme",
        "Yeh file address app nahi khol sakta. Content share karein.",
    ),
    NO_READ_GRANT("no_read_grant", "App ko is file ko padhne ki permission nahi mili."),
    DUPLICATE_STREAM("duplicate_stream", "Ek hi file do baar aayi thi, isliye ek baar gini gayi."),
    MALFORMED_EXTRA("malformed_extra", "Share ka data padha nahi ja saka."),
    EMPTY_CLIP("empty_clip", "Share ki list khaali thi."),
    NULL_STREAM_URI("null_stream_uri", "Share mein ek khaali file entry thi, address nahi mila."),
    HTML_TEXT_IGNORED("html_text_ignored", "HTML wala hissa text ki tarah nahi padha jaata."),
    CLIP_ITEMS_TRUNCATED("clip_items_truncated", "Share mein bahut items the; pehle 20 hi padhe gaye."),
    ;

    override fun toString(): String = "ShareIntentIssue($code)"
}

/**
 * One stream handle as plain data. The adapter fills this in from an Intent without opening it;
 * [providerMime] is `ContentResolver.getType` metadata (never a byte sniff), [declaredBytes] is the
 * provider's `OpenableColumns.SIZE`, and [openable] stays `true` until a bounded read proves
 * otherwise - this layer only knows what the intent claimed.
 */
data class ShareIntentStreamSnapshot(
    val uriString: String?,
    val scheme: String?,
    val declaredMime: String?,
    val declaredBytes: Long? = null,
    val providerMime: String? = null,
    val openable: Boolean = true,
) {
    /** Redacted: a content URI can point at one personal document, so it is never printed. */
    override fun toString(): String =
        "ShareIntentStreamSnapshot(uri=${if (uriString.isNullOrBlank()) "null" else "present"}, " +
            "scheme=${scheme?.trim()?.lowercase()?.takeIf { it == "content" } ?: if (scheme == null) "null" else "other"}, " +
            "declaredMime=${if (declaredMime.isNullOrBlank()) "null" else "present"}, declaredBytes=$declaredBytes, " +
            "providerMime=${if (providerMime.isNullOrBlank()) "null" else "present"}, openable=$openable)"
}

/**
 * A share intent described as plain data, so the decision layer never needs an `Intent`.
 *
 * Field order mirrors the order a share can carry text: [text] (`EXTRA_TEXT`), then
 * [clipTextParts] (`ClipData` text items), then [subject] (`EXTRA_SUBJECT`) and [title]
 * (`EXTRA_TITLE`). [htmlText] is recorded but never analysed - the app does not parse HTML.
 */
data class ShareIntentSnapshot(
    val action: String? = null,
    val declaredMime: String? = null,
    val text: String? = null,
    val subject: String? = null,
    val title: String? = null,
    val htmlText: String? = null,
    val clipItemCount: Int = 0,
    val clipTextParts: List<String> = emptyList(),
    val clipUris: List<ShareIntentStreamSnapshot> = emptyList(),
    val extraStreamUris: List<ShareIntentStreamSnapshot> = emptyList(),
    val dataUri: String? = null,
    val readGrant: ShareUriGrant = ShareUriGrant.None,
) {
    /** Redacted: lengths and counts only. The payload text itself is never printable. */
    override fun toString(): String =
        "ShareIntentSnapshot(action=${action?.trim()?.takeIf { it == ShareActions.ACTION_SEND || it == ShareActions.ACTION_SEND_MULTIPLE } ?: if (action == null) "null" else "other"}, " +
            "declaredMime=${if (declaredMime.isNullOrBlank()) "null" else "present"}, " +
            "textLen=${text?.length ?: 0}, subjectLen=${subject?.length ?: 0}, " +
            "titleLen=${title?.length ?: 0}, htmlLen=${htmlText?.length ?: 0}, " +
            "clipItemCount=$clipItemCount, clipTextParts=${clipTextParts.size}, " +
            "clipUris=${clipUris.size}, extraStreamUris=${extraStreamUris.size}, " +
            "dataUri=${if (dataUri.isNullOrBlank()) "null" else "present"}, readGrant=$readGrant)"
}

/**
 * The outcome of reading one share intent.
 *
 * [Accepted] never means "safe": it means the reader found analysable material. The material still
 * sits behind B01's explicit review gate, so [analysableOrNull] stays null until the user accepts
 * the review ([ShareIntentRead.Accepted.share] has `reviewState == Pending`).
 */
sealed interface ShareIntentRead {

    /** Everything noteworthy about how this share arrived. Empty for a clean share. */
    val issues: List<ShareIntentIssue>

    /**
     * True when the reader found material that *can* be analysed. This is not a verdict and not a
     * safety claim, and it does not bypass the review gate; see [analysableOrNull].
     */
    val isAnalysable: Boolean

    /** The gated analysable text, or null while the share is refused / awaiting review. */
    fun analysableOrNull(): String?

    /** Re-issue this result with extra issues appended (used by the adapter); duplicates dropped. */
    fun withIssues(extra: List<ShareIntentIssue>): ShareIntentRead

    /**
     * A share the reader could work with. [share] keeps B01's review state, [grant] records what the
     * intent allowed for its URIs, and [issues] records what was odd without being fatal.
     */
    data class Accepted(
        val share: IncomingShare,
        val grant: ShareUriGrant,
        override val issues: List<ShareIntentIssue> = emptyList(),
    ) : ShareIntentRead {

        /** Material is present. The review gate still applies; see [analysableOrNull]. */
        override val isAnalysable: Boolean get() = share.content.isAnalysable

        /** Delegates to [IncomingShare]: null until the user accepts the review. */
        override fun analysableOrNull(): String? = share.analysableOrNull()

        override fun withIssues(extra: List<ShareIntentIssue>): ShareIntentRead =
            copy(issues = (issues + extra).distinct())

        override fun toString(): String =
            "ShareIntentRead.Accepted(content=${share.content}, reviewState=${share.reviewState}, " +
                "grant=$grant, issues=${issues.map { it.code }})"
    }

    /**
     * A share this app will not analyse, exactly one reason deep: [reason] for a refusal, or
     * [emptyReason] when there was simply nothing to look at. [issues] explains the detail.
     */
    data class Refused(
        val reason: ShareRejectReason? = null,
        val emptyReason: ShareEmptyReason? = null,
        override val issues: List<ShareIntentIssue> = emptyList(),
    ) : ShareIntentRead {

        init {
            require((reason == null) != (emptyReason == null)) {
                "a refused share carries exactly one reason (reason=$reason, emptyReason=$emptyReason)"
            }
        }

        override val isAnalysable: Boolean get() = false

        override fun analysableOrNull(): String? = null

        override fun withIssues(extra: List<ShareIntentIssue>): ShareIntentRead =
            copy(issues = (issues + extra).distinct())

        override fun toString(): String =
            "ShareIntentRead.Refused(reason=${(reason?.code ?: emptyReason?.code)}, " +
                "issues=${issues.map { it.code }})"
    }
}

/**
 * Pure decision core for share intents.
 *
 * Reads nothing, opens nothing, resolves nothing: every URI stays an opaque string. The rules, in
 * order:
 *  1. action must be SEND or SEND_MULTIPLE (null -> NO_ACTION, anything else -> UNSUPPORTED_ACTION);
 *  2. a declared APK or HTML type is refused before anything else is looked at;
 *  3. handles are collected ClipData first, then `EXTRA_STREAM`, then `intent.data`, de-duplicated
 *     by URI string; a null entry, a non-`content` scheme, or a `content` handle with no read grant
 *     refuses the whole share (half a share is not an analysis);
 *  4. text is merged deterministically, HTML is ignored and said so, then [SharePayloadParser]
 *     decides the shape and the limits, so there is exactly one place that knows about 8 MB / 5
 *     images / MIME allowlists.
 */
object ShareIntentReaderCore {

    private const val SCHEME_CONTENT = "content"

    /** Provider honesty markers: these say "unknown", so they must not be read as a type claim. */
    private const val MIME_UNKNOWN = "application/octet-stream"
    private const val MIME_ANY = "*/*"

    fun read(snapshot: ShareIntentSnapshot): ShareIntentRead {
        val action = snapshot.action?.trim()
        if (action.isNullOrEmpty()) {
            return ShareIntentRead.Refused(emptyReason = ShareEmptyReason.NO_ACTION)
        }
        if (action != ShareActions.ACTION_SEND && action != ShareActions.ACTION_SEND_MULTIPLE) {
            return ShareIntentRead.Refused(emptyReason = ShareEmptyReason.UNSUPPORTED_ACTION)
        }

        val declared = ShareBounds.normaliseMime(snapshot.declaredMime)
        // A lying intent MIME is refused before any handle or text is considered.
        if (declared == ShareBounds.MIME_APK) return ShareIntentRead.Refused(reason = ShareRejectReason.APK_ARCHIVE)
        if (declared == ShareBounds.MIME_HTML) return ShareIntentRead.Refused(reason = ShareRejectReason.HTML_DOCUMENT)

        val issues = ArrayList<ShareIntentIssue>(4)
        if (!snapshot.htmlText.isNullOrBlank()) issues += ShareIntentIssue.HTML_TEXT_IGNORED
        if (snapshot.clipItemCount > ShareBounds.MAX_CLIP_ITEMS) issues += ShareIntentIssue.CLIP_ITEMS_TRUNCATED
        if (snapshot.clipItemCount > 0 && snapshot.clipTextParts.isEmpty() && snapshot.clipUris.isEmpty()) {
            issues += ShareIntentIssue.EMPTY_CLIP
        }

        val candidates = ArrayList<ShareIntentStreamSnapshot>(
            snapshot.clipUris.size + snapshot.extraStreamUris.size + 1,
        )
        candidates += snapshot.clipUris
        candidates += snapshot.extraStreamUris
        snapshot.dataUri?.trim()?.takeIf { it.isNotEmpty() }?.let { data ->
            candidates += ShareIntentStreamSnapshot(
                uriString = data,
                scheme = null,
                declaredMime = concreteMime(declared),
                declaredBytes = null,
                providerMime = null,
            )
        }

        val seen = LinkedHashSet<String>(candidates.size)
        val streams = ArrayList<ShareIntentStreamSnapshot>(candidates.size)
        var sawNullHandle = false
        var sawForeignScheme = false
        for (candidate in candidates) {
            val uriString = candidate.uriString?.trim()
            if (uriString.isNullOrEmpty()) {
                sawNullHandle = true
                issues += ShareIntentIssue.NULL_STREAM_URI
                continue
            }
            if (!seen.add(uriString)) {
                issues += ShareIntentIssue.DUPLICATE_STREAM
                continue
            }
            val scheme = schemeOf(candidate)
            if (scheme != SCHEME_CONTENT) {
                sawForeignScheme = true
                issues += ShareIntentIssue.NON_CONTENT_SCHEME
                continue
            }
            streams += candidate.copy(uriString = uriString, scheme = scheme)
        }

        // A share whose file handle cannot be trusted is refused as a whole, never half-analysed.
        if (sawNullHandle) return ShareIntentRead.Refused(reason = ShareRejectReason.NO_URI, issues = issues.distinct())
        if (sawForeignScheme) {
            return ShareIntentRead.Refused(reason = ShareRejectReason.UNOPENABLE_URI, issues = issues.distinct())
        }
        if (streams.isNotEmpty() && snapshot.readGrant == ShareUriGrant.None) {
            issues += ShareIntentIssue.NO_READ_GRANT
            return ShareIntentRead.Refused(reason = ShareRejectReason.UNOPENABLE_URI, issues = issues.distinct())
        }

        val body = mergedText(snapshot)
        val subject = subjectOf(snapshot, body)
        val content = SharePayloadParser.parse(
            action = action,
            mimeType = snapshot.declaredMime,
            text = body,
            subject = subject,
            streams = streams.map { toStreamRef(it) },
            clipCount = streams.size,
        )

        return when (content) {
            is SharedContent.Empty -> ShareIntentRead.Refused(
                emptyReason = content.reason,
                issues = issues.distinct(),
            )
            is SharedContent.Rejected -> ShareIntentRead.Refused(
                reason = content.reason,
                issues = issues.distinct(),
            )
            else -> ShareIntentRead.Accepted(
                share = IncomingShare.from(content),
                grant = snapshot.readGrant,
                issues = issues.distinct(),
            )
        }
    }

    /**
     * Merges `EXTRA_TEXT` then the ClipData text items in order: trimmed, blanks dropped, identical
     * strings kept once. When nothing analysable is left the first raw value is handed back on
     * purpose, so the parser can still tell "sender sent a blank body" (BLANK_TEXT) from "sender
     * sent nothing at all" (NO_CONTENT).
     */
    private fun mergedText(snapshot: ShareIntentSnapshot): String? {
        val sources = ArrayList<String>(2 + snapshot.clipTextParts.size)
        snapshot.text?.let { sources += it }
        sources += snapshot.clipTextParts.take(ShareBounds.MAX_CLIP_ITEMS)

        val distinct = LinkedHashSet<String>(sources.size)
        for (source in sources) {
            val trimmed = source.trim()
            if (trimmed.isNotEmpty()) distinct += trimmed
        }
        if (distinct.isNotEmpty()) return distinct.joinToString("\n")
        return sources.firstOrNull()
    }

    /**
     * `EXTRA_SUBJECT` first, then `EXTRA_TITLE`; a subject that already appears in the body is
     * dropped rather than counted twice (senders copy the same string into both fields).
     */
    private fun subjectOf(snapshot: ShareIntentSnapshot, body: String?): String? {
        val bodyParts = body?.split('\n')?.toSet().orEmpty()
        val candidates = listOf(snapshot.subject, snapshot.title)
            .mapNotNull { raw -> raw?.trim()?.takeIf { it.isNotEmpty() } }
        return candidates.firstOrNull { it !in bodyParts }
    }

    /** `content` for both `content://` and bare `content:`; anything else is refused. */
    private fun schemeOf(candidate: ShareIntentStreamSnapshot): String? {
        val declaredScheme = candidate.scheme?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        if (declaredScheme != null) return declaredScheme
        val uriString = candidate.uriString?.trim().orEmpty()
        val separator = uriString.indexOf(':')
        if (separator <= 0) return null
        return uriString.substring(0, separator).lowercase()
    }

    /** A wildcard MIME is a category, not a file type, so it is not a per-file declaration. */
    private fun concreteMime(normalised: String?): String? =
        normalised?.takeIf { it.isNotEmpty() && it != MIME_ANY && !it.contains('*') }

    /**
     * Provider-reported type, used where B07 would otherwise sniff header bytes. `octet-stream` and
     * the any-type wildcard only say "unknown", so they are discarded instead of accusing an honest
     * sender of mislabelling a file.
     */
    private fun toStreamRef(stream: ShareIntentStreamSnapshot): SharedStreamRef = SharedStreamRef(
        uriString = stream.uriString,
        declaredMime = stream.declaredMime,
        declaredBytes = stream.declaredBytes,
        openable = stream.openable,
        sniffedMime = ShareBounds.normaliseMime(stream.providerMime)
            ?.takeIf { it.isNotEmpty() && it != MIME_UNKNOWN && it != MIME_ANY },
    )
}

// =============================================================================================
// (b) THIN ANDROID ADAPTER - the only section that touches android.*.
//     Compiles against android.jar, whose method bodies are stubs that throw: it is therefore NOT
//     JVM-tested. It makes no decision of its own - it copies Intent/ClipData fields into a
//     ShareIntentSnapshot (guarding every extra) and delegates to ShareIntentReaderCore.read().
// =============================================================================================

/**
 * Reads a received share `Intent` into a [ShareIntentRead], for the navigation/share entry.
 *
 * What it does: copies intent extras, ClipData text items and ClipData URI items into a
 * [ShareIntentSnapshot], and asks the content provider that owns a handle for its *metadata*
 * (`getType` and `OpenableColumns.SIZE`). What it does not do: open a stream, read a byte, fetch a
 * URL, resolve a destination, keep the grant, or write anything to disk or preferences.
 *
 * Not JVM-tested: constructing an `Intent` needs android.jar stubs. On device, call [read] off the
 * main thread when a share carries URIs, because the metadata calls are cross-process.
 */
class ShareIntentReader(private val context: Context) {

    /** `EXTRA_TITLE` - some senders put the page/app title here instead of `EXTRA_SUBJECT`. */
    private companion object {
        const val EXTRA_TITLE: String = "android.intent.extra.TITLE"
        const val EXTRA_HTML_TEXT: String = "android.intent.extra.HTML_TEXT"
    }

    /**
     * Reads [intent]; a null intent is a share with no action (NO_ACTION), not a crash.
     *
     * Every extra read is guarded: a hostile or malformed extra produces
     * [ShareIntentIssue.MALFORMED_EXTRA] and an empty field, never an exception for the caller.
     */
    fun read(intent: Intent?): ShareIntentRead {
        if (intent == null) return ShareIntentReaderCore.read(ShareIntentSnapshot())

        val issues = ArrayList<ShareIntentIssue>(2)

        /** Reads one extra; a failure leaves the field empty and is recorded, never rethrown. */
        fun <T> guarded(read: () -> T, fallback: T): T = try {
            read()
        } catch (e: Exception) {
            issues += ShareIntentIssue.MALFORMED_EXTRA
            fallback
        }

        val action = guarded({ intent.action }, null)
        val intentMime = guarded({ intent.type }, null)
        val text = guarded({ intent.getCharSequenceExtra(ShareActions.EXTRA_TEXT)?.toString() }, null)
        val subject = guarded({ intent.getCharSequenceExtra(ShareActions.EXTRA_SUBJECT)?.toString() }, null)
        val title = guarded({ intent.getCharSequenceExtra(EXTRA_TITLE)?.toString() }, null)
        val htmlText = guarded({ intent.getCharSequenceExtra(EXTRA_HTML_TEXT)?.toString() }, null)

        val clip = guarded({ intent.clipData }, null)
        val clipMime = guarded({ clip?.description?.getMimeType(0) }, null)
        val clipItemCount = guarded({ clip?.itemCount ?: 0 }, 0)

        val clipTextParts = ArrayList<String>(minOf(clipItemCount, ShareBounds.MAX_CLIP_ITEMS))
        val clipUris = ArrayList<ShareIntentStreamSnapshot>(minOf(clipItemCount, ShareBounds.MAX_CLIP_ITEMS))
        val seenUriStrings = LinkedHashSet<String>(ShareBounds.MAX_IMAGES)
        var nullStreamRecorded = false
        for (index in 0 until minOf(clipItemCount, ShareBounds.MAX_CLIP_ITEMS)) {
            val item = guarded({ clip?.getItemAt(index) }, null)
            val itemText = guarded({ item?.text?.toString() }, null)
            val itemUri = guarded({ item?.uri }, null)
            if (!itemText.isNullOrBlank()) clipTextParts += itemText
            when {
                itemUri != null -> {
                    val uriString = safe { itemUri.toString() }?.trim()
                    if (uriString.isNullOrEmpty()) {
                        if (!nullStreamRecorded) {
                            clipUris += ShareIntentStreamSnapshot(
                                uriString = null,
                                scheme = null,
                                declaredMime = clipMime,
                            )
                            nullStreamRecorded = true
                        }
                    } else if (!seenUriStrings.add(uriString)) {
                        issues += ShareIntentIssue.DUPLICATE_STREAM
                    } else {
                        if (seenUriStrings.size > ShareBounds.MAX_IMAGES) {
                            return ShareIntentRead.Refused(
                                reason = ShareRejectReason.TOO_MANY_IMAGES,
                                issues = issues.distinct(),
                            )
                        }
                        clipUris += streamSnapshot(itemUri, clipMime ?: intentMime)
                    }
                }
                // A clip slot with neither text nor a handle is a lost attachment, not an empty share.
                itemText.isNullOrBlank() -> if (!nullStreamRecorded) {
                    clipUris += ShareIntentStreamSnapshot(
                        uriString = null,
                        scheme = null,
                        declaredMime = clipMime,
                        declaredBytes = null,
                        providerMime = null,
                    )
                    nullStreamRecorded = true
                }
            }
        }

        val extraStreamUris = ArrayList<ShareIntentStreamSnapshot>(2)
        for (uri in streamExtras(intent, issues)) {
            if (uri != null) {
                val uriString = safe { uri.toString() }?.trim()
                if (uriString.isNullOrEmpty()) {
                    if (!nullStreamRecorded) {
                        extraStreamUris += ShareIntentStreamSnapshot(
                            uriString = null,
                            scheme = null,
                            declaredMime = intentMime,
                        )
                        nullStreamRecorded = true
                    }
                } else if (!seenUriStrings.add(uriString)) {
                    issues += ShareIntentIssue.DUPLICATE_STREAM
                } else {
                    if (seenUriStrings.size > ShareBounds.MAX_IMAGES) {
                        return ShareIntentRead.Refused(
                            reason = ShareRejectReason.TOO_MANY_IMAGES,
                            issues = issues.distinct(),
                        )
                    }
                    extraStreamUris += streamSnapshot(uri, intentMime)
                }
            } else {
                if (!nullStreamRecorded) {
                    extraStreamUris += ShareIntentStreamSnapshot(
                        uriString = null,
                        scheme = null,
                        declaredMime = intentMime,
                        declaredBytes = null,
                        providerMime = null,
                    )
                    nullStreamRecorded = true
                }
            }
        }

        val dataUri = guarded({ intent.data?.toString() }, null)
        val hasContentHandle = (clipUris + extraStreamUris).any { it.scheme == "content" } ||
            dataUri?.startsWith("content:") == true

        return ShareIntentReaderCore.read(
            ShareIntentSnapshot(
                action = action,
                declaredMime = intentMime ?: clipMime,
                text = text,
                subject = subject,
                title = title,
                htmlText = htmlText,
                clipItemCount = clipItemCount,
                clipTextParts = clipTextParts,
                clipUris = clipUris,
                extraStreamUris = extraStreamUris,
                dataUri = dataUri,
                readGrant = guarded({ ShareIntentFlags.grantOf(intent.flags, hasContentHandle) }, ShareUriGrant.None),
            ),
        ).withIssues(issues)
    }

    /**
     * One handle as data. [fallbackMime] is the type the intent or clip *declared*; the provider's
     * own answer is recorded separately so a contradictory provider type can be caught without
     * guessing, and nothing is sniffed because no byte is read here.
     */
    private fun streamSnapshot(uri: Uri, fallbackMime: String?): ShareIntentStreamSnapshot =
        ShareIntentStreamSnapshot(
            uriString = safe { uri.toString() },
            scheme = safe { uri.scheme },
            declaredMime = ShareBounds.normaliseMime(fallbackMime)?.takeIf { it.isNotEmpty() },
            declaredBytes = safe { declaredBytes(uri) },
            providerMime = safe { context.contentResolver.getType(uri) },
            // Optimistic by design: whether the handle really opens is only known at read time.
            openable = true,
        )

    /**
     * `EXTRA_STREAM` as a single Uri and as an `ArrayList<Uri>`, on both sides of the API 33
     * parcelable split. A null entry means "the sender claimed a stream and gave us nothing".
     */
    private fun streamExtras(intent: Intent, issues: MutableList<ShareIntentIssue>): List<Uri?> {
        val out = ArrayList<Uri?>(2)
        fun <T> guarded(read: () -> T, fallback: T): T = try {
            read()
        } catch (e: Exception) {
            issues += ShareIntentIssue.MALFORMED_EXTRA
            fallback
        }
        guarded({ singleStreamExtra(intent) }, null)?.let { out += it }
        guarded({ multipleStreamExtras(intent) }, null)?.let { out.addAll(it) }
        return out
    }

    @Suppress("DEPRECATION")
    private fun singleStreamExtra(intent: Intent): Uri? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(ShareActions.EXTRA_STREAM, Uri::class.java)
        } else {
            intent.getParcelableExtra(ShareActions.EXTRA_STREAM)
        }

    @Suppress("DEPRECATION", "UNCHECKED_CAST")
    private fun multipleStreamExtras(intent: Intent): ArrayList<Uri>? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableArrayListExtra(ShareActions.EXTRA_STREAM, Uri::class.java)
        } else {
            intent.getParcelableArrayListExtra<Uri>(ShareActions.EXTRA_STREAM)
        }

    /** `OpenableColumns.SIZE` is provider metadata; no stream is opened to obtain it. */
    private fun declaredBytes(uri: Uri): Long? =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
        }

    /** A provider that refuses or dies yields null, never an exception carrying its own message. */
    private inline fun <T> safe(read: () -> T): T? = try {
        read()
    } catch (e: Exception) {
        null
    }
}
