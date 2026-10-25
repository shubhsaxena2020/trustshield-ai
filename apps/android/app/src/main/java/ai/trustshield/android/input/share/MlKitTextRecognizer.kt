/*
 * TrustShield Android - P3-04 (issue #83): bundled Latin + Devanagari ML Kit OCR adapter.
 *
 * Product source: `docs/android/BEHAVIOR_SPEC.md` (an unreadable or unsupported input must lower
 * confidence instead of producing a guess), `docs/android/ARCHITECTURE.md` ("Share/Camera/Mic UI ->
 * bounded input adapters -> ..." - input adapters never launch, fetch or persist what they read) and
 * `docs/android/UI_SPEC.md` (bad OCR lowers confidence and says "saaf screenshot bhejein").
 *
 * ------------------------------------------------------------------------------------------------
 * PRIVACY CONTRACT (stated here because this is the first code in the app that turns pixels into text)
 * ------------------------------------------------------------------------------------------------
 *  1. The bitmap and the recognised text are EPHEMERAL. They live in memory for the duration of one
 *     call. Nothing here writes a file, a cache entry, a preference, a database row or a
 *     `ContentValues`; the only thing this class persists is nothing at all.
 *  2. Recognised text is NEVER logged. There is no `android.util.Log`, no Timber, no `println`, no
 *     `System.err` and no analytics call in this file. The name of a failure enum is the most any
 *     caller may learn from a log line, and this class does not even emit that.
 *  3. There is NO network use. The `com.google.mlkit:text-recognition` and
 *     `com.google.mlkit:text-recognition-devanagari` artifacts are the BUNDLED variants: the Latin and
 *     Devanagari models ship inside the APK, so OCR works in airplane mode with no model download. That
 *     is a property of the dependency, not a promise about any other ML Kit artifact.
 *  4. A bitmap supplied by the caller (`recognizeBitmap`) is NEVER recycled by this class - the caller
 *     owns it. A bitmap this class decodes itself from bytes (`recognize`) is recycled in a `finally`
 *     block, so a failure path cannot leak it either.
 *  5. B07 bounds are reused, not reinvented: `SharedImageReader` / `ImageHeaderSniffer` (issue #39,
 *     `ImagePolicy`: 8 MiB, 40 MP, 10 000 px max dimension, 5 images) validate the bytes BEFORE any
 *     pixel is decoded, and the same dimension/pixel limits are re-applied to a caller-supplied bitmap
 *     so the camera path cannot slip past them.
 *
 * ------------------------------------------------------------------------------------------------
 * SCRIPT DECISION (deterministic, no guessing, no language-detection library)
 * ------------------------------------------------------------------------------------------------
 * Both bundled recognisers run over the SAME bitmap - a single recogniser would simply return its own
 * script's gibberish for the other one. The merge rule lives in [OcrTextMerge] and is pure Kotlin, so
 * it is provable off-device:
 *
 *  - Count letters (not digits, not punctuation, not combining marks) per Unicode script with
 *    [OcrTextMerge.letterCount].
 *  - Sum each script across BOTH outputs. Devanagari total above Latin total means the image carries
 *    Devanagari, so the Devanagari output wins; otherwise the Latin output wins.
 *  - If neither output contains a single letter of either script (digits only), the longer reading wins
 *    and an exact tie goes to Latin - still deterministic, and still no invented content.
 *  - The winning text is classified OTHER if it contains letters outside Latin/Devanagari; otherwise
 *    it is LATIN / DEVANAGARI / MIXED by a 20% minority-share threshold, so one stray English word
 *    inside a Hindi sentence does not turn the whole result "MIXED".
 *  - `meanConfidence` is null unless ML Kit reports at least one positive element confidence; a mean is
 *    never estimated, defaulted to 1.0, or derived from the amount of text found.
 */
package ai.trustshield.android.input.share

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/** Which script the recognised text is actually written in. */
enum class OcrScript {
    LATIN,
    DEVANAGARI,

    /** Both scripts are present in a meaningful proportion (see [OcrTextMerge.classify]). */
    MIXED,

    /** The reading contains letters outside the two scripts this adapter supports. */
    OTHER,
}

/**
 * Why OCR did not produce text. Every value is an honest statement about a refusal; none of them mean
 * "the image is safe" or "the image is a scam".
 */
enum class OcrFailure {
    /** Real byte count over [ImagePolicy.maxBytes], or dimensions/pixels over the B07 limits. */
    TOO_LARGE,

    /** Not a container B07 accepts, or the sender's `image/<subtype>` claim contradicts the bytes. */
    UNSUPPORTED_TYPE,

    /** A recognised container whose header is truncated, self-inconsistent, or empty. */
    CORRUPT_IMAGE,

    /** The header was fine but the platform decoder produced no bitmap. */
    DECODE_FAILED,

    /** OCR ran and returned nothing usable (blank, or no letter or digit at all). */
    NO_TEXT,

    /**
     * The bundled recogniser could not run: client closed, model failed to initialise, or both
     * recognisers errored. Never reported when at least one recogniser returned text.
     */
    MODEL_UNAVAILABLE,
}

/**
 * One OCR reading.
 *
 * @param text the recognised text, bounded by [MlKitTextRecognizer.MAX_TEXT_CHARS]. In memory only.
 * @param script the script majority of [text] (see [OcrTextMerge.classify]).
 * @param blockCount ML Kit text blocks in the winning reading; 0 when the winning reading is unknown.
 * @param meanConfidence mean of the element confidences ML Kit actually reported, in `0.0..1.0`, or
 *   `null` when no element reported one. Never invented; never a substitute for a risk score.
 * @param truncated true when [text] was cut at [MlKitTextRecognizer.MAX_TEXT_CHARS].
 */
data class OcrResult(
    val text: String,
    val script: OcrScript,
    val blockCount: Int,
    val meanConfidence: Double?,
    val truncated: Boolean = false,
)

/** The only two things OCR can say: here is the text, or here is exactly why there is none. */
sealed interface OcrOutcome {
    data class Recognized(val result: OcrResult) : OcrOutcome

    data class Failed(val failure: OcrFailure) : OcrOutcome
}

/**
 * Deterministic merge of the two bundled recognisers' outputs. Pure Kotlin - no `android.*`, no ML Kit,
 * no state - so the decision rule is provable without a device.
 *
 * The rule is documented on this file's header. [choose] is the whole contract for callers who only
 * have strings; [decide] additionally reports which recogniser won, so the adapter can attach that
 * recogniser's real block count and confidence instead of inventing them.
 */
internal object OcrTextMerge {

    /** A minority script must reach this share of the letters before the result is called MIXED. */
    const val MIXED_MINORITY_PERCENT: Int = 20

    /** Letters (not digits, marks or punctuation) of [script] in [text]. Handles surrogate pairs. */
    fun letterCount(text: String, script: Character.UnicodeScript): Int {
        var count = 0
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            if (Character.isLetter(codePoint) && Character.UnicodeScript.of(codePoint) == script) {
                count++
            }
            index += Character.charCount(codePoint)
        }
        return count
    }

    /** Truncates at a UTF-16 boundary without leaving an unmatched high surrogate. */
    fun truncateAtCodePoint(text: String, maxChars: Int): String {
        if (text.length <= maxChars) return text
        var end = maxChars
        if (end > 0 && Character.isHighSurrogate(text[end - 1]) && Character.isLowSurrogate(text[end])) {
            end--
        }
        return text.substring(0, end)
    }

    /**
     * LATIN / DEVANAGARI / MIXED for one string.
     *
     * No letters at all (digits, punctuation, emoji) counts as [OcrScript.LATIN] - a default, not a
     * claim. When both scripts are present, a minority share of at least [MIXED_MINORITY_PERCENT]% is
     * [OcrScript.MIXED]; below that the dominant script wins, so a single English acronym inside a
     * Hindi sentence stays [OcrScript.DEVANAGARI].
     */
    fun classify(text: String): OcrScript {
        if (containsOtherLetters(text)) return OcrScript.OTHER
        val latin = letterCount(text, Character.UnicodeScript.LATIN)
        val devanagari = letterCount(text, Character.UnicodeScript.DEVANAGARI)
        val total = latin + devanagari
        if (total == 0) return OcrScript.LATIN
        val minority = minOf(latin, devanagari)
        if (minority == 0) return if (devanagari > 0) OcrScript.DEVANAGARI else OcrScript.LATIN
        if (minority * 100 >= total * MIXED_MINORITY_PERCENT) return OcrScript.MIXED
        return if (devanagari > latin) OcrScript.DEVANAGARI else OcrScript.LATIN
    }

    private fun containsOtherLetters(text: String): Boolean {
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            val script = Character.UnicodeScript.of(codePoint)
            if (Character.isLetter(codePoint) &&
                script != Character.UnicodeScript.LATIN &&
                script != Character.UnicodeScript.DEVANAGARI
            ) return true
            index += Character.charCount(codePoint)
        }
        return false
    }

    /**
     * Picks the reading to keep out of the two bundled recognisers' outputs.
     *
     * Because this helper only has strings, `blockCount` is the number of non-blank lines of the
     * winning text; the adapter replaces it with ML Kit's real block count using [Decided].
     */
    fun choose(latin: String, devanagari: String): OcrResult {
        val decided = decide(latin, devanagari)
        return OcrResult(
            text = decided.text,
            script = decided.script,
            blockCount = decided.lineCount,
            meanConfidence = null,
        )
    }

    /** Which reading won, and on what evidence. */
    data class Decided(
        val text: String,
        val script: OcrScript,
        val lineCount: Int,
        val fromDevanagari: Boolean,
    )

    fun decide(latin: String, devanagari: String): Decided {
        val latinReading = latin.trim()
        val devanagariReading = devanagari.trim()

        val latinReadingLetters = letterCount(latinReading, Character.UnicodeScript.LATIN) +
            letterCount(latinReading, Character.UnicodeScript.DEVANAGARI)
        val devanagariReadingLetters = letterCount(devanagariReading, Character.UnicodeScript.LATIN) +
            letterCount(devanagariReading, Character.UnicodeScript.DEVANAGARI)

        val devanagariAcrossBoth = letterCount(latinReading, Character.UnicodeScript.DEVANAGARI) +
            letterCount(devanagariReading, Character.UnicodeScript.DEVANAGARI)
        val latinAcrossBoth = letterCount(latinReading, Character.UnicodeScript.LATIN) +
            letterCount(devanagariReading, Character.UnicodeScript.LATIN)

        val fromDevanagari = when {
            // A reading with no letters at all cannot win just because the other reading's letters
            // happened to be of its script: a Hindi sentence with English words inside must not be
            // outvoted by an empty Latin reading.
            latinReadingLetters == 0 && devanagariReadingLetters > 0 -> true
            devanagariReadingLetters == 0 && latinReadingLetters > 0 -> false
            devanagariAcrossBoth > latinAcrossBoth -> true
            latinAcrossBoth > devanagariAcrossBoth -> false
            // No script evidence at all (digits only): the longer reading wins, an exact tie goes to Latin.
            else -> devanagariReading.length > latinReading.length
        }

        val text = if (fromDevanagari) devanagariReading else latinReading
        return Decided(
            text = text,
            script = classify(text),
            lineCount = text.lineSequence().count { it.isNotBlank() },
            fromDevanagari = fromDevanagari,
        )
    }
}

/**
 * Bundled ML Kit OCR for screenshots and shared images: Latin **and** Devanagari, offline, in memory.
 *
 * Both bundled recognisers run over the same bitmap; [OcrTextMerge] picks the reading the script
 * majority supports. Everything about privacy, bounds reuse and the merge rule is on this file's
 * header - read it before wiring a new caller.
 *
 * Instances hold two ML Kit clients, so keep one per screen/session and [close] it when the flow ends
 * ([close] is idempotent and safe to call twice). Calls after [close] return
 * [OcrFailure.MODEL_UNAVAILABLE] instead of throwing.
 *
 * This class deliberately does **not** depend on P3-02's pipeline: it returns text and an honest
 * failure, and whoever owns the pipeline decides what the text means.
 */
class MlKitTextRecognizer(policy: ImagePolicy = ImagePolicy()) : Closeable {

    private val policy: ImagePolicy = policy
    private val reader = SharedImageReader(policy)
    private val closed = AtomicBoolean(false)

    // Clients are created on first use, so validation-only calls (rejected bytes, oversized bitmaps)
    // never load a model: the honest-failure path stays cheap.
    private val latinClient: Lazy<TextRecognizer> = lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }
    private val devanagariClient: Lazy<TextRecognizer> = lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())
    }

    /** The bounds this instance validates against. Exposed so callers can state them in copy. */
    val policySummary: ImagePolicy get() = policy

    /**
     * Validates [bytes] through B07 and, only if they are accepted, decodes and recognises them.
     *
     * Rejection happens before any pixel work: [OcrFailure.TOO_LARGE], [OcrFailure.UNSUPPORTED_TYPE]
     * and [OcrFailure.CORRUPT_IMAGE] are decided from the header bytes alone. The decoded bitmap belongs
     * to this call and is recycled in a `finally` block. [declaredMime] is the sender's claim; it is
     * checked by `SharedImageReader`, never trusted.
     */
    suspend fun recognize(bytes: ByteArray, declaredMime: String? = null): OcrOutcome {
        if (closed.get()) return OcrOutcome.Failed(OcrFailure.MODEL_UNAVAILABLE)

        val image = reader.read(InMemoryByteSource(bytes), declaredMime)
        if (!image.accepted) return OcrOutcome.Failed(failureFor(image.reason))

        val bitmap = withContext(Dispatchers.Default) { decode(bytes) }
            ?: return OcrOutcome.Failed(OcrFailure.DECODE_FAILED)

        return try {
            recognizeDecoded(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * Recognises an already-decoded bitmap (camera frames, a share flow that decoded once already).
     *
     * The B07 dimension and pixel limits still apply, so a bitmap the byte policy would have rejected is
     * refused here too. **The caller owns [bitmap]**: it is not recycled by this method, and it must stay
     * valid (not recycled) until the returned [OcrOutcome] arrives.
     */
    suspend fun recognizeBitmap(bitmap: Bitmap): OcrOutcome {
        if (closed.get()) return OcrOutcome.Failed(OcrFailure.MODEL_UNAVAILABLE)
        if (bitmap.isRecycled) return OcrOutcome.Failed(OcrFailure.DECODE_FAILED)
        if (bitmap.width > policy.maxDimension ||
            bitmap.height > policy.maxDimension ||
            bitmap.width.toLong() * bitmap.height.toLong() > policy.maxPixels
        ) {
            return OcrOutcome.Failed(OcrFailure.TOO_LARGE)
        }
        return recognizeDecoded(bitmap)
    }

    /** Closes both bundled recognisers. Idempotent; safe to call twice. */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        if (latinClient.isInitialized()) runCatching { latinClient.value.close() }
        if (devanagariClient.isInitialized()) runCatching { devanagariClient.value.close() }
    }

    // ---------------------------------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------------------------------

    private suspend fun recognizeDecoded(bitmap: Bitmap): OcrOutcome = coroutineScope {
        val input = InputImage.fromBitmap(bitmap, 0)
        // Both models read the same pixels in parallel: two clients, two independent readings.
        val latinReading = async { runCatching { latinClient.value.process(input).awaitText() } }
        val devanagariReading = async { runCatching { devanagariClient.value.process(input).awaitText() } }

        val latinText = latinReading.await().getOrNull()
        val devanagariText = devanagariReading.await().getOrNull()
        if (latinText == null && devanagariText == null) {
            return@coroutineScope OcrOutcome.Failed(OcrFailure.MODEL_UNAVAILABLE)
        }

        val decided = OcrTextMerge.decide(
            latin = latinText?.text.orEmpty(),
            devanagari = devanagariText?.text.orEmpty(),
        )
        val winningText = if (decided.fromDevanagari) devanagariText else latinText

        val truncated = decided.text.length > MAX_TEXT_CHARS
        val text = OcrTextMerge.truncateAtCodePoint(decided.text, MAX_TEXT_CHARS)
        if (text.none { it.isLetterOrDigit() }) {
            return@coroutineScope OcrOutcome.Failed(OcrFailure.NO_TEXT)
        }

        OcrOutcome.Recognized(
            OcrResult(
                text = text,
                script = OcrTextMerge.classify(text),
                blockCount = winningText?.textBlocks?.size ?: 0,
                meanConfidence = winningText?.let { meanConfidence(it) },
                truncated = truncated,
            ),
        )
    }

    private fun decode(bytes: ByteArray): Bitmap? = try {
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Throwable) {
        // Includes OutOfMemoryError on a huge-but-accepted image: report a decode failure, never crash.
        null
    }

    /**
     * Mean of the element confidences ML Kit actually reported, or null.
     *
     * `Text.Element#getConfidence()` is a primitive float, so "not reported" arrives as `0.0` (or, on
     * some builds, a non-finite value). Averaging those in would fabricate a number, so only finite,
     * positive values count; when none qualifies the answer is null and callers must say that
     * confidence is unavailable rather than show a figure.
     */
    private fun meanConfidence(text: Text): Double? {
        val reported = ArrayList<Float>(16)
        for (block in text.textBlocks) {
            for (line in block.lines) {
                for (element in line.elements) {
                    val confidence = element.confidence
                    if (confidence.isFinite() && confidence > 0f) reported.add(confidence)
                }
            }
        }
        if (reported.isEmpty()) return null
        var sum = 0.0
        for (value in reported) sum += value
        return (sum / reported.size).coerceIn(0.0, 1.0)
    }

    private suspend fun Task<Text>.awaitText(): Text = suspendCancellableCoroutine { continuation ->
        addOnSuccessListener { text -> if (continuation.isActive) continuation.resume(text) }
        addOnFailureListener { error -> if (continuation.isActive) continuation.resumeWithException(error) }
    }

    /** B07 reason -> honest OCR failure. Every branch is a refusal; none of them is a verdict. */
    private fun failureFor(reason: ImageReason?): OcrFailure = when (reason) {
        ImageReason.TooLarge,
        ImageReason.TooManyPixels,
        ImageReason.TooManyImages,
        -> OcrFailure.TOO_LARGE

        ImageReason.UnsupportedType,
        ImageReason.MimeMismatch,
        -> OcrFailure.UNSUPPORTED_TYPE

        ImageReason.Corrupt,
        ImageReason.Empty,
        null,
        -> OcrFailure.CORRUPT_IMAGE
    }

    companion object {
        /**
         * In-memory cap on recognised text: 20 000 characters is far more than a screenshot or a chat
         * capture carries, and it keeps one pathological image from holding an unbounded string. Not a
         * B07 bound - a pipeline sanity limit, reported honestly through [OcrResult.truncated].
         */
        const val MAX_TEXT_CHARS: Int = 20_000
    }
}
