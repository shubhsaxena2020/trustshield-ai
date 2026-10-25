/*
 * TrustShield Android - P3-08 (issue #87): ephemeral share-to-analysis coordinator.
 *
 * WHY THIS FILE EXISTS
 *   P3-07 ([ShareIntentReader]) proves a share Intent arrived safely and hands back an [IncomingShare]
 *   plus a review gate. P3-02 ([LocalScanPipeline]) turns text into a deterministic, evidence-backed
 *   verdict. Nothing joined the two: shared text never reached the pipeline, a shared image had no
 *   route into it at all, and nothing proved that the payload references were dropped afterwards.
 *   This file is that join, and only that join:
 *
 *       ShareIntentRead (P3-07)  ─┐
 *       ShareIngestRequest       ─┼──▶  ShareIngestCoordinator  ──▶  LocalScanPipeline (A05)  ──▶  ScanOutcome
 *       ShareImageTextSource     ─┘        (bounded, clearing)        (unchanged, deterministic)
 *
 * WHAT IT DOES NOT DO
 *   - It never fetches, opens, resolves, navigates to or executes a URL, URI, deep link, attachment
 *     or APK. There is no network code, no `android.*` import, no `Context`, no `ContentResolver`,
 *     no file IO, no preference and no log statement in this file.
 *   - It never sets a verdict. The only score, label, confidence, evidence and quote in the result
 *     come from [LocalScanPipeline] - the same deterministic engine the scan screen uses. Shared text
 *     and typed text therefore produce byte-identical [ScanOutcome]s for the same string, which is
 *     pinned by a test.
 *   - It never invents OCR text. Without a configured [ShareImageTextSource] the honest default
 *     ([NoShareImageTextSource]) reports "OCR is not available on this build" and the share ends as
 *     [ShareIngestOutcome.Unavailable], never as a low-risk result and never with a fabricated quote.
 *
 * THE OCR SEAM (and why it is a seam rather than the ML Kit adapter)
 *   [ShareImageTextSource] is one synchronous function: bytes in, [ShareImageText] out. The bundled
 *   Latin/Devanagari adapter lives in `MlKitTextRecognizer.kt` (P3-04, #83) and is `suspend`, so it is
 *   not imported here - that would make this decision layer an Android class and cost the JVM tests
 *   that are the point of the package. The wiring layer bridges the two with one lambda:
 *
 *   ```kotlin
 *   val coordinator = ShareIngestCoordinator(
 *       textSource = ShareImageTextSource { bytes, mime ->
 *           when (val ocr = runBlocking(Dispatchers.Default) { recognizer.recognize(bytes, mime) }) {
 *               is OcrOutcome.Recognized -> ShareImageText.Text(
 *                   text = ocr.result.text,
 *                   meanConfidence = ocr.result.meanConfidence,
 *                   truncated = ocr.result.truncated,
 *               )
 *               is OcrOutcome.Failed -> ShareImageText.Unavailable(ocr.failure.name.lowercase())
 *           }
 *       },
 *       byteSourceFor = { ref -> ContentUriShareByteSource(resolver, Uri.parse(ref.uriString)) },
 *   )
 *   ```
 *
 *   `ShareImageText.Unavailable("ocr_unavailable")` is the one reason string this layer reads by
 *   name; every other reason is reported as [ShareIngestRefusal.OCR_FAILED], so a new OCR failure
 *   enum cannot silently become a verdict.
 *
 * BYTE BUDGET
 *   One [ByteBudget] per share, sized [ShareBounds.MAX_TOTAL_BYTES] by default and shared by every
 *   image in it. An image whose declared or actual size would cross the ceiling is refused *before*
 *   its bytes are used: nothing is silently truncated, because a truncated image would be a lie told
 *   to the OCR stage. A multi-image share is analysed as a whole or not at all - half a share is not
 *   an analysis, and a partial result would hide the part that could not be read.
 *
 * CLEARING / PRIVACY (N01)
 *   Every byte array this class obtains is overwritten with zeros (`Arrays.fill(buffer, 0)`) in a
 *   `finally` block, so both the success and every failure path clear it, and the reference is then
 *   dropped. The class keeps no field that can hand back shared text, bytes or a URI after
 *   [ingest] returns; `ShareIngestReceipt.payloadReleased` states that fact to the caller, and a test
 *   reflects over the declared fields to prove no such field exists. Text the user asked to analyse
 *   survives only inside the returned [ScanOutcome] (its evidence quotes are substrings of it), and
 *   this file never writes it anywhere - no disk, no preferences, no log, no crash report.
 *   Every `toString()` below is redacted: counts, enum codes and sizes only.
 *
 * THREADING
 *   Synchronous and stateless, like [LocalScanPipeline]: call it from a background dispatcher, one
 *   share at a time, on the same pipeline instance the screen owns. The OCR seam is handed a byte
 *   array that is zeroed as soon as `recognize` returns, so an implementation must not retain it.
 */
package ai.trustshield.android.input.share

import ai.trustshield.android.core.analysis.LocalScanPipeline
import ai.trustshield.android.core.analysis.ScanOutcome
import ai.trustshield.android.core.model.InputType
import java.util.Arrays

// =============================================================================================
// OCR seam
// =============================================================================================

/**
 * What an OCR pass can say: the text it read, or why it read nothing. There is deliberately no
 * "probably this" and no empty-but-successful state - blank text is reported as [Unavailable] by the
 * coordinator, not smoothed over here.
 */
sealed interface ShareImageText {

    /**
     * Recognised text.
     *
     * @param text the recognised characters. In memory only for the duration of one analysis.
     * @param meanConfidence the recogniser's own mean element confidence in `0.0..1.0`, or null when
     *   it reported none. Never estimated here, and never used as a risk value.
     * @param truncated true when the recogniser itself cut the text at its own bound.
     */
    data class Text(
        val text: String,
        val meanConfidence: Double? = null,
        val truncated: Boolean = false,
    ) : ShareImageText {
        /** Redacted: the recognised text is never printable. */
        override fun toString(): String =
            "ShareImageText.Text(chars=${text.length}, meanConfidence=$meanConfidence, truncated=$truncated)"
    }

    /**
     * No text was produced. [reason] is a stable code from the OCR implementation (see
     * [NoShareImageTextSource.REASON] for the "no module wired" case).
     */
    data class Unavailable(val reason: String) : ShareImageText {
        override fun toString(): String = "ShareImageText.Unavailable($reason)"
    }
}

/**
 * The one thing a share flow needs from OCR: recognised text for these bytes, or an honest reason why
 * there is none. Implemented by the wiring layer around the bundled ML Kit adapter (see the file
 * header); [NoShareImageTextSource] is the default until that module is wired.
 *
 * Contract: the caller owns [bytes] and zeroes it as soon as this returns, so an implementation must
 * not keep the array, log it, or hand it to anything that persists it.
 */
fun interface ShareImageTextSource {
    fun recognize(bytes: ByteArray, mime: String): ShareImageText
}

/**
 * The honest default: this build has no OCR module, so every image share is
 * [ShareIngestRefusal.OCR_UNAVAILABLE] with a Hinglish limitation. It never returns text, which means
 * no image can produce a verdict - or an invented evidence quote - until a real recogniser is wired.
 */
object NoShareImageTextSource : ShareImageTextSource {

    /** The one OCR reason string this layer interprets by name. */
    const val REASON: String = "ocr_unavailable"

    override fun recognize(bytes: ByteArray, mime: String): ShareImageText =
        ShareImageText.Unavailable(REASON)
}

// =============================================================================================
// Request
// =============================================================================================

/**
 * One share, ready to be analysed. Each image keeps its own byte source and its own declared MIME, so
 * a share can never be analysed against another image's type.
 */
sealed interface ShareIngestRequest {

    /** Shared text, already past the review gate. Analysed as [InputType.TEXT]. */
    data class Text(val text: String) : ShareIngestRequest {
        /** Redacted: the shared text is never printable. */
        override fun toString(): String = "ShareIngestRequest.Text(chars=${text.length})"
    }

    /** One shared image. Analysed as [InputType.IMAGE] after OCR. */
    data class Image(val source: ShareByteSource, val mime: String) : ShareIngestRequest {
        override fun toString(): String = "ShareIngestRequest.Image(mime=$mime)"
    }

    /** Several shared images, one byte budget. Analysed as one [InputType.IMAGE] input or not at all. */
    data class Images(val items: List<Pair<ShareByteSource, String>>) : ShareIngestRequest {
        override fun toString(): String = "ShareIngestRequest.Images(count=${items.size})"
    }
}

// =============================================================================================
// Outcome
// =============================================================================================

/**
 * Why a share produced no verdict. [code] is stable for tests, support and screenshots; [message] is
 * the short Hinglish line the result screen shows. This enum is closed on purpose: a new failure mode
 * has to be added here deliberately instead of appearing as ad-hoc copy.
 */
enum class ShareIngestRefusal(val code: String, val message: String) {
    /** Type or action this app does not analyse (APK, HTML page, an unknown action). */
    CLOSED_BY_DESIGN("closed_by_design", "Yeh share app abhi check nahi karta."),

    /** Nothing readable arrived: blank text, emoji only, or an empty share. */
    EMPTY_CONTENT("empty_content", "Is share mein check karne layak kuch nahi tha."),

    /** The share, together with the images already read, crossed the one shared byte budget. */
    BYTE_BUDGET_EXCEEDED("byte_budget_exceeded", "Share ka data size limit se bada tha, isliye check nahi hua."),

    /** A single image is larger than the whole share budget, so no read was even attempted. */
    IMAGE_TOO_LARGE("image_too_large", "Ek image size limit se badi hai, isliye check nahi hua."),

    /** More images in one share than [ShareBounds.MAX_IMAGES]. */
    TOO_MANY_IMAGES("too_many_images", "Ek saath 5 se zyada image check nahi kar sakte."),

    /** A MIME this app does not decode for OCR. */
    UNSUPPORTED_MIME("unsupported_mime", "Yeh file type support nahi hai (PNG, JPEG, WebP chalte hain)."),

    /** No OCR implementation is wired into this build. Never a safe verdict. */
    OCR_UNAVAILABLE("ocr_unavailable", "Image se text padhne wala module is build mein nahi hai."),

    /** OCR ran but produced no usable text, or the seam itself failed. */
    OCR_FAILED("ocr_failed", "Image se text padha nahi ja saka."),

    /** The bounded read of a shared stream failed. */
    READ_FAILED("read_failed", "Shared file padhi nahi ja saki."),

    /** The share is still [ReviewState.Pending]: the user must accept it before anything is read. */
    REVIEW_NOT_ACCEPTED("review_not_accepted", "Pehle share ko review karke accept karein."),
    ;

    /** Redacted by construction; the code is safe to print. */
    override fun toString(): String = "ShareIngestRefusal($code)"
}

/**
 * Counters for one analysed share. Deliberately content-free: no text, no URI, no bytes - only sizes
 * and flags, so it can sit in a support screenshot or a debug overlay without carrying a payload.
 *
 * @property inputType the [InputType] the pipeline stamped ([InputType.TEXT] or [InputType.IMAGE]).
 * @property imagesRead how many images were read *and* recognised into the analysed input.
 * @property bytesRead bytes handed to OCR, summed over the images read.
 * @property textChars characters in [ScanOutcome.analyzedText] - what actually reached the engine.
 * @property payloadReleased true when every byte array this ingest obtained was zeroed and dropped
 *   before the outcome was returned. Always true on this synchronous path; stated so a caller can
 *   show it and a test can pin it.
 * @property truncatedInput true when the analysed input was longer than the pipeline's window.
 * @property limitations honest Hinglish notes about partial coverage (truncation, blurry OCR). They
 *   never re-score anything, and an empty list means full coverage.
 */
data class ShareIngestReceipt(
    val inputType: InputType,
    val imagesRead: Int,
    val bytesRead: Long,
    val textChars: Int,
    val payloadReleased: Boolean,
    val truncatedInput: Boolean,
    val limitations: List<String> = emptyList(),
) {
    /** Redacted: counts and flags only. */
    override fun toString(): String =
        "ShareIngestReceipt(inputType=$inputType, imagesRead=$imagesRead, bytesRead=$bytesRead, " +
            "textChars=$textChars, payloadReleased=$payloadReleased, truncatedInput=$truncatedInput, " +
            "limitations=${limitations.size})"
}

/**
 * What the coordinator hands back. Exactly one of three things happened: the deterministic engine
 * produced a verdict, the input could not be judged (which the UI renders as **Unable to Verify**), or
 * the share was refused outright.
 *
 * [Unavailable] and [Refused] carry no [ScanOutcome] at all, so there is no score, no evidence and no
 * quote that could be mistaken for a verdict - and in particular nothing that could be read as "safe".
 */
sealed interface ShareIngestOutcome {

    /** The pipeline produced a verdict from this share. [receipt] proves coverage and clearing. */
    data class Analysed(val outcome: ScanOutcome, val receipt: ShareIngestReceipt) : ShareIngestOutcome {
        /** Redacted: the analysed text lives in [outcome] only, and is never printed. */
        override fun toString(): String =
            "ShareIngestOutcome.Analysed(score=${outcome.risk.score}, label=${outcome.risk.label}, " +
                "confidence=${outcome.risk.confidence}, status=${outcome.result.status}, receipt=$receipt)"
    }

    /** No verdict could be produced; [limitations] is non-empty Hinglish copy for the UI. */
    data class Unavailable(
        val reason: ShareIngestRefusal,
        val limitations: List<String>,
    ) : ShareIngestOutcome {
        init {
            require(limitations.isNotEmpty()) {
                "an unavailable share must say why in at least one limitation"
            }
        }

        override fun toString(): String =
            "ShareIngestOutcome.Unavailable(reason=$reason, limitations=${limitations.size})"
    }

    /** The share is not analysable at all. [detail] carries codes/sizes only, never content. */
    data class Refused(
        val reason: ShareIngestRefusal,
        val detail: String? = null,
    ) : ShareIngestOutcome {
        override fun toString(): String =
            "ShareIngestOutcome.Refused(reason=$reason, detail=${if (detail.isNullOrBlank()) "null" else "present"})"
    }
}

// =============================================================================================
// Copy
// =============================================================================================

/**
 * The Hinglish limitation lines this layer can attach to an outcome. One place, so a screen, a test
 * and a review see the same sentence, and so no copy can be assembled from payload text.
 */
object ShareIngestCopy {
    const val EMPTY_SHARE: String = "Is share mein check karne layak kuch nahi tha, isliye koi result nahi bana."
    const val OCR_MODULE_MISSING: String =
        "Is build mein image se text padhne ka module nahi hai. Text copy karke check karein."
    const val OCR_FAILED: String =
        "Image se text padha nahi ja saka, isliye koi risk nahi bataya gaya."
    const val OCR_NO_TEXT: String = "Image mein padhne layak text nahi mila."
    const val READ_FAILED: String =
        "Shared file padhi nahi ja saki. Dobara share karein ya text copy karke check karein."
    const val NO_BYTE_SOURCE: String = "Is share ki file tak padhne ka rasta nahi mila."
    const val NO_READ_GRANT: String = "App ko is file ko padhne ki permission nahi mili."
    const val TRUNCATED_INPUT: String =
        "Input bahut lamba tha, isliye sirf pehla hissa check hua."
    const val OCR_TRUNCATED: String =
        "Image ka text bahut lamba tha, isliye sirf pehla hissa check hua."
    const val LOW_OCR_CONFIDENCE: String =
        "Image ka text dhundhla padha gaya. Isse risk nahi badalta, sirf confidence kam hota hai."
}

// =============================================================================================
// Coordinator
// =============================================================================================

/**
 * Carries a shared text or image into the one local pipeline, under one shared byte budget, and
 * clears every byte it touched before returning.
 *
 * ```kotlin
 * val coordinator = ShareIngestCoordinator()                       // honest no-OCR default
 * when (val result = coordinator.ingestShare(ShareIntentReader(context).read(intent))) {
 *     is ShareIngestOutcome.Analysed -> showResult(result.outcome)   // same screen as typed text
 *     is ShareIngestOutcome.Unavailable -> showUnableToVerify(result.reason, result.limitations)
 *     is ShareIngestOutcome.Refused -> showRefusal(result.reason)
 * }
 * ```
 *
 * @param pipeline the deterministic engine (A05). Shared by the scan screen so typed, pasted and
 *   shared input are judged by one instance; serialise calls on it.
 * @param textSource the OCR seam. Defaults to [NoShareImageTextSource], which refuses honestly.
 * @param maxBytes the whole-share byte ceiling; one [ByteBudget] of this size is created per ingest.
 * @param byteSourceFor optional factory that turns a [ShareImageRef] from a [ShareIntentRead] into a
 *   bounded [ShareByteSource]. The wiring layer passes the `content://` adapter here; when it is null
 *   an image share that arrived as an intent is reported as [ShareIngestRefusal.READ_FAILED] rather
 *   than guessed at.
 */
class ShareIngestCoordinator(
    private val pipeline: LocalScanPipeline = LocalScanPipeline(),
    private val textSource: ShareImageTextSource = NoShareImageTextSource,
    private val maxBytes: Long = ShareBounds.MAX_TOTAL_BYTES,
    private val byteSourceFor: ((ShareImageRef) -> ShareByteSource?)? = null,
) {

    /**
     * Analyses one share. Never throws for a malformed or hostile payload: a read that fails, a seam
     * that throws and bytes that do not fit all become an honest [ShareIngestOutcome] instead.
     */
    fun ingest(request: ShareIngestRequest): ShareIngestOutcome = when (request) {
        is ShareIngestRequest.Text -> ingestText(request.text)
        is ShareIngestRequest.Image -> ingestImages(listOf(request.source to request.mime))
        is ShareIngestRequest.Images -> ingestImages(request.items)
    }

    /**
     * Analyses the result of P3-07's intent reader - the path the app actually uses.
     *
     * Refused reads are mapped onto [ShareIngestRefusal] without touching a byte; an accepted share
     * still in [ReviewState.Pending] is refused with [ShareIngestRefusal.REVIEW_NOT_ACCEPTED], because
     * F01 requires explicit user review before any share is read; an accepted text share goes through
     * the text path; an accepted image share needs [byteSourceFor] and a read grant.
     */
    fun ingestShare(read: ShareIntentRead): ShareIngestOutcome = when (read) {
        is ShareIntentRead.Refused -> ShareIngestOutcome.Refused(
            reason = refusalFor(read),
            detail = "reason=${read.reason?.code ?: read.emptyReason?.code}",
        )

        is ShareIntentRead.Accepted -> ingestAccepted(read)
    }

    // ------------------------------------------------------------------ text

    private fun ingestText(raw: String): ShareIngestOutcome {
        // No letter and no digit means there is nothing for the rules to read: blank text, emoji only
        // or punctuation only. Refused as empty rather than handed to the engine as a "verdict".
        if (raw.none { it.isLetterOrDigit() }) {
            return ShareIngestOutcome.Unavailable(
                reason = ShareIngestRefusal.EMPTY_CONTENT,
                limitations = listOf(ShareIngestCopy.EMPTY_SHARE),
            )
        }

        val outcome = pipeline.analyzeText(raw, InputType.TEXT)
        val limitations = ArrayList<String>(1)
        if (outcome.inputTruncated) limitations += ShareIngestCopy.TRUNCATED_INPUT

        return ShareIngestOutcome.Analysed(
            outcome = outcome,
            receipt = ShareIngestReceipt(
                inputType = InputType.TEXT,
                imagesRead = 0,
                bytesRead = 0L,
                textChars = outcome.analyzedText.length,
                payloadReleased = true,
                truncatedInput = outcome.inputTruncated,
                limitations = limitations.toList(),
            ),
        )
    }

    // ------------------------------------------------------------------ images

    /**
     * Reads, recognises and analyses every image, or refuses/withholds the whole share.
     *
     * The budget is created once here and passed to every source, so the ceiling is per share, not per
     * image. A share with an unreadable or unrecognisable image is reported as a whole: analysing the
     * readable half would present partial coverage as a full verdict.
     */
    private fun ingestImages(items: List<Pair<ShareByteSource, String>>): ShareIngestOutcome {
        if (items.isEmpty()) {
            return ShareIngestOutcome.Unavailable(
                reason = ShareIngestRefusal.EMPTY_CONTENT,
                limitations = listOf(ShareIngestCopy.EMPTY_SHARE),
            )
        }
        if (items.size > ShareBounds.MAX_IMAGES) {
            return ShareIngestOutcome.Refused(
                reason = ShareIngestRefusal.TOO_MANY_IMAGES,
                detail = "count=${items.size} max=${ShareBounds.MAX_IMAGES}",
            )
        }

        val budget = ByteBudget(maxBytes)
        val texts = ArrayList<String>(items.size)
        val limitations = ArrayList<String>(2)
        var bytesRead = 0L

        for ((source, declaredMime) in items) {
            val mime = ShareBounds.normaliseMime(declaredMime)
            if (mime == null || mime !in ShareBounds.IMAGE_MIME_ALLOWLIST) {
                return ShareIngestOutcome.Refused(
                    reason = ShareIngestRefusal.UNSUPPORTED_MIME,
                    detail = "allowed=${ShareBounds.IMAGE_MIME_ALLOWLIST.joinToString(",")}",
                )
            }

            val remaining = budget.remaining
            if (remaining <= 0L) {
                return ShareIngestOutcome.Refused(
                    reason = ShareIngestRefusal.BYTE_BUDGET_EXCEEDED,
                    detail = "consumed=${budget.consumed} limit=$maxBytes",
                )
            }

            // Declared size decides before a byte is touched: an oversized image is never half-read.
            val declared = source.sizeBytes
            if (declared != null) {
                if (declared > maxBytes) {
                    return ShareIngestOutcome.Refused(
                        reason = ShareIngestRefusal.IMAGE_TOO_LARGE,
                        detail = "declared=$declared limit=$maxBytes",
                    )
                }
                if (declared > remaining) {
                    return ShareIngestOutcome.Refused(
                        reason = ShareIngestRefusal.BYTE_BUDGET_EXCEEDED,
                        detail = "declared=$declared remaining=$remaining",
                    )
                }
            }

            val buffer = try {
                source.readAtMost(remaining.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), budget)
            } catch (exceeded: ShareBoundExceeded) {
                return ShareIngestOutcome.Refused(
                    reason = ShareIngestRefusal.BYTE_BUDGET_EXCEEDED,
                    detail = "read=${exceeded.read} remaining=${exceeded.remaining} limit=${exceeded.limit}",
                )
            } catch (failed: Exception) {
                // A provider that died or refused: no bytes, no message content, no partial result.
                return ShareIngestOutcome.Unavailable(
                    reason = ShareIngestRefusal.READ_FAILED,
                    limitations = listOf(ShareIngestCopy.READ_FAILED),
                )
            }

            try {
                if (buffer.size.toLong() > remaining) {
                    // A source that ignored the bound must not be trusted with this share.
                    return ShareIngestOutcome.Refused(
                        reason = ShareIngestRefusal.BYTE_BUDGET_EXCEEDED,
                        detail = "read=${buffer.size} remaining=$remaining",
                    )
                }

                val recognised = try {
                    textSource.recognize(buffer, mime)
                } catch (failed: Exception) {
                    ShareImageText.Unavailable("ocr_failed")
                }

                when (recognised) {
                    is ShareImageText.Unavailable -> return unavailableFor(recognised.reason)
                    is ShareImageText.Text -> {
                        val text = recognised.text.trim()
                        if (text.none { it.isLetterOrDigit() }) {
                            return ShareIngestOutcome.Unavailable(
                                reason = ShareIngestRefusal.OCR_FAILED,
                                limitations = listOf(ShareIngestCopy.OCR_NO_TEXT),
                            )
                        }
                        texts += text
                        bytesRead += buffer.size.toLong()
                        if (recognised.truncated && ShareIngestCopy.OCR_TRUNCATED !in limitations) {
                            limitations += ShareIngestCopy.OCR_TRUNCATED
                        }
                        val mean = recognised.meanConfidence
                        if (mean != null && mean < LOW_OCR_CONFIDENCE_THRESHOLD &&
                            ShareIngestCopy.LOW_OCR_CONFIDENCE !in limitations
                        ) {
                            limitations += ShareIngestCopy.LOW_OCR_CONFIDENCE
                        }
                    }
                }
            } finally {
                // Every byte array this class obtained, cleared on the success and every failure path.
                Arrays.fill(buffer, 0)
            }
        }

        // Several images are one document: joined in the order they arrived, so the same share always
        // produces the same input. Each fragment is already trimmed, and no fragment is dropped.
        val merged = texts.joinToString("\n")
        val outcome = pipeline.analyzeImageText(merged)
        if (outcome.inputTruncated && ShareIngestCopy.TRUNCATED_INPUT !in limitations) {
            limitations += ShareIngestCopy.TRUNCATED_INPUT
        }

        return ShareIngestOutcome.Analysed(
            outcome = outcome,
            receipt = ShareIngestReceipt(
                inputType = InputType.IMAGE,
                imagesRead = texts.size,
                bytesRead = bytesRead,
                textChars = outcome.analyzedText.length,
                payloadReleased = true,
                truncatedInput = outcome.inputTruncated,
                limitations = limitations.toList(),
            ),
        )
    }

    // ------------------------------------------------------------------ intent reads

    private fun ingestAccepted(read: ShareIntentRead.Accepted): ShareIngestOutcome {
        val share = read.share
        // F01: nothing may be read for analysis until the user has accepted the review.
        if (share.reviewState != ReviewState.Accepted) {
            return ShareIngestOutcome.Refused(
                reason = ShareIngestRefusal.REVIEW_NOT_ACCEPTED,
                detail = "review=${share.reviewState.name}",
            )
        }

        val content = share.content
        return when (content) {
            is SharedContent.Images -> ingestImageRefs(listOf(content.image), read.grant)
            is SharedContent.MultipleImages -> ingestImageRefs(content.images, read.grant)
            is SharedContent.Empty -> ShareIngestOutcome.Unavailable(
                reason = ShareIngestRefusal.EMPTY_CONTENT,
                limitations = listOf(ShareIngestCopy.EMPTY_SHARE),
            )
            is SharedContent.Rejected -> ShareIngestOutcome.Refused(
                reason = refusalFor(content.reason),
                detail = content.detail,
            )
            else -> ingestText(content.analysableText)
        }
    }

    /**
     * Turns the accepted image references into bounded sources through [byteSourceFor], then runs the
     * normal image path so the budget and the clearing rules are shared with a direct request.
     */
    private fun ingestImageRefs(refs: List<ShareImageRef>, grant: ShareUriGrant): ShareIngestOutcome {
        if (refs.isEmpty()) {
            return ShareIngestOutcome.Unavailable(
                reason = ShareIngestRefusal.EMPTY_CONTENT,
                limitations = listOf(ShareIngestCopy.EMPTY_SHARE),
            )
        }
        if (grant == ShareUriGrant.None) {
            // P3-07 already refuses this case; refusing again is cheaper than probing a URI we may
            // not read, and it keeps the guarantee independent of the caller.
            return ShareIngestOutcome.Unavailable(
                reason = ShareIngestRefusal.READ_FAILED,
                limitations = listOf(ShareIngestCopy.NO_READ_GRANT),
            )
        }
        val factory = byteSourceFor ?: return ShareIngestOutcome.Unavailable(
            reason = ShareIngestRefusal.READ_FAILED,
            limitations = listOf(ShareIngestCopy.NO_BYTE_SOURCE),
        )

        val items = ArrayList<Pair<ShareByteSource, String>>(refs.size)
        for (ref in refs) {
            val source = factory(ref) ?: return ShareIngestOutcome.Unavailable(
                reason = ShareIngestRefusal.READ_FAILED,
                limitations = listOf(ShareIngestCopy.NO_BYTE_SOURCE),
            )
            items += source to ref.mime
        }
        return ingestImages(items)
    }

    // ------------------------------------------------------------------ mapping

    private fun unavailableFor(reason: String): ShareIngestOutcome.Unavailable =
        if (reason == NoShareImageTextSource.REASON) {
            ShareIngestOutcome.Unavailable(
                reason = ShareIngestRefusal.OCR_UNAVAILABLE,
                limitations = listOf(ShareIngestCopy.OCR_MODULE_MISSING),
            )
        } else {
            ShareIngestOutcome.Unavailable(
                reason = ShareIngestRefusal.OCR_FAILED,
                limitations = listOf(ShareIngestCopy.OCR_FAILED),
            )
        }

    private fun refusalFor(read: ShareIntentRead.Refused): ShareIngestRefusal {
        read.reason?.let { return refusalFor(it) }
        return when (read.emptyReason) {
            ShareEmptyReason.NO_ACTION, ShareEmptyReason.UNSUPPORTED_ACTION ->
                ShareIngestRefusal.CLOSED_BY_DESIGN

            ShareEmptyReason.NO_CONTENT, ShareEmptyReason.BLANK_TEXT, ShareEmptyReason.DISCARDED ->
                ShareIngestRefusal.EMPTY_CONTENT

            null -> ShareIngestRefusal.EMPTY_CONTENT
        }
    }

    private fun refusalFor(reason: ShareRejectReason): ShareIngestRefusal = when (reason) {
        // Not a message this app analyses at all - a deliberate product boundary, not a failure.
        ShareRejectReason.APK_ARCHIVE, ShareRejectReason.HTML_DOCUMENT -> ShareIngestRefusal.CLOSED_BY_DESIGN
        ShareRejectReason.IMAGE_TYPE_NOT_ALLOWED, ShareRejectReason.DECLARED_IMAGE_NOT_IMAGE ->
            ShareIngestRefusal.UNSUPPORTED_MIME

        ShareRejectReason.TOO_MANY_IMAGES -> ShareIngestRefusal.TOO_MANY_IMAGES
        ShareRejectReason.OVERSIZED -> ShareIngestRefusal.BYTE_BUDGET_EXCEEDED
        ShareRejectReason.NO_URI, ShareRejectReason.UNOPENABLE_URI -> ShareIngestRefusal.READ_FAILED
    }

    companion object {
        /**
         * Below this mean confidence the image text is disclosed as blurry. It changes nothing about
         * the risk score - the limitation only says the reading was poor, and A05 already lowers
         * confidence on its own terms.
         */
        const val LOW_OCR_CONFIDENCE_THRESHOLD: Double = 0.5
    }
}
