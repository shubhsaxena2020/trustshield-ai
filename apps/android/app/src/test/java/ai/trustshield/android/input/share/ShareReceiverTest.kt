package ai.trustshield.android.input.share

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * B01 unit tests - pure core only.
 *
 * Nothing here constructs an Intent, Uri, ContentResolver or any other android.* object:
 * android.jar method bodies are stubs that throw at runtime, and no test may touch the network
 * or a real content provider. The adapter ([intentToShare], [ContentUriByteSource]) is therefore
 * NOT covered here; it is compile-verified by :app:assembleDebug and documented as untested.
 */
class ShareReceiverTest {

    // ------------------------------------------------------------------ text intake

    @Test
    fun textOnlySendIsAnalysableText() {
        val content = SharePayloadParser.parse(
            action = ShareActions.ACTION_SEND,
            mimeType = "text/plain",
            text = "Sir aapka account block ho jayega, turant verify karein",
            subject = null,
        )
        assertTrue(content is SharedContent.Text)
        val text = content as SharedContent.Text
        assertEquals("Sir aapka account block ho jayega, turant verify karein", text.text)
        assertEquals("Sir aapka account block ho jayega, turant verify karein", content.analysableText)
        assertEquals(1, content.parts.size)
        assertEquals(SharePartKind.TEXT, content.parts[0].kind)
        assertTrue(content.isAnalysable)
    }

    @Test
    fun textAndUrlInOneFieldAreMergedButSeparatelyAddressable() {
        val content = SharePayloadParser.parse(
            action = ShareActions.ACTION_SEND,
            mimeType = "text/plain",
            text = "Yeh dekho https://bit.ly/xyz aur batao",
            subject = null,
        )
        assertTrue("expected TextAndLink, got $content", content is SharedContent.TextAndLink)
        val mixed = content as SharedContent.TextAndLink
        assertEquals("Yeh dekho aur batao", mixed.text)
        assertEquals("https://bit.ly/xyz", mixed.url)
        // Merged input keeps both, one per line, so the analyser sees text and link.
        assertEquals("Yeh dekho aur batao\nhttps://bit.ly/xyz", content.analysableText)
        assertEquals(listOf(SharePartKind.TEXT, SharePartKind.LINK), content.parts.map { it.kind })
        assertEquals("https://bit.ly/xyz", content.parts[1].raw)
    }

    @Test
    fun whatsappStyleSeparateTextAndSubjectAreBothAddressable() {
        val content = SharePayloadParser.parse(
            action = ShareActions.ACTION_SEND,
            mimeType = "text/plain",
            text = "KYC update karo",
            subject = "SBI alert",
        )
        val text = content as SharedContent.Text
        assertEquals(listOf(SharePartKind.TEXT, SharePartKind.SUBJECT), content.parts.map { it.kind })
        assertEquals("KYC update karo\nSBI alert", text.analysableText)
    }

    @Test
    fun linkOnlySendIsLink() {
        val content = SharePayloadParser.parse(
            action = ShareActions.ACTION_SEND,
            mimeType = "text/plain",
            text = "https://example.com/pay?x=1",
            subject = null,
        )
        assertTrue(content is SharedContent.Link)
        assertEquals("https://example.com/pay?x=1", (content as SharedContent.Link).url)
        assertEquals("https://example.com/pay?x=1", content.analysableText)
    }

    @Test
    fun subjectOnlySendUsesSubjectAsTextExactlyOnce() {
        val content = SharePayloadParser.parse(
            action = ShareActions.ACTION_SEND,
            mimeType = "text/plain",
            text = null,
            subject = "Aapka beta bol raha hoon, naya number hai",
        )
        assertTrue("expected Text, got $content", content is SharedContent.Text)
        assertEquals("Aapka beta bol raha hoon, naya number hai", content.analysableText)
        // The subject is not duplicated into the merged input.
        assertFalse(content.analysableText.contains("\n"))
        assertEquals(1, content.parts.size)
    }

    @Test
    fun blankTextIsBlankTextNotNoContent() {
        val blank = SharePayloadParser.parse(ShareActions.ACTION_SEND, "text/plain", "   \n\t ", null)
        assertTrue(blank is SharedContent.Empty)
        assertEquals(ShareEmptyReason.BLANK_TEXT, (blank as SharedContent.Empty).reason)
        assertEquals("blank_text", blank.reason.code)

        val nothing = SharePayloadParser.parse(ShareActions.ACTION_SEND, "text/plain", null, null)
        assertTrue(nothing is SharedContent.Empty)
        assertEquals(ShareEmptyReason.NO_CONTENT, (nothing as SharedContent.Empty).reason)
        assertFalse(nothing.isAnalysable)
    }

    @Test
    fun nullActionIsEmptyNotAnException() {
        val content = SharePayloadParser.parse(null, "text/plain", "hello", null)
        assertTrue(content is SharedContent.Empty)
        assertEquals(ShareEmptyReason.NO_ACTION, (content as SharedContent.Empty).reason)
        assertNull(IncomingShare.from(content).analysableOrNull())
    }

    @Test
    fun unsupportedActionIsEmpty() {
        val content = SharePayloadParser.parse("android.intent.action.VIEW", "text/plain", "hello", null)
        assertEquals(ShareEmptyReason.UNSUPPORTED_ACTION, (content as SharedContent.Empty).reason)
    }

    @Test
    fun devanagariAndMixedScriptTextIsPreservedByteForByte() {
        val input = "आपका खाता बंद हो जाएगा। KYC ke liye OTP 4821 bhejein, warna paisa kat jayega"
        val content = SharePayloadParser.parse(ShareActions.ACTION_SEND, "text/plain", input, null)
        val text = content as SharedContent.Text
        // No normalisation here: A02 owns digit/zero-width normalisation. Bytes in, same bytes out.
        assertEquals(input, text.text)
        assertEquals(input, content.analysableText)
        assertArrayEquals(input.toByteArray(Charsets.UTF_8), text.text.toByteArray(Charsets.UTF_8))
    }
    // ------------------------------------------------------------------ image intake

    private fun ref(
        mime: String?,
        bytes: Long?,
        uri: String? = "content://media/external/images/media/1",
        sniffed: String? = null,
        openable: Boolean = true,
    ) = SharedStreamRef(
        uriString = uri,
        declaredMime = mime,
        declaredBytes = bytes,
        openable = openable,
        sniffedMime = sniffed,
    )

    @Test
    fun singleImageSendIsImages() {
        val content = SharePayloadParser.parse(
            action = ShareActions.ACTION_SEND,
            mimeType = "image/jpeg",
            text = null,
            subject = null,
            streams = listOf(ref("image/jpeg", 512_000)),
        )
        assertTrue("expected Images, got $content", content is SharedContent.Images)
        val image = (content as SharedContent.Images).image
        assertEquals("image/jpeg", image.mime)
        assertEquals(512_000L, image.sizeBytes)
        assertTrue(content.isAnalysable)
    }

    @Test
    fun sendMultipleWithThreeImagesKeepsAllThree() {
        val content = SharePayloadParser.parse(
            action = ShareActions.ACTION_SEND_MULTIPLE,
            mimeType = "image/*",
            text = null,
            subject = null,
            streams = listOf(ref("image/png", 1000), ref("image/jpeg", 2000), ref("image/webp", 3000)),
            clipCount = 3,
        )
        assertTrue("expected MultipleImages, got $content", content is SharedContent.MultipleImages)
        val images = (content as SharedContent.MultipleImages).images
        assertEquals(3, images.size)
        assertEquals(listOf("image/png", "image/jpeg", "image/webp"), images.map { it.mime })
        assertEquals(6000L, images.sumOf { it.sizeBytes ?: 0L })
    }

    @Test
    fun sixImagesAreRejectedNotSilentlyTrimmed() {
        val six = (1..6).map { ref("image/png", 1000, uri = "content://media/external/images/media/$it") }
        val content = SharePayloadParser.parse(
            action = ShareActions.ACTION_SEND_MULTIPLE,
            mimeType = "image/*",
            text = null,
            subject = null,
            streams = six,
            clipCount = 6,
        )
        assertTrue("expected Rejected, got $content", content is SharedContent.Rejected)
        val rejected = content as SharedContent.Rejected
        assertEquals(ShareRejectReason.TOO_MANY_IMAGES, rejected.reason)
        assertEquals("too_many_images", rejected.reason.code)
        assertTrue(rejected.detail!!.contains("max=5"))
        assertFalse(content.isAnalysable)
    }

    @Test
    fun missingUriIsRejectedWithNoUri() {
        val content = SharePayloadParser.parse(
            action = ShareActions.ACTION_SEND,
            mimeType = "image/jpeg",
            text = null,
            subject = null,
            streams = listOf(ref(null, null, uri = null)),
        )
        assertEquals(ShareRejectReason.NO_URI, (content as SharedContent.Rejected).reason)
    }

    @Test
    fun imageMimeWithoutAnyHandleIsRejectedWithNoUri() {
        val content = SharePayloadParser.parse(ShareActions.ACTION_SEND, "image/png", null, null)
        assertEquals(ShareRejectReason.NO_URI, (content as SharedContent.Rejected).reason)

        val multiple = SharePayloadParser.parse(ShareActions.ACTION_SEND_MULTIPLE, "image/*", null, null)
        assertEquals(ShareRejectReason.NO_URI, (multiple as SharedContent.Rejected).reason)
    }

    @Test
    fun apkMimeIsRejectedBeforeAnythingElse() {
        val textSend = SharePayloadParser.parse(
            ShareActions.ACTION_SEND,
            "application/vnd.android.package-archive",
            "install karo",
            null,
        )
        assertEquals(ShareRejectReason.APK_ARCHIVE, (textSend as SharedContent.Rejected).reason)

        val streamSend = SharePayloadParser.parse(
            ShareActions.ACTION_SEND_MULTIPLE,
            "image/*",
            null,
            null,
            streams = listOf(ref("application/vnd.android.package-archive", 100)),
        )
        assertEquals(ShareRejectReason.APK_ARCHIVE, (streamSend as SharedContent.Rejected).reason)
    }

    @Test
    fun htmlMimeIsRejected() {
        val content = SharePayloadParser.parse(ShareActions.ACTION_SEND, "text/html; charset=utf-8", "<html/>", null)
        assertEquals(ShareRejectReason.HTML_DOCUMENT, (content as SharedContent.Rejected).reason)
    }

    @Test
    fun declaredImageWithNonImageBytesIsRejected() {
        val content = SharePayloadParser.parse(
            action = ShareActions.ACTION_SEND_MULTIPLE,
            mimeType = "image/*",
            text = null,
            subject = null,
            streams = listOf(ref("image/png", 4096, sniffed = "application/pdf")),
            clipCount = 1,
        )
        assertEquals(ShareRejectReason.DECLARED_IMAGE_NOT_IMAGE, (content as SharedContent.Rejected).reason)
    }

    @Test
    fun declaredImageWithHtmlBytesIsStillRejected() {
        val content = SharePayloadParser.parse(
            action = ShareActions.ACTION_SEND_MULTIPLE,
            mimeType = "image/*",
            text = null,
            subject = null,
            streams = listOf(ref("image/png", 4096, sniffed = "text/html")),
            clipCount = 1,
        )
        assertTrue("expected Rejected, got $content", content is SharedContent.Rejected)
        val code = (content as SharedContent.Rejected).reason.code
        assertTrue("unexpected code $code", code == "html_document" || code == "declared_image_not_image")
    }

    @Test
    fun imageTypeOutsideAllowlistIsRejected() {
        val content = SharePayloadParser.parse(
            action = ShareActions.ACTION_SEND,
            mimeType = "image/gif",
            text = null,
            subject = null,
            streams = listOf(ref("image/gif", 1024)),
        )
        assertEquals(ShareRejectReason.IMAGE_TYPE_NOT_ALLOWED, (content as SharedContent.Rejected).reason)
    }

    @Test
    fun oversizedSingleStreamIsRejected() {
        val nineMb = 9L * 1024L * 1024L
        val content = SharePayloadParser.parse(
            action = ShareActions.ACTION_SEND,
            mimeType = "image/jpeg",
            text = null,
            subject = null,
            streams = listOf(ref("image/jpeg", nineMb)),
        )
        assertEquals(ShareRejectReason.OVERSIZED, (content as SharedContent.Rejected).reason)
    }

    @Test
    fun manyFilesUnderLimitIndividuallyButOverInTotalAreRejected() {
        val threeMb = 3L * 1024L * 1024L
        val content = SharePayloadParser.parse(
            action = ShareActions.ACTION_SEND_MULTIPLE,
            mimeType = "image/*",
            text = null,
            subject = null,
            streams = (1..3).map { ref("image/jpeg", threeMb, uri = "content://m/$it") },
            clipCount = 3,
        )
        assertEquals(ShareRejectReason.OVERSIZED, (content as SharedContent.Rejected).reason)
    }

    @Test
    fun unopenableHandleIsRejected() {
        val content = SharePayloadParser.parse(
            action = ShareActions.ACTION_SEND,
            mimeType = "image/png",
            text = null,
            subject = null,
            streams = listOf(ref("image/png", 100, openable = false)),
        )
        assertEquals(ShareRejectReason.UNOPENABLE_URI, (content as SharedContent.Rejected).reason)
    }
    // ------------------------------------------------------------------ explicit review gate

    @Test
    fun pendingReviewBlocksAnalysisAndAcceptAllowsIt() {
        val content = SharePayloadParser.parse(
            ShareActions.ACTION_SEND,
            "text/plain",
            "OTP 123456 batao warna account band",
            null,
        )
        val share = IncomingShare.from(content)

        assertTrue(share.requiresReview)
        assertEquals(ReviewState.Pending, share.reviewState)
        assertFalse("pending share must not be analysable", share.isAnalysable)
        assertNull(share.analysableOrNull())

        val accepted = share.accept()
        assertEquals(ReviewState.Accepted, accepted.reviewState)
        assertTrue(accepted.isAnalysable)
        assertEquals("OTP 123456 batao warna account band", accepted.analysableOrNull())
        // The original object is untouched: accepting is not a mutation side effect.
        assertEquals(ReviewState.Pending, share.reviewState)
    }

    @Test
    fun discardDropsThePayloadReference() {
        val content = SharePayloadParser.parse(
            ShareActions.ACTION_SEND,
            "text/plain",
            "Card 4111 1111 1111 1111 ka CVV bhejo",
            null,
        )
        val discarded = IncomingShare.from(content).accept().discard()

        assertEquals(ReviewState.Discarded, discarded.reviewState)
        assertFalse(discarded.requiresReview)
        assertTrue(discarded.content is SharedContent.Empty)
        assertEquals(ShareEmptyReason.DISCARDED, (discarded.content as SharedContent.Empty).reason)
        assertFalse(discarded.isAnalysable)
        assertNull(discarded.analysableOrNull())
        // The raw payload is no longer reachable through the share, in fields or in toString.
        assertFalse(discarded.toString().contains("4111"))
        assertFalse(discarded.toString().contains("CVV"))
    }

    @Test
    fun rejectedAndEmptySharesDoNotAskForReview() {
        val rejected = IncomingShare.from(
            SharePayloadParser.parse(ShareActions.ACTION_SEND, "text/html", "x", null),
        )
        assertFalse(rejected.requiresReview)
        assertFalse(rejected.isAnalysable)

        val empty = IncomingShare.from(SharePayloadParser.parse(null, "text/plain", "x", null))
        assertFalse(empty.requiresReview)
        assertFalse(empty.isAnalysable)
    }

    @Test
    fun imagesRequireReviewBeforeAnyByteIsRead() {
        val content = SharePayloadParser.parse(
            ShareActions.ACTION_SEND,
            "image/jpeg",
            null,
            null,
            streams = listOf(ref("image/jpeg", 2048)),
        )
        val pending = IncomingShare.from(content)
        assertTrue(pending.requiresReview)
        assertEquals(ReviewState.Pending, pending.reviewState)
        assertFalse(pending.isAnalysable)
        assertTrue(pending.accept().isAnalysable)
    }

    // ------------------------------------------------------------------ bounds and redaction

    @Test
    fun byteBudgetEnforcesTheTotalCeiling() {
        val budget = ByteBudget(limit = 10L)
        assertEquals(10L, budget.remaining)

        assertTrue(budget.tryConsume(8L))
        assertEquals(8L, budget.consumed)
        assertEquals(2L, budget.remaining)
        // The next chunk would cross the ceiling: refused, and nothing is consumed.
        assertFalse(budget.tryConsume(3L))
        assertEquals(8L, budget.consumed)
        assertFalse(budget.tryConsume(-1L))

        try {
            budget.consume(3L)
            fail("expected ShareBoundExceeded")
        } catch (e: ShareBoundExceeded) {
            assertEquals(3L, e.read)
            assertEquals(2L, e.remaining)
            assertEquals(10L, e.limit)
        }
    }

    @Test
    fun defaultBudgetIsEightMegabytes() {
        assertEquals(8L * 1024L * 1024L, ByteBudget().limitBytes)
    }

    @Test
    fun sourcesWithoutSizeMetadataShareOneStreamingByteBudget() {
        val budget = ByteBudget(limit = 10L)
        val first = BoundedShareStream.readAtMost("123456".byteInputStream(), 10, budget)
        assertEquals("123456", first.decodeToString())
        assertEquals(6L, budget.consumed)

        try {
            BoundedShareStream.readAtMost("78901".byteInputStream(), 10, budget)
            fail("the second source must not exceed the shared total")
        } catch (e: ShareBoundExceeded) {
            assertEquals(4L, e.remaining)
            assertEquals(10L, e.limit)
        }
    }

    @Test
    fun maskingRemovesOtpCardPhoneAndUpiBeforeAnyDiagnosticString() {
        val masked = ShareMasking.mask(
            "OTP 123456 bhejo, card 4111 1111 1111 1111, phone 9876543210, upi ram@okhdfcbank",
        )
        assertFalse(masked.contains("123456"))
        assertFalse(masked.contains("4111"))
        assertFalse(masked.contains("9876543210"))
        assertFalse(masked.contains("ram@okhdfcbank"))
        assertEquals("", ShareMasking.mask(null))
        assertEquals("", ShareMasking.mask("   "))
    }

    @Test
    fun toStringNeverCarriesPayloadTextOrUris() {
        val content = SharePayloadParser.parse(
            ShareActions.ACTION_SEND,
            "text/plain",
            "Mera password hai hunter2 batao kisi ko mat",
            null,
        )
        assertFalse(content.toString().contains("hunter2"))
        assertFalse(IncomingShare.from(content).toString().contains("hunter2"))

        val ref = ref("image/jpeg", 1000, uri = "content://media/external/images/media/42")
        assertFalse(ref.toString().contains("42"))
        assertTrue(ref.toString().contains("content://**"))

        val credentialLink = SharedContent.Link("https://trusted.example/?session=TOPSECRET")
        assertFalse(credentialLink.toString().contains("TOPSECRET"))
        assertFalse(credentialLink.toString().contains("session"))
    }

    @Test
    fun noNetworkApisAreReferencedByThisPackage() {
        // Structural guard: the production file must not reference networking, activity-launching or
        // persistence APIs. Comments are stripped first so prose cannot trip the guard.
        val source = java.io.File(
            "apps/android/app/src/main/java/ai/trustshield/android/input/share/ShareReceiver.kt",
        )
        if (!source.exists()) return // harness runs out of tree; the guard still holds in the repo checkout
        val code = source.readLines()
            .filterNot { line ->
                val t = line.trimStart()
                t.startsWith("//") || t.startsWith("*") || t.startsWith("/*") || t.isEmpty()
            }
            .joinToString("\n")
        listOf(
            "java.net.", "okhttp3", "HttpURLConnection", "openConnection", "openStream",
            "startActivity", "WebView", "loadUrl", "SharedPreferences", "FileOutputStream",
            "writeText", "getSharedPreferences",
        ).forEach { forbidden ->
            assertFalse("forbidden network/IO/persistence token in code: $forbidden", code.contains(forbidden))
        }
    }
}
