/*
 * TrustShield Android - P3-04 (issue #83): instrumented proof for the bundled ML Kit OCR adapter.
 *
 * What this suite proves on a device:
 *  - a rendered Latin sentence is recognised as LATIN text;
 *  - a rendered Devanagari sentence is recognised as Devanagari script (bundled Devanagari model);
 *  - a blank bitmap yields an honest NO_TEXT failure instead of an empty "success";
 *  - a 16-byte truncated PNG header is rejected as CORRUPT_IMAGE before any decode;
 *  - bytes over the B07 byte budget are rejected as TOO_LARGE (checked with a 64-byte policy, so the
 *    test stays fast and never allocates 8 MiB);
 *  - plain text bytes are rejected as UNSUPPORTED_TYPE;
 *  - the deterministic merge rule (OcrTextMerge.choose) behaves on plain strings - pure, no device;
 *  - close() is idempotent.
 *
 * Every bitmap in this file is synthetic, drawn in-test with Canvas/Paint and recycled immediately.
 * The sentences are invented demo strings, not real personal data. Nothing here writes a file, opens a
 * socket or logs recognised content: failure messages name the outcome, never the payload.
 */
package ai.trustshield.android.input.share

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MlKitTextRecognizerTest {

    // ---------------------------------------------------------------------------------------------
    // Synthetic image helpers. Real pixels, drawn locally; nothing is read from disk or the network.
    // ---------------------------------------------------------------------------------------------

    private fun renderLine(
        text: String,
        width: Int = 1600,
        height: Int = 260,
        textSize: Float = 64f,
    ): Bitmap {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            this.textSize = textSize
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        }
        val measured = paint.measureText(text)
        assertTrue(
            "test fixture too narrow for the drawn sentence (${measured.toInt()}px)",
            measured < width - 80,
        )
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(Color.WHITE)
            drawText(text, 40f, height / 2f + textSize / 3f, paint)
        }
        return bitmap
    }

    private fun blankBitmap(width: Int = 600, height: Int = 240): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
            Canvas(bitmap).drawColor(Color.WHITE)
        }

    private fun pngBytes(bitmap: Bitmap): ByteArray {
        val out = ByteArrayOutputStream()
        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) { "PNG encode failed" }
        return out.toByteArray()
    }

    /** A PNG signature plus eight filler bytes: a container we recognise but cannot read. */
    private fun truncatedPngHeader(): ByteArray = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
    )

    private fun recognized(outcome: OcrOutcome): OcrResult {
        assertTrue("expected Recognized, got $outcome", outcome is OcrOutcome.Recognized)
        return (outcome as OcrOutcome.Recognized).result
    }

    private fun failure(outcome: OcrOutcome): OcrFailure {
        assertTrue("expected Failed, got $outcome", outcome is OcrOutcome.Failed)
        return (outcome as OcrOutcome.Failed).failure
    }

    // ---------------------------------------------------------------------------------------------
    // Bundled models: Latin
    // ---------------------------------------------------------------------------------------------

    @Test
    fun latinBitmap_isRecognizedAsLatin(): Unit = runBlocking {
        val bitmap = renderLine("Your KYC is pending. OTP batao.")
        val outcome = MlKitTextRecognizer().use { it.recognizeBitmap(bitmap) }
        bitmap.recycle()

        val result = recognized(outcome)
        assertEquals(OcrScript.LATIN, result.script)
        assertTrue("blockCount should be at least one", result.blockCount >= 1)
        assertTrue(
            "Latin sentence was not recovered as expected",
            result.text.uppercase().contains("KYC"),
        )
        assertTrue(
            "Latin sentence lost the second half",
            result.text.uppercase().contains("OTP"),
        )
    }

    // ---------------------------------------------------------------------------------------------
    // Bundled models: Devanagari
    // ---------------------------------------------------------------------------------------------

    @Test
    fun devanagariBitmap_isRecognizedAsDevanagari(): Unit = runBlocking {
        val bitmap = renderLine("आपका खाता बंद हो जाएगा")
        val outcome = MlKitTextRecognizer().use { it.recognizeBitmap(bitmap) }
        bitmap.recycle()

        val result = recognized(outcome)
        assertEquals(
            "expected a Devanagari-script result",
            OcrScript.DEVANAGARI,
            result.script,
        )
        assertTrue("blockCount should be at least one", result.blockCount >= 1)
        assertTrue(
            "Devanagari letters were not recovered",
            OcrTextMerge.letterCount(result.text, Character.UnicodeScript.DEVANAGARI) >= 5,
        )
        // Honesty check: a confidence, when reported, is a real 0..1 measurement and never invented.
        result.meanConfidence?.let { mean ->
            assertTrue("meanConfidence out of range", mean in 0.0..1.0)
        }
    }

    @Test
    fun blankBitmap_isNoText(): Unit = runBlocking {
        val bitmap = blankBitmap()
        val outcome = MlKitTextRecognizer().use { it.recognizeBitmap(bitmap) }
        bitmap.recycle()

        assertEquals(OcrFailure.NO_TEXT, failure(outcome))
    }

    // ---------------------------------------------------------------------------------------------
    // B07 bounds are honoured before any pixel work
    // ---------------------------------------------------------------------------------------------

    @Test
    fun truncatedPngHeader_isCorruptImage(): Unit = runBlocking {
        val outcome = MlKitTextRecognizer().use { it.recognize(truncatedPngHeader()) }
        assertEquals(OcrFailure.CORRUPT_IMAGE, failure(outcome))
    }

    @Test
    fun bytesOverPolicy_isTooLarge(): Unit = runBlocking {
        // A 64-byte budget instead of 8 MiB keeps this fast and proves the same branch.
        val tight = MlKitTextRecognizer(ImagePolicy(maxBytes = 64))
        val small = renderLine("Hello", width = 200, height = 80, textSize = 24f)
        val bytes = pngBytes(small)
        small.recycle()
        assertTrue("fixture PNG should exceed the 64-byte policy", bytes.size > 64)

        val outcome = tight.use { it.recognize(bytes, declaredMime = "image/png") }
        assertEquals(OcrFailure.TOO_LARGE, failure(outcome))
    }

    @Test
    fun nonImageBytes_isUnsupportedType(): Unit = runBlocking {
        val bytes = "this is a chat message, not an image".toByteArray()
        val outcome = MlKitTextRecognizer().use { it.recognize(bytes) }
        assertEquals(OcrFailure.UNSUPPORTED_TYPE, failure(outcome))
    }

    @Test
    fun declaredImageTypeThatDisagreesWithTheContainer_isRejected(): Unit = runBlocking {
        val small = renderLine("Hello", width = 200, height = 80, textSize = 24f)
        val bytes = pngBytes(small)
        small.recycle()

        val outcome = MlKitTextRecognizer().use { it.recognize(bytes, declaredMime = "image/jpeg") }
        assertEquals(OcrFailure.UNSUPPORTED_TYPE, failure(outcome))
    }

    // ---------------------------------------------------------------------------------------------
    // Deterministic merge rule (pure)
    // ---------------------------------------------------------------------------------------------

    @Test
    fun choose_prefersLatinWhenOnlyLatinScriptIsPresent() {
        val result = OcrTextMerge.choose(
            latin = "Your KYC is pending. OTP batao.",
            devanagari = "",
        )
        assertEquals(OcrScript.LATIN, result.script)
        assertEquals("Your KYC is pending. OTP batao.", result.text)
        assertNull("no confidence may be invented", result.meanConfidence)
    }

    @Test
    fun choose_prefersDevanagariWhenTheScriptMajorityIsDevanagari() {
        val result = OcrTextMerge.choose(
            latin = "TGTG",
            devanagari = "आपका खाता बंद हो जाएगा",
        )
        assertEquals(OcrScript.DEVANAGARI, result.script)
        assertEquals("आपका खाता बंद हो जाएगा", result.text)
        assertNull("no confidence may be invented", result.meanConfidence)
    }

    @Test
    fun choose_neverPrefersAnEmptyReadingOverOneThatHasLetters() {
        // Found by the first green run: a Devanagari reading that contains English words must not lose
        // to an empty Latin reading just because those English letters "belong" to Latin.
        val result = OcrTextMerge.choose(latin = "", devanagari = "KYC OTP बंद")
        assertEquals("KYC OTP बंद", result.text)
    }

    @Test
    fun choose_marksARealScriptMixAsMixed() {
        val result = OcrTextMerge.choose(
            latin = "",
            devanagari = "OTP batao आपका खाता",
        )
        assertEquals(OcrScript.MIXED, result.script)
        assertEquals("OTP batao आपका खाता", result.text)
    }

    @Test
    fun choose_prefersTheLongerTextWhenNeitherScriptIsPresent() {
        // Digits only: no letters to vote with, so the longer reading wins and stays honest.
        val result = OcrTextMerge.choose(latin = "1234", devanagari = "123456")
        assertEquals("123456", result.text)
        assertEquals(1, result.blockCount)
    }

    @Test
    fun choose_emptyInput_producesAnEmptyLatinResult() {
        val result = OcrTextMerge.choose("", "")
        assertEquals("", result.text)
        assertEquals(OcrScript.LATIN, result.script)
        assertEquals(0, result.blockCount)
        assertNull(result.meanConfidence)
    }

    @Test
    fun letterCount_ignoresDigitsAndPunctuation() {
        assertEquals(3, OcrTextMerge.letterCount("OTP: 1234!", Character.UnicodeScript.LATIN))
        // Combining marks (ा) are not letters, so आपका contributes three.
        assertEquals(
            3,
            OcrTextMerge.letterCount("OTP आपका", Character.UnicodeScript.DEVANAGARI),
        )
    }

    // ---------------------------------------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------------------------------------

    @Test
    fun closeTwice_doesNotThrow() {
        val recognizer = MlKitTextRecognizer()
        assertNotNull(recognizer)
        recognizer.close()
        recognizer.close()
    }

    @Test
    fun recognizeAfterClose_failsHonestly(): Unit = runBlocking {
        val recognizer = MlKitTextRecognizer()
        recognizer.close()
        val outcome = recognizer.recognize(truncatedPngHeader())
        assertEquals(OcrFailure.MODEL_UNAVAILABLE, failure(outcome))
    }
}
