/*
 * TrustShield Android - P3-05 (issue #84): image selection and the ephemeral OCR handoff.
 *
 * SCOPE
 *   Let the user pick a photo (Android photo picker) or hand in an image that was shared TO the app
 *   (an ACTION_SEND / ACTION_SEND_MULTIPLE URI grant relayed by the navigation layer), run it through
 *   the B07 bounds, through the bundled OCR from P3-04, and into the SAME offline pipeline as typed
 *   text (P3-02 `LocalScanPipeline.analyzeImageText`). After the scan the in-memory image data is
 *   zeroed and nothing about the pick survives in this controller.
 *
 * WHAT THIS FILE DOES NOT DO (and must never be changed to do)
 *   * It never opens, resolves, follows or launches a URI as a document, an app, an activity or a URL.
 *     The only URI it accepts is a `content://` grant, and the only thing done with it is a bounded
 *     byte read through [ai.trustshield.android.input.share.ByteSource].
 *   * It never writes a file, a cache entry, a thumbnail, a preference, a database row or a history
 *     entry. There is no `File`, no `Context.cacheDir`, no `Bitmap.compress`, no `SharedPreferences`.
 *   * It never logs or prints the raw URI, the image bytes or the recognised text. There is no
 *     `android.util.Log`, no `println`, no analytics call. Every `toString()` in this file is redacted
 *     on purpose, including the state object's, because a data class `toString()` is exactly how a
 *     crash reporter would otherwise capture the OCR text.
 *   * It never claims a photo is "safe". An unreadable or unverifiable image becomes
 *     [ImageScanError] + `Unable to Verify`, never a green result.
 *
 * STRUCTURE (same pattern as B01/B07: a pure core with a thin Android shell in one file)
 *   1. Pure Kotlin state machine - [ImageScanPhase], [ImageScanError], [ImagePreview],
 *      [ImageScanState], [ImageScanEvent], [reduce]. No Compose, no framework object, JVM-testable.
 *   2. [OcrRecognizer] + [ImageScanController] + the `scan` extension - the real bounded pipeline.
 *      Still pure Kotlin: the OCR implementation and the byte source are injected.
 *   3. `@Composable rememberImageScanLauncher` - the only Android/Compose code here: the photo picker
 *      and the inbound-share grant, plus grant hygiene. It is a thin shell by design; the workflow it
 *      drives is entirely in (1) and (2).
 *
 * WHY [OcrRecognizer] IS DECLARED HERE INSTEAD OF USING `MlKitTextRecognizer` DIRECTLY
 *   P3-04's recogniser imports `android.graphics.Bitmap`, ML Kit clients and the GMS task API. If the
 *   controller depended on that class, every unit test of the state machine and of the pipeline wiring
 *   would need an Android runtime (and would then be testing ML Kit, not our logic). Declaring a
 *   one-method interface here - the narrowest thing the controller actually needs (`bytes` in, one
 *   [OcrOutcome] out) - keeps the whole workflow on the JVM with fakes, and keeps the concrete OCR
 *   engine a detail of the navigation package that knows the Context:
 *
 *     val ocr = MlKitTextRecognizer()
 *     val controller = ImageScanController(
 *         pipeline = LocalScanPipeline(),
 *         recognizer = OcrRecognizer { bytes, mime -> ocr.recognize(bytes, mime) },
 *         readBytes = { raw ->
 *             if (isContentUri(raw)) ContentResolverByteSource(context.contentResolver, Uri.parse(raw)) else null
 *         },
 *     )
 *
 *   The adapter is one lambda because [OcrRecognizer] is a `fun interface` whose single method matches
 *   `MlKitTextRecognizer.recognize(ByteArray, String?)` exactly. Closing the recogniser stays the
 *   caller's job (it owns the ML Kit clients).
 */
package ai.trustshield.android.ui.home

import ai.trustshield.android.core.analysis.LocalScanPipeline
import ai.trustshield.android.core.analysis.ScanOutcome
import ai.trustshield.android.core.model.RiskLabel
import ai.trustshield.android.input.share.ByteRead
import ai.trustshield.android.input.share.ByteSource
import ai.trustshield.android.input.share.ImagePolicy
import ai.trustshield.android.input.share.ImageReason
import ai.trustshield.android.input.share.OcrFailure
import ai.trustshield.android.input.share.OcrOutcome
import ai.trustshield.android.input.share.OcrScript
import ai.trustshield.android.input.share.SharedImageReader
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Process
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

// =================================================================================================
// 1. Pure state machine (no android.*, no Compose, fully JVM-testable)
// =================================================================================================

/**
 * Where one image scan is. The scan is short (read, OCR, analyse), so these are the only phases the
 * screen ever has to draw, and every one of them is reachable from a single user action.
 *
 * [DONE] and [FAILED] are the terminal phases. [DONE] with a non-null [ImageScanState.error] means
 * "there is a result to show, but it is not a verdict you may read as safe" - see [reduce].
 */
enum class ImageScanPhase {
    /** Nothing picked yet. */
    IDLE,

    /** Reading the granted bytes within the B07 bound. */
    READING,

    /** Bounds passed; the bundled OCR is running on the bytes. */
    OCR,

    /** The local deterministic pipeline is judging the recognised text. */
    ANALYSING,

    /** A result is available (and possibly an [ImageScanState.error] saying it is unverifiable). */
    DONE,

    /** No result: the image or the reader was refused. [ImageScanState.error] says why. */
    FAILED,
}

/**
 * Every way an image scan can fail to produce a trustworthy result.
 *
 * Copy rules (`docs/android/UI_SPEC.md`, HEAD_ORDERS §2 rules 6-7): simple Hinglish by default with an
 * English twin for the toggle, a short "what to do next" line so the state is never a dead end, and
 * absolute safety is never claimed. A refusal here says only "I could not read this", never "this photo
 * is safe".
 *
 * @property code stable machine identifier for tests/support; never shown to the user.
 * @property messageHinglish what went wrong, Hinglish.
 * @property messageEnglish the same statement in English.
 * @property nextStepHinglish the safest next action, Hinglish.
 * @property nextStepEnglish the safest next action, English.
 */
enum class ImageScanError(
    val code: String,
    val messageHinglish: String,
    val messageEnglish: String,
    val nextStepHinglish: String,
    val nextStepEnglish: String,
) {
    /** No readable grant, or the picker/provider could not be opened. */
    URI_UNAVAILABLE(
        "uri_unavailable",
        "Yeh photo kholi nahi ja saki.",
        "That photo could not be opened.",
        "Dobara photo chunein ya screenshot share karein.",
        "Pick the photo again, or share the screenshot directly.",
    ),

    /** A container B07 does not handle, or the sender's MIME claim contradicts the bytes. */
    UNSUPPORTED_TYPE(
        "unsupported_type",
        "Yeh file type support nahi hai.",
        "That file type is not supported.",
        "PNG, JPEG ya WebP screenshot bhejein.",
        "Send a PNG, JPEG or WebP screenshot.",
    ),

    /** Over the byte, dimension or pixel bound, so nothing was decoded. */
    TOO_LARGE(
        "too_large",
        "Photo bahut badi hai, isliye check nahi kar paye.",
        "That image is too large, so it was not checked.",
        "Chhota screenshot bhejein (8 MB tak, 10 000 pixel se chhota).",
        "Send a smaller screenshot (up to 8 MB, under 10 000 px).",
    ),

    /** Signature matched but the image is truncated/damaged, or the platform decoder refused it. */
    CORRUPT_IMAGE(
        "corrupt_image",
        "Photo adhoori ya kharab hai.",
        "That image is incomplete or damaged.",
        "Screenshot dobara lein ya doosri photo bhejein.",
        "Take the screenshot again, or send a different photo.",
    ),

    /** OCR ran and found nothing worth reading. This is the UI_SPEC copy for a bad read. */
    NO_TEXT(
        "no_text",
        "Is photo mein padhne layak text nahi mila.",
        "No readable text was found in this image.",
        "Saaf screenshot bhejein jismein poora message dikhe.",
        "Send a clean screenshot where the whole message is visible.",
    ),

    /** The bundled on-device recogniser could not run at all on this device/state. */
    OCR_UNAVAILABLE(
        "ocr_unavailable",
        "Is device par text padhne wala engine abhi kaam nahi kar raha.",
        "The on-device text reader is not available right now.",
        "Text copy karke paste karein, ya app dobara kholein.",
        "Paste the text instead, or reopen the app and try again.",
    ),

    /**
     * The image was read and analysed, but the deterministic engine could not verify what it says
     * (emoji-only, unsupported script, unreadable recognition). The outcome is still returned for
     * display - it is just never rendered as "safe".
     */
    ANALYSIS_UNABLE_TO_VERIFY(
        "analysis_unable_to_verify",
        "Photo padhi gayi, par is text ka pakka check nahi ho paya.",
        "The image was read, but this text could not be verified.",
        "Text copy karke paste karein, aur bina verify kiye koi link ya OTP na maanein.",
        "Paste the text instead, and do not act on any link or OTP you cannot verify.",
    ),
    ;

    /** Copy for the language the user chose (Hinglish is the default, never the fallback). */
    fun message(english: Boolean): String = if (english) messageEnglish else messageHinglish

    /** "What to do next" for the language the user chose. */
    fun nextStep(english: Boolean): String = if (english) nextStepEnglish else nextStepHinglish

    /** Diagnostics stay content-free, so the printed value is the stable code. */
    override fun toString(): String = "ImageScanError($code)"
}

/**
 * Redacted labels for where an image came from.
 *
 * The real URI is never carried by [ImagePreview]: it can name the sender's provider, a chat thread or
 * an item id, none of which belongs in a state object a crash reporter may serialise.
 */
object ImageSourceLabel {
    /** What any `content://` pick is labelled as. */
    const val CONTENT: String = "content://**"

    /** Anything that is not a URI this flow would accept. */
    const val UNKNOWN: String = "**"

    /**
     * Reduces [uriString] to a scheme-only label. Idempotent: a label that already went through this
     * function comes out unchanged, so [ImagePreview.toString] can call it defensively.
     */
    fun redact(uriString: String): String {
        val scheme = uriString.substringBefore("://", missingDelimiterValue = "")
        return when {
            scheme == "content" -> CONTENT
            scheme.isNotEmpty() && scheme.all { it.isLetter() } -> "$scheme://**"
            else -> UNKNOWN
        }
    }
}

/**
 * What the review screen shows about the image it is about to care about: real dimensions and size from
 * the B07 header read, plus a redacted source label. No bytes, no thumbnail, no URI, no text.
 *
 * [toString] re-redacts [source], so a caller that mistakenly constructs this with a raw URI still
 * cannot leak it into a log line.
 */
data class ImagePreview(
    val width: Int,
    val height: Int,
    val byteCount: Long,
    /** A label from [ImageSourceLabel], never a real URI. */
    val source: String,
) {
    init {
        require(width >= 0 && height >= 0) { "preview dimensions must not be negative" }
        require(byteCount >= 0L) { "preview byte count must not be negative" }
        require(source.isNotBlank()) { "preview source label must not be blank" }
    }

    override fun toString(): String =
        "ImagePreview(width=$width, height=$height, byteCount=$byteCount, " +
            "source=${ImageSourceLabel.redact(source)})"
}

/**
 * Everything the image flow may show, and nothing it may keep.
 *
 * @property phase where the scan is.
 * @property preview size/dimension metadata of the accepted image (null before bounds pass).
 * @property outcome the deterministic result, once the pipeline has run. This is the only carrier of
 *   recognised text ([ScanOutcome.analyzedText]) the UI may use, and only for rendering the quotes the
 *   engine itself verified.
 * @property error why the image was refused, or that the outcome could not be verified.
 * @property imageDataCleared true once the bytes this controller read have been zeroed. Set by
 *   [ImageScanEvent.ClearRequested], which `scan` always sends before returning.
 *
 * [toString] is overridden because this is a data class: the generated one would print
 * [ScanOutcome.analyzedText] - the recognised text - into any crash report or debugger dump.
 */
data class ImageScanState(
    val phase: ImageScanPhase = ImageScanPhase.IDLE,
    val preview: ImagePreview? = null,
    val outcome: ScanOutcome? = null,
    val error: ImageScanError? = null,
    val imageDataCleared: Boolean = false,
) {
    /** True while the controller is working; the screen must not start a second scan. */
    val isBusy: Boolean
        get() = phase == ImageScanPhase.READING ||
            phase == ImageScanPhase.OCR ||
            phase == ImageScanPhase.ANALYSING

    /**
     * True when offering "try another photo" is honest: the previous attempt finished, either way.
     * Never true while busy - a retry then would race the running scan and hold two copies of bytes.
     */
    val canRetry: Boolean
        get() = !isBusy &&
            (phase == ImageScanPhase.DONE || phase == ImageScanPhase.FAILED)

    override fun toString(): String = buildString {
        append("ImageScanState(phase=").append(phase)
        append(", error=").append(error?.code ?: "none")
        append(", cleared=").append(imageDataCleared)
        append(", preview=").append(preview?.toString() ?: "none")
        append(", outcome=")
        append(
            outcome?.let {
                "${it.risk.label.wire}/${it.risk.confidence.wire}/${it.result.evidence.size} evidence"
            } ?: "none",
        )
        append(')')
    }
}

/**
 * The seven things that can happen to one image scan. [reduce] is total over this set: every event is
 * defined from every phase, so a late or repeated event can never throw.
 */
sealed interface ImageScanEvent {
    /** A new scan starts. Everything from the previous attempt is dropped. */
    data object Started : ImageScanEvent

    /** B07 accepted the bytes; [preview] is the metadata the review screen may show. */
    data class BoundsAccepted(val preview: ImagePreview) : ImageScanEvent

    /** The image (or its grant) was refused before/during the bounds read. */
    data class Rejected(val error: ImageScanError) : ImageScanEvent

    /**
     * OCR produced a reading.
     *
     * [text] and [script] are carried so the reducer can validate the handoff, and they are
     * deliberately NOT stored on [ImageScanState]: the only text that survives the scan is
     * [ScanOutcome.analyzedText], which the pipeline produced and which the result screen needs for
     * quote verification.
     */
    data class OcrDone(val text: String, val script: OcrScript) : ImageScanEvent

    /** OCR refused: no text, or no recogniser. */
    data class OcrFailed(val error: ImageScanError) : ImageScanEvent

    /** The deterministic engine finished. */
    data class Analysed(val outcome: ScanOutcome) : ImageScanEvent

    /** The image bytes are gone. Only the "data was cleared" flag changes. */
    data object ClearRequested : ImageScanEvent
}

/**
 * The whole transition table, pure and total.
 *
 * | event            | phase     | preview | outcome | error                               | cleared |
 * |------------------|-----------|---------|---------|-------------------------------------|---------|
 * | `Started`        | READING   | dropped | dropped | dropped                             | false   |
 * | `BoundsAccepted` | OCR       | set     | dropped | cleared                             | false   |
 * | `Rejected`       | FAILED    | kept    | dropped | set                                 | false   |
 * | `OcrDone`        | ANALYSING | kept    | kept    | cleared                             | false   |
 * | `OcrFailed`      | FAILED    | kept    | dropped | set                                 | false   |
 * | `Analysed`       | DONE      | kept    | set     | `ANALYSIS_UNABLE_TO_VERIFY` or none | false   |
 * | `ClearRequested` | kept      | kept    | kept    | kept                                | true    |
 *
 * Notes that are behavioural, not cosmetic:
 *  * every non-clear event resets `imageDataCleared = false`, because bytes exist again from the moment
 *    they are read until [ImageScanEvent.ClearRequested] arrives;
 *  * `OcrDone` with a blank reading is refused as [ImageScanError.NO_TEXT] instead of being analysed -
 *    the pipeline would answer `UNABLE_TO_VERIFY`, but "nothing was read" is the more honest label;
 *  * an `UNABLE_TO_VERIFY` outcome keeps its phase at [ImageScanPhase.DONE] **and** sets
 *    [ImageScanState.error], so a screen keyed on the error can never fall through to the green tone.
 */
fun reduce(state: ImageScanState, event: ImageScanEvent): ImageScanState = when (event) {
    ImageScanEvent.Started -> ImageScanState(phase = ImageScanPhase.READING)

    is ImageScanEvent.BoundsAccepted -> state.copy(
        phase = ImageScanPhase.OCR,
        preview = event.preview,
        outcome = null,
        error = null,
        imageDataCleared = false,
    )

    is ImageScanEvent.Rejected -> state.copy(
        phase = ImageScanPhase.FAILED,
        outcome = null,
        error = event.error,
        imageDataCleared = false,
    )

    is ImageScanEvent.OcrDone ->
        if (event.text.isBlank()) {
            state.copy(
                phase = ImageScanPhase.FAILED,
                outcome = null,
                error = ImageScanError.NO_TEXT,
                imageDataCleared = false,
            )
        } else {
            state.copy(phase = ImageScanPhase.ANALYSING, error = null, imageDataCleared = false)
        }

    is ImageScanEvent.OcrFailed -> state.copy(
        phase = ImageScanPhase.FAILED,
        outcome = null,
        error = event.error,
        imageDataCleared = false,
    )

    is ImageScanEvent.Analysed -> state.copy(
        phase = ImageScanPhase.DONE,
        outcome = event.outcome,
        error = if (event.outcome.risk.label == RiskLabel.UNABLE_TO_VERIFY) {
            ImageScanError.ANALYSIS_UNABLE_TO_VERIFY
        } else {
            null
        },
        imageDataCleared = false,
    )

    ImageScanEvent.ClearRequested -> state.copy(imageDataCleared = true)
}

/**
 * True only for a `content://` grant. Everything else - `file:`, `http(s):`, `intent:`, a bare path -
 * is refused before any byte is touched, which is how this flow stays on the right side of "never open,
 * resolve or follow a URI as a document/app/URL".
 *
 * Conservative on purpose: uppercase schemes and embedded whitespace/control characters are refused
 * rather than normalised, because a URI that needs cleaning up is not a URI this flow was handed.
 */
fun isContentUri(uriString: String): Boolean =
    uriString.startsWith("content://") &&
        uriString.length > "content://".length &&
        uriString.none { it.isWhitespace() || it.isISOControl() }

/** B07 refusal -> user-facing reason. Every branch is a refusal; none of them is a verdict. */
internal fun errorForImageReason(reason: ImageReason?): ImageScanError = when (reason) {
    ImageReason.TooLarge,
    ImageReason.TooManyPixels,
    ImageReason.TooManyImages,
    -> ImageScanError.TOO_LARGE

    ImageReason.UnsupportedType,
    ImageReason.MimeMismatch,
    -> ImageScanError.UNSUPPORTED_TYPE

    ImageReason.Corrupt,
    ImageReason.Empty,
    null,
    -> ImageScanError.CORRUPT_IMAGE
}

/** OCR refusal -> user-facing reason. `DECODE_FAILED` is a damaged/undecodable image, not a size issue. */
internal fun errorForOcrFailure(failure: OcrFailure): ImageScanError = when (failure) {
    OcrFailure.TOO_LARGE -> ImageScanError.TOO_LARGE
    OcrFailure.UNSUPPORTED_TYPE -> ImageScanError.UNSUPPORTED_TYPE
    OcrFailure.CORRUPT_IMAGE, OcrFailure.DECODE_FAILED -> ImageScanError.CORRUPT_IMAGE
    OcrFailure.NO_TEXT -> ImageScanError.NO_TEXT
    OcrFailure.MODEL_UNAVAILABLE -> ImageScanError.OCR_UNAVAILABLE
}

// =================================================================================================
// 2. The controller and the real bounded pipeline (still pure Kotlin)
// =================================================================================================

/**
 * The narrowest possible view of an OCR engine: bytes in, one [OcrOutcome] out.
 *
 * Declared here, next to the state machine, so that neither the reducer nor the controller needs an
 * Android runtime or the ML Kit types to be tested - see the file header for the full reasoning and for
 * the one-lambda adapter that connects P3-04's `MlKitTextRecognizer`.
 */
fun interface OcrRecognizer {
    /**
     * Recognises text in [bytes]. Implementations must be ephemeral: they may not write the bytes or the
     * text anywhere, log either, or keep a reference after returning.
     */
    suspend fun recognize(bytes: ByteArray, declaredMime: String?): OcrOutcome
}

/**
 * Drives one image from a URI grant to a [ScanOutcome], and forgets the image afterwards.
 *
 * ## What it keeps
 * Only [state]: a phase, image *metadata* (dimensions and size), the [ScanOutcome] the pipeline
 * produced, an error code and a "cleared" flag. No bytes, no bitmap, no URI string, no OCR text of its
 * own. [describeLastScan] is the diagnostic view and is content-free by construction.
 *
 * ## What it does with the bytes
 * Exactly one bounded read (`ImagePolicy.maxBytes + 1`, the B07 limit), one zero-fill of that buffer in
 * a `finally` block, and then nothing. The buffer is handed to [OcrRecognizer] un-copied, so this flow
 * holds one copy of the pixels, not two.
 *
 * ## Threading
 * [scan] is a `suspend` function with no blocking I/O of its own; the UI calls it from its own
 * coroutine. [LocalScanPipeline] serialises internal state, so one controller should drive one scan at
 * a time - [ImageScanState.isBusy] is what the screen checks.
 *
 * @param pipeline the merged offline engine (P3-02). Called once per accepted image, via
 *   `analyzeImageText`.
 * @param recognizer the OCR engine; `MlKitTextRecognizer` behind a lambda in production, a fake in tests.
 * @param policy the B07 bounds. Defaults to [ImagePolicy]'s 8 MiB / 40 MP / 10 000 px.
 * @param readBytes opens the grant as a bounded [ByteSource], or returns null when the URI cannot be
 *   read. This is where `ContentResolverByteSource` is injected; nothing else in this file knows what a
 *   `ContentResolver` is.
 */
class ImageScanController(
    internal val pipeline: LocalScanPipeline,
    internal val recognizer: OcrRecognizer,
    internal val policy: ImagePolicy = ImagePolicy(),
    internal val readBytes: (String) -> ByteSource?,
) {
    /** B07 reader over [policy]. Internal so the file-level `scan` extension can use it. */
    internal val reader: SharedImageReader = SharedImageReader(policy)

    /**
     * Observer for the Compose shell: receives every state, including the intermediate phases.
     *
     * States never carry the recognised text textually - only inside [ImageScanState.outcome], whose
     * text field this class never prints. Set it from the screen, clear it on dispose.
     */
    var onState: ((ImageScanState) -> Unit)? = null

    /** The current state. Read-only outside this class: only `scan` and [dispatch] move it. */
    var state: ImageScanState = ImageScanState()
        private set

    /**
     * Applies one event through [reduce] and publishes the result to [onState].
     *
     * Exposed for the shell (a picker that could not launch reports itself this way). Never call it with
     * an [ImageScanEvent.Analysed] that did not come from the pipeline.
     */
    internal fun dispatch(event: ImageScanEvent): ImageScanState {
        val next = reduce(state, event)
        state = next
        onState?.invoke(next)
        return next
    }

    /**
     * A redacted, single-line description of the last scan for diagnostics, support and tests.
     *
     * Deliberately omits [ImagePreview.source] (even a redacted label is a URI shape), the recognised
     * text and [ScanOutcome.analyzedText]. This method must stay safe to hand to a crash reporter or a
     * developer log.
     */
    fun describeLastScan(): String = buildString {
        val current = state
        append("ImageScan(phase=").append(current.phase.name)
        append(", error=").append(current.error?.code ?: "none")
        append(", cleared=").append(current.imageDataCleared)
        current.preview?.let { preview ->
            append(", image=").append(preview.width).append('x').append(preview.height)
            append(", bytes=").append(preview.byteCount)
        }
        current.outcome?.let { outcome ->
            append(", risk=").append(outcome.risk.label.wire)
            append(", confidence=").append(outcome.risk.confidence.wire)
            append(", evidence=").append(outcome.result.evidence.size)
            append(", critical=").append(outcome.escalateCritical)
            append(", truncated=").append(outcome.inputTruncated)
        }
        append(')')
    }
}

/**
 * Reads a [ByteSource] once and keeps the buffer it produced, so the bytes that passed B07's bounds are
 * exactly the bytes OCR sees. [SharedImageReader.read] calls [ByteSource.read] exactly once; if that
 * ever changed, the last read is the one kept - and therefore the one zeroed.
 */
private class CapturingByteSource(private val delegate: ByteSource) : ByteSource {
    var captured: ByteArray? = null
        private set

    override fun read(maxBytes: Int): ByteRead {
        val read = delegate.read(maxBytes)
        captured = read.bytes
        return read
    }
}

/**
 * The real workflow: `content://` grant -> bounded read -> B07 bounds -> bundled OCR -> the same local
 * pipeline -> [ImageScanPhase.DONE], with the bytes zeroed and dropped on every exit path.
 *
 * Terminal behaviour, all of it tested:
 *  * a non-`content://` URI, an unreadable grant or a throwing byte source ->
 *    [ImageScanError.URI_UNAVAILABLE] (nothing is opened, nothing is logged);
 *  * a B07 refusal -> the mapped [ImageScanError], and OCR and the pipeline are never called;
 *  * OCR refusing -> the mapped [ImageScanError], pipeline never called;
 *  * OCR succeeding -> `pipeline.analyzeImageText(text)`; an `UNABLE_TO_VERIFY` verdict is returned as
 *    [ImageScanPhase.DONE] with [ImageScanError.ANALYSIS_UNABLE_TO_VERIFY] (result shown, never "safe");
 *  * in every case the `finally` block zero-fills the buffer it read, drops its reference, and sends
 *    [ImageScanEvent.ClearRequested], so [ImageScanState.imageDataCleared] is true on return.
 *
 * The URI string is a parameter only: it is not stored on the controller, not echoed into an error and
 * not put in [ImageScanState] - only [ImageSourceLabel.redact]'s scheme label survives.
 *
 * @param declaredMime the sender's claim, if any. Passed to B07 (which checks it and lets the detected
 *   container win) and to the recogniser; never trusted.
 */
suspend fun ImageScanController.scan(
    uriString: String,
    declaredMime: String? = null,
): ImageScanState {
    dispatch(ImageScanEvent.Started)

    // The only reference to the pixels. Nulled and zero-filled in the `finally` block.
    var held: ByteArray? = null

    // A local function so every exit path falls through the `finally` below: a `return` from inside
    // `try` would capture the state object from *before* the clear and hand the caller a state that
    // still claims the bytes are there.
    suspend fun run() {
        if (!isContentUri(uriString)) {
            dispatch(ImageScanEvent.Rejected(ImageScanError.URI_UNAVAILABLE))
            return
        }

        val source = try {
            readBytes(uriString)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            null
        }
        if (source == null) {
            dispatch(ImageScanEvent.Rejected(ImageScanError.URI_UNAVAILABLE))
            return
        }

        val capture = CapturingByteSource(source)
        val image = try {
            reader.read(capture, declaredMime)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            // A provider that dies mid-stream is an unavailable URI, not a corrupt image.
            null
        } finally {
            // Even a failed read may have produced (part of) a buffer; keep it so it gets zeroed.
            held = capture.captured
        }
        if (image == null) {
            dispatch(ImageScanEvent.Rejected(ImageScanError.URI_UNAVAILABLE))
            return
        }

        if (!image.accepted) {
            dispatch(ImageScanEvent.Rejected(errorForImageReason(image.reason)))
            return
        }

        dispatch(
            ImageScanEvent.BoundsAccepted(
                ImagePreview(
                    width = image.width,
                    height = image.height,
                    byteCount = image.byteCount,
                    source = ImageSourceLabel.redact(uriString),
                ),
            ),
        )

        val bytes = held
        if (bytes == null || bytes.isEmpty()) {
            dispatch(ImageScanEvent.Rejected(ImageScanError.URI_UNAVAILABLE))
            return
        }

        val reading = try {
            recognizer.recognize(bytes, declaredMime)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            // A recogniser that throws has produced no text: say so, never guess.
            OcrOutcome.Failed(OcrFailure.MODEL_UNAVAILABLE)
        }

        when (reading) {
            is OcrOutcome.Recognized -> {
                dispatch(ImageScanEvent.OcrDone(reading.result.text, reading.result.script))
                // The pipeline owns the verdict. `analyzedText` is what the result screen verifies its
                // quotes against; this controller keeps no copy of the reading itself.
                dispatch(ImageScanEvent.Analysed(pipeline.analyzeImageText(reading.result.text)))
            }

            is OcrOutcome.Failed ->
                dispatch(ImageScanEvent.OcrFailed(errorForOcrFailure(reading.failure)))
        }
    }

    try {
        run()
    } finally {
        val bytes = held
        held = null
        if (bytes != null) bytes.fill(0)
        dispatch(ImageScanEvent.ClearRequested)
    }

    return state
}

// =================================================================================================
// 3. Compose shell - the only Android/Compose code in this file
// =================================================================================================

/**
 * What the scan screen can ask the image flow to do. Two entry points, both of which end in
 * [ImageScanController.scan]:
 *
 *  * [launch] opens the system photo picker (no storage permission, no gallery browsing by us);
 *  * [acceptSharedUri] takes a `content://` grant that arrived through `ACTION_SEND` /
 *    `ACTION_SEND_MULTIPLE` and was relayed by the navigation layer.
 */
class ImageScanActions internal constructor(
    private val launchPicker: () -> Unit,
    private val accept: (String) -> Unit,
    /** Which language the screen is showing, so a share entry point can print matching copy. */
    val english: Boolean,
) {
    /** Opens the Android photo picker. Reports [ImageScanError.URI_UNAVAILABLE] if it cannot open. */
    fun launch(): Unit = launchPicker()

    /**
     * Handles one inbound shared-image URI grant. Anything that is not a readable `content://` grant is
     * refused as [ImageScanError.URI_UNAVAILABLE]; nothing is resolved or launched.
     */
    fun acceptSharedUri(uriString: String): Unit = accept(uriString)
}

/**
 * Wires the photo picker and the inbound share grant to [controller] and pushes every state to
 * [onState]. This is a remembered *action holder*, not a screen: it draws nothing.
 *
 * ```kotlin
 * val ocr = remember { MlKitTextRecognizer() }               // P3-04; closed in a DisposableEffect
 * val controller = remember {
 *     ImageScanController(
 *         pipeline = LocalScanPipeline(),
 *         recognizer = OcrRecognizer { bytes, mime -> ocr.recognize(bytes, mime) },
 *         readBytes = { raw ->
 *             if (isContentUri(raw)) ContentResolverByteSource(context.contentResolver, Uri.parse(raw)) else null
 *         },
 *     )
 * }
 * val imageScan = rememberImageScanLauncher(controller, english = english) { state -> /* render */ }
 * // imageScan.launch()          -> photo picker
 * // imageScan.acceptSharedUri(uriStringFromTheShareIntent)
 * ```
 *
 * Privacy behaviour, all of it intentional:
 *  * the picker is `PickVisualMedia` with `ImageOnly`, so the app receives a single read grant and never
 *    needs `READ_MEDIA_IMAGES`;
 *  * a persistable grant is taken **only** when the platform actually reports one, and it is released
 *    again as soon as the bytes have been read - the app does not keep access to a photo it discarded;
 *  * no `startActivity` for an inbound URI, no `openInputStream` here, no thumbnail/logo load and no
 *    pre-analysis rendering of the picked image: the first thing that happens to the bytes is the
 *    bounded read inside [ImageScanController.scan];
 *  * if the picker activity is missing, the state becomes [ImageScanError.URI_UNAVAILABLE] instead of a
 *    crash or a silent no-op.
 *
 * @param controller the flow owner; one per screen. Its [ImageScanController.onState] is set here for
 *   the lifetime of the composition and cleared on dispose.
 * @param english selects the copy language carried by the actions object (the screen renders
 *   `error.message(english)`).
 * @param onState receives every state, intermediate phases included.
 */
@Composable
fun rememberImageScanLauncher(
    controller: ImageScanController,
    english: Boolean = false,
    onState: (ImageScanState) -> Unit,
): ImageScanActions {
    val context: Context = LocalContext.current
    val scope = rememberCoroutineScope()

    DisposableEffect(controller, onState) {
        controller.onState = onState
        onDispose { if (controller.onState === onState) controller.onState = null }
    }

    fun startWithGrant(uriString: String) {
        if (!isContentUri(uriString)) {
            controller.dispatch(ImageScanEvent.Rejected(ImageScanError.URI_UNAVAILABLE))
            return
        }
        val uri = uriString.toUri()
        val tookGrant = persistReadGrant(context, uri)
        scope.launch {
            try {
                // `scan` zero-fills the bytes and publishes every phase through `controller.onState`;
                // the returned state is deliberately unused so this lambda cannot hold on to it.
                controller.scan(uriString)
            } finally {
                releaseReadGrant(context, uri, tookGrant)
            }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        // A null result means the user backed out of the picker: nothing happened, no error to show.
        if (uri != null) startWithGrant(uri.toString())
    }

    return ImageScanActions(
        launchPicker = {
            try {
                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            } catch (error: Exception) {
                // ActivityNotFoundException / IllegalStateException: this device has no usable picker.
                // Say so instead of pretending the pick did something.
                controller.dispatch(ImageScanEvent.Rejected(ImageScanError.URI_UNAVAILABLE))
            }
        },
        accept = { uriString -> startWithGrant(uriString) },
        english = english,
    )
}

/**
 * Takes a persistable read grant for [uri] **only if one was actually offered**.
 *
 * `takePersistableUriPermission` throws `SecurityException` when the intent did not carry
 * `FLAG_GRANT_PERSISTABLE_URI_PERMISSION`, and "assume we have it" is how that crash ships. So: ask the
 * platform whether the grant is there, take it, and report whether *this call* took it, so the caller
 * only ever releases a grant it created itself.
 *
 * @return true when this call took the persistable grant and therefore owns the release.
 */
private fun persistReadGrant(context: Context, uri: Uri): Boolean {
    val alreadyHeld = runCatching {
        context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
    }.getOrDefault(false)
    if (alreadyHeld) return false // someone else's grant: not ours to release

    val offered = runCatching {
        context.checkUriPermission(
            uri,
            Process.myPid(),
            Process.myUid(),
            Intent.FLAG_GRANT_READ_URI_PERMISSION,
        ) == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)
    if (!offered) return false

    return runCatching {
        context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        true
    }.getOrDefault(false)
}

/** Releases a grant taken by [persistReadGrant]. No-op when this flow did not take one. */
private fun releaseReadGrant(context: Context, uri: Uri, tookGrant: Boolean) {
    if (!tookGrant) return
    runCatching {
        context.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
